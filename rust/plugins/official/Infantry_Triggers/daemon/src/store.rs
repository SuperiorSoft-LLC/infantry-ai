// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The adopted `triggers`, `trigger_events` and `trigger_webhooks` tables (Kotlin `StorageTriggerStore`, the same
//! columns and semantics): upsert keeps the row's pending mail id, delete drops the events and the secret with the row,
//! an insert prunes a trigger's events to the newest `keep`. The SQL runs on whatever [`Sql`] the plugin is given.
use crate::model::{Action, EventView, TriggerView};
use std::collections::HashMap;

/// Events kept per trigger.
pub const EVENTS_KEPT: i64 = 500;

#[derive(Debug, Clone, PartialEq)]
pub enum V {
    Null,
    Int(i64),
    Text(String),
}

impl From<&str> for V {
    fn from(s: &str) -> V {
        V::Text(s.into())
    }
}
impl From<i64> for V {
    fn from(n: i64) -> V {
        V::Int(n)
    }
}
impl From<bool> for V {
    fn from(b: bool) -> V {
        V::Int(b as i64)
    }
}
impl<T: Into<V>> From<Option<T>> for V {
    fn from(o: Option<T>) -> V {
        o.map(Into::into).unwrap_or(V::Null)
    }
}
impl From<&String> for V {
    fn from(s: &String) -> V {
        V::Text(s.clone())
    }
}

pub type Row = HashMap<String, V>;

/// SQL and a key/value space on the plugin's storage.
pub trait Sql {
    fn exec(&self, sql: &str, params: &[V]) -> Result<u64, String>;
    fn query(&self, sql: &str, params: &[V]) -> Result<Vec<Row>, String>;
    fn kv_get(&self, key: &str) -> Option<String>;
    fn kv_set(&self, key: &str, value: Option<&str>);
}

fn text(r: &Row, k: &str) -> Option<String> {
    match r.get(k) {
        Some(V::Text(s)) => Some(s.clone()),
        Some(V::Int(n)) => Some(n.to_string()),
        _ => None,
    }
}
fn int(r: &Row, k: &str) -> Option<i64> {
    match r.get(k) {
        Some(V::Int(n)) => Some(*n),
        Some(V::Text(s)) => s.parse().ok(),
        _ => None,
    }
}

const CREATE: [&str; 3] = [
    "CREATE TABLE IF NOT EXISTS triggers (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL, action_kind TEXT NOT NULL, \
     action_agent_id TEXT, enabled INTEGER NOT NULL, state TEXT NOT NULL CHECK (state IN ('armed', 'running', 'tripped', 'disabled')), \
     coalesce_ms INTEGER NOT NULL, ceiling_runs INTEGER NOT NULL, ceiling_window_ms INTEGER NOT NULL, created_at INTEGER NOT NULL, \
     tripped_at INTEGER, tripped_reason TEXT, interval_ms INTEGER, action_prompt TEXT, project_id TEXT, last_fired_at INTEGER, \
     next_fire_at INTEGER, pending_mail_id TEXT)",
    "CREATE TABLE IF NOT EXISTS trigger_events (event_id TEXT NOT NULL PRIMARY KEY, trigger_id TEXT NOT NULL, received_at INTEGER NOT NULL, \
     source TEXT NOT NULL, disposition TEXT NOT NULL, delivery_id TEXT, digest TEXT)",
    "CREATE TABLE IF NOT EXISTS trigger_webhooks (trigger_id TEXT NOT NULL PRIMARY KEY, secret TEXT NOT NULL)",
];
// The (trigger_id, received_at) index comes with the daemon's schema; plugin storage refuses CREATE INDEX (it reindexes).
/// TRIG-AT-1: additive nullable columns (the Kotlin module's own storage version 1).
const TRIG_AT_1: [(&str, &str); 4] = [("fire_at", "INTEGER"), ("delete_after_fire", "INTEGER"), ("owner", "TEXT"), ("ref", "TEXT")];

pub struct Store<S: Sql> {
    pub sql: S,
}

