// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The RUNNER side of the link, with no transport: one request in, one answer out ([`LinkServer::handle`]).
//!
//! - With no credential only [`wire::PAIR`] is accepted: with the live one-time code or the runner's bearer secret. A code
//!   cannot move a runner paired with another orchestrator; the bearer can (it is the owner's authority).
//! - With the paired orchestrator's id and live credential every `runner_link_*` frame reaches the [`Backend`]; any other
//!   frame type is refused (a runner credential calls only the link family).
//! - Every operation, refused ones included, is appended to the audit file (health is not: it is the heartbeat).
//!
//! Refusal texts are the Kotlin runner's, word for word, so an orchestrator of either build reads them the same.

use crate::pairing::{Bearer, PairCodes, NO_LIVE_CODE, WRONG_BEARER};
use crate::store::LinkStore;
use crate::wire::{self, *};
use std::io::Write;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::Duration;

const SHOWN_TYPE_SIZE: usize = 64;
const GRANT_NOT_ALLOWED: &str = "grant not allowed by this code; set it on the runner (issue the code there with `infantry runner pair --grant-on-pair`, or grant by hand: infantry settings set runner.projects '[\"<project id>\"]' and infantry settings set runner.harnesses '[\"<harness id>\"]')";

/// What a runner reports in its hello.
#[derive(Debug, Clone, Default)]
pub struct RunnerInfo {
    pub harnesses: Vec<HarnessWire>,
    pub projects: Vec<String>,
    pub capacity: i32,
    pub os: String,
    pub offers: Vec<String>,
}

/// What the link reaches on the runner: its sessions or processes. Every method has the "not offered" answer as default.
pub trait Backend: Send + Sync + 'static {
    fn info(&self) -> RunnerInfo {
        RunnerInfo { os: std::env::consts::OS.into(), ..Default::default() }
    }
    fn health(&self) -> Option<HealthAnswer> {
        Some(HealthAnswer { state: "ok".into(), running: 0, free_slots: -1, load: None })
    }
    fn start(&self, _r: &StartRequest) -> StartAnswer {
        StartAnswer { refusal: Some("this runner starts no harness sessions".into()), ..Default::default() }
    }
    fn stop(&self, _r: &StopRequest) -> StopAnswer {
        StopAnswer { stopped: false }
    }
    fn remove(&self, _session_id: &str) -> bool {
        false
    }
    /// Whether this orchestrator started `session_id` here.
    fn is_linked(&self, _session_id: &str) -> bool {
        false
    }
    fn command(&self, _r: &CommandRequest) -> CommandAnswer {
        CommandAnswer { kind: "unknown".into(), ..Default::default() }
    }
    fn events(&self, _r: &EventsRequest) -> Vec<EventWire> {
        Vec::new()
    }
    /// The runner-side rows of a health check (after the `contract` row the server writes itself).
    fn check(&self, _r: &CheckRequest) -> Vec<CheckRow> {
        Vec::new()
    }
    /// `None` = frames_pull is not offered.
    fn frames_pull(&self, _r: &FramesPullRequest, _wait: Duration, _max: usize) -> Option<FramesPullAnswer> {
        None
    }
    /// `staged-files` on the runner's host.
    fn stage_file(&self, _r: &StageFileRequest) -> StageFileAnswer {
        StageFileAnswer { refusal: Some("this runner stages no files".into()), error_type: "unsupported".into(), ..Default::default() }
    }
    /// RR-13: one runner-scope frame. `Err` = refused (the reason names the frame); the default offers none.
    fn frame(&self, frame: &str, _data: &str) -> Result<crate::wire::FrameAnswer, String> {
        Err(format!("frame '{frame}' is {NOT_OFFERED} this runner offers"))
    }
    /// `loopback-http` on the runner's host: the stream id whose frames carry the response.
    fn http_open(&self, _r: &HttpOpenRequest) -> HttpOpenAnswer {
        HttpOpenAnswer { refusal: Some("this runner makes no loopback http requests".into()), error_type: "unsupported".into(), ..Default::default() }
    }
}

/// A backend that only answers hello and health (the ping/pong runner).
pub struct InfoOnly;
impl Backend for InfoOnly {}

