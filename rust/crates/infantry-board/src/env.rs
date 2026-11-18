// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! What the board asks of the world around it: agent profiles, the harness catalog, settings, the
//! clock and ids. The board's own rules never reach past this trait, so the same board runs in the
//! daemon, in a test, or (later) as a wasm plugin whose host answers these calls.
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::Mutex;

/// One agent profile, as the board sees it.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct BoardAgent {
    pub id: Option<String>,
    pub name: String,
    pub role: Option<String>,
    pub provider: Option<String>,
    pub model: Option<String>,
    pub effort: Option<String>,
}

/// What a role/profile binding resolves to (Kotlin `executorFor`).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct BoundExecutor {
    pub provider: Option<String>,
    pub model: Option<String>,
    pub effort: Option<String>,
}

/// One installed harness and how it honours a context limit / an effort level.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ProviderFacts {
    pub id: String,
    pub default_model: Option<String>,
    pub context_limit_support: Option<String>,
    pub context_limit_how: Option<String>,
    pub effort_support: Option<String>,
    pub effort_how: Option<String>,
    /// The models it lists, when it can list them (None = cannot; any model is accepted).
    pub known_models: Option<Vec<String>>,
}

/// TICKET-WEIGHT: a weight's executor.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct WeightExecutor {
    pub provider: Option<String>,
    pub model: Option<String>,
}

/// TOKEN-7: a weight's context defaults.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct WeightContextEntry {
    pub context_limit: Option<i32>,
    pub effort: Option<String>,
}

/// The project switches the effective context resolves against (`None` = unset, the `default` is true).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct ContextSettings {
    pub include_linked_tasks_context: Option<bool>,
    pub include_skills_context: Option<bool>,
}

/// The role ids the catalog ships with (`RoleIds.CANONICAL`).
pub const CANONICAL_ROLES: [&str; 16] = [
    "architect", "backend", "frontend", "fullstack", "qa", "devops", "security", "mobile", "pm", "marketing",
    "version-control", "seo", "localization", "ideation", "automation", "assistant",
];

/// Retired role id -> its replacement (`RoleIds.RETIRED`).
pub fn normalize_role(id: &str) -> &str {
    match id {
        "git" => "version-control",
        "i18n" => "localization",
        "brainstormer" => "ideation",
        "bot" => "automation",
        "chief-of-staff" => "assistant",
        other => other,
    }
}

/// The board's view of its host. Every method has the answer of a host that offers nothing (no
/// profiles, no sessions, no settings), which is Kotlin's `TicketBoard` with its defaults.
pub trait BoardEnv: Send + Sync {
    fn now_ms(&self) -> i64;
    fn new_ticket_id(&self) -> String {
        format!("ticket-{}", &uuid::Uuid::new_v4().to_string()[..8])
    }
    fn agents(&self) -> Vec<BoardAgent> {
        Vec::new()
    }
    fn agent(&self, id: &str) -> Option<BoardAgent> {
        self.agents().into_iter().find(|a| a.id.as_deref() == Some(id))
    }
    fn role_ids(&self) -> Vec<String> {
        CANONICAL_ROLES.iter().map(|s| s.to_string()).collect()
    }
    /// LIMITS: why a ticket may not newly name `role` (the role allowlist), or None.
    fn role_refusal(&self, _role: Option<&str>) -> Option<String> {
        None
    }
    /// Whether a session manager is present (Kotlin `sessionsOn`).
    fn sessions_on(&self) -> bool {
        false
    }
    fn default_provider(&self) -> Option<String> {
        None
    }
    fn provider(&self, _id: &str) -> Option<ProviderFacts> {
        None
    }
    fn has_provider(&self, id: &str) -> bool {
        self.provider(id).is_some()
    }
    /// Why a worker on (provider, model) cannot be spawned, or None.
    fn check_spawn(&self, _provider: Option<&str>, _model: Option<&str>) -> Option<String> {
        None
    }
    fn executor_for(&self, _agent: &BoardAgent, _provider: Option<&str>, _model: Option<&str>) -> Option<BoundExecutor> {
        None
    }
    /// `None` = no context composer (the view carries no `effectiveContext`).
    fn context_settings(&self, _project_id: Option<&str>) -> Option<ContextSettings> {
        None
    }
    fn weight_executor(&self, _weight: Option<i32>) -> Option<WeightExecutor> {
        None
    }
    fn weight_context(&self, _weight: Option<i32>) -> Option<WeightContextEntry> {
        None
    }
    /// `limits.session.defaultContextTokens`.
    fn default_context_limit(&self) -> Option<i32> {
        None
    }
    /// `limits.session.compactMaxTokens`.
    fn compact_max_tokens(&self) -> Option<i32> {
        None
    }
    // ---- epics -----------------------------------------------------------------------------------------

