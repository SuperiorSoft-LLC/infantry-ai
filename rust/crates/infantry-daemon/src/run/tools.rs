// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The `infantry_run_*` agent tools (Kotlin `RunCommandTools`): list, save, delete, start and stop saved run commands.
//! A command belongs to a project; an agent reaches another project's only with `allowCrossProject`.

use super::{split_line, CommandSave, RunManager};
use infantry_scripting::host::{ToolInfo, ToolInvoker, ToolResult};
use serde_json::{json, Map, Value};
use std::sync::Arc;

pub const NAMES: &[&str] = &[
    "infantry_run_commands",
    "infantry_run_command_save",
    "infantry_run_command_delete",
    "infantry_run_start",
    "infantry_run_branches",
    "infantry_run_secrets",
    "infantry_secret_find",
    "infantry_secret_render",
    "infantry_run_stop",
];

fn schema(props: Value, required: &[&str]) -> Value {
    json!({"type": "object", "properties": props, "required": required})
}

pub fn catalog() -> Vec<ToolInfo> {
    let scope = json!({
        "project": {"type": "string", "description": "Project id (default: yours)."},
        "allowCrossProject": {"type": "boolean", "description": "Reach another project's commands."},
    });
    let with = |extra: Value| {
        let mut p = scope.as_object().unwrap().clone();
        p.extend(extra.as_object().unwrap().clone());
        Value::Object(p)
    };
    let t = |name: &str, description: &str, input_schema: Value, read_only: bool| ToolInfo { name: name.into(), description: description.into(), input_schema, read_only };
    vec![
        t("infantry_run_commands", "List the saved run commands (the Run tab) of a project.", schema(scope.clone(), &[]), true),
        t(
            "infantry_run_command_save",
            "Save (create or update) a run command by id: `command` is one shell line; it runs in a terminal from the Run tab.",
            schema(
                with(json!({
                    "id": {"type": "string"}, "name": {"type": "string"}, "command": {"type": "string", "description": "One shell line."},
                    "cwd": {"type": "string", "description": "Folder (relative = to the project's folder)."},
                    "detached": {"type": "boolean"},
                    "secrets": {"type": "array", "items": {"type": "string"}, "description": "Secret names whose fields become the run's environment."},
                    "renders": {"type": "array", "items": {"type": "string"}, "description": "template=out secret-template renders (relative to the command folder) done before each run; outputs are deleted when it ends."},
                })),
                &["id"],
            ),
            false,
        ),
        t("infantry_run_command_delete", "Delete a saved run command.", schema(with(json!({"id": {"type": "string"}})), &["id"]), false),
        t(
            "infantry_run_start",
            "Start a saved run command in a new terminal; answers the run (runId). A branch command (its cwd or argv names {worktree} or {branch}) needs `branch`: it runs in that branch's worktree (its own, else a temporary preview worktree removed when the run ends).",
            schema(with(json!({"id": {"type": "string"}, "branch": {"type": "string"}})), &["id"]),
            false,
        ),
        t(
            "infantry_run_branches",
            "List a project's local branches for a branch command (one whose cwd or argv names {worktree} or {branch}), each with the worktree it is checked out in, if any.",
            schema(scope.clone(), &[]),
            true,
        ),
        t(
            "infantry_run_secrets",
            "List the secrets a project's saved commands can use, the scope each resolves to (project first, then app) and each field with its exact placeholder: {{secret:NAME}} or {{secret:NAME#FIELD}}. Names only, never a value; secrets are created in Configuration by the owner.",
            schema(with(json!({"scope": {"type": "string", "description": "project | app | all (default: all)"}})), &[]),
            true,
        ),
        t(
            "infantry_secret_find",
            "Find a secret by name or field name; returns placeholders, never values.",
            schema(with(json!({"q": {"type": "string"}, "scope": {"type": "string", "description": "project | app | all (default: all)"}})), &["q"]),
            true,
        ),
        t(
            "infantry_secret_render",
            "Render a text file (.env, .json, .yaml, any text) replacing {{secret:NAME}} (or {{secret:NAME#FIELD}}) placeholders with the saved secret values, into an owner-only output file. Returns names and the output path only, never a value. `check` reports found/missing names and writes nothing. A missing secret fails and writes nothing unless allowMissing.",
            schema(
                with(json!({
                    "template": {"type": "string", "description": "The template file (relative = inside the project folder)."},
                    "out": {"type": "string", "description": "Output file (required unless check); never the template itself."},
                    "allowMissing": {"type": "boolean"}, "check": {"type": "boolean"},
                })),
                &["template"],
            ),
            false,
        ),
        t(
            "infantry_run_stop",
            "Stop a run: Ctrl+C then the process tree after its grace; force kills now.",
            schema(json!({"runId": {"type": "string"}, "force": {"type": "boolean"}}), &["runId"]),
            false,
        ),
    ]
}

fn s<'a>(a: &'a Map<String, Value>, k: &str) -> Option<&'a str> {
    a.get(k).and_then(Value::as_str).map(str::trim).filter(|v| !v.is_empty())
}

fn strings(a: &Map<String, Value>, k: &str) -> Option<Vec<String>> {
    a.get(k).and_then(Value::as_array).map(|v| v.iter().filter_map(Value::as_str).map(String::from).collect())
}

fn b(a: &Map<String, Value>, k: &str) -> bool {
    matches!(a.get(k), Some(Value::Bool(true))) || a.get(k).and_then(Value::as_str) == Some("true")
}

