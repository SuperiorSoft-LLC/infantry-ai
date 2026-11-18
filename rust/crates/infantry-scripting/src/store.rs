// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Named scripts: per project (`<project>/.infantry/scripts/<name>.<ext>`), app-wide (the Script Arsenal,
//! `<state>/global-scripts/`), and the ones loaded plugins ship. Lookup order: project, global, plugin.
//! The extension picks the engine. A name is one plain segment; nothing outside the folder is reachable.
use regex::Regex;
use serde_json::{json, Map, Value};
use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};
use std::sync::{Arc, OnceLock};
use std::time::UNIX_EPOCH;

pub const DIR: &str = "scripts";
pub const PROJECT: &str = "project";
pub const GLOBAL: &str = "global";

fn valid() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$").unwrap())
}

/// `Err` with the refusal text when `name` is not one plain segment.
pub fn check_name(name: &str) -> Result<(), String> {
    if valid().is_match(name) && !name.contains("..") {
        Ok(())
    } else {
        Err(format!("bad script name '{name}' (letters, digits, '_', '-', '.'; at most 64)"))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Script {
    pub name: String,
    pub extension: String,
    pub source: String,
    /// `project`, `global` or `plugin:<module>`.
    pub scope: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub name: String,
    pub extension: String,
    pub size: u64,
    pub modified_millis: u64,
    pub saved_by: Option<String>,
    pub scope: String,
}

/// The scripts loaded plugins ship (module.json `scripts`). The daemon implements it over its module registry.
pub trait PluginScripts: Send + Sync {
    /// A shipped script by name: `Ok(None)` = none, `Err` = it exists but its plugin is disabled (the reason).
    fn find(&self, name: &str) -> Result<Option<Script>, String>;
    /// The script `name` of module `module` (a plugin hook runs its own module's script only).
    fn read(&self, module: &str, name: &str) -> Option<Script>;
    /// Names of the enabled shipped scripts.
    fn names(&self) -> Vec<String>;
}

/// Reads `<dir>/<name>.<ext>` + its `.meta.json` sidecar. Shared by the project and the global scope.
#[derive(Debug, Clone)]
pub struct ScriptDir {
    pub dir: PathBuf,
    pub scope: String,
}

impl ScriptDir {
    pub fn new(dir: impl Into<PathBuf>, scope: &str) -> Self {
        ScriptDir { dir: dir.into(), scope: scope.into() }
    }

    fn file(&self, name: &str, ext: &str) -> PathBuf {
        self.dir.join(format!("{name}.{ext}"))
    }

    pub fn read_ext(&self, name: &str, ext: &str) -> Option<Script> {
        let source = std::fs::read_to_string(self.file(name, ext)).ok()?;
        Some(Script { name: name.into(), extension: ext.into(), source, scope: self.scope.clone() })
    }

    pub fn read(&self, name: &str, exts: &[String]) -> Option<Script> {
        exts.iter().find_map(|e| self.read_ext(name, e))
    }

    pub fn has(&self, name: &str, exts: &[String]) -> bool {
        exts.iter().any(|e| self.file(name, e).is_file())
    }

    pub fn entries(&self, exts: &[String]) -> Vec<Entry> {
        let Ok(rd) = std::fs::read_dir(&self.dir) else { return Vec::new() };
        let mut out: Vec<Entry> = rd
            .flatten()
            .filter_map(|e| {
                let fname = e.file_name().to_string_lossy().to_string();
                if fname.ends_with(".meta.json") {
                    return None;
                }
                let ext = exts.iter().find(|x| fname.ends_with(&format!(".{x}")))?;
                let md = e.metadata().ok().filter(|m| m.is_file())?;
                let by = std::fs::read_to_string(self.dir.join(format!("{fname}.meta.json")))
                    .ok()
                    .and_then(|t| serde_json::from_str::<Value>(&t).ok())
                    .and_then(|v| v.get("savedBy").and_then(Value::as_str).map(str::to_string));
                Some(Entry {
                    name: fname.trim_end_matches(&format!(".{ext}")).to_string(),
                    extension: ext.clone(),
                    size: md.len(),
                    modified_millis: md.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|d| d.as_millis() as u64).unwrap_or(0),
                    saved_by: by,
                    scope: self.scope.clone(),
                })
            })
            .collect();
        out.sort_by(|a, b| a.name.cmp(&b.name));
        out
    }

    /// Writes the script and its sidecar (dropping a copy under another extension). True when it replaced one.
    pub fn write(&self, name: &str, ext: &str, source: &str, saved_by: &str, exts: &[String]) -> std::io::Result<bool> {
        check_name(name).map_err(std::io::Error::other)?;
        std::fs::create_dir_all(&self.dir)?;
        let existed = self.has(name, exts) || self.file(name, ext).is_file();
        for other in exts.iter().filter(|o| o.as_str() != ext) {
            let _ = std::fs::remove_file(self.file(name, other));
            let _ = std::fs::remove_file(self.dir.join(format!("{name}.{other}.meta.json")));
        }
        std::fs::write(self.file(name, ext), source)?;
        std::fs::write(self.dir.join(format!("{name}.{ext}.meta.json")), json!({"savedBy": saved_by}).to_string())?;
        Ok(existed)
    }

    pub fn delete(&self, name: &str, exts: &[String]) -> bool {
        let mut any = false;
        for e in exts {
            if std::fs::remove_file(self.file(name, e)).is_ok() {
                any = true;
                let _ = std::fs::remove_file(self.dir.join(format!("{name}.{e}.meta.json")));
            }
        }
        any
    }
}

/// project id -> the project's `.infantry` directory (`None` = unknown project).
pub type ProjectDirs = Arc<dyn Fn(&str) -> Option<PathBuf> + Send + Sync>;

#[derive(Clone)]
pub struct ScriptStore {
    project_dirs: ProjectDirs,
    extensions: Arc<dyn Fn() -> Vec<String> + Send + Sync>,
    pub global: Option<ScriptDir>,
    pub plugins: Option<Arc<dyn PluginScripts>>,
}

impl ScriptStore {
    pub fn new(project_dirs: ProjectDirs, extensions: Arc<dyn Fn() -> Vec<String> + Send + Sync>) -> Self {
        ScriptStore { project_dirs, extensions, global: None, plugins: None }
    }

    pub fn with_global(mut self, dir: impl Into<PathBuf>) -> Self {
        self.global = Some(ScriptDir::new(dir, GLOBAL));
        self
    }

    pub fn with_plugins(mut self, p: Arc<dyn PluginScripts>) -> Self {
        self.plugins = Some(p);
        self
    }

    pub fn extensions(&self) -> Vec<String> {
        (self.extensions)()
    }

    /// The `.infantry/scripts` folder of a project.
    pub fn project_dir(&self, project: &str) -> Option<ScriptDir> {
        (self.project_dirs)(project).map(|d| ScriptDir::new(d.join(DIR), PROJECT))
    }

    /// Project copy, then global, then a shipped one. `Err` = bad name or a disabled plugin's script.
    pub fn read(&self, project: &str, name: &str) -> Result<Option<Script>, String> {
        check_name(name)?;
        let exts = self.extensions();
        if let Some(s) = self.project_dir(project).and_then(|d| d.read(name, &exts)) {
            return Ok(Some(s));
        }
        if let Some(s) = self.global.as_ref().and_then(|g| g.read(name, &exts)) {
            return Ok(Some(s));
        }
        match &self.plugins {
            Some(p) => p.find(name),
            None => Ok(None),
        }
    }

    pub fn has_project_copy(&self, project: &str, name: &str) -> bool {
        self.project_dir(project).is_some_and(|d| d.has(name, &self.extensions()))
    }

    pub fn names(&self, project: &str) -> Vec<String> {
        let exts = self.extensions();
        let mut all: Vec<String> = self.project_dir(project).map(|d| d.entries(&exts)).unwrap_or_default().into_iter().map(|e| e.name).collect();
        if let Some(g) = &self.global {
            all.extend(g.entries(&exts).into_iter().map(|e| e.name));
        }
        if let Some(p) = &self.plugins {
            all.extend(p.names());
        }
        all.sort();
        all.dedup();
        all
    }

    pub fn entries(&self, project: &str) -> Vec<Entry> {
        self.project_dir(project).map(|d| d.entries(&self.extensions())).unwrap_or_default()
    }

    pub fn write(&self, project: &str, name: &str, ext: &str, source: &str, saved_by: &str) -> Result<bool, String> {
        let d = self.project_dir(project).ok_or_else(|| format!("unknown project '{project}'"))?;
        d.write(name, ext, source, saved_by, &self.extensions()).map_err(|e| e.to_string())
    }

    pub fn delete(&self, project: &str, name: &str) -> Result<bool, String> {
        check_name(name)?;
        Ok(self.project_dir(project).is_some_and(|d| d.delete(name, &self.extensions())))
    }
}

/// One script the app ships.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BundledScript {
    pub name: String,
    pub ext: String,
    pub source: String,
}

