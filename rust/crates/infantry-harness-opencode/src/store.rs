// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! One opencode session read from its own store, `<data dir>/opencode/opencode.db` (sqlite, opened READ-ONLY with a busy
//! timeout), as [`StatEvent`]s, and a model's context window from opencode's cached models.dev catalog `models.json`
//! (Kotlin `OpenCodeStats.read`, HARNESS H8b-3, and `SessionStatsService.anyCatalogLimit`). A step's context is input +
//! cache read + cache write; cost is only what opencode reported on its `step-finish` part.

use infantry_harness::stats::{RequestUsage, StatEvent};
use rusqlite::{Connection, OpenFlags};
use serde_json::Value;
use std::path::Path;
use std::time::Duration;

/// How long a read waits for opencode's own writer before it gives up (the record is then unread, never blocked on).
const BUSY_TIMEOUT: Duration = Duration::from_millis(2_000);

/// The session's events, or None when there is no db, no such session, or the db cannot be read now.
pub fn read(db: &Path, psid: &str) -> Option<Vec<StatEvent>> {
    if !db.is_file() {
        return None;
    }
    let conn = Connection::open_with_flags(db, OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX).ok()?;
    conn.busy_timeout(BUSY_TIMEOUT).ok()?;
    read_conn(&conn, psid)
}

fn read_conn(conn: &Connection, psid: &str) -> Option<Vec<StatEvent>> {
    let (a, b): (i64, i64) = conn.query_row("SELECT time_created, time_updated FROM session WHERE id = ?1", [psid], |r| Ok((r.get(0)?, r.get(1)?))).ok()?;
    let mut events = vec![StatEvent::Seen(Some(a.min(b))), StatEvent::Seen(Some(a.max(b)))];
    let rows = |sql: &str| -> Option<Vec<(Option<i64>, String)>> {
        let mut st = conn.prepare(sql).ok()?;
        let it = st.query_map([psid], |r| Ok((r.get::<_, Option<i64>>(0)?, r.get::<_, Option<String>>(1)?.unwrap_or_default()))).ok()?;
        Some(it.flatten().collect())
    };
    let mut model: Option<String> = None;
    for (_, data) in rows("SELECT time_created, data FROM message WHERE session_id = ?1 ORDER BY time_created, id")? {
        let Ok(m) = serde_json::from_str::<Value>(&data) else { continue };
        match m.get("role").and_then(Value::as_str) {
            Some("user") => events.push(StatEvent::Prompt),
            Some("assistant") => {
                let id = m.get("modelID").and_then(Value::as_str).map(String::from);
                if let Some(i) = id.as_deref().filter(|i| !i.trim().is_empty()) {
                    model = Some(i.to_string());
                }
                events.push(StatEvent::Turn(id));
            }
            _ => {}
        }
    }
    let mut step_tools: Vec<String> = Vec::new();
    for (at, data) in rows("SELECT time_created, data FROM part WHERE session_id = ?1 ORDER BY time_created, id")? {
        let Ok(p) = serde_json::from_str::<Value>(&data) else { continue };
        match p.get("type").and_then(Value::as_str) {
            Some("tool") => step_tools.push(p.get("tool").and_then(Value::as_str).unwrap_or("unknown").to_string()),
            Some("step-finish") => {
                let Some(t) = p.get("tokens").filter(|t| t.is_object()) else { continue };
                let n = |v: Option<&Value>| v.and_then(Value::as_u64).unwrap_or(0);
                let cache = t.get("cache");
                let req = RequestUsage {
                    at,
                    model: model.clone(),
                    input: n(t.get("input")),
                    output: n(t.get("output")),
                    cache_read: n(cache.and_then(|c| c.get("read"))),
                    cache_write: n(cache.and_then(|c| c.get("write"))),
                    cost: p.get("cost").and_then(Value::as_f64),
                };
                if req.is_empty() {
                    continue; // an all-zero request is not a request
                }
                events.push(StatEvent::Request(req));
                // The step's tools follow its request, so the NEXT request measures what they added.
                events.extend(step_tools.drain(..).map(|name| StatEvent::Tool { name, at }));
            }
            _ => {}
        }
    }
    events.extend(step_tools.into_iter().map(|name| StatEvent::Tool { name, at: None }));
    Some(events)
}

/// `limit.context` of `model` in models.dev's `models.json` (`{provider: {models: {id: {limit: {context}}}}}`): under its
/// provider when `model` is `provider/id`, else the first provider that lists the id.
pub fn context_window(text: &str, model: &str) -> Option<u64> {
    let root: Value = serde_json::from_str(text).ok()?;
    let root = root.as_object()?;
    let in_provider = |p: &Value, id: &str| p.get("models")?.get(id)?.get("limit")?.get("context")?.as_u64();
    if let Some((provider, id)) = model.split_once('/') {
        if let Some(n) = root.get(provider).and_then(|p| in_provider(p, id)) {
            return Some(n);
        }
    }
    root.values().find_map(|p| in_provider(p, model))
}

#[cfg(test)]
mod tests {
    use super::*;
    use infantry_harness::stats::fold;

    #[test]
    fn an_opencode_store_folds_steps_with_reported_cost() {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch(
            r#"CREATE TABLE session(id TEXT, time_created INTEGER, time_updated INTEGER);
               CREATE TABLE message(id TEXT, session_id TEXT, time_created INTEGER, data TEXT);
               CREATE TABLE part(id TEXT, session_id TEXT, time_created INTEGER, data TEXT);
               INSERT INTO session VALUES('ses_1', 1000, 9000);
               INSERT INTO message VALUES('a','ses_1',1000,'{"role":"user"}');
               INSERT INTO message VALUES('b','ses_1',1100,'{"role":"assistant","modelID":"glm-4.6"}');
               INSERT INTO part VALUES('p1','ses_1',1200,'{"type":"tool","tool":"read"}');
               INSERT INTO part VALUES('p2','ses_1',1300,'{"type":"step-finish","tokens":{"input":100,"output":10,"cache":{"read":0,"write":0}},"cost":0.5}');
               INSERT INTO part VALUES('p3','ses_1',1400,'{"type":"step-finish","tokens":{"input":20,"output":5,"cache":{"read":130,"write":0}},"cost":0.25}');"#,
        )
        .unwrap();
        let s = fold(&read_conn(&c, "ses_1").unwrap());
        assert_eq!((s.started_at, s.ended_at, s.user_prompts, s.assistant_turns, s.tool_uses), (Some(1000), Some(9000), 1, 1, 1));
        assert_eq!(s.billed_tokens, 110 + 155);
        assert_eq!(s.top_sources, vec![("read".to_string(), 1, Some(50))]);
        assert_eq!(s.cost_total, Some(0.75));
        assert!(read_conn(&c, "nope").is_none());
        let cat = r#"{"zai":{"models":{"glm-4.6":{"limit":{"context":200000}}}}}"#;
        assert_eq!(context_window(cat, "glm-4.6"), Some(200000));
        assert_eq!(context_window(cat, "zai/glm-4.6"), Some(200000));
    }
}
