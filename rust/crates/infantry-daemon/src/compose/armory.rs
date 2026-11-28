// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! ARMORY-1: the daemon's update check for installed apps and the shell, against the Armory apps catalog.
//!
//! The catalog is one `apps.json` (default: the `battalionOS-apps` repository, setting `armory.catalogUrl`; an http(s)
//! address, a file or a folder holding `apps.json`):
//!
//! ```json
//! { "apps": [ { "id": "infantry-ai", "name": "Infantry AI", "kind": "shell",
//!               "feed": "https://.../rust-{channel}/latest.json", "publicKey": "<ed25519 public key>", "restart": "relaunch" } ] }
//! ```
//!
//! An entry names its latest version directly (`version`, optional `notes`) or points at a feed (`feed`, the release
//! feed document of docs/wiki/Updates.md: `{version, notes, platforms}`; `{channel}` is replaced by `armory.channel`).
//! `kind` is `app` or `shell`, `restart` is `none`, `daemon` or `relaunch`. The shell is always installed, at the version
//! of this daemon; an app is installed when `armory-installed.json` (state dir, `{id: version}`, written by the Armory
//! installer) lists it. The check runs on a background thread, never in a client: at start, then every
//! `armory.checkSeconds` (default 0 = only at start), and on `armory_check` ("check now"). Every client reads the same
//! list with `armory_updates`; a change is also pushed as `armory_updates`. A dead catalog or feed becomes a warning in
//! the report, never a crash. Downloads go through [`super::sources::http_get`] (size cap: `catalog.maxFileBytes`).
use super::installer::version_cmp;
use super::sources;
use infantry_core::bus::EventBus;
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Duration;

pub const CATALOG_URL_KEY: &str = "armory.catalogUrl";
pub const DEFAULT_CATALOG_URL: &str = "https://raw.githubusercontent.com/SuperiorSoft-LLC/battalionOS-apps/main/apps.json";
pub const CHANNEL_KEY: &str = "armory.channel";
pub const DEFAULT_CHANNEL: &str = "stable";
/// Seconds between checks; 0 (the cheapest choice) checks only at start and on "check now".
pub const CHECK_SECONDS_KEY: &str = "armory.checkSeconds";
pub const INSTALLED_FILE: &str = "armory-installed.json";
pub const ARMORY_UPDATES: &str = "armory_updates";
pub const ARMORY_CHECK: &str = "armory_check";
/// ARMORY-3: `armory_update {id}` starts the install of one app's update; `armory_progress` tells how it goes.
pub const ARMORY_UPDATE: &str = "armory_update";
pub const ARMORY_PROGRESS: &str = "armory_progress";
/// Where installed apps live, under the state dir.
pub const APPS_DIR: &str = "armory-apps";
const RESTARTS: [&str; 3] = ["none", "daemon", "relaunch"];

/// An entry of the apps catalog.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AppEntry {
    pub id: String,
    pub name: String,
    /// `app` or `shell`.
    pub kind: String,
    pub version: Option<String>,
    pub notes: String,
    pub feed: Option<String>,
    pub public_key: String,
    pub restart: String,
    /// Where the app's files are: a folder (or `file://`), or an http(s) base holding `package.json` (`{"files":[..]}`).
    /// `{version}` is replaced by the version being installed.
    pub package: Option<String>,
}

/// What a check found: the updates and the reasons something could not be checked.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Report {
    pub updates: Vec<Value>,
    pub warnings: Vec<String>,
}

fn text(v: &Value, k: &str) -> String {
    v.get(k).and_then(Value::as_str).map(str::trim).unwrap_or("").to_string()
}

/// The entries of an `apps.json` document; an entry that cannot be used becomes a warning.
pub fn parse_catalog(doc: &Value) -> (Vec<AppEntry>, Vec<String>) {
    let mut warnings = Vec::new();
    let Some(apps) = doc.get("apps").and_then(Value::as_array) else {
        return (Vec::new(), vec!["the apps catalog has no `apps` array".into()]);
    };
    let mut out: Vec<AppEntry> = Vec::new();
    for a in apps {
        let id = text(a, "id");
        if !super::installer::safe_name(&id) {
            warnings.push(format!("apps catalog entry '{id}' is not a usable app id"));
            continue;
        }
        if out.iter().any(|e| e.id == id) {
            warnings.push(format!("apps catalog lists '{id}' twice; the first one is used"));
            continue;
        }
        let kind = if text(a, "kind") == "shell" { "shell" } else { "app" };
        let restart = Some(text(a, "restart")).filter(|r| RESTARTS.contains(&r.as_str())).unwrap_or_else(|| (if kind == "shell" { "relaunch" } else { "none" }).into());
        let some = |s: String| Some(s).filter(|s| !s.is_empty());
        out.push(AppEntry { name: some(text(a, "name")).unwrap_or_else(|| id.clone()), id, kind: kind.into(), version: some(text(a, "version")), notes: text(a, "notes"), feed: some(text(a, "feed")), public_key: text(a, "publicKey"), restart, package: some(text(a, "package")) });
    }
    (out, warnings)
}

