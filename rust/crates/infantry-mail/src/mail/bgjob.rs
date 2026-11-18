// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The bg-job notifier: tells a session about its background jobs by mail on thread `bg:<id>`.
//!
//! END (the one-line finished note), VANISHED (the runner is gone: the exit read from `<log>.exit`), EOF (output
//! closed, still running), QUIET (no output for a while, one line per interval), HEARTBEAT (still running after T,
//! 2T, 4T... up to the cap, which is also escalated). Dedupe: a handled job sends nothing more, EOF after the end
//! is stale, VANISHED after END is skipped, each kind once. An end notice that lands mid-turn is parked and sent on
//! the next [BgJobNotifier::scanned] pass once the session is idle.
//!
//! Every text comes from `resources/watch-notices.json` (the Kotlin `watch/notices.json`), overridable as a whole.
use std::collections::{HashMap, HashSet};
use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;
use std::sync::{Arc, Mutex};

use regex::Regex;
use serde::{Deserialize, Serialize};

use super::mailbox::{MailResult, Mailbox};
use super::message::{MailSendRequest, SYSTEM};

const NOTICES_JSON: &str = include_str!("../../resources/watch-notices.json");

/// The notice wording: key -> template with `{name}` holes.
#[derive(Debug, Clone)]
pub struct WatchNotices(HashMap<String, String>);

impl Default for WatchNotices {
    fn default() -> Self {
        WatchNotices(serde_json::from_str(NOTICES_JSON).expect("bundled watch-notices.json"))
    }
}

