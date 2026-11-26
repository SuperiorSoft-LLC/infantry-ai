// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The account hub (Kotlin `Infantry_Account`: `AccountService` + `Provider`), generic: it knows a provider only by the
//! descriptor a module declares (`module.json` `account.providers`, the `describe` shape: id, name, icon, signIn
//! {method: device_code, clientId, scopes, deviceAuthorizationUrl, tokenUrl, revokeUrl}, accountUrl, billingUrl,
//! signUpUrl, consumers, services). Sign-in is the OAuth device-code flow; the refresh token is kept in the vault
//! (keystore entry [`SECRET`], field `acct_<provider>_refresh`), the access token in memory only. Every URL is https (or
//! http to this machine, a local test provider): a token never travels in clear text.
//!
//! Frames: `account_status` `{provider?, refresh?}` -> `account_status` (`{providers:[...]}` plus the first provider's
//! state, email, plan, message and services at the top, what the Account panel shows); `account_sign_in` `{provider}`
//! -> `account_sign_in` `{id, provider, userCode, verificationUri, expiresAt}` (owner only; the flow is polled in the
//! background); `account_sign_out` `{provider}` -> `account_sign_out` `{wasSignedIn}` (owner only).
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::Path;
use std::sync::{Arc, Mutex};

pub mod frames {
    pub const STATUS: &str = "account_status";
    pub const SIGN_IN: &str = "account_sign_in";
    pub const SIGN_OUT: &str = "account_sign_out";
    /// `account_token {provider}` -> `{accessToken, expiresAt}`: answered only to a plugin module the provider lists in
    /// `consumers` (pinned by the plugin host, see `Account::token_for`); over the wire it is always refused.
    pub const TOKEN: &str = "account_token";
    pub const ALL: &[&str] = &[STATUS, SIGN_IN, SIGN_OUT, TOKEN];
}

/// The keystore entry holding every provider's refresh token (one field per provider).
pub const SECRET: &str = "infantry-account";
/// How long an account document is reused before it is read again (`account.cacheSeconds`).
pub const CACHE_MS: i64 = 30_000;
/// The poll interval when the provider names none (RFC 8628 default).
pub const POLL_DEFAULT_SECS: u64 = 5;
/// The core setting naming this device on a provider's approve page (default: "Infantry on <hostname>").
pub const DEVICE_NAME_KEY: &str = "account.deviceName";
/// The longest device name sent.
pub const DEVICE_NAME_MAX: usize = 80;
/// The device-code grant (RFC 8628).
pub const DEVICE_GRANT: &str = "urn:ietf:params:oauth:grant-type:device_code";
const ID_MAX: usize = 40;

#[derive(Debug, Clone, PartialEq)]
pub struct Service {
    pub id: String,
    pub name: String,
    pub entitlement: String,
    pub section: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Provider {
    pub id: String,
    pub name: String,
    pub icon: String,
    pub description: Option<String>,
    pub client_id: String,
    pub scopes: Vec<String>,
    pub device_url: String,
    pub token_url: String,
    pub revoke_url: Option<String>,
    pub account_url: Option<String>,
    pub billing_url: Option<String>,
    pub sign_up_url: Option<String>,
    pub services: Vec<Service>,
    /// The plugin module ids allowed to read this provider's access token (`account_token`).
    pub consumers: Vec<String>,
}

/// https, or http to this machine only.
pub fn url_ok(u: &str) -> bool {
    if u.starts_with("https://") {
        return true;
    }
    let Some(rest) = u.strip_prefix("http://") else { return false };
    let host = rest.split(['/', '?']).next().unwrap_or("");
    let host = if host.starts_with('[') { host.split(']').next().map(|h| format!("{h}]")).unwrap_or_default() } else { host.split(':').next().unwrap_or("").to_string() };
    matches!(host.as_str(), "127.0.0.1" | "localhost" | "[::1]")
}

fn s(v: &Value, k: &str) -> Option<String> {
    v.get(k).and_then(Value::as_str).map(str::trim).filter(|x| !x.is_empty()).map(String::from)
}

impl Provider {
    pub fn refresh_field(&self) -> String {
        format!("acct_{}_refresh", self.id)
    }

