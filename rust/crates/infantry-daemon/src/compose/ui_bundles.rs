// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! WEBUI-1: the web UI halves of plugins, served over the daemon's own port to every app host (desktop, browser, phone).
//!
//! A module.json `ui` block declares one browser ES module and the slots it fills:
//! `{"apiVersion": 1, "entry": "ui/dist/index.js", "styles": ["ui/dist/index.css"], "assets": ["ui/dist/assets"],
//!   "contributes": [{"slot": "tab", "id": "acme.board", "label": "Board"}]}`.
//!
//! - `GET /ui-plugins/{id}/{path}` answers an ENABLED module's entry, one of its styles, or a file under one of its asset
//!   folders, resolved inside the module folder. Anything else is 404. The route sits behind the access policy of every
//!   other route (the token, the loopback rule, no browser Origin), so a client reads it with the token it already holds.
//! - The `plugins_op list` row carries `ui.web` (`apiVersion`, the entry and style URLs, `contributes`, `rev`): the app
//!   loads from it, and a new `rev` (a new build in the folder) makes it load again.
//! - Served at all: official and core modules. A community module's web half waits for the marketplace's signed approval
//!   (MARKET-1), exactly as its daemon half does.
use super::host_frames::{s, scan_modules, Module};
use super::plugins::Disabled;
use infantry_plugin_host::PluginHost;
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;

/// The route's URL prefix (an app host asks the daemon for paths under it only).
pub const PREFIX: &str = "/ui-plugins/";

/// What a module.json `ui` block declares, its paths checked to stay inside the module.
#[derive(Debug, Clone, PartialEq)]
pub struct WebHalf {
    pub api_version: u64,
    pub entry: String,
    pub styles: Vec<String>,
    pub assets: Vec<String>,
    pub contributes: Vec<Value>,
}

/// [rel] as a forward-slash path that stays inside a module folder, or None: absolute, `.` / `..` / empty parts,
/// backslashes, a drive or stream colon, NUL.
pub fn clean(rel: &str) -> Option<String> {
    let r = rel.trim();
    if r.is_empty() || r.starts_with('/') || r.contains(['\\', ':', '\0']) {
        return None;
    }
    r.split('/').all(|p| !p.is_empty() && p != "." && p != "..").then(|| r.to_string())
}

/// A module id that is one plain URL segment.
fn plain_id(id: &str) -> bool {
    !id.is_empty() && id != "." && id != ".." && id.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '-' | '.'))
}

/// [rel] percent-encoded for a URL path (the route decodes it back).
fn encode(rel: &str) -> String {
    rel.bytes().map(|b| if b.is_ascii_alphanumeric() || b"-._~/".contains(&b) { char::from(b).to_string() } else { format!("%{b:02X}") }).collect()
}

/// The web UI half of [manifest]: None when it declares none, or when its entry is not a path inside the module.
pub fn web_half(manifest: &Value) -> Option<WebHalf> {
    let ui = manifest.get("ui").filter(|u| u.is_object())?;
    let entry = clean(s(ui, "entry"))?;
    let paths = |k: &str| -> Vec<String> { ui.get(k).and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_str).filter_map(|p| clean(p.trim().trim_end_matches('/'))).collect() };
    Some(WebHalf {
        api_version: ui.get("apiVersion").and_then(Value::as_u64).unwrap_or(0),
        entry,
        styles: paths("styles"),
        assets: paths("assets"),
        contributes: ui.get("contributes").and_then(Value::as_array).map(|a| a.iter().filter(|c| c.is_object() && !s(c, "id").is_empty()).cloned().collect()).unwrap_or_default(),
    })
}

/// Whether [manifest] has a daemon half (a component or a sidecar).
pub fn has_daemon(manifest: &Value) -> bool {
    manifest.get("daemon").is_some_and(|d| d.get("component").is_some() || d.get("sidecar").is_some())
}

