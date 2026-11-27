// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The trigger service over a real SQLite and fake ports (light: the rules TriggerGuardTest and TriggerPortsGuardTest
//! pin, plus restart honesty, webhooks, schedules and nudges).
use infantry_triggers_daemon::guard::Outcome;
use infantry_triggers_daemon::model::{TriggerSave, TriggerView};
use infantry_triggers_daemon::service::{ActionAnswer, MailRequest, Ports, Service, SessionInfo, StartAnswer};
use infantry_triggers_daemon::store::{Row, Sql, Store, V};
use infantry_triggers_daemon::webhook::{self, Ingress};
use serde_json::{json, Value};
use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

struct Lite(rusqlite::Connection, RefCell<HashMap<String, String>>);

fn p(v: &V) -> rusqlite::types::Value {
    match v {
        V::Null => rusqlite::types::Value::Null,
        V::Int(n) => rusqlite::types::Value::Integer(*n),
        V::Text(s) => rusqlite::types::Value::Text(s.clone()),
    }
}

impl Sql for Lite {
    fn exec(&self, sql: &str, params: &[V]) -> Result<u64, String> {
        self.0.execute(sql, rusqlite::params_from_iter(params.iter().map(p))).map(|n| n as u64).map_err(|e| e.to_string())
    }
    fn query(&self, sql: &str, params: &[V]) -> Result<Vec<Row>, String> {
        let mut st = self.0.prepare(sql).map_err(|e| e.to_string())?;
        let cols: Vec<String> = st.column_names().iter().map(|c| c.to_string()).collect();
        let rows = st
            .query_map(rusqlite::params_from_iter(params.iter().map(p)), |r| {
                let mut m = Row::new();
                for (i, c) in cols.iter().enumerate() {
                    let v = match r.get::<_, rusqlite::types::Value>(i)? {
                        rusqlite::types::Value::Integer(n) => V::Int(n),
                        rusqlite::types::Value::Text(s) => V::Text(s),
                        _ => V::Null,
                    };
                    m.insert(c.clone(), v);
                }
                Ok(m)
            })
            .map_err(|e| e.to_string())?;
        rows.collect::<Result<Vec<_>, _>>().map_err(|e| e.to_string())
    }
    fn kv_get(&self, key: &str) -> Option<String> {
        self.1.borrow().get(key).cloned()
    }
    fn kv_set(&self, key: &str, value: Option<&str>) {
        match value {
            Some(v) => self.1.borrow_mut().insert(key.into(), v.into()),
            None => self.1.borrow_mut().remove(key),
        };
    }
}

#[derive(Default)]
struct Log {
    now: Cell<i64>,
    seq: Cell<u64>,
    start_next: RefCell<Vec<Option<StartAnswer>>>,
    attention: RefCell<Vec<(String, String)>>,
    mails: RefCell<Vec<MailRequest>>,
    sessions: RefCell<Vec<SessionInfo>>,
    started_prompts: RefCell<Vec<String>>,
    actions: RefCell<Vec<String>>,
    skip_idle: Cell<bool>,
    unread: Cell<bool>,
    open_work: Cell<bool>,
}

#[derive(Clone)]
struct Fake(Rc<Log>);

impl Ports for Fake {
    fn now(&self) -> i64 {
        self.0.now.get()
    }
    fn new_id(&self) -> String {
        self.0.seq.set(self.0.seq.get() + 1);
        format!("{:08x}-id", self.0.seq.get())
    }
    fn start_next(&self, _agent: Option<&str>) -> Result<Option<StartAnswer>, String> {
        let mut q = self.0.start_next.borrow_mut();
        Ok(if q.is_empty() { None } else { q.remove(0) })
    }
    fn start_prompt(&self, agent_id: &str, _prompt: &str, _project: Option<&str>) -> Result<String, String> {
        self.0.started_prompts.borrow_mut().push(agent_id.into());
        Ok("s-new".into())
    }
    fn sessions(&self) -> Vec<SessionInfo> {
        self.0.sessions.borrow().clone()
    }
    fn send_mail(&self, m: &MailRequest) -> Result<(String, String), String> {
        self.0.mails.borrow_mut().push(m.clone());
        Ok((format!("m{}", self.0.mails.borrow().len()), m.thread_id.clone()))
    }
    fn mail_pending(&self, _mail: &str, _thread: Option<&str>) -> bool {
        true
    }
    fn attention(&self, kind: &str, _trigger: &str, outcome: &str, _detail: &str) {
        self.0.attention.borrow_mut().push((kind.into(), outcome.into()));
    }
    fn push(&self, _v: &TriggerView) {}
    fn push_all(&self, _all: &[TriggerView]) {}
    fn publish(&self, _name: &str, _payload: Value) {}
    fn log(&self, _line: &str) {}
    fn call_action(&self, kind: &str, _fire: Value) -> ActionAnswer {
        self.0.actions.borrow_mut().push(kind.into());
        if kind == "acme.notify" { ActionAnswer::Done } else { ActionAnswer::NoHandler }
    }
    fn skip_when_idle(&self) -> bool {
        self.0.skip_idle.get()
    }
    fn has_unread_mail(&self, _to: &str) -> bool {
        self.0.unread.get()
    }
    fn has_open_work(&self, _project: Option<&str>) -> bool {
        self.0.open_work.get()
    }
}

