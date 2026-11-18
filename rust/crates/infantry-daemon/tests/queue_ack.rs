// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-DAEMON-2: a prompt into a running turn is refused typed (`run_in_flight`, its requestId echoed), `queue_add` holds
//! it and the daemon sends it at the turn end, and a `send:` prompt is acknowledged once (a resend = `duplicate`, not run).
use infantry_conformance::client::Client;
use infantry_daemon::auth::AccessPolicy;
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::server::{self, App};
use infantry_daemon::sessions::{Config, Daemon};
use infantry_protocol::Envelope;
use std::sync::Arc;
use std::time::Duration;

fn start() -> u16 {
    let dir = std::env::temp_dir().join(format!("infantry-daemon-queue-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async move {
            let daemon = Daemon::new(Arc::new(Stub { start_ms: 300, line_ms: 25 }), Config::new(dir.clone(), dir)).unwrap();
            let policy = Arc::new(AccessPolicy::new("127.0.0.1", None).unwrap());
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            tx.send(listener.local_addr().unwrap().port()).unwrap();
            server::serve(listener, App { daemon, policy }, std::future::pending()).await.unwrap();
        });
    });
    rx.recv_timeout(Duration::from_secs(10)).unwrap()
}

fn frame(ty: &str, data: &str, sid: &str, req: Option<&str>) -> Envelope {
    let mut e = Envelope::new(ty, data);
    e.session_id = Some(sid.into());
    e.request_id = req.map(String::from);
    e
}

#[test]
fn mid_turn_prompts_are_refused_typed_queued_and_acknowledged_once() {
    let port = start();
    let mut c = Client::connect(&format!("ws://127.0.0.1:{port}"), None).unwrap();
    let t = Duration::from_secs(20);
    c.wait_for(t, |e| e.r#type == "ready").unwrap();
    c.send_frame("prompt", "first", None).unwrap();
    let sid = c.wait_for(t, |e| e.r#type == "session").unwrap().session_id.unwrap();

    c.send(&frame("prompt", "second", &sid, Some("send:a"))).unwrap();
    let err = c.wait_for(t, |e| e.r#type == "error").unwrap();
    assert!(err.data.starts_with(&format!("run_in_flight {sid}: ")), "{}", err.data);
    assert_eq!(err.request_id.as_deref(), Some("send:a"));
    assert_eq!(err.session_id, None, "the typed refusal is session-less, as Kotlin's");

    c.send(&frame("queue_add", "held", &sid, None)).unwrap();
    let held = c.wait_for(t, |e| e.r#type == "session" && e.data.contains("\"queued\"")).unwrap();
    assert!(held.data.contains("\"text\":\"held\""), "{}", held.data);
    c.wait_for(t, |e| e.r#type == "finished").unwrap();
    let turn = c.wait_for(t, |e| e.r#type == "turn").unwrap();
    assert_eq!(turn.data, "held", "the held message is the next turn");
    c.wait_for(t, |e| e.r#type == "finished").unwrap();

    // The refused id was forgotten: the same id now runs and is acknowledged; its resend is a duplicate and does not run.
    c.send(&frame("prompt", "second", &sid, Some("send:a"))).unwrap();
    let ack = c.wait_for(t, |e| e.r#type == "prompt_ack").unwrap();
    assert_eq!((ack.data.as_str(), ack.request_id.as_deref(), ack.session_id.as_deref()), ("accepted", Some("send:a"), Some(sid.as_str())));
    c.wait_for(t, |e| e.r#type == "finished").unwrap();
    c.send(&frame("prompt", "second", &sid, Some("send:a"))).unwrap();
    let dup = c.wait_for(t, |e| e.r#type == "prompt_ack" || e.r#type == "turn").unwrap();
    assert_eq!((dup.r#type.as_str(), dup.data.as_str()), ("prompt_ack", "duplicate"));
    c.close();
}
