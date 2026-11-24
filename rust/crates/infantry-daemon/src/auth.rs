// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Who may connect: a port of Kotlin's `AccessPolicy` (the parts `/ws` and `/health` use).
//!
//! - **token** (`INFANTRY_TOKEN_FILE`, else `INFANTRY_TOKEN`; `AGENTHQ_*` fallbacks): every route except `/health` needs
//!   `Authorization: Bearer <token>` (scheme case-insensitive, surrounding blanks ignored). A non-loopback bind needs one.
//! - **loopback** (no token): the Host header must name the loopback address (DNS rebinding).
//! - A request with an Origin header is refused, except a same-origin browser on `/ws` whose Host is in
//!   `INFANTRY_WEB_HOSTS`, on a token daemon, presenting the bearer (header, or the `infantry.bearer.<base64url>`
//!   subprotocol, answered with `infantry`).
//! - The app's own webview (Tauri, `TAURI_ORIGINS` plus `INFANTRY_WEB_ORIGINS`) is the one Origin admitted on `/ws` like
//!   a non-browser client: a web page cannot forge it, so the loopback Host check (no token) or the bearer (header or
//!   subprotocol) is all it needs. The desktop window opens its own `WebSocket` this way (no plugin IPC hop).

use base64::Engine;
use sha2::{Digest, Sha256};

pub const MIN_TOKEN_LENGTH: usize = 32;
pub const TOKEN_ENV: &str = "INFANTRY_TOKEN";
pub const TOKEN_FILE_ENV: &str = "INFANTRY_TOKEN_FILE";
pub const WEB_HOSTS_ENV: &str = "INFANTRY_WEB_HOSTS";
/// Extra app-webview origins (comma separated) admitted on `/ws` next to [`TAURI_ORIGINS`].
pub const WEB_ORIGINS_ENV: &str = "INFANTRY_WEB_ORIGINS";
/// The origins Tauri 2 serves its page from: macOS/Linux, Windows, and Android/Windows with `useHttpsScheme`.
pub const TAURI_ORIGINS: [&str; 3] = ["tauri://localhost", "http://tauri.localhost", "https://tauri.localhost"];
pub const OPEN_PATH: &str = "/health";
pub const BEARER_PROTOCOL_PREFIX: &str = "infantry.bearer.";
/// The subprotocol the daemon answers a browser with.
pub const ANSWER_PROTOCOL: &str = "infantry";

/// `INFANTRY_X`, else the legacy `AGENTHQ_X`.
pub fn env(name: &str) -> Option<String> {
    let v = std::env::var(name).ok().filter(|v| !v.trim().is_empty());
    v.or_else(|| {
        let legacy = name.strip_prefix("INFANTRY_").map(|r| format!("AGENTHQ_{r}"))?;
        std::env::var(legacy).ok().filter(|v| !v.trim().is_empty())
    })
}

/// The token from the environment: the file `INFANTRY_TOKEN_FILE` names (BOM and trailing newline stripped), else
/// `INFANTRY_TOKEN`. Both set, an unreadable or empty file, or a weak token is a configuration error.
pub fn token_from_environment() -> Result<Option<String>, String> {
    let (file, value) = (env(TOKEN_FILE_ENV), env(TOKEN_ENV));
    let token = match (file, value) {
        (Some(_), Some(_)) => return Err(format!("set one of {TOKEN_FILE_ENV} and {TOKEN_ENV}, not both")),
        (Some(f), None) => {
            let t = std::fs::read_to_string(&f).map_err(|e| format!("{TOKEN_FILE_ENV} {f}: {e}"))?;
            let t = t.trim_start_matches('\u{feff}').trim().to_string();
            if t.is_empty() {
                return Err(format!("{TOKEN_FILE_ENV} {f} is empty"));
            }
            t
        }
        (None, Some(v)) => v.trim().to_string(),
        (None, None) => return Ok(None),
    };
    check_token(&token)?;
    Ok(Some(token))
}

pub fn check_token(t: &str) -> Result<(), String> {
    if t.chars().count() < MIN_TOKEN_LENGTH || t.chars().any(|c| c.is_whitespace() || c.is_control()) {
        return Err(format!("the token must be at least {MIN_TOKEN_LENGTH} characters with no whitespace"));
    }
    Ok(())
}

/// One refused request.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Refusal {
    pub status: u16,
    pub reason: &'static str,
    pub challenge_bearer: bool,
}

pub struct AccessPolicy {
    digest: Option<[u8; 32]>,
    browser_hosts: Vec<String>,
    app_origins: Vec<String>,
}

fn digest(s: &str) -> [u8; 32] {
    Sha256::digest(s.as_bytes()).into()
}