impl<S: Sql> Store<S> {
    /// Opens the tables: creates them when absent (a daemon whose schema lacks them), adds the TRIG-AT-1 columns.
    pub fn open(sql: S) -> Result<Store<S>, String> {
        for s in CREATE {
            sql.exec(s, &[])?;
        }
        for (col, ty) in TRIG_AT_1 {
            if sql.query(&format!("SELECT {col} FROM triggers LIMIT 0"), &[]).is_err() {
                sql.exec(&format!("ALTER TABLE triggers ADD COLUMN {col} {ty}"), &[])?;
            }
        }
        Ok(Store { sql })
    }

    pub fn upsert(&self, t: &TriggerView, interval_ms: Option<i64>) -> Result<(), String> {
        let pending = self.pending_mail(&t.id);
        self.sql.exec(
            "INSERT OR REPLACE INTO triggers(id, name, kind, action_kind, action_agent_id, enabled, state, coalesce_ms, ceiling_runs, \
             ceiling_window_ms, created_at, tripped_at, tripped_reason, interval_ms, action_prompt, project_id, last_fired_at, next_fire_at, \
             pending_mail_id, fire_at, delete_after_fire, owner, ref) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            &[
                (&t.id).into(),
                (&t.name).into(),
                (&t.kind).into(),
                (&t.action.kind).into(),
                t.action.agent_id.as_deref().into(),
                t.enabled.into(),
                (&t.state).into(),
                t.coalesce_ms.into(),
                t.ceiling_runs.into(),
                t.ceiling_window_ms.into(),
                t.created_at.into(),
                t.tripped_at.into(),
                t.tripped_reason.as_deref().into(),
                interval_ms.into(),
                t.action.prompt.as_deref().into(),
                t.project_id.as_deref().into(),
                t.last_fired_at.into(),
                t.next_fire_at.into(),
                pending.as_deref().into(),
                t.fire_at.into(),
                t.delete_after_fire.into(),
                t.owner.as_deref().into(),
                t.reference.as_deref().into(),
            ],
        )?;
        Ok(())
    }

    fn view(r: &Row) -> TriggerView {
        TriggerView {
            id: text(r, "id").unwrap_or_default(),
            name: text(r, "name").unwrap_or_default(),
            kind: text(r, "kind").unwrap_or_default(),
            action: Action { kind: text(r, "action_kind").unwrap_or_default(), agent_id: text(r, "action_agent_id"), prompt: text(r, "action_prompt") },
            enabled: int(r, "enabled").unwrap_or(0) != 0,
            state: text(r, "state").unwrap_or_default(),
            coalesce_ms: int(r, "coalesce_ms").unwrap_or(0),
            ceiling_runs: int(r, "ceiling_runs").unwrap_or(0),
            ceiling_window_ms: int(r, "ceiling_window_ms").unwrap_or(0),
            created_at: int(r, "created_at").unwrap_or(0),
            tripped_at: int(r, "tripped_at"),
            tripped_reason: text(r, "tripped_reason"),
            interval_ms: int(r, "interval_ms"),
            project_id: text(r, "project_id"),
            last_fired_at: int(r, "last_fired_at"),
            next_fire_at: int(r, "next_fire_at"),
            fire_at: int(r, "fire_at"),
            delete_after_fire: int(r, "delete_after_fire").unwrap_or(0) != 0,
            owner: text(r, "owner"),
            reference: text(r, "ref"),
        }
    }

    pub fn get(&self, id: &str) -> Option<TriggerView> {
        self.sql.query("SELECT * FROM triggers WHERE id = ?", &[id.into()]).ok()?.first().map(Self::view)
    }

    pub fn all(&self) -> Vec<TriggerView> {
        self.sql.query("SELECT * FROM triggers ORDER BY created_at ASC, id ASC", &[]).unwrap_or_default().iter().map(Self::view).collect()
    }

    pub fn intervals(&self) -> Vec<(String, i64)> {
        self.sql
            .query("SELECT id, interval_ms FROM triggers ORDER BY created_at ASC, id ASC", &[])
            .unwrap_or_default()
            .iter()
            .filter_map(|r| Some((text(r, "id")?, int(r, "interval_ms")?)))
            .collect()
    }

    pub fn delete(&self, id: &str) -> Result<bool, String> {
        let existed = self.get(id).is_some();
        self.sql.exec("DELETE FROM trigger_events WHERE trigger_id = ?", &[id.into()])?;
        self.sql.exec("DELETE FROM trigger_webhooks WHERE trigger_id = ?", &[id.into()])?;
        self.sql.exec("DELETE FROM triggers WHERE id = ?", &[id.into()])?;
        self.sql.kv_set(&pending_thread_key(id), None);
        Ok(existed)
    }

    pub fn insert_event(&self, e: &EventView) -> Result<(), String> {
        self.sql.exec(
            "INSERT OR REPLACE INTO trigger_events(event_id, trigger_id, received_at, source, disposition, delivery_id, digest) VALUES (?, ?, ?, ?, ?, ?, ?)",
            &[(&e.event_id).into(), (&e.trigger_id).into(), e.received_at.into(), (&e.source).into(), (&e.disposition).into(), e.delivery_id.as_deref().into(), e.digest.as_deref().into()],
        )?;
        self.sql.exec(
            "DELETE FROM trigger_events WHERE trigger_id = ? AND event_id NOT IN (SELECT event_id FROM trigger_events WHERE trigger_id = ? ORDER BY received_at DESC, event_id DESC LIMIT ?)",
            &[(&e.trigger_id).into(), (&e.trigger_id).into(), EVENTS_KEPT.into()],
        )?;
        Ok(())
    }

    pub fn events(&self, trigger_id: &str) -> Vec<EventView> {
        self.sql
            .query("SELECT * FROM trigger_events WHERE trigger_id = ? ORDER BY received_at ASC, event_id ASC", &[trigger_id.into()])
            .unwrap_or_default()
            .iter()
            .map(|r| EventView {
                event_id: text(r, "event_id").unwrap_or_default(),
                trigger_id: text(r, "trigger_id").unwrap_or_default(),
                received_at: int(r, "received_at").unwrap_or(0),
                source: text(r, "source").unwrap_or_default(),
                disposition: text(r, "disposition").unwrap_or_default(),
                delivery_id: text(r, "delivery_id"),
                digest: text(r, "digest"),
            })
            .collect()
    }

    pub fn set_webhook_secret(&self, id: &str, secret: &str) -> Result<(), String> {
        self.sql.exec("INSERT OR REPLACE INTO trigger_webhooks(trigger_id, secret) VALUES (?, ?)", &[id.into(), secret.into()]).map(|_| ())
    }

    pub fn webhook_secret(&self, id: &str) -> Option<String> {
        self.sql.query("SELECT secret FROM trigger_webhooks WHERE trigger_id = ?", &[id.into()]).ok()?.first().and_then(|r| text(r, "secret"))
    }

    pub fn delete_webhook_secret(&self, id: &str) -> Result<(), String> {
        self.sql.exec("DELETE FROM trigger_webhooks WHERE trigger_id = ?", &[id.into()]).map(|_| ())
    }

    pub fn pending_mail(&self, id: &str) -> Option<String> {
        self.sql.query("SELECT pending_mail_id FROM triggers WHERE id = ?", &[id.into()]).ok()?.first().and_then(|r| text(r, "pending_mail_id"))
    }

    /// The nudge mail a trigger waits on, and (kept beside it) its thread, which is how its delivery is read back.
    pub fn set_pending_mail(&self, id: &str, mail: Option<(&str, &str)>) -> Result<(), String> {
        self.sql.exec("UPDATE triggers SET pending_mail_id = ? WHERE id = ?", &[mail.map(|m| m.0).into(), id.into()])?;
        self.sql.kv_set(&pending_thread_key(id), mail.map(|m| m.1));
        Ok(())
    }

    pub fn pending_thread(&self, id: &str) -> Option<String> {
        self.sql.kv_get(&pending_thread_key(id))
    }
}

fn pending_thread_key(id: &str) -> String {
    format!("pending-thread/{id}")
}
