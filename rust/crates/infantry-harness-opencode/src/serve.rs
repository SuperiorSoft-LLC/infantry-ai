// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The `opencode serve` protocol as a [`Translator`] (one per server process; the driver delivers its HTTP through the
//! runner).
//!
//! - The process prints `listening on <url>`: the translator opens `GET /event` (server-sent events, basic auth). The
//!   server is ready once that stream answers 2xx; a turn sent earlier waits.
//! - A turn: resume the request's `ses_...` id (or `POST /session` for a new one; reported as
//!   [`HarnessEvent::ProviderSession`]), then `POST /session/{ses}/prompt_async` with the model in the body.
//! - The stream's `message.part.updated` parts become text / reasoning / tool / usage events; `session.idle` (after the
//!   session was busy) ends the turn: failed when opencode reported an error or nothing was said, else completed.
//! - `permission.asked`: answered from the [`ApprovalMode`] (`once` / `reject`, never `always`); in `ask` mode it goes to
//!   the host as [`HarnessEvent::ApprovalRequested`]. Infantry's own CLI and MCP tools are always allowed. A question
//!   (`question.asked`) is rejected: no one can answer it.
//! - opencode retries a refusing provider forever: after `max_retries` retries the turn is aborted and failed.
//! - A provider that refuses the session's own history (HTTP 400 `invalid_request`) moves the turn once to a fresh
//!   session, the old first message (the persona) sent again ahead of the prompt.
//! - Interrupt: `POST /session/{ses}/abort`; the turn ends on its answer, the server lives on.

use crate::config;
use infantry_harness::{
    ApprovalMode, HarnessEvent, HttpRequest, Outbound, Translator, TranslatorInput, TurnEnd, Usage,
};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet, VecDeque};

pub const DEFAULT_MAX_RETRIES: u32 = 2;
/// HARNESS-GAP G12: how often `GET /mcp` is asked while the server is up.
pub const MCP_POLL_MS: u64 = 5_000;

pub const RECOVERY_NOTE: &str = "[infantry] Your earlier conversation was lost: the provider refused its history, so this is a fresh session. \
Your first message (above) is repeated so you keep your instructions; it was already handled. Answer this message:";

/// What an in-flight request is for.
#[derive(Debug, Clone)]
enum Call {
    Events,
    CreateSession,
    Prompt,
    Abort,
    GiveUp,
    Reply,
    RecoverMessages,
    RecoverCreate { persona: Option<String> },
    McpStatus,
    McpDisconnect,
    McpConnect,
}

/// `GET /mcp` answers `{name: {status: connected|failed|disabled|needs_auth|...}}`: one (name, status) per server, by name.
pub fn mcp_statuses(body: &str) -> Option<Vec<(String, String)>> {
    let v: Value = serde_json::from_str(body).ok()?;
    let mut out: Vec<(String, String)> = v
        .as_object()?
        .iter()
        .map(|(k, x)| (k.clone(), x.get("status").and_then(Value::as_str).unwrap_or("unknown").to_string()))
        .collect();
    out.sort();
    Some(out)
}

#[derive(Debug, Default)]
struct Turn {
    prompt: String,
    started: bool,
    user_messages: HashSet<String>,
    emitted: HashSet<String>,
    replied: bool,
    saw_busy: bool,
    errored: bool,
    error_message: Option<String>,
    recovery_used: bool,
    recovering: bool,
    abort_requested: bool,
}

pub struct ServeTranslator {
    auth: String,
    approval: ApprovalMode,
    model: String,
    max_retries: u32,
    url: Option<String>,
    ready: bool,
    session: Option<String>,
    resume: Option<String>,
    turn: Option<Turn>,
    queued: bool,
    next_id: u64,
    calls: HashMap<u64, Call>,
    outbound: Vec<Outbound>,
    events: Vec<HarnessEvent>,
    stderr: VecDeque<String>,
    /// Permission ids put to the host, with the tool asked about.
    asked: HashMap<String, String>,
    /// G12: when `/mcp` was last asked, whether an ask is out, and the last state reported.
    mcp_polled_at: u64,
    mcp_asking: bool,
    mcp_last: Option<(String, Vec<(String, String)>)>,
}

