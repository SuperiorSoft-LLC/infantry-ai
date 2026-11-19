// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The attention list: things the owner must look at (a trigger tripped, a deadline passed, a session failed...).
//! Stored in the `attention` table; every change is broadcast as an `attention` frame. The owner acknowledges an
//! item; an item whose session is gone is swept to acknowledged.
use std::sync::{Arc, RwLock};

use infantry_core::db::{Db, DbError};
use rusqlite::{params, OptionalExtension, Row};
use serde::{Deserialize, Serialize};

pub const KINDS: [&str; 11] = [
    "trigger_tripped",
    "deadline",
    "stalled",
    "ceiling",
    "launch_blocked",
    "prerequisite_declined",
    "queue_cleared",
    "unreported",
    "session_failed",
    "session_resumed",
    "runner_lost",
];

const SCHEMA: &str = "CREATE TABLE IF NOT EXISTS attention (id TEXT NOT NULL PRIMARY KEY, created_at INTEGER NOT NULL, kind TEXT NOT NULL, trigger_id TEXT, ticket_id TEXT, \
pod_id TEXT, session_id TEXT, outcome TEXT NOT NULL, detail TEXT NOT NULL, acked_at INTEGER)";

/// One item; the wire JSON writes every key, nulls included.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AttentionItem {
    pub id: String,
    pub at: i64,
    pub kind: String,
    pub trigger_id: Option<String>,
    pub ticket_id: Option<String>,
    pub pod_id: Option<String>,
    pub session_id: Option<String>,
    pub outcome: String,
    pub detail: String,
    pub acked_at: Option<i64>,
}

/// What a raiser gives.
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AttentionRaise {
    pub kind: String,
    #[serde(default)]
    pub trigger_id: Option<String>,
    #[serde(default)]
    pub ticket_id: Option<String>,
    #[serde(default)]
    pub pod_id: Option<String>,
    #[serde(default)]
    pub session_id: Option<String>,
    pub outcome: String,
    pub detail: String,
}

fn item(r: &Row) -> rusqlite::Result<AttentionItem> {
    Ok(AttentionItem {
        id: r.get(0)?,
        at: r.get(1)?,
        kind: r.get(2)?,
        trigger_id: r.get(3)?,
        ticket_id: r.get(4)?,
        pod_id: r.get(5)?,
        session_id: r.get(6)?,
        outcome: r.get(7)?,
        detail: r.get(8)?,
        acked_at: r.get(9)?,
    })
}

const COLS: &str = "id, created_at, kind, trigger_id, ticket_id, pod_id, session_id, outcome, detail, acked_at";

type Broadcast = Arc<dyn Fn(&AttentionItem) + Send + Sync>;

pub struct AttentionStore {
    db: Arc<Db>,
    clock: Arc<dyn Fn() -> i64 + Send + Sync>,
    broadcast: RwLock<Option<Broadcast>>,
}

impl AttentionStore {
    pub fn new(db: Arc<Db>, clock: Arc<dyn Fn() -> i64 + Send + Sync>) -> Result<Self, DbError> {
        db.write(|c| c.execute_batch(SCHEMA))?;
        Ok(AttentionStore { db, clock, broadcast: RwLock::new(None) })
    }

    pub fn set_broadcast(&self, f: impl Fn(&AttentionItem) + Send + Sync + 'static) {
        *self.broadcast.write().unwrap() = Some(Arc::new(f));
    }

    fn changed(&self, i: &AttentionItem) {
        if let Some(b) = self.broadcast.read().unwrap().clone() {
            b(i);
        }
    }

    pub fn raise(&self, r: &AttentionRaise) -> Result<AttentionItem, String> {
        if !KINDS.contains(&r.kind.as_str()) {
            return Err(format!("unknown attention kind '{}' (known: {})", r.kind, KINDS.join(", ")));
        }
        if r.outcome.trim().is_empty() {
            return Err("an attention item needs an outcome".into());
        }
        if r.detail.trim().is_empty() {
            return Err("an attention item needs a detail".into());
        }
        let i = AttentionItem {
            id: uuid::Uuid::new_v4().to_string(),
            at: (self.clock)(),
            kind: r.kind.clone(),
            trigger_id: r.trigger_id.clone(),
            ticket_id: r.ticket_id.clone(),
            pod_id: r.pod_id.clone(),
            session_id: r.session_id.clone(),
            outcome: r.outcome.trim().into(),
            detail: r.detail.trim().into(),
            acked_at: None,
        };
        self.db
            .write(|c| {
                c.execute(
                    &format!("INSERT INTO attention({COLS}) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, NULL)"),
                    params![i.id, i.at, i.kind, i.trigger_id, i.ticket_id, i.pod_id, i.session_id, i.outcome, i.detail],
                )
            })
            .map_err(|e| e.to_string())?;
        self.changed(&i);
        Ok(i)
    }

    /// Newest first; `open_only` hides acknowledged items.
    pub fn list(&self, open_only: bool) -> Vec<AttentionItem> {
        let sql = format!("SELECT {COLS} FROM attention {} ORDER BY created_at DESC, id", if open_only { "WHERE acked_at IS NULL" } else { "" });
        self.db.read(|c| c.prepare(&sql)?.query_map([], item)?.collect()).unwrap_or_default()
    }

    pub fn get(&self, id: &str) -> Option<AttentionItem> {
        self.db.read(|c| c.query_row(&format!("SELECT {COLS} FROM attention WHERE id = ?1"), [id], item).optional()).ok().flatten()
    }

    /// Acknowledges an item; the first acknowledgement's time is kept.
    pub fn acknowledge(&self, id: &str) -> Result<AttentionItem, String> {
        let id = id.trim();
        if id.is_empty() {
            return Err("attention_ack expects an attention id".into());
        }
        let now = (self.clock)();
        self.db.write(|c| c.execute("UPDATE attention SET acked_at = COALESCE(acked_at, ?1) WHERE id = ?2", params![now, id])).map_err(|e| e.to_string())?;
        let i = self.get(id).ok_or_else(|| format!("unknown attention item '{id}'"))?;
        self.changed(&i);
        Ok(i)
    }

    /// Acknowledges open items whose session no longer exists; returns how many.
    pub fn sweep_stale(&self, session_exists: impl Fn(&str) -> bool) -> usize {
        let stale: Vec<String> = self.list(true).into_iter().filter(|i| i.session_id.as_deref().is_some_and(|s| !session_exists(s))).map(|i| i.id).collect();
        stale.iter().filter(|id| self.acknowledge(id).is_ok()).count()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn raise_list_ack_sweep() {
        let dir = tempfile::tempdir().unwrap();
        let db = Arc::new(Db::open(dir.path().join("t.db")).unwrap());
        let s = AttentionStore::new(db, Arc::new(|| 7)).unwrap();
        assert!(s.raise(&AttentionRaise { kind: "nope".into(), outcome: "x".into(), detail: "y".into(), ..Default::default() }).is_err());
        let i = s.raise(&AttentionRaise { kind: "stalled".into(), session_id: Some("s1".into()), outcome: "stalled".into(), detail: "idle 30m".into(), ..Default::default() }).unwrap();
        let wire = serde_json::to_value(&i).unwrap();
        assert!(wire.get("ackedAt").unwrap().is_null());
        assert_eq!(s.list(true).len(), 1);
        assert_eq!(s.acknowledge("zzz").unwrap_err(), "unknown attention item 'zzz'");
        assert_eq!(s.sweep_stale(|_| false), 1);
        assert!(s.list(true).is_empty());
        assert_eq!(s.list(false)[0].acked_at, Some(7));
    }
}
