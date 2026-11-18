// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The desktop half of the shell: the daemon sidecar, the embedded runner, or INFANTRY_URL (RUST-MOBILE-1 moved it out of
//! lib.rs so a phone build leaves it out).

use crate::daemon;
use crate::local::{Local, Project, Resumable};
use crate::{env, ws_url};
use serde::Serialize;
use std::io::Write;
use std::path::PathBuf;
use std::sync::{Arc, Condvar, Mutex};
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
    /// INFANTRY_UI_LOCAL=1, or the `runner` setting `embedded`: agents run in this app's own embedded runner (local.rs).
    local: bool,
    /// `embedded` (local.rs), `daemon` (the sidecar this app started or found) or `connect` (INFANTRY_URL / INFANTRY_PORT).
    mode: &'static str,
    /// Daemon mode: why the sidecar could not start (the UI shows it; the URL is then unreachable).
    error: Option<String>,
    /// INFANTRY_PERF_SCENARIO: the perf harness's scenario (`agents=5;stream=1;...`), run in-page (perf-scenario.ts).
    perf: Option<String>,
}

/// INFANTRY_URL, else INFANTRY_PORT on loopback; None when unset or when it names a foreign daemon.
fn connect_target() -> Option<String> {
    let url = match (env("URL"), env("PORT")) {
        (Some(u), _) => ws_url(&u),
        (_, Some(p)) => format!("ws://127.0.0.1:{p}/ws"),
        _ => return None,
    };
    if daemon::foreign(&url) {
        eprintln!("[infantry] ignoring {url}: the Kotlin app's daemon; using this app's own daemon");
        return None;
    }
    Some(url)
}

/// Where the agents run.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum Mode {
    Embedded,
    Daemon,
    Connect,
}

fn truthy(v: &str) -> bool {
    v != "0" && !v.eq_ignore_ascii_case("false")
}

/// INFANTRY_URL / INFANTRY_PORT = connect to that daemon; else INFANTRY_UI_LOCAL (1 = embedded, 0 = daemon); else the
/// `runner` setting in ui.json (`embedded` | `daemon`); else daemon mode.
/// An endpoint naming the Kotlin app's daemon (8797/8787; a shell set up for the `infantry` CLI carries one) is ignored:
/// this app never connects there.
fn mode(settings: &UiSettings) -> Mode {
    if connect_target().is_some() {
        return Mode::Connect;
    }
    match env("UI_LOCAL") {
        Some(v) if truthy(&v) => Mode::Embedded,
        Some(_) => Mode::Daemon,
        None if settings.runner.as_deref() == Some("embedded") => Mode::Embedded,
        None => Mode::Daemon,
    }
}

/// The app's own settings file (`<state>/ui.json`); read at start, so a change applies on the next start.
#[derive(serde::Deserialize, Serialize, Default, Clone)]
struct UiSettings {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    runner: Option<String>,
    /// Daemon mode: the sidecar's state dir (RUST-MIGRATE-1: a copy made by `infantry-migrate`, e.g.
    /// `%LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust`); unset = `<app data>/daemon`.
    #[serde(default, rename = "daemonStateDir", skip_serializing_if = "Option::is_none")]
    daemon_state_dir: Option<String>,
}

fn settings_path(state: &std::path::Path) -> PathBuf {
    state.join("ui.json")
}

fn load_settings(state: &std::path::Path) -> UiSettings {
    std::fs::read_to_string(settings_path(state)).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default()
}

/// The app's state: its dir, its settings, its mode, and (daemon mode) the sidecar once it is up.
struct AppState {
    dir: PathBuf,
    mode: Mode,
    settings: Mutex<UiSettings>,
    sidecar: Arc<(Mutex<Option<Result<daemon::Sidecar, String>>>, Condvar)>,
}

