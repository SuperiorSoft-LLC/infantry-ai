// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The CLI's daemon connection (the Kotlin `FrameStream` the commands use): a read cursor that hands each frame out once,
//! requests correlated by `requestId` where the daemon echoes one, `requestLastOf` with a fence, and reconnect with
//! backoff that refuses to re-send a non-idempotent request. Blocking I/O on one socket.

use crate::target::Target;
use serde_json::{Map, Value};

use std::net::{TcpStream, ToSocketAddrs};
use std::time::{Duration, Instant};
use tungstenite::client::IntoClientRequest;
use tungstenite::protocol::frame::coding::CloseCode;
use tungstenite::protocol::CloseFrame;
use tungstenite::stream::MaybeTlsStream;
use tungstenite::{Message, WebSocket};

const POLL_WINDOW_MS: u64 = 100;
const ERROR_GRACE_MS: u64 = 1_000;
const READY_WAIT_MS: u64 = 2_000;
const RECONNECT_BACKOFF_MS: [u64; 6] = [250, 500, 1_000, 2_000, 4_000, 8_000];
const SESSION_HEADER: &str = "X-Infantry-Session";
const OWNER_HEADER: &str = "X-Infantry-Owner";
/// BUG-MCP-RACE-1: the first contract whose daemon echoes `requestId`.
pub const REQUEST_ID_CONTRACT: &str = "1.87.0";
/// The request types whose answer type is also broadcast, so they carry a `requestId` (`Envelope.CORRELATED_REQUESTS`).
pub const CORRELATED_REQUESTS: [&str; 12] = [
    "ticket_save",
    "ticket_get",
    "ticket_list",
    "ticket_move",
    "ticket_link",
    "ticket_unlink",
    "ticket_reorder",
    "ticket_delete",
    "ticket_start_next",
    "ticket_unassign",
    "ticket_assign",
    "agent_switch",
];

/// A failure in the words the Kotlin CLI uses; every one is printed as `error: <message>`.
#[derive(Debug)]
pub enum ConnError {
    /// HTTP 401 / 403: never retried (exit 1).
    AuthRefused(String),
    /// Nothing answered the connect (the CLI reports the daemon not running, exit 2).
    Connect(String),
    /// Anything else: the message is Kotlin's `e.message` (exit 1).
    Failed(String),
}

impl ConnError {
    pub fn message(&self) -> String {
        match self {
            ConnError::AuthRefused(m) | ConnError::Connect(m) | ConnError::Failed(m) => m.clone(),
        }
    }
}

impl From<ConnError> for String {
    fn from(e: ConnError) -> String {
        e.message()
    }
}

/// One frame.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct Envelope {
    pub kind: String,
    pub data: String,
    pub session_id: Option<String>,
    pub sequence: Option<i64>,
    pub request_id: Option<String>,
}

impl Envelope {
    pub fn new(kind: &str, data: impl Into<String>) -> Self {
        Envelope { kind: kind.into(), data: data.into(), ..Default::default() }
    }

    pub fn with_session(mut self, session: Option<String>) -> Self {
        self.session_id = session;
        self
    }

    /// kotlinx with `encodeDefaults = true`: `sessionId` / `sequence` always written, `requestId` only when set.
    pub fn encode(&self) -> String {
        let mut m = Map::new();
        m.insert("type".into(), Value::String(self.kind.clone()));
        m.insert("data".into(), Value::String(self.data.clone()));
        m.insert("sessionId".into(), self.session_id.clone().map_or(Value::Null, Value::String));
        m.insert("sequence".into(), self.sequence.map_or(Value::Null, |s| Value::from(s)));
        if let Some(r) = &self.request_id {
            m.insert("requestId".into(), Value::String(r.clone()));
        }
        serde_json::to_string(&Value::Object(m)).unwrap_or_default()
    }

