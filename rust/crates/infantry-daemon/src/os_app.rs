// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! BOS-7: the daemon as an app of BattalionOS (app contract v1). Opt-in: nothing here runs unless the OS started the
//! daemon with `BATTALION_CONTRACT` set; without it the daemon behaves exactly as before.
//!
//! What the OS gives (env): `BATTALION_CONTRACT` (1), `BATTALION_READY_FILE`, `BATTALION_TOKEN_FILE` (the per-start secret
//! of the stop call), `BATTALION_STATE_DIR`, `BATTALION_APP_DIR`, `BATTALION_UI_ORIGIN`. What the daemon does with it:
//! - writes the ready file `{contract, pid, url, health, stop}` once it listens (and removes it on a clean stop),
//! - `POST /stop` with the secret as bearer ends the daemon through the graceful path of `serve_with`,
//! - `/health` carries `busy` (agent turns in flight), so the OS can ask before an update,
//! - the app's own origin joins the WebSocket allow list,
//! - `<state>/launch.json` (host, port, tokenFile) gives phone access without window flags,
//! - plugins live in `<state>/plugins-installed`; the package's plugins are only the bundled source they are synced from.
//!
//! The contract names are copied here (the daemon does not depend on the BattalionOS crates); the contract tests in this
//! file pin them.

use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::sync::Arc;

pub const CONTRACT_ENV: &str = "BATTALION_CONTRACT";
pub const READY_FILE_ENV: &str = "BATTALION_READY_FILE";
pub const TOKEN_FILE_ENV: &str = "BATTALION_TOKEN_FILE";
pub const STATE_DIR_ENV: &str = "BATTALION_STATE_DIR";
pub const APP_DIR_ENV: &str = "BATTALION_APP_DIR";
pub const UI_ORIGIN_ENV: &str = "BATTALION_UI_ORIGIN";
/// The contract version this daemon speaks.
pub const CONTRACT: u32 = 1;
/// The route the OS stops the app through.
pub const STOP_PATH: &str = "/stop";
/// Where the plugins of an app daemon live, inside the state dir.
pub const INSTALLED_DIR: &str = "plugins-installed";
/// The launch file in the state dir.
pub const LAUNCH_FILE: &str = "launch.json";
/// Set once the first start has copied the user-added plugins from the old place.
const MIGRATED_MARK: &str = ".migrated-from-program-folder";
/// Where user-added plugins lived before the app contract (default: the package's own plugins folder).
pub const LEGACY_PLUGINS_ENV: &str = "INFANTRY_LEGACY_PLUGINS_DIR";

/// What the OS told this process.
#[derive(Debug, Clone, PartialEq)]
pub struct OsEnv {
    pub ready_file: Option<PathBuf>,
    pub token_file: PathBuf,
    pub state_dir: Option<PathBuf>,
    pub app_dir: Option<PathBuf>,
    pub ui_origin: Option<String>,
}

impl OsEnv {
    /// `Ok(None)` when `BATTALION_CONTRACT` is unset or empty (not an app: nothing changes). A contract this daemon does
    /// not speak, or a missing token file, refuses the start.
    pub fn from_env(env: &dyn Fn(&str) -> Option<String>) -> Result<Option<OsEnv>, String> {
        let get = |k: &str| env(k).map(|v| v.trim().to_string()).filter(|v| !v.is_empty());
        let Some(c) = get(CONTRACT_ENV) else { return Ok(None) };
        let v: u32 = c.parse().map_err(|_| format!("{CONTRACT_ENV} `{c}` is not a contract version"))?;
        if v != CONTRACT {
            return Err(format!("{CONTRACT_ENV} {v}: this daemon speaks app contract {CONTRACT}"));
        }
        let token_file = get(TOKEN_FILE_ENV).map(PathBuf::from).ok_or_else(|| format!("{TOKEN_FILE_ENV} is not set (the OS gives an app the secret of the stop call)"))?;
        Ok(Some(OsEnv {
            ready_file: get(READY_FILE_ENV).map(PathBuf::from),
            token_file,
            state_dir: get(STATE_DIR_ENV).map(PathBuf::from),
            app_dir: get(APP_DIR_ENV).map(PathBuf::from),
            ui_origin: get(UI_ORIGIN_ENV).map(|o| o.trim_end_matches('/').to_string()),
        }))
    }
}

