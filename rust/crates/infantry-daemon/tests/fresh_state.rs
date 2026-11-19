// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-PROMPT-1: a daemon on a FRESH (empty) state dir builds the full schema, so the board answers instead of
//! refusing with "no such table: tickets".
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::sessions::{Config, Daemon};
use infantry_protocol::Envelope;
use std::sync::Arc;

#[test]
fn a_fresh_state_dir_gets_the_full_schema_and_the_board_answers() {
    let dir = std::env::temp_dir().join(format!("infantry-daemon-fresh-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let rt = tokio::runtime::Runtime::new().unwrap();
    let out = rt.block_on(async {
        let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(dir.clone(), dir.clone())).unwrap();
        let c = infantry_daemon::compose::build(&daemon, &dir).unwrap();
        let version = c.db.user_version().unwrap();
        let reply = tokio::task::spawn_blocking(move || c.request(&Envelope::new("ticket_list", "{}"), true)).await.unwrap();
        (version, reply)
    });
    let (version, reply) = out;
    assert_eq!(version, infantry_core::db::MAX_USER_VERSION, "a fresh db is stamped with the schema version");
    let reply = reply.expect("ticket_list answers on a fresh state dir");
    assert!(!reply.is_empty());
    drop(rt);
    let _ = std::fs::remove_dir_all(&dir);
}
