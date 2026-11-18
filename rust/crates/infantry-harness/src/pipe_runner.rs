// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! [`PipeRunner`]: a minimal [`Runner`] on `tokio::process`, plain pipes only (no PTY, no Job Object, no hook channel,
//! no tail). INTERIM, behind the `pipe-runner` feature: it lets the harness crates prove a real CLI turn before
//! RUST-RUN-1's `infantry-runner-local` lands. Nothing in production uses it.

use crate::runner::{
    HookEndpoint, KillScope, Pong, ProcEvent, ProcHandle, ProcId, ProcessSpec, Runner, RunnerError, RunnerFuture,
    ScreenSnapshot, Size, TailSpec,
};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::process::Stdio;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt};
use tokio::process::{ChildStdin, Command};
use tokio::sync::mpsc::{unbounded_channel, UnboundedReceiver, UnboundedSender};

struct Entry {
    pid: Option<u32>,
    /// Set before `Exited` is sent: an exited pid is never killed (the OS may have reused it).
    exited: Arc<AtomicBool>,
    stdin: Option<ChildStdin>,
    events: Option<UnboundedReceiver<ProcEvent>>,
}

#[derive(Default)]
pub struct PipeRunner {
    seq: AtomicU64,
    procs: Mutex<HashMap<ProcId, Entry>>,
}

impl PipeRunner {
    pub fn new() -> Self {
        Self::default()
    }

    fn unsupported<'a, T: Send + 'a>(what: &str) -> RunnerFuture<'a, T> {
        let msg = format!("PipeRunner has no {what}");
        Box::pin(async move { Err(RunnerError::Unsupported(msg)) })
    }
}

fn pump<R: AsyncRead + Unpin + Send + 'static>(
    mut r: R,
    tx: UnboundedSender<ProcEvent>,
    wrap: fn(Vec<u8>) -> ProcEvent,
) -> tokio::task::JoinHandle<()> {
    tokio::spawn(async move {
        let mut buf = vec![0u8; 8192];
        loop {
            match r.read(&mut buf).await {
                Ok(0) | Err(_) => break,
                Ok(n) => {
                    let _ = tx.send(wrap(buf[..n].to_vec()));
                }
            }
        }
    })
}

impl Runner for PipeRunner {
    fn id(&self) -> &str {
        "pipe"
    }

