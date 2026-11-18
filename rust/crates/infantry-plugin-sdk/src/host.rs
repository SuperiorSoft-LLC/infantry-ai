// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The import facade: one set of functions for both targets. On wasm32 each is the WIT import itself; natively it is
//! the same import sent as a JSON-lines `import` line to the host (sidecar mode). An interface the manifest does
//! not request makes a WASM component fail to load, and a sidecar call fail with an error.
#![allow(clippy::needless_return)]

#[cfg(target_arch = "wasm32")]
use crate::bindings::infantry::plugin as w;

#[cfg(not(target_arch = "wasm32"))]
use crate::sidecar::{import, import_as};
#[cfg(not(target_arch = "wasm32"))]
use infantry_plugin_wit::jsonl;
#[cfg(not(target_arch = "wasm32"))]
use serde_json::json;

pub mod log {
    pub use crate::bindings::infantry::plugin::log::Level;

    pub fn write(level: Level, line: &str) {
        #[cfg(target_arch = "wasm32")]
        super::w::log::write(level, line);
        #[cfg(not(target_arch = "wasm32"))]
        {
            let level = match level {
                Level::Debug => "debug",
                Level::Info => "info",
                Level::Warn => "warn",
                Level::Error => "error",
            };
            let _ = super::import("log.write", super::json!({"level": level, "line": line}));
        }
    }
    pub fn debug(line: &str) {
        write(Level::Debug, line)
    }
    pub fn info(line: &str) {
        write(Level::Info, line)
    }
    pub fn warn(line: &str) {
        write(Level::Warn, line)
    }
    pub fn error(line: &str) {
        write(Level::Error, line)
    }
}

pub mod config {
    macro_rules! getter {
        ($name:ident, $wit:literal) => {
            pub fn $name() -> String {
                #[cfg(target_arch = "wasm32")]
                return super::w::config::$name();
                #[cfg(not(target_arch = "wasm32"))]
                return super::import_as::<String>($wit, super::json!({})).unwrap_or_default();
            }
        };
    }
    getter!(module_id, "config.module-id");
    getter!(tier, "config.tier");
    getter!(api_version, "config.api-version");
    getter!(environment, "config.environment");

    pub fn get(key: &str) -> Option<String> {
        #[cfg(target_arch = "wasm32")]
        return super::w::config::get(key);
        #[cfg(not(target_arch = "wasm32"))]
        return super::import_as::<Option<String>>("config.get", super::json!({"key": key})).ok().flatten();
    }
    pub fn endpoint(name: &str) -> Option<String> {
        #[cfg(target_arch = "wasm32")]
        return super::w::config::endpoint(name);
        #[cfg(not(target_arch = "wasm32"))]
        return super::import_as::<Option<String>>("config.endpoint", super::json!({"name": name})).ok().flatten();
    }
}

pub mod settings {
    pub fn get(key: &str) -> Option<String> {
        #[cfg(target_arch = "wasm32")]
        return super::w::settings::get(key);
        #[cfg(not(target_arch = "wasm32"))]
        return super::import_as::<Option<String>>("settings.get", super::json!({"key": key})).ok().flatten();
    }
    pub fn set(key: &str, value: &str) -> Result<(), String> {
        #[cfg(target_arch = "wasm32")]
        return super::w::settings::set(key, value);
        #[cfg(not(target_arch = "wasm32"))]
        return super::import("settings.set", super::json!({"key": key, "value": value})).map(|_| ());
    }
    pub fn remove(key: &str) -> Result<(), String> {
        #[cfg(target_arch = "wasm32")]
        return super::w::settings::remove(key);
        #[cfg(not(target_arch = "wasm32"))]
        return super::import("settings.remove", super::json!({"key": key})).map(|_| ());
    }
}

pub mod storage {
    pub use crate::types::{SqlRows, SqlValue};

    pub fn kv_get(key: &str) -> Result<Option<String>, String> {
        #[cfg(target_arch = "wasm32")]
        return super::w::storage::kv_get(key);
        #[cfg(not(target_arch = "wasm32"))]
        return super::import_as("storage.kv-get", super::json!({"key": key}));
    }
    pub fn kv_set(key: &str, value: &str) -> Result<(), String> {
        #[cfg(target_arch = "wasm32")]
        return super::w::storage::kv_set(key, value);
        #[cfg(not(target_arch = "wasm32"))]
        return super::import("storage.kv-set", super::json!({"key": key, "value": value})).map(|_| ());
    }
    pub fn kv_delete(key: &str) -> Result<bool, String> {
        #[cfg(target_arch = "wasm32")]
        return super::w::storage::kv_delete(key);
        #[cfg(not(target_arch = "wasm32"))]
        return super::import_as("storage.kv-delete", super::json!({"key": key}));
    }
    pub fn kv_keys(prefix: &str) -> Result<Vec<String>, String> {
        #[cfg(target_arch = "wasm32")]
        return super::w::storage::kv_keys(prefix);
        #[cfg(not(target_arch = "wasm32"))]
        return super::import_as("storage.kv-keys", super::json!({"prefix": prefix}));
    }
    pub fn execute(sql: &str, params: &[SqlValue]) -> Result<u64, String> {
        #[cfg(target_arch = "wasm32")]
        return super::w::storage::execute(sql, params);
        #[cfg(not(target_arch = "wasm32"))]
        return super::import_as("storage.execute", super::json!({"sql": sql, "params": params.iter().map(super::sql_out).collect::<Vec<_>>()}));
    }
    pub fn query(sql: &str, params: &[SqlValue]) -> Result<SqlRows, String> {
        #[cfg(target_arch = "wasm32")]
        return super::w::storage::query(sql, params);
        #[cfg(not(target_arch = "wasm32"))]
        {
            let rows: super::jsonl::SqlRows = super::import_as("storage.query", super::json!({"sql": sql, "params": params.iter().map(super::sql_out).collect::<Vec<_>>()}))?;
            return Ok(SqlRows { columns: rows.columns, rows: rows.rows.into_iter().map(|r| r.into_iter().map(super::sql_in).collect()).collect() });
        }
    }
}

