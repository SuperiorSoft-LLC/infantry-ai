// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Token usage read from an agent's JSON stdout, one line at a time, whatever the stream dialect: Claude Code
//! `assistant` / `result` lines, pi `message_end`, opencode `step_finish`. Each turn is reported once (a later line of
//! the same message only raises its output), compactions are reported as events, and [`StreamUsage::totals`] is the
//! run's sum (`result` totals win when the harness prints them).
use std::collections::HashMap;

use serde_json::Value;

use crate::util::{f64_field, long_field, str_field};

/// One model turn's tokens.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Turn {
    pub msg_id: Option<String>,
    pub model: String,
    pub input: i64,
    pub output: i64,
    pub thinking: i64,
    pub cache_read: i64,
    pub cache_write: i64,
    pub cost_usd: Option<f64>,
}

impl Turn {
    /// The prompt this turn carried (input plus cache read and write).
    pub fn context(&self) -> i64 {
        self.input + self.cache_read + self.cache_write
    }

    pub fn total(&self) -> i64 {
        self.input + self.output + self.thinking + self.cache_read + self.cache_write
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum StreamEvent {
    /// A new turn.
    Turn(Turn),
    /// A later line of an already reported message: its output grew to `output`.
    OutputRaised { msg_id: String, output: i64 },
    /// The harness compacted its context (`trigger`: auto / manual; `pre_tokens` when it said).
    Compaction { trigger: String, pre_tokens: Option<i64> },
}

/// The run's sums.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Totals {
    pub model: String,
    pub input: i64,
    pub output: i64,
    pub reasoning: i64,
    pub cache_read: i64,
    pub cache_write: i64,
    pub cost_usd: Option<f64>,
    pub turns: i64,
    /// Sum of each turn's context; `context_sum / turns` is the average prompt size.
    pub context_sum: i64,
}

/// The model name Claude gives its own (non-request) messages.
const SYNTHETIC_MODEL: &str = "<synthetic>";

#[derive(Default)]
pub struct StreamUsage {
    seen: HashMap<String, usize>,
    turns: Vec<Turn>,
    result: Option<Totals>,
    /// A result frame with no `usage` (its cost, turn count and model only).
    result_extra: Option<Totals>,
}

fn n(v: Option<&Value>, k: &str) -> i64 {
    v.and_then(|o| long_field(o, k)).unwrap_or(0)
}

impl StreamUsage {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn accept(&mut self, line: &str) -> Vec<StreamEvent> {
        let t = line.trim();
        if !t.starts_with('{') {
            return Vec::new();
        }
        let Ok(v) = serde_json::from_str::<Value>(t) else { return Vec::new() };
        let kind = str_field(&v, "type").unwrap_or("");
        match kind {
            "assistant" => self.claude_assistant(&v),
            "result" => {
                self.claude_result(&v);
                Vec::new()
            }
            "system" if str_field(&v, "subtype") == Some("compact_boundary") => {
                let m = v.get("compact_metadata");
                vec![StreamEvent::Compaction {
                    trigger: m.and_then(|m| str_field(m, "trigger")).unwrap_or("auto").into(),
                    pre_tokens: m.and_then(|m| long_field(m, "pre_tokens")),
                }]
            }
            "auto_compaction_end" | "compaction_end" => vec![StreamEvent::Compaction {
                trigger: if kind == "auto_compaction_end" { "auto".into() } else { str_field(&v, "trigger").unwrap_or("manual").into() },
                pre_tokens: long_field(&v, "tokensBefore").or_else(|| long_field(&v, "pre_tokens")),
            }],
            "message_end" => self.pi_message_end(&v),
            "step_finish" => self.opencode_step(&v),
            _ => Vec::new(),
        }
    }

    fn push(&mut self, turn: Turn) -> Vec<StreamEvent> {
        if turn.total() == 0 {
            return Vec::new();
        }
        if let Some(id) = turn.msg_id.clone() {
            if let Some(&i) = self.seen.get(&id) {
                let prev = &mut self.turns[i];
                if turn.output > prev.output {
                    prev.output = turn.output;
                    return vec![StreamEvent::OutputRaised { msg_id: id, output: turn.output }];
                }
                return Vec::new();
            }
            self.seen.insert(id, self.turns.len());
        }
        self.turns.push(turn.clone());
        vec![StreamEvent::Turn(turn)]
    }

