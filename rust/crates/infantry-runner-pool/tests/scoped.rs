// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! RR-13: runner-scoped frames. A runner-mode link served on port 0 answers `runner_link_frame` through its own `Ask` (a
//! fake unit set with its own "vault" and "prefix"); the orchestrator reaches it through `runner_frame`.
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit};
use infantry_runner_link::pairing::BearerToken;
use infantry_runner_link::process::ProcessBackend;
use infantry_runner_pool::{Ask, FrameBackend, RunnerFrameUnit, RunnerPool, RunnerServe, ServeOptions, RUNNER_FRAME};
use infantry_runner_source::{FnScope, RunnerEntry, RunnerRegistry, LOCAL};
use std::path::Path;
use std::sync::{Arc, Mutex};

/// One daemon's frame set: `harness_install_status` names its prefix, `providers` is fixed text, `secret_set` writes `vault`.
#[derive(Clone)]
struct Fake {
    prefix: String,
    vault: Arc<Mutex<Vec<String>>>,
    asked: Arc<Mutex<Vec<String>>>,
}

impl Fake {
    fn new(prefix: &str) -> Fake {
        Fake { prefix: prefix.into(), vault: Default::default(), asked: Default::default() }
    }

    fn ask(&self) -> Ask {
        let me = self.clone();
        Arc::new(move |frame: &str, data: &str| {
            me.asked.lock().unwrap().push(frame.to_string());
            match frame {
                "harness_install_status" => Ok(("harness_install_status".into(), format!("{{\"prefix\":\"{}\"}}", me.prefix))),
                "providers" => Ok(("providers".into(), "{\"providers\":[\"pi\"]}".into())),
                "secret_set" => {
                    me.vault.lock().unwrap().push(data.to_string());
                    Ok(("secret_state".into(), "{\"ok\":true}".into()))
                }
                other => Err(format!("{other} is not wired in this test")),
            }
        })
    }
}

fn opts() -> ServeOptions {
    ServeOptions {
        bind_host: "127.0.0.1".into(),
        port: 0,
        name: Some("box".into()),
        public_host: None,
        public_port: None,
        advertise_hosts: vec![],
        allow_projects: "*".into(),
        allow_harnesses: "*".into(),
        app_version: "0.0.0-test".into(),
        allow_plain: false,
    }
}

fn start_runner(dir: &Path, fake: &Fake) -> Arc<RunnerServe> {
    let inner = ProcessBackend::new(Arc::new(infantry_runner_local::LocalRunner::default()));
    RunnerServe::start(dir, Box::new(FrameBackend::new(Box::new(inner), fake.ask())), opts()).unwrap()
}

fn orchestrator(tmp: &Path, local: &Fake) -> (Arc<RunnerPool>, RunnerFrameUnit) {
    let pool = RunnerPool::open(&tmp.join("orch"));
    let local_entry = RunnerEntry {
        name: LOCAL.into(),
        kind: "embedded".into(),
        runner: Arc::new(infantry_runner_local::LocalRunner::default()),
        scope: Arc::new(infantry_runner_source::NoScope(LOCAL.into())),
    };
    let registry = RunnerRegistry::new(local_entry);
    let ask = local.ask();
    registry.set_local_scope(Arc::new(FnScope(Box::new(move |f, d| ask(f, d)))));
    registry.add_source(pool.clone());
    (pool, RunnerFrameUnit { registry })
}

fn pair(pool: &RunnerPool, rs: &RunnerServe) {
    let t = BearerToken::parse(&rs.bearer_text().unwrap()).unwrap();
    let url = format!("wss://127.0.0.1:{}", rs.port());
    pool.add(&url, &t.secret, t.fingerprint.as_deref(), None, "orch").unwrap();
}

fn call(u: &RunnerFrameUnit, data: serde_json::Value) -> Envelope {
    let env = Envelope::new(RUNNER_FRAME, data.to_string());
    u.handle(&env, &FrameCtx { conn_id: 7, owner: true, bus: infantry_core::frames::EventBus::new(16) }).unwrap().remove(0)
}

/// The refusal text of a frame the orchestrator will not answer.
fn refused(u: &RunnerFrameUnit, data: serde_json::Value) -> String {
    let env = Envelope::new(RUNNER_FRAME, data.to_string());
    u.handle(&env, &FrameCtx { conn_id: 7, owner: true, bus: infantry_core::frames::EventBus::new(16) }).unwrap_err().0
}

fn tmp(tag: &str) -> std::path::PathBuf {
    std::env::temp_dir().join(format!("rr13-{tag}-{}", uuid::Uuid::new_v4()))
}

