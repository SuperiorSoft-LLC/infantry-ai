// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! What the interactive claude's screen shows, read from its visible rows: the folder trust menu, a permission dialog,
//! the AskUserQuestion menu, the first-run setup. Markers are matched squeezed (no whitespace, lower case), so a redraw
//! that moves or wraps words still matches.

/// The text with every whitespace character removed, lower-cased.
pub fn squeeze(s: &str) -> String {
    s.chars().filter(|c| !c.is_whitespace()).flat_map(char::to_lowercase).collect()
}

pub fn squeeze_rows(rows: &[String]) -> String {
    squeeze(&rows.join("\n"))
}

pub const TRUST_OPTION: &str = "itrustthisfolder";
pub const TRUST_DECLINE: &str = "no,exit";
/// What the CLI draws before the selected option: `❯`, or `>` once its UI has fallen back to ASCII glyphs (which it does
/// when the environment it starts in names no capable terminal, e.g. a daemon the desktop app started has no `TERM`).
pub const POINTERS: [char; 2] = ['❯', '>'];

/// What every permission dialog of the CLI reads as (its "No" option, its question).
pub const DIALOG_MARKERS: [&str; 2] = ["tellclaudewhattododifferently", "doyouwanttoproceed?"];
/// The AskUserQuestion menu's footer (every marker must be on screen).
pub const ASK_MENU_MARKERS: [&str; 2] = ["entertoselect", "esctocancel"];
/// FIRST-RUN-F1: the first-run setup screens (theme pick, sign in); a paste there is never a turn.
pub const SETUP_MARKERS: [&str; 2] = ["choosethetextstylethatlooksbestwithyourterminal", "selectloginmethod"];
/// claude's marker for a long paste folded in its input box.
pub const PASTED_MARKER: &str = "[pastedtext#";

/// The trust menu as the grid shows it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrustMenu {
    Absent,
    /// On screen, the pointer not drawn yet.
    NoPointer,
    OnYes,
    OnNo,
}

/// Whether the (squeezed) option row [sq] is the selected one: a pointer leads it, ahead of the option's [label], with only
/// a border or rule character (never a word) before the pointer, so a `>` inside a sentence is not one.
fn pointed(sq: &str, label: &str) -> bool {
    let Some(label_at) = sq.find(label) else { return false };
    sq.find(POINTERS).is_some_and(|at| at < label_at && !sq[..at].chars().any(char::is_alphanumeric))
}

/// The folder trust menu: the row that holds the pointer is the selected one.
pub fn trust_menu(rows: &[String]) -> TrustMenu {
    let sq: Vec<String> = rows.iter().map(|r| squeeze(r)).collect();
    if !sq.iter().any(|r| r.contains(TRUST_OPTION)) {
        return TrustMenu::Absent;
    }
    for r in &sq {
        if pointed(r, TRUST_OPTION) {
            return TrustMenu::OnYes;
        }
        if pointed(r, TRUST_DECLINE) {
            return TrustMenu::OnNo;
        }
    }
    TrustMenu::NoPointer
}

pub fn permission_dialog(squeezed: &str) -> bool {
    DIALOG_MARKERS.iter().any(|m| squeezed.contains(m))
}

pub fn ask_menu(squeezed: &str) -> bool {
    ASK_MENU_MARKERS.iter().all(|m| squeezed.contains(m))
}

pub fn setup_screen(squeezed: &str) -> bool {
    SETUP_MARKERS.iter().any(|m| squeezed.contains(m))
}

/// The last [max] characters of the visible rows (rows with something to read, joined), for a failure reason: a rule or
/// a box edge says nothing and would eat the characters the reader needs.
pub fn tail_text(rows: &[String], max: usize) -> String {
    let text = rows.iter().map(|r| r.trim()).filter(|r| r.chars().any(char::is_alphanumeric)).collect::<Vec<_>>().join(" | ");
    let n = text.chars().count();
    text.chars().skip(n.saturating_sub(max)).collect()
}
