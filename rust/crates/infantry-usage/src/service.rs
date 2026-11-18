// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! [`UsageService`]: the whole usage subsystem behind one handle. The daemon opens it once, registers
//! [`crate::UsageUnit`] over it, feeds it session events, and calls [`UsageService::start_background`].
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use infantry_core::bus::EventBus;
use infantry_protocol::envelope::Envelope;
use infantry_protocol::frames;
use infantry_protocol::usage::{ProviderLimits, ProviderState, ProviderStateFrame, ProviderStatus, UsageReport, UsageSet};
use serde_json::Value;

use crate::config::{FileStore, UsageConfig, UsageStore};
use crate::governor::{self, WorkersReport};
use crate::limits::{LimitService, LogFn};
use crate::meter::{self, SessionInfo, TurnOrigin, UsageMeter};
use crate::pace::{self, PaceQuery, PaceSettings, PaceStore};
use crate::pacing::{PacingVerdict, UsagePacing};
use crate::refresher::{Refresher, Ticker};
use crate::tracker::{OperatorBudget, ProviderStateTracker};
use crate::util::{self, until, Clock};

/// The meters of every live ticket worker (one entry per worker; see [`crate::pacing::meter_for`]).
pub type WorkerMeters = Arc<dyn Fn() -> Vec<String> + Send + Sync>;

/// Where the files live and what the service reads from the daemon.
pub struct UsageDeps {
    pub state_dir: PathBuf,
    /// The owner's settings (the daemon's settings writer); `None` = `usage-settings.json` in the state dir.
    pub store: Option<Arc<dyn UsageStore>>,
    pub worker_meters: WorkerMeters,
    /// `None` = the Anthropic and OpenCode Go sources over real HTTP.
    pub limits: Option<Arc<LimitService>>,
    pub clock: Clock,
    pub log: LogFn,
}

impl UsageDeps {
    pub fn new(state_dir: impl Into<PathBuf>, worker_meters: WorkerMeters) -> Self {
        UsageDeps { state_dir: state_dir.into(), store: None, worker_meters, limits: None, clock: util::system_clock(), log: Arc::new(|m| eprintln!("{m}")) }
    }
}

pub struct UsageService {
    pub config: UsageConfig,
    pub limits: Arc<LimitService>,
    pub pacing: UsagePacing,
    pub tracker: Arc<ProviderStateTracker>,
    pub budget: OperatorBudget,
    pub meter: Option<UsageMeter>,
    pub pace: PaceStore,
    clock: Clock,
    log: LogFn,
    tickers: Mutex<Vec<Ticker>>,
    /// The bus `start_background` was given, shared with the tracker's sink (built before the bus is known).
    bus: Arc<Mutex<Option<EventBus>>>,
}

fn file_in(dir: &Path, name: &str) -> PathBuf {
    dir.join(name)
}

