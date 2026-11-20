// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The managed script library: scripts live in the daemon database (not loose files) with a title, a description,
//! tags, an author, a version history, an enabled flag and run stats; hooks live there too and name a script by id.
//! Scope is `project` (one project), `global` (the Script Arsenal) or `plugin` (shipped by a plugin, read-only, not
//! stored here). Files stay for sharing: [`ScriptLibrary::export_to`] and [`ScriptLibrary::import_dir`].
use crate::runlog::{RunRecord, RunStore};
use infantry_core::db::Db;
use regex::Regex;
use rusqlite::{params, Connection, OptionalExtension};
use serde_json::{json, Map, Value};
use std::path::Path;
use std::sync::{Arc, OnceLock};

pub const SCHEMA: &str = "
CREATE TABLE IF NOT EXISTS ms_scripts (
  id TEXT NOT NULL PRIMARY KEY, scope TEXT NOT NULL, project_id TEXT NOT NULL DEFAULT '', name TEXT NOT NULL,
  ext TEXT NOT NULL DEFAULT 'lua', title TEXT NOT NULL, description TEXT NOT NULL, body TEXT NOT NULL,
  tags TEXT NOT NULL DEFAULT '[]', author TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
  enabled INTEGER NOT NULL DEFAULT 1, version INTEGER NOT NULL DEFAULT 1, flag TEXT,
  UNIQUE (scope, project_id, name));
CREATE TABLE IF NOT EXISTS ms_script_versions (
  script_id TEXT NOT NULL, version INTEGER NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL, body TEXT NOT NULL,
  author TEXT NOT NULL DEFAULT '', saved_at INTEGER NOT NULL, PRIMARY KEY (script_id, version));
CREATE TABLE IF NOT EXISTS ms_hooks (
  project_id TEXT NOT NULL DEFAULT '', id TEXT NOT NULL, script_id TEXT, script_name TEXT NOT NULL, data TEXT NOT NULL,
  PRIMARY KEY (project_id, id));
CREATE TABLE IF NOT EXISTS ms_stats (
  project_id TEXT NOT NULL, script TEXT NOT NULL, runs INTEGER NOT NULL DEFAULT 0, failures INTEGER NOT NULL DEFAULT 0,
  total_ms INTEGER NOT NULL DEFAULT 0, timed_runs INTEGER NOT NULL DEFAULT 0, last_at INTEGER, last_ok INTEGER, last_error TEXT,
  PRIMARY KEY (project_id, script));
CREATE TABLE IF NOT EXISTS ms_runs (
  project_id TEXT NOT NULL, id INTEGER NOT NULL, data TEXT NOT NULL, PRIMARY KEY (project_id, id));
CREATE TABLE IF NOT EXISTS ms_meta (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL);
";

pub fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

#[derive(Debug, Clone, PartialEq)]
pub struct ManagedScript {
    pub id: String,
    pub scope: String,
    pub project_id: String,
    pub name: String,
    pub ext: String,
    pub title: String,
    pub description: String,
    pub body: String,
    pub tags: Vec<String>,
    pub author: String,
    pub created_at: i64,
    pub updated_at: i64,
    pub enabled: bool,
    pub version: i64,
    /// A note for the owner: a likely duplicate or a versioned copy ("name-2").
    pub flag: Option<String>,
}

impl ManagedScript {
    /// The row without the body (the list form).
    pub fn to_json(&self, with_body: bool) -> Value {
        let mut o = json!({
            "id": self.id, "scope": self.scope, "projectId": self.project_id, "name": self.name, "ext": self.ext,
            "title": self.title, "description": self.description, "tags": self.tags, "author": self.author,
            "createdAt": self.created_at, "updatedAt": self.updated_at, "enabled": self.enabled, "version": self.version,
            "flag": self.flag, "size": self.body.len(),
        });
        if with_body {
            o["body"] = json!(self.body);
        }
        o
    }
}

