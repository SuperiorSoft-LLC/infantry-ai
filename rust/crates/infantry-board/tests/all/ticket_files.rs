// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! TICKET-FILES: record from git (a canned git host), the `ticket_files` queries, related ranking and the tools.
use crate::common;

use std::sync::Arc;

use common::*;
use infantry_board::ticket_files::TicketFilesQuery;
use infantry_board::{Board, BoardEnv};
use infantry_core::db::Db;

/// A host whose git answers from a table: the args joined by spaces -> stdout.
struct FakeGit(Vec<(String, String)>);

impl BoardEnv for FakeGit {
    fn now_ms(&self) -> i64 {
        1_700_000_000_000
    }
    fn git(&self, _p: Option<&str>, args: &[&str]) -> Option<Vec<u8>> {
        let k = args.join(" ");
        self.0.iter().find(|(a, _)| *a == k).map(|(_, o)| o.clone().into_bytes())
    }
}

fn q(json: &str) -> TicketFilesQuery {
    serde_json::from_str(json).unwrap()
}

fn seed(f: &Fixture) {
    f.board
        .db()
        .write(|c| {
            c.execute_batch(
                "INSERT INTO ticket_files(ticket_id,path,status,added,removed,tip_sha,recorded_at) VALUES
                 ('ticket-aaaa0001','src/a.rs','M',3,1,'1234567890ab',1),('ticket-aaaa0001','src/b.rs','A',10,0,'1234567890ab',1),
                 ('ticket-aaaa0002','src/a.rs','M',1,1,NULL,2),('ticket-aaaa0002','src/c.rs','M',2,2,NULL,2),
                 ('ticket-aaaa0003','src/a.rs','M',1,0,NULL,3),('ticket-aaaa0003','src/b.rs','M',1,0,NULL,3);",
            )
        })
        .unwrap();
}

#[test]
fn queries_answer_by_ticket_path_and_related() {
    let f = fixture("tfiles-q", kotlin_daemon_env());
    seed(&f);
    let t = f.board.ticket_files_query(&q(r#"{"ticketId":"aaaa0001"}"#)).unwrap();
    assert!(t.starts_with("ticket-aaaa0001 : 2 files, +13 -1 @12345678"), "{t}");
    assert!(t.contains("A +10 -0  src/b.rs"), "{t}");
    let p = f.board.ticket_files_query(&q(r#"{"paths":["a.rs"]}"#)).unwrap();
    assert!(p.starts_with("1 files changed by 3 tickets (a.rs)"), "{p}");
    let r = f.board.ticket_files_query(&q(r#"{"ticketId":"ticket-aaaa0001","related":true,"top":1}"#)).unwrap();
    // aaaa0003 shares both files (jaccard 1.0) and ranks first; top 1 keeps only it.
    assert!(r.contains("ticket-aaaa0003") && !r.contains("ticket-aaaa0002 "), "{r}");
    let capped = f.board.ticket_files_query(&q(r#"{"ticketId":"aaaa0001","limit":1}"#)).unwrap();
    assert!(capped.ends_with("... 1 more lines (--limit)"), "{capped}");
    assert!(f.board.ticket_files_query(&q("{}")).is_err());
    let none = f.board.ticket_files_query(&q(r#"{"ticketId":"ffff0000"}"#)).unwrap();
    assert!(none.contains("no files recorded"), "{none}");
}

#[test]
fn settings_cap_the_lines() {
    let f = fixture("tfiles-set", kotlin_daemon_env());
    seed(&f);
    f.board.db().write(|c| c.execute("INSERT INTO settings(key, value, updated_at) VALUES ('limits.ticketFiles.maxLines', '1', 1)", [])).unwrap();
    let t = f.board.ticket_files_query(&q(r#"{"ticketId":"aaaa0001"}"#)).unwrap();
    assert_eq!(t.lines().count(), 3, "{t}");
}

#[test]
fn backfill_records_from_a_merge_commit() {
    let f = fixture("tfiles-rec", kotlin_daemon_env());
    f.env.push_ids(&["ticket-bbbb0001"]);
    save(&f.orch, r#"{"title":"T","projectId":"p1"}"#);
    f.board.db().write(|c| c.execute("UPDATE tickets SET status='done' WHERE id='ticket-bbbb0001'", [])).unwrap();
    let git = FakeGit(vec![
        ("log --all --reverse --format=%H%x1f%P%x1f%s".into(), "m1\u{1f}p1 p2\u{1f}Merge ticket/bbbb0001-x into main\n".into()),
        ("-c core.quotepath=off diff -z --raw --numstat -M m1^1 m1".into(), ":100644 100644 a b M\0src/x.rs\x005\t2\tsrc/x.rs\0".into()),
    ]);
    let board = Board::new(Arc::new(Db::open(&f.tmp.path).unwrap()), Arc::new(git));
    let out = board.ticket_files_query(&q(r#"{"backfill":true}"#)).unwrap();
    assert!(out.starts_with("backfill: 1 done tickets, 0 already recorded, 1 recovered (merge 1"), "{out}");
    let t = board.ticket_files_query(&q(r#"{"ticketId":"bbbb0001"}"#)).unwrap();
    assert!(t.contains("M +5 -2  src/x.rs"), "{t}");
    assert_eq!(board.ticket_file_paths("ticket-bbbb0001"), vec!["src/x.rs".to_string()]);
}