    fn claude_assistant(&mut self, v: &Value) -> Vec<StreamEvent> {
        let Some(msg) = v.get("message") else { return Vec::new() };
        let u = msg.get("usage");
        if u.is_none() {
            return Vec::new();
        }
        self.push(Turn {
            msg_id: str_field(msg, "id").map(str::to_owned),
            model: str_field(msg, "model").unwrap_or("").into(),
            input: n(u, "input_tokens"),
            output: n(u, "output_tokens"),
            thinking: 0,
            cache_read: n(u, "cache_read_input_tokens"),
            cache_write: n(u, "cache_creation_input_tokens"),
            cost_usd: None,
        })
    }

    /// Kotlin `StreamUsage.claudeResult`: the result's own `usage` (the main model's tokens) is the run's token count;
    /// `total_cost_usd` and `num_turns` are kept; `modelUsage` only names the model when no assistant line did.
    fn claude_result(&mut self, v: &Value) {
        let mut t = Totals { cost_usd: f64_field(v, "total_cost_usd"), turns: long_field(v, "num_turns").unwrap_or(0), ..Default::default() };
        t.model = v.get("modelUsage").and_then(Value::as_object).and_then(|m| m.keys().next().cloned()).unwrap_or_default();
        let Some(u) = v.get("usage").filter(|u| u.is_object()) else {
            // No usage on the result: the streamed turns stay the count; only the cost and turn count are taken.
            self.result_extra = Some(t);
            return;
        };
        t.input = n(Some(u), "input_tokens");
        t.output = n(Some(u), "output_tokens");
        t.cache_read = n(Some(u), "cache_read_input_tokens");
        t.cache_write = n(Some(u), "cache_creation_input_tokens");
        self.result = Some(t);
    }

    fn pi_message_end(&mut self, v: &Value) -> Vec<StreamEvent> {
        let Some(msg) = v.get("message").filter(|m| str_field(m, "role") == Some("assistant")) else { return Vec::new() };
        let u = msg.get("usage");
        if u.is_none() {
            return Vec::new();
        }
        let model = match (str_field(msg, "provider"), str_field(msg, "model")) {
            (Some(p), Some(m)) => format!("{p}/{m}"),
            (_, m) => m.unwrap_or("").into(),
        };
        self.push(Turn {
            msg_id: str_field(msg, "responseId").or(str_field(msg, "id")).map(str::to_owned),
            model,
            input: n(u, "input"),
            output: n(u, "output"),
            thinking: 0,
            cache_read: n(u, "cacheRead"),
            cache_write: n(u, "cacheWrite"),
            cost_usd: u.and_then(|u| u.get("cost")).and_then(|c| f64_field(c, "total")),
        })
    }

    fn opencode_step(&mut self, v: &Value) -> Vec<StreamEvent> {
        let Some(part) = v.get("part") else { return Vec::new() };
        let t = part.get("tokens");
        if t.is_none() {
            return Vec::new();
        }
        let cache = t.and_then(|t| t.get("cache"));
        self.push(Turn {
            msg_id: str_field(part, "id").map(str::to_owned),
            model: str_field(part, "model").unwrap_or("").into(),
            input: n(t, "input"),
            output: n(t, "output"),
            thinking: n(t, "reasoning"),
            cache_read: n(cache, "read"),
            cache_write: n(cache, "write"),
            cost_usd: f64_field(part, "cost"),
        })
    }

