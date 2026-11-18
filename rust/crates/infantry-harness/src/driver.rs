// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! [`HarnessDriver`]: runs any [`Harness`] on any [`Runner`]. It opens what the harness asks for (a hook channel, file
//! tails), spawns the process through the runner, splits piped output into lines, feeds the translator, delivers its
//! outbound traffic, keeps the resume token between turns and guarantees every started turn ends exactly once.
//!
//! [`Outbound::Http`] goes through [`Runner::http`] (the runner makes the call on its host, where the harness's server
//! listens); the answer is fed back as [`TranslatorInput::HttpResponse`] or, streamed, `HttpOpened` / `HttpLine` /
//! `HttpClosed`. A harness's [`crate::Asset`]s are staged with [`Runner::stage_file`] before every launch.

use crate::runner::{HookCall, HookEndpoint, HttpEvent, KillScope, ProcEvent, ProcId, Runner, TailFrom, TailSpec};
use crate::{
    Harness, HarnessEvent, HookTarget, LaunchError, LaunchRequest, Outbound, Translator, TranslatorInput, TurnDelivery,
    TurnEnd,
};
use std::collections::{HashMap, VecDeque};
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::mpsc::{error::TryRecvError, unbounded_channel, UnboundedReceiver, UnboundedSender};
use tokio::task::JoinHandle;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DriverError {
    /// A turn is already running.
    Busy,
    Launch(LaunchError),
    /// The runner refused (spawn, hook channel, tail).
    Runner(String),
    /// [`HarnessDriver::run_turn`] gave up waiting (the turn was interrupted).
    Timeout,
}

impl std::fmt::Display for DriverError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DriverError::Busy => f.write_str("a turn is already running"),
            DriverError::Launch(e) => write!(f, "launch refused: {e}"),
            DriverError::Runner(e) => write!(f, "runner: {e}"),
            DriverError::Timeout => f.write_str("turn timed out"),
        }
    }
}

/// Splits a byte stream into lines (CR/LF trimmed); a partial last line waits for more.
#[derive(Default)]
struct Lines(Vec<u8>);

impl Lines {
    fn push(&mut self, bytes: &[u8]) -> Vec<String> {
        self.0.extend_from_slice(bytes);
        let mut out = Vec::new();
        while let Some(i) = self.0.iter().position(|b| *b == b'\n') {
            let line: Vec<u8> = self.0.drain(..=i).collect();
            out.push(String::from_utf8_lossy(&line).trim_end_matches(['\r', '\n']).to_string());
        }
        out
    }
    fn rest(&mut self) -> Option<String> {
        let rest = std::mem::take(&mut self.0);
        let s = String::from_utf8_lossy(&rest).trim_end_matches(['\r', '\n']).to_string();
        (!s.is_empty()).then_some(s)
    }
}

struct Live {
    id: ProcId,
    pid: Option<u32>,
    pty: bool,
    started: Instant,
    events: UnboundedReceiver<ProcEvent>,
    translator: Box<dyn Translator>,
    stdout: Lines,
    stderr: Lines,
    tails: Vec<(String, UnboundedReceiver<String>)>,
    /// Every in-flight [`Outbound::Http`] of this process: its events, tagged with the translator's id.
    http_tx: UnboundedSender<(u64, HttpEvent)>,
    http_rx: UnboundedReceiver<(u64, HttpEvent)>,
    http: HashMap<u64, HttpState>,
    http_tasks: Vec<JoinHandle<()>>,
}

/// One in-flight request: streamed (lines) or whole (buffered body).
struct HttpState {
    stream: bool,
    status: Option<u16>,
    body: Vec<u8>,
    lines: Lines,
}

impl Drop for Live {
    fn drop(&mut self) {
        for t in &self.http_tasks {
            t.abort();
        }
    }
}

