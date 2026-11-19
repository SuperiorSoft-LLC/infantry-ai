// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The interactive translator stepped by hand on a fake clock over the recorded screens and transcript records (the
//! cases of Kotlin's `ClaudeTtySessionTest.kt` and the old runner driver), plus the transcript mapper, the stream reader,
//! the screen readers, the hooks and the headless translator.

mod common;

use common::*;
use infantry_harness::{ApprovalMode, HarnessEvent, Outbound, Translator, TranslatorInput, TurnEnd, Usage};
use infantry_harness_claude::headless::Headless;
use infantry_harness_claude::interactive::{self as tty, Interactive, Timings};
use infantry_harness_claude::screen::{self, TrustMenu};
use infantry_harness_claude::transcript::{self, Signal, TranscriptMapper};
use infantry_harness_claude::{hooks, stream};
use serde_json::Value;

const T: Timings = Timings {
    boot_timeout_ms: 120_000,
    key_gap_ms: 400,
    trust_retry_ms: 1_500,
    paste_settle_ms: 400,
    submit_retry_ms: 4_000,
    stop_settle_ms: 600,
    stop_claim_ms: 2_000,
    abort_grace_ms: 3_000,
    clear_settle_ms: 400,
    ready_after_start_ms: 1_500,
    ready_fallback_ms: 8_000,
    dialog_grace_ms: 3_000,
    first_submit_ms: 30_000,
    hook_silent_ms: 20_000,
    screen_tail_chars: 600,
};

#[test]
fn the_test_clock_is_the_default_one() {
    assert_eq!(T, Timings::default());
}

struct Rig {
    t: Interactive,
    now: u64,
    rows: Vec<String>,
    written: Vec<String>,
    replies: Vec<(u64, Value)>,
    seen: Vec<HarnessEvent>,
}

impl Rig {
    fn new(approval: ApprovalMode) -> Rig {
        Rig { t: Interactive::new(ID, approval, T), now: 1_000, rows: Vec::new(), written: vec![], replies: vec![], seen: vec![] }
    }

    fn take(&mut self) {
        for o in self.t.take_outbound() {
            match o {
                Outbound::Stdin(b) => self.written.push(String::from_utf8(b).unwrap()),
                Outbound::HookReply { id, body } => self.replies.push((id, serde_json::from_str(&body).unwrap())),
                Outbound::Http { .. } => panic!("no http"),
            }
        }
    }

    fn feed(&mut self, i: TranslatorInput) {
        let ev = self.t.feed(i);
        self.seen.extend(ev);
        self.take();
    }

    fn tick(&mut self) {
        let rows = self.rows.clone();
        self.feed(TranslatorInput::Screen { rows, now: self.now });
    }

    /// The CLI draws [name] (output), seen at the next tick.
    fn screen(&mut self, name: &str) {
        self.rows = screen(name);
        self.feed(TranslatorInput::Pty(b"drawn".to_vec()));
        self.tick();
    }

    fn event(&mut self, word: &str) {
        self.feed(TranslatorInput::HookEvent(word.into()));
    }

    fn record(&mut self, line: &str) {
        self.feed(TranslatorInput::Tail { path: "t.jsonl".into(), line: line.into() });
    }

    fn send(&mut self, p: &str) {
        self.t.send_turn(p);
        self.take();
    }

    fn up(&mut self) {
        self.screen("welcome");
        self.event("start");
        self.tick();
        self.now += T.ready_after_start_ms;
    }

    /// Up, one turn pasted and entered.
    fn entered(&mut self, p: &str) {
        self.send(p);
        self.up();
        self.tick();
        self.now += T.paste_settle_ms;
        self.tick();
    }

    fn ends(&self) -> Vec<(TurnEnd, Option<String>)> {
        self.seen.iter().filter_map(|e| if let HarnessEvent::TurnEnded { end, reason } = e { Some((*end, reason.clone())) } else { None }).collect()
    }

    fn output_types(&self) -> Vec<String> {
        self.seen
            .iter()
            .filter_map(|e| if let HarnessEvent::Stdout(l) = e { Some(serde_json::from_str::<Value>(l).unwrap()["type"].as_str().unwrap().to_string()) } else { None })
            .collect()
    }

    fn last_result(&self) -> Value {
        self.seen
            .iter()
            .rev()
            .find_map(|e| match e {
                HarnessEvent::Stdout(l) => serde_json::from_str::<Value>(l).ok().filter(|v| v["type"] == "result"),
                _ => None,
            })
            .expect("a result line")
    }

