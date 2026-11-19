// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! IMG-5: chat uploads are disposable. A sweep deletes every file under `uploads/chat/` and `uploads/file/` of every
//! project whose last-modified time ("last used": an upload, a dedupe hit included, touches it) is older than the
//! retention window. `uploads/ticket` is never read. The limits are named settings:
//! `images.chatImageRetentionDays` (default 14, 0 = keep forever) and `images.chatImageSweepHours` (default 6).
use crate::Ask;
use serde_json::{json, Value};
use std::sync::Mutex;

pub const DAYS_KEY: &str = "images.chatImageRetentionDays";
pub const HOURS_KEY: &str = "images.chatImageSweepHours";
pub const DEFAULT_DAYS: i64 = 14;
pub const DEFAULT_HOURS: i64 = 6;
const PURPOSES: [&str; 2] = ["chat", "file"];
const DAY_MS: i64 = 86_400_000;
const HOUR_MS: i64 = 3_600_000;

static LAST: Mutex<Option<i64>> = Mutex::new(None);

fn num(v: Option<String>, default: i64, max: i64) -> i64 {
    v.and_then(|s| s.trim().parse::<i64>().ok()).filter(|n| (0..=max).contains(n)).unwrap_or(default)
}

fn call(ask: Ask, body: Value) -> Option<Value> {
    let r = ask("project_files", &body.to_string(), None).ok()?;
    (r.type_ != "error").then(|| serde_json::from_str(&r.data).ok()).flatten()
}

/// One sweep over every project; answers the (project id, relative path) of the files it deleted.
pub fn sweep(ask: Ask, days: i64, now_ms: i64) -> Vec<(String, String)> {
    if days <= 0 {
        return Vec::new();
    }
    let cutoff = now_ms - days * DAY_MS;
    let Ok(r) = ask("project_list", "", None) else { return Vec::new() };
    let projects: Vec<Value> = serde_json::from_str::<Value>(&r.data).ok().and_then(|v| v.as_array().cloned()).unwrap_or_default();
    let mut gone = Vec::new();
    for id in projects.iter().filter_map(|p| p["id"].as_str()) {
        for purpose in PURPOSES {
            let dir = format!("uploads/{purpose}");
            let Some(l) = call(ask, json!({"op": "list", "projectId": id, "path": dir})) else { continue };
            for f in l["files"].as_array().into_iter().flatten() {
                let (Some(name), Some(m)) = (f["name"].as_str(), f["mtime"].as_i64()) else { continue };
                if m > 0 && m < cutoff && name != ".gitignore" {
                    let path = format!("{dir}/{name}");
                    if call(ask, json!({"op": "delete", "projectId": id, "path": path}))
                        .map(|d| d["deleted"] == true)
                        .unwrap_or(false)
                    {
                        gone.push((id.to_string(), path));
                    }
                }
            }
        }
    }
    gone
}

/// Called on every `clock.tick`; sweeps at the first tick and then every `images.chatImageSweepHours`.
pub fn on_tick(ask: Ask, setting: impl Fn(&str) -> Option<String>) {
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0);
    let every = num(setting(HOURS_KEY), DEFAULT_HOURS, 24 * 365).max(1) * HOUR_MS;
    {
        let mut last = LAST.lock().unwrap_or_else(|e| e.into_inner());
        if last.map(|t| now - t < every).unwrap_or(false) {
            return;
        }
        *last = Some(now);
    }
    sweep(ask, num(setting(DAYS_KEY), DEFAULT_DAYS, 3650), now);
}

#[cfg(test)]
mod tests {
    use super::*;
    use infantry_plugin_sdk::types::Frame;
    use std::cell::RefCell;

    fn fr(t: &str, d: &str) -> Frame {
        Frame { type_: t.into(), data: d.into(), session_id: None, request_id: None }
    }

    #[test]
    fn deletes_only_old_chat_and_file_uploads() {
        let deleted = RefCell::new(Vec::new());
        let ask = |ty: &str, data: &str, _: Option<&str>| -> Result<Frame, String> {
            let d: Value = serde_json::from_str(if data.is_empty() { "null" } else { data }).unwrap();
            Ok(match (ty, d["op"].as_str()) {
                ("project_list", _) => fr("project_list", r#"[{"id":"p1"}]"#),
                (_, Some("list")) => fr("project_files", r#"{"files":[{"name":"old.png","mtime":1000},{"name":"new.png","mtime":9999999999999}]}"#),
                (_, Some("delete")) => {
                    deleted.borrow_mut().push(d["path"].as_str().unwrap().to_string());
                    fr("project_files", r#"{"deleted":true}"#)
                }
                _ => fr("error", "?"),
            })
        };
        let gone = sweep(&ask, 14, 5_000_000_000_000);
        assert_eq!(*deleted.borrow(), vec!["uploads/chat/old.png", "uploads/file/old.png"]);
        assert_eq!(gone.len(), 2);
        assert!(sweep(&ask, 0, 5_000_000_000_000).is_empty());
    }
}