pub struct LinkServer {
    store: LinkStore,
    codes: Option<Mutex<PairCodes>>,
    bearer: Option<Bearer>,
    backend: Box<dyn Backend>,
    audit: Option<PathBuf>,
    /// What the hello says this runner is: (kind, app version).
    identity: Option<(String, String)>,
    /// The longest a frames_pull is held here (below the caller's call timeout).
    pub push_wait_cap: Duration,
    pub push_frames_max: usize,
    clock: Box<dyn Fn() -> i64 + Send + Sync>,
    log: Box<dyn Fn(&str) + Send + Sync>,
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

fn shown(t: &str) -> String {
    t.chars().filter(|c| (' '..='~').contains(c)).take(SHOWN_TYPE_SIZE).collect()
}

fn decode<T: serde::de::DeserializeOwned>(data: &str) -> Option<T> {
    serde_json::from_str(data).ok()
}

fn is_id(s: &str) -> bool {
    !s.is_empty() && s.len() <= 64 && s.chars().all(|c| c.is_ascii_alphanumeric() || "-_.".contains(c))
}

fn contract_row(peer: Option<&str>) -> CheckRow {
    match peer {
        None => CheckRow { id: "contract".into(), ok: true, detail: format!("this runner speaks contract {CONTRACT}"), fix: None },
        Some(p) if p == CONTRACT => CheckRow { id: "contract".into(), ok: true, detail: format!("this runner speaks contract {CONTRACT}"), fix: None },
        Some(p) => CheckRow {
            id: "contract".into(),
            ok: false,
            detail: format!("this runner speaks contract {CONTRACT}, the orchestrator {p}"),
            fix: Some("update the runner to the same release as the orchestrator (restart it with the current image or install), then check again".into()),
        },
    }
}

impl LinkServer {
    pub fn new(store: LinkStore, codes: Option<PairCodes>, bearer: Option<Bearer>, backend: Box<dyn Backend>, audit: Option<PathBuf>) -> Self {
        LinkServer {
            store,
            codes: codes.map(Mutex::new),
            bearer,
            backend,
            audit,
            identity: None,
            push_wait_cap: Duration::from_secs(20),
            push_frames_max: 500,
            clock: Box::new(now_ms),
            log: Box::new(|l| eprintln!("{l}")),
        }
    }

    pub fn with_clock(mut self, clock: impl Fn() -> i64 + Send + Sync + 'static) -> Self {
        self.clock = Box::new(clock);
        self
    }

    /// The kind (`runner`) and app version the hello carries.
    pub fn with_identity(mut self, kind: &str, app_version: &str) -> Self {
        self.identity = Some((kind.into(), app_version.into()));
        self
    }

    pub fn with_log(mut self, log: impl Fn(&str) + Send + Sync + 'static) -> Self {
        self.log = Box::new(log);
        self
    }

    pub fn store(&self) -> &LinkStore {
        &self.store
    }

    /// Issues a fresh one-time code (the console's `runner pair`); None when this server takes no codes.
    pub fn issue_code(&self, runner_name: &str) -> Option<String> {
        self.codes.as_ref().map(|c| c.lock().unwrap().issue(runner_name, (self.clock)()))
    }

    fn record(&self, seat: &str, op: &str, outcome: &str, from: Option<&str>) {
        let Some(path) = &self.audit else { return };
        if let Some(dir) = path.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        let line = serde_json::json!({"at": (self.clock)(), "seat": seat, "op": op, "outcome": outcome, "from": from});
        if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(path) {
            let _ = writeln!(f, "{line}");
        }
    }

    fn refuse(&self, seat: &str, t: &str, from: Option<&str>, why: String) -> Answer {
        self.record(seat, &shown(t), &format!("refused: {why}"), from);
        Answer::error(why)
    }

    fn ok<T: serde::Serialize>(&self, seat: &str, t: &str, from: Option<&str>, value: &T) -> Answer {
        if t != wire::HEALTH && t != wire::FRAMES_PULL {
            self.record(seat, t, "ok", from);
        }
        Answer::reply(serde_json::to_string(value).expect("answer"))
    }

