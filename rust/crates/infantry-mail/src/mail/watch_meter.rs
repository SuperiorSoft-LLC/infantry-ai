// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! What the job watcher, its heartbeat and the stall nudges did, as COUNTS only (Kotlin STALL-1/2 `WatchMeter`). None of
//! them calls a model: they read files and pids and send mail; a token is spent only by the turn a delivered notice
//! starts. Served as `watch` on the `usage_meter` report (the Stats "Job watcher" line). Process-wide, since start.

use serde_json::{json, Value};
use std::sync::atomic::{AtomicU64, Ordering};

pub struct WatchMeter {
    events: AtomicU64,
    heartbeats: AtomicU64,
    opt_outs: AtomicU64,
    stalls: AtomicU64,
    hook_rewrites: AtomicU64,
    hook_overrides: AtomicU64,
}

/// The daemon's one meter.
pub static WATCH_METER: WatchMeter = WatchMeter::new();

pub const NOTE: &str = "watcher, heartbeat and stall detector use no model tokens";

impl WatchMeter {
    pub const fn new() -> Self {
        WatchMeter {
            events: AtomicU64::new(0),
            heartbeats: AtomicU64::new(0),
            opt_outs: AtomicU64::new(0),
            stalls: AtomicU64::new(0),
            hook_rewrites: AtomicU64::new(0),
            hook_overrides: AtomicU64::new(0),
        }
    }
    /// A watcher notice sent to a session.
    pub fn event(&self) {
        self.events.fetch_add(1, Ordering::Relaxed);
    }
    /// A heartbeat notice sent for a running job.
    pub fn heartbeat(&self) {
        self.heartbeats.fetch_add(1, Ordering::Relaxed);
    }
    /// A job that opted out of watching.
    pub fn opt_out(&self) {
        self.opt_outs.fetch_add(1, Ordering::Relaxed);
    }
    /// A stall nudge sent to a silent worker.
    pub fn stall(&self) {
        self.stalls.fetch_add(1, Ordering::Relaxed);
    }
    /// A harness hook routed a shell command through the watcher.
    pub fn hook_rewrite(&self) {
        self.hook_rewrites.fetch_add(1, Ordering::Relaxed);
    }
    /// Another hook's rewrite replaced ours: the command ran unwatched.
    pub fn hook_override(&self) {
        self.hook_overrides.fetch_add(1, Ordering::Relaxed);
    }
    /// The counts in the Kotlin `WatchMeter.Counts` wire shape.
    pub fn counts(&self) -> Value {
        let g = |a: &AtomicU64| a.load(Ordering::Relaxed);
        json!({
            "events": g(&self.events), "heartbeats": g(&self.heartbeats), "optOuts": g(&self.opt_outs),
            "stallNudges": g(&self.stalls), "hookRewrites": g(&self.hook_rewrites), "hookOverrides": g(&self.hook_overrides),
            "note": NOTE,
        })
    }
}

impl Default for WatchMeter {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn counts_carry_the_kotlin_field_names() {
        let m = WatchMeter::new();
        m.event();
        m.event();
        m.stall();
        let c = m.counts();
        assert_eq!((c["events"].as_u64(), c["stallNudges"].as_u64(), c["heartbeats"].as_u64()), (Some(2), Some(1), Some(0)));
        assert_eq!(c["note"], NOTE);
    }
}
