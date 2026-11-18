// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! [`Interactive`]: the translator of one interactive `claude` in its terminal. It is stepped by the driver's screen
//! snapshots ([`TranslatorInput::Screen`], whose `now` is its clock) and fed the hook events, the hook calls and the
//! transcript lines. WHAT it does is Kotlin's `ClaudeTtySession.TtyConnection`:
//!
//! - **Start:** the folder trust menu is answered from the grid (Down while "No, exit" is selected, Enter only on the
//!   accept row once the screen is quiet). The CLI is up a moment after its `SessionStart` hook, or a while after its
//!   first screen. FIRST-RUN-F1: a setup screen (theme, sign in) fails the waiting turn with the screen.
//! - **Input:** each turn is pasted (bracketed paste) and submitted with Enter; a swallowed Enter is pressed once more.
//!   FIRST-TURN-1: a first paste the booting CLI lost is pressed again or pasted again, once. CHAT-QUEUE-1: text typed into
//!   a running turn ([`Translator::inject`]) is pasted and entered; claude takes it at its next step.
//! - **Ack:** [`HarnessEvent::TurnStarted`] when the CLI took the prompt (its `UserPromptSubmit` hook or the recorded prompt).
//! - **Output:** the transcript records as stream-json ([`HarnessEvent::Stdout`]) and as neutral events.
//! - **Turn end:** the `Stop` hook or the transcript's `turn_duration`, then the turn's `result` (usage, cost estimate);
//!   an API error fails the turn.
//! - **Own turns (CLI-PTY-4):** a turn the CLI starts itself (a background task ended) is tracked; nothing is pasted into it.
//! - **Interrupt:** Esc; a turn stopped before its first reply also gets Ctrl+C, so its prompt does not join the next paste.
//! - **Dialogs (G3):** a permission dialog or AskUserQuestion menu drawn with no hook call open is answered No (Esc) after
//!   a grace, so a turn never waits on keys nobody sends.
//! - **Close:** Esc, `/exit`, Enter.

use crate::hooks::{Answers, EV_START, EV_STOP, EV_SUBMIT};
use crate::screen::{self, TrustMenu};
use crate::stream;
use crate::transcript::{Signal, TranscriptMapper};
use infantry_harness::{ApprovalMode, HarnessEvent, Outbound, Translator, TranslatorInput, TurnEnd};
use serde_json::Value;
use std::collections::VecDeque;

pub const ESC: &str = "\u{1b}";
pub const ENTER: &str = "\r";
pub const CTRL_C: &str = "\u{3}";
pub const DOWN: &str = "\u{1b}[B";
pub const PASTE_START: &str = "\u{1b}[200~";
pub const PASTE_END: &str = "\u{1b}[201~";

pub const NOT_SIGNED_IN: &str =
    "Claude Code is not signed in. Run `claude` once in a terminal and sign in, then restart this agent.";
pub const DIALOG_DENIED_REASON: &str = "claude showed a permission dialog that no approval answered; it was answered No";
pub const ASK_MENU_REASON: &str = "claude drew its AskUserQuestion menu with no hook call open; it was cancelled";
/// FIRST-TURN-1: the prompt's first characters (squeezed) that show it sits in the input box.
pub const PROMPT_HEAD_CHARS: usize = 24;

/// Every wait of the interactive translator, in milliseconds. The defaults are Kotlin's.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Timings {
    /// How long the CLI may take to come up before its waiting turn fails.
    pub boot_timeout_ms: u64,
    /// The quiet a key waits for (a menu still drawing takes no keys).
    pub key_gap_ms: u64,
    pub trust_retry_ms: u64,
    /// From a paste to its Enter.
    pub paste_settle_ms: u64,
    /// From an Enter the CLI did not take to the one retry.
    pub submit_retry_ms: u64,
    /// From the turn's stop to its result (the last records are flushed after the hook; longer than a tail poll).
    pub stop_settle_ms: u64,
    /// How long a stop with no turn open may still be claimed by a turn the CLI started itself.
    pub stop_claim_ms: u64,
    /// From Esc to calling the turn interrupted when the CLI recorded nothing.
    pub abort_grace_ms: u64,
    /// After Ctrl+C cleared the input box, before the next paste.
    pub clear_settle_ms: u64,
    pub ready_after_start_ms: u64,
    /// Up without a `SessionStart` hook: this long after the first screen.
    pub ready_fallback_ms: u64,
    pub dialog_grace_ms: u64,
    /// FIRST-TURN-1: from the first paste to the CLI taking it before one recovery (0 = not watched).
    pub first_submit_ms: u64,
    /// HOOK-SHELL-1: an answered turn with no hook event this long means the hooks write nothing (said once).
    pub hook_silent_ms: u64,
    /// Characters of screen in a failure reason.
    pub screen_tail_chars: usize,
}

