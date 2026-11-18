// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The Rust runner's process backend: a link [`Backend`] over any [`Runner`] (a composition root passes the local one), so a
//! [`crate::RemoteRunner`] on another machine drives processes here.
//!
//! - `runner_link_start` with [`StartRequest::process`] spawns it; the session id is the process id;
//! - its output and exit are pushed on `runner_link_frames_pull` as [`wire::PROC_OUTPUT`] / [`wire::PROC_STDERR`] (data =
//!   base64 bytes) / [`wire::PROC_EXIT`] (data = the code) frames, sequenced per session from 1;
//! - the exit is also a `finished` event on `runner_link_events` (payload = the code);
//! - `runner_link_command` kinds [`wire::CMD_WRITE`] (text = base64 bytes) and [`wire::CMD_CLOSE_INPUT`];
//! - `runner_link_stop` kills the tree, `runner_link_remove` releases it.

use crate::server::{Backend, RunnerInfo};
use crate::wire::{self, *};
use base64::Engine;
use infantry_protocol::Envelope;
use infantry_runner_api::{HttpEvent, HttpRequest, KillScope, ProcEvent, ProcId, Runner};
use std::collections::HashMap;
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

#[derive(Default)]
struct Session {
    frames: Vec<Envelope>,
    exited: Option<i32>,
}

#[derive(Default)]
struct Shared {
    sessions: HashMap<String, Session>,
    events: Vec<EventWire>,
    event_seq: HashMap<String, i64>,
}

pub struct ProcessBackend {
    runner: Arc<dyn Runner>,
    rt: tokio::runtime::Runtime,
    shared: Arc<(Mutex<Shared>, Condvar)>,
}

const B64: base64::engine::GeneralPurpose = base64::engine::general_purpose::STANDARD;
/// The id prefix of a link http stream (never a runner process id).
const HTTP_STREAM_PREFIX: &str = "http-";

impl ProcessBackend {
    pub fn new(runner: Arc<dyn Runner>) -> Self {
        let rt = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().thread_name("runner-link-proc").build().expect("runtime");
        ProcessBackend { runner, rt, shared: Arc::new((Mutex::new(Shared::default()), Condvar::new())) }
    }

    fn push(shared: &(Mutex<Shared>, Condvar), sid: &str, t: &str, data: String) {
        let (m, cv) = shared;
        let mut s = m.lock().unwrap();
        let sess = s.sessions.entry(sid.to_string()).or_default();
        let mut e = Envelope::new(t, data);
        e.session_id = Some(sid.to_string());
        e.sequence = Some(sess.frames.len() as i64 + 1);
        sess.frames.push(e);
        cv.notify_all();
    }
}

impl Backend for ProcessBackend {
    fn info(&self) -> RunnerInfo {
        RunnerInfo {
            os: std::env::consts::OS.into(),
            offers: vec![OFFER_PROCESS.into(), OFFER_STAGED_FILES.into(), OFFER_LOOPBACK_HTTP.into()],
            ..Default::default()
        }
    }

    fn health(&self) -> Option<HealthAnswer> {
        let s = self.shared.0.lock().unwrap();
        let running = s.sessions.values().filter(|x| x.exited.is_none()).count() as i32;
        Some(HealthAnswer { state: "ok".into(), running, free_slots: -1, load: None })
    }

