// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! ARCHIVE-1 (contract 1.149.0): the ticket archive, a port of Kotlin's `ArchiveService` + `SqlTicketArchive`.
//!
//! An archived ticket leaves `tickets` / `ticket_links` (so a board read never scans it) and lives in `archived_tickets` /
//! `archived_ticket_links` (schema `archive.sq`) with every column, plus a JSON snapshot of its epic and project.
//! `ticket_files` is never touched, so related-file lists keep finding archived tickets. Restore puts the row back in the
//! same lane at its original position (clamped to the lane's size) with the links whose other end is live.
//!
//! Frames: `archive_query` (`list | show | search | count`) and `archive_apply` (`archive | restore`), both answered with
//! `archive_report`. An in-progress ticket is never archived. The auto policy is the setting
//! `board.archive.doneAfterDays` (a project value `board.archive.doneAfterDays.<projectId>` wins; absent or <= 0 = off).
use std::collections::BTreeSet;

use infantry_orchestrator_api::dto::status;
use infantry_orchestrator_api::OrchError;
use infantry_protocol::Envelope;
use rusqlite::{params, Connection, OptionalExtension, TransactionBehavior};
use serde::{Deserialize, Serialize};

use crate::board::{tickets_envelope, Board};
use crate::store::{self, LinkRow, TicketRow, COLUMNS};

pub const QUERY_FRAME: &str = "archive_query";
pub const APPLY_FRAME: &str = "archive_apply";
pub const REPORT_FRAME: &str = "archive_report";
/// The auto-archive setting (app scope; `<KEY>.<projectId>` is the project value).
pub const KEY: &str = "board.archive.doneAfterDays";
pub const DEFAULT_LIMIT: i64 = 100;
pub const MAX_LIMIT: i64 = 500;
pub const MAX_BULK: i64 = 5_000;
const DAY_MS: i64 = 86_400_000;
/// How often the auto policy runs (Kotlin `scheduledTicker(3_600_000L)`); the policy itself is the setting [`KEY`].
pub const AUTO_CHECK_EVERY: std::time::Duration = std::time::Duration::from_secs(3_600);

/// `ArchiveRequest` (Kotlin `ArchiveWire.kt`); unknown fields are ignored, as Kotlin's decoder does.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct ArchiveRequest {
    pub op: String,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub ids: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub project_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub epic_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub older_than_days: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub limit: Option<i64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub offset: Option<i64>,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    pub dry_run: bool,
}

/// One archived ticket as the lists and the UI read it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ArchiveItem {
    pub id: String,
    pub title: String,
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub project_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub epic_id: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub epic_name: Option<String>,
    pub archived_at: i64,
    pub updated_at: i64,
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub branch: Option<String>,
    /// Only on `show`.
    #[serde(skip_serializing_if = "Option::is_none", default)]
    pub description: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty", default)]
    pub links: Vec<String>,
}

/// The answer: compact `text` (CLI / MCP) plus the structured `items` (UI); `count` is the archive total for the scope,
/// or how many moved.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ArchiveReport {
    pub text: String,
    #[serde(skip_serializing_if = "Vec::is_empty", default)]
    pub items: Vec<ArchiveItem>,
    #[serde(default)]
    pub count: i64,
}

impl ArchiveReport {
    pub fn envelope(&self) -> Envelope {
        Envelope::new(REPORT_FRAME, serde_json::to_string(self).expect("archive report json"))
    }
}

/// One archived ticket with everything it had.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ArchivedTicket {
    pub row: TicketRow,
    pub archived_at: i64,
    pub links: Vec<LinkRow>,
    pub epic_json: Option<String>,
    pub project_json: Option<String>,
}

fn bad(m: impl Into<String>) -> OrchError {
    OrchError::Refused(m.into())
}

fn failed(e: impl std::fmt::Display) -> OrchError {
    OrchError::Failed(e.to_string())
}

