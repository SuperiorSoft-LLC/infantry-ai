// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The login window: a window of the app that shows a harness's login page (claude.ai refuses framing, so it is a window of its
//! own, not an iframe). The window carries no capability, so the page can call none of the app's commands. The app watches its
//! navigation and tells the main window every web address it reaches; the UI reads the page's code from the harness's declared
//! address (`loginCodeFromUrl`) and hands the login to the system browser when the address leaves the declared hosts. Nothing here names a harness.
use tauri::{Emitter, Manager, WebviewUrl, WebviewWindowBuilder};

const LABEL: &str = "login-window";
/// Sent to the main window with the address (a string) the login window reached.
pub const URL_EVENT: &str = "login-window-url";
/// Sent to the main window when the login window is gone.
pub const CLOSED_EVENT: &str = "login-window-closed";
const WIDTH: f64 = 520.0;
const HEIGHT: f64 = 760.0;

#[tauri::command]
pub async fn login_window_open(app: tauri::AppHandle, url: String) -> Result<(), String> {
    let target = tauri::Url::parse(&url).map_err(|e| format!("not a web address: {e}"))?;
    if target.scheme() != "https" && target.scheme() != "http" {
        return Err("only web addresses open in the login window".into());
    }
    if let Some(old) = app.get_webview_window(LABEL) {
        let _ = old.close();
    }
    let seen = app.clone();
    let win = WebviewWindowBuilder::new(&app, LABEL, WebviewUrl::External(target))
        .title("Log in")
        .inner_size(WIDTH, HEIGHT)
        .center()
        .on_navigation(move |u| {
            if matches!(u.scheme(), "http" | "https") {
                let _ = seen.emit(URL_EVENT, u.to_string());
            }
            true
        })
        .build()
        .map_err(|e| e.to_string())?;
    let closed = app.clone();
    win.on_window_event(move |e| {
        if matches!(e, tauri::WindowEvent::Destroyed) {
            let _ = closed.emit(CLOSED_EVENT, ());
        }
    });
    Ok(())
}

#[tauri::command]
pub async fn login_window_close(app: tauri::AppHandle) -> Result<(), String> {
    if let Some(w) = app.get_webview_window(LABEL) {
        w.close().map_err(|e| e.to_string())?;
    }
    Ok(())
}
