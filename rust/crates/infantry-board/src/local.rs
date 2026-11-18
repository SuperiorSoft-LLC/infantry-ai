// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! [`LocalOrchestrator`]: the in-process [`Orchestrator`] over a [`Board`]. Changes are published on
//! the given [`EventBus`] as the same frames the [`crate::unit::BoardUnit`] pushes.
use std::sync::Arc;

use infantry_core::bus::EventBus;
use infantry_orchestrator_api::{
    BoardAnswer, BoxFuture, DoneReport, Limit, OrchError, OrchResult, Orchestrator, Ticket, TicketAnswer, TicketAssign,
    TicketListQuery, TicketMove, TicketSave, TicketUnassign,
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
