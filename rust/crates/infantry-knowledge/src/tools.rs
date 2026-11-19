// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The agent tools: `infantry_role_*` and `infantry_skill_*` (layered skills). [`descriptors`] lists them (MCP
//! `tools/list` shape); [`call`] runs one. An agent caller may read roles and change only persona / description;
//! create and delete need a non-agent caller.
use serde_json::{json, Map, Value};

use crate::library::SkillScope;
use crate::roles::RoleSave;
use crate::unit::Knowledge;

/// Who is calling a tool.
#[derive(Debug, Clone, Default)]
pub struct Caller {
    /// An agent session (or a connection holding the agent token).
    pub agent: bool,
    pub project_id: Option<String>,
    pub role: Option<String>,
}

fn prop(d: &str) -> Value {
    json!({ "type": "string", "description": d })
}

fn tool(name: &str, desc: &str, read_only: bool, destructive: bool, props: &[(&str, &str)], required: &[&str]) -> Value {
    let p: Map<String, Value> = props.iter().map(|(k, d)| (k.to_string(), prop(d))).collect();
    json!({
        "name": name,
        "description": desc,
        "inputSchema": { "type": "object", "properties": p, "required": required },
        "annotations": { "readOnlyHint": read_only, "destructiveHint": destructive, "idempotentHint": read_only, "openWorldHint": false },
    })
}

const ID: (&str, &str) = ("id", "The role id, e.g. automation.");
const PERSONA: (&str, &str) = ("persona", "The role notes: the instructions every NEW session of this role receives at start. Replaces the stored text.");
const DESCRIPTION: (&str, &str) = ("description", "One line describing the role (at most 200 characters); shown in the role editor, never sent to a session.");
const CARDS: (&str, &str) = ("cards", "Preferred card types, comma separated (e.g. news). Empty string clears. Named at session start while loaded.");
const TOOLS: (&str, &str) = ("tools", "Preferred tool or capability names, comma separated. Empty string clears. Named at session start while loaded.");
const MCP: (&str, &str) = ("mcp", "MCP server names this role's sessions are granted, comma separated (non-agent caller only; walled when empty). Needs mcpPurpose. Empty string clears.");
const MCP_PURPOSE: (&str, &str) = ("mcpPurpose", "Why the role needs those MCP servers (non-agent caller only).");
const SCOPE: (&str, &str) = ("scope", "global, project or role.");
const SKILL_NAME: (&str, &str) = ("name", "The skill name.");
const SKILL_PROJECT: (&str, &str) = ("projectId", "Project id for project/role scope (default: the caller's project).");
const SKILL_ROLE: (&str, &str) = ("role", "Role id for role scope (default: the caller's role).");

pub const TOOL_NAMES: &[&str] = &[
    "infantry_role_list",
    "infantry_role_get",
    "infantry_role_update",
    "infantry_role_create",
    "infantry_role_delete",
    "infantry_skill_list",
    "infantry_skill_save",
    "infantry_skill_delete",
];

pub fn descriptors() -> Vec<Value> {
    let edit = [ID, PERSONA, DESCRIPTION, CARDS, TOOLS, MCP, MCP_PURPOSE, ("name", "New display name (non-agent caller only)."), ("color", "#rrggbb (non-agent caller only)."), ("icon", "Icon key (non-agent caller only).")];
    vec![
        tool("infantry_role_list", "List every role: id, name, description, source and persona size.", true, false, &[], &[]),
        tool("infantry_role_get", "Read one role in full, including its persona (the role notes).", true, false, &[ID], &["id"]),
        tool(
            "infantry_role_update",
            "Update an existing role's persona (role notes), description and/or preferred cards / tools. Applies to new sessions only. An agent caller may change nothing else; a non-agent caller may also rename it (name), recolour it (color #rrggbb) or set its icon.",
            false,
            false,
            &edit,
            &["id"],
        ),
        tool("infantry_role_create", "Create a role (non-agent caller only).", false, false, &edit, &["id", "name"]),
        tool(
            "infantry_role_delete",
            "Delete a custom role, or reset a customised bundled role to its bundled definition (non-agent caller only). Refused while a live session has the role.",
            false,
            true,
            &[ID],
            &["id"],
        ),
        tool("infantry_skill_list", "List the skills of one layer (global, project or role) in full.", true, false, &[SCOPE, SKILL_PROJECT, SKILL_ROLE], &["scope"]),
        tool(
            "infantry_skill_save",
            "Create or replace a skill (by name) in one layer. A narrower layer overrides a wider one of the same name.",
            false,
            false,
            &[SCOPE, SKILL_NAME, ("description", "One line."), ("body", "The skill text."), SKILL_PROJECT, SKILL_ROLE],
            &["scope", "name", "body"],
        ),
        tool("infantry_skill_delete", "Delete a skill from one layer.", false, true, &[SCOPE, SKILL_NAME, SKILL_PROJECT, SKILL_ROLE], &["scope", "name"]),
    ]
}

fn s<'a>(a: &'a Value, k: &str) -> Option<&'a str> {
    a.get(k).and_then(Value::as_str)
}

fn ids(a: &Value, k: &str) -> Option<Vec<String>> {
    s(a, k).map(|v| v.split(',').map(str::trim).filter(|x| !x.is_empty()).map(String::from).collect())
}

/// Runs tool `name`. Ok = the result text (JSON); Err = the refusal the agent sees. None = not one of ours.
pub fn call(k: &Knowledge, name: &str, args: &Value, caller: &Caller) -> Option<Result<String, String>> {
    if !TOOL_NAMES.contains(&name) {
        return None;
    }
    Some(run(k, name, args, caller))
}

