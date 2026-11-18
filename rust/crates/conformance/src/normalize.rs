// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Turns a raw transcript into a stable one: ids become `<id:N>` by first appearance, timestamps `<ts>`, pids `<pid>`,
//! machine paths `<path>`. The double-encoded `data` string is parsed so the fixture is readable and key-order free.
use infantry_protocol::Envelope;
use serde_json::{json, Map, Value};
use std::collections::HashMap;

/// Pushes the daemon sends on its own clock (plan meters, limits): not part of any session scenario.
pub const AMBIENT: &[&str] = &["usage_report", "usage_meter", "limits", "provider_state", "provider_states", "usage"];

#[derive(Default)]
pub struct Normalizer {
    ids: HashMap<String, usize>,
}

/// Every UUID in `s` (8-4-4-4-12 hex) as byte ranges.
fn find_uuids(s: &str) -> Vec<(usize, usize)> {
    let b = s.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i + 36 <= b.len() {
        let ok = b[i..i + 36].iter().enumerate().all(|(k, c)| {
            if matches!(k, 8 | 13 | 18 | 23) {
                *c == b'-'
            } else {
                c.is_ascii_hexdigit()
            }
        });
        if ok {
            out.push((i, i + 36));
            i += 36;
        } else {
            i += 1;
        }
    }
    out
}

fn is_time_key(k: &str) -> bool {
    let l = k.to_ascii_lowercase();
    l.ends_with("at") || l.ends_with("since") || l.contains("heartbeat") || l.contains("timestamp")
}
fn is_pid_key(k: &str) -> bool {
    k.to_ascii_lowercase().ends_with("pid")
}
fn is_path_key(k: &str) -> bool {
    matches!(k, "workingDir" | "worktreePath" | "repoRoot" | "cwd" | "path" | "logPath")
}

impl Normalizer {
    pub fn new() -> Self {
        Self::default()
    }

    fn string(&mut self, s: &str) -> String {
        let mut out = String::new();
        let mut last = 0;
        for (a, b) in find_uuids(s) {
            out.push_str(&s[last..a]);
            let n = self.ids.len() + 1;
            let n = *self.ids.entry(s[a..b].to_string()).or_insert(n);
            out.push_str(&format!("<id:{n}>"));
            last = b;
        }
        out.push_str(&s[last..]);
        out
    }

    fn value(&mut self, key: &str, v: &Value) -> Value {
        match v {
            Value::String(_) if is_path_key(key) => json!("<path>"),
            Value::String(s) => json!(self.string(s)),
            Value::Number(n) if is_time_key(key) && n.as_i64().is_some_and(|x| x > 0) => json!("<ts>"),
            Value::Number(n) if n.as_i64().is_some_and(|x| x >= 100_000_000_000) => json!("<ts>"),
            Value::Number(n) if is_pid_key(key) && n.as_i64().is_some_and(|x| x > 0) => json!("<pid>"),
            Value::Array(a) => Value::Array(a.iter().map(|x| self.value(key, x)).collect()),
            Value::Object(o) => {
                let mut m = Map::new();
                for (k, x) in o {
                    m.insert(k.clone(), self.value(k, x));
                }
                Value::Object(m)
            }
            other => other.clone(),
        }
    }

    /// `ready` data is `provider=.. contract=.. instance=.. since=.. build=..`; instance, since and build vary per run.
    fn ready(s: &str) -> String {
        s.split(' ')
            .map(|kv| match kv.split_once('=') {
                Some(("instance", _)) => "instance=<id>".to_string(),
                Some(("since", _)) => "since=<ts>".to_string(),
                Some(("build", _)) => "build=<build>".to_string(),
                _ => kv.to_string(),
            })
            .collect::<Vec<_>>()
            .join(" ")
    }

    /// One inbound frame as a fixture entry. `sequence` is dropped: it counts frames, which differ between daemons.
    pub fn frame(&mut self, e: &Envelope) -> Value {
        let data: Value = if e.r#type == "ready" {
            json!(Self::ready(&e.data))
        } else {
            match serde_json::from_str::<Value>(&e.data) {
                Ok(v @ (Value::Object(_) | Value::Array(_))) => self.value("", &v),
                _ => json!(self.string(&e.data)),
            }
        };
        let mut m = Map::new();
        m.insert("type".into(), json!(e.r#type));
        m.insert("sessionId".into(), e.session_id.as_deref().map_or(Value::Null, |s| json!(self.string(s))));
        m.insert("data".into(), data);
        Value::Object(m)
    }

    /// The whole transcript, with runs of identical consecutive frames collapsed (heartbeat pushes repeat).
    pub fn transcript(&mut self, frames: &[Envelope]) -> Vec<Value> {
        let mut out: Vec<Value> = Vec::new();
        for f in frames {
            if AMBIENT.contains(&f.r#type.as_str()) {
                continue;
            }
            let v = self.frame(f);
            if out.last() != Some(&v) {
                out.push(v);
            }
        }
        out
    }
}