/// Fixed-length digests compared without an early exit.
fn same(a: &[u8; 32], b: &[u8; 32]) -> bool {
    a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

pub fn is_loopback_host(h: &str) -> bool {
    let h = h.trim().trim_start_matches('[').trim_end_matches(']').to_ascii_lowercase();
    h == "localhost" || h == "::1" || h.starts_with("127.")
}

/// The host name of a Host header (port and IPv6 brackets dropped).
fn host_name(header: &str) -> String {
    let h = header.trim();
    if let Some(rest) = h.strip_prefix('[') {
        return rest.split(']').next().unwrap_or("").to_ascii_lowercase();
    }
    h.rsplit_once(':').map_or(h, |(n, p)| if p.chars().all(|c| c.is_ascii_digit()) { n } else { h }).to_ascii_lowercase()
}

/// The bearer from a `Sec-WebSocket-Protocol` list.
pub fn bearer_from_protocols(protocols: Option<&str>) -> Option<String> {
    let enc = protocols?.split(',').map(str::trim).find_map(|p| p.strip_prefix(BEARER_PROTOCOL_PREFIX))?;
    let bytes = base64::engine::general_purpose::URL_SAFE_NO_PAD.decode(enc.trim_end_matches('=')).ok()?;
    String::from_utf8(bytes).ok()
}

impl AccessPolicy {
    /// Refuses a non-loopback bind without a token.
    pub fn new(bind_host: &str, token: Option<&str>) -> Result<AccessPolicy, String> {
        if token.is_none() && !is_loopback_host(bind_host) {
            return Err(format!("binding {bind_host} (not loopback) needs a token: set {TOKEN_FILE_ENV} or {TOKEN_ENV}"));
        }
        let browser_hosts = env(WEB_HOSTS_ENV)
            .map(|v| v.split(',').map(|h| h.trim().to_ascii_lowercase()).filter(|h| !h.is_empty()).collect())
            .unwrap_or_default();
        let mut app_origins: Vec<String> = TAURI_ORIGINS.iter().map(|o| o.to_string()).collect();
        if let Some(v) = env(WEB_ORIGINS_ENV) {
            app_origins.extend(v.split(',').map(|o| o.trim().trim_end_matches('/').to_ascii_lowercase()).filter(|o| !o.is_empty()));
        }
        Ok(AccessPolicy { digest: token.map(digest), browser_hosts, app_origins })
    }

    /// BOS-7: the app's own origin (`BATTALION_UI_ORIGIN`) joins the origins admitted on `/ws`.
    pub fn with_app_origin(mut self, origin: &str) -> AccessPolicy {
        let o = origin.trim().trim_end_matches('/').to_ascii_lowercase();
        if !o.is_empty() && !self.app_origins.contains(&o) {
            self.app_origins.push(o);
        }
        self
    }

    pub fn token_required(&self) -> bool {
        self.digest.is_some()
    }

    fn matches(&self, presented: &str) -> bool {
        match &self.digest {
            Some(d) => !presented.is_empty() && same(&digest(presented), d),
            None => true,
        }
    }

    fn bearer_matches(&self, authorization: Option<&str>) -> bool {
        if self.digest.is_none() {
            return true;
        }
        let h = authorization.unwrap_or("").trim();
        if !h.get(..7).is_some_and(|p| p.eq_ignore_ascii_case("bearer ")) {
            return false;
        }
        let presented = h[7..].trim();
        !presented.is_empty() && self.matches(presented)
    }

    /// None = admitted.
    pub fn check(&self, path: &str, host: Option<&str>, origin: Option<&str>, authorization: Option<&str>, protocols: Option<&str>) -> Option<Refusal> {
        const LOOPBACK: &str = "the Host header must name the loopback address (localhost, 127.0.0.1 or [::1])";
        if let Some(o) = origin {
            if path == "/ws" && self.is_app_origin(o) {
                return self.check_app(host, authorization, protocols);
            }
            if path != "/ws" {
                return Some(Refusal { status: 403, reason: "requests carrying an Origin header are refused: this daemon is not a browser API", challenge_bearer: false });
            }
            return self.check_browser(host, o, authorization, protocols);
        }
        if self.digest.is_none() {
            return if host.is_some_and(|h| is_loopback_host(&host_name(h))) {
                None
            } else {
                Some(Refusal { status: 403, reason: LOOPBACK, challenge_bearer: false })
            };
        }
        if path == OPEN_PATH || self.bearer_matches(authorization) {
            None
        } else {
            Some(Refusal { status: 401, reason: "a bearer token is required", challenge_bearer: true })
        }
    }

    /// True when [origin] is one of the app webview's own origins.
    pub fn is_app_origin(&self, origin: &str) -> bool {
        let o = origin.trim().trim_end_matches('/').to_ascii_lowercase();
        self.app_origins.iter().any(|a| *a == o)
    }

    /// The app webview: a loopback Host with no token, else the bearer (header or subprotocol).
    fn check_app(&self, host: Option<&str>, authorization: Option<&str>, protocols: Option<&str>) -> Option<Refusal> {
        if self.digest.is_none() {
            return if host.is_some_and(|h| is_loopback_host(&host_name(h))) {
                None
            } else {
                Some(Refusal { status: 403, reason: "the Host header must name the loopback address (localhost, 127.0.0.1 or [::1])", challenge_bearer: false })
            };
        }
        if self.bearer_matches(authorization) || bearer_from_protocols(protocols).is_some_and(|t| self.matches(&t)) {
            None
        } else {
            Some(Refusal { status: 401, reason: "a bearer token is required", challenge_bearer: true })
        }
    }

    fn check_browser(&self, host: Option<&str>, origin: &str, authorization: Option<&str>, protocols: Option<&str>) -> Option<Refusal> {
        if self.digest.is_none() {
            return Some(Refusal { status: 403, reason: "a browser can only connect to a daemon with a bearer token", challenge_bearer: false });
        }
        let h = host.map(host_name).unwrap_or_default();
        if !self.browser_hosts.contains(&h) {
            return Some(Refusal { status: 403, reason: "this host name is not one the daemon answers browsers on; add it to INFANTRY_WEB_HOSTS", challenge_bearer: false });
        }
        let o = origin.trim().to_ascii_lowercase();
        let o_host = o.split("://").nth(1).map(host_name).unwrap_or_default();
        let same_origin = o_host == h && host.is_some_and(|hh| o.ends_with(&hh.trim().to_ascii_lowercase()));
        if !same_origin {
            return Some(Refusal { status: 403, reason: "requests carrying a cross-site Origin header are refused: this daemon is not a browser API", challenge_bearer: false });
        }
        let ok = self.bearer_matches(authorization) || bearer_from_protocols(protocols).is_some_and(|t| self.matches(&t));
        if ok {
            None
        } else {
            Some(Refusal { status: 401, reason: "a bearer token is required", challenge_bearer: true })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    const T: &str = "0123456789abcdef0123456789abcdef";

    #[test]
    fn loopback_without_token_checks_the_host() {
        let p = AccessPolicy::new("127.0.0.1", None).unwrap();
        assert_eq!(p.check("/ws", Some("127.0.0.1:18901"), None, None, None), None);
        assert_eq!(p.check("/ws", Some("[::1]:18901"), None, None, None), None);
        assert_eq!(p.check("/ws", Some("evil.example"), None, None, None).unwrap().status, 403);
        assert!(AccessPolicy::new("0.0.0.0", None).is_err());
    }

    #[test]
    fn bearer_is_checked_strictly() {
        let p = AccessPolicy::new("0.0.0.0", Some(T)).unwrap();
        for bad in [None, Some("Bearer"), Some("Bearer "), Some("Basic x"), Some("Bearer wrong")] {
            assert_eq!(p.check("/ws", Some("h"), None, bad, None).unwrap().status, 401, "{bad:?}");
        }
        assert_eq!(p.check("/ws", Some("h"), None, Some(&format!("  bearer   {T}  ")), None), None);
        assert_eq!(p.check("/health", Some("h"), None, None, None), None);
    }

    #[test]
    fn origin_is_refused_even_with_the_token() {
        let p = AccessPolicy::new("127.0.0.1", Some(T)).unwrap();
        let auth = format!("Bearer {T}");
        assert_eq!(p.check("/ws", Some("127.0.0.1"), Some("http://evil.example"), Some(&auth), None).unwrap().status, 403);
    }

    #[test]
    fn the_app_webview_origin_is_admitted_like_a_native_client() {
        // No token: loopback Host only, whatever the Tauri origin is.
        let open = AccessPolicy::new("127.0.0.1", None).unwrap();
        for o in TAURI_ORIGINS {
            assert_eq!(open.check("/ws", Some("127.0.0.1:18990"), Some(o), None, None), None, "{o}");
        }
        assert_eq!(open.check("/ws", Some("evil.example"), Some("tauri://localhost"), None, None).unwrap().status, 403);
        // A web page's origin is still refused, and the app origin is still refused off `/ws`.
        assert_eq!(open.check("/ws", Some("127.0.0.1"), Some("http://evil.example"), None, None).unwrap().status, 403);
        assert_eq!(open.check("/health", Some("127.0.0.1"), Some("tauri://localhost"), None, None).unwrap().status, 403);
        // A token daemon: the bearer rides the header or the subprotocol; none is 401.
        let p = AccessPolicy::new("0.0.0.0", Some(T)).unwrap();
        let enc = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(T);
        let sub = format!("infantry, {BEARER_PROTOCOL_PREFIX}{enc}");
        assert_eq!(p.check("/ws", Some("100.1.2.3:18990"), Some("http://tauri.localhost"), None, Some(&sub)), None);
        assert_eq!(p.check("/ws", Some("100.1.2.3:18990"), Some("https://tauri.localhost/"), Some(&format!("Bearer {T}")), None), None);
        assert_eq!(p.check("/ws", Some("100.1.2.3:18990"), Some("tauri://localhost"), None, None).unwrap().status, 401);
    }

    #[test]
    fn subprotocol_bearer_decodes() {
        let enc = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(T);
        assert_eq!(bearer_from_protocols(Some(&format!("infantry, {BEARER_PROTOCOL_PREFIX}{enc}"))).as_deref(), Some(T));
    }
}
