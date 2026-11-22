// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The `harness_install_status` and `harness_install` frames (RUST PARITY of Kotlin `HarnessInstaller` +
//! `NodeRuntimeInstaller`, ONBOARD-2): detect the agent CLIs on this machine and install them with npm into the app's OWN
//! folder (`<state>/harness-tools`), so a clean PC needs no admin rights and no Node.js of its own.
//!
//! - `harness_install_status` -> `harness_install_status`: every harness (id, label, npm package, state, version, path),
//!   the npm found, whether Node.js can be installed by the daemon, whether an install is running and its log lines.
//! - `harness_install {ids, update, runtime}` -> `harness_install_result {started}`: the work runs on a thread; the log
//!   (`<id>: installed <version>` / `<id>: failed - <reason>`, the lines the wizard reads) is in the next status answers.
//!
//! Node.js is the nodejs.org LTS Windows zip, checked against the release's SHASUMS256.txt, unpacked flat into the
//! prefix (the layout npm uses for a global prefix on Windows); nothing needs to be on PATH.
//! The prefix is added to this process's PATH (at start and after each install) so the harnesses find what it installed.
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_protocol::frames as pf;
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const FRAMES: &[&str] = &[pf::HARNESS_INSTALL_STATUS, pf::HARNESS_INSTALL];
const DIST_BASE: &str = "https://nodejs.org/dist";
/// One npm install may not take longer than this (a named limit, not a hidden one).
const NPM_TIMEOUT: Duration = Duration::from_secs(900);
/// The log line that carries npm's latest output, rewritten in place while the install runs.
const NPM_LIVE: &str = "[npm] ";
/// The log line that carries the Node.js download's progress, rewritten in place.
const NODE_DOWNLOAD: &str = "[node] downloading ";
const VERSION_TIMEOUT: Duration = Duration::from_secs(8);
const NODE_GUIDANCE: &str = "Node.js is not installed on this machine. Infantry AI can install it for you into its own folder (no admin prompt).";
const NODE_MANUAL: &str = "Install Node.js from nodejs.org, then press Check again.";

/// One harness CLI the installer knows: `(id, label, command, npm package)`.
const KNOWN: &[(&str, &str, &str, &str)] = &[
    ("claude", "Claude Code", "claude", "@anthropic-ai/claude-code"),
    ("codex", "Codex", "codex", "@openai/codex"),
    ("opencode", "OpenCode", "opencode", "opencode-ai"),
    ("pi", "Pi", "pi", "@earendil-works/pi-coding-agent"),
];

pub struct HarnessInstall {
    prefix: PathBuf,
    log: Arc<Mutex<Vec<String>>>,
    busy: Arc<AtomicBool>,
    versions: Arc<Mutex<HashMap<PathBuf, Option<String>>>>,
    /// Runs after an install, before it reports done: the daemon's harness list is rebuilt so it shows the new CLI.
    on_changed: Arc<dyn Fn() + Send + Sync>,
    /// Runs when the install is over and the report no longer says busy: the daemon pushes it to every client, so a window that
    /// did not start the install (another window, the CLI) re-reads the report.
    on_done: Arc<dyn Fn() + Send + Sync>,
}

impl HarnessInstall {
    pub fn new(state_dir: &Path, on_changed: Arc<dyn Fn() + Send + Sync>, on_done: Arc<dyn Fn() + Send + Sync>) -> Arc<HarnessInstall> {
        let prefix = state_dir.join("harness-tools");
        infantry_proc::user_path::refresh_with(infantry_proc::user_path::registry_dirs, true);
        add_to_path(&prefix);
        Arc::new(HarnessInstall { prefix, log: Arc::default(), busy: Arc::default(), versions: Arc::default(), on_changed, on_done })
    }

