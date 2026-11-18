// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Usage, pace and limits (`Usage.kt`, `Limits.kt`, `daemon/api/usage/UsageModel.kt`).
use crate::dto;
use serde::{Deserialize, Serialize};
use ts_rs::TS;

dto! {
    /// Tokens a finished run reported.
    pub struct RunUsage {
        pub run_id: String,
        pub provider: String,
        pub model: String,
        pub input: i64,
        pub output: i64,
        pub reasoning: i64,
        pub cache_read: i64,
        pub cache_write: i64,
        #[serde(default)] pub cost_usd: Option<f64>,
    }
}

fn usage_type() -> String {
    "usage".into()
}
dto! {
    /// Sequenced `usage` frame body. `type` is `@EncodeDefault` (always written).
    pub struct UsageFrame {
        pub usage: RunUsage,
        #[serde(rename = "type", default = "usage_type")]
        #[ts(rename = "type")]
        pub r#type: String,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "lowercase")]
#[ts(export)]
pub enum ProviderStatus {
    Unknown,
    Stalled,
    Exhausted,
}
impl ProviderStatus {
    pub fn wire_name(self) -> &'static str {
        match self {
            ProviderStatus::Unknown => "unknown",
            ProviderStatus::Stalled => "stalled",
            ProviderStatus::Exhausted => "exhausted",
        }
    }
    /// Unknown names read as `Unknown`, like `fromWireName`.
    pub fn from_wire_name(s: &str) -> Self {
        match s {
            "stalled" => ProviderStatus::Stalled,
            "exhausted" => ProviderStatus::Exhausted,
            _ => ProviderStatus::Unknown,
        }
    }
}

fn none_source() -> String {
    "none".into()
}
dto! {
    pub struct ProviderState {
        pub provider: String,
        pub status: ProviderStatus,
        #[serde(default)] pub window: Option<String>,
        #[serde(default)] pub utilization: Option<f64>,
        #[serde(default)] pub resets_at: Option<i64>,
        #[serde(default)] pub observed_at: Option<i64>,
        #[serde(default = "none_source")] pub source: String,
    }
}
fn provider_state_type() -> String {
    "provider_state".into()
}
dto! {
    pub struct ProviderStateFrame {
        pub state: ProviderState,
        #[serde(rename = "type", default = "provider_state_type")]
        #[ts(rename = "type")]
        pub r#type: String,
    }
}

dto! {
    /// One usage window of a source. No Kotlin defaults: every field is written (nulls included).
    pub struct LimitWindow {
        pub provider: String,
        pub label: String,
        pub window_minutes: i32,
        pub percent: Option<f64>,
        pub resets_at: Option<i64>,
        pub observed_at: i64,
        pub source: String,
        pub pace_delta: Option<f64>,
    }
}
dto! {
    /// Answer element of the `limits` frame (token-free).
    pub struct ProviderLimits {
        pub provider: String,
        pub status: String,
        pub windows: Vec<LimitWindow>,
        pub reason: Option<String>,
        pub observed_at: i64,
        #[serde(default)] pub stale: bool,
        #[serde(default)] pub retry_at: Option<i64>,
    }
}
impl ProviderLimits {
    pub fn unknown(provider: &str, now: i64, reason: &str) -> Self {
        ProviderLimits {
            provider: provider.into(), status: "unknown".into(), windows: vec![], reason: Some(reason.into()),
            observed_at: now, stale: false, retry_at: None,
        }
    }
}

dto! { pub struct PacingTier { #[serde(default)] pub below: Option<f64>, pub workers: i32 } }

fn default_tiers() -> Vec<PacingTier> {
    vec![
        PacingTier { below: Some(60.0), workers: 3 },
        PacingTier { below: Some(80.0), workers: 2 },
        PacingTier { below: None, workers: 1 },
    ]
}
fn mode_pace() -> String {
    "pace".into()
}
fn t() -> bool {
    true
}
fn max_workers() -> i32 {
    5
}
fn ok_projected() -> f64 {
    100.0
}
fn hard_stop() -> f64 {
    95.0
}
dto! {
    pub struct PacingRule {
        #[serde(default = "t")] pub enabled: bool,
        #[serde(default = "default_tiers")] pub tiers: Vec<PacingTier>,
        #[serde(default = "mode_pace")] pub mode: String,
        #[serde(default = "max_workers")] pub max_workers: i32,
        #[serde(default = "ok_projected")] pub ok_projected: f64,
        #[serde(default = "hard_stop")] pub hard_stop_percent: f64,
    }
}
impl Default for PacingRule {
    fn default() -> Self {
        PacingRule {
            enabled: true, tiers: default_tiers(), mode: "pace".into(), max_workers: 5, ok_projected: 100.0, hard_stop_percent: 95.0,
        }
    }
}

dto! {
    pub struct ManualUsage {
        pub percent: f64,
        #[serde(default)] pub resets_at: Option<i64>,
        pub set_at: i64,
    }
}
dto! { pub struct UsageOverride { #[serde(default)] pub workers: Option<i32>, pub until: i64 } }

dto! {
    pub struct MeterUsage {
        pub meter: String,
        pub percent: Option<f64>,
        #[serde(default)] pub window: Option<String>,
        #[serde(default)] pub resets_at: Option<i64>,
        #[serde(default)] pub pace_delta: Option<f64>,
        pub source: String,
        #[serde(default)] pub windows: Vec<LimitWindow>,
        #[serde(default)] pub reason: Option<String>,
        #[serde(default)] pub rule_allowance: Option<i32>,
        #[serde(default, rename = "override")]
        #[ts(rename = "override")]
        pub r#override: Option<UsageOverride>,
        #[serde(default)] pub allowance: Option<i32>,
        pub running: i32,
        pub can_start: bool,
        #[serde(default)] pub warning: Option<String>,
        #[serde(default)] pub updated_at: Option<i64>,
        #[serde(default)] pub projected_percent: Option<f64>,
        #[serde(default)] pub reasoning: Option<String>,
    }
}
dto! { pub struct UsageReport { pub meters: Vec<MeterUsage>, pub rule: PacingRule, pub observed_at: i64 } }

dto! {
    /// `usage_set` request body (`UsageSet`).
    pub struct UsageSet {
        #[serde(default)] pub meter: String,
        #[serde(default)] pub manual_percent: Option<f64>,
        #[serde(default)] pub manual_resets_at: Option<i64>,
        #[serde(default)] pub clear_manual: bool,
        #[serde(default)] pub override_workers: Option<i32>,
        #[serde(default)] pub override_until: Option<i64>,
        #[serde(default)] pub clear_override: bool,
        #[serde(default)]
        #[ts(type = "unknown | null")]
        pub rule: Option<serde_json::Value>,
    }
}
