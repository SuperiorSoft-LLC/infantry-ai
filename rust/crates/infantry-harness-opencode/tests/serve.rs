// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The `opencode serve` protocol, offline: the translator fed what the process and the server would say.

use infantry_harness::{ApprovalMode, Harness, HarnessEvent, LaunchRequest, Outbound, Translator, TranslatorInput, TurnEnd};
use infantry_harness_opencode::{serve::ServeTranslator, OpenCodeHarness, PASSWORD_ENV, WATCH_PLUGIN};
use std::path::PathBuf;

fn http(out: &[Outbound]) -> Vec<(u64, String, String, bool)> {
    out.iter()
        .filter_map(|o| match o {
            Outbound::Http { id, request, stream } => {
                Some((*id, request.method.clone(), request.url.clone(), *stream))
            }
            _ => None,
        })
        .collect()
}

fn sse(t: &mut ServeTranslator, id: u64, json: &str) -> Vec<HarnessEvent> {
    t.feed(TranslatorInput::HttpLine { id, line: format!("data: {json}") })
}

/// Boots the translator to ready; returns the event-stream id.
fn boot(t: &mut ServeTranslator) -> u64 {
    t.feed(TranslatorInput::Stdout("opencode server listening on http://127.0.0.1:5123".into()));
    let out = http(&t.take_outbound());
    assert_eq!(out.len(), 1);
    let (id, method, url, stream) = out[0].clone();
    assert_eq!((method.as_str(), url.as_str(), stream), ("GET", "http://127.0.0.1:5123/event", true));
    t.feed(TranslatorInput::HttpOpened { id, status: 200 });
    id
}

