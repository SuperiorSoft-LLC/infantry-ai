// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The board's operations: list, get, save, move, delete. Each write loads the board inside ONE
//! immediate (write-first) transaction, applies the request and the rules in memory, writes only what
//! changed plus its `ticket_events`, commits, and then, still under the board lock so pushes reach
//! clients in commit order, hands the pushes to the caller's `publish`.
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use infantry_core::db::{Db, DbError};
use infantry_orchestrator_api::dto::{skills_mode, status, Ticket, TicketMove, TicketSave};
use infantry_orchestrator_api::{BoardAnswer, OrchError, TicketAnswer};
use infantry_protocol::Envelope;
use rusqlite::{Connection, TransactionBehavior};

use crate::env::{normalize_role, BoardEnv};
use crate::rules::{self, board_order, take, Snapshot};
use crate::store::{self, TicketRow};
use crate::views::{views, CONTEXT_LIMIT_MAX, CONTEXT_LIMIT_MIN};

/// The wire frame types the board answers with and pushes.
pub const TICKET: &str = "ticket";
pub const TICKETS: &str = "tickets";

/// Effort levels, lowest first (`EffortLevels.ALL`).
pub const EFFORT_LEVELS: [&str; 5] = ["low", "medium", "high", "xhigh", "max"];

/// One ticket as a `ticket` frame, every field present.
pub fn ticket_envelope(t: &Ticket) -> Envelope {
    Envelope::new(TICKET, serde_json::to_string(t).expect("ticket json"))
}

/// The board as a `tickets` frame, in board order.
pub fn tickets_envelope(ts: &[Ticket]) -> Envelope {
    Envelope::new(TICKETS, serde_json::to_string(ts).expect("tickets json"))
}

fn failed(e: impl std::fmt::Display) -> OrchError {
    OrchError::Failed(e.to_string())
}

impl From<DbErrorWrap> for OrchError {
    fn from(e: DbErrorWrap) -> Self {
        failed(e.0)
    }
}
struct DbErrorWrap(DbError);

/// What one committed operation did.
struct Outcome {
    board: Vec<Ticket>,
    changed: Vec<String>,
    deleted: Vec<String>,
}

pub struct Board {
    db: Arc<Db>,
    env: Arc<dyn BoardEnv>,
    lock: Mutex<()>,
}

impl Board {
    pub fn new(db: Arc<Db>, env: Arc<dyn BoardEnv>) -> Board {
        Board { db, env, lock: Mutex::new(()) }
    }

    fn guard(&self) -> std::sync::MutexGuard<'_, ()> {
        self.lock.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// The whole board's views, read in one transaction.
    fn read_views(&self) -> Result<(HashMap<String, TicketRow>, HashMap<String, Ticket>), OrchError> {
        let (rows, links, runs) = self
            .db
            .read(|c| {
                let tx = c.unchecked_transaction()?;
                let out = (store::all_tickets(&tx)?, store::all_links(&tx)?, store::all_runs(&tx)?);
                tx.finish()?;
                Ok(out)
            })
            .map_err(DbErrorWrap)?;
        let rows: HashMap<String, TicketRow> = rows.into_iter().map(|r| (r.id.clone(), r)).collect();
        let v = views(self.env.as_ref(), &rows, &links, &runs);
        Ok((rows, v))
    }

    /// The board in board order; `project_id` / `epic_id` filter (both = both must match).
    pub fn list(&self, project_id: Option<&str>, epic_id: Option<&str>) -> Result<Vec<Ticket>, OrchError> {
        let _g = self.guard();
        let (rows, mut v) = self.read_views()?;
        Ok(board_order(rows.values())
            .into_iter()
            .filter_map(|r| v.remove(&r.id))
            .filter(|t| project_id.is_none() || t.project_id.as_deref() == project_id)
            .filter(|t| epic_id.is_none() || t.epic_id.as_deref() == epic_id)
            .collect())
    }

    pub fn get(&self, id: &str) -> Result<Ticket, OrchError> {
        let wanted = rules::check_id(id).map_err(OrchError::Refused)?;
        let _g = self.guard();
        let (_, mut v) = self.read_views()?;
        v.remove(&wanted).ok_or_else(|| OrchError::Refused(format!("no ticket '{}'", take(&wanted, 80))))
    }

