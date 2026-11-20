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
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use tauri::ipc::Channel;
use tauri::{Emitter, Manager, State};

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
    /// Daemon mode: the sidecar found running is older than the daemon binary (restart it).
    note: Option<String>,
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
pub(crate) struct UiSettings {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    runner: Option<String>,
    /// Daemon mode: the sidecar's state dir (RUST-MIGRATE-1: a copy made by `infantry-migrate`, e.g.
    /// `%LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust`); unset = `<app data>/daemon`.
    #[serde(default, rename = "daemonStateDir", skip_serializing_if = "Option::is_none")]
    daemon_state_dir: Option<String>,
    /// Daemon mode: the sidecar's port when INFANTRY_UI_DAEMON_PORT is unset (an installed app relaunched by the MSI
    /// inherits no env, so a second install, e.g. the update test, pins its port here); unset = 18990.
    #[serde(default, rename = "daemonPort", skip_serializing_if = "Option::is_none")]
    daemon_port: Option<u16>,
    /// RUST-UPD-1: the update feed, channel and auto-check / auto-install (update.rs).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub(crate) updater: Option<crate::update::UpdaterSettings>,
    /// RUST-UPD-1: the sidecar accepts phones (every interface, bearer token; pairing.rs). Off by default.
    #[serde(default, rename = "remoteAccess", skip_serializing_if = "Option::is_none")]
    pub(crate) remote_access: Option<bool>,
    /// RUST-UPD-1: keep the app folder on the user PATH (`infantry install-cli`). Opt-in, off by default.
    #[serde(default, rename = "paths.addCli", skip_serializing_if = "Option::is_none")]
    pub(crate) add_cli: Option<bool>,
    /// RUST-BUG 2617b342: the app restarts a daemon that died (supervisor.rs); backoff, polling and log generations.
    #[serde(default, rename = "daemonSupervisor", skip_serializing_if = "Option::is_none")]
    pub(crate) supervisor: Option<crate::supervisor::SupervisorSettings>,
}

fn settings_path(state: &std::path::Path) -> PathBuf {
    state.join("ui.json")
}

fn load_settings(state: &std::path::Path) -> UiSettings {
    std::fs::read_to_string(settings_path(state)).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default()
}

type SidecarCell = Arc<(Mutex<Option<Result<daemon::Sidecar, String>>>, Condvar)>;

/// The app's state: its dir, its settings, its mode, and (daemon mode) the sidecar once it is up.
pub(crate) struct AppState {
    dir: PathBuf,
    mode: Mode,
    pub(crate) settings: Mutex<UiSettings>,
    sidecar: SidecarCell,
    /// Daemon mode: the sidecar's state dir (None in embedded or connect mode).
    pub(crate) daemon_state: Option<PathBuf>,
    daemon_port: u16,
    cwd: PathBuf,
    /// Set while the app itself stops the daemon (an update, a restart): the supervisor must not call that a death.
    pub(crate) paused: Arc<AtomicBool>,
    /// Set when the app exits.
    exiting: Arc<AtomicBool>,
}

impl AppState {
    /// Changes the settings and writes ui.json.
    pub(crate) fn update(&self, f: impl FnOnce(&mut UiSettings)) -> Result<(), String> {
        let mut s = self.settings.lock().unwrap();
        f(&mut s);
        let text = serde_json::to_string_pretty(&*s).map_err(|e| e.to_string())?;
        std::fs::write(settings_path(&self.dir), text).map_err(|e| e.to_string())
    }

    fn log_keep(&self) -> usize {
        self.settings.lock().unwrap().supervisor.clone().unwrap_or_default().log_keep()
    }

    fn launch(&self) -> Result<daemon::Launch, String> {
        launch_for(self.settings.lock().unwrap().remote_access.unwrap_or(false), self.daemon_state.as_deref())
    }

    /// Phone access on: the bearer token the sidecar requires (the page needs it too, even on loopback).
    pub(crate) fn remote_token(&self) -> Option<String> {
        let state = self.daemon_state.as_ref()?;
        if !self.settings.lock().unwrap().remote_access.unwrap_or(false) {
            return None;
        }
        let f = daemon::remote_token_file(state).ok()?;
        std::fs::read_to_string(f).ok().map(|t| t.trim().to_string()).filter(|t| !t.is_empty())
    }

    /// The sidecar's port once it is up.
    pub(crate) fn sidecar_port(&self) -> Option<u16> {
        let g = self.sidecar.0.lock().unwrap();
        let url = g.as_ref()?.as_ref().ok()?.url.clone();
        url.trim_end_matches("/ws").rsplit(':').next()?.parse().ok()
    }

    /// Stops the sidecar by its PID and starts it again with the current settings (phone access, after an update).
    pub(crate) fn restart_daemon(&self) -> Result<(), String> {
        let state = self.daemon_state.as_ref().ok_or("this window runs no daemon sidecar")?;
        self.paused.store(true, Ordering::SeqCst);
        let r = self.restart_daemon_paused(state);
        self.paused.store(false, Ordering::SeqCst);
        r
    }

    fn restart_daemon_paused(&self, state: &std::path::Path) -> Result<(), String> {
        daemon::stop(state)?;
        let r = daemon::ensure(state, self.daemon_port, &self.cwd, &self.launch()?, self.log_keep());
        let out = r.as_ref().map(|_| ()).map_err(|e| e.clone());
        let (m, cv) = &*self.sidecar;
        *m.lock().unwrap() = Some(r);
        cv.notify_all();
        out
    }
}

