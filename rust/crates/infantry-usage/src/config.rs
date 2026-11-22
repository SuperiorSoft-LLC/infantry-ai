// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The owner's usage settings: the pacing rule (`usage.pacing`), typed percents (`usage.manual`) and allowance
//! overrides (`usage.overrides`), plus the numeric settings this crate reads (`usage.refreshMinutes`,
//! `limits.usage.*`, `limits.pacing.maxWorkersCeiling`, `pace.*`).
//!
//! Storage is behind [`UsageStore`]: the daemon wires its settings writer; [`FileStore`] keeps a JSON file for a
//! standalone run and tests. A stored value that no longer parses reads as the default (never a crash).
use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use infantry_protocol::usage::{ManualUsage, PacingRule, UsageOverride, UsageSet};
use serde_json::Value;

use crate::limits::ReadTuning;
use crate::pacing::{self, manual_expires_at};

pub const RULE_SETTING: &str = "usage.pacing";
pub const MANUAL_SETTING: &str = "usage.manual";
pub const OVERRIDES_SETTING: &str = "usage.overrides";
pub const REFRESH_MINUTES: &str = "usage.refreshMinutes";
pub const OVERRIDE_ROLES: &str = "usage.overrideRoles";
pub const CEILING: &str = "limits.pacing.maxWorkersCeiling";
pub const STALE_MAX_HOURS: &str = "limits.usage.staleMaxHours";
pub const BACKOFF_MAX_MINUTES: &str = "limits.usage.backoffMaxMinutes";
pub const MANUAL_FLOOR_SECONDS: &str = "limits.usage.manualRefreshFloorSeconds";
/// Workers a meter may run while its reading is missing (0 = no limit, the old fail-open).
pub const UNKNOWN_WORKERS: &str = "limits.pacing.unknownWorkers";
pub const QUOTA_HIT_HOLD_MINUTES: &str = "limits.usage.quotaHitHoldMinutes";

/// Read-write key/value settings (JSON values).
pub trait UsageStore: Send + Sync {
    fn get(&self, key: &str) -> Option<Value>;
    fn set(&self, key: &str, value: Value) -> Result<(), String>;
}

/// In memory only.
#[derive(Default)]
pub struct MemoryStore(Mutex<BTreeMap<String, Value>>);

impl UsageStore for MemoryStore {
    fn get(&self, key: &str) -> Option<Value> {
        self.0.lock().unwrap_or_else(|p| p.into_inner()).get(key).cloned()
    }
    fn set(&self, key: &str, value: Value) -> Result<(), String> {
        self.0.lock().unwrap_or_else(|p| p.into_inner()).insert(key.into(), value);
        Ok(())
    }
}

/// One JSON object in a file, rewritten atomically (temp file + rename) on every set.
pub struct FileStore {
    path: PathBuf,
    values: Mutex<BTreeMap<String, Value>>,
}

impl FileStore {
    pub fn open(path: impl Into<PathBuf>) -> Self {
        let path = path.into();
        let values = std::fs::read_to_string(&path).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or_default();
        FileStore { path, values: Mutex::new(values) }
    }
}

impl UsageStore for FileStore {
    fn get(&self, key: &str) -> Option<Value> {
        self.values.lock().unwrap_or_else(|p| p.into_inner()).get(key).cloned()
    }
    fn set(&self, key: &str, value: Value) -> Result<(), String> {
        let mut v = self.values.lock().unwrap_or_else(|p| p.into_inner());
        v.insert(key.into(), value);
        if let Some(dir) = self.path.parent() {
            std::fs::create_dir_all(dir).map_err(|e| format!("cannot create {}: {e}", dir.display()))?;
        }
        let tmp = self.path.with_extension("json.tmp");
        let text = serde_json::to_string_pretty(&*v).map_err(|e| e.to_string())?;
        std::fs::write(&tmp, text).map_err(|e| format!("cannot write {}: {e}", tmp.display()))?;
        std::fs::rename(&tmp, &self.path).map_err(|e| format!("cannot replace {}: {e}", self.path.display()))
    }
}

/// Typed access over a [`UsageStore`].
#[derive(Clone)]
pub struct UsageConfig {
    store: Arc<dyn UsageStore>,
}

