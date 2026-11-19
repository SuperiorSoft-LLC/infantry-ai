// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! A done ticket's worktree is removed once its branch is merged (setting `worktrees.removeAfterMerge`, default on),
//! and `worktrees_stale` reports every ticket worktree without touching one.
//!
//! The merge itself is made by the done protocol after the ticket moves to done, so removal is a periodic sweep
//! (`worktrees.sweepSeconds`), not a step of the move. A worktree with uncommitted changes, an unmerged branch or a
//! live session in it is skipped and the ticket gets one note saying why. The branch is kept. Nothing is forced.
use super::settings::SettingsUnit;
use crate::sessions::Daemon;
use infantry_board::Board as TicketBoard;
use infantry_orchestrator_api::{CommentAdd, CommentAuthor};
use infantry_projects::stale::{self, Removal, StaleEntry};
use infantry_projects::{paths, Projects};
use serde_json::Value;
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Duration;

pub const REMOVE_AFTER_MERGE: &str = "worktrees.removeAfterMerge";
pub const SWEEP_SECONDS: &str = "worktrees.sweepSeconds";
/// Default gap between sweeps; a setting, so the owner can slow it.
pub const DEFAULT_SWEEP_SECONDS: u64 = 300;
/// A sweep never removes more than this many worktrees at once (`worktrees.sweepBatch`), so a backlog drains slowly.
pub const SWEEP_BATCH: &str = "worktrees.sweepBatch";
pub const DEFAULT_SWEEP_BATCH: usize = 5;

pub struct WorktreeSweep {
    pub board: Arc<TicketBoard>,
    pub projects: Arc<Projects>,
    pub daemon: Arc<Daemon>,
    pub settings: SettingsUnit,
    pub repo: Option<PathBuf>,
    /// Tickets already told why their worktree was skipped (once per daemon run, not per sweep).
    told: Mutex<HashSet<String>>,
}

fn flag(v: Option<Value>, default: bool) -> bool {
    match v {
        Some(Value::Bool(b)) => b,
        Some(Value::String(s)) => !matches!(s.trim().to_lowercase().as_str(), "false" | "off" | "0" | "no"),
        _ => default,
    }
}

fn number(v: Option<Value>) -> Option<u64> {
    v.and_then(|v| v.as_u64().or_else(|| v.as_str().and_then(|s| s.trim().parse().ok())))
}

impl WorktreeSweep {
    pub fn new(board: Arc<TicketBoard>, projects: Arc<Projects>, daemon: Arc<Daemon>, settings: SettingsUnit, repo: Option<PathBuf>) -> Self {
        WorktreeSweep { board, projects, daemon, settings, repo, told: Mutex::new(HashSet::new()) }
    }

    pub fn enabled(&self) -> bool {
        flag(self.settings.app(REMOVE_AFTER_MERGE), true)
    }

    pub fn interval(&self) -> Duration {
        Duration::from_secs(number(self.settings.app(SWEEP_SECONDS)).filter(|n| *n > 0).unwrap_or(DEFAULT_SWEEP_SECONDS))
    }

    fn batch(&self) -> usize {
        number(self.settings.app(SWEEP_BATCH)).filter(|n| *n > 0).map(|n| n as usize).unwrap_or(DEFAULT_SWEEP_BATCH)
    }

    /// The repositories to look in, each with the base branch tickets merge into.
    fn roots(&self) -> Vec<(PathBuf, String)> {
        let mut out: Vec<(PathBuf, String)> = Vec::new();
        let mut add = |dir: &Path, base: Option<String>| {
            if self.projects.branches.common_dir(dir).is_none() {
                return;
            }
            let root = self.projects.branches.main_root(dir);
            if !out.iter().any(|(r, _)| paths::contains(r, &root) && paths::contains(&root, r)) {
                out.push((root, base.unwrap_or_else(|| "HEAD".into())));
            }
        };
        for p in self.projects.store.list() {
            let d = p.directory.trim().to_string();
            if !d.is_empty() {
                add(Path::new(&d), self.projects.store.base_branch_of(&p.id));
            }
        }
        if let Some(r) = &self.repo {
            add(r, None);
        }
        out
    }