    /// One operation: load, apply `op`, settle, renumber, stamp, write, record events; all in one transaction.
    fn commit(&self, op: impl FnOnce(&mut Snapshot, &Connection) -> rules::Rule<()>) -> Result<Outcome, OrchError> {
        let env = self.env.clone();
        let res = self
            .db
            .write(|c| {
                let tx = c.transaction_with_behavior(TransactionBehavior::Immediate)?;
                let rows = store::all_tickets(&tx)?;
                let links = store::all_links(&tx)?;
                let runs = store::all_runs(&tx)?;
                let mut s = Snapshot::new(rows, links, env.now_ms());
                let before = views(env.as_ref(), &s.original, &s.original_links, &runs);
                if let Err(refusal) = op(&mut s, &tx) {
                    return Ok(Err(refusal)); // dropped tx = rollback
                }
                s.settle();
                s.renumber();
                s.stamp();
                let d = s.diff();
                for id in &d.deleted {
                    store::delete_ticket(&tx, id)?;
                }
                for l in &d.removed_links {
                    store::delete_link(&tx, l)?;
                }
                for r in &d.created {
                    store::insert_ticket(&tx, r)?;
                }
                for r in &d.updated {
                    store::update_ticket(&tx, r)?;
                }
                for l in &d.added_links {
                    store::insert_link(&tx, l)?;
                }
                let runs_after: Vec<_> = runs.into_iter().filter(|r| !s.deleted.contains(&r.ticket_id)).collect();
                let mut after = views(env.as_ref(), &s.rows, &s.links, &runs_after);
                let board: Vec<Ticket> =
                    board_order(s.rows.values()).into_iter().filter_map(|r| after.remove(&r.id)).collect();
                let changed: Vec<String> =
                    board.iter().filter(|t| before.get(&t.id) != Some(*t)).map(|t| t.id.clone()).collect();
                // BOARD-API-2: the typed events, in the same transaction as the change.
                for t in &board {
                    let was = before.get(&t.id).map(|b| b.status.as_str());
                    let kind = match was {
                        None => Some("created"),
                        Some(w) if w != t.status => Some(match t.status.as_str() {
                            status::IN_PROGRESS => "started",
                            status::DONE => "done",
                            _ => "moved",
                        }),
                        Some(_) if changed.contains(&t.id) => Some("updated"),
                        _ => None,
                    };
                    if let Some(kind) = kind {
                        store::insert_event(&tx, &t.id, kind, was, Some(&t.status), s.now)?;
                    }
                }
                for id in &s.deleted {
                    store::insert_event(&tx, id, "deleted", before.get(id).map(|b| b.status.as_str()), None, s.now)?;
                }
                tx.commit()?;
                Ok(Ok(Outcome { board, changed, deleted: s.deleted.clone() }))
            })
            .map_err(DbErrorWrap)?;
        res.map_err(OrchError::Refused)
    }

    fn answer(&self, primary: &str, o: Outcome, publish: &dyn Fn(Envelope)) -> TicketAnswer {
        let by_id: HashMap<&str, &Ticket> = o.board.iter().map(|t| (t.id.as_str(), t)).collect();
        let ticket = (*by_id.get(primary).expect("primary ticket")).clone();
        if o.changed.is_empty() {
            return TicketAnswer { ticket, pushed: false };
        }
        let order = o.changed.iter().filter(|i| *i == primary).chain(o.changed.iter().filter(|i| *i != primary));
        for id in order {
            publish(ticket_envelope(by_id[id.as_str()]));
        }
        TicketAnswer { ticket, pushed: o.changed.iter().any(|i| i == primary) }
    }

    fn board_answer(&self, o: Outcome, publish: &dyn Fn(Envelope)) -> BoardAnswer {
        if o.changed.is_empty() && o.deleted.is_empty() {
            return BoardAnswer { tickets: o.board, pushed: false };
        }
        publish(tickets_envelope(&o.board));
        BoardAnswer { tickets: o.board, pushed: true }
    }

