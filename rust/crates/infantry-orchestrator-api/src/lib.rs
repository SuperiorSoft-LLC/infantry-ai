// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry AI orchestrator interface (layer L4, location-transparent).
//!
//! [`Orchestrator`] is what a composition root (daemon, CLI, Tauri app) holds to drive the board. It is
//! async and speaks only serde DTOs, so the in-process implementation (`infantry-board`'s
//! `LocalOrchestrator`) and a later remote one (the same calls as frames over a link) are interchangeable
//! behind `Arc<dyn Orchestrator>`.
//!
//! Layer rule: agents and harnesses (L3) NEVER import this crate; an agent works with no orchestrator.
pub mod dto;
pub mod work;

use std::future::Future;
use std::pin::Pin;

use serde::{Deserialize, Serialize};

pub use dto::*;
pub use work::*;

/// A boxed, `Send` future: keeps [`Orchestrator`] object-safe.
pub type BoxFuture<'a, T> = Pin<Box<dyn Future<Output = T> + Send + 'a>>;

/// Why an orchestrator call did not do what was asked.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", content = "message", rename_all = "snake_case")]
pub enum OrchError {
    /// The request broke a board rule; nothing changed. The text is the reason, without the
    /// `<frame> refused: ` prefix (the frame layer adds it).
    Refused(String),
    /// Something failed underneath (the database, the link). `<frame> failed: <text>` on the wire.
    Failed(String),
    /// This orchestrator does not implement the call (yet).
    Unsupported(String),
}

impl std::fmt::Display for OrchError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            OrchError::Refused(m) | OrchError::Failed(m) => f.write_str(m),
            OrchError::Unsupported(m) => write!(f, "not supported: {m}"),
        }
    }
}

impl std::error::Error for OrchError {}

pub type OrchResult<T> = Result<T, OrchError>;

/// The answer to a one-ticket request. `pushed`: the change already went to every client as a push.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TicketAnswer {
    pub ticket: Ticket,
    pub pushed: bool,
}

/// The answer to a whole-board request (delete, reorder). `pushed` as above.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BoardAnswer {
    pub tickets: Vec<Ticket>,
    pub pushed: bool,
}

/// How many ticket workers a project may run, and how many run now. `max` None = no cap.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Limit {
    pub project_id: Option<String>,
    pub max: Option<u32>,
    pub running: u32,
}

/// A worker's report that its ticket's run ended (the done protocol feeds this).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DoneReport {
    pub ticket_id: String,
    pub session_id: String,
    /// A `FinishedPayload.Outcome` name: `SUCCEEDED`, `FAILED`, `KILLED_GRACEFUL`, `KILLED_FORCED`, `ORPHANED`.
    pub outcome: String,
    #[serde(default)]
    pub detail: Option<String>,
}

