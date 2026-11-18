// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! [`PipeRunner`]: a minimal [`Runner`] on `std::process` with plain pipes (no PTY, no Job Object). INTERIM: it lets the
//! harness crates prove a real CLI turn before RUST-RUN-1's `infantry-runner-local` lands; the daemon uses that one.

use crate::stub_runner::{ProcEvent, Process, ProcessSpec, Runner, StdinMode};
use std::io::{self, BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::mpsc::{channel, Receiver, RecvTimeoutError, Sender};
use std::thread;
use std::time::Duration;

pub struct PipeRunner;

impl Runner for PipeRunner {
    fn name(&self) -> &str {
        "pipe"
    }

    fn spawn(&self, spec: &ProcessSpec) -> io::Result<Box<dyn Process>> {
        if spec.pty.is_some() {
            return Err(io::Error::new(io::ErrorKind::Unsupported, "PipeRunner has no PTY"));
        }
        let program = resolve(&spec.program).unwrap_or_else(|| PathBuf::from(&spec.program));
        let mut cmd = Command::new(&program);
        cmd.args(&spec.args).current_dir(&spec.cwd);
        for (k, _) in std::env::vars_os() {
            let k = k.to_string_lossy().to_string();
            if spec.env_remove.iter().any(|r| match r.strip_suffix('*') {
                Some(prefix) => k.starts_with(prefix),
                None => k == *r,
            }) {
                cmd.env_remove(&k);
            }
        }
        cmd.envs(spec.env.iter().map(|(k, v)| (k, v)));
        cmd.stdin(match spec.stdin {
            StdinMode::Null => Stdio::null(),
            _ => Stdio::piped(),
        });
        cmd.stdout(Stdio::piped()).stderr(Stdio::piped());
        let mut child = cmd.spawn()?;
        let pid = child.id();
        let mut stdin = child.stdin.take();
        if let StdinMode::Bytes(bytes) = &spec.stdin {
            if let Some(mut s) = stdin.take() {
                let bytes = bytes.clone();
                // Written on its own thread so a large prompt cannot deadlock against unread stdout; dropped = EOF.
                thread::spawn(move || {
                    let _ = s.write_all(&bytes);
                });
            }
        }
        let (tx, rx) = channel();
        let _ = tx.send(ProcEvent::Started { pid: Some(pid) });
        let out = lines(child.stdout.take().expect("piped"), tx.clone(), ProcEvent::Stdout);
        let err = lines(child.stderr.take().expect("piped"), tx.clone(), ProcEvent::Stderr);
        let waiter = WaitHandle::new(child);
        let w = waiter.clone();
        thread::spawn(move || {
            let _ = out.join();
            let _ = err.join();
            let code = w.wait();
            let _ = tx.send(ProcEvent::Exited { code });
        });
        Ok(Box::new(PipeProcess { pid, rx, stdin, waiter, done: false }))
    }
}

fn lines<R: Read + Send + 'static>(r: R, tx: Sender<ProcEvent>, wrap: fn(String) -> ProcEvent) -> thread::JoinHandle<()> {
    thread::spawn(move || {
        let mut r = BufReader::new(r);
        let mut buf = Vec::new();
        loop {
            buf.clear();
            match r.read_until(b'\n', &mut buf) {
                Ok(0) | Err(_) => break,
                Ok(_) => {
                    let s = String::from_utf8_lossy(&buf);
                    let _ = tx.send(wrap(s.trim_end_matches(['\r', '\n']).to_string()));
                }
            }
        }
    })
}

/// The child, shared between the waiter thread and kill.
#[derive(Clone)]
struct WaitHandle(std::sync::Arc<std::sync::Mutex<Child>>);

impl WaitHandle {
    fn new(c: Child) -> Self {
        WaitHandle(std::sync::Arc::new(std::sync::Mutex::new(c)))
    }
    fn wait(&self) -> Option<i32> {
        loop {
            if let Ok(Some(st)) = self.0.lock().expect("child lock").try_wait() {
                return st.code();
            }
            thread::sleep(Duration::from_millis(20));
        }
    }
}

struct PipeProcess {
    pid: u32,
    rx: Receiver<ProcEvent>,
    stdin: Option<ChildStdin>,
    waiter: WaitHandle,
    done: bool,
}

impl Process for PipeProcess {
    fn pid(&self) -> Option<u32> {
        Some(self.pid)
    }

    fn recv(&mut self, timeout: Duration) -> Option<ProcEvent> {
        if self.done {
            return None;
        }
        match self.rx.recv_timeout(timeout) {
            Ok(ev) => {
                self.done = matches!(ev, ProcEvent::Exited { .. });
                Some(ev)
            }
            Err(RecvTimeoutError::Timeout) | Err(RecvTimeoutError::Disconnected) => None,
        }
    }

    fn write(&mut self, bytes: &[u8]) -> io::Result<()> {
        match self.stdin.as_mut() {
            Some(s) => s.write_all(bytes).and_then(|_| s.flush()),
            None => Err(io::Error::new(io::ErrorKind::BrokenPipe, "stdin is not a pipe")),
        }
    }

    fn kill(&mut self) {
        self.stdin = None;
        kill_tree(self.pid, &self.waiter);
    }
}

impl Drop for PipeProcess {
    fn drop(&mut self) {
        if !self.done {
            self.kill();
        }
    }
}

#[cfg(windows)]
fn kill_tree(pid: u32, _w: &WaitHandle) {
    // The tree by PID (a `.cmd` shim's node child would outlive a plain kill).
    let _ = Command::new("taskkill")
        .args(["/PID", &pid.to_string(), "/T", "/F"])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
}

#[cfg(not(windows))]
fn kill_tree(_pid: u32, w: &WaitHandle) {
    let _ = w.0.lock().expect("child lock").kill();
}

/// [name] on PATH (with PATHEXT on Windows); None = leave it to the OS.
fn resolve(name: &str) -> Option<PathBuf> {
    let p = Path::new(name);
    if p.components().count() > 1 || p.extension().is_some() {
        return None;
    }
    let exts: Vec<String> = if cfg!(windows) {
        std::env::var("PATHEXT")
            .unwrap_or_else(|_| ".COM;.EXE;.BAT;.CMD".into())
            .split(';')
            .filter(|e| !e.is_empty())
            .map(|e| e.to_string())
            .collect()
    } else {
        vec![String::new()]
    };
    let path = std::env::var_os("PATH")?;
    for dir in std::env::split_paths(&path) {
        for ext in &exts {
            let c = dir.join(format!("{name}{ext}"));
            if c.is_file() {
                return Some(c);
            }
        }
    }
    None
}
