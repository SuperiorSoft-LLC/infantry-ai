// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! BOS-7: the daemon as an app of BattalionOS, with and without the contract: the stop route (bearer), `busy` in
//! `/health`, the app origin on the WS allow list, and a clean end.
use infantry_daemon::auth::AccessPolicy;
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::os_app::{OsEnv, OsRuntime};
use infantry_daemon::server::{self, App};
use infantry_daemon::sessions::{Config, Daemon};
use std::sync::Arc;
use std::time::Duration;

const SECRET: &str = "stop-secret-for-test";

fn call(method: &str, base: &str, path: &str, bearer: Option<&str>) -> (u16, String) {
    let mut r = ureq::request(method, &format!("{base}{path}"));
    if let Some(t) = bearer {
        r = r.set("Authorization", &format!("Bearer {t}"));
    }
    match r.call() {
        Ok(resp) | Err(ureq::Error::Status(_, resp)) => (resp.status(), resp.into_string().unwrap_or_default()),
        Err(e) => panic!("{method} {path}: {e}"),
    }
}

/// A daemon on a free port; `os` makes it an app. Returns the base url and the thread that ends when the server does.
fn start(os: Option<Arc<OsRuntime>>) -> (String, std::thread::JoinHandle<std::io::Result<()>>) {
    static N: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
    let n = N.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
    let dir = std::env::temp_dir().join(format!("infantry-os-app-{}-{n}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let (tx, rx) = std::sync::mpsc::channel();
    let h = std::thread::spawn(move || {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async move {
            let daemon = Daemon::new(Arc::new(Stub { start_ms: 0, line_ms: 0 }), Config::new(dir.clone(), dir)).unwrap();
            let policy = Arc::new(AccessPolicy::new("127.0.0.1", None).unwrap());
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            tx.send(listener.local_addr().unwrap().port()).unwrap();
            let stopper = os.clone();
            let stop = async move {
                match stopper {
                    Some(o) => o.stopped().await,
                    None => std::future::pending().await,
                }
            };
            server::serve_os(listener, App { daemon, policy }, None, os, stop).await
        })
    });
    (format!("http://127.0.0.1:{}", rx.recv_timeout(Duration::from_secs(10)).unwrap()), h)
}

fn runtime(dir: &std::path::Path) -> Arc<OsRuntime> {
    let token = dir.join("token");
    std::fs::write(&token, SECRET).unwrap();
    OsRuntime::new(OsEnv { ready_file: Some(dir.join("ready.json")), token_file: token, state_dir: None, app_dir: None, ui_origin: Some("http://127.0.0.1:5173".into()) }).unwrap()
}

#[test]
fn an_app_daemon_stops_on_the_bearer_and_ends_cleanly() {
    let d = tempfile::tempdir().unwrap();
    let os = runtime(d.path());
    let (base, handle) = start(Some(os.clone()));
    os.write_ready("127.0.0.1", base.rsplit(':').next().unwrap().parse().unwrap()).unwrap();
    let ready: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(d.path().join("ready.json")).unwrap()).unwrap();
    assert_eq!(ready["url"], base.as_str());
    // /health tells what is in flight.
    let (s, body) = call("GET", &base, "/health", None);
    assert_eq!(s, 200);
    let v: serde_json::Value = serde_json::from_str(&body).unwrap();
    assert_eq!(v["busy"], 0, "{body}");
    // The stop call needs the secret.
    assert_eq!(call("POST", &base, "/stop", None).0, 401);
    assert_eq!(call("POST", &base, "/stop", Some("wrong")).0, 401);
    assert_eq!(call("GET", &base, "/stop", Some(SECRET)).0, 405, "stop is a POST");
    assert_eq!(call("GET", &base, "/health", None).0, 200, "refused stops leave it running");
    let (s, body) = call("POST", &base, "/stop", Some(SECRET));
    assert_eq!((s, body.contains("stopping")), (200, true));
    let r = handle.join().expect("the server thread ends");
    assert!(r.is_ok(), "stop is the graceful path: {r:?}");
    os.clear_ready();
    assert!(!d.path().join("ready.json").exists());
}

#[test]
fn without_the_contract_there_is_no_stop_route_and_no_busy() {
    let (base, _h) = start(None);
    let (s, body) = call("GET", &base, "/health", None);
    assert_eq!(s, 200);
    assert!(serde_json::from_str::<serde_json::Value>(&body).unwrap().get("busy").is_none(), "{body}");
    assert_eq!(call("POST", &base, "/stop", Some(SECRET)).0, 404);
    assert_eq!(call("GET", &base, "/health", None).0, 200);
}

#[test]
fn the_app_origin_joins_the_ws_allow_list() {
    let plain = AccessPolicy::new("127.0.0.1", None).unwrap();
    let origin = "http://127.0.0.1:5173";
    let refused = plain.check("/ws", Some("127.0.0.1:18990"), Some(origin), None, None);
    let app = AccessPolicy::new("127.0.0.1", None).unwrap().with_app_origin("HTTP://127.0.0.1:5173/");
    assert!(app.check("/ws", Some("127.0.0.1:18990"), Some(origin), None, None).is_none());
    assert!(refused.is_some(), "without the app origin a browser origin is refused");
    assert!(app.check("/ws", Some("127.0.0.1:18990"), Some("http://evil.example"), None, None).is_some());
}