fn service() -> (Service<Lite, Fake>, Rc<Log>) {
    let log = Rc::new(Log::default());
    log.now.set(1_000_000);
    let repo = Store::open(Lite(rusqlite::Connection::open_in_memory().unwrap(), RefCell::new(HashMap::new()))).unwrap();
    let mut s = Service::new(repo, Fake(log.clone()));
    s.start();
    (s, log)
}

fn save(s: &mut Service<Lite, Fake>, v: Value) -> TriggerView {
    s.save(serde_json::from_value::<TriggerSave>(v).unwrap(), None).unwrap()
}

fn started(sid: &str) -> Option<StartAnswer> {
    Some(StartAnswer { reason: "started".into(), session_id: Some(sid.into()), detail: None })
}

#[test]
fn a_start_next_fire_runs_until_its_session_ends() {
    let (mut s, log) = service();
    let t = save(&mut s, json!({"name": "nightly", "coalesceMs": 0}));
    assert_eq!(t.state, "armed");
    log.start_next.borrow_mut().push(started("s1"));
    assert_eq!(s.fire(&t.id, "manual", None, None).unwrap().state, "running");
    s.on_run_ended("s1", Outcome::Succeeded);
    assert_eq!(s.get(&t.id).unwrap().state, "armed");
    let ev = s.events(&t.id).unwrap();
    assert_eq!(ev.len(), 1);
    assert_eq!(ev[0].disposition, "started");
}

#[test]
fn nothing_to_start_relabels_the_event_and_rearms() {
    let (mut s, _log) = service();
    let t = save(&mut s, json!({"name": "idle board", "coalesceMs": 0}));
    s.fire(&t.id, "manual", None, None).unwrap();
    assert_eq!(s.get(&t.id).unwrap().state, "armed");
    assert_eq!(s.events(&t.id).unwrap()[0].disposition, "nothing_to_start");
}

#[test]
fn the_ceiling_trips_the_trigger_and_raises_attention() {
    let (mut s, log) = service();
    let t = save(&mut s, json!({"name": "busy", "coalesceMs": 0, "ceilingRuns": 1}));
    s.fire(&t.id, "manual", None, None).unwrap();
    let again = s.fire(&t.id, "manual", None, None).unwrap();
    assert_eq!(again.state, "tripped");
    assert_eq!(log.attention.borrow()[0], ("ceiling".into(), "REFUSED_CEILING".into()));
    assert_eq!(s.fire(&t.id, "manual", None, None).unwrap().state, "tripped", "nothing re-arms it but arm");
    assert_eq!(s.arm(&t.id).unwrap().state, "armed");
}

#[test]
fn a_failed_run_trips() {
    let (mut s, log) = service();
    let t = save(&mut s, json!({"name": "flaky", "coalesceMs": 0}));
    log.start_next.borrow_mut().push(started("s9"));
    s.fire(&t.id, "manual", None, None).unwrap();
    s.on_run_ended("s9", Outcome::Failed);
    let after = s.get(&t.id).unwrap();
    assert_eq!(after.state, "tripped");
    assert!(after.tripped_reason.unwrap().contains("FAILED"));
}

#[test]
fn a_run_in_flight_at_restart_comes_back_tripped() {
    let (mut s, log) = service();
    let t = save(&mut s, json!({"name": "mid-run", "coalesceMs": 0}));
    log.start_next.borrow_mut().push(started("s2"));
    s.fire(&t.id, "manual", None, None).unwrap();
    let Service { repo, .. } = s;
    let s2 = Service::new(repo, Fake(log.clone()));
    let back = s2.get(&t.id).unwrap();
    assert_eq!(back.state, "tripped");
    assert_eq!(back.tripped_reason.as_deref(), Some("daemon restarted mid-run"));
}

#[test]
fn bad_requests_are_refused_by_name() {
    let (mut s, _log) = service();
    let e = s.save(serde_json::from_value(json!({"name": "x", "kind": "cron"})).unwrap(), None).unwrap_err();
    assert!(e.contains("not served"));
    let e = s.save(serde_json::from_value(json!({"name": "x", "kind": "schedule", "intervalMs": 1000})).unwrap(), None).unwrap_err();
    assert!(e.contains("at least 1 minute"));
    let e = s.save(serde_json::from_value(json!({"name": "x", "owner": "Mod"})).unwrap(), None).unwrap_err();
    assert!(e.contains("stamped"));
}

#[test]
fn a_schedule_fires_when_due() {
    let (mut s, log) = service();
    let t = save(&mut s, json!({"name": "hourly", "kind": "schedule", "intervalMs": 60_000, "coalesceMs": 0}));
    s.pump();
    assert!(s.events(&t.id).unwrap().is_empty());
    log.now.set(log.now.get() + 60_000);
    s.pump();
    let ev = s.events(&t.id).unwrap();
    assert_eq!((ev.len(), ev[0].source.as_str()), (1, "schedule"));
}