    pub fn handle(&self, t: &str, data: &str, orchestrator_id: Option<&str>, credential: Option<&str>, from: Option<&str>) -> Answer {
        const ANON: &str = "anonymous";
        if credential.map_or(true, |c| c.trim().is_empty()) {
            if t != wire::PAIR {
                return self.refuse(ANON, t, from, format!("{} refused: this runner link needs a pair credential", shown(t)));
            }
            return self.pair(data, from);
        }
        if !self.store.accepts(orchestrator_id, credential) {
            return self.refuse(ANON, t, from, format!("{} refused: {NOT_PAIRED}", shown(t)));
        }
        let seat = format!("orchestrator:{}", orchestrator_id.unwrap_or_default());
        if !t.starts_with("runner_link_") {
            return self.refuse(&seat, t, from, format!("{} refused: a runner credential (seat {}) may call only the runner link frames", shown(t), shown(&seat)));
        }
        let malformed = || self.refuse(&seat, t, from, format!("{t} refused: malformed request"));
        let not_linked = |id: &str| self.refuse(&seat, t, from, format!("{t} refused: session {} was not started by this orchestrator", id.chars().take(SHOWN_TYPE_SIZE).collect::<String>()));
        match t {
            wire::PAIR => self.refuse(&seat, t, from, format!("{t} refused: already paired; rotate with {}", wire::REKEY)),
            wire::HELLO => {
                let p = self.store.orchestrator().expect("paired");
                let info = self.backend.info();
                let a = HelloAnswer {
                    runner_id: self.store.self_id(),
                    runner_name: p.runner_name,
                    contract: CONTRACT.into(),
                    issued_at: p.issued_at,
                    harnesses: info.harnesses,
                    projects: info.projects,
                    capacity: info.capacity,
                    os: info.os,
                    offers: info.offers,
                    kind: self.identity.as_ref().map(|i| i.0.clone()),
                    app_version: self.identity.as_ref().map(|i| i.1.clone()),
                };
                self.ok(&seat, t, from, &a)
            }
            wire::REKEY => {
                let p = self.store.orchestrator().expect("paired");
                let now = (self.clock)();
                let fresh = self.store.issue(&p.id, &p.name, &p.runner_name, now);
                self.ok(&seat, t, from, &RekeyAnswer { credential: fresh, issued_at: now })
            }
            wire::DETACH => {
                self.store.unpair();
                self.record(&seat, t, "ok", from);
                Answer::reply("{}")
            }
            wire::HEALTH => match self.backend.health() {
                Some(h) => self.ok(&seat, t, from, &h),
                None => self.refuse(&seat, t, from, format!("{t} unavailable on this runner")),
            },
            wire::START => match decode::<StartRequest>(data) {
                Some(r) => {
                    let a = self.backend.start(&r);
                    self.ok(&seat, t, from, &a)
                }
                None => malformed(),
            },
            wire::CHECK => match decode::<CheckRequest>(data) {
                Some(r) => {
                    let mut rows = vec![contract_row(r.contract.as_deref())];
                    rows.extend(self.backend.check(&r));
                    self.ok(&seat, t, from, &CheckAnswer { rows })
                }
                None => malformed(),
            },
            wire::STOP => match decode::<StopRequest>(data) {
                Some(r) if self.backend.is_linked(&r.session_id) => {
                    let a = self.backend.stop(&r);
                    self.ok(&seat, t, from, &a)
                }
                Some(r) => not_linked(&r.session_id),
                None => malformed(),
            },
            wire::REMOVE => match decode::<RemoveRequest>(data) {
                Some(r) => {
                    let removed = self.backend.is_linked(&r.session_id) && self.backend.remove(&r.session_id);
                    self.ok(&seat, t, from, &RemoveAnswer { removed })
                }
                None => malformed(),
            },
            wire::COMMAND => match decode::<CommandRequest>(data) {
                Some(r) if self.backend.is_linked(&r.session_id) => {
                    let a = self.backend.command(&r);
                    self.ok(&seat, t, from, &a)
                }
                Some(_) => self.ok(&seat, t, from, &CommandAnswer { kind: "unknown".into(), ..Default::default() }),
                None => malformed(),
            },
            wire::EVENTS => match decode::<EventsRequest>(data) {
                Some(r) => Answer::reply(serde_json::to_string(&EventsAnswer { events: self.backend.events(&r) }).expect("events")),
                None => malformed(),
            },
            wire::FRAMES_PULL => {
                let Some(r) = decode::<FramesPullRequest>(data) else { return malformed() };
                let cap = self.push_frames_max.max(1);
                let max = if r.max >= 1 && (r.max as usize) <= cap { r.max as usize } else { cap };
                let wait = Duration::from_millis(r.wait_ms.max(0) as u64).min(self.push_wait_cap);
                match self.backend.frames_pull(&r, wait, max) {
                    Some(a) => self.ok(&seat, t, from, &a),
                    None => self.refuse(&seat, t, from, format!("{t} refused: {NOT_OFFERED} this runner offers (contract {CONTRACT})")),
                }
            }
            wire::STAGE_FILE => match decode::<StageFileRequest>(data) {
                Some(r) => {
                    let a = self.backend.stage_file(&r);
                    self.ok(&seat, t, from, &a)
                }
                None => malformed(),
            },
            wire::FRAME => match decode::<wire::FrameRequest>(data) {
                // The audit line records the op and the frame NAME, never the data (it can carry a key).
                Some(r) => match self.backend.frame(&r.frame, &r.data) {
                    Ok(a) => {
                        self.record(&seat, &format!("{t}:{}", shown(&r.frame)), "ok", from);
                        Answer::reply(serde_json::to_string(&a).expect("frame answer"))
                    }
                    Err(why) => self.refuse(&seat, &format!("{t}:{}", shown(&r.frame)), from, why),
                },
                None => malformed(),
            },
            wire::HTTP_OPEN => match decode::<HttpOpenRequest>(data) {
                Some(r) => {
                    let a = self.backend.http_open(&r);
                    self.ok(&seat, t, from, &a)
                }
                None => malformed(),
            },
            _ => self.refuse(&seat, t, from, format!("{} refused: {NOT_OFFERED} this runner offers (contract {CONTRACT})", shown(t))),
        }
    }

