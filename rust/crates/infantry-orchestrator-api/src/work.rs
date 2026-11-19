// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The work-running half of the orchestrator contract: what a ticket start asks of the world
//! ([`WorkspaceProvider`] for the ticket's branch and worktree, [`SessionLauncher`] for its worker),
//! the start / queue / done DTOs, routing rules, and the hook event names the board emits.
//!
//! Both traits are SYNC and are only ever called with no board lock held, so an implementation may
//! take as long as it needs (git, a remote runner) or call back into the board without deadlocking.
use serde::{Deserialize, Serialize};

// ---- worktrees ------------------------------------------------------------------------------------------

/// What a start asks the workspace provider for: the ticket's own branch, cut or reused.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkspaceRequest {
    pub ticket_id: String,
    pub title: String,
    pub project_id: Option<String>,
    pub epic_id: Option<String>,
    /// The branch to cut from (the epic's base, else the project's); None = the provider's own default.
    pub base_branch: Option<String>,
    /// What the ticket already holds: reuse it, never cut a second branch for one ticket.
    pub recorded_branch: Option<String>,
    pub recorded_base: Option<String>,
}

/// A ready ticket workspace.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Workspace {
    pub branch: String,
    /// The commit (or ref) the branch was cut from.
    pub base: String,
    /// The worktree folder the worker runs in.
    pub path: String,
    /// What the cut did, for the start's detail line (e.g. "cut from main"); None = nothing to say.
    #[serde(default)]
    pub note: Option<String>,
}

/// Branches and worktrees (RUST-GIT-1's `infantry-projects` implements it; the board never names git).
pub trait WorkspaceProvider: Send + Sync {
    /// Whether tickets of `project_id` get a worktree when they do not say (a project with a git repo).
    fn wants_worktree(&self, project_id: Option<&str>) -> bool;
    /// The ticket's branch and worktree, cut or reused; Err = the refusal, said on the start.
    fn ensure(&self, request: &WorkspaceRequest) -> Result<Workspace, String>;
    /// The folder a ticket with no worktree runs in; Err when it may not run there (a git checkout).
    fn folder(&self, project_id: Option<&str>) -> Result<Option<String>, String> {
        let _ = project_id;
        Ok(None)
    }
}

/// A host with no repositories: every ticket runs in no particular folder, a worktree is refused.
pub struct NoWorkspaces;

impl WorkspaceProvider for NoWorkspaces {
    fn wants_worktree(&self, _project_id: Option<&str>) -> bool {
        false
    }
    fn ensure(&self, _request: &WorkspaceRequest) -> Result<Workspace, String> {
        Err("no repo configured".into())
    }
}

// ---- sessions -------------------------------------------------------------------------------------------

/// Why a session is being launched (unattended launches may be refused by a budget gate).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum StartOrigin {
    /// A move into in_progress, a ticket_start or an assign: someone asked for this ticket.
    Move,
    /// `ticket_start_next`: the top ready ticket.
    StartNext,
    /// A lane or agent queue draining after a worker left.
    QueueDrain,
    /// A trigger or hook.
    Trigger,
}

impl StartOrigin {
    pub fn unattended(self) -> bool {
        matches!(self, StartOrigin::QueueDrain | StartOrigin::Trigger)
    }
}

/// Everything a worker is launched with.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LaunchRequest {
    pub ticket_id: String,
    pub project_id: Option<String>,
    pub title: String,
    pub role: Option<String>,
    pub agent_id: Option<String>,
    pub provider: Option<String>,
    pub model: Option<String>,
    pub effort: Option<String>,
    pub context_limit: Option<i32>,
    /// `local`, a runner name or `any`.
    pub runner: String,
    /// The ticket's worktree; None = run in `folder`.
    pub workspace: Option<Workspace>,
    pub folder: Option<String>,
    /// The composed start brief: the worker's first prompt.
    pub brief: String,
    /// `(source, chars)` per brief section.
    pub brief_sources: Vec<(String, usize)>,
    pub origin: StartOrigin,
}

