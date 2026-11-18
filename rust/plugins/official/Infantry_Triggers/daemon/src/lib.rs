// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry_Triggers, daemon half (a WASM component on infantry-plugin-sdk; Kotlin `TriggersDaemonPlugin` is the spec):
//!
//! - the `trigger_*` frames (`trigger_list` -> `triggers`, `trigger_events` -> `trigger_events`; a change is pushed to
//!   every client as `trigger`, a delete as `triggers`, and that push is the answer);
//! - the `triggers.list|save|delete|fire` capabilities as the tools `triggers_list|save|delete|fire`;
//! - `POST /hooks/{id}` (a public route; the per-hook HMAC is its authentication);
//! - ship triggers on `ticket.moved` into done; run ends on `session.run_ended`; the pump on the daemon's `clock.tick`
//!   (the WIT has no timer, so the host's generic tick drives schedules, one-shots and coalescing windows);
//! - `triggersTripped` through the `status` hook; orphaned module triggers pruned at start and on `modules.changed`.
//!
//! Every daemon fact comes through a port ([`service::Ports`]) answered by the `frames` grant: `ticket_start_next`,
//! `new` (an unattended prompt session), `sessions`, `mail_send`, `mail_thread`, `attention_raise`, `plugins_op`.
pub mod guard;
pub mod model;
pub mod service;
pub mod store;
pub mod webhook;

use guard::Outcome;
use infantry_plugin_sdk::types::{CallContext, Caller, Frame, HttpResponse, NamedEvent, RouteRequest, SqlValue};
use infantry_plugin_sdk::{export_plugin, host, Plugin};
use model::{TriggerSave, TriggerView};
use serde_json::{json, Value};
use service::{MailRequest, Ports, Service, SessionInfo, StartAnswer};
use std::collections::{HashMap, HashSet};
use std::sync::Mutex;
use store::{Row, Sql, Store, V};

/// How long one port call into the daemon may take.
pub const FRAME_TIMEOUT_MS: u32 = 15_000;
pub const AT_MAX_LATE_KEY: &str = "triggers.at.maxLateMs";
pub const TRIPPED_FIELD: &str = "triggersTripped";
pub const HOOK_ROUTE_PREFIX: &str = "/hooks/";
/// The bus events this half listens to (the first is the host's generic clock).
pub const EV_TICK: &str = "clock.tick";
pub const EV_TICKET_MOVED: &str = "ticket.moved";
pub const EV_RUN_ENDED: &str = "session.run_ended";
pub const EV_MODULES_CHANGED: &str = "modules.changed";

// ---- the plugin's storage, through the host -------------------------------------------------------------------

pub struct HostSql;

fn sql_in(v: &V) -> SqlValue {
    match v {
        V::Null => SqlValue::Null,
        V::Int(n) => SqlValue::Integer(*n),
        V::Text(s) => SqlValue::Text(s.clone()),
    }
}

impl Sql for HostSql {
    fn exec(&self, sql: &str, params: &[V]) -> Result<u64, String> {
        host::storage::execute(sql, &params.iter().map(sql_in).collect::<Vec<_>>())
    }
    fn query(&self, sql: &str, params: &[V]) -> Result<Vec<Row>, String> {
        let rows = host::storage::query(sql, &params.iter().map(sql_in).collect::<Vec<_>>())?;
        Ok(rows
            .rows
            .into_iter()
            .map(|r| {
                rows.columns
                    .iter()
                    .cloned()
                    .zip(r.into_iter().map(|c| match c {
                        SqlValue::Integer(n) => V::Int(n),
                        SqlValue::Real(f) => V::Int(f as i64),
                        SqlValue::Text(s) => V::Text(s),
                        SqlValue::Blob(b) => V::Text(String::from_utf8_lossy(&b).into_owned()),
                        SqlValue::Null => V::Null,
                    }))
                    .collect::<HashMap<_, _>>()
            })
            .collect())
    }
    fn kv_get(&self, key: &str) -> Option<String> {
        host::storage::kv_get(key).ok().flatten()
    }
    fn kv_set(&self, key: &str, value: Option<&str>) {
        let _ = match value {
            Some(v) => host::storage::kv_set(key, v),
            None => host::storage::kv_delete(key).map(|_| ()),
        };
    }
}

// ---- the daemon, through the frames grant ---------------------------------------------------------------------

pub struct HostPorts {
    seq: std::cell::Cell<u64>,
}

fn request(ty: &str, data: &str) -> Result<Frame, String> {
    host::frames::request(ty, data, None, FRAME_TIMEOUT_MS)
}

fn s(v: &Value, k: &str) -> Option<String> {
    v.get(k).and_then(Value::as_str).map(str::trim).filter(|x| !x.is_empty()).map(str::to_string)
}