    /// A progress line that is rewritten in place (the latest npm line, the download percent) instead of piling up: it replaces the
    /// last log entry when that one starts with [prefix].
    fn live(log: &Mutex<Vec<String>>, prefix: &str, line: impl Into<String>) {
        let mut l = log.lock().unwrap_or_else(|e| e.into_inner());
        match l.last_mut() {
            Some(last) if last.starts_with(prefix) => *last = line.into(),
            _ => l.push(line.into()),
        }
    }

    fn note(log: &Mutex<Vec<String>>, line: impl Into<String>) {
        let mut l = log.lock().unwrap_or_else(|e| e.into_inner());
        l.push(line.into());
        let n = l.len();
        if n > 200 {
            l.drain(..n - 200);
        }
    }

    fn report(&self) -> Value {
        infantry_proc::user_path::refresh();
        let npm = find_npm(&self.prefix);
        let installable = npm.is_none() && node_platform().is_some();
        let harnesses: Vec<Value> = KNOWN
            .iter()
            .map(|(id, label, cmd, pkg)| {
                let path = find_cli(&self.prefix, cmd);
                let (state, version) = match &path {
                    None => ("missing", None),
                    Some(p) => match self.version_of(p) {
                        Some(v) => ("installed", Some(v)),
                        None => ("unreadable", None),
                    },
                };
                let managed = path.as_ref().is_some_and(|p| p.starts_with(&self.prefix));
                json!({ "id": id, "label": label, "npmPackage": pkg, "state": state, "installedVersion": version,
                        "path": path.map(|p| p.display().to_string()), "managed": managed })
            })
            .collect();
        let guidance = if npm.is_some() { Value::Null } else { json!(if installable { NODE_GUIDANCE } else { NODE_MANUAL }) };
        json!({
            "harnesses": harnesses, "prefixDir": self.prefix.display().to_string(),
            "npmPath": npm.map(|p| p.display().to_string()), "guidance": guidance, "nodeInstallable": installable,
            "busy": self.busy.load(Ordering::SeqCst), "log": *self.log.lock().unwrap_or_else(|e| e.into_inner()),
        })
    }

    /// `<cli> --version`, once per path (a probe can cost a process start).
    fn version_of(&self, path: &Path) -> Option<String> {
        if let Some(v) = self.versions.lock().unwrap_or_else(|e| e.into_inner()).get(path) {
            return v.clone();
        }
        // A running install is polled every 1.5 s: probing four CLIs would delay every answer by seconds (a probe is a process start,
        // slow under antivirus), and the progress bar would only show once the probes ended. Cached versions only while busy.
        if self.busy.load(Ordering::SeqCst) {
            return None;
        }
        let v = read_version(path);
        // An unreadable version is not cached: it may be a CLI that was still being unpacked.
        if v.is_some() {
            self.versions.lock().unwrap_or_else(|e| e.into_inner()).insert(path.to_path_buf(), v.clone());
        }
        v
    }

    fn start(&self, ids: Vec<String>, update: bool, runtime: bool) -> Result<(), String> {
        if self.busy.swap(true, Ordering::SeqCst) {
            return Err("an install is already running".into());
        }
        let (prefix, log, busy) = (self.prefix.clone(), self.log.clone(), self.busy.clone());
        let (versions, on_changed, on_done) = (self.versions.clone(), self.on_changed.clone(), self.on_done.clone());
        std::thread::spawn(move || {
            run_install(&prefix, &log, &ids, update, runtime);
            add_to_path(&prefix);
            versions.lock().unwrap_or_else(|e| e.into_inner()).clear();
            on_changed();
            busy.store(false, Ordering::SeqCst);
            on_done();
        });
        Ok(())
    }
}

