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

/// The feed when the user set none: one `latest.json` per channel on the product's public releases.
pub const DEFAULT_FEED: &str = "https://github.com/SuperiorSoft-LLC/infantry-ai/releases/download/rust-{channel}/latest.json";
/// tauri.conf.json carries this until the owner generates the signing key pair (UPDATER.md).
pub const PUBKEY_PLACEHOLDER: &str = "OWNER-SETS-THE-UPDATER-PUBLIC-KEY";
pub const CHANNELS: [&str; 2] = ["stable", "nightly"];

/// `ui.json` `updater`.
#[derive(Serialize, Deserialize, Default, Clone)]
#[serde(rename_all = "camelCase")]
pub struct UpdaterSettings {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub feed_url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub channel: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub auto_check: Option<bool>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub auto_install: Option<bool>,
}

impl UpdaterSettings {
    pub fn channel(&self) -> &str {
        self.channel.as_deref().filter(|c| CHANNELS.contains(c)).unwrap_or("stable")
    }
    pub fn feed_template(&self) -> &str {
        self.feed_url.as_deref().map(str::trim).filter(|u| !u.is_empty()).unwrap_or(DEFAULT_FEED)
    }
}

/// The feed for `channel`: `{channel}` replaced.
pub fn feed_for(template: &str, channel: &str) -> String {
    template.replace("{channel}", channel)
}

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

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdaterView {
    feed_url: String,
    default_feed_url: &'static str,
    channel: String,
    channels: [&'static str; 2],
    auto_check: bool,
    auto_install: bool,
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
    let feed = feed_for(s.feed_template(), s.channel());
    let url: tauri::Url = feed.parse().map_err(|e| format!("feed URL {feed}: {e}"))?;
    let mut b = app.updater_builder().endpoints(vec![url]).map_err(|e| e.to_string())?;
    // Switching from nightly back to stable offers the newest stable even when it is older (Kotlin NIGHTLY-1).
    if s.channel() == "stable" {
        b = b.version_comparator(|current, remote| remote.version != current);
    }
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

/// Installs the update the last check found: download and verify, stop the daemon by PID, run the installer.
#[tauri::command]
pub async fn update_install(app: AppHandle, state: State<'_, AppState>, pending: State<'_, Pending>) -> Result<(), String> {
    if !signed(pubkey(&app).as_deref()) {
        return Err("this build has no updater public key, so an update cannot be verified; see UPDATER.md".into());
    }
    let Some(update) = pending.0.lock().unwrap().take() else {
        return Err("no update to install: check for updates first".into());
    };
    let bytes = update.download(|_, _| {}, || {}).await.map_err(|e| format!("download failed: {e}"))?;
    let stopped = match state.daemon_state.as_ref() {
        Some(dir) => {
            let dir = dir.clone();
            tauri::async_runtime::spawn_blocking(move || daemon::stop(&dir)).await.map_err(|e| e.to_string())??
        }
        None => None,
    };
    // On Windows the installer takes over and this process exits; reaching the error arm means it did not start.
    if let Err(e) = update.install(bytes) {
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
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_channel_fills_the_feed_and_unknown_channels_fall_back_to_stable() {
        let s = UpdaterSettings { channel: Some("nightly".into()), ..Default::default() };
        assert_eq!(feed_for(s.feed_template(), s.channel()), DEFAULT_FEED.replace("{channel}", "nightly"));
        let odd = UpdaterSettings { channel: Some("beta".into()), feed_url: Some("  ".into()), ..Default::default() };
        assert_eq!(odd.channel(), "stable");
        assert_eq!(odd.feed_template(), DEFAULT_FEED);
        assert_eq!(feed_for("https://x/{channel}/latest.json", "stable"), "https://x/stable/latest.json");
    }

    #[test]
    fn the_placeholder_key_is_not_a_signature_key() {
        assert!(!signed(None));
        assert!(!signed(Some(PUBKEY_PLACEHOLDER)));
        assert!(!signed(Some("  ")));
        assert!(signed(Some("dW50cnVzdGVkIGNvbW1lbnQ6IG1pbmlzaWduIHB1YmxpYyBrZXk=")));
    }
}
