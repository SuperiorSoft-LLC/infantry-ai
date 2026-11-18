// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The interactive claude's transcript (`<config dir>/projects/<cwd slug>/<session id>.jsonl`, one JSON record per line)
//! read as the stream-json records headless mode would have printed, plus the turn signals the translator needs. Pure.
//! WHAT it reads is Kotlin's `ClaudeTranscript.kt` (CLI-PTY-1).

use serde_json::{json, Map, Value};

#[derive(Debug, Clone, PartialEq)]
pub enum Signal {
    /// A stream-json record (as text) to report as if the CLI printed it.
    Line(String),
    /// A typed prompt was recorded (the CLI took the turn).
    PromptRecorded,
    /// A prompt the CLI made itself (its `origin.kind`, e.g. `task-notification`).
    OwnPrompt(String),
    /// The CLI recorded that the user interrupted the turn (Esc).
    Interrupted,
    /// The CLI recorded that its turn ended (`system`/`turn_duration`).
    TurnEnded,
    /// The CLI gave up on the request (an API error it shows as an assistant message).
    ApiError(String),
}

pub const SYNTHETIC_MODEL: &str = "<synthetic>";
pub const INTERRUPTED_PREFIX: &str = "[Request interrupted by user";
pub const TURN_DURATION: &str = "turn_duration";
pub const ORIGIN_HUMAN: &str = "human";
pub const USAGE_KEYS: [&str; 4] = ["input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens"];

/// USD per million tokens; the first entry whose `matches` is in the model id wins. An estimate at list price.
pub struct ModelPrice {
    pub matches: &'static str,
    pub input: f64,
    pub output: f64,
    pub cache_read: f64,
    pub cache_write: f64,
}

pub const PRICES: [ModelPrice; 3] = [
    ModelPrice { matches: "haiku", input: 1.0, output: 5.0, cache_read: 0.10, cache_write: 1.25 },
    ModelPrice { matches: "sonnet", input: 3.0, output: 15.0, cache_read: 0.30, cache_write: 3.75 },
    ModelPrice { matches: "opus", input: 5.0, output: 25.0, cache_read: 0.50, cache_write: 6.25 },
];

fn cost(model: &str, u: &Value) -> Option<f64> {
    let p = PRICES.iter().find(|p| model.contains(p.matches))?;
    let n = |k: &str| u.get(k).and_then(Value::as_u64).unwrap_or(0) as f64;
    Some(
        (n("input_tokens") * p.input
            + n("output_tokens") * p.output
            + n("cache_read_input_tokens") * p.cache_read
            + n("cache_creation_input_tokens") * p.cache_write)
            / 1_000_000.0,
    )
}

fn s<'a>(o: &'a Value, k: &str) -> Option<&'a str> {
    o.get(k).and_then(Value::as_str)
}

fn flag(o: &Value, k: &str) -> bool {
    match o.get(k) {
        Some(Value::Bool(v)) => *v,
        Some(Value::String(v)) => v == "true",
        _ => false,
    }
}

/// One per session: it sums the running turn's usage until [`TranscriptMapper::result`] closes the turn.
pub struct TranscriptMapper {
    session_id: String,
    /// The last usage of each assistant message id in the turn (claude writes one record per content block, each with
    /// the usage so far), in order, with the message's model.
    usage: Vec<(String, Value, Option<String>)>,
    last_text: String,
}

impl TranscriptMapper {
    pub fn new(session_id: &str) -> Self {
        TranscriptMapper { session_id: session_id.into(), usage: Vec::new(), last_text: String::new() }
    }

    pub fn map(&mut self, line: &str) -> Vec<Signal> {
        let Ok(record) = serde_json::from_str::<Value>(line.trim()) else { return Vec::new() };
        if !record.is_object() || flag(&record, "isSidechain") {
            return Vec::new();
        }
        match s(&record, "type") {
            Some("assistant") => self.assistant(&record),
            Some("user") => self.user(&record),
            Some("system") => self.system(&record),
            _ => Vec::new(),
        }
    }

    fn line(&self, kind: &str, message: &Value, record: &Value) -> Signal {
        let mut o = Map::new();
        o.insert("type".into(), json!(kind));
        o.insert("message".into(), message.clone());
        o.insert("parent_tool_use_id".into(), Value::Null);
        o.insert("session_id".into(), json!(self.session_id));
        if let Some(u) = s(record, "uuid") {
            o.insert("uuid".into(), json!(u));
        }
        Signal::Line(Value::Object(o).to_string())
    }

    fn assistant(&mut self, record: &Value) -> Vec<Signal> {
        let Some(message) = record.get("message").filter(|m| m.is_object()) else { return Vec::new() };
        if flag(record, "isApiErrorMessage") {
            let t = texts(message.get("content"));
            return vec![Signal::ApiError(if t.trim().is_empty() { "claude reported an API error".into() } else { t })];
        }
        if s(message, "model") == Some(SYNTHETIC_MODEL) {
            return Vec::new();
        }
        if let (Some(id), Some(u)) = (s(message, "id"), message.get("usage").filter(|u| u.is_object())) {
            let model = s(message, "model").map(str::to_string);
            match self.usage.iter_mut().find(|(k, _, _)| k == id) {
                Some(e) => {
                    e.1 = u.clone();
                    if model.is_some() {
                        e.2 = model;
                    }
                }
                None => self.usage.push((id.to_string(), u.clone(), model)),
            }
        }
        let t = texts(message.get("content"));
        if !t.trim().is_empty() {
            self.last_text = t;
        }
        vec![self.line("assistant", message, record)]
    }

