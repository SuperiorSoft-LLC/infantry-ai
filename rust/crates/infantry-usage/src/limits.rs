// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Plan-limit meters, read live from each provider's own usage endpoint.
//!
//! NO SIGNAL IS NEVER 0%: a source that cannot answer gives `status = "unknown"`, no windows and a fixed reason code.
//! A credential is sent only as the Authorization header of its own endpoint; a failure of the call is `network`
//! (the error, whose text could carry anything, is dropped).
use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::Duration;

use infantry_protocol::usage::{LimitWindow, ProviderLimits};
use serde_json::Value;

use crate::credentials;
use crate::util::{self, Clock};

/// The only strings a reason may hold (plus `http_<code>`).
pub mod reason {
    pub const NO_CREDENTIALS: &str = "no_credentials";
    pub const EXPIRED: &str = "expired";
    pub const NO_SUBSCRIPTION: &str = "no_subscription";
    pub const RATE_LIMITED: &str = "rate_limited";
    pub const NETWORK: &str = "network";
    pub const BAD_RESPONSE: &str = "bad_response";
    pub const LIVE_SOURCES_OFF: &str = "live_sources_off";
    pub const SOURCE_FAILED: &str = "source_failed";
    pub const NOT_READ_YET: &str = "not_read_yet";

    pub fn of_status(status: u16) -> Option<String> {
        match status {
            200..=299 => None,
            401 => Some(EXPIRED.into()),
            403 => Some(NO_SUBSCRIPTION.into()),
            429 => Some(RATE_LIMITED.into()),
            s => Some(format!("http_{s}")),
        }
    }

    /// A transient failure a retry can fix (not "no credentials" or "live sources off").
    pub fn is_transient(r: &str) -> bool {
        r == NETWORK || r == RATE_LIMITED || r == BAD_RESPONSE || r == SOURCE_FAILED || r.starts_with("http_")
    }
}

/// Whether a reading failed transiently (UsageRefresher.isFailure).
pub fn is_failure(l: &ProviderLimits) -> bool {
    l.reason.as_deref().is_some_and(reason::is_transient)
}

/// One HTTP answer. `retry_after_ms`: the `Retry-After` header as a delay.
#[derive(Debug, Clone)]
pub struct HttpResponse {
    pub status: u16,
    pub body: String,
    pub retry_after_ms: Option<i64>,
}

/// The one HTTP call a live source makes; injectable so tests never touch the network. `Err` = network failure.
pub trait LimitHttp: Send + Sync {
    fn get(&self, url: &str, headers: &[(&str, String)]) -> Result<HttpResponse, ()>;
}

/// ureq over the OS TLS stack, 12 s timeouts.
pub struct UreqHttp {
    agent: Option<ureq::Agent>,
}

impl UreqHttp {
    pub const TIMEOUT: Duration = Duration::from_secs(12);

    pub fn new() -> Self {
        let agent = native_tls::TlsConnector::new().ok().map(|tls| {
            ureq::AgentBuilder::new().timeout_connect(Self::TIMEOUT).timeout(Self::TIMEOUT).tls_connector(Arc::new(tls)).build()
        });
        UreqHttp { agent }
    }
}

impl Default for UreqHttp {
    fn default() -> Self {
        Self::new()
    }
}

impl LimitHttp for UreqHttp {
    fn get(&self, url: &str, headers: &[(&str, String)]) -> Result<HttpResponse, ()> {
        let agent = self.agent.as_ref().ok_or(())?;
        let mut req = agent.get(url);
        for (k, v) in headers {
            req = req.set(k, v);
        }
        let resp = match req.call() {
            Ok(r) => r,
            Err(ureq::Error::Status(_, r)) => r,
            Err(_) => return Err(()),
        };
        let status = resp.status();
        let retry = util::parse_retry_after(resp.header("Retry-After"), util::now_ms());
        let body = resp.into_string().map_err(|_| ())?;
        Ok(HttpResponse { status, body, retry_after_ms: retry })
    }
}

/// A provider's plan limits, read at `now`. Never panics for an expected failure: it reports a reason instead.
pub trait LimitSource: Send + Sync {
    fn provider(&self) -> &str;
    fn read(&self, now: i64) -> ProviderLimits;
}