/// A launched worker.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LaunchedSession {
    pub session_id: String,
    /// A note for the start's detail (e.g. "effort high: unsupported"); None = nothing to say.
    #[serde(default)]
    pub note: Option<String>,
    /// The branch a remote runner cut itself, when it did.
    #[serde(default)]
    pub workspace: Option<Workspace>,
}

/// Where a worker stands, as the board needs it for lane occupancy.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum WorkerState {
    /// A turn is running.
    Busy,
    /// Alive, resting between turns.
    Idle,
    /// Gone, failed or killed: it holds no lane.
    Ended,
}

/// Starts and ends ticket workers (the daemon wires it to its session manager / runners).
pub trait SessionLauncher: Send + Sync {
    /// Launch a worker; Err = the refusal (provider missing, budget spent, runner down), said on the start.
    fn launch(&self, request: &LaunchRequest) -> Result<LaunchedSession, String>;
    /// End a worker (idempotent). `reason` is recorded with the kill.
    fn stop(&self, session_id: &str, reason: &str);
    /// The worker's state; an unknown id is [`WorkerState::Ended`].
    fn state(&self, session_id: &str) -> WorkerState;
    /// Deliver a short prompt to a live worker (a handoff request); false = not delivered.
    fn prompt(&self, session_id: &str, text: &str) -> bool {
        let _ = (session_id, text);
        false
    }
}

/// A host with no session manager: every launch is refused.
pub struct NoSessions;

impl SessionLauncher for NoSessions {
    fn launch(&self, _request: &LaunchRequest) -> Result<LaunchedSession, String> {
        Err("ticket session manager is unavailable".into())
    }
    fn stop(&self, _session_id: &str, _reason: &str) {}
    fn state(&self, _session_id: &str) -> WorkerState {
        WorkerState::Ended
    }
}

// ---- routing rules --------------------------------------------------------------------------------------

/// ROUTING-1: a project's rule. The first matching rule of the project wins for a ticket that pins
/// neither provider nor model.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct RoutingRule {
    pub name: String,
    /// Weights 1..5 this rule matches; empty = any weight, unweighted included.
    #[serde(default)]
    pub weights: Vec<i32>,
    /// Ticket types this rule matches; empty = any type, untyped included.
    #[serde(default)]
    pub ticket_types: Vec<String>,
    /// The ticket's role this rule matches; None = any role.
    #[serde(default)]
    pub role: Option<String>,
    pub provider: String,
    pub model: String,
    #[serde(default)]
    pub effort: Option<String>,
    /// The role a ticket with no role is started as.
    #[serde(default)]
    pub set_role: Option<String>,
    /// Where a matching ticket starts: `local`, a runner name or `any`; None = no say.
    #[serde(default)]
    pub runner: Option<String>,
}

impl RoutingRule {
    pub fn matches(&self, weight: Option<i32>, ticket_type: Option<&str>, role: Option<&str>) -> bool {
        (self.weights.is_empty() || weight.is_some_and(|w| self.weights.contains(&w)))
            && (self.ticket_types.is_empty() || ticket_type.is_some_and(|t| self.ticket_types.iter().any(|x| x == t)))
            && (self.role.is_none() || self.role.as_deref() == role)
    }

    /// The first rule of `rules` that matches.
    pub fn first<'a>(rules: &'a [RoutingRule], weight: Option<i32>, ticket_type: Option<&str>, role: Option<&str>) -> Option<&'a RoutingRule> {
        rules.iter().find(|r| r.matches(weight, ticket_type, role))
    }
}

// ---- requests -------------------------------------------------------------------------------------------

/// `ticket_start`: start one ticket (the same as a move into in_progress). `started_by`: the
/// starting session, who the worker reports to (None = the owner / UI).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TicketStartRequest {
    pub id: String,
    #[serde(default)]
    pub started_by: Option<String>,
}