    /// Parses a descriptor; the error names what is wrong, never a secret.
    pub fn parse(v: &Value) -> Result<Provider, String> {
        let id = s(v, "id").ok_or("no id")?;
        let id_ok = id.len() <= ID_MAX && id.chars().next().is_some_and(|c| c.is_ascii_lowercase()) && id.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_');
        if !id_ok {
            return Err(format!("id '{id}' must be lowercase letters, digits and _ (starting with a letter)"));
        }
        let sign = v.get("signIn").filter(|x| x.is_object()).ok_or("no signIn")?;
        let method = s(sign, "method").unwrap_or_else(|| "device_code".into());
        if method != "device_code" {
            return Err(format!("signIn.method '{method}' is not supported yet (device_code)"));
        }
        let url = |o: &Value, k: &str, req: bool| -> Result<Option<String>, String> {
            match s(o, k) {
                None if req => Err(format!("no {k}")),
                None => Ok(None),
                Some(u) if url_ok(&u) => Ok(Some(u)),
                Some(_) => Err(format!("{k} must be https")),
            }
        };
        let list = |o: &Value, k: &str| o.get(k).and_then(Value::as_array).into_iter().flatten().filter_map(|x| x.as_str().map(String::from)).collect::<Vec<_>>();
        Ok(Provider {
            name: s(v, "name").unwrap_or_else(|| id.clone()),
            icon: s(v, "icon").unwrap_or_else(|| "cloud".into()),
            description: s(v, "description"),
            client_id: s(sign, "clientId").ok_or("no signIn.clientId")?,
            scopes: list(sign, "scopes"),
            device_url: url(sign, "deviceAuthorizationUrl", true)?.unwrap_or_default(),
            token_url: url(sign, "tokenUrl", true)?.unwrap_or_default(),
            revoke_url: url(sign, "revokeUrl", false)?,
            account_url: url(v, "accountUrl", false)?,
            billing_url: url(v, "billingUrl", false)?,
            sign_up_url: url(v, "signUpUrl", false)?,
            consumers: list(v, "consumers").into_iter().map(|c| c.trim().to_string()).filter(|c| !c.is_empty()).collect(),
            services: v
                .get("services")
                .and_then(Value::as_array)
                .into_iter()
                .flatten()
                .filter_map(|x| {
                    let sid = s(x, "id")?;
                    Some(Service { name: s(x, "name").unwrap_or_else(|| sid.clone()), entitlement: s(x, "entitlement").unwrap_or_else(|| sid.clone()), section: s(x, "section"), id: sid })
                })
                .collect(),
            id,
        })
    }
}

/// "alice@gmail.com" -> "a***@gmail.com"; an already masked value is kept.
pub fn mask_email(v: &str) -> String {
    if v.contains('*') {
        return v.into();
    }
    let first: String = v.chars().take(1).collect();
    match v.find('@') {
        Some(at) if at > 0 => format!("{first}***{}", &v[at..]),
        _ => format!("{first}***"),
    }
}

/// HTTP as the hub uses it: (status, JSON body).
pub trait AccountHttp: Send + Sync {
    fn post_form(&self, url: &str, form: &[(&str, &str)]) -> Result<(u16, Value), String>;
    fn get_json(&self, url: &str, bearer: &str) -> Result<(u16, Value), String>;
}

/// Where refresh tokens live (the vault in the daemon).
pub trait TokenStore: Send + Sync {
    fn get(&self, field: &str) -> Option<String>;
    fn set(&self, field: &str, value: &str) -> Result<(), String>;
    fn delete(&self, field: &str) -> bool;
}

#[derive(Debug, Clone)]
struct Flow {
    id: String,
    device_code: String,
    user_code: String,
    verification_uri: String,
    expires_at: i64,
    interval: u64,
    state: &'static str,
    error: Option<String>,
}

#[derive(Default)]
struct State {
    access: HashMap<String, (String, i64)>,
    docs: HashMap<String, (Value, i64)>,
    flows: HashMap<String, Flow>,
    /// Providers whose sign-in the service refused (the refresh token was dropped): they read "expired", not "never".
    expired: std::collections::HashSet<String>,
}

/// What a try for a live access token found.
enum Access {
    Token(String),
    /// The service refused the refresh token: the sign-in is over.
    Refused,
    /// The service could not be asked (network, 5xx): the sign-in is kept.
    Unreachable(String),
}

pub struct Account {
    providers: Vec<Provider>,
    http: Arc<dyn AccountHttp>,
    tokens: Arc<dyn TokenStore>,
    state: Mutex<State>,
    /// One refresh at a time: the service rotates refresh tokens and ends the whole sign-in when a used one is sent
    /// again, so two callers refreshing at once (the Services card and a plugin) must not both send the same token.
    refreshing: Mutex<()>,
    clock: fn() -> i64,
    settings: Box<dyn Fn(&str) -> Option<String> + Send + Sync>,
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// The providers the installed official modules declare (`account.providers`); a bad descriptor is logged and skipped.
///
/// A descriptor may write `{name}` for a module endpoint (`daemon.endpoints.<name>`): it is replaced with that
/// endpoint's address for `environment` (prod when the environment has none), so the provider's URLs come from the
/// same environment as the plugin's own calls and no stage script has to render them.
pub fn declared_providers(plugins_dir: &Path, environment: &str, log: &dyn Fn(&str)) -> Vec<Provider> {
    let mut out: Vec<Provider> = Vec::new();
    for tier in crate::compose::plugins::TRUSTED_TIERS {
        let Ok(rd) = std::fs::read_dir(plugins_dir.join(tier)) else { continue };
        for e in rd.filter_map(|e| e.ok()) {
            let Some(v) = std::fs::read_to_string(e.path().join("module.json")).ok().and_then(|t| serde_json::from_str::<Value>(&t).ok()) else { continue };
            for d in v.pointer("/account/providers").and_then(Value::as_array).into_iter().flatten() {
                let d = &fill_endpoints(d, v.pointer("/daemon/endpoints"), environment);
                match Provider::parse(d) {
                    Ok(p) if out.iter().all(|o| o.id != p.id) => out.push(p),
                    Ok(p) => log(&format!("account provider '{}' is declared twice; the first wins", p.id)),
                    Err(why) => log(&format!("account provider of {} refused: {why}", e.path().display())),
                }
            }
        }
    }
    out
}

/// `{name}` in every string of `v` -> the module endpoint `name` for `environment` (else prod). Unknown names stay.
pub fn fill_endpoints(v: &Value, endpoints: Option<&Value>, environment: &str) -> Value {
    let Some(eps) = endpoints.and_then(Value::as_object) else { return v.clone() };
    fn walk(v: &Value, eps: &serde_json::Map<String, Value>, env: &str) -> Value {
        match v {
            Value::String(s) => {
                let mut out = s.clone();
                for (name, by_env) in eps {
                    if let Some(addr) = by_env.get(env).or_else(|| by_env.get("prod")).and_then(Value::as_str) {
                        out = out.replace(&format!("{{{name}}}"), addr.trim_end_matches('/'));
                    }
                }
                Value::String(out)
            }
            Value::Array(a) => Value::Array(a.iter().map(|x| walk(x, eps, env)).collect()),
            Value::Object(o) => Value::Object(o.iter().map(|(k, x)| (k.clone(), walk(x, eps, env))).collect()),
            other => other.clone(),
        }
    }
    walk(v, eps, environment)
}

impl Account {
    pub fn new(providers: Vec<Provider>, http: Arc<dyn AccountHttp>, tokens: Arc<dyn TokenStore>) -> Arc<Account> {
        Arc::new(Account { providers, http, tokens, state: Mutex::new(State::default()), refreshing: Mutex::new(()), clock: now_ms, settings: Box::new(|_| None) })
    }

    /// The same hub reading core settings (`account.deviceName`) through `get`. Call before the hub is shared.
    pub fn with_settings(self: Arc<Self>, get: impl Fn(&str) -> Option<String> + Send + Sync + 'static) -> Arc<Account> {
        let mut a = Arc::try_unwrap(self).unwrap_or_else(|_| panic!("with_settings: the account is already shared"));
        a.settings = Box::new(get);
        Arc::new(a)
    }

