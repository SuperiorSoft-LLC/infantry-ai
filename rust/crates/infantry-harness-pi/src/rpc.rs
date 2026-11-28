// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! pi's persistent mode (`--mode rpc`) as a [`Translator`]: one process per session, commands as JSON lines on stdin,
//! the agent's event records (the same ones `--mode json` prints) and command `response`s on stdout.
//!
//! - A turn: `{"id","type":"prompt","message"}` then `get_state` (RPC mode prints no `session` header; `get_state`'s
//!   `sessionId` is the resume token).
//! - End of turn: `agent_settled` (NOT `agent_end`: retries and compaction can follow it). The verdict is
//!   [`EventStream`]'s over the turn's lines: the LAST assistant message's `stopReason` decides (`error` / `aborted` fail;
//!   pi exits 0 on a failed run). A `response/prompt` with `success:false` fails at once; `disposition:"handled"` (no run
//!   started, so no `agent_settled` comes) completes at once.
//! - Interrupt: `{"type":"abort"}`; the turn ends on the `agent_settled {"aborted":true}` it produces.
//! - A dialog `extension_ui_request` (select / confirm / input / editor) blocks until answered: an approval confirm from
//!   `infantry-approval.ts` (title `infantry-approval:<tool>`) goes to the host as [`HarnessEvent::ApprovalRequested`];
//!   any other dialog is answered cancelled (no one is at a TUI).
//! - Compaction ([`RpcTranslator::with_context_limit`], best effort): a turn that settles cleanly is held for
//!   `get_session_stats`; when `contextUsage.tokens` is over the limit, `compact` is sent and the turn ends on its
//!   answer, so the next turn starts compacted. pi's own automatic compaction is reported as a warning.

use infantry_harness::{HarnessEvent, Outbound, Translator, TranslatorInput, TurnEnd, Usage};
use serde_json::{json, Value};
use std::collections::HashSet;

pub const APPROVAL_MARKER: &str = "infantry-approval:";
const DIALOG_METHODS: [&str; 4] = ["select", "confirm", "input", "editor"];

fn s<'a>(v: &'a Value, key: &str) -> Option<&'a str> {
    v.get(key).and_then(Value::as_str)
}

fn is_true(v: &Value, key: &str) -> bool {
    v.get(key).and_then(Value::as_bool) == Some(true)
}

fn is_false(v: &Value, key: &str) -> bool {
    v.get(key).and_then(Value::as_bool) == Some(false)
}

/// Reads pi's agent event records (docs/json.md): text and thinking deltas, tool calls and results, usage summed over the
/// run's assistant messages, and the run's verdict. One per turn (or per `--mode json` run).
#[derive(Debug, Default)]
pub struct EventStream {
    session_announced: bool,
    settled: bool,
    aborted: bool,
    last_failure: Option<String>,
    last_stop: Option<String>,
    retry_final_error: Option<String>,
    assistant_messages: u32,
    input: u64,
    cache_read: u64,
    cache_write: u64,
    output: u64,
    /// Thinking deltas joined into one reasoning event per block.
    thinking: String,
}

impl EventStream {
    fn flush_thinking(&mut self, out: &mut Vec<HarnessEvent>) {
        if !self.thinking.is_empty() {
            out.push(HarnessEvent::Reasoning(std::mem::take(&mut self.thinking)));
        }
    }

