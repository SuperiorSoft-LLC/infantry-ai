// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The daemon's one tool catalog: the scripting tools and every loaded plugin's tools. Scripts call through it
//! ([`ScriptHost`]); MCP bridges list and call it over `plugin_tools` / `plugin_tool_call` ([`ToolsUnit`]).

use crate::sessions::Daemon;
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_plugin_host::{CallContext, Caller, PluginHost};
use infantry_scripting::host::{ScriptHost, ToolInfo, ToolInvoker, ToolResult};
use infantry_scripting::tools::ScriptingTools;
use serde_json::{json, Map, Value};
use std::sync::{Arc, OnceLock};

pub const PLUGIN_TOOLS: &str = "plugin_tools";
pub const PLUGIN_TOOL_CALL: &str = "plugin_tool_call";
pub const PLUGIN_TOOL_RESULT: &str = "plugin_tool_result";
/// A loaded plugin's capability by id (`{id, args}` -> `capability_result` `{text}`): how one plugin reaches what another
/// declares without knowing which module it is (a trigger's plugin action kind, for one).
pub const CAPABILITY_CALL: &str = "capability_call";
pub const CAPABILITY_RESULT: &str = "capability_result";
/// CHAT-IMG-2: an image (or a card with an image) into a session's chat, for plugins and clients:
/// `{sessionId, path | data | url, caption?, level?}` -> `chat_image_result` `{text, isError}`. The same capability as the
/// `infantry_chat_image` tool and `ih.chat_image`, with the same checks; the post is in the target session's project.
pub const CHAT_IMAGE: &str = "chat_image";
pub const CHAT_IMAGE_RESULT: &str = "chat_image_result";

/// AR-8: the communication family (AR-6) every agent keeps, whatever its role's `tools` list names.
pub const COMMUNICATION_TOOLS: &[&str] =
    &["infantry_list_sessions", "infantry_get_session", "infantry_message", "infantry_inbox", "infantry_read", "infantry_ask"];

/// AR-8: whether [name] passes an agent's allow-list (`None`: the role names no tools, so every tool passes).
pub fn tool_allowed(allow: Option<&[String]>, name: &str) -> bool {
    match allow {
        None => true,
        Some(list) => COMMUNICATION_TOOLS.contains(&name) || list.iter().any(|t| t == name),
    }
}

/// AR-8: the refusal for a tool the agent's role does not list.
pub fn not_enabled(name: &str) -> String {
    format!("tool {name} is not enabled for this agent")
}

pub struct Tools {
    daemon: Arc<Daemon>,
    scripting: OnceLock<ScriptingTools>,
    plugins: OnceLock<Option<Arc<PluginHost>>>,
    knowledge: OnceLock<Arc<infantry_knowledge::Knowledge>>,
    runs: OnceLock<Arc<crate::run::RunManager>>,
    agents: OnceLock<Arc<super::agent_tools::AgentTools>>,
    harness: OnceLock<Arc<super::harness_install::HarnessInstall>>,
}

impl Tools {
    pub fn new(daemon: Arc<Daemon>) -> Tools {
        Tools { daemon, scripting: OnceLock::new(), plugins: OnceLock::new(), knowledge: OnceLock::new(), runs: OnceLock::new(), agents: OnceLock::new(), harness: OnceLock::new() }
    }

    pub fn set_scripting(&self, t: ScriptingTools) {
        let _ = self.scripting.set(t);
    }

    pub fn set_knowledge(&self, k: Arc<infantry_knowledge::Knowledge>) {
        let _ = self.knowledge.set(k);
    }

    pub fn set_runs(&self, r: Arc<crate::run::RunManager>) {
        let _ = self.runs.set(r);
    }

    pub fn set_agents(&self, a: Arc<super::agent_tools::AgentTools>) {
        let _ = self.agents.set(a);
    }

    pub fn set_harness_install(&self, h: Arc<super::harness_install::HarnessInstall>) {
        let _ = self.harness.set(h);
    }

    pub fn set_plugins(&self, h: Option<Arc<PluginHost>>) {
        let _ = self.plugins.set(h);
    }

