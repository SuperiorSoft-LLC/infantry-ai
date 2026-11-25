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

// ---- BOS-33: phone access under the OS ------------------------------------------------------------------------------

const PHONE_TOKEN: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
const PAGE: &str = "http://127.0.0.1:5173";

/// A token-required app daemon (as when phone access is on): its state dir, base url, and the thread.
fn start_phone(state: &std::path::Path, os: Arc<OsRuntime>) -> (String, std::thread::JoinHandle<std::io::Result<()>>) {
    let (state, (tx, rx)) = (state.to_path_buf(), std::sync::mpsc::channel());
    let h = std::thread::spawn(move || {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async move {
            let daemon = Daemon::new(Arc::new(Stub { start_ms: 0, line_ms: 0 }), Config::new(state.clone(), state)).unwrap();
            let policy = Arc::new(AccessPolicy::new("0.0.0.0", Some(PHONE_TOKEN)).unwrap().with_app_origin(PAGE));
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let port = listener.local_addr().unwrap().port();
            os.write_ready("127.0.0.1", port).unwrap();
            tx.send(port).unwrap();
            let o = os.clone();
            server::serve_os(listener, App { daemon, policy }, None, Some(os), async move { o.stopped().await }).await
        })
    });
    (format!("http://127.0.0.1:{}", rx.recv_timeout(Duration::from_secs(10)).unwrap()), h)
}

fn phone_call(method: &str, base: &str, path: &str, origin: Option<&str>, bearer: Option<&str>, body: Option<&str>) -> (u16, String) {
    let mut r = ureq::request(method, &format!("{base}{path}"));
    if let Some(o) = origin {
        r = r.set("Origin", o);
    }
    if let Some(t) = bearer {
        r = r.set("Authorization", &format!("Bearer {t}"));
    }
    let res = match body {
        Some(b) => r.set("Content-Type", "text/plain").send_string(b),
        None => r.call(),
    };
    match res {
        Ok(resp) | Err(ureq::Error::Status(_, resp)) => (resp.status(), resp.into_string().unwrap_or_default()),
        Err(e) => panic!("{method} {path}: {e}"),
    }
}

