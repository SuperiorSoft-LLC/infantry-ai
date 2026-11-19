// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Turns a raw transcript into a stable one: ids become `<id:N>` by first appearance, timestamps `<ts>`, pids `<pid>`,
//! machine paths `<path>` (folder-valued keys, and absolute paths inside any string). The double-encoded `data` string is parsed so the fixture is readable and key-order free.
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

/// Where a path token ends: whitespace, a quote, or a closing bracket / separator punctuation.
fn path_end(c: char) -> bool {
    c.is_whitespace() || matches!(c, '"' | '\'' | '`' | ')' | ']' | '}' | ',' | ';' | '<' | '>' | '|')
}

/// Every absolute path in `s` as byte ranges: a drive path (`C:\..`, `C:/..`), a UNC path (`\\host\..`), or a Unix path
/// (`/a/b`) at the start of a token. A path runs to the next [path_end] character, so a path with a space in it is
/// blanked up to the space only. URLs are left alone (`ws://h/p` has no `/` at a token start).
fn find_paths(s: &str) -> Vec<(usize, usize)> {
    let b = s.as_bytes();
    let mut out = Vec::new();
    let mut i = 0;
    while i < b.len() {
        let at_token = i == 0 || s[..i].chars().next_back().is_some_and(|c| path_end(c) || c == '(' || c == '[' || c == '=');
        let drive = i + 2 < b.len() && b[i].is_ascii_alphabetic() && b[i + 1] == b':' && (b[i + 2] == b'\\' || b[i + 2] == b'/');
        let unc = i + 2 < b.len() && b[i] == b'\\' && b[i + 1] == b'\\' && b[i + 2].is_ascii_alphanumeric();
        let unix = b[i] == b'/' && i + 1 < b.len() && (b[i + 1].is_ascii_alphanumeric() || b[i + 1] == b'.' || b[i + 1] == b'_');
        if at_token && (drive || unc || unix) {
            let end = s[i..].char_indices().find(|(_, c)| path_end(*c)).map_or(s.len(), |(k, _)| i + k);
            // A lone `/word` is a command or a route (`/exit`, `/health`), not a machine path.
            if !unix || s[i + 1..end].contains('/') {
                out.push((i, end));
                i = end;
                continue;
            }
        }
        i += s[i..].chars().next().map_or(1, char::len_utf8);
    }
    out
}

/// `s` with every absolute path replaced by `<path>`.
pub fn blank_paths(s: &str) -> String {
    let mut out = String::new();
    let mut last = 0;
    for (a, b) in find_paths(s) {
        out.push_str(&s[last..a]);
        out.push_str("<path>");
        last = b;
    }
    out.push_str(&s[last..]);
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
        let s = &blank_paths(s);
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
            // The scripting plugin's `automation-stats` push (one per wake) is not a session frame; the Kotlin fixtures were
            // captured with no scripting plugin, so it is ambient here too.
            if v.get("type").and_then(Value::as_str) == Some("timeline_item")
                && v.pointer("/data/source").and_then(Value::as_str) == Some("automation-stats")
            {
                continue;
            }
            if out.last() != Some(&v) {
                out.push(v);
            }
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::blank_paths;

    #[test]
    fn absolute_paths_inside_strings_are_blanked() {
        assert_eq!(blank_paths(r"starting stub model default in C:\Users\a\.worktrees\rust-conf-1"), "starting stub model default in <path>");
        assert_eq!(blank_paths("in /home/a/work, then (D:/x/y)"), "in <path>, then (<path>)");
        assert_eq!(blank_paths("share \\\\box\\s\\d done"), "share <path> done");
        // Not paths: a URL, a slash command, a ratio, a route.
        for keep in ["ws://127.0.0.1:18961/ws", "/exit", "3/4 done", "GET /health"] {
            assert_eq!(blank_paths(keep), keep);
        }
    }
}