/// TESTENV-2: whether the OS env applies to a daemon given `explicit_state` (`--state-dir`, else `INFANTRY_STATE_DIR`).
/// A daemon told to use another data folder than the OS's own (`BATTALION_STATE_DIR`) is a normal daemon that merely
/// inherited BATTALION_* (a test daemon started from an agent session): no app mode, no plugin migration, no phone/LAN
/// settings carried over. The same folder, or no explicit folder, keeps the app mode.
pub fn applies_to(os: &OsEnv, explicit_state: Option<&Path>) -> bool {
    let Some(explicit) = explicit_state else { return true };
    let norm = |p: &Path| {
        let s = p.to_string_lossy().replace('\\', "/");
        let s = s.trim_end_matches('/').to_string();
        if cfg!(windows) { s.to_lowercase() } else { s }
    };
    os.state_dir.as_deref().is_some_and(|o| norm(o) == norm(explicit))
}

/// The running side: the stop secret (kept as a digest) and the stop signal.
pub struct OsRuntime {
    pub env: OsEnv,
    stop_digest: [u8; 32],
    stop: tokio::sync::Notify,
    /// BOS-33: the port this daemon bound (set when the ready file is written), for the phone-access rows.
    bound_port: std::sync::atomic::AtomicU16,
}

fn digest(s: &str) -> [u8; 32] {
    Sha256::digest(s.as_bytes()).into()
}

impl OsRuntime {
    /// Reads the stop secret (BOM and trailing newline stripped). An empty or unreadable file refuses the start.
    pub fn new(env: OsEnv) -> Result<Arc<OsRuntime>, String> {
        let t = std::fs::read_to_string(&env.token_file).map_err(|e| format!("{TOKEN_FILE_ENV} {}: {e}", env.token_file.display()))?;
        let t = t.trim_start_matches('\u{feff}').trim();
        if t.is_empty() {
            return Err(format!("{TOKEN_FILE_ENV} {} is empty", env.token_file.display()));
        }
        Ok(Arc::new(OsRuntime { stop_digest: digest(t), env, stop: tokio::sync::Notify::new(), bound_port: Default::default() }))
    }

    /// True when `Authorization: Bearer <secret>` carries the stop secret (constant time on the digests).
    pub fn stop_authorized(&self, authorization: Option<&str>) -> bool {
        let Some(given) = authorization.and_then(|a| a.trim().strip_prefix("Bearer ").or_else(|| a.trim().strip_prefix("bearer "))) else { return false };
        let given = given.trim();
        !given.is_empty() && digest(given).iter().zip(&self.stop_digest).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
    }

    /// Asks the daemon to stop; [OsRuntime::stopped] resolves (once, even when asked before anyone waits).
    pub fn request_stop(&self) {
        self.stop.notify_one();
    }

    pub async fn stopped(&self) {
        self.stop.notified().await;
    }

    /// Writes the ready file for a daemon listening on `bind_host:port`. The OS only accepts a loopback url.
    pub fn write_ready(&self, bind_host: &str, port: u16) -> Result<(), String> {
        self.write_ready_as(bind_host, port, std::process::id(), &|p| crate::health::pid_alive(p as i64))
    }

