// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! ARCHIVE-1: archive / restore round trips on the real schema (links, position, in-progress refused, dry run, auto).
use crate::common;

use std::cell::RefCell;

use common::*;
use infantry_board::archive::{ArchiveReport, ArchiveRequest};
use infantry_protocol::Envelope;

const DAY: i64 = 86_400_000;

fn req(json: &str) -> ArchiveRequest {
    serde_json::from_str(json).unwrap()
}

fn apply(f: &Fixture, json: &str) -> Result<ArchiveReport, infantry_orchestrator_api::OrchError> {
    f.board.archive_apply(&req(json), &|_e: Envelope| {})
}

fn query(f: &Fixture, json: &str) -> ArchiveReport {
    f.board.archive_query(&req(json)).unwrap()
}

/// The `todo` lane: (id, position) in board order.
fn lane(f: &Fixture, status: &str) -> Vec<(String, i32)> {
    let mut v: Vec<_> = f.board.list(None, None).unwrap().into_iter().filter(|t| t.status == status).map(|t| (t.id, t.position)).collect();
    v.sort_by_key(|x| x.1);
    v
}

fn links(f: &Fixture) -> Vec<String> {
    f.board
        .db()
        .read(|c| {
            let mut s = c.prepare("SELECT ticket_id || ' ' || type || ' ' || related_id FROM ticket_links ORDER BY 1")?;
            let out = s.query_map([], |r| r.get(0))?.collect();
            out
        })
        .unwrap()
}

fn sql(f: &Fixture, s: &str) {
    f.board.db().write(|c| c.execute_batch(s)).unwrap();
}

