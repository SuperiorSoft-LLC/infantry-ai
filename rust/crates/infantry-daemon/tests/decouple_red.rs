// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! DECOUPLE-1 (#45, EPIC #41): the daemon with NO orchestrator installed.
//!
//! Core and the agents half boot with a plugins folder that holds no plugin at all. Two agent sessions start through
//! a harness test double and exchange a message (A to B, then B's reply to A) over the mail frames. Nothing of the
//! orchestrator may be there: no board, ticket, epic, ticket-start, pod or worktree frame, no board tool in the tool
//! catalog, no board table in a fresh `infantry.db`, nothing of it in a client's connection burst, no `pm` role.
//!
//! RED until the moves in docs/rust-port/DECOUPLE-PLAN.md land: today core builds the orchestrator whatever is
//! installed (infantry-daemon/src/compose/mod.rs). The test lists every break, not only the first one. The move that
//! makes it pass removes the `#[ignore]`; from then on it is the EPIC #41 guard and stays green.
//!
//! Why the mail FRAMES and not the `infantry_message` tool: two places serve that tool today (core's fallback in
//! compose/agent_tools.rs and the Infantry_Chat plugin, which calls these frames), and the plan keeps one of them. The
//! frames are the agents half's contract either way.
//!
//! Its own test binary: it points `INFANTRY_PLUGINS_DIR` at an empty folder, a process-wide setting.
use infantry_daemon::harness::{Capabilities, EventSink, Harness, HarnessSession, OpenRequest, RunStart, Turn};
use infantry_daemon::sessions::{Config, Daemon, FrameUnits, NewOpts};
use infantry_protocol::Envelope;
use serde_json::{json, Value};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// The orchestrator's frames (wire names, the contract): none may have an owner on a daemon with no orchestrator.
const ORCHESTRATION_FRAMES: &[&str] = &[
    // the board (infantry-board/src/unit.rs FRAMES)
    "ticket_list",
    "ticket_get",
    "ticket_save",
    "ticket_move",
    "ticket_delete",
    "ticket_link",
    "ticket_unlink",
    "ticket_reorder",
    "epic_save",
    "epic_list",
    "epic_base",
    "archive_query",
    "archive_apply",
    // the board's work half (infantry-board/src/unit.rs ALL_FRAMES): ticket starts, agent queues, the done protocol
    "ticket_start",
    "ticket_start_next",
    "ticket_assign",
    "ticket_unassign",
    "agent_queue",
    "agent_switch",
    "ticket_done",
    // pods, the work DAG (infantry-daemon/src/pods.rs frames::ALL)
    "pod_submit",
    "pod_query",
    "pod_cancel",
    "pod_list",
    "pod_review",
    // worktrees and the repository ticket starts cut from (infantry-projects/src/units.rs WORKTREE_FRAMES)
    "worktrees",
    "worktree_remove",
    "repo",
];

/// An orchestrator tool (infantry-board/src/tools.rs and the pod tool in compose/agent_tools.rs).
fn is_orchestration_tool(name: &str) -> bool {
    ["infantry_ticket_", "infantry_epic_", "infantry_pod_"].iter().any(|p| name.starts_with(p))
        || ["infantry_board", "infantry_archive", "infantry_agent_queue", "infantry_agent_switch"].contains(&name)
}

/// An orchestrator table of the shared schema (tickets.sq, epics.sq, pods.sq, pipeline.sq, archive.sq, ticket_*.sq).
fn is_orchestration_table(name: &str) -> bool {
    ["ticket", "archived_ticket", "epic", "pod", "pipeline_", "node_dependencies", "node_attempts"].iter().any(|p| name.starts_with(p))
}

/// The harness test double: it records the prompt of every turn it is sent and never runs a process.
#[derive(Default, Clone)]
struct Recorder {
    prompts: Arc<Mutex<Vec<String>>>,
}

struct RecSession {
    prompts: Arc<Mutex<Vec<String>>>,
}