impl Live {
    /// Turns one response event of request [id] into translator input.
    fn http_input(&mut self, id: u64, ev: HttpEvent, inputs: &mut Vec<TranslatorInput>) {
        let Some(st) = self.http.get_mut(&id) else { return };
        match ev {
            HttpEvent::Head { status, .. } => {
                st.status = Some(status);
                if st.stream {
                    inputs.push(TranslatorInput::HttpOpened { id, status });
                }
            }
            HttpEvent::Body(b) => {
                if st.stream {
                    inputs.extend(st.lines.push(&b).into_iter().map(|line| TranslatorInput::HttpLine { id, line }));
                } else {
                    st.body.extend_from_slice(&b);
                }
            }
            HttpEvent::End => {
                if let Some(mut st) = self.http.remove(&id) {
                    if st.stream {
                        inputs.extend(st.lines.rest().map(|line| TranslatorInput::HttpLine { id, line }));
                        inputs.push(TranslatorInput::HttpClosed { id, error: None });
                    } else {
                        let body = String::from_utf8_lossy(&st.body).into_owned();
                        inputs.push(TranslatorInput::HttpResponse { id, status: st.status, body });
                    }
                }
            }
            HttpEvent::Failed(e) => {
                if let Some(st) = self.http.remove(&id) {
                    if st.stream {
                        inputs.push(TranslatorInput::HttpClosed { id, error: Some(e) });
                    } else {
                        inputs.push(TranslatorInput::HttpResponse { id, status: None, body: e });
                    }
                }
            }
            _ => {}
        }
    }
}

pub struct HarnessDriver {
    harness: Arc<dyn Harness>,
    runner: Arc<dyn Runner>,
    base: LaunchRequest,
    resume: Option<String>,
    live: Option<Live>,
    hooks: Option<HookEndpoint>,
    hook_calls: HashMap<u64, HookCall>,
    hook_seq: u64,
    hook_events: Option<UnboundedReceiver<String>>,
    turn_active: bool,
    interrupting: bool,
    pending: VecDeque<HarnessEvent>,
    exit_grace: Duration,
}

impl HarnessDriver {
    /// [base] is the template for every launch; its `resume` is the conversation to continue (None = new).
    pub fn new(harness: Arc<dyn Harness>, runner: Arc<dyn Runner>, base: LaunchRequest) -> Self {
        let resume = base.resume.clone();
        HarnessDriver {
            harness,
            runner,
            base,
            resume,
            live: None,
            hooks: None,
            hook_calls: HashMap::new(),
            hook_seq: 0,
            hook_events: None,
            turn_active: false,
            interrupting: false,
            pending: VecDeque::new(),
            exit_grace: Duration::from_secs(5),
        }
    }

    /// How long a process-per-turn CLI may take to exit after its turn ended before the next turn kills it (it may
    /// still be saving the conversation it is resumed from).
    pub fn with_exit_grace(mut self, grace: Duration) -> Self {
        self.exit_grace = grace;
        self
    }

    /// The token a later session resumes this conversation with.
    pub fn resume_token(&self) -> Option<&str> {
        self.resume.as_deref()
    }

    pub fn busy(&self) -> bool {
        self.turn_active
    }

    pub fn proc_id(&self) -> Option<&ProcId> {
        self.live.as_ref().map(|l| &l.id)
    }

    /// The live process's OS pid on the runner's host, when the runner knows it.
    pub fn pid(&self) -> Option<u32> {
        self.live.as_ref().and_then(|l| l.pid)
    }

    /// Types [text] into the running turn of a persistent CLI ([`Translator::inject`]). False = no turn, or the CLI
    /// cannot take it now (the host queues it as a turn instead).
    pub async fn inject(&mut self, text: &str) -> bool {
        let taken = self.turn_active && self.live.as_mut().is_some_and(|l| l.translator.inject(text));
        self.deliver_outbound().await;
        taken
    }

