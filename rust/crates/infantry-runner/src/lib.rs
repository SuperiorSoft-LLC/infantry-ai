// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The Infantry AI process runner (Rust port, RUST-SESS-1 spike of plan unit P0.3).
//!
//! Generic mechanisms only (owner decision O2: `pty-screen`, `file-tail`, `hook-channel`):
//! - [`pty`]: a child in a pseudo-terminal (ConPTY on Windows) with its output pumped into a [`screen::Screen`];
//! - [`screen`]: the vt100 screen state plus a plain-text scrollback for marker matching;
//! - [`tail`]: the lines appended to a file (a transcript, an events file);
//! - [`hooks`]: the hook channel: a command-hook events file and a loopback HTTP endpoint for blocking hook calls;
//! - [`job`]: Windows Job Objects (children die with the runner) and kill by pid;
//! - [`harness`]: the vendor-neutral [`harness::Harness`] / [`harness::Driver`] traits and the [`harness::Event`] stream;
//! - [`session`]: one running harness session (the PTY, its driver, the tick loop);
//! - [`stream`]: stream-json records as structured events.
//!
//! [`claude`] is ONE harness implementation; nothing outside it names a vendor.

pub mod claude;
pub mod harness;
pub mod hooks;
pub mod job;
pub mod pty;
pub mod screen;
pub mod session;
pub mod stream;
pub mod tail;