    /// Folders where a live session works (a session that has not exited).
    fn live_dirs(&self) -> Vec<PathBuf> {
        self.daemon
            .records()
            .iter()
            .filter(|r| !matches!(r.state, infantry_protocol::session::SessionState::Exited | infantry_protocol::session::SessionState::Failed | infantry_protocol::session::SessionState::Orphaned))
            .flat_map(|r| [r.worktree_path.clone(), Some(r.working_dir.clone())])
            .flatten()
            .filter(|p| !p.trim().is_empty())
            .map(PathBuf::from)
            .collect()
    }

    /// Every ticket worktree with its ticket, status, merged, dirty, live and size. Read-only.
    pub fn report(&self, with_size: bool) -> Vec<StaleEntry> {
        let tickets: HashMap<String, (String, String)> = self
            .board
            .list(None, None)
            .unwrap_or_default()
            .into_iter()
            .filter_map(|t| t.worktree_branch.clone().map(|b| (b, (t.id.clone(), t.status.clone()))))
            .collect();
        let live = self.live_dirs();
        let mut all = Vec::new();
        for (root, base) in self.roots() {
            for l in stale::list(&self.projects.git, &root) {
                let mut e = stale::inspect(&self.projects.git, &root, &base, &l, with_size);
                if let Some((id, status)) = e.branch.as_ref().and_then(|b| tickets.get(b)) {
                    e.ticket_id = Some(id.clone());
                    e.ticket_status = Some(status.clone());
                }
                e.live = live.iter().any(|d| paths::contains(&l.path, d));
                all.push(e);
            }
        }
        all
    }

    fn note(&self, ticket_id: &str, body: &str) {
        let _ = self.board.comment_add(CommentAdd { ticket_id: ticket_id.into(), body: body.into(), kind: Some("note".into()), state: None, author: CommentAuthor::daemon() });
    }

    /// One pass: remove (at most a batch of) done tickets' merged worktrees. Returns how many were removed.
    pub fn sweep(&self) -> usize {
        if !self.enabled() {
            return 0;
        }
        let tickets: Vec<_> = self.board.list(None, None).unwrap_or_default().into_iter().filter(|t| t.status == "done" && t.worktree_branch.is_some()).collect();
        let live = self.live_dirs();
        let (mut removed, batch) = (0usize, self.batch());
        for (root, base) in self.roots() {
            let listed = stale::list(&self.projects.git, &root);
            for t in &tickets {
                if removed >= batch {
                    return removed;
                }
                let Some(branch) = t.worktree_branch.as_deref() else { continue };
                let Some(l) = listed.iter().find(|l| l.branch.as_deref() == Some(branch)) else { continue };
                let is_live = live.iter().any(|d| paths::contains(&l.path, d));
                match stale::remove_if_merged(&self.projects.git, &root, &l.path, branch, &base, is_live) {
                    Removal::Removed => {
                        removed += 1;
                        self.note(&t.id, &format!("[AUTOMATIC] worktree removed after the merge: {} (branch '{branch}' kept)", l.path.display()));
                    }
                    Removal::Skipped(why) => {
                        // An unmerged branch is the normal wait before the merge; only a real blocker is told, once.
                        if !why.contains("not merged") && self.told.lock().unwrap_or_else(|e| e.into_inner()).insert(t.id.clone()) {
                            self.note(&t.id, &format!("[AUTOMATIC] worktree {} was NOT removed after the merge: {why}", l.path.display()));
                        }
                    }
                }
            }
        }
        removed
    }

    /// Run `sweep` on its interval for the daemon's life (a blocking thread: it runs git).
    pub fn spawn(self: Arc<Self>) {
        std::thread::spawn(move || loop {
            std::thread::sleep(self.interval());
            self.sweep();
        });
    }
}

impl WorktreeSweep {
    /// The `worktrees` frame's `stale` answer: the report as a JSON array, largest first.
    pub fn report_json(&self) -> String {
        let mut rows = self.report(true);
        rows.sort_by(|a, b| b.size_bytes.cmp(&a.size_bytes));
        serde_json::to_string(&rows).unwrap_or_else(|_| "[]".into())
    }
}