    fn count(&self, f: impl Fn(&HarnessEvent) -> bool) -> usize {
        self.seen.iter().filter(|e| f(e)).count()
    }
}

fn paste(p: &str) -> String {
    format!("{}{p}{}", tty::PASTE_START, tty::PASTE_END)
}

// ---- turns --------------------------------------------------------------------------------------------------------------

#[test]
fn a_turn_waits_for_the_cli_is_pasted_entered_acked_and_ends_on_the_stop_hook_with_its_usage() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.send("read a.txt");
    r.tick();
    assert!(r.written.is_empty(), "nothing is typed before the CLI is up");
    assert!(r.seen.contains(&HarnessEvent::ProviderSession(ID.into())), "the session id is known at once");
    r.up();
    r.tick();
    assert_eq!(r.written, vec![paste("read a.txt")], "bracketed paste");
    r.now += T.paste_settle_ms;
    r.tick();
    assert_eq!(r.written.last().unwrap(), tty::ENTER, "then Enter");
    assert_eq!(r.count(|e| *e == HarnessEvent::TurnStarted), 0, "not acked before the CLI took it");
    r.event("submit");
    assert_eq!(r.count(|e| *e == HarnessEvent::TurnStarted), 1, "the submit hook acks the turn");
    for l in TURN.lines().filter(|l| !l.contains("turn_duration")) {
        r.record(l);
    }
    r.tick();
    assert!(r.ends().is_empty(), "still running");
    r.event("stop");
    r.tick();
    assert!(r.ends().is_empty(), "the stop settles first");
    r.now += T.stop_settle_ms;
    r.tick();
    assert_eq!(r.ends(), vec![(TurnEnd::Completed, None)]);
    assert_eq!(r.output_types(), vec!["system", "assistant", "user", "assistant", "assistant", "result"]);
    assert_eq!(r.count(|e| *e == HarnessEvent::TurnStarted), 1, "the recorded prompt does not ack twice");
    assert_eq!(r.written.len(), 2, "no Enter retry once the prompt was taken");
    assert!(r.seen.contains(&HarnessEvent::ToolStarted { id: "toolu_1".into(), name: "Read".into(), input: r#"{"file_path":"a.txt"}"#.into() }));
    assert!(r.seen.contains(&HarnessEvent::ToolFinished { id: "toolu_1".into(), output: "hello".into(), exit_code: None, ok: true }));
    assert!(r.seen.contains(&HarnessEvent::Reasoning("the file says hello".into())));
    assert!(r.seen.contains(&HarnessEvent::Text("It says hello.".into())));
    assert!(!r.seen.contains(&HarnessEvent::Text("a subagent's words".into())), "a sidechain is not the session's");
    // m0 (2 + 10 + 0 in, 7 out) and the LAST usage of m1 (3 + 100 + 50 in, 9 out).
    let usage = Usage { input_tokens: 165, cached_input_tokens: 110, output_tokens: 16, reasoning_tokens: 0 };
    assert!(r.seen.contains(&HarnessEvent::Usage(usage)), "{:?}", r.seen);
    let res = r.last_result();
    assert_eq!(res["result"], "It says hello.");
    assert_eq!(res["cost_estimated"], true);
}

#[test]
fn with_hooks_that_write_nothing_the_transcripts_turn_duration_ends_the_turn() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.send("read a.txt");
    r.screen("welcome");
    r.now += T.ready_fallback_ms;
    r.tick();
    assert_eq!(r.written.len(), 1, "pasted on the fallback readiness");
    r.now += T.paste_settle_ms;
    r.tick();
    for l in TURN.lines() {
        r.record(l);
    }
    assert_eq!(r.count(|e| *e == HarnessEvent::TurnStarted), 1, "the recorded prompt acks");
    r.tick();
    r.now += T.stop_settle_ms;
    r.tick();
    assert_eq!(r.ends(), vec![(TurnEnd::Completed, None)]);
}

#[test]
fn an_enter_the_paste_swallowed_is_pressed_once_more() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.entered("x");
    r.now += T.submit_retry_ms;
    r.tick();
    assert_eq!(r.written, vec![paste("x"), "\r".into(), "\r".into()]);
    r.now += T.submit_retry_ms;
    r.tick();
    assert_eq!(r.written.len(), 3, "only once");
}

