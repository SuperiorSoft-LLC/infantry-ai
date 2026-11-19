// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The engine contract every script language implements, and its plain data.
//!
//! A run is pure: the script sees `input` and QUEUES actions (`ih.call`), it never executes one.
//! The [`crate::dispatcher::ScriptDispatcher`] runs the queued actions after the script ends.
use serde_json::{Map, Value};

/// The sandbox limits of one run.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ScriptLimits {
    /// VM instructions before the run is killed (`BUDGET_EXCEEDED`).
    pub max_steps: u64,
    /// Wall clock before the run is killed (`TIMEOUT`).
    pub timeout_ms: u64,
    /// Live heap the script may hold on top of the fresh state (`ALLOC_EXCEEDED`).
    pub max_alloc_bytes: u64,
}

impl Default for ScriptLimits {
    fn default() -> Self {
        ScriptLimits { max_steps: 5_000_000, timeout_ms: 2_000, max_alloc_bytes: 64 * 1024 * 1024 }
    }
}

/// Who runs the script. Data only: an engine never checks it.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ScriptCaller {
    pub id: String,
    pub role: String,
}

impl ScriptCaller {
    pub fn new(id: impl Into<String>, role: impl Into<String>) -> Self {
        ScriptCaller { id: id.into(), role: role.into() }
    }
}

/// One action a script asked for (`ih.call(name, args)`, `ih.<alias>{...}`). Nothing runs inside the script.
#[derive(Debug, Clone, PartialEq)]
pub struct QueuedAction {
    pub name: String,
    pub args: Map<String, Value>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ScriptOutcome {
    Ok,
    Error,
    BudgetExceeded,
    Timeout,
    AllocExceeded,
}

impl ScriptOutcome {
    /// The wire / report name (the Kotlin enum names).
    pub fn as_str(self) -> &'static str {
        match self {
            ScriptOutcome::Ok => "OK",
            ScriptOutcome::Error => "ERROR",
            ScriptOutcome::BudgetExceeded => "BUDGET_EXCEEDED",
            ScriptOutcome::Timeout => "TIMEOUT",
            ScriptOutcome::AllocExceeded => "ALLOC_EXCEEDED",
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct ScriptResult {
    pub outcome: ScriptOutcome,
    pub actions: Vec<QueuedAction>,
    pub logs: Vec<String>,
    pub error: Option<String>,
}

impl ScriptResult {
    pub fn failed(outcome: ScriptOutcome, error: impl Into<String>) -> Self {
        ScriptResult { outcome, actions: Vec::new(), logs: Vec::new(), error: Some(error.into()) }
    }
}

/// A script language. Implementations must be cheap to share; every run builds its own state.
pub trait ScriptEngine: Send + Sync {
    fn id(&self) -> &str;
    /// File extensions (no dot, lower case) this engine runs, e.g. `lua`.
    fn extensions(&self) -> &[&str];
    /// How this language binds `ih` (call, aliases, results), shown to agents by script_api.
    fn binding(&self) -> &str {
        ""
    }
    /// Parses `source` without running it: `None` when it loads, else the parse error.
    fn check(&self, _source: &str) -> Option<String> {
        None
    }
    /// Runs `source` once. `input` is the `input` global (an object: args, phase, caller, results).
    fn run(&self, source: &str, input: &Value, limits: ScriptLimits, caller: &ScriptCaller) -> ScriptResult;
}

/// The loaded engines, looked up by file extension.
#[derive(Clone, Default)]
pub struct ScriptEngines {
    engines: Vec<std::sync::Arc<dyn ScriptEngine>>,
}

impl ScriptEngines {
    pub fn new(engines: Vec<std::sync::Arc<dyn ScriptEngine>>) -> Self {
        ScriptEngines { engines }
    }

    /// The standard set: Lua.
    pub fn standard() -> Self {
        ScriptEngines::new(vec![std::sync::Arc::new(crate::lua::LuaEngine::new())])
    }

    pub fn by_extension(&self, ext: &str) -> Option<std::sync::Arc<dyn ScriptEngine>> {
        let ext = ext.to_ascii_lowercase();
        self.engines.iter().find(|e| e.extensions().iter().any(|x| *x == ext)).cloned()
    }

    /// Every engine, in load order.
    pub fn all(&self) -> Vec<std::sync::Arc<dyn ScriptEngine>> {
        self.engines.clone()
    }

    pub fn default_engine(&self) -> Option<std::sync::Arc<dyn ScriptEngine>> {
        self.engines.first().cloned()
    }

    /// Every extension of every engine, in load order (the store's lookup order).
    pub fn extensions(&self) -> Vec<String> {
        self.engines.iter().flat_map(|e| e.extensions().iter().map(|x| x.to_string())).collect()
    }
}
