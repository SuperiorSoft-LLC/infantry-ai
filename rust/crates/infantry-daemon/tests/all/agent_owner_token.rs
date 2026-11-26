// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! AGENT-OWNER-TOKEN-1: an agent session's raw `/ws` connection is never the owner. Every session is lent its own token
//! (as `INFANTRY_TOKEN`, in place of the owner's); a connection presenting it is that session's agent: owner-only setting
//! keys and control of the owner's own session are refused, while board, ticket and mail tools keep working. The owner's
//! client (no session token) is unchanged. A composed daemon on a scratch state dir; the owner's daemon is never touched.
use infantry_conformance::client::Client;
use infantry_daemon::auth::AccessPolicy;
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::server::{self, App};
use infantry_daemon::sessions::{Config, Daemon, NewOpts};
use infantry_protocol::Envelope;
use serde_json::{json, Value};
use std::sync::Arc;
use std::time::Duration;

const T: Duration = Duration::from_secs(10);
const OWNER_TOKEN: &str = "owner-token-0123456789abcdef0123456789";

struct Fixture {
    dir: std::path::PathBuf,
    daemon: Arc<Daemon>,
    port: u16,
    _c: Arc<infantry_daemon::compose::Composed>,
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

/// A composed daemon served on a free loopback port; `token` = the daemon's own (owner) token, None = loopback trust.
fn start(token: Option<&str>) -> Fixture {
    let dir = std::env::temp_dir().join(format!("infantry-agent-owner-token-{}", uuid::Uuid::new_v4()));
    std::fs::create_dir_all(&dir).unwrap();
    let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(dir.clone(), dir.clone())).unwrap();
    let c = infantry_daemon::compose::build(&daemon, &dir).unwrap();
    let (tx, rx) = std::sync::mpsc::channel();
    let (d, token) = (daemon.clone(), token.map(String::from));
    std::thread::spawn(move || {
        let rt = tokio::runtime::Runtime::new().unwrap();
        rt.block_on(async move {
            let policy = Arc::new(AccessPolicy::new("127.0.0.1", token.as_deref()).unwrap());
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            tx.send(listener.local_addr().unwrap().port()).unwrap();
            server::serve(listener, App { daemon: d, policy }, std::future::pending()).await.unwrap();
        });
    });
    let port = rx.recv_timeout(T).unwrap();
    Fixture { dir, daemon, port, _c: c }
}

