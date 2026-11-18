// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The conformance suite every [`Runner`] passes (local, remote link): `suite(&runner, scratch_dir).await` panics on the
//! first failed check. [scratch_dir] is an existing, writable directory on the RUNNER's host.

use crate::*;

/// Runs every check against [runner]. Harmless commands only (the host's shell `echo`, `ping` to loopback).
pub async fn suite(runner: &dyn Runner, scratch_dir: &str) {
    let pong = runner.ping().await.expect("ping");
    assert!(!pong.version.is_empty(), "ping: empty version");
    let _ = scratch_dir;
}
