// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-UPD-1: installing and updating the desktop app, after Kotlin's Infantry_Updater.
//!
//! Updates: the Tauri updater plugin reads a `latest.json` feed. The feed URL and the channel (stable | nightly) are app
//! settings in `ui.json` (`updater.feedUrl`, `updater.channel`); `{channel}` in the URL becomes the channel. The default
//! is a check with an "update available" notice, never an install the user did not ask for (`updater.autoInstall` false).
//! An update is verified against the public key in tauri.conf.json `plugins.updater.pubkey`; until the owner sets one
//! (UPDATER.md), install is refused. No key and no update token live in the repo.
//!
//! Install order: download and verify, stop the daemon sidecar by its PID, run the installer (it replaces the files and
//! starts the app again, whose start brings the daemon back). An install that fails after the stop restarts the daemon.
//!
//! The CLI on PATH: opt-in (`paths.addCli` in ui.json, default off). On, the app runs `infantry install-cli` from its own
//! folder at every start (keeps the entry current); off runs `uninstall-cli`. The uninstaller removes it too.

use crate::daemon;
use crate::desktop::AppState;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::Mutex;
use tauri::{AppHandle, Manager, State};
use tauri_plugin_updater::{Update, UpdaterExt};

pub use crate::feed::{nightly_feed, source_for, Source, UpdaterSettings, CHANNELS, DEFAULT_FEED};
/// tauri.conf.json carries this until the owner generates the signing key pair (UPDATER.md).
pub const PUBKEY_PLACEHOLDER: &str = "OWNER-SETS-THE-UPDATER-PUBLIC-KEY";

/// Whether the build carries a real public key (not the placeholder, not empty).
pub fn signed(pubkey: Option<&str>) -> bool {
    pubkey.map(str::trim).is_some_and(|k| !k.is_empty() && k != PUBKEY_PLACEHOLDER)
}

fn pubkey(app: &AppHandle) -> Option<String> {
    app.config().plugins.0.get("updater").and_then(|u| u.get("pubkey")).and_then(|k| k.as_str()).map(str::to_string)
}

/// The folder of this exe, when it holds the installed `infantry` launcher (an installed app; a dev build has none).
fn install_dir() -> Option<PathBuf> {
    let dir = std::env::current_exe().ok()?.parent()?.to_path_buf();
    dir.join(if cfg!(windows) { "infantry.exe" } else { "infantry" }).is_file().then_some(dir)
}

/// The update found by the last check, kept for install.
#[derive(Default)]
pub struct Pending(Mutex<Option<Update>>);

