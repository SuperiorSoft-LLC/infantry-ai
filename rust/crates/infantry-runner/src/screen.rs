// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `pty-screen`: what a terminal program shows. Two views of the same output:
//! - the vt100 grid ([`Screen::rows`]): the screen as a person sees it now (which menu row is selected, what dialog is up);
//! - the plain-text scrollback ([`Screen::text_since`]): escapes stripped, appended in arrival order, trimmed to a window, with a
//!   running character count so a reader can mark "what was drawn after X" (an answered dialog is history).

/// The scrollback kept, in characters.
pub const SCROLLBACK_KEEP_CHARS: usize = 32 * 1024;

pub struct Screen {
    parser: vt100::Parser,
    scrollback: String,
    /// Characters appended to the scrollback so far (the scrollback is trimmed; this is not).
    total: u64,
    /// Bytes of a UTF-8 character cut by a read boundary, waiting for the rest.
    pending: Vec<u8>,
    /// Bytes ever fed.
    bytes: u64,
    first_output_at: Option<u64>,
    last_output_at: u64,
}

impl Screen {
    pub fn new(rows: u16, cols: u16) -> Self {
        Screen {
            parser: vt100::Parser::new(rows, cols, 0),
            scrollback: String::new(),
            total: 0,
            pending: Vec::new(),
            bytes: 0,
            first_output_at: None,
            last_output_at: 0,
        }
    }

    /// Feeds raw terminal output that arrived at [now] (ms on the session clock).
    pub fn feed(&mut self, data: &[u8], now: u64) {
        if data.is_empty() {
            return;
        }
        self.bytes += data.len() as u64;
        self.first_output_at.get_or_insert(now);
        self.last_output_at = now;
        self.parser.process(data);
        self.pending.extend_from_slice(data);
        let (text, rest) = split_utf8(&self.pending);
        let stripped = strip(&text);
        self.pending = rest;
        self.total += stripped.chars().count() as u64;
        self.scrollback.push_str(&stripped);
        if self.scrollback.len() > SCROLLBACK_KEEP_CHARS * 2 {
            let cut = self.scrollback.len() - SCROLLBACK_KEEP_CHARS;
            let cut = (cut..self.scrollback.len()).find(|i| self.scrollback.is_char_boundary(*i)).unwrap_or(cut);
            self.scrollback.drain(..cut);
        }
    }

    /// The visible rows as text (trailing blanks trimmed).
    pub fn rows(&self) -> Vec<String> {
        let screen = self.parser.screen();
        let (_, cols) = screen.size();
        screen.rows(0, cols).map(|r| r.trim_end().to_string()).collect()
    }

    /// The visible screen as one text block.
    pub fn contents(&self) -> String {
        self.parser.screen().contents()
    }

    /// The cursor (row, col), 0-based, for answering a cursor position query.
    pub fn cursor(&self) -> (u16, u16) {
        self.parser.screen().cursor_position()
    }

    pub fn total(&self) -> u64 {
        self.total
    }

    pub fn bytes(&self) -> u64 {
        self.bytes
    }

    pub fn first_output_at(&self) -> Option<u64> {
        self.first_output_at
    }

    pub fn last_output_at(&self) -> u64 {
        self.last_output_at
    }

    /// The scrollback drawn after [mark] (a [total] read earlier), at most [max] characters from its end.
    pub fn text_since(&self, mark: u64, max: usize) -> String {
        let since = self.total.saturating_sub(mark) as usize;
        self.tail_chars(since.min(max))
    }

    /// The last [n] characters of the scrollback.
    pub fn tail_chars(&self, n: usize) -> String {
        let count = self.scrollback.chars().count();
        self.scrollback.chars().skip(count.saturating_sub(n)).collect()
    }

    /// The last screen text as a person reads it (whitespace runs become one space), for a failure message.
    pub fn tail_text(&self, n: usize) -> String {
        let t = self.tail_chars(n);
        let t = t.split_whitespace().collect::<Vec<_>>().join(" ");
        if t.is_empty() { "(blank)".into() } else { t }
    }
}

