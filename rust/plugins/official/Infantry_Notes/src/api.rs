// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The wire surface: the `memory_*`, `decision_*` and `issue_*` frames and the `infantry_memory` tool, over the stores.
//! A refusal answers an `error` frame `<frame> refused: <why>` (a coded one has its code first); anything else
//! `<frame> failed: <why>`. Adds, opens, attempts and fixes are PUSHED to every client (the asker's answer IS the push).
use crate::db::*;
use crate::memory::{self, Caller, Target, GLOBAL_PROJECT};
use crate::{decisions, issues};
use serde_json::{json, Value};

pub const FRAME_TYPES: [&str; 15] = [
    "decision_list", "decision_get", "decision_add", "issue_list", "issue_get", "issue_precheck", "issue_open", "issue_attempt", "issue_fix",
    "memory_search", "memory_list", "memory_get", "memory_save", "memory_delete", "memory_purge_role",
];

/// What a frame produced: an optional reply to the asker and frames to broadcast.
#[derive(Debug, Default)]
pub struct Reply {
    pub reply: Option<(String, String)>,
    pub push: Vec<(String, String)>,
}

fn err(frame: &str, e: &str, refused: bool) -> Reply {
    Reply { reply: Some(("error".into(), format!("{frame} {}: {e}", if refused { "refused" } else { "failed" }))), push: vec![] }
}

fn ok(ty: &str, v: Value) -> Reply {
    Reply { reply: Some((ty.into(), v.to_string())), push: vec![] }
}

fn pushed(ty: &str, v: Value) -> Reply {
    Reply { reply: None, push: vec![(ty.into(), v.to_string())] }
}

pub fn handle(db: &dyn Db, p: &dyn Ports, ty: &str, data: &str) -> Option<Reply> {
    if !FRAME_TYPES.contains(&ty) {
        return None;
    }
    Some(match run(db, p, ty, data) {
        Ok(r) => r,
        // every error here is a rule or shape refusal, except storage faults which carry a `sql` marker
        Err(e) if e.starts_with("storage:") => err(ty, &e, false),
        Err(e) => err(ty, &e, true),
    })
}

fn session_of(p: &dyn Ports, v: &Value) -> Result<Option<SessionInfo>, String> {
    match opt_trim(v, "sessionId") {
        None => Ok(None),
        Some(id) => p.session(&id).map(Some).ok_or_else(|| refuse("session_not_found", format!("no session '{id}' on this daemon"))),
    }
}

fn caller_of(s: &Option<SessionInfo>) -> Option<Caller> {
    s.as_ref().map(|s| Caller { session_id: s.id.clone(), role: s.role.clone() })
}

