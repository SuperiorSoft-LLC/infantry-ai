// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `config_get` / `config_set`: the settings the installed plugins declare (module.json `daemon.config`, the grouped
//! `{node, fields:[...]}` shape; a flat `{key, ...}` entry becomes a one-field card of its module). The Settings dialog
//! draws `config_schema` (the cards) and `config_values` (the overrides) and writes one key with `config_set`.
//! A value is the app-scope row of `settings_get` / `settings_set` (the SAME row, so the two doors agree); a declared key
//! with no row resolves to its declared default. Core names no plugin: the cards are whatever the manifests declare.
use std::path::PathBuf;

use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_protocol::frames as f;
use serde_json::{json, Value};

use super::host_frames::scan_modules;
use super::settings::SettingsUnit;

pub struct ConfigUnit {
    pub settings: SettingsUnit,
    pub plugins_dir: Option<PathBuf>,
}

/// The cards every module declares, in module order.
pub(super) fn declarations(plugins_dir: &Option<PathBuf>) -> Vec<Value> {
    let mut out = vec![];
    for m in scan_modules(plugins_dir) {
        let Some(list) = m.manifest.pointer("/daemon/config").and_then(Value::as_array) else { continue };
        for c in list {
            if c.get("fields").and_then(Value::as_array).is_some() {
                out.push(c.clone());
            } else if c.get("key").is_some() {
                out.push(json!({"node": m.id, "group": m.id, "title": m.id, "fields": [c]}));
            }
        }
    }
    out
}

fn field<'a>(cards: &'a [Value], node: &str, key: &str) -> Option<&'a Value> {
    cards
        .iter()
        .filter(|c| c.get("node").and_then(Value::as_str) == Some(node))
        .flat_map(|c| c.get("fields").and_then(Value::as_array).into_iter().flatten())
        .find(|fl| fl.get("key").and_then(Value::as_str) == Some(key))
}

/// The value to store for `raw` under the declared `field`, or why it is refused.
fn checked(fl: &Value, raw: &Value) -> Result<Value, String> {
    if fl.get("type").and_then(Value::as_str) != Some("number") {
        return Ok(raw.clone());
    }
    let key = fl["key"].as_str().unwrap_or("value");
    let n = match raw {
        Value::Number(n) => n.as_f64(),
        Value::String(s) => s.trim().parse::<f64>().ok(),
        _ => None,
    }
    .filter(|n| n.is_finite())
    .ok_or_else(|| format!("{key} must be a number"))?;
    if let Some(b) = fl.get("min").and_then(Value::as_f64).filter(|b| n < *b) {
        return Err(format!("{key} must be at least {b}"));
    }
    if let Some(b) = fl.get("max").and_then(Value::as_f64).filter(|b| n > *b) {
        return Err(format!("{key} must be at most {b}"));
    }
    Ok(if n.fract() == 0.0 && n.abs() < 9e15 { json!(n as i64) } else { json!(n) })
}

impl ConfigUnit {
    fn rows(&self, cards: &[Value]) -> Vec<Value> {
        let mut rows = vec![];
        for c in cards {
            let node = c.get("node").and_then(Value::as_str).unwrap_or("");
            for fl in c.get("fields").and_then(Value::as_array).into_iter().flatten() {
                let Some(key) = fl.get("key").and_then(Value::as_str) else { continue };
                if let Some(v) = self.settings.app(key) {
                    rows.push(json!({"nodeId": node, "key": key, "value": v}));
                }
            }
        }
        rows
    }
}