    /// Ends the session the CLI's own way ([`Translator::close`]) and waits up to [grace] for it to exit (its events
    /// are returned), then closes as [`HarnessDriver::close`] does.
    pub async fn shutdown(&mut self, grace: Duration, poll_step: Duration) -> Vec<HarnessEvent> {
        let mut out = Vec::new();
        let asked = self.live.as_mut().is_some_and(|l| l.translator.close());
        if asked {
            self.deliver_outbound().await;
            let until = Instant::now() + grace;
            while self.live.is_some() && Instant::now() < until {
                out.extend(self.poll(poll_step).await);
            }
        }
        self.close().await;
        out
    }

    /// Starts one turn; its events arrive through [`HarnessDriver::poll`].
    pub async fn start_turn(&mut self, prompt: &str) -> Result<(), DriverError> {
        if self.turn_active {
            return Err(DriverError::Busy);
        }
        match self.harness.capabilities().delivery {
            TurnDelivery::SpawnPerTurn => {
                let until = Instant::now() + self.exit_grace;
                while self.live.is_some() && Instant::now() < until {
                    let tail = self.poll(Duration::from_millis(50)).await;
                    self.pending.extend(tail);
                }
                self.drop_live().await;
                self.spawn(Some(prompt.to_string())).await?;
            }
            TurnDelivery::Persistent => {
                if self.live.is_none() {
                    self.spawn(None).await?;
                }
                if let Some(live) = self.live.as_mut() {
                    live.translator.send_turn(prompt);
                }
            }
        }
        self.turn_active = true;
        self.interrupting = false;
        self.deliver_outbound().await;
        Ok(())
    }

    async fn spawn(&mut self, prompt: Option<String>) -> Result<(), DriverError> {
        let caps = self.harness.capabilities();
        if caps.hook_channel && self.hooks.is_none() {
            let ep = self.runner.hook_channel().await.map_err(|e| DriverError::Runner(e.to_string()))?;
            let tail = self
                .runner
                .tail(TailSpec { path: ep.events_file.clone(), from: TailFrom::Start })
                .await
                .map_err(|e| DriverError::Runner(e.to_string()))?;
            self.hook_events = Some(tail);
            self.hooks = Some(ep);
        }
        let mut req = self.base.clone();
        req.resume = self.resume.clone();
        req.prompt = prompt;
        req.hooks = self.hooks.as_ref().map(|h| HookTarget { url: h.url.clone(), events_file: h.events_file.clone() });
        for asset in self.harness.assets(&req) {
            match self.runner.stage_file(&asset.name, &asset.contents).await {
                Ok(path) => req.staged.push((asset.name, path)),
                Err(e) => self.pending.push_back(HarnessEvent::Warning(format!("could not stage {}: {e}", asset.name))),
            }
        }
        let plan = self.harness.launch(&req).map_err(DriverError::Launch)?;
        let pty = plan.spec.pty.is_some();
        let program = plan.spec.program.clone();
        let handle = self.runner.spawn(plan.spec).await.map_err(|e| DriverError::Runner(format!("{program}: {e}")))?;
        let events = self.runner.events(&handle.id).await.map_err(|e| DriverError::Runner(e.to_string()))?;
        if let Some(bytes) = plan.stdin {
            if let Err(e) = self.runner.write(&handle.id, &bytes).await {
                self.pending.push_back(HarnessEvent::Warning(format!("stdin write failed: {e}")));
            }
            let _ = self.runner.close_input(&handle.id).await;
        }
        let (http_tx, http_rx) = unbounded_channel();
        let mut tails = Vec::new();
        for spec in plan.tail {
            let path = spec.path.clone();
            match self.runner.tail(spec).await {
                Ok(rx) => tails.push((path, rx)),
                Err(e) => self.pending.push_back(HarnessEvent::Warning(format!("tail {path}: {e}"))),
            }
        }
        self.live = Some(Live {
            id: handle.id,
            pid: handle.pid,
            pty,
            started: Instant::now(),
            events,
            translator: plan.translator,
            stdout: Lines::default(),
            stderr: Lines::default(),
            tails,
            http_tx,
            http_rx,
            http: HashMap::new(),
            http_tasks: Vec::new(),
        });
        Ok(())
    }

