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

/// The daemon for `state`: the one already running there, else a new sidecar on `port` (0 = any free port).
pub fn ensure(state: &Path, port: u16, cwd: &Path, launch: &Launch) -> Result<Sidecar, String> {
    if FOREIGN_PORTS.contains(&port) {
        return Err(format!("port {port} belongs to the Kotlin app's daemon; this app never uses it"));
    }
    std::fs::create_dir_all(state).map_err(|e| format!("state dir {}: {e}", state.display()))?;
    if let Some(p) = read_port(state).filter(|p| !FOREIGN_PORTS.contains(p) && healthy(*p)) {
        return Ok(Sidecar { url: format!("ws://127.0.0.1:{p}/ws"), child: None });
    }
    let _ = std::fs::remove_file(port_file(state));
    let bin = binary()?;
    let log = std::fs::File::create(state.join("daemon.log")).map_err(|e| format!("daemon log: {e}"))?;
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
            return Err(format!("{} exited at start ({status}); see {}", bin.display(), state.join("daemon.log").display()));
        }
        if let Some(p) = read_port(state).filter(|p| !FOREIGN_PORTS.contains(p)) {
            return Ok(Sidecar { url: format!("ws://127.0.0.1:{p}/ws"), child: Some(child) });
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
        assert!(ensure(Path::new("."), 8797, Path::new("."), &super::Launch::default()).is_err());
    }
}
