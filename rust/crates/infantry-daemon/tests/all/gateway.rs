// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The strangler gateway end to end, against the INSTALLED Kotlin build as the legacy backend (headless
//! `DaemonMainKt`, `INFANTRY_PROVIDER=stub`, temp state, ports 18962 / 18963). Two concurrent clients: A without the
//! owner credential (the conformance client), B with it. Skipped, with a line saying so, when the build is absent.
//!
//! `INFANTRY_GW_KOTLIN_BUILD` names another build folder (one with `app/InfantryAI.cfg` and `runtime/bin/java.exe`).
use infantry_conformance::client::Client;
use infantry_conformance::{fixture, normalize::Normalizer, scenarios};
use infantry_daemon::auth::AccessPolicy;
use infantry_daemon::gateway::{legacy, Gateway, GatewayConfig, RouteTable};
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::server::{self, App};
use infantry_daemon::sessions::{self, Config, Daemon};
use infantry_protocol::Envelope;
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::{Duration, Instant};

const BUILD: &str = r"C:\Users\antho\agenthq-dist\builds\20261117-181728-6afaed5e";
const KOTLIN_PORT: u16 = 18962;
const GATEWAY_PORT: u16 = 18963;
const T: Duration = Duration::from_secs(30);

/// A java @argfile that runs the build's headless daemon with the classpath and plugin root its launcher uses.
fn argfile(build: &Path, out: &Path) -> std::io::Result<()> {
    let app = build.join("app");
    let cfg = std::fs::read_to_string(app.join("InfantryAI.cfg"))?;
    let appdir = app.display().to_string();
    let cp: Vec<String> = cfg.lines().filter_map(|l| l.strip_prefix("app.classpath=")).map(|p| p.trim().replace("$APPDIR", &appdir)).collect();
    let q = |s: &str| format!("\"{}\"", s.replace('\\', "\\\\"));
    let lines = [
        "-Xmx512m".to_string(),
        "--add-opens=java.base/java.util=ALL-UNNAMED".into(),
        q(&format!("-Dinfantry.plugins.bundled={}", app.join("resources").join("modules").display())),
        "-Dinfantry.plugins.devTrust=true".into(),
        "-Dinfantry.env=prod".into(),
        "-cp".into(),
        q(&cp.join(";")),
        "com.superiorsoftllc.infantry.daemon.DaemonMainKt".into(),
    ];
    std::fs::write(out, lines.join("\n") + "\n")
}

struct Running {
    gw: Arc<Gateway>,
    instance: String,
    stop: Option<tokio::sync::oneshot::Sender<()>>,
    dir: PathBuf,
}

impl Drop for Running {
    fn drop(&mut self) {
        self.gw.stop();
        if let Some(s) = self.stop.take() {
            let _ = s.send(());
        }
        // The backend holds files in the state dir until it is gone.
        let end = Instant::now() + Duration::from_secs(10);
        while self.gw.legacy.pid().is_some() && Instant::now() < end {
            std::thread::sleep(Duration::from_millis(100));
        }
        for _ in 0..20 {
            if std::fs::remove_dir_all(&self.dir).is_ok() || !self.dir.exists() {
                break;
            }
            std::thread::sleep(Duration::from_millis(250));
        }
    }
}

fn start(build: &Path) -> Running {
    for k in ["INFANTRY_URL", "INFANTRY_TOKEN", "INFANTRY_TOKEN_FILE", "INFANTRY_HOME", "AGENTHQ_URL", "AGENTHQ_TOKEN", "AGENTHQ_HOME"] {
        std::env::remove_var(k);
    }
    std::env::set_var("INFANTRY_PROVIDER", "stub");
    let dir = std::env::temp_dir().join(format!("infantry-gw-{}-{}", std::process::id(), sessions::now_ms()));
    let state = dir.join("state");
    std::fs::create_dir_all(&state).unwrap();
    let args = dir.join("kotlin.args");
    argfile(build, &args).unwrap();
    let java = build.join("runtime").join("bin").join("java.exe");
    let gw = Gateway::new(GatewayConfig {
        legacy_command: vec![java.display().to_string(), format!("@{}", args.display())],
        legacy_port: KOTLIN_PORT,
        state_dir: state.clone(),
        access_token: None,
        routes: RouteTable::new(sessions::owns),
    })
    .unwrap();
    let (tx, rx) = std::sync::mpsc::channel();
    let (stop_tx, stop_rx) = tokio::sync::oneshot::channel::<()>();
    let g2 = gw.clone();
    std::thread::spawn(move || {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async move {
            let cfg = Config::new(state.clone(), state);
            let daemon = Daemon::new(Arc::new(Stub::default()), cfg).unwrap();
            let policy = Arc::new(AccessPolicy::new("127.0.0.1", None).unwrap());
            let listener = tokio::net::TcpListener::bind(("127.0.0.1", GATEWAY_PORT)).await.unwrap();
            tx.send(daemon.instance.clone()).unwrap();
            server::serve_with(listener, App { daemon, policy }, Some(g2), async {
                let _ = stop_rx.await;
            })
            .await
            .unwrap();
        });
    });
    let instance = rx.recv_timeout(Duration::from_secs(10)).unwrap();
    let r = Running { gw, instance, stop: Some(stop_tx), dir };
    wait_ready(&r.gw, Duration::from_secs(180));
    r
}

