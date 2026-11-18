// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Any `infantry-harness` [`Harness`](NeutralHarness) behind the daemon's seam, on the local runner. Each daemon session
//! gets one thread with its own small runtime that owns the session's [`HarnessDriver`]: the daemon's calls become
//! commands to it, the driver's events become daemon [`HarnessEvent`]s. Names no vendor.

use super::{Capabilities, EventSink, Harness, HarnessEvent, HarnessSession, OpenRequest, RunStart, TokenUsage, Turn, TurnEnd};
use infantry_harness::{
    ApprovalMode, Harness as NeutralHarness, HarnessDriver, HarnessEvent as Ev, LaunchRequest, Runner, TurnDelivery,
    TurnEnd as End,
};
use infantry_runner_local::{LocalConfig, LocalRunner};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc as std_mpsc, Arc, OnceLock};
use std::time::{Duration, Instant};
use tokio::sync::mpsc;

/// Inherited variables the agent CLI must not see: the daemon's own identity.
const ENV_REMOVE: [&str; 2] = ["INFANTRY_*", "AGENTHQ_*"];
/// How often a live session's driver is stepped when nothing arrives (screen reads, timers).
pub const POLL_STEP: Duration = Duration::from_millis(100);
/// How long a new turn waits for an interrupted one to finish stopping.
pub const STOP_WAIT: Duration = Duration::from_secs(10);
/// How long a graceful close lets the CLI exit its own way before its tree is killed.
pub const CLOSE_GRACE: Duration = Duration::from_secs(5);

pub struct Driven {
    harness: Arc<dyn NeutralHarness>,
    runner: OnceLock<Arc<LocalRunner>>,
}

impl Driven {
    pub fn new(harness: Arc<dyn NeutralHarness>) -> Self {
        Driven { harness, runner: OnceLock::new() }
    }
}

impl Harness for Driven {
    fn name(&self) -> &str {
        self.harness.id()
    }

    fn capabilities(&self) -> Capabilities {
        let c = self.harness.capabilities();
        Capabilities {
            pty_screen: c.pty_screen,
            file_tail: c.file_tail,
            hook_channel: c.hook_channel,
            persistent_process: c.delivery == TurnDelivery::Persistent,
        }
    }

    fn open(&self, req: OpenRequest, sink: EventSink) -> Result<Box<dyn HarnessSession>, String> {
        let runner = self
            .runner
            .get_or_init(|| {
                Arc::new(LocalRunner::new(LocalConfig { scratch_dir: Some(req.state_dir.join("runner")), ..Default::default() }))
            })
            .clone();
        let (tx, rx) = mpsc::unbounded_channel();
        let state = Arc::new(State::default());
        let worker = Worker { harness: self.harness.clone(), runner, req, sink, state: state.clone(), discard_end: false };
        std::thread::Builder::new()
            .name(format!("infantry-harness-{}", self.harness.id()))
            .spawn(move || worker.run(rx))
            .map_err(|e| format!("could not start the harness thread: {e}"))?;
        Ok(Box::new(DrivenSession { tx, state }))
    }
}

#[derive(Default)]
struct State {
    alive: AtomicBool,
    turn: AtomicBool,
}

enum Cmd {
    Turn(Turn, std_mpsc::Sender<Result<RunStart, String>>),
    Interrupt,
    Close { force: bool },
}

struct DrivenSession {
    tx: mpsc::UnboundedSender<Cmd>,
    state: Arc<State>,
}

impl HarnessSession for DrivenSession {
    fn send_turn(&mut self, turn: Turn) -> Result<RunStart, String> {
        let (reply, answer) = std_mpsc::channel();
        self.tx.send(Cmd::Turn(turn, reply)).map_err(|_| "the harness session has ended".to_string())?;
        answer.recv().map_err(|_| "the harness session ended before the turn started".to_string())?
    }

    fn interrupt(&mut self) -> bool {
        let running = self.state.turn.load(Ordering::SeqCst);
        let _ = self.tx.send(Cmd::Interrupt);
        running
    }

    fn close(&mut self, force: bool) {
        let _ = self.tx.send(Cmd::Close { force });
    }

    fn process_alive(&self) -> bool {
        self.state.alive.load(Ordering::SeqCst)
    }
}

// Dropping the session drops its command channel: the worker closes the driver (killing the tree) and ends.

struct Worker {
    harness: Arc<dyn NeutralHarness>,
    runner: Arc<LocalRunner>,
    req: OpenRequest,
    sink: EventSink,
    state: Arc<State>,
    /// The end of a turn the daemon already ended (an interrupt) is not passed on.
    discard_end: bool,
}