impl UsageConfig {
    pub fn new(store: Arc<dyn UsageStore>) -> Self {
        UsageConfig { store }
    }

    pub fn store(&self) -> &Arc<dyn UsageStore> {
        &self.store
    }

    fn num(&self, key: &str) -> Option<f64> {
        match self.store.get(key)? {
            Value::Number(n) => n.as_f64(),
            Value::String(s) => s.trim().parse().ok(),
            _ => None,
        }
    }

    /// A string setting (a number or bool reads as its text).
    pub fn text(&self, key: &str) -> Option<String> {
        match self.store.get(key)? {
            Value::String(s) => Some(s),
            Value::Null => None,
            v => Some(v.to_string()),
        }
    }

    pub fn ceiling(&self) -> i32 {
        self.num(CEILING).map(|n| n as i32).filter(|n| *n >= 1).unwrap_or(pacing::DEFAULT_CEILING)
    }

    pub fn refresh_minutes(&self) -> i64 {
        self.num(REFRESH_MINUTES).map(|n| n as i64).filter(|n| *n >= 0).unwrap_or(5)
    }

    pub fn unknown_allowance(&self) -> Option<i32> {
        match self.num(UNKNOWN_WORKERS).map(|n| n as i32) {
            Some(0) => None,
            Some(n) if (1..=self.ceiling()).contains(&n) => Some(n),
            _ => Some(1),
        }
    }

    pub fn quota_hit_hold_ms(&self) -> i64 {
        self.num(QUOTA_HIT_HOLD_MINUTES).map(|n| n as i64).filter(|n| (1..=10_080).contains(n)).unwrap_or(60) * 60_000
    }

    /// `usage.overrideRoles` (default `owner,pm`).
    pub fn override_roles(&self) -> Vec<String> {
        self.text(OVERRIDE_ROLES).unwrap_or_else(|| "owner,pm".into()).split(',').map(|r| r.trim().to_lowercase()).filter(|r| !r.is_empty()).collect()
    }

    pub fn tuning(&self) -> ReadTuning {
        let d = ReadTuning::default();
        ReadTuning {
            stale_max_ms: self.num(STALE_MAX_HOURS).map(|h| h as i64).filter(|h| (1..=168).contains(h)).map(|h| h * 3_600_000).unwrap_or(d.stale_max_ms),
            backoff_base_ms: self.refresh_minutes().max(1) * 60_000,
            backoff_max_ms: self.num(BACKOFF_MAX_MINUTES).map(|m| m as i64).filter(|m| (1..=1440).contains(m)).map(|m| m * 60_000).unwrap_or(d.backoff_max_ms),
            manual_floor_ms: self.num(MANUAL_FLOOR_SECONDS).map(|s| s as i64).filter(|s| (0..=3600).contains(s)).map(|s| s * 1000).unwrap_or(d.manual_floor_ms),
        }
    }

    pub fn rule(&self) -> PacingRule {
        self.store.get(RULE_SETTING).and_then(|v| pacing::parse_rule(&v, self.ceiling()).ok()).unwrap_or_default()
    }

    pub fn manual(&self) -> BTreeMap<String, ManualUsage> {
        self.store.get(MANUAL_SETTING).and_then(|v| pacing::parse_manual(&v).ok()).unwrap_or_default()
    }

    pub fn overrides(&self) -> BTreeMap<String, UsageOverride> {
        self.store.get(OVERRIDES_SETTING).and_then(|v| pacing::parse_overrides(&v, i32::MAX).ok()).unwrap_or_default()
    }

