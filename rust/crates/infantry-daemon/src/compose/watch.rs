// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-SESS-2: the watchdog's ports (mail waiting-on, attention items, nudges) and the done-worker reaper's trigger
//! (a `ticket` push in the done column, for a project with `autoRemoveDoneWorkers` on).

use crate::sessions::{Daemon, Pending, WatchPorts};
use infantry_core::bus::EventBus;
use infantry_mail::mail::router::MailRouter;
use infantry_mail::mail::message::{MailSendRequest, SYSTEM};
use infantry_mail::notify::prompts::PendingPrompts;
use infantry_mail::notify::attention::{AttentionRaise, AttentionStore};
use infantry_orchestrator_api::dto::{status, Ticket};
use infantry_projects::Projects;
use infantry_protocol::session::SessionRecord;
use std::sync::Arc;

pub struct Ports {
    pub daemon: Arc<Daemon>,
    pub mail: Arc<MailRouter>,
    pub attention: Arc<AttentionStore>,
    pub settings: super::settings::SettingsUnit,
    pub prompts: Arc<PendingPrompts>,
    pub bus: EventBus,
    /// A ticket's status by id; the composition root wires it. None = no ticket counts as open work.
    pub ticket_status: Option<Arc<dyn Fn(&str) -> Option<String> + Send + Sync>>,
}

/// The generic stall event's name: a session is waiting on a prompt nobody answered, or printed nothing for too long.
pub const SESSION_STALLED: &str = "session.stalled";

/// `watchdog_blocked` -> `blocked`, `watchdog_quiet` -> `quiet`, `watchdog_stalled` -> `stalled`.
fn reason_of(outcome: &str) -> &str {
    outcome.strip_prefix("watchdog_").unwrap_or(outcome)
}

/// The payload of `session.stalled`: it names the session and its ticket, never a board or a feature.
pub fn stalled_payload(rec: &SessionRecord, outcome: &str, detail: &str) -> serde_json::Value {
    serde_json::json!({ "sessionId": rec.id, "ticketId": rec.ticket_id, "projectId": rec.project_id, "leadSessionId": rec.spawner_session_id, "reason": reason_of(outcome), "detail": detail })
}

impl WatchPorts for Ports {
    /// The oldest mail someone is waiting on this session to answer.
    fn pending(&self, rec: &SessionRecord, _now: i64) -> Option<Pending> {
        self.mail
            .waiting()
            .into_iter()
            .filter(|w| w.waiting_on == rec.id)
            .min_by_key(|w| w.since)
            .map(|w| Pending { key: w.message_id.clone(), since: w.since, summary: format!("mail '{}' waits for your reply", w.subject) })
    }

    /// The oldest open approval or question card of this session.
    fn blocked(&self, rec: &SessionRecord, _now: i64) -> Option<Pending> {
        self.prompts
            .snapshot()
            .into_iter()
            .filter(|p| p.session_id == rec.id)
            .min_by_key(|p| p.created_at)
            .map(|p| Pending { key: p.prompt_id.clone(), since: p.created_at, summary: format!("waits on {} '{}'", p.kind, p.tool.clone().or(p.question.clone()).unwrap_or_default()) })
    }

    fn blocked_limit(&self) -> Option<i64> {
        self.settings.app("session.blockedMinutes").and_then(|v| v.as_i64().or_else(|| v.as_str().and_then(|x| x.trim().parse().ok()))).filter(|n| *n >= 0).map(|n| n * 60_000)
    }

    /// A worker's ticket is open while it is in progress.
    fn work_open(&self, rec: &SessionRecord) -> bool {
        let (Some(status_of), Some(t)) = (self.ticket_status.as_ref(), rec.ticket_id.as_deref()) else { return false };
        status_of(t).as_deref() == Some(status::IN_PROGRESS)
    }