/// The board's answer to `ticket_start_next` (a `ticket` frame): the hand-out's reason and session.
pub fn start_answer(ticket_json: &str) -> Option<StartAnswer> {
    let t: Value = serde_json::from_str(ticket_json).ok()?;
    let start = t.get("start")?;
    Some(StartAnswer { reason: s(start, "reason").unwrap_or_default(), session_id: s(&t, "sessionId"), detail: s(start, "detail") })
}

/// A `sessions` record as a nudge target.
pub fn session_info(r: &Value) -> Option<SessionInfo> {
    let state = s(r, "state").unwrap_or_default().to_ascii_uppercase();
    let live = state == "BUSY" || state == "IDLE";
    Some(SessionInfo {
        id: s(r, "id")?,
        agent_id: s(r, "agentId"),
        role: s(r, "role"),
        project_id: s(r, "projectId"),
        spawned_at: r.get("spawnedAt").and_then(Value::as_i64).unwrap_or(0),
        reachable: live || s(r, "providerSessionId").is_some(),
        live,
    })
}

impl Ports for HostPorts {
    fn now(&self) -> i64 {
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
    }
    fn new_id(&self) -> String {
        use std::hash::{BuildHasher, Hasher};
        self.seq.set(self.seq.get() + 1);
        let mut out = String::new();
        for i in 0..2u64 {
            let mut h = std::collections::hash_map::RandomState::new().build_hasher();
            h.write_u64(self.seq.get() ^ (i << 32));
            h.write_i64(self.now());
            out.push_str(&format!("{:016x}", h.finish()));
        }
        out
    }
    fn start_next(&self, agent: Option<&str>) -> Result<Option<StartAnswer>, String> {
        let mut body = json!({ "startedBy": "trigger" });
        if let Some(a) = agent {
            body["agentId"] = json!(a);
        }
        // Anything but a hand-out (nothing startable, a refusal) is "nothing was started", as in Kotlin.
        match request("ticket_start_next", &body.to_string()) {
            Ok(f) if f.type_ == "ticket" => Ok(start_answer(&f.data)),
            Ok(_) | Err(_) => Ok(None),
        }
    }
    fn start_prompt(&self, agent_id: &str, prompt: &str, project_id: Option<&str>) -> Result<String, String> {
        let mut body = json!({ "prompt": prompt, "unattended": true, "projectId": project_id });
        match agent_id.strip_prefix("role:") {
            Some(role) => body["role"] = json!(role),
            None => body["agentId"] = json!(agent_id),
        }
        let f = request("new", &body.to_string())?;
        let v: Value = serde_json::from_str(&f.data).unwrap_or(Value::Null);
        s(&v, "id").ok_or_else(|| format!("the daemon started no session ({})", f.type_))
    }
    fn sessions(&self) -> Vec<SessionInfo> {
        request("sessions", "")
            .ok()
            .and_then(|f| serde_json::from_str::<Vec<Value>>(&f.data).ok())
            .unwrap_or_default()
            .iter()
            .filter_map(session_info)
            .collect()
    }
    fn send_mail(&self, m: &MailRequest) -> Result<(String, String), String> {
        let body = json!({ "from": m.from, "to": m.to, "subject": m.subject, "body": m.body, "threadId": m.thread_id });
        let f = request("mail_send", &body.to_string())?;
        let v: Value = serde_json::from_str(&f.data).unwrap_or(Value::Null);
        if f.type_ == "mail" {
            Ok((s(&v, "id").unwrap_or_default(), s(&v, "threadId").unwrap_or_else(|| m.thread_id.clone())))
        } else {
            Err(s(&v, "reason").unwrap_or(f.data))
        }
    }
    fn mail_pending(&self, mail_id: &str, thread_id: Option<&str>) -> bool {
        let Some(thread) = thread_id else { return false };
        let Ok(f) = request("mail_thread", thread) else { return false };
        serde_json::from_str::<Vec<Value>>(&f.data)
            .unwrap_or_default()
            .iter()
            .find(|m| m.get("id").and_then(Value::as_str) == Some(mail_id))
            .is_some_and(|m| m.get("deliveredAt").is_none_or(Value::is_null) && m.get("failureReason").is_none_or(Value::is_null))
    }
    fn attention(&self, kind: &str, trigger_id: &str, outcome: &str, detail: &str) {
        let body = json!({ "kind": kind, "triggerId": trigger_id, "outcome": outcome, "detail": detail });
        if let Err(e) = request("attention_raise", &body.to_string()) {
            host::log::warn(&format!("trip attention failed: {e}"));
        }
    }
    fn push(&self, view: &TriggerView) {
        host::events::broadcast("trigger", &serde_json::to_string(view).unwrap_or_default());
    }
    fn push_all(&self, all: &[TriggerView]) {
        host::events::broadcast("triggers", &serde_json::to_string(all).unwrap_or_default());
    }
    fn publish(&self, name: &str, payload: Value) {
        let _ = host::events::publish(name, &payload.to_string(), None);
    }
    fn log(&self, line: &str) {
        host::log::info(line);
    }
    fn at_max_late_ms(&self) -> i64 {
        host::config::get(AT_MAX_LATE_KEY).and_then(|v| v.trim().parse::<f64>().ok()).map(|f| (f as i64).max(0)).unwrap_or(service::DEFAULT_AT_MAX_LATE_MS)
    }
}

