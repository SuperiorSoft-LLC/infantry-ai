// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Links, reorder, epics and comments over a temp infantry.db with the real Kotlin schema.
use crate::common;

use common::*;
use infantry_core::frames::{FrameCtx, FrameUnit};
use infantry_orchestrator_api::{
    CommentAdd, CommentAuthor, CommentQuery, EpicBaseRequest, EpicSave, NotifyEdit, OrchError, Orchestrator, TicketLinkRequest,
    TicketListQuery, TicketReorder,
};
use infantry_protocol::Envelope;

fn link(ty: &str, a: &str, b: &str) -> TicketLinkRequest {
    TicketLinkRequest { ticket_id: a.into(), related_id: b.into(), r#type: ty.into() }
}

fn three(f: &Fixture) {
    f.env.push_ids(&["ticket-00000001", "ticket-00000002", "ticket-00000003"]);
    save(&f.orch, r#"{"title":"A","projectId":"p1"}"#);
    save(&f.orch, r#"{"title":"B","projectId":"p1"}"#);
    save(&f.orch, r#"{"title":"C"}"#);
}

#[test]
fn links_hold_refuse_cycles_and_unlink() {
    let f = fixture("links", kotlin_daemon_env());
    three(&f);
    // B blocks A: A goes pending.
    let a = block_on(f.orch.link(link("blocks", "ticket-00000002", "ticket-00000001"))).unwrap();
    assert_eq!(a.ticket.id, "ticket-00000002");
    assert_eq!(block_on(f.orch.get("ticket-00000001".into())).unwrap().status, "pending");
    // Idempotent.
    block_on(f.orch.link(link("blocked_by", "ticket-00000001", "ticket-00000002"))).unwrap();
    // A blocks B would close a cycle.
    let e = block_on(f.orch.link(link("blocked_by", "ticket-00000002", "ticket-00000001"))).unwrap_err();
    assert!(matches!(&e, OrchError::Refused(m) if m.contains("would close a cycle")), "{e:?}");
    assert!(matches!(block_on(f.orch.link(link("likes", "ticket-00000001", "ticket-00000002"))), Err(OrchError::Refused(_))));
    assert!(matches!(block_on(f.orch.link(link("relates_to", "ticket-00000001", "ticket-00000001"))), Err(OrchError::Refused(_))));
    // Unlink releases A; a second unlink is refused.
    block_on(f.orch.unlink(link("blocks", "ticket-00000002", "ticket-00000001"))).unwrap();
    assert_eq!(block_on(f.orch.get("ticket-00000001".into())).unwrap().status, "todo");
    assert!(matches!(block_on(f.orch.unlink(link("blocks", "ticket-00000002", "ticket-00000001"))), Err(OrchError::Refused(_))));
}

#[test]
fn reorder_puts_listed_on_top() {
    let f = fixture("reorder", kotlin_daemon_env());
    three(&f);
    let b = block_on(f.orch.reorder(TicketReorder { status: Some("todo".into()), ids: vec!["ticket-00000003".into(), "ticket-00000002".into()] }))
        .unwrap();
    let ids: Vec<&str> = b.tickets.iter().map(|t| t.id.as_str()).collect();
    assert_eq!(ids, vec!["ticket-00000003", "ticket-00000002", "ticket-00000001"]);
    assert!(b.pushed);
    let dup = block_on(f.orch.reorder(TicketReorder { status: None, ids: vec!["ticket-00000001".into(), "ticket-00000001".into()] }));
    assert!(matches!(dup, Err(OrchError::Refused(_))));
    let wrong = block_on(f.orch.reorder(TicketReorder { status: Some("backlog".into()), ids: vec!["ticket-00000001".into()] }));
    assert!(matches!(wrong, Err(OrchError::Refused(m)) if m.contains("is not in backlog")));
}

#[test]
fn epics_list_derived_ticket_ids_and_base() {
    let f = fixture("epics", kotlin_daemon_env());
    let unknown = block_on(f.orch.epic_save(EpicSave { project_id: "p1".into(), name: "E".into(), goal: String::new() }));
    assert!(matches!(unknown, Err(OrchError::Refused(m)) if m.starts_with("unknown project")));
    f.board.db().write(|c| c.execute("INSERT INTO projects(id, name, created_at) VALUES ('p1', 'P', 1)", [])).unwrap();
    let e = block_on(f.orch.epic_save(EpicSave { project_id: "p1".into(), name: " E ".into(), goal: "g".into() })).unwrap();
    assert!(e.id.starts_with("epic-") && e.name == "E");
    f.env.push_ids(&["ticket-00000009"]);
    save(&f.orch, &format!(r#"{{"title":"T","projectId":"p1","epicId":"{}"}}"#, e.id));
    let list = block_on(f.orch.epics("p1".into())).unwrap();
    assert_eq!(list[0].story_ids, vec!["ticket-00000009".to_string()]);
    // No repository: a set is refused, a read and a clear answer.
    let set = block_on(f.orch.epic_base(EpicBaseRequest { epic_id: e.id.clone(), branch: Some("main".into()), clear: false }));
    assert!(matches!(set, Err(OrchError::Refused(_))));
    let read = block_on(f.orch.epic_base(EpicBaseRequest { epic_id: e.id.clone(), branch: None, clear: true })).unwrap();
    assert_eq!(read.base_branch, "");

    // The frames.
    let unit = infantry_board::BoardUnit::new(f.board.clone());
    let ctx = FrameCtx { conn_id: 1, owner: true, bus: f.bus.clone() };
    let out = unit.handle(&Envelope::new("epic_list", "p1"), &ctx).map_err(|e| e.0).unwrap();
    assert_eq!(out[0].r#type, "epics");
    let bad = unit.handle(&Envelope::new("epic_save", r#"{"projectId":"p1"}"#), &ctx).unwrap_err();
    assert!(bad.0.starts_with("epic_save refused: malformed"), "{}", bad.0);
}

#[test]
fn comments_notify_resolve_and_read() {
    let mut env = kotlin_daemon_env();
    env.registry = vec!["qa".into(), "role:pm".into()];
    env.live = vec![("qa".into(), "sess-qa-1".into())];
    let f = fixture("comments", env);
    three(&f);
    let owner = CommentAuthor::owner();
    block_on(f.orch.record_creator("ticket-00000001".into(), owner.clone(), Some(vec!["qa".into(), "pm".into()]))).unwrap();
    let mut events = f.bus.subscribe_events();
    let c = block_on(f.orch.comment_add(CommentAdd {
        ticket_id: "00000001".into(),
        body: "done in abc123\nmore".into(),
        kind: Some("done".into()),
        state: None,
        author: owner.clone(),
    }))
    .unwrap();
    assert_eq!((c.kind.as_str(), c.state.as_str()), ("done", "open"));
    // qa woke; role:pm has no live session.
    assert_eq!(c.undelivered, vec!["role:pm".to_string()]);
    assert_eq!(f.env.notified.lock().unwrap().len(), 1);
    assert_eq!(events.try_recv().unwrap().name, "ticket.commented");

    let open = block_on(f.orch.comments(CommentQuery { ticket_id: "ticket-00000001".into(), ..Default::default() })).unwrap();
    assert_eq!(open.len(), 1);
    let r = block_on(f.orch.comment_resolve(c.id[..8].to_string(), owner.clone())).unwrap();
    assert_eq!((r.state.as_str(), r.resolved_by.as_deref()), ("resolved", Some("owner")));
    let open = block_on(f.orch.comments(CommentQuery { ticket_id: "ticket-00000001".into(), ..Default::default() })).unwrap();
    assert!(open.is_empty());

    let bad = block_on(f.orch.comment_add(CommentAdd { ticket_id: "ticket-00000001".into(), body: "x".into(), kind: Some("saga".into()), state: None, author: owner }));
    assert!(matches!(bad, Err(OrchError::Refused(_))));
    let n = block_on(f.orch.notify_edit(NotifyEdit { ticket_id: "ticket-00000001".into(), remove: vec!["pm".into()], ..Default::default() })).unwrap();
    assert_eq!(n, vec!["qa".to_string()]);
    let refused = block_on(f.orch.notify_edit(NotifyEdit { ticket_id: "ticket-00000001".into(), add: vec!["ghost".into()], ..Default::default() }));
    assert!(matches!(refused, Err(OrchError::Refused(_))));
    let _ = block_on(f.orch.tickets(TicketListQuery::default())).unwrap();
}

#[test]
fn notify_list_takes_roles_and_sessions() {
    let mut env = kotlin_daemon_env();
    env.live_roles = vec![("pm".into(), "sess-pm-1".into()), ("pm".into(), "sess-pm-2".into()), ("qa".into(), "sess-w".into())];
    let f = fixture("notifyroles", env);
    three(&f);
    let owner = CommentAuthor::owner();
    block_on(f.orch.record_creator("ticket-00000001".into(), owner.clone(), Some(vec!["role:pm".into(), "role:qa".into(), "sess-w".into()]))).unwrap();
    let c = block_on(f.orch.comment_add(CommentAdd {
        ticket_id: "00000001".into(),
        body: "hello team".into(),
        kind: None,
        state: None,
        author: owner,
    }))
    .unwrap();
    // pm resolves to both holders, qa to its one holder, and the session listed too is mailed once, not twice.
    let got = f.env.notified.lock().unwrap().clone();
    assert_eq!(got.len(), 3, "{got:?}");
    assert!(c.undelivered.is_empty(), "{:?}", c.undelivered);
    assert!(got.iter().all(|l| l.contains("[TICKET COMMENT]") && l.contains("hello team")));
    // A role nobody holds is recorded undelivered; a plain unknown word is refused.
    let n = block_on(f.orch.notify_edit(NotifyEdit { ticket_id: "ticket-00000001".into(), add: vec!["role:ghost".into()], ..Default::default() })).unwrap();
    assert!(n.contains(&"role:ghost".to_string()));
    let refused = block_on(f.orch.notify_edit(NotifyEdit { ticket_id: "ticket-00000001".into(), add: vec!["ghost".into()], ..Default::default() }));
    assert!(matches!(refused, Err(OrchError::Refused(_))));
    // The human user: "user" is stored as "owner" and gets an app notice, not mail.
    let n = block_on(f.orch.notify_edit(NotifyEdit { ticket_id: "ticket-00000001".into(), add: vec!["user".into()], ..Default::default() })).unwrap();
    assert!(n.contains(&"owner".to_string()));
    let before = f.env.notified.lock().unwrap().len();
    block_on(f.orch.comment_add(CommentAdd {
        ticket_id: "00000001".into(),
        body: "for the user".into(),
        kind: None,
        state: None,
        author: CommentAuthor::daemon(),
    }))
    .unwrap();
    let got = f.env.notified.lock().unwrap().clone();
    assert!(got[before..].iter().any(|l| l.starts_with("owner ") && l.contains("for the user")), "{got:?}");
}
