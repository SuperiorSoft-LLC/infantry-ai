// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! DAEMON-LOCK: one live daemon per state dir. [`StateLock::acquire`] takes an OS-level exclusive lock on
//! `<state>/daemon.lock` before the caller opens any other file of the state dir, and holds it for the life of the value
//! (the process); the OS releases it at once when the process dies, however it dies. A refused start writes nothing:
//! the holder's pid is read from `daemon.lock.pid`, which only the holder writes (after it holds the lock).
use std::fs::{File, OpenOptions, TryLockError};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

pub const LOCK_FILE: &str = "daemon.lock";
pub const HOLDER_FILE: &str = "daemon.lock.pid";
/// How long a start waits for a holder that is still closing (the restart/update path), unless the caller says otherwise.
pub const DEFAULT_WAIT: Duration = Duration::from_secs(5);
const POLL: Duration = Duration::from_millis(50);

#[derive(Debug)]
pub struct StateLock {
    _file: File,
}

#[derive(Debug, PartialEq, Eq)]
pub enum LockError {
    /// A live daemon holds the state dir (its pid when it could be read).
    Held { state_dir: PathBuf, pid: Option<u32> },
    Io(String),
}

impl std::fmt::Display for LockError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            LockError::Held { pid, .. } => {
                let who = pid.map(|p| p.to_string()).unwrap_or_else(|| "unknown".into());
                write!(f, "Infantry AI is already running with this data (process {who}). Closing its window can leave it running in the background: end process {who} (Task Manager, Details tab, End task), then press Retry.")
            }
            LockError::Io(e) => write!(f, "state dir lock: {e}"),
        }
    }
}

impl StateLock {
    /// Takes the lock, waiting up to `wait` for a holder that is closing. The state dir must exist.
    pub fn acquire(state_dir: &Path, wait: Duration) -> Result<StateLock, LockError> {
        let path = state_dir.join(LOCK_FILE);
        let file = OpenOptions::new().read(true).write(true).create(true).truncate(false).open(&path).map_err(|e| LockError::Io(format!("{}: {e}", path.display())))?;
        let start = Instant::now();
        loop {
            match file.try_lock() {
                Ok(()) => break,
                Err(TryLockError::WouldBlock) => {
                    if start.elapsed() >= wait {
                        return Err(LockError::Held { state_dir: state_dir.to_path_buf(), pid: holder_pid(state_dir) });
                    }
                    std::thread::sleep(POLL);
                }
                Err(TryLockError::Error(e)) => return Err(LockError::Io(format!("{}: {e}", path.display()))),
            }
        }
        // An OLDER daemon (0.7.2 and before) takes no lock; it is seen by its recorded pid. The same wait lets one that is closing finish.
        while let Some(pid) = legacy_holder(state_dir) {
            if start.elapsed() >= wait {
                return Err(LockError::Held { state_dir: state_dir.to_path_buf(), pid: Some(pid) });
            }
            std::thread::sleep(POLL);
        }
        let _ = std::fs::write(state_dir.join(HOLDER_FILE), std::process::id().to_string());
        Ok(StateLock { _file: file })
    }
}

/// The pid of an older (lock-less) daemon using this folder, or None. An older daemon is seen by what it really keeps:
/// `health/status.json` (pid, port, running), `daemon.pid` and `daemon.port` (any of them may be stale or missing: 0.7.2 left
/// a dead pid in `daemon.pid` while it ran). A recorded pid must be ALIVE and a recorded port must answer `/health` as an
/// Infantry AI daemon (a 401/403 counts); a dead pid, or a reused pid whose port answers nothing like a daemon, never blocks.
pub fn legacy_holder(state_dir: &Path) -> Option<u32> {
    legacy_holder_with(state_dir, &|p| crate::health::pid_alive(p as i64), &answers_as_daemon)
}

pub fn legacy_holder_with(state_dir: &Path, alive: &dyn Fn(u32) -> bool, is_daemon: &dyn Fn(u16) -> bool) -> Option<u32> {
    let num = |f: &Path| std::fs::read_to_string(f).ok().and_then(|t| t.trim().parse::<u32>().ok());
    let status: Option<serde_json::Value> = std::fs::read_to_string(state_dir.join("health").join(crate::health::STATUS)).ok().and_then(|t| serde_json::from_str(&t).ok());
    let mut pids: Vec<u32> = Vec::new();
    let mut ports: Vec<u16> = Vec::new();
    if let Some(st) = status.filter(|st| st["running"].as_bool().unwrap_or(true)) {
        pids.extend(st["pid"].as_u64().and_then(|p| u32::try_from(p).ok()));
        ports.extend(st["port"].as_u64().and_then(|p| u16::try_from(p).ok()));
    }
    pids.extend(num(&state_dir.join("daemon.pid")));
    ports.extend(num(&state_dir.join("daemon.port")).and_then(|p| u16::try_from(p).ok()));
    let pid = pids.into_iter().filter(|p| *p != 0).find(|p| alive(*p))?;
    ports.into_iter().filter(|p| *p != 0).any(|p| is_daemon(p)).then_some(pid)
}

