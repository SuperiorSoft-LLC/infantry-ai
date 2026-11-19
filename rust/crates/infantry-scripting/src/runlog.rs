// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Script run history and stats: the newest N run records per project, per-session wake counters and
//! turns saved, the `infantry_script_stats` answer, and the chat contributions (timeline items).
use crate::dispatcher::RunReport;
use serde_json::{json, Map, Value};
use std::collections::{BTreeMap, HashMap, VecDeque};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

pub const STATS: &str = "infantry_script_stats";
pub const STATS_SOURCE: &str = "automation-stats";
/// The wake reason the daemon reports for a mail a hook handled (no wake = a turn saved).
pub const HANDLED: &str = "mail-handled";

#[derive(Debug, Clone, PartialEq)]
pub struct RunAction {
    pub tool: String,
    pub ok: Option<bool>,
    pub text: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct RunRecord {
    pub id: u64,
    pub at: u64,
    pub project: String,
    pub script: String,
    /// `<hook id> <on>` for a hook, `manual` for a script_run.
    pub trigger: String,
    pub session: Option<String>,
    /// handled | delivered | passed | ok | failed | verdict-lost
    pub outcome: String,
    pub ok: bool,
    pub actions: Vec<RunAction>,
    pub log: Vec<String>,
    pub summary: String,
    pub error: Option<String>,
    pub r#ref: Option<String>,
    pub duration_ms: Option<u64>,
}

fn cut(s: &str, n: usize) -> String {
    s.chars().take(n).collect()
}

impl RunRecord {
    pub fn line(&self) -> String {
        format!("AUTOMATION · {} · {}{}", self.script, self.summary, if self.ok { "" } else { " · FAILED" })
    }

    /// The chat contribution of this run (a timeline_item payload), or `None` when it is not tied to a session.
    pub fn timeline_json(&self) -> Option<Value> {
        let sid = self.session.as_ref()?;
        let mut details = vec![Value::String(format!("trigger: {}", self.trigger))];
        for a in &self.actions {
            let st = match a.ok {
                Some(true) => "ok",
                Some(false) => "FAILED",
                None => "planned",
            };
            details.push(Value::String(format!("{} {st}{}", a.tool, a.text.as_ref().map(|t| format!(": {}", cut(t, 160))).unwrap_or_default())));
        }
        details.extend(self.log.iter().map(|l| Value::String(format!("log: {}", cut(l, 200)))));
        if let Some(e) = &self.error {
            details.push(Value::String(format!("error: {}", cut(e, 200))));
        }
        let mut o = json!({
            "id": format!("run-{}-{}", self.project, self.id), "sessionId": sid, "at": self.at, "source": "automation",
            "label": format!("AUTOMATION · {}", self.script), "text": self.line(), "ok": self.ok,
        });
        if let Some(d) = self.duration_ms {
            o["meta"] = json!({"durationMs": d.to_string()});
        }
        o["details"] = Value::Array(details);
        Some(o)
    }

    /// The tool form (text shortened).
    pub fn to_json(&self) -> Value {
        let mut o = Map::new();
        o.insert("id".into(), json!(self.id));
        o.insert("at".into(), json!(self.at));
        o.insert("project".into(), json!(self.project));
        o.insert("script".into(), json!(self.script));
        o.insert("trigger".into(), json!(self.trigger));
        if let Some(s) = &self.session {
            o.insert("session".into(), json!(s));
        }
        if let Some(d) = self.duration_ms {
            o.insert("durationMs".into(), json!(d));
        }
        o.insert("outcome".into(), json!(self.outcome));
        o.insert("ok".into(), json!(self.ok));
        o.insert("line".into(), json!(self.line()));
        o.insert("summary".into(), json!(self.summary));
        if let Some(e) = &self.error {
            o.insert("error".into(), json!(e));
        }
        if let Some(r) = &self.r#ref {
            o.insert("ref".into(), json!(r));
        }
        o.insert("actions".into(), self.actions_json());
        o.insert("log".into(), Value::Array(self.log.iter().map(|l| json!(cut(l, 300))).collect()));
        Value::Object(o)
    }