    /// A new epic id (`epic-` + 8 hex).
    fn new_epic_id(&self) -> String {
        format!("epic-{}", &uuid::Uuid::new_v4().to_string()[..8])
    }
    /// A new comment id (a UUID).
    fn new_comment_id(&self) -> String {
        uuid::Uuid::new_v4().to_string()
    }
    /// Validates and normalises a base-branch name (`ProjectBaseCheck.checkName`).
    fn branch_name(&self, raw: &str) -> Result<String, String> {
        Ok(raw.trim().to_string())
    }
    /// Checks that `branch` resolves to a commit in `project_id`'s repository. A host with no repository refuses.
    fn check_base(&self, _project_id: &str, _branch: &str) -> Result<(), String> {
        Err("this daemon has no repository to check a base branch against".into())
    }

    // ---- comments --------------------------------------------------------------------------------------

    /// Is `id` an agent of the registry (a user-created / role agent)?
    fn is_registry_agent(&self, _id: &str) -> bool {
        false
    }
    /// Is `id` a session id (an invoked worker)? Only words a refusal.
    fn is_session(&self, _id: &str) -> bool {
        false
    }
    /// The live session ids of `agent_id` (in `project_id` when given).
    fn live_sessions(&self, _agent_id: &str, _project_id: Option<&str>) -> Vec<String> {
        Vec::new()
    }
    /// Wakes one session with a short mail line; true when delivered.
    fn notify_session(&self, _session_id: &str, _subject: &str, _line: &str) -> bool {
        false
    }

    /// IMG-3: the images as stored relPaths, or why not. A host with no project files accepts only none.
    fn normalize_images(&self, _project_id: Option<&str>, images: &[String]) -> Result<Vec<String>, String> {
        if images.is_empty() {
            Ok(Vec::new())
        } else {
            Err("images are not available on this board host".into())
        }
    }
}

/// A [`BoardEnv`] made of plain values: tests, and the daemon until its registries land.
#[derive(Default)]
pub struct StaticEnv {
    /// Fixed clock (ms); 0 = the system clock.
    pub fixed_now: AtomicI64,
    /// Ids handed out in order before falling back to random ones.
    pub ids: Mutex<Vec<String>>,
    pub agents: Vec<BoardAgent>,
    pub sessions_on: bool,
    pub default_provider: Option<String>,
    pub providers: Vec<ProviderFacts>,
    pub context: Option<ContextSettings>,
    pub default_context_limit: Option<i32>,
    pub compact_max_tokens: Option<i32>,
    pub weight_context: Vec<(i32, WeightContextEntry)>,
    pub weight_executors: Vec<(i32, WeightExecutor)>,
    /// Registry agent ids (comment notify targets).
    pub registry: Vec<String>,
    /// Live sessions as (agent id, session id).
    pub live: Vec<(String, String)>,
    /// Every delivered notify line, as "<session> <line>".
    pub notified: Mutex<Vec<String>>,
}

impl StaticEnv {
    pub fn set_now(&self, ms: i64) {
        self.fixed_now.store(ms, Ordering::SeqCst);
    }
    pub fn push_ids(&self, ids: &[&str]) {
        let mut q = self.ids.lock().unwrap_or_else(|p| p.into_inner());
        q.extend(ids.iter().rev().map(|s| s.to_string()));
    }
}

impl BoardEnv for StaticEnv {
    fn now_ms(&self) -> i64 {
        match self.fixed_now.load(Ordering::SeqCst) {
            0 => std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0),
            n => n,
        }
    }
    fn new_ticket_id(&self) -> String {
        let mut q = self.ids.lock().unwrap_or_else(|p| p.into_inner());
        q.pop().unwrap_or_else(|| format!("ticket-{}", &uuid::Uuid::new_v4().to_string()[..8]))
    }
    fn agents(&self) -> Vec<BoardAgent> {
        self.agents.clone()
    }
    fn sessions_on(&self) -> bool {
        self.sessions_on
    }
    fn default_provider(&self) -> Option<String> {
        self.default_provider.clone()
    }
    fn provider(&self, id: &str) -> Option<ProviderFacts> {
        self.providers.iter().find(|p| p.id == id).cloned()
    }
    fn context_settings(&self, _project_id: Option<&str>) -> Option<ContextSettings> {
        self.context
    }
    fn weight_executor(&self, weight: Option<i32>) -> Option<WeightExecutor> {
        let w = weight?;
        self.weight_executors.iter().find(|(k, _)| *k == w).map(|(_, e)| e.clone()).filter(|e| e.provider.is_some() || e.model.is_some())
    }
    fn weight_context(&self, weight: Option<i32>) -> Option<WeightContextEntry> {
        let w = weight?;
        self.weight_context.iter().find(|(k, _)| *k == w).map(|(_, e)| e.clone())
    }
    fn default_context_limit(&self) -> Option<i32> {
        self.default_context_limit
    }
    fn compact_max_tokens(&self) -> Option<i32> {
        self.compact_max_tokens
    }
    fn is_registry_agent(&self, id: &str) -> bool {
        self.registry.iter().any(|r| r == id)
    }
    fn live_sessions(&self, agent_id: &str, _project_id: Option<&str>) -> Vec<String> {
        self.live.iter().filter(|(a, _)| a == agent_id).map(|(_, s)| s.clone()).collect()
    }
    fn notify_session(&self, session_id: &str, _subject: &str, line: &str) -> bool {
        self.notified.lock().unwrap_or_else(|p| p.into_inner()).push(format!("{session_id} {line}"));
        true
    }
}