/// The board, location-transparent. Every method is a plain request/answer over DTOs.
pub trait Orchestrator: Send + Sync {
    /// The board in board order (column by column, top first), filtered.
    fn tickets(&self, filter: TicketListQuery) -> BoxFuture<'_, OrchResult<Vec<Ticket>>>;
    fn get(&self, id: String) -> BoxFuture<'_, OrchResult<Ticket>>;
    /// Create (no id) or edit a ticket.
    fn save(&self, request: TicketSave) -> BoxFuture<'_, OrchResult<TicketAnswer>>;
    /// Change a ticket's column and/or place.
    fn move_lane(&self, request: TicketMove) -> BoxFuture<'_, OrchResult<TicketAnswer>>;
    fn delete(&self, id: String) -> BoxFuture<'_, OrchResult<BoardAnswer>>;
    /// Link two tickets (idempotent); answered with `ticket_id`'s ticket.
    fn link(&self, request: TicketLinkRequest) -> BoxFuture<'_, OrchResult<TicketAnswer>>;
    /// Remove a link (refused when there is none); answered with `ticket_id`'s ticket.
    fn unlink(&self, request: TicketLinkRequest) -> BoxFuture<'_, OrchResult<TicketAnswer>>;
    /// Put the listed tickets on top of their columns, in order.
    fn reorder(&self, request: TicketReorder) -> BoxFuture<'_, OrchResult<BoardAnswer>>;
    /// Epics of a project, in creation order.
    fn epics(&self, project_id: String) -> BoxFuture<'_, OrchResult<Vec<Epic>>>;
    fn epic_save(&self, request: EpicSave) -> BoxFuture<'_, OrchResult<Epic>>;
    /// Read, set or clear an epic's base branch.
    fn epic_base(&self, request: EpicBaseRequest) -> BoxFuture<'_, OrchResult<Epic>>;
    /// Record a new ticket's creator and its starting notify list (None = creator + project defaults).
    fn record_creator(&self, ticket_id: String, creator: CommentAuthor, notify: Option<Vec<String>>) -> BoxFuture<'_, OrchResult<()>>;
    /// Add a comment; wakes the notify list and emits the named event `ticket.commented`.
    fn comment_add(&self, request: CommentAdd) -> BoxFuture<'_, OrchResult<Comment>>;
    /// Resolve a comment by id or unique prefix (idempotent, no mail).
    fn comment_resolve(&self, id: String, by: CommentAuthor) -> BoxFuture<'_, OrchResult<Comment>>;
    fn comments(&self, query: CommentQuery) -> BoxFuture<'_, OrchResult<Vec<Comment>>>;
    /// Edit a ticket's notify list; answers the list as it now stands.
    fn notify_edit(&self, request: NotifyEdit) -> BoxFuture<'_, OrchResult<Vec<String>>>;
    fn assign(&self, request: TicketAssign) -> BoxFuture<'_, OrchResult<TicketAnswer>>;
    fn unassign(&self, request: TicketUnassign) -> BoxFuture<'_, OrchResult<TicketAnswer>>;
    fn spawn_limit(&self, project_id: Option<String>) -> BoxFuture<'_, OrchResult<Limit>>;
    fn on_done(&self, report: DoneReport) -> BoxFuture<'_, OrchResult<()>>;
    /// Start one ticket (the same as a move into in_progress). Never waits on another start: the
    /// answer's `start` says started, queued, held or refused.
    fn start(&self, request: TicketStartRequest) -> BoxFuture<'_, OrchResult<TicketAnswer>> {
        let _ = request;
        unsupported("ticket_start")
    }
    /// Start the highest ready todo ticket; refused (with each candidate's reason) when none starts.
    fn start_next(&self, request: StartNextRequest) -> BoxFuture<'_, OrchResult<TicketAnswer>> {
        let _ = request;
        unsupported("ticket_start_next")
    }
    /// List or edit an agent's queue.
    fn agent_queue(&self, request: AgentQueueRequest) -> BoxFuture<'_, OrchResult<AgentQueueState>> {
        let _ = request;
        unsupported("agent_queue")
    }
    /// The worker's done: done comment, move to done, next ticket of its lane.
    fn done(&self, request: TicketDone) -> BoxFuture<'_, OrchResult<DoneAnswer>> {
        let _ = request;
        unsupported("ticket_done")
    }
    /// PIPE-CLOSE-1: done, stop and remove the worker, remove the clean merged worktree, start the lane's next ticket.
    fn close(&self, request: TicketClose) -> BoxFuture<'_, OrchResult<CloseAnswer>> {
        let _ = request;
        unsupported("ticket_close")
    }
    /// PIPE-RESTART-1: put a worker back on an in_progress ticket whose worker died: resume it, else a fresh session on
    /// the same worktree and branch.
    fn restart(&self, request: TicketRestart) -> BoxFuture<'_, OrchResult<RestartAnswer>> {
        let _ = request;
        unsupported("ticket_restart")
    }
}

fn unsupported<T: Send + 'static>(what: &str) -> BoxFuture<'static, OrchResult<T>> {
    let m = format!("{what} is not offered by this orchestrator");
    Box::pin(async move { Err(OrchError::Unsupported(m)) })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn errors_round_trip_as_tagged_json() {
        let e = OrchError::Refused("no ticket 'x'".into());
        let j = serde_json::to_string(&e).unwrap();
        assert_eq!(j, r#"{"kind":"refused","message":"no ticket 'x'"}"#);
        assert_eq!(serde_json::from_str::<OrchError>(&j).unwrap(), e);
    }

    #[test]
    fn trait_is_object_safe() {
        fn _takes(_: &dyn Orchestrator) {}
    }
}