fn s<'a>(v: &'a Value, key: &str) -> Option<&'a str> {
    v.get(key).and_then(Value::as_str).filter(|x| !x.is_empty())
}

/// `provider/model` split at the first slash.
pub fn split_model(model: &str) -> Option<(&str, &str)> {
    let i = model.find('/')?;
    (i > 0 && i < model.len() - 1).then(|| (&model[..i], &model[i + 1..]))
}

/// The `prompt_async` body: the text, and the model when it names a provider.
pub fn prompt_body(prompt: &str, model: &str) -> String {
    let mut b = json!({"parts": [{"type": "text", "text": prompt}]});
    if let Some((p, m)) = split_model(model) {
        b["model"] = json!({"providerID": p, "modelID": m});
    }
    b.to_string()
}

/// A provider refusing the request itself (HTTP 400 `invalid_request`): on a resumed session, its stored history.
pub fn is_poisoned_history(error: &Value) -> bool {
    let status = error.get("data").and_then(|d| d.get("statusCode")).and_then(|c| c.as_u64().or_else(|| c.as_str()?.parse().ok()));
    let text = error.to_string();
    let is400 = status == Some(400)
        || (status.is_none() && text.split(|c: char| !c.is_ascii_digit()).any(|w| w == "400"));
    is400 && text.to_ascii_lowercase().contains("invalid_request")
}

/// The text of the first user message in a `GET /session/{id}/message` answer.
pub fn first_user_text(messages: &str) -> Option<String> {
    let list: Value = serde_json::from_str(messages).ok()?;
    let first = list.as_array()?.iter().find(|m| m.get("info").and_then(|i| i.get("role")).and_then(Value::as_str) == Some("user"))?;
    let text: Vec<&str> = first
        .get("parts")?
        .as_array()?
        .iter()
        .filter(|p| s(p, "type") == Some("text") && p.get("synthetic").and_then(Value::as_bool) != Some(true))
        .filter_map(|p| s(p, "text"))
        .collect();
    let joined = text.join("\n");
    (!joined.trim().is_empty()).then_some(joined)
}

/// The fresh session's first prompt: the old first turn (persona) again, a line saying why, then [prompt].
pub fn recovery_prompt(persona: Option<&str>, prompt: &str) -> String {
    match persona.map(str::trim).filter(|p| !p.is_empty() && *p != prompt.trim()) {
        Some(first) => format!("{first}\n\n---\n\n{RECOVERY_NOTE}\n\n{prompt}"),
        None => prompt.to_string(),
    }
}

/// The "no reply" reason: an idle with nothing said is not a success.
pub fn no_reply(model: &str, stderr: &[String]) -> String {
    let tail: Vec<&str> = stderr.iter().map(String::as_str).filter(|l| !l.trim().is_empty()).collect();
    let mut r = format!("no reply from {model}: the turn ended with no assistant output");
    if !tail.is_empty() {
        r.push_str(&format!(" (opencode stderr: {})", tail.join(" | ")));
    }
    r
}

fn permission_name(props: &Value) -> String {
    s(props, "permission").or_else(|| s(props, "type")).unwrap_or("unknown").to_string()
}

fn request_id(props: &Value) -> Option<String> {
    s(props, "id").or_else(|| s(props, "requestID")).map(String::from)
}

fn strings(v: Option<&Value>) -> Vec<String> {
    match v {
        Some(Value::Array(a)) => a.iter().filter_map(|x| x.as_str().map(String::from)).collect(),
        Some(Value::String(x)) => vec![x.clone()],
        _ => Vec::new(),
    }
}

fn bash_commands(props: &Value) -> Vec<String> {
    let p = strings(props.get("patterns"));
    if !p.is_empty() {
        return p;
    }
    let p = strings(props.get("pattern"));
    if !p.is_empty() {
        return p;
    }
    props.get("metadata").and_then(|m| s(m, "command")).map(|c| vec![c.to_string()]).unwrap_or_default()
}

