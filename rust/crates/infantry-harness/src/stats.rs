// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Session statistics read from a harness's OWN local session record (Kotlin STATS-1 / STATS-2, `StatsAccumulator`,
//! `RequestUsage`, `HarnessStatEvent`). A harness walks its record in order and reports [`StatEvent`]s; [`fold`] turns
//! them into a [`StoreStats`] the same way for every harness, so the numbers mean the same for all.
//!
//! - context tokens of a request = input + cache read + cache write (what the model saw);
//! - billed tokens = context + output (reasoning is never added: harnesses disagree on whether output holds it);
//! - cost is only what the harness REPORTED, never computed;
//! - context growth between request N and N+1 is credited to a tool only when step N ran exactly one tool; a multi-tool
//!   step's growth goes to `unattributed_tool_tokens` (never split by guess); a shrink (compaction) credits nothing.

use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};

/// One thing a harness's record says, in record order.
#[derive(Debug, Clone, PartialEq)]
pub enum StatEvent {
    /// A record with this time (or none).
    Seen(Option<i64>),
    /// A real user prompt.
    Prompt,
    /// One assistant message (a turn), with its model.
    Turn(Option<String>),
    /// One model request with the token counts the harness reported.
    Request(RequestUsage),
    /// An assistant record at this time (closes a tool step's round trip).
    AssistantAt(Option<i64>),
    /// A tool call made by the most recent request.
    Tool { name: String, at: Option<i64> },
    /// A tool result of this many characters.
    ToolResult { chars: u64, at: Option<i64> },
}

/// The harness-neutral record of one model request.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct RequestUsage {
    pub at: Option<i64>,
    pub model: Option<String>,
    pub input: u64,
    pub output: u64,
    pub cache_read: u64,
    pub cache_write: u64,
    /// What the harness reported this request cost; None = it reports none.
    pub cost: Option<f64>,
}

impl RequestUsage {
    pub fn context_tokens(&self) -> u64 {
        self.input + self.cache_read + self.cache_write
    }
    pub fn billed_tokens(&self) -> u64 {
        self.context_tokens() + self.output
    }
    /// A request that reports no tokens at all (aborted, synthetic) is not a request.
    pub fn is_empty(&self) -> bool {
        self.billed_tokens() == 0
    }
}

/// One point per model request.
#[derive(Debug, Clone, PartialEq)]
pub struct StatsPoint {
    pub at: Option<i64>,
    pub context_tokens: u64,
    pub cumulative_billed_tokens: u64,
    pub cumulative_cost: Option<f64>,
}

/// What a harness's own record says about one session (every field the Kotlin `SessionStats` fills from it).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct StoreStats {
    pub started_at: Option<i64>,
    pub ended_at: Option<i64>,
    pub user_prompts: u64,
    pub assistant_turns: u64,
    pub tool_uses: u64,
    pub models: Vec<String>,
    /// The model of the most recent non-empty request (the one whose context window applies).
    pub last_model: Option<String>,
    pub billed_tokens: u64,
    pub cost_total: Option<f64>,
    pub series: Vec<StatsPoint>,
    /// (tool, calls, tokens added), largest growth first, then most calls, then name.
    pub top_sources: Vec<(String, u64, Option<u64>)>,
    pub unattributed_tool_tokens: u64,
    pub ms_to_first_edit: Option<i64>,
    pub tool_calls_before_first_edit: Option<u64>,
    pub tool_results: u64,
    pub avg_tool_result_chars: Option<u64>,
    pub max_tool_result_chars: Option<u64>,
    pub step_ms_p50: Option<i64>,
    pub step_ms_p90: Option<i64>,
    pub step_ms_mean: Option<i64>,
}

/// Tool names (lowercase) that change a file: a session's "first edit".
const EDIT_TOOLS: [&str; 5] = ["edit", "write", "multiedit", "notebookedit", "apply_patch"];