// ---- the plugin ------------------------------------------------------------------------------------------------

struct State {
    svc: Service<HostSql, HostPorts>,
    ingress: webhook::Ingress,
}

static STATE: Mutex<Option<State>> = Mutex::new(None);

fn with<T>(f: impl FnOnce(&mut State) -> Result<T, String>) -> Result<T, String> {
    let mut g = STATE.lock().unwrap_or_else(|e| e.into_inner());
    let st = g.as_mut().ok_or("Infantry_Triggers is not started")?;
    f(st)
}

/// The modules loaded now (empty when the host gives no catalog: then nothing is pruned).
fn loaded_modules() -> HashSet<String> {
    request("plugins_op", r#"{"op":"status"}"#)
        .ok()
        .and_then(|f| serde_json::from_str::<Vec<Value>>(&f.data).ok())
        .unwrap_or_default()
        .iter()
        .filter(|m| m.get("enabled").and_then(Value::as_bool) == Some(true))
        .filter_map(|m| s(m, "id"))
        .collect()
}

fn prune(st: &mut State, loaded: HashSet<String>) {
    if !loaded.is_empty() {
        st.svc.prune_orphans(&loaded);
    }
}

fn json_of<T: serde::Serialize>(v: &T) -> String {
    serde_json::to_string(v).unwrap_or_default()
}

/// The frame data's trigger id (the UI sends it bare; a JSON string or `{"id"}` is read too).
fn id_of(data: &str) -> String {
    let t = data.trim();
    match serde_json::from_str::<Value>(t) {
        Ok(Value::String(s)) => s,
        Ok(v @ Value::Object(_)) => s(&v, "id").unwrap_or_default(),
        _ => t.to_string(),
    }
}

pub struct Triggers;

impl Plugin for Triggers {
    fn start() -> Result<(), String> {
        let repo = Store::open(HostSql)?;
        let mut svc = Service::new(repo, HostPorts { seq: std::cell::Cell::new(0) });
        for ev in [EV_TICK, EV_TICKET_MOVED, EV_RUN_ENDED, EV_MODULES_CHANGED] {
            host::events::subscribe(ev)?;
        }
        let loaded = loaded_modules();
        if !loaded.is_empty() {
            svc.prune_orphans(&loaded);
        }
        svc.start();
        *STATE.lock().unwrap_or_else(|e| e.into_inner()) = Some(State { svc, ingress: webhook::Ingress::default() });
        Ok(())
    }

    fn stop() {
        *STATE.lock().unwrap_or_else(|e| e.into_inner()) = None;
    }

    fn handle_frame(frame: Frame, _caller: Caller) -> Result<Option<Frame>, String> {
        let reply = |ty: &str, data: String| Some(Frame { type_: ty.into(), data, session_id: None, request_id: frame.request_id.clone() });
        let data = frame.data.as_str();
        with(|st| match frame.type_.as_str() {
            "trigger_list" => Ok(reply("triggers", json_of(&st.svc.list()))),
            "trigger_events" => Ok(reply("trigger_events", json_of(&st.svc.events(&id_of(data))?))),
            "trigger_save" => {
                let req: TriggerSave = serde_json::from_str(data).map_err(|e| format!("bad trigger: {e}"))?;
                st.svc.save(req, None).map(|_| None)
            }
            "trigger_fire" => st.svc.fire(&id_of(data), "manual", None, None).map(|_| None),
            "trigger_arm" => st.svc.arm(&id_of(data)).map(|_| None),
            "trigger_disable" => st.svc.disable(&id_of(data)).map(|_| None),
            "trigger_delete" => {
                let id = id_of(data);
                // TRIG-AT-1: a person does not delete a module's trigger; the module does, or its unload prunes it.
                if let Some(t) = st.svc.get(&id) {
                    service::require_owner(&t, None)?;
                }
                st.svc.delete(&id).map(|_| None)
            }
            other => Err(format!("not a trigger frame: {other}")),
        })
    }

    fn call_tool(name: String, args: String, _ctx: CallContext) -> Result<String, String> {
        let a: Value = serde_json::from_str(if args.trim().is_empty() { "{}" } else { &args }).map_err(|e| format!("bad arguments: {e}"))?;
        let id = || s(&a, "id").ok_or_else(|| "id is required".to_string());
        with(|st| match name.as_str() {
            "triggers_list" => {
                let (kind, project) = (s(&a, "kind"), s(&a, "projectId"));
                let list: Vec<TriggerView> = st
                    .svc
                    .list()
                    .into_iter()
                    .filter(|t| kind.as_ref().is_none_or(|k| &t.kind == k) && project.as_ref().is_none_or(|p| t.project_id.as_ref() == Some(p)))
                    .collect();
                Ok(json_of(&list))
            }
            "triggers_save" => {
                let req: TriggerSave = serde_json::from_value(a.clone()).map_err(|e| format!("bad trigger: {e}"))?;
                Ok(json_of(&st.svc.save(req, None)?))
            }
            "triggers_delete" => {
                let id = id()?;
                if let Some(t) = st.svc.get(&id) {
                    service::require_owner(&t, None)?;
                }
                st.svc.delete(&id)?;
                Ok(json!({ "deleted": id.trim() }).to_string())
            }
            "triggers_fire" => Ok(json_of(&st.svc.fire(&id()?, "manual", None, None)?)),
            other => Err(format!("unknown tool {other}")),
        })
    }

    fn handle_route(r: RouteRequest) -> Result<HttpResponse, String> {
        let id = r.path.strip_prefix(HOOK_ROUTE_PREFIX).filter(|_| r.method.eq_ignore_ascii_case("POST")).ok_or_else(|| format!("no route {} {}", r.method, r.path))?;
        let (sig, ts, dl) = (webhook::header(&r.headers, &webhook::SIGNATURE_HEADERS), webhook::header(&r.headers, &webhook::TIMESTAMP_HEADERS), webhook::header(&r.headers, &webhook::DELIVERY_HEADERS));
        let outcome = with(|st| Ok(st.ingress.deliver(&mut st.svc, id, sig, ts, dl, &r.body)))?;
        Ok(match outcome {
            webhook::Outcome::Accepted(label) => HttpResponse {
                status: 200,
                headers: vec![("Content-Type".into(), "application/json".into())],
                body: json!({ "status": "ok", "disposition": label }).to_string().into_bytes(),
            },
            webhook::Outcome::Rejected(status, why) => HttpResponse { status, headers: vec![("Content-Type".into(), "text/plain".into())], body: why.into_bytes() },
        })
    }

    fn on_hook(kind: String, _payload: String) -> Result<String, String> {
        match kind.as_str() {
            "status" => with(|st| Ok(json!({ TRIPPED_FIELD: st.svc.tripped_count().to_string() }).to_string())),
            other => Err(format!("unknown hook {other}")),
        }
    }

    fn on_event(_subscription: u64, event: NamedEvent) {
        let p: Value = serde_json::from_str(&event.payload).unwrap_or(Value::Null);
        let loaded = (event.name == EV_MODULES_CHANGED).then(|| {
            let from_payload: HashSet<String> = p.get("modules").and_then(Value::as_array).into_iter().flatten().filter_map(|m| m.as_str().map(String::from)).collect();
            if from_payload.is_empty() {
                loaded_modules()
            } else {
                from_payload
            }
        });
        let _ = with(|st| {
            match event.name.as_str() {
                EV_TICK => st.svc.pump(),
                EV_TICKET_MOVED => st.svc.on_ticket_moved(&s(&p, "to").unwrap_or_default()),
                EV_RUN_ENDED => {
                    if let Some(sid) = s(&p, "sessionId") {
                        st.svc.on_run_ended(&sid, outcome_of(&s(&p, "outcome").unwrap_or_default()));
                    }
                }
                EV_MODULES_CHANGED => prune(st, loaded.clone().unwrap_or_default()),
                _ => {}
            }
            Ok(())
        });
    }
}

/// A session run's outcome name (`SUCCEEDED`, `FAILED`, `KILLED_*`, ...) as the guard reads it.
pub fn outcome_of(name: &str) -> Outcome {
    let n = name.trim().to_ascii_uppercase();
    if n == "SUCCEEDED" {
        Outcome::Succeeded
    } else if n.starts_with("KILLED") {
        Outcome::Killed
    } else if n == "START_REFUSED" {
        Outcome::StartRefused
    } else {
        Outcome::Failed
    }
}

export_plugin!(Triggers);
