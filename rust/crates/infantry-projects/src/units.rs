// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The frame units: decode, ask the store / service, encode. No rule lives here.
//!
//! A refusal (unknown project, malformed payload, a path git must not see) is the `<frame> refused: <why>` text; a
//! git read that merely could not happen is an ANSWER with `available = false`, never an error.
use crate::service::{ProjectGitService, Refused};
use crate::store::{check_base_branch_name, ProjectStore};
use crate::ticket_branches::TicketBranches;
use crate::wire::*;
use crate::worktree::WorktreeManager;
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_protocol::frames as f;
use infantry_protocol::projects::{Project, RoutingRule};
use serde::de::DeserializeOwned;
use serde::Serialize;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

fn decode<T: DeserializeOwned>(frame: &str, data: &str) -> Result<T, Refusal> {
    serde_json::from_str(data).map_err(|e| Refusal::new(frame, format!("malformed {frame} payload: {e}")))
}

fn reply<T: Serialize>(req: &Envelope, frame: &str, body: &T) -> Envelope {
    let mut e = Envelope::new(frame, serde_json::to_string(body).unwrap_or_default());
    e.request_id = req.request_id.clone();
    e
}

fn refused(frame: &str) -> impl Fn(Refused) -> Refusal + '_ {
    move |r| Refusal::new(frame, r.0)
}

pub type RoutingValidator = Arc<dyn Fn(Vec<RoutingRule>) -> Result<Vec<RoutingRule>, String> + Send + Sync>;
pub type CutRepo = Arc<dyn Fn(&Project) -> Option<PathBuf> + Send + Sync>;
/// The live sessions of a project, as `(sessionId, role)`.
pub type LiveSessions = Arc<dyn Fn(&str) -> Vec<(String, String)> + Send + Sync>;
pub type SessionCheckout = Arc<dyn Fn(&str) -> Option<String> + Send + Sync>;

// ---- projects + workspaces ---------------------------------------------------------------------

/// `project_save/get/list/base/config` and `workspace_save/list`.
pub struct ProjectsUnit {
    pub store: Arc<ProjectStore>,
    pub branches: Arc<TicketBranches>,
    /// Where ticket starts cut, for a project (`project_base` resolves the branch there). `None` = no repository.
    pub cut_repo: CutRepo,
    /// Normalises and checks a routing-rule list. The default has no harness list and refuses every non-empty list;
    /// the composition root passes the orchestrator's validator.
    pub routing: RoutingValidator,
    /// Which sessions of a project are live (`project_delete` refuses while any is, unless forced). Default: none.
    pub live_sessions: LiveSessions,
}

const PROJECT_FRAMES: &[&str] = &[f::PROJECT_DELETE, f::PROJECT_SAVE, f::PROJECT_GET, f::PROJECT_LIST, f::PROJECT_BASE, f::PROJECT_CONFIG, f::WORKSPACE_SAVE, f::WORKSPACE_LIST];

impl ProjectsUnit {
    pub fn new(store: Arc<ProjectStore>, branches: Arc<TicketBranches>, fallback_repo: Option<PathBuf>) -> Self {
        let b = branches.clone();
        let cut_repo: CutRepo = Arc::new(move |p: &Project| {
            let dir = p.directory.trim();
            if !dir.is_empty() && b.common_dir(Path::new(dir)).is_some() {
                return Some(PathBuf::from(dir));
            }
            fallback_repo.clone()
        });
        let routing: RoutingValidator = Arc::new(|rules: Vec<RoutingRule>| {
            if rules.is_empty() {
                Ok(rules)
            } else {
                Err("this daemon has no harness list to check a provider / model against".into())
            }
        });
        ProjectsUnit { store, branches, cut_repo, routing, live_sessions: Arc::new(|_| Vec::new()) }
    }

    /// Accepts `branch` as project `p`'s base: the repo resolves it, and the project's directory is in that repo.
    fn check_base(&self, p: &Project, name: &str) -> Result<(), Refused> {
        let r = |s: String| Err(Refused(s));
        let Some(repo) = (self.cut_repo)(p) else {
            return if !p.directory.is_empty() && self.branches.common_dir(Path::new(&p.directory)).is_none() {
                r(format!("project directory '{}' is not a git repository, so base branches do not apply: its tickets run in the folder with no branch", p.directory))
            } else {
                r(format!("ticket starts cut in no git repository (set INFANTRY_REPO, or run the daemon in a checkout), so there is nothing to resolve '{name}' in"))
            };
        };
        let Some(repo_common) = self.branches.common_dir(&repo) else {
            return r(format!("the repository ticket starts cut in is not a git repository: {}", repo.display()));
        };
        if !p.directory.is_empty() && self.branches.common_dir(Path::new(&p.directory)).as_ref() != Some(&repo_common) {
            return r(format!(
                "project directory '{}' is not in the repository ticket starts cut in ({}); its branches are not the ones a ticket can be cut from",
                p.directory,
                repo.display()
            ));
        }
        if self.branches.resolve_base(&repo, name).is_some() {
            return Ok(());
        }
        if self.branches.resolve_base(&repo, "HEAD").is_none() {
            r(format!("repository {} has no commits yet; make a first commit, then set a base branch", repo.display()))
        } else {
            r(format!("base branch '{name}' does not resolve to a commit in {}", repo.display()))
        }
    }
}

