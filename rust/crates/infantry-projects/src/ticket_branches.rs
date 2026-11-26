// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Branch per task: starting a ticket gives it exactly one branch and one worktree.
//!
//! - A ticket never started cuts `ticket/<short-id>-<slug>` in `<repo>/.worktrees/` off the COMMIT its base ref
//!   (the epic's / project's base) resolves to. Only that commit counts, so the base checkout's uncommitted state
//!   never matters and never refuses a start.
//! - A ticket that already holds a branch REUSES it (re-attaching the worktree when its directory is gone); it never
//!   cuts a second branch.
//! - A base that does not resolve is refused. An existing branch under the ticket's name with no record on the
//!   ticket is adopted (re-cut at the base) only when it holds no commits beyond the base and no worktree has it;
//!   one with commits of its own is refused (its base is unknown).
//!
//! Nothing here pushes or merges. The orchestrator calls into this; this never calls the orchestrator.
use crate::git::{self, SharedGit};
use crate::wire::TicketWorktree;
use crate::worktree::slug;
use std::path::{Path, PathBuf};
use std::sync::Arc;

pub const BRANCH_PREFIX: &str = "ticket/";
const SHORT_SHA: usize = 8;

/// One ticket's start request. `recorded_*` is what the ticket holds from an earlier start (both `None` if never).
#[derive(Debug, Clone)]
pub struct TicketBranchRequest {
    pub ticket_id: String,
    pub title: String,
    pub repo: PathBuf,
    pub base_branch: String,
    pub recorded_branch: Option<String>,
    pub recorded_base: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TicketBranchOutcome {
    /// `note` names what a FRESH cut came from; `warning` is what the worktree still lacks (from a provision hook).
    Ready { worktree: TicketWorktree, note: Option<String>, warning: Option<String> },
    Refused(String),
}

/// Prepares a fresh worktree (an SDK link, a local config file...). Returns a warning when something is missing.
/// Core ships none; a plugin registers its own, so core names no build stack.
pub trait ProvisionHook: Send + Sync {
    fn provision(&self, repo_root: &Path, worktree: &Path) -> Option<String>;
}

/// `ticket/<short-id>-<slug>`: the id without `ticket-` (8 alphanumerics), the title in lowercase words.
pub fn branch_for(ticket_id: &str, title: &str) -> String {
    let lower = ticket_id.strip_prefix("ticket-").unwrap_or(ticket_id).to_lowercase();
    let short: String = lower.chars().filter(|c| c.is_alphanumeric()).take(8).collect();
    let short = if short.is_empty() { "ticket".to_string() } else { short };
    format!("{BRANCH_PREFIX}{short}-{}", slug(title, 40, "task"))
}

pub struct TicketBranches {
    git: SharedGit,
    dir_name: String,
    hooks: Vec<Arc<dyn ProvisionHook>>,
}

impl TicketBranches {
    pub fn new(git: SharedGit) -> Self {
        TicketBranches { git, dir_name: ".worktrees".into(), hooks: Vec::new() }
    }

    pub fn with_hook(mut self, hook: Arc<dyn ProvisionHook>) -> Self {
        self.hooks.push(hook);
        self
    }

    fn provision(&self, root: &Path, path: &Path) -> Option<String> {
        let w: Vec<String> = self.hooks.iter().filter_map(|h| h.provision(root, path)).collect();
        (!w.is_empty()).then(|| w.join("; "))
    }

    /// Where `branch`'s worktree lives (never stored: recomputed from the branch).
    pub fn path_for(&self, root: &Path, branch: &str) -> PathBuf {
        root.join(&self.dir_name).join(branch.replace('/', "-"))
    }

    pub fn main_root(&self, repo: &Path) -> PathBuf {
        git::main_root(&*self.git, repo)
    }

    fn branch_exists(&self, root: &Path, branch: &str) -> bool {
        let r = format!("refs/heads/{branch}");
        git::run(&*self.git, root, &["rev-parse", "--verify", "--quiet", &r]).ok()
    }

    /// True when any worktree (main or linked) has `full_ref` checked out.
    fn checked_out(&self, root: &Path, full_ref: &str) -> bool {
        let r = git::run(&*self.git, root, &["worktree", "list", "--porcelain"]);
        r.out().lines().any(|l| l.strip_prefix("branch ").map(str::trim) == Some(full_ref))
    }

