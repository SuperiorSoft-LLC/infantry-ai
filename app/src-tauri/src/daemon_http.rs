// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! WEBUI-1: reads, for the page, a file the connected daemon serves to the app: its web UI plugins' scripts and
//! stylesheets (`/ui-plugins/<id>/...`). The page's origin (tauri.localhost) is not the daemon's, and its CSP
//! (tauri.conf.json) lets it load no daemon origin, so this process makes the request: a plain GET with the window's
//! daemon token, the same on desktop and phone. Only `/ui-plugins/` paths are read (the rule of @infantry/client
//! `daemonGetRefusal`), and only from a daemon address (ws, wss, http or https).

use std::io::Read;

/// `ws://host:port/ws` (or `http://host:port`) -> `http://host:port`: where the daemon serves its HTTP routes.
pub(crate) fn http_base(url: &str) -> Result<String, String> {
    let u = url.trim();
    let base = if let Some(rest) = u.strip_prefix("ws://") {
        format!("http://{rest}")
    } else if let Some(rest) = u.strip_prefix("wss://") {
        format!("https://{rest}")
    } else if u.starts_with("http://") || u.starts_with("https://") {
        u.to_string()
    } else {
        return Err(format!("{url}: not a daemon address"));
    };
    let base = base.trim_end_matches('/');
    Ok(base.strip_suffix("/ws").unwrap_or(base).to_string())
}

/// None when [path] is one the page may read; else why not.
fn refusal(path: &str) -> Option<String> {
    if !path.starts_with("/ui-plugins/") {
        return Some(format!("{path}: only /ui-plugins/ can be read"));
    }
    if path.contains(['?', '#', '\\']) || path.contains("//") || path.split('/').any(|s| s == "." || s == "..") {
        return Some(format!("{path}: not a plain file path"));
    }
    None
}

fn fetch(target: &str, path: &str, token: Option<&str>) -> Result<Vec<u8>, String> {
    let mut req = ureq::get(target);
    if let Some(t) = token.filter(|t| !t.is_empty()) {
        req = req.set("Authorization", &format!("Bearer {t}"));
    }
    match req.call() {
        Ok(resp) => {
            let mut out = Vec::new();
            resp.into_reader().read_to_end(&mut out).map_err(|e| format!("{path}: {e}"))?;
            Ok(out)
        }
        Err(ureq::Error::Status(code, resp)) => {
            let body: String = resp.into_string().unwrap_or_default().chars().take(200).collect();
            Err(format!("{path}: HTTP {code}: {body}"))
        }
        Err(e) => Err(format!("{path}: {e}")),
    }
}

/// The bytes of daemon file [path] (a `/ui-plugins/` path), from the daemon at [url], with [token] when it has one.
#[tauri::command]
pub async fn daemon_get(url: String, token: Option<String>, path: String) -> Result<tauri::ipc::Response, String> {
    if let Some(why) = refusal(&path) {
        return Err(why);
    }
    let target = format!("{}{path}", http_base(&url)?);
    let bytes = tauri::async_runtime::spawn_blocking(move || fetch(&target, &path, token.as_deref())).await.map_err(|e| e.to_string())??;
    Ok(tauri::ipc::Response::new(bytes))
}

#[cfg(test)]
mod tests {
    use super::{http_base, refusal};

    #[test]
    fn a_daemon_address_becomes_its_http_base() {
        assert_eq!(http_base("ws://127.0.0.1:18990/ws").unwrap(), "http://127.0.0.1:18990");
        assert_eq!(http_base("wss://box.example/ws/").unwrap(), "https://box.example");
        assert_eq!(http_base("http://100.67.51.8:18990").unwrap(), "http://100.67.51.8:18990");
        assert!(http_base("file:///etc").is_err());
    }

    #[test]
    fn only_plain_ui_plugin_paths_are_read() {
        assert_eq!(refusal("/ui-plugins/Infantry_Triggers/ui/dist/index.js"), None);
        for bad in ["/avatars/x.png", "/ui-plugins/../health", "/ui-plugins/A/./x.js", "/ui-plugins/A//x.js", "/ui-plugins/A/x.js?y", "/ui-plugins/A\\x.js"] {
            assert!(refusal(bad).is_some(), "{bad} should be refused");
        }
    }
}
