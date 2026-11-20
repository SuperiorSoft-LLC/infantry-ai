// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-DAEMON-2: daemon mode. The app starts `infantry-daemon` as a sidecar process when none is running for its state
//! dir (PLAN.md 1.1: the app never embeds the daemon), then the WebView talks to it over the v1 WebSocket exactly as to
//! any daemon. The sidecar outlives the window, as the daemon does; the next app start finds it by its `daemon.port`
//! file and its `/health` answer, and connects to it instead of starting another.
//!
//! Where the binary is: `INFANTRY_DAEMON_BIN`, else `infantry-daemon.exe` beside the app's own exe (an installed app),
//! else the workspace's release build (a dev checkout).

use std::io::{Read, Write};
use std::net::TcpStream;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

/// How long a fresh sidecar may take to write its port file.
pub const START_TIMEOUT_MS: u64 = 15_000;
/// One `/health` probe.
pub const HEALTH_TIMEOUT_MS: u64 = 1_500;

#[cfg(windows)]
const EXE: &str = "infantry-daemon.exe";
#[cfg(not(windows))]
const EXE: &str = "infantry-daemon";

/// Variables the sidecar must not inherit: the app's own endpoint and token (a daemon on loopback needs none, and the
/// launching shell's token belongs to another daemon), the app's UI knobs, and a launching claude's session guard.
const ENV_DROP: [&str; 6] = ["INFANTRY_URL", "INFANTRY_PORT", "INFANTRY_HOST", "INFANTRY_TOKEN", "INFANTRY_TOKEN_FILE", "INFANTRY_STATE_DIR"];
const ENV_DROP_PREFIX: [&str; 4] = ["AGENTHQ_", "INFANTRY_UI_", "INFANTRY_PERF_", "CLAUDE"];

/// The sidecar's port when nothing pins it (`INFANTRY_UI_DAEMON_PORT`): fixed, so the app and `start.cmd` agree.
pub const DEFAULT_PORT: u16 = 18990;
/// Ports this app never connects to or starts on: the Kotlin app's daemon (8797) and the Kotlin dev daemon (8787).
pub const FOREIGN_PORTS: [u16; 2] = [8797, 8787];
/// The sidecar's state dir under `%LOCALAPPDATA%` when nothing pins it (the Rust app's own, never the Kotlin app's).
pub const STATE_SUBDIR: [&str; 2] = ["SuperiorSoft", "InfantryAI-Rust"];

/// `%LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust` (None where there is no LOCALAPPDATA).
pub fn default_state_dir() -> Option<PathBuf> {
    let l = std::env::var_os("LOCALAPPDATA").filter(|v| !v.is_empty())?;
    Some(STATE_SUBDIR.iter().fold(PathBuf::from(l), |p, s| p.join(s)))
}

/// Whether a URL or port names a daemon this app must leave alone.
pub fn foreign(url_or_port: &str) -> bool {
    let tail = url_or_port.trim().trim_end_matches('/').trim_end_matches("/ws");
    let port = tail.rsplit(':').next().and_then(|p| p.split('/').next()).and_then(|p| p.parse::<u16>().ok());
    port.is_some_and(|p| FOREIGN_PORTS.contains(&p))
}

/// A daemon this app found or started.
pub struct Sidecar {
    pub url: String,
    /// The process, when this app started it (None = it was already running).
    pub child: Option<Child>,
    /// Set when the daemon found running is older than the daemon binary on disk (it may refuse newer frames).
    pub note: Option<String>,
}

/// Why a running daemon is stale: its start time (`health/status.json` `startedAt`, ms) is before the binary's
/// modification time. None when it is current or either time is unknown.
pub fn stale_note(state: &Path, bin: Option<&Path>) -> Option<String> {
    let st: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(state.join("health").join("status.json")).ok()?).ok()?;
    let started = st["startedAt"].as_i64()?;
    let built = bin?.metadata().ok()?.modified().ok()?.duration_since(std::time::UNIX_EPOCH).ok()?.as_millis() as i64;
    (built > started + 1_000).then(|| {
        "The running daemon is older than the daemon binary on disk, so it may refuse newer requests (\"this daemon does not serve that frame yet\"). Restart the daemon (Settings > Daemon) to load the new build.".to_string()
    })
}