    /// Decodes one text frame as kotlinx would, or None (a frame kotlinx cannot decode is dropped).
    pub fn decode(text: &str) -> Option<Envelope> {
        let v: Value = serde_json::from_str(text).ok()?;
        let o = v.as_object()?;
        let kind = o.get("type")?.as_str()?.to_string();
        let data = match o.get("data") {
            None => String::new(),
            Some(Value::String(s)) => s.clone(),
            Some(_) => return None,
        };
        let opt_str = |k: &str| -> Result<Option<String>, ()> {
            match o.get(k) {
                None | Some(Value::Null) => Ok(None),
                Some(Value::String(s)) => Ok(Some(s.clone())),
                Some(_) => Err(()),
            }
        };
        let session_id = opt_str("sessionId").ok()?;
        let request_id = opt_str("requestId").ok()?;
        let sequence = match o.get("sequence") {
            None | Some(Value::Null) => None,
            Some(Value::Number(n)) => Some(n.as_i64()?),
            Some(_) => return None,
        };
        Some(Envelope { kind, data, session_id, sequence, request_id })
    }

    /// `key=value` out of a `ready` frame's data.
    pub fn ready_field(&self, key: &str) -> Option<String> {
        self.data
            .split(' ')
            .find_map(|f| f.strip_prefix(&format!("{key}=")).map(str::to_string))
            .filter(|v| !v.trim().is_empty())
    }
}

/// `contractAtLeast`: dotted numeric compare; a missing part is 0, a non-number part makes it false.
pub fn contract_at_least(version: &str, minimum: &str) -> bool {
    let parse = |s: &str| -> Option<Vec<u64>> { s.trim().trim_start_matches("infantry/").split('.').map(|p| p.parse().ok()).collect() };
    let (Some(a), Some(b)) = (parse(version), parse(minimum)) else { return false };
    for i in 0..a.len().max(b.len()) {
        let (x, y) = (a.get(i).copied().unwrap_or(0), b.get(i).copied().unwrap_or(0));
        if x != y {
            return x > y;
        }
    }
    true
}

type Socket = WebSocket<MaybeTlsStream<TcpStream>>;

struct Link {
    socket: Socket,
    frames: Vec<Envelope>,
    closed: bool,
}

impl Link {
    fn set_read_timeout(&mut self, d: Duration) {
        let _ = match self.socket.get_mut() {
            MaybeTlsStream::Plain(s) => s.set_read_timeout(Some(d)),
            MaybeTlsStream::NativeTls(s) => s.get_mut().set_read_timeout(Some(d)),
            _ => Ok(()),
        };
    }

    fn pump(&mut self) -> bool {
        if self.closed {
            return false;
        }
        match self.socket.read() {
            Ok(Message::Text(t)) => {
                if let Some(e) = Envelope::decode(&t) {
                    self.frames.push(e);
                }
                true
            }
            Ok(Message::Close(_)) => {
                self.closed = true;
                false
            }
            Ok(_) => true,
            Err(tungstenite::Error::Io(e)) if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) => false,
            Err(_) => {
                self.closed = true;
                false
            }
        }
    }
}

/// Where to connect and with what credentials.
#[derive(Clone)]
pub struct Endpoint {
    pub target: Target,
    /// RUNNER-POOL-6a: the owner token (`<stateDir>/owner.token`), sent as `X-Infantry-Owner`; None = not the owner.
    pub owner_token: Option<String>,
}

fn refusal_message(t: &Target, status: u16) -> String {
    let why = if t.token.is_none() {
        "no token was sent: pass --token-file, --endpoint NAME, or set INFANTRY_TOKEN_FILE".to_string()
    } else {
        format!("the token from {} was not accepted: it must match the daemon's", t.token_source)
    };
    format!("the daemon at {} refused this client (HTTP {status}): {why}", t.url)
}