fn is_infantry_ask(props: &Value) -> bool {
    let perm = permission_name(props);
    (perm.trim().eq_ignore_ascii_case("bash") && {
        let c = bash_commands(props);
        !c.is_empty() && c.iter().all(|x| config::is_infantry_command(x))
    }) || config::is_infantry_tool(&perm)
}

/// What the call is, for the host's card: the command, else the file, else the patterns.
pub fn approval_input(props: &Value) -> String {
    let meta = props.get("metadata").cloned().unwrap_or(Value::Null);
    if let Some(c) = s(&meta, "command") {
        return c.into();
    }
    if let Some(f) = s(&meta, "filepath").or_else(|| s(&meta, "filePath")) {
        return f.into();
    }
    let p = strings(props.get("patterns"));
    if !p.is_empty() {
        return p.join(", ");
    }
    let p = strings(props.get("pattern"));
    if !p.is_empty() {
        return p.join(", ");
    }
    s(&meta, "url").unwrap_or("").into()
}

impl ServeTranslator {
    pub fn new(password: &str, approval: ApprovalMode, model: String, resume: Option<String>, max_retries: u32) -> Self {
        use base64::Engine;
        let auth = format!("Basic {}", base64::engine::general_purpose::STANDARD.encode(format!("opencode:{password}")));
        ServeTranslator {
            auth,
            approval,
            model,
            max_retries,
            url: None,
            ready: false,
            session: None,
            resume: resume.filter(|r| !r.trim().is_empty()),
            turn: None,
            queued: false,
            next_id: 0,
            calls: HashMap::new(),
            outbound: Vec::new(),
            events: Vec::new(),
            stderr: VecDeque::new(),
            asked: HashMap::new(),
            mcp_polled_at: 0,
            mcp_asking: false,
            mcp_last: None,
        }
    }

    /// The provider session this server works on.
    pub fn session(&self) -> Option<&str> {
        self.session.as_deref()
    }

    fn request(&mut self, method: &str, path: &str, body: Option<String>, call: Call, stream: bool) {
        let Some(url) = self.url.clone() else { return };
        self.next_id += 1;
        let id = self.next_id;
        let mut req = HttpRequest::new(method, format!("{url}{path}")).header("Authorization", self.auth.clone());
        if stream {
            req = req.header("Accept", "text/event-stream");
        }
        if let Some(b) = body {
            req = req.header("Content-Type", "application/json").body(b.into_bytes());
        }
        self.calls.insert(id, call);
        self.outbound.push(Outbound::Http { id, request: req, stream });
    }

    fn post(&mut self, path: &str, body: String, call: Call) {
        self.request("POST", path, Some(body), call, false);
    }

    fn end(&mut self, end: TurnEnd, reason: Option<String>) {
        if self.turn.take().is_some() {
            self.queued = false;
            self.events.push(HarnessEvent::TurnEnded { end, reason });
        }
    }

    fn fail(&mut self, reason: impl Into<String>) {
        let reason = reason.into();
        self.events.push(HarnessEvent::Error(reason.clone()));
        self.end(TurnEnd::Failed, Some(reason));
    }

    /// The server is ready and a turn waits: resume or create the session, then prompt.
    fn begin(&mut self) {
        if !self.queued || !self.ready {
            return;
        }
        self.queued = false;
        if self.session.is_none() {
            if let Some(r) = self.resume.take() {
                self.session = Some(r.clone());
                self.events.push(HarnessEvent::ProviderSession(r));
            }
        }
        match self.session.clone() {
            Some(ses) => self.prompt(&ses),
            None => self.post("/session", "{}".into(), Call::CreateSession),
        }
    }

    fn prompt(&mut self, ses: &str) {
        let Some(prompt) = self.turn.as_ref().map(|t| t.prompt.clone()) else { return };
        let body = prompt_body(&prompt, &self.model);
        self.post(&format!("/session/{ses}/prompt_async"), body, Call::Prompt);
    }

