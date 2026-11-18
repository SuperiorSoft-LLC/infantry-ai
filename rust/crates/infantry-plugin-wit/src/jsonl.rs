// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The JSON-lines mapping of the `daemon-plugin` world, for native sidecars (`daemon.sidecar {exe, args}`).
//!
//! One JSON object per line, UTF-8, `\n`-terminated, on the sidecar's stdin (host to sidecar) and stdout (sidecar to
//! host). The sidecar's stderr goes to the daemon log.
//!
//! 1. The sidecar's first line is a hello: `{"hello":{"world":"infantry:plugin/daemon-plugin@0.1.0"}}`.
//! 2. The host calls an export: `{"id":1,"call":"call-tool","params":{"name":..,"args":..,"ctx":{..}}}`.
//! 3. While it runs, the sidecar may call an import: `{"id":7,"import":"frames.request","params":{..}}`; the host
//!    answers `{"id":7,"ok":<value>}` or `{"id":7,"err":"why"}` (a not-granted import is answered with an err).
//! 4. The sidecar answers the export: `{"id":1,"ok":<value>}`, `{"id":1,"err":"why"}` (the export's own
//!    `result` error), or `{"id":1,"trap":"why"}` (the plugin failed; the host disables it as for a WASM trap).
//!
//! A reply line has no `call`/`import` key; the host only ever receives replies to its calls and the sidecar only to
//! its imports, so the two id spaces never mix. Names are the WIT names (kebab-case functions, `<interface>.<func>`
//! for imports); record fields are camelCase; `list<u8>` is base64; `option` is `null` or the value.
use serde::{Deserialize, Serialize};
use serde_json::Value;

/// The `world` a sidecar names in its hello.
pub const HELLO_WORLD: &str = "infantry:plugin/daemon-plugin@0.1.0";

/// One line, either direction.
#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Line {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub id: Option<u64>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub hello: Option<Hello>,
    /// An export call (host to sidecar).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub call: Option<String>,
    /// An import call (sidecar to host), `<interface>.<func>`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub import: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub params: Option<Value>,
    /// A success reply's value (absent = unit / none).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ok: Option<Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub err: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub trap: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Hello {
    pub world: String,
}

impl Line {
    pub fn hello() -> Line {
        Line { hello: Some(Hello { world: HELLO_WORLD.into() }), ..Default::default() }
    }
    pub fn call(id: u64, name: &str, params: Value) -> Line {
        Line { id: Some(id), call: Some(name.into()), params: Some(params), ..Default::default() }
    }
    pub fn import(id: u64, name: &str, params: Value) -> Line {
        Line { id: Some(id), import: Some(name.into()), params: Some(params), ..Default::default() }
    }
    pub fn ok(id: u64, value: Value) -> Line {
        Line { id: Some(id), ok: if value.is_null() { None } else { Some(value) }, ..Default::default() }
    }
    pub fn err(id: u64, why: impl Into<String>) -> Line {
        Line { id: Some(id), err: Some(why.into()), ..Default::default() }
    }
    pub fn trap(id: u64, why: impl Into<String>) -> Line {
        Line { id: Some(id), trap: Some(why.into()), ..Default::default() }
    }
    /// True for a reply (no `call`, `import` or `hello`).
    pub fn is_reply(&self) -> bool {
        self.call.is_none() && self.import.is_none() && self.hello.is_none()
    }
    /// The line as JSON text (no newline).
    pub fn to_text(&self) -> String {
        serde_json::to_string(self).expect("a Line always serializes")
    }
}

