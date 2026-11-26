// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! MAIL-OWNER-2: mail to the owner at the end of a work block, and alert mails only for what matters.
//!
//! - Summary: when a project's ticket workers go from busy to none running (setting `mail.owner.summary` = `idle`,
//!   the default), or once a day at a set time (`HH:MM`, UTC like quiet hours), the owner gets ONE short mail: tickets done since the last
//!   summary (title + the first line of the done comment), still running, blocked or stopped. A summary that would say
//!   the same as the last one is folded (not sent). `off` turns it off.
//! - Alerts (setting `mail.owner.alerts`, default on): one mail per cause until that cause clears: a worker the
//!   watchdog reports stuck (`session.stalled`; its minutes are the watchdog's `session.*Minutes` settings), a mail
//!   notice whose kind is in `mail.owner.alertKinds` (default `build.failed`: the release BUILD FAILED notice), and
//!   free disk under `health.minDiskMb`.
//!
//! Both go through the one owner mail path (SYSTEM -> `owner`), so they show in the Inbox (desktop and phone) and
//! Mark all read / Archive read work on them. Generic: names no board column of its own, no project, no plugin.

use infantry_core::bus::EventBus;
use infantry_mail::mail::message::{MailSendRequest, SYSTEM};
use infantry_mail::mail::router::MailRouter;
use infantry_orchestrator_api::dto::{status, Ticket};
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};

pub const SUMMARY_SETTING: &str = "mail.owner.summary";
pub const ALERTS_SETTING: &str = "mail.owner.alerts";
pub const ALERT_KINDS_SETTING: &str = "mail.owner.alertKinds";
pub const TICK_SETTING: &str = "mail.owner.tickSeconds";
pub const DEFAULT_ALERT_KINDS: [&str; 1] = ["build.failed"];
const DEFAULT_TICK_SECS: u64 = 60;
/// The timed summary runs at most once in this window (once a day, robust to the clock's time zone).
const TIMED_GAP_MS: i64 = 20 * 3_600_000;
/// The generic bus event a mail notice with a kind emits (agent_tools `infantry_message`).
pub const MAIL_NOTICE: &str = "mail.notice";
/// Lines kept per section of a summary; the rest is counted.
pub const SUMMARY_SECTION_LINES: usize = 15;

/// When the summary is sent.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SummaryMode {
    Off,
    /// When a project's ticket workers go from busy to none.
    Idle,
    /// Once a day at this minute of the day (local), when something changed.
    At(u32),
}

pub fn summary_mode(v: Option<&serde_json::Value>) -> SummaryMode {
    let s = match v {
        Some(serde_json::Value::Bool(false)) => return SummaryMode::Off,
        Some(serde_json::Value::String(s)) => s.trim().to_ascii_lowercase(),
        _ => return SummaryMode::Idle,
    };
    match s.as_str() {
        "off" | "false" | "none" | "0" => SummaryMode::Off,
        "" | "idle" | "on" | "true" => SummaryMode::Idle,
        hm => hm
            .split_once(':')
            .and_then(|(h, m)| Some((h.parse::<u32>().ok()?, m.parse::<u32>().ok()?)))
            .filter(|(h, m)| *h < 24 && *m < 60)
            .map(|(h, m)| SummaryMode::At(h * 60 + m))
            .unwrap_or(SummaryMode::Idle),
    }
}

/// Whether a project's summary is due this tick: idle mode on busy -> none, timed mode once the minute is reached and
/// the last timed summary is older than a day's gap.
pub fn summary_due(mode: &SummaryMode, was_busy: bool, busy: bool, minute: u32, now: i64, last_timed: i64) -> bool {
    match mode {
        SummaryMode::Off => false,
        SummaryMode::Idle => was_busy && !busy,
        SummaryMode::At(m) => minute >= *m && now - last_timed >= TIMED_GAP_MS,
    }
}

/// One summary mail.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Summary {
    pub subject: String,
    pub body: String,
    /// What the summary says, minus the clock: equal fingerprints = nothing changed.
    pub fingerprint: String,
    pub done: usize,
}

fn first_line(s: &str) -> String {
    s.lines().map(str::trim).find(|l| !l.is_empty()).unwrap_or("").chars().take(160).collect()
}

fn section(out: &mut String, head: &str, lines: &[String]) {
    if lines.is_empty() {
        return;
    }
    out.push_str(&format!("{head} ({}):\n", lines.len()));
    for l in lines.iter().take(SUMMARY_SECTION_LINES) {
        out.push_str(&format!("- {l}\n"));
    }
    if lines.len() > SUMMARY_SECTION_LINES {
        out.push_str(&format!("- ... and {} more\n", lines.len() - SUMMARY_SECTION_LINES));
    }
    out.push('\n');
}

