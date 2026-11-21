// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! INSTALLER-1: the bootstrap seam and the live install. A daemon started with an empty plugins folder installs the plugin
//! the bundled folder names as its bootstrap plugin (by its manifest flag, never by an id in code) and reports bootstrap
//! mode; `plugin_admin install` then puts a plugin and what it requires on disk and into the running daemon, in dependency
//! order, with no restart; `uninstall` takes them out again; a normal install is not in bootstrap mode.
//!
//! Its own test binary, and ONE test: it points `INFANTRY_PLUGINS_DIR` and `INFANTRY_BUNDLED_PLUGINS_DIR` at temp folders, a
//! process-wide setting, one scenario after the other.
use infantry_core::frames::{FrameCtx, FrameUnit, Refusal};
use infantry_daemon::auth::AccessPolicy;
use infantry_daemon::compose::Composed;
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::server::{self, App};
use infantry_daemon::sessions::{Config, Daemon, FrameUnits};
use infantry_protocol::Envelope;
use serde_json::{json, Value};
use std::path::Path;
use std::sync::Arc;

const TOKEN: &str = "installer-test-token";

/// A module with a web UI half and nothing else (so the test needs no WASM build), in `<root>/<tier>/<id>`.
fn module(root: &Path, tier: &str, id: &str, extra: Value) {
    let dir = root.join(tier).join(id);
    std::fs::create_dir_all(dir.join("ui/dist")).unwrap();
    let mut m = json!({
        "name": id, "displayName": id, "version": "1.0.0",
        "ui": { "apiVersion": 1, "entry": "ui/dist/index.js", "styles": ["ui/dist/index.css"], "contributes": [{ "slot": "tab", "id": format!("test.{id}"), "label": id }] },
    });
    m.as_object_mut().unwrap().extend(extra.as_object().unwrap().clone());
    std::fs::write(dir.join("module.json"), m.to_string()).unwrap();
    std::fs::write(dir.join("ui/dist/index.js"), "export default { apiVersion: 1 };").unwrap();
    std::fs::write(dir.join("ui/dist/index.css"), ".t{}").unwrap();
}

fn bundle(root: &Path) {
    module(root, "official", "Test_Installer", json!({ "bootstrap": true }));
    module(root, "official", "Test_Base", json!({}));
    module(root, "official", "Test_Board", json!({ "requires": ["Test_Base"] }));
    module(root, "official", "Test_Mail", json!({ "requires": ["Test_Board"] }));
}

fn get(base: &str, path: &str) -> u16 {
    match ureq::get(&format!("{base}/{path}")).set("Authorization", &format!("Bearer {TOKEN}")).call() {
        Ok(r) | Err(ureq::Error::Status(_, r)) => r.status(),
        Err(e) => panic!("GET {path}: {e}"),
    }
}

/// `plugin_admin` as the owner: (code, out, err).
fn admin(c: &Composed, args: &[&str]) -> (i64, String, String) {
    let reply = c.request(&Envelope::new("plugin_admin", json!({ "args": args }).to_string()), true).unwrap();
    let v: Value = serde_json::from_str(&reply[0].data).unwrap();
    (v["code"].as_i64().unwrap(), v["out"].as_str().unwrap().to_string(), v["err"].as_str().unwrap().to_string())
}

fn boot(c: &Composed) -> Value {
    let reply = c.request(&Envelope::new("boot_progress", "{}"), true).unwrap();
    serde_json::from_str(&reply[0].data).unwrap()
}

fn ids(c: &Composed) -> Vec<String> {
    let reply = c.request(&Envelope::new("plugins_op", r#"{"op":"list"}"#), true).unwrap();
    let list: Value = serde_json::from_str(&reply[0].data).unwrap();
    list.as_array().unwrap().iter().map(|r| r["id"].as_str().unwrap().to_string()).collect()
}

struct Echo;
impl FrameUnit for Echo {
    fn name(&self) -> &'static str {
        "echo"
    }
    fn frames(&self) -> &'static [&'static str] {
        &["late_echo"]
    }
    fn handle(&self, _env: &Envelope, _ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        Ok(vec![Envelope::new("late_echo", "hi")])
    }
}