impl FrameUnit for ConfigUnit {
    fn name(&self) -> &'static str {
        "config"
    }
    fn frames(&self) -> &'static [&'static str] {
        &[f::CONFIG_GET, f::CONFIG_SET]
    }
    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let cards = declarations(&self.plugins_dir);
        let reply = |ty: &str, d: Value| {
            let mut e = Envelope::new(ty, d.to_string());
            e.request_id = env.request_id.clone();
            e
        };
        if env.r#type == f::CONFIG_GET {
            return Ok(vec![reply(f::CONFIG_SCHEMA, json!({"rev": 0, "declarations": cards})), reply(f::CONFIG_VALUES, json!({"rev": 0, "rows": self.rows(&cards)}))]);
        }
        let d: Value = serde_json::from_str(&env.data).map_err(|e| Refusal::new(f::CONFIG_SET, format!("bad payload ({e})")))?;
        let (node, key) = (d.get("node").and_then(Value::as_str).unwrap_or(""), d.get("key").and_then(Value::as_str).unwrap_or(""));
        let fl = field(&cards, node, key).ok_or_else(|| Refusal::new(f::CONFIG_SET, format!("no plugin declares setting {key} on {node}")))?;
        let value = d.get("value").cloned().unwrap_or(Value::Null);
        let stored = if value.is_null() {
            self.settings.clear_app(key).map_err(|e| Refusal::new(f::CONFIG_SET, e))?;
            Value::Null
        } else {
            let v = checked(fl, &value).map_err(|e| Refusal::new(f::CONFIG_SET, e))?;
            self.settings.set(key, Some(&v), &json!({})).map_err(|e| Refusal::new(f::CONFIG_SET, e))?;
            v
        };
        let changed = json!({"node": node, "key": key, "value": stored, "rev": 0});
        // Every client sees the write; the sender gets it as its own answer (and settles its in-flight edit).
        ctx.bus.publish(Envelope::new(f::CONFIG_CHANGED, changed.to_string()));
        Ok(vec![reply(f::CONFIG_CHANGED, changed)])
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use infantry_core::db::Db;
    use std::sync::Arc;

    fn world() -> (ConfigUnit, PathBuf) {
        let root = std::env::temp_dir().join(format!("cfg-unit-{}", uuid::Uuid::new_v4()));
        let m = root.join("official").join("Demo_Plug");
        std::fs::create_dir_all(&m).unwrap();
        std::fs::write(
            m.join("module.json"),
            r#"{"name":"Demo_Plug","daemon":{"config":[{"node":"Demo_Plug.tab","title":"Demo","fields":[{"key":"demo.refreshSeconds","type":"number","default":30,"min":0,"max":3600,"scope":"app"}]}]}}"#,
        )
        .unwrap();
        let db = Arc::new(Db::open(root.join("infantry.db")).unwrap());
        db.write(|c| c.execute_batch("CREATE TABLE IF NOT EXISTS settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)")).unwrap();
        (ConfigUnit { settings: SettingsUnit { db }, plugins_dir: Some(root.clone()) }, root)
    }
    fn ask(u: &ConfigUnit, ty: &str, data: Value) -> Result<Vec<Envelope>, Refusal> {
        u.handle(&Envelope::new(ty, data.to_string()), &FrameCtx { conn_id: 1, owner: true, bus: Default::default() })
    }
    fn rows(u: &ConfigUnit) -> Value {
        let out = ask(u, f::CONFIG_GET, json!({})).unwrap();
        assert_eq!(out[0].r#type, f::CONFIG_SCHEMA);
        assert_eq!(serde_json::from_str::<Value>(&out[0].data).unwrap()["declarations"][0]["node"], "Demo_Plug.tab");
        serde_json::from_str::<Value>(&out[1].data).unwrap()["rows"].clone()
    }

    #[test]
    fn declared_settings_read_change_zero_and_unknown() {
        let (u, root) = world();
        assert_eq!(rows(&u), json!([])); // nothing stored: the app resolves the declared default
        ask(&u, f::CONFIG_SET, json!({"node":"Demo_Plug.tab","key":"demo.refreshSeconds","value":45})).unwrap();
        assert_eq!(rows(&u)[0]["value"], 45);
        assert_eq!(u.settings.app("demo.refreshSeconds"), Some(json!(45))); // the settings_get door sees the same row
        ask(&u, f::CONFIG_SET, json!({"node":"Demo_Plug.tab","key":"demo.refreshSeconds","value":0})).unwrap();
        assert_eq!(rows(&u)[0]["value"], 0); // 0 is a stored value (refresh off), not "unset"
        assert!(ask(&u, f::CONFIG_SET, json!({"node":"Demo_Plug.tab","key":"demo.refreshSeconds","value":-1})).is_err());
        assert!(ask(&u, f::CONFIG_SET, json!({"node":"Demo_Plug.tab","key":"demo.refreshSeconds","value":"x"})).is_err());
        let e = ask(&u, f::CONFIG_SET, json!({"node":"Demo_Plug.tab","key":"nope.key","value":1})).unwrap_err();
        assert!(e.0.starts_with("config_set refused:"), "{}", e.0);
        ask(&u, f::CONFIG_SET, json!({"node":"Demo_Plug.tab","key":"demo.refreshSeconds","value":null})).unwrap();
        assert_eq!(rows(&u), json!([]));
        let _ = std::fs::remove_dir_all(root);
    }
}
