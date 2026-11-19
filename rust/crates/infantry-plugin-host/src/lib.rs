// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The Infantry AI daemon plugin host (docs/rust-port/PLAN.md section 2).
//!
//! A daemon half is either a WASM component (`daemon.component`, run by wasmtime with the component model and
//! WASI 0.2) or a native sidecar (`daemon.sidecar`, the JSON-lines mapping of the same WIT world over stdio). Both
//! sit behind the [`Plugin`] trait, so callers cannot tell them apart. [`PluginHost`] loads a module folder, guards
//! every call ([`guard`]), exposes the plugin's frames as a FrameUnit and its tools as a tool table, and hot swaps
//! a new version in while the old one drains.
pub mod guard;
pub mod host;
pub mod imports;
pub mod manifest;
pub mod services;
pub mod sidecar;
pub mod unit;
pub mod wasm;

pub use guard::{CallError, Fault, Guard, GuardLimits};
pub use host::{LoadedPlugin, PluginHost, ToolEntry};
pub use infantry_plugin_wit::jsonl::{CallContext, Caller, Frame, HttpResponse, NamedEvent, RouteRequest};
pub use manifest::{ModuleManifest, Tier};
pub use services::{HostServices, MemoryServices};

/// The per-call budget the host hands a runtime.
#[derive(Debug, Clone, Copy)]
pub struct Budget {
    /// CPU the call may burn (WASM: fuel = cpu_ms x [`HostLimits::fuel_per_ms`]).
    pub cpu_ms: u64,
    /// Wall clock until the call is cut off (WASM: the epoch deadline; sidecar: the hang ceiling).
    pub wall_ms: u64,
}

/// One runtime of a daemon half. Every method is one WIT export; a `Fault` is the runtime failing, the inner
/// `Result` is the plugin's own answer.
pub trait Plugin: Send + Sync {
    /// `wasm` or `sidecar`.
    fn runtime(&self) -> &'static str;
    fn start(&self, budget: Budget) -> Result<Result<(), String>, Fault>;
    fn stop(&self, budget: Budget) -> Result<(), Fault>;
    fn call_tool(&self, name: &str, args: &str, ctx: &CallContext, budget: Budget) -> Result<Result<String, String>, Fault>;
    fn handle_frame(&self, frame: &Frame, caller: &Caller, budget: Budget) -> Result<Result<Option<Frame>, String>, Fault>;
    fn handle_route(&self, request: &RouteRequest, budget: Budget) -> Result<Result<HttpResponse, String>, Fault>;
    fn on_hook(&self, kind: &str, payload: &str, budget: Budget) -> Result<Result<String, String>, Fault>;
    fn on_event(&self, subscription: u64, event: &NamedEvent, budget: Budget) -> Result<(), Fault>;
    /// Cuts off whatever call is running (planned unload after the drain window).
    fn cancel(&self);
    /// The plugin's memory now, in bytes (WASM: linear memory; sidecar: none known).
    fn memory_bytes(&self) -> Option<u64>;
}

/// The host's limits; every number is a named setting with the cheapest sane default.
#[derive(Debug, Clone)]
pub struct HostLimits {
    /// limits.plugins.callTimeoutMs: a frame/hook/event call's wall clock when the manifest sets none.
    pub call_timeout_ms: u64,
    /// limits.plugins.toolTimeoutMs: a tool call's wall clock when the manifest sets none.
    pub tool_timeout_ms: u64,
    /// limits.plugins.cpuMsPerCall: the CPU budget when the manifest sets none.
    pub cpu_ms_per_call: u64,
    /// limits.plugins.fuelPerMs: WASM fuel units granted per CPU millisecond.
    pub fuel_per_ms: u64,
    /// limits.plugins.epochTickMs: how often the engine epoch advances (the wall deadline's resolution).
    pub epoch_tick_ms: u64,
    /// limits.plugins.startTimeoutMs.
    pub start_timeout_ms: u64,
    /// limits.plugins.strikes.
    pub strikes: u32,
    /// limits.plugins.cooldownMs.
    pub cooldown_ms: u64,
    /// limits.plugins.queueLimit.
    pub queue_limit: usize,
    /// limits.plugins.drainMs: how long an unloaded or swapped-out version may finish its calls.
    pub drain_ms: u64,
    /// limits.plugins.memoryMb: the most linear memory one WASM instance may grow to.
    pub memory_mb: u64,
    /// limits.plugins.sidecarHelloMs: how long a sidecar has to say hello.
    pub sidecar_hello_ms: u64,
}

impl Default for HostLimits {
    fn default() -> Self {
        HostLimits {
            call_timeout_ms: 10_000,
            tool_timeout_ms: 45_000,
            cpu_ms_per_call: 2_000,
            fuel_per_ms: 1_000_000,
            epoch_tick_ms: 5,
            start_timeout_ms: 15_000,
            strikes: 3,
            cooldown_ms: 30_000,
            queue_limit: 256,
            drain_ms: 5_000,
            memory_mb: 256,
            sidecar_hello_ms: 10_000,
        }
    }
}
