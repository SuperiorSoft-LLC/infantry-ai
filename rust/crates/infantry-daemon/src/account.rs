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
    pub const ALL: &[&str] = &[STATUS, SIGN_IN, SIGN_OUT];
}

/// The keystore entry holding every provider's refresh token (one field per provider).
pub const SECRET: &str = "infantry-account";
/// How long an account document is reused before it is read again (`account.cacheSeconds`).
pub const CACHE_MS: i64 = 30_000;
/// The poll interval when the provider names none (RFC 8628 default).
pub const POLL_DEFAULT_SECS: u64 = 5;
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
}

pub struct Account {
    providers: Vec<Provider>,
    http: Arc<dyn AccountHttp>,
    tokens: Arc<dyn TokenStore>,
    state: Mutex<State>,
    clock: fn() -> i64,
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// The providers the installed official modules declare (`account.providers`); a bad descriptor is logged and skipped.
pub fn declared_providers(plugins_dir: &Path, log: &dyn Fn(&str)) -> Vec<Provider> {
    let mut out: Vec<Provider> = Vec::new();
    for tier in crate::compose::plugins::TIERS {
        let Ok(rd) = std::fs::read_dir(plugins_dir.join(tier)) else { continue };
        for e in rd.filter_map(|e| e.ok()) {
            let Some(v) = std::fs::read_to_string(e.path().join("module.json")).ok().and_then(|t| serde_json::from_str::<Value>(&t).ok()) else { continue };
            for d in v.pointer("/account/providers").and_then(Value::as_array).into_iter().flatten() {
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

impl Account {
    pub fn new(providers: Vec<Provider>, http: Arc<dyn AccountHttp>, tokens: Arc<dyn TokenStore>) -> Arc<Account> {
        Arc::new(Account { providers, http, tokens, state: Mutex::new(State::default()), clock: now_ms })
    }

    fn provider(&self, id: Option<&str>) -> Result<&Provider, String> {
        match id.map(str::trim).filter(|s| !s.is_empty()) {
            Some(id) => self.providers.iter().find(|p| p.id == id).ok_or_else(|| format!("no account provider '{id}'")),
            None => self.providers.first().ok_or_else(|| "no account provider is installed".to_string()),
        }
    }

    /// A live access token: the cached one, else a refresh-token grant (a refused refresh signs the provider out).
    fn access(&self, p: &Provider) -> Option<String> {
        let now = (self.clock)();
        if let Some((t, until)) = self.state.lock().unwrap().access.get(&p.id) {
            if *until > now {
                return Some(t.clone());
            }
        }
        let refresh = self.tokens.get(&p.refresh_field())?;
        match self.http.post_form(&p.token_url, &[("grant_type", "refresh_token"), ("refresh_token", &refresh), ("client_id", &p.client_id)]) {
            Ok((200, v)) => {
                self.keep_tokens(p, &v);
                self.state.lock().unwrap().access.get(&p.id).map(|(t, _)| t.clone())
            }
            Ok((400 | 401, _)) => {
                self.tokens.delete(&p.refresh_field());
                None
            }
            _ => None,
        }
    }

    fn keep_tokens(&self, p: &Provider, v: &Value) {
        if let Some(r) = s(v, "refresh_token") {
            let _ = self.tokens.set(&p.refresh_field(), &r);
        }
        if let Some(a) = s(v, "access_token") {
            let ttl = v.get("expires_in").and_then(Value::as_i64).unwrap_or(0).max(0) * 1_000;
            self.state.lock().unwrap().access.insert(p.id.clone(), (a, (self.clock)() + ttl));
        }
    }

    fn view(&self, p: &Provider, refresh: bool) -> Value {
        let signed_in = self.tokens.get(&p.refresh_field()).is_some();
        let mut v = json!({ "id": p.id, "name": p.name, "icon": p.icon, "signedIn": signed_in });
        let o = v.as_object_mut().expect("object");
        for (k, val) in [("description", &p.description), ("billingUrl", &p.billing_url), ("signUpUrl", &p.sign_up_url)] {
            if let Some(x) = val {
                o.insert(k.into(), json!(x));
            }
        }
        let mut doc = Value::Null;
        if signed_in {
            if let Some(url) = &p.account_url {
                let now = (self.clock)();
                let cached = self.state.lock().unwrap().docs.get(&p.id).filter(|(_, at)| !refresh && now - at < CACHE_MS).map(|(d, _)| d.clone());
                doc = match cached {
                    Some(d) => d,
                    None => match self.access(p).map(|t| self.http.get_json(url, &t)) {
                        Some(Ok((200, d))) => {
                            self.state.lock().unwrap().docs.insert(p.id.clone(), (d.clone(), now));
                            d
                        }
                        Some(Ok((code, _))) => json!({ "error": format!("the account could not be read (HTTP {code})") }),
                        Some(Err(e)) => json!({ "error": format!("the account could not be read: {e}") }),
                        None => json!({ "error": "signed out: the sign-in expired" }),
                    },
                };
            }
        }
        if let Some(email) = doc.pointer("/user/email").and_then(Value::as_str) {
            o.insert("user".into(), json!({ "email": mask_email(email) }));
        }
        for k in ["accountId", "memberRole", "plan", "limits", "usage", "reclaimableUntil", "error"] {
            if let Some(x) = doc.get(k).filter(|x| !x.is_null()) {
                o.insert(k.into(), x.clone());
            }
        }
        let entitled: Vec<String> = doc.get("entitlements").and_then(Value::as_array).into_iter().flatten().filter_map(|x| x.as_str().map(String::from)).collect();
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
                    top["message"] = json!(format!("Not signed in to {}.", f["name"].as_str().unwrap_or("the account")));
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
        let (code, v) = self.http.post_form(&p.device_url, &[("client_id", &p.client_id), ("scope", &scope)])?;
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
        ureq::AgentBuilder::new().timeout(std::time::Duration::from_millis(HTTP_TIMEOUT_MS)).build()
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
            Ok((200, json!({"user": {"email": "alice@example.com"}, "plan": {"name": "Basic", "status": "active"}, "entitlements": ["hosting"]})))
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
    fn descriptors_need_https_and_a_device_flow() {
        let bad = json!({"id": "x", "signIn": {"clientId": "c", "deviceAuthorizationUrl": "http://evil.test/d", "tokenUrl": "https://t"}});
        assert!(Provider::parse(&bad).unwrap_err().contains("https"));
        assert!(url_ok("http://127.0.0.1:9/x") && !url_ok("http://127.0.0.1.evil.test/x"));
    }
}
