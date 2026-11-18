// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Port of `ClaudeTtySession.TtyConnection` (CLI-PTY-1): one interactive claude in its terminal, stepped every tick.
//!
//! - **Start:** the folder trust menu is answered from the vt100 grid (the row the pointer is on), Enter only on the accept row
//!   once the screen is quiet; the CLI is up a moment after its `SessionStart` hook (or a while after its first screen).
//! - **Input:** each turn is pasted (bracketed paste) and submitted with Enter; a swallowed Enter is pressed once more.
//! - **Output:** the transcript, tailed and mapped to stream-json ([`TranscriptMapper`]).
//! - **Turn end:** the `Stop` hook (events file) or the transcript's `turn_duration`; an API error fails the turn.
//! - **Interrupt:** Esc; a turn stopped before its first reply gets Ctrl+C so its prompt does not join the next paste.
//! - **Dialogs:** a permission dialog drawn with no hook call open is answered No (Esc) after a grace; an AskUserQuestion menu
//!   drawn with no hook call open (the hook failed) is cancelled the same way, so a turn never waits on keys nobody sends.
//!
//! - **First turn (FIRST-TURN-1, the not-taken half):** a first paste the booting CLI lost is pressed again or pasted again, once.
//!
//! Not ported in the spike (Kotlin keeps them): FIRST-TURN-1's no-reply half (Esc + a nudge), the MCP pid watch (CLI-PTY-3), the first
//! prompt kept for a recovery (G2), typing into a running turn (CHAT-QUEUE-1), the first-run setup screen check (FIRST-RUN-F1).

use super::transcript::{self, Signal, TranscriptMapper};
use crate::harness::{Driver, Event, Io, TurnEnd};
use crate::hooks::{EventsFile, HookServer};
use crate::screen::squeeze;
use crate::stream;
use crate::tail::AppendedLines;
use std::collections::VecDeque;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};

pub const EV_START: &str = "start";
pub const EV_SUBMIT: &str = "submit";
pub const EV_STOP: &str = "stop";

pub const ESC: &str = "\u{1b}";
pub const ENTER: &str = "\r";
pub const CTRL_C: &str = "\u{3}";
pub const DOWN: &str = "\u{1b}[B";
pub const PASTE_START: &str = "\u{1b}[200~";
pub const PASTE_END: &str = "\u{1b}[201~";

pub const TRUST_OPTION: &str = "itrustthisfolder";
pub const TRUST_DECLINE: &str = "no,exit";
pub const POINTER: char = '❯';

pub const KEY_GAP_MS: u64 = 400;
pub const TRUST_RETRY_MS: u64 = 1_500;
pub const PASTE_SETTLE_MS: u64 = 400;
pub const SUBMIT_RETRY_MS: u64 = 4_000;
pub const STOP_SETTLE_MS: u64 = 300;
pub const STOP_CLAIM_MS: u64 = 2_000;
pub const ABORT_GRACE_MS: u64 = 3_000;
pub const CLEAR_SETTLE_MS: u64 = 400;
pub const READY_AFTER_START_MS: u64 = 1_500;
pub const READY_FALLBACK_MS: u64 = 8_000;
pub const SCREEN_TAIL_CHARS: usize = 600;
pub const DIALOG_WINDOW_CHARS: usize = 4096;
pub const DIALOG_GRACE_MS: u64 = 3_000;
/// How often a transcript not under the cwd's slug is searched for across every project folder (a user can have thousands).
pub const TRANSCRIPT_SCAN_MS: u64 = 5_000;
/// FIRST-TURN-1: from the first paste to the CLI taking it (its UserPromptSubmit hook or the recorded prompt); 0 = not watched.
pub const FIRST_SUBMIT_MS: u64 = 30_000;
/// FIRST-TURN-1: the prompt's first characters (squeezed) that show it sits in the input box.
pub const PROMPT_HEAD_CHARS: usize = 24;
/// claude's marker for a long paste folded in the input box (squeezed).
pub const PASTED_MARKER: &str = "[pastedtext#";
const PROMPT_WINDOW_CHARS: usize = 16_384;
/// What every permission dialog of the CLI reads as, squeezed and lower-cased (its "No" option).
pub const DIALOG_MARKERS: [&str; 2] = ["tellclaudewhattododifferently", "doyouwanttoproceed?"];
pub const DIALOG_DENIED_REASON: &str = "claude showed a permission dialog that no approval answered; it was answered No";
/// The AskUserQuestion menu's footer, squeezed (every marker must be on screen).
pub const ASK_MENU_MARKERS: [&str; 2] = ["entertoselect", "esctocancel"];
pub const ASK_MENU_REASON: &str = "claude drew its AskUserQuestion menu with no hook call open; it was cancelled";

