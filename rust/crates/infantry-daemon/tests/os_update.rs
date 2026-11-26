// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! UPDATE-LIVE-1: the daemon as an app gets ready for an update while an agent is busy, holds new turns, and after the
//! restart (a new daemon on the same state dir) resumes the cut turn and sends the held message. Over the real route too.
use infantry_daemon::auth::AccessPolicy;
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::os_app::{OsEnv, OsRuntime};
use infantry_daemon::os_update::{self, Request, RESUME_FILE};
use infantry_daemon::server::{self, App};
use infantry_daemon::sessions::{Config, Daemon, NewOpts};
use std::sync::Arc;
use std::time::{Duration, Instant};

const SECRET: &str = "prepare-secret-for-test";

fn runtime(dir: &std::path::Path, grace_ms: u64) -> Arc<OsRuntime> {
    let token = dir.join("token");
    std::fs::write(&token, SECRET).unwrap();
    let os = OsRuntime::new(OsEnv { ready_file: None, token_file: token, state_dir: Some(dir.to_path_buf()), app_dir: None, ui_origin: None }).unwrap();
    let _ = os.turn_grace_ms.set(Arc::new(move || grace_ms));
    os
}

fn wait(what: &str, mut f: impl FnMut() -> bool) {
    let end = Instant::now() + Duration::from_secs(20);
    while !f() {
        assert!(Instant::now() < end, "timed out waiting for {what}");
        std::thread::sleep(Duration::from_millis(50));
    }
}

fn daemon(dir: &std::path::Path, start_ms: u64) -> Arc<Daemon> {
    Daemon::new(Arc::new(Stub { start_ms, line_ms: 5 }), Config::new(dir.to_path_buf(), dir.to_path_buf())).unwrap()
}

#[test]
fn a_busy_agent_is_given_its_grace_new_turns_wait_and_both_resume_after_the_restart() {
    let d = tempfile::tempdir().unwrap();
    let rt = tokio::runtime::Runtime::new().unwrap();
    let _g = rt.enter();
    let os = runtime(d.path(), 800);
    let dm = daemon(d.path(), 4_000);
    // A: a turn that stays busy (the stub takes 4 s to start). B: at rest.
    let a = dm.launch(NewOpts { prompt: Some("long work".into()), ..Default::default() }).unwrap();
    let b = dm.launch(NewOpts::default()).unwrap();
    wait("A busy", || dm.turn_active(&a) == Some(true));

    let first = os_update::prepare(&dm, &os, &Request { to: Some("0.9.0".into()), ..Default::default() });
    assert_eq!(first["ready"], false, "{first}");
    assert_eq!(first["reason"], os_update::REASON);
    assert!(first["etaMs"].as_u64().is_some_and(|e| e > 0 && e <= 800), "{first}");
    // While it prepares, a new turn into B is held, not started.
    dm.deliver(&b, "a mail for B").unwrap();
    assert_eq!(dm.turn_active(&b), Some(false), "no new turn starts while an update is prepared");
    assert_eq!(dm.record(&b).unwrap().queued.len(), 1);

    std::thread::sleep(Duration::from_millis(900));
    let ready = os_update::prepare(&dm, &os, &Request { to: Some("0.9.0".into()), ..Default::default() });
    assert_eq!(ready["ready"], true, "the grace is over: {ready}");
    let file: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(d.path().join(RESUME_FILE)).unwrap()).unwrap();
    assert_eq!((file["to"].as_str(), file["cut"].clone()), (Some("0.9.0"), serde_json::json!([a])));

    // The OS stops the app: the store is saved and a new daemon (the new version) starts on the same state dir.
    dm.shutdown();
    drop(dm);
    let dm2 = daemon(d.path(), 0);
    assert_eq!(dm2.record(&b).unwrap().queued.len(), 1, "the held message survived the restart");
    let r = os_update::after_restart(&dm2).expect("a prepared update was found");
    assert_eq!(r.line(), "Updated to 0.9.0: 2 agents resumed");
    assert!(r.sessions.contains(&a) && r.sessions.contains(&b), "{r:?}");
    assert!(!d.path().join(RESUME_FILE).exists(), "read once");
    wait("A resumed and B took its mail", || {
        let (ra, rb) = (dm2.record(&a).unwrap(), dm2.record(&b).unwrap());
        ra.run_count >= 2 && rb.run_count >= 1 && rb.queued.is_empty()
    });
    assert!(os_update::after_restart(&dm2).is_none(), "a plain restart resumes nothing");
}