fn run(db: &dyn Db, p: &dyn Ports, ty: &str, data: &str) -> Result<Reply, String> {
    // memory_list also takes a bare project id
    let v: Value = if ty == "memory_list" && !data.trim_start().starts_with('{') { json!({ "projectId": data }) } else { serde_json::from_str(data).map_err(|e| format!("invalid payload: {e}"))? };
    let project = || req_str(&v, "projectId");
    match ty {
        "decision_add" => {
            let by = session_of(p, &v)?;
            let d = decisions::add(db, p, &project()?, &req_str(&v, "summary")?, opt_str(&v, "body").as_deref(), opt_str(&v, "supersedes").as_deref(), by.as_ref())?;
            Ok(pushed("decision_item", d))
        }
        "decision_list" => Ok(ok("decisions", Value::Array(decisions::list(db, &project()?, opt_bool(&v, "includeSuperseded"))?))),
        "decision_get" => {
            let r = req_str(&v, "ref")?;
            let d = decisions::get(db, &project()?, &r)?.ok_or_else(|| refuse("decision_not_found", format!("no decision {} in project '{}'", r.trim(), project().unwrap_or_default().trim())))?;
            Ok(ok("decision_item", d))
        }
        "issue_open" => {
            let by = session_of(p, &v)?;
            let i = issues::open(db, p, &project()?, &req_str(&v, "summary")?, &req_str(&v, "location")?, opt_str(&v, "ticketId").as_deref(), by.as_ref())?;
            Ok(pushed("issue_item", i))
        }
        "issue_attempt" => {
            let by = session_of(p, &v)?;
            let i = issues::attempt(db, p, &project()?, &req_str(&v, "summary")?, opt_str(&v, "outcome").as_deref(), opt_str(&v, "location").as_deref(), opt_str(&v, "issue").as_deref(), by.as_ref())?;
            Ok(pushed("issue_item", i))
        }
        "issue_fix" => {
            let by = session_of(p, &v)?;
            let i = issues::fix(db, p, &project()?, &req_str(&v, "summary")?, opt_str(&v, "issue").as_deref(), by.as_ref())?;
            Ok(pushed("issue_item", i))
        }
        "issue_list" => Ok(ok("issues", Value::Array(issues::list(db, &project()?, opt_bool(&v, "includeFixed"), opt_str(&v, "location").as_deref())?))),
        "issue_get" => {
            let r = req_str(&v, "ref")?;
            let i = issues::get(db, &project()?, &r)?.ok_or_else(|| refuse("issue_not_found", format!("no issue {} in project '{}'", r.trim(), project().unwrap_or_default().trim())))?;
            Ok(ok("issue_item", i))
        }
        "issue_precheck" => Ok(ok("issues", Value::Array(issues::precheck(db, &project()?, &req_str(&v, "path")?)?))),
        "memory_search" => {
            let who = session_of(p, &v)?;
            let t = memory::resolve(opt_str(&v, "scope").as_deref(), opt_str(&v, "role").as_deref(), caller_of(&who).as_ref())?;
            let pr = if t.global { GLOBAL_PROJECT.to_string() } else { project()? };
            Ok(ok("memory_hits", Value::Array(memory::search(db, &pr, &req_str(&v, "query")?, &t.scope, &t.owner)?)))
        }
        "memory_list" => Ok(ok("memory_notes", Value::Array(index_entries(db, p, &v)?))),
        "memory_get" => {
            let who = session_of(p, &v)?;
            let t = memory::resolve(opt_str(&v, "scope").as_deref(), opt_str(&v, "role").as_deref(), caller_of(&who).as_ref())?;
            let pr = if t.global { GLOBAL_PROJECT.to_string() } else { project()? };
            let name = req_str(&v, "name")?;
            let n = memory::get(db, &pr, &name, &t.scope, &t.owner)?.ok_or_else(|| refuse("note_not_found", format!("no {} note '{}' in project '{}'", t.scope, name.trim(), project().unwrap_or_default().trim())))?;
            Ok(ok("memory_note", n.json()))
        }
        "memory_save" => {
            let who = session_of(p, &v)?;
            let note = save(db, p, &v, &who)?;
            p.audit(&format!("memory write scope={} name={} by={}", opt_str(&v, "scope").unwrap_or_else(|| "project".into()), note.name, who_label(&who)));
            Ok(ok("memory_note", note.json()))
        }
        "memory_delete" => {
            let who = session_of(p, &v)?;
            let t = memory::resolve(opt_str(&v, "scope").as_deref(), opt_str(&v, "role").as_deref(), caller_of(&who).as_ref())?;
            if t.global {
                require_global_writer(p, &who)?;
            }
            let pr = if t.global { GLOBAL_PROJECT.to_string() } else { project()? };
            let name = req_str(&v, "name")?;
            if !memory::delete(db, &pr, &name, &t.scope, &t.owner)? {
                return Err(refuse("note_not_found", format!("no {} note '{}' to delete", t.scope, name.trim())));
            }
            p.audit(&format!("memory delete scope={} name={} by={}", opt_str(&v, "scope").unwrap_or_else(|| "project".into()), name.trim(), who_label(&who)));
            Ok(ok("memory_notes", Value::Array(index_entries(db, p, &json!({ "projectId": v["projectId"], "scope": v["scope"], "sessionId": v["sessionId"], "role": v["role"] }))?)))
        }
        "memory_purge_role" => {
            let role = req_str(&v, "role")?;
            let role = role.trim();
            if role.is_empty() {
                return Err("expects a role".into());
            }
            if req_str(&v, "confirm").unwrap_or_default().trim() != role {
                return Err(format!("deleting a role purges ALL its notes in every project; pass confirm equal to the role id '{role}'"));
            }
            memory::purge_role(db, role)?;
            Ok(ok("memory_notes", json!([])))
        }
        _ => unreachable!(),
    }
}

fn who_label(who: &Option<SessionInfo>) -> String {
    who.as_ref().map(|w| format!("{}/{}", w.role, w.id)).unwrap_or_else(|| "owner".into())
}

