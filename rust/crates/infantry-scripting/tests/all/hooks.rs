// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Light proof: a lane hook stored in a project's hooks.json runs its script on the matching event.
use infantry_scripting::dispatcher::ScriptDispatcher;
use infantry_scripting::engine::ScriptEngines;
use infantry_scripting::hooks::{HookEvent, HookOn, HookStore, HooksConfig, ScriptHooks};
use infantry_scripting::host::FakeHost;
use infantry_scripting::runlog::ScriptRunLog;
use infantry_scripting::store::{ProjectDirs, ScriptStore};
use serde_json::json;
use std::collections::BTreeMap;
use std::sync::Arc;

#[test]
fn hook_on_parse() {
    assert_eq!(HookOn::parse("lane  enter Done").unwrap(), HookOn::Lane { lane: "done".into(), exit: false });
    assert_eq!(HookOn::parse("every 30s").unwrap(), HookOn::Every(30));
    assert!(HookOn::parse("ticket.*").is_ok());
    assert!(HookOn::parse("Bad Event").is_err());
}

#[test]
fn lane_hook_runs_its_script() {
    let tmp = tempfile::tempdir().unwrap();
    let infantry = tmp.path().join(".infantry");
    std::fs::create_dir_all(infantry.join("scripts")).unwrap();
    std::fs::write(infantry.join("scripts/note.lua"), "ih.call('infantry_ticket_comment', {ticketId = input.args.ticketId, body = 'to ' .. input.args.lane})").unwrap();
    let dir = infantry.clone();
    let dirs: ProjectDirs = Arc::new(move |p: &str| (p == "p1").then(|| dir.clone()));
    let engines = ScriptEngines::standard();
    let exts = engines.extensions();
    let scripts = ScriptStore::new(dirs.clone(), Arc::new(move || exts.clone()));
    let store = Arc::new(HookStore::new(dirs, None));
    let host = FakeHost::new(&["infantry_ticket_comment", "infantry_ticket_get"]);
    let probe = host.clone();
    let dispatcher = ScriptDispatcher::new(Arc::new(host), Arc::new(BTreeMap::new), Arc::new(|| 3));
    let cfg = HooksConfig { projects: Arc::new(|| vec!["p1".to_string()]), ..HooksConfig::default() };
    let hooks = Arc::new(ScriptHooks::new(store.clone(), scripts, engines, dispatcher, Arc::new(ScriptRunLog::default()), cfg));
    let tools_hook = infantry_scripting::hooks::Hook::new("n1", HookOn::parse("lane enter done").unwrap(), "note");
    store.add("p1", tools_hook).unwrap();

    hooks.on_event(HookEvent::new("ticket.moved", json!({"ticketId": "t-1", "projectId": "p1", "from": "review", "to": "done"})));
    hooks.on_event(HookEvent::new("ticket.moved", json!({"ticketId": "t-1", "projectId": "p1", "from": "todo", "to": "review"})));
    hooks.on_event(HookEvent::new("ticket.moved", json!({"ticketId": "t-2", "projectId": "other", "from": "review", "to": "done"})));

    let comments = probe.named("infantry_ticket_comment");
    assert_eq!(comments.len(), 1, "{:?}", probe.calls());
    assert_eq!(comments[0]["body"], json!("to done"));
}
