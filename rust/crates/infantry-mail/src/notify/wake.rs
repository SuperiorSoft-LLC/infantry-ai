// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Which notices WAKE a session (inject a prompt, i.e. cost a model turn) and which only land in the inbox
//! (the attention list) to be read on the next real turn. Every notice source names a kind (a dotted string such as
//! `comment.done`, `build.failed`) and a dedupe key (the comment id, the build run id); the setting `notify.wake.kinds`
//! (comma or newline separated; a trailing `*` matches a prefix) says which kinds wake. The same (kind, key) wakes
//! a session at most once. Core names no kind: the sources pick their own strings.
use std::collections::{BTreeMap, HashMap, HashSet, VecDeque};
use std::sync::Mutex;

/// The setting that holds the wake list (scopes app, project, role).
pub const WAKE_KINDS_SETTING: &str = "notify.wake.kinds";

/// The cheapest default: a turn only when something needs action.
pub const DEFAULT_WAKE_KINDS: &str = "comment.done, comment.issue, worker.failed, worker.idle, build.failed, owner.message, session.needs_input";

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

    /// The setting text after turning [kind] on or off. Wildcards and `all` are expanded over [known] first, so that
    /// switching one kind off leaves the others as they were; a kind nobody declared keeps its own entry.
    pub fn with_kind(raw: Option<&str>, kind: &str, wake: bool, known: &[String]) -> String {
        let p = WakePolicy::parse(raw);
        let kind = kind.to_ascii_lowercase();
        let mut out: Vec<String> = known.iter().filter(|k| **k != kind && p.wakes(k)).cloned().collect();
        for k in &p.kinds {
            let literal = !k.ends_with('*') && k != "all" && k != "none";
            if literal && *k != kind && !out.contains(k) {
                out.push(k.clone());
            }
        }
        if wake {
            out.push(kind);
        }
        out.sort();
        if out.is_empty() { "none".into() } else { out.join(", ") }
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

/// What a sender says about a notice kind it raises (the Notifications settings card lists these).
#[derive(Debug, Clone, PartialEq, serde::Serialize)]
pub struct KindInfo {
    pub kind: String,
    pub description: String,
    /// Who raises it (a plain label such as "board" or "builds"); empty for a kind seen only at run time.
    pub source: String,
}

/// The kinds senders declare. Core names none: each sender calls [`KindRegistry::declare`] for the strings it raises.
#[derive(Default)]
pub struct KindRegistry {
    kinds: Mutex<BTreeMap<String, KindInfo>>,
}

impl KindRegistry {
    pub fn declare(&self, source: &str, kinds: &[(&str, &str)]) {
        let mut g = self.kinds.lock().unwrap();
        for (kind, description) in kinds {
            g.insert(kind.to_ascii_lowercase(), KindInfo { kind: kind.to_ascii_lowercase(), description: description.to_string(), source: source.to_string() });
        }
    }

    /// Declared kinds, then any kind in `seen` nobody declared (shown with no description), sorted by kind.
    pub fn list(&self, seen: &[String]) -> Vec<KindInfo> {
        let mut all = self.kinds.lock().unwrap().clone();
        for k in seen {
            all.entry(k.clone()).or_insert_with(|| KindInfo { kind: k.clone(), description: String::new(), source: String::new() });
        }
        all.into_values().collect()
    }
}

/// How many decisions one kind got: `checks` = decisions asked, `wakes` = turns spent, `inbox` = held, `duplicates` = dropped.
#[derive(Debug, Clone, Copy, Default, PartialEq, serde::Serialize)]
pub struct KindStats {
    pub checks: u64,
    pub wakes: u64,
    pub inbox: u64,
    pub duplicates: u64,
}

/// Remembers which (session, kind, key) already woke, so one event wakes a session once.
pub struct WakeGate {
    inner: Mutex<(HashSet<String>, VecDeque<String>)>,
    stats: Mutex<HashMap<String, KindStats>>,
    cap: usize,
}

impl WakeGate {
    pub fn new(cap: usize) -> WakeGate {
        WakeGate { inner: Mutex::new((HashSet::new(), VecDeque::new())), stats: Mutex::new(HashMap::new()), cap: cap.max(16) }
    }

    /// Per-kind counters since the daemon started (checks run vs turns spent).
    pub fn stats(&self) -> HashMap<String, KindStats> {
        self.stats.lock().unwrap().clone()
    }

    /// The decision for one notice. An empty `key` is never deduped.
    pub fn decide(&self, policy: &WakePolicy, session: &str, kind: &str, key: &str) -> Wake {
        let w = self.decide_inner(policy, session, kind, key);
        let mut st = self.stats.lock().unwrap();
        let e = st.entry(kind.to_ascii_lowercase()).or_default();
        e.checks += 1;
        match w {
            Wake::Now => e.wakes += 1,
            Wake::Inbox => e.inbox += 1,
            Wake::Duplicate => e.duplicates += 1,
        }
        w
    }

    fn decide_inner(&self, policy: &WakePolicy, session: &str, kind: &str, key: &str) -> Wake {
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
    fn with_kind_round_trips_through_parse() {
        let known: Vec<String> = ["a.x", "b.y", "c.z"].map(String::from).to_vec();
        let on = WakePolicy::with_kind(Some("a.*, c.z"), "b.y", true, &known);
        let p = WakePolicy::parse(Some(&on));
        assert!(p.wakes("a.x") && p.wakes("b.y") && p.wakes("c.z"));
        let off = WakePolicy::with_kind(Some("all"), "b.y", false, &known);
        let p = WakePolicy::parse(Some(&off));
        assert!(p.wakes("a.x") && !p.wakes("b.y") && p.wakes("c.z"));
        assert_eq!(WakePolicy::with_kind(Some("a.x"), "a.x", false, &known), "none");
        assert!(WakePolicy::parse(Some("none")).wakes("a.x") == false);
        // a kind nobody declared keeps its own entry when another one is toggled
        assert!(WakePolicy::parse(Some(&WakePolicy::with_kind(Some("zz.custom"), "a.x", true, &known))).wakes("zz.custom"));
    }

    #[test]
    fn stats_count_checks_against_turns() {
        let p = WakePolicy::parse(None);
        let g = WakeGate::new(100);
        g.decide(&p, "pm", "build.success", "r1");
        g.decide(&p, "pm", "build.success", "r2");
        g.decide(&p, "pm", "build.failed", "r3");
        g.decide(&p, "pm", "build.failed", "r3");
        let st = g.stats();
        assert_eq!(st["build.success"], KindStats { checks: 2, wakes: 0, inbox: 2, duplicates: 0 });
        assert_eq!(st["build.failed"], KindStats { checks: 2, wakes: 1, inbox: 0, duplicates: 1 });
    }

    #[test]
    fn registry_lists_declared_then_unknown_kinds() {
        let r = KindRegistry::default();
        r.declare("builds", &[("Build.Failed", "A build failed")]);
        let l = r.list(&["zzz.custom".into(), "build.failed".into()]);
        assert_eq!(l.iter().map(|k| k.kind.as_str()).collect::<Vec<_>>(), ["build.failed", "zzz.custom"]);
        assert_eq!(l[0].description, "A build failed");
        assert_eq!(l[1].description, "");
    }

    #[test]
    fn setting_overrides_and_prefix_matches() {
        let p = WakePolicy::parse(Some("comment.*\nbuild.failed"));
        assert!(p.wakes("comment.note") && p.wakes("build.failed") && !p.wakes("build.success"));
        assert!(!WakePolicy::parse(Some("none")).wakes("comment.done"));
        assert!(WakePolicy::parse(Some("all")).wakes("anything"));
    }
}