/// The trust menu as the grid shows it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrustMenu {
    Absent,
    /// On screen, the pointer not drawn yet.
    NoPointer,
    OnYes,
    OnNo,
}

/// Reads the folder trust menu from the visible rows: the row that holds the pointer is the selected one.
pub fn trust_menu(rows: &[String]) -> TrustMenu {
    let sq: Vec<String> = rows.iter().map(|r| squeeze(r)).collect();
    if !sq.iter().any(|r| r.contains(TRUST_OPTION)) {
        return TrustMenu::Absent;
    }
    for r in &sq {
        if r.contains(POINTER) {
            if r.contains(TRUST_OPTION) {
                return TrustMenu::OnYes;
            }
            if r.contains(TRUST_DECLINE) {
                return TrustMenu::OnNo;
            }
        }
    }
    TrustMenu::NoPointer
}

/// Whether the AskUserQuestion menu is drawn on the visible rows.
pub fn ask_menu(rows: &[String]) -> bool {
    let all = squeeze(&rows.join("\n"));
    ASK_MENU_MARKERS.iter().all(|m| all.contains(m))
}

#[derive(Default)]
struct Turn {
    prompt: String,
    own: bool,
    abort_at: Option<u64>,
    abort_reason: Option<String>,
    pasted_at: Option<u64>,
    entered_at: Option<u64>,
    enter_retried: bool,
    submitted: bool,
    stop_at: Option<u64>,
    replied: bool,
    /// FIRST-TURN-1: the connection's first sent turn, held to the submit deadline.
    watched: bool,
    /// Screen bytes at the paste: what is drawn after it is where the prompt would show.
    screen_mark: u64,
    recovery: Option<&'static str>,
    recovered_at: Option<u64>,
}

/// What the hook handlers (other threads) share with the driver.
#[derive(Default)]
pub struct HookState {
    /// Hook calls being answered now: the dialog fallbacks wait while any is open.
    pub open: AtomicUsize,
    /// A call was answered since the last tick: what was drawn before it is history.
    pub answered: AtomicBool,
    /// Notes to report as [Event::Detail].
    pub notes: Mutex<Vec<String>>,
}

impl HookState {
    pub fn note(&self, s: impl Into<String>) {
        self.notes.lock().unwrap().push(s.into());
    }
}

pub struct DriverConfig {
    pub session_id: String,
    pub cwd: String,
    pub config_dir: PathBuf,
    pub events: PathBuf,
    /// Byte offset of a resumed transcript at launch: what is before it is history.
    pub transcript_start: u64,
    pub boot_timeout_ms: u64,
    /// FIRST-TURN-1: [FIRST_SUBMIT_MS] by default.
    pub first_submit_ms: u64,
    pub hooks: Arc<HookServer>,
    pub hook_tokens: Vec<String>,
    pub hook_state: Arc<HookState>,
}

pub struct ClaudeDriver {
    cfg: DriverConfig,
    events: EventsFile,
    transcript: AppendedLines,
    transcript_file: Option<PathBuf>,
    scanned_at: Option<u64>,
    mapper: TranscriptMapper,
    queue: VecDeque<String>,
    active: Option<Turn>,
    started_at: Option<u64>,
    trust_seen: bool,
    trust_down_at: Option<u64>,
    trust_enter_done: bool,
    interrupted_seen: bool,
    input_cleared_at: Option<u64>,
    init_sent: bool,
    exited: bool,
    own_pending: Option<String>,
    unclaimed_stop_at: Option<u64>,
    dialog_mark: u64,
    dialog_seen_at: Option<u64>,
    menu_seen_at: Option<u64>,
    /// (step, at): 0 = Esc sent, 1 = `/exit` pasted, 2 = Enter sent.
    closing: Option<(u8, u64)>,
    first_pasted: bool,
}

