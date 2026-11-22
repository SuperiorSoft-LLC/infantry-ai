// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RR-7 daemon side: the runner-side harness probe (usage and context window read the CLI's own files ON the runner), the
//! host the workspace code (`infantry-runner-pool::workspace`) asks for runners and git credentials, and the observer that
//! pushes a remote ticket worker's branch after every turn.
use crate::sessions::{Daemon, SessionObserver};
use infantry_harness::Probe;
use infantry_protocol::session::SessionRecord;
use infantry_runner_api::{LentCredentials, ProcessSpec, Runner};
use infantry_runner_source::remote::{block, exec, is_remote, RemoteHost};
use infantry_runner_source::RunnerEntry;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;
use tokio::runtime::Handle;

/// What the runners plugin asks of this daemon: its runners, credentials, the project's checkout and base branch, settings.
pub struct DaemonHost {
    pub daemon: Arc<Daemon>,
    pub projects: Arc<infantry_projects::Projects>,
    /// Where ticket starts cut when the project's own folder is not a repository.
    pub repo: Option<PathBuf>,
    pub setting: Arc<dyn Fn(&str) -> Option<serde_json::Value> + Send + Sync>,
}

impl RemoteHost for DaemonHost {
    fn runner(&self, name: &str) -> Result<RunnerEntry, String> {
        self.daemon.runner_registry().resolve(name).map_err(|r| r.to_string())
    }
    fn git_credentials(&self, runner: &str, project: Option<&str>) -> Option<LentCredentials> {
        self.daemon.lender().and_then(|l| l.git_credentials(runner, project))
    }
    fn repo_of(&self, project: Option<&str>) -> Option<PathBuf> {
        if let Some(d) = project.and_then(|p| super::project_dir(&self.projects, p)) {
            if self.projects.branches.common_dir(&d).is_some() {
                return Some(d);
            }
        }
        self.repo.clone().filter(|r| self.projects.branches.common_dir(r).is_some())
    }
    fn base_branch(&self, project: Option<&str>) -> Option<String> {
        project.and_then(|p| self.projects.store.base_branch_of(p))
    }
    fn setting(&self, key: &str) -> Option<serde_json::Value> {
        (self.setting)(key)
    }
}

/// [`Probe`] over a runner: the harness reads the CLI's own files ON the runner's host (usage stats, context window).
pub struct RunnerProbe {
    runner: Arc<dyn Runner>,
    handle: Handle,
    unix: bool,
}

impl RunnerProbe {
    pub fn new(runner: Arc<dyn Runner>, handle: Handle) -> Self {
        let probe = runner.clone();
        let unix = block(&handle, async move { probe.ping().await }).map(|p| p.os != "windows").unwrap_or(true);
        RunnerProbe { runner, handle, unix }
    }

    fn run(&self, program: &str, args: Vec<String>) -> Option<String> {
        let (code, out, _) = exec(&self.handle, &self.runner, ProcessSpec::new(program, args), Duration::from_secs(60)).ok()?;
        (code == 0).then_some(out)
    }
}

impl Probe for RunnerProbe {
    fn env(&self, name: &str) -> Option<String> {
        if !name.chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
            return None;
        }
        let v = if self.unix { self.run("sh", vec!["-c".into(), format!("printenv {name}")]) } else { self.run("cmd", vec!["/c".into(), format!("echo %{name}%")]).filter(|v| !v.contains('%')) };
        v.map(|v| v.trim().to_string()).filter(|v| !v.is_empty())
    }
    fn home(&self) -> Option<PathBuf> {
        self.env(if self.unix { "HOME" } else { "USERPROFILE" }).map(PathBuf::from)
    }
    fn read_to_string(&self, path: &Path) -> Option<String> {
        let p = path.to_string_lossy().into_owned();
        if self.unix {
            self.run("cat", vec![p.replace('\\', "/")])
        } else {
            self.run("cmd", vec!["/c".into(), "type".into(), p])
        }
    }
    fn exists(&self, path: &Path) -> bool {
        let p = path.to_string_lossy().into_owned();
        let (prog, args) = if self.unix { ("test", vec!["-e".to_string(), p.replace('\\', "/")]) } else { ("cmd", vec!["/c".to_string(), "dir".into(), "/b".into(), "/a".into(), p.replace('/', "\\")]) };
        exec(&self.handle, &self.runner, ProcessSpec::new(prog, args), Duration::from_secs(30)).map(|(c, _, _)| c == 0).unwrap_or(false)
    }
    fn list_dir(&self, path: &Path) -> Vec<PathBuf> {
        let p = path.to_string_lossy().into_owned();
        let out = if self.unix { self.run("ls", vec!["-1A".into(), p.replace('\\', "/")]) } else { self.run("cmd", vec!["/c".into(), "dir".into(), "/b".into(), "/a".into(), p]) };
        out.map(|o| o.lines().map(str::trim).filter(|l| !l.is_empty()).map(|l| path.join(l)).collect()).unwrap_or_default()
    }
}

/// Pushes a remote ticket worker's branch after every turn (unless `runners.pushOnCommit` pushes on each commit).
pub struct RemotePush {
    pub daemon: Arc<Daemon>,
}

impl SessionObserver for RemotePush {
    fn on_turn_end(&self, rec: &SessionRecord, _outcome: &str, _detail: &str) {
        if !is_remote(Some(&rec.runner)) {
            return;
        }
        let (Some(branch), Some(path)) = (rec.worktree_branch.clone(), rec.worktree_path.clone()) else { return };
        let Some(ws) = self.daemon.remote_workspace() else { return };
        if ws.push_on_commit() {
            return;
        }
        let (runner, project) = (rec.runner.clone(), rec.project_id.clone());
        // Off the session's lock: git on another machine takes as long as it takes.
        std::thread::spawn(move || {
            if let Err(e) = ws.push(&runner, project.as_deref(), &path, &branch) {
                eprintln!("[infantry] {e}");
            }
        });
    }
}
