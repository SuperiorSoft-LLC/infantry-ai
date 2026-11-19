// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The scripting tools the daemon registers: `infantry_script_run`, `_api`, `_list`, `_save`, `_delete`,
//! `_hook`, `_stats`, plus `infantry_exec` / `infantry_exec_status`. No role check anywhere: the caller's
//! identity is carried, never tested (except the `scripting.global.writeRoles` and `scripting.exec.roles` settings).
use crate::dispatcher::{RunOptions, ScriptDispatcher};
use crate::engine::{ScriptCaller, ScriptEngines, ScriptLimits};
use crate::exec::{ExecTools, EXEC, STATUS};
use crate::hooks::{Hook, HookOn, HookStore, EXEC_TICK, INCOMING_EVENT};
use crate::host::{ScriptHost, ToolInfo, ToolInvoker, ToolResult};
use crate::runlog::{ScriptRunLog, STATS};
use crate::store::{ScriptStore, GLOBAL, PROJECT};
use serde_json::{json, Map, Value};
use std::collections::BTreeMap;
use std::sync::Arc;

pub const RUN: &str = "infantry_script_run";
pub const API: &str = "infantry_script_api";
pub const LIST: &str = "infantry_script_list";
pub const SAVE: &str = "infantry_script_save";
pub const DELETE: &str = "infantry_script_delete";
pub const HOOK: &str = "infantry_script_hook";

/// Every tool name this crate answers.
pub const NAMES: &[&str] = &[RUN, API, LIST, SAVE, DELETE, HOOK, STATS, EXEC, STATUS, crate::chat::NOTE, crate::chat::IMAGE];

/// The self-describing API, generated from the live registry at each call. With `tool`: that tool in full.
pub fn describe(host: &dyn ScriptHost, aliases: &BTreeMap<String, String>, bindings: &[(String, String)], tool: Option<&str>) -> String {
    let mut catalog = host.catalog();
    catalog.sort_by(|a, b| a.name.cmp(&b.name));
    if let Some(tool) = tool {
        let resolved = if catalog.iter().any(|t| t.name == tool) { Some(tool.to_string()) } else { aliases.get(tool).cloned() };
        let Some(t) = resolved.and_then(|r| catalog.iter().find(|t| t.name == r)) else { return format!("unknown tool or alias '{tool}'") };
        let mut via: Vec<&String> = aliases.iter().filter(|(_, v)| **v == t.name).map(|(k, _)| k).collect();
        via.sort();
        let mut s = format!("{}{}\n", t.name, if t.read_only { "  [read-only]" } else { "" });
        if !via.is_empty() {
            s.push_str(&format!("aliases: {}\n", via.iter().map(|v| v.as_str()).collect::<Vec<_>>().join(", ")));
        }
        s.push_str(&format!("{}\n\nargs (JSON Schema):\n{}", t.description, t.input_schema));
        return s;
    }
    let mut s = format!("SCRIPT API: {} tools (live registry). A script queues actions; the daemon runs them in order as the caller.\n", catalog.len());
    s.push_str("Call any tool by name (ih.call(name, args)) or by alias. To read results, queue ih.again(): the script re-runs with input.results.\n\n");
    s.push_str("ALIASES (data, setting scripting.aliases):\n");
    if aliases.is_empty() {
        s.push_str("  (none)\n");
    }
    for (a, t) in aliases {
        s.push_str(&format!("  {a} -> {t}\n"));
    }
    for (id, text) in bindings {
        if !text.trim().is_empty() {
            s.push_str(&format!("\nENGINE {id}:\n{}\n", text.trim().lines().map(|l| format!("  {l}")).collect::<Vec<_>>().join("\n")));
        }
    }
    s.push_str("\nTOOLS (--tool <name> for one in full):\n");
    for t in &catalog {
        s.push_str(&format!("  {}\n", line(t)));
    }
    s.trim_end().to_string()
}

fn line(t: &ToolInfo) -> String {
    let required: Vec<&str> = t.input_schema.get("required").and_then(Value::as_array).map(|a| a.iter().filter_map(Value::as_str).collect()).unwrap_or_default();
    let args = t
        .input_schema
        .get("properties")
        .and_then(Value::as_object)
        .map(|p| {
            p.iter()
                .map(|(k, v)| format!("{k}{}:{}", if required.contains(&k.as_str()) { "*" } else { "" }, v.get("type").and_then(Value::as_str).unwrap_or("any")))
                .collect::<Vec<_>>()
                .join(", ")
        })
        .unwrap_or_default();
    let first = t.description.trim().lines().next().unwrap_or("");
    let first = if first.chars().count() > 90 { format!("{}...", first.chars().take(87).collect::<String>()) } else { first.to_string() };
    format!("{}({args}) - {first}", t.name)
}

