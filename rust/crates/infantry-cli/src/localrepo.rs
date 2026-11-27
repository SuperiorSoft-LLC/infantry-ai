// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! LOCAL-REPO-1: private local app repos. `infantry app publish <dir> --repo <name>` packs a built BattalionOS app
//! (a folder holding `app.json`) into a `.bosapp`, signs it with this device's local key, bumps its version and writes
//! it plus its feed and catalog entry into a local repo folder. The Armory reads that folder as a local source.
//! Nothing here touches the network.
//!
//! Layout (all under the BattalionOS data dir, resolved like BattalionOS `Paths::from_env`):
//!
//! ```text
//! <home>/keys/local-repo.key           minisign secret key box (unencrypted), made on first use
//! <home>/keys/local-repo.pub           its public key line (base64), what local repo entries pin
//! <home>/local-repos/<slug>/repo.json  {"schema":1,"name":"My apps"}
//! <home>/local-repos/<slug>/catalog.json                    catalog v2 (the same format as remote sources)
//! <home>/local-repos/<slug>/apps/<id>/<channel>.json        feed v1
//! <home>/local-repos/<slug>/apps/<id>/<id>-<ver>-<plat>.bosapp (+ .sig)
//! ```

use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};

pub const DEFAULT_REPO: &str = "My apps";
pub const DEFAULT_CHANNEL: &str = "stable";
const KEY_FILE: &str = "local-repo.key";
const PUB_FILE: &str = "local-repo.pub";

/// The BattalionOS data dir: `override_` (a flag), else `BATTALION_HOME`, else `%LOCALAPPDATA%\SuperiorSoft\BattalionOS`
/// (Windows) or `~/.local/share/BattalionOS`. The same order as BattalionOS `Paths::from_env`.
pub fn os_home(override_: Option<&str>, getenv: &dyn Fn(&str) -> Option<String>) -> Result<PathBuf, String> {
    if let Some(h) = override_.filter(|h| !h.trim().is_empty()) {
        return Ok(PathBuf::from(h));
    }
    if let Some(h) = getenv("BATTALION_HOME").filter(|h| !h.is_empty()) {
        return Ok(PathBuf::from(h));
    }
    if cfg!(windows) {
        let base = getenv("LOCALAPPDATA").filter(|v| !v.is_empty()).ok_or("LOCALAPPDATA is not set; pass --os-home")?;
        Ok(PathBuf::from(base).join("SuperiorSoft").join("BattalionOS"))
    } else {
        let home = getenv("HOME").filter(|v| !v.is_empty()).ok_or("HOME is not set; pass --os-home")?;
        Ok(PathBuf::from(home).join(".local").join("share").join("BattalionOS"))
    }
}

/// `windows-x86_64`, as BattalionOS `platform_key` names this machine.
pub fn host_platform() -> String {
    format!("{}-{}", std::env::consts::OS, std::env::consts::ARCH)
}

/// The device key, made the first time. Returns (secret key, public key base64 line).
pub fn device_key(home: &Path) -> Result<(minisign::SecretKey, String), String> {
    let dir = home.join("keys");
    let (skp, pkp) = (dir.join(KEY_FILE), dir.join(PUB_FILE));
    if !skp.is_file() {
        fs::create_dir_all(&dir).map_err(|e| format!("cannot create {}: {e}", dir.display()))?;
        let kp = minisign::KeyPair::generate_unencrypted_keypair().map_err(|e| format!("key generation failed: {e}"))?;
        let sbox = kp.sk.to_box(Some("BattalionOS local repo key (this device only)")).map_err(|e| e.to_string())?;
        // create_new: two publishers racing never overwrite each other's key.
        match fs::OpenOptions::new().write(true).create_new(true).open(&skp) {
            Ok(mut f) => {
                f.write_all(sbox.into_string().as_bytes()).map_err(|e| e.to_string())?;
                fs::write(&pkp, kp.pk.to_base64() + "\n").map_err(|e| e.to_string())?;
            }
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(e) => return Err(format!("cannot write {}: {e}", skp.display())),
        }
    }
    let text = fs::read_to_string(&skp).map_err(|e| format!("cannot read {}: {e}", skp.display()))?;
    let sbox = minisign::SecretKeyBox::from_string(&text).map_err(|e| format!("{} is not a minisign key: {e}", skp.display()))?;
    let sk = sbox.into_unencrypted_secret_key().map_err(|e| format!("{} cannot be used: {e}", skp.display()))?;
    let pk = minisign::PublicKey::from_secret_key(&sk).map_err(|e| e.to_string())?.to_base64();
    if fs::read_to_string(&pkp).map(|p| p.trim() != pk).unwrap_or(true) {
        fs::write(&pkp, pk.clone() + "\n").map_err(|e| e.to_string())?;
    }
    Ok((sk, pk))
}

