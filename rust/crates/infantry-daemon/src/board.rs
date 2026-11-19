// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-MIGRATE-1: the projects and agent profiles in `<stateDir>/infantry.db`, read-only, for `project_list`,
//! `project_get` and `agent_list`. The db is the one the Kotlin daemon keeps (the same schema, PLAN §4), so a state
//! dir migrated with `infantry-migrate` lists the same projects. Projects are read only here; agent profiles are also
//! written (`save_agent`, `delete_agent`), the same rows the Kotlin daemon keeps.
//!
//! A connection is opened per request (a listing is rare and the file is small); no file = no projects.

use infantry_protocol::agents::AgentProfile;
use infantry_protocol::projects::{Project, RoutingRule};
use rusqlite::{Connection, OpenFlags, Row};
use std::path::{Path, PathBuf};
use std::time::Duration;

pub const DB_FILE: &str = "infantry.db";
/// How long a read waits on a db another process is writing.
pub const BUSY_TIMEOUT_MS: u64 = 5_000;

pub struct Board {
    db: PathBuf,
}

/// A JSON column, or its default when it is empty or not JSON (one bad row does not cost the listing).
fn json<T: serde::de::DeserializeOwned + Default>(row: &Row, col: &str) -> T {
    row.get::<_, Option<String>>(col).ok().flatten().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default()
}

fn text(row: &Row, col: &str) -> String {
    row.get::<_, Option<String>>(col).ok().flatten().unwrap_or_default()
}

fn opt(row: &Row, col: &str) -> Option<String> {
    row.get::<_, Option<String>>(col).ok().flatten()
}

fn project(row: &Row) -> rusqlite::Result<Project> {
    Ok(Project {
        id: row.get("id")?,
        name: row.get("name")?,
        goal: text(row, "goal"),
        directory: text(row, "directory"),
        base_branch: text(row, "base_branch"),
        lane_caps: json(row, "lane_caps"),
        auto_remove_done_workers: row.get::<_, Option<i64>>("auto_remove_done_workers").ok().flatten().unwrap_or(0) != 0,
        routing_rules: json::<Vec<RoutingRule>>(row, "routing_rules"),
        experiments: json(row, "experiments"),
        data: json(row, "project_data"),
    })
}

fn agent(row: &Row) -> rusqlite::Result<AgentProfile> {
    let list = |col: &str| Some(json::<Vec<String>>(row, col));
    Ok(AgentProfile {
        id: Some(row.get("id")?),
        name: row.get("name")?,
        role: row.get("role")?,
        provider: opt(row, "provider"),
        model: opt(row, "model"),
        instructions: opt(row, "instructions"),
        project_id: opt(row, "project_id"),
        effort: opt(row, "effort"),
        trait_ids: list("trait_ids"),
        skill_ids: list("skill_ids"),
        runner: opt(row, "runner"),
    })
}

impl Board {
    pub fn new(state_dir: &Path) -> Board {
        Board { db: state_dir.join(DB_FILE) }
    }

    /// The db, or None when this state dir has none. Opened read-write without create, so a WAL db can be read
    /// whoever else has it open; only SELECTs run on it.
    fn open(&self) -> Result<Option<Connection>, String> {
        if !self.db.is_file() {
            return Ok(None);
        }
        let c = Connection::open_with_flags(&self.db, OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_NO_MUTEX)
            .map_err(|e| format!("{}: {e}", self.db.display()))?;
        c.busy_timeout(Duration::from_millis(BUSY_TIMEOUT_MS)).map_err(|e| e.to_string())?;
        Ok(Some(c))
    }

    fn has_table(c: &Connection, t: &str) -> bool {
        c.query_row("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?1", [t], |_| Ok(())).is_ok()
    }

    /// Every project in creation order (as Kotlin's `getAllProjects`).
    pub fn projects(&self) -> Result<Vec<Project>, String> {
        let Some(c) = self.open()? else { return Ok(vec![]) };
        if !Self::has_table(&c, "projects") {
            return Ok(vec![]);
        }
        let mut st = c.prepare("SELECT * FROM projects ORDER BY created_at ASC, id ASC").map_err(|e| e.to_string())?;
        let rows = st.query_map([], project).map_err(|e| e.to_string())?;
        Ok(rows.filter_map(Result::ok).collect())
    }

    pub fn project(&self, id: &str) -> Result<Option<Project>, String> {
        Ok(self.projects()?.into_iter().find(|p| p.id == id))
    }

    /// One agent profile by id.
    pub fn agent(&self, id: &str) -> Result<Option<AgentProfile>, String> {
        Ok(self.agents(None)?.into_iter().find(|a| a.id.as_deref() == Some(id)))
    }