/// Reads one document by address: http(s) through the download seam, else a file (or a folder holding `apps.json`).
pub fn fetch_any(location: &str, max_bytes: u64) -> Result<Vec<u8>, String> {
    if location.starts_with("http://") || location.starts_with("https://") {
        let tls = native_tls::TlsConnector::new().map_err(|e| format!("no TLS for downloads: {e}"))?;
        let agent = ureq::AgentBuilder::new().tls_connector(Arc::new(tls)).build();
        return sources::http_get(&agent, location, max_bytes);
    }
    let p = Path::new(location.strip_prefix("file://").unwrap_or(location));
    let p = if p.is_dir() { p.join("apps.json") } else { p.to_path_buf() };
    let len = std::fs::metadata(&p).map_err(|e| format!("{}: {e}", p.display()))?.len();
    if len > max_bytes {
        return Err(format!("{}: larger than {max_bytes} bytes", p.display()));
    }
    std::fs::read(&p).map_err(|e| format!("{}: {e}", p.display()))
}

/// A feed address next to the catalog when it is relative.
fn resolve_location(catalog: &str, feed: &str) -> String {
    if feed.contains("://") || Path::new(feed).is_absolute() {
        return feed.to_string();
    }
    let dir = Path::new(catalog);
    if dir.is_dir() {
        return format!("{}/{feed}", catalog.trim_end_matches(['/', '\\']));
    }
    let base = catalog.rsplit_once(['/', '\\']).map(|x| x.0).unwrap_or(".");
    format!("{base}/{feed}")
}

fn json_of(fetch: &dyn Fn(&str) -> Result<Vec<u8>, String>, location: &str) -> Result<Value, String> {
    let bytes = fetch(location)?;
    serde_json::from_slice(&bytes).map_err(|e| format!("{location} is not JSON ({e})"))
}

/// The whole check. `installed`: id to version of the installed apps; `shell_version`: the running shell's version.
pub fn check(catalog_location: &str, channel: &str, installed: &BTreeMap<String, String>, shell_version: &str, fetch: &dyn Fn(&str) -> Result<Vec<u8>, String>) -> Report {
    let mut report = Report::default();
    let doc = match json_of(fetch, catalog_location) {
        Ok(d) => d,
        Err(e) => {
            report.warnings.push(format!("apps catalog unavailable: {e}"));
            return report;
        }
    };
    let (entries, warnings) = parse_catalog(&doc);
    report.warnings.extend(warnings);
    for e in entries {
        let have = if e.kind == "shell" { Some(shell_version.to_string()) } else { installed.get(&e.id).cloned() };
        let Some(have) = have else { continue };
        let (mut version, mut notes) = (e.version.clone(), e.notes.clone());
        if let Some(feed) = &e.feed {
            let url = resolve_location(catalog_location, &feed.replace("{channel}", channel));
            match json_of(fetch, &url) {
                Ok(f) => {
                    if let Some(v) = f.get("version").and_then(Value::as_str).map(str::trim).filter(|v| !v.is_empty()) {
                        version = Some(v.to_string());
                        if let Some(n) = f.get("notes").and_then(Value::as_str) {
                            notes = n.to_string();
                        }
                    }
                }
                Err(err) => report.warnings.push(format!("{}: feed unavailable: {err}", e.id)),
            }
        }
        let Some(available) = version else {
            report.warnings.push(format!("{}: no version to compare (no feed answer and no `version`)", e.id));
            continue;
        };
        if version_cmp(&available, &have).is_gt() {
            report.updates.push(json!({ "id": e.id, "name": e.name, "kind": e.kind, "installed": have, "available": available, "notes": notes, "restart": e.restart, "publicKey": e.public_key, "package": e.package }));
        }
    }
    report
}

