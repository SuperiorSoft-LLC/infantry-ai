// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Ticket comments, the ticket's creator and its notify list, in the board plugin's own tables
//! (`infantry_board_comments`, `infantry_board_ticket_meta`, module `Infantry_Board` in
//! `plugin_storage_versions`), the tables Kotlin's `TicketComments` creates.
//!
//! Every new comment wakes each notified agent's live sessions with ONE short mail line (the author's own
//! session skipped); an agent with no live session is recorded as undelivered. Notify targets are
//! registry agents only, never invoked worker sessions. A project's default notify list is the setting
//! `board.notify.<projectId>` (comma or newline separated agent ids).
use std::sync::Arc;

use infantry_core::db::Db;
use infantry_orchestrator_api::dto::{Comment, CommentAuthor};
use infantry_orchestrator_api::OrchError;
use rusqlite::{params, Connection, OptionalExtension, Row};

use crate::env::BoardEnv;
use crate::store;

pub const MODULE_ID: &str = "Infantry_Board";
pub const SCHEMA_VERSION: i64 = 1;
/// The notify entry for the human user: a notice in the app, not mail.
pub const OWNER_ENTRY: &str = "owner";
pub const OPEN: &str = "open";
pub const RESOLVED: &str = "resolved";
pub const KINDS: [&str; 4] = ["done", "issue", "note", "handoff"];
pub const STATES: [&str; 2] = [OPEN, RESOLVED];
pub const WHICH: [&str; 4] = ["latestOpen", "latest", "first", "all"];

const MIGRATION: [&str; 3] = [
    "CREATE TABLE IF NOT EXISTS infantry_board_comments(id TEXT PRIMARY KEY, ticket_id TEXT NOT NULL, author_kind TEXT NOT NULL, \
     author_session TEXT, author_agent TEXT, kind TEXT NOT NULL, body TEXT NOT NULL, state TEXT NOT NULL, created_at INTEGER NOT NULL, \
     resolved_at INTEGER, resolved_by TEXT, undelivered TEXT NOT NULL DEFAULT '')",
    "CREATE INDEX IF NOT EXISTS infantry_board_comments_ticket ON infantry_board_comments(ticket_id, created_at)",
    "CREATE TABLE IF NOT EXISTS infantry_board_ticket_meta(ticket_id TEXT PRIMARY KEY, created_by TEXT NOT NULL, notify TEXT NOT NULL DEFAULT '')",
];

fn failed(e: impl std::fmt::Display) -> OrchError {
    OrchError::Failed(e.to_string())
}

fn refused(m: impl Into<String>) -> OrchError {
    OrchError::Refused(m.into())
}

fn lines(text: &str) -> Vec<String> {
    text.split('\n').filter(|s| !s.trim().is_empty()).map(str::to_string).collect()
}

fn comment_of(r: &Row<'_>) -> rusqlite::Result<Comment> {
    Ok(Comment {
        id: r.get("id")?,
        ticket_id: r.get("ticket_id")?,
        author: CommentAuthor { kind: r.get("author_kind")?, session_id: r.get("author_session")?, agent_id: r.get("author_agent")? },
        kind: r.get("kind")?,
        body: r.get("body")?,
        state: r.get("state")?,
        created_at: r.get("created_at")?,
        resolved_at: r.get("resolved_at")?,
        resolved_by: r.get("resolved_by")?,
        undelivered: lines(&r.get::<_, Option<String>>("undelivered")?.unwrap_or_default()),
    })
}

/// A ticket's creator and notify list.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Meta {
    pub created_by: String,
    pub notify: Vec<String>,
}

/// Open count, the newest open comment, and every undelivered agent.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Summary {
    pub open: usize,
    pub latest_open: Option<Comment>,
    pub undelivered: Vec<String>,
}

pub struct Comments {
    db: Arc<Db>,
    env: Arc<dyn BoardEnv>,
    lock: std::sync::Mutex<()>,
}

