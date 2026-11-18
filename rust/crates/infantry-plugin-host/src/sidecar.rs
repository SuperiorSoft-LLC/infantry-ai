// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The sidecar runtime: a native process (`daemon.sidecar {exe, args}`) speaking the JSON-lines mapping of the
//! daemon-plugin world over stdio (`infantry_plugin_wit::jsonl`). Its imports are answered by the same
//! [`PluginImports`] as a WASM component's, so grants and filters are identical.
//!
//! The process gets a scrubbed environment (no daemon token or URL), no console window, and its stderr goes to the
//! daemon log. A call with no answer by its wall deadline is a hang (the guard's breaker strike); the process dying
//! or answering `trap` is a trap. CPU is not metered here (an OS Job Object is the follow-up).
use crate::guard::Fault;
use crate::imports::PluginImports;
use crate::{Budget, CallContext, Caller, Frame, HttpResponse, NamedEvent, Plugin, RouteRequest};
use infantry_plugin_wit::jsonl::{self, Line, LogLevel};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::{BufRead, BufReader, Write};
use std::path::Path;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc::{self, RecvTimeoutError, Sender};
use std::sync::{Arc, Mutex};
use std::time::Duration;

type Pending = Arc<Mutex<HashMap<u64, Sender<Line>>>>;

pub struct SidecarPlugin {
    child: Mutex<Child>,
    stdin: Arc<Mutex<ChildStdin>>,
    pending: Pending,
    next_id: AtomicU64,
    dead: Arc<Mutex<Option<String>>>,
}

/// The environment variables a sidecar keeps; everything else (tokens, daemon URL) is scrubbed.
const KEPT_ENV: &[&str] = &["PATH", "SystemRoot", "SYSTEMROOT", "TEMP", "TMP", "HOME", "USERPROFILE", "LANG"];

fn write_line(stdin: &Mutex<ChildStdin>, line: &Line) -> Result<(), String> {
    let mut w = stdin.lock().map_err(|_| "sidecar stdin poisoned".to_string())?;
    let mut text = line.to_text();
    text.push('\n');
    w.write_all(text.as_bytes()).and_then(|_| w.flush()).map_err(|e| format!("sidecar stdin: {e}"))
}

impl SidecarPlugin {
    /// Starts `exe` (inside the module folder `dir`) and waits up to `hello_ms` for its hello line.
    pub fn spawn(exe: &Path, args: &[String], dir: &Path, imports: Arc<PluginImports>, hello_ms: u64) -> Result<SidecarPlugin, String> {
        let mut cmd = Command::new(exe);
        cmd.args(args).current_dir(dir).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped()).env_clear();
        for k in KEPT_ENV {
            if let Ok(v) = std::env::var(k) {
                cmd.env(k, v);
            }
        }
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }
        let mut child = cmd.spawn().map_err(|e| format!("sidecar {} did not start: {e}", exe.display()))?;
        let stdin = Arc::new(Mutex::new(child.stdin.take().ok_or("sidecar stdin")?));
        let stdout = child.stdout.take().ok_or("sidecar stdout")?;
        let stderr = child.stderr.take().ok_or("sidecar stderr")?;
        let pending: Pending = Arc::default();
        let dead: Arc<Mutex<Option<String>>> = Arc::default();
        let (hello_tx, hello_rx) = mpsc::channel::<Result<String, String>>();

        let log = imports.clone();
        std::thread::Builder::new()
            .name(format!("sidecar-{}-stderr", imports.module_id))
            .spawn(move || {
                for line in BufReader::new(stderr).lines().map_while(Result::ok) {
                    log.log(LogLevel::Warn, &format!("[sidecar stderr] {line}"));
                }
            })
            .map_err(|e| e.to_string())?;

        let (p, d, w, imp) = (pending.clone(), dead.clone(), stdin.clone(), imports.clone());
        std::thread::Builder::new()
            .name(format!("sidecar-{}-stdout", imports.module_id))
            .spawn(move || {
                let mut hello = Some(hello_tx);
                for text in BufReader::new(stdout).lines().map_while(Result::ok) {
                    if text.trim().is_empty() {
                        continue;
                    }
                    let line: Line = match serde_json::from_str(&text) {
                        Ok(l) => l,
                        Err(e) => {
                            imp.log(LogLevel::Warn, &format!("sidecar sent a line that is not the plugin protocol ({e}): {}", text.chars().take(200).collect::<String>()));
                            continue;
                        }
                    };
                    if let Some(h) = &line.hello {
                        if let Some(tx) = hello.take() {
                            let _ = tx.send(if h.world == jsonl::HELLO_WORLD { Ok(h.world.clone()) } else { Err(format!("sidecar speaks {}, this host {}", h.world, jsonl::HELLO_WORLD)) });
                        }
                        continue;
                    }
                    if let Some(name) = line.import.clone() {
                        let Some(id) = line.id else { continue };
                        let (imp2, w2) = (imp.clone(), w.clone());
                        let params = line.params.unwrap_or(Value::Null);
                        // An import may block (frames.request); answering it off this thread keeps replies flowing.
                        let _ = std::thread::Builder::new().name("sidecar-import".into()).spawn(move || {
                            let reply = match imp2.dispatch_json(&name, params) {
                                Ok(v) => Line::ok(id, v),
                                Err(e) => Line::err(id, e),
                            };
                            let _ = write_line(&w2, &reply);
                        });
                        continue;
                    }
                    if line.is_reply() {
                        if let Some(id) = line.id {
                            if let Some(tx) = p.lock().unwrap().remove(&id) {
                                let _ = tx.send(line);
                            }
                        }
                    }
                }
                *d.lock().unwrap() = Some("the sidecar process exited".into());
                // Dropping the senders wakes every waiting call with Disconnected (a trap).
                p.lock().unwrap().clear();
                if let Some(tx) = hello.take() {
                    let _ = tx.send(Err("the sidecar exited before its hello".into()));
                }
            })
            .map_err(|e| e.to_string())?;

        let plugin = SidecarPlugin { child: Mutex::new(child), stdin, pending, next_id: AtomicU64::new(1), dead };
        match hello_rx.recv_timeout(Duration::from_millis(hello_ms)) {
            Ok(Ok(_)) => Ok(plugin),
            Ok(Err(why)) => Err(why),
            Err(_) => Err(format!("the sidecar said no hello within {hello_ms} ms")),
        }
    }

    pub fn pid(&self) -> u32 {
        self.child.lock().unwrap().id()
    }

    fn call(&self, what: &str, export: &str, params: Value, budget: Budget) -> Result<Result<Value, String>, Fault> {
        if let Some(why) = self.dead.lock().unwrap().clone() {
            return Err(Fault::Trap(why));
        }
        let id = self.next_id.fetch_add(1, Ordering::Relaxed);
        let (tx, rx) = mpsc::channel();
        self.pending.lock().unwrap().insert(id, tx);
        if let Err(e) = write_line(&self.stdin, &Line::call(id, export, params)) {
            self.pending.lock().unwrap().remove(&id);
            return Err(Fault::Trap(e));
        }
        match rx.recv_timeout(Duration::from_millis(budget.wall_ms.max(1))) {
            Ok(line) => {
                if let Some(t) = line.trap {
                    Err(Fault::Trap(format!("{what}: {t}")))
                } else if let Some(e) = line.err {
                    Ok(Err(e))
                } else {
                    Ok(Ok(line.ok.unwrap_or(Value::Null)))
                }
            }
            Err(RecvTimeoutError::Timeout) => {
                self.pending.lock().unwrap().remove(&id);
                Err(Fault::Hang(format!("{what} made no answer in {} ms", budget.wall_ms)))
            }
            Err(RecvTimeoutError::Disconnected) => Err(Fault::Trap(self.dead.lock().unwrap().clone().unwrap_or_else(|| "the sidecar went away".into()))),
        }
    }

    fn typed<T: serde::de::DeserializeOwned>(&self, what: &str, export: &str, params: Value, budget: Budget) -> Result<Result<T, String>, Fault> {
        match self.call(what, export, params, budget)? {
            Ok(v) => serde_json::from_value(v).map(Ok).map_err(|e| Fault::Trap(format!("{what}: the sidecar answered a value of the wrong shape: {e}"))),
            Err(e) => Ok(Err(e)),
        }
    }

    fn kill(&self) {
        if let Ok(mut c) = self.child.lock() {
            let _ = c.kill();
            let _ = c.wait();
        }
    }
}

