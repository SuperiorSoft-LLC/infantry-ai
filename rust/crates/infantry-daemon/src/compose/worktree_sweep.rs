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
pub const REMOVE_BUILD_DIRS: &str = "worktrees.removeBuildDirs";
pub const SWEEP_SECONDS: &str = "worktrees.sweepSeconds";
/// Default gap between sweeps; a setting, so the owner can slow it.
pub const DEFAULT_SWEEP_SECONDS: u64 = 300;
/// A sweep never removes more than this many worktrees at once (`worktrees.sweepBatch`), so a backlog drains slowly.
pub const SWEEP_BATCH: &str = "worktrees.sweepBatch";
pub const DEFAULT_SWEEP_BATCH: usize = 5;
/// Gap between build-output sweeps (`worktrees.buildSweepSeconds`): a finished ticket's `target` folders go within this.
pub const BUILD_SWEEP_SECONDS: &str = "worktrees.buildSweepSeconds";
pub const DEFAULT_BUILD_SWEEP_SECONDS: u64 = 20;

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

    /// `worktrees.removeBuildDirs` (default on): a done ticket's `rust/target` etc. are deleted by the sweep.
    fn removes_build_dirs(&self) -> bool {
        flag(self.settings.app(REMOVE_BUILD_DIRS), true)
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
            .filter_map(|t| t.work_branch().map(|b| (b.to_string(), (t.id.clone(), t.status.clone()))))
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
        let _ = self.board.comment_add(CommentAdd { ticket_id: ticket_id.into(), body: body.into(), kind: Some("note".into()), state: None, author: CommentAuthor::daemon(), wake_kind: None, wake_key: None });
    }

    /// One pass: remove (at most a batch of) done tickets' merged worktrees. Returns how many were removed.
    pub fn sweep(&self) -> usize {
        let tickets: Vec<_> = self.board.list(None, None).unwrap_or_default().into_iter().filter(|t| t.status == "done" && t.work_branch().is_some()).collect();
        let live = self.live_dirs();
        let (mut removed, batch, enabled, clean_builds) = (0usize, self.batch(), self.enabled(), self.removes_build_dirs());
        for (root, base) in self.roots() {
            let listed = stale::list(&self.projects.git, &root);
            for t in &tickets {
                let Some(branch) = t.work_branch() else { continue };
                let Some(l) = listed.iter().find(|l| l.branch.as_deref() == Some(branch)) else { continue };
                let is_live = live.iter().any(|d| paths::contains(&l.path, d));
                // A done ticket's build output is freed even when the worktree is kept.
                if clean_builds && !is_live {
                    self.clean_one(&t.id, &l.path);
                }
                if !enabled {
                    continue;
                }
                if removed >= batch {
                    return removed;
                }
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

    /// Delete one worktree's build output; a folder that stays locked is logged loudly and noted on the ticket (once).
    fn clean_one(&self, ticket_id: &str, path: &Path) {
        let r = stale::remove_build_dirs(path);
        if r.freed > 0 {
            eprintln!("[infantry] build output removed from {} ({} MB freed, ticket {ticket_id})", path.display(), r.freed / (1024 * 1024));
            self.note(ticket_id, &format!("[AUTOMATIC] build output removed from {} ({} MB freed)", path.display(), r.freed / (1024 * 1024)));
        }
        for (dir, why) in &r.failed {
            eprintln!("[infantry] WARNING: could not delete build folder {} after {} tries: {why} (ticket {ticket_id}); disk space is NOT freed", dir.display(), stale::REMOVE_TRIES);
            if self.told.lock().unwrap_or_else(|e| e.into_inner()).insert(format!("build:{}", dir.display())) {
                self.note(ticket_id, &format!("[AUTOMATIC] WARNING: build folder {} could not be deleted ({why}); something holds a file open. Close it and the next sweep deletes it.", dir.display()));
            }
        }
    }

    /// Delete build output of every worktree whose ticket is done, or whose ticket is gone from the board (removed
    /// without a merge). Cheap when nothing is there. Runs at daemon start and every `worktrees.buildSweepSeconds`.
    pub fn sweep_builds(&self) -> u64 {
        if !self.removes_build_dirs() {
            return 0;
        }
        let tickets = self.board.list(None, None).unwrap_or_default();
        let by_branch: HashMap<String, (String, String)> = tickets.iter().filter_map(|t| t.work_branch().map(|b| (b.to_string(), (t.id.clone(), t.status.clone())))).collect();
        let live = self.live_dirs();
        let mut freed = 0u64;
        for (root, _) in self.roots() {
            for l in stale::list(&self.projects.git, &root) {
                let Some(branch) = l.branch.as_deref() else { continue };
                if live.iter().any(|d| paths::contains(&l.path, d)) {
                    continue;
                }
                let id = match by_branch.get(branch) {
                    Some((id, status)) if status == "done" => id.clone(),
                    Some(_) => continue,
                    // A ticket branch with no ticket on the board: the ticket was removed.
                    None if branch.starts_with("ticket/") => branch.trim_start_matches("ticket/").chars().take(8).collect::<String>(),
                    None => continue,
                };
                let before = stale::BUILD_DIRS.iter().any(|d| l.path.join(d).is_dir());
                if before {
                    if by_branch.contains_key(branch) {
                        self.clean_one(&id, &l.path);
                    } else {
                        let r = stale::remove_build_dirs(&l.path);
                        freed += r.freed;
                        eprintln!("[infantry] build output removed from {} of removed ticket {id} ({} MB freed)", l.path.display(), r.freed / (1024 * 1024));
                        for (dir, why) in &r.failed {
                            eprintln!("[infantry] WARNING: could not delete build folder {} after {} tries: {why}", dir.display(), stale::REMOVE_TRIES);
                        }
                    }
                }
            }
        }
        freed
    }

    /// Run the sweeps for the daemon's life (blocking threads: they run git): build output at start and every
    /// `worktrees.buildSweepSeconds`, whole worktrees on `worktrees.sweepSeconds`.
    pub fn spawn(self: Arc<Self>) {
        let b = self.clone();
        std::thread::spawn(move || loop {
            b.sweep_builds();
            let secs = number(b.settings.app(BUILD_SWEEP_SECONDS)).filter(|n| *n > 0).unwrap_or(DEFAULT_BUILD_SWEEP_SECONDS);
            std::thread::sleep(Duration::from_secs(secs));
        });
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

/// PIPE-CLOSE-1: the board's one-call close asks to release one ticket's worktree: removed only when its branch is
/// merged into the project's base branch and it is clean. `dry_run` only checks. Ok(None) = no such worktree,
/// Err(why) = kept. The same rules as the sweep's `remove_if_merged`; nothing is forced.
pub fn release_ticket_worktree(projects: &Projects, repo: &Path, req: &infantry_orchestrator_api::WorkspaceRelease) -> Result<Option<String>, String> {
    let root = projects.branches.main_root(repo);
    let base = req
        .base
        .clone()
        .filter(|b| !b.trim().is_empty())
        .or_else(|| req.project_id.as_deref().and_then(|p| projects.store.base_branch_of(p)))
        .unwrap_or_else(|| "HEAD".into());
    let git = &projects.git;
    let Some(l) = stale::list(git, &root).into_iter().find(|l| l.branch.as_deref() == Some(req.branch.as_str())) else { return Ok(None) };
    let shown = l.path.to_string_lossy().into_owned();
    if req.dry_run {
        if !stale::is_merged(git, &root, &req.branch, &base) {
            return Err(format!("branch '{}' is not merged into '{base}'", req.branch));
        }
        if l.path.join(".git").exists() && stale::is_dirty(git, &l.path) {
            return Err(format!("{shown} has uncommitted changes"));
        }
        return Ok(Some(shown));
    }
    match stale::remove_if_merged(git, &root, &l.path, &req.branch, &base, false) {
        Removal::Removed => Ok(Some(shown)),
        Removal::Skipped(why) => Err(why),
    }
}
