// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RR-8: small persisted sequence marks, one JSON file per owner, so a restart on either end of the link keeps the stream
//! sequence going.
//!
//! - the RUNNER keeps, per linked process, the high-water mark of the frames it pushed (and the exit code once it ended), so
//!   after a restart its frames continue ABOVE whatever cursor the orchestrator holds;
//! - the ORCHESTRATOR keeps, per `(runner, process)`, the cursor it has consumed up to, so it can re-attach with
//!   `frames_pull` from there.
//!
//! An unreadable file is set aside as `<file>.unreadable` ([`crate::store::read_or_set_aside`]), never silently reset.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

/// The high-water mark is written every this many frames (and at every start and exit), so a crash loses at most this many.
pub const CHECKPOINT_EVERY: i64 = 256;
/// After a restart a session's sequence resumes at its persisted mark plus this gap, which is larger than
/// [`CHECKPOINT_EVERY`]: whatever the orchestrator already took, the new frames are above it.
pub const RESTART_GAP: i64 = 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct Mark {
    /// The highest frame sequence known (runner) or consumed (orchestrator).
    pub seq: i64,
    /// The exit code, once the process ended.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub exited: Option<i32>,
}

pub struct MarkFile {
    file: PathBuf,
    marks: BTreeMap<String, Mark>,
}

impl MarkFile {
    /// `<dir>/<name>`; an existing readable file is loaded.
    pub fn open(dir: &Path, name: &str) -> Self {
        let file = dir.join(name);
        let marks = crate::store::read_or_set_aside(&file).unwrap_or_default();
        MarkFile { file, marks }
    }

    pub fn get(&self, key: &str) -> Option<Mark> {
        self.marks.get(key).copied()
    }

    pub fn all(&self) -> Vec<(String, Mark)> {
        self.marks.iter().map(|(k, v)| (k.clone(), *v)).collect()
    }

    pub fn set(&mut self, key: &str, mark: Mark) {
        if self.marks.get(key) != Some(&mark) {
            self.marks.insert(key.to_string(), mark);
            self.save();
        }
    }

    pub fn remove(&mut self, key: &str) {
        if self.marks.remove(key).is_some() {
            self.save();
        }
    }

    fn save(&self) {
        if let Some(dir) = self.file.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        let tmp = self.file.with_extension("tmp");
        if std::fs::write(&tmp, serde_json::to_string_pretty(&self.marks).expect("marks")).is_ok() {
            let _ = std::fs::rename(&tmp, &self.file);
        }
    }
}

/// The orchestrator's cursors, `(runner, process)` -> the sequence consumed so far, persisted in `<dir>/cursors.json`. A
/// stale cursor is safe (a re-attach replays a little), so a write is throttled to [`CURSOR_WRITE_EVERY`] unless the process
/// ended, which is written at once.
pub struct Cursors {
    file: std::sync::Mutex<(MarkFile, Option<std::time::Instant>)>,
}

pub const CURSOR_WRITE_EVERY: std::time::Duration = std::time::Duration::from_millis(500);

impl Cursors {
    pub fn open(dir: &Path) -> Self {
        Cursors { file: std::sync::Mutex::new((MarkFile::open(dir, "cursors.json"), None)) }
    }

    fn key(runner: &str, sid: &str) -> String {
        format!("{runner}/{sid}")
    }

    pub fn get(&self, runner: &str, sid: &str) -> Option<Mark> {
        self.file.lock().unwrap().0.get(&Self::key(runner, sid))
    }

    /// Every persisted cursor of `runner`: `(process id, mark)`.
    pub fn of_runner(&self, runner: &str) -> Vec<(String, Mark)> {
        let prefix = format!("{runner}/");
        self.file.lock().unwrap().0.all().into_iter().filter_map(|(k, m)| k.strip_prefix(&prefix).map(|s| (s.to_string(), m))).collect()
    }

    pub fn advance(&self, runner: &str, sid: &str, seq: i64, exited: Option<i32>) {
        let mut g = self.file.lock().unwrap();
        let due = exited.is_some() || g.1.is_none_or(|t| t.elapsed() >= CURSOR_WRITE_EVERY);
        if due {
            g.0.set(&Self::key(runner, sid), Mark { seq, exited });
            g.1 = Some(std::time::Instant::now());
        }
    }

    pub fn forget(&self, runner: &str, sid: &str) {
        self.file.lock().unwrap().0.remove(&Self::key(runner, sid));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn marks_persist_and_an_unreadable_file_is_set_aside() {
        let dir = std::env::temp_dir().join(format!("marks-{}", uuid::Uuid::new_v4()));
        let mut m = MarkFile::open(&dir, "m.json");
        m.set("p1", Mark { seq: 7, exited: None });
        m.set("p2", Mark { seq: 9, exited: Some(3) });
        let m = MarkFile::open(&dir, "m.json");
        assert_eq!(m.get("p1"), Some(Mark { seq: 7, exited: None }));
        assert_eq!(m.get("p2").unwrap().exited, Some(3));
        std::fs::write(dir.join("m.json"), "garbage").unwrap();
        assert!(MarkFile::open(&dir, "m.json").all().is_empty());
        assert!(dir.join("m.json.unreadable").exists());
        std::fs::remove_dir_all(dir).unwrap();
    }
}
