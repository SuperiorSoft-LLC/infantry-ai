// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Milestone tickets (`infantry_notify_milestone`): a marked ticket sends ONE milestone notice to the owner when it
//! enters done. Stored in the `notify_milestones` table; the mark is taken when the notice fires.
use std::sync::Arc;

use infantry_core::db::{Db, DbError};
use rusqlite::{params, OptionalExtension};
use serde::{Deserialize, Serialize};

const SCHEMA: &str = "CREATE TABLE IF NOT EXISTS notify_milestones (ticket_id TEXT NOT NULL PRIMARY KEY, sound TEXT, created_at INTEGER NOT NULL)";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Milestone {
    pub ticket_id: String,
    pub sound: Option<String>,
}

pub struct MilestoneStore {
    db: Arc<Db>,
}

impl MilestoneStore {
    pub fn new(db: Arc<Db>) -> Result<Self, DbError> {
        db.write(|c| c.execute_batch(SCHEMA))?;
        Ok(MilestoneStore { db })
    }

    /// Marks (`on`) or clears a ticket. Returns the mark now held (None when cleared).
    pub fn set(&self, ticket_id: &str, on: bool, sound: Option<&str>, at: i64) -> Result<Option<Milestone>, String> {
        let id = ticket_id.trim();
        if id.is_empty() {
            return Err("needs a ticketId".into());
        }
        let r = if on {
            self.db.write(|c| {
                c.execute(
                    "INSERT INTO notify_milestones(ticket_id, sound, created_at) VALUES (?1, ?2, ?3) ON CONFLICT(ticket_id) DO UPDATE SET sound = excluded.sound",
                    params![id, sound, at],
                )
            })
        } else {
            self.db.write(|c| c.execute("DELETE FROM notify_milestones WHERE ticket_id = ?1", params![id]))
        };
        r.map_err(|e| e.to_string())?;
        Ok(on.then(|| Milestone { ticket_id: id.into(), sound: sound.map(str::to_string) }))
    }

    pub fn list(&self) -> Vec<Milestone> {
        self.db
            .read(|c| {
                let mut st = c.prepare("SELECT ticket_id, sound FROM notify_milestones ORDER BY created_at, ticket_id")?;
                let rows = st.query_map([], |r| Ok(Milestone { ticket_id: r.get(0)?, sound: r.get(1)? }))?;
                rows.collect()
            })
            .unwrap_or_default()
    }

    /// Takes the mark of a ticket that entered done: Some once, None after (so the notice fires once).
    pub fn take(&self, ticket_id: &str) -> Option<Milestone> {
        self.db
            .write(|c| {
                let m = c
                    .query_row("SELECT ticket_id, sound FROM notify_milestones WHERE ticket_id = ?1", params![ticket_id], |r| {
                        Ok(Milestone { ticket_id: r.get(0)?, sound: r.get(1)? })
                    })
                    .optional()?;
                if m.is_some() {
                    c.execute("DELETE FROM notify_milestones WHERE ticket_id = ?1", params![ticket_id])?;
                }
                Ok(m)
            })
            .ok()
            .flatten()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mark_list_take_once_and_clear() {
        let dir = tempfile::tempdir().unwrap();
        let s = MilestoneStore::new(Arc::new(Db::open(dir.path().join("t.db")).unwrap())).unwrap();
        assert!(s.set(" ", true, None, 1).is_err());
        assert_eq!(s.set("t1", true, Some("bell"), 1).unwrap().unwrap().sound.as_deref(), Some("bell"));
        s.set("t2", true, None, 2).unwrap();
        assert_eq!(s.list().len(), 2);
        assert_eq!(s.set("t2", false, None, 3).unwrap(), None);
        assert_eq!(s.list().len(), 1);
        assert_eq!(s.take("t1").unwrap().ticket_id, "t1");
        assert_eq!(s.take("t1"), None, "fires once");
        assert!(s.list().is_empty());
    }
}
