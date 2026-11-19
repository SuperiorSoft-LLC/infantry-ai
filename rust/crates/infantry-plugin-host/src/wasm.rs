// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The WASM runtime: one wasmtime [`Engine`] per host (component model, WASI 0.2, fuel and epoch interruption)
//! and one [`WasmPlugin`] per loaded component.
//!
//! - **Grants are linker entries.** Only `log`, `config`, `types` and WASI (with no files, env, sockets or
//!   preopens) are always linked; every other interface is linked only when the manifest requests it, so a
//!   component that imports something it did not request does not instantiate.
//! - **Per-call budget:** fuel = `cpu_ms x fuel_per_ms` (CPU actually burnt in WASM) and an epoch deadline at
//!   `wall_ms` (wall clock, also covering time spent in host imports). Either one running out is a runaway.
//! - A trapped instance is never entered again: the next call instantiates a fresh one (and runs `start`).
use crate::guard::Fault;
use crate::imports::PluginImports;
use crate::{Budget, CallContext, Caller, Frame, HttpResponse, NamedEvent, Plugin, RouteRequest};
use infantry_plugin_wit::jsonl;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use wasmtime::component::{Component, HasSelf, Linker, ResourceTable};
use wasmtime::{Config, Engine, Store, Trap, UpdateDeadline};
use wasmtime_wasi::{WasiCtx, WasiCtxView, WasiView};

mod bindings {
    wasmtime::component::bindgen!({
        world: "daemon-plugin",
        path: "../infantry-plugin-wit/wit",
    });
}

use bindings::infantry::plugin as wit;
use bindings::{DaemonPlugin, DaemonPluginPre};

/// The shared engine plus the thread that advances its epoch.
pub struct WasmEngine {
    engine: Engine,
    origin: Instant,
    tick_ms: u64,
    stop: Arc<AtomicBool>,
}

impl WasmEngine {
    pub fn new(epoch_tick_ms: u64) -> anyhow::Result<Arc<WasmEngine>> {
        let mut config = Config::new();
        config.wasm_component_model(true).consume_fuel(true).epoch_interruption(true);
        let engine = Engine::new(&config).map_err(|e| anyhow::anyhow!("wasmtime engine: {e}"))?;
        let stop = Arc::new(AtomicBool::new(false));
        let tick_ms = epoch_tick_ms.max(1);
        let (e, s) = (engine.clone(), stop.clone());
        std::thread::Builder::new()
            .name("plugin-epoch".into())
            .spawn(move || {
                while !s.load(Ordering::Relaxed) {
                    std::thread::sleep(Duration::from_millis(tick_ms));
                    e.increment_epoch();
                }
            })
            .map_err(|e| anyhow::anyhow!("epoch thread: {e}"))?;
        Ok(Arc::new(WasmEngine { engine, origin: Instant::now(), tick_ms, stop }))
    }

    pub fn engine(&self) -> &Engine {
        &self.engine
    }

    fn now_ms(&self) -> u64 {
        self.origin.elapsed().as_millis() as u64
    }

    /// Compiles a component (its bytes are checked to be a component, not a core module).
    pub fn compile(&self, bytes: &[u8]) -> Result<Component, String> {
        Component::from_binary(&self.engine, bytes).map_err(|e| format!("not a loadable component: {e}"))
    }
}

impl Drop for WasmEngine {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
    }
}

/// The store data of one instance.
pub struct State {
    wasi: WasiCtx,
    table: ResourceTable,
    imports: Arc<PluginImports>,
    limiter: MemoryTracker,
}

impl WasiView for State {
    fn ctx(&mut self) -> WasiCtxView<'_> {
        WasiCtxView { ctx: &mut self.wasi, table: &mut self.table }
    }
}

/// Caps linear memory and records how much the instance holds.
struct MemoryTracker {
    max_bytes: usize,
    total: Arc<AtomicU64>,
}

impl wasmtime::ResourceLimiter for MemoryTracker {
    fn memory_growing(&mut self, current: usize, desired: usize, _maximum: Option<usize>) -> wasmtime::Result<bool> {
        let total = self.total.load(Ordering::Relaxed) as usize;
        let after = total.saturating_sub(current) + desired;
        if desired > self.max_bytes || after > self.max_bytes {
            return Ok(false);
        }
        self.total.store(after as u64, Ordering::Relaxed);
        Ok(true)
    }
    fn table_growing(&mut self, _current: usize, desired: usize, _maximum: Option<usize>) -> wasmtime::Result<bool> {
        Ok(desired <= 1_000_000)
    }
}