/// base64 for `list<u8>`.
pub mod b64 {
    use base64::Engine;
    use serde::{Deserialize, Deserializer, Serializer};
    pub fn serialize<S: Serializer>(v: &[u8], s: S) -> Result<S::Ok, S::Error> {
        s.serialize_str(&base64::engine::general_purpose::STANDARD.encode(v))
    }
    pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Vec<u8>, D::Error> {
        let s = String::deserialize(d)?;
        base64::engine::general_purpose::STANDARD.decode(s).map_err(serde::de::Error::custom)
    }
    pub mod opt {
        use base64::Engine;
        use serde::{Deserialize, Deserializer, Serializer};
        pub fn serialize<S: Serializer>(v: &Option<Vec<u8>>, s: S) -> Result<S::Ok, S::Error> {
            match v {
                Some(b) => s.serialize_str(&base64::engine::general_purpose::STANDARD.encode(b)),
                None => s.serialize_none(),
            }
        }
        pub fn deserialize<'de, D: Deserializer<'de>>(d: D) -> Result<Option<Vec<u8>>, D::Error> {
            match Option::<String>::deserialize(d)? {
                Some(s) => base64::engine::general_purpose::STANDARD.decode(s).map(Some).map_err(serde::de::Error::custom),
                None => Ok(None),
            }
        }
    }
}

