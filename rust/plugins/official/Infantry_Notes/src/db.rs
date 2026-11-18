// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The SQL seam: the guest backs it with the WIT `storage` interface, the tests with rusqlite.
//! Every store rule lives above this trait; it only runs a statement.
use serde_json::Value;

/// One SQL value.
#[derive(Debug, Clone, PartialEq)]
pub enum V {
    Null,
    I(i64),
    T(String),
}

impl From<&str> for V {
    fn from(s: &str) -> V {
        V::T(s.to_string())
    }
}
impl From<String> for V {
    fn from(s: String) -> V {
        V::T(s)
    }
}
impl From<i64> for V {
    fn from(n: i64) -> V {
        V::I(n)
    }
}
impl From<Option<String>> for V {
    fn from(s: Option<String>) -> V {
        s.map(V::T).unwrap_or(V::Null)
    }
}

/// A result row, columns by name.
#[derive(Debug, Clone, Default)]
pub struct Row {
    pub cols: Vec<String>,
    pub vals: Vec<V>,
}

impl Row {
    fn at(&self, k: &str) -> Option<&V> {
        self.cols.iter().position(|c| c == k).map(|i| &self.vals[i])
    }
    pub fn i(&self, k: &str) -> i64 {
        match self.at(k) {
            Some(V::I(n)) => *n,
            Some(V::T(s)) => s.parse().unwrap_or(0),
            _ => 0,
        }
    }
    pub fn s(&self, k: &str) -> String {
        match self.at(k) {
            Some(V::T(s)) => s.clone(),
            Some(V::I(n)) => n.to_string(),
            _ => String::new(),
        }
    }
    pub fn opt(&self, k: &str) -> Option<String> {
        match self.at(k) {
            Some(V::T(s)) => Some(s.clone()),
            _ => None,
        }
    }
    pub fn opt_i(&self, k: &str) -> Option<i64> {
        match self.at(k) {
            Some(V::I(n)) => Some(*n),
            _ => None,
        }
    }
}

pub trait Db {
    fn exec(&self, sql: &str, params: &[V]) -> Result<u64, String>;
    fn query(&self, sql: &str, params: &[V]) -> Result<Vec<Row>, String>;
}

/// The host facts the stores need.
pub trait Ports {
    fn now_ms(&self) -> i64;
    fn now_iso(&self) -> String;
    /// A fresh 8-character id suffix.
    fn new_id(&self) -> String;
    /// The declared session, or None when the daemon does not know it.
    fn session(&self, id: &str) -> Option<SessionInfo>;
    /// Roles allowed to write global notes (setting `knowledge.globalWriteRoles`).
    fn global_write_roles(&self) -> Vec<String>;
    fn audit(&self, line: &str);
}

#[derive(Debug, Clone, Default)]
pub struct SessionInfo {
    pub id: String,
    pub agent_id: Option<String>,
    pub role: String,
}

/// A refusal with a stable code (`<code>: <detail>`); a plain shape error has no code.
pub fn refuse(code: &str, detail: impl AsRef<str>) -> String {
    format!("{code}: {}", detail.as_ref())
}

pub fn opt_str(v: &Value, k: &str) -> Option<String> {
    match v.get(k) {
        Some(Value::String(s)) => Some(s.clone()),
        _ => None,
    }
}

pub fn req_str(v: &Value, k: &str) -> Result<String, String> {
    opt_str(v, k).ok_or_else(|| format!("missing field '{k}'"))
}

pub fn opt_bool(v: &Value, k: &str) -> bool {
    v.get(k).and_then(|b| b.as_bool()).unwrap_or(false)
}

pub fn opt_trim(v: &Value, k: &str) -> Option<String> {
    opt_str(v, k).map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
}