/// Builds the linker for one plugin: the baseline plus exactly the linked interfaces.
fn linker_for(engine: &Engine, imports: &PluginImports) -> Result<Linker<State>, String> {
    let mut linker: Linker<State> = Linker::new(engine);
    let e = |what: &str, err: wasmtime::Error| format!("linking {what}: {err}");
    wasmtime_wasi::p2::add_to_linker_sync(&mut linker).map_err(|x| e("wasi", x))?;
    wit::types::add_to_linker::<State, HasSelf<State>>(&mut linker, |s| s).map_err(|x| e("types", x))?;
    wit::log::add_to_linker::<State, HasSelf<State>>(&mut linker, |s| s).map_err(|x| e("log", x))?;
    wit::config::add_to_linker::<State, HasSelf<State>>(&mut linker, |s| s).map_err(|x| e("config", x))?;
    if imports.linked("settings") {
        wit::settings::add_to_linker::<State, HasSelf<State>>(&mut linker, |s| s).map_err(|x| e("settings", x))?;
    }
    if imports.linked("storage") {
        wit::storage::add_to_linker::<State, HasSelf<State>>(&mut linker, |s| s).map_err(|x| e("storage", x))?;
    }
    if imports.linked("events") {
        wit::events::add_to_linker::<State, HasSelf<State>>(&mut linker, |s| s).map_err(|x| e("events", x))?;
    }
    if imports.linked("http") {
        wit::http::add_to_linker::<State, HasSelf<State>>(&mut linker, |s| s).map_err(|x| e("http", x))?;
    }
    if imports.linked("frames") {
        wit::frames::add_to_linker::<State, HasSelf<State>>(&mut linker, |s| s).map_err(|x| e("frames", x))?;
    }
    Ok(linker)
}

struct Live {
    store: Store<State>,
    bindings: DaemonPlugin,
}

pub struct WasmPlugin {
    engine: Arc<WasmEngine>,
    pre: DaemonPluginPre<State>,
    imports: Arc<PluginImports>,
    fuel_per_ms: u64,
    max_memory_bytes: usize,
    live: Mutex<Option<Live>>,
    /// Engine ms at which the running call is cut off.
    deadline_ms: Arc<AtomicU64>,
    cancel: Arc<AtomicBool>,
    memory: Arc<AtomicU64>,
    start_budget: Mutex<Option<Budget>>,
}

impl WasmPlugin {
    /// Links and pre-instantiates `component`. An import the manifest did not request fails here.
    pub fn new(engine: Arc<WasmEngine>, component: &Component, imports: Arc<PluginImports>, fuel_per_ms: u64, memory_mb: u64) -> Result<WasmPlugin, String> {
        let linker = linker_for(engine.engine(), &imports)?;
        let pre = linker.instantiate_pre(component).map_err(|e| refusal_of_link(&e))?;
        let pre = DaemonPluginPre::new(pre).map_err(|e| format!("not a daemon-plugin component: {e}"))?;
        Ok(WasmPlugin {
            engine,
            pre,
            imports,
            fuel_per_ms: fuel_per_ms.max(1),
            max_memory_bytes: (memory_mb.max(1) * 1024 * 1024) as usize,
            live: Mutex::new(None),
            deadline_ms: Arc::new(AtomicU64::new(u64::MAX)),
            cancel: Arc::new(AtomicBool::new(false)),
            memory: Arc::new(AtomicU64::new(0)),
            start_budget: Mutex::new(None),
        })
    }

    fn instantiate(&self) -> Result<Live, Fault> {
        let wasi = WasiCtx::builder().allow_tcp(false).allow_udp(false).allow_ip_name_lookup(false).build();
        self.memory.store(0, Ordering::Relaxed);
        let state = State { wasi, table: ResourceTable::new(), imports: self.imports.clone(), limiter: MemoryTracker { max_bytes: self.max_memory_bytes, total: self.memory.clone() } };
        let mut store = Store::new(self.engine.engine(), state);
        store.limiter(|s| &mut s.limiter);
        let (deadline, cancel, engine) = (self.deadline_ms.clone(), self.cancel.clone(), self.engine.clone());
        store.epoch_deadline_callback(move |_| {
            if cancel.load(Ordering::SeqCst) || engine.now_ms() >= deadline.load(Ordering::SeqCst) {
                Ok(UpdateDeadline::Interrupt)
            } else {
                Ok(UpdateDeadline::Continue(1))
            }
        });
        store.set_fuel(u64::MAX / 2).map_err(|e| Fault::Trap(e.to_string()))?;
        store.set_epoch_deadline(1);
        self.deadline_ms.store(self.engine.now_ms() + 60_000, Ordering::SeqCst);
        let bindings = self.pre.instantiate(&mut store).map_err(|e| self.fault(e, "instantiate"))?;
        Ok(Live { store, bindings })
    }

