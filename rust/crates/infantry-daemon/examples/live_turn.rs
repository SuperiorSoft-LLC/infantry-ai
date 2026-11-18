// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Live check: one session on a running daemon, end to end. Prints every frame type it sees.
//! `cargo run --example live_turn -- ws://127.0.0.1:18961 <cwd> [model]`
//!
//! Steps: `new` + `prompt` (turn 1), a second prompt sent mid-turn with a `send:` request id (refused `run_in_flight`),
//! then `queue_add` mid-turn (held, delivered at the turn end), a resend of an acknowledged id (`duplicate`), and the kill.
use infantry_conformance::client::Client;
use infantry_protocol::Envelope;
use std::time::{Duration, Instant};

fn show(e: &Envelope) {
    let d: String = e.data.chars().take(110).collect();
    println!("  <- {:<11} {} {}", e.r#type, e.request_id.as_deref().unwrap_or(""), d.replace('\n', " "));
}

fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    let url = a.first().cloned().unwrap_or_else(|| "ws://127.0.0.1:18961".into());
    let cwd = a.get(1).cloned().expect("cwd");
    let model = a.get(2).cloned().unwrap_or_else(|| "haiku".into());
    let mut c = Client::connect(&url, None).expect("connect");
    let t = Duration::from_secs(120);
    c.wait_for(t, |e| e.r#type == "ready").expect("ready");
    let new = serde_json::json!({ "cwd": cwd, "model": model }).to_string();
    c.send_frame("new", &new, None).unwrap();
    let start = Instant::now();
    c.send_frame("prompt", "Reply with the word ok and nothing else.", None).unwrap();
    let sid = c.wait_for(t, |e| e.r#type == "session").unwrap().session_id.unwrap();
    println!("session {sid}");
    // Mid-turn: a plain prompt is refused typed; a queue_add is held.
    let mut p = Envelope::new("prompt", "Reply with the word two.");
    p.session_id = Some(sid.clone());
    p.request_id = Some("send:mid-1".into());
    c.send(&p).unwrap();
    let mut q = Envelope::new("queue_add", "Reply with the word queued and nothing else.");
    q.session_id = Some(sid.clone());
    c.send(&q).unwrap();
    let mut finished = 0;
    let mut texts = Vec::new();
    while finished < 2 {
        let Some(e) = c.next(t).unwrap() else { panic!("timeout") };
        if e.session_id.as_deref().is_some_and(|s| s != sid) {
            continue;
        }
        if e.r#type != "stdout" {
            show(&e);
        }
        match e.r#type.as_str() {
            "text_delta" => texts.push(e.data.clone()),
            "finished" => {
                finished += 1;
                println!("  finished #{finished} after {} ms", start.elapsed().as_millis());
            }
            _ => {}
        }
    }
    println!("text: {texts:?}");
    // An acknowledged prompt and its resend.
    let mut p = Envelope::new("prompt", "Reply with the word three.");
    p.session_id = Some(sid.clone());
    p.request_id = Some("send:ack-1".into());
    c.send(&p).unwrap();
    let ack = c.wait_for(t, |e| e.r#type == "prompt_ack" || e.r#type == "error").unwrap();
    show(&ack);
    c.send(&p).unwrap();
    let dup = c.wait_for(t, |e| e.r#type == "prompt_ack" || e.r#type == "error").unwrap();
    show(&dup);
    c.wait_for(t, |e| e.r#type == "finished" && e.session_id.as_deref() == Some(&sid)).unwrap();
    println!("turn 3 finished");
    c.send_frame("kill", "", Some(&sid)).unwrap();
    c.wait_for(t, |e| e.r#type == "finished" && e.session_id.as_deref() == Some(&sid)).unwrap();
    c.send_frame("session_delete", "", Some(&sid)).unwrap();
    let _ = c.drain(Duration::from_millis(500), Duration::from_secs(3));
    println!("done");
    c.close();
}