fn open_link_once(ep: &Endpoint) -> Result<Link, ConnError> {
    let t = &ep.target;
    let mut request = t.url.as_str().into_client_request().map_err(|e| ConnError::Connect(e.to_string()))?;
    {
        let h = request.headers_mut();
        if let Some(tok) = &t.token {
            if let Ok(v) = format!("Bearer {tok}").parse() {
                h.insert("Authorization", v);
            }
        }
        if let Some(s) = &t.session_id {
            if let Ok(v) = s.parse() {
                h.insert(SESSION_HEADER, v);
            }
        }
        if let Some(o) = &ep.owner_token {
            if let Ok(v) = o.parse() {
                h.insert(OWNER_HEADER, v);
            }
        }
    }
    let uri = request.uri().clone();
    let host = uri.host().unwrap_or("").trim_start_matches('[').trim_end_matches(']').to_string();
    let port = uri.port_u16().unwrap_or(if uri.scheme_str() == Some("wss") { 443 } else { 80 });
    let addrs: Vec<_> = (host.as_str(), port).to_socket_addrs().map_err(|e| ConnError::Connect(e.to_string()))?.collect();
    let mut last = None;
    let mut tcp = None;
    for a in addrs {
        match TcpStream::connect_timeout(&a, Duration::from_millis(t.connect_timeout_ms.max(1) as u64)) {
            Ok(s) => {
                tcp = Some(s);
                break;
            }
            Err(e) => last = Some(e),
        }
    }
    let tcp = tcp.ok_or_else(|| ConnError::Connect(last.map_or("no address".into(), |e| e.to_string())))?;
    let _ = tcp.set_read_timeout(Some(Duration::from_millis(t.connect_timeout_ms.max(1) as u64)));
    let _ = tcp.set_write_timeout(Some(Duration::from_millis(t.connect_timeout_ms.max(1) as u64)));
    let _ = tcp.set_nodelay(true);
    match tungstenite::client_tls_with_config(request, tcp, None, None) {
        Ok((socket, _)) => Ok(Link { socket, frames: Vec::new(), closed: false }),
        Err(tungstenite::HandshakeError::Failure(tungstenite::Error::Http(resp))) => {
            let status = resp.status().as_u16();
            if status == 401 || status == 403 {
                Err(ConnError::AuthRefused(refusal_message(t, status)))
            } else {
                Err(ConnError::Connect(format!("HTTP {status}")))
            }
        }
        Err(e) => Err(ConnError::Connect(e.to_string())),
    }
}

/// `openLink`: one retry after 500 ms, so a short stall does not read as "not reachable".
fn open_link(ep: &Endpoint) -> Result<Link, ConnError> {
    let mut link = match open_link_once(ep) {
        Ok(l) => l,
        Err(ConnError::AuthRefused(m)) => return Err(ConnError::AuthRefused(m)),
        Err(_) => {
            std::thread::sleep(Duration::from_millis(500));
            open_link_once(ep)?
        }
    };
    link.set_read_timeout(Duration::from_millis(10));
    Ok(link)
}

pub struct FrameStream {
    ep: Endpoint,
    err: Box<dyn Fn(&str)>,
    link: Link,
    cursor: usize,
    /// Bumped on every reconnect: a request sent before it cannot be answered after it.
    pub generation: u64,
    instance: Option<String>,
    reconnected: bool,
}

impl FrameStream {
    pub fn open(ep: &Endpoint, err: Box<dyn Fn(&str)>) -> Result<Self, ConnError> {
        let link = open_link(ep)?;
        Ok(FrameStream { ep: ep.clone(), err, link, cursor: 0, generation: 0, instance: None, reconnected: false })
    }

    pub fn answer_timeout_ms(&self) -> u64 {
        self.ep.target.answer_timeout_ms.max(0) as u64
    }

    pub fn target(&self) -> &Target {
        &self.ep.target
    }

    fn next_available(&mut self) -> Option<Envelope> {
        let f = self.link.frames.get(self.cursor)?.clone();
        self.cursor += 1;
        if f.kind == "ready" {
            if let Some(i) = f.ready_field("instance") {
                self.instance = Some(i);
            }
        }
        Some(f)
    }