    /// Maps a wasmtime error to a fault: fuel or the epoch = runaway, anything else = trap.
    fn fault(&self, e: wasmtime::Error, what: &str) -> Fault {
        if self.cancel.load(Ordering::SeqCst) {
            return Fault::Cancelled(format!("{what} cut off (unloading)"));
        }
        match e.downcast_ref::<Trap>() {
            Some(Trap::OutOfFuel) => Fault::Runaway(format!("{what} used its whole CPU budget (fuel)")),
            Some(Trap::Interrupt) => Fault::Runaway(format!("{what} ran past its deadline (epoch)")),
            Some(t) => Fault::Trap(format!("{what}: {t}")),
            None => Fault::Trap(format!("{what}: {}", first_line(&format!("{e:#}")))),
        }
    }

    /// Runs `f` on the live instance (a fresh one after a trap), with `budget`.
    fn with<T>(&self, what: &str, budget: Budget, f: impl FnOnce(&DaemonPlugin, &mut Store<State>) -> wasmtime::Result<T>) -> Result<T, Fault> {
        let mut guard = self.live.lock().map_err(|_| Fault::Trap("instance lock poisoned".into()))?;
        if guard.is_none() {
            let mut live = self.instantiate()?;
            // A replacement instance runs `start` again (the first one is run by the host's own start call).
            let start_budget = *self.start_budget.lock().unwrap();
            if let Some(b) = start_budget {
                self.arm(&mut live.store, b)?;
                match live.bindings.call_start(&mut live.store) {
                    Ok(Ok(())) => {}
                    Ok(Err(e)) => return Err(Fault::Trap(format!("restart after a trap failed: {e}"))),
                    Err(e) => return Err(self.fault(e, "start")),
                }
            }
            *guard = Some(live);
        }
        let live = guard.as_mut().expect("instance just set");
        self.arm(&mut live.store, budget)?;
        let out = f(&live.bindings, &mut live.store);
        self.deadline_ms.store(u64::MAX, Ordering::SeqCst);
        match out {
            Ok(v) => Ok(v),
            Err(e) => {
                // The component instance may not be entered after a trap: drop it, the next call makes a fresh one.
                *guard = None;
                self.imports.clear_subscriptions();
                Err(self.fault(e, what))
            }
        }
    }

    fn arm(&self, store: &mut Store<State>, budget: Budget) -> Result<(), Fault> {
        store.set_fuel(budget.cpu_ms.max(1).saturating_mul(self.fuel_per_ms)).map_err(|e| Fault::Trap(e.to_string()))?;
        self.deadline_ms.store(self.engine.now_ms() + budget.wall_ms.max(self.engine.tick_ms), Ordering::SeqCst);
        store.set_epoch_deadline(1);
        Ok(())
    }
}

fn first_line(s: &str) -> String {
    s.lines().next().unwrap_or_default().to_string()
}

/// "imports `infantry:plugin/storage@0.1.0` which the manifest does not request" from a link error.
fn refusal_of_link(e: &wasmtime::Error) -> String {
    let text = format!("{e:#}");
    if let Some(at) = text.find("infantry:plugin/") {
        let name: String = text[at..].chars().take_while(|c| !c.is_whitespace() && *c != '`' && *c != '\'' && *c != '"').collect();
        let iface = name.trim_start_matches("infantry:plugin/").split('@').next().unwrap_or_default().to_string();
        return format!("the component imports {name}, which module.json does not request (add \"{iface}\" to daemon.needs or daemon.wants)");
    }
    format!("the component's imports cannot be linked: {}", first_line(&text))
}

// ---- type conversions between the bindgen types and the host-neutral JSON types ----