fn read_installed(state_dir: &Path) -> BTreeMap<String, String> {
    std::fs::read_to_string(state_dir.join(INSTALLED_FILE))
        .ok()
        .and_then(|t| serde_json::from_str::<BTreeMap<String, Value>>(&t).ok())
        .map(|m| m.into_iter().filter_map(|(k, v)| Some((k, v.as_str()?.to_string()))).collect())
        .unwrap_or_default()
}

fn write_installed(state_dir: &Path, id: &str, version: &str) -> Result<(), String> {
    let mut m = read_installed(state_dir);
    m.insert(id.to_string(), version.to_string());
    super::host_frames::write_json(&state_dir.join(INSTALLED_FILE), &json!(m))
}

fn copy_tree(from: &Path, to: &Path) -> Result<(), String> {
    std::fs::create_dir_all(to).map_err(|e| format!("{}: {e}", to.display()))?;
    for e in std::fs::read_dir(from).map_err(|e| format!("{}: {e}", from.display()))?.flatten() {
        let (src, dst) = (e.path(), to.join(e.file_name()));
        if src.is_dir() {
            copy_tree(&src, &dst)?;
        } else {
            std::fs::copy(&src, &dst).map_err(|e| format!("{}: {e}", src.display()))?;
        }
    }
    Ok(())
}

/// Brings an app's package into `dest`: a folder is copied; an http(s) base is read file by file from its `package.json`.
pub fn fetch_package(location: &str, dest: &Path, max_bytes: u64) -> Result<(), String> {
    let _ = std::fs::remove_dir_all(dest);
    if location.starts_with("http://") || location.starts_with("https://") {
        let base = location.trim_end_matches('/');
        let list: Value = serde_json::from_slice(&fetch_any(&format!("{base}/package.json"), max_bytes)?).map_err(|e| format!("{base}/package.json is not JSON ({e})"))?;
        let files = list.get("files").and_then(Value::as_array).ok_or("package.json has no `files` array")?;
        for f in files.iter().filter_map(Value::as_str) {
            if f.split('/').any(|p| p.is_empty() || p == ".." || p.contains(['\\', ':'])) {
                return Err(format!("package file '{f}' is not a safe relative path"));
            }
            let bytes = fetch_any(&format!("{base}/{f}"), max_bytes)?;
            let to = dest.join(f);
            std::fs::create_dir_all(to.parent().unwrap_or(dest)).map_err(|e| e.to_string())?;
            std::fs::write(&to, bytes).map_err(|e| format!("{}: {e}", to.display()))?;
        }
        return Ok(());
    }
    let from = Path::new(location.strip_prefix("file://").unwrap_or(location));
    if !from.is_dir() {
        return Err(format!("{location}: not a folder"));
    }
    copy_tree(from, dest)
}

/// What the client does after an update: `reopen` (close and reopen the app's window), `reconnect` (the daemon restarts
/// alone, the window stays), `relaunch` (the shell: the app's own updater, on a click).
pub fn action_of(restart: &str) -> &'static str {
    match restart {
        "daemon" => "reconnect",
        "relaunch" => "relaunch",
        _ => "reopen",
    }
}