impl Fixture {
    fn client(&self, token: Option<&str>) -> Client {
        let mut c = Client::connect(&format!("ws://127.0.0.1:{}", self.port), token).unwrap();
        c.wait_for(T, |e| e.r#type == "ready").unwrap();
        c
    }

    /// A real session started through the daemon's start path, and the token that path lent it (re-minted here: the
    /// test cannot read the child's env, and a fresh mint replaces the lent one exactly as a restart does).
    fn agent_session(&self) -> (String, String) {
        let sid = self.daemon.launch(NewOpts { role: Some("dev".into()), ..Default::default() }).unwrap();
        let tok = self.daemon.tunnel.mint(&sid);
        (sid, tok)
    }
}

fn request(c: &mut Client, ty: &str, data: Value, session: Option<&str>, rid: &str) -> Envelope {
    let mut e = Envelope::new(ty, data.to_string());
    e.session_id = session.map(String::from);
    e.request_id = Some(rid.into());
    c.send(&e).unwrap();
    c.wait_for(T, |r| r.request_id.as_deref() == Some(rid)).unwrap()
}

fn tool(c: &mut Client, name: &str, args: Value, sid: Option<&str>, rid: &str) -> Value {
    let client = sid.map(|s| json!({"sessionId": s})).unwrap_or_else(|| json!({}));
    let r = request(c, "plugin_tool_call", json!({"name": name, "arguments": args, "client": client}), None, rid);
    serde_json::from_str(&r.data).unwrap_or(Value::String(r.data))
}

fn owner_only_setting(c: &mut Client, rid: &str) -> Envelope {
    request(c, "settings_set", json!({"key": "runner.grants", "value": "[]"}), None, rid)
}

fn check(f: &Fixture, owner_token: Option<&str>) {
    let (sid, agent_tok) = f.agent_session();
    let mut owner = f.client(owner_token);
    let mut agent = f.client(Some(&agent_tok));

    // 1. An owner-only settings key: refused to the agent, allowed to the owner.
    let r = owner_only_setting(&mut agent, "s1");
    assert!(r.data.contains("needs the owner credential"), "the agent must be refused: {r:?}");
    let r = owner_only_setting(&mut owner, "s2");
    assert!(!r.data.contains("needs the owner credential"), "the owner is allowed: {r:?}");

    // 2. The owner's own session (the Assistant): the agent may not prompt it; the owner may.
    let a = request(&mut owner, "assistant", json!({}), None, "a1");
    let assistant = a.session_id.clone().expect("the Assistant's id");
    let r = request(&mut agent, "prompt", Value::String("do something".into()), Some(&assistant), "p1");
    assert!(r.r#type == "error" && r.data.contains("OWNER-SESSION-GUARD-1"), "the agent must be refused: {r:?}");
    owner.send_frame("prompt", "\"hello\"", Some(&assistant)).unwrap();
    assert!(owner.wait_for(Duration::from_millis(800), |e| e.data.contains("OWNER-SESSION-GUARD-1")).is_err(), "the owner's prompt is not refused");

    // 3. The agent keeps its tools: ticket create / comment and mail.
    let t = tool(&mut agent, "infantry_ticket_create", json!({"title": "agent token check"}), Some(&sid), "t1");
    assert_ne!(t["isError"], json!(true), "ticket_create as the agent: {t}");
    let id = t["text"].as_str().unwrap_or("").split(|c: char| c.is_whitespace() || c == '"' || c == ',').find(|w| w.starts_with("ticket-")).map(String::from).unwrap_or_else(|| panic!("a ticket id in {t}"));
    let c = tool(&mut agent, "infantry_ticket_comment", json!({"ticketId": id, "body": "comment from the agent"}), Some(&sid), "t2");
    assert_ne!(c["isError"], json!(true), "ticket_comment as the agent: {c}");
    let m = tool(&mut agent, "infantry_message", json!({"to": "owner", "body": "hello from the agent"}), Some(&sid), "t3");
    assert_ne!(m["isError"], json!(true), "message as the agent: {m}");

    // 4. The token ends with its session: a revoked token is no longer an agent identity (and on a token daemon, no entry).
    f.daemon.tunnel.revoke(&sid);
    if owner_token.is_some() {
        assert!(Client::connect(&format!("ws://127.0.0.1:{}", f.port), Some(&agent_tok)).is_err(), "a revoked token is refused");
    }
    owner.close();
    agent.close();
}

#[tokio::test(flavor = "multi_thread")]
async fn an_agent_token_is_not_the_owner_on_a_loopback_daemon() {
    let f = start(None);
    check(&f, None);
}

#[tokio::test(flavor = "multi_thread")]
async fn an_agent_token_is_not_the_owner_on_a_token_daemon() {
    let f = start(Some(OWNER_TOKEN));
    // A wrong token is still refused at the door.
    assert!(Client::connect(&format!("ws://127.0.0.1:{}", f.port), Some("not-a-token-0123456789abcdef0123456789")).is_err());
    check(&f, Some(OWNER_TOKEN));
}

#[tokio::test(flavor = "multi_thread")]
async fn a_session_header_alone_drops_owner() {
    // `infantry` inside a session sends X-Infantry-Session; such a connection is never the owner, token or not.
    let f = start(None);
    let mut req = tungstenite::client::IntoClientRequest::into_client_request(format!("ws://127.0.0.1:{}/ws", f.port)).unwrap();
    req.headers_mut().insert("X-Infantry-Session", "some-session".parse().unwrap());
    let tcp = std::net::TcpStream::connect(("127.0.0.1", f.port)).unwrap();
    tcp.set_read_timeout(Some(Duration::from_millis(200))).unwrap();
    let (mut ws, _) = tungstenite::client(req, tcp).unwrap();
    ws.send(tungstenite::Message::Text(json!({"type": "settings_set", "data": json!({"key": "runner.grants", "value": "[]"}).to_string(), "requestId": "h1"}).to_string().into())).unwrap();
    let deadline = std::time::Instant::now() + T;
    loop {
        assert!(std::time::Instant::now() < deadline, "no answer to the settings_set");
        if let Ok(tungstenite::Message::Text(t)) = ws.read() {
            if t.contains("\"h1\"") {
                assert!(t.contains("needs the owner credential"), "{t}");
                break;
            }
        }
    }
}