/// Three todo tickets a, b, c (in that order) in project p1, epic e1; b relates to c.
fn three(f: &Fixture) {
    f.env.push_ids(&["ticket-aaaa000a", "ticket-aaaa000b", "ticket-aaaa000c", "ticket-aaaa000d"]);
    for t in ["A", "B", "C"] {
        save(&f.orch, &format!(r#"{{"title":"{t}","projectId":"p1","description":"body of {t}"}}"#));
    }
    sql(f, "INSERT INTO ticket_links(ticket_id, related_id, type, created_at) VALUES ('ticket-aaaa000b','ticket-aaaa000c','relates_to',1)");
}

#[test]
fn archive_and_restore_keep_position_and_links() {
    let f = fixture("arch-rt", kotlin_daemon_env());
    three(&f);
    let before = lane(&f, "todo");
    let b_pos = before.iter().find(|x| x.0 == "ticket-aaaa000b").unwrap().1;
    let pushed = RefCell::new(Vec::new());
    let r = f.board.archive_apply(&req(r#"{"op":"archive","ids":["aaaa000b"]}"#), &|e: Envelope| pushed.borrow_mut().push(e.r#type)).unwrap();
    assert_eq!((r.count, r.text.as_str()), (1, "archived 1 ticket(s)"));
    assert_eq!(pushed.borrow().as_slice(), ["tickets"]);
    // Gone from the live board, the lane closed its gap, the link left with it.
    let after = lane(&f, "todo");
    assert_eq!(after.len(), 2);
    assert_eq!(after.iter().map(|x| x.1).collect::<Vec<_>>(), vec![0, 1]);
    assert!(f.board.get("ticket-aaaa000b").is_err());
    assert!(links(&f).is_empty());

    let shown = query(&f, r#"{"op":"show","ids":["000b"]}"#);
    assert_eq!(shown.items[0].links, vec!["ticket-aaaa000b relates_to ticket-aaaa000c".to_string()]);
    assert_eq!(shown.items[0].description.as_deref(), Some("body of B"));
    assert_eq!(query(&f, r#"{"op":"count"}"#).count, 1);
    assert_eq!(query(&f, r#"{"op":"search","text":"of b"}"#).items.len(), 1);
    assert_eq!(query(&f, r#"{"op":"list","projectId":"p2"}"#).items.len(), 0);

    let r = apply(&f, r#"{"op":"restore","ids":["ticket-aaaa000b"]}"#).unwrap();
    assert_eq!(r.count, 1);
    assert_eq!(lane(&f, "todo"), before);
    assert_eq!(f.board.get("ticket-aaaa000b").unwrap().position, b_pos);
    assert_eq!(links(&f), vec!["ticket-aaaa000b relates_to ticket-aaaa000c".to_string()]);
    assert_eq!(query(&f, r#"{"op":"count"}"#).count, 0);
}

#[test]
fn a_link_comes_back_only_when_both_ends_are_live() {
    let f = fixture("arch-links", kotlin_daemon_env());
    three(&f);
    assert_eq!(apply(&f, r#"{"op":"archive","ids":["aaaa000b","aaaa000c"]}"#).unwrap().count, 2);
    apply(&f, r#"{"op":"restore","ids":["aaaa000b"]}"#).unwrap();
    assert!(links(&f).is_empty(), "c is still archived");
    apply(&f, r#"{"op":"restore","ids":["aaaa000c"]}"#).unwrap();
    assert_eq!(links(&f), vec!["ticket-aaaa000b relates_to ticket-aaaa000c".to_string()]);
}

#[test]
fn in_progress_is_never_archived() {
    let f = fixture("arch-ip", kotlin_daemon_env());
    three(&f);
    sql(&f, "UPDATE tickets SET status = 'in_progress', position = 0 WHERE id = 'ticket-aaaa000a'");
    let e = apply(&f, r#"{"op":"archive","ids":["aaaa000a"]}"#).unwrap_err();
    assert!(format!("{e:?}").contains("never archived"), "{e:?}");
    assert!(apply(&f, r#"{"op":"archive","status":"in_progress"}"#).is_err());
    // A project-wide sweep skips it.
    sql(&f, "UPDATE tickets SET epic_id = 'e1' WHERE project_id = 'p1'");
    assert_eq!(apply(&f, r#"{"op":"archive","epicId":"e1"}"#).unwrap().count, 2);
    assert_eq!(f.board.get("ticket-aaaa000a").unwrap().status, "in_progress");
    // Even a direct id list that slipped past the pick leaves it.
    assert!(f.board.archive_ids(&["ticket-aaaa000a".to_string()]).unwrap().is_empty());
}

#[test]
fn dry_run_moves_nothing_and_lane_filter_ages() {
    let f = fixture("arch-dry", kotlin_daemon_env());
    three(&f);
    sql(&f, &format!("UPDATE tickets SET status = 'done', updated_at = {} WHERE id IN ('ticket-aaaa000a','ticket-aaaa000b')", 1_700_000_000_000 - 40 * DAY));
    sql(&f, "UPDATE tickets SET status = 'done' WHERE id = 'ticket-aaaa000c'");
    let r = apply(&f, r#"{"op":"archive","status":"done","olderThanDays":30,"dryRun":true}"#).unwrap();
    assert_eq!((r.count, r.text.as_str()), (2, "would archive 2 ticket(s)"));
    assert_eq!(query(&f, r#"{"op":"count"}"#).count, 0);
    assert_eq!(apply(&f, r#"{"op":"archive","status":"done","olderThanDays":30}"#).unwrap().count, 2);
    assert_eq!(lane(&f, "done").len(), 1);
    let r = apply(&f, r#"{"op":"restore","ids":["aaaa000a"],"dryRun":true}"#).unwrap();
    assert_eq!((r.count, query(&f, r#"{"op":"count"}"#).count), (1, 2));
}

#[test]
fn auto_archive_follows_the_setting_and_the_project_value() {
    let f = fixture("arch-auto", kotlin_daemon_env());
    three(&f);
    save(&f.orch, r#"{"title":"D","projectId":"p2"}"#);
    let old = 1_700_000_000_000 - 40 * DAY;
    sql(&f, &format!("UPDATE tickets SET status = 'done', updated_at = {old}"));
    // Off: nothing moves.
    assert_eq!(f.board.auto_archive(&|_| {}).unwrap(), 0);
    // Global 30 days, p1 overrides with 60: only p2's ticket is due.
    sql(&f, "INSERT INTO settings(key, value, updated_at) VALUES ('board.archive.doneAfterDays','30',0), ('board.archive.doneAfterDays.p1','\"60\"',0)");
    assert_eq!(f.board.auto_archive(&|_| {}).unwrap(), 1);
    assert!(f.board.get("ticket-aaaa000d").is_err());
    assert_eq!(lane(&f, "done").len(), 3);
}

#[test]
fn unknown_ops_and_missing_args_refuse() {
    let f = fixture("arch-bad", kotlin_daemon_env());
    assert!(apply(&f, r#"{"op":"archive"}"#).is_err());
    assert!(apply(&f, r#"{"op":"restore"}"#).is_err());
    assert!(apply(&f, r#"{"op":"burn"}"#).is_err());
    assert!(f.board.archive_query(&req(r#"{"op":"search"}"#)).is_err());
    assert!(f.board.archive_query(&req(r#"{"op":"show","ids":["nope"]}"#)).is_err());
}