impl ClaudeDriver {
    pub fn new(cfg: DriverConfig) -> Self {
        ClaudeDriver {
            events: EventsFile::fresh(cfg.events.clone()),
            transcript: AppendedLines::from(cfg.transcript_start),
            transcript_file: None,
            scanned_at: None,
            mapper: TranscriptMapper::new(&cfg.session_id),
            queue: VecDeque::new(),
            active: None,
            started_at: None,
            trust_seen: false,
            trust_down_at: None,
            trust_enter_done: false,
            interrupted_seen: false,
            input_cleared_at: None,
            init_sent: false,
            exited: false,
            own_pending: None,
            unclaimed_stop_at: None,
            dialog_mark: 0,
            dialog_seen_at: None,
            menu_seen_at: None,
            closing: None,
            first_pasted: false,
            cfg,
        }
    }

    fn output(io: &Io<'_>, line: String) {
        io.emit(Event::Output { records: stream::parse_line(&line), line });
    }

    fn paste(io: &mut Io<'_>, text: &str) {
        io.write(&format!("{PASTE_START}{text}{PASTE_END}"));
    }

    fn read_transcript(&mut self, now: u64) -> Vec<String> {
        if self.transcript_file.is_none() {
            let (config, id) = (&self.cfg.config_dir, &self.cfg.session_id);
            self.transcript_file = transcript::find_under(config, &self.cfg.cwd, id);
            if self.transcript_file.is_none() && self.scanned_at.is_none_or(|at| now.saturating_sub(at) >= TRANSCRIPT_SCAN_MS) {
                self.scanned_at = Some(now);
                self.transcript_file = transcript::find(config, None, id);
            }
        }
        match &self.transcript_file {
            Some(p) => self.transcript.read(p),
            None => Vec::new(),
        }
    }

    /// A turn began with none open (or the open one stopped); the first reason wins unless [specific].
    fn own(&mut self, why: &str, specific: bool) {
        if self.own_pending.is_none() || specific {
            self.own_pending = Some(why.into());
        }
    }

    fn open_turn(&mut self) -> Option<&mut Turn> {
        self.active.as_mut().filter(|t| t.stop_at.is_none())
    }

    /// One transcript signal; returns an API error's text.
    fn on_signal(&mut self, s: Signal, io: &Io<'_>) -> Option<String> {
        match s {
            Signal::Line(json) => {
                if let Some(t) = self.active.as_mut() {
                    t.replied = true;
                }
                Self::output(io, json);
            }
            Signal::PromptRecorded => match self.open_turn() {
                Some(t) => t.submitted = true,
                None => self.own("a prompt typed in the terminal", true),
            },
            Signal::OwnPrompt(kind) => match self.open_turn() {
                Some(t) => t.submitted = true,
                None => {
                    let why = if kind == "task-notification" { "a background task ended".to_string() } else { format!("a {kind}") };
                    self.own(&why, true)
                }
            },
            Signal::Interrupted => self.interrupted_seen = true,
            Signal::TurnEnded => match self.active.as_mut() {
                Some(t) if t.submitted || t.entered_at.is_some() || t.own => {
                    t.stop_at.get_or_insert(io.now);
                }
                None if self.own_pending.is_some() => self.unclaimed_stop_at = Some(io.now),
                _ => {}
            },
            Signal::ApiError(t) => return Some(t),
        }
        None
    }

    fn ready(&self, io: &Io<'_>) -> bool {
        let now = io.now;
        if self.trust_seen && !self.trust_enter_done {
            return false;
        }
        if self.input_cleared_at.is_some_and(|at| now.saturating_sub(at) < CLEAR_SETTLE_MS) {
            return false;
        }
        if let Some(s) = self.started_at {
            return now.saturating_sub(s) >= READY_AFTER_START_MS;
        }
        io.screen.first_output_at().is_some_and(|f| now.saturating_sub(f) >= READY_FALLBACK_MS)
    }

