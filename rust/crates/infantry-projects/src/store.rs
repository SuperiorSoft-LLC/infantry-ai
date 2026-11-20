// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Projects and workspaces: the stores that own them and mint their ids, write-through to a [`Persistence`].
//!
//! A refusal is an `Err(Refused)` naming the problem, and nothing is written (every check runs before the write).
use crate::service::{Refused, Res};
use crate::wire::{ProjectSave, Workspace};
use infantry_core::db::Db;
use infantry_protocol::projects::{Project, RoutingRule};
use rusqlite::{params, Connection, OptionalExtension, Row};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

pub const MAX_NAME_LENGTH: usize = 200;
pub const MAX_GOAL_LENGTH: usize = 100_000;
pub const MAX_DIRECTORY_LENGTH: usize = 4_096;
pub const MAX_BRANCH_LENGTH: usize = 255;
pub const DATA_KEY_MAX: usize = 80;
pub const DATA_VALUE_MAX: usize = 4096;
pub const DATA_MAX_ENTRIES: usize = 200;

fn refuse<T>(why: impl Into<String>) -> Res<T> {
    Err(Refused(why.into()))
}

fn shown(s: &str, n: usize) -> String {
    s.chars().take(n).collect()
}

/// A random 8-hex suffix (no extra dependency: std's per-process random hasher keys).
pub fn short_id() -> String {
    use std::collections::hash_map::RandomState;
    use std::hash::{BuildHasher, Hasher};
    use std::sync::atomic::{AtomicU64, Ordering};
    static N: AtomicU64 = AtomicU64::new(0);
    let mut h = RandomState::new().build_hasher();
    h.write_u64(N.fetch_add(1, Ordering::Relaxed));
    h.write_u128(std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0));
    format!("{:08x}", h.finish() as u32)
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

// ---- persistence -------------------------------------------------------------------------------

/// Where projects and workspaces outlive the process.
pub trait Persistence: Send + Sync {
    fn load_projects(&self) -> Vec<Project>;
    fn upsert_project(&self, p: &Project) -> Result<(), String>;
    fn load_workspaces(&self) -> Vec<Workspace>;
    fn upsert_workspace(&self, w: &Workspace) -> Result<(), String>;
    /// Removes the project and its workspaces.
    fn delete_project(&self, id: &str) -> Result<(), String>;
}

/// Nothing outlives the process (tests, a stateless daemon).
#[derive(Default)]
pub struct MemoryPersistence;

impl Persistence for MemoryPersistence {
    fn load_projects(&self) -> Vec<Project> {
        Vec::new()
    }
    fn upsert_project(&self, _: &Project) -> Result<(), String> {
        Ok(())
    }
    fn load_workspaces(&self) -> Vec<Workspace> {
        Vec::new()
    }
    fn upsert_workspace(&self, _: &Workspace) -> Result<(), String> {
        Ok(())
    }
    fn delete_project(&self, _: &str) -> Result<(), String> {
        Ok(())
    }
}

/// The `projects` / `workspaces` tables of `infantry.db`, the schema the Kotlin daemon keeps (so a migrated state
/// dir lists the same projects). `ensure_schema` creates them only when absent, with the identical column order.
pub struct SqlPersistence {
    db: Arc<Db>,
}

