// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `file-tail`: the lines appended to a file since the last read. A line still being written waits for its newline, so a
//! UTF-8 character is never cut; a file that shrank (replaced or truncated) is read again from its start.

use std::fs::File;
use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

/// Bytes read per call at most; the rest comes at the next call.
pub const READ_CHUNK: u64 = 256 * 1024;

#[derive(Debug, Default)]
pub struct AppendedLines {
    offset: u64,
}

impl AppendedLines {
    /// Starts at byte [start]: what is before it is history (a resumed transcript), never read.
    pub fn from(start: u64) -> Self {
        AppendedLines { offset: start }
    }

    pub fn offset(&self) -> u64 {
        self.offset
    }

    /// The whole lines appended to [path] since the last call (blank lines dropped, CR trimmed). A missing file reads nothing.
    pub fn read(&mut self, path: &Path) -> Vec<String> {
        let Ok(mut f) = File::open(path) else { return Vec::new() };
        let Ok(len) = f.metadata().map(|m| m.len()) else { return Vec::new() };
        if len < self.offset {
            self.offset = 0;
        }
        if len == self.offset {
            return Vec::new();
        }
        if f.seek(SeekFrom::Start(self.offset)).is_err() {
            return Vec::new();
        }
        // Read a chunk at a time until a newline shows up: a line longer than one chunk (a big tool result or a
        // compaction summary in a resumed transcript) must still be delivered, never stall the tail.
        let mut buf: Vec<u8> = Vec::new();
        let mut remaining = len - self.offset;
        let last_nl = loop {
            let want = remaining.min(READ_CHUNK) as usize;
            if want == 0 {
                return Vec::new();
            }
            let start = buf.len();
            buf.resize(start + want, 0);
            let mut got = 0;
            while got < want {
                match f.read(&mut buf[start + got..]) {
                    Ok(0) => break,
                    Ok(n) => got += n,
                    Err(_) => break,
                }
            }
            buf.truncate(start + got);
            if let Some(i) = buf.iter().rposition(|b| *b == b'\n') {
                break i;
            }
            if got == 0 {
                return Vec::new();
            }
            remaining -= got as u64;
        };
        self.offset += last_nl as u64 + 1;
        String::from_utf8_lossy(&buf[..=last_nl])
            .split('\n')
            .map(|l| l.trim_end_matches('\r').to_string())
            .filter(|l| !l.trim().is_empty())
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    #[test]
    fn appended_lines_wait_for_their_newline_and_restart_on_truncation() {
        let dir = std::env::temp_dir().join(format!("infantry-tail-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join("t.jsonl");
        let mut t = AppendedLines::default();
        assert!(t.read(&p).is_empty(), "no file yet");
        let mut f = std::fs::OpenOptions::new().create(true).append(true).open(&p).unwrap();
        f.write_all(b"one\r\ntw").unwrap();
        assert_eq!(t.read(&p), vec!["one"]);
        f.write_all("o ❯\n\nthree\n".as_bytes()).unwrap();
        assert_eq!(t.read(&p), vec!["two ❯", "three"]);
        drop(f);
        std::fs::write(&p, b"new\n").unwrap();
        assert_eq!(t.read(&p), vec!["new"]);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn a_line_longer_than_one_chunk_is_delivered_and_the_tail_goes_on() {
        let dir = std::env::temp_dir().join(format!("infantry-tail-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join("big.jsonl");
        let big = "x".repeat(READ_CHUNK as usize * 3 + 17);
        std::fs::write(&p, format!("before
{big}
after
")).unwrap();
        let mut t = AppendedLines::default();
        let mut all = Vec::new();
        for _ in 0..3 {
            all.extend(t.read(&p));
        }
        assert_eq!(all.len(), 3, "the huge line must not stall the tail");
        assert_eq!(all[1].len(), big.len());
        assert_eq!(all[2], "after");
        let _ = std::fs::remove_dir_all(dir);
    }
}
