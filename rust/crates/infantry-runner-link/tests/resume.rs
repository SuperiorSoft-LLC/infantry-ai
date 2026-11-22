// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RR-8 runner side: a runner restarted mid-session keeps the stream sequence going above the orchestrator's cursor (nothing
//! dropped as "already taken", nothing repeated), a process that did not survive ends with the runner's reason, and the new
//! boot takes new work.

use infantry_runner_api::ProcessSpec;
use infantry_runner_link::process::ProcessBackend;
use infantry_runner_link::wire::*;
use infantry_runner_link::Backend;
use std::collections::BTreeMap;
use std::sync::Arc;
use std::time::Duration;

fn sh(line: &str) -> ProcessSpec {
    if cfg!(windows) {
        ProcessSpec::new("cmd", ["/c", line])
    } else {
        ProcessSpec::new("sh", ["-c", line])
    }
}

fn long_running() -> ProcessSpec {
    sh(if cfg!(windows) { "echo first-line && ping -n 40 127.0.0.1 > nul" } else { "echo first-line; sleep 40" })
}

fn backend(dir: &std::path::Path, tag: &str) -> ProcessBackend {
    ProcessBackend::new(Arc::new(infantry_runner_local::LocalRunner::default().with_id_tag(tag))).with_state_dir(dir)
}

fn pull(b: &ProcessBackend, sid: &str, after: i64) -> Vec<infantry_protocol::Envelope> {
    let req = FramesPullRequest { resume_from: BTreeMap::from([(sid.to_string(), after)]), wait_ms: 0, max: 0 };
    b.frames_pull(&req, Duration::from_millis(1500), 1000).unwrap().frames
}

fn start(b: &ProcessBackend, spec: ProcessSpec) -> String {
    let a = b.start(&StartRequest { start_id: uuid::Uuid::new_v4().to_string(), process: Some(spec), ..Default::default() });
    a.session_id.unwrap_or_else(|| panic!("refused: {:?}", a.refusal))
}

#[test]
fn a_restarted_runner_continues_above_the_cursor_and_reports_the_lost_process() {
    let dir = std::env::temp_dir().join(format!("rr8-{}", uuid::Uuid::new_v4()));
    let first = backend(&dir, "a");
    let sid = start(&first, long_running());
    let mut cursor = 0;
    let deadline = std::time::Instant::now() + Duration::from_secs(15);
    while cursor == 0 && std::time::Instant::now() < deadline {
        cursor = pull(&first, &sid, 0).iter().map(|f| f.sequence.unwrap_or(0)).max().unwrap_or(0);
    }
    assert!(cursor > 0, "the process produced no frame");
    // An already finished process, whose exit the orchestrator already took.
    let done = start(&first, sh("exit 3"));
    let mut done_cursor = 0;
    let deadline = std::time::Instant::now() + Duration::from_secs(15);
    while std::time::Instant::now() < deadline {
        let f = pull(&first, &done, done_cursor);
        done_cursor = f.iter().map(|x| x.sequence.unwrap_or(0)).max().unwrap_or(done_cursor);
        if f.iter().any(|x| x.r#type == PROC_EXIT) {
            break;
        }
    }
    drop(first); // the runner goes down mid-session

    let second = backend(&dir, "b");
    assert!(second.is_linked(&sid), "the restarted runner still knows the session");
    let frames = pull(&second, &sid, cursor);
    assert!(frames.iter().all(|f| f.sequence.unwrap() > cursor), "{frames:?}");
    assert!(frames.iter().any(|f| f.r#type == PROC_STDERR && String::from_utf8_lossy(&base64_decode(&f.data)).contains("did not survive")), "{frames:?}");
    let exit = frames.iter().find(|f| f.r#type == PROC_EXIT).expect("an exit frame");
    assert_eq!(exit.data, "-1");
    let seqs: Vec<i64> = frames.iter().map(|f| f.sequence.unwrap()).collect();
    assert!(seqs.windows(2).all(|w| w[0] < w[1]), "no duplicates, ascending: {seqs:?}");
    // Pulling again from the new cursor gives nothing: the frames are not served twice.
    assert!(pull(&second, &sid, *seqs.last().unwrap()).is_empty());
    // The process that had exited keeps its exit at the same sequence: the orchestrator that took it is not given it again.
    assert!(second.is_linked(&done));
    assert!(pull(&second, &done, done_cursor).is_empty(), "the exit the orchestrator took is not repeated");
    // A second restart does not stack another gap or another reason on the lost session.
    drop(second);
    let third = backend(&dir, "c");
    assert!(pull(&third, &sid, *seqs.last().unwrap()).is_empty());
    // The new boot takes new work, on ids that never collide with the old ones.
    let fresh = start(&third, sh("echo after-restart"));
    assert_ne!(fresh, sid);
    let deadline = std::time::Instant::now() + Duration::from_secs(15);
    let mut got = String::new();
    while !got.contains("after-restart") && std::time::Instant::now() < deadline {
        for f in pull(&third, &fresh, 0) {
            if f.r#type == PROC_OUTPUT {
                got.push_str(&String::from_utf8_lossy(&base64_decode(&f.data)));
            }
        }
    }
    assert!(got.contains("after-restart"), "{got:?}");
    // Releasing forgets it for good.
    assert!(third.remove(&sid));
    drop(third);
    assert!(!backend(&dir, "d").is_linked(&sid));
    std::fs::remove_dir_all(dir).ok();
}

#[test]
fn an_unreadable_session_file_is_set_aside() {
    let dir = std::env::temp_dir().join(format!("rr8-bad-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    std::fs::write(dir.join("runner-sessions.json"), "not json").unwrap();
    let b = backend(&dir, "a");
    assert!(!b.is_linked("anything"));
    assert!(dir.join("runner-sessions.json.unreadable").exists());
    std::fs::remove_dir_all(dir).ok();
}

fn base64_decode(s: &str) -> Vec<u8> {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.decode(s).unwrap_or_default()
}