fn wait_ready(gw: &Gateway, max: Duration) {
    let end = Instant::now() + max;
    while !gw.backend_ready() {
        assert!(Instant::now() < end, "the legacy backend never became ready");
        std::thread::sleep(Duration::from_millis(250));
    }
}

fn url() -> String {
    format!("ws://127.0.0.1:{GATEWAY_PORT}/ws")
}

fn req(ty: &str, data: &str, id: Option<&str>) -> Envelope {
    let mut e = Envelope::new(ty, data);
    e.request_id = id.map(String::from);
    e
}

/// The owner client: the conformance client cannot send the owner header, so this one is plain tungstenite.
struct Owner {
    ws: tungstenite::WebSocket<std::net::TcpStream>,
    seen: Vec<Envelope>,
    cursor: usize,
}

impl Owner {
    fn connect(token: &str) -> Owner {
        use tungstenite::client::IntoClientRequest;
        let mut r = url().into_client_request().unwrap();
        r.headers_mut().insert("X-Infantry-Owner", token.parse().unwrap());
        let tcp = std::net::TcpStream::connect(("127.0.0.1", GATEWAY_PORT)).unwrap();
        let (ws, _) = tungstenite::client(r, tcp).unwrap();
        ws.get_ref().set_read_timeout(Some(Duration::from_millis(100))).unwrap();
        Owner { ws, seen: Vec::new(), cursor: 0 }
    }
    fn send(&mut self, e: &Envelope) {
        self.ws.send(tungstenite::Message::Text(serde_json::to_string(e).unwrap())).unwrap();
    }
    fn pump(&mut self) {
        match self.ws.read() {
            Ok(tungstenite::Message::Text(t)) => self.seen.push(serde_json::from_str(&t).unwrap()),
            Ok(_) => {}
            Err(tungstenite::Error::Io(e)) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => {}
            Err(e) => panic!("owner read: {e}"),
        }
    }
    fn wait_for(&mut self, max: Duration, pred: impl Fn(&Envelope) -> bool) -> Envelope {
        let end = Instant::now() + max;
        loop {
            while self.cursor < self.seen.len() {
                self.cursor += 1;
                if pred(&self.seen[self.cursor - 1]) {
                    return self.seen[self.cursor - 1].clone();
                }
            }
            assert!(Instant::now() < end, "owner: timed out; saw {:?}", self.seen.iter().map(|e| (&e.r#type, &e.request_id)).collect::<Vec<_>>());
            self.pump();
        }
    }
    fn drain(&mut self, d: Duration) {
        let end = Instant::now() + d;
        while Instant::now() < end {
            self.pump();
        }
    }
}

fn id_is(e: &Envelope, id: &str) -> bool {
    e.request_id.as_deref() == Some(id)
}

#[test]
fn the_gateway_serves_rust_frames_and_forwards_the_rest_to_kotlin() {
    let build = std::env::var("INFANTRY_GW_KOTLIN_BUILD").map(PathBuf::from).unwrap_or_else(|_| PathBuf::from(BUILD));
    if !build.join("app").join("InfantryAI.cfg").exists() {
        eprintln!("SKIPPED: no Kotlin build at {}", build.display());
        return;
    }
    let r = start(&build);
    let owner_token = r.gw.owner_token().expect("Kotlin wrote owner.token into the shared state dir");

    // The ready burst: Kotlin's order, Rust's instance and sessions.
    let mut a = Client::connect(&url(), None).unwrap();
    let ready = a.wait_for(T, |e| e.r#type == "ready").unwrap();
    assert!(ready.data.contains(&format!("instance={}", r.instance)), "ready carries Rust's instance: {}", ready.data);
    a.drain(Duration::from_millis(700), Duration::from_secs(10)).unwrap();
    let burst: Vec<&str> = a.seen.iter().map(|e| e.r#type.as_str()).collect();
    assert_eq!(&burst[..4], ["ready", "policy", "sessions", "worktrees"], "burst {burst:?}");
    let mut b = Owner::connect(&owner_token);
    b.wait_for(T, |e| e.r#type == "worktrees");
    b.drain(Duration::from_millis(700));

    // A project (Kotlin), then a ticket: its push reaches the OTHER client with no id; the sender gets its own id back.
    let folder = r.dir.join("proj");
    std::fs::create_dir_all(&folder).unwrap();
    a.send(&req("project_save", &json!({"name": "gw", "directory": folder.display().to_string()}).to_string(), Some("p-1"))).unwrap();
    let p = a.wait_for(T, |e| id_is(e, "p-1")).unwrap();
    assert_eq!(p.r#type, "project", "{p:?}");
    let project_id = serde_json::from_str::<Value>(&p.data).unwrap()["id"].as_str().unwrap().to_string();

    a.send(&req("ticket_save", &json!({"title": "through the gateway", "projectId": project_id}).to_string(), Some("ts-1"))).unwrap();
    let t = a.wait_for(T, |e| id_is(e, "ts-1")).unwrap();
    assert_eq!(t.r#type, "ticket", "{t:?}");
    let pushed = b.wait_for(T, |e| e.r#type == "ticket" && e.request_id.is_none());
    assert!(pushed.data.contains("through the gateway"), "Kotlin's broadcast reached the owner client: {}", pushed.data);

    // memory_save and ticket_list: answered by Kotlin through Rust, requestId preserved, to the sender only.
    a.send(&req("memory_save", &json!({"projectId": project_id, "name": "gw-note", "description": "d", "body": "via rust"}).to_string(), Some("m-1"))).unwrap();
    let m = a.wait_for(T, |e| id_is(e, "m-1")).unwrap();
    assert_eq!(m.r#type, "memory_note", "{m:?}");
    b.send(&req("ticket_list", &project_id, Some("tl-1")));
    let tl = b.wait_for(T, |e| id_is(e, "tl-1"));
    assert_eq!(tl.r#type, "tickets");
    assert!(tl.data.contains("through the gateway"));
    // A frame with no id: answered with no id (the minted one is stripped).
    a.send(&req("project_list", "", None)).unwrap();
    let pl = a.wait_for(T, |e| e.r#type == "projects").unwrap();
    assert!(pl.request_id.is_none(), "{pl:?}");
    a.drain(Duration::from_millis(500), Duration::from_secs(5)).unwrap();
    b.drain(Duration::from_millis(500));
    for e in a.seen.iter().chain(b.seen.iter()) {
        assert!(!e.request_id.as_deref().unwrap_or("").contains("gw:"), "a minted id leaked: {e:?}");
    }
    assert!(!b.seen.iter().any(|e| id_is(e, "m-1") || id_is(e, "p-1")), "an answer went to the wrong client");
    assert!(!a.seen.iter().any(|e| id_is(e, "tl-1")), "an answer went to the wrong client");

    // The conformance sessions suite, through the gateway (Rust owns the sessions frames).
    let cfg = scenarios::Config { url: url(), token: None, timeout: T };
    let fixtures = Path::new(env!("CARGO_MANIFEST_DIR")).join("../conformance/fixtures/kotlin");
    let mut failures = Vec::new();
    let mut diffs = Vec::new();
    for name in scenarios::NAMES {
        let run = scenarios::run(name, &cfg);
        if let Err(e) = run.result {
            failures.push(format!("{name}: {e}"));
            continue;
        }
        let norm = Normalizer::new().transcript(&run.frames);
        if let Some(d) = fixture::diff(&fixture::read(&fixtures, scenarios::SUITE, name).unwrap(), &norm) {
            diffs.push(format!("{name}: {d}"));
        }
    }
    assert!(failures.is_empty(), "sessions suite through the gateway:\n{}", failures.join("\n"));
    if !diffs.is_empty() {
        eprintln!("fixture differences through the gateway (informational):\n{}", diffs.join("\n"));
    }

    // Kotlin down: forwarded frames are refused with the contract text, Rust frames still work, and it comes back.
    let pid = r.gw.legacy.pid().expect("a running backend");
    let _ = std::process::Command::new("taskkill").args(["/PID", &pid.to_string(), "/F"]).output();
    let end = Instant::now() + T;
    while r.gw.backend_ready() {
        assert!(Instant::now() < end, "the gateway never noticed the backend went away");
        std::thread::sleep(Duration::from_millis(50));
    }
    b.send(&req("ticket_list", &project_id, Some("tl-down")));
    let refused = b.wait_for(T, |e| id_is(e, "tl-down"));
    assert_eq!((refused.r#type.as_str(), refused.data.as_str()), ("error", "ticket_list refused: legacy backend unavailable"));
    b.send(&req("sessions", "", None));
    b.wait_for(T, |e| e.r#type == "sessions");
    wait_ready(&r.gw, Duration::from_secs(180));
    assert_ne!(r.gw.legacy.pid(), Some(pid), "the backend was restarted");
    b.send(&req("ticket_list", &project_id, Some("tl-back")));
    let back = b.wait_for(T, |e| id_is(e, "tl-back"));
    assert_eq!(back.r#type, "tickets");
    assert!(back.data.contains("through the gateway"), "same state dir: the ticket survived the restart");
    a.close();
    drop(r);
}

#[test]
fn a_free_port_is_found() {
    assert!(legacy::free_port().unwrap() > 0);
}