    /// [OsRuntime::write_ready] with the pid and the liveness check given (tests).
    fn write_ready_as(&self, bind_host: &str, port: u16, me: u32, alive: &dyn Fn(u32) -> bool) -> Result<(), String> {
        self.bound_port.store(port, std::sync::atomic::Ordering::Relaxed);
        let Some(path) = &self.env.ready_file else { return Ok(()) };
        if let Some(other) = live_other_owner(path, me, alive) {
            // TESTENV-1: an inherited BATTALION_READY_FILE (a test daemon started from a worker shell inside the OS) must
            // not take the ready file of the app that owns it. Not fatal: this daemon still serves, it is just not announced.
            // The contract gives no way to tell the OS launched us (no per-launch nonce), so the live-owner check is the guard.
            eprintln!("[infantry] ready file {} names live process {other}, not this one ({me}): not overwritten (inherited BATTALION_* env?)", path.display());
            return Ok(());
        }
        let body = ready_json(bind_host, port, me)?;
        write_atomic(path, body.as_bytes())
    }

    pub fn bound_port(&self) -> u16 {
        self.bound_port.load(std::sync::atomic::Ordering::Relaxed)
    }

    /// Removes the ready file (a clean stop leaves none behind).
    pub fn clear_ready(&self) {
        if let Some(p) = &self.env.ready_file {
            let _ = std::fs::remove_file(p);
        }
    }
}

/// TESTENV-1: the pid an existing ready file names when that process is alive and is not `me`; `None` when the file is
/// missing, unreadable, names no pid, names this process or a dead one (a stale file left by a crash is replaced).
pub fn live_other_owner(path: &Path, me: u32, alive: &dyn Fn(u32) -> bool) -> Option<u32> {
    let text = std::fs::read_to_string(path).ok()?;
    let v: serde_json::Value = serde_json::from_str(text.trim_start_matches('\u{feff}')).ok()?;
    let pid = u32::try_from(v.get("pid")?.as_u64()?).ok()?;
    (pid != me && pid != 0 && alive(pid)).then_some(pid)
}

/// The ready file text. A wildcard bind is reached on loopback; any other non-loopback bind cannot be an app's address.
pub fn ready_json(bind_host: &str, port: u16, pid: u32) -> Result<String, String> {
    let host = bind_host.trim().trim_start_matches('[').trim_end_matches(']');
    let url_host = match host.to_ascii_lowercase().as_str() {
        "0.0.0.0" => "127.0.0.1".to_string(),
        "::" => "[::1]".to_string(),
        h if crate::auth::is_loopback_host(h) => if h.contains(':') { format!("[{h}]") } else { h.to_string() },
        _ => return Err(format!("bound to {bind_host}: an app daemon must bind a loopback or wildcard address (the OS reaches it on loopback)")),
    };
    Ok(serde_json::json!({"contract": CONTRACT, "pid": pid, "url": format!("http://{url_host}:{port}"), "health": "/health", "stop": STOP_PATH}).to_string())
}

fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), String> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| format!("{}: {e}", dir.display()))?;
    }
    let tmp = path.with_extension("tmp");
    std::fs::write(&tmp, bytes).map_err(|e| format!("{}: {e}", tmp.display()))?;
    std::fs::rename(&tmp, path).map_err(|e| format!("{}: {e}", path.display()))
}

/// `<state>/launch.json`: how the daemon is reached from outside (a phone), written by the window instead of flags.
/// Every field is optional; a flag or an `INFANTRY_*` variable wins over it.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Launch {
    pub host: Option<String>,
    pub port: Option<u16>,
    pub token_file: Option<PathBuf>,
}