#[derive(Debug, Clone)]
pub struct SaveInput {
    pub scope: String,
    pub project_id: String,
    pub name: String,
    pub ext: String,
    pub title: String,
    pub description: String,
    pub body: String,
    /// `None` keeps the tags of the script being replaced.
    pub tags: Option<Vec<String>>,
    pub author: String,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct ScriptStats {
    pub runs: i64,
    pub failures: i64,
    pub avg_ms: Option<i64>,
    pub last_at: Option<i64>,
    pub last_ok: Option<bool>,
    pub last_error: Option<String>,
}

impl ScriptStats {
    pub fn to_json(&self) -> Value {
        json!({"runs": self.runs, "failures": self.failures, "avgMs": self.avg_ms, "lastRunAt": self.last_at, "lastOk": self.last_ok, "lastError": self.last_error})
    }
}

#[derive(Debug, Clone, Default)]
pub struct MigrationReport {
    pub scripts: usize,
    pub hooks: usize,
    pub flagged: usize,
    pub skipped: usize,
}

fn re(cell: &'static OnceLock<Regex>, p: &str) -> &'static Regex {
    cell.get_or_init(|| Regex::new(p).unwrap())
}

/// `done-protocol-4` -> `Done protocol 4`.
pub fn title_from_name(name: &str) -> String {
    let t: String = name.chars().map(|c| if matches!(c, '-' | '_' | '.') { ' ' } else { c }).collect();
    let t = t.split_whitespace().collect::<Vec<_>>().join(" ");
    let mut cs = t.chars();
    match cs.next() {
        Some(f) => f.to_uppercase().collect::<String>() + cs.as_str(),
        None => name.to_string(),
    }
}

/// The leading `--` comment block of a Lua source as one description (empty when the file does not start with one).
pub fn description_from_header(src: &str) -> String {
    let mut out = Vec::new();
    let mut in_block = false;
    for line in src.lines() {
        let t = line.trim();
        if in_block {
            if let Some(i) = t.find("]]") {
                let head = t[..i].trim();
                if !head.is_empty() {
                    out.push(head.to_string());
                }
                in_block = false;
            } else if !t.is_empty() {
                out.push(t.to_string());
            }
            continue;
        }
        if t.is_empty() && out.is_empty() {
            continue;
        }
        if let Some(rest) = t.strip_prefix("--[[") {
            match rest.find("]]") {
                Some(i) => out.push(rest[..i].trim().to_string()),
                None => {
                    in_block = true;
                    if !rest.trim().is_empty() {
                        out.push(rest.trim().to_string());
                    }
                }
            }
        } else if let Some(rest) = t.strip_prefix("--") {
            out.push(rest.trim_start_matches('-').trim().to_string());
        } else {
            break;
        }
    }
    let text = out.into_iter().filter(|l| !l.is_empty()).collect::<Vec<_>>().join(" ");
    text.chars().take(400).collect()
}

/// `(base, suffix)` when `name` looks like a versioned copy: `x-4`, `x-merge`, `x-merge-5`, `x-copy`, `x.bak`.
pub fn versioned_base(name: &str) -> Option<String> {
    static R: OnceLock<Regex> = OnceLock::new();
    let caps = re(&R, r"^(.+?)(?:-merge|-copy|-old|-new|-bak)?(?:-\d+)?(?:\.bak)?$").captures(name)?;
    let base = caps.get(1)?.as_str();
    (base != name && !base.is_empty()).then(|| base.to_string())
}

pub struct ScriptLibrary {
    db: Arc<Db>,
}

type R<T> = Result<T, String>;

fn e(x: impl std::fmt::Display) -> String {
    x.to_string()
}

fn row(r: &rusqlite::Row) -> rusqlite::Result<ManagedScript> {
    let tags: String = r.get("tags")?;
    Ok(ManagedScript {
        id: r.get("id")?,
        scope: r.get("scope")?,
        project_id: r.get("project_id")?,
        name: r.get("name")?,
        ext: r.get("ext")?,
        title: r.get("title")?,
        description: r.get("description")?,
        body: r.get("body")?,
        tags: serde_json::from_str(&tags).unwrap_or_default(),
        author: r.get("author")?,
        created_at: r.get("created_at")?,
        updated_at: r.get("updated_at")?,
        enabled: r.get::<_, i64>("enabled")? != 0,
        version: r.get("version")?,
        flag: r.get("flag")?,
    })
}

const COLS: &str = "id, scope, project_id, name, ext, title, description, body, tags, author, created_at, updated_at, enabled, version, flag";

fn new_id() -> String {
    // 16 hex chars from time + a process counter: unique without a rand dependency.
    use std::sync::atomic::{AtomicU64, Ordering};
    static N: AtomicU64 = AtomicU64::new(0);
    let n = N.fetch_add(1, Ordering::Relaxed);
    let t = now_ms() as u64;
    format!("scr-{:08x}{:04x}", (t ^ (t >> 20)) & 0xffff_ffff, (n ^ (std::process::id() as u64)) & 0xffff)
}

impl ScriptLibrary {
    pub fn new(db: Arc<Db>) -> R<Self> {
        db.write(|c| c.execute_batch(SCHEMA)).map_err(e)?;
        Ok(ScriptLibrary { db })
    }

    pub fn db(&self) -> &Arc<Db> {
        &self.db
    }

    fn q<T>(&self, f: impl FnOnce(&Connection) -> rusqlite::Result<T>) -> R<T> {
        self.db.read(f).map_err(e)
    }

    fn w<T>(&self, f: impl FnOnce(&mut Connection) -> rusqlite::Result<T>) -> R<T> {
        self.db.write(f).map_err(e)
    }

    // ---- scripts ----

    pub fn get(&self, id: &str) -> R<Option<ManagedScript>> {
        self.q(|c| c.query_row(&format!("SELECT {COLS} FROM ms_scripts WHERE id = ?1"), [id], row).optional())
    }

    pub fn find(&self, scope: &str, project: &str, name: &str) -> R<Option<ManagedScript>> {
        self.q(|c| c.query_row(&format!("SELECT {COLS} FROM ms_scripts WHERE scope = ?1 AND project_id = ?2 AND name = ?3"), params![scope, project, name], row).optional())
    }

    /// The script a project runs under `name`: its own, then the global one.
    pub fn resolve(&self, project: &str, name: &str) -> R<Option<ManagedScript>> {
        if let Some(s) = self.find("project", project, name)? {
            return Ok(Some(s));
        }
        self.find("global", "", name)
    }

    /// A script by id or, failing that, by name as `resolve` sees it.
    pub fn by_ref(&self, project: &str, reference: &str) -> R<Option<ManagedScript>> {
        match self.get(reference)? {
            Some(s) => Ok(Some(s)),
            None => self.resolve(project, reference),
        }
    }

    /// The project's scripts and the global ones (`project` empty = the global ones only, `None` = every script).
    pub fn list(&self, project: Option<&str>) -> R<Vec<ManagedScript>> {
        self.q(|c| {
            let (sql, p): (String, Vec<String>) = match project {
                None => (format!("SELECT {COLS} FROM ms_scripts ORDER BY scope, name"), vec![]),
                Some("") => (format!("SELECT {COLS} FROM ms_scripts WHERE scope = 'global' ORDER BY name"), vec![]),
                Some(p) => (format!("SELECT {COLS} FROM ms_scripts WHERE scope = 'global' OR (scope = 'project' AND project_id = ?1) ORDER BY scope DESC, name"), vec![p.to_string()]),
            };
            let mut st = c.prepare(&sql)?;
            let rows = st.query_map(rusqlite::params_from_iter(p.iter()), row)?.collect::<rusqlite::Result<Vec<_>>>()?;
            Ok(rows)
        })
    }

    pub fn save(&self, i: SaveInput) -> R<(ManagedScript, bool)> {
        if i.title.trim().is_empty() {
            return Err("a script needs a title".into());
        }
        if i.description.trim().is_empty() {
            return Err("a script needs a description".into());
        }
        crate::store::check_name(&i.name)?;
        if i.scope != "project" && i.scope != "global" {
            return Err("scope must be project or global".into());
        }
        let project = if i.scope == "global" { String::new() } else { i.project_id.clone() };
        if i.scope == "project" && project.is_empty() {
            return Err("a project script needs a project".into());
        }
        let now = now_ms();
        let old = self.find(&i.scope, &project, &i.name)?;
        let id = old.as_ref().map(|o| o.id.clone()).unwrap_or_else(new_id);
        let tags = i.tags.clone().or_else(|| old.as_ref().map(|o| o.tags.clone())).unwrap_or_default();
        let tags_json = serde_json::to_string(&tags).unwrap_or_else(|_| "[]".into());
        let replaced = old.is_some();
        self.w(|c| {
            let tx = c.transaction()?;
            if let Some(o) = &old {
                tx.execute(
                    "INSERT OR REPLACE INTO ms_script_versions (script_id, version, title, description, body, author, saved_at) VALUES (?1,?2,?3,?4,?5,?6,?7)",
                    params![o.id, o.version, o.title, o.description, o.body, o.author, o.updated_at],
                )?;
                tx.execute(
                    "UPDATE ms_scripts SET ext=?2, title=?3, description=?4, body=?5, tags=?6, author=?7, updated_at=?8, version=version+1 WHERE id=?1",
                    params![id, i.ext, i.title.trim(), i.description.trim(), i.body, tags_json, i.author, now],
                )?;
            } else {
                tx.execute(
                    &format!("INSERT INTO ms_scripts ({COLS}) VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?11,1,1,NULL)"),
                    params![id, i.scope, project, i.name, i.ext, i.title.trim(), i.description.trim(), i.body, tags_json, i.author, now],
                )?;
            }
            tx.commit()
        })?;
        Ok((self.get(&id)?.ok_or("saved script vanished")?, replaced))
    }

    /// Changes title, description and tags without a new body version.
    pub fn set_meta(&self, id: &str, title: &str, description: &str, tags: &[String]) -> R<()> {
        if title.trim().is_empty() || description.trim().is_empty() {
            return Err("a script needs a title and a description".into());
        }
        let n = self.w(|c| c.execute("UPDATE ms_scripts SET title=?2, description=?3, tags=?4, updated_at=?5 WHERE id=?1", params![id, title.trim(), description.trim(), serde_json::to_string(tags).unwrap_or_default(), now_ms()]))?;
        if n == 0 { Err(format!("no script {id}")) } else { Ok(()) }
    }

    pub fn set_enabled(&self, id: &str, on: bool) -> R<()> {
        let n = self.w(|c| c.execute("UPDATE ms_scripts SET enabled=?2 WHERE id=?1", params![id, on as i64]))?;
        if n == 0 { Err(format!("no script {id}")) } else { Ok(()) }
    }

    pub fn set_flag(&self, id: &str, flag: Option<&str>) -> R<()> {
        self.w(|c| c.execute("UPDATE ms_scripts SET flag=?2 WHERE id=?1", params![id, flag])).map(|_| ())
    }

    /// Removes the script and its history. Hooks that name it are removed too; returns how many.
    pub fn delete(&self, id: &str) -> R<(bool, usize)> {
        self.w(|c| {
            let tx = c.transaction()?;
            let hooks = tx.execute("DELETE FROM ms_hooks WHERE script_id = ?1", [id])?;
            tx.execute("DELETE FROM ms_script_versions WHERE script_id = ?1", [id])?;
            let n = tx.execute("DELETE FROM ms_scripts WHERE id = ?1", [id])?;
            tx.commit()?;
            Ok((n > 0, hooks))
        })
    }

    /// `(version, title, description, author, savedAt, size)` of every saved version, newest first, current included.
    pub fn versions(&self, id: &str) -> R<Vec<Value>> {
        let cur = self.get(id)?.ok_or_else(|| format!("no script {id}"))?;
        let mut out = vec![json!({"version": cur.version, "title": cur.title, "description": cur.description, "author": cur.author, "savedAt": cur.updated_at, "size": cur.body.len(), "current": true})];
        let old = self.q(|c| {
            let mut st = c.prepare("SELECT version, title, description, author, saved_at, length(body) FROM ms_script_versions WHERE script_id = ?1 ORDER BY version DESC")?;
            let v = st.query_map([id], |r| Ok(json!({"version": r.get::<_, i64>(0)?, "title": r.get::<_, String>(1)?, "description": r.get::<_, String>(2)?, "author": r.get::<_, String>(3)?, "savedAt": r.get::<_, i64>(4)?, "size": r.get::<_, i64>(5)?, "current": false})))?.collect::<rusqlite::Result<Vec<_>>>()?;
            Ok(v)
        })?;
        out.extend(old);
        Ok(out)
    }

    pub fn version_body(&self, id: &str, version: i64) -> R<Option<(String, String, String)>> {
        if let Some(cur) = self.get(id)?.filter(|s| s.version == version) {
            return Ok(Some((cur.title, cur.description, cur.body)));
        }
        self.q(|c| c.query_row("SELECT title, description, body FROM ms_script_versions WHERE script_id = ?1 AND version = ?2", params![id, version], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?))).optional())
    }

