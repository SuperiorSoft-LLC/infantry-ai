// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! One Claude Code session read from its own store, `<config dir>/projects/<any>/<providerSessionId>.jsonl`, streamed line
//! by line, as [`StatEvent`]s (Kotlin `ClaudeStats.read`, HARNESS H7b-2). The CLI writes one record per content block of a
//! message, each carrying the usage so far: a message's request keeps its FIRST position and time while its LAST usage
//! wins. Claude's record states no cost, so none is reported. Pure apart from the probe's reads.

use infantry_harness::stats::{iso_millis, safe_id, text_length, RequestUsage, StatEvent};
use infantry_harness::Probe;
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};

/// The session's transcript: the first `<projects>/<dir>/<psid>.jsonl` that exists.
pub fn find(probe: &dyn Probe, projects: &Path, psid: &str) -> Option<PathBuf> {
    if !safe_id(psid) {
        return None;
    }
    probe.list_dir(projects).into_iter().map(|d| d.join(format!("{psid}.jsonl"))).find(|f| probe.exists(f))
}

/// The record's events, in order.
pub fn read(lines: impl Iterator<Item = String>) -> Vec<StatEvent> {
    let mut events: Vec<StatEvent> = Vec::new();
    let mut seen_messages = HashSet::new();
    let mut request_index: HashMap<String, usize> = HashMap::new();
    let mut seen_tools = HashSet::new();
    for line in lines {
        if line.trim().is_empty() {
            continue;
        }
        // Only user/assistant lines are parsed; the rest (attachments, snapshots) only lend a timestamp.
        if !line.contains("\"user\"") && !line.contains("\"assistant\"") {
            if let Some(at) = timestamp_of(&line) {
                events.push(StatEvent::Seen(Some(at)));
            }
            continue;
        }
        let Ok(o) = serde_json::from_str::<Value>(&line) else { continue };
        if !o.is_object() {
            continue;
        }
        let at = o.get("timestamp").and_then(Value::as_str).and_then(iso_millis);
        events.push(StatEvent::Seen(at));
        let Some(message) = o.get("message").filter(|m| m.is_object()) else { continue };
        match o.get("type").and_then(Value::as_str) {
            Some("user") => {
                for b in message.get("content").and_then(Value::as_array).into_iter().flatten() {
                    if b.get("type").and_then(Value::as_str) == Some("tool_result") {
                        events.push(StatEvent::ToolResult { chars: text_length(b.get("content")), at });
                    }
                }
                if !truthy(&o, "isMeta") && !truthy(&o, "isCompactSummary") && !truthy(&o, "isSidechain") && is_prompt(message.get("content")) {
                    events.push(StatEvent::Prompt);
                }
            }
            Some("assistant") => {
                let id = message.get("id").and_then(Value::as_str).map(String::from);
                let model = message.get("model").and_then(Value::as_str).map(String::from);
                events.push(StatEvent::AssistantAt(at));
                if let Some(id) = &id {
                    if seen_messages.insert(id.clone()) {
                        events.push(StatEvent::Turn(model.clone()));
                    }
                    if let Some(u) = message.get("usage").filter(|u| u.is_object()) {
                        let n = |k: &str| u.get(k).and_then(Value::as_u64).unwrap_or(0);
                        let mut req = RequestUsage {
                            at,
                            model: model.clone(),
                            input: n("input_tokens"),
                            output: n("output_tokens"),
                            cache_read: n("cache_read_input_tokens"),
                            cache_write: n("cache_creation_input_tokens"),
                            cost: None,
                        };
                        match request_index.get(id) {
                            None => {
                                request_index.insert(id.clone(), events.len());
                                events.push(StatEvent::Request(req));
                            }
                            Some(&i) => {
                                if let StatEvent::Request(old) = &events[i] {
                                    req.at = old.at.or(at);
                                }
                                events[i] = StatEvent::Request(req);
                            }
                        }
                    }
                }
                for b in message.get("content").and_then(Value::as_array).into_iter().flatten() {
                    if b.get("type").and_then(Value::as_str) == Some("tool_use") {
                        let tid = b.get("id").and_then(Value::as_str);
                        if tid.is_none_or(|t| seen_tools.insert(t.to_string())) {
                            events.push(StatEvent::Tool { name: b.get("name").and_then(Value::as_str).unwrap_or("unknown").to_string(), at });
                        }
                    }
                }
            }
            _ => {}
        }
    }
    events
}