/// `ticket_start_next`: the highest ready todo ticket, optionally one of a project / assigned to an agent.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct StartNextRequest {
    #[serde(default)]
    pub project_id: Option<String>,
    /// A profile id or `role:<role>`.
    #[serde(default)]
    pub agent_id: Option<String>,
    #[serde(default)]
    pub started_by: Option<String>,
}

/// `agent_queue` (AGENT-TASKS-3): `action` = list|add|remove|move|clear; `at` a 1-based place.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AgentQueueRequest {
    pub agent_id: String,
    #[serde(default = "default_list")]
    pub action: String,
    #[serde(default)]
    pub ticket_id: Option<String>,
    #[serde(default)]
    pub at: Option<i32>,
}

fn default_list() -> String {
    "list".into()
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentQueueEntry {
    pub ticket_id: String,
    pub title: String,
    pub rank: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AgentQueueState {
    pub agent_id: String,
    #[serde(default)]
    pub name: Option<String>,
    #[serde(default)]
    pub tickets: Vec<AgentQueueEntry>,
    #[serde(default)]
    pub message: Option<String>,
}

/// The done protocol from the worker's side: its done comment (`summary`), then the move to done,
/// then the next ticket of its lane starts. `session_id`: the reporting worker.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TicketDone {
    pub ticket_id: String,
    pub session_id: String,
    pub summary: String,
    /// End the worker once its ticket is done (default true).
    #[serde(default = "yes")]
    pub stop_worker: bool,
}

fn yes() -> bool {
    true
}

/// What a done answered: the closed ticket and the one its lane started next, if any.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DoneAnswer {
    pub ticket: crate::Ticket,
    #[serde(default)]
    pub next: Option<crate::Ticket>,
}

// ---- hooks ----------------------------------------------------------------------------------------------

/// The named events the board emits on the bus for Lua hooks (LUA-1's engine subscribes). Payloads
/// are JSON objects with at least `ticketId` and `projectId`.
pub mod hooks {
    /// A worker was launched and the ticket is in_progress: `sessionId`, `agentId`, `role`, `provider`, `model`, `branch`.
    pub const TICKET_STARTED: &str = "ticket.started";
    /// The board started a ticket by itself (the lane drained after a done or a failed run): `reason`, `afterSession`, `role`.
    pub const TICKET_AUTO_STARTED: &str = "ticket.auto_started";
    /// A start was refused: `reason`, `detail`.
    pub const TICKET_START_REFUSED: &str = "ticket.start_refused";
    /// A start found its lane full and the ticket waits in it: `lane`.
    pub const TICKET_QUEUED: &str = "ticket.queued";
    /// The ticket changed column: `from`, `to`.
    pub const TICKET_MOVED: &str = "ticket.moved";
    /// The done protocol closed it: `sessionId`, `commentId`.
    pub const TICKET_DONE: &str = "ticket.done";
    /// A worker's run ended: `sessionId`, `outcome`, `detail`.
    pub const RUN_ENDED: &str = "ticket.run_ended";
    /// A run ended badly (failed, killed, orphaned, or clean with no done comment): `sessionId`, `outcome`, `detail`.
    pub const RUN_FAILED: &str = "ticket.run_failed";
    /// An in_progress ticket went back to todo: `sessionId`, `note`.
    pub const TICKET_UNASSIGNED: &str = "ticket.unassigned";
    /// An agent's queue changed: `agentId`, `tickets`.
    pub const QUEUE_CHANGED: &str = "agent.queue_changed";
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rule_matching() {
        let r = RoutingRule { name: "big".into(), weights: vec![4, 5], provider: "claude".into(), model: "opus".into(), ..Default::default() };
        assert!(r.matches(Some(5), None, None));
        assert!(!r.matches(None, None, None));
        let any = RoutingRule { name: "any".into(), role: Some("qa".into()), ..Default::default() };
        assert!(any.matches(None, Some("defect"), Some("qa")) && !any.matches(None, None, Some("backend")));
    }
}
