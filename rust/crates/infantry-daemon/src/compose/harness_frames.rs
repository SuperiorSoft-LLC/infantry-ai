// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The `providers` and `models` frames (RUST PARITY of Kotlin `HarnessModelCatalogs` + `ModelCatalogCache`): the
//! harness list the New agent and Switch dialogs fill their dropdowns from.
//!
//! - `providers` -> `providers`: one row per harness this daemon runs, the default first: availability, the models the
//!   harness lists and the effort levels it takes.
//! - `models` -> `models`: the same models as groups (harness -> provider -> model). A harness whose model ids read
//!   `provider/model` (opencode, pi) is split by that prefix; any other has one group named after it.
//!
//! Listing models can cost a child process, so the answer is cached: a request is served from the last good list and,
//! once it is older than [`REFRESH_AFTER`], a background thread refreshes it for the next one. The first list is built
//! at start, never on a client's open.
use crate::sessions::Daemon;
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_protocol::frames as pf;
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

/// How long a list is served before a refresh starts behind the answer.
const REFRESH_AFTER: Duration = Duration::from_secs(300);
const FRAMES: &[&str] = &[pf::PROVIDERS, pf::MODELS];
/// The reasoning efforts a harness takes (the swap check's list).
const EFFORTS: [&str; 5] = ["low", "medium", "high", "xhigh", "max"];

struct Listing {
    at: Instant,
    providers: Value,
    models: Value,
}

pub struct HarnessFrames {
    daemon: Arc<Daemon>,
    cache: Arc<Mutex<Option<Listing>>>,
    refreshing: Arc<Mutex<bool>>,
}

/// `(group, models)` pairs for one harness's model ids.
fn groups_of(harness: &str, ids: &[String]) -> Vec<(String, Vec<String>)> {
    if !ids.is_empty() && ids.iter().all(|m| m.contains('/')) {
        let mut out: Vec<(String, Vec<String>)> = Vec::new();
        for m in ids {
            let g = m.split('/').next().unwrap_or(harness).to_string();
            match out.iter_mut().find(|(n, _)| *n == g) {
                Some((_, v)) => v.push(m.clone()),
                None => out.push((g, vec![m.clone()])),
            }
        }
        return out;
    }
    vec![(harness.to_string(), ids.to_vec())]
}

fn build(daemon: &Daemon) -> Listing {
    let probe = infantry_harness::LocalProbe;
    let mut providers = Vec::new();
    let mut models = Vec::new();
    for (i, p) in daemon.providers().iter().enumerate() {
        let Some(h) = crate::harness::neutral(p) else {
            providers.push(json!({ "id": p, "default": i == 0, "available": true }));
            continue;
        };
        let problem = h.sign_in_problem(&probe);
        let ids: Vec<String> = h.models(&probe).into_iter().map(|m| m.id).collect();
        providers.push(json!({
            "id": p, "default": i == 0, "models": ids, "available": problem.is_none(), "unavailableReason": problem,
            "efforts": EFFORTS, "effort": "supported",
        }));
        for (g, ms) in groups_of(p, &ids) {
            models.push(json!({ "provider": p, "group": g, "label": g, "models": ms, "unavailable": problem.is_some() }));
        }
    }
    Listing { at: Instant::now(), providers: Value::Array(providers), models: Value::Array(models) }
}

impl HarnessFrames {
    pub fn new(daemon: Arc<Daemon>) -> Arc<HarnessFrames> {
        let u = Arc::new(HarnessFrames { daemon, cache: Arc::new(Mutex::new(None)), refreshing: Arc::new(Mutex::new(false)) });
        u.refresh_in_background();
        u
    }

    fn refresh_in_background(&self) {
        {
            let mut r = self.refreshing.lock().unwrap_or_else(|e| e.into_inner());
            if *r {
                return;
            }
            *r = true;
        }
        let (daemon, cache, flag) = (self.daemon.clone(), self.cache.clone(), self.refreshing.clone());
        std::thread::spawn(move || {
            let l = build(&daemon);
            *cache.lock().unwrap_or_else(|e| e.into_inner()) = Some(l);
            *flag.lock().unwrap_or_else(|e| e.into_inner()) = false;
        });
    }

    /// The last good listing; a stale one starts a refresh behind the answer. Only a cold cache (start-up not done) builds inline.
    fn listing(&self) -> (Value, Value) {
        let hit = {
            let c = self.cache.lock().unwrap_or_else(|e| e.into_inner());
            c.as_ref().map(|l| (l.providers.clone(), l.models.clone(), l.at.elapsed() > REFRESH_AFTER))
        };
        match hit {
            Some((p, m, stale)) => {
                if stale {
                    self.refresh_in_background();
                }
                (p, m)
            }
            None => {
                let l = build(&self.daemon);
                let out = (l.providers.clone(), l.models.clone());
                *self.cache.lock().unwrap_or_else(|e| e.into_inner()) = Some(l);
                out
            }
        }
    }
}

impl FrameUnit for HarnessFrames {
    fn name(&self) -> &'static str {
        "harness-frames"
    }
    fn frames(&self) -> &'static [&'static str] {
        FRAMES
    }
    fn handle(&self, env: &Envelope, _ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let (providers, models) = self.listing();
        let (ty, data) = match env.r#type.as_str() {
            pf::PROVIDERS => (pf::PROVIDERS, providers),
            pf::MODELS => (pf::MODELS, models),
            other => return Err(Refusal::new(other, "not a harness frame")),
        };
        let mut e = Envelope::new(ty, data.to_string());
        e.session_id = env.session_id.clone();
        e.request_id = env.request_id.clone();
        Ok(vec![e])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn prefixed_ids_split_by_provider_and_plain_ids_stay_one_group() {
        let ids = vec!["anthropic/a".to_string(), "openai/b".to_string(), "anthropic/c".to_string()];
        let g = groups_of("opencode", &ids);
        assert_eq!(g.len(), 2);
        assert_eq!(g[0], ("anthropic".to_string(), vec!["anthropic/a".to_string(), "anthropic/c".to_string()]));
        let plain = groups_of("claude", &["opus".to_string()]);
        assert_eq!(plain, vec![("claude".to_string(), vec!["opus".to_string()])]);
        assert_eq!(groups_of("pi", &[]).len(), 1);
    }
}
