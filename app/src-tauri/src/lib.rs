// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The Infantry AI desktop shell. The WebView renders the React app; the daemon socket is opened from Rust by
//! tauri-plugin-websocket, so it carries no browser Origin (the daemon refuses a foreign Origin, CONTRACT §7) and a
//! token rides a plain `Authorization: Bearer` header.

use serde::Serialize;

/// Where the daemon is, resolved like the `infantry` CLI: INFANTRY_URL, else INFANTRY_PORT on loopback, else 8787.
/// The old AGENTHQ_* names are read as fallbacks (AGENTS.md §7).
#[derive(Serialize)]
struct Endpoint {
    url: String,
    token: Option<String>,
    /// INFANTRY_UI_STRESS=1: fake sessions and a fast stream instead of a daemon (the perf check).
    stress: bool,
}

fn env(name: &str) -> Option<String> {
    std::env::var(format!("INFANTRY_{name}"))
        .or_else(|_| std::env::var(format!("AGENTHQ_{name}")))
        .ok()
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
}

fn ws_url(raw: &str) -> String {
    let u = raw.trim_end_matches('/');
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

#[tauri::command]
fn endpoint() -> Endpoint {
    let url = match (env("URL"), env("PORT")) {
        (Some(u), _) => ws_url(&u),
        (None, Some(p)) => format!("ws://127.0.0.1:{p}/ws"),
        (None, None) => "ws://127.0.0.1:8787/ws".to_string(),
    };
    let token = env("TOKEN_FILE")
        .and_then(|f| std::fs::read_to_string(f).ok())
        .map(|t| t.trim().to_string())
        .filter(|t| !t.is_empty())
        .or_else(|| env("TOKEN"));
    let stress = env("UI_STRESS").is_some_and(|v| v != "0" && v != "false");
    Endpoint { url, token, stress }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_websocket::init())
        .invoke_handler(tauri::generate_handler![endpoint])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
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
    }
}
