// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The sessions suite. Each scenario opens its own connection, drives the wire, checks what CONTRACT.md promises, and
//! returns the transcript (frames of that scenario's session plus the connection-level ones) for record or compare.
//!
//! The daemon under test must run a scripted provider (Kotlin: `INFANTRY_PROVIDER=stub`) so a turn is cheap and
//! deterministic; the suite never needs a real model.
use crate::client::Client;
use infantry_protocol::session::SessionRecord;
use infantry_protocol::Envelope;
use serde_json::Value;
use std::time::Duration;

pub const SUITE: &str = "sessions";
pub const NAMES: &[&str] = &["handshake", "list_sessions", "spawn_output", "send_input", "interrupt", "kill", "remove"];

pub struct Config {
    pub url: String,
    pub token: Option<String>,
    pub timeout: Duration,
}

pub struct Run {
    pub frames: Vec<Envelope>,
    /// `Err` = a contract check failed.
    pub result: Result<(), String>,
}

const QUIET: Duration = Duration::from_millis(700);

fn data(e: &Envelope) -> Value {
    serde_json::from_str(&e.data).unwrap_or(Value::Null)
}
fn state_of(e: &Envelope) -> String {
    data(e)["state"].as_str().unwrap_or("").to_string()
}
fn is(e: &Envelope, ty: &str, sid: &str) -> bool {
    e.r#type == ty && e.session_id.as_deref() == Some(sid)
}

fn open(cfg: &Config) -> Result<Client, String> {
    let mut c = Client::connect(&cfg.url, cfg.token.as_deref())?;
    let ready = c.wait_for(cfg.timeout, |e| e.r#type == "ready").map_err(|e| format!("no ready frame: {e}"))?;
    if !ready.data.starts_with("provider=") || !ready.data.contains(" contract=") {
        return Err(format!("ready is not `provider=.. contract=..`: {}", ready.data));
    }
    Ok(c)
}

/// Start a session with a first prompt (`new` alone starts nothing) and wait for the turn to finish.
fn spawn(c: &mut Client, cfg: &Config) -> Result<String, String> {
    c.send_frame("prompt", "conformance: first turn", None)?;
    let first = c.wait_for(cfg.timeout, |e| e.session_id.is_some())?;
    let sid = first.session_id.unwrap();
    c.wait_for(cfg.timeout, |e| is(e, "finished", &sid))?;
    c.wait_for(cfg.timeout, |e| is(e, "status", &sid) && state_of(e) == "IDLE")?;
    Ok(sid)
}

fn cleanup(c: &mut Client, sid: &str) {
    let _ = c.send_frame("kill", &format!("{sid} force"), None);
    let _ = c.drain(QUIET, Duration::from_secs(5));
    let _ = c.send_frame("session_delete", sid, None);
    let _ = c.drain(QUIET, Duration::from_secs(5));
}

/// Decode every `session` / `sessions` frame with `infantry-protocol` and write it back: the result must equal the wire.
fn protocol_check(frames: &[Envelope]) -> Result<(), String> {
    let mut bad = Vec::new();
    for f in frames {
        let recs: Vec<Value> = match f.r#type.as_str() {
            "session" => vec![data(f)],
            "sessions" => data(f).as_array().cloned().unwrap_or_default(),
            _ => continue,
        };
        for wire in recs {
            match serde_json::from_value::<SessionRecord>(wire.clone()) {
                Err(e) => bad.push(format!("SessionRecord does not decode: {e}")),
                Ok(r) => {
                    let back = serde_json::to_value(&r).unwrap();
                    if back != wire {
                        bad.push(format!("SessionRecord round trip differs: {}", first_diff(&wire, &back)));
                    }
                }
            }
        }
    }
    bad.dedup();
    if bad.is_empty() { Ok(()) } else { Err(bad.join("; ")) }
}

fn first_diff(a: &Value, b: &Value) -> String {
    if let (Value::Object(x), Value::Object(y)) = (a, b) {
        for (k, v) in x {
            if y.get(k) != Some(v) {
                return format!("key `{k}`: wire {v} vs protocol {}", y.get(k).map_or("<absent>".into(), |v| v.to_string()));
            }
        }
        for k in y.keys() {
            if !x.contains_key(k) {
                return format!("key `{k}`: wire <absent> vs protocol {}", y[k]);
            }
        }
    }
    "values differ".into()
}

