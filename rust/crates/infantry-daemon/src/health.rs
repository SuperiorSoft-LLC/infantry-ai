// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The `health` frame (RUST-WIRE-2), natively: the same report as Kotlin's DAEMON-HEALTH-1 `HealthReport` (daemon row,
//! plugins, MCP bridges per session, hooks, free memory and disk, environment facts with the owner's off-limits ports,
//! the restart log), answered as JSON or, with `"format":"text"`, as the `infantry health` text.
//!
//! The journal is the same two files Kotlin writes (`<state>/health/status.json`, `events.jsonl`), so a migrated
//! state dir keeps its restart history and an offline reader sees one format.

use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::BTreeMap;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU16, Ordering};
use std::sync::{Arc, Mutex};

pub const HEALTH: &str = "health";
pub const DIR: &str = "health";
pub const EVENTS: &str = "events.jsonl";
pub const STATUS: &str = "status.json";
/// `limits.health.maxEvents` default: journal lines kept (the file is cut back to this when it doubles).
pub const MAX_EVENTS: usize = 200;
/// `limits.health.eventTail` default: restart / disconnect lines a report carries.
pub const EVENT_TAIL: usize = 10;
/// The owner's daemon and UI (8787, 8788) and the PM's MCP pair (8797, 8798): off limits to every agent and test.
pub const DEV_OWNER_PORTS: [u16; 4] = [8787, 8788, 8797, 8798];
/// Comma list that replaces the dev owner ports (blank = none).
pub const OWNER_PORTS_ENV: &str = "INFANTRY_OWNER_PORTS";
/// Set on an installed app: it has no dev owner ports.
pub const OWN_URL_ONLY_ENV: &str = "INFANTRY_OWN_URL_ONLY";
pub const TEST_PORT_RANGE: &str = "18900-18999";
const MB: u64 = 1_048_576;
/// Every panic of this daemon, one line each, under `<state>/health/` (RUST-BUG e0c433c9: the launcher rewrites
/// `daemon.log` on each start, so a crash's own output was gone by the time anyone looked).
pub const PANICS: &str = "panics.log";
/// `limits.health.panicLogBytes` default: past this size `panics.log` is moved to `panics.prev.log` and restarted.
pub const PANIC_LOG_MAX_BYTES: u64 = 256 * 1024;
/// Where Windows Error Reporting writes a crashed process's dump by default (`%LOCALAPPDATA%\CrashDumps`).
pub const CRASH_DUMPS_SUBDIR: &str = "CrashDumps";

/// Crash files live in `<state>/health/crashes/`, one per panic: the message, location, thread and a backtrace.
pub const CRASHES_DIR: &str = "crashes";
/// `limits.health.crashFiles` default: crash files kept (the oldest are deleted past this).
pub const CRASH_FILES_KEEP: usize = 20;
/// `INFANTRY_CRASH_FILES_KEEP` overrides [`CRASH_FILES_KEEP`] (the hook runs before settings are loaded).
pub const CRASH_FILES_ENV: &str = "INFANTRY_CRASH_FILES_KEEP";

/// Installs a panic hook that appends the panic (thread, message, location) to `<state>/health/panics.log`, writes the
/// message and a backtrace to a crash file under `<state>/health/crashes/`, then runs the previous hook (stderr). A
/// panic that aborts the process, or a later out-of-memory abort, is thereby on disk, and the health report lists it.
pub fn install_panic_log(state_dir: &Path) {
    let dir = state_dir.join(DIR);
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let thread = std::thread::current().name().unwrap_or("unnamed").to_string();
        let at = now_ms();
        append_panic_line(&dir, &format!("{at} pid {} thread '{thread}' panicked: {info}", std::process::id()));
        let keep = std::env::var(CRASH_FILES_ENV).ok().and_then(|v| v.trim().parse().ok()).unwrap_or(CRASH_FILES_KEEP);
        let trace = std::backtrace::Backtrace::force_capture();
        write_crash_file(&dir, at, std::process::id(), &thread, &info.to_string(), &trace.to_string(), keep);
        previous(info);
    }));
}

