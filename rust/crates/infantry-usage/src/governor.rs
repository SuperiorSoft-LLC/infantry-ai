// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The per-account "workers allowed" answer: one flat line per meter that a script (the usage governor) or a card
//! reads without re-deriving the pacing rule. Served as the `usage_workers` frame (`usage_workers_get`), and folded
//! into the `infantry_usage` JSON the governor Lua script already reads.
use infantry_protocol::usage::{MeterUsage, UsageReport};
use serde::{Deserialize, Serialize};

pub const USAGE_WORKERS_GET: &str = "usage_workers_get";
pub const USAGE_WORKERS: &str = "usage_workers";

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkersAllowed {
    pub meter: String,
    /// Workers allowed now (`None` = no limit: no reading, rule off, or a "no limit" override).
    pub allowance: Option<i32>,
    /// What the pacing rule alone says (an override can differ).
    pub rule_allowance: Option<i32>,
    pub running: i32,
    pub can_start: bool,
    /// Free slots left (`None` = unlimited).
    pub free_slots: Option<i32>,
    /// Whether an override sets the allowance, and until when.
    pub override_until: Option<i64>,
    /// The governing window's percent and its points ahead (+) or under (-) an even pace.
    pub percent: Option<f64>,
    pub pace_delta: Option<f64>,
    /// The weekly window's pace delta (what the governor steps on), when the meter has a weekly window.
    pub weekly_pace_delta: Option<f64>,
    pub projected_percent: Option<f64>,
    pub resets_at: Option<i64>,
    pub source: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkersReport {
    pub meters: Vec<WorkersAllowed>,
    pub observed_at: i64,
}

fn is_weekly(label: &str, minutes: i32) -> bool {
    let l = label.to_lowercase();
    minutes == 10_080 || l.contains("week")
}

pub fn workers_allowed(m: &MeterUsage) -> WorkersAllowed {
    WorkersAllowed {
        meter: m.meter.clone(),
        allowance: m.allowance,
        rule_allowance: m.rule_allowance,
        running: m.running,
        can_start: m.can_start,
        free_slots: m.allowance.map(|a| (a - m.running).max(0)),
        override_until: m.r#override.as_ref().map(|o| o.until),
        percent: m.percent,
        pace_delta: m.pace_delta,
        weekly_pace_delta: m.windows.iter().find(|w| is_weekly(&w.label, w.window_minutes)).and_then(|w| w.pace_delta),
        projected_percent: m.projected_percent,
        resets_at: m.resets_at,
        source: m.source.clone(),
    }
}

pub fn workers_report(r: &UsageReport) -> WorkersReport {
    WorkersReport { meters: r.meters.iter().map(workers_allowed).collect(), observed_at: r.observed_at }
}

#[cfg(test)]
mod tests {
    use super::*;
    use infantry_protocol::usage::{LimitWindow, PacingRule, UsageOverride};

    #[test]
    fn free_slots_and_the_weekly_delta() {
        let w = LimitWindow { provider: "claude".into(), label: "Current week".into(), window_minutes: 10_080, percent: Some(40.0), resets_at: None, observed_at: 0, source: "live".into(), pace_delta: Some(-12.0) };
        let m = MeterUsage {
            meter: "claude".into(), percent: Some(40.0), window: Some("Current week".into()), resets_at: None, pace_delta: Some(-12.0), source: "live".into(),
            windows: vec![w], reason: None, rule_allowance: Some(3), r#override: Some(UsageOverride { workers: Some(4), until: 9 }), allowance: Some(4),
            running: 1, can_start: true, warning: None, updated_at: None, projected_percent: None, reasoning: None,
        };
        let r = workers_report(&UsageReport { meters: vec![m], rule: PacingRule::default(), observed_at: 1 });
        let a = &r.meters[0];
        assert_eq!((a.free_slots, a.weekly_pace_delta, a.override_until), (Some(3), Some(-12.0), Some(9)));
        assert!(serde_json::to_string(&r).unwrap().contains("\"freeSlots\":3"));
    }
}
