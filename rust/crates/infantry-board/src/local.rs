// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! [`LocalOrchestrator`]: the in-process [`Orchestrator`] over a [`Board`]. Changes are published on
//! the given [`EventBus`] as the same frames the [`crate::unit::BoardUnit`] pushes.
use std::sync::Arc;

use infantry_core::bus::EventBus;
use infantry_orchestrator_api::{
    BoardAnswer, BoxFuture, Comment, CommentAdd, CommentAuthor, CommentQuery, DoneReport, Epic, EpicBaseRequest, EpicSave, Limit,
    NotifyEdit, OrchError, OrchResult, Orchestrator, Ticket, TicketAnswer, TicketAssign, TicketLinkRequest, TicketListQuery,
    TicketMove, TicketReorder, TicketSave, TicketUnassign,
};

use crate::board::Board;

pub struct LocalOrchestrator {
    board: Arc<Board>,
    bus: EventBus,
}

impl LocalOrchestrator {
    pub fn new(board: Arc<Board>, bus: EventBus) -> LocalOrchestrator {
        LocalOrchestrator { board, bus }
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

fn not_yet<T: Send + 'static>(what: &str) -> BoxFuture<'static, OrchResult<T>> {
    let m = format!("{what} is not built in the Rust board yet");
    Box::pin(async move { Err(OrchError::Unsupported(m)) })
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
        Box::pin(async move { self.board.move_lane(request, &self.publish()) })
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

    fn assign(&self, _request: TicketAssign) -> BoxFuture<'_, OrchResult<TicketAnswer>> {
        not_yet("ticket_assign (it starts or queues a worker)")
    }

    fn unassign(&self, _request: TicketUnassign) -> BoxFuture<'_, OrchResult<TicketAnswer>> {
        not_yet("ticket_unassign (it ends a worker)")
    }

    fn spawn_limit(&self, _project_id: Option<String>) -> BoxFuture<'_, OrchResult<Limit>> {
        not_yet("spawn_limit (lane caps)")
    }

    fn on_done(&self, _report: DoneReport) -> BoxFuture<'_, OrchResult<()>> {
        not_yet("on_done (the done protocol)")
    }
}
