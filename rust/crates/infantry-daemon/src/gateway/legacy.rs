// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The legacy backend process: the Kotlin daemon, started headless on a private loopback port. It shares the Rust
//! daemon's DATABASE (`INFANTRY_DB` = `<state>/infantry.db`) but has its OWN state dir, [`LEGACY_DIR`] under the Rust
//! one: Rust owns the session store (`sessions.json`), the mailbox and the other state files now, and the Kotlin child
//! must never write them (two writers of one `sessions.json` lose sessions). The few files the child needs to agree
//! with Rust on (the owner credential, the access token, the keystore) are copied in once when missing. It runs in a Job Object, so it dies with the gateway however the
//! gateway ends, and it is restarted with backoff when it exits.

use infantry_runner::job::Job;
use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// The first restart delay; it doubles per quick exit up to [BACKOFF_MAX].
pub const BACKOFF_START: Duration = Duration::from_secs(1);
pub const BACKOFF_MAX: Duration = Duration::from_secs(30);
/// A run at least this long counts as healthy: the next restart starts again at [BACKOFF_START].
pub const HEALTHY_RUN: Duration = Duration::from_secs(60);

/// Environment the gateway sets for the backend (the rest of the gateway's environment is inherited).
pub const ENV_PORT: &str = "INFANTRY_PORT";
pub const ENV_STATE_DIR: &str = "INFANTRY_STATE_DIR";
pub const ENV_HOST: &str = "INFANTRY_HOST";
pub const ENV_PORT_STRICT: &str = "INFANTRY_PORT_STRICT";
pub const ENV_DB: &str = "INFANTRY_DB";
/// The Kotlin child's own state dir, under the Rust state dir.
pub const LEGACY_DIR: &str = "legacy";
/// Files copied from the Rust state dir into [`LEGACY_DIR`] when missing there (credentials and the keystore only).
pub const SHARED_FILES: [&str; 5] = ["owner.token", "remote.token", "keystore.json", "keystore.key.dpapi", "network.properties"];
/// Names a client of ANOTHER daemon; the backend must not inherit it.
pub const ENV_URL: &str = "INFANTRY_URL";

/// Splits a command line: whitespace separates words, double quotes group (a `\"` inside is a quote).
pub fn split_command(line: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut quoted = false;
    let mut any = false;
    let mut chars = line.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '\\' if quoted && chars.peek() == Some(&'"') => {
                cur.push('"');
                chars.next();
            }
            '"' => {
                quoted = !quoted;
                any = true;
            }
            c if c.is_whitespace() && !quoted => {
                if any || !cur.is_empty() {
                    out.push(std::mem::take(&mut cur));
                    any = false;
                }
            }
            c => cur.push(c),
        }
    }
    if any || !cur.is_empty() {
        out.push(cur);
    }
    out
}

pub struct Legacy {
    command: Vec<String>,
    pub port: u16,
    state_dir: PathBuf,
    stop: AtomicBool,
    job: Mutex<Option<Job>>,
    pid: Mutex<Option<u32>>,
}

impl Legacy {
    pub fn new(command: Vec<String>, port: u16, state_dir: PathBuf) -> Result<Arc<Legacy>, String> {
        if command.is_empty() {
            return Err("the legacy backend command is empty".into());
        }
        Ok(Arc::new(Legacy { command, port, state_dir, stop: AtomicBool::new(false), job: Mutex::new(None), pid: Mutex::new(None) }))
    }

    /// The pid of the running backend, if any.
    pub fn pid(&self) -> Option<u32> {
        *self.pid.lock().unwrap()
    }

    /// Starts the supervisor thread: run, wait, restart with backoff, until [Legacy::stop].
    pub fn supervise(self: &Arc<Self>) {
        let me = self.clone();
        std::thread::Builder::new().name("legacy-backend".into()).spawn(move || me.run()).expect("legacy supervisor thread");
    }

