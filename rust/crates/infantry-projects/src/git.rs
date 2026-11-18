// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The one seam every git read and write goes through: `git <args>` in a working directory.
//!
//! Load-bearing facts (the same the Kotlin runner learned):
//! - stdout and stderr are drained on separate threads, so a chatty stderr can never deadlock a big stdout;
//! - a hung git (index lock, credential prompt) is bounded by a timeout and reported as exit 124;
//! - inherited `GIT_DIR`-style overrides are scrubbed, so the explicit working directory always wins;
//! - `GIT_TERMINAL_PROMPT=0`: there is no terminal to answer a credential prompt.
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::Arc;
use std::time::{Duration, Instant};

/// The outcome of one git run. A non-zero exit is data, never an error.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct GitResult {
    pub args: Vec<String>,
    pub exit_code: i32,
    pub stdout: String,
    pub stderr: String,
}

impl GitResult {
    pub fn ok(&self) -> bool {
        self.exit_code == 0
    }

    /// stdout without surrounding whitespace (git ends every captured line with a newline).
    pub fn out(&self) -> &str {
        self.stdout.trim()
    }

    /// stderr, falling back to stdout when git wrote its complaint to the other stream.
    pub fn err(&self) -> &str {
        let e = self.stderr.trim();
        if e.is_empty() {
            self.stdout.trim()
        } else {
            e
        }
    }

    /// The first non-blank line of [`GitResult::err`], what a user can act on.
    pub fn first_err_line(&self) -> &str {
        self.err().lines().map(str::trim).find(|l| !l.is_empty()).unwrap_or("")
    }

    /// `Some(out)` when the run succeeded and printed something.
    pub fn some_out(&self) -> Option<String> {
        (self.ok() && !self.out().is_empty()).then(|| self.out().to_string())
    }
}

/// Runs git. Implementations never panic on a non-zero exit; `Err` means git could not be started at all.
pub trait GitCli: Send + Sync {
    fn run(&self, cwd: &Path, args: &[&str]) -> std::io::Result<GitResult>;
}

pub type SharedGit = Arc<dyn GitCli>;

/// Exit code reported for a git that ran past its timeout.
pub const TIMEOUT_EXIT: i32 = 124;
/// The default bound on one git run.
pub const DEFAULT_TIMEOUT: Duration = Duration::from_secs(60);

const SCRUBBED_ENV: &[&str] = &[
    "GIT_DIR",
    "GIT_WORK_TREE",
    "GIT_COMMON_DIR",
    "GIT_INDEX_FILE",
    "GIT_OBJECT_DIRECTORY",
    "GIT_ALTERNATE_OBJECT_DIRECTORIES",
    "GIT_NAMESPACE",
    "GIT_PREFIX",
];

/// [`GitCli`] over the OS `git` executable.
#[derive(Debug, Clone)]
pub struct SystemGit {
    pub executable: PathBuf,
    pub timeout: Duration,
    /// Extra environment (tests inject a committer identity here instead of touching global config).
    pub extra_env: Vec<(String, String)>,
}

impl Default for SystemGit {
    fn default() -> Self {
        let executable = std::env::var_os("INFANTRY_GIT_EXE").filter(|v| !v.is_empty()).map(PathBuf::from).unwrap_or_else(|| PathBuf::from("git"));
        SystemGit { executable, timeout: DEFAULT_TIMEOUT, extra_env: Vec::new() }
    }
}

impl SystemGit {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn shared() -> SharedGit {
        Arc::new(Self::default())
    }
}

fn drain<R: Read + Send + 'static>(r: Option<R>) -> std::thread::JoinHandle<Vec<u8>> {
    std::thread::spawn(move || {
        let mut buf = Vec::new();
        if let Some(mut r) = r {
            let _ = r.read_to_end(&mut buf);
        }
        buf
    })
}

