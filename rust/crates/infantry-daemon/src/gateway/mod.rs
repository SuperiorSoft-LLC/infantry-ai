// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The strangler gateway (docs/rust-port/PLAN.md section 1.6). The Rust daemon is the front door: it answers the frame
//! types a ported unit owns ([routes::RouteTable]) and forwards every other frame, unchanged, to a headless Kotlin
//! daemon on a private port ([legacy::Legacy]). The Kotlin side needs no change: to it the gateway is an ordinary client.
//!
//! - **Upstreams.** One WebSocket to Kotlin as an OWNER client (`owner.token` plus the bearer subprotocol) carries the
//!   owner clients' frames and is the one whose broadcasts are relayed. A second one without the owner credential
//!   carries the frames of clients that did not present it, so Kotlin's owner gate still applies to them.
//! - **Correlation.** Every forwarded frame carries a `requestId`: the client's own (kept, so Kotlin's prefix rules such
//!   as `plugin:` / `loopback:` / `send:` hold), or one the gateway mints (`gw:<n>`), stripped again on the way back.
//!   Kotlin echoes the id on a direct answer and never on a push, so a frame with an id goes to its sender only and
//!   one without goes to every client.
//! - **Ready burst.** A connecting client gets Kotlin's burst for a client of its kind (ready, policy, sessions,
//!   worktrees, config, open prompts, plugin replay, in Kotlin's order), with the frames a Rust unit owns (`sessions`)
//!   taken from Rust and the `ready` line carrying Rust's instance (the session sequences are Rust's).
//! - **Down.** While Kotlin is unreachable a forwarded frame is refused `"<type> refused: legacy backend unavailable"`;
//!   the supervisor restarts Kotlin with backoff and the upstreams reconnect by themselves.

pub mod legacy;
pub mod routes;

use crate::wire;
use futures_util::{SinkExt, StreamExt};
use infantry_protocol::Envelope;
use serde_json::Value;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU16, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};
use tokio::sync::mpsc;
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::Message;

pub use routes::{Route, RouteTable};

/// The refusal reason while the backend is unreachable (`"<type> refused: legacy backend unavailable"`).
pub const UNAVAILABLE: &str = "legacy backend unavailable";
/// The prefix of a gateway-minted request id.
pub const MINTED_PREFIX: &str = "gw:";
/// A frame type no daemon serves: its `error` answer (with the id echoed) marks the end of a connection burst, because
/// Kotlin queues the whole burst before it reads the first frame.
pub const SYNC_FRAME: &str = "gateway_sync";
/// How long an unanswered forwarded frame is remembered (many frames are answered by a push, never directly).
pub const PENDING_TTL: Duration = Duration::from_secs(600);
/// Past this many remembered frames only the recent ones ([PENDING_REFUSE_WINDOW]) are kept.
pub const PENDING_MAX: usize = 8192;
/// An upstream lost within this time of forwarding a frame the client correlates refuses it, so the client does not wait.
pub const PENDING_REFUSE_WINDOW: Duration = Duration::from_secs(60);
/// How long a burst may take on a loaded backend.
pub const BURST_TIMEOUT: Duration = Duration::from_secs(20);
/// Reconnect delay of an upstream, first and largest.
pub const RECONNECT_START: Duration = Duration::from_millis(250);
pub const RECONNECT_MAX: Duration = Duration::from_secs(2);

/// The owner credential headers and subprotocol (Kotlin's `OwnerCredential`).
pub const OWNER_HEADER: &str = "X-Infantry-Owner";
pub const OWNER_HEADER_LEGACY: &str = "X-AgentHQ-Owner";
pub const OWNER_PROTOCOL_PREFIX: &str = "infantry.owner.";
pub const OWNER_TOKEN_FILE: &str = "owner.token";

pub struct GatewayConfig {
    pub legacy_command: Vec<String>,
    pub legacy_port: u16,
    pub state_dir: PathBuf,
    /// The access token (INFANTRY_TOKEN), sent upstream as the bearer subprotocol.
    pub access_token: Option<String>,
    pub routes: RouteTable,
}

struct Pending {
    conn: u64,
    /// The client's own id (put back on the answer), or None for a minted one (removed from the answer).
    original: Option<String>,
    ty: String,
    session: Option<String>,
    owner: bool,
    at: Instant,
}

#[derive(Default)]
struct Upstream {
    tx: Mutex<Option<mpsc::UnboundedSender<String>>>,
}

impl Upstream {
    fn send(&self, text: String) -> bool {
        match self.tx.lock().unwrap().as_ref() {
            Some(tx) => tx.send(text).is_ok(),
            None => false,
        }
    }
    fn ready(&self) -> bool {
        self.tx.lock().unwrap().as_ref().is_some_and(|t| !t.is_closed())
    }
}

