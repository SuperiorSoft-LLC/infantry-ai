// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! PARITY-AUDIT-1 #10: a session's progress line (the `progress` field of its record, drawn on its card).
//!
//! One report (the `infantry_progress` tool's arguments, or a line an `infantry_exec` job turned into one) updates it:
//! `{percent?, label?, steps?, step?, done?, total?, state?: running|done|failed, clear?}`. The mode follows from what the
//! report carries (steps, percent, count, else status). A report keeps what it does not name (start time, plan).

use super::{now_ms, Daemon};
use infantry_protocol::session::SessionProgress;
use serde_json::{json, Value};
use std::sync::Arc;

/// The longest label or step name kept.
pub const PROGRESS_LABEL_CHARS: usize = 200;
/// The most plan steps kept.
pub const PROGRESS_STEPS_MAX: usize = 50;

fn text(v: &Value, k: &str) -> Option<String> {
    v.get(k).and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty()).map(|s| s.chars().take(PROGRESS_LABEL_CHARS).collect())
}

/// The progress after `report` lands on `prev`; `Ok(None)` clears it. Refuses a report that says nothing.
pub fn next_progress(prev: Option<&SessionProgress>, report: &Value, now: i64) -> Result<Option<SessionProgress>, String> {
    if report.get("clear").and_then(Value::as_bool) == Some(true) {
        return Ok(None);
    }
    let state = match text(report, "state").as_deref() {
        None | Some("running") => "running",
        Some("done") => "done",
        Some("failed") | Some("fail") => "failed",
        Some(o) => return Err(format!("state must be running, done or failed (got '{o}')")),
    };
    let percent = report.get("percent").and_then(Value::as_f64);
    if let Some(p) = percent {
        if !(0.0..=100.0).contains(&p) {
            return Err("percent must be 0..100".into());
        }
    }
    let steps: Option<Vec<String>> = report.get("steps").and_then(Value::as_array).map(|a| {
        a.iter().filter_map(Value::as_str).map(str::trim).filter(|s| !s.is_empty()).take(PROGRESS_STEPS_MAX).map(|s| s.chars().take(PROGRESS_LABEL_CHARS).collect()).collect()
    });
    // `step` with no name (or an empty one) completes the next step.
    let step_next = report.get("step").is_some();
    let step = text(report, "step");
    let done = report.get("done").and_then(Value::as_i64).filter(|n| *n >= 0);
    let total = report.get("total").and_then(Value::as_i64).filter(|n| *n > 0);
    let label = text(report, "label");
    if state == "running" && percent.is_none() && steps.is_none() && !step_next && done.is_none() && label.is_none() {
        return Err("a progress report needs percent, label, steps, step, done/total, state or clear".into());
    }
    // A new plan starts a fresh line; otherwise the line carries on.
    let mut p = match (prev, &steps) {
        (Some(prev), None) => prev.clone(),
        _ => serde_json::from_value::<SessionProgress>(json!({"mode": "status", "startedAt": now, "updatedAt": now})).map_err(|e| e.to_string())?,
    };
    if let Some(s) = steps {
        p.steps_total = Some(s.len() as i32);
        p.steps_done = Some(0);
        p.steps = s;
        p.mode = "steps".into();
    }
    if step_next {
        let by_name = step.as_ref().and_then(|s| p.steps.iter().position(|x| x == s)).map(|i| i as i32 + 1);
        let done_now = by_name.unwrap_or(p.steps_done.unwrap_or(0) + 1);
        p.steps_done = Some(done_now.max(p.steps_done.unwrap_or(0)));
        p.step = step.or_else(|| p.steps.get(done_now as usize - 1).cloned());
        p.mode = "steps".into();
    }
    if let Some(d) = done {
        p.done = Some(d);
        p.total = total.or(p.total);
        p.mode = "count".into();
    }
    if let Some(x) = percent {
        p.percent = Some(x);
        if p.mode == "status" {
            p.mode = "percent".into();
        }
    }
    if label.is_some() {
        if label != p.label {
            p.label_started_at = Some(now);
        }
        p.label = label;
    }
    if state == "done" && p.mode == "percent" {
        p.percent = Some(100.0);
    }
    p.state = state.into();
    p.updated_at = now;
    p.origin = text(report, "origin").or(p.origin);
    Ok(Some(p))
}

impl Daemon {
    /// Applies one progress report to session `sid` and pushes the record. Returns the line now in force.
    pub fn apply_progress(self: &Arc<Self>, sid: &str, report: &Value) -> Result<Option<SessionProgress>, String> {
        let entry = self.find(sid).ok_or_else(|| format!("unknown session '{sid}'"))?;
        let mut e = entry.lock().unwrap();
        let next = next_progress(e.rec.progress.as_ref(), report, now_ms())?;
        e.rec.progress = next.clone();
        let state = next.as_ref().map(|p| p.state.clone()).unwrap_or_else(|| "cleared".into());
        self.named_event("session.progress", json!({"sessionId": e.rec.id, "projectId": e.rec.project_id, "state": state}));
        self.push(&mut e);
        Ok(next)
    }
}

/// The one-line answer of `infantry_progress`.
pub fn progress_line(p: Option<&SessionProgress>) -> String {
    let Some(p) = p else { return "progress cleared".into() };
    let mut out = format!("progress {} {}", p.state, p.mode);
    if let Some(x) = p.percent {
        out.push_str(&format!(" {x:.0}%"));
    }
    if let (Some(d), Some(t)) = (p.steps_done, p.steps_total) {
        out.push_str(&format!(" step {d}/{t}"));
    }
    if let Some(d) = p.done {
        out.push_str(&format!(" {d}/{}", p.total.map(|t| t.to_string()).unwrap_or_else(|| "?".into())));
    }
    if let Some(l) = &p.label {
        out.push_str(&format!(": {l}"));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_report_builds_and_carries_the_line() {
        let p = next_progress(None, &json!({"percent": 40, "label": "compile"}), 10).unwrap().unwrap();
        assert_eq!((p.mode.as_str(), p.percent, p.label.as_deref(), p.started_at), ("percent", Some(40.0), Some("compile"), 10));
        let p2 = next_progress(Some(&p), &json!({"percent": 60}), 20).unwrap().unwrap();
        assert_eq!((p2.percent, p2.label.as_deref(), p2.started_at, p2.updated_at), (Some(60.0), Some("compile"), 10, 20));
        let d = next_progress(Some(&p2), &json!({"state": "done"}), 30).unwrap().unwrap();
        assert_eq!((d.state.as_str(), d.percent), ("done", Some(100.0)));
        assert!(next_progress(Some(&d), &json!({"clear": true}), 40).unwrap().is_none());
    }

    #[test]
    fn steps_advance_by_name_or_by_one() {
        let p = next_progress(None, &json!({"steps": ["a", "b", "c"]}), 1).unwrap().unwrap();
        assert_eq!((p.steps_total, p.steps_done), (Some(3), Some(0)));
        let p = next_progress(Some(&p), &json!({"step": "b"}), 2).unwrap().unwrap();
        assert_eq!((p.steps_done, p.step.as_deref()), (Some(2), Some("b")));
        let p = next_progress(Some(&p), &json!({"step": "extra"}), 3).unwrap().unwrap();
        assert_eq!(p.steps_done, Some(3));
    }

    #[test]
    fn empty_or_bad_reports_are_refused() {
        assert!(next_progress(None, &json!({}), 1).is_err());
        assert!(next_progress(None, &json!({"percent": 140}), 1).is_err());
        assert!(next_progress(None, &json!({"state": "maybe"}), 1).is_err());
    }
}
