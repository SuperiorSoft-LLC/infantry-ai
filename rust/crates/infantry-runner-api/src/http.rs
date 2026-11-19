// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `loopback-http`: how a harness talks to a server process it started on the runner's host (`opencode serve`). The
//! request is made BY THE RUNNER, on its host, because that server listens on the runner host's loopback: a remote runner
//! forwards it, the harness cannot tell where it ran. Only loopback URLs are accepted; this is not a general web client.
//!
//! One shape covers a plain call and a long-lived stream (server-sent events): the response arrives as [`HttpEvent`]s,
//! [`HttpEvent::Head`] first, then body chunks as they come, then [`HttpEvent::End`] (or [`HttpEvent::Failed`]).

use serde::{Deserialize, Serialize};

/// One HTTP/1.1 request to a loopback server on the runner's host.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct HttpRequest {
    /// `GET`, `POST`, ...
    pub method: String,
    /// `http://127.0.0.1:<port>/path` (or `localhost` / `[::1]`); anything else is refused.
    pub url: String,
    #[serde(default)]
    pub headers: Vec<(String, String)>,
    #[serde(default)]
    pub body: Vec<u8>,
    /// How long the connection and the response head may take; None = the runner's default (30 s). The body of a stream
    /// is not timed.
    #[serde(default)]
    pub timeout_ms: Option<u64>,
}

impl HttpRequest {
    pub fn new(method: impl Into<String>, url: impl Into<String>) -> Self {
        HttpRequest { method: method.into(), url: url.into(), ..Default::default() }
    }

    pub fn header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.headers.push((name.into(), value.into()));
        self
    }

    pub fn body(mut self, body: impl Into<Vec<u8>>) -> Self {
        self.body = body.into();
        self
    }
}

/// What a response does, in order.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
#[non_exhaustive]
pub enum HttpEvent {
    /// The status line and headers arrived.
    Head { status: u16, headers: Vec<(String, String)> },
    /// Body bytes (de-chunked), as they arrive.
    Body(Vec<u8>),
    /// The body is complete (always the last event of a successful response).
    End,
    /// The request failed (refused, connection lost, timed out); the last event.
    Failed(String),
}

/// Whether [url] names a loopback host (`127.x.x.x`, `localhost`, `[::1]`) over plain `http`.
pub fn is_loopback_url(url: &str) -> bool {
    let Some(rest) = url.strip_prefix("http://") else { return false };
    let authority = rest.split(['/', '?']).next().unwrap_or("");
    let host = if let Some(v6) = authority.strip_prefix('[') {
        v6.split(']').next().unwrap_or("")
    } else {
        authority.rsplit_once(':').map(|(h, _)| h).unwrap_or(authority)
    };
    host.eq_ignore_ascii_case("localhost")
        || host == "::1"
        || host.parse::<std::net::Ipv4Addr>().is_ok_and(|ip| ip.is_loopback())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn loopback_only() {
        assert!(is_loopback_url("http://127.0.0.1:4096/event"));
        assert!(is_loopback_url("http://localhost:1/"));
        assert!(is_loopback_url("http://[::1]:80/x"));
        assert!(!is_loopback_url("https://127.0.0.1/"));
        assert!(!is_loopback_url("http://example.com/"));
        assert!(!is_loopback_url("http://127.0.0.1.evil.com/"));
    }
}
