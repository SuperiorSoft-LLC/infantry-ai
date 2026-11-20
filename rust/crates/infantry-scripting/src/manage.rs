// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! What the Scripts view and the script tools share: the library rows joined with their run stats and the hooks that
//! use them, the parse-checked save, and hook add/remove by script id. No fixed workflow lives here: it is mechanism.
use crate::engine::ScriptEngines;
use crate::hooks::{valid_hook_id, Hook, HookOn, HookStore, EXEC_TICK, INCOMING_EVENT};
use crate::library::{ManagedScript, SaveInput, ScriptLibrary, ScriptStats};
use crate::store::PluginScripts;
use serde_json::{json, Map, Value};
use std::sync::Arc;

pub struct ScriptManager {
    pub library: Arc<ScriptLibrary>,
    pub hooks: Arc<HookStore>,
    pub engines: ScriptEngines,
    pub plugins: Option<Arc<dyn PluginScripts>>,
    /// `scripting.hooks.minEverySec`.
    pub min_every_sec: Arc<dyn Fn() -> u64 + Send + Sync>,
}

/// `"a, b ,c"` -> `["a","b","c"]` (lower case, no empties, no duplicates).
pub fn parse_tags(text: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for t in text.split(',').map(|t| t.trim().to_lowercase()).filter(|t| !t.is_empty()) {
        if !out.contains(&t) {
            out.push(t);
        }
    }
    out
}

fn status(s: &ManagedScript, st: &ScriptStats) -> &'static str {
    if !s.enabled {
        "disabled"
    } else if st.last_ok == Some(false) {
        "failing"
    } else if st.runs == 0 {
        "never-run"
    } else {
        "ok"
    }
}

impl ScriptManager {
    fn hook_refs(&self, hooks: &[Hook], s: &ManagedScript) -> Vec<Value> {
        hooks
            .iter()
            .filter(|h| h.script == s.name || h.script == format!("{}.{}", s.name, s.ext))
            .map(|h| json!({"id": h.id, "on": h.on.to_string(), "enabled": h.enabled, "args": h.args, "to": h.to, "everyProject": h.arsenal, "scope": if h.arsenal { "global" } else { "project" }}))
            .collect()
    }

    pub fn row(&self, s: &ManagedScript, hooks: &[Hook]) -> Value {
        let st = self.library.stats_for(s).unwrap_or_default();
        let mut o = s.to_json(false);
        o["stats"] = st.to_json();
        o["status"] = json!(status(s, &st));
        o["hooks"] = Value::Array(self.hook_refs(hooks, s));
        o
    }

    /// Every script a project sees (its own, the global ones, the plugin-shipped), as list rows.
    pub fn overview(&self, project: &str) -> Vec<Value> {
        let hooks = self.hooks.list(project);
        let mut rows: Vec<Value> = self.library.list(Some(project)).unwrap_or_default().iter().map(|s| self.row(s, &hooks)).collect();
        if let Some(p) = &self.plugins {
            for n in p.names() {
                if rows.iter().any(|r| r["name"] == json!(n)) {
                    continue;
                }
                let mut r = json!({"id": format!("plugin:{n}"), "scope": "plugin", "projectId": "", "name": n, "ext": "lua", "title": crate::library::title_from_name(&n),
                    "description": "Shipped by a plugin (read-only).", "tags": ["plugin"], "author": "plugin", "createdAt": 0, "updatedAt": 0, "enabled": true, "version": 1,
                    "flag": null, "size": 0, "stats": ScriptStats::default().to_json(), "status": "never-run"});
                r["hooks"] = Value::Array(hooks.iter().filter(|h| h.script == n).map(|h| json!({"id": h.id, "on": h.on.to_string(), "enabled": h.enabled, "scope": "plugin"})).collect());
                rows.push(r);
            }
        }
        rows
    }

    /// One script in full: the row, the body, the version list.
    pub fn detail(&self, id: &str, project: &str) -> Result<Value, String> {
        let s = self.library.get(id)?.ok_or_else(|| format!("no script {id}"))?;
        let hooks = self.hooks.list(project);
        let mut o = self.row(&s, &hooks);
        o["body"] = json!(s.body);
        o["versions"] = Value::Array(self.library.versions(id)?);
        Ok(o)
    }