    fn idle_work_limit(&self) -> Option<i64> {
        self.settings.app("session.idleWorkMinutes").and_then(|v| v.as_i64().or_else(|| v.as_str().and_then(|x| x.trim().parse().ok()))).filter(|n| *n >= 0).map(|n| n * 60_000)
    }

    fn limits(&self) -> (Option<i64>, Option<i64>) {
        let mins = |k: &str| self.settings.app(k).and_then(|v| v.as_i64().or_else(|| v.as_str().and_then(|x| x.trim().parse().ok()))).filter(|n| *n >= 0).map(|n| n * 60_000);
        (mins("session.stalledMinutes"), mins("session.hungKillMinutes"))
    }

    fn raise(&self, rec: &SessionRecord, outcome: &str, detail: &str) {
        let r = AttentionRaise {
            kind: "stalled".into(),
            ticket_id: rec.ticket_id.clone(),
            session_id: Some(rec.id.clone()),
            outcome: outcome.into(),
            detail: detail.into(),
            ..Default::default()
        };
        if let Err(e) = self.attention.raise(&r) {
            eprintln!("[infantry] watchdog could not raise attention for {}: {e}", rec.id);
        }
        self.bus.emit(SESSION_STALLED, stalled_payload(rec, outcome, detail));
        // The lead (the session that spawned this worker) is told by mail; no lead = the attention item is the notice.
        if let Some(lead) = rec.spawner_session_id.as_deref().filter(|l| *l != rec.id) {
            let ticket = rec.ticket_id.as_deref().map(|t| format!(" (ticket {t})")).unwrap_or_default();
            mail_lead(&self.mail, lead, &format!("Worker {} is {}{ticket}", label_of(rec), reason_of(outcome)), detail);
        }
    }

    fn nudge(&self, rec: &SessionRecord, body: &str) -> bool {
        infantry_mail::mail::watch_meter::WATCH_METER.stall();
        self.daemon.deliver(&rec.id, &format!("[infantry watchdog] {body}")).is_ok()
    }
}

/// The one path that tells a lead about a worker that is down (a stall, a failed run): a SYSTEM mail, delivered at once.
/// True when the mail was accepted.
pub fn mail_lead(mail: &MailRouter, lead: &str, subject: &str, body: &str) -> bool {
    let m = MailSendRequest { from: SYSTEM.into(), to: lead.into(), subject: subject.into(), body: body.into(), ..Default::default() };
    matches!(mail.send(&m, true), infantry_mail::mail::mailbox::MailResult::Sent(_))
}

/// A worker that ended badly, as the host tells it (no board type: the caller maps its own).
#[derive(Debug, Clone, Copy)]
pub struct WorkerDown<'a> {
    pub ticket_id: &'a str,
    pub session_id: &'a str,
    /// The session to mail; None = the owner started it and only the attention item tells.
    pub lead: Option<&'a str>,
    pub outcome: &'a str,
    pub subject: &'a str,
    pub detail: &'a str,
}

/// Everything a worker's bad end tells the host, through the paths the stall watchdog uses: an attention item for the
/// owner (kind `session_failed`) and a mail to the lead. Returns true when the lead was mailed.
pub fn worker_down(attention: &AttentionStore, mail: &MailRouter, f: &WorkerDown<'_>) -> bool {
    let r = AttentionRaise {
        kind: "session_failed".into(),
        ticket_id: Some(f.ticket_id.to_string()),
        session_id: Some(f.session_id.to_string()),
        outcome: f.outcome.to_string(),
        detail: f.detail.to_string(),
        ..Default::default()
    };
    if let Err(e) = attention.raise(&r) {
        eprintln!("[infantry] could not raise attention for the failed worker {}: {e}", f.session_id);
    }
    f.lead.is_some_and(|lead| mail_lead(mail, lead, f.subject, f.detail))
}