    /// One record in, events out.
    pub fn record(&mut self, v: &Value) -> Vec<HarnessEvent> {
        let mut out = Vec::new();
        let update = v.get("assistantMessageEvent");
        if s(v, "type") == Some("message_update") && update.and_then(|u| s(u, "type")) == Some("thinking_delta") {
            if let Some(d) = update.and_then(|u| s(u, "delta")) {
                self.thinking.push_str(d);
            }
            return out;
        }
        self.flush_thinking(&mut out);
        match s(v, "type") {
            Some("session") => {
                if let Some(id) = s(v, "id").filter(|i| !i.is_empty()) {
                    if !self.session_announced {
                        self.session_announced = true;
                        out.push(HarnessEvent::ProviderSession(id.into()));
                    }
                }
            }
            Some("agent_start") => out.push(HarnessEvent::TurnStarted),
            Some("message_update") => {
                let Some(u) = update else { return out };
                match s(u, "type") {
                    Some("text_delta") => {
                        if let Some(d) = s(u, "delta").filter(|d| !d.is_empty()) {
                            out.push(HarnessEvent::TextDelta(d.into()));
                        }
                    }
                    Some("toolcall_end") => {
                        let call = u.get("toolCall").cloned().unwrap_or(Value::Null);
                        if let Some(name) = s(&call, "name").filter(|n| !n.is_empty()) {
                            let input = call.get("arguments").cloned().unwrap_or(json!({})).to_string();
                            out.push(HarnessEvent::ToolStarted { id: s(&call, "id").unwrap_or("").into(), name: name.into(), input });
                        }
                    }
                    _ => {}
                }
            }
            Some("message_end") => self.message_ended(v),
            Some("tool_execution_end") => {
                if let Some(id) = s(v, "toolCallId").filter(|i| !i.is_empty()) {
                    let is_error = is_true(v, "isError");
                    let output = result_text(v.get("result").and_then(|r| r.get("content")));
                    out.push(HarnessEvent::ToolFinished { id: id.into(), output, exit_code: None, ok: !is_error });
                }
            }
            Some("auto_retry_end") => {
                self.retry_final_error = if is_false(v, "success") { s(v, "finalError").filter(|e| !e.is_empty()).map(String::from) } else { None };
            }
            Some("auto_retry_start") => {
                let msg = s(v, "errorMessage").unwrap_or("provider error");
                out.push(HarnessEvent::Warning(format!("pi: {msg}, retrying")));
            }
            Some("auto_compaction_start") | Some("compaction_start") => {
                out.push(HarnessEvent::Warning("pi is compacting the conversation".into()));
                out.push(HarnessEvent::Compacting(true));
            }
            Some("agent_settled") => {
                self.settled = true;
                self.aborted = is_true(v, "aborted");
                if self.last_failure.is_none() && !self.aborted && self.assistant_messages > 0 {
                    out.push(HarnessEvent::Usage(Usage {
                        input_tokens: self.input + self.cache_read + self.cache_write,
                        cached_input_tokens: self.cache_read,
                        output_tokens: self.output,
                        reasoning_tokens: 0,
                    }));
                }
            }
            _ => {}
        }
        out
    }

    fn message_ended(&mut self, v: &Value) {
        let Some(m) = v.get("message") else { return };
        if s(m, "role") != Some("assistant") {
            return;
        }
        self.assistant_messages += 1;
        if let Some(u) = m.get("usage") {
            let n = |k: &str| u.get(k).and_then(Value::as_f64).unwrap_or(0.0) as u64;
            self.input += n("input");
            self.cache_read += n("cacheRead");
            self.cache_write += n("cacheWrite");
            self.output += n("output");
        }
        let stop = s(m, "stopReason").map(String::from);
        let err = s(m, "errorMessage").filter(|e| !e.trim().is_empty()).map(String::from);
        self.last_failure = match stop.as_deref() {
            Some("error") => Some(err.unwrap_or_else(|| "pi reported an error".into())),
            Some("aborted") => Some(err.unwrap_or_else(|| "pi run was aborted".into())),
            _ => None,
        };
        self.last_stop = stop;
    }