/// Points ahead (+) or under (-) an even pace: percent minus the elapsed share of the window, to one decimal.
pub fn pace_delta(percent: Option<f64>, resets_at: Option<i64>, window_minutes: i32, now: i64) -> Option<f64> {
    let (p, r) = (percent?, resets_at?);
    if window_minutes <= 0 {
        return None;
    }
    let window_ms = window_minutes as f64 * 60_000.0;
    let elapsed = window_ms - ((r - now) as f64).clamp(0.0, window_ms);
    Some(util::round1(p - elapsed / window_ms * 100.0))
}

fn window(provider: &str, label: &str, minutes: i32, percent: Option<&Value>, resets: Option<&Value>, now: i64) -> LimitWindow {
    let pct = util::percent_of(percent);
    let reset = util::time_of(resets);
    LimitWindow {
        provider: provider.into(),
        label: label.into(),
        window_minutes: minutes,
        percent: pct,
        resets_at: reset,
        observed_at: now,
        source: "live".into(),
        pace_delta: pace_delta(pct, reset, minutes, now),
    }
}

/// Credential, call, status -> reason, parse -> windows.
fn live_read(
    provider: &str,
    now: i64,
    http: &dyn LimitHttp,
    url: &str,
    secret: Option<String>,
    headers: impl FnOnce(&str) -> Vec<(&'static str, String)>,
    parse: impl FnOnce(&str, i64) -> Option<Vec<LimitWindow>>,
) -> ProviderLimits {
    let Some(secret) = secret else { return ProviderLimits::unknown(provider, now, reason::NO_CREDENTIALS) };
    let hs = headers(&secret);
    drop(secret);
    let Ok(resp) = http.get(url, &hs) else { return ProviderLimits::unknown(provider, now, reason::NETWORK) };
    drop(hs);
    if let Some(r) = reason::of_status(resp.status) {
        let mut out = ProviderLimits::unknown(provider, now, &r);
        out.retry_at = resp.retry_after_ms.map(|ms| now + ms);
        return out;
    }
    match parse(&resp.body, now) {
        Some(w) if !w.is_empty() => ProviderLimits { provider: provider.into(), status: "ok".into(), windows: w, reason: None, observed_at: now, stale: false, retry_at: None },
        _ => ProviderLimits::unknown(provider, now, reason::BAD_RESPONSE),
    }
}

/// A function that returns a credential when called (read at each source run, never cached).
pub type Credential = Arc<dyn Fn() -> Option<String> + Send + Sync>;

/// Anthropic plan: `GET https://api.anthropic.com/api/oauth/usage` with the Claude Code CLI's own OAuth token.
pub struct AnthropicOAuthSource {
    http: Arc<dyn LimitHttp>,
    token: Credential,
}

impl AnthropicOAuthSource {
    pub const URL: &'static str = "https://api.anthropic.com/api/oauth/usage";
    pub const PROVIDER: &'static str = "claude";

    pub fn new(http: Arc<dyn LimitHttp>, token: Credential) -> Self {
        AnthropicOAuthSource { http, token }
    }

    /// The token where the CLI keeps it (`CLAUDE_CONFIG_DIR`, else `~/.claude`).
    pub fn local(http: Arc<dyn LimitHttp>) -> Self {
        Self::new(http, Arc::new(|| credentials::anthropic_access_token(&credentials::process_env, &credentials::user_home())))
    }

    /// `five_hour` / `seven_day` objects, or a `limits` array some accounts get instead.
    pub fn parse(body: &str, now: i64) -> Option<Vec<LimitWindow>> {
        let root: Value = serde_json::from_str(body).ok()?;
        let root = root.as_object()?;
        let mut five: Option<&Value> = root.get("five_hour").filter(|v| v.is_object());
        let mut seven: Option<&Value> = root.get("seven_day").filter(|v| v.is_object());
        if let Some(items) = root.get("limits").and_then(Value::as_array) {
            for item in items.iter().filter(|i| i.is_object()) {
                let kind = ["type", "name", "window", "id"].iter().find_map(|k| util::str_field(item, k)).map(str::to_lowercase);
                let Some(kind) = kind else { continue };
                if kind.contains("five") || kind.contains("5h") || kind.contains("session") {
                    five = five.or(Some(item));
                } else if kind.contains("seven") || kind.contains("week") {
                    seven = seven.or(Some(item));
                }
            }
        }
        let w = |label: &str, minutes: i32, o: &Value| {
            window(Self::PROVIDER, label, minutes, o.get("utilization").or(o.get("percent")), o.get("resets_at").or(o.get("resetsAt")), now)
        };
        Some(five.map(|o| w("Current session", 300, o)).into_iter().chain(seven.map(|o| w("Current week", 10_080, o))).collect())
    }
}

impl LimitSource for AnthropicOAuthSource {
    fn provider(&self) -> &str {
        Self::PROVIDER
    }
    fn read(&self, now: i64) -> ProviderLimits {
        live_read(
            Self::PROVIDER,
            now,
            self.http.as_ref(),
            Self::URL,
            (self.token)(),
            |t| {
                vec![
                    ("Authorization", format!("Bearer {t}")),
                    ("anthropic-beta", "oauth-2025-04-20".into()),
                    ("User-Agent", "claude-cli/1.0 (external, cli)".into()),
                    ("Accept", "application/json".into()),
                ]
            },
            Self::parse,
        )
    }
}

/// OpenCode Go: `GET https://opencode.ai/zen/go/v1/usage` with the `opencode-go` key from opencode's auth.json.
pub struct OpenCodeGoSource {
    http: Arc<dyn LimitHttp>,
    key: Credential,
}

impl OpenCodeGoSource {
    pub const URL: &'static str = "https://opencode.ai/zen/go/v1/usage";
    pub const PROVIDER: &'static str = "opencode-go";

    pub fn new(http: Arc<dyn LimitHttp>, key: Credential) -> Self {
        OpenCodeGoSource { http, key }
    }

    pub fn local(http: Arc<dyn LimitHttp>) -> Self {
        Self::new(http, Arc::new(|| credentials::opencode_go_key(&credentials::process_env, &credentials::user_home())))
    }

    /// `usage.rolling` (5h), `usage.weekly` (7d), `usage.monthly` (30d), each `{percent, resetsAt}`.
    pub fn parse(body: &str, now: i64) -> Option<Vec<LimitWindow>> {
        let root: Value = serde_json::from_str(body).ok()?;
        let usage = root.get("usage").filter(|v| v.is_object())?;
        Some(
            [("rolling", "5h limit", 300), ("weekly", "Weekly limit", 10_080), ("monthly", "Monthly limit", 43_200)]
                .iter()
                .filter_map(|(k, label, minutes)| {
                    let o = usage.get(*k).filter(|v| v.is_object())?;
                    Some(window(Self::PROVIDER, label, *minutes, o.get("percent"), o.get("resetsAt").or(o.get("resets_at")), now))
                })
                .collect(),
        )
    }
}

impl LimitSource for OpenCodeGoSource {
    fn provider(&self) -> &str {
        Self::PROVIDER
    }
    fn read(&self, now: i64) -> ProviderLimits {
        live_read(
            Self::PROVIDER,
            now,
            self.http.as_ref(),
            Self::URL,
            (self.key)(),
            |k| vec![("Authorization", format!("Bearer {k}")), ("Accept", "application/json".into())],
            Self::parse,
        )
    }
}

/// A fixed reading (a mock or a plugin-supplied reading); `None` from the closure = off right now.
pub struct FixedSource {
    provider: String,
    windows: Arc<dyn Fn(i64) -> Vec<LimitWindow> + Send + Sync>,
}

impl FixedSource {
    pub fn new(provider: impl Into<String>, windows: Arc<dyn Fn(i64) -> Vec<LimitWindow> + Send + Sync>) -> Self {
        FixedSource { provider: provider.into(), windows }
    }
}

impl LimitSource for FixedSource {
    fn provider(&self) -> &str {
        &self.provider
    }
    fn read(&self, now: i64) -> ProviderLimits {
        let w = (self.windows)(now);
        if w.is_empty() {
            return ProviderLimits::unknown(&self.provider, now, reason::NOT_READ_YET);
        }
        ProviderLimits { provider: self.provider.clone(), status: "ok".into(), windows: w, reason: None, observed_at: now, stale: false, retry_at: None }
    }
}

/// How the service rides out a failing source (settings `limits.usage.*`).
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ReadTuning {
    /// A last good reading is kept (shown stale, used by pacing) while younger than this.
    pub stale_max_ms: i64,
    /// After a transient failure without `Retry-After`: base x 2^(failures-1), at most max.
    pub backoff_base_ms: i64,
    pub backoff_max_ms: i64,
    /// A forced read inside this long after the last read answers the last read.
    pub manual_floor_ms: i64,
}

impl Default for ReadTuning {
    fn default() -> Self {
        ReadTuning { stale_max_ms: 6 * 3_600_000, backoff_base_ms: LimitService::DEFAULT_MIN_INTERVAL_MS, backoff_max_ms: 60 * 60_000, manual_floor_ms: 60_000 }
    }
}

impl ReadTuning {
    pub fn backoff_ms(&self, failures: u32) -> i64 {
        if failures == 0 {
            return 0;
        }
        let base = self.backoff_base_ms.max(1);
        let cap = self.backoff_max_ms.max(base);
        let mut d = base;
        for _ in 0..(failures - 1).min(30) {
            d = (d * 2).min(cap);
        }
        d
    }
}

#[derive(Default)]
struct SourceState {
    last_good: Option<ProviderLimits>,
    failures: u32,
    retry_at: i64,
    last_reason: Option<String>,
}

/// One cache + per-source state set (the live one, and the real one kept apart under a replacement).
#[derive(Default)]
struct ReadState {
    states: HashMap<String, SourceState>,
    cached: Option<Vec<ProviderLimits>>,
    cached_at: i64,
    cached_replaced: bool,
}

/// Sources a plugin offered in place of every other source, and whether pacing judges them.
struct Replacement {
    sources: Vec<Arc<dyn LimitSource>>,
    affects_pacing: Arc<dyn Fn() -> bool + Send + Sync>,
}

pub type LogFn = Arc<dyn Fn(&str) + Send + Sync>;

/// The daemon's limit meters. Every source is read on request at most once per `min_interval_ms` (the answer in
/// between is the cached one, so a burst of requests cannot earn a 429). One source per provider (the first listed
/// wins). Per provider the last good reading is kept: a transient failure answers it marked `stale` while it is
/// younger than the stale limit, and the source is not asked again until its backoff ends, whoever asks.
///
/// Reads are serialised (one live read at a time) but never hold the state lock across HTTP, so
/// [`LimitService::cached_or_refresh`] never blocks behind a slow endpoint.
pub struct LimitService {
    sources: Vec<Arc<dyn LimitSource>>,
    extra: RwLock<Arc<dyn Fn() -> Vec<Arc<dyn LimitSource>> + Send + Sync>>,
    replacement: RwLock<Option<Replacement>>,
    live_enabled: Arc<dyn Fn() -> bool + Send + Sync>,
    tuning: RwLock<Arc<dyn Fn() -> ReadTuning + Send + Sync>>,
    clock: Clock,
    min_interval_ms: i64,
    log: LogFn,
    live: Mutex<ReadState>,
    real: Mutex<ReadState>,
    read_lock: Mutex<()>,
    refreshing: AtomicBool,
    logged_duplicates: Mutex<Vec<String>>,
}

impl LimitService {
    pub const DEFAULT_MIN_INTERVAL_MS: i64 = 5 * 60_000;
    /// `INFANTRY_LIMITS_LIVE=false` (or `0` / `off` / `no`) turns the token-reading HTTP calls off.
    pub const LIVE_ENV: &'static str = "INFANTRY_LIMITS_LIVE";

    pub fn new(sources: Vec<Arc<dyn LimitSource>>, live_enabled: Arc<dyn Fn() -> bool + Send + Sync>, clock: Clock, min_interval_ms: i64, log: LogFn) -> Self {
        LimitService {
            sources,
            extra: RwLock::new(Arc::new(Vec::new)),
            replacement: RwLock::new(None),
            live_enabled,
            tuning: RwLock::new(Arc::new(ReadTuning::default)),
            clock,
            min_interval_ms,
            log,
            live: Mutex::new(ReadState::default()),
            real: Mutex::new(ReadState::default()),
            read_lock: Mutex::new(()),
            refreshing: AtomicBool::new(false),
            logged_duplicates: Mutex::new(Vec::new()),
        }
    }

    pub fn live_from_env(env: &credentials::Env) -> bool {
        !matches!(env(Self::LIVE_ENV).map(|v| v.trim().to_lowercase()).as_deref(), Some("false" | "0" | "off" | "no"))
    }

    /// The Anthropic and OpenCode Go sources over real HTTP, live unless `INFANTRY_LIMITS_LIVE` turns it off.
    pub fn from_env() -> Self {
        let http: Arc<dyn LimitHttp> = Arc::new(UreqHttp::new());
        LimitService::new(
            vec![Arc::new(AnthropicOAuthSource::local(http.clone())), Arc::new(OpenCodeGoSource::local(http))],
            Arc::new(|| LimitService::live_from_env(&credentials::process_env)),
            util::system_clock(),
            Self::DEFAULT_MIN_INTERVAL_MS,
            Arc::new(|m| eprintln!("{m}")),
        )
    }

    /// No sources: answers an empty list, reads nothing.
    pub fn none() -> Self {
        LimitService::new(Vec::new(), Arc::new(|| false), util::system_clock(), Self::DEFAULT_MIN_INTERVAL_MS, Arc::new(|_| {}))
    }

    /// Settings read at every snapshot, so a changed setting counts at once.
    pub fn set_tuning(&self, f: Arc<dyn Fn() -> ReadTuning + Send + Sync>) {
        *self.tuning.write().unwrap_or_else(|p| p.into_inner()) = f;
    }

    /// Usage sources contributed by harness plugins, read on every call.
    pub fn set_extra_sources(&self, f: Arc<dyn Fn() -> Vec<Arc<dyn LimitSource>> + Send + Sync>) {
        *self.extra.write().unwrap_or_else(|p| p.into_inner()) = f;
    }

    /// Sources read INSTEAD of every other one (a demo or mock); empty clears it. `affects_pacing` false = pacing keeps
    /// judging the real readings.
    pub fn set_replacement(&self, sources: Vec<Arc<dyn LimitSource>>, affects_pacing: Arc<dyn Fn() -> bool + Send + Sync>) {
        *self.replacement.write().unwrap_or_else(|p| p.into_inner()) = if sources.is_empty() { None } else { Some(Replacement { sources, affects_pacing }) };
    }

    fn tuning(&self) -> ReadTuning {
        (self.tuning.read().unwrap_or_else(|p| p.into_inner()))()
    }

    fn replaced(&self) -> bool {
        self.replacement.read().unwrap_or_else(|p| p.into_inner()).is_some()
    }

    fn real_sources(&self) -> Vec<Arc<dyn LimitSource>> {
        let extra = (self.extra.read().unwrap_or_else(|p| p.into_inner()))();
        let mut kept: Vec<Arc<dyn LimitSource>> = Vec::new();
        for s in self.sources.iter().cloned().chain(extra) {
            if kept.iter().any(|k| k.provider() == s.provider()) {
                let mut logged = self.logged_duplicates.lock().unwrap_or_else(|p| p.into_inner());
                if !logged.iter().any(|p| p == s.provider()) {
                    logged.push(s.provider().to_string());
                    (self.log)(&format!("[infantry] limits {}: a second usage source for the same provider is ignored (one read per refresh)", s.provider()));
                }
                continue;
            }
            kept.push(s);
        }
        kept
    }

    fn all_sources(&self) -> Vec<Arc<dyn LimitSource>> {
        if let Some(r) = self.replacement.read().unwrap_or_else(|p| p.into_inner()).as_ref() {
            return r.sources.clone();
        }
        self.real_sources()
    }

    /// Every provider this service would read.
    pub fn providers(&self) -> Vec<String> {
        self.all_sources().iter().map(|s| s.provider().to_string()).collect()
    }

    fn all_unknown(&self, sources: &[Arc<dyn LimitSource>], now: i64, why: &str) -> Vec<ProviderLimits> {
        sources.iter().map(|s| ProviderLimits::unknown(s.provider(), now, why)).collect()
    }

    /// A fresh read when the cache is older than the minimum interval (or `force`, outside the manual floor).
    pub fn snapshot(&self, force: bool) -> Vec<ProviderLimits> {
        let rep = self.replaced();
        if !(self.live_enabled)() && !rep {
            return self.all_unknown(&self.all_sources(), (self.clock)(), reason::LIVE_SOURCES_OFF);
        }
        let _one = self.read_lock.lock().unwrap_or_else(|p| p.into_inner());
        let now = (self.clock)();
        let t = self.tuning();
        {
            let mut st = self.live.lock().unwrap_or_else(|p| p.into_inner());
            if st.cached_replaced != rep {
                st.cached = None;
            }
            if let Some(last) = &st.cached {
                let age = now - st.cached_at;
                if (!force && age < self.min_interval_ms) || (force && age < t.manual_floor_ms) {
                    return expire(last, now, &t);
                }
            }
        }
        let fresh: Vec<ProviderLimits> = self.all_sources().iter().map(|s| self.read_one(s.as_ref(), now, &t, &self.live)).collect();
        let mut st = self.live.lock().unwrap_or_else(|p| p.into_inner());
        st.cached = Some(fresh.clone());
        st.cached_at = now;
        st.cached_replaced = rep;
        fresh
    }

    fn read_one(&self, s: &dyn LimitSource, now: i64, t: &ReadTuning, state: &Mutex<ReadState>) -> ProviderLimits {
        let provider = s.provider().to_string();
        {
            let st = state.lock().unwrap_or_else(|p| p.into_inner());
            if let Some(ss) = st.states.get(&provider) {
                if ss.retry_at > now {
                    return failed_view(&provider, ss, now, t);
                }
            }
        }
        let r = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| s.read(now)))
            .unwrap_or_else(|_| ProviderLimits::unknown(&provider, now, reason::SOURCE_FAILED));
        let mut st = state.lock().unwrap_or_else(|p| p.into_inner());
        let ss = st.states.entry(provider.clone()).or_default();
        match r.reason.as_deref() {
            None => {
                *ss = SourceState { last_good: Some(r.clone()), ..Default::default() };
                r
            }
            Some(why) if !reason::is_transient(why) => {
                // Not signed in, no subscription: a retry cannot fix it, and an old reading would mislead.
                *ss = SourceState { last_reason: Some(why.to_string()), ..Default::default() };
                (self.log)(&format!("[infantry] limits {provider}: unknown ({why})"));
                r
            }
            Some(why) => {
                ss.failures += 1;
                ss.last_reason = Some(why.to_string());
                ss.retry_at = r.retry_at.map(|a| a.max(now)).unwrap_or(now + t.backoff_ms(ss.failures));
                (self.log)(&format!("[infantry] limits {provider}: unknown ({why}); next read in {} min", (ss.retry_at - now) / 60_000));
                failed_view(&provider, ss, now, t)
            }
        }
    }

    /// The last read, never blocking. A stale or missing read starts a refresh on a background thread; until it lands
    /// the answer is the old read, or every source `not_read_yet` (pacing then fails open).
    pub fn cached_or_refresh(self: &Arc<Self>) -> Vec<ProviderLimits> {
        let now = (self.clock)();
        let rep = self.replaced();
        if !(self.live_enabled)() && !rep {
            return self.all_unknown(&self.all_sources(), now, reason::LIVE_SOURCES_OFF);
        }
        let (last, at) = {
            let st = self.live.lock().unwrap_or_else(|p| p.into_inner());
            if st.cached_replaced != rep { (None, 0) } else { (st.cached.clone(), st.cached_at) }
        };
        if last.is_none() || now - at >= self.min_interval_ms {
            self.refresh_in_background(|_| {});
        }
        match last {
            Some(l) => expire(&l, now, &self.tuning()),
            None => self.all_unknown(&self.all_sources(), now, reason::NOT_READ_YET),
        }
    }

    /// [`Self::cached_or_refresh`] for the pacing rule: the real sources' last read while a replacement that does not
    /// count for pacing is offered.
    pub fn cached_or_refresh_for_pacing(self: &Arc<Self>) -> Vec<ProviderLimits> {
        let affects = self.replacement.read().unwrap_or_else(|p| p.into_inner()).as_ref().map(|r| (r.affects_pacing)());
        if affects.unwrap_or(true) {
            return self.cached_or_refresh();
        }
        let now = (self.clock)();
        let (last, at) = {
            let st = self.real.lock().unwrap_or_else(|p| p.into_inner());
            (st.cached.clone(), st.cached_at)
        };
        if last.is_none() || now - at >= self.min_interval_ms {
            let me = self.clone();
            std::thread::Builder::new().name("infantry-limits-real".into()).spawn(move || drop(me.real_snapshot())).ok();
        }
        match last {
            Some(l) => expire(&l, now, &self.tuning()),
            None => self.all_unknown(&self.real_sources(), now, reason::NOT_READ_YET),
        }
    }

    fn real_snapshot(&self) -> Vec<ProviderLimits> {
        let real = self.real_sources();
        let now = (self.clock)();
        if !(self.live_enabled)() {
            return self.all_unknown(&real, now, reason::LIVE_SOURCES_OFF);
        }
        let _one = self.read_lock.lock().unwrap_or_else(|p| p.into_inner());
        let t = self.tuning();
        {
            let st = self.real.lock().unwrap_or_else(|p| p.into_inner());
            if let Some(c) = &st.cached {
                if now - st.cached_at < self.min_interval_ms {
                    return expire(c, now, &t);
                }
            }
        }
        let fresh: Vec<ProviderLimits> = real.iter().map(|s| self.read_one(s.as_ref(), now, &t, &self.real)).collect();
        let mut st = self.real.lock().unwrap_or_else(|p| p.into_inner());
        st.cached = Some(fresh.clone());
        st.cached_at = now;
        fresh
    }

    /// [`Self::snapshot`] on a background thread (a live read can take 12 s per source), then `on_ready`. At most one
    /// background refresh runs at a time; a call while one runs does nothing.
    pub fn refresh_in_background(self: &Arc<Self>, on_ready: impl FnOnce(Vec<ProviderLimits>) + Send + 'static) {
        if self.all_sources().is_empty() || self.refreshing.swap(true, Ordering::SeqCst) {
            return;
        }
        let me = self.clone();
        let spawned = std::thread::Builder::new().name("infantry-limits".into()).spawn(move || {
            let r = me.snapshot(false);
            me.refreshing.store(false, Ordering::SeqCst);
            on_ready(r);
        });
        if spawned.is_err() {
            self.refreshing.store(false, Ordering::SeqCst);
        }
    }
}

