// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! ORPHAN-1: a runner process no orchestrator asks after is ended after the grace period; one that is asked after lives on.

use infantry_runner_api::ProcessSpec;
use infantry_runner_link::process::ProcessBackend;
use infantry_runner_link::server::Backend;
use infantry_runner_link::wire::{FramesPullRequest, StartRequest};
use std::collections::BTreeMap;
use std::sync::Arc;
use std::time::Duration;

fn long_sleep() -> ProcessSpec {
    if cfg!(windows) {
        ProcessSpec::new("ping", ["-n", "120", "127.0.0.1"])
    } else {
        ProcessSpec::new("sleep", ["120"])
    }
}

#[test]
fn a_process_nobody_asks_after_is_reaped_and_an_asked_after_one_is_not() {
    let backend = ProcessBackend::new(Arc::new(infantry_runner_local::LocalRunner::default())).with_orphan_reaper(Arc::new(|| 1));
    let start = |b: &ProcessBackend| b.start(&StartRequest { start_id: "s".into(), process: Some(long_sleep()), ..Default::default() }).session_id.expect("started");
    let (owned, orphan) = (start(&backend), start(&backend));
    // The orchestrator keeps pulling the frames of `owned` only.
    let deadline = std::time::Instant::now() + Duration::from_secs(12);
    while backend.is_linked(&orphan) && std::time::Instant::now() < deadline {
        let req = FramesPullRequest { resume_from: BTreeMap::from([(owned.clone(), 0)]), ..Default::default() };
        let _ = backend.frames_pull(&req, Duration::from_millis(300), 10);
    }
    assert!(!backend.is_linked(&orphan), "the orphan was not reaped");
    assert!(backend.is_linked(&owned), "the owned process was reaped");
}