impl GitCli for SystemGit {
    fn run(&self, cwd: &Path, args: &[&str]) -> std::io::Result<GitResult> {
        let mut cmd = Command::new(&self.executable);
        cmd.args(args).current_dir(cwd).stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::piped());
        for k in SCRUBBED_ENV {
            cmd.env_remove(k);
        }
        cmd.env("GIT_TERMINAL_PROMPT", "0");
        for (k, v) in &self.extra_env {
            cmd.env(k, v);
        }
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            const CREATE_NO_WINDOW: u32 = 0x0800_0000;
            cmd.creation_flags(CREATE_NO_WINDOW);
        }
        let mut child = cmd.spawn()?;
        let out = drain(child.stdout.take());
        let err = drain(child.stderr.take());
        let started = Instant::now();
        let status = loop {
            match child.try_wait()? {
                Some(s) => break Some(s),
                None if started.elapsed() >= self.timeout => {
                    let _ = child.kill();
                    let _ = child.wait();
                    break None;
                }
                None => std::thread::sleep(Duration::from_millis(5)),
            }
        };
        let stdout = String::from_utf8_lossy(&out.join().unwrap_or_default()).into_owned();
        let stderr = String::from_utf8_lossy(&err.join().unwrap_or_default()).into_owned();
        let args: Vec<String> = args.iter().map(|s| s.to_string()).collect();
        Ok(match status {
            Some(s) => GitResult { args, exit_code: s.code().unwrap_or(-1), stdout, stderr },
            None => GitResult { args, exit_code: TIMEOUT_EXIT, stdout, stderr: format!("git timed out after {}ms", self.timeout.as_millis()) },
        })
    }
}

/// Runs git and folds a start failure into a failed result (exit -1), for callers that only read.
pub fn run(git: &dyn GitCli, cwd: &Path, args: &[&str]) -> GitResult {
    git.run(cwd, args).unwrap_or_else(|e| GitResult {
        args: args.iter().map(|s| s.to_string()).collect(),
        exit_code: -1,
        stdout: String::new(),
        stderr: format!("git could not be run: {e}"),
    })
}

/// True when `dir` is inside a git work tree (the main checkout or a linked worktree).
pub fn is_repo(git: &dyn GitCli, dir: &Path) -> bool {
    dir.is_dir() && {
        let r = run(git, dir, &["rev-parse", "--is-inside-work-tree"]);
        r.ok() && r.out() == "true"
    }
}

/// The commit `rev` resolves to in `cwd`, or `None`.
pub fn resolve_commit(git: &dyn GitCli, cwd: &Path, rev: &str) -> Option<String> {
    let spec = format!("{rev}^{{commit}}");
    run(git, cwd, &["rev-parse", "--verify", "--quiet", &spec]).some_out().and_then(|s| s.lines().next().map(|l| l.trim().to_string()))
}

/// The checked-out branch of `dir`; `None` when HEAD is detached (works before the first commit).
pub fn current_branch(git: &dyn GitCli, dir: &Path) -> Option<String> {
    run(git, dir, &["symbolic-ref", "--quiet", "--short", "HEAD"]).some_out()
}

/// HEAD's commit id; `None` in a repository with no commit yet.
pub fn head(git: &dyn GitCli, dir: &Path) -> Option<String> {
    run(git, dir, &["rev-parse", "--verify", "--quiet", "HEAD"]).some_out()
}

/// The main checkout's root, even when `repo` is a linked worktree (`--git-common-dir`).
pub fn main_root(git: &dyn GitCli, repo: &Path) -> PathBuf {
    let r = run(git, repo, &["rev-parse", "--path-format=absolute", "--git-common-dir"]);
    if r.ok() {
        let common = PathBuf::from(r.out().lines().next().unwrap_or("").trim());
        if common.is_dir() && common.file_name().is_some_and(|n| n == ".git") {
            if let Some(parent) = common.parent() {
                return crate::paths::native(parent);
            }
        }
    }
    crate::paths::canonical(repo)
}

/// The shared git directory of `dir` (canonical), or `None` when `dir` is not in a repository.
pub fn common_dir(git: &dyn GitCli, dir: &Path) -> Option<PathBuf> {
    if !dir.is_dir() {
        return None;
    }
    let r = run(git, dir, &["rev-parse", "--path-format=absolute", "--git-common-dir"]);
    let line = r.some_out()?.lines().next()?.trim().to_string();
    (!line.is_empty()).then(|| crate::paths::canonical(Path::new(&line)))
}
