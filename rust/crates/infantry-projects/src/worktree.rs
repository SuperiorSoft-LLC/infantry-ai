// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! One git worktree per session.
//!
//! Collision model: the manager owns branch names. A caller passes a task hint at most; the branch is
//! `infantry/<slug>-<session-tag>` and the path `<repo>/.worktrees/<branch with '/' -> '-'>`. A leftover path or
//! branch retries with a numeric suffix, then fails loudly; it never reuses or deletes another agent's worktree.
//! `create` is idempotent per session id. Removing a dirty worktree is refused unless forced, and the branch is
//! kept (the archive: `git worktree add <path> <branch>` restores it) unless asked otherwise.
use crate::changes::parse_porcelain;
use crate::git::{self, SharedGit};
use crate::paths;
use crate::wire::{CleanupResult, WorktreeRecord, WorktreeRef, WorktreeStatus};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorktreeError(pub String);

impl std::fmt::Display for WorktreeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}
impl std::error::Error for WorktreeError {}

/// What a session spawn needs from a workspace provisioner: make one, say where it is.
pub trait WorktreeProvisioner: Send + Sync {
    fn create(&self, session_id: &str, repo: &Path, branch_hint: Option<&str>, base_ref: Option<&str>) -> Result<WorktreeRecord, WorktreeError>;
    /// Register a worktree the caller already cut (a ticket's own branch).
    fn adopt(&self, record: WorktreeRecord);
}

pub struct WorktreeConfig {
    /// Where a repository's worktrees live, relative to its main root.
    pub dir_name: String,
    pub max_attempts: usize,
    pub max_changes: usize,
}

impl Default for WorktreeConfig {
    fn default() -> Self {
        WorktreeConfig { dir_name: ".worktrees".into(), max_attempts: 5, max_changes: 500 }
    }
}

pub struct WorktreeManager {
    git: SharedGit,
    cfg: WorktreeConfig,
    records: Mutex<HashMap<String, WorktreeRecord>>,
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// Lowercase words joined by `-`, at most `max` chars, never empty (`fallback`).
pub fn slug(hint: &str, max: usize, fallback: &str) -> String {
    let lowered: String = hint.to_lowercase().chars().map(|c| if c.is_alphanumeric() { c } else { '-' }).collect();
    let joined = lowered.split('-').filter(|s| !s.is_empty()).collect::<Vec<_>>().join("-");
    let cut: String = joined.chars().take(max).collect();
    let t = cut.trim_matches('-');
    if t.is_empty() {
        fallback.to_string()
    } else {
        t.to_string()
    }
}

/// The last 8 alphanumerics of a session id.
pub fn session_tag(id: &str) -> String {
    let a: Vec<char> = id.chars().filter(|c| c.is_alphanumeric()).collect();
    if a.is_empty() {
        "session".into()
    } else {
        a[a.len().saturating_sub(8)..].iter().collect()
    }
}

fn is_collision(stderr: &str) -> bool {
    let s = stderr.to_lowercase();
    ["already exists", "already checked out", "already used by worktree", "is a main working tree"].iter().any(|m| s.contains(m))
}

impl WorktreeManager {
    pub fn new(git: SharedGit, cfg: WorktreeConfig) -> Self {
        WorktreeManager { git, cfg, records: Mutex::new(HashMap::new()) }
    }

    fn recs(&self) -> std::sync::MutexGuard<'_, HashMap<String, WorktreeRecord>> {
        self.records.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn get(&self, session_id: &str) -> Option<WorktreeRecord> {
        self.recs().get(session_id).cloned()
    }

    pub fn list(&self) -> Vec<WorktreeRecord> {
        let mut v: Vec<_> = self.recs().values().cloned().collect();
        v.sort_by_key(|r| r.created_at);
        v
    }

    pub fn status_of(&self, session_id: &str) -> Option<WorktreeStatus> {
        self.get(session_id).map(|r| self.status(&r))
    }

    pub fn statuses(&self) -> Vec<WorktreeStatus> {
        self.list().iter().map(|r| self.status(r)).collect()
    }

    /// Live state from git, never cached: exists (a `.git` entry), dirty + changed files (one status read, so they
    /// cannot disagree), behind/ahead against the immutable cut point.
    pub fn status(&self, r: &WorktreeRecord) -> WorktreeStatus {
        let path = PathBuf::from(&r.path);
        let mut s = WorktreeStatus {
            session_id: r.session_id.clone(),
            repo_root: r.repo_root.clone(),
            path: r.path.clone(),
            branch: r.branch.clone(),
            base_ref: r.base_ref.clone(),
            exists: path.join(".git").exists(),
            dirty: false,
            ahead: 0,
            behind: 0,
            removed: r.removed(),
            changes: None,
            changes_truncated: false,
        };
        if !s.exists {
            return s;
        }
        let st = git::run(&*self.git, &path, &["--no-optional-locks", "status", "--porcelain=v1", "-z", "--untracked-files=all"]);
        if st.ok() {
            let all = parse_porcelain(&st.stdout);
            s.dirty = !all.is_empty();
            s.changes_truncated = all.len() > self.cfg.max_changes;
            s.changes = Some(all.into_iter().take(self.cfg.max_changes).collect());
        }
        let range = format!("{}...HEAD", r.base_ref);
        let c = git::run(&*self.git, &path, &["rev-list", "--left-right", "--count", &range]);
        let parts: Vec<i64> = c.out().split_whitespace().filter_map(|p| p.parse().ok()).collect();
        if parts.len() >= 2 {
            s.behind = parts[0];
            s.ahead = parts[1];
        }
        s
    }