#[tauri::command]
async fn endpoint(state: State<'_, AppState>) -> Result<Endpoint, String> {
    let stress = env("UI_STRESS").is_some_and(|v| truthy(&v));
    let token = env("TOKEN_FILE")
        .and_then(|f| std::fs::read_to_string(f).ok())
        .map(|t| t.trim().to_string())
        .filter(|t| !t.is_empty())
        .or_else(|| env("TOKEN"));
    let perf = env("PERF_SCENARIO");
    let (url, token, error, mode) = match state.mode {
        Mode::Connect => {
            let url = connect_target().expect("connect mode has a target");
            (url, token, None, "connect")
        }
        Mode::Embedded => ("embedded runner".into(), None, None, "embedded"),
        Mode::Daemon => {
            // The sidecar starts on a thread at setup; wait for it off the main thread.
            let cell = state.sidecar.clone();
            let r = tauri::async_runtime::spawn_blocking(move || {
                let (m, cv) = &*cell;
                let g = cv.wait_while(m.lock().unwrap(), |s| s.is_none()).unwrap();
                match g.as_ref().unwrap() {
                    Ok(s) => Ok(s.url.clone()),
                    Err(e) => Err(e.clone()),
                }
            })
            .await
            .map_err(|e| e.to_string())?;
            match r {
                Ok(url) => (url, None, None, "daemon"),
                Err(e) => ("ws://127.0.0.1:0/ws".into(), None, Some(e), "daemon"),
            }
        }
    };
    Ok(Endpoint { url, token, stress, local: mode == "embedded", mode, error, perf })
}

/// The `runner` setting (`embedded` | `daemon`); applies on the next start.
#[tauri::command]
fn ui_runner_get(state: State<'_, AppState>) -> (String, String) {
    let s = state.settings.lock().unwrap();
    let current = match state.mode {
        Mode::Embedded => "embedded",
        Mode::Daemon => "daemon",
        Mode::Connect => "connect",
    };
    (s.runner.clone().unwrap_or_else(|| "daemon".into()), current.into())
}

#[tauri::command]
fn ui_runner_set(state: State<'_, AppState>, runner: String) -> Result<(), String> {
    if runner != "embedded" && runner != "daemon" {
        return Err(format!("unknown runner `{runner}` (embedded | daemon)"));
    }
    let mut s = state.settings.lock().unwrap();
    s.runner = Some(runner);
    let text = serde_json::to_string_pretty(&*s).map_err(|e| e.to_string())?;
    std::fs::write(settings_path(&state.dir), text).map_err(|e| e.to_string())
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
            let settings = load_settings(&dir);
            let mode = mode(&settings);
            let sidecar = Arc::new((Mutex::new(None), Condvar::new()));
            if mode == Mode::Daemon {
                // INFANTRY_UI_DAEMON_PORT pins the sidecar's port (tests use 189xx; 0 = any free port), else 18990.
                let port = env("UI_DAEMON_PORT").and_then(|p| p.parse().ok()).unwrap_or(daemon::DEFAULT_PORT);
                // INFANTRY_UI_DAEMON_STATE_DIR, else ui.json `daemonStateDir`, else %LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust
                // (the state `infantry-migrate` fills and start.cmd uses), else <app data>/daemon.
                let state = env("UI_DAEMON_STATE_DIR")
                    .or_else(|| settings.daemon_state_dir.clone())
                    .filter(|s| !s.trim().is_empty())
                    .map(PathBuf::from)
                    .or_else(daemon::default_state_dir)
                    .unwrap_or_else(|| dir.join("daemon"));
                let cell = sidecar.clone();
                let cwd = std::env::current_dir().unwrap_or_else(|_| dir.clone());
                std::thread::spawn(move || {
                    let r = daemon::ensure(&state, port, &cwd);
                    if let Err(e) = &r {
                        eprintln!("[infantry] daemon mode: {e}");
                    }
                    let (m, cv) = &*cell;
                    *m.lock().unwrap() = Some(r);
                    cv.notify_all();
                });
            }
            app.manage(AppState { dir: dir.clone(), mode, settings: Mutex::new(settings), sidecar });
            app.manage(Local::new(dir));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![endpoint, ui_runner_get, ui_runner_set, local_connect, local_send, local_set_project, local_resumable, perf_report])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app, e| {
            // Every session's process tree dies with the app (Job Objects); dropping them first makes it prompt.
            if let tauri::RunEvent::Exit = e {
                app.state::<Local>().shutdown();
            }
        });
}
