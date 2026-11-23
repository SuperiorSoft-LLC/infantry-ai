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

use crate::marks::{Mark, MarkFile, CHECKPOINT_EVERY, RESTART_GAP};
use crate::server::{Backend, RunnerInfo};
use crate::wire::{self, *};
use base64::Engine;
use infantry_protocol::Envelope;
use infantry_runner_api::{HookCall, HttpEvent, HttpRequest, KillScope, LentCredentials, Masker, ProcEvent, ProcId, Runner, Size, TailFrom, TailSpec};
use std::collections::HashMap;
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

#[derive(Default)]
struct Session {
    frames: Vec<Envelope>,
    exited: Option<i32>,
    /// The highest sequence handed out (frames are numbered from here, not from `frames.len()`, so a session restored after
    /// a runner restart continues above the orchestrator's cursor).
    last_seq: i64,
}

#[derive(Default)]
struct Shared {
    sessions: HashMap<String, Session>,
    events: Vec<EventWire>,
    event_seq: HashMap<String, i64>,
    /// RR-8: the persisted high-water marks of the process sessions (None = in memory only).
    log: Option<MarkFile>,
}

/// Blocking hook calls waiting for the orchestrator's answer, by call id.
type Pending = Arc<Mutex<HashMap<String, HookCall>>>;

/// One open loopback forward: its tunnelled connections and the flag that stops its listener.
struct Tunnel {
    conns: Arc<Mutex<HashMap<u64, std::net::TcpStream>>>,
    stop: Arc<std::sync::atomic::AtomicBool>,
    addr: std::net::SocketAddr,
}

/// RR-6 the runner owner's policy for credentials an orchestrator lends with a start. Both are read per start, so a
/// setting change takes effect live.
#[derive(Clone)]
pub struct LendPolicy {
    /// `runner.acceptCredentials` (default true): false drops every lent credential.
    pub accept: Arc<dyn Fn() -> bool + Send + Sync>,
    /// True when this runner's owner set `harness.<id>.authMode=subscription-login`: the runner keeps its own sign-in over
    /// a lent key of that harness.
    pub keeps_own_login: Arc<dyn Fn(&str) -> bool + Send + Sync>,
}

impl Default for LendPolicy {
    fn default() -> Self {
        LendPolicy { accept: Arc::new(|| true), keeps_own_login: Arc::new(|_| false) }
    }
}

pub struct ProcessBackend {
    lend: LendPolicy,
    tunnels: Mutex<HashMap<String, Tunnel>>,
    runner: Arc<dyn Runner>,
    /// Open hook streams (and tail streams): the task that owns the endpoint (or the tail) and the calls it waits on.
    streams: Mutex<HashMap<String, (tokio::task::JoinHandle<()>, Pending)>>,
    rt: tokio::runtime::Runtime,
    shared: Arc<(Mutex<Shared>, Condvar)>,
}

const B64: base64::engine::GeneralPurpose = base64::engine::general_purpose::STANDARD;
/// The id prefix of a link http stream (never a runner process id).
const HTTP_STREAM_PREFIX: &str = "http-";
/// The id prefixes of a hook stream and a tail stream (never a runner process id).
const HOOK_STREAM_PREFIX: &str = "hook-";
const TAIL_STREAM_PREFIX: &str = "tail-";
const TUNNEL_STREAM_PREFIX: &str = "tun-";

fn is_link_stream(id: &str) -> bool {
    [HTTP_STREAM_PREFIX, HOOK_STREAM_PREFIX, TAIL_STREAM_PREFIX, TUNNEL_STREAM_PREFIX].iter().any(|p| id.starts_with(p))
}

impl ProcessBackend {
    pub fn new(runner: Arc<dyn Runner>) -> Self {
        let rt = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().thread_name("runner-link-proc").build().expect("runtime");
        ProcessBackend { lend: LendPolicy::default(), tunnels: Mutex::new(HashMap::new()), runner, streams: Mutex::new(HashMap::new()), rt, shared: Arc::new((Mutex::new(Shared::default()), Condvar::new())) }
    }

