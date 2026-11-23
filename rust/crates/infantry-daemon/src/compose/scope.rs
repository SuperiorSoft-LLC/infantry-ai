// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The host-side scope of a frame a plugin asks for a caller (Kotlin `LocalSpawns` + `KillScope` + `ProjectScope`):
//! a plugin working for an agent session may do no more than that session could itself.
//!
//! - **Spawn depth**: a `new` (or `ticket_start`) for a session at depth `d` makes a child at `d + 1`; above the setting
//!   `limits.concurrency.maxDepth` (default [`MAX_DEPTH`]) it is refused. Depth is the session's place in the
//!   recorded `spawnerSessionId` chain.
//! - **Kill scope**: `kill` / `session_delete`. No session may kill itself; a session may kill the sessions it spawned;
//!   a privileged role ([`PRIVILEGED_ROLES`]) any session in its own project; no one else.
//! - **Project scope**: a frame naming a `projectId` other than the caller's is refused unless the caller's role
//!   crosses projects ([`CROSS_PROJECT_ROLES`]).
//!
//! The owner (no session) and a plugin's own work (no caller) are not scoped.
use infantry_plugin_host::CallContext;
use infantry_protocol::session::SessionRecord;
use serde_json::Value;

/// `limits.concurrency.maxDepth` (LimitRegistry): how deep a session spawned through a tool may sit.
pub const MAX_DEPTH_KEY: &str = "limits.concurrency.maxDepth";
pub const MAX_DEPTH: i64 = 1;
/// Roles that may kill any session of their own project (`ProjectScope.PRIVILEGED_ROLES`).
pub const PRIVILEGED_ROLES: [&str; 3] = ["pm", "owner", "assistant"];
/// Roles that may reach another project (`ProjectScope.CROSS_PROJECT_ROLES`).
pub const CROSS_PROJECT_ROLES: [&str; 2] = ["owner", "assistant"];
/// Frames that create a session.
const SPAWNS: [&str; 2] = [crate::wire::NEW, "ticket_start"];
/// Frames that end a session.
const KILLS: [&str; 2] = [crate::wire::KILL, crate::wire::SESSION_DELETE];

fn blank(s: &Option<String>) -> Option<&str> {
    s.as_deref().map(str::trim).filter(|s| !s.is_empty())
}

/// The calling session, None for the owner or no caller.
fn caller_session(ctx: Option<&CallContext>) -> Option<&str> {
    let ctx = ctx?;
    blank(&ctx.client_session_id).or_else(|| blank(&ctx.caller.active_session))
}

/// The session's depth in the spawner chain (a root session is 0; a broken or cyclic chain stops where it breaks).
pub fn depth_of(id: &str, records: &[SessionRecord]) -> i64 {
    let mut depth = 0;
    let mut at = id.to_string();
    let mut seen = std::collections::HashSet::new();
    while seen.insert(at.clone()) {
        let Some(parent) = records.iter().find(|r| r.id == at).and_then(|r| blank(&r.spawner_session_id).map(str::to_string)) else { break };
        depth += 1;
        at = parent;
    }
    depth
}

/// The session a `kill` / `session_delete` names: the frame's session id, else the first word of its data.
fn kill_target(frame_session: Option<&str>, data: &str) -> Option<String> {
    frame_session.map(str::to_string).or_else(|| data.split_whitespace().next().map(str::to_string)).filter(|s| !s.is_empty())
}

/// SEC-PLUGIN-1 / SVC-SECRET: `plugin_secret_get`, `_set` and `_clear` from a plugin reach the secret of THAT plugin and no other. The module comes from the
/// host (the manifest's module id), never from the request: a request that names another module is refused, one that
/// names none is filled in. Returns the request data to dispatch.
pub fn own_secret_request(frame: &str, module: &str, data: &str) -> Result<String, String> {
    let mut v: Value = if data.trim().is_empty() { Value::Object(Default::default()) } else { serde_json::from_str(data).map_err(|e| format!("{frame} refused: malformed request ({e})"))? };
    let Some(o) = v.as_object_mut() else { return Err(format!("{frame} refused: malformed request")) };
    if let Some(named) = o.get("module").and_then(Value::as_str).map(str::trim).filter(|m| !m.is_empty()) {
        if !named.eq_ignore_ascii_case(module) {
            return Err(format!("{frame} refused: a plugin reaches only its own secrets ({module}), not those of {named}"));
        }
    }
    o.insert("module".into(), module.into());
    Ok(v.to_string())
}