impl Comments {
    /// Opens the store, creating its tables on first use (plugin storage version 1).
    pub fn new(db: Arc<Db>, env: Arc<dyn BoardEnv>) -> Result<Comments, OrchError> {
        db.write(|c| {
            let tx = c.transaction()?;
            tx.execute("CREATE TABLE IF NOT EXISTS plugin_storage_versions (module TEXT PRIMARY KEY, version INTEGER NOT NULL)", [])?;
            let current: i64 = tx
                .query_row("SELECT version FROM plugin_storage_versions WHERE module = ?1", [MODULE_ID], |r| r.get(0))
                .optional()?
                .unwrap_or(0);
            if current < SCHEMA_VERSION {
                for s in MIGRATION {
                    tx.execute(s, [])?;
                }
                tx.execute(
                    "INSERT INTO plugin_storage_versions (module, version) VALUES (?1, ?2) ON CONFLICT(module) DO UPDATE SET version = excluded.version",
                    params![MODULE_ID, SCHEMA_VERSION],
                )?;
            }
            tx.commit()
        })
        .map_err(failed)?;
        Ok(Comments { db, env, lock: std::sync::Mutex::new(()) })
    }

    fn guard(&self) -> std::sync::MutexGuard<'_, ()> {
        self.lock.lock().unwrap_or_else(|p| p.into_inner())
    }

    // ---- notify list ---------------------------------------------------------------------------------

    /// The ids as registry agent ids; a bare role name counts as `role:<name>`. Anything else is refused.
    pub fn validate_targets(&self, ids: &[String]) -> Result<Vec<String>, OrchError> {
        let mut out: Vec<String> = Vec::new();
        for raw in ids {
            let id = raw.trim();
            if id.is_empty() {
                return Err(refused("a notify target is a non-blank agent id"));
            }
            let ok = if matches!(id.to_ascii_lowercase().as_str(), "owner" | "user") {
                OWNER_ENTRY.to_string()
            } else if self.env.is_registry_agent(id) || self.env.is_session(id) {
                id.to_string()
            } else if let Some(role) = id.strip_prefix("role:").map(str::trim).filter(|r| !r.is_empty()) {
                // A role is resolved at send time to whoever holds it then, so it needs no holder now.
                format!("role:{role}")
            } else if self.env.is_role(id) || self.env.is_registry_agent(&format!("role:{id}")) {
                format!("role:{id}")
            } else {
                return Err(refused(format!(
                    "'{id}' is not an agent id, a session id or a role; notify takes agent ids, session ids and `role:<name>`"
                )));
            };
            if !out.contains(&ok) {
                out.push(ok);
            }
        }
        Ok(out)
    }

    /// The project's default notify agents (setting `board.notify.<projectId>`), the ones still in the registry.
    pub fn project_defaults(&self, project_id: Option<&str>) -> Vec<String> {
        let Some(p) = project_id.filter(|p| !p.trim().is_empty()) else { return Vec::new() };
        let raw = self.db.read(|c| store::setting(c, &format!("board.notify.{p}"))).ok().flatten().unwrap_or_default();
        // The settings table holds JSON: a string value is unwrapped, anything else read as is.
        let text = serde_json::from_str::<String>(&raw).unwrap_or(raw);
        let mut out: Vec<String> = Vec::new();
        for part in text.split([',', '\n']).map(str::trim).filter(|s| !s.is_empty()) {
            if let Ok(v) = self.validate_targets(&[part.to_string()]) {
                if !out.contains(&v[0]) {
                    out.push(v[0].clone());
                }
            }
        }
        out
    }

    /// Records the creator (never edited afterwards) and the starting notify list: the explicit one, else
    /// creator + project defaults. A second call for the same ticket changes nothing.
    pub fn on_created(&self, ticket_id: &str, project_id: Option<&str>, creator: &CommentAuthor, explicit: Option<Vec<String>>) -> Result<(), OrchError> {
        let creator_agent = creator.agent_id.clone().filter(|a| self.env.is_registry_agent(a));
        let notify = match explicit {
            Some(e) => self.validate_targets(&e)?,
            None => {
                let mut v: Vec<String> = creator_agent.into_iter().collect();
                if creator.kind == CommentAuthor::OWNER {
                    v.push(OWNER_ENTRY.to_string()); // a human creator hears it in the app
                }
                for d in self.project_defaults(project_id) {
                    if !v.contains(&d) {
                        v.push(d);
                    }
                }
                v
            }
        };
        let by = creator.agent_id.clone().unwrap_or_else(|| creator.label());
        self.db
            .write(|c| {
                c.execute(
                    "INSERT OR IGNORE INTO infantry_board_ticket_meta(ticket_id, created_by, notify) VALUES (?1, ?2, ?3)",
                    params![ticket_id, by, notify.join("\n")],
                )
            })
            .map_err(failed)?;
        Ok(())
    }

    fn meta_on(c: &Connection, ticket_id: &str) -> rusqlite::Result<Option<Meta>> {
        c.query_row("SELECT created_by, notify FROM infantry_board_ticket_meta WHERE ticket_id = ?1", [ticket_id], |r| {
            Ok(Meta { created_by: r.get(0)?, notify: lines(&r.get::<_, String>(1)?) })
        })
        .optional()
    }

    pub fn meta(&self, ticket_id: &str) -> Result<Option<Meta>, OrchError> {
        self.db.read(|c| Self::meta_on(c, ticket_id)).map_err(failed)
    }

    /// Replace (`set`), add and remove, validated. A ticket with no meta row gets one (creator `unknown`).
    pub fn edit_notify(
        &self,
        ticket_id: &str,
        project_id: Option<&str>,
        set: Option<&[String]>,
        add: &[String],
        remove: &[String],
    ) -> Result<Vec<String>, OrchError> {
        let _g = self.guard();
        let current = self.meta(ticket_id)?;
        let base = match set {
            Some(s) => self.validate_targets(s)?,
            // A ticket saved over the wire has no row yet: it starts from the project's default list.
            None => current.as_ref().map(|m| m.notify.clone()).unwrap_or_else(|| self.project_defaults(project_id)),
        };
        let gone: Vec<String> = remove.iter().map(|r| r.trim()).flat_map(|r| [r.to_string(), format!("role:{r}")]).collect();        let mut next: Vec<String> = Vec::new();
        for id in base.into_iter().chain(self.validate_targets(add)?) {
            if !next.contains(&id) && !gone.contains(&id) {
                next.push(id);
            }
        }
        let text = next.join("\n");
        self.db
            .write(|c| match current {
                None => c.execute(
                    "INSERT INTO infantry_board_ticket_meta(ticket_id, created_by, notify) VALUES (?1, 'unknown', ?2)",
                    params![ticket_id, text],
                ),
                Some(_) => c.execute("UPDATE infantry_board_ticket_meta SET notify = ?1 WHERE ticket_id = ?2", params![text, ticket_id]),
            })
            .map_err(failed)?;
        Ok(next)
    }

    // ---- comments ------------------------------------------------------------------------------------

    /// Stores a comment and wakes the notify list. `title` / `project_id` are the ticket's.
    #[allow(clippy::too_many_arguments)]
    pub fn add(
        &self,
        ticket_id: &str,
        project_id: Option<&str>,
        title: &str,
        author: &CommentAuthor,
        kind: &str,
        state: &str,
        body: &str,
        wake: Option<(String, String)>,
    ) -> Result<Comment, OrchError> {
        if !KINDS.contains(&kind) {
            return Err(refused(format!("kind is one of {}", KINDS.join("|"))));
        }
        if !STATES.contains(&state) {
            return Err(refused(format!("state is one of {}", STATES.join("|"))));
        }
        if body.trim().is_empty() {
            return Err(refused("a comment needs a body"));
        }
        let _g = self.guard();
        let id = self.env.new_comment_id();
        let now = self.env.now_ms();
        let resolved = state == RESOLVED;
        self.db
            .write(|c| {
                c.execute(
                    "INSERT INTO infantry_board_comments(id, ticket_id, author_kind, author_session, author_agent, kind, body, state, created_at, resolved_at, resolved_by) \
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)",
                    params![
                        id,
                        ticket_id,
                        author.kind,
                        author.session_id,
                        author.agent_id,
                        kind,
                        body,
                        state,
                        now,
                        resolved.then_some(now),
                        resolved.then(|| author.label())
                    ],
                )
            })
            .map_err(failed)?;
        let missed = self.notify_all(ticket_id, project_id, title, &id, author, kind, state, body, wake.as_ref())?;
        if !missed.is_empty() {
            self.db
                .write(|c| c.execute("UPDATE infantry_board_comments SET undelivered = ?1 WHERE id = ?2", params![missed.join("\n"), id]))
                .map_err(failed)?;
        }
        self.get(&id)?.ok_or_else(|| failed("the stored comment vanished"))
    }

    #[allow(clippy::too_many_arguments)]
    fn notify_all(
        &self,
        ticket_id: &str,
        project_id: Option<&str>,
        title: &str,
        comment_id: &str,
        author: &CommentAuthor,
        kind: &str,
        state: &str,
        body: &str,
        wake: Option<&(String, String)>,
    ) -> Result<Vec<String>, OrchError> {
        let agents = match self.meta(ticket_id)? {
            Some(m) => m.notify,
            None => self.project_defaults(project_id),
        };
        let short = last_chars(ticket_id, 8);
        let flat: String = body.split_whitespace().collect::<Vec<_>>().join(" ");
        let preview: String = flat.chars().take(150).collect();
        let line = format!(
            "[TICKET COMMENT] {short} {title}: {kind}/{state} comment {} by {}: {preview}. Read: infantry_ticket_comments {short}",
            comment_id.chars().take(8).collect::<String>(),
            author.label()
        );
        let subject = format!("ticket comment {short}");
        let mut missed = Vec::new();
        let mut sent: Vec<String> = Vec::new();
        for entry in agents {
            if author.agent_id.as_deref() == Some(entry.as_str()) || author.session_id.as_deref() == Some(entry.as_str()) {
                continue;
            }
            if entry == OWNER_ENTRY {
                if author.kind != CommentAuthor::OWNER
                    && !self.env.notify_owner(&format!("Ticket comment {short}"), &format!("{title}: {kind} by {}: {preview}", author.label()), ticket_id, project_id)
                {
                    missed.push(entry);
                }
                continue;
            }
            // An entry is a role (resolved now), a live session, or an agent's live sessions.
            let targets: Vec<String> = if let Some(role) = entry.strip_prefix("role:") {
                self.env.live_role_sessions(role, project_id)
            } else if self.env.is_session(&entry) {
                if self.env.is_live_session(&entry) { vec![entry.clone()] } else { Vec::new() }
            } else {
                self.env.live_sessions(&entry, project_id)
            };
            let sessions: Vec<String> = targets.into_iter().filter(|s| Some(s.as_str()) != author.session_id.as_deref()).collect();
            let mut delivered = 0;
            for s in &sessions {
                if sent.contains(s) {
                    delivered += 1; // already got this comment through another entry
                } else if self.env.notify_notice(s, wake.map_or_else(|| format!("comment.{kind}"), |w| w.0.clone()).as_str(), wake.filter(|w| !w.1.is_empty()).map_or(if kind == "done" { ticket_id } else { comment_id }, |w| w.1.as_str()), &subject, &line) {
                    sent.push(s.clone());
                    delivered += 1;
                }
            }
            if delivered == 0 {
                missed.push(entry);
            }
        }
        Ok(missed)
    }

    /// A comment by id or unique prefix (`%` and `_` ignored); None for unknown or ambiguous.
    pub fn get(&self, id_or_prefix: &str) -> Result<Option<Comment>, OrchError> {
        let key: String = id_or_prefix.trim().chars().filter(|c| *c != '%' && *c != '_').collect();
        if key.is_empty() {
            return Ok(None);
        }
        let found = self
            .db
            .read(|c| {
                let mut st = c.prepare("SELECT * FROM infantry_board_comments WHERE id LIKE ?1")?;
                let v = st.query_map([format!("{key}%")], comment_of)?.collect::<rusqlite::Result<Vec<_>>>()?;
                Ok(v)
            })
            .map_err(failed)?;
        Ok(if found.len() == 1 { found.into_iter().next() } else { None })
    }

    /// Marks a comment resolved (idempotent, no mail); None for an unknown / ambiguous id.
    pub fn resolve(&self, id_or_prefix: &str, by: &CommentAuthor) -> Result<Option<Comment>, OrchError> {
        let _g = self.guard();
        let Some(c) = self.get(id_or_prefix)? else { return Ok(None) };
        if c.state != RESOLVED {
            let now = self.env.now_ms();
            self.db
                .write(|db| {
                    db.execute(
                        "UPDATE infantry_board_comments SET state = 'resolved', resolved_at = ?1, resolved_by = ?2 WHERE id = ?3",
                        params![now, by.label(), c.id],
                    )
                })
                .map_err(failed)?;
        }
        self.get(&c.id)
    }

    /// which = latestOpen | latest | first | all, optionally one kind; `limit` keeps the newest N of `all`.
    pub fn read(&self, ticket_id: &str, which: &str, kind: Option<&str>, limit: Option<i32>) -> Result<Vec<Comment>, OrchError> {
        if !WHICH.contains(&which) {
            return Err(refused(format!("which is one of {}", WHICH.join("|"))));
        }
        if kind.is_some_and(|k| !KINDS.contains(&k)) {
            return Err(refused(format!("kind is one of {}", KINDS.join("|"))));
        }
        let all: Vec<Comment> = self
            .db
            .read(|c| {
                let mut st = c.prepare("SELECT * FROM infantry_board_comments WHERE ticket_id = ?1 ORDER BY created_at, rowid")?;
                let v = st.query_map([ticket_id], comment_of)?.collect::<rusqlite::Result<Vec<_>>>()?;
                Ok(v)
            })
            .map_err(failed)?
            .into_iter()
            .filter(|c| kind.is_none_or(|k| c.kind == k))
            .collect();
        Ok(match which {
            "latestOpen" => all.into_iter().rev().find(|c| c.state == OPEN).into_iter().collect(),
            "latest" => all.into_iter().last().into_iter().collect(),
            "first" => all.into_iter().next().into_iter().collect(),
            _ => match limit.filter(|l| *l > 0) {
                Some(l) => {
                    let skip = all.len().saturating_sub(l as usize);
                    all.into_iter().skip(skip).collect()
                }
                None => all,
            },
        })
    }

    /// Did `session_id` comment on `ticket_id` at or after `since_ms`?
    pub fn commented_since(&self, ticket_id: &str, session_id: &str, since_ms: i64) -> Result<bool, OrchError> {
        self.db
            .read(|c| {
                c.prepare_cached("SELECT 1 FROM infantry_board_comments WHERE ticket_id = ?1 AND author_session = ?2 AND created_at >= ?3 LIMIT 1")?
                    .exists(params![ticket_id, session_id, since_ms])
            })
            .map_err(failed)
    }

    pub fn summary(&self, ticket_id: &str) -> Result<Summary, OrchError> {
        let all = self.read(ticket_id, "all", None, None)?;
        let open: Vec<&Comment> = all.iter().filter(|c| c.state == OPEN).collect();
        let mut undelivered: Vec<String> = Vec::new();
        for u in all.iter().flat_map(|c| c.undelivered.iter()) {
            if !undelivered.contains(u) {
                undelivered.push(u.clone());
            }
        }
        Ok(Summary { open: open.len(), latest_open: open.last().map(|c| (*c).clone()), undelivered })
    }
}

/// The last `n` characters.
pub fn last_chars(s: &str, n: usize) -> String {
    let count = s.chars().count();
    s.chars().skip(count.saturating_sub(n)).collect()
}
