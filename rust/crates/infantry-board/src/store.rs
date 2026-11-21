// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The board's rows on the EXISTING infantry.db tables (`tickets`, `ticket_links`, `ticket_runs`,
//! `ticket_events`, `skills`, `settings`). No migrations: the columns are the ones Kotlin's
//! `tickets.sq` defines, written the way `InfantryStore` writes them (booleans as 0/1, id lists as
//! compact JSON arrays). Deletes cascade through the schema's own foreign keys.
use rusqlite::{params, Connection, OptionalExtension, Row};

/// One `tickets` row.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TicketRow {
    pub id: String,
    pub title: String,
    pub description: String,
    pub ticket_type: Option<String>,
    pub status: String,
    pub position: i32,
    pub wont_fix: bool,
    pub created_at: i64,
    pub updated_at: i64,
    pub agent_id: Option<String>,
    pub provider: Option<String>,
    pub model: Option<String>,
    pub use_worktree: Option<bool>,
    pub worktree_branch: Option<String>,
    pub include_linked_tasks_context: Option<bool>,
    pub queued_for: Option<String>,
    pub queued_at: Option<i64>,
    pub session_id: Option<String>,
    pub project_id: Option<String>,
    pub epic_id: Option<String>,
    pub branch: Option<String>,
    pub base: Option<String>,
    pub skill_ids: Vec<String>,
    pub skills_mode: String,
    pub include_skills_context: Option<bool>,
    pub started_by: Option<String>,
    pub images: Vec<String>,
    pub weight: Option<i32>,
    pub context_limit: Option<i32>,
    pub effort: Option<String>,
    pub queued_agent: Option<String>,
    pub queue_rank: Option<i32>,
    pub runner: Option<String>,
    pub mcp: Option<String>,
}

impl TicketRow {
    /// A new row with every optional column empty.
    pub fn new(id: String, title: String, status: String, now: i64) -> TicketRow {
        TicketRow {
            id,
            title,
            description: String::new(),
            ticket_type: None,
            status,
            position: 0,
            wont_fix: false,
            created_at: now,
            updated_at: now,
            agent_id: None,
            provider: None,
            model: None,
            use_worktree: None,
            worktree_branch: None,
            include_linked_tasks_context: None,
            queued_for: None,
            queued_at: None,
            session_id: None,
            project_id: None,
            epic_id: None,
            branch: None,
            base: None,
            skill_ids: Vec::new(),
            skills_mode: "merge".into(),
            include_skills_context: None,
            started_by: None,
            images: Vec::new(),
            weight: None,
            context_limit: None,
            effort: None,
            queued_agent: None,
            queue_rank: None,
            runner: None,
            mcp: None,
        }
    }
}

/// One `ticket_links` row, in its stored direction.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LinkRow {
    pub ticket_id: String,
    pub related_id: String,
    pub r#type: String,
    pub created_at: i64,
}

/// One `ticket_runs` row.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RunRow {
    pub ticket_id: String,
    pub session_id: String,
    pub started_at: i64,
    pub ended_at: Option<i64>,
    pub outcome: Option<String>,
    pub detail: Option<String>,
}

const COLUMNS: &str = "id, title, description, ticket_type, status, position, wont_fix, created_at, updated_at, agent_id, \
    provider, model, use_worktree, worktree_branch, include_linked_tasks_context, queued_for, queued_at, session_id, \
    project_id, epic_id, branch_name, base_commit, skill_ids, skills_mode, include_skills_context, started_by, images, \
    weight, context_limit, effort, queued_agent, queue_rank, runner, mcp";

fn bool_of(v: Option<i64>) -> Option<bool> {
    v.map(|x| x != 0)
}

fn int_of(b: Option<bool>) -> Option<i64> {
    b.map(i64::from)
}

/// A stored id list (`["a","b"]`); an unreadable one reads as empty, as a missing one would.
fn ids_of(text: String) -> Vec<String> {
    serde_json::from_str(&text).unwrap_or_default()
}

