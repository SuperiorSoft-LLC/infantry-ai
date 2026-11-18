// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry_Mcp, daemon half: `infantry_mcp`, the agent face of the MCP registry (the `mcp_*` frames). Reads: list,
//! status, my_tools, grants. Changes (add, remove, enable, disable, grant, revoke) are refused to an agent session:
//! an agent must never widen its own tools. Output is compact; the registry never carries a secret value.
use infantry_plugin_sdk::types::{CallContext, Frame};
use infantry_plugin_sdk::{export_plugin, host, Plugin};
use serde_json::{json, Map, Value};

pub const TOOL: &str = "infantry_mcp";
const OPS: [&str; 10] = ["list", "status", "my_tools", "grants", "add", "remove", "enable", "disable", "grant", "revoke"];
const CHANGES: [&str; 6] = ["add", "remove", "enable", "disable", "grant", "revoke"];
const REPLY_TIMEOUT_MS: u32 = 45_000;

pub struct Mcp;

fn fail<T>(text: impl std::fmt::Display) -> Result<T, String> {
    Err(format!("{TOOL}: {text}"))
}

fn str_arg(a: &Map<String, Value>, k: &str) -> Option<String> {
    a.get(k).and_then(|v| v.as_str()).map(str::trim).filter(|s| !s.is_empty()).map(String::from)
}

type Send<'a> = &'a dyn Fn(&str, &str, Option<&str>) -> Result<Frame, String>;

/// The whole tool, with the frame round trip as `send` (type, payload, session) so a test can stand in for the daemon.
pub fn handle(args: &Map<String, Value>, agent: bool, session: Option<&str>, project: Option<&str>, send: Send) -> Result<String, String> {
    let Some(op) = args.get("op").and_then(|v| v.as_str()) else { return fail(format!("op is required ({})", OPS.join("|"))) };
    if !OPS.contains(&op) {
        return fail(format!("unknown op '{op}' ({})", OPS.join("|")));
    }
    if CHANGES.contains(&op) && agent {
        return fail(format!("{op} refused. Agent sessions cannot change MCP servers or grants (walled by default); ask the PM or the owner."));
    }
    let name = str_arg(args, "name");
    let body = |extra: Vec<(&str, Value)>| -> String {
        let mut o = Map::new();
        o.insert("target".into(), str_arg(args, "target").unwrap_or_else(|| "infantry".into()).into());
        if let Some(p) = project {
            o.insert("projectId".into(), p.into());
        }
        if let Some(s) = str_arg(args, "scope") {
            o.insert("scope".into(), s.into());
        }
        for (k, v) in extra {
            o.insert(k.into(), v);
        }
        Value::Object(o).to_string()
    };
    let need = |k: &str| -> Result<Value, String> { args.get(k).cloned().ok_or_else(|| format!("{TOOL}: {op} needs {k}")) };
    let need_name = || name.clone().map(Value::from).ok_or_else(|| format!("{TOOL}: {op} needs name"));
    let (ty, payload) = match op {
        "list" => ("mcp_list", body(vec![])),
        "status" => ("mcp_test", body(vec![("name", need_name()?)])),
        "my_tools" => ("mcp_plan", json!({"sessionId": session.ok_or_else(|| format!("{TOOL}: my_tools needs a calling agent session"))?}).to_string()),
        "grants" => ("mcp_grants", "{}".to_string()),
        "add" => ("mcp_add", body(vec![("server", need("server")?)])),
        "remove" => ("mcp_remove", body(vec![("name", need_name()?)])),
        "enable" | "disable" => ("mcp_set_enabled", body(vec![("name", need_name()?), ("enabled", (op == "enable").into())])),
        "grant" => ("mcp_assign", body(vec![("grant", need("grant")?)])),
        _ => ("mcp_revoke", body(vec![("grantId", str_arg(args, "grantId").map(Value::from).ok_or_else(|| format!("{TOOL}: revoke needs grantId"))?)])),
    };
    match send(ty, &payload, if op == "my_tools" { session } else { None }) {
        Ok(r) if r.type_ == "error" => fail(format!("{op}: {}", r.data)),
        Ok(r) => Ok(compact(&r.data)),
        Err(e) => fail(format!("{op} failed: {e}")),
    }
}

fn s<'a>(o: &'a Value, k: &str) -> Option<&'a str> {
    o.get(k).and_then(|v| v.as_str())
}

fn or_null(o: &Value, k: &str) -> String {
    s(o, k).unwrap_or("null").to_string()
}