pub mod events {
    pub use crate::types::NamedEvent;

    pub fn publish(name: &str, payload: &str, cause_id: Option<&str>) -> Result<NamedEvent, String> {
        #[cfg(target_arch = "wasm32")]
        return super::w::events::publish(name, payload, cause_id);
        #[cfg(not(target_arch = "wasm32"))]
        return super::import_as::<super::jsonl::NamedEvent>("events.publish", super::json!({"name": name, "payload": payload, "causeId": cause_id})).map(super::event_in);
    }
    pub fn subscribe(pattern: &str) -> Result<u64, String> {
        #[cfg(target_arch = "wasm32")]
        return super::w::events::subscribe(pattern);
        #[cfg(not(target_arch = "wasm32"))]
        return super::import_as("events.subscribe", super::json!({"pattern": pattern}));
    }
    pub fn unsubscribe(id: u64) {
        #[cfg(target_arch = "wasm32")]
        super::w::events::unsubscribe(id);
        #[cfg(not(target_arch = "wasm32"))]
        let _ = super::import("events.unsubscribe", super::json!({"id": id}));
    }
    pub fn broadcast(r#type: &str, data: &str) {
        #[cfg(target_arch = "wasm32")]
        super::w::events::broadcast(r#type, data);
        #[cfg(not(target_arch = "wasm32"))]
        let _ = super::import("events.broadcast", super::json!({"type": r#type, "data": data}));
    }
}

pub mod http {
    pub use crate::types::{HttpRequest, HttpResponse};

    pub fn send(request: &HttpRequest) -> Result<HttpResponse, String> {
        #[cfg(target_arch = "wasm32")]
        return super::w::http::send(request);
        #[cfg(not(target_arch = "wasm32"))]
        {
            let r = super::jsonl::HttpRequest { method: request.method.clone(), url: request.url.clone(), headers: request.headers.clone(), body: request.body.clone(), timeout_ms: request.timeout_ms };
            let resp: super::jsonl::HttpResponse = super::import_as("http.send", super::json!({"request": r}))?;
            return Ok(HttpResponse { status: resp.status, headers: resp.headers, body: resp.body });
        }
    }
}

pub mod frames {
    pub use crate::types::Frame;

    /// A frame into the daemon; its reply frame.
    pub fn request(r#type: &str, data: &str, session_id: Option<&str>, timeout_ms: u32) -> Result<Frame, String> {
        #[cfg(target_arch = "wasm32")]
        return super::w::frames::request(r#type, data, session_id, timeout_ms);
        #[cfg(not(target_arch = "wasm32"))]
        return super::import_as::<super::jsonl::Frame>("frames.request", super::json!({"type": r#type, "data": data, "sessionId": session_id, "timeoutMs": timeout_ms})).map(super::frame_in);
    }
}

// ---- native conversions between the WIT types and their JSON form ----

#[cfg(not(target_arch = "wasm32"))]
pub(crate) fn frame_in(f: jsonl::Frame) -> crate::types::Frame {
    crate::types::Frame { type_: f.r#type, data: f.data, session_id: f.session_id, request_id: f.request_id }
}
#[cfg(not(target_arch = "wasm32"))]
pub(crate) fn frame_out(f: crate::types::Frame) -> jsonl::Frame {
    jsonl::Frame { r#type: f.type_, data: f.data, session_id: f.session_id, request_id: f.request_id }
}
#[cfg(not(target_arch = "wasm32"))]
pub(crate) fn event_in(e: jsonl::NamedEvent) -> crate::types::NamedEvent {
    crate::types::NamedEvent { name: e.name, payload: e.payload, source: e.source, id: e.id, cause_id: e.cause_id, depth: e.depth, at: e.at }
}
#[cfg(not(target_arch = "wasm32"))]
pub(crate) fn sql_out(v: &crate::types::SqlValue) -> jsonl::SqlValue {
    use crate::types::SqlValue as S;
    match v {
        S::Null => jsonl::SqlValue::Null,
        S::Integer(i) => jsonl::SqlValue::Integer(*i),
        S::Real(r) => jsonl::SqlValue::Real(*r),
        S::Text(t) => jsonl::SqlValue::Text(t.clone()),
        S::Blob(b) => jsonl::SqlValue::Blob(b.clone()),
    }
}
#[cfg(not(target_arch = "wasm32"))]
pub(crate) fn sql_in(v: jsonl::SqlValue) -> crate::types::SqlValue {
    use crate::types::SqlValue as S;
    match v {
        jsonl::SqlValue::Null => S::Null,
        jsonl::SqlValue::Integer(i) => S::Integer(i),
        jsonl::SqlValue::Real(r) => S::Real(r),
        jsonl::SqlValue::Text(t) => S::Text(t),
        jsonl::SqlValue::Blob(b) => S::Blob(b),
    }
}