#[test]
fn a_prompt_nudge_mails_the_role_session_and_waits_while_pending() {
    let (mut s, log) = service();
    log.sessions.borrow_mut().push(SessionInfo { id: "s5".into(), role: Some("qa".into()), reachable: true, live: true, ..Default::default() });
    let t = save(&mut s, json!({"name": "ping qa", "coalesceMs": 0, "action": {"kind": "prompt", "agentId": "role:qa", "prompt": "status?"}}));
    s.fire(&t.id, "manual", None, None).unwrap();
    assert_eq!(log.mails.borrow()[0].to, "s5");
    assert_eq!(s.events(&t.id).unwrap()[0].disposition, "mailed");
    s.fire(&t.id, "manual", None, None).unwrap();
    assert_eq!(s.events(&t.id).unwrap()[1].disposition, "coalesced_pending");
    assert!(log.started_prompts.borrow().is_empty());
}

#[test]
fn a_webhook_needs_its_signature_and_a_replay_is_a_duplicate() {
    let (mut s, log) = service();
    let secret = "s".repeat(40);
    let t = save(&mut s, json!({"name": "hook", "kind": "webhook", "webhookSecret": secret, "coalesceMs": 0}));
    let mut ing = Ingress::default();
    let ts = log.now.get() / 1000;
    let body = br#"{"x":1}"#;
    let sig = webhook::sign(&secret, ts, "d1", body);
    assert_eq!(ing.deliver(&mut s, &t.id, Some("v1=00"), Some(&ts.to_string()), Some("d1"), body), webhook::Outcome::Rejected(401, webhook::UNAUTHORIZED.into()));
    assert!(matches!(ing.deliver(&mut s, &t.id, Some(&sig), Some(&ts.to_string()), Some("d1"), body), webhook::Outcome::Accepted(l) if l == "nothing_to_start"));
    assert_eq!(ing.deliver(&mut s, &t.id, Some(&sig), Some(&ts.to_string()), Some("d1"), body), webhook::Outcome::Accepted("duplicate".into()));
}

/// TriggerPortsGuardTest: the trigger logic reaches the daemon only through its ports; only lib.rs talks to the host.
#[test]
fn trigger_logic_reaches_the_daemon_only_through_its_ports() {
    let src = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("src");
    for f in ["service.rs", "guard.rs", "store.rs", "webhook.rs", "model.rs"] {
        let text = std::fs::read_to_string(src.join(f)).unwrap();
        for banned in ["host::", "infantry_plugin_sdk", "frames::request"] {
            assert!(!text.contains(banned), "{f} names `{banned}`: reach the daemon through service::Ports");
        }
    }
}

#[test]
fn a_plugin_action_kind_runs_its_handler() {
    let (mut s, log) = service();
    let t = save(&mut s, json!({"name": "ping", "coalesceMs": 0, "action": {"kind": "acme.notify"}}));
    s.fire(&t.id, "manual", None, None).unwrap();
    assert_eq!(log.actions.borrow()[0], "acme.notify");
    assert_eq!(s.events(&t.id).unwrap()[0].disposition, "delivered");
    let u = save(&mut s, json!({"name": "nobody", "coalesceMs": 0, "action": {"kind": "gone.kind"}}));
    s.fire(&u.id, "manual", None, None).unwrap();
    assert_eq!(s.events(&u.id).unwrap()[0].disposition, "refused_unknown_action");
}

fn idle_setup() -> (Service<Lite, Fake>, Rc<Log>, TriggerView) {
    let (mut s, log) = service();
    log.skip_idle.set(true);
    log.sessions.borrow_mut().push(SessionInfo { id: "pm1".into(), role: Some("pm".into()), project_id: Some("p1".into()), reachable: true, live: true, ..Default::default() });
    let t = save(&mut s, json!({"name": "pm wake", "coalesceMs": 0, "projectId": "p1", "action": {"kind": "prompt", "agentId": "role:pm", "prompt": "check board"}}));
    (s, log, t)
}

#[test]
fn token13_a_prompt_trigger_is_skipped_when_the_project_is_idle() {
    let (mut s, log, t) = idle_setup();
    s.fire(&t.id, "manual", None, None).unwrap();
    assert!(log.mails.borrow().is_empty());
    assert!(log.started_prompts.borrow().is_empty());
    let e = &s.events(&t.id).unwrap()[0];
    assert_eq!(e.disposition, "skipped_idle");
    assert_eq!(e.digest.as_deref(), Some("idle project"));
}

#[test]
fn token13_a_prompt_trigger_delivers_with_an_in_progress_ticket_or_unread_mail() {
    let (mut s, log, t) = idle_setup();
    log.open_work.set(true);
    s.fire(&t.id, "manual", None, None).unwrap();
    assert_eq!(log.mails.borrow()[0].to, "pm1");
    assert_eq!(s.events(&t.id).unwrap()[0].disposition, "mailed");
    let (mut s, log, t) = idle_setup();
    log.unread.set(true);
    s.fire(&t.id, "manual", None, None).unwrap();
    assert_eq!(log.mails.borrow().len(), 1);
}