    /// `ticket_save`: create (no id) or edit.
    pub fn save(&self, req: TicketSave, publish: &dyn Fn(Envelope)) -> Result<TicketAnswer, OrchError> {
        let _g = self.guard();
        let env = self.env.clone();
        let editing = req.id.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_string);
        let refuse = OrchError::Refused;
        let title = req.title.as_deref().map(rules::check_title).transpose().map_err(refuse)?;
        let description = req.description.as_deref().map(rules::check_description).transpose().map_err(refuse)?;
        let clear_type = req.ticket_type.as_deref().is_some_and(|t| t.trim().is_empty());
        let ttype = req
            .ticket_type
            .as_deref()
            .filter(|t| !t.trim().is_empty())
            .map(rules::check_type)
            .transpose()
            .map_err(refuse)?;
        if editing.is_none() && title.is_none() {
            return Err(refuse("title is required to create a ticket".into()));
        }
        let create_status = match req.status.as_deref().map(|s| s.trim().to_lowercase()).filter(|s| !s.is_empty()) {
            None => status::TODO.to_string(),
            Some(w) => {
                if editing.is_some() {
                    return Err(refuse("status is set on a create only; move a ticket with ticket_move".into()));
                }
                if w != status::TODO && w != status::BACKLOG {
                    return Err(refuse(format!("a new ticket enters {} or {}, not '{}'", status::TODO, status::BACKLOG, take(&w, 40))));
                }
                w
            }
        };
        let existing = match &editing {
            None => None,
            Some(w) => Some(
                self.db
                    .read(|c| store::get_ticket(c, w))
                    .map_err(DbErrorWrap)?
                    .ok_or_else(|| refuse(format!("no ticket '{}' (ticket_save creates a ticket only when it carries no id)", take(w, 80))))?,
            ),
        };
        let ex = existing.as_ref();
        let agent_id = match &req.agent_id {
            Some(a) => Some(a.trim()).filter(|s| !s.is_empty()).map(normalize_role_agent_id),
            None => ex.and_then(|e| e.agent_id.clone()),
        };
        self.validate_agent_id(agent_id.as_deref(), req.agent_id.is_some()).map_err(refuse)?;
        let trimmed = |v: &Option<String>, keep: Option<String>| match v {
            None => keep,
            Some(s) => Some(s.trim().to_string()).filter(|s| !s.is_empty()),
        };
        let provider = match &req.provider {
            None => ex.and_then(|e| e.provider.clone()),
            Some(p) => Some(p.trim().to_lowercase()).filter(|s| !s.is_empty()),
        };
        let model = trimmed(&req.model, ex.and_then(|e| e.model.clone()));
        let project_id = req
            .project_id
            .as_deref()
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_string)
            .or_else(|| ex.and_then(|e| e.project_id.clone()));
        let epic_id = trimmed(&req.epic_id, ex.and_then(|e| e.epic_id.clone()));
        let use_worktree = req.use_worktree.or(ex.and_then(|e| e.use_worktree));
        let worktree_branch = trimmed(&req.worktree_branch, ex.and_then(|e| e.worktree_branch.clone()));
        let include_linked = req.include_linked_tasks_context.or(ex.and_then(|e| e.include_linked_tasks_context));
        let skill_ids: Vec<String> = match &req.skill_ids {
            Some(ids) => ids.iter().map(|s| s.trim().to_string()).collect(),
            None => ex.map(|e| e.skill_ids.clone()).unwrap_or_default(),
        };
        if req.skill_ids.is_some() {
            self.require_known_skills(&skill_ids)?;
        }
        let skills_mode = match &req.skills_mode {
            Some(m) => {
                let v = m.trim().to_lowercase();
                if v != skills_mode::MERGE && v != skills_mode::REPLACE {
                    return Err(refuse(format!("skillsMode must be merge or replace, was '{}'", take(m, 40))));
                }
                v
            }
            None => ex.map(|e| e.skills_mode.clone()).unwrap_or_else(|| skills_mode::MERGE.into()),
        };
        let include_skills = req.include_skills_context.or(ex.and_then(|e| e.include_skills_context));
        let images = match &req.images {
            Some(list) => env.normalize_images(project_id.as_deref(), list).map_err(refuse)?,
            None => ex.map(|e| e.images.clone()).unwrap_or_default(),
        };
        let weight = match req.weight {
            None => ex.and_then(|e| e.weight),
            Some(0) => None,
            Some(w) if (1..=5).contains(&w) => Some(w),
            Some(w) => return Err(refuse(format!("weight must be 1..5 (or 0 to clear), was {w}"))),
        };
        let context_limit = match req.context_limit {
            None => ex.and_then(|e| e.context_limit),
            Some(0) => None,
            Some(v) if (CONTEXT_LIMIT_MIN..=CONTEXT_LIMIT_MAX).contains(&v) => Some(v),
            Some(v) => {
                return Err(refuse(format!(
                    "context limit {v} is out of range: {CONTEXT_LIMIT_MIN}..{CONTEXT_LIMIT_MAX} tokens (e.g. 200000 or 200k)"
                )))
            }
        };
        let runner = match &req.runner {
            None => ex.and_then(|e| e.runner.clone()),
            Some(r) => {
                let v = r.trim().to_lowercase();
                if v.is_empty() || v == "none" {
                    None
                } else {
                    runner_problem(&v).map_or(Ok(()), |m| Err(refuse(m)))?;
                    Some(v)
                }
            }
        };
        let effort = match &req.effort {
            None => ex.and_then(|e| e.effort.clone()),
            Some(raw) => parse_effort(raw).map_err(refuse)?,
        };
        let mcp = match &req.mcp {
            None => ex.and_then(|e| e.mcp.clone()),
            Some(m) => {
                if let Some(p) = m.problem() {
                    return Err(refuse(p));
                }
                (!m.require.is_empty()).then(|| m.to_stored())
            }
        };
        self.validate_executor_pins(agent_id.as_deref(), provider.as_deref(), model.as_deref()).map_err(refuse)?;

        let mut primary = editing.clone().unwrap_or_default();
        let outcome = self.commit(|s, _| {
            match &editing {
                None => {
                    primary = new_id(env.as_ref(), s)?;
                    let mut r = TicketRow::new(primary.clone(), title.clone().expect("create title"), create_status.clone(), s.now);
                    r.description = description.clone().unwrap_or_default();
                    r.ticket_type = ttype.clone();
                    r.agent_id = agent_id.clone();
                    r.provider = provider.clone();
                    r.model = model.clone();
                    r.use_worktree = use_worktree;
                    r.worktree_branch = worktree_branch.clone();
                    r.include_linked_tasks_context = include_linked;
                    r.skill_ids = skill_ids.clone();
                    r.skills_mode = skills_mode.clone();
                    r.include_skills_context = include_skills;
                    r.project_id = project_id.clone();
                    r.epic_id = epic_id.clone();
                    r.images = images.clone();
                    r.weight = weight;
                    r.context_limit = context_limit;
                    r.effort = effort.clone();
                    r.runner = runner.clone();
                    r.mcp = mcp.clone();
                    s.create(r);
                }
                Some(id) => {
                    let mut r = s.require(id)?;
                    let agent_changed = agent_id != r.agent_id;
                    if let Some(t) = &title {
                        r.title = t.clone();
                    }
                    r.project_id = project_id.clone();
                    r.epic_id = epic_id.clone();
                    if let Some(d) = &description {
                        r.description = d.clone();
                    }
                    if clear_type {
                        r.ticket_type = None;
                    } else if let Some(t) = &ttype {
                        r.ticket_type = Some(t.clone());
                    }
                    r.agent_id = agent_id.clone();
                    r.provider = provider.clone();
                    r.model = model.clone();
                    r.use_worktree = use_worktree;
                    r.worktree_branch = worktree_branch.clone();
                    r.include_linked_tasks_context = include_linked;
                    r.skill_ids = skill_ids.clone();
                    r.skills_mode = skills_mode.clone();
                    r.include_skills_context = include_skills;
                    r.images = images.clone();
                    r.weight = weight;
                    r.context_limit = context_limit;
                    r.effort = effort.clone();
                    r.runner = runner.clone();
                    r.mcp = mcp.clone();
                    if agent_changed {
                        r.queued_for = None;
                        r.queued_at = None;
                    }
                    s.rows.insert(id.clone(), r);
                }
            }
            Ok(())
        })?;
        Ok(self.answer(&primary, outcome, publish))
    }

    /// `ticket_move`. A move that would START a ticket (into in_progress) needs the hand-out, not built yet.
    pub fn move_lane(&self, req: TicketMove, publish: &dyn Fn(Envelope)) -> Result<TicketAnswer, OrchError> {
        let _g = self.guard();
        let refuse = OrchError::Refused;
        let id = rules::check_id(&req.id).map_err(refuse)?;
        let target_status = req.status.as_deref().map(rules::check_status).transpose().map_err(refuse)?;
        let position = req.position;
        if let Some(p) = position.filter(|p| *p < 0) {
            return Err(refuse(format!("position must be 0 or more, was {p}")));
        }
        if target_status.as_deref() == Some(status::IN_PROGRESS) {
            let current = self
                .db
                .read(|c| store::get_ticket(c, &id))
                .map_err(DbErrorWrap)?
                .ok_or_else(|| refuse(format!("no ticket '{}'", take(&id, 80))))?;
            if current.status != status::IN_PROGRESS || position.is_none() {
                return Err(OrchError::Unsupported(
                    "starting a ticket (a move into in_progress) needs the hand-out, which this board does not run yet".into(),
                ));
            }
        }
        let wont_fix_req = req.wont_fix;
        let outcome = self.commit(|s, _| {
            let row = s.require(&id)?;
            let target = target_status.clone().unwrap_or_else(|| row.status.clone());
            let holding: Vec<TicketRow> = s.holding(&id).into_iter().cloned().collect();
            if target == status::PENDING && row.status != status::PENDING && holding.is_empty() {
                return Err(format!(
                    "ticket {id} has no open prerequisite: pending is set by the daemon while a blocked_by prerequisite is open, never by hand"
                ));
            }
            if row.status == status::PENDING && target != status::PENDING && target != status::BACKLOG && !holding.is_empty() {
                let refs: Vec<&TicketRow> = holding.iter().collect();
                return Err(format!(
                    "ticket {id} is held ({}): a held ticket leaves pending by itself, once nothing holds it",
                    rules::hold_reason(&refs).unwrap_or_default()
                ));
            }
            let wont_fix = match wont_fix_req {
                Some(true) => {
                    if target != status::DONE {
                        return Err("wontFix closes a ticket as done: send status done with it".into());
                    }
                    true
                }
                _ if target != status::DONE => false,
                Some(false) => false,
                None => row.status == status::DONE && row.wont_fix,
            };
            if target != row.status || position.is_some() {
                s.place(&id, &target, position);
            }
            if let Some(r) = s.rows.get_mut(&id) {
                r.wont_fix = wont_fix;
            }
            s.named.insert(id.clone());
            Ok(())
        })?;
        Ok(self.answer(&id, outcome, publish))
    }

    /// `ticket_delete`: the ticket and its links (and runs) go; whatever it held is released.
    pub fn delete(&self, id: &str, publish: &dyn Fn(Envelope)) -> Result<BoardAnswer, OrchError> {
        let _g = self.guard();
        let wanted = rules::check_id(id).map_err(OrchError::Refused)?;
        let outcome = self.commit(|s, _| {
            s.require(&wanted)?;
            s.delete(&wanted);
            Ok(())
        })?;
        Ok(self.board_answer(outcome, publish))
    }

    fn require_known_skills(&self, ids: &[String]) -> Result<(), OrchError> {
        let mut seen = std::collections::HashSet::new();
        if let Some(d) = ids.iter().find(|i| !seen.insert(i.as_str())) {
            return Err(OrchError::Refused(format!("skillIds: skill '{}' is listed twice", take(d, 80))));
        }
        let unknown = self.db.read(|c| store::unknown_skills(c, ids)).map_err(DbErrorWrap)?;
        if !unknown.is_empty() {
            let list: Vec<String> = unknown.iter().map(|u| format!("'{}'", take(u, 80))).collect();
            return Err(OrchError::Refused(format!("skillIds: unknown skill {}", list.join(", "))));
        }
        Ok(())
    }

    fn ticket_profile(&self, agent_id: &str) -> Option<crate::env::BoardAgent> {
        match agent_id.strip_prefix("role:") {
            Some(role) => self.env.agents().into_iter().find(|a| a.role.as_deref() == Some(role)),
            None => self.env.agent(agent_id),
        }
    }

    fn validate_agent_id(&self, agent_id: Option<&str>, check_catalog: bool) -> rules::Rule<()> {
        let Some(a) = agent_id else { return Ok(()) };
        if check_catalog {
            let role = match a.strip_prefix("role:") {
                Some(r) => Some(r.to_string()),
                None => self.ticket_profile(a).and_then(|p| p.role),
            };
            if let Some(m) = self.env.role_refusal(role.as_deref()) {
                return Err(m);
            }
        }
        if let Some(role) = a.strip_prefix("role:") {
            let ids = self.env.role_ids();
            if !role_pattern(role) || (check_catalog && !ids.iter().any(|i| i == role)) {
                return Err(format!(
                    "agentId '{a}' must be a profile id or role:<canonical role id>; valid role ids: {}",
                    ids.join(", ")
                ));
            }
            return Ok(());
        }
        if self.env.agent(a).is_none() {
            return Err(format!("no agent profile '{}' (agent_list shows the profiles)", take(a, 80)));
        }
        Ok(())
    }

    fn validate_executor_pins(&self, agent_id: Option<&str>, provider: Option<&str>, model: Option<&str>) -> rules::Rule<()> {
        let profile = agent_id.and_then(|a| self.ticket_profile(a));
        let profile_provider = profile.as_ref().and_then(|p| p.provider.clone());
        let sessions_default = if self.env.sessions_on() { self.env.default_provider() } else { None };
        let profile_model = profile.as_ref().and_then(|p| p.model.clone()).filter(|_| {
            provider.is_none() || provider.map(str::to_string) == profile_provider.clone().or(sessions_default.clone())
        });
        if provider.is_none() && model.is_none() && profile_provider.is_none() && profile_model.is_none() {
            return Ok(());
        }
        if !self.env.sessions_on() {
            return Err("ticket executor validation is unavailable".into());
        }
        let p = provider.map(str::to_string).or(profile_provider.clone());
        let m = model.map(str::to_string).or(profile_model);
        if let Some(why) = self.env.check_spawn(p.as_deref(), m.as_deref()) {
            return Err(why);
        }
        if let Some(model) = model {
            let pid = provider.map(str::to_string).or(profile_provider).or(self.env.default_provider()).unwrap_or_default();
            if let Some(known) = self.env.provider(&pid).and_then(|f| f.known_models) {
                if !known.iter().any(|k| k == model) {
                    let shown: Vec<&str> = known.iter().take(20).map(String::as_str).collect();
                    return Err(format!("unknown model '{}' for provider '{pid}' (it lists: {})", take(model, 80), shown.join(", ")));
                }
            }
        }
        Ok(())
    }
}

