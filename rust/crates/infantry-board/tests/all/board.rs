// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The board over a temp infantry.db with the real Kotlin schema, through the LocalOrchestrator and the unit.
use crate::common;

use common::*;
use infantry_board::BoardUnit;
use infantry_core::frames::{FrameCtx, FrameUnit};
use infantry_orchestrator_api::{OrchError, Orchestrator, TicketListQuery, TicketMove};
use infantry_protocol::Envelope;

fn lanes(f: &Fixture) -> Vec<(String, String, i32)> {
    block_on(f.orch.tickets(TicketListQuery::default()))
        .unwrap()
        .into_iter()
        .map(|t| (t.id, t.status, t.position))
        .collect()
}

fn events(f: &Fixture) -> Vec<String> {
    f.board
        .db()
        .read(|c| {
            let mut s = c.prepare("SELECT ticket_id, kind, coalesce(from_status,''), coalesce(to_status,'') FROM ticket_events ORDER BY seq")?;
            let rows = s
                .query_map([], |r| Ok(format!("{} {} {}>{}", r.get::<_, String>(0)?, r.get::<_, String>(1)?, r.get::<_, String>(2)?, r.get::<_, String>(3)?)))?
                .collect::<Result<Vec<_>, _>>()?;
            Ok(rows)
        })
        .unwrap()
}

#[test]
fn create_list_get_edit_move_delete() {
    let f = fixture("crud", kotlin_daemon_env());
    standard_board(&f);
    assert_eq!(
        lanes(&f),
        vec![
            ("ticket-aaaa0003".into(), "backlog".into(), 0),
            ("ticket-aaaa0002".into(), "todo".into(), 0),
            ("ticket-aaaa0001".into(), "todo".into(), 1),
            ("ticket-aaaa0004".into(), "pending".into(), 0),
            ("ticket-aaaa0005".into(), "done".into(), 0),
        ]
    );
    let t = block_on(f.orch.get("ticket-aaaa0001".into())).unwrap();
    assert_eq!((t.title.as_str(), t.description.as_str(), t.ticket_type.as_deref()), ("First, edited", "body", Some("defect")));
    assert_eq!(t.updated_at, 1_700_000_002_000);
    let held = block_on(f.orch.get("ticket-aaaa0004".into())).unwrap();
    assert_eq!(held.held_by, vec!["ticket-aaaa0001".to_string()]);
    assert!(block_on(f.orch.get("ticket-aaaa0005".into())).unwrap().wont_fix);
    assert!(matches!(block_on(f.orch.get("ticket-aaaa0006".into())), Err(OrchError::Refused(_))));

    // Filters.
    let p1 = block_on(f.orch.tickets(serde_json::from_str(r#"{"projectId":"p1"}"#).unwrap())).unwrap();
    assert_eq!(p1.iter().map(|t| t.id.as_str()).collect::<Vec<_>>(), vec!["ticket-aaaa0001"]);

    // Closing the blocker frees the held ticket.
    mv(&f.orch, r#"{"id":"ticket-aaaa0001","status":"done"}"#);
    assert_eq!(block_on(f.orch.get("ticket-aaaa0004".into())).unwrap().status, "todo");

    let ev = events(&f);
    assert!(ev.contains(&"ticket-aaaa0001 created >todo".to_string()), "{ev:#?}");
    assert!(ev.contains(&"ticket-aaaa0005 done todo>done".to_string()), "{ev:#?}");
    assert!(ev.contains(&"ticket-aaaa0006 deleted todo>".to_string()), "{ev:#?}");
}

#[test]
fn broken_rules_are_refused_and_write_nothing() {
    let f = fixture("rules", kotlin_daemon_env());
    f.env.push_ids(&["ticket-bbbb0001"]);
    save(&f.orch, r#"{"title":"One"}"#);
    let before = lanes(&f);
    let bad = [r#"{"title":""}"#, r#"{"title":"x","status":"nope"}"#, r#"{"title":"x","ticketType":"saga"}"#, r#"{"id":"ticket-missing","title":"x"}"#];
    for b in bad {
        let r = block_on(f.orch.save(serde_json::from_str(b).unwrap()));
        assert!(matches!(r, Err(OrchError::Refused(_))), "{b}: {r:?}");
    }
    let m: TicketMove = serde_json::from_str(r#"{"id":"ticket-bbbb0001","status":"in_progress"}"#).unwrap();
    assert!(matches!(block_on(f.orch.move_lane(m)), Err(OrchError::Unsupported(_))));
    assert_eq!(lanes(&f), before);
}

fn unit_call(u: &BoardUnit, bus: &infantry_core::bus::EventBus, ty: &str, data: &str, rid: Option<&str>) -> Result<Option<Envelope>, String> {
    let ctx = FrameCtx { conn_id: 1, owner: true, bus: bus.clone() };
    let mut req = Envelope::new(ty, data);
    req.request_id = rid.map(Into::into);
    let mut out = u.handle(&req, &ctx).map_err(|e| e.0)?;
    assert!(out.len() <= 1);
    Ok(out.pop())
}

#[test]
fn the_unit_pushes_and_answers_like_kotlin() {
    let f = fixture("unit", kotlin_daemon_env());
    f.env.push_ids(&["ticket-dddd0001", "ticket-dddd0002"]);
    let unit = BoardUnit::new(f.board.clone());
    let mut rx = f.bus.subscribe();

    // No requestId: the push is the answer.
    let a = unit_call(&unit, &f.bus, "ticket_save", r#"{"title":"One"}"#, None).unwrap();
    assert!(a.is_none());
    let pushed = rx.try_recv().unwrap();
    assert_eq!(pushed.r#type, "ticket");
    let t: serde_json::Value = serde_json::from_str(&pushed.data).unwrap();
    assert_eq!(t["id"], "ticket-dddd0001");
    // Every field written, nulls included (encodeDefaults).
    assert!(t.as_object().unwrap().contains_key("agentId") && t["agentId"].is_null());

    // With a requestId: pushed AND answered, the id echoed.
    let a = unit_call(&unit, &f.bus, "ticket_save", r#"{"title":"Two"}"#, Some("r1")).unwrap().unwrap();
    assert_eq!((a.r#type.as_str(), a.request_id.as_deref()), ("ticket", Some("r1")));
    assert!(rx.try_recv().is_ok());

    let l = unit_call(&unit, &f.bus, "ticket_list", "", None).unwrap().unwrap();
    assert_eq!(l.r#type, "tickets");
    assert_eq!(serde_json::from_str::<Vec<serde_json::Value>>(&l.data).unwrap().len(), 2);

    let e = unit_call(&unit, &f.bus, "ticket_save", r#"{"title":"x","bogus":1}"#, None).unwrap_err();
    assert!(e.starts_with("ticket_save refused: malformed"), "{e}");
    let e = unit_call(&unit, &f.bus, "ticket_get", "ticket-nope", None).unwrap_err();
    assert!(e.starts_with("ticket_get refused:"), "{e}");

    let d = unit_call(&unit, &f.bus, "ticket_delete", "ticket-dddd0001", None).unwrap();
    assert!(d.is_none());
    let pushed = rx.try_recv().unwrap();
    assert_eq!(pushed.r#type, "tickets");
}
