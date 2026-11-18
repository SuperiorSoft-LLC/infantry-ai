// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! PERF helper: start N sessions (one turn each) on a daemon and leave them listed, for an RSS reading.
//! `cargo run --example spawn_sessions -- ws://127.0.0.1:18961 5 [token]`
use infantry_conformance::client::Client;
use std::time::Duration;

fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    let url = a.first().cloned().unwrap_or_else(|| "ws://127.0.0.1:18961".into());
    let n: usize = a.get(1).and_then(|s| s.parse().ok()).unwrap_or(5);
    let mut c = Client::connect(&url, a.get(2).map(String::as_str)).expect("connect");
    let t = Duration::from_secs(30);
    c.wait_for(t, |e| e.r#type == "ready").expect("ready");
    let mut done: Vec<String> = Vec::new();
    for i in 0..n {
        c.send_frame("prompt", &format!("perf session {i}"), None).expect("send");
        let sid = c
            .wait_for(t, |e| e.r#type == "session" && e.session_id.as_ref().is_some_and(|s| !done.contains(s)))
            .expect("session")
            .session_id
            .unwrap();
        c.wait_for(t, |e| e.r#type == "finished" && e.session_id.as_deref() == Some(&sid)).expect("finished");
        println!("{sid}");
        done.push(sid);
    }
    c.close();
}
