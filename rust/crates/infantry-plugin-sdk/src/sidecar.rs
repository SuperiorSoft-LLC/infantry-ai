// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The native sidecar runtime: serves a [`Plugin`] over stdio with the JSON-lines mapping of the daemon-plugin
//! world (`infantry_plugin_wit::jsonl`).
//!
//! One reader thread owns stdin. A reply to an `import` is routed by id to the call that is waiting for it; a `call`
//! is run on its own worker thread, so a long call (an SSH command of half an hour) never holds up another one. At
//! most [`Plugin::MAX_CONCURRENT_CALLS`] run at once; the rest wait their turn (a plugin that relies on one call at a
//! time sets it to 1). `start` and `stop` run on the serving thread itself, before and after every other call. A
//! panic in the plugin is answered as a `trap`; the other calls go on.
use crate::host::{event_in, frame_in, frame_out};
use crate::types::{CallContext, Caller, RouteRequest};
use crate::Plugin;
use infantry_plugin_wit::jsonl::{self, Line};
use serde_json::Value;
use std::collections::HashMap;
use std::io::{BufRead, Write};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc;
use std::sync::{Arc, Condvar, Mutex};

/// The imports a call is waiting on, and whether the host has gone.
#[derive(Default)]
struct Pending {
    closed: bool,
    waiting: HashMap<u64, mpsc::Sender<Line>>,
}

/// The line transport of one served plugin: where lines go out, and who waits for a reply.
struct Hub {
    out: Mutex<Box<dyn Write + Send>>,
    pending: Mutex<Pending>,
    next_id: AtomicU64,
}

impl Hub {
    fn new(out: Box<dyn Write + Send>) -> Hub {
        Hub { out: Mutex::new(out), pending: Mutex::new(Pending::default()), next_id: AtomicU64::new(1) }
    }

    fn send(&self, line: &Line) {
        let mut out = self.out.lock().unwrap_or_else(|e| e.into_inner());
        let _ = writeln!(out, "{}", line.to_text());
        let _ = out.flush();
    }

    fn import(&self, name: &str, params: Value) -> Result<Value, String> {
        let closed = || "the host closed the plugin channel".to_string();
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = mpsc::channel();
        {
            let mut p = self.pending.lock().unwrap_or_else(|e| e.into_inner());
            if p.closed {
                return Err(closed());
            }
            p.waiting.insert(id, tx);
        }
        self.send(&Line::import(id, name, params));
        match rx.recv() {
            Ok(line) => match line.err {
                Some(e) => Err(e),
                None => Ok(line.ok.unwrap_or(Value::Null)),
            },
            Err(_) => Err(closed()),
        }
    }

    /// The host is gone: every waiting import fails now, and later ones fail at once.
    fn close(&self) {
        let mut p = self.pending.lock().unwrap_or_else(|e| e.into_inner());
        p.closed = true;
        p.waiting.clear();
    }

    fn route_reply(&self, line: Line) {
        let Some(id) = line.id else { return };
        let tx = self.pending.lock().unwrap_or_else(|e| e.into_inner()).waiting.remove(&id);
        if let Some(tx) = tx {
            let _ = tx.send(line);
        }
    }
}

/// The hub of the plugin being served in this process (a sidecar serves exactly one).
static HUB: Mutex<Option<Arc<Hub>>> = Mutex::new(None);

fn hub() -> Option<Arc<Hub>> {
    HUB.lock().unwrap_or_else(|e| e.into_inner()).clone()
}

/// Calls host import `name` from any thread; its JSON ok value or error.
pub fn import(name: &str, params: Value) -> Result<Value, String> {
    match hub() {
        Some(h) => h.import(name, params),
        None => Err("the plugin is not being served".into()),
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

/// Runs one export and builds its reply line (a panic becomes a `trap`).
fn answer<P: Plugin>(id: u64, name: &str, params: Option<Value>) -> Line {
    let out = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| dispatch::<P>(name, params)));
    match out {
        Ok(Ok(Ok(v))) => Line::ok(id, v),
        Ok(Ok(Err(e))) => Line::err(id, e),
        Ok(Err(bad)) => Line::trap(id, bad),
        Err(panic) => {
            let why = panic.downcast_ref::<&str>().map(|s| s.to_string()).or_else(|| panic.downcast_ref::<String>().cloned()).unwrap_or_else(|| "panicked".into());
            Line::trap(id, format!("panicked: {why}"))
        }
    }
}

/// A counting gate: `enter` waits while `limit` calls are inside.
struct Gate {
    limit: usize,
    inside: Mutex<usize>,
    freed: Condvar,
}

impl Gate {
    fn enter(&self) {
        let mut n = self.inside.lock().unwrap_or_else(|e| e.into_inner());
        while *n >= self.limit {
            n = self.freed.wait(n).unwrap_or_else(|e| e.into_inner());
        }
        *n += 1;
    }
    fn leave(&self) {
        *self.inside.lock().unwrap_or_else(|e| e.into_inner()) -= 1;
        self.freed.notify_one();
    }
}

/// Serves `P` until the host closes stdin or calls `stop`.
pub fn serve<P: Plugin>() {
    serve_on::<P>(std::io::BufReader::new(std::io::stdin()), std::io::stdout());
}

/// [`serve`] over any line reader and writer (the tests drive it through in-memory pipes).
pub fn serve_on<P: Plugin>(reader: impl BufRead + Send + 'static, writer: impl Write + Send + 'static) {
    let hub = Arc::new(Hub::new(Box::new(writer)));
    *HUB.lock().unwrap_or_else(|e| e.into_inner()) = Some(hub.clone());
    hub.send(&Line::hello());

    let (calls_tx, calls_rx) = mpsc::channel::<Line>();
    let reader_hub = hub.clone();
    let spawned = std::thread::Builder::new().name("sidecar-stdin".into()).spawn(move || {
        for text in reader.lines().map_while(Result::ok) {
            if text.trim().is_empty() {
                continue;
            }
            match serde_json::from_str::<Line>(&text) {
                Ok(line) if line.call.is_some() => {
                    if calls_tx.send(line).is_err() {
                        break;
                    }
                }
                Ok(line) if line.is_reply() => reader_hub.route_reply(line),
                Ok(_) => {}
                Err(e) => eprintln!("infantry sidecar: a host line is not the plugin protocol: {e}"),
            }
        }
        reader_hub.close();
    });
    if spawned.is_err() {
        return;
    }

    let gate = Arc::new(Gate { limit: P::MAX_CONCURRENT_CALLS.max(1), inside: Mutex::new(0), freed: Condvar::new() });
    while let Ok(line) = calls_rx.recv() {
        let (Some(id), Some(name)) = (line.id, line.call.clone()) else { continue };
        let params = line.params;
        if name == "start" || name == "stop" {
            hub.send(&answer::<P>(id, &name, params));
            if name == "stop" {
                break;
            }
            continue;
        }
        gate.enter();
        let (h, g) = (hub.clone(), gate.clone());
        let worker = std::thread::Builder::new().name(format!("sidecar-call-{id}")).spawn(move || {
            let reply = answer::<P>(id, &name, params);
            g.leave();
            h.send(&reply);
        });
        if worker.is_err() {
            gate.leave();
            hub.send(&Line::trap(id, "the sidecar could not start a thread for this call".to_string()));
        }
    }
    hub.close();
    *HUB.lock().unwrap_or_else(|e| e.into_inner()) = None;
}

#[cfg(test)]
mod tests;