impl Default for Timings {
    fn default() -> Self {
        Timings {
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
        }
    }
}

#[derive(Default)]
struct Turn {
    prompt: String,
    /// The CLI started it (nothing of the host's).
    own: bool,
    abort_at: Option<u64>,
    abort_reason: Option<String>,
    pasted_at: Option<u64>,
    entered_at: Option<u64>,
    enter_retried: bool,
    submitted: bool,
    stop_at: Option<u64>,
    replied_at: Option<u64>,
    /// FIRST-TURN-1: the process's first pasted turn, held to the submit deadline.
    watched: bool,
    recovery: Option<&'static str>,
    recovered_at: Option<u64>,
}

pub struct Interactive {
    t: Timings,
    session_id: String,
    mapper: TranscriptMapper,
    answers: Answers,
    out: Vec<Outbound>,
    /// Events produced outside a feed (an interrupt of a queued turn), reported with the next one.
    later: Vec<HarnessEvent>,
    now: u64,
    rows: Vec<String>,
    output_seen: bool,
    first_output_at: Option<u64>,
    last_output_at: u64,
    started_at: Option<u64>,
    hook_seen: bool,
    hook_warned: bool,
    trust_seen: bool,
    trust_down_at: Option<u64>,
    trust_done: bool,
    interrupted_seen: bool,
    input_cleared_at: Option<u64>,
    api_error: Option<String>,
    queue: VecDeque<String>,
    active: Option<Turn>,
    own_pending: Option<String>,
    unclaimed_stop_at: Option<u64>,
    dialog_seen_at: Option<u64>,
    menu_seen_at: Option<u64>,
    injects: VecDeque<u64>,
    /// (step, at): 0 = Esc sent, 1 = `/exit` pasted, 2 = Enter sent.
    closing: Option<(u8, u64)>,
    first_pasted: bool,
    init_sent: bool,
    exited: bool,
}

impl Interactive {
    pub fn new(session_id: &str, approval: ApprovalMode, t: Timings) -> Self {
        Interactive {
            t,
            session_id: session_id.into(),
            mapper: TranscriptMapper::new(session_id),
            answers: Answers::new(approval),
            out: Vec::new(),
            later: Vec::new(),
            now: 0,
            rows: Vec::new(),
            output_seen: false,
            first_output_at: None,
            last_output_at: 0,
            started_at: None,
            hook_seen: false,
            hook_warned: false,
            trust_seen: false,
            trust_down_at: None,
            trust_done: false,
            interrupted_seen: false,
            input_cleared_at: None,
            api_error: None,
            queue: VecDeque::new(),
            active: None,
            own_pending: None,
            unclaimed_stop_at: None,
            dialog_seen_at: None,
            menu_seen_at: None,
            injects: VecDeque::new(),
            closing: None,
            first_pasted: false,
            init_sent: false,
            exited: false,
        }
    }

    fn key(&mut self, k: &str) {
        self.out.push(Outbound::Stdin(k.as_bytes().to_vec()));
    }

    fn paste(&mut self, text: &str) {
        self.key(&format!("{PASTE_START}{text}{PASTE_END}"));
    }

    fn screen_tail(&self) -> String {
        screen::tail_text(&self.rows, self.t.screen_tail_chars)
    }

    /// One stream-json record as the raw line plus its neutral events.
    fn record(v: &Value, out: &mut Vec<HarnessEvent>) {
        out.push(HarnessEvent::Stdout(v.to_string()));
        out.extend(stream::events(v));
    }

    /// The open (not stopping) turn.
    fn open_turn(&mut self) -> Option<&mut Turn> {
        self.active.as_mut().filter(|t| t.stop_at.is_none())
    }

