// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! RR-7: a ticket on a remote runner works in its own ticket branch ON the runner, and the branch comes back.
//!
//! Everything here is processes over the same [`Runner`] the harness uses: `git` runs on the runner's host through
//! [`RunnerGit`] (a [`GitCli`] over a runner), so the branch and worktree code is the local one
//! ([`TicketBranches`]), not a copy. Per project the runner keeps a clone cache cloned from the project's `origin`
//! (fetched when it exists), and each ticket gets its worktree in it on the same `ticket/<short-id>-<slug>` branch a local
//! start cuts, from `origin/<base>`. The orchestrator's git identity is written into the clone. The branch is pushed after
//! every turn ([`RemotePush`]), or on each commit (a `post-commit` hook) when `runners.pushOnCommit` is on.
//!
use infantry_projects::git::{self, GitCli, GitResult, SharedGit, SystemGit};
use infantry_projects::ticket_branches::{TicketBranchOutcome, TicketBranchRequest, TicketBranches};
use infantry_projects::worktree::slug;
use infantry_runner_api::{LendingRunner, ProcessSpec, Runner};
use infantry_runner_source::remote::{block, exec, is_remote, Prepared, RemoteHost, RemoteWorkspace, TicketSpec};
use infantry_runner_source::RunnerEntry;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::runtime::Handle;

/// The setting that pushes on each commit instead of after each turn (default off).
pub const PUSH_ON_COMMIT_KEY: &str = "runners.pushOnCommit";
/// How long one process on the runner may run (a clone of a big repository included).
const CALL_TIMEOUT: Duration = Duration::from_secs(600);

/// `git` on a runner's host. Paths the orchestrator builds with its own separators are written the way the runner's OS
/// writes them.
pub struct RunnerGit {
    runner: Arc<dyn Runner>,
    handle: Handle,
    unix: bool,
}

impl RunnerGit {
    pub fn new(runner: Arc<dyn Runner>, handle: Handle) -> Arc<Self> {
        let probe = runner.clone();
        let unix = block(&handle, async move { probe.ping().await }).map(|p| p.os != "windows").unwrap_or(true);
        Arc::new(RunnerGit { runner, handle, unix })
    }

    /// A path as the runner's host writes it.
    pub fn native(&self, p: &str) -> String {
        if self.unix {
            p.replace('\\', "/")
        } else {
            p.replace('/', "\\")
        }
    }

    pub fn runner(&self) -> &Arc<dyn Runner> {
        &self.runner
    }
}

impl GitCli for RunnerGit {
    fn run(&self, cwd: &Path, args: &[&str]) -> std::io::Result<GitResult> {
        let argv: Vec<String> = args.iter().map(|a| if a.contains('\\') && self.unix && a.starts_with('/') { self.native(a) } else { (*a).to_string() }).collect();
        let spec = ProcessSpec::new("git", argv.clone()).cwd(self.native(&cwd.to_string_lossy())).env("GIT_TERMINAL_PROMPT", "0");
        let (exit_code, stdout, stderr) = exec(&self.handle, &self.runner, spec, CALL_TIMEOUT)?;
        Ok(GitResult { args: args.iter().map(|s| s.to_string()).collect(), exit_code, stdout, stderr })
    }

    fn path_exists(&self, path: &Path) -> bool {
        let p = self.native(&path.to_string_lossy());
        let spec = if self.unix { ProcessSpec::new("test", ["-e".to_string(), p]) } else { ProcessSpec::new("cmd", ["/c".to_string(), "dir".into(), "/b".into(), "/a".into(), p]) };
        exec(&self.handle, &self.runner, spec, Duration::from_secs(30)).map(|(c, _, _)| c == 0).unwrap_or(false)
    }
}

/// The runners plugin's remote workspaces: the clone cache, the ticket worktree and the push, over the runner's processes.
pub struct RemoteWorkspaces {
    host: Arc<dyn RemoteHost>,
    handle: Handle,
    local_git: SharedGit,
    /// One workspace preparation at a time: two starts share a clone cache and git locks its refs.
    busy: Mutex<()>,
}

impl RemoteWorkspaces {
    pub fn new(host: Arc<dyn RemoteHost>, handle: Handle) -> Arc<Self> {
        Arc::new(RemoteWorkspaces { host, handle, local_git: SystemGit::shared(), busy: Mutex::new(()) })
    }

    fn entry(&self, name: &str) -> Result<RunnerEntry, String> {
        self.host.runner(name)
    }