    /// The name this device signs in under: the setting, else "Infantry on <hostname>".
    pub fn device_name(&self) -> String {
        let named = (self.settings)(DEVICE_NAME_KEY).map(|v| v.trim().to_string()).filter(|v| !v.is_empty());
        let host = || std::env::var("COMPUTERNAME").or_else(|_| std::env::var("HOSTNAME")).map(|h| h.trim().to_string()).ok().filter(|h| !h.is_empty()).unwrap_or_else(|| "this computer".into());
        named.unwrap_or_else(|| format!("Infantry on {}", host())).chars().take(DEVICE_NAME_MAX).collect()
    }

    /// `account_token` for plugin `module`: the provider's live access token, only when the provider lists the module
    /// in `consumers`. The refusal and the error never carry a token.
    pub fn token_for(&self, provider: Option<&str>, module: &str) -> Result<Value, String> {
        let p = self.provider(provider)?;
        let module = module.trim();
        if module.is_empty() || !p.consumers.iter().any(|c| c.eq_ignore_ascii_case(module)) {
            return Err(format!("{} does not list '{module}' as a consumer of its account token", p.name));
        }
        let token = match self.access(p) {
            Access::Token(t) => t,
            Access::Refused => return Err(format!("not signed in to {}", p.name)),
            Access::Unreachable(e) => return Err(format!("{} could not be reached: {e}", p.name)),
        };
        let until = self.state.lock().unwrap().access.get(&p.id).map(|(_, u)| *u).unwrap_or(0);
        Ok(json!({ "accessToken": token, "expiresAt": until }))
    }

    fn provider(&self, id: Option<&str>) -> Result<&Provider, String> {
        match id.map(str::trim).filter(|s| !s.is_empty()) {
            Some(id) => self.providers.iter().find(|p| p.id == id).ok_or_else(|| format!("no account provider '{id}'")),
            None => self.providers.first().ok_or_else(|| "no account provider is installed".to_string()),
        }
    }

    fn cached_access(&self, p: &Provider) -> Option<String> {
        let now = (self.clock)();
        self.state.lock().unwrap().access.get(&p.id).filter(|(_, until)| *until > now).map(|(t, _)| t.clone())
    }

    /// A live access token: the cached one, else a refresh-token grant, one at a time. Only a refusal of the token
    /// still kept ends the sign-in; a network failure or a 5xx keeps it.
    fn access(&self, p: &Provider) -> Access {
        if let Some(t) = self.cached_access(p) {
            return Access::Token(t);
        }
        let _one = self.refreshing.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(t) = self.cached_access(p) {
            return Access::Token(t); // another caller refreshed while this one waited
        }
        let Some(refresh) = self.tokens.get(&p.refresh_field()) else { return Access::Refused };
        match self.http.post_form(&p.token_url, &[("grant_type", "refresh_token"), ("refresh_token", &refresh), ("client_id", &p.client_id)]) {
            Ok((200, v)) => {
                self.keep_tokens(p, &v);
                match self.state.lock().unwrap().access.get(&p.id) {
                    Some((t, _)) => Access::Token(t.clone()),
                    None => Access::Unreachable("the service answered without an access token".into()),
                }
            }
            Ok((400 | 401, _)) => {
                if self.tokens.get(&p.refresh_field()).as_deref() == Some(refresh.as_str()) {
                    self.tokens.delete(&p.refresh_field());
                    self.state.lock().unwrap().expired.insert(p.id.clone());
                }
                Access::Refused
            }
            Ok((code, _)) => Access::Unreachable(format!("HTTP {code}")),
            Err(e) => Access::Unreachable(e),
        }
    }

    /// The account document; a 401 (the access token was revoked or aged out early) drops it and tries once with a
    /// fresh one from the refresh token.
    fn read_doc(&self, p: &Provider, url: &str, token: &str) -> Result<(u16, Value), String> {
        match self.http.get_json(url, token)? {
            (401, _) => {
                self.state.lock().unwrap().access.remove(&p.id);
                match self.access(p) {
                    Access::Token(t) => self.http.get_json(url, &t),
                    Access::Refused => Ok((401, Value::Null)),
                    Access::Unreachable(e) => Err(e),
                }
            }
            other => Ok(other),
        }
    }

    fn keep_tokens(&self, p: &Provider, v: &Value) {
        if let Some(r) = s(v, "refresh_token") {
            let _ = self.tokens.set(&p.refresh_field(), &r);
            self.state.lock().unwrap().expired.remove(&p.id);
        }
        if let Some(a) = s(v, "access_token") {
            let ttl = v.get("expires_in").and_then(Value::as_i64).unwrap_or(0).max(0) * 1_000;
            self.state.lock().unwrap().access.insert(p.id.clone(), (a, (self.clock)() + ttl));
        }
    }