    /// The CLI took the open turn's prompt: the ack.
    fn submitted(&mut self, out: &mut Vec<HarnessEvent>) -> bool {
        match self.open_turn() {
            Some(t) => {
                if !t.submitted {
                    t.submitted = true;
                    if !t.own {
                        out.push(HarnessEvent::TurnStarted);
                    }
                }
                true
            }
            None => false,
        }
    }

    /// A turn began with none open; the first reason wins unless [specific].
    fn own(&mut self, why: String, specific: bool) {
        if self.own_pending.is_none() || specific {
            self.own_pending = Some(why);
        }
    }

    fn stopped(&mut self) {
        let now = self.now;
        match self.active.as_mut() {
            Some(t) if t.submitted || t.entered_at.is_some() || t.own => {
                t.stop_at.get_or_insert(now);
            }
            None => self.unclaimed_stop_at = Some(now),
            _ => {}
        }
    }

    fn hook_event(&mut self, word: &str, out: &mut Vec<HarnessEvent>) {
        self.hook_seen = true;
        match word {
            EV_START => {
                self.started_at.get_or_insert(self.now);
            }
            EV_SUBMIT => {
                if !self.submitted(out) {
                    self.own("a prompt nobody sent".into(), false);
                }
            }
            EV_STOP => self.stopped(),
            _ => {}
        }
    }

    fn transcript(&mut self, line: &str, out: &mut Vec<HarnessEvent>) {
        for s in self.mapper.map(line) {
            match s {
                Signal::Line(text) => {
                    let now = self.now;
                    if let Some(t) = self.active.as_mut() {
                        t.replied_at.get_or_insert(now);
                    }
                    match serde_json::from_str::<Value>(&text) {
                        Ok(v) => Self::record(&v, out),
                        Err(_) => out.push(HarnessEvent::Stdout(text)),
                    }
                }
                Signal::PromptRecorded => {
                    if !self.submitted(out) {
                        self.own("a prompt typed in the terminal".into(), true);
                    }
                }
                Signal::OwnPrompt(kind) => {
                    if !self.submitted(out) {
                        let why = if kind == "task-notification" { "a background task ended".into() } else { format!("a {kind}") };
                        self.own(why, true);
                    }
                }
                Signal::Interrupted => self.interrupted_seen = true,
                Signal::TurnEnded => {
                    if self.active.is_none() && self.own_pending.is_none() {
                        continue;
                    }
                    self.stopped();
                }
                Signal::ApiError(e) => self.api_error = Some(e),
            }
        }
    }

    fn ready(&self) -> bool {
        let now = self.now;
        if self.trust_seen && !self.trust_done {
            return false;
        }
        if self.input_cleared_at.is_some_and(|at| now.saturating_sub(at) < self.t.clear_settle_ms) {
            return false;
        }
        if let Some(s) = self.started_at {
            return now.saturating_sub(s) >= self.t.ready_after_start_ms;
        }
        self.first_output_at.is_some_and(|f| now.saturating_sub(f) >= self.t.ready_fallback_ms)
    }

    fn answer_trust(&mut self, out: &mut Vec<HarnessEvent>) {
        if self.trust_done {
            return;
        }
        let quiet = self.now.saturating_sub(self.last_output_at);
        match screen::trust_menu(&self.rows) {
            // A menu that went away without our Enter (answered by hand, or redrawn away) blocks nothing.
            TrustMenu::Absent => {
                if self.trust_seen && quiet >= self.t.trust_retry_ms {
                    self.trust_done = true;
                }
            }
            TrustMenu::NoPointer => self.trust_seen = true,
            TrustMenu::OnYes => {
                self.trust_seen = true;
                if quiet >= self.t.key_gap_ms {
                    self.key(ENTER);
                    self.trust_done = true;
                    out.push(HarnessEvent::Warning("claude: folder trust answered (accept row selected, Enter)".into()));
                }
            }
            TrustMenu::OnNo => {
                self.trust_seen = true;
                let due = quiet >= self.t.key_gap_ms
                    && self.trust_down_at.is_none_or(|d| self.now.saturating_sub(d) >= self.t.trust_retry_ms);
                if due {
                    self.key(DOWN);
                    self.trust_down_at = Some(self.now);
                }
            }
        }
    }

