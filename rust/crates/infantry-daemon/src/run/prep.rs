// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! What a run needs before its process starts (Kotlin `BranchWorktrees`, `RunManager.secretEnv` / `renderFor`):
//! - a BRANCH command (its cwd or argv names `{worktree}` or `{branch}`) runs in that branch's checkout: the worktree it
//!   is already checked out in, else a detached preview worktree under `<root>/.worktrees/preview-<slug>`, removed when
//!   the run ends;
//! - the command's secrets become environment variables (every field under its own name);
//! - its `template=out` renders are written before it starts and deleted when it ends.
//!
//! A refusal names a secret, a field or a path, never a value.

use infantry_projects::git::SharedGit;
use infantry_projects::Projects;
use infantry_vault::unit::VaultUnit;
use std::path::{Path, PathBuf};
use std::sync::Arc;

pub const WORKTREE: &str = "{worktree}";
pub const BRANCH: &str = "{branch}";
pub const ROOT: &str = "{root}";
pub const WORKTREES_DIR: &str = ".worktrees";
pub const PREVIEW_PREFIX: &str = "preview-";
/// Longest preview folder slug.
pub const MAX_SLUG_CHARS: usize = 60;
/// Secrets (and renders) one command may name (Kotlin `maxRunSecrets`).
pub const MAX_RUN_SECRETS: usize = 16;
/// Attempts to remove a preview worktree (a Windows file lock outlives its process for a moment).
const REMOVE_ATTEMPTS: usize = 5;
const REMOVE_RETRY_MS: u64 = 300;

/// The vault and the project git view, set once the daemon composes them (absent in a bare test).
pub struct Extras {
    pub vault: Arc<VaultUnit>,
    pub projects: Arc<Projects>,
}

/// True when the command names `{worktree}` or `{branch}` in its cwd or argv.
pub fn is_branch_command(argv: &[String], cwd: Option<&str>) -> bool {
    cwd.into_iter().chain(argv.iter().map(String::as_str)).any(|s| s.contains(WORKTREE) || s.contains(BRANCH))
}

fn uses(text: &str) -> bool {
    text.contains(WORKTREE) || text.contains(BRANCH) || text.contains(ROOT)
}

/// A templated cwd is `{worktree}` or `{worktree}/<relative path>`. None = valid, else why not.
pub fn cwd_refusal(cwd: &str) -> Option<String> {
    let t = cwd.trim().replace('\\', "/");
    let Some(rest) = t.strip_prefix(WORKTREE) else { return Some(format!("a cwd with a variable must start with {WORKTREE}")) };
    if !rest.is_empty() && !rest.starts_with('/') {
        return Some(format!("a cwd with a variable must be {WORKTREE} or {WORKTREE}/<path>"));
    }
    if uses(rest) {
        return Some(format!("a cwd names {WORKTREE} once and no other variable"));
    }
    if rest.split('/').any(|p| p == "..") {
        return Some("a cwd may not leave the worktree (..)".into());
    }
    None
}

pub fn substitute(text: &str, worktree: &Path, branch: &str, root: &Path) -> String {
    text.replace(WORKTREE, &worktree.to_string_lossy()).replace(BRANCH, branch).replace(ROOT, &root.to_string_lossy())
}

/// `template=out` -> (template, out); None when it is not that shape.
pub fn split_render(entry: &str) -> Option<(String, String)> {
    let (t, o) = entry.split_once('=')?;
    let (t, o) = (t.trim(), o.trim());
    (!t.is_empty() && !o.is_empty() && t != o).then(|| (t.to_string(), o.to_string()))
}

/// Saved-command checks the Kotlin registry makes: names, render shape, no renders on a branch command, a valid cwd.
pub fn check_command(argv: &[String], cwd: Option<&str>, secrets: &[String], renders: &[String]) -> Result<(), String> {
    if secrets.len() > MAX_RUN_SECRETS {
        return Err(format!("a command names at most {MAX_RUN_SECRETS} secrets"));
    }
    if let Some(bad) = secrets.iter().find(|s| !infantry_vault::keystore::is_name(s)) {
        return Err(format!("'{bad}' is not a secret name"));
    }
    if renders.len() > MAX_RUN_SECRETS {
        return Err(format!("a command names at most {MAX_RUN_SECRETS} renders"));
    }
    if renders.iter().any(|r| split_render(r).is_none()) {
        return Err("a render is not 'template=out'".into());
    }
    if is_branch_command(argv, cwd) {
        if !renders.is_empty() {
            return Err("a command that runs on a branch cannot render secret templates".into());
        }
        if let Some(c) = cwd.filter(|c| uses(c)) {
            if let Some(why) = cwd_refusal(c) {
                return Err(why);
            }
        }
    }
    Ok(())
}