#[test]
fn turns_queue_behind_the_running_one() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.entered("one");
    r.send("two");
    r.event("submit");
    r.tick();
    assert_eq!(r.written.len(), 2, "the second waits");
    r.record(&assistant("m1", "done", 1));
    r.event("stop");
    r.now += T.stop_settle_ms;
    r.tick();
    r.tick();
    assert_eq!(r.ends().len(), 1);
    assert_eq!(r.written.last().unwrap(), &paste("two"));
}

#[test]
fn an_api_error_the_cli_records_fails_the_turn_with_its_words() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.entered("x");
    r.record(r#"{"type":"assistant","isApiErrorMessage":true,"message":{"id":"e","model":"<synthetic>","content":[{"type":"text","text":"API Error: 401"}]}}"#);
    r.tick();
    assert_eq!(r.ends(), vec![(TurnEnd::Failed, Some("API Error: 401".into()))]);
    let res = r.last_result();
    assert_eq!(res["is_error"], true);
    assert_eq!(res["result"], "API Error: 401");
}

// ---- interrupt ------------------------------------------------------------------------------------------------------

#[test]
fn interrupt_is_esc_and_a_turn_stopped_before_its_reply_clears_the_input_box() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.entered("long job");
    r.event("submit");
    r.tick();
    assert!(r.t.interrupt());
    r.take();
    assert_eq!(r.written.last().unwrap(), tty::ESC);
    r.record(INTERRUPTED);
    r.tick();
    assert_eq!(r.ends(), vec![(TurnEnd::Interrupted, None)]);
    assert_eq!(r.written.last().unwrap(), tty::CTRL_C, "Ctrl+C empties the box the CLI put the prompt back into");
    assert_eq!(r.output_types().last().unwrap(), "result", "an interrupted turn still reports its usage");
    r.send("next");
    r.tick();
    assert_eq!(r.written.last().unwrap(), tty::CTRL_C, "the next paste waits for the box to clear");
    r.now += T.clear_settle_ms;
    r.tick();
    assert_eq!(r.written.last().unwrap(), &paste("next"));
}

#[test]
fn interrupt_ends_after_the_grace_when_nothing_is_recorded_and_after_a_reply_sends_no_ctrl_c() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.entered("x");
    r.record(PROMPT);
    r.record(&assistant("m1", "partial", 1));
    r.tick();
    r.t.interrupt();
    r.take();
    r.now += T.abort_grace_ms;
    r.tick();
    assert_eq!(r.ends(), vec![(TurnEnd::Interrupted, None)]);
    assert!(!r.written.contains(&tty::CTRL_C.to_string()));
}

#[test]
fn interrupting_a_turn_not_pasted_yet_drops_it() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.send("x");
    assert!(r.t.interrupt());
    r.tick();
    assert_eq!(r.ends(), vec![(TurnEnd::Interrupted, None)]);
    r.up();
    r.tick();
    assert!(r.written.is_empty(), "it never runs");
}

// ---- start: trust, setup, boot ---------------------------------------------------------------------------------------

#[test]
fn trust_menu_reads_the_row_with_the_pointer() {
    assert_eq!(screen::trust_menu(&screen("welcome")), TrustMenu::Absent);
    assert_eq!(screen::trust_menu(&screen("trust_on_no")), TrustMenu::OnNo);
    assert_eq!(screen::trust_menu(&screen("trust_on_yes")), TrustMenu::OnYes);
    assert_eq!(screen::trust_menu(&screen("trust_no_pointer")), TrustMenu::NoPointer);
}

#[test]
fn the_folder_trust_menu_is_answered_from_the_grid_down_then_enter_before_any_paste() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.send("x");
    r.screen("trust_on_no");
    assert!(r.written.is_empty(), "no key before the screen is quiet");
    r.now += T.key_gap_ms;
    r.tick();
    assert_eq!(r.written, vec![tty::DOWN]);
    r.now += T.key_gap_ms;
    r.tick();
    assert_eq!(r.written.len(), 1, "no second Down before the retry interval");
    r.screen("trust_on_yes");
    assert_eq!(r.written.len(), 1, "the redraw is not quiet yet");
    r.now += T.key_gap_ms;
    r.tick();
    assert_eq!(r.written, vec![tty::DOWN, tty::ENTER]);
    r.event("start");
    r.screen("welcome");
    r.now += T.ready_after_start_ms;
    r.tick();
    assert_eq!(r.written.last().unwrap(), &paste("x"));
}