impl Drop for SidecarPlugin {
    fn drop(&mut self) {
        self.kill();
    }
}

impl Plugin for SidecarPlugin {
    fn runtime(&self) -> &'static str {
        "sidecar"
    }
    fn start(&self, budget: Budget) -> Result<Result<(), String>, Fault> {
        self.call("start", "start", json!({}), budget).map(|r| r.map(|_| ()))
    }
    fn stop(&self, budget: Budget) -> Result<(), Fault> {
        let r = self.call("stop", "stop", json!({}), budget).map(|_| ());
        self.kill();
        r
    }
    fn call_tool(&self, name: &str, args: &str, ctx: &CallContext, budget: Budget) -> Result<Result<String, String>, Fault> {
        let p = jsonl::CallToolParams { name: name.into(), args: args.into(), ctx: ctx.clone() };
        self.typed(&format!("call-tool({name})"), "call-tool", serde_json::to_value(p).unwrap_or_default(), budget)
    }
    fn handle_frame(&self, frame: &Frame, caller: &Caller, budget: Budget) -> Result<Result<Option<Frame>, String>, Fault> {
        let p = jsonl::HandleFrameParams { frame: frame.clone(), caller: caller.clone() };
        self.typed(&format!("handle-frame({})", frame.r#type), "handle-frame", serde_json::to_value(p).unwrap_or_default(), budget)
    }
    fn handle_route(&self, request: &RouteRequest, budget: Budget) -> Result<Result<HttpResponse, String>, Fault> {
        let p = jsonl::HandleRouteParams { request: request.clone() };
        self.typed(&format!("handle-route({} {})", request.method, request.path), "handle-route", serde_json::to_value(p).unwrap_or_default(), budget)
    }
    fn on_hook(&self, kind: &str, payload: &str, budget: Budget) -> Result<Result<String, String>, Fault> {
        let p = jsonl::OnHookParams { kind: kind.into(), payload: payload.into() };
        self.typed(&format!("on-hook({kind})"), "on-hook", serde_json::to_value(p).unwrap_or_default(), budget)
    }
    fn on_event(&self, subscription: u64, event: &NamedEvent, budget: Budget) -> Result<(), Fault> {
        let p = jsonl::OnEventParams { subscription, event: event.clone() };
        self.call(&format!("on-event({})", event.name), "on-event", serde_json::to_value(p).unwrap_or_default(), budget).map(|_| ())
    }
    fn cancel(&self) {
        self.kill();
    }
    fn memory_bytes(&self) -> Option<u64> {
        None
    }
}