/// One local branch for the picker, with the worktree it is checked out in (not the main checkout).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BranchInfo {
    pub name: String,
    pub worktree: Option<String>,
}

pub fn branches(projects: &Projects, project_id: &str) -> Result<Vec<BranchInfo>, String> {
    let project = projects.store.get(project_id).map_err(|e| format!("{e}"))?;
    let l = projects.service.branches(&project);
    if !l.available {
        return Err(if l.reason.is_empty() { "the project has no git checkout".into() } else { l.reason });
    }
    Ok(l.branches.into_iter().map(|b| BranchInfo { name: b.name, worktree: b.worktree_path }).collect())
}

/// The checkout a branch run uses; `created` = this run made it and removes it at the end.
#[derive(Debug, Clone)]
pub struct Prepared {
    pub dir: PathBuf,
    pub created: bool,
    pub root: PathBuf,
}

fn slug(branch: &str) -> String {
    let s: String = branch
        .to_lowercase()
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || "._-".contains(c) { c } else { '-' })
        .take(MAX_SLUG_CHARS)
        .collect();
    s.trim_matches(['-', '.']).to_string()
}

fn git_ok(git: &SharedGit, dir: &Path, args: &[&str]) -> Result<String, String> {
    match git.run(dir, args) {
        Ok(r) if r.ok() => Ok(r.stdout),
        Ok(r) => Err(r.err().lines().rev().find(|l| !l.trim().is_empty()).unwrap_or("git failed").to_string()),
        Err(e) => Err(format!("git did not start: {e}")),
    }
}

/// The branch's checkout: its worktree when it has one, else a new detached preview worktree at the branch tip.
pub fn prepare(projects: &Projects, project_id: &str, root: &Path, branch: &str) -> Result<Prepared, String> {
    if !infantry_projects::service::is_safe_branch_name(branch) {
        return Err(format!("'{branch}' is not a branch name"));
    }
    let git = &projects.git;
    git_ok(git, root, &["rev-parse", "--verify", "--quiet", &format!("refs/heads/{branch}")]).map_err(|_| format!("no local branch '{branch}'"))?;
    if let Some(wt) = branches(projects, project_id)?.into_iter().find(|b| b.name == branch).and_then(|b| b.worktree) {
        let dir = PathBuf::from(wt);
        if dir.is_dir() {
            return Ok(Prepared { dir, created: false, root: root.to_path_buf() });
        }
    }
    let dir = root.join(WORKTREES_DIR).join(format!("{PREVIEW_PREFIX}{}", slug(branch)));
    // A leftover of a run that died with the daemon: the preview is the branch tip as it is now.
    if dir.exists() && !remove_preview(git, root, &dir) {
        return Err(format!("the old preview folder {} could not be removed", dir.display()));
    }
    let d = dir.to_string_lossy().into_owned();
    if let Err(e) = git_ok(git, root, &["worktree", "add", "--detach", &d, &format!("refs/heads/{branch}")]) {
        remove_preview(git, root, &dir);
        return Err(format!("git worktree add failed: {e}"));
    }
    if !dir.is_dir() {
        return Err("git worktree add made no folder".into());
    }
    Ok(Prepared { dir, created: true, root: root.to_path_buf() })
}

/// Removes a preview worktree. Only a `preview-` folder directly under `<root>/.worktrees` is ever touched.
pub fn remove_preview(git: &SharedGit, root: &Path, dir: &Path) -> bool {
    let is_preview = dir.parent() == Some(&root.join(WORKTREES_DIR))
        && dir.file_name().and_then(|n| n.to_str()).is_some_and(|n| n.starts_with(PREVIEW_PREFIX) && n.len() > PREVIEW_PREFIX.len());
    if !is_preview {
        return false;
    }
    let d = dir.to_string_lossy().into_owned();
    for attempt in 0..REMOVE_ATTEMPTS {
        let _ = git.run(root, &["worktree", "remove", "--force", &d]);
        if dir.exists() {
            let _ = std::fs::remove_dir_all(dir);
        }
        if !dir.exists() {
            break;
        }
        if attempt + 1 < REMOVE_ATTEMPTS {
            std::thread::sleep(std::time::Duration::from_millis(REMOVE_RETRY_MS));
        }
    }
    let _ = git.run(root, &["worktree", "prune"]);
    !dir.exists()
}

