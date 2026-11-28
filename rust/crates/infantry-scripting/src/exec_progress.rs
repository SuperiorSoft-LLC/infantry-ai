// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! PARITY-AUDIT-1 #10: `infantry_exec`'s `progress` argument. Turns a job's output lines into `infantry_progress`
//! reports for the calling session's card.
//!
//! `progress: true` or `{label?, steps?, markers? (default true), percentRegex?, stepRegex?, minGapMs?}`:
//! - `##infantry` marker lines (the `infantry run` grammar): `progress <0-100> [label]`, `plan a|b|c`, `step [name]`,
//!   `status <label>`, `done [label]`, `fail [reason]`;
//! - `percentRegex`: group 1 is the percent; `stepRegex`: groups 1 = n, 2 = N, 3 = name ("step 2/5: build").
//!
//! Plain updates are rate limited (at most one per `minGapMs`, the latest one waits); plan, step, done and fail never wait.
//! The job's end sends done (exit 0) or failed unless a marker already ended the line.

use regex::Regex;
use serde_json::{json, Map, Value};

pub const MARKER: &str = "##infantry";
/// Default gap between two rate-limited reports.
pub const DEFAULT_MIN_GAP_MS: u64 = 1000;

pub struct JobProgress {
    label: Option<String>,
    markers: bool,
    percent: Option<Regex>,
    step: Option<Regex>,
    min_gap_ms: u64,
    last_sent: Option<u64>,
    pending: Option<Value>,
    ended: bool,
}

