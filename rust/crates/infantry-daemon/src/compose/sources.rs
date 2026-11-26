// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! user-added plugin sources, stored on the daemon so every connected client (desktop, web, phone) sees the same
//! list. A *source* is laid out like the plugins folder (`<source>/{official,core}/<Module>/module.json`) and is either a
//! local folder or a git repository (cloned under `<state>/plugin-sources/`, refreshed with a fast-forward pull). The list
//! is `plugin-sources.json` in the state dir. Core names no source: the built-in ones (the bundled folder and the
//! `plugins.sources` setting) stay in [`super::installer::InstallConfig`] and are merged with these by the caller.

use super::installer;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

pub const SOURCES_FILE: &str = "plugin-sources.json";
pub const CACHE_DIR: &str = "plugin-sources";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Source {
    pub id: String,
    /// `folder` or `git`.
    pub kind: String,
    /// The folder path, or the git URL.
    pub location: String,
    /// A badge the client shows (e.g. `Official`), set when the source came from the default-sources list.
    pub label: Option<String>,
}

/// The file in the plugins folder that names the sources a host starts with (data the release stages, never code):
/// `{ "sources": [ { "location": "<git url or folder>", "label": "Official" } ] }`. `INFANTRY_DEFAULT_SOURCES` names
/// another file. Each location is added once; one the user removed is not added again.
pub const DEFAULTS_FILE: &str = "default-sources.json";
pub const DEFAULTS_ENV: &str = "INFANTRY_DEFAULT_SOURCES";
/// The locations already seeded from the defaults file (kept so a removed default stays removed).
pub const SEEDED_FILE: &str = "plugin-sources-seeded.json";

/// The defaults file to seed from: the one `INFANTRY_DEFAULT_SOURCES` names, else the first of `dirs` that holds
/// [`DEFAULTS_FILE`] (the plugins dir, then the bundled folder: OS-app mode keeps only modules in the plugins dir).
pub fn defaults_file(named: Option<PathBuf>, dirs: &[Option<PathBuf>]) -> Option<PathBuf> {
    named.or_else(|| dirs.iter().flatten().map(|d| d.join(DEFAULTS_FILE)).find(|f| f.is_file()))
}

pub struct SourceStore {
    file: PathBuf,
    cache: PathBuf,
    items: Mutex<Vec<Source>>,
    /// The largest file a catalog download may be (`catalog.maxFileBytes`), set by the composition at start.
    max_bytes: std::sync::atomic::AtomicU64,
}

/// A stored folder location without the Windows verbatim prefix (older state files kept `\?\C:\...`).
fn plain_location(kind: &str, location: &str) -> String {
    if kind == "folder" { installer::plain(Path::new(location)).display().to_string() } else { location.to_string() }
}

fn slug(location: &str) -> String {
    let base: String = location.trim_end_matches(['/', '\\']).trim_end_matches(".git").rsplit(['/', '\\', ':']).next().unwrap_or("").chars().map(|c| if c.is_ascii_alphanumeric() || c == '-' { c } else { '-' }).take(32).collect();
    let h = Sha256::digest(location.as_bytes());
    let short: String = h.iter().take(3).map(|b| format!("{b:02x}")).collect();
    format!("{}-{short}", if base.is_empty() { "source" } else { &base })
}

/// An http(s) address that is not a git repository: it names a catalog document (a `.json` file).
fn is_catalog_url(location: &str) -> bool {
    (location.starts_with("http://") || location.starts_with("https://")) && location.split(['?', '#']).next().unwrap_or("").ends_with(".json")
}

fn is_git(location: &str) -> bool {
    location.ends_with(".git") || ["http://", "https://", "git@", "ssh://", "git://", "file://"].iter().any(|p| location.starts_with(p))
}

/// The setting that caps the largest single file a catalog (plugin sources and the Armory apps catalog) may make the
/// daemon download, and its default.
pub const MAX_FILE_BYTES_KEY: &str = "catalog.maxFileBytes";
pub const DEFAULT_MAX_FILE_BYTES: u64 = 64 * 1024 * 1024;

/// A setting value that is a positive whole number (a number or a numeric string).
pub fn limit_of(v: Option<Value>) -> u64 {
    v.and_then(|v| v.as_u64().or_else(|| v.as_str().and_then(|s| s.trim().parse().ok()))).filter(|n| *n > 0).unwrap_or(DEFAULT_MAX_FILE_BYTES)
}

