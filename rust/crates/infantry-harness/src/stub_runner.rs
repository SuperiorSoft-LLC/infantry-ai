// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! LOCAL STUB of the `Runner` contract owned by RUST-RUN-1 (`infantry-runner-api`). It exists only until that crate lands;
//! then this module becomes `pub use infantry_runner_api::*` and the harness code does not change.
//!
//! A runner starts processes somewhere (this machine, a container, a linked remote) and streams back what they do.
//! Harnesses never spawn: they describe a [`ProcessSpec`], and the [`crate::HarnessDriver`] hands it to a runner.

use std::io;
use std::path::PathBuf;
use std::time::Duration;

/// A terminal size, for a process that runs in a PTY.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PtySize {
    pub cols: u16,
    pub rows: u16,
}

/// What the process gets on stdin.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum StdinMode {
    /// Nothing (closed at once).
    Null,
    /// These bytes, then EOF (a prompt on stdin).
    Bytes(Vec<u8>),
    /// Kept open; the caller writes through [`Process::write`] (an RPC or interactive CLI).
    Pipe,
}

/// One process to start.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProcessSpec {
    /// The program name or path; the runner resolves it on its own PATH.
    pub program: String,
    pub args: Vec<String>,
    /// Extra environment, applied after [`ProcessSpec::env_remove`].
    pub env: Vec<(String, String)>,
    /// Environment names (exact) or prefixes (ending in `*`) removed from the inherited environment.
    pub env_remove: Vec<String>,
    pub cwd: PathBuf,
    pub stdin: StdinMode,
    /// Some = run in a PTY (output arrives as raw [`ProcEvent::Output`]); None = plain pipes (output arrives as lines).
    pub pty: Option<PtySize>,
}

impl ProcessSpec {
    pub fn new(program: impl Into<String>, cwd: PathBuf) -> Self {
        ProcessSpec {
            program: program.into(),
            args: Vec::new(),
            env: Vec::new(),
            env_remove: Vec::new(),
            cwd,
            stdin: StdinMode::Null,
            pty: None,
        }
    }
}

/// What a running process does.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ProcEvent {
    /// The process is up.
    Started { pid: Option<u32> },
    /// One stdout line (pipe mode), without its line ending.
    Stdout(String),
    /// One stderr line (pipe mode), without its line ending.
    Stderr(String),
    /// Raw terminal bytes (PTY mode).
    Output(Vec<u8>),
    /// The process ended; None = killed / no code. Always the last event.
    Exited { code: Option<i32> },
}

/// A started process.
pub trait Process: Send {
    fn pid(&self) -> Option<u32>;
    /// The next event, waiting at most [timeout]; None = nothing yet (or the stream is over after `Exited`).
    fn recv(&mut self, timeout: Duration) -> Option<ProcEvent>;
    /// Writes to stdin (a [`StdinMode::Pipe`] process) or the PTY.
    fn write(&mut self, bytes: &[u8]) -> io::Result<()>;
    /// Kills the process tree; `Exited` follows.
    fn kill(&mut self);
}

/// Starts processes somewhere.
pub trait Runner: Send + Sync {
    /// A short name for logs (`local`, `docker:<name>`, `link:<id>`).
    fn name(&self) -> &str;
    fn spawn(&self, spec: &ProcessSpec) -> io::Result<Box<dyn Process>>;
}
