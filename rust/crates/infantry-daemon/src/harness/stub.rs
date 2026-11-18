// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The scripted provider (`INFANTRY_PROVIDER=stub`): spawns nothing and replays the Kotlin stub's default script
//! (`ScriptedRunner.kt` / `StubScript.default()`), so the conformance suite and UI work run with no provider CLI.
//! Lines are the provider's own (opencode-like) shapes and are read here into neutral events, as a real harness would.

use super::{Capabilities, EventSink, Harness, HarnessEvent, HarnessSession, OpenRequest, RunStart, Turn, TurnEnd};
use serde_json::Value;
use std::sync::{Arc, Mutex};
use std::time::Duration;

pub const NAME: &str = "stub";

/// `StubScript.default()`.
pub const STDOUT: &[&str] = &[
    r#"{"type":"step_start","sessionID":"stub-session-1"}"#,
    r#"{"type":"text","text":"[stub] daemon is running without a provider CLI."}"#,
    r#"{"type":"text","text":"Every frame on this socket is the real protocol; only the text is scripted."}"#,
    r#"{"type":"step_finish"}"#,
];
pub const STDERR: &[&str] = &["[stub] no provider process was started"];
pub const PROVIDER_SESSION: &str = "stub-session-1";

/// Timing of the replay. `line_ms` is the Kotlin stub's 25 ms per line; `start_ms` stands for the start-up a real child
/// has (Kotlin's stub showed ~200 ms before its first line), so an interrupt sent right after the turn starts lands first.
#[derive(Debug, Clone, Copy)]
pub struct Stub {
    pub start_ms: u64,
    pub line_ms: u64,
}

impl Default for Stub {
    fn default() -> Self {
        let env = |k: &str, d: u64| std::env::var(k).ok().and_then(|v| v.trim().parse().ok()).unwrap_or(d);
        Stub { start_ms: env("INFANTRY_STUB_START_MS", 150), line_ms: env("INFANTRY_STUB_LINE_MS", 25) }
    }
}

impl Harness for Stub {
    fn name(&self) -> &str {
        NAME
    }
    fn capabilities(&self) -> Capabilities {
        Capabilities::default()
    }
    fn open(&self, _req: OpenRequest, sink: EventSink) -> Result<Box<dyn HarnessSession>, String> {
        Ok(Box::new(StubSession { cfg: *self, sink, run: None }))
    }
}

/// One replay; `stopped` is checked and events are sent under its lock, so nothing is emitted after an interrupt returns.
struct Replay {
    stopped: Mutex<bool>,
}

struct StubSession {
    cfg: Stub,
    sink: EventSink,
    run: Option<Arc<Replay>>,
}

impl StubSession {
    fn stop(&mut self) -> bool {
        match self.run.take() {
            Some(r) => {
                let mut s = r.stopped.lock().unwrap();
                let was = !*s;
                *s = true;
                was
            }
            None => false,
        }
    }
}

fn emit_line(sink: &EventSink, line: &str) {
    sink.emit(HarnessEvent::Stdout(line.to_string()));
    let Ok(v) = serde_json::from_str::<Value>(line) else { return };
    match v.get("type").and_then(Value::as_str) {
        Some("step_start") => {
            if let Some(id) = v.get("sessionID").and_then(Value::as_str) {
                sink.emit(HarnessEvent::ProviderSession(id.to_string()));
            }
        }
        Some("text") => {
            if let Some(t) = v.get("text").and_then(Value::as_str) {
                sink.emit(HarnessEvent::Text(t.to_string()));
            }
        }
        _ => {}
    }
}

impl HarnessSession for StubSession {
    fn send_turn(&mut self, _turn: Turn) -> Result<RunStart, String> {
        self.stop();
        let replay = Arc::new(Replay { stopped: Mutex::new(false) });
        self.run = Some(replay.clone());
        let (cfg, sink) = (self.cfg, self.sink.clone());
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(cfg.start_ms)).await;
            for line in STDOUT {
                tokio::time::sleep(Duration::from_millis(cfg.line_ms)).await;
                let g = replay.stopped.lock().unwrap();
                if *g {
                    return;
                }
                emit_line(&sink, line);
            }
            let mut g = replay.stopped.lock().unwrap();
            if *g {
                return;
            }
            for l in STDERR {
                sink.emit(HarnessEvent::Stderr(l.to_string()));
            }
            *g = true;
            sink.emit(HarnessEvent::TurnEnded { end: TurnEnd::Completed, exit_code: Some(0), reason: None, process_alive: false });
        });
        Ok(RunStart { pid: None, provider_session_id: Some(PROVIDER_SESSION.into()) })
    }

    fn interrupt(&mut self) -> bool {
        self.stop()
    }

    fn close(&mut self, _force: bool) {
        self.stop();
    }

    fn process_alive(&self) -> bool {
        false
    }
}