    fn run(&self) {
        let mut backoff = BACKOFF_START;
        while !self.stop.load(Ordering::SeqCst) {
            let started = Instant::now();
            match self.start_once() {
                Ok(code) => eprintln!("[infantry] legacy backend exited ({code}) after {} s", started.elapsed().as_secs()),
                Err(e) => eprintln!("[infantry] legacy backend could not start: {e}"),
            }
            if self.stop.load(Ordering::SeqCst) {
                break;
            }
            backoff = if started.elapsed() >= HEALTHY_RUN { BACKOFF_START } else { backoff };
            eprintln!("[infantry] restarting the legacy backend in {} ms", backoff.as_millis());
            let until = Instant::now() + backoff;
            while Instant::now() < until && !self.stop.load(Ordering::SeqCst) {
                std::thread::sleep(Duration::from_millis(100));
            }
            backoff = (backoff * 2).min(BACKOFF_MAX);
        }
    }

    /// The Kotlin child's state dir (created, with the shared files copied in when missing).
    pub fn own_dir(&self) -> PathBuf {
        self.state_dir.join(LEGACY_DIR)
    }

    fn prepare_dir(&self) -> Result<PathBuf, String> {
        let dir = self.own_dir();
        std::fs::create_dir_all(&dir).map_err(|e| format!("{}: {e}", dir.display()))?;
        for f in SHARED_FILES {
            let (from, to) = (self.state_dir.join(f), dir.join(f));
            if from.is_file() && !to.exists() {
                let _ = std::fs::copy(&from, &to);
            }
        }
        Ok(dir)
    }

    fn start_once(&self) -> Result<String, String> {
        let job = Job::new().map_err(|e| format!("job object: {e}"))?;
        let own = self.prepare_dir()?;
        let mut cmd = Command::new(&self.command[0]);
        cmd.args(&self.command[1..])
            .env(ENV_PORT, self.port.to_string())
            .env(ENV_STATE_DIR, &own)
            .env(ENV_DB, self.state_dir.join("infantry.db"))
            .env(ENV_HOST, "127.0.0.1")
            .env(ENV_PORT_STRICT, "true")
            .env_remove(ENV_URL)
            .env_remove("AGENTHQ_URL")
            .env_remove("AGENTHQ_PORT")
            .env_remove("AGENTHQ_STATE_DIR")
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }
        let mut child = cmd.spawn().map_err(|e| format!("{}: {e}", self.command[0]))?;
        if let Err(e) = job.assign(child.id()) {
            eprintln!("[infantry] legacy backend not in a job object ({e}): it may outlive a crashed gateway");
        }
        *self.pid.lock().unwrap() = Some(child.id());
        *self.job.lock().unwrap() = Some(job);
        if self.stop.load(Ordering::SeqCst) {
            self.kill();
        }
        eprintln!("[infantry] legacy backend started (pid {}) on 127.0.0.1:{}", child.id(), self.port);
        let pipes: Vec<Box<dyn std::io::Read + Send>> = [
            child.stdout.take().map(|s| Box::new(s) as Box<dyn std::io::Read + Send>),
            child.stderr.take().map(|s| Box::new(s) as Box<dyn std::io::Read + Send>),
        ]
        .into_iter()
        .flatten()
        .collect();
        for p in pipes {
            std::thread::spawn(move || {
                for line in BufReader::new(p).lines().map_while(Result::ok) {
                    eprintln!("[legacy] {line}");
                }
            });
        }
        let status = child.wait().map_err(|e| e.to_string());
        *self.pid.lock().unwrap() = None;
        *self.job.lock().unwrap() = None;
        status.map(|s| s.to_string())
    }

    fn kill(&self) {
        if let Some(j) = self.job.lock().unwrap().as_ref() {
            j.kill();
        }
    }

    /// Stops the backend for good (no restart).
    pub fn stop(&self) {
        self.stop.store(true, Ordering::SeqCst);
        self.kill();
    }
}

impl Drop for Legacy {
    fn drop(&mut self) {
        self.stop();
    }
}

/// A free loopback port for the backend (bound, then released).
pub fn free_port() -> Result<u16, String> {
    let l = std::net::TcpListener::bind("127.0.0.1:0").map_err(|e| e.to_string())?;
    l.local_addr().map(|a| a.port()).map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn split_keeps_quoted_words() {
        assert_eq!(split_command(r#"java "@C:\a b\k.args" -x"#), vec!["java", r#"@C:\a b\k.args"#, "-x"]);
        assert_eq!(split_command(r#"  a  "" b "#), vec!["a", "", "b"]);
        assert_eq!(split_command(r#""say \"hi\"""#), vec![r#"say "hi""#]);
    }
}