impl Harness for Recorder {
    fn name(&self) -> &str {
        "recorder"
    }
    fn capabilities(&self) -> Capabilities {
        Capabilities::default()
    }
    fn open(&self, _req: OpenRequest, _sink: EventSink) -> Result<Box<dyn HarnessSession>, String> {
        Ok(Box::new(RecSession { prompts: self.prompts.clone() }))
    }
}

impl HarnessSession for RecSession {
    fn send_turn(&mut self, turn: Turn) -> Result<RunStart, String> {
        self.prompts.lock().unwrap().push(turn.prompt);
        Ok(RunStart { pid: None, provider_session_id: None })
    }
    fn interrupt(&mut self) -> bool {
        false
    }
    fn close(&mut self, _force: bool) {}
    fn process_alive(&self) -> bool {
        false
    }
}

struct Cleanup(PathBuf);

impl Drop for Cleanup {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn frame(ty: &str, data: String, session: Option<&str>) -> Envelope {
    let mut e = Envelope::new(ty, data);
    e.session_id = session.map(str::to_string);
    e
}

/// `mail_send` as `from`; the sent message, or why not.
fn send(c: &infantry_daemon::compose::Composed, from: &str, body: Value) -> Result<Value, String> {
    let mut req = body;
    req["from"] = json!(from);
    let out = c.request(&frame("mail_send", req.to_string(), Some(from)), true)?;
    let reply = out.first().ok_or("mail_send answered nothing")?;
    if reply.r#type != "mail" {
        return Err(format!("mail_send answered `{}`: {}", reply.r#type, reply.data));
    }
    serde_json::from_str(&reply.data).map_err(|e| format!("mail_send answer is not a message ({e}): {}", reply.data))
}

/// The messages in `who`'s inbox.
fn inbox(c: &infantry_daemon::compose::Composed, who: &str) -> Result<Vec<Value>, String> {
    let out = c.request(&frame("mail_inbox", who.to_string(), Some(who)), true)?;
    let reply = out.first().ok_or("mail_inbox answered nothing")?;
    let list: Value = serde_json::from_str(&reply.data).map_err(|e| format!("mail_inbox answer is not JSON ({e})"))?;
    Ok(list.as_array().cloned().unwrap_or_default())
}

fn has_mail(list: &[Value], from: &str, body: &str) -> bool {
    list.iter().any(|m| m["from"].as_str() == Some(from) && m["body"].as_str().is_some_and(|b| b.contains(body)))
}

#[tokio::test(flavor = "multi_thread")]
#[ignore = "RED (DECOUPLE-1 #45): core still builds the orchestrator; see docs/rust-port/DECOUPLE-PLAN.md"]
async fn agents_run_and_talk_with_no_orchestrator_installed() {
    let tmp = std::env::temp_dir().join(format!("infantry-decouple-red-{}", uuid::Uuid::new_v4()));
    let _cleanup = Cleanup(tmp.clone());
    let (state, plugins) = (tmp.join("state"), tmp.join("plugins"));
    std::fs::create_dir_all(plugins.join("official")).unwrap();
    std::fs::create_dir_all(&state).unwrap();
    std::env::set_var("INFANTRY_PLUGINS_DIR", &plugins);
    std::env::remove_var("INFANTRY_ROLES_DIR");

    let rec = Recorder::default();
    let daemon = Daemon::new(Arc::new(rec.clone()), Config::new(state.clone(), tmp.clone())).unwrap();
    let c = infantry_daemon::compose::build(&daemon, &state).expect("core and the agents half compose with no plugin installed");
    let mut breaks: Vec<String> = Vec::new();

    // 1. No orchestrator frame has an owner (not even one that refuses it).
    for f in ORCHESTRATION_FRAMES {
        if let Some(unit) = c.registry.owner(f) {
            breaks.push(format!("frame `{f}` is served by core unit `{}`", unit.name()));
        }
    }

    // 2. A client's connection burst carries nothing of the orchestrator.
    for raw in c.hello() {
        let ty = serde_json::from_str::<Value>(&raw).ok().and_then(|v| v["type"].as_str().map(str::to_string)).unwrap_or_default();
        if ORCHESTRATION_FRAMES.contains(&ty.as_str()) {
            breaks.push(format!("the connection burst sends `{ty}`"));
        }
    }

    // 3. The tool catalog an agent sees holds no orchestrator tool.
    match c.request(&Envelope::new("plugin_tools", ""), true) {
        Ok(out) => {
            let list: Value = out.first().and_then(|e| serde_json::from_str(&e.data).ok()).unwrap_or(Value::Null);
            for t in list.as_array().cloned().unwrap_or_default() {
                let name = t["name"].as_str().unwrap_or_default();
                if is_orchestration_tool(name) {
                    breaks.push(format!("tool `{name}` is in the agents' tool catalog"));
                }
            }
        }
        Err(e) => breaks.push(format!("plugin_tools refused: {e}")),
    }

    // 4. A fresh infantry.db holds no orchestrator table.
    let tables: Vec<String> = c
        .db
        .read(|conn| {
            let mut st = conn.prepare("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")?;
            let rows = st.query_map([], |r| r.get::<_, String>(0))?;
            rows.collect()
        })
        .expect("list the tables of infantry.db");
    for t in tables.iter().filter(|t| is_orchestration_table(t)) {
        breaks.push(format!("table `{t}` was created in a fresh infantry.db"));
    }

    // 5. No PM role: the PM is the orchestrator's role; its catalog comes with the orchestrator, not with core. (No
    //    role unit at all is fine: roles are the knowledge plugin's.)
    if let Ok(out) = c.request(&Envelope::new("role_list", ""), true) {
        let roles: Value = out.first().and_then(|e| serde_json::from_str(&e.data).ok()).unwrap_or(Value::Null);
        if roles.as_array().is_some_and(|a| a.iter().any(|r| r["id"].as_str() == Some("pm"))) {
            breaks.push("the role list carries the `pm` role".into());
        }
    }

    // 6. The agents half: two sessions through the harness double; A's first turn reaches the harness.
    let a = daemon.launch(NewOpts { name: Some("alpha".into()), prompt: Some("hello".into()), ..Default::default() }).expect("session A starts");
    let b = daemon.launch(NewOpts { name: Some("bravo".into()), ..Default::default() }).expect("session B starts");
    let deadline = Instant::now() + Duration::from_secs(10);
    while !rec.prompts.lock().unwrap().iter().any(|p| p.contains("hello")) {
        if Instant::now() > deadline {
            breaks.push("session A's first turn never reached the harness".into());
            break;
        }
        std::thread::sleep(Duration::from_millis(20));
    }

    // 7. A writes to B, B reads it and answers, A reads the answer.
    match send(&c, &a, json!({"to": b, "subject": "ping", "body": "DECOUPLE ping from A"})) {
        Err(e) => breaks.push(format!("A to B: {e}")),
        Ok(ping) => {
            match inbox(&c, &b) {
                Ok(list) if has_mail(&list, &a, "DECOUPLE ping from A") => {}
                Ok(list) => breaks.push(format!("B's inbox does not hold A's message: {list:?}")),
                Err(e) => breaks.push(format!("B's inbox: {e}")),
            }
            let id = ping["id"].as_str().unwrap_or_default();
            match send(&c, &b, json!({"to": a, "subject": "pong", "body": "DECOUPLE pong from B", "replyTo": id})) {
                Err(e) => breaks.push(format!("B to A: {e}")),
                Ok(_) => match inbox(&c, &a) {
                    Ok(list) if has_mail(&list, &b, "DECOUPLE pong from B") => {}
                    Ok(list) => breaks.push(format!("A's inbox does not hold B's answer: {list:?}")),
                    Err(e) => breaks.push(format!("A's inbox: {e}")),
                },
            }
        }
    }

    assert!(
        breaks.is_empty(),
        "DECOUPLE-1 RED: {} break(s); the moves that clear them are in docs/rust-port/DECOUPLE-PLAN.md:\n  {}",
        breaks.len(),
        breaks.join("\n  ")
    );
}