const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS projects (
    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, goal TEXT NOT NULL DEFAULT '',
    story_ids TEXT NOT NULL DEFAULT '[]', created_at INTEGER NOT NULL, directory TEXT NOT NULL DEFAULT '',
    base_branch TEXT NOT NULL DEFAULT '', lane_caps TEXT NOT NULL DEFAULT '{}',
    auto_remove_done_workers INTEGER NOT NULL DEFAULT 0, experiments TEXT NOT NULL DEFAULT '{}',
    routing_rules TEXT NOT NULL DEFAULT '[]', project_data TEXT NOT NULL DEFAULT '{}'
);
CREATE TABLE IF NOT EXISTS workspaces (
    id TEXT NOT NULL PRIMARY KEY, project_id TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name TEXT NOT NULL, path TEXT, created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS index_workspaces_on_project ON workspaces(project_id, created_at);
";

fn json_col<T: serde::de::DeserializeOwned + Default>(row: &Row, col: &str) -> T {
    row.get::<_, Option<String>>(col).ok().flatten().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default()
}

fn text_col(row: &Row, col: &str) -> String {
    row.get::<_, Option<String>>(col).ok().flatten().unwrap_or_default()
}

fn project_row(row: &Row) -> rusqlite::Result<Project> {
    Ok(Project {
        id: row.get("id")?,
        name: row.get("name")?,
        goal: text_col(row, "goal"),
        directory: text_col(row, "directory"),
        base_branch: text_col(row, "base_branch"),
        lane_caps: json_col(row, "lane_caps"),
        auto_remove_done_workers: row.get::<_, Option<i64>>("auto_remove_done_workers").ok().flatten().unwrap_or(0) != 0,
        routing_rules: json_col::<Vec<RoutingRule>>(row, "routing_rules"),
        experiments: json_col(row, "experiments"),
        data: json_col(row, "project_data"),
    })
}

impl SqlPersistence {
    pub fn new(db: Arc<Db>) -> Result<Self, String> {
        db.write(|c| c.execute_batch(SCHEMA)).map_err(|e| e.to_string())?;
        Ok(SqlPersistence { db })
    }

    fn has_table(c: &Connection, name: &str) -> bool {
        c.query_row("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?1", [name], |_| Ok(())).optional().ok().flatten().is_some()
    }
}

impl Persistence for SqlPersistence {
    fn load_projects(&self) -> Vec<Project> {
        self.db
            .read(|c| {
                if !Self::has_table(c, "projects") {
                    return Ok(Vec::new());
                }
                let mut st = c.prepare("SELECT * FROM projects ORDER BY created_at ASC, id ASC")?;
                let rows = st.query_map([], project_row)?;
                Ok(rows.filter_map(Result::ok).collect())
            })
            .unwrap_or_default()
    }

    fn upsert_project(&self, p: &Project) -> Result<(), String> {
        let (caps, exp, rules, data) = (to_json(&p.lane_caps), to_json(&p.experiments), to_json(&p.routing_rules), to_json(&p.data));
        self.db
            .write(|c| {
                let n = c.execute(
                    "INSERT OR IGNORE INTO projects(id, name, goal, created_at, directory, base_branch, lane_caps, auto_remove_done_workers, experiments, routing_rules, project_data) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)",
                    params![p.id, p.name, p.goal, now_ms(), p.directory, p.base_branch, caps, p.auto_remove_done_workers as i64, exp, rules, data],
                )?;
                if n == 0 {
                    c.execute(
                        "UPDATE projects SET name=?1, goal=?2, directory=?3, base_branch=?4, lane_caps=?5, auto_remove_done_workers=?6, experiments=?7, routing_rules=?8, project_data=?9 WHERE id=?10",
                        params![p.name, p.goal, p.directory, p.base_branch, caps, p.auto_remove_done_workers as i64, exp, rules, data, p.id],
                    )?;
                }
                Ok(())
            })
            .map_err(|e| e.to_string())
    }

    fn load_workspaces(&self) -> Vec<Workspace> {
        self.db
            .read(|c| {
                if !Self::has_table(c, "workspaces") {
                    return Ok(Vec::new());
                }
                let mut st = c.prepare("SELECT id, project_id, name, path FROM workspaces ORDER BY created_at ASC, id ASC")?;
                let rows = st.query_map([], |r| Ok(Workspace { id: r.get(0)?, project_id: r.get(1)?, name: r.get(2)?, path: r.get(3)? }))?;
                Ok(rows.filter_map(Result::ok).collect())
            })
            .unwrap_or_default()
    }

    fn upsert_workspace(&self, w: &Workspace) -> Result<(), String> {
        self.db
            .write(|c| {
                let n = c.execute(
                    "INSERT OR IGNORE INTO workspaces(id, project_id, name, path, created_at) VALUES (?1,?2,?3,?4,?5)",
                    params![w.id, w.project_id, w.name, w.path, now_ms()],
                )?;
                if n == 0 {
                    c.execute("UPDATE workspaces SET project_id=?1, name=?2, path=?3 WHERE id=?4", params![w.project_id, w.name, w.path, w.id])?;
                }
                Ok(())
            })
            .map_err(|e| e.to_string())
    }

    fn delete_project(&self, id: &str) -> Result<(), String> {
        self.db
            .write(|c| {
                // Explicit, so it does not depend on `PRAGMA foreign_keys`.
                c.execute("DELETE FROM workspaces WHERE project_id=?1", params![id])?;
                c.execute("DELETE FROM projects WHERE id=?1", params![id])?;
                Ok(())
            })
            .map_err(|e| e.to_string())
    }
}

fn to_json<T: serde::Serialize>(v: &T) -> String {
    serde_json::to_string(v).unwrap_or_default()
}

// ---- validation --------------------------------------------------------------------------------

fn check_name(raw: &str) -> Res<String> {
    let name = raw.trim();
    if name.is_empty() {
        return refuse("name is blank");
    }
    if name.chars().count() > MAX_NAME_LENGTH {
        return refuse(format!("name is longer than {MAX_NAME_LENGTH} characters"));
    }
    if name.chars().any(char::is_control) {
        return refuse("name contains a control character");
    }
    Ok(name.to_string())
}

fn check_goal(raw: &str) -> Res<String> {
    let goal = raw.trim();
    if goal.chars().count() > MAX_GOAL_LENGTH {
        return refuse(format!("goal is longer than {MAX_GOAL_LENGTH} characters"));
    }
    Ok(goal.to_string())
}

/// Empty clears; anything else must be an ABSOLUTE path to a directory that exists now.
fn check_directory(raw: &str) -> Res<String> {
    let d = raw.trim();
    if d.is_empty() {
        return Ok(String::new());
    }
    if d.len() > MAX_DIRECTORY_LENGTH {
        return refuse(format!("directory is longer than {MAX_DIRECTORY_LENGTH} characters"));
    }
    if d.chars().any(char::is_control) {
        return refuse("directory contains a control character");
    }
    let p = Path::new(d);
    if !p.is_absolute() {
        return refuse(format!("directory must be an absolute path: '{}'", shown(d, 80)));
    }
    if !p.is_dir() {
        return refuse(format!("directory is not a directory that exists on the daemon's machine: '{}'", shown(d, 80)));
    }
    Ok(d.to_string())
}

/// A base branch name: not blank, at most 255 chars, no leading `-`, no whitespace or control char.
pub fn check_base_branch_name(raw: &str) -> Res<String> {
    let name = raw.trim();
    if name.is_empty() {
        return refuse("base branch is blank");
    }
    if name.chars().count() > MAX_BRANCH_LENGTH {
        return refuse(format!("base branch is longer than {MAX_BRANCH_LENGTH} characters"));
    }
    if name.starts_with('-') {
        return refuse(format!("base branch must not start with '-': '{}'", shown(name, 80)));
    }
    if name.chars().any(|c| c.is_control() || c.is_whitespace()) {
        return refuse("base branch contains whitespace or a control character");
    }
    Ok(name.to_string())
}

fn check_data_key(raw: &str) -> Res<String> {
    let k = raw.trim();
    let ok = !k.is_empty()
        && k.len() <= DATA_KEY_MAX
        && k.chars().next().is_some_and(|c| c.is_ascii_alphanumeric())
        && k.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'));
    if ok {
        Ok(k.to_string())
    } else {
        refuse(format!("data key '{}' is not a name of 1..{DATA_KEY_MAX} letters, digits, '.', '_' or '-'", shown(k, DATA_KEY_MAX)))
    }
}

// ---- the project store -------------------------------------------------------------------------

struct Inner {
    /// Creation order.
    order: Vec<String>,
    by_id: BTreeMap<String, Project>,
    workspaces: Vec<Workspace>,
}

/// Owns every project (and its workspaces) and mints their ids.
pub struct ProjectStore {
    inner: Mutex<Inner>,
    persistence: Box<dyn Persistence>,
    /// Where a starter project's folder is made (`<root>/<name>`).
    projects_root: PathBuf,
    /// The lane cap of a role a project sets none for (setting `limits.concurrency.laneDefault`).
    pub lane_default: Mutex<i32>,
}

fn default_projects_root() -> PathBuf {
    let home = std::env::var_os("USERPROFILE").or_else(|| std::env::var_os("HOME")).map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."));
    home.join("Infantry AI")
}

impl ProjectStore {
    pub fn new(persistence: Box<dyn Persistence>) -> Self {
        Self::with_root(persistence, default_projects_root())
    }

    pub fn in_memory() -> Self {
        Self::new(Box::new(MemoryPersistence))
    }

    pub fn with_root(persistence: Box<dyn Persistence>, projects_root: PathBuf) -> Self {
        let mut inner = Inner { order: Vec::new(), by_id: BTreeMap::new(), workspaces: persistence.load_workspaces() };
        for p in persistence.load_projects() {
            if !inner.by_id.contains_key(&p.id) {
                inner.order.push(p.id.clone());
            }
            inner.by_id.insert(p.id.clone(), p);
        }
        ProjectStore { inner: Mutex::new(inner), persistence, projects_root, lane_default: Mutex::new(1) }
    }

    fn lock(&self) -> std::sync::MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner())
    }

    fn put(&self, inner: &mut Inner, p: Project) -> Res<Project> {
        self.persistence.upsert_project(&p).map_err(|e| Refused(format!("could not save the project: {e}")))?;
        if !inner.by_id.contains_key(&p.id) {
            inner.order.push(p.id.clone());
        }
        inner.by_id.insert(p.id.clone(), p.clone());
        Ok(p)
    }

    fn mint(&self, inner: &Inner, prefix: &str) -> Res<String> {
        for _ in 0..10 {
            let c = format!("{prefix}-{}", short_id());
            if !inner.by_id.contains_key(&c) && !inner.workspaces.iter().any(|w| w.id == c) {
                return Ok(c);
            }
        }
        refuse("could not find a free id")
    }

    /// Create (no id) or edit a project. A `None` field leaves it as it is.
    pub fn save(&self, req: &ProjectSave) -> Res<Project> {
        let mut inner = self.lock();
        let editing = req.id.as_deref().map(str::trim).filter(|s| !s.is_empty());
        match editing {
            None => {
                let Some(raw) = req.name.as_deref() else { return refuse("name is required to create a project") };
                let name = check_name(raw)?;
                let goal = req.goal.as_deref().map(check_goal).transpose()?.unwrap_or_default();
                let directory = if req.starter { self.starter_directory(&name)? } else { req.directory.as_deref().map(check_directory).transpose()?.unwrap_or_default() };
                let id = self.mint(&inner, "project")?;
                let p = Project { id, name, goal, directory, ..Project::empty() };
                self.put(&mut inner, p)
            }
            Some(id) => {
                let Some(existing) = inner.by_id.get(id).cloned() else {
                    return refuse(format!("no project '{}' (project_save creates a project only when it carries no id)", shown(id, 80)));
                };
                let name = req.name.as_deref().map(check_name).transpose()?.unwrap_or(existing.name.clone());
                let goal = req.goal.as_deref().map(check_goal).transpose()?.unwrap_or(existing.goal.clone());
                let directory = req.directory.as_deref().map(check_directory).transpose()?.unwrap_or(existing.directory.clone());
                self.put(&mut inner, Project { name, goal, directory, ..existing })
            }
        }
    }

    fn starter_directory(&self, name: &str) -> Res<String> {
        let folder: String = name.chars().map(|c| if c.is_alphanumeric() || " -_.".contains(c) { c } else { '_' }).collect();
        let folder = folder.trim().trim_end_matches('.');
        let folder = if folder.is_empty() { "project" } else { folder };
        let dir = self.projects_root.join(folder);
        if !dir.is_dir() && std::fs::create_dir_all(&dir).is_err() {
            return refuse(format!("cannot create the project folder {}", dir.display()));
        }
        check_directory(&dir.to_string_lossy())
    }

    pub fn get(&self, id: &str) -> Res<Project> {
        let wanted = id.trim();
        if wanted.is_empty() {
            return refuse("expects a project id");
        }
        self.lock().by_id.get(wanted).cloned().ok_or_else(|| Refused(format!("no project '{}'", shown(wanted, 80))))
    }

    /// Removes a project and its workspaces; returns the removed project. Nothing is written for an unknown id.
    pub fn delete(&self, id: &str) -> Res<Project> {
        let wanted = id.trim();
        if wanted.is_empty() {
            return refuse("expects a project id");
        }
        let mut inner = self.lock();
        let Some(existing) = inner.by_id.get(wanted).cloned() else { return refuse(format!("no project '{}'", shown(wanted, 80))) };
        self.persistence.delete_project(wanted).map_err(|e| Refused(format!("could not delete the project: {e}")))?;
        inner.by_id.remove(wanted);
        inner.order.retain(|x| x != wanted);
        inner.workspaces.retain(|w| w.project_id != wanted);
        Ok(existing)
    }

    pub fn list(&self) -> Vec<Project> {
        let inner = self.lock();
        inner.order.iter().filter_map(|id| inner.by_id.get(id).cloned()).collect()
    }

    pub fn exists(&self, id: &str) -> bool {
        self.lock().by_id.contains_key(id.trim())
    }

    /// The oldest project (the one `project_list` answers first), if any.
    pub fn default_project_id(&self) -> Option<String> {
        self.lock().order.first().cloned()
    }

    fn update(&self, id: &str, f: impl FnOnce(&mut Project) -> Res<()>) -> Res<Project> {
        let wanted = id.trim();
        if wanted.is_empty() {
            return refuse("expects a project id");
        }
        let mut inner = self.lock();
        let Some(existing) = inner.by_id.get(wanted).cloned() else { return refuse(format!("no project '{}'", shown(wanted, 80))) };
        let mut next = existing.clone();
        f(&mut next)?;
        if next == existing {
            return Ok(existing);
        }
        self.put(&mut inner, next)
    }

    /// Set (after `check` accepted it) or clear (`None` / blank) the base branch.
    pub fn set_base_branch(&self, id: &str, branch: Option<&str>, check: impl FnOnce(&Project, &str) -> Res<()>) -> Res<Project> {
        let wanted = branch.map(str::trim).unwrap_or("").to_string();
        self.update(id, |p| {
            if !wanted.is_empty() {
                check(p, &wanted)?;
            }
            p.base_branch = wanted;
            Ok(())
        })
    }

    pub fn set_lane_caps(&self, id: &str, caps: &BTreeMap<String, i32>) -> Res<Project> {
        let lane_default = *self.lane_default.lock().unwrap_or_else(|e| e.into_inner());
        self.update(id, |p| {
            for (role, cap) in caps {
                let key = role.trim();
                if key.is_empty() {
                    return refuse("role is blank");
                }
                if *cap < 1 {
                    return refuse(format!("cap for role '{}' must be 1 or more, was {cap}", shown(key, 80)));
                }
                if *cap == lane_default {
                    p.lane_caps.remove(key);
                } else {
                    p.lane_caps.insert(key.to_string(), *cap);
                }
            }
            Ok(())
        })
    }

    pub fn set_auto_remove_done_workers(&self, id: &str, on: bool) -> Res<Project> {
        self.update(id, |p| {
            p.auto_remove_done_workers = on;
            Ok(())
        })
    }

    pub fn set_routing_rules(&self, id: &str, rules: Vec<RoutingRule>) -> Res<Project> {
        self.update(id, |p| {
            p.routing_rules = rules;
            Ok(())
        })
    }

    /// Merge `on`/`off` switches; `off` removes the key.
    pub fn set_experiments(&self, id: &str, switches: &BTreeMap<String, String>) -> Res<Project> {
        self.update(id, |p| {
            for (raw, val) in switches {
                let key = raw.trim();
                let plain = (1..=60).contains(&key.len()) && key.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'));
                if !plain {
                    return refuse(format!("experiment key '{}' is not a plain name", shown(key, 60)));
                }
                match val.trim().to_lowercase().as_str() {
                    "on" => {
                        p.experiments.insert(key.to_string(), "on".into());
                    }
                    "off" => {
                        p.experiments.remove(key);
                    }
                    _ => return refuse(format!("experiment '{key}' must be on or off, was '{}'", shown(val, 20))),
                }
            }
            Ok(())
        })
    }

    /// Merge `set` into the project's generic data and remove `unset`. Any refusal writes nothing.
    pub fn set_data(&self, id: &str, set: &BTreeMap<String, String>, unset: &[String]) -> Res<Project> {
        self.update(id, |p| {
            for k in unset {
                p.data.remove(&check_data_key(k)?);
            }
            for (k, v) in set {
                let key = check_data_key(k)?;
                if v.trim().is_empty() {
                    return refuse(format!("data '{key}' has a blank value (use unset to remove it)"));
                }
                if v.chars().count() > DATA_VALUE_MAX {
                    return refuse(format!("data '{key}' value is {} chars, the limit is {DATA_VALUE_MAX}", v.chars().count()));
                }
                p.data.insert(key, v.clone());
            }
            if p.data.len() > DATA_MAX_ENTRIES {
                return refuse(format!("a project holds at most {DATA_MAX_ENTRIES} data entries"));
            }
            Ok(())
        })
    }

    pub fn lane_cap_of(&self, project_id: &str, role: &str) -> i32 {
        let d = *self.lane_default.lock().unwrap_or_else(|e| e.into_inner());
        self.lock().by_id.get(project_id.trim()).and_then(|p| p.lane_caps.get(role).copied()).unwrap_or(d)
    }

    pub fn base_branch_of(&self, id: &str) -> Option<String> {
        self.lock().by_id.get(id.trim()).map(|p| p.base_branch.clone()).filter(|b| !b.is_empty())
    }

    pub fn data_of(&self, id: &str, key: &str) -> Option<String> {
        self.lock().by_id.get(id.trim()).and_then(|p| p.data.get(key).cloned())
    }

    // ---- workspaces ----

    /// Create a workspace inside project `project_id` (which must exist).
    pub fn save_workspace(&self, project_id: &str, name: &str, path: Option<&str>) -> Res<Workspace> {
        let owner = project_id.trim();
        if owner.is_empty() {
            return refuse("expects a project id");
        }
        let mut inner = self.lock();
        if !inner.by_id.contains_key(owner) {
            return refuse(format!("no project '{}'", shown(owner, 80)));
        }
        let name = check_name(name)?;
        let id = self.mint(&inner, "workspace")?;
        let w = Workspace { id, project_id: owner.to_string(), name, path: path.map(str::trim).filter(|s| !s.is_empty()).map(str::to_string) };
        self.persistence.upsert_workspace(&w).map_err(|e| Refused(format!("could not save the workspace: {e}")))?;
        inner.workspaces.push(w.clone());
        Ok(w)
    }

    pub fn workspaces(&self, project_id: &str) -> Vec<Workspace> {
        let p = project_id.trim();
        self.lock().workspaces.iter().filter(|w| w.project_id == p).cloned().collect()
    }
}

