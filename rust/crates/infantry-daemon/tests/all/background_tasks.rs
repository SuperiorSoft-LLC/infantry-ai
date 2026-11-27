// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! A session whose agent CLI keeps a background task running past the turn end: the start and the end are chat-log lines
//! ("started background: ...", "turn ended, 1 background task still running: ...", "background finished: ..."), the
//! record carries the task, and the session reads IDLE at rest (no turn runs; STUCK-RUNNING-1), with the task listed.
use infantry_conformance::client::Client;
use infantry_daemon::auth::AccessPolicy;
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::server::{self, App};
use infantry_daemon::sessions::{Config, Daemon};
use std::sync::Arc;
use std::time::Duration;

#[test]
fn a_background_task_past_the_turn_end_shows_in_the_log_and_the_session_reads_idle() {
    let dir = std::env::temp_dir().join(format!("infantry-daemon-bgtasks-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let (tx, rx) = std::sync::mpsc::channel();
    let d2 = dir.clone();
    std::thread::spawn(move || {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async move {
            let daemon = Daemon::new(Arc::new(Stub { start_ms: 100, line_ms: 10 }), Config::new(d2.clone(), d2)).unwrap();
            let policy = Arc::new(AccessPolicy::new("127.0.0.1", None).unwrap());
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            tx.send(listener.local_addr().unwrap().port()).unwrap();
            server::serve(listener, App { daemon, policy }, std::future::pending()).await.unwrap();
        });
    });
    let port = rx.recv_timeout(Duration::from_secs(10)).unwrap();
    let mut c = Client::connect(&format!("ws://127.0.0.1:{port}"), None).unwrap();
    let t = Duration::from_secs(20);
    c.wait_for(t, |e| e.r#type == "ready").unwrap();
    c.send_frame("prompt", "run it [stub-bg]", None).unwrap();
    c.wait_for(t, |e| e.r#type == "stderr" && e.data.contains("started background: sleep 60")).unwrap();
    // At rest (the turn ended) with a task running: the record counts it and reads IDLE (BUSY only during a turn).
    let at_rest = c.wait_for(t, |e| e.r#type == "session" && e.data.contains("\"backgroundTasks\":[{") && e.data.contains("\"turnActive\":false")).unwrap();
    assert!(at_rest.data.contains("\"state\":\"IDLE\""), "{}", at_rest.data);
    c.wait_for(t, |e| e.r#type == "stderr" && e.data.contains("turn ended, 1 background task still running: sleep 60")).unwrap();
    c.wait_for(t, |e| e.r#type == "stderr" && e.data.contains("background finished: sleep 60, exit 0, 4m12s")).unwrap();
    let ended = c.wait_for(t, |e| e.r#type == "session" && !e.data.contains("backgroundTasks")).unwrap();
    assert!(ended.data.contains("\"state\":\"IDLE\""), "{}", ended.data);
}
