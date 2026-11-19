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
    static N: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
    let n = N.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
    let dir = std::env::temp_dir().join(format!("infantry-daemon-queue-{}-{n}", std::process::id()));
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

/// "Send now" on a harness that cannot take input mid-turn is refused loudly and the message stays queued, then runs as
/// the next turn: it is never dropped silently.
#[test]
fn queue_force_refused_mid_turn_keeps_the_message_queued() {
    let port = start();
    let mut c = Client::connect(&format!("ws://127.0.0.1:{port}"), None).unwrap();
    let t = Duration::from_secs(20);
    c.wait_for(t, |e| e.r#type == "ready").unwrap();
    c.send_frame("prompt", "first", None).unwrap();
    let sid = c.wait_for(t, |e| e.r#type == "session").unwrap().session_id.unwrap();
    c.send(&frame("queue_add", "held", &sid, None)).unwrap();
    let held = c.wait_for(t, |e| e.r#type == "session" && e.data.contains("\"queued\"")).unwrap();
    let v: serde_json::Value = serde_json::from_str(&held.data).unwrap();
    let qid = v["queued"][0]["id"].as_str().unwrap().to_string();

    c.send(&frame("queue_force", &qid, &sid, None)).unwrap();
    let err = c.wait_for(t, |e| e.r#type == "error").unwrap();
    assert!(err.data.contains("queue_force refused"), "{}", err.data);
    c.wait_for(t, |e| e.r#type == "finished").unwrap();
    let turn = c.wait_for(t, |e| e.r#type == "turn").unwrap();
    assert_eq!(turn.data, "held", "the refused message still runs as the next turn");
    c.close();
}

#[test]
fn a_json_new_binds_a_session_at_rest_and_replay_rebuilds_its_stream() {
    let port = start();
    let mut c = Client::connect(&format!("ws://127.0.0.1:{port}"), None).unwrap();
    let t = Duration::from_secs(20);
    c.wait_for(t, |e| e.r#type == "ready").unwrap();
    c.send_frame("new", r#"{"name":"Smoke"}"#, None).unwrap();
    let s = c.wait_for(t, |e| e.r#type == "session").unwrap();
    let sid = s.session_id.clone().unwrap();
    assert!(s.data.contains("\"state\":\"IDLE\"") && s.data.contains("\"role\":\"smoke\""), "{}", s.data);
    // A session-less prompt on this connection goes into the bound session.
    c.send_frame("prompt", "first", None).unwrap();
    let turn = c.wait_for(t, |e| e.r#type == "turn").unwrap();
    assert_eq!(turn.session_id.as_deref(), Some(sid.as_str()));
    let fin = c.wait_for(t, |e| e.r#type == "finished").unwrap();
    // A second client asks for the history and gets every frame up to the finish, in order.
    let mut c2 = Client::connect(&format!("ws://127.0.0.1:{port}"), None).unwrap();
    c2.wait_for(t, |e| e.r#type == "ready").unwrap();
    c2.send(&frame("replay_request", r#"{"lastSequence":0}"#, &sid, None)).unwrap();
    let mut seqs = Vec::new();
    loop {
        let e = c2.wait_for(t, |e| e.session_id.as_deref() == Some(sid.as_str())).unwrap();
        seqs.push(e.sequence.unwrap());
        if e.sequence == fin.sequence {
            break;
        }
    }
    assert_eq!(seqs, (1..=fin.sequence.unwrap()).collect::<Vec<_>>());
    c.close();
    c2.close();
}
