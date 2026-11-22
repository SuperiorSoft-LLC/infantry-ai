// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! [`RemoteRunner`]: the [`Runner`] trait over a paired runner link, so a harness cannot tell a remote runner from the
//! local one.
//!
//! | Runner call | Link frame |
//! |---|---|
//! | `ping` | `runner_link_hello` + `runner_link_health` |
//! | `spawn` | `runner_link_start` with `process` (only to a runner whose hello offers `process`) |
//! | `write` / `close_input` | `runner_link_command` kind `proc_write` (base64) / `proc_close_input` (`proc_status`: known?) |
//! | `kill` | `runner_link_stop` |
//! | `events` | `runner_link_frames_pull` (held), the `runner_proc_*` frames of that process |
//! | `release` | `runner_link_remove` |
//!
//! RR-5, a pty process (`ProcessSpec::pty`): the orchestrator keeps its own vt100 [`Screen`] fed by the pushed output
//! bytes, so `screen` and `screen_text_since` answer from it and no screen frame crosses the link; `resize` is a
//! `proc_resize` command; `hook_channel` opens a hook endpoint on the runner host (`runner_link_hook_open`) whose blocking
//! calls arrive as `runner_hook_call` frames and are answered with `runner_link_forward_answer`; `tail` follows that
//! endpoint's events file. A runner that does not offer `hook-channel` answers [`RunnerError::Unsupported`].
//! A Kotlin runner runs harness sessions, not processes, so `spawn` on it is `Unsupported` too (its hello offers nothing).

use crate::client::{CallError, LinkClient};
use crate::wire::{self, *};
use base64::Engine;
use infantry_runner_api::*;
use infantry_runner::screen::Screen;
use std::collections::{BTreeMap, HashMap};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::mpsc;

const B64: base64::engine::GeneralPurpose = base64::engine::general_purpose::STANDARD;

/// What a pty process's snapshot keeps of the plain-text tail.
const SCREEN_TAIL_CHARS: usize = 4096;

/// The orchestrator's view of one remote pty process: its screen and the events not yet read.
struct PtyState {
    screen: Mutex<Screen>,
    epoch: std::time::Instant,
    hub: Mutex<PtyHub>,
    stopped: AtomicBool,
}

#[derive(Default)]
struct PtyHub {
    subs: Vec<mpsc::UnboundedSender<ProcEvent>>,
    /// Events before the first subscriber (the first subscriber gets everything since spawn).
    backlog: Vec<ProcEvent>,
    subscribed: bool,
    exit: Option<i32>,
}

impl PtyState {
    fn publish(&self, ev: ProcEvent) {
        let mut h = self.hub.lock().unwrap();
        if let ProcEvent::Exited(c) = &ev {
            h.exit = Some(*c);
        }
        if h.subscribed {
            h.subs.retain(|s| s.send(ev.clone()).is_ok());
        } else {
            h.backlog.push(ev);
        }
        if h.exit.is_some() {
            h.subs.clear();
        }
    }

    fn subscribe(&self) -> mpsc::UnboundedReceiver<ProcEvent> {
        let (tx, rx) = mpsc::unbounded_channel();
        let mut h = self.hub.lock().unwrap();
        if !h.subscribed {
            h.subscribed = true;
            for ev in std::mem::take(&mut h.backlog) {
                let _ = tx.send(ev);
            }
        } else if let Some(c) = h.exit {
            let _ = tx.send(ProcEvent::Exited(c));
        }
        if h.exit.is_none() {
            h.subs.push(tx);
        }
        rx
    }
}

/// Closes a remote hook endpoint when the orchestrator drops its [`HookEndpoint`].
struct RemoteGuard {
    client: Arc<LinkClient>,
    stream: String,
    stop: Arc<AtomicBool>,
}

impl Drop for RemoteGuard {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        let (c, sid) = (self.client.clone(), self.stream.clone());
        let _ = std::thread::Builder::new().name("remote-runner-unhook".into()).spawn(move || {
            let _ = c.typed::<_, RemoveAnswer>(wire::REMOVE, &RemoveRequest { session_id: sid });
        });
    }
}

