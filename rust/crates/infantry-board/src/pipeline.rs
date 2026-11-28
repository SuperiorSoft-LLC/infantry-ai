// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! A project's optional verification pipeline (PIPE-1, Kotlin `PipelineStore` / `PipelineHandler`): an ordered list of
//! stage names per project, an append-only history of commits moved to a stage, and at most one running check per
//! scope. Stages are data; nothing here knows any stage name.
//!
//! No config row (every project by default) means no pipeline: `state` answers `configured: false` and every write is
//! refused `no_pipeline`. The pipeline never touches tickets: ticket moves, done and close do not read it.
//!
//! Tables: Kotlin's own `pipeline_config`, `pipeline_entries`, `pipeline_checks` (43.sqm), created here if absent so a
//! Kotlin-migrated database and a fresh Rust one hold the same rows.
use std::sync::{Arc, Mutex};

use infantry_core::db::Db;
use infantry_orchestrator_api::OrchError;
use infantry_protocol::Envelope;
use rusqlite::{params, Connection, OptionalExtension};
use serde::{Deserialize, Serialize};

use crate::env::BoardEnv;

pub const FRAMES: [&str; 4] = ["pipeline_config", "pipeline_show", "pipeline_record", "pipeline_check"];
pub const STATE_FRAME: &str = "pipeline_state";

