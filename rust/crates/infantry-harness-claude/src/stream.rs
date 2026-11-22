// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Claude's stream-json records (what `claude -p --output-format stream-json --verbose` prints, and what
//! [`crate::transcript`] makes of the interactive transcript) as neutral [`HarnessEvent`]s. Shared by both modes; the
//! turn end is the caller's (a headless `result` ends a turn, an interactive one is built by the translator).

use infantry_harness::{HarnessEvent, Usage};
use serde_json::Value;

pub(crate) fn s<'a>(v: &'a Value, k: &str) -> &'a str {
    v.get(k).and_then(Value::as_str).unwrap_or("")
}

/// The neutral events of one stream-json record (no [`HarnessEvent::TurnEnded`]; a `result` gives its usage only).
pub fn events(v: &Value) -> Vec<HarnessEvent> {
    match s(v, "type") {
        "system" if s(v, "subtype") == "init" => {
            let id = s(v, "session_id");
            if id.is_empty() {
                Vec::new()
            } else {
                vec![HarnessEvent::ProviderSession(id.into())]
            }
        }
        "assistant" => assistant(v.get("message").unwrap_or(&Value::Null)),
        "user" => {
            let mut out = tool_results(v.get("message").unwrap_or(&Value::Null));
            out.extend(task_notice(v));
            out
        }
        "result" => v.get("usage").filter(|u| u.is_object()).map(|u| vec![HarnessEvent::Usage(usage(u))]).unwrap_or_default(),
        _ => Vec::new(),
    }
}

/// The CLI's `<task-notification>` (a background task ended or was stopped), from a user record's text.
pub fn task_notice(v: &Value) -> Option<HarnessEvent> {
    let content = v.get("message")?.get("content");
    let text = match content {
        Some(Value::String(t)) => t.clone(),
        Some(Value::Array(a)) => a.iter().filter(|b| s(b, "type") == "text").map(|b| s(b, "text")).collect::<Vec<_>>().join("
"),
        _ => return None,
    };
    let at = text.find("<task-notification>")?;
    let body = &text[at..];
    let all = |tag: &str| -> Vec<String> {
        let (open, close) = (format!("<{tag}>"), format!("</{tag}>"));
        let mut found = Vec::new();
        let mut rest = body;
        while let Some(i) = rest.find(&open) {
            let after = &rest[i + open.len()..];
            let Some(j) = after.find(&close) else { break };
            found.push(after[..j].trim().to_string());
            rest = &after[j + close.len()..];
        }
        found
    };
    let ids = all("task-id");
    if ids.is_empty() {
        return None;
    }
    let status = all("status").into_iter().next().unwrap_or_else(|| "completed".into());
    let exit_code = all("exit-code").first().and_then(|c| c.parse().ok());
    Some(HarnessEvent::TaskNotice { ids, status, exit_code })
}

fn blocks(message: &Value) -> impl Iterator<Item = &Value> {
    message.get("content").and_then(Value::as_array).into_iter().flatten().filter(|b| b.is_object())
}

fn assistant(message: &Value) -> Vec<HarnessEvent> {
    let mut out = Vec::new();
    for b in blocks(message) {
        match s(b, "type") {
            "text" if !s(b, "text").trim().is_empty() => out.push(HarnessEvent::Text(s(b, "text").into())),
            "thinking" if !s(b, "thinking").trim().is_empty() => out.push(HarnessEvent::Reasoning(s(b, "thinking").into())),
            "tool_use" => out.push(HarnessEvent::ToolStarted {
                id: s(b, "id").into(),
                name: s(b, "name").into(),
                input: b.get("input").map(Value::to_string).unwrap_or_default(),
            }),
            _ => {}
        }
    }
    out
}

fn tool_results(message: &Value) -> Vec<HarnessEvent> {
    blocks(message)
        .filter(|b| s(b, "type") == "tool_result")
        .map(|b| HarnessEvent::ToolFinished {
            id: s(b, "tool_use_id").into(),
            output: result_text(b.get("content")),
            exit_code: None,
            ok: !b.get("is_error").and_then(Value::as_bool).unwrap_or(false),
        })
        .collect()
}

/// A tool result's content: a string, or the text of its text blocks.
fn result_text(content: Option<&Value>) -> String {
    match content {
        Some(Value::String(t)) => t.clone(),
        Some(Value::Array(a)) => a.iter().filter(|b| s(b, "type") == "text").map(|b| s(b, "text")).collect::<Vec<_>>().join("\n"),
        _ => String::new(),
    }
}

/// Claude's usage in neutral terms: `input_tokens` counts every prompt token (fresh, cache read and cache write, as the
/// other harnesses report it), `cached_input_tokens` the cache reads.
pub fn usage(u: &Value) -> Usage {
    let n = |k: &str| u.get(k).and_then(Value::as_u64).unwrap_or(0);
    Usage {
        input_tokens: n("input_tokens") + n("cache_read_input_tokens") + n("cache_creation_input_tokens"),
        cached_input_tokens: n("cache_read_input_tokens"),
        output_tokens: n("output_tokens"),
        reasoning_tokens: 0,
    }
}