/// Folds a harness's ordered record (Kotlin `StatsAccumulator`).
pub fn fold(events: &[StatEvent]) -> StoreStats {
    let mut s = StoreStats::default();
    let mut models_seen = HashSet::new();
    let mut calls: HashMap<String, u64> = HashMap::new();
    let mut added: HashMap<String, u64> = HashMap::new();
    let mut pending: Vec<String> = Vec::new();
    let (mut cost, mut any_cost) = (0.0f64, false);
    let (mut first_edit_at, mut result_chars, mut last_result_at) = (None::<i64>, 0u64, None::<i64>);
    let mut step_ms: Vec<i64> = Vec::new();
    for e in events {
        match e {
            StatEvent::Seen(Some(at)) => {
                s.started_at = Some(s.started_at.map_or(*at, |f| f.min(*at)));
                s.ended_at = Some(s.ended_at.map_or(*at, |l| l.max(*at)));
            }
            StatEvent::Seen(None) => {}
            StatEvent::Prompt => s.user_prompts += 1,
            StatEvent::Turn(model) => {
                s.assistant_turns += 1;
                if let Some(m) = model.as_deref().filter(|m| !m.trim().is_empty()) {
                    if models_seen.insert(m.to_string()) {
                        s.models.push(m.to_string());
                    }
                }
            }
            StatEvent::Request(u) => {
                if u.is_empty() {
                    continue;
                }
                if let Some(m) = u.model.as_deref().filter(|m| !m.trim().is_empty()) {
                    s.last_model = Some(m.to_string());
                }
                if let Some(c) = u.cost {
                    cost += c;
                    any_cost = true;
                }
                let context = u.context_tokens();
                if let (Some(prev), false) = (s.series.last().map(|p| p.context_tokens), pending.is_empty()) {
                    if context > prev {
                        let delta = context - prev;
                        if pending.len() == 1 {
                            *added.entry(pending[0].clone()).or_default() += delta;
                        } else {
                            s.unattributed_tool_tokens += delta;
                        }
                    }
                }
                pending.clear();
                s.billed_tokens += u.billed_tokens();
                s.series.push(StatsPoint { at: u.at, context_tokens: context, cumulative_billed_tokens: s.billed_tokens, cumulative_cost: any_cost.then_some(cost) });
            }
            StatEvent::ToolResult { chars, at } => {
                s.tool_results += 1;
                result_chars += chars;
                s.max_tool_result_chars = Some(s.max_tool_result_chars.unwrap_or(0).max(*chars));
                if at.is_some() {
                    last_result_at = *at;
                }
            }
            StatEvent::AssistantAt(at) => {
                if let (Some(from), Some(at)) = (last_result_at, at) {
                    last_result_at = None;
                    if *at >= from {
                        step_ms.push(at - from);
                    }
                }
            }
            StatEvent::Tool { name, at } => {
                if first_edit_at.is_none() && s.tool_calls_before_first_edit.is_none() && EDIT_TOOLS.contains(&name.to_lowercase().as_str()) {
                    s.tool_calls_before_first_edit = Some(s.tool_uses);
                    first_edit_at = *at;
                }
                s.tool_uses += 1;
                *calls.entry(name.clone()).or_default() += 1;
                pending.push(name.clone());
            }
        }
    }
    s.cost_total = any_cost.then_some(cost);
    let mut top: Vec<(String, u64, Option<u64>)> = calls.into_iter().map(|(t, n)| { let a = added.get(&t).copied(); (t, n, a) }).collect();
    top.sort_by(|a, b| b.2.map_or(-1, |x| x as i128).cmp(&a.2.map_or(-1, |x| x as i128)).then(b.1.cmp(&a.1)).then(a.0.cmp(&b.0)));
    s.top_sources = top;
    s.ms_to_first_edit = s.started_at.and_then(|f| first_edit_at.map(|e| e - f));
    if s.tool_results == 0 {
        s.max_tool_result_chars = None;
    } else {
        s.avg_tool_result_chars = Some(result_chars / s.tool_results);
    }
    if !step_ms.is_empty() {
        let mut sorted = step_ms.clone();
        sorted.sort_unstable();
        let pick = |p: f64| sorted[(sorted.len() - 1).min((sorted.len() as f64 * p) as usize)];
        s.step_ms_p50 = Some(pick(0.5));
        s.step_ms_p90 = Some(pick(0.9));
        s.step_ms_mean = Some(step_ms.iter().sum::<i64>() / step_ms.len() as i64);
    }
    s
}

