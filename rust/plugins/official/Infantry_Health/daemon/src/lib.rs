// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry_Health, daemon half: `infantry_health`, the daemon health and environment report as an agent tool. The
//! report is built by the daemon's core (`health` frame); this tool only asks for it. `json: true` returns the
//! machine form, otherwise the same text as `infantry health`.
//!
//! `cargo build -p infantry-health-daemon --target wasm32-wasip2 --release --lib` builds the component
//! (`target/wasm32-wasip2/release/infantry_health_daemon.wasm`, shipped as `daemon/Infantry_Health.wasm`).
use infantry_plugin_sdk::types::CallContext;
use infantry_plugin_sdk::{export_plugin, host, Plugin};

pub const TOOL: &str = "infantry_health";
const REPLY_TIMEOUT_MS: u32 = 15_000;

pub struct Health;

impl Plugin for Health {
    fn call_tool(name: String, args: String, ctx: CallContext) -> Result<String, String> {
        if name != TOOL {
            return Err(format!("unknown tool {name}"));
        }
        let args: serde_json::Value = serde_json::from_str(if args.trim().is_empty() { "{}" } else { &args }).map_err(|e| format!("{TOOL} failed: arguments are not JSON: {e}"))?;
        let want_json = args.get("json").and_then(|v| v.as_bool()) == Some(true);
        let mut request = serde_json::Map::new();
        if let Some(s) = ctx.client_session_id {
            request.insert("sessionId".into(), s.into());
        }
        if let Some(p) = ctx.client_project_id {
            request.insert("projectId".into(), p.into());
        }
        if !want_json {
            request.insert("format".into(), "text".into());
        }
        let reply = host::frames::request("health", &serde_json::Value::Object(request).to_string(), None, REPLY_TIMEOUT_MS).map_err(|e| format!("{TOOL} failed: {e}"))?;
        if reply.type_ != "health" || reply.data.trim().is_empty() {
            return Err(if reply.data.trim().is_empty() { format!("{TOOL}: the daemon has no health service") } else { reply.data });
        }
        Ok(reply.data)
    }
}

export_plugin!(Health);