/// `Project` with every field at its wire default.
trait EmptyProject {
    fn empty() -> Self;
}

impl EmptyProject for Project {
    fn empty() -> Self {
        Project {
            id: String::new(),
            name: String::new(),
            goal: String::new(),
            directory: String::new(),
            base_branch: String::new(),
            lane_caps: BTreeMap::new(),
            auto_remove_done_workers: false,
            routing_rules: Vec::new(),
            experiments: BTreeMap::new(),
            data: BTreeMap::new(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_edit_refuse_and_persist_in_sqlite() {
        let tmp = tempfile::tempdir().unwrap();
        let db = Arc::new(Db::open(tmp.path().join("infantry.db")).unwrap());
        let dir = tmp.path().to_string_lossy().into_owned();
        let id = {
            let s = ProjectStore::new(Box::new(SqlPersistence::new(db.clone()).unwrap()));
            assert!(s.save(&ProjectSave { name: Some("  ".into()), ..Default::default() }).is_err());
            assert!(s.save(&ProjectSave { name: Some("x".into()), directory: Some("relative".into()), ..Default::default() }).is_err());
            let p = s.save(&ProjectSave { name: Some("Alpha".into()), directory: Some(dir.clone()), ..Default::default() }).unwrap();
            assert!(p.id.starts_with("project-"));
            let caps = BTreeMap::from([("dev".to_string(), 3)]);
            s.set_lane_caps(&p.id, &caps).unwrap();
            assert!(s.set_lane_caps(&p.id, &BTreeMap::from([("dev".to_string(), 0)])).is_err());
            s.set_data(&p.id, &BTreeMap::from([("build.root".into(), "x".into())]), &[]).unwrap();
            assert!(s.set_data(&p.id, &BTreeMap::from([("-bad".into(), "x".into())]), &[]).is_err());
            s.save_workspace(&p.id, "ws", Some("  ")).unwrap();
            assert!(s.save_workspace("nope", "ws", None).is_err());
            p.id
        };
        let s = ProjectStore::new(Box::new(SqlPersistence::new(db).unwrap()));
        let p = s.get(&id).unwrap();
        assert_eq!((p.name.as_str(), p.directory.as_str()), ("Alpha", dir.as_str()));
        assert_eq!(p.lane_caps["dev"], 3);
        assert_eq!(p.data["build.root"], "x");
        let ws = s.workspaces(&id);
        assert_eq!(ws.len(), 1);
        assert_eq!(ws[0].path, None);
        let renamed = s.save(&ProjectSave { id: Some(id.clone()), name: Some("Beta".into()), ..Default::default() }).unwrap();
        assert_eq!((renamed.name.as_str(), renamed.directory.as_str()), ("Beta", dir.as_str()));
    }
}
