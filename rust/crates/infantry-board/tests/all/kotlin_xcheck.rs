// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Rust -> Kotlin cross-check, step 1. With INFANTRY_XCHECK_DIR set (a TEMP state dir, never the owner's),
//! builds the standard board, copies the database to `<dir>/infantry.db` (sqlite backup API) and writes the
//! `ticket_list` answer Rust gives for it to `<dir>/rust-tickets.json`. A Kotlin daemon started on that state
//! dir must answer `ticket_list` with the same `tickets` data (`scripts`: see the RUST-ORCH-1 report).
//! With INFANTRY_XCHECK_KOTLIN also set (Kotlin's `tickets` data, saved to a file), compares the two.
use crate::common;

use common::*;
use infantry_board::BoardUnit;
use infantry_core::frames::{FrameCtx, FrameUnit};
use infantry_protocol::Envelope;

#[test]
fn rust_board_for_kotlin() {
    let Ok(dir) = std::env::var("INFANTRY_XCHECK_DIR") else {
        eprintln!("INFANTRY_XCHECK_DIR unset: skipped");
        return;
    };
    let dir = std::path::PathBuf::from(dir);
    let f = fixture("xcheck", kotlin_daemon_env());
    standard_board(&f);
    let unit = BoardUnit::new(f.board.clone());
    let ctx = FrameCtx { conn_id: 1, owner: true, bus: f.bus.clone() };
    let ours = unit.handle(&Envelope::new("ticket_list", ""), &ctx).map_err(|e| e.0).unwrap().pop().unwrap();
    assert_eq!(ours.r#type, "tickets");

    match std::env::var("INFANTRY_XCHECK_KOTLIN") {
        Err(_) => {
            std::fs::create_dir_all(&dir).unwrap();
            let target = dir.join("infantry.db");
            assert!(!target.exists(), "{} exists: use a fresh temp dir", target.display());
            f.board
                .db()
                .read(|c| {
                    let mut to = rusqlite::Connection::open(&target)?;
                    rusqlite::backup::Backup::new(c, &mut to)?.run_to_completion(256, std::time::Duration::ZERO, None)?;
                    Ok(())
                })
                .unwrap();
            std::fs::write(dir.join("rust-tickets.json"), &ours.data).unwrap();
        }
        Ok(kotlin) => {
            let k = std::fs::read_to_string(kotlin).unwrap();
            let kv: Vec<serde_json::Value> = serde_json::from_str(&k).unwrap();
            let ov: Vec<serde_json::Value> = serde_json::from_str(&ours.data).unwrap();
            assert_eq!(ov.len(), kv.len());
            for (o, k) in ov.iter().zip(kv.iter()) {
                assert_eq!(o, k, "ticket {} differs", o["id"]);
            }
            // Byte-equal too: same field order, same number formatting.
            assert_eq!(ours.data, k.trim_end());
        }
    }
}
