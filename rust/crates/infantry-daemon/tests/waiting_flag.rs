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
    let dir = std::env::temp_dir().join(format!("infantry-daemon-waiting-{}-{n}", std::process::id()));
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
fn waiting_frame_flags_reads_clears_and_refuses() {
    let port = start();
    let mut c = Client::connect(&format!("ws://127.0.0.1:{port}"), None).unwrap();
    let t = Duration::from_secs(20);
    c.wait_for(t, |e| e.r#type == "ready").unwrap();
    c.send_frame("prompt", "first", None).unwrap();
    let sid = c.wait_for(t, |e| e.r#type == "session").unwrap().session_id.unwrap();
    c.wait_for(t, |e| e.r#type == "finished").unwrap();

    c.send(&frame("waiting", r#"{"op":"on","reason":"cargo build","minutes":10}"#, &sid, Some("w1"))).unwrap();
    let on = c.wait_for(t, |e| e.r#type == "waiting").unwrap();
    assert!(on.data.contains("\"reason\":\"cargo build\""), "{}", on.data);
    c.send(&frame("waiting", r#"{"op":"get"}"#, &sid, None)).unwrap();
    assert!(c.wait_for(t, |e| e.r#type == "waiting").unwrap().data.contains("cargo build"));

    // A reason is required, minutes are bounded, an unknown op is refused.
    for bad in [r#"{"op":"on"}"#, r#"{"op":"on","reason":"x","minutes":0}"#, r#"{"op":"later"}"#] {
        c.send(&frame("waiting", bad, &sid, None)).unwrap();
        let err = c.wait_for(t, |e| e.r#type == "error").unwrap();
        assert!(err.data.starts_with("waiting refused"), "{}", err.data);
    }

    // The next turn starts: the flag is gone.
    c.send(&frame("prompt", "again", &sid, None)).unwrap();
    c.wait_for(t, |e| e.r#type == "finished").unwrap();
    c.send(&frame("waiting", r#"{"op":"get"}"#, &sid, None)).unwrap();
    assert_eq!(c.wait_for(t, |e| e.r#type == "waiting").unwrap().data, "null");

    c.send(&frame("waiting", r#"{"op":"on","reason":"job"}"#, &sid, None)).unwrap();
    c.wait_for(t, |e| e.r#type == "waiting").unwrap();
    c.send(&frame("waiting", r#"{"op":"off"}"#, &sid, None)).unwrap();
    assert_eq!(c.wait_for(t, |e| e.r#type == "waiting").unwrap().data, "null");
    c.close();
}