    fn plugins(&self) -> Option<&Arc<PluginHost>> {
        self.plugins.get().and_then(|p| p.as_ref())
    }

    /// The `plugin_tools` descriptors (MCP tool shape).
    pub fn descriptors(&self) -> Vec<Value> {
        // AR-6: a board tool's `instructions` paragraph rides along, so the MCP server instructions carry the board's
        // text only when the board tools are in the catalog.
        let paragraphs = super::agent_tools::board_instructions();
        self.catalog()
            .into_iter()
            .map(|t| {
                let paragraph = paragraphs.get(&t.name).cloned();
                let mut d = json!({
                    "name": t.name, "description": t.description, "inputSchema": t.input_schema,
                    "annotations": {"readOnlyHint": t.read_only},
                });
                // A plugin tool that declares its own budget tells the caller how long to wait for it.
                let own = self.plugins().and_then(|h| h.tools().into_iter().find(|e| e.decl.name == t.name).filter(|e| e.decl.timeout_ms.is_some()).and_then(|e| h.get(&e.module_id).map(|p| p.tool_timeout_ms(&t.name))));
                if let Some(ms) = own {
                    d["timeoutMs"] = json!(ms);
                }
                if let Some(p) = paragraph {
                    d["instructions"] = p;
                }
                d
            })
            .collect()
    }
}

fn plugin_context(inv: &ToolInvoker) -> CallContext {
    CallContext {
        caller: Caller { owner: inv.owner, agent: inv.agent, active_session: inv.active_session.clone(), project: inv.project_id.clone(), ..Default::default() },
        client_session_id: inv.session_id.clone(),
        client_role: inv.role.clone(),
        client_project_id: inv.project_id.clone(),
    }
}

impl Tools {
    /// `infantry_capability_list`: every capability the loaded plugins declare, as a JSON array.
    fn capability_list(&self) -> String {
        let mut out = Vec::new();
        if let Some(h) = self.plugins() {
            for p in h.loaded() {
                for c in &p.manifest.capabilities {
                    let available = !p.guard.is_disabled() && (c.runs != "daemon" || p.manifest.capability_tool(&c.id).is_some());
                    out.push(json!({ "id": c.id, "module": p.id, "runs": c.runs, "action": c.action, "available": available }));
                }
            }
        }
        Value::Array(out).to_string()
    }
}

impl ScriptHost for Tools {
    fn catalog(&self) -> Vec<ToolInfo> {
        let mut out = self.scripting.get().map(|s| s.catalog()).unwrap_or_default();
        if self.knowledge.get().is_some() {
            for d in infantry_knowledge::tools::descriptors() {
                let name = d["name"].as_str().unwrap_or_default().to_string();
                if name.is_empty() || out.iter().any(|o| o.name == name) {
                    continue;
                }
                out.push(ToolInfo {
                    description: d["description"].as_str().unwrap_or_default().to_string(),
                    input_schema: d.get("inputSchema").cloned().unwrap_or_else(|| json!({"type": "object"})),
                    read_only: d["annotations"]["readOnlyHint"].as_bool().unwrap_or(false),
                    name,
                });
            }
        }
        if self.runs.get().is_some() {
            for t in crate::run::tools::catalog() {
                if !out.iter().any(|o| o.name == t.name) {
                    out.push(t);
                }
            }
        }
        if self.agents.get().is_some() {
            for t in super::agent_tools::board_catalog() {
                if !out.iter().any(|o| o.name == t.name) {
                    out.push(t);
                }
            }
        }
        if self.harness.get().is_some() {
            for t in harness_tool_catalog() {
                if !out.iter().any(|o| o.name == t.name) {
                    out.push(t);
                }
            }
        }
        if let Some(h) = self.plugins() {
            for t in h.tools() {
                if out.iter().any(|o| o.name == t.decl.name) {
                    continue;
                }
                out.push(ToolInfo {
                    description: if t.decl.description.is_empty() { t.decl.name.clone() } else { t.decl.description.clone() },
                    input_schema: t.decl.input_schema.clone().unwrap_or_else(|| json!({"type": "object"})),
                    read_only: t.decl.kind == "read",
                    name: t.decl.name.clone(),
                });
            }
        }
        // Fallbacks: a plugin that declares the same tool serves it instead.
        if self.agents.get().is_some() {
            for t in super::agent_tools::agent_catalog() {
                if !out.iter().any(|o| o.name == t.name) {
                    out.push(t);
                }
            }
        }
        out
    }