fn failed_view(provider: &str, st: &SourceState, now: i64, t: &ReadTuning) -> ProviderLimits {
    let why = st.last_reason.clone().unwrap_or_else(|| reason::SOURCE_FAILED.into());
    let retry = (st.retry_at > now).then_some(st.retry_at);
    match st.last_good.as_ref().filter(|g| now - g.observed_at < t.stale_max_ms) {
        Some(g) => ProviderLimits { stale: true, reason: Some(why), retry_at: retry, ..g.clone() },
        None => ProviderLimits { retry_at: retry, ..ProviderLimits::unknown(provider, now, &why) },
    }
}

/// A reading that grew older than the stale limit while cached reads unknown from then on (pacing fails open).
fn expire(list: &[ProviderLimits], now: i64, t: &ReadTuning) -> Vec<ProviderLimits> {
    list.iter()
        .map(|p| {
            if !p.windows.is_empty() && now - p.observed_at >= t.stale_max_ms {
                ProviderLimits { retry_at: p.retry_at, ..ProviderLimits::unknown(&p.provider, now, p.reason.as_deref().unwrap_or(reason::SOURCE_FAILED)) }
            } else {
                p.clone()
            }
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicI64, AtomicUsize};

    struct FakeHttp {
        status: Mutex<u16>,
        body: String,
        calls: AtomicUsize,
        seen_auth: Mutex<Vec<String>>,
    }
    impl LimitHttp for FakeHttp {
        fn get(&self, _url: &str, headers: &[(&str, String)]) -> Result<HttpResponse, ()> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            self.seen_auth.lock().unwrap().extend(headers.iter().filter(|(k, _)| *k == "Authorization").map(|(_, v)| v.clone()));
            Ok(HttpResponse { status: *self.status.lock().unwrap(), body: self.body.clone(), retry_after_ms: None })
        }
    }

    const NOW: i64 = 1_800_000_000_000;

    #[test]
    fn anthropic_body_parses_into_two_windows_with_pace() {
        let body = format!(r#"{{"five_hour":{{"utilization":30,"resets_at":{}}},"seven_day":{{"utilization":"60%","resets_at":"2027-01-15T08:00:00Z"}}}}"#, (NOW + 150 * 60_000) / 1000);
        let w = AnthropicOAuthSource::parse(&body, NOW).unwrap();
        assert_eq!(w.len(), 2);
        assert_eq!(w[0].label, "Current session");
        // half of a 5h window gone, 30% used: 20 points under pace
        assert_eq!(w[0].pace_delta, Some(-20.0));
        assert_eq!(w[1].percent, Some(60.0));
        let alt = AnthropicOAuthSource::parse(r#"{"limits":[{"type":"weekly","percent":5}]}"#, NOW).unwrap();
        assert_eq!(alt[0].label, "Current week");
        assert!(OpenCodeGoSource::parse(r#"{"usage":{"weekly":{"percent":12,"resetsAt":0}}}"#, NOW).unwrap()[0].label == "Weekly limit");
    }

    #[test]
    fn failures_back_off_keep_the_last_good_reading_and_never_leak_the_token() {
        let http = Arc::new(FakeHttp { status: Mutex::new(200), body: r#"{"five_hour":{"utilization":10}}"#.into(), calls: AtomicUsize::new(0), seen_auth: Mutex::new(vec![]) });
        let now = Arc::new(AtomicI64::new(NOW));
        let n2 = now.clone();
        let src: Arc<dyn LimitSource> = Arc::new(AnthropicOAuthSource::new(http.clone(), Arc::new(|| Some("SECRET-T".into()))));
        let svc = LimitService::new(vec![src], Arc::new(|| true), Arc::new(move || n2.load(Ordering::SeqCst)), 60_000, Arc::new(|_| {}));
        assert_eq!(svc.snapshot(false)[0].status, "ok");
        assert_eq!(svc.snapshot(false)[0].status, "ok");
        assert_eq!(http.calls.load(Ordering::SeqCst), 1, "cached inside the min interval");
        *http.status.lock().unwrap() = 429;
        now.fetch_add(120_000, Ordering::SeqCst);
        let r = svc.snapshot(false);
        assert!(r[0].stale && r[0].reason.as_deref() == Some("rate_limited") && r[0].windows.len() == 1);
        assert!(r[0].retry_at.is_some());
        now.fetch_add(120_000, Ordering::SeqCst);
        svc.snapshot(false);
        assert_eq!(http.calls.load(Ordering::SeqCst), 2, "backing off: not asked again");
        let wire = serde_json::to_string(&r).unwrap();
        assert!(!wire.contains("SECRET-T"));
        assert_eq!(http.seen_auth.lock().unwrap()[0], "Bearer SECRET-T");
    }

    #[test]
    fn no_credentials_and_live_off_are_unknown_never_zero() {
        let http: Arc<dyn LimitHttp> = Arc::new(FakeHttp { status: Mutex::new(200), body: String::new(), calls: AtomicUsize::new(0), seen_auth: Mutex::new(vec![]) });
        let src: Arc<dyn LimitSource> = Arc::new(OpenCodeGoSource::new(http, Arc::new(|| None)));
        let svc = LimitService::new(vec![src.clone()], Arc::new(|| true), Arc::new(|| NOW), 0, Arc::new(|_| {}));
        let r = svc.snapshot(false);
        assert_eq!((r[0].status.as_str(), r[0].reason.as_deref()), ("unknown", Some("no_credentials")));
        let off = LimitService::new(vec![src], Arc::new(|| false), Arc::new(|| NOW), 0, Arc::new(|_| {}));
        assert_eq!(off.snapshot(true)[0].reason.as_deref(), Some("live_sources_off"));
    }

    #[test]
    fn backoff_doubles_up_to_the_cap() {
        let t = ReadTuning { stale_max_ms: 1, backoff_base_ms: 1000, backoff_max_ms: 5000, manual_floor_ms: 0 };
        assert_eq!([0, 1, 2, 3, 4].map(|f| t.backoff_ms(f)), [0, 1000, 2000, 4000, 5000]);
    }
}