/// The build id of a daemon binary (`--build-id`), None when it cannot be asked.
pub fn bundled_build_id(bin: &Path) -> Option<String> {
    let mut c = Command::new(bin);
    c.arg("--build-id");
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(0x0800_0000);
    }
    let out = c.output().ok().filter(|o| o.status.success())?;
    Some(String::from_utf8_lossy(&out.stdout).trim().to_string()).filter(|s| !s.is_empty())
}

/// The `buildId` a daemon on `port` reports in `/health` (None = an older daemon that reports none, or no answer).
pub fn running_build_id(port: u16) -> Option<String> {
    let mut s = TcpStream::connect_timeout(&([127, 0, 0, 1], port).into(), Duration::from_millis(HEALTH_TIMEOUT_MS)).ok()?;
    let _ = s.set_read_timeout(Some(Duration::from_millis(HEALTH_TIMEOUT_MS)));
    s.write_all(format!("GET /health HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\n\r\n").as_bytes()).ok()?;
    let mut body = String::new();
    let _ = s.read_to_string(&mut body);
    let json: serde_json::Value = serde_json::from_str(body.split("\r\n\r\n").nth(1)?.trim()).ok()?;
    json["buildId"].as_str().map(str::to_string)
}

/// `GET /health` on loopback answered `ok`.
pub fn healthy(port: u16) -> bool {
    let Ok(mut s) = TcpStream::connect_timeout(&([127, 0, 0, 1], port).into(), Duration::from_millis(HEALTH_TIMEOUT_MS)) else {
        return false;
    };
    let _ = s.set_read_timeout(Some(Duration::from_millis(HEALTH_TIMEOUT_MS)));
    let req = format!("GET /health HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\n\r\n");
    if s.write_all(req.as_bytes()).is_err() {
        return false;
    }
    let mut body = String::new();
    let _ = s.read_to_string(&mut body);
    body.starts_with("HTTP/1.1 200") && body.contains("\"ok\":true")
}

fn port_file(state: &Path) -> PathBuf {
    state.join("daemon.port")
}

fn read_port(state: &Path) -> Option<u16> {
    std::fs::read_to_string(port_file(state)).ok()?.trim().parse().ok()
}

/// The daemon binary, or why there is none.
pub fn binary() -> Result<PathBuf, String> {
    if let Some(b) = std::env::var_os("INFANTRY_DAEMON_BIN").map(PathBuf::from) {
        return if b.is_file() { Ok(b) } else { Err(format!("INFANTRY_DAEMON_BIN {} is not a file", b.display())) };
    }
    let mut tried = Vec::new();
    if let Some(dir) = std::env::current_exe().ok().and_then(|e| e.parent().map(Path::to_path_buf)) {
        tried.push(dir.join(EXE));
    }
    tried.push(Path::new(env!("CARGO_MANIFEST_DIR")).join("../../rust/target/release").join(EXE));
    tried.iter().find(|p| p.is_file()).cloned().ok_or_else(|| {
        format!("no {EXE} found (tried {}); set INFANTRY_DAEMON_BIN", tried.iter().map(|p| p.display().to_string()).collect::<Vec<_>>().join(", "))
    })
}

/// How the sidecar listens (RUST-UPD-1). Loopback with no token by default; `remote` (Settings > Daemon > Allow phone
/// connections) binds every interface and requires the bearer token in `token_file`, as the daemon insists.
#[derive(Clone, Default)]
pub struct Launch {
    pub remote: bool,
    pub token_file: Option<PathBuf>,
}

impl Launch {
    fn host(&self) -> &'static str {
        if self.remote { "0.0.0.0" } else { "127.0.0.1" }
    }
}

/// The phone-access token file in `state`, created (64 hex characters from the OS RNG) when missing. Never printed.
pub fn remote_token_file(state: &Path) -> Result<PathBuf, String> {
    let f = state.join("remote-access.token");
    let ok = std::fs::read_to_string(&f).map(|t| t.trim().len() >= 32).unwrap_or(false);
    if !ok {
        std::fs::create_dir_all(state).map_err(|e| e.to_string())?;
        let t = format!("{}{}", uuid::Uuid::new_v4().simple(), uuid::Uuid::new_v4().simple());
        std::fs::write(&f, t).map_err(|e| format!("token file {}: {e}", f.display()))?;
    }
    Ok(f)
}