/// `My apps` -> `my-apps`.
pub fn slug(name: &str) -> String {
    let mut s = String::new();
    for c in name.trim().chars() {
        if c.is_ascii_alphanumeric() {
            s.push(c.to_ascii_lowercase());
        } else if !s.ends_with('-') && !s.is_empty() {
            s.push('-');
        }
    }
    let s = s.trim_end_matches('-').to_string();
    if s.is_empty() { "repo".into() } else { s }
}

fn read_json(p: &Path) -> Option<Value> {
    serde_json::from_str(&fs::read_to_string(p).ok()?).ok()
}

/// The repos this home knows: (name, dir). Folders under `<home>/local-repos/` with a repo.json, then
/// `os.json` `armory.localRepos` entries (`{"name","path"}`) that live elsewhere. Read only.
pub fn list_repos(home: &Path) -> Vec<(String, PathBuf)> {
    let mut out = Vec::new();
    if let Ok(rd) = fs::read_dir(home.join("local-repos")) {
        let mut dirs: Vec<PathBuf> = rd.filter_map(|e| e.ok()).map(|e| e.path()).filter(|p| p.is_dir()).collect();
        dirs.sort();
        for d in dirs {
            if let Some(n) = read_json(&d.join("repo.json")).and_then(|v| v.get("name").and_then(Value::as_str).map(str::to_string)) {
                out.push((n, d));
            }
        }
    }
    if let Some(list) = read_json(&home.join("os.json")).and_then(|v| v.pointer("/armory/localRepos").cloned()).and_then(|v| v.as_array().cloned()) {
        for r in list {
            if let (Some(n), Some(p)) = (r.get("name").and_then(Value::as_str), r.get("path").and_then(Value::as_str)) {
                if !out.iter().any(|(m, _)| m.eq_ignore_ascii_case(n)) {
                    out.push((n.to_string(), PathBuf::from(p)));
                }
            }
        }
    }
    out
}

/// The repo named `name`, made under `<home>/local-repos/` when there is none.
pub fn open_repo(home: &Path, name: &str) -> Result<PathBuf, String> {
    let name = name.trim();
    if name.is_empty() {
        return Err("the repo name is empty".into());
    }
    if let Some((_, d)) = list_repos(home).into_iter().find(|(n, _)| n.eq_ignore_ascii_case(name)) {
        return Ok(d);
    }
    let base = home.join("local-repos");
    let mut dir = base.join(slug(name));
    let mut i = 2;
    while dir.exists() {
        dir = base.join(format!("{}-{i}", slug(name)));
        i += 1;
    }
    fs::create_dir_all(&dir).map_err(|e| format!("cannot create {}: {e}", dir.display()))?;
    fs::write(dir.join("repo.json"), serde_json::to_string_pretty(&json!({"schema": 1, "name": name})).unwrap() + "\n").map_err(|e| e.to_string())?;
    fs::write(dir.join("catalog.json"), "{\n  \"schema\": 2,\n  \"apps\": []\n}\n").map_err(|e| e.to_string())?;
    Ok(dir)
}

