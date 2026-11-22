// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The owner-only `runner_admin` frame: `{op: add|list|check|detach, ...}` -> one `runner_admin` answer `{ok, ...}`.
use crate::pool::{RunnerPool, RunnerState};
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_runner_link::pairing::BearerToken;
use serde_json::{json, Map, Value};
use std::sync::Arc;

pub const ADMIN_FRAME: &str = "runner_admin";
const FRAMES: &[&str] = &[ADMIN_FRAME];

pub struct AdminUnit {
    pub pool: Arc<RunnerPool>,
}

fn fail(msg: impl std::fmt::Display) -> Value {
    json!({"ok": false, "error": msg.to_string()})
}

fn row(id: &str, ok: bool, detail: impl Into<String>, fix: Option<&str>) -> Value {
    let mut r = json!({"id": id, "ok": ok, "detail": detail.into()});
    if let Some(f) = fix {
        r["fix"] = json!(f);
    }
    r
}

fn text<'a>(d: &'a Map<String, Value>, k: &str) -> Option<&'a str> {
    d.get(k).and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty())
}

impl AdminUnit {
    fn add(&self, d: &Map<String, Value>) -> Value {
        let Some(endpoint) = text(d, "endpoint") else { return fail("add needs an endpoint (wss://host:port)") };
        if !endpoint.starts_with("ws://") && !endpoint.starts_with("wss://") {
            return fail("the endpoint must start with wss:// (or ws:// with runners.allowPlainTailnet on)");
        }
        let Some(bearer) = text(d, "bearer") else { return fail("add needs the runner's bearer") };
        let token = match BearerToken::parse(bearer) {
            Ok(t) => t,
            Err(why) => return fail(why),
        };
        let from = text(d, "orchestratorName").unwrap_or("orchestrator");
        match self.pool.add(endpoint, &token.secret, token.fingerprint.as_deref(), text(d, "name"), from) {
            Ok(r) => json!({"ok": true, "name": r.name, "url": r.url, "runnerId": r.runner_id, "contract": r.contract, "grantedProjects": [], "grantedHarnesses": []}),
            Err(e) => fail(e),
        }
    }

    fn list(&self) -> Value {
        let mut runners: Vec<Value> = self
            .pool
            .runners()
            .into_iter()
            .map(|r| {
                let s = self.pool.state(&r.name);
                let mut v = json!({"name": r.name, "url": r.url, "runnerId": r.runner_id, "contract": r.contract, "state": s.word()});
                match s {
                    RunnerState::Online { rtt_ms, contract } => {
                        v["rttMs"] = json!(rtt_ms);
                        v["contract"] = json!(contract);
                    }
                    RunnerState::Offline { reason } => v["reason"] = json!(reason),
                    RunnerState::Outdated { contract, reason } => {
                        v["contract"] = json!(contract);
                        v["reason"] = json!(reason);
                    }
                    RunnerState::Unknown => {}
                }
                v
            })
            .collect();
        runners.insert(0, json!({"name": "local", "kind": "embedded", "state": "online", "url": "", "contract": infantry_runner_link::wire::CONTRACT}));
        json!({"ok": true, "runners": runners})
    }

    fn check(&self, d: &Map<String, Value>) -> Value {
        let Some(name) = text(d, "name") else { return fail("check needs a runner name") };
        let Some((state, ping)) = self.pool.check(name) else { return fail(format!("no runner named '{name}' (infantry runner list)")) };
        let rows = match (&state, ping) {
            (RunnerState::Online { rtt_ms, contract }, Some(p)) => vec![
                row("link", true, format!("pong from '{}' in {rtt_ms} ms", p.hello.runner_name), None),
                row("contract", true, format!("contract {contract}"), None),
                row("health", true, format!("{}, {} running, {} free slots", p.health.state, p.health.running, p.health.free_slots), None),
            ],
            (RunnerState::Outdated { contract, reason }, _) => vec![row("contract", false, format!("contract {contract}: {reason}"), Some("update the runner"))],
            (RunnerState::Offline { reason }, _) => {
                let fix = if reason.contains("not the pinned one") { "add the runner again with its current bearer" } else { "start the runner, then check the endpoint" };
                vec![row("link", false, reason.clone(), Some(fix))]
            }
            _ => Vec::new(),
        };
        json!({"ok": true, "name": name, "state": state.word(), "rows": rows})
    }

    fn detach(&self, d: &Map<String, Value>) -> Value {
        let Some(name) = text(d, "name") else { return fail("detach needs a runner name") };
        match self.pool.detach(name) {
            Some(remote) => json!({"ok": true, "name": name, "remote": remote}),
            None => fail(format!("no runner named '{name}'")),
        }
    }
}

impl FrameUnit for AdminUnit {
    fn name(&self) -> &'static str {
        "runner-pool"
    }

    fn frames(&self) -> &'static [&'static str] {
        FRAMES
    }

    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        if !ctx.owner {
            return Err(Refusal::new(ADMIN_FRAME, "owner only"));
        }
        let Ok(Value::Object(d)) = serde_json::from_str::<Value>(&env.data) else { return Err(Refusal::new(ADMIN_FRAME, "malformed request")) };
        let answer = match text(&d, "op") {
            Some("add") => self.add(&d),
            Some("list") => self.list(),
            Some("check") => self.check(&d),
            Some("detach") => self.detach(&d),
            Some(other) => fail(format!("unknown op '{other}' (add, list, check, detach)")),
            None => fail("missing op"),
        };
        let mut out = Envelope::new(ADMIN_FRAME, answer.to_string());
        out.request_id = env.request_id.clone();
        Ok(vec![out])
    }
}
