// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Any `infantry-harness` [`Harness`](NeutralHarness) behind the daemon's seam, on the runner the session is placed on. Each daemon session
//! gets one thread with its own small runtime that owns the session's [`HarnessDriver`]: the daemon's calls become
//! commands to it, the driver's events become daemon [`HarnessEvent`]s. Names no vendor.

use super::{Capabilities, EventSink, Harness, HarnessEvent, HarnessSession, OpenRequest, RunStart, TokenUsage, Turn, TurnEnd};
use infantry_harness::{
    ApprovalMode, Harness as NeutralHarness, HarnessAnswer, HarnessDriver, HarnessEvent as Ev, LaunchRequest, Runner, TurnDelivery,
    TurnEnd as End,
};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc as std_mpsc, Arc};
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
}

impl Driven {
    pub fn new(harness: Arc<dyn NeutralHarness>) -> Self {
        Driven { harness }
    }
}

impl Harness for Driven {
    fn name(&self) -> &str {
        self.harness.id()
    }

    fn commands(&self) -> Vec<infantry_harness::HarnessCommand> {
        self.harness.commands()
    }

    // The local runner's files: the CLI's record lives on this machine.
    fn store_stats(&self, provider_session_id: &str) -> Option<Vec<infantry_harness::stats::StatEvent>> {
        self.harness.store_stats(&infantry_harness::LocalProbe, provider_session_id)
    }

    fn context_window(&self, model: &str) -> Option<u64> {
        self.harness.context_window(&infantry_harness::LocalProbe, model)
    }

    fn store_stats_via(&self, probe: &dyn infantry_harness::Probe, provider_session_id: &str) -> Option<Vec<infantry_harness::stats::StatEvent>> {
        self.harness.store_stats(probe, provider_session_id)
    }

    fn context_window_via(&self, probe: &dyn infantry_harness::Probe, model: &str) -> Option<u64> {
        self.harness.context_window(probe, model)
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
        // The runner the session manager resolved for this session (local or remote): the driver runs on it and nothing else.
        let runner = req.runner.clone();
        let (tx, rx) = mpsc::unbounded_channel();
        let state = Arc::new(State::default());
        let worker = Worker { harness: self.harness.clone(), runner, req, sink, state: state.clone(), discard_end: false, back: tx.downgrade() };
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
    /// The owner's answer to an approval the CLI asked for.
    Approve { id: String, allow: bool },
    /// The owner's answers to a CLI's questions.
    Answer { id: String, answers: Vec<HarnessAnswer> },
    /// A message for the running turn; the answer says whether the CLI took it.
    Inject(String, std_mpsc::Sender<bool>),
    ReconnectMcp,
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

    fn injector(&self) -> Option<super::Injector> {
        if !self.state.turn.load(Ordering::SeqCst) {
            return None;
        }
        let tx = self.tx.clone();
        Some(Box::new(move |text: String| {
            let (reply, answer) = std_mpsc::channel();
            tx.send(Cmd::Inject(text, reply)).is_ok() && answer.recv_timeout(Duration::from_millis(super::INJECT_WAIT_MS)).unwrap_or(false)
        }))
    }

    fn reconnect_mcp(&mut self) -> bool {
        self.state.alive.load(Ordering::SeqCst) && self.tx.send(Cmd::ReconnectMcp).is_ok()
    }
}

// Dropping the session drops its command channel: the worker closes the driver (killing the tree) and ends.

struct Worker {
    harness: Arc<dyn NeutralHarness>,
    runner: Arc<dyn Runner>,
    req: OpenRequest,
    sink: EventSink,
    state: Arc<State>,
    /// The end of a turn the daemon already ended (an interrupt) is not passed on.
    discard_end: bool,
    /// This worker's own command channel (weak: the session's drop still ends the worker): an owner's answer comes back on it.
    back: mpsc::WeakUnboundedSender<Cmd>,
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
                    Some(Cmd::Approve { id, allow }) => {
                        if let Some(d) = driver.as_mut() {
                            d.answer(&id, allow).await;
                        }
                    }
                    Some(Cmd::Answer { id, answers }) => {
                        if let Some(d) = driver.as_mut() {
                            d.answer_questions(&id, answers).await;
                        }
                    }
                    Some(Cmd::Inject(text, reply)) => {
                        let taken = match driver.as_mut() {
                            Some(d) => d.inject(&text).await,
                            None => false,
                        };
                        let _ = reply.send(taken);
                    }
                    Some(Cmd::ReconnectMcp) => {
                        if let Some(d) = driver.as_mut() {
                            if !d.reconnect_mcp().await {
                                self.sink.emit(HarnessEvent::Stderr(format!("[harness] {} cannot reconnect its MCP server", self.harness.id())));
                            }
                        }
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
        // With no prompter nobody can answer an ask: such a session runs as it always did (allow all).
        base.approval = if self.req.prompter.is_some() { self.req.approval } else { ApprovalMode::AllowAll };
        base.resume = resume;
        base.env_remove = ENV_REMOVE.map(String::from).to_vec();
        base.env = self.req.env.clone();
        base.mcp = self.req.mcp.clone();
        let runner = self.runner.clone();
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
        if let Some(p) = d.proc_id() {
            self.sink.emit(HarnessEvent::Placed(p.to_string()));
        }
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
                Ev::ApprovalRequested { id, tool, input } => match self.req.prompter.clone() {
                    Some(p) => {
                        let back = self.back.clone();
                        p.approve(&self.req.session_id, &tool, &input, Box::new(move |allow| {
                            if let Some(b) = back.upgrade() {
                                let _ = b.send(Cmd::Approve { id, allow });
                            }
                        }));
                    }
                    None => {
                        self.sink.emit(HarnessEvent::Stderr(format!("[harness] {tool} needs an approval nobody can answer here: denied")));
                        d.answer(&id, false).await;
                    }
                },
                Ev::QuestionAsked { id, questions } => match self.req.prompter.clone() {
                    Some(p) => {
                        let back = self.back.clone();
                        p.ask(&self.req.session_id, questions, Box::new(move |answers| {
                            if let Some(b) = back.upgrade() {
                                let _ = b.send(Cmd::Answer { id, answers });
                            }
                        }));
                    }
                    None => {
                        let n = questions.len();
                        d.answer_questions(&id, vec![HarnessAnswer::default(); n]).await;
                    }
                },
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
                Ev::ToolStarted { name, input, .. } => {
                    // Codex reports `file_change` as a summary only: its diff is read from the folder (changes.rs).
                    let writes = if name == "file_change" {
                        super::changes::summary_writes(&self.req.cwd, &input, infantry_harness::writes::WRITE_MAX_BYTES)
                    } else {
                        infantry_harness::writes::file_writes(&name, &input, infantry_harness::writes::WRITE_MAX_BYTES)
                    };
                    for w in writes {
                        self.sink.emit(HarnessEvent::Write(w.to_string()));
                    }
                    self.sink.emit(HarnessEvent::Tool(name));
                }
                Ev::Usage(u) => self.sink.emit(HarnessEvent::Usage(TokenUsage {
                    input: u.input_tokens,
                    cached: u.cached_input_tokens,
                    output: u.output_tokens,
                    reasoning: u.reasoning_tokens,
                })),
                Ev::McpState { state, servers } => self.sink.emit(HarnessEvent::McpState { state, servers }),
                Ev::Background(c) => self.sink.emit(HarnessEvent::Background(c)),
                Ev::TurnStarted | Ev::Reasoning(_) | Ev::ToolFinished { .. } | Ev::TaskNotice { .. } => {}
            }
        }
    }
}