fn parse_ver(v: &str) -> Option<(u64, u64, u64)> {
    let core = v.trim().split(['-', '+']).next()?;
    let mut it = core.split('.').map(|p| p.parse::<u64>().ok());
    Some((it.next()??, it.next().unwrap_or(Some(0))?, it.next().unwrap_or(Some(0))?))
}

/// The version to publish: the manifest's, unless the repo already holds that version or a newer one; then one patch
/// above the newest the repo holds.
pub fn next_version(manifest: &str, published: &[String]) -> Result<String, String> {
    let m = parse_ver(manifest).ok_or_else(|| format!("app.json version '{manifest}' is not x.y.z"))?;
    match published.iter().filter_map(|v| parse_ver(v)).max() {
        Some(top) if top >= m => Ok(format!("{}.{}.{}", top.0, top.1, top.2 + 1)),
        _ => Ok(format!("{}.{}.{}", m.0, m.1, m.2)),
    }
}

/// `file:///C:/x/y/` for a folder.
pub fn file_url(p: &Path) -> String {
    let s = p.to_string_lossy().replace('\\', "/");
    let s = s.trim_start_matches("//?/");
    if s.starts_with('/') { format!("file://{s}") } else { format!("file:///{s}") }
}

#[derive(Debug)]
pub struct Published {
    pub id: String,
    pub version: String,
    pub repo: String,
    pub repo_dir: PathBuf,
    pub package: PathBuf,
    pub channel: String,
}

pub struct PublishArgs<'a> {
    pub dir: &'a Path,
    pub repo: &'a str,
    pub channel: &'a str,
    pub platform: Option<&'a str>,
    pub notes: Option<&'a str>,
    pub home: &'a Path,
}

fn valid_id(id: &str) -> bool {
    !id.is_empty() && id.len() <= 64 && id.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-' || c == '.') && !id.starts_with(['.', '-'])
}

fn add_files(dir: &Path, rel: &Path, out: &mut Vec<(String, PathBuf)>) -> Result<(), String> {
    let mut entries: Vec<_> = fs::read_dir(dir.join(rel)).map_err(|e| e.to_string())?.filter_map(|e| e.ok()).collect();
    entries.sort_by_key(|e| e.file_name());
    for e in entries {
        let name = e.file_name().to_string_lossy().to_string();
        if rel.as_os_str().is_empty() && (name == ".git" || name == "app.json") {
            continue;
        }
        let ft = e.file_type().map_err(|e| e.to_string())?;
        if ft.is_symlink() {
            continue;
        }
        let r = rel.join(&name);
        if ft.is_dir() {
            add_files(dir, &r, out)?;
        } else if ft.is_file() {
            out.push((r.to_string_lossy().replace('\\', "/"), dir.join(&r)));
        }
    }
    Ok(())
}