    /// The next frame not yet consumed, or None within [timeout_ms] (or right after a reconnect).
    pub fn next(&mut self, timeout_ms: u64) -> Result<Option<Envelope>, ConnError> {
        let deadline = Instant::now() + Duration::from_millis(timeout_ms);
        loop {
            if let Some(f) = self.next_available() {
                return Ok(Some(f));
            }
            if self.link.closed {
                self.reconnect()?;
                return Ok(None);
            }
            if Instant::now() >= deadline {
                return Ok(None);
            }
            self.link.pump();
        }
    }

    /// True once after a reconnect happened (`takeReconnected`).
    pub fn take_reconnected(&mut self) -> bool {
        std::mem::replace(&mut self.reconnected, false)
    }

    /// The first not-yet-consumed frame of [kind], without consuming it.
    pub fn peek(&self, kind: &str) -> Option<&Envelope> {
        self.link.frames[self.cursor.min(self.link.frames.len())..].iter().find(|f| f.kind == kind)
    }

    /// Waits for the next frame of [kind] (frames of other types are consumed and dropped).
    pub fn await_type(&mut self, kind: &str, timeout_ms: u64) -> Result<Envelope, ConnError> {
        let deadline = Instant::now() + Duration::from_millis(timeout_ms);
        while Instant::now() < deadline {
            let left = deadline.saturating_duration_since(Instant::now()).as_millis() as u64;
            if let Some(f) = self.next(left.min(POLL_WINDOW_MS))? {
                if f.kind == kind {
                    return Ok(f);
                }
            }
        }
        let seen: Vec<&str> = self.link.frames.iter().map(|f| f.kind.as_str()).collect();
        Err(ConnError::Failed(format!("timed out after {timeout_ms}ms waiting for '{kind}'; saw [{}]", seen.join(", "))))
    }

    fn send_text(&mut self, text: &str) -> Result<(), String> {
        self.link.socket.send(Message::Text(text.to_string())).map_err(|e| e.to_string())
    }

    /// Sends one frame, re-making the connection first when it has died.
    pub fn send(&mut self, env: &Envelope) -> Result<(), ConnError> {
        if self.link.closed {
            self.reconnect()?;
        }
        let text = env.encode();
        if self.send_text(&text).is_err() {
            self.reconnect()?;
            self.send_text(&text).map_err(|e| ConnError::Failed(format!("could not send to the daemon: {e}")))?;
        }
        Ok(())
    }

    /// The `contract=` of the current link's `ready` (not consumed), waiting up to [READY_WAIT_MS].
    pub fn ready_contract(&mut self) -> Option<String> {
        let deadline = Instant::now() + Duration::from_millis(READY_WAIT_MS);
        while Instant::now() < deadline {
            if let Some(r) = self.link.frames.iter().find(|f| f.kind == "ready") {
                return r.ready_field("contract").map(|c| c.trim_start_matches("infantry/").to_string());
            }
            if self.link.closed {
                break;
            }
            self.link.pump();
        }
        None
    }

    /// The current link's whole `ready` data (not consumed), waiting as [ready_contract] does.
    pub fn ready_data(&mut self) -> Option<String> {
        self.ready_contract();
        self.link.frames.iter().find(|f| f.kind == "ready").map(|r| r.data.clone())
    }

    fn correlate(&mut self, env: &Envelope) -> Envelope {
        if !CORRELATED_REQUESTS.contains(&env.kind.as_str()) || env.request_id.is_some() {
            return env.clone();
        }
        match self.ready_contract() {
            Some(c) if contract_at_least(&c, REQUEST_ID_CONTRACT) => {
                let mut e = env.clone();
                e.request_id = Some(uuid::Uuid::new_v4().to_string());
                e
            }
            _ => env.clone(),
        }
    }

    /// `request`: send [env] and wait for the [answer] frame answering it. [idempotent]: may be re-sent after a drop.
    pub fn request(&mut self, env: &Envelope, answer: &str, what: &str, idempotent: bool) -> Result<Envelope, ConnError> {
        let t = self.answer_timeout_ms();
        self.request_within(env, answer, what, idempotent, t)
    }