pub struct RemoteRunner {
    id: String,
    /// pty processes started here, by process id.
    ptys: Mutex<HashMap<String, Arc<PtyState>>>,
    client: Arc<LinkClient>,
    hello: Mutex<Option<HelloAnswer>>,
    /// How long one frames_pull is held on the runner.
    pub pull_wait: Duration,
}

fn disconnected(e: impl std::fmt::Display) -> RunnerError {
    RunnerError::Disconnected(e.to_string())
}

fn from_call(e: CallError) -> RunnerError {
    match e {
        CallError::Link(l) => RunnerError::Disconnected(l.0),
        CallError::Refused(r) if r.contains("was not started by this orchestrator") => RunnerError::NotFound(r),
        CallError::Refused(r) => RunnerError::Unsupported(r),
        CallError::Malformed(m) => RunnerError::Io(m),
    }
}

fn from_command(a: CommandAnswer, id: &ProcId) -> Result<(), RunnerError> {
    let reason = a.reason.unwrap_or_default();
    match (a.kind.as_str(), a.error_type.as_str()) {
        ("done", _) => Ok(()),
        ("unknown", _) => Err(RunnerError::NotFound(id.0.clone())),
        (_, "not_found") => Err(RunnerError::NotFound(reason)),
        (_, "exited") => Err(RunnerError::Exited(reason)),
        (_, "unsupported") => Err(RunnerError::Unsupported(reason)),
        (_, "disconnected") => Err(RunnerError::Disconnected(reason)),
        _ => Err(RunnerError::Io(reason)),
    }
}

impl RemoteRunner {
    /// `id` is the runner's name; `client` holds its url and credential.
    pub fn new(id: impl Into<String>, client: LinkClient) -> Self {
        RemoteRunner { id: id.into(), ptys: Mutex::new(HashMap::new()), client: Arc::new(client), hello: Mutex::new(None), pull_wait: Duration::from_secs(10) }
    }

    async fn blocking<T: Send + 'static>(&self, f: impl FnOnce(&LinkClient) -> Result<T, CallError> + Send + 'static) -> Result<T, RunnerError> {
        let c = self.client.clone();
        tokio::task::spawn_blocking(move || f(&c)).await.map_err(disconnected)?.map_err(from_call)
    }

    async fn hello(&self) -> Result<HelloAnswer, RunnerError> {
        if let Some(h) = self.hello.lock().unwrap().clone() {
            return Ok(h);
        }
        let h = self.blocking(|c| c.hello()).await?;
        *self.hello.lock().unwrap() = Some(h.clone());
        Ok(h)
    }

    async fn command(&self, id: &ProcId, kind: &str, text: String) -> Result<(), RunnerError> {
        let req = CommandRequest { session_id: id.0.clone(), kind: kind.into(), text, ..Default::default() };
        let a: CommandAnswer = self.blocking(move |c| c.typed(wire::COMMAND, &req)).await?;
        from_command(a, id)
    }

    /// The state of a pty process started here; any other process (piped, released, unknown) has no screen: `Unsupported`.
    fn pty(&self, id: &ProcId) -> Result<Arc<PtyState>, RunnerError> {
        self.ptys.lock().unwrap().get(&id.0).cloned().ok_or_else(|| RunnerError::Unsupported(format!("{}: not a pty process of this runner, so it has no screen or terminal size", id.0)))
    }

    /// Reads a pty process's pushed frames for good: feeds the screen, then hands each event to the subscribers.
    fn pump(&self, sid: &str, size: Size) -> Result<(), RunnerError> {
        let st = Arc::new(PtyState {
            screen: Mutex::new(Screen::new(size.rows, size.cols)),
            epoch: std::time::Instant::now(),
            hub: Mutex::new(PtyHub::default()),
            stopped: AtomicBool::new(false),
        });
        self.ptys.lock().unwrap().insert(sid.to_string(), st.clone());
        let (client, sid, wait) = (self.client.clone(), sid.to_string(), self.pull_wait);
        std::thread::Builder::new()
            .name("remote-runner-pty".into())
            .spawn(move || {
                let mut after = 0i64;
                while !st.stopped.load(Ordering::SeqCst) {
                    let req = FramesPullRequest { resume_from: BTreeMap::from([(sid.clone(), after)]), wait_ms: wait.as_millis() as i64, max: 0 };
                    let Ok(a) = client.typed_with::<_, FramesPullAnswer>(wire::FRAMES_PULL, &req, wait + Duration::from_secs(15)) else { return };
                    for f in a.frames {
                        after = after.max(f.sequence.unwrap_or(0));
                        match f.r#type.as_str() {
                            wire::PROC_OUTPUT => {
                                if let Ok(b) = B64.decode(f.data.as_bytes()) {
                                    st.screen.lock().unwrap().feed(&b, st.epoch.elapsed().as_millis() as u64);
                                    st.publish(ProcEvent::Output(b));
                                }
                            }
                            wire::PROC_STDERR => {
                                if let Ok(b) = B64.decode(f.data.as_bytes()) {
                                    st.publish(ProcEvent::Stderr(b));
                                }
                            }
                            wire::PROC_EXIT => {
                                st.publish(ProcEvent::Exited(f.data.trim().parse().unwrap_or(-1)));
                                return;
                            }
                            _ => {}
                        }
                    }
                }
            })
            .map_err(disconnected)?;
        Ok(())
    }

    fn unsupported<T>(what: &str) -> Result<T, RunnerError> {
        Err(RunnerError::Unsupported(format!("{what} is not carried over the runner link yet")))
    }
}

