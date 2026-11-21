// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The Infantry AI shell (Tauri v2). The WebView renders the React app; the daemon socket is opened from Rust by
//! tauri-plugin-websocket, so it carries no browser Origin (the daemon refuses a foreign Origin, CONTRACT §7) and a
//! token rides a plain `Authorization: Bearer` header.
//!
//! Desktop (`desktop.rs`): the daemon sidecar, the embedded runner, or INFANTRY_URL. A phone (`mobile.rs`, RUST-MOBILE-1)
//! runs no daemon and no agents: it connects to a remote daemon, paired by link or QR code, its token in the keystore.
//! Both read the daemon's web UI plugin files for the page (`daemon_http.rs`, WEBUI-1).

mod daemon_http;
mod feed;
#[cfg(desktop)]
mod daemon;
#[cfg(desktop)]
mod desktop;
#[cfg(desktop)]
mod local;
#[cfg(desktop)]
mod login_window;
#[cfg(desktop)]
mod pairing;
#[cfg(desktop)]
mod supervisor;
#[cfg(desktop)]
mod update;
#[cfg(target_os = "android")]
mod android_update;
#[cfg(mobile)]
mod mobile;

/// An `INFANTRY_<name>` variable, else its old `AGENTHQ_<name>` (AGENTS.md §7); blank counts as unset.
#[cfg_attr(mobile, allow(dead_code))]
pub(crate) fn env(name: &str) -> Option<String> {
    std::env::var(format!("INFANTRY_{name}"))
        .or_else(|_| std::env::var(format!("AGENTHQ_{name}")))
        .ok()
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

/// A daemon address as its socket URL: http(s) becomes ws(s), a bare host gets ws://, and a URL with no path gets `/ws`.
pub(crate) fn ws_url(raw: &str) -> String {
    let u = raw.trim().trim_end_matches('/');
    let u = if let Some(rest) = u.strip_prefix("http://") {
        format!("ws://{rest}")
    } else if let Some(rest) = u.strip_prefix("https://") {
        format!("wss://{rest}")
    } else if u.starts_with("ws://") || u.starts_with("wss://") {
        u.to_string()
    } else {
        format!("ws://{u}")
    };
    let after_scheme = u.split_once("://").map(|(_, r)| r).unwrap_or("");
    if after_scheme.contains('/') { u } else { format!("{u}/ws") }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    #[cfg(desktop)]
    desktop::run();
    #[cfg(mobile)]
    mobile::run();
}

#[cfg(test)]
mod tests {
    use super::ws_url;

    #[test]
    fn urls_become_socket_urls() {
        assert_eq!(ws_url("http://127.0.0.1:18931"), "ws://127.0.0.1:18931/ws");
        assert_eq!(ws_url("ws://127.0.0.1:8787/ws"), "ws://127.0.0.1:8787/ws");
        assert_eq!(ws_url("https://box.example/"), "wss://box.example/ws");
        assert_eq!(ws_url("127.0.0.1:9000"), "ws://127.0.0.1:9000/ws");
        assert_eq!(ws_url(" 100.67.51.8:18990 "), "ws://100.67.51.8:18990/ws");
    }
}
