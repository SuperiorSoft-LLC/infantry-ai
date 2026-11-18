// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The board's rules, in memory: lanes, places, the derived `pending` lane, renumbering, the
//! `updatedAt` stamp, and the input checks. One operation works on a [`Snapshot`] loaded inside its
//! transaction, then [`Snapshot::diff`] says what to write.
use std::collections::{BTreeMap, HashMap, HashSet};

use infantry_orchestrator_api::dto::{link_type, status, ticket_type};

use crate::store::{LinkRow, TicketRow};

pub const MAX_TITLE_LENGTH: usize = 200;
pub const MAX_DESCRIPTION_LENGTH: usize = 100_000;

/// A refusal: the request broke a rule, nothing changes.
pub type Rule<T> = Result<T, String>;

pub fn check_id(raw: &str) -> Rule<String> {
    let id = raw.trim();
    if id.is_empty() {
        return Err("expects a ticket id".into());
    }
    Ok(id.to_string())
}

pub fn check_title(raw: &str) -> Rule<String> {
    let t = raw.trim();
    if t.is_empty() {
        return Err("title is blank".into());
    }
    if t.encode_utf16().count() > MAX_TITLE_LENGTH {
        return Err(format!("title is longer than {MAX_TITLE_LENGTH} characters"));
    }
    if t.chars().any(char::is_control) {
        return Err("title contains a control character".into());
    }
    Ok(t.to_string())
}

pub fn check_description(raw: &str) -> Rule<String> {
    let d = raw.trim();
    // Kotlin counts UTF-16 units.
    if d.encode_utf16().count() > MAX_DESCRIPTION_LENGTH {
        return Err(format!("description is longer than {MAX_DESCRIPTION_LENGTH} characters"));
    }
    Ok(d.to_string())
}

pub fn check_type(raw: &str) -> Rule<String> {
    let lower = raw.trim().to_lowercase();
    let t = ticket_type::normalize(&lower).to_string();
    if !ticket_type::ALL.contains(&t.as_str()) {
        return Err(format!("ticketType '{}' is not a ticket type: {}", take(&t, 40), ticket_type::ALL.join(", ")));
    }
    Ok(t)
}

pub fn check_status(raw: &str) -> Rule<String> {
    let s = raw.trim().to_lowercase();
    if !status::ALL.contains(&s.as_str()) {
        return Err(format!("status '{}' is not a column: {}", take(&s, 40), status::ALL.join(", ")));
    }
    Ok(s)
}

/// The first `n` characters of `s` (Kotlin's `take`).
pub fn take(s: &str, n: usize) -> String {
    s.chars().take(n).collect()
}

/// The prerequisites among `ids` that hold a ticket: not done, or done as won't fix.
pub fn holders<'a>(ids: &[String], rows: &'a HashMap<String, TicketRow>) -> Vec<&'a TicketRow> {
    ids.iter().filter_map(|i| rows.get(i)).filter(|r| r.status != status::DONE || r.wont_fix).collect()
}

/// Why `holding` holds a ticket, in words; None when nothing does.
pub fn hold_reason(holding: &[&TicketRow]) -> Option<String> {
    if holding.is_empty() {
        return None;
    }
    let waiting: Vec<&str> = holding.iter().filter(|r| !r.wont_fix).map(|r| r.id.as_str()).collect();
    let declined: Vec<&str> = holding.iter().filter(|r| r.wont_fix).map(|r| r.id.as_str()).collect();
    let mut parts = Vec::new();
    if !waiting.is_empty() {
        parts.push(format!("waiting for {}", waiting.join(", ")));
    }
    if !declined.is_empty() {
        parts.push(format!("{} {} closed as won't fix", declined.join(", "), if declined.len() == 1 { "was" } else { "were" }));
    }
    Some(parts.join("; "))
}

/// `rows` in board order: column by column ([`status::ALL`] first, unknown columns after by name), then position, then id.
pub fn board_order<'a>(rows: impl IntoIterator<Item = &'a TicketRow>) -> Vec<&'a TicketRow> {
    let rank = |s: &str| status::ALL.iter().position(|x| *x == s).unwrap_or(status::ALL.len());
    let mut v: Vec<&TicketRow> = rows.into_iter().collect();
    v.sort_by(|a, b| {
        (rank(&a.status), &a.status, a.position, &a.id).cmp(&(rank(&b.status), &b.status, b.position, &b.id))
    });
    v
}

/// One operation's working copy of the board.
pub struct Snapshot {
    pub now: i64,
    pub original: HashMap<String, TicketRow>,
    pub original_links: Vec<LinkRow>,
    pub rows: HashMap<String, TicketRow>,
    pub links: Vec<LinkRow>,
    /// Tickets the request named: a new position counts as their own change.
    pub named: HashSet<String>,
    pub deleted: Vec<String>,
    added_links: Vec<LinkRow>,
    removed_links: Vec<LinkRow>,
    /// Each column's ids, top first. Known columns always exist; a stored unknown one is kept as found.
    columns: BTreeMap<usize, (String, Vec<String>)>,
}