    fn stderr_tail(&self) -> Vec<String> {
        self.stderr.iter().cloned().collect()
    }

    fn on_response(&mut self, call: Call, status: Option<u16>, body: String) {
        let ok = status.is_some_and(|c| (200..300).contains(&c));
        let why = || match status {
            Some(c) => format!("answered {c}: {}", body.chars().take(300).collect::<String>()),
            None => body.clone(),
        };
        match call {
            Call::CreateSession => {
                let id = serde_json::from_str::<Value>(&body).ok().and_then(|v| s(&v, "id").map(String::from));
                match (ok, id) {
                    (true, Some(id)) => {
                        self.session = Some(id.clone());
                        self.events.push(HarnessEvent::ProviderSession(id.clone()));
                        self.prompt(&id);
                    }
                    _ => self.fail(format!("opencode serve refused the turn: POST /session {}", why())),
                }
            }
            Call::Prompt => {
                if !ok {
                    self.fail(format!("opencode serve refused the turn: prompt_async {}", why()));
                } else if let Some(t) = self.turn.as_mut() {
                    if !t.started {
                        t.started = true;
                        self.events.push(HarnessEvent::TurnStarted);
                    }
                }
            }
            Call::Abort => self.end(TurnEnd::Interrupted, None),
            Call::GiveUp | Call::Reply => {
                if !ok {
                    self.events.push(HarnessEvent::Warning(format!("opencode serve: {}", why())));
                }
            }
            Call::RecoverMessages => {
                let persona = if ok { first_user_text(&body) } else { None };
                self.post("/session", "{}".into(), Call::RecoverCreate { persona });
            }
            Call::RecoverCreate { persona } => {
                let id = serde_json::from_str::<Value>(&body).ok().and_then(|v| s(&v, "id").map(String::from));
                match (ok, id) {
                    (true, Some(fresh)) => {
                        self.session = Some(fresh.clone());
                        self.events.push(HarnessEvent::ProviderSession(fresh.clone()));
                        let Some(t) = self.turn.as_mut() else { return };
                        t.saw_busy = false;
                        t.errored = false;
                        t.error_message = None;
                        t.recovering = false;
                        let body = prompt_body(&recovery_prompt(persona.as_deref(), &t.prompt), &self.model);
                        self.post(&format!("/session/{fresh}/prompt_async"), body, Call::Prompt);
                    }
                    _ => self.fail(format!("opencode: the provider refused the history and a fresh session failed too: {}", why())),
                }
            }
            Call::Events => {}
            Call::McpStatus => {
                self.mcp_asking = false;
                if let Some(all) = mcp_statuses(&body).filter(|_| ok) {
                    let state = all.iter().find(|(n, _)| n == crate::config::MCP_SERVER).map(|(_, st)| st.clone()).unwrap_or_else(|| "missing".into());
                    let now = (state, all);
                    if self.mcp_last.as_ref() != Some(&now) {
                        self.events.push(HarnessEvent::McpState { state: now.0.clone(), servers: now.1.clone() });
                        self.mcp_last = Some(now);
                    }
                }
            }
            Call::McpDisconnect => self.post(&format!("/mcp/{}/connect", crate::config::MCP_SERVER), "{}".into(), Call::McpConnect),
            Call::McpConnect => {
                if !ok {
                    self.events.push(HarnessEvent::Warning(format!("opencode could not reconnect its MCP server: {}", why())));
                }
                self.mcp_polled_at = 0; // ask again at once
            }
        }
    }

    fn idle(&mut self) {
        let Some(t) = self.turn.as_ref() else { return };
        if t.errored {
            let reason = t.error_message.clone().unwrap_or_else(|| "opencode reported an error".into());
            self.end(TurnEnd::Failed, Some(reason));
        } else if !t.replied {
            let reason = no_reply(&self.model, &self.stderr_tail());
            self.events.push(HarnessEvent::Error(reason.clone()));
            self.end(TurnEnd::Failed, Some(reason));
        } else {
            self.end(TurnEnd::Completed, None);
        }
    }

