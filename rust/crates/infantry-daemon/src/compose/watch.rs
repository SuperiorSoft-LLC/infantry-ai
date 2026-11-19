// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-SESS-2: the watchdog's ports (mail waiting-on, attention items, nudges) and the done-worker reaper's trigger
//! (a `ticket` push in the done column, for a project with `autoRemoveDoneWorkers` on).

use crate::sessions::{Daemon, Pending, WatchPorts};
use infantry_core::bus::EventBus;
use infantry_mail::mail::router::MailRouter;
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
    }

    fn nudge(&self, rec: &SessionRecord, body: &str) -> bool {
        self.daemon.deliver(&rec.id, &format!("[infantry watchdog] {body}")).is_ok()
    }
}

/// Listens for `ticket` pushes: a ticket in the done column reaps its workers when its project says so.
pub fn spawn_reaper(daemon: Arc<Daemon>, projects: Arc<Projects>, bus: &EventBus) {
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
            let on = t.project_id.as_deref().and_then(|p| projects.store.get(p).ok()).is_some_and(|p| p.auto_remove_done_workers);
            if !on {
                continue;
            }
            let d = daemon.clone();
            let _ = tokio::task::spawn_blocking(move || d.reap_done(&t.id, t.session_id.as_deref())).await;
        }
    });
}
