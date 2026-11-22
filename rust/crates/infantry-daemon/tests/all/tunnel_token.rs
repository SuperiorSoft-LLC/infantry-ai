// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RR-6b: a connection through the runner loopback tunnel is never trusted as loopback/owner. The tunnel listener answers
//! only with a per-session token (401 without one), and a token reaches only its own session's tool surface (403 otherwise).
use infantry_conformance::client::Client;
use infantry_daemon::auth::AccessPolicy;
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::server::{self, App};
use infantry_daemon::sessions::{Config, Daemon};
use std::io::{Read, Write};
use std::sync::Arc;
use std::time::Duration;

/// (daemon, main port, tunnel port): a loopback daemon with NO token, the case where loopback is trusted as owner.
fn start() -> (Arc<Daemon>, u16, u16) {
    static N: std::sync::atomic::AtomicUsize = std::sync::atomic::AtomicUsize::new(0);
    let n = N.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
    let dir = std::env::temp_dir().join(format!("infantry-daemon-tunnel-{}-{n}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(dir.clone(), dir)).unwrap();
    let (tx, rx) = std::sync::mpsc::channel();
    let d = daemon.clone();
    std::thread::spawn(move || {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async move {
            let policy = Arc::new(AccessPolicy::new("127.0.0.1", None).unwrap());
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            tx.send(listener.local_addr().unwrap().port()).unwrap();
            server::serve(listener, App { daemon: d, policy }, std::future::pending()).await.unwrap();
        });
    });
    let main = rx.recv_timeout(Duration::from_secs(10)).unwrap();
    for _ in 0..100 {
        if daemon.tunnel.port() != 0 {
            break;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    let t = daemon.tunnel.port();
    assert_ne!(t, 0, "the tunnel listener is open");
    (daemon, main, t)
}

/// The status line of a raw websocket upgrade request, with the given Authorization header (none = a bare local process).
fn raw_status(port: u16, auth: Option<&str>) -> String {
    let mut s = std::net::TcpStream::connect(("127.0.0.1", port)).unwrap();
    s.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
    let a = auth.map(|t| format!("Authorization: Bearer {t}\r\n")).unwrap_or_default();
    let req = format!("GET /ws HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n{a}Connection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n");
    s.write_all(req.as_bytes()).unwrap();
    let mut buf = [0u8; 256];
    let n = s.read(&mut buf).unwrap_or(0);
    String::from_utf8_lossy(&buf[..n]).lines().next().unwrap_or("").to_string()
}

fn call(sid: &str, as_session: &str) -> infantry_protocol::Envelope {
    let mut e = infantry_protocol::Envelope::new("plugin_tool_call", serde_json::json!({ "name": "x", "client": { "sessionId": as_session } }).to_string());
    e.session_id = Some(sid.into());
    e.request_id = Some("r1".into());
    e
}

#[test]
fn the_tunnel_listener_needs_a_session_token_and_never_trusts_loopback() {
    let (daemon, main, tunnel) = start();
    // The main port trusts loopback (no token configured): that is the owner's own client, unchanged.
    assert!(raw_status(main, None).contains("101"), "the main port still admits a loopback client");
    // The tunnel port: a bare local process (same Host header, no token) is refused, as is a made-up token.
    assert!(raw_status(tunnel, None).contains("401"), "no token -> 401");
    assert!(raw_status(tunnel, Some("not-a-real-token")).contains("401"), "an unknown token -> 401");
    let a = daemon.tunnel.mint("sess-a");
    assert!(raw_status(tunnel, Some(&a)).contains("101"), "a minted token is admitted");
    // The token ends with the session.
    daemon.tunnel.revoke("sess-a");
    assert!(raw_status(tunnel, Some(&a)).contains("401"), "a revoked token -> 401");
}

#[test]
fn a_token_reaches_only_its_own_sessions_tool_surface() {
    let (daemon, _main, tunnel) = start();
    let (ta, _tb) = (daemon.tunnel.mint("sess-a"), daemon.tunnel.mint("sess-b"));
    let mut c = Client::connect(&format!("ws://127.0.0.1:{tunnel}"), Some(&ta)).unwrap();
    let t = Duration::from_secs(10);
    c.wait_for(t, |e| e.r#type == "ready").unwrap();
    // The hello carries no session list.
    // Session A's token making a call as session B, or naming B's session: 403.
    c.send(&call("sess-a", "sess-b")).unwrap();
    let e = c.wait_for(t, |e| e.r#type == "error").unwrap();
    assert!(e.data.starts_with("403 forbidden"), "{}", e.data);
    c.send(&call("sess-b", "sess-a")).unwrap();
    let e = c.wait_for(t, |e| e.r#type == "error").unwrap();
    assert!(e.data.starts_with("403 forbidden"), "{}", e.data);
    // Frames outside the tool surface (owner frames, the session list, a prompt into another session): 403.
    for ty in ["sessions", "prompt", "secret_list", "kill"] {
        c.send_frame(ty, "{}", Some("sess-b")).unwrap();
        let e = c.wait_for(t, |e| e.r#type == "error").unwrap();
        assert!(e.data.starts_with("403 forbidden"), "{ty}: {}", e.data);
    }
    // Its own call is let through to the daemon (answered by it, not refused as out of scope).
    c.send(&call("sess-a", "sess-a")).unwrap();
    let e = c.wait_for(t, |e| e.request_id.as_deref() == Some("r1")).unwrap();
    assert!(!e.data.starts_with("403 forbidden"), "{}", e.data);
    c.close();
}
