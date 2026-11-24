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
            LockError::Held { state_dir, pid } => {
                let who = pid.map(|p| format!("pid {p}")).unwrap_or_else(|| "an unknown pid".into());
                write!(f, "state dir {} is already used by a live daemon ({who}); one daemon per state dir", state_dir.display())
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
        let _ = std::fs::write(state_dir.join(HOLDER_FILE), std::process::id().to_string());
        Ok(StateLock { _file: file })
    }
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
}
