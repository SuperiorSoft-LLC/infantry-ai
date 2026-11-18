// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The native sidecar runtime: serves a [`Plugin`] over stdio with the JSON-lines mapping of the daemon-plugin
//! world (`infantry_plugin_wit::jsonl`). Calls are served one at a time; an export may call imports, and a host
//! call that arrives meanwhile is queued. A panic in the plugin is answered as a `trap`.
use crate::host::{event_in, frame_in, frame_out};
use crate::types::{CallContext, Caller, RouteRequest};
use crate::Plugin;
use infantry_plugin_wit::jsonl::{self, Line};
use serde_json::Value;
use std::collections::VecDeque;
use std::io::{BufRead, Write};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

static QUEUED: Mutex<VecDeque<Line>> = Mutex::new(VecDeque::new());
static NEXT_ID: AtomicU64 = AtomicU64::new(1);
static OUT: Mutex<()> = Mutex::new(());

fn send(line: &Line) {
    let _g = OUT.lock().unwrap_or_else(|e| e.into_inner());
    let mut out = std::io::stdout().lock();
    let _ = writeln!(out, "{}", line.to_text());
    let _ = out.flush();
}

/// The next line from the host; None at end of input.
fn read_line() -> Option<Line> {
    let stdin = std::io::stdin();
    let mut buf = String::new();
    loop {
        buf.clear();
        if stdin.lock().read_line(&mut buf).ok()? == 0 {
            return None;
        }
        if buf.trim().is_empty() {
            continue;
        }
        match serde_json::from_str::<Line>(&buf) {
            Ok(l) => return Some(l),
            Err(e) => eprintln!("infantry sidecar: a host line is not the plugin protocol: {e}"),
        }
    }
}

/// Calls host import `name`; its JSON ok value or error.
pub fn import(name: &str, params: Value) -> Result<Value, String> {
    let id = NEXT_ID.fetch_add(1, Ordering::Relaxed);
    send(&Line::import(id, name, params));
    loop {
        let Some(line) = read_line() else { return Err("the host closed the plugin channel".into()) };
        if line.call.is_some() {
            QUEUED.lock().unwrap_or_else(|e| e.into_inner()).push_back(line);
            continue;
        }
        if line.is_reply() && line.id == Some(id) {
            return match line.err {
                Some(e) => Err(e),
                None => Ok(line.ok.unwrap_or(Value::Null)),
            };
        }
    }
}

/// [`import`] with the ok value decoded.
pub fn import_as<T: serde::de::DeserializeOwned>(name: &str, params: Value) -> Result<T, String> {
    serde_json::from_value(import(name, params)?).map_err(|e| format!("{name}: the host answered a value of the wrong shape: {e}"))
}

fn caller_in(c: jsonl::Caller) -> Caller {
    Caller { owner: c.owner, agent: c.agent, active_session: c.active_session, project: c.project, remote_address: c.remote_address }
}

fn params<T: serde::de::DeserializeOwned>(v: Option<Value>) -> Result<T, String> {
    serde_json::from_value(v.unwrap_or(Value::Object(Default::default()))).map_err(|e| format!("bad params: {e}"))
}

fn json<T: serde::Serialize>(v: T) -> Value {
    serde_json::to_value(v).unwrap_or(Value::Null)
}

/// One export call: Ok(Ok(value)) / Ok(Err(plugin error)) / Err(bad call).
fn dispatch<P: Plugin>(name: &str, p: Option<Value>) -> Result<Result<Value, String>, String> {
    Ok(match name {
        "start" => P::start().map(|_| Value::Null),
        "stop" => {
            P::stop();
            Ok(Value::Null)
        }
        "call-tool" => {
            let c: jsonl::CallToolParams = params(p)?;
            let ctx = CallContext { caller: caller_in(c.ctx.caller), client_session_id: c.ctx.client_session_id, client_role: c.ctx.client_role, client_project_id: c.ctx.client_project_id };
            P::call_tool(c.name, c.args, ctx).map(Value::String)
        }
        "handle-frame" => {
            let c: jsonl::HandleFrameParams = params(p)?;
            P::handle_frame(frame_in(c.frame), caller_in(c.caller)).map(|o| json(o.map(frame_out)))
        }
        "handle-route" => {
            let c: jsonl::HandleRouteParams = params(p)?;
            let r = c.request;
            P::handle_route(RouteRequest { method: r.method, path: r.path, query: r.query, headers: r.headers, body: r.body })
                .map(|h| json(jsonl::HttpResponse { status: h.status, headers: h.headers, body: h.body }))
        }
        "on-hook" => {
            let c: jsonl::OnHookParams = params(p)?;
            P::on_hook(c.kind, c.payload).map(Value::String)
        }
        "on-event" => {
            let c: jsonl::OnEventParams = params(p)?;
            P::on_event(c.subscription, event_in(c.event));
            Ok(Value::Null)
        }
        other => return Err(format!("unknown export '{other}'")),
    })
}

/// Serves `P` until the host closes stdin or calls `stop`.
pub fn serve<P: Plugin>() {
    send(&Line::hello());
    loop {
        let queued = QUEUED.lock().unwrap_or_else(|e| e.into_inner()).pop_front();
        let Some(line) = queued.or_else(read_line) else { return };
        let (Some(id), Some(name)) = (line.id, line.call.clone()) else { continue };
        let params = line.params;
        let out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| dispatch::<P>(&name, params)));
        let reply = match out {
            Ok(Ok(Ok(v))) => Line::ok(id, v),
            Ok(Ok(Err(e))) => Line::err(id, e),
            Ok(Err(bad)) => Line::trap(id, bad),
            Err(panic) => {
                let why = panic.downcast_ref::<&str>().map(|s| s.to_string()).or_else(|| panic.downcast_ref::<String>().cloned()).unwrap_or_else(|| "panicked".into());
                Line::trap(id, format!("panicked: {why}"))
            }
        };
        send(&reply);
        if name == "stop" {
            return;
        }
    }
}
