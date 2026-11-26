// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Durable storage for the mailbox: `mailbox.json` (`{"version":1,"messages":[...]}`), the same file the Kotlin
//! daemon writes, so the migrated copy reads as is.
//!
//! A write goes to a `.tmp` sibling and is renamed over the store. On Windows a rename over a file another handle
//! holds open is refused transiently, so the rename is retried with a short backoff before the plain copy fallback.
//! A read never answers "no mail" for a mailbox that has some: an unreadable store (a replacement in flight) is
//! looked at again, and the `.tmp` replacement is served when it is the only readable copy.
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::thread::sleep;
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};

use super::message::Message;

/// Persistence seam: the mailbox holds the whole set in memory and saves snapshots.
pub trait MailStore: Send + Sync {
    fn load(&self) -> Vec<Message>;
    fn save_all(&self, messages: &[Message]) -> io::Result<()>;
}

/// An in-memory store for tests and for a daemon that runs without a state dir.
#[derive(Default)]
pub struct MemoryMailStore(Mutex<Vec<Message>>);

impl MailStore for MemoryMailStore {
    fn load(&self) -> Vec<Message> {
        self.0.lock().unwrap().clone()
    }
    fn save_all(&self, messages: &[Message]) -> io::Result<()> {
        *self.0.lock().unwrap() = messages.to_vec();
        Ok(())
    }
}

#[derive(Serialize)]
struct SnapshotOut<'a> {
    version: u32,
    messages: &'a [Message],
}

#[derive(Deserialize)]
struct SnapshotIn {
    #[serde(default)]
    messages: Vec<serde_json::Value>,
}

/// Look budgets (ms). Not a correctness device: the correctness is in looking again.
const ABSENT_LOOK_MS: u64 = 25;
const UNREADABLE_LOOK_MS: u64 = 250;
/// How long a refused rename is retried before the non-atomic fallback.
const REPLACE_BUDGET_MS: u64 = 2_000;
const REPLACE_MAX_BACKOFF_MS: u64 = 4;

pub struct JsonMailStore {
    file: PathBuf,
    tmp: PathBuf,
    lock: Mutex<()>,
}

impl JsonMailStore {
    pub fn new(file: impl Into<PathBuf>) -> Self {
        let file = file.into();
        let mut tmp = file.clone().into_os_string();
        tmp.push(".tmp");
        JsonMailStore { file, tmp: PathBuf::from(tmp), lock: Mutex::new(()) }
    }

    /// `mailbox.json` inside the state dir.
    pub fn in_state_dir(dir: &Path) -> Self {
        Self::new(dir.join("mailbox.json"))
    }

    pub fn path(&self) -> &Path {
        &self.file
    }

    fn read(path: &Path) -> Result<Option<Vec<Message>>, String> {
        if !path.is_file() {
            return Ok(None);
        }
        let text = fs::read_to_string(path).map_err(|e| e.to_string())?;
        let snap: SnapshotIn = serde_json::from_str(&text).map_err(|e| e.to_string())?;
        // One bad row never drops the rest.
        Ok(Some(snap.messages.into_iter().filter_map(|v| serde_json::from_value(v).ok()).collect()))
    }

    fn replace_into_place(&self) -> io::Result<()> {
        let started = Instant::now();
        let mut backoff = 1u64;
        loop {
            match fs::rename(&self.tmp, &self.file) {
                Ok(()) => return Ok(()),
                Err(_) if (started.elapsed().as_millis() as u64) < REPLACE_BUDGET_MS => {
                    sleep(Duration::from_millis(backoff));
                    backoff = (backoff * 2).min(REPLACE_MAX_BACKOFF_MS);
                }
                Err(_) => break,
            }
        }
        // Best effort on a filesystem that will not rename over: copy, then drop the tmp.
        fs::copy(&self.tmp, &self.file).map(|_| ()).and_then(|_| fs::remove_file(&self.tmp)).map_err(|e| {
            io::Error::new(e.kind(), format!("could not replace {} after {}ms: {e}", self.file.display(), started.elapsed().as_millis()))
        })
    }
}

impl MailStore for JsonMailStore {
    fn load(&self) -> Vec<Message> {
        let _g = self.lock.lock().unwrap();
        let budget = if self.file.is_file() || self.tmp.is_file() { UNREADABLE_LOOK_MS } else { ABSENT_LOOK_MS };
        let deadline = Instant::now() + Duration::from_millis(budget);
        let mut failure = None;
        loop {
            for p in [&self.file, &self.tmp] {
                match Self::read(p) {
                    Ok(Some(m)) => return m,
                    Ok(None) => {}
                    Err(e) => failure = Some(e),
                }
            }
            if Instant::now() >= deadline {
                break;
            }
            sleep(Duration::from_millis(1));
        }
        if let Some(e) = failure {
            eprintln!("[infantry] could not read mail store {}: {e}", self.file.display());
        }
        Vec::new()
    }

    fn save_all(&self, messages: &[Message]) -> io::Result<()> {
        let _g = self.lock.lock().unwrap();
        let mut sorted: Vec<&Message> = messages.iter().collect();
        sorted.sort_by(|a, b| a.thread_id.cmp(&b.thread_id).then(a.seq.cmp(&b.seq)));
        let owned: Vec<Message> = sorted.into_iter().cloned().collect();
        let mut buf = Vec::new();
        let fmt = serde_json::ser::PrettyFormatter::with_indent(b"    ");
        let mut ser = serde_json::Serializer::with_formatter(&mut buf, fmt);
        SnapshotOut { version: 1, messages: &owned }.serialize(&mut ser).map_err(io::Error::other)?;
        if let Some(parent) = self.file.parent() {
            fs::create_dir_all(parent)?;
        }
        fs::write(&self.tmp, &buf)?;
        self.replace_into_place()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mail::message::MessageState;

    fn msg(id: &str, thread: &str, seq: i64) -> Message {
        Message {
            id: id.into(),
            thread_id: thread.into(),
            seq,
            from: "a".into(),
            to: "b".into(),
            subject: String::new(),
            body: "x".into(),
            sent_at: 1,
            state: MessageState::Queued,
            reply_to: None,
            checkpoint: None,
            needs_input: false,
            delivered_at: None,
            read_at: None,
            answered_at: None,
            failure_reason: None,
            archived_at: None,
        }
    }

    #[test]
    fn saves_and_loads_and_serves_the_tmp_when_alone() {
        let dir = tempfile::tempdir().unwrap();
        let store = JsonMailStore::in_state_dir(dir.path());
        assert!(store.load().is_empty());
        store.save_all(&[msg("2", "t", 2), msg("1", "t", 1)]).unwrap();
        let loaded = store.load();
        assert_eq!(loaded.iter().map(|m| m.id.as_str()).collect::<Vec<_>>(), vec!["1", "2"]);
        let text = fs::read_to_string(store.path()).unwrap();
        assert!(text.starts_with("{\n    \"version\": 1,"));
        // A replacement interrupted after the old file went away: the tmp is the only copy and is served.
        fs::rename(store.path(), dir.path().join("mailbox.json.tmp")).unwrap();
        assert_eq!(store.load().len(), 2);
    }
}