    fn reply_permission(&mut self, id: &str, allow: bool, tool: &str, why: &str) {
        let body = json!({"reply": if allow { "once" } else { "reject" }}).to_string();
        self.post(&format!("/permission/{id}/reply"), body, Call::Reply);
        if !allow {
            self.events.push(HarnessEvent::Warning(format!("{tool} was refused: {why}")));
        }
    }

    fn dispatch(&mut self, ev: &Value) {
        let Some(kind) = s(ev, "type") else { return };
        let Some(props) = ev.get("properties") else { return };
        let part = props.get("part");
        let sid = s(props, "sessionID").or_else(|| part.and_then(|p| s(p, "sessionID")));
        if sid.is_none() || sid != self.session.as_deref() {
            return;
        }
        match kind {
            "permission.asked" | "permission.updated" => {
                let Some(id) = request_id(props) else { return };
                let tool = permission_name(props);
                let infantry = is_infantry_ask(props);
                if self.approval == ApprovalMode::Ask && !infantry {
                    if self.asked.insert(id.clone(), tool.clone()).is_none() {
                        self.events.push(HarnessEvent::ApprovalRequested { id, tool, input: approval_input(props) });
                    }
                } else {
                    let allow = infantry || config::allows(self.approval, &tool);
                    let why = format!("tool approval is {:?}", self.approval);
                    self.reply_permission(&id, allow, &tool, &why);
                }
            }
            "question.asked" => {
                if let Some(id) = request_id(props) {
                    self.post(&format!("/question/{id}/reject"), "{}".into(), Call::Reply);
                }
            }
            _ => self.turn_event(kind, props, part),
        }
    }

