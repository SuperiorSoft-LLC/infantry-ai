// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The local runner (RUST-RUN-1): [`infantry_runner_api::Runner`] on this machine, built on `infantry-runner`.
//!
//! - piped processes: `std::process` with `CREATE_NO_WINDOW` (never a console window), stdout/stderr pumped on threads;
//! - pty processes: ConPTY on Windows ([`conpty`], resizable), openpty elsewhere; the output feeds a terminal screen
//!   (`pty-screen`) and the runner answers the terminal's cursor-position queries itself;
//! - every process lives in its own Job Object (a process group elsewhere): [`KillScope::Tree`] kills every descendant, and
//!   the whole tree dies with the runner;
//! - `file-tail` on `infantry_runner::tail::AppendedLines`; `hook-channel` on `infantry_runner::hooks::HookServer`;
//! - `loopback-http` on a small HTTP/1.1 client ([`http`]); `staged-files` in the scratch folder's `staged/`.

#[cfg(windows)]
mod conpty;
mod http;
mod hub;
mod spawn;

use hub::Hub;
use infantry_runner::hooks::{HookHandler, HookServer};
use infantry_runner::job::Job;
use infantry_runner::screen::Screen;
use infantry_runner::tail::AppendedLines;
use infantry_runner_api::{
    HookCall, HookEndpoint, HttpEvent, HttpRequest, KillScope, Pong, ProcEvent, ProcHandle, ProcId, ProcessSpec, Runner, RunnerError, RunnerFuture,
    ScreenSnapshot, Size, TailFrom, TailSpec,
};
use std::collections::HashMap;
use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::{mpsc, oneshot};

/// The local runner's knobs. Every default is the cheap choice.
#[derive(Debug, Clone)]
pub struct LocalConfig {
    /// Harness CLI names [`Runner::ping`] looks for on PATH.
    pub harnesses: Vec<String>,
    /// Output bytes kept per process before its first `events` subscriber (the oldest go first).
    pub backlog_max_bytes: usize,
    /// After a process exits, how long its output may still drain before [`ProcEvent::Exited`] is sent anyway (a
    /// grandchild that keeps the pipe open cannot hold the exit back).
    pub drain_grace: Duration,
    /// How often [`Runner::tail`] looks at its file.
    pub tail_poll: Duration,
    /// Characters of plain text in [`ScreenSnapshot::tail`].
    pub screen_tail_chars: usize,
    /// Where hook events files go. Default: a fresh folder under the temp dir, removed with the runner.
    pub scratch_dir: Option<PathBuf>,
}

impl Default for LocalConfig {
    fn default() -> Self {
        LocalConfig {
            harnesses: Vec::new(),
            backlog_max_bytes: 4 * 1024 * 1024,
            drain_grace: Duration::from_secs(2),
            tail_poll: Duration::from_millis(200),
            screen_tail_chars: 4096,
            scratch_dir: None,
        }
    }
}

/// A terminal's state: the vt100 grid (resizable, for rows and cursor) and `infantry_runner`'s plain-text scrollback.
pub(crate) struct Term {
    grid: vt100::Parser,
    scroll: Screen,
    epoch: std::time::Instant,
}

impl Term {
    fn new(size: Size) -> Self {
        Term { grid: vt100::Parser::new(size.rows, size.cols, 0), scroll: Screen::new(size.rows, size.cols), epoch: std::time::Instant::now() }
    }

    pub(crate) fn feed(&mut self, data: &[u8]) {
        self.grid.process(data);
        self.scroll.feed(data, self.epoch.elapsed().as_millis() as u64);
    }

    pub(crate) fn bytes(&self) -> u64 {
        self.scroll.bytes()
    }

    /// The answer a terminal gives to the queries in [data] (a program may block on a cursor-position query).
    pub(crate) fn answers(&self, data: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        for _ in infantry_runner::screen::queries(data) {
            let (r, c) = self.grid.screen().cursor_position();
            out.extend(format!("\x1b[{};{}R", r + 1, c + 1).into_bytes());
        }
        out
    }

    fn resize(&mut self, size: Size) {
        self.grid.screen_mut().set_size(size.rows, size.cols);
    }