/// One GET with a size cap and a timeout; the single download seam of the catalogs.
pub fn http_get(agent: &ureq::Agent, url: &str, max_bytes: u64) -> Result<Vec<u8>, String> {
    use std::io::Read;
    let resp = agent.get(url).timeout(std::time::Duration::from_secs(60)).call().map_err(|e| format!("{url}: {e}"))?;
    let mut body = Vec::new();
    resp.into_reader().take(max_bytes + 1).read_to_end(&mut body).map_err(|e| format!("{url}: {e}"))?;
    if body.len() as u64 > max_bytes {
        return Err(format!("{url}: larger than {max_bytes} bytes"));
    }
    Ok(body)
}

/// A catalog document is `{ "modules": [ { "name": "Id", "tier": "official", "base": "<url or path next to the catalog>",
/// "files": ["module.json", "ui/dist/index.js", ...] } ] }`. Each module's files are downloaded into `dest/<tier>/<name>/`,
/// so a catalog source behaves like a folder source from then on (search, vet, install, update). Built in a staging folder
/// and swapped in, so a failed refresh keeps the previous copy.
pub fn fetch_catalog(url: &str, dest: &Path, max_bytes: u64) -> Result<usize, String> {
    let tls = native_tls::TlsConnector::new().map_err(|e| format!("no TLS for catalog downloads: {e}"))?;
    let agent = ureq::AgentBuilder::new().tls_connector(std::sync::Arc::new(tls)).build();
    let doc: Value = serde_json::from_slice(&http_get(&agent, url, max_bytes)?).map_err(|_| format!("{url} is not a catalog (JSON with a `modules` array)"))?;
    let modules = doc.get("modules").and_then(Value::as_array).ok_or_else(|| format!("{url} is not a catalog (JSON with a `modules` array)"))?;
    let staging = dest.with_extension("new");
    let _ = std::fs::remove_dir_all(&staging);
    std::fs::create_dir_all(&staging).map_err(|e| e.to_string())?;
    let run = || -> Result<usize, String> {
        for m in modules {
            let name = m.get("name").and_then(Value::as_str).unwrap_or("");
            let tier = m.get("tier").and_then(Value::as_str).unwrap_or("official");
            if !installer::safe_name(name) || !super::plugins::TIERS.contains(&tier) {
                return Err(format!("catalog entry '{name}' ({tier}) is not a name or tier a module installs under"));
            }
            let base = m.get("base").and_then(Value::as_str).unwrap_or("");
            let base = if base.contains("://") { base.to_string() } else { format!("{}/{}", url.rsplit_once('/').map(|x| x.0).unwrap_or(url), base.trim_start_matches('/')) };
            for f in m.get("files").and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_str) {
                let local = Path::new(f);
                if local.is_absolute() || local.components().any(|c| !matches!(c, std::path::Component::Normal(_))) {
                    return Err(format!("{name}: file '{f}' is outside the module folder"));
                }
                let bytes = http_get(&agent, &format!("{}/{f}", base.trim_end_matches('/')), max_bytes)?;
                let target = staging.join(tier).join(name).join(local);
                std::fs::create_dir_all(target.parent().unwrap_or(&staging)).map_err(|e| e.to_string())?;
                std::fs::write(&target, bytes).map_err(|e| format!("{}: {e}", target.display()))?;
            }
        }
        Ok(modules.len())
    };
    match run() {
        Ok(n) => {
            let _ = std::fs::remove_dir_all(dest);
            std::fs::rename(&staging, dest).map_err(|e| format!("{}: {e}", dest.display()))?;
            Ok(n)
        }
        Err(e) => {
            let _ = std::fs::remove_dir_all(&staging);
            Err(e)
        }
    }
}

fn git(args: &[&str], cwd: Option<&Path>) -> Result<String, String> {
    let mut cmd = infantry_proc::command("git");
    cmd.args(args).env("GIT_TERMINAL_PROMPT", "0");
    if let Some(c) = cwd {
        cmd.current_dir(c);
    }
    let out = cmd.output().map_err(|e| format!("git: {e}"))?;
    if out.status.success() {
        Ok(String::from_utf8_lossy(&out.stdout).trim().to_string())
    } else {
        Err(format!("git {}: {}", args.first().unwrap_or(&""), String::from_utf8_lossy(&out.stderr).trim()))
    }
}

/// CI-TESTS-RED-1: two spellings of one folder (an 8.3 short name, a junction, a trailing separator) are the same
/// location. A folder source is stored canonical while the defaults file and the seeded list keep the text as written.
fn same_location(a: &str, b: &str) -> bool {
    let canon = |p: &str| std::fs::canonicalize(p).ok().map(|c| installer::plain(&c).display().to_string());
    a == b || matches!((canon(a), canon(b)), (Some(x), Some(y)) if x == y)
}