    /// Saves an old version's body as a NEW version (history is never rewritten).
    pub fn restore(&self, id: &str, version: i64, author: &str) -> R<ManagedScript> {
        let cur = self.get(id)?.ok_or_else(|| format!("no script {id}"))?;
        let (title, description, body) = self.version_body(id, version)?.ok_or_else(|| format!("script {id} has no version {version}"))?;
        let (s, _) = self.save(SaveInput { scope: cur.scope, project_id: cur.project_id, name: cur.name, ext: cur.ext, title, description, body, tags: None, author: author.to_string() })?;
        Ok(s)
    }

    // ---- stats ----

    pub fn record_run(&self, project: &str, script: &str, ok: bool, duration_ms: Option<u64>, error: Option<&str>, at: u64) -> R<()> {
        self.w(|c| {
            c.execute(
                "INSERT INTO ms_stats (project_id, script, runs, failures, total_ms, timed_runs, last_at, last_ok, last_error) VALUES (?1,?2,1,?3,?4,?5,?6,?7,?8)
                 ON CONFLICT(project_id, script) DO UPDATE SET runs = runs + 1, failures = failures + ?3, total_ms = total_ms + ?4, timed_runs = timed_runs + ?5,
                   last_at = ?6, last_ok = ?7, last_error = CASE WHEN ?7 = 0 THEN ?8 ELSE last_error END",
                params![project, script, (!ok) as i64, duration_ms.unwrap_or(0) as i64, duration_ms.is_some() as i64, at as i64, ok as i64, error],
            )
            .map(|_| ())
        })
    }

