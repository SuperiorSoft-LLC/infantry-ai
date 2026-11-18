// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The gateway's route table: each inbound frame type goes to `rust` (a ported unit answers it here) or `legacy` (it is
//! forwarded to the Kotlin daemon). The default comes from what the Rust units own today; the setting
//! [`ROUTES_SETTING`] overrides single types, and `"*"` overrides every type. Setting a ported type back to `legacy`
//! is the rollback of that unit.

use serde_json::Value;
use std::collections::BTreeMap;

/// The Infantry setting (env var of the same name, or `--gateway-routes`) holding the override, a JSON object:
/// `{"memory_save":"rust","prompt":"legacy","*":"legacy"}`.
pub const ROUTES_SETTING: &str = "INFANTRY_GATEWAY_ROUTES";
/// The key that overrides every frame type not named on its own.
pub const ALL: &str = "*";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Route {
    Rust,
    Legacy,
}

impl Route {
    fn parse(s: &str) -> Option<Route> {
        match s.trim().to_ascii_lowercase().as_str() {
            "rust" => Some(Route::Rust),
            "legacy" | "kotlin" => Some(Route::Legacy),
            _ => None,
        }
    }
}

#[derive(Clone)]
pub struct RouteTable {
    owned: fn(&str) -> bool,
    overrides: BTreeMap<String, Route>,
}

impl RouteTable {
    /// `owned` answers whether a Rust unit serves a frame type (the default route).
    pub fn new(owned: fn(&str) -> bool) -> RouteTable {
        RouteTable { owned, overrides: BTreeMap::new() }
    }

    /// The table with the override `json` applied (blank = none). A bad value is refused whole, naming the key.
    pub fn with_overrides(mut self, json: &str) -> Result<RouteTable, String> {
        if json.trim().is_empty() {
            return Ok(self);
        }
        let v: Value = serde_json::from_str(json).map_err(|e| format!("{ROUTES_SETTING} is not JSON ({e})"))?;
        let obj = v.as_object().ok_or_else(|| format!("{ROUTES_SETTING} must be a JSON object of type -> \"rust\"|\"legacy\""))?;
        for (k, v) in obj {
            let r = v.as_str().and_then(Route::parse).ok_or_else(|| format!("{ROUTES_SETTING}: `{k}` must be \"rust\" or \"legacy\", not {v}"))?;
            self.overrides.insert(k.clone(), r);
        }
        Ok(self)
    }

    pub fn route(&self, ty: &str) -> Route {
        if let Some(r) = self.overrides.get(ty).or_else(|| self.overrides.get(ALL)) {
            return *r;
        }
        if (self.owned)(ty) { Route::Rust } else { Route::Legacy }
    }

    pub fn is_rust(&self, ty: &str) -> bool {
        self.route(ty) == Route::Rust
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn owned(t: &str) -> bool {
        t == "prompt" || t == "sessions"
    }

    #[test]
    fn default_is_ownership_and_overrides_win() {
        let t = RouteTable::new(owned);
        assert_eq!(t.route("prompt"), Route::Rust);
        assert_eq!(t.route("memory_save"), Route::Legacy);
        let t = t.with_overrides(r#"{"prompt":"legacy","memory_save":"rust"}"#).unwrap();
        assert_eq!(t.route("prompt"), Route::Legacy);
        assert_eq!(t.route("memory_save"), Route::Rust);
        assert_eq!(t.route("sessions"), Route::Rust);
    }

    #[test]
    fn star_rolls_everything_back() {
        let t = RouteTable::new(owned).with_overrides(r#"{"*":"legacy","sessions":"rust"}"#).unwrap();
        assert_eq!(t.route("prompt"), Route::Legacy);
        assert_eq!(t.route("sessions"), Route::Rust);
    }

    #[test]
    fn bad_values_are_refused() {
        assert!(RouteTable::new(owned).with_overrides(r#"{"prompt":"maybe"}"#).unwrap_err().contains("`prompt`"));
        assert!(RouteTable::new(owned).with_overrides("[1]").is_err());
        assert!(RouteTable::new(owned).with_overrides("  ").is_ok());
    }
}