fn require_global_writer(p: &dyn Ports, who: &Option<SessionInfo>) -> Result<(), String> {
    match who {
        None => Ok(()),
        Some(w) if p.global_write_roles().iter().any(|r| *r == w.role) => Ok(()),
        Some(w) => Err(format!("role '{}' may not write global notes (setting knowledge.globalWriteRoles)", w.role)),
    }
}

fn index_entries(db: &dyn Db, _p: &dyn Ports, v: &Value) -> Result<Vec<Value>, String> {
    let (who, caller) = match opt_trim(v, "sessionId") {
        Some(id) => {
            let s = _p.session(&id).ok_or_else(|| format!("no session '{id}' on this daemon"))?;
            (Some(s.clone()), Some(Caller { session_id: s.id, role: s.role }))
        }
        None => (None, None),
    };
    let _ = who;
    let t = memory::resolve(opt_str(v, "scope").as_deref(), opt_str(v, "role").as_deref(), caller.as_ref())?;
    let pr = if t.global { GLOBAL_PROJECT.to_string() } else { req_str(v, "projectId")? };
    let mut notes = memory::index(db, &pr, &t.scope, &t.owner)?;
    notes.sort_by(|a, b| a.name.cmp(&b.name));
    Ok(notes.iter().map(|n| json!({ "name": n.name, "description": n.description, "folder": n.folder })).collect())
}

fn save(db: &dyn Db, p: &dyn Ports, v: &Value, who: &Option<SessionInfo>) -> Result<memory::Note, String> {
    let mode = opt_trim(v, "mode").unwrap_or_else(|| "replace".into());
    let t: Target = memory::resolve(opt_str(v, "scope").as_deref(), opt_str(v, "role").as_deref(), caller_of(who).as_ref())?;
    if t.global {
        require_global_writer(p, who)?;
    }
    let pr = if t.global { GLOBAL_PROJECT.to_string() } else { req_str(v, "projectId")? };
    let name = req_str(v, "name")?;
    let refuse_field = |f: &str| Err(format!("mode '{mode}' does not take '{f}'"));
    let (body, content, find, desc, folder) = (opt_str(v, "body"), opt_str(v, "content"), opt_str(v, "find"), opt_str(v, "description"), opt_str(v, "folder"));
    match mode.as_str() {
        "replace" => {
            if content.is_some() {
                return refuse_field("content' (replace takes 'body");
            }
            if find.is_some() {
                return refuse_field("find");
            }
            let d = desc.ok_or("replace expects 'description'")?;
            let b = body.ok_or("replace expects 'body'")?;
            memory::save(db, p, &pr, &name, &d, &b, folder.as_deref(), &t.scope, &t.owner)
        }
        "append" => {
            if body.is_some() {
                return refuse_field("body' (append takes 'content");
            }
            if find.is_some() {
                return refuse_field("find");
            }
            let c = content.ok_or("append expects 'content'")?;
            memory::append(db, p, &pr, &name, &c, desc.as_deref(), folder.as_deref(), &t.scope, &t.owner)
        }
        "edit" => {
            if body.is_some() {
                return refuse_field("body' (edit takes 'content");
            }
            let f = find.ok_or("edit expects 'find'")?;
            let c = content.ok_or("edit expects 'content' (an empty one removes the passage)")?;
            memory::edit(db, p, &pr, &name, &f, &c, desc.as_deref(), folder.as_deref(), &t.scope, &t.owner)
        }
        other => Err(format!("unknown mode '{other}'; expected one of replace, append, edit")),
    }
}

// ----------------------------------------------------------------------------------------------- the infantry_memory tool

pub const TOOL_NAME: &str = "infantry_memory";
const ACTIONS: [&str; 6] = ["show", "search", "index", "write", "update", "delete"];
const TOOL_SCOPES: [&str; 4] = ["project", "role", "session", "global"];

/// The tool call context (call-context minus the arguments).
#[derive(Debug, Clone, Default)]
pub struct ToolCtx {
    pub session_id: Option<String>,
    pub role: Option<String>,
    pub project_id: Option<String>,
}