    /// Stats of a script: a project script counts its own project's runs; a global one every project's.
    pub fn stats_for(&self, s: &ManagedScript) -> R<ScriptStats> {
        self.q(|c| {
            let (sql, p): (&str, Vec<String>) = if s.scope == "project" {
                ("SELECT runs, failures, total_ms, timed_runs, last_at, last_ok, last_error FROM ms_stats WHERE script = ?1 AND project_id = ?2", vec![s.name.clone(), s.project_id.clone()])
            } else {
                // A project that has its own copy of the name runs that one, so it is not counted here.
                ("SELECT runs, failures, total_ms, timed_runs, last_at, last_ok, last_error FROM ms_stats m WHERE script = ?1 AND NOT EXISTS (SELECT 1 FROM ms_scripts p WHERE p.scope='project' AND p.project_id = m.project_id AND p.name = m.script)", vec![s.name.clone()])
            };
            let mut st = c.prepare(sql)?;
            let rows = st
                .query_map(rusqlite::params_from_iter(p.iter()), |r| Ok((r.get::<_, i64>(0)?, r.get::<_, i64>(1)?, r.get::<_, i64>(2)?, r.get::<_, i64>(3)?, r.get::<_, Option<i64>>(4)?, r.get::<_, Option<i64>>(5)?, r.get::<_, Option<String>>(6)?)))?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            let mut out = ScriptStats::default();
            let (mut ms, mut timed) = (0, 0);
            for (runs, fails, total, tr, last, ok, err) in rows {
                out.runs += runs;
                out.failures += fails;
                ms += total;
                timed += tr;
                if last > out.last_at {
                    out.last_at = last;
                    out.last_ok = ok.map(|v| v != 0);
                }
                if err.is_some() && (out.last_error.is_none() || last >= out.last_at) {
                    out.last_error = err;
                }
            }
            out.avg_ms = (timed > 0).then(|| ms / timed);
            Ok(out)
        })
    }

