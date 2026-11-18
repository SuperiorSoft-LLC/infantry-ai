// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry_Notes: the project knowledge (memory notes, append-only decisions, issues) as a WASM component
//! (world `daemon-plugin`) over the adopted `memory_notes`, `decisions`, `issues` and `issue_events` tables.
pub mod api;
pub mod db;
pub mod decisions;
pub mod issues;
pub mod memory;

/// Epoch milliseconds as an ISO-8601 UTC instant (`2026-11-18T10:15:30.123Z`), as `Instant.toString()` prints it.
pub fn iso(ms: i64) -> String {
    let (days, rem) = (ms.div_euclid(86_400_000), ms.rem_euclid(86_400_000));
    // civil-from-days (Howard Hinnant)
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    let (h, mi, s, milli) = (rem / 3_600_000, rem / 60_000 % 60, rem / 1000 % 60, rem % 1000);
    format!("{y:04}-{m:02}-{d:02}T{h:02}:{mi:02}:{s:02}.{milli:03}Z")
}

#[cfg(target_arch = "wasm32")]
mod guest {
    use super::api::{self, ToolCtx};
    use super::db::{Db, Ports, Row, SessionInfo, V};
    use std::cell::RefCell;

    wit_bindgen::generate!({
        path: "../../../crates/infantry-plugin-wit/wit",
        world: "daemon-plugin",
    });

    use infantry::plugin::types::SqlValue;

    struct Notes;

    struct Wit;

    impl Db for Wit {
        fn exec(&self, sql: &str, params: &[V]) -> Result<u64, String> {
            infantry::plugin::storage::execute(sql, &params.iter().map(to_sql).collect::<Vec<_>>()).map_err(|e| format!("storage: {e}"))
        }
        fn query(&self, sql: &str, params: &[V]) -> Result<Vec<Row>, String> {
            let r = infantry::plugin::storage::query(sql, &params.iter().map(to_sql).collect::<Vec<_>>()).map_err(|e| format!("storage: {e}"))?;
            Ok(r.rows.into_iter().map(|vals| Row { cols: r.columns.clone(), vals: vals.into_iter().map(from_sql).collect() }).collect())
        }
    }

    fn to_sql(v: &V) -> SqlValue {
        match v {
            V::Null => SqlValue::Null,
            V::I(n) => SqlValue::Integer(*n),
            V::T(s) => SqlValue::Text(s.clone()),
        }
    }

    fn from_sql(v: SqlValue) -> V {
        match v {
            SqlValue::Integer(n) => V::I(n),
            SqlValue::Text(s) => V::T(s),
            SqlValue::Real(f) => V::T(f.to_string()),
            SqlValue::Null | SqlValue::Blob(_) => V::Null,
        }
    }

    thread_local! {
        static KNOWN: RefCell<Option<SessionInfo>> = const { RefCell::new(None) };
        static COUNTER: RefCell<u64> = const { RefCell::new(0) };
    }

    fn now_ms() -> i64 {
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
    }

    /// Host facts. A session the call itself names (the tool's own session, the frame's active session) is known without a lookup.
    struct Host;

    impl Ports for Host {
        fn now_ms(&self) -> i64 {
            now_ms()
        }
        fn now_iso(&self) -> String {
            super::iso(now_ms())
        }
        fn new_id(&self) -> String {
            use std::hash::{BuildHasher, Hasher};
            let n = COUNTER.with(|c| {
                *c.borrow_mut() += 1;
                *c.borrow()
            });
            let mut h = std::collections::hash_map::RandomState::new().build_hasher();
            h.write_i64(now_ms());
            h.write_u64(n);
            format!("{:08x}", h.finish() as u32)
        }
        fn session(&self, id: &str) -> Option<SessionInfo> {
            if let Some(k) = KNOWN.with(|k| k.borrow().clone()).filter(|k| k.id == id && !k.role.is_empty()) {
                return Some(k);
            }
            // the daemon's own session lookup; absent or refused: fall back to the id the call carries
            let looked = infantry::plugin::frames::request("session_get", &serde_json::json!({ "id": id }).to_string(), Some(id), 2000).ok().and_then(|f| serde_json::from_str::<serde_json::Value>(&f.data).ok()).filter(|v| v["id"].as_str() == Some(id));
            match looked {
                Some(v) => Some(SessionInfo { id: id.into(), agent_id: v["agentId"].as_str().map(String::from), role: v["role"].as_str().unwrap_or("").into() }),
                None => KNOWN.with(|k| k.borrow().clone()).filter(|k| k.id == id),
            }
        }
        fn global_write_roles(&self) -> Vec<String> {
            let raw = infantry::plugin::settings::get("knowledge.globalWriteRoles").or_else(|| infantry::plugin::config::get("knowledge.globalWriteRoles")).unwrap_or_else(|| "pm".into());
            raw.trim().trim_matches('"').split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect()
        }
        fn audit(&self, line: &str) {
            infantry::plugin::log::write(infantry::plugin::log::Level::Info, line);
        }
    }

    impl Guest for Notes {
        fn start() -> Result<(), String> {
            // dropping a session's notes when it is removed; a host without that event simply never calls on_event
            let _ = infantry::plugin::events::subscribe("session.removed");
            Ok(())
        }
        fn stop() {}
        fn call_tool(name: String, args: String, ctx: CallContext) -> Result<String, String> {
            if name != api::TOOL_NAME {
                return Err(format!("unknown tool {name}"));
            }
            let t = ToolCtx { session_id: ctx.client_session_id.clone(), role: ctx.client_role.clone(), project_id: ctx.client_project_id.clone() };
            if let Some(id) = &t.session_id {
                KNOWN.with(|k| *k.borrow_mut() = Some(SessionInfo { id: id.clone(), agent_id: None, role: t.role.clone().unwrap_or_default() }));
            }
            let r = api::call_tool(&Wit, &Host, &args, &t);
            KNOWN.with(|k| *k.borrow_mut() = None);
            r
        }
        fn handle_frame(f: Frame, caller: Caller) -> Result<Option<Frame>, String> {
            if let Some(id) = caller.active_session.clone().or_else(|| f.session_id.clone()) {
                KNOWN.with(|k| *k.borrow_mut() = Some(SessionInfo { id, agent_id: None, role: String::new() }));
            }
            let out = api::handle(&Wit, &Host, &f.type_, &f.data);
            KNOWN.with(|k| *k.borrow_mut() = None);
            let Some(rep) = out else { return Ok(None) };
            for (ty, data) in rep.push {
                infantry::plugin::events::broadcast(&ty, &data);
            }
            Ok(rep.reply.map(|(ty, data)| Frame { type_: ty, data, session_id: f.session_id, request_id: f.request_id }))
        }
        fn handle_route(_r: RouteRequest) -> Result<HttpResponse, String> {
            Err("no routes".into())
        }
        fn on_hook(_k: String, _p: String) -> Result<String, String> {
            Err("no hooks".into())
        }
        fn on_event(_s: u64, e: NamedEvent) {
            if e.name == "session.removed" {
                if let Some(id) = serde_json::from_str::<serde_json::Value>(&e.payload).ok().and_then(|v| v["sessionId"].as_str().map(String::from)) {
                    let _ = super::memory::purge_session(&Wit, &id);
                }
            }
        }
    }

    export!(Notes);
}
