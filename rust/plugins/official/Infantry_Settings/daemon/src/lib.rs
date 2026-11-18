// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry_Settings, daemon half: the project config tools (`project_config` frame) and the scoped settings tools
//! (`settings_get` / `settings_set` frames), sent on the caller's connection with the daemon's own gating.
use infantry_plugin_sdk::types::CallContext;
use infantry_plugin_sdk::{export_plugin, host, Plugin};
use serde_json::{json, Map, Value};

const TIMEOUT_MS: u32 = 45_000;

pub struct Settings;

fn s(a: &Value, k: &str) -> Option<String> {
    match a.get(k)? {
        Value::String(x) => Some(x.trim().to_string()).filter(|x| !x.is_empty()),
        Value::Null => None,
        other => Some(other.to_string()),
    }
}

/// Build the `project_config` patch for infantry_project_config_set; the error is the tool's refusal text.
pub fn config_patch(a: &Value) -> Result<Value, String> {
    const N: &str = "infantry_project_config_set";
    let project = s(a, "projectId").ok_or(format!("{N} needs a projectId"))?;
    let auto = match s(a, "autoRemoveDoneWorkers").map(|x| x.to_lowercase()).as_deref() {
        None => None,
        Some("true") => Some(true),
        Some("false") => Some(false),
        Some(o) => return Err(format!("{N}: autoRemoveDoneWorkers must be true or false, was '{}'", o.chars().take(20).collect::<String>())),
    };
    let mut p = Map::new();
    p.insert("projectId".into(), project.into());
    if let Some(k) = s(a, "dataUnset") {
        p.insert("unsetData".into(), json!([k]));
        return Ok(Value::Object(p));
    }
    if let Some(k) = s(a, "dataKey") {
        let v = s(a, "value").ok_or(format!("{N}: dataKey needs a value"))?;
        p.insert("data".into(), json!({ k: v }));
        return Ok(Value::Object(p));
    }
    if let Some(k) = s(a, "experiment") {
        let v = s(a, "value").ok_or(format!("{N}: experiment needs a value of on or off"))?;
        p.insert("experiments".into(), json!({ k: v }));
        return Ok(Value::Object(p));
    }
    let rules = match s(a, "routingRules") {
        None => None,
        Some(raw) => match serde_json::from_str::<Value>(&raw) {
            Ok(v) if v.is_array() => Some(v),
            Ok(_) => return Err(format!("{N}: routingRules must be a JSON array of rules")),
            Err(e) => return Err(format!("{N}: routingRules must be a JSON array: {}", e.to_string().chars().take(200).collect::<String>())),
        },
    };
    let role = s(a, "role");
    let cap = s(a, "cap");
    if role.is_none() && cap.is_none() && (rules.is_some() || auto.is_some()) {
        if let Some(r) = rules {
            p.insert("routingRules".into(), r);
        }
        if let Some(b) = auto {
            p.insert("autoRemoveDoneWorkers".into(), b.into());
        }
        return Ok(Value::Object(p));
    }
    let role = role.ok_or(format!("{N} needs a role (with a cap), autoRemoveDoneWorkers, or routingRules"))?;
    let cap = cap.and_then(|c| c.parse::<i64>().ok()).filter(|c| *c >= 1).ok_or(format!("{N} needs a cap of 1 or more"))?;
    p.insert("laneCaps".into(), json!({ role: cap }));
    if let Some(b) = auto {
        p.insert("autoRemoveDoneWorkers".into(), b.into());
    }
    if let Some(r) = rules {
        p.insert("routingRules".into(), r);
    }
    Ok(Value::Object(p))
}

/// The tool answer for a `project` frame reply.
pub fn project_answer(data: &str) -> Result<String, String> {
    let p: Value = serde_json::from_str(data).map_err(|e| format!("project_config: bad answer: {e}"))?;
    let get = |k: &str, d: Value| p.get(k).cloned().unwrap_or(d);
    Ok(json!({
        "projectId": get("id", Value::Null),
        "laneCaps": get("laneCaps", json!({})),
        "autoRemoveDoneWorkers": get("autoRemoveDoneWorkers", false.into()),
        "routingRules": get("routingRules", json!([])),
        "experiments": get("experiments", json!({})),
        "data": get("data", json!({})),
        "note": "routingRules: first match wins, the last is the default row; a ticket pin of provider/model always wins. max concurrent agents per role; a role not listed runs 1. autoRemoveDoneWorkers: a done ticket worker session is ended and removed"
    })
    .to_string())
}

fn ask(ty: &str, data: &str, want: &str) -> Result<String, String> {
    let r = host::frames::request(ty, data, None, TIMEOUT_MS).map_err(|e| if e.is_empty() { format!("{ty} failed") } else { e })?;
    match r.type_.as_str() {
        t if t == want => Ok(r.data),
        "error" => Err(if r.data.trim().is_empty() { format!("{ty} failed") } else { r.data }),
        t => Err(format!("{ty}: unexpected answer {t}")),
    }
}

impl Plugin for Settings {
    fn call_tool(name: String, args: String, _ctx: CallContext) -> Result<String, String> {
        let a: Value = serde_json::from_str(if args.trim().is_empty() { "{}" } else { &args }).map_err(|e| format!("{name}: arguments are not JSON: {e}"))?;
        match name.as_str() {
            "infantry_project_config_get" => {
                let p = s(&a, "projectId").ok_or("infantry_project_config_get needs a projectId")?;
                project_answer(&ask("project_config", &json!({ "projectId": p }).to_string(), "project")?)
            }
            "infantry_project_config_set" => project_answer(&ask("project_config", &config_patch(&a)?.to_string(), "project")?),
            "infantry_settings_get" => {
                let key = s(&a, "key").ok_or("infantry_settings_get needs a key")?;
                let mut o = Map::new();
                o.insert("key".into(), key.into());
                for f in ["project", "role", "session", "explain"] {
                    if let Some(v) = s(&a, f) {
                        o.insert(f.into(), v.into());
                    }
                }
                ask("settings_get", &Value::Object(o).to_string(), "settings")
            }
            "infantry_settings_set" => {
                let key = s(&a, "key").ok_or("infantry_settings_set needs a key")?;
                let raw = s(&a, "value").ok_or("infantry_settings_set needs a value")?;
                let value = serde_json::from_str::<Value>(&raw).unwrap_or(Value::String(raw));
                let mut o = Map::new();
                o.insert("key".into(), key.into());
                o.insert("value".into(), value);
                for f in ["scope", "project", "role", "session"] {
                    if let Some(v) = s(&a, f) {
                        o.insert(f.into(), v.into());
                    }
                }
                ask("settings_set", &Value::Object(o).to_string(), "settings")
            }
            other => Err(format!("unknown tool {other}")),
        }
    }
}

export_plugin!(Settings);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn patch_shapes() {
        let p = config_patch(&json!({"projectId": "p", "role": "backend", "cap": "3"})).unwrap();
        assert_eq!(p["laneCaps"]["backend"], 3);
        assert!(config_patch(&json!({"projectId": "p", "role": "x"})).unwrap_err().contains("cap of 1"));
        assert_eq!(config_patch(&json!({"projectId": "p", "autoRemoveDoneWorkers": "true"})).unwrap()["autoRemoveDoneWorkers"], true);
    }
}
