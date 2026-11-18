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
    lock: Arc<Mutex<()>>,
}

impl Output {
    pub fn new(ascii: bool) -> Self {
        Output { ascii, lock: Arc::new(Mutex::new(())) }
    }

    fn encode(&self, text: &str) -> Vec<u8> {
        if self.ascii {
            text.chars().map(|c| if c.is_ascii() { c as u8 } else { b'?' }).collect()
        } else {
            text.as_bytes().to_vec()
        }
    }

    /// One protocol message on stdout.
    pub fn message(&self, message: &Value) {
        // As kotlinx encodes it (numbers normalised). A message kotlinx cannot encode (a non-finite number) is not written.
        let text = match crate::kjson::encode(message) {
            Ok(t) => t,
            Err(e) => {
                self.err(&format!("infantry mcp: could not encode a message: {}", e.lines().next().unwrap_or("")));
                return;
            }
        };
        let mut bytes = self.encode(&text);
        bytes.push(b'\n');
        let _g = self.lock.lock().unwrap_or_else(|p| p.into_inner());
        let mut out = std::io::stdout().lock();
        let _ = out.write_all(&bytes);
        let _ = out.flush();
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