pub fn run(name: &str, cfg: &Config) -> Run {
    let mut c = match open(cfg) {
        Ok(c) => c,
        Err(e) => return Run { frames: vec![], result: Err(e) },
    };
    let mut sid: Option<String> = None;
    let result = body(name, cfg, &mut c, &mut sid);
    let mut frames: Vec<Envelope> = c.seen.clone();
    if let Some(s) = &sid {
        frames.retain(|f| f.session_id.is_none() || f.session_id.as_deref() == Some(s));
    }
    let result = result.and_then(|_| protocol_check(&frames)).and_then(|_| {
        if c.drift.is_empty() { Ok(()) } else { Err(c.drift.join("; ")) }
    });
    if let Some(s) = &sid {
        cleanup(&mut c, s);
    }
    c.close();
    Run { frames, result }
}

fn body(name: &str, cfg: &Config, c: &mut Client, sid: &mut Option<String>) -> Result<(), String> {
    let t = cfg.timeout;
    match name {
        // ready -> policy burst, nothing else unsolicited that matters.
        "handshake" => {
            c.wait_for(t, |e| e.r#type == "policy")?;
            c.drain(QUIET, t)?;
            Ok(())
        }
        "list_sessions" => {
            c.drain(QUIET, t)?;
            c.send_frame("sessions", "", None)?;
            let l = c.wait_for(t, |e| e.r#type == "sessions")?;
            if !data(&l).is_array() {
                return Err("`sessions` data is not a JSON array".into());
            }
            Ok(())
        }
        "spawn_output" => {
            c.send_frame("prompt", "conformance: spawn", None)?;
            let s = c.wait_for(t, |e| e.session_id.is_some())?.session_id.unwrap();
            *sid = Some(s.clone());
            c.wait_for(t, |e| is(e, "session", &s))?;
            c.wait_for(t, |e| is(e, "status", &s) && state_of(e) == "BUSY")?;
            c.wait_for(t, |e| is(e, "text_delta", &s))?;
            let fin = c.wait_for(t, |e| is(e, "finished", &s))?;
            if data(&fin)["outcome"] != "SUCCEEDED" {
                return Err(format!("finished outcome is not SUCCEEDED: {}", fin.data));
            }
            c.wait_for(t, |e| is(e, "status", &s) && state_of(e) == "IDLE")?;
            c.drain(QUIET, t)?;
            Ok(())
        }
        "send_input" => {
            let s = spawn(c, cfg)?;
            *sid = Some(s.clone());
            c.send_frame("prompt", "conformance: input", Some(&s))?;
            let turn = c.wait_for(t, |e| is(e, "turn", &s))?;
            if turn.data != "conformance: input" {
                return Err(format!("turn echo differs: {}", turn.data));
            }
            c.wait_for(t, |e| is(e, "text_delta", &s))?;
            c.wait_for(t, |e| is(e, "finished", &s))?;
            c.drain(QUIET, t)?;
            Ok(())
        }
        "interrupt" => {
            let s = spawn(c, cfg)?;
            *sid = Some(s.clone());
            c.send_frame("prompt", "conformance: interrupt me", Some(&s))?;
            c.wait_for(t, |e| is(e, "status", &s) && state_of(e) == "BUSY")?;
            c.send_frame("interrupt", &s, None)?;
            c.wait_for(t, |e| is(e, "status", &s) && state_of(e) == "IDLE")?;
            let mark = c.seen.len();
            c.drain(Duration::from_millis(1500), Duration::from_secs(4))?;
            if c.seen[mark..].iter().any(|e| is(e, "finished", &s)) {
                return Err("interrupt must send NO `finished` frame".into());
            }
            Ok(())
        }
        "kill" => {
            let s = spawn(c, cfg)?;
            *sid = Some(s.clone());
            c.send_frame("kill", &s, None)?;
            let fin = c.wait_for(t, |e| is(e, "finished", &s))?;
            if data(&fin)["outcome"] != "KILLED_GRACEFUL" {
                return Err(format!("kill outcome is not KILLED_GRACEFUL: {}", fin.data));
            }
            c.wait_for(t, |e| is(e, "session", &s) && state_of(e) == "EXITED")?;
            c.drain(QUIET, t)?;
            Ok(())
        }
        "remove" => {
            let s = spawn(c, cfg)?;
            *sid = Some(s.clone());
            c.send_frame("kill", &s, None)?;
            c.wait_for(t, |e| is(e, "session", &s) && state_of(e) == "EXITED")?;
            c.drain(QUIET, t)?;
            c.send_frame("session_delete", &s, None)?;
            let l = c.wait_for(t, |e| e.r#type == "sessions" && e.session_id.is_none())?;
            let gone = data(&l).as_array().is_some_and(|a| a.iter().all(|r| r["id"] != s.as_str()));
            if !gone {
                return Err("session is still listed after session_delete".into());
            }
            *sid = None;
            Ok(())
        }
        other => Err(format!("unknown scenario `{other}`")),
    }
}
