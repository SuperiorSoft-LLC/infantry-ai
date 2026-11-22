// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! RR-7: the seam for a ticket's workspace on a remote runner. The daemon core names only these traits; the runners plugin
//! (`infantry-runner-pool`) implements [`RemoteWorkspace`] and the composition root sets it on the daemon. The plugin asks
//! the daemon for what it owns (runners, credentials, the project's repository, settings) through [`RemoteHost`].
use crate::RunnerEntry;
use infantry_runner_api::{LentCredentials, ProcEvent, ProcessSpec, Runner};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;
use tokio::runtime::Handle;

/// Is `name` a runner other than `local`?
pub fn is_remote(name: Option<&str>) -> bool {
    name.map(str::trim).is_some_and(|n| !n.is_empty() && !n.eq_ignore_ascii_case(crate::LOCAL))
}

/// What the workspace code asks of the daemon that owns the runners.
pub trait RemoteHost: Send + Sync {
    /// The runner called `name` (online, paired) or the reason it cannot be used.
    fn runner(&self, name: &str) -> Result<RunnerEntry, String>;
    /// The git credential a start on `runner` would be lent (None = none lent).
    fn git_credentials(&self, runner: &str, project: Option<&str>) -> Option<LentCredentials>;
    /// The project's repository on this machine (the one whose `origin` the runner clones), if it has one.
    fn repo_of(&self, project: Option<&str>) -> Option<PathBuf>;
    /// The branch tickets of the project are cut from (None = the repository's default).
    fn base_branch(&self, project: Option<&str>) -> Option<String>;
    /// An app-scope setting.
    fn setting(&self, key: &str) -> Option<serde_json::Value>;
}

/// The ticket a workspace is made for.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TicketSpec {
    pub ticket_id: String,
    pub title: String,
    pub project_id: Option<String>,
    /// The branch to cut from (the epic's base); None = the project's.
    pub base_branch: Option<String>,
    pub recorded_branch: Option<String>,
    pub recorded_base: Option<String>,
}

/// A ready workspace on a runner.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Prepared {
    pub branch: String,
    pub base: String,
    /// The worktree folder ON the runner's host.
    pub path: String,
    pub note: Option<String>,
}

/// A ticket's branch and worktree made on a runner, and its branch brought back. Sync, like the workspace provider it backs.
pub trait RemoteWorkspace: Send + Sync {
    /// The ticket's worktree on `runner`, in the project's clone cache there, cut (or reused) on the ticket branch.
    fn ensure(&self, runner: &str, ticket: &TicketSpec) -> Result<Prepared, String>;
    /// The folder a session with no ticket branch runs in on `runner`: the project's clone there.
    fn clone_folder(&self, runner: &str, project: Option<&str>) -> Result<Option<String>, String>;
    /// Pushes `branch` from `worktree` on `runner` to origin and brings it into the project's repository here. Returns the pushed
    /// commit.
    fn push(&self, runner: &str, project: Option<&str>, worktree: &str, branch: &str) -> Result<String, String>;
    /// Whether commits are pushed as they are made (`runners.pushOnCommit`), so a turn end need not push.
    fn push_on_commit(&self) -> bool;
}

/// Runs a future to completion from a sync caller on any thread (inside a runtime or not).
pub fn block<F: std::future::Future + Send>(handle: &Handle, f: F) -> F::Output
where
    F::Output: Send,
{
    std::thread::scope(|s| s.spawn(|| handle.block_on(f)).join().expect("the runner call panicked"))
}

/// Runs one process on the runner to its end: (exit code, stdout, stderr).
pub fn exec(handle: &Handle, runner: &Arc<dyn Runner>, spec: ProcessSpec, timeout: Duration) -> std::io::Result<(i32, String, String)> {
    let r = runner.clone();
    block(handle, async move {
        let work = async {
            let h = r.spawn(spec).await.map_err(|e| std::io::Error::other(e.to_string()))?;
            let mut rx = r.events(&h.id).await.map_err(|e| std::io::Error::other(e.to_string()))?;
            let (mut out, mut err, mut code) = (Vec::new(), Vec::new(), -1);
            while let Some(ev) = rx.recv().await {
                match ev {
                    ProcEvent::Output(b) => out.extend(b),
                    ProcEvent::Stderr(b) => err.extend(b),
                    ProcEvent::Exited(c) => {
                        code = c;
                        break;
                    }
                    _ => {}
                }
            }
            let _ = r.release(&h.id).await;
            Ok::<_, std::io::Error>((code, String::from_utf8_lossy(&out).into_owned(), String::from_utf8_lossy(&err).into_owned()))
        };
        match tokio::time::timeout(timeout, work).await {
            Ok(r) => r,
            Err(_) => Err(std::io::Error::other(format!("the process timed out after {}s", timeout.as_secs()))),
        }
    })
}