fn ids_text(ids: &[String]) -> String {
    serde_json::to_string(ids).expect("string list")
}

fn row_of(r: &Row<'_>) -> rusqlite::Result<TicketRow> {
    Ok(TicketRow {
        id: r.get(0)?,
        title: r.get(1)?,
        description: r.get(2)?,
        ticket_type: r.get(3)?,
        status: r.get(4)?,
        position: r.get(5)?,
        wont_fix: r.get::<_, i64>(6)? != 0,
        created_at: r.get(7)?,
        updated_at: r.get(8)?,
        agent_id: r.get(9)?,
        provider: r.get(10)?,
        model: r.get(11)?,
        use_worktree: bool_of(r.get(12)?),
        worktree_branch: r.get(13)?,
        include_linked_tasks_context: bool_of(r.get(14)?),
        queued_for: r.get(15)?,
        queued_at: r.get(16)?,
        session_id: r.get(17)?,
        project_id: r.get(18)?,
        epic_id: r.get(19)?,
        branch: r.get(20)?,
        base: r.get(21)?,
        skill_ids: ids_of(r.get(22)?),
        skills_mode: r.get(23)?,
        include_skills_context: bool_of(r.get(24)?),
        started_by: r.get(25)?,
        images: ids_of(r.get(26)?),
        weight: r.get(27)?,
        context_limit: r.get(28)?,
        effort: r.get(29)?,
        queued_agent: r.get(30)?,
        queue_rank: r.get(31)?,
        runner: r.get(32)?,
        mcp: r.get(33)?,
    })
}

/// Every ticket, in the stored order of `getAllTickets`.
pub fn all_tickets(c: &Connection) -> rusqlite::Result<Vec<TicketRow>> {
    let mut st = c.prepare_cached(&format!("SELECT {COLUMNS} FROM tickets ORDER BY status ASC, position ASC, id ASC"))?;
    let rows = st.query_map([], row_of)?.collect();
    rows
}

pub fn get_ticket(c: &Connection, id: &str) -> rusqlite::Result<Option<TicketRow>> {
    c.query_row(&format!("SELECT {COLUMNS} FROM tickets WHERE id = ?1"), [id], row_of).optional()
}