impl StoreStats {
    /// The record's fields in the `session_stats` wire shape (camelCase, as Kotlin's `SessionStats`).
    pub fn to_json(&self, model_context_limit: Option<u64>) -> Value {
        json!({
            "startedAt": self.started_at, "endedAt": self.ended_at,
            "durationMs": self.started_at.zip(self.ended_at).map(|(a, b)| b - a),
            "userPrompts": self.user_prompts, "assistantTurns": self.assistant_turns, "toolUses": self.tool_uses,
            "models": self.models,
            "lastContextTokens": self.series.last().map(|p| p.context_tokens),
            "modelContextLimit": model_context_limit,
            "billedTokens": self.billed_tokens, "costTotal": self.cost_total,
            "series": self.series.iter().map(|p| json!({"at": p.at, "contextTokens": p.context_tokens, "cumulativeBilledTokens": p.cumulative_billed_tokens, "cumulativeCost": p.cumulative_cost})).collect::<Vec<_>>(),
            "topSources": self.top_sources.iter().map(|(t, n, a)| json!({"tool": t, "calls": n, "tokensAdded": a})).collect::<Vec<_>>(),
            "unattributedToolTokens": self.unattributed_tool_tokens,
            "firstContextTokens": self.series.first().map(|p| p.context_tokens),
            "msToFirstEdit": self.ms_to_first_edit, "toolCallsBeforeFirstEdit": self.tool_calls_before_first_edit,
            "toolResults": self.tool_results, "avgToolResultChars": self.avg_tool_result_chars, "maxToolResultChars": self.max_tool_result_chars,
            "stepMsP50": self.step_ms_p50, "stepMsP90": self.step_ms_p90, "stepMsMean": self.step_ms_mean,
        })
    }
}

