// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The Infantry AI daemon plugin ABI.
//!
//! - [`WIT_DIR`] / [`DAEMON_PLUGIN_WIT`]: the `infantry:plugin@0.1.0` package, world `daemon-plugin`. The host
//!   (wasmtime `bindgen!`) and the guest SDK (`wit_bindgen::generate!`) both generate from this one file.
//! - [`jsonl`]: the JSON-lines mapping of the same world, spoken over stdio by a native sidecar (`daemon.sidecar`).
//!
//! The WIT is frozen at 0.1.0: changes are additive only (new imports; exports never change).
//!
//! # Manifest rules (`module.json`, read by infantry-plugin-host)
//! - `daemon.component` (a `.wasm` in the module folder) or `daemon.sidecar` (`{exe, args}`), never both.
//! - `daemon.needs` / `daemon.wants` name the [`GRANTABLE_IMPORTS`]; an import that is not requested is not linked,
//!   so a component that imports it is refused at load ("does not request").
//! - Tool names match `^[a-z][a-z0-9_]{2,63}$`. A capability id may be dotted (`weather.forecast`); a
//!   `runs: "daemon"` capability is answered by the daemon tool with the same `action`, else by the tool named
//!   after the id with `.`/`-` turned into `_` (`weather_forecast`). `call-tool` receives the TOOL name.
//! - `daemon.config` takes the Kotlin grouped shape `[{node, fields:[{key, scope, default}]}]` or flat
//!   `[{key, scope, default}]`; `config.get(key)` serves the `scope: "daemon"` fields.
//! - `daemon.limits.cpuMsPerCall` (fuel + epoch budget), `toolTimeoutMs`, `callTimeoutMs`; three runaways in a
//!   row, or one trap, disable the plugin, never the host. Loading the same module id again hot swaps it: new
//!   calls go to the new version while the old one drains, then gets `stop`.

pub mod jsonl;

/// The WIT package name and version.
pub const PACKAGE: &str = "infantry:plugin@0.1.0";
/// The world a daemon half implements.
pub const WORLD: &str = "daemon-plugin";
/// The ABI version the host speaks (`config.api-version`).
pub const API_VERSION: &str = "0.1.0";

/// Absolute path of the `wit/` folder (for code generators in this workspace).
pub const WIT_DIR: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/wit");

/// The WIT source itself.
pub const DAEMON_PLUGIN_WIT: &str = include_str!("../wit/daemon-plugin.wit");

/// The interfaces a manifest can request in `daemon.needs` / `daemon.wants` (each is one import, linked only when
/// requested). `log` and `config` are [`BASELINE_IMPORTS`], always linked.
pub const GRANTABLE_IMPORTS: &[&str] = &["settings", "storage", "events", "http", "frames"];

/// The imports every plugin gets.
pub const BASELINE_IMPORTS: &[&str] = &["log", "config"];

/// The export names, in WIT spelling.
pub const EXPORTS: &[&str] = &["start", "stop", "call-tool", "handle-frame", "handle-route", "on-hook", "on-event"];
