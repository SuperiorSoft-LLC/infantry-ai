// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! What the harness asks the runner to start: the terminal type an interactive claude is told it draws in, and the
//! permission wiring of each tool-approval mode.
//!
//! The failure behind "new agent: thinking, then failed": a daemon the desktop app starts has no `TERM`, a claude that
//! inherited that drew ASCII glyphs (`>` for `❯`), and the harness could not read its folder-trust menu.
//!
//! APPROVAL-ASK-1: with `toolApproval.mode=ask` a worker's Bash call ran with no card. The cause was not the wiring (the
//! tests below pin it) but claude itself: it runs its built-in read-only commands (`echo`, `ls`, `pwd`, `cat`, `git status`,
//! `node -v` ...) in its default permission mode (the one ask uses) without opening a permission dialog, so the
//! `PermissionRequest` hook is never called for them and no card exists. A command that needs a dialog (a write, a
//! redirect, `mkdir`, `node -e`) does reach the hook. To check ask mode live, use one of those, never `echo`.

use crate::common::FakeProbe;
use infantry_harness::{ApprovalMode, Harness, HookTarget, LaunchPlan, LaunchRequest};
use infantry_harness_claude::{ClaudeHarness, TERM_ENV, TERM_VALUE};
use serde_json::Value;
use std::path::PathBuf;
use std::sync::Arc;

fn harness() -> ClaudeHarness {
    ClaudeHarness::for_host(Arc::new(FakeProbe::with_home("/home/dev")))
}

fn request() -> LaunchRequest {
    LaunchRequest::new(PathBuf::from("/work"))
}

/// Every TERM value the process is started with.
fn term(plan: &LaunchPlan) -> Vec<&str> {
    plan.spec.env.iter().filter(|(k, _)| k.eq_ignore_ascii_case(TERM_ENV)).map(|(_, v)| v.as_str()).collect()
}

#[test]
fn an_interactive_claude_is_told_which_terminal_it_draws_in() {
    let plan = harness().launch(&request()).unwrap();
    assert!(plan.spec.pty.is_some(), "it runs in a pseudo-terminal");
    assert_eq!(term(&plan), vec![TERM_VALUE], "set by the harness, not left to whatever the daemon was started from");
}

#[test]
fn the_terminal_is_set_even_when_the_daemon_has_none_to_inherit() {
    // A process the desktop app started has no TERM; the harness's own value is what the child gets, and nothing removes it.
    let mut req = request();
    req.env_remove.push(TERM_ENV.into());
    let plan = harness().launch(&req).unwrap();
    assert_eq!(term(&plan), vec![TERM_VALUE]);
}

#[test]
fn a_terminal_the_request_names_is_kept() {
    let mut req = request();
    req.env.push(("term".into(), "screen-256color".into()));
    let plan = harness().launch(&req).unwrap();
    assert_eq!(term(&plan), vec!["screen-256color"], "one TERM, the caller's");
}

#[test]
fn a_headless_claude_has_no_terminal() {
    let mut req = request();
    req.prompt = Some("hi".into());
    let plan = harness().headless().launch(&req).unwrap();
    assert!(plan.spec.pty.is_none());
    assert!(term(&plan).is_empty());
}

const HOOK_URL: &str = "http://127.0.0.1:41999/hook/s1";

/// A request as the daemon's driver builds it for a session: its approval mode and the hook channel opened on the runner.
fn approval(mode: ApprovalMode) -> LaunchRequest {
    let mut req = request();
    req.prompt = Some("hi".into()); // a headless launch carries its turn
    req.approval = mode;
    req.hooks = Some(HookTarget { url: HOOK_URL.into(), events_file: "/work/events".into() });
    req
}

/// The value after `flag` in the process arguments.
fn after<'a>(plan: &'a LaunchPlan, flag: &str) -> Option<&'a str> {
    plan.spec.args.iter().position(|a| a == flag).and_then(|i| plan.spec.args.get(i + 1)).map(String::as_str)
}

/// The `--settings` JSON of a launch.
fn settings(plan: &LaunchPlan) -> Value {
    serde_json::from_str(after(plan, "--settings").expect("a --settings value")).expect("--settings is JSON")
}

/// The urls of the http hooks registered for `event` in the `--settings` JSON.
fn http_hooks(settings: &Value, event: &str) -> Vec<String> {
    settings["hooks"][event]
        .as_array()
        .into_iter()
        .flatten()
        .flat_map(|group| group["hooks"].as_array().into_iter().flatten())
        .filter(|hook| hook["type"] == "http")
        .filter_map(|hook| hook["url"].as_str().map(String::from))
        .collect()
}

#[test]
fn ask_starts_claude_with_its_dialogs_on_and_the_hook_that_turns_them_into_cards() {
    for (kind, h) in [("interactive", harness()), ("headless", harness().headless())] {
        let plan = h.launch(&approval(ApprovalMode::Ask)).unwrap();
        let s = settings(&plan);
        assert_eq!(after(&plan, "--permission-mode"), Some("default"), "{kind}: claude asks for what it does not allow itself");
        assert_eq!(http_hooks(&s, "PermissionRequest"), vec![HOOK_URL], "{kind}: a dialog reaches the host, which opens the card");
        assert_eq!(after(&plan, "--allowedTools"), None, "{kind}: nothing is allow-listed behind the owner's back");
        assert!(s.get("skipDangerousModePermissionPrompt").is_none(), "{kind}: no bypass");
        assert!(!plan.spec.args.iter().any(|a| a.contains("dangerously")), "{kind}: no skip-permissions flag");
    }
}

#[test]
fn every_mode_but_allow_all_keeps_the_permission_hook_and_allow_all_alone_bypasses_the_dialogs() {
    for (mode, permission_mode) in [
        (ApprovalMode::Deny, "dontAsk"),
        (ApprovalMode::Ask, "default"),
        (ApprovalMode::AllowEdits, "acceptEdits"),
        (ApprovalMode::AllowAll, "bypassPermissions"),
    ] {
        let plan = harness().launch(&approval(mode)).unwrap();
        let s = settings(&plan);
        let bypass = mode == ApprovalMode::AllowAll;
        assert_eq!(after(&plan, "--permission-mode"), Some(permission_mode), "{mode:?}");
        assert_eq!(http_hooks(&s, "PermissionRequest").is_empty(), bypass, "{mode:?}: only a bypass has no dialog to answer");
        assert_eq!(s.get("skipDangerousModePermissionPrompt").is_some(), bypass, "{mode:?}");
    }
}