/// The summary of one project's board. `since` = the last summary's time (tickets done after it count as new);
/// `done_line` = the first line of a ticket's latest done comment; `live` = ticket ids with a running worker.
/// None = nothing to say (no ticket done, running or blocked).
pub fn build_summary(project: &str, tickets: &[Ticket], since: i64, done_line: &dyn Fn(&str) -> Option<String>, live: &HashSet<String>) -> Option<Summary> {
    let mut done = Vec::new();
    let mut running = Vec::new();
    let mut blocked = Vec::new();
    let mut ids = Vec::new();
    let mut sorted: Vec<&Ticket> = tickets.iter().collect();
    sorted.sort_by_key(|t| (t.updated_at, t.id.clone()));
    for t in sorted {
        let title: String = t.title.chars().take(90).collect();
        match t.status.as_str() {
            status::DONE if t.updated_at > since && !t.wont_fix => {
                let line = done_line(&t.id).map(|l| first_line(&l)).filter(|l| !l.is_empty());
                done.push(match line {
                    Some(l) => format!("{} {title}: {l}", t.id),
                    None => format!("{} {title}", t.id),
                });
                ids.push(format!("d:{}", t.id));
            }
            status::IN_PROGRESS if live.contains(&t.id) => {
                running.push(format!("{} {title}", t.id));
                ids.push(format!("r:{}", t.id));
            }
            status::IN_PROGRESS => {
                blocked.push(format!("{} {title} (in progress, no worker running)", t.id));
                ids.push(format!("s:{}", t.id));
            }
            status::PENDING => {
                let why = t.hold_reason.as_deref().map(|r| format!(": {}", first_line(r))).unwrap_or_default();
                blocked.push(format!("{} {title} (blocked{why})", t.id));
                ids.push(format!("b:{}", t.id));
            }
            _ => {}
        }
    }
    if done.is_empty() && running.is_empty() && blocked.is_empty() {
        return None;
    }
    let mut body = String::new();
    section(&mut body, "Done", &done);
    section(&mut body, "Still running", &running);
    section(&mut body, "Blocked or stopped", &blocked);
    let subject = format!("Work summary ({project}): {} done, {} running, {} blocked", done.len(), running.len(), blocked.len());
    ids.sort();
    Some(Summary { subject, body: body.trim_end().to_string(), fingerprint: ids.join(","), done: done.len() })
}

/// Fold: a summary is sent unless it says the same as the last one sent and nothing new is done.
pub fn should_send(s: &Summary, last_fingerprint: Option<&str>) -> bool {
    s.done > 0 || last_fingerprint != Some(s.fingerprint.as_str())
}

/// Whether a mail notice becomes an owner alert: its kind is an alert kind, and it is not already mail to the owner
/// (the owner would get it twice).
pub fn notice_alerts(kinds: &[String], kind: &str, to: &str) -> bool {
    let addr = to.split('@').next().unwrap_or(to);
    kinds.iter().any(|k| k.eq_ignore_ascii_case(kind)) && !infantry_mail::mail::message::is_owner_address(addr)
}

/// Alert dedupe: one mail per cause until it clears.
#[derive(Default)]
pub struct AlertGate {
    open: Mutex<HashMap<String, i64>>,
}

impl AlertGate {
    /// True the first time a cause fires (send the mail); false while it is still open.
    pub fn fire(&self, cause: &str, now: i64) -> bool {
        let mut m = self.open.lock().unwrap();
        if m.contains_key(cause) {
            return false;
        }
        m.insert(cause.to_string(), now);
        true
    }
    pub fn clear(&self, cause: &str) {
        self.open.lock().unwrap().remove(cause);
    }
    /// Open causes with this prefix and when they fired.
    pub fn open_with(&self, prefix: &str) -> Vec<(String, i64)> {
        self.open.lock().unwrap().iter().filter(|(k, _)| k.starts_with(prefix)).map(|(k, v)| (k.clone(), *v)).collect()
    }
}

/// Per project: was it busy at the last tick, and what the last summary said.
#[derive(Default, Clone)]
struct ProjectState {
    busy: bool,
    last_at: i64,
    last_fingerprint: Option<String>,
    /// When the timed (`HH:MM`) summary last ran.
    last_timed: i64,
}

