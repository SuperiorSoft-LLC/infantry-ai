// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The location-transparent runner interface (RUST-RUN-1).
//!
//! A harness needs ONLY the [`Runner`] trait, so it runs the same on this machine ([`infantry-runner-local`]) or on another
//! one (`infantry-runner-link`). Everything that touches the OS is a runner capability, never harness code:
//! - processes: [`Runner::spawn`] piped or in a pseudo-terminal, [`Runner::write`], [`Runner::resize`], [`Runner::kill`],
//!   the [`ProcEvent`] stream ([`Runner::events`]);
//! - `pty-screen`: what a terminal program shows now ([`Runner::screen`], [`Runner::screen_text_since`]);
//! - `file-tail`: the lines appended to a file on the runner's host ([`Runner::tail`]);
//! - `hook-channel`: how a CLI's hooks reach the harness ([`Runner::hook_channel`]);
//! - `loopback-http`: requests to a server process the harness started there ([`Runner::http`], e.g. `opencode serve`);
//! - `staged-files`: files a CLI loads by path (a plugin, an extension) put on the runner's host ([`Runner::stage_file`]).
//!
//! Every path in this API is a path on the RUNNER's host. The trait is object-safe (`Arc<dyn Runner>`): methods return
//! boxed futures, so no `async-trait` macro is needed.
//!
//! **Frozen contract**: after its first integration this trait only grows additively (new methods with a default body,
//! new fields with `#[serde(default)]`, new variants of the `#[non_exhaustive]` enums).

pub mod hooks;
pub mod http;
#[cfg(feature = "test-support")]
pub mod suite;

pub use hooks::{HookCall, HookEndpoint};
pub use http::{HttpEvent, HttpRequest};

use serde::{Deserialize, Serialize};
use std::future::Future;
use std::pin::Pin;
use tokio::sync::mpsc;

/// The future every [`Runner`] method returns.
pub type RunnerFuture<'a, T> = Pin<Box<dyn Future<Output = Result<T, RunnerError>> + Send + 'a>>;

/// A process's runner-wide id (opaque; unique for the life of the runner).
#[derive(Debug, Clone, PartialEq, Eq, Hash, PartialOrd, Ord, Serialize, Deserialize)]
#[serde(transparent)]
pub struct ProcId(pub String);

impl std::fmt::Display for ProcId {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

/// A terminal size in character cells.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct Size {
    pub rows: u16,
    pub cols: u16,
}

impl Size {
    /// Wide, so an interactive CLI rarely wraps its own status lines (the Kotlin PtyProcessRunner default).
    pub const DEFAULT: Size = Size { rows: 50, cols: 200 };
}

/// The CPU priority a process (and everything it starts) runs at. Kotlin `BuildPriority`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
#[non_exhaustive]
pub enum Priority {
    #[default]
    Normal,
    BelowNormal,
    Idle,
}

/// What to run.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProcessSpec {
    /// The program: a path, or a name found on the runner host's PATH.
    pub program: String,
    pub args: Vec<String>,
    /// Set after [`ProcessSpec::env_remove`] is applied to the runner's inherited environment.
    #[serde(default)]
    pub env: Vec<(String, String)>,
    /// Exact names, or prefixes ending in `*`, removed from the inherited environment (case-insensitive).
    #[serde(default)]
    pub env_remove: Vec<String>,
    /// The working directory; `None` = the runner's own.
    #[serde(default)]
    pub cwd: Option<String>,
    /// `Some` = run in a pseudo-terminal of this size (output is the raw terminal stream; [`Runner::screen`] works);
    /// `None` = piped stdin/stdout/stderr.
    #[serde(default)]
    pub pty: Option<Size>,
    #[serde(default)]
    pub priority: Priority,
}

impl ProcessSpec {
    pub fn new(program: impl Into<String>, args: impl IntoIterator<Item = impl Into<String>>) -> Self {
        ProcessSpec { program: program.into(), args: args.into_iter().map(Into::into).collect(), ..Default::default() }
    }

    pub fn pty(mut self, size: Size) -> Self {
        self.pty = Some(size);
        self
    }

    pub fn cwd(mut self, dir: impl Into<String>) -> Self {
        self.cwd = Some(dir.into());
        self
    }

    pub fn env(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.env.push((key.into(), value.into()));
        self
    }
}

/// A started process.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ProcHandle {
    pub id: ProcId,
    /// The OS pid on the runner's host, when it has one.
    #[serde(default)]
    pub pid: Option<u32>,
}

/// What a process does, in order. [`ProcEvent::Exited`] is always the last event and comes once the output is drained.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
#[non_exhaustive]
pub enum ProcEvent {
    /// Stdout bytes (piped), or the raw terminal stream (pty; a terminal has no stderr).
    Output(Vec<u8>),
    /// Stderr bytes (piped only).
    Stderr(Vec<u8>),
    /// The exit code (a killed process reports what the OS gives, usually 1 on Windows, 128+signal elsewhere).
    Exited(i32),
}

/// How much [`Runner::kill`] kills.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
#[non_exhaustive]
pub enum KillScope {
    /// The process and every descendant (a Job Object on Windows, the process group elsewhere).
    Tree,
    /// Only the direct child.
    Process,
}

/// Who the runner is.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Pong {
    /// The runner build's version.
    pub version: String,
    /// `windows`, `linux`, `macos` (Rust's `std::env::consts::OS`).
    pub os: String,
    /// The harness CLIs found on the runner host (the names it was asked to look for).
    #[serde(default)]
    pub harnesses: Vec<String>,
}

