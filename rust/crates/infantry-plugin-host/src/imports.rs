// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The WIT imports for ONE plugin, runtime-neutral: the WASM linker and the sidecar's JSON-lines `import` lines both
//! end here. This is where the per-plugin rules live: grants, the `<Module>.` settings prefix, the endpoint filter
//! for outbound HTTP and the table rule for SQL.
use crate::manifest::{DaemonDecl, Tier};
use crate::services::HostServices;
use infantry_plugin_wit::jsonl::{self, CallContext, Frame, HttpRequest, HttpResponse, LogLevel, NamedEvent, SqlRows, SqlValue};
use rusqlite::hooks::{AuthAction, AuthContext, Authorization};
use serde_json::{json, Value};
use std::collections::BTreeSet;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

/// The key/value table the host keeps for every plugin (core-owned; no plugin may name it in SQL).
pub const KV_TABLE: &str = "plugin_kv";

pub struct PluginImports {
    pub module_id: String,
    pub tier: Tier,
    pub decl: DaemonDecl,
    services: Arc<dyn HostServices>,
    /// Interfaces linked AND granted.
    granted: BTreeSet<String>,
    /// Interfaces linked but refused (a `wants` the owner did not grant): every call fails.
    denied: BTreeSet<String>,
    subscriptions: Mutex<Vec<(u64, String)>>,
    next_subscription: AtomicU64,
    /// The callers of the calls running now, innermost last (see [`PluginImports::acting_for`]).
    active: Mutex<Vec<CallContext>>,
}

/// Pops the caller [`PluginImports::acting_for`] pushed, when the call ends (also on a fault).
pub struct ActingFor<'a>(&'a PluginImports);

impl Drop for ActingFor<'_> {
    fn drop(&mut self) {
        self.0.active.lock().unwrap().pop();
    }
}

impl PluginImports {
    pub fn new(module_id: &str, tier: Tier, decl: DaemonDecl, services: Arc<dyn HostServices>, granted: BTreeSet<String>, denied: BTreeSet<String>) -> Self {
        PluginImports { module_id: module_id.into(), tier, decl, services, granted, denied, subscriptions: Mutex::default(), next_subscription: AtomicU64::new(1), active: Mutex::default() }
    }

    /// Whether interface `name` is linked (granted or linked-as-denied).
    pub fn linked(&self, name: &str) -> bool {
        infantry_plugin_wit::BASELINE_IMPORTS.contains(&name) || self.granted.contains(name) || self.denied.contains(name)
    }

    fn check(&self, iface: &str) -> Result<(), String> {
        if self.granted.contains(iface) {
            Ok(())
        } else {
            Err(format!("{iface} not granted to {}", self.module_id))
        }
    }

    pub fn services(&self) -> &Arc<dyn HostServices> {
        &self.services
    }

    // ---- log, config ----

    pub fn log(&self, level: LogLevel, line: &str) {
        self.services.log(&self.module_id, level, line);
    }
    pub fn config_get(&self, key: &str) -> Option<String> {
        self.decl.config_default(key)
    }
    pub fn environment(&self) -> String {
        self.services.environment()
    }
    pub fn endpoint(&self, name: &str) -> Option<String> {
        self.decl.endpoint(name, &self.services.environment())
    }

    // ---- settings ----

    fn setting_key(&self, key: &str) -> Result<String, String> {
        if key.is_empty() || key.contains(char::is_whitespace) {
            return Err(format!("setting key '{key}' is empty or has whitespace"));
        }
        Ok(format!("{}.{key}", self.module_id))
    }
    pub fn setting_get(&self, key: &str) -> Result<Option<String>, String> {
        self.check("settings")?;
        Ok(self.services.setting_get(&self.setting_key(key)?))
    }
    pub fn setting_set(&self, key: &str, value: &str) -> Result<(), String> {
        self.check("settings")?;
        self.services.setting_set(&self.setting_key(key)?, value)
    }
    pub fn setting_remove(&self, key: &str) -> Result<(), String> {
        self.check("settings")?;
        self.services.setting_remove(&self.setting_key(key)?)
    }

    // ---- storage ----

    /// The table prefix this plugin's SQL may use.
    pub fn table_prefix(&self) -> String {
        format!("{}_", self.module_id.to_lowercase())
    }

    fn kv_ready(conn: &rusqlite::Connection) -> Result<(), String> {
        conn.execute_batch(&format!("CREATE TABLE IF NOT EXISTS {KV_TABLE} (module TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY (module, key))"))
            .map_err(|e| e.to_string())
    }