    /// The run's verdict: (end, reason). [exit_code] is the process's (0 while it lives on).
    pub fn verdict(&self, exit_code: i32) -> (TurnEnd, Option<String>) {
        if let Some(f) = &self.last_failure {
            return (TurnEnd::Failed, Some(self.retry_final_error.clone().unwrap_or_else(|| f.clone())));
        }
        if self.aborted {
            return (TurnEnd::Interrupted, Some("pi run was aborted".into()));
        }
        if exit_code != 0 {
            return (TurnEnd::Failed, Some(format!("pi exited with code {exit_code}")));
        }
        if !self.settled {
            return (TurnEnd::Failed, Some("pi ended without reporting that the run settled: the run was cut short".into()));
        }
        (TurnEnd::Completed, (self.last_stop.as_deref() == Some("length")).then(|| "length".into()))
    }
}

/// A tool result's content is a list of blocks; only the text blocks are output.
fn result_text(content: Option<&Value>) -> String {
    match content {
        Some(Value::String(t)) => t.clone(),
        Some(Value::Array(a)) => {
            a.iter().filter(|b| s(b, "type") == Some("text")).filter_map(|b| s(b, "text")).collect::<Vec<_>>().join("\n")
        }
        _ => String::new(),
    }
}

pub fn prompt_line(id: &str, prompt: &str) -> String {
    json!({"id": id, "type": "prompt", "message": prompt}).to_string()
}

/// HARNESS-GAP G10: a steering message for the running agent (pi rpc `steer`): delivered after the current tool calls,
/// the turn goes on.
pub fn steer_line(id: &str, message: &str) -> String {
    json!({"id": id, "type": "steer", "message": message}).to_string()
}

pub fn command_line(id: &str, kind: &str) -> String {
    json!({"id": id, "type": kind}).to_string()
}

pub fn confirm_line(id: &str, confirmed: bool) -> String {
    json!({"type": "extension_ui_response", "id": id, "confirmed": confirmed}).to_string()
}

pub fn cancel_line(id: &str) -> String {
    json!({"type": "extension_ui_response", "id": id, "cancelled": true}).to_string()
}

struct Turn {
    prompt_id: String,
    stream: EventStream,
    abort_requested: bool,
}

pub struct RpcTranslator {
    ids: u64,
    turn: Option<Turn>,
    /// A settled turn held for the context check, and the command id it waits for.
    held: Option<(Turn, String)>,
    context_limit: Option<u64>,
    known_session: Option<String>,
    asked: HashSet<String>,
    outbound: Vec<Outbound>,
    events: Vec<HarnessEvent>,
    last_stderr: Option<String>,
}

impl Default for RpcTranslator {
    fn default() -> Self {
        Self::new()
    }
}

impl RpcTranslator {
    pub fn new() -> Self {
        RpcTranslator {
            ids: 0,
            turn: None,
            held: None,
            context_limit: None,
            known_session: None,
            asked: HashSet::new(),
            outbound: Vec::new(),
            events: Vec::new(),
            last_stderr: None,
        }
    }

    /// Compacts after a turn that ends with more than [limit] context tokens.
    pub fn with_context_limit(mut self, limit: Option<u64>) -> Self {
        self.context_limit = limit.filter(|l| *l > 0);
        self
    }

    fn next_id(&mut self, kind: &str) -> String {
        self.ids += 1;
        format!("infantry-{kind}-{}", self.ids)
    }

    fn write(&mut self, line: String) {
        self.outbound.push(Outbound::Stdin(format!("{line}\n").into_bytes()));
    }

    fn end(&mut self, end: TurnEnd, reason: Option<String>) {
        if let (TurnEnd::Failed, Some(r)) = (end, reason.as_ref()) {
            self.events.push(HarnessEvent::Error(r.clone()));
        }
        self.events.push(HarnessEvent::TurnEnded { end, reason });
    }

    /// Holds [turn] for the answer to a stats / compact command.
    fn hold(&mut self, turn: Turn, kind: &str) {
        let id = self.next_id(kind);
        self.write(command_line(&id, kind));
        self.held = Some((turn, id));
    }

