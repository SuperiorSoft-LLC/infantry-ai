// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The daemon connection: a port of the parts of the Kotlin `FrameStream` the MCP bridge uses (open with the bearer and
//! session headers, one retry on a stall, `request` with the error grace window, reconnect with backoff, and the refusal
//! of a non-idempotent re-send). Blocking I/O on one socket per call: the bridge opens one stream per tool call, so calls run side by side.

use crate::target::Target;
use serde_json::{Map, Value};
use std::io::Write;
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

/// A failure, in the words the Kotlin bridge uses.
#[derive(Debug)]
pub enum FrameError {
    /// The daemon refused the credential (HTTP 401 / 403). Never retried.
    AuthRefused(String),
    /// Nothing answered the connect (the caller reports the daemon unreachable).
    Connect(String),
    /// Any other failure: the message is what `e.message` was in Kotlin.
    Failed(String),
}

impl FrameError {
    pub fn message(&self) -> &str {
        match self {
            FrameError::AuthRefused(m) | FrameError::Connect(m) | FrameError::Failed(m) => m,
        }
    }
}

/// One frame (`Envelope`): only the fields the bridge reads.
#[derive(Clone, Debug)]
pub struct Envelope {
    pub kind: String,
    pub data: String,
}

impl Envelope {
    /// The Kotlin CLI's encoding (`encodeDefaults = true`): `sessionId` / `sequence` written as null, `requestId` omitted.
    pub fn encode(kind: &str, data: &str) -> String {
        let mut m = Map::new();
        m.insert("type".into(), Value::String(kind.into()));
        m.insert("data".into(), Value::String(data.into()));
        m.insert("sessionId".into(), Value::Null);
        m.insert("sequence".into(), Value::Null);
        serde_json::to_string(&Value::Object(m)).unwrap_or_default()
    }

    /// Decodes one text frame as kotlinx would, or None (kotlinx drops a frame it cannot decode).
    pub fn decode(text: &str) -> Option<Envelope> {
        let v: Value = serde_json::from_str(text).ok()?;
        let o = v.as_object()?;
        let kind = o.get("type")?.as_str()?.to_string();
        let data = match o.get("data") {
            None => String::new(),
            Some(Value::String(s)) => s.clone(),
            Some(_) => return None,
        };
        match o.get("sessionId") {
            None | Some(Value::Null) | Some(Value::String(_)) => {}
            Some(_) => return None,
        }
        match o.get("sequence") {
            None | Some(Value::Null) => {}
            Some(Value::Number(n)) if n.as_i64().is_some() => {}
            Some(_) => return None,
        }
        match o.get("requestId") {
            None | Some(Value::Null) | Some(Value::String(_)) => {}
            Some(_) => return None,
        }
        Some(Envelope { kind, data })
    }

    fn ready_field(&self, key: &str) -> Option<String> {
        self.data.split(' ').find_map(|f| f.strip_prefix(&format!("{key}=")).map(str::to_string))
    }
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

    /// Reads at most one message within the socket's read timeout; records it. False when nothing came.
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
            Err(tungstenite::Error::Io(e))
                if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) =>
            {
                false
            }
            Err(_) => {
                self.closed = true;
                false
            }
        }
    }
}

fn refusal_message(target: &Target, status: u16) -> String {
    let why = if target.token.is_none() {
        "no token was sent: pass --token-file, --endpoint NAME, or set INFANTRY_TOKEN_FILE".to_string()
    } else {
        format!("the token from {} was not accepted: it must match the daemon's", target.token_source)
    };
    format!("the daemon at {} refused this client (HTTP {status}): {why}", target.url)
}

