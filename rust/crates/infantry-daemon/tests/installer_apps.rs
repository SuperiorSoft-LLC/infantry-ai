// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! APPS-ROOT-1: the pure rules of apps. An app's manifest lists `core` and `optional` members; installing it brings the
//! core set first (the app last); removing it takes its members away unless another app lists them or a module that stays
//! requires them. No daemon, no ports.
use infantry_daemon::compose::installer::{app_removal, catalog, expand_apps, plan, AppDecl};
use serde_json::{json, Value};
use std::collections::BTreeSet;
use std::path::Path;

fn module(root: &Path, id: &str, extra: Value) {
    let dir = root.join("official").join(id);
    std::fs::create_dir_all(&dir).unwrap();
    let mut m = json!({ "name": id, "version": "1.0.0" });
    m.as_object_mut().unwrap().extend(extra.as_object().unwrap().clone());
    std::fs::write(dir.join("module.json"), m.to_string()).unwrap();
}

#[test]
fn an_app_installs_its_core_members_first_and_leaves_optional_ones_out() {
    let src = std::env::temp_dir().join(format!("infantry-apps-{}", uuid::Uuid::new_v4()));
    module(&src, "App_Base", json!({}));
    module(&src, "App_Core", json!({ "requires": ["App_Base"] }));
    module(&src, "App_Extra", json!({}));
    module(&src, "App_Root", json!({ "app": { "core": ["App_Core", "App_Base"], "optional": ["App_Extra"] } }));
    let offers = catalog(&[src.clone()]);
    let root = offers.iter().find(|o| o.id == "App_Root").unwrap();
    assert_eq!(root.app, Some(AppDecl { core: vec!["App_Core".into(), "App_Base".into()], optional: vec!["App_Extra".into()] }));
    let want = expand_apps(&["App_Root".into()], &offers);
    assert_eq!(want, ["App_Core", "App_Base", "App_Root"], "core members, then the app");
    let order: Vec<String> = plan(&want, &offers, &BTreeSet::new()).unwrap().into_iter().map(|o| o.id).collect();
    assert_eq!(order, ["App_Base", "App_Core", "App_Root"]);
    assert_eq!(expand_apps(&["App_Extra".into()], &offers), ["App_Extra"], "an optional member installs on its own");
    let _ = std::fs::remove_dir_all(&src);
}

#[test]
fn removing_an_app_keeps_what_another_app_or_another_module_still_needs() {
    let m = |id: &str, extra: Value| {
        let mut v = json!({ "name": id });
        v.as_object_mut().unwrap().extend(extra.as_object().unwrap().clone());
        (id.to_string(), v)
    };
    let mods = vec![
        m("Inst", json!({ "bootstrap": true })),
        m("A", json!({ "app": { "core": ["Shared", "OnlyA", "Dep"], "optional": ["OptA"] } })),
        m("B", json!({ "app": { "core": ["Shared"] } })),
        m("Shared", json!({})),
        m("OnlyA", json!({})),
        m("OptA", json!({})),
        m("Dep", json!({})),
        m("Stray", json!({ "requires": ["Dep"] })),
    ];
    let (order, kept) = app_removal("A", &mods, Some("Inst")).unwrap();
    assert_eq!(order, ["OnlyA", "OptA", "A"]);
    assert!(kept.contains(&("Shared".into(), "also part of B".into())), "{kept:?}");
    assert!(kept.contains(&("Dep".into(), "required by Stray".into())), "{kept:?}");
    assert!(app_removal("Shared", &mods, None).is_none(), "a plain module is no app");
}
