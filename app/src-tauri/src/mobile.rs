// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-MOBILE-1: the phone shell. No daemon and no agents run here: the app connects to a remote daemon, like the Kotlin
//! infantryAndroid shell. The address (and a display name) live in `<app data>/remote.json`; the token lives in the
//! platform keystore (Android: an AES key held by the Android Keystore, `KeystorePlugin.kt`), never in a plain file.
//! The WebView pairs with `remote_save` (a pasted `infantry://connect?url=..&token=..` link, a scanned QR code, or the
//! two fields) and reads it back through `endpoint` with mode `remote`.

use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use tauri::{AppHandle, Manager};

/// The keystore entry of the daemon token.
const TOKEN_KEY: &str = "daemon.token";

#[derive(Serialize)]
struct Endpoint {
    /// Empty until the phone is paired; the WebView then shows the connect screen.
    url: String,
    token: Option<String>,
    stress: bool,
    local: bool,
    mode: &'static str,
    error: Option<String>,
    perf: Option<String>,
    /// The paired daemon's display name, when the link carried one.
    name: Option<String>,
    /// The phone keeps the plugin socket (its WebView rules are untested for a direct one): INFANTRY_UI_TRANSPORT is a desktop knob.
    transport: &'static str,
}

#[derive(Serialize, Deserialize, Default)]
struct Remote {
    #[serde(default)]
    url: Option<String>,
    #[serde(default)]
    name: Option<String>,
}

fn remote_path(app: &AppHandle) -> Result<PathBuf, String> {
    let dir = app.path().app_local_data_dir().map_err(|e| e.to_string())?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    Ok(dir.join("remote.json"))
}

fn load(app: &AppHandle) -> Remote {
    remote_path(app).ok().and_then(|p| std::fs::read_to_string(p).ok()).and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default()
}

#[cfg(target_os = "android")]
mod keystore {
    use serde::{Deserialize, Serialize};
    use tauri::plugin::{Builder, PluginHandle, TauriPlugin};
    use tauri::{Manager, Wry};

    /// The app's own Kotlin plugin (gen/android/.../KeystorePlugin.kt).
    pub struct Keystore(PluginHandle<Wry>);

    #[derive(Serialize)]
    struct Key<'a> {
        key: &'a str,
    }
    #[derive(Serialize)]
    struct Put<'a> {
        key: &'a str,
        value: &'a str,
    }
    #[derive(Deserialize)]
    struct Got {
        value: Option<String>,
    }

    pub fn init() -> TauriPlugin<Wry> {
        Builder::new("keystore")
            .setup(|app, api| {
                let handle = api.register_android_plugin("com.superiorsoft.infantry", "KeystorePlugin")?;
                app.manage(Keystore(handle));
                Ok(())
            })
            .build()
    }

    impl Keystore {
        pub fn get(&self, key: &str) -> Result<Option<String>, String> {
            self.0.run_mobile_plugin::<Got>("get", Key { key }).map(|g| g.value.filter(|v| !v.is_empty())).map_err(|e| e.to_string())
        }
        pub fn put(&self, key: &str, value: &str) -> Result<(), String> {
            self.0.run_mobile_plugin::<serde_json::Value>("put", Put { key, value }).map(|_| ()).map_err(|e| e.to_string())
        }
        pub fn remove(&self, key: &str) -> Result<(), String> {
            self.0.run_mobile_plugin::<serde_json::Value>("remove", Key { key }).map(|_| ()).map_err(|e| e.to_string())
        }
    }
}