impl FrameUnit for HarnessInstall {
    fn name(&self) -> &'static str {
        "harness-install"
    }
    fn frames(&self) -> &'static [&'static str] {
        FRAMES
    }
    fn handle(&self, env: &Envelope, _ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let (ty, data) = match env.r#type.as_str() {
            pf::HARNESS_INSTALL_STATUS => (pf::HARNESS_INSTALL_STATUS, self.report()),
            pf::HARNESS_INSTALL => {
                let req: Value = if env.data.trim().is_empty() { json!({}) } else { serde_json::from_str(&env.data).map_err(|e| Refusal::new(pf::HARNESS_INSTALL, e))? };
                let ids: Vec<String> = req["ids"].as_array().map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect()).unwrap_or_default();
                if let Some(bad) = ids.iter().find(|i| !KNOWN.iter().any(|k| k.0 == i.as_str())) {
                    return Err(Refusal::new(pf::HARNESS_INSTALL, format!("unknown harness '{bad}'")));
                }
                let (update, runtime) = (req["update"].as_bool().unwrap_or(false), req["runtime"].as_bool().unwrap_or(false));
                self.start(ids, update, runtime).map_err(|e| Refusal::new(pf::HARNESS_INSTALL, e))?;
                (pf::HARNESS_INSTALL_RESULT, json!({ "started": true }))
            }
            other => return Err(Refusal::new(other, "not a harness install frame")),
        };
        let mut e = Envelope::new(ty, data.to_string());
        e.session_id = env.session_id.clone();
        e.request_id = env.request_id.clone();
        Ok(vec![e])
    }
}

// ---- finding things ----

/// Puts [prefix] first on this process's PATH once (the harnesses and every child they start inherit it).
fn add_to_path(prefix: &Path) {
    let mut dirs = vec![prefix.to_path_buf()];
    if !cfg!(windows) {
        dirs.push(prefix.join("bin"));
    }
    let cur = std::env::var_os("PATH").unwrap_or_default();
    let have: Vec<PathBuf> = std::env::split_paths(&cur).collect();
    let add: Vec<PathBuf> = dirs.into_iter().filter(|d| !have.contains(d)).collect();
    if add.is_empty() {
        return;
    }
    if let Ok(joined) = std::env::join_paths(add.into_iter().chain(have)) {
        std::env::set_var("PATH", joined);
    }
}

fn exts() -> &'static [&'static str] {
    if cfg!(windows) { &[".cmd", ".exe", ""] } else { &[""] }
}

/// The app's own copy first (`<prefix>/<cmd>.cmd`), then the machine's PATH.
fn find_cli(prefix: &Path, cmd: &str) -> Option<PathBuf> {
    let own = [prefix.to_path_buf(), prefix.join("bin")];
    own.iter().flat_map(|d| exts().iter().map(move |e| d.join(format!("{cmd}{e}")))).find(|p| p.is_file()).or_else(|| infantry_harness::shim::find_on_path(&infantry_harness::LocalProbe, cmd))
}

fn find_npm(prefix: &Path) -> Option<PathBuf> {
    let own = prefix.join(if cfg!(windows) { "npm.cmd" } else { "bin/npm" });
    if own.is_file() {
        return Some(own);
    }
    find_cli(prefix, "npm")
}

fn read_version(path: &Path) -> Option<String> {
    let path = path.to_path_buf();
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let out = infantry_proc::command(&path).arg("--version").stdin(std::process::Stdio::null()).output().ok();
        let _ = tx.send(out);
    });
    let out = rx.recv_timeout(VERSION_TIMEOUT).ok()??;
    if !out.status.success() {
        return None;
    }
    String::from_utf8_lossy(&out.stdout).lines().map(str::trim).find(|l| !l.is_empty()).map(|l| l.split_whitespace().next().unwrap_or(l).to_string())
}

// ---- installing ----