/// The pid of the daemon serving `state`: its health record (`health/status.json`), only while that record names the
/// port in `daemon.port` and the port answers `/health` (a stale record never names a reused pid).
pub fn running_pid(state: &Path) -> Option<u32> {
    let port = read_port(state)?;
    let st: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(state.join("health").join("status.json")).ok()?).ok()?;
    pid_for(&st, port).filter(|_| healthy(port))
}

fn pid_for(status: &serde_json::Value, port: u16) -> Option<u32> {
    let running = status["running"].as_bool().unwrap_or(true);
    if !running || status["port"].as_u64() != Some(u64::from(port)) {
        return None;
    }
    status["pid"].as_u64().and_then(|p| u32::try_from(p).ok())
}

/// How long a stopped daemon may take to stop answering.
pub const STOP_TIMEOUT_MS: u64 = 10_000;

/// Stops the daemon serving `state` by its PID (never by image name) and waits until its port is quiet. Answers the
/// pid it stopped (None = none was running).
pub fn stop(state: &Path) -> Result<Option<u32>, String> {
    let Some(port) = read_port(state) else { return Ok(None) };
    let Some(pid) = running_pid(state) else { return Ok(None) };
    if pid == std::process::id() {
        return Err("refusing to stop this app's own process".into());
    }
    kill_pid(pid)?;
    let start = Instant::now();
    while healthy(port) {
        if start.elapsed() > Duration::from_millis(STOP_TIMEOUT_MS) {
            return Err(format!("daemon pid {pid} still answers on port {port} after {STOP_TIMEOUT_MS} ms"));
        }
        std::thread::sleep(Duration::from_millis(100));
    }
    let _ = std::fs::remove_file(port_file(state));
    Ok(Some(pid))
}

#[cfg(windows)]
fn kill_pid(pid: u32) -> Result<(), String> {
    use std::os::windows::process::CommandExt;
    const CREATE_NO_WINDOW: u32 = 0x0800_0000;
    let out = Command::new("taskkill")
        .args(["/PID", &pid.to_string(), "/T", "/F"])
        .creation_flags(CREATE_NO_WINDOW)
        .output()
        .map_err(|e| format!("taskkill: {e}"))?;
    if out.status.success() { Ok(()) } else { Err(format!("taskkill /PID {pid} failed: {}", String::from_utf8_lossy(&out.stderr).trim())) }
}

#[cfg(not(windows))]
fn kill_pid(pid: u32) -> Result<(), String> {
    let st = Command::new("kill").arg(pid.to_string()).status().map_err(|e| format!("kill: {e}"))?;
    if st.success() { Ok(()) } else { Err(format!("kill {pid} failed")) }
}

/// The daemon's log folder under the state dir.
pub const LOGS: &str = "logs";

/// Opens a fresh `<state>/logs/daemon.log`, first shifting the older ones up (`daemon.log` -> `daemon.1.log` -> ...)
/// and dropping those past `keep`. The logs of the earlier layout (`<state>/daemon.log`, `daemon.prev.log`) are moved
/// into the rotation, so no start ever truncates a log.
pub fn rotate_log(state: &Path, keep: usize) -> Result<std::fs::File, String> {
    let keep = keep.max(1);
    let dir = state.join(LOGS);
    std::fs::create_dir_all(&dir).map_err(|e| format!("logs dir {}: {e}", dir.display()))?;
    let at = |n: usize| dir.join(if n == 0 { "daemon.log".to_string() } else { format!("daemon.{n}.log") });
    for old in ["daemon.prev.log", "daemon.log"] {
        let legacy = state.join(old);
        if legacy.is_file() {
            // Older than anything in logs/: it takes the next free generation, or is dropped when none is free.
            let free = (0..=keep).find(|n| !at(*n).exists());
            if let Some(n) = free.filter(|n| *n > 0 || old == "daemon.log") {
                let _ = std::fs::rename(&legacy, at(n));
            } else {
                let _ = std::fs::remove_file(&legacy);
            }
        }
    }
    let _ = std::fs::remove_file(at(keep));
    for n in (0..keep).rev() {
        if at(n).is_file() {
            let _ = std::fs::rename(at(n), at(n + 1));
        }
    }
    std::fs::File::create(at(0)).map_err(|e| format!("daemon log: {e}"))
}

