// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Golden fixtures on disk: `<dir>/<suite>/<scenario>.json` = `{scenario, frames}` plus the raw frames in
//! `<dir>/raw/<scenario>.json` (un-normalised, for the protocol crate checks).
use infantry_protocol::Envelope;
use serde_json::{json, Value};
use std::path::Path;

pub fn write(dir: &Path, suite: &str, scenario: &str, frames: &[Value], raw: &[Envelope]) -> Result<(), String> {
    let d = dir.join(suite);
    std::fs::create_dir_all(&d).map_err(|e| e.to_string())?;
    let body = json!({ "scenario": scenario, "frames": frames });
    std::fs::write(d.join(format!("{scenario}.json")), serde_json::to_string_pretty(&body).unwrap() + "\n")
        .map_err(|e| e.to_string())?;
    let r = dir.join("raw");
    std::fs::create_dir_all(&r).map_err(|e| e.to_string())?;
    let raw: Vec<Value> = raw.iter().map(|e| serde_json::to_value(e).unwrap()).collect();
    std::fs::write(r.join(format!("{scenario}.json")), serde_json::to_string_pretty(&raw).unwrap() + "\n")
        .map_err(|e| e.to_string())
}

pub fn read(dir: &Path, suite: &str, scenario: &str) -> Result<Vec<Value>, String> {
    let p = dir.join(suite).join(format!("{scenario}.json"));
    let t = std::fs::read_to_string(&p).map_err(|e| format!("{}: {e}", p.display()))?;
    let v: Value = serde_json::from_str(&t).map_err(|e| e.to_string())?;
    v["frames"].as_array().cloned().ok_or_else(|| "no frames".into())
}

/// `session` pushes are heartbeat driven (their count and the last one are timing); everything else is compared in order.
/// Diff: the non-`session` frames in order, then the SET of distinct `session` snapshots. `None` = identical.
/// Frame types whose count and interleaving are timing (heartbeat pushes, status and raw provider lines): compared as sets.
const SETLIKE: &[&str] = &["session", "status", "stdout", "stderr"];

pub fn diff(expected: &[Value], actual: &[Value]) -> Option<String> {
    let split = |v: &[Value]| -> (Vec<Value>, Vec<Value>) {
        let (mut rest, mut snaps) = (Vec::new(), Vec::new());
        for f in v {
            if SETLIKE.contains(&f["type"].as_str().unwrap_or("")) {
                if !snaps.contains(f) {
                    snaps.push(f.clone());
                }
            } else {
                rest.push(f.clone());
            }
        }
        (rest, snaps)
    };
    let ((er, es), (ar, asn)) = (split(expected), split(actual));
    for i in 0..er.len().max(ar.len()) {
        match (er.get(i), ar.get(i)) {
            (Some(a), Some(b)) if a == b => {}
            (a, b) => {
                let s = |v: Option<&Value>| v.map_or("<missing>".to_string(), |v| v.to_string());
                return Some(format!("frame #{i} (not counting set-compared frames):\n  expected {}\n  actual   {}", s(a), s(b)));
            }
        }
    }
    for e in &es {
        if !asn.contains(e) {
            return Some(format!("frame expected but never seen: {e}"));
        }
    }
    for a in &asn {
        if !es.contains(a) {
            return Some(format!("frame seen but not in the fixture: {a}"));
        }
    }
    None
}