    // ---- hooks ----

    /// The stored hooks of a project (`""` = the app-wide arsenal): `(id, script_id, script_name, data)`.
    pub fn hooks(&self, project: &str) -> R<Vec<(String, Option<String>, String, String)>> {
        self.q(|c| {
            let mut st = c.prepare("SELECT id, script_id, script_name, data FROM ms_hooks WHERE project_id = ?1 ORDER BY rowid")?;
            let v = st.query_map([project], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))?.collect::<rusqlite::Result<Vec<_>>>()?;
            Ok(v)
        })
    }

    pub fn hook_put(&self, project: &str, id: &str, script_id: Option<&str>, script_name: &str, data: &str) -> R<()> {
        self.w(|c| c.execute("INSERT OR REPLACE INTO ms_hooks (project_id, id, script_id, script_name, data) VALUES (?1,?2,?3,?4,?5)", params![project, id, script_id, script_name, data]).map(|_| ()))
    }

    pub fn hook_delete(&self, project: &str, id: &str) -> R<bool> {
        self.w(|c| c.execute("DELETE FROM ms_hooks WHERE project_id = ?1 AND id = ?2", params![project, id]).map(|n| n > 0))
    }

    pub fn hooks_all(&self) -> R<Vec<(String, String, Option<String>, String)>> {
        self.q(|c| {
            let mut st = c.prepare("SELECT project_id, id, script_id, script_name FROM ms_hooks")?;
            let v = st.query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?, r.get(3)?)))?.collect::<rusqlite::Result<Vec<_>>>()?;
            Ok(v)
        })
    }

    // ---- meta + migration ----

    pub fn meta(&self, key: &str) -> Option<String> {
        self.q(|c| c.query_row("SELECT value FROM ms_meta WHERE key = ?1", [key], |r| r.get(0)).optional()).ok().flatten()
    }

    pub fn set_meta_value(&self, key: &str, value: &str) {
        let _ = self.w(|c| c.execute("INSERT OR REPLACE INTO ms_meta (key, value) VALUES (?1, ?2)", params![key, value]));
    }

    /// Imports every script file of `dir` (`<name>.<ext>` + `.meta.json` sidecar) into `scope`/`project`. A name that is
    /// already stored is left alone. `.bak` files and sidecars are skipped.
    pub fn import_dir(&self, dir: &Path, scope: &str, project: &str, exts: &[String], author: &str) -> R<MigrationReport> {
        let mut rep = MigrationReport::default();
        let Ok(rd) = std::fs::read_dir(dir) else { return Ok(rep) };
        let mut files: Vec<_> = rd.flatten().filter(|f| f.path().is_file()).collect();
        files.sort_by_key(|f| f.file_name());
        for f in files {
            let fname = f.file_name().to_string_lossy().to_string();
            if fname.ends_with(".meta.json") || fname.ends_with(".bak") || fname == "hooks.json" {
                continue;
            }
            let Some(ext) = exts.iter().find(|x| fname.ends_with(&format!(".{x}"))) else { rep.skipped += 1; continue };
            let name = fname.trim_end_matches(&format!(".{ext}")).to_string();
            if crate::store::check_name(&name).is_err() || self.find(scope, project, &name)?.is_some() {
                rep.skipped += 1;
                continue;
            }
            let Ok(body) = std::fs::read_to_string(f.path()) else { rep.skipped += 1; continue };
            let meta: Option<Value> = std::fs::read_to_string(dir.join(format!("{fname}.meta.json"))).ok().and_then(|t| serde_json::from_str(&t).ok());
            let m = |k: &str| meta.as_ref().and_then(|v| v.get(k)).and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty()).map(str::to_string);
            let header = description_from_header(&body);
            let description = m("description").or(Some(header).filter(|h| !h.is_empty())).unwrap_or_else(|| "(imported from a file; no description yet, please add one)".into());
            let title = m("title").unwrap_or_else(|| title_from_name(&name));
            let by = m("savedBy").unwrap_or_else(|| author.to_string());
            let tags = meta.as_ref().and_then(|v| v.get("tags")).and_then(Value::as_array).map(|a| a.iter().filter_map(|t| t.as_str().map(str::to_string)).collect());
            self.save(SaveInput { scope: scope.into(), project_id: project.into(), name, ext: ext.clone(), title, description, body, tags, author: by })?;
            rep.scripts += 1;
        }
        rep.flagged = self.flag_duplicates()?;
        Ok(rep)
    }

    /// Imports a `hooks.json` into the database (`scope_key` `""` = the arsenal). A hook id already stored is left alone.
    pub fn import_hooks_file(&self, path: &Path, project: &str, scope_key: &str) -> R<usize> {
        let Ok(text) = std::fs::read_to_string(path) else { return Ok(0) };
        let hooks = crate::hooks::parse_hooks(&text)?;
        let have: Vec<String> = self.hooks(scope_key)?.into_iter().map(|h| h.0).collect();
        let mut n = 0;
        for h in hooks.iter().filter(|h| !have.contains(&h.id)) {
            let sid = if scope_key.is_empty() { self.find("global", "", &h.script)? } else { self.resolve(project, &h.script)? }.map(|s| s.id);
            self.hook_put(scope_key, &h.id, sid.as_deref(), &h.script, &h.to_json().to_string())?;
            n += 1;
        }
        Ok(n)
    }

    /// The one-time import of the loose files: the global folder, each project's `.infantry/scripts` (and the
    /// `.agenthq/scripts` leftovers beside it) and every `hooks.json`. Each source is imported once (a marker in
    /// `ms_meta`), scripts before hooks so hooks resolve their script id. Returns log lines for what was done.
    pub fn migrate_files(&self, global_dir: Option<&Path>, global_hooks: Option<&Path>, projects: &[(String, std::path::PathBuf)], exts: &[String]) -> Vec<String> {
        let mut log = Vec::new();
        let mut once = |key: &str, f: &mut dyn FnMut() -> R<String>| {
            if self.meta(key).is_some() {
                return;
            }
            match f() {
                Ok(m) => {
                    self.set_meta_value(key, &now_ms().to_string());
                    if !m.is_empty() {
                        log.push(m);
                    }
                }
                Err(err) => log.push(format!("script import {key} failed: {err}")),
            }
        };
        if let Some(d) = global_dir {
            once("imported:global-scripts", &mut || {
                let r = self.import_dir(d, "global", "", exts, "import")?;
                Ok(format!("imported {} global script(s)", r.scripts))
            });
        }
        for (id, infantry_dir) in projects {
            let scripts = infantry_dir.join(crate::store::DIR);
            once(&format!("imported:scripts:{id}"), &mut || {
                let mut r = self.import_dir(&scripts, "project", id, exts, "import")?;
                if let Some(parent) = infantry_dir.parent() {
                    let old = self.import_dir(&parent.join(".agenthq").join(crate::store::DIR), "project", id, exts, "import")?;
                    r.scripts += old.scripts;
                }
                Ok(format!("imported {} script(s) of project {id}", r.scripts))
            });
            once(&format!("imported:hooks:{id}"), &mut || {
                let n = self.import_hooks_file(&scripts.join("hooks.json"), id, id)?;
                Ok(if n > 0 { format!("imported {n} hook(s) of project {id}") } else { String::new() })
            });
        }
        if let Some(f) = global_hooks {
            once("imported:global-hooks", &mut || {
                let n = self.import_hooks_file(f, "", "")?;
                Ok(if n > 0 { format!("imported {n} global hook(s)") } else { String::new() })
            });
        }
        let _ = self.flag_duplicates();
        log
    }

    /// Marks likely versioned copies (`x-4`, `x-merge-5` while `x` exists) and identical bodies. Returns how many are flagged.
    pub fn flag_duplicates(&self) -> R<usize> {
        let all = self.list(None)?;
        let mut flagged = 0;
        for s in &all {
            let same_scope = |o: &&ManagedScript| o.id != s.id && o.scope == s.scope && o.project_id == s.project_id;
            let mut flag = versioned_base(&s.name)
                .filter(|b| all.iter().filter(same_scope).any(|o| &o.name == b))
                .map(|b| format!("likely a versioned copy of '{b}': merge into it or delete"));
            if flag.is_none() {
                flag = all.iter().filter(same_scope).find(|o| o.body.trim() == s.body.trim() && o.name < s.name).map(|o| format!("identical to '{}': delete one", o.name));
            }
            if flag != s.flag {
                self.set_flag(&s.id, flag.as_deref())?;
            }
            if flag.is_some() {
                flagged += 1;
            }
        }
        Ok(flagged)
    }

    /// Writes scripts as `<name>.<ext>` + `.meta.json` (title, description, tags, savedBy) into `dir`.
    pub fn export_to(&self, scripts: &[ManagedScript], dir: &Path) -> R<usize> {
        std::fs::create_dir_all(dir).map_err(e)?;
        for s in scripts {
            std::fs::write(dir.join(format!("{}.{}", s.name, s.ext)), &s.body).map_err(e)?;
            let mut m = Map::new();
            m.insert("title".into(), json!(s.title));
            m.insert("description".into(), json!(s.description));
            m.insert("tags".into(), json!(s.tags));
            m.insert("savedBy".into(), json!(s.author));
            std::fs::write(dir.join(format!("{}.{}.meta.json", s.name, s.ext)), Value::Object(m).to_string()).map_err(e)?;
        }
        Ok(scripts.len())
    }
}