impl SourceStore {
    pub fn load(state_dir: &Path) -> SourceStore {
        let file = state_dir.join(SOURCES_FILE);
        let items = std::fs::read_to_string(&file)
            .ok()
            .and_then(|t| serde_json::from_str::<Value>(&t).ok())
            .and_then(|v| v.as_array().cloned())
            .unwrap_or_default()
            .iter()
            .filter_map(|s| Some(Source { id: s["id"].as_str()?.into(), kind: s["kind"].as_str()?.into(), location: plain_location(s["kind"].as_str()?, s["location"].as_str()?), label: s["label"].as_str().map(str::to_string) }))
            .collect();
        SourceStore { file, cache: state_dir.join(CACHE_DIR), items: Mutex::new(items), max_bytes: std::sync::atomic::AtomicU64::new(DEFAULT_MAX_FILE_BYTES) }
    }

    pub fn set_max_bytes(&self, n: u64) {
        self.max_bytes.store(n, std::sync::atomic::Ordering::Relaxed);
    }

    fn max_bytes(&self) -> u64 {
        self.max_bytes.load(std::sync::atomic::Ordering::Relaxed)
    }

    fn save(&self, items: &[Source]) -> Result<(), String> {
        let v: Vec<Value> = items.iter().map(|s| {
            let mut v = json!({ "id": s.id, "kind": s.kind, "location": s.location });
            if let Some(l) = &s.label {
                v["label"] = json!(l);
            }
            v
        }).collect();
        super::host_frames::write_json(&self.file, &Value::Array(v))
    }

    fn dir_of(&self, s: &Source) -> PathBuf {
        if s.kind != "folder" {
            self.cache.join(&s.id)
        } else {
            PathBuf::from(&s.location)
        }
    }

    /// The folders the stored sources offer modules from (a source that is gone is skipped, not an error).
    pub fn dirs(&self) -> Vec<PathBuf> {
        self.items.lock().unwrap_or_else(|e| e.into_inner()).iter().map(|s| self.dir_of(s)).filter(|d| d.is_dir()).collect()
    }

    /// Every source with how many modules it offers (`-1` offers: the folder is missing).
    pub fn list(&self) -> Vec<Value> {
        self.items
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .iter()
            .map(|s| {
                let dir = self.dir_of(s);
                let offers = if dir.is_dir() { installer::catalog(&[dir.clone()]).len() as i64 } else { -1 };
                json!({ "id": s.id, "kind": s.kind, "location": s.location, "label": s.label, "dir": dir.display().to_string(), "offers": offers })
            })
            .collect()
    }

    /// Adds a local folder or a git repository. Refused when it is already listed, cannot be read or offers no module.
    pub fn add(&self, location: &str) -> Result<Source, String> {
        self.add_labelled(location, None)
    }

    fn add_labelled(&self, location: &str, label: Option<String>) -> Result<Source, String> {
        let location = location.trim().strip_prefix("file://").filter(|l| Path::new(l).is_dir()).unwrap_or(location.trim()).to_string();
        if location.is_empty() {
            return Err("usage: source-add <folder or git url>".into());
        }
        let mut items = self.items.lock().unwrap_or_else(|e| e.into_inner());
        let canon = |p: &str| std::fs::canonicalize(p).map(|c| installer::plain(&c).display().to_string()).unwrap_or_else(|_| p.to_string());
        let (kind, location) = if Path::new(&location).is_dir() {
            ("folder", canon(&location))
        } else if is_catalog_url(&location) {
            ("catalog", location)
        } else if is_git(&location) {
            ("git", location)
        } else {
            return Err(format!("{location} is neither a folder on this machine nor a git url"));
        };
        let src = Source { id: slug(&location), kind: kind.into(), location, label };
        if items.iter().any(|s| s.id == src.id) {
            return Err(format!("{} is already a source", src.location));
        }
        let dir = self.dir_of(&src);
        if src.kind != "folder" {
            std::fs::create_dir_all(&self.cache).map_err(|e| e.to_string())?;
            let _ = std::fs::remove_dir_all(&dir);
            if src.kind == "git" {
                git(&["clone", "--depth", "1", &src.location, &dir.display().to_string()], None)?;
            } else {
                fetch_catalog(&src.location, &dir, self.max_bytes())?;
            }
        }
        if installer::catalog(&[dir.clone()]).is_empty() {
            if src.kind != "folder" {
                let _ = std::fs::remove_dir_all(&dir);
            }
            return Err(format!("{} offers no module (expected <source>/official/<Module>/module.json or <source>/core/<Module>/module.json)", src.location));
        }
        items.push(src.clone());
        if let Err(e) = self.save(&items) {
            items.pop();
            return Err(e);
        }
        Ok(src)
    }