pub fn sha256(text: &str) -> String {
    Sha256::digest(text.as_bytes()).iter().map(|b| format!("{b:02x}")).collect()
}

macro_rules! bundled {
    ($($f:literal),* $(,)?) => { &[$(($f, include_str!(concat!("../global-scripts/", $f)))),*] };
}

/// The starter set (`global-scripts/`), byte-identical to the Kotlin resources.
pub const BUNDLED: &[(&str, &str)] = bundled!(
    "comment-tail.lua",
    "done-forward.lua",
    "find-ticket.lua",
    "goal-autostart-off.lua",
    "goal-handoff.lua",
    "hook-health.lua",
    "lane-one-worker.lua",
    "lane-park.lua",
    "lane-pending-note.lua",
    "lane-pin-check.lua",
    "mem-check.lua",
    "needs-owner-alert.lua",
    "usage-governor.lua",
    "wake-on-log.lua",
    "worker-cap.lua",
    "worker-issue-wake.lua",
    "worker-silent-ping.lua",
    "worker-waiting-nudge.lua",
);

pub const BUNDLED_INDEX: &str = include_str!("../global-scripts/index.json");

pub fn bundled() -> Vec<BundledScript> {
    BUNDLED
        .iter()
        .map(|(f, src)| {
            let (name, ext) = f.rsplit_once('.').unwrap_or((f, "lua"));
            BundledScript { name: name.into(), ext: ext.into(), source: src.to_string() }
        })
        .collect()
}