impl Runner for RemoteRunner {
    fn id(&self) -> &str {
        &self.id
    }

    fn ping(&self) -> RunnerFuture<'_, Pong> {
        Box::pin(async move {
            let p = self.blocking(|c| c.ping()).await?;
            *self.hello.lock().unwrap() = Some(p.hello.clone());
            Ok(Pong { version: format!("runner-link {}", p.hello.contract), os: p.hello.os, harnesses: p.hello.harnesses.into_iter().map(|h| h.id).collect() })
        })
    }

    fn spawn(&self, spec: ProcessSpec) -> RunnerFuture<'_, ProcHandle> {
        Box::pin(async move {
            let h = self.hello().await?;
            if !h.offers.iter().any(|o| o == OFFER_PROCESS) {
                return Err(RunnerError::Unsupported(format!("runner '{}' runs harness sessions, not processes", h.runner_name)));
            }
            let pty = spec.pty;
            let req = StartRequest { start_id: uuid::Uuid::new_v4().to_string(), process: Some(spec), ..Default::default() };
            let a: StartAnswer = self.blocking(move |c| c.typed(wire::START, &req)).await?;
            match (a.session_id, a.refusal) {
                (Some(id), None) => {
                    if let Some(size) = pty {
                        self.pump(&id, size)?;
                    }
                    Ok(ProcHandle { id: ProcId(id), pid: None })
                }
                (_, Some(r)) => Err(RunnerError::Io(r)),
                (None, None) => Err(RunnerError::Io("the runner started nothing".into())),
            }
        })
    }

    fn write<'a>(&'a self, id: &'a ProcId, data: &'a [u8]) -> RunnerFuture<'a, ()> {
        Box::pin(self.command(id, wire::CMD_WRITE, B64.encode(data)))
    }

    fn close_input<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, ()> {
        Box::pin(self.command(id, wire::CMD_CLOSE_INPUT, String::new()))
    }

    fn resize<'a>(&'a self, id: &'a ProcId, size: Size) -> RunnerFuture<'a, ()> {
        Box::pin(async move {
            let st = self.pty(id)?;
            self.command(id, wire::CMD_RESIZE, format!("{},{}", size.rows, size.cols)).await?;
            st.screen.lock().unwrap().resize(size.rows, size.cols);
            Ok(())
        })
    }

    fn kill<'a>(&'a self, id: &'a ProcId, scope: KillScope) -> RunnerFuture<'a, ()> {
        Box::pin(async move {
            let req = StopRequest { session_id: id.0.clone(), reason: None, force: matches!(scope, KillScope::Tree), ticket_id: None };
            let _: StopAnswer = self.blocking(move |c| c.typed(wire::STOP, &req)).await?;
            Ok(())
        })
    }

    fn events<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, mpsc::UnboundedReceiver<ProcEvent>> {
        Box::pin(async move {
            if let Some(st) = self.ptys.lock().unwrap().get(&id.0).cloned() {
                return Ok(st.subscribe());
            }
            // One pull now: an unknown process is NotFound here, not a silent empty stream.
            let sid = id.0.clone();
            let first = {
                let req = FramesPullRequest { resume_from: BTreeMap::from([(sid.clone(), 0)]), wait_ms: 0, max: 0 };
                let a: FramesPullAnswer = self.blocking(move |c| c.typed(wire::FRAMES_PULL, &req)).await?;
                a
            };
            if first.frames.is_empty() {
                // Nothing yet: ask whether the runner knows the process at all (an unknown one answers kind `unknown`).
                self.command(id, wire::CMD_STATUS, String::new()).await?;
            }
            let (tx, rx) = mpsc::unbounded_channel();
            let client = self.client.clone();
            let wait = self.pull_wait;
            std::thread::Builder::new()
                .name("remote-runner-events".into())
                .spawn(move || {
                    let mut after = 0i64;
                    let mut batch = first.frames;
                    loop {
                        for f in batch.drain(..) {
                            after = after.max(f.sequence.unwrap_or(0));
                            let ev = match f.r#type.as_str() {
                                wire::PROC_OUTPUT => B64.decode(f.data.as_bytes()).ok().map(ProcEvent::Output),
                                wire::PROC_STDERR => B64.decode(f.data.as_bytes()).ok().map(ProcEvent::Stderr),
                                wire::PROC_EXIT => Some(ProcEvent::Exited(f.data.trim().parse().unwrap_or(-1))),
                                _ => None,
                            };
                            let last = matches!(ev, Some(ProcEvent::Exited(_)));
                            if let Some(ev) = ev {
                                if tx.send(ev).is_err() {
                                    return;
                                }
                            }
                            if last {
                                return;
                            }
                        }
                        if tx.is_closed() {
                            return;
                        }
                        let req = FramesPullRequest { resume_from: BTreeMap::from([(sid.clone(), after)]), wait_ms: wait.as_millis() as i64, max: 0 };
                        match client.typed_with::<_, FramesPullAnswer>(wire::FRAMES_PULL, &req, wait + Duration::from_secs(15)) {
                            Ok(a) => batch = a.frames,
                            Err(_) => return, // the link dropped or the process was released: the stream ends
                        }
                    }
                })
                .map_err(disconnected)?;
            Ok(rx)
        })
    }

    fn screen<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, ScreenSnapshot> {
        Box::pin(async move {
            let st = self.pty(id)?;
            let s = st.screen.lock().unwrap();
            let (rows, cols) = s.size();
            Ok(ScreenSnapshot { size: Size { rows, cols }, rows: s.rows(), cursor: s.cursor(), total: s.total(), tail: s.tail_chars(SCREEN_TAIL_CHARS) })
        })
    }

    fn screen_text_since<'a>(&'a self, id: &'a ProcId, mark: u64, max: usize) -> RunnerFuture<'a, String> {
        Box::pin(async move { Ok(self.pty(id)?.screen.lock().unwrap().text_since(mark, max)) })
    }

    fn tail(&self, spec: TailSpec) -> RunnerFuture<'_, mpsc::UnboundedReceiver<String>> {
        Box::pin(async move {
            let h = self.hello().await?;
            if !h.offers.iter().any(|o| o == OFFER_HOOK_CHANNEL) {
                return Self::unsupported("tail");
            }
            let from = match spec.from {
                TailFrom::Start => "start".to_string(),
                TailFrom::End => "end".to_string(),
                TailFrom::Offset(o) => o.to_string(),
                _ => "start".to_string(),
            };
            let open = TailOpenRequest { path: spec.path, from };
            let a: HttpOpenAnswer = self.blocking(move |c| c.typed(wire::TAIL_OPEN, &open)).await?;
            let sid = match (a.stream_id, a.refusal) {
                (Some(id), None) => id,
                (_, why) => {
                    let why = why.unwrap_or_else(|| "the runner opened no tail".into());
                    return Err(if a.error_type == "unsupported" { RunnerError::Unsupported(why) } else { RunnerError::Io(why) });
                }
            };
            let (tx, rx) = mpsc::unbounded_channel();
            let (client, wait) = (self.client.clone(), self.pull_wait);
            std::thread::Builder::new()
                .name("remote-runner-tail".into())
                .spawn(move || {
                    let mut after = 0i64;
                    'pull: while !tx.is_closed() {
                        let req = FramesPullRequest { resume_from: BTreeMap::from([(sid.clone(), after)]), wait_ms: wait.as_millis() as i64, max: 0 };
                        let Ok(a) = client.typed_with::<_, FramesPullAnswer>(wire::FRAMES_PULL, &req, wait + Duration::from_secs(15)) else { break };
                        for f in a.frames {
                            after = after.max(f.sequence.unwrap_or(0));
                            if f.r#type == wire::TAIL_LINE && tx.send(f.data).is_err() {
                                break 'pull;
                            }
                        }
                    }
                    let _ = client.typed::<_, RemoveAnswer>(wire::REMOVE, &RemoveRequest { session_id: sid });
                })
                .map_err(disconnected)?;
            Ok(rx)
        })
    }

    fn hook_channel(&self) -> RunnerFuture<'_, HookEndpoint> {
        Box::pin(async move {
            let h = self.hello().await?;
            if !h.offers.iter().any(|o| o == OFFER_HOOK_CHANNEL) {
                return Self::unsupported("hook_channel");
            }
            let a: HookOpenAnswer = self.blocking(|c| c.typed(wire::HOOK_OPEN, &serde_json::json!({}))).await?;
            let sid = match (a.stream_id, a.refusal) {
                (Some(id), None) => id,
                (_, why) => {
                    let why = why.unwrap_or_else(|| "the runner opened no hook channel".into());
                    return Err(if a.error_type == "unsupported" { RunnerError::Unsupported(why) } else { RunnerError::Io(why) });
                }
            };
            let (calls_tx, calls_rx) = mpsc::unbounded_channel::<HookCall>();
            let stop = Arc::new(AtomicBool::new(false));
            let (client, wait, stop2, sid2) = (self.client.clone(), self.pull_wait, stop.clone(), sid.clone());
            std::thread::Builder::new()
                .name("remote-runner-hooks".into())
                .spawn(move || {
                    let mut after = 0i64;
                    while !stop2.load(Ordering::SeqCst) && !calls_tx.is_closed() {
                        let req = FramesPullRequest { resume_from: BTreeMap::from([(sid2.clone(), after)]), wait_ms: wait.as_millis() as i64, max: 0 };
                        let Ok(a) = client.typed_with::<_, FramesPullAnswer>(wire::FRAMES_PULL, &req, wait + Duration::from_secs(15)) else { return };
                        for f in a.frames {
                            after = after.max(f.sequence.unwrap_or(0));
                            let Some(w) = (f.r#type == wire::HOOK_CALL).then(|| serde_json::from_str::<HookCallWire>(&f.data).ok()).flatten() else { continue };
                            let (otx, orx) = tokio::sync::oneshot::channel();
                            if calls_tx.send(HookCall::new(w.body, otx)).is_err() {
                                return;
                            }
                            // The hook waits on the runner: answer it when the orchestrator has (an unanswered call answers `{}`).
                            let (c, sid, call_id) = (client.clone(), sid2.clone(), w.call_id);
                            let _ = std::thread::Builder::new().name("remote-runner-hook-answer".into()).spawn(move || {
                                let body = orx.blocking_recv().unwrap_or_else(|_| "{}".to_string());
                                let req = ForwardAnswerRequest { stream_id: sid, call_id, body };
                                let _ = c.typed::<_, ForwardAnswerAnswer>(wire::FORWARD_ANSWER, &req);
                            });
                        }
                    }
                })
                .map_err(disconnected)?;
            let token = a.url.rsplit('/').next().unwrap_or_default().to_string();
            let guard = RemoteGuard { client: self.client.clone(), stream: sid, stop };
            Ok(HookEndpoint::new(token, a.url, a.events_file, calls_rx, Box::new(guard)))
        })
    }

    fn http(&self, req: HttpRequest) -> RunnerFuture<'_, mpsc::UnboundedReceiver<HttpEvent>> {
        Box::pin(async move {
            let h = self.hello().await?;
            if !h.offers.iter().any(|o| o == OFFER_LOOPBACK_HTTP) {
                return Self::unsupported("loopback-http");
            }
            let open = HttpOpenRequest { method: req.method, url: req.url, headers: req.headers, body: B64.encode(&req.body), timeout_ms: req.timeout_ms };
            let a: HttpOpenAnswer = self.blocking(move |c| c.typed(wire::HTTP_OPEN, &open)).await?;
            let sid = match (a.stream_id, a.refusal) {
                (Some(id), None) => id,
                (_, why) => {
                    let why = why.unwrap_or_else(|| "the runner opened no stream".into());
                    return Err(if a.error_type == "unsupported" { RunnerError::Unsupported(why) } else { RunnerError::Io(why) });
                }
            };
            let (tx, rx) = mpsc::unbounded_channel();
            let client = self.client.clone();
            let wait = self.pull_wait;
            std::thread::Builder::new()
                .name("remote-runner-http".into())
                .spawn(move || {
                    let mut after = 0i64;
                    'pull: loop {
                        let req = FramesPullRequest { resume_from: BTreeMap::from([(sid.clone(), after)]), wait_ms: wait.as_millis() as i64, max: 0 };
                        let batch = match client.typed_with::<_, FramesPullAnswer>(wire::FRAMES_PULL, &req, wait + Duration::from_secs(15)) {
                            Ok(a) => a.frames,
                            Err(e) => {
                                let _ = tx.send(HttpEvent::Failed(format!("the runner link dropped: {e}")));
                                break;
                            }
                        };
                        for f in batch {
                            after = after.max(f.sequence.unwrap_or(0));
                            let ev = match f.r#type.as_str() {
                                wire::HTTP_HEAD => serde_json::from_str::<HttpHeadWire>(&f.data).ok().map(|h| HttpEvent::Head { status: h.status, headers: h.headers }),
                                wire::HTTP_BODY => B64.decode(f.data.as_bytes()).ok().map(HttpEvent::Body),
                                wire::HTTP_END => Some(HttpEvent::End),
                                wire::HTTP_FAILED => Some(HttpEvent::Failed(f.data)),
                                _ => None,
                            };
                            let last = matches!(ev, Some(HttpEvent::End | HttpEvent::Failed(_)));
                            if let Some(ev) = ev {
                                if tx.send(ev).is_err() {
                                    break 'pull;
                                }
                            }
                            if last {
                                break 'pull;
                            }
                        }
                        if tx.is_closed() {
                            break;
                        }
                    }
                    let rm = RemoveRequest { session_id: sid };
                    let _ = client.typed::<_, RemoveAnswer>(wire::REMOVE, &rm);
                })
                .map_err(disconnected)?;
            Ok(rx)
        })
    }

    fn stage_file<'a>(&'a self, name: &'a str, contents: &'a [u8]) -> RunnerFuture<'a, String> {
        Box::pin(async move {
            let h = self.hello().await?;
            if !h.offers.iter().any(|o| o == OFFER_STAGED_FILES) {
                return Self::unsupported("staged-files");
            }
            let req = StageFileRequest { name: name.to_string(), contents: B64.encode(contents) };
            let a: StageFileAnswer = self.blocking(move |c| c.typed(wire::STAGE_FILE, &req)).await?;
            match (a.path, a.refusal) {
                (Some(p), None) => Ok(p),
                (_, why) => {
                    let why = why.unwrap_or_else(|| "the runner staged nothing".into());
                    Err(if a.error_type == "unsupported" { RunnerError::Unsupported(why) } else { RunnerError::Io(why) })
                }
            }
        })
    }

    fn release<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, ()> {
        Box::pin(async move {
            if let Some(st) = self.ptys.lock().unwrap().remove(&id.0) {
                st.stopped.store(true, Ordering::SeqCst);
            }
            let req = RemoveRequest { session_id: id.0.clone() };
            let _: RemoveAnswer = self.blocking(move |c| c.typed(wire::REMOVE, &req)).await?;
            Ok(())
        })
    }
}