    pub fn ensure(&self, req: &TicketBranchRequest) -> TicketBranchOutcome {
        let root = self.main_root(&req.repo);
        if !git::is_repo(&*self.git, &root) {
            return TicketBranchOutcome::Refused(format!("not a git repository: {}", root.display()));
        }
        if let Some(b) = &req.recorded_branch {
            return self.reattach(&root, b, req.recorded_base.as_deref());
        }
        let Some(base) = git::resolve_commit(&*self.git, &root, &req.base_branch) else {
            return TicketBranchOutcome::Refused(format!("base branch '{}' does not resolve to a commit in {}", req.base_branch, root.display()));
        };
        let branch = branch_for(&req.ticket_id, &req.title);
        let path = self.path_for(&root, &branch);
        // A leftover branch with no record (e.g. a queue that cut it earlier) is adopted only when it holds no
        // commits of its own (an ancestor of the base) and no worktree has it checked out: it is re-cut at the base.
        let leftover = self.branch_exists(&root, &branch);
        if leftover {
            let r = format!("refs/heads/{branch}");
            let ancestor = git::run(&*self.git, &root, &["merge-base", "--is-ancestor", &r, &base]).ok();
            if !ancestor {
                return TicketBranchOutcome::Refused(format!(
                    "branch '{branch}' already exists with commits not in {} and the ticket has no record of it; not adopting a branch of unknown base",
                    req.base_branch
                ));
            }
            if self.checked_out(&root, &r) {
                return TicketBranchOutcome::Refused(format!("branch '{branch}' already exists and is checked out in a worktree the ticket has no record of"));
            }
        }
        if self.git.path_exists(&path) {
            return TicketBranchOutcome::Refused(format!("worktree path already exists: {}", path.display()));
        }
        let p = path.to_string_lossy().into_owned();
        // `-B` resets the adopted leftover to the base; `-b` for a fresh cut.
        let flag = if leftover { "-B" } else { "-b" };
        let added = git::run(&*self.git, &root, &["worktree", "add", flag, &branch, &p, &base]);
        if !added.ok() {
            return TicketBranchOutcome::Refused(format!("git worktree add failed: {}", added.err()));
        }
        let note = Some(format!("cut from {} {}", req.base_branch, &base[..base.len().min(SHORT_SHA)]));
        let warning = self.provision(&root, &path);
        TicketBranchOutcome::Ready { worktree: TicketWorktree { branch, base, path: p, repo_root: root.to_string_lossy().into_owned() }, note, warning }
    }

    fn reattach(&self, root: &Path, branch: &str, recorded_base: Option<&str>) -> TicketBranchOutcome {
        let path = self.path_for(root, branch);
        let base = match recorded_base.map(str::to_string).or_else(|| git::resolve_commit(&*self.git, root, branch)) {
            Some(b) => b,
            None => return TicketBranchOutcome::Refused(format!("recorded branch '{branch}' is missing and has no recorded base")),
        };
        let p = path.to_string_lossy().into_owned();
        let wt = TicketWorktree { branch: branch.to_string(), base: base.clone(), path: p.clone(), repo_root: root.to_string_lossy().into_owned() };
        if self.git.path_exists(&path.join(".git")) {
            return TicketBranchOutcome::Ready { worktree: wt, note: None, warning: None };
        }
        let args: Vec<&str> = if self.branch_exists(root, branch) {
            vec!["worktree", "add", &p, branch]
        } else {
            // Deleted by hand: re-create it at the commit the ticket recorded, not at today's base.
            vec!["worktree", "add", "-b", branch, &p, &base]
        };
        let r = git::run(&*self.git, root, &args);
        if !r.ok() {
            return TicketBranchOutcome::Refused(format!("git worktree add failed for '{branch}': {}", r.err()));
        }
        TicketBranchOutcome::Ready { worktree: wt, note: None, warning: self.provision(root, &path) }
    }

    /// The commit `rev` resolves to where a ticket start in `repo` cuts.
    pub fn resolve_base(&self, repo: &Path, rev: &str) -> Option<String> {
        git::resolve_commit(&*self.git, &self.main_root(repo), rev)
    }

    pub fn common_dir(&self, dir: &Path) -> Option<PathBuf> {
        git::common_dir(&*self.git, dir)
    }

    /// Commits `branch` is ahead of `base`, or `None` when either ref is unknown.
    pub fn ahead(&self, repo: &Path, branch: &str, base: &str) -> Option<i64> {
        let range = format!("{base}..{branch}");
        let r = git::run(&*self.git, &self.main_root(repo), &["rev-list", "--count", &range]);
        r.ok().then(|| r.out().lines().next().and_then(|l| l.trim().parse().ok())).flatten()
    }

    pub fn tip(&self, repo: &Path, branch: &str) -> Option<String> {
        git::resolve_commit(&*self.git, &self.main_root(repo), &format!("refs/heads/{branch}"))
    }

    /// The newest `max` `<short> <subject>` lines of `base..branch`.
    pub fn subjects(&self, repo: &Path, branch: &str, base: &str, max: usize) -> Vec<String> {
        let range = format!("{base}..{branch}");
        let n = max.to_string();
        let r = git::run(&*self.git, &self.main_root(repo), &["log", "--format=%h %s", "-n", &n, &range]);
        if !r.ok() {
            return Vec::new();
        }
        r.out().lines().map(str::trim).filter(|l| !l.is_empty()).map(str::to_string).collect()
    }

    /// Files `git status --short` lists in `dir`, or `None` when it is not a checkout.
    pub fn dirty_files(&self, dir: &Path) -> Option<usize> {
        if !dir.is_dir() {
            return None;
        }
        let r = git::run(&*self.git, dir, &["status", "--short"]);
        r.ok().then(|| r.out().lines().filter(|l| !l.trim().is_empty()).count())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn branch_names() {
        assert_eq!(branch_for("ticket-e85faaeb", "Feature: built-in harness installer (opencode)"), "ticket/e85faaeb-feature-built-in-harness-installer-openc");
        assert_eq!(branch_for("", "!!"), "ticket/ticket-task");
    }
}