/// `infantry_memory`: the same rules as the frames, resolved from the call's own session and role.
pub fn call_tool(db: &dyn Db, p: &dyn Ports, args: &str, ctx: &ToolCtx) -> Result<String, String> {
    let a: Value = serde_json::from_str(args).unwrap_or(Value::Null);
    let arg = |k: &str| opt_trim(&a, k);
    let action = arg("action");
    let scope = arg("scope");
    let session = ctx.session_id.clone().map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
    let role = ctx.role.clone().map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
    let read = matches!(action.as_deref(), Some("show" | "search" | "index"));
    let project = arg("projectId")
        .or_else(|| ctx.project_id.clone().map(|s| s.trim().to_string()).filter(|s| !s.is_empty()))
        .or_else(|| (read || scope.as_deref() == Some("global")).then(|| GLOBAL_PROJECT.to_string()))
        .ok_or_else(|| format!("{TOOL_NAME}: no project (this session has none; pass projectId)"))?;
    if let Some(s) = &scope {
        if !TOOL_SCOPES.contains(&s.as_str()) {
            return Err(format!("{TOOL_NAME}: scope must be one of {}", TOOL_SCOPES.join("|")));
        }
    }
    // a frame body carrying the same fields the CLI sends; the tool reuses the frame rules
    let base = |extra: Value| {
        let mut o = json!({ "projectId": project, "scope": scope, "sessionId": session, "role": role });
        if let (Some(m), Some(e)) = (o.as_object_mut(), extra.as_object()) {
            for (k, v) in e {
                m.insert(k.clone(), v.clone());
            }
        }
        o.to_string()
    };
    let text = |r: Result<Option<Reply>, String>| -> Result<(String, String), String> {
        let rep = r?.and_then(|r| r.reply).ok_or("no reply")?;
        if rep.0 == "error" { Err(rep.1) } else { Ok(rep) }
    };
    let call = |ty: &str, body: String| text(Ok(handle(db, p, ty, &body)));
    match action.as_deref() {
        Some("show") => {
            let n = arg("name").ok_or_else(|| format!("{TOOL_NAME} show needs name"))?;
            let mut last = String::new();
            let scopes: Vec<String> = scope.clone().map(|s| vec![s]).unwrap_or_else(|| vec!["role".into(), "project".into(), "session".into()]);
            for s in scopes {
                let body = json!({ "projectId": project, "name": n, "scope": s, "sessionId": session, "role": role }).to_string();
                match call("memory_get", body) {
                    Ok((_, data)) => {
                        let o: Value = serde_json::from_str(&data).unwrap_or(Value::Null);
                        return Ok(format!("SCOPE: {s}\nNAME: {n}\n\n{}", o["body"].as_str().unwrap_or("")));
                    }
                    Err(e) => last = e,
                }
            }
            Err(if last.is_empty() { format!("{TOOL_NAME}: no note '{n}'") } else { last })
        }
        Some("search") => {
            let q = arg("query").ok_or_else(|| format!("{TOOL_NAME} search needs query"))?;
            call("memory_search", base(json!({ "query": q }))).map(|r| r.1)
        }
        Some("index") => call("memory_list", base(json!({}))).map(|r| r.1),
        Some(act @ ("write" | "update")) => {
            let n = arg("name").ok_or_else(|| format!("{TOOL_NAME} {act} needs name"))?;
            let extra = if act == "write" {
                let d = arg("description").ok_or_else(|| format!("{TOOL_NAME} write needs description"))?;
                let b = opt_str(&a, "body").ok_or_else(|| format!("{TOOL_NAME} write needs body"))?;
                json!({ "name": n, "description": d, "body": b, "folder": arg("folder") })
            } else {
                let c = opt_str(&a, "content").ok_or_else(|| format!("{TOOL_NAME} update needs content"))?;
                let f = opt_str(&a, "find").filter(|s| !s.is_empty());
                json!({ "name": n, "description": arg("description"), "content": c, "mode": if f.is_some() { "edit" } else { "append" }, "find": f, "folder": arg("folder") })
            };
            call("memory_save", base(extra))?;
            Ok(format!("saved {} note '{n}'", scope.as_deref().unwrap_or("project")))
        }
        Some("delete") => {
            let n = arg("name").ok_or_else(|| format!("{TOOL_NAME} delete needs name"))?;
            call("memory_delete", base(json!({ "name": n })))?;
            Ok(format!("deleted {} note '{n}'", scope.as_deref().unwrap_or("project")))
        }
        _ => Err(format!("{TOOL_NAME}: action must be one of {}", ACTIONS.join("|"))),
    }
}
