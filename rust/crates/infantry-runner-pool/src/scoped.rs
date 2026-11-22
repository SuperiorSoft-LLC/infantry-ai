// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! RR-13 runner-scoped configuration. A runner's own units (harness install, providers, models, harness and provider keys)
//! are reached by NAME and a target runner:
//! - orchestrator: the owner-only `runner_frame {runner, frame, data}` ([`RunnerFrameUnit`]) resolves the runner in the
//!   registry (`local` in-process, a paired one over its link) and answers exactly what that runner's unit answered;
//! - runner: the link op `runner_link_frame` is answered by [`FrameBackend`] through the daemon's own `composed.request`,
//!   ONLY for the frames of [`RUNNER_FRAMES`]; any other frame is refused by name.
//!
//! Secret values travel only inside the request body (over the wss link) and never reach a log, an audit line or an answer text.
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_runner_link::server::{Backend, RunnerInfo};
use infantry_runner_link::wire::*;
use infantry_runner_source::RunnerRegistry;
use serde_json::Value;
use std::sync::Arc;
use std::time::Duration;

pub const RUNNER_FRAME: &str = "runner_frame";

/// The frames a runner answers over the link (declared here, in the plugin; core names none of them).
/// NEVER offered: `secret_reveal`, `secret_backup_export` / `_import`, `secret_render`, board, mail and session frames.
pub const RUNNER_FRAMES: &[&str] = &[
    "harness_install_status",
    "harness_install",
    "providers",
    "models",
    "secret_status",
    "secret_state",
    "secret_set",
    "secret_field_set",
    "secret_field_delete",
    "secret_delete",
    "secret_verify_check",
    "secret_login",
    "runner_log",
];

/// RR-VIS: the runner's own log tail: one line per runner-scope frame it answered (what, ok or refused, how long, a trimmed
/// answer), kept in memory (this many lines, each at most [`LOG_LINE_MAX`] chars). Served by the `runner_log` frame.
pub const LOG_LINES_MAX: usize = 400;
pub const LOG_LINE_MAX: usize = 400;

fn ring() -> &'static std::sync::Mutex<std::collections::VecDeque<String>> {
    static RING: std::sync::OnceLock<std::sync::Mutex<std::collections::VecDeque<String>>> = std::sync::OnceLock::new();
    RING.get_or_init(Default::default)
}

/// Appends one line to this runner's log tail.
pub fn runner_log_push(line: &str) {
    let mut r = ring().lock().unwrap_or_else(|e| e.into_inner());
    let stamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0);
    r.push_back(format!("{stamp} {}", line.chars().take(LOG_LINE_MAX).collect::<String>().replace(['\n', '\r'], " ")));
    while r.len() > LOG_LINES_MAX {
        r.pop_front();
    }
}

pub fn is_offered(frame: &str) -> bool {
    RUNNER_FRAMES.contains(&frame)
}

/// Runs one frame in this daemon as owner: `(frame, data) -> (answer type, answer data)`.
pub type Ask = Arc<dyn Fn(&str, &str) -> Result<(String, String), String> + Send + Sync>;

/// The runner-mode backend: everything of the inner backend, plus `runner_link_frame` through [`Ask`].
pub struct FrameBackend {
    inner: Box<dyn Backend>,
    ask: Ask,
}

impl FrameBackend {
    pub fn new(inner: Box<dyn Backend>, ask: Ask) -> Self {
        FrameBackend { inner, ask }
    }
}