impl WatchNotices {
    /// The defaults with the keys of `overrides` (the `watch.notices` setting) replaced.
    pub fn with_overrides(overrides: &HashMap<String, String>) -> Self {
        let mut n = Self::default();
        n.0.extend(overrides.iter().map(|(k, v)| (k.clone(), v.clone())));
        n
    }
    pub fn text(&self, key: &str, values: &[(&str, String)]) -> String {
        let mut t = self.0.get(key).cloned().unwrap_or_else(|| key.to_string());
        for (k, v) in values {
            t = t.replace(&format!("{{{k}}}"), v);
        }
        t
    }
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BgNotify {
    #[serde(default)]
    pub tail_lines: Option<usize>,
    #[serde(default)]
    pub patterns: Vec<String>,
    #[serde(default)]
    pub timeout_minutes: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BgJob {
    pub id: String,
    pub label: String,
    pub pid: i64,
    pub started_at: i64,
    #[serde(default)]
    pub last_beat: i64,
    #[serde(default)]
    pub log_path: Option<String>,
    #[serde(default)]
    pub keep: bool,
    #[serde(default)]
    pub notify: Option<BgNotify>,
    #[serde(default)]
    pub attached: bool,
    #[serde(default)]
    pub opt_out: Option<String>,
    #[serde(default)]
    pub cmd_pid: Option<i64>,
    #[serde(default)]
    pub follow: Vec<String>,
    #[serde(default)]
    pub log_paused: Option<String>,
}

/// What the notifier asks of the daemon.
pub trait BgHost: Send + Sync {
    /// Every registered job with its session.
    fn jobs(&self) -> Vec<(String, BgJob)>;
    fn in_turn(&self, session: &str) -> bool;
    fn pid_alive(&self, pid: i64) -> bool;
    /// Who else hears about a long wait or an opt-out (the starter, the user); None = nobody.
    fn recipient_of(&self, _session: &str) -> Option<String> {
        None
    }
    /// Ends a job's process tree (hang watch).
    fn end_tree(&self, _session: &str, _job: &BgJob) {}
    /// The nudge gate for an end notice: true = a hook handled it, do not mail.
    fn gate_handled(&self, _session: &str, _job: &BgJob, _subject: &str, _body: &str) -> bool {
        false
    }
}

/// Notifier settings (the `limits.watch.*` / `bg.notice.*` keys).
#[derive(Debug, Clone)]
pub struct BgSettings {
    pub default_tail_lines: usize,
    pub default_timeout_ms: i64,
    pub quiet_ms: i64,
    pub heartbeat_cap_ms: i64,
    pub status_notices: bool,
    pub line_chars: usize,
    pub read_max_bytes: u64,
    pub tail_read_bytes: u64,
    pub dedupe: bool,
    pub keep_ms: i64,
    pub hang_grace_ms: i64,
    pub fold_into_turn: bool,
    pub skip_if_result_read: bool,
}

impl Default for BgSettings {
    fn default() -> Self {
        BgSettings {
            default_tail_lines: 20,
            default_timeout_ms: 15 * 60_000,
            quiet_ms: 5 * 60_000,
            heartbeat_cap_ms: 60 * 60_000,
            status_notices: false,
            line_chars: 200,
            read_max_bytes: 256 * 1024,
            tail_read_bytes: 64 * 1024,
            dedupe: true,
            keep_ms: 24 * 60 * 60_000,
            hang_grace_ms: 30_000,
            fold_into_turn: true,
            skip_if_result_read: true,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
enum Kind {
    Eof,
    End,
    Vanished,
}

#[derive(Default)]
struct Cursor {
    offset: Option<u64>,
    partial: String,
}

#[derive(Default)]
struct Watch {
    started_at: i64,
    cursors: HashMap<String, Cursor>,
    matches: Vec<String>,
    first_match_at: i64,
    next_beat_at: i64,
    interval: i64,
    escalated: bool,
    last_output_at: i64,
    last_hang_at: i64,
    read_error: Option<String>,
    error_sent: bool,
    opt_out_seen: bool,
    paused_sent: bool,
    hang_ended: bool,
}

struct Finished {
    session: String,
    label: String,
    ended_at: i64,
    handled: bool,
}

struct Parked {
    session: String,
    job: BgJob,
    req: MailSendRequest,
}

#[derive(Default)]
struct State {
    watches: HashMap<String, Watch>,
    finished: HashMap<String, Finished>,
    handled: HashMap<String, i64>,
    sent_kinds: HashMap<String, HashSet<Kind>>,
    parked: HashMap<String, Parked>,
    ended_at: HashMap<String, i64>,
}

type SendFn = Arc<dyn Fn(MailSendRequest) -> MailResult + std::marker::Send + Sync>;

pub struct BgJobNotifier {
    host: Arc<dyn BgHost>,
    mailbox: Arc<Mailbox>,
    send: SendFn,
    settings: Arc<dyn Fn() -> BgSettings + std::marker::Send + Sync>,
    notices: WatchNotices,
    state: Mutex<State>,
    wrapper_label: Regex,
}

/// `Ns` under a minute, else `NmSs`.
pub fn fmt(ms: i64) -> String {
    let s = (ms / 1000).max(0);
    if s < 60 {
        format!("{s}s")
    } else {
        format!("{}m{}s", s / 60, s % 60)
    }
}

/// The last `n` lines of `path`, read from at most its last `max_bytes`; empty when it is gone or unreadable.
pub fn read_tail(path: &Path, n: usize, max_bytes: u64) -> Vec<String> {
    let Ok(mut f) = File::open(path) else { return vec![] };
    let len = f.metadata().map(|m| m.len()).unwrap_or(0);
    let from = len.saturating_sub(max_bytes.max(1024));
    if f.seek(SeekFrom::Start(from)).is_err() {
        return vec![];
    }
    let mut buf = Vec::new();
    let _ = f.read_to_end(&mut buf);
    let text = String::from_utf8_lossy(&buf);
    let mut lines: Vec<String> = text.split('\n').map(|l| l.trim_end_matches('\r').to_string()).collect();
    if from > 0 && !lines.is_empty() {
        lines.remove(0);
    }
    while lines.last().is_some_and(|l| l.is_empty()) {
        lines.pop();
    }
    let skip = lines.len().saturating_sub(n);
    lines.split_off(skip)
}

impl BgJobNotifier {
    /// `send` is the router's automation send (`router.send(req, true)`).
    pub fn new(host: Arc<dyn BgHost>, mailbox: Arc<Mailbox>, send: SendFn, settings: Arc<dyn Fn() -> BgSettings + std::marker::Send + Sync>, notices: WatchNotices) -> Self {
        BgJobNotifier { host, mailbox, send, settings, notices, state: Mutex::new(State::default()), wrapper_label: Regex::new(r"echo\s+\S*\$\?").unwrap() }
    }

    fn now(&self) -> i64 {
        self.mailbox.now()
    }

    fn t(&self, key: &str, job: &BgJob, extra: &[(&str, String)]) -> String {
        let mut values = vec![("id", job.id.clone()), ("label", job.label.chars().take(60).collect()), ("elapsed", fmt(self.now() - job.started_at))];
        values.extend(extra.iter().cloned());
        self.notices.text(key, &values)
    }

    /// The session has seen the job's result (`infantry bg ack`, or read its log after the end). Returns how many
    /// queued notices were dropped.
    pub fn ack(&self, session: &str, job_id: &str) -> usize {
        {
            let mut s = self.state.lock().unwrap();
            s.handled.insert(job_id.to_string(), self.now());
            if let Some(f) = s.finished.get_mut(job_id) {
                f.handled = true;
            }
            s.parked.remove(job_id);
        }
        self.mailbox.drop_queued(&format!("bg:{job_id}"), session, "handled: the job's result was already read", |_| true)
    }

    pub fn is_handled(&self, job_id: &str) -> bool {
        self.state.lock().unwrap().handled.contains_key(job_id)
    }

    /// When a job of `session` last ended (the stall detector's input).
    pub fn last_ended_at(&self, session: &str) -> Option<i64> {
        self.state.lock().unwrap().ended_at.get(session).copied()
    }

    fn deliver(&self, session: &str, job: &BgJob, req: MailSendRequest, kind: Option<Kind>) {
        let cfg = (self.settings)();
        if job.attached && self.host.in_turn(session) {
            return self.skipped(job, kind, "attached job, its turn saw the end");
        }
        {
            let mut s = self.state.lock().unwrap();
            if cfg.dedupe {
                if cfg.skip_if_result_read && s.handled.contains_key(&job.id) {
                    drop(s);
                    return self.skipped(job, kind, "result already read");
                }
                if kind == Some(Kind::Eof) && s.finished.contains_key(&job.id) {
                    drop(s);
                    return self.skipped(job, kind, "stale after the end");
                }
                if kind == Some(Kind::Vanished) && s.sent_kinds.get(&job.id).is_some_and(|k| k.contains(&Kind::End)) {
                    drop(s);
                    return self.skipped(job, kind, "the job already reported its real end");
                }
                if let Some(k) = kind {
                    if !s.sent_kinds.entry(job.id.clone()).or_default().insert(k) {
                        drop(s);
                        return self.skipped(job, kind, "already sent once");
                    }
                }
            }
            let is_end = matches!(kind, Some(Kind::End | Kind::Vanished));
            if cfg.fold_into_turn && is_end && self.host.in_turn(session) {
                s.parked.insert(job.id.clone(), Parked { session: session.into(), job: job.clone(), req });
                return;
            }
        }
        if matches!(kind, Some(Kind::End | Kind::Vanished)) && self.host.gate_handled(session, job, &req.subject, &req.body) {
            return;
        }
        (self.send)(req);
    }

    fn skipped(&self, job: &BgJob, kind: Option<Kind>, why: &str) {
        eprintln!("[infantry] bg notice skipped: job {} kind {}: {why}", job.id, kind.map(|k| format!("{k:?}").to_lowercase()).unwrap_or_else(|| "status".into()));
    }

    fn note(&self, session: &str, job: &BgJob, subject: String, verdict: &str) -> MailSendRequest {
        MailSendRequest {
            from: SYSTEM.into(),
            to: session.into(),
            subject,
            thread_id: Some(format!("bg:{}", job.id)),
            body: format!("task finished: {} {verdict} log {}\n", job.id, job.log_path.as_deref().unwrap_or("-")),
            ..Default::default()
        }
    }

    fn finish(&self, session: &str, job: &BgJob) {
        let now = self.now();
        {
            let mut s = self.state.lock().unwrap();
            s.ended_at.insert(session.into(), now);
            s.finished.insert(job.id.clone(), Finished { session: session.into(), label: job.label.clone(), ended_at: now, handled: false });
            s.watches.remove(&job.id);
        }
        if (self.settings)().dedupe {
            let eof_subject = self.t("job.eof.subject", job, &[]);
            self.mailbox.drop_queued(&format!("bg:{}", job.id), session, "superseded: the job has finished", |m| m.subject == eof_subject);
        }
    }

    /// The wrapper reported the end with `exit_code`. `bytes`, `marker` are the end facts the frame carries.
    pub fn done(&self, session: &str, job: &BgJob, exit_code: Option<i32>, bytes: Option<u64>, marker: Option<&str>) {
        if job.notify.is_none() {
            return;
        }
        self.finish(session, job);
        let verdict = exit_code.map(|c| format!("exit {c}")).unwrap_or_else(|| "exit unknown".into());
        let line_chars = (self.settings)().line_chars;
        let marker: Option<String> = marker.map(|m| m.chars().take(line_chars).collect());
        let subject = self.t(
            "job.done.subject",
            job,
            &[
                ("verdict", verdict.clone() + if marker.is_some() { ", failure marker" } else { "" }),
                ("noOutput", if bytes == Some(0) { ", no output".into() } else { String::new() }),
            ],
        );
        let full = match &marker {
            Some(m) => format!("{verdict} (failure marker: {m})"),
            None => verdict,
        };
        let req = self.note(session, job, subject, &full);
        self.deliver(session, job, req, Some(Kind::End));
    }

    /// The end of a job the daemon no longer holds: still told, never a silent drop.
    pub fn done_unregistered(&self, session: &str, job_id: &str, exit_code: Option<i32>) {
        let label = self.state.lock().unwrap().finished.get(job_id).map(|f| f.label.clone()).unwrap_or_else(|| format!("job {job_id}"));
        let job = BgJob { id: job_id.into(), label, started_at: self.now(), notify: Some(BgNotify::default()), ..Default::default() };
        self.done(session, &job, exit_code, None, None);
    }

    /// The job's runner is gone without reporting: the exit code is read from `<log minus .log>.exit` when present.
    pub fn expired(&self, session: &str, job: &BgJob) {
        if job.notify.is_none() {
            return;
        }
        self.finish(session, job);
        let code = job.log_path.as_deref().and_then(|p| std::fs::read_to_string(format!("{}.exit", p.strip_suffix(".log").unwrap_or(p))).ok()).and_then(|t| t.trim().parse::<i32>().ok());
        let (subject, verdict) = match code {
            Some(c) => (self.t("job.vanished.subjectExit", job, &[("exit", c.to_string())]), format!("exit {c}")),
            None => (self.t("job.vanished.subjectUnknown", job, &[]), "exit unknown".into()),
        };
        let req = self.note(session, job, subject, &verdict);
        self.deliver(session, job, req, Some(Kind::Vanished));
    }

    /// The command closed its output and still runs: told once, never as done.
    pub fn eof(&self, session: &str, job: &BgJob, bytes: Option<u64>) {
        if job.notify.is_none() {
            return;
        }
        let subject = self.t("job.eof.subject", job, &[]);
        let head = self.t("job.eof.head", job, &[("bytes", bytes.map(|b| b.to_string()).unwrap_or_else(|| "?".into()))]);
        let lines = self.tail_of(job);
        let req = self.mail(session, job, subject, head, &lines);
        self.deliver(session, job, req, Some(Kind::Eof));
    }

    fn tail_of(&self, job: &BgJob) -> Vec<String> {
        let cfg = (self.settings)();
        let n = job.notify.as_ref().and_then(|n| n.tail_lines).unwrap_or(cfg.default_tail_lines).max(1);
        job.follow.first().or(job.log_path.as_ref()).map(|p| read_tail(Path::new(p), n, cfg.tail_read_bytes)).unwrap_or_default()
    }

    fn mail(&self, session: &str, job: &BgJob, subject: String, head: String, lines: &[String]) -> MailSendRequest {
        let cfg = (self.settings)();
        let cut = |l: &str| {
            let max = cfg.line_chars.max(20);
            if l.chars().count() <= max {
                l.to_string()
            } else {
                l.chars().take(max).collect::<String>() + "…"
            }
        };
        let mut body = format!("{head}\n");
        if let Some(p) = &job.log_path {
            body.push_str(&format!("log: {p}\n"));
        }
        for f in &job.follow {
            body.push_str(&format!("following: {f}\n"));
        }
        if !lines.is_empty() {
            body.push_str(&format!("last {} lines:\n", lines.len()));
            for l in lines {
                body.push_str(&format!("  {}\n", cut(l)));
            }
        }
        if cfg.dedupe {
            body.push_str(&self.notices.text("job.ackHint", &[("id", job.id.clone())]));
            body.push('\n');
        }
        MailSendRequest { from: SYSTEM.into(), to: session.into(), subject, thread_id: Some(format!("bg:{}", job.id)), body, ..Default::default() }
    }

    /// The session manager's scan pass: flush parked notices, read new output, hang watch, heartbeats, quiet notices,
    /// supersede and forget ended jobs.
    pub fn scanned(&self) {
        self.flush_parked();
        let cfg = (self.settings)();
        let now = self.now();
        let all = self.host.jobs();
        let live: Vec<&(String, BgJob)> = all.iter().filter(|(_, j)| j.notify.is_some()).collect();
        if cfg.dedupe {
            self.supersede(&all, now, &cfg);
        }
        let mut outgoing: Vec<(String, BgJob, MailSendRequest)> = Vec::new();
        let mut escalations: Vec<MailSendRequest> = Vec::new();
        let mut hang_end: Vec<(String, BgJob)> = Vec::new();
        {
            let mut s = self.state.lock().unwrap();
            let ids: HashSet<&str> = live.iter().map(|(_, j)| j.id.as_str()).collect();
            s.watches.retain(|k, _| ids.contains(k.as_str()));
            for (sid, job) in &live {
                let notify = job.notify.clone().unwrap_or_default();
                let finished = s.finished.contains_key(&job.id);
                let w = s.watches.entry(job.id.clone()).or_insert_with(|| Watch { started_at: job.started_at, last_output_at: job.started_at, ..Default::default() });
                if let (Some(reason), false) = (&job.opt_out, w.opt_out_seen) {
                    w.opt_out_seen = true;
                    if let Some(to) = self.host.recipient_of(sid) {
                        escalations.push(MailSendRequest {
                            from: SYSTEM.into(),
                            to,
                            thread_id: Some(format!("optout:{sid}")),
                            subject: self.t("escalate.optOut.subject", job, &[("session", sid.chars().take(8).collect()), ("label", job.label.chars().take(50).collect())]),
                            body: self.t("escalate.optOut.body", job, &[("sessionFull", sid.clone()), ("label", job.label.chars().take(80).collect()), ("reason", reason.clone())]) + "\n",
                            ..Default::default()
                        });
                    }
                }
                let watched = job.log_path.is_some() || !job.follow.is_empty();
                if watched {
                    let patterns: Vec<Regex> = notify.patterns.iter().filter_map(|p| Regex::new(p).ok()).collect();
                    let mut err = None;
                    if let (Some(p), None) = (&job.log_path, &job.log_paused) {
                        err = read_new(p, true, w, &patterns, now, &cfg).err();
                    }
                    for f in &job.follow {
                        if let Err(e) = read_new(f, false, w, &patterns, now, &cfg) {
                            err = Some(e);
                        }
                    }
                    w.read_error = err;
                }
                if let (Some(reason), false) = (&job.log_paused, w.paused_sent) {
                    w.paused_sent = true;
                    let req = self.mail(sid, job, self.t("job.logPaused.subject", job, &[]), self.t("job.logPaused.head", job, &[("reason", reason.clone())]), &[]);
                    outgoing.push((sid.clone(), job.clone(), req));
                }
                if !w.matches.is_empty() {
                    let grace = cfg.hang_grace_ms;
                    if grace <= 0 || grace == i64::MAX {
                        w.matches.clear();
                    } else if now - w.first_match_at >= grace && !w.hang_ended {
                        w.hang_ended = true;
                        if self.host.pid_alive(job.cmd_pid.unwrap_or(job.pid)) {
                            hang_end.push((sid.clone(), job.clone()));
                        }
                        w.matches.clear();
                    }
                }
                let beat = heartbeat_due(w, notify.timeout_minutes, now, &cfg);
                if !cfg.status_notices {
                    continue;
                }
                let alive = self.host.pid_alive(job.cmd_pid.unwrap_or(job.pid));
                if beat || (w.read_error.is_some() && !w.error_sent) {
                    if w.read_error.is_some() {
                        w.error_sent = true;
                    }
                    let log_state = match (&w.read_error, job.follow.first().or(job.log_path.as_ref())) {
                        (Some(e), _) => format!("the watcher could not read the log ({e})"),
                        (None, None) => "no log (output not watched)".into(),
                        (None, Some(p)) if !Path::new(p).is_file() => "the log is gone".into(),
                        _ if now - w.last_output_at >= 60_000 => format!("no output for {}", fmt(now - w.last_output_at)),
                        _ => format!("last output {} ago", fmt(now - w.last_output_at)),
                    };
                    let proc = if alive { "its process is still running" } else { "its process is GONE (the end report has not arrived)" };
                    let head = self.t("job.heartbeat.head", job, &[("logState", log_state.clone()), ("proc", proc.into()), ("next", fmt(w.interval))]);
                    let lines = self.tail_of(job);
                    outgoing.push((sid.clone(), job.clone(), self.mail(sid, job, self.t("job.heartbeat.subject", job, &[]), head, &lines)));
                    if !w.escalated && w.interval >= cfg.heartbeat_cap_ms {
                        w.escalated = true;
                        if let Some(to) = self.host.recipient_of(sid) {
                            escalations.push(MailSendRequest {
                                from: SYSTEM.into(),
                                to,
                                thread_id: Some(format!("bg:{}:watch", job.id)),
                                subject: self.t("escalate.longWait.subject", job, &[("session", sid.chars().take(8).collect())]),
                                body: self.t(
                                    "escalate.longWait.body",
                                    job,
                                    &[("sessionFull", sid.clone()), ("label", job.label.chars().take(80).collect()), ("logState", log_state), ("proc", proc.into()), ("cap", fmt(cfg.heartbeat_cap_ms))],
                                ) + "\n",
                                ..Default::default()
                            });
                        }
                    }
                } else if watched && job.log_paused.is_none() && !finished && alive && cfg.quiet_ms > 0 && cfg.quiet_ms != i64::MAX && now - w.last_output_at >= cfg.quiet_ms && now - w.last_hang_at >= cfg.quiet_ms {
                    w.last_hang_at = now;
                    let quiet = fmt(now - w.last_output_at);
                    let req = MailSendRequest {
                        from: SYSTEM.into(),
                        to: sid.clone(),
                        subject: self.t("job.quiet.subject", job, &[("quiet", quiet.clone())]),
                        thread_id: Some(format!("bg:{}", job.id)),
                        body: format!("task hanging: {} no output {quiet}, check log {}\n", job.id, job.follow.first().or(job.log_path.as_ref()).map(String::as_str).unwrap_or("-")),
                        ..Default::default()
                    };
                    outgoing.push((sid.clone(), job.clone(), req));
                }
            }
        }
        for (sid, job) in hang_end {
            eprintln!("[infantry] hang-watch: ending job {} (session {sid})", job.id);
            self.host.end_tree(&sid, &job);
        }
        for (sid, job, req) in outgoing {
            self.deliver(&sid, &job, req, None);
        }
        for req in escalations {
            (self.send)(req);
        }
    }

    fn flush_parked(&self) {
        let ready: Vec<(String, Parked)> = {
            let mut s = self.state.lock().unwrap();
            let ids: Vec<String> = s.parked.iter().filter(|(_, p)| !self.host.in_turn(&p.session)).map(|(k, _)| k.clone()).collect();
            ids.into_iter().filter_map(|id| s.parked.remove(&id).map(|p| (id, p))).collect()
        };
        let skip_read = (self.settings)().skip_if_result_read;
        for (id, p) in ready {
            if skip_read && self.is_handled(&id) {
                continue;
            }
            if self.host.gate_handled(&p.session, &p.job, &p.req.subject, &p.req.body) {
                continue;
            }
            (self.send)(p.req);
        }
    }

    fn supersede(&self, all: &[(String, BgJob)], now: i64, cfg: &BgSettings) {
        let mut drops = Vec::new();
        {
            let mut s = self.state.lock().unwrap();
            let expired: Vec<String> = s.finished.iter().filter(|(_, f)| now - f.ended_at > cfg.keep_ms).map(|(k, _)| k.clone()).collect();
            for id in expired {
                s.finished.remove(&id);
                s.handled.remove(&id);
                s.sent_kinds.remove(&id);
            }
            let mut newly = Vec::new();
            for (id, f) in s.finished.iter_mut() {
                if !f.handled && all.iter().any(|(sid, j)| *sid == f.session && j.id != *id && j.label == f.label && j.started_at >= f.ended_at) {
                    f.handled = true;
                    newly.push(id.clone());
                    drops.push((id.clone(), f.session.clone()));
                }
            }
            for id in newly {
                s.handled.insert(id, now);
            }
        }
        for (id, session) in drops {
            self.mailbox.drop_queued(&format!("bg:{id}"), &session, "superseded: a newer job with the same label started", |_| true);
        }
    }
}

/// Beats at T, then 2T, 4T later... up to the cap.
fn heartbeat_due(w: &mut Watch, timeout_minutes: Option<i64>, now: i64, cfg: &BgSettings) -> bool {
    let base = match timeout_minutes {
        Some(m) if m <= 0 => return false,
        Some(m) => m * 60_000,
        None => cfg.default_timeout_ms,
    };
    if base <= 0 || base == i64::MAX {
        return false;
    }
    if w.interval == 0 {
        w.interval = base;
        w.next_beat_at = w.started_at + base;
    }
    if now < w.next_beat_at {
        return false;
    }
    let cap = cfg.heartbeat_cap_ms.max(base);
    w.interval = (w.interval * 2).min(cap);
    w.next_beat_at = now + w.interval;
    true
}

/// Reads the bytes appended to `path` since the last pass; records output time and pattern matches. In the runner's
/// own log, its `[infantry:` lines are not output.
fn read_new(path: &str, own: bool, w: &mut Watch, patterns: &[Regex], now: i64, cfg: &BgSettings) -> Result<(), String> {
    let p = Path::new(path);
    if !p.is_file() {
        return Ok(());
    }
    let mut f = File::open(p).map_err(|e| e.to_string())?;
    let len = f.metadata().map_err(|e| e.to_string())?.len();
    let c = w.cursors.entry(path.to_string()).or_default();
    let mut offset = *c.offset.get_or_insert(if own { 0 } else { len });
    if len < offset {
        offset = 0;
        c.partial.clear();
    }
    if len == offset {
        c.offset = Some(offset);
        return Ok(());
    }
    let max_read = cfg.read_max_bytes.max(1024);
    if len - offset > max_read {
        offset = len - max_read;
        c.partial.clear();
    }
    f.seek(SeekFrom::Start(offset)).map_err(|e| e.to_string())?;
    let mut buf = Vec::with_capacity((len - offset) as usize);
    f.take(len - offset).read_to_end(&mut buf).map_err(|e| e.to_string())?;
    c.offset = Some(offset + buf.len() as u64);
    if buf.is_empty() {
        return Ok(());
    }
    if !own {
        w.last_output_at = now;
    }
    let text = std::mem::take(&mut c.partial) + &String::from_utf8_lossy(&buf);
    let mut parts: Vec<&str> = text.split('\n').collect();
    let last = parts.pop().unwrap_or("");
    let keep = cfg.tail_read_bytes.max(1024) as usize;
    c.partial = last.chars().rev().take(keep).collect::<Vec<_>>().into_iter().rev().collect();
    for line in parts {
        let l = line.trim_end_matches('\r');
        if own && !l.starts_with("[infantry:") && !l.starts_with("[agenthq:") {
            w.last_output_at = now;
        }
        if patterns.iter().any(|re| re.is_match(l)) {
            if w.matches.is_empty() {
                w.first_match_at = now;
            }
            w.matches.push(l.chars().take(cfg.line_chars).collect());
        }
    }
    Ok(())
}

impl BgJobNotifier {
    /// True when the label echoes `$?` (the watched exit is the wrapper shell's).
    pub fn wrapper_label(&self, label: &str) -> bool {
        self.wrapper_label.is_match(label)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mail::mailbox::{tests::mailbox, MailLimits};
    use std::sync::atomic::{AtomicBool, Ordering};

    struct Host(AtomicBool);
    impl BgHost for Host {
        fn jobs(&self) -> Vec<(String, BgJob)> {
            vec![]
        }
        fn in_turn(&self, _s: &str) -> bool {
            self.0.load(Ordering::SeqCst)
        }
        fn pid_alive(&self, _p: i64) -> bool {
            true
        }
    }

    #[test]
    fn end_notice_dedupes_and_parks_mid_turn() {
        let (mb, _) = mailbox(MailLimits::default());
        let host = Arc::new(Host(AtomicBool::new(false)));
        let mbs = mb.clone();
        let n = BgJobNotifier::new(host.clone(), mb.clone(), Arc::new(move |r| mbs.send(&r, true)), Arc::new(BgSettings::default), WatchNotices::default());
        let job = BgJob { id: "j1".into(), label: "gradle build".into(), notify: Some(BgNotify::default()), log_path: Some("x.log".into()), ..Default::default() };
        n.done("s1", &job, Some(0), Some(10), None);
        let inbox = mb.inbox("s1");
        assert_eq!(inbox.len(), 1);
        assert_eq!(inbox[0].thread_id, "bg:j1");
        assert_eq!(inbox[0].body, "task finished: j1 exit 0 log x.log\n");
        assert!(inbox[0].subject.starts_with("[AUTOMATIC] bg job j1 finished, exit 0"));
        n.expired("s1", &job);
        n.done("s1", &job, Some(0), None, None);
        assert_eq!(mb.inbox("s1").len(), 1);
        // Mid-turn: parked until the scan after the turn.
        host.0.store(true, Ordering::SeqCst);
        let job2 = BgJob { id: "j2".into(), ..job.clone() };
        n.done("s1", &job2, Some(1), None, None);
        assert_eq!(mb.inbox("s1").len(), 1);
        host.0.store(false, Ordering::SeqCst);
        n.scanned();
        assert_eq!(mb.inbox("s1").len(), 2);
        assert_eq!(fmt(61_000), "1m1s");
    }
}