    /// The folder trust menu: Down only while "No, exit" is selected, Enter only on the accept row once the screen is quiet.
    fn answer_trust(&mut self, io: &mut Io<'_>) {
        if self.trust_enter_done {
            return;
        }
        let menu = trust_menu(&io.screen.rows());
        let quiet = io.now.saturating_sub(io.screen.last_output_at());
        match menu {
            TrustMenu::Absent => {
                // A menu that went away without our Enter (answered by hand, or a redraw replaced it) blocks nothing.
                if self.trust_seen && quiet >= TRUST_RETRY_MS {
                    self.trust_enter_done = true;
                }
            }
            TrustMenu::NoPointer => self.trust_seen = true,
            TrustMenu::OnYes => {
                self.trust_seen = true;
                if quiet >= KEY_GAP_MS {
                    io.write(ENTER);
                    self.trust_enter_done = true;
                    io.emit(Event::Detail("interactive claude: folder trust answered (accept row selected, Enter)".into()));
                }
            }
            TrustMenu::OnNo => {
                self.trust_seen = true;
                let due = match self.trust_down_at {
                    None => quiet >= KEY_GAP_MS,
                    Some(d) => io.now.saturating_sub(d) >= TRUST_RETRY_MS && quiet >= KEY_GAP_MS,
                };
                if due {
                    io.write(DOWN);
                    self.trust_down_at = Some(io.now);
                }
            }
        }
    }

    /// G3: a permission dialog drawn after the last answered hook call, with no hook call open for the grace: answered No.
    fn answer_dialog(&mut self, io: &mut Io<'_>) {
        let open = self.cfg.hook_state.open.load(Ordering::SeqCst) > 0;
        let Some(turn) = self.active.as_mut().filter(|t| t.abort_at.is_none() && !open) else {
            self.dialog_seen_at = None;
            self.menu_seen_at = None;
            return;
        };
        let tail = squeeze(&io.screen.text_since(self.dialog_mark, DIALOG_WINDOW_CHARS));
        let dialog = DIALOG_MARKERS.iter().any(|m| tail.contains(m));
        let menu = !dialog && ask_menu(&io.screen.rows());
        if !dialog {
            self.dialog_seen_at = None;
        }
        if !menu {
            self.menu_seen_at = None;
        }
        let seen = match (dialog, menu) {
            (true, _) => *self.dialog_seen_at.get_or_insert(io.now),
            (_, true) => *self.menu_seen_at.get_or_insert(io.now),
            _ => return,
        };
        if io.now.saturating_sub(seen) < DIALOG_GRACE_MS {
            return;
        }
        let reason = if dialog { DIALOG_DENIED_REASON } else { ASK_MENU_REASON };
        io.emit(Event::Detail(format!("interactive claude: {reason}")));
        turn.abort_at = Some(io.now);
        turn.abort_reason = Some(reason.into());
        self.interrupted_seen = false;
        self.dialog_mark = io.screen.total();
        self.dialog_seen_at = None;
        self.menu_seen_at = None;
        io.write(ESC);
    }

    /// FIRST-TURN-1: the watched first turn was not taken [DriverConfig::first_submit_ms] after its paste (or its recovery).
    fn first_turn_due(&self, turn: &Turn, now: u64) -> bool {
        if !turn.watched || turn.submitted || turn.replied || turn.abort_at.is_some() || turn.stop_at.is_some() || self.cfg.first_submit_ms == 0 {
            return false;
        }
        let Some(pasted) = turn.pasted_at else { return false };
        now.saturating_sub(pasted.max(turn.recovered_at.unwrap_or(0))) >= self.cfg.first_submit_ms
    }

    fn step_close(&mut self, io: &mut Io<'_>) {
        let Some((step, at)) = self.closing else { return };
        if io.now.saturating_sub(at) < KEY_GAP_MS {
            return;
        }
        match step {
            0 => {
                Self::paste(io, "/exit");
                self.closing = Some((1, io.now));
            }
            1 => {
                io.write(ENTER);
                self.closing = Some((2, io.now));
            }
            _ => {}
        }
    }
}