    /// Git on the runner, lent the git credential RR-6 lends a start (so a clone, fetch or push of a private origin works).
    fn git_on(&self, entry: &RunnerEntry, project: Option<&str>) -> Arc<RunnerGit> {
        let mut runner = entry.runner.clone();
        if entry.kind == "remote" {
            if let Some(lent) = self.host.git_credentials(&entry.name, project) {
                runner = Arc::new(LendingRunner::new(runner, lent));
            }
        }
        RunnerGit::new(runner, self.handle.clone())
    }

    /// The project's `origin` and the orchestrator's git identity, read in the project's own checkout here.
    fn origin_of(&self, repo: &Path) -> Result<(String, Option<String>, Option<String>), String> {
        let origin = git::run(&*self.local_git, repo, &["remote", "get-url", "origin"]).some_out().map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
        let origin = origin.ok_or_else(|| format!("{} has no `origin` remote: a remote runner clones the project from it", repo.display()))?;
        let cfg = |k: &str| git::run(&*self.local_git, repo, &["config", "--get", k]).some_out().map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
        Ok((origin, cfg("user.name"), cfg("user.email")))
    }

    /// The clone cache of `project` on the runner: cloned from `origin`, or fetched when it exists. Returns its folder there.
    fn clone_cache(&self, entry: &RunnerEntry, git_on: &Arc<RunnerGit>, project: Option<&str>, repo: &Path) -> Result<PathBuf, String> {
        let (origin, name, email) = self.origin_of(repo)?;
        let scratch = infantry_runner_source::RunnerRegistry::scratch_dir(entry)?;
        let key = slug(project.unwrap_or("project"), 40, "project");
        let cache = PathBuf::from(git_on.native(&format!("{}/clones/{key}", scratch.trim_end_matches(['/', '\\']))));
        if git_on.path_exists(&cache.join(".git")) {
            let _ = git::run(&**git_on, &cache, &["remote", "set-url", "origin", &origin]);
            let f = git::run(&**git_on, &cache, &["fetch", "--prune", "--quiet", "origin", "+refs/heads/*:refs/remotes/origin/*"]);
            if !f.ok() {
                return Err(format!("git fetch on runner '{}' failed: {}", entry.name, f.first_err_line()));
            }
        } else {
            let target = git_on.native(&cache.to_string_lossy());
            let c = git::run(&**git_on, Path::new(&scratch), &["clone", "--quiet", &origin, &target]);
            if !c.ok() {
                return Err(format!("git clone of the project's origin on runner '{}' failed: {}", entry.name, c.first_err_line()));
            }
        }
        // The orchestrator's identity, so a commit made on the runner is the owner's (RUNNER-GIT-IDENT-1).
        for (k, v) in [("user.name", name.as_deref().unwrap_or("Infantry AI")), ("user.email", email.as_deref().unwrap_or("infantry@localhost"))] {
            let r = git::run(&**git_on, &cache, &["config", k, v]);
            if !r.ok() {
                return Err(format!("could not set {k} in the clone on runner '{}': {}", entry.name, r.first_err_line()));
            }
        }
        self.sync_push_hook(git_on, &cache);
        Ok(cache)
    }

    /// `runners.pushOnCommit` on: a `post-commit` hook in the clone pushes each commit; off: the hook this wrote is removed.
    fn sync_push_hook(&self, git_on: &Arc<RunnerGit>, cache: &Path) {
        if !git_on.unix {
            return;
        }
        let hook = git_on.native(&format!("{}/.git/hooks/post-commit", cache.to_string_lossy()));
        let sh = |script: &str| {
            let spec = ProcessSpec::new("sh", ["-c".to_string(), script.to_string(), hook.clone()]);
            exec(&self.handle, git_on.runner(), spec, Duration::from_secs(30)).map(|(c, _, _)| c == 0).unwrap_or(false)
        };
        if self.push_on_commit() {
            sh("mkdir -p \"$(dirname \"$0\")\" && printf '%s\\n' '#!/bin/sh' '# infantry: runners.pushOnCommit' 'git push -q origin HEAD >/dev/null 2>&1 || true' > \"$0\" && chmod +x \"$0\"");
        } else {
            sh("if grep -q 'infantry: runners.pushOnCommit' \"$0\" 2>/dev/null; then rm -f \"$0\"; fi");
        }
    }

    fn repo(&self, project: Option<&str>) -> Result<PathBuf, String> {
        self.host.repo_of(project).ok_or_else(|| "no repository to clone on the runner: the project has no checkout here".to_string())
    }
}