impl FrameUnit for ProjectsUnit {
    fn name(&self) -> &'static str {
        "projects"
    }

    fn frames(&self) -> &'static [&'static str] {
        PROJECT_FRAMES
    }

    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let t = env.r#type.as_str();
        let rf = refused(t);
        let one = |p: Project| Ok(vec![reply(env, f::PROJECT, &p)]);
        match t {
            f::PROJECT_SAVE => {
                let p = self.store.save(&decode::<ProjectSave>(t, &env.data)?).map_err(rf)?;
                // Every client sees the new or renamed project.
                ctx.bus.publish(Envelope::new(f::PROJECT, serde_json::to_string(&p).unwrap_or_default()));
                one(p)
            }
            f::PROJECT_GET => one(self.store.get(&env.data).map_err(rf)?),
            f::PROJECT_LIST => Ok(vec![reply(env, f::PROJECTS, &self.store.list())]),
            f::PROJECT_DELETE => {
                let req: ProjectDeleteRequest = decode(t, &env.data)?;
                let p = self.store.get(&req.project_id).map_err(&rf)?;
                let live = (self.live_sessions)(&p.id);
                if !live.is_empty() && !req.force {
                    let who: Vec<String> = live.iter().take(5).map(|(id, role)| format!("{id} ({role})")).collect();
                    return Err(Refusal::new(
                        t,
                        format!(
                            "project {} '{}' has {} live session(s): {}{}; stop them, or delete with force",
                            p.id,
                            p.name,
                            live.len(),
                            who.join(", "),
                            if live.len() > who.len() { ", ..." } else { "" }
                        ),
                    ));
                }
                let gone = self.store.delete(&p.id).map_err(&rf)?;
                // Every client drops it.
                ctx.bus.publish(Envelope::new(f::PROJECT_DELETED, serde_json::to_string(&gone).unwrap_or_default()));
                Ok(vec![reply(env, f::PROJECT_DELETED, &gone)])
            }
            f::PROJECT_BASE => {
                let req: ProjectBaseRequest = decode(t, &env.data)?;
                if req.clear && req.branch.is_some() {
                    return Err(Refusal::new(t, "pass a branch or clear, not both"));
                }
                let p = if req.clear {
                    self.store.set_base_branch(&req.project_id, None, |_, _| Ok(()))
                } else if let Some(b) = req.branch.as_deref() {
                    let name = check_base_branch_name(b).map_err(&rf)?;
                    self.store.set_base_branch(&req.project_id, Some(&name), |p, n| self.check_base(p, n))
                } else {
                    self.store.get(&req.project_id)
                };
                one(p.map_err(rf)?)
            }
            f::PROJECT_CONFIG => {
                let req: ProjectConfigRequest = decode(t, &env.data)?;
                let caps = req.lane_caps.filter(|c| !c.is_empty());
                let exps = req.experiments.filter(|e| !e.is_empty());
                let data = req.data.unwrap_or_default();
                let unset = req.unset_data.unwrap_or_default();
                let untouched = caps.is_none() && req.auto_remove_done_workers.is_none() && req.routing_rules.is_none() && exps.is_none() && data.is_empty() && unset.is_empty();
                let mut p = self.store.get(&req.project_id).map_err(&rf)?;
                if untouched {
                    return one(p);
                }
                // Rules are validated first, so a refused list writes nothing at all.
                let rules = req.routing_rules.map(|r| (self.routing)(r)).transpose().map_err(|e| Refusal::new(t, e))?;
                if let Some(c) = &caps {
                    p = self.store.set_lane_caps(&req.project_id, c).map_err(&rf)?;
                }
                if let Some(r) = rules {
                    p = self.store.set_routing_rules(&req.project_id, r).map_err(&rf)?;
                }
                if let Some(on) = req.auto_remove_done_workers {
                    p = self.store.set_auto_remove_done_workers(&req.project_id, on).map_err(&rf)?;
                }
                if let Some(e) = &exps {
                    p = self.store.set_experiments(&req.project_id, e).map_err(&rf)?;
                }
                if !data.is_empty() || !unset.is_empty() {
                    p = self.store.set_data(&req.project_id, &data, &unset).map_err(&rf)?;
                }
                // Every client sees the changed project.
                ctx.bus.publish(Envelope::new(f::PROJECT, serde_json::to_string(&p).unwrap_or_default()));
                one(p)
            }
            f::WORKSPACE_SAVE => {
                let req: WorkspaceSave = decode(t, &env.data)?;
                let w = self.store.save_workspace(&req.project_id, &req.name, req.path.as_deref()).map_err(rf)?;
                Ok(vec![reply(env, f::WORKSPACE, &w)])
            }
            f::WORKSPACE_LIST => Ok(vec![reply(env, f::WORKSPACES, &self.store.workspaces(&env.data))]),
            _ => Err(Refusal::new(t, "not a projects frame")),
        }
    }
}

