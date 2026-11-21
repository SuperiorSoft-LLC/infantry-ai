// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! WORKSPACE-1: the OS home folder. A session started with no project and no folder runs in its own folder,
//! `<root>/agents/<session-id>`, never the daemon's own folder. The root is a named setting; the composition root hands in a
//! [`WorkspaceSpec`] read at each creation, so a change applies to the next session. Any later app reads the same setting.

use super::{Daemon, NewOpts};
use std::path::{Path, PathBuf};
use std::sync::Arc;

/// The app setting that names the OS home folder.
pub const ROOT_KEY: &str = "workspace.root";
/// The app setting: keep a no-project agent's folder when its session is removed (default false = remove it).
pub const KEEP_KEY: &str = "workspace.keep_agent_folders";
/// The sub-folder of the root that holds the no-project agents' folders.
pub const AGENTS_DIR: &str = "agents";

/// Where no-project sessions start and whether their folders outlive them.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct WorkspaceSpec {
    pub root: PathBuf,
    pub keep: bool,
}

/// The default root: the user's home folder plus `Infantry AI` (None when the home folder is unknown).
pub fn default_root() -> Option<PathBuf> {
    let home = std::env::var_os("USERPROFILE").or_else(|| std::env::var_os("HOME")).filter(|h| !h.is_empty())?;
    Some(PathBuf::from(home).join("Infantry AI"))
}

/// Reads the spec from the two settings (blank or absent root = the default root).
pub fn spec_from(root: Option<String>, keep: Option<bool>) -> Option<WorkspaceSpec> {
    let root = root.map(|r| r.trim().to_string()).filter(|r| !r.is_empty()).map(PathBuf::from).or_else(default_root)?;
    Some(WorkspaceSpec { root, keep: keep.unwrap_or(false) })
}

/// The folder of a no-project session, made on demand.
pub fn agent_dir(root: &Path, id: &str) -> PathBuf {
    root.join(AGENTS_DIR).join(id)
}

impl Daemon {
    /// Sets where the workspace root comes from (set once, at composition).
    pub fn set_workspace_spec(&self, f: Arc<dyn Fn() -> Option<WorkspaceSpec> + Send + Sync>) {
        let _ = self.workspace_spec.set(f);
    }

    fn workspace(&self) -> Option<WorkspaceSpec> {
        self.workspace_spec.get().and_then(|f| f())
    }

    /// For a session with no folder and no project: its own folder under the root (made now). None = use the default cwd.
    pub(super) fn own_folder(&self, opts: &NewOpts, id: &str) -> Result<Option<PathBuf>, String> {
        if opts.cwd.as_deref().is_some_and(|c| !c.trim().is_empty()) || opts.project_id.as_deref().is_some_and(|p| !p.trim().is_empty()) {
            return Ok(None);
        }
        let Some(ws) = self.workspace() else { return Ok(None) };
        let dir = agent_dir(&ws.root, id);
        std::fs::create_dir_all(&dir).map_err(|e| format!("session refused: cannot make its folder {}: {e}", dir.display()))?;
        Ok(Some(dir))
    }

    /// Removes a removed session's own folder (only `<root>/agents/<id>` itself, and only when the setting says so).
    pub(super) fn drop_own_folder(&self, working_dir: &str, id: &str) {
        let Some(ws) = self.workspace() else { return };
        if ws.keep {
            return;
        }
        let dir = agent_dir(&ws.root, id);
        if Path::new(working_dir) == dir {
            let _ = std::fs::remove_dir_all(&dir);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::sessions::Config;

    fn daemon(root: PathBuf, keep: bool, state: &Path) -> Arc<Daemon> {
        let d = Daemon::new(Arc::new(crate::harness::stub::Stub::default()), Config::new(state.to_path_buf(), state.to_path_buf())).unwrap();
        let cell = Arc::new(std::sync::Mutex::new(WorkspaceSpec { root, keep }));
        let c = cell.clone();
        d.set_workspace_spec(Arc::new(move || Some(c.lock().unwrap().clone())));
        d
    }

    #[test]
    fn no_project_session_gets_its_own_folder_and_loses_it_on_removal() {
        let t = std::env::temp_dir().join(format!("infantry-ws-test-{}", uuid::Uuid::new_v4()));
        let (root, state) = (t.join("root"), t.join("state"));
        std::fs::create_dir_all(&state).unwrap();
        let d = daemon(root.clone(), false, &state);
        let rec = d.new_record(&NewOpts::default(), "IDLE", "session").unwrap();
        let want = agent_dir(&root, &rec.id);
        assert_eq!(Path::new(&rec.working_dir), want);
        assert!(want.is_dir());
        let id = d.list_idle(rec);
        d.delete_session(&id).unwrap();
        assert!(!want.exists());
    }

    #[test]
    fn keep_setting_keeps_the_folder_and_an_explicit_cwd_is_left_alone() {
        let t = std::env::temp_dir().join(format!("infantry-ws-test-{}", uuid::Uuid::new_v4()));
        let (root, state) = (t.join("root"), t.join("state"));
        std::fs::create_dir_all(&state).unwrap();
        let d = daemon(root.clone(), true, &state);
        let rec = d.new_record(&NewOpts::default(), "IDLE", "session").unwrap();
        let want = agent_dir(&root, &rec.id);
        let id = d.list_idle(rec);
        d.delete_session(&id).unwrap();
        assert!(want.is_dir());
        let own = NewOpts { cwd: Some(state.to_string_lossy().into_owned()), ..Default::default() };
        let rec = d.new_record(&own, "IDLE", "session").unwrap();
        assert_eq!(Path::new(&rec.working_dir), state);
    }

    #[test]
    fn spec_reads_blank_root_as_default() {
        assert_eq!(spec_from(Some("X:/w".into()), Some(true)), Some(WorkspaceSpec { root: "X:/w".into(), keep: true }));
        assert_eq!(spec_from(Some("  ".into()), None).map(|s| s.keep), default_root().map(|_| false));
    }
}
