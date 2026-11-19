// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! opencode's configuration: the inline `OPENCODE_CONFIG_CONTENT` a `serve` child gets (permission rules, the watch
//! plugin, MCP servers; always MERGED into what the child would inherit, never overwritten), the permission policy per
//! [`ApprovalMode`], and the model catalogue read from the user's own config files (no process started).

use infantry_harness::{ApprovalMode, ModelInfo, Probe};
use serde_json::{json, Map, Value};
use std::collections::{BTreeSet, HashSet};
use std::path::{Path, PathBuf};

/// opencode reads an inline JSON config from here, merged over the user's config files.
pub const CONFIG_CONTENT_ENV: &str = "OPENCODE_CONFIG_CONTENT";
/// The bash pattern of Infantry's own CLI, allowed in every mode (BUG-PERM-5).
pub const INFANTRY_BASH_PATTERN: &str = "infantry *";
/// The session's own MCP server (opencode names its tools `infantry_<tool>`).
pub const MCP_SERVER: &str = "infantry";
pub const MCP_TOOL_PATTERN: &str = "infantry_*";
/// The permissions `ask` mode makes opencode ask about (reads are never gated).
pub const ASK_PERMISSIONS: [&str; 4] = ["edit", "bash", "webfetch", "websearch"];
/// opencode 1.x asks `edit` for every file-changing tool; the tool names are kept for builds that name the tool.
pub const EDIT_PERMISSIONS: [&str; 5] = ["edit", "write", "patch", "multiedit", "apply_patch"];

fn parse_obj(text: Option<&str>) -> Map<String, Value> {
    text.filter(|t| !t.trim().is_empty())
        .and_then(|t| serde_json::from_str::<Value>(t).ok())
        .and_then(|v| match v {
            Value::Object(m) => Some(m),
            _ => None,
        })
        .unwrap_or_default()
}

/// `permission` as an object: a single string becomes `{"*": it}`.
fn permission_obj(root: &Map<String, Value>) -> Map<String, Value> {
    match root.get("permission") {
        Some(Value::Object(m)) => m.clone(),
        Some(p @ Value::String(_)) => {
            let mut m = Map::new();
            m.insert("*".into(), p.clone());
            m
        }
        _ => Map::new(),
    }
}

/// Sets `key` last in [m] (opencode resolves the last matching pattern).
fn put_last(m: &mut Map<String, Value>, key: &str, value: Value) {
    m.remove(key);
    m.insert(key.into(), value);
}

/// [existing] with every [`ASK_PERMISSIONS`] key set to `"ask"` (PROMPT-4).
pub fn merge_ask_rules(existing: Option<&str>) -> String {
    let mut root = parse_obj(existing);
    let mut p = permission_obj(&root);
    for k in ASK_PERMISSIONS {
        put_last(&mut p, k, json!("ask"));
    }
    root.insert("permission".into(), Value::Object(p));
    Value::Object(root).to_string()
}

/// [existing] with `permission.bash."infantry *"` = allow, every other key kept (BUG-PERM-5).
pub fn merge_allow_infantry(existing: Option<&str>) -> String {
    let mut root = parse_obj(existing);
    let mut p = match root.get("permission") {
        Some(Value::Object(m)) => m.clone(),
        Some(s @ Value::String(_)) => {
            let mut m = Map::new();
            m.insert("*".into(), s.clone());
            m.insert("bash".into(), s.clone());
            m
        }
        _ => Map::new(),
    };
    let mut bash = match p.get("bash") {
        Some(Value::Object(m)) => m.clone(),
        Some(s @ Value::String(_)) => {
            let mut m = Map::new();
            m.insert("*".into(), s.clone());
            m
        }
        _ => Map::new(),
    };
    put_last(&mut bash, INFANTRY_BASH_PATTERN, json!("allow"));
    put_last(&mut p, "bash", Value::Object(bash));
    root.insert("permission".into(), Value::Object(p));
    Value::Object(root).to_string()
}