    fn release(&mut self, detail: Option<String>) {
        if let Some((turn, _)) = self.held.take() {
            if let Some(d) = detail {
                self.events.push(HarnessEvent::Warning(d));
            }
            if turn.abort_requested {
                self.end(TurnEnd::Interrupted, None);
            } else {
                self.end(TurnEnd::Completed, None);
            }
        }
    }

    fn on_held_response(&mut self, r: &Value) {
        let data = r.get("data").cloned().unwrap_or(Value::Null);
        match s(r, "command") {
            Some("get_session_stats") => {
                let tokens = data.get("contextUsage").and_then(|c| c.get("tokens")).and_then(Value::as_f64).map(|t| t as u64);
                let limit = self.context_limit.unwrap_or(u64::MAX);
                let aborted = self.held.as_ref().is_some_and(|(t, _)| t.abort_requested);
                match tokens {
                    Some(t) if !is_false(r, "success") && t > limit && !aborted => {
                        self.events.push(HarnessEvent::Warning(format!("context limit: {t} tokens is over {limit}, compacting")));
                        let (turn, _) = self.held.take().expect("held");
                        self.hold(turn, "compact");
                    }
                    _ => self.release(None),
                }
            }
            Some("compact") => {
                let detail = if is_false(r, "success") {
                    format!("context limit: pi refused the compaction ({})", s(r, "error").unwrap_or("no reason"))
                } else {
                    let n = |k: &str| data.get(k).map(|v| v.to_string()).unwrap_or_else(|| "?".into());
                    format!("context limit: compacted {} -> ~{} tokens", n("tokensBefore"), n("estimatedTokensAfter"))
                };
                self.release(Some(detail));
            }
            _ => self.release(None),
        }
    }

    fn on_response(&mut self, r: &Value) {
        if self.held.as_ref().is_some_and(|(_, id)| s(r, "id") == Some(id.as_str())) {
            return self.on_held_response(r);
        }
        match s(r, "command") {
            Some("get_state") => {
                if let Some(id) = r.get("data").and_then(|d| s(d, "sessionId")).filter(|i| !i.is_empty()) {
                    if self.known_session.as_deref() != Some(id) {
                        self.known_session = Some(id.into());
                        self.events.push(HarnessEvent::ProviderSession(id.into()));
                    }
                }
            }
            Some("prompt") => {
                let Some(turn) = self.turn.as_ref() else { return };
                if s(r, "id").is_some_and(|id| id != turn.prompt_id) {
                    return;
                }
                if is_false(r, "success") {
                    self.turn = None;
                    let why = s(r, "error").filter(|e| !e.is_empty()).unwrap_or("pi refused the prompt").to_string();
                    self.end(TurnEnd::Failed, Some(why));
                } else if r.get("data").and_then(|d| s(d, "disposition")) == Some("handled") {
                    self.turn = None;
                    self.end(TurnEnd::Completed, None);
                }
            }
            _ => {}
        }
    }

    fn on_dialog(&mut self, r: &Value) {
        let method = s(r, "method").unwrap_or("");
        if !DIALOG_METHODS.contains(&method) {
            return; // fire-and-forget (setStatus, notify, ...)
        }
        let Some(id) = s(r, "id") else { return };
        let title = s(r, "title").unwrap_or("");
        if method == "confirm" {
            if let Some(tool) = title.strip_prefix(APPROVAL_MARKER) {
                if self.asked.insert(id.to_string()) {
                    self.events.push(HarnessEvent::ApprovalRequested {
                        id: id.into(),
                        tool: tool.into(),
                        input: s(r, "message").unwrap_or("").into(),
                    });
                }
                return;
            }
        }
        let line = cancel_line(id);
        self.write(line);
    }

