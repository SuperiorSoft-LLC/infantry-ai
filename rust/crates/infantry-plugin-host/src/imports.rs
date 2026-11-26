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
    /// A declared config key: the owner's stored value (Settings), else the manifest default.
    pub fn config_get(&self, key: &str) -> Option<String> {
        self.declared_value(key).or_else(|| self.decl.config_default(key))
    }
    /// The stored app-scope value of `key`, only when this plugin declares `key` (a plugin reads no one else's setting).
    fn declared_value(&self, key: &str) -> Option<String> {
        let declared = self.decl.config_fields().any(|c| c.get("key").and_then(|k| k.as_str()) == Some(key));
        declared.then(|| self.services.app_setting(key)).flatten()
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
        // The plugin's own value wins; else what the owner set in Settings for a key the plugin declares.
        Ok(self.services.setting_get(&self.setting_key(key)?).or_else(|| self.declared_value(key)))
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
    /// PLUGIN-BROADCAST-1: pushes a frame to every client, only of a type this module declared in `daemon.pushFrames`
    /// and never a core type (the daemon stamps the source module on the envelope).
    pub fn broadcast(&self, r#type: &str, data: &str) -> Result<(), String> {
        self.check("events")?;
        if reserved_push_type(self.tier, r#type) {
            return Err(format!("broadcast refused: '{}' is a core frame type; {} may not push it", r#type, self.module_id));
        }
        if !self.decl.push_frames.iter().any(|f| f == r#type) {
            return Err(format!("broadcast refused: '{}' is not in {}'s daemon.pushFrames", r#type, self.module_id));
        }
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
        // Self-declared, so honoured only above Community tier (as storage.adopt is): a community plugin cannot grant itself open egress.
        let runtime = self.decl.http_runtime_hosts && self.tier != Tier::Community && request.url.get(..8).is_some_and(|p| p.eq_ignore_ascii_case("https://"));
        if allowed.iter().any(|base| url_under(&request.url, base)) {
            return self.services.http_send(&self.module_id, request);
        }
        if !runtime {
            return Err(format!("http refused: {} is not one of {}'s daemon.endpoints", origin_of(&request.url), self.module_id));
        }
        self.http_send_runtime(request)
    }

    /// The runtime-hosts grant: public https hosts only, every redirect hop checked again (no SSRF into the
    /// owner's machine, LAN, tailnet or cloud metadata).
    fn http_send_runtime(&self, mut request: HttpRequest) -> Result<HttpResponse, String> {
        for _ in 0..=RUNTIME_HTTP_MAX_REDIRECTS {
            if !request.url.get(..8).is_some_and(|p| p.eq_ignore_ascii_case("https://")) {
                return Err(format!("http refused: {} is not https", origin_of(&request.url)));
            }
            public_https_host(&request.url)?;
            let resp = self.services.http_send_no_redirect(&self.module_id, request.clone())?;
            let location = resp.headers.iter().find(|(k, _)| k.eq_ignore_ascii_case("location")).map(|(_, v)| v.clone());
            match location {
                Some(loc) if (300..400).contains(&resp.status) => {
                    request.url = if loc.contains("://") {
                        loc
                    } else if loc.starts_with('/') && !loc.starts_with("//") {
                        format!("{}{loc}", origin_of(&request.url))
                    } else {
                        return Err(format!("http refused: redirect to {loc}"));
                    };
                    if resp.status != 307 && resp.status != 308 {
                        request.method = "GET".into();
                        request.body = None;
                    }
                }
                _ => return Ok(resp),
            }
        }
        Err(format!("http refused: more than {RUNTIME_HTTP_MAX_REDIRECTS} redirects"))
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
        // The owner's authority is an explicit, per-frame-type grant of the manifest (`daemon.ownerFrames`), official and
        // core modules only; the `frames` grant alone sends every frame as a non-owner.
        let as_owner = self.tier != Tier::Community && self.decl.owner_frames.iter().any(|f| f == r#type);
        if let Some(why) = undeclared_secret(&self.module_id, &self.decl.secrets, r#type, data) {
            return Err(why);
        }
        self.services.frame_request(&self.module_id, Frame { r#type: r#type.into(), data: data.into(), session_id, request_id: None }, caller.as_ref(), as_owner, timeout_ms)
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
/// Redirect hops a runtime-hosts fetch follows.
const RUNTIME_HTTP_MAX_REDIRECTS: usize = 5;

/// Ok when every address `url`'s host resolves to is public. Refuses loopback, private (RFC1918, fc00::/7),
/// link-local (169.254/16, fe80::/10), unspecified, CGNAT (100.64/10, Tailscale), broadcast and multicast.
pub fn public_https_host(url: &str) -> Result<(), String> {
    use std::net::{IpAddr, ToSocketAddrs};
    let rest = &url[url.find("://").map(|i| i + 3).unwrap_or(0)..];
    let auth = rest.split(['/', '?', '#']).next().unwrap_or_default();
    let auth = auth.rsplit('@').next().unwrap_or_default();
    let (host, port) = if let Some(h) = auth.strip_prefix('[') {
        let (h, tail) = h.split_once(']').ok_or("http refused: bad host")?;
        (h.to_string(), tail.strip_prefix(':').and_then(|p| p.parse().ok()).unwrap_or(443u16))
    } else {
        match auth.rsplit_once(':') {
            Some((h, p)) => (h.to_string(), p.parse().map_err(|_| "http refused: bad port".to_string())?),
            None => (auth.to_string(), 443),
        }
    };
    let host = host.trim_end_matches('.').to_ascii_lowercase();
    if host.is_empty() || host == "localhost" || host.ends_with(".localhost") {
        return Err(format!("http refused: {host} is not a public host"));
    }
    fn public(ip: IpAddr) -> bool {
        match ip {
            IpAddr::V4(v) => {
                let o = v.octets();
                !(v.is_loopback() || v.is_private() || v.is_link_local() || v.is_unspecified() || v.is_broadcast() || v.is_multicast() || o[0] == 0 || (o[0] == 100 && (o[1] & 0xc0) == 64))
            }
            IpAddr::V6(v) => {
                if let Some(m) = v.to_ipv4_mapped() {
                    return public(IpAddr::V4(m));
                }
                let s = v.segments();
                !(v.is_loopback() || v.is_unspecified() || v.is_multicast() || (s[0] & 0xfe00) == 0xfc00 || (s[0] & 0xffc0) == 0xfe80)
            }
        }
    }
    let addrs: Vec<IpAddr> = match host.parse::<IpAddr>() {
        Ok(ip) => vec![ip],
        Err(_) => (host.as_str(), port).to_socket_addrs().map_err(|e| format!("http refused: {host} does not resolve: {e}"))?.map(|a| a.ip()).collect(),
    };
    if addrs.is_empty() || addrs.iter().any(|a| !public(*a)) {
        return Err(format!("http refused: {host} is not a public host"));
    }
    Ok(())
}

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

/// Frame-type families that only core may push to clients: sessions and their output, tickets, auth and pairing,
/// agents, prompts, the vault, plugin admin, settings. No plugin of any tier may push them.
const CORE_PUSH_PREFIXES: &[&str] = &[
    "session", "ticket", "auth", "token", "pair", "hello", "ready", "error", "stdout", "stderr", "exit", "prompt", "assistant",
    "turn", "agent", "queue", "run", "vault", "secret", "plugin", "mcp", "settings", "project", "account", "update", "installer",
];

/// Whether a plugin of `tier` may never push frame type `t` (PLUGIN-BROADCAST-1). Core families are refused for every
/// tier; a community module may in addition push no name of the core protocol at all (`infantry_protocol::frames::ALL`).
pub fn reserved_push_type(tier: Tier, t: &str) -> bool {
    let t = t.trim().to_ascii_lowercase();
    if t.is_empty() || CORE_PUSH_PREFIXES.iter().any(|p| t.starts_with(p)) || crate::host::CORE_FRAMES.contains(&t.as_str()) {
        return true;
    }
    tier == Tier::Community && infantry_protocol::frames::ALL.contains(&t.as_str())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::services::MemoryServices;

    fn pusher(tier: Tier, grants: &[&str], push: &[&str]) -> (PluginImports, Arc<MemoryServices>) {
        let decl = DaemonDecl { push_frames: push.iter().map(|s| s.to_string()).collect(), ..Default::default() };
        let svc = Arc::new(MemoryServices::new());
        (PluginImports::new("Acme_X", tier, decl, svc.clone(), grants.iter().map(|s| s.to_string()).collect(), BTreeSet::new()), svc)
    }

    #[test]
    fn broadcast_is_scoped_to_declared_non_core_types() {
        let (i, svc) = pusher(Tier::Official, &["events"], &["acme_x_item", "ticket_list", "sessions"]);
        i.broadcast("acme_x_item", "{}").unwrap();
        // Core types are refused even when declared.
        for t in ["ticket_list", "sessions", "session_state", "auth_ok", "ready", "error", "plugin_tools", "SESSIONS"] {
            assert!(i.broadcast(t, "{}").unwrap_err().contains("core frame type"), "{t}");
        }
        assert!(i.broadcast("acme_x_other", "{}").unwrap_err().contains("daemon.pushFrames"));
        assert_eq!(svc.broadcasts.lock().unwrap().len(), 1);
        // Without the events grant: refused.
        let (i, svc) = pusher(Tier::Official, &[], &["acme_x_item"]);
        assert!(i.broadcast("acme_x_item", "{}").unwrap_err().contains("not granted"));
        assert!(svc.broadcasts.lock().unwrap().is_empty());
        // Community: no core-protocol name at all, even one an official plugin may push.
        let (i, _) = pusher(Tier::Community, &["events"], &["trigger", "acme_x_item"]);
        assert!(i.broadcast("trigger", "{}").unwrap_err().contains("core frame type"));
        i.broadcast("acme_x_item", "{}").unwrap();
        assert!(!reserved_push_type(Tier::Official, "trigger") && !reserved_push_type(Tier::Official, "decision_item"));
    }

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
    fn a_setting_the_owner_stored_reaches_the_plugin_that_declares_it() {
        let decl = DaemonDecl {
            config: vec![serde_json::json!({"node":"Acme_X.ssh","fields":[{"key":"limits.x.connectTimeoutSec","type":"number","default":15,"scope":"daemon"}]})],
            ..Default::default()
        };
        let svc = Arc::new(MemoryServices::new());
        let i = PluginImports::new("Acme_X", Tier::Official, decl, svc.clone(), ["settings".to_string()].into(), BTreeSet::new());
        assert_eq!(i.setting_get("limits.x.connectTimeoutSec").unwrap(), None); // nothing stored: the plugin uses its default
        assert_eq!(i.config_get("limits.x.connectTimeoutSec").as_deref(), Some("15"));
        svc.app_settings.lock().unwrap().insert("limits.x.connectTimeoutSec".into(), "42".into());
        svc.app_settings.lock().unwrap().insert("other.key".into(), "9".into());
        assert_eq!(i.setting_get("limits.x.connectTimeoutSec").unwrap().as_deref(), Some("42"));
        assert_eq!(i.config_get("limits.x.connectTimeoutSec").as_deref(), Some("42"));
        assert_eq!(i.setting_get("other.key").unwrap(), None); // a key the plugin did not declare is not its to read
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

/// PLUGIN-GAPS-1: `daemon.secrets` is the list of vault fields a module may read, write or clear. A module that lists
/// any is refused a `plugin_secret_get` / `_set` / `_clear` of a field the list does not name; an empty list (or none)
/// leaves the field free, as before. The vault rules (own entry only, no `INFANTRY_` field) apply either way.
pub fn undeclared_secret(module: &str, declared: &[String], r#type: &str, data: &str) -> Option<String> {
    use infantry_protocol::frames::{PLUGIN_SECRET_CLEAR, PLUGIN_SECRET_GET, PLUGIN_SECRET_SET};
    if declared.is_empty() || ![PLUGIN_SECRET_GET, PLUGIN_SECRET_SET, PLUGIN_SECRET_CLEAR].contains(&r#type) {
        return None;
    }
    let field = serde_json::from_str::<serde_json::Value>(data).ok().and_then(|v| v.get("field").and_then(|f| f.as_str()).map(|f| f.trim().to_string())).unwrap_or_default();
    if declared.iter().any(|d| d.trim() == field) {
        return None;
    }
    Some(format!("{type} refused: field '{field}' is not in daemon.secrets of {module} ({})", declared.join(", "), type = r#type))
}

#[cfg(test)]
mod secrets_tests {
    use super::undeclared_secret;

    #[test]
    fn daemon_secrets_limits_the_vault_fields_a_module_reaches() {
        let declared = vec!["API_KEY".to_string()];
        assert_eq!(undeclared_secret("Acme", &declared, "plugin_secret_get", r#"{"field":"API_KEY"}"#), None);
        let why = undeclared_secret("Acme", &declared, "plugin_secret_set", r#"{"field":"OTHER","value":"x"}"#).unwrap();
        assert!(why.contains("not in daemon.secrets of Acme"), "{why}");
        assert!(undeclared_secret("Acme", &declared, "plugin_secret_clear", "{}").is_some(), "no field is not a declared field");
        assert_eq!(undeclared_secret("Acme", &[], "plugin_secret_get", r#"{"field":"ANY"}"#), None, "an empty list leaves the field free");
        assert_eq!(undeclared_secret("Acme", &declared, "ticket_list", "{}"), None, "other frames are not touched");
#[cfg(test)]
mod runtime_hosts_tests {
    use super::*;
    use crate::services::MemoryServices;

    fn cards_like(runtime: bool) -> PluginImports {
        let mut decl: DaemonDecl = serde_json::from_str(r#"{"wants":["http"]}"#).unwrap();
        if runtime {
            decl = serde_json::from_str(r#"{"wants":["http"],"httpRuntimeHosts":true}"#).unwrap();
        }
        PluginImports::new("Infantry_Cards", Tier::Official, decl, Arc::new(MemoryServices::new()), ["http".to_string()].into(), BTreeSet::new())
    }
    fn get(url: &str) -> HttpRequest {
        HttpRequest { method: "GET".into(), url: url.into(), ..Default::default() }
    }

    #[test]
    fn runtime_hosts_refuse_internal_hosts() {
        let i = cards_like(true);
        for u in ["https://127.0.0.1/x", "https://localhost/x", "https://10.0.0.5/x", "https://169.254.169.254/latest/meta-data", "https://100.100.100.100/", "https://192.168.1.2:8443/", "https://[::1]/", "https://[fe80::1]/", "https://[fd00::1]/", "https://[::ffff:127.0.0.1]/", "https://0.0.0.0/"] {
            assert!(i.http_send(get(u)).unwrap_err().contains("not a public host"), "{u}");
        }
        assert!(public_https_host("https://8.8.8.8/x").is_ok());
    }

    #[test]
    fn runtime_hosts_refuse_redirect_to_internal_host() {
        let s = MemoryServices::new().with_http(|r: HttpRequest| {
            assert!(r.url.contains("8.8.8.8"), "followed into {}", r.url);
            Ok(HttpResponse { status: 302, headers: vec![("Location".into(), "https://169.254.169.254/".into())], body: vec![] })
        });
        let decl: DaemonDecl = serde_json::from_str(r#"{"wants":["http"],"httpRuntimeHosts":true}"#).unwrap();
        let i = PluginImports::new("Infantry_Cards", Tier::Official, decl, Arc::new(s), ["http".to_string()].into(), BTreeSet::new());
        assert!(i.http_send(get("https://8.8.8.8/img")).unwrap_err().contains("not a public host"));
    }

    #[test]
    fn runtime_hosts_grant_lets_https_through_and_nothing_else() {
        let fav = "https://8.8.8.8/s2/favicons?domain=reuters.com&sz=64"; // an IP: no DNS in the test
        // No grant: refused (CARDS-THUMBS-1).
        assert!(cards_like(false).http_send(get(fav)).unwrap_err().contains("http refused"));
        // Grant: passes the filter (MemoryServices has no network).
        let i = cards_like(true);
        assert!(i.http_send(get(fav)).unwrap_err().contains("no network"));
        assert!(i.http_send(get("http://plain.example/x.png")).unwrap_err().contains("http refused"));
        // A community plugin declaring the flag gets nothing (PM review: self-declared grant).
        let decl: DaemonDecl = serde_json::from_str(r#"{"wants":["http"],"httpRuntimeHosts":true}"#).unwrap();
        let c = PluginImports::new("Evil", Tier::Community, decl, Arc::new(MemoryServices::new()), ["http".to_string()].into(), BTreeSet::new());
        assert!(c.http_send(get(fav)).unwrap_err().contains("http refused"));
    }
}
