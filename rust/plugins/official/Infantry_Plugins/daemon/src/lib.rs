// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry_Plugins, daemon half: the agent tools over the plugin manager (list, search, show, status, list_import,
//! request) and the `plugins.status` capability. `infantry_plugin_request` raises an owner question and installs
//! nothing: the daemon installs only on the owner's yes.
//!
//! HOST NEED: the plugin catalog is not reachable from a guest yet. This plugin speaks the `plugins_op` frame
//! (`{"op": list|search|show|status|import|request, ...}`, answered by a `plugins` frame holding the JSON result, or
//! `error`); the wiring unit must serve that frame from the plugin manager.
use infantry_plugin_sdk::types::CallContext;
use infantry_plugin_sdk::{export_plugin, host, Plugin};
use serde_json::{json, Map, Value};

const TIMEOUT_MS: u32 = 30_000;

pub struct Plugins;

fn arg(a: &Value, k: &str) -> Option<String> {
    a.get(k).and_then(|v| v.as_str()).map(str::trim).filter(|s| !s.is_empty()).map(String::from)
}

/// The `plugins_op` payload for a tool call, or the refusal text.
pub fn op_payload(tool: &str, a: &Value, session: Option<&str>) -> Result<Value, String> {
    let need = |k: &str| arg(a, k).ok_or_else(|| format!("{tool}: {k} is required"));
    let mut o = Map::new();
    match tool {
        "infantry_plugin_list" => o.insert("op".into(), "list".into()),
        "infantry_plugin_search" => {
            o.insert("query".into(), arg(a, "query").unwrap_or_default().into());
            o.insert("tag".into(), arg(a, "tag").unwrap_or_default().into());
            o.insert("op".into(), "search".into())
        }
        "infantry_plugin_show" => {
            o.insert("ref".into(), need("ref")?.into());
            o.insert("op".into(), "show".into())
        }
        "infantry_plugin_status" => {
            o.insert("id".into(), need("id")?.into());
            o.insert("op".into(), "status".into())
        }
        "infantry_plugin_list_import" => {
            o.insert("location".into(), need("location")?.into());
            o.insert("op".into(), "import".into())
        }
        "infantry_plugin_request" => {
            o.insert("ref".into(), need("ref")?.into());
            o.insert("reason".into(), arg(a, "reason").unwrap_or_default().into());
            if let Some(s) = session {
                o.insert("sessionId".into(), s.into());
            }
            o.insert("op".into(), "request".into())
        }
        "plugins_status" => {
            o.insert("id".into(), need("id")?.into());
            o.insert("op".into(), "status".into())
        }
        other => return Err(format!("unknown tool {other}")),
    };
    Ok(Value::Object(o))
}

fn run(tool: &str, a: &Value, session: Option<&str>) -> Result<String, String> {
    let payload = op_payload(tool, a, session)?;
    let r = host::frames::request("plugins_op", &payload.to_string(), session, TIMEOUT_MS).map_err(|e| format!("{tool} failed: {e}"))?;
    if r.type_ == "error" {
        return Err(format!("{tool}: {}", r.data));
    }
    if tool == "plugins_status" {
        // {installed:false} when the plugin is not on this host
        let v: Value = serde_json::from_str(&r.data).unwrap_or(Value::Null);
        if v.is_null() || v.get("id").is_none() {
            return Ok(json!({"id": arg(a, "id"), "installed": false}).to_string());
        }
        let mut m = v.as_object().cloned().unwrap_or_default();
        m.insert("installed".into(), true.into());
        return Ok(Value::Object(m).to_string());
    }
    Ok(r.data)
}

impl Plugin for Plugins {
    fn call_tool(name: String, args: String, ctx: CallContext) -> Result<String, String> {
        let a: Value = serde_json::from_str(if args.trim().is_empty() { "{}" } else { &args }).map_err(|e| format!("{name}: arguments are not JSON: {e}"))?;
        run(&name, &a, ctx.client_session_id.as_deref())
    }
}

export_plugin!(Plugins);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn payloads() {
        assert!(op_payload("infantry_plugin_show", &json!({}), None).unwrap_err().contains("ref is required"));
        let p = op_payload("infantry_plugin_request", &json!({"ref": "a@1", "reason": "x"}), Some("s")).unwrap();
        assert_eq!(p["op"], "request");
        assert_eq!(p["sessionId"], "s");
    }
}
