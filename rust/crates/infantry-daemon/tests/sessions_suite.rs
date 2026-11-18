// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The conformance sessions suite (contract checks) against an in-process daemon on port 0 with the stub provider, plus
//! the fixture diff for every frame that does not carry the recording machine's folder (see PERF.md, "Rust daemon").
use infantry_conformance::{fixture, normalize::Normalizer, scenarios};
use infantry_daemon::auth::AccessPolicy;
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::server::{self, App};
use infantry_daemon::sessions::{Config, Daemon, KILL_SETTLE_MS};
use std::sync::Arc;
use std::time::Duration;

const TOKEN: &str = "suite-token-0123456789abcdef0123456789";

fn start() -> (u16, std::path::PathBuf) {
    let dir = std::env::temp_dir().join(format!("infantry-daemon-suite-{}", uuid_like()));
    std::fs::create_dir_all(&dir).unwrap();
    let (tx, rx) = std::sync::mpsc::channel();
    let d2 = dir.clone();
    std::thread::spawn(move || {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async move {
            let cfg = Config::new(d2.clone(), d2);
            let daemon = Daemon::new(Arc::new(Stub::default()), cfg).unwrap();
            let policy = Arc::new(AccessPolicy::new("127.0.0.1", Some(TOKEN)).unwrap());
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            tx.send(listener.local_addr().unwrap().port()).unwrap();
            server::serve(listener, App { daemon, policy }, std::future::pending()).await.unwrap();
        });
    });
    (rx.recv_timeout(Duration::from_secs(10)).unwrap(), dir)
}

fn uuid_like() -> String {
    format!("{}-{}", std::process::id(), std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos())
}

/// The run's working folder appears verbatim in two status lines; put the recording's folder there.
fn rehome(frames: &mut [serde_json::Value], from: &str, to: &str) {
    for f in frames {
        if let Some(a) = f["data"]["activity"].as_str() {
            if a.contains(from) {
                f["data"]["activity"] = serde_json::json!(a.replace(from, to));
            }
        }
    }
}

#[test]
fn the_sessions_suite_passes_and_matches_the_kotlin_fixtures() {
    let (port, dir) = start();
    let cfg = scenarios::Config { url: format!("ws://127.0.0.1:{port}"), token: Some(TOKEN.into()), timeout: Duration::from_secs(30) };
    let fixtures = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../conformance/fixtures/kotlin");
    let recorded = r"C:\Users\antho\agenthq-standalone\.worktrees\rust-conf-1";
    let mut failures = Vec::new();
    for name in scenarios::NAMES {
        let r = scenarios::run(name, &cfg);
        if let Err(e) = r.result {
            failures.push(format!("{name}: {e}"));
            continue;
        }
        let mut norm = Normalizer::new().transcript(&r.frames);
        rehome(&mut norm, &dir.to_string_lossy(), recorded);
        let exp = fixture::read(&fixtures, scenarios::SUITE, name).unwrap();
        if let Some(d) = fixture::diff(&exp, &norm) {
            failures.push(format!("{name}: {d}"));
        }
    }
    let _ = std::fs::remove_dir_all(&dir);
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}