/// Run history and stats persist through the library: every run record updates the script's counters and is kept
/// (the newest N per project) as the run log's store.
impl RunStore for ScriptLibrary {
    fn save(&self, rec: &RunRecord) {
        let _ = self.record_run(&rec.project, &rec.script, rec.ok, rec.duration_ms, rec.error.as_deref(), rec.at);
        let _ = self.w(|c| c.execute("INSERT OR REPLACE INTO ms_runs (project_id, id, data) VALUES (?1,?2,?3)", params![rec.project, rec.id as i64, rec.encode()]));
    }

    fn trim(&self, project: &str, keep: usize) {
        let _ = self.w(|c| c.execute("DELETE FROM ms_runs WHERE project_id = ?1 AND id NOT IN (SELECT id FROM ms_runs WHERE project_id = ?1 ORDER BY id DESC LIMIT ?2)", params![project, keep as i64]));
    }

    fn load(&self) -> Vec<RunRecord> {
        self.q(|c| {
            let mut st = c.prepare("SELECT data FROM ms_runs ORDER BY id")?;
            let v = st.query_map([], |r| r.get::<_, String>(0))?.collect::<rusqlite::Result<Vec<_>>>()?;
            Ok(v)
        })
        .unwrap_or_default()
        .iter()
        .filter_map(|t| RunRecord::decode(t))
        .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lib() -> (ScriptLibrary, std::path::PathBuf) {
        static N: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);
        let dir = std::env::temp_dir().join(format!("ms-lib-{}-{}", std::process::id(), N.fetch_add(1, std::sync::atomic::Ordering::Relaxed)));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let db = Arc::new(Db::open(dir.join("t.db")).unwrap());
        (ScriptLibrary::new(db).unwrap(), dir)
    }