fn frame_in(f: &Frame) -> wit::types::Frame {
    wit::types::Frame { type_: f.r#type.clone(), data: f.data.clone(), session_id: f.session_id.clone(), request_id: f.request_id.clone() }
}
fn frame_out(f: wit::types::Frame) -> Frame {
    Frame { r#type: f.type_, data: f.data, session_id: f.session_id, request_id: f.request_id }
}
fn caller_in(c: &Caller) -> wit::types::Caller {
    wit::types::Caller { owner: c.owner, agent: c.agent, active_session: c.active_session.clone(), project: c.project.clone(), remote_address: c.remote_address.clone() }
}
fn ctx_in(c: &CallContext) -> wit::types::CallContext {
    wit::types::CallContext { caller: caller_in(&c.caller), client_session_id: c.client_session_id.clone(), client_role: c.client_role.clone(), client_project_id: c.client_project_id.clone() }
}
fn event_in(e: &NamedEvent) -> wit::types::NamedEvent {
    wit::types::NamedEvent { name: e.name.clone(), payload: e.payload.clone(), source: e.source.clone(), id: e.id.clone(), cause_id: e.cause_id.clone(), depth: e.depth, at: e.at }
}
fn event_out(e: NamedEvent) -> wit::types::NamedEvent {
    event_in(&e)
}
fn sql_in(v: wit::types::SqlValue) -> jsonl::SqlValue {
    match v {
        wit::types::SqlValue::Null => jsonl::SqlValue::Null,
        wit::types::SqlValue::Integer(i) => jsonl::SqlValue::Integer(i),
        wit::types::SqlValue::Real(r) => jsonl::SqlValue::Real(r),
        wit::types::SqlValue::Text(t) => jsonl::SqlValue::Text(t),
        wit::types::SqlValue::Blob(b) => jsonl::SqlValue::Blob(b),
    }
}
fn sql_out(v: jsonl::SqlValue) -> wit::types::SqlValue {
    match v {
        jsonl::SqlValue::Null => wit::types::SqlValue::Null,
        jsonl::SqlValue::Integer(i) => wit::types::SqlValue::Integer(i),
        jsonl::SqlValue::Real(r) => wit::types::SqlValue::Real(r),
        jsonl::SqlValue::Text(t) => wit::types::SqlValue::Text(t),
        jsonl::SqlValue::Blob(b) => wit::types::SqlValue::Blob(b),
    }
}

// ---- the imports (host side) ----

impl wit::types::Host for State {}

impl wit::log::Host for State {
    fn write(&mut self, level: wit::log::Level, line: String) {
        let level = match level {
            wit::log::Level::Debug => jsonl::LogLevel::Debug,
            wit::log::Level::Info => jsonl::LogLevel::Info,
            wit::log::Level::Warn => jsonl::LogLevel::Warn,
            wit::log::Level::Error => jsonl::LogLevel::Error,
        };
        self.imports.log(level, &line);
    }
}

impl wit::config::Host for State {
    fn module_id(&mut self) -> String {
        self.imports.module_id.clone()
    }
    fn tier(&mut self) -> String {
        self.imports.tier.as_str().into()
    }
    fn api_version(&mut self) -> String {
        infantry_plugin_wit::API_VERSION.into()
    }
    fn environment(&mut self) -> String {
        self.imports.environment()
    }
    fn get(&mut self, key: String) -> Option<String> {
        self.imports.config_get(&key)
    }
    fn endpoint(&mut self, name: String) -> Option<String> {
        self.imports.endpoint(&name)
    }
}

impl wit::settings::Host for State {
    fn get(&mut self, key: String) -> Option<String> {
        self.imports.setting_get(&key).ok().flatten()
    }
    fn set(&mut self, key: String, value: String) -> Result<(), String> {
        self.imports.setting_set(&key, &value)
    }
    fn remove(&mut self, key: String) -> Result<(), String> {
        self.imports.setting_remove(&key)
    }
}

impl wit::storage::Host for State {
    fn kv_get(&mut self, key: String) -> Result<Option<String>, String> {
        self.imports.kv_get(&key)
    }
    fn kv_set(&mut self, key: String, value: String) -> Result<(), String> {
        self.imports.kv_set(&key, &value)
    }
    fn kv_delete(&mut self, key: String) -> Result<bool, String> {
        self.imports.kv_delete(&key)
    }
    fn kv_keys(&mut self, prefix: String) -> Result<Vec<String>, String> {
        self.imports.kv_keys(&prefix)
    }
    fn execute(&mut self, sql: String, params: Vec<wit::types::SqlValue>) -> Result<u64, String> {
        self.imports.execute(&sql, &params.into_iter().map(sql_in).collect::<Vec<_>>())
    }
    fn query(&mut self, sql: String, params: Vec<wit::types::SqlValue>) -> Result<wit::types::SqlRows, String> {
        let rows = self.imports.query(&sql, &params.into_iter().map(sql_in).collect::<Vec<_>>())?;
        Ok(wit::types::SqlRows { columns: rows.columns, rows: rows.rows.into_iter().map(|r| r.into_iter().map(sql_out).collect()).collect() })
    }
}

impl wit::events::Host for State {
    fn publish(&mut self, name: String, payload: String, cause_id: Option<String>) -> Result<wit::types::NamedEvent, String> {
        self.imports.publish(&name, &payload, cause_id.as_deref()).map(event_out)
    }
    fn subscribe(&mut self, pattern: String) -> Result<u64, String> {
        self.imports.subscribe(&pattern)
    }
    fn unsubscribe(&mut self, id: u64) {
        self.imports.unsubscribe(id)
    }
    fn broadcast(&mut self, type_: String, data: String) {
        let _ = self.imports.broadcast(&type_, &data);
    }
}

impl wit::http::Host for State {
    fn send(&mut self, request: wit::types::HttpRequest) -> Result<wit::types::HttpResponse, String> {
        let r = self.imports.http_send(jsonl::HttpRequest { method: request.method, url: request.url, headers: request.headers, body: request.body, timeout_ms: request.timeout_ms })?;
        Ok(wit::types::HttpResponse { status: r.status, headers: r.headers, body: r.body })
    }
}

impl wit::frames::Host for State {
    fn request(&mut self, type_: String, data: String, session_id: Option<String>, timeout_ms: u32) -> Result<wit::types::Frame, String> {
        self.imports.frame_request(&type_, &data, session_id, timeout_ms).map(|f| frame_in(&f))
    }
}

// ---- the exports ----

impl Plugin for WasmPlugin {
    fn runtime(&self) -> &'static str {
        "wasm"
    }
    fn start(&self, budget: Budget) -> Result<Result<(), String>, Fault> {
        *self.start_budget.lock().unwrap() = None;
        let r = self.with("start", budget, |b, s| b.call_start(s));
        *self.start_budget.lock().unwrap() = Some(budget);
        r
    }
    fn stop(&self, budget: Budget) -> Result<(), Fault> {
        if self.live.lock().map(|l| l.is_none()).unwrap_or(true) {
            return Ok(());
        }
        self.with("stop", budget, |b, s| b.call_stop(s))
    }
    fn call_tool(&self, name: &str, args: &str, ctx: &CallContext, budget: Budget) -> Result<Result<String, String>, Fault> {
        let ctx = ctx_in(ctx);
        self.with(&format!("call-tool({name})"), budget, |b, s| b.call_call_tool(s, name, args, &ctx))
    }
    fn handle_frame(&self, frame: &Frame, caller: &Caller, budget: Budget) -> Result<Result<Option<Frame>, String>, Fault> {
        let (f, c) = (frame_in(frame), caller_in(caller));
        self.with(&format!("handle-frame({})", frame.r#type), budget, |b, s| b.call_handle_frame(s, &f, &c)).map(|r| r.map(|o| o.map(frame_out)))
    }
    fn handle_route(&self, request: &RouteRequest, budget: Budget) -> Result<Result<HttpResponse, String>, Fault> {
        let r = wit::types::RouteRequest { method: request.method.clone(), path: request.path.clone(), query: request.query.clone(), headers: request.headers.clone(), body: request.body.clone() };
        self.with(&format!("handle-route({} {})", request.method, request.path), budget, |b, s| b.call_handle_route(s, &r))
            .map(|r| r.map(|h| HttpResponse { status: h.status, headers: h.headers, body: h.body }))
    }
    fn on_hook(&self, kind: &str, payload: &str, budget: Budget) -> Result<Result<String, String>, Fault> {
        self.with(&format!("on-hook({kind})"), budget, |b, s| b.call_on_hook(s, kind, payload))
    }
    fn on_event(&self, subscription: u64, event: &NamedEvent, budget: Budget) -> Result<(), Fault> {
        let e = event_in(event);
        self.with(&format!("on-event({})", event.name), budget, |b, s| b.call_on_event(s, subscription, &e))
    }
    fn cancel(&self) {
        self.cancel.store(true, Ordering::SeqCst);
    }
    fn memory_bytes(&self) -> Option<u64> {
        Some(self.memory.load(Ordering::Relaxed))
    }
}
