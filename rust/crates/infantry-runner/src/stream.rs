// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Stream-json records (`--output-format stream-json`, one JSON object per line: `system`, `assistant`, `user`, `result`) read as
//! structured events. The record shape is a de-facto standard several agent CLIs share; the reader names no vendor.

use serde_json::Value;

#[derive(Debug, Clone, PartialEq)]
pub enum StreamEvent {
    Init { session_id: Option<String> },
    AssistantText { text: String, message_id: Option<String>, model: Option<String> },
    Thinking { text: String },
    ToolCall { id: String, name: String, input: Value },
    ToolResult { tool_use_id: String, content: String, is_error: bool },
    Result { is_error: bool, text: String, usage: Option<Value>, cost_usd: Option<f64> },
    Compact { trigger: Option<String>, pre_tokens: Option<u64> },
}

/// The structured events of one stream-json line (none for a line that is not a record or carries nothing of note).
pub fn parse_line(line: &str) -> Vec<StreamEvent> {
    match serde_json::from_str::<Value>(line.trim()) {
        Ok(v) => parse(&v),
        Err(_) => Vec::new(),
    }
}

pub fn parse(v: &Value) -> Vec<StreamEvent> {
    let s = |o: &Value, k: &str| o.get(k).and_then(Value::as_str).map(str::to_string);
    match v.get("type").and_then(Value::as_str) {
        Some("system") => match v.get("subtype").and_then(Value::as_str) {
            Some("init") => vec![StreamEvent::Init { session_id: s(v, "session_id") }],
            Some("compact_boundary") => {
                let md = v.get("compact_metadata");
                vec![StreamEvent::Compact {
                    trigger: md.and_then(|m| s(m, "trigger")),
                    pre_tokens: md.and_then(|m| m.get("pre_tokens")).and_then(Value::as_u64),
                }]
            }
            _ => Vec::new(),
        },
        Some("assistant") => {
            let Some(msg) = v.get("message") else { return Vec::new() };
            let (id, model) = (s(msg, "id"), s(msg, "model"));
            blocks(msg)
                .filter_map(|b| match b.get("type").and_then(Value::as_str) {
                    Some("text") => s(b, "text").filter(|t| !t.is_empty()).map(|text| StreamEvent::AssistantText {
                        text,
                        message_id: id.clone(),
                        model: model.clone(),
                    }),
                    Some("thinking") => s(b, "thinking").filter(|t| !t.is_empty()).map(|text| StreamEvent::Thinking { text }),
                    Some("tool_use") => Some(StreamEvent::ToolCall {
                        id: s(b, "id").unwrap_or_default(),
                        name: s(b, "name").unwrap_or_default(),
                        input: b.get("input").cloned().unwrap_or(Value::Null),
                    }),
                    _ => None,
                })
                .collect()
        }
        Some("user") => {
            let Some(msg) = v.get("message") else { return Vec::new() };
            blocks(msg)
                .filter(|b| b.get("type").and_then(Value::as_str) == Some("tool_result"))
                .map(|b| StreamEvent::ToolResult {
                    tool_use_id: s(b, "tool_use_id").unwrap_or_default(),
                    content: content_text(b.get("content")),
                    is_error: b.get("is_error").and_then(Value::as_bool).unwrap_or(false),
                })
                .collect()
        }
        Some("result") => vec![StreamEvent::Result {
            is_error: v.get("is_error").and_then(Value::as_bool).unwrap_or(false),
            text: s(v, "result").unwrap_or_default(),
            usage: v.get("usage").cloned(),
            cost_usd: v.get("total_cost_usd").and_then(Value::as_f64),
        }],
        _ => Vec::new(),
    }
}

fn blocks(msg: &Value) -> impl Iterator<Item = &Value> {
    msg.get("content").and_then(Value::as_array).into_iter().flatten()
}

/// A tool result's content as text: a string as it is, an array of text blocks joined by newlines.
fn content_text(c: Option<&Value>) -> String {
    match c {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Array(a)) => a.iter().filter_map(|b| b.get("text").and_then(Value::as_str)).collect::<Vec<_>>().join("\n"),
        _ => String::new(),
    }
}