    /// Creates or overwrites the profile `p` (its id is set); `created_at` of a known profile is kept.
    pub fn save_agent(&self, p: &AgentProfile, now_ms: i64) -> Result<(), String> {
        let Some(c) = self.open()? else { return Err("this state dir has no database yet".into()) };
        if !Self::has_table(&c, "agents") {
            return Err("the database has no agents table".into());
        }
        let list = |v: &Option<Vec<String>>| serde_json::to_string(&v.clone().unwrap_or_default()).unwrap_or_else(|_| "[]".into());
        c.execute(
            "INSERT INTO agents (id, name, role, provider, model, instructions, created_at, updated_at, project_id, effort, trait_ids, skill_ids, runner)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?7, ?8, ?9, ?10, ?11, ?12)
             ON CONFLICT(id) DO UPDATE SET name = ?2, role = ?3, provider = ?4, model = ?5, instructions = ?6, updated_at = ?7,
               project_id = ?8, effort = ?9, trait_ids = ?10, skill_ids = ?11, runner = ?12",
            rusqlite::params![
                p.id, p.name, p.role, p.provider, p.model, p.instructions, now_ms, p.project_id, p.effort, list(&p.trait_ids), list(&p.skill_ids), p.runner
            ],
        )
        .map_err(|e| e.to_string())?;
        Ok(())
    }

    /// Deletes the profile; false = there was none.
    pub fn delete_agent(&self, id: &str) -> Result<bool, String> {
        let Some(c) = self.open()? else { return Ok(false) };
        if !Self::has_table(&c, "agents") {
            return Ok(false);
        }
        Ok(c.execute("DELETE FROM agents WHERE id = ?1", [id]).map_err(|e| e.to_string())? > 0)
    }

    /// Agent profiles, optionally of one project, by name.
    pub fn agents(&self, project_id: Option<&str>) -> Result<Vec<AgentProfile>, String> {
        let Some(c) = self.open()? else { return Ok(vec![]) };
        if !Self::has_table(&c, "agents") {
            return Ok(vec![]);
        }
        let mut st = c.prepare("SELECT * FROM agents ORDER BY name ASC, id ASC").map_err(|e| e.to_string())?;
        let rows = st.query_map([], agent).map_err(|e| e.to_string())?;
        Ok(rows.filter_map(Result::ok).filter(|a| project_id.is_none_or(|p| a.project_id.as_deref() == Some(p))).collect())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_projects_and_agents_from_a_kotlin_shaped_db() {
        let dir = std::env::temp_dir().join(format!("infantry-board-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let b = Board::new(&dir);
        assert!(b.projects().unwrap().is_empty(), "no db = no projects");
        let c = Connection::open(dir.join(DB_FILE)).unwrap();
        c.execute_batch(
            r#"PRAGMA journal_mode=WAL;
            CREATE TABLE projects (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, goal TEXT NOT NULL DEFAULT '',
              story_ids TEXT NOT NULL DEFAULT '[]', created_at INTEGER NOT NULL, directory TEXT NOT NULL DEFAULT '',
              base_branch TEXT NOT NULL DEFAULT '', lane_caps TEXT NOT NULL DEFAULT '{}', auto_remove_done_workers INTEGER NOT NULL DEFAULT 0,
              experiments TEXT NOT NULL DEFAULT '{}', routing_rules TEXT NOT NULL DEFAULT '[]', project_data TEXT NOT NULL DEFAULT '{}');
            INSERT INTO projects (id, name, created_at, directory, lane_caps, auto_remove_done_workers, routing_rules, project_data)
              VALUES ('project-b', 'Beta', 2, 'C:\\b', '{"backend":2}', 1,
                      '[{"name":"cheap","provider":"opencode","model":"m","ticketTypes":["bug"]}]', '{"k":"v"}'),
                     ('project-a', 'Alpha', 1, '', 'not json', 0, '[]', '{}');
            CREATE TABLE agents (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, role TEXT NOT NULL, provider TEXT, model TEXT,
              instructions TEXT, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, project_id TEXT, effort TEXT,
              trait_ids TEXT NOT NULL DEFAULT '[]', skill_ids TEXT NOT NULL DEFAULT '[]', runner TEXT);
            INSERT INTO agents (id, name, role, created_at, updated_at, project_id) VALUES
              ('agent-1', 'Ada', 'backend', 1, 1, 'project-a'), ('agent-2', 'Bo', 'qa', 1, 1, 'project-b');"#,
        )
        .unwrap();
        // Read while another connection holds the db open, as the Kotlin daemon would.
        let p = b.projects().unwrap();
        assert_eq!(p.iter().map(|p| p.id.as_str()).collect::<Vec<_>>(), ["project-a", "project-b"]);
        assert!(p[0].lane_caps.is_empty(), "a bad JSON column reads as its default");
        assert_eq!(p[1].lane_caps["backend"], 2);
        assert!(p[1].auto_remove_done_workers);
        assert_eq!(p[1].routing_rules[0].ticket_types, ["bug"]);
        assert_eq!(p[1].data["k"], "v");
        let wire = serde_json::to_value(&p[1]).unwrap();
        assert_eq!(wire["baseBranch"], "");
        assert_eq!(wire["routingRules"][0]["provider"], "opencode");
        assert_eq!(b.project("project-b").unwrap().unwrap().name, "Beta");
        assert_eq!(b.agents(None).unwrap().len(), 2);
        assert_eq!(b.agents(Some("project-b")).unwrap()[0].name, "Bo");
        drop(c);
        let _ = std::fs::remove_dir_all(dir);
    }
}