    fn invoke(&self, name: &str, args: &Map<String, Value>, inv: &ToolInvoker) -> ToolResult {
        if let Some(s) = self.scripting.get() {
            if let Some(r) = s.handle(name, args, inv) {
                return r;
            }
        }
        if let Some(k) = self.knowledge.get() {
            let caller = infantry_knowledge::tools::Caller { agent: inv.agent, project_id: inv.project_id.clone(), role: inv.role.clone() };
            if let Some(r) = infantry_knowledge::tools::call(k, name, &Value::Object(args.clone()), &caller) {
                return match r {
                    Ok(text) => ToolResult::ok(text),
                    Err(e) => ToolResult::err(e),
                };
            }
        }
        if let Some(r) = self.runs.get().and_then(|m| crate::run::tools::invoke(m, name, args, inv)) {
            return r;
        }
        if let Some(r) = self.agents.get().and_then(|a| a.board_call(name, args, inv)) {
            return r;
        }
        if let Some(r) = self.harness.get().and_then(|h| harness_tool_call(h, name, args, inv.owner)) {
            return r;
        }
        if let Some(h) = self.plugins() {
            if h.tool_owner(name).is_some() {
                let a = Value::Object(args.clone()).to_string();
                return match h.call_tool(name, &a, &plugin_context(inv)) {
                    Ok(text) => ToolResult::ok(text),
                    Err(e) => ToolResult::err(e.message().to_string()),
                };
            }
        }
        if name == "infantry_capability_list" {
            return ToolResult::ok(self.capability_list());
        }
        if let Some(r) = self.agents.get().and_then(|a| a.call(name, args, inv)) {
            return r;
        }
        ToolResult::err(format!("{name} refused: no such tool on this daemon"))
    }
}

impl Tools {
    /// AR-8: the allow-list of the agent behind [session]: its role's `tools` (the session record's role, else the
    /// client's), or None when there is no session, no such role, or the role names no tools (the full set).
    pub fn allow_list(&self, session: Option<&str>, client_role: Option<&str>) -> Option<Vec<String>> {
        let session = session.filter(|s| !s.trim().is_empty())?;
        let role = self.daemon.record(session).and_then(|r| r.role).filter(|r| !r.trim().is_empty()).or_else(|| client_role.map(str::to_string))?;
        let tools = self.knowledge.get()?.roles.role(&role)?.tools;
        (!tools.is_empty()).then_some(tools)
    }

    /// The `plugin_tools` descriptors this caller may use (AR-8).
    pub fn descriptors_for(&self, session: Option<&str>, client_role: Option<&str>) -> Vec<Value> {
        let allow = self.allow_list(session, client_role);
        self.descriptors().into_iter().filter(|d| tool_allowed(allow.as_deref(), d.get("name").and_then(Value::as_str).unwrap_or(""))).collect()
    }

    fn session_project(&self, session: &str) -> Option<String> {
        self.daemon.record(session)?.project_id
    }
}

/// `plugin_tools` (the catalog) and `plugin_tool_call` (`{name, arguments, client}` -> `plugin_tool_result`).
pub struct ToolsUnit {
    pub tools: Arc<Tools>,
}

fn reply(env: &Envelope, ty: &str, data: String) -> Envelope {
    let mut e = Envelope::new(ty, data);
    e.request_id = env.request_id.clone();
    e.session_id = env.session_id.clone();
    e
}