/// Epoch millis of an ISO-8601 instant (`2026-11-19T12:34:56.789Z`, or with a `+hh:mm` offset); None when it is not one.
pub fn iso_millis(text: &str) -> Option<i64> {
    let t = text.trim();
    let b = t.as_bytes();
    if b.len() < 20 || b[4] != b'-' || b[7] != b'-' || !(b[10] == b'T' || b[10] == b't' || b[10] == b' ') || b[13] != b':' || b[16] != b':' {
        return None;
    }
    let num = |r: std::ops::Range<usize>| t.get(r)?.parse::<i64>().ok();
    let (y, mo, d, h, mi, sec) = (num(0..4)?, num(5..7)?, num(8..10)?, num(11..13)?, num(14..16)?, num(17..19)?);
    if !(1..=12).contains(&mo) || !(1..=31).contains(&d) || h > 23 || mi > 59 || sec > 60 {
        return None;
    }
    let mut i = 19;
    let mut ms = 0i64;
    if b.get(i) == Some(&b'.') {
        i += 1;
        let start = i;
        while i < b.len() && b[i].is_ascii_digit() {
            i += 1;
        }
        let frac = &t[start..i];
        if frac.is_empty() {
            return None;
        }
        let three: String = frac.chars().chain("000".chars()).take(3).collect();
        ms = three.parse().ok()?;
    }
    let offset_min = match t.get(i..)? {
        "Z" | "z" => 0,
        o if o.len() == 6 && (o.starts_with('+') || o.starts_with('-')) && &o[3..4] == ":" => {
            let v = o[1..3].parse::<i64>().ok()? * 60 + o[4..6].parse::<i64>().ok()?;
            if o.starts_with('-') { -v } else { v }
        }
        _ => return None,
    };
    // Days from the civil date (Howard Hinnant's algorithm).
    let (yy, m) = if mo <= 2 { (y - 1, mo + 9) } else { (y, mo - 3) };
    let era = yy.div_euclid(400);
    let yoe = yy - era * 400;
    let doy = (153 * m + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    Some(((days * 86_400 + h * 3600 + mi * 60 + sec) - offset_min * 60) * 1000 + ms)
}

/// Characters of a tool result's content: a string, or the `text` of its blocks (Kotlin `textLength`).
pub fn text_length(content: Option<&Value>) -> u64 {
    match content {
        Some(Value::String(s)) => s.chars().count() as u64,
        Some(Value::Array(a)) => a.iter().map(|b| b.get("text").and_then(Value::as_str).map_or(0, |s| s.chars().count() as u64)).sum(),
        _ => 0,
    }
}

/// A provider session id safe to put in a file name.
pub fn safe_id(psid: &str) -> bool {
    !psid.is_empty() && !psid.contains("..") && psid.chars().all(|c| c.is_ascii_alphanumeric() || "._-".contains(c))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn req(at: i64, input: u64, cache_read: u64, output: u64) -> StatEvent {
        StatEvent::Request(RequestUsage { at: Some(at), model: Some("m".into()), input, output, cache_read, ..Default::default() })
    }

    #[test]
    fn iso_instants_parse_like_java_instant() {
        assert_eq!(iso_millis("1970-01-01T00:00:00Z"), Some(0));
        assert_eq!(iso_millis("2026-11-19T12:34:56.789Z"), Some(1_795_091_696_789));
        assert_eq!(iso_millis("2026-11-19T13:34:56.789+01:00"), Some(1_795_091_696_789));
        assert_eq!(iso_millis("not a time"), None);
    }

    #[test]
    fn growth_after_a_lone_tool_is_credited_and_a_multi_tool_step_is_not_split() {
        let ev = vec![
            StatEvent::Seen(Some(1_000)),
            StatEvent::Prompt,
            StatEvent::Turn(Some("m".into())),
            req(1_000, 100, 0, 10),
            StatEvent::Tool { name: "Read".into(), at: Some(1_000) },
            StatEvent::ToolResult { chars: 40, at: Some(1_500) },
            StatEvent::AssistantAt(Some(2_000)),
            req(2_000, 50, 100, 5), // context 150: Read added 50
            StatEvent::Tool { name: "Grep".into(), at: Some(2_000) },
            StatEvent::Tool { name: "Edit".into(), at: Some(2_100) },
            req(3_000, 10, 190, 5), // context 200: 50 unattributed
            StatEvent::Seen(Some(4_000)),
        ];
        let s = fold(&ev);
        assert_eq!((s.started_at, s.ended_at, s.user_prompts, s.assistant_turns, s.tool_uses), (Some(1_000), Some(4_000), 1, 1, 3));
        assert_eq!(s.billed_tokens, 110 + 155 + 205);
        assert_eq!(s.series.iter().map(|p| p.context_tokens).collect::<Vec<_>>(), vec![100, 150, 200]);
        assert_eq!(s.top_sources[0], ("Read".to_string(), 1, Some(50)));
        assert_eq!(s.unattributed_tool_tokens, 50);
        assert_eq!((s.tool_calls_before_first_edit, s.ms_to_first_edit), (Some(2), Some(1_100)));
        assert_eq!((s.tool_results, s.avg_tool_result_chars, s.step_ms_p50), (1, Some(40), Some(500)));
        assert_eq!(s.cost_total, None);
    }

    #[test]
    fn reported_cost_accumulates_and_empty_requests_are_skipped() {
        let mut a = RequestUsage { input: 10, cost: Some(0.25), ..Default::default() };
        let ev = vec![StatEvent::Request(a.clone()), StatEvent::Request(RequestUsage::default()), { a.cost = Some(0.5); StatEvent::Request(a) }];
        let s = fold(&ev);
        assert_eq!(s.series.len(), 2);
        assert_eq!(s.cost_total, Some(0.75));
        assert_eq!(s.series[1].cumulative_cost, Some(0.75));
    }
}