    /// G3: a dialog drawn while no hook call is open, for the grace: answered No.
    fn answer_dialog(&mut self, out: &mut Vec<HarnessEvent>) {
        let open = self.answers.open();
        if open || !self.active.as_ref().is_some_and(|t| t.abort_at.is_none()) {
            self.dialog_seen_at = None;
            self.menu_seen_at = None;
            return;
        }
        let sq = screen::squeeze_rows(&self.rows);
        let dialog = screen::permission_dialog(&sq);
        let menu = !dialog && screen::ask_menu(&sq);
        if !dialog {
            self.dialog_seen_at = None;
        }
        if !menu {
            self.menu_seen_at = None;
        }
        let now = self.now;
        let seen = match (dialog, menu) {
            (true, _) => *self.dialog_seen_at.get_or_insert(now),
            (_, true) => *self.menu_seen_at.get_or_insert(now),
            _ => return,
        };
        if now.saturating_sub(seen) < self.t.dialog_grace_ms {
            return;
        }
        let reason = if dialog { DIALOG_DENIED_REASON } else { ASK_MENU_REASON };
        out.push(HarnessEvent::Warning(format!("claude: {reason}")));
        if let Some(t) = self.active.as_mut() {
            t.abort_at = Some(now);
            t.abort_reason = Some(reason.into());
        }
        self.interrupted_seen = false;
        self.dialog_seen_at = None;
        self.menu_seen_at = None;
        self.key(ESC);
    }

    fn step_close(&mut self) {
        let Some((step, at)) = self.closing else { return };
        if self.now.saturating_sub(at) < self.t.key_gap_ms {
            return;
        }
        match step {
            0 => {
                self.paste("/exit");
                self.closing = Some((1, self.now));
            }
            1 => {
                self.key(ENTER);
                self.closing = Some((2, self.now));
            }
            _ => {}
        }
    }

    fn check_hooks(&mut self, out: &mut Vec<HarnessEvent>) {
        if self.hook_seen || self.hook_warned {
            return;
        }
        let Some(at) = self.active.as_ref().and_then(|t| t.replied_at) else { return };
        if self.now.saturating_sub(at) >= self.t.hook_silent_ms {
            self.hook_warned = true;
            out.push(HarnessEvent::Warning(format!(
                "claude: its hooks wrote no event {} s into an answered turn; the turn end is read from the transcript only",
                self.t.hook_silent_ms / 1000
            )));
        }
    }

    /// FIRST-TURN-1: the screen shows [turn]'s prompt in the input box (its first characters, or the pasted-text marker).
    fn prompt_on_screen(&self, turn: &Turn) -> bool {
        let drawn = screen::squeeze_rows(&self.rows);
        let head: String = screen::squeeze(&turn.prompt).chars().take(PROMPT_HEAD_CHARS).collect();
        drawn.contains(screen::PASTED_MARKER) || (!head.is_empty() && drawn.contains(&head))
    }

    fn first_turn_due(&self, turn: &Turn) -> bool {
        if !turn.watched || turn.submitted || turn.replied_at.is_some() || turn.abort_at.is_some() || turn.stop_at.is_some() {
            return false;
        }
        let (Some(pasted), true) = (turn.pasted_at, self.t.first_submit_ms > 0) else { return false };
        self.now.saturating_sub(pasted.max(turn.recovered_at.unwrap_or(0))) >= self.t.first_submit_ms
    }

    /// The end of the active turn: its result record and usage, and for a host turn the turn end (a turn the CLI
    /// started itself is not the host's: ending it would end whatever turn the host has queued).
    fn end(&mut self, turn: Turn, end: TurnEnd, reason: Option<String>, out: &mut Vec<HarnessEvent>) {
        let error = if end == TurnEnd::Failed { reason.clone() } else { None };
        let result = self.mapper.result(error.as_deref());
        Self::record(&result, out);
        self.injects.clear();
        if turn.own {
            out.push(HarnessEvent::Warning(format!("claude: its own turn ended ({end:?})")));
        } else {
            out.push(HarnessEvent::TurnEnded { end, reason });
        }
    }

    fn fail_queue(&mut self, reason: &str, out: &mut Vec<HarnessEvent>) {
        for _ in self.queue.drain(..) {
            out.push(HarnessEvent::TurnEnded { end: TurnEnd::Failed, reason: Some(reason.to_string()) });
        }
    }