/// True when `127.0.0.1:port` answers `GET /health` as an Infantry AI daemon (`contract` and `buildId` in the JSON), or
/// with 401/403 (a daemon that wants its token).
fn answers_as_daemon(port: u16) -> bool {
    use std::io::{Read, Write};
    let addr = std::net::SocketAddr::from(([127, 0, 0, 1], port));
    let Ok(mut s) = std::net::TcpStream::connect_timeout(&addr, Duration::from_millis(500)) else { return false };
    let _ = s.set_read_timeout(Some(Duration::from_millis(800)));
    let _ = s.set_write_timeout(Some(Duration::from_millis(500)));
    if s.write_all(format!("GET /health HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nConnection: close\r\n\r\n").as_bytes()).is_err() {
        return false;
    }
    let mut buf = Vec::new();
    let _ = s.take(64 * 1024).read_to_end(&mut buf);
    let text = String::from_utf8_lossy(&buf);
    let status = text.lines().next().unwrap_or("");
    status.contains(" 401") || status.contains(" 403") || (status.contains(" 200") && text.contains("\"contract\"") && text.contains("\"buildId\""))
}

/// The pid the current holder recorded (None when unreadable).
pub fn holder_pid(state_dir: &Path) -> Option<u32> {
    std::fs::read_to_string(state_dir.join(HOLDER_FILE)).ok()?.trim().parse().ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_second_lock_is_refused_and_the_holders_files_stay() {
        let d = tempfile::tempdir().unwrap();
        let first = StateLock::acquire(d.path(), Duration::ZERO).unwrap();
        std::fs::write(d.path().join("daemon.pid"), "111").unwrap();
        let before = std::fs::read(d.path().join(HOLDER_FILE)).unwrap();
        let err = StateLock::acquire(d.path(), Duration::from_millis(120)).unwrap_err();
        assert_eq!(err, LockError::Held { state_dir: d.path().to_path_buf(), pid: Some(std::process::id()) });
        assert!(err.to_string().contains(&std::process::id().to_string()));
        assert_eq!(std::fs::read(d.path().join(HOLDER_FILE)).unwrap(), before);
        assert_eq!(std::fs::read_to_string(d.path().join("daemon.pid")).unwrap(), "111");
        drop(first);
        StateLock::acquire(d.path(), Duration::ZERO).unwrap();
    }

    /// A child process takes the lock and is killed; the lock is free at once (the OS released it).
    #[test]
    fn a_killed_holders_lock_is_free_at_once() {
        if let Ok(dir) = std::env::var("STATE_LOCK_TEST_CHILD") {
            let _l = StateLock::acquire(Path::new(&dir), Duration::ZERO).unwrap();
            println!("LOCKED");
            std::thread::sleep(Duration::from_secs(60));
            return;
        }
        use std::io::BufRead;
        let d = tempfile::tempdir().unwrap();
        let mut child = std::process::Command::new(std::env::current_exe().unwrap())
            .args(["state_lock::tests::a_killed_holders_lock_is_free_at_once", "--exact", "--nocapture", "--test-threads=1"])
            .env("STATE_LOCK_TEST_CHILD", d.path())
            .stdout(std::process::Stdio::piped())
            .spawn()
            .unwrap();
        let mut out = std::io::BufReader::new(child.stdout.take().unwrap());
        let mut line = String::new();
        loop {
            line.clear();
            assert!(out.read_line(&mut line).unwrap() > 0, "child exited before locking");
            if line.contains("LOCKED") {
                break;
            }
        }
        assert!(matches!(StateLock::acquire(d.path(), Duration::ZERO), Err(LockError::Held { pid: Some(p), .. }) if p == child.id()));
        child.kill().unwrap();
        child.wait().unwrap();
        StateLock::acquire(d.path(), Duration::ZERO).expect("lock free right after the kill");
    }

    /// A tiny server on a free port that answers every request with `body` (what `/health` of a daemon, or of something else, says).
    fn fake_health(body: &'static str) -> u16 {
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        std::thread::spawn(move || {
            use std::io::{Read, Write};
            for mut c in l.incoming().flatten().take(40) {
                let mut b = [0u8; 512];
                let _ = c.read(&mut b);
                let _ = c.write_all(format!("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()).as_bytes());
            }
        });
        port
    }

    fn old_daemon_files(d: &Path, pid: u32, port: u16) {
        std::fs::write(d.join("daemon.pid"), pid.to_string()).unwrap();
        std::fs::write(d.join("daemon.port"), port.to_string()).unwrap();
    }

    #[test]
    fn an_older_daemons_live_pid_is_refused_and_nothing_is_written() {
        let d = tempfile::tempdir().unwrap();
        let port = fake_health(r#"{"ok":true,"contract":1,"buildId":"x"}"#);
        old_daemon_files(d.path(), std::process::id(), port);
        let err = StateLock::acquire(d.path(), Duration::from_millis(150)).unwrap_err();
        assert_eq!(err, LockError::Held { state_dir: d.path().to_path_buf(), pid: Some(std::process::id()) });
        assert_eq!(err.to_string(), format!("Infantry AI is already running with this data (process {0}). Closing its window can leave it running in the background: end process {0} (Task Manager, Details tab, End task), then press Retry.", std::process::id()));
        assert!(!d.path().join(HOLDER_FILE).exists());
    }

    /// The 2026-11-24 case: `daemon.pid` holds a dead pid, `daemon.port` is gone; only `health/status.json` names the live daemon.
    #[test]
    fn an_older_daemon_is_seen_by_its_status_file_when_daemon_pid_is_dead() {
        let d = tempfile::tempdir().unwrap();
        let port = fake_health(r#"{"ok":true,"contract":1,"buildId":"x"}"#);
        let mut child = std::process::Command::new(std::env::current_exe().unwrap()).args(["--list"]).stdout(std::process::Stdio::null()).spawn().unwrap();
        let dead = child.id();
        child.wait().unwrap();
        std::fs::write(d.path().join("daemon.pid"), dead.to_string()).unwrap();
        std::fs::create_dir_all(d.path().join("health")).unwrap();
        std::fs::write(d.path().join("health/status.json"), format!(r#"{{"pid":{},"port":{port},"running":true}}"#, std::process::id())).unwrap();
        let err = StateLock::acquire(d.path(), Duration::from_millis(150)).unwrap_err();
        assert_eq!(err, LockError::Held { state_dir: d.path().to_path_buf(), pid: Some(std::process::id()) });
        assert!(!d.path().join(HOLDER_FILE).exists() && !d.path().join("launch.json").exists());
        // A record that says running:false, or a port that answers nothing, never blocks.
        std::fs::write(d.path().join("health/status.json"), format!(r#"{{"pid":{},"port":{port},"running":false}}"#, std::process::id())).unwrap();
        StateLock::acquire(d.path(), Duration::ZERO).unwrap();
    }

    /// A daemon that wants its token answers 401: it still counts as a daemon.
    #[test]
    fn a_daemon_that_answers_401_counts() {
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        std::thread::spawn(move || {
            use std::io::{Read, Write};
            for mut c in l.incoming().flatten().take(10) {
                let mut b = [0u8; 512];
                let _ = c.read(&mut b);
                let _ = c.write_all(b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
            }
        });
        let d = tempfile::tempdir().unwrap();
        old_daemon_files(d.path(), std::process::id(), port);
        assert!(StateLock::acquire(d.path(), Duration::from_millis(100)).is_err());
    }

    #[test]
    fn a_dead_pid_never_blocks() {
        let d = tempfile::tempdir().unwrap();
        let port = fake_health(r#"{"ok":true,"contract":1,"buildId":"x"}"#);
        let mut child = std::process::Command::new(std::env::current_exe().unwrap()).args(["--list"]).stdout(std::process::Stdio::null()).spawn().unwrap();
        let dead = child.id();
        child.wait().unwrap();
        old_daemon_files(d.path(), dead, port);
        StateLock::acquire(d.path(), Duration::ZERO).unwrap();
    }

    #[test]
    fn a_reused_pid_never_blocks() {
        // The pid is alive (this process) but the recorded port answers like another program, or answers nothing.
        let d = tempfile::tempdir().unwrap();
        old_daemon_files(d.path(), std::process::id(), fake_health("<html>not infantry</html>"));
        StateLock::acquire(d.path(), Duration::ZERO).unwrap();
        let d2 = tempfile::tempdir().unwrap();
        let closed = std::net::TcpListener::bind("127.0.0.1:0").unwrap().local_addr().unwrap().port();
        old_daemon_files(d2.path(), std::process::id(), closed);
        StateLock::acquire(d2.path(), Duration::ZERO).unwrap();
    }

    /// The standalone restart path: the old daemon is stopped and the new one starts at once; the start waits for the lock.
    #[test]
    fn a_start_waits_for_a_holder_that_is_closing() {
        let d = tempfile::tempdir().unwrap();
        let first = StateLock::acquire(d.path(), Duration::ZERO).unwrap();
        let t = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(300));
            drop(first);
        });
        let started = Instant::now();
        StateLock::acquire(d.path(), Duration::from_secs(5)).unwrap();
        assert!(started.elapsed() >= Duration::from_millis(250));
        t.join().unwrap();
    }
}