/// FIRST-TURN-1: the screen drawn since [turn]'s paste shows it in the input box (its first characters, or the pasted-text marker).
fn prompt_on_screen(turn: &Turn, io: &Io<'_>) -> bool {
    let drawn = squeeze(&io.screen.text_since(turn.screen_mark, PROMPT_WINDOW_CHARS));
    let head: String = squeeze(&turn.prompt).chars().take(PROMPT_HEAD_CHARS).collect();
    drawn.contains(PASTED_MARKER) || (!head.is_empty() && drawn.contains(&head))
}

impl Driver for ClaudeDriver {
    fn tick(&mut self, io: &mut Io<'_>) {
        let now = io.now;
        let hs = self.cfg.hook_state.clone();
        if hs.answered.swap(false, Ordering::SeqCst) {
            self.dialog_mark = io.screen.total();
            self.dialog_seen_at = None;
            self.menu_seen_at = None;
        }
        for n in std::mem::take(&mut *hs.notes.lock().unwrap()) {
            io.emit(Event::Detail(n));
        }
        for e in self.events.read() {
            match e.as_str() {
                EV_START => {
                    self.started_at.get_or_insert(now);
                }
                EV_SUBMIT => match self.open_turn() {
                    Some(t) => t.submitted = true,
                    None => self.own("a prompt nobody sent", false),
                },
                EV_STOP => match self.active.as_mut() {
                    Some(t) if t.submitted || t.entered_at.is_some() || t.own => {
                        t.stop_at.get_or_insert(now);
                    }
                    None => self.unclaimed_stop_at = Some(now),
                    _ => {}
                },
                _ => {}
            }
        }
        if !self.init_sent {
            self.init_sent = true;
            io.emit(Event::ProviderSession(self.cfg.session_id.clone()));
            Self::output(io, self.mapper.init_line());
        }
        self.step_close(io);
        self.answer_trust(io);
        self.answer_dialog(io);
        let mut api_error = None;
        for line in self.read_transcript(now) {
            for s in self.mapper.map(&line) {
                if let Some(e) = self.on_signal(s, io) {
                    api_error = Some(e);
                }
            }
        }
        let Some(mut turn) = self.active.take() else {
            if let Some(why) = self.own_pending.take() {
                // The CLI works on a turn of its own: track it, and paste nothing into it until it ends.
                self.active = Some(Turn { own: true, submitted: true, stop_at: self.unclaimed_stop_at.take(), ..Default::default() });
                io.emit(Event::TurnStarted { reason: Some(why) });
            } else if !self.queue.is_empty() && self.ready(io) {
                let prompt = self.queue.pop_front().unwrap();
                self.unclaimed_stop_at = None;
                let (watched, screen_mark) = (!self.first_pasted, io.screen.total());
                self.first_pasted = true;
                Self::paste(io, &prompt);
                self.active = Some(Turn { prompt, pasted_at: Some(now), watched, screen_mark, ..Default::default() });
            } else if !self.queue.is_empty() && now > self.cfg.boot_timeout_ms && self.started_at.is_none() {
                let reason = format!(
                    "the interactive claude did not come up in {} s; screen: {}",
                    self.cfg.boot_timeout_ms / 1000,
                    io.screen.tail_text(SCREEN_TAIL_CHARS)
                );
                for _ in self.queue.drain(..) {
                    io.emit(Event::TurnEnded { end: TurnEnd::Failed, reason: Some(reason.clone()) });
                }
            }
            if self.unclaimed_stop_at.is_some_and(|at| now.saturating_sub(at) >= STOP_CLAIM_MS) {
                self.unclaimed_stop_at = None;
            }
            return;
        };
        if let Some(abort_at) = turn.abort_at {
            if self.interrupted_seen || now.saturating_sub(abort_at) >= ABORT_GRACE_MS {
                Self::output(io, self.mapper.result_line(None)); // an interrupted turn still reports the usage it spent
                if !turn.replied {
                    io.write(CTRL_C);
                    self.input_cleared_at = Some(now);
                }
                io.emit(Event::TurnEnded { end: TurnEnd::Interrupted, reason: turn.abort_reason.take() });
                return;
            }
        } else if let Some(err) = api_error {
            Self::output(io, self.mapper.result_line(Some(&err)));
            io.emit(Event::TurnEnded { end: TurnEnd::Failed, reason: Some(err) });
            return;
        } else if turn.stop_at.is_some_and(|s| now.saturating_sub(s) >= STOP_SETTLE_MS) {
            // The hook can fire just before the last record is flushed: one more read, then the turn's result.
            self.active = Some(turn);
            for line in self.read_transcript(now) {
                for s in self.mapper.map(&line) {
                    self.on_signal(s, io);
                }
            }
            self.active = None;
            Self::output(io, self.mapper.result_line(None));
            io.emit(Event::TurnEnded { end: TurnEnd::Completed, reason: None });
            return;
        } else if self.first_turn_due(&turn, now) {
            // FIRST-TURN-1: ONE recovery (Enter again when the prompt shows in the input box, else the prompt pasted again), then failure.
            let waited = format!("took no prompt {} s after the paste", self.cfg.first_submit_ms / 1000);
            if let Some(tried) = turn.recovery {
                let reason = format!("the interactive claude {waited}, after one recovery ({tried}); screen: {}", io.screen.tail_text(SCREEN_TAIL_CHARS));
                io.emit(Event::TurnEnded { end: TurnEnd::Failed, reason: Some(reason) });
                return;
            }
            let tried = if prompt_on_screen(&turn, io) {
                io.write(ENTER);
                turn.entered_at = Some(now);
                turn.enter_retried = true;
                "Enter pressed again"
            } else {
                turn.screen_mark = io.screen.total();
                Self::paste(io, &turn.prompt);
                turn.pasted_at = Some(now);
                turn.entered_at = None;
                turn.enter_retried = false;
                "the prompt pasted again"
            };
            turn.recovery = Some(tried);
            turn.recovered_at = Some(now);
            io.emit(Event::Detail(format!("interactive claude: the first turn {waited}; trying once: {tried}")));
        } else if turn.pasted_at.is_some_and(|p| now.saturating_sub(p) >= PASTE_SETTLE_MS) && turn.entered_at.is_none() {
            io.write(ENTER);
            turn.entered_at = Some(now);
        } else if turn.entered_at.is_some_and(|e| now.saturating_sub(e) >= SUBMIT_RETRY_MS) && !turn.submitted && !turn.enter_retried {
            // A paste still being taken can swallow the first Enter.
            io.write(ENTER);
            turn.enter_retried = true;
        }
        self.active = Some(turn);
    }

