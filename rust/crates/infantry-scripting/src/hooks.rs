// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Hooks: when an event happens, run a stored script daemon-side (no model, no tokens).
//!
//! A hook is DATA in `<project>/.infantry/scripts/hooks.json` (plus the app-wide `global-hooks.json`, the hooks
//! plugins declare, and the hooks this crate ships). It fires on a named event (`ticket.commented`, a prefix
//! `ticket.*`, `*`), on a lane change (`lane enter done` = the `ticket.moved` event whose `to` is done), or on a
//! timer (`every 30s`). Events come from infantry_core's [`EventBus`]; timers tick on tokio.
//!
//! LOOP GUARD: a run is attributed by what it touched (the ids its actions name, its triggering ticket). An event on
//! one of those ids during the run or `attribution_ms` after is that run's child (depth + 1), refused past
//! `max_depth`; a hook that fires more than `max_runs` times in `window_ms` is refused. Refusals and failures are
//! logged and reported (a comment on the triggering ticket, mail to the owner when `mail_failures` is on).
use crate::dispatcher::{RunOptions, RunReport, ScriptDispatcher};
use crate::engine::{ScriptCaller, ScriptEngines, ScriptLimits};
use crate::host::ToolInvoker;
use crate::runlog::ScriptRunLog;
use crate::store::{ProjectDirs, ScriptStore, DIR};
use infantry_core::bus::{EventBus, NamedEvent};
use regex::Regex;
use serde_json::{json, Map, Value};
use std::collections::{HashMap, HashSet, VecDeque};
use std::path::PathBuf;
use std::sync::{Arc, Mutex, OnceLock};

pub const LANE_EVENT: &str = "ticket.moved";
pub const EXEC_TICK: &str = "exec.tick";
pub const INCOMING_EVENT: &str = "mail.incoming";
pub const BUILTIN_SCOPE: &str = "builtin";
pub const TIMER_EVENT: &str = "timer.tick";
pub const HOOKS_PATH: &str = "scripts/hooks.json";

fn re(cell: &'static OnceLock<Regex>, pat: &str) -> &'static Regex {
    cell.get_or_init(|| Regex::new(pat).unwrap())
}
static LANE_RE: OnceLock<Regex> = OnceLock::new();
static NAME_RE: OnceLock<Regex> = OnceLock::new();
static ID_RE: OnceLock<Regex> = OnceLock::new();
static EVERY_RE: OnceLock<Regex> = OnceLock::new();

/// What a hook listens to.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HookOn {
    Lane { lane: String, exit: bool },
    Named(String),
    Every(u64),
}

impl std::fmt::Display for HookOn {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            HookOn::Lane { lane, exit } => write!(f, "lane {} {lane}", if *exit { "exit" } else { "enter" }),
            HookOn::Named(p) => write!(f, "{p}"),
            HookOn::Every(s) => write!(f, "every {s}s"),
        }
    }
}