pub fn all_links(c: &Connection) -> rusqlite::Result<Vec<LinkRow>> {
    let mut st = c.prepare_cached(
        "SELECT ticket_id, related_id, type, created_at FROM ticket_links ORDER BY created_at ASC, ticket_id ASC, related_id ASC, type ASC",
    )?;
    let rows = st
        .query_map([], |r| Ok(LinkRow { ticket_id: r.get(0)?, related_id: r.get(1)?, r#type: r.get(2)?, created_at: r.get(3)? }))?
        .collect();
    rows
}

/// Every run, by ticket then start (`getTicketRunsByTicketId`'s order).
pub fn all_runs(c: &Connection) -> rusqlite::Result<Vec<RunRow>> {
    let mut st = c.prepare_cached(
        "SELECT ticket_id, session_id, started_at, ended_at, outcome, detail FROM ticket_runs ORDER BY ticket_id ASC, started_at ASC, session_id ASC",
    )?;
    let rows = st
        .query_map([], |r| {
            Ok(RunRow {
                ticket_id: r.get(0)?,
                session_id: r.get(1)?,
                started_at: r.get(2)?,
                ended_at: r.get(3)?,
                outcome: r.get(4)?,
                detail: r.get(5)?,
            })
        })?
        .collect();
    rows
}

pub fn insert_ticket(c: &Connection, t: &TicketRow) -> rusqlite::Result<()> {
    c.prepare_cached(&format!(
        "INSERT INTO tickets({COLUMNS}) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, \
         ?18, ?19, ?20, ?21, ?22, ?23, ?24, ?25, ?26, ?27, ?28, ?29, ?30, ?31, ?32, ?33, ?34)"
    ))?
    .execute(params![
        t.id,
        t.title,
        t.description,
        t.ticket_type,
        t.status,
        t.position,
        i64::from(t.wont_fix),
        t.created_at,
        t.updated_at,
        t.agent_id,
        t.provider,
        t.model,
        int_of(t.use_worktree),
        t.worktree_branch,
        int_of(t.include_linked_tasks_context),
        t.queued_for,
        t.queued_at,
        t.session_id,
        t.project_id,
        t.epic_id,
        t.branch,
        t.base,
        ids_text(&t.skill_ids),
        t.skills_mode,
        int_of(t.include_skills_context),
        t.started_by,
        ids_text(&t.images),
        t.weight,
        t.context_limit,
        t.effort,
        t.queued_agent,
        t.queue_rank,
        t.runner,
        t.mcp,
    ])?;
    Ok(())
}

/// Overwrites everything but id and created_at (`updateTicket`).
pub fn update_ticket(c: &Connection, t: &TicketRow) -> rusqlite::Result<()> {
    c.prepare_cached(
        "UPDATE tickets SET title = ?2, description = ?3, ticket_type = ?4, status = ?5, position = ?6, wont_fix = ?7, \
         updated_at = ?8, agent_id = ?9, provider = ?10, model = ?11, use_worktree = ?12, worktree_branch = ?13, \
         include_linked_tasks_context = ?14, queued_for = ?15, queued_at = ?16, session_id = ?17, project_id = ?18, \
         epic_id = ?19, branch_name = ?20, base_commit = ?21, skill_ids = ?22, skills_mode = ?23, include_skills_context = ?24, \
         started_by = ?25, images = ?26, weight = ?27, context_limit = ?28, effort = ?29, queued_agent = ?30, queue_rank = ?31, \
         runner = ?32, mcp = ?33 WHERE id = ?1",
    )?
    .execute(params![
        t.id,
        t.title,
        t.description,
        t.ticket_type,
        t.status,
        t.position,
        i64::from(t.wont_fix),
        t.updated_at,
        t.agent_id,
        t.provider,
        t.model,
        int_of(t.use_worktree),
        t.worktree_branch,
        int_of(t.include_linked_tasks_context),
        t.queued_for,
        t.queued_at,
        t.session_id,
        t.project_id,
        t.epic_id,
        t.branch,
        t.base,
        ids_text(&t.skill_ids),
        t.skills_mode,
        int_of(t.include_skills_context),
        t.started_by,
        ids_text(&t.images),
        t.weight,
        t.context_limit,
        t.effort,
        t.queued_agent,
        t.queue_rank,
        t.runner,
        t.mcp,
    ])?;
    Ok(())
}

/// Deletes the ticket; its links and runs go with it through ON DELETE CASCADE.
pub fn delete_ticket(c: &Connection, id: &str) -> rusqlite::Result<bool> {
    Ok(c.execute("DELETE FROM tickets WHERE id = ?1", [id])? > 0)
}

pub fn insert_link(c: &Connection, l: &LinkRow) -> rusqlite::Result<()> {
    c.execute(
        "INSERT OR IGNORE INTO ticket_links(ticket_id, related_id, type, created_at) VALUES (?1, ?2, ?3, ?4)",
        params![l.ticket_id, l.related_id, l.r#type, l.created_at],
    )?;
    Ok(())
}

pub fn delete_link(c: &Connection, l: &LinkRow) -> rusqlite::Result<()> {
    c.execute(
        "DELETE FROM ticket_links WHERE ticket_id = ?1 AND related_id = ?2 AND type = ?3",
        params![l.ticket_id, l.related_id, l.r#type],
    )?;
    Ok(())
}

/// The BOARD-API-2 typed event log (`ticket_events`), written in the same transaction as the change.
pub fn insert_event(c: &Connection, ticket_id: &str, kind: &str, from: Option<&str>, to: Option<&str>, at: i64) -> rusqlite::Result<()> {
    c.prepare_cached("INSERT INTO ticket_events(ticket_id, kind, from_status, to_status, at) VALUES (?1, ?2, ?3, ?4, ?5)")?
        .execute(params![ticket_id, kind, from, to, at])?;
    Ok(())
}

/// The ids among `ids` that no stored skill carries.
pub fn unknown_skills(c: &Connection, ids: &[String]) -> rusqlite::Result<Vec<String>> {
    let mut st = c.prepare_cached("SELECT 1 FROM skills WHERE id = ?1")?;
    let mut out = Vec::new();
    for id in ids {
        if !st.exists([id])? {
            out.push(id.clone());
        }
    }
    Ok(out)
}

/// A stored app-scope setting's JSON value text, if any.
pub fn setting(c: &Connection, key: &str) -> rusqlite::Result<Option<String>> {
    c.query_row("SELECT value FROM settings WHERE key = ?1", [key], |r| r.get(0)).optional()
}

/// Records a run's start (idempotent per ticket and session).
pub fn insert_run(c: &Connection, ticket_id: &str, session_id: &str, started_at: i64) -> rusqlite::Result<()> {
    c.prepare_cached("INSERT OR IGNORE INTO ticket_runs(ticket_id, session_id, started_at) VALUES (?1, ?2, ?3)")?
        .execute(params![ticket_id, session_id, started_at])?;
    Ok(())
}

/// Ends a run that has not ended yet; false when there is no such open run.
pub fn finish_run(c: &Connection, ticket_id: &str, session_id: &str, ended_at: i64, outcome: &str, detail: &str) -> rusqlite::Result<bool> {
    let n = c
        .prepare_cached(
            "UPDATE ticket_runs SET ended_at = ?3, outcome = ?4, detail = ?5 WHERE ticket_id = ?1 AND session_id = ?2 AND ended_at IS NULL",
        )?
        .execute(params![ticket_id, session_id, ended_at, outcome, detail])?;
    Ok(n > 0)
}

/// PIPE-RESTART-1: opens a run that ended again (its dead session was resumed), so the resumed session's own end is
/// recorded and handled like any run's. False when there is no ended run to reopen.
pub fn reopen_run(c: &Connection, ticket_id: &str, session_id: &str) -> rusqlite::Result<bool> {
    let n = c
        .prepare_cached("UPDATE ticket_runs SET ended_at = NULL, outcome = NULL, detail = NULL WHERE ticket_id = ?1 AND session_id = ?2 AND ended_at IS NOT NULL")?
        .execute(params![ticket_id, session_id])?;
    Ok(n > 0)
}

/// PIPE-RESTART-1: rewrites how an already ended run ended (a clean end whose session died in a later turn).
/// False when the run is not ended or there is none.
pub fn amend_run(c: &Connection, ticket_id: &str, session_id: &str, outcome: &str, detail: &str) -> rusqlite::Result<bool> {
    let n = c
        .prepare_cached("UPDATE ticket_runs SET outcome = ?3, detail = ?4 WHERE ticket_id = ?1 AND session_id = ?2 AND ended_at IS NOT NULL")?
        .execute(params![ticket_id, session_id, outcome, detail])?;
    Ok(n > 0)
}

/// One ticket's runs, oldest first.
pub fn runs_of(c: &Connection, ticket_id: &str) -> rusqlite::Result<Vec<RunRow>> {
    let mut st = c.prepare_cached(
        "SELECT ticket_id, session_id, started_at, ended_at, outcome, detail FROM ticket_runs WHERE ticket_id = ?1 ORDER BY started_at ASC, session_id ASC",
    )?;
    let rows = st
        .query_map([ticket_id], |r| {
            Ok(RunRow {
                ticket_id: r.get(0)?,
                session_id: r.get(1)?,
                started_at: r.get(2)?,
                ended_at: r.get(3)?,
                outcome: r.get(4)?,
                detail: r.get(5)?,
            })
        })?
        .collect();
    rows
}
