// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The host seam: everything a script reaches (`ih.*`) goes through [`ScriptHost`].
//!
//! A script never runs a tool itself: it queues actions, and the [`crate::dispatcher::ScriptDispatcher`]
//! runs them through the host after the script ends. The daemon implements [`ScriptHost`] over its live
//! tool registry; tests use [`FakeHost`].
use serde_json::{Map, Value};
use std::sync::{Arc, Mutex};

/// Who a tool call runs as. Mirrors the Kotlin `ToolInvoker` + `Caller` pair, flattened.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ToolInvoker {
    /// Owner permissions (the project's automation identity, or the human owner).
    pub owner: bool,
    /// The call comes from an agent session.
    pub agent: bool,
    /// The session the caller acts as (an agent's own session, or the session a hook acts for).
    pub active_session: Option<String>,
    pub session_id: Option<String>,
    pub role: Option<String>,
    pub project_id: Option<String>,
    /// Audit label, e.g. `script:on-done`.
    pub label: String,
}

impl ToolInvoker {
    /// The automation identity of a project (owner permissions, no session).
    pub fn automation(project: Option<&str>, label: impl Into<String>) -> Self {
        ToolInvoker { owner: true, project_id: project.map(str::to_string), label: label.into(), ..Default::default() }
    }

    /// An agent session's identity.
    pub fn agent(session: &str, role: &str, project: Option<&str>, label: impl Into<String>) -> Self {
        ToolInvoker {
            owner: false,
            agent: true,
            active_session: Some(session.to_string()),
            session_id: Some(session.to_string()),
            role: Some(role.to_string()),
            project_id: project.map(str::to_string),
            label: label.into(),
        }
    }
}

/// One entry of the live tool registry.
#[derive(Debug, Clone, PartialEq)]
pub struct ToolInfo {
    pub name: String,
    pub description: String,
    /// JSON Schema of the arguments (`{"type":"object","properties":{...}}`).
    pub input_schema: Value,
    pub read_only: bool,
}

impl ToolInfo {
    pub fn named(name: impl Into<String>) -> Self {
        let name = name.into();
        ToolInfo { description: name.clone(), name, input_schema: Value::Object(Map::new()), read_only: false }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ToolResult {
    pub text: String,
    pub is_error: bool,
}

impl ToolResult {
    pub fn ok(text: impl Into<String>) -> Self {
        ToolResult { text: text.into(), is_error: false }
    }
    pub fn err(text: impl Into<String>) -> Self {
        ToolResult { text: text.into(), is_error: true }
    }
}

/// The services a script run needs from the daemon.
pub trait ScriptHost: Send + Sync {
    /// The live tool registry (names resolve against it at every run).
    fn catalog(&self) -> Vec<ToolInfo>;
    /// Runs one tool as `invoker`. Never panics; a failure is `is_error`.
    fn invoke(&self, name: &str, args: &Map<String, Value>, invoker: &ToolInvoker) -> ToolResult;
    /// The project a session belongs to, `None` when unknown (lets `infantry_progress` target another session of the same project).
    fn session_project(&self, _session: &str) -> Option<String> {
        None
    }
}

/// A recording host for tests: answers by tool name (default `{}`), records every call.
#[derive(Clone, Default)]
pub struct FakeHost {
    pub tools: Vec<String>,
    pub answers: std::collections::HashMap<String, ToolResult>,
    pub calls: Arc<Mutex<Vec<(String, Map<String, Value>, ToolInvoker)>>>,
}

impl FakeHost {
    pub fn new(tools: &[&str]) -> Self {
        FakeHost { tools: tools.iter().map(|s| s.to_string()).collect(), ..Default::default() }
    }
    pub fn answer(mut self, tool: &str, text: &str) -> Self {
        self.answers.insert(tool.to_string(), ToolResult::ok(text));
        self
    }
    pub fn fail(mut self, tool: &str, text: &str) -> Self {
        self.answers.insert(tool.to_string(), ToolResult::err(text));
        self
    }
    pub fn calls(&self) -> Vec<(String, Map<String, Value>)> {
        self.calls.lock().unwrap().iter().map(|(n, a, _)| (n.clone(), a.clone())).collect()
    }
    pub fn named(&self, tool: &str) -> Vec<Map<String, Value>> {
        self.calls().into_iter().filter(|(n, _)| n == tool).map(|(_, a)| a).collect()
    }
}

impl ScriptHost for FakeHost {
    fn catalog(&self) -> Vec<ToolInfo> {
        self.tools.iter().map(ToolInfo::named).collect()
    }
    fn invoke(&self, name: &str, args: &Map<String, Value>, invoker: &ToolInvoker) -> ToolResult {
        self.calls.lock().unwrap().push((name.to_string(), args.clone(), invoker.clone()));
        self.answers.get(name).cloned().unwrap_or_else(|| ToolResult::ok("{}"))
    }
}