    /// `None` when the stream showed no usage at all (Kotlin `StreamUsage.totals`): turns are the model requests counted
    /// from the stream, else the result's `num_turns`, else 1; with a result's usage its tokens are the run's, the
    /// context sum stays per request; cost is the result's, else the requests' summed; the model is the last assistant
    /// line's (never `<synthetic>`), else the result's.
    pub fn totals(&self) -> Option<Totals> {
        if self.result.is_none() && self.turns.is_empty() {
            return None;
        }
        let extra = self.result.as_ref().or(self.result_extra.as_ref());
        let counted = self.turns.len() as i64;
        let turns = if counted > 0 { counted } else { extra.map(|r| r.turns).filter(|n| *n > 0).unwrap_or(1) };
        let mut summed_cost: Option<f64> = None;
        let mut t = Totals::default();
        for turn in &self.turns {
            t.input += turn.input;
            t.output += turn.output;
            t.reasoning += turn.thinking;
            t.cache_read += turn.cache_read;
            t.cache_write += turn.cache_write;
            if let Some(c) = turn.cost_usd {
                summed_cost = Some(summed_cost.unwrap_or(0.0) + c);
            }
            if !turn.model.is_empty() && turn.model != SYNTHETIC_MODEL {
                t.model = turn.model.clone();
            }
        }
        let streamed_context: i64 = self.turns.iter().map(Turn::context).sum();
        if let Some(r) = &self.result {
            t.input = r.input;
            t.output = r.output;
            t.cache_read = r.cache_read;
            t.cache_write = r.cache_write;
            t.context_sum = if counted > 0 { streamed_context } else { r.input + r.cache_read + r.cache_write };
        } else {
            t.context_sum = streamed_context;
        }
        t.cost_usd = extra.and_then(|r| r.cost_usd).or(summed_cost);
        if t.model.is_empty() {
            t.model = extra.map(|r| r.model.clone()).unwrap_or_default();
        }
        t.turns = turns;
        Some(t)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn claude_turns_dedupe_and_result_wins() {
        let mut s = StreamUsage::new();
        let a = r#"{"type":"assistant","message":{"id":"m1","model":"claude-opus","usage":{"input_tokens":10,"output_tokens":2,"cache_read_input_tokens":100}}}"#;
        assert!(matches!(s.accept(a).as_slice(), [StreamEvent::Turn(_)]));
        let b = a.replace("\"output_tokens\":2", "\"output_tokens\":9");
        assert_eq!(s.accept(&b), vec![StreamEvent::OutputRaised { msg_id: "m1".into(), output: 9 }]);
        assert_eq!(s.totals().unwrap().output, 9);
        s.accept(r#"{"type":"system","subtype":"compact_boundary","compact_metadata":{"trigger":"auto","pre_tokens":5}}"#);
        s.accept(r#"{"type":"result","total_cost_usd":0.5,"num_turns":3,"modelUsage":{"claude-opus":{"inputTokens":40,"outputTokens":20}}}"#);
        let t = s.totals().unwrap();
        // Kotlin StreamUsage: a result with no `usage` keeps the streamed tokens and the counted requests; its cost wins.
        assert_eq!((t.input, t.cost_usd, t.turns, t.model.as_str()), (10, Some(0.5), 1, "claude-opus"));
    }

    /// Kotlin `UsageMeterTest`, "claude result totals win and assistant lines give turns and context", same lines and values.
    #[test]
    fn claude_result_totals_win_and_assistant_lines_give_turns_and_context() {
        let mut s = StreamUsage::new();
        for l in [
            r#"{"type":"assistant","message":{"id":"m1","model":"claude-sonnet-5-5","usage":{"input_tokens":10,"output_tokens":1,"cache_read_input_tokens":1000,"cache_creation_input_tokens":200}}}"#,
            r#"{"type":"assistant","message":{"id":"m1","model":"claude-sonnet-5-5","usage":{"input_tokens":10,"output_tokens":1,"cache_read_input_tokens":1000,"cache_creation_input_tokens":200}}}"#,
            r#"{"type":"assistant","message":{"id":"m2","model":"claude-sonnet-5-5","usage":{"input_tokens":20,"output_tokens":2,"cache_read_input_tokens":1200,"cache_creation_input_tokens":0}}}"#,
            r#"{"type":"result","num_turns":2,"total_cost_usd":0.5,"usage":{"input_tokens":30,"output_tokens":90,"cache_read_input_tokens":2200,"cache_creation_input_tokens":200}}"#,
        ] {
            s.accept(l);
        }
        let t = s.totals().unwrap();
        assert_eq!((t.turns, t.input, t.output, t.cache_read, t.cache_write), (2, 30, 90, 2200, 200));
        assert_eq!((t.context_sum, t.cost_usd, t.model.as_str()), (1210 + 1220, Some(0.5), "claude-sonnet-5-5"));
    }

    #[test]
    fn pi_and_opencode_dialects() {
        let mut s = StreamUsage::new();
        s.accept(r#"{"type":"message_end","message":{"role":"assistant","provider":"anthropic","model":"claude-x","usage":{"input":5,"output":5,"cost":{"total":0.01}}}}"#);
        s.accept(r#"{"type":"step_finish","part":{"id":"p","tokens":{"input":1,"output":1,"reasoning":3,"cache":{"read":2}},"cost":0.02}}"#);
        s.accept(r#"{"type":"step_finish","part":{"tokens":{"input":0,"output":0}}}"#);
        let t = s.totals().unwrap();
        assert_eq!((t.input, t.reasoning, t.turns), (6, 3, 2));
        assert!((t.cost_usd.unwrap() - 0.03).abs() < 1e-9);
        assert!(StreamUsage::new().totals().is_none());
    }
}