    /// Adds each source the defaults file names that was never seeded on this host. One already listed (the user
    /// added it by hand) is only marked seeded; one that fails (offline) stays unseeded and is tried at the next start.
    /// Returns one line per source tried. A missing or unreadable defaults file seeds nothing.
    pub fn seed_defaults(&self, defaults: &Path) -> Vec<String> {
        let Some(list) = std::fs::read_to_string(defaults).ok().and_then(|t| serde_json::from_str::<Value>(&t).ok()) else { return Vec::new() };
        let seeded_file = self.file.with_file_name(SEEDED_FILE);
        let mut seeded: Vec<String> = std::fs::read_to_string(&seeded_file).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
        let mut lines = Vec::new();
        for d in list["sources"].as_array().cloned().unwrap_or_default() {
            let Some(loc) = d["location"].as_str().map(str::trim).filter(|l| !l.is_empty()) else { continue };
            if seeded.iter().any(|s| s == loc) {
                // A host that already seeded the new default may still list the old one it replaces.
                lines.extend(self.drop_replaced(&d, &seeded));
                continue;
            }
            let label = d["label"].as_str().map(str::to_string);
            let listed = self.items.lock().unwrap_or_else(|e| e.into_inner()).iter().any(|s| s.location == loc || s.id == slug(loc));
            let ok = if listed {
                lines.push(format!("{loc}: already a source"));
                true
            } else {
                match self.add_labelled(loc, label) {
                    Ok(s) => {
                        lines.push(format!("{loc}: added as {}", s.id));
                        true
                    }
                    Err(e) => {
                        lines.push(format!("{loc}: not added yet ({e})"));
                        false
                    }
                }
            };
            if ok {
                seeded.push(loc.to_string());
                let _ = super::host_frames::write_json(&seeded_file, &json!(seeded));
                lines.extend(self.drop_replaced(&d, &seeded));
            }
        }
        lines
    }

    /// PLUGIN-GAPS-1: a default entry may name the old locations it `replaces` (data in the defaults file, never code).
    /// An old location is dropped only when this host seeded it from the defaults (it is in the seeded list) and it is
    /// still listed with a label (the seeded default): a source the user added by hand has no label and is kept.
    fn drop_replaced(&self, entry: &Value, seeded: &[String]) -> Vec<String> {
        let mut lines = Vec::new();
        for old in entry["replaces"].as_array().cloned().unwrap_or_default() {
            let Some(old) = old.as_str().map(str::trim).filter(|o| !o.is_empty()) else { continue };
            if !seeded.iter().any(|s| same_location(s, old)) {
                continue;
            }
            let id = self.items.lock().unwrap_or_else(|e| e.into_inner()).iter().find(|s| same_location(&s.location, old) && s.label.is_some()).map(|s| s.id.clone());
            if let Some(id) = id {
                match self.remove(&id) {
                    Ok(_) => lines.push(format!("{old}: replaced by the new default")),
                    Err(e) => lines.push(format!("{old}: not replaced ({e})")),
                }
            }
        }
        lines
    }

    /// Drops a source by id or location. A cloned repository is deleted; a local folder is never touched.
    pub fn remove(&self, key: &str) -> Result<Source, String> {
        let key = key.trim();
        let mut items = self.items.lock().unwrap_or_else(|e| e.into_inner());
        let Some(i) = items.iter().position(|s| s.id == key || s.location == key) else { return Err(format!("no source '{key}'")) };
        let gone = items.remove(i);
        if let Err(e) = self.save(&items) {
            items.insert(i, gone);
            return Err(e);
        }
        if gone.kind != "folder" {
            let _ = std::fs::remove_dir_all(self.dir_of(&gone));
        }
        Ok(gone)
    }

    /// Re-reads one source (or all): a git repository is fast-forwarded. Returns one line per source.
    pub fn refresh(&self, key: &str) -> Result<Vec<String>, String> {
        let items = self.items.lock().unwrap_or_else(|e| e.into_inner()).clone();
        let chosen: Vec<&Source> = items.iter().filter(|s| key.is_empty() || s.id == key || s.location == key).collect();
        if chosen.is_empty() {
            return Err(if key.is_empty() { "no sources to refresh".into() } else { format!("no source '{key}'") });
        }
        chosen
            .into_iter()
            .map(|s| {
                let dir = self.dir_of(s);
                if !dir.is_dir() {
                    return Err(format!("{}: the folder is gone", s.location));
                }
                if s.kind == "catalog" {
                    fetch_catalog(&s.location, &dir, self.max_bytes())?;
                } else if s.kind == "git" {
                    git(&["pull", "--ff-only"], Some(&dir))?;
                }
                Ok(format!("{}: {} module(s)", s.id, installer::catalog(&[dir]).len()))
            })
            .collect()
    }
}