    fn actions_json(&self) -> Value {
        Value::Array(
            self.actions
                .iter()
                .map(|a| {
                    let mut m = Map::new();
                    m.insert("tool".into(), json!(a.tool));
                    if let Some(ok) = a.ok {
                        m.insert("ok".into(), json!(ok));
                    }
                    if let Some(t) = &a.text {
                        m.insert("text".into(), json!(cut(t, 300)));
                    }
                    Value::Object(m)
                })
                .collect(),
        )
    }

    /// The stored form (one JSON row).
    pub fn encode(&self) -> String {
        let mut v = self.to_json();
        if let Some(o) = v.as_object_mut() {
            o.remove("line");
        }
        v.to_string()
    }

    pub fn decode(text: &str) -> Option<RunRecord> {
        let o: Value = serde_json::from_str(text).ok()?;
        let s = |k: &str| o.get(k).and_then(|v| v.as_str().map(str::to_string).or_else(|| (!v.is_null()).then(|| v.to_string())));
        let n = |k: &str| o.get(k).and_then(|v| v.as_u64().or_else(|| v.as_str()?.parse().ok()));
        Some(RunRecord {
            id: n("id")?,
            at: n("at")?,
            project: s("project")?,
            script: s("script")?,
            trigger: s("trigger").unwrap_or_default(),
            session: s("session"),
            outcome: s("outcome").unwrap_or_default(),
            ok: o.get("ok").and_then(Value::as_bool).unwrap_or(false),
            actions: o
                .get("actions")
                .and_then(Value::as_array)
                .map(|a| {
                    a.iter()
                        .map(|x| RunAction {
                            tool: x.get("tool").and_then(Value::as_str).unwrap_or_default().into(),
                            ok: x.get("ok").and_then(Value::as_bool),
                            text: x.get("text").and_then(Value::as_str).map(str::to_string),
                        })
                        .collect()
                })
                .unwrap_or_default(),
            log: o.get("log").and_then(Value::as_array).map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect()).unwrap_or_default(),
            summary: s("summary").unwrap_or_default(),
            error: s("error"),
            r#ref: s("ref"),
            duration_ms: n("durationMs"),
        })
    }
}

/// Where run records survive a restart (the daemon's db). Tests use none.
pub trait RunStore: Send + Sync {
    fn save(&self, rec: &RunRecord);
    /// Drops all but the newest `keep` records of `project`.
    fn trim(&self, project: &str, keep: usize);
    fn load(&self) -> Vec<RunRecord>;
}

#[derive(Debug, Clone, PartialEq)]
struct WakeNote {
    at: u64,
    reason: String,
    r#ref: Option<String>,
}

type Listener = Arc<dyn Fn(&Value) + Send + Sync>;
type RecordListener = Arc<dyn Fn(&RunRecord) + Send + Sync>;

#[derive(Default)]
struct Inner {
    runs: HashMap<String, VecDeque<RunRecord>>,
    wakes: HashMap<String, HashMap<String, u64>>,
    session_project: HashMap<String, String>,
    last_wake: HashMap<String, WakeNote>,
    notes: VecDeque<(u64, Value)>,
}

/// The run log. Cheap to share (`Arc`).
pub struct ScriptRunLog {
    limit: Arc<dyn Fn() -> usize + Send + Sync>,
    clock: Arc<dyn Fn() -> u64 + Send + Sync>,
    store: Option<Arc<dyn RunStore>>,
    seq: AtomicU64,
    inner: Mutex<Inner>,
    on_record: Mutex<Option<RecordListener>>,
    on_stats: Mutex<Option<Listener>>,
}

pub fn now_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

impl Default for ScriptRunLog {
    fn default() -> Self {
        ScriptRunLog::new(Arc::new(|| 200), Arc::new(now_ms), None)
    }
}

impl ScriptRunLog {
    pub fn new(limit: Arc<dyn Fn() -> usize + Send + Sync>, clock: Arc<dyn Fn() -> u64 + Send + Sync>, store: Option<Arc<dyn RunStore>>) -> Self {
        let log = ScriptRunLog { limit, clock, store, seq: AtomicU64::new(0), inner: Mutex::new(Inner::default()), on_record: Mutex::new(None), on_stats: Mutex::new(None) };
        if let Some(s) = &log.store {
            let max = (log.limit)();
            let mut inner = log.inner.lock().unwrap();
            for r in s.load() {
                if let Some(sess) = &r.session {
                    inner.session_project.insert(sess.clone(), r.project.clone());
                }
                log.seq.fetch_max(r.id, Ordering::Relaxed);
                let q = inner.runs.entry(r.project.clone()).or_default();
                q.push_back(r);
                while q.len() > max {
                    q.pop_front();
                }
            }
        }
        log
    }