/// The project the call acts in; Err when an agent reaches across projects without saying so.
fn project(a: &Map<String, Value>, inv: &ToolInvoker) -> Result<Option<String>, String> {
    let asked = s(a, "project").map(String::from);
    match (&asked, &inv.project_id) {
        (Some(p), Some(mine)) if p != mine && inv.agent && !inv.owner && !b(a, "allowCrossProject") => {
            Err(format!("project '{p}' is not yours; pass allowCrossProject to reach it"))
        }
        _ => Ok(asked.or(inv.project_id.clone())),
    }
}

/// None = not a run tool.
pub fn invoke(m: &Arc<RunManager>, name: &str, a: &Map<String, Value>, inv: &ToolInvoker) -> Option<ToolResult> {
    if !NAMES.contains(&name) {
        return None;
    }
    let r: Result<String, String> = (|| match name {
        "infantry_run_commands" => Ok(m.commands_json(project(a, inv)?.as_deref())),
        "infantry_run_command_save" => {
            let id = s(a, "id").ok_or("id is required")?.to_string();
            let p = project(a, inv)?;
            if let Some(old) = m.commands.get(&id) {
                if old.project.is_some() && old.project != p && !inv.owner && !b(a, "allowCrossProject") {
                    return Err(format!("command '{id}' belongs to another project"));
                }
            }
            let req = CommandSave {
                id,
                name: s(a, "name").map(String::from),
                argv: s(a, "command").map(split_line).unwrap_or_default(),
                cwd: s(a, "cwd").map(String::from),
                grace_millis: None,
                project: p,
                renders: strings(a, "renders"),
                secrets: strings(a, "secrets"),
                detached: a.get("detached").map(|_| b(a, "detached")),
            };
            m.save(req).map(|c| serde_json::to_string(&c).unwrap_or_default())
        }
        "infantry_run_command_delete" => {
            let id = s(a, "id").ok_or("id is required")?;
            let p = project(a, inv)?;
            let c = m.commands.get(id).ok_or_else(|| format!("no saved command '{id}'"))?;
            if c.project.is_some() && c.project != p && !inv.owner && !b(a, "allowCrossProject") {
                return Err(format!("command '{id}' belongs to another project"));
            }
            m.delete(id).map(|_| format!("deleted {id}"))
        }
        "infantry_run_start" => {
            let id = s(a, "id").ok_or("id is required")?;
            let p = project(a, inv)?;
            let c = m.commands.get(id).ok_or_else(|| format!("no saved command '{id}'"))?;
            if c.project.is_some() && c.project != p && !inv.owner && !b(a, "allowCrossProject") {
                return Err(format!("command '{id}' belongs to another project"));
            }
            m.start(id, s(a, "branch").map(String::from)).map(|r| r.to_string())
        }
        "infantry_run_branches" => {
            let p = project(a, inv)?.ok_or("project is required (you have none)")?;
            let ext = m.extras().ok_or("branches are not available on this daemon")?;
            let rows = super::prep::branches(&ext.projects, &p)?;
            Ok(if rows.is_empty() {
                "no local branches".into()
            } else {
                rows.iter().map(|b| format!("{}{}", b.name, b.worktree.as_ref().map(|w| format!("  (worktree {w})")).unwrap_or_default())).collect::<Vec<_>>().join("
")
            })
        }
        "infantry_run_secrets" => {
            let p = project(a, inv)?;
            let ext = m.extras().ok_or("secrets are not available on this daemon")?;
            infantry_vault::template::run_secrets(ext.vault.run_secrets(), p.as_deref(), s(a, "scope")).map_err(|e| e.to_string())
        }
        "infantry_secret_find" => {
            let q = s(a, "q").ok_or("q is required")?;
            let p = project(a, inv)?;
            let ext = m.extras().ok_or("secrets are not available on this daemon")?;
            infantry_vault::template::secret_find(ext.vault.run_secrets(), q, p.as_deref(), s(a, "scope")).map_err(|e| e.to_string())
        }
        "infantry_secret_render" => {
            let p = project(a, inv)?;
            let ext = m.extras().ok_or("secrets are not available on this daemon")?;
            let check = b(a, "check");
            let base = p.as_deref().and_then(|p| m.project_folder(p)).unwrap_or_else(|| m.default_folder());
            let at = |rel: &str| {
                let path = std::path::PathBuf::from(rel);
                if path.is_absolute() { path } else { base.join(path) }
            };
            let template = at(s(a, "template").ok_or("template is required")?);
            let out = s(a, "out").map(at);
            if !check && out.is_none() {
                return Err("out is required unless check".into());
            }
            if out.as_ref() == Some(&template) {
                return Err("out is the template itself".into());
            }
            let hint = format!(" (relative paths start at {})", base.display());
            let r = ext.vault.renderer().render(&template, out.as_deref(), p.as_deref(), b(a, "allowMissing"), check, &hint);
            if r.ok() { Ok(r.summary(check)) } else { Err(r.summary(check)) }
        }
        "infantry_run_stop" => {
            let id = s(a, "runId").ok_or("runId is required")?;
            m.stop(id, b(a, "force")).map(|_| format!("stopping {id}"))
        }
        _ => Err("unknown".into()),
    })();
    Some(match r {
        Ok(t) => ToolResult::ok(t),
        Err(e) => ToolResult::err(format!("{name} refused: {e}")),
    })
}