/// Installs one app update: fetch into a staging folder, check the signature against the catalog's key (nothing else is
/// trusted; an unsigned or wrongly signed package is refused), swap it in, record the version. On any failure the old
/// version stays in place.
pub fn install(update: &Value, state_dir: &Path, max_bytes: u64, progress: &dyn Fn(u8)) -> Result<(), String> {
    let s = |k: &str| update.get(k).and_then(Value::as_str).unwrap_or("").trim().to_string();
    let (id, version, key) = (s("id"), s("available"), s("publicKey"));
    if !super::installer::safe_name(&id) || version.is_empty() || version.contains(['/', '\\']) {
        return Err(format!("'{id}' {version}: not an installable app update"));
    }
    if s("restart") == "relaunch" || s("kind") == "shell" {
        return Err("the shell updates through the app's own updater, not here".into());
    }
    if key.is_empty() {
        return Err(format!("{id}: the catalog gives no public key, so the package cannot be verified"));
    }
    let package = Some(s("package")).filter(|p| !p.is_empty()).ok_or_else(|| format!("{id}: the catalog entry has no `package`"))?.replace("{version}", &version);
    let apps = state_dir.join(APPS_DIR);
    let stage = apps.join(".installing").join(&id);
    let cleanup = |e: String| {
        let _ = std::fs::remove_dir_all(&stage);
        e
    };
    progress(10);
    fetch_package(&package, &stage, max_bytes).map_err(&cleanup)?;
    progress(60);
    let trusted = BTreeMap::from([(id.clone(), key)]);
    match sources::vet(&stage, &trusted).map_err(&cleanup)?.status.as_str() {
        "signed" => {}
        other => return Err(cleanup(format!("{id}: refused, the package is {other} (it must be signed with the catalog's key)"))),
    }
    progress(80);
    let current = apps.join(&id);
    if current.exists() {
        let aside = super::installer::swap_in(&stage, &current).map_err(&cleanup)?;
        if let Err(e) = write_installed(state_dir, &id, &version) {
            let _ = super::installer::swap_back(&aside, &current);
            return Err(e);
        }
        let _ = std::fs::remove_dir_all(&aside);
    } else {
        std::fs::rename(&stage, &current).map_err(|e| cleanup(format!("{}: {e}", current.display())))?;
        if let Err(e) = write_installed(state_dir, &id, &version) {
            let _ = std::fs::remove_dir_all(&current);
            return Err(e);
        }
    }
    progress(100);
    Ok(())
}

pub type Setting = Arc<dyn Fn(&str) -> Option<Value> + Send + Sync>;

/// The unit: the last report, the background check and the two frames.
pub struct ArmoryUnit {
    state_dir: PathBuf,
    setting: Setting,
    bus: EventBus,
    last: Mutex<Option<(Report, u64)>>,
    /// Restarts the daemon alone (the `daemon` class); the composition root sets it.
    restart: Mutex<Option<Arc<dyn Fn() + Send + Sync>>>,
    me: std::sync::Weak<ArmoryUnit>,
    /// Tells the owner a failed update (title, body) on the notification bus; the composition root sets it.
    notifier: Mutex<Option<Arc<dyn Fn(&str, &str) + Send + Sync>>>,
}

fn now() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

impl ArmoryUnit {
    pub fn new(state_dir: &Path, setting: Setting, bus: EventBus) -> Arc<ArmoryUnit> {
        Arc::new_cyclic(|me| ArmoryUnit { state_dir: state_dir.to_path_buf(), setting, bus, last: Mutex::new(None), restart: Mutex::new(None), me: me.clone(), notifier: Mutex::new(None) })
    }

    fn string(&self, key: &str, default: &str) -> String {
        (self.setting)(key).and_then(|v| v.as_str().map(|s| s.trim().to_string())).filter(|s| !s.is_empty()).unwrap_or_else(|| default.into())
    }

    /// Runs the check now, keeps the report and tells the clients when it changed.
    pub fn check_now(&self) -> Report {
        let max = sources::limit_of((self.setting)(sources::MAX_FILE_BYTES_KEY));
        let report = check(&self.string(CATALOG_URL_KEY, DEFAULT_CATALOG_URL), &self.string(CHANNEL_KEY, DEFAULT_CHANNEL), &read_installed(&self.state_dir), env!("CARGO_PKG_VERSION"), &|loc| fetch_any(loc, max));
        let changed = self.last.lock().unwrap_or_else(|e| e.into_inner()).as_ref().map(|(r, _)| r != &report).unwrap_or(true);
        *self.last.lock().unwrap_or_else(|e| e.into_inner()) = Some((report.clone(), now()));
        if changed {
            self.bus.publish(Envelope::new(ARMORY_UPDATES, self.answer().to_string()));
        }
        report
    }

    /// The one "updates available" answer every client reads.
    pub fn answer(&self) -> Value {
        match &*self.last.lock().unwrap_or_else(|e| e.into_inner()) {
            Some((r, at)) => json!({ "checked": true, "checkedAt": at, "updates": r.updates, "warnings": r.warnings }),
            None => json!({ "checked": false, "checkedAt": 0, "updates": [], "warnings": [] }),
        }
    }

    pub fn set_notifier(&self, f: Arc<dyn Fn(&str, &str) + Send + Sync>) {
        *self.notifier.lock().unwrap_or_else(|e| e.into_inner()) = Some(f);
    }

    pub fn set_restart(&self, f: Arc<dyn Fn() + Send + Sync>) {
        *self.restart.lock().unwrap_or_else(|e| e.into_inner()) = Some(f);
    }

