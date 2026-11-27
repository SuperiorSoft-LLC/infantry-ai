// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `settings_get` / `settings_set` (Kotlin `ScopedSettings`, SETTINGS-SCOPES-1) on the SAME `settings` table: app values
//! are the flat rows, a scoped value is the row `@scope/<scope>/<target>/<key>` (role target = `<project>/<role>`).
//! Resolution: session > role > project > app > default. Answers `settings` with `{key, value, scope, layers}`.
//!
//! A declared key's range is checked by `config::CheckedSettingsUnit` (the registered door), the same check as `config_set`.
//! Not ported yet: the per-key allowed-scope table (every key accepts every scope).
use infantry_core::db::Db;
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use serde_json::{json, Value};
use std::sync::Arc;

pub const SETTINGS_GET: &str = "settings_get";
pub const SETTINGS_SET: &str = "settings_set";
pub const SETTINGS: &str = "settings";
/// The bus event a `settings_set` emits: `{"key": ...}`.
pub const SETTINGS_CHANGED_EVENT: &str = "settings.changed";

pub struct SettingsUnit {
    pub db: Arc<Db>,
}

/// RUNNER-SETTINGS-OWNER-1 (Kotlin `FrameGate.OWNER_ONLY_SETTING_KEYS`): keys (exact, or a prefix ending in `.`) only an
/// owner connection may WRITE, through `settings_set` or `config_set`; a read stays open. The runner's trust keys, plus
/// (SETTINGS-OWNER-2) where plugin code comes from, the safety-script off switch, the owner-alert wake policy and the
/// plugin wall/CPU budgets.
pub const OWNER_ONLY_SETTING_KEYS: &[&str] = &[
    "runner.",
    "limits.runners.",
    "runners.enabled",
    "runners.advertiseHosts",
    "runners.fingerprint",
    "plugins.sources",
    "plugins.bootstrap",
    "scripting.builtins.off",
    "notify.wake.kinds",
    "limits.plugins.",
    // Which binary each harness CLI runs from (cli_location_set): an agent must not point it at its own program.
    "cli.executable.",
];

pub fn is_owner_only_setting(key: &str) -> bool {
    let k = key.trim().to_lowercase();
    OWNER_ONLY_SETTING_KEYS.iter().any(|p| {
        let p = p.to_lowercase();
        if p.ends_with('.') { k.starts_with(&p) } else { k == p }
    })
}

/// Why a write of `key` is refused on a non-owner connection; None when it may go ahead.
pub fn owner_refusal(key: &str, owner: bool) -> Option<String> {
    (!owner && is_owner_only_setting(key)).then(|| format!("{} needs the owner credential; agents do not hold it", key.trim()))
}

fn row_key(scope: &str, target: &str, key: &str) -> String {
    format!("@scope/{scope}/{target}/{key}")
}

/// The setting a session's default context limit (tokens) is read from; 0 = off.
pub const DEFAULT_CONTEXT_KEY: &str = "limits.session.defaultContextTokens";
/// What the setting is when no scope sets it.
pub const DEFAULT_CONTEXT_TOKENS: i32 = 500_000;

/// A token count as the settings hold it: a number, or text such as `500000`, `500k`, `1m`, `off`.
pub fn parse_tokens(v: &Value) -> Option<i32> {
    if let Some(n) = v.as_i64() {
        return Some(n.clamp(0, i32::MAX as i64) as i32);
    }
    let t = v.as_str()?.trim().to_lowercase();
    if t == "off" || t == "none" {
        return Some(0);
    }
    let (num, mult) = match t.chars().last()? {
        'k' => (&t[..t.len() - 1], 1_000.0),
        'm' => (&t[..t.len() - 1], 1_000_000.0),
        _ => (t.as_str(), 1.0),
    };
    num.trim().parse::<f64>().ok().filter(|n| *n >= 0.0).map(|n| (n * mult).min(i32::MAX as f64) as i32)
}