pub struct Gateway {
    pub routes: RouteTable,
    pub legacy: Arc<legacy::Legacy>,
    state_dir: PathBuf,
    access_token: Option<String>,
    clients: Mutex<HashMap<u64, mpsc::UnboundedSender<String>>>,
    pending: Mutex<HashMap<String, Pending>>,
    next_conn: AtomicU64,
    next_id: AtomicU64,
    owner_up: Upstream,
    agent_up: Upstream,
    /// The gateway's public port, written back to `daemon.port` once Kotlin has written its private one there.
    public_port: AtomicU16,
    stopping: AtomicBool,
}

fn b64url(s: &str) -> String {
    use base64::Engine;
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(s.as_bytes())
}

fn b64url_decode(s: &str) -> Option<String> {
    use base64::Engine;
    let b = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(s.trim_end_matches('=')).ok()?;
    String::from_utf8(b).ok()
}

fn digest(s: &str) -> [u8; 32] {
    use sha2::Digest;
    sha2::Sha256::digest(s.as_bytes()).into()
}

fn error_frame(msg: String, session: Option<&str>, request: Option<&str>) -> String {
    let mut e = Envelope::new(wire::ERROR, msg);
    e.session_id = session.map(String::from);
    e.request_id = request.map(String::from);
    serde_json::to_string(&e).unwrap_or_default()
}

fn frame_type(v: &Value) -> &str {
    v.get("type").and_then(Value::as_str).unwrap_or("")
}

fn request_id(v: &Value) -> Option<&str> {
    v.get("requestId").and_then(Value::as_str)
}

impl Gateway {
    pub fn new(cfg: GatewayConfig) -> Result<Arc<Gateway>, String> {
        let legacy = legacy::Legacy::new(cfg.legacy_command, cfg.legacy_port, cfg.state_dir.clone())?;
        Ok(Arc::new(Gateway {
            routes: cfg.routes,
            legacy,
            state_dir: cfg.state_dir,
            access_token: cfg.access_token,
            clients: Mutex::new(HashMap::new()),
            pending: Mutex::new(HashMap::new()),
            next_conn: AtomicU64::new(1),
            next_id: AtomicU64::new(1),
            owner_up: Upstream::default(),
            agent_up: Upstream::default(),
            public_port: AtomicU16::new(0),
            stopping: AtomicBool::new(false),
        }))
    }

    /// Starts the backend supervisor and both upstream connections (call inside the tokio runtime).
    pub fn start(self: &Arc<Self>, public_port: u16) {
        self.public_port.store(public_port, Ordering::SeqCst);
        self.legacy.supervise();
        for owner in [true, false] {
            let me = self.clone();
            tokio::spawn(async move { me.upstream_loop(owner).await });
        }
    }

    pub fn stop(&self) {
        self.stopping.store(true, Ordering::SeqCst);
        self.legacy.stop();
    }

    /// True while the owner upstream is connected and past its burst (the backend answers).
    pub fn backend_ready(&self) -> bool {
        self.owner_up.ready()
    }

    fn url(&self) -> String {
        format!("ws://127.0.0.1:{}/ws", self.legacy.port)
    }

    /// The owner credential Kotlin generated into the shared state dir (None until it has started once).
    pub fn owner_token(&self) -> Option<String> {
        let t = std::fs::read_to_string(self.state_dir.join(OWNER_TOKEN_FILE)).ok()?;
        let t = t.trim_start_matches('\u{feff}').trim().to_string();
        (!t.is_empty()).then_some(t)
    }

    /// Whether a client presented the owner credential (header, else the `infantry.owner.<base64url>` subprotocol).
    pub fn is_owner(&self, header: Option<&str>, protocols: Option<&str>) -> bool {
        let presented = header.map(str::trim).filter(|h| !h.is_empty()).map(String::from).or_else(|| {
            protocols?.split(',').map(str::trim).find_map(|p| p.strip_prefix(OWNER_PROTOCOL_PREFIX)).and_then(b64url_decode)
        });
        match (presented, self.owner_token()) {
            (Some(p), Some(t)) => digest(&p) == digest(&t),
            _ => false,
        }
    }

