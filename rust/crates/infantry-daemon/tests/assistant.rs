// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! ASSISTANT-1 (#115): the `assistant` frame makes the installation's ONE Assistant. It is a global (`global: true`),
//! project-less session at rest in its own folder under the state dir; every later ask, from any client, returns the same
//! session; what it runs on is the binding of its role (Roles > binding), never a preset; a plain `new` with the same
//! role is still an ordinary session.
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::sessions::{AssistantSpec, Config, Conn, Daemon, NewOpts};
use infantry_protocol::agents::SessionSwap;
use infantry_protocol::session::{SessionRecord, SessionState};
use infantry_protocol::Envelope;
use serde_json::json;
use std::sync::{Arc, Barrier};

struct Fixture {
    dir: std::path::PathBuf,
    daemon: Arc<Daemon>,
    c: Option<Arc<infantry_daemon::compose::Composed>>,
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

/// A daemon composed like `main` (the role bindings and settings are wired).
fn composed() -> Fixture {
    let dir = std::env::temp_dir().join(format!("infantry-daemon-assistant-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(dir.clone(), dir.clone())).unwrap();
    let c = infantry_daemon::compose::build(&daemon, &dir).unwrap();
    Fixture { dir, daemon, c: Some(c) }
}

/// A bare daemon: no composition root, so no role bindings (the default spec).
fn bare() -> Fixture {
    let dir = std::env::temp_dir().join(format!("infantry-daemon-assistant-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(dir.clone(), dir.clone())).unwrap();
    Fixture { dir, daemon, c: None }
}

/// One `assistant` frame sent as `request`; the replies this connection gets.
fn ask(daemon: &Arc<Daemon>, request: &str) -> Vec<Envelope> {
    let mut env = Envelope::new("assistant", "");
    env.request_id = Some(request.into());
    daemon.handle(env, &mut Conn::default()).iter().map(|f| serde_json::from_str(f).expect("an envelope")).collect()
}

/// The Assistant an ask answered with.
fn assistant(daemon: &Arc<Daemon>, request: &str) -> SessionRecord {
    let replies = ask(daemon, request);
    assert_eq!(replies.len(), 1, "one answer: {replies:?}");
    let r = &replies[0];
    assert_eq!(r.r#type, "session", "the answer is the session itself: {r:?}");
    assert_eq!(r.request_id.as_deref(), Some(request), "it answers this ask");
    let rec: SessionRecord = serde_json::from_str(&r.data).expect("a session record");
    assert_eq!(r.session_id.as_deref(), Some(rec.id.as_str()), "it names the session");
    rec
}

fn globals(daemon: &Daemon) -> Vec<SessionRecord> {
    daemon.records().into_iter().filter(|r| r.global).collect()
}

/// A frame sent to the composed daemon as the owner; it must not be refused (a save answers every client, not only the sender).
fn send(f: &Fixture, ty: &str, body: serde_json::Value) {
    let c = f.c.as_ref().expect("a composed daemon");
    c.request(&Envelope::new(ty, body.to_string()), true).unwrap_or_else(|e| panic!("{ty} was refused: {e}"));
}

#[tokio::test(flavor = "multi_thread")]
async fn the_assistant_is_one_global_projectless_session_in_its_own_folder() {
    let f = composed();
    let first = assistant(&f.daemon, "assistant:1");
    assert!(first.global, "marked as the installation's Assistant");
    assert_eq!(first.role.as_deref(), Some("assistant"));
    assert_eq!(first.project_id, None, "it belongs to no project");
    assert_eq!(first.state, SessionState::Idle, "made at rest: nothing runs until the first prompt");
    assert_eq!(first.provider, "stub", "the daemon's default harness when nothing is bound");
    assert_eq!(first.model, None, "and that harness's default model");
    let folder = f.dir.join("assistant");
    assert_eq!(std::path::Path::new(&first.working_dir), folder, "its own folder under the state dir");
    assert!(folder.is_dir(), "the folder exists");

    let again = assistant(&f.daemon, "assistant:2");
    assert_eq!(again.id, first.id, "one Assistant per installation");
    assert_eq!(globals(&f.daemon).len(), 1);
    assert_eq!(f.daemon.records().len(), 1, "the ask made no other session");
}

#[tokio::test(flavor = "multi_thread")]
async fn clients_asking_at_the_same_moment_are_given_the_same_assistant() {
    let f = composed();
    let n = 8;
    let gate = Arc::new(Barrier::new(n));
    let ids: Vec<String> = (0..n)
        .map(|i| {
            let (daemon, gate) = (f.daemon.clone(), gate.clone());
            std::thread::spawn(move || {
                gate.wait();
                assistant(&daemon, &format!("assistant:{i}")).id
            })
        })
        .collect::<Vec<_>>()
        .into_iter()
        .map(|t| t.join().expect("an ask finished"))
        .collect();
    assert!(ids.iter().all(|i| *i == ids[0]), "every client got the same session: {ids:?}");
    assert_eq!(globals(&f.daemon).len(), 1, "two clients can never make two Assistants");
    assert_eq!(f.daemon.records().len(), 1);
}

#[tokio::test(flavor = "multi_thread")]
async fn the_assistant_runs_on_the_binding_of_its_role() {
    let f = composed();
    send(&f, "role_binding_set", json!({"roleId": "assistant", "provider": "stub", "model": "model-one", "effort": "high"}));
    let rec = assistant(&f.daemon, "assistant:1");
    assert_eq!((rec.provider.as_str(), rec.model.as_deref(), rec.effort.as_deref()), ("stub", Some("model-one"), Some("high")));
}

#[tokio::test(flavor = "multi_thread")]
async fn the_setting_names_the_role_the_assistant_plays() {
    let f = composed();
    send(&f, "role_binding_set", json!({"roleId": "assistant", "provider": "stub", "model": "not-this-one"}));
    send(&f, "role_binding_set", json!({"roleId": "automation", "provider": "stub", "model": "the-automation-model"}));
    send(&f, "settings_set", json!({"key": "assistant.role", "value": "automation"}));
    let rec = assistant(&f.daemon, "assistant:1");
    assert_eq!(rec.role.as_deref(), Some("automation"), "the role comes from the setting");
    assert_eq!(rec.model.as_deref(), Some("the-automation-model"), "and its binding decides the model");
    assert!(rec.global);
}

#[tokio::test(flavor = "multi_thread")]
async fn a_binding_naming_a_harness_this_daemon_does_not_run_is_refused_with_the_reason() {
    let f = bare();
    f.daemon.set_assistant_spec(Arc::new(|| AssistantSpec { provider: Some("gone".into()), ..Default::default() }));
    let replies = ask(&f.daemon, "assistant:1");
    assert_eq!(replies.len(), 1);
    let refusal = &replies[0];
    assert_eq!(refusal.r#type, "error", "{refusal:?}");
    assert_eq!(refusal.request_id.as_deref(), Some("assistant:1"), "the refusal answers this ask");
    assert!(refusal.data.starts_with("assistant refused:"), "{}", refusal.data);
    assert!(refusal.data.contains("gone") && refusal.data.contains("stub"), "it names the harness asked for and the ones this daemon runs: {}", refusal.data);
    assert!(f.daemon.records().is_empty(), "a refused ask leaves no session behind, and none on another harness");
}

#[tokio::test(flavor = "multi_thread")]
async fn a_plain_new_session_with_the_assistant_role_stays_an_ordinary_session() {
    let f = bare();
    let plain = f.daemon.launch(NewOpts { role: Some("assistant".into()), ..Default::default() }).unwrap();
    assert!(globals(&f.daemon).is_empty(), "`new` never makes the installation's Assistant");
    let rec = assistant(&f.daemon, "assistant:1");
    assert_ne!(rec.id, plain, "the Assistant is its own session");
    let ids: Vec<_> = f.daemon.records().into_iter().map(|r| (r.id, r.global)).collect();
    assert!(ids.contains(&(plain, false)) && ids.contains(&(rec.id, true)), "{ids:?}");
}

#[tokio::test(flavor = "multi_thread")]
async fn the_assistant_is_the_same_session_after_a_restart() {
    let f = composed();
    let first = assistant(&f.daemon, "assistant:1");
    f.daemon.flush();
    let again = Daemon::new(Arc::new(Stub::default()), Config::new(f.dir.clone(), f.dir.clone())).unwrap();
    assert_eq!(globals(&again).iter().map(|r| r.id.clone()).collect::<Vec<_>>(), vec![first.id.clone()], "it is loaded as the Assistant");
    assert_eq!(assistant(&again, "assistant:2").id, first.id, "and asking for it makes no second one");
}

#[tokio::test(flavor = "multi_thread")]
async fn the_model_of_the_assistant_can_be_switched_but_not_its_role() {
    let f = bare();
    let id = assistant(&f.daemon, "assistant:1").id;
    let swap = |role: Option<&str>| SessionSwap { session_id: id.clone(), provider: "stub".into(), model: Some("model-two".into()), effort: None, role: role.map(String::from) };
    f.daemon.swap(swap(None)).expect("a model switch is allowed");
    assert_eq!(f.daemon.record(&id).unwrap().model.as_deref(), Some("model-two"));
    let refused = f.daemon.swap(swap(Some("backend"))).unwrap_err();
    assert!(refused.contains("fixed"), "{refused}");
    assert_eq!(f.daemon.record(&id).unwrap().role.as_deref(), Some("assistant"));
}
