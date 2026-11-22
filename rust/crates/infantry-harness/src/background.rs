// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Background tasks of an agent CLI (a `run_in_background` Bash, a Monitor, a background subagent) that keep running
//! after the turn ends. ONE tracker, fed by [`crate::driver::HarnessDriver`] with every harness's neutral events, so a
//! harness only has to report the facts: a tool started with a background input, its result naming the task, and the
//! CLI's task notice ([`HarnessEvent::TaskNotice`]) when the task ends.
//!
//! Which harnesses expose it: **claude** (both modes: the headless stream and the interactive transcript carry the tool
//! use, its result and the `<task-notification>` record). codex, opencode and pi report no background-task facts, so
//! their tracker stays empty (the child process tree is the only fallback, not wired here).

use crate::HarnessEvent;
use serde_json::Value;
use std::collections::{HashMap, HashSet};

/// One background task, as the owner sees it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BackgroundTask {
    pub id: String,
    /// The command or description the agent gave it.
    pub label: String,
    pub started_at: i64,
}

/// What a fed event changed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BackgroundChange {
    Started(BackgroundTask),
    /// `status` is the CLI's word (`completed`, `failed`, `killed`, `stopped`); `ran_ms` the time it ran.
    Finished { task: BackgroundTask, status: String, exit_code: Option<i32>, ran_ms: i64 },
}

#[derive(Debug, Default)]
pub struct BackgroundTracker {
    /// Tool call id -> label, for a background tool call whose result has not come yet.
    pending: HashMap<String, String>,
    running: Vec<BackgroundTask>,
    /// Task ids whose end was seen: an end that arrives before the start (out of order) must not leave a ghost.
    ended: HashSet<String>,
}

impl BackgroundTracker {
    pub fn running(&self) -> &[BackgroundTask] {
        &self.running
    }

    pub fn count(&self) -> usize {
        self.running.len()
    }

    /// Feeds one event; returns the changes it caused (usually none).
    pub fn observe(&mut self, ev: &HarnessEvent, now: i64) -> Vec<BackgroundChange> {
        match ev {
            HarnessEvent::ToolStarted { id, name, input } => {
                if let Some(label) = background_label(name, input) {
                    self.pending.insert(id.clone(), label);
                }
                Vec::new()
            }
            HarnessEvent::ToolFinished { id, output, ok, .. } => {
                let Some(label) = self.pending.remove(id) else { return Vec::new() };
                if !ok {
                    return Vec::new();
                }
                let task_id = task_id_in(output).unwrap_or_else(|| id.clone());
                if self.ended.remove(&task_id) || self.running.iter().any(|t| t.id == task_id) {
                    return Vec::new();
                }
                let task = BackgroundTask { id: task_id, label, started_at: now };
                self.running.push(task.clone());
                vec![BackgroundChange::Started(task)]
            }
            HarnessEvent::TaskNotice { ids, status, exit_code } => {
                let mut out = Vec::new();
                for id in ids.iter().filter(|i| !i.starts_with("__orphan")) {
                    match self.running.iter().position(|t| &t.id == id) {
                        Some(at) => {
                            let task = self.running.remove(at);
                            let ran_ms = (now - task.started_at).max(0);
                            out.push(BackgroundChange::Finished { task, status: status.clone(), exit_code: *exit_code, ran_ms });
                        }
                        None => {
                            self.ended.insert(id.clone());
                        }
                    }
                }
                out
            }
            _ => Vec::new(),
        }
    }
}

/// The label of a tool call that runs in the background, None for any other call.
fn background_label(name: &str, input: &str) -> Option<String> {
    let v: Value = serde_json::from_str(input).unwrap_or(Value::Null);
    let flag = v.get("run_in_background").and_then(Value::as_bool).unwrap_or(false);
    if !flag && name != "Monitor" {
        return None;
    }
    let pick = |k: &str| v.get(k).and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty());
    let label = pick("description").or_else(|| pick("command")).or_else(|| pick("prompt")).unwrap_or(name);
    Some(label.chars().take(120).collect())
}

/// The task id a background tool's result names ("... running in background with ID: b1abc. ...").
fn task_id_in(output: &str) -> Option<String> {
    let at = output.find("with ID:").map(|i| i + "with ID:".len()).or_else(|| output.find("task_id:").map(|i| i + "task_id:".len()))?;
    let id: String = output[at..].trim_start().chars().take_while(|c| c.is_ascii_alphanumeric() || *c == '_' || *c == '-').collect();
    Some(id).filter(|s| !s.is_empty())
}

/// "turn ended, 2 background tasks still running: a, b" (None when nothing runs).
pub fn turn_end_summary(tasks: &[BackgroundTask]) -> Option<String> {
    if tasks.is_empty() {
        return None;
    }
    let n = tasks.len();
    let names: Vec<&str> = tasks.iter().map(|t| t.label.as_str()).collect();
    Some(format!("turn ended, {n} background task{} still running: {}", if n == 1 { "" } else { "s" }, names.join("; ")))
}

