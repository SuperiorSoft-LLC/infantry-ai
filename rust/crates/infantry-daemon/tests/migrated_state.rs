// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-MIGRATE-1: a daemon on a state dir migrated from the Kotlin app lists every session and project there, marks
//! the sessions live in the Kotlin app `heldBy`, refuses a turn into one, and never stores `heldBy` or touches the source.
use infantry_conformance::client::Client;
use infantry_daemon::auth::AccessPolicy;
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::server::{self, App};
use infantry_daemon::sessions::{Config, Daemon};
use infantry_protocol::Envelope;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

fn rec(id: &str, state: &str, project: &str) -> serde_json::Value {
    serde_json::json!({"id": id, "provider": "claude", "workingDir": "C:\\w", "spawnedAt": 1, "lastHeartbeat": 1,
        "state": state, "providerSessionId": format!("p-{id}"), "role": "backend", "projectId": project})
}

fn dirs() -> (PathBuf, PathBuf, PathBuf) {
    let root = std::env::temp_dir().join(format!("infantry-daemon-migrated-{}", uuid::Uuid::new_v4()));
    let (src, copy) = (root.join("InfantryAI"), root.join("InfantryAI-Rust"));
    std::fs::create_dir_all(&src).unwrap();
    std::fs::create_dir_all(&copy).unwrap();
    let store = |held: bool| {
        serde_json::json!({"version": 1, "sessions": [
            rec("s-idle", "IDLE", "project-a"), rec("s-live", if held { "BUSY" } else { "IDLE" }, "project-a"),
            rec("s-done", "EXITED", "project-b")
        ]})
        .to_string()
    };
    std::fs::write(src.join("sessions.json"), store(true)).unwrap();
    std::fs::write(copy.join("sessions.json"), store(false)).unwrap();
    std::fs::write(copy.join("migration.json"), serde_json::json!({"version": 1, "source": src, "sourceLabel": "Infantry (Kotlin)"}).to_string()).unwrap();
    let db = rusqlite::Connection::open(copy.join("infantry.db")).unwrap();
    db.execute_batch(
        "CREATE TABLE projects (id TEXT PRIMARY KEY, name TEXT NOT NULL, goal TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL,
           directory TEXT NOT NULL DEFAULT '', base_branch TEXT NOT NULL DEFAULT '', lane_caps TEXT NOT NULL DEFAULT '{}',
           auto_remove_done_workers INTEGER NOT NULL DEFAULT 0, experiments TEXT NOT NULL DEFAULT '{}',
           routing_rules TEXT NOT NULL DEFAULT '[]', project_data TEXT NOT NULL DEFAULT '{}');
         INSERT INTO projects (id, name, created_at, directory) VALUES ('project-a', 'Alpha', 1, 'C:\\a'), ('project-b', 'Beta', 2, 'C:\\b');",
    )
    .unwrap();
    (root, src, copy)
}

fn start(dir: &Path) -> u16 {
    let dir = dir.to_path_buf();
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::spawn(move || {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async move {
            let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(dir.clone(), dir.clone())).unwrap();
            // RUST-WIRE-1: project frames moved from the session manager to the projects unit; compose like main.
            infantry_daemon::compose::build(&daemon, &dir).unwrap();
            let policy = Arc::new(AccessPolicy::new("127.0.0.1", None).unwrap());
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            tx.send(listener.local_addr().unwrap().port()).unwrap();
            server::serve(listener, App { daemon, policy }, std::future::pending()).await.unwrap();
        });
    });
    rx.recv_timeout(Duration::from_secs(10)).unwrap()
}

#[test]
fn lists_migrated_sessions_and_projects_and_holds_kotlin_live_sessions() {
    let (root, src, copy) = dirs();
    let src_before = std::fs::read(src.join("sessions.json")).unwrap();
    let port = start(&copy);
    let t = Duration::from_secs(20);
    let mut c = Client::connect(&format!("ws://127.0.0.1:{port}"), None).unwrap();
    let sessions = c.wait_for(t, |e| e.r#type == "sessions").unwrap();
    let list: Vec<serde_json::Value> = serde_json::from_str(&sessions.data).unwrap();
    assert_eq!(list.len(), 3, "every migrated session is listed");
    let by = |id: &str| list.iter().find(|r| r["id"] == id).unwrap().clone();
    assert_eq!(by("s-live")["heldBy"], "Infantry (Kotlin)");
    assert!(by("s-idle").get("heldBy").is_none());
    assert_eq!(by("s-idle")["projectId"], "project-a");

    c.send_frame("project_list", "", None).unwrap();
    let projects = c.wait_for(t, |e| e.r#type == "projects").unwrap();
    let p: Vec<serde_json::Value> = serde_json::from_str(&projects.data).unwrap();
    assert_eq!(p.iter().map(|p| p["name"].as_str().unwrap()).collect::<Vec<_>>(), ["Alpha", "Beta"]);
    assert_eq!(p[0]["directory"], "C:\\a");
    c.send_frame("project_get", "project-b", None).unwrap();
    assert!(c.wait_for(t, |e| e.r#type == "project").unwrap().data.contains("\"Beta\""));

    // A turn into the held session is refused; nothing runs.
    let mut e = Envelope::new("prompt", "hello");
    e.session_id = Some("s-live".into());
    c.send(&e).unwrap();
    let err = c.wait_for(t, |e| e.r#type == "error").unwrap();
    assert!(err.data.contains("live in Infantry (Kotlin)"), "{}", err.data);

    // The Kotlin app lets go of it: the next refresh clears the hold and pushes a fresh list.
    std::fs::write(src.join("sessions.json"), serde_json::json!({"version": 1, "sessions": [rec("s-live", "IDLE", "project-a")]}).to_string()).unwrap();
    let fresh = c.wait_for(t, |e| e.r#type == "sessions" && !e.data.contains("heldBy")).unwrap();
    assert_eq!(serde_json::from_str::<Vec<serde_json::Value>>(&fresh.data).unwrap().len(), 3);

    // heldBy is never stored, and the source was only read.
    let stored = std::fs::read_to_string(copy.join("sessions.json")).unwrap();
    assert!(!stored.contains("heldBy"));
    assert_ne!(std::fs::read(src.join("sessions.json")).unwrap(), src_before, "only the test changed the source");
    let _ = std::fs::remove_dir_all(root);
}