/// The daemon for `state`: the one already running there, else a new sidecar on `port` (0 = any free port).
pub fn ensure(state: &Path, port: u16, cwd: &Path, launch: &Launch, log_keep: usize) -> Result<Sidecar, String> {
    if FOREIGN_PORTS.contains(&port) {
        return Err(format!("port {port} belongs to the Kotlin app's daemon; this app never uses it"));
    }
    std::fs::create_dir_all(state).map_err(|e| format!("state dir {}: {e}", state.display()))?;
    if let Some(p) = read_port(state).filter(|p| !FOREIGN_PORTS.contains(p) && healthy(*p)) {
        let bin = binary().ok();
        let wanted = bin.as_deref().and_then(bundled_build_id);
        // Same build (or the bundled build cannot be told): reuse it. A different or unreported build is replaced,
        // but only a daemon our own health record names (this state dir); anything else is never killed.
        if wanted.is_none() || running_build_id(p) == wanted {
            let note = stale_note(state, bin.as_deref()).filter(|_| wanted.is_none());
            return Ok(Sidecar { url: format!("ws://127.0.0.1:{p}/ws"), child: None, note });
        }
        if running_pid(state).is_none() {
            return Err(format!("port {p} is held by a daemon that is not this app's (no health record in {} names it); not stopping it", state.display()));
        }
        stop(state)?;
    }
    let _ = std::fs::remove_file(port_file(state));
    let bin = binary()?;
    // RUST-BUG 2617b342: never truncate the log. `<state>/logs/daemon.log` is rotated to daemon.1.log ... daemon.<keep>.log.
    let log = rotate_log(state, log_keep)?;
    let mut cmd = Command::new(&bin);
    cmd.args(["--host", launch.host(), "--port", &port.to_string(), "--state-dir"])
        .arg(state)
        .current_dir(cwd)
        .stdin(Stdio::null())
        .stdout(log.try_clone().map_err(|e| e.to_string())?)
        .stderr(log);
    for (k, _) in std::env::vars_os() {
        let k = k.to_string_lossy().into_owned();
        if ENV_DROP.contains(&k.as_str()) || ENV_DROP_PREFIX.iter().any(|p| k.starts_with(p)) {
            cmd.env_remove(&k);
        }
    }
    if launch.remote {
        let f = launch.token_file.as_ref().ok_or("phone access needs a token file")?;
        cmd.env("INFANTRY_TOKEN_FILE", f);
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        cmd.creation_flags(CREATE_NO_WINDOW);
    }
    let mut child = cmd.spawn().map_err(|e| format!("could not start {}: {e}", bin.display()))?;
    let start = Instant::now();
    while start.elapsed() < Duration::from_millis(START_TIMEOUT_MS) {
        if let Ok(Some(status)) = child.try_wait() {
            return Err(format!("{} exited at start ({status}); see {}", bin.display(), state.join(LOGS).join("daemon.log").display()));
        }
        if let Some(p) = read_port(state).filter(|p| !FOREIGN_PORTS.contains(p)) {
            return Ok(Sidecar { url: format!("ws://127.0.0.1:{p}/ws"), child: Some(child), note: None });
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    let _ = child.kill();
    Err(format!("{} wrote no port file in {START_TIMEOUT_MS} ms", bin.display()))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn logs_rotate_instead_of_truncating() {
        let dir = std::env::temp_dir().join(format!("infantry-rot-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(dir.join("daemon.log"), "legacy").unwrap();
        for i in 1..=4 {
            let mut f = rotate_log(&dir, 3).unwrap();
            writeln!(f, "run {i}").unwrap();
        }
        let l = |n: &str| std::fs::read_to_string(dir.join("logs").join(n)).unwrap_or_default();
        assert_eq!((l("daemon.log").trim(), l("daemon.1.log").trim(), l("daemon.2.log").trim(), l("daemon.3.log").trim()), ("run 4", "run 3", "run 2", "run 1"));
        assert!(!dir.join("logs/daemon.4.log").exists() && !dir.join("daemon.log").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_daemon_older_than_the_binary_is_called_stale() {
        let dir = std::env::temp_dir().join(format!("infantry-stale-{}", std::process::id()));
        std::fs::create_dir_all(dir.join("health")).unwrap();
        let bin = dir.join("bin.exe");
        std::fs::write(&bin, b"x").unwrap();
        std::fs::write(dir.join("health").join("status.json"), r#"{"startedAt": 1000}"#).unwrap();
        assert!(stale_note(&dir, Some(&bin)).is_some_and(|n| n.contains("older")));
        std::fs::write(dir.join("health").join("status.json"), r#"{"startedAt": 99999999999999}"#).unwrap();
        assert!(stale_note(&dir, Some(&bin)).is_none());
        assert!(stale_note(&dir, None).is_none());
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Two daemon builds on a free test port: an older one is replaced by the bundled one; a foreign one is refused.
    /// Needs the release daemon build (returns early when it is absent).
    #[test]
    fn a_stale_daemon_is_replaced_and_a_foreign_one_refused() {
        let Ok(bin) = binary() else { return };
        let Some(id) = bundled_build_id(&bin) else { return };
        let dir = std::env::temp_dir().join(format!("infantry-swap-{}", uuid::Uuid::new_v4().simple()));
        let port = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
        // "Older" daemon: a copy of the binary with one byte appended has a different build id.
        let old = dir.join("old").join(EXE);
        std::fs::create_dir_all(old.parent().unwrap()).unwrap();
        std::fs::copy(&bin, &old).unwrap();
        std::fs::OpenOptions::new().append(true).open(&old).unwrap().write_all(b"x").unwrap();
        let state = dir.join("state");
        std::fs::create_dir_all(&state).unwrap();
        let mut old_child = Command::new(&old).args(["--port", &port.to_string(), "--state-dir"]).arg(&state).stdout(Stdio::null()).stderr(Stdio::null()).spawn().unwrap();
        let t = Instant::now();
        while !healthy(port) && t.elapsed() < Duration::from_secs(15) {
            std::thread::sleep(Duration::from_millis(100));
        }
        assert_ne!(running_build_id(port).as_deref(), Some(id.as_str()));
        std::fs::write(port_file(&state), port.to_string()).unwrap();
        // Foreign: the health record names another port, so the daemon is not ours and must survive.
        let status = state.join("health").join("status.json");
        let real = std::fs::read_to_string(&status).unwrap();
        let mut v: serde_json::Value = serde_json::from_str(&real).unwrap();
        v["port"] = serde_json::json!(1);
        std::fs::write(&status, v.to_string()).unwrap();
        assert!(ensure(&state, port, &dir, &Launch::default(), 5).is_err());
        assert!(healthy(port), "a foreign daemon must not be killed");
        std::fs::write(&status, real).unwrap();
        let sc = ensure(&state, port, &dir, &Launch::default(), 5).unwrap();
        assert_eq!(running_build_id(port).as_deref(), Some(id.as_str()), "the bundled daemon replaced the old one");
        let _ = old_child.wait();
        let _ = stop(&state);
        drop(sc);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_health_record_names_a_pid_only_for_the_live_port() {
        let st = serde_json::json!({"pid": 4242, "port": 18990, "running": true});
        assert_eq!(super::pid_for(&st, 18990), Some(4242));
        assert_eq!(super::pid_for(&st, 18991), None);
        assert_eq!(super::pid_for(&serde_json::json!({"pid": 4242, "port": 18990, "running": false}), 18990), None);
    }

    #[test]
    fn the_phone_token_is_long_and_kept() {
        let dir = std::env::temp_dir().join(format!("infantry-upd-{}", uuid::Uuid::new_v4().simple()));
        let f = super::remote_token_file(&dir).unwrap();
        let t = std::fs::read_to_string(&f).unwrap();
        assert!(t.len() >= 64 && t.chars().all(|c| c.is_ascii_hexdigit()));
        assert_eq!(std::fs::read_to_string(super::remote_token_file(&dir).unwrap()).unwrap(), t);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_dead_port_is_not_healthy() {
        // Bind and drop: the port is free (nothing answers there).
        let p = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
        assert!(!healthy(p));
    }

    #[test]
    fn the_kotlin_ports_are_foreign() {
        assert!(foreign("ws://127.0.0.1:8797/ws"));
        assert!(foreign("http://localhost:8787"));
        assert!(!foreign("ws://127.0.0.1:18990/ws"));
        assert!(ensure(Path::new("."), 8797, Path::new("."), &super::Launch::default(), 5).is_err());
    }
}