    /// Remove a session's worktree. Dirty is refused unless `force`; the branch is kept unless `delete_branch`
    /// (an unmerged one only with `delete_unmerged`).
    pub fn remove(&self, session_id: &str, force: bool, delete_branch: bool, delete_unmerged: bool) -> Result<CleanupResult, WorktreeError> {
        let r = self.get(session_id).ok_or_else(|| WorktreeError(format!("no worktree known for session {session_id}")))?;
        let root = PathBuf::from(&r.repo_root);
        let path = PathBuf::from(&r.path);
        let exists = path.join(".git").exists();
        let dirty = exists && self.status(&r).dirty;
        let res = |removed, branch_kept, forced, message: String| CleanupResult {
            session_id: session_id.to_string(),
            path: r.path.clone(),
            branch: r.branch.clone(),
            removed,
            branch_kept,
            dirty,
            forced,
            message,
        };
        if exists && dirty && !force {
            return Ok(res(
                false,
                true,
                false,
                format!("refusing to remove a worktree with uncommitted changes (would lose work): {} -- commit first, or call again with force=true", r.path),
            ));
        }
        if exists {
            let p = path.to_string_lossy().into_owned();
            let mut args = vec!["worktree", "remove"];
            if force {
                args.push("--force");
            }
            args.push(&p);
            let out = git::run(&*self.git, &root, &args);
            if !out.ok() {
                return Ok(res(false, true, force, format!("git worktree remove failed: {}", out.err())));
            }
            git::run(&*self.git, &root, &["worktree", "prune"]);
        }
        let mut branch_kept = true;
        if delete_branch {
            let flag = if delete_unmerged { "-D" } else { "-d" };
            branch_kept = !git::run(&*self.git, &root, &["branch", flag, &r.branch]).ok();
        }
        if let Some(rec) = self.recs().get_mut(session_id) {
            rec.removed_at = Some(now_ms());
        }
        let message = if !exists {
            format!("no worktree at {}; archived", r.path)
        } else if branch_kept {
            format!("worktree removed; branch '{}' kept (recover with: git worktree add {} {})", r.branch, r.path, r.branch)
        } else {
            format!("worktree and branch '{}' removed", r.branch)
        };
        Ok(res(exists, branch_kept, force && dirty, message))
    }

    /// Rebuild the registry from persisted sessions at startup (status is recomputed, never persisted).
    pub fn reconcile(&self, refs: &[WorktreeRef]) {
        let mut recs = self.recs();
        for r in refs {
            recs.entry(r.session_id.clone()).or_insert_with(|| WorktreeRecord {
                session_id: r.session_id.clone(),
                repo_root: r.repo_root.clone(),
                path: r.path.clone(),
                branch: r.branch.clone(),
                base_ref: r.base_ref.clone(),
                created_at: 0,
                removed_at: None,
            });
        }
    }
}

impl WorktreeProvisioner for WorktreeManager {
    fn create(&self, session_id: &str, repo: &Path, branch_hint: Option<&str>, base_ref: Option<&str>) -> Result<WorktreeRecord, WorktreeError> {
        if let Some(r) = self.get(session_id).filter(|r| !r.removed()) {
            return Ok(r);
        }
        // Cut from the MAIN checkout: a worktree nested in another worktree is its own trap.
        let root = git::main_root(&*self.git, repo);
        if !git::is_repo(&*self.git, &root) {
            return Err(WorktreeError(format!("not a git repository: {}", root.display())));
        }
        let base = base_ref.filter(|b| !b.trim().is_empty()).unwrap_or("HEAD");
        let base_commit = git::resolve_commit(&*self.git, repo, base).ok_or_else(|| WorktreeError(format!("cannot resolve base ref '{base}' in {}", repo.display())))?;
        let slug = slug(branch_hint.unwrap_or(""), 32, "session");
        let tag = session_tag(session_id);
        let mut last = String::new();
        for attempt in 0..self.cfg.max_attempts {
            let suffix = if attempt == 0 { String::new() } else { format!("-{attempt}") };
            let branch = format!("infantry/{slug}-{tag}{suffix}");
            let path = root.join(&self.cfg.dir_name).join(branch.replace('/', "-"));
            if path.exists() {
                last = format!("path already exists: {}", path.display());
                continue;
            }
            let p = path.to_string_lossy().into_owned();
            let out = git::run(&*self.git, &root, &["worktree", "add", "-b", &branch, &p, &base_commit]);
            if out.ok() {
                let rec = WorktreeRecord {
                    session_id: session_id.to_string(),
                    repo_root: root.to_string_lossy().into_owned(),
                    path: p,
                    branch,
                    base_ref: base_commit,
                    created_at: now_ms(),
                    removed_at: None,
                };
                self.recs().insert(session_id.to_string(), rec.clone());
                return Ok(rec);
            }
            last = out.err().to_string();
            if !is_collision(&last) {
                return Err(WorktreeError(format!("git worktree add failed: {last}")));
            }
        }
        Err(WorktreeError(format!("could not create a unique worktree after {} attempts: {last}", self.cfg.max_attempts)))
    }

    fn adopt(&self, record: WorktreeRecord) {
        self.recs().insert(record.session_id.clone(), record);
    }
}

/// True when `dir` sits inside one of the manager's worktrees (used to mark the session's worktree).
pub fn holder_of<'a>(records: &'a [WorktreeRecord], dir: &Path) -> Option<&'a WorktreeRecord> {
    records.iter().filter(|r| paths::contains(Path::new(&r.path), dir)).max_by_key(|r| paths::depth_key(Path::new(&r.path)))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn slugs_and_tags() {
        assert_eq!(slug("Fix the  Login bug!", 32, "session"), "fix-the-login-bug");
        assert_eq!(slug("***", 32, "session"), "session");
        assert_eq!(session_tag("sess-0123456789ab"), "456789ab");
    }
}