// ---- project git -------------------------------------------------------------------------------

/// The `project_git_*` requests.
pub struct ProjectGitUnit {
    pub store: Arc<ProjectStore>,
    pub service: Arc<ProjectGitService>,
    /// The directory a session runs in (its worktree or the shared checkout); `None` for an unknown session.
    pub session_checkout: SessionCheckout,
}

const GIT_FRAMES: &[&str] = &[
    f::PROJECT_GIT_STATUS,
    f::PROJECT_GIT_DIFF,
    f::PROJECT_GIT_LOG,
    f::PROJECT_GIT_BRANCHES,
    f::PROJECT_GIT_BRANCH_CHANGES,
    f::PROJECT_GIT_BRANCH_DIFF,
    f::PROJECT_GIT_WORKTREES,
    f::PROJECT_GIT_WORKTREE_STATUS,
    f::PROJECT_GIT_CHECKOUT,
];

impl ProjectGitUnit {
    pub fn new(store: Arc<ProjectStore>, service: Arc<ProjectGitService>) -> Self {
        ProjectGitUnit { store, service, session_checkout: Arc::new(|_| None) }
    }
}

impl FrameUnit for ProjectGitUnit {
    fn name(&self) -> &'static str {
        "project-git"
    }

    fn frames(&self) -> &'static [&'static str] {
        GIT_FRAMES
    }

    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let t = env.r#type.as_str();
        let rf = refused(t);
        let project = |id: &str| self.store.get(id).map_err(refused(t));
        let s = &self.service;
        let out = match t {
            f::PROJECT_GIT_STATUS => reply(env, f::PROJECT_GIT, &s.status(&project(&env.data)?)),
            f::PROJECT_GIT_DIFF => {
                let r: ProjectGitDiffRequest = decode(t, &env.data)?;
                reply(env, f::PROJECT_GIT_PATCH, &s.diff(&project(&r.project_id)?, &r.path, r.staged, r.worktree.as_deref()).map_err(rf)?)
            }
            f::PROJECT_GIT_LOG => {
                let r: ProjectGitLogRequest = decode(t, &env.data)?;
                reply(env, f::PROJECT_GIT_HISTORY, &s.log(&project(&r.project_id)?, r.limit))
            }
            f::PROJECT_GIT_BRANCHES => {
                let r: ProjectGitBranchesRequest = decode(t, &env.data)?;
                reply(env, f::PROJECT_GIT_BRANCH_LIST, &s.branches(&project(&r.project_id)?))
            }
            f::PROJECT_GIT_BRANCH_CHANGES => {
                let r: ProjectGitBranchChangesRequest = decode(t, &env.data)?;
                reply(env, f::PROJECT_GIT_BRANCH_DIFFSTAT, &s.branch_changes(&project(&r.project_id)?, &r.branch, r.base.as_deref()).map_err(rf)?)
            }
            f::PROJECT_GIT_BRANCH_DIFF => {
                let r: ProjectGitBranchDiffRequest = decode(t, &env.data)?;
                reply(env, f::PROJECT_GIT_PATCH, &s.branch_diff(&project(&r.project_id)?, &r.branch, r.base.as_deref(), &r.path).map_err(rf)?)
            }
            f::PROJECT_GIT_WORKTREES => {
                let r: ProjectGitWorktreesRequest = decode(t, &env.data)?;
                let p = project(&r.project_id)?;
                let dir = r.session_id.as_deref().filter(|s| !s.trim().is_empty()).and_then(|id| (self.session_checkout)(id));
                reply(env, f::PROJECT_GIT_WORKTREE_LIST, &s.worktrees(&p, dir.as_deref()))
            }
            f::PROJECT_GIT_WORKTREE_STATUS => {
                let r: ProjectGitWorktreeStatusRequest = decode(t, &env.data)?;
                reply(env, f::PROJECT_GIT_WORKTREE, &s.worktree_status(&project(&r.project_id)?, &r.path, r.history_limit).map_err(rf)?)
            }
            f::PROJECT_GIT_CHECKOUT => {
                let r: ProjectGitCheckoutRequest = decode(t, &env.data)?;
                let p = project(&r.project_id)?;
                let result = s.checkout(&p, &r.branch, r.create);
                if result.switched {
                    // Every open pane sees the new branch.
                    ctx.bus.publish(Envelope::new(f::PROJECT_GIT, serde_json::to_string(&s.status(&p)).unwrap_or_default()));
                }
                reply(env, f::PROJECT_GIT_CHECKOUT_RESULT, &result)
            }
            _ => return Err(Refusal::new(t, "not a project git frame")),
        };
        Ok(vec![out])
    }
}

