// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The generic harness contract (layer L3). A [`Harness`] describes how one agent CLI is run; it never starts a process.
//!
//! - [`Harness::launch`] turns a [`LaunchRequest`] into a [`LaunchPlan`]: the [`ProcessSpec`] to run and the
//!   [`Translator`] that reads it.
//! - A [`Translator`] is fed what the process does ([`TranslatorInput`]: process output, hook calls, tailed file lines,
//!   screen snapshots) and returns neutral [`HarnessEvent`]s. It may queue [`Outbound`] traffic (keys for a PTY, RPC
//!   lines for stdin) that the driver delivers.
//! - [`HarnessDriver`] runs ANY harness on ANY [`Runner`].
//!
//! The five CLIs this shape is built for ([`TurnDelivery`] says how a turn reaches each):
//!
//! | CLI | Process | Turn delivery | Output |
//! |---|---|---|---|
//! | Claude (interactive) | one PTY for the session | keys typed into the PTY | screen + hook channel + transcript tail |
//! | Codex | one per turn | prompt on stdin, `resume <thread>` | stdout JSONL |
//! | OpenCode | `serve`, one for the session | HTTP request | SSE stream |
//! | Pi | one for the session | RPC JSON line on stdin | stdout JSON lines |
//! | Gemini | one per turn | prompt argument / stdin | stdout stream-json |
//!
//! Nothing here knows orchestration (boards, tickets): an agent runs with no orchestrator.

pub mod driver;
pub mod pipe_runner;
pub mod stub_runner;

pub use driver::{DriverError, HarnessDriver};
pub use stub_runner::{ProcEvent, Process, ProcessSpec, PtySize, Runner, StdinMode};

use std::path::{Path, PathBuf};

/// What a session may do without asking. Each harness maps it onto its own flags.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ApprovalMode {
    /// Read only; every write or command refused.
    Deny,
    /// Ask the owner for each risky action (a harness that cannot ask falls back to [`ApprovalMode::Deny`]).
    Ask,
    /// Edits inside the workspace allowed; anything else asks.
    AllowEdits,
    /// Everything allowed (externally sandboxed).
    AllowAll,
}

/// How a turn reaches the CLI.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TurnDelivery {
    /// A new process per turn; the prompt is part of the [`LaunchRequest`]; resume via [`LaunchRequest::resume`].
    SpawnPerTurn,
    /// One process for the session; a turn is [`Translator::send_turn`] traffic to its stdin or PTY.
    Persistent,
}

/// What a harness needs from the host and what it can report.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Capabilities {
    pub delivery: TurnDelivery,
    /// Runs in a PTY and reads the screen ([`TranslatorInput::Screen`]).
    pub pty_screen: bool,
    /// Reads files the CLI writes ([`LaunchPlan::tail`], [`TranslatorInput::Tail`]).
    pub file_tail: bool,
    /// Receives the CLI's hook calls ([`TranslatorInput::Hook`]).
    pub hook_channel: bool,
    /// Talks HTTP to its own server process ([`Outbound::Http`]).
    pub http: bool,
    /// Can continue a conversation from a [`HarnessEvent::ProviderSession`] token.
    pub resume: bool,
    /// Reports token usage ([`HarnessEvent::Usage`]).
    pub usage: bool,
    /// Accepts [`LaunchRequest::model`].
    pub model_selection: bool,
    /// The approval modes it honours as asked (others are mapped to the nearest safer one).
    pub approval_modes: Vec<ApprovalMode>,
}

/// One model a harness offers.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ModelInfo {
    pub id: String,
    pub label: String,
}

/// Read-only access to the machine the CLI runs on (local or remote), so a harness can check sign-in and list models
/// without spawning anything.
pub trait Probe {
    fn env(&self, name: &str) -> Option<String>;
    fn home(&self) -> Option<PathBuf>;
    fn read_to_string(&self, path: &Path) -> Option<String>;
    fn exists(&self, path: &Path) -> bool;
}

/// [`Probe`] over this process's own environment and file system.
pub struct LocalProbe;

impl Probe for LocalProbe {
    fn env(&self, name: &str) -> Option<String> {
        std::env::var(name).ok().filter(|v| !v.is_empty())
    }
    fn home(&self) -> Option<PathBuf> {
        self.env("USERPROFILE").or_else(|| self.env("HOME")).map(PathBuf::from)
    }
    fn read_to_string(&self, path: &Path) -> Option<String> {
        std::fs::read_to_string(path).ok()
    }
    fn exists(&self, path: &Path) -> bool {
        path.exists()
    }
}