    /// Told every new or amended record (the daemon pushes it to the session's chat).
    pub fn set_on_record(&self, f: RecordListener) {
        *self.on_record.lock().unwrap() = Some(f);
    }

    /// Told a session's stats item after each wake / handled mail, and the mail summary lines.
    pub fn set_on_stats(&self, f: Listener) {
        *self.on_stats.lock().unwrap() = Some(f);
    }

    fn notify_record(&self, r: &RunRecord) {
        let l = self.on_record.lock().unwrap().clone();
        if let Some(l) = l {
            l(r);
        }
    }

    fn notify_stats(&self, v: &Value) {
        let l = self.on_stats.lock().unwrap().clone();
        if let Some(l) = l {
            l(v);
        }
    }

    #[allow(clippy::too_many_arguments)]
    pub fn record(
        &self,
        project: &str,
        script: &str,
        trigger: &str,
        session: Option<&str>,
        report: Option<&RunReport>,
        outcome: &str,
        summary: &str,
        error: Option<&str>,
        r#ref: Option<&str>,
        quiet: bool,
    ) -> RunRecord {
        let actions = report.map(|r| r.records.iter().map(|a| RunAction { tool: a.tool.clone(), ok: a.ok, text: a.text.clone() }).collect()).unwrap_or_default();
        let rec = RunRecord {
            id: self.seq.fetch_add(1, Ordering::Relaxed) + 1,
            at: (self.clock)(),
            project: project.into(),
            script: script.into(),
            trigger: trigger.into(),
            session: session.map(str::to_string),
            outcome: outcome.into(),
            ok: error.is_none() && report.is_none_or(|r| r.ok()),
            actions,
            log: report.map(|r| r.logs.clone()).unwrap_or_default(),
            summary: summary.into(),
            error: error.map(str::to_string).or_else(|| report.and_then(|r| r.error.clone())),
            r#ref: r#ref.map(str::to_string),
            duration_ms: report.and_then(|r| r.duration_ms),
        };
        let max = (self.limit)();
        {
            let mut inner = self.inner.lock().unwrap();
            if let Some(s) = session {
                inner.session_project.insert(s.into(), project.into());
            }
            let q = inner.runs.entry(project.into()).or_default();
            q.push_back(rec.clone());
            while q.len() > max {
                q.pop_front();
            }
        }
        if let Some(s) = &self.store {
            s.save(&rec);
            s.trim(project, max);
        }
        if !quiet {
            self.notify_record(&rec);
        }
        rec
    }