/// The digest of a module folder: every file except `module.sha256`, in path order, as `path NUL length NUL bytes`.
pub fn folder_digest(dir: &Path) -> Result<String, String> {
    fn walk(root: &Path, dir: &Path, out: &mut Vec<PathBuf>) -> Result<(), String> {
        for e in std::fs::read_dir(dir).map_err(|e| format!("{}: {e}", dir.display()))? {
            let p = e.map_err(|e| e.to_string())?.path();
            if p.is_dir() {
                walk(root, &p, out)?;
            } else if !(p.parent() == Some(root) && matches!(p.file_name().and_then(|n| n.to_str()), Some(DIGEST_FILE | SIG_FILE))) {
                out.push(p);
            }
        }
        Ok(())
    }
    let mut files = Vec::new();
    walk(dir, dir, &mut files)?;
    let mut rel: Vec<(String, PathBuf)> = files.into_iter().map(|p| (p.strip_prefix(dir).unwrap_or(&p).to_string_lossy().replace('\\', "/"), p)).collect();
    rel.sort();
    let mut h = Sha256::new();
    for (name, path) in rel {
        let bytes = std::fs::read(&path).map_err(|e| format!("{}: {e}", path.display()))?;
        h.update(name.as_bytes());
        h.update([0]);
        h.update((bytes.len() as u64).to_le_bytes());
        h.update([0]);
        h.update(&bytes);
    }
    Ok(h.finalize().iter().map(|b| format!("{b:02x}")).collect())
}

/// A module may ship `module.sha256`, the hex digest of its other files ([`folder_digest`]). When it does, an install
/// refuses a folder whose files no longer match it (a tampered or half-copied module). No file: nothing to check here
/// (the offer then shows as unsigned).
pub const DIGEST_FILE: &str = "module.sha256";

pub fn verify(dir: &Path) -> Result<bool, String> {
    let Ok(want) = std::fs::read_to_string(dir.join(DIGEST_FILE)) else { return Ok(false) };
    let got = folder_digest(dir)?;
    if want.trim().eq_ignore_ascii_case(&got) {
        Ok(true)
    } else {
        Err(format!("{}: signature check failed: the files do not match {DIGEST_FILE}", dir.display()))
    }
}

/// A module (or any package) may ship `module.sig.json`: `{ "publicKey": b64, "digest": hex, "signature": b64, "license": text }`.
/// `digest` is [`folder_digest`] of the other files and `signature` is an ed25519 signature over those hex characters.
pub const SIG_FILE: &str = "module.sig.json";

/// What a folder's checks say about it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Vetted {
    /// `unsigned` (no check file), `digest` (matches `module.sha256`), `signed` (valid signature, key trusted),
    /// `untrusted` (valid signature, key not in the trusted signers).
    pub status: String,
    pub signer: Option<String>,
    pub license: String,
}

/// The trusted signers: a name -> base64 ed25519 public key map, stored on the daemon in `plugin-signers.json`.
pub struct Signers {
    file: PathBuf,
    items: Mutex<std::collections::BTreeMap<String, String>>,
}

impl Signers {
    pub fn load(state_dir: &Path) -> Signers {
        let file = state_dir.join("plugin-signers.json");
        let items = std::fs::read_to_string(&file).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
        Signers { file, items: Mutex::new(items) }
    }

