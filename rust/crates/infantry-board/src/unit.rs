// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The board's [`FrameUnit`]: `ticket_list`, `ticket_get`, `ticket_save`, `ticket_move`, `ticket_delete`, and (with a
//! [`Dispatcher`]) the work frames `ticket_start`, `ticket_start_next`, `ticket_assign`, `ticket_unassign`, `agent_queue`,
//! `ticket_done`; a `ticket_move` into in_progress is then a start.
//!
//! Wire rules (Kotlin `BoardFrames.kt`): a change is pushed to every client on the bus (`ticket` per
//! changed ticket, the requested one first; `tickets` after a delete). The requester gets a direct
//! answer only when nothing was pushed for its ticket, or when it sent a `requestId` (contract 1.87.0),
//! which the answer echoes. A refusal is `<frame> refused: <why>`, any other failure `<frame> failed: <what>`.
use std::sync::Arc;

use infantry_core::frames::{FrameCtx, FrameUnit, Refusal};
use infantry_orchestrator_api::dto::{
    decode_strict, AgentSwitch, Epic, EpicBaseRequest, EpicSave, TicketLinkRequest, TicketListQuery, TicketMove, TicketReorder, TicketSave,
};
use infantry_orchestrator_api::{
    AgentQueueRequest, OrchError, StartNextRequest, StartOrigin, TicketAssign, TicketClose, TicketDone, TicketRestart, TicketStartRequest, TicketUnassign,
};
use infantry_protocol::Envelope;

use crate::board::{ticket_envelope, tickets_envelope, Board};
use crate::dispatch::Dispatcher;

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

/// Every frame of a unit with the work half ([`FRAMES`] plus the work frames).
pub const ALL_FRAMES: &[&str] = &[
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
    "ticket_start",
    "ticket_start_next",
    "ticket_assign",
    "ticket_unassign",
    "agent_queue",
    "agent_switch",
    "ticket_done",
    "ticket_close",
    "ticket_restart",
];

pub struct BoardUnit {
    board: Arc<Board>,
    work: Option<Arc<Dispatcher>>,
}

impl BoardUnit {
    pub fn new(board: Arc<Board>) -> BoardUnit {
        BoardUnit { board, work: None }
    }

    pub fn with_dispatcher(work: Arc<Dispatcher>) -> BoardUnit {
        BoardUnit { board: work.board().clone(), work: Some(work) }
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
        if self.work.is_some() {
            ALL_FRAMES
        } else {
            FRAMES
        }
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
                let mut ts = self.board.list(q.project_id.as_deref(), q.epic_id.as_deref()).map_err(r)?;
                if q.compact {
                    ts = ts.into_iter().map(|t| t.compacted()).collect();
                }
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
                let a = match &self.work {
                    Some(w) => w.move_lane(req, env.session_id.as_deref()),
                    None => self.board.move_lane(req, &publish),
                }
                .map_err(r)?;
                Ok(answer(a.pushed, ticket_envelope(&a.ticket)))
            }
            "ticket_start" | "ticket_start_next" | "ticket_assign" | "ticket_unassign" | "agent_queue" | "agent_switch" | "ticket_done" | "ticket_close" | "ticket_restart" => {
                let Some(w) = &self.work else { return Err(r(OrchError::Unsupported(format!("{frame} needs the work half")))) };
                let bad = |m: String| r(OrchError::Refused(m));
                let what = format!("{frame} payload");
                let a = match frame {
                    "ticket_start" => {
                        let q: TicketStartRequest = decode_strict(&env.data, &what).map_err(bad)?;
                        w.start(&q.id, StartOrigin::Move, q.started_by.as_deref().or(env.session_id.as_deref())).map_err(r)?
                    }
                    "ticket_start_next" => w.start_next(&decode_strict::<StartNextRequest>(&env.data, &what).map_err(bad)?).map_err(r)?,
                    "ticket_assign" => w.assign(&decode_strict::<TicketAssign>(&env.data, &what).map_err(bad)?).map_err(r)?,
                    "ticket_unassign" => w.unassign(&decode_strict::<TicketUnassign>(&env.data, &what).map_err(bad)?).map_err(r)?,
                    "agent_switch" => w.switch(&decode_strict::<AgentSwitch>(&env.data, &what).map_err(bad)?).map_err(r)?,
                    "agent_queue" => {
                        let s = w.agent_queue(&decode_strict::<AgentQueueRequest>(&env.data, &what).map_err(bad)?).map_err(r)?;
                        return Ok(answer(false, Envelope::new("agent_queue_state", serde_json::to_string(&s).expect("queue json"))));
                    }
                    "ticket_close" => {
                        let c = w.close(&decode_strict::<TicketClose>(&env.data, &what).map_err(bad)?).map_err(r)?;
                        let mut e = Envelope::new("ticket_closed", serde_json::to_string(&c).expect("close json"));
                        e.request_id = env.request_id.clone();
                        return Ok(vec![e]);
                    }
                    "ticket_restart" => {
                        let mut req = decode_strict::<TicketRestart>(&env.data, &what).map_err(bad)?;
                        if req.restarted_by.is_none() {
                            req.restarted_by = env.session_id.clone();
                        }
                        let c = w.restart(&req).map_err(r)?;
                        let mut e = Envelope::new("ticket_restarted", serde_json::to_string(&c).expect("restart json"));
                        e.request_id = env.request_id.clone();
                        return Ok(vec![e]);
                    }
                    _ => {
                        let d = w.done(&decode_strict::<TicketDone>(&env.data, &what).map_err(bad)?).map_err(r)?;
                        return Ok(answer(true, ticket_envelope(&d.ticket)));
                    }
                };
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