    fn request(&self, owner: bool) -> Result<tokio_tungstenite::tungstenite::handshake::client::Request, String> {
        let mut req = self.url().into_client_request().map_err(|e| e.to_string())?;
        let mut protocols = vec![crate::auth::ANSWER_PROTOCOL.to_string()];
        if let Some(t) = &self.access_token {
            protocols.push(format!("{}{}", crate::auth::BEARER_PROTOCOL_PREFIX, b64url(t)));
        }
        if owner {
            let t = self.owner_token().ok_or("no owner.token in the state dir yet")?;
            req.headers_mut().insert(OWNER_HEADER, t.parse().map_err(|_| "owner.token is not a header value")?);
            protocols.push(format!("{OWNER_PROTOCOL_PREFIX}{}", b64url(&t)));
        }
        req.headers_mut().insert("Sec-WebSocket-Protocol", protocols.join(", ").parse().map_err(|_| "bad subprotocol")?);
        Ok(req)
    }

    fn sync_frame(&self) -> (String, String) {
        let id = format!("{MINTED_PREFIX}sync:{}", self.next_id.fetch_add(1, Ordering::SeqCst));
        let mut e = Envelope::new(SYNC_FRAME, "");
        e.request_id = Some(id.clone());
        (id, serde_json::to_string(&e).unwrap_or_default())
    }

    /// Opens a connection to Kotlin, sends the sync frame and returns the socket plus every frame before its answer.
    async fn connect_with_burst(
        &self,
        owner: bool,
    ) -> Result<(tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>, Vec<String>), String> {
        let req = self.request(owner)?;
        let (mut ws, _) = tokio::time::timeout(BURST_TIMEOUT, tokio_tungstenite::connect_async(req))
            .await
            .map_err(|_| "connect timed out".to_string())?
            .map_err(|e| e.to_string())?;
        let (sync_id, sync) = self.sync_frame();
        ws.send(Message::Text(sync.into())).await.map_err(|e| e.to_string())?;
        let mut burst = Vec::new();
        let end = Instant::now() + BURST_TIMEOUT;
        loop {
            let left = end.saturating_duration_since(Instant::now());
            let m = tokio::time::timeout(left, ws.next()).await.map_err(|_| "no burst end in time".to_string())?;
            match m {
                Some(Ok(Message::Text(t))) => {
                    let t = t.to_string();
                    let done = serde_json::from_str::<Value>(&t).ok().is_some_and(|v| request_id(&v) == Some(sync_id.as_str()));
                    if done {
                        return Ok((ws, burst));
                    }
                    burst.push(t);
                }
                Some(Ok(Message::Close(_))) | None => return Err("closed during the burst".into()),
                Some(Err(e)) => return Err(e.to_string()),
                Some(Ok(_)) => {}
            }
        }
    }

    async fn upstream_loop(self: Arc<Self>, owner: bool) {
        let mut delay = RECONNECT_START;
        let mut was_up = false;
        while !self.stopping.load(Ordering::SeqCst) {
            match self.connect_with_burst(owner).await {
                Ok((ws, _burst)) => {
                    delay = RECONNECT_START;
                    let (mut sink, mut stream) = ws.split();
                    let (tx, mut rx) = mpsc::unbounded_channel::<String>();
                    *self.up(owner).tx.lock().unwrap() = Some(tx);
                    eprintln!("[infantry] gateway: {} upstream connected to the legacy backend", if owner { "owner" } else { "agent" });
                    was_up = true;
                    if owner {
                        self.write_port_file();
                    }
                    let writer = tokio::spawn(async move {
                        while let Some(t) = rx.recv().await {
                            if sink.send(Message::Text(t.into())).await.is_err() {
                                break;
                            }
                        }
                        let _ = sink.close().await;
                    });
                    while let Some(m) = stream.next().await {
                        match m {
                            Ok(Message::Text(t)) => self.on_upstream(owner, t.to_string()),
                            Ok(Message::Close(_)) | Err(_) => break,
                            Ok(_) => {}
                        }
                    }
                    *self.up(owner).tx.lock().unwrap() = None;
                    writer.abort();
                    self.upstream_lost(owner);
                }
                Err(e) => {
                    if was_up {
                        eprintln!("[infantry] gateway: legacy backend unreachable ({e}); retrying");
                        was_up = false;
                    }
                    tokio::time::sleep(delay).await;
                    delay = (delay * 2).min(RECONNECT_MAX);
                }
            }
        }
    }

    fn up(&self, owner: bool) -> &Upstream {
        if owner { &self.owner_up } else { &self.agent_up }
    }

    /// Kotlin writes its private port into `daemon.port` when it binds; clients must find the gateway there.
    fn write_port_file(&self) {
        let port = self.public_port.load(Ordering::SeqCst);
        if port != 0 {
            let _ = std::fs::write(self.state_dir.join("daemon.port"), port.to_string());
        }
    }