    fn send_turn(&mut self, prompt: String, io: &mut Io<'_>) {
        if self.exited {
            io.emit(Event::TurnEnded { end: TurnEnd::Failed, reason: Some("the interactive claude has exited".into()) });
        } else {
            self.queue.push_back(prompt);
        }
    }

    fn abort_turn(&mut self, io: &mut Io<'_>) -> bool {
        let Some(t) = self.active.as_mut() else { return false };
        if t.abort_at.is_none() {
            t.abort_at = Some(io.now);
            self.interrupted_seen = false;
            io.write(ESC);
        }
        true
    }

    fn close(&mut self, io: &mut Io<'_>) {
        if self.closing.is_none() && !self.exited {
            io.write(ESC);
            self.closing = Some((0, io.now));
        }
    }

    fn on_exit(&mut self, code: i32, io: &mut Io<'_>) {
        self.exited = true;
        for t in &self.cfg.hook_tokens {
            self.cfg.hooks.unregister(t);
        }
        if code != 0 && self.closing.is_none() {
            io.emit(Event::Detail(format!(
                "the interactive claude exited (code {code}); screen: {}",
                io.screen.tail_text(SCREEN_TAIL_CHARS)
            )));
        }
        let open = self.active.take().into_iter().count() + self.queue.drain(..).count();
        for _ in 0..open {
            io.emit(Event::TurnEnded { end: TurnEnd::Failed, reason: Some(format!("the interactive claude exited (code {code})")) });
        }
    }

    fn busy(&self) -> bool {
        self.active.is_some() || !self.queue.is_empty()
    }
}
