// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST PARITY (agent registry edits and live roster): `agent_save`, `agent_delete`, `agent_swap`, `agent_roster` and
//! `agent_switch` answer on a fresh state dir (none falls to "does not serve that frame yet"), and the
//! `infantry_agent_*` tools are in the catalog and run.
use infantry_daemon::board::Board;
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::sessions::{Config, Daemon, NewOpts};
use infantry_protocol::Envelope;
use infantry_scripting::host::{ScriptHost, ToolInvoker};
use serde_json::{json, Value};
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
    let dir = std::env::temp_dir().join(format!("infantry-daemon-agents-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(dir.clone(), dir.clone())).unwrap();
    let c = infantry_daemon::compose::build(&daemon, &dir).unwrap();
    Fixture { dir, daemon, c }
}

fn ask(f: &Fixture, ty: &str, body: Value) -> Result<Vec<Envelope>, String> {
    f.c.request(&Envelope::new(ty, body.to_string()), true)
}

fn text(r: Result<Vec<Envelope>, String>) -> Envelope {
    r.expect("the frame answers").into_iter().next().expect("one answer")
}

fn agent(f: &Fixture, name: &str, role: &str) -> Value {
    let a = text(ask(f, "agent_save", json!({"name": name, "role": role, "projectId": "p1", "provider": "stub"})));
    assert_eq!(a.r#type, "agent");
    serde_json::from_str(&a.data).unwrap()
}

#[tokio::test(flavor = "multi_thread")]
async fn agent_save_creates_normalises_and_refuses() {
    let f = fixture();
    let a = agent(&f, "  Ada ", "Backend");
    assert_eq!(a["name"], "Ada");
    assert_eq!(a["role"], "backend");
    assert!(a["id"].as_str().is_some_and(|i| !i.is_empty()));
    // A known agent keeps its project and cannot move.
    let moved = ask(&f, "agent_save", json!({"id": a["id"], "name": "Ada", "role": "backend", "projectId": "p2"})).unwrap_err();
    assert!(moved.contains("cannot move"), "{moved}");
    // The same id with a blank project keeps p1.
    let again = text(ask(&f, "agent_save", json!({"id": a["id"], "name": "Ada B", "role": "backend", "effort": "high"})));
    let again: Value = serde_json::from_str(&again.data).unwrap();
    assert_eq!((again["name"].as_str(), again["projectId"].as_str(), again["effort"].as_str()), (Some("Ada B"), Some("p1"), Some("high")));
    for bad in [json!({"name": "", "role": "qa"}), json!({"name": "X", "role": "Not A Role"}), json!({"name": "X", "role": "qa", "effort": "ludicrous"}), json!({"name": "X", "role": "qa", "provider": "nope"})] {
        let e = ask(&f, "agent_save", bad).unwrap_err();
        assert!(e.starts_with("agent_save refused:") && !e.contains("does not serve"), "{e}");
    }
}

#[tokio::test(flavor = "multi_thread")]
async fn agent_delete_answers_the_list_and_refuses_an_unknown_id() {
    let f = fixture();
    let a = agent(&f, "Bo", "qa");
    let keep = agent(&f, "Cy", "qa");
    let list = text(f.c.request(&Envelope::new("agent_delete", a["id"].as_str().unwrap()), true));
    assert_eq!(list.r#type, "agents");
    let ids: Vec<Value> = serde_json::from_str::<Vec<Value>>(&list.data).unwrap().into_iter().map(|p| p["id"].clone()).collect();
    assert_eq!(ids, vec![keep["id"].clone()]);
    let e = f.c.request(&Envelope::new("agent_delete", a["id"].as_str().unwrap()), true).unwrap_err();
    assert!(e.contains("no agent profile"), "{e}");
}

#[tokio::test(flavor = "multi_thread")]
async fn agent_swap_changes_the_executor_and_pushes_agent_swapped() {
    let f = fixture();
    let a = agent(&f, "Di", "backend");
    let mut rx = f.c.bus.subscribe();
    let out = ask(&f, "agent_swap", json!({"agentId": a["id"], "provider": "stub", "model": "m2", "effort": "low"})).unwrap();
    assert!(out.is_empty(), "the answer is the push to every client");
    let pushed = std::iter::from_fn(|| rx.try_recv().ok()).find(|e| e.r#type == "agent_swapped").expect("agent_swapped is pushed");
    let sw: Value = serde_json::from_str(&pushed.data).unwrap();
    assert_eq!(sw["agent"]["model"], "m2");
    assert_eq!(sw["session"]["outcome"], "none", "an agent with no session has nothing to move");
    assert_eq!(Board::new(&f.dir).agent(a["id"].as_str().unwrap()).unwrap().unwrap().effort.as_deref(), Some("low"));
    // A refusal writes nothing.
    let e = ask(&f, "agent_swap", json!({"agentId": a["id"], "provider": "stub", "model": "m3", "effort": "ludicrous"})).unwrap_err();
    assert!(e.starts_with("agent_swap refused:"), "{e}");
    assert_eq!(Board::new(&f.dir).agent(a["id"].as_str().unwrap()).unwrap().unwrap().model.as_deref(), Some("m2"));
    let e = ask(&f, "agent_swap", json!({"agentId": "nobody", "provider": "stub"})).unwrap_err();
    assert!(e.contains("no agent profile"), "{e}");
    let e = ask(&f, "agent_swap", json!({"agentId": a["id"], "provider": "stub", "session": "sideways"})).unwrap_err();
    assert!(e.contains("not one of"), "{e}");
}

#[tokio::test(flavor = "multi_thread")]
async fn agent_roster_lists_the_projects_live_agents_only() {
    let f = fixture();
    let a = agent(&f, "Ed", "backend");
    let other = text(ask(&f, "agent_save", json!({"name": "Fay", "role": "qa", "projectId": "p2"})));
    let other: Value = serde_json::from_str(&other.data).unwrap();
    let opts = |agent: &Value, project: &str| NewOpts { agent_id: agent["id"].as_str().map(String::from), project_id: Some(project.into()), role: agent["role"].as_str().map(String::from), ..Default::default() };
    let sid = f.daemon.launch(opts(&a, "p1")).unwrap();
    f.daemon.launch(opts(&other, "p2")).unwrap();
    let r = text(ask(&f, "agent_roster", json!({"projectId": "p1"})));
    assert_eq!(r.r#type, "roster");
    let rows: Vec<Value> = serde_json::from_str(&r.data).unwrap();
    assert_eq!(rows.len(), 1, "never another project's agent: {rows:?}");
    assert_eq!((rows[0]["agentId"].as_str(), rows[0]["name"].as_str(), rows[0]["status"].as_str()), (a["id"].as_str(), Some("Ed"), Some("IDLE")));
    assert_eq!(rows[0]["sessionId"], sid);
    let e = ask(&f, "agent_roster", json!({"projectId": " "})).unwrap_err();
    assert!(e.contains("projectId is blank"), "{e}");
}

#[tokio::test(flavor = "multi_thread")]
async fn agent_switch_is_served_by_the_board() {
    let f = fixture();
    let e = ask(&f, "agent_switch", json!({"agentId": "nobody", "ticketId": "a1b2c3d4"})).unwrap_err();
    assert!(!e.contains("does not serve") && e.contains("agent_switch refused"), "{e}");
    let e = ask(&f, "agent_switch", json!({"agentId": "x"})).unwrap_err();
    assert!(e.contains("agent_switch"), "a malformed payload is refused by the frame: {e}");
}

#[tokio::test(flavor = "multi_thread")]
async fn the_agent_tools_are_in_the_catalog_and_run() {
    let f = fixture();
    let names: Vec<String> = f.c.tools.catalog().into_iter().map(|t| t.name).collect();
    for t in ["infantry_agent_list", "infantry_agent_get", "infantry_agent_enable", "infantry_agent_disable", "infantry_agent_queue", "infantry_agent_switch"] {
        assert!(names.iter().any(|n| n == t), "{t} is in the catalog");
    }
    let owner = ToolInvoker { owner: true, ..Default::default() };
    let none = serde_json::Map::new();
    assert!(!f.c.tools.invoke("infantry_agent_list", &none, &owner).is_error);
    let id = serde_json::Map::from_iter([("id".to_string(), json!("no-such-agent"))]);
    assert!(f.c.tools.invoke("infantry_agent_get", &id, &owner).is_error, "an unknown agent is an error result");
    // Disabling is the user's override: an agent caller is refused before anything is looked up.
    let agent = ToolInvoker { agent: true, session_id: Some("s1".into()), ..Default::default() };
    let r = f.c.tools.invoke("infantry_agent_disable", &id, &agent);
    assert!(r.is_error && format!("{r:?}").contains("may not"), "{r:?}");
    // The queue tool answers through the board (an unknown agent is a refusal, not a missing tool).
    let q = serde_json::Map::from_iter([("agentId".to_string(), json!("nobody"))]);
    let r = f.c.tools.invoke("infantry_agent_queue", &q, &owner);
    assert!(r.is_error && !format!("{r:?}").to_lowercase().contains("unknown tool"), "{r:?}");
}