impl UsageService {
    pub fn open(deps: UsageDeps) -> Arc<Self> {
        let store: Arc<dyn UsageStore> = deps.store.unwrap_or_else(|| Arc::new(FileStore::open(file_in(&deps.state_dir, "usage-settings.json"))));
        let config = UsageConfig::new(store);
        let limits = deps.limits.unwrap_or_else(|| Arc::new(LimitService::from_env()));
        {
            let c = config.clone();
            limits.set_tuning(Arc::new(move || c.tuning()));
        }
        let clock = deps.clock;
        let bus: Arc<Mutex<Option<EventBus>>> = Arc::new(Mutex::new(None));
        let sink_bus = bus.clone();
        let tracker = Arc::new(ProviderStateTracker::new(
            clock.clone(),
            Arc::new(move |s: &ProviderState| {
                if let Some(b) = sink_bus.lock().unwrap_or_else(|p| p.into_inner()).as_ref() {
                    let body = ProviderStateFrame { state: s.clone(), r#type: frames::PROVIDER_STATE.into() };
                    b.publish(Envelope::new(frames::PROVIDER_STATE, serde_json::to_string(&body).unwrap_or_default()));
                }
            }),
            config.quota_hit_hold_ms(),
        ));
        let pacing = {
            let (l1, l2) = (limits.clone(), limits.clone());
            let (c1, c2, c3) = (config.clone(), config.clone(), config.clone());
            UsagePacing {
                limits: Arc::new(move || l1.cached_or_refresh()),
                pacing_limits: Arc::new(move || l2.cached_or_refresh_for_pacing()),
                rule: Arc::new(move || c1.rule()),
                manual: Arc::new(move || c2.manual()),
                overrides: Arc::new(move || c3.overrides()),
                worker_meters: deps.worker_meters,
                clock: clock.clone(),
            }
        };
        let dir = deps.state_dir.clone();
        let meter = match UsageMeter::open(
            &file_in(&dir, "usage-meter.db"),
            Arc::new(|| UsageMeter::keep_weeks_from_env(&crate::credentials::process_env)),
            clock.clone(),
            Arc::new(move || meter::prices_from(&dir)),
        ) {
            Ok(m) => Some(m),
            Err(e) => {
                (deps.log)(&format!("[infantry] usage meter off: cannot open usage-meter.db ({e})"));
                None
            }
        };
        let svc = Arc::new(UsageService {
            budget: OperatorBudget::from_env(&crate::credentials::process_env, clock.clone()),
            pace: PaceStore::open(file_in(&deps.state_dir.join("pace"), "samples.jsonl")),
            config,
            limits,
            pacing,
            tracker,
            meter,
            clock,
            log: deps.log,
            tickers: Mutex::new(Vec::new()),
            bus,
        });
        svc
    }

    fn now(&self) -> i64 {
        (self.clock)()
    }

    /// The report from the last live read (never blocks; a stale cache refreshes in the background).
    pub fn report(&self) -> UsageReport {
        self.pacing.report()
    }

    /// A fresh live read first (`usage_get` with `force`; inside the manual floor the cache answers). Blocks on HTTP.
    pub fn report_fresh(&self, force: bool) -> UsageReport {
        let l = self.limits.snapshot(force);
        self.pacing.report_from(&l)
    }

    pub fn set(&self, req: &UsageSet) -> Result<UsageReport, String> {
        self.config.apply(req, self.now())?;
        Ok(self.report())
    }

    /// The `limits` answer. Blocks on HTTP when the cache is old.
    pub fn limits(&self, force: bool) -> Vec<ProviderLimits> {
        self.limits.snapshot(force)
    }

    /// Every tracked provider plus every meter provider (unknown until something is seen).
    pub fn provider_states(&self) -> Vec<ProviderState> {
        let mut out = self.tracker.states();
        let have: BTreeSet<String> = out.iter().map(|s| s.provider.clone()).collect();
        for p in self.limits.providers() {
            if !have.contains(&p) {
                out.push(ProviderState { provider: p, status: ProviderStatus::Unknown, window: None, utilization: None, resets_at: None, observed_at: None, source: "none".into() });
            }
        }
        out
    }

    pub fn meter_report(&self) -> Result<meter::Report, String> {
        self.meter.as_ref().ok_or_else(|| "the usage meter is off (usage-meter.db did not open)".to_string())?.report()
    }

    pub fn pace_settings(&self) -> PaceSettings {
        let c = self.config.clone();
        PaceSettings::from(move |k| c.text(k))
    }

    pub fn pace_report(&self, body: &str) -> Value {
        let now = self.now();
        pace::report(&self.pace, &self.pace_settings(), &PaceQuery::parse(body, now), now)
    }

    pub fn workers(&self) -> WorkersReport {
        governor::workers_report(&self.report())
    }

    /// Whether one more ticket worker may start on `meter` (the start gate).
    pub fn check(&self, meter: &str) -> PacingVerdict {
        self.pacing.check(meter)
    }

    /// `infantry_usage_override {meter, value, hours?}` for `role`: `value` = a worker count, `none` (no limit) or
    /// `clear`. The default end is the meter's reset when it is ahead, else two hours. Returns the rendered line.
    pub fn override_allowance(&self, meter: &str, value: &str, hours: Option<f64>, role: &str) -> Result<String, String> {
        let roles = self.config.override_roles();
        if !roles.iter().any(|r| r == &role.trim().to_lowercase()) {
            return Err(format!("only {} may override usage pacing (setting {})", roles.join(", "), crate::config::OVERRIDE_ROLES));
        }
        let now = self.now();
        let m = meter.trim().to_lowercase();
        let base = UsageSet { meter: m.clone(), manual_percent: None, manual_resets_at: None, clear_manual: false, override_workers: None, override_until: None, clear_override: false, rule: None };
        let v = value.trim().to_lowercase();
        if v == "clear" {
            self.config.apply(&UsageSet { clear_override: true, ..base }, now)?;
            return Ok(format!("override cleared for {m}: the pacing rule decides again"));
        }
        let workers = match v.as_str() {
            "none" | "" | "no limit" => None,
            n => Some(n.parse::<i32>().map_err(|_| format!("value must be a worker count, none or clear (got '{value}')"))?),
        };
        let resets = self.report().meters.into_iter().find(|x| x.meter == m).and_then(|x| x.resets_at).filter(|r| *r > now);
        let until_at = match hours {
            Some(h) if h > 0.0 => now + (h * 3_600_000.0) as i64,
            _ => resets.unwrap_or(now + 2 * 3_600_000),
        };
        self.config.apply(&UsageSet { override_workers: workers, override_until: Some(until_at), ..base }, now)?;
        Ok(format!(
            "override active: {} until {} (in {})",
            workers.map(|w| w.to_string()).unwrap_or_else(|| "no limit".into()),
            util::stamp(until_at),
            until(until_at, now)
        ))
    }

    // ---- session events ----

    pub fn on_turn(&self, session: &SessionInfo, origin: &TurnOrigin) {
        if let Some(m) = &self.meter {
            m.on_turn(session, origin);
        }
    }

    /// One stdout line: the meter counts it, the tracker reads quota and rate-limit lines from it.
    pub fn on_stdout(&self, session: &SessionInfo, line: &str) {
        if let Some(m) = &self.meter {
            m.on_stdout(session, line);
        }
        self.tracker.observe_stream_line(&session.provider, line);
    }

    pub fn on_exit(&self, session: &SessionInfo) {
        match self.meter.as_ref().and_then(|m| m.on_exit(session)) {
            Some(t) => self.budget.record(&infantry_protocol::usage::RunUsage {
                run_id: format!("{}:{}", session.id, session.run_count),
                provider: session.provider.clone(),
                model: session.model.clone().unwrap_or(t.model),
                input: t.input,
                output: t.output,
                reasoning: t.reasoning,
                cache_read: t.cache_read,
                cache_write: t.cache_write,
                cost_usd: t.cost_usd,
            }),
            None => self.budget.record_run(&session.provider),
        }
    }

    pub fn on_stalled(&self, provider: &str) {
        self.tracker.mark_stalled(provider);
    }

    // ---- background ----

    /// Frames the background work pushes: `usage_report` after every refresh, `limits` alongside it.
    pub fn report_envelope(&self, report: &UsageReport) -> Envelope {
        Envelope::new(frames::USAGE_REPORT, serde_json::to_string(report).unwrap_or_default())
    }

    /// The refresher (first read after a minute), the Pace sampler (every minute), the Pace prune (hourly) and the meter
    /// prune (every 6 h). Idempotent; [`UsageService::stop`] ends them.
    pub fn start_background(self: &Arc<Self>, bus: EventBus) {
        let mut tickers = self.tickers.lock().unwrap_or_else(|p| p.into_inner());
        if !tickers.is_empty() {
            return;
        }
        *self.bus.lock().unwrap_or_else(|p| p.into_inner()) = Some(bus.clone());
        if let Some(m) = &self.meter {
            let b = bus.clone();
            m.set_on_recorded(Arc::new(move || {
                b.emit("usage.meter.recorded", Value::Null);
            }));
        }
        let (me, b) = (Arc::downgrade(self), bus.clone());
        let read_me = me.clone();
        let refresher = Refresher::new(
            Box::new(move || read_me.upgrade().map(|s| s.limits.snapshot(false)).unwrap_or_default()),
            {
                let c = self.config.clone();
                Box::new(move || c.refresh_minutes())
            },
            Box::new(move || {
                if let Some(s) = me.upgrade() {
                    let r = s.report();
                    b.publish(s.report_envelope(&r));
                    b.publish(Envelope::new(frames::LIMITS, serde_json::to_string(&s.limits.cached_or_refresh()).unwrap_or_default()));
                }
            }),
            self.clock.clone(),
        );
        tickers.push(Ticker::spawn("infantry-usage-refresh", crate::refresher::IDLE_RECHECK_MS, move || refresher.tick()));
        let me = Arc::downgrade(self);
        tickers.push(Ticker::spawn("infantry-pace-sample", pace::SAMPLE_EVERY_MS, move || {
            if let Some(s) = me.upgrade() {
                s.pace.record_report(&s.report());
            }
            pace::SAMPLE_EVERY_MS
        }));
        let me = Arc::downgrade(self);
        tickers.push(Ticker::spawn("infantry-pace-prune", 5_000, move || {
            if let Some(s) = me.upgrade() {
                let days = s.pace_settings().retention_days;
                s.pace.prune(days, s.now());
            }
            pace::PRUNE_EVERY_MS
        }));
        let me = Arc::downgrade(self);
        tickers.push(Ticker::spawn("infantry-usage-meter-prune", 5_000, move || {
            if let Some(s) = me.upgrade() {
                if let Some(Err(e)) = s.meter.as_ref().map(|m| m.prune()) {
                    (s.log)(&format!("[infantry] usage meter prune failed: {e}"));
                }
            }
            meter::PRUNE_EVERY_MS
        }));
    }

    pub fn stop(&self) {
        for mut t in self.tickers.lock().unwrap_or_else(|p| p.into_inner()).drain(..) {
            t.stop();
        }
    }
}