/// [tabs] with the web half's `tab` and `panel` contributions folded in, as the shell reads declarations: a `panel` is a
/// tab of the right column, and a contribution replaces a declaration with the same id (it is the more specific one).
pub fn fold_tabs(manifest: &Value, tabs: &mut Vec<Value>) {
    let Some(w) = web_half(manifest) else { return };
    for c in w.contributes {
        let container = match s(&c, "slot") {
            "tab" => "center",
            "panel" => "right",
            _ => continue,
        };
        let id = s(&c, "id").to_string();
        let mut decl = c;
        if let Some(o) = decl.as_object_mut() {
            o.remove("slot");
            o.entry("container").or_insert_with(|| container.into());
        }
        match tabs.iter_mut().find(|t| t.as_str().unwrap_or_else(|| s(t, "id")) == id) {
            Some(t) => *t = decl,
            None => tabs.push(decl),
        }
    }
}

/// A short fingerprint of the files the app loads (size and modification time of each): it changes with a new build.
fn rev<'a>(dir: &Path, files: impl IntoIterator<Item = &'a String>) -> String {
    let mut h: u64 = 0xcbf2_9ce4_8422_2325;
    for f in files {
        let meta = std::fs::metadata(dir.join(f)).ok();
        let stamp = meta.as_ref().and_then(|m| m.modified().ok()).and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok()).map(|d| d.as_nanos()).unwrap_or(0);
        for b in format!("{f}:{}:{stamp};", meta.map(|m| m.len()).unwrap_or(0)).bytes() {
            h = (h ^ u64::from(b)).wrapping_mul(0x0100_0000_01b3);
        }
    }
    format!("{h:016x}")
}

/// The `ui.web` block of a plugin row: what an app host loads, as URLs under [PREFIX]. Null without a web UI half.
pub(super) fn web_row(m: &Module) -> Value {
    let Some(w) = web_half(&m.manifest).filter(|_| plain_id(&m.id)) else { return Value::Null };
    let url = |rel: &String| format!("{PREFIX}{}/{}", m.id, encode(rel));
    json!({
        "apiVersion": w.api_version,
        "entry": url(&w.entry),
        "styles": w.styles.iter().map(url).collect::<Vec<_>>(),
        "contributes": w.contributes,
        "rev": rev(&m.dir, std::iter::once(&w.entry).chain(&w.styles)),
    })
}

/// The Content-Type of a served file, by its extension.
pub fn media_type(p: &Path) -> &'static str {
    match p.extension().and_then(|e| e.to_str()).map(str::to_ascii_lowercase).as_deref() {
        Some("js" | "mjs") => "text/javascript; charset=utf-8",
        Some("css") => "text/css; charset=utf-8",
        Some("json" | "map") => "application/json",
        Some("svg") => "image/svg+xml",
        Some("png") => "image/png",
        Some("jpg" | "jpeg") => "image/jpeg",
        Some("gif") => "image/gif",
        Some("webp") => "image/webp",
        Some("ico") => "image/x-icon",
        Some("woff2") => "font/woff2",
        Some("woff") => "font/woff",
        Some("ttf") => "font/ttf",
        Some("wasm") => "application/wasm",
        Some("txt" | "md") => "text/plain; charset=utf-8",
        _ => "application/octet-stream",
    }
}

/// What the route and the plugin list need to tell an enabled module.
pub struct UiBundles {
    pub plugins_dir: Option<PathBuf>,
    pub plugins: Option<Arc<PluginHost>>,
    pub disabled: Arc<Disabled>,
}

impl UiBundles {
    /// The load status of every daemon half (`loaded`, or a fault text).
    pub fn statuses(&self) -> BTreeMap<String, String> {
        self.plugins.as_ref().map(|h| h.statuses()).unwrap_or_default()
    }