    fn turn_event(&mut self, kind: &str, props: &Value, part: Option<&Value>) {
        let max_retries = self.max_retries;
        let Some(t) = self.turn.as_mut() else { return };
        if t.recovering || t.abort_requested && kind != "session.idle" {
            return;
        }
        match kind {
            "session.status" => {
                let status = props.get("status").cloned().unwrap_or(Value::Null);
                match s(&status, "type") {
                    Some("retry") => {
                        t.saw_busy = true;
                        let attempt = status.get("attempt").and_then(Value::as_u64).unwrap_or(0);
                        let message = s(&status, "message").unwrap_or("provider error").to_string();
                        self.events.push(HarnessEvent::Warning(format!("opencode: {message}, retry {attempt}")));
                        if max_retries > 0 && attempt > max_retries as u64 {
                            if let Some(ses) = self.session.clone() {
                                self.post(&format!("/session/{ses}/abort"), "{}".into(), Call::GiveUp);
                            }
                            self.fail(format!("opencode: {message} (gave up after {max_retries} retries)"));
                        }
                    }
                    Some("busy") => t.saw_busy = true,
                    Some("idle") if t.saw_busy => self.idle(),
                    _ => {}
                }
            }
            "session.idle" => {
                if t.abort_requested {
                    self.end(TurnEnd::Interrupted, None);
                } else if t.saw_busy {
                    self.idle();
                }
            }
            "message.updated" => {
                let info = props.get("info").cloned().unwrap_or(Value::Null);
                if s(&info, "role") == Some("user") {
                    if let Some(id) = s(&info, "id") {
                        t.user_messages.insert(id.into());
                    }
                }
            }
            "session.error" => {
                let error = props.get("error").cloned().unwrap_or(Value::Null);
                let message = error
                    .get("data")
                    .and_then(|d| s(d, "message"))
                    .or_else(|| s(&error, "message"))
                    .or_else(|| s(&error, "name"))
                    .unwrap_or("opencode reported an error")
                    .to_string();
                if !t.replied && !t.recovery_used && is_poisoned_history(&error) {
                    if let Some(old) = self.session.clone() {
                        t.recovery_used = true;
                        t.recovering = true;
                        self.events.push(HarnessEvent::Warning(format!(
                            "opencode: the provider refused session {old}'s history ({}); starting a fresh provider session",
                            message.chars().take(200).collect::<String>()
                        )));
                        self.request("GET", &format!("/session/{old}/message"), None, Call::RecoverMessages, false);
                        return;
                    }
                }
                t.errored = true;
                t.saw_busy = true;
                t.error_message = Some(message.clone());
                self.events.push(HarnessEvent::Error(message));
            }
            "message.part.updated" => {
                let Some(part) = part else { return };
                if s(part, "messageID").is_some_and(|m| t.user_messages.contains(m)) {
                    return; // the prompt's own echo
                }
                t.saw_busy = true;
                let pid = s(part, "id").unwrap_or("").to_string();
                let ended = part.get("time").and_then(|x| x.get("end")).is_some_and(|e| !e.is_null());
                match s(part, "type") {
                    Some("text") if ended && t.emitted.insert(pid.clone()) => {
                        t.replied = true;
                        let text = s(part, "text").unwrap_or("").to_string();
                        if !text.is_empty() {
                            self.events.push(HarnessEvent::Text(text));
                        }
                    }
                    Some("reasoning") if ended && t.emitted.insert(pid.clone()) => {
                        let text = s(part, "text").unwrap_or("").to_string();
                        if !text.is_empty() {
                            self.events.push(HarnessEvent::Reasoning(text));
                        }
                    }
                    Some("tool") => {
                        let state = part.get("state").cloned().unwrap_or(Value::Null);
                        let call = s(part, "callID").unwrap_or(&pid).to_string();
                        let name = s(part, "tool").or_else(|| s(part, "name")).unwrap_or("tool").to_string();
                        let input = state.get("input").or_else(|| part.get("input")).map(Value::to_string).unwrap_or_else(|| "{}".into());
                        let status = s(&state, "status");
                        let start_key = format!("start:{pid}");
                        let finished = matches!(status, Some("completed") | Some("error"));
                        if matches!(status, Some("running")) || finished {
                            if !t.emitted.contains(&pid) && t.emitted.insert(start_key) {
                                self.events.push(HarnessEvent::ToolStarted { id: call.clone(), name, input });
                            }
                        }
                        if finished && t.emitted.insert(pid) {
                            t.replied = true;
                            let ok = status == Some("completed");
                            let output = s(&state, "output").or_else(|| s(&state, "error")).unwrap_or("").to_string();
                            self.events.push(HarnessEvent::ToolFinished { id: call, output, exit_code: None, ok });
                        }
                    }
                    Some("step-finish") if t.emitted.insert(pid.clone()) => {
                        if let Some(u) = part.get("tokens").and_then(usage) {
                            self.events.push(HarnessEvent::Usage(u));
                        }
                    }
                    _ => {}
                }
            }
            _ => {}
        }
    }
}

/// A `step-finish` part's tokens. `input_tokens` counts every prompt token (fresh + cache read + cache write), as Codex
/// does; a part without both input and output says nothing.
pub fn usage(tokens: &Value) -> Option<Usage> {
    let input = tokens.get("input")?.as_u64()?;
    let output = tokens.get("output")?.as_u64()?;
    let cache = tokens.get("cache");
    let read = cache.and_then(|c| c.get("read")).and_then(Value::as_u64).unwrap_or(0);
    let write = cache.and_then(|c| c.get("write")).and_then(Value::as_u64).unwrap_or(0);
    Some(Usage {
        input_tokens: input + read + write,
        cached_input_tokens: read,
        output_tokens: output,
        reasoning_tokens: tokens.get("reasoning").and_then(Value::as_u64).unwrap_or(0),
    })
}