/// `"timestamp":"..."` of a line that is not parsed.
fn timestamp_of(line: &str) -> Option<i64> {
    let i = line.find("\"timestamp\":\"")? + 13;
    let rest = &line[i..];
    iso_millis(&rest[..rest.find('"')?])
}

/// A flag Kotlin reads as text: a `true` boolean or the string "true".
fn truthy(o: &Value, k: &str) -> bool {
    match o.get(k) {
        Some(Value::Bool(b)) => *b,
        Some(Value::String(s)) => s == "true",
        _ => false,
    }
}

/// A real prompt: plain string content, or text blocks with no tool_result (that is a tool's answer).
fn is_prompt(content: Option<&Value>) -> bool {
    match content {
        Some(Value::String(_)) => true,
        Some(Value::Array(a)) => {
            let ty = |b: &Value| b.get("type").and_then(Value::as_str).map(String::from);
            a.iter().all(|b| ty(b).as_deref() != Some("tool_result")) && a.iter().any(|b| ty(b).as_deref() == Some("text"))
        }
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use infantry_harness::stats::fold;

    const RECORD: &str = r#"{"type":"user","timestamp":"2026-11-19T10:00:00.000Z","message":{"role":"user","content":"fix the bug"}}
{"type":"assistant","timestamp":"2026-11-19T10:00:02.000Z","message":{"id":"m1","model":"claude-opus-4","content":[{"type":"text","text":"ok"}],"usage":{"input_tokens":10,"cache_read_input_tokens":1000,"cache_creation_input_tokens":0,"output_tokens":1}}}
{"type":"assistant","timestamp":"2026-11-19T10:00:03.000Z","message":{"id":"m1","model":"claude-opus-4","content":[{"type":"tool_use","id":"t1","name":"Read","input":{}}],"usage":{"input_tokens":10,"cache_read_input_tokens":1000,"cache_creation_input_tokens":0,"output_tokens":20}}}
{"type":"user","timestamp":"2026-11-19T10:00:04.000Z","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"t1","content":"0123456789"}]}}
{"type":"assistant","timestamp":"2026-11-19T10:00:05.000Z","message":{"id":"m2","model":"claude-opus-4","content":[{"type":"text","text":"done"}],"usage":{"input_tokens":5,"cache_read_input_tokens":1300,"cache_creation_input_tokens":5,"output_tokens":3}}}
{"type":"file-history-snapshot","timestamp":"2026-11-19T10:00:09.000Z"}
{"type":"user","isMeta":true,"timestamp":"2026-11-19T10:00:06.000Z","message":{"role":"user","content":"<meta>"}}"#;

    #[test]
    fn one_request_per_message_with_its_last_usage_and_tool_growth_credited() {
        let s = fold(&read(RECORD.lines().map(String::from)));
        assert_eq!((s.user_prompts, s.assistant_turns, s.tool_uses), (1, 2, 1));
        // m1 is one request (final output 20), m2 the second.
        assert_eq!(s.series.len(), 2);
        assert_eq!(s.series[0].context_tokens, 1010);
        assert_eq!(s.series[0].at, iso_millis("2026-11-19T10:00:02.000Z"));
        assert_eq!(s.billed_tokens, 1030 + 1313);
        assert_eq!(s.top_sources, vec![("Read".to_string(), 1, Some(300))]);
        assert_eq!(s.ended_at, iso_millis("2026-11-19T10:00:09.000Z"));
        assert_eq!((s.tool_results, s.max_tool_result_chars, s.step_ms_p50), (1, Some(10), Some(1000)));
        assert_eq!(s.models, vec!["claude-opus-4".to_string()]);
        assert_eq!(s.cost_total, None);
    }
}
