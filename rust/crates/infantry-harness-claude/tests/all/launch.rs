// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! What the harness asks the runner to start: the terminal type an interactive claude is told it draws in.
//!
//! The failure behind "new agent: thinking, then failed": a daemon the desktop app starts has no `TERM`, a claude that
//! inherited that drew ASCII glyphs (`>` for `❯`), and the harness could not read its folder-trust menu.

use crate::common::FakeProbe;
use infantry_harness::{Harness, LaunchPlan, LaunchRequest};
use infantry_harness_claude::{ClaudeHarness, TERM_ENV, TERM_VALUE};
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