fn composed(rt: &tokio::runtime::Runtime, tmp: &Path, plugins: &Path, state: &Path) -> (Arc<Daemon>, Arc<Composed>) {
    std::env::set_var("INFANTRY_PLUGINS_DIR", plugins);
    std::fs::create_dir_all(state).unwrap();
    rt.block_on(async {
        let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(state.to_path_buf(), tmp.to_path_buf())).unwrap();
        let c = infantry_daemon::compose::build(&daemon, state).unwrap();
        (daemon, c)
    })
}

#[test]
fn a_daemon_with_nothing_installed_boots_the_installer_and_installs_live() {
    let tmp = std::env::temp_dir().join(format!("infantry-installer-it-{}", uuid::Uuid::new_v4()));
    let (bundled, rt) = (tmp.join("bundle"), tokio::runtime::Runtime::new().unwrap());
    bundle(&bundled);
    std::env::set_var("INFANTRY_BUNDLED_PLUGINS_DIR", &bundled);
    std::env::remove_var("INFANTRY_BOOTSTRAP_PLUGIN");

    // ---- an empty plugins folder: the bootstrap plugin comes from the bundle, and the report says bootstrap mode ----
    let plugins = tmp.join("plugins");
    std::fs::create_dir_all(&plugins).unwrap();
    let state = tmp.join("state");
    let (daemon, c) = composed(&rt, &tmp, &plugins, &state);
    assert!(plugins.join("official/Test_Installer/module.json").is_file(), "the bootstrap plugin was installed from the bundle");
    assert!(!plugins.join("official/Test_Base").exists(), "and nothing else");
    let b = boot(&c);
    assert_eq!(b["ready"], true, "{b}");
    assert_eq!(b["bootstrap"], json!({ "plugin": "Test_Installer", "active": true, "error": null }));
    assert_eq!(ids(&c), ["Test_Installer"]);

    let (port, stop) = rt.block_on(async {
        let policy = Arc::new(AccessPolicy::new("127.0.0.1", Some(TOKEN)).unwrap());
        let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0)).await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let (tx, rx) = tokio::sync::oneshot::channel::<()>();
        tokio::spawn(server::serve(listener, App { daemon: daemon.clone(), policy }, async {
            let _ = rx.await;
        }));
        (port, tx)
    });
    let ui = |id: &str| get(&format!("http://127.0.0.1:{port}/ui-plugins/{id}"), "ui/dist/index.js");
    assert_eq!(ui("Test_Installer"), 200, "the installer's screen is served");
    assert_eq!(ui("Test_Mail"), 404);

    // ---- the owner only; the list shows what is offered and what is installed ----
    let refused = c.request(&Envelope::new("plugin_admin", r#"{"args":["available"]}"#), false).unwrap_err();
    assert!(refused.contains("owner only"), "{refused}");
    let (code, out, _) = admin(&c, &["available"]);
    assert_eq!(code, 0);
    let rows: Vec<Value> = serde_json::from_str(&out).unwrap();
    assert_eq!(rows.iter().map(|r| r["id"].as_str().unwrap()).collect::<Vec<_>>(), ["Test_Base", "Test_Board", "Test_Installer", "Test_Mail"]);
    let row = |id: &str| rows.iter().find(|r| r["id"] == id).unwrap().clone();
    assert_eq!((row("Test_Installer")["installed"].clone(), row("Test_Installer")["bootstrap"].clone()), (json!(true), json!(true)));
    assert_eq!((row("Test_Mail")["installed"].clone(), row("Test_Mail")["requires"].clone()), (json!(false), json!(["Test_Board"])));

    // ---- an install brings what it requires first, loads it with no restart, and ends bootstrap mode for every window ----
    let mut pushes = c.bus.subscribe();
    let (code, out, err) = admin(&c, &["install", "Test_Mail"]);
    assert_eq!((code, out.as_str(), err.as_str()), (0, "installed Test_Base, Test_Board, Test_Mail", ""));
    for id in ["Test_Base", "Test_Board", "Test_Mail"] {
        assert!(plugins.join("official").join(id).join("module.json").is_file(), "{id} is on disk");
        assert_eq!(ui(id), 200, "{id} is served at once");
    }
    assert_eq!(ids(&c), ["Test_Base", "Test_Board", "Test_Installer", "Test_Mail"]);
    let seen: Vec<Envelope> = std::iter::from_fn(|| pushes.try_recv().ok()).collect();
    let changed: Vec<String> = seen.iter().filter(|e| e.r#type == "plugins_changed").map(|e| serde_json::from_str::<Value>(&e.data).unwrap()["id"].as_str().unwrap().to_string()).collect();
    assert_eq!(changed, ["Test_Base", "Test_Board", "Test_Mail"], "every window is told, in install order");
    let last = seen.iter().rev().find(|e| e.r#type == "boot_progress").expect("boot_progress is pushed when bootstrap mode ends");
    assert_eq!(serde_json::from_str::<Value>(&last.data).unwrap()["bootstrap"]["active"], false);
    assert_eq!(boot(&c)["bootstrap"]["active"], false);
    assert!(admin(&c, &["install", "Test_Mail"]).1.contains("nothing to install"));
    assert!(admin(&c, &["install", "Test_Nope"]).2.contains("no source offers Test_Nope"));

    // ---- a plugin installed after start answers its frames (the registry was built before it existed) ----
    assert!(!c.owns("late_echo"));
    c.late.add("Late_Mod", &["late_echo".to_string()], Arc::new(Echo));
    assert!(c.owns("late_echo"));
    assert_eq!(c.request(&Envelope::new("late_echo", ""), true).unwrap()[0].data, "hi");
    c.late.remove("Late_Mod");
    assert!(c.request(&Envelope::new("late_echo", ""), true).is_err());

    // ---- uninstall: the installer stays, what is required stays, the rest goes and bootstrap mode returns ----
    assert!(admin(&c, &["uninstall", "Test_Installer"]).2.contains("installer"));
    assert!(admin(&c, &["uninstall", "Test_Base"]).2.contains("required by Test_Board"));
    for id in ["Test_Mail", "Test_Board", "Test_Base"] {
        assert_eq!(admin(&c, &["uninstall", id]).0, 0, "{id}");
        assert!(!plugins.join("official").join(id).exists(), "{id} is gone from disk");
        assert_eq!(ui(id), 404, "{id} is no longer served");
    }
    assert_eq!(ids(&c), ["Test_Installer"]);
    assert_eq!(boot(&c)["bootstrap"]["active"], true);
    assert!(admin(&c, &["uninstall", "Test_Base"]).2.contains("not installed"));
    let _ = stop.send(());
    drop(c);
    drop(daemon);

    // ---- the owner disabled the bootstrap plugin: with nothing else installed it is the way in, so it runs anyway ----
    let (plugins, state) = (tmp.join("plugins-off"), tmp.join("state-off"));
    module(&plugins, "official", "Test_Installer", json!({ "bootstrap": true }));
    std::fs::create_dir_all(&state).unwrap();
    std::fs::write(state.join("plugins-disabled.json"), r#"["Test_Installer"]"#).unwrap();
    let (_d, c) = composed(&rt, &tmp, &plugins, &state);
    assert_eq!(boot(&c)["bootstrap"]["active"], true);
    assert!(!std::fs::read_to_string(state.join("plugins-disabled.json")).unwrap().contains("Test_Installer"));

    // ---- a normal install (other plugins already installed) is not in bootstrap mode and nothing is copied ----
    let (plugins, state) = (tmp.join("plugins-normal"), tmp.join("state-normal"));
    module(&plugins, "official", "Test_Installer", json!({ "bootstrap": true }));
    module(&plugins, "official", "Test_Other", json!({}));
    let (_d, c) = composed(&rt, &tmp, &plugins, &state);
    let b = boot(&c);
    assert_eq!((b["ready"].clone(), b["bootstrap"].clone()), (json!(true), json!({ "plugin": "Test_Installer", "active": false, "error": null })));
    assert!(!plugins.join("official/Test_Base").exists());

    // ---- APPS-ROOT-1: an app installs its core members, offers its optional ones, and removes its members with it ----
    let (b2, plugins, state) = (tmp.join("bundle-apps"), tmp.join("plugins-apps"), tmp.join("state-apps"));
    module(&b2, "official", "Test_Installer", json!({ "bootstrap": true }));
    module(&b2, "official", "Test_Shell", json!({ "app": { "core": ["Test_Core", "Test_Shared"], "optional": ["Test_Opt"] } }));
    module(&b2, "official", "Test_Shell2", json!({ "app": { "core": ["Test_Shared"] } }));
    for id in ["Test_Core", "Test_Shared", "Test_Opt"] {
        module(&b2, "official", id, json!({}));
    }
    std::fs::create_dir_all(&plugins).unwrap();
    std::env::set_var("INFANTRY_BUNDLED_PLUGINS_DIR", &b2);
    let (_d, c) = composed(&rt, &tmp, &plugins, &state);
    let on_disk = |id: &str| plugins.join("official").join(id).join("module.json").is_file();
    let (code, out, _) = admin(&c, &["install", "Test_Shell"]);
    assert_eq!((code, out.as_str()), (0, "installed Test_Core, Test_Shared, Test_Shell"), "the core set comes with the app, the app last");
    assert!(!on_disk("Test_Opt") && !on_disk("Test_Shell2"), "an optional member is not forced");
    let rows: Vec<Value> = serde_json::from_str(&admin(&c, &["available"]).1).unwrap();
    let row = |id: &str| rows.iter().find(|r| r["id"] == id).unwrap().clone();
    assert_eq!(row("Test_Shell")["app"], json!({ "core": ["Test_Core", "Test_Shared"], "optional": ["Test_Opt"] }));
    assert_eq!(row("Test_Opt")["memberOf"], json!([{ "app": "Test_Shell", "kind": "optional" }]));
    assert_eq!(row("Test_Core")["memberOf"], json!([{ "app": "Test_Shell", "kind": "core" }]));
    assert_eq!(row("Test_Shared")["memberOf"].as_array().unwrap().len(), 2, "a member can belong to two apps");
    assert!(row("Test_Installer")["memberOf"].as_array().unwrap().is_empty() && row("Test_Installer")["app"].is_null());
    // The core member cannot go alone; the optional one installs and removes on its own.
    assert!(admin(&c, &["uninstall", "Test_Core"]).2.contains("core part of Test_Shell"));
    assert!(on_disk("Test_Core"));
    assert_eq!(admin(&c, &["install", "Test_Opt"]).1, "installed Test_Opt");
    assert_eq!(admin(&c, &["uninstall", "Test_Opt"]).0, 0);
    assert!(!on_disk("Test_Opt") && on_disk("Test_Shell"), "only the optional member went");
    // A member another installed app lists is kept when the first app goes.
    assert_eq!(admin(&c, &["install", "Test_Shell2", "Test_Opt"]).0, 0);
    let (code, out, _) = admin(&c, &["uninstall", "Test_Shell"]);
    assert_eq!(code, 0);
    assert_eq!(out, "uninstalled Test_Core, Test_Opt, Test_Shell; kept Test_Shared (also part of Test_Shell2)");
    assert!(on_disk("Test_Shared") && on_disk("Test_Shell2") && !on_disk("Test_Core") && !on_disk("Test_Shell"));
    assert_eq!(admin(&c, &["uninstall", "Test_Shell2"]).1, "uninstalled Test_Shared, Test_Shell2");
    assert_eq!(ids(&c), ["Test_Installer"]);
    assert_eq!(boot(&c)["bootstrap"]["active"], true, "removing the last app returns to the installer");

    // ---- a daemon with no bootstrap plugin anywhere reports none, and is ready as it always was ----
    let (plugins, state) = (tmp.join("plugins-none"), tmp.join("state-none"));
    module(&plugins, "official", "Test_Other", json!({}));
    std::env::set_var("INFANTRY_BUNDLED_PLUGINS_DIR", tmp.join("no-such-bundle"));
    let (_d, c) = composed(&rt, &tmp, &plugins, &state);
    let b = boot(&c);
    assert_eq!((b["ready"].clone(), b["bootstrap"].clone()), (json!(true), json!({ "active": false })));

    rt.shutdown_timeout(std::time::Duration::from_secs(5));
    let _ = std::fs::remove_dir_all(&tmp);
}
