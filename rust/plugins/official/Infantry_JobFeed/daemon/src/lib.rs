// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry_JobFeed, daemon half: the two POST routes of the Job Feed box. A client PULLS: `jobs` lists a session's
//! watched jobs, `read` returns the lines appended since the client's offset. Nothing runs between reads. A read
//! that comes sooner than `jobfeed.minIntervalMs` after the previous one for the same (session, job) is answered
//! `throttled` with the same offset. Lines are ANSI-stripped and cut to `jobfeed.maxLineChars`.
//!
//! HOST NEED: a guest cannot see a session's jobs yet. This plugin speaks two frames the wiring unit must serve:
//! `session_jobs` `{"sessionId"}` -> `jobs` `[{id,label,startedAt,running,exitCode?}]` and `session_job_log`
//! `{"sessionId","jobId","from","maxBytes"}` -> `job_log` `{text,nextOffset,size,reset,job:{...}}` (or `error`/empty
//! data when the job is unknown).
use infantry_plugin_sdk::types::{Frame, HttpResponse, RouteRequest};
use infantry_plugin_sdk::{export_plugin, host, Plugin};
use serde_json::{json, Value};
use std::collections::VecDeque;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

const DEFAULT_MIN_INTERVAL_MS: u64 = 200;
const DEFAULT_MAX_LINE_CHARS: usize = 300;
const DEFAULT_MAX_TRACKED: usize = 200;
const DEFAULT_READ_BYTES: u64 = 16_384;
const TIMEOUT_MS: u32 = 15_000;

static LAST_READ: Mutex<VecDeque<(String, u64)>> = Mutex::new(VecDeque::new());

pub struct JobFeed;

/// Removes ANSI CSI/OSC sequences and carriage returns.
pub fn strip_ansi(s: &str) -> String {
    let c: Vec<char> = s.chars().collect();
    let mut out = String::with_capacity(s.len());
    let mut i = 0;
    while i < c.len() {
        if c[i] == '\u{1b}' && i + 1 < c.len() {
            if c[i + 1] == '[' {
                i += 2;
                while i < c.len() && !('@'..='~').contains(&c[i]) {
                    i += 1;
                }
                i += 1;
                continue;
            }
            if c[i + 1] == ']' {
                i += 2;
                while i < c.len() && c[i] != '\u{7}' {
                    i += 1;
                }
                i += 1;
                continue;
            }
        }
        if c[i] != '\r' {
            out.push(c[i]);
        }
        i += 1;
    }
    out
}

