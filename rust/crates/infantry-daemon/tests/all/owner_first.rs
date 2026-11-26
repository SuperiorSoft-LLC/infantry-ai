// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! OWNER-PRIO-1: input the owner typed goes before mail. A mail wake held for a busy session never runs in front of an
//! owner prompt queued behind it, and a mail wake into a session at rest with an owner prompt waiting joins the queue
//! after it instead of taking the turn. The mail is not lost: it is the turn after.
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::sessions::{Conn, Daemon, NewOpts, Config};
use infantry_protocol::Envelope;
use std::sync::Arc;
use std::time::{Duration, Instant};

fn wait(what: &str, mut f: impl FnMut() -> bool) {
    let end = Instant::now() + Duration::from_secs(20);
    while !f() {
        assert!(Instant::now() < end, "timed out waiting for {what}");
        std::thread::sleep(Duration::from_millis(20));
    }
}

fn owner_queues(dm: &Arc<Daemon>, sid: &str, text: &str) {
    let mut e = Envelope::new("queue_add", text);
    e.session_id = Some(sid.into());
    let out = dm.handle(e, &mut Conn::default());
    assert!(out.iter().all(|f| !f.contains("\"error\"")), "{out:?}");
}

#[test]
fn an_owner_prompt_queued_behind_a_mail_wake_runs_first_and_the_mail_after() {
    let d = tempfile::tempdir().unwrap();
    let rt = tokio::runtime::Runtime::new().unwrap();
    let _g = rt.enter();
    let dm = Daemon::new(Arc::new(Stub { start_ms: 600, line_ms: 5 }), Config::new(d.path().to_path_buf(), d.path().to_path_buf())).unwrap();
    let s = dm.launch(NewOpts { prompt: Some("work".into()), ..Default::default() }).unwrap();
    wait("busy", || dm.turn_active(&s) == Some(true));

    // The mail wake is due first (held: a turn is in flight), then the owner types.
    dm.deliver(&s, "a mail from a worker").unwrap();
    owner_queues(&dm, &s, "the owner's message");
    assert_eq!(dm.record(&s).unwrap().queued.len(), 2);

    // The next turn is the owner's; the mail waits, not lost.
    wait("second turn", || dm.record(&s).unwrap().run_count >= 2);
    let q = dm.record(&s).unwrap().queued;
    assert_eq!(q.iter().map(|q| q.text.as_str()).collect::<Vec<_>>(), vec!["a mail from a worker"], "the owner's prompt went first");

    // At the next turn end the mail goes out.
    wait("mail turn", || dm.record(&s).unwrap().run_count >= 3);
    assert!(dm.record(&s).unwrap().queued.is_empty());
}

#[test]
fn a_mail_wake_never_takes_the_turn_from_a_waiting_owner_prompt() {
    let d = tempfile::tempdir().unwrap();
    let rt = tokio::runtime::Runtime::new().unwrap();
    let _g = rt.enter();
    let dm = Daemon::new(Arc::new(Stub { start_ms: 600, line_ms: 5 }), Config::new(d.path().to_path_buf(), d.path().to_path_buf())).unwrap();
    let s = dm.launch(NewOpts { prompt: Some("work".into()), ..Default::default() }).unwrap();
    wait("busy", || dm.turn_active(&s) == Some(true));
    owner_queues(&dm, &s, "the owner's message");
    // The turn ends: the mail router's wake (session_idle) and the queue drain race. Mail delivered at that moment
    // must not start its own turn in front of the owner's.
    wait("at rest or owner turn", || dm.turn_active(&s) == Some(false) || dm.record(&s).unwrap().run_count >= 2);
    dm.deliver(&s, "a mail from a worker").unwrap();
    wait("second turn", || dm.record(&s).unwrap().run_count >= 2);
    let q = dm.record(&s).unwrap().queued;
    assert_eq!(q.iter().map(|q| q.text.as_str()).collect::<Vec<_>>(), vec!["a mail from a worker"], "the owner's prompt went first");
    wait("mail turn", || dm.record(&s).unwrap().run_count >= 3);
}
