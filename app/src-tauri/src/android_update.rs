// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-UPD-2: the phone's in-app update. The desktop uses the Tauri updater plugin; Android has none, so the phone reads
//! the same `latest.json` (feed rules in `feed.rs`) and a small Kotlin plugin (`UpdatePlugin.kt`) fetches the feed text,
//! downloads the APK and hands it to the system package installer. The WebView calls the SAME command names the desktop
//! has (`update_settings_get`, `update_settings_set`, `update_check`, `update_install`) plus `update_progress`, which it
//! polls while a download runs, so Settings > About is one component for both.

use crate::feed::{android_offer, feed_allowed, nightly_feed, source_for, Offer, Source, UpdaterSettings, CHANNELS, DEFAULT_FEED};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::Mutex;
use tauri::plugin::{Builder, PluginHandle, TauriPlugin};
use tauri::{AppHandle, Manager, State, Wry};

/// The Kotlin plugin (gen/android/.../UpdatePlugin.kt).
pub struct Updater(PluginHandle<Wry>);

/// The offer the last check found, kept for install.
#[derive(Default)]
pub struct Pending(Mutex<Option<Offer>>);

#[derive(Serialize)]
struct Url<'a> {
    url: &'a str,
}
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct DownloadArgs<'a> {
    url: &'a str,
    sha256: &'a str,
    size: Option<u64>,
    version: &'a str,
}
#[derive(Deserialize)]
struct Text {
    text: String,
}
#[derive(Deserialize)]
struct Abi {
    abi: String,
}

pub fn init() -> TauriPlugin<Wry> {
    Builder::new("updater_android")
        .setup(|app, api| {
            let handle = api.register_android_plugin("com.superiorsoft.infantry", "UpdatePlugin")?;
            app.manage(Updater(handle));
            app.manage(Pending::default());
            Ok(())
        })
        .build()
}

fn settings_path(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app.path().app_local_data_dir().map_err(|e| e.to_string())?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    Ok(dir.join("updater.json"))
}

fn load(app: &AppHandle) -> UpdaterSettings {
    settings_path(app).ok().and_then(|p| std::fs::read_to_string(p).ok()).and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default()
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdaterView {
    feed_url: String,
    default_feed_url: &'static str,
    channel: String,
    channels: [&'static str; 2],
    auto_check: bool,
    auto_install: bool,
    check_minutes: u32,
    /// Android verifies the APK's signing key itself, and the download is checked against the feed's sha256.
    signed: bool,
    current: String,
}

#[tauri::command]
pub fn update_settings_get(app: AppHandle) -> UpdaterView {
    let s = load(&app);
    UpdaterView {
        feed_url: s.feed_template().to_string(),
        default_feed_url: DEFAULT_FEED,
        channel: s.channel().to_string(),
        channels: CHANNELS,
        auto_check: s.auto_check.unwrap_or(true),
        auto_install: false,
        check_minutes: s.check_minutes(),
        signed: true,
        current: app.package_info().version.to_string(),
    }
}

#[tauri::command]
pub fn update_settings_set(
    app: AppHandle,
    feed_url: Option<String>,
    channel: Option<String>,
    auto_check: Option<bool>,
    _auto_install: Option<bool>,
    check_minutes: Option<u32>,
) -> Result<(), String> {
    if let Some(c) = channel.as_deref() {
        if !CHANNELS.contains(&c) {
            return Err(format!("unknown channel `{c}` (stable | nightly)"));
        }
    }
    if let Some(u) = feed_url.as_deref().map(str::trim).filter(|u| !u.is_empty()) {
        if !feed_allowed(u) {
            return Err("the feed must be an https:// URL (http only on this machine)".into());
        }
    }
    let mut u = load(&app);
    if let Some(f) = feed_url {
        u.feed_url = Some(f.trim().to_string()).filter(|f| !f.is_empty());
    }
    if channel.is_some() {
        u.channel = channel;
    }
    if auto_check.is_some() {
        u.auto_check = auto_check;
    }
    if check_minutes.is_some() {
        u.check_minutes = check_minutes;
    }
    let text = serde_json::to_string_pretty(&u).map_err(|e| e.to_string())?;
    std::fs::write(settings_path(&app)?, text).map_err(|e| e.to_string())
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
    can_install: bool,
}

#[tauri::command]
pub async fn update_check(app: AppHandle, updater: State<'_, Updater>, pending: State<'_, Pending>) -> Result<UpdateInfo, String> {
    let s = load(&app);
    let current = app.package_info().version.to_string();
    let abi = updater.0.run_mobile_plugin_async::<Abi>("abi", ()).await.map_err(|e| e.to_string())?.abi;
    let feed = match source_for(s.feed_template(), s.channel()) {
        Source::Manifest(u) => u,
        Source::ReleaseList(api) => {
            let list = updater.0.run_mobile_plugin_async::<Text>("fetch", Url { url: &api }).await.map_err(|e| e.to_string())?.text;
            nightly_feed(&list)?
        }
    };
    if !feed_allowed(&feed) {
        return Err(format!("the feed must be an https:// URL: {feed}"));
    }
    let text = updater.0.run_mobile_plugin_async::<Text>("fetch", Url { url: &feed }).await.map_err(|e| e.to_string())?.text;
    let offer = android_offer(&text, &current, s.channel(), &abi)?;
    let info = UpdateInfo {
        available: offer.is_some(),
        current,
        version: offer.as_ref().map(|o| o.version.clone()),
        notes: offer.as_ref().and_then(|o| o.notes.clone()),
        date: offer.as_ref().and_then(|o| o.date.clone()),
        channel: s.channel().to_string(),
        can_install: true,
    };
    *pending.0.lock().unwrap() = offer;
    Ok(info)
}

/// Starts the download of the offer the last check found; the installer opens when the APK is verified. Answers at once.
#[tauri::command]
pub async fn update_install(updater: State<'_, Updater>, pending: State<'_, Pending>) -> Result<(), String> {
    let Some(o) = pending.0.lock().unwrap().clone() else {
        return Err("no update to install: check for updates first".into());
    };
    updater
        .0
        .run_mobile_plugin_async::<serde_json::Value>("download", DownloadArgs { url: &o.url, sha256: &o.sha256, size: o.size, version: &o.version })
        .await
        .map(|_| ())
        .map_err(|e| e.to_string())
}

#[derive(Serialize, Deserialize)]
pub struct Progress {
    /// idle | downloading | verifying | installing | done | error
    state: String,
    done: u64,
    total: u64,
    message: String,
}

/// The running download, for the progress bar.
#[tauri::command]
pub async fn update_progress(updater: State<'_, Updater>) -> Result<Progress, String> {
    updater.0.run_mobile_plugin_async::<Progress>("progress", ()).await.map_err(|e| e.to_string())
}
