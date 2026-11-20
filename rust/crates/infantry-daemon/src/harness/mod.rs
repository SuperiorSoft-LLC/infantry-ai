// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The provider seam (PLAN O2). Daemon core drives every agent CLI through [`Harness`] and [`HarnessSession`] and reads
//! back neutral [`HarnessEvent`]s; it names no vendor. A harness says what host mechanisms it uses ([`Capabilities`]:
//! `pty-screen`, `file-tail`, `hook-channel`) and whether it keeps one process across turns.
//!
//! Implementations: [`stub`] (scripted, no process; `INFANTRY_PROVIDER=stub`) and [`driven`], which runs any
//! `infantry-harness` crate (one agent CLI each) on the local runner. The registry is [`by_name`].

pub mod driven;
pub mod launch;
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

/// Where a session's tool approvals and questions go: the owner's prompt cards. Each call returns at once; [done] runs
/// (on any thread) once the owner, the timer or the session's end resolved it.
pub trait Prompter: Send + Sync {
    /// The approval mode a session runs with (its scoped setting, else the env, else the default).
    fn mode(&self, session: &str, project: Option<&str>, role: Option<&str>) -> infantry_harness::ApprovalMode;
    fn approve(&self, session: &str, tool: &str, input: &str, done: Box<dyn FnOnce(bool) + Send>);
    fn ask(&self, session: &str, questions: Vec<infantry_harness::HarnessQuestion>, done: Box<dyn FnOnce(Vec<infantry_harness::HarnessAnswer>) + Send>);
    /// The session ended: its open cards close.
    fn end(&self, _session: &str) {}
}

/// Opens one daemon session's harness side.
#[derive(Clone)]
pub struct OpenRequest {
    /// The daemon session id.
    pub session_id: String,
    pub cwd: PathBuf,
    pub model: Option<String>,
    pub effort: Option<String>,
    /// The daemon's state dir (per-session files go under it).
    pub state_dir: PathBuf,
    /// The session env the agent process gets ([`launch::LaunchBase::session`]).
    pub env: Vec<(String, String)>,
    /// The MCP servers the agent gets (the infantry entry when the CLI is found).
    pub mcp: infantry_harness::McpPlan,
    /// What the session may do without asking.
    pub approval: infantry_harness::ApprovalMode,
    /// Where its approvals and questions go; None = nobody can answer (an ask is denied).
    pub prompter: Option<std::sync::Arc<dyn Prompter>>,
}

impl std::fmt::Debug for OpenRequest {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("OpenRequest").field("session_id", &self.session_id).field("cwd", &self.cwd).field("approval", &self.approval).finish_non_exhaustive()
    }
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
    /// A tool call began (its name): counted in the session's stats.
    Tool(String),
    /// A tool call changed a file: the wire `FileWrite` JSON (path, content / edits / patch), drawn as a change box.
    Write(String),
    /// Token usage of one model request, as the CLI reported it: the session's stats.
    Usage(TokenUsage),
    /// The CLI's view of the session's own MCP server and of every server it reports (G12).
    McpState { state: String, servers: Vec<(String, String)> },
}

/// Hands one message to the running turn; true = the CLI took it. Called with no session lock held (it waits briefly).
pub type Injector = Box<dyn FnOnce(String) -> bool + Send>;
/// How long an inject waits for the CLI's answer.
pub const INJECT_WAIT_MS: u64 = 3_000;

/// One model request's token counts as the harness reported them (never estimated).
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct TokenUsage {
    pub input: u64,
    pub cached: u64,
    pub output: u64,
    pub reasoning: u64,
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
    /// The provider's `/` commands (`command_catalog`).
    fn commands(&self) -> Vec<infantry_harness::HarnessCommand> {
        Vec::new()
    }
    /// The session as the CLI's own local record tells it (`session_stats`); None = no record to read.
    fn store_stats(&self, _provider_session_id: &str) -> Option<Vec<infantry_harness::stats::StatEvent>> {
        None
    }
    /// The context window of `model` when this CLI's local catalog states it.
    fn context_window(&self, _model: &str) -> Option<u64> {
        None
    }
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
    /// Mid-turn input (a queued message forced into the running turn); None = this session cannot take it.
    fn injector(&self) -> Option<Injector> {
        None
    }
    /// Reconnects the session's own MCP server inside the CLI; false = it cannot (no process, or no such command).
    fn reconnect_mcp(&mut self) -> bool {
        false
    }
}

/// The provider a daemon runs when none is named.
pub const DEFAULT_PROVIDER: &str = infantry_harness_claude::ID;

/// The agent-CLI harness (the neutral `infantry-harness` trait: models, sign-in, launch) for a provider id.
pub fn neutral(name: &str) -> Option<std::sync::Arc<dyn infantry_harness::Harness>> {
    Some(match name {
        infantry_harness_claude::ID => std::sync::Arc::new(infantry_harness_claude::ClaudeHarness::default()),
        infantry_harness_codex::ID => std::sync::Arc::new(infantry_harness_codex::CodexHarness::default()),
        infantry_harness_opencode::ID => std::sync::Arc::new(infantry_harness_opencode::OpenCodeHarness::default()),
        infantry_harness_pi::ID => std::sync::Arc::new(infantry_harness_pi::PiHarness::default()),
        // HARNESS REGISTRATION SPOT: a new provider crate adds one arm here and its id to
        // [`REGISTERED`]; nothing else in the daemon names a provider.
        _ => return None,
    })
}

/// The harness for a provider name, or None when this daemon has no such harness.
pub fn by_name(name: &str) -> Option<Box<dyn Harness>> {
    if name == stub::NAME {
        return Some(Box::new(stub::Stub::default()));
    }
    neutral(name).map(|h| Box::new(driven::Driven::new(h)) as Box<dyn Harness>)
}

/// Every provider this build can run, in the order the harness list shows them (the stub is a test harness, not listed).
pub const REGISTERED: &[&str] = &[infantry_harness_claude::ID, infantry_harness_codex::ID, infantry_harness_opencode::ID, infantry_harness_pi::ID];

/// Every registered harness except `skip` (the daemon's default, already built).
pub fn all_except(skip: &str) -> Vec<std::sync::Arc<dyn Harness>> {
    REGISTERED.iter().filter(|n| **n != skip).filter_map(|n| by_name(n)).map(std::sync::Arc::from).collect()
}
