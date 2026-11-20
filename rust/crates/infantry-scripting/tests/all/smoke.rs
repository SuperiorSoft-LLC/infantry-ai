// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Light proof: every bundled script runs through all its phases against the fake host (the Kotlin
//! BundledGlobalScriptsTest scenarios), plus the sandbox limits and the error line number.
use infantry_scripting::dispatcher::{RunOptions, ScriptDispatcher};
use infantry_scripting::engine::{ScriptCaller, ScriptEngine, ScriptLimits, ScriptOutcome, ScriptResult};
use infantry_scripting::host::{FakeHost, ToolInvoker};
use infantry_scripting::lua::LuaEngine;
use infantry_scripting::store::{BUNDLED, BUNDLED_INDEX};
use serde_json::{json, Map, Value};
use std::collections::BTreeMap;
use std::sync::Arc;

const TOOLS: &[&str] = &[
    "infantry_notify", "infantry_chat_note", "infantry_ticket_comment", "infantry_ticket_comments", "infantry_ticket_update", "infantry_ticket_get",
    "infantry_ticket_move", "infantry_ticket_start", "infantry_script_hook", "infantry_script_stats", "infantry_session_remove", "infantry_prompt",
    "infantry_message", "infantry_board", "infantry_list_sessions", "infantry_get_session", "infantry_processes", "infantry_processes_reap",
    "infantry_usage", "infantry_settings_get", "infantry_settings_set", "infantry_project_config_get", "infantry_project_config_set", "infantry_exec",
];

struct Sc {
    args: Value,
    answers: Vec<(&'static str, String)>,
    tools: Vec<&'static str>,
    log: Option<&'static str>,
    min_phases: u32,
}

fn sc(args: Value, answers: &[(&'static str, &str)], tools: &[&'static str], log: Option<&'static str>, min_phases: u32) -> Sc {
    Sc { args, answers: answers.iter().map(|(k, v)| (*k, v.to_string())).collect(), tools: tools.to_vec(), log, min_phases }
}

fn source(name: &str) -> &'static str {
    let file = format!("{name}.lua");
    BUNDLED.iter().find(|(n, _)| *n == file).map(|(_, s)| *s).unwrap_or_else(|| panic!("no bundled {file}"))
}

fn dispatcher(host: FakeHost, phases: u32) -> ScriptDispatcher {
    let aliases: BTreeMap<String, String> =
        [("message", "infantry_message"), ("comment", "infantry_ticket_comment")].iter().map(|(a, b)| (a.to_string(), b.to_string())).collect();
    ScriptDispatcher::new(Arc::new(host), Arc::new(move || aliases.clone()), Arc::new(move || phases))
}

fn now() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_millis() as u64
}