    /// The `usage_set` frame: a meter's typed percent and/or override set or cleared, and/or the rule replaced.
    /// Validated whole before anything is written; the error names the problem.
    pub fn apply(&self, request: &UsageSet, now: i64) -> Result<(), String> {
        let meter = request.meter.trim().to_lowercase();
        let touches_meter = request.clear_manual || request.manual_percent.is_some() || request.clear_override || request.override_until.is_some();
        if request.rule.is_none() && !touches_meter {
            return Err("usage_set changes nothing".into());
        }
        if touches_meter && meter.is_empty() {
            return Err("usage_set needs a meter".into());
        }
        if let Some(pct) = request.manual_percent {
            if !(0.0..=100.0).contains(&pct) {
                return Err("percent must be in 0..100".into());
            }
            if request.manual_resets_at.is_some_and(|r| r <= now) {
                return Err("resetsAt must be in the future".into());
            }
        }
        let ceiling = self.ceiling();
        if let Some(until) = request.override_until {
            if until <= now {
                return Err("override 'until' must be in the future".into());
            }
            if request.override_workers.is_some_and(|w| !(0..=ceiling).contains(&w)) {
                return Err(format!("workers must be in 0..{ceiling} or none (setting {CEILING})"));
            }
        }
        let rule = match &request.rule {
            Some(v) => Some(pacing::parse_rule(v, ceiling).map_err(|e| format!("setting '{RULE_SETTING}' {e}"))?),
            None => None,
        };
        if let Some(r) = rule {
            self.store.set(RULE_SETTING, serde_json::to_value(r).map_err(|e| e.to_string())?)?;
        }
        if request.clear_manual || request.manual_percent.is_some() {
            let mut next: BTreeMap<String, ManualUsage> = self.manual().into_iter().filter(|(_, m)| manual_expires_at(m) > now).collect();
            if request.clear_manual {
                next.remove(&meter);
            }
            if let Some(pct) = request.manual_percent {
                next.insert(meter.clone(), ManualUsage { percent: pct, resets_at: request.manual_resets_at, set_at: now });
            }
            self.store.set(MANUAL_SETTING, serde_json::to_value(next).map_err(|e| e.to_string())?)?;
        }
        if request.clear_override || request.override_until.is_some() {
            let mut next: BTreeMap<String, UsageOverride> = self.overrides().into_iter().filter(|(_, o)| o.until > now).collect();
            if request.clear_override {
                next.remove(&meter);
            }
            if let Some(until) = request.override_until {
                next.insert(meter, UsageOverride { workers: request.override_workers, until });
            }
            self.store.set(OVERRIDES_SETTING, serde_json::to_value(next).map_err(|e| e.to_string())?)?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn set(meter: &str) -> UsageSet {
        UsageSet { meter: meter.into(), manual_percent: None, manual_resets_at: None, clear_manual: false, override_workers: None, override_until: None, clear_override: false, rule: None }
    }

    #[test]
    fn apply_validates_then_writes_and_clears() {
        let c = UsageConfig::new(Arc::new(MemoryStore::default()));
        assert_eq!(c.apply(&set("claude"), 0).unwrap_err(), "usage_set changes nothing");
        assert!(c.apply(&UsageSet { override_until: Some(10), override_workers: Some(99), ..set("claude") }, 0).unwrap_err().contains("0..50"));
        c.apply(&UsageSet { override_until: Some(10_000), ..set("Claude") }, 0).unwrap();
        assert_eq!(c.overrides()["claude"], UsageOverride { workers: None, until: 10_000 });
        c.apply(&UsageSet { manual_percent: Some(40.0), ..set("opencode-go") }, 5).unwrap();
        assert_eq!(c.manual()["opencode-go"].set_at, 5);
        c.apply(&UsageSet { clear_override: true, ..set("claude") }, 1).unwrap();
        assert!(c.overrides().is_empty());
        c.apply(&UsageSet { rule: Some(serde_json::json!({"mode": "bands"})), ..set("") }, 1).unwrap();
        assert_eq!(c.rule().mode, "bands");
        assert!(c.apply(&UsageSet { rule: Some(serde_json::json!({"mode": "x"})), ..set("") }, 1).unwrap_err().starts_with("setting 'usage.pacing'"));
    }

    #[test]
    fn file_store_persists() {
        let p = std::env::temp_dir().join(format!("infantry-usage-cfg-{}.json", std::process::id()));
        let _ = std::fs::remove_file(&p);
        FileStore::open(&p).set(REFRESH_MINUTES, serde_json::json!(7)).unwrap();
        assert_eq!(UsageConfig::new(Arc::new(FileStore::open(&p))).refresh_minutes(), 7);
        let _ = std::fs::remove_file(&p);
    }
}