// ---- the store (Kotlin `archive.sq`) --------------------------------------------------------------------------------

fn archived_row(c: &Connection, r: &rusqlite::Row<'_>) -> rusqlite::Result<ArchivedTicket> {
    let row = store::row_of(r)?;
    let links = archived_links(c, &row.id)?;
    Ok(ArchivedTicket { row, archived_at: r.get(34)?, epic_json: r.get(35)?, project_json: r.get(36)?, links })
}

fn archived_links(c: &Connection, id: &str) -> rusqlite::Result<Vec<LinkRow>> {
    let mut st = c.prepare_cached("SELECT ticket_id, related_id, type, created_at FROM archived_ticket_links WHERE ticket_id = ?1 OR related_id = ?1")?;
    let out = st.query_map([id], |r| Ok(LinkRow { ticket_id: r.get(0)?, related_id: r.get(1)?, r#type: r.get(2)?, created_at: r.get(3)? }))?.collect();
    out
}

fn select(c: &Connection, tail: &str, p: &[&dyn rusqlite::ToSql]) -> rusqlite::Result<Vec<ArchivedTicket>> {
    let sql = format!("SELECT {COLUMNS}, archived_at, epic_json, project_json FROM archived_tickets {tail}");
    let mut st = c.prepare(&sql)?;
    let mut rows = st.query(p)?;
    let mut out = Vec::new();
    while let Some(r) = rows.next()? {
        out.push(archived_row(c, r)?);
    }
    Ok(out)
}

pub fn get_archived(c: &Connection, id: &str) -> rusqlite::Result<Option<ArchivedTicket>> {
    Ok(select(c, "WHERE id = ?1", &[&id])?.into_iter().next())
}

pub fn count_archived(c: &Connection, project_id: Option<&str>) -> rusqlite::Result<i64> {
    c.query_row("SELECT COUNT(*) FROM archived_tickets WHERE (?1 IS NULL OR project_id = ?1)", [project_id], |r| r.get(0))
}

pub fn list_archived(c: &Connection, project_id: Option<&str>, epic_id: Option<&str>, limit: i64, offset: i64) -> rusqlite::Result<Vec<ArchivedTicket>> {
    select(
        c,
        "WHERE (?1 IS NULL OR project_id = ?1) AND (?2 IS NULL OR epic_id = ?2) ORDER BY archived_at DESC, id ASC LIMIT ?3 OFFSET ?4",
        &[&project_id, &epic_id, &limit, &offset],
    )
}

/// Case-insensitive substring match on title, description and id.
pub fn search_archived(c: &Connection, project_id: Option<&str>, text: &str, limit: i64) -> rusqlite::Result<Vec<ArchivedTicket>> {
    let like = format!("%{}%", text.replace('\\', "\\\\").replace('%', "\\%").replace('_', "\\_"));
    select(
        c,
        "WHERE (?1 IS NULL OR project_id = ?1) AND (title LIKE ?2 ESCAPE '\\' OR description LIKE ?2 ESCAPE '\\' OR id LIKE ?2 ESCAPE '\\') \
         ORDER BY archived_at DESC, id ASC LIMIT ?3",
        &[&project_id, &like, &limit],
    )
}

fn archived_ids(c: &Connection) -> rusqlite::Result<Vec<String>> {
    let mut st = c.prepare("SELECT id FROM archived_tickets")?;
    let out = st.query_map([], |r| r.get(0))?.collect();
    out
}

/// Moves one live ticket (and its links) into the archive. False when no live ticket has that id.
fn archive_one(c: &Connection, id: &str, epic_json: Option<&str>, project_json: Option<&str>, now: i64) -> rusqlite::Result<bool> {
    let cols = COLUMNS;
    let n = c.execute(
        &format!("INSERT OR REPLACE INTO archived_tickets({cols}, archived_at, epic_json, project_json) SELECT {cols}, ?2, ?3, ?4 FROM tickets WHERE id = ?1"),
        params![id, now, epic_json, project_json],
    )?;
    if n == 0 {
        return Ok(false);
    }
    c.execute(
        "INSERT OR REPLACE INTO archived_ticket_links(ticket_id, related_id, type, created_at) \
         SELECT ticket_id, related_id, type, created_at FROM ticket_links WHERE ticket_id = ?1 OR related_id = ?1",
        [id],
    )?;
    store::delete_ticket(c, id)?;
    Ok(true)
}

/// Closes the gaps archives left in a lane (contiguous positions, same order).
fn renumber(c: &Connection, lane: &str) -> rusqlite::Result<()> {
    c.execute(
        "UPDATE tickets SET position = (SELECT COUNT(*) FROM tickets t2 WHERE t2.status = tickets.status \
         AND (t2.position < tickets.position OR (t2.position = tickets.position AND t2.id < tickets.id))) WHERE status = ?1",
        [lane],
    )?;
    Ok(())
}

/// Puts an archived ticket back at its original lane position (clamped to the lane), with the links whose other end is
/// live. False when it is not archived.
fn restore_one(c: &Connection, id: &str) -> rusqlite::Result<bool> {
    let Some((lane, pos)): Option<(String, i64)> =
        c.query_row("SELECT status, position FROM archived_tickets WHERE id = ?1", [id], |r| Ok((r.get(0)?, r.get(1)?))).optional()?
    else {
        return Ok(false);
    };
    if c.query_row("SELECT 1 FROM tickets WHERE id = ?1", [id], |_| Ok(())).optional()?.is_some() {
        return Ok(false); // a live ticket took the id: never overwrite it
    }
    let size: i64 = c.query_row("SELECT COUNT(*) FROM tickets WHERE status = ?1", [&lane], |r| r.get(0))?;
    let at = pos.clamp(0, size);
    c.execute("UPDATE tickets SET position = position + 1 WHERE status = ?1 AND position >= ?2", params![lane, at])?;
    let cols = COLUMNS;
    let rest = cols.replacen("id, title, description, ticket_type, status, position, ", "", 1);
    c.execute(
        &format!("INSERT INTO tickets({cols}) SELECT id, title, description, ticket_type, status, ?2, {rest} FROM archived_tickets WHERE id = ?1"),
        params![id, at],
    )?;
    c.execute(
        "INSERT OR IGNORE INTO ticket_links(ticket_id, related_id, type, created_at) \
         SELECT l.ticket_id, l.related_id, l.type, l.created_at FROM archived_ticket_links l \
         WHERE (l.ticket_id = ?1 OR l.related_id = ?1) AND l.ticket_id IN (SELECT id FROM tickets) AND l.related_id IN (SELECT id FROM tickets)",
        [id],
    )?;
    c.execute(
        "DELETE FROM archived_ticket_links WHERE (ticket_id = ?1 OR related_id = ?1) \
         AND ticket_id IN (SELECT id FROM tickets) AND related_id IN (SELECT id FROM tickets)",
        [id],
    )?;
    c.execute("DELETE FROM archived_tickets WHERE id = ?1", [id])?;
    Ok(true)
}

// ---- the service ----------------------------------------------------------------------------------------------------

fn days(v: Option<String>) -> i64 {
    // A setting is stored as JSON value text: `30` or `"30"`.
    v.map(|s| s.trim().trim_matches('"').trim().parse::<i64>().unwrap_or(0)).filter(|d| *d > 0).unwrap_or(0)
}

fn list_text(items: &[ArchiveItem], total: i64) -> String {
    if items.is_empty() {
        return "no archived tickets".into();
    }
    let lines: Vec<String> = items.iter().map(|i| format!("{} [{}] {}", i.id, i.status, i.title)).collect();
    format!("{total} archived\n{}", lines.join("\n"))
}

impl Board {
    fn archive_now(&self) -> i64 {
        self.env().now_ms()
    }

    fn epic_snapshot(&self, c: &Connection, epic_id: &str) -> Option<(String, String)> {
        let e = crate::epics::get_row(c, epic_id).ok().flatten()?;
        Some((e.name.clone(), serde_json::to_string(&e).ok()?))
    }

    fn item(&self, c: &Connection, a: &ArchivedTicket, full: bool) -> ArchiveItem {
        let epic_name = a
            .row
            .epic_id
            .as_deref()
            .and_then(|e| self.epic_snapshot(c, e).map(|s| s.0))
            .or_else(|| a.epic_json.as_deref().and_then(|j| serde_json::from_str::<serde_json::Value>(j).ok()).and_then(|v| v["name"].as_str().map(str::to_string)));
        ArchiveItem {
            id: a.row.id.clone(),
            title: a.row.title.clone(),
            status: a.row.status.clone(),
            project_id: a.row.project_id.clone(),
            epic_id: a.row.epic_id.clone(),
            epic_name,
            archived_at: a.archived_at,
            updated_at: a.row.updated_at,
            branch: a.row.branch.clone(),
            description: full.then(|| a.row.description.clone()),
            links: if full { a.links.iter().map(|l| format!("{} {} {}", l.ticket_id, l.r#type, l.related_id)).collect() } else { Vec::new() },
        }
    }

    /// An archived id: exact, `ticket-<id>`, or a unique suffix.
    fn resolve_archived(&self, c: &Connection, raw: &str) -> Result<String, OrchError> {
        let want = raw.trim();
        if want.is_empty() {
            return Err(bad("a ticket id is required"));
        }
        let ids = archived_ids(c).map_err(failed)?;
        if ids.iter().any(|i| i == want) {
            return Ok(want.to_string());
        }
        let hits: Vec<&String> = ids.iter().filter(|i| i.ends_with(want)).collect();
        match hits.len() {
            1 => Ok(hits[0].clone()),
            0 => Err(bad(format!("no archived ticket '{}'", crate::rules::take(want, 80)))),
            n => Err(bad(format!("'{}' matches {n} archived tickets: use the full id", crate::rules::take(want, 80)))),
        }
    }

    /// `archive_query`: list, show, search, count.
    pub fn archive_query(&self, req: &ArchiveRequest) -> Result<ArchiveReport, OrchError> {
        let project = req.project_id.as_deref().filter(|p| !p.is_empty());
        let limit = req.limit.unwrap_or(DEFAULT_LIMIT).clamp(1, MAX_LIMIT);
        self.db()
            .read(|c| {
                Ok(match req.op.as_str() {
                    "list" | "" => {
                        let rows = list_archived(c, project, req.epic_id.as_deref().filter(|e| !e.is_empty()), limit, req.offset.unwrap_or(0).max(0))?;
                        let items: Vec<ArchiveItem> = rows.iter().map(|a| self.item(c, a, false)).collect();
                        let total = count_archived(c, project)?;
                        Ok(ArchiveReport { text: list_text(&items, total), items, count: total })
                    }
                    "search" => {
                        let q = req.text.as_deref().unwrap_or("").trim().to_string();
                        if q.is_empty() {
                            return Ok(Err(bad("search needs text")));
                        }
                        let items: Vec<ArchiveItem> = search_archived(c, project, &q, limit)?.iter().map(|a| self.item(c, a, false)).collect();
                        let n = items.len() as i64;
                        Ok(ArchiveReport { text: list_text(&items, n), items, count: n })
                    }
                    "show" => {
                        let [one] = req.ids.as_slice() else { return Ok(Err(bad("show needs exactly one id"))) };
                        let id = match self.resolve_archived(c, one) {
                            Ok(id) => id,
                            Err(e) => return Ok(Err(e)),
                        };
                        let Some(a) = get_archived(c, &id)? else { return Ok(Err(bad(format!("no archived ticket {id}")))) };
                        let it = self.item(c, &a, true);
                        let branch = it.branch.as_ref().map(|b| format!(" branch {b}")).unwrap_or_default();
                        let text = format!("{} [{}] {}\narchived {}{branch}\n\n{}", it.id, it.status, it.title, it.archived_at, a.row.description);
                        Ok(ArchiveReport { text, items: vec![it], count: 1 })
                    }
                    "count" => {
                        let n = count_archived(c, project)?;
                        Ok(ArchiveReport { text: format!("{n} archived"), items: Vec::new(), count: n })
                    }
                    other => Err(bad(format!("unknown archive query '{other}' (list | show | search | count)"))),
                })
            })
            .map_err(failed)?
    }

    /// The live ids an `archive` request names. In-progress tickets are never picked (and refused by id).
    fn pick_to_archive(&self, req: &ArchiveRequest) -> Result<Vec<String>, OrchError> {
        let live = self.list(None, None)?;
        if !req.ids.is_empty() {
            let mut out = Vec::new();
            for raw in &req.ids {
                let t = self.find(raw)?;
                if t.status == status::IN_PROGRESS {
                    return Err(bad(format!("{} is in progress: an in-progress ticket is never archived", t.id)));
                }
                out.push(t.id);
            }
            return Ok(out);
        }
        let project = req.project_id.as_deref().filter(|p| !p.is_empty());
        let scoped = live.into_iter().filter(|t| project.is_none() || t.project_id.as_deref() == project).filter(|t| t.status != status::IN_PROGRESS);
        if let Some(epic) = req.epic_id.as_deref().filter(|e| !e.is_empty()) {
            return Ok(scoped.filter(|t| t.epic_id.as_deref() == Some(epic)).map(|t| t.id).collect());
        }
        let Some(lane) = req.status.as_deref().filter(|s| !s.is_empty()) else {
            return Err(bad("archive needs ids, an epicId, or a status (+ olderThanDays)"));
        };
        if lane == status::IN_PROGRESS {
            return Err(bad("an in-progress ticket is never archived"));
        }
        let cutoff = req.older_than_days.map(|d| self.archive_now() - d.max(0) * DAY_MS);
        let rows = self.db().read(store::all_tickets).map_err(failed)?;
        let updated: std::collections::HashMap<String, i64> = rows.into_iter().map(|r| (r.id, r.updated_at)).collect();
        Ok(scoped
            .filter(|t| t.status == lane)
            .filter(|t| cutoff.is_none_or(|c| updated.get(&t.id).copied().unwrap_or(i64::MAX) <= c))
            .map(|t| t.id)
            .collect())
    }

    /// Archives `ids` in one transaction (never an in-progress one), then closes each left lane's gaps once.
    pub fn archive_ids(&self, ids: &[String]) -> Result<Vec<String>, OrchError> {
        if ids.is_empty() {
            return Ok(Vec::new());
        }
        let now = self.archive_now();
        let _g = self.guard();
        let env = self.env().clone();
        let moved = self
            .db()
            .write(|c| {
                let tx = c.transaction_with_behavior(TransactionBehavior::Immediate)?;
                let mut lanes = BTreeSet::new();
                let mut moved = Vec::new();
                for id in ids {
                    let Some(t) = store::get_ticket(&tx, id)? else { continue };
                    if t.status == status::IN_PROGRESS {
                        continue;
                    }
                    let epic = t.epic_id.as_deref().and_then(|e| self.epic_snapshot(&tx, e)).map(|s| s.1);
                    let project = t.project_id.as_deref().and_then(|p| env.project_json(p));
                    if archive_one(&tx, id, epic.as_deref(), project.as_deref(), now)? {
                        lanes.insert(t.status.clone());
                        moved.push(id.clone());
                    }
                }
                for l in &lanes {
                    renumber(&tx, l)?;
                }
                tx.commit()?;
                Ok(moved)
            })
            .map_err(failed)?;
        Ok(moved)
    }

    /// Restores `ids` (archived ids, already resolved). Returns the ones that came back.
    pub fn restore_ids(&self, ids: &[String]) -> Result<Vec<String>, OrchError> {
        let _g = self.guard();
        self.db()
            .write(|c| {
                let tx = c.transaction_with_behavior(TransactionBehavior::Immediate)?;
                let mut moved = Vec::new();
                for id in ids {
                    if restore_one(&tx, id)? {
                        moved.push(id.clone());
                    }
                }
                tx.commit()?;
                Ok(moved)
            })
            .map_err(failed)
    }

    /// `archive_apply`: archive or restore. After a move the whole board is pushed (`tickets`).
    pub fn archive_apply(&self, req: &ArchiveRequest, publish: &dyn Fn(Envelope)) -> Result<ArchiveReport, OrchError> {
        let (verb, moved) = match req.op.as_str() {
            "archive" => {
                let ids = self.pick_to_archive(req)?;
                (if req.dry_run { "would archive" } else { "archived" }, if req.dry_run { ids } else { self.archive_ids(&ids)? })
            }
            "restore" | "unarchive" => {
                let project = req.project_id.as_deref().filter(|p| !p.is_empty());
                let ids: Vec<String> = self
                    .db()
                    .read(|c| {
                        if !req.ids.is_empty() {
                            return Ok(req.ids.iter().map(|r| self.resolve_archived(c, r)).collect::<Result<Vec<_>, _>>());
                        }
                        match req.epic_id.as_deref().filter(|e| !e.is_empty()) {
                            Some(e) => Ok(Ok(list_archived(c, project, Some(e), MAX_BULK, 0)?.into_iter().map(|a| a.row.id).collect())),
                            None => Ok(Err(bad("restore needs ids or an epicId"))),
                        }
                    })
                    .map_err(failed)??;
                (if req.dry_run { "would restore" } else { "restored" }, if req.dry_run { ids } else { self.restore_ids(&ids)? })
            }
            other => return Err(bad(format!("unknown archive action '{other}' (archive | restore)"))),
        };
        if !req.dry_run && !moved.is_empty() {
            publish(tickets_envelope(&self.list(None, None)?));
        }
        let n = moved.len() as i64;
        Ok(ArchiveReport { text: format!("{verb} {n} ticket(s)"), items: Vec::new(), count: n })
    }

    /// The auto policy: archives every done ticket older than its project's `board.archive.doneAfterDays`. How many moved.
    pub fn auto_archive(&self, publish: &dyn Fn(Envelope)) -> Result<usize, OrchError> {
        let now = self.archive_now();
        let (global, rows) = self.db().read(|c| Ok((days(store::setting(c, KEY)?), store::all_tickets(c)?))).map_err(failed)?;
        let mut due = Vec::new();
        for t in rows.iter().filter(|t| t.status == status::DONE) {
            let project = match &t.project_id {
                Some(p) => days(self.db().read(|c| store::setting(c, &format!("{KEY}.{p}"))).map_err(failed)?),
                None => 0,
            };
            let d = if project > 0 { project } else { global };
            if d > 0 && t.updated_at <= now - d * DAY_MS {
                due.push(t.id.clone());
            }
        }
        let moved = self.archive_ids(&due)?;
        if !moved.is_empty() {
            publish(tickets_envelope(&self.list(None, None)?));
        }
        Ok(moved.len())
    }
}

impl Board {
    /// Runs [`Board::auto_archive`] every [`AUTO_CHECK_EVERY`] for the process's life (a failure is logged, the loop goes on).
    pub fn spawn_auto_archive(self: &std::sync::Arc<Self>, publish: impl Fn(Envelope) + Send + 'static) {
        let board = self.clone();
        std::thread::spawn(move || loop {
            std::thread::sleep(AUTO_CHECK_EVERY);
            match board.auto_archive(&publish) {
                Ok(0) => {}
                Ok(n) => eprintln!("[board] auto-archive: archived {n} done ticket(s) past {KEY}"),
                Err(e) => eprintln!("[board] auto-archive failed: {e:?}"),
            }
        });
    }
}