pub fn publish(a: &PublishArgs) -> Result<Published, String> {
    let channel = a.channel.trim();
    if channel.is_empty() || !channel.chars().all(|c| c.is_ascii_alphanumeric() || c == '-') {
        return Err(format!("channel '{channel}' must be letters, digits or '-'"));
    }
    let mpath = a.dir.join("app.json");
    let mtext = fs::read_to_string(&mpath).map_err(|e| format!("{} has no app.json ({e}); build the app first", a.dir.display()))?;
    let mut manifest: Map<String, Value> = serde_json::from_str(&mtext).map_err(|e| format!("app.json is not JSON: {e}"))?;
    let s = |k: &str| manifest.get(k).and_then(Value::as_str).map(str::to_string);
    let id = s("id").ok_or("app.json has no id")?;
    if !valid_id(&id) {
        return Err(format!("app id '{id}' is not allowed (lowercase letters, digits, '-' and '.')"));
    }
    let name = s("name").ok_or("app.json has no name")?;
    let contract = manifest.get("contract").cloned().ok_or("app.json has no contract {min,max}")?;
    let cmax = contract.get("max").and_then(Value::as_u64).ok_or("app.json contract.max is missing")?;
    if let Some(entry) = manifest.get("ui").and_then(|v| v.get("entry")).and_then(Value::as_str) {
        if !a.dir.join(entry).is_file() {
            return Err(format!("ui.entry {entry} is not in {}", a.dir.display()));
        }
    }
    let platform = a.platform.map(str::to_string).unwrap_or_else(host_platform);

    let (sk, pubkey) = device_key(a.home)?;
    let repo_dir = open_repo(a.home, a.repo)?;
    let repo_name = read_json(&repo_dir.join("repo.json")).and_then(|v| v.get("name").and_then(Value::as_str).map(str::to_string)).unwrap_or_else(|| a.repo.to_string());
    let app_dir = repo_dir.join("apps").join(&id);
    fs::create_dir_all(&app_dir).map_err(|e| e.to_string())?;

    // every version this repo has published for the app, any channel
    let mut published = Vec::new();
    if let Ok(rd) = fs::read_dir(&app_dir) {
        for e in rd.filter_map(|e| e.ok()) {
            if e.path().extension().is_some_and(|x| x == "json") {
                if let Some(v) = read_json(&e.path()).and_then(|v| v.get("version").and_then(Value::as_str).map(str::to_string)) {
                    published.push(v);
                }
            }
        }
    }
    let src_version = s("version").ok_or("app.json has no version")?;
    let version = next_version(&src_version, &published)?;
    manifest.insert("version".into(), Value::String(version.clone()));
    let state_schema = manifest.get("state").and_then(|v| v.get("schema")).and_then(Value::as_u64).unwrap_or(1);
    let manifest_text = serde_json::to_string_pretty(&manifest).unwrap() + "\n";

    // pack
    let mut files = Vec::new();
    add_files(a.dir, Path::new(""), &mut files)?;
    let pkg_name = format!("{id}-{version}-{platform}.bosapp");
    let pkg = app_dir.join(&pkg_name);
    {
        let f = fs::File::create(&pkg).map_err(|e| format!("cannot write {}: {e}", pkg.display()))?;
        let mut z = zip::ZipWriter::new(f);
        let opt = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated);
        z.start_file("app.json", opt).map_err(|e| e.to_string())?;
        z.write_all(manifest_text.as_bytes()).map_err(|e| e.to_string())?;
        for (rel, src) in &files {
            z.start_file(rel.as_str(), opt).map_err(|e| e.to_string())?;
            z.write_all(&fs::read(src).map_err(|e| format!("cannot read {}: {e}", src.display()))?).map_err(|e| e.to_string())?;
        }
        z.finish().map_err(|e| e.to_string())?;
    }
    let data = fs::read(&pkg).map_err(|e| e.to_string())?;
    let sig = minisign::sign(None, &sk, std::io::Cursor::new(&data), Some(&format!("file:{pkg_name}")), Some("signature from this device's local repo key"))
        .map_err(|e| format!("signing failed: {e}"))?
        .into_string();
    fs::write(app_dir.join(format!("{pkg_name}.sig")), &sig).map_err(|e| e.to_string())?;

    // feed for the channel
    let feed_path = app_dir.join(format!("{channel}.json"));
    let mut platforms = Map::new();
    if let Some(prev) = read_json(&feed_path) {
        if prev.get("version").and_then(Value::as_str) == Some(version.as_str()) {
            if let Some(p) = prev.get("platforms").and_then(Value::as_object) {
                platforms = p.clone();
            }
        }
    }
    platforms.insert(
        platform.clone(),
        json!({"url": file_url(&pkg), "signature": sig, "sha256": hex(&Sha256::digest(&data)), "size": data.len() as u64}),
    );
    let mut feed = json!({"version": version, "contract": cmax, "stateSchema": state_schema, "platforms": platforms});
    if let Some(n) = a.notes.filter(|n| !n.trim().is_empty()) {
        feed["notes"] = Value::String(n.to_string());
    }
    write_atomic(&feed_path, &(serde_json::to_string_pretty(&feed).unwrap() + "\n"))?;

    // catalog entry (replace this app's, keep the others)
    let cat_path = repo_dir.join("catalog.json");
    let mut catalog = read_json(&cat_path).filter(|c| c.get("schema").and_then(Value::as_u64) == Some(2)).unwrap_or_else(|| json!({"schema": 2, "apps": []}));
    let apps = catalog["apps"].as_array().cloned().unwrap_or_default();
    let old = apps.iter().find(|e| e.get("id").and_then(Value::as_str) == Some(id.as_str())).cloned();
    let mut channels = old.as_ref().and_then(|o| o.get("channels").and_then(Value::as_object).cloned()).unwrap_or_default();
    channels.insert(channel.to_string(), Value::String(file_url(&feed_path)));
    let mut plats: Vec<String> = old.as_ref().and_then(|o| o.get("platforms").and_then(Value::as_array).cloned()).unwrap_or_default().iter().filter_map(|v| v.as_str().map(str::to_string)).collect();
    if !plats.contains(&platform) {
        plats.push(platform.clone());
    }
    let mut entry = json!({
        "id": id, "name": name, "publicKey": pubkey, "contract": contract,
        "permissions": manifest.get("permissions").cloned().unwrap_or(json!([])),
        "channels": channels, "platforms": plats,
    });
    for k in ["publisher", "license", "summary"] {
        if let Some(v) = manifest.get(k).filter(|v| v.is_string()) {
            entry[k] = v.clone();
        }
    }
    let mut apps: Vec<Value> = apps.into_iter().filter(|e| e.get("id").and_then(Value::as_str) != Some(id.as_str())).collect();
    apps.push(entry);
    catalog["apps"] = Value::Array(apps);
    write_atomic(&cat_path, &(serde_json::to_string_pretty(&catalog).unwrap() + "\n"))?;

    // the source tree carries the published version, so the next build starts from it
    if src_version != version {
        let mut src: Map<String, Value> = serde_json::from_str(&mtext).unwrap_or_default();
        src.insert("version".into(), Value::String(version.clone()));
        let _ = fs::write(&mpath, serde_json::to_string_pretty(&src).unwrap() + "\n");
    }
    Ok(Published { id, version, repo: repo_name, repo_dir, package: pkg, channel: channel.to_string() })
}

