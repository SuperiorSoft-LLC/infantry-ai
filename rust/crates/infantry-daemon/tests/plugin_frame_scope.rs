// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! SEC-PLUGIN-1: a plugin with the `frames` grant is not the owner.
//!
//! The `frames` import used to hand every frame to the daemon as the OWNER, so a plugin whose manifest asks for nothing
//! but `frames` could reveal, overwrite or delete any saved secret and read another plugin's. These tests send frames
//! the way a plugin does (through `PluginImports::frame_request`, the code behind the WIT import and the sidecar line),
//! at a composed daemon on a scratch state dir with a scratch vault (the owner's daemon and vault are never touched).
//!
//! Its own test binary: it points `INFANTRY_PLUGINS_DIR` at an empty temp folder, a process-wide setting.
use infantry_daemon::harness::stub::Stub;
use infantry_daemon::sessions::{Config, Daemon};
use infantry_plugin_host::imports::PluginImports;
use infantry_plugin_host::manifest::{DaemonDecl, Tier};
use infantry_protocol::Envelope;
use serde_json::{json, Value};
use std::collections::BTreeSet;
use std::sync::Arc;

struct Scratch {
    dir: std::path::PathBuf,
    c: Arc<infantry_daemon::compose::Composed>,
}

impl Drop for Scratch {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.dir);
    }
}

/// A composed daemon with no plugin installed, a scratch vault, and the three secrets the owner saved.
fn scratch() -> Scratch {
    let dir = std::env::temp_dir().join(format!("infantry-sec-plugin-1-{}", uuid::Uuid::new_v4()));
    let (state, plugins) = (dir.join("state"), dir.join("plugins"));
    std::fs::create_dir_all(plugins.join("official")).unwrap();
    std::fs::create_dir_all(&state).unwrap();
    std::env::set_var("INFANTRY_PLUGINS_DIR", &plugins);
    let daemon = Daemon::new(Arc::new(Stub::default()), Config::new(state.clone(), dir.clone())).unwrap();
    let c = infantry_daemon::compose::build(&daemon, &state).unwrap();
    let s = Scratch { dir, c };
    // The owner's own work: a user secret, and the secrets of two plugins.
    for (name, field, value) in [("prod-db", "PASS", "fake-db-pass-0123456789"), ("plugin.victim_mod", "TOKEN", "fake-victim-token-0123456789"), ("plugin.evil_mod", "TOKEN", "fake-own-token-0123456789")] {
        s.owner(&Envelope::new("secret_field_set", json!({ "name": name, "field": field, "value": value }).to_string())).unwrap();
    }
    s
}

impl Scratch {
    fn owner(&self, env: &Envelope) -> Result<Vec<Envelope>, String> {
        self.c.request(env, true)
    }

    /// The plugin `id` (an official module whose manifest `needs` is `frames` and the given `ownerFrames`).
    fn plugin(&self, id: &str, owner_frames: &[&str]) -> PluginImports {
        let decl: DaemonDecl = serde_json::from_value(json!({ "needs": ["frames"], "ownerFrames": owner_frames })).unwrap();
        let services = self.c.plugins.as_ref().expect("the plugin host is up").services().clone();
        PluginImports::new(id, Tier::Official, decl, services, ["frames".to_string()].into(), BTreeSet::new())
    }
}

/// What a plugin sends: the answer's data, or the refusal text.
fn send(p: &PluginImports, ty: &str, data: Value) -> Result<Value, String> {
    let f = p.frame_request(ty, &data.to_string(), None, 5000)?;
    Ok(serde_json::from_str(&f.data).unwrap_or(Value::String(f.data)))
}

