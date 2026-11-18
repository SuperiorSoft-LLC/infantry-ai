// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The Infantry AI desktop shell. The WebView renders the React app; the daemon socket is opened from Rust by
//! tauri-plugin-websocket, so it carries no browser Origin (the daemon refuses a foreign Origin, CONTRACT §7) and a
//! token rides a plain `Authorization: Bearer` header.

mod local;

use local::{Local, Project, Resumable};
use serde::Serialize;
use std::io::Write;
use tauri::ipc::Channel;
use tauri::{Manager, State};

/// Where the daemon is, resolved like the `infantry` CLI: INFANTRY_URL, else INFANTRY_PORT on loopback, else 8787.
/// The old AGENTHQ_* names are read as fallbacks (AGENTS.md §7).
#[derive(Serialize)]
struct Endpoint {
    url: String,
    token: Option<String>,
    /// INFANTRY_UI_STRESS=1: fake sessions and a fast stream instead of a daemon (the perf check).
    stress: bool,
    /// No INFANTRY_URL / INFANTRY_PORT (or INFANTRY_UI_LOCAL=1): agents run in this app's own embedded runner (local.rs).
    local: bool,
    /// INFANTRY_PERF_SCENARIO: the perf harness's scenario (`agents=5;stream=1;...`), run in-page (perf-scenario.ts).
    perf: Option<String>,
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
    let local = env("UI_LOCAL").map_or(env("URL").is_none() && env("PORT").is_none(), |v| v != "0" && v != "false");
    Endpoint { url, token, stress, local, perf: env("PERF_SCENARIO") }
}

/// The embedded runner's link: the WebView's transport opens it with a channel that receives every outbound frame.
#[tauri::command]
fn local_connect(local: State<'_, Local>, channel: Channel<String>) {
    local.connect(channel);
}

/// One frame from the WebView (an encoded envelope).
#[tauri::command]
fn local_send(local: State<'_, Local>, text: String) {
    local.handle(&text);
}

#[tauri::command]
fn local_set_project(local: State<'_, Local>, folder: String) -> Result<Project, String> {
    local.set_project(&folder)
}

#[tauri::command]
fn local_resumable(local: State<'_, Local>, folder: String) -> Vec<Resumable> {
    local.resumable(&folder)
}

/// The perf harness: one JSON line appended to INFANTRY_PERF_OUT.
#[tauri::command]
fn perf_report(line: String) -> Result<(), String> {
    let Some(path) = env("PERF_OUT") else { return Ok(()) };
    let mut f = std::fs::OpenOptions::new().create(true).append(true).open(path).map_err(|e| e.to_string())?;
    writeln!(f, "{}", line.replace('\n', " ")).map_err(|e| e.to_string())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_websocket::init())
        .plugin(tauri_plugin_dialog::init())
        .setup(|app| {
            // The runner's own files (per-session hook settings, the scratch folder). INFANTRY_STATE_DIR pins it for tests.
            let dir = env("STATE_DIR").map(std::path::PathBuf::from).unwrap_or_else(|| {
                app.path().app_local_data_dir().unwrap_or_else(|_| std::env::temp_dir().join("infantry-tauri"))
            });
            let _ = std::fs::create_dir_all(&dir);
            app.manage(Local::new(dir));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![endpoint, local_connect, local_send, local_set_project, local_resumable, perf_report])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app, e| {
            // Every session's process tree dies with the app (Job Objects); dropping them first makes it prompt.
            if let tauri::RunEvent::Exit = e {
                app.state::<Local>().shutdown();
            }
        });
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