pub fn started_line(task: &BackgroundTask) -> String {
    format!("started background: {}", task.label)
}

/// "background finished: <label>, exit 0, 4m12s".
pub fn finished_line(task: &BackgroundTask, status: &str, exit_code: Option<i32>, ran_ms: i64) -> String {
    let secs = ran_ms / 1000;
    let took = if secs >= 60 { format!("{}m{:02}s", secs / 60, secs % 60) } else { format!("{secs}s") };
    let exit = match exit_code {
        Some(c) => format!("exit {c}"),
        None => status.to_string(),
    };
    format!("background finished: {}, {exit}, {took}", task.label)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn start(id: &str, input: &str) -> HarnessEvent {
        HarnessEvent::ToolStarted { id: id.into(), name: "Bash".into(), input: input.into() }
    }
    fn result(id: &str, out: &str) -> HarnessEvent {
        HarnessEvent::ToolFinished { id: id.into(), output: out.into(), exit_code: None, ok: true }
    }
    fn notice(ids: &[&str], status: &str) -> HarnessEvent {
        HarnessEvent::TaskNotice { ids: ids.iter().map(|s| s.to_string()).collect(), status: status.into(), exit_code: Some(0) }
    }
    const SLEEP: &str = r#"{"command":"sleep 60","description":"nap","run_in_background":true}"#;

    #[test]
    fn a_background_call_counts_from_its_result_until_its_notice() {
        let mut t = BackgroundTracker::default();
        assert!(t.observe(&start("u1", SLEEP), 1000).is_empty());
        let c = t.observe(&result("u1", "Command running in background with ID: b1x. Output is being written to"), 2000);
        assert!(matches!(&c[0], BackgroundChange::Started(k) if k.id == "b1x" && k.label == "nap"));
        assert_eq!(t.count(), 1);
        let c = t.observe(&notice(&["b1x"], "completed"), 5000);
        assert!(matches!(&c[0], BackgroundChange::Finished { ran_ms: 3000, .. }));
        assert_eq!(t.count(), 0);
    }

    #[test]
    fn a_foreground_call_is_not_a_task() {
        let mut t = BackgroundTracker::default();
        t.observe(&start("u1", r#"{"command":"ls"}"#), 0);
        assert!(t.observe(&result("u1", "ok"), 1).is_empty());
        assert_eq!(t.count(), 0);
    }

    #[test]
    fn a_notice_before_the_start_leaves_no_ghost() {
        let mut t = BackgroundTracker::default();
        assert!(t.observe(&notice(&["b9"], "completed"), 10).is_empty());
        t.observe(&start("u2", SLEEP), 11);
        assert!(t.observe(&result("u2", "running in background with ID: b9"), 12).is_empty());
        assert_eq!(t.count(), 0);
    }

    #[test]
    fn two_tasks_end_in_any_order_and_a_resume_notice_stops_the_rest() {
        let mut t = BackgroundTracker::default();
        for (u, b) in [("u1", "b1"), ("u2", "b2"), ("u3", "b3")] {
            t.observe(&start(u, SLEEP), 0);
            t.observe(&result(u, &format!("with ID: {b}")), 0);
        }
        assert_eq!(t.count(), 3);
        t.observe(&notice(&["b2"], "completed"), 5);
        assert_eq!(t.count(), 2);
        let c = t.observe(&notice(&["b1", "b3", "__orphan_summary__:shell"], "stopped"), 9);
        assert_eq!(c.len(), 2);
        assert_eq!(t.count(), 0);
    }

    #[test]
    fn a_failed_start_and_a_repeated_notice_change_nothing() {
        let mut t = BackgroundTracker::default();
        t.observe(&start("u1", SLEEP), 0);
        assert!(t.observe(&HarnessEvent::ToolFinished { id: "u1".into(), output: "boom".into(), exit_code: None, ok: false }, 1).is_empty());
        t.observe(&start("u2", SLEEP), 0);
        t.observe(&result("u2", "with ID: b1"), 0);
        t.observe(&notice(&["b1"], "completed"), 1);
        assert!(t.observe(&notice(&["b1"], "completed"), 2).is_empty());
    }

    #[test]
    fn the_end_of_turn_summary_names_every_running_task() {
        assert_eq!(turn_end_summary(&[]), None);
        let a = BackgroundTask { id: "a".into(), label: "npm test".into(), started_at: 0 };
        let b = BackgroundTask { id: "b".into(), label: "watch".into(), started_at: 0 };
        assert_eq!(turn_end_summary(std::slice::from_ref(&a)).unwrap(), "turn ended, 1 background task still running: npm test");
        assert_eq!(turn_end_summary(&[a.clone(), b]).unwrap(), "turn ended, 2 background tasks still running: npm test; watch");
        assert_eq!(started_line(&a), "started background: npm test");
        assert_eq!(finished_line(&a, "completed", Some(0), 252_000), "background finished: npm test, exit 0, 4m12s");
    }
}