fn write_atomic(p: &Path, text: &str) -> Result<(), String> {
    let tmp = p.with_extension("json.tmp");
    fs::write(&tmp, text).map_err(|e| format!("cannot write {}: {e}", tmp.display()))?;
    fs::rename(&tmp, p).map_err(|e| format!("cannot replace {}: {e}", p.display()))
}

fn hex(b: &[u8]) -> String {
    b.iter().map(|x| format!("{x:02x}")).collect()
}

/// Check a package against its feed entry and a public key, as the Armory does (sha256, size, minisign).
pub fn verify_package(pkg: &Path, feed_entry: &Value, pubkey: &str) -> Result<(), String> {
    let data = fs::read(pkg).map_err(|e| e.to_string())?;
    if feed_entry.get("size").and_then(Value::as_u64) != Some(data.len() as u64) {
        return Err("size does not match the feed".into());
    }
    if feed_entry.get("sha256").and_then(Value::as_str) != Some(hex(&Sha256::digest(&data)).as_str()) {
        return Err("sha256 does not match the feed".into());
    }
    let pk = minisign::PublicKey::from_base64(pubkey.trim()).map_err(|e| e.to_string())?;
    let sb = minisign::SignatureBox::from_string(feed_entry.get("signature").and_then(Value::as_str).unwrap_or("")).map_err(|e| e.to_string())?;
    minisign::verify(&pk, &sb, std::io::Cursor::new(&data), true, false, false).map_err(|e| format!("signature: {e}"))
}

