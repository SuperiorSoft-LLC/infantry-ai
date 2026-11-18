// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `settings_get` / `settings_set` (Kotlin `ScopedSettings`, SETTINGS-SCOPES-1) on the SAME `settings` table: app values
//! are the flat rows, a scoped value is the row `@scope/<scope>/<target>/<key>` (role target = `<project>/<role>`).
//! Resolution: session > role > project > app > default. Answers `settings` with `{key, value, scope, layers}`.
//!
//! Not ported yet: the per-key allowed-scope table and value validation (every key accepts every scope).
use infantry_core::db::Db;
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use serde_json::{json, Value};
use std::sync::Arc;

pub const SETTINGS_GET: &str = "settings_get";
pub const SETTINGS_SET: &str = "settings_set";
pub const SETTINGS: &str = "settings";

pub struct SettingsUnit {
    pub db: Arc<Db>,
}

fn row_key(scope: &str, target: &str, key: &str) -> String {
    format!("@scope/{scope}/{target}/{key}")
}

fn text(d: &Value, k: &str) -> Option<String> {
    d.get(k).and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty()).map(str::to_string)
}

impl SettingsUnit {
    fn stored(&self, row: &str) -> Option<Value> {
        let raw: Option<String> = self
            .db
            .read(|c| {
                c.query_row("SELECT value FROM settings WHERE key = ?1", [row], |r| r.get::<_, String>(0)).map(Some).or_else(|e| match e {
                    rusqlite::Error::QueryReturnedNoRows => Ok(None),
                    e => Err(e),
                })
            })
            .ok()
            .flatten();
        raw.and_then(|s| serde_json::from_str::<Value>(&s).ok()).filter(|v| !v.is_null())
    }

    fn targets(d: &Value) -> Vec<(&'static str, Option<String>)> {
        let project = text(d, "project");
        let role = text(d, "role");
        vec![
            ("session", text(d, "session")),
            ("role", project.as_ref().zip(role.as_ref()).map(|(p, r)| format!("{p}/{r}"))),
            ("project", project.clone()),
        ]
    }

    pub fn resolve(&self, key: &str, d: &Value) -> Value {
        let mut layers = Vec::new();
        for (scope, target) in Self::targets(d) {
            if let Some(t) = target {
                if let Some(v) = self.stored(&row_key(scope, &t, key)) {
                    layers.push(json!({"scope": scope, "target": t, "value": v}));
                }
            }
        }
        if let Some(v) = self.stored(key) {
            layers.push(json!({"scope": "app", "target": "", "value": v}));
        }
        let (value, scope) = layers.first().map(|l| (l["value"].clone(), l["scope"].clone())).unwrap_or((Value::Null, json!("default")));
        json!({"key": key, "value": value, "scope": scope, "layers": layers})
    }

    fn set(&self, key: &str, value: Option<&Value>, d: &Value) -> Result<(), String> {
        let scope = text(d, "scope").unwrap_or_else(|| "app".into()).to_lowercase();
        let row = if scope == "app" {
            if value.map(Value::is_null).unwrap_or(true) {
                return Err("the app scope cannot be cleared, set a value".into());
            }
            key.to_string()
        } else {
            let target = Self::targets(d)
                .into_iter()
                .find(|(s, _)| *s == scope)
                .ok_or_else(|| format!("unknown scope '{scope}' (one of app, project, role, session)"))?
                .1
                .ok_or_else(|| format!("scope {scope} needs its target ({})", if scope == "role" { "project and role" } else { scope.as_str() }))?;
            row_key(&scope, &target, key)
        };
        let stored = value.cloned().unwrap_or(Value::Null).to_string();
        let now = super::now_ms();
        self.db
            .write(|c| {
                c.execute(
                    "INSERT INTO settings(key, value, updated_at) VALUES (?1, ?2, ?3) ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
                    rusqlite::params![row, stored, now],
                )
            })
            .map(|_| ())
            .map_err(|e| e.to_string())
    }
}

impl FrameUnit for SettingsUnit {
    fn name(&self) -> &'static str {
        "settings"
    }
    fn frames(&self) -> &'static [&'static str] {
        &[SETTINGS_GET, SETTINGS_SET]
    }
    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let ty = env.r#type.as_str();
        let d: Value = serde_json::from_str(if env.data.trim().is_empty() { "{}" } else { &env.data }).map_err(|e| Refusal::new(ty, format!("bad payload ({e})")))?;
        let key = text(&d, "key").ok_or_else(|| Refusal::new(ty, "a key is required"))?;
        if ty == SETTINGS_SET {
            let _ = ctx;
            self.set(&key, d.get("value"), &d).map_err(|e| Refusal::new(ty, e))?;
        }
        let mut out = self.resolve(&key, &d);
        if text(&d, "explain").as_deref() == Some("true") {
            let mut lines = vec![format!("{key} = {}  (from {})", out["value"], out["scope"].as_str().unwrap_or("default")), "  order: session > role > project > app > default".into()];
            for l in out["layers"].as_array().cloned().unwrap_or_default() {
                lines.push(format!("  {} {} = {}", l["scope"].as_str().unwrap_or(""), l["target"].as_str().unwrap_or(""), l["value"]));
            }
            out["explain"] = json!(lines.join("\n"));
        }
        let mut e = Envelope::new(SETTINGS, out.to_string());
        e.request_id = env.request_id.clone();
        Ok(vec![e])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_scoped_value_wins_over_app_and_clears_back() {
        let dir = std::env::temp_dir().join(format!("rm2-settings-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let db = Arc::new(Db::open(dir.join("infantry.db")).unwrap());
        db.write(|c| c.execute_batch("CREATE TABLE IF NOT EXISTS settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)")).unwrap();
        let u = SettingsUnit { db };
        u.set("a.b", Some(&json!(5)), &json!({})).unwrap();
        u.set("a.b", Some(&json!(7)), &json!({"scope": "project", "project": "p"})).unwrap();
        assert_eq!(u.resolve("a.b", &json!({"project": "p"}))["value"], json!(7));
        assert_eq!(u.resolve("a.b", &json!({}))["scope"], json!("app"));
        u.set("a.b", Some(&Value::Null), &json!({"scope": "project", "project": "p"})).unwrap();
        assert_eq!(u.resolve("a.b", &json!({"project": "p"}))["value"], json!(5));
        assert!(u.set("a.b", None, &json!({})).is_err());
        let _ = std::fs::remove_dir_all(dir);
    }
}