#[test]
fn the_page_reads_and_sets_phone_access_and_a_phone_cannot_read_the_token() {
    let d = tempfile::tempdir().unwrap();
    let state = d.path().join("state");
    std::fs::create_dir_all(&state).unwrap();
    std::fs::write(d.path().join("token"), SECRET).unwrap();
    let os = OsRuntime::new(OsEnv { ready_file: Some(d.path().join("ready.json")), token_file: d.path().join("token"), state_dir: Some(state.clone()), app_dir: None, ui_origin: Some(PAGE.into()) }).unwrap();
    let (base, handle) = start_phone(&state, os);
    // The ready file stays loopback (the OS only accepts a loopback url).
    let ready: serde_json::Value = serde_json::from_str(&std::fs::read_to_string(d.path().join("ready.json")).unwrap()).unwrap();
    assert_eq!(ready["url"], base.as_str());
    // The page (its origin, from this computer) reads the rows without the phone token; off at first, no token shown.
    let (s, body) = phone_call("GET", &base, "/os/phone?reveal=1", Some(PAGE), None, None);
    let v: serde_json::Value = serde_json::from_str(&body).unwrap();
    assert_eq!((s, v["enabled"].as_bool(), v["token"].is_null()), (200, Some(false), true), "{body}");
    // A phone, even with the token, has no origin of the page: refused, and so is a foreign origin or none.
    for (o, t) in [(None, Some(PHONE_TOKEN)), (Some("http://evil.example"), Some(PHONE_TOKEN)), (Some("http://tauri.localhost"), Some(PHONE_TOKEN)), (None, None)] {
        assert_eq!(phone_call("GET", &base, "/os/phone?reveal=1", o, t, None).0, 403, "{o:?}");
        assert_eq!(phone_call("POST", &base, "/os/phone", o, t, Some(r#"{"enabled":true}"#)).0, 403, "{o:?}");
    }
    assert!(!state.join("launch.json").exists(), "a refused call changes nothing");
    // The page turns it on: launch.json binds every interface with the token file; the daemon asks the OS for a restart.
    let (s, body) = phone_call("POST", &base, "/os/phone", Some(PAGE), None, Some(r#"{"enabled":true}"#));
    assert_eq!((s, body.contains(r#""restart":true"#)), (200, true), "{body}");
    let launch = infantry_daemon::os_app::Launch::read(&state).unwrap();
    assert_eq!(launch.host.as_deref(), Some("0.0.0.0"));
    assert!(launch.token_file.is_some_and(|f| f.is_file()));
    handle.join().unwrap().unwrap(); // a change asks the OS to restart the app: the daemon stopped gracefully
}

#[test]
fn the_page_connects_over_ws_with_a_token_required_and_a_phone_without_it_is_refused() {
    let d = tempfile::tempdir().unwrap();
    std::fs::write(d.path().join("token"), SECRET).unwrap();
    let os = OsRuntime::new(OsEnv { ready_file: None, token_file: d.path().join("token"), state_dir: Some(d.path().to_path_buf()), app_dir: None, ui_origin: Some(PAGE.into()) }).unwrap();
    let (base, _h) = start_phone(d.path(), os.clone());
    let port = base.rsplit(':').next().unwrap();
    let connect = |origin: Option<&str>, bearer: Option<&str>| {
        use tungstenite::client::IntoClientRequest;
        let mut req = format!("ws://127.0.0.1:{port}/ws").into_client_request().unwrap();
        if let Some(o) = origin {
            req.headers_mut().insert("Origin", o.parse().unwrap());
        }
        if let Some(t) = bearer {
            req.headers_mut().insert("Authorization", format!("Bearer {t}").parse().unwrap());
        }
        let stream = std::net::TcpStream::connect(("127.0.0.1", port.parse::<u16>().unwrap())).unwrap();
        tungstenite::client(req, stream).is_ok()
    };
    assert!(connect(Some(PAGE), None), "the OS's own page connects without the phone token");
    assert!(connect(None, Some(PHONE_TOKEN)), "a client with the token connects");
    assert!(!connect(None, None), "no token, no origin: refused");
    assert!(!connect(None, Some("wrong")), "a wrong token is refused");
    assert!(!connect(Some("http://evil.example"), None), "a foreign origin is refused");
    os.request_stop();
}

#[test]
fn the_token_shows_to_the_page_on_reveal_only_and_never_to_a_phone() {
    let d = tempfile::tempdir().unwrap();
    let state = d.path().join("state");
    infantry_daemon::phone::set(&state, true, 18990).unwrap();
    let token = std::fs::read_to_string(state.join("remote-access.token")).unwrap().trim().to_string();
    std::fs::write(d.path().join("token"), SECRET).unwrap();
    let os = OsRuntime::new(OsEnv { ready_file: None, token_file: d.path().join("token"), state_dir: Some(state.clone()), app_dir: None, ui_origin: Some(PAGE.into()) }).unwrap();
    let (base, h) = start_phone(&state, os);
    let (_, plain) = phone_call("GET", &base, "/os/phone", Some(PAGE), None, None);
    assert!(!plain.contains(&token), "not asked, not shown");
    let (_, shown) = phone_call("GET", &base, "/os/phone?reveal=1", Some(PAGE), None, None);
    assert!(shown.contains(&token), "the page asked");
    // The phone holds the token and still gets nothing from this route.
    let (s, body) = phone_call("GET", &base, "/os/phone?reveal=1", None, Some(&token), None);
    assert_eq!((s, body.contains(&token)), (403, false));
    // Off: the host and token file leave launch.json (loopback again); the same token stays on disk for next time.
    let (s, _) = phone_call("POST", &base, "/os/phone", Some(PAGE), None, Some(r#"{"enabled":false}"#));
    assert_eq!(s, 200);
    let l = infantry_daemon::os_app::Launch::read(&state).unwrap();
    assert_eq!((l.host, l.token_file), (None, None));
    assert_eq!(std::fs::read_to_string(state.join("remote-access.token")).unwrap().trim(), token);
    h.join().unwrap().unwrap();
}
