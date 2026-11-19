// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! [`LocalOrchestrator`]: the in-process [`Orchestrator`] over a [`Board`]. Changes are published on
//! the given [`EventBus`] as the same frames the [`crate::unit::BoardUnit`] pushes.
use std::sync::Arc;

use infantry_core::bus::EventBus;
use infantry_orchestrator_api::{
    BoardAnswer, BoxFuture, Comment, CommentAdd, CommentAuthor, CommentQuery, DoneReport, Epic, EpicBaseRequest, EpicSave, Limit,
    NotifyEdit, OrchError, OrchResult, Orchestrator, Ticket, TicketAnswer, TicketAssign, TicketLinkRequest, TicketListQuery,
    TicketMove, TicketReorder, TicketSave, TicketUnassign, AgentQueueRequest, AgentQueueState, DoneAnswer, StartNextRequest,
    StartOrigin, TicketDone, TicketStartRequest,
};

use crate::board::Board;
use crate::dispatch::Dispatcher;

pub struct LocalOrchestrator {
    board: Arc<Board>,
    bus: EventBus,
    work: Option<Arc<Dispatcher>>,
}

impl LocalOrchestrator {
    /// The board half only: the work half answers Unsupported.
    pub fn new(board: Arc<Board>, bus: EventBus) -> LocalOrchestrator {
        LocalOrchestrator { board, bus, work: None }
    }

    /// Both halves: starts, queues and the done protocol run through `work`.
    pub fn with_dispatcher(work: Arc<Dispatcher>, bus: EventBus) -> LocalOrchestrator {
        LocalOrchestrator { board: work.board().clone(), bus, work: Some(work) }
    }

    fn work(&self, what: &str) -> OrchResult<&Arc<Dispatcher>> {
        self.work.as_ref().ok_or_else(|| OrchError::Unsupported(format!("{what} needs the work half (no session launcher here)")))
    }

    fn publish(&self) -> impl Fn(infantry_protocol::Envelope) + '_ {
        move |e| {
            self.bus.publish(e);
        }
    }
}

/// SCRIPT-3b: an added comment publishes the named event `ticket.commented` (a failed publish never fails it).
pub fn emit_commented(bus: &EventBus, c: &Comment) {
    let preview: String = c.body.lines().next().unwrap_or("").chars().take(200).collect();
    bus.emit(
        "ticket.commented",
        serde_json::json!({ "ticketId": c.ticket_id, "commentId": c.id, "author": c.author.label(), "bodyPreview": preview }),
    );
}

impl Orchestrator for LocalOrchestrator {
    fn tickets(&self, filter: TicketListQuery) -> BoxFuture<'_, OrchResult<Vec<Ticket>>> {
        Box::pin(async move { self.board.list(filter.project_id.as_deref(), filter.epic_id.as_deref()) })
    }

    fn get(&self, id: String) -> BoxFuture<'_, OrchResult<Ticket>> {
        Box::pin(async move { self.board.get(&id) })
    }

    fn save(&self, request: TicketSave) -> BoxFuture<'_, OrchResult<TicketAnswer>> {
        Box::pin(async move { self.board.save(request, &self.publish()) })
    }

    fn move_lane(&self, request: TicketMove) -> BoxFuture<'_, OrchResult<TicketAnswer>> {
        Box::pin(async move {
            match &self.work {
                Some(w) => w.move_lane(request, None),
                None => self.board.move_lane(request, &self.publish()),
            }
        })
    }

    fn delete(&self, id: String) -> BoxFuture<'_, OrchResult<BoardAnswer>> {
        Box::pin(async move { self.board.delete(&id, &self.publish()) })
    }

    fn link(&self, request: TicketLinkRequest) -> BoxFuture<'_, OrchResult<TicketAnswer>> {
        Box::pin(async move { self.board.link(request, &self.publish()) })
    }

    fn unlink(&self, request: TicketLinkRequest) -> BoxFuture<'_, OrchResult<TicketAnswer>> {
        Box::pin(async move { self.board.unlink(request, &self.publish()) })
    }

    fn reorder(&self, request: TicketReorder) -> BoxFuture<'_, OrchResult<BoardAnswer>> {
        Box::pin(async move { self.board.reorder(request, &self.publish()) })
    }

    fn epics(&self, project_id: String) -> BoxFuture<'_, OrchResult<Vec<Epic>>> {
        Box::pin(async move { self.board.epics(&project_id) })
    }

    fn epic_save(&self, request: EpicSave) -> BoxFuture<'_, OrchResult<Epic>> {
        Box::pin(async move { self.board.epic_save(request) })
    }

    fn epic_base(&self, request: EpicBaseRequest) -> BoxFuture<'_, OrchResult<Epic>> {
        Box::pin(async move { self.board.epic_base(request) })
    }

    fn record_creator(&self, ticket_id: String, creator: CommentAuthor, notify: Option<Vec<String>>) -> BoxFuture<'_, OrchResult<()>> {
        Box::pin(async move { self.board.record_creator(&ticket_id, &creator, notify) })
    }

    fn comment_add(&self, request: CommentAdd) -> BoxFuture<'_, OrchResult<Comment>> {
        Box::pin(async move {
            let c = self.board.comment_add(request)?;
            emit_commented(&self.bus, &c);
            Ok(c)
        })
    }

    fn comment_resolve(&self, id: String, by: CommentAuthor) -> BoxFuture<'_, OrchResult<Comment>> {
        Box::pin(async move { self.board.comment_resolve(&id, &by) })
    }

    fn comments(&self, query: CommentQuery) -> BoxFuture<'_, OrchResult<Vec<Comment>>> {
        Box::pin(async move { self.board.comments(query) })
    }

    fn notify_edit(&self, request: NotifyEdit) -> BoxFuture<'_, OrchResult<Vec<String>>> {
        Box::pin(async move { self.board.notify_edit(request) })
    }

    fn assign(&self, request: TicketAssign) -> BoxFuture<'_, OrchResult<TicketAnswer>> {
        Box::pin(async move { self.work("ticket_assign")?.assign(&request) })
    }

    fn unassign(&self, request: TicketUnassign) -> BoxFuture<'_, OrchResult<TicketAnswer>> {
        Box::pin(async move { self.work("ticket_unassign")?.unassign(&request) })
    }

    fn spawn_limit(&self, project_id: Option<String>) -> BoxFuture<'_, OrchResult<Limit>> {
        Box::pin(async move { self.work("spawn_limit")?.spawn_limit(project_id.as_deref()) })
    }

    fn on_done(&self, report: DoneReport) -> BoxFuture<'_, OrchResult<()>> {
        Box::pin(async move { self.work("on_done")?.on_done(&report) })
    }

    fn start(&self, request: TicketStartRequest) -> BoxFuture<'_, OrchResult<TicketAnswer>> {
        Box::pin(async move { self.work("ticket_start")?.start(&request.id, StartOrigin::Move, request.started_by.as_deref()) })
    }

    fn start_next(&self, request: StartNextRequest) -> BoxFuture<'_, OrchResult<TicketAnswer>> {
        Box::pin(async move { self.work("ticket_start_next")?.start_next(&request) })
    }

    fn agent_queue(&self, request: AgentQueueRequest) -> BoxFuture<'_, OrchResult<AgentQueueState>> {
        Box::pin(async move { self.work("agent_queue")?.agent_queue(&request) })
    }

    fn done(&self, request: TicketDone) -> BoxFuture<'_, OrchResult<DoneAnswer>> {
        Box::pin(async move { self.work("ticket_done")?.done(&request) })
    }
}