// ---- session worktrees -------------------------------------------------------------------------

/// `worktrees` (all, or one session's as a `worktree` frame), `worktree_remove` and `repo`.
pub struct WorktreesUnit {
    pub manager: Arc<WorktreeManager>,
    /// The repository session worktrees are cut from (`repo` frame sets it).
    pub repo: Mutex<Option<PathBuf>>,
    /// Answers `worktrees` with data `stale`: the read-only report of every ticket worktree, as a JSON array.
    /// The host sets it (it knows the board and the sessions); this crate does not.
    pub stale: Mutex<Option<Arc<dyn Fn() -> String + Send + Sync>>>,
}

const WORKTREE_FRAMES: &[&str] = &[f::WORKTREES, f::WORKTREE_REMOVE, f::REPO];

impl WorktreesUnit {
    pub fn new(manager: Arc<WorktreeManager>, repo: Option<PathBuf>) -> Self {
        WorktreesUnit { manager, repo: Mutex::new(repo), stale: Mutex::new(None) }
    }

    pub fn repo(&self) -> Option<PathBuf> {
        self.repo.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }
}

impl FrameUnit for WorktreesUnit {
    fn name(&self) -> &'static str {
        "worktrees"
    }

    fn frames(&self) -> &'static [&'static str] {
        WORKTREE_FRAMES
    }

    fn handle(&self, env: &Envelope, _ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let t = env.r#type.as_str();
        match t {
            f::REPO => {
                let dir = env.data.trim();
                let resolved = (!dir.is_empty()).then(|| crate::paths::canonical(Path::new(dir)));
                *self.repo.lock().unwrap_or_else(|e| e.into_inner()) = resolved.clone();
                let mut e = Envelope::new(f::REPO, resolved.map(|p| p.to_string_lossy().into_owned()).unwrap_or_default());
                e.request_id = env.request_id.clone();
                Ok(vec![e])
            }
            f::WORKTREES => {
                let sid = env.data.trim();
                if sid == "stale" {
                    let report = self.stale.lock().unwrap_or_else(|e| e.into_inner()).clone();
                    let Some(report) = report else { return Err(Refusal::new(t, "no worktree report on this host")) };
                    // Answered as `worktree` (the hello pushes an unsolicited `worktrees`, which a waiting client would take for it).
                    let mut e = Envelope::new(f::WORKTREE, report());
                    e.request_id = env.request_id.clone();
                    return Ok(vec![e]);
                }
                if sid.is_empty() {
                    return Ok(vec![reply(env, f::WORKTREES, &self.manager.statuses())]);
                }
                let s = self.manager.status_of(sid).ok_or_else(|| Refusal(format!("no worktree known for session {sid}")))?;
                let mut e = reply(env, f::WORKTREE, &s);
                e.session_id = Some(sid.to_string());
                Ok(vec![e])
            }
            f::WORKTREE_REMOVE => {
                let parts: Vec<&str> = env.data.split_whitespace().collect();
                let Some(id) = parts.first() else { return Err(Refusal("worktree_remove expects '<sessionId> [force]'".into())) };
                let force = parts[1..].iter().any(|p| p.eq_ignore_ascii_case("force"));
                let r = self.manager.remove(id, force, false, false).map_err(|e| Refusal(e.0))?;
                let mut e = reply(env, f::WORKTREE_REMOVE, &r);
                e.session_id = Some(id.to_string());
                Ok(vec![e])
            }
            _ => Err(Refusal::new(t, "not a worktree frame")),
        }
    }
}