/// The environment the named secrets give a run (a later secret's field of the same name wins).
pub fn secret_env(vault: &VaultUnit, names: &[String], project: Option<&str>) -> Result<Vec<(String, String)>, String> {
    if names.is_empty() {
        return Ok(vec![]);
    }
    if vault.handler().restricted() {
        return Err("secrets are locked under a restricted policy".into());
    }
    let mut env: Vec<(String, String)> = Vec::new();
    for name in names {
        let fields = vault
            .run_secrets()
            .fields(name, project)
            .map_err(|e| e.to_string())?
            .ok_or_else(|| format!("secret '{name}' is not in the keystore{}", project.map(|p| format!(" (project '{p}' or app)")).unwrap_or_default()))?;
        for (field, value) in fields {
            if field.to_uppercase().starts_with("INFANTRY_") {
                return Err(format!("secret '{name}' field '{field}' would override a daemon setting"));
            }
            env.retain(|(k, _)| *k != field);
            env.push((field, value));
        }
    }
    Ok(env)
}

/// Renders each `template=out` (relative to `base`, never leaving it); a failure removes what it wrote.
pub fn render_all(vault: &VaultUnit, base: &Path, renders: &[String], project: Option<&str>) -> Result<Vec<PathBuf>, String> {
    let mut done: Vec<PathBuf> = Vec::new();
    let undo = |done: &[PathBuf]| done.iter().for_each(|p| drop(std::fs::remove_file(p)));
    let root = std::path::absolute(base).map_err(|e| e.to_string())?;
    for entry in renders {
        let Some((t, o)) = split_render(entry) else { continue };
        let (tf, of) = (root.join(&t), root.join(&o));
        let inside = |p: &Path| !Path::new(&t).is_absolute() && !Path::new(&o).is_absolute() && p.components().all(|c| c != std::path::Component::ParentDir);
        if !inside(&tf) || !inside(&of) {
            undo(&done);
            return Err("a render path leaves the command folder".into());
        }
        let r = vault.renderer().render(&tf, Some(&of), project, false, false, "");
        if !r.ok() {
            undo(&done);
            return Err(r.summary(false));
        }
        done.push(of);
    }
    Ok(done)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn branch_commands_and_their_cwd() {
        let argv = |s: &str| s.split(' ').map(String::from).collect::<Vec<_>>();
        assert!(is_branch_command(&argv("npm run dev"), Some("{worktree}/web")));
        assert!(is_branch_command(&argv("echo {branch}"), None));
        assert!(!is_branch_command(&argv("echo {root}"), None));
        assert_eq!(cwd_refusal("{worktree}/web"), None);
        assert!(cwd_refusal("web/{worktree}").is_some());
        assert!(cwd_refusal("{worktree}/../x").is_some());
        assert!(check_command(&argv("echo {branch}"), None, &[], &["a=b".into()]).is_err(), "no renders on a branch");
        assert!(check_command(&argv("echo"), None, &[], &["only".into()]).is_err(), "render shape");
        assert_eq!(split_render(" .env.tpl = .env "), Some((".env.tpl".into(), ".env".into())));
        assert_eq!(slug("Feature/Big One"), "feature-big-one");
        let (w, r) = (Path::new("/w"), Path::new("/r"));
        assert_eq!(substitute("{worktree}:{branch}:{root}", w, "b", r), format!("{}:b:{}", w.display(), r.display()));
    }

    #[test]
    fn only_a_preview_folder_is_ever_removed() {
        struct NoGit;
        impl infantry_projects::git::GitCli for NoGit {
            fn run(&self, _: &Path, args: &[&str]) -> std::io::Result<infantry_projects::git::GitResult> {
                Ok(infantry_projects::git::GitResult { args: args.iter().map(|s| s.to_string()).collect(), exit_code: 0, stdout: String::new(), stderr: String::new() })
            }
        }
        let root = std::env::temp_dir().join(format!("infantry-prep-{}", uuid::Uuid::new_v4()));
        let keep = root.join("src");
        let preview = root.join(WORKTREES_DIR).join("preview-x");
        std::fs::create_dir_all(&keep).unwrap();
        std::fs::create_dir_all(&preview).unwrap();
        let git: SharedGit = Arc::new(NoGit);
        assert!(!remove_preview(&git, &root, &keep));
        assert!(keep.is_dir());
        assert!(remove_preview(&git, &root, &preview));
        assert!(!preview.exists());
        let _ = std::fs::remove_dir_all(&root);
    }
}