fn label_of(rec: &SessionRecord) -> String {
    rec.agent_id.clone().unwrap_or_else(|| rec.id.chars().take(8).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The real composition (prompt store, bus, mail, attention) with a stub worker: an approval card nobody answers
    /// raises `session.stalled`, an attention item, and a mail to the lead once the limit passes.
    #[tokio::test(flavor = "multi_thread")]
    async fn a_worker_blocked_on_an_unanswered_approval_card_alerts_the_lead_and_the_owner() {
        use crate::sessions::{Config, NewOpts, WatchConfig};
        let dir = std::env::temp_dir().join(format!("infantry-pipe-stall-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let daemon = Daemon::new(Arc::new(crate::harness::stub::Stub { start_ms: 5_000, line_ms: 25 }), Config::new(dir.clone(), dir.clone())).unwrap();
        let c = crate::compose::build(&daemon, &dir).unwrap();
        let lead = daemon.launch(NewOpts::default()).unwrap();
        let worker = daemon.launch(NewOpts { prompt: Some("go".into()), spawner: Some(lead.clone()), ticket_id: Some("t1".into()), ..Default::default() }).unwrap();
        let mut events = c.bus.subscribe_events();
        let attention = Arc::new(AttentionStore::new(c.db.clone(), Arc::new(crate::sessions::now_ms)).unwrap());
        let ports = Ports { daemon: daemon.clone(), mail: c.mail.clone(), attention: attention.clone(), settings: super::super::settings::SettingsUnit { db: c.db.clone() }, prompts: c.prompts.clone(), bus: c.bus.clone(), ticket_status: None };
        let (p, w) = (c.prompts.clone(), worker.clone());
        tokio::spawn(async move { p.request_approval(&w, "Bash", "rm -rf x", None).await });
        tokio::time::sleep(std::time::Duration::from_millis(200)).await;
        let cfg = WatchConfig::default();
        let d = daemon.clone();
        let early = crate::sessions::now_ms() + cfg.blocked_ms - 60_000;
        tokio::task::spawn_blocking(move || d.watch_tick(&ports, &cfg, early)).await.unwrap();
        assert!(attention.list(true).is_empty(), "under the limit nothing is raised");
        let ports = Ports { daemon: daemon.clone(), mail: c.mail.clone(), attention: attention.clone(), settings: super::super::settings::SettingsUnit { db: c.db.clone() }, prompts: c.prompts.clone(), bus: c.bus.clone(), ticket_status: None };
        let d = daemon.clone();
        let late = crate::sessions::now_ms() + cfg.blocked_ms + 1_000;
        tokio::task::spawn_blocking(move || d.watch_tick(&ports, &cfg, late)).await.unwrap();
        assert_eq!(daemon.record(&worker).unwrap().watch.unwrap().state, "BLOCKED");
        let items = attention.list(true);
        assert!(items.iter().any(|i| i.kind == "stalled" && i.outcome == "watchdog_blocked" && i.session_id.as_deref() == Some(worker.as_str())), "{items:?}");
        let ev = std::iter::from_fn(|| events.try_recv().ok()).find(|e| e.name == SESSION_STALLED).expect("session.stalled");
        assert_eq!((ev.name.as_str(), ev.payload["reason"].as_str(), ev.payload["leadSessionId"].as_str()), (SESSION_STALLED, Some("blocked"), Some(lead.as_str())));
        let mail = c.mail.mailbox().inbox(&lead);
        assert!(mail.iter().any(|m| m.subject.contains("blocked") && m.body.contains("Bash")), "{mail:?}");
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// PIPE-RESTART-1: a worker that dies is told through the stall watchdog's own paths: a `session_failed` attention
    /// item for the owner and a SYSTEM mail to the lead; no lead means the attention item alone.
    #[tokio::test(flavor = "multi_thread")]
    async fn a_dead_worker_raises_attention_and_mails_its_lead() {
        use crate::sessions::{Config, NewOpts};
        let dir = std::env::temp_dir().join(format!("infantry-pipe-down-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let daemon = Daemon::new(Arc::new(crate::harness::stub::Stub { start_ms: 5_000, line_ms: 25 }), Config::new(dir.clone(), dir.clone())).unwrap();
        let c = crate::compose::build(&daemon, &dir).unwrap();
        let lead = daemon.launch(NewOpts::default()).unwrap();
        let attention = Arc::new(AttentionStore::new(c.db.clone(), Arc::new(crate::sessions::now_ms)).unwrap());
        let f = |lead: Option<&'static str>| WorkerDown { ticket_id: "t1", session_id: "w1", lead, outcome: "FAILED", subject: "worker of t1 is down", detail: "Recover it: infantry ticket restart t1" };
        let lead_ref: &'static str = Box::leak(lead.clone().into_boxed_str());
        assert!(worker_down(&attention, &c.mail, &f(Some(lead_ref))), "the lead was mailed");
        let mail = c.mail.mailbox().inbox(&lead);
        assert!(mail.iter().any(|m| m.subject == "worker of t1 is down" && m.body.contains("infantry ticket restart t1")), "{mail:?}");
        let items = attention.list(true);
        assert!(items.iter().any(|i| i.kind == "session_failed" && i.ticket_id.as_deref() == Some("t1") && i.session_id.as_deref() == Some("w1") && i.outcome == "FAILED"), "{items:?}");
        assert!(!worker_down(&attention, &c.mail, &f(None)), "no lead = not mailed, but the owner still has the attention item");
        assert_eq!(attention.list(true).len(), 2);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_stall_event_names_the_session_and_its_lead_only() {
        let rec: SessionRecord = serde_json::from_value(serde_json::json!({"id": "w1", "provider": "stub", "workingDir": ".", "spawnedAt": 0, "lastHeartbeat": 0, "state": "BUSY", "ticketId": "t9", "spawnerSessionId": "pm1"})).unwrap();
        let p = stalled_payload(&rec, "watchdog_blocked", "waits");
        assert_eq!((p["sessionId"].as_str(), p["ticketId"].as_str(), p["leadSessionId"].as_str(), p["reason"].as_str()), (Some("w1"), Some("t9"), Some("pm1"), Some("blocked")));
    }
}

/// Setting `board.autoRemoveDoneLeadWorkers` (default ON): a done ticket that a lead session started has its worker
/// removed even when the project did not turn `autoRemoveDoneWorkers` on (PIPE-AUTOCLEAN).
pub const AUTO_REMOVE_LEAD_WORKERS: &str = "board.autoRemoveDoneLeadWorkers";

/// Listens for `ticket` pushes: a ticket in the done column reaps its workers when its project says so.
pub fn spawn_reaper(daemon: Arc<Daemon>, projects: Arc<Projects>, board: Arc<infantry_board::Board>, settings: super::settings::SettingsUnit, bus: &EventBus) {
    let mut rx = bus.subscribe();
    tokio::spawn(async move {
        loop {
            let env = match rx.recv().await {
                Ok(e) => e,
                Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                Err(_) => break,
            };
            if env.r#type != infantry_protocol::frames::TICKET {
                continue;
            }
            let Ok(t) = serde_json::from_str::<Ticket>(&env.data) else { continue };
            if t.status != status::DONE {
                continue;
            }
            let project_on = t.project_id.as_deref().and_then(|p| projects.store.get(p).ok()).is_some_and(|p| p.auto_remove_done_workers);
            let lead_on = !matches!(settings.app(AUTO_REMOVE_LEAD_WORKERS), Some(serde_json::Value::Bool(false)))
                && !matches!(settings.app(AUTO_REMOVE_LEAD_WORKERS), Some(serde_json::Value::String(ref s)) if matches!(s.trim().to_lowercase().as_str(), "false" | "off" | "0" | "no"))
                && board.started_by_of(&t.id).is_some_and(|s| !s.trim().is_empty() && s != "owner");
            let on = project_on || lead_on;
            if !on {
                continue;
            }
            let d = daemon.clone();
            let _ = tokio::task::spawn_blocking(move || d.reap_done(&t.id, t.session_id.as_deref())).await;
        }
    });
}