// ---- the `types` interface, JSON form ----------------------------------------------------------------------------

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Frame {
    #[serde(rename = "type")]
    pub r#type: String,
    #[serde(default)]
    pub data: String,
    #[serde(default)]
    pub session_id: Option<String>,
    #[serde(default)]
    pub request_id: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Caller {
    #[serde(default)]
    pub owner: bool,
    #[serde(default)]
    pub agent: bool,
    #[serde(default)]
    pub active_session: Option<String>,
    #[serde(default)]
    pub project: Option<String>,
    #[serde(default)]
    pub remote_address: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CallContext {
    #[serde(default)]
    pub caller: Caller,
    #[serde(default)]
    pub client_session_id: Option<String>,
    #[serde(default)]
    pub client_role: Option<String>,
    #[serde(default)]
    pub client_project_id: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NamedEvent {
    pub name: String,
    #[serde(default)]
    pub payload: String,
    #[serde(default)]
    pub source: String,
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub cause_id: String,
    #[serde(default)]
    pub depth: u32,
    #[serde(default)]
    pub at: u64,
}

/// `"null"`, `{"integer":1}`, `{"real":1.5}`, `{"text":"x"}`, `{"blob":"<base64>"}`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SqlValue {
    Null,
    Integer(i64),
    Real(f64),
    Text(String),
    Blob(#[serde(with = "b64")] Vec<u8>),
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct SqlRows {
    pub columns: Vec<String>,
    pub rows: Vec<Vec<SqlValue>>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HttpRequest {
    pub method: String,
    pub url: String,
    #[serde(default)]
    pub headers: Vec<(String, String)>,
    #[serde(default, with = "b64::opt")]
    pub body: Option<Vec<u8>>,
    #[serde(default)]
    pub timeout_ms: Option<u32>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct HttpResponse {
    pub status: u16,
    #[serde(default)]
    pub headers: Vec<(String, String)>,
    #[serde(default, with = "b64")]
    pub body: Vec<u8>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct RouteRequest {
    pub method: String,
    pub path: String,
    #[serde(default)]
    pub query: String,
    #[serde(default)]
    pub headers: Vec<(String, String)>,
    #[serde(default, with = "b64")]
    pub body: Vec<u8>,
}

// ---- export params (host to sidecar `call`) ----------------------------------------------------------------------
// start: no params, ok = absent. stop: no params. on-event: ok = absent.

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct CallToolParams {
    pub name: String,
    /// The JSON object of arguments, as text (as in the WIT).
    pub args: String,
    #[serde(default)]
    pub ctx: CallContext,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct HandleFrameParams {
    pub frame: Frame,
    #[serde(default)]
    pub caller: Caller,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct HandleRouteParams {
    pub request: RouteRequest,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct OnHookParams {
    pub kind: String,
    pub payload: String,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct OnEventParams {
    pub subscription: u64,
    pub event: NamedEvent,
}

// ---- import params (sidecar to host `import`) --------------------------------------------------------------------
// Each import's params are an object named after the WIT parameters (camelCase); the ok value is the WIT result's
// ok type in the JSON form above. `log.write` takes {"level":"info","line":".."}.

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum LogLevel {
    Debug,
    Info,
    Warn,
    Error,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct LogWrite {
    pub level: LogLevel,
    pub line: String,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct KeyParam {
    pub key: String,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct KeyValue {
    pub key: String,
    pub value: String,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct NameParam {
    pub name: String,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct PrefixParam {
    pub prefix: String,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct SqlParams {
    pub sql: String,
    #[serde(default)]
    pub params: Vec<SqlValue>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PublishParams {
    pub name: String,
    #[serde(default)]
    pub payload: String,
    #[serde(default)]
    pub cause_id: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct PatternParam {
    pub pattern: String,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct IdParam {
    pub id: u64,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct BroadcastParams {
    #[serde(rename = "type")]
    pub r#type: String,
    pub data: String,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct HttpSendParams {
    pub request: HttpRequest,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FrameRequestParams {
    #[serde(rename = "type")]
    pub r#type: String,
    #[serde(default)]
    pub data: String,
    #[serde(default)]
    pub session_id: Option<String>,
    pub timeout_ms: u32,
}

/// Every import name in the mapping (`<interface>.<func>`).
pub const IMPORT_NAMES: &[&str] = &[
    "log.write",
    "config.module-id",
    "config.tier",
    "config.api-version",
    "config.environment",
    "config.get",
    "config.endpoint",
    "settings.get",
    "settings.set",
    "settings.remove",
    "storage.kv-get",
    "storage.kv-set",
    "storage.kv-delete",
    "storage.kv-keys",
    "storage.execute",
    "storage.query",
    "events.publish",
    "events.subscribe",
    "events.unsubscribe",
    "events.broadcast",
    "http.send",
    "frames.request",
];

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn lines_round_trip_with_the_documented_spelling() {
        let call = Line::call(1, "call-tool", json!({"name": "x", "args": "{}"}));
        let back: Value = serde_json::from_str(&call.to_text()).unwrap();
        assert_eq!(back, json!({"id": 1, "call": "call-tool", "params": {"name": "x", "args": "{}"}}));
        let ok: Line = serde_json::from_str(r#"{"id":1,"ok":"hi"}"#).unwrap();
        assert!(ok.is_reply());
        assert_eq!(ok.ok, Some(json!("hi")));
        assert_eq!(Line::ok(2, Value::Null).to_text(), r#"{"id":2}"#);
        assert_eq!(Line::hello().to_text(), r#"{"hello":{"world":"infantry:plugin/daemon-plugin@0.1.0"}}"#);
    }

    #[test]
    fn sql_values_and_bytes_use_the_documented_json() {
        let v = vec![SqlValue::Null, SqlValue::Integer(3), SqlValue::Text("a".into()), SqlValue::Blob(vec![1, 2])];
        assert_eq!(serde_json::to_string(&v).unwrap(), r#"["null",{"integer":3},{"text":"a"},{"blob":"AQI="}]"#);
        let f = FrameRequestParams { r#type: "health".into(), data: "{}".into(), session_id: None, timeout_ms: 5 };
        assert_eq!(serde_json::to_string(&f).unwrap(), r#"{"type":"health","data":"{}","sessionId":null,"timeoutMs":5}"#);
    }

    #[test]
    fn the_wit_parses_and_has_the_frozen_exports() {
        let mut resolve = wit_parser::Resolve::default();
        let (pkg, _) = resolve.push_dir(crate::WIT_DIR).expect("the WIT parses");
        let world = resolve.select_world(&[pkg], Some(crate::WORLD)).expect("world daemon-plugin");
        let w = &resolve.worlds[world];
        let mut exports: Vec<String> = w.exports.keys().map(|k| resolve.name_world_key(k)).collect();
        exports.sort();
        let mut want: Vec<String> = crate::EXPORTS.iter().map(|s| s.to_string()).collect();
        want.sort();
        assert_eq!(exports, want);
        let imports: Vec<String> = w.imports.keys().map(|k| resolve.name_world_key(k)).collect();
        for i in crate::BASELINE_IMPORTS.iter().chain(crate::GRANTABLE_IMPORTS) {
            assert!(imports.iter().any(|n| n == &format!("infantry:plugin/{i}@0.1.0")), "import {i} missing: {imports:?}");
        }
    }
}