    pub fn snapshot(&self) -> std::collections::BTreeMap<String, String> {
        self.items.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    pub fn add(&self, name: &str, key: &str) -> Result<(), String> {
        use base64::Engine;
        let raw = base64::engine::general_purpose::STANDARD.decode(key.trim()).map_err(|_| "the key must be base64".to_string())?;
        if raw.len() != 32 || !installer::safe_name(name) {
            return Err("usage: signer-add <name> <base64 ed25519 public key (32 bytes)>".into());
        }
        let mut items = self.items.lock().unwrap_or_else(|e| e.into_inner());
        items.insert(name.into(), key.trim().into());
        super::host_frames::write_json(&self.file, &json!(&*items))
    }

    pub fn remove(&self, name: &str) -> Result<(), String> {
        let mut items = self.items.lock().unwrap_or_else(|e| e.into_inner());
        if items.remove(name).is_none() {
            return Err(format!("no signer '{name}'"));
        }
        super::host_frames::write_json(&self.file, &json!(&*items))
    }
}

/// Checks a folder: a signature file is verified against the folder's digest and the trusted signers (a bad one is an
/// error, never a downgrade to unsigned); otherwise `module.sha256` is checked as in [`verify`].
pub fn vet(dir: &Path, trusted: &std::collections::BTreeMap<String, String>) -> Result<Vetted, String> {
    use base64::Engine;
    let Ok(text) = std::fs::read_to_string(dir.join(SIG_FILE)) else {
        let digest = verify(dir)?;
        return Ok(Vetted { status: if digest { "digest" } else { "unsigned" }.into(), signer: None, license: String::new() });
    };
    let bad = |why: &str| format!("{}: signature check failed: {why}", dir.display());
    let doc: Value = serde_json::from_str(&text).map_err(|_| bad(&format!("{SIG_FILE} is not JSON")))?;
    let s = |k: &str| doc.get(k).and_then(Value::as_str).unwrap_or("").to_string();
    let b64 = base64::engine::general_purpose::STANDARD;
    if !s("digest").eq_ignore_ascii_case(&folder_digest(dir)?) {
        return Err(bad("the files changed after they were signed"));
    }
    let (key, sig) = (b64.decode(s("publicKey")).map_err(|_| bad("publicKey is not base64"))?, b64.decode(s("signature")).map_err(|_| bad("signature is not base64"))?);
    ring::signature::UnparsedPublicKey::new(&ring::signature::ED25519, &key).verify(s("digest").to_lowercase().as_bytes(), &sig).map_err(|_| bad("the signature does not match the key"))?;
    let signer = trusted.iter().find(|(_, k)| b64.decode(k).is_ok_and(|k| k == key)).map(|(n, _)| n.clone());
    Ok(Vetted { status: if signer.is_some() { "signed" } else { "untrusted" }.into(), signer, license: s("license") })
}

#[cfg(test)]
mod tests {
    #[test]
    fn defaults_file_falls_back_to_the_bundled_folder_in_os_app_mode() {
        let root = std::env::temp_dir().join(format!("src-seed-1-{}", std::process::id()));
        let installed = root.join("plugins-installed");
        let bundled = root.join("package-plugins");
        std::fs::create_dir_all(&installed).unwrap();
        std::fs::create_dir_all(&bundled).unwrap();
        // OS-app mode: the installed dir holds modules only; the bundled folder holds the defaults file.
        std::fs::write(bundled.join(DEFAULTS_FILE), "{}").unwrap();
        assert_eq!(defaults_file(None, &[Some(installed.clone()), Some(bundled.clone())]), Some(bundled.join(DEFAULTS_FILE)));
        // Standalone: the plugins dir has it and wins.
        std::fs::write(installed.join(DEFAULTS_FILE), "{}").unwrap();
        assert_eq!(defaults_file(None, &[Some(installed.clone()), Some(bundled.clone())]), Some(installed.join(DEFAULTS_FILE)));
        // The env-named file always wins; no file anywhere gives none.
        assert_eq!(defaults_file(Some(root.join("x.json")), &[Some(installed.clone())]), Some(root.join("x.json")));
        assert_eq!(defaults_file(None, &[None, Some(root.join("missing"))]), None);
        let _ = std::fs::remove_dir_all(&root);
    }

    use super::*;

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("infantry-sources-{name}-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    fn module(root: &Path, id: &str, version: &str) -> PathBuf {
        let dir = root.join("official").join(id);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("module.json"), json!({ "name": id, "version": version }).to_string()).unwrap();
        std::fs::write(dir.join("a.txt"), "hello").unwrap();
        dir
    }

    #[test]
    fn sources_are_added_listed_refreshed_and_removed_and_survive_a_restart() {
        let (state, src) = (tmp("state"), tmp("src"));
        module(&src, "Ps_One", "1.0.0");
        let store = SourceStore::load(&state);
        assert!(store.list().is_empty());
        let added = store.add(&src.display().to_string()).unwrap();
        assert_eq!(added.kind, "folder");
        assert!(store.add(&src.display().to_string()).unwrap_err().contains("already a source"));
        assert_eq!(store.list()[0]["offers"], 1);
        // A second client (a new store over the same state dir) sees the same list.
        let other = SourceStore::load(&state);
        assert_eq!(other.list().len(), 1);
        assert_eq!(other.dirs(), [PathBuf::from(&added.location)]);
        module(&src, "Ps_Two", "1.0.0");
        assert!(other.refresh("").unwrap()[0].contains("2 module(s)"));
        assert!(store.remove("nope").is_err());
        store.remove(&added.id).unwrap();
        assert!(src.join("official/Ps_One").is_dir(), "a local folder is never deleted");
        assert!(SourceStore::load(&state).list().is_empty());
        for d in [state, src] {
            let _ = std::fs::remove_dir_all(d);
        }
    }

