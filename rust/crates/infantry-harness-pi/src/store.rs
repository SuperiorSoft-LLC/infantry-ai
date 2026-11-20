// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! One pi session read from its own store, `<agent dir>/sessions/<any>/<timestamp>_<providerSessionId>.jsonl`, as
//! [`StatEvent`]s, and a model's context window from pi's local catalog `models-store.json` (Kotlin
//! `SessionStatsService.readPi` and `catalogLimit`). pi reports each request's cost (`usage.cost.total`). Pure apart
//! from the probe's reads.

use infantry_harness::stats::{iso_millis, safe_id, text_length, RequestUsage, StatEvent};
use infantry_harness::Probe;
use serde_json::Value;
use std::path::{Path, PathBuf};

/// The session's record: the first `<sessions>/<dir>/*_<psid>.jsonl`.
pub fn find(probe: &dyn Probe, sessions: &Path, psid: &str) -> Option<PathBuf> {
    if !safe_id(psid) {
        return None;
    }
    let suffix = format!("_{psid}.jsonl");
    probe
        .list_dir(sessions)
        .into_iter()
        .flat_map(|d| probe.list_dir(&d))
        .find(|f| f.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.ends_with(&suffix)))
}

/// The record's events, in order.
pub fn read(lines: impl Iterator<Item = String>) -> Vec<StatEvent> {
    let mut events = Vec::new();
    for line in lines {
        if line.trim().is_empty() {
            continue;
        }
        let Ok(o) = serde_json::from_str::<Value>(&line) else { continue };
        let line_at = o.get("timestamp").and_then(Value::as_str).and_then(iso_millis);
        events.push(StatEvent::Seen(line_at));
        if o.get("type").and_then(Value::as_str) != Some("message") {
            continue;
        }
        let Some(m) = o.get("message").filter(|m| m.is_object()) else { continue };
        let at = m.get("timestamp").and_then(Value::as_i64).or(line_at);
        match m.get("role").and_then(Value::as_str) {
            Some("user") => events.push(StatEvent::Prompt),
            Some("toolResult") => events.push(StatEvent::ToolResult { chars: text_length(m.get("content")), at }),
            Some("assistant") => {
                let model = m.get("model").and_then(Value::as_str).map(String::from);
                events.push(StatEvent::AssistantAt(at));
                events.push(StatEvent::Turn(model.clone()));
                if let Some(u) = m.get("usage").filter(|u| u.is_object()) {
                    let n = |k: &str| u.get(k).and_then(Value::as_u64).unwrap_or(0);
                    events.push(StatEvent::Request(RequestUsage {
                        at,
                        model,
                        input: n("input"),
                        output: n("output"),
                        cache_read: n("cacheRead"),
                        cache_write: n("cacheWrite"),
                        cost: u.get("cost").and_then(|c| c.get("total")).and_then(Value::as_f64),
                    }));
                }
                for b in m.get("content").and_then(Value::as_array).into_iter().flatten() {
                    if b.get("type").and_then(Value::as_str) == Some("toolCall") {
                        events.push(StatEvent::Tool { name: b.get("name").and_then(Value::as_str).unwrap_or("unknown").to_string(), at });
                    }
                }
            }
            _ => {}
        }
    }
    events
}

/// The `contextWindow` of `model` in pi's `models-store.json` (`{provider: {models: [{id, contextWindow}]}}`): under
/// its provider when `model` is `provider/id`, else the first provider that lists the id.
pub fn context_window(text: &str, model: &str) -> Option<u64> {
    let root: Value = serde_json::from_str(text).ok()?;
    let root = root.as_object()?;
    let in_provider = |p: &Value, id: &str| {
        p.get("models")?.as_array()?.iter().find(|m| m.get("id").and_then(Value::as_str) == Some(id))?.get("contextWindow")?.as_u64()
    };
    if let Some((provider, id)) = model.split_once('/') {
        if let Some(n) = root.get(provider).and_then(|p| in_provider(p, id)) {
            return Some(n);
        }
    }
    root.values().find_map(|p| in_provider(p, model))
}

#[cfg(test)]
mod tests {
    use super::*;
    use infantry_harness::stats::fold;

    #[test]
    fn a_pi_record_folds_with_reported_cost_and_the_catalog_states_the_window() {
        let rec = [
            r#"{"type":"session","timestamp":"2026-11-19T10:00:00.000Z"}"#,
            r#"{"type":"message","timestamp":"2026-11-19T10:00:01.000Z","message":{"role":"user","content":"hi"}}"#,
            r#"{"type":"message","timestamp":"2026-11-19T10:00:02.000Z","message":{"role":"assistant","model":"deepseek-chat","timestamp":1795082402000,"usage":{"input":100,"output":10,"cacheRead":0,"cacheWrite":0,"cost":{"total":0.01}},"content":[{"type":"toolCall","name":"bash"}]}}"#,
            r#"{"type":"message","timestamp":"2026-11-19T10:00:03.000Z","message":{"role":"toolResult","content":[{"type":"text","text":"abc"}]}}"#,
            r#"{"type":"message","timestamp":"2026-11-19T10:00:04.000Z","message":{"role":"assistant","model":"deepseek-chat","usage":{"input":10,"output":5,"cacheRead":120,"cacheWrite":0,"cost":{"total":0.02}},"content":[]}}"#,
        ];
        let s = fold(&read(rec.iter().map(|l| l.to_string())));
        assert_eq!((s.user_prompts, s.assistant_turns, s.tool_uses, s.series.len()), (1, 2, 1, 2));
        assert_eq!(s.top_sources, vec![("bash".to_string(), 1, Some(30))]);
        assert!((s.cost_total.unwrap() - 0.03).abs() < 1e-9);
        assert_eq!(s.last_model.as_deref(), Some("deepseek-chat"));
        let store = r#"{"openai":{"models":[{"id":"gpt-5","contextWindow":400000}]},"deepseek":{"models":[{"id":"deepseek-chat","contextWindow":128000}]}}"#;
        assert_eq!(context_window(store, "deepseek-chat"), Some(128000));
        assert_eq!(context_window(store, "openai/gpt-5"), Some(400000));
        assert_eq!(context_window(store, "nope"), None);
    }
}
