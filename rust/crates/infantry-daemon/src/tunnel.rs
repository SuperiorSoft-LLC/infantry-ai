// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RR-6b: the tunnel listener's gate. A remote runner's session reaches this daemon through a loopback forward (RR-5): the
//! runner host accepts a connection and the link mirrors it to a port HERE. To this daemon those bytes look like a local
//! socket, so they must never be trusted as loopback or owner: any process on the runner host could dial that port. The
//! forward therefore targets a second listener (`serve_with` opens it) on which EVERY request needs a per-session token,
//! minted at the session's start, lent in its env (`INFANTRY_TOKEN`, the MCP env too) and dropped when the session ends.
//! A token is bound to one session id; its connection may only speak the MCP surface for that session.

use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::atomic::{AtomicU16, Ordering};
use std::sync::Mutex;

/// The frames a tunnel connection may send: the MCP bridge's tool surface, nothing else (no session list, no owner frames).
const ALLOWED_FRAMES: [&str; 3] = ["plugin_tools", "plugin_tool_call", "capability_call"];

#[derive(Default)]
pub struct TunnelGate {
    port: AtomicU16,
    /// sha256(token) -> session id
    by_digest: Mutex<HashMap<[u8; 32], String>>,
    /// session id -> sha256(token)
    by_session: Mutex<HashMap<String, [u8; 32]>>,
}

fn digest(t: &str) -> [u8; 32] {
    Sha256::digest(t.as_bytes()).into()
}

impl TunnelGate {
    /// The port of the tunnel listener (0 = none is open, e.g. a daemon built without `serve_with`).
    pub fn port(&self) -> u16 {
        self.port.load(Ordering::Relaxed)
    }

    pub fn set_port(&self, p: u16) {
        self.port.store(p, Ordering::Relaxed);
    }

    /// A fresh token for [session_id] (64 hex characters, 244 bits); an earlier token of that session stops working.
    pub fn mint(&self, session_id: &str) -> String {
        let token = format!("{}{}", uuid::Uuid::new_v4().simple(), uuid::Uuid::new_v4().simple());
        let d = digest(&token);
        let mut by_session = self.by_session.lock().unwrap();
        let mut by_digest = self.by_digest.lock().unwrap();
        if let Some(old) = by_session.insert(session_id.to_string(), d) {
            by_digest.remove(&old);
        }
        by_digest.insert(d, session_id.to_string());
        token
    }

    /// The session a token was minted for.
    pub fn resolve(&self, token: &str) -> Option<String> {
        let t = token.trim();
        if t.is_empty() {
            return None;
        }
        self.by_digest.lock().unwrap().get(&digest(t)).cloned()
    }

    /// True while [session_id] has a live token.
    pub fn resolve_session(&self, session_id: &str) -> Option<()> {
        self.by_session.lock().unwrap().contains_key(session_id).then_some(())
    }

    /// The token ends with its session.
    pub fn revoke(&self, session_id: &str) {
        if let Some(d) = self.by_session.lock().unwrap().remove(session_id) {
            self.by_digest.lock().unwrap().remove(&d);
        }
    }
}

/// The bearer in an `Authorization` header value.
pub fn bearer(authorization: Option<&str>) -> Option<&str> {
    let h = authorization?.trim();
    if !h.get(..7).is_some_and(|p| p.eq_ignore_ascii_case("bearer ")) {
        return None;
    }
    Some(h[7..].trim()).filter(|t| !t.is_empty())
}

/// Why a frame from a tunnel connection bound to [sid] is refused (403), or `None` when it is in that session's scope.
pub fn scope_refusal(sid: &str, ty: &str, session_id: Option<&str>, data: &str) -> Option<String> {
    if !ALLOWED_FRAMES.contains(&ty) {
        return Some(format!("frame `{ty}` is outside a remote session's scope"));
    }
    if let Some(other) = session_id.filter(|s| !s.is_empty() && *s != sid) {
        return Some(format!("frame names session `{other}`, not this connection's session"));
    }
    let named = serde_json::from_str::<serde_json::Value>(data).ok().and_then(|v| v["client"]["sessionId"].as_str().map(String::from));
    match named {
        Some(n) if n != sid => Some(format!("tool call is made as session `{n}`, not this connection's session")),
        None if ty == "plugin_tool_call" => Some("a tool call must name its session (client.sessionId)".into()),
        _ => None,
    }
}