/// One launch of a harness.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LaunchRequest {
    /// The folder the CLI works in.
    pub cwd: PathBuf,
    pub model: Option<String>,
    pub effort: Option<String>,
    pub approval: ApprovalMode,
    /// A [`HarnessEvent::ProviderSession`] token to continue; None = a new conversation.
    pub resume: Option<String>,
    /// The turn's prompt ([`TurnDelivery::SpawnPerTurn`]); None for a persistent launch.
    pub prompt: Option<String>,
    /// Extra environment for the child.
    pub env: Vec<(String, String)>,
    /// Environment names or prefixes (ending in `*`) removed from the inherited environment.
    pub env_remove: Vec<String>,
}

impl LaunchRequest {
    pub fn new(cwd: PathBuf) -> Self {
        LaunchRequest {
            cwd,
            model: None,
            effort: None,
            approval: ApprovalMode::Deny,
            resume: None,
            prompt: None,
            env: Vec::new(),
            env_remove: Vec::new(),
        }
    }
}

/// Why a harness cannot launch.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LaunchError(pub String);

impl std::fmt::Display for LaunchError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

/// What to run and how to read it.
pub struct LaunchPlan {
    pub spec: ProcessSpec,
    pub translator: Box<dyn Translator>,
    /// Files the host tails for the translator ([`Capabilities::file_tail`]).
    pub tail: Vec<PathBuf>,
}

/// One call on the hook channel (a CLI's hook script reporting to the host).
#[derive(Debug, Clone, PartialEq)]
pub struct HookCall {
    pub event: String,
    pub payload: serde_json::Value,
}

/// One new line in a tailed file.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TailLine {
    pub path: PathBuf,
    pub line: String,
}

/// What a translator is fed.
#[derive(Debug, Clone, PartialEq)]
pub enum TranslatorInput {
    Proc(ProcEvent),
    Hook(HookCall),
    Tail(TailLine),
    /// The current screen text of a PTY session, [now] ms after spawn.
    Screen { text: String, now: u64 },
}

/// Traffic a translator asks the driver to deliver.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outbound {
    /// Bytes to the process's stdin or PTY.
    Stdin(Vec<u8>),
    /// A request to the harness's own server ([`Capabilities::http`]).
    Http { method: String, path: String, body: String },
}

/// Token usage of one turn.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Usage {
    pub input_tokens: u64,
    pub cached_input_tokens: u64,
    pub output_tokens: u64,
    pub reasoning_tokens: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TurnEnd {
    Completed,
    Interrupted,
    Failed,
}

/// What a harness reports, in neutral terms.
#[derive(Debug, Clone, PartialEq)]
pub enum HarnessEvent {
    /// The resume token (a later turn passes it as [`LaunchRequest::resume`]).
    ProviderSession(String),
    TurnStarted,
    /// One assistant message (whole).
    Text(String),
    /// A piece of an assistant message still being written.
    TextDelta(String),
    Reasoning(String),
    /// A tool call began; `input` is the tool's input as text (a command line, JSON).
    ToolStarted { id: String, name: String, input: String },
    ToolFinished { id: String, output: String, exit_code: Option<i32>, ok: bool },
    /// The CLI wants permission (answered by the host through the translator's channel).
    ApprovalRequested { id: String, tool: String, input: String },
    Usage(Usage),
    /// A non-fatal problem the CLI reported.
    Warning(String),
    /// A fatal problem of the turn (a [`HarnessEvent::TurnEnded`] with `Failed` follows).
    Error(String),
    /// One raw stdout line (for the transcript).
    Stdout(String),
    /// One stderr line.
    Stderr(String),
    TurnEnded { end: TurnEnd, reason: Option<String> },
    /// The process ended.
    Exited { code: Option<i32> },
}

/// Reads one launched process.
pub trait Translator: Send {
    fn feed(&mut self, input: TranslatorInput) -> Vec<HarnessEvent>;
    /// Queues one turn for a persistent process ([`TurnDelivery::Persistent`]); delivered via [`Translator::take_outbound`].
    fn send_turn(&mut self, _prompt: &str) {}
    /// Asks the CLI to stop its turn. False = it has no way; the driver kills the process.
    fn interrupt(&mut self) -> bool {
        false
    }
    /// Drains the traffic to deliver.
    fn take_outbound(&mut self) -> Vec<Outbound> {
        Vec::new()
    }
}

/// One agent CLI.
pub trait Harness: Send + Sync {
    /// The provider id on the wire (`codex`, `claude`, ...).
    fn id(&self) -> &str;
    fn capabilities(&self) -> Capabilities;
    /// The models it offers on the machine [probe] looks at; empty = only the CLI's default.
    fn models(&self, probe: &dyn Probe) -> Vec<ModelInfo>;
    /// Why the CLI cannot run there (not installed, not signed in); None = fine as far as files and env tell.
    fn sign_in_problem(&self, probe: &dyn Probe) -> Option<String>;
    fn launch(&self, req: &LaunchRequest) -> Result<LaunchPlan, LaunchError>;
}