impl Backend for FrameBackend {
    fn info(&self) -> RunnerInfo {
        self.inner.info()
    }
    fn health(&self) -> Option<HealthAnswer> {
        self.inner.health()
    }
    fn start(&self, r: &StartRequest) -> StartAnswer {
        self.inner.start(r)
    }
    fn stop(&self, r: &StopRequest) -> StopAnswer {
        self.inner.stop(r)
    }
    fn remove(&self, id: &str) -> bool {
        self.inner.remove(id)
    }
    fn is_linked(&self, id: &str) -> bool {
        self.inner.is_linked(id)
    }
    fn command(&self, r: &CommandRequest) -> CommandAnswer {
        self.inner.command(r)
    }
    fn events(&self, r: &EventsRequest) -> Vec<EventWire> {
        self.inner.events(r)
    }
    fn check(&self, r: &CheckRequest) -> Vec<CheckRow> {
        self.inner.check(r)
    }
    fn frames_pull(&self, r: &FramesPullRequest, wait: Duration, max: usize) -> Option<FramesPullAnswer> {
        self.inner.frames_pull(r, wait, max)
    }
    fn stage_file(&self, r: &StageFileRequest) -> StageFileAnswer {
        self.inner.stage_file(r)
    }
    fn http_open(&self, r: &HttpOpenRequest) -> HttpOpenAnswer {
        self.inner.http_open(r)
    }
    fn tunnel_open(&self) -> TunnelOpenAnswer {
        self.inner.tunnel_open()
    }
    fn tunnel_send(&self, r: &TunnelSendRequest) -> bool {
        self.inner.tunnel_send(r)
    }
    fn hook_open(&self) -> HookOpenAnswer {
        self.inner.hook_open()
    }
    fn forward_answer(&self, r: &ForwardAnswerRequest) -> bool {
        self.inner.forward_answer(r)
    }
    fn tail_open(&self, r: &TailOpenRequest) -> HttpOpenAnswer {
        self.inner.tail_open(r)
    }
    fn frame(&self, frame: &str, data: &str) -> Result<FrameAnswer, String> {
        if !is_offered(frame) {
            let shown: String = frame.chars().filter(|c| (' '..='~').contains(c)).take(48).collect();
            return Err(format!("frame '{shown}' is not offered over the runner link (offered: {})", RUNNER_FRAMES.join(", ")));
        }
        if frame == "runner_log" {
            let lines: Vec<String> = ring().lock().unwrap_or_else(|e| e.into_inner()).iter().cloned().collect();
            return Ok(FrameAnswer { r#type: "runner_log".into(), data: serde_json::json!({ "lines": lines }).to_string() });
        }
        let started = std::time::Instant::now();
        match (self.ask)(frame, data) {
            Ok((t, d)) => {
                // The answer of an install or login is the step's output; a read frame logs only that it was answered.
                let shown = if frame.contains("install") || frame.contains("login") { d.as_str() } else { "" };
                runner_log_push(&format!("{frame} ok {} ms {shown}", started.elapsed().as_millis()));
                Ok(FrameAnswer { r#type: t, data: d })
            }
            Err(e) => {
                runner_log_push(&format!("{frame} REFUSED {} ms: {e}", started.elapsed().as_millis()));
                Err(e)
            }
        }
    }
}

/// The owner-only `runner_frame {runner?, frame, data?}` frame of the orchestrator.
pub struct RunnerFrameUnit {
    pub registry: Arc<RunnerRegistry>,
}

impl RunnerFrameUnit {
    fn run(&self, runner: &str, frame: &str, data: &str) -> Result<(String, String), String> {
        if !is_offered(frame) {
            return Err(format!("frame '{frame}' is not a runner-scope frame (offered: {})", RUNNER_FRAMES.join(", ")));
        }
        let entry = self.registry.resolve_scope(runner).map_err(|r| r.to_string())?;
        entry.scope.request_typed(frame, data)
    }
}

impl FrameUnit for RunnerFrameUnit {
    fn name(&self) -> &'static str {
        "runner-frame"
    }

    fn frames(&self) -> &'static [&'static str] {
        &[RUNNER_FRAME]
    }

    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        if !ctx.owner {
            return Err(Refusal::new(RUNNER_FRAME, "owner only"));
        }
        let Ok(Value::Object(d)) = serde_json::from_str::<Value>(&env.data) else { return Err(Refusal::new(RUNNER_FRAME, "malformed request")) };
        let text = |k: &str| d.get(k).and_then(Value::as_str).map(str::trim).unwrap_or("");
        let frame = text("frame");
        if frame.is_empty() {
            return Err(Refusal::new(RUNNER_FRAME, "needs a frame"));
        }
        // `data` is a JSON value (object) or already a JSON text.
        let data = match d.get("data") {
            None | Some(Value::Null) => "{}".to_string(),
            Some(Value::String(s)) => s.clone(),
            Some(v) => v.to_string(),
        };
        // A runner that cannot answer is a loud refusal naming it and why, never another runner's answer.
        let (t, body) = self.run(text("runner"), frame, &data).map_err(|why| Refusal::new(RUNNER_FRAME, why))?;
        let mut out = Envelope::new(&t, body);
        out.request_id = env.request_id.clone();
        Ok(vec![out])
    }
}
