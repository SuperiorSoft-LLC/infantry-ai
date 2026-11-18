// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The vendor-neutral harness contract. A [`Harness`] turns a [`LaunchRequest`] into a [`LaunchSpec`]: the command to run in a
//! PTY and the [`Driver`] that runs it (a state machine stepped by [`crate::session::Session`] every tick, reading the screen,
//! its own tailed files and hook calls, typing keys, and emitting [`Event`]s). Nothing here names a vendor.

use crate::hooks::HookServer;
use crate::screen::Screen;
use crate::stream::StreamEvent;
use std::path::PathBuf;
use std::sync::mpsc::Sender;
use std::sync::Arc;

/// What a session may do without asking.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ToolApproval {
    Deny,
    Ask,
    AllowEdits,
    AllowAll,
}

/// One launch of a harness session.
#[derive(Debug, Clone)]
pub struct LaunchRequest {
    /// The project folder the CLI runs in.
    pub cwd: PathBuf,
    pub model: Option<String>,
    pub effort: Option<String>,
    pub approval: ToolApproval,
    /// A provider session id to continue; None starts a new conversation.
    pub resume: Option<String>,
    /// The id for a new conversation (None = the harness picks one).
    pub session_id: Option<String>,
    /// Extra environment for the child.
    pub env: Vec<(String, String)>,
    /// Environment names (exact) or prefixes (ending in `*`) removed from the inherited environment before [env] is applied.
    pub env_remove: Vec<String>,
}

impl LaunchRequest {
    pub fn new(cwd: PathBuf) -> Self {
        LaunchRequest {
            cwd,
            model: None,
            effort: None,
            approval: ToolApproval::AllowAll,
            resume: None,
            session_id: None,
            env: Vec::new(),
            env_remove: Vec::new(),
        }
    }
}

/// One question put to the owner (a harness's ask tool, through the hook channel).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Question {
    pub text: String,
    pub options: Vec<String>,
    pub recommended: Option<String>,
    pub multi_select: bool,
}

/// The owner's answer; empty (nothing picked, no own text) = not answered.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Answer {
    pub picked: Vec<String>,
    pub other: Option<String>,
}

impl Answer {
    pub fn answered(&self) -> bool {
        !self.picked.is_empty() || self.other.as_deref().is_some_and(|o| !o.trim().is_empty())
    }
}

/// The host side of the questions and approvals a session raises (the daemon's prompt card; a test fakes it). Blocking.
pub trait Owner: Send + Sync {
    fn ask(&self, session: &str, q: &Question) -> Answer;
    /// Whether the tool call [tool] with [input] (JSON) may run.
    fn approve(&self, session: &str, tool: &str, input: &str) -> bool;
}

/// Nobody answers: every question unanswered, every approval denied.
pub struct NoOwner;

impl Owner for NoOwner {
    fn ask(&self, _: &str, _: &Question) -> Answer {
        Answer::default()
    }
    fn approve(&self, _: &str, _: &str, _: &str) -> bool {
        false
    }
}

/// What a harness gets from the runner at launch.
#[derive(Clone)]
pub struct Host {
    /// The runner's state folder (per-session files go under it).
    pub state_dir: PathBuf,
    pub hooks: Arc<HookServer>,
    pub owner: Arc<dyn Owner>,
}

/// The command a harness runs, and the driver that runs it.
pub struct LaunchSpec {
    pub program: String,
    pub args: Vec<String>,
    pub env: Vec<(String, String)>,
    pub cwd: PathBuf,
    /// The provider's session id (known before the CLI writes anything).
    pub session_id: String,
    pub driver: Box<dyn Driver>,
}

pub trait Harness: Send + Sync {
    fn name(&self) -> &str;
    fn launch(&self, req: &LaunchRequest, host: &Host) -> std::io::Result<LaunchSpec>;
}

/// How a turn ended.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TurnEnd {
    Completed,
    Interrupted,
    Failed,
}

/// What a session reports.
#[derive(Debug, Clone, PartialEq)]
pub enum Event {
    /// The provider's session id (to resume later).
    ProviderSession(String),
    /// One stream-json record (as headless mode would print it) and its structured reading.
    Output { line: String, records: Vec<StreamEvent> },
    /// A turn began that nobody sent (the CLI's own, e.g. a background task ended).
    TurnStarted { reason: Option<String> },
    TurnEnded { end: TurnEnd, reason: Option<String> },
    /// A human-readable note for the log.
    Detail(String),
    /// The first byte the terminal printed, [ms] after spawn.
    FirstOutput { ms: u64 },
    Exited { code: i32 },
}

/// What a driver touches in one step: the clock, the screen, the terminal input, the event stream.
pub struct Io<'a> {
    /// Milliseconds since the session was spawned.
    pub now: u64,
    pub screen: &'a Screen,
    pub(crate) input: &'a mut Vec<u8>,
    pub(crate) events: &'a Sender<Event>,
}

impl<'a> Io<'a> {
    pub fn new(now: u64, screen: &'a Screen, input: &'a mut Vec<u8>, events: &'a Sender<Event>) -> Self {
        Io { now, screen, input, events }
    }

    /// Types [s] into the terminal (sent after the step).
    pub fn write(&mut self, s: &str) {
        self.input.extend_from_slice(s.as_bytes());
    }

    pub fn emit(&self, e: Event) {
        let _ = self.events.send(e);
    }
}

/// A harness session's state machine. Every method runs under the session lock; none may block.
pub trait Driver: Send {
    /// One step (every [`crate::session::TICK_MS`]).
    fn tick(&mut self, io: &mut Io<'_>);
    /// Queues one turn; it is typed once the CLI is ready and the turn before it ended.
    fn send_turn(&mut self, prompt: String, io: &mut Io<'_>);
    /// Interrupts the running turn (its end is reported as Interrupted). False = nothing to interrupt.
    fn abort_turn(&mut self, io: &mut Io<'_>) -> bool;
    /// Starts a clean exit (the CLI's own quit); the session kills the process if it does not exit in time.
    fn close(&mut self, io: &mut Io<'_>);
    fn on_exit(&mut self, code: i32, io: &mut Io<'_>);
    /// A turn is queued or running.
    fn busy(&self) -> bool;
}