/// What the owner-mail loop reads; the composition root wires it.
pub struct Ports {
    pub mail: Arc<MailRouter>,
    pub settings: super::settings::SettingsUnit,
    /// Every ticket on the board.
    pub tickets: Arc<dyn Fn() -> Vec<Ticket> + Send + Sync>,
    /// The body of a ticket's latest done comment.
    pub done_comment: Arc<dyn Fn(&str) -> Option<String> + Send + Sync>,
    /// Live ticket workers: ticket id and project id.
    pub workers: Arc<dyn Fn() -> Vec<WorkerRow> + Send + Sync>,
    /// Whether a session has ended or is gone, and its last event time.
    pub session_seen: Arc<dyn Fn(&str) -> Option<(bool, i64)> + Send + Sync>,
    /// Free disk in MB and the threshold, None when unknown.
    pub disk: Arc<dyn Fn() -> Option<(u64, u64)> + Send + Sync>,
    pub now: fn() -> i64,
    /// Local minute of the day, for `HH:MM` mode.
    pub minute_of_day: fn() -> u32,
}

#[derive(Debug, Clone)]
pub struct WorkerRow {
    pub ticket_id: String,
    pub project_id: String,
}

pub struct OwnerMail {
    ports: Ports,
    pub alerts: AlertGate,
    projects: Mutex<HashMap<String, ProjectState>>,
    /// The first summary of a project counts work done since the daemon started (not since its first tick).
    started: i64,
}

impl OwnerMail {
    pub fn new(ports: Ports) -> Arc<OwnerMail> {
        let started = (ports.now)();
        Arc::new(OwnerMail { ports, alerts: AlertGate::default(), projects: Mutex::new(HashMap::new()), started })
    }

    fn alerts_on(&self) -> bool {
        !matches!(self.ports.settings.app(ALERTS_SETTING), Some(serde_json::Value::Bool(false)))
            && !matches!(self.ports.settings.app(ALERTS_SETTING).as_ref().and_then(|v| v.as_str()), Some("off" | "false"))
    }

    fn alert_kinds(&self) -> Vec<String> {
        match self.ports.settings.app(ALERT_KINDS_SETTING) {
            Some(serde_json::Value::Array(a)) => a.iter().filter_map(|v| v.as_str().map(|s| s.trim().to_string())).filter(|s| !s.is_empty()).collect(),
            Some(serde_json::Value::String(s)) => s.split(',').map(|x| x.trim().to_string()).filter(|s| !s.is_empty()).collect(),
            _ => DEFAULT_ALERT_KINDS.iter().map(|s| s.to_string()).collect(),
        }
    }

    pub fn tick_secs(&self) -> u64 {
        self.ports.settings.app(TICK_SETTING).and_then(|v| v.as_u64().or_else(|| v.as_str().and_then(|s| s.trim().parse().ok()))).filter(|n| *n > 0).unwrap_or(DEFAULT_TICK_SECS)
    }

    /// The one owner mail path. True when the mail was accepted.
    fn mail_owner(&self, subject: &str, body: &str) -> bool {
        let m = MailSendRequest { from: SYSTEM.into(), to: infantry_mail::mail::unit::OWNER.into(), subject: subject.into(), body: body.into(), ..Default::default() };
        match self.ports.mail.send(&m, true) {
            infantry_mail::mail::mailbox::MailResult::Sent(_) => true,
            infantry_mail::mail::mailbox::MailResult::Refused(why) => {
                eprintln!("[infantry] owner mail '{subject}' refused: {why}");
                false
            }
        }
    }

    /// An alert: mailed once per cause until [`AlertGate::clear`].
    pub fn alert(&self, cause: &str, subject: &str, body: &str) -> bool {
        if !self.alerts_on() || !self.alerts.fire(cause, (self.ports.now)()) {
            return false;
        }
        if !self.mail_owner(&format!("ALERT: {subject}"), body) {
            self.alerts.clear(cause);
            return false;
        }
        true
    }

    /// A bus event: `session.stalled` -> stuck alert; `mail.notice` of an alert kind -> alert.
    pub fn on_event(&self, name: &str, p: &serde_json::Value) {
        let s = |k: &str| p.get(k).and_then(|v| v.as_str()).unwrap_or("").to_string();
        match name {
            super::watch::SESSION_STALLED => {
                let sid = s("sessionId");
                if sid.is_empty() {
                    return;
                }
                let ticket = s("ticketId");
                let on = if ticket.is_empty() { String::new() } else { format!(" on ticket {ticket}") };
                self.alert(&format!("stuck:{sid}"), &format!("worker {sid} is {}{on}", s("reason")), &format!("{}\n\nThis alert is sent once; it re-arms when the worker shows output again or ends.", s("detail")));
            }
            MAIL_NOTICE => {
                let kind = s("kind");
                if !notice_alerts(&self.alert_kinds(), &kind, &s("to")) {
                    return;
                }
                let key = s("key");
                self.alert(&format!("notice:{kind}:{key}"), &s("subject"), &s("body"));
            }
            _ => {}
        }
    }

