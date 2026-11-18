// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! [`HarnessDriver`]: runs any [`Harness`] on any [`Runner`]. It owns the process, feeds the translator, delivers its
//! outbound traffic, keeps the resume token between turns and guarantees every started turn ends exactly once.
//!
//! Not yet driven here (the harness side is ready for them): file tails ([`crate::LaunchPlan::tail`]), hook calls and
//! screen snapshots come from the runner's PTY/hook seams once RUST-RUN-1 exposes them; [`Outbound::Http`] needs an HTTP
//! client on the runner side. A translator that needs them gets a [`HarnessEvent::Warning`] instead of silence.

use crate::{
    Harness, HarnessEvent, LaunchError, LaunchRequest, Outbound, ProcEvent, Process, Runner, Translator, TranslatorInput,
    TurnDelivery, TurnEnd,
};
use std::collections::VecDeque;
use std::sync::Arc;
use std::time::{Duration, Instant};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DriverError {
    /// A turn is already running.
    Busy,
    Launch(LaunchError),
    Spawn(String),
    /// [`HarnessDriver::run_turn`] gave up waiting (the turn was interrupted).
    Timeout,
}

impl std::fmt::Display for DriverError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            DriverError::Busy => f.write_str("a turn is already running"),
            DriverError::Launch(e) => write!(f, "launch refused: {e}"),
            DriverError::Spawn(e) => write!(f, "spawn failed: {e}"),
            DriverError::Timeout => f.write_str("turn timed out"),
        }
    }
}

struct Live {
    proc: Box<dyn Process>,
    translator: Box<dyn Translator>,
}

pub struct HarnessDriver {
    harness: Arc<dyn Harness>,
    runner: Arc<dyn Runner>,
    base: LaunchRequest,
    resume: Option<String>,
    live: Option<Live>,
    turn_active: bool,
    interrupting: bool,
    pending: VecDeque<HarnessEvent>,
}

impl HarnessDriver {
    /// [base] is the template for every launch; its `resume` is the conversation to continue (None = new).
    pub fn new(harness: Arc<dyn Harness>, runner: Arc<dyn Runner>, base: LaunchRequest) -> Self {
        let resume = base.resume.clone();
        HarnessDriver { harness, runner, base, resume, live: None, turn_active: false, interrupting: false, pending: VecDeque::new() }
    }

    /// The token a later session resumes this conversation with.
    pub fn resume_token(&self) -> Option<&str> {
        self.resume.as_deref()
    }

    pub fn busy(&self) -> bool {
        self.turn_active
    }

    pub fn pid(&self) -> Option<u32> {
        self.live.as_ref().and_then(|l| l.proc.pid())
    }

    /// Starts one turn; its events arrive through [`HarnessDriver::poll`].
    pub fn start_turn(&mut self, prompt: &str) -> Result<(), DriverError> {
        if self.turn_active {
            return Err(DriverError::Busy);
        }
        match self.harness.capabilities().delivery {
            TurnDelivery::SpawnPerTurn => {
                self.live = None;
                self.spawn(Some(prompt.to_string()))?;
            }
            TurnDelivery::Persistent => {
                if self.live.is_none() {
                    self.spawn(None)?;
                }
                let live = self.live.as_mut().expect("spawned above");
                live.translator.send_turn(prompt);
            }
        }
        self.turn_active = true;
        self.interrupting = false;
        self.deliver_outbound();
        Ok(())
    }

    fn spawn(&mut self, prompt: Option<String>) -> Result<(), DriverError> {
        let mut req = self.base.clone();
        req.resume = self.resume.clone();
        req.prompt = prompt;
        let plan = self.harness.launch(&req).map_err(DriverError::Launch)?;
        if !plan.tail.is_empty() {
            self.pending.push_back(HarnessEvent::Warning(format!(
                "{}: file tail requested but this driver does not tail files yet",
                self.harness.id()
            )));
        }
        let proc = self.runner.spawn(&plan.spec).map_err(|e| DriverError::Spawn(format!("{}: {e}", plan.spec.program)))?;
        self.live = Some(Live { proc, translator: plan.translator });
        Ok(())
    }

    /// Waits up to [timeout] for process activity and returns the events it produced (possibly none).
    pub fn poll(&mut self, timeout: Duration) -> Vec<HarnessEvent> {
        let mut out: Vec<HarnessEvent> = self.pending.drain(..).collect();
        let Some(live) = self.live.as_mut() else { return self.account(out) };
        // Wait once, then drain what is already there, but never past [timeout] (a chatty process must not starve the caller).
        let started = Instant::now();
        let mut wait = timeout;
        let mut exited = false;
        while let Some(ev) = live.proc.recv(wait) {
            wait = Duration::ZERO;
            exited = matches!(ev, ProcEvent::Exited { .. });
            out.extend(live.translator.feed(TranslatorInput::Proc(ev)));
            if exited || started.elapsed() >= timeout {
                break;
            }
        }
        self.deliver_outbound();
        if exited {
            self.live = None;
            if self.turn_active && !out.iter().any(|e| matches!(e, HarnessEvent::TurnEnded { .. })) {
                let code = out.iter().rev().find_map(|e| match e {
                    HarnessEvent::Exited { code } => Some(*code),
                    _ => None,
                });
                out.push(HarnessEvent::TurnEnded {
                    end: TurnEnd::Failed,
                    reason: Some(format!("process exited (code {:?}) before the turn ended", code.flatten())),
                });
            }
        }
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

    fn deliver_outbound(&mut self) {
        let Some(live) = self.live.as_mut() else { return };
        for o in live.translator.take_outbound() {
            match o {
                Outbound::Stdin(bytes) => {
                    if let Err(e) = live.proc.write(&bytes) {
                        self.pending.push_back(HarnessEvent::Warning(format!("stdin write failed: {e}")));
                    }
                }
                Outbound::Http { method, path, .. } => self.pending.push_back(HarnessEvent::Warning(format!(
                    "{method} {path}: HTTP delivery is not supported by this driver yet"
                ))),
            }
        }
    }

    /// Stops the running turn: the CLI's own interrupt when it has one, else the process is killed. False = no turn.
    pub fn interrupt(&mut self) -> bool {
        if !self.turn_active {
            return false;
        }
        self.interrupting = true;
        if let Some(live) = self.live.as_mut() {
            if !live.translator.interrupt() {
                live.proc.kill();
            }
        }
        self.deliver_outbound();
        true
    }

    /// Ends the session's process (killed).
    pub fn close(&mut self) {
        if let Some(mut live) = self.live.take() {
            live.proc.kill();
        }
        self.turn_active = false;
    }

    /// Runs one turn to its end, handing every event to [on_event]. After [timeout] the turn is interrupted and
    /// [`DriverError::Timeout`] returned.
    pub fn run_turn(
        &mut self,
        prompt: &str,
        timeout: Duration,
        mut on_event: impl FnMut(&HarnessEvent),
    ) -> Result<TurnEnd, DriverError> {
        self.start_turn(prompt)?;
        let deadline = Instant::now() + timeout;
        let step = Duration::from_millis(100);
        loop {
            for e in self.poll(step) {
                on_event(&e);
                if let HarnessEvent::TurnEnded { end, .. } = e {
                    return Ok(end);
                }
            }
            if Instant::now() >= deadline {
                self.interrupt();
                for e in self.poll(step) {
                    on_event(&e);
                }
                self.close();
                return Err(DriverError::Timeout);
            }
        }
    }
}

impl Drop for HarnessDriver {
    fn drop(&mut self) {
        self.close();
    }
}