    fn system(&mut self, record: &Value) -> Vec<Signal> {
        match s(record, "subtype") {
            Some(TURN_DURATION) => vec![Signal::TurnEnded],
            Some("compact_boundary") => {
                let mut o = Map::new();
                o.insert("type".into(), json!("system"));
                o.insert("subtype".into(), json!("compact_boundary"));
                if let Some(md) = record.get("compactMetadata").or_else(|| record.get("compact_metadata")) {
                    let scalar = |v: &&Value| !v.is_object() && !v.is_array();
                    let mut m = Map::new();
                    if let Some(t) = md.get("trigger").filter(scalar) {
                        m.insert("trigger".into(), t.clone());
                    }
                    if let Some(p) = md.get("preTokens").or_else(|| md.get("pre_tokens")).filter(scalar) {
                        m.insert("pre_tokens".into(), p.clone());
                    }
                    o.insert("compact_metadata".into(), Value::Object(m));
                }
                o.insert("session_id".into(), json!(self.session_id));
                vec![Signal::Line(Value::Object(o).to_string())]
            }
            _ => Vec::new(),
        }
    }

    fn user(&mut self, record: &Value) -> Vec<Signal> {
        if flag(record, "isMeta") {
            return Vec::new();
        }
        let Some(message) = record.get("message").filter(|m| m.is_object()) else { return Vec::new() };
        let content = message.get("content");
        let blocks: Vec<&Value> =
            content.and_then(Value::as_array).map(|a| a.iter().filter(|v| v.is_object()).collect()).unwrap_or_default();
        if blocks.iter().any(|b| s(b, "type") == Some("tool_result")) {
            return vec![self.line("user", message, record)];
        }
        let text = match content {
            Some(Value::String(t)) => t.clone(),
            _ => blocks.iter().filter(|b| s(b, "type") == Some("text")).filter_map(|b| s(b, "text")).collect::<Vec<_>>().join("\n"),
        };
        let origin = record.get("origin").and_then(|o| s(o, "kind")).filter(|k| !k.trim().is_empty());
        if text.starts_with(INTERRUPTED_PREFIX) {
            vec![Signal::Interrupted]
        } else if text.trim().is_empty() {
            Vec::new()
        } else if let Some(kind) = origin.filter(|k| *k != ORIGIN_HUMAN) {
            vec![Signal::OwnPrompt(kind.to_string())]
        } else {
            vec![Signal::PromptRecorded]
        }
    }

    /// The stream-json `result` record that closes the turn: its summed usage, last text and estimated cost. Resets the turn.
    pub fn result(&mut self, error: Option<&str>) -> Value {
        let mut o = Map::new();
        o.insert("type".into(), json!("result"));
        o.insert("subtype".into(), json!(if error.is_some() { "error_during_execution" } else { "success" }));
        o.insert("is_error".into(), json!(error.is_some()));
        o.insert("result".into(), json!(error.map(str::to_string).unwrap_or_else(|| self.last_text.clone())));
        o.insert("session_id".into(), json!(self.session_id));
        if !self.usage.is_empty() {
            let mut u = Map::new();
            for k in USAGE_KEYS {
                let sum: u64 = self.usage.iter().map(|(_, v, _)| v.get(k).and_then(Value::as_u64).unwrap_or(0)).sum();
                u.insert(k.into(), json!(sum));
            }
            o.insert("usage".into(), Value::Object(u));
        }
        let costs: Vec<f64> = self.usage.iter().filter_map(|(_, u, m)| m.as_deref().and_then(|m| cost(m, u))).collect();
        if !costs.is_empty() {
            o.insert("total_cost_usd".into(), json!(costs.iter().sum::<f64>()));
            o.insert("cost_estimated".into(), json!(true));
        }
        self.usage.clear();
        self.last_text.clear();
        Value::Object(o)
    }

    /// The stream-json `system`/`init` record, so a reader learns the session id as from a headless start.
    pub fn init(&self) -> Value {
        json!({"type": "system", "subtype": "init", "session_id": self.session_id})
    }
}

fn texts(content: Option<&Value>) -> String {
    match content {
        Some(Value::String(t)) => t.clone(),
        Some(Value::Array(a)) => {
            a.iter().filter(|b| s(b, "type") == Some("text")).filter_map(|b| s(b, "text")).collect::<Vec<_>>().join("\n")
        }
        _ => String::new(),
    }
}

/// claude's project folder name for [cwd]: every character that is not an ASCII letter or digit becomes `-`.
pub fn slug(cwd: &str) -> String {
    cwd.chars().map(|c| if c.is_ascii_alphanumeric() { c } else { '-' }).collect()
}