fn run_install(prefix: &Path, log: &Arc<Mutex<Vec<String>>>, ids: &[String], update: bool, runtime: bool) {
    let note = |s: String| HarnessInstall::note(log, s);
    let mut npm = find_npm(prefix);
    if npm.is_none() {
        match node_platform() {
            Some(platform) => match install_node(prefix, platform, &note, Some(log)) {
                Ok(()) => {
                    note("node: installed".into());
                    npm = find_npm(prefix);
                }
                Err(e) => {
                    note(format!("node: failed - {e}"));
                    return;
                }
            },
            None => {
                note(format!("node: failed - {NODE_MANUAL}"));
                return;
            }
        }
    }
    if runtime {
        if npm.is_some() && !log.lock().unwrap_or_else(|e| e.into_inner()).iter().any(|l| l == "node: installed") {
            note("node: skipped - already installed".into());
        }
        return;
    }
    let Some(npm) = npm else {
        note("node: failed - npm was not found after the install".into());
        return;
    };
    let targets: Vec<&(&str, &str, &str, &str)> = KNOWN.iter().filter(|k| ids.is_empty() || ids.iter().any(|i| i == k.0)).collect();
    for (id, _, cmd, pkg) in targets {
        if !update && find_cli(prefix, cmd).is_some() {
            note(format!("{id}: skipped - already installed"));
            continue;
        }
        note(format!("[{id}] npm install -g {pkg}"));
        match npm_install(&npm, prefix, pkg, log.clone()) {
            Ok(()) => match find_cli(prefix, cmd).and_then(|p| read_version(&p)) {
                Some(v) => note(format!("{id}: installed {v}")),
                None => note(format!("{id}: failed - npm finished but {cmd} does not run")),
            },
            Err(e) => note(format!("{id}: failed - {e}")),
        }
    }
}

fn npm_install(npm: &Path, prefix: &Path, pkg: &str, log_arc: Arc<Mutex<Vec<String>>>) -> Result<(), String> {
    let mut path = std::ffi::OsString::from(prefix.as_os_str());
    path.push(if cfg!(windows) { ";" } else { ":" });
    path.push(std::env::var_os("PATH").unwrap_or_default());
    let mut child = infantry_proc::command(npm)
        // --loglevel=http makes npm print each package it fetches, so there is a live line to show.
        .args(["install", "-g", "--no-fund", "--no-audit", "--loglevel=http", "--prefix"])
        .arg(prefix)
        .arg(format!("{pkg}@latest"))
        .env("PATH", path)
        .stdin(std::process::Stdio::null())
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
        .map_err(|e| format!("could not start npm: {e}"))?;
    let tail: Arc<Mutex<Vec<String>>> = Arc::default();
    let log_live = log_arc;
    let mut readers = Vec::new();
    for stream in [child.stdout.take().map(|s| Box::new(s) as Box<dyn Read + Send>), child.stderr.take().map(|s| Box::new(s) as Box<dyn Read + Send>)].into_iter().flatten() {
        let (tail, log) = (tail.clone(), log_live.clone());
        readers.push(std::thread::spawn(move || {
            for line in std::io::BufRead::lines(std::io::BufReader::new(stream)).map_while(Result::ok) {
                let line = line.trim().to_string();
                if line.is_empty() {
                    continue;
                }
                // The latest npm line, live (the owner sees it moving); the last few are kept for an error.
                HarnessInstall::live(&log, NPM_LIVE, format!("{NPM_LIVE}{}", shown_npm(&line)));
                let mut t = tail.lock().unwrap_or_else(|e| e.into_inner());
                t.push(line);
                let n = t.len();
                if n > 20 {
                    t.drain(..n - 20);
                }
            }
        }));
    }
    let start = Instant::now();
    let status = loop {
        match child.try_wait() {
            Ok(Some(s)) => break Some(s),
            Ok(None) if start.elapsed() > NPM_TIMEOUT => {
                let _ = child.kill();
                let _ = child.wait();
                break None;
            }
            Ok(None) => std::thread::sleep(Duration::from_millis(300)),
            Err(e) => return Err(format!("waiting for npm failed: {e}")),
        }
    };
    for r in readers {
        let _ = r.join();
    }
    let t = tail.lock().unwrap_or_else(|e| e.into_inner());
    match status {
        Some(s) if s.success() => Ok(()),
        Some(s) => Err(format!("npm exited with {} ({})", s.code().map_or("a signal".into(), |c| c.to_string()), t.last().cloned().unwrap_or_default())),
        None => Err(format!("npm timed out after {}s", NPM_TIMEOUT.as_secs())),
    }
}