impl JobProgress {
    /// The tracker for an `infantry_exec` `progress` value, plus the reports to send at once (the opening line).
    /// `None` when the value asks for none (absent, false). Refuses a bad regex.
    pub fn from_arg(v: Option<&Value>) -> Result<Option<(JobProgress, Vec<Value>)>, String> {
        let o: Map<String, Value> = match v {
            None | Some(Value::Null) | Some(Value::Bool(false)) => return Ok(None),
            Some(Value::Bool(true)) => Map::new(),
            Some(Value::Object(o)) => o.clone(),
            Some(_) => return Err("progress must be true or an object {label, steps, markers, percentRegex, stepRegex}".into()),
        };
        let re = |k: &str| -> Result<Option<Regex>, String> {
            match o.get(k).and_then(Value::as_str).filter(|s| !s.trim().is_empty()) {
                None => Ok(None),
                Some(r) => Regex::new(r).map(Some).map_err(|e| format!("progress.{k} is not a regex: {e}")),
            }
        };
        let label = o.get("label").and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty()).map(str::to_string);
        let steps: Vec<Value> = o.get("steps").and_then(Value::as_array).cloned().unwrap_or_default();
        let p = JobProgress {
            label: label.clone(),
            markers: o.get("markers").and_then(Value::as_bool).unwrap_or(true),
            percent: re("percentRegex")?,
            step: re("stepRegex")?,
            min_gap_ms: o.get("minGapMs").and_then(Value::as_u64).unwrap_or(DEFAULT_MIN_GAP_MS),
            last_sent: None,
            pending: None,
            ended: false,
        };
        let open = if steps.is_empty() { p.with_label(json!({"label": label.clone().unwrap_or_else(|| "running".into())})) } else { p.with_label(json!({"steps": steps})) };
        Ok(Some((p, vec![open])))
    }

    fn with_label(&self, mut r: Value) -> Value {
        if let (Some(l), Some(o)) = (&self.label, r.as_object_mut()) {
            o.entry("label").or_insert(json!(l));
        }
        r["origin"] = json!("exec");
        r
    }

    fn hard(&mut self, r: Value, now: u64) -> Vec<Value> {
        self.pending = None;
        self.last_sent = Some(now);
        vec![self.with_label(r)]
    }

    fn soft(&mut self, r: Value, now: u64) -> Vec<Value> {
        if self.last_sent.map(|t| now.saturating_sub(t) >= self.min_gap_ms).unwrap_or(true) {
            return self.hard(r, now);
        }
        self.pending = Some(r);
        Vec::new()
    }

    /// The reports one output line yields (`now` in ms).
    pub fn line(&mut self, raw: &str, now: u64) -> Vec<Value> {
        if self.ended {
            return Vec::new();
        }
        let t = raw.trim();
        let mut out = self.flush(now);
        if self.markers {
            if let Some(rest) = t.strip_prefix(MARKER) {
                out.extend(self.marker(rest.trim(), now));
                return out;
            }
        }
        if let Some(c) = self.step.as_ref().and_then(|r| r.captures(t)) {
            let n = c.get(1).and_then(|m| m.as_str().parse::<f64>().ok());
            let total = c.get(2).and_then(|m| m.as_str().parse::<f64>().ok()).filter(|x| *x > 0.0);
            if let (Some(n), Some(total)) = (n, total) {
                let mut r = json!({"percent": (n / total * 100.0).clamp(0.0, 100.0)});
                if let Some(name) = c.get(3).map(|m| m.as_str().trim()).filter(|s| !s.is_empty()) {
                    r["label"] = json!(name);
                }
                out.extend(self.soft(r, now));
                return out;
            }
        }
        if let Some(p) = self.percent.as_ref().and_then(|r| r.captures(t)).and_then(|c| c.get(1)).and_then(|m| m.as_str().parse::<f64>().ok()) {
            out.extend(self.soft(json!({"percent": p.clamp(0.0, 100.0)}), now));
        }
        out
    }

    fn marker(&mut self, m: &str, now: u64) -> Vec<Value> {
        let (word, rest) = m.split_once(char::is_whitespace).map(|(a, b)| (a, b.trim())).unwrap_or((m, ""));
        let text = |s: &str| (!s.is_empty()).then(|| s.to_string());
        match word {
            "progress" => {
                let (num, label) = rest.split_once(char::is_whitespace).map(|(a, b)| (a, b.trim())).unwrap_or((rest, ""));
                match num.trim_end_matches('%').parse::<f64>() {
                    Ok(p) => {
                        let mut r = json!({"percent": p.clamp(0.0, 100.0)});
                        if let Some(l) = text(label) {
                            r["label"] = json!(l);
                        }
                        self.soft(r, now)
                    }
                    Err(_) => Vec::new(),
                }
            }
            "plan" => {
                let steps: Vec<&str> = rest.split('|').map(str::trim).filter(|s| !s.is_empty()).collect();
                if steps.is_empty() { Vec::new() } else { self.hard(json!({"steps": steps}), now) }
            }
            "step" => self.hard(match text(rest) { Some(s) => json!({"step": s}), None => json!({"step": ""}) }, now),
            "status" => text(rest).map(|l| self.soft(json!({"label": l}), now)).unwrap_or_default(),
            "done" => {
                self.ended = true;
                self.hard(match text(rest) { Some(l) => json!({"state": "done", "label": l}), None => json!({"state": "done"}) }, now)
            }
            "fail" => {
                self.ended = true;
                self.hard(match text(rest) { Some(l) => json!({"state": "failed", "label": l}), None => json!({"state": "failed"}) }, now)
            }
            _ => Vec::new(),
        }
    }

    /// The waiting report once its gap has passed.
    pub fn flush(&mut self, now: u64) -> Vec<Value> {
        match self.pending.take() {
            Some(r) if self.last_sent.map(|t| now.saturating_sub(t) >= self.min_gap_ms).unwrap_or(true) => self.hard(r, now),
            other => {
                self.pending = other;
                Vec::new()
            }
        }
    }

    /// The job ended: the waiting report, then done or failed (unless a marker ended it already).
    pub fn finish(&mut self, ok: bool) -> Vec<Value> {
        if self.ended {
            return Vec::new();
        }
        let mut out: Vec<Value> = self.pending.take().map(|r| self.with_label(r)).into_iter().collect();
        self.ended = true;
        out.push(self.with_label(json!({"state": if ok { "done" } else { "failed" }})));
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn absent_or_false_asks_for_none_and_a_bad_regex_is_refused() {
        assert!(JobProgress::from_arg(None).unwrap().is_none());
        assert!(JobProgress::from_arg(Some(&json!(false))).unwrap().is_none());
        assert!(JobProgress::from_arg(Some(&json!({"percentRegex": "("}))).is_err());
        assert!(JobProgress::from_arg(Some(&json!(7))).is_err());
    }

    #[test]
    fn markers_and_regexes_become_reports() {
        let (mut p, open) = JobProgress::from_arg(Some(&json!({"label": "build", "percentRegex": "(\\d+)%"}))).unwrap().unwrap();
        assert_eq!(open[0]["label"], "build");
        assert_eq!(p.line("##infantry plan a|b", 0)[0]["steps"], json!(["a", "b"]));
        assert_eq!(p.line("##infantry step a", 10)[0]["step"], "a");
        assert_eq!(p.line("at 30% now", 2000)[0]["percent"], 30.0);
        // Within the gap the latest one waits, then goes out with the next line past the gap.
        assert!(p.line("at 40%", 2100).is_empty());
        assert!(p.line("at 50%", 2200).is_empty());
        assert_eq!(p.line("noise", 3100)[0]["percent"], 50.0);
        let end = p.finish(true);
        assert_eq!(end.last().unwrap()["state"], "done");
    }

    #[test]
    fn a_fail_marker_ends_the_line_before_the_exit() {
        let (mut p, _) = JobProgress::from_arg(Some(&json!(true))).unwrap().unwrap();
        assert_eq!(p.line("##infantry fail tests red", 0)[0]["state"], "failed");
        assert!(p.finish(true).is_empty());
        assert!(p.line("##infantry progress 10", 20).is_empty());
    }

    #[test]
    fn step_regex_gives_percent_and_name() {
        let (mut p, _) = JobProgress::from_arg(Some(&json!({"stepRegex": "step (\\d+)/(\\d+): (.*)", "markers": false}))).unwrap().unwrap();
        let r = p.line("step 1/4: fetch", 0);
        assert_eq!((r[0]["percent"].as_f64(), r[0]["label"].as_str()), (Some(25.0), Some("fetch")));
        assert!(p.line("##infantry done", 5000).is_empty(), "markers off: a marker line is plain output");
    }
}