fn run(k: &Knowledge, name: &str, a: &Value, caller: &Caller) -> Result<String, String> {
    let role_of = |id: &str| k.roles.role(&crate::ids::normalize(id.trim()));
    match name {
        "infantry_role_list" => {
            let out: Vec<Value> = k
                .roles
                .all()
                .into_iter()
                .map(|r| {
                    let mut o = json!({ "id": r.id, "name": r.name, "description": r.description, "source": r.source });
                    if !r.plugin.is_empty() {
                        o["plugin"] = json!(r.plugin);
                        o["disabled"] = json!(r.disabled);
                        o["edited"] = json!(r.edited);
                    }
                    for (key, list) in [("cards", &r.cards), ("tools", &r.tools), ("mcp", &r.mcp_servers)] {
                        if !list.is_empty() {
                            o[key] = json!(list.join(","));
                        }
                    }
                    o["personaChars"] = json!(r.persona.chars().count());
                    o
                })
                .collect();
            Ok(Value::Array(out).to_string())
        }
        "infantry_role_get" => {
            let id = s(a, "id").ok_or(format!("{name} needs an id"))?;
            let r = role_of(id).ok_or(format!("{name}: unknown role '{id}'"))?;
            Ok(serde_json::to_string(&k.with_avatar(r)).unwrap_or_default())
        }
        "infantry_role_update" => {
            let id = s(a, "id").ok_or(format!("{name} needs an id"))?;
            let existing = role_of(id).ok_or(format!("{name}: unknown role '{id}'"))?;
            let restricted: Vec<&str> = ["name", "color", "icon", "mcp", "mcpPurpose", "cards", "tools"].into_iter().filter(|k| s(a, k).is_some()).collect();
            if caller.agent && restricted.iter().any(|k| ["name", "color", "icon", "mcp", "mcpPurpose"].contains(k)) {
                return Err(format!("{name}: an agent may change only persona and description (never its MCP grants); {} need a non-agent caller", restricted.join(", ")));
            }
            if ["persona", "description", "cards", "tools", "mcp", "mcpPurpose", "name", "color", "icon"].iter().all(|k| s(a, k).is_none()) {
                return Err(format!("{name}: nothing to change (give persona, description, cards or tools)"));
            }
            let req = RoleSave {
                id: existing.id.clone(),
                name: s(a, "name").map(String::from).unwrap_or(existing.name.clone()),
                color: s(a, "color").map(String::from).or(Some(existing.color.clone())),
                persona: s(a, "persona").map(String::from).unwrap_or(existing.persona.clone()),
                icon: s(a, "icon").map(String::from),
                description: s(a, "description").map(String::from),
                cards: ids(a, "cards"),
                tools: ids(a, "tools"),
                mcp: ids(a, "mcp"),
                mcp_purpose: s(a, "mcpPurpose").map(String::from),
            };
            k.roles.save(&req).map(|r| serde_json::to_string(&k.with_avatar(r)).unwrap_or_default()).map_err(|e| format!("{name} refused: {e}"))
        }
        "infantry_role_create" => {
            if caller.agent {
                return Err(format!("{name} needs a non-agent caller: an agent may only update the persona and description of an existing role"));
            }
            let id = s(a, "id").ok_or(format!("{name} needs an id"))?.trim();
            if k.roles.role(id).is_some_and(|r| r.source == crate::roles::RoleSource::Custom) {
                return Err(format!("{name}: role '{id}' already exists (use infantry_role_update)"));
            }
            let req = RoleSave {
                id: id.into(),
                name: s(a, "name").unwrap_or(id).into(),
                color: s(a, "color").map(String::from),
                persona: s(a, "persona").unwrap_or("").into(),
                icon: s(a, "icon").map(String::from),
                description: s(a, "description").map(String::from),
                cards: ids(a, "cards"),
                tools: ids(a, "tools"),
                mcp: ids(a, "mcp"),
                mcp_purpose: s(a, "mcpPurpose").map(String::from),
            };
            k.roles.save(&req).map(|r| serde_json::to_string(&k.with_avatar(r)).unwrap_or_default()).map_err(|e| format!("{name} refused: {e}"))
        }
        "infantry_role_delete" => {
            if caller.agent {
                return Err(format!("{name} needs a non-agent caller: an agent may not delete roles"));
            }
            let id = s(a, "id").ok_or(format!("{name} needs an id"))?;
            match k.roles.delete(id, &k.daemon.live_role_ids()).map_err(|e| format!("{name} refused: {e}"))? {
                Some(r) => Ok(json!({ "reset": true, "role": k.with_avatar(r) }).to_string()),
                None => Ok(json!({ "removed": id.trim() }).to_string()),
            }
        }
        _ => {
            let scope = SkillScope::parse(
                s(a, "scope").unwrap_or("global"),
                s(a, "projectId").or(caller.project_id.as_deref()),
                s(a, "role").or(caller.role.as_deref()),
            )
            .map_err(|e| format!("{name} refused: {e}"))?;
            let l = &k.library;
            let skills = match name {
                "infantry_skill_list" => l.layer_list(&scope),
                "infantry_skill_save" => l.layer_save(&scope, s(a, "name").unwrap_or(""), s(a, "description").unwrap_or(""), s(a, "body").unwrap_or("")),
                _ => l.layer_delete(&scope, s(a, "name").unwrap_or("")),
            }
            .map_err(|e| e.render(name))?;
            Ok(json!({ "scope": scope.layer(), "projectId": scope.project(), "role": scope.role(), "skills": skills }).to_string())
        }
    }
}