    /// One tick: clear alerts whose cause is gone, check the disk, send due summaries.
    pub fn tick(&self) {
        let now = (self.ports.now)();
        // A stuck worker clears when it shows output after the alert, or ends.
        for (cause, at) in self.alerts.open_with("stuck:") {
            let sid = &cause["stuck:".len()..];
            match (self.ports.session_seen)(sid) {
                Some((ended, last)) if !ended && last <= at => {}
                _ => self.alerts.clear(&cause),
            }
        }
        match (self.ports.disk)() {
            Some((free, min)) if free < min => {
                self.alert("disk", &format!("low disk: {free} MB free"), &format!("Free disk is {free} MB, under {min} MB (setting {}). Delete build output (target folders).", crate::health::MIN_DISK_SETTING));
            }
            Some(_) => self.alerts.clear("disk"),
            None => {}
        }
        self.summaries(now);
    }

    fn summaries(&self, now: i64) {
        let mode = summary_mode(self.ports.settings.app(SUMMARY_SETTING).as_ref());
        let workers = (self.ports.workers)();
        let tickets = (self.ports.tickets)();
        let mut live_by_project: HashMap<String, HashSet<String>> = HashMap::new();
        for w in &workers {
            live_by_project.entry(w.project_id.clone()).or_default().insert(w.ticket_id.clone());
        }
        let mut projects: HashSet<String> = tickets.iter().filter_map(|t| t.project_id.clone()).collect();
        projects.extend(live_by_project.keys().cloned());
        let minute = (self.ports.minute_of_day)();
        let mut state = self.projects.lock().unwrap();
        for p in projects {
            let live = live_by_project.remove(&p).unwrap_or_default();
            let busy = !live.is_empty();
            let st = state.entry(p.clone()).or_insert_with(|| ProjectState { last_at: self.started, ..Default::default() });
            let due = summary_due(&mode, st.busy, busy, minute, now, st.last_timed);
            st.busy = busy;
            if !due {
                continue;
            }
            let mine: Vec<Ticket> = tickets.iter().filter(|t| t.project_id.as_deref() == Some(p.as_str())).cloned().collect();
            let dc = self.ports.done_comment.clone();
            // Nothing to say: the timed slot stays open, so later work that day is still reported.
            let Some(s) = build_summary(&p, &mine, st.last_at, &|id| dc(id), &live) else { continue };
            if let SummaryMode::At(_) = mode {
                st.last_timed = now;
            }
            if !should_send(&s, st.last_fingerprint.as_deref()) {
                continue;
            }
            if self.mail_owner(&s.subject, &s.body) {
                st.last_at = now;
                st.last_fingerprint = Some(s.fingerprint);
            }
        }
    }
}