// ---- Node.js ----

/// `win-x64` / `win-arm64`; None = no automatic install on this machine.
fn node_platform() -> Option<&'static str> {
    if !cfg!(windows) {
        return None;
    }
    match std::env::consts::ARCH {
        "x86_64" => Some("win-x64"),
        "aarch64" => Some("win-arm64"),
        _ => None,
    }
}

/// npm's http lines are long (`http fetch GET 200 https://registry.npmjs.org/x 123ms`): cut for one live line.
fn shown_npm(line: &str) -> String {
    line.chars().take(160).collect()
}

/// `12.3 of 40.1 MB (31%)` for a download in progress.
fn download_text(done: u64, total: Option<u64>) -> String {
    let mb = |b: u64| format!("{:.1}", b as f64 / 1_048_576.0);
    match total.filter(|t| *t > 0) {
        Some(t) => format!("{} of {} MB ({}%)", mb(done), mb(t), (done.min(t) * 100) / t),
        None => format!("{} MB", mb(done)),
    }
}

fn http_get(url: &str) -> Result<Vec<u8>, String> {
    http_get_with(url, &|_, _| {})
}

fn http_get_with(url: &str, progress: &dyn Fn(u64, Option<u64>)) -> Result<Vec<u8>, String> {
    // ureq is built with only the native-tls feature, which is not a default backend: without an explicit connector every
    // https call fails "no TLS backend is configured" (Windows Sandbox run 6: the wizard's Node step never downloaded).
    let tls = native_tls::TlsConnector::new().map_err(|e| format!("no TLS for {url}: {e}"))?;
    let agent = ureq::AgentBuilder::new()
        .tls_connector(Arc::new(tls))
        .timeout_connect(Duration::from_secs(20))
        .timeout_read(Duration::from_secs(120))
        .build();
    let resp = agent.get(url).call().map_err(|e| format!("could not reach {url}: {e}"))?;
    let total = resp.header("Content-Length").and_then(|v| v.parse::<u64>().ok());
    let mut reader = resp.into_reader();
    let mut buf = Vec::new();
    let mut chunk = [0u8; 65536];
    let mut last = 0u64;
    loop {
        let n = reader.read(&mut chunk).map_err(|e| format!("download of {url} failed: {e}"))?;
        if n == 0 {
            break;
        }
        buf.extend_from_slice(&chunk[..n]);
        // About every 512 KB, not per chunk: a log line is rewritten, a frame can follow.
        if buf.len() as u64 - last >= 524_288 {
            last = buf.len() as u64;
            progress(last, total);
        }
    }
    progress(buf.len() as u64, total);
    Ok(buf)
}

/// The newest release in nodejs.org's `index.json` that is an LTS and ships a [platform] zip.
fn latest_lts(index: &str, platform: &str) -> Option<String> {
    let want = format!("{platform}-zip");
    let releases: Value = serde_json::from_str(index).ok()?;
    releases.as_array()?.iter().find_map(|r| {
        let lts = r["lts"].is_string();
        let has = r["files"].as_array().is_some_and(|f| f.iter().any(|x| x.as_str() == Some(want.as_str())));
        let v = r["version"].as_str()?;
        (lts && has && v.starts_with('v') && v[1..].split('.').count() == 3 && v[1..].split('.').all(|p| p.parse::<u32>().is_ok())).then(|| v.to_string())
    })
}

/// The hex hash SHASUMS256.txt gives [file] (`<hash>  <file>` lines).
fn sha_for(sums: &str, file: &str) -> Option<String> {
    sums.lines().map(|l| l.split_whitespace().collect::<Vec<_>>()).find(|p| p.len() == 2 && p[1] == file).map(|p| p[0].to_string()).filter(|h| h.len() == 64 && h.chars().all(|c| c.is_ascii_hexdigit()))
}