fn scenarios() -> Vec<(&'static str, Vec<Sc>)> {
    let now = now();
    let t = "ticket-aaaa1111";
    let pm = "s-pm";
    let one = format!(r#"{{"script":"bad","ok":false,"error":"boom","at":{now}}}"#);
    let bad = format!(r#"{{"runs":[{one},{one},{one}]}}"#);
    let sessions = format!(r#"{{"sessions":[{{"id":"s-worker-1","agentId":"a1","role":"backend","status":"BUSY","lastOutputAt":{}}}]}}"#, now - 910_000);
    let pin = format!(r#"{{"ticketId":"{t}","provider":"other","model":"x","weight":2,"role":"backend"}}"#);
    vec![
        ("hook-health", vec![sc(json!({"pm": pm}), &[("infantry_script_stats", &bad)], &["infantry_notify", "infantry_chat_note"], None, 2)]),
        ("find-ticket", vec![sc(json!({"q": "login"}), &[("infantry_board", r#"{"board":["  aaaa1111 | login page | backend"]}"#)], &[], Some("login page"), 2)]),
        ("comment-tail", vec![sc(json!({"ticket": t}), &[("infantry_ticket_comments", "first\nlast line")], &[], Some("last line"), 2)]),
        (
            "needs-owner-alert",
            vec![
                sc(json!({"ticketId": t, "body": "This needs owner decision"}), &[], &["infantry_notify"], None, 1),
                sc(json!({"ticketId": t}), &[("infantry_ticket_comments", "abc12345 x by agent-1: needs the owner")], &["infantry_notify"], None, 2),
            ],
        ),
        ("worker-issue-wake", vec![sc(json!({"ticketId": t, "wake": pm}), &[("infantry_ticket_comments", "c1 issue/open by agent-77 question")], &["infantry_chat_note"], None, 2)]),
        (
            "wake-on-log",
            vec![sc(json!({"path": "x.log", "pattern": "ready"}), &[("infantry_exec", r#"{"exitCode":0,"stdoutTail":"found: ready"}"#)], &["infantry_exec", "infantry_chat_note"], None, 2)],
        ),
        (
            "done-forward",
            vec![sc(
                json!({"ticket": "aaaa1111", "ticketId": t, "recipient": "pm@other", "pm": pm, "hookId": "h1", "startNext": "bbbb2222"}),
                &[("infantry_ticket_comments", "the report")],
                &["infantry_message", "infantry_ticket_start", "infantry_notify", "infantry_script_hook"],
                None,
                3,
            )],
        ),
        (
            "goal-handoff",
            vec![sc(
                json!({"goals": ["aaaa1111"], "ticketId": t, "recipient": "pm@other", "pm": pm, "hookId": "h1"}),
                &[("infantry_ticket_get", r#"{"status":"done"}"#)],
                &["infantry_message", "infantry_chat_note", "infantry_script_hook"],
                None,
                2,
            )],
        ),
        (
            "goal-autostart-off",
            vec![sc(
                json!({"goals": ["aaaa1111"], "ticketId": t, "pm": pm, "offArgsJson": "{}"}),
                &[("infantry_ticket_get", r#"{"status":"done"}"#)],
                &["infantry_chat_note", "infantry_script_hook", "infantry_prompt"],
                None,
                2,
            )],
        ),
        ("mem-check", vec![sc(json!({"wake": pm}), &[("infantry_processes", "memory: 4000 MB free\nrogue (2)\n")], &["infantry_processes_reap", "infantry_chat_note"], None, 2)]),
        // No Kotlin scenario: a smoke run (no error) with an empty session list.
        ("worker-waiting-nudge", vec![sc(json!({"idleSeconds": 60, "tickSec": 20}), &[("infantry_list_sessions", r#"{"sessions":[]}"#)], &[], None, 1)]),
        ("worker-silent-ping", vec![sc(json!({"pm": pm}), &[("infantry_list_sessions", &sessions)], &["infantry_message", "infantry_chat_note"], None, 2)]),
        (
            "worker-cap",
            vec![sc(
                json!({"fill": true}),
                &[
                    ("infantry_settings_get", r#"{"value":2}"#),
                    ("infantry_project_config_get", r#"{"data":{"workers.cap.lowered":"1","workers.cap.status":"Lowered to 1 by the usage governor"}}"#),
                    ("infantry_board", "  aaaa1111 | todo | backend | a ticket\n"),
                ],
                &["infantry_settings_get", "infantry_project_config_get", "infantry_board"],
                None,
                2,
            )],
        ),
        (
            "usage-governor",
            vec![sc(
                json!({"project": "p1", "pm": pm}),
                &[
                    ("infantry_usage", r#"{"meters":[{"meter":"claude","rows":[{"label":"Current week","paceDelta":1.5}]}]}"#),
                    ("infantry_settings_get", r#"{"value":{"maxWorkers":2}}"#),
                    ("infantry_project_config_get", r#"{"data":{},"routingRules":[]}"#),
                ],
                &["infantry_settings_set", "infantry_project_config_set", "infantry_notify"],
                None,
                2,
            )],
        ),
        (
            "lane-park",
            vec![sc(json!({"ticketId": t, "doneLane": "done", "from": "in_progress", "to": "todo", "workerSession": "w1"}), &[], &["infantry_session_remove", "infantry_ticket_comment"], None, 2)],
        ),
        (
            "lane-pending-note",
            vec![
                sc(json!({"ticketId": t, "from": "todo", "to": "pending"}), &[], &["infantry_ticket_comment"], None, 1),
                sc(json!({"ticketId": t, "from": "pending", "to": "todo", "startLane": "todo"}), &[], &["infantry_ticket_start"], None, 1),
            ],
        ),
        (
            "lane-pin-check",
            vec![sc(
                json!({"ticketId": t, "to": "todo", "provider": "claude", "allowedProviders": ["claude"], "defaultModel": "m1"}),
                &[("infantry_ticket_get", &pin)],
                &["infantry_ticket_update", "infantry_ticket_comment"],
                None,
                2,
            )],
        ),
        (
            "lane-one-worker",
            vec![
                sc(
                    json!({"ticketId": t, "maxInProgress": 1, "exemptRoles": ["version-control"], "workerSession": "w1", "kickPrompt": "go {ticket}"}),
                    &[("infantry_ticket_get", r#"{"role":"backend","title":"t"}"#), ("infantry_board", r#"{"board":["  cccc3333 | other | backend 1234abcd x"]}"#)],
                    &["infantry_session_remove", "infantry_ticket_move", "infantry_ticket_comment"],
                    None,
                    2,
                ),
                sc(
                    json!({"ticketId": t, "maxInProgress": 1, "workerSession": "w1", "kickPrompt": "go {ticket}"}),
                    &[
                        ("infantry_ticket_get", r#"{"role":"backend","title":"t"}"#),
                        ("infantry_board", r#"{"board":[]}"#),
                        ("infantry_get_session", r#"{"status":"IDLE","runCount":1}"#),
                    ],
                    &["infantry_prompt"],
                    None,
                    3,
                ),
            ],
        ),
    ]
}

fn invoker() -> ToolInvoker {
    ToolInvoker::agent("s1", "pm", Some("p"), "script:bundled")
}

#[test]
fn every_bundled_script_has_a_scenario_and_parses() {
    let mut index: Vec<String> = serde_json::from_str::<Vec<String>>(BUNDLED_INDEX).unwrap().into_iter().map(|f| f.trim_end_matches(".lua").to_string()).collect();
    index.sort();
    let mut names: Vec<String> = scenarios().iter().map(|(n, _)| n.to_string()).collect();
    names.sort();
    assert_eq!(index, names);
    for (file, src) in BUNDLED {
        assert_eq!(LuaEngine::new().check(src), None, "{file} does not parse");
    }
}

#[test]
fn every_bundled_script_runs_end_to_end() {
    let mut failures = Vec::new();
    for (name, list) in scenarios() {
        let src = source(name);
        for (i, s) in list.iter().enumerate() {
            let label = format!("{name}#{}", i + 1);
            let mut host = FakeHost::new(TOOLS);
            for (k, v) in &s.answers {
                host = host.answer(k, v);
            }
            let probe = host.clone();
            let args: Map<String, Value> = s.args.as_object().unwrap().clone();
            let r = dispatcher(host, 6).run(&LuaEngine::new(), src, &args, ScriptLimits::default(), &ScriptCaller::new("s1", "pm"), &invoker(), RunOptions::default(), &mut |_| {});
            if !r.ok() {
                failures.push(format!("{label}: {}", r.render(name)));
                continue;
            }
            if r.phases < s.min_phases {
                failures.push(format!("{label}: ran {} phase(s), wanted >= {}", r.phases, s.min_phases));
            }
            for t in &s.tools {
                if probe.named(t).is_empty() {
                    failures.push(format!("{label}: never called {t}; called {:?}", probe.calls().iter().map(|c| c.0.clone()).collect::<Vec<_>>()));
                }
            }
            if let Some(l) = s.log {
                if !r.logs.iter().any(|x| x.contains(l)) {
                    failures.push(format!("{label}: log lacks '{l}': {:?}", r.logs));
                }
            }
        }
    }
    assert!(failures.is_empty(), "{}", failures.join("\n"));
}

#[test]
fn every_bundled_script_dry_runs() {
    for (name, list) in scenarios() {
        let args = list[0].args.as_object().unwrap().clone();
        let opts = RunOptions { dry_run: true, progress_to: None };
        let r = dispatcher(FakeHost::new(TOOLS), 6).run(&LuaEngine::new(), source(name), &args, ScriptLimits::default(), &ScriptCaller::new("s1", "pm"), &invoker(), opts, &mut |_| {});
        assert!(r.ok(), "{name} dry run: {}", r.render(name));
    }
}

fn run(src: &str, limits: ScriptLimits) -> ScriptResult {
    LuaEngine::new().run(src, &json!({"args": {}}), limits, &ScriptCaller::default())
}

#[test]
fn runaway_loop_is_killed_by_the_step_budget() {
    let r = run("while true do end", ScriptLimits { max_steps: 100_000, timeout_ms: 10_000, ..ScriptLimits::default() });
    assert_eq!(r.outcome, ScriptOutcome::BudgetExceeded, "{:?}", r.error);
}

#[test]
fn runaway_loop_is_killed_by_the_deadline() {
    let r = run("while true do end", ScriptLimits { max_steps: u64::MAX / 2, timeout_ms: 300, ..ScriptLimits::default() });
    assert_eq!(r.outcome, ScriptOutcome::Timeout, "{:?}", r.error);
}

#[test]
fn pcall_cannot_swallow_the_budget() {
    let r = run("pcall(function() while true do end end) ih.call('infantry_notify', {})", ScriptLimits { max_steps: 100_000, ..ScriptLimits::default() });
    assert_eq!(r.outcome, ScriptOutcome::BudgetExceeded);
}

#[test]
fn memory_is_capped() {
    let lim = ScriptLimits { max_alloc_bytes: 4 * 1024 * 1024, ..ScriptLimits::default() };
    let r = run("local t = {} for i = 1, 10000000 do t[i] = tostring(i) .. 'xxxxxxxxxxxxxxxx' end", lim);
    assert_eq!(r.outcome, ScriptOutcome::AllocExceeded, "{:?}", r.error);
    let r = run("local s = string.rep('x', 1e9)", lim);
    assert_eq!(r.outcome, ScriptOutcome::AllocExceeded, "{:?}", r.error);
}

#[test]
fn an_error_names_its_line() {
    let r = run("local a = 1\nlocal b = 2\nerror('boom')", ScriptLimits::default());
    assert_eq!(r.outcome, ScriptOutcome::Error);
    let e = r.error.unwrap();
    assert!(e.contains("script:3") && e.contains("boom"), "{e}");
}

#[test]
fn queues_actions_and_converts_like_luaj() {
    let r = run("ih.call('infantry_notify', {n = 3, f = 1.5, s = '7', list = {1, 2}, obj = {}}) ih.exec 'git status' ih.log('hi')", ScriptLimits::default());
    assert_eq!(r.outcome, ScriptOutcome::Ok, "{:?}", r.error);
    assert_eq!(r.actions.len(), 2);
    assert_eq!(Value::Object(r.actions[0].args.clone()), json!({"n": 3, "f": 1.5, "s": "7", "list": [1, 2], "obj": {}}));
    assert_eq!(r.actions[1].name, "exec");
    assert_eq!(Value::Object(r.actions[1].args.clone()), json!({"command": "git status"}));
    assert_eq!(r.logs, vec!["hi".to_string()]);
}

#[test]
fn sandbox_has_no_io() {
    let r = run("return io.open('x')", ScriptLimits::default());
    assert_eq!(r.outcome, ScriptOutcome::Error);
}

#[test]
fn bundled_scripts_match_the_kotlin_copies() {
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../plugins/official/Infantry_Scripting/daemon/src/main/resources/global-scripts");
    if !dir.is_dir() {
        return;
    }
    for (file, src) in BUNDLED {
        let k = std::fs::read_to_string(dir.join(file)).unwrap();
        assert_eq!(k.replace("\r\n", "\n"), src.replace("\r\n", "\n"), "{file} drifted from the Kotlin copy");
    }
}