    /// RR-8: keeps each process session's stream high-water mark (and exit) in `<dir>/runner-sessions.json`. A session the
    /// previous run left is restored: a process that did not survive the restart ends with its reason (one stderr frame and
    /// an exit frame), numbered ABOVE any cursor the orchestrator can hold, so it takes them as new frames and nothing is
    /// dropped as "already taken" or repeated; the session stays known to this runner.
    pub fn with_state_dir(self, dir: &std::path::Path) -> Self {
        let mut log = MarkFile::open(dir, "runner-sessions.json");
        {
            let mut s = self.shared.0.lock().unwrap();
            for (sid, mark) in log.all() {
                let mut sess = Session { last_seq: mark.seq, ..Default::default() };
                let code = match mark.exited {
                    // The exit frame was the last one, at `mark.seq`: it is rebuilt at the SAME sequence, so an orchestrator
                    // that took it is not given it twice, and one that did not still gets it.
                    Some(code) => {
                        sess.last_seq -= 1;
                        code
                    }
                    None => {
                        sess.last_seq += RESTART_GAP;
                        Self::append(&mut sess, &sid, wire::PROC_STDERR, B64.encode("[runner] the runner restarted and the process did not survive it
"));
                        -1
                    }
                };
                let seq = Self::append(&mut sess, &sid, wire::PROC_EXIT, code.to_string());
                sess.exited = Some(code);
                // The restored exit is the new mark, so a second restart does not stack another gap on it.
                log.set(&sid, Mark { seq, exited: Some(code) });
                s.event_seq.insert(sid.clone(), seq);
                s.events.push(EventWire { session_id: sid.clone(), seq, kind: "finished".into(), payload: code.to_string() });
                s.sessions.insert(sid, sess);
            }
            s.log = Some(log);
        }
        self
    }

    /// Sets the runner owner's policy for lent credentials.
    pub fn with_lend_policy(mut self, p: LendPolicy) -> Self {
        self.lend = p;
        self
    }

    fn append(sess: &mut Session, sid: &str, t: &str, data: String) -> i64 {
        sess.last_seq += 1;
        let mut e = Envelope::new(t, data);
        e.session_id = Some(sid.to_string());
        e.sequence = Some(sess.last_seq);
        sess.frames.push(e);
        sess.last_seq
    }

    fn push(shared: &(Mutex<Shared>, Condvar), sid: &str, t: &str, data: String) {
        let (m, cv) = shared;
        let mut s = m.lock().unwrap();
        let sess = s.sessions.entry(sid.to_string()).or_default();
        let seq = Self::append(sess, sid, t, data);
        // RR-8: a process session's mark is written every CHECKPOINT_EVERY frames (and at its exit, below).
        if seq % CHECKPOINT_EVERY == 0 && !is_link_stream(sid) {
            if let Some(log) = s.log.as_mut() {
                log.set(sid, Mark { seq, exited: None });
            }
        }
        cv.notify_all();
    }
}

impl Backend for ProcessBackend {
    fn info(&self) -> RunnerInfo {
        RunnerInfo {
            os: std::env::consts::OS.into(),
            offers: vec![OFFER_PROCESS.into(), OFFER_STAGED_FILES.into(), OFFER_LOOPBACK_HTTP.into(), OFFER_HOOK_CHANNEL.into(), OFFER_PTY_RESIZE.into(), OFFER_LOOPBACK_FORWARD.into()],
            ..Default::default()
        }
    }

    fn health(&self) -> Option<HealthAnswer> {
        let s = self.shared.0.lock().unwrap();
        let running = s.sessions.values().filter(|x| x.exited.is_none()).count() as i32;
        Some(HealthAnswer { state: "ok".into(), running, free_slots: -1, load: None })
    }

    fn start(&self, r: &StartRequest) -> StartAnswer {
        let Some(mut spec) = r.process.clone() else {
            return StartAnswer { refusal: Some("this runner runs processes only (a start with `process`); harness sessions are not offered here yet".into()), ..Default::default() };
        };
        // RR-6: lent credentials go into this process's env, in memory only, when the runner's owner accepts them. They are
        // never logged: the request's Debug names none, and the output below is masked.
        let mut secrets: Vec<String> = Vec::new();
        spec.lent = None;
        if let Some(lent) = r.credentials.clone().and_then(|v| serde_json::from_value::<LentCredentials>(v).ok()).filter(|l| !l.is_empty()) {
            if (self.lend.accept)() {
                lent.apply(&mut spec, &*self.lend.keeps_own_login);
                secrets = lent.secrets();
            }
        }
        let runner = self.runner.clone();
        // Logging only: what is launched (program, argument count and folder; never the env or the arguments, which can hold secrets).
        let launch = format!("{} ({} args) in {}", spec.program, spec.args.len(), spec.cwd.as_deref().unwrap_or("(runner default folder)"));
        let handle = match self.rt.block_on(runner.spawn(spec)) {
            Ok(h) => h,
            Err(e) => {
                eprintln!("[infantry] runner process start FAILED: {launch}: {e}");
                return StartAnswer { refusal: Some(e.to_string()), ..Default::default() };
            }
        };
        let sid = handle.id.0.clone();
        eprintln!("[infantry] runner process {sid} started: {launch}");
        {
            let mut s = self.shared.0.lock().unwrap();
            // A process id this runner already restored keeps its sequence going (ids are unique per boot, so this is a
            // guard, not the usual case).
            let last_seq = s.sessions.get(&sid).map_or(0, |x| x.last_seq);
            s.sessions.insert(sid.clone(), Session { last_seq, ..Default::default() });
            if let Some(log) = s.log.as_mut() {
                log.set(&sid, Mark { seq: last_seq, exited: None });
            }
        }
        let rx = self.rt.block_on(self.runner.events(&handle.id));
        let mut rx = match rx {
            Ok(rx) => rx,
            Err(e) => return StartAnswer { refusal: Some(e.to_string()), ..Default::default() },
        };
        let shared = self.shared.clone();
        let id = sid.clone();
        self.rt.spawn(async move {
            let (mut out_mask, mut err_mask) = (Masker::new(secrets.clone()), Masker::new(secrets));
            // Logging only: the last masked output of the process, written with its exit so a start that dies at once is explained.
            let mut tail: Vec<u8> = Vec::new();
            while let Some(ev) = rx.recv().await {
                match ev {
                    ProcEvent::Output(b) => {
                        let m = out_mask.feed(&b);
                        keep_tail(&mut tail, &m);
                        Self::push(&shared, &id, wire::PROC_OUTPUT, B64.encode(m))
                    }
                    ProcEvent::Stderr(b) => {
                        let m = err_mask.feed(&b);
                        keep_tail(&mut tail, &m);
                        Self::push(&shared, &id, wire::PROC_STDERR, B64.encode(m))
                    }
                    ProcEvent::Exited(code) => {
                        let shown: String = String::from_utf8_lossy(&tail).chars().map(|c| if c.is_control() { ' ' } else { c }).collect();
                        eprintln!("[infantry] runner process {id} exited with code {code}; last output: {}", if shown.trim().is_empty() { "(none)" } else { shown.trim() });
                        for (held, err) in [(out_mask.finish(), false), (err_mask.finish(), true)] {
                            if !held.is_empty() {
                                Self::push(&shared, &id, if err { wire::PROC_STDERR } else { wire::PROC_OUTPUT }, B64.encode(held));
                            }
                        }
                        Self::push(&shared, &id, wire::PROC_EXIT, code.to_string());
                        let mut s = shared.0.lock().unwrap();
                        let mark = s.sessions.get_mut(&id).map(|x| {
                            x.exited = Some(code);
                            x.last_seq
                        });
                        if let (Some(seq), Some(log)) = (mark, s.log.as_mut()) {
                            log.set(&id, Mark { seq, exited: Some(code) });
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
        // RR-8: a session restored after a restart has no process in the runner any more: releasing it is only forgetting it.
        let restored_gone = self.shared.0.lock().unwrap().sessions.get(session_id).is_some_and(|x| x.exited.is_some());
        let ok = is_link_stream(session_id) || self.rt.block_on(self.runner.release(&id)).is_ok() || restored_gone;
        if let Some(t) = self.tunnels.lock().unwrap().remove(session_id) {
            t.stop.store(true, std::sync::atomic::Ordering::SeqCst);
            let _ = std::net::TcpStream::connect_timeout(&t.addr, Duration::from_millis(200));
            for (_, c) in t.conns.lock().unwrap().drain() {
                let _ = c.shutdown(std::net::Shutdown::Both);
            }
        }
        if let Some((task, _)) = self.streams.lock().unwrap().remove(session_id) {
            task.abort();
        }
        let mut s = self.shared.0.lock().unwrap();
        s.sessions.remove(session_id);
        if let Some(log) = s.log.as_mut() {
            log.remove(session_id);
        }
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
            wire::CMD_RESIZE => match r.text.split_once(',').and_then(|(a, b)| Some((a.trim().parse::<u16>().ok()?, b.trim().parse::<u16>().ok()?))) {
                Some((rows, cols)) if rows > 0 && cols > 0 => done(self.rt.block_on(self.runner.resize(&id, Size { rows, cols }))),
                _ => CommandAnswer { kind: "refused".into(), reason: Some("text is not `rows,cols`".into()), ..Default::default() },
            },
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

    fn tunnel_open(&self) -> TunnelOpenAnswer {
        use std::io::Read;
        use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
        let refuse = |e: String| TunnelOpenAnswer { refusal: Some(e), error_type: "io".into(), ..Default::default() };
        let listener = match std::net::TcpListener::bind("127.0.0.1:0") {
            Ok(l) => l,
            Err(e) => return refuse(e.to_string()),
        };
        let addr = match listener.local_addr() {
            Ok(a) => a,
            Err(e) => return refuse(e.to_string()),
        };
        let sid = format!("{TUNNEL_STREAM_PREFIX}{}", uuid::Uuid::new_v4());
        self.shared.0.lock().unwrap().sessions.insert(sid.clone(), Session::default());
        let conns: Arc<Mutex<HashMap<u64, std::net::TcpStream>>> = Arc::default();
        let stop = Arc::new(AtomicBool::new(false));
        self.tunnels.lock().unwrap().insert(sid.clone(), Tunnel { conns: conns.clone(), stop: stop.clone(), addr });
        let (shared, id) = (self.shared.clone(), sid.clone());
        let frame = |conn: u64, data: String| serde_json::to_string(&TunnelFrame { conn, data }).unwrap_or_default();
        let spawned = std::thread::Builder::new().name("runner-link-tunnel".into()).spawn(move || {
            let next = AtomicU64::new(1);
            for stream in listener.incoming() {
                if stop.load(Ordering::SeqCst) {
                    break;
                }
                let Ok(stream) = stream else { continue };
                let conn = next.fetch_add(1, Ordering::SeqCst);
                let Ok(mut reader) = stream.try_clone() else { continue };
                conns.lock().unwrap().insert(conn, stream);
                Self::push(&shared, &id, wire::TUNNEL_CONN, frame(conn, String::new()));
                let (shared, id, conns) = (shared.clone(), id.clone(), conns.clone());
                let _ = std::thread::Builder::new().name("runner-link-tunnel-conn".into()).spawn(move || {
                    let mut buf = vec![0u8; 16 * 1024];
                    loop {
                        match reader.read(&mut buf) {
                            Ok(0) | Err(_) => break,
                            Ok(n) => Self::push(&shared, &id, wire::TUNNEL_DATA, frame(conn, B64.encode(&buf[..n]))),
                        }
                    }
                    conns.lock().unwrap().remove(&conn);
                    Self::push(&shared, &id, wire::TUNNEL_CLOSE, frame(conn, String::new()));
                });
            }
        });
        if let Err(e) = spawned {
            return refuse(e.to_string());
        }
        TunnelOpenAnswer { stream_id: Some(sid), port: addr.port(), ..Default::default() }
    }

    fn tunnel_send(&self, r: &TunnelSendRequest) -> bool {
        use std::io::Write;
        let conns = match self.tunnels.lock().unwrap().get(&r.stream_id) {
            Some(t) => t.conns.clone(),
            None => return false,
        };
        let mut g = conns.lock().unwrap();
        let Some(c) = g.get_mut(&r.conn) else { return false };
        if !r.data.is_empty() {
            match B64.decode(r.data.as_bytes()) {
                Ok(b) if c.write_all(&b).is_ok() => {}
                _ => {
                    let _ = c.shutdown(std::net::Shutdown::Both);
                    return false;
                }
            }
        }
        if r.close {
            let _ = c.shutdown(std::net::Shutdown::Both);
            g.remove(&r.conn);
        }
        true
    }

    fn hook_open(&self) -> HookOpenAnswer {
        let mut ep = match self.rt.block_on(self.runner.hook_channel()) {
            Ok(ep) => ep,
            Err(e) => return HookOpenAnswer { refusal: Some(e.to_string()), error_type: error_type(&e).into(), ..Default::default() },
        };
        let sid = format!("{HOOK_STREAM_PREFIX}{}", uuid::Uuid::new_v4());
        self.shared.0.lock().unwrap().sessions.insert(sid.clone(), Session::default());
        let (url, events_file) = (ep.url.clone(), ep.events_file.clone());
        let pending: Pending = Arc::new(Mutex::new(HashMap::new()));
        let (shared, id, held) = (self.shared.clone(), sid.clone(), pending.clone());
        // The task owns the endpoint: aborting it (remove) drops the endpoint and unregisters the url.
        let task = self.rt.spawn(async move {
            // Move the whole endpoint in (a closure would capture only `calls` and drop the guard, closing the url).
            let mut ep = ep;
            while let Some(call) = ep.calls.recv().await {
                let call_id = uuid::Uuid::new_v4().simple().to_string();
                let data = serde_json::to_string(&HookCallWire { call_id: call_id.clone(), body: call.body.clone() }).unwrap_or_default();
                held.lock().unwrap().insert(call_id, call);
                Self::push(&shared, &id, wire::HOOK_CALL, data);
            }
        });
        self.streams.lock().unwrap().insert(sid.clone(), (task, pending));
        HookOpenAnswer { stream_id: Some(sid), url, events_file, ..Default::default() }
    }

    fn forward_answer(&self, r: &ForwardAnswerRequest) -> bool {
        let call = self.streams.lock().unwrap().get(&r.stream_id).and_then(|(_, p)| p.lock().unwrap().remove(&r.call_id));
        match call {
            Some(c) => {
                c.reply(r.body.clone());
                true
            }
            None => false,
        }
    }

    fn tail_open(&self, r: &TailOpenRequest) -> HttpOpenAnswer {
        let from = match r.from.as_str() {
            "end" => TailFrom::End,
            n => n.parse::<u64>().map(TailFrom::Offset).unwrap_or(TailFrom::Start),
        };
        let mut rx = match self.rt.block_on(self.runner.tail(TailSpec { path: r.path.clone(), from })) {
            Ok(rx) => rx,
            Err(e) => return HttpOpenAnswer { refusal: Some(e.to_string()), error_type: error_type(&e).into(), stream_id: None },
        };
        let sid = format!("{TAIL_STREAM_PREFIX}{}", uuid::Uuid::new_v4());
        self.shared.0.lock().unwrap().sessions.insert(sid.clone(), Session::default());
        let (shared, id) = (self.shared.clone(), sid.clone());
        let task = self.rt.spawn(async move {
            while let Some(line) = rx.recv().await {
                Self::push(&shared, &id, wire::TAIL_LINE, line);
            }
        });
        self.streams.lock().unwrap().insert(sid.clone(), (task, Arc::new(Mutex::new(HashMap::new()))));
        HttpOpenAnswer { stream_id: Some(sid), ..Default::default() }
    }

    /// RR-6: the `credentials` row (does this runner take what an orchestrator lends) and the `git` row: `git ls-remote` of
    /// the project's remote ON this runner, with the git credential a start would lend, so a pass proves a worker's access.
    fn check(&self, r: &CheckRequest) -> Vec<CheckRow> {
        let lent = r.credentials.clone().and_then(|v| serde_json::from_value::<LentCredentials>(v).ok()).filter(|l| !l.is_empty());
        let accepted = (self.lend.accept)();
        let mut rows = Vec::new();
        if lent.is_some() {
            rows.push(if accepted {
                CheckRow { id: "credentials".into(), ok: true, detail: "this runner takes the credentials an orchestrator lends".into(), fix: None }
            } else {
                CheckRow { id: "credentials".into(), ok: false, detail: "this runner drops lent credentials (runner.acceptCredentials is false)".into(), fix: Some("set runner.acceptCredentials to true on the runner, or store the keys on the runner and set runners.credentials to runner".into()) }
            });
        }
        if let Some(remote) = r.git_remote.as_deref().map(str::trim).filter(|g| !g.is_empty()) {
            let mut spec = infantry_runner_api::ProcessSpec::new("git", ["ls-remote", "--exit-code", remote, "HEAD"]);
            spec.env.push(("GIT_TERMINAL_PROMPT".into(), "0".into()));
            let mut secrets = Vec::new();
            if let (Some(l), true) = (&lent, accepted) {
                l.apply(&mut spec, &*self.lend.keeps_own_login);
                secrets = l.secrets();
            }
            let credential = if secrets.is_empty() { "no credential" } else { "the lent git credential" };
            let res = self.rt.block_on(async {
                let h = self.runner.spawn(spec).await.map_err(|e| e.to_string())?;
                let mut rx = self.runner.events(&h.id).await.map_err(|e| e.to_string())?;
                let (mut out, mut code) = (Vec::new(), None);
                let _ = tokio::time::timeout(Duration::from_secs(30), async {
                    while let Some(ev) = rx.recv().await {
                        match ev {
                            ProcEvent::Output(b) | ProcEvent::Stderr(b) => out.extend(b),
                            ProcEvent::Exited(c) => {
                                code = Some(c);
                                break;
                            }
                            _ => {}
                        }
                    }
                })
                .await;
                let _ = self.runner.release(&h.id).await;
                Ok::<_, String>((code, out))
            });
            let mut m = Masker::new(secrets);
            let mut shown = |b: &[u8]| {
                let mut v = m.feed(b);
                v.extend(m.finish());
                String::from_utf8_lossy(&v).lines().next().unwrap_or("").chars().take(200).collect::<String>()
            };
            rows.push(match res {
                Ok((Some(0), _)) => CheckRow { id: "git".into(), ok: true, detail: format!("git ls-remote on the runner reached the remote with {credential}"), fix: None },
                Ok((Some(c), out)) => CheckRow { id: "git".into(), ok: false, detail: format!("git ls-remote on the runner failed ({c}) with {credential}: {}", shown(&out)), fix: Some("set runners.gitSecret to a vault secret holding a token that can read the repository".into()) },
                Ok((None, _)) => CheckRow { id: "git".into(), ok: false, detail: "git ls-remote on the runner did not finish in time".into(), fix: None },
                Err(e) => CheckRow { id: "git".into(), ok: false, detail: format!("git could not run on the runner: {e}"), fix: Some("install git on the runner".into()) },
            });
        }
        rows
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

/// Logging only: keeps the last 2 KiB of a process's masked output.
fn keep_tail(tail: &mut Vec<u8>, b: &[u8]) {
    tail.extend_from_slice(b);
    if tail.len() > 2048 {
        let cut = tail.len() - 2048;
        tail.drain(..cut);
    }
}