    /// The one enable rule (the `enabled` of `plugins_op list`, and what this route serves): a module with a daemon half
    /// while that half is loaded; any other while it is official or core and the owner has not disabled it.
    pub(super) fn enabled(&self, m: &Module, statuses: &BTreeMap<String, String>) -> bool {
        if has_daemon(&m.manifest) {
            statuses.get(&m.id).is_some_and(|st| st == "loaded")
        } else {
            m.tier != "community" && !self.disabled.contains(&m.id)
        }
    }

    /// The file `GET /ui-plugins/{id}/{rel}` answers, or None (404).
    pub fn file(&self, id: &str, rel: &str) -> Option<PathBuf> {
        let rel = clean(rel)?;
        let m = scan_modules(&self.plugins_dir).into_iter().find(|m| m.id == id)?;
        if !self.enabled(&m, &self.statuses()) {
            return None;
        }
        let w = web_half(&m.manifest)?;
        let listed = rel == w.entry || w.styles.contains(&rel) || w.assets.iter().any(|a| rel.starts_with(&format!("{a}/")));
        if !listed {
            return None;
        }
        let root = m.dir.canonicalize().ok()?;
        let file = m.dir.join(&rel).canonicalize().ok()?;
        (file.starts_with(&root) && file.is_file()).then_some(file)
    }
}