    fn tick(&mut self, out: &mut Vec<HarnessEvent>) {
        let now = self.now;
        self.step_close();
        self.answer_trust(out);
        self.answer_dialog(out);
        self.check_hooks(out);
        let Some(mut turn) = self.active.take() else {
            if let Some(why) = self.own_pending.take() {
                // The CLI works on a turn of its own: track it, and paste nothing into it until it ends.
                self.active = Some(Turn { own: true, submitted: true, stop_at: self.unclaimed_stop_at.take(), ..Default::default() });
                out.push(HarnessEvent::Warning(format!("claude: started a turn of its own ({why})")));
            } else if !self.queue.is_empty() && self.started_at.is_none() && screen::setup_screen(&screen::squeeze_rows(&self.rows)) {
                let reason = format!("{NOT_SIGNED_IN} Screen: {}", self.screen_tail());
                self.fail_queue(&reason, out);
            } else if !self.queue.is_empty() && self.ready() {
                let prompt = self.queue.pop_front().unwrap_or_default();
                self.unclaimed_stop_at = None;
                self.api_error = None;
                let watched = !self.first_pasted;
                self.first_pasted = true;
                self.paste(&prompt);
                self.active = Some(Turn { prompt, pasted_at: Some(now), watched, ..Default::default() });
            } else if !self.queue.is_empty() && now > self.t.boot_timeout_ms && self.started_at.is_none() {
                let reason =
                    format!("the interactive claude did not come up in {} s; screen: {}", self.t.boot_timeout_ms / 1000, self.screen_tail());
                self.fail_queue(&reason, out);
            }
            if self.unclaimed_stop_at.is_some_and(|at| now.saturating_sub(at) >= self.t.stop_claim_ms) {
                self.unclaimed_stop_at = None;
            }
            return;
        };
        if let Some(abort_at) = turn.abort_at {
            if self.interrupted_seen || now.saturating_sub(abort_at) >= self.t.abort_grace_ms {
                if turn.replied_at.is_none() {
                    self.key(CTRL_C);
                    self.input_cleared_at = Some(now);
                }
                let reason = turn.abort_reason.take();
                self.end(turn, TurnEnd::Interrupted, reason, out);
                return;
            }
        } else if let Some(err) = self.api_error.take() {
            self.end(turn, TurnEnd::Failed, Some(err), out);
            return;
        } else if turn.stop_at.is_some_and(|s| now.saturating_sub(s) >= self.t.stop_settle_ms) {
            self.end(turn, TurnEnd::Completed, None, out);
            return;
        } else if self.first_turn_due(&turn) {
            let waited = format!("took no prompt {} s after the paste", self.t.first_submit_ms / 1000);
            if let Some(tried) = turn.recovery {
                let reason =
                    format!("the interactive claude {waited}, after one recovery ({tried}); screen: {}", self.screen_tail());
                self.end(turn, TurnEnd::Failed, Some(reason), out);
                return;
            }
            let tried = if self.prompt_on_screen(&turn) {
                self.key(ENTER);
                turn.entered_at = Some(now);
                turn.enter_retried = true;
                "Enter pressed again"
            } else {
                self.paste(&turn.prompt.clone());
                turn.pasted_at = Some(now);
                turn.entered_at = None;
                turn.enter_retried = false;
                "the prompt pasted again"
            };
            turn.recovery = Some(tried);
            turn.recovered_at = Some(now);
            out.push(HarnessEvent::Warning(format!("claude: the first turn {waited}; trying once: {tried}")));
        } else if turn.pasted_at.is_some_and(|p| now.saturating_sub(p) >= self.t.paste_settle_ms) && turn.entered_at.is_none() {
            self.key(ENTER);
            turn.entered_at = Some(now);
        } else if turn.entered_at.is_some_and(|e| now.saturating_sub(e) >= self.t.submit_retry_ms)
            && !turn.submitted
            && !turn.enter_retried
        {
            // A paste still being taken can swallow the first Enter.
            self.key(ENTER);
            turn.enter_retried = true;
        }
        if self.injects.front().is_some_and(|at| now.saturating_sub(*at) >= self.t.paste_settle_ms) {
            self.injects.pop_front();
            self.key(ENTER);
        }
        self.active = Some(turn);
    }

