// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Read-only settings view. Writers live in the composition root; units only read.
use std::collections::BTreeMap;

pub trait Settings: Send + Sync {
    /// The raw JSON value for `key`, if set.
    fn get(&self, key: &str) -> Option<serde_json::Value>;
    /// Every key that has a value, sorted.
    fn keys(&self) -> Vec<String>;

    fn get_str(&self, key: &str) -> Option<String> {
        self.get(key).and_then(|v| v.as_str().map(str::to_owned))
    }
    fn get_bool(&self, key: &str) -> Option<bool> {
        self.get(key).and_then(|v| v.as_bool())
    }
    fn get_i64(&self, key: &str) -> Option<i64> {
        self.get(key).and_then(|v| v.as_i64())
    }
}

/// An immutable in-memory [`Settings`], for tests and fixed defaults.
#[derive(Debug, Clone, Default)]
pub struct MemorySettings {
    values: BTreeMap<String, serde_json::Value>,
}

impl MemorySettings {
    pub fn new() -> Self {
        Self::default()
    }
    /// Builder: returns a copy with `key` set.
    pub fn with(mut self, key: impl Into<String>, value: serde_json::Value) -> Self {
        self.values.insert(key.into(), value);
        self
    }
}

impl<K: Into<String>> FromIterator<(K, serde_json::Value)> for MemorySettings {
    fn from_iter<I: IntoIterator<Item = (K, serde_json::Value)>>(iter: I) -> Self {
        MemorySettings { values: iter.into_iter().map(|(k, v)| (k.into(), v)).collect() }
    }
}

impl Settings for MemorySettings {
    fn get(&self, key: &str) -> Option<serde_json::Value> {
        self.values.get(key).cloned()
    }
    fn keys(&self) -> Vec<String> {
        self.values.keys().cloned().collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn typed_reads() {
        let s = MemorySettings::new().with("a", json!("x")).with("b", json!(true)).with("c", json!(5));
        let d: &dyn Settings = &s;
        assert_eq!(d.get_str("a").as_deref(), Some("x"));
        assert_eq!(d.get_bool("b"), Some(true));
        assert_eq!(d.get_i64("c"), Some(5));
        assert_eq!(d.get_i64("a"), None);
        assert_eq!(d.keys(), vec!["a", "b", "c"]);
    }
}