/// The axum router of `GET /ui-plugins/{id}/{*path}` (the composition root merges it behind the access policy).
pub fn router(bundles: Arc<UiBundles>) -> axum::Router {
    use axum::extract::{Path as P, State};
    use axum::http::{header, StatusCode};
    use axum::response::{IntoResponse, Response};
    async fn get(State(b): State<Arc<UiBundles>>, P((id, rel)): P<(String, String)>) -> Response {
        let read = tokio::task::spawn_blocking(move || b.file(&id, &rel).and_then(|f| Some((media_type(&f), std::fs::read(&f).ok()?)))).await;
        match read {
            Ok(Some((ty, bytes))) => ([(header::CONTENT_TYPE, ty), (header::CACHE_CONTROL, "no-cache"), (header::X_CONTENT_TYPE_OPTIONS, "nosniff")], bytes).into_response(),
            _ => (StatusCode::NOT_FOUND, "not found").into_response(),
        }
    }
    axum::Router::new().route("/ui-plugins/{id}/{*path}", axum::routing::get(get)).with_state(bundles)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    /// A plugins folder: official/Web (a web half, no daemon half), community/Wild (the same, community tier) and
    /// official/Daemonish (a web half beside a daemon half that is not loaded).
    fn fixture(tag: &str) -> (PathBuf, UiBundles) {
        let root = std::env::temp_dir().join(format!("infantry-uib-{tag}-{}", uuid::Uuid::new_v4()));
        let ui = json!({
            "apiVersion": 1, "entry": "ui/dist/index.js", "styles": ["ui/dist/index.css"], "assets": ["ui/dist/assets/"],
            "contributes": [{"slot": "tab", "id": "web.tab", "label": "Web"}, {"slot": "settings", "id": "web.card"}],
        });
        for (tier, id, daemon) in [("official", "Web", false), ("community", "Wild", false), ("official", "Daemonish", true)] {
            let d = root.join(tier).join(id);
            fs::create_dir_all(d.join("ui/dist/assets")).unwrap();
            let mut m = json!({ "name": id, "ui": ui });
            if daemon {
                m["daemon"] = json!({ "component": "daemon/plugin.wasm" });
            }
            fs::write(d.join("module.json"), m.to_string()).unwrap();
            fs::write(d.join("ui/dist/index.js"), "export default {}").unwrap();
            fs::write(d.join("ui/dist/index.css"), ".x{}").unwrap();
            fs::write(d.join("ui/dist/assets/logo.svg"), "<svg/>").unwrap();
            fs::write(d.join("ui/dist/secret.txt"), "not listed").unwrap();
        }
        let state = root.join("state");
        fs::create_dir_all(&state).unwrap();
        let b = UiBundles { plugins_dir: Some(root.clone()), plugins: None, disabled: Arc::new(Disabled::load(&state)) };
        (root, b)
    }

    #[test]
    fn paths_that_could_leave_the_module_are_refused() {
        for bad in ["", "/a", "a/../b", "./a", "a//b", "a\\b", "C:/a", "a:b", "..", "a/.", "a\0b"] {
            assert_eq!(clean(bad), None, "{bad:?}");
        }
        assert_eq!(clean("ui/dist/index.js").as_deref(), Some("ui/dist/index.js"));
        assert!(web_half(&json!({ "ui": { "entry": "../x.js" } })).is_none());
        assert!(web_half(&json!({ "ui": {} })).is_none());
        assert!(web_half(&json!({})).is_none());
        assert_eq!(web_half(&json!({ "ui": { "entry": "i.js", "styles": ["../s.css", "s.css"] } })).unwrap().styles, vec!["s.css".to_string()]);
    }

    #[test]
    fn serves_only_the_declared_files_of_an_enabled_module() {
        let (root, b) = fixture("serve");
        for ok in ["ui/dist/index.js", "ui/dist/index.css", "ui/dist/assets/logo.svg"] {
            assert!(b.file("Web", ok).is_some(), "{ok}");
        }
        for refused in ["module.json", "ui/dist/secret.txt", "ui/dist/../module.json", "ui/dist/assets/../../../module.json", "/etc/passwd", "ui\\dist\\index.js", "ui/dist/assets", "ui/dist/assets/missing.png"] {
            assert!(b.file("Web", refused).is_none(), "{refused}");
        }
        assert!(b.file("Wild", "ui/dist/index.js").is_none(), "a community module's web half waits for MARKET-1");
        assert!(b.file("Daemonish", "ui/dist/index.js").is_none(), "a daemon half that is not loaded serves no UI");
        assert!(b.file("Nope", "ui/dist/index.js").is_none());
        b.disabled.set("Web", true).unwrap();
        assert!(b.file("Web", "ui/dist/index.js").is_none(), "the owner disabled it");
        assert!(Disabled::load(&root.join("state")).contains("Web"), "the disabled set is kept in the state dir");
        b.disabled.set("Web", false).unwrap();
        assert!(b.file("Web", "ui/dist/index.js").is_some());
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn the_row_carries_the_urls_the_contributions_and_a_rev_that_follows_the_build() {
        let (root, b) = fixture("row");
        let m = scan_modules(&b.plugins_dir).into_iter().find(|m| m.id == "Web").unwrap();
        let w = web_row(&m);
        assert_eq!(w["apiVersion"], 1);
        assert_eq!(w["entry"], "/ui-plugins/Web/ui/dist/index.js");
        assert_eq!(w["styles"], json!(["/ui-plugins/Web/ui/dist/index.css"]));
        assert_eq!(w["contributes"][1]["id"], "web.card");
        let before = w["rev"].clone();
        fs::write(m.dir.join("ui/dist/index.js"), "export default { rebuilt: true }").unwrap();
        assert_ne!(web_row(&m)["rev"], before, "a new build gets a new rev");
        assert_eq!(web_row(&Module { id: "a b".into(), ..m }), Value::Null, "an id that is not one URL segment gets no web half");
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn tab_and_panel_contributions_join_the_declared_tabs() {
        let m = json!({
            "tabs": [{ "id": "a", "container": "center" }, "b"],
            "ui": { "entry": "i.js", "contributes": [{ "slot": "tab", "id": "a", "label": "A" }, { "slot": "panel", "id": "p" }, { "slot": "settings", "id": "s" }] },
        });
        let u = super::super::host_frames::ui_of(&m);
        assert_eq!(u["tabs"], json!([{ "id": "a", "label": "A", "container": "center" }, "b", { "id": "p", "container": "right" }]));
        assert_eq!(media_type(Path::new("x/index.JS")), "text/javascript; charset=utf-8");
        assert_eq!(encode("ui/a b#.js"), "ui/a%20b%23.js");
    }
}