#[test]
fn a_setup_screen_fails_the_waiting_turn_as_not_signed_in() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.send("x");
    r.screen("setup_theme");
    let ends = r.ends();
    assert_eq!(ends.len(), 1);
    assert_eq!(ends[0].0, TurnEnd::Failed);
    assert!(ends[0].1.as_deref().unwrap().starts_with(tty::NOT_SIGNED_IN), "{ends:?}");
    assert!(r.written.is_empty(), "nothing is typed into a setup screen");
}

#[test]
fn a_cli_that_never_comes_up_fails_its_queued_turns_with_the_screen() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.send("x");
    r.rows = vec!["Loading...".into()];
    r.now = T.boot_timeout_ms + 1;
    r.tick();
    let ends = r.ends();
    assert_eq!(ends[0].0, TurnEnd::Failed);
    assert!(ends[0].1.as_deref().unwrap().contains("Loading..."), "{ends:?}");
}

#[test]
fn a_first_paste_the_booting_cli_lost_is_entered_again_once_then_fails() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    let p = "read a.txt and tell me what it says";
    r.entered(p);
    let pasted_at = r.now - T.paste_settle_ms;
    r.rows = screen("prompt_in_box");
    r.now = pasted_at + T.first_submit_ms;
    r.tick();
    assert_eq!(r.written.last().unwrap(), tty::ENTER, "the prompt sits in the box: Enter again");
    assert!(r.seen.iter().any(|e| matches!(e, HarnessEvent::Warning(w) if w.contains("Enter pressed again"))));
    r.now += T.first_submit_ms;
    r.tick();
    let ends = r.ends();
    assert_eq!(ends[0].0, TurnEnd::Failed);
    assert!(ends[0].1.as_deref().unwrap().contains("after one recovery"), "{ends:?}");
}

#[test]
fn a_first_paste_that_never_reached_the_box_is_pasted_again() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.entered("hello there");
    r.now += T.first_submit_ms;
    r.tick();
    assert_eq!(r.written.last().unwrap(), &paste("hello there"));
    r.event("submit");
    r.now += T.first_submit_ms;
    r.tick();
    assert!(r.ends().is_empty(), "taken after the recovery");
}

// ---- dialogs and hooks ------------------------------------------------------------------------------------------------

#[test]
fn a_permission_dialog_nothing_answers_is_answered_no_and_ends_the_turn() {
    let mut r = Rig::new(ApprovalMode::Ask);
    r.entered("x");
    r.screen("permission_dialog");
    r.now += T.dialog_grace_ms;
    r.tick();
    assert_eq!(r.written.last().unwrap(), tty::ESC);
    r.record(INTERRUPTED);
    r.tick();
    assert_eq!(r.ends(), vec![(TurnEnd::Interrupted, Some(tty::DIALOG_DENIED_REASON.into()))]);
}

#[test]
fn an_ask_user_question_menu_no_hook_answered_is_cancelled() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.entered("x");
    r.screen("ask_menu");
    r.now += T.dialog_grace_ms;
    r.tick();
    assert_eq!(r.written.last().unwrap(), tty::ESC);
    r.now += T.abort_grace_ms;
    r.tick();
    assert_eq!(r.ends(), vec![(TurnEnd::Interrupted, Some(tty::ASK_MENU_REASON.into()))]);
}