    fn progress(&self, id: &str, stage: &str, percent: u8, extra: Value) {
        let mut d = json!({ "id": id, "stage": stage, "percent": percent });
        if let (Some(o), Some(x)) = (d.as_object_mut(), extra.as_object()) {
            o.extend(x.clone());
        }
        self.bus.publish(Envelope::new(ARMORY_PROGRESS, d.to_string()));
        // PARITY-AUDIT-1 #2: a progress task that ends failed is a bus event (Kotlin's `progress.failed`).
        if stage == "failed" {
            d["kind"] = json!("armory_update");
            self.bus.emit(crate::compose::adapters::PROGRESS_FAILED, d);
        }
    }

    /// Starts the update of app `id` on a background thread; progress and the end are pushed as `armory_progress`.
    pub fn start_update(&self, id: &str) -> Result<(), String> {
        let update = self.last.lock().unwrap_or_else(|e| e.into_inner()).as_ref().and_then(|(r, _)| r.updates.iter().find(|u| u["id"] == id).cloned()).ok_or_else(|| format!("{id} has no update to install (check first)"))?;
        if update["kind"] == "shell" || update["restart"] == "relaunch" {
            return Err("the shell updates through the app's own updater (download, then a Relaunch click)".into());
        }
        let me = self.me.upgrade().ok_or("the armory unit is gone")?;
        let id = id.to_string();
        std::thread::spawn(move || me.run_update(&id, &update));
        Ok(())
    }

    /// The install and what follows it, by restart class. Public so a test can run it without a thread.
    pub fn run_update(&self, id: &str, update: &Value) {
        let max = sources::limit_of((self.setting)(sources::MAX_FILE_BYTES_KEY));
        match install(update, &self.state_dir, max, &|p| self.progress(id, "installing", p, json!({}))) {
            Ok(()) => {
                let restart = update["restart"].as_str().unwrap_or("none");
                self.check_now();
                self.progress(id, "done", 100, json!({ "version": update["available"], "action": action_of(restart) }));
                if restart == "daemon" {
                    if let Some(f) = self.restart.lock().unwrap_or_else(|e| e.into_inner()).clone() {
                        f();
                    }
                }
            }
            Err(e) => {
                self.progress(id, "failed", 0, json!({ "error": e }));
                if let Some(f) = self.notifier.lock().unwrap_or_else(|e| e.into_inner()).clone() {
                    f(&format!("{id} was not updated"), &format!("{e}. The version you had keeps running."));
                }
            }
        }
    }

    /// The background check: at start, then every `armory.checkSeconds` when that is above 0.
    pub fn spawn(self: Arc<Self>) {
        std::thread::spawn(move || loop {
            self.check_now();
            let secs = (self.setting)(CHECK_SECONDS_KEY).and_then(|v| v.as_u64().or_else(|| v.as_str().and_then(|s| s.trim().parse().ok()))).unwrap_or(0);
            if secs == 0 {
                return;
            }
            std::thread::sleep(Duration::from_secs(secs));
        });
    }
}

