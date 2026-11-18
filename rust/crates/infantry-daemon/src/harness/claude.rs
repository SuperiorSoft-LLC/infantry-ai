// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Claude Code through `infantry-runner`: the interactive CLI in a PTY (`pty-screen`), its transcript (`file-tail`) and
//! its hooks (`hook-channel`). One process lives across turns; a session whose process is gone is resumed with the
//! provider session id. Everything vendor-specific stays in this file and in `infantry_runner::claude`.

use super::{Capabilities, EventSink, Harness, HarnessEvent, HarnessSession, OpenRequest, RunStart, Turn, TurnEnd};
use infantry_runner::claude::Claude;
use infantry_runner::harness::{self as rh, Host, LaunchRequest, NoOwner, ToolApproval};
use infantry_runner::hooks::HookServer;
use infantry_runner::session::Session;
use infantry_runner::stream::StreamEvent;
use std::sync::{Arc, Mutex, OnceLock};

pub const NAME: &str = "claude";

/// Inherited variables the child must not see: the daemon's own identity and a launching claude's nested-session guard.
const ENV_REMOVE: [&str; 4] = ["CLAUDECODE", "CLAUDE_CODE_*", "INFANTRY_*", "AGENTHQ_*"];

#[derive(Default)]
pub struct ClaudeHarness {
    hooks: OnceLock<Arc<HookServer>>,
}

impl ClaudeHarness {
    fn hooks(&self) -> Result<Arc<HookServer>, String> {
        if let Some(h) = self.hooks.get() {
            return Ok(h.clone());
        }
        let h = HookServer::start().map_err(|e| format!("hook server: {e}"))?;
        Ok(self.hooks.get_or_init(|| h).clone())
    }
}

impl Harness for ClaudeHarness {
    fn name(&self) -> &str {
        NAME
    }
    fn capabilities(&self) -> Capabilities {
        Capabilities { pty_screen: true, file_tail: true, hook_channel: true, persistent_process: true }
    }
    fn open(&self, req: OpenRequest, sink: EventSink) -> Result<Box<dyn HarnessSession>, String> {
        // Questions and approvals are not routed to a prompt card yet: sessions run with approval = allow all, and a
        // question goes unanswered (a known gap shared with the app's embedded runner).
        let host = Host { state_dir: req.state_dir.join("runner"), hooks: self.hooks()?, owner: Arc::new(NoOwner) };
        Ok(Box::new(ClaudeSession { req, host, sink, live: Arc::new(Mutex::new(None)) }))
    }
}

struct ClaudeSession {
    req: OpenRequest,
    host: Host,
    sink: EventSink,
    live: Arc<Mutex<Option<Session>>>,
}

impl ClaudeSession {
    fn spawn(&mut self, resume: Option<String>) -> Result<(), String> {
        let mut lr = LaunchRequest::new(self.req.cwd.clone());
        lr.model = self.req.model.clone().filter(|m| !m.trim().is_empty() && m != "default");
        lr.effort = self.req.effort.clone();
        lr.approval = ToolApproval::AllowAll;
        lr.resume = resume;
        lr.env_remove = ENV_REMOVE.map(String::from).to_vec();
        let mut s = Session::spawn(&Claude::default(), &lr, &self.host).map_err(|e| format!("could not start claude: {e}"))?;
        let (_keep, dummy) = std::sync::mpsc::channel();
        let rx = std::mem::replace(&mut s.events, dummy);
        *self.live.lock().unwrap() = Some(s);
        let (sink, live) = (self.sink.clone(), self.live.clone());
        std::thread::Builder::new()
            .name("infantry-claude-pump".into())
            .spawn(move || {
                for e in rx.iter() {
                    if !forward(&sink, &live, e) {
                        break;
                    }
                }
            })
            .map_err(|e| e.to_string())?;
        Ok(())
    }
}

/// One runner event as neutral ones; false once the process exited.
fn forward(sink: &EventSink, live: &Mutex<Option<Session>>, e: rh::Event) -> bool {
    match e {
        rh::Event::ProviderSession(id) => sink.emit(HarnessEvent::ProviderSession(id)),
        rh::Event::Output { line, records } => {
            sink.emit(HarnessEvent::Stdout(line));
            for r in records {
                if let StreamEvent::AssistantText { text, .. } = r {
                    sink.emit(HarnessEvent::Text(text));
                }
            }
        }
        rh::Event::TurnEnded { end, reason } => {
            let end = match end {
                rh::TurnEnd::Completed => TurnEnd::Completed,
                // An interrupt the daemon asked for was already reported by it; a stray one is a failed turn.
                rh::TurnEnd::Interrupted | rh::TurnEnd::Failed => TurnEnd::Failed,
            };
            sink.emit(HarnessEvent::TurnEnded {
                end,
                exit_code: if end == TurnEnd::Completed { Some(0) } else { None },
                reason,
                process_alive: true,
            });
        }
        rh::Event::Detail(d) => sink.emit(HarnessEvent::Stderr(format!("[runner] {d}"))),
        rh::Event::TurnStarted { .. } | rh::Event::FirstOutput { .. } => {}
        rh::Event::Exited { code } => {
            live.lock().unwrap().take();
            sink.emit(HarnessEvent::Exited { code: Some(code) });
            return false;
        }
    }
    true
}

impl HarnessSession for ClaudeSession {
    fn send_turn(&mut self, turn: Turn) -> Result<RunStart, String> {
        let running = self.live.lock().unwrap().as_ref().is_some_and(|s| s.exited().is_none());
        if !running {
            self.spawn(turn.resume.clone())?;
        }
        let g = self.live.lock().unwrap();
        let s = g.as_ref().ok_or("the claude process exited before the turn")?;
        s.send_turn(&turn.prompt);
        Ok(RunStart { pid: Some(s.pid()), provider_session_id: Some(s.session_id().to_string()) })
    }

    fn interrupt(&mut self) -> bool {
        self.live.lock().unwrap().as_ref().is_some_and(Session::interrupt)
    }

    fn close(&mut self, force: bool) {
        let Some(s) = self.live.lock().unwrap().take() else { return };
        if force {
            s.kill();
            return;
        }
        // The CLI's own exit; the session is held until it exits (the runner kills the tree after its close grace),
        // so dropping this harness session does not turn a graceful close into a kill.
        s.close();
        let _ = std::thread::Builder::new().name("infantry-claude-close".into()).spawn(move || {
            let grace = std::time::Duration::from_millis(infantry_runner::session::CLOSE_GRACE_MS + 2_000);
            let start = std::time::Instant::now();
            while s.exited().is_none() && start.elapsed() < grace {
                std::thread::sleep(std::time::Duration::from_millis(infantry_runner::session::TICK_MS));
            }
        });
    }

    fn process_alive(&self) -> bool {
        self.live.lock().unwrap().as_ref().is_some_and(|s| s.exited().is_none())
    }
}

impl Drop for ClaudeSession {
    fn drop(&mut self) {
        // Dropping the runner session kills its process tree (its Job Object).
        self.live.lock().unwrap().take();
    }
}