/// The tool surface. The daemon routes a call whose name is in [`NAMES`] to [`ScriptingTools::handle`].
pub struct ScriptingTools {
    pub engines: ScriptEngines,
    pub store: ScriptStore,
    pub hooks: Arc<HookStore>,
    pub dispatcher: ScriptDispatcher,
    pub run_log: Arc<ScriptRunLog>,
    pub exec: Arc<ExecTools>,
    pub limits: Arc<dyn Fn() -> ScriptLimits + Send + Sync>,
    pub config: Arc<dyn Fn(&str) -> Option<String> + Send + Sync>,
    pub chat: crate::chat::ChatTools,
}

fn arg<'a>(a: &'a Map<String, Value>, k: &str) -> Option<&'a str> {
    a.get(k).and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty())
}

fn refuse(tool: &str, why: &str) -> ToolResult {
    ToolResult::err(format!("{tool} refused: {why}"))
}

/// A JSON object given inline or as a JSON string.
fn object_arg(v: Option<&Value>) -> Result<Map<String, Value>, String> {
    match v {
        None | Some(Value::Null) => Ok(Map::new()),
        Some(Value::Object(o)) => Ok(o.clone()),
        Some(Value::String(s)) if s.trim().is_empty() => Ok(Map::new()),
        Some(Value::String(s)) => match serde_json::from_str::<Value>(s) {
            Ok(Value::Object(o)) => Ok(o),
            Ok(_) => Err("not an object".into()),
            Err(e) => Err(e.to_string().chars().take(120).collect()),
        },
        Some(_) => Err("not an object".into()),
    }
}