impl Translator for ServeTranslator {
    fn feed(&mut self, input: TranslatorInput) -> Vec<HarnessEvent> {
        match input {
            TranslatorInput::Stdout(line) => {
                if self.url.is_none() {
                    if let Some(i) = line.find("listening on ") {
                        let url = line[i + 13..].split_whitespace().next().unwrap_or("").trim_end_matches('/').to_string();
                        if url.starts_with("http://") || url.starts_with("https://") {
                            self.url = Some(url);
                            self.request("GET", "/event", None, Call::Events, true);
                        }
                    }
                }
                self.events.push(HarnessEvent::Stdout(line));
            }
            TranslatorInput::Stderr(line) => {
                if !line.trim().is_empty() {
                    self.stderr.push_back(line.clone());
                    while self.stderr.len() > 10 {
                        self.stderr.pop_front();
                    }
                }
                self.events.push(HarnessEvent::Stderr(line));
            }
            TranslatorInput::HttpOpened { id, status } => {
                if matches!(self.calls.get(&id), Some(Call::Events)) {
                    if (200..300).contains(&status) {
                        self.ready = true;
                        self.begin();
                    } else {
                        self.fail(format!("opencode serve refused its event stream ({status})"));
                    }
                }
            }
            TranslatorInput::HttpLine { id, line } => {
                if matches!(self.calls.get(&id), Some(Call::Events)) {
                    if let Some(data) = line.strip_prefix("data:") {
                        if let Ok(ev) = serde_json::from_str::<Value>(data.trim()) {
                            self.dispatch(&ev);
                        }
                    }
                }
            }
            TranslatorInput::HttpClosed { id, error } => {
                if matches!(self.calls.remove(&id), Some(Call::Events)) {
                    self.ready = false;
                    if self.turn.is_some() {
                        let why = error.unwrap_or_else(|| "closed".into());
                        self.fail(format!("opencode serve's event stream ended: {why}"));
                    }
                }
            }
            TranslatorInput::HttpResponse { id, status, body } => {
                if let Some(call) = self.calls.remove(&id) {
                    self.on_response(call, status, body);
                }
            }
            TranslatorInput::Exited { code } => {
                if self.turn.is_some() {
                    let tail = self.stderr_tail();
                    let mut reason = format!("opencode serve exited (code {code})");
                    if !tail.is_empty() {
                        reason.push_str(&format!(": {}", tail.join(" | ")));
                    }
                    self.fail(reason);
                }
                self.ready = false;
                self.events.push(HarnessEvent::Exited { code: Some(code) });
            }
            _ => {}
        }
        std::mem::take(&mut self.events)
    }

    fn send_turn(&mut self, prompt: &str) {
        self.turn = Some(Turn { prompt: prompt.to_string(), ..Default::default() });
        self.queued = true;
        self.begin();
    }

    fn interrupt(&mut self) -> bool {
        let Some(t) = self.turn.as_mut() else { return true };
        match self.session.clone() {
            Some(ses) if self.ready => {
                t.abort_requested = true;
                self.post(&format!("/session/{ses}/abort"), "{}".into(), Call::Abort);
                true
            }
            _ => false, // still booting: the driver kills the server
        }
    }

    fn answer(&mut self, id: &str, allow: bool) {
        if let Some(tool) = self.asked.remove(id) {
            self.reply_permission(id, allow, &tool, "the owner did not approve");
        }
    }

    fn take_outbound(&mut self) -> Vec<Outbound> {
        std::mem::take(&mut self.outbound)
    }

    fn on_timer(&mut self, now_ms: u64) {
        if self.ready && !self.mcp_asking && now_ms.saturating_sub(self.mcp_polled_at) >= MCP_POLL_MS {
            self.mcp_polled_at = now_ms;
            self.mcp_asking = true;
            self.request("GET", "/mcp", None, Call::McpStatus, false);
        }
    }

    fn reconnect_mcp(&mut self) -> bool {
        if !self.ready {
            return false;
        }
        // A disconnect that fails (it was not connected) still goes on to the connect.
        self.post(&format!("/mcp/{}/disconnect", crate::config::MCP_SERVER), "{}".into(), Call::McpDisconnect);
        true
    }
}
