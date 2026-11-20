// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! WEBUI-1: a plugin's web UI half is served by the daemon over its own port, only while the plugin is enabled, behind
//! the same access policy as every other route (the token, no browser Origin). Enabling or disabling it pushes
//! `plugins_changed` to every client, and the choice is kept in the state dir.
//!
//! Its own test binary: it points `INFANTRY_PLUGINS_DIR` at a temp folder, a process-wide setting.
use infantry_daemon::auth::AccessPolicy;
use infantry_daemon::compose::Composed;
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::server::{self, App};
use infantry_daemon::sessions::{Config, Daemon};
use infantry_protocol::Envelope;
use serde_json::{json, Value};
use std::sync::Arc;

const TOKEN: &str = "webui-test-token";
const ID: &str = "Acme_Web";

/// (status, content type, body) of one GET.
fn get(base: &str, path: &str, token: Option<&str>, origin: Option<&str>) -> (u16, String, String) {
    let mut r = ureq::get(&format!("{base}/{path}"));
    if let Some(t) = token {
        r = r.set("Authorization", &format!("Bearer {t}"));
    }
    if let Some(o) = origin {
        r = r.set("Origin", o);
    }
    match r.call() {
        Ok(resp) | Err(ureq::Error::Status(_, resp)) => (resp.status(), resp.header("content-type").unwrap_or("").to_string(), resp.into_string().unwrap_or_default()),
        Err(e) => panic!("GET {path}: {e}"),
    }
}

fn row(c: &Composed) -> Value {
    let reply = c.request(&Envelope::new("plugins_op", r#"{"op":"list"}"#), true).unwrap();
    let list: Value = serde_json::from_str(&reply[0].data).unwrap();
    list.as_array().unwrap().iter().find(|r| r["id"] == ID).cloned().expect("the module is listed")
}

fn admin(c: &Composed, op: &str) -> Value {
    let reply = c.request(&Envelope::new("plugin_admin", json!({ "args": [op, ID] }).to_string()), true).unwrap();
    serde_json::from_str(&reply[0].data).unwrap()
}

#[test]
fn a_web_ui_half_is_served_while_enabled_behind_the_access_policy() {
    let tmp = std::env::temp_dir().join(format!("infantry-webui-{}", uuid::Uuid::new_v4()));
    let (state, plugins) = (tmp.join("state"), tmp.join("plugins"));
    let module = plugins.join("official").join(ID);
    std::fs::create_dir_all(module.join("ui/dist")).unwrap();
    std::fs::create_dir_all(&state).unwrap();
    let manifest = json!({
        "name": ID, "displayName": "Acme Web",
        "ui": { "apiVersion": 1, "entry": "ui/dist/index.js", "styles": ["ui/dist/index.css"], "contributes": [{ "slot": "tab", "id": "acme.web", "label": "Web" }] },
    });
    std::fs::write(module.join("module.json"), manifest.to_string()).unwrap();
    std::fs::write(module.join("ui/dist/index.js"), "export default { apiVersion: 1 };").unwrap();
    std::fs::write(module.join("ui/dist/index.css"), ".acme{}").unwrap();
    std::env::set_var("INFANTRY_PLUGINS_DIR", &plugins);

    let rt = tokio::runtime::Runtime::new().unwrap();
    let (port, c, stop) = rt.block_on(async {
        let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(state.clone(), tmp.clone())).unwrap();
        let c = infantry_daemon::compose::build(&daemon, &state).unwrap();
        let policy = Arc::new(AccessPolicy::new("127.0.0.1", Some(TOKEN)).unwrap());
        let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let (tx, rx) = tokio::sync::oneshot::channel::<()>();
        tokio::spawn(server::serve(listener, App { daemon, policy }, async {
            let _ = rx.await;
        }));
        (port, c, tx)
    });
    let base = format!("http://127.0.0.1:{port}/ui-plugins/{ID}");

    // Served with the token, as JavaScript; refused without it, with a wrong one, or from a browser Origin.
    let (status, ty, body) = get(&base, "ui/dist/index.js", Some(TOKEN), None);
    assert_eq!((status, body.as_str()), (200, "export default { apiVersion: 1 };"));
    assert!(ty.starts_with("text/javascript"), "{ty}");
    assert_eq!(get(&base, "ui/dist/index.css", Some(TOKEN), None).0, 200);
    assert_eq!(get(&base, "ui/dist/index.js", None, None).0, 401);
    assert_eq!(get(&base, "ui/dist/index.js", Some("wrong"), None).0, 401);
    assert_eq!(get(&base, "ui/dist/index.js", Some(TOKEN), Some("https://evil.example")).0, 403);
    // Only the declared files, never the manifest or a path out of the module.
    assert_eq!(get(&base, "module.json", Some(TOKEN), None).0, 404);
    assert_eq!(get(&base, "ui/dist/..%2F..%2Fmodule.json", Some(TOKEN), None).0, 404);

    // The plugin list row carries the web half and folds its tab into the declarations.
    let r = row(&c);
    assert_eq!(r["enabled"], true);
    assert_eq!(r["status"], "ui only");
    assert_eq!(r["ui"]["web"]["entry"], format!("/ui-plugins/{ID}/ui/dist/index.js"));
    assert_eq!(r["ui"]["web"]["apiVersion"], 1);
    assert_eq!(r["ui"]["tabs"][0]["id"], "acme.web");

    // Disabled by the owner: no longer served, every client is told, the choice is kept.
    let mut pushes = c.bus.subscribe();
    assert_eq!(admin(&c, "disable")["code"], 0);
    assert_eq!(get(&base, "ui/dist/index.js", Some(TOKEN), None).0, 404);
    let r = row(&c);
    assert_eq!((r["enabled"].clone(), r["status"].clone()), (json!(false), json!("disabled")));
    let pushed = std::iter::from_fn(|| pushes.try_recv().ok()).find(|e| e.r#type == "plugins_changed").expect("plugins_changed is pushed");
    assert_eq!(serde_json::from_str::<Value>(&pushed.data).unwrap(), json!({ "id": ID, "enabled": false }));
    let kept = std::fs::read_to_string(state.join("plugins-disabled.json")).unwrap();
    assert!(kept.contains(ID), "{kept}");

    // Enabled again: served again.
    assert_eq!(admin(&c, "enable")["code"], 0);
    assert_eq!(get(&base, "ui/dist/index.js", Some(TOKEN), None).0, 200);
    assert_eq!(row(&c)["enabled"], true);
    assert_ne!(admin(&c, "bogus")["code"], 0);

    let _ = stop.send(());
    drop(c);
    rt.shutdown_timeout(std::time::Duration::from_secs(5));
    let _ = std::fs::remove_dir_all(&tmp);
}
