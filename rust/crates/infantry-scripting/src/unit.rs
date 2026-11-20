// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The Scripts view's frames. Reads are open to every client; writes, runs and file transfers are the owner's.
//! A write answers the requester with the stored row and tells every client `scripts_changed` so open views refresh.
//!
//! * `scripts_list {projectId}` -> `scripts {projectId, scripts:[row]}` (rows carry stats, status and the hooks that use them)
//! * `scripts_get {id, projectId}` -> `scripts_item` (the row + body + versions)
//! * `scripts_save {id?|scope,projectId,name, title, description, body, tags[]}` -> `scripts_item`
//! * `scripts_delete {id}`, `scripts_enable {id, enabled}`, `scripts_restore {id, version}`
//! * `scripts_run {projectId, name, args, dryRun}` -> `scripts_result {ok, text}`
//! * `scripts_hook {op: add|remove|enable, projectId, scriptId, on, hookId, everyProject, enabled}` -> `scripts_result`
//! * `scripts_files {action: export|import, projectId, scope, dir?}` -> `scripts_result`
use crate::host::{ScriptHost, ToolInvoker};
use crate::manage::{parse_tags, ScriptManager};
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use serde_json::{json, Map, Value};
use std::sync::Arc;

pub const LIST: &str = "scripts_list";
pub const GET: &str = "scripts_get";
pub const SAVE: &str = "scripts_save";
pub const DELETE: &str = "scripts_delete";
pub const ENABLE: &str = "scripts_enable";
pub const RESTORE: &str = "scripts_restore";
pub const RUN: &str = "scripts_run";
pub const HOOK: &str = "scripts_hook";
pub const FILES: &str = "scripts_files";
pub const SCRIPTS: &str = "scripts";
pub const ITEM: &str = "scripts_item";
pub const RESULT: &str = "scripts_result";
pub const CHANGED: &str = "scripts_changed";

pub struct ScriptsUnit {
    pub manager: Arc<ScriptManager>,
    pub host: Arc<dyn ScriptHost>,
}

fn s(d: &Value, k: &str) -> Option<String> {
    d.get(k).and_then(Value::as_str).map(str::trim).filter(|v| !v.is_empty()).map(str::to_string)
}

fn answer(env: &Envelope, ty: &str, v: Value) -> Envelope {
    let mut out = Envelope::new(ty, v.to_string());
    out.session_id = env.session_id.clone();
    out.request_id = env.request_id.clone();
    out
}