/// What [`Snapshot::diff`] says to store.
pub struct Diff {
    pub deleted: Vec<String>,
    pub removed_links: Vec<LinkRow>,
    pub created: Vec<TicketRow>,
    pub updated: Vec<TicketRow>,
    pub added_links: Vec<LinkRow>,
}

impl Snapshot {
    pub fn new(rows: Vec<TicketRow>, links: Vec<LinkRow>, now: i64) -> Snapshot {
        let original: HashMap<String, TicketRow> = rows.iter().map(|r| (r.id.clone(), r.clone())).collect();
        let mut s = Snapshot {
            now,
            rows: original.clone(),
            original,
            original_links: links.clone(),
            links,
            named: HashSet::new(),
            deleted: Vec::new(),
            added_links: Vec::new(),
            removed_links: Vec::new(),
            columns: BTreeMap::new(),
        };
        for st in status::ALL {
            s.column_mut(st);
        }
        let mut sorted = rows;
        sorted.sort_by(|a, b| (a.position, &a.id).cmp(&(b.position, &b.id)));
        for r in sorted {
            s.column_mut(&r.status).push(r.id);
        }
        s
    }

    /// The column's id list, created at the end (insertion order, like Kotlin's LinkedHashMap) when new.
    fn column_mut(&mut self, st: &str) -> &mut Vec<String> {
        let key = match self.columns.iter().find(|(_, (name, _))| name == st) {
            Some((k, _)) => *k,
            None => {
                let k = self.columns.len();
                self.columns.insert(k, (st.to_string(), Vec::new()));
                k
            }
        };
        &mut self.columns.get_mut(&key).expect("column").1
    }

    fn column(&self, st: &str) -> Vec<String> {
        self.columns.values().find(|(n, _)| n == st).map(|(_, ids)| ids.clone()).unwrap_or_default()
    }

    pub fn require(&self, id: &str) -> Rule<TicketRow> {
        self.rows.get(id).cloned().ok_or_else(|| format!("no ticket '{}'", take(id, 80)))
    }

    pub fn create(&mut self, row: TicketRow) {
        let (id, st) = (row.id.clone(), row.status.clone());
        self.rows.insert(id.clone(), row);
        self.column_mut(&st).push(id);
    }

    pub fn delete(&mut self, id: &str) {
        let Some(row) = self.rows.remove(id) else { return };
        self.column_mut(&row.status).retain(|x| x != id);
        // The database cascades the same way.
        self.links.retain(|l| l.ticket_id != id && l.related_id != id);
        self.deleted.push(id.to_string());
    }

    /// Put `id` in column `st` at `index` (clamped; None = the bottom).
    pub fn place(&mut self, id: &str, st: &str, index: Option<i32>) {
        let row = self.rows.get(id).expect("placed row").clone();
        self.column_mut(&row.status).retain(|x| x != id);
        let target = self.column_mut(st);
        let at = index.map(|i| (i.max(0) as usize).min(target.len())).unwrap_or(target.len());
        target.insert(at, id.to_string());
        let todo = st == status::TODO;
        let mut next = row.clone();
        next.status = st.to_string();
        if !todo {
            next.queued_for = None;
            next.queued_at = None;
            // AGENT-TASKS-3: leaving todo drops the ticket from its agent's queue.
            next.queued_agent = None;
            next.queue_rank = None;
        }
        self.rows.insert(id.to_string(), next);
        // The queue the ticket left closes up.
        if let (Some(left), Some(rank), false) = (row.queued_agent.as_ref(), row.queue_rank, todo) {
            let behind: Vec<String> = self
                .rows
                .values()
                .filter(|r| r.queued_agent.as_ref() == Some(left) && r.queue_rank.unwrap_or(0) > rank)
                .map(|r| r.id.clone())
                .collect();
            for b in behind {
                let r = self.rows.get_mut(&b).expect("queued row");
                r.queue_rank = r.queue_rank.map(|q| q - 1);
            }
        }
    }

    pub fn add_link(&mut self, link: LinkRow) {
        if !self.links.iter().any(|l| same_link(l, &link)) {
            self.links.push(link.clone());
            self.added_links.push(link);
        }
    }

    pub fn remove_link(&mut self, link: &LinkRow) -> bool {
        let Some(i) = self.links.iter().position(|l| same_link(l, link)) else { return false };
        let gone = self.links.remove(i);
        self.removed_links.push(gone);
        true
    }