impl HookOn {
    /// `lane enter <lane>` / `lane exit <lane>` (case and spacing free), `every <N>s`, or an event name / pattern.
    pub fn parse(text: &str) -> Result<HookOn, String> {
        let t = text.split_whitespace().collect::<Vec<_>>().join(" ");
        if t.is_empty() {
            return Err("a hook needs 'on' (e.g. 'lane enter done' or 'ticket.commented')".into());
        }
        let lower = t.to_lowercase();
        if lower.starts_with("every ") {
            let n = re(&EVERY_RE, r"^(?i)every (\d{1,7})s$").captures(&t).and_then(|c| c[1].parse::<u64>().ok());
            return match n {
                Some(n) if n > 0 => Ok(HookOn::Every(n)),
                _ => Err(format!("bad timer hook '{t}' (expected: every <N>s, e.g. every 30s)")),
            };
        }
        if lower.starts_with("lane ") {
            let parts: Vec<&str> = t.split(' ').collect();
            let dir = parts.get(1).map(|d| d.to_lowercase());
            let ok = parts.len() == 3 && matches!(dir.as_deref(), Some("enter" | "exit")) && re(&LANE_RE, r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$").is_match(parts[2]);
            if !ok {
                return Err(format!("bad lane hook '{t}' (expected: lane enter <lane> or lane exit <lane>)"));
            }
            return Ok(HookOn::Lane { lane: parts[2].to_lowercase(), exit: dir.as_deref() == Some("exit") });
        }
        let core = if t == "*" { "x".to_string() } else { t.strip_suffix(".*").or_else(|| t.strip_suffix('.')).unwrap_or(&t).to_string() };
        if !re(&NAME_RE, r"^[a-z][a-z0-9_-]*([.][a-z0-9][a-z0-9_-]*)*$").is_match(&core) {
            return Err(format!("bad event '{t}' (a dotted lower-case name like ticket.commented, a prefix 'ticket.*', or 'lane enter <lane>', 'lane exit <lane>')"));
        }
        Ok(HookOn::Named(t))
    }

    pub fn matches(&self, event: &str, payload: &Map<String, Value>) -> bool {
        match self {
            HookOn::Lane { lane, exit } => event == LANE_EVENT && str_of(payload, if *exit { "from" } else { "to" }).is_some_and(|v| v.eq_ignore_ascii_case(lane)),
            HookOn::Every(_) => false,
            HookOn::Named(p) => {
                if p == "*" {
                    true
                } else if let Some(prefix) = p.strip_suffix('*') {
                    event.starts_with(prefix)
                } else if p.ends_with('.') {
                    event.starts_with(p.as_str())
                } else {
                    p == event
                }
            }
        }
    }
}

pub fn valid_hook_id(id: &str) -> bool {
    re(&ID_RE, r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$").is_match(id)
}

/// One hook: when `on` happens, run the stored script `script` with `args` plus the event payload.
#[derive(Debug, Clone, PartialEq)]
pub struct Hook {
    pub id: String,
    pub on: HookOn,
    pub script: String,
    pub args: Map<String, Value>,
    pub enabled: bool,
    /// Who last edited it (audit only). A mail hook runs as this session; every other hook runs as the project.
    pub owner_session: Option<String>,
    pub owner_role: Option<String>,
    pub owner_is_owner: bool,
    /// A `mail.incoming` hook names exactly one session; a timer may name the session it acts for.
    pub to: Option<String>,
    /// Also runs for events that name NO project.
    pub global: bool,
    /// Read from the app-wide hook file: runs for EVERY project's events, as that project.
    pub arsenal: bool,
    /// The module that declares it (never stored).
    pub plugin: Option<String>,
}

impl Hook {
    pub fn new(id: &str, on: HookOn, script: &str) -> Self {
        Hook {
            id: id.into(),
            on,
            script: script.into(),
            args: Map::new(),
            enabled: true,
            owner_session: None,
            owner_role: None,
            owner_is_owner: false,
            to: None,
            global: false,
            arsenal: false,
            plugin: None,
        }
    }

    pub fn to_json(&self) -> Value {
        let mut o = Map::new();
        o.insert("id".into(), json!(self.id));
        o.insert("on".into(), json!(self.on.to_string()));
        o.insert("script".into(), json!(self.script));
        if !self.args.is_empty() {
            o.insert("args".into(), Value::Object(self.args.clone()));
        }
        if !self.enabled {
            o.insert("enabled".into(), json!(false));
        }
        if let Some(s) = &self.owner_session {
            o.insert("ownerSession".into(), json!(s));
        }
        if let Some(s) = &self.owner_role {
            o.insert("ownerRole".into(), json!(s));
        }
        if self.owner_is_owner {
            o.insert("ownerIsOwner".into(), json!(true));
        }
        if let Some(s) = &self.to {
            o.insert("to".into(), json!(s));
        }
        if self.global {
            o.insert("global".into(), json!(true));
        }
        Value::Object(o)
    }

    pub fn line(&self) -> String {
        let mut s = format!("{}  on {}", self.id, self.on);
        if let Some(t) = &self.to {
            s.push_str(&format!(" to {t}"));
        }
        s.push_str(&format!(" -> {}", self.script));
        if let Some(o) = &self.owner_session {
            s.push_str(&format!(" (edited by {o}{})", self.owner_role.as_ref().map(|r| format!("/{r}")).unwrap_or_default()));
        }
        if !self.args.is_empty() {
            s.push_str(&format!(" {}", Value::Object(self.args.clone())));
        }
        if self.global {
            s.push_str("  [global]");
        }
        if self.arsenal {
            s.push_str("  [arsenal: every project]");
        }
        if let Some(p) = &self.plugin {
            s.push_str(&format!("  [plugin: {p}, every project]"));
        }
        if !self.enabled {
            s.push_str("  [disabled]");
        }
        s
    }
}

/// Parses a whole `hooks.json`; a bad entry fails the file (named in the message) rather than dropping a hook.
pub fn parse_hooks(text: &str) -> Result<Vec<Hook>, String> {
    let root: Value = serde_json::from_str(text).map_err(|e| e.to_string())?;
    let root = root.as_object().ok_or("not a JSON object")?;
    let Some(arr) = root.get("hooks").and_then(Value::as_array) else { return Ok(Vec::new()) };
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for (i, el) in arr.iter().enumerate() {
        let o = el.as_object().ok_or(format!("hooks[{i}] is not an object"))?;
        let s = |k: &str| o.get(k).and_then(Value::as_str).map(str::trim).filter(|v| !v.is_empty()).map(str::to_string);
        let b = |k: &str| o.get(k).and_then(Value::as_bool);
        let id = s("id").ok_or(format!("hooks[{i}] has no id"))?;
        if !valid_hook_id(&id) {
            return Err(format!("hook id '{id}' is bad (letters, digits, '_', '-', '.'; at most 64)"));
        }
        if !seen.insert(id.clone()) {
            return Err(format!("hook id '{id}' is listed twice"));
        }
        let script = s("script").ok_or(format!("hook '{id}' names no script"))?;
        let on = HookOn::parse(&s("on").unwrap_or_default())?;
        out.push(Hook {
            args: o.get("args").and_then(Value::as_object).cloned().unwrap_or_default(),
            enabled: b("enabled").unwrap_or(true),
            owner_session: s("ownerSession"),
            owner_role: s("ownerRole"),
            owner_is_owner: b("ownerIsOwner") == Some(true),
            to: s("to"),
            global: b("global") == Some(true),
            ..Hook::new(&id, on, &script)
        });
    }
    Ok(out)
}

fn hooks_doc(hooks: &[Hook]) -> String {
    serde_json::to_string_pretty(&json!({"version": 1, "hooks": hooks.iter().map(Hook::to_json).collect::<Vec<_>>()})).unwrap_or_default()
}

type Stamp = Option<(u128, u64)>;

#[derive(Clone)]
struct Cached {
    stamp: Stamp,
    hooks: Vec<Hook>,
    error: Option<String>,
}

/// The hooks of each project, read at each event with the file's mtime+size as the cache key.
pub struct HookStore {
    project_dirs: ProjectDirs,
    arsenal_file: Option<PathBuf>,
    plugin_hooks: Arc<dyn Fn() -> Vec<Hook> + Send + Sync>,
    cache: Mutex<HashMap<String, Cached>>,
    write: Mutex<()>,
    /// When set, hooks live in the database and name their script by id (hooks.json is only imported once).
    library: Option<Arc<crate::library::ScriptLibrary>>,
}

fn stamp(p: &std::path::Path) -> Stamp {
    let md = std::fs::metadata(p).ok()?;
    let m = md.modified().ok()?.duration_since(std::time::UNIX_EPOCH).ok()?.as_nanos();
    Some((m, md.len()))
}

impl HookStore {
    pub fn new(project_dirs: ProjectDirs, arsenal_file: Option<PathBuf>) -> Self {
        HookStore { project_dirs, arsenal_file, plugin_hooks: Arc::new(Vec::new), cache: Mutex::new(HashMap::new()), write: Mutex::new(()), library: None }
    }

    pub fn with_library(mut self, l: Arc<crate::library::ScriptLibrary>) -> Self {
        self.library = Some(l);
        self
    }

    /// The stored hooks of `scope_key` (`""` = the arsenal) from the database; the script name follows the script id.
    fn db_hooks(&self, scope_key: &str) -> Vec<Hook> {
        let Some(l) = &self.library else { return Vec::new() };
        l.hooks(scope_key)
            .unwrap_or_default()
            .into_iter()
            .filter_map(|(_, script_id, name, data)| {
                let v: Value = serde_json::from_str(&data).ok()?;
                let mut h = parse_hooks(&json!({"hooks": [v]}).to_string()).ok()?.into_iter().next()?;
                h.script = script_id.and_then(|id| l.get(&id).ok().flatten()).map(|s| s.name).unwrap_or(name);
                Some(h)
            })
            .collect()
    }

    /// Replaces the hooks of `scope_key` with `next`: puts the new and changed, deletes the removed.
    fn db_replace(&self, project: &str, scope_key: &str, next: &[Hook]) -> Result<(), String> {
        let l = self.library.as_ref().ok_or("no library")?;
        let have: Vec<String> = l.hooks(scope_key)?.into_iter().map(|h| h.0).collect();
        for h in next {
            let sid = if scope_key.is_empty() { l.find("global", "", &h.script)? } else { l.resolve(project, &h.script)? }.map(|s| s.id);
            l.hook_put(scope_key, &h.id, sid.as_deref(), &h.script, &h.to_json().to_string())?;
        }
        for id in have.iter().filter(|id| next.iter().all(|h| &h.id != *id)) {
            l.hook_delete(scope_key, id)?;
        }
        Ok(())
    }

    pub fn with_plugin_hooks(mut self, f: Arc<dyn Fn() -> Vec<Hook> + Send + Sync>) -> Self {
        self.plugin_hooks = f;
        self
    }

    fn path(&self, project: &str) -> Option<PathBuf> {
        (self.project_dirs)(project).map(|d| d.join(DIR).join("hooks.json"))
    }

    /// The project's hooks, then arsenal hooks it does not shadow, then plugin hooks neither shadows.
    pub fn list(&self, project: &str) -> Vec<Hook> {
        let own = self.list_own(project);
        let mut shared = own.clone();
        shared.extend(self.arsenal_hooks().into_iter().filter(|g| own.iter().all(|h| h.id != g.id)));
        let plugin = (self.plugin_hooks)();
        let extra: Vec<Hook> = plugin.into_iter().filter(|h| shared.iter().all(|s| s.id != h.id)).collect();
        shared.extend(extra);
        shared
    }

    pub fn list_own(&self, project: &str) -> Vec<Hook> {
        if self.library.is_some() {
            return self.db_hooks(project);
        }
        self.load(project).hooks
    }

    /// Why `hooks.json` of `project` could not be read, or `None`.
    pub fn error(&self, project: &str) -> Option<String> {
        if self.library.is_some() {
            return None;
        }
        self.load(project).error
    }

    pub fn arsenal_hooks(&self) -> Vec<Hook> {
        if self.library.is_some() {
            return self.db_hooks("").into_iter().map(|h| Hook { arsenal: true, ..h }).collect();
        }
        let Some(f) = self.arsenal_file.as_ref().filter(|f| f.is_file()) else { return Vec::new() };
        let text = std::fs::read_to_string(f).unwrap_or_default();
        parse_hooks(&text).unwrap_or_default().into_iter().map(|h| Hook { arsenal: true, ..h }).collect()
    }

    pub fn add_arsenal(&self, hook: Hook) -> Result<(), String> {
        let _g = self.write.lock().unwrap();
        if self.library.is_some() {
            let mut cur = self.db_hooks("");
            if cur.iter().any(|h| h.id == hook.id) {
                return Err(format!("global hook '{}' already exists (remove it first)", hook.id));
            }
            cur.push(hook);
            return self.db_replace("", "", &cur);
        }
        let f = self.arsenal_file.clone().ok_or("this daemon has no global hook file")?;
        let mut cur = if f.is_file() { parse_hooks(&std::fs::read_to_string(&f).unwrap_or_default()).map_err(|e| format!("global hooks file does not parse: {e}"))? } else { Vec::new() };
        if cur.iter().any(|h| h.id == hook.id) {
            return Err(format!("global hook '{}' already exists (remove it first)", hook.id));
        }
        cur.push(hook);
        write_file(&f, &hooks_doc(&cur))
    }

    pub fn remove_arsenal(&self, id: &str) -> Result<bool, String> {
        let _g = self.write.lock().unwrap();
        if self.library.is_some() {
            let cur = self.db_hooks("");
            let next: Vec<Hook> = cur.iter().filter(|h| h.id != id).cloned().collect();
            if next.len() == cur.len() {
                return Ok(false);
            }
            self.db_replace("", "", &next)?;
            return Ok(true);
        }
        let Some(f) = self.arsenal_file.clone().filter(|f| f.is_file()) else { return Ok(false) };
        let cur = parse_hooks(&std::fs::read_to_string(&f).unwrap_or_default()).map_err(|e| format!("global hooks file does not parse: {e}"))?;
        let next: Vec<Hook> = cur.iter().filter(|h| h.id != id).cloned().collect();
        if next.len() == cur.len() {
            return Ok(false);
        }
        write_file(&f, &hooks_doc(&next))?;
        Ok(true)
    }

    pub fn add(&self, project: &str, hook: Hook) -> Result<(), String> {
        self.mutate(project, |cur| {
            if cur.iter().any(|h| h.id == hook.id) {
                return Err(format!("hook '{}' already exists (remove it first)", hook.id));
            }
            let mut next = cur.to_vec();
            next.push(hook.clone());
            Ok(next)
        })
    }

    pub fn remove(&self, project: &str, id: &str) -> Result<bool, String> {
        let mut found = false;
        self.mutate(project, |cur| {
            let next: Vec<Hook> = cur.iter().filter(|h| h.id != id).cloned().collect();
            found = next.len() != cur.len();
            Ok(next)
        })?;
        Ok(found)
    }

    /// Drops every hook attached to `session` (it ended), in each of `projects`. Returns how many went.
    pub fn remove_for_session(&self, projects: &[String], session: &str) -> usize {
        let mut n = 0;
        for p in projects {
            if self.list_own(p).iter().all(|h| h.to.as_deref() != Some(session)) {
                continue;
            }
            let _ = self.mutate(p, |cur| {
                let next: Vec<Hook> = cur.iter().filter(|h| h.to.as_deref() != Some(session)).cloned().collect();
                n += cur.len() - next.len();
                Ok(next)
            });
        }
        n
    }

    fn mutate(&self, project: &str, mut change: impl FnMut(&[Hook]) -> Result<Vec<Hook>, String>) -> Result<(), String> {
        let _g = self.write.lock().unwrap();
        if self.library.is_some() {
            let cur = self.db_hooks(project);
            let next = change(&cur)?;
            return self.db_replace(project, project, &next);
        }
        let path = self.path(project).ok_or_else(|| format!("unknown project '{project}'"))?;
        // Refuse to overwrite a file we cannot read: the owner's hand edit must not be lost to a failed parse.
        let cur = match std::fs::read_to_string(&path) {
            Ok(t) => parse_hooks(&t).map_err(|e| format!("{HOOKS_PATH} does not parse: {e}"))?,
            Err(_) => Vec::new(),
        };
        let next = change(&cur)?;
        write_file(&path, &hooks_doc(&next))?;
        self.cache.lock().unwrap().remove(project);
        Ok(())
    }

    fn load(&self, project: &str) -> Cached {
        let Some(path) = self.path(project) else { return Cached { stamp: None, hooks: Vec::new(), error: None } };
        let st = stamp(&path);
        if let Some(c) = self.cache.lock().unwrap().get(project).filter(|c| c.stamp == st) {
            return c.clone();
        }
        let fresh = match st {
            None => Cached { stamp: None, hooks: Vec::new(), error: None },
            Some(_) => match std::fs::read_to_string(&path) {
                Err(_) => Cached { stamp: st, hooks: Vec::new(), error: None },
                Ok(t) => match parse_hooks(&t) {
                    Ok(h) => Cached { stamp: st, hooks: h, error: None },
                    Err(e) => Cached { stamp: st, hooks: Vec::new(), error: Some(e) },
                },
            },
        };
        self.cache.lock().unwrap().insert(project.into(), fresh.clone());
        fresh
    }
}

fn write_file(path: &std::path::Path, text: &str) -> Result<(), String> {
    if let Some(d) = path.parent() {
        std::fs::create_dir_all(d).map_err(|e| e.to_string())?;
    }
    std::fs::write(path, text).map_err(|e| e.to_string())
}

/// A hook this crate SHIPS (`builtin-scripts/builtins.json`): runs daemon-wide as the automation identity.
#[derive(Debug, Clone, PartialEq)]
pub struct BuiltinHook {
    pub id: String,
    pub on: HookOn,
    pub script: String,
    pub extension: String,
    pub source: String,
    pub args: Map<String, Value>,
    /// script arg -> plugin setting key, read at each run (`"true"`/`"false"` become booleans).
    pub config_args: Vec<(String, String)>,
}

pub const BUILTINS_OFF_SETTING: &str = "scripting.builtins.off";
const BUILTINS_INDEX: &str = include_str!("../builtin-scripts/builtins.json");
const BUILTIN_FILES: &[(&str, &str)] = &[("resume-after-restart.lua", include_str!("../builtin-scripts/resume-after-restart.lua"))];

impl BuiltinHook {
    pub fn resolved_args(&self, config: &dyn Fn(&str) -> Option<String>) -> Map<String, Value> {
        let mut m = self.args.clone();
        for (arg, key) in &self.config_args {
            if let Some(v) = config(key) {
                let val = match v.trim().to_lowercase().as_str() {
                    "true" => Value::Bool(true),
                    "false" => Value::Bool(false),
                    _ => Value::String(v),
                };
                m.insert(arg.clone(), val);
            }
        }
        m
    }

    pub fn off(config: &dyn Fn(&str) -> Option<String>) -> HashSet<String> {
        config(BUILTINS_OFF_SETTING).unwrap_or_default().split([',', '\n']).map(str::trim).filter(|s| !s.is_empty()).map(str::to_string).collect()
    }

    /// Reads an index and its scripts through `resource`; a bad entry is skipped with a log line.
    pub fn load(index: &str, resource: &dyn Fn(&str) -> Option<String>, log: &dyn Fn(&str)) -> Vec<BuiltinHook> {
        let Some(hooks) = serde_json::from_str::<Value>(index).ok().and_then(|v| v.get("hooks").and_then(Value::as_array).cloned()) else {
            log("builtins: index unreadable");
            return Vec::new();
        };
        hooks
            .iter()
            .filter_map(|el| {
                let r = (|| -> Result<BuiltinHook, String> {
                    let o = el.as_object().ok_or("not an object")?;
                    let s = |k: &str| o.get(k).and_then(Value::as_str).map(str::trim).filter(|v| !v.is_empty()).map(str::to_string);
                    let id = s("id").ok_or("no id")?;
                    let file = s("script").ok_or(format!("hook {id} has no script"))?;
                    if file.contains('/') || file.contains('\\') || !file.contains('.') {
                        return Err(format!("hook {id}: script must be a plain file name with its extension"));
                    }
                    let source = resource(&file).ok_or(format!("hook {id}: no {file}"))?;
                    let (script, ext) = file.rsplit_once('.').unwrap();
                    Ok(BuiltinHook {
                        on: HookOn::parse(&s("on").ok_or(format!("hook {id} has no on"))?)?,
                        script: script.into(),
                        extension: ext.into(),
                        source,
                        args: o.get("args").and_then(Value::as_object).cloned().unwrap_or_default(),
                        config_args: o
                            .get("configArgs")
                            .and_then(Value::as_object)
                            .map(|m| m.iter().filter_map(|(k, v)| v.as_str().map(|s| (k.clone(), s.to_string()))).collect())
                            .unwrap_or_default(),
                        id,
                    })
                })();
                r.map_err(|e| log(&format!("builtins: entry skipped: {e}"))).ok()
            })
            .collect()
    }

    /// The hooks this crate ships.
    pub fn shipped() -> Vec<BuiltinHook> {
        Self::load(BUILTINS_INDEX, &|f| BUILTIN_FILES.iter().find(|(n, _)| *n == f).map(|(_, s)| s.to_string()), &|_| {})
    }
}

/// An event as hooks see it. `id`, `cause_id`, `depth` and `source` come from the bus payload's reserved keys
/// `_eventId`, `_causeId`, `_depth`, `_source` when the publisher sets them (core's `NamedEvent` carries name + payload only).
#[derive(Debug, Clone, PartialEq)]
pub struct HookEvent {
    pub name: String,
    pub payload: Map<String, Value>,
    pub id: String,
    pub cause_id: Option<String>,
    pub depth: u32,
    pub source: String,
}

impl HookEvent {
    pub fn new(name: &str, payload: Value) -> Self {
        HookEvent { name: name.into(), payload: payload.as_object().cloned().unwrap_or_default(), id: String::new(), cause_id: None, depth: 0, source: "daemon".into() }
    }

    pub fn from_bus(e: &NamedEvent) -> Self {
        let mut payload = e.payload.as_object().cloned().unwrap_or_default();
        let id = payload.remove("_eventId").and_then(|v| v.as_str().map(str::to_string)).unwrap_or_default();
        let cause_id = payload.remove("_causeId").and_then(|v| v.as_str().map(str::to_string));
        let depth = payload.remove("_depth").and_then(|v| v.as_u64()).unwrap_or(0) as u32;
        let source = payload.remove("_source").and_then(|v| v.as_str().map(str::to_string)).unwrap_or_else(|| "daemon".into());
        HookEvent { name: e.name.clone(), payload, id, cause_id, depth, source }
    }
}

/// Mail about to be delivered (`mail.incoming`).
#[derive(Debug, Clone, Default, PartialEq)]
pub struct IncomingMail {
    pub id: String,
    pub from: String,
    pub to: String,
    pub subject: String,
    pub body: String,
    pub recipient_session_id: Option<String>,
    pub recipient_role: Option<String>,
    pub thread_id: Option<String>,
    pub project_id: Option<String>,
    pub from_project_id: Option<String>,
    pub from_project_name: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MailVerdict {
    pub handled: bool,
    pub text: Option<String>,
}

/// Loop-guard limits; all settings (`scripting.hooks.*`), read at each event.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HookGuardConfig {
    pub max_depth: u32,
    pub max_runs: usize,
    pub window_ms: u64,
    pub attribution_ms: u64,
}

impl Default for HookGuardConfig {
    fn default() -> Self {
        HookGuardConfig { max_depth: 3, max_runs: 10, window_ms: 60_000, attribution_ms: 5_000 }
    }
}

/// An automation mark on a session card while one of its hooks runs (display only).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AutomationMark {
    pub script: String,
    pub hook_id: String,
    pub since: u64,
}

/// Where hook runs execute.
#[derive(Clone)]
pub enum Executor {
    /// On the caller (tests).
    Inline,
    /// Serial per ticket (events of one ticket run in publish order), parallel across tickets, on tokio's blocking pool.
    Tokio(tokio::runtime::Handle),
}

/// The pluggable parts of [`ScriptHooks`]; every field has a working default.
#[derive(Clone)]
pub struct HooksConfig {
    pub projects: Arc<dyn Fn() -> Vec<String> + Send + Sync>,
    pub limits: Arc<dyn Fn() -> ScriptLimits + Send + Sync>,
    pub guard: Arc<dyn Fn() -> HookGuardConfig + Send + Sync>,
    pub enabled: Arc<dyn Fn() -> bool + Send + Sync>,
    pub mail_failures: Arc<dyn Fn() -> bool + Send + Sync>,
    /// (project, branch) -> the branch's tip sha.
    pub tip: Arc<dyn Fn(&str, &str) -> Option<String> + Send + Sync>,
    pub log: Arc<dyn Fn(&str) + Send + Sync>,
    pub automation: Arc<dyn Fn(&str, Option<AutomationMark>) + Send + Sync>,
    pub clock: Arc<dyn Fn() -> u64 + Send + Sync>,
    pub builtins: Arc<dyn Fn() -> Vec<BuiltinHook> + Send + Sync>,
    /// This plugin's settings (builtin config args, the builtins off switch).
    pub config: Arc<dyn Fn(&str) -> Option<String> + Send + Sync>,
    pub executor: Executor,
}

impl Default for HooksConfig {
    fn default() -> Self {
        HooksConfig {
            projects: Arc::new(Vec::new),
            limits: Arc::new(ScriptLimits::default),
            guard: Arc::new(HookGuardConfig::default),
            enabled: Arc::new(|| true),
            mail_failures: Arc::new(|| false),
            tip: Arc::new(|_, _| None),
            log: Arc::new(|_| {}),
            automation: Arc::new(|_, _| {}),
            clock: Arc::new(crate::runlog::now_ms),
            builtins: Arc::new(Vec::new),
            config: Arc::new(|_| None),
            executor: Executor::Inline,
        }
    }
}

struct Run {
    depth: u32,
    touched: Mutex<HashSet<String>>,
    touched_at: Mutex<u64>,
    ended_at: Mutex<Option<u64>>,
}

/// The hook runner. Share it as `Arc<ScriptHooks>`.
pub struct ScriptHooks {
    store: Arc<HookStore>,
    scripts: ScriptStore,
    engines: ScriptEngines,
    dispatcher: ScriptDispatcher,
    run_log: Arc<ScriptRunLog>,
    cfg: HooksConfig,
    runs: Mutex<Vec<Arc<Run>>>,
    fires: Mutex<HashMap<String, VecDeque<u64>>>,
    reported: Mutex<HashMap<String, u64>>,
    seen: Mutex<(HashSet<String>, VecDeque<String>)>,
    inflight: Mutex<HashSet<String>>,
    once: Mutex<HashSet<String>>,
    last_timer: Mutex<HashMap<String, u64>>,
    tick_count: Mutex<HashMap<String, u64>>,
    lanes: Mutex<HashMap<String, tokio::sync::mpsc::UnboundedSender<Box<dyn FnOnce() + Send>>>>,
}

fn str_of<'a>(o: &'a Map<String, Value>, k: &str) -> Option<&'a str> {
    o.get(k).and_then(Value::as_str).filter(|s| !s.trim().is_empty())
}

