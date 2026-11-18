// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-MIGRATE-1: which sessions another app is running right now, so one session never runs in two apps.
//!
//! A state dir made by `infantry-migrate` has a `migration.json` naming the dir it was copied from (`source`) and how
//! to name that app to the user (`sourceLabel`, "Infantry (Kotlin)"). While both apps are in use, the Rust daemon
//! re-reads the source's `sessions.json` (read only: one `fs::read`, nothing is opened for writing, locked or created)
//! and every session live there is "held": listed with `heldBy`, and a turn into it is refused.
//!
//! Live = the record's own `live()` (busy, or a child process the app reported alive), and when it names a child pid,
//! that process still exists: a store the other app left behind when it stopped does not hold a session forever.

use infantry_protocol::session::SessionRecord;
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

pub const MANIFEST: &str = "migration.json";
pub const SESSIONS: &str = "sessions.json";
/// How often the source store is re-read (`limits.migrate.heldRefreshMs`).
pub const REFRESH_MS: u64 = 2_000;

/// The other app's state dir and its name, from this state dir's `migration.json`.
#[derive(Debug, Clone, PartialEq)]
pub struct Source {
    pub dir: PathBuf,
    pub label: String,
}

impl Source {
    /// None when this state dir is not a migrated copy.
    pub fn of(state_dir: &Path) -> Option<Source> {
        let v: serde_json::Value = serde_json::from_slice(&std::fs::read(state_dir.join(MANIFEST)).ok()?).ok()?;
        let dir = PathBuf::from(v["source"].as_str()?);
        let label = v["sourceLabel"].as_str().filter(|s| !s.is_empty()).unwrap_or("another app").to_string();
        // A copy pointed back at itself (the cut-over: Rust now runs on the live dir) holds nothing.
        let same = std::fs::canonicalize(&dir).ok().zip(std::fs::canonicalize(state_dir).ok()).is_some_and(|(a, b)| a == b);
        (!same).then_some(Source { dir, label })
    }

    /// Session id -> label for every session live in the source now. An unreadable or missing store holds nothing
    /// (the other app is not running, or is between writes; the next read decides).
    pub fn held(&self) -> Option<BTreeMap<String, String>> {
        let bytes = std::fs::read(self.dir.join(SESSIONS)).ok()?;
        let v: serde_json::Value = serde_json::from_slice(&bytes).ok()?;
        let list = v.get("sessions").and_then(|s| s.as_array()).or_else(|| v.as_array())?;
        let mut out = BTreeMap::new();
        for r in list {
            let Some(id) = r["id"].as_str() else { continue };
            let live = match serde_json::from_value::<SessionRecord>(r.clone()) {
                Ok(rec) => rec.live() && rec.child_pid.is_none_or(pid_alive),
                Err(_) => r["live"].as_bool().unwrap_or(false) && r["childPid"].as_i64().is_none_or(pid_alive),
            };
            if live {
                out.insert(id.to_string(), self.label.clone());
            }
        }
        Some(out)
    }
}

#[cfg(windows)]
fn pid_alive(pid: i64) -> bool {
    use windows_sys::Win32::Foundation::{CloseHandle, STILL_ACTIVE};
    use windows_sys::Win32::System::Threading::{GetExitCodeProcess, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION};
    let Ok(pid) = u32::try_from(pid) else { return false };
    // SAFETY: a query-only handle, closed before return.
    unsafe {
        let h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if h.is_null() {
            // Access denied means it exists (another user's process); anything else, it is gone.
            return windows_sys::Win32::Foundation::GetLastError() == windows_sys::Win32::Foundation::ERROR_ACCESS_DENIED;
        }
        let mut code = 0u32;
        let ok = GetExitCodeProcess(h, &mut code) != 0;
        CloseHandle(h);
        ok && code == STILL_ACTIVE as u32
    }
}

#[cfg(not(windows))]
fn pid_alive(pid: i64) -> bool {
    Path::new(&format!("/proc/{pid}")).exists() || !Path::new("/proc/self").exists()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn holds_only_sessions_live_in_the_source() {
        let root = std::env::temp_dir().join(format!("infantry-held-{}", uuid::Uuid::new_v4()));
        let (src, copy) = (root.join("kotlin"), root.join("rust"));
        std::fs::create_dir_all(&src).unwrap();
        std::fs::create_dir_all(&copy).unwrap();
        assert_eq!(Source::of(&copy), None, "not a migrated copy");
        std::fs::write(copy.join(MANIFEST), serde_json::json!({"source": src, "sourceLabel": "Infantry (Kotlin)"}).to_string()).unwrap();
        let s = Source::of(&copy).unwrap();
        assert_eq!(s.held(), None, "no source store yet");
        let me = std::process::id() as i64;
        let rec = |id: &str, state: &str, pid: Option<i64>| {
            serde_json::json!({"id": id, "provider": "claude", "workingDir": "C:\\w", "spawnedAt": 1, "lastHeartbeat": 1,
                "state": state, "childPid": pid, "isProcessAlive": pid.is_some()})
        };
        let store = serde_json::json!({"version": 1, "sessions": [
            rec("busy", "BUSY", None), rec("alive", "IDLE", Some(me)), rec("gone", "IDLE", Some(i32::MAX as i64 - 7)),
            rec("rest", "IDLE", None), {"id": "odd", "live": true}
        ]});
        std::fs::write(src.join(SESSIONS), store.to_string()).unwrap();
        let held = s.held().unwrap();
        assert_eq!(held.keys().map(String::as_str).collect::<Vec<_>>(), ["alive", "busy", "odd"]);
        assert_eq!(held["busy"], "Infantry (Kotlin)");
        // Pointed at itself, nothing is held.
        std::fs::write(copy.join(MANIFEST), serde_json::json!({"source": copy}).to_string()).unwrap();
        assert_eq!(Source::of(&copy), None);
        let _ = std::fs::remove_dir_all(root);
    }
}
