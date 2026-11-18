// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The migration on a synthetic state dir: a live WAL db with a writer running through the snapshot, a quiet db,
//! JSON stores, a protected secret, runtime files that must be skipped; then verify, re-run (.prev) and refusals.
use infantry_migrate::{acl, manifest, sha256_hex, snapshot, sqlite, verify};
use rusqlite::Connection;
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;

fn temp(name: &str) -> PathBuf {
    static N: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
    let n = N.fetch_add(1, Ordering::SeqCst);
    let d = std::env::temp_dir().join(format!("infantry-migrate-test-{name}-{}-{}-{n}", std::process::id(), infantry_migrate::now_ms()));
    std::fs::create_dir_all(&d).unwrap();
    d
}

fn session(id: &str, state: &str, project: &str) -> serde_json::Value {
    serde_json::json!({
        "id": id, "provider": "claude", "workingDir": "C:\\work", "spawnedAt": 1, "lastHeartbeat": 2, "state": state,
        "providerSessionId": format!("p-{id}"), "role": "backend", "projectId": project,
        "someFieldOnlyKotlinKnows": {"x": 1}, "live": false, "turnActive": false
    })
}

/// Every file under `dir` (relative path -> sha256), leaving out SQLite's own sidecars, which the writer
/// creates and removes.
fn tree(dir: &Path) -> BTreeMap<String, String> {
    fn walk(root: &Path, d: &Path, out: &mut BTreeMap<String, String>) {
        for e in std::fs::read_dir(d).unwrap().flatten() {
            let p = e.path();
            if p.is_dir() {
                walk(root, &p, out);
            } else {
                let rel = infantry_migrate::rel_string(root, &p);
                if rel.ends_with("-wal") || rel.ends_with("-shm") {
                    continue;
                }
                let db = rel.ends_with(".db");
                out.insert(rel, if db { String::new() } else { sha256_hex(&std::fs::read(&p).unwrap()) });
            }
        }
    }
    let mut out = BTreeMap::new();
    walk(dir, dir, &mut out);
    out
}

#[cfg(windows)]
fn protect(p: &Path) {
    let user = std::env::var("USERNAME").unwrap();
    let ok = std::process::Command::new("icacls").arg(p).args(["/inheritance:r", "/grant:r", &format!("{user}:F")]).output().unwrap();
    assert!(ok.status.success());
}

#[cfg(unix)]
fn protect(p: &Path) {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(p, std::fs::Permissions::from_mode(0o600)).unwrap();
}

struct Source {
    dir: PathBuf,
    writer: Option<std::thread::JoinHandle<()>>,
    stop: Arc<AtomicBool>,
}