#[tokio::test(flavor = "multi_thread")]
async fn a_plugin_with_only_the_frames_grant_cannot_reach_the_vault() {
    let s = scratch();
    let evil = s.plugin("Evil_Mod", &[]);
    let mut breaks: Vec<String> = Vec::new();
    let mut expect_owner_only = |what: &str, r: Result<Value, String>| match r {
        Err(e) if e.contains("owner only") => {}
        Err(e) => breaks.push(format!("{what}: refused, but not as an owner-only frame: {e}")),
        Ok(v) => breaks.push(format!("{what}: ANSWERED ({v})")),
    };

    // 1. The vault's owner-only frames.
    expect_owner_only("secret_reveal", send(&evil, "secret_reveal", json!({ "name": "prod-db", "field": "PASS" })));
    expect_owner_only("secret_backup_export", send(&evil, "secret_backup_export", json!({ "passphrase": "fake-passphrase-123" })));
    expect_owner_only("secret_field_set (overwrite)", send(&evil, "secret_field_set", json!({ "name": "prod-db", "field": "PASS", "value": "fake-attacker-0123456789" })));
    expect_owner_only("secret_delete", send(&evil, "secret_delete", json!({ "name": "prod-db" })));
    expect_owner_only("secret_audit_get", send(&evil, "secret_audit_get", json!({})));

    // 2. Another plugin's secret: refused, and the plugin's own is the only one it reads.
    match send(&evil, "plugin_secret_get", json!({ "module": "Victim_Mod", "field": "TOKEN" })) {
        Err(e) if e.contains("only its own") => {}
        Err(e) => breaks.push(format!("plugin_secret_get naming another module: refused without saying why: {e}")),
        Ok(v) => breaks.push(format!("plugin_secret_get naming ANOTHER module answered: {v}")),
    }
    match send(&evil, "plugin_secret_get", json!({ "module": "Evil_Mod", "field": "TOKEN" })) {
        Ok(v) if v["value"] == "fake-own-token-0123456789" => {}
        other => breaks.push(format!("a plugin cannot read its OWN secret: {other:?}")),
    }
    match send(&evil, "plugin_secret_get", json!({ "field": "TOKEN" })) {
        Ok(v) if v["module"] == "Evil_Mod" && v["value"] == "fake-own-token-0123456789" => {}
        other => breaks.push(format!("plugin_secret_get with no module is not filled in with the caller's: {other:?}")),
    }

    // 3. What the plugin tried changed nothing.
    let still = s.owner(&Envelope::new("plugin_secret_get", json!({ "module": "Victim_Mod", "field": "TOKEN" }).to_string())).unwrap();
    if !still[0].data.contains("fake-victim-token-0123456789") {
        breaks.push(format!("the victim's secret was changed: {}", still[0].data));
    }
    let db = s.owner(&Envelope::new("secret_status", "")).map(|r| r[0].data.clone()).unwrap_or_default();
    if !(db.contains("prod-db") && db.contains("6789")) {
        breaks.push(format!("the user secret prod-db was overwritten or deleted: {db}"));
    }

    assert!(breaks.is_empty(), "SEC-PLUGIN-1: {} break(s):\n  {}", breaks.len(), breaks.join("\n  "));
}

#[tokio::test(flavor = "multi_thread")]
async fn a_frame_the_manifest_lists_as_an_owner_frame_is_sent_as_the_owner_and_only_that_one() {
    let s = scratch();
    // A plugin that lists `secret_field_set` in `daemon.ownerFrames` (the explicit grant) may send that one frame.
    let granted = s.plugin("Admin_Mod", &["secret_field_set"]);
    let set = send(&granted, "secret_field_set", json!({ "name": "granted-note", "field": "K", "value": "fake-granted-0123456789" }));
    assert!(set.is_ok(), "a listed owner frame is refused: {set:?}");
    // ... but not the next owner-only frame it did not list.
    let del = send(&granted, "secret_delete", json!({ "name": "granted-note" }));
    assert!(del.unwrap_err().contains("owner only"), "an owner frame that is not listed was sent as the owner");
    // The same frame from a plugin that lists nothing is refused.
    let plain = s.plugin("Plain_Mod", &[]);
    let r = send(&plain, "secret_field_set", json!({ "name": "plain-note", "field": "K", "value": "fake-plain-0123456789" }));
    assert!(r.unwrap_err().contains("owner only"));
}
