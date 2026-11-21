// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! LIVE: the interactive `claude` in a PTY on the real LocalRunner: two turns in one process, then a NEW process that resumes
//! the session and still knows what the first turn said; and a resume after a kill with a background job (a ticket restart)
//! that still takes its prompt. They spend tokens, so they are ignored by default:
//! `cargo test -p infantry-harness-claude --test all live:: -- --ignored --nocapture`.
//!
//! The child gets a temp INFANTRY_STATE_DIR and no INFANTRY_URL / INFANTRY_TOKEN / INFANTRY_HOME, so it can never reach the
//! owner's daemon.

use infantry_harness::{Harness, HarnessDriver, HarnessEvent, LaunchRequest, LocalProbe, TurnEnd};
use infantry_harness_claude::ClaudeHarness;
use infantry_runner_local::LocalRunner;
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

const STEP: Duration = Duration::from_millis(100);
const LIMIT: Duration = Duration::from_secs(300);

fn request(dir: &Path, state: &Path, resume: Option<String>) -> LaunchRequest {
    let mut req = LaunchRequest::new(dir.to_path_buf());
    req.resume = resume;
    req.env.push(("INFANTRY_STATE_DIR".into(), state.display().to_string()));
    for k in ["INFANTRY_URL", "INFANTRY_TOKEN", "INFANTRY_HOME", "AGENTHQ_URL", "AGENTHQ_TOKEN", "AGENTHQ_HOME"] {
        req.env_remove.push(k.into());
    }
    req
}

async fn turn(d: &mut HarnessDriver, label: &str, prompt: &str) -> String {
    let mut text = String::new();
    let end = d
        .run_turn(prompt, LIMIT, STEP, |e| {
            if let HarnessEvent::Text(t) = e {
                text.push_str(t);
            }
            if !matches!(e, HarnessEvent::Stdout(_)) {
                println!("{label} {e:?}");
            }
        })
        .await
        .unwrap();
    assert_eq!(end, TurnEnd::Completed, "{label}");
    text
}

#[tokio::test(flavor = "multi_thread")]
#[ignore = "spends real claude tokens"]
async fn two_real_turns_then_a_resume_in_a_new_process() {
    let harness = Arc::new(ClaudeHarness::default());
    if let Some(problem) = harness.sign_in_problem(&LocalProbe) {
        panic!("claude cannot run here: {problem}");
    }
    let root = std::env::temp_dir().join(format!("infantry-harn-claude-live-{}", std::process::id()));
    let (dir, state) = (root.join("work"), root.join("state"));
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::create_dir_all(&state).unwrap();
    let runner = Arc::new(LocalRunner::default());

    let mut d = HarnessDriver::new(harness.clone(), runner.clone(), request(&dir, &state, None));
    let t1 = turn(&mut d, "turn1", "Remember the word pineapple. Reply with only the word ok.").await;
    assert!(t1.to_lowercase().contains("ok"), "{t1:?}");
    let session = d.resume_token().expect("session id captured").to_string();
    let t2 = turn(&mut d, "turn2", "Reply with only the word ok again.").await;
    assert!(t2.to_lowercase().contains("ok"), "{t2:?}");
    assert_eq!(d.resume_token(), Some(session.as_str()), "the second turn ran in the same session");
    d.close().await;

    let mut d = HarnessDriver::new(harness, runner, request(&dir, &state, Some(session.clone())));
    let t3 = turn(&mut d, "resume", "Which word did I ask you to remember? Reply with only that word.").await;
    assert!(t3.to_lowercase().contains("pineapple"), "the resumed process kept the conversation: {t3:?}");
    d.close().await;
    let _ = std::fs::remove_dir_all(&root);
}

/// Like [`turn`], but a turn that has not ended in [limit] is an error the test reports.
async fn turn_within(d: &mut HarnessDriver, label: &str, prompt: &str, limit: Duration) -> Result<String, String> {
    let mut text = String::new();
    let end = d
        .run_turn(prompt, limit, STEP, |e| {
            if let HarnessEvent::Text(t) = e {
                text.push_str(t);
            }
            if !matches!(e, HarnessEvent::Stdout(_)) {
                println!("{label} {e:?}");
            }
        })
        .await
        .map_err(|e| format!("{label}: {e:?}"))?;
    if end != TurnEnd::Completed {
        return Err(format!("{label}: the turn ended {end:?}"));
    }
    Ok(text)
}

/// RESTART-HANG-1: a ticket restart. The process dies with a background shell job unfinished (no completion record), a new
/// process resumes the session, and the prompt it is sent (the `[RESTART]` brief) must still be taken. The resumed claude
/// writes a notice about the orphaned job to its transcript (`queueTranscriptOnly`): it is no turn to wait for. Before the
/// fix the prompt was never pasted and this turn ran out its time.
#[tokio::test(flavor = "multi_thread")]
#[ignore = "spends real claude tokens"]
async fn a_resume_after_a_kill_with_a_background_job_still_takes_its_prompt() {
    let harness = Arc::new(ClaudeHarness::default());
    if let Some(problem) = harness.sign_in_problem(&LocalProbe) {
        panic!("claude cannot run here: {problem}");
    }
    let root = std::env::temp_dir().join(format!("infantry-harn-claude-restart-{}", std::process::id()));
    let (dir, state) = (root.join("work"), root.join("state"));
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::create_dir_all(&state).unwrap();
    let runner = Arc::new(LocalRunner::default());
    let cheap = |resume: Option<String>| {
        let mut req = request(&dir, &state, resume);
        req.model = Some("haiku".into());
        req
    };

    let mut d = HarnessDriver::new(harness.clone(), runner.clone(), cheap(None));
    let t1 = turn(
        &mut d,
        "turn1",
        "Use the Bash tool with run_in_background set to true to run: sleep 600. Do nothing else. Once it is running, reply with only the word started.",
    )
    .await;
    assert!(t1.to_lowercase().contains("started"), "{t1:?}");
    let session = d.resume_token().expect("session id captured").to_string();
    d.close().await; // the kill: the CLI dies with its job unfinished

    let mut d = HarnessDriver::new(harness, runner, cheap(Some(session)));
    let t2 = turn_within(&mut d, "restart", "[RESTART] Your session ended. Reply with only the word ok.", Duration::from_secs(120))
        .await
        .unwrap_or_else(|e| panic!("the restart prompt was never taken: {e}"));
    assert!(t2.to_lowercase().contains("ok"), "{t2:?}");
    d.close().await;
    let _ = std::fs::remove_dir_all(&root);
}
