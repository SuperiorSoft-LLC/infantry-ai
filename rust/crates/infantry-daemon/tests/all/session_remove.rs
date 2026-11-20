// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! BUG-PARK-HOOK-1: `infantry_session_remove` is idempotent. An unknown session id answers OK with outcome
//! `already-removed` (so a park hook whose first action is the remove runs its later actions), while the real
//! refusals (a live session without force, a caller who may not remove it, an ambiguous prefix) are unchanged.
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::sessions::{Config, Daemon, NewOpts};
use infantry_scripting::host::{ScriptHost, ToolInvoker};
use serde_json::{json, Map, Value};
use std::sync::Arc;

struct Fixture {
    dir: std::path::PathBuf,
    daemon: Arc<Daemon>,
    c: Arc<infantry_daemon::compose::Composed>,
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

fn fixture() -> Fixture {
    let dir = std::env::temp_dir().join(format!("infantry-daemon-session-remove-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(dir.clone(), dir.clone())).unwrap();
    let c = infantry_daemon::compose::build(&daemon, &dir).unwrap();
    Fixture { dir, daemon, c }
}

fn args(v: Value) -> Map<String, Value> {
    v.as_object().cloned().unwrap()
}

#[tokio::test(flavor = "multi_thread")]
async fn removing_an_unknown_session_is_ok_already_removed() {
    let f = fixture();
    let owner = ToolInvoker { owner: true, ..Default::default() };
    let r = f.c.tools.invoke("infantry_session_remove", &args(json!({"sessionId": "caad95ef-7d4b-4e0d-9f82-024416402182", "force": true})), &owner);
    assert!(!r.is_error, "an unknown session is already removed, not a refusal: {r:?}");
    assert!(r.text.contains("already-removed"), "{r:?}");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_second_remove_of_the_same_session_is_ok_already_removed() {
    let f = fixture();
    let owner = ToolInvoker { owner: true, ..Default::default() };
    let sid = f.daemon.launch(NewOpts { project_id: Some("p1".into()), ..Default::default() }).unwrap();
    let a = args(json!({"sessionId": sid, "force": true}));
    let first = f.c.tools.invoke("infantry_session_remove", &a, &owner);
    assert!(!first.is_error && first.text.starts_with("removed "), "{first:?}");
    let second = f.c.tools.invoke("infantry_session_remove", &a, &owner);
    assert!(!second.is_error, "park-enter then park-exit: the second remove finds nothing and is OK: {second:?}");
    assert!(second.text.contains("already-removed"), "{second:?}");
}

#[tokio::test(flavor = "multi_thread")]
async fn the_real_refusals_are_unchanged() {
    let f = fixture();
    let owner = ToolInvoker { owner: true, ..Default::default() };
    let sid = f.daemon.launch(NewOpts { project_id: Some("p1".into()), ..Default::default() }).unwrap();
    // A live session without force is refused and stays.
    let r = f.c.tools.invoke("infantry_session_remove", &args(json!({"sessionId": sid})), &owner);
    assert!(r.is_error && r.text.contains("still running"), "{r:?}");
    // A caller who is neither the owner, the pm nor the spawner is refused.
    let worker = ToolInvoker { agent: true, session_id: Some("someone-else".into()), role: Some("backend".into()), ..Default::default() };
    let r = f.c.tools.invoke("infantry_session_remove", &args(json!({"sessionId": sid, "force": true})), &worker);
    assert!(r.is_error && r.text.contains("may remove it"), "{r:?}");
    assert!(f.daemon.records().iter().any(|x| x.id == sid), "a refused remove leaves the session in place");
    // A blank id is still a refusal.
    let r = f.c.tools.invoke("infantry_session_remove", &args(json!({"sessionId": " "})), &owner);
    assert!(r.is_error && r.text.contains("sessionId is required"), "{r:?}");
}
