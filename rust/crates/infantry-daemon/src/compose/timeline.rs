// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! SCRIPT-VIS-1: the automation timeline in a session's chat. The script run log's records and each session's stats item
//! (turns saved, wakes by reason) go out as `timeline_item`; `timeline_list` `"<sessionId>"?` answers `timeline_items`;
//! every turn a session starts is recorded as a wake with its reason (Kotlin's `session.woken`).
use crate::sessions::SessionObserver;
use infantry_core::bus::EventBus;
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_protocol::frames as f;
use infantry_protocol::session::SessionRecord;
use infantry_scripting::runlog::ScriptRunLog;
use serde_json::Value;
use std::sync::Arc;

/// The most items one `timeline_list` answers.
pub const LIST_MAX: usize = 1000;

/// Pushes the run log's records and stats items to every client.
pub fn wire(run_log: &ScriptRunLog, bus: &EventBus) {
    let b = bus.clone();
    run_log.set_on_record(Arc::new(move |r| {
        if let Some(j) = r.timeline_json() {
            let _ = b.publish(Envelope::new(f::TIMELINE_ITEM, j.to_string()));
        }
    }));
    let b = bus.clone();
    run_log.set_on_stats(Arc::new(move |item: &Value| {
        b.publish(Envelope::new(f::TIMELINE_ITEM, item.to_string()));
    }));
}

pub struct TimelineUnit {
    pub run_log: Arc<ScriptRunLog>,
}

impl FrameUnit for TimelineUnit {
    fn name(&self) -> &'static str {
        "timeline"
    }

    fn frames(&self) -> &'static [&'static str] {
        &[f::TIMELINE_LIST]
    }

    fn handle(&self, env: &Envelope, _ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let wanted = env.data.trim().trim_matches('"').to_string();
        let items: Vec<Value> = self
            .run_log
            .timeline_items(LIST_MAX)
            .into_iter()
            .filter(|i| wanted.is_empty() || i["sessionId"].as_str() == Some(wanted.as_str()))
            .collect();
        let mut out = Envelope::new(f::TIMELINE_ITEMS, Value::Array(items).to_string());
        out.session_id = env.session_id.clone();
        out.request_id = env.request_id.clone();
        Ok(vec![out])
    }
}

/// Why a turn woke its session: `mail` for a mail delivery, `agent` for a worker / spawned session, else `owner`.
pub fn wake_reason(rec: &SessionRecord, text: &str) -> &'static str {
    let t = text.trim_start();
    if t.starts_with("NEW MAIL") || t.starts_with("[COMMS") {
        "mail"
    } else if rec.ticket_id.is_some() || rec.spawner_session_id.is_some() {
        "agent"
    } else {
        "owner"
    }
}

pub struct WakeObserver {
    pub run_log: Arc<ScriptRunLog>,
}

impl SessionObserver for WakeObserver {
    fn on_turn(&self, rec: &SessionRecord, text: &str) {
        self.run_log.wake(&rec.id, wake_reason(rec, text), None, rec.project_id.as_deref());
    }
}
