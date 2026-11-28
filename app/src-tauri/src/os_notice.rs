// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! PARITY-AUDIT-1 #5: an OS notification for a notice the bus routed to the `os` channel (the page decides which, and
//! collapses repeats: @infantry/client `createOsNotifier`). Showing one never touches the window; a click on it brings
//! the window up and emits `os-notice-click` with the notice's key so the page opens the ticket or chat it names.
//! Windows only for now (the desktop builds are Windows): the toast is WinRT's (tauri-winrt-notification, what
//! tauri-plugin-notification uses on Windows), called directly because that plugin reports no click on desktop.
use tauri::{AppHandle, Runtime};

#[tauri::command]
pub fn os_notify<R: Runtime>(app: AppHandle<R>, title: String, body: String, key: String) -> Result<(), String> {
    show(app, title, body, key)
}

#[cfg(windows)]
fn show<R: Runtime>(app: AppHandle<R>, title: String, body: String, key: String) -> Result<(), String> {
    use tauri::{Emitter, Manager};
    use tauri_winrt_notification::Toast;
    // A dev run has no registered app id (no installed shortcut); the PowerShell one shows the toast (as the plugin does).
    let id = if tauri::is_dev() { Toast::POWERSHELL_APP_ID.to_string() } else { app.config().identifier.clone() };
    let handle = app.clone();
    Toast::new(&id)
        .title(&title)
        .text1(&body)
        .on_activated(move |_| {
            if let Some(w) = handle.webview_windows().values().next() {
                let _ = w.unminimize();
                let _ = w.show();
                let _ = w.set_focus();
            }
            let _ = handle.emit("os-notice-click", key.clone());
            Ok(())
        })
        .show()
        .map_err(|e| e.to_string())
}

#[cfg(not(windows))]
fn show<R: Runtime>(_app: AppHandle<R>, _title: String, _body: String, _key: String) -> Result<(), String> {
    Err("OS notifications are Windows only for now".into())
}
