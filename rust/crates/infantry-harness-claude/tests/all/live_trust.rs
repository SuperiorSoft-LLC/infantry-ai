// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! LIVE: the interactive `claude` started in a folder it has never been started in (so it asks whether to trust it) answers
//! its first turn. It spends a few tokens, so it is ignored by default:
//! `cargo test -p infantry-harness-claude --test all live_trust:: -- --ignored --nocapture`.
//!
//! Two starts, one after the other (both write the owner's `~/.claude.json`, where the CLI remembers a trusted folder, so
//! each uses a folder that is new):
//! - with no terminal environment at all, like a daemon the desktop app started: the harness tells the CLI its terminal;
//! - with `TERM=xterm`, which makes the CLI's UI draw ASCII glyphs on Windows: the `>` pointer is read end to end.
//!
//! The child gets a temp INFANTRY_STATE_DIR and no INFANTRY_URL / INFANTRY_TOKEN / INFANTRY_HOME, so it can never reach the
//! owner's daemon.

use infantry_harness::{Harness, HarnessDriver, HarnessEvent, LaunchRequest, LocalProbe, TurnEnd};
use infantry_harness_claude::ClaudeHarness;
use infantry_runner_local::LocalRunner;
use std::sync::Arc;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const STEP: Duration = Duration::from_millis(100);
const LIMIT: Duration = Duration::from_secs(150);
/// What a process the desktop app started does not have.
const TERMINAL_ENV: [&str; 7] = ["TERM", "COLORTERM", "TERM_PROGRAM", "WT_SESSION", "WT_PROFILE_ID", "NO_COLOR", "LANG"];

async fn first_turn_in_a_new_folder(name: &str, request_term: Option<&str>) {
    let harness = Arc::new(ClaudeHarness::default());
    if let Some(problem) = harness.sign_in_problem(&LocalProbe) {
        panic!("claude cannot run here: {problem}");
    }
    let stamp = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or_default();
    static SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
    let n = SEQ.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    let root = std::env::temp_dir().join(format!("infantry-harn-claude-trust-{name}-{}-{n}-{stamp}", std::process::id()));
    let (dir, state) = (root.join("work"), root.join("state"));
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::create_dir_all(&state).unwrap();

    let mut req = LaunchRequest::new(dir);
    req.env.push(("INFANTRY_STATE_DIR".into(), state.display().to_string()));
    req.env_remove.extend(["INFANTRY_URL", "INFANTRY_TOKEN", "INFANTRY_HOME", "AGENTHQ_URL", "AGENTHQ_TOKEN", "AGENTHQ_HOME"].map(String::from));
    req.env_remove.extend(TERMINAL_ENV.map(String::from));
    if let Some(term) = request_term {
        req.env.push(("TERM".into(), term.into()));
    }

    let mut d = HarnessDriver::new(harness, Arc::new(LocalRunner::default()), req);
    let (mut text, mut trust_answered) = (String::new(), false);
    let end = d
        .run_turn("Reply with only the word ok.", LIMIT, STEP, |e| {
            match e {
                HarnessEvent::Text(t) => text.push_str(t),
                HarnessEvent::Warning(w) if w.contains("folder trust answered") => trust_answered = true,
                _ => {}
            }
            if !matches!(e, HarnessEvent::Stdout(_)) {
                println!("{name} {e:?}");
            }
        })
        .await
        .unwrap();
    d.close().await;
    let _ = std::fs::remove_dir_all(&root);
    assert_eq!(end, TurnEnd::Completed, "{name}: the turn ran to its end");
    assert!(trust_answered, "{name}: the folder-trust menu was drawn and answered");
    assert!(text.to_lowercase().contains("ok"), "{name}: {text:?}");
}

#[tokio::test(flavor = "multi_thread")]
#[ignore = "spends real claude tokens"]
async fn a_new_folder_is_trusted_and_answers_with_or_without_a_terminal_environment() {
    first_turn_in_a_new_folder("no-terminal-env", None).await;
    first_turn_in_a_new_folder("ascii-glyphs", Some("xterm")).await;
}