    fn input(name: &str, body: &str) -> SaveInput {
        SaveInput { scope: "project".into(), project_id: "p1".into(), name: name.into(), ext: "lua".into(), title: "T".into(), description: "D".into(), body: body.into(), tags: None, author: "me".into() }
    }

    #[test]
    fn title_and_description_are_required() {
        let (l, _d) = lib();
        let mut i = input("a", "x");
        i.title = " ".into();
        assert!(l.save(i).unwrap_err().contains("title"));
        let mut i = input("a", "x");
        i.description = "".into();
        assert!(l.save(i).unwrap_err().contains("description"));
    }

    #[test]
    fn versions_keep_the_previous_body_and_restore_makes_a_new_one() {
        let (l, _d) = lib();
        let (s, replaced) = l.save(input("a", "one")).unwrap();
        assert!(!replaced);
        let (s2, replaced) = l.save(input("a", "two")).unwrap();
        assert!(replaced && s2.id == s.id && s2.version == 2);
        assert_eq!(l.versions(&s.id).unwrap().len(), 2);
        let r = l.restore(&s.id, 1, "owner").unwrap();
        assert_eq!((r.body.as_str(), r.version), ("one", 3));
        assert_eq!(l.version_body(&s.id, 2).unwrap().unwrap().2, "two");
    }

