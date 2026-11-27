// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The wire shapes (Kotlin `Triggers.kt`, contract 1.14.0+): a trigger as the Triggers tab shows it, a save request, one
//! event row. Every field of a view is written, nulls included (Kotlin encodes defaults).
use serde::{Deserialize, Serialize};

pub const MAX_PROMPT_CHARS: usize = 4000;
pub const DEFAULT_COALESCE_MS: i64 = 120_000;
pub const DEFAULT_CEILING_RUNS: i64 = 6;
pub const DEFAULT_CEILING_WINDOW_MS: i64 = 3_600_000;

fn start_next() -> String {
    "start_next".into()
}
fn manual() -> String {
    "manual".into()
}
fn yes() -> bool {
    true
}
fn coalesce() -> i64 {
    DEFAULT_COALESCE_MS
}
fn ceiling_runs() -> i64 {
    DEFAULT_CEILING_RUNS
}
fn ceiling_window() -> i64 {
    DEFAULT_CEILING_WINDOW_MS
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Action {
    #[serde(default = "start_next")]
    pub kind: String,
    #[serde(default)]
    pub agent_id: Option<String>,
    #[serde(default)]
    pub prompt: Option<String>,
}

impl Default for Action {
    fn default() -> Self {
        Action { kind: start_next(), agent_id: None, prompt: None }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TriggerView {
    pub id: String,
    pub name: String,
    pub kind: String,
    pub action: Action,
    pub enabled: bool,
    pub state: String,
    pub coalesce_ms: i64,
    pub ceiling_runs: i64,
    pub ceiling_window_ms: i64,
    pub created_at: i64,
    #[serde(default)]
    pub tripped_at: Option<i64>,
    #[serde(default)]
    pub tripped_reason: Option<String>,
    #[serde(default)]
    pub interval_ms: Option<i64>,
    #[serde(default)]
    pub project_id: Option<String>,
    #[serde(default)]
    pub last_fired_at: Option<i64>,
    #[serde(default)]
    pub next_fire_at: Option<i64>,
    #[serde(default)]
    pub fire_at: Option<i64>,
    #[serde(default)]
    pub delete_after_fire: bool,
    #[serde(default)]
    pub owner: Option<String>,
    #[serde(default, rename = "ref")]
    pub reference: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TriggerSave {
    #[serde(default)]
    pub id: Option<String>,
    pub name: String,
    #[serde(default = "manual")]
    pub kind: String,
    #[serde(default = "yes")]
    pub enabled: bool,
    #[serde(default = "coalesce")]
    pub coalesce_ms: i64,
    #[serde(default = "ceiling_runs")]
    pub ceiling_runs: i64,
    #[serde(default = "ceiling_window")]
    pub ceiling_window_ms: i64,
    #[serde(default)]
    pub action: Action,
    #[serde(default)]
    pub interval_ms: Option<i64>,
    #[serde(default)]
    pub webhook_secret: Option<String>,
    #[serde(default)]
    pub project_id: Option<String>,
    #[serde(default)]
    pub fire_at: Option<i64>,
    #[serde(default)]
    pub delete_after_fire: bool,
    #[serde(default)]
    pub owner: Option<String>,
    #[serde(default, rename = "ref")]
    pub reference: Option<String>,
}

impl TriggerSave {
    pub fn named(name: &str) -> Self {
        serde_json::from_value(serde_json::json!({ "name": name })).expect("defaults")
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EventView {
    pub event_id: String,
    pub trigger_id: String,
    pub received_at: i64,
    pub source: String,
    pub disposition: String,
    #[serde(default)]
    pub delivery_id: Option<String>,
    #[serde(default)]
    pub digest: Option<String>,
}

/// What happened to one event (`TriggerEventDisposition`, written lowercase).
pub mod disposition {
    pub const STARTED: &str = "started";
    pub const FOLDED: &str = "folded";
    pub const FOLLOWUP_FOLDED: &str = "followup_folded";
    pub const DROPPED_AFTER_FAILURE: &str = "dropped_after_failure";
    pub const REFUSED_CEILING: &str = "refused_ceiling";
    pub const REFUSED_LAUNCH_GATE: &str = "refused_launch_gate";
    pub const SUPPRESSED_TRIPPED: &str = "suppressed_tripped";
    pub const SUPPRESSED_DISABLED: &str = "suppressed_disabled";
    pub const NOTHING_TO_START: &str = "nothing_to_start";
    pub const DUPLICATE: &str = "duplicate";
    pub const MAILED: &str = "mailed";
    pub const DEFERRED_BUSY: &str = "deferred_busy";
    pub const COALESCED_PENDING: &str = "coalesced_pending";
    pub const TARGET_GONE: &str = "target_gone";
    pub const DELIVERED: &str = "delivered";
    pub const REFUSED: &str = "refused";
    pub const REFUSED_UNKNOWN_ACTION: &str = "refused_unknown_action";
    pub const MISSED: &str = "missed";
    /// TOKEN-13: a prompt trigger skipped because nothing needed its target.
    pub const SKIPPED_IDLE: &str = "skipped_idle";
}

/// Trigger states on the wire.
pub mod state {
    pub const ARMED: &str = "armed";
    pub const RUNNING: &str = "running";
    pub const TRIPPED: &str = "tripped";
    pub const DISABLED: &str = "disabled";
}
