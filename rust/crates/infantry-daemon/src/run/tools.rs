// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The `infantry_run_*` agent tools (Kotlin `RunCommandTools`): list, save, delete, start and stop saved run commands.
//! A command belongs to a project; an agent reaches another project's only with `allowCrossProject`.

use super::{split_line, CommandSave, RunManager};
use infantry_scripting::host::{ToolInfo, ToolInvoker, ToolResult};
use serde_json::{json, Map, Value};
use std::sync::Arc;

pub const NAMES: &[&str] =
    &["infantry_run_commands", "infantry_run_command_save", "infantry_run_command_delete", "infantry_run_start", "infantry_run_stop"];

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
                    "detached": {"type": "boolean"}, "renders": {"type": "array"},
                })),
                &["id"],
            ),
            false,
        ),
        t("infantry_run_command_delete", "Delete a saved run command.", schema(with(json!({"id": {"type": "string"}})), &["id"]), false),
        t(
            "infantry_run_start",
            "Start a saved run command in a new terminal; answers the run (runId).",
            schema(with(json!({"id": {"type": "string"}, "branch": {"type": "string"}})), &["id"]),
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
                renders: a.get("renders").and_then(Value::as_array).cloned(),
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