/// `(tokens, source scope)` the default context limit resolves to for a spawn in `project` / `role`
/// (session > role > project > app > default). 0 tokens = off.
pub fn default_context_limit(settings: &SettingsUnit, project: Option<&str>, role: Option<&str>) -> Option<(i32, String)> {
    let mut d = json!({});
    if let Some(p) = project {
        d["project"] = json!(p);
    }
    if let Some(r) = role {
        d["role"] = json!(r);
    }
    let r = settings.resolve(DEFAULT_CONTEXT_KEY, &d);
    match parse_tokens(&r["value"]) {
        Some(n) => Some((n, r["scope"].as_str().unwrap_or("app").to_string())),
        None => Some((DEFAULT_CONTEXT_TOKENS, "default".to_string())),
    }
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

    /// The app-scope value of `key` (null and absent are None).
    pub fn app(&self, key: &str) -> Option<Value> {
        self.stored(key)
    }

    /// Removes the app-scope row of `key` (back to the declared default).
    pub fn clear_app(&self, key: &str) -> Result<(), String> {
        self.db.write(|c| c.execute("DELETE FROM settings WHERE key = ?1", [key])).map(|_| ()).map_err(|e| e.to_string())
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

    pub fn set(&self, key: &str, value: Option<&Value>, d: &Value) -> Result<(), String> {
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
            if let Some(why) = owner_refusal(&key, ctx.owner) {
                return Err(Refusal::new(ty, why));
            }
            self.set(&key, d.get("value"), &d).map_err(|e| Refusal::new(ty, e))?;
            // PACE-UNIFY-1: a named event, so a unit that reads this key (e.g. usage pacing) can answer the change live.
            ctx.bus.emit(SETTINGS_CHANGED_EVENT, json!({"key": key}));
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

#[cfg(test)]
mod owner_key_tests {
    use super::*;

    #[test]
    fn owner_only_keys_match_exact_and_prefix_and_only_refuse_agents() {
        assert!(is_owner_only_setting("runner.grants"));
        assert!(is_owner_only_setting("limits.runners.linkCallSeconds"));
        assert!(is_owner_only_setting("Runners.Enabled"));
        assert!(!is_owner_only_setting("runners.enabledExtra"));
        assert!(!is_owner_only_setting("sites.refreshSeconds"));
        assert!(owner_refusal("runner.grants", false).is_some());
        assert!(owner_refusal("runner.grants", true).is_none());
        assert!(owner_refusal("sites.refreshSeconds", false).is_none());
    }

    #[test]
    fn settings_owner_2_keys_refuse_agents_and_allow_the_owner() {
        for k in ["plugins.sources", "plugins.bootstrap", "scripting.builtins.off", "notify.wake.kinds", "limits.plugins.toolTimeoutMs", "limits.plugins.maxDeclaredTimeoutMs"] {
            assert!(owner_refusal(k, false).is_some(), "{k} must refuse an agent");
            assert!(owner_refusal(k, true).is_none(), "{k} must allow the owner");
        }
        assert!(!is_owner_only_setting("plugins.sourcesExtra"));
        assert!(!is_owner_only_setting("limits.pluginsX"));
    }

    #[test]
    fn settings_set_refuses_an_agent_on_an_owner_key() {
        let dir = std::env::temp_dir().join(format!("ownerkey-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let db = Arc::new(Db::open(dir.join("i.db")).unwrap());
        db.write(|c| c.execute_batch("CREATE TABLE IF NOT EXISTS settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)")).unwrap();
        let u = SettingsUnit { db };
        let set = |key: &str, owner: bool| u.handle(&Envelope::new(SETTINGS_SET, json!({"key": key, "value": 1}).to_string()), &FrameCtx { conn_id: 1, owner, bus: Default::default() });
        let e = set("runners.enabled", false).err().unwrap().0;
        assert!(e.contains("needs the owner credential"), "{e}");
        assert!(u.app("runners.enabled").is_none(), "nothing written");
        assert!(set("runners.enabled", true).is_ok());
        assert!(set("sites.refreshSeconds", false).is_ok(), "other keys stay agent-writable");
        let _ = std::fs::remove_dir_all(dir);
    }
}

#[cfg(test)]
mod context_default_tests {
    use super::*;

    #[test]
    fn tokens_parse_numbers_suffixes_and_off() {
        assert_eq!(parse_tokens(&json!(500000)), Some(500_000));
        assert_eq!(parse_tokens(&json!("250k")), Some(250_000));
        assert_eq!(parse_tokens(&json!("1m")), Some(1_000_000));
        assert_eq!(parse_tokens(&json!("off")), Some(0));
        assert_eq!(parse_tokens(&json!("many")), None);
        assert_eq!(parse_tokens(&json!(-5)), Some(0));
    }

    #[test]
    fn default_resolves_default_then_app_then_project_then_role_and_off() {
        let dir = std::env::temp_dir().join(format!("ctxdef-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let db = std::sync::Arc::new(infantry_core::db::Db::open(dir.join("i.db")).unwrap());
        db.write(|c| c.execute_batch("CREATE TABLE IF NOT EXISTS settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)")).unwrap();
        let u = SettingsUnit { db };
        let at = |p: Option<&str>, r: Option<&str>| default_context_limit(&u, p, r).unwrap();
        assert_eq!(at(Some("p1"), Some("pm")), (500_000, "default".into()));
        u.set(DEFAULT_CONTEXT_KEY, Some(&json!("250k")), &json!({})).unwrap();
        assert_eq!(at(Some("p1"), Some("pm")), (250_000, "app".into()));
        u.set(DEFAULT_CONTEXT_KEY, Some(&json!(1_000_000)), &json!({"scope":"project","project":"p1"})).unwrap();
        assert_eq!(at(Some("p1"), Some("pm")), (1_000_000, "project".into()));
        assert_eq!(at(Some("p2"), None), (250_000, "app".into()));
        u.set(DEFAULT_CONTEXT_KEY, Some(&json!(0)), &json!({"scope":"role","project":"p1","role":"pm"})).unwrap();
        assert_eq!(at(Some("p1"), Some("pm")), (0, "role".into()));
    }
}

/// PACE-UNIFY-1: the usage service's store is the daemon settings (app scope), so `usage.pacing`, `usage.manual` and
/// `usage.overrides` have one source: what `settings_set` writes is what pacing enforces and the Usage panel shows.
pub struct UsageSettingsStore(pub SettingsUnit);

impl infantry_usage::config::UsageStore for UsageSettingsStore {
    fn get(&self, key: &str) -> Option<Value> {
        self.0.app(key).filter(|v| !v.is_null())
    }
    fn set(&self, key: &str, value: Value) -> Result<(), String> {
        self.0.set(key, Some(&value), &json!({}))
    }
}