/// [config] with a local plugin (its runner-host path) appended to the `plugin` list, as a `file://` URL.
pub fn append_plugin(config: &str, plugin_path: &str) -> String {
    let mut root = parse_obj(Some(config));
    let spec = file_url(plugin_path);
    let mut list: Vec<Value> = match root.get("plugin") {
        Some(Value::Array(a)) => a.iter().filter(|v| v.as_str() != Some(spec.as_str())).cloned().collect(),
        _ => Vec::new(),
    };
    list.push(Value::String(spec));
    root.insert("plugin".into(), Value::Array(list));
    Value::Object(root).to_string()
}

/// [config] with the session's own MCP server ([command] + [env]) as `mcp.infantry` and its tools allowed; a user's own
/// `mcp.infantry` is kept as it is (MCP-1).
pub fn merge_mcp(config: &str, command: &[String], env: &[(String, String)]) -> String {
    let mut root = parse_obj(Some(config));
    let mut mcp = match root.get("mcp") {
        Some(Value::Object(m)) => m.clone(),
        _ => Map::new(),
    };
    if !mcp.contains_key(MCP_SERVER) {
        let env: Map<String, Value> = env.iter().map(|(k, v)| (k.clone(), Value::String(v.clone()))).collect();
        mcp.insert(MCP_SERVER.into(), json!({"type": "local", "command": command, "environment": env, "enabled": true}));
    }
    let mut p = permission_obj(&root);
    put_last(&mut p, MCP_TOOL_PATTERN, json!("allow"));
    root.insert("mcp".into(), Value::Object(mcp));
    root.insert("permission".into(), Value::Object(p));
    Value::Object(root).to_string()
}

/// `file:///C:/x/y.js` (Windows) or `file:///x/y.js`.
pub fn file_url(path: &str) -> String {
    let p = path.replace('\\', "/");
    if p.starts_with('/') {
        format!("file://{p}")
    } else {
        format!("file:///{p}")
    }
}

/// The `serve` child's inline config under [mode]: [inherited] + the infantry allow, + the ask rules in `ask` mode.
pub fn serve_config(mode: ApprovalMode, inherited: Option<&str>) -> String {
    if mode == ApprovalMode::Ask {
        merge_allow_infantry(Some(&merge_ask_rules(inherited)))
    } else {
        merge_allow_infantry(inherited)
    }
}

pub fn is_edit(permission: &str) -> bool {
    EDIT_PERMISSIONS.contains(&permission.trim().to_ascii_lowercase().as_str())
}

/// Whether [mode] lets a call needing [permission] run without asking.
pub fn allows(mode: ApprovalMode, permission: &str) -> bool {
    match mode {
        ApprovalMode::Deny | ApprovalMode::Ask => false,
        ApprovalMode::AllowEdits => is_edit(permission),
        ApprovalMode::AllowAll => true,
    }
}

/// [command] is a plain `infantry` invocation, nothing chained onto it.
pub fn is_infantry_command(command: &str) -> bool {
    let c = command.trim();
    let chained = c.contains([';', '&', '|', '`', '<', '>', '\r', '\n']) || c.contains("$(");
    (c == "infantry" || c.starts_with("infantry ")) && !chained
}

/// A permission ask naming one of the session's own MCP tools.
pub fn is_infantry_tool(permission: &str) -> bool {
    permission.trim().to_ascii_lowercase().starts_with("infantry_")
}

// --- JSONC --------------------------------------------------------------------------------------------------------