impl RemoteWorkspace for RemoteWorkspaces {
    fn ensure(&self, runner: &str, req: &TicketSpec) -> Result<Prepared, String> {
        let project = req.project_id.as_deref();
        let repo = self.repo(project)?;
        let base = req.base_branch.clone().filter(|b| !b.trim().is_empty()).or_else(|| self.host.base_branch(project)).unwrap_or_else(|| "HEAD".into());
        let entry = self.entry(runner)?;
        let g = self.git_on(&entry, project);
        let _g = self.busy.lock().unwrap_or_else(|e| e.into_inner());
        let cache = self.clone_cache(&entry, &g, project, &repo)?;
        // A branch an earlier start pushed but this clone has never seen: bring it back before reattaching, so its commits stay.
        if let Some(b) = req.recorded_branch.as_deref() {
            let local = format!("refs/heads/{b}");
            let remote = format!("refs/remotes/origin/{b}");
            if !git::run(&*g, &cache, &["rev-parse", "--verify", "--quiet", &local]).ok() && git::run(&*g, &cache, &["rev-parse", "--verify", "--quiet", &remote]).ok() {
                let _ = git::run(&*g, &cache, &["branch", b, &format!("origin/{b}")]);
            }
        }
        let base_ref = if base.is_empty() || base == "HEAD" { "origin/HEAD".to_string() } else { format!("origin/{base}") };
        let branches = TicketBranches::new(g.clone() as SharedGit);
        let out = branches.ensure(&TicketBranchRequest {
            ticket_id: req.ticket_id.clone(),
            title: req.title.clone(),
            repo: cache.clone(),
            base_branch: base_ref,
            recorded_branch: req.recorded_branch.clone(),
            recorded_base: req.recorded_base.clone(),
        });
        match out {
            TicketBranchOutcome::Ready { worktree, note, warning } => {
                let note = match (note, warning) {
                    (Some(n), Some(w)) => Some(format!("{n}; {w}")),
                    (n, w) => n.or(w),
                };
                Ok(Prepared { branch: worktree.branch, base: worktree.base, path: g.native(&worktree.path), note: note.map(|n| format!("{n} on runner {}", entry.name)) })
            }
            TicketBranchOutcome::Refused(why) => Err(why),
        }
    }

    fn clone_folder(&self, runner: &str, project: Option<&str>) -> Result<Option<String>, String> {
        // A project with no checkout here has nothing to clone: the session keeps the runner's scratch folder.
        let Some(repo) = self.host.repo_of(project) else { return Ok(None) };
        let entry = self.entry(runner)?;
        let g = self.git_on(&entry, project);
        let _g = self.busy.lock().unwrap_or_else(|e| e.into_inner());
        let cache = self.clone_cache(&entry, &g, project, &repo)?;
        Ok(Some(g.native(&cache.to_string_lossy())))
    }

    fn push(&self, runner: &str, project: Option<&str>, worktree: &str, branch: &str) -> Result<String, String> {
        let repo = self.repo(project)?;
        let entry = self.entry(runner)?;
        let g = self.git_on(&entry, project);
        let dir = PathBuf::from(g.native(worktree));
        let head = git::run(&*g, &dir, &["rev-parse", "--verify", "HEAD"]).some_out().map(|s| s.trim().to_string()).ok_or_else(|| format!("no worktree at {worktree} on runner '{}'", entry.name))?;
        let refspec = format!("HEAD:refs/heads/{branch}");
        let p = git::run(&*g, &dir, &["push", "--quiet", "origin", &refspec]);
        if !p.ok() {
            return Err(format!("git push of {branch} from runner '{}' failed: {}", entry.name, p.first_err_line()));
        }
        // The branch comes back to the orchestrator's repository (best effort: the push already holds the work).
        let into = format!("+refs/heads/{branch}:refs/heads/{branch}");
        let f = git::run(&*self.local_git, &repo, &["fetch", "--quiet", "origin", &into]);
        if !f.ok() {
            eprintln!("[infantry] could not fetch {branch} into {}: {}", repo.display(), f.first_err_line());
        }
        Ok(head)
    }

    fn push_on_commit(&self) -> bool {
        match self.host.setting(PUSH_ON_COMMIT_KEY) {
            Some(serde_json::Value::Bool(b)) => b,
            Some(serde_json::Value::String(s)) => s.trim().eq_ignore_ascii_case("true"),
            _ => false,
        }
    }
}

#[allow(dead_code)]
fn _is_remote_is_reexported(n: &str) -> bool {
    is_remote(Some(n))
}
