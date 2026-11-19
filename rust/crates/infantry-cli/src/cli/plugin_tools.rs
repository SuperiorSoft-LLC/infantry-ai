// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! One plugin tool call from the CLI (Kotlin `McpPluginTools.call`): `plugin_tool_call {name, arguments, client}`,
//! answered by `plugin_tool_result {text, isError}`. The wait is the tool's declared `timeoutMs` (from `plugin_tools`),
//! else `limits.plugins.toolTimeoutMs`, plus a quarter for the round trip. Never sent twice: a tool may change state.

use super::conn::Envelope;
use super::Ctx;
use serde_json::{Map, Value};

/// A tool's answer: its text and whether it is an error.
pub struct ToolResult {
    pub text: String,
    pub is_error: bool,
}

/// `answerWaitMs`: the tool's own timeout plus 25 %.
fn answer_wait_ms(tool_ms: i64) -> u64 {
    (tool_ms.max(1) as u64).saturating_add(tool_ms.max(1) as u64 / 4)
}

/// The `client` object: who runs this CLI (session, role) and the project a call that names none works in.
pub fn client(getenv: super::Getenv) -> Value {
    let mut c = Map::new();
    let nb = |k: &str| getenv(k).map(|v| v.trim().to_string()).filter(|v| !v.is_empty());
    if let Some(s) = nb("INFANTRY_SESSION_ID") {
        c.insert("sessionId".into(), Value::String(s));
    }
    if let Some(r) = nb("INFANTRY_ROLE") {
        c.insert("role".into(), Value::String(r));
    }
    if let Some(p) = nb("INFANTRY_PROJECT_ID") {
        c.insert("projectId".into(), Value::String(p));
    }
    Value::Object(c)
}

/// Call plugin tool `name` with `arguments`.
pub fn call(ctx: &mut Ctx, name: &str, arguments: Map<String, Value>) -> Result<ToolResult, String> {
    let getenv = ctx.getenv;
    let default_ms = crate::env::PLUGIN_TOOL_TIMEOUT_MS.from_env(getenv).unwrap_or(crate::env::PLUGIN_TOOL_TIMEOUT_MS.default);
    let stream = ctx.stream();
    let declared = stream
        .request(&Envelope::new("plugin_tools", ""), "plugin_tools", "plugin_tools", true)
        .ok()
        .and_then(|a| serde_json::from_str::<Value>(&a.data).ok())
        .and_then(|v| {
            v.as_array()?.iter().find(|d| d["name"] == name).and_then(|d| d["timeoutMs"].as_i64())
        });
    let payload = serde_json::json!({"name": name, "arguments": Value::Object(arguments), "client": client(getenv)});
    let answer = stream.request_within(
        &Envelope::new("plugin_tool_call", payload.to_string()),
        "plugin_tool_result",
        "plugin_tool_call",
        false,
        answer_wait_ms(declared.unwrap_or(default_ms)),
    )?;
    let Ok(Value::Object(r)) = serde_json::from_str::<Value>(&answer.data) else {
        return Ok(ToolResult { text: "malformed plugin_tool_result".into(), is_error: true });
    };
    Ok(ToolResult {
        text: r.get("text").and_then(Value::as_str).unwrap_or_default().to_string(),
        is_error: r.get("isError").and_then(Value::as_bool).unwrap_or(false),
    })
}

/// Print a result (stderr when it is an error) and give the exit code.
pub fn print(ctx: &mut Ctx, r: &ToolResult) -> i32 {
    if r.is_error {
        ctx.err(&r.text);
        1
    } else {
        ctx.out(&r.text);
        0
    }
}