impl FrameUnit for ArmoryUnit {
    fn name(&self) -> &'static str {
        "armory"
    }
    fn frames(&self) -> &'static [&'static str] {
        &[ARMORY_UPDATES, ARMORY_CHECK, ARMORY_UPDATE]
    }
    fn handle(&self, env: &Envelope, _ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        if env.r#type == ARMORY_UPDATE {
            let id = serde_json::from_str::<Value>(&env.data).ok().and_then(|d| d.get("id").and_then(Value::as_str).map(str::to_string)).unwrap_or_default();
            self.start_update(&id).map_err(|e| Refusal::new(ARMORY_UPDATE, e))?;
            let mut e = Envelope::new(ARMORY_PROGRESS, json!({ "id": id, "stage": "installing", "percent": 0 }).to_string());
            e.request_id = env.request_id.clone();
            return Ok(vec![e]);
        }
        if env.r#type == ARMORY_CHECK {
            self.check_now();
        }
        let mut e = Envelope::new(ARMORY_UPDATES, self.answer().to_string());
        e.request_id = env.request_id.clone();
        Ok(vec![e])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn go(files: &[(&str, &str)], installed: &[(&str, &str)], shell: &str) -> Report {
        let files: HashMap<String, String> = files.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect();
        let installed = installed.iter().map(|(a, b)| (a.to_string(), b.to_string())).collect();
        check("mem://apps.json", "stable", &installed, shell, &|u| files.get(u).map(|s| s.clone().into_bytes()).ok_or_else(|| format!("{u}: not found")))
    }

    #[test]
    fn versions_compare_by_part_not_by_text() {
        let r = go(&[("mem://apps.json", r#"{"apps":[{"id":"a","version":"1.10.0"}]}"#)], &[("a", "1.9.0")], "0.1.0");
        assert_eq!(r.updates.len(), 1);
        let same = go(&[("mem://apps.json", r#"{"apps":[{"id":"a","version":"1.9.0"}]}"#)], &[("a", "1.9.0")], "0.1.0");
        assert!(same.updates.is_empty() && same.warnings.is_empty());
        let older = go(&[("mem://apps.json", r#"{"apps":[{"id":"a","version":"1.8.0"}]}"#)], &[("a", "1.9.0")], "0.1.0");
        assert!(older.updates.is_empty());
    }

    #[test]
    fn the_list_spans_the_shell_feed_and_an_app_and_skips_what_is_not_installed() {
        let r = go(
            &[
                (
                    "mem://apps.json",
                    r#"{"apps":[
                    {"id":"infantry-ai","name":"Infantry AI","kind":"shell","feed":"https://x/rust-{channel}/latest.json","publicKey":"K"},
                    {"id":"tower","name":"Tower","version":"2.0.0","notes":"new","restart":"daemon"},
                    {"id":"absent","version":"9.0.0"}]}"#,
                ),
                ("https://x/rust-stable/latest.json", r#"{"version":"0.4.0","notes":"shell notes"}"#),
            ],
            &[("tower", "1.0.0")],
            "0.3.0",
        );
        assert!(r.warnings.is_empty(), "{:?}", r.warnings);
        assert_eq!(r.updates.len(), 2);
        let u = &r.updates;
        assert_eq!((u[0]["id"].as_str(), u[0]["kind"].as_str(), u[0]["restart"].as_str()), (Some("infantry-ai"), Some("shell"), Some("relaunch")));
        assert_eq!((u[0]["installed"].as_str(), u[0]["available"].as_str(), u[0]["notes"].as_str()), (Some("0.3.0"), Some("0.4.0"), Some("shell notes")));
        assert_eq!((u[1]["id"].as_str(), u[1]["restart"].as_str(), u[1]["installed"].as_str()), (Some("tower"), Some("daemon"), Some("1.0.0")));
    }

    #[test]
    fn a_dead_feed_or_catalog_is_a_warning_not_a_crash() {
        let r = go(&[("mem://apps.json", r#"{"apps":[{"id":"infantry-ai","kind":"shell","feed":"https://dead/latest.json"},{"id":"tower","version":"2.0.0"}]}"#)], &[("tower", "1.0.0")], "0.3.0");
        assert_eq!(r.updates.len(), 1);
        assert_eq!(r.updates[0]["id"], "tower");
        assert!(r.warnings.iter().any(|w| w.contains("infantry-ai") && w.contains("feed unavailable")), "{:?}", r.warnings);
        let dead = go(&[], &[], "0.3.0");
        assert!(dead.updates.is_empty() && dead.warnings[0].contains("catalog unavailable"));
        let junk = go(&[("mem://apps.json", "not json")], &[], "0.3.0");
        assert!(junk.warnings[0].contains("not JSON"));
    }

    #[test]
    fn bad_entries_warn_and_the_rest_still_count() {
        let r = go(&[("mem://apps.json", r#"{"apps":[{"id":"../x","version":"1"},{"id":"ok","version":"2.0.0","restart":"bogus"},{"id":"ok","version":"3.0.0"}]}"#)], &[("ok", "1.0.0")], "0.3.0");
        assert_eq!(r.updates.len(), 1);
        assert_eq!((r.updates[0]["available"].as_str(), r.updates[0]["restart"].as_str()), (Some("2.0.0"), Some("none")));
        assert_eq!(r.warnings.len(), 2);
    }

    #[test]
    fn a_local_catalog_and_a_relative_feed_work_and_the_unit_answers_frames() {
        let dir = std::env::temp_dir().join(format!("armory-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(dir.join("feeds")).unwrap();
        std::fs::write(dir.join("apps.json"), r#"{"apps":[{"id":"tower","feed":"feeds/tower.json"}]}"#).unwrap();
        std::fs::write(dir.join("feeds/tower.json"), r#"{"version":"1.1.0","notes":"n"}"#).unwrap();
        std::fs::write(dir.join(INSTALLED_FILE), r#"{"tower":"1.0.0"}"#).unwrap();
        let loc = dir.display().to_string();
        let setting: Setting = Arc::new(move |k| (k == CATALOG_URL_KEY).then(|| json!(loc.clone())));
        let unit = ArmoryUnit::new(&dir, setting, EventBus::new(8));
        assert_eq!(unit.answer()["checked"], false);
        let out = unit.handle(&Envelope::new(ARMORY_CHECK, ""), &FrameCtx { conn_id: 1, owner: true, bus: EventBus::new(8) }).ok().unwrap();
        let a: Value = serde_json::from_str(&out[0].data).unwrap();
        assert_eq!(a["checked"], true);
        assert_eq!((a["updates"][0]["id"].as_str(), a["updates"][0]["available"].as_str()), (Some("tower"), Some("1.1.0")));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_download_cap_is_a_setting_with_a_default() {
        assert_eq!(sources::limit_of(None), sources::DEFAULT_MAX_FILE_BYTES);
        assert_eq!(sources::limit_of(Some(json!(0))), sources::DEFAULT_MAX_FILE_BYTES);
        assert_eq!(sources::limit_of(Some(json!("1024"))), 1024);
    }

    // ---- ARMORY-3: the update install, by restart class ----
    use base64::Engine;
    use ring::signature::{Ed25519KeyPair, KeyPair};
    use std::sync::atomic::{AtomicUsize, Ordering};

    fn key() -> (Ed25519KeyPair, String) {
        let pkcs8 = Ed25519KeyPair::generate_pkcs8(&ring::rand::SystemRandom::new()).unwrap();
        let kp = Ed25519KeyPair::from_pkcs8(pkcs8.as_ref()).unwrap();
        let b64 = base64::engine::general_purpose::STANDARD.encode(kp.public_key().as_ref());
        (kp, b64)
    }

    fn scratch() -> PathBuf {
        let d = std::env::temp_dir().join(format!("armory-install-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    /// A package folder with one file, signed by `kp` (the signature file is written last, over the folder digest).
    fn package(body: &str, kp: &Ed25519KeyPair, claimed_key: &str) -> PathBuf {
        let dir = scratch().join("p");
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("app.js"), body).unwrap();
        let digest = sources::folder_digest(&dir).unwrap();
        let sig = base64::engine::general_purpose::STANDARD.encode(kp.sign(digest.as_bytes()).as_ref());
        std::fs::write(dir.join(sources::SIG_FILE), json!({ "publicKey": claimed_key, "digest": digest, "signature": sig }).to_string()).unwrap();
        dir
    }

    /// The installed v1.0 of "tower" plus an update entry whose package is `pkg`.
    fn world(restart: &str, pkg: &Path, pubkey: &str) -> (PathBuf, Value) {
        let state = scratch();
        std::fs::create_dir_all(state.join(APPS_DIR).join("tower")).unwrap();
        std::fs::write(state.join(APPS_DIR).join("tower/app.js"), "v1.0").unwrap();
        std::fs::write(state.join(INSTALLED_FILE), r#"{"tower":"1.0.0"}"#).unwrap();
        let update = json!({ "id": "tower", "kind": "app", "installed": "1.0.0", "available": "1.1.0", "restart": restart, "publicKey": pubkey, "package": pkg.display().to_string() });
        (state, update)
    }

    fn unit_for(state: &Path) -> (Arc<ArmoryUnit>, tokio::sync::broadcast::Receiver<Envelope>, Arc<AtomicUsize>) {
        let bus = EventBus::new(64);
        let rx = bus.subscribe();
        let unit = ArmoryUnit::new(state, Arc::new(|_| None), bus);
        let restarted = Arc::new(AtomicUsize::new(0));
        let r = restarted.clone();
        unit.set_restart(Arc::new(move || {
            r.fetch_add(1, Ordering::SeqCst);
        }));
        (unit, rx, restarted)
    }

    /// The last `armory_progress` frame the bus carried.
    fn last_progress(rx: &mut tokio::sync::broadcast::Receiver<Envelope>) -> Value {
        let mut last = None;
        while let Ok(e) = rx.try_recv() {
            if e.r#type == ARMORY_PROGRESS {
                last = serde_json::from_str(&e.data).ok();
            }
        }
        last.expect("a progress frame")
    }

    #[test]
    fn a_none_class_update_installs_signed_files_and_the_window_reopens() {
        let (kp, k) = key();
        let (state, update) = world("none", &package("v1.1", &kp, &k), &k);
        let (unit, mut rx, restarted) = unit_for(&state);
        unit.run_update("tower", &update);
        assert_eq!(std::fs::read_to_string(state.join(APPS_DIR).join("tower/app.js")).unwrap(), "v1.1");
        assert_eq!(read_installed(&state)["tower"], "1.1.0");
        let end = last_progress(&mut rx);
        assert_eq!((end["stage"].as_str(), end["action"].as_str(), end["version"].as_str()), (Some("done"), Some("reopen"), Some("1.1.0")));
        assert_eq!(restarted.load(Ordering::SeqCst), 0, "no daemon restart for the none class");
        assert!(!state.join(APPS_DIR).join("tower.old").exists() && !state.join(APPS_DIR).join(".installing/tower").exists());
    }

    #[test]
    fn a_daemon_class_update_restarts_the_daemon_once_and_tells_the_window_to_reconnect() {
        let (kp, k) = key();
        let (state, update) = world("daemon", &package("v1.1", &kp, &k), &k);
        let (unit, mut rx, restarted) = unit_for(&state);
        unit.run_update("tower", &update);
        assert_eq!(restarted.load(Ordering::SeqCst), 1);
        assert_eq!(last_progress(&mut rx)["action"], "reconnect");
        assert_eq!(read_installed(&state)["tower"], "1.1.0");
    }

    #[test]
    fn a_bad_signature_is_refused_and_the_old_version_keeps_running() {
        let (kp, k) = key();
        let (other, other_k) = key();
        // Signed by a key that is not the catalog's.
        let (state, update) = world("none", &package("v1.1", &other, &other_k), &k);
        let (unit, mut rx, restarted) = unit_for(&state);
        let told: Arc<Mutex<Vec<String>>> = Arc::default();
        let t = told.clone();
        unit.set_notifier(Arc::new(move |title, _| t.lock().unwrap().push(title.to_string())));
        unit.run_update("tower", &update);
        let end = last_progress(&mut rx);
        assert_eq!(end["stage"], "failed");
        assert_eq!(told.lock().unwrap().len(), 1, "the failure reaches the notification bus once");
        assert!(end["error"].as_str().unwrap().contains("refused"), "{end}");
        assert_eq!(std::fs::read_to_string(state.join(APPS_DIR).join("tower/app.js")).unwrap(), "v1.0");
        assert_eq!(read_installed(&state)["tower"], "1.0.0");
        assert!(!state.join(APPS_DIR).join(".installing/tower").exists(), "the staged copy is deleted");
        assert_eq!(restarted.load(Ordering::SeqCst), 0);

        // Tampered after signing.
        let tampered = package("v1.1", &kp, &k);
        std::fs::write(tampered.join("app.js"), "evil").unwrap();
        let (state, update) = world("none", &tampered, &k);
        let err = install(&update, &state, 1 << 20, &|_| {}).unwrap_err();
        assert!(err.contains("signature check failed"), "{err}");
        assert_eq!(std::fs::read_to_string(state.join(APPS_DIR).join("tower/app.js")).unwrap(), "v1.0");

        // Unsigned, and a catalog with no key.
        let unsigned = scratch().join("u");
        std::fs::create_dir_all(&unsigned).unwrap();
        std::fs::write(unsigned.join("app.js"), "v1.1").unwrap();
        let (state, update) = world("none", &unsigned, &k);
        assert!(install(&update, &state, 1 << 20, &|_| {}).unwrap_err().contains("unsigned"));
        let (state, update) = world("none", &unsigned, "");
        assert!(install(&update, &state, 1 << 20, &|_| {}).unwrap_err().contains("no public key"));
    }

    #[test]
    fn the_shell_is_refused_here_and_left_to_the_apps_updater() {
        let (state, mut update) = world("relaunch", Path::new("."), "K");
        update["kind"] = json!("shell");
        assert!(install(&update, &state, 1 << 20, &|_| {}).unwrap_err().contains("own updater"));
        assert_eq!((action_of("relaunch"), action_of("none"), action_of("daemon")), ("relaunch", "reopen", "reconnect"));
        let (unit, _, _) = unit_for(&state);
        assert!(unit.start_update("tower").is_err(), "nothing was checked yet");
    }
}