    fn snapshot(&self, tail: usize) -> ScreenSnapshot {
        let s = self.grid.screen();
        let (rows, cols) = s.size();
        ScreenSnapshot {
            size: Size { rows, cols },
            rows: s.rows(0, cols).map(|r| r.trim_end().to_string()).collect(),
            cursor: s.cursor_position(),
            total: self.scroll.total(),
            tail: self.scroll.tail_chars(tail),
        }
    }
}

pub(crate) type Input = Arc<Mutex<Option<Box<dyn Write + Send>>>>;

/// The platform half of a pty: resize and close.
pub(crate) trait PtyCtl: Send + Sync {
    fn resize(&self, size: Size) -> std::io::Result<()>;
}

/// One process the runner started.
pub(crate) struct Proc {
    pub hub: Arc<Hub>,
    pub pid: u32,
    pub input: Input,
    pub term: Option<Arc<Mutex<Term>>>,
    pub pty: Option<Box<dyn PtyCtl>>,
    pub job: Arc<Job>,
    /// Kills only the direct child.
    pub kill_one: Box<dyn Fn() + Send + Sync>,
}

/// The [`Runner`] on this machine.
pub struct LocalRunner {
    cfg: LocalConfig,
    scratch: PathBuf,
    owns_scratch: bool,
    procs: Mutex<HashMap<ProcId, Arc<Proc>>>,
    next: AtomicU64,
    /// RR-8: a per-boot tag in every id (`p<tag>-<n>`), so a restarted runner never reuses an id a peer still holds a cursor for.
    id_tag: Option<String>,
    hooks: Mutex<Option<Arc<HookServer>>>,
}

impl Default for LocalRunner {
    fn default() -> Self {
        LocalRunner::new(LocalConfig::default())
    }
}

impl LocalRunner {
    pub fn new(cfg: LocalConfig) -> Self {
        let (scratch, owns_scratch) = match &cfg.scratch_dir {
            Some(d) => (d.clone(), false),
            None => (std::env::temp_dir().join(format!("infantry-runner-local-{}", uuid::Uuid::new_v4())), true),
        };
        LocalRunner { cfg, scratch, owns_scratch, procs: Mutex::default(), next: AtomicU64::new(1), id_tag: None, hooks: Mutex::default() }
    }

    /// Process ids carry `tag` (`p<tag>-<n>`): a runner that persists its link sessions across restarts passes a per-boot tag.
    pub fn with_id_tag(mut self, tag: impl Into<String>) -> Self {
        self.id_tag = Some(tag.into());
        self
    }

    fn proc(&self, id: &ProcId) -> Result<Arc<Proc>, RunnerError> {
        self.procs.lock().unwrap().get(id).cloned().ok_or_else(|| RunnerError::NotFound(id.0.clone()))
    }

    fn term(&self, id: &ProcId) -> Result<Arc<Mutex<Term>>, RunnerError> {
        self.proc(id)?.term.clone().ok_or_else(|| RunnerError::Unsupported(format!("{id} is not in a pseudo-terminal")))
    }

    fn hook_server(&self) -> std::io::Result<Arc<HookServer>> {
        let mut g = self.hooks.lock().unwrap();
        if let Some(s) = g.as_ref() {
            return Ok(s.clone());
        }
        let s = HookServer::start()?;
        *g = Some(s.clone());
        Ok(s)
    }

    fn kill_now(p: &Proc, scope: KillScope) {
        if p.hub.exit().is_some() && scope == KillScope::Process {
            return;
        }
        match scope {
            KillScope::Process => (p.kill_one)(),
            _ => {
                p.job.kill();
                if p.hub.exit().is_none() {
                    (p.kill_one)();
                }
            }
        }
    }
}

impl Drop for LocalRunner {
    fn drop(&mut self) {
        for p in self.procs.lock().unwrap().values() {
            p.job.kill();
        }
        if self.owns_scratch {
            let _ = std::fs::remove_dir_all(&self.scratch);
        }
    }
}

