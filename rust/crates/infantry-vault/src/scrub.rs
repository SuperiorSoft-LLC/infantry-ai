// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The output scrubber: every known secret value masked as `••••` in text the daemon shows or keeps (session output,
//! chat rows, transcripts, a terminal's screen, a tool result).
//!
//! - [`SecretMask`]: over a live value source (the keystore's [`crate::Keystore::all_values`], plus anything else held
//!   outside it via [`union`]). `mask` for whole text, `stream` for chunks that can split a value. The daemon applies it
//!   to every stream; cheap to call, the targets are rebuilt only when the source's `Arc` changes.
//! - [`ByteScrubber`]: a fixed value set over a byte stream (one run's own environment secrets).
//!
//! Values shorter than [`MIN_SECRET_LENGTH`] are left alone (masking "1" or "yes" would wreck output and hide nothing).
//! A value's JSON-escaped form is masked too, since a CLI's stream-json line carries it escaped.
use std::collections::BTreeSet;
use std::sync::{Arc, Mutex};

pub const MIN_SECRET_LENGTH: usize = 4;
pub const MASK: &str = "••••";

pub type ValuesFn = Arc<dyn Fn() -> Arc<Vec<String>> + Send + Sync>;

pub fn json_escaped(s: &str) -> String {
    let mut b = String::with_capacity(s.len() + 8);
    for c in s.chars() {
        match c {
            '"' => b.push_str("\\\""),
            '\\' => b.push_str("\\\\"),
            '\n' => b.push_str("\\n"),
            '\r' => b.push_str("\\r"),
            '\t' => b.push_str("\\t"),
            c if (c as u32) < 0x20 => b.push_str(&format!("\\u{:04x}", c as u32)),
            c => b.push(c),
        }
    }
    b
}

struct Targets {
    source: Option<Arc<Vec<String>>>,
    list: Arc<Vec<String>>,
}

#[derive(Clone)]
pub struct SecretMask {
    values: ValuesFn,
    targets: Arc<Mutex<Targets>>,
}

impl std::fmt::Debug for SecretMask {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("SecretMask")
    }
}

impl SecretMask {
    pub fn new(values: ValuesFn) -> Self {
        SecretMask { values, targets: Arc::new(Mutex::new(Targets { source: None, list: Arc::new(Vec::new()) })) }
    }

    /// The mask a daemon with no keystore uses.
    pub fn none() -> Self {
        let empty = Arc::new(Vec::new());
        Self::new(Arc::new(move || empty.clone()))
    }

    /// Over a keystore's values.
    pub fn of(keystore: Arc<crate::Keystore>) -> Self {
        Self::new(Arc::new(move || keystore.all_values()))
    }

    fn current(&self) -> Arc<Vec<String>> {
        let source = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| (self.values)())).unwrap_or_else(|_| Arc::new(Vec::new()));
        let mut t = self.targets.lock().unwrap_or_else(|p| p.into_inner());
        if t.source.as_ref().is_some_and(|s| Arc::ptr_eq(s, &source)) {
            return t.list.clone();
        }
        let set: BTreeSet<String> = source.iter().filter(|v| v.chars().count() >= MIN_SECRET_LENGTH).flat_map(|v| [v.clone(), json_escaped(v)]).collect();
        let mut list: Vec<String> = set.into_iter().collect();
        list.sort_by_key(|s| std::cmp::Reverse(s.len()));
        t.source = Some(source);
        t.list = Arc::new(list);
        t.list.clone()
    }

    /// True when at least one value is long enough to be masked.
    pub fn active(&self) -> bool {
        !self.current().is_empty()
    }

    /// `text` with every known value replaced by [`MASK`].
    pub fn mask(&self, text: &str) -> String {
        if text.is_empty() {
            return String::new();
        }
        mask_with(&self.current(), text)
    }

    /// A masker for one stream of chunks (not shared between streams).
    pub fn stream(&self) -> MaskStream {
        MaskStream { mask: self.clone(), held: String::new() }
    }
}

fn mask_with(list: &[String], text: &str) -> String {
    let mut out = text.to_string();
    for t in list {
        if out.contains(t.as_str()) {
            out = out.replace(t.as_str(), MASK);
        }
    }
    out
}

/// Byte length of the longest proper prefix of `t` that `data` ends with (on char boundaries).
fn held_prefix(data: &str, t: &str) -> usize {
    let max = (t.len().saturating_sub(1)).min(data.len());
    (1..=max).rev().find(|&n| t.is_char_boundary(n) && data.as_bytes().ends_with(&t.as_bytes()[..n])).unwrap_or(0)
}