/// Splits [bytes] into the longest decodable text and the bytes of a character not yet complete.
fn split_utf8(bytes: &[u8]) -> (String, Vec<u8>) {
    match std::str::from_utf8(bytes) {
        Ok(s) => (s.to_string(), Vec::new()),
        Err(e) if e.error_len().is_none() => {
            let ok = e.valid_up_to();
            (String::from_utf8_lossy(&bytes[..ok]).into_owned(), bytes[ok..].to_vec())
        }
        Err(_) => (String::from_utf8_lossy(bytes).into_owned(), Vec::new()),
    }
}

/// Plain text of a terminal chunk: escape sequences and control characters dropped (newlines kept).
pub fn strip(s: &str) -> String {
    let c: Vec<char> = s.chars().collect();
    let mut b = String::with_capacity(s.len());
    let mut i = 0;
    while i < c.len() {
        let ch = c[i];
        if ch == '\u{1b}' {
            i += 1;
            if i >= c.len() {
                break;
            }
            match c[i] {
                '[' => {
                    i += 1;
                    while i < c.len() && !('@'..='~').contains(&c[i]) {
                        i += 1;
                    }
                    i += 1;
                }
                ']' => {
                    i += 1;
                    while i < c.len() && c[i] != '\u{7}' && c[i] != '\u{1b}' {
                        i += 1;
                    }
                    if i < c.len() && c[i] == '\u{1b}' {
                        i += 1;
                    }
                    i += 1;
                }
                '(' | ')' => i += 2,
                _ => i += 1,
            }
        } else {
            if ch >= ' ' || ch == '\n' {
                b.push(ch);
            }
            i += 1;
        }
    }
    b
}

/// Lower case, no whitespace: a cursor move between words leaves no space on screen, so matching ignores spaces.
pub fn squeeze(s: &str) -> String {
    s.chars().filter(|c| !c.is_whitespace()).flat_map(char::to_lowercase).collect()
}

/// Terminal queries a program may block on, found in a chunk of its output: answered by the runner like a terminal would.
#[derive(Debug, PartialEq, Eq)]
pub enum TerminalQuery {
    /// `ESC [ 6 n`: report the cursor position (ConPTY sends it at start and waits for the answer).
    CursorPosition,
}

pub fn queries(data: &[u8]) -> Vec<TerminalQuery> {
    data.windows(4).filter(|w| w == b"\x1b[6n").map(|_| TerminalQuery::CursorPosition).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strip_drops_escapes() {
        assert_eq!(strip("\u{1b}[1mHi\u{1b}[0m \u{1b}]0;title\u{7}there\r\n"), "Hi there\n");
    }

    #[test]
    fn squeeze_ignores_spaces_and_case() {
        assert_eq!(squeeze(" Yes, I trust\n this folder"), "yes,itrustthisfolder");
    }

    #[test]
    fn a_character_cut_by_a_read_waits_for_its_rest() {
        let mut s = Screen::new(5, 40);
        let bytes = "❯ ok".as_bytes();
        s.feed(&bytes[..2], 1);
        assert_eq!(s.tail_chars(10), "");
        s.feed(&bytes[2..], 2);
        assert_eq!(s.tail_chars(10), "❯ ok");
        assert_eq!(s.total(), 4);
    }

    #[test]
    fn the_grid_shows_a_repainted_cell_where_it_is() {
        let mut s = Screen::new(5, 40);
        s.feed(b"\x1b[1;1H\xe2\x9d\xaf Yes\x1b[2;1H  No", 1);
        // a repaint that moves only the pointer cell
        s.feed(b"\x1b[1;1H \x1b[2;1H\xe2\x9d\xaf", 2);
        let rows = s.rows();
        assert_eq!(rows[0], "  Yes");
        assert_eq!(rows[1], "❯ No");
        assert_eq!(s.text_since(0, 100).matches('❯').count(), 2, "the scrollback keeps every repaint");
    }

    #[test]
    fn cursor_queries_are_found() {
        assert_eq!(queries(b"ab\x1b[6ncd"), vec![TerminalQuery::CursorPosition]);
        assert!(queries(b"\x1b[6m").is_empty());
    }
}