pub const SEEDED_BY: &str = "bundled";
const SEED_STATE: &str = "bundled.json";

/// Copies a missing bundled script into the global scope; refreshes one only while nobody edited it (the hash last
/// seeded, kept in `<state_dir>/bundled.json`, still equals the file's). Returns the names written.
pub fn seed(global: &ScriptDir, state_dir: &Path, bundled: &[BundledScript], exts: &[String], log: &dyn Fn(&str)) -> std::io::Result<Vec<String>> {
    std::fs::create_dir_all(state_dir)?;
    let state_file = state_dir.join(SEED_STATE);
    let mut seeded: Map<String, Value> = std::fs::read_to_string(&state_file)
        .ok()
        .and_then(|t| serde_json::from_str::<Value>(&t).ok())
        .and_then(|v| v.as_object().cloned())
        .unwrap_or_default();
    let mut written = Vec::new();
    for b in bundled {
        let hash = sha256(&b.source);
        match global.read_ext(&b.name, &b.ext) {
            None => {
                global.write(&b.name, &b.ext, &b.source, SEEDED_BY, exts)?;
                seeded.insert(b.name.clone(), Value::String(hash));
                written.push(b.name.clone());
            }
            Some(existing) => {
                let cur = sha256(&existing.source);
                if cur == hash {
                    seeded.insert(b.name.clone(), Value::String(hash));
                } else if seeded.get(&b.name).and_then(Value::as_str) == Some(cur.as_str()) {
                    global.write(&b.name, &b.ext, &b.source, SEEDED_BY, exts)?;
                    seeded.insert(b.name.clone(), Value::String(hash));
                    written.push(b.name.clone());
                } else {
                    log(&format!("global script {} was edited; the bundled update is not applied", b.name));
                }
            }
        }
    }
    let sorted: std::collections::BTreeMap<_, _> = seeded.into_iter().collect();
    std::fs::write(state_file, serde_json::to_string(&sorted).unwrap_or_default())?;
    Ok(written)
}