fn open_link_once(target: &Target) -> Result<Link, FrameError> {
    let mut request = target
        .url
        .as_str()
        .into_client_request()
        .map_err(|e| FrameError::Connect(e.to_string()))?;
    {
        let h = request.headers_mut();
        if let Some(t) = &target.token {
            if let Ok(v) = format!("Bearer {t}").parse() {
                h.insert("Authorization", v);
            }
        }
        if let Some(s) = &target.session_id {
            if let Ok(v) = s.parse() {
                h.insert(SESSION_HEADER, v);
            }
        }
    }
    let uri = request.uri().clone();
    let host = uri.host().unwrap_or("").trim_start_matches('[').trim_end_matches(']').to_string();
    let port = uri.port_u16().unwrap_or(if uri.scheme_str() == Some("wss") { 443 } else { 80 });
    let addrs: Vec<_> = (host.as_str(), port)
        .to_socket_addrs()
        .map_err(|e| FrameError::Connect(e.to_string()))?
        .collect();
    let mut last = None;
    let mut tcp = None;
    for a in addrs {
        match TcpStream::connect_timeout(&a, Duration::from_millis(target.connect_timeout_ms.max(1) as u64)) {
            Ok(s) => {
                tcp = Some(s);
                break;
            }
            Err(e) => last = Some(e),
        }
    }
    let tcp = tcp.ok_or_else(|| FrameError::Connect(last.map_or("no address".into(), |e| e.to_string())))?;
    let _ = tcp.set_read_timeout(Some(Duration::from_millis(target.connect_timeout_ms.max(1) as u64)));
    let _ = tcp.set_write_timeout(Some(Duration::from_millis(target.connect_timeout_ms.max(1) as u64)));
    let _ = tcp.set_nodelay(true);
    match tungstenite::client_tls_with_config(request, tcp, None, None) {
        Ok((socket, _)) => Ok(Link { socket, frames: Vec::new(), closed: false }),
        Err(tungstenite::HandshakeError::Failure(tungstenite::Error::Http(resp))) => {
            let status = resp.status().as_u16();
            if status == 401 || status == 403 {
                Err(FrameError::AuthRefused(refusal_message(target, status)))
            } else {
                Err(FrameError::Connect(format!("HTTP {status}")))
            }
        }
        Err(e) => Err(FrameError::Connect(e.to_string())),
    }
}

/// `openLink`: a short stall must not read as "not reachable": retry once after 500 ms.
fn open_link(target: &Target) -> Result<Link, FrameError> {
    let mut link = match open_link_once(target) {
        Ok(l) => l,
        Err(FrameError::AuthRefused(m)) => return Err(FrameError::AuthRefused(m)),
        Err(_) => {
            std::thread::sleep(Duration::from_millis(500));
            open_link_once(target)?
        }
    };
    link.set_read_timeout(Duration::from_millis(10));
    Ok(link)
}

pub struct FrameStream {
    target: Target,
    err: fn(&str),
    link: Link,
    cursor: usize,
    generation: u64,
    instance: Option<String>,
}

