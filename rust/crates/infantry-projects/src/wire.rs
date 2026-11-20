// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The payloads of the project, workspace, project-git and worktree frames (contract 1.36.0, 1.65.0, 1.74.0, 1.88.0).
//!
//! The honesty rule every git answer carries: `available = false` with a `reason` means the daemon COULD NOT READ
//! (no directory, not a repository, git failed) and the data fields MUST NOT be read as "nothing changed";
//! `available = true` with an empty list is a known clean answer.
//!
//! Requests are decoded strictly (an unknown key is refused), answers encode every field (`null` included), except
//! [`ProjectGitPatch::worktree`], which is absent when the request named none.
use crate::changes::{BranchChangedFile, ChangedFile};
use serde::{Deserialize, Serialize};

fn default_log_limit() -> i64 {
    LOG_DEFAULT_LIMIT
}
fn default_history_limit() -> i64 {
    WORKTREE_DEFAULT_HISTORY
}

pub const LOG_DEFAULT_LIMIT: i64 = 50;
pub const LOG_MAX_LIMIT: i64 = 200;
pub const WORKTREE_DEFAULT_HISTORY: i64 = 30;

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProjectGitStatus {
    pub project_id: String,
    pub directory: String,
    pub available: bool,
    #[serde(default)]
    pub reason: String,
    #[serde(default)]
    pub repo_root: String,
    #[serde(default)]
    pub branch: Option<String>,
    #[serde(default)]
    pub head: Option<String>,
    #[serde(default)]
    pub changes: Vec<ChangedFile>,
    #[serde(default)]
    pub changes_truncated: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectGitDiffRequest {
    pub project_id: String,
    pub path: String,
    #[serde(default)]
    pub staged: bool,
    #[serde(default)]
    pub worktree: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProjectGitPatch {
    pub project_id: String,
    pub path: String,
    pub staged: bool,
    pub available: bool,
    #[serde(default)]
    pub reason: String,
    #[serde(default)]
    pub patch: String,
    #[serde(default)]
    pub binary: bool,
    #[serde(default)]
    pub truncated: bool,
    #[serde(default)]
    pub branch: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub worktree: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectGitLogRequest {
    pub project_id: String,
    #[serde(default = "default_log_limit")]
    pub limit: i64,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GitCommit {
    pub hash: String,
    pub short_hash: String,
    pub author: String,
    /// Author time, epoch SECONDS.
    pub timestamp: i64,
    pub subject: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProjectGitHistory {
    pub project_id: String,
    pub available: bool,
    #[serde(default)]
    pub reason: String,
    #[serde(default)]
    pub commits: Vec<GitCommit>,
    #[serde(default)]
    pub truncated: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectGitBranchesRequest {
    pub project_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct GitBranch {
    pub name: String,
    pub head: String,
    #[serde(default)]
    pub upstream: Option<String>,
    #[serde(default)]
    pub is_worktree: bool,
    #[serde(default)]
    pub worktree_path: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProjectGitBranchList {
    pub project_id: String,
    pub available: bool,
    #[serde(default)]
    pub reason: String,
    #[serde(default)]
    pub current: Option<String>,
    #[serde(default)]
    pub branches: Vec<GitBranch>,
    #[serde(default)]
    pub truncated: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectGitBranchChangesRequest {
    pub project_id: String,
    pub branch: String,
    #[serde(default)]
    pub base: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProjectGitBranchDiffstat {
    pub project_id: String,
    pub branch: String,
    #[serde(default)]
    pub base: String,
    #[serde(default)]
    pub merge_base: String,
    pub available: bool,
    #[serde(default)]
    pub reason: String,
    #[serde(default)]
    pub changes: Vec<BranchChangedFile>,
    #[serde(default)]
    pub truncated: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectGitBranchDiffRequest {
    pub project_id: String,
    pub branch: String,
    #[serde(default)]
    pub base: Option<String>,
    pub path: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectGitCheckoutRequest {
    pub project_id: String,
    pub branch: String,
    #[serde(default)]
    pub create: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProjectGitCheckoutResult {
    pub project_id: String,
    pub branch: String,
    #[serde(default)]
    pub create: bool,
    pub switched: bool,
    #[serde(default)]
    pub code: String,
    #[serde(default)]
    pub reason: String,
    #[serde(default)]
    pub previous: Option<String>,
    #[serde(default)]
    pub current: Option<String>,
    #[serde(default)]
    pub dirty_count: i64,
    #[serde(default)]
    pub worktree_path: Option<String>,
}

/// The stable refusal codes of [`ProjectGitCheckoutResult::code`].
pub mod checkout_refusal {
    pub const UNAVAILABLE: &str = "unavailable";
    pub const BAD_NAME: &str = "bad_name";
    pub const DIRTY: &str = "dirty";
    pub const HELD_BY_WORKTREE: &str = "held_by_worktree";
    pub const NO_BRANCH: &str = "no_branch";
    pub const EXISTS: &str = "exists";
    pub const GIT_FAILED: &str = "git_failed";
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectGitWorktreesRequest {
    pub project_id: String,
    #[serde(default)]
    pub session_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct GitWorktree {
    pub path: String,
    #[serde(default)]
    pub branch: Option<String>,
    #[serde(default)]
    pub head: Option<String>,
    #[serde(default)]
    pub main: bool,
    #[serde(default)]
    pub project: bool,
    #[serde(default)]
    pub session: bool,
    #[serde(default)]
    pub detached: bool,
    #[serde(default)]
    pub bare: bool,
    #[serde(default)]
    pub prunable: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProjectGitWorktreeList {
    pub project_id: String,
    pub available: bool,
    #[serde(default)]
    pub reason: String,
    #[serde(default)]
    pub worktrees: Vec<GitWorktree>,
    #[serde(default)]
    pub selected: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectGitWorktreeStatusRequest {
    pub project_id: String,
    pub path: String,
    #[serde(default = "default_history_limit")]
    pub history_limit: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ProjectGitWorktreeStatus {
    pub project_id: String,
    pub path: String,
    pub available: bool,
    #[serde(default)]
    pub reason: String,
    #[serde(default)]
    pub branch: Option<String>,
    #[serde(default)]
    pub head: Option<String>,
    #[serde(default)]
    pub base: String,
    #[serde(default)]
    pub ahead: Option<i64>,
    #[serde(default)]
    pub behind: Option<i64>,
    #[serde(default)]
    pub base_reason: String,
    #[serde(default)]
    pub uncommitted: i64,
    #[serde(default)]
    pub changes: Vec<ChangedFile>,
    #[serde(default)]
    pub changes_truncated: bool,
    #[serde(default)]
    pub commits: Vec<GitCommit>,
    #[serde(default)]
    pub commits_truncated: bool,
}

// ---- projects / workspaces -------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectSave {
    #[serde(default)]
    pub id: Option<String>,
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub goal: Option<String>,
    #[serde(default)]
    pub directory: Option<String>,
    #[serde(default)]
    pub starter: bool,
}

/// `project_delete`: remove a project. Refused while one of its sessions is live unless `force`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectDeleteRequest {
    pub project_id: String,
    #[serde(default)]
    pub force: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectBaseRequest {
    pub project_id: String,
    #[serde(default)]
    pub branch: Option<String>,
    #[serde(default)]
    pub clear: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct ProjectConfigRequest {
    pub project_id: String,
    #[serde(default)]
    pub lane_caps: Option<std::collections::BTreeMap<String, i32>>,
    #[serde(default)]
    pub auto_remove_done_workers: Option<bool>,
    #[serde(default)]
    pub routing_rules: Option<Vec<infantry_protocol::projects::RoutingRule>>,
    #[serde(default)]
    pub experiments: Option<std::collections::BTreeMap<String, String>>,
    #[serde(default)]
    pub data: Option<std::collections::BTreeMap<String, String>>,
    #[serde(default)]
    pub unset_data: Option<Vec<String>>,
}

/// A workspace inside a project; `path` is its optional checkout folder.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Workspace {
    pub id: String,
    pub project_id: String,
    pub name: String,
    #[serde(default)]
    pub path: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct WorkspaceSave {
    pub project_id: String,
    pub name: String,
    #[serde(default)]
    pub path: Option<String>,
}

// ---- session worktrees ---------------------------------------------------------------------------

/// One session's worktree, as the manager registered it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorktreeRecord {
    pub session_id: String,
    pub repo_root: String,
    pub path: String,
    pub branch: String,
    pub base_ref: String,
    pub created_at: i64,
    #[serde(default)]
    pub removed_at: Option<i64>,
}

impl WorktreeRecord {
    pub fn removed(&self) -> bool {
        self.removed_at.is_some()
    }
}

/// The identity of a persisted session worktree, to rebuild the registry at startup.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorktreeRef {
    pub session_id: String,
    pub repo_root: String,
    pub path: String,
    pub branch: String,
    pub base_ref: String,
}

/// The live state of a session worktree (the `worktree` / `worktrees` frames).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorktreeStatus {
    pub session_id: String,
    pub repo_root: String,
    pub path: String,
    pub branch: String,
    pub base_ref: String,
    pub exists: bool,
    pub dirty: bool,
    pub ahead: i64,
    pub behind: i64,
    pub removed: bool,
    /// `None` = UNKNOWN (gone, or git unreadable); empty = known clean.
    #[serde(default)]
    pub changes: Option<Vec<ChangedFile>>,
    #[serde(default)]
    pub changes_truncated: bool,
}

/// The honest result of a cleanup (`worktree_remove`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CleanupResult {
    pub session_id: String,
    pub path: String,
    pub branch: String,
    pub removed: bool,
    pub branch_kept: bool,
    pub dirty: bool,
    pub forced: bool,
    pub message: String,
}

/// A ticket's branch and worktree.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TicketWorktree {
    pub branch: String,
    pub base: String,
    pub path: String,
    pub repo_root: String,
}