    fn start(&self, r: &StartRequest) -> StartAnswer {
        let Some(spec) = r.process.clone() else {
            return StartAnswer { refusal: Some("this runner runs processes only (a start with `process`); harness sessions are not offered here yet".into()), ..Default::default() };
        };
        let runner = self.runner.clone();
        let handle = match self.rt.block_on(runner.spawn(spec)) {
            Ok(h) => h,
            Err(e) => return StartAnswer { refusal: Some(e.to_string()), ..Default::default() },
        };
        let sid = handle.id.0.clone();
        self.shared.0.lock().unwrap().sessions.insert(sid.clone(), Session::default());
        let rx = self.rt.block_on(self.runner.events(&handle.id));
        let mut rx = match rx {
            Ok(rx) => rx,
            Err(e) => return StartAnswer { refusal: Some(e.to_string()), ..Default::default() },
        };
        let shared = self.shared.clone();
        let id = sid.clone();
        self.rt.spawn(async move {
            while let Some(ev) = rx.recv().await {
                match ev {
                    ProcEvent::Output(b) => Self::push(&shared, &id, wire::PROC_OUTPUT, B64.encode(b)),
                    ProcEvent::Stderr(b) => Self::push(&shared, &id, wire::PROC_STDERR, B64.encode(b)),
                    ProcEvent::Exited(code) => {
                        Self::push(&shared, &id, wire::PROC_EXIT, code.to_string());
                        let mut s = shared.0.lock().unwrap();
                        if let Some(x) = s.sessions.get_mut(&id) {
                            x.exited = Some(code);
                        }
                        let seq = s.event_seq.entry(id.clone()).or_insert(0);
                        *seq += 1;
                        let seq = *seq;
                        s.events.push(EventWire { session_id: id.clone(), seq, kind: "finished".into(), payload: code.to_string() });
                        shared.1.notify_all();
                    }
                    _ => {}
                }
            }
        });
        StartAnswer { session_id: Some(sid), ..Default::default() }
    }

    fn is_linked(&self, session_id: &str) -> bool {
        self.shared.0.lock().unwrap().sessions.contains_key(session_id)
    }

    fn stop(&self, r: &StopRequest) -> StopAnswer {
        let id = ProcId(r.session_id.clone());
        let scope = if r.force { KillScope::Tree } else { KillScope::Process };
        StopAnswer { stopped: self.rt.block_on(self.runner.kill(&id, scope)).is_ok() }
    }

    fn remove(&self, session_id: &str) -> bool {
        let id = ProcId(session_id.to_string());
        // An http stream is the link's own, not a runner process: forgetting its frames is the whole release.
        let ok = session_id.starts_with(HTTP_STREAM_PREFIX) || self.rt.block_on(self.runner.release(&id)).is_ok();
        let mut s = self.shared.0.lock().unwrap();
        s.sessions.remove(session_id);
        s.events.retain(|e| e.session_id != session_id);
        ok
    }

    fn command(&self, r: &CommandRequest) -> CommandAnswer {
        let id = ProcId(r.session_id.clone());
        let done = |res: Result<(), infantry_runner_api::RunnerError>| match res {
            Ok(()) => CommandAnswer { kind: "done".into(), ..Default::default() },
            Err(e) => CommandAnswer { kind: "refused".into(), reason: Some(e.to_string()), error_type: error_type(&e).into(), started: false },
        };
        match r.kind.as_str() {
            wire::CMD_WRITE => match B64.decode(r.text.as_bytes()) {
                Ok(bytes) => done(self.rt.block_on(self.runner.write(&id, &bytes))),
                Err(_) => CommandAnswer { kind: "refused".into(), reason: Some("text is not base64".into()), ..Default::default() },
            },
            wire::CMD_CLOSE_INPUT => done(self.rt.block_on(self.runner.close_input(&id))),
            wire::CMD_STATUS => done(Ok(())),
            // `unknown` means "not a session of yours" on the link; a known session's unknown kind is a refusal.
            other => CommandAnswer { kind: "refused".into(), reason: Some(format!("command kind '{other}' is not offered for a process")), error_type: "unsupported".into(), started: false },
        }
    }

    fn events(&self, r: &EventsRequest) -> Vec<EventWire> {
        let mut s = self.shared.0.lock().unwrap();
        if !r.acked.is_empty() {
            s.events.retain(|e| r.acked.get(&e.session_id).is_none_or(|a| e.seq > *a));
            s.events.clone()
        } else {
            s.events.iter().filter(|e| e.seq > r.since_seq).cloned().collect()
        }
    }

