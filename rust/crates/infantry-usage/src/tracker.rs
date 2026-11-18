// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Provider limit state seen on the agents' own output (a `rate_limit_event`, a quota error line, a stall), and the
//! operator budget counted from the daemon's own runs. Exhaustion is declared only by an injected classifier or a
//! harness-reported quota error; a stall is `stalled` (the stream cannot tell it from free-quota exhaustion).
use std::collections::{BTreeMap, HashMap};
use std::sync::{Arc, Mutex};

use infantry_protocol::usage::{ProviderState, ProviderStatus, RunUsage};
use serde_json::Value;

use crate::util::Clock;

/// The source of a quota-hit mark; only these expire at their `resetsAt`.
pub const QUOTA_HIT_SOURCE: &str = "quota-hit";
/// Phrases harnesses print on a quota error (opencode Zen "Free usage exceeded"; OpenAI-style insufficient_quota).
pub const QUOTA_ERROR_PHRASES: &[&str] = &["free usage exceeded", "usage exceeded", "insufficient_quota", "quota exceeded"];

pub fn is_quota_error_line(line: &str) -> bool {
    let l = line.to_lowercase();
    QUOTA_ERROR_PHRASES.iter().any(|p| l.contains(p))
}

fn unknown(provider: &str) -> ProviderState {
    ProviderState { provider: provider.into(), status: ProviderStatus::Unknown, window: None, utilization: None, resets_at: None, observed_at: None, source: "none".into() }
}

pub type StateSink = Arc<dyn Fn(&ProviderState) + Send + Sync>;

pub struct ProviderStateTracker {
    states: Mutex<BTreeMap<String, ProviderState>>,
    clock: Clock,
    sink: StateSink,
    exhaustion: Arc<dyn Fn(&str) -> bool + Send + Sync>,
    quota_hit_hold_ms: i64,
}

impl ProviderStateTracker {
    pub fn new(clock: Clock, sink: StateSink, quota_hit_hold_ms: i64) -> Self {
        ProviderStateTracker { states: Mutex::new(BTreeMap::new()), clock, sink, exhaustion: Arc::new(|_| false), quota_hit_hold_ms }
    }

    /// The classifier that declares a `rate_limit_event` line an exhaustion (none by default).
    pub fn with_exhaustion(mut self, f: Arc<dyn Fn(&str) -> bool + Send + Sync>) -> Self {
        self.exhaustion = f;
        self
    }

    /// A stored state; a quota-hit mark whose reset has passed reads `unknown` again.
    pub fn state(&self, provider: &str) -> ProviderState {
        let states = self.states.lock().unwrap_or_else(|p| p.into_inner());
        match states.get(provider) {
            Some(s) if s.status == ProviderStatus::Exhausted && s.source == QUOTA_HIT_SOURCE && s.resets_at.is_some_and(|r| (self.clock)() >= r) => unknown(provider),
            Some(s) => s.clone(),
            None => unknown(provider),
        }
    }

    pub fn states(&self) -> Vec<ProviderState> {
        let names: Vec<String> = self.states.lock().unwrap_or_else(|p| p.into_inner()).keys().cloned().collect();
        names.iter().map(|n| self.state(n)).collect()
    }

    /// Daemon startup: the last observations persisted before a restart, publishing nothing.
    pub fn restore(&self, saved: Vec<ProviderState>) {
        let mut s = self.states.lock().unwrap_or_else(|p| p.into_inner());
        for st in saved {
            s.insert(st.provider.clone(), st);
        }
    }

    /// A harness reported a quota hit: `exhausted` until `resets_at` (`None` = until another observation).
    pub fn mark_quota_hit(&self, provider: &str, resets_at: Option<i64>) -> ProviderState {
        self.publish(ProviderState { status: ProviderStatus::Exhausted, resets_at, observed_at: Some((self.clock)()), source: QUOTA_HIT_SOURCE.into(), ..unknown(provider) })
    }

    pub fn mark_stalled(&self, provider: &str) -> ProviderState {
        self.publish(ProviderState { status: ProviderStatus::Stalled, observed_at: Some((self.clock)()), source: "stall".into(), ..unknown(provider) })
    }

    /// The new state when `line` is a quota error or a `rate_limit_event`, else `None`.
    pub fn observe_stream_line(&self, provider: &str, line: &str) -> Option<ProviderState> {
        if is_quota_error_line(line) {
            return Some(self.mark_quota_hit(provider, Some((self.clock)() + self.quota_hit_hold_ms)));
        }
        if !line.contains("rate_limit_event") {
            return None;
        }
        let obj: Value = serde_json::from_str(line).ok()?;
        if obj.get("type")?.as_str()? != "rate_limit_event" {
            return None;
        }
        let info = obj.get("rate_limit_info");
        let next = ProviderState {
            provider: provider.into(),
            status: if (self.exhaustion)(line) { ProviderStatus::Exhausted } else { ProviderStatus::Unknown },
            window: info.and_then(|i| i.get("rateLimitType")).and_then(Value::as_str).map(str::to_owned),
            utilization: info.and_then(|i| crate::util::f64_field(i, "utilization")),
            resets_at: info.and_then(|i| crate::util::long_field(i, "resetsAt")),
            observed_at: Some((self.clock)()),
            source: "stream".into(),
        };
        Some(self.publish(next))
    }