/// Writes `crash-<ms>-pid<pid>.txt` and deletes the oldest files past `keep`. Never panics.
pub fn write_crash_file(health_dir: &Path, at: i64, pid: u32, thread: &str, message: &str, backtrace: &str, keep: usize) {
    let dir = health_dir.join(CRASHES_DIR);
    if std::fs::create_dir_all(&dir).is_err() {
        return;
    }
    let body = format!("at {at} (ms since epoch)
pid {pid}
thread {thread}

{message}

backtrace:
{backtrace}
");
    let _ = std::fs::write(dir.join(format!("crash-{at:015}-pid{pid}.txt")), body);
    let mut files = crash_files(health_dir);
    while files.len() > keep.max(1) {
        let _ = std::fs::remove_file(files.remove(0));
    }
}

/// The crash files, oldest first (the name carries a zero-padded timestamp).
pub fn crash_files(health_dir: &Path) -> Vec<PathBuf> {
    let mut v: Vec<PathBuf> = std::fs::read_dir(health_dir.join(CRASHES_DIR))
        .map(|d| d.filter_map(|e| e.ok().map(|e| e.path())).filter(|p| p.file_name().is_some_and(|n| n.to_string_lossy().starts_with("crash-"))).collect())
        .unwrap_or_default();
    v.sort();
    v
}

fn append_panic_line(dir: &Path, line: &str) {
    let _ = std::fs::create_dir_all(dir);
    let path = dir.join(PANICS);
    if std::fs::metadata(&path).map(|m| m.len() > PANIC_LOG_MAX_BYTES).unwrap_or(false) {
        let _ = std::fs::rename(&path, dir.join("panics.prev.log"));
    }
    if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(&path) {
        let _ = writeln!(f, "{}", line.replace(['\r', '\n'], " "));
    }
}

/// The default WER dump folder, if the platform has one.
pub fn default_crash_dump_dir() -> Option<PathBuf> {
    std::env::var_os("LOCALAPPDATA").filter(|v| !v.is_empty()).map(|l| PathBuf::from(l).join(CRASH_DUMPS_SUBDIR))
}

/// What is known about how `pid` died: its last panic line and its crash dump, when they exist.
fn crash_evidence(health_dir: &Path, pid: i64, dumps: Option<&Path>) -> Vec<String> {
    let mut out = Vec::new();
    let tag = format!(" pid {pid} ");
    if let Some(line) = std::fs::read_to_string(health_dir.join(PANICS)).ok().and_then(|t| t.lines().rev().find(|l| l.contains(&tag)).map(str::to_string)) {
        out.push(format!("last panic: {}", line.split_once("panicked: ").map(|(_, m)| m).unwrap_or(&line)));
    }
    let pid_tag = format!("-pid{pid}.txt");
    if let Some(f) = crash_files(health_dir).into_iter().rev().find(|p| p.to_string_lossy().ends_with(&pid_tag)) {
        out.push(format!("crash file: {}", f.display()));
    }
    if let Some(dir) = dumps {
        let exe = crate::own_files::own_binary().and_then(|p| p.file_name().map(|n| n.to_string_lossy().into_owned())).unwrap_or_else(|| "infantry-daemon.exe".into());
        let dump = dir.join(format!("{exe}.{pid}.dmp"));
        if dump.is_file() {
            out.push(format!("crash dump: {}", dump.display()));
        }
    }
    out
}

fn now_ms() -> i64 {
    crate::sessions::now_ms()
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HealthEvent {
    pub at: i64,
    pub kind: String,
    #[serde(default)]
    pub cause: Option<String>,
    #[serde(default)]
    pub detail: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct HealthStatus {
    pub pid: i64,
    pub port: i32,
    pub build: String,
    pub started_at: i64,
    pub last_seen: i64,
    #[serde(default = "yes")]
    pub running: bool,
    #[serde(default)]
    pub last_restart_cause: Option<String>,
    #[serde(default)]
    pub last_restart_at: Option<i64>,
    #[serde(default)]
    pub stop_reason: Option<String>,
}

fn yes() -> bool {
    true
}

/// The restart journal (Kotlin `HealthJournal`).
pub struct HealthJournal {
    dir: PathBuf,
    lock: Mutex<()>,
}

impl HealthJournal {
    pub fn new(state_dir: &Path) -> HealthJournal {
        HealthJournal { dir: state_dir.join(DIR), lock: Mutex::new(()) }
    }

    pub fn status(&self) -> Option<HealthStatus> {
        std::fs::read_to_string(self.dir.join(STATUS)).ok().and_then(|t| serde_json::from_str(&t).ok())
    }

    pub fn tail(&self, n: usize) -> Vec<HealthEvent> {
        let text = std::fs::read_to_string(self.dir.join(EVENTS)).unwrap_or_default();
        let lines: Vec<&str> = text.lines().filter(|l| !l.trim().is_empty()).collect();
        lines[lines.len().saturating_sub(n)..].iter().filter_map(|l| serde_json::from_str(l).ok()).collect()
    }

    /// A daemon start: why the previous one ended (clean stop, crash, or a live record), journaled.
    pub fn begin(&self, pid: i64, port: i32, build: &str) -> HealthStatus {
        self.begin_with_dumps(pid, port, build, default_crash_dump_dir().as_deref())
    }

    /// [`begin`](Self::begin) with the crash dump folder given; a crash restart names the dead pid's panic and dump.
    pub fn begin_with_dumps(&self, pid: i64, port: i32, build: &str, dumps: Option<&Path>) -> HealthStatus {
        let _g = self.lock.lock().unwrap();
        let previous = self.status();
        let mut evidence = Vec::new();
        let cause = previous.as_ref().map(|p| {
            if !p.running {
                p.stop_reason.clone().unwrap_or_else(|| "clean stop".into())
            } else if p.pid != pid && !pid_alive(p.pid) {
                evidence = crash_evidence(&self.dir, p.pid, dumps);
                "crash or kill (no clean stop recorded)".into()
            } else {
                format!("restart over a live record (pid {})", p.pid)
            }
        });
        let t = now_ms();
        let s = HealthStatus {
            pid,
            port,
            build: build.into(),
            started_at: t,
            last_seen: t,
            running: true,
            last_restart_at: previous.as_ref().map(|_| t),
            last_restart_cause: cause.clone(),
            stop_reason: None,
        };
        self.write(&s);
        let mut detail = format!("pid {pid} port {port} build {build}");
        if let Some(p) = previous.as_ref().filter(|_| !evidence.is_empty()) {
            detail.push_str(&format!("; previous pid {}: {}", p.pid, evidence.join("; ")));
        }
        self.append(&HealthEvent { at: t, kind: "daemon_start".into(), cause, detail: Some(detail) });
        s
    }

    pub fn heartbeat(&self) {
        let _g = self.lock.lock().unwrap();
        if let Some(mut s) = self.status().filter(|s| s.running) {
            s.last_seen = now_ms();
            self.write(&s);
        }
    }

    pub fn end(&self, reason: &str) {
        let _g = self.lock.lock().unwrap();
        let t = now_ms();
        if let Some(mut s) = self.status() {
            s.running = false;
            s.last_seen = t;
            s.stop_reason = Some(reason.into());
            self.write(&s);
        }
        self.append(&HealthEvent { at: t, kind: "daemon_stop".into(), cause: Some(reason.into()), detail: None });
    }

    pub fn record(&self, kind: &str, cause: Option<&str>, detail: Option<&str>) {
        let _g = self.lock.lock().unwrap();
        self.append(&HealthEvent { at: now_ms(), kind: kind.into(), cause: cause.map(String::from), detail: detail.map(String::from) });
    }

    fn write(&self, s: &HealthStatus) {
        let _ = std::fs::create_dir_all(&self.dir);
        let tmp = self.dir.join(format!("{STATUS}.tmp"));
        if std::fs::write(&tmp, serde_json::to_string(s).unwrap_or_default()).is_ok() {
            let _ = std::fs::rename(&tmp, self.dir.join(STATUS));
        }
    }

    fn append(&self, e: &HealthEvent) {
        let _ = std::fs::create_dir_all(&self.dir);
        let path = self.dir.join(EVENTS);
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(&path) {
            let _ = writeln!(f, "{}", serde_json::to_string(e).unwrap_or_default());
        }
        let text = std::fs::read_to_string(&path).unwrap_or_default();
        let lines: Vec<&str> = text.lines().collect();
        if lines.len() > MAX_EVENTS * 2 {
            let keep = lines[lines.len() - MAX_EVENTS..].join("\n") + "\n";
            let _ = std::fs::write(&path, keep);
        }
    }
}

#[cfg(windows)]
pub(crate) fn pid_alive(pid: i64) -> bool {
    use windows_sys::Win32::Foundation::{CloseHandle, STILL_ACTIVE};
    use windows_sys::Win32::System::Threading::{GetExitCodeProcess, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION};
    let Ok(pid) = u32::try_from(pid) else { return false };
    unsafe {
        let h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if h.is_null() {
            return false;
        }
        let mut code = 0u32;
        let ok = GetExitCodeProcess(h, &mut code) != 0;
        CloseHandle(h);
        ok && code == STILL_ACTIVE as u32
    }
}

#[cfg(not(windows))]
pub(crate) fn pid_alive(pid: i64) -> bool {
    Path::new(&format!("/proc/{pid}")).exists()
}

/// One session's MCP bridge (`infantry mcp`) as the daemon sees it.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct McpBridgeRow {
    pub session_id: String,
    pub connected: bool,
    pub connected_at: i64,
    pub last_seen: i64,
    pub reconnects: i32,
    /// The last connection ended with a normal close frame (the bridge opens one short connection per tool call and says
    /// "bye"), so the next connection is a new call, not a reconnect.
    #[serde(skip)]
    pub clean_close: bool,
}

/// The cause `closed` is given when the bridge closed its connection itself with a normal close frame.
pub const CLEAN_CLOSE: &str = "client closed";

/// Tracks MCP bridge connections (Kotlin `McpBridgeTracker`): a `plugin_tool_call` carrying a session id is that
/// session's bridge alive on that connection; the connection closing drops it.
#[derive(Default)]
pub struct Bridges {
    rows: Mutex<BTreeMap<String, McpBridgeRow>>,
}

impl Bridges {
    /// `conn` holds the sessions that already called on this connection.
    pub fn seen(&self, session_id: &str, conn: &mut Vec<String>, journal: &HealthJournal) {
        let id = session_id.trim();
        if id.is_empty() {
            return;
        }
        let t = now_ms();
        let mut rows = self.rows.lock().unwrap();
        if conn.iter().any(|c| c == id) {
            if let Some(r) = rows.get_mut(id) {
                r.last_seen = t;
            }
            return;
        }
        conn.push(id.into());
        // A new connection after a clean "bye" is the next tool call; only a drop (or a second live socket) is a reconnect.
        let prev = rows.get(id).map(|r| (r.reconnects, r.clean_close && !r.connected));
        let reconnects = match prev {
            Some((n, true)) => n,
            Some((n, false)) => n + 1,
            None => 0,
        };
        rows.insert(id.into(), McpBridgeRow { session_id: id.into(), connected: true, connected_at: t, last_seen: t, reconnects, clean_close: false });
        drop(rows);
        let reconnect = matches!(prev, Some((_, false)));
        journal.record("mcp_connect", reconnect.then_some("reconnect"), Some(&format!("session {id}")));
    }

    pub fn closed(&self, conn: &[String], cause: Option<&str>, journal: &HealthJournal) {
        let t = now_ms();
        for id in conn {
            let had = {
                let mut rows = self.rows.lock().unwrap();
                rows.get_mut(id).map(|r| {
                    r.connected = false;
                    r.clean_close = cause == Some(CLEAN_CLOSE);
                    r.last_seen = t;
                })
            };
            if had.is_some() {
                journal.record("mcp_disconnect", cause, Some(&format!("session {id}")));
            }
        }
    }

    pub fn rows(&self) -> Vec<McpBridgeRow> {
        self.rows.lock().unwrap().values().cloned().collect()
    }
}

/// The daemon's health handle: journal, bridges and the bound port (set once the listener is up).
pub struct Health {
    pub journal: HealthJournal,
    pub bridges: Bridges,
    pub state_dir: PathBuf,
    port: AtomicU16,
}

impl Health {
    pub fn new(state_dir: &Path) -> Health {
        Health { journal: HealthJournal::new(state_dir), bridges: Bridges::default(), state_dir: state_dir.to_path_buf(), port: AtomicU16::new(0) }
    }

    pub fn started(&self, port: u16, build: &str) {
        self.port.store(port, Ordering::Relaxed);
        self.journal.begin(std::process::id() as i64, port as i32, build);
        let _ = std::fs::write(self.state_dir.join("daemon.pid"), std::process::id().to_string());
        register_daemon(&daemon_registry_dir(), &self.state_dir, port);
    }

    pub fn port(&self) -> Option<u16> {
        Some(self.port.load(Ordering::Relaxed)).filter(|p| *p != 0)
    }
}

/// The machine-wide folder where each running daemon leaves `<pid>.json`, so `infantry health` can list the others and
/// a cleanup can stop a test daemon by pid (never by image name).
pub fn daemon_registry_dir() -> PathBuf {
    if let Some(d) = crate::auth::env("INFANTRY_DAEMON_REGISTRY_DIR").filter(|v| !v.trim().is_empty()) {
        return PathBuf::from(d);
    }
    match crate::auth::env("LOCALAPPDATA").filter(|v| !v.trim().is_empty()) {
        Some(l) => PathBuf::from(l).join("SuperiorSoft").join("InfantryAI-Cache").join("daemons"),
        None => std::env::temp_dir().join("infantry-ai-daemons"),
    }
}

/// Records this process in `dir` (best effort).
pub fn register_daemon(dir: &Path, state_dir: &Path, port: u16) {
    let _ = std::fs::create_dir_all(dir);
    let exe = crate::own_files::own_binary().map(|p| p.to_string_lossy().into_owned()).unwrap_or_default();
    let v = serde_json::json!({"pid": std::process::id(), "port": port, "exe": exe, "cwd": std::env::current_dir().map(|p| p.to_string_lossy().into_owned()).unwrap_or_default(), "stateDir": state_dir.to_string_lossy(), "startedAt": now_ms()});
    let _ = std::fs::write(dir.join(format!("{}.json", std::process::id())), v.to_string());
}

/// The other live daemons recorded in `dir` (this process excluded); a record whose pid is dead is deleted.
pub fn other_daemons(dir: &Path, me: u32) -> Vec<Value> {
    let mut out = Vec::new();
    let Ok(rd) = std::fs::read_dir(dir) else { return out };
    for e in rd.flatten() {
        let Some(v) = std::fs::read_to_string(e.path()).ok().and_then(|t| serde_json::from_str::<Value>(&t).ok()) else { continue };
        let pid = v["pid"].as_i64().unwrap_or(0);
        if pid == me as i64 {
            continue;
        }
        if pid > 0 && pid_alive(pid) {
            out.push(v);
        } else {
            let _ = std::fs::remove_file(e.path());
        }
    }
    out.sort_by_key(|v| v["port"].as_i64().unwrap_or(0));
    out
}

/// Stops every other daemon recorded in `dir` whose state dir or working dir lies under `root` (a released ticket
/// worktree), by pid from its record; never this process and never an owner port. Returns the pids stopped.
pub fn stop_daemons_under(dir: &Path, root: &Path, me: u32, owner_ports: &[u16]) -> Vec<u32> {
    let norm = |p: &str| p.replace('\\', "/").trim_end_matches('/').to_lowercase();
    let r = norm(&root.to_string_lossy());
    let under = |p: &str| !p.is_empty() && { let n = norm(p); n == r || n.starts_with(&format!("{r}/")) };
    let mut stopped = Vec::new();
    for v in other_daemons(dir, me) {
        let port = v["port"].as_u64().unwrap_or(0) as u16;
        let pid = v["pid"].as_u64().unwrap_or(0) as u32;
        if pid == 0 || owner_ports.contains(&port) || !(under(v["stateDir"].as_str().unwrap_or("")) || under(v["cwd"].as_str().unwrap_or(""))) {
            continue;
        }
        if infantry_proc::kill_pid(pid) {
            let _ = std::fs::remove_file(dir.join(format!("{pid}.json")));
            stopped.push(pid);
        }
    }
    stopped
}

/// What the report needs from the composed daemon, as functions (core names no plugin).
/// Setting: free disk (MB) under which `infantry health` warns.
pub const MIN_DISK_SETTING: &str = "health.minDiskMb";
pub const DEFAULT_MIN_DISK_MB: u64 = 20_480;

/// The disk warning line, or None when the disk is fine (or unknown).
pub fn disk_warning(free_mb: Option<u64>, min_mb: u64) -> Option<String> {
    free_mb.filter(|f| *f < min_mb).map(|f| format!("WARNING: low disk: {f} MB free, below {min_mb} MB (setting {MIN_DISK_SETTING}); delete build output (target folders)"))
}

pub struct HealthSources {
    pub build: String,
    pub plugins: Box<dyn Fn() -> BTreeMap<String, String> + Send + Sync>,
    pub hooks: Box<dyn Fn() -> Option<usize> + Send + Sync>,
    pub harnesses: Box<dyn Fn() -> Vec<String> + Send + Sync>,
    /// The `health.minDiskMb` setting (None = the default, [`DEFAULT_MIN_DISK_MB`]).
    pub min_disk_mb: Box<dyn Fn() -> Option<u64> + Send + Sync>,
    /// (project path, worktree path, branch) of the session, else of the project (None = none applies).
    pub paths: Box<dyn Fn(Option<&str>, Option<&str>) -> (Option<String>, Option<String>, Option<String>) + Send + Sync>,
}

pub fn owner_ports(get: impl Fn(&str) -> Option<String>) -> Vec<u16> {
    if let Some(v) = std::env::var(OWNER_PORTS_ENV).ok() {
        return v.split(',').filter_map(|p| p.trim().parse().ok()).collect();
    }
    if get(OWN_URL_ONLY_ENV).is_some() {
        return Vec::new();
    }
    DEV_OWNER_PORTS.to_vec()
}

pub fn shells(os: &str) -> String {
    if os.to_lowercase().starts_with("windows") {
        "PowerShell (pwsh 7: && and || work; no head/tail/grep/rm -rf) and Git Bash (POSIX; /dev/null; forward slashes); each tool call is one shell, state does not persist".into()
    } else {
        "bash/zsh (POSIX)".into()
    }
}

/// The branch checked out in `dir` (`.git/HEAD`, also a worktree's `.git` file); None when detached or no repo.
pub fn current_branch(dir: &Path) -> Option<String> {
    let mut git = dir.join(".git");
    if git.is_file() {
        let t = std::fs::read_to_string(&git).ok()?;
        let g = PathBuf::from(t.trim().trim_start_matches("gitdir:").trim());
        git = if g.is_absolute() { g } else { dir.join(g) };
    }
    let head = std::fs::read_to_string(git.join("HEAD")).ok()?;
    let b = head.trim().trim_start_matches("ref: refs/heads/").to_string();
    (!b.is_empty() && !(b.len() == 40 && b.chars().all(|c| c.is_ascii_hexdigit()))).then_some(b)
}

/// Where `infantry` resolves on PATH (read-only; never writes a PATH).
pub fn cli_path_line() -> Option<String> {
    let path = std::env::var_os("PATH")?;
    let names: &[&str] = if cfg!(windows) { &["infantry.exe", "infantry.cmd", "infantry.bat"] } else { &["infantry"] };
    for d in std::env::split_paths(&path) {
        for n in names {
            if d.join(n).is_file() {
                return Some(format!("on PATH ({})", d.join(n).display()));
            }
        }
    }
    Some("not on PATH (`infantry install-cli` adds it)".into())
}

fn os_name() -> String {
    let os = match std::env::consts::OS {
        "windows" => "Windows",
        "macos" => "Mac OS X",
        "linux" => "Linux",
        o => o,
    };
    match os_version() {
        Some(v) => format!("{os} {v}"),
        None => os.into(),
    }
}

#[cfg(windows)]
fn os_version() -> Option<String> {
    // `ver` prints "Microsoft Windows [Version 10.0.26100.1234]"; no window (CREATE_NO_WINDOW).
    let out = infantry_proc::command("cmd").args(["/C", "ver"]).output().ok()?;
    let t = String::from_utf8_lossy(&out.stdout);
    let v = t.split("Version").nth(1)?.trim().trim_end_matches(']').trim().to_string();
    (!v.is_empty()).then_some(v)
}

#[cfg(not(windows))]
fn os_version() -> Option<String> {
    std::fs::read_to_string("/proc/sys/kernel/osrelease").ok().map(|s| s.trim().to_string())
}

#[cfg(windows)]
fn free_memory_mb() -> Option<u64> {
    use windows_sys::Win32::System::SystemInformation::{GlobalMemoryStatusEx, MEMORYSTATUSEX};
    let mut m: MEMORYSTATUSEX = unsafe { std::mem::zeroed() };
    m.dwLength = std::mem::size_of::<MEMORYSTATUSEX>() as u32;
    (unsafe { GlobalMemoryStatusEx(&mut m) } != 0).then(|| m.ullAvailPhys / MB)
}

#[cfg(not(windows))]
fn free_memory_mb() -> Option<u64> {
    let t = std::fs::read_to_string("/proc/meminfo").ok()?;
    let l = t.lines().find(|l| l.starts_with("MemAvailable:"))?;
    l.split_whitespace().nth(1)?.parse::<u64>().ok().map(|kb| kb / 1024)
}

#[cfg(windows)]
pub(crate) fn free_disk_mb(dir: &Path) -> Option<u64> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::GetDiskFreeSpaceExW;
    let w: Vec<u16> = dir.as_os_str().encode_wide().chain(std::iter::once(0)).collect();
    let mut free = 0u64;
    (unsafe { GetDiskFreeSpaceExW(w.as_ptr(), &mut free, std::ptr::null_mut(), std::ptr::null_mut()) } != 0).then(|| free / MB)
}

#[cfg(not(windows))]
pub(crate) fn free_disk_mb(_dir: &Path) -> Option<u64> {
    None
}

/// Worker notices (failed, killed, ended without a done comment) most recently sent to a starter, newest last.
static NOTICES: std::sync::Mutex<std::collections::VecDeque<Value>> = std::sync::Mutex::new(std::collections::VecDeque::new());
const NOTICE_KEEP: usize = 30;

/// Logs one worker notice: when it went out, to whom, how it arrived (`injected` into a running turn, `turn`, `queued`
/// behind it) and how long the hand-off took.
pub fn record_notice(subject: &str, ticket_id: &str, to: &str, how: &str, lag_ms: i64, line: &str) {
    let mut n = NOTICES.lock().unwrap();
    if n.len() >= NOTICE_KEEP {
        n.pop_front();
    }
    n.push_back(serde_json::json!({"at": now_ms(), "subject": subject, "ticketId": ticket_id, "to": to, "how": how, "lagMs": lag_ms, "line": line.chars().take(160).collect::<String>()}));
}

/// The report (Kotlin `HealthReport`, the same keys).
pub fn report(h: &Health, src: &HealthSources, session_id: Option<&str>, project_id: Option<&str>) -> Value {
    let t = now_ms();
    let st = h.journal.status();
    let (project, worktree, branch) = (src.paths)(session_id, project_id);
    let mut plugins: Vec<Value> = (src.plugins)()
        .into_iter()
        .map(|(id, s)| serde_json::json!({"id": id, "status": if s == "loaded" || s == "started" { "loaded".to_string() } else { s }}))
        .collect();
    plugins.sort_by(|a, b| a["id"].as_str().cmp(&b["id"].as_str()));
    let os = os_name();
    serde_json::json!({
        "at": t,
        "daemon": {
            "up": true, "pid": std::process::id(), "port": h.port(), "build": src.build,
            "startedAt": st.as_ref().map(|s| s.started_at), "uptimeMs": st.as_ref().map(|s| t - s.started_at), "lastSeen": t,
            "lastRestartAt": st.as_ref().and_then(|s| s.last_restart_at), "lastRestartCause": st.as_ref().and_then(|s| s.last_restart_cause.clone()),
            "source": "live",
        },
        "plugins": plugins,
        "pluginRows": {},
        "mcp": h.bridges.rows(),
        "mcpServers": [],
        "hooks": (src.hooks)(),
        "hookFailures": null,
        "freeMemoryMb": free_memory_mb(),
        "freeDiskMb": free_disk_mb(&h.state_dir),
        "diskWarning": disk_warning(free_disk_mb(&h.state_dir), (src.min_disk_mb)().unwrap_or(DEFAULT_MIN_DISK_MB)),
        "environment": {
            "os": os, "arch": std::env::consts::ARCH, "shells": shells(&os),
            "projectPath": project, "worktreePath": worktree, "gitBranch": branch,
            "jdk": "none (Rust daemon)", "buildTools": {},
            "ownerPorts": owner_ports(crate::auth::env), "testPortRange": TEST_PORT_RANGE,
            "harnesses": (src.harnesses)(), "stateDir": h.state_dir.to_string_lossy(),
            "cliPath": cli_path_line(),
        },
        "otherDaemons": other_daemons(&daemon_registry_dir(), std::process::id()),
        "events": h.journal.tail(EVENT_TAIL),
        "notices": NOTICES.lock().unwrap().iter().cloned().collect::<Vec<_>>(),
        "crashes": crash_files(&h.state_dir.join(DIR)).iter().rev().take(EVENT_TAIL).map(|p| p.to_string_lossy().into_owned()).collect::<Vec<_>>(),
    })
}

pub fn age(ms: i64) -> String {
    let s = (ms / 1000).max(0);
    match s {
        s if s < 60 => format!("{s}s"),
        s if s < 3600 => format!("{}m", s / 60),
        s if s < 86_400 => format!("{}h {}m", s / 3600, s % 3600 / 60),
        s => format!("{}d", s / 86_400),
    }
}

/// The `infantry health` text (Kotlin `HealthText.render`).
pub fn render(r: &Value) -> String {
    let mut o = String::new();
    let mut line = |s: String| {
        o.push_str(&s);
        o.push('\n');
    };
    let at = r["at"].as_i64().unwrap_or(0);
    let d = &r["daemon"];
    let opt = |v: &Value| if v.is_null() { "?".to_string() } else { v.as_str().map(String::from).unwrap_or_else(|| v.to_string()) };
    line(format!("daemon: UP ({})", d["source"].as_str().unwrap_or("live")));
    line(format!("  pid {}, port {}, build {}", opt(&d["pid"]), opt(&d["port"]), opt(&d["build"])));
    if let Some(u) = d["uptimeMs"].as_i64() {
        line(format!("  uptime {}", age(u)));
    }
    if let Some(c) = d["lastRestartCause"].as_str() {
        line(format!("  last restart: {c}{}", d["lastRestartAt"].as_i64().map(|t| format!(" ({} ago)", age(at - t))).unwrap_or_default()));
    }
    for o in r["otherDaemons"].as_array().cloned().unwrap_or_default() {
        line(format!("other daemon: pid {}, port {}, {} (state {})", opt(&o["pid"]), opt(&o["port"]), opt(&o["exe"]), opt(&o["stateDir"])));
    }
    let plugins = r["plugins"].as_array().cloned().unwrap_or_default();
    let bad: Vec<&Value> = plugins.iter().filter(|p| p["status"] != "loaded").collect();
    line(format!("plugins: {} loaded, {} not", plugins.len() - bad.len(), bad.len()));
    for p in bad {
        line(format!("  {}: {}", p["id"].as_str().unwrap_or("?"), p["status"].as_str().unwrap_or("?")));
    }
    let mcp = r["mcp"].as_array().cloned().unwrap_or_default();
    let up = mcp.iter().filter(|m| m["connected"] == true).count();
    line(format!("mcp bridges: {up} connected, {} dropped", mcp.len() - up));
    for m in &mcp {
        line(format!(
            "  {}: {}, last seen {} ago, {} reconnect(s)",
            m["sessionId"].as_str().unwrap_or("?"),
            if m["connected"] == true { "connected" } else { "DROPPED" },
            age(at - m["lastSeen"].as_i64().unwrap_or(at)),
            m["reconnects"].as_i64().unwrap_or(0)
        ));
    }
    if let Some(h) = r["hooks"].as_u64() {
        line(format!("hooks: {h}"));
    }
    if !r["freeMemoryMb"].is_null() || !r["freeDiskMb"].is_null() {
        line(format!("free: memory {} MB, disk {} MB", opt(&r["freeMemoryMb"]), opt(&r["freeDiskMb"])));
    }
    if let Some(w) = r["diskWarning"].as_str() {
        line(w.to_string());
    }
    let e = &r["environment"];
    let s = |k: &str| e[k].as_str().map(String::from);
    line("environment:".into());
    line(format!("  os {} {}; jdk {}", s("os").unwrap_or_default(), s("arch").unwrap_or_default(), s("jdk").unwrap_or_default()));
    line(format!("  shells: {}", s("shells").unwrap_or_default()));
    line(if s("projectPath").is_none() && s("worktreePath").is_none() {
        "  project: none applies (no session and no project for this call)".into()
    } else {
        format!(
            "  project {}; worktree {}; branch {}",
            s("projectPath").unwrap_or("-".into()),
            s("worktreePath").unwrap_or("-".into()),
            s("gitBranch").unwrap_or("-".into())
        )
    });
    let ports: Vec<String> = e["ownerPorts"].as_array().cloned().unwrap_or_default().iter().map(|p| p.to_string()).collect();
    if !ports.is_empty() {
        line(format!("  OWNER ports (off limits): {}; free for test daemons: {}", ports.join(", "), s("testPortRange").unwrap_or_default()));
    }
    let hs: Vec<String> = e["harnesses"].as_array().cloned().unwrap_or_default().iter().filter_map(|h| h.as_str().map(String::from)).collect();
    line(format!("  harnesses: {}", if hs.is_empty() { "none".into() } else { hs.join(", ") }));
    line(format!("  state dir: {}", s("stateDir").unwrap_or_default()));
    if let Some(c) = s("cliPath") {
        line(format!("  CLI: {c}"));
    }
    let events = r["events"].as_array().cloned().unwrap_or_default();
    if !events.is_empty() {
        line("recent restarts / disconnects:".into());
        for ev in events {
            line(format!(
                "  {} {}{}{}",
                ev["at"].as_i64().unwrap_or(0),
                ev["kind"].as_str().unwrap_or("?"),
                ev["cause"].as_str().map(|c| format!(" ({c})")).unwrap_or_default(),
                ev["detail"].as_str().map(|d| format!(" {d}")).unwrap_or_default()
            ));
        }
    }
    let notices = r["notices"].as_array().cloned().unwrap_or_default();
    if !notices.is_empty() {
        line("worker notices sent to starters:".into());
        for n in notices {
            line(format!(
                "  {} {} {} -> {} ({}, {} ms)",
                n["at"].as_i64().unwrap_or(0),
                n["subject"].as_str().unwrap_or("?"),
                n["ticketId"].as_str().unwrap_or("?"),
                n["to"].as_str().unwrap_or("?"),
                n["how"].as_str().unwrap_or("?"),
                n["lagMs"].as_i64().unwrap_or(0)
            ));
        }
    }
    let crashes = r["crashes"].as_array().cloned().unwrap_or_default();
    if !crashes.is_empty() {
        line(format!("crash files ({}), newest first:", crashes.len()));
        for c in crashes {
            line(format!("  {}", c.as_str().unwrap_or("?")));
        }
    }
    o.trim_end().to_string()
}

/// `health` `{sessionId?, projectId?, format?: "text"}` -> a `health` frame (the report JSON, or its text).
pub struct HealthUnit {
    pub health: Arc<Health>,
    pub sources: HealthSources,
}

impl FrameUnit for HealthUnit {
    fn name(&self) -> &'static str {
        "health"
    }
    fn frames(&self) -> &'static [&'static str] {
        &[HEALTH]
    }
    fn handle(&self, env: &Envelope, _ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let v: Value = if env.data.trim().is_empty() { Value::Null } else { serde_json::from_str(&env.data).map_err(|e| Refusal::new(HEALTH, format!("bad payload ({e})")))? };
        let s = |k: &str| v.get(k).and_then(Value::as_str).map(str::trim).filter(|x| !x.is_empty()).map(String::from);
        let session = s("sessionId").or_else(|| env.session_id.clone());
        let r = report(&self.health, &self.sources, session.as_deref(), s("projectId").as_deref());
        let data = if s("format").as_deref() == Some("text") { render(&r) } else { serde_json::to_string_pretty(&r).unwrap_or_default() };
        let mut e = Envelope::new(HEALTH, data);
        e.request_id = env.request_id.clone();
        e.session_id = env.session_id.clone();
        Ok(vec![e])
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn stop_daemons_under_kills_only_a_daemon_of_that_worktree_by_pid() {
        let dir = std::env::temp_dir().join(format!("infantry-reg2-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        #[cfg(windows)]
        let child = infantry_proc::command("ping").args(["-n", "60", "127.0.0.1"]).stdout(std::process::Stdio::null()).spawn().unwrap();
        #[cfg(not(windows))]
        let child = infantry_proc::command("sleep").arg("60").spawn().unwrap();
        let pid = child.id();
        let mut guard = infantry_proc::KillOnDrop(child);
        std::fs::write(dir.join("a.json"), format!(r#"{{"pid":{pid},"port":18950,"cwd":"","stateDir":"C:/wt/t1/state"}}"#)).unwrap();
        // another worktree and an owner port are left alone
        assert!(stop_daemons_under(&dir, Path::new(r"C:/wt/t2"), 1, &[8787]).is_empty());
        assert!(stop_daemons_under(&dir, Path::new(r"C:/wt/t1"), 1, &[18950]).is_empty());
        assert_eq!(stop_daemons_under(&dir, Path::new(r"C:/wt/t1"), 1, &[8787]), vec![pid]);
        assert!(guard.0.wait().is_ok());
    }

    #[test]
    fn other_daemons_lists_live_records_skips_me_and_prunes_dead_ones() {
        let dir = std::env::temp_dir().join(format!("infantry-reg-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let live = std::process::id();
        std::fs::write(dir.join("1.json"), format!(r#"{{"pid":{live},"port":18123,"exe":"x","stateDir":"s"}}"#)).unwrap();
        std::fs::write(dir.join("2.json"), r#"{"pid":4000000000,"port":18950,"exe":"y","stateDir":"t"}"#).unwrap();
        assert_eq!(super::other_daemons(&dir, live + 1).len(), 1);
        assert!(super::other_daemons(&dir, live).is_empty());
        assert!(!dir.join("2.json").exists(), "a dead pid's record is pruned");
    }

    use super::*;

    #[test]
    fn journal_names_why_the_last_run_ended() {
        let d = std::env::temp_dir().join(format!("health-{}", uuid::Uuid::new_v4()));
        let j = HealthJournal::new(&d);
        assert!(j.begin(1, 18901, "b").last_restart_cause.is_none());
        j.end("ctrl-c");
        assert_eq!(j.begin(2, 18901, "b").last_restart_cause.as_deref(), Some("ctrl-c"));
        // A record still marked running whose pid is gone: a crash.
        assert_eq!(j.begin(i32::MAX as i64, 18901, "b").last_restart_cause.as_deref(), Some("crash or kill (no clean stop recorded)"));
        assert_eq!(j.tail(10).len(), 4);
        let _ = std::fs::remove_dir_all(d);
    }

    /// RUST-BUG e0c433c9: a crash restart names the dead pid's last panic and its WER crash dump.
    #[test]
    fn crash_restart_names_the_panic_and_the_dump() {
        let d = std::env::temp_dir().join(format!("health-{}", uuid::Uuid::new_v4()));
        let dumps = d.join("dumps");
        std::fs::create_dir_all(&dumps).unwrap();
        let j = HealthJournal::new(&d);
        let dead = i32::MAX as i64;
        j.begin_with_dumps(dead, 18901, "b", Some(&dumps));
        append_panic_line(&d.join(DIR), &format!("1 pid {dead} thread 'main' panicked: boom at x.rs:1:1"));
        append_panic_line(&d.join(DIR), "2 pid 7 thread 'main' panicked: someone else");
        let exe = std::env::current_exe().unwrap().file_name().unwrap().to_string_lossy().into_owned();
        std::fs::write(dumps.join(format!("{exe}.{dead}.dmp")), b"MDMP").unwrap();
        j.begin_with_dumps(dead - 1, 18901, "b", Some(&dumps));
        let detail = j.tail(1)[0].detail.clone().unwrap();
        assert!(detail.contains(&format!("previous pid {dead}")), "{detail}");
        assert!(detail.contains("last panic: boom at x.rs:1:1"), "{detail}");
        assert!(!detail.contains("someone else"), "{detail}");
        assert!(detail.contains("crash dump: ") && detail.contains(".dmp"), "{detail}");
        let _ = std::fs::remove_dir_all(d);
    }

    /// A panic leaves a crash file (message + backtrace); old files go past the keep limit; evidence names the file.
    #[test]
    fn crash_files_are_written_listed_and_pruned() {
        let d = std::env::temp_dir().join(format!("infantry-crash-{}", std::process::id())).join(DIR);
        let _ = std::fs::remove_dir_all(&d);
        for i in 1..=4 {
            write_crash_file(&d, i, 77, "main", "boom at x.rs:1:1", "frame 0", 3);
        }
        let files = crash_files(&d);
        assert_eq!(files.len(), 3, "oldest pruned");
        let text = std::fs::read_to_string(files.last().unwrap()).unwrap();
        assert!(text.contains("boom at x.rs:1:1") && text.contains("backtrace:") && text.contains("pid 77"), "{text}");
        assert!(crash_evidence(&d, 77, None).iter().any(|e| e.starts_with("crash file: ")));
        let _ = std::fs::remove_dir_all(d.parent().unwrap());
    }

    #[test]
    fn panic_log_rolls_over_past_its_limit() {
        let d = std::env::temp_dir().join(format!("health-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&d).unwrap();
        std::fs::write(d.join(PANICS), vec![b'x'; PANIC_LOG_MAX_BYTES as usize + 1]).unwrap();
        append_panic_line(&d, "1 pid 1 thread 'main' panicked: new");
        assert!(d.join("panics.prev.log").is_file());
        assert_eq!(std::fs::read_to_string(d.join(PANICS)).unwrap().trim(), "1 pid 1 thread 'main' panicked: new");
        let _ = std::fs::remove_dir_all(d);
    }

    #[test]
    fn bridges_count_reconnects_per_connection() {
        let d = std::env::temp_dir().join(format!("health-{}", uuid::Uuid::new_v4()));
        let j = HealthJournal::new(&d);
        let b = Bridges::default();
        let mut c1 = Vec::new();
        b.seen("s1", &mut c1, &j);
        b.seen("s1", &mut c1, &j);
        b.closed(&c1, Some("eof"), &j);
        assert!(!b.rows()[0].connected);
        let mut c2 = Vec::new();
        b.seen("s1", &mut c2, &j);
        assert_eq!(b.rows()[0].reconnects, 1);
        assert!(b.rows()[0].connected);
        let _ = std::fs::remove_dir_all(d);
    }

    /// A bridge that opens a short connection per tool call, with idle gaps, and closes each cleanly never reconnects.
    #[test]
    fn clean_per_call_connections_are_not_reconnects() {
        let d = std::env::temp_dir().join(format!("health-{}", uuid::Uuid::new_v4()));
        let j = HealthJournal::new(&d);
        let b = Bridges::default();
        for _ in 0..30 {
            let mut c = Vec::new();
            b.seen("pm", &mut c, &j);
            b.closed(&c, Some(CLEAN_CLOSE), &j);
        }
        assert_eq!(b.rows()[0].reconnects, 0);
        let mut c = Vec::new();
        b.seen("pm", &mut c, &j);
        b.closed(&c, Some("connection lost"), &j);
        b.seen("pm", &mut Vec::new(), &j);
        assert_eq!(b.rows()[0].reconnects, 1);
        let _ = std::fs::remove_dir_all(d);
    }

    #[test]
    fn owner_ports_default_to_the_dev_list() {
        if std::env::var(OWNER_PORTS_ENV).is_err() {
            assert_eq!(owner_ports(|_| None), DEV_OWNER_PORTS.to_vec());
            assert!(owner_ports(|_| Some("1".into())).is_empty());
        }
    }
}

#[cfg(test)]
mod disk_warning_tests {
    use super::*;

    #[test]
    fn warns_only_below_the_threshold() {
        assert!(disk_warning(Some(1000), 10_240).unwrap().contains("low disk"));
        assert!(disk_warning(Some(20_000), 10_240).is_none());
        assert!(disk_warning(None, 10_240).is_none());
    }
}