    #[test]
    fn stats_aggregate_and_hooks_follow_the_id() {
        let (l, _d) = lib();
        let (s, _) = l.save(input("a", "x")).unwrap();
        l.record_run("p1", "a", true, Some(10), None, 5).unwrap();
        l.record_run("p1", "a", false, Some(30), Some("boom"), 9).unwrap();
        let st = l.stats_for(&s).unwrap();
        assert_eq!((st.runs, st.failures, st.avg_ms, st.last_ok), (2, 1, Some(20), Some(false)));
        assert_eq!(st.last_error.as_deref(), Some("boom"));
        l.hook_put("p1", "h1", Some(&s.id), "a", "{}").unwrap();
        assert_eq!(l.delete(&s.id).unwrap(), (true, 1));
        assert!(l.hooks("p1").unwrap().is_empty());
    }

    #[test]
    fn import_titles_describes_and_flags_copies() {
        let (l, d) = lib();
        let src = d.join("scripts");
        std::fs::create_dir_all(&src).unwrap();
        std::fs::write(src.join("done-protocol.lua"), "-- Moves a done ticket forward.\n-- Second line.\nreturn 1").unwrap();
        std::fs::write(src.join("done-protocol-4.lua"), "return 2").unwrap();
        std::fs::write(src.join("done-protocol-merge-5.lua"), "return 3").unwrap();
        std::fs::write(src.join("other.lua"), "return 2").unwrap();
        std::fs::write(src.join("x.lua.bak"), "old").unwrap();
        std::fs::write(src.join("done-protocol.lua.meta.json"), r#"{"savedBy":"pm"}"#).unwrap();
        let rep = l.import_dir(&src, "project", "p1", &["lua".into()], "import").unwrap();
        assert_eq!(rep.scripts, 4);
        let a = l.find("project", "p1", "done-protocol").unwrap().unwrap();
        assert_eq!((a.title.as_str(), a.description.as_str(), a.author.as_str()), ("Done protocol", "Moves a done ticket forward. Second line.", "pm"));
        assert!(l.find("project", "p1", "done-protocol-4").unwrap().unwrap().flag.unwrap().contains("versioned copy of 'done-protocol'"));
        assert!(l.find("project", "p1", "done-protocol-merge-5").unwrap().unwrap().flag.is_some());
        assert!(l.find("project", "p1", "other").unwrap().unwrap().flag.unwrap().contains("identical"));
        // a second import adds nothing
        assert_eq!(l.import_dir(&src, "project", "p1", &["lua".into()], "import").unwrap().scripts, 0);
    }
}