    /// The `blocked_by` prerequisites holding `id` right now.
    pub fn holding(&self, id: &str) -> Vec<&TicketRow> {
        let ids: Vec<String> = self
            .links
            .iter()
            .filter(|l| l.r#type == link_type::BLOCKED_BY && l.ticket_id == id)
            .map(|l| l.related_id.clone())
            .collect();
        holders(&ids, &self.rows)
    }

    /// The derived column: a held `todo` ticket goes to `pending`, a `pending` one nothing holds to `todo`.
    pub fn settle(&mut self) {
        let hold: Vec<String> = self.column(status::TODO).into_iter().filter(|i| !self.holding(i).is_empty()).collect();
        let release: Vec<String> = self.column(status::PENDING).into_iter().filter(|i| self.holding(i).is_empty()).collect();
        for i in hold {
            self.place(&i, status::PENDING, None);
        }
        for i in release {
            self.place(&i, status::TODO, None);
        }
    }

    /// Positions = index in the column, so every column is contiguous from 0.
    pub fn renumber(&mut self) {
        let cols: Vec<(String, Vec<String>)> = self.columns.values().cloned().collect();
        for (st, ids) in cols {
            for (index, id) in ids.iter().enumerate() {
                let r = self.rows.get_mut(id).expect("column row");
                if r.position != index as i32 || r.status != st {
                    r.status = st.clone();
                    r.position = index as i32;
                }
            }
        }
    }

    /// Move `updatedAt` where the ticket itself changed.
    pub fn stamp(&mut self) {
        let now = self.now;
        for (id, row) in self.rows.iter_mut() {
            let Some(b) = self.original.get(id) else { continue };
            let own = row.title != b.title
                || row.description != b.description
                || row.ticket_type != b.ticket_type
                || row.status != b.status
                || row.wont_fix != b.wont_fix
                || row.agent_id != b.agent_id
                || row.provider != b.provider
                || row.model != b.model
                || row.use_worktree != b.use_worktree
                || row.worktree_branch != b.worktree_branch
                || row.include_linked_tasks_context != b.include_linked_tasks_context
                || row.skill_ids != b.skill_ids
                || row.skills_mode != b.skills_mode
                || row.include_skills_context != b.include_skills_context
                || row.images != b.images
                || (self.named.contains(id) && row.position != b.position);
            if own {
                row.updated_at = now;
            }
        }
    }

    /// The difference from what was loaded. Deletions first, so links never name a missing ticket.
    pub fn diff(&self) -> Diff {
        let mut created = Vec::new();
        let mut updated = Vec::new();
        for (id, row) in &self.rows {
            match self.original.get(id) {
                None => created.push(row.clone()),
                Some(b) if b != row => updated.push(row.clone()),
                _ => {}
            }
        }
        created.sort_by(|a, b| a.id.cmp(&b.id));
        updated.sort_by(|a, b| a.id.cmp(&b.id));
        Diff {
            deleted: self.deleted.clone(),
            removed_links: self.removed_links.clone(),
            created,
            updated,
            added_links: self.added_links.clone(),
        }
    }
}

fn same_link(a: &LinkRow, b: &LinkRow) -> bool {
    a.ticket_id == b.ticket_id && a.related_id == b.related_id && a.r#type == b.r#type
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(id: &str, st: &str, pos: i32) -> TicketRow {
        let mut r = TicketRow::new(id.into(), id.into(), st.into(), 1);
        r.position = pos;
        r
    }

    #[test]
    fn place_renumbers_and_clamps() {
        let mut s = Snapshot::new(vec![row("a", "todo", 0), row("b", "todo", 1), row("c", "todo", 2)], vec![], 9);
        s.place("c", status::TODO, Some(0));
        s.place("a", status::DONE, Some(99));
        s.renumber();
        assert_eq!((s.rows["c"].position, s.rows["b"].position), (0, 1));
        assert_eq!((s.rows["a"].status.as_str(), s.rows["a"].position), ("done", 0));
    }

    #[test]
    fn a_blocked_todo_settles_into_pending_and_back() {
        let link = LinkRow { ticket_id: "a".into(), related_id: "b".into(), r#type: "blocked_by".into(), created_at: 0 };
        let mut s = Snapshot::new(vec![row("a", "todo", 0), row("b", "todo", 1)], vec![link], 9);
        s.settle();
        s.renumber();
        assert_eq!(s.rows["a"].status, "pending");
        assert_eq!(hold_reason(&s.holding("a")).as_deref(), Some("waiting for b"));
        s.place("b", status::DONE, None);
        s.settle();
        assert_eq!(s.rows["a"].status, "todo");
    }

    #[test]
    fn checks_word_their_refusals_like_kotlin() {
        assert_eq!(check_title("  ").unwrap_err(), "title is blank");
        assert_eq!(check_type("bug").unwrap(), "defect");
        assert!(check_status("doing").unwrap_err().starts_with("status 'doing' is not a column: backlog, todo"));
    }
}