    fn publish(&self, next: ProviderState) -> ProviderState {
        self.states.lock().unwrap_or_else(|p| p.into_inner()).insert(next.provider.clone(), next.clone());
        (self.sink)(&next);
        next
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct BudgetSnapshot {
    pub runs: i64,
    pub tokens: i64,
}

/// Operator budget per provider per local calendar day. Never called "quota".
pub struct OperatorBudget {
    max_runs_per_day: Option<i64>,
    max_tokens_per_day: Option<i64>,
    clock: Clock,
    days: Mutex<HashMap<String, (String, BudgetSnapshot)>>,
}

impl OperatorBudget {
    pub fn new(max_runs_per_day: Option<i64>, max_tokens_per_day: Option<i64>, clock: Clock) -> Self {
        OperatorBudget { max_runs_per_day, max_tokens_per_day, clock, days: Mutex::new(HashMap::new()) }
    }

    /// `INFANTRY_BUDGET_RUNS_PER_DAY` / `INFANTRY_BUDGET_TOKENS_PER_DAY`; unset or invalid = no limit.
    pub fn from_env(env: &crate::credentials::Env, clock: Clock) -> Self {
        let read = |k: &str| env(k).and_then(|v| v.trim().parse::<i64>().ok()).filter(|n| *n >= 0);
        OperatorBudget::new(read("INFANTRY_BUDGET_RUNS_PER_DAY"), read("INFANTRY_BUDGET_TOKENS_PER_DAY"), clock)
    }

    fn with_today<T>(&self, provider: &str, f: impl FnOnce(&mut BudgetSnapshot) -> T) -> T {
        let today = crate::util::local_day((self.clock)());
        let mut days = self.days.lock().unwrap_or_else(|p| p.into_inner());
        let e = days.entry(provider.to_string()).or_insert_with(|| (today.clone(), BudgetSnapshot::default()));
        if e.0 != today {
            *e = (today, BudgetSnapshot::default());
        }
        f(&mut e.1)
    }

    pub fn record(&self, usage: &RunUsage) {
        self.with_today(&usage.provider, |d| {
            d.runs += 1;
            d.tokens += usage.input + usage.output + usage.reasoning;
        });
    }

    /// A run that reported no usage still counts as a run; its tokens are not invented.
    pub fn record_run(&self, provider: &str) {
        self.with_today(provider, |d| d.runs += 1);
    }

    pub fn restore(&self, provider: &str, runs: i64, tokens: i64) {
        self.with_today(provider, |d| *d = BudgetSnapshot { runs, tokens });
    }

    pub fn snapshot(&self, provider: &str) -> BudgetSnapshot {
        self.with_today(provider, |d| *d)
    }

    pub fn is_spent(&self, provider: &str) -> bool {
        let s = self.snapshot(provider);
        self.max_runs_per_day.is_some_and(|m| s.runs >= m) || self.max_tokens_per_day.is_some_and(|m| s.tokens >= m)
    }
}

/// Where a start came from: unattended starts are refused at a spent limit, human starts only warned.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LaunchOrigin {
    Human,
    Unattended,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum GateDecision {
    Allow,
    Warn(String),
    Refused(String),
}

/// The minimal launch gate; the caller raises attention on a refusal.
pub fn launch_gate(tracker: &ProviderStateTracker, budget: &OperatorBudget, provider: &str, origin: LaunchOrigin) -> GateDecision {
    let reason = if tracker.state(provider).status == ProviderStatus::Exhausted {
        format!("provider {provider} limit is exhausted")
    } else if budget.is_spent(provider) {
        format!("operator budget for provider {provider} is spent")
    } else {
        return GateDecision::Allow;
    };
    match origin {
        LaunchOrigin::Human => GateDecision::Warn(reason),
        LaunchOrigin::Unattended => GateDecision::Refused(reason),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicI64, Ordering};

    #[test]
    fn quota_hit_holds_then_expires_and_gates_unattended_starts() {
        let now = Arc::new(AtomicI64::new(1_000));
        let n = now.clone();
        let t = ProviderStateTracker::new(Arc::new(move || n.load(Ordering::SeqCst)), Arc::new(|_| {}), 60_000);
        assert!(t.observe_stream_line("opencode", "Error: Free usage exceeded").is_some());
        assert_eq!(t.state("opencode").status, ProviderStatus::Exhausted);
        let b = OperatorBudget::new(None, None, Arc::new(|| 0));
        assert!(matches!(launch_gate(&t, &b, "opencode", LaunchOrigin::Unattended), GateDecision::Refused(_)));
        now.store(61_000, Ordering::SeqCst);
        assert_eq!(t.state("opencode").status, ProviderStatus::Unknown);
        let s = t.observe_stream_line("claude", r#"{"type":"rate_limit_event","rate_limit_info":{"rateLimitType":"five_hour","utilization":0.4}}"#).unwrap();
        assert_eq!((s.window.as_deref(), s.utilization), (Some("five_hour"), Some(0.4)));
    }

    #[test]
    fn budget_counts_runs_and_tokens() {
        let b = OperatorBudget::new(Some(2), None, Arc::new(|| 0));
        b.record_run("claude");
        assert!(!b.is_spent("claude"));
        b.record(&RunUsage { run_id: "s:1".into(), provider: "claude".into(), model: "m".into(), input: 5, output: 5, reasoning: 0, cache_read: 0, cache_write: 0, cost_usd: None });
        assert_eq!(b.snapshot("claude"), BudgetSnapshot { runs: 2, tokens: 10 });
        assert!(b.is_spent("claude"));
    }
}