impl FrameUnit for ToolsUnit {
    fn name(&self) -> &'static str {
        "tools"
    }
    fn frames(&self) -> &'static [&'static str] {
        &[PLUGIN_TOOLS, PLUGIN_TOOL_CALL, CAPABILITY_CALL, CHAT_IMAGE]
    }
    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        match env.r#type.as_str() {
            PLUGIN_TOOLS => {
                // AR-8: an agent's client (`{client: {sessionId, role}}`) gets only the tools its role enables.
                let v: Value = serde_json::from_str(&env.data).unwrap_or(Value::Null);
                let s = |k: &str| v.get("client").and_then(|c| c.get(k)).and_then(Value::as_str).map(str::to_string);
                let list = self.tools.descriptors_for(s("sessionId").as_deref(), s("role").as_deref());
                Ok(vec![reply(env, PLUGIN_TOOLS, Value::Array(list).to_string())])
            }
            CHAT_IMAGE => {
                let v: Value = serde_json::from_str(&env.data).map_err(|e| Refusal::new(CHAT_IMAGE, format!("bad payload ({e})")))?;
                let mut args = v.as_object().cloned().ok_or_else(|| Refusal::new(CHAT_IMAGE, "the payload is not an object"))?;
                let session = args.get("sessionId").and_then(Value::as_str).map(str::to_string).or_else(|| env.session_id.clone()).ok_or_else(|| Refusal::new(CHAT_IMAGE, "needs sessionId"))?;
                // CHAT-IMG-4: "starter" and project-less sessions resolve in the chat tool itself. The frame acts as the
                // calling session (the envelope's), else as the target session; the project is the target's (as before), else the caller's.
                let caller = env.session_id.clone().filter(|s| !s.trim().is_empty()).or_else(|| (!session.eq_ignore_ascii_case("starter")).then(|| session.clone()));
                let project = self.tools.session_project(&session).or_else(|| caller.as_deref().and_then(|c| self.tools.session_project(c)));
                args.insert("sessionId".into(), json!(session));
                let inv = ToolInvoker { project_id: project, session_id: caller, label: format!("frame:{CHAT_IMAGE}"), ..Default::default() };
                let r = self.tools.invoke(infantry_scripting::chat::IMAGE, &args, &inv);
                Ok(vec![reply(env, CHAT_IMAGE_RESULT, json!({"text": r.text, "isError": r.is_error}).to_string())])
            }
            CAPABILITY_CALL => {
                let v: Value = serde_json::from_str(&env.data).map_err(|e| Refusal::new(CAPABILITY_CALL, format!("bad payload ({e})")))?;
                let id = v.get("id").and_then(Value::as_str).ok_or_else(|| Refusal::new(CAPABILITY_CALL, "no capability id"))?;
                let args = v.get("args").cloned().unwrap_or_else(|| json!({})).to_string();
                let host = self.tools.plugins().ok_or_else(|| Refusal::new(CAPABILITY_CALL, format!("{id} refused: no loaded plugin answers this capability")))?;
                let ctx = CallContext { caller: Caller { owner: ctx.owner, ..Default::default() }, ..Default::default() };
                let text = host.call_capability(id, &args, &ctx).map_err(|e| Refusal(e.message().to_string()))?;
                Ok(vec![reply(env, CAPABILITY_RESULT, json!({ "text": text }).to_string())])
            }
            _ => {
                let v: Value = serde_json::from_str(&env.data).map_err(|e| Refusal::new(PLUGIN_TOOL_CALL, format!("bad payload ({e})")))?;
                let name = v.get("name").and_then(Value::as_str).ok_or_else(|| Refusal::new(PLUGIN_TOOL_CALL, "no tool name"))?;
                let args = v.get("arguments").and_then(Value::as_object).cloned().unwrap_or_default();
                let client = v.get("client").and_then(Value::as_object).cloned().unwrap_or_default();
                let s = |k: &str| client.get(k).and_then(Value::as_str).map(str::to_string).filter(|x| !x.trim().is_empty());
                let session = s("sessionId");
                // AR-8: enforced here, not only by hiding the tool from the list.
                if !tool_allowed(self.tools.allow_list(session.as_deref(), s("role").as_deref()).as_deref(), name) {
                    return Ok(vec![reply(env, PLUGIN_TOOL_RESULT, json!({"text": not_enabled(name), "isError": true}).to_string())]);
                }
                let inv = ToolInvoker {
                    // OWNER-SESSION-GUARD-1: the owner's connection with no session is the owner's own client.
                    owner_client: ctx.owner && session.is_none(),
                    owner: ctx.owner && session.is_none(),
                    agent: session.is_some(),
                    active_session: session.clone(),
                    session_id: session,
                    role: s("role"),
                    project_id: s("projectId"),
                    label: format!("tool:{name}"),
                };
                let r = self.tools.invoke(name, &args, &inv);
                Ok(vec![reply(env, PLUGIN_TOOL_RESULT, json!({"text": r.text, "isError": r.is_error}).to_string())])
            }
        }
    }
}