    /// One frame from Kotlin: a correlated answer goes to its sender, a push (owner upstream only) to every client.
    fn on_upstream(&self, owner: bool, text: String) {
        let Ok(mut v) = serde_json::from_str::<Value>(&text) else { return };
        if let Some(id) = request_id(&v).map(String::from) {
            let p = self.pending.lock().unwrap().get(&id).map(|p| (p.conn, p.original.clone()));
            let Some((conn, original)) = p else { return };
            let out = if original.as_deref() == Some(id.as_str()) {
                text
            } else {
                let obj = v.as_object_mut().expect("an envelope is an object");
                match original {
                    Some(o) => {
                        obj.insert("requestId".into(), Value::String(o));
                    }
                    None => {
                        obj.remove("requestId");
                    }
                }
                v.to_string()
            };
            self.send_to(conn, out);
            return;
        }
        if !owner {
            return;
        }
        // A frame a Rust unit owns is Rust's to send (Kotlin's view of the sessions is not the one clients drive).
        if self.routes.is_rust(frame_type(&v)) {
            return;
        }
        let clients: Vec<_> = self.clients.lock().unwrap().values().cloned().collect();
        for c in clients {
            let _ = c.send(text.clone());
        }
    }

    fn upstream_lost(&self, owner: bool) {
        let now = Instant::now();
        let lost: Vec<(u64, String)> = {
            let mut p = self.pending.lock().unwrap();
            let mut out = Vec::new();
            p.retain(|_, e| {
                if e.owner != owner {
                    return true;
                }
                if e.original.is_some() && now.duration_since(e.at) < PENDING_REFUSE_WINDOW {
                    out.push((e.conn, error_frame(format!("{} refused: {UNAVAILABLE}", e.ty), e.session.as_deref(), e.original.as_deref())));
                }
                false
            });
            out
        };
        for (c, f) in lost {
            self.send_to(c, f);
        }
    }

    fn send_to(&self, conn: u64, text: String) {
        if let Some(c) = self.clients.lock().unwrap().get(&conn) {
            let _ = c.send(text);
        }
    }

    /// A new client: its id and the queue of frames the gateway relays to it.
    pub fn register(&self) -> (u64, mpsc::UnboundedReceiver<String>) {
        let id = self.next_conn.fetch_add(1, Ordering::SeqCst);
        let (tx, rx) = mpsc::unbounded_channel();
        self.clients.lock().unwrap().insert(id, tx);
        (id, rx)
    }

    pub fn unregister(&self, conn: u64) {
        self.clients.lock().unwrap().remove(&conn);
        self.pending.lock().unwrap().retain(|_, p| p.conn != conn);
    }

    /// The connection burst for a client: Kotlin's (for a client of its kind) merged with Rust's `hello`; Rust's alone
    /// while the backend is down.
    pub async fn hello(&self, owner: bool, rust: Vec<String>) -> Vec<String> {
        if !self.backend_ready() {
            return rust;
        }
        match self.connect_with_burst(owner).await {
            Ok((mut ws, burst)) => {
                let _ = ws.close(None).await;
                merge_burst(&self.routes, burst, rust)
            }
            Err(e) => {
                eprintln!("[infantry] gateway: no legacy burst for a new client ({e})");
                rust
            }
        }
    }

    /// Forwards a client frame to Kotlin; `Some(frame)` is the refusal to answer the client with instead.
    pub fn forward(&self, text: &str, conn: u64, owner: bool) -> Option<String> {
        let mut v: Value = match serde_json::from_str(text) {
            Ok(v @ Value::Object(_)) => v,
            _ => return Some(error_frame("frame refused: not an envelope".into(), None, None)),
        };
        let ty = frame_type(&v).to_string();
        let session = v.get("sessionId").and_then(Value::as_str).map(String::from);
        let original = request_id(&v).map(String::from);
        let up = self.up(owner);
        if !up.ready() {
            return Some(error_frame(format!("{ty} refused: {UNAVAILABLE}"), session.as_deref(), original.as_deref()));
        }
        let wire_id = {
            let mut p = self.pending.lock().unwrap();
            if p.len() >= 1024 {
                let now = Instant::now();
                p.retain(|_, e| now.duration_since(e.at) < PENDING_TTL);
                if p.len() >= PENDING_MAX {
                    p.retain(|_, e| now.duration_since(e.at) < PENDING_REFUSE_WINDOW);
                }
            }
            let n = self.next_id.fetch_add(1, Ordering::SeqCst);
            let wire_id = match &original {
                // Kept as it is (Kotlin reads its prefix), unless another client has the same id in flight.
                Some(o) if p.get(o).is_none_or(|e| e.conn == conn) => o.clone(),
                Some(o) => format!("{o}#{MINTED_PREFIX}{n}"),
                None => format!("{MINTED_PREFIX}{n}"),
            };
            p.insert(wire_id.clone(), Pending { conn, original: original.clone(), ty: ty.clone(), session: session.clone(), owner, at: Instant::now() });
            wire_id
        };
        let text = if original.as_deref() == Some(wire_id.as_str()) {
            text.to_string()
        } else {
            v.as_object_mut().unwrap().insert("requestId".into(), Value::String(wire_id.clone()));
            v.to_string()
        };
        if up.send(text) {
            None
        } else {
            self.pending.lock().unwrap().remove(&wire_id);
            Some(error_frame(format!("{ty} refused: {UNAVAILABLE}"), session.as_deref(), original.as_deref()))
        }
    }
}