impl FrameStream {
    /// Connects to [target]. `err` writes one line to stderr.
    pub fn open(target: &Target, err: fn(&str)) -> Result<Self, FrameError> {
        let link = open_link(target)?;
        Ok(FrameStream { target: target.clone(), err, link, cursor: 0, generation: 0, instance: None })
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

    /// The next frame not yet consumed, or None within [timeout_ms] (or after a reconnect).
    fn next(&mut self, timeout_ms: u64) -> Result<Option<Envelope>, FrameError> {
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

    fn send_text(&mut self, text: &str) -> Result<(), String> {
        self.link.socket.send(Message::Text(text.to_string())).map_err(|e| e.to_string())
    }

    fn send(&mut self, text: &str) -> Result<(), FrameError> {
        if self.link.closed {
            self.reconnect()?;
        }
        if self.send_text(text).is_err() {
            self.reconnect()?;
            self.send_text(text)
                .map_err(|e| FrameError::Failed(format!("could not send to the daemon: {e}")))?;
        }
        Ok(())
    }

    /// `FrameStream.request` for a frame that carries no request id: send, then wait for [answer] (an `error` is kept and
    /// reported when no answer follows within the grace window).
    pub fn request(
        &mut self,
        kind: &str,
        data: &str,
        answer: &str,
        what: &str,
        idempotent: bool,
        timeout_ms: i64,
    ) -> Result<Envelope, FrameError> {
        let text = Envelope::encode(kind, data);
        self.send(&text)?;
        let mut sent_at = self.generation;
        let mut last_error: Option<String> = None;
        let timeout_ms = timeout_ms.max(0) as u64;
        let deadline = Instant::now() + Duration::from_millis(timeout_ms);
        while Instant::now() < deadline {
            if self.generation != sent_at {
                if !idempotent {
                    return Err(FrameError::Failed(format!(
                        "the connection dropped before the daemon answered {what}, so it is unknown whether it was applied. \
                         The contract has no pod listing to check against: do not re-send unless a duplicate is acceptable"
                    )));
                }
                self.send(&text)?;
                sent_at = self.generation;
                last_error = None;
            }
            let frame = self.next(if last_error.is_none() { POLL_WINDOW_MS } else { ERROR_GRACE_MS })?;
            let Some(frame) = frame else {
                if self.generation == sent_at {
                    if let Some(e) = &last_error {
                        return Err(FrameError::Failed(if e.trim().is_empty() { format!("{what} failed") } else { e.clone() }));
                    }
                }
                continue;
            };
            if frame.kind == answer {
                return Ok(frame);
            }
            if frame.kind == "error" {
                last_error = Some(frame.data);
            }
        }
        Err(FrameError::Failed(last_error.unwrap_or_else(|| format!("daemon at {} did not answer {what} within {timeout_ms}ms", self.target.url))))
    }

    /// Waits up to [READY_WAIT_MS] for [link]'s `ready` and returns its `instance=`, reading frames into the link.
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

    fn reconnect(&mut self) -> Result<(), FrameError> {
        (self.err)(&format!("[client] connection to {} lost; reconnecting", self.target.url));
        for (attempt, wait) in RECONNECT_BACKOFF_MS.iter().enumerate() {
            std::thread::sleep(Duration::from_millis(*wait));
            match open_link(&self.target) {
                Ok(mut replacement) => {
                    let instance = Self::await_ready_instance(&mut replacement);
                    let restarted = matches!((&self.instance, &instance), (Some(a), Some(b)) if a != b);
                    self.link = replacement;
                    self.cursor = 0;
                    self.generation += 1;
                    (self.err)("[client] reconnected");
                    if restarted {
                        let short: String = instance.as_deref().unwrap_or("").chars().take(8).collect();
                        (self.err)(&format!(
                            "[client] the daemon is a new instance ({short}); replaying tracked sessions from the start"
                        ));
                    }
                    return Ok(());
                }
                Err(FrameError::AuthRefused(m)) => return Err(FrameError::AuthRefused(m)),
                Err(_) => (self.err)(&format!(
                    "[client] reconnect attempt {}/{} failed (ConnectException)",
                    attempt + 1,
                    RECONNECT_BACKOFF_MS.len()
                )),
            }
        }
        Err(FrameError::Failed(format!(
            "lost the connection to {} and could not re-establish it after {} attempts",
            self.target.url,
            RECONNECT_BACKOFF_MS.len()
        )))
    }

    /// Sends a normal close ("bye"), best effort.
    /// A clone of the plain TCP socket under this stream (None over TLS): shutting it down ends a pending request.
    pub fn abort_handle(&self) -> Option<std::net::TcpStream> {
        match self.link.socket.get_ref() {
            MaybeTlsStream::Plain(s) => s.try_clone().ok(),
            _ => None,
        }
    }

    pub fn close(mut self) {
        let _ = self.link.socket.close(Some(CloseFrame { code: CloseCode::Normal, reason: "bye".into() }));
        let _ = self.link.socket.flush();
        if let MaybeTlsStream::Plain(s) = self.link.socket.get_mut() {
            let _ = s.flush();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn envelope_encoding_matches_kotlin() {
        assert_eq!(
            Envelope::encode("plugin_tools", ""),
            r#"{"type":"plugin_tools","data":"","sessionId":null,"sequence":null}"#
        );
    }

    #[test]
    fn decode_drops_what_kotlinx_drops() {
        assert!(Envelope::decode(r#"{"type":"x","data":{}}"#).is_none());
        assert!(Envelope::decode(r#"{"data":"x"}"#).is_none());
        let e = Envelope::decode(r#"{"type":"ready","data":"contract=1 instance=abc","extra":1}"#).unwrap();
        assert_eq!(e.ready_field("instance").as_deref(), Some("abc"));
    }
}