fn launch_for(remote: bool, state: Option<&std::path::Path>) -> Result<daemon::Launch, String> {
    if !remote {
        return Ok(daemon::Launch::default());
    }
    let state = state.ok_or("phone access needs the daemon sidecar")?;
    Ok(daemon::Launch { remote: true, token_file: Some(daemon::remote_token_file(state)?) })
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
    let mut note: Option<String> = None;
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
                    Ok(s) => Ok((s.url.clone(), s.note.clone())),
                    Err(e) => Err(e.clone()),
                }
            })
            .await
            .map_err(|e| e.to_string())?;
            match r {
                Ok((url, n)) => {
                    note = n;
                    (url, state.remote_token(), None, "daemon")
                }
                Err(e) => ("ws://127.0.0.1:0/ws".into(), None, Some(e), "daemon"),
            }
        }
    };
    Ok(Endpoint { url, token, stress, local: mode == "embedded", mode, error, note, perf })
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
        .plugin(tauri_plugin_updater::Builder::new().build())
        .setup(|app| {
            // The runner's own files (per-session hook settings, the scratch folder). INFANTRY_STATE_DIR pins it for tests.
            let dir = env("STATE_DIR").map(std::path::PathBuf::from).unwrap_or_else(|| {
                app.path().app_local_data_dir().unwrap_or_else(|_| std::env::temp_dir().join("infantry-tauri"))
            });
            let _ = std::fs::create_dir_all(&dir);
            let settings = load_settings(&dir);
            let mode = mode(&settings);
            let sidecar: SidecarCell = Arc::new((Mutex::new(None), Condvar::new()));
            let cwd = std::env::current_dir().unwrap_or_else(|_| dir.clone());
            let port = env("UI_DAEMON_PORT").and_then(|p| p.parse().ok()).or(settings.daemon_port).unwrap_or(daemon::DEFAULT_PORT);
            let mut daemon_state = None;
            let paused = Arc::new(AtomicBool::new(false));
            let exiting = Arc::new(AtomicBool::new(false));
            let settings_remote = settings.remote_access.unwrap_or(false);
            crate::update::keep_cli_path(settings.add_cli.unwrap_or(false));
            if mode == Mode::Daemon {
                // INFANTRY_UI_DAEMON_PORT (above) pins the sidecar's port (tests use 189xx; 0 = any free port), else 18990.
                // INFANTRY_UI_DAEMON_STATE_DIR, else ui.json `daemonStateDir`, else %LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust
                // (the state `infantry-migrate` fills and start.cmd uses), else <app data>/daemon.
                let state = env("UI_DAEMON_STATE_DIR")
                    .or_else(|| settings.daemon_state_dir.clone())
                    .filter(|s| !s.trim().is_empty())
                    .map(PathBuf::from)
                    .or_else(daemon::default_state_dir)
                    .unwrap_or_else(|| dir.join("daemon"));
                daemon_state = Some(state.clone());
                let cell = sidecar.clone();
                let cwd = cwd.clone();
                let launch = launch_for(settings.remote_access.unwrap_or(false), Some(&state));
                let sup = settings.supervisor.clone().unwrap_or_default();
                let (paused2, exiting2) = (paused.clone(), exiting.clone());
                let handle = app.handle().clone();
                std::thread::spawn(move || {
                    let r = launch.and_then(|l| daemon::ensure(&state, port, &cwd, &l, sup.log_keep()));
                    if let Err(e) = &r {
                        eprintln!("[infantry] daemon mode: {e}");
                        crate::supervisor::journal(&state, "daemon_start_failed", "app start", e);
                    }
                    let ok = r.is_ok();
                    let (m, cv) = &*cell;
                    *m.lock().unwrap() = Some(r);
                    cv.notify_all();
                    if ok {
                        // RUST-BUG 2617b342: from here the app restarts the daemon if it dies without a clean stop.
                        let remote = settings_remote;
                        crate::supervisor::run(crate::supervisor::Watch {
                            state: state.clone(),
                            port,
                            cwd,
                            settings: sup,
                            launch: Box::new(move || launch_for(remote, Some(&state))),
                            cell: cell.clone(),
                            paused: paused2,
                            exiting: exiting2,
                            notify: Box::new(move |n| {
                                let _ = handle.emit("daemon-notice", n);
                            }),
                        });
                    }
                });
            }
            app.manage(AppState { dir: dir.clone(), mode, settings: Mutex::new(settings), sidecar, daemon_state, daemon_port: port, cwd, paused, exiting });
            crate::update::manage(app);
            app.manage(Local::new(dir));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![endpoint, ui_runner_get, ui_runner_set, local_connect, local_send, local_set_project, local_resumable, perf_report,
            crate::update::update_settings_get, crate::update::update_settings_set, crate::update::update_check, crate::update::update_install,
            crate::update::cli_path_get, crate::update::cli_path_set, crate::pairing::pairing_get, crate::pairing::pairing_set])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app, e| {
            // Every session's process tree dies with the app (Job Objects); dropping them first makes it prompt.
            if let tauri::RunEvent::Exit = e {
                app.state::<AppState>().exiting.store(true, Ordering::SeqCst);
                app.state::<Local>().shutdown();
            }
        });
}
