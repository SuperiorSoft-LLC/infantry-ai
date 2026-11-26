// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The mail frames: `mail_send`, `mail_read`, `mail_inbox`, `mail_outbox`, `mail_thread`, `mail_threads`,
//! `mail_waiting`. Every mailbox change is also broadcast as a `mail` frame (a refusal as `mail_refused`).
use std::sync::Arc;

use infantry_core::bus::EventBus;
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_protocol::frames as f;

use super::mailbox::{MailEvent, MailResult};
use super::message::{MailRefused, MailSendRequest, Message};
use super::router::MailRouter;

/// The owner's mailbox id: mail the UI sends with no session comes from here.
pub const OWNER: &str = "owner";

pub struct MailUnit {
    router: Arc<MailRouter>,
}

pub(crate) fn json<T: serde::Serialize>(v: &T) -> String {
    serde_json::to_string(v).unwrap_or_else(|_| "null".into())
}

pub(crate) fn reply(env: &Envelope, frame: &str, data: String) -> Envelope {
    let mut out = Envelope::new(frame, data);
    out.request_id = env.request_id.clone();
    out.session_id = env.session_id.clone();
    out
}

fn mail_frame(m: &Message) -> Envelope {
    let mut e = Envelope::new(f::MAIL, json(m));
    e.session_id = Some(m.to.clone());
    e
}

impl MailUnit {
    pub fn new(router: Arc<MailRouter>) -> Self {
        MailUnit { router }
    }

    /// Broadcasts every mailbox change on `bus` (call once at wiring).
    pub fn attach_bus(router: &MailRouter, bus: EventBus) {
        router.mailbox().set_event_sink(move |e| {
            match e {
                MailEvent::Sent(m) | MailEvent::Changed(m) => {
                    bus.publish(mail_frame(m));
                }
                MailEvent::Refused { from, to, reason } => {
                    bus.publish(Envelope::new(f::MAIL_REFUSED, json(&MailRefused { from: from.clone(), to: to.clone(), thread_id: None, reason: reason.clone() })));
                }
                MailEvent::CeilingReached { thread_id, limit } => {
                    bus.emit("mail.ceiling", serde_json::json!({ "threadId": thread_id, "limit": limit }));
                }
            };
        });
    }

    fn acting(env: &Envelope, ctx: &FrameCtx) -> String {
        env.session_id.clone().filter(|s| !s.trim().is_empty()).unwrap_or_else(|| if ctx.owner { OWNER.into() } else { String::new() })
    }

    /// The session a list frame is about: its data, else the envelope's session, else the owner.
    fn subject(env: &Envelope, ctx: &FrameCtx) -> String {
        let d = env.data.trim().trim_matches('"');
        if d.is_empty() {
            Self::acting(env, ctx)
        } else {
            d.to_string()
        }
    }
}

impl FrameUnit for MailUnit {
    fn name(&self) -> &'static str {
        "mail"
    }

    fn frames(&self) -> &'static [&'static str] {
        &[f::MAIL_SEND, f::MAIL_READ, f::MAIL_INBOX, f::MAIL_OUTBOX, f::MAIL_THREAD, f::MAIL_THREADS, f::MAIL_WAITING, f::MAIL_ARCHIVE]
    }

    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let mb = self.router.mailbox();
        match env.r#type.as_str() {
            f::MAIL_SEND => {
                let Ok(mut req) = serde_json::from_str::<MailSendRequest>(&env.data) else {
                    let r = MailRefused { from: String::new(), to: String::new(), thread_id: None, reason: "malformed mail_send payload".into() };
                    return Ok(vec![reply(env, f::MAIL_REFUSED, json(&r))]);
                };
                if req.from.trim().is_empty() {
                    req.from = Self::acting(env, ctx);
                }
                Ok(vec![match self.router.send(&req, false) {
                    MailResult::Sent(m) => {
                        let cur = mb.message(&m.id).unwrap_or(m);
                        let mut e = reply(env, f::MAIL, json(&cur));
                        e.session_id = Some(cur.to.clone());
                        e
                    }
                    MailResult::Refused(reason) => reply(env, f::MAIL_REFUSED, json(&MailRefused { from: req.from, to: req.to, thread_id: req.thread_id, reason })),
                }])
            }
            f::MAIL_READ => {
                let id = env.data.trim().trim_matches('"');
                let found = mb.find(id).map_err(|e| Refusal::new(f::MAIL_READ, e))?;
                let owner_view = ctx.owner && env.session_id.is_none();
                let read = found.and_then(|m| if owner_view { mb.mark_read_by_owner(&m.id) } else { mb.mark_read(&m.id) });
                match read {
                    Some(m) => Ok(vec![reply(env, f::MAIL, json(&m))]),
                    None => Ok(vec![reply(env, f::ERROR, format!("unknown or unreadable message '{id}'"))]),
                }
            }
            f::MAIL_ARCHIVE => {
                if !ctx.owner || env.session_id.is_some() {
                    return Err(Refusal::new(f::MAIL_ARCHIVE, "only the owner archives the owner's mail"));
                }
                let ids: Vec<String> = serde_json::from_str(env.data.trim()).map_err(|_| Refusal::new(f::MAIL_ARCHIVE, "data must be a JSON array of message ids"))?;
                Ok(mb.archive_by_owner(&ids).iter().map(|m| reply(env, f::MAIL, json(m))).collect())
            }
            f::MAIL_INBOX | f::MAIL_OUTBOX => {
                let who = Self::subject(env, ctx);
                let list = match (env.r#type.as_str(), who.as_str()) {
                    // The owner's whole mail view.
                    (_, "*") if ctx.owner => mb.all(),
                    (f::MAIL_INBOX, w) => mb.inbox(w),
                    (_, w) => mb.outbox(w),
                };
                Ok(vec![reply(env, f::MAILS, json(&list))])
            }
            f::MAIL_THREAD => Ok(vec![reply(env, f::MAILS, json(&mb.thread(env.data.trim().trim_matches('"'))))]),
            f::MAIL_THREADS => Ok(vec![reply(env, f::MAIL_THREADS, json(&mb.threads()))]),
            f::MAIL_WAITING => Ok(vec![reply(env, f::MAIL_WAITING, json(&self.router.waiting()))]),
            other => Err(Refusal::new(other, "not a mail frame")),
        }
    }
}