#[test]
fn a_dialog_with_its_hook_call_open_waits_for_the_host() {
    let mut r = Rig::new(ApprovalMode::Ask);
    r.entered("x");
    let call = r#"{"hook_event_name":"PermissionRequest","tool_name":"Bash","tool_input":{"command":"ls"}}"#;
    r.feed(TranslatorInput::Hook { id: 7, body: call.into() });
    assert!(r.seen.contains(&HarnessEvent::ApprovalRequested { id: "hook-7".into(), tool: "Bash".into(), input: r#"{"command":"ls"}"#.into() }));
    r.screen("permission_dialog");
    r.now += T.dialog_grace_ms * 2;
    r.tick();
    assert_ne!(r.written.last().unwrap(), tty::ESC, "the host is deciding");
    r.t.answer("hook-7", true);
    r.take();
    assert_eq!(r.replies.len(), 1);
    assert_eq!(r.replies[0].0, 7);
    assert_eq!(r.replies[0].1["hookSpecificOutput"]["hookEventName"], "PermissionRequest");
    assert_eq!(r.replies[0].1["hookSpecificOutput"]["decision"]["behavior"], "allow");
}

#[test]
fn the_permission_hook_asks_only_under_ask_and_the_question_hook_asks_in_plain_text() {
    let call = r#"{"hook_event_name":"PermissionRequest","tool_name":"Bash","tool_input":{"command":"rm -rf x"}}"#;
    let mut r = Rig::new(ApprovalMode::AllowEdits);
    r.feed(TranslatorInput::Hook { id: 1, body: call.into() });
    assert_eq!(r.replies[0].1["hookSpecificOutput"]["decision"]["behavior"], "deny");
    assert!(r.replies[0].1["hookSpecificOutput"]["decision"]["message"].as_str().unwrap().contains("Bash"));
    assert_eq!(r.count(|e| matches!(e, HarnessEvent::ApprovalRequested { .. })), 0);
    let ask = r#"{"hook_event_name":"PreToolUse","tool_name":"AskUserQuestion","tool_input":{"questions":[]}}"#;
    r.feed(TranslatorInput::Hook { id: 2, body: ask.into() });
    let out = &r.replies[1].1["hookSpecificOutput"];
    assert_eq!(out["hookEventName"], "PreToolUse");
    assert_eq!(out["permissionDecision"], "deny");
    assert_eq!(out["permissionDecisionReason"], hooks::QUESTION_DENIED);
    r.feed(TranslatorInput::Hook { id: 3, body: "not json".into() });
    assert_eq!(r.replies[2], (3, serde_json::json!({})), "anything else gets no decision");
    let mut ask_mode = Rig::new(ApprovalMode::Ask);
    ask_mode.feed(TranslatorInput::Hook { id: 4, body: call.into() });
    ask_mode.t.answer("hook-4", false);
    ask_mode.take();
    assert_eq!(ask_mode.replies[0].1["hookSpecificOutput"]["decision"]["behavior"], "deny");
}

#[test]
fn ask_user_question_goes_to_the_owner_and_the_answer_fills_the_tool_input() {
    let ask = r#"{"hook_event_name":"PreToolUse","tool_name":"AskUserQuestion","tool_input":{"questions":[{"question":"Which db?","header":"DB","multiSelect":false,"options":[{"label":"Postgres (Recommended)","description":"robust"},{"label":"SQLite"}]}]}}"#;
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.feed(TranslatorInput::Hook { id: 9, body: ask.into() });
    assert!(r.replies.is_empty(), "the hook call waits for the owner");
    let q = r
        .seen
        .iter()
        .find_map(|e| match e {
            HarnessEvent::QuestionAsked { id, questions } => Some((id.clone(), questions.clone())),
            _ => None,
        })
        .expect("a question event");
    assert_eq!(q.1.len(), 1);
    assert!(q.1[0].text.starts_with("[DB] Which db?") && q.1[0].text.contains("- Postgres (Recommended): robust"));
    assert_eq!(q.1[0].recommended.as_deref(), Some("Postgres (Recommended)"));
    assert!(q.1[0].allow_other);
    r.t.answer_questions(&q.0, vec![infantry_harness::HarnessAnswer { answered: true, answers: vec!["SQLite".into()], other_text: Some("for now".into()) }]);
    r.take();
    let out = &r.replies[0].1["hookSpecificOutput"];
    assert_eq!(r.replies[0].0, 9);
    assert_eq!(out["permissionDecision"], "allow");
    assert_eq!(out["updatedInput"]["answers"]["Which db?"], "SQLite, for now");

    // No answer (timeout): denied with the carry-on reason.
    r.feed(TranslatorInput::Hook { id: 10, body: ask.into() });
    r.t.answer_questions("hook-10", vec![infantry_harness::HarnessAnswer::default()]);
    r.take();
    assert_eq!(r.replies[1].1["hookSpecificOutput"]["permissionDecisionReason"], hooks::QUESTION_NO_ANSWER);
}

// ---- own turns, inject, close -----------------------------------------------------------------------------------------

#[test]
fn a_turn_the_cli_begins_by_itself_is_tracked_and_nothing_is_pasted_into_it() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.up();
    r.tick();
    r.record(r#"{"type":"user","origin":{"kind":"task-notification"},"message":{"role":"user","content":"<task-notification>b1</task-notification>"}}"#);
    r.tick();
    assert!(r.seen.iter().any(|e| matches!(e, HarnessEvent::Warning(w) if w.contains("a background task ended"))));
    r.send("queued");
    r.tick();
    assert!(r.written.is_empty(), "nothing pasted into the CLI's own turn");
    r.record(&assistant("m1", "noted", 1));
    r.event("stop");
    r.tick();
    r.now += T.stop_settle_ms;
    r.tick();
    assert!(r.ends().is_empty(), "the CLI's own turn is not the host's");
    assert_eq!(r.count(|e| *e == HarnessEvent::TurnStarted), 0);
    r.tick();
    assert_eq!(r.written, vec![paste("queued")]);
}

#[test]
fn a_stop_that_comes_before_its_own_prompt_record_is_claimed() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.up();
    r.tick();
    r.event("submit");
    r.event("stop");
    r.tick();
    r.now += T.stop_settle_ms;
    r.tick();
    assert!(r.seen.iter().any(|e| matches!(e, HarnessEvent::Warning(w) if w.contains("its own turn ended"))));
    r.send("next");
    r.tick();
    assert_eq!(r.written, vec![paste("next")]);
}

#[test]
fn text_typed_into_a_running_turn_is_pasted_and_entered() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.send("x");
    assert!(!r.t.inject("too early"), "no turn running");
    r.up();
    r.tick();
    assert!(!r.t.inject("not entered yet"));
    r.now += T.paste_settle_ms;
    r.tick();
    r.event("submit");
    assert!(r.t.inject("also do y"));
    r.take();
    assert_eq!(r.written.last().unwrap(), &paste("also do y"));
    assert!(!r.t.inject("one at a time"));
    r.now += T.paste_settle_ms;
    r.tick();
    assert_eq!(r.written.last().unwrap(), tty::ENTER);
    assert!(r.t.inject("then z"), "the next one may go");
}

#[test]
fn close_is_esc_then_exit_and_an_exit_fails_open_turns() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.up();
    assert!(r.t.close());
    r.take();
    assert_eq!(r.written, vec![tty::ESC]);
    r.now += T.key_gap_ms;
    r.tick();
    r.now += T.key_gap_ms;
    r.tick();
    assert_eq!(r.written, vec![tty::ESC.to_string(), paste("/exit"), tty::ENTER.to_string()]);
    r.send("late");
    r.feed(TranslatorInput::Exited { code: 0 });
    assert_eq!(r.ends().len(), 1);
    assert_eq!(r.ends()[0].0, TurnEnd::Failed);
    assert!(r.seen.contains(&HarnessEvent::Exited { code: Some(0) }));
    r.send("after");
    r.tick();
    assert_eq!(r.ends().len(), 2, "a turn sent after the exit fails at once");
    assert!(!r.t.close(), "nothing left to close");
}

