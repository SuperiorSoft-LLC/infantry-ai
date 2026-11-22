// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Which notices WAKE a session (inject a prompt, i.e. cost a model turn) and which only land in the inbox
//! (the attention list) to be read on the next real turn. Every notice source names a kind (a dotted string such as
//! `comment.done`, `build.failed`) and a dedupe key (the comment id, the build run id); the setting `notify.wake.kinds`
//! (comma or newline separated; a trailing `*` matches a prefix) says which kinds wake. The same (kind, key) wakes
//! a session at most once. Core names no kind: the sources pick their own strings.
use std::collections::{HashSet, VecDeque};
use std::sync::Mutex;

/// The setting that holds the wake list (scopes app, project, role).
pub const WAKE_KINDS_SETTING: &str = "notify.wake.kinds";

/// The cheapest default: a turn only when something needs action.
pub const DEFAULT_WAKE_KINDS: &str = "comment.done, comment.issue, worker.failed, worker.idle, build.failed, owner.message";

/// What to do with a notice.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Wake {
    /// Inject a prompt into the session.
    Now,
    /// Keep it in the inbox; no turn.
    Inbox,
    /// This (kind, key) already wakened this session.
    Duplicate,
}

/// A parsed `notify.wake.kinds`.
#[derive(Debug, Clone, PartialEq)]
pub struct WakePolicy {
    kinds: Vec<String>,
}

impl WakePolicy {
    /// An unset or blank setting is the default list; the single word `none` wakes nothing, `all` wakes everything.
    pub fn parse(raw: Option<&str>) -> WakePolicy {
        let text = raw.map(str::trim).filter(|s| !s.is_empty()).unwrap_or(DEFAULT_WAKE_KINDS);
        let kinds = text.split([',', '\n', ' ']).map(|s| s.trim().to_ascii_lowercase()).filter(|s| !s.is_empty()).collect();
        WakePolicy { kinds }
    }

    pub fn wakes(&self, kind: &str) -> bool {
        let kind = kind.to_ascii_lowercase();
        self.kinds.iter().any(|k| match k.as_str() {
            "all" | "*" => true,
            "none" => false,
            _ => match k.strip_suffix('*') {
                Some(prefix) => kind.starts_with(prefix),
                None => *k == kind,
            },
        })
    }
}

/// Remembers which (session, kind, key) already woke, so one event wakes a session once.
pub struct WakeGate {
    inner: Mutex<(HashSet<String>, VecDeque<String>)>,
    cap: usize,
}

impl WakeGate {
    pub fn new(cap: usize) -> WakeGate {
        WakeGate { inner: Mutex::new((HashSet::new(), VecDeque::new())), cap: cap.max(16) }
    }

    /// The decision for one notice. An empty `key` is never deduped.
    pub fn decide(&self, policy: &WakePolicy, session: &str, kind: &str, key: &str) -> Wake {
        if !policy.wakes(kind) {
            return Wake::Inbox;
        }
        if key.is_empty() {
            return Wake::Now;
        }
        let id = format!("{session}|{kind}|{key}");
        let mut g = self.inner.lock().unwrap();
        if !g.0.insert(id.clone()) {
            return Wake::Duplicate;
        }
        g.1.push_back(id);
        while g.1.len() > self.cap {
            if let Some(old) = g.1.pop_front() {
                g.0.remove(&old);
            }
        }
        Wake::Now
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_wake_on_action_only() {
        let p = WakePolicy::parse(None);
        let g = WakeGate::new(100);
        assert_eq!(g.decide(&p, "pm", "comment.note", "c1"), Wake::Inbox);
        assert_eq!(g.decide(&p, "pm", "comment.issue", "c2"), Wake::Now);
        assert_eq!(g.decide(&p, "pm", "comment.done", "c3"), Wake::Now);
        assert_eq!(g.decide(&p, "pm", "build.success", "r1"), Wake::Inbox);
        assert_eq!(g.decide(&p, "pm", "build.failed", "r2"), Wake::Now);
        assert_eq!(g.decide(&p, "pm", "ticket.done.auto", "t1"), Wake::Inbox);
    }

    #[test]
    fn same_run_id_twice_wakes_once() {
        let p = WakePolicy::parse(None);
        let g = WakeGate::new(100);
        assert_eq!(g.decide(&p, "pm", "build.failed", "run9"), Wake::Now);
        assert_eq!(g.decide(&p, "pm", "build.failed", "run9"), Wake::Duplicate);
        assert_eq!(g.decide(&p, "other", "build.failed", "run9"), Wake::Now);
    }

    #[test]
    fn setting_overrides_and_prefix_matches() {
        let p = WakePolicy::parse(Some("comment.*\nbuild.failed"));
        assert!(p.wakes("comment.note") && p.wakes("build.failed") && !p.wakes("build.success"));
        assert!(!WakePolicy::parse(Some("none")).wakes("comment.done"));
        assert!(WakePolicy::parse(Some("all")).wakes("anything"));
    }
}