/// Parses opencode's JSONC (comments and trailing commas allowed).
pub fn parse_jsonc(text: &str) -> Option<Value> {
    let text = text.trim_start_matches('\u{feff}');
    let mut out = String::with_capacity(text.len());
    let b: Vec<char> = text.chars().collect();
    let (mut i, mut in_str) = (0usize, false);
    while i < b.len() {
        let c = b[i];
        if in_str {
            out.push(c);
            if c == '\\' && i + 1 < b.len() {
                out.push(b[i + 1]);
                i += 1;
            } else if c == '"' {
                in_str = false;
            }
        } else if c == '"' {
            in_str = true;
            out.push(c);
        } else if c == '/' && b.get(i + 1) == Some(&'/') {
            while i < b.len() && b[i] != '\n' {
                i += 1;
            }
            continue;
        } else if c == '/' && b.get(i + 1) == Some(&'*') {
            i += 2;
            while i + 1 < b.len() && !(b[i] == '*' && b[i + 1] == '/') {
                i += 1;
            }
            i += 2;
            continue;
        } else if c == ',' {
            let next = b[i + 1..].iter().find(|c| !c.is_whitespace());
            if !matches!(next, Some('}') | Some(']')) {
                out.push(c);
            }
        } else {
            out.push(c);
        }
        i += 1;
    }
    serde_json::from_str(&out).ok()
}

// --- the model catalogue --------------------------------------------------------------------------------------------

/// Where opencode keeps what the user configured (xdg-basedir on every OS, Windows included).
#[derive(Debug, Clone)]
pub struct Locations {
    pub config_files: Vec<PathBuf>,
    pub auth_file: PathBuf,
    pub models_file: PathBuf,
}

impl Locations {
    pub fn from_probe(probe: &dyn Probe) -> Option<Locations> {
        let home = probe.home()?;
        let base = |xdg: &str, fallback: &str| {
            probe.env(xdg).map(PathBuf::from).unwrap_or_else(|| fallback.split('/').fold(home.clone(), |p, s| p.join(s))).join("opencode")
        };
        let config = base("XDG_CONFIG_HOME", ".config");
        let data = base("XDG_DATA_HOME", ".local/share");
        let cache = base("XDG_CACHE_HOME", ".cache");
        let mut config_files = vec![config.join("opencode.json"), config.join("opencode.jsonc")];
        if let Some(f) = probe.env("OPENCODE_CONFIG") {
            config_files.push(PathBuf::from(f));
        }
        if let Some(d) = probe.env("OPENCODE_CONFIG_DIR") {
            config_files.push(Path::new(&d).join("opencode.json"));
            config_files.push(Path::new(&d).join("opencode.jsonc"));
        }
        Some(Locations {
            config_files,
            auth_file: data.join("auth.json"),
            models_file: probe.env("OPENCODE_MODELS_PATH").map(PathBuf::from).unwrap_or_else(|| cache.join("models.json")),
        })
    }
}

fn str_list(v: Option<&Value>) -> Option<Vec<String>> {
    v?.as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect())
}

