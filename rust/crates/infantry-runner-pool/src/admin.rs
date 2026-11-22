// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The owner-only `runner_admin` frame: `{op: add|list|check|detail|detach, ...}` -> one `runner_admin` answer `{ok, ...}`.
use crate::pool::{RunnerPool, RunnerState};
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_runner_link::pairing::BearerToken;
use serde_json::{json, Map, Value};
use std::sync::Arc;

pub const ADMIN_FRAME: &str = "runner_admin";
const FRAMES: &[&str] = &[ADMIN_FRAME];

/// RR-6: what a check needs of the orchestrator's lending policy (the daemon root implements it over its vault and settings).
pub trait CheckLend: Send + Sync {
    /// The git credential a start on `runner` would lend for `project`, as the JSON a check request carries; `None` = none.
    fn git_credentials(&self, runner: &str, project: Option<&str>) -> Option<Value>;
    /// Where `harness` gets its credential on `runner`: `runner_has_key` is whether the runner's own vault holds its key.
    fn source(&self, runner: &str, harness: &str, runner_has_key: bool) -> CredentialSource;
    /// The harnesses this orchestrator knows (a process-only runner does not list its own).
    fn harnesses(&self) -> Vec<String>;
}

/// Where a harness's credential comes from, for the check row.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CredentialSource {
    Lent,
    StoredOnRunner,
    None,
}