/// One line per server / grant; anything unrecognised comes back as the (secret-free) JSON the daemon sent.
pub fn compact(data: &str) -> String {
    let Ok(root) = serde_json::from_str::<Value>(data) else { return data.to_string() };
    if let Some(servers) = root.get("servers").and_then(|v| v.as_array()) {
        let mut lines: Vec<String> = servers.iter().map(server_line).collect();
        if lines.is_empty() {
            lines.push("no MCP servers".into());
        }
        for t in root.get("targets").and_then(|v| v.as_array()).into_iter().flatten() {
            if let Some(n) = s(t, "note") {
                lines.push(format!("note {}: {n}", or_null(t, "id")));
            }
        }
        return lines.join("\n");
    }
    if root.get("add").is_some() {
        return plan_lines(&root);
    }
    if root.get("name").is_some() && root.get("state").is_some() {
        let tools = root.get("toolCount").and_then(|v| v.as_i64()).map(|n| format!(" {n} tools")).unwrap_or_default();
        let detail = s(&root, "detail").map(|d| format!(" - {d}")).unwrap_or_default();
        return format!("{} {}{tools}{detail}", or_null(&root, "name"), or_null(&root, "state"));
    }
    if let Some(a) = root.as_array() {
        if a.is_empty() {
            return "no grants (walled: only the infantry server)".into();
        }
        return a
            .iter()
            .map(|g| format!("{}  {} -> {} {}  by {}: {}", or_null(g, "id"), or_null(g, "server"), or_null(g, "scope"), or_null(g, "target"), or_null(g, "approvedBy"), or_null(g, "purpose")))
            .collect::<Vec<_>>()
            .join("\n");
    }
    data.to_string()
}

fn server_line(o: &Value) -> String {
    let off = if o.get("enabled").and_then(|v| v.as_bool()) == Some(false) { " (off)" } else { "" };
    let tools = o.get("toolCount").and_then(|v| v.as_i64()).map(|n| format!("  {n} tools")).unwrap_or_default();
    let shadow = s(o, "shadowedBy").map(|x| format!("  shadowed by {x}")).unwrap_or_default();
    let scope = s(o, "scope").map(|x| format!(":{x}")).unwrap_or_default();
    format!("{}  {}/{}{scope}  {}{off}{tools}{shadow}", or_null(o, "name"), or_null(o, "target"), or_null(o, "source"), or_null(o, "state"))
}

fn plan_lines(o: &Value) -> String {
    let list = |k: &str| -> Vec<String> { o.get(k).and_then(|v| v.as_array()).into_iter().flatten().filter_map(|x| x.as_str().map(String::from)).collect() };
    let add = list("add");
    let deny = list("deny");
    let live = o.get("status").cloned().unwrap_or(Value::Null);
    let got = add.iter().map(|n| live.get(n).and_then(|v| v.as_str()).map(|st| format!("{n} [{st}]")).unwrap_or_else(|| n.clone())).collect::<Vec<_>>().join(", ");
    let mut lines = vec![format!("you get: {}", if got.is_empty() { "(none)".to_string() } else { got })];
    if !deny.is_empty() {
        lines.push(format!("skipped: {}", deny.join(", ")));
    }
    if let Some(why) = o.get("reasons").and_then(|v| v.as_object()) {
        for (k, v) in why {
            lines.push(format!("  {k}: {}", v.as_str().map(String::from).unwrap_or_else(|| v.to_string())));
        }
    }
    lines.join("\n")
}

impl Plugin for Mcp {
    fn call_tool(name: String, args: String, ctx: CallContext) -> Result<String, String> {
        if name != TOOL {
            return Err(format!("unknown tool {name}"));
        }
        let v: Value = serde_json::from_str(if args.trim().is_empty() { "{}" } else { &args }).map_err(|e| format!("{TOOL}: arguments are not JSON: {e}"))?;
        let empty = Map::new();
        let a = v.as_object().unwrap_or(&empty);
        let agent = ctx.caller.agent || ctx.client_session_id.is_some() || ctx.client_role.is_some();
        handle(a, agent, ctx.client_session_id.as_deref(), ctx.client_project_id.as_deref(), &|ty, data, sess| host::frames::request(ty, data, sess, REPLY_TIMEOUT_MS))
    }
}

export_plugin!(Mcp);

#[cfg(test)]
mod tests {
    use super::*;

    fn frame(t: &str, d: &str) -> Frame {
        Frame { type_: t.into(), data: d.into(), session_id: None, request_id: None }
    }

    #[test]
    fn agents_cannot_change_and_list_is_compact() {
        let a = json!({"op": "add", "server": {}});
        let e = handle(a.as_object().unwrap(), true, Some("s1"), None, &|_, _, _| Ok(frame("x", "{}"))).unwrap_err();
        assert!(e.contains("add refused"));
        let l = json!({"op": "list"});
        let out = handle(l.as_object().unwrap(), true, None, None, &|t, _, _| {
            assert_eq!(t, "mcp_list");
            Ok(frame("mcp", r#"{"servers":[{"name":"a","target":"infantry","source":"app","state":"ok","enabled":false,"toolCount":3}]}"#))
        })
        .unwrap();
        assert_eq!(out, "a  infantry/app  ok (off)  3 tools");
    }
}