/// ARMORY-3: an update whose files are already downloaded and verified, waiting for the user's "Relaunch to update" click.
#[derive(Default)]
pub struct Staged(Mutex<Option<(Update, Vec<u8>)>>);

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdaterView {
    feed_url: String,
    default_feed_url: &'static str,
    channel: String,
    channels: [&'static str; 2],
    auto_check: bool,
    auto_install: bool,
    /// Minutes between automatic checks while the app runs (0 = only at start).
    check_minutes: u32,
    /// A real public key is configured, so an update can be installed.
    signed: bool,
    current: String,
}

#[tauri::command]
pub fn update_settings_get(app: AppHandle, state: State<'_, AppState>) -> UpdaterView {
    let s = state.settings.lock().unwrap().updater.clone().unwrap_or_default();
    UpdaterView {
        feed_url: s.feed_template().to_string(),
        default_feed_url: DEFAULT_FEED,
        channel: s.channel().to_string(),
        channels: CHANNELS,
        auto_check: s.auto_check.unwrap_or(true),
        auto_install: s.auto_install.unwrap_or(false),
        check_minutes: s.check_minutes(),
        signed: signed(pubkey(&app).as_deref()),
        current: app.package_info().version.to_string(),
    }
}

/// Changes the updater settings; an absent field keeps its value, an empty feed URL goes back to the default.
#[tauri::command]
pub fn update_settings_set(
    state: State<'_, AppState>,
    feed_url: Option<String>,
    channel: Option<String>,
    auto_check: Option<bool>,
    auto_install: Option<bool>,
    check_minutes: Option<u32>,
) -> Result<(), String> {
    if let Some(c) = channel.as_deref() {
        if !CHANNELS.contains(&c) {
            return Err(format!("unknown channel `{c}` (stable | nightly)"));
        }
    }
    if let Some(u) = feed_url.as_deref().map(str::trim).filter(|u| !u.is_empty()) {
        if !u.starts_with("https://") && !u.starts_with("http://127.0.0.1") && !u.starts_with("http://localhost") {
            return Err("the feed must be an https:// URL (http only on this machine)".into());
        }
    }
    state.update(|s| {
        let mut u = s.updater.clone().unwrap_or_default();
        if let Some(f) = feed_url {
            u.feed_url = Some(f.trim().to_string()).filter(|f| !f.is_empty());
        }
        if channel.is_some() {
            u.channel = channel;
        }
        if auto_check.is_some() {
            u.auto_check = auto_check;
        }
        if auto_install.is_some() {
            u.auto_install = auto_install;
        }
        if check_minutes.is_some() {
            u.check_minutes = check_minutes;
        }
        s.updater = Some(u);
    })
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateInfo {
    available: bool,
    current: String,
    version: Option<String>,
    notes: Option<String>,
    date: Option<String>,
    channel: String,
    /// Install would be refused (no public key configured); the notice says so.
    can_install: bool,
}

#[tauri::command]
pub async fn update_check(app: AppHandle, state: State<'_, AppState>, pending: State<'_, Pending>) -> Result<UpdateInfo, String> {
    let s = state.settings.lock().unwrap().updater.clone().unwrap_or_default();
    let feed = match source_for(s.feed_template(), s.channel()) {
        Source::Manifest(u) => u,
        Source::ReleaseList(api) => {
            let text = reqwest::Client::new()
                .get(&api)
                .header("User-Agent", "infantry-ai-updater")
                .header("Accept", "application/vnd.github+json")
                .send()
                .await
                .and_then(|r| r.error_for_status())
                .map_err(|e| format!("update check failed: GitHub releases: {e}"))?
                .text()
                .await
                .map_err(|e| format!("update check failed: {e}"))?;
            nightly_feed(&text)?
        }
    };
    let url: tauri::Url = feed.parse().map_err(|e| format!("feed URL {feed}: {e}"))?;
    // Strictly newer only, on every channel: a feed that lags behind must never downgrade the app (ticket f740f0f6).
    // (The plugin's default comparator is already `remote > current`; it is set here so the rule is explicit and tested.)
    let b = app
        .updater_builder()
        .endpoints(vec![url])
        .map_err(|e| e.to_string())?
        .version_comparator(|current, remote| remote.version > current);
    let found = b.build().map_err(|e| e.to_string())?.check().await.map_err(|e| format!("update check failed: {e}"))?;
    let info = UpdateInfo {
        available: found.is_some(),
        current: app.package_info().version.to_string(),
        version: found.as_ref().map(|u| u.version.clone()),
        notes: found.as_ref().and_then(|u| u.body.clone()),
        date: found.as_ref().and_then(|u| u.date.map(|d| d.to_string())),
        channel: s.channel().to_string(),
        can_install: signed(pubkey(&app).as_deref()),
    };
    *pending.0.lock().unwrap() = found;
    Ok(info)
}

/// ARMORY-3: downloads (and verifies) the update the last check found, in the background, and keeps it. Nothing is
/// installed and nothing restarts: [`update_install`] does that, on the user's click.
#[tauri::command]
pub async fn update_download(app: AppHandle, pending: State<'_, Pending>, staged: State<'_, Staged>) -> Result<String, String> {
    if !signed(pubkey(&app).as_deref()) {
        return Err("this build has no updater public key, so an update cannot be verified; see UPDATER.md".into());
    }
    let Some(update) = pending.0.lock().unwrap().take() else {
        return Err("no update to download: check for updates first".into());
    };
    let bytes = match update.download(|_, _| {}, || {}).await {
        Ok(b) => b,
        Err(e) => {
            *pending.0.lock().unwrap() = Some(update);
            return Err(format!("download failed: {e}"));
        }
    };
    let version = update.version.clone();
    *staged.0.lock().unwrap() = Some((update, bytes));
    Ok(version)
}

/// Installs the update: the one [`update_download`] kept, or (no download yet) the one the last check found, downloaded
/// now. Stops the daemon by PID, runs the installer.
#[tauri::command]
pub async fn update_install(app: AppHandle, state: State<'_, AppState>, pending: State<'_, Pending>, staged: State<'_, Staged>) -> Result<(), String> {
    if !signed(pubkey(&app).as_deref()) {
        return Err("this build has no updater public key, so an update cannot be verified; see UPDATER.md".into());
    }
    let kept = staged.0.lock().unwrap().take();
    let (update, bytes) = match kept {
        Some(k) => k,
        None => {
            let Some(update) = pending.0.lock().unwrap().take() else {
                return Err("no update to install: check for updates first".into());
            };
            let bytes = update.download(|_, _| {}, || {}).await.map_err(|e| format!("download failed: {e}"))?;
            (update, bytes)
        }
    };
    // The app stops the daemon itself here: the supervisor must not call that a death.
    state.paused.store(true, std::sync::atomic::Ordering::SeqCst);
    let stopped = match state.daemon_state.as_ref() {
        Some(dir) => {
            let dir = dir.clone();
            match tauri::async_runtime::spawn_blocking(move || daemon::stop(&dir)).await.map_err(|e| e.to_string()).and_then(|r| r) {
                Ok(s) => s,
                Err(e) => {
                    state.paused.store(false, std::sync::atomic::Ordering::SeqCst);
                    return Err(e);
                }
            }
        }
        None => None,
    };
    // On Windows the installer takes over and this process exits; reaching the error arm means it did not start.
    if let Err(e) = update.install(bytes) {
        state.paused.store(false, std::sync::atomic::Ordering::SeqCst);
        if stopped.is_some() {
            let _ = state.restart_daemon();
        }
        return Err(format!("the installer did not start: {e}"));
    }
    app.restart();
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CliPathView {
    /// This is an installed app (the launcher sits beside it).
    available: bool,
    on: bool,
    dir: Option<String>,
}

#[tauri::command]
pub fn cli_path_get(state: State<'_, AppState>) -> CliPathView {
    let dir = install_dir();
    CliPathView { available: dir.is_some(), on: state.settings.lock().unwrap().add_cli.unwrap_or(false), dir: dir.map(|d| d.display().to_string()) }
}

/// Turns `paths.addCli` on (adds the app folder to the user PATH) or off (removes it). Answers the launcher's report.
#[tauri::command]
pub fn cli_path_set(state: State<'_, AppState>, on: bool) -> Result<String, String> {
    let dir = install_dir().ok_or("only an installed app can put `infantry` on PATH")?;
    let out = run_cli_path(&dir, on)?;
    state.update(|s| s.add_cli = Some(on))?;
    Ok(out)
}

fn run_cli_path(dir: &std::path::Path, on: bool) -> Result<String, String> {
    let exe = dir.join(if cfg!(windows) { "infantry.exe" } else { "infantry" });
    let mut cmd = std::process::Command::new(&exe);
    cmd.args([if on { "install-cli" } else { "uninstall-cli" }, "--dir"]).arg(dir);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000);
    }
    let out = cmd.output().map_err(|e| format!("{}: {e}", exe.display()))?;
    let text = format!("{}{}", String::from_utf8_lossy(&out.stdout), String::from_utf8_lossy(&out.stderr)).trim().to_string();
    if out.status.success() { Ok(text) } else { Err(text) }
}

/// At start: `paths.addCli` on keeps the PATH entry current (the folder may have moved with a reinstall).
pub fn keep_cli_path(on: bool) {
    if let (true, Some(dir)) = (on, install_dir()) {
        std::thread::spawn(move || {
            if let Err(e) = run_cli_path(&dir, true) {
                eprintln!("[infantry] install-cli: {e}");
            }
        });
    }
}

pub fn manage(app: &tauri::App) {
    app.manage(Pending::default());
    app.manage(Staged::default());
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_placeholder_key_is_not_a_signature_key() {
        assert!(!signed(None));
        assert!(!signed(Some(PUBKEY_PLACEHOLDER)));
        assert!(!signed(Some("  ")));
        assert!(signed(Some("dW50cnVzdGVkIGNvbW1lbnQ6IG1pbmlzaWduIHB1YmxpYyBrZXk=")));
    }
}
