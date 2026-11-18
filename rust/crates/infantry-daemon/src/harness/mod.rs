// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The provider seam (PLAN O2). Daemon core drives every agent CLI through [`Harness`] and [`HarnessSession`] and reads
//! back neutral [`HarnessEvent`]s; it names no vendor. A harness says what host mechanisms it uses ([`Capabilities`]:
//! `pty-screen`, `file-tail`, `hook-channel`) and whether it keeps one process across turns.
//!
//! Implementations: [`stub`] (scripted, no process; `INFANTRY_PROVIDER=stub`) and [`driven`], which runs any
//! `infantry-harness` crate (one agent CLI each) on the local runner. The registry is [`by_name`].

pub mod driven;
pub mod stub;

use std::path::PathBuf;
use tokio::sync::mpsc::UnboundedSender;

/// The generic host mechanisms a harness asks for (PLAN O2).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Capabilities {
    pub pty_screen: bool,
    pub file_tail: bool,
    pub hook_channel: bool,
    /// One process lives across turns (an interactive CLI); false = a process per turn (`-p` style, the stub).
    pub persistent_process: bool,
}

/// Opens one daemon session's harness side.
#[derive(Debug, Clone)]
pub struct OpenRequest {
    /// The daemon session id.
    pub session_id: String,
    pub cwd: PathBuf,
    pub model: Option<String>,
    pub effort: Option<String>,
    /// The daemon's state dir (per-session files go under it).
    pub state_dir: PathBuf,
}

/// One turn.
#[derive(Debug, Clone)]
pub struct Turn {
    pub prompt: String,
    /// The provider session to continue (None = a new conversation).
    pub resume: Option<String>,
}

/// What the harness knows once a turn's process is up.
#[derive(Debug, Clone, Default)]
pub struct RunStart {
    /// The OS process; None = there is none (the stub, an HTTP provider).
    pub pid: Option<u32>,
    /// The provider session id when it is known before any output.
    pub provider_session_id: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TurnEnd {
    Completed,
    Failed,
}

/// What a harness reports, in neutral terms. Events for a turn the daemon already ended (an interrupt, a kill) are dropped.
#[derive(Debug, Clone, PartialEq)]
pub enum HarnessEvent {
    /// One raw provider stdout line (sent on as a `stdout` frame).
    Stdout(String),
    /// One provider stderr / diagnostic line.
    Stderr(String),
    /// Assistant text (a `text_delta` frame).
    Text(String),
    /// The provider session id (what a later turn resumes).
    ProviderSession(String),
    /// The turn ended. `process_alive` = the harness keeps its process for the next turn.
    TurnEnded { end: TurnEnd, exit_code: Option<i32>, reason: Option<String>, process_alive: bool },
    /// The process went away outside a turn end (a crash, the CLI's own exit).
    Exited { code: Option<i32> },
}

/// Where a harness sends its events (one per daemon session, delivered in order).
#[derive(Clone)]
pub struct EventSink(pub UnboundedSender<HarnessEvent>);

impl EventSink {
    pub fn emit(&self, e: HarnessEvent) {
        let _ = self.0.send(e);
    }
}

pub trait Harness: Send + Sync {
    /// The provider name on the wire (`ready provider=..`, `SessionRecord.provider`, status `tool`).
    fn name(&self) -> &str;
    fn capabilities(&self) -> Capabilities;
    fn open(&self, req: OpenRequest, sink: EventSink) -> Result<Box<dyn HarnessSession>, String>;
}

/// One daemon session's harness side. Methods never block for long; output arrives through the [`EventSink`].
pub trait HarnessSession: Send {
    /// Starts one turn (and the process, when it is not running).
    fn send_turn(&mut self, turn: Turn) -> Result<RunStart, String>;
    /// Stops the running turn; the session keeps its conversation. False = nothing was running.
    fn interrupt(&mut self) -> bool;
    /// Ends the session's process: `force` kills the tree now, else the CLI's own exit first.
    fn close(&mut self, force: bool);
    /// The session's process is alive.
    fn process_alive(&self) -> bool;
}

/// The provider a daemon runs when none is named.
pub const DEFAULT_PROVIDER: &str = infantry_harness_claude::ID;

/// The harness for a provider name, or None when this daemon has no such harness.
pub fn by_name(name: &str) -> Option<Box<dyn Harness>> {
    match name {
        stub::NAME => Some(Box::new(stub::Stub::default())),
        infantry_harness_claude::ID => {
            Some(Box::new(driven::Driven::new(std::sync::Arc::new(infantry_harness_claude::ClaudeHarness::default()))))
        }
        _ => None,
    }
}
