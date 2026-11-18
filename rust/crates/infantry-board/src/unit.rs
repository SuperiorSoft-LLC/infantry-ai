// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The board's [`FrameUnit`]: `ticket_list`, `ticket_get`, `ticket_save`, `ticket_move`, `ticket_delete`.
//!
//! Wire rules (Kotlin `BoardFrames.kt`): a change is pushed to every client on the bus (`ticket` per
//! changed ticket, the requested one first; `tickets` after a delete). The requester gets a direct
//! answer only when nothing was pushed for its ticket, or when it sent a `requestId` (contract 1.87.0),
//! which the answer echoes. A refusal is `<frame> refused: <why>`, any other failure `<frame> failed: <what>`.
use std::sync::Arc;

use infantry_core::frames::{FrameCtx, FrameUnit, Refusal};
use infantry_orchestrator_api::dto::{
    decode_strict, Epic, EpicBaseRequest, EpicSave, TicketLinkRequest, TicketListQuery, TicketMove, TicketReorder, TicketSave,
};
use infantry_orchestrator_api::OrchError;
use infantry_protocol::Envelope;

use crate::board::{ticket_envelope, tickets_envelope, Board};

pub const FRAMES: &[&str] = &[
    "ticket_list",
    "ticket_get",
    "ticket_save",
    "ticket_move",
    "ticket_delete",
    "ticket_link",
    "ticket_unlink",
    "ticket_reorder",
    "epic_save",
    "epic_list",
    "epic_base",
];

pub struct BoardUnit {
    board: Arc<Board>,
}

impl BoardUnit {
    pub fn new(board: Arc<Board>) -> BoardUnit {
        BoardUnit { board }
    }
}

/// One epic as an `epic` frame, every field present.
pub fn epic_envelope(e: &Epic) -> Envelope {
    Envelope::new("epic", serde_json::to_string(e).expect("epic json"))
}

fn refusal(frame: &str, e: OrchError) -> Refusal {
    match e {
        OrchError::Refused(m) => Refusal(format!("{frame} refused: {m}")),
        OrchError::Failed(m) => Refusal(format!("{frame} failed: {m}")),
        OrchError::Unsupported(m) => Refusal(format!("{frame} failed: not supported: {m}")),
    }
}

impl FrameUnit for BoardUnit {
    fn name(&self) -> &'static str {
        "board"
    }

    fn frames(&self) -> &'static [&'static str] {
        FRAMES
    }

    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let frame = env.r#type.as_str();
        let direct = env.request_id.is_some();
        let publish = |e: Envelope| {
            ctx.bus.publish(e);
        };
        let answer = |pushed: bool, e: Envelope| -> Vec<Envelope> {
            if pushed && !direct {
                return Vec::new();
            }
            let mut e = e;
            e.request_id = env.request_id.clone();
            vec![e]
        };
        let r = |e| refusal(frame, e);
        match frame {
            "ticket_list" => {
                let q = TicketListQuery::decode(&env.data).map_err(|m| r(OrchError::Refused(m)))?;
                let ts = self.board.list(q.project_id.as_deref(), q.epic_id.as_deref()).map_err(r)?;
                Ok(answer(false, tickets_envelope(&ts)))
            }
            "ticket_get" => {
                let t = self.board.get(&env.data).map_err(r)?;
                Ok(answer(false, ticket_envelope(&t)))
            }
            "ticket_save" => {
                let req: TicketSave = decode_strict(&env.data, "ticket_save payload").map_err(|m| r(OrchError::Refused(m)))?;
                let a = self.board.save(req, &publish).map_err(r)?;
                Ok(answer(a.pushed, ticket_envelope(&a.ticket)))
            }
            "ticket_move" => {
                let req: TicketMove = decode_strict(&env.data, "ticket_move payload").map_err(|m| r(OrchError::Refused(m)))?;
                let a = self.board.move_lane(req, &publish).map_err(r)?;
                Ok(answer(a.pushed, ticket_envelope(&a.ticket)))
            }
            "ticket_delete" => {
                let a = self.board.delete(&env.data, &publish).map_err(r)?;
                Ok(answer(a.pushed, tickets_envelope(&a.tickets)))
            }
            "ticket_link" | "ticket_unlink" => {
                let req: TicketLinkRequest =
                    decode_strict(&env.data, &format!("{frame} payload")).map_err(|m| r(OrchError::Refused(m)))?;
                let a = if frame == "ticket_link" { self.board.link(req, &publish) } else { self.board.unlink(req, &publish) }.map_err(r)?;
                Ok(answer(a.pushed, ticket_envelope(&a.ticket)))
            }
            "ticket_reorder" => {
                let req: TicketReorder = decode_strict(&env.data, "ticket_reorder payload").map_err(|m| r(OrchError::Refused(m)))?;
                let a = self.board.reorder(req, &publish).map_err(r)?;
                Ok(answer(a.pushed, tickets_envelope(&a.tickets)))
            }
            // Epic frames are always answered directly (nothing is pushed).
            "epic_save" => {
                let req: EpicSave = decode_strict(&env.data, "epic_save payload").map_err(|m| r(OrchError::Refused(m)))?;
                let e = self.board.epic_save(req).map_err(r)?;
                Ok(answer(false, epic_envelope(&e)))
            }
            "epic_base" => {
                let req: EpicBaseRequest = decode_strict(&env.data, "epic_base payload").map_err(|m| r(OrchError::Refused(m)))?;
                let e = self.board.epic_base(req).map_err(r)?;
                Ok(answer(false, epic_envelope(&e)))
            }
            "epic_list" => {
                let es = self.board.epics(&env.data).map_err(r)?;
                Ok(answer(false, Envelope::new("epics", serde_json::to_string(&es).expect("epics json"))))
            }
            other => Err(Refusal(format!("{other} refused: not a board frame"))),
        }
    }
}