pub fn shape(text: &str, cut: usize, strip: bool) -> String {
    if text.is_empty() || (cut == 0 && !strip) {
        return text.to_string();
    }
    text.split('\n')
        .map(|raw| {
            let l = if strip { strip_ansi(raw) } else { raw.to_string() };
            if cut > 0 && l.chars().count() > cut {
                format!("{}...", l.chars().take(cut).collect::<String>())
            } else {
                l
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

/// True when this read is too soon after the last one (and records it otherwise).
fn throttled(key: &str, now: u64, gap: u64, max_tracked: usize) -> bool {
    let mut q = LAST_READ.lock().unwrap();
    if gap > 0 {
        if let Some((_, t)) = q.iter().find(|(k, _)| k == key) {
            if now.saturating_sub(*t) < gap {
                return true;
            }
        }
    }
    q.retain(|(k, _)| k != key);
    q.push_back((key.to_string(), now));
    while q.len() > max_tracked.max(1) {
        q.pop_front();
    }
    false
}

fn cfg<T: std::str::FromStr>(k: &str, d: T) -> T {
    host::config::get(k).and_then(|v| v.trim().parse().ok()).unwrap_or(d)
}

fn field(b: &Value, k: &str) -> Option<String> {
    b.get(k).and_then(|v| v.as_str()).map(str::trim).filter(|s| !s.is_empty()).map(String::from)
}

fn ok(v: Value) -> HttpResponse {
    HttpResponse { status: 200, headers: vec![("content-type".into(), "application/json".into())], body: v.to_string().into_bytes() }
}

fn job_json(j: &Value) -> Value {
    let mut o = json!({"id": j.get("id").cloned().unwrap_or(Value::Null), "label": j.get("label").cloned().unwrap_or(Value::Null), "startedAt": j.get("startedAt").cloned().unwrap_or(Value::Null), "running": j.get("running").cloned().unwrap_or(Value::Null)});
    if let Some(e) = j.get("exitCode").filter(|v| !v.is_null()) {
        o["exitCode"] = e.clone();
    }
    o
}

pub fn jobs_body(sid: Option<&str>, ask: &dyn Fn(&str, &str, Option<&str>) -> Result<Frame, String>) -> Value {
    let jobs: Vec<Value> = match sid {
        Some(s) => ask("session_jobs", &json!({"sessionId": s}).to_string(), Some(s)).ok().and_then(|f| serde_json::from_str::<Value>(&f.data).ok()).and_then(|v| v.as_array().cloned()).unwrap_or_default().iter().map(job_json).collect(),
        None => vec![],
    };
    json!({"sessionId": sid.unwrap_or(""), "jobs": jobs})
}

pub fn read_body(b: &Value, ask: &dyn Fn(&str, &str, Option<&str>) -> Result<Frame, String>, now: u64) -> Value {
    let sid = field(b, "sessionId");
    let job = field(b, "jobId");
    let from = b.get("from").and_then(|v| v.as_i64()).unwrap_or(-1);
    let max = b.get("maxBytes").and_then(|v| v.as_u64()).unwrap_or(DEFAULT_READ_BYTES);
    let unknown = |job: &Option<String>| json!({"jobId": job.clone().unwrap_or_default(), "text": "", "throttled": false, "known": false, "next": from});
    let (Some(sid), Some(jid)) = (sid, job.clone()) else { return unknown(&job) };
    if throttled(&format!("{sid}/{jid}"), now, cfg("jobfeed.minIntervalMs", DEFAULT_MIN_INTERVAL_MS), cfg("jobfeed.maxTracked", DEFAULT_MAX_TRACKED)) {
        return json!({"jobId": jid, "text": "", "throttled": true, "known": false, "next": from});
    }
    let req = json!({"sessionId": sid, "jobId": jid, "from": from, "maxBytes": max}).to_string();
    let Some(chunk) = ask("session_job_log", &req, Some(&sid)).ok().filter(|f| f.type_ != "error").and_then(|f| serde_json::from_str::<Value>(&f.data).ok()).filter(|v| v.is_object()) else { return unknown(&job) };
    let strip = host::config::get("jobfeed.stripAnsi").map(|v| v != "false").unwrap_or(true);
    let text = shape(chunk.get("text").and_then(|v| v.as_str()).unwrap_or(""), cfg("jobfeed.maxLineChars", DEFAULT_MAX_LINE_CHARS), strip);
    let j = chunk.get("job").cloned().unwrap_or(Value::Null);
    let mut o = json!({
        "jobId": jid, "text": text, "throttled": false, "known": true,
        "next": chunk.get("nextOffset").cloned().unwrap_or(Value::Null), "size": chunk.get("size").cloned().unwrap_or(Value::Null),
        "reset": chunk.get("reset").cloned().unwrap_or(false.into()), "label": j.get("label").cloned().unwrap_or(Value::Null),
        "startedAt": j.get("startedAt").cloned().unwrap_or(Value::Null), "running": j.get("running").cloned().unwrap_or(Value::Null)
    });
    if let Some(e) = j.get("exitCode").filter(|v| !v.is_null()) {
        o["exitCode"] = e.clone();
    }
    o
}

impl Plugin for JobFeed {
    fn handle_route(request: RouteRequest) -> Result<HttpResponse, String> {
        let b: Value = serde_json::from_slice(&request.body).unwrap_or(Value::Null);
        let ask = |ty: &str, data: &str, s: Option<&str>| host::frames::request(ty, data, s, TIMEOUT_MS);
        match (request.method.to_uppercase().as_str(), request.path.rsplit('/').next().unwrap_or("")) {
            ("POST", "jobs") => Ok(ok(jobs_body(field(&b, "sessionId").as_deref(), &ask))),
            ("POST", "read") => Ok(ok(read_body(&b, &ask, now_ms()))),
            _ => Err(format!("no route {} {}", request.method, request.path)),
        }
    }
}

export_plugin!(JobFeed);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ansi_and_cut() {
        assert_eq!(shape("\u{1b}[31mred\u{1b}[0m\r\nabcdef", 3, true), "red\nabc...");
        assert_eq!(shape("x", 0, false), "x");
    }

    #[test]
    fn rate_cap() {
        assert!(!throttled("a/b", 1000, 200, 10));
        assert!(throttled("a/b", 1100, 200, 10));
        assert!(!throttled("a/b", 1300, 200, 10));
    }
}