    fn ping(&self) -> RunnerFuture<'_, Pong> {
        Box::pin(async {
            Ok(Pong { version: env!("CARGO_PKG_VERSION").into(), os: std::env::consts::OS.into(), harnesses: Vec::new() })
        })
    }

    fn spawn(&self, spec: ProcessSpec) -> RunnerFuture<'_, ProcHandle> {
        Box::pin(async move {
            if spec.pty.is_some() {
                return Err(RunnerError::Unsupported("PipeRunner has no PTY".into()));
            }
            let program = resolve(&spec.program).unwrap_or_else(|| PathBuf::from(&spec.program));
            let mut cmd = Command::new(&program);
            cmd.args(&spec.args);
            if let Some(cwd) = &spec.cwd {
                cmd.current_dir(cwd);
            }
            for (k, _) in std::env::vars_os() {
                let k = k.to_string_lossy().to_string();
                let lk = k.to_ascii_lowercase();
                if spec.env_remove.iter().any(|r| {
                    let r = r.to_ascii_lowercase();
                    match r.strip_suffix('*') {
                        Some(prefix) => lk.starts_with(prefix),
                        None => lk == r,
                    }
                }) {
                    cmd.env_remove(&k);
                }
            }
            cmd.envs(spec.env.iter().map(|(k, v)| (k, v)));
            cmd.stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped()).kill_on_drop(false);
            let mut child = cmd.spawn()?;
            let pid = child.id();
            let (tx, rx) = unbounded_channel();
            let out = pump(child.stdout.take().expect("piped"), tx.clone(), ProcEvent::Output);
            let err = pump(child.stderr.take().expect("piped"), tx.clone(), ProcEvent::Stderr);
            let stdin = child.stdin.take();
            let exited = Arc::new(AtomicBool::new(false));
            let flag = exited.clone();
            tokio::spawn(async move {
                let code = child.wait().await.ok().and_then(|s| s.code()).unwrap_or(1);
                flag.store(true, Ordering::SeqCst);
                let _ = out.await;
                let _ = err.await;
                let _ = tx.send(ProcEvent::Exited(code));
            });
            let id = ProcId(format!("pipe-{}", self.seq.fetch_add(1, Ordering::Relaxed) + 1));
            self.procs.lock().expect("procs").insert(id.clone(), Entry { pid, exited, stdin, events: Some(rx) });
            Ok(ProcHandle { id, pid })
        })
    }

    fn write<'a>(&'a self, id: &'a ProcId, data: &'a [u8]) -> RunnerFuture<'a, ()> {
        Box::pin(async move {
            let stdin = self.procs.lock().expect("procs").get_mut(id).ok_or_else(|| RunnerError::NotFound(id.0.clone()))?.stdin.take();
            let Some(mut s) = stdin else { return Err(RunnerError::Exited(format!("{id}: stdin closed"))) };
            let r = async {
                s.write_all(data).await?;
                s.flush().await
            }
            .await;
            if let Some(e) = self.procs.lock().expect("procs").get_mut(id) {
                e.stdin = Some(s);
            }
            r.map_err(RunnerError::from)
        })
    }

    fn close_input<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, ()> {
        Box::pin(async move {
            if let Some(e) = self.procs.lock().expect("procs").get_mut(id) {
                e.stdin = None;
            }
            Ok(())
        })
    }

    fn resize<'a>(&'a self, _id: &'a ProcId, _size: Size) -> RunnerFuture<'a, ()> {
        Self::unsupported("PTY")
    }

    fn kill<'a>(&'a self, id: &'a ProcId, _scope: KillScope) -> RunnerFuture<'a, ()> {
        Box::pin(async move {
            let pid = self.procs.lock().expect("procs").get(id).filter(|e| !e.exited.load(Ordering::SeqCst)).and_then(|e| e.pid);
            if let Some(pid) = pid {
                kill_pid_tree(pid).await;
            }
            Ok(())
        })
    }

    fn events<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, UnboundedReceiver<ProcEvent>> {
        Box::pin(async move {
            self.procs
                .lock()
                .expect("procs")
                .get_mut(id)
                .ok_or_else(|| RunnerError::NotFound(id.0.clone()))?
                .events
                .take()
                .ok_or_else(|| RunnerError::Unsupported("PipeRunner serves one events subscriber".into()))
        })
    }

    fn screen<'a>(&'a self, _id: &'a ProcId) -> RunnerFuture<'a, ScreenSnapshot> {
        Self::unsupported("PTY screen")
    }

    fn screen_text_since<'a>(&'a self, _id: &'a ProcId, _mark: u64, _max: usize) -> RunnerFuture<'a, String> {
        Self::unsupported("PTY screen")
    }

    fn tail(&self, _spec: TailSpec) -> RunnerFuture<'_, UnboundedReceiver<String>> {
        Self::unsupported("file tail")
    }

    fn hook_channel(&self) -> RunnerFuture<'_, HookEndpoint> {
        Self::unsupported("hook channel")
    }

    fn release<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, ()> {
        Box::pin(async move {
            let entry = self.procs.lock().expect("procs").remove(id);
            if let Some(Entry { pid: Some(pid), exited, .. }) = entry {
                if !exited.load(Ordering::SeqCst) {
                    kill_pid_tree(pid).await;
                }
            }
            Ok(())
        })
    }
}

#[cfg(windows)]
async fn kill_pid_tree(pid: u32) {
    // By PID, the whole tree (a `.cmd` shim's node child would outlive a plain kill).
    let _ = Command::new("taskkill")
        .args(["/PID", &pid.to_string(), "/T", "/F"])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status()
        .await;
}

#[cfg(not(windows))]
async fn kill_pid_tree(pid: u32) {
    let _ = Command::new("kill").args(["-9", &pid.to_string()]).status().await;
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