fn install_node(prefix: &Path, platform: &str, note: &dyn Fn(String), live: Option<&Mutex<Vec<String>>>) -> Result<(), String> {
    std::fs::create_dir_all(prefix).map_err(|e| format!("cannot create {}: {e}", prefix.display()))?;
    let work = prefix.join(".node-download");
    let _ = std::fs::remove_dir_all(&work);
    std::fs::create_dir_all(&work).map_err(|e| format!("cannot create {}: {e}", work.display()))?;
    let result = (|| {
        note("[node] looking up the current Node.js LTS release".into());
        let index = String::from_utf8_lossy(&http_get(&format!("{DIST_BASE}/index.json"))?).to_string();
        let version = latest_lts(&index, platform).ok_or_else(|| format!("nodejs.org lists no LTS release for {platform}"))?;
        let zip_name = format!("node-{version}-{platform}.zip");
        let sums = String::from_utf8_lossy(&http_get(&format!("{DIST_BASE}/{version}/SHASUMS256.txt"))?).to_string();
        let want = sha_for(&sums, &zip_name).ok_or_else(|| format!("the Node.js checksums do not list {zip_name}"))?;
        note(format!("{NODE_DOWNLOAD}{zip_name}"));
        let bytes = http_get_with(&format!("{DIST_BASE}/{version}/{zip_name}"), &|done, total| {
            if let Some(live) = live {
                HarnessInstall::live(live, NODE_DOWNLOAD, format!("{NODE_DOWNLOAD}{zip_name}: {}", download_text(done, total)));
            }
        })?;
        let got = Sha256::digest(&bytes).iter().map(|b| format!("{b:02x}")).collect::<String>();
        if !got.eq_ignore_ascii_case(&want) {
            return Err("the Node.js download does not match its checksum".to_string());
        }
        let zip = work.join(&zip_name);
        std::fs::write(&zip, &bytes).map_err(|e| format!("cannot write the download: {e}"))?;
        note(format!("[node] unpacking into {}", prefix.display()));
        // bsdtar ships with Windows 10+ and reads zip; --strip-components drops the archive's top folder.
        let out = infantry_proc::command("tar").arg("-xf").arg(&zip).arg("-C").arg(prefix).args(["--strip-components", "1"]).output().map_err(|e| format!("cannot run tar: {e}"))?;
        if !out.status.success() {
            return Err(format!("unpacking failed: {}", String::from_utf8_lossy(&out.stderr).trim()));
        }
        if !prefix.join("node.exe").is_file() || !prefix.join("npm.cmd").is_file() {
            return Err("the Node.js archive did not contain node.exe and npm.cmd".into());
        }
        note(format!("[node] Node.js {version} is ready"));
        Ok(())
    })();
    let _ = std::fs::remove_dir_all(&work);
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_newest_lts_with_a_zip_is_picked() {
        let idx = r#"[{"version":"v25.1.0","lts":false,"files":["win-x64-zip"]},
            {"version":"v24.11.0","lts":"Krypton","files":["win-x64-zip","win-arm64-zip"]},
            {"version":"v22.1.0","lts":"Jod","files":["win-x64-zip"]}]"#;
        assert_eq!(latest_lts(idx, "win-x64").as_deref(), Some("v24.11.0"));
        assert_eq!(latest_lts(idx, "win-ia32"), None);
        assert_eq!(latest_lts("not json", "win-x64"), None);
    }

    #[test]
    fn a_progress_line_is_rewritten_in_place_and_a_download_reads_as_percent() {
        let log: Mutex<Vec<String>> = Mutex::default();
        HarnessInstall::note(&log, "[claude] npm install -g x");
        HarnessInstall::live(&log, NPM_LIVE, "[npm] http fetch one");
        HarnessInstall::live(&log, NPM_LIVE, "[npm] http fetch two");
        assert_eq!(*log.lock().unwrap(), vec!["[claude] npm install -g x".to_string(), "[npm] http fetch two".to_string()]);
        assert_eq!(download_text(10_485_760, Some(41_943_040)), "10.0 of 40.0 MB (25%)");
        assert_eq!(download_text(1_048_576, None), "1.0 MB");
    }

    #[test]
    fn a_checksum_is_read_by_file_name_and_must_be_64_hex() {
        let h = "a".repeat(64);
        let sums = format!("{h}  node-v1.0.0-win-x64.zip\n{}  node-v1.0.0-linux.tar.gz\nbad  x", "b".repeat(64));
        assert_eq!(sha_for(&sums, "node-v1.0.0-win-x64.zip").as_deref(), Some(h.as_str()));
        assert_eq!(sha_for(&sums, "x"), None);
        assert_eq!(sha_for(&sums, "missing.zip"), None);
    }

    #[test]
    fn a_status_lists_every_known_harness_and_refuses_an_unknown_id() {
        let dir = std::env::temp_dir().join(format!("inf-hi-{}", std::process::id()));
        let u = HarnessInstall::new(&dir, Arc::new(|| {}), Arc::new(|| {}));
        let r = u.report();
        assert_eq!(r["harnesses"].as_array().unwrap().len(), KNOWN.len());
        assert_eq!(r["busy"], false);
        let bus = infantry_core::bus::EventBus::default();
        let ctx = FrameCtx { conn_id: 1, owner: true, bus };
        let e = Envelope::new(pf::HARNESS_INSTALL, json!({"ids":["nope"]}).to_string());
        assert!(u.handle(&e, &ctx).unwrap_err().0.contains("unknown harness"));
    }

    /// INSTALL-PROGRESS-2: while a harness installs, the status says busy and carries the live `[npm]` line; the start is acked at once.
    #[test]
    fn a_harness_install_reports_busy_and_a_live_npm_line_while_it_runs() {
        let dir = std::env::temp_dir().join(format!("inf-hi-live-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let fake = if cfg!(windows) { dir.join("npm.cmd") } else { dir.join("bin/npm") };
        std::fs::create_dir_all(fake.parent().unwrap()).unwrap();
        let body = if cfg!(windows) { "@echo off
echo npm http fetch GET 200 https://registry.npmjs.org/x 12ms 1>&2
ping -n 4 127.0.0.1 >nul
exit /b 1
" } else { "#!/bin/sh
echo npm http fetch GET 200 https://registry.npmjs.org/x 12ms >&2
sleep 3
exit 1
" };
        std::fs::write(&fake, body).unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&fake, std::fs::Permissions::from_mode(0o755)).unwrap();
        }
        let u = HarnessInstall::new(&dir, Arc::new(|| {}), Arc::new(|| {}));
        let ctx = FrameCtx { conn_id: 1, owner: true, bus: infantry_core::bus::EventBus::default() };
        let ack = u.handle(&Envelope::new(pf::HARNESS_INSTALL, json!({"ids":["claude"],"update":true}).to_string()), &ctx).unwrap();
        assert_eq!(ack[0].r#type, pf::HARNESS_INSTALL_RESULT);
        let t0 = Instant::now();
        let r0 = u.report();
        assert!(t0.elapsed() < Duration::from_secs(2), "a status while busy must not probe CLIs: {:?}", t0.elapsed());
        assert_eq!(r0["busy"], true, "busy straight after the ack");
        let deadline = Instant::now() + Duration::from_secs(10);
        loop {
            let r = u.report();
            let log: Vec<String> = r["log"].as_array().unwrap().iter().filter_map(|v| v.as_str().map(String::from)).collect();
            if log.iter().any(|l| l.starts_with("[npm] ") && l.contains("registry.npmjs.org")) {
                assert_eq!(r["busy"], true, "the npm line is seen while busy");
                break;
            }
            assert!(Instant::now() < deadline, "no [npm] line in {log:?}");
            std::thread::sleep(Duration::from_millis(100));
        }
        while u.report()["busy"] == true {
            assert!(Instant::now() < deadline + Duration::from_secs(10));
            std::thread::sleep(Duration::from_millis(100));
        }
        let _ = std::fs::remove_dir_all(&dir);
    }
}
