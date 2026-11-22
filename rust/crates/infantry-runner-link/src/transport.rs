// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The link transport: a WebSocket at [`wire::PATH`], one request frame and one answer frame per call (the caller's id
//! and credential in the upgrade headers). [`call`] is the orchestrator end, [`serve`] the runner end. Blocking I/O, one
//! thread per connection: a link carries a handful of calls a second, and a held `frames_pull` just parks its thread.
//!
//! `wss://` is the link: TLS with the runner's pinned self-signed certificate ([`crate::tls`]). Plain `ws://` is served and
//! called only when `runners.allowPlainTailnet` is on (the caller passes [`Security::allow_plain`]; it defaults to false).

use crate::wire::{self, Answer, LinkEnvelope};
use crate::tls;
use std::io::{ErrorKind, Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream, ToSocketAddrs};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::time::Duration;
use tungstenite::handshake::server::{ErrorResponse, Request, Response};
use tungstenite::http::StatusCode;
use tungstenite::{Message, WebSocket};

/// A link call that got no answer.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LinkFailure(pub String);

impl std::fmt::Display for LinkFailure {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for LinkFailure {}

/// `ws://host:port[/path]` -> (host, port, path); the link path is added when absent.
pub fn endpoint(url: &str) -> Result<(String, u16, String), LinkFailure> {
    let url = url.trim();
    let (scheme, rest) = url.split_once("://").ok_or_else(|| LinkFailure(format!("the runner URL must start with ws:// or wss:// (got '{url}')")))?;
    match scheme.to_ascii_lowercase().as_str() {
        "ws" | "wss" => {}
        _ => return Err(LinkFailure(format!("the runner URL must start with ws:// or wss:// (got '{url}')"))),
    }
    let (authority, path) = match rest.find('/') {
        Some(i) => (&rest[..i], &rest[i..]),
        None => (rest, ""),
    };
    let (host, port) = if let Some(stripped) = authority.strip_prefix('[') {
        let (h, p) = stripped.split_once(']').ok_or_else(|| LinkFailure(format!("not a URL: {url}")))?;
        (format!("[{h}]"), p.strip_prefix(':').unwrap_or(""))
    } else {
        match authority.rsplit_once(':') {
            Some((h, p)) => (h.to_string(), p),
            None => (authority.to_string(), ""),
        }
    };
    if host.is_empty() || host == "[]" {
        return Err(LinkFailure(format!("the runner URL names no host: {url}")));
    }
    let port = if port.is_empty() { 80 } else { port.parse().map_err(|_| LinkFailure(format!("the runner URL's port is not a port number: {url}")))? };
    let path = if path.is_empty() || path == "/" { wire::PATH.to_string() } else { path.to_string() };
    Ok((host, port, path))
}

/// True for a `wss://` URL.
pub fn is_tls(url: &str) -> bool {
    url.trim().get(..6).is_some_and(|p| p.eq_ignore_ascii_case("wss://"))
}

/// The reason a plain `ws://` link is refused while `runners.allowPlainTailnet` is off.
pub const PLAIN_REFUSED: &str = "plain ws:// is refused: runners.allowPlainTailnet is off. A plain link sends the runner's credential and every command and its output unencrypted, so anyone on the path can read them or take the runner over. Use wss:// (the default), or turn the setting on only for a private network you trust, on both the runner and the orchestrator";

/// How a call is secured: the certificate pin for `wss://`, and whether plain `ws://` is allowed.
#[derive(Debug, Clone, Default)]
pub struct Security {
    /// The runner certificate's SHA-256 (lower-case hex), from the bearer. Required for `wss://`.
    pub pin: Option<String>,
    /// `runners.allowPlainTailnet`: false (the default) refuses `ws://`.
    pub allow_plain: bool,
}

/// What one call put on the wire and got back, verbatim (fixtures record these).
#[derive(Debug, Clone)]
pub struct Exchange {
    pub sent: String,
    pub received: String,
    pub answer: Answer,
}

/// One orchestrator -> runner call: connect, send `(type, data)` with the id and credential headers, read the answer, close.
pub fn call(
    url: &str,
    orchestrator_id: Option<&str>,
    credential: Option<&str>,
    r#type: &str,
    data: &str,
    timeout: Duration,
    security: &Security,
) -> Result<Exchange, LinkFailure> {
    let (host, port, path) = endpoint(url)?;
    let tls_link = is_tls(url);
    if !tls_link && !security.allow_plain {
        return Err(LinkFailure(PLAIN_REFUSED.into()));
    }
    let pin = security.pin.as_deref().map(str::trim).filter(|p| !p.is_empty());
    if tls_link && pin.is_none() {
        return Err(LinkFailure("wss:// needs the runner's certificate pin (the middle part of its bearer); this runner has none, so nothing was sent. Add it again with its bearer".into()));
    }
    let bare = host.trim_start_matches('[').trim_end_matches(']');
    let addrs: Vec<SocketAddr> =
        (bare, port).to_socket_addrs().map_err(|e| LinkFailure(format!("cannot reach the runner link at {url}: {e}")))?.collect();
    let mut last = None;
    let mut stream = None;
    for a in addrs {
        match TcpStream::connect_timeout(&a, timeout) {
            Ok(s) => {
                stream = Some(s);
                break;
            }
            Err(e) => last = Some(e),
        }
    }
    let stream = stream.ok_or_else(|| LinkFailure(format!("cannot reach the runner link at {url}: {}", last.map(|e| e.to_string()).unwrap_or_else(|| "no address".into()))))?;
    stream.set_read_timeout(Some(timeout)).ok();
    stream.set_write_timeout(Some(timeout)).ok();
    stream.set_nodelay(true).ok();
    if tls_link {
        // The pin is checked inside the handshake: a wrong certificate fails here, before any request bytes exist.
        let secured = tls::connect(stream, pin.unwrap_or_default()).map_err(LinkFailure)?;
        return exchange(secured, url, &host, port, &path, orchestrator_id, credential, r#type, data, timeout);
    }
    exchange(stream, url, &host, port, &path, orchestrator_id, credential, r#type, data, timeout)
}

#[allow(clippy::too_many_arguments)]
fn exchange<S: Read + Write>(
    stream: S,
    url: &str,
    host: &str,
    port: u16,
    path: &str,
    orchestrator_id: Option<&str>,
    credential: Option<&str>,
    r#type: &str,
    data: &str,
    timeout: Duration,
) -> Result<Exchange, LinkFailure> {
    let mut builder = Request::builder()
        .uri(format!("ws://{host}:{port}{path}"))
        .header("Host", format!("{host}:{port}"))
        .header("Connection", "Upgrade")
        .header("Upgrade", "websocket")
        .header("Sec-WebSocket-Version", "13")
        .header("Sec-WebSocket-Key", tungstenite::handshake::client::generate_key());
    if let Some(id) = orchestrator_id.filter(|s| !s.is_empty()) {
        builder = builder.header(wire::ORCHESTRATOR_HEADER, id);
    }
    if let Some(c) = credential.filter(|s| !s.is_empty()) {
        builder = builder.header(wire::CREDENTIAL_HEADER, c);
    }
    let request = builder.body(()).map_err(|e| LinkFailure(format!("bad link request: {e}")))?;
    let (mut ws, _) = tungstenite::client(request, stream).map_err(|e| LinkFailure(format!("cannot reach the runner link at {url}: {e}")))?;
    let sent = serde_json::to_string(&LinkEnvelope {
        r#type: r#type.into(),
        data: data.into(),
        session_id: None,
        request_id: Some(uuid::Uuid::new_v4().to_string()),
    })
    .expect("envelope");
    let result = (|| {
        ws.send(Message::text(sent.clone())).map_err(|e| LinkFailure(format!("the runner link at {url} gave no answer: {e}")))?;
        loop {
            match ws.read() {
                Ok(Message::Text(t)) => return Ok(t.to_string()),
                Ok(Message::Close(c)) => {
                    return Err(LinkFailure(format!("the runner closed the link ({})", c.map(|c| format!("{} {}", u16::from(c.code), c.reason)).unwrap_or_default())))
                }
                Ok(_) => continue,
                Err(tungstenite::Error::Io(e)) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                    return Err(LinkFailure(format!("the runner link at {url} did not answer within {}s", timeout.as_secs())))
                }
                Err(e) => return Err(LinkFailure(format!("the runner link at {url} gave no answer: {e}"))),
            }
        }
    })();
    let _ = ws.close(None);
    let _ = ws.flush();
    let received = result?;
    let env: LinkEnvelope = serde_json::from_str(&received).map_err(|e| LinkFailure(format!("the runner link at {url} gave no answer: {e}")))?;
    Ok(Exchange { sent, received, answer: Answer { r#type: env.r#type, data: env.data } })
}

/// The runner's handler: one request in, one answer out.
pub trait LinkHandler: Send + Sync + 'static {
    fn handle(&self, r#type: &str, data: &str, orchestrator_id: Option<&str>, credential: Option<&str>, from: Option<&str>) -> Answer;

    /// The answer to a frame that is not a request envelope.
    fn malformed(&self) -> Answer {
        Answer::error("malformed runner link frame")
    }
}

/// A served link: [`LinkServing::port`] is where it listens; dropping (or [`LinkServing::close`]) stops accepting.
pub struct LinkServing {
    pub port: u16,
    stop: Arc<AtomicBool>,
    addr: SocketAddr,
}

impl LinkServing {
    pub fn close(&self) {
        if !self.stop.swap(true, Ordering::SeqCst) {
            let _ = TcpStream::connect_timeout(&self.addr, Duration::from_millis(200)); // wake the accept loop
        }
    }
}

impl Drop for LinkServing {
    fn drop(&mut self) {
        self.close();
    }
}

/// Serves the runner end on `host:port` (0 = any free port) until closed: `wss` with `tls`, plain `ws` with None (the
/// caller serves plain only under `runners.allowPlainTailnet`).
pub fn serve(handler: Arc<dyn LinkHandler>, host: &str, port: u16, tls: Option<Arc<rustls::ServerConfig>>) -> std::io::Result<LinkServing> {
    let listener = TcpListener::bind((host, port))?;
    let local = listener.local_addr()?;
    let wake = if local.ip().is_unspecified() { SocketAddr::new([127, 0, 0, 1].into(), local.port()) } else { local };
    let stop = Arc::new(AtomicBool::new(false));
    let stop2 = stop.clone();
    std::thread::Builder::new().name("runner-link-accept".into()).spawn(move || {
        for conn in listener.incoming() {
            if stop2.load(Ordering::SeqCst) {
                break;
            }
            let Ok(stream) = conn else { continue };
            let h = handler.clone();
            let tls = tls.clone();
            let _ = std::thread::Builder::new().name("runner-link-conn".into()).spawn(move || {
                let from = stream.peer_addr().ok().map(|a| a.ip().to_string());
                stream.set_nodelay(true).ok();
                match tls {
                    Some(cfg) => {
                        if let Ok(secured) = tls::accept(&cfg, stream) {
                            connection(h, secured, from);
                        }
                    }
                    None => connection(h, stream, from),
                }
            });
        }
    })?;
    Ok(LinkServing { port: local.port(), stop, addr: wake })
}

fn connection<S: Read + Write>(handler: Arc<dyn LinkHandler>, stream: S, from: Option<String>) {
    let mut orchestrator: Option<String> = None;
    let mut credential: Option<String> = None;
    let callback = |req: &Request, resp: Response| -> Result<Response, ErrorResponse> {
        if req.uri().path() != wire::PATH {
            let mut r = ErrorResponse::new(Some("not found".into()));
            *r.status_mut() = StatusCode::NOT_FOUND;
            return Err(r);
        }
        let header = |name: &str| req.headers().get(name).and_then(|v| v.to_str().ok()).map(str::to_string);
        orchestrator = header(wire::ORCHESTRATOR_HEADER);
        credential = header(wire::CREDENTIAL_HEADER);
        Ok(resp)
    };
    let Ok(mut ws) = tungstenite::accept_hdr(stream, callback) else { return };
    serve_frames(&*handler, &mut ws, orchestrator.as_deref(), credential.as_deref(), from.as_deref());
}

fn serve_frames<S: Read + Write>(handler: &dyn LinkHandler, ws: &mut WebSocket<S>, orchestrator: Option<&str>, credential: Option<&str>, from: Option<&str>) {
    loop {
        let text = match ws.read() {
            Ok(Message::Text(t)) => t.to_string(),
            Ok(Message::Close(_)) | Err(_) => break,
            Ok(_) => continue,
        };
        let request: Option<LinkEnvelope> = serde_json::from_str(&text).ok();
        let answer = match &request {
            Some(r) => handler.handle(&r.r#type, &r.data, orchestrator, credential, from),
            None => handler.malformed(),
        };
        let out = LinkEnvelope { r#type: answer.r#type, data: answer.data, session_id: None, request_id: request.and_then(|r| r.request_id) };
        if ws.send(Message::text(serde_json::to_string(&out).expect("envelope"))).is_err() {
            break;
        }
    }
    let _ = ws.close(None);
    let _ = ws.flush();
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn endpoints() {
        assert_eq!(endpoint("ws://127.0.0.1:18982").unwrap(), ("127.0.0.1".into(), 18982, "/runner-link".into()));
        assert_eq!(endpoint("ws://h:1/x").unwrap(), ("h".into(), 1, "/x".into()));
        assert_eq!(endpoint("ws://[::1]:9").unwrap(), ("[::1]".into(), 9, "/runner-link".into()));
        assert_eq!(endpoint("wss://h:1").unwrap(), ("h".into(), 1, "/runner-link".into()));
        assert!(is_tls("WSS://h:1") && !is_tls("ws://h:1"));
        assert!(endpoint("http://h").is_err());
        assert!(endpoint("ws://:5").is_err());
    }
}
