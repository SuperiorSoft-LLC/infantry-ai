// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! `module.json`, the same schema as the Kotlin `ModuleManifest` (only the keys the daemon half needs are typed;
//! the rest is ignored). The Rust daemon adds `daemon.component` (a `.wasm` in the module folder) and
//! `daemon.sidecar` (`{exe, args}`), which replace `daemon.jar`.
use serde::Deserialize;
use std::collections::BTreeMap;
use std::path::{Component, Path, PathBuf};

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ModuleManifest {
    pub name: String,
    #[serde(default)]
    pub version: String,
    #[serde(default)]
    pub daemon: Option<DaemonDecl>,
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DaemonDecl {
    #[serde(default)]
    pub needs: Vec<String>,
    #[serde(default)]
    pub wants: Vec<String>,
    /// `daemon.config` declarations; a field with `"scope":"daemon"` is served by `config.get`.
    #[serde(default)]
    pub config: Vec<serde_json::Value>,
    /// The WASM component, a path inside the module folder.
    #[serde(default)]
    pub component: Option<String>,
    #[serde(default)]
    pub sidecar: Option<SidecarDecl>,
    #[serde(default)]
    pub api_at_least: Option<String>,
    #[serde(default)]
    pub tools: Vec<ToolDecl>,
    #[serde(default)]
    pub frames: Vec<FrameDecl>,
    #[serde(default)]
    pub hooks: Vec<String>,
    #[serde(default)]
    pub routes: Vec<String>,
    #[serde(default)]
    pub public_routes: Vec<String>,
    #[serde(default)]
    pub env: Vec<String>,
    #[serde(default)]
    pub endpoints: BTreeMap<String, BTreeMap<String, String>>,
    #[serde(default)]
    pub secrets: Vec<String>,
    #[serde(default)]
    pub limits: Limits,
    #[serde(default)]
    pub storage: StorageDecl,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SidecarDecl {
    /// A path inside the module folder (`.exe` is appended on Windows when the file has no extension).
    pub exe: String,
    #[serde(default)]
    pub args: Vec<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ToolDecl {
    pub name: String,
    #[serde(default)]
    pub action: String,
    #[serde(default = "read")]
    pub kind: String,
    #[serde(default)]
    pub description: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FrameDecl {
    #[serde(rename = "type")]
    pub r#type: String,
    #[serde(default)]
    pub action: String,
    #[serde(default = "read")]
    pub kind: String,
    #[serde(default)]
    pub owner_only: bool,
}

fn read() -> String {
    "read".into()
}

/// `daemon.limits`; `None` = the host default ([`crate::HostLimits`]).
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Limits {
    pub call_timeout_ms: Option<u64>,
    pub tool_timeout_ms: Option<u64>,
    pub cpu_ms_per_call: Option<u64>,
    pub route_requests_per_minute: Option<u32>,
    pub memory_mb_hint: Option<u32>,
}

#[derive(Debug, Clone, Default, Deserialize)]
pub struct StorageDecl {
    #[serde(default)]
    pub adopt: Vec<String>,
}

/// Where a module came from; decided by the install folder and the signature, never by the manifest.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Tier {
    Core,
    Official,
    Community,
}

impl Tier {
    pub fn as_str(self) -> &'static str {
        match self {
            Tier::Core => "core",
            Tier::Official => "official",
            Tier::Community => "community",
        }
    }
}

/// The tool name pattern `^[a-z][a-z0-9_]{2,63}$` (DaemonPluginHost TOOL_NAME).
pub fn tool_name_ok(name: &str) -> bool {
    let mut chars = name.chars();
    matches!(chars.next(), Some('a'..='z'))
        && (3..=64).contains(&name.len())
        && chars.all(|c| matches!(c, 'a'..='z' | '0'..='9' | '_'))
}

/// `rel` resolved inside `dir`, or why not (absolute paths and `..` are refused).
pub fn inside(dir: &Path, rel: &str, what: &str) -> Result<PathBuf, String> {
    let p = Path::new(rel);
    if p.is_absolute() || p.components().any(|c| matches!(c, Component::ParentDir | Component::Prefix(_) | Component::RootDir)) {
        return Err(format!("{what} {rel} is outside the module folder"));
    }
    Ok(dir.join(p))
}

impl DaemonDecl {
    /// The interfaces requested in `needs` + `wants` that name a WIT import (other entries are NS3 capabilities).
    pub fn requested_imports(&self) -> impl Iterator<Item = &str> {
        self.needs.iter().chain(self.wants.iter()).map(String::as_str).filter(|n| infantry_plugin_wit::GRANTABLE_IMPORTS.contains(n))
    }

    /// The default of a `scope: "daemon"` config field.
    pub fn config_default(&self, key: &str) -> Option<String> {
        self.config.iter().find(|c| c.get("key").and_then(|k| k.as_str()) == Some(key) && c.get("scope").and_then(|s| s.as_str()) == Some("daemon")).and_then(|c| match c.get("default") {
            Some(serde_json::Value::String(s)) => Some(s.clone()),
            Some(serde_json::Value::Null) | None => None,
            Some(v) => Some(v.to_string()),
        })
    }

    /// The address of endpoint `name` for `environment` (prod when that environment has none).
    pub fn endpoint(&self, name: &str, environment: &str) -> Option<String> {
        let e = self.endpoints.get(name)?;
        e.get(environment).or_else(|| e.get("prod")).cloned()
    }

    /// Every declared endpoint address (any environment), for the outbound HTTP filter.
    pub fn endpoint_addresses(&self) -> Vec<String> {
        self.endpoints.values().flat_map(|m| m.values().cloned()).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_the_kotlin_schema_plus_component_and_sidecar() {
        let m: ModuleManifest = serde_json::from_str(
            r#"{"name":"Acme_X","version":"1.0.0","tier":"official","daemon":{"component":"daemon/x.wasm",
            "sidecar":{"exe":"bin/x","args":["--stdio"]},"needs":["frames","ns3.thing"],"wants":["storage"],
            "tools":[{"name":"acme_x","action":"acme.x.read"}],"limits":{"cpuMsPerCall":50},
            "endpoints":{"api":{"prod":"https://a.example","dev":"http://127.0.0.1:1"}},
            "config":[{"key":"k","scope":"daemon","default":"v"}]}}"#,
        )
        .unwrap();
        let d = m.daemon.unwrap();
        assert_eq!(d.requested_imports().collect::<Vec<_>>(), vec!["frames", "storage"]);
        assert_eq!(d.limits.cpu_ms_per_call, Some(50));
        assert_eq!(d.tools[0].kind, "read");
        assert_eq!(d.endpoint("api", "staging").as_deref(), Some("https://a.example"));
        assert_eq!(d.endpoint("api", "dev").as_deref(), Some("http://127.0.0.1:1"));
        assert_eq!(d.config_default("k").as_deref(), Some("v"));
        assert!(tool_name_ok("infantry_health") && !tool_name_ok("Bad-Name"));
        assert!(inside(Path::new("m"), "../x.wasm", "daemon.component").is_err());
    }
}