/// The MCP tool's descriptor.
pub const MCP_DESCRIPTOR: &str = r#"{"name":"infantry_app_publish","description":"Publish a built BattalionOS app (a folder with app.json) to a private local repo on this machine: packs a .bosapp, signs it with this device's local key, bumps the version and writes the feed and catalog entry. The Armory then shows it as installable or updatable. Nothing leaves this machine.","inputSchema":{"type":"object","properties":{"dir":{"type":"string","description":"The built app folder (holds app.json)."},"repo":{"type":"string","description":"Local repo name (default \"My apps\"); made when missing."},"channel":{"type":"string","description":"Update channel (default stable)."},"notes":{"type":"string","description":"Release notes."}},"required":["dir"],"additionalProperties":false},"annotations":{"readOnlyHint":false,"destructiveHint":false,"idempotentHint":false,"openWorldHint":false}}"#;
pub const MCP_NAME: &str = "infantry_app_publish";

pub fn summary(p: &Published) -> String {
    format!("published {} {} to local repo \"{}\" (channel {}): {}\nOpen the Armory to install or update it.", p.id, p.version, p.repo, p.channel, p.package.display())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("infantry-localrepo-{tag}-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&d).unwrap();
        d
    }

    fn hello(dir: &Path, version: &str) {
        fs::create_dir_all(dir.join("ui")).unwrap();
        fs::write(dir.join("ui/index.html"), "<h1>hello</h1>").unwrap();
        fs::write(
            dir.join("app.json"),
            json!({"manifest":1,"id":"hello-app","name":"Hello","version":version,"contract":{"min":1,"max":1},"ui":{"entry":"ui/index.html"},"state":{"schema":1}}).to_string(),
        )
        .unwrap();
    }

    fn args<'a>(dir: &'a Path, home: &'a Path, repo: &'a str) -> PublishArgs<'a> {
        PublishArgs { dir, repo, channel: "stable", platform: None, notes: None, home }
    }

    #[test]
    fn publish_writes_catalog_feed_and_a_package_the_device_key_verifies() {
        let (home, app) = (tmp("home"), tmp("app"));
        hello(&app, "1.0.0");
        let p = publish(&args(&app, &home, "My apps")).unwrap();
        assert_eq!((p.id.as_str(), p.version.as_str()), ("hello-app", "1.0.0"));
        assert_eq!(p.repo_dir, home.join("local-repos/my-apps"));
        let cat = read_json(&p.repo_dir.join("catalog.json")).unwrap();
        assert_eq!(cat["schema"], 2);
        let e = &cat["apps"][0];
        let pubkey = fs::read_to_string(home.join("keys/local-repo.pub")).unwrap();
        assert_eq!(e["publicKey"].as_str().unwrap(), pubkey.trim());
        assert!(e["channels"]["stable"].as_str().unwrap().starts_with("file:///"));
        let feed = read_json(&p.repo_dir.join("apps/hello-app/stable.json")).unwrap();
        let entry = &feed["platforms"][host_platform()];
        verify_package(&p.package, entry, &pubkey).unwrap();
        // the package holds the stamped manifest and the page
        let mut z = zip::ZipArchive::new(fs::File::open(&p.package).unwrap()).unwrap();
        assert!(z.by_name("ui/index.html").is_ok());
        let mut m = String::new();
        std::io::Read::read_to_string(&mut z.by_name("app.json").unwrap(), &mut m).unwrap();
        assert!(m.contains("\"1.0.0\""));
    }

    #[test]
    fn republish_bumps_the_version_and_keeps_one_key() {
        let (home, app) = (tmp("home"), tmp("app"));
        hello(&app, "1.0.0");
        publish(&args(&app, &home, "My apps")).unwrap();
        let k1 = fs::read_to_string(home.join("keys/local-repo.pub")).unwrap();
        let p2 = publish(&args(&app, &home, "my apps")).unwrap();
        assert_eq!(p2.version, "1.0.1");
        assert_eq!(fs::read_to_string(home.join("keys/local-repo.pub")).unwrap(), k1);
        // the source tree carries the new version
        assert_eq!(read_json(&app.join("app.json")).unwrap()["version"], "1.0.1");
        let cat = read_json(&p2.repo_dir.join("catalog.json")).unwrap();
        assert_eq!(cat["apps"].as_array().unwrap().len(), 1);
        assert_eq!(read_json(&p2.repo_dir.join("apps/hello-app/stable.json")).unwrap()["version"], "1.0.1");
        // a manifest ahead of the repo wins
        let mut m = read_json(&app.join("app.json")).unwrap();
        m["version"] = json!("2.0.0");
        fs::write(app.join("app.json"), m.to_string()).unwrap();
        assert_eq!(publish(&args(&app, &home, "My apps")).unwrap().version, "2.0.0");
    }

    #[test]
    fn a_tampered_package_is_refused() {
        let (home, app) = (tmp("home"), tmp("app"));
        hello(&app, "1.0.0");
        let p = publish(&args(&app, &home, "Experiments")).unwrap();
        let feed = read_json(&p.repo_dir.join("apps/hello-app/stable.json")).unwrap();
        let entry = feed["platforms"][host_platform()].clone();
        let pubkey = fs::read_to_string(home.join("keys/local-repo.pub")).unwrap();
        let mut data = fs::read(&p.package).unwrap();
        let n = data.len() / 2;
        data[n] ^= 0xff;
        fs::write(&p.package, &data).unwrap();
        assert!(verify_package(&p.package, &entry, &pubkey).is_err());
        // also refused when the feed is rewritten to match the bytes: the signature fails
        let mut e2 = entry.clone();
        e2["sha256"] = json!(hex(&Sha256::digest(&data)));
        assert!(verify_package(&p.package, &e2, &pubkey).unwrap_err().starts_with("signature"));
        // and another key never verifies it
        let other = minisign::KeyPair::generate_unencrypted_keypair().unwrap().pk.to_base64();
        assert!(verify_package(&p.package, &e2, &other).is_err());
    }

    #[test]
    fn several_repos_and_channels() {
        let (home, app) = (tmp("home"), tmp("app"));
        hello(&app, "1.0.0");
        let a = publish(&args(&app, &home, "My apps")).unwrap();
        let b = publish(&PublishArgs { channel: "prerelease", ..args(&app, &home, "Experiments") }).unwrap();
        assert_ne!(a.repo_dir, b.repo_dir);
        let names: Vec<String> = list_repos(&home).into_iter().map(|(n, _)| n).collect();
        assert_eq!(names, vec!["Experiments".to_string(), "My apps".to_string()]);
        let cat = read_json(&b.repo_dir.join("catalog.json")).unwrap();
        assert!(cat["apps"][0]["channels"]["prerelease"].is_string());
    }

    #[test]
    fn home_resolves_flag_then_env_then_default() {
        let env = |k: &str| match k {
            "BATTALION_HOME" => Some("C:/env-home".to_string()),
            "LOCALAPPDATA" => Some("C:/lad".to_string()),
            "HOME" => Some("/h".to_string()),
            _ => None,
        };
        assert_eq!(os_home(Some("C:/flag"), &env).unwrap(), PathBuf::from("C:/flag"));
        assert_eq!(os_home(None, &env).unwrap(), PathBuf::from("C:/env-home"));
        let no_env = |k: &str| if k == "BATTALION_HOME" { None } else { env(k) };
        let d = os_home(None, &no_env).unwrap();
        assert!(d.ends_with("BattalionOS"));
    }

    #[test]
    fn bad_input_is_refused() {
        let (home, app) = (tmp("home"), tmp("app"));
        assert!(publish(&args(&app, &home, "My apps")).unwrap_err().contains("no app.json"));
        hello(&app, "1.0.0");
        fs::remove_file(app.join("ui/index.html")).unwrap();
        assert!(publish(&args(&app, &home, "My apps")).unwrap_err().contains("ui.entry"));
        assert_eq!(next_version("1.2", &[]).unwrap(), "1.2.0");
        assert!(next_version("abc", &[]).is_err());
        assert_eq!(slug("  My Apps!! "), "my-apps");
    }
}