/// Whether [name] is a program on PATH (with PATHEXT on Windows).
fn on_path(name: &str) -> bool {
    let Some(path) = std::env::var_os("PATH") else { return false };
    let exts: Vec<String> = if cfg!(windows) {
        let pe = std::env::var("PATHEXT").unwrap_or_else(|_| ".COM;.EXE;.BAT;.CMD".into());
        std::iter::once(String::new()).chain(pe.split(';').filter(|e| !e.is_empty()).map(str::to_string)).collect()
    } else {
        vec![String::new()]
    };
    std::env::split_paths(&path).any(|dir| exts.iter().any(|e| dir.join(format!("{name}{e}")).is_file()))
}

fn ready<'a, T: Send + 'a>(r: Result<T, RunnerError>) -> RunnerFuture<'a, T> {
    Box::pin(std::future::ready(r))
}

async fn blocking<T: Send + 'static>(f: impl FnOnce() -> Result<T, RunnerError> + Send + 'static) -> Result<T, RunnerError> {
    tokio::task::spawn_blocking(f).await.map_err(|e| RunnerError::Io(format!("runner task failed: {e}")))?
}

/// Drops the hook token and its events file with the endpoint.
struct HookGuard {
    server: Arc<HookServer>,
    token: String,
    file: PathBuf,
}

impl Drop for HookGuard {
    fn drop(&mut self) {
        self.server.unregister(&self.token);
        let _ = std::fs::remove_file(&self.file);
    }
}

impl Runner for LocalRunner {
    fn id(&self) -> &str {
        "local"
    }