/// A JSON scalar as text (the Kotlin `contentOrNull`): strings as is, numbers and booleans printed.
fn text_of(o: &Map<String, Value>, k: &str) -> Option<String> {
    match o.get(k)? {
        Value::String(s) if !s.trim().is_empty() => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}

/// Two ids name the same thing when equal, or one is the other's 8+ char prefix (`ticket-` stripped).
pub fn same_id(a: &str, b: &str) -> bool {
    let x = a.strip_prefix("ticket-").unwrap_or(a);
    let y = b.strip_prefix("ticket-").unwrap_or(b);
    x == y || (x.len().min(y.len()) >= 8 && (x.starts_with(y) || y.starts_with(x)))
}

/// Does hook `hook` of project `hook_project` run for an event of `event_project`? Never for another project's.
pub fn admits_project(hook_project: &str, hook: &Hook, event_project: Option<&str>) -> bool {
    match event_project {
        None => hook.global,
        Some(p) => p == hook_project,
    }
}

fn flatten(v: &Value, out: &mut Vec<String>) {
    match v {
        Value::String(s) => out.push(s.clone()),
        Value::Object(m) => m.values().for_each(|x| flatten(x, out)),
        Value::Array(a) => a.iter().for_each(|x| flatten(x, out)),
        _ => {}
    }
}

fn reason_line(what: &str) -> String {
    let l = what.lines().find_map(|l| l.strip_prefix("error:")).or_else(|| what.lines().next()).unwrap_or("");
    l.trim().chars().take(300).collect()
}

fn cut(s: &str, n: usize) -> String {
    s.chars().take(n).collect()
}

impl ScriptHooks {
    pub fn new(store: Arc<HookStore>, scripts: ScriptStore, engines: ScriptEngines, dispatcher: ScriptDispatcher, run_log: Arc<ScriptRunLog>, cfg: HooksConfig) -> Self {
        ScriptHooks {
            store,
            scripts,
            engines,
            dispatcher,
            run_log,
            cfg,
            runs: Mutex::new(Vec::new()),
            fires: Mutex::new(HashMap::new()),
            reported: Mutex::new(HashMap::new()),
            seen: Mutex::new((HashSet::new(), VecDeque::new())),
            inflight: Mutex::new(HashSet::new()),
            once: Mutex::new(HashSet::new()),
            last_timer: Mutex::new(HashMap::new()),
            tick_count: Mutex::new(HashMap::new()),
            lanes: Mutex::new(HashMap::new()),
        }
    }

    pub fn store(&self) -> &Arc<HookStore> {
        &self.store
    }

    fn log(&self, s: &str) {
        (self.cfg.log)(s)
    }

    fn now(&self) -> u64 {
        (self.cfg.clock)()
    }

    /// Subscribes to the bus: every named event goes through [`ScriptHooks::on_event`]. Returns the task.
    pub fn attach(self: &Arc<Self>, bus: &EventBus) -> tokio::task::JoinHandle<()> {
        let mut rx = bus.subscribe_events();
        let me = self.clone();
        tokio::spawn(async move {
            loop {
                match rx.recv().await {
                    Ok(e) => me.on_event(HookEvent::from_bus(&e)),
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(n)) => me.log(&format!("hooks: lagged, {n} event(s) dropped")),
                    Err(tokio::sync::broadcast::error::RecvError::Closed) => break,
                }
            }
        })
    }

    /// Ticks the `every <N>s` hooks once a second (the floor is `min_every_sec()`, setting scripting.hooks.minEverySec).
    pub fn spawn_timers(self: &Arc<Self>, min_every_sec: Arc<dyn Fn() -> u64 + Send + Sync>) -> tokio::task::JoinHandle<()> {
        let me = self.clone();
        tokio::spawn(async move {
            let mut iv = tokio::time::interval(std::time::Duration::from_secs(1));
            iv.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                iv.tick().await;
                me.timer_tick(min_every_sec());
            }
        })
    }

    fn submit(self: &Arc<Self>, key: String, job: Box<dyn FnOnce() + Send>) {
        match &self.cfg.executor {
            Executor::Inline => job(),
            Executor::Tokio(h) => {
                let mut lanes = self.lanes.lock().unwrap();
                if let Some(tx) = lanes.get(&key) {
                    match tx.send(job) {
                        Ok(()) => return,
                        Err(e) => {
                            lanes.remove(&key);
                            return self.start_lane(&mut lanes, h, key, e.0);
                        }
                    }
                }
                self.start_lane(&mut lanes, h, key, job);
            }
        }
    }

    #[allow(clippy::type_complexity)]
    fn start_lane(
        self: &Arc<Self>,
        lanes: &mut HashMap<String, tokio::sync::mpsc::UnboundedSender<Box<dyn FnOnce() + Send>>>,
        h: &tokio::runtime::Handle,
        key: String,
        first: Box<dyn FnOnce() + Send>,
    ) {
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<Box<dyn FnOnce() + Send>>();
        let _ = tx.send(first);
        lanes.insert(key.clone(), tx);
        let me = Arc::downgrade(self);
        h.spawn(async move {
            loop {
                match tokio::time::timeout(std::time::Duration::from_secs(30), rx.recv()).await {
                    Ok(Some(job)) => {
                        let _ = tokio::task::spawn_blocking(job).await;
                    }
                    Ok(None) => break,
                    Err(_) => {
                        // Idle: retire the lane unless a job slipped in.
                        let Some(me) = me.upgrade() else { break };
                        let mut lanes = me.lanes.lock().unwrap();
                        if rx.is_empty() {
                            lanes.remove(&key);
                            break;
                        }
                    }
                }
            }
        });
    }

    /// Returns at once on the tokio executor: the run happens on the ticket's lane.
    pub fn on_event(self: &Arc<Self>, e: HookEvent) {
        if !(self.cfg.enabled)() {
            return;
        }
        if !self.first_delivery(&e) {
            self.log(&format!("hooks: event {} {} already handled, not re-run", e.name, e.id));
            return;
        }
        let key = str_of(&e.payload, "ticketId").unwrap_or("-").to_string();
        let me = self.clone();
        self.submit(key, Box::new(move || me.dispatch(&e)));
    }

    /// Idempotency: an event id, or the same ticket transition (ticket, seq, from, to), runs hooks once.
    fn first_delivery(&self, e: &HookEvent) -> bool {
        let mut keys = Vec::new();
        if !e.id.trim().is_empty() {
            keys.push(format!("id:{}", e.id));
        }
        if e.name == LANE_EVENT {
            if let Some(seq) = text_of(&e.payload, "seq") {
                keys.push(format!(
                    "moved:{}:{seq}:{}:{}",
                    text_of(&e.payload, "ticketId").unwrap_or_default(),
                    text_of(&e.payload, "from").unwrap_or_default(),
                    text_of(&e.payload, "to").unwrap_or_default()
                ));
            }
        }
        let mut seen = self.seen.lock().unwrap();
        if keys.iter().any(|k| seen.0.contains(k)) {
            return false;
        }
        for k in keys {
            seen.0.insert(k.clone());
            seen.1.push_back(k);
            while seen.1.len() > 2048 {
                if let Some(old) = seen.1.pop_front() {
                    seen.0.remove(&old);
                }
            }
        }
        true
    }

    fn setting_key<'a>(name: &str, o: &'a Map<String, Value>) -> Option<&'a str> {
        if name == "setting.changed" {
            str_of(o, "key")
        } else {
            None
        }
    }

    fn ids_of(e: &HookEvent) -> Vec<String> {
        [str_of(&e.payload, "ticketId"), str_of(&e.payload, "sessionId"), Self::setting_key(&e.name, &e.payload)].into_iter().flatten().map(str::to_string).collect()
    }

    fn dispatch(self: &Arc<Self>, e: &HookEvent) {
        self.run_builtins(e);
        let mut candidates: Vec<(String, Hook)> = Vec::new();
        for p in (self.cfg.projects)() {
            if let Some(err) = self.store.error(&p) {
                if self.once.lock().unwrap().insert(format!("unreadable:{p}")) {
                    self.log(&format!("hooks: project {p} skipped, its hooks cannot be read: {err}"));
                }
            }
            for h in self.store.list(&p) {
                if h.enabled && h.on.matches(&e.name, &e.payload) {
                    candidates.push((p.clone(), h));
                }
            }
        }
        if candidates.is_empty() {
            return;
        }
        let ticket_id = str_of(&e.payload, "ticketId").map(str::to_string);
        let ticket = ticket_id.as_deref().and_then(|t| self.read_ticket(t));
        let project = str_of(&e.payload, "projectId").map(str::to_string).or_else(|| ticket.as_ref().and_then(|t| str_of(t, "projectId").map(str::to_string)));
        let mine: Vec<(String, Hook)> = candidates.into_iter().filter(|(p, h)| admits_project(p, h, project.as_deref())).collect();
        if mine.is_empty() {
            return;
        }
        let ids = Self::ids_of(e);
        let depth = e.depth.max(self.attributed_depth(&ids));
        for (p, hook) in mine {
            let tick_key = (e.name == EXEC_TICK || e.name == TIMER_EVENT).then(|| format!("{p}/{}", hook.id));
            if let Some(k) = &tick_key {
                if !self.inflight.lock().unwrap().insert(k.clone()) {
                    self.slow_once(k, &hook);
                    continue;
                }
            }
            if let Some(refusal) = self.admit(&format!("{p}/{}", hook.id), depth) {
                if let Some(k) = &tick_key {
                    self.inflight.lock().unwrap().remove(k);
                }
                self.log(&format!("hook {} ({p}) refused on {}: {refusal}", hook.id, e.name));
                self.report_once(&p, &hook, ticket_id.as_deref(), "refused", &format!("Hook '{}' (on {}) did not run for {}: {refusal}. Script '{}' was NOT run.", hook.id, hook.on, e.name, hook.script));
                continue;
            }
            let args = self.script_args(&p, &hook, e, ticket.as_ref(), depth);
            let run = self.new_run(depth, &ids);
            self.execute(&p, &hook, e, ticket_id.as_deref(), args, &run);
            if let Some(k) = &tick_key {
                self.inflight.lock().unwrap().remove(k);
            }
        }
    }

    fn new_run(&self, depth: u32, ids: &[String]) -> Arc<Run> {
        let run = Arc::new(Run { depth, touched: Mutex::new(ids.iter().cloned().collect()), touched_at: Mutex::new(self.now()), ended_at: Mutex::new(None) });
        self.runs.lock().unwrap().push(run.clone());
        run
    }

    fn slow_once(&self, key: &str, hook: &Hook) {
        if self.once.lock().unwrap().insert(format!("slow:{key}")) {
            self.log(&format!("hook {} ({key}): a tick arrived while its previous run was still going; skipped (never stacked)", hook.id));
        }
    }

    /// Call about once a second: fires every enabled `every <N>s` hook whose period (never below `min_every_sec`) has passed.
    pub fn timer_tick(self: &Arc<Self>, min_every_sec: u64) {
        if !(self.cfg.enabled)() {
            return;
        }
        let now = self.now();
        for p in (self.cfg.projects)() {
            for h in self.store.list(&p) {
                let HookOn::Every(secs) = h.on else { continue };
                if !h.enabled {
                    continue;
                }
                let key = format!("{p}/{}", h.id);
                let period = secs.max(min_every_sec) * 1000;
                {
                    let mut last = self.last_timer.lock().unwrap();
                    match last.get(&key).copied() {
                        None => {
                            last.insert(key.clone(), now);
                            continue;
                        }
                        Some(l) if now.saturating_sub(l) < period => continue,
                        Some(_) => {
                            last.insert(key.clone(), now);
                        }
                    }
                }
                let n = {
                    let mut c = self.tick_count.lock().unwrap();
                    let v = c.entry(key.clone()).or_insert(0);
                    *v += 1;
                    *v
                };
                let mut e = HookEvent::new(TIMER_EVENT, json!({"tick": n, "at": now, "hookId": h.id, "projectId": p}));
                e.id = format!("timer:{key}:{n}");
                e.source = "timer".into();
                let me = self.clone();
                self.submit(format!("timer:{key}"), Box::new(move || me.dispatch_timer(&e)));
            }
        }
    }

    fn dispatch_timer(self: &Arc<Self>, e: &HookEvent) {
        let Some(p) = str_of(&e.payload, "projectId").map(str::to_string) else { return };
        let hook_id = str_of(&e.payload, "hookId").unwrap_or_default();
        let Some(hook) = self.store.list(&p).into_iter().find(|h| h.id == hook_id && h.enabled && matches!(h.on, HookOn::Every(_))) else { return };
        let key = format!("{p}/{}", hook.id);
        if !self.inflight.lock().unwrap().insert(key.clone()) {
            self.slow_once(&key, &hook);
            return;
        }
        if let Some(refusal) = self.admit(&key, 0) {
            self.log(&format!("hook {} ({p}) refused on timer: {refusal}", hook.id));
            self.report_once(&p, &hook, None, "refused", &format!("Hook '{}' (on {}) did not run: {refusal}. Script '{}' was NOT run.", hook.id, hook.on, hook.script));
        } else {
            let run = self.new_run(0, &[]);
            let args = self.script_args(&p, &hook, e, None, 0);
            self.execute(&p, &hook, e, None, args, &run);
        }
        self.inflight.lock().unwrap().remove(&key);
    }

    /// `mail.incoming`, run SYNCHRONOUSLY on the caller for every enabled hook on exactly that name attached to the
    /// recipient session. The first run that queues `ih.handled()` returns handled; `ih.deliver_text{text=..}` returns that text.
    pub fn incoming(&self, mail: &IncomingMail) -> Option<MailVerdict> {
        if !(self.cfg.enabled)() {
            return None;
        }
        let mut payload = Map::new();
        for (k, v) in [("id", &mail.id), ("from", &mail.from), ("to", &mail.to), ("subject", &mail.subject), ("body", &mail.body)] {
            payload.insert(k.into(), json!(v));
        }
        for (k, v) in [
            ("sessionId", &mail.recipient_session_id),
            ("role", &mail.recipient_role),
            ("threadId", &mail.thread_id),
            ("projectId", &mail.project_id),
            ("fromProjectId", &mail.from_project_id),
            ("fromProjectName", &mail.from_project_name),
        ] {
            if let Some(v) = v {
                payload.insert(k.into(), json!(v));
            }
        }
        let e = HookEvent { name: INCOMING_EVENT.into(), payload, id: format!("mail-incoming:{}", mail.id), cause_id: None, depth: 0, source: "daemon".into() };
        for p in (self.cfg.projects)() {
            for hook in self.store.list(&p) {
                if !admits_project(&p, &hook, mail.project_id.as_deref()) || !hook.enabled || hook.on != HookOn::Named(INCOMING_EVENT.into()) {
                    continue;
                }
                let Some(to) = hook.to.clone() else {
                    if self.once.lock().unwrap().insert(format!("nomail:{p}/{}", hook.id)) {
                        self.log(&format!("hooks: mail.incoming hook {} ({p}) has no session (to), ignored; re-add it with to=<session id>", hook.id));
                    }
                    continue;
                };
                if Some(&to) != mail.recipient_session_id.as_ref() || self.admit(&format!("{p}/{}", hook.id), 0).is_some() {
                    continue;
                }
                if let Some(v) = self.execute_incoming(&p, &hook, &e) {
                    self.summarize(mail, if v.handled { "handled" } else { "rewritten" });
                    return Some(v);
                }
            }
        }
        self.summarize(mail, "delivered");
        None
    }

    fn summarize(&self, mail: &IncomingMail, verdict: &str) {
        if let Some(sid) = &mail.recipient_session_id {
            let subj = if mail.subject.trim().is_empty() { "mail" } else { mail.subject.as_str() };
            self.run_log.mail_summary(&mail.id, subj, sid, verdict);
        }
    }

    fn execute_incoming(&self, project: &str, hook: &Hook, e: &HookEvent) -> Option<MailVerdict> {
        let mail_id = str_of(&e.payload, "id").map(str::to_string);
        let script = match self.script_of(project, hook) {
            Ok(Some(s)) => s,
            Ok(None) => {
                self.fail_quiet(project, hook, &format!("no script '{}' in .infantry/scripts/", hook.script));
                return None;
            }
            Err(m) => {
                self.fail_quiet(project, hook, &m);
                return None;
            }
        };
        let Some(engine) = self.engines.by_extension(&script.extension) else {
            self.fail_quiet(project, hook, &format!("no engine loaded for .{}", script.extension));
            return None;
        };
        let who = ScriptCaller::new(hook.owner_session.clone().unwrap_or_default(), hook.owner_role.clone().unwrap_or_default());
        if let Some(t) = &hook.to {
            self.mark(t, hook);
        }
        let args = self.script_args(project, hook, e, None, 0);
        let inv = self.invoker_of(project, hook);
        let report = self.dispatcher.run(
            engine.as_ref(),
            &script.source,
            &args,
            (self.cfg.limits)(),
            &who,
            &inv,
            RunOptions { dry_run: false, progress_to: hook.to.as_deref() },
            &mut |_| {},
        );
        if let Some(t) = &hook.to {
            (self.cfg.automation)(t, None);
        }
        let from = str_of(&e.payload, "from").unwrap_or_default().to_string();
        let subj = str_of(&e.payload, "subject").unwrap_or("mail").to_string();
        let trig = format!("{} {}", hook.id, e.name);
        let rec = |outcome: &str, summary: String, err: Option<&str>| {
            self.run_log.record(project, &hook.script, &trig, hook.to.as_deref(), Some(&report), outcome, &summary, err, mail_id.as_deref(), true);
        };
        if !report.ok() {
            rec("failed", format!("failed on {subj} from {from}"), report.error.as_deref());
            self.fail_quiet(project, hook, &report.render(&hook.script));
            return None;
        }
        if report.handled {
            rec("handled", format!("handled {subj} from {from}"), None);
            return Some(MailVerdict { handled: true, text: None });
        }
        if let Some(text) = report.deliver_text.clone() {
            rec("delivered", format!("delivered rewritten {subj} from {from}"), None);
            return Some(MailVerdict { handled: false, text: Some(text) });
        }
        rec("passed", format!("passed {subj} from {from} through"), None);
        None
    }

    fn run_builtins(&self, e: &HookEvent) {
        let list = (self.cfg.builtins)();
        if list.is_empty() {
            return;
        }
        let config = self.cfg.config.clone();
        let off = BuiltinHook::off(&*config);
        let ids = Self::ids_of(e);
        let depth = e.depth.max(self.attributed_depth(&ids));
        let project = str_of(&e.payload, "projectId").map(str::to_string);
        for b in list {
            if off.contains(&b.id) || !b.on.matches(&e.name, &e.payload) {
                continue;
            }
            let key = format!("{BUILTIN_SCOPE}/{}", b.id);
            if let Some(r) = self.admit(&key, depth) {
                self.log(&format!("builtin hook {} refused on {}: {r}", b.id, e.name));
                continue;
            }
            let run = self.new_run(depth, &ids);
            let trigger = format!("{BUILTIN_SCOPE}:{} {}", b.id, b.on);
            let rl_project = project.clone().unwrap_or_else(|| BUILTIN_SCOPE.into());
            match self.engines.by_extension(&b.extension) {
                None => self.log(&format!("builtin hook {}: no engine loaded for .{}, not run", b.id, b.extension)),
                Some(engine) => {
                    let mut args = b.resolved_args(&*config);
                    args.extend(e.payload.clone());
                    args.insert("event".into(), json!({"name": e.name, "id": e.id, "causeId": e.cause_id, "depth": depth, "source": e.source}));
                    args.insert("hook".into(), json!(format!("{BUILTIN_SCOPE}:{}", b.id)));
                    let inv = ToolInvoker::automation(project.as_deref(), format!("script:{BUILTIN_SCOPE}:{}", b.id));
                    let report = self.dispatcher.run(engine.as_ref(), &b.source, &args, (self.cfg.limits)(), &ScriptCaller::default(), &inv, RunOptions::default(), &mut |planned| {
                        self.touch(&run, planned);
                    });
                    let summary = format!("ran for {}: {} action(s)", e.name, report.records.len());
                    self.run_log.record(&rl_project, &b.script, &trigger, None, Some(&report), if report.ok() { "ok" } else { "failed" }, &summary, report.error.as_deref(), None, false);
                    if report.ok() {
                        self.log(&format!("builtin hook {} ran {} for {}: {}, {} action(s)", b.id, b.script, e.name, report.outcome, report.records.len()));
                    } else {
                        self.log(&cut(&format!("builtin hook {} FAILED: {}", b.id, reason_line(&report.render(&b.script))), 400));
                    }
                }
            }
            *run.ended_at.lock().unwrap() = Some(self.now());
        }
    }

    fn touch(&self, run: &Run, planned: &[crate::dispatcher::ActionRecord]) {
        *run.touched_at.lock().unwrap() = self.now();
        let mut t = run.touched.lock().unwrap();
        for a in planned {
            let mut v = Vec::new();
            flatten(&Value::Object(a.args.clone()), &mut v);
            t.extend(v);
        }
    }

    /// `None` to run; else why the guard refused. Counts the run against the hook's rate window when admitted.
    fn admit(&self, key: &str, depth: u32) -> Option<String> {
        let g = (self.cfg.guard)();
        if depth > g.max_depth {
            return Some(format!("loop guard: event chain depth {depth} exceeds the limit {} (its own actions keep re-triggering it)", g.max_depth));
        }
        let now = self.now();
        let mut fires = self.fires.lock().unwrap();
        let q = fires.entry(key.into()).or_default();
        while q.front().is_some_and(|f| now.saturating_sub(*f) >= g.window_ms) {
            q.pop_front();
        }
        if q.len() >= g.max_runs {
            return Some(format!("rate limit: {} runs in the last {}s (limit {}); the hook is tripped until the window clears", q.len(), g.window_ms / 1000, g.max_runs));
        }
        q.push_back(now);
        None
    }

    fn attributed_depth(&self, ids: &[String]) -> u32 {
        if ids.is_empty() {
            return 0;
        }
        let now = self.now();
        let window = (self.cfg.guard)().attribution_ms;
        let mut runs = self.runs.lock().unwrap();
        runs.retain(|r| !r.ended_at.lock().unwrap().is_some_and(|end| now.saturating_sub(end) > window));
        let mut best = 0;
        for r in runs.iter() {
            if now.saturating_sub(*r.touched_at.lock().unwrap()) <= window && r.touched.lock().unwrap().iter().any(|t| ids.iter().any(|i| same_id(t, i))) {
                best = best.max(r.depth + 1);
            }
        }
        best
    }

    fn mark(&self, session: &str, hook: &Hook) {
        (self.cfg.automation)(session, Some(AutomationMark { script: hook.script.clone(), hook_id: hook.id.clone(), since: self.now() }));
    }

    fn execute(&self, project: &str, hook: &Hook, e: &HookEvent, ticket_id: Option<&str>, args: Map<String, Value>, run: &Run) {
        let script = match self.script_of(project, hook) {
            Ok(Some(s)) => s,
            Ok(None) => {
                *run.ended_at.lock().unwrap() = Some(self.now());
                return self.fail(project, hook, ticket_id, &format!("no script '{}' in .infantry/scripts/", hook.script));
            }
            Err(m) => {
                *run.ended_at.lock().unwrap() = Some(self.now());
                return self.fail(project, hook, ticket_id, &m);
            }
        };
        let Some(engine) = self.engines.by_extension(&script.extension) else {
            *run.ended_at.lock().unwrap() = Some(self.now());
            return self.fail(project, hook, ticket_id, &format!("no engine loaded for .{}", script.extension));
        };
        let invoker = self.invoker_of(project, hook);
        let who = if hook.to.is_none() { ScriptCaller::default() } else { ScriptCaller::new(hook.owner_session.clone().unwrap_or_default(), hook.owner_role.clone().unwrap_or_default()) };
        let marked = args.get("workerSession").or_else(|| args.get("sessionId")).and_then(Value::as_str).map(str::to_string);
        if let Some(m) = &marked {
            self.mark(m, hook);
        }
        let hook_to = hook.to.clone().filter(|t| Some(t) != marked.as_ref());
        if let Some(t) = &hook_to {
            self.mark(t, hook);
        }
        let report: RunReport = self.dispatcher.run(
            engine.as_ref(),
            &script.source,
            &args,
            (self.cfg.limits)(),
            &who,
            &invoker,
            RunOptions { dry_run: false, progress_to: hook.to.as_deref() },
            &mut |planned| self.touch(run, planned),
        );
        let summary = format!("ran for {}: {} action(s)", e.name, report.records.len());
        self.run_log.record(project, &hook.script, &format!("{} {}", hook.id, hook.on), marked.as_deref(), Some(&report), if report.ok() { "ok" } else { "failed" }, &summary, report.error.as_deref(), None, false);
        if report.ok() {
            self.log(&format!("hook {} ({project}) ran {} for {}: {}, {} action(s)", hook.id, hook.script, e.name, report.outcome, report.records.len()));
        } else {
            self.fail(project, hook, ticket_id, &report.render(&hook.script));
        }
        for s in [marked, hook_to].into_iter().flatten() {
            (self.cfg.automation)(&s, None);
        }
        *run.ended_at.lock().unwrap() = Some(self.now());
    }

    fn fail_quiet(&self, project: &str, hook: &Hook, what: &str) {
        let line = cut(&format!("hook {} ({project}) FAILED: {}", hook.id, reason_line(what)), 400);
        if self.once.lock().unwrap().insert(format!("quiet:{project}/{}/{line}", hook.id)) {
            self.log(&line);
        }
    }

    fn fail(&self, project: &str, hook: &Hook, ticket_id: Option<&str>, what: &str) {
        self.log(&cut(&format!("hook {} ({project}) FAILED: {}", hook.id, reason_line(what)), 400));
        self.report(project, hook, ticket_id, &format!("Hook '{}' (on {}, script '{}') failed: {}\n{}", hook.id, hook.on, hook.script, reason_line(what), cut(what, 1500)));
    }

    fn report_once(&self, project: &str, hook: &Hook, ticket_id: Option<&str>, reason: &str, text: &str) {
        let key = format!("{project}/{}/{reason}", hook.id);
        let now = self.now();
        {
            let mut rep = self.reported.lock().unwrap();
            if rep.get(&key).is_some_and(|last| now.saturating_sub(*last) < (self.cfg.guard)().window_ms) {
                return;
            }
            rep.insert(key, now);
        }
        self.report(project, hook, ticket_id, text);
    }

    /// A failure is never silent: a comment on the triggering ticket; mail to the hook's owner when mail_failures is on.
    fn report(&self, project: &str, hook: &Hook, ticket_id: Option<&str>, text: &str) {
        let sys = ToolInvoker::automation(Some(project), "script-hooks");
        let host = self.dispatcher.host();
        if let Some(t) = ticket_id {
            let mut a = Map::new();
            a.insert("ticketId".into(), json!(t));
            a.insert("kind".into(), json!("issue"));
            a.insert("body".into(), json!(text));
            if !host.invoke("infantry_ticket_comment", &a, &sys).is_error {
                return;
            }
        }
        if !(self.cfg.mail_failures)() {
            return;
        }
        let to = hook.owner_session.clone().or_else(|| hook.owner_role.clone()).unwrap_or_else(|| "pm".into());
        let mut a = Map::new();
        a.insert("to".into(), json!(to));
        a.insert("body".into(), json!(text));
        let r = host.invoke("infantry_message", &a, &sys);
        if r.is_error {
            self.log(&format!("hook {} ({project}): report could not be delivered: {}", hook.id, cut(&r.text, 200)));
        }
    }

    /// A plugin's hook runs its own module's script, never a project or global one that shares the name.
    fn script_of(&self, project: &str, hook: &Hook) -> Result<Option<crate::store::Script>, String> {
        match &hook.plugin {
            Some(m) => Ok(self.scripts.plugins.as_ref().and_then(|p| p.read(m, &hook.script))),
            None => self.scripts.read(project, &hook.script).map_err(|e| format!("script name refused: {e}")),
        }
    }

    /// A project hook runs as the project's automation identity; a mail hook (`to` set) keeps its session's identity.
    pub fn invoker_of(&self, project: &str, hook: &Hook) -> ToolInvoker {
        let label = format!("script:{}", hook.script);
        if hook.to.is_none() {
            return ToolInvoker::automation(Some(project), label);
        }
        let owner = hook.owner_is_owner || hook.owner_session.is_none();
        ToolInvoker {
            owner,
            agent: !owner,
            active_session: if owner { None } else { hook.owner_session.clone() },
            session_id: hook.owner_session.clone(),
            role: hook.owner_role.clone(),
            project_id: Some(project.into()),
            label,
            owner_client: false,
        }
    }

    fn read_ticket(&self, ticket_id: &str) -> Option<Map<String, Value>> {
        let mut a = Map::new();
        a.insert("ticketId".into(), json!(ticket_id));
        let r = self.dispatcher.host().invoke("infantry_ticket_get", &a, &ToolInvoker::automation(None, "script-hooks"));
        if r.is_error {
            return None;
        }
        serde_json::from_str::<Value>(&r.text).ok()?.as_object().cloned().filter(|m| !m.is_empty())
    }

    /// `input.args`: the hook's args, overlaid by the event payload and the ticket's fields, plus `event` and `hook`.
    fn script_args(&self, project: &str, hook: &Hook, e: &HookEvent, ticket: Option<&Map<String, Value>>, depth: u32) -> Map<String, Value> {
        let mut m = hook.args.clone();
        m.extend(e.payload.clone());
        let put = |m: &mut Map<String, Value>, k: &str, v: Option<String>| {
            if let Some(v) = v.filter(|v| !v.trim().is_empty()) {
                m.insert(k.into(), Value::String(v));
            }
        };
        put(&mut m, "projectId", Some(project.into()));
        if let Some(t) = ticket {
            for (k, src) in [("title", "title"), ("status", "status"), ("role", "role"), ("branch", "branch"), ("base", "base"), ("workerSession", "sessionId")] {
                put(&mut m, k, text_of(t, src));
            }
            if let Some(b) = text_of(t, "branch") {
                put(&mut m, "tipSha", (self.cfg.tip)(project, &b));
            }
        }
        if let HookOn::Lane { exit, .. } = &hook.on {
            put(&mut m, "lane", text_of(&e.payload, if *exit { "from" } else { "to" }));
            put(&mut m, "from", text_of(&e.payload, "from"));
            put(&mut m, "to", text_of(&e.payload, "to"));
        }
        m.insert("event".into(), json!({"name": e.name, "id": e.id, "causeId": e.cause_id, "depth": depth, "source": e.source}));
        m.insert("hook".into(), json!(hook.id));
        m
    }
}

/// Best effort, no process: the tip of local branch `branch` read from `<project dir>/.git` (loose ref, then packed-refs).
pub fn git_tip(project_dir: &std::path::Path, branch: &str) -> Option<String> {
    if branch.contains("..") || branch.starts_with('/') {
        return None;
    }
    let git = project_dir.join(".git");
    if !git.is_dir() {
        return None;
    }
    if let Ok(t) = std::fs::read_to_string(git.join("refs/heads").join(branch)) {
        let t = t.trim();
        if t.len() >= 40 {
            return Some(t.to_string());
        }
    }
    let packed = std::fs::read_to_string(git.join("packed-refs")).ok()?;
    let suffix = format!(" refs/heads/{branch}");
    packed.lines().find(|l| l.ends_with(&suffix)).and_then(|l| l.split(' ').next()).map(str::to_string)
}