    /// Waits up to [timeout] for activity and returns the events it produced (possibly none).
    pub async fn poll(&mut self, timeout: Duration) -> Vec<HarnessEvent> {
        let mut out: Vec<HarnessEvent> = self.pending.drain(..).collect();
        let mut exited = false;
        if let Some(live) = self.live.as_mut() {
            let mut inputs = Vec::new();
            // Side channels first: hook calls, hook events, tails (never block on them).
            if let Some(ep) = self.hooks.as_mut() {
                while let Ok(call) = ep.calls.try_recv() {
                    self.hook_seq += 1;
                    inputs.push(TranslatorInput::Hook { id: self.hook_seq, body: call.body.clone() });
                    self.hook_calls.insert(self.hook_seq, call);
                }
            }
            if let Some(rx) = self.hook_events.as_mut() {
                while let Ok(line) = rx.try_recv() {
                    inputs.push(TranslatorInput::HookEvent(crate::runner::hooks::event_of(&line)));
                }
            }
            for (path, rx) in live.tails.iter_mut() {
                loop {
                    match rx.try_recv() {
                        Ok(line) => inputs.push(TranslatorInput::Tail { path: path.clone(), line }),
                        Err(TryRecvError::Empty) | Err(TryRecvError::Disconnected) => break,
                    }
                }
            }
            while let Ok((id, ev)) = live.http_rx.try_recv() {
                live.http_input(id, ev, &mut inputs);
            }
            // Then process output: wait once (only if nothing else arrived) for the process or an HTTP answer, then drain
            // what is already there.
            let mut first = None;
            if inputs.is_empty() {
                tokio::select! {
                    ev = live.events.recv() => first = ev,
                    Some((id, ev)) = live.http_rx.recv() => {
                        live.http_input(id, ev, &mut inputs);
                        while let Ok((id, ev)) = live.http_rx.try_recv() {
                            live.http_input(id, ev, &mut inputs);
                        }
                    }
                    _ = tokio::time::sleep(timeout) => {}
                }
            }
            if first.is_none() {
                first = live.events.try_recv().ok();
            }
            let mut next = first;
            let deadline = Instant::now() + timeout;
            while let Some(ev) = next.take() {
                match ev {
                    ProcEvent::Output(b) if live.pty => inputs.push(TranslatorInput::Pty(b)),
                    ProcEvent::Output(b) => inputs.extend(live.stdout.push(&b).into_iter().map(TranslatorInput::Stdout)),
                    ProcEvent::Stderr(b) => inputs.extend(live.stderr.push(&b).into_iter().map(TranslatorInput::Stderr)),
                    ProcEvent::Exited(code) => {
                        inputs.extend(live.stdout.rest().map(TranslatorInput::Stdout));
                        inputs.extend(live.stderr.rest().map(TranslatorInput::Stderr));
                        inputs.push(TranslatorInput::Exited { code });
                        exited = true;
                        break;
                    }
                    _ => {}
                }
                if Instant::now() >= deadline {
                    break;
                }
                next = live.events.try_recv().ok();
            }
            if live.pty && !exited {
                if let Ok(snap) = self.runner.screen(&live.id).await {
                    inputs.push(TranslatorInput::Screen { rows: snap.rows, now: live.started.elapsed().as_millis() as u64 });
                }
            }
            for i in inputs {
                out.extend(live.translator.feed(i));
            }
        }
        self.deliver_outbound().await;
        if exited {
            self.drop_live().await;
            if self.turn_active && !out.iter().any(|e| matches!(e, HarnessEvent::TurnEnded { .. })) {
                out.push(HarnessEvent::TurnEnded {
                    end: TurnEnd::Failed,
                    reason: Some("process exited before the turn ended".into()),
                });
            }
        }
        out.extend(self.pending.drain(..));
        self.account(out)
    }