    /// The records of the mail hooks that ran for mail `ref`, oldest first.
    pub fn by_ref(&self, r#ref: &str) -> Vec<RunRecord> {
        let inner = self.inner.lock().unwrap();
        let mut v: Vec<RunRecord> = inner.runs.values().flat_map(|q| q.iter().filter(|r| r.r#ref.as_deref() == Some(r#ref)).cloned()).collect();
        v.sort_by_key(|r| r.id);
        v
    }

    /// ONE chat line per mail with its final outcome. `verdict`: handled | rewritten | delivered.
    pub fn mail_summary(&self, r#ref: &str, subject: &str, session: &str, verdict: &str) {
        let recs = self.by_ref(r#ref);
        let Some(last) = recs.last() else { return };
        let failed: Vec<&RunRecord> = recs.iter().filter(|r| !r.ok).collect();
        let text = match verdict {
            "handled" => format!("mail {subject} to {session}: handled by {} (not delivered, no turn)", last.script),
            "rewritten" => format!("mail {subject} to {session}: delivered, rewritten by {}", last.script),
            _ if !failed.is_empty() => format!(
                "mail {subject} to {session}: hook {} FAILED ({}) -> delivered, recipient woken",
                failed.iter().map(|r| r.script.as_str()).collect::<Vec<_>>().join(", "),
                failed[0].error.as_deref().map(|e| cut(e, 120)).unwrap_or_else(|| "error".into())
            ),
            _ => format!("mail {subject} to {session}: delivered (no hook handled it)"),
        };
        let ok = failed.is_empty();
        let details: Vec<Value> = recs
            .iter()
            .map(|r| json!(format!("{}: {} - {}{}", r.script, r.outcome, r.summary, r.error.as_deref().map(|e| format!(" ({})", cut(e, 160))).unwrap_or_default())))
            .collect();
        let item = json!({
            "id": format!("mail-{}-{}", r#ref, recs[0].project), "sessionId": session, "at": (self.clock)(), "source": "automation",
            "label": "AUTOMATION · mail", "text": format!("AUTOMATION · {text}{}", if ok { "" } else { " · FAILED" }), "ok": ok, "details": details,
        });
        self.notify_stats(&item);
    }

    /// The host dropped the verdict of the mail hook run for mail `ref`: the record is amended (outcome verdict-lost, failed).
    pub fn verdict_lost(&self, r#ref: &str, reason: &str) {
        let amended = {
            let mut inner = self.inner.lock().unwrap();
            let mut found = None;
            for q in inner.runs.values_mut() {
                if let Some(r) = q.iter_mut().rev().find(|r| r.r#ref.as_deref() == Some(r#ref)) {
                    r.summary = format!("verdict lost ({}); the mail was delivered anyway", r.outcome);
                    r.outcome = "verdict-lost".into();
                    r.ok = false;
                    r.error = Some(format!("host dropped the verdict: {reason}"));
                    found = Some(r.clone());
                    break;
                }
            }
            found
        };
        if let Some(r) = amended {
            if let Some(s) = &self.store {
                s.save(&r);
            }
            self.notify_record(&r);
        }
    }

    /// A chat note kept so a reload of the chat shows it again; memory only, newest 200.
    pub fn add_note(&self, item: Value) {
        let at = item.get("at").and_then(|v| v.as_u64().or_else(|| v.as_str()?.parse().ok())).unwrap_or_else(|| (self.clock)());
        let mut inner = self.inner.lock().unwrap();
        inner.notes.push_back((at, item));
        while inner.notes.len() > 200 {
            inner.notes.pop_front();
        }
    }

    /// Keeps a chat note for reloads and pushes it to every client as a `timeline_item`.
    pub fn post_note(&self, item: Value) {
        self.add_note(item.clone());
        self.notify_stats(&item);
    }

    /// `project` ties the session to a project (the first sight wins).
    pub fn wake(&self, session: &str, reason: &str, r#ref: Option<&str>, project: Option<&str>) {
        {
            let mut inner = self.inner.lock().unwrap();
            if let Some(p) = project {
                inner.session_project.entry(session.into()).or_insert_with(|| p.into());
            }
            *inner.wakes.entry(session.into()).or_default().entry(reason.into()).or_insert(0) += 1;
            let at = (self.clock)();
            inner.last_wake.insert(session.into(), WakeNote { at, reason: reason.into(), r#ref: r#ref.map(str::to_string) });
        }
        if let Some(item) = self.stats_item(session) {
            self.notify_stats(&item);
        }
    }

    /// Newest first, at most `n` (1..1000), optionally one session's.
    pub fn runs(&self, project: &str, session: Option<&str>, n: usize) -> Vec<RunRecord> {
        let inner = self.inner.lock().unwrap();
        let Some(q) = inner.runs.get(project) else { return Vec::new() };
        let all: Vec<RunRecord> = q.iter().filter(|r| session.is_none() || r.session.as_deref() == session).cloned().collect();
        let n = n.clamp(1, 1000);
        all.into_iter().rev().take(n).collect()
    }

    /// How many scripts failed their last `streak` runs in a row, over all projects.
    pub fn failing_scripts(&self, streak: usize) -> usize {
        let inner = self.inner.lock().unwrap();
        inner
            .runs
            .values()
            .map(|q| {
                let mut by: HashMap<&str, Vec<&RunRecord>> = HashMap::new();
                for r in q {
                    by.entry(r.script.as_str()).or_default().push(r);
                }
                by.values().filter(|rs| rs.len() >= streak && rs[rs.len() - streak..].iter().all(|r| !r.ok)).count()
            })
            .sum()
    }

    fn stats_of(by: &HashMap<String, u64>, last: Option<&WakeNote>) -> Value {
        let reasons: BTreeMap<&String, &u64> = by.iter().filter(|(k, _)| k.as_str() != HANDLED).collect();
        let mut o = json!({
            "turnsSaved": by.get(HANDLED).copied().unwrap_or(0),
            "wakes": reasons.values().copied().sum::<u64>(),
            "wakesByReason": reasons,
        });
        if let Some(w) = last {
            let mut lw = json!({"at": w.at, "reason": w.reason});
            if let Some(r) = &w.r#ref {
                lw["ref"] = json!(r);
            }
            o["lastWake"] = lw;
        }
        o
    }

    pub fn session_stats(&self, session: &str) -> Value {
        let inner = self.inner.lock().unwrap();
        Self::stats_of(&inner.wakes.get(session).cloned().unwrap_or_default(), inner.last_wake.get(session))
    }

    pub fn project_stats(&self, project: &str) -> Value {
        let inner = self.inner.lock().unwrap();
        let mut total: HashMap<String, u64> = HashMap::new();
        for (s, m) in &inner.wakes {
            if inner.session_project.get(s).map(String::as_str) == Some(project) {
                for (k, v) in m {
                    *total.entry(k.clone()).or_insert(0) += v;
                }
            }
        }
        Self::stats_of(&total, None)
    }

    /// The session's stats as a timeline item (source `automation-stats`), `None` before its first wake.
    pub fn stats_item(&self, session: &str) -> Option<Value> {
        let (by, last, proj) = {
            let inner = self.inner.lock().unwrap();
            (inner.wakes.get(session)?.clone(), inner.last_wake.get(session).cloned(), inner.session_project.get(session).cloned())
        };
        let mine = self.session_stats(session);
        let reasons: BTreeMap<&String, &u64> = by.iter().filter(|(k, _)| k.as_str() != HANDLED).collect();
        let mut meta = json!({
            "turnsSaved": mine["turnsSaved"].to_string(), "wakes": mine["wakes"].to_string(),
            "wakesByReason": reasons.iter().map(|(k, v)| format!("{k}:{v}")).collect::<Vec<_>>().join(","),
        });
        if let Some(w) = &last {
            meta["lastWake"] = json!(w.reason);
        }
        if let Some(p) = proj {
            let ps = self.project_stats(&p);
            meta["projectTurnsSaved"] = json!(ps["turnsSaved"].to_string());
            meta["projectWakes"] = json!(ps["wakes"].to_string());
        }
        Some(json!({
            "id": format!("stats-{session}"), "sessionId": session, "at": last.map(|w| w.at).unwrap_or_else(|| (self.clock)()),
            "source": STATS_SOURCE, "label": "AUTOMATION stats",
            "text": format!("turns saved {} · wakes {}", mine["turnsSaved"], mine["wakes"]), "meta": meta,
        }))
    }

    /// The newest chat contributions over all sessions, oldest first, at most `n`; the stats items come last.
    pub fn timeline_items(&self, n: usize) -> Vec<Value> {
        let (mut items, sessions) = {
            let inner = self.inner.lock().unwrap();
            let mut items: Vec<(u64, Value)> = inner.runs.values().flat_map(|q| q.iter().filter_map(|r| r.timeline_json().map(|j| (r.at, j)))).collect();
            items.extend(inner.notes.iter().cloned());
            let mut sessions: Vec<String> = inner.wakes.keys().cloned().collect();
            sessions.sort();
            (items, sessions)
        };
        items.sort_by_key(|(at, _)| *at);
        let skip = items.len().saturating_sub(n);
        let mut out: Vec<Value> = items.into_iter().skip(skip).map(|(_, v)| v).collect();
        out.extend(sessions.iter().filter_map(|s| self.stats_item(s)));
        out
    }

    /// The `infantry_script_stats` answer: `{project, stats, runs}`.
    pub fn stats_answer(&self, project: &str, session: Option<&str>, limit: usize) -> Value {
        json!({
            "project": project,
            "stats": match session { Some(s) => self.session_stats(s), None => self.project_stats(project) },
            "runs": self.runs(project, session, limit).iter().map(RunRecord::to_json).collect::<Vec<_>>(),
        })
    }
}
