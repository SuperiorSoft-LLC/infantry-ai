// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The login window: a window of the app that shows a harness's login page (claude.ai refuses framing, so it is a window of its
//! own, not an iframe). The window carries no capability, so the page can call none of the app's commands. The app watches its
//! navigation and tells the main window every address that starts with a prefix the harness declared; the page's code is read
//! from that address by the UI (`loginCodeFromUrl`). Nothing here names a harness.
use tauri::{Emitter, Manager, WebviewUrl, WebviewWindowBuilder};

const LABEL: &str = "login-window";
/// Sent to the main window with the address (a string) the login window reached.
pub const URL_EVENT: &str = "login-window-url";
/// Sent to the main window when the login window is gone.
pub const CLOSED_EVENT: &str = "login-window-closed";
const WIDTH: f64 = 520.0;
const HEIGHT: f64 = 760.0;

/// True when [url] is an address the harness declared: same origin and path (a trailing slash does not matter), any query.
pub fn watched(url: &str, watch: &[String]) -> bool {
    let Ok(u) = tauri::Url::parse(url) else { return false };
    let here = format!("{}{}", u.origin().ascii_serialization(), u.path());
    let here = here.trim_end_matches('/');
    watch.iter().any(|w| w.trim_end_matches('/') == here)
}

#[tauri::command]
pub async fn login_window_open(app: tauri::AppHandle, url: String, watch: Vec<String>) -> Result<(), String> {
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
            if watched(u.as_str(), &watch) {
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

#[cfg(test)]
mod tests {
    use super::watched;

    fn w() -> Vec<String> {
        vec!["https://platform.claude.com/oauth/code/callback".into()]
    }

    #[test]
    fn a_declared_address_is_watched_with_or_without_a_query_or_slash() {
        assert!(watched("https://platform.claude.com/oauth/code/callback?code=a&state=b", &w()));
        assert!(watched("https://platform.claude.com/oauth/code/callback/", &w()));
    }

    #[test]
    fn other_pages_and_look_alikes_are_not() {
        assert!(!watched("https://platform.claude.com/oauth/authorize?x=1", &w()));
        assert!(!watched("https://platform.claude.com/oauth/code/callback-evil?code=a", &w()));
        assert!(!watched("https://evil.example/oauth/code/callback?code=a", &w()));
        assert!(!watched("not a url", &w()));
        assert!(!watched("https://platform.claude.com/oauth/code/callback", &[]));
    }
}