const MIGRATION: [&str; 4] = [
    "CREATE TABLE IF NOT EXISTS pipeline_config (project_id TEXT NOT NULL PRIMARY KEY, stages TEXT NOT NULL, exclusive_check INTEGER NOT NULL DEFAULT 0)",
    "CREATE TABLE IF NOT EXISTS pipeline_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id TEXT NOT NULL, commit_sha TEXT NOT NULL, \
     stage TEXT NOT NULL, moved_by TEXT, evidence TEXT, moved_at INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS index_pipeline_entries_on_project ON pipeline_entries(project_id, commit_sha, id)",
    "CREATE TABLE IF NOT EXISTS pipeline_checks (project_id TEXT NOT NULL, scope TEXT NOT NULL, commit_sha TEXT NOT NULL, what TEXT NOT NULL, \
     started_by TEXT, started_at INTEGER NOT NULL, last_output_at INTEGER NOT NULL, PRIMARY KEY (project_id, scope))",
];

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PipelineConfig {
    pub stages: Vec<String>,
    #[serde(default)]
    pub exclusive_check: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PipelineEntry {
    pub commit: String,
    pub stage: String,
    pub moved_by: Option<String>,
    pub moved_at: i64,
    pub evidence: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PipelineCheck {
    pub scope: String,
    pub commit: String,
    pub what: String,
    pub started_by: Option<String>,
    pub started_at: i64,
    pub last_output_at: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PipelineState {
    pub project_id: String,
    pub configured: bool,
    pub config: Option<PipelineConfig>,
    /// Each commit's current stage (its newest entry), newest first.
    pub commits: Vec<PipelineEntry>,
    /// Every entry, newest first.
    pub history: Vec<PipelineEntry>,
    pub checks: Vec<PipelineCheck>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PipelineConfigure {
    pub project_id: String,
    #[serde(default)]
    pub stages: Option<Vec<String>>,
    #[serde(default)]
    pub exclusive_check: bool,
    #[serde(default)]
    pub clear: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PipelineShow {
    pub project_id: String,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PipelineRecord {
    pub project_id: String,
    pub commit: String,
    pub stage: String,
    #[serde(default)]
    pub evidence: Option<String>,
    #[serde(default)]
    pub session_id: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct PipelineCheckRequest {
    pub project_id: String,
    pub action: String,
    #[serde(default)]
    pub scope: String,
    #[serde(default)]
    pub commit: Option<String>,
    #[serde(default)]
    pub what: Option<String>,
    #[serde(default)]
    pub session_id: Option<String>,
}

fn refused(m: impl Into<String>) -> OrchError {
    OrchError::Refused(m.into())
}

fn failed(e: impl std::fmt::Display) -> OrchError {
    OrchError::Failed(e.to_string())
}

fn scope_label(scope: &str) -> String {
    if scope.is_empty() { "the default scope".into() } else { format!("scope '{scope}'") }
}

fn nonblank(s: Option<&str>) -> Option<String> {
    s.map(str::trim).filter(|x| !x.is_empty()).map(str::to_string)
}

pub struct Pipeline {
    db: Arc<Db>,
    env: Arc<dyn BoardEnv>,
    lock: Mutex<()>,
}

impl Pipeline {
    /// Creates the tables if absent.
    pub fn new(db: Arc<Db>, env: Arc<dyn BoardEnv>) -> Result<Pipeline, OrchError> {
        db.write(|c| {
            for s in MIGRATION {
                c.execute_batch(s)?;
            }
            Ok(())
        })
        .map_err(failed)?;
        Ok(Pipeline { db, env, lock: Mutex::new(()) })
    }

    fn guard(&self) -> std::sync::MutexGuard<'_, ()> {
        self.lock.lock().unwrap_or_else(|p| p.into_inner())
    }

    pub fn configure(&self, req: &PipelineConfigure) -> Result<PipelineState, OrchError> {
        let project = req.project_id.trim().to_string();
        if project.is_empty() {
            return Err(refused("projectId is required"));
        }
        let _g = self.guard();
        if req.clear {
            self.db.write(|c| c.execute("DELETE FROM pipeline_config WHERE project_id = ?1", params![project])).map_err(failed)?;
            return self.state(&project);
        }
        let list: Vec<String> = req.stages.clone().unwrap_or_default().iter().map(|s| s.trim().to_string()).collect();
        if list.is_empty() {
            return Err(refused("stages is required: the ordered stage names, or clear to remove the pipeline"));
        }
        if list.iter().any(|s| s.is_empty() || s.contains(',') || s.contains('\n')) {
            return Err(refused("a stage name is non-blank, one line, without commas"));
        }
        let mut seen = std::collections::HashSet::new();
        if !list.iter().all(|s| seen.insert(s.clone())) {
            return Err(refused("stage names must be unique"));
        }
        let stages = serde_json::to_string(&list).map_err(failed)?;
        self.db
            .write(|c| {
                c.execute(
                    "INSERT OR REPLACE INTO pipeline_config(project_id, stages, exclusive_check) VALUES (?1, ?2, ?3)",
                    params![project, stages, req.exclusive_check as i64],
                )
            })
            .map_err(failed)?;
        self.state(&project)
    }

    pub fn record(&self, project_id: &str, commit: &str, stage: &str, evidence: Option<&str>, by: Option<&str>) -> Result<PipelineState, OrchError> {
        let project = project_id.trim().to_string();
        let _g = self.guard();
        let config = self.require_config(&project)?;
        let sha = commit.trim().to_string();
        if sha.is_empty() {
            return Err(refused("commit is required"));
        }
        let name = stage.trim().to_string();
        if !config.stages.contains(&name) {
            return Err(refused(format!("unknown_stage: '{name}' is not a stage of this pipeline; stages: {}", config.stages.join(", "))));
        }
        let now = self.env.now_ms();
        let evidence = nonblank(evidence);
        self.db
            .write(|c| {
                c.execute(
                    "INSERT INTO pipeline_entries(project_id, commit_sha, stage, moved_by, evidence, moved_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
                    params![project, sha, name, by, evidence, now],
                )
            })
            .map_err(failed)?;
        self.state(&project)
    }

    pub fn check(&self, req: &PipelineCheckRequest, by: Option<&str>) -> Result<PipelineState, OrchError> {
        let project = req.project_id.trim().to_string();
        let _g = self.guard();
        let config = self.require_config(&project)?;
        let area = req.scope.trim().to_string();
        let held = self.db.read(|c| held_check(c, &project, &area)).map_err(failed)?;
        let delete = |c: &mut Connection| c.execute("DELETE FROM pipeline_checks WHERE project_id = ?1 AND scope = ?2", params![project, area]);
        match req.action.trim() {
            "start" => {
                let sha = nonblank(req.commit.as_deref()).ok_or_else(|| refused("start needs a commit"))?;
                let job = nonblank(req.what.as_deref()).ok_or_else(|| refused("start needs what it runs"))?;
                if let Some(h) = &held {
                    if config.exclusive_check {
                        return Err(refused(format!(
                            "check_running: '{}' on {} is already running in {} (started {}, last output {}); finish it first",
                            h.what,
                            h.commit,
                            scope_label(&area),
                            h.started_at,
                            h.last_output_at
                        )));
                    }
                }
                let now = self.env.now_ms();
                self.db
                    .write(|c| {
                        let tx = c.transaction()?;
                        tx.execute("DELETE FROM pipeline_checks WHERE project_id = ?1 AND scope = ?2", params![project, area])?;
                        tx.execute(
                            "INSERT INTO pipeline_checks(project_id, scope, commit_sha, what, started_by, started_at, last_output_at) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?6)",
                            params![project, area, sha, job, by, now],
                        )?;
                        tx.commit()
                    })
                    .map_err(failed)?;
            }
            "ping" => {
                if held.is_none() {
                    return Err(refused(format!("no_check: no running check in {}", scope_label(&area))));
                }
                let now = self.env.now_ms();
                self.db
                    .write(|c| c.execute("UPDATE pipeline_checks SET last_output_at = ?1 WHERE project_id = ?2 AND scope = ?3", params![now, project, area]))
                    .map_err(failed)?;
            }
            "finish" => {
                if held.is_none() {
                    return Err(refused(format!("no_check: no running check in {}", scope_label(&area))));
                }
                self.db.write(delete).map_err(failed)?;
            }
            other => return Err(refused(format!("unknown check action '{other}'; use start, ping or finish"))),
        }
        self.state(&project)
    }

    pub fn state(&self, project_id: &str) -> Result<PipelineState, OrchError> {
        let project = project_id.trim().to_string();
        let Some(config) = self.config(&project)? else {
            return Ok(PipelineState { project_id: project, configured: false, config: None, commits: vec![], history: vec![], checks: vec![] });
        };
        let (history, checks) = self
            .db
            .read(|c| {
                let mut st = c.prepare("SELECT commit_sha, stage, moved_by, moved_at, evidence FROM pipeline_entries WHERE project_id = ?1 ORDER BY id DESC")?;
                let history = st
                    .query_map(params![project], |r| {
                        Ok(PipelineEntry { commit: r.get(0)?, stage: r.get(1)?, moved_by: r.get(2)?, moved_at: r.get(3)?, evidence: r.get(4)? })
                    })?
                    .collect::<rusqlite::Result<Vec<_>>>()?;
                let mut st = c.prepare(
                    "SELECT scope, commit_sha, what, started_by, started_at, last_output_at FROM pipeline_checks WHERE project_id = ?1 ORDER BY started_at",
                )?;
                let checks = st.query_map(params![project], check_of)?.collect::<rusqlite::Result<Vec<_>>>()?;
                Ok((history, checks))
            })
            .map_err(failed)?;
        let mut seen = std::collections::HashSet::new();
        let commits = history.iter().filter(|e| seen.insert(e.commit.clone())).cloned().collect();
        Ok(PipelineState { project_id: project, configured: true, config: Some(config), commits, history, checks })
    }

    fn config(&self, project: &str) -> Result<Option<PipelineConfig>, OrchError> {
        let row = self
            .db
            .read(|c| {
                c.query_row("SELECT stages, exclusive_check FROM pipeline_config WHERE project_id = ?1", params![project], |r| {
                    Ok((r.get::<_, String>(0)?, r.get::<_, i64>(1)?))
                })
                .optional()
            })
            .map_err(failed)?;
        row.map(|(stages, ex)| Ok(PipelineConfig { stages: serde_json::from_str(&stages).map_err(failed)?, exclusive_check: ex != 0 })).transpose()
    }

    fn require_config(&self, project: &str) -> Result<PipelineConfig, OrchError> {
        self.config(project)?
            .ok_or_else(|| refused(format!("no_pipeline: project '{project}' has no pipeline configured (infantry pipeline configure <stage>...)")))
    }

    /// One `pipeline_*` frame answered as a `pipeline_state` envelope. `push` is true when the state changed (every
    /// frame but `pipeline_show`), so the caller pushes it to every client.
    pub fn answer(&self, frame: &str, data: &str, session_id: Option<&str>) -> Result<(Envelope, bool), OrchError> {
        let bad = |e: serde_json::Error| refused(format!("{frame} payload: {e}"));
        let who = |declared: Option<&str>| nonblank(declared).or_else(|| nonblank(session_id));
        let state = match frame {
            "pipeline_config" => self.configure(&serde_json::from_str(data).map_err(bad)?)?,
            "pipeline_show" => self.state(&serde_json::from_str::<PipelineShow>(data).map_err(bad)?.project_id)?,
            "pipeline_record" => {
                let q: PipelineRecord = serde_json::from_str(data).map_err(bad)?;
                self.record(&q.project_id, &q.commit, &q.stage, q.evidence.as_deref(), who(q.session_id.as_deref()).as_deref())?
            }
            "pipeline_check" => {
                let q: PipelineCheckRequest = serde_json::from_str(data).map_err(bad)?;
                let by = who(q.session_id.as_deref());
                self.check(&q, by.as_deref())?
            }
            other => return Err(refused(format!("{other} is not a pipeline frame"))),
        };
        Ok((state_envelope(&state), frame != "pipeline_show"))
    }
}

pub fn state_envelope(s: &PipelineState) -> Envelope {
    Envelope::new(STATE_FRAME, serde_json::to_string(s).expect("pipeline state json"))
}

fn check_of(r: &rusqlite::Row<'_>) -> rusqlite::Result<PipelineCheck> {
    Ok(PipelineCheck { scope: r.get(0)?, commit: r.get(1)?, what: r.get(2)?, started_by: r.get(3)?, started_at: r.get(4)?, last_output_at: r.get(5)? })
}

fn held_check(c: &Connection, project: &str, scope: &str) -> rusqlite::Result<Option<PipelineCheck>> {
    c.query_row(
        "SELECT scope, commit_sha, what, started_by, started_at, last_output_at FROM pipeline_checks WHERE project_id = ?1 AND scope = ?2",
        params![project, scope],
        check_of,
    )
    .optional()
}
