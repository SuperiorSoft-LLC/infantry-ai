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

/// HOSTSTATS-CI-1: `INFANTRY_PLUGINS_DIR` is process-wide and the tests run in parallel. A folder per test let one test's
/// Drop delete the folder another test's compose was about to read, so `plugins::start` found no folder and the host was
/// None ("the plugin host is up" panic in CI). One empty folder for the whole binary, set once, never removed mid-run.
fn shared_plugins_dir() -> &'static std::path::Path {
    static DIR: std::sync::OnceLock<std::path::PathBuf> = std::sync::OnceLock::new();
    DIR.get_or_init(|| {
        let d = std::env::temp_dir().join(format!("infantry-sec-plugin-1-plugins-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(d.join("official")).unwrap();
        d
    })
}

/// A composed daemon with no plugin installed, a scratch vault, and the three secrets the owner saved.
fn scratch() -> Scratch {
    let dir = std::env::temp_dir().join(format!("infantry-sec-plugin-1-{}", uuid::Uuid::new_v4()));
    let state = dir.join("state");
    std::fs::create_dir_all(&state).unwrap();
    std::env::set_var("INFANTRY_PLUGINS_DIR", shared_plugins_dir());
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

#[tokio::test(flavor = "multi_thread")]
async fn a_plugin_writes_reads_and_clears_only_its_own_secret() {
    let s = scratch();
    let evil = s.plugin("Evil_Mod", &[]);
    // Write, read back, overwrite, clear.
    send(&evil, "plugin_secret_set", json!({ "field": "KEY", "value": "fake-written-0123456789" })).expect("own write");
    assert_eq!(send(&evil, "plugin_secret_get", json!({ "field": "KEY" })).unwrap()["value"], "fake-written-0123456789");
    let ack = send(&evil, "plugin_secret_set", json!({ "module": "evil_mod", "field": "KEY", "value": "fake-second-0123456789" })).unwrap();
    assert!(ack["value"].is_null(), "a write answer must not carry a value: {ack}");
    send(&evil, "plugin_secret_clear", json!({ "field": "KEY" })).expect("own clear");
    assert!(send(&evil, "plugin_secret_get", json!({ "field": "KEY" })).unwrap()["value"].is_null());
    send(&evil, "plugin_secret_clear", json!({ "field": "KEY" })).expect("clearing twice is fine");
    // Another module: refused, and the victim's secret is untouched.
    for (ty, data) in [("plugin_secret_set", json!({ "module": "Victim_Mod", "field": "TOKEN", "value": "fake-attack-0123456789" })), ("plugin_secret_clear", json!({ "module": "Victim_Mod", "field": "TOKEN" }))] {
        let e = send(&evil, ty, data).unwrap_err();
        assert!(e.contains("only its own") && !e.contains("fake-"), "{ty}: {e}");
    }
    let still = s.owner(&Envelope::new("plugin_secret_get", json!({ "module": "Victim_Mod", "field": "TOKEN" }).to_string())).unwrap();
    assert!(still[0].data.contains("fake-victim-token-0123456789"));
    // The Kotlin rules: bad name, INFANTRY_*, blank value; no error holds the value.
    for (field, value) in [("1bad", "fake-v-0123456789"), ("has-dash", "fake-v-0123456789"), ("INFANTRY_TOKEN", "fake-v-0123456789"), ("infantry_x", "fake-v-0123456789"), ("OK", "   ")] {
        let e = send(&evil, "plugin_secret_set", json!({ "field": field, "value": value })).unwrap_err();
        assert!(!e.contains("fake-v-"), "an error holds the value: {e}");
    }
    assert!(send(&evil, "plugin_secret_clear", json!({ "field": "INFANTRY_TOKEN" })).is_err());
    // A connection that is not the owner (a session, the CLI, a runner) is refused.
    for (ty, data) in [("plugin_secret_set", json!({ "module": "Evil_Mod", "field": "K", "value": "fake-v-0123456789" })), ("plugin_secret_clear", json!({ "module": "Evil_Mod", "field": "TOKEN" }))] {
        let e = s.c.request(&Envelope::new(ty, data.to_string()), false).map(|_| "answered".to_string()).unwrap_err();
        assert!(e.contains("owner only"), "{ty}: {e}");
    }
    let own = s.owner(&Envelope::new("plugin_secret_get", json!({ "module": "Evil_Mod", "field": "TOKEN" }).to_string())).unwrap();
    assert!(own[0].data.contains("fake-own-token-0123456789"), "the stored key must still be found");
}