    #[allow(clippy::too_many_arguments)]
    pub fn save(&self, scope: &str, project: &str, name: &str, engine: Option<&str>, title: &str, description: &str, body: &str, tags: Option<Vec<String>>, author: &str) -> Result<(ManagedScript, bool), String> {
        let engine = match engine {
            Some(id) => self.engines.all().into_iter().find(|e| e.id() == id),
            None => self.engines.default_engine(),
        }
        .ok_or("no such engine")?;
        crate::store::check_name(name)?;
        if title.trim().is_empty() {
            return Err("a title is required (a short human name for the script)".into());
        }
        if description.trim().is_empty() {
            return Err("a description is required (what the script does and when to use it)".into());
        }
        if let Some(err) = engine.check(body) {
            return Err(format!("does not parse: {err}"));
        }
        let ext = engine.extensions().first().copied().unwrap_or("lua").to_string();
        self.library.save(SaveInput { scope: scope.into(), project_id: project.into(), name: name.into(), ext, title: title.into(), description: description.into(), body: body.into(), tags, author: author.into() })
    }

    pub fn delete(&self, id: &str) -> Result<usize, String> {
        let (gone, hooks) = self.library.delete(id)?;
        if gone { Ok(hooks) } else { Err(format!("no script {id}")) }
    }

    /// Adds a hook that runs script `script_id` on `on` ("lane enter done", "ticket.commented", "every 60s").
    pub fn hook_add(&self, project: &str, script_id: &str, on: &str, args: Map<String, Value>, id: Option<&str>, arsenal: bool, author: &str) -> Result<String, String> {
        let s = self.library.get(script_id)?.ok_or_else(|| format!("no script {script_id}"))?;
        let on = HookOn::parse(on)?;
        if on == HookOn::Named(INCOMING_EVENT.into()) {
            return Err("a mail.incoming hook attaches to one session; add it with infantry_script_hook".into());
        }
        if let HookOn::Every(n) = on {
            if n < (self.min_every_sec)() {
                return Err(format!("every {n}s is below the minimum {}s (setting scripting.hooks.minEverySec)", (self.min_every_sec)()));
            }
        }
        if on == HookOn::Named(EXEC_TICK.into()) {
            return Err("exec.tick hooks are added with infantry_script_hook".into());
        }
        if arsenal && s.scope != "global" {
            return Err("an every-project hook names a global script".into());
        }
        let default = {
            let tail: String = on.to_string().chars().map(|c| if c.is_ascii_alphanumeric() { c } else { '-' }).collect();
            format!("{}-{}", s.name, tail.trim_matches('-')).chars().take(64).collect::<String>().trim_matches(['-', '.', '_']).to_string()
        };
        let hid = id.map(str::to_string).unwrap_or(default);
        if !valid_hook_id(&hid) {
            return Err(format!("bad hook id '{hid}'"));
        }
        let h = Hook { args, owner_session: Some(author.to_string()), owner_is_owner: author == "owner", ..Hook::new(&hid, on, &s.name) };
        if arsenal {
            self.hooks.add_arsenal(h)?;
        } else {
            self.hooks.add(project, h)?;
        }
        Ok(hid)
    }

    pub fn hook_remove(&self, project: &str, id: &str, arsenal: bool) -> Result<(), String> {
        let gone = if arsenal { self.hooks.remove_arsenal(id)? } else { self.hooks.remove(project, id)? };
        if gone { Ok(()) } else { Err(format!("no hook '{id}'")) }
    }

    pub fn hook_set_enabled(&self, project: &str, id: &str, arsenal: bool, on: bool) -> Result<(), String> {
        let list = if arsenal { self.hooks.arsenal_hooks() } else { self.hooks.list_own(project) };
        let mut h = list.into_iter().find(|h| h.id == id).ok_or_else(|| format!("no hook '{id}'"))?;
        h.enabled = on;
        h.arsenal = false;
        if arsenal {
            self.hooks.remove_arsenal(id)?;
            self.hooks.add_arsenal(h)
        } else {
            self.hooks.remove(project, id)?;
            self.hooks.add(project, h)
        }
    }

    /// The per-script stats list the `infantry_script_stats` tool appends.
    pub fn stats_list(&self, project: &str) -> Value {
        Value::Array(
            self.overview(project)
                .into_iter()
                .map(|r| json!({"id": r["id"], "name": r["name"], "title": r["title"], "scope": r["scope"], "status": r["status"], "stats": r["stats"], "hooks": r["hooks"].as_array().map(|a| a.iter().map(|h| json!(format!("{} on {}", h["id"].as_str().unwrap_or(""), h["on"].as_str().unwrap_or("")))).collect::<Vec<_>>()).unwrap_or_default()}))
                .collect(),
        )
    }
}
