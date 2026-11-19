// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `process_agent --port N [...]`: the runner agent with this machine's LocalRunner behind the link, so a RemoteRunner
//! elsewhere spawns processes here. Same flags as `infantry-runner-agent`.

use infantry_runner_link::process::ProcessBackend;
use std::sync::Arc;

fn main() {
    let local = Arc::new(infantry_runner_local::LocalRunner::default());
    infantry_runner_link::agent::run("process_agent", std::env::args().skip(1), Box::new(ProcessBackend::new(local)))
}
