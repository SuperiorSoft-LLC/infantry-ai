// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The shared Runner conformance suite against the local runner.

use infantry_runner_api::Runner;
use infantry_runner_local::{LocalConfig, LocalRunner};

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn local_runner_passes_the_suite() {
    let dir = std::env::temp_dir().join(format!("infantry-runner-suite-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let runner = LocalRunner::new(LocalConfig { harnesses: vec!["cmd".into(), "sh".into(), "no-such-harness-cli".into()], ..Default::default() });
    let pong = runner.ping().await.unwrap();
    assert!(!pong.harnesses.iter().any(|h| h == "no-such-harness-cli"));
    infantry_runner_api::suite::suite(&runner, &dir.to_string_lossy()).await;
    drop(runner);
    let _ = std::fs::remove_dir_all(&dir);
}
