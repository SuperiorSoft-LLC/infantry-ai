// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Ticket worktrees that outlived their ticket: find them (read-only) and remove one that is safe to remove.
//!
//! `remove_if_merged` never uses `--force` and never deletes a folder itself: only `git worktree remove` (which
//! refuses a dirty tree) and `git worktree prune`. It skips, and says why, when the branch is not merged into the base,
//! the tree has uncommitted changes, or a live session works in it. The branch is kept (the archive).
use crate::git::{self, SharedGit};
use serde::{Deserialize, Serialize};
use std::path::{Path, PathBuf};

/// One linked worktree of a repository, as `git worktree list --porcelain` names it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Listed {
    pub path: PathBuf,
    pub branch: Option<String>,
}

/// What the read-only report knows about one worktree. Ticket fields are filled by the caller.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StaleEntry {
    pub path: String,
    pub branch: Option<String>,
    pub ticket_id: Option<String>,
    pub ticket_status: Option<String>,
    pub merged: bool,
    pub dirty: bool,
    pub live: bool,
    pub size_bytes: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Removal {
    Removed,
    Skipped(String),
}

/// The linked worktrees of `root` (the main checkout, listed first by git, is left out).
pub fn list(git: &SharedGit, root: &Path) -> Vec<Listed> {
    let r = git::run(&**git, root, &["worktree", "list", "--porcelain"]);
    if !r.ok() {
        return Vec::new();
    }
    let mut out: Vec<Listed> = Vec::new();
    for block in r.out().replace("\r\n", "\n").split("\n\n") {
        let mut path = None;
        let mut branch = None;
        for line in block.lines() {
            if let Some(p) = line.strip_prefix("worktree ") {
                path = Some(PathBuf::from(p.trim()));
            } else if let Some(b) = line.strip_prefix("branch ") {
                branch = Some(b.trim().strip_prefix("refs/heads/").unwrap_or(b.trim()).to_string());
            }
        }
        if let Some(path) = path {
            out.push(Listed { path, branch });
        }
    }
    if !out.is_empty() {
        out.remove(0);
    }
    out
}

/// True when `branch` is an ancestor of `base` (everything on it is in the base).
pub fn is_merged(git: &SharedGit, root: &Path, branch: &str, base: &str) -> bool {
    git::run(&**git, root, &["merge-base", "--is-ancestor", branch, base]).ok()
}

/// True when the tree at `path` has uncommitted or untracked changes. An unreadable tree counts as dirty.
pub fn is_dirty(git: &SharedGit, path: &Path) -> bool {
    let r = git::run(&**git, path, &["--no-optional-locks", "status", "--porcelain=v1", "--untracked-files=all"]);
    !r.ok() || !r.out().trim().is_empty()
}

/// Bytes under `path` (files only; a symlink is counted as itself, never followed).
pub fn dir_size(path: &Path) -> u64 {
    let mut total = 0u64;
    let mut stack = vec![path.to_path_buf()];
    while let Some(d) = stack.pop() {
        let Ok(rd) = std::fs::read_dir(&d) else { continue };
        for e in rd.flatten() {
            let Ok(ft) = e.file_type() else { continue };
            if ft.is_dir() {
                stack.push(e.path());
            } else if let Ok(m) = e.metadata() {
                total += m.len();
            }
        }
    }
    total
}

/// The cargo target folders a worktree can hold (relative to its root). They are build output, never source.
pub const BUILD_DIRS: [&str; 3] = ["rust/target", "app/src-tauri/target", "target"];

/// Delete the cargo target folders under the worktree `path` (the worktree and its branch stay). Returns the bytes freed.
/// Only a real folder named in [`BUILD_DIRS`] is removed; a symlink (a shared target) is left alone.
pub fn remove_build_dirs(path: &Path) -> u64 {
    let mut freed = 0u64;
    for rel in BUILD_DIRS {
        let d = path.join(rel);
        let Ok(meta) = std::fs::symlink_metadata(&d) else { continue };
        if !meta.is_dir() {
            continue;
        }
        let size = dir_size(&d);
        if std::fs::remove_dir_all(&d).is_ok() {
            freed += size;
        }
    }
    freed
}

/// Report one worktree (git facts and size). `live` and the ticket are the caller's to fill.
pub fn inspect(git: &SharedGit, root: &Path, base: &str, l: &Listed, with_size: bool) -> StaleEntry {
    let exists = l.path.join(".git").exists();
    StaleEntry {
        path: l.path.to_string_lossy().into_owned(),
        branch: l.branch.clone(),
        ticket_id: None,
        ticket_status: None,
        merged: l.branch.as_deref().is_some_and(|b| is_merged(git, root, b, base)),
        dirty: exists && is_dirty(git, &l.path),
        live: false,
        size_bytes: if with_size { dir_size(&l.path) } else { 0 },
    }
}