    fn line(&mut self, line: &str) {
        let Ok(v) = serde_json::from_str::<Value>(line.trim()) else { return };
        match s(&v, "type") {
            Some("response") => self.on_response(&v),
            Some("extension_ui_request") => self.on_dialog(&v),
            Some("agent_settled") => {
                let Some(mut turn) = self.turn.take() else { return };
                let evs = turn.stream.record(&v);
                self.events.extend(evs);
                let (end, reason) = turn.stream.verdict(0);
                if turn.abort_requested || end == TurnEnd::Interrupted {
                    self.end(TurnEnd::Interrupted, None);
                } else if end == TurnEnd::Failed {
                    self.end(end, reason);
                } else if self.context_limit.is_some() {
                    self.hold(turn, "get_session_stats");
                } else {
                    self.end(end, reason);
                }
            }
            _ => {
                if let Some(turn) = self.turn.as_mut() {
                    let evs = turn.stream.record(&v);
                    self.events.extend(evs);
                }
            }
        }
    }
}

impl Translator for RpcTranslator {
    fn feed(&mut self, input: TranslatorInput) -> Vec<HarnessEvent> {
        match input {
            TranslatorInput::Stdout(line) => {
                if !line.trim().is_empty() {
                    self.line(&line);
                    self.events.insert(0, HarnessEvent::Stdout(line));
                }
            }
            TranslatorInput::Stderr(line) => {
                if !line.trim().is_empty() {
                    self.last_stderr = Some(line.clone());
                }
                self.events.push(HarnessEvent::Stderr(line));
            }
            TranslatorInput::Exited { code } => {
                let live = self.turn.take().map(|t| t.stream).or_else(|| self.held.take().map(|(t, _)| t.stream));
                if live.is_some() {
                    let mut reason = format!("pi exited (code {code}) before the turn settled");
                    if let Some(e) = &self.last_stderr {
                        reason.push_str(&format!(": {e}"));
                    }
                    self.end(TurnEnd::Failed, Some(reason));
                }
                self.events.push(HarnessEvent::Exited { code: Some(code) });
            }
            _ => {}
        }
        std::mem::take(&mut self.events)
    }

    fn send_turn(&mut self, prompt: &str) {
        let pid = self.next_id("prompt");
        let sid = self.next_id("state");
        self.write(format!("{}\n{}", prompt_line(&pid, prompt), command_line(&sid, "get_state")));
        self.turn = Some(Turn { prompt_id: pid, stream: EventStream::default(), abort_requested: false });
    }

    /// `steer` into the running agent; false (the host keeps it queued) when no turn is live or it is being stopped.
    fn inject(&mut self, text: &str) -> bool {
        if text.trim().is_empty() || self.held.is_some() || !self.turn.as_ref().is_some_and(|t| !t.abort_requested) {
            return false;
        }
        let id = self.next_id("steer");
        self.write(steer_line(&id, text));
        true
    }

    fn interrupt(&mut self) -> bool {
        if let Some((turn, _)) = self.held.as_mut() {
            turn.abort_requested = true; // ends Interrupted on the pending answer
            return true;
        }
        let Some(turn) = self.turn.as_mut() else { return true };
        turn.abort_requested = true;
        let id = self.next_id("abort");
        self.write(command_line(&id, "abort"));
        true
    }

    fn answer(&mut self, id: &str, allow: bool) {
        if self.asked.remove(id) {
            self.write(confirm_line(id, allow));
        }
    }

    fn take_outbound(&mut self) -> Vec<Outbound> {
        std::mem::take(&mut self.outbound)
    }
}

#[cfg(test)]
mod steer_tests {
    use super::*;

    #[test]
    fn inject_steers_a_running_turn_and_is_refused_when_idle() {
        let mut t = RpcTranslator::new();
        assert!(!t.inject("now"), "no turn: the host queues it");
        t.send_turn("work");
        t.take_outbound();
        assert!(t.inject("now"));
        let out = t.take_outbound();
        let Outbound::Stdin(b) = &out[0] else { panic!("{out:?}") };
        let v: Value = serde_json::from_slice(b.trim_ascii_end()).unwrap();
        assert_eq!((v["type"].as_str(), v["message"].as_str()), (Some("steer"), Some("now")));
    }
}