    fn exited(&mut self, code: i32, out: &mut Vec<HarnessEvent>) {
        self.exited = true;
        if code != 0 && self.closing.is_none() {
            out.push(HarnessEvent::Warning(format!("the interactive claude exited (code {code}); screen: {}", self.screen_tail())));
        }
        let reason = format!("the interactive claude exited (code {code})");
        if let Some(t) = self.active.take() {
            if !t.own {
                out.push(HarnessEvent::TurnEnded { end: TurnEnd::Failed, reason: Some(reason.clone()) });
            }
        }
        self.fail_queue(&reason, out);
        out.push(HarnessEvent::Exited { code: Some(code) });
    }
}

impl Translator for Interactive {
    fn feed(&mut self, input: TranslatorInput) -> Vec<HarnessEvent> {
        let mut out = std::mem::take(&mut self.later);
        if !self.init_sent {
            self.init_sent = true;
            out.push(HarnessEvent::ProviderSession(self.session_id.clone()));
            out.push(HarnessEvent::Stdout(self.mapper.init().to_string()));
        }
        match input {
            TranslatorInput::Pty(_) => self.output_seen = true,
            TranslatorInput::Screen { rows, now } => {
                self.now = now;
                if std::mem::take(&mut self.output_seen) {
                    self.last_output_at = now;
                    self.first_output_at.get_or_insert(now);
                }
                self.rows = rows;
                self.tick(&mut out);
            }
            TranslatorInput::HookEvent(word) => self.hook_event(&word, &mut out),
            TranslatorInput::Tail { line, .. } => self.transcript(&line, &mut out),
            TranslatorInput::Hook { id, body } => {
                let mut ob = Vec::new();
                out.extend(self.answers.call(id, &body, &mut ob));
                self.out.extend(ob);
                self.dialog_seen_at = None;
                self.menu_seen_at = None;
            }
            TranslatorInput::Exited { code } => self.exited(code, &mut out),
            TranslatorInput::Stdout(l) | TranslatorInput::Stderr(l) => out.push(HarnessEvent::Stderr(l)),
            // This harness sends no HTTP requests, so no HTTP input reaches it.
            TranslatorInput::HttpResponse { .. }
            | TranslatorInput::HttpOpened { .. }
            | TranslatorInput::HttpLine { .. }
            | TranslatorInput::HttpClosed { .. } => {}
        }
        out
    }

    fn send_turn(&mut self, prompt: &str) {
        if self.exited {
            self.later.push(HarnessEvent::TurnEnded {
                end: TurnEnd::Failed,
                reason: Some("the interactive claude has exited".into()),
            });
        } else {
            self.queue.push_back(prompt.to_string());
        }
    }

    fn interrupt(&mut self) -> bool {
        let now = self.now;
        match self.active.as_mut() {
            Some(t) if !t.own => {
                if t.abort_at.is_none() {
                    t.abort_at = Some(now);
                    self.interrupted_seen = false;
                    self.key(ESC);
                }
                true
            }
            _ if !self.queue.is_empty() => {
                // Not pasted yet (the CLI is booting, or busy with a turn of its own): it simply never runs.
                self.queue.pop_front();
                self.later.push(HarnessEvent::TurnEnded { end: TurnEnd::Interrupted, reason: None });
                true
            }
            Some(t) => {
                // Only a turn of the CLI's own is running: stop it.
                if t.abort_at.is_none() {
                    t.abort_at = Some(now);
                    self.interrupted_seen = false;
                    self.key(ESC);
                }
                true
            }
            None => true,
        }
    }

    fn answer(&mut self, id: &str, allow: bool) {
        let mut ob = Vec::new();
        if self.answers.answer(id, allow, &mut ob) {
            self.dialog_seen_at = None;
            self.menu_seen_at = None;
        }
        self.out.extend(ob);
    }

    fn inject(&mut self, text: &str) -> bool {
        let Some(t) = self.active.as_ref() else { return false };
        if self.exited || t.stop_at.is_some() || t.abort_at.is_some() || !(t.own || t.entered_at.is_some()) || !self.injects.is_empty() {
            return false;
        }
        self.paste(text);
        self.injects.push_back(self.now);
        true
    }

    fn close(&mut self) -> bool {
        if self.exited {
            return false;
        }
        if self.closing.is_none() {
            self.key(ESC);
            self.closing = Some((0, self.now));
        }
        true
    }

    fn take_outbound(&mut self) -> Vec<Outbound> {
        std::mem::take(&mut self.out)
    }
}
