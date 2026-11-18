// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! A blocking `/ws` client: handshake (optional bearer), send, and collect frames with deadlines.
use infantry_protocol::Envelope;
use std::net::{TcpStream, ToSocketAddrs};
use std::time::{Duration, Instant};
use tungstenite::client::IntoClientRequest;
use tungstenite::{Message, WebSocket};

pub struct Client {
    ws: WebSocket<TcpStream>,
    /// Every frame received, in order (the raw transcript the recorder normalises).
    pub seen: Vec<Envelope>,
    cursor: usize,
    /// Envelope round-trip mismatches (wire JSON vs `infantry-protocol` re-encoding), one line each.
    pub drift: Vec<String>,
}

impl Client {
    /// `url` is `ws://host:port/ws` (or `http://host:port`, or `host:port`). `token` goes as `Authorization: Bearer`.
    pub fn connect(url: &str, token: Option<&str>) -> Result<Client, String> {
        let url = ws_url(url);
        let mut req = url.as_str().into_client_request().map_err(|e| e.to_string())?;
        if let Some(t) = token {
            req.headers_mut().insert("Authorization", format!("Bearer {t}").parse().map_err(|_| "bad token")?);
        }
        let uri = req.uri().clone();
        let host = uri.host().ok_or("no host")?.trim_start_matches('[').trim_end_matches(']').to_string();
        let port = uri.port_u16().unwrap_or(80);
        let addr = (host.as_str(), port).to_socket_addrs().map_err(|e| e.to_string())?.next().ok_or("no address")?;
        let tcp = TcpStream::connect_timeout(&addr, Duration::from_secs(10)).map_err(|e| format!("connect: {e}"))?;
        let _ = tcp.set_nodelay(true);
        let _ = tcp.set_read_timeout(Some(Duration::from_secs(10)));
        let (ws, _) = tungstenite::client(req, tcp).map_err(|e| format!("handshake: {e}"))?;
        let _ = ws.get_ref().set_read_timeout(Some(Duration::from_millis(100)));
        Ok(Client { ws, seen: Vec::new(), cursor: 0, drift: Vec::new() })
    }

    pub fn send(&mut self, e: &Envelope) -> Result<(), String> {
        let text = serde_json::to_string(e).map_err(|e| e.to_string())?;
        self.ws.send(Message::Text(text)).map_err(|e| format!("send: {e}"))
    }

    /// Send `{type, data, sessionId}`.
    pub fn send_frame(&mut self, ty: &str, data: &str, session: Option<&str>) -> Result<(), String> {
        let mut e = Envelope::new(ty, data);
        e.session_id = session.map(String::from);
        self.send(&e)
    }

    fn pump(&mut self) -> Result<(), String> {
        match self.ws.read() {
            Ok(Message::Text(t)) => {
                let e: Envelope = serde_json::from_str(&t).map_err(|e| format!("bad frame {t:.200}: {e}"))?;
                if let Ok(wire) = serde_json::from_str::<serde_json::Value>(&t) {
                    if serde_json::to_value(&e).ok().as_ref() != Some(&wire) {
                        self.drift.push(format!("Envelope round trip differs for `{}`: wire {t:.200}", e.r#type));
                    }
                }
                self.seen.push(e);
                Ok(())
            }
            Ok(Message::Close(_)) => Err("closed by daemon".into()),
            Ok(_) => Ok(()),
            Err(tungstenite::Error::Io(e))
                if matches!(e.kind(), std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut) =>
            {
                Ok(())
            }
            Err(e) => Err(format!("read: {e}")),
        }
    }

    /// The next frame not yet consumed by `wait_for` / `next`, or `None` after `timeout`.
    pub fn next(&mut self, timeout: Duration) -> Result<Option<Envelope>, String> {
        let end = Instant::now() + timeout;
        loop {
            if self.cursor < self.seen.len() {
                self.cursor += 1;
                return Ok(Some(self.seen[self.cursor - 1].clone()));
            }
            self.pump()?;
            if self.cursor >= self.seen.len() && Instant::now() >= end {
                return Ok(None);
            }
        }
    }

    /// Consume frames until `pred` is true for one (returned); every frame skipped stays in `seen`.
    pub fn wait_for(&mut self, timeout: Duration, mut pred: impl FnMut(&Envelope) -> bool) -> Result<Envelope, String> {
        let end = Instant::now() + timeout;
        loop {
            let left = end.saturating_duration_since(Instant::now());
            match self.next(left)? {
                Some(e) if pred(&e) => return Ok(e),
                Some(_) => {}
                None => return Err("timed out waiting for a frame".into()),
            }
        }
    }

    /// Consume everything that arrives until `quiet` of silence (bounded by `max`).
    pub fn drain(&mut self, quiet: Duration, max: Duration) -> Result<(), String> {
        let end = Instant::now() + max;
        while Instant::now() < end {
            if self.next(quiet)?.is_none() {
                break;
            }
        }
        Ok(())
    }

    pub fn close(mut self) {
        let _ = self.ws.close(None);
    }
}

pub fn ws_url(u: &str) -> String {
    let u = u.trim().trim_end_matches('/');
    let u = match u.strip_prefix("http://") {
        Some(r) => format!("ws://{r}"),
        None => u.to_string(),
    };
    let u = if u.starts_with("ws://") { u } else { format!("ws://{u}") };
    if u.ends_with("/ws") { u } else { format!("{u}/ws") }
}