    fn stage_file(&self, r: &StageFileRequest) -> StageFileAnswer {
        let Ok(bytes) = B64.decode(r.contents.as_bytes()) else {
            return StageFileAnswer { refusal: Some("contents is not base64".into()), error_type: "io".into(), ..Default::default() };
        };
        match self.rt.block_on(self.runner.stage_file(&r.name, &bytes)) {
            Ok(path) => StageFileAnswer { path: Some(path), ..Default::default() },
            Err(e) => StageFileAnswer { refusal: Some(e.to_string()), error_type: error_type(&e).into(), path: None },
        }
    }

    fn http_open(&self, r: &HttpOpenRequest) -> HttpOpenAnswer {
        let Ok(body) = B64.decode(r.body.as_bytes()) else {
            return HttpOpenAnswer { refusal: Some("body is not base64".into()), error_type: "io".into(), ..Default::default() };
        };
        let mut req = HttpRequest::new(r.method.clone(), r.url.clone());
        req.headers = r.headers.clone();
        req.body = body;
        req.timeout_ms = r.timeout_ms;
        let mut rx = match self.rt.block_on(self.runner.http(req)) {
            Ok(rx) => rx,
            Err(e) => return HttpOpenAnswer { refusal: Some(e.to_string()), error_type: error_type(&e).into(), stream_id: None },
        };
        let sid = format!("{HTTP_STREAM_PREFIX}{}", uuid::Uuid::new_v4());
        self.shared.0.lock().unwrap().sessions.insert(sid.clone(), Session::default());
        let (shared, id) = (self.shared.clone(), sid.clone());
        self.rt.spawn(async move {
            let mut ended = false;
            while let Some(ev) = rx.recv().await {
                match ev {
                    HttpEvent::Head { status, headers } => {
                        Self::push(&shared, &id, wire::HTTP_HEAD, serde_json::to_string(&HttpHeadWire { status, headers }).unwrap_or_default())
                    }
                    HttpEvent::Body(b) => Self::push(&shared, &id, wire::HTTP_BODY, B64.encode(b)),
                    HttpEvent::End => {
                        Self::push(&shared, &id, wire::HTTP_END, String::new());
                        ended = true;
                    }
                    HttpEvent::Failed(why) => {
                        Self::push(&shared, &id, wire::HTTP_FAILED, why);
                        ended = true;
                    }
                    _ => {}
                }
                if ended {
                    break;
                }
            }
            if !ended {
                Self::push(&shared, &id, wire::HTTP_FAILED, "the runner's http stream closed without an end".into());
            }
            if let Some(x) = shared.0.lock().unwrap().sessions.get_mut(&id) {
                x.exited = Some(0);
            }
        });
        HttpOpenAnswer { stream_id: Some(sid), ..Default::default() }
    }

    fn frames_pull(&self, r: &FramesPullRequest, wait: Duration, max: usize) -> Option<FramesPullAnswer> {
        let (m, cv) = &*self.shared;
        let deadline = Instant::now() + wait;
        let mut s = m.lock().unwrap();
        loop {
            let mut frames = Vec::new();
            let mut more = false;
            for (sid, after) in &r.resume_from {
                let Some(sess) = s.sessions.get(sid) else { continue };
                for f in sess.frames.iter().filter(|f| f.sequence.unwrap_or(0) > *after) {
                    if frames.len() >= max {
                        more = true;
                        break;
                    }
                    frames.push(f.clone());
                }
            }
            let now = Instant::now();
            if !frames.is_empty() || now >= deadline {
                return Some(FramesPullAnswer { frames, more });
            }
            s = cv.wait_timeout(s, deadline - now).unwrap().0;
        }
    }
}

/// The [`infantry_runner_api::RunnerError`] kind word, so the other side rebuilds the same error.
pub fn error_type(e: &infantry_runner_api::RunnerError) -> &'static str {
    use infantry_runner_api::RunnerError::*;
    match e {
        NotFound(_) => "not_found",
        Unsupported(_) => "unsupported",
        Exited(_) => "exited",
        Io(_) => "io",
        Disconnected(_) => "disconnected",
        _ => "io",
    }
}