    pub fn request_within(&mut self, env: &Envelope, answer: &str, what: &str, idempotent: bool, timeout_ms: u64) -> Result<Envelope, ConnError> {
        self.request_matching(env, Some(answer), what, idempotent, timeout_ms)
    }

    /// Like `request_within`, but a frame of ANY type that carries this request's id answers it (the answer type is the
    /// runner's own unit's, e.g. `providers`). Needs a daemon that echoes request ids.
    pub fn request_any_type(&mut self, env: &Envelope, what: &str, timeout_ms: u64) -> Result<Envelope, ConnError> {
        self.request_matching(env, None, what, false, timeout_ms)
    }

    fn request_matching(&mut self, env: &Envelope, answer: Option<&str>, what: &str, idempotent: bool, timeout_ms: u64) -> Result<Envelope, ConnError> {
        let mut sent = self.correlate(env);
        self.send(&sent)?;
        let mut sent_at = self.generation;
        let mut last_error: Option<String> = None;
        let deadline = Instant::now() + Duration::from_millis(timeout_ms);
        while Instant::now() < deadline {
            if self.generation != sent_at {
                if !idempotent {
                    return Err(ConnError::Failed(format!(
                        "the connection dropped before the daemon answered {what}, so it is unknown whether it was applied. The contract has no pod listing to check against: do not re-send unless a duplicate is acceptable"
                    )));
                }
                sent = self.correlate(env);
                self.send(&sent)?;
                sent_at = self.generation;
                last_error = None;
            }
            let frame = self.next(if last_error.is_none() { POLL_WINDOW_MS } else { ERROR_GRACE_MS })?;
            let Some(frame) = frame else {
                if self.generation == sent_at {
                    if let Some(e) = &last_error {
                        return Err(ConnError::Failed(if e.trim().is_empty() { format!("{what} failed") } else { e.clone() }));
                    }
                }
                continue;
            };
            let id = sent.request_id.as_ref();
            let typed = answer.is_some_and(|a| frame.kind == a);
            let by_id = answer.is_none() && id.is_some() && frame.request_id.as_ref() == id && frame.kind != "error";
            if typed || by_id {
                if id.is_none() || frame.request_id.as_ref() == id {
                    return Ok(frame);
                }
            } else if frame.kind == "error" && (id.is_none() || frame.request_id.is_none() || frame.request_id.as_ref() == id) {
                last_error = Some(frame.data);
            }
        }
        Err(ConnError::Failed(last_error.unwrap_or_else(|| format!("daemon at {} did not answer {what} within {timeout_ms}ms", self.ep.target.url))))
    }

    /// `requestLastOf`: send [env] then [fence]; the LAST frame of [answers] before the fence's [fence_type] answer.
    /// Never re-sent after a drop: the drop is reported with [drop_warning].
    pub fn request_last_of(
        &mut self,
        env: &Envelope,
        answers: &[&str],
        fence: &Envelope,
        fence_type: &str,
        what: &str,
        drop_warning: &str,
    ) -> Result<Envelope, ConnError> {
        self.send(env)?;
        self.send(fence)?;
        let sent_at = self.generation;
        let mut last: Option<Envelope> = None;
        let mut last_error: Option<String> = None;
        let timeout = self.answer_timeout_ms();
        let deadline = Instant::now() + Duration::from_millis(timeout);
        let url = self.ep.target.url.clone();
        let none = |e: &Option<String>| ConnError::Failed(e.clone().unwrap_or_else(|| format!("daemon at {url} did not answer {what} within {timeout}ms")));
        while Instant::now() < deadline {
            if self.generation != sent_at {
                return Err(ConnError::Failed(format!(
                    "the connection dropped before the daemon answered {what}, so it is unknown whether it was applied. {drop_warning}"
                )));
            }
            let frame = self.next(if last_error.is_none() { POLL_WINDOW_MS } else { ERROR_GRACE_MS })?;
            let Some(frame) = frame else {
                if self.generation == sent_at {
                    if let Some(e) = &last_error {
                        return Err(ConnError::Failed(if e.trim().is_empty() { format!("{what} failed") } else { e.clone() }));
                    }
                }
                continue;
            };
            if frame.kind == fence_type {
                return last.ok_or_else(|| none(&last_error));
            } else if answers.contains(&frame.kind.as_str()) {
                last = Some(frame);
            } else if frame.kind == "error" {
                last_error = Some(frame.data);
            }
        }
        Err(none(&last_error))
    }