    #[test]
    fn a_default_source_is_seeded_once_labelled_and_stays_removed() {
        let (state, src, cfg) = (tmp("seed"), tmp("seedsrc"), tmp("seedcfg"));
        module(&src, "Ps_Seed", "1.2.0");
        let defaults = cfg.join(DEFAULTS_FILE);
        let missing = cfg.join("gone");
        std::fs::write(&defaults, json!({ "sources": [ { "location": src.display().to_string(), "label": "Official" }, { "location": missing.display().to_string() } ] }).to_string()).unwrap();
        let store = SourceStore::load(&state);
        let lines = store.seed_defaults(&defaults);
        assert!(lines[0].contains("added"), "{lines:?}");
        assert!(lines[1].contains("not added yet"), "{lines:?}");
        assert_eq!(store.list()[0]["label"], "Official");
        // A restart keeps the label and does not add it twice.
        let again = SourceStore::load(&state);
        assert_eq!(again.list()[0]["label"], "Official");
        assert!(again.seed_defaults(&defaults).iter().all(|l| !l.contains("added as")));
        assert_eq!(again.list().len(), 1);
        // Removed on purpose: never re-added.
        let id = again.list()[0]["id"].as_str().unwrap().to_string();
        again.remove(&id).unwrap();
        SourceStore::load(&state).seed_defaults(&defaults);
        assert!(SourceStore::load(&state).list().is_empty());
        // No defaults file: nothing happens.
        assert!(SourceStore::load(&state).seed_defaults(&cfg.join("none.json")).is_empty());
        for d in [state, src, cfg] {
            let _ = std::fs::remove_dir_all(d);
        }
    }

    #[test]
    fn a_seeded_default_is_replaced_once_but_a_hand_added_source_is_kept() {
        let (state, old, new, hand, cfg) = (tmp("rs"), tmp("rsold"), tmp("rsnew"), tmp("rshand"), tmp("rscfg"));
        for d in [&old, &new, &hand] {
            module(d, "Ps_Rep", "1.0.0");
        }
        let defaults = cfg.join(DEFAULTS_FILE);
        std::fs::write(&defaults, json!({ "sources": [ { "location": old.display().to_string(), "label": "Official" } ] }).to_string()).unwrap();
        let store = SourceStore::load(&state);
        store.seed_defaults(&defaults);
        store.add(&hand.display().to_string()).unwrap();
        let (old_loc, hand_loc) = (store.list()[0]["location"].as_str().unwrap().to_string(), store.list()[1]["location"].as_str().unwrap().to_string());
        // A newer release names a new Official that replaces the old one (and, wrongly, the hand-added one).
        std::fs::write(&defaults, json!({ "sources": [ { "location": new.display().to_string(), "label": "Official", "replaces": [old_loc, hand_loc] } ] }).to_string()).unwrap();
        let store = SourceStore::load(&state);
        let lines = store.seed_defaults(&defaults);
        assert!(lines.iter().any(|l| l.contains("replaced by the new default")), "{lines:?}");
        let locs: Vec<String> = store.list().iter().map(|s| s["location"].as_str().unwrap().to_string()).collect();
        assert!(!locs.iter().any(|l| l == &old_loc), "the seeded old default is gone: {locs:?}");
        assert!(locs.iter().any(|l| l == &hand_loc), "a hand-added source is never dropped: {locs:?}");
        assert_eq!(locs.len(), 2);
        // Once only: a later start changes nothing.
        assert!(SourceStore::load(&state).seed_defaults(&defaults).is_empty());
        for d in [state, old, new, hand, cfg] {
            let _ = std::fs::remove_dir_all(d);
        }
    }

    #[test]
    fn a_folder_that_offers_nothing_or_does_not_exist_is_refused() {
        let (state, empty) = (tmp("state2"), tmp("empty"));
        let store = SourceStore::load(&state);
        assert!(store.add(&empty.display().to_string()).unwrap_err().contains("offers no module"));
        assert!(store.add("no/such/folder").unwrap_err().contains("neither a folder"));
        assert!(store.list().is_empty());
        for d in [state, empty] {
            let _ = std::fs::remove_dir_all(d);
        }
    }

    #[test]
    fn the_catalog_merges_sources_in_order_and_the_first_to_offer_an_id_wins() {
        let (a, b) = (tmp("a"), tmp("b"));
        module(&a, "Ps_Shared", "1.0.0");
        module(&b, "Ps_Shared", "2.0.0");
        module(&b, "Ps_Only_B", "1.0.0");
        let offers = installer::catalog(&[a.clone(), b.clone()]);
        let ids: Vec<(&str, &str)> = offers.iter().map(|o| (o.id.as_str(), o.version.as_str())).collect();
        assert_eq!(ids, [("Ps_Shared", "1.0.0"), ("Ps_Only_B", "1.0.0")]);
        for d in [a, b] {
            let _ = std::fs::remove_dir_all(d);
        }
    }