/// Holds back the end of a chunk that could begin a value until the next chunk (or [`MaskStream::flush`]) decides.
pub struct MaskStream {
    mask: SecretMask,
    held: String,
}

impl MaskStream {
    pub fn chunk(&mut self, text: &str) -> String {
        let list = self.mask.current();
        let joined = std::mem::take(&mut self.held) + text;
        if list.is_empty() {
            return joined;
        }
        let data = mask_with(&list, &joined);
        let keep = list.iter().map(|t| held_prefix(&data, t)).max().unwrap_or(0);
        let cut = data.len() - keep;
        self.held = data[cut..].to_string();
        data[..cut].to_string()
    }

    /// What is still held back: the stream ended, so it cannot become a value any more.
    pub fn flush(&mut self) -> String {
        std::mem::take(&mut self.held)
    }
}

/// One value source over several (the keystore plus values held outside it); the result keeps its identity while the
/// union is unchanged, so a mask's target cache holds.
pub fn union(parts: Vec<ValuesFn>) -> ValuesFn {
    let last: Arc<Mutex<Arc<Vec<String>>>> = Arc::new(Mutex::new(Arc::new(Vec::new())));
    Arc::new(move || {
        let now: BTreeSet<String> = parts.iter().flat_map(|p| p().iter().cloned().collect::<Vec<_>>()).collect();
        let now: Vec<String> = now.into_iter().collect();
        let mut l = last.lock().unwrap_or_else(|p| p.into_inner());
        if **l != now {
            *l = Arc::new(now);
        }
        l.clone()
    })
}

/// A fixed value set over a byte stream: a value split across two chunks is still masked.
pub struct ByteScrubber {
    targets: Vec<Vec<u8>>,
    held: Vec<u8>,
}

impl ByteScrubber {
    pub fn new<S: AsRef<str>>(values: &[S]) -> Self {
        let set: BTreeSet<Vec<u8>> = values.iter().map(|v| v.as_ref()).filter(|v| v.chars().count() >= MIN_SECRET_LENGTH).map(|v| v.as_bytes().to_vec()).collect();
        let mut targets: Vec<Vec<u8>> = set.into_iter().collect();
        targets.sort_by_key(|t| std::cmp::Reverse(t.len()));
        ByteScrubber { targets, held: Vec::new() }
    }

    pub fn active(&self) -> bool {
        !self.targets.is_empty()
    }

    /// One whole line.
    pub fn line(&self, text: &str) -> String {
        let s: Vec<String> = self.targets.iter().map(|t| String::from_utf8_lossy(t).into_owned()).collect();
        mask_with(&s, text)
    }

    /// The chunk with every value masked; bytes that may begin a value are kept for the next call.
    pub fn bytes(&mut self, chunk: &[u8]) -> Vec<u8> {
        if !self.active() {
            return chunk.to_vec();
        }
        let mut data = std::mem::take(&mut self.held);
        data.extend_from_slice(chunk);
        let mut out = Vec::with_capacity(data.len());
        let mut i = 0;
        'outer: while i < data.len() {
            for t in &self.targets {
                if data[i..].starts_with(t) {
                    out.extend_from_slice(MASK.as_bytes());
                    i += t.len();
                    continue 'outer;
                }
            }
            let rest = &data[i..];
            if self.targets.iter().any(|t| rest.len() < t.len() && t.starts_with(rest)) {
                break;
            }
            out.push(data[i]);
            i += 1;
        }
        self.held = data[i..].to_vec();
        out
    }

    pub fn flush(&mut self) -> Vec<u8> {
        std::mem::take(&mut self.held)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mask_stream_and_bytes_hide_split_values() {
        let vals = Arc::new(vec!["fake-secret-1".to_string(), "ab".to_string(), "q\"uote".to_string()]);
        let m = SecretMask::new(Arc::new(move || vals.clone()));
        assert_eq!(m.mask("x fake-secret-1 ab {\"v\":\"q\\\"uote\"}"), "x •••• ab {\"v\":\"••••\"}");
        let mut s = m.stream();
        let a = s.chunk("hello fake-se");
        let b = s.chunk("cret-1 done fake");
        assert_eq!(a, "hello ");
        assert_eq!(format!("{a}{b}{}", s.flush()), "hello •••• done fake");
        let mut bs = ByteScrubber::new(&["fake-secret-1"]);
        let mut out = bs.bytes(b"a fake-sec");
        out.extend(bs.bytes(b"ret-1 b"));
        out.extend(bs.flush());
        assert_eq!(String::from_utf8(out).unwrap(), "a •••• b");
        assert!(!SecretMask::none().active());
    }
}