    fn await_ready_instance(link: &mut Link) -> Option<String> {
        let deadline = Instant::now() + Duration::from_millis(READY_WAIT_MS);
        while Instant::now() < deadline {
            if let Some(r) = link.frames.iter().find(|f| f.kind == "ready") {
                return r.ready_field("instance");
            }
            if link.closed {
                break;
            }
            link.pump();
        }
        None
    }

    fn reconnect(&mut self) -> Result<(), ConnError> {
        (self.err)(&format!("[client] connection to {} lost; reconnecting", self.ep.target.url));
        for (attempt, wait) in RECONNECT_BACKOFF_MS.iter().enumerate() {
            std::thread::sleep(Duration::from_millis(*wait));
            match open_link(&self.ep) {
                Ok(mut replacement) => {
                    let instance = Self::await_ready_instance(&mut replacement);
                    let restarted = matches!((&self.instance, &instance), (Some(a), Some(b)) if a != b);
                    self.link = replacement;
                    self.cursor = 0;
                    self.generation += 1;
                    self.reconnected = true;
                    (self.err)("[client] reconnected");
                    if restarted {
                        let short: String = instance.as_deref().unwrap_or("").chars().take(8).collect();
                        (self.err)(&format!("[client] the daemon is a new instance ({short}); replaying tracked sessions from the start"));
                    }
                    return Ok(());
                }
                Err(ConnError::AuthRefused(m)) => return Err(ConnError::AuthRefused(m)),
                Err(_) => (self.err)(&format!(
                    "[client] reconnect attempt {}/{} failed (ConnectException)",
                    attempt + 1,
                    RECONNECT_BACKOFF_MS.len()
                )),
            }
        }
        Err(ConnError::Failed(format!(
            "lost the connection to {} and could not re-establish it after {} attempts",
            self.ep.target.url,
            RECONNECT_BACKOFF_MS.len()
        )))
    }

    /// A normal close ("bye"), best effort.
    pub fn close(mut self) {
        let _ = self.link.socket.close(Some(CloseFrame { code: CloseCode::Normal, reason: "bye".into() }));
        let _ = self.link.socket.flush();
    }
}

/// `daemonReady`: the daemon's `ready` within [timeout_ms], or None (down, refused, silent).
pub fn daemon_ready(ep: &Endpoint, timeout_ms: u64) -> Option<Envelope> {
    let mut link = open_link_once(ep).ok()?;
    link.set_read_timeout(Duration::from_millis(10));
    let deadline = Instant::now() + Duration::from_millis(timeout_ms);
    while Instant::now() < deadline {
        if let Some(r) = link.frames.iter().find(|f| f.kind == "ready") {
            let r = r.clone();
            let _ = link.socket.close(None);
            return Some(r);
        }
        if link.closed {
            break;
        }
        link.pump();
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn encode_like_kotlin() {
        assert_eq!(Envelope::new("board", "{}").encode(), r#"{"type":"board","data":"{}","sessionId":null,"sequence":null}"#);
        let mut e = Envelope::new("ticket_get", "x");
        e.request_id = Some("r".into());
        assert!(e.encode().ends_with(r#""sequence":null,"requestId":"r"}"#));
    }

    #[test]
    fn contracts() {
        assert!(contract_at_least("1.157.0", "1.87.0"));
        assert!(contract_at_least("infantry/1.87", "1.87.0"));
        assert!(!contract_at_least("1.86.9", "1.87.0"));
        assert!(!contract_at_least("x", "1.87.0"));
    }
}
