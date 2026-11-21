// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! user-added plugin sources through `plugin_admin`, end to end on the composed daemon: add a local-folder source,
//! find its plugin by search, refuse a tampered one, install, disable, enable, uninstall, remove the source. A second
//! composition over the same state dir (another client's view of the daemon) sees the same sources.
//! Its own test binary and ONE test: it sets process-wide environment variables.
use infantry_daemon::compose::{sources, Composed};
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::sessions::{Config, Daemon};
use infantry_protocol::Envelope;
use serde_json::{json, Value};
use std::path::Path;
use std::sync::Arc;

fn module(root: &Path, id: &str) -> std::path::PathBuf {
    let dir = root.join("official").join(id);
    std::fs::create_dir_all(dir.join("ui/dist")).unwrap();
    let m = json!({ "name": id, "displayName": id, "description": "a test plugin", "version": "1.0.0", "ui": { "apiVersion": 1, "entry": "ui/dist/index.js" } });
    std::fs::write(dir.join("module.json"), m.to_string()).unwrap();
    std::fs::write(dir.join("ui/dist/index.js"), "export default { apiVersion: 1 };").unwrap();
    dir
}

fn admin(c: &Composed, args: &[&str]) -> (i64, String, String) {
    let reply = c.request(&Envelope::new("plugin_admin", json!({ "args": args }).to_string()), true).unwrap();
    let v: Value = serde_json::from_str(&reply[0].data).unwrap();
    (v["code"].as_i64().unwrap(), v["out"].as_str().unwrap().to_string(), v["err"].as_str().unwrap().to_string())
}

fn composed(rt: &tokio::runtime::Runtime, tmp: &Path, state: &Path) -> Arc<Composed> {
    std::fs::create_dir_all(state).unwrap();
    rt.block_on(async {
        let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(state.to_path_buf(), tmp.to_path_buf())).unwrap();
        infantry_daemon::compose::build(&daemon, state).unwrap()
    })
}

#[test]
fn a_user_added_source_is_searched_installed_from_and_removed() {
    let tmp = std::env::temp_dir().join(format!("infantry-plugin-sources-it-{}", uuid::Uuid::new_v4()));
    let (plugins, bundled, src, state) = (tmp.join("plugins"), tmp.join("bundle"), tmp.join("mysrc"), tmp.join("state"));
    for d in [&plugins, &bundled] {
        std::fs::create_dir_all(d).unwrap();
    }
    std::env::set_var("INFANTRY_PLUGINS_DIR", &plugins);
    std::env::set_var("INFANTRY_BUNDLED_PLUGINS_DIR", &bundled);
    std::env::remove_var("INFANTRY_BOOTSTRAP_PLUGIN");
    let good = module(&src, "Ps_Good");
    let bad = module(&src, "Ps_Bad");
    for d in [&good, &bad] {
        std::fs::write(d.join(sources::DIGEST_FILE), sources::folder_digest(d).unwrap()).unwrap();
    }
    std::fs::write(bad.join("ui/dist/index.js"), "export default { evil: true };").unwrap();

    let rt = tokio::runtime::Runtime::new().unwrap();
    let c = composed(&rt, &tmp, &state);
    assert_eq!(admin(&c, &["search", "test plugin"]).1.trim(), "[]", "nothing offered before a source is added");
    let (code, out, err) = admin(&c, &["source-add", &src.display().to_string()]);
    assert_eq!(code, 0, "{err}");
    assert!(out.contains("folder source"), "{out}");

    // Another client on the same daemon (same state dir) sees the same source, and it appears in a search.
    let second = composed(&rt, &tmp, &state);
    let list: Value = serde_json::from_str(&admin(&second, &["sources"]).1).unwrap();
    assert_eq!(list.as_array().unwrap().len(), 1);
    let found: Value = serde_json::from_str(&admin(&second, &["search", "test plugin"]).1).unwrap();
    let ids: Vec<&str> = found.as_array().unwrap().iter().map(|r| r["id"].as_str().unwrap()).collect();
    assert_eq!(ids, ["Ps_Bad", "Ps_Good"]);
    assert_eq!(found[1]["signed"], true);

    // A module whose files do not match its signature file is refused, and nothing is left behind.
    let (code, _, err) = admin(&c, &["install", "Ps_Bad"]);
    assert_eq!(code, 1);
    assert!(err.contains("signature check failed"), "{err}");
    assert!(!plugins.join("official/Ps_Bad").exists());

    assert_eq!(admin(&c, &["install", "Ps_Good"]).0, 0);
    assert!(plugins.join("official/Ps_Good/module.json").is_file());
    assert_eq!(admin(&c, &["disable", "Ps_Good"]).0, 0);
    assert_eq!(admin(&c, &["enable", "Ps_Good"]).0, 0);
    assert_eq!(admin(&c, &["uninstall", "Ps_Good"]).0, 0);
    assert!(!plugins.join("official/Ps_Good").exists());

    let id = list[0]["id"].as_str().unwrap();
    assert_eq!(admin(&c, &["source-remove", id]).0, 0);
    assert_eq!(admin(&c, &["search", "test plugin"]).1.trim(), "[]");
    assert!(src.join("official/Ps_Good").is_dir(), "the user's folder is untouched");
    let _ = std::fs::remove_dir_all(&tmp);
}