    /// Bookkeeping over a batch: the resume token, the turn state, interrupts reported as such.
    fn account(&mut self, mut out: Vec<HarnessEvent>) -> Vec<HarnessEvent> {
        for e in out.iter_mut() {
            match e {
                HarnessEvent::ProviderSession(id) => self.resume = Some(id.clone()),
                HarnessEvent::TurnEnded { end, .. } => {
                    if self.interrupting && *end == TurnEnd::Failed {
                        *end = TurnEnd::Interrupted;
                    }
                    self.turn_active = false;
                    self.interrupting = false;
                }
                _ => {}
            }
        }
        out
    }

    async fn deliver_outbound(&mut self) {
        let Some(live) = self.live.as_mut() else { return };
        for o in live.translator.take_outbound() {
            match o {
                Outbound::Stdin(bytes) => {
                    if let Err(e) = self.runner.write(&live.id, &bytes).await {
                        self.pending.push_back(HarnessEvent::Warning(format!("stdin write failed: {e}")));
                    }
                }
                Outbound::HookReply { id, body } => match self.hook_calls.remove(&id) {
                    Some(call) => call.reply(body),
                    None => self.pending.push_back(HarnessEvent::Warning(format!("hook reply {id}: no such call"))),
                },
                Outbound::Http { id, request, stream } => {
                    live.http.insert(id, HttpState { stream, status: None, body: Vec::new(), lines: Lines::default() });
                    let what = format!("{} {}", request.method, request.url);
                    match self.runner.http(request).await {
                        Ok(mut rx) => {
                            let tx = live.http_tx.clone();
                            live.http_tasks.retain(|t| !t.is_finished());
                            live.http_tasks.push(tokio::spawn(async move {
                                while let Some(ev) = rx.recv().await {
                                    if tx.send((id, ev)).is_err() {
                                        break;
                                    }
                                }
                            }));
                        }
                        Err(e) => {
                            let _ = live.http_tx.send((id, HttpEvent::Failed(format!("{what}: {e}"))));
                        }
                    }
                }
            }
        }
    }

    /// The host's answer to [`HarnessEvent::ApprovalRequested`] [id].
    pub async fn answer(&mut self, id: &str, allow: bool) {
        if let Some(live) = self.live.as_mut() {
            live.translator.answer(id, allow);
        }
        self.deliver_outbound().await;
    }

    /// Stops the running turn: the CLI's own interrupt when it has one, else the process tree is killed. False = no turn.
    pub async fn interrupt(&mut self) -> bool {
        if !self.turn_active {
            return false;
        }
        self.interrupting = true;
        if let Some(live) = self.live.as_mut() {
            if !live.translator.interrupt() {
                let _ = self.runner.kill(&live.id, KillScope::Tree).await;
            }
        }
        self.deliver_outbound().await;
        true
    }

    async fn drop_live(&mut self) {
        if let Some(live) = self.live.take() {
            let _ = self.runner.release(&live.id).await; // kills (tree) a process still running
        }
        self.hook_calls.clear();
    }

    /// Ends the session: its process (killed) and hook channel.
    pub async fn close(&mut self) {
        self.drop_live().await;
        self.hooks = None;
        self.hook_events = None;
        self.turn_active = false;
    }

    /// Runs one turn to its end, handing every event to [on_event]. After [timeout] the turn is interrupted and
    /// [`DriverError::Timeout`] returned.
    pub async fn run_turn(
        &mut self,
        prompt: &str,
        timeout: Duration,
        poll_step: Duration,
        mut on_event: impl FnMut(&HarnessEvent),
    ) -> Result<TurnEnd, DriverError> {
        self.start_turn(prompt).await?;
        let deadline = Instant::now() + timeout;
        loop {
            for e in self.poll(poll_step).await {
                on_event(&e);
                if let HarnessEvent::TurnEnded { end, .. } = e {
                    return Ok(end);
                }
            }
            if Instant::now() >= deadline {
                self.interrupt().await;
                for e in self.poll(poll_step).await {
                    on_event(&e);
                }
                self.close().await;
                return Err(DriverError::Timeout);
            }
        }
    }
}