#[cfg(target_os = "android")]
fn token_get(app: &AppHandle) -> Result<Option<String>, String> {
    app.state::<keystore::Keystore>().get(TOKEN_KEY)
}
#[cfg(target_os = "android")]
fn token_set(app: &AppHandle, token: Option<&str>) -> Result<(), String> {
    let ks = app.state::<keystore::Keystore>();
    match token {
        Some(t) => ks.put(TOKEN_KEY, t),
        None => ks.remove(TOKEN_KEY),
    }
}
// iOS: the Keychain plugin is not written yet (no Mac to build it); a token is refused rather than kept in a file.
#[cfg(not(target_os = "android"))]
fn token_get(_: &AppHandle) -> Result<Option<String>, String> {
    Ok(None)
}
#[cfg(not(target_os = "android"))]
fn token_set(_: &AppHandle, token: Option<&str>) -> Result<(), String> {
    match token {
        Some(_) => Err("this platform has no keystore support yet; pair with a daemon that needs no token".into()),
        None => Ok(()),
    }
}

#[tauri::command]
fn endpoint(app: AppHandle) -> Endpoint {
    let r = load(&app);
    let (token, error) = match token_get(&app) {
        Ok(t) => (t, None),
        Err(e) => (None, Some(format!("the token could not be read from the keystore: {e}"))),
    };
    Endpoint { url: r.url.unwrap_or_default(), token, stress: false, local: false, mode: "remote", error, perf: None, name: r.name, transport: "plugin" }
}

/// Pairs with a daemon: keeps the address and name, puts the token in the keystore (none = removes it). Answers the
/// socket URL it saved.
#[tauri::command]
fn remote_save(app: AppHandle, url: String, token: Option<String>, name: Option<String>) -> Result<String, String> {
    if url.trim().is_empty() {
        return Err("a daemon address is required".into());
    }
    let url = crate::ws_url(&url);
    token_set(&app, token.as_deref().map(str::trim).filter(|t| !t.is_empty()))?;
    let text = serde_json::to_string_pretty(&Remote { url: Some(url.clone()), name: name.filter(|n| !n.trim().is_empty()) }).map_err(|e| e.to_string())?;
    std::fs::write(remote_path(&app)?, text).map_err(|e| e.to_string())?;
    Ok(url)
}

/// Asks the saved PC (the address in `remote.json`, the token from the keystore) whether it answers and takes this phone's
/// login. `ok` | `unreachable` | `refused`. Never throws: an unreadable token is the endpoint's `error`, not this.
#[tauri::command]
async fn remote_check(app: AppHandle) -> String {
    let r = load(&app);
    let Some(url) = r.url.filter(|u| !u.trim().is_empty()) else { return crate::remote_reach::Reach::Unreachable.word().into() };
    let token = token_get(&app).unwrap_or(None);
    tauri::async_runtime::spawn_blocking(move || crate::remote_reach::reach(&url, token.as_deref()).word().to_string()).await.unwrap_or_else(|_| crate::remote_reach::Reach::Unreachable.word().to_string())
}

/// Forgets the paired daemon: the address file and the keystore entry.
#[tauri::command]
fn remote_forget(app: AppHandle) -> Result<(), String> {
    token_set(&app, None)?;
    match std::fs::remove_file(remote_path(&app)?) {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(e.to_string()),
        _ => Ok(()),
    }
}

pub fn run() {
    let builder = tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_websocket::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_barcode_scanner::init());
    #[cfg(target_os = "android")]
    let builder = builder.plugin(keystore::init()).plugin(crate::android_update::init());
    // RUST-UPD-2: Android adds the update commands (the same names the desktop has); iOS has no updater.
    #[cfg(target_os = "android")]
    let builder = builder.invoke_handler(tauri::generate_handler![
        endpoint,
        remote_save,
        remote_forget,
        remote_check,
        crate::daemon_http::daemon_get,
        crate::android_update::update_settings_get,
        crate::android_update::update_settings_set,
        crate::android_update::update_check,
        crate::android_update::update_install,
        crate::android_update::update_progress
    ]);
    #[cfg(not(target_os = "android"))]
    let builder = builder.invoke_handler(tauri::generate_handler![endpoint, remote_save, remote_forget, remote_check, crate::daemon_http::daemon_get]);
    builder
        .setup(|app| {
            // The scanner's permission is granted here, not in capabilities/ (the desktop build has no scanner plugin).
            app.add_capability(include_str!("../mobile-capabilities/mobile.json"))?;
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