/// `infantry_harness_status` / `infantry_harness_install`: the setup wizard's report and install, for agents.
fn harness_tool_catalog() -> Vec<ToolInfo> {
    vec![
        ToolInfo {
            name: "infantry_harness_status".into(),
            description: "Which harness CLIs are installed on the daemon's machine, their versions, where each runs from, and whether Node/npm is there: the report the setup wizard shows (harness_install_status). Install missing ones with infantry_harness_install.".into(),
            input_schema: json!({"type": "object", "properties": {}, "additionalProperties": false}),
            read_only: true,
        },
        ToolInfo {
            name: "infantry_harness_install".into(),
            description: "Owner only. Start installing harness CLIs on the daemon's machine into Infantry AI's own folder (no admin rights; the user's own installs are never shadowed). ids: which ones (default: every missing one). update=true also refreshes outdated ones. Runs in the background: poll infantry_harness_status until it is no longer busy. Sign-in stays the user's.".into(),
            input_schema: json!({"type": "object", "properties": {
                "ids": {"type": "array", "items": {"type": "string"}, "description": "Harness ids (infantry_harness_status lists them). Default: all missing."},
                "update": {"type": "boolean", "description": "Also update outdated CLIs (default false)."}}, "additionalProperties": false}),
            read_only: false,
        },
    ]
}

fn harness_tool_call(h: &super::harness_install::HarnessInstall, name: &str, args: &Map<String, Value>, owner: bool) -> Option<ToolResult> {
    match name {
        "infantry_harness_status" => Some(ToolResult::ok(h.status_report().to_string())),
        "infantry_harness_install" if !owner => Some(ToolResult::err("infantry_harness_install refused: installing software on the daemon's machine needs the owner credential; agents do not hold it")),
        "infantry_harness_install" => {
            let ids: Vec<String> = args.get("ids").and_then(Value::as_array).map(|a| a.iter().filter_map(|v| v.as_str().map(|s| s.trim().to_string())).filter(|s| !s.is_empty()).collect()).unwrap_or_default();
            Some(match h.begin(ids, matches!(args.get("update"), Some(Value::Bool(true)))) {
                Ok(()) => ToolResult::ok(json!({"started": true}).to_string()),
                Err(e) => ToolResult::err(format!("infantry_harness_install refused: {e}")),
            })
        }
        _ => None,
    }
}

#[cfg(test)]
mod harness_tool_tests {
    use super::*;

    #[test]
    fn both_harness_tools_answer_and_install_refuses_an_unknown_harness() {
        let dir = std::env::temp_dir().join(format!("hi-tools-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let h = super::super::harness_install::HarnessInstall::new(&dir, Arc::new(|| {}), Arc::new(|| {}));
        assert_eq!(harness_tool_catalog().iter().map(|t| (t.name.as_str(), t.read_only)).collect::<Vec<_>>(), [("infantry_harness_status", true), ("infantry_harness_install", false)]);
        let st = harness_tool_call(&h, "infantry_harness_status", &Map::new(), false).unwrap();
        assert!(!st.is_error && st.text.starts_with('{'), "{}", st.text);
        let mut a = Map::new();
        a.insert("ids".into(), json!(["nope"]));
        // An agent (no owner credential) is refused before anything runs.
        let agent = harness_tool_call(&h, "infantry_harness_install", &a, false).unwrap();
        assert!(agent.is_error && agent.text.contains("needs the owner credential"), "{}", agent.text);
        let bad = harness_tool_call(&h, "infantry_harness_install", &a, true).unwrap();
        assert!(bad.is_error && bad.text.contains("unknown harness 'nope'"), "{}", bad.text);
        assert!(harness_tool_call(&h, "infantry_other", &a, true).is_none());
        let _ = std::fs::remove_dir_all(dir);
    }
}
