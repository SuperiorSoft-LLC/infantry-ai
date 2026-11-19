// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry AI projects, workspaces and git (platform level, L1).
//!
//! - [`store`]: projects and workspaces, persisted in `infantry.db` (the Kotlin schema), with project config.
//! - [`service`]: the project git view (status, diff, log, branches, branch diffstat/diff, worktrees, switch).
//! - [`worktree`]: one worktree per session; [`ticket_branches`]: `ticket/<short>-<slug>` cut from the epic base.
//! - [`units`]: the FrameUnits ([`units::ProjectsUnit`], [`units::ProjectGitUnit`], [`units::WorktreesUnit`]).
//!
//! Everything git goes through the [`git::GitCli`] seam over the git CLI. This crate never depends on the board or
//! the orchestrator: the orchestrator calls [`ticket_branches::TicketBranches`], never the other way round.
pub mod changes;
pub mod git;
pub mod paths;
pub mod service;
pub mod stale;
pub mod store;
pub mod ticket_branches;
pub mod units;
pub mod wire;
pub mod worktree;

use std::path::PathBuf;
use std::sync::Arc;

/// Everything wired once, for a composition root.
pub struct Projects {
    pub git: git::SharedGit,
    pub store: Arc<store::ProjectStore>,
    pub service: Arc<service::ProjectGitService>,
    pub worktrees: Arc<worktree::WorktreeManager>,
    pub branches: Arc<ticket_branches::TicketBranches>,
}

impl Projects {
    /// `db` = the daemon's `infantry.db` (`None` keeps everything in memory); `repo` = where ticket starts cut when a
    /// project's own directory is not a repository (`INFANTRY_REPO`, else the daemon's checkout).
    pub fn new(git: git::SharedGit, db: Option<Arc<infantry_core::db::Db>>, cfg: service::GitViewConfig) -> Result<Self, String> {
        let persistence: Box<dyn store::Persistence> = match db {
            Some(db) => Box::new(store::SqlPersistence::new(db)?),
            None => Box::new(store::MemoryPersistence),
        };
        Ok(Projects {
            store: Arc::new(store::ProjectStore::new(persistence)),
            service: Arc::new(service::ProjectGitService::new(git.clone(), cfg)),
            worktrees: Arc::new(worktree::WorktreeManager::new(git.clone(), worktree::WorktreeConfig::default())),
            branches: Arc::new(ticket_branches::TicketBranches::new(git.clone())),
            git,
        })
    }

    pub fn projects_unit(&self, repo: Option<PathBuf>) -> units::ProjectsUnit {
        units::ProjectsUnit::new(self.store.clone(), self.branches.clone(), repo)
    }

    pub fn git_unit(&self) -> units::ProjectGitUnit {
        units::ProjectGitUnit::new(self.store.clone(), self.service.clone())
    }

    pub fn worktrees_unit(&self, repo: Option<PathBuf>) -> units::WorktreesUnit {
        units::WorktreesUnit::new(self.worktrees.clone(), repo)
    }
}