/// Remove the worktree at `path` when its `branch` is merged into `base`, it is clean and no session is live in it.
pub fn remove_if_merged(git: &SharedGit, root: &Path, path: &Path, branch: &str, base: &str, live: bool) -> Removal {
    if live {
        return Removal::Skipped("a live session works in it".into());
    }
    if !is_merged(git, root, branch, base) {
        return Removal::Skipped(format!("branch '{branch}' is not merged into '{base}'"));
    }
    if path.join(".git").exists() && is_dirty(git, path) {
        return Removal::Skipped("it has uncommitted changes".into());
    }
    let p = path.to_string_lossy().into_owned();
    let out = git::run(&**git, root, &["worktree", "remove", &p]);
    git::run(&**git, root, &["worktree", "prune"]);
    if out.ok() || !path.exists() {
        Removal::Removed
    } else {
        Removal::Skipped(format!("git worktree remove refused: {}", out.first_err_line()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn build_dirs_are_deleted_and_source_is_kept() {
        let root = std::env::temp_dir().join(format!("inf-bd-{}", std::process::id()));
        for d in ["rust/target/debug", "app/src-tauri/target/x", "rust/src"] {
            std::fs::create_dir_all(root.join(d)).unwrap();
        }
        std::fs::write(root.join("rust/target/debug/a.bin"), vec![0u8; 1000]).unwrap();
        std::fs::write(root.join("rust/src/lib.rs"), "x").unwrap();
        assert!(remove_build_dirs(&root) >= 1000);
        assert!(!root.join("rust/target").exists() && !root.join("app/src-tauri/target").exists());
        assert!(root.join("rust/src/lib.rs").exists());
        assert_eq!(remove_build_dirs(&root), 0);
        let _ = std::fs::remove_dir_all(&root);
    }

    use crate::git::{GitCli, SystemGit};
    use std::sync::Arc;

    fn git() -> SystemGit {
        let mut g = SystemGit::new();
        for (k, v) in [("GIT_AUTHOR_NAME", "T"), ("GIT_AUTHOR_EMAIL", "t@e.x"), ("GIT_COMMITTER_NAME", "T"), ("GIT_COMMITTER_EMAIL", "t@e.x"), ("GIT_CONFIG_NOSYSTEM", "1")] {
            g.extra_env.push((k.into(), v.into()));
        }
        g
    }

    fn sh(g: &SystemGit, dir: &Path, args: &[&str]) {
        let r = g.run(dir, args).unwrap();
        assert!(r.ok(), "git {args:?}: {}", r.err());
    }

    #[test]
    fn removes_only_a_merged_clean_idle_worktree() {
        let tmp = tempfile::tempdir().unwrap();
        let repo = tmp.path().join("repo");
        std::fs::create_dir_all(&repo).unwrap();
        let g = git();
        sh(&g, &repo, &["init", "-q", "-b", "main"]);
        std::fs::write(repo.join("a.txt"), "one\n").unwrap();
        sh(&g, &repo, &["add", "."]);
        sh(&g, &repo, &["commit", "-q", "-m", "init"]);
        let mk = |name: &str| {
            let p = repo.join(".worktrees").join(name);
            sh(&g, &repo, &["worktree", "add", "-q", "-b", name, &p.to_string_lossy()]);
            std::fs::write(p.join(format!("{name}.txt")), name).unwrap();
            sh(&g, &p, &["add", "."]);
            sh(&g, &p, &["commit", "-q", "-m", name]);
            p
        };
        let (merged, unmerged, dirty, live) = (mk("t-merged"), mk("t-unmerged"), mk("t-dirty"), mk("t-live"));
        for b in ["t-merged", "t-dirty", "t-live"] {
            sh(&g, &repo, &["merge", "-q", "--no-ff", "-m", b, b]);
        }
        std::fs::write(dirty.join("scratch.txt"), "uncommitted").unwrap();
        let git: SharedGit = Arc::new(g);

        assert_eq!(list(&git, &repo).len(), 4);
        let e = inspect(&git, &repo, "main", &Listed { path: dirty.clone(), branch: Some("t-dirty".into()) }, true);
        assert!(e.merged && e.dirty && e.size_bytes > 0);

        assert!(matches!(remove_if_merged(&git, &repo, &unmerged, "t-unmerged", "main", false), Removal::Skipped(s) if s.contains("not merged")));
        assert!(matches!(remove_if_merged(&git, &repo, &dirty, "t-dirty", "main", false), Removal::Skipped(s) if s.contains("uncommitted")));
        assert!(matches!(remove_if_merged(&git, &repo, &live, "t-live", "main", true), Removal::Skipped(s) if s.contains("live")));
        assert!(unmerged.exists() && dirty.exists() && live.exists());

        assert_eq!(remove_if_merged(&git, &repo, &merged, "t-merged", "main", false), Removal::Removed);
        assert!(!merged.exists());
        // The branch is kept: it is the archive.
        assert!(git::resolve_commit(&*git, &repo, "refs/heads/t-merged").is_some());
        assert_eq!(list(&git, &repo).len(), 3);
    }
}