impl Launch {
    /// A missing file is no launch settings; a file that is not valid refuses the start (no silent fallback).
    pub fn read(state_dir: &Path) -> Result<Launch, String> {
        let path = state_dir.join(LAUNCH_FILE);
        let text = match std::fs::read_to_string(&path) {
            Ok(t) => t,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Launch::default()),
            Err(e) => return Err(format!("{}: {e}", path.display())),
        };
        let v: serde_json::Value = serde_json::from_str(text.trim_start_matches('\u{feff}')).map_err(|e| format!("{}: {e}", path.display()))?;
        let text = |k: &str| v.get(k).and_then(|x| x.as_str()).map(str::trim).filter(|s| !s.is_empty()).map(str::to_string);
        let port = match v.get("port") {
            None | Some(serde_json::Value::Null) => None,
            Some(p) => Some(p.as_u64().and_then(|n| u16::try_from(n).ok()).ok_or_else(|| format!("{}: port must be 0-65535", path.display()))?),
        };
        Ok(Launch { host: text("host"), port, token_file: text("tokenFile").map(PathBuf::from) })
    }
}

/// The modules of a plugins folder: `<tier>/<module>` folders holding a `module.json`.
fn modules_in(root: &Path) -> Vec<(String, String, PathBuf)> {
    let mut out = vec![];
    let Ok(tiers) = std::fs::read_dir(root) else { return out };
    for tier in tiers.filter_map(|e| e.ok()).filter(|e| e.path().is_dir()) {
        let tier_name = tier.file_name().to_string_lossy().into_owned();
        if tier_name.starts_with('.') {
            continue;
        }
        let Ok(mods) = std::fs::read_dir(tier.path()) else { continue };
        for m in mods.filter_map(|e| e.ok()).map(|e| e.path()).filter(|p| p.join("module.json").is_file()) {
            let name = m.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
            out.push((tier_name.clone(), name, m));
        }
    }
    out.sort();
    out
}

/// What [prepare_plugins] did.
#[derive(Debug, Default, PartialEq)]
pub struct PluginSync {
    /// User-added modules copied from the old place on this first start.
    pub migrated: Vec<String>,
    /// Package modules put into (or refreshed in) the plugins dir.
    pub synced: Vec<String>,
}

/// Makes `installed` (`<state>/plugins-installed`) hold the plugins:
/// 1. the first start copies every module of `legacy` (where user-added plugins lived, the program folder) that is not
///    there yet, once (a marker file); a later start never copies again, so a plugin the user removed stays removed;
/// 2. every start puts the package's modules (`bundled`) in, replacing a module whose `module.json` differs, so a newer
///    package updates its own plugins. A module only the user has is never touched, so it survives any package version.
pub fn prepare_plugins(installed: &Path, bundled: Option<&Path>, legacy: Option<&Path>) -> Result<PluginSync, String> {
    std::fs::create_dir_all(installed).map_err(|e| format!("{}: {e}", installed.display()))?;
    let mut sync = PluginSync::default();
    let mark = installed.join(MIGRATED_MARK);
    if !mark.exists() {
        if let Some(from) = legacy.filter(|l| l.is_dir() && !same_dir(l, installed)) {
            for (tier, name, dir) in modules_in(from) {
                let to = installed.join(&tier).join(&name);
                if !to.exists() {
                    crate::compose::installer::copy_dir(&dir, &to)?;
                    sync.migrated.push(format!("{tier}/{name}"));
                }
            }
        }
        std::fs::write(&mark, b"1").map_err(|e| format!("{}: {e}", mark.display()))?;
    }
    if let Some(from) = bundled.filter(|b| b.is_dir() && !same_dir(b, installed)) {
        for (tier, name, dir) in modules_in(from) {
            let to = installed.join(&tier).join(&name);
            let same = match (std::fs::read(dir.join("module.json")), std::fs::read(to.join("module.json"))) {
                (Ok(a), Ok(b)) => a == b,
                _ => false,
            };
            if same {
                continue;
            }
            // Copied beside first, then swapped in, so a failed copy never leaves a half module.
            let staging = installed.join(".syncing").join(&tier).join(&name);
            let _ = std::fs::remove_dir_all(&staging);
            crate::compose::installer::copy_dir(&dir, &staging)?;
            if to.exists() {
                std::fs::remove_dir_all(&to).map_err(|e| format!("{}: {e}", to.display()))?;
            }
            std::fs::create_dir_all(to.parent().unwrap_or(installed)).map_err(|e| e.to_string())?;
            std::fs::rename(&staging, &to).map_err(|e| format!("{}: {e}", to.display()))?;
            sync.synced.push(format!("{tier}/{name}"));
        }
        let _ = std::fs::remove_dir_all(installed.join(".syncing"));
    }
    Ok(sync)
}

