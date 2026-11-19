// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! pi's rpc protocol, offline: the translator fed what `pi --mode rpc` prints.

use infantry_harness::{ApprovalMode, Harness, HarnessEvent, LaunchRequest, Outbound, Translator, TranslatorInput, TurnEnd};
use infantry_harness_pi::{parse_list_models, rpc::RpcTranslator, PiHarness, APPROVAL};
use std::path::PathBuf;

fn stdin(t: &mut RpcTranslator) -> Vec<serde_json::Value> {
    t.take_outbound()
        .into_iter()
        .flat_map(|o| match o {
            Outbound::Stdin(b) => String::from_utf8(b).unwrap().lines().map(|l| serde_json::from_str(l).unwrap()).collect(),
            _ => Vec::new(),
        })
        .collect()
}

fn feed(t: &mut RpcTranslator, line: &str) -> Vec<HarnessEvent> {
    t.feed(TranslatorInput::Stdout(line.into())).into_iter().filter(|e| !matches!(e, HarnessEvent::Stdout(_))).collect()
}

const SETTLED: &str = r#"{"type":"agent_settled","aborted":false}"#;

#[test]
fn turn_session_thinking_and_usage() {
    let mut t = RpcTranslator::new();
    t.send_turn("hi");
    let lines = stdin(&mut t);
    assert_eq!(lines[0]["type"], "prompt");
    assert_eq!(lines[0]["message"], "hi");
    assert_eq!(lines[1]["type"], "get_state");
    let evs = feed(&mut t, r#"{"type":"response","command":"get_state","success":true,"data":{"sessionId":"abc"}}"#);
    assert_eq!(evs, vec![HarnessEvent::ProviderSession("abc".into())]);
    assert_eq!(feed(&mut t, r#"{"type":"agent_start"}"#), vec![HarnessEvent::TurnStarted]);
    feed(&mut t, r#"{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","delta":"thin"}}"#);
    feed(&mut t, r#"{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","delta":"king"}}"#);
    let evs = feed(&mut t, r#"{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"Hello"}}"#);
    assert_eq!(evs, vec![HarnessEvent::Reasoning("thinking".into()), HarnessEvent::TextDelta("Hello".into())]);
    feed(&mut t, r#"{"type":"message_end","message":{"role":"assistant","stopReason":"stop","usage":{"input":10,"output":4,"cacheRead":2,"cacheWrite":1}}}"#);
    let evs = feed(&mut t, SETTLED);
    assert!(matches!(&evs[0], HarnessEvent::Usage(u) if u.input_tokens == 13 && u.cached_input_tokens == 2 && u.output_tokens == 4));
    assert_eq!(evs[1], HarnessEvent::TurnEnded { end: TurnEnd::Completed, reason: None });
}

#[test]
fn failed_run_exits_zero_but_fails() {
    let mut t = RpcTranslator::new();
    t.send_turn("x");
    stdin(&mut t);
    feed(&mut t, r#"{"type":"message_end","message":{"role":"assistant","stopReason":"error","errorMessage":"401 bad key"}}"#);
    let evs = feed(&mut t, SETTLED);
    assert!(matches!(evs.last(), Some(HarnessEvent::TurnEnded { end: TurnEnd::Failed, reason: Some(r) }) if r == "401 bad key"));
    // A refused prompt fails at once.
    t.send_turn("y");
    let id = stdin(&mut t)[0]["id"].as_str().unwrap().to_string();
    let evs = feed(&mut t, &format!(r#"{{"type":"response","command":"prompt","id":"{id}","success":false,"error":"busy"}}"#));
    assert!(matches!(evs.last(), Some(HarnessEvent::TurnEnded { end: TurnEnd::Failed, reason: Some(r) }) if r == "busy"));
}

#[test]
fn interrupt_approval_and_death() {
    let mut t = RpcTranslator::new();
    t.send_turn("long");
    stdin(&mut t);
    let evs = feed(&mut t, r#"{"type":"extension_ui_request","id":"d1","method":"confirm","title":"infantry-approval:bash","message":"rm x"}"#);
    assert!(matches!(&evs[0], HarnessEvent::ApprovalRequested { id, tool, input } if id == "d1" && tool == "bash" && input == "rm x"));
    t.answer("d1", true);
    assert_eq!(stdin(&mut t)[0]["confirmed"], true);
    feed(&mut t, r#"{"type":"extension_ui_request","id":"d2","method":"select","title":"pick"}"#);
    assert_eq!(stdin(&mut t)[0]["cancelled"], true);

    assert!(t.interrupt());
    assert_eq!(stdin(&mut t)[0]["type"], "abort");
    let evs = feed(&mut t, r#"{"type":"agent_settled","aborted":true}"#);
    assert_eq!(evs.last(), Some(&HarnessEvent::TurnEnded { end: TurnEnd::Interrupted, reason: None }));

    t.send_turn("again");
    let evs = t.feed(TranslatorInput::Exited { code: 3 });
    assert!(evs.iter().any(|e| matches!(e, HarnessEvent::TurnEnded { end: TurnEnd::Failed, .. })));
}

#[test]
fn compaction_over_the_limit() {
    let mut t = RpcTranslator::new().with_context_limit(Some(1000));
    t.send_turn("x");
    stdin(&mut t);
    feed(&mut t, r#"{"type":"message_end","message":{"role":"assistant","stopReason":"stop","usage":{"input":1,"output":1}}}"#);
    let evs = feed(&mut t, SETTLED);
    assert!(!evs.iter().any(|e| matches!(e, HarnessEvent::TurnEnded { .. })), "held for stats");
    let stats = stdin(&mut t);
    assert_eq!(stats[0]["type"], "get_session_stats");
    let id = stats[0]["id"].as_str().unwrap();
    feed(&mut t, &format!(r#"{{"type":"response","command":"get_session_stats","id":"{id}","success":true,"data":{{"contextUsage":{{"tokens":5000}}}}}}"#));
    let compact = stdin(&mut t);
    assert_eq!(compact[0]["type"], "compact");
    let id = compact[0]["id"].as_str().unwrap();
    let evs = feed(&mut t, &format!(r#"{{"type":"response","command":"compact","id":"{id}","success":true,"data":{{"tokensBefore":5000,"estimatedTokensAfter":800}}}}"#));
    assert_eq!(evs.last(), Some(&HarnessEvent::TurnEnded { end: TurnEnd::Completed, reason: None }));
}

#[test]
fn launch_fails_closed_without_the_gate_and_lists_models() {
    let h = PiHarness::default();
    let mut req = LaunchRequest::new(PathBuf::from("."));
    req.approval = ApprovalMode::AllowEdits;
    assert!(h.launch(&req).is_err());
    req.staged.push((APPROVAL.into(), "/s/infantry-approval.ts".into()));
    req.resume = Some("abc".into());
    let plan = h.launch(&req).unwrap();
    assert_eq!(plan.spec.args, ["--mode", "rpc", "--session", "abc", "-e", "/s/infantry-approval.ts"]);
    assert!(plan.spec.env.contains(&("INFANTRY_TOOL_APPROVAL".into(), "ALLOW_EDITS".into())));

    let table = "warning: x\nprovider  model  context\nanthropic  claude-x  200K\ncommandcode  deepseek/v4  128K\n";
    let ids: Vec<String> = parse_list_models(table).into_iter().map(|m| m.id).collect();
    assert_eq!(ids, ["anthropic/claude-x", "commandcode/deepseek/v4"]);
}