    fn pair(&self, data: &str, from: Option<&str>) -> Answer {
        const ANON: &str = "anonymous";
        let p = wire::PAIR;
        let Some(req) = decode::<PairRequest>(data) else { return self.refuse(ANON, p, from, format!("{p} refused: malformed request")) };
        let wanted: Vec<String> =
            req.grant_projects.iter().chain(&req.grant_harnesses).map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect();
        if wanted.iter().any(|s| s == "*") {
            return self.refuse(ANON, p, from, format!("{p} refused: a grant at pairing may not name '*' (name the ids; `infantry runner pair --allow-projects *` on the runner grants every one)"));
        }
        if let Some(bad) = wanted.iter().find(|s| !is_id(s)) {
            return self.refuse(ANON, p, from, format!("{p} refused: '{}' is not an id", shown(bad)));
        }
        let now = (self.clock)();
        let by_bearer = self.bearer.as_ref().and_then(|b| b.read().filter(|_| b.accepts(&req.code)));
        let bearer_exists = self.bearer.as_ref().is_some_and(|b| b.read().is_some());
        let mut runner_name = by_bearer.as_ref().map(|b| b.name.clone());
        let redeemed: Result<(), String> = if by_bearer.is_some() {
            Ok(())
        } else if let Some(codes) = &self.codes {
            let mut c = codes.lock().unwrap();
            runner_name = c.live_name(now).map(str::to_string);
            c.redeem(&req.code, now).map_err(|r| if bearer_exists && r == NO_LIVE_CODE { WRONG_BEARER.to_string() } else { r })
        } else {
            Err(if bearer_exists { WRONG_BEARER.into() } else { NO_LIVE_CODE.into() })
        };
        if let Err(why) = redeemed {
            return self.refuse(ANON, p, from, format!("{p} refused: {why}"));
        }
        if req.orchestrator_id.trim().is_empty() {
            return self.refuse(ANON, p, from, format!("{p} refused: no orchestrator id"));
        }
        let previous = self.store.orchestrator().filter(|o| o.id != req.orchestrator_id);
        if let (Some(prev), None) = (&previous, &by_bearer) {
            return self.refuse(ANON, p, from, format!(
                "{p} refused: this runner is paired with another orchestrator ({}) and a one-time code cannot move it. Paste the runner's bearer instead (`infantry runner bearer` on the runner prints it): the bearer moves the runner to this app",
                prev.name
            ));
        }
        let seat = format!("orchestrator:{}", req.orchestrator_id);
        if let Some(prev) = &previous {
            self.record(&seat, "runner_link_pair_takeover", &format!("replaced orchestrator {} ({})", prev.name, prev.id), from);
            (self.log)(&format!(
                "[infantry] runner link: paired with orchestrator {} by this runner's bearer; it replaces {}, whose link stops working",
                if req.orchestrator_name.trim().is_empty() { &req.orchestrator_id } else { &req.orchestrator_name },
                prev.name
            ));
        }
        let runner_name = runner_name.unwrap_or_default();
        let credential = self.store.issue(&req.orchestrator_id, &req.orchestrator_name, &runner_name, now);
        let answer = PairAnswer {
            runner_id: self.store.self_id(),
            runner_name,
            credential,
            contract: CONTRACT.into(),
            granted_projects: vec![],
            granted_harnesses: vec![],
            grant_refusal: (!wanted.is_empty()).then(|| GRANT_NOT_ALLOWED.to_string()),
            replaced_orchestrator: previous.map(|p| p.name),
        };
        self.ok(&seat, p, from, &answer)
    }
}

impl crate::transport::LinkHandler for LinkServer {
    fn handle(&self, t: &str, data: &str, orchestrator_id: Option<&str>, credential: Option<&str>, from: Option<&str>) -> Answer {
        LinkServer::handle(self, t, data, orchestrator_id, credential, from)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn server() -> LinkServer {
        LinkServer::new(LinkStore::in_memory(), Some(PairCodes::new(60_000, 10)), None, Box::new(InfoOnly), None).with_clock(|| 1000)
    }

    fn pair(s: &LinkServer, code: &str, id: &str) -> Answer {
        s.handle(PAIR, &serde_json::json!({"code": code, "orchestratorId": id}).to_string(), None, None, None)
    }

    #[test]
    fn pair_then_hello_and_health() {
        let s = server();
        assert_eq!(s.handle(HELLO, "", None, None, None).data, "runner_link_hello refused: this runner link needs a pair credential");
        let code = s.issue_code("box").unwrap();
        let a = pair(&s, &code, "inf-o");
        assert!(a.ok(), "{a:?}");
        let pa: PairAnswer = serde_json::from_str(&a.data).unwrap();
        assert_eq!((pa.runner_name.as_str(), pa.contract.as_str(), pa.grant_refusal), ("box", CONTRACT, None));
        let h = s.handle(HELLO, "", Some("inf-o"), Some(&pa.credential), None);
        let hello: HelloAnswer = serde_json::from_str(&h.data).unwrap();
        assert_eq!((hello.runner_id, hello.runner_name, hello.issued_at), (pa.runner_id, "box".to_string(), 1000));
        let health = s.handle(HEALTH, "", Some("inf-o"), Some(&pa.credential), None);
        assert_eq!(health.data, r#"{"state":"ok","running":0,"freeSlots":-1,"load":null}"#);
        assert_eq!(s.handle(HEALTH, "", Some("inf-o"), Some("bad"), None).data, format!("runner_link_health refused: {NOT_PAIRED}"));
        assert!(s.handle("settings_set", "", Some("inf-o"), Some(&pa.credential), None).data.contains("may call only the runner link frames"));
        assert!(s.handle("runner_link_nope", "", Some("inf-o"), Some(&pa.credential), None).data.starts_with("runner_link_nope refused: not a runner link operation"));
        assert_eq!(pair(&s, &code, "inf-o").data, format!("runner_link_pair refused: {NO_LIVE_CODE}"));
    }

    #[test]
    fn a_code_cannot_move_a_paired_runner_but_a_bearer_can() {
        let dir = std::env::temp_dir().join(format!("link-srv-{}", uuid::Uuid::new_v4()));
        let b = Bearer::of(&dir);
        let v = b.ensure(Some("box"), false).unwrap();
        let s = LinkServer::new(LinkStore::in_memory(), Some(PairCodes::new(60_000, 10)), Some(Bearer::of(&dir)), Box::new(InfoOnly), None).with_log(|_| {});
        assert!(pair(&s, &v.secret, "inf-a").ok());
        let code = s.issue_code("box").unwrap();
        assert!(pair(&s, &code, "inf-b").data.contains("a one-time code cannot move it"));
        let moved: PairAnswer = serde_json::from_str(&pair(&s, &v.secret, "inf-b").data).unwrap();
        assert_eq!(moved.replaced_orchestrator.as_deref(), Some("inf-a"));
        // The code was spent by the refused move (as on Kotlin), so a wrong secret now reads as a wrong bearer.
        assert!(pair(&s, "wrong", "inf-c").data.contains("wrong bearer"));
        std::fs::remove_dir_all(dir).unwrap();
    }

    #[test]
    fn grants_are_refused_without_authority() {
        let s = server();
        let code = s.issue_code("box").unwrap();
        let a = s.handle(PAIR, &serde_json::json!({"code": code, "orchestratorId": "o", "grantProjects": ["*"]}).to_string(), None, None, None);
        assert!(a.data.contains("may not name '*'"));
        let a = s.handle(PAIR, &serde_json::json!({"code": code, "orchestratorId": "o", "grantProjects": ["p1"]}).to_string(), None, None, None);
        let pa: PairAnswer = serde_json::from_str(&a.data).unwrap();
        assert_eq!(pa.grant_refusal.as_deref(), Some(GRANT_NOT_ALLOWED));
    }
}