    fn view(&self, p: &Provider, refresh: bool) -> Value {
        let mut doc = Value::Null;
        let mut unreachable = false;
        if self.tokens.get(&p.refresh_field()).is_some() {
            if let Some(url) = &p.account_url {
                let now = (self.clock)();
                let cached = self.state.lock().unwrap().docs.get(&p.id).filter(|(_, at)| !refresh && now - at < CACHE_MS).map(|(d, _)| d.clone());
                doc = match cached {
                    Some(d) => d,
                    None => {
                        let read = match self.access(p) {
                            Access::Token(t) => self.read_doc(p, url, &t),
                            Access::Refused => Ok((401, Value::Null)),
                            Access::Unreachable(e) => Err(e),
                        };
                        match read {
                            Ok((200, d)) => {
                                self.state.lock().unwrap().docs.insert(p.id.clone(), (d.clone(), now));
                                d
                            }
                            // Refused: the sign-in is over; the state below says expired, with no error next to it.
                            Ok((401, _)) => Value::Null,
                            Ok((code, _)) => {
                                unreachable = true;
                                json!({ "error": format!("{} could not be reached (HTTP {code})", p.name) })
                            }
                            Err(e) => {
                                unreachable = true;
                                json!({ "error": format!("{} could not be reached: {e}", p.name) })
                            }
                        }
                    }
                };
            }
        }
        // Read after the refresh: a refused one has just ended the sign-in.
        let signed_in = self.tokens.get(&p.refresh_field()).is_some();
        let sign_in = match (signed_in, unreachable) {
            (true, true) => "unreachable",
            (true, false) => "signed_in",
            (false, _) if self.state.lock().unwrap().expired.contains(&p.id) => "expired",
            (false, _) => "signed_out",
        };
        let mut v = json!({ "id": p.id, "name": p.name, "icon": p.icon, "signedIn": signed_in, "signIn": sign_in });
        let o = v.as_object_mut().expect("object");
        for (k, val) in [("description", &p.description), ("billingUrl", &p.billing_url), ("signUpUrl", &p.sign_up_url)] {
            if let Some(x) = val {
                o.insert(k.into(), json!(x));
            }
        }
        if !signed_in {
            doc = Value::Null;
        }
        if let Some(email) = doc.get("email").or_else(|| doc.pointer("/user/email")).and_then(Value::as_str) {
            o.insert("user".into(), json!({ "email": mask_email(email) }));
        }
        for k in ["accountId", "memberRole", "plan", "limits", "usage", "reclaimableUntil", "error"] {
            if let Some(x) = doc.get(k).filter(|x| !x.is_null()) {
                o.insert(k.into(), x.clone());
            }
        }
        // Legacy `planActive && signedIn && holds(entitlement)`: the plan is active (no status counts as active) and the
        // document's `permissions` list holds the service's entitlement.
        let plan_active = doc.pointer("/plan/status").and_then(Value::as_str).map_or(true, |st| st == "active");
        let entitled: Vec<String> = if signed_in && plan_active { doc.get("permissions").and_then(Value::as_array).into_iter().flatten().filter_map(|x| x.as_str().map(String::from)).collect() } else { Vec::new() };
        o.insert("permissions".into(), doc.get("permissions").cloned().unwrap_or(json!([])));
        let services: Vec<Value> = p
            .services
            .iter()
            .map(|sv| json!({ "id": sv.id, "name": sv.name, "section": sv.section, "entitled": entitled.contains(&sv.entitlement), "state": if signed_in { "signed_in" } else { "signed_out" } }))
            .collect();
        o.insert("services".into(), json!(services));
        if let Some(f) = self.state.lock().unwrap().flows.get(&p.id) {
            o.insert("flow".into(), json!({ "id": f.id, "state": f.state, "userCode": f.user_code, "verificationUri": f.verification_uri, "expiresAt": f.expires_at, "error": f.error }));
        }
        v
    }

    /// `account_status`: every provider (or one), and the first one's state at the top.
    pub fn status(&self, provider: Option<&str>, refresh: bool) -> Result<Value, String> {
        let list: Vec<&Provider> = match provider.map(str::trim).filter(|s| !s.is_empty()) {
            Some(_) => vec![self.provider(provider)?],
            None => self.providers.iter().collect(),
        };
        let views: Vec<Value> = list.iter().map(|p| self.view(p, refresh)).collect();
        let mut top = json!({ "providers": views });
        match views.first() {
            None => {
                top["state"] = json!("none");
                top["message"] = json!("No account provider is installed.");
            }
            Some(f) => {
                let signed_in = f["signedIn"].as_bool() == Some(true);
                top["state"] = json!(if signed_in { "signed_in" } else { "signed_out" });
                top["signIn"] = f["signIn"].clone();
                if let Some(e) = f.pointer("/user/email") {
                    top["email"] = e.clone();
                }
                if let Some(plan) = f.get("plan") {
                    top["plan"] = if let Some(name) = plan.get("name") { name.clone() } else { plan.clone() };
                    if let Some(st) = plan.get("status") {
                        top["planStatus"] = st.clone();
                    }
                }
                if let Some(e) = f.get("error") {
                    top["message"] = e.clone();
                } else if !signed_in {
                    let name = f["name"].as_str().unwrap_or("the account");
                    top["message"] = json!(if f["signIn"] == "expired" { format!("The sign-in to {name} expired. Sign in again.") } else { format!("Not signed in to {name}.") });
                }
                top["services"] = f["services"].clone();
            }
        }
        Ok(top)
    }

    /// Starts the device-code flow; [`Account::poll`] finishes it.
    pub fn sign_in(&self, provider: Option<&str>) -> Result<Value, String> {
        let p = self.provider(provider)?;
        let scope = p.scopes.join(" ");
        let (code, v) = self.http.post_form(&p.device_url, &[("client_id", &p.client_id), ("scope", &scope), ("device_name", &self.device_name())])?;
        if code != 200 {
            return Err(format!("{} refused the sign-in (HTTP {code})", p.name));
        }
        let need = |k: &str| s(&v, k).ok_or_else(|| format!("{} answered no {k}", p.name));
        let flow = Flow {
            id: uuid::Uuid::new_v4().to_string(),
            device_code: need("device_code")?,
            user_code: need("user_code")?,
            verification_uri: s(&v, "verification_uri_complete").or_else(|| s(&v, "verification_uri")).ok_or_else(|| format!("{} answered no verification_uri", p.name))?,
            expires_at: (self.clock)() + v.get("expires_in").and_then(Value::as_i64).unwrap_or(600) * 1_000,
            interval: v.get("interval").and_then(Value::as_u64).unwrap_or(POLL_DEFAULT_SECS).max(1),
            state: "pending",
            error: None,
        };
        let out = json!({ "id": flow.id, "provider": p.id, "userCode": flow.user_code, "verificationUri": flow.verification_uri, "expiresAt": flow.expires_at });
        self.state.lock().unwrap().flows.insert(p.id.clone(), flow);
        Ok(out)
    }

    /// One poll of a provider's pending flow: Some(seconds to wait) while it is pending, None when it ended.
    pub fn poll(&self, provider: &str) -> Option<u64> {
        let p = self.provider(Some(provider)).ok()?.clone();
        let flow = self.state.lock().unwrap().flows.get(provider).filter(|f| f.state == "pending").cloned()?;
        let end = |state: &'static str, err: Option<String>| {
            if let Some(f) = self.state.lock().unwrap().flows.get_mut(provider) {
                f.state = state;
                f.error = err;
            }
            None
        };
        if (self.clock)() >= flow.expires_at {
            return end("expired", Some("the code expired; sign in again".into()));
        }
        match self.http.post_form(&p.token_url, &[("grant_type", DEVICE_GRANT), ("device_code", &flow.device_code), ("client_id", &p.client_id)]) {
            Ok((200, v)) => {
                self.keep_tokens(&p, &v);
                let mut st = self.state.lock().unwrap();
                st.flows.remove(provider);
                st.docs.remove(provider);
                None
            }
            Ok((_, v)) => match s(&v, "error").as_deref() {
                Some("authorization_pending") => Some(flow.interval),
                Some("slow_down") => Some(flow.interval + POLL_DEFAULT_SECS),
                Some("access_denied") => end("denied", Some("the sign-in was denied".into())),
                Some("expired_token") => end("expired", Some("the code expired; sign in again".into())),
                other => end("failed", Some(format!("the sign-in failed ({})", other.unwrap_or("no reason")))),
            },
            Err(_) => Some(flow.interval),
        }
    }

