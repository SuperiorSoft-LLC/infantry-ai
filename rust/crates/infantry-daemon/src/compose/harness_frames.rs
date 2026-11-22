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
    /// Judges each harness's sign-in with the subscription tokens the vault holds.
    creds: Arc<crate::harness::credentials::HarnessCredentials>,
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

/// One harness's `providers` row and its `models` groups. `usageNote` is set only for a harness that says why the Usage
/// tab has no meter card for it ([`infantry_harness::Harness::usage_note`]).
fn rows_of(id: &str, default: bool, h: &dyn infantry_harness::Harness, probe: &dyn infantry_harness::Probe) -> (Value, Vec<Value>) {
    let problem = h.sign_in_problem(probe);
    let ids: Vec<String> = h.models(probe).into_iter().map(|m| m.id).collect();
    let mut row = json!({
        "id": id, "default": default, "models": ids, "available": problem.is_none(), "unavailableReason": problem,
        "efforts": EFFORTS, "effort": "supported",
    });
    if let Some(at) = h.login_location(probe) {
        row["loginAt"] = Value::String(at);
    }
    if let Some(note) = h.usage_note() {
        row["usageNote"] = Value::String(note);
    }
    let groups = groups_of(id, &ids)
        .into_iter()
        .map(|(g, ms)| json!({ "provider": id, "group": g, "label": g, "models": ms, "unavailable": problem.is_some() }))
        .collect();
    (row, groups)
}

fn build(daemon: &Daemon, creds: &crate::harness::credentials::HarnessCredentials) -> Listing {
    let probe = creds.probe();
    let mut providers = Vec::new();
    let mut models = Vec::new();
    for (i, p) in daemon.providers().iter().enumerate() {
        let Some(h) = daemon.registry().neutral(p) else {
            providers.push(json!({ "id": p, "default": i == 0, "available": true }));
            continue;
        };
        let (row, groups) = rows_of(p, i == 0, h.as_ref(), &probe);
        providers.push(row);
        models.extend(groups);
    }
    Listing { at: Instant::now(), providers: Value::Array(providers), models: Value::Array(models) }
}

impl HarnessFrames {
    pub fn new(daemon: Arc<Daemon>, creds: Arc<crate::harness::credentials::HarnessCredentials>) -> Arc<HarnessFrames> {
        let u = Arc::new(HarnessFrames { daemon, creds, cache: Arc::new(Mutex::new(None)), refreshing: Arc::new(Mutex::new(false)) });
        u.refresh_in_background();
        u
    }

    /// Builds the listing now and stores it (the install changed what is on this machine; the next answer must show it).
    pub fn rebuild_now(&self) {
        let l = build(&self.daemon, &self.creds);
        *self.cache.lock().unwrap_or_else(|e| e.into_inner()) = Some(l);
    }

    fn refresh_in_background(&self) {
        {
            let mut r = self.refreshing.lock().unwrap_or_else(|e| e.into_inner());
            if *r {
                return;
            }
            *r = true;
        }
        let (daemon, creds, cache, flag) = (self.daemon.clone(), self.creds.clone(), self.cache.clone(), self.refreshing.clone());
        std::thread::spawn(move || {
            let l = build(&daemon, &creds);
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
                let l = build(&self.daemon, &self.creds);
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
        // `providers {"refresh":true}`: the owner just signed in or installed something; rebuild now instead of serving the cache.
        if env.data.contains("refresh") {
            self.rebuild_now();
        }
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
    use infantry_harness::{Capabilities, LaunchError, LaunchPlan, LaunchRequest, ModelInfo, Probe, TurnDelivery};

    /// A harness with one model, an optional sign-in problem and an optional usage note.
    struct Stub {
        note: Option<&'static str>,
        problem: Option<&'static str>,
    }

    impl infantry_harness::Harness for Stub {
        fn id(&self) -> &str {
            "stub"
        }
        fn capabilities(&self) -> Capabilities {
            Capabilities {
                delivery: TurnDelivery::SpawnPerTurn,
                pty_screen: false,
                file_tail: false,
                hook_channel: false,
                http: false,
                resume: false,
                usage: false,
                model_selection: true,
                approval_modes: Vec::new(),
            }
        }
        fn models(&self, _: &dyn Probe) -> Vec<ModelInfo> {
            vec![ModelInfo { id: "lab/m1".into(), label: "m1".into() }]
        }
        fn sign_in_problem(&self, _: &dyn Probe) -> Option<String> {
            self.problem.map(String::from)
        }
        fn launch(&self, _: &LaunchRequest) -> Result<LaunchPlan, LaunchError> {
            Err(LaunchError("stub".into()))
        }
        fn usage_note(&self) -> Option<String> {
            self.note.map(String::from)
        }
    }

    #[test]
    fn a_row_carries_the_usage_note_only_when_the_harness_has_one() {
        let (with, _) = rows_of("stub", false, &Stub { note: Some("no plan limit is read"), problem: None }, &infantry_harness::LocalProbe);
        assert_eq!(with["usageNote"], "no plan limit is read", "{with}");
        let (without, _) = rows_of("stub", false, &Stub { note: None, problem: None }, &infantry_harness::LocalProbe);
        assert!(without.get("usageNote").is_none(), "a harness with a meter has no note: {without}");
    }

    #[test]
    fn an_unavailable_row_says_why_and_marks_its_model_groups() {
        let (row, groups) = rows_of("stub", true, &Stub { note: None, problem: Some("stub is not installed (not on PATH)") }, &infantry_harness::LocalProbe);
        assert_eq!(row["available"], false);
        assert_eq!(row["unavailableReason"], "stub is not installed (not on PATH)");
        assert_eq!(row["default"], true);
        assert_eq!((groups.len(), groups[0]["group"].as_str(), groups[0]["unavailable"].as_bool()), (1, Some("lab"), Some(true)));
        let (ok, _) = rows_of("stub", false, &Stub { note: None, problem: None }, &infantry_harness::LocalProbe);
        assert_eq!((ok["available"].as_bool(), ok["unavailableReason"].is_null()), (Some(true), true));
    }

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