impl Worker {
    fn run(mut self, mut rx: mpsc::UnboundedReceiver<Cmd>) {
        let rt = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
            Ok(rt) => rt,
            Err(e) => {
                self.sink.emit(HarnessEvent::Stderr(format!("[harness] no runtime: {e}")));
                return;
            }
        };
        rt.block_on(async move {
            let mut driver: Option<HarnessDriver> = None;
            loop {
                let live = driver.as_ref().is_some_and(|d| d.proc_id().is_some());
                let cmd = if live {
                    match rx.try_recv() {
                        Ok(c) => Some(c),
                        Err(mpsc::error::TryRecvError::Empty) => None,
                        Err(mpsc::error::TryRecvError::Disconnected) => break,
                    }
                } else {
                    match rx.recv().await {
                        Some(c) => Some(c),
                        None => break,
                    }
                };
                match cmd {
                    Some(Cmd::Turn(turn, reply)) => {
                        let d = driver.get_or_insert_with(|| self.driver(turn.resume.clone()));
                        let _ = reply.send(self.start(d, &turn.prompt).await);
                    }
                    Some(Cmd::Interrupt) => {
                        if let Some(d) = driver.as_mut() {
                            if d.interrupt().await {
                                self.discard_end = true;
                            }
                        }
                        self.state.turn.store(false, Ordering::SeqCst);
                    }
                    Some(Cmd::Close { force }) => {
                        if let Some(mut d) = driver.take() {
                            if force {
                                d.close().await;
                            } else {
                                let evs = d.shutdown(CLOSE_GRACE, POLL_STEP).await;
                                self.forward(&mut d, evs).await;
                            }
                        }
                        break;
                    }
                    None => {
                        if let Some(d) = driver.as_mut() {
                            let evs = d.poll(POLL_STEP).await;
                            self.forward(d, evs).await;
                        }
                    }
                }
            }
            if let Some(mut d) = driver.take() {
                d.close().await;
            }
            self.state.alive.store(false, Ordering::SeqCst);
            self.state.turn.store(false, Ordering::SeqCst);
        });
    }

    fn driver(&self, resume: Option<String>) -> HarnessDriver {
        let mut base = LaunchRequest::new(self.req.cwd.clone());
        base.model = self.req.model.clone();
        base.effort = self.req.effort.clone();
        // Questions and approvals are not routed to a prompt card yet: sessions run with approval = allow all.
        base.approval = ApprovalMode::AllowAll;
        base.resume = resume;
        base.env_remove = ENV_REMOVE.map(String::from).to_vec();
        base.env = self.req.env.clone();
        base.mcp = self.req.mcp.clone();
        let runner: Arc<dyn Runner> = self.runner.clone();
        HarnessDriver::new(self.harness.clone(), runner, base)
    }

    async fn start(&mut self, d: &mut HarnessDriver, prompt: &str) -> Result<RunStart, String> {
        let until = Instant::now() + STOP_WAIT;
        while d.busy() && Instant::now() < until {
            let evs = d.poll(POLL_STEP).await;
            self.forward(d, evs).await;
        }
        if d.busy() {
            return Err(format!("the previous turn did not stop within {} s", STOP_WAIT.as_secs()));
        }
        self.discard_end = false;
        d.start_turn(prompt).await.map_err(|e| format!("could not start {}: {e}", self.harness.id()))?;
        self.state.turn.store(true, Ordering::SeqCst);
        self.state.alive.store(d.proc_id().is_some(), Ordering::SeqCst);
        // One step now so a session id the CLI is started with is known before any output.
        let evs = d.poll(Duration::ZERO).await;
        let start = RunStart { pid: d.pid(), provider_session_id: d.resume_token().map(str::to_string) };
        self.forward(d, evs).await;
        Ok(start)
    }

    async fn forward(&mut self, d: &mut HarnessDriver, evs: Vec<Ev>) {
        for e in evs {
            match e {
                Ev::ProviderSession(id) => self.sink.emit(HarnessEvent::ProviderSession(id)),
                Ev::Text(t) | Ev::TextDelta(t) => self.sink.emit(HarnessEvent::Text(t)),
                Ev::Stdout(l) => self.sink.emit(HarnessEvent::Stdout(l)),
                Ev::Stderr(l) => self.sink.emit(HarnessEvent::Stderr(l)),
                Ev::Warning(w) => self.sink.emit(HarnessEvent::Stderr(format!("[harness] {w}"))),
                Ev::Error(err) => self.sink.emit(HarnessEvent::Stderr(format!("[harness] error: {err}"))),
                Ev::ApprovalRequested { id, tool, .. } => {
                    self.sink.emit(HarnessEvent::Stderr(format!("[harness] {tool} needs an approval this daemon cannot ask yet: denied")));
                    d.answer(&id, false).await;
                }
                Ev::TurnEnded { end, reason } => {
                    self.state.turn.store(false, Ordering::SeqCst);
                    if std::mem::take(&mut self.discard_end) {
                        continue;
                    }
                    let end = if end == End::Completed { TurnEnd::Completed } else { TurnEnd::Failed };
                    let alive = d.proc_id().is_some();
                    self.state.alive.store(alive, Ordering::SeqCst);
                    self.sink.emit(HarnessEvent::TurnEnded {
                        end,
                        exit_code: (end == TurnEnd::Completed).then_some(0),
                        reason,
                        process_alive: alive,
                    });
                }
                Ev::Exited { code } => {
                    self.state.alive.store(false, Ordering::SeqCst);
                    self.sink.emit(HarnessEvent::Exited { code });
                }
                Ev::ToolStarted { name, .. } => self.sink.emit(HarnessEvent::Tool(name)),
                Ev::Usage(u) => self.sink.emit(HarnessEvent::Usage(TokenUsage {
                    input: u.input_tokens,
                    cached: u.cached_input_tokens,
                    output: u.output_tokens,
                    reasoning: u.reasoning_tokens,
                })),
                Ev::TurnStarted | Ev::Reasoning(_) | Ev::ToolFinished { .. } => {}
            }
        }
    }
}