    pub fn sign_out(&self, provider: Option<&str>) -> Result<Value, String> {
        let p = self.provider(provider)?;
        let refresh = self.tokens.get(&p.refresh_field());
        if let (Some(r), Some(url)) = (&refresh, &p.revoke_url) {
            let _ = self.http.post_form(url, &[("token", r), ("token_type_hint", "refresh_token"), ("client_id", &p.client_id)]);
        }
        self.tokens.delete(&p.refresh_field());
        let mut st = self.state.lock().unwrap();
        st.access.remove(&p.id);
        st.docs.remove(&p.id);
        st.flows.remove(&p.id);
        Ok(json!({ "wasSignedIn": refresh.is_some() }))
    }
}

pub struct AccountUnit {
    pub account: Arc<Account>,
}

impl FrameUnit for AccountUnit {
    fn name(&self) -> &'static str {
        "account"
    }
    fn frames(&self) -> &'static [&'static str] {
        frames::ALL
    }
    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let ty = env.r#type.as_str();
        let d: Value = serde_json::from_str(env.data.trim()).unwrap_or(Value::Null);
        let provider = s(&d, "provider");
        let refuse = |m: String| Refusal::new(ty, m);
        let v = match ty {
            frames::STATUS => self.account.status(provider.as_deref(), d.get("refresh").and_then(Value::as_bool).unwrap_or(false)).map_err(refuse)?,
            frames::TOKEN => return Err(refuse("only a plugin module that the provider lists as a consumer may ask, in process".into())),
            frames::SIGN_IN | frames::SIGN_OUT if !ctx.owner => return Err(refuse("the owner's act (the Account tab or the CLI)".into())),
            frames::SIGN_IN => {
                let v = self.account.sign_in(provider.as_deref()).map_err(refuse)?;
                let (a, id, bus) = (self.account.clone(), v["provider"].as_str().unwrap_or_default().to_string(), ctx.bus.clone());
                std::thread::spawn(move || {
                    while let Some(wait) = a.poll(&id) {
                        std::thread::sleep(std::time::Duration::from_secs(wait));
                    }
                    if let Ok(s) = a.status(Some(&id), true) {
                        bus.publish(Envelope::new(frames::STATUS, s.to_string()));
                    }
                });
                v
            }
            frames::SIGN_OUT => self.account.sign_out(provider.as_deref()).map_err(refuse)?,
            other => return Err(refuse(format!("not an account frame: {other}"))),
        };
        let mut out = Envelope::new(ty, v.to_string());
        out.request_id = env.request_id.clone();
        Ok(vec![out])
    }
}

/// The vault's keystore as the token store.
pub struct VaultTokens {
    pub keystore: Arc<infantry_vault::keystore::Keystore>,
}

impl TokenStore for VaultTokens {
    fn get(&self, field: &str) -> Option<String> {
        self.keystore.field(SECRET, field, None).ok().flatten()
    }
    fn set(&self, field: &str, value: &str) -> Result<(), String> {
        self.keystore.set_field(SECRET, field, value, None).map_err(|e| e.to_string())
    }
    fn delete(&self, field: &str) -> bool {
        self.keystore.delete_field(SECRET, field, None).unwrap_or(false)
    }
}

/// HTTP through ureq (TLS by the platform), bounded by [`HTTP_TIMEOUT_MS`].
pub struct UreqHttp;

/// One account HTTP call's ceiling.
pub const HTTP_TIMEOUT_MS: u64 = 20_000;

impl UreqHttp {
    fn agent() -> ureq::Agent {
        // native-tls is not ureq's default backend: without the connector every https call fails "no TLS backend is configured".
        let b = ureq::AgentBuilder::new().timeout(std::time::Duration::from_millis(HTTP_TIMEOUT_MS));
        match native_tls::TlsConnector::new() {
            Ok(tls) => b.tls_connector(std::sync::Arc::new(tls)).build(),
            Err(_) => b.build(),
        }
    }
    fn answer(r: Result<ureq::Response, ureq::Error>) -> Result<(u16, Value), String> {
        match r {
            Ok(r) | Err(ureq::Error::Status(_, r)) => {
                let code = r.status();
                Ok((code, r.into_string().ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or(Value::Null)))
            }
            Err(e) => Err(e.to_string()),
        }
    }
}