/// Starts the loop: bus events for alerts, a tick for the summary, the disk and clearing.
pub fn spawn(om: Arc<OwnerMail>, bus: &EventBus) {
    {
        let mut rx = bus.subscribe_events();
        let om = om.clone();
        tokio::spawn(async move {
            loop {
                match rx.recv().await {
                    Ok(e) if e.name == super::watch::SESSION_STALLED || e.name == MAIL_NOTICE => {
                        let om = om.clone();
                        let _ = tokio::task::spawn_blocking(move || om.on_event(&e.name, &e.payload)).await;
                    }
                    Ok(_) | Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(_) => break,
                }
            }
        });
    }
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(om.tick_secs())).await;
            let om2 = om.clone();
            let _ = tokio::task::spawn_blocking(move || om2.tick()).await;
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn t(id: &str, st: &str, updated: i64) -> Ticket {
        serde_json::from_value(serde_json::json!({ "id": id, "title": format!("Title {id}"), "description": "", "status": st, "position": 0, "createdAt": 0, "updatedAt": updated, "projectId": "p" })).unwrap()
    }

    #[test]
    fn the_summary_lists_done_since_last_running_and_blocked_with_ids() {
        let mut b = t("t4", status::PENDING, 5);
        b.hold_reason = Some("waits on t1\nmore".into());
        let tickets = vec![t("t1", status::DONE, 50), t("t0", status::DONE, 5), t("t2", status::IN_PROGRESS, 40), t("t3", status::IN_PROGRESS, 40), b, t("t5", status::TODO, 60)];
        let live: HashSet<String> = ["t2".to_string()].into();
        let s = build_summary("p", &tickets, 10, &|id| (id == "t1").then(|| "Fixed the thing\n\nFiles read: x".to_string()), &live).unwrap();
        assert_eq!(s.subject, "Work summary (p): 1 done, 1 running, 2 blocked");
        assert!(s.body.contains("Done (1):\n- t1 Title t1: Fixed the thing"), "{}", s.body);
        assert!(!s.body.contains("t0"), "done before the last summary is not new");
        assert!(s.body.contains("Still running (1):\n- t2 Title t2"));
        assert!(s.body.contains("t3 Title t3 (in progress, no worker running)"));
        assert!(s.body.contains("t4 Title t4 (blocked: waits on t1)"));
        assert!(!s.body.contains("t5"), "todo is not reported");
    }

    #[test]
    fn an_empty_board_says_nothing_and_an_unchanged_summary_is_folded() {
        assert!(build_summary("p", &[t("a", status::TODO, 1)], 0, &|_| None, &HashSet::new()).is_none());
        let tickets = vec![t("a", status::PENDING, 1)];
        let s = build_summary("p", &tickets, 0, &|_| None, &HashSet::new()).unwrap();
        assert!(should_send(&s, None));
        assert!(!should_send(&s, Some(&s.fingerprint)), "same blocked list, nothing done: folded");
        let d = build_summary("p", &[t("b", status::DONE, 9)], 0, &|_| None, &HashSet::new()).unwrap();
        assert!(should_send(&d, Some(&d.fingerprint)), "something new done is always sent");
    }

    #[test]
    fn a_long_section_is_cut_and_counted() {
        let tickets: Vec<Ticket> = (0..20).map(|i| t(&format!("d{i:02}"), status::DONE, 100 + i)).collect();
        let s = build_summary("p", &tickets, 0, &|_| None, &HashSet::new()).unwrap();
        assert!(s.body.contains(&format!("... and {} more", 20 - SUMMARY_SECTION_LINES)));
    }

    #[test]
    fn the_summary_setting_reads_off_idle_and_a_time() {
        use serde_json::json;
        assert_eq!(summary_mode(None), SummaryMode::Idle);
        assert_eq!(summary_mode(Some(&json!("off"))), SummaryMode::Off);
        assert_eq!(summary_mode(Some(&json!(false))), SummaryMode::Off);
        assert_eq!(summary_mode(Some(&json!("18:30"))), SummaryMode::At(18 * 60 + 30));
        assert_eq!(summary_mode(Some(&json!("25:00"))), SummaryMode::Idle);
    }

    #[test]
    fn only_alert_kinds_not_already_to_the_owner_become_alerts() {
        let k = vec!["build.failed".to_string()];
        assert!(notice_alerts(&k, "build.failed", "pm"));
        assert!(notice_alerts(&k, "BUILD.FAILED", "session-1"));
        assert!(!notice_alerts(&k, "build.success", "pm"), "a green build is no alert");
        assert!(!notice_alerts(&k, "build.failed", "owner"), "already the owner's mail");
        assert!(!notice_alerts(&k, "build.failed", "owner@project-1"));
    }

    #[test]
    fn the_summary_is_due_when_workers_go_from_busy_to_none_or_at_the_set_time() {
        let idle = SummaryMode::Idle;
        assert!(summary_due(&idle, true, false, 0, 0, 0), "busy -> none");
        assert!(!summary_due(&idle, true, true, 0, 0, 0), "still busy");
        assert!(!summary_due(&idle, false, false, 0, 0, 0), "never busy: no summary");
        assert!(!summary_due(&idle, false, true, 0, 0, 0), "work starting");
        assert!(!summary_due(&SummaryMode::Off, true, false, 0, 0, 0), "off");
        let at = SummaryMode::At(18 * 60);
        assert!(!summary_due(&at, false, false, 17 * 60, TIMED_GAP_MS, 0), "before the time");
        assert!(summary_due(&at, false, false, 18 * 60, TIMED_GAP_MS, 0), "at the time");
        assert!(!summary_due(&at, false, false, 19 * 60, TIMED_GAP_MS, 1), "already sent today");
    }

    #[test]
    fn an_alert_fires_once_per_cause_until_it_clears() {
        let g = AlertGate::default();
        assert!(g.fire("stuck:s1", 1));
        assert!(!g.fire("stuck:s1", 2), "deduplicated while open");
        assert!(g.fire("stuck:s2", 2), "another cause is its own alert");
        assert_eq!(g.open_with("stuck:").len(), 2);
        g.clear("stuck:s1");
        assert!(g.fire("stuck:s1", 3), "re-arms after it clears");
    }
}