/// A pseudo-terminal's screen now.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ScreenSnapshot {
    pub size: Size,
    /// The visible rows as text (trailing blanks trimmed), top to bottom.
    pub rows: Vec<String>,
    /// (row, col), zero-based.
    pub cursor: (u16, u16),
    /// Characters ever appended to the plain-text scrollback: a mark for [`Runner::screen_text_since`].
    pub total: u64,
    /// The last bytes ever written to the terminal, at most this many characters of plain text (escapes stripped).
    pub tail: String,
}

/// Where [`Runner::tail`] starts.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
#[non_exhaustive]
pub enum TailFrom {
    /// The whole file.
    Start,
    /// Only what is appended after the call (what is there now is history).
    End,
    /// From this byte offset.
    Offset(u64),
}

/// A file to follow on the runner's host.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TailSpec {
    pub path: String,
    pub from: TailFrom,
}

/// Why a runner call failed.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "message", rename_all = "snake_case")]
#[non_exhaustive]
pub enum RunnerError {
    /// No process with this id (never spawned, or released).
    NotFound(String),
    /// The call makes no sense for this process or runner (a screen of a piped process, resize without a pty).
    Unsupported(String),
    /// The process already exited.
    Exited(String),
    /// An OS error on the runner's host.
    Io(String),
    /// The runner is unreachable (a remote link dropped).
    Disconnected(String),
}

impl std::fmt::Display for RunnerError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            RunnerError::NotFound(m) => write!(f, "no such process: {m}"),
            RunnerError::Unsupported(m) => write!(f, "unsupported: {m}"),
            RunnerError::Exited(m) => write!(f, "process exited: {m}"),
            RunnerError::Io(m) => write!(f, "io: {m}"),
            RunnerError::Disconnected(m) => write!(f, "runner disconnected: {m}"),
        }
    }
}

impl std::error::Error for RunnerError {}

impl From<std::io::Error> for RunnerError {
    fn from(e: std::io::Error) -> Self {
        RunnerError::Io(e.to_string())
    }
}

/// The runner seam. Local or remote, a harness cannot tell which.
pub trait Runner: Send + Sync {
    /// A short stable name: `local`, or the remote runner's id.
    fn id(&self) -> &str;

    /// Who the runner is; a remote runner's liveness check (ping/pong first).
    fn ping(&self) -> RunnerFuture<'_, Pong>;

    /// Starts a process. Its events are buffered until the first [`Runner::events`] call, so none is lost.
    fn spawn(&self, spec: ProcessSpec) -> RunnerFuture<'_, ProcHandle>;

    /// Writes to the process's stdin (piped) or types into its terminal (pty).
    fn write<'a>(&'a self, id: &'a ProcId, data: &'a [u8]) -> RunnerFuture<'a, ()>;

    /// Closes a piped process's stdin (a CLI that reads to EOF needs it). A pty's input stays open: this does nothing.
    fn close_input<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, ()>;

    /// Resizes a pty process's terminal (the program sees the new size). [`RunnerError::Unsupported`] for a piped process.
    fn resize<'a>(&'a self, id: &'a ProcId, size: Size) -> RunnerFuture<'a, ()>;

    /// Kills the process (and with [`KillScope::Tree`] every descendant). Killing an exited process is not an error.
    fn kill<'a>(&'a self, id: &'a ProcId, scope: KillScope) -> RunnerFuture<'a, ()>;

    /// The process's events. The first subscriber gets everything since spawn; a later one from now on; every subscriber
    /// gets [`ProcEvent::Exited`] (a subscriber after the exit gets it at once), then the channel closes.
    fn events<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, mpsc::UnboundedReceiver<ProcEvent>>;

    /// A pty process's screen now (also after it exited, until released).
    fn screen<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, ScreenSnapshot>;

    /// The plain text a pty process drew after scrollback mark [mark] ([`ScreenSnapshot::total`]), at most [max] characters
    /// (the newest kept). "What was drawn after X": an answered dialog is history.
    fn screen_text_since<'a>(&'a self, id: &'a ProcId, mark: u64, max: usize) -> RunnerFuture<'a, String>;

    /// Follows a file on the runner's host: each whole line appended (CR trimmed, blank lines dropped). A missing file is
    /// waited for; a file that shrank is read again from its start. Dropping the receiver stops the tail.
    fn tail(&self, spec: TailSpec) -> RunnerFuture<'_, mpsc::UnboundedReceiver<String>>;

    /// A fresh hook channel: a loopback URL a blocking hook POSTs to and an events file a command hook appends to, both on
    /// the runner's host. Dropping the endpoint closes it.
    fn hook_channel(&self) -> RunnerFuture<'_, HookEndpoint>;

    /// Forgets an exited process (its screen and buffered state). A running one is killed (tree) first.
    fn release<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, ()>;

    /// `loopback-http`: one request to a server on the runner host's loopback (a process the harness started there), made
    /// by the runner on its host. The response streams as [`HttpEvent`]s (a server-sent events stream stays open until the
    /// server closes it or the receiver is dropped). Non-loopback URLs are refused. Default: unsupported.
    fn http(&self, req: HttpRequest) -> RunnerFuture<'_, mpsc::UnboundedReceiver<HttpEvent>> {
        let _ = req;
        Box::pin(async { Err(RunnerError::Unsupported("loopback-http".into())) })
    }

    /// `staged-files`: puts [contents] in a file called [name] (a plain file name, no folders) in the runner's own staging
    /// folder on its host, rewritten only when it differs, and returns its absolute path there. For files a CLI loads by
    /// path (a plugin, an extension). Default: unsupported.
    fn stage_file<'a>(&'a self, name: &'a str, contents: &'a [u8]) -> RunnerFuture<'a, String> {
        let _ = (name, contents);
        Box::pin(async { Err(RunnerError::Unsupported("staged-files".into())) })
    }
}