impl AccountHttp for UreqHttp {
    fn post_form(&self, url: &str, form: &[(&str, &str)]) -> Result<(u16, Value), String> {
        if !url_ok(url) {
            return Err("refused: not https".into());
        }
        Self::answer(Self::agent().post(url).set("Accept", "application/json").send_form(form))
    }
    fn get_json(&self, url: &str, bearer: &str) -> Result<(u16, Value), String> {
        if !url_ok(url) {
            return Err("refused: not https".into());
        }
        Self::answer(Self::agent().get(url).set("Accept", "application/json").set("Authorization", &format!("Bearer {bearer}")).call())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Default)]
    struct FakeHttp {
        pending: Mutex<u32>,
        calls: Mutex<Vec<String>>,
        doc: Mutex<Option<Value>>,
    }
    impl AccountHttp for FakeHttp {
        fn post_form(&self, url: &str, form: &[(&str, &str)]) -> Result<(u16, Value), String> {
            self.calls.lock().unwrap().push(url.into());
            let grant = form.iter().find(|(k, _)| *k == "grant_type").map(|(_, v)| *v);
            Ok(match (url, grant) {
                ("https://p.test/device", _) => (200, json!({"device_code": "dc", "user_code": "ABCD-1234", "verification_uri": "https://p.test/go", "expires_in": 600, "interval": 1})),
                ("https://p.test/token", Some(DEVICE_GRANT)) => {
                    let mut n = self.pending.lock().unwrap();
                    if *n > 0 {
                        *n -= 1;
                        (400, json!({"error": "authorization_pending"}))
                    } else {
                        (200, json!({"access_token": "at", "refresh_token": "rt", "expires_in": 3600}))
                    }
                }
                _ => (200, json!({})),
            })
        }
        fn get_json(&self, _url: &str, bearer: &str) -> Result<(u16, Value), String> {
            assert_eq!(bearer, "at");
            if let Some(d) = self.doc.lock().unwrap().clone() {
                return Ok((200, d));
            }
            Ok((200, json!({"user": {"email": "alice@example.com"}, "plan": {"name": "Basic", "status": "active"}, "permissions": ["hosting"]})))
        }
    }
    #[derive(Default)]
    struct MemTokens(Mutex<HashMap<String, String>>);
    impl TokenStore for MemTokens {
        fn get(&self, f: &str) -> Option<String> {
            self.0.lock().unwrap().get(f).cloned()
        }
        fn set(&self, f: &str, v: &str) -> Result<(), String> {
            self.0.lock().unwrap().insert(f.into(), v.into());
            Ok(())
        }
        fn delete(&self, f: &str) -> bool {
            self.0.lock().unwrap().remove(f).is_some()
        }
    }

    fn provider() -> Provider {
        Provider::parse(&json!({
            "id": "acme", "name": "Acme", "accountUrl": "https://p.test/me", "services": [{"id": "hosting", "name": "Hosting"}],
            "signIn": {"clientId": "cli", "deviceAuthorizationUrl": "https://p.test/device", "tokenUrl": "https://p.test/token", "revokeUrl": "https://p.test/revoke"}
        }))
        .unwrap()
    }

    #[test]
    fn device_sign_in_keeps_the_refresh_token_and_shows_a_masked_account() {
        let http = Arc::new(FakeHttp { pending: Mutex::new(1), ..Default::default() });
        let tokens = Arc::new(MemTokens::default());
        let a = Account::new(vec![provider()], http.clone(), tokens.clone());
        assert_eq!(a.status(None, false).unwrap()["state"], "signed_out");
        let f = a.sign_in(None).unwrap();
        assert_eq!(f["userCode"], "ABCD-1234");
        assert_eq!(a.poll("acme"), Some(1), "still pending");
        assert_eq!(a.poll("acme"), None);
        assert_eq!(tokens.get("acct_acme_refresh").as_deref(), Some("rt"));
        let s = a.status(None, true).unwrap();
        assert_eq!((s["state"].as_str(), s["email"].as_str(), s["plan"].as_str()), (Some("signed_in"), Some("a***@example.com"), Some("Basic")));
        assert_eq!(s["services"][0]["entitled"], true);
        assert!(!s.to_string().contains("\"rt\"") && !s.to_string().contains("\"at\""), "no token in a view");
        assert_eq!(a.sign_out(None).unwrap()["wasSignedIn"], true);
        assert!(http.calls.lock().unwrap().iter().any(|u| u.ends_with("/revoke")));
        assert_eq!(a.status(None, false).unwrap()["state"], "signed_out");
    }

    #[test]
    fn the_account_document_has_a_top_level_email_and_a_plan_that_may_be_null() {
        let sign_in = |doc: Value| {
            let http = Arc::new(FakeHttp { doc: Mutex::new(Some(doc)), ..Default::default() });
            let a = Account::new(vec![provider()], http, Arc::new(MemTokens::default()));
            a.sign_in(None).unwrap();
            while a.poll("acme").is_some() {}
            a.status(None, true).unwrap()
        };
        let s = sign_in(json!({"email": "alice@example.com", "plan": {"status": "active"}, "permissions": ["hosting"]}));
        assert_eq!(s["email"], "a***@example.com", "top-level email is masked: {s}");
        assert_eq!(s["planStatus"], "active");
        assert_eq!(s["providers"][0]["plan"]["status"], "active", "the plan is copied through");
        assert_eq!(s["providers"][0]["user"]["email"], "a***@example.com");
        assert!(!s.to_string().contains("alice@"), "no raw email in a view");
        let s = sign_in(json!({"email": "alice@example.com", "plan": null}));
        assert_eq!(s["email"], "a***@example.com");
        assert!(s.get("plan").is_none() && s["providers"][0].get("plan").is_none(), "a null plan stays absent: {s}");
    }

    fn entitled_with(doc: Value, signed_in: bool) -> Value {
        let http = Arc::new(FakeHttp::default());
        *http.doc.lock().unwrap() = Some(doc);
        let tokens = Arc::new(MemTokens::default());
        if signed_in {
            tokens.set("acct_acme_refresh", "rt").unwrap();
        }
        let a = Account::new(vec![provider()], http, tokens);
        a.state.lock().unwrap().access.insert("acme".into(), ("at".into(), i64::MAX));
        a.status(None, true).unwrap()["services"][0]["entitled"].clone()
    }

    #[test]
    fn entitled_needs_an_active_plan_and_the_permission() {
        assert_eq!(entitled_with(json!({"plan": {"status": "active"}, "permissions": ["hosting"]}), true), true);
        assert_eq!(entitled_with(json!({"permissions": ["hosting"]}), true), true, "no plan status counts as active");
        assert_eq!(entitled_with(json!({"plan": null, "permissions": []}), true), false, "no permission");
        assert_eq!(entitled_with(json!({"plan": {"status": "canceled"}, "permissions": ["hosting"]}), true), false, "plan not active");
        assert_eq!(entitled_with(json!({"plan": {"status": "active"}, "permissions": ["hosting"]}), false), false, "signed out");
    }

    #[test]
    fn descriptor_endpoint_placeholders_follow_the_environment() {
        let eps = json!({"api": {"prod": "https://api.example", "staging": "http://127.0.0.1:1/api/"}, "site": {"prod": "https://site.example"}});
        let d = json!({"id": "x", "accountUrl": "{api}/me", "signUpUrl": "{site}/up", "other": "{unknown}", "list": ["{api}"]});
        let prod = fill_endpoints(&d, Some(&eps), "prod");
        assert_eq!((prod["accountUrl"].as_str(), prod["signUpUrl"].as_str()), (Some("https://api.example/me"), Some("https://site.example/up")));
        let stg = fill_endpoints(&d, Some(&eps), "staging");
        assert_eq!(stg["accountUrl"], "http://127.0.0.1:1/api/me");
        assert_eq!(stg["signUpUrl"], "https://site.example/up", "an environment without an address falls back to prod");
        assert_eq!((stg["other"].as_str(), stg["list"][0].as_str()), (Some("{unknown}"), Some("http://127.0.0.1:1/api")));
        assert_eq!(fill_endpoints(&d, None, "prod"), d);
    }

    #[test]
    fn descriptors_need_https_and_a_device_flow() {
        let bad = json!({"id": "x", "signIn": {"clientId": "c", "deviceAuthorizationUrl": "http://evil.test/d", "tokenUrl": "https://t"}});
        assert!(Provider::parse(&bad).unwrap_err().contains("https"));
        assert!(url_ok("http://127.0.0.1:9/x") && !url_ok("http://127.0.0.1.evil.test/x"));
    }

    /// A provider whose token endpoint hands out `at1`, then `at2`; the account document 401s on `at1`.
    #[derive(Default)]
    struct RotatingHttp {
        issued: Mutex<u32>,
        forms: Mutex<Vec<Vec<(String, String)>>>,
    }
    impl AccountHttp for RotatingHttp {
        fn post_form(&self, url: &str, form: &[(&str, &str)]) -> Result<(u16, Value), String> {
            self.forms.lock().unwrap().push(form.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect());
            if url.ends_with("/device") {
                return Ok((200, json!({"device_code": "dc", "user_code": "U-1", "verification_uri": "https://p.test/go", "expires_in": 600})));
            }
            let mut n = self.issued.lock().unwrap();
            *n += 1;
            Ok((200, json!({"access_token": format!("at{n}"), "refresh_token": "rt", "expires_in": 3600})))
        }
        fn get_json(&self, _url: &str, bearer: &str) -> Result<(u16, Value), String> {
            if bearer == "at1" { Ok((401, Value::Null)) } else { Ok((200, json!({"plan": {"name": "Pro"}}))) }
        }
    }

    fn consumer_provider() -> Provider {
        Provider::parse(&json!({
            "id": "acme", "name": "Acme", "accountUrl": "https://p.test/me", "consumers": ["Good_Mod", " "],
            "signIn": {"clientId": "cli", "deviceAuthorizationUrl": "https://p.test/device", "tokenUrl": "https://p.test/token"}
        }))
        .unwrap()
    }

    fn signed_in(http: Arc<dyn AccountHttp>) -> Arc<Account> {
        let tokens = Arc::new(MemTokens::default());
        tokens.set("acct_acme_refresh", "rt").unwrap();
        Account::new(vec![consumer_provider()], http, tokens)
    }

    #[test]
    fn consumers_are_parsed_and_only_a_listed_module_gets_the_token() {
        assert_eq!(consumer_provider().consumers, vec!["Good_Mod".to_string()]);
        let a = signed_in(Arc::new(RotatingHttp::default()));
        let ok = a.token_for(Some("acme"), "Good_Mod").unwrap();
        assert_eq!(ok["accessToken"], "at1");
        assert!(ok["expiresAt"].as_i64().unwrap() > 0);
        let no = a.token_for(Some("acme"), "Evil_Mod").unwrap_err();
        assert!(no.contains("consumer") && !no.contains("at1"), "{no}");
        assert!(a.token_for(Some("acme"), "").is_err(), "no module, no token");
        assert!(a.token_for(Some("nope"), "Good_Mod").is_err());
    }

    #[test]
    fn a_provider_with_no_consumers_answers_nobody_and_a_signed_out_one_no_token() {
        let none = Provider::parse(&json!({"id": "acme", "signIn": {"clientId": "c", "deviceAuthorizationUrl": "https://p.test/device", "tokenUrl": "https://p.test/token"}})).unwrap();
        let tokens = Arc::new(MemTokens::default());
        tokens.set("acct_acme_refresh", "rt").unwrap();
        let a = Account::new(vec![none], Arc::new(RotatingHttp::default()), tokens);
        assert!(a.token_for(Some("acme"), "Good_Mod").is_err());
        let out = Account::new(vec![consumer_provider()], Arc::new(RotatingHttp::default()), Arc::new(MemTokens::default()));
        assert!(out.token_for(Some("acme"), "Good_Mod").unwrap_err().contains("not signed in"));
    }

    #[test]
    fn the_wire_frame_is_refused_for_everyone_owner_included_and_leaks_no_token() {
        let unit = AccountUnit { account: signed_in(Arc::new(RotatingHttp::default())) };
        for owner in [true, false] {
            let ctx = FrameCtx { conn_id: 1, owner, bus: infantry_core::bus::EventBus::new(8) };
            let r = unit.handle(&Envelope::new(frames::TOKEN, json!({"provider": "acme", "module": "Good_Mod", "_module": "Good_Mod"}).to_string()), &ctx);
            let why = r.err().expect("refused").0;
            assert!(why.starts_with("account_token refused:") && !why.contains("at1"), "{why}");
        }
        assert!(unit.frames().contains(&frames::TOKEN), "registered so it is refused, not unknown");
    }

    #[test]
    fn the_token_is_in_no_status_view() {
        let a = signed_in(Arc::new(RotatingHttp::default()));
        let _ = a.token_for(Some("acme"), "Good_Mod").unwrap();
        let s = a.status(None, true).unwrap().to_string();
        assert!(!s.contains("at1") && !s.contains("at2") && !s.contains("\"rt\""), "{s}");
    }

    #[test]
    fn status_refreshes_the_access_token_after_a_401() {
        let http = Arc::new(RotatingHttp::default());
        let a = signed_in(http.clone());
        let s = a.status(None, true).unwrap();
        assert_eq!(s["plan"], "Pro", "the retry with the fresh token read the document: {s}");
        assert_eq!(*http.issued.lock().unwrap(), 2);
    }

    #[test]
    fn sign_in_sends_a_device_name_from_the_setting_or_the_hostname() {
        let http = Arc::new(RotatingHttp::default());
        let a = Account::new(vec![consumer_provider()], http.clone(), Arc::new(MemTokens::default()));
        a.sign_in(None).unwrap();
        let sent = http.forms.lock().unwrap()[0].iter().find(|(k, _)| k == "device_name").map(|(_, v)| v.clone()).expect("device_name sent");
        assert!(sent.starts_with("Infantry on "), "{sent}");
        let a = Account::new(vec![consumer_provider()], http.clone(), Arc::new(MemTokens::default())).with_settings(|k| (k == DEVICE_NAME_KEY).then(|| "Anthony's desk".to_string()));
        assert_eq!(a.device_name(), "Anthony's desk");
        let long = Account::new(vec![], http, Arc::new(MemTokens::default())).with_settings(|_| Some("x".repeat(500)));
        assert_eq!(long.device_name().chars().count(), DEVICE_NAME_MAX);
    }

    /// The hosting server's refresh rules (superiorsoft-core auth/Auth.kt `refresh`): every refresh rotates the refresh
    /// token, and a used one sent again kills the whole sign-in (400 invalid_grant). Refreshes are slow, like a network.
    #[derive(Default)]
    struct StrictServer {
        live: Mutex<Option<String>>,
        used: Mutex<Vec<String>>,
        n: Mutex<u32>,
        down: Mutex<bool>,
    }
    impl AccountHttp for StrictServer {
        fn post_form(&self, _url: &str, form: &[(&str, &str)]) -> Result<(u16, Value), String> {
            if *self.down.lock().unwrap() {
                return Err("connection refused".into());
            }
            let sent = form.iter().find(|(k, _)| *k == "refresh_token").map(|(_, v)| v.to_string()).unwrap_or_default();
            std::thread::sleep(std::time::Duration::from_millis(60));
            if self.used.lock().unwrap().contains(&sent) {
                *self.live.lock().unwrap() = None; // replay: the family is gone
                return Ok((400, json!({"error": "invalid_grant", "error_description": "refresh token reused"})));
            }
            let mut live = self.live.lock().unwrap();
            if live.as_deref() != Some(sent.as_str()) {
                return Ok((400, json!({"error": "invalid_grant", "error_description": "unknown refresh token"})));
            }
            self.used.lock().unwrap().push(sent);
            let mut n = self.n.lock().unwrap();
            *n += 1;
            *live = Some(format!("rt{n}"));
            Ok((200, json!({"access_token": format!("at{n}"), "refresh_token": format!("rt{n}"), "expires_in": 3600})))
        }
        fn get_json(&self, _url: &str, bearer: &str) -> Result<(u16, Value), String> {
            Ok(if bearer.starts_with("at") { (200, json!({"email": "alice@example.com", "plan": {"name": "Basic"}})) } else { (401, Value::Null) })
        }
    }

    fn strict() -> (Arc<StrictServer>, Arc<MemTokens>, Arc<Account>) {
        let server = Arc::new(StrictServer::default());
        *server.live.lock().unwrap() = Some("rt0".into());
        let tokens = Arc::new(MemTokens::default());
        tokens.set("acct_acme_refresh", "rt0").unwrap();
        let a = Account::new(vec![consumer_provider()], server.clone(), tokens.clone());
        (server, tokens, a)
    }

    /// HOSTING-SIGNIN-1: the Services card (status) and the Sites plugin (token_for) refresh at the same moment. Both
    /// sent the same refresh token; the server killed the sign-in on the replay and the card read "Connected" next to
    /// "signed out: the sign-in expired".
    #[test]
    fn a_card_and_a_plugin_refreshing_at_once_keep_the_sign_in() {
        let (server, tokens, a) = strict();
        let (a1, a2) = (a.clone(), a.clone());
        let t1 = std::thread::spawn(move || a1.status(None, true).unwrap());
        let t2 = std::thread::spawn(move || a2.token_for(Some("acme"), "Good_Mod"));
        let s = t1.join().unwrap();
        assert!(t2.join().unwrap().is_ok(), "the plugin got a token");
        assert_eq!(s["providers"][0]["signIn"], "signed_in", "{s}");
        assert!(s["message"].is_null(), "{s}");
        assert_eq!(*server.n.lock().unwrap(), 1, "one refresh for both");
        assert_eq!(tokens.get("acct_acme_refresh"), server.live.lock().unwrap().clone(), "the kept token is the live one");
        assert_eq!(a.status(None, true).unwrap()["state"], "signed_in");
    }

    #[test]
    fn a_refused_refresh_reads_expired_with_sign_in_never_connected() {
        let (server, tokens, a) = strict();
        *server.live.lock().unwrap() = None; // the server ended the sign-in
        for _ in 0..2 {
            let s = a.status(None, true).unwrap();
            let p = &s["providers"][0];
            assert_eq!((p["signedIn"].as_bool(), p["signIn"].as_str(), s["state"].as_str()), (Some(false), Some("expired"), Some("signed_out")), "{s}");
            assert!(s["message"].as_str().unwrap().contains("expired"), "{s}");
        }
        assert!(tokens.get("acct_acme_refresh").is_none());
    }

    #[test]
    fn an_unreachable_service_keeps_the_sign_in_and_says_unreachable() {
        let (server, tokens, a) = strict();
        *server.down.lock().unwrap() = true;
        let s = a.status(None, true).unwrap();
        let p = &s["providers"][0];
        assert_eq!((p["signedIn"].as_bool(), p["signIn"].as_str()), (Some(true), Some("unreachable")), "{s}");
        assert!(!p["error"].as_str().unwrap().contains("signed out"), "{s}");
        assert_eq!(tokens.get("acct_acme_refresh").as_deref(), Some("rt0"), "a network failure never drops the sign-in");
        *server.down.lock().unwrap() = false;
        assert_eq!(a.status(None, true).unwrap()["providers"][0]["signIn"], "signed_in");
    }

    #[test]
    fn a_never_signed_in_provider_reads_signed_out() {
        let a = Account::new(vec![consumer_provider()], Arc::new(StrictServer::default()), Arc::new(MemTokens::default()));
        assert_eq!(a.status(None, true).unwrap()["providers"][0]["signIn"], "signed_out");
    }
}