fn same_dir(a: &Path, b: &Path) -> bool {
    match (std::fs::canonicalize(a), std::fs::canonicalize(b)) {
        (Ok(a), Ok(b)) => a == b,
        _ => a == b,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn env_of(pairs: &[(&str, &str)]) -> impl Fn(&str) -> Option<String> {
        let m: Vec<(String, String)> = pairs.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect();
        move |k: &str| m.iter().find(|(n, _)| n == k).map(|(_, v)| v.clone())
    }

    fn module(root: &Path, tier: &str, name: &str, version: &str) {
        let d = root.join(tier).join(name);
        std::fs::create_dir_all(d.join("sub")).unwrap();
        std::fs::write(d.join("module.json"), format!(r#"{{"name":"{name}","version":"{version}"}}"#)).unwrap();
        std::fs::write(d.join("sub").join("f.txt"), version).unwrap();
    }

    #[test]
    fn contract_names_match_the_battalion_app_api() {
        assert_eq!(CONTRACT_ENV, "BATTALION_CONTRACT");
    }

    #[test]
    fn an_explicit_other_state_dir_is_not_an_os_app() {
        let os = |sd: Option<&str>| OsEnv { ready_file: None, token_file: "t".into(), state_dir: sd.map(PathBuf::from), app_dir: None, ui_origin: None };
        assert!(applies_to(&os(Some("/os/state")), None));
        assert!(applies_to(&os(None), None));
        assert!(applies_to(&os(Some("/os/state")), Some(Path::new("/os/state/"))));
        assert!(!applies_to(&os(Some("/os/state")), Some(Path::new("/tmp/td-1"))));
        assert!(!applies_to(&os(None), Some(Path::new("/tmp/td-1"))));
        assert_eq!((READY_FILE_ENV, TOKEN_FILE_ENV, STATE_DIR_ENV), ("BATTALION_READY_FILE", "BATTALION_TOKEN_FILE", "BATTALION_STATE_DIR"));
        assert_eq!((APP_DIR_ENV, UI_ORIGIN_ENV, CONTRACT), ("BATTALION_APP_DIR", "BATTALION_UI_ORIGIN", 1));
    }

    #[test]
    fn without_the_contract_nothing_is_an_app() {
        assert_eq!(OsEnv::from_env(&env_of(&[])), Ok(None));
        assert_eq!(OsEnv::from_env(&env_of(&[(CONTRACT_ENV, "  ")])), Ok(None));
        // The other names alone do not switch it on.
        assert_eq!(OsEnv::from_env(&env_of(&[(READY_FILE_ENV, "r.json"), (TOKEN_FILE_ENV, "t")])), Ok(None));
    }

    #[test]
    fn with_the_contract_the_env_is_read_and_bad_values_refuse() {
        let e = OsEnv::from_env(&env_of(&[(CONTRACT_ENV, "1"), (TOKEN_FILE_ENV, "t"), (READY_FILE_ENV, "r.json"), (UI_ORIGIN_ENV, "http://127.0.0.1:5000/"), (STATE_DIR_ENV, "s")])).unwrap().unwrap();
        assert_eq!(e.ui_origin.as_deref(), Some("http://127.0.0.1:5000"));
        assert_eq!(e.ready_file, Some(PathBuf::from("r.json")));
        assert!(OsEnv::from_env(&env_of(&[(CONTRACT_ENV, "2"), (TOKEN_FILE_ENV, "t")])).unwrap_err().contains("contract 1"));
        assert!(OsEnv::from_env(&env_of(&[(CONTRACT_ENV, "x"), (TOKEN_FILE_ENV, "t")])).is_err());
        assert!(OsEnv::from_env(&env_of(&[(CONTRACT_ENV, "1")])).unwrap_err().contains(TOKEN_FILE_ENV));
    }

    #[test]
    fn stop_needs_the_exact_bearer() {
        let d = tempfile::tempdir().unwrap();
        let t = d.path().join("token");
        std::fs::write(&t, "\u{feff}s3cret\r\n").unwrap();
        let rt = OsRuntime::new(OsEnv { ready_file: None, token_file: t.clone(), state_dir: None, app_dir: None, ui_origin: None }).unwrap();
        assert!(rt.stop_authorized(Some("Bearer s3cret")));
        assert!(!rt.stop_authorized(Some("Bearer s3cret2")));
        assert!(!rt.stop_authorized(Some("Bearer ")));
        assert!(!rt.stop_authorized(Some("s3cret")));
        assert!(!rt.stop_authorized(None));
        std::fs::write(&t, "  \n").unwrap();
        assert!(OsRuntime::new(OsEnv { ready_file: None, token_file: t, state_dir: None, app_dir: None, ui_origin: None }).is_err());
    }

    #[test]
    fn ready_file_is_loopback_json_and_removed_on_clear() {
        let v: serde_json::Value = serde_json::from_str(&ready_json("0.0.0.0", 4242, 7).unwrap()).unwrap();
        assert_eq!((v["contract"].as_u64(), v["pid"].as_u64(), v["url"].as_str(), v["stop"].as_str(), v["health"].as_str()), (Some(1), Some(7), Some("http://127.0.0.1:4242"), Some("/stop"), Some("/health")));
        assert_eq!(serde_json::from_str::<serde_json::Value>(&ready_json("::1", 1, 7).unwrap()).unwrap()["url"], "http://[::1]:1");
        assert!(ready_json("192.168.1.5", 1, 7).is_err());
        let d = tempfile::tempdir().unwrap();
        std::fs::write(d.path().join("token"), "x").unwrap();
        let ready = d.path().join("run").join("ready.json");
        let rt = OsRuntime::new(OsEnv { ready_file: Some(ready.clone()), token_file: d.path().join("token"), state_dir: None, app_dir: None, ui_origin: None }).unwrap();
        rt.write_ready("127.0.0.1", 9).unwrap();
        assert!(ready.is_file());
        rt.clear_ready();
        assert!(!ready.exists());
    }

    #[test]
    fn a_ready_file_of_another_live_process_is_never_overwritten() {
        let d = tempfile::tempdir().unwrap();
        let ready = d.path().join("run").join("ready.json");
        std::fs::create_dir_all(ready.parent().unwrap()).unwrap();
        std::fs::write(&ready, r#"{"contract":1,"pid":4242,"url":"http://127.0.0.1:1"}"#).unwrap();
        assert_eq!(live_other_owner(&ready, 7, &|_| true), Some(4242));
        assert_eq!(live_other_owner(&ready, 7, &|_| false), None, "a dead owner's file is stale");
        assert_eq!(live_other_owner(&ready, 4242, &|_| true), None, "our own file");
        assert_eq!(live_other_owner(&d.path().join("none.json"), 7, &|_| true), None);
        std::fs::write(&ready, "\u{feff}not json").unwrap();
        assert_eq!(live_other_owner(&ready, 7, &|_| true), None);
        // The write itself: a live owner keeps its file byte for byte; a dead one's is replaced.
        let owner = r#"{"contract":1,"pid":26000,"url":"http://127.0.0.1:18990"}"#;
        std::fs::write(&ready, owner).unwrap();
        std::fs::write(d.path().join("token"), "x").unwrap();
        let rt = OsRuntime::new(OsEnv { ready_file: Some(ready.clone()), token_file: d.path().join("token"), state_dir: None, app_dir: None, ui_origin: None }).unwrap();
        rt.write_ready_as("127.0.0.1", 18903, 7, &|p| p == 26000).unwrap();
        assert_eq!(std::fs::read_to_string(&ready).unwrap(), owner, "the owner's ready file is untouched");
        rt.write_ready_as("127.0.0.1", 18903, 7, &|_| false).unwrap();
        let v: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(&ready).unwrap()).unwrap();
        assert_eq!((v["pid"].as_u64(), v["url"].as_str()), (Some(7), Some("http://127.0.0.1:18903")));
    }

    #[test]
    fn launch_file_reads_and_a_bad_one_refuses() {
        let d = tempfile::tempdir().unwrap();
        assert_eq!(Launch::read(d.path()).unwrap(), Launch::default());
        std::fs::write(d.path().join(LAUNCH_FILE), r#"{"host":"0.0.0.0","port":18991,"tokenFile":"C:/t"}"#).unwrap();
        assert_eq!(Launch::read(d.path()).unwrap(), Launch { host: Some("0.0.0.0".into()), port: Some(18991), token_file: Some("C:/t".into()) });
        std::fs::write(d.path().join(LAUNCH_FILE), r#"{"port":70000}"#).unwrap();
        assert!(Launch::read(d.path()).is_err());
        std::fs::write(d.path().join(LAUNCH_FILE), "nope").unwrap();
        assert!(Launch::read(d.path()).is_err());
    }

    #[test]
    fn a_user_added_plugin_survives_two_package_versions() {
        let d = tempfile::tempdir().unwrap();
        let (program, state) = (d.path().join("program-plugins"), d.path().join("state"));
        let installed = state.join(INSTALLED_DIR);
        // Before the app contract: the package plugin and the user's own live in the program folder.
        module(&program, "official", "Board", "1");
        module(&program, "community", "MyOwn", "1");
        let first = prepare_plugins(&installed, Some(&program), Some(&program)).unwrap();
        assert_eq!(first.migrated.len(), 2);
        // Package v2 (a new folder, the old one gone with the update): Board changes, MyOwn is not in it.
        let v2 = d.path().join("pkg-v2");
        module(&v2, "official", "Board", "2");
        std::fs::remove_dir_all(&program).unwrap();
        let second = prepare_plugins(&installed, Some(&v2), Some(&program)).unwrap();
        assert_eq!(second.synced, vec!["official/Board".to_string()]);
        assert!(second.migrated.is_empty(), "the migration runs once");
        // Package v3.
        let v3 = d.path().join("pkg-v3");
        module(&v3, "official", "Board", "3");
        module(&v3, "core", "New", "1");
        prepare_plugins(&installed, Some(&v3), None).unwrap();
        assert_eq!(std::fs::read_to_string(installed.join("community/MyOwn/sub/f.txt")).unwrap(), "1", "the user's plugin is untouched");
        assert!(std::fs::read_to_string(installed.join("official/Board/module.json")).unwrap().contains(r#""version":"3""#));
        assert_eq!(std::fs::read_to_string(installed.join("official/Board/sub/f.txt")).unwrap(), "3", "a package module is replaced whole");
        assert!(installed.join("core/New/module.json").is_file());
        // A plugin the user removed does not come back from the old place.
        std::fs::remove_dir_all(installed.join("community/MyOwn")).unwrap();
        prepare_plugins(&installed, Some(&v3), Some(&program)).unwrap();
        assert!(!installed.join("community/MyOwn").exists());
    }

    #[test]
    fn the_package_folder_is_never_synced_onto_itself() {
        let d = tempfile::tempdir().unwrap();
        module(d.path(), "official", "Board", "1");
        let r = prepare_plugins(d.path(), Some(d.path()), Some(d.path())).unwrap();
        assert_eq!(r, PluginSync::default());
    }
}
