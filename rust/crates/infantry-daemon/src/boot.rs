// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! BOOT-SPLASH-1: the boot progress seam. The daemon owns one generic state, the ordered boot steps
//! (`core` -> `installer` -> `ui` -> `plugins` N of M) and the label of the current one, and answers it on the
//! `boot_progress` frame. Every change is also pushed to the clients as a `boot_progress` frame. Nothing here draws:
//! the host layer draws the splash from this state, and a plugin only reports its own load progress.
//!
//! Steps that have no owner yet (the installer and the UI plugin of the boot chain) finish at once; the chain fills in
//! as INSTALLER-1 and BOOT-CHAIN-1 land, by calling `finish` / `plugins` from their own code.

use infantry_core::bus::EventBus;
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};

pub const BOOT_PROGRESS: &str = "boot_progress";

/// The steps in order: id and the label the splash shows while that step runs.
pub const STEPS: [(&str, &str); 4] = [("core", "Starting the core"), ("installer", "Checking the install"), ("ui", "Loading the interface"), ("plugins", "Loading plugins")];

#[derive(Clone, Debug, PartialEq)]
struct State {
    /// How many of `STEPS` are finished (the next one is current).
    done: usize,
    /// Plugins loaded and plugins expected, while the `plugins` step runs.
    plugins: (usize, usize),
}

pub struct BootState {
    state: Mutex<State>,
    bus: Option<EventBus>,
}

impl BootState {
    pub fn new(bus: Option<EventBus>) -> Arc<Self> {
        Arc::new(Self { state: Mutex::new(State { done: 0, plugins: (0, 0) }), bus })
    }

    /// The report: `{ready, current, label, steps:[{id,label,done,n?,m?}]}`.
    pub fn report(&self) -> Value {
        let s = self.state.lock().unwrap_or_else(|e| e.into_inner()).clone();
        let ready = s.done >= STEPS.len();
        let steps: Vec<Value> = STEPS
            .iter()
            .enumerate()
            .map(|(i, (id, label))| {
                let mut v = json!({ "id": id, "label": label, "done": i < s.done });
                if *id == "plugins" && s.plugins.1 > 0 {
                    v["n"] = json!(if i < s.done { s.plugins.1 } else { s.plugins.0 });
                    v["m"] = json!(s.plugins.1);
                }
                v
            })
            .collect();
        let cur = STEPS.get(s.done);
        let mut label = cur.map(|c| c.1.to_string()).unwrap_or_else(|| "Ready".into());
        if let (Some(("plugins", _)), true) = (cur, s.plugins.1 > 0) {
            label = format!("{label} ({} of {})", s.plugins.0, s.plugins.1);
        }
        json!({ "ready": ready, "current": cur.map(|c| c.0).unwrap_or("ready"), "label": label, "steps": steps })
    }

    fn push(&self) {
        if let Some(bus) = &self.bus {
            bus.publish(Envelope::new(BOOT_PROGRESS, self.report().to_string()));
        }
    }

    /// Marks the step `id` (and every step before it) finished.
    pub fn finish(&self, id: &str) {
        if let Some(i) = STEPS.iter().position(|s| s.0 == id) {
            {
                let mut s = self.state.lock().unwrap_or_else(|e| e.into_inner());
                s.done = s.done.max(i + 1);
            }
            self.push();
        }
    }

    /// Records plugin load progress: `n` loaded of `m` expected. The step finishes when `n >= m`.
    pub fn plugins(&self, n: usize, m: usize) {
        {
            let mut s = self.state.lock().unwrap_or_else(|e| e.into_inner());
            s.plugins = (n.min(m), m);
        }
        if n >= m {
            self.finish("plugins");
        } else {
            self.push();
        }
    }
}

/// `boot_progress` `{}` -> a `boot_progress` frame with the report.
pub struct BootUnit {
    pub boot: Arc<BootState>,
}

impl FrameUnit for BootUnit {
    fn name(&self) -> &'static str {
        "boot"
    }
    fn frames(&self) -> &'static [&'static str] {
        &[BOOT_PROGRESS]
    }
    fn handle(&self, env: &Envelope, _ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let mut e = Envelope::new(BOOT_PROGRESS, self.boot.report().to_string());
        e.request_id = env.request_id.clone();
        e.session_id = env.session_id.clone();
        Ok(vec![e])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn steps_run_in_order_and_plugins_count_n_of_m() {
        let b = BootState::new(None);
        assert_eq!(b.report()["current"], "core");
        b.finish("core");
        b.finish("installer");
        b.finish("ui");
        b.plugins(1, 3);
        let r = b.report();
        assert_eq!(r["ready"], false);
        assert_eq!(r["label"], "Loading plugins (1 of 3)");
        b.plugins(3, 3);
        let r = b.report();
        assert_eq!(r["ready"], true);
        assert_eq!(r["steps"][3]["n"], 3);
    }

    #[test]
    fn finishing_a_late_step_finishes_the_earlier_ones() {
        let b = BootState::new(None);
        b.finish("ui");
        assert_eq!(b.report()["current"], "plugins");
    }
}
