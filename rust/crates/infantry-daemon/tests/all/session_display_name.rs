// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! SESSION-NAME-1: a session's display name renames how it shows, persists across a restart, and never changes its role.
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::sessions::{Config, Daemon, NewOpts};
use infantry_protocol::agents::SessionSwap;
use std::sync::Arc;

fn rename(id: &str, name: &str) -> SessionSwap {
    SessionSwap { session_id: id.into(), provider: "stub".into(), model: None, effort: None, role: None, display_name: Some(name.into()) }
}

#[tokio::test(flavor = "multi_thread")]
async fn the_display_name_persists_and_the_role_stays() {
    let dir = std::env::temp_dir().join(format!("infantry-daemon-name-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(dir.clone(), dir.clone())).unwrap();
    let id = daemon.launch(NewOpts { role: Some("pm".into()), ..Default::default() }).unwrap();
    daemon.swap(rename(&id, "  PM - releases  ")).expect("a rename is allowed");
    let rec = daemon.record(&id).unwrap();
    assert_eq!(rec.display_name.as_deref(), Some("PM - releases"));
    assert_eq!(rec.role.as_deref(), Some("pm"), "the role is unchanged");
    daemon.flush();

    let again = Daemon::new(Arc::new(Stub::default()), Config::new(dir.clone(), dir.clone())).unwrap();
    let rec = again.record(&id).expect("loaded after a restart");
    assert_eq!(rec.display_name.as_deref(), Some("PM - releases"), "the name survives a restart");
    assert_eq!(rec.role.as_deref(), Some("pm"));

    again.swap(rename(&id, "")).unwrap();
    assert_eq!(again.record(&id).unwrap().display_name, None, "empty = back to the role name");
    let _ = std::fs::remove_dir_all(&dir);
}