#[test]
fn hooks_that_write_nothing_are_said_once() {
    let mut r = Rig::new(ApprovalMode::AllowAll);
    r.send("x");
    r.screen("welcome");
    r.now += T.ready_fallback_ms;
    r.tick();
    r.record(PROMPT);
    r.record(&assistant("m1", "working", 1));
    r.now += T.hook_silent_ms;
    r.tick();
    r.now += T.hook_silent_ms;
    r.tick();
    assert_eq!(r.count(|e| matches!(e, HarnessEvent::Warning(w) if w.contains("hooks wrote no event"))), 1);
}

// ---- transcript mapper ------------------------------------------------------------------------------------------------

fn line(s: &Signal) -> &str {
    match s {
        Signal::Line(l) => l,
        other => panic!("not a line: {other:?}"),
    }
}

#[test]
fn transcript_records_become_stream_json_records() {
    let mut m = TranscriptMapper::new("sid");
    let lines: Vec<&str> = TURN.lines().collect();
    assert_eq!(m.map(lines[0]), vec![Signal::PromptRecorded]);
    let a = m.map(lines[1]);
    let r = m.map(lines[2]);
    assert!(m.map(lines[3]).is_empty(), "a snapshot is not output");
    assert!(m.map(lines[4]).is_empty(), "a subagent's record is not the session's");
    assert!(m.map("not json").is_empty());
    let v: Value = serde_json::from_str(line(&a[0])).unwrap();
    assert_eq!(v["type"], "assistant");
    assert_eq!(v["session_id"], "sid");
    assert_eq!(v["uuid"], "u-t");
    assert!(v["parent_tool_use_id"].is_null());
    assert_eq!(stream::events(&v), vec![HarnessEvent::ToolStarted { id: "toolu_1".into(), name: "Read".into(), input: r#"{"file_path":"a.txt"}"#.into() }]);
    let v: Value = serde_json::from_str(line(&r[0])).unwrap();
    assert_eq!(stream::events(&v), vec![HarnessEvent::ToolFinished { id: "toolu_1".into(), output: "hello".into(), exit_code: None, ok: true }]);
    assert_eq!(stream::events(&m.init()), vec![HarnessEvent::ProviderSession("sid".into())]);
    assert_eq!(m.map(lines[7]), vec![Signal::TurnEnded]);
}

#[test]
fn the_result_sums_the_last_usage_of_each_message_and_resets_the_turn() {
    let mut m = TranscriptMapper::new("sid");
    for l in TURN.lines() {
        m.map(l);
    }
    let r = m.result(None);
    assert_eq!(r["subtype"], "success");
    assert_eq!(r["result"], "It says hello.");
    assert_eq!(r["usage"]["output_tokens"], 16);
    assert_eq!(r["usage"]["cache_read_input_tokens"], 110);
    assert_eq!(r["usage"]["input_tokens"], 5);
    assert_eq!(r["usage"]["cache_creation_input_tokens"], 50);
    // haiku: (5*1 + 16*5 + 110*0.10 + 50*1.25) / 1e6
    let cost = r["total_cost_usd"].as_f64().unwrap();
    assert!((cost - 158.5e-6).abs() < 1e-12, "{cost}");
    assert!(m.result(None).get("usage").is_none(), "the next turn starts from zero");
    let e = m.result(Some("boom"));
    assert_eq!((e["is_error"].as_bool(), e["result"].as_str()), (Some(true), Some("boom")));
}

#[test]
fn interrupts_synthetic_messages_api_errors_own_prompts_and_compaction_are_read() {
    let mut m = TranscriptMapper::new("sid");
    assert_eq!(m.map(INTERRUPTED), vec![Signal::Interrupted]);
    assert!(m.map(r#"{"type":"assistant","message":{"id":"s","model":"<synthetic>","content":[{"type":"text","text":"No response requested."}]}}"#).is_empty());
    assert_eq!(
        m.map(r#"{"type":"assistant","isApiErrorMessage":true,"message":{"id":"e","model":"<synthetic>","content":[{"type":"text","text":"API Error: 529 overloaded"}]}}"#),
        vec![Signal::ApiError("API Error: 529 overloaded".into())]
    );
    assert!(m.map(r#"{"type":"user","isMeta":true,"message":{"role":"user","content":"<command-name>/model</command-name>"}}"#).is_empty());
    let notice = r#"{"type":"user","origin":{"kind":"task-notification"},"message":{"role":"user","content":"<task-notification>done</task-notification>"}}"#;
    assert_eq!(m.map(notice), vec![Signal::OwnPrompt("task-notification".into())]);
    let pasted = r#"{"type":"user","origin":{"kind":"human"},"message":{"role":"user","content":"NEW MAIL (1)"}}"#;
    assert_eq!(m.map(pasted), vec![Signal::PromptRecorded]);
    let compact = r#"{"type":"system","subtype":"compact_boundary","compactMetadata":{"trigger":"auto","preTokens":1234}}"#;
    let v: Value = serde_json::from_str(line(&m.map(compact)[0])).unwrap();
    assert_eq!(v["compact_metadata"]["trigger"], "auto");
    assert_eq!(v["compact_metadata"]["pre_tokens"], 1234);
}

#[test]
fn the_transcript_folder_is_the_cwd_slug() {
    assert_eq!(transcript::slug("C:\\Users\\a\\x y.z"), "C--Users-a-x-y-z");
    assert_eq!(transcript::slug("/home/a/b_c"), "-home-a-b-c");
}

// ---- stream-json (the Kotlin golden inputs) and headless ---------------------------------------------------------------

fn golden_events(text: &str) -> Vec<HarnessEvent> {
    text.lines().filter(|l| !l.trim().is_empty()).flat_map(|l| stream::events(&serde_json::from_str(l).unwrap())).collect()
}

#[test]
fn the_claude_stream_fixture_reads_as_tool_calls_results_and_text() {
    let events = golden_events(CLAUDE_RUN);
    let calls: Vec<&str> = events.iter().filter_map(|e| if let HarnessEvent::ToolStarted { name, .. } = e { Some(name.as_str()) } else { None }).collect();
    assert_eq!(calls, vec!["TodoWrite", "Bash", "Bash", "Grep", "Bash", "Bash"]);
    let results: Vec<(&str, bool)> =
        events.iter().filter_map(|e| if let HarnessEvent::ToolFinished { id, ok, .. } = e { Some((id.as_str(), *ok)) } else { None }).collect();
    assert_eq!(results.len(), 6);
    assert!(results.contains(&("toolu_01HHWAKbZhDFRec3JuQoa8W2", true)), "the Grep result is not an error");
    assert_eq!(results.iter().filter(|(_, ok)| !ok).count(), 5, "{results:?}");
    let HarnessEvent::Text(t) = events.last().unwrap() else { panic!("ends with text: {:?}", events.last()) };
    assert!(t.starts_with("DONE"));
}

#[test]
fn the_task_tools_fixture_reads_its_three_calls() {
    let events = golden_events(CLAUDE_TASKS);
    let calls: Vec<(String, Value)> = events
        .iter()
        .filter_map(|e| if let HarnessEvent::ToolStarted { name, input, .. } = e { Some((name.clone(), serde_json::from_str(input).unwrap())) } else { None })
        .collect();
    assert_eq!(calls.iter().map(|c| c.0.as_str()).collect::<Vec<_>>(), vec!["TaskCreate", "TaskUpdate", "TaskUpdate"]);
    assert_eq!(calls[0].1["subject"], "Read README");
    assert_eq!(calls[2].1["status"], "deleted");
    assert!(events.contains(&HarnessEvent::ToolFinished { id: "toolu_02".into(), output: "Updated task #1 status".into(), exit_code: None, ok: true }));
}

#[test]
fn headless_reads_the_stream_and_ends_on_its_result() {
    let mut h = Headless::new(ApprovalMode::AllowAll);
    let mut seen = Vec::new();
    seen.extend(h.feed(TranslatorInput::Stdout(r#"{"type":"system","subtype":"init","session_id":"s-1"}"#.into())));
    for l in CLAUDE_RUN.lines().filter(|l| !l.trim().is_empty()) {
        seen.extend(h.feed(TranslatorInput::Stdout(l.into())));
    }
    let result = r#"{"type":"result","subtype":"success","is_error":false,"result":"DONE","usage":{"input_tokens":4,"output_tokens":20,"cache_read_input_tokens":30,"cache_creation_input_tokens":6}}"#;
    seen.extend(h.feed(TranslatorInput::Stdout(result.into())));
    seen.extend(h.feed(TranslatorInput::Exited { code: 0 }));
    assert!(seen.contains(&HarnessEvent::ProviderSession("s-1".into())));
    assert_eq!(seen.iter().filter(|e| **e == HarnessEvent::TurnStarted).count(), 1);
    assert!(seen.contains(&HarnessEvent::Usage(Usage { input_tokens: 40, cached_input_tokens: 30, output_tokens: 20, reasoning_tokens: 0 })));
    let ends: Vec<_> = seen.iter().filter(|e| matches!(e, HarnessEvent::TurnEnded { .. })).collect();
    assert_eq!(ends, vec![&HarnessEvent::TurnEnded { end: TurnEnd::Completed, reason: None }]);
    assert_eq!(seen.last().unwrap(), &HarnessEvent::Exited { code: Some(0) });
}

#[test]
fn headless_fails_an_error_result_and_an_exit_without_one() {
    let mut h = Headless::new(ApprovalMode::AllowAll);
    let ev = h.feed(TranslatorInput::Stdout(r#"{"type":"result","subtype":"error_max_turns","is_error":true,"result":""}"#.into()));
    assert!(ev.contains(&HarnessEvent::TurnEnded { end: TurnEnd::Failed, reason: Some("error_max_turns".into()) }));
    let mut h = Headless::new(ApprovalMode::AllowAll);
    h.feed(TranslatorInput::Stderr("Invalid API key".into()));
    let ev = h.feed(TranslatorInput::Exited { code: 1 });
    let HarnessEvent::TurnEnded { end: TurnEnd::Failed, reason: Some(r) } = &ev[0] else { panic!("{ev:?}") };
    assert!(r.contains("Invalid API key"), "{r}");
}