fn ready_field<'a>(line: &'a str, key: &str) -> Option<&'a str> {
    line.split(' ').find_map(|kv| kv.strip_prefix(key).and_then(|r| r.strip_prefix('=')))
}

/// Kotlin's burst in Kotlin's order; a frame type a Rust unit owns comes from Rust's `hello` instead, and `ready`
/// carries Rust's `instance` and `since` (the per-session sequences and the replay history are Rust's).
pub fn merge_burst(routes: &RouteTable, kotlin: Vec<String>, rust: Vec<String>) -> Vec<String> {
    let rust: Vec<(String, String)> = rust
        .into_iter()
        .map(|t| (serde_json::from_str::<Value>(&t).ok().map(|v| frame_type(&v).to_string()).unwrap_or_default(), t))
        .collect();
    let rust_ready = rust.iter().find(|(ty, _)| ty == wire::READY).and_then(|(_, t)| serde_json::from_str::<Envelope>(t).ok());
    let mut out = Vec::with_capacity(kotlin.len());
    for t in kotlin {
        let Ok(mut v) = serde_json::from_str::<Value>(&t) else { continue };
        let ty = frame_type(&v).to_string();
        if ty == wire::READY {
            if let (Some(r), Some(data)) = (&rust_ready, v.get("data").and_then(Value::as_str)) {
                let mut line = data.to_string();
                for key in ["instance", "since"] {
                    if let (Some(old), Some(new)) = (ready_field(data, key), ready_field(&r.data, key)) {
                        line = line.replacen(&format!("{key}={old}"), &format!("{key}={new}"), 1);
                    }
                }
                v.as_object_mut().unwrap().insert("data".into(), Value::String(line));
                out.push(v.to_string());
                continue;
            }
        }
        if routes.is_rust(&ty) {
            if let Some((_, r)) = rust.iter().find(|(rt, _)| *rt == ty) {
                out.push(r.clone());
            }
            continue;
        }
        out.push(t);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn f(ty: &str, data: &str) -> String {
        serde_json::to_string(&Envelope::new(ty, data)).unwrap()
    }

    #[test]
    fn burst_keeps_kotlin_order_and_takes_rust_sessions() {
        let routes = RouteTable::new(crate::sessions::owns);
        let kotlin = vec![
            f("ready", "provider=stub contract=1.155.0 instance=K since=5 build=b"),
            f("policy", "{\"k\":1}"),
            f("sessions", "[\"kotlin\"]"),
            f("worktrees", "[1]"),
            f("config_schema", "{}"),
            f("attention", "{}"),
        ];
        let rust = vec![f("ready", "provider=stub contract=1.155.0 instance=R since=0 build=rust"), f("policy", "{}"), f("sessions", "[\"rust\"]"), f("worktrees", "[]")];
        let m: Vec<Envelope> = merge_burst(&routes, kotlin, rust).iter().map(|t| serde_json::from_str(t).unwrap()).collect();
        let types: Vec<&str> = m.iter().map(|e| e.r#type.as_str()).collect();
        assert_eq!(types, ["ready", "policy", "sessions", "worktrees", "config_schema", "attention"]);
        assert_eq!(m[0].data, "provider=stub contract=1.155.0 instance=R since=0 build=b");
        assert_eq!(m[1].data, "{\"k\":1}");
        assert_eq!(m[2].data, "[\"rust\"]");
        assert_eq!(m[3].data, "[1]");
    }

    #[test]
    fn rolled_back_sessions_come_from_kotlin() {
        let routes = RouteTable::new(crate::sessions::owns).with_overrides(r#"{"sessions":"legacy"}"#).unwrap();
        let m = merge_burst(&routes, vec![f("sessions", "[\"kotlin\"]")], vec![f("sessions", "[\"rust\"]")]);
        assert!(m[0].contains("kotlin"));
    }
}