pub struct AdminUnit {
    pub pool: Arc<RunnerPool>,
    /// The lending policy; unset = the check shows no credential rows.
    pub lend: Option<Arc<dyn CheckLend>>,
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
                let mut v = json!({"name": r.name, "url": r.url, "runnerId": r.runner_id, "contract": r.contract, "state": s.word(), "needs": infantry_runner_link::wire::MIN_RUNNER_CONTRACT, "outdated": matches!(s, RunnerState::Outdated { .. })});
                if let Some(a) = self.pool.runner_app_version(&r.name) {
                    v["appVersion"] = json!(a);
                }
                if let Some(u) = self.pool.update_status(&r.name) {
                    v["update"] = json!({"state": u.state, "steps": u.steps, "detail": u.detail});
                }
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
        let ping_harnesses: Vec<String> = ping.as_ref().map(|p| p.hello.harnesses.iter().map(|h| h.id.clone()).collect()).unwrap_or_default();
        let rows = match (&state, ping) {
            (RunnerState::Online { rtt_ms, contract }, Some(p)) => vec![
                row("link", true, format!("pong from '{}' in {rtt_ms} ms", p.hello.runner_name), None),
                row("contract", true, format!("contract {contract}"), None),
                row("health", true, format!("{}, {} running, {}", p.health.state, p.health.running, if p.health.free_slots < 0 { "no slot limit".to_string() } else { format!("{} free slots", p.health.free_slots) }), None),
            ],
            (RunnerState::Outdated { contract, reason }, _) => vec![row("contract", false, format!("contract {contract}: {reason}"), Some("update the runner"))],
            (RunnerState::Offline { reason }, _) => {
                let (plain, fix) = crate::pool::plain_reason(reason);
                vec![row("link", false, format!("{plain} ({reason})"), Some(&fix))]
            }
            _ => Vec::new(),
        };
        let mut rows = rows;
        if matches!(state, RunnerState::Online { .. }) {
            rows.extend(self.credential_rows(name, d, ping_harnesses));
        }
        for r in &rows {
            let ok = r["ok"] == true;
            let (detail, fix) = (r["detail"].as_str().unwrap_or(""), r["fix"].as_str().unwrap_or(""));
            self.pool.log_step(name, r["id"].as_str().unwrap_or("check"), "runner check", detail, Some(if ok { 0 } else { 1 }), if ok { "passed" } else { fix });
        }
        self.pool.keep_check(name, rows.clone());
        json!({"ok": true, "name": name, "state": state.word(), "rows": rows})
    }

    /// RR-VIS: everything the connection panel shows about one runner, in one answer. No new probe: it reads what the last
    /// heartbeat or check learned (`refresh` first for a fresh look).
    fn detail(&self, d: &Map<String, Value>) -> Value {
        let Some(name) = text(d, "name") else { return fail("detail needs a runner name") };
        let last = self.pool.last_check(name).map(|(at, rows)| json!({"at": at, "rows": rows}));
        if name == "local" {
            return json!({"ok": true, "name": "local",
                "runner": {"name": "local", "runnerId": "", "mode": "embedded (this app)", "os": std::env::consts::OS, "appVersion": self.pool.app_version, "contract": infantry_runner_link::wire::CONTRACT, "needs": infantry_runner_link::wire::MIN_RUNNER_CONTRACT},
                "link": {"url": "", "state": "online", "reasonPlain": "the embedded runner is this app itself: there is no network link"},
                "lastCheck": last, "log": self.pool.step_log("local")});
        }
        let Some(r) = self.pool.runners().into_iter().find(|r| r.name == name) else { return fail(format!("no runner named '{name}' (infantry runner list)")) };
        let s = self.pool.state(name);
        let seen = self.pool.seen(name);
        let mode = match seen.as_ref().and_then(|x| x.kind.as_deref()) {
            Some("runner") => "remote headless (app --mode runner)".to_string(),
            Some(k) => k.to_string(),
            None if seen.is_some() => "remote (a Kotlin runner)".to_string(),
            None => "remote (not answered yet)".to_string(),
        };
        let mut link = json!({"url": r.url, "fingerprint": r.fingerprint, "pairedAt": r.issued_at, "pairedFrom": r.paired_from, "state": s.word()});
        if let Some(x) = &seen {
            link["lastPingAt"] = json!(x.at_ms);
        }
        match &s {
            RunnerState::Online { rtt_ms, .. } => link["rttMs"] = json!(rtt_ms),
            RunnerState::Offline { reason } | RunnerState::Outdated { reason, .. } => {
                let (plain, fix) = crate::pool::plain_reason(reason);
                link["reason"] = json!(reason);
                link["reasonPlain"] = json!(if matches!(s, RunnerState::Outdated { .. }) { "the runner is older than this app".to_string() } else { plain });
                link["fix"] = json!(if matches!(s, RunnerState::Outdated { .. }) { "Update runner".to_string() } else { fix });
            }
            RunnerState::Unknown => link["reasonPlain"] = json!("not probed yet: press Check"),
        }
        let harnesses: Vec<Value> = seen.as_ref().map(|x| x.harnesses.iter().map(|(id, li)| json!({"id": id, "loggedIn": li})).collect()).unwrap_or_default();
        let mut v = json!({"ok": true, "name": name,
            "runner": {"name": r.name, "runnerId": r.runner_id, "mode": mode, "os": seen.as_ref().map(|x| x.os.clone()).unwrap_or_default(), "appVersion": self.pool.runner_app_version(name), "contract": match &s { RunnerState::Online { contract, .. } | RunnerState::Outdated { contract, .. } => contract.clone(), _ => r.contract.clone() }, "needs": infantry_runner_link::wire::MIN_RUNNER_CONTRACT},
            "link": link, "offers": seen.as_ref().map(|x| x.offers.clone()).unwrap_or_default(), "helloHarnesses": harnesses, "lastCheck": last, "log": self.pool.step_log(name)});
        if let Some(u) = self.pool.update_status(name) {
            v["update"] = json!({"state": u.state, "steps": u.steps, "detail": u.detail});
        }
        v
    }

    /// RR-6: the runner-side `credentials` and `git` rows, then one `credential:<harness>` row per harness saying where its
    /// credential comes from (lent / stored on the runner / none). A value is never shown.
    fn credential_rows(&self, name: &str, d: &Map<String, Value>, harnesses: Vec<String>) -> Vec<Value> {
        let Some(lend) = &self.lend else { return Vec::new() };
        let project = text(d, "projectId");
        let git = lend.git_credentials(name, project);
        let req = infantry_runner_link::wire::CheckRequest { project_id: project.map(str::to_string), git_remote: text(d, "gitRemote").map(str::to_string), credentials: git, ..Default::default() };
        let mut rows: Vec<Value> = match self.pool.check_rows(name, &req) {
            Ok(rs) => rs.into_iter().map(|r| row(&r.id, r.ok, r.detail, r.fix.as_deref())).collect(),
            Err(e) => vec![row("git", false, format!("the runner did not answer the check: {e}"), None)],
        };
        let stored = self.pool.runner_secret_names(name).unwrap_or_default();
        let harnesses = if harnesses.is_empty() { lend.harnesses() } else { harnesses };
        let logins = self.pool.runner_logins(name).unwrap_or_default();
        for h in harnesses {
            let has = stored.iter().any(|n| n == &format!("harness.{h}.key") || n == &format!("harness.{h}.token"));
            // The runner's own login (a subscription, not an API key) counts: the harness row above already says it is signed in.
            // What the runner's own harness tests (OAuth credentials file, CLAUDE_CODE_OAUTH_TOKEN or ANTHROPIC_API_KEY), asked of the runner itself.
            let login = logins.iter().find(|(id, _, _)| id == &h);
            let signed_in = login.is_some_and(|(_, ok, _)| *ok);
            let via = login.and_then(|(_, _, at)| at.clone()).map(|a| format!(" ({a})")).unwrap_or_default();
            let (ok, detail, fix) = match lend.source(name, &h, has) {
                CredentialSource::Lent => (true, "the key is lent by the orchestrator with each start".to_string(), None),
                CredentialSource::StoredOnRunner => (true, "the key is stored on the runner".to_string(), None),
                CredentialSource::None if signed_in => (true, format!("signed in on the runner{via}: its own login or key, nothing to lend"), None),
                CredentialSource::None => (false, "not signed in on the runner, and no key is lent or stored".to_string(), Some("Set up this runner → Sign in, or set the key in the runner's provider section")),
            };
            rows.push(row(&format!("credential:{h}"), ok, detail, fix));
        }
        rows
    }

    /// A heartbeat now, then the list.
    fn refresh(&self) -> Value {
        self.pool.beat();
        self.list()
    }

    fn update(&self, d: &Map<String, Value>) -> Value {
        let Some(name) = text(d, "name") else { return fail("update needs a runner name") };
        if name == "local" {
            return fail("the embedded runner is the app itself: update the app");
        }
        match self.pool.update(name) {
            Ok(text) => json!({"ok": true, "name": name, "text": text, "container": !text.contains("is not a Docker container")}),
            Err(e) => fail(e),
        }
    }

    fn update_status(&self, d: &Map<String, Value>) -> Value {
        let Some(name) = text(d, "name") else { return fail("update_status needs a runner name") };
        match self.pool.update_status(name) {
            Some(u) => json!({"ok": true, "name": name, "state": u.state, "steps": u.steps, "detail": u.detail}),
            None => json!({"ok": true, "name": name, "state": "none", "steps": [], "detail": ""}),
        }
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
            Some("detail") => self.detail(&d),
            Some("detach") => self.detach(&d),
            Some("refresh") => self.refresh(),
            Some("update") => self.update(&d),
            Some("update_status") => self.update_status(&d),
            Some(other) => fail(format!("unknown op '{other}' (add, list, refresh, check, detail, update, update_status, detach)")),
            None => fail("missing op"),
        };
        let mut out = Envelope::new(ADMIN_FRAME, answer.to_string());
        out.request_id = env.request_id.clone();
        Ok(vec![out])
    }
}