fn normalize_role_agent_id(a: &str) -> String {
    match a.strip_prefix("role:") {
        Some(r) => format!("role:{}", normalize_role(r)),
        None => a.to_string(),
    }
}

/// `RoleIds.PATTERN`: `[a-z][a-z0-9-]{0,31}`.
fn role_pattern(r: &str) -> bool {
    let mut cs = r.chars();
    matches!(cs.next(), Some('a'..='z'))
        && r.len() <= 32
        && cs.all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-')
}

/// `TicketRunner.problem`: `[A-Za-z0-9][A-Za-z0-9._-]{0,39}`.
fn runner_problem(v: &str) -> Option<String> {
    let mut cs = v.chars();
    let ok = matches!(cs.next(), Some(c) if c.is_ascii_alphanumeric())
        && v.chars().count() <= 40
        && cs.all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'));
    (!ok).then(|| {
        format!(
            "runner '{}' must be local, any or a runner name (letters, digits, . _ -, at most 40 characters)",
            take(v, 40)
        )
    })
}

/// `EffortLevels.parse`: blank / off / none / default clear it; a level in any case sets it.
fn parse_effort(raw: &str) -> Result<Option<String>, String> {
    let t = raw.trim().to_lowercase();
    if t.is_empty() || t == "off" || t == "none" || t == "default" {
        return Ok(None);
    }
    if EFFORT_LEVELS.contains(&t.as_str()) {
        Ok(Some(t))
    } else {
        Err(format!("unknown effort '{}': one of [{}]", take(&t, 40), EFFORT_LEVELS.join(", ")))
    }
}

fn new_id(env: &dyn BoardEnv, s: &Snapshot) -> rules::Rule<String> {
    for _ in 0..10 {
        let c = env.new_ticket_id();
        if !s.rows.contains_key(&c) && !s.deleted.contains(&c) {
            return Ok(c);
        }
    }
    Err("could not find a free ticket id".into())
}