#[test]
fn update_now_is_ready_at_once_and_cancel_lets_turns_start_again() {
    let d = tempfile::tempdir().unwrap();
    let rt = tokio::runtime::Runtime::new().unwrap();
    let _g = rt.enter();
    let os = runtime(d.path(), 60_000);
    let dm = daemon(d.path(), 4_000);
    let a = dm.launch(NewOpts { prompt: Some("long work".into()), ..Default::default() }).unwrap();
    let b = dm.launch(NewOpts::default()).unwrap();
    wait("A busy", || dm.turn_active(&a) == Some(true));
    assert_eq!(os_update::prepare(&dm, &os, &Request::default())["ready"], false);
    dm.deliver(&b, "held").unwrap();
    // Cancel ("Later"): new turns start again and the held message goes out.
    let c = os_update::prepare(&dm, &os, &Request { cancel: true, ..Default::default() });
    assert_eq!(c["cancelled"], true);
    assert!(!dm.turns_paused());
    wait("B takes its held message", || dm.record(&b).is_some_and(|r| r.queued.is_empty() && r.run_count >= 1));
    // "Update now": ready at once although A still runs; A is named to resume.
    let now = os_update::prepare(&dm, &os, &Request { to: Some("1.0.0".into()), now: true, ..Default::default() });
    assert_eq!(now["ready"], true, "{now}");
    let file: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(d.path().join(RESUME_FILE)).unwrap()).unwrap();
    assert!(file["cut"].as_array().unwrap().iter().any(|x| x == &serde_json::json!(a)), "{file}");
}

#[test]
fn the_prepare_route_needs_the_secret_and_answers_json() {
    let d = tempfile::tempdir().unwrap();
    let os = runtime(d.path(), 1_000);
    let dir = d.path().to_path_buf();
    let (tx, rx) = std::sync::mpsc::channel();
    let os2 = os.clone();
    std::thread::spawn(move || {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async move {
            let daemon = Daemon::new(Arc::new(Stub { start_ms: 0, line_ms: 0 }), Config::new(dir.clone(), dir)).unwrap();
            let policy = Arc::new(AccessPolicy::new("127.0.0.1", None).unwrap());
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            tx.send(listener.local_addr().unwrap().port()).unwrap();
            let stopper = os2.clone();
            server::serve_os(listener, App { daemon, policy }, None, Some(os2), async move { stopper.stopped().await }).await
        })
    });
    let base = format!("http://127.0.0.1:{}", rx.recv_timeout(Duration::from_secs(10)).unwrap());
    let post = |bearer: Option<&str>, body: &str| {
        let mut r = ureq::post(&format!("{base}/prepare-update")).set("Content-Type", "application/json");
        if let Some(t) = bearer {
            r = r.set("Authorization", &format!("Bearer {t}"));
        }
        match r.send_string(body) {
            Ok(resp) | Err(ureq::Error::Status(_, resp)) => (resp.status(), resp.into_string().unwrap_or_default()),
            Err(e) => panic!("{e}"),
        }
    };
    assert_eq!(post(None, "{}").0, 401);
    assert_eq!(post(Some("wrong"), "{}").0, 401);
    assert_eq!(post(Some(SECRET), "nope").0, 400);
    let (s, body) = post(Some(SECRET), r#"{"to":"0.9.0"}"#);
    assert_eq!(s, 200);
    assert_eq!(serde_json::from_str::<serde_json::Value>(&body).unwrap()["ready"], true, "nothing busy: ready at once ({body})");
    let (s, body) = post(Some(SECRET), r#"{"cancel":true}"#);
    assert_eq!((s, body.contains("cancelled")), (200, true));
    os.request_stop();
}
