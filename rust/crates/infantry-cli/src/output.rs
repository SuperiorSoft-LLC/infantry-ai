// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Stdout and stderr as the Kotlin CLI writes them: UTF-8, or US-ASCII when `INFANTRY_CLI_ASCII=1` (each character
//! outside ASCII becomes `?`, as Java's encoder writes it), `\n` line ends, flushed per line.

use serde_json::Value;
use std::io::Write;
use std::sync::{Arc, Mutex};

#[derive(Clone)]
pub struct Output {
    ascii: bool,
    /// Every protocol message goes through this lock, so messages from concurrent calls never interleave.
    sink: Arc<Mutex<Option<Box<dyn Write + Send>>>>,
}

impl Output {
    pub fn new(ascii: bool) -> Self {
        Output { ascii, sink: Arc::new(Mutex::new(None)) }
    }

    /// Messages go to [sink] instead of stdout (tests).
    pub fn with_sink(ascii: bool, sink: Box<dyn Write + Send>) -> Self {
        Output { ascii, sink: Arc::new(Mutex::new(Some(sink))) }
    }

    fn encode(&self, text: &str) -> Vec<u8> {
        if self.ascii {
            text.chars().map(|c| if c.is_ascii() { c as u8 } else { b'?' }).collect()
        } else {
            text.as_bytes().to_vec()
        }
    }

    /// One protocol message on stdout, whole. False when the write failed (stdout is broken).
    pub fn message(&self, message: &Value) -> bool {
        // As kotlinx encodes it (numbers normalised). A message kotlinx cannot encode (a non-finite number) is not written.
        let text = match crate::kjson::encode(message) {
            Ok(t) => t,
            Err(e) => {
                self.err(&format!("infantry mcp: could not encode a message: {}", e.lines().next().unwrap_or("")));
                return true;
            }
        };
        let mut bytes = self.encode(&text);
        bytes.push(b'\n');
        let mut sink = self.sink.lock().unwrap_or_else(|p| p.into_inner());
        let written = match sink.as_mut() {
            Some(w) => w.write_all(&bytes).and_then(|_| w.flush()),
            None => {
                let mut out = std::io::stdout().lock();
                out.write_all(&bytes).and_then(|_| out.flush())
            }
        };
        written.is_ok()
    }

    /// One line on stderr.
    pub fn err(&self, line: &str) {
        let mut bytes = self.encode(line);
        bytes.push(b'\n');
        let mut e = std::io::stderr().lock();
        let _ = e.write_all(&bytes);
        let _ = e.flush();
    }
}