    #[test]
    fn an_install_is_refused_when_the_files_do_not_match_the_signature_file() {
        let src = tmp("sig");
        let dir = module(&src, "Ps_Signed", "1.0.0");
        assert_eq!(verify(&dir), Ok(false), "no signature file: unsigned, not refused");
        std::fs::write(dir.join(DIGEST_FILE), folder_digest(&dir).unwrap()).unwrap();
        assert_eq!(verify(&dir), Ok(true));
        std::fs::write(dir.join("a.txt"), "tampered").unwrap();
        assert!(verify(&dir).unwrap_err().contains("signature check failed"));
        std::fs::write(dir.join(DIGEST_FILE), "00ff").unwrap();
        assert!(verify(&dir).is_err());
        let _ = std::fs::remove_dir_all(&src);
    }
}

#[cfg(test)]
mod catalog_tests {
    use super::*;
    use std::io::{Read, Write};

    /// A one-thread HTTP server over fixed pages (port 0): returns its base url.
    fn serve(pages: Vec<(&'static str, String)>) -> String {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://{}", listener.local_addr().unwrap());
        std::thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                let mut s = stream;
                let mut buf = [0u8; 2048];
                let n = s.read(&mut buf).unwrap_or(0);
                let path = String::from_utf8_lossy(&buf[..n]).split_whitespace().nth(1).unwrap_or("/").to_string();
                let (status, body) = match pages.iter().find(|(p, _)| *p == path) {
                    Some((_, b)) => ("200 OK", b.clone()),
                    None => ("404 Not Found", String::new()),
                };
                let _ = write!(s, "HTTP/1.1 {status}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len());
            }
        });
        base
    }

    #[test]
    fn a_folder_source_location_and_dir_have_no_verbatim_prefix() {
        let state = std::env::temp_dir().join(format!("infantry-plain-{}", uuid::Uuid::new_v4()));
        let dir = state.join("src");
        std::fs::create_dir_all(dir.join("official/Plain_One")).unwrap();
        std::fs::write(dir.join("official/Plain_One/module.json"), r#"{"name":"Plain_One","version":"1.0.0"}"#).unwrap();
        let store = SourceStore::load(&state);
        let added = store.add(&dir.display().to_string()).unwrap();
        let verbatim = r"\\?\";
        assert!(!added.location.starts_with(verbatim), "{}", added.location);
        let row = &store.list()[0];
        assert!(!row["location"].as_str().unwrap().starts_with(verbatim));
        assert!(!row["dir"].as_str().unwrap().starts_with(verbatim));
        assert_eq!(plain_location("folder", r"\\?\C:\a\src"), r"C:\a\src");
    }

    #[test]
    fn a_catalog_url_becomes_a_source_that_is_downloaded_refreshed_and_removed() {
        let state = std::env::temp_dir().join(format!("infantry-catalog-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&state).unwrap();
        let catalog = json!({ "modules": [{ "name": "Cat_One", "tier": "official", "base": "files/Cat_One", "files": ["module.json", "ui/dist/index.js"] }] }).to_string();
        let base = serve(vec![
            ("/catalog.json", catalog),
            ("/files/Cat_One/module.json", json!({ "name": "Cat_One", "version": "1.0.0" }).to_string()),
            ("/files/Cat_One/ui/dist/index.js", "export default {};".into()),
            ("/evil.json", json!({ "modules": [{ "name": "Cat_Evil", "base": "files/Cat_One", "files": ["../escape.txt"] }] }).to_string()),
            ("/empty.json", json!({ "modules": [] }).to_string()),
        ]);
        let store = SourceStore::load(&state);
        let src = store.add(&format!("{base}/catalog.json")).unwrap();
        assert_eq!(src.kind, "catalog");
        let row = &store.list()[0];
        assert_eq!(row["offers"], 1);
        let dir = PathBuf::from(row["dir"].as_str().unwrap());
        assert!(dir.join("official/Cat_One/ui/dist/index.js").is_file());
        assert_eq!(store.refresh("").unwrap().len(), 1);
        assert!(store.add(&format!("{base}/evil.json")).unwrap_err().contains("outside the module folder"));
        assert!(store.add(&format!("{base}/empty.json")).unwrap_err().contains("offers no module"));
        assert!(store.add(&format!("{base}/missing.json")).is_err());
        assert_eq!(store.list().len(), 1, "a refused catalog is not kept");
        store.remove(&src.id).unwrap();
        assert!(!dir.exists(), "the downloaded copy goes with the source");
        let _ = std::fs::remove_dir_all(&state);
    }
}
