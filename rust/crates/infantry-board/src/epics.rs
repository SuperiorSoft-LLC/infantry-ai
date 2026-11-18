// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Epics on the existing `epics` table: one epic inside one project, listed in creation order. An
//! epic's `storyIds` is DERIVED from the board (the ids of the tickets filed under it, in board order),
//! never the stored column, which only an unwired store maintained. The base branch is set and cleared
//! only through [`Epics::base`], which checks it first.
use std::collections::HashMap;
use std::sync::Arc;

use infantry_core::db::Db;
use infantry_orchestrator_api::dto::{Epic, EpicBaseRequest, EpicSave};
use infantry_orchestrator_api::OrchError;
use rusqlite::{params, Connection, OptionalExtension, Row};

use crate::env::BoardEnv;
use crate::rules::take;

fn failed(e: impl std::fmt::Display) -> OrchError {
    OrchError::Failed(e.to_string())
}

fn refused(m: impl Into<String>) -> OrchError {
    OrchError::Refused(m.into())
}

/// One stored `epics` row (its `story_ids` column ignored: the board is the truth).
fn row_of(r: &Row<'_>) -> rusqlite::Result<Epic> {
    Ok(Epic {
        id: r.get("id")?,
        project_id: r.get("project_id")?,
        name: r.get("name")?,
        goal: r.get("goal")?,
        story_ids: Vec::new(),
        base_branch: r.get("base_branch")?,
    })
}

fn get_row(c: &Connection, id: &str) -> rusqlite::Result<Option<Epic>> {
    c.query_row("SELECT * FROM epics WHERE id = ?1", [id], row_of).optional()
}

pub struct Epics {
    db: Arc<Db>,
    env: Arc<dyn BoardEnv>,
}

impl Epics {
    pub fn new(db: Arc<Db>, env: Arc<dyn BoardEnv>) -> Epics {
        Epics { db, env }
    }

    /// `epic` as answered: `storyIds` = the tickets filed under it, in board order.
    fn view(mut e: Epic, by_epic: &HashMap<String, Vec<String>>) -> Epic {
        e.story_ids = by_epic.get(&e.id).cloned().unwrap_or_default();
        e
    }

    /// `epic_list`: every epic of the project, in creation order (ties by id).
    pub fn list(&self, project_id: &str, by_epic: &HashMap<String, Vec<String>>) -> Result<Vec<Epic>, OrchError> {
        let p = project_id.trim().to_string();
        let rows = self
            .db
            .read(|c| {
                let mut st = c.prepare("SELECT * FROM epics WHERE project_id = ?1 ORDER BY created_at ASC, id ASC")?;
                let v = st.query_map([&p], row_of)?.collect::<rusqlite::Result<Vec<_>>>()?;
                Ok(v)
            })
            .map_err(failed)?;
        Ok(rows.into_iter().map(|e| Self::view(e, by_epic)).collect())
    }

    pub fn get(&self, id: &str, by_epic: &HashMap<String, Vec<String>>) -> Result<Epic, OrchError> {
        let id = id.trim().to_string();
        self.db
            .read(|c| get_row(c, &id))
            .map_err(failed)?
            .map(|e| Self::view(e, by_epic))
            .ok_or_else(|| refused(format!("unknown epic '{}'", take(&id, 80))))
    }

    /// The epic's base branch, None when it has none or there is no such epic.
    pub fn base_branch_of(&self, id: &str) -> Option<String> {
        let id = id.trim().to_string();
        self.db.read(|c| get_row(c, &id)).ok().flatten().map(|e| e.base_branch).filter(|b| !b.is_empty())
    }

    /// `epic_save`: create an epic in a known project. A blank name or unknown project writes nothing.
    pub fn save(&self, req: EpicSave, by_epic: &HashMap<String, Vec<String>>) -> Result<Epic, OrchError> {
        let owner = req.project_id.trim().to_string();
        let name = req.name.trim().to_string();
        if name.is_empty() {
            return Err(refused("name is blank"));
        }
        let now = self.env.now_ms();
        let env = self.env.clone();
        let res = self
            .db
            .write(|c| {
                let known: bool = c.prepare_cached("SELECT 1 FROM projects WHERE id = ?1")?.exists([&owner])?;
                if !known {
                    return Ok(Err(format!("unknown project '{}'", take(&owner, 80))));
                }
                let mut id = env.new_epic_id();
                for _ in 0..10 {
                    if get_row(c, &id)?.is_none() {
                        break;
                    }
                    id = env.new_epic_id();
                }
                c.execute(
                    "INSERT INTO epics(id, project_id, name, goal, story_ids, created_at, base_branch) VALUES (?1, ?2, ?3, ?4, '[]', ?5, '')",
                    params![id, owner, name, req.goal, now],
                )?;
                Ok(Ok(get_row(c, &id)?.expect("inserted epic")))
            })
            .map_err(failed)?;
        res.map(|e| Self::view(e, by_epic)).map_err(refused)
    }

    /// `epic_base`: set (checked), clear, or read the epic's base branch.
    pub fn base(&self, req: EpicBaseRequest, by_epic: &HashMap<String, Vec<String>>) -> Result<Epic, OrchError> {
        if req.clear && req.branch.is_some() {
            return Err(refused("pass a branch or clear, not both"));
        }
        let id = req.epic_id.trim().to_string();
        let existing = self.get(&id, by_epic)?;
        let wanted = match (&req.branch, req.clear) {
            (_, true) => String::new(),
            (Some(raw), false) => {
                let name = self.env.branch_name(raw).map_err(refused)?;
                if !name.is_empty() {
                    self.env.check_base(&existing.project_id, &name).map_err(refused)?;
                }
                name
            }
            (None, false) => return Ok(existing),
        };
        self.db
            .write(|c| c.execute("UPDATE epics SET base_branch = ?1 WHERE id = ?2", params![wanted, id]))
            .map_err(failed)?;
        self.get(&id, by_epic)
    }
}
