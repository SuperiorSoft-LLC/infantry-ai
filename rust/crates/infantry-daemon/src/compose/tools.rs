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

pub struct Tools {
    daemon: Arc<Daemon>,
    scripting: OnceLock<ScriptingTools>,
    plugins: OnceLock<Option<Arc<PluginHost>>>,
}

impl Tools {
    pub fn new(daemon: Arc<Daemon>) -> Tools {
        Tools { daemon, scripting: OnceLock::new(), plugins: OnceLock::new() }
    }

    pub fn set_scripting(&self, t: ScriptingTools) {
        let _ = self.scripting.set(t);
    }

    pub fn set_plugins(&self, h: Option<Arc<PluginHost>>) {
        let _ = self.plugins.set(h);
    }

    fn plugins(&self) -> Option<&Arc<PluginHost>> {
        self.plugins.get().and_then(|p| p.as_ref())
    }

    /// The `plugin_tools` descriptors (MCP tool shape).
    pub fn descriptors(&self) -> Vec<Value> {
        self.catalog()
            .into_iter()
            .map(|t| {
                json!({
                    "name": t.name, "description": t.description, "inputSchema": t.input_schema,
                    "annotations": {"readOnlyHint": t.read_only},
                })
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

impl ScriptHost for Tools {
    fn catalog(&self) -> Vec<ToolInfo> {
        let mut out = self.scripting.get().map(|s| s.catalog()).unwrap_or_default();
        if let Some(h) = self.plugins() {
            for t in h.tools() {
                if out.iter().any(|o| o.name == t.decl.name) {
                    continue;
                }
                out.push(ToolInfo {
                    description: if t.decl.description.is_empty() { t.decl.name.clone() } else { t.decl.description.clone() },
                    input_schema: json!({"type": "object"}),
                    read_only: t.decl.kind == "read",
                    name: t.decl.name.clone(),
                });
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
        if let Some(h) = self.plugins() {
            if h.tool_owner(name).is_some() {
                let a = Value::Object(args.clone()).to_string();
                return match h.call_tool(name, &a, &plugin_context(inv)) {
                    Ok(text) => ToolResult::ok(text),
                    Err(e) => ToolResult::err(e.message().to_string()),
                };
            }
        }
        ToolResult::err(format!("{name} refused: no such tool on this daemon"))
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
        &[PLUGIN_TOOLS, PLUGIN_TOOL_CALL]
    }
    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        match env.r#type.as_str() {
            PLUGIN_TOOLS => Ok(vec![reply(env, PLUGIN_TOOLS, Value::Array(self.tools.descriptors()).to_string())]),
            _ => {
                let v: Value = serde_json::from_str(&env.data).map_err(|e| Refusal::new(PLUGIN_TOOL_CALL, format!("bad payload ({e})")))?;
                let name = v.get("name").and_then(Value::as_str).ok_or_else(|| Refusal::new(PLUGIN_TOOL_CALL, "no tool name"))?;
                let args = v.get("arguments").and_then(Value::as_object).cloned().unwrap_or_default();
                let client = v.get("client").and_then(Value::as_object).cloned().unwrap_or_default();
                let s = |k: &str| client.get(k).and_then(Value::as_str).map(str::to_string).filter(|x| !x.trim().is_empty());
                let session = s("sessionId");
                let inv = ToolInvoker {
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