impl FrameUnit for ScriptsUnit {
    fn name(&self) -> &'static str {
        "scripts"
    }

    fn frames(&self) -> &'static [&'static str] {
        &[LIST, GET, SAVE, DELETE, ENABLE, RESTORE, RUN, HOOK, FILES]
    }

    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let ty = env.r#type.as_str();
        let d: Value = serde_json::from_str(env.data.trim()).unwrap_or(Value::Null);
        let refuse = |m: String| Refusal::new(ty, m);
        let project = s(&d, "projectId").unwrap_or_default();
        let writes = !matches!(ty, LIST | GET);
        if writes && !ctx.owner {
            return Err(refuse("only the owner manages scripts from the app (agents use the infantry_script_* tools)".into()));
        }
        let m = &self.manager;
        let changed = |project: &str| ctx.bus.publish(Envelope::new(CHANGED, json!({"projectId": project}).to_string()));
        let detail = |id: &str, project: &str| m.detail(id, project).map_err(|e| refuse(e));
        let result = |ok: bool, text: String| answer(env, RESULT, json!({"ok": ok, "text": text, "request": ty}));
        let call = |tool: &str, args: Map<String, Value>| {
            let inv = ToolInvoker { owner: true, project_id: Some(project.clone()).filter(|p| !p.is_empty()), label: "owner:scripts-view".into(), ..Default::default() };
            self.host.invoke(tool, &args, &inv)
        };
        let id = s(&d, "id");
        let need_id = || id.clone().ok_or_else(|| refuse("needs id".into()));
        match ty {
            LIST => Ok(vec![answer(env, SCRIPTS, json!({"projectId": project, "scripts": m.overview(&project)}))]),
            GET => Ok(vec![answer(env, ITEM, detail(&need_id()?, &project)?)]),
            SAVE => {
                let title = s(&d, "title").unwrap_or_default();
                let description = s(&d, "description").unwrap_or_default();
                let body = d.get("body").and_then(Value::as_str).unwrap_or_default();
                let tags = d.get("tags").map(|t| match t {
                    Value::Array(v) => parse_tags(&v.iter().filter_map(Value::as_str).collect::<Vec<_>>().join(",")),
                    Value::String(x) => parse_tags(x),
                    _ => Vec::new(),
                });
                // Editing an existing script keeps its scope and name; a new one names both.
                let (scope, sproject, name) = match &id {
                    Some(i) => {
                        let cur = m.library.get(i).map_err(refuse)?.ok_or_else(|| refuse(format!("no script {i}")))?;
                        (cur.scope, cur.project_id, cur.name)
                    }
                    None => {
                        let scope = s(&d, "scope").unwrap_or_else(|| "project".into());
                        (scope.clone(), if scope == "global" { String::new() } else { project.clone() }, s(&d, "name").ok_or_else(|| refuse("needs name".into()))?)
                    }
                };
                let (saved, _) = m.save(&scope, &sproject, &name, None, &title, &description, body, tags, "owner").map_err(refuse)?;
                changed(&project);
                Ok(vec![answer(env, ITEM, detail(&saved.id, &project)?)])
            }
            DELETE => {
                let hooks = m.delete(&need_id()?).map_err(refuse)?;
                changed(&project);
                Ok(vec![result(true, format!("deleted{}", if hooks > 0 { format!(" with {hooks} hook(s)") } else { String::new() }))])
            }
            ENABLE => {
                let i = need_id()?;
                m.library.set_enabled(&i, d.get("enabled").and_then(Value::as_bool).unwrap_or(true)).map_err(refuse)?;
                changed(&project);
                Ok(vec![answer(env, ITEM, detail(&i, &project)?)])
            }
            RESTORE => {
                let i = need_id()?;
                let v = d.get("version").and_then(Value::as_i64).ok_or_else(|| refuse("needs version".into()))?;
                m.library.restore(&i, v, "owner").map_err(refuse)?;
                changed(&project);
                Ok(vec![answer(env, ITEM, detail(&i, &project)?)])
            }
            RUN => {
                let name = s(&d, "name").ok_or_else(|| refuse("needs name".into()))?;
                let mut a = Map::new();
                a.insert("name".into(), json!(name));
                a.insert("project".into(), json!(project));
                a.insert("dryRun".into(), json!(d.get("dryRun").and_then(Value::as_bool) == Some(true)));
                if let Some(args) = d.get("args") {
                    a.insert("args".into(), if args.is_string() { args.clone() } else { json!(args.to_string()) });
                }
                let r = call(crate::tools::RUN, a);
                changed(&project);
                Ok(vec![result(!r.is_error, r.text)])
            }
            HOOK => {
                let op = s(&d, "op").unwrap_or_default();
                let every = d.get("everyProject").and_then(Value::as_bool) == Some(true);
                let text = match op.as_str() {
                    "add" => {
                        let args = d.get("args").and_then(Value::as_object).cloned().unwrap_or_default();
                        let hid = m.hook_add(&project, &s(&d, "scriptId").ok_or_else(|| refuse("needs scriptId".into()))?, &s(&d, "on").unwrap_or_default(), args, s(&d, "hookId").as_deref(), every, "owner").map_err(refuse)?;
                        format!("added hook {hid}")
                    }
                    "remove" => {
                        let hid = s(&d, "hookId").ok_or_else(|| refuse("needs hookId".into()))?;
                        m.hook_remove(&project, &hid, every).map_err(refuse)?;
                        format!("removed hook {hid}")
                    }
                    "enable" => {
                        let hid = s(&d, "hookId").ok_or_else(|| refuse("needs hookId".into()))?;
                        let on = d.get("enabled").and_then(Value::as_bool).unwrap_or(true);
                        m.hook_set_enabled(&project, &hid, every, on).map_err(refuse)?;
                        format!("hook {hid} {}", if on { "enabled" } else { "disabled" })
                    }
                    other => return Err(refuse(format!("op must be add, remove or enable (got '{other}')"))),
                };
                changed(&project);
                Ok(vec![result(true, text)])
            }
            FILES => {
                let mut a = Map::new();
                for k in ["action", "scope", "dir", "name"] {
                    if let Some(v) = s(&d, k) {
                        a.insert(k.into(), json!(v));
                    }
                }
                a.insert("project".into(), json!(project));
                let r = call(crate::tools::FILES, a);
                changed(&project);
                Ok(vec![result(!r.is_error, r.text)])
            }
            other => Err(refuse(format!("not a scripts frame: {other}"))),
        }
    }
}
