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
    assert_eq!(found[1]["signed"], "digest", "the check result: digest, signed, untrusted, unsigned or bad");

    // A module whose files do not match its signature file is refused, and nothing is left behind.
    let (code, _, err) = admin(&c, &["install", "Ps_Bad"]);
    assert_eq!(code, 1);
    assert!(err.contains("signature check failed"), "{err}");
    assert!(!plugins.join("official/Ps_Bad").exists());

    assert_eq!(admin(&c, &["install", "Ps_Good"]).0, 0);
    assert!(plugins.join("official/Ps_Good/module.json").is_file());
    assert_eq!(admin(&c, &["disable", "Ps_Good"]).0, 0);
    assert_eq!(admin(&c, &["enable", "Ps_Good"]).0, 0);
    // An update: the source ships a newer version; the check lists it, a tampered newer copy is refused and the old one
    // stays loaded, a good one swaps in.
    assert_eq!(admin(&c, &["updates"]).1.trim(), "[]");
    std::fs::write(good.join("module.json"), json!({ "name": "Ps_Good", "version": "1.2.0", "ui": { "apiVersion": 1, "entry": "ui/dist/index.js" } }).to_string()).unwrap();
    let ups: Value = serde_json::from_str(&admin(&c, &["updates"]).1).unwrap();
    assert_eq!((ups[0]["id"].as_str(), ups[0]["installed"].as_str(), ups[0]["available"].as_str()), (Some("Ps_Good"), Some("1.0.0"), Some("1.2.0")));
    assert!(ups[0]["check"].is_string() && ups[0]["grants"].is_array() && ups[0]["license"].is_string(), "{}", ups[0]);
    let (code, _, err) = admin(&c, &["update", "Ps_Good"]);
    assert_eq!(code, 1, "the digest no longer matches the changed manifest");
    assert!(err.contains("signature check failed"), "{err}");
    assert!(std::fs::read_to_string(plugins.join("official/Ps_Good/module.json")).unwrap().contains("1.0.0"), "the old copy stays");
    std::fs::write(good.join(sources::DIGEST_FILE), sources::folder_digest(&good).unwrap()).unwrap();
    assert_eq!(admin(&c, &["update", "Ps_Good"]).0, 0);
    assert!(std::fs::read_to_string(plugins.join("official/Ps_Good/module.json")).unwrap().contains("1.2.0"));
    assert!(!plugins.join(".installing/Ps_Good.old").exists() && !plugins.join(".installing/Ps_Good").exists(), "no leftovers");
    assert_eq!(admin(&c, &["update"]).1, "nothing to update");

    assert_eq!(admin(&c, &["uninstall", "Ps_Good"]).0, 0);
    assert!(!plugins.join("official/Ps_Good").exists());

    // ---- ed25519: a signed module is checked against the trusted signers ----
    use base64::Engine;
    use ring::signature::{Ed25519KeyPair, KeyPair};
    let b64 = base64::engine::general_purpose::STANDARD;
    let pair = Ed25519KeyPair::from_seed_unchecked(&[7u8; 32]).unwrap();
    let sign = |dir: &Path| {
        let digest = sources::folder_digest(dir).unwrap();
        let sig = pair.sign(digest.as_bytes());
        std::fs::write(dir.join(sources::SIG_FILE), json!({ "publicKey": b64.encode(pair.public_key().as_ref()), "digest": digest, "signature": b64.encode(sig.as_ref()), "license": "Apache-2.0" }).to_string()).unwrap();
    };
    let signed = module(&src, "Ps_Signed");
    sign(&signed);
    let shown: Value = serde_json::from_str(&admin(&c, &["show", "Ps_Signed"]).1).unwrap();
    assert_eq!((shown["check"].as_str(), shown["license"].as_str()), (Some("untrusted"), Some("Apache-2.0")));
    // PLUGINS-3: the listing row carries the same check, licence and grants as `show`, so the dialog needs no second call.
    let rows: Vec<Value> = serde_json::from_str(&admin(&c, &["available"]).1).unwrap();
    let row = rows.iter().find(|r| r["id"] == "Ps_Signed").expect("the offer is listed");
    assert_eq!((row["check"].as_str(), row["signed"].as_str(), row["license"].as_str()), (Some("untrusted"), Some("untrusted"), Some("Apache-2.0")));
    assert!(row["grants"].is_array(), "{row}");
    let (code, _, err) = admin(&c, &["install", "Ps_Signed"]);
    assert_eq!(code, 1);
    assert!(err.contains("not a trusted signer"), "{err}");
    assert!(!plugins.join("official/Ps_Signed").exists());
    assert_eq!(admin(&c, &["signer-add", "acme", &b64.encode(pair.public_key().as_ref())]).0, 0);
    assert!(admin(&composed(&rt, &tmp, &state), &["signers"]).1.contains("acme"), "the signer is kept on the daemon");
    let shown: Value = serde_json::from_str(&admin(&c, &["show", "Ps_Signed"]).1).unwrap();
    assert_eq!((shown["check"].as_str(), shown["signer"].as_str()), (Some("signed"), Some("acme")));
    // A file changed after signing is refused even from a trusted signer.
    std::fs::write(signed.join("ui/dist/index.js"), "export default { evil: 1 };").unwrap();
    let (code, _, err) = admin(&c, &["install", "Ps_Signed"]);
    assert_eq!(code, 1);
    assert!(err.contains("changed after they were signed"), "{err}");
    sign(&signed);
    assert_eq!(admin(&c, &["install", "Ps_Signed"]).0, 0);
    // An untrusted key installs only with the owner's --approve.
    assert_eq!(admin(&c, &["signer-remove", "acme"]).0, 0);
    let other = module(&src, "Ps_Other");
    sign(&other);
    assert_eq!(admin(&c, &["install", "Ps_Other"]).0, 1);
    assert_eq!(admin(&c, &["install", "Ps_Other", "--approve"]).0, 0);

    for m in ["Ps_Signed", "Ps_Other"] {
        assert_eq!(admin(&c, &["uninstall", m]).0, 0);
    }
    let id = list[0]["id"].as_str().unwrap();
    assert_eq!(admin(&c, &["source-remove", id]).0, 0);
    assert_eq!(admin(&c, &["search", "test plugin"]).1.trim(), "[]");
    assert!(src.join("official/Ps_Good").is_dir(), "the user's folder is untouched");
    let _ = std::fs::remove_dir_all(&tmp);
}