    pub fn kv_get(&self, key: &str) -> Result<Option<String>, String> {
        self.check("storage")?;
        let db = self.services.db();
        let conn = db.lock().map_err(|_| "storage is poisoned".to_string())?;
        Self::kv_ready(&conn)?;
        let mut st = conn.prepare(&format!("SELECT value FROM {KV_TABLE} WHERE module = ?1 AND key = ?2")).map_err(|e| e.to_string())?;
        let mut rows = st.query(rusqlite::params![self.module_id, key]).map_err(|e| e.to_string())?;
        match rows.next().map_err(|e| e.to_string())? {
            Some(r) => Ok(Some(r.get(0).map_err(|e| e.to_string())?)),
            None => Ok(None),
        }
    }
    pub fn kv_set(&self, key: &str, value: &str) -> Result<(), String> {
        self.check("storage")?;
        let db = self.services.db();
        let conn = db.lock().map_err(|_| "storage is poisoned".to_string())?;
        Self::kv_ready(&conn)?;
        conn.execute(&format!("INSERT INTO {KV_TABLE} (module, key, value) VALUES (?1, ?2, ?3) ON CONFLICT(module, key) DO UPDATE SET value = excluded.value"), rusqlite::params![self.module_id, key, value])
            .map(|_| ())
            .map_err(|e| e.to_string())
    }
    pub fn kv_delete(&self, key: &str) -> Result<bool, String> {
        self.check("storage")?;
        let db = self.services.db();
        let conn = db.lock().map_err(|_| "storage is poisoned".to_string())?;
        Self::kv_ready(&conn)?;
        conn.execute(&format!("DELETE FROM {KV_TABLE} WHERE module = ?1 AND key = ?2"), rusqlite::params![self.module_id, key]).map(|n| n > 0).map_err(|e| e.to_string())
    }
    pub fn kv_keys(&self, prefix: &str) -> Result<Vec<String>, String> {
        self.check("storage")?;
        let db = self.services.db();
        let conn = db.lock().map_err(|_| "storage is poisoned".to_string())?;
        Self::kv_ready(&conn)?;
        let mut st = conn.prepare(&format!("SELECT key FROM {KV_TABLE} WHERE module = ?1 AND substr(key, 1, length(?2)) = ?2 ORDER BY key")).map_err(|e| e.to_string())?;
        let keys = st.query_map(rusqlite::params![self.module_id, prefix], |r| r.get::<_, String>(0)).map_err(|e| e.to_string())?;
        keys.collect::<Result<Vec<_>, _>>().map_err(|e| e.to_string())
    }

