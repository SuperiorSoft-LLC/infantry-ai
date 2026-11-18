// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! LIVE: two real codex turns through the generic driver on the real LocalRunner (a new thread, then a resume of it). It spends tokens, so it
//! is ignored by default: `cargo test -p infantry-harness-codex --test live -- --ignored --nocapture`.

use infantry_harness::{Harness, HarnessDriver, HarnessEvent, LaunchRequest, LocalProbe, TurnEnd};
use infantry_harness_codex::CodexHarness;
use infantry_runner_local::LocalRunner;
use std::sync::Arc;
use std::time::Duration;

#[tokio::test(flavor = "multi_thread")]
#[ignore = "spends real codex tokens"]
async fn a_real_turn_then_a_resumed_turn() {
    // GAP (RUST-RUN-1): LocalRunner's piped spawn does not apply PATHEXT, so the npm `codex.cmd` shim is "program not
    // found" by bare name. Until it does, this test hands the harness the full path it finds on PATH.
    let harness = Arc::new(CodexHarness { program: on_path("codex").expect("codex on PATH") });
    if let Some(problem) = harness.sign_in_problem(&LocalProbe) {
        panic!("codex cannot run here: {problem}");
    }
    let dir = std::env::temp_dir().join(format!("infantry-harn1-live-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let mut d = HarnessDriver::new(harness, Arc::new(LocalRunner::default()), LaunchRequest::new(dir.clone()));
    let step = Duration::from_millis(100);
    let limit = Duration::from_secs(240);

    let mut texts = Vec::new();
    let end = d
        .run_turn("reply with the word ok", limit, step, |e| {
            if let HarnessEvent::Text(t) = e {
                texts.push(t.clone());
            }
            if !matches!(e, HarnessEvent::Stdout(_)) {
                println!("turn1 {e:?}");
            }
        })
        .await
        .unwrap();
    assert_eq!(end, TurnEnd::Completed);
    assert!(texts.iter().any(|t| t.to_lowercase().contains("ok")), "{texts:?}");
    let thread = d.resume_token().expect("thread id captured").to_string();

    let mut sessions = Vec::new();
    let end = d
        .run_turn("reply with the word ok again", limit, step, |e| {
            if let HarnessEvent::ProviderSession(s) = e {
                sessions.push(s.clone());
            }
            if !matches!(e, HarnessEvent::Stdout(_)) {
                println!("turn2 {e:?}");
            }
        })
        .await
        .unwrap();
    assert_eq!(end, TurnEnd::Completed);
    assert_eq!(sessions, vec![thread], "the second turn resumed the same thread");
    d.close().await;
    let _ = std::fs::remove_dir_all(&dir);
}

fn on_path(name: &str) -> Option<String> {
    let exts: &[&str] = if cfg!(windows) { &[".cmd", ".exe", ""] } else { &[""] };
    std::env::split_paths(&std::env::var_os("PATH")?)
        .flat_map(|d| exts.iter().map(move |e| d.join(format!("{name}{e}"))))
        .find(|p| p.is_file())
        .map(|p| p.to_string_lossy().to_string())
}
