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

/// What [`BoardEnv::worker_failed`] is told: one ticket worker's bad end.
#[derive(Debug, Clone, Copy)]
pub struct WorkerFailed<'a> {
    pub ticket_id: &'a str,
    pub project_id: Option<&'a str>,
    /// The worker session that ended.
    pub session_id: &'a str,
    /// The ticket's lead: the session that started it. None = the owner started it.
    pub lead: Option<&'a str>,
    /// The run outcome (`FAILED`, `SUCCEEDED` for a clean end with no done comment, ...).
    pub outcome: &'a str,
    /// The mail subject and body: what happened and the command that recovers it.
    pub subject: &'a str,
    pub detail: &'a str,
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
    /// The human user (`owner` on a notify list): a notice in the app's notifications, not mail. True when posted.
    fn notify_owner(&self, _title: &str, _body: &str, _ticket_id: &str, _project_id: Option<&str>) -> bool {
        false
    }
    /// The live session ids holding `role` (in `project_id` when given): `role:<name>` resolved at send time.
    fn live_role_sessions(&self, _role: &str, _project_id: Option<&str>) -> Vec<String> {
        Vec::new()
    }
    /// Is `role` a role some agent or live session holds?
    fn is_role(&self, _role: &str) -> bool {
        false
    }
    /// Is `id` a session that is still live?
    fn is_live_session(&self, _id: &str) -> bool {
        false
    }
    /// AUTO-REPORT-1: the git facts of a finished ticket for the starter's mail: its branch tip and whether the
    /// merge target already holds it. None when the host has no repository.
    fn branch_facts(&self, _project_id: Option<&str>, _branch: Option<&str>, _base: Option<&str>) -> Option<String> {
        None
    }
    /// Wakes one session with a short mail line; true when delivered.
    fn notify_session(&self, _session_id: &str, _subject: &str, _line: &str) -> bool {
        false
    }
    /// Like `notify_session` for a notice that needs action now (a worker failed, was killed, or ended without a done
    /// comment): the host should put it into the receiver's running turn and log it with its timestamp.
    fn alert_session(&self, session_id: &str, subject: &str, line: &str, _ticket_id: &str) -> bool {
        self.notify_session(session_id, subject, line)
    }
    /// PIPE-RESTART-1: a ticket worker's run ended badly (failed, killed, or ended without a done comment). The host
    /// raises an attention item for the owner and MAILS the ticket's lead (`failure.lead`, its starter session, when
    /// there is one). True = the lead was mailed; false (no lead, no mail path, mail refused) leaves the caller to fall
    /// back to [`alert_session`](Self::alert_session).
    fn worker_failed(&self, _failure: &WorkerFailed<'_>) -> bool {
        false
    }
    /// `board.restartResumeSeconds`, in ms: a resumed worker that dies sooner than this after a restart counts as a
    /// failed resume, and a fresh session replaces it on the same worktree. 0 = never fall back.
    fn restart_resume_window_ms(&self) -> u64 {
        20_000
    }

    // ---- the work-running half (starts, lanes, briefs, done) ---------------------------------------------

    /// LANE-CAPS: the most workers of `role` that may run at once in `project_id` (at least 1).
    fn lane_cap(&self, _project_id: Option<&str>, _role: &str) -> u32 {
        1
    }
    /// `lanes.maxInProgress` (scopes app, project, role): the most tickets of `role` in `project_id` that may be
    /// in_progress at once, whether or not their worker is alive. Default 1; at least 1.
    fn max_in_progress(&self, _project_id: Option<&str>, _role: &str) -> u32 {
        1
    }
    /// PIPE-ROLE-1: the role a ticket with no role starts with (`tickets.defaultRole`, scopes app and project).
    /// None = the ticket is refused as before.
    fn default_role(&self, _project_id: Option<&str>) -> Option<String> {
        None
    }
    /// The most ticket workers a project may run at once; None = no cap.
    fn max_workers(&self, _project_id: Option<&str>) -> Option<u32> {
        None
    }
    /// How many slow start phases (worktree cut + launch) may run at once; more are deferred, never waited on.
    fn max_parallel_starts(&self) -> usize {
        4
    }
    /// ROUTING-1: the project's routing rules, in order.
    fn routing_rules(&self, _project_id: Option<&str>) -> Vec<infantry_orchestrator_api::RoutingRule> {
        Vec::new()
    }
    /// RUNNER-POOL-3: `runners.default`.
    fn default_runner(&self) -> String {
        "local".into()
    }
    /// AGENT-TASKS-3: `limits.agentTasks.queueMax`.
    fn queue_max(&self) -> usize {
        10
    }
    /// AGENT-TASKS-2: `limits.agentTasks.unassignWaitSeconds`: how long a busy unassigned worker may finish its turn.
    fn unassign_wait_secs(&self) -> u64 {
        120
    }
    /// After a worker leaves its lane (done, failed, unassigned), start the lane's next ticket.
    /// False when the project's hooks.json turns auto start off (a hook with `autoStart=false`).
    fn auto_start_next(&self, _project_id: Option<&str>) -> bool {
        true
    }
    /// LANE-CAPS / INSTALL-PROFILE-1: save a new agent profile (a lane clone or a role's first profile); None = cannot.
    fn create_agent(&self, _template: &BoardAgent) -> Option<BoardAgent> {
        None
    }
    /// MCP-FAIL-1: why a ticket's `mcp.require` is not met, or None.
    fn mcp_preflight(&self, _ticket_id: &str, _project_id: Option<&str>, _agent_id: Option<&str>, _mcp_json: &str) -> Option<String> {
        None
    }
    /// USAGE-PACE: why one more worker on (provider, model) may not start, or None.
    fn pacing_refusal(&self, _provider: Option<&str>, _model: Option<&str>) -> Option<String> {
        None
    }
    /// The role / profile instructions a worker is briefed with.
    fn instructions(&self, _role: Option<&str>, _agent_id: Option<&str>, _project_id: Option<&str>) -> Option<String> {
        None
    }
    /// PIPE-BRIEF-1: the standard DONE-report block the brief appends when the ticket text has none (data, from the
    /// host's settings). None = append nothing.
    fn done_report_block(&self, _project_id: Option<&str>) -> Option<String> {
        None
    }
    /// Host-rendered brief sections `(source, text)` (knowledge, skills, role notes), in order.
    fn brief_sections(&self, _project_id: Option<&str>, _role: Option<&str>, _agent_id: Option<&str>, _ticket_id: &str) -> Vec<(String, String)> {
        Vec::new()
    }
    /// The files a ticket touched (for the RELATED block), most relevant first.
    fn ticket_files(&self, _ticket_id: &str) -> Vec<String> {
        Vec::new()
    }
    /// A session's display name (its agent's name, else its role), for "report to"; None = no such session.
    fn session_label(&self, _session_id: &str) -> Option<String> {
        None
    }
    /// IMG-3: a stored image relPath as an absolute path the worker can read.
    fn image_path(&self, _project_id: Option<&str>, rel: &str) -> Option<String> {
        Some(rel.to_string())
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
    /// Live sessions as (role, session id).
    pub live_roles: Vec<(String, String)>,
    /// Lane cap per role (0 = the default 1).
    pub lane_cap: u32,
    /// `lanes.maxInProgress` (0 = follow `lane_cap`, so a test that widens the lane widens both).
    pub max_in_progress: u32,
    /// `hooks.json` autoStart=false.
    pub auto_start_off: bool,
    pub rules: Vec<infantry_orchestrator_api::RoutingRule>,
    /// Slow-start slots (0 = the default 4).
    pub start_slots: usize,
    /// PIPE-RESTART-1: whether `worker_failed` mails a lead (a host with a mail path); every call is recorded in `failed`.
    pub lead_mail: bool,
    /// Every `worker_failed` call, as "<lead or -> <ticket> <session> <outcome>: <subject>".
    pub failed: Mutex<Vec<String>>,
    /// `board.restartResumeSeconds` in ms (0 = the default 20 s).
    pub resume_window_ms: u64,
    /// PIPE-ROLE-1: `tickets.defaultRole`.
    pub default_role: Option<String>,
    /// The standard DONE-report block (PIPE-BRIEF-1).
    pub done_report: Option<String>,
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
    fn live_role_sessions(&self, role: &str, _project_id: Option<&str>) -> Vec<String> {
        self.live_roles.iter().filter(|(r, _)| r == role).map(|(_, s)| s.clone()).collect()
    }
    fn is_role(&self, role: &str) -> bool {
        self.live_roles.iter().any(|(r, _)| r == role) || self.agents.iter().any(|a| a.role.as_deref() == Some(role))
    }
    fn is_session(&self, id: &str) -> bool {
        self.is_live_session(id)
    }
    fn notify_owner(&self, title: &str, body: &str, _ticket_id: &str, _project_id: Option<&str>) -> bool {
        self.notified.lock().unwrap_or_else(|p| p.into_inner()).push(format!("owner {title} {body}"));
        true
    }
    fn is_live_session(&self, id: &str) -> bool {
        self.live.iter().any(|(_, s)| s == id) || self.live_roles.iter().any(|(_, s)| s == id)
    }
    fn notify_session(&self, session_id: &str, _subject: &str, line: &str) -> bool {
        self.notified.lock().unwrap_or_else(|p| p.into_inner()).push(format!("{session_id} {line}"));
        true
    }
    fn worker_failed(&self, f: &WorkerFailed<'_>) -> bool {
        self.failed.lock().unwrap_or_else(|p| p.into_inner()).push(format!("{} {} {} {}: {}", f.lead.unwrap_or("-"), f.ticket_id, f.session_id, f.outcome, f.subject));
        self.lead_mail && f.lead.is_some()
    }
    fn restart_resume_window_ms(&self) -> u64 {
        if self.resume_window_ms == 0 { 20_000 } else { self.resume_window_ms }
    }
    fn lane_cap(&self, _project_id: Option<&str>, _role: &str) -> u32 {
        self.lane_cap.max(1)
    }
    fn max_in_progress(&self, _project_id: Option<&str>, _role: &str) -> u32 {
        if self.max_in_progress == 0 { self.lane_cap.max(1) } else { self.max_in_progress }
    }
    fn auto_start_next(&self, _project_id: Option<&str>) -> bool {
        !self.auto_start_off
    }
    fn max_parallel_starts(&self) -> usize {
        if self.start_slots == 0 { 4 } else { self.start_slots }
    }
    fn default_role(&self, _project_id: Option<&str>) -> Option<String> {
        self.default_role.clone()
    }
    fn routing_rules(&self, _project_id: Option<&str>) -> Vec<infantry_orchestrator_api::RoutingRule> {
        self.rules.clone()
    }
    fn done_report_block(&self, _project_id: Option<&str>) -> Option<String> {
        self.done_report.clone()
    }
}