    /// Runs `f` with an authorizer that lets the statement touch only this plugin's tables.
    fn with_own_tables<T>(&self, f: impl FnOnce(&rusqlite::Connection) -> Result<T, String>) -> Result<T, String> {
        self.check("storage")?;
        let db = self.services.db();
        let conn = db.lock().map_err(|_| "storage is poisoned".to_string())?;
        let prefix = self.table_prefix();
        let adopt: Vec<String> = if self.tier == Tier::Community { Vec::new() } else { self.decl.storage.adopt.iter().map(|t| t.to_lowercase()).collect() };
        let refused: Arc<Mutex<Option<String>>> = Arc::default();
        let why = refused.clone();
        conn.authorizer(Some(move |ctx: AuthContext<'_>| {
            let own = |t: &str| {
                let t = t.to_lowercase();
                t.starts_with(&prefix) || adopt.contains(&t) || matches!(t.as_str(), "sqlite_master" | "sqlite_schema" | "sqlite_sequence" | "sqlite_temp_master")
            };
            let table = match ctx.action {
                AuthAction::Select | AuthAction::Function { .. } | AuthAction::Recursive | AuthAction::Transaction { .. } | AuthAction::Savepoint { .. } => return Authorization::Allow,
                AuthAction::CreateIndex { table_name, .. }
                | AuthAction::CreateTable { table_name }
                | AuthAction::CreateTrigger { table_name, .. }
                | AuthAction::Delete { table_name }
                | AuthAction::DropIndex { table_name, .. }
                | AuthAction::DropTable { table_name }
                | AuthAction::DropTrigger { table_name, .. }
                | AuthAction::Insert { table_name }
                | AuthAction::Read { table_name, .. }
                | AuthAction::Update { table_name, .. }
                | AuthAction::AlterTable { table_name, .. }
                | AuthAction::Analyze { table_name } => table_name,
                AuthAction::Pragma { pragma_name, .. } => {
                    *why.lock().unwrap() = Some(format!("PRAGMA {pragma_name} is not allowed"));
                    return Authorization::Deny;
                }
                _ => {
                    *why.lock().unwrap() = Some("that statement is not allowed on plugin storage".into());
                    return Authorization::Deny;
                }
            };
            if ctx.database_name.is_some_and(|d| d != "main" && d != "temp") || !own(table) {
                *why.lock().unwrap() = Some(format!("table '{table}' is not this plugin's (its tables start with '{prefix}')"));
                return Authorization::Deny;
            }
            Authorization::Allow
        }));
        let out = f(&conn);
        conn.authorizer(None::<fn(AuthContext<'_>) -> Authorization>);
        let reason = refused.lock().unwrap().take();
        match (out, reason) {
            (Err(_), Some(r)) => Err(format!("storage refused: {r}")),
            (o, _) => o,
        }
    }

    pub fn execute(&self, sql: &str, params: &[SqlValue]) -> Result<u64, String> {
        self.with_own_tables(|conn| {
            let mut st = conn.prepare(sql).map_err(|e| e.to_string())?;
            st.execute(rusqlite::params_from_iter(params.iter().map(to_sql))).map(|n| n as u64).map_err(|e| e.to_string())
        })
    }

    pub fn query(&self, sql: &str, params: &[SqlValue]) -> Result<SqlRows, String> {
        self.with_own_tables(|conn| {
            let mut st = conn.prepare(sql).map_err(|e| e.to_string())?;
            let columns: Vec<String> = st.column_names().iter().map(|c| c.to_string()).collect();
            let n = columns.len();
            let mut rows = st.query(rusqlite::params_from_iter(params.iter().map(to_sql))).map_err(|e| e.to_string())?;
            let mut out = Vec::new();
            while let Some(r) = rows.next().map_err(|e| e.to_string())? {
                let mut row = Vec::with_capacity(n);
                for i in 0..n {
                    row.push(match r.get_ref(i).map_err(|e| e.to_string())? {
                        rusqlite::types::ValueRef::Null => SqlValue::Null,
                        rusqlite::types::ValueRef::Integer(v) => SqlValue::Integer(v),
                        rusqlite::types::ValueRef::Real(v) => SqlValue::Real(v),
                        rusqlite::types::ValueRef::Text(t) => SqlValue::Text(String::from_utf8_lossy(t).into_owned()),
                        rusqlite::types::ValueRef::Blob(b) => SqlValue::Blob(b.to_vec()),
                    });
                }
                out.push(row);
            }
            Ok(SqlRows { columns, rows: out })
        })
    }

    // ---- events ----

    pub fn publish(&self, name: &str, payload: &str, cause_id: Option<&str>) -> Result<NamedEvent, String> {
        self.check("events")?;
        self.services.publish(&self.module_id, name, payload, cause_id)
    }
    pub fn subscribe(&self, pattern: &str) -> Result<u64, String> {
        self.check("events")?;
        if pattern.is_empty() {
            return Err("an empty event pattern".into());
        }
        let id = self.next_subscription.fetch_add(1, Ordering::Relaxed);
        self.subscriptions.lock().unwrap().push((id, pattern.into()));
        Ok(id)
    }
    pub fn unsubscribe(&self, id: u64) {
        self.subscriptions.lock().unwrap().retain(|(i, _)| *i != id);
    }
    pub fn broadcast(&self, r#type: &str, data: &str) -> Result<(), String> {
        self.check("events")?;
        self.services.broadcast(&self.module_id, r#type, data);
        Ok(())
    }
    /// The subscription ids whose pattern matches `name`.
    pub fn matching_subscriptions(&self, name: &str) -> Vec<u64> {
        self.subscriptions.lock().unwrap().iter().filter(|(_, p)| glob(p, name)).map(|(i, _)| *i).collect()
    }
    /// Drops every subscription (the instance is being replaced).
    pub fn clear_subscriptions(&self) {
        self.subscriptions.lock().unwrap().clear();
    }

    // ---- http, frames ----

    pub fn http_send(&self, request: HttpRequest) -> Result<HttpResponse, String> {
        self.check("http")?;
        let allowed = self.decl.endpoint_addresses();
        let ok = allowed.iter().any(|base| url_under(&request.url, base));
        if !ok {
            return Err(format!("http refused: {} is not one of {}'s daemon.endpoints", origin_of(&request.url), self.module_id));
        }
        self.services.http_send(&self.module_id, request)
    }

    /// Marks `ctx` as the caller this plugin works for until the guard drops: a frame the plugin asks meanwhile is
    /// scoped to it. A WASM instance runs one call at a time, so the innermost entry is the running call; a sidecar
    /// that runs calls side by side is scoped to the latest one, never to none (the stricter reading).
    pub fn acting_for(&self, ctx: CallContext) -> ActingFor<'_> {
        self.active.lock().unwrap().push(ctx);
        ActingFor(self)
    }

    /// The caller of the innermost running call, None outside any call.
    pub fn caller(&self) -> Option<CallContext> {
        self.active.lock().unwrap().last().cloned()
    }

    pub fn frame_request(&self, r#type: &str, data: &str, session_id: Option<String>, timeout_ms: u32) -> Result<Frame, String> {
        self.check("frames")?;
        let caller = self.caller();
        self.services.frame_request(&self.module_id, Frame { r#type: r#type.into(), data: data.into(), session_id, request_id: None }, caller.as_ref(), timeout_ms)
    }

    /// One sidecar `import` line: `<interface>.<func>` with its JSON params; the JSON ok value or the error.
    pub fn dispatch_json(&self, name: &str, params: Value) -> Result<Value, String> {
        fn p<T: serde::de::DeserializeOwned>(v: Value) -> Result<T, String> {
            serde_json::from_value(v).map_err(|e| format!("bad params: {e}"))
        }
        fn to<T: serde::Serialize>(v: T) -> Result<Value, String> {
            serde_json::to_value(v).map_err(|e| e.to_string())
        }
        let iface = name.split('.').next().unwrap_or_default();
        if !self.linked(iface) {
            return Err(format!("{iface} is not linked for {} (module.json daemon.needs/wants does not request it)", self.module_id));
        }
        match name {
            "log.write" => {
                let w: jsonl::LogWrite = p(params)?;
                self.log(w.level, &w.line);
                Ok(Value::Null)
            }
            "config.module-id" => Ok(json!(self.module_id)),
            "config.tier" => Ok(json!(self.tier.as_str())),
            "config.api-version" => Ok(json!(infantry_plugin_wit::API_VERSION)),
            "config.environment" => Ok(json!(self.environment())),
            "config.get" => Ok(json!(self.config_get(&p::<jsonl::KeyParam>(params)?.key))),
            "config.endpoint" => Ok(json!(self.endpoint(&p::<jsonl::NameParam>(params)?.name))),
            "settings.get" => to(self.setting_get(&p::<jsonl::KeyParam>(params)?.key)?),
            "settings.set" => {
                let kv: jsonl::KeyValue = p(params)?;
                self.setting_set(&kv.key, &kv.value).map(|_| Value::Null)
            }
            "settings.remove" => self.setting_remove(&p::<jsonl::KeyParam>(params)?.key).map(|_| Value::Null),
            "storage.kv-get" => to(self.kv_get(&p::<jsonl::KeyParam>(params)?.key)?),
            "storage.kv-set" => {
                let kv: jsonl::KeyValue = p(params)?;
                self.kv_set(&kv.key, &kv.value).map(|_| Value::Null)
            }
            "storage.kv-delete" => to(self.kv_delete(&p::<jsonl::KeyParam>(params)?.key)?),
            "storage.kv-keys" => to(self.kv_keys(&p::<jsonl::PrefixParam>(params)?.prefix)?),
            "storage.execute" => {
                let s: jsonl::SqlParams = p(params)?;
                to(self.execute(&s.sql, &s.params)?)
            }
            "storage.query" => {
                let s: jsonl::SqlParams = p(params)?;
                to(self.query(&s.sql, &s.params)?)
            }
            "events.publish" => {
                let e: jsonl::PublishParams = p(params)?;
                to(self.publish(&e.name, &e.payload, e.cause_id.as_deref())?)
            }
            "events.subscribe" => to(self.subscribe(&p::<jsonl::PatternParam>(params)?.pattern)?),
            "events.unsubscribe" => {
                self.unsubscribe(p::<jsonl::IdParam>(params)?.id);
                Ok(Value::Null)
            }
            "events.broadcast" => {
                let b: jsonl::BroadcastParams = p(params)?;
                self.broadcast(&b.r#type, &b.data).map(|_| Value::Null)
            }
            "http.send" => to(self.http_send(p::<jsonl::HttpSendParams>(params)?.request)?),
            "frames.request" => {
                let f: jsonl::FrameRequestParams = p(params)?;
                to(self.frame_request(&f.r#type, &f.data, f.session_id, f.timeout_ms)?)
            }
            other => Err(format!("unknown import '{other}'")),
        }
    }
}

fn to_sql(v: &SqlValue) -> rusqlite::types::Value {
    match v {
        SqlValue::Null => rusqlite::types::Value::Null,
        SqlValue::Integer(i) => rusqlite::types::Value::Integer(*i),
        SqlValue::Real(r) => rusqlite::types::Value::Real(*r),
        SqlValue::Text(t) => rusqlite::types::Value::Text(t.clone()),
        SqlValue::Blob(b) => rusqlite::types::Value::Blob(b.clone()),
    }
}

/// `scheme://host[:port]` of a URL (lower-cased), or the whole text when it has no scheme.
pub fn origin_of(url: &str) -> String {
    let Some((scheme, rest)) = url.split_once("://") else { return url.to_lowercase() };
    let host = rest.split(['/', '?', '#']).next().unwrap_or_default();
    format!("{}://{}", scheme.to_lowercase(), host.to_lowercase())
}

/// Whether `url` is on the same origin as `base` and under its path.
pub fn url_under(url: &str, base: &str) -> bool {
    if origin_of(url) != origin_of(base) || !url.contains("://") {
        return false;
    }
    let path = |u: &str| -> String {
        let rest = u.split_once("://").map(|x| x.1).unwrap_or_default();
        let p = rest.find('/').map(|i| &rest[i..]).unwrap_or("/");
        p.split(['?', '#']).next().unwrap_or("/").to_string()
    };
    let bp = path(base);
    let bp = bp.trim_end_matches('/');
    let up = path(url);
    bp.is_empty() || up == bp || up.starts_with(&format!("{bp}/"))
}

/// `*` matches any run of characters (event-name globs like `ticket.*`).
pub fn glob(pattern: &str, name: &str) -> bool {
    let parts: Vec<&str> = pattern.split('*').collect();
    if parts.len() == 1 {
        return pattern == name;
    }
    let mut rest = name;
    for (i, part) in parts.iter().enumerate() {
        if i == 0 {
            match rest.strip_prefix(part) {
                Some(r) => rest = r,
                None => return false,
            }
        } else if i == parts.len() - 1 {
            return rest.ends_with(part);
        } else {
            match rest.find(part) {
                Some(at) => rest = &rest[at + part.len()..],
                None => return false,
            }
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::services::MemoryServices;

    fn imports(grants: &[&str]) -> PluginImports {
        let decl = DaemonDecl { endpoints: [("api".to_string(), [("prod".to_string(), "https://api.example/v1".to_string())].into())].into(), ..Default::default() };
        PluginImports::new("Acme_X", Tier::Official, decl, Arc::new(MemoryServices::new()), grants.iter().map(|s| s.to_string()).collect(), BTreeSet::new())
    }

    #[test]
    fn sql_touches_only_the_plugins_own_tables() {
        let i = imports(&["storage"]);
        i.execute("CREATE TABLE acme_x_notes (id INTEGER PRIMARY KEY, body TEXT)", &[]).unwrap();
        assert_eq!(i.execute("INSERT INTO acme_x_notes (body) VALUES (?1)", &[SqlValue::Text("hi".into())]).unwrap(), 1);
        let rows = i.query("SELECT body FROM acme_x_notes", &[]).unwrap();
        assert_eq!(rows.rows, vec![vec![SqlValue::Text("hi".into())]]);
        i.kv_set("k", "v").unwrap();
        let e = i.query("SELECT * FROM plugin_kv", &[]).unwrap_err();
        assert!(e.contains("not this plugin's"), "{e}");
        let e = i.execute("CREATE TABLE other_t (x)", &[]).unwrap_err();
        assert!(e.contains("not this plugin's"), "{e}");
        assert!(i.execute("PRAGMA writable_schema = 1", &[]).is_err());
        assert_eq!(i.kv_get("k").unwrap().as_deref(), Some("v"));
        assert_eq!(i.kv_keys("").unwrap(), vec!["k"]);
        assert!(i.kv_delete("k").unwrap());
    }

    #[test]
    fn ungranted_interfaces_fail_and_http_is_filtered_to_endpoints() {
        let i = imports(&["http"]);
        assert!(i.setting_get("x").unwrap_err().contains("not granted"));
        let req = |url: &str| HttpRequest { method: "GET".into(), url: url.into(), ..Default::default() };
        assert!(i.http_send(req("https://evil.example/v1")).unwrap_err().contains("http refused"));
        assert!(i.http_send(req("https://api.example/v2")).unwrap_err().contains("http refused"));
        // Allowed by the filter; MemoryServices has no network, so its own error comes back.
        assert!(i.http_send(req("https://api.example/v1/things?q=1")).unwrap_err().contains("no network"));
        assert!(glob("ticket.*", "ticket.moved") && !glob("ticket.*", "session.ended") && glob("*", "x"));
    }
}