/// Why `caller` may not send this frame, or None when it may.
pub fn refusal(ty: &str, data: &str, frame_session: Option<&str>, ctx: Option<&CallContext>, records: &[SessionRecord], max_depth: i64) -> Option<String> {
    let me = caller_session(ctx)?;
    let rec = records.iter().find(|r| r.id == me);
    let role = rec.and_then(|r| blank(&r.role)).or_else(|| ctx.and_then(|c| blank(&c.client_role))).map(str::to_lowercase);
    let role_str = role.as_deref().unwrap_or("worker");
    let own_project = rec.and_then(|r| blank(&r.project_id)).or_else(|| ctx.and_then(|c| blank(&c.client_project_id))).map(str::to_string);
    let crosses = CROSS_PROJECT_ROLES.contains(&role_str);

    if !crosses {
        let named = serde_json::from_str::<Value>(data).ok().and_then(|v| v.get("projectId").and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty()).map(str::to_string));
        if let (Some(named), Some(own)) = (named, own_project.as_deref()) {
            if named != own {
                return Some(format!("{ty} refused: project '{named}' is not this session's project '{own}'; a {role_str} may not reach another project"));
            }
        }
    }
    if SPAWNS.contains(&ty) {
        let here = depth_of(me, records);
        let child = here + 1;
        if child > max_depth {
            return Some(format!(
                "{ty} refused: depth limit. Session {me} runs at depth {here}, so the new session would be at depth {child}, above the maximum of {max_depth} ({MAX_DEPTH_KEY})"
            ));
        }
    }
    if KILLS.contains(&ty) {
        let target = kill_target(frame_session, data)?;
        if target == me {
            return Some(format!("{ty} refused: session {target} is this session itself; an agent may not kill its own session. End your turn instead, or ask the owner to stop it."));
        }
        let t = records.iter().find(|r| r.id == target);
        if t.and_then(|t| blank(&t.spawner_session_id)) == Some(me) {
            return None;
        }
        if PRIVILEGED_ROLES.contains(&role_str) {
            let theirs = t.and_then(|t| blank(&t.project_id));
            return match own_project.as_deref() {
                Some(own) if theirs == Some(own) => None,
                own => Some(format!(
                    "{ty} refused: session {target} belongs to project '{}', not this session's project '{}'; a pm may only kill sessions in its own project",
                    theirs.unwrap_or("none"),
                    own.unwrap_or("none")
                )),
            };
        }
        return Some(format!("{ty} refused: session {target} was not spawned by this session ({me}); a {role_str} may only kill sessions it spawned itself"));
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn rec(id: &str, spawner: Option<&str>, role: Option<&str>, project: &str) -> SessionRecord {
        let mut v = serde_json::json!({ "id": id, "projectId": project, "provider": "claude", "workingDir": ".", "spawnedAt": 0, "lastHeartbeat": 0, "state": "IDLE" });
        if let Some(s) = spawner {
            v["spawnerSessionId"] = s.into();
        }
        if let Some(r) = role {
            v["role"] = r.into();
        }
        serde_json::from_value(v).unwrap()
    }

    fn by(id: &str) -> CallContext {
        CallContext { client_session_id: Some(id.into()), ..Default::default() }
    }

    #[test]
    fn a_plugin_reads_only_its_own_secret() {
        let own = own_secret_request("plugin_secret_get", "Acme_X", r#"{"field":"TOKEN"}"#).unwrap();
        assert_eq!(serde_json::from_str::<Value>(&own).unwrap(), serde_json::json!({ "module": "Acme_X", "field": "TOKEN" }));
        assert!(own_secret_request("plugin_secret_get", "Acme_X", r#"{"module":"acme_x","field":"T"}"#).is_ok());
        assert!(own_secret_request("plugin_secret_get", "Acme_X", r#"{"module":"Other_Y","field":"T"}"#).unwrap_err().contains("only its own"));
        assert!(own_secret_request("plugin_secret_get", "Acme_X", "[1]").is_err() && own_secret_request("plugin_secret_get", "Acme_X", "{").is_err());
    }

    #[test]
    fn owner_and_plugin_work_are_not_scoped() {
        assert_eq!(refusal("kill", "x", None, None, &[], 1), None);
        assert_eq!(refusal("kill", "x", None, Some(&CallContext::default()), &[], 1), None);
    }

    #[test]
    fn depth_kill_and_project_rules() {
        let rs = vec![rec("pm", None, Some("pm"), "p"), rec("w", Some("pm"), Some("dev"), "p"), rec("o", None, Some("dev"), "q")];
        assert_eq!(depth_of("w", &rs), 1);
        assert_eq!(refusal("new", "{}", None, Some(&by("pm")), &rs, 1), None);
        assert!(refusal("new", "{}", None, Some(&by("w")), &rs, 1).unwrap().contains("depth limit"));
        assert!(refusal("kill", "", Some("w"), Some(&by("w")), &rs, 1).unwrap().contains("itself"));
        assert_eq!(refusal("kill", "w", None, Some(&by("pm")), &rs, 1), None);
        assert!(refusal("kill", "o", None, Some(&by("pm")), &rs, 1).unwrap().contains("own project"));
        assert!(refusal("session_delete", "pm", None, Some(&by("w")), &rs, 1).unwrap().contains("not spawned by"));
        assert!(refusal("ticket_list", r#"{"projectId":"q"}"#, None, Some(&by("w")), &rs, 1).unwrap().contains("another project"));
        assert_eq!(refusal("ticket_list", r#"{"projectId":"p"}"#, None, Some(&by("w")), &rs, 1), None);
    }
}
