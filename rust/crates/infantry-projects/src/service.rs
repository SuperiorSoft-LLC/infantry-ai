// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The project git view: status, diff, log, branches, branch diffstat/diff, worktrees and the one write (switch).
//!
//! Every read runs git in the project's OWN directory (resolved up to its top level) and takes no index lock
//! (`--no-optional-locks`: a plain `git status` refreshes the index, which makes an agent's own `git add` in the same
//! tree fail intermittently). A read that could not happen answers `available = false` with a reason; a request the
//! service refuses to hand to git at all (a path that could leave the repository, a malformed branch) is an
//! `Err(Refused)`, which the frame unit turns into `<frame> refused: <why>`.
//!
//! A branch or base name reaches git ONLY as `refs/heads/<name>` after `check-ref-format` and a lookup (or as a 40-hex
//! id that resolved); every later command gets the resolved commit ids, never the caller's string.
use crate::changes::{parse_name_status, parse_numstat, parse_porcelain};
use crate::git::{self, GitResult, SharedGit};
use crate::paths;
use crate::wire::*;
use infantry_protocol::projects::Project;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// A request refused before git saw it; the text is the reason (without the `<frame> refused:` prefix).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Refused(pub String);

impl std::fmt::Display for Refused {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

pub type Res<T> = Result<T, Refused>;

fn refuse<T>(why: impl Into<String>) -> Res<T> {
    Err(Refused(why.into()))
}

/// Caps and base resolution. Every number is a setting the daemon may override; defaults are the Kotlin ones.
#[derive(Debug, Clone)]
pub struct GitViewConfig {
    pub change_cap: usize,
    pub patch_cap: usize,
    pub branch_cap: usize,
    /// The integration branch a branch with no recorded base is compared against (before the repo default).
    pub integration_branch: Option<String>,
    /// Repo-relative file of `<branch>=<sha>` lines recording where a branch was cut from.
    pub expected_bases_path: Option<String>,
}

impl Default for GitViewConfig {
    fn default() -> Self {
        GitViewConfig { change_cap: 500, patch_cap: 256_000, branch_cap: 300, integration_branch: None, expected_bases_path: None }
    }
}

const HEADS: &str = "refs/heads/";
const SHORT_HASH: usize = 8;
const FS: char = '\u{1f}';
const RS: char = '\u{1e}';
const LOG_FORMAT: &str = "--format=%H%x1f%an%x1f%at%x1f%s%x1e";
const STATUS_ARGS: &[&str] = &["--no-optional-locks", "status", "--porcelain=v1", "-z", "--untracked-files=all"];

fn is_full_sha(s: &str) -> bool {
    s.len() == 40 && s.bytes().all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

fn shown(s: &str, n: usize) -> String {
    s.chars().filter(|c| !c.is_control()).take(n).collect()
}

/// Repo-relative, cannot climb out: no `..`, no absolute or drive-qualified form, no control character.
pub fn check_relative_path(raw: &str) -> Res<String> {
    let path = raw.trim();
    if path.is_empty() {
        return refuse("path is blank");
    }
    if path.chars().any(char::is_control) {
        return refuse("path contains a control character");
    }
    let n = path.replace('\\', "/");
    let b = n.as_bytes();
    if n.starts_with('/') || (b.len() >= 2 && b[0].is_ascii_alphabetic() && b[1] == b':') {
        return refuse(format!("path must be relative to the repository: '{}'", shown(path, 80)));
    }
    if n.split('/').any(|s| s == "..") {
        return refuse(format!("path must not leave the repository: '{}'", shown(path, 80)));
    }
    Ok(n)
}

/// No control char, no leading `-` or `/`, not `refs/…`, no `..` / `@{` / `\`, not `HEAD` / `@`.
pub fn is_safe_branch_name(name: &str) -> bool {
    !name.trim().is_empty()
        && name == name.trim()
        && !name.chars().any(char::is_control)
        && !name.starts_with('-')
        && !name.starts_with('/')
        && !name.starts_with("refs/")
        && !name.contains("..")
        && !name.contains("@{")
        && !name.contains('\\')
        && name != "HEAD"
        && name != "@"
}

pub fn check_branch_name(raw: &str) -> Res<()> {
    if is_safe_branch_name(raw) {
        Ok(())
    } else {
        refuse(format!("not a valid local branch name: '{}'", shown(raw, 80)))
    }
}

pub fn check_base_name(raw: &str) -> Res<()> {
    if is_full_sha(raw) || is_safe_branch_name(raw) {
        Ok(())
    } else {
        refuse(format!("not a valid base (local branch or full commit id): '{}'", shown(raw, 80)))
    }
}

enum Root {
    Found(PathBuf),
    Unavailable(String),
}

enum Range {
    Found { root: PathBuf, base: String, branch_sha: String, merge_base: String },
    Unavailable { base: String, reason: String },
}

#[derive(Debug, Clone, Default)]
struct Entry {
    path: String,
    head: Option<String>,
    branch: Option<String>,
    detached: bool,
    bare: bool,
    prunable: bool,
}

/// What a patch run produced, folded into a [`ProjectGitPatch`].
fn patch_from(result: &GitResult, cap: usize, mut base: ProjectGitPatch) -> ProjectGitPatch {
    if !result.ok() {
        base.available = false;
        base.reason = format!("git diff failed: {}", result.err().lines().next().unwrap_or(""));
        return base;
    }
    base.available = true;
    let text = &result.stdout;
    if text.lines().any(|l| l.starts_with("Binary files ") && l.ends_with(" differ")) {
        base.binary = true;
        return base;
    }
    if text.len() > cap {
        let mut end = cap;
        while !text.is_char_boundary(end) {
            end -= 1;
        }
        base.patch = text[..end].to_string();
        base.truncated = true;
    } else {
        base.patch = text.clone();
    }
    base
}

pub struct ProjectGitService {
    git: SharedGit,
    cfg: GitViewConfig,
    /// Top level of a project directory, found once (a `git rev-parse` per call cost 0.1-0.3 s on a busy machine).
    roots: Mutex<HashMap<String, PathBuf>>,
    /// Read answers (as JSON) with the repository fingerprint they were made at: served while no ref, HEAD or index moved.
    views: Mutex<HashMap<String, (u64, Instant, String)>>,
}

/// Everything that moves when a commit, a branch, a checkout or a staged change happens: the mtimes of HEAD, the index,
/// packed-refs and every file under refs/ (and the worktree list), folded to one number.
fn fingerprint(root: &Path) -> u64 {
    let mut dirs = vec![root.join(".git")];
    if let Ok(text) = std::fs::read_to_string(root.join(".git")) {
        if let Some(d) = text.trim().strip_prefix("gitdir:") {
            let d = PathBuf::from(d.trim());
            if let Ok(c) = std::fs::read_to_string(d.join("commondir")) {
                dirs.push(d.join(c.trim()));
            }
            dirs.insert(0, d);
        }
    }
    let mut acc: u64 = 0;
    let mut fold = |p: &Path| {
        if let Ok(m) = std::fs::metadata(p) {
            let t = m.modified().ok().and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map_or(0, |d| d.as_nanos() as u64);
            acc = acc.wrapping_mul(1_000_003).wrapping_add(t ^ m.len());
        }
    };
    for d in &dirs {
        for f in ["HEAD", "index", "packed-refs", "worktrees"] {
            fold(&d.join(f));
        }
        let mut stack = vec![d.join("refs")];
        while let Some(dir) = stack.pop() {
            fold(&dir);
            if let Ok(rd) = std::fs::read_dir(&dir) {
                for e in rd.flatten() {
                    match e.file_type() {
                        Ok(t) if t.is_dir() => stack.push(e.path()),
                        _ => fold(&e.path()),
                    }
                }
            }
        }
    }
    acc
}

impl ProjectGitService {
    pub fn new(git: SharedGit, cfg: GitViewConfig) -> Self {
        ProjectGitService { git, cfg, roots: Mutex::default(), views: Mutex::default() }
    }

    fn run(&self, cwd: &Path, args: &[&str]) -> GitResult {
        git::run(&*self.git, cwd, args)
    }

    /// A read served from the cache while the repository fingerprint holds (and, with a [ttl], while the answer is young).
    fn cached<T: serde::Serialize + serde::de::DeserializeOwned>(&self, project: &Project, key: &str, ttl: Option<Duration>, compute: impl FnOnce() -> T) -> T {
        let Root::Found(root) = self.resolve_root(project) else { return compute() };
        let fp = fingerprint(&root);
        let key = format!("{}|{}|{key}", project.id, project.directory);
        if let Some((f, at, json)) = self.views.lock().unwrap_or_else(|e| e.into_inner()).get(&key) {
            if *f == fp && ttl.is_none_or(|t| at.elapsed() < t) {
                if let Ok(v) = serde_json::from_str(json) {
                    return v;
                }
            }
        }
        let v = compute();
        if let Ok(json) = serde_json::to_string(&v) {
            self.views.lock().unwrap_or_else(|e| e.into_inner()).insert(key, (fp, Instant::now(), json));
        }
        v
    }

    /// The repository top level of the project's directory, or why there is none.
    fn resolve_root(&self, project: &Project) -> Root {
        let key = project.directory.trim().to_string();
        if let Some(r) = self.roots.lock().unwrap_or_else(|e| e.into_inner()).get(&key) {
            if r.join(".git").exists() {
                return Root::Found(r.clone());
            }
        }
        let found = self.resolve_root_uncached(project);
        if let Root::Found(r) = &found {
            self.roots.lock().unwrap_or_else(|e| e.into_inner()).insert(key, r.clone());
        }
        found
    }

    fn resolve_root_uncached(&self, project: &Project) -> Root {
        let configured = project.directory.trim();
        if configured.is_empty() {
            return Root::Unavailable("this project has no directory configured".into());
        }
        let dir = Path::new(configured);
        if !dir.is_dir() {
            return Root::Unavailable(format!("the project directory does not exist: {configured}"));
        }
        let top = match self.git.run(dir, &["rev-parse", "--show-toplevel"]) {
            Ok(t) => t,
            Err(e) => return Root::Unavailable(format!("git could not be run by the daemon: {e}")),
        };
        if !top.ok() || top.out().is_empty() {
            let why = top.first_err_line();
            let suffix = if why.is_empty() { String::new() } else { format!(" ({why})") };
            return Root::Unavailable(format!("the project directory is not a git repository: {configured}{suffix}"));
        }
        Root::Found(paths::native(Path::new(top.out())))
    }

    /// Not cached by result: an edit in the working tree moves no ref, so no fingerprint could tell it changed. The repo root is cached.
    pub fn status(&self, project: &Project) -> ProjectGitStatus {
        self.status_uncached(project)
    }

    fn status_uncached(&self, project: &Project) -> ProjectGitStatus {
        let mut s = ProjectGitStatus { project_id: project.id.clone(), directory: project.directory.clone(), ..Default::default() };
        let root = match self.resolve_root(project) {
            Root::Unavailable(r) => {
                s.reason = r;
                return s;
            }
            Root::Found(d) => d,
        };
        let porcelain = self.run(&root, STATUS_ARGS);
        if !porcelain.ok() {
            s.reason = format!("git status failed: {}", porcelain.err().lines().next().unwrap_or(""));
            return s;
        }
        let all = parse_porcelain(&porcelain.stdout);
        s.available = true;
        s.repo_root = root.to_string_lossy().into_owned();
        s.branch = git::current_branch(&*self.git, &root);
        s.head = git::head(&*self.git, &root);
        s.changes_truncated = all.len() > self.cfg.change_cap;
        s.changes = all.into_iter().take(self.cfg.change_cap).collect();
        s
    }

    /// The diff of one path; `worktree` reads it in that listed worktree of the project's repository instead.
    pub fn diff(&self, project: &Project, path: &str, staged: bool, worktree: Option<&str>) -> Res<ProjectGitPatch> {
        let relative = check_relative_path(path)?;
        let base = ProjectGitPatch {
            project_id: project.id.clone(),
            path: relative.clone(),
            staged,
            worktree: worktree.map(str::to_string),
            ..Default::default()
        };
        let root = match worktree {
            None => self.resolve_root(project),
            Some(w) => self.resolve_worktree(project, w)?,
        };
        let root = match root {
            Root::Unavailable(r) => return Ok(ProjectGitPatch { reason: r, ..base }),
            Root::Found(d) => d,
        };
        let untracked = !staged && {
            let r = self.run(&root, &["--no-optional-locks", "ls-files", "--others", "--exclude-standard", "-z", "--", &relative]);
            r.ok() && !r.stdout.trim().is_empty()
        };
        let result = if untracked {
            // Only a path git lists as untracked in THIS repository is read by the no-index diff. Exit 1 = "differs".
            // git special-cases the literal "/dev/null" here on every platform, Windows included.
            let mut r = self.run(&root, &["--no-optional-locks", "diff", "--no-color", "--no-ext-diff", "--no-index", "--", "/dev/null", &relative]);
            if r.exit_code == 1 {
                r.exit_code = 0;
            }
            r
        } else {
            let mut args = vec!["--no-optional-locks", "diff", "--no-color", "--no-ext-diff"];
            if staged {
                args.push("--cached");
            }
            args.extend(["--", relative.as_str()]);
            self.run(&root, &args)
        };
        Ok(patch_from(&result, self.cfg.patch_cap, base))
    }

    fn parse_log(out: &str) -> Vec<GitCommit> {
        out.split(RS)
            .filter_map(|rec| {
                let f: Vec<&str> = rec.trim_matches(['\n', '\r']).split(FS).collect();
                if f.len() < 4 {
                    return None;
                }
                Some(GitCommit {
                    hash: f[0].to_string(),
                    short_hash: f[0].chars().take(SHORT_HASH).collect(),
                    author: f[1].to_string(),
                    timestamp: f[2].parse().ok()?,
                    subject: f[3..].join(&FS.to_string()),
                })
            })
            .collect()
    }

    /// The newest `limit` commits (clamped to 1..=200), measuring "there is more" by asking for one extra.
    fn read_log(&self, dir: &Path, limit: i64, rev: Option<&str>) -> Result<(Vec<GitCommit>, bool), String> {
        let wanted = limit.clamp(1, LOG_MAX_LIMIT) as usize;
        let max = format!("--max-count={}", wanted + 1);
        let mut args = vec!["--no-optional-locks", "log", max.as_str(), LOG_FORMAT];
        if let Some(r) = rev {
            args.push(r);
        }
        let r = self.run(dir, &args);
        if !r.ok() {
            return Err(format!("git log failed: {}", r.err().lines().next().unwrap_or("")));
        }
        let mut commits = Self::parse_log(&r.stdout);
        let truncated = commits.len() > wanted;
        commits.truncate(wanted);
        Ok((commits, truncated))
    }

    pub fn log(&self, project: &Project, limit: i64) -> ProjectGitHistory {
        self.cached(project, &format!("log{limit}"), None, || self.log_uncached(project, limit))
    }

    fn log_uncached(&self, project: &Project, limit: i64) -> ProjectGitHistory {
        let mut h = ProjectGitHistory { project_id: project.id.clone(), ..Default::default() };
        let root = match self.resolve_root(project) {
            Root::Unavailable(r) => {
                h.reason = r;
                return h;
            }
            Root::Found(d) => d,
        };
        h.available = true;
        if git::head(&*self.git, &root).is_none() {
            return h; // no commit yet: an empty history, not a failure
        }
        match self.read_log(&root, limit, None) {
            Ok((c, t)) => {
                h.commits = c;
                h.truncated = t;
            }
            Err(r) => {
                h.available = false;
                h.reason = r;
            }
        }
        h
    }

    // ---- branches ------------------------------------------------------------------------------

    pub fn branches(&self, project: &Project) -> ProjectGitBranchList {
        self.cached(project, "branches", None, || self.branches_uncached(project))
    }

    fn branches_uncached(&self, project: &Project) -> ProjectGitBranchList {
        let mut l = ProjectGitBranchList { project_id: project.id.clone(), ..Default::default() };
        let root = match self.resolve_root(project) {
            Root::Unavailable(r) => {
                l.reason = r;
                return l;
            }
            Root::Found(d) => d,
        };
        let count = format!("--count={}", self.cfg.branch_cap + 1);
        let refs = self.run(&root, &["--no-optional-locks", "for-each-ref", &count, "--format=%(refname)%1f%(objectname)%1f%(upstream:short)", HEADS]);
        if !refs.ok() {
            l.reason = format!("git for-each-ref failed: {}", refs.err().lines().next().unwrap_or(""));
            return l;
        }
        let wts = self.worktree_branches(&root);
        let all: Vec<GitBranch> = refs
            .stdout
            .lines()
            .filter_map(|line| {
                let f: Vec<&str> = line.trim_end_matches('\r').split(FS).collect();
                if f.len() < 2 || !f[0].starts_with(HEADS) {
                    return None;
                }
                let name = f[0][HEADS.len()..].to_string();
                let wp = wts.get(&name).cloned();
                Some(GitBranch {
                    head: f[1].to_string(),
                    upstream: f.get(2).filter(|u| !u.is_empty()).map(|u| u.to_string()),
                    is_worktree: wp.is_some(),
                    worktree_path: wp,
                    name,
                })
            })
            .collect();
        l.available = true;
        l.current = git::current_branch(&*self.git, &root);
        l.truncated = all.len() > self.cfg.branch_cap;
        l.branches = all.into_iter().take(self.cfg.branch_cap).collect();
        l
    }

    pub fn branch_changes(&self, project: &Project, branch: &str, base: Option<&str>) -> Res<ProjectGitBranchDiffstat> {
        check_branch_name(branch)?;
        if let Some(b) = base {
            check_base_name(b)?;
        }
        let mut d = ProjectGitBranchDiffstat { project_id: project.id.clone(), branch: branch.to_string(), ..Default::default() };
        let (root, base_name, branch_sha, mb) = match self.resolve_range(project, branch, base)? {
            Range::Unavailable { base, reason } => {
                d.base = base;
                d.reason = reason;
                return Ok(d);
            }
            Range::Found { root, base, branch_sha, merge_base } => (root, base, branch_sha, merge_base),
        };
        d.base = base_name;
        d.merge_base = mb.clone();
        let common = ["--no-optional-locks", "diff", "--no-color", "--no-ext-diff", "-M", "-z"];
        let names = self.run(&root, &[&common[..], &["--name-status", &mb, &branch_sha, "--"]].concat());
        let stats = self.run(&root, &[&common[..], &["--numstat", &mb, &branch_sha, "--"]].concat());
        if let Some(failed) = [&names, &stats].into_iter().find(|r| !r.ok()) {
            d.reason = format!("git diff failed: {}", failed.err().lines().next().unwrap_or(""));
            return Ok(d);
        }
        let counts = parse_numstat(&stats.stdout);
        let all: Vec<_> = parse_name_status(&names.stdout)
            .into_iter()
            .map(|mut f| {
                match counts.get(&f.path) {
                    Some(Some((a, r))) => {
                        f.added = *a;
                        f.removed = *r;
                    }
                    Some(None) => f.binary = true,
                    None => {}
                }
                f
            })
            .collect();
        d.available = true;
        d.truncated = all.len() > self.cfg.change_cap;
        d.changes = all.into_iter().take(self.cfg.change_cap).collect();
        Ok(d)
    }

    pub fn branch_diff(&self, project: &Project, branch: &str, base: Option<&str>, path: &str) -> Res<ProjectGitPatch> {
        let relative = check_relative_path(path)?;
        check_branch_name(branch)?;
        if let Some(b) = base {
            check_base_name(b)?;
        }
        let p = ProjectGitPatch { project_id: project.id.clone(), path: relative.clone(), branch: Some(branch.to_string()), ..Default::default() };
        match self.resolve_range(project, branch, base)? {
            Range::Unavailable { reason, .. } => Ok(ProjectGitPatch { reason, ..p }),
            Range::Found { root, branch_sha, merge_base, .. } => {
                let r = self.run(&root, &["--no-optional-locks", "diff", "--no-color", "--no-ext-diff", &merge_base, &branch_sha, "--", &relative]);
                Ok(patch_from(&r, self.cfg.patch_cap, p))
            }
        }
    }

    fn resolve_range(&self, project: &Project, branch: &str, base: Option<&str>) -> Res<Range> {
        let root = match self.resolve_root(project) {
            Root::Unavailable(reason) => return Ok(Range::Unavailable { base: base.unwrap_or("").to_string(), reason }),
            Root::Found(d) => d,
        };
        let Some(branch_sha) = self.resolve_local_branch(&root, branch) else {
            return refuse(format!("no local branch '{}'", shown(branch, 80)));
        };
        let (base_name, base_sha) = match base {
            Some(b) => match self.resolve_base(&root, b) {
                Some(sha) => (b.to_string(), sha),
                None => return refuse(format!("no local branch or commit '{}'", shown(b, 80))),
            },
            None => match self.default_base(&root, branch, &branch_sha, &project.base_branch) {
                Some(x) => x,
                None => return Ok(Range::Unavailable { base: String::new(), reason: self.no_base_reason(branch) }),
            },
        };
        let mb = self.run(&root, &["merge-base", &base_sha, &branch_sha]);
        if !mb.ok() || mb.out().is_empty() {
            return Ok(Range::Unavailable {
                reason: format!("no merge-base between '{branch}' and '{base_name}': the histories are unrelated"),
                base: base_name,
            });
        }
        Ok(Range::Found { root, base: base_name, branch_sha, merge_base: mb.out().to_string() })
    }

    fn no_base_reason(&self, branch: &str) -> String {
        match &self.cfg.integration_branch {
            Some(i) => format!("no base found for '{branch}': no recorded base, no {i} branch and no default branch"),
            None => format!("no base found for '{branch}': no recorded base, no project base branch and no default branch"),
        }
    }

    fn resolve_local_branch(&self, root: &Path, name: &str) -> Option<String> {
        let r = format!("{HEADS}{name}");
        if !self.run(root, &["check-ref-format", &r]).ok() {
            return None;
        }
        git::resolve_commit(&*self.git, root, &r)
    }

    fn resolve_base(&self, root: &Path, base: &str) -> Option<String> {
        if is_full_sha(base) {
            git::resolve_commit(&*self.git, root, base)
        } else {
            self.resolve_local_branch(root, base)
        }
    }

    /// Recorded base, then the project's base branch, the integration branch, origin/HEAD, `init.defaultBranch`,
    /// `main`, `master`. Never the branch itself.
    fn default_base(&self, root: &Path, branch: &str, branch_sha: &str, project_base: &str) -> Option<(String, String)> {
        if let Some(sha) = self.recorded_base(root, branch, branch_sha) {
            if let Some(c) = self.resolve_base(root, &sha) {
                return Some((sha, c));
            }
        }
        let mut cands: Vec<String> = Vec::new();
        if !project_base.trim().is_empty() {
            cands.push(project_base.trim().to_string());
        }
        if let Some(i) = &self.cfg.integration_branch {
            cands.push(i.clone());
        }
        if let Some(o) = self.run(root, &["symbolic-ref", "--quiet", "refs/remotes/origin/HEAD"]).some_out() {
            cands.push(o.trim_start_matches("refs/remotes/origin/").to_string());
        }
        if let Some(d) = self.run(root, &["config", "--get", "init.defaultBranch"]).some_out() {
            cands.push(d);
        }
        cands.push("main".into());
        cands.push("master".into());
        let mut seen = Vec::new();
        for name in cands {
            if seen.contains(&name) || name == branch || !is_safe_branch_name(&name) {
                continue;
            }
            if let Some(c) = self.resolve_local_branch(root, &name) {
                return Some((name, c));
            }
            seen.push(name);
        }
        None
    }

    fn recorded_base(&self, root: &Path, branch: &str, branch_sha: &str) -> Option<String> {
        let rel = self.cfg.expected_bases_path.as_deref()?;
        let spec = format!("{branch_sha}:{rel}");
        let committed = self.run(root, &["cat-file", "-p", &spec]);
        let committed = committed.ok().then_some(committed.stdout);
        let on_disk = std::fs::read_to_string(root.join(rel)).ok();
        [committed, on_disk].into_iter().flatten().find_map(|text| {
            text.lines()
                .map(str::trim)
                .filter(|l| !l.starts_with('#') && l.split('=').next().unwrap_or("").trim() == branch)
                .filter_map(|l| l.split_once('=').map(|(_, v)| v.trim().to_lowercase()))
                .find(|v| is_full_sha(v))
        })
    }

    fn list_worktrees(&self, root: &Path) -> Result<Vec<Entry>, String> {
        let r = self.run(root, &["--no-optional-locks", "worktree", "list", "--porcelain"]);
        if !r.ok() {
            return Err(format!("git worktree list failed: {}", r.err().lines().next().unwrap_or("")));
        }
        Ok(parse_worktree_list(&r.stdout))
    }

    fn worktree_branches(&self, root: &Path) -> HashMap<String, String> {
        self.list_worktrees(root).unwrap_or_default().into_iter().filter_map(|e| e.branch.map(|b| (b, e.path))).collect()
    }

    // ---- worktree view -------------------------------------------------------------------------

    /// Every worktree of the project's repository; the one holding `session_dir` (else the project dir) is selected.
    /// "Holding" is the DEEPEST containing worktree, because linked worktrees often sit inside the main one.
    pub fn worktrees(&self, project: &Project, session_dir: Option<&str>) -> ProjectGitWorktreeList {
        self.cached(project, &format!("worktrees{session_dir:?}"), None, || self.worktrees_uncached(project, session_dir))
    }

    fn worktrees_uncached(&self, project: &Project, session_dir: Option<&str>) -> ProjectGitWorktreeList {
        let mut l = ProjectGitWorktreeList { project_id: project.id.clone(), ..Default::default() };
        let root = match self.resolve_root(project) {
            Root::Unavailable(r) => {
                l.reason = r;
                return l;
            }
            Root::Found(d) => d,
        };
        let listed = match self.list_worktrees(&root) {
            Ok(e) => e,
            Err(r) => {
                l.reason = r;
                return l;
            }
        };
        let holder = |dir: Option<&str>| -> Option<usize> {
            let d = dir.map(str::trim).filter(|d| !d.is_empty())?;
            listed
                .iter()
                .enumerate()
                .filter(|(_, w)| !w.bare && paths::contains(Path::new(&w.path), Path::new(d)))
                .max_by_key(|(_, w)| paths::depth_key(Path::new(&w.path)))
                .map(|(i, _)| i)
        };
        let ph = holder(Some(project.directory.as_str()));
        let sh = holder(session_dir);
        l.worktrees = listed
            .iter()
            .enumerate()
            .map(|(i, w)| GitWorktree {
                path: w.path.clone(),
                branch: w.branch.clone(),
                head: w.head.clone(),
                main: i == 0,
                project: Some(i) == ph,
                session: Some(i) == sh,
                detached: w.detached,
                bare: w.bare,
                prunable: w.prunable,
            })
            .collect();
        l.selected = sh.or(ph).or_else(|| listed.iter().position(|w| !w.bare)).map(|i| listed[i].path.clone());
        l.available = true;
        l
    }

    /// The directory of a worktree the project's repository lists; anything else is refused.
    fn resolve_worktree(&self, project: &Project, path: &str) -> Res<Root> {
        let wanted = path.trim();
        if wanted.is_empty() {
            return refuse("worktree path is blank");
        }
        if wanted.chars().any(char::is_control) {
            return refuse("worktree path contains a control character");
        }
        let root = match self.resolve_root(project) {
            Root::Found(d) => d,
            u => return Ok(u),
        };
        let listed = match self.list_worktrees(&root) {
            Ok(e) => e,
            Err(r) => return Ok(Root::Unavailable(r)),
        };
        let Some(m) = listed.iter().find(|w| !w.bare && (w.path == wanted || paths::same_dir(Path::new(&w.path), Path::new(wanted)))) else {
            return refuse(format!("not a worktree of this project's repository: '{}'", shown(wanted, 160)));
        };
        let dir = paths::native(Path::new(&m.path));
        if m.prunable || !dir.is_dir() {
            return Ok(Root::Unavailable(format!("the worktree directory does not exist: {}", m.path)));
        }
        Ok(Root::Found(dir))
    }

    pub fn worktree_status(&self, project: &Project, path: &str, history_limit: i64) -> Res<ProjectGitWorktreeStatus> {
        let mut s = ProjectGitWorktreeStatus { project_id: project.id.clone(), path: path.to_string(), ..Default::default() };
        let dir = match self.resolve_worktree(project, path)? {
            Root::Unavailable(r) => {
                s.reason = r;
                return Ok(s);
            }
            Root::Found(d) => d,
        };
        let porcelain = self.run(&dir, STATUS_ARGS);
        if !porcelain.ok() {
            s.reason = format!("git status failed: {}", porcelain.err().lines().next().unwrap_or(""));
            return Ok(s);
        }
        let all = parse_porcelain(&porcelain.stdout);
        s.branch = git::current_branch(&*self.git, &dir);
        s.head = git::head(&*self.git, &dir);
        match (&s.head, &s.branch) {
            (None, _) => s.base_reason = "no commit yet".into(),
            (_, None) => s.base_reason = "HEAD is detached".into(),
            (Some(head), Some(branch)) => match self.default_base(&dir, branch, head, &project.base_branch) {
                None => s.base_reason = self.no_base_reason(branch),
                Some((name, sha)) => {
                    s.base = name;
                    let range = format!("{sha}...{head}");
                    let c = self.run(&dir, &["--no-optional-locks", "rev-list", "--left-right", "--count", &range]);
                    let parts: Vec<i64> = if c.ok() { c.out().split_whitespace().filter_map(|p| p.parse().ok()).collect() } else { vec![] };
                    if parts.len() >= 2 {
                        s.behind = Some(parts[0]);
                        s.ahead = Some(parts[1]);
                    } else {
                        s.base_reason = format!("git rev-list failed: {}", c.err().lines().next().unwrap_or(""));
                    }
                }
            },
        }
        if let Some(head) = s.head.clone() {
            match self.read_log(&dir, history_limit, Some(&head)) {
                Ok((c, t)) => {
                    s.commits = c;
                    s.commits_truncated = t;
                }
                Err(r) => {
                    s.reason = r;
                    return Ok(ProjectGitWorktreeStatus { project_id: s.project_id, path: s.path, reason: s.reason, ..Default::default() });
                }
            }
        }
        let mut distinct: Vec<&str> = all.iter().map(|c| c.path.as_str()).collect();
        distinct.sort_unstable();
        distinct.dedup();
        s.uncommitted = distinct.len() as i64;
        s.available = true;
        s.changes_truncated = all.len() > self.cfg.change_cap;
        s.changes = all.into_iter().take(self.cfg.change_cap).collect();
        Ok(s)
    }

    // ---- the one write: switch the checked-out branch ------------------------------------------

    /// `git switch` in the project's top level, only when nothing can be lost (never force, stash or discard).
    /// Every refusal is an answer: name, repository, existence, other worktree, dirty tracked tree.
    pub fn checkout(&self, project: &Project, branch: &str, create: bool) -> ProjectGitCheckoutResult {
        use checkout_refusal::*;
        let base = ProjectGitCheckoutResult { project_id: project.id.clone(), branch: branch.to_string(), create, ..Default::default() };
        let refused = |code: &str, reason: String, previous: Option<String>, dirty: i64, held: Option<String>| ProjectGitCheckoutResult {
            switched: false,
            code: code.to_string(),
            reason,
            current: previous.clone(),
            previous,
            dirty_count: dirty,
            worktree_path: held,
            ..base.clone()
        };
        let sh = shown(branch, 80);
        if !is_safe_branch_name(branch) {
            return refused(BAD_NAME, format!("not a valid branch name: '{sh}'"), None, 0, None);
        }
        let root = match self.resolve_root(project) {
            Root::Unavailable(r) => return refused(UNAVAILABLE, r, None, 0, None),
            Root::Found(d) => d,
        };
        let full = format!("{HEADS}{branch}");
        if !self.run(&root, &["check-ref-format", &full]).ok() {
            return refused(BAD_NAME, format!("git rejects the branch name '{sh}'"), None, 0, None);
        }
        let previous = git::current_branch(&*self.git, &root);
        let exists = git::resolve_commit(&*self.git, &root, &full).is_some();
        if create && exists {
            return refused(EXISTS, format!("a local branch '{sh}' already exists"), previous, 0, None);
        }
        if !create && !exists {
            return refused(NO_BRANCH, format!("no local branch '{sh}'"), previous, 0, None);
        }
        if !create && previous.as_deref() == Some(branch) {
            return ProjectGitCheckoutResult { switched: true, reason: format!("already on '{sh}'"), current: previous.clone(), previous, ..base };
        }
        if !create {
            if let Some(holder) = self.worktree_branches(&root).get(branch) {
                if !paths::same_dir(Path::new(holder), &root) {
                    return refused(HELD_BY_WORKTREE, format!("'{sh}' is checked out in another worktree: {holder}"), previous, 0, Some(holder.clone()));
                }
            }
        }
        let porcelain = self.run(&root, &["--no-optional-locks", "status", "--porcelain=v1", "-z", "--untracked-files=no"]);
        if !porcelain.ok() {
            return refused(GIT_FAILED, format!("git status failed: {}", porcelain.err().lines().next().unwrap_or("")), previous, 0, None);
        }
        let mut dirty: Vec<String> = parse_porcelain(&porcelain.stdout).into_iter().map(|c| c.path).collect();
        dirty.sort();
        dirty.dedup();
        if !dirty.is_empty() {
            let files = if dirty.len() == 1 { "1 file has".to_string() } else { format!("{} files have", dirty.len()) };
            return refused(DIRTY, format!("the working tree is not clean: {files} uncommitted changes; commit them first"), previous, dirty.len() as i64, None);
        }
        let args: Vec<&str> = if create { vec!["switch", "--quiet", "-c", branch] } else { vec!["switch", "--quiet", "--no-guess", branch] };
        let r = self.run(&root, &args);
        if !r.ok() {
            return refused(GIT_FAILED, format!("git switch failed: {}", r.first_err_line()), previous, 0, None);
        }
        ProjectGitCheckoutResult { switched: true, previous, current: git::current_branch(&*self.git, &root), ..base }
    }
}

/// `git worktree list --porcelain`: blank-line separated records, the main worktree first.
fn parse_worktree_list(out: &str) -> Vec<Entry> {
    let mut entries = Vec::new();
    let mut cur: Option<Entry> = None;
    for raw in out.lines() {
        let line = raw.trim_end_matches('\r');
        if let Some(p) = line.strip_prefix("worktree ") {
            entries.extend(cur.take());
            cur = Some(Entry { path: p.to_string(), ..Default::default() });
            continue;
        }
        if line.is_empty() {
            entries.extend(cur.take());
            continue;
        }
        let Some(e) = cur.as_mut() else { continue };
        if let Some(h) = line.strip_prefix("HEAD ") {
            e.head = (!h.is_empty()).then(|| h.to_string());
        } else if let Some(b) = line.strip_prefix("branch refs/heads/") {
            e.branch = Some(b.to_string());
        } else if line == "detached" {
            e.detached = true;
        } else if line == "bare" {
            e.bare = true;
        } else if line == "prunable" || line.starts_with("prunable ") {
            e.prunable = true;
        }
    }
    entries.extend(cur);
    entries
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn paths_and_branch_names_are_checked_before_git() {
        assert_eq!(check_relative_path("src\\a.rs").unwrap(), "src/a.rs");
        assert!(check_relative_path("../etc/passwd").is_err());
        assert!(check_relative_path("C:/x").is_err());
        assert!(check_relative_path("/x").is_err());
        assert!(check_branch_name("ticket/abc-x").is_ok());
        for bad in ["-x", "a..b", "HEAD", "refs/heads/x", "a@{1}", " x"] {
            assert!(check_branch_name(bad).is_err(), "{bad}");
        }
        assert!(check_base_name(&"a".repeat(40)).is_ok());
    }

    #[test]
    fn worktree_list_parses_records() {
        let out = "worktree /r\nHEAD abc\nbranch refs/heads/main\n\nworktree /r/.worktrees/x\nHEAD def\ndetached\nprunable gitdir file points to non-existent location\n";
        let e = parse_worktree_list(out);
        assert_eq!(e.len(), 2);
        assert_eq!(e[0].branch.as_deref(), Some("main"));
        assert!(e[1].detached && e[1].prunable);
    }
}