impl ScriptingTools {
    pub fn catalog(&self) -> Vec<ToolInfo> {
        let schema = |s: &str| serde_json::from_str::<Value>(s).unwrap_or(json!({"type": "object"}));
        let t = |name: &str, desc: &str, sch: &str, ro: bool| ToolInfo { name: name.into(), description: desc.into(), input_schema: schema(sch), read_only: ro };
        let mut tools = crate::chat::ChatTools::catalog();
        tools.extend(vec![
            t(RUN, "Run a named script of this project as you: the daemon runs the script and the tool calls it queues, no model, no tokens. Output: the actions taken (or planned with dryRun) with each result, and the script's log.",
              r#"{"type":"object","properties":{"name":{"type":"string"},"args":{"type":"string","description":"JSON object given to the script as input.args"},"dryRun":{"type":"boolean"},"project":{"type":"string"}},"required":["name"]}"#, false),
            t(API, "Everything a script can call: every tool in the live registry, the alias table and how the script language binds them.", r#"{"type":"object","properties":{"tool":{"type":"string"}}}"#, true),
            t(LIST, "List the scripts this project can run (its own, the global arsenal, plugin-shipped).", r#"{"type":"object","properties":{"project":{"type":"string"},"scope":{"type":"string"}}}"#, true),
            t(SAVE, "Save a script (project, or scope=global for the Script Arsenal). The source is parse-checked first.",
              r#"{"type":"object","properties":{"name":{"type":"string"},"source":{"type":"string"},"engine":{"type":"string"},"scope":{"type":"string","enum":["project","global"]},"project":{"type":"string"}},"required":["name","source"]}"#, false),
            t(DELETE, "Delete a script (project, or scope=global).", r#"{"type":"object","properties":{"name":{"type":"string"},"scope":{"type":"string"},"project":{"type":"string"}},"required":["name"]}"#, false),
            t(HOOK, "List, add or remove the project's script hooks (data in .infantry/scripts/hooks.json): on a named event, 'lane enter <lane>', 'lane exit <lane>' or 'every <N>s'.",
              r#"{"type":"object","properties":{"action":{"type":"string"},"on":{"type":"string"},"script":{"type":"string"},"args":{"type":"string"},"id":{"type":"string"},"to":{"type":"string"},"disabled":{"type":"boolean"},"global":{"type":"boolean"},"scope":{"type":"string"},"project":{"type":"string"}},"required":["action"]}"#, false),
            t(STATS, "Script automation, read-only: turns saved, wakes by reason, and the latest script runs.", r#"{"type":"object","properties":{"session":{"type":"string"},"project":{"type":"string"},"limit":{"type":"integer"}}}"#, true),
            t(EXEC, "Run a command line as you and get {exitCode, durationMs, stdoutTail, stderrTail, timedOut}. Off by default (scripting.exec.enabled).",
              r#"{"type":"object","properties":{"command":{"description":"argv array, or one command-line string"},"cwd":{"type":"string"},"env":{"type":"object"},"timeoutSec":{"type":"integer"},"stdin":{"type":"string"},"background":{"type":"boolean"},"script":{"type":"string"},"project":{"type":"string"},"serial":{"type":"string"}},"required":["command"]}"#, false),
            t(STATUS, "State of a background infantry_exec run; no id lists the jobs.", r#"{"type":"object","properties":{"id":{"type":"string"},"state":{"type":"object"}}}"#, true),
        ]);
        tools
    }

    /// Answers one call, or `None` when the name is not a scripting tool.
    pub fn handle(&self, name: &str, a: &Map<String, Value>, inv: &ToolInvoker) -> Option<ToolResult> {
        if let Some(r) = self.chat.handle(name, a, inv) {
            return Some(r);
        }
        Some(match name {
            RUN => self.run(a, inv),
            API => {
                let tool = arg(a, "tool");
                let bindings: Vec<(String, String)> = self.engines.all().iter().map(|e| (e.id().to_string(), e.binding().to_string())).collect();
                let text = describe(self.dispatcher.host().as_ref(), &self.dispatcher.aliases(), &bindings, tool);
                ToolResult { is_error: tool.is_some() && text.starts_with("unknown tool"), text }
            }
            LIST => self.list(a, inv),
            SAVE => self.save(a, inv),
            DELETE => self.delete(a, inv),
            HOOK => self.hook(a, inv),
            STATS => {
                let Some(project) = arg(a, "project").map(str::to_string).or_else(|| inv.project_id.clone()) else {
                    return Some(ToolResult::err(format!("{STATS} needs a project (pass project, or run it from a project session; infantry_projects lists the project ids)")));
                };
                let n = a.get("limit").and_then(|v| v.as_u64().or_else(|| v.as_str()?.parse().ok())).unwrap_or(20) as usize;
                ToolResult::ok(self.run_log.stats_answer(&project, arg(a, "session"), n).to_string())
            }
            EXEC => self.exec.exec(a, inv),
            STATUS => self.exec.status(a),
            _ => return None,
        })
    }

    fn project(&self, tool: &str, a: &Map<String, Value>, inv: &ToolInvoker) -> Result<String, ToolResult> {
        arg(a, "project")
            .map(str::to_string)
            .or_else(|| inv.project_id.clone())
            .ok_or_else(|| ToolResult::err(format!("{tool} needs a project (pass project, or run it from a project session; infantry_projects lists the project ids)")))
    }

    fn run(&self, a: &Map<String, Value>, inv: &ToolInvoker) -> ToolResult {
        let name = arg(a, "name").unwrap_or_default().to_string();
        let project = match self.project(RUN, a, inv) {
            Ok(p) => p,
            Err(r) => return r,
        };
        let dry = a.get("dryRun").and_then(Value::as_bool) == Some(true);
        let args = match object_arg(a.get("args")) {
            Ok(m) => m,
            Err(e) => return ToolResult::err(format!("{RUN}: args must be a JSON object ({e})")),
        };
        let script = match self.store.read(&project, &name) {
            Err(e) => return refuse(RUN, &e),
            Ok(None) => {
                let have = self.store.names(&project).join(", ");
                let exts = self.engines.extensions().iter().map(|e| format!(".{e}")).collect::<Vec<_>>().join("/");
                return ToolResult::err(format!("{RUN}: no script '{name}' in project {project} (looked in .infantry/scripts/ for {exts}); have: {}", if have.is_empty() { "none" } else { &have }));
            }
            Ok(Some(s)) => s,
        };
        let Some(engine) = self.engines.by_extension(&script.extension) else { return ToolResult::err(format!("{RUN}: no engine loaded for .{}", script.extension)) };
        let who = ScriptCaller::new(inv.session_id.clone().unwrap_or_default(), inv.role.clone().unwrap_or_default());
        let mut invoker = inv.clone();
        invoker.project_id = Some(project.clone());
        invoker.label = format!("script:{name}");
        let report = self.dispatcher.run(engine.as_ref(), &script.source, &args, (self.limits)(), &who, &invoker, RunOptions { dry_run: dry, progress_to: None }, &mut |_| {});
        if !dry {
            self.run_log.record(&project, &name, "manual", inv.session_id.as_deref(), Some(&report), if report.ok() { "ok" } else { "failed" }, &format!("ran {} action(s)", report.records.len()), report.error.as_deref(), None, false);
        }
        ToolResult { text: report.render(&name), is_error: !report.ok() }
    }

    fn list(&self, a: &Map<String, Value>, inv: &ToolInvoker) -> ToolResult {
        let exts = self.engines.extensions();
        let mut lines = Vec::new();
        if let Ok(p) = self.project(LIST, a, inv) {
            for e in self.store.entries(&p) {
                lines.push(format!("{}.{}  [project]  {} bytes{}", e.name, e.extension, e.size, e.saved_by.map(|b| format!("  saved by {b}")).unwrap_or_default()));
            }
        }
        if let Some(g) = &self.store.global {
            for e in g.entries(&exts) {
                lines.push(format!("{}.{}  [global]  {} bytes{}", e.name, e.extension, e.size, e.saved_by.map(|b| format!("  saved by {b}")).unwrap_or_default()));
            }
        }
        if let Some(p) = &self.store.plugins {
            for n in p.names() {
                lines.push(format!("{n}  [plugin]"));
            }
        }
        ToolResult::ok(if lines.is_empty() { "no scripts".to_string() } else { lines.join("\n") })
    }

    /// The refusal when the caller may not write the global scope, else `None`. The owner always may.
    pub fn global_write_refusal(&self, inv: &ToolInvoker) -> Option<String> {
        if self.store.global.is_none() {
            return Some("this daemon has no global script scope".into());
        }
        if inv.owner {
            return None;
        }
        let roles: Vec<String> = (self.config)("scripting.global.writeRoles").unwrap_or_default().split(',').map(|r| r.trim().to_lowercase()).filter(|r| !r.is_empty()).collect();
        let role = inv.role.clone().unwrap_or_default().to_lowercase();
        if roles.is_empty() || roles.contains(&role) {
            None
        } else {
            Some(format!("writing the global scope is limited to roles {} (setting scripting.global.writeRoles); save a project copy instead", roles.join(", ")))
        }
    }

    fn save(&self, a: &Map<String, Value>, inv: &ToolInvoker) -> ToolResult {
        let scope = arg(a, "scope").map(str::to_lowercase).unwrap_or_else(|| PROJECT.into());
        let Some(name) = arg(a, "name") else { return refuse(SAVE, "needs name") };
        let source = a.get("source").and_then(Value::as_str).unwrap_or_default();
        let engine = match arg(a, "engine") {
            Some(id) => self.engines.all().into_iter().find(|e| e.id() == id),
            None => self.engines.default_engine(),
        };
        let Some(engine) = engine else { return refuse(SAVE, "no such engine") };
        if let Err(e) = crate::store::check_name(name) {
            return refuse(SAVE, &e);
        }
        if let Some(err) = engine.check(source) {
            return refuse(SAVE, &format!("does not parse: {err}"));
        }
        let ext = engine.extensions().first().copied().unwrap_or("lua");
        let by = inv.session_id.clone().or_else(|| inv.role.clone()).unwrap_or_else(|| if inv.owner { "owner".into() } else { "unknown".into() });
        let r = match scope.as_str() {
            PROJECT => match self.project(SAVE, a, inv) {
                Ok(p) => self.store.write(&p, name, ext, source, &by),
                Err(r) => return r,
            },
            GLOBAL => {
                if let Some(why) = self.global_write_refusal(inv) {
                    return refuse(SAVE, &why);
                }
                self.store.global.as_ref().unwrap().write(name, ext, source, &by, &self.engines.extensions()).map_err(|e| e.to_string())
            }
            _ => return refuse(SAVE, "scope must be project or global"),
        };
        match r {
            Ok(replaced) => ToolResult::ok(format!("{} {scope} script {name}.{ext}", if replaced { "replaced" } else { "saved" })),
            Err(e) => refuse(SAVE, &e),
        }
    }

    fn delete(&self, a: &Map<String, Value>, inv: &ToolInvoker) -> ToolResult {
        let scope = arg(a, "scope").map(str::to_lowercase).unwrap_or_else(|| PROJECT.into());
        let Some(name) = arg(a, "name") else { return refuse(DELETE, "needs name") };
        if let Err(e) = crate::store::check_name(name) {
            return refuse(DELETE, &e);
        }
        let gone = match scope.as_str() {
            PROJECT => match self.project(DELETE, a, inv) {
                Ok(p) => self.store.delete(&p, name).unwrap_or(false),
                Err(r) => return r,
            },
            GLOBAL => {
                if let Some(why) = self.global_write_refusal(inv) {
                    return refuse(DELETE, &why);
                }
                self.store.global.as_ref().unwrap().delete(name, &self.engines.extensions())
            }
            _ => return refuse(DELETE, "scope must be project or global"),
        };
        if gone {
            ToolResult::ok(format!("deleted {scope} script {name}"))
        } else {
            refuse(DELETE, &format!("no {scope} script '{name}'"))
        }
    }

    fn min_every_sec(&self) -> u64 {
        (self.config)("scripting.hooks.minEverySec").and_then(|v| v.trim().parse().ok()).unwrap_or(10)
    }

    fn hook(&self, a: &Map<String, Value>, inv: &ToolInvoker) -> ToolResult {
        let r = |why: &str| refuse(HOOK, why);
        let project = match self.project(HOOK, a, inv) {
            Ok(p) => p,
            Err(r) => return r,
        };
        let action = arg(a, "action").map(str::to_lowercase).unwrap_or_default();
        let disabled = a.get("disabled").and_then(Value::as_bool) == Some(true);
        let by_owner = inv.owner && inv.session_id.is_none();
        if arg(a, "scope").map(str::to_lowercase).as_deref() == Some("global") && (action == "add" || action == "remove") {
            if let Some(why) = self.global_write_refusal(inv) {
                return r(&why);
            }
            if action == "remove" {
                let Some(id) = arg(a, "id") else { return r("remove needs id") };
                return match self.hooks.remove_arsenal(id) {
                    Ok(true) => ToolResult::ok(format!("removed global hook {id}")),
                    Ok(false) => r(&format!("no global hook '{id}'")),
                    Err(e) => r(&e),
                };
            }
            let on = match HookOn::parse(arg(a, "on").unwrap_or_default()) {
                Ok(o) => o,
                Err(e) => return r(&e),
            };
            let Some(script) = arg(a, "script") else { return r("add needs script") };
            if self.store.global.as_ref().and_then(|g| g.read(script, &self.engines.extensions())).is_none() {
                return r(&format!("no global script '{script}' (a global hook names a script of the Script Arsenal; save it with scope=global)"));
            }
            if let HookOn::Every(n) = on {
                if n < self.min_every_sec() {
                    return r(&format!("every {n}s is below the minimum {}s (setting scripting.hooks.minEverySec)", self.min_every_sec()));
                }
            }
            if on == HookOn::Named(INCOMING_EVENT.into()) {
                return r("a mail.incoming hook attaches to one session; it cannot be global");
            }
            let args = match object_arg(a.get("args")) {
                Ok(m) => m,
                Err(_) => return r("args must be a JSON object"),
            };
            let id = arg(a, "id").map(str::to_string).unwrap_or_else(|| default_id(script, &on));
            if !crate::hooks::valid_hook_id(&id) {
                return r(&format!("bad hook id '{id}'"));
            }
            let h = Hook { args, enabled: !disabled, owner_session: inv.session_id.clone(), owner_role: inv.role.clone(), owner_is_owner: by_owner, ..Hook::new(&id, on.clone(), script) };
            return match self.hooks.add_arsenal(h) {
                Ok(()) => ToolResult::ok(format!("added global hook {id}: on {on} -> {script} (runs for every project){}", if disabled { " [disabled]" } else { "" })),
                Err(e) => r(&e),
            };
        }
        match action.as_str() {
            "list" => {
                let hooks = self.hooks.list(&project);
                let mut text = if hooks.is_empty() {
                    format!("no hooks in project {project}")
                } else {
                    format!("{} hook(s) in project {project}:\n{}", hooks.len(), hooks.iter().map(|h| format!("  {}", h.line())).collect::<Vec<_>>().join("\n"))
                };
                if let Some(e) = self.hooks.error(&project) {
                    text.push_str(&format!("\nWARNING: {} does not parse, so NO hook is active: {e}", crate::hooks::HOOKS_PATH));
                }
                ToolResult::ok(text)
            }
            "remove" => {
                let Some(id) = arg(a, "id") else { return r("remove needs id") };
                match self.hooks.remove(&project, id) {
                    Ok(true) => ToolResult::ok(format!("removed hook {id}")),
                    Ok(false) => r(&format!("no hook '{id}' in project {project}")),
                    Err(e) => r(&e),
                }
            }
            "add" => {
                let Some(on_text) = arg(a, "on") else { return r("add needs on (e.g. 'lane enter done' or 'ticket.commented')") };
                let on = match HookOn::parse(on_text) {
                    Ok(o) => o,
                    Err(e) => return r(&e),
                };
                let Some(script) = arg(a, "script") else { return r("add needs script") };
                match self.store.read(&project, script) {
                    Ok(Some(_)) => {}
                    Err(e) => return r(&e),
                    Ok(None) => {
                        let have = self.store.names(&project).join(", ");
                        return r(&format!("no script '{script}' in project {project} (looked in .infantry/scripts/); have: {}", if have.is_empty() { "none" } else { &have }));
                    }
                }
                let args = match object_arg(a.get("args")) {
                    Ok(m) => m,
                    Err(_) => return r("args must be a JSON object"),
                };
                let is_mail = on == HookOn::Named(INCOMING_EVENT.into());
                let is_timer = matches!(on, HookOn::Every(_)) || on == HookOn::Named(EXEC_TICK.into());
                if let HookOn::Every(n) = on {
                    if n < self.min_every_sec() {
                        return r(&format!("every {n}s is below the minimum {}s (setting scripting.hooks.minEverySec)", self.min_every_sec()));
                    }
                }
                let to = arg(a, "to").map(str::to_string);
                if !is_mail && !is_timer && to.is_some() {
                    return r("'to' only applies to on=mail.incoming, every <N>s and exec.tick");
                }
                let target = if is_mail {
                    match to.or_else(|| inv.session_id.clone()) {
                        Some(t) => Some(t),
                        None => return r("a mail.incoming hook attaches to ONE session: pass to=<session id> (no role-wide or project-wide mail hooks)"),
                    }
                } else if is_timer {
                    to
                } else {
                    None
                };
                if is_mail {
                    if let Some(h) = self.hooks.list(&project).into_iter().find(|h| h.on == on && h.to == target && h.script == script) {
                        return r(&format!("session {} already has script '{script}' on mail.incoming (hook {}); one hook per (session, script)", target.unwrap_or_default(), h.id));
                    }
                }
                let id = arg(a, "id").map(str::to_string).unwrap_or_else(|| {
                    let d = default_id(script, &on);
                    match &target {
                        Some(t) => format!("{d}-{}", t.chars().take(12).collect::<String>()).chars().take(64).collect::<String>().trim_matches(['-', '.', '_']).to_string(),
                        None => d,
                    }
                });
                if !crate::hooks::valid_hook_id(&id) {
                    return r(&format!("bad hook id '{id}' (letters, digits, '_', '-', '.'; at most 64)"));
                }
                let global = a.get("global").and_then(Value::as_bool) == Some(true);
                let h = Hook {
                    args,
                    enabled: !disabled,
                    owner_session: inv.session_id.clone(),
                    owner_role: inv.role.clone(),
                    owner_is_owner: by_owner,
                    to: target,
                    global,
                    ..Hook::new(&id, on.clone(), script)
                };
                match self.hooks.add(&project, h) {
                    Ok(()) => ToolResult::ok(format!("added hook {id}: on {on} -> {script}{}{}", if global { " [global]" } else { "" }, if disabled { " [disabled]" } else { "" })),
                    Err(e) => r(&e),
                }
            }
            other => r(&format!("action must be list, add or remove (got '{other}')")),
        }
    }
}

fn default_id(script: &str, on: &HookOn) -> String {
    let re = regex::Regex::new("[^A-Za-z0-9]+").unwrap();
    let tail = re.replace_all(&on.to_string(), "-").trim_matches('-').to_string();
    format!("{script}-{tail}").chars().take(64).collect::<String>().trim_matches(['-', '.', '_']).to_string()
}
