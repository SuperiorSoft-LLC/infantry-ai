// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The session store: `<stateDir>/sessions.json`, the same file and shape as Kotlin's `JsonSessionStore`
//! (`{"version":1,"sessions":[SessionRecord..]}`, pretty printed). Written to a `.tmp` sibling and moved into place;
//! a move that collides with a reader is retried. A store that exists but cannot be read is never overwritten.

use infantry_protocol::session::SessionRecord;
use serde_json::{json, Value};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

pub const FILE: &str = "sessions.json";
/// How long a replace may retry a sharing violation (a reader holding the file).
pub const REPLACE_BUDGET_MS: u64 = 2_000;

pub struct JsonSessionStore {
    file: PathBuf,
    /// Set when the file was on disk but unreadable: saving would destroy it.
    read_only: bool,
}

impl JsonSessionStore {
    pub fn new(state_dir: &Path) -> Self {
        JsonSessionStore { file: state_dir.join(FILE), read_only: false }
    }

    /// The stored sessions (none when there is no file). An unreadable file is reported and left alone.
    pub fn load(&mut self) -> Result<Vec<SessionRecord>, String> {
        let text = match std::fs::read_to_string(&self.file) {
            Ok(t) => t,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(e) => {
                self.read_only = true;
                return Err(format!("{}: {e}", self.file.display()));
            }
        };
        let parsed = serde_json::from_str::<Value>(&text).map_err(|e| e.to_string()).and_then(|v| {
            let list = v.get("sessions").cloned().unwrap_or(Value::Array(vec![]));
            let arr = list.as_array().cloned().unwrap_or_default();
            // One bad record does not cost the others.
            Ok(arr.into_iter().filter_map(|r| serde_json::from_value::<SessionRecord>(r).ok()).collect::<Vec<_>>())
        });
        parsed.map_err(|e| {
            self.read_only = true;
            format!("{}: {e}", self.file.display())
        })
    }

    pub fn save(&self, sessions: &[SessionRecord]) -> Result<(), String> {
        if self.read_only {
            return Err(format!("{} was unreadable at start; not overwriting it", self.file.display()));
        }
        let body = serde_json::to_string_pretty(&json!({ "version": 1, "sessions": sessions })).map_err(|e| e.to_string())?;
        if let Some(d) = self.file.parent() {
            std::fs::create_dir_all(d).map_err(|e| e.to_string())?;
        }
        let tmp = self.file.with_file_name(format!("{FILE}.tmp"));
        std::fs::write(&tmp, body).map_err(|e| e.to_string())?;
        let start = Instant::now();
        loop {
            // std's rename is MoveFileExW(MOVEFILE_REPLACE_EXISTING) on Windows: one atomic replace.
            match std::fs::rename(&tmp, &self.file) {
                Ok(()) => return Ok(()),
                Err(e) if start.elapsed() < Duration::from_millis(REPLACE_BUDGET_MS) => {
                    let _ = e;
                    std::thread::sleep(Duration::from_millis(10));
                }
                Err(e) => return Err(format!("{}: {e}", self.file.display())),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trips_and_refuses_to_overwrite_an_unreadable_store() {
        let dir = std::env::temp_dir().join(format!("infantry-store-{}", uuid::Uuid::new_v4()));
        let mut s = JsonSessionStore::new(&dir);
        assert!(s.load().unwrap().is_empty());
        let rec: SessionRecord = serde_json::from_value(json!({
            "id": "a", "provider": "stub", "workingDir": "/w", "spawnedAt": 1, "lastHeartbeat": 1, "state": "IDLE"
        }))
        .unwrap();
        s.save(std::slice::from_ref(&rec)).unwrap();
        assert_eq!(JsonSessionStore::new(&dir).load().unwrap(), vec![rec.clone()]);
        std::fs::write(dir.join(FILE), "{not json").unwrap();
        let mut bad = JsonSessionStore::new(&dir);
        assert!(bad.load().is_err());
        assert!(bad.save(&[rec]).is_err());
        let _ = std::fs::remove_dir_all(dir);
    }
}