#[test]
fn new_turn_creates_a_session_and_completes() {
    let mut t = ServeTranslator::new("pw", ApprovalMode::AllowAll, "opencode/free".into(), None, 2);
    t.send_turn("hi"); // before ready: waits
    assert!(t.take_outbound().is_empty());
    let ev = boot(&mut t);
    let out = http(&t.take_outbound());
    assert_eq!(out[0].1, "POST");
    assert!(out[0].2.ends_with("/session"));
    let evs = t.feed(TranslatorInput::HttpResponse { id: out[0].0, status: Some(200), body: r#"{"id":"ses_1"}"#.into() });
    assert!(evs.contains(&HarnessEvent::ProviderSession("ses_1".into())));
    let out = http(&t.take_outbound());
    assert!(out[0].2.ends_with("/session/ses_1/prompt_async"));
    let evs = t.feed(TranslatorInput::HttpResponse { id: out[0].0, status: Some(204), body: String::new() });
    assert!(evs.contains(&HarnessEvent::TurnStarted));

    sse(&mut t, ev, r#"{"type":"session.status","properties":{"sessionID":"ses_1","status":{"type":"busy"}}}"#);
    let evs = sse(&mut t, ev, r#"{"type":"message.part.updated","properties":{"part":{"id":"p1","sessionID":"ses_1","messageID":"m2","type":"text","text":"hello","time":{"start":1,"end":2}}}}"#);
    assert_eq!(evs, vec![HarnessEvent::Text("hello".into())]);
    let evs = sse(&mut t, ev, r#"{"type":"message.part.updated","properties":{"part":{"id":"p2","sessionID":"ses_1","type":"step-finish","tokens":{"input":10,"output":5,"reasoning":1,"cache":{"read":3,"write":0}}}}}"#);
    assert!(matches!(&evs[0], HarnessEvent::Usage(u) if u.input_tokens == 13 && u.cached_input_tokens == 3 && u.output_tokens == 5));
    // Another session's events are not ours.
    assert!(sse(&mut t, ev, r#"{"type":"session.idle","properties":{"sessionID":"ses_other"}}"#).is_empty());
    let evs = sse(&mut t, ev, r#"{"type":"session.idle","properties":{"sessionID":"ses_1"}}"#);
    assert_eq!(evs, vec![HarnessEvent::TurnEnded { end: TurnEnd::Completed, reason: None }]);
}

#[test]
fn resume_skips_session_creation_and_silence_fails() {
    let mut t = ServeTranslator::new("pw", ApprovalMode::Deny, "opencode/free".into(), Some("ses_9".into()), 2);
    let ev = boot(&mut t);
    t.send_turn("again");
    let out = http(&t.take_outbound());
    assert!(out[0].2.ends_with("/session/ses_9/prompt_async"));
    sse(&mut t, ev, r#"{"type":"session.status","properties":{"sessionID":"ses_9","status":{"type":"busy"}}}"#);
    let evs = sse(&mut t, ev, r#"{"type":"session.idle","properties":{"sessionID":"ses_9"}}"#);
    assert!(matches!(evs.last(), Some(HarnessEvent::TurnEnded { end: TurnEnd::Failed, reason: Some(r) }) if r.contains("no reply")));
}

#[test]
fn permissions_interrupt_and_death() {
    let mut t = ServeTranslator::new("pw", ApprovalMode::Ask, "m/x".into(), Some("ses_1".into()), 2);
    let ev = boot(&mut t);
    t.send_turn("go");
    t.take_outbound();
    // Infantry's own CLI is answered at once; anything else goes to the host.
    sse(&mut t, ev, r#"{"type":"permission.asked","properties":{"id":"per_1","sessionID":"ses_1","permission":"bash","patterns":["infantry ticket get 1"]}}"#);
    let out = http(&t.take_outbound());
    assert!(out[0].2.ends_with("/permission/per_1/reply"));
    let evs = sse(&mut t, ev, r#"{"type":"permission.asked","properties":{"id":"per_2","sessionID":"ses_1","permission":"bash","metadata":{"command":"rm x"}}}"#);
    assert!(matches!(&evs[0], HarnessEvent::ApprovalRequested { id, tool, input } if id == "per_2" && tool == "bash" && input == "rm x"));
    t.answer("per_2", false);
    assert!(http(&t.take_outbound())[0].2.ends_with("/permission/per_2/reply"));

    assert!(t.interrupt());
    let out = http(&t.take_outbound());
    assert!(out[0].2.ends_with("/session/ses_1/abort"));
    let evs = t.feed(TranslatorInput::HttpResponse { id: out[0].0, status: Some(200), body: "true".into() });
    assert!(evs.iter().any(|e| matches!(e, HarnessEvent::TurnEnded { end: TurnEnd::Interrupted, .. })));

    t.send_turn("next");
    t.take_outbound();
    t.feed(TranslatorInput::Stderr("boom".into()));
    let evs = t.feed(TranslatorInput::Exited { code: 1 });
    assert!(evs.iter().any(|e| matches!(e, HarnessEvent::TurnEnded { end: TurnEnd::Failed, reason: Some(r) } if r.contains("boom"))));
    assert_eq!(evs.last(), Some(&HarnessEvent::Exited { code: Some(1) }));
}

#[test]
fn launch_plan_carries_password_config_and_watch_plugin() {
    let h = OpenCodeHarness::default();
    let mut req = LaunchRequest::new(PathBuf::from("."));
    req.approval = ApprovalMode::AllowAll;
    assert_eq!(h.assets(&req)[0].name, WATCH_PLUGIN);
    req.staged.push((WATCH_PLUGIN.into(), "C:\\stage\\infantry-watch.js".into()));
    let plan = h.launch(&req).unwrap();
    assert_eq!(plan.spec.args, ["serve", "--hostname", "127.0.0.1", "--port", "0"]);
    let env: std::collections::HashMap<_, _> = plan.spec.env.iter().cloned().collect();
    assert!(env[PASSWORD_ENV].len() >= 24);
    let cfg: serde_json::Value = serde_json::from_str(&env["OPENCODE_CONFIG_CONTENT"]).unwrap();
    assert_eq!(cfg["plugin"][0], "file:///C:/stage/infantry-watch.js");
    assert_eq!(cfg["permission"]["bash"]["infantry *"], "allow");
}

/// HARNESS-GAP G12: `/mcp` is polled while the server is up; a change is reported once; reconnect = disconnect, connect.
#[test]
fn mcp_state_is_polled_reported_on_change_and_reconnected() {
    let mut t = ServeTranslator::new("pw", ApprovalMode::AllowAll, "opencode/free".into(), None, 2);
    t.on_timer(10_000);
    assert!(t.take_outbound().is_empty(), "not up yet: nothing asked");
    boot(&mut t);
    t.take_outbound();
    t.on_timer(10_000);
    let out = http(&t.take_outbound());
    assert_eq!((out.len(), out[0].1.as_str()), (1, "GET"));
    assert!(out[0].2.ends_with("/mcp"));
    t.on_timer(10_001);
    assert!(t.take_outbound().is_empty(), "one ask at a time, throttled");
    let body = r#"{"infantry":{"status":"failed"},"github":{"status":"connected"}}"#;
    let evs = t.feed(TranslatorInput::HttpResponse { id: out[0].0, status: Some(200), body: body.into() });
    assert!(evs.contains(&HarnessEvent::McpState { state: "failed".into(), servers: vec![("github".into(), "connected".into()), ("infantry".into(), "failed".into())] }));
    t.on_timer(10_000 + infantry_harness_opencode::serve::MCP_POLL_MS);
    let again = http(&t.take_outbound());
    let evs = t.feed(TranslatorInput::HttpResponse { id: again[0].0, status: Some(200), body: body.into() });
    assert!(!evs.iter().any(|e| matches!(e, HarnessEvent::McpState { .. })), "unchanged: not reported again");

    assert!(t.reconnect_mcp());
    let d = http(&t.take_outbound());
    assert!(d[0].2.ends_with("/mcp/infantry/disconnect"));
    t.feed(TranslatorInput::HttpResponse { id: d[0].0, status: Some(400), body: "not connected".into() });
    let c = http(&t.take_outbound());
    assert!(c[0].2.ends_with("/mcp/infantry/connect"));
}
