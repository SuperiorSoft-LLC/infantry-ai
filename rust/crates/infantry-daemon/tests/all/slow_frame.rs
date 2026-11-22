// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! HOP-1: a slow unit frame (a harness probe, a git status) must not hold up the frames behind it on its connection.
//! The board's `ticket_list` waited 5 s behind a `harness_install_status` probe in the desktop window; the connection
//! loop answered one frame at a time. A frame still running after `Config::frame_detach_ms` is detached: later frames
//! pass it, and its reply is sent when it ends.
use infantry_conformance::client::Client;
use infantry_daemon::auth::AccessPolicy;
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::server::{self, App};
use infantry_daemon::sessions::{Config, Daemon, FrameUnits};
use infantry_protocol::Envelope;
use std::sync::Arc;
use std::time::{Duration, Instant};

const SLOW_MS: u64 = 1_500;

/// Two frames: `slow_probe` sleeps, `fast_list` answers at once.
struct Units;

impl FrameUnits for Units {
    fn owns(&self, ty: &str) -> bool {
        ty == "slow_probe" || ty == "fast_list"
    }
    fn handle(&self, env: Envelope, _owner: bool) -> Vec<String> {
        if env.r#type == "slow_probe" {
            std::thread::sleep(Duration::from_millis(SLOW_MS));
        }
        let ty = if env.r#type == "slow_probe" { "slow_done" } else { "fast_done" };
        vec![serde_json::to_string(&Envelope::new(ty, "ok")).unwrap()]
    }
    fn hello(&self) -> Vec<String> {
        Vec::new()
    }
    fn http(&self) -> Option<axum::Router> {
        None
    }
}

fn start() -> u16 {
    let dir = std::env::temp_dir().join(format!("infantry-daemon-slow-frame-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async move {
            let daemon = Daemon::new(Arc::new(Stub { start_ms: 300, line_ms: 25 }), Config::new(dir.clone(), dir)).unwrap();
            daemon.set_units(Arc::new(Units));
            let policy = Arc::new(AccessPolicy::new("127.0.0.1", None).unwrap());
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            tx.send(listener.local_addr().unwrap().port()).unwrap();
            server::serve(listener, App { daemon, policy }, std::future::pending()).await.unwrap();
        });
    });
    rx.recv_timeout(Duration::from_secs(10)).unwrap()
}

#[test]
fn a_slow_unit_frame_does_not_hold_up_the_frames_behind_it() {
    let port = start();
    let mut c = Client::connect(&format!("ws://127.0.0.1:{port}"), None).unwrap();
    let t = Duration::from_secs(20);
    c.wait_for(t, |e| e.r#type == "ready").unwrap();
    let sent = Instant::now();
    c.send_frame("slow_probe", "", None).unwrap();
    c.send_frame("fast_list", "", None).unwrap();
    c.wait_for(t, |e| e.r#type == "fast_done").unwrap();
    let fast = sent.elapsed();
    assert!(fast < Duration::from_millis(SLOW_MS / 2), "the fast frame waited {fast:?} behind the slow one");
    // The slow frame still answers, once, when it ends.
    c.wait_for(t, |e| e.r#type == "slow_done").unwrap();
    assert!(sent.elapsed() >= Duration::from_millis(SLOW_MS), "the slow frame answered before it could have finished");
    c.close();
}