#[test]
fn local_answers_in_process_and_equals_a_direct_ask() {
    let t = tmp("local");
    let local = Fake::new("/orch/harness-tools");
    let (_pool, unit) = orchestrator(&t, &local);
    let direct = local.ask()("providers", "{}").unwrap();
    for body in [serde_json::json!({"runner": "local", "frame": "providers"}), serde_json::json!({"frame": "providers"})] {
        let a = call(&unit, body);
        assert_eq!((a.r#type.as_str(), a.data.as_str()), (direct.0.as_str(), direct.1.as_str()));
    }
    let _ = std::fs::remove_dir_all(t);
}

#[test]
fn a_remote_runner_answers_with_its_own_unit_and_a_secret_lands_only_in_its_vault() {
    let t = tmp("remote");
    let (local, remote) = (Fake::new("/orch/harness-tools"), Fake::new("/runner/harness-tools"));
    let rs = start_runner(&t.join("runner"), &remote);
    let (pool, unit) = orchestrator(&t, &local);
    pair(&pool, &rs);

    let a = call(&unit, serde_json::json!({"runner": "box", "frame": "harness_install_status"}));
    assert_eq!(a.r#type, "harness_install_status");
    assert!(a.data.contains("/runner/harness-tools") && !a.data.contains("/orch/"), "{}", a.data);

    let set = call(&unit, serde_json::json!({"runner": "box", "frame": "secret_set", "data": {"id": "k", "value": "sk-test-123"}}));
    assert_eq!(set.r#type, "secret_state", "{}", set.data);
    assert_eq!(remote.vault.lock().unwrap().len(), 1);
    assert!(local.vault.lock().unwrap().is_empty(), "the orchestrator's own vault must not change");
    assert!(!local.asked.lock().unwrap().iter().any(|f| f == "secret_set"));

    // The value is never in the runner's audit file (only op and frame name).
    let audit = std::fs::read_to_string(t.join("runner").join("runner-link").join("audit.jsonl")).unwrap();
    assert!(audit.contains("runner_link_frame:secret_set") && !audit.contains("sk-test-123"), "{audit}");
    let _ = std::fs::remove_dir_all(t);
}

#[test]
fn a_frame_off_the_allow_list_is_refused_by_name_on_the_runner_side() {
    let t = tmp("deny");
    let remote = Fake::new("/runner/harness-tools");
    let rs = start_runner(&t.join("runner"), &remote);
    let (pool, unit) = orchestrator(&t, &Fake::new("/orch"));
    pair(&pool, &rs);
    // Straight over the link (bypassing the orchestrator-side check): the runner refuses by name and asks nothing.
    let paired = pool.store().runner("box").unwrap();
    let client = infantry_runner_link::LinkClient::of(&paired, &pool.orchestrator_id());
    for frame in ["secret_reveal", "ticket_save", "secret_render", "secret_backup_export"] {
        let body = serde_json::json!({"frame": frame, "data": "{}"}).to_string();
        let a = client.call(infantry_runner_link::wire::FRAME, &body).unwrap();
        assert!(!a.ok() && a.data.contains(frame), "{frame}: {}", a.data);
    }
    assert!(remote.asked.lock().unwrap().is_empty(), "a refused frame never reaches the runner's units");
    // And the orchestrator refuses the same names before the link.
    let a = refused(&unit, serde_json::json!({"runner": "box", "frame": "secret_reveal"}));
    assert!(a.contains("secret_reveal"), "{a}");
    let _ = std::fs::remove_dir_all(t);
}

#[test]
fn an_offline_or_unknown_runner_is_refused_and_never_answered_locally() {
    let t = tmp("offline");
    let (local, remote) = (Fake::new("/orch/harness-tools"), Fake::new("/runner/harness-tools"));
    let rs = start_runner(&t.join("runner"), &remote);
    let (pool, unit) = orchestrator(&t, &local);
    pair(&pool, &rs);
    let mut moved = pool.store().runner("box").unwrap();
    moved.url = "wss://127.0.0.1:9".into();
    pool.store().save_runner(moved);
    let a = refused(&unit, serde_json::json!({"runner": "box", "frame": "providers"}));
    assert!(a.contains("runner 'box' is offline"), "{a}");
    let b = refused(&unit, serde_json::json!({"runner": "nope", "frame": "providers"}));
    assert!(b.contains("runner 'nope' is not paired"), "{b}");
    let c = refused(&unit, serde_json::json!({"runner": "any", "frame": "providers"}));
    assert!(c.contains("`any` is not a runner"), "{c}");
    assert!(local.asked.lock().unwrap().is_empty(), "a refused remote frame is never answered by `local`");
    let _ = std::fs::remove_dir_all(t);
}

#[test]
fn only_the_owner_may_ask() {
    let t = tmp("owner");
    let (_pool, unit) = orchestrator(&t, &Fake::new("/orch"));
    let env = Envelope::new(RUNNER_FRAME, r#"{"frame":"providers"}"#.to_string());
    assert!(unit.handle(&env, &FrameCtx { conn_id: 7, owner: false, bus: infantry_core::frames::EventBus::new(16) }).is_err());
    let _ = std::fs::remove_dir_all(t);
}