/// The models the user's opencode setup offers: a provider counts when it is not disabled and is signed in
/// (`auth.json`), has its API-key variable set, or is defined in the config; its models are the models.dev cache entry
/// (minus deprecated) plus the config's own, honouring whitelist / blacklist. Sorted by provider, then model.
pub fn catalogue(probe: &dyn Probe, loc: &Locations) -> Vec<ModelInfo> {
    let read = |p: &Path| probe.read_to_string(p).and_then(|t| parse_jsonc(&t)).and_then(|v| v.as_object().cloned());
    let configs: Vec<Map<String, Value>> = loc.config_files.iter().filter_map(|p| read(p)).collect();
    let auth = read(&loc.auth_file).unwrap_or_default();
    let built_in = read(&loc.models_file).unwrap_or_default();

    let disabled: HashSet<String> = configs.iter().flat_map(|c| str_list(c.get("disabled_providers")).unwrap_or_default()).collect();
    let enabled: Option<HashSet<String>> =
        configs.iter().filter_map(|c| str_list(c.get("enabled_providers"))).last().map(|l| l.into_iter().collect());
    let blocks = |id: &str| -> Vec<Map<String, Value>> {
        configs.iter().filter_map(|c| c.get("provider")?.get(id)?.as_object().cloned()).collect()
    };

    let mut candidates: BTreeSet<String> = auth.keys().cloned().collect();
    for c in &configs {
        if let Some(p) = c.get("provider").and_then(Value::as_object) {
            candidates.extend(p.keys().cloned());
        }
    }
    for (id, p) in &built_in {
        if str_list(p.get("env")).unwrap_or_default().iter().any(|e| probe.env(e).is_some()) {
            candidates.insert(id.clone());
        }
    }

    let mut out = Vec::new();
    for id in candidates.into_iter().filter(|id| !disabled.contains(id) && enabled.as_ref().is_none_or(|e| e.contains(id))) {
        let dev = built_in.get(&id).and_then(Value::as_object);
        let bl = blocks(&id);
        let mut ids: BTreeSet<String> = BTreeSet::new();
        if let Some(models) = dev.and_then(|d| d.get("models")).and_then(Value::as_object) {
            for (mid, m) in models {
                if m.get("status").and_then(Value::as_str) != Some("deprecated") {
                    ids.insert(mid.clone());
                }
            }
        }
        for b in &bl {
            if let Some(m) = b.get("models").and_then(Value::as_object) {
                ids.extend(m.keys().cloned());
            }
        }
        let whitelist = bl.iter().filter_map(|b| str_list(b.get("whitelist"))).last().filter(|w| !w.is_empty());
        let blacklist: HashSet<String> = bl.iter().flat_map(|b| str_list(b.get("blacklist")).unwrap_or_default()).collect();
        for mid in ids {
            if whitelist.as_ref().is_none_or(|w| w.contains(&mid)) && !blacklist.contains(&mid) {
                let model_name = dev
                    .and_then(|d| d.get("models"))
                    .and_then(|m| m.get(&mid))
                    .and_then(|m| m.get("name"))
                    .and_then(Value::as_str)
                    .map(String::from);
                let full = format!("{id}/{mid}");
                out.push(ModelInfo { label: model_name.unwrap_or_else(|| full.clone()), id: full });
            }
        }
    }
    out
}

/// `opencode models` stdout: one `provider/model` per line.
pub fn parse_models_output(text: &str) -> Vec<ModelInfo> {
    text.lines()
        .map(str::trim)
        .filter(|l| l.contains('/') && !l.starts_with('/') && !l.chars().any(char::is_whitespace))
        .map(|l| ModelInfo { id: l.into(), label: l.into() })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn infantry_allow_is_merged_last() {
        let c = merge_allow_infantry(Some(r#"{"model":"x","permission":{"bash":"ask","edit":"deny"}}"#));
        let v: Value = serde_json::from_str(&c).unwrap();
        assert_eq!(v["model"], "x");
        assert_eq!(v["permission"]["edit"], "deny");
        assert_eq!(v["permission"]["bash"]["*"], "ask");
        assert_eq!(v["permission"]["bash"]["infantry *"], "allow");
        let ask: Value = serde_json::from_str(&serve_config(ApprovalMode::Ask, Some(r#"{"permission":"allow"}"#))).unwrap();
        assert_eq!(ask["permission"]["*"], "allow");
        assert_eq!(ask["permission"]["edit"], "ask");
        assert_eq!(ask["permission"]["bash"]["*"], "ask");
    }

    #[test]
    fn jsonc_and_policy() {
        let v = parse_jsonc("{\n // c\n \"a\": [1, 2,], /* b */ \"s\": \"x//y\",\n}").unwrap();
        assert_eq!(v["s"], "x//y");
        assert!(is_infantry_command("infantry ticket get 1"));
        assert!(!is_infantry_command("infantry x; rm -rf /"));
        assert!(allows(ApprovalMode::AllowEdits, "edit") && !allows(ApprovalMode::AllowEdits, "bash"));
        assert_eq!(file_url("C:\\s\\w.js"), "file:///C:/s/w.js");
    }
}
