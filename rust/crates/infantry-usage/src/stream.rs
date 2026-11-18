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

#[derive(Default)]
pub struct StreamUsage {
    seen: HashMap<String, usize>,
    turns: Vec<Turn>,
    result: Option<Totals>,
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

    fn claude_result(&mut self, v: &Value) {
        let mut t = Totals { cost_usd: f64_field(v, "total_cost_usd"), turns: long_field(v, "num_turns").unwrap_or(0), ..Default::default() };
        if let Some(models) = v.get("modelUsage").and_then(Value::as_object).filter(|m| !m.is_empty()) {
            let mut top = (0i64, String::new());
            for (name, m) in models {
                let (i, o) = (n(Some(m), "inputTokens"), n(Some(m), "outputTokens"));
                t.input += i;
                t.output += o;
                t.cache_read += n(Some(m), "cacheReadInputTokens");
                t.cache_write += n(Some(m), "cacheCreationInputTokens");
                if i + o >= top.0 {
                    top = (i + o, name.clone());
                }
            }
            t.model = top.1;
        } else if let Some(u) = v.get("usage") {
            t.input = n(Some(u), "input_tokens");
            t.output = n(Some(u), "output_tokens");
            t.cache_read = n(Some(u), "cache_read_input_tokens");
            t.cache_write = n(Some(u), "cache_creation_input_tokens");
        } else {
            return;
        }
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

    /// `None` when the stream showed no usage at all.
    pub fn totals(&self) -> Option<Totals> {
        let context_sum = self.turns.iter().map(Turn::context).sum();
        if let Some(r) = &self.result {
            return Some(Totals { context_sum, ..r.clone() });
        }
        if self.turns.is_empty() {
            return None;
        }
        let mut t = Totals::default();
        let mut cost: Option<f64> = None;
        for turn in &self.turns {
            t.input += turn.input;
            t.output += turn.output;
            t.reasoning += turn.thinking;
            t.cache_read += turn.cache_read;
            t.cache_write += turn.cache_write;
            if let Some(c) = turn.cost_usd {
                cost = Some(cost.unwrap_or(0.0) + c);
            }
            if !turn.model.is_empty() {
                t.model = turn.model.clone();
            }
        }
        t.cost_usd = cost;
        t.turns = self.turns.len() as i64;
        t.context_sum = context_sum;
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
        assert_eq!((t.input, t.cost_usd, t.turns, t.model.as_str()), (40, Some(0.5), 3, "claude-opus"));
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
