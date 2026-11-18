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
//! `screen`, `screen_text_since`, `resize`, `tail` and `hook_channel` are not carried yet: [`RunnerError::Unsupported`].
//! A Kotlin runner runs harness sessions, not processes, so `spawn` on it is `Unsupported` too (its hello offers nothing).

use crate::client::{CallError, LinkClient};
use crate::wire::{self, *};
use base64::Engine;
use infantry_runner_api::*;
use std::collections::BTreeMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::mpsc;

const B64: base64::engine::GeneralPurpose = base64::engine::general_purpose::STANDARD;

pub struct RemoteRunner {
    id: String,
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
        RemoteRunner { id: id.into(), client: Arc::new(client), hello: Mutex::new(None), pull_wait: Duration::from_secs(10) }
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
            let req = StartRequest { start_id: uuid::Uuid::new_v4().to_string(), process: Some(spec), ..Default::default() };
            let a: StartAnswer = self.blocking(move |c| c.typed(wire::START, &req)).await?;
            match (a.session_id, a.refusal) {
                (Some(id), None) => Ok(ProcHandle { id: ProcId(id), pid: None }),
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

    fn resize<'a>(&'a self, _id: &'a ProcId, _size: Size) -> RunnerFuture<'a, ()> {
        Box::pin(async { Self::unsupported("resize") })
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

    fn screen<'a>(&'a self, _id: &'a ProcId) -> RunnerFuture<'a, ScreenSnapshot> {
        Box::pin(async { Self::unsupported("screen") })
    }

    fn screen_text_since<'a>(&'a self, _id: &'a ProcId, _mark: u64, _max: usize) -> RunnerFuture<'a, String> {
        Box::pin(async { Self::unsupported("screen_text_since") })
    }

    fn tail(&self, _spec: TailSpec) -> RunnerFuture<'_, mpsc::UnboundedReceiver<String>> {
        Box::pin(async { Self::unsupported("tail") })
    }

    fn hook_channel(&self) -> RunnerFuture<'_, HookEndpoint> {
        Box::pin(async { Self::unsupported("hook_channel") })
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
            let req = RemoveRequest { session_id: id.0.clone() };
            let _: RemoveAnswer = self.blocking(move |c| c.typed(wire::REMOVE, &req)).await?;
            Ok(())
        })
    }
}