    fn ping(&self) -> RunnerFuture<'_, Pong> {
        let harnesses = self.cfg.harnesses.iter().filter(|h| on_path(h)).cloned().collect();
        ready(Ok(Pong { version: env!("CARGO_PKG_VERSION").into(), os: std::env::consts::OS.into(), harnesses }))
    }

    fn spawn(&self, spec: ProcessSpec) -> RunnerFuture<'_, ProcHandle> {
        Box::pin(async move {
            let hub = Arc::new(Hub::new(self.cfg.backlog_max_bytes));
            let grace = self.cfg.drain_grace;
            let p = blocking(move || {
                // The runner seeds its own disk (a CLI's first-run config) before it starts the process.
                for seed in &spec.seeds {
                    eprintln!("[infantry] runner seed {}: {}", infantry_runner_api::expand_seed_path(&seed.path, &spec.env), if seed.apply(&spec.env) { "written" } else { "unchanged or skipped" });
                }
                spawn::start(&spec, hub, grace).map_err(RunnerError::from)
            })
            .await?;
            let id = {
                let n = self.next.fetch_add(1, Ordering::SeqCst);
                ProcId(match &self.id_tag {
                    Some(t) => format!("p{t}-{n}"),
                    None => format!("p{n}"),
                })
            };
            let pid = p.pid;
            self.procs.lock().unwrap().insert(id.clone(), Arc::new(p));
            Ok(ProcHandle { id, pid: Some(pid) })
        })
    }

    fn write<'a>(&'a self, id: &'a ProcId, data: &'a [u8]) -> RunnerFuture<'a, ()> {
        Box::pin(async move {
            let p = self.proc(id)?;
            if p.hub.exit().is_some() {
                return Err(RunnerError::Exited(id.0.clone()));
            }
            let data = data.to_vec();
            let input = p.input.clone();
            let name = id.0.clone();
            blocking(move || {
                let mut g = input.lock().unwrap();
                let w = g.as_mut().ok_or_else(|| RunnerError::Unsupported(format!("{name}: input closed")))?;
                w.write_all(&data)?;
                w.flush()?;
                Ok(())
            })
            .await
        })
    }

    fn close_input<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, ()> {
        let r = self.proc(id).map(|p| {
            if p.term.is_none() {
                p.input.lock().unwrap().take();
            }
        });
        ready(r)
    }

    fn resize<'a>(&'a self, id: &'a ProcId, size: Size) -> RunnerFuture<'a, ()> {
        let r = (|| {
            let p = self.proc(id)?;
            let (Some(pty), Some(term)) = (p.pty.as_ref(), p.term.as_ref()) else {
                return Err(RunnerError::Unsupported(format!("{id} is not in a pseudo-terminal")));
            };
            if p.hub.exit().is_some() {
                return Err(RunnerError::Exited(id.0.clone()));
            }
            pty.resize(size)?;
            term.lock().unwrap().resize(size);
            Ok(())
        })();
        ready(r)
    }

    fn kill<'a>(&'a self, id: &'a ProcId, scope: KillScope) -> RunnerFuture<'a, ()> {
        ready(self.proc(id).map(|p| LocalRunner::kill_now(&p, scope)))
    }

    fn events<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, mpsc::UnboundedReceiver<ProcEvent>> {
        ready(self.proc(id).map(|p| p.hub.subscribe()))
    }

    fn screen<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, ScreenSnapshot> {
        let n = self.cfg.screen_tail_chars;
        ready(self.term(id).map(|t| t.lock().unwrap().snapshot(n)))
    }

    fn screen_text_since<'a>(&'a self, id: &'a ProcId, mark: u64, max: usize) -> RunnerFuture<'a, String> {
        ready(self.term(id).map(|t| t.lock().unwrap().scroll.text_since(mark, max)))
    }

    fn tail(&self, spec: TailSpec) -> RunnerFuture<'_, mpsc::UnboundedReceiver<String>> {
        let poll = self.cfg.tail_poll;
        Box::pin(async move {
            let path = PathBuf::from(&spec.path);
            let start = match spec.from {
                TailFrom::Offset(o) => o,
                TailFrom::End => std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0),
                _ => 0,
            };
            let (tx, rx) = mpsc::unbounded_channel();
            tokio::spawn(async move {
                let mut lines = AppendedLines::from(start);
                loop {
                    let p = path.clone();
                    let Ok((l, got)) = tokio::task::spawn_blocking(move || {
                        let got = lines.read(&p);
                        (lines, got)
                    })
                    .await
                    else {
                        return;
                    };
                    lines = l;
                    for line in got {
                        if tx.send(line).is_err() {
                            return;
                        }
                    }
                    tokio::select! {
                        _ = tokio::time::sleep(poll) => {}
                        _ = tx.closed() => return,
                    }
                }
            });
            Ok(rx)
        })
    }

    fn hook_channel(&self) -> RunnerFuture<'_, HookEndpoint> {
        Box::pin(async move {
            let server = self.hook_server()?;
            let dir = self.scratch.join("hooks");
            std::fs::create_dir_all(&dir)?;
            let token = uuid::Uuid::new_v4().simple().to_string();
            let file = dir.join(format!("{token}.events"));
            let _ = std::fs::remove_file(&file);
            let (tx, rx) = mpsc::unbounded_channel::<HookCall>();
            let handler: HookHandler = Arc::new(move |body: &str| {
                let (otx, orx) = oneshot::channel();
                if tx.send(HookCall::new(body.to_string(), otx)).is_err() {
                    return "{}".to_string();
                }
                orx.blocking_recv().unwrap_or_else(|_| "{}".to_string())
            });
            server.register(&token, handler);
            let url = server.url(&token);
            let events_file = file.to_string_lossy().into_owned();
            let guard = HookGuard { server, token: token.clone(), file };
            Ok(HookEndpoint::new(token, url, events_file, rx, Box::new(guard)))
        })
    }

    fn release<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, ()> {
        let r = self.procs.lock().unwrap().remove(id).ok_or_else(|| RunnerError::NotFound(id.0.clone()));
        ready(r.map(|p| {
            if p.hub.exit().is_none() {
                LocalRunner::kill_now(&p, KillScope::Tree);
            }
        }))
    }

    fn http(&self, req: HttpRequest) -> RunnerFuture<'_, mpsc::UnboundedReceiver<HttpEvent>> {
        Box::pin(async move { http::start(req) })
    }

    fn stage_file<'a>(&'a self, name: &'a str, contents: &'a [u8]) -> RunnerFuture<'a, String> {
        Box::pin(async move {
            let plain = !name.is_empty()
                && name != "."
                && name != ".."
                && name.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_'));
            if !plain {
                return Err(RunnerError::Unsupported(format!("stage_file: {name:?} is not a plain file name")));
            }
            let dir = self.scratch.join("staged");
            std::fs::create_dir_all(&dir)?;
            let file = dir.join(name);
            if std::fs::read(&file).ok().as_deref() != Some(contents) {
                std::fs::write(&file, contents)?;
            }
            Ok(file.to_string_lossy().into_owned())
        })
    }
}