impl Source {
    fn build() -> Source {
        let dir = temp("src");
        let sessions = serde_json::json!({"version": 1, "sessions": [session("s1", "IDLE", "project-a"), session("s2", "BUSY", "project-b")]});
        std::fs::write(dir.join("sessions.json"), serde_json::to_string_pretty(&sessions).unwrap()).unwrap();
        std::fs::write(dir.join("mailbox.json"), r#"{"messages":[]}"#).unwrap();
        std::fs::create_dir_all(dir.join("roles")).unwrap();
        std::fs::write(dir.join("roles/backend.md"), "# backend").unwrap();
        std::fs::write(dir.join("roles/pm.md"), "# pm").unwrap();
        std::fs::write(dir.join("keystore.json"), r#"{"secret":"never-print-me"}"#).unwrap();
        protect(&dir.join("keystore.json"));
        for f in ["daemon.lock", "daemon.port", "sessions.json.tmp"] {
            std::fs::write(dir.join(f), "x").unwrap();
        }
        std::fs::create_dir_all(dir.join("infantry.db.holders")).unwrap();
        std::fs::write(dir.join("infantry.db.holders/1.json"), "{}").unwrap();
        // A quiet WAL db: closed, so it has no -shm.
        let q = Connection::open(dir.join("usage-meter.db")).unwrap();
        q.execute_batch("PRAGMA journal_mode=WAL; CREATE TABLE usage(n INTEGER); INSERT INTO usage VALUES (1),(2),(3);").unwrap();
        drop(q);
        assert!(!dir.join("usage-meter.db-shm").exists());
        // The live db: a writer keeps committing through the snapshot, as the Kotlin daemon would.
        let c = Connection::open(dir.join("infantry.db")).unwrap();
        c.execute_batch(
            "PRAGMA journal_mode=WAL; PRAGMA user_version=62;
             CREATE TABLE projects(id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL);
             CREATE TABLE agents(id TEXT PRIMARY KEY, name TEXT NOT NULL);
             CREATE TABLE events(n INTEGER);
             INSERT INTO projects VALUES ('project-a','A',1),('project-b','B',2);
             INSERT INTO agents VALUES ('agent-1','Ada');",
        )
        .unwrap();
        let stop = Arc::new(AtomicBool::new(false));
        let s = stop.clone();
        let writer = std::thread::spawn(move || {
            let mut n = 0;
            while !s.load(Ordering::SeqCst) {
                c.execute("INSERT INTO events VALUES (?1)", [n]).unwrap();
                n += 1;
            }
        });
        while !dir.join("infantry.db-shm").exists() {
            std::thread::yield_now();
        }
        Source { dir, writer: Some(writer), stop }
    }

    fn stop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(w) = self.writer.take() {
            w.join().unwrap();
        }
    }
}

impl Drop for Source {
    fn drop(&mut self) {
        self.stop();
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

fn opts(from: &Path, to: &Path) -> snapshot::Options {
    snapshot::Options { from: from.into(), to: to.into(), label: manifest::DEFAULT_SOURCE_LABEL.into(), force: false }
}

#[test]
fn snapshot_copies_a_live_state_dir_without_writing_to_it() {
    let mut src = Source::build();
    let before = tree(&src.dir);
    let out = temp("dst");
    let to = out.join("InfantryAI-Rust");
    let mut log = Vec::new();
    let m = snapshot::run(&opts(&src.dir, &to), &mut |s| log.push(s.to_string())).unwrap();
    src.stop();

    // Nothing in the source was created, changed or removed (SQLite's own -wal/-shm belong to the writer).
    assert_eq!(tree(&src.dir).keys().collect::<Vec<_>>(), before.keys().collect::<Vec<_>>());
    for (k, v) in &before {
        if !v.is_empty() {
            assert_eq!(&sha256_hex(&std::fs::read(src.dir.join(k)).unwrap()), v, "{k} changed in the source");
        }
    }

    assert_eq!(m.counts.sessions, 2);
    assert_eq!(m.counts.projects, Some(2));
    assert_eq!(m.counts.agents, Some(1));
    assert_eq!(m.counts.roles, 2);
    let skipped: Vec<&str> = m.skipped.iter().map(|s| s.path.as_str()).collect();
    for s in ["daemon.lock", "daemon.port", "sessions.json.tmp", "infantry.db.holders", "infantry.db-wal", "infantry.db-shm"] {
        assert!(skipped.contains(&s), "{s} not skipped: {skipped:?}");
        assert!(!to.join(s).exists(), "{s} was copied");
    }

    // Both dbs: copied whole, consistent, and the live one read while it was written.
    let live = m.databases.iter().find(|d| d.path == "infantry.db").unwrap();
    assert_eq!((live.mode.as_str(), live.integrity.as_str(), live.user_version), ("live", "ok", 62));
    let quiet = m.databases.iter().find(|d| d.path == "usage-meter.db").unwrap();
    assert_eq!((quiet.mode.as_str(), quiet.tables["usage"]), ("quiet", Some(3)));
    let copy = sqlite::open_copy(&to.join("infantry.db")).unwrap();
    let f = sqlite::facts(&copy, true).unwrap();
    assert_eq!(f.integrity, "ok");
    assert_eq!(f.tables, live.tables);
    let events: i64 = copy.query_row("SELECT COUNT(*) FROM events", [], |r| r.get(0)).unwrap();
    let max: Option<i64> = copy.query_row("SELECT MAX(n) FROM events", [], |r| r.get(0)).unwrap();
    // A consistent cut: the rows are exactly 0..count, no gap and no torn page.
    assert_eq!(max.map(|m| m + 1).unwrap_or(0), events);
    drop(copy);

    // The secret: byte for byte, with the same protected access list.
    assert_eq!(std::fs::read(to.join("keystore.json")).unwrap(), std::fs::read(src.dir.join("keystore.json")).unwrap());
    let (a, b) = (acl::access(&src.dir.join("keystore.json")).unwrap(), acl::access(&to.join("keystore.json")).unwrap());
    assert!(a.protected);
    assert_eq!(a, b);
    assert!(m.files.iter().any(|f| f.path == "keystore.json" && f.acl));
    // Nothing printed a file's content.
    assert!(!log.join("\n").contains("never-print-me"));

    let r = verify::run(&to, None).unwrap();
    assert!(r.ok(), "{:?}", r.failures);
    let text = r.lines.join("\n");
    assert!(text.contains("sessions  2 in the copy (snapshot 2), all load: yes"), "{text}");
    assert!(!text.contains("never-print-me"));

    // A second run keeps the first as .prev.
    std::fs::write(src.dir.join("roles/qa.md"), "# qa").unwrap();
    let m2 = snapshot::run(&opts(&src.dir, &to), &mut |_| {}).unwrap();
    assert_eq!(m2.counts.roles, 3);
    let prev = manifest::read(&infantry_migrate::sibling(&to, ".prev")).unwrap();
    assert_eq!(prev.counts.roles, 2);
    assert!(!infantry_migrate::sibling(&to, ".snapshot").exists());

    // A damaged copy fails verify.
    std::fs::remove_file(to.join("roles/pm.md")).unwrap();
    let r = verify::run(&to, None).unwrap();
    assert!(!r.ok());
    assert!(r.failures.iter().any(|f| f.contains("roles/pm.md")));
    let _ = std::fs::remove_dir_all(out);
}

#[test]
fn verify_fails_on_a_session_the_rust_daemon_cannot_load() {
    let src = Source::build();
    let out = temp("bad");
    let to = out.join("copy");
    snapshot::run(&opts(&src.dir, &to), &mut |_| {}).unwrap();
    let bad = serde_json::json!({"version": 1, "sessions": [session("s1", "IDLE", "p"), {"id": "broken"}]});
    std::fs::write(to.join("sessions.json"), bad.to_string()).unwrap();
    let r = verify::run(&to, None).unwrap();
    assert!(r.failures.iter().any(|f| f.contains("do not load")), "{:?}", r.failures);
    let _ = std::fs::remove_dir_all(out);
}

#[test]
fn refuses_unsafe_destinations() {
    let src = Source::build();
    // Inside the source.
    let inside = src.dir.join("copy");
    assert!(snapshot::run(&opts(&src.dir, &inside), &mut |_| {}).unwrap_err().contains("outside the source"));
    assert!(!inside.exists());
    // A folder that is not a snapshot is not replaced without --force.
    let out = temp("other");
    let to = out.join("mine");
    std::fs::create_dir_all(&to).unwrap();
    std::fs::write(to.join("important.txt"), "keep").unwrap();
    assert!(snapshot::run(&opts(&src.dir, &to), &mut |_| {}).unwrap_err().contains("not a snapshot"));
    assert_eq!(std::fs::read_to_string(to.join("important.txt")).unwrap(), "keep");
    // A destination a daemon is serving is refused.
    let to2 = out.join("served");
    snapshot::run(&opts(&src.dir, &to2), &mut |_| {}).unwrap();
    let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
    std::fs::write(to2.join("daemon.port"), l.local_addr().unwrap().port().to_string()).unwrap();
    assert!(snapshot::run(&opts(&src.dir, &to2), &mut |_| {}).unwrap_err().contains("a daemon is serving"));
    let _ = std::fs::remove_dir_all(out);
}
