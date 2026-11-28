// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-MIGRATE-2: the PM / worker tool set the daemon answers itself, with the Kotlin tools' names and arguments:
//! the board tools (`infantry_board::tools`, ahead of plugins) and the agent + usage tools below. The agent and usage
//! tools are FALLBACKS: they sit after the plugin tools in the catalog, so a loaded Infantry_Agents / Infantry_Usage
//! daemon half that declares the same name serves it instead.
use crate::sessions::{Daemon, NewOpts};
use infantry_board::Dispatcher;
use infantry_core::bus::EventBus;
use infantry_orchestrator_api::StartOrigin;
use infantry_protocol::session::{SessionRecord, SessionState};
use infantry_scripting::host::{ToolInfo, ToolInvoker, ToolResult};
use infantry_usage::UsageService;
use serde_json::{json, Map, Value};
use std::sync::Arc;

const DESCRIPTORS: &str = include_str!("agent_tools.json");
/// `limits.tools.listLimit` default.
pub const LIST_LIMIT: usize = 50;
/// `limits.tools.sessionTailLines` default and its ceiling.
pub const TAIL_LINES: usize = 5;
pub const TAIL_LINES_MAX: usize = 200;
/// One transcript line is cut at this many characters.
pub const TAIL_LINE_CHARS: usize = 400;
/// `infantry_attention_raise` detail ceiling.
pub const ATTENTION_DETAIL_MAX: usize = 2000;

pub struct AgentTools {
    pub daemon: Arc<Daemon>,
    pub work: Arc<Dispatcher>,
    pub bus: EventBus,
    pub usage: Arc<UsageService>,
    pub mail: Arc<infantry_mail::mail::router::MailRouter>,
    /// The agent registry the `infantry_agent_list|get|enable|disable` tools read.
    pub registry: Arc<super::agent_registry::AgentRegistry>,
    /// Read by `infantry_pod_state`.
    pub pods: Arc<crate::pods::Pods>,
    /// Written by `infantry_attention_raise`.
    pub attention: Arc<infantry_mail::notify::attention::AttentionStore>,
    pub project_dir: Arc<dyn Fn(&str) -> Option<std::path::PathBuf> + Send + Sync>,
    /// WAKE-2: the shared wake gate and settings `infantry_message` with a `kind` is decided by.
    pub wake_gate: Arc<infantry_mail::notify::wake::WakeGate>,
    pub settings: super::settings::SettingsUnit,
    /// NEEDS-INPUT-1: the open question and approval cards `infantry_prompt_answer` answers.
    pub prompts: Arc<infantry_mail::notify::prompts::PendingPrompts>,
    /// MAIL-OWNER-1: `infantry_ask` raises an owner question (a decision mail in the owner's Inbox).
    pub questions: Arc<infantry_mail::notify::questions::OwnerQuestions>,
    pub asker_of: infantry_mail::notify::units::AskerOf,
    /// SCRIPT-TOOLS-MISSING-1: `infantry_notify` posts the owner's notice here (in-app toast + OS presenters).
    pub hub: Arc<infantry_mail::notify::hub::NotificationHub>,
    /// PARITY-AUDIT-1 #15: the tickets `infantry_notify_milestone` marked.
    pub milestones: Arc<infantry_mail::notify::milestones::MilestoneStore>,
}

fn infos(raw: &str) -> Vec<ToolInfo> {
    let v: Vec<Value> = serde_json::from_str(raw).expect("tool descriptors");
    v.into_iter()
        .map(|d| ToolInfo {
            name: d["name"].as_str().unwrap_or_default().to_string(),
            description: d["description"].as_str().unwrap_or_default().to_string(),
            input_schema: d.get("inputSchema").cloned().unwrap_or_else(|| json!({"type": "object"})),
            read_only: d["readOnly"].as_bool().unwrap_or(false),
        })
        .collect()
}

/// The board tools' catalog entries.
pub fn board_catalog() -> Vec<ToolInfo> {
    infos(&serde_json::to_string(&infantry_board::tools::descriptors()).unwrap_or_default())
}

/// AR-6: each board tool's `instructions` paragraph, by tool name (the MCP server instructions carry it).
pub fn board_instructions() -> std::collections::HashMap<String, Value> {
    infantry_board::tools::descriptors()
        .into_iter()
        .filter_map(|d| Some((d.get("name")?.as_str()?.to_string(), d.get("instructions")?.clone())))
        .collect()
}

/// The agent + usage tools' catalog entries.
pub fn agent_catalog() -> Vec<ToolInfo> {
    infos(DESCRIPTORS)
}

/// PARITY-AUDIT-1 #10: the longest name `infantry_event_emit` takes.
pub const EVENT_NAME_MAX: usize = 64;
/// Event families the daemon itself emits: an agent or script may not fake them (the owner may).
pub const RESERVED_EVENT_PREFIXES: &[&str] = &["session.", "ticket.", "mail.", "run.", "mcp.", "modules.", "timer"];

/// An emitted event's name: lower-case words joined by `.`, `_` or `-`.
fn event_name_ok(n: &str) -> bool {
    n.len() <= EVENT_NAME_MAX
        && n.starts_with(|c: char| c.is_ascii_lowercase())
        && n.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || matches!(c, '.' | '_' | '-'))
}

fn s(a: &Map<String, Value>, k: &str) -> Option<String> {
    a.get(k).and_then(|v| match v {
        Value::String(x) => Some(x.trim().to_string()),
        Value::Number(n) => Some(n.to_string()),
        _ => None,
    })
    .filter(|x| !x.is_empty())
}

fn n(a: &Map<String, Value>, k: &str) -> Option<i64> {
    match a.get(k)? {
        Value::Number(x) => x.as_i64(),
        Value::String(x) => x.trim().parse().ok(),
        _ => None,
    }
}

fn flag(a: &Map<String, Value>, k: &str) -> bool {
    matches!(a.get(k), Some(Value::Bool(true))) || matches!(a.get(k), Some(Value::String(x)) if x == "true")
}

fn state_word(st: &SessionState) -> String {
    serde_json::to_value(st).ok().and_then(|v| v.as_str().map(str::to_string)).unwrap_or_default()
}

fn live(r: &SessionRecord) -> bool {
    matches!(r.state, SessionState::Idle | SessionState::Busy | SessionState::Starting | SessionState::WaitingInput)
}

fn pm(inv: &ToolInvoker) -> bool {
    inv.owner || inv.session_id.is_none() || inv.role.as_deref().map(|r| r.eq_ignore_ascii_case("pm")).unwrap_or(false)
}

/// OWNER-SESSION-GUARD-1: an agent or script (anyone but the owner's own client) may not control an owner-owned session.
fn owner_guard(inv: &ToolInvoker, action: &str, r: &SessionRecord) -> Option<String> {
    (!inv.owner_client).then(|| super::scope::owner_session_refusal(action, r)).flatten()
}

fn short(id: &str) -> String {
    infantry_board::tools::short(id)
}

impl AgentTools {
    /// A session by full id or unique prefix.
    fn find(&self, want: &str) -> Result<SessionRecord, String> {
        let want = want.trim();
        if want.is_empty() {
            return Err("sessionId is required".into());
        }
        let all = self.daemon.records();
        if let Some(r) = all.iter().find(|r| r.id == want) {
            return Ok(r.clone());
        }
        let hits: Vec<&SessionRecord> = all.iter().filter(|r| r.id.starts_with(want)).collect();
        match hits.len() {
            1 => Ok(hits[0].clone()),
            0 => Err(format!("no session '{want}'")),
            k => Err(format!("'{want}' matches {k} sessions: give more of the id")),
        }
    }

    fn row(r: &SessionRecord) -> String {
        let mut out = format!("{} {}", r.id, state_word(&r.state));
        if let Some(x) = &r.role {
            out.push_str(&format!(" role={x}"));
        }
        if let Some(x) = &r.agent_id {
            out.push_str(&format!(" agent={x}"));
        }
        out.push_str(&format!(" provider={}", r.provider));
        if let Some(x) = &r.model {
            out.push_str(&format!(" model={x}"));
        }
        if let Some(x) = &r.project_id {
            out.push_str(&format!(" project={x}"));
        }
        if let Some(x) = &r.ticket_id {
            out.push_str(&format!(" ticket={}", short(x)));
        }
        if let Some(x) = &r.held_by {
            out.push_str(&format!(" heldBy={x}"));
        }
        out
    }

    /// The session's live state word for the board's lines.
    pub fn session_state(&self, sid: &str) -> Option<String> {
        let r = self.daemon.record(sid)?;
        let busy = self.daemon.turn_active(sid).unwrap_or(false);
        Some(if busy { "busy".into() } else { state_word(&r.state).to_lowercase() })
    }

    pub fn board_call(&self, name: &str, args: &Map<String, Value>, inv: &ToolInvoker) -> Option<ToolResult> {
        let caller = infantry_board::tools::Caller {
            owner: inv.owner,
            session_id: inv.session_id.clone(),
            agent_id: inv.session_id.as_deref().and_then(|s| self.daemon.record(s)).and_then(|r| r.agent_id),
            role: inv.role.clone(),
            project_id: inv.project_id.clone().or_else(|| inv.session_id.as_deref().and_then(|s| self.daemon.record(s)).and_then(|r| r.project_id)),
        };
        let bus = self.bus.clone();
        let publish = move |e: infantry_protocol::Envelope| {
            bus.publish(e);
        };
        let state = |sid: &str| self.session_state(sid);
        let ctx = infantry_board::tools::Ctx { work: &self.work, publish: &publish, session_state: &state };
        infantry_board::tools::call(&ctx, name, args, &caller).map(|r| match r {
            Ok(t) => ToolResult::ok(t),
            Err(e) => ToolResult::err(format!("{name} {e}")),
        })
    }

    /// One line per agent: `id  name  [disabled]  (contributor/source)`.
    fn agent_line(e: &Value) -> String {
        format!(
            "{}	{}{}	({}/{})",
            e["definition"]["id"].as_str().unwrap_or(""),
            e["definition"]["name"].as_str().unwrap_or(""),
            if e["enabled"].as_bool().unwrap_or(true) { "" } else { "	[disabled]" },
            e["contributor"].as_str().unwrap_or(""),
            e["source"].as_str().unwrap_or("")
        )
    }

    fn caller_project(&self, inv: &ToolInvoker) -> Option<String> {
        inv.project_id.clone().or_else(|| inv.session_id.as_deref().and_then(|s| self.daemon.record(s)).and_then(|r| r.project_id))
    }

    pub fn call(&self, name: &str, a: &Map<String, Value>, inv: &ToolInvoker) -> Option<ToolResult> {
        let r: Result<String, String> = match name {
            "infantry_list_sessions" => (|| {
                let scope = match s(a, "projectId").as_deref() {
                    Some("all") if !pm(inv) => return Err("only the pm or the owner may list every project".to_string()),
                    Some("all") => None,
                    Some(p) => Some(p.to_string()),
                    None if inv.session_id.is_some() => self.caller_project(inv),
                    None => None,
                };
                let mut rows: Vec<SessionRecord> =
                    self.daemon.records().into_iter().filter(|r| scope.as_deref().map(|p| r.project_id.as_deref() == Some(p)).unwrap_or(true)).collect();
                rows.sort_by(|x, y| live(y).cmp(&live(x)).then(y.last_heartbeat.cmp(&x.last_heartbeat)));
                let cap = n(a, "limit").filter(|k| *k > 0).map(|k| k as usize).unwrap_or(LIST_LIMIT);
                let total = rows.len();
                let mut out: Vec<String> = rows.iter().take(cap).map(Self::row).collect();
                if total > cap {
                    out.push(format!("... {} more (limit)", total - cap));
                }
                if out.is_empty() {
                    out.push("no sessions".into());
                }
                Ok(out.join("\n"))
            })(),
            "infantry_get_session" => (|| {
                let r = self.find(&s(a, "sessionId").unwrap_or_default())?;
                let busy = self.daemon.turn_active(&r.id).unwrap_or(false);
                let mut out = vec![
                    Self::row(&r),
                    format!("turn: {}", if busy { "running" } else { "at rest" }),
                    format!("cwd: {}", r.worktree_path.as_deref().unwrap_or(&r.working_dir)),
                ];
                if let Some(x) = &r.provider_session_id {
                    out.push(format!("conversation: {x}"));
                }
                if let Some(x) = &r.last_action {
                    out.push(format!("last action: {x}"));
                }
                if let Some(x) = &r.last_error {
                    out.push(format!("last error: {x}"));
                }
                if !flag(a, "brief") {
                    let lines = n(a, "lines").map(|k| k.clamp(1, TAIL_LINES_MAX as i64) as usize).unwrap_or(TAIL_LINES);
                    if let Some((tail, cut)) = self.daemon.transcript_tail(&r.id, lines) {
                        out.push(format!("--- transcript (last {}{})", tail.len(), if cut { ", older left out" } else { "" }));
                        for f in tail {
                            let ty = f["type"].as_str().unwrap_or("?");
                            let data = f["data"].as_str().map(str::to_string).unwrap_or_else(|| f["data"].to_string());
                            let data: String = data.replace('\n', " ").chars().take(TAIL_LINE_CHARS).collect();
                            out.push(format!("{ty}: {data}"));
                        }
                    }
                }
                Ok(out.join("\n"))
            })(),
            "infantry_session_read" => (|| {
                let r = self.find(&s(a, "sessionId").unwrap_or_default())?;
                let mine = self.caller_project(inv);
                if !inv.owner && mine.is_some() && r.project_id.is_some() && mine != r.project_id {
                    return Err(format!("session '{}' is not a session of this project", r.id));
                }
                let lines = n(a, "lines").map(|k| k.clamp(1, TAIL_LINES_MAX as i64) as usize).unwrap_or(TAIL_LINES);
                let since = n(a, "sinceSeq");
                let (tail, cut) = self.daemon.transcript_tail(&r.id, lines).unwrap_or_default();
                let frames: Vec<Value> = tail
                    .into_iter()
                    .filter(|f| since.map(|k| f["sequence"].as_i64().unwrap_or(i64::MAX) > k).unwrap_or(true))
                    .map(|f| {
                        let data = f["data"].as_str().map(str::to_string).unwrap_or_else(|| f["data"].to_string());
                        json!({"seq": f["sequence"], "type": f["type"], "text": data.chars().take(TAIL_LINE_CHARS).collect::<String>()})
                    })
                    .collect();
                let busy = self.daemon.turn_active(&r.id).unwrap_or(false);
                Ok(json!({
                    "sessionId": r.id, "state": format!("{:?}", r.state).to_lowercase(), "turn": if busy { "running" } else { "at rest" },
                    "lastAction": r.last_action, "lastError": r.last_error, "olderLeftOut": cut, "frames": frames,
                })
                .to_string())
            })(),
            "infantry_progress" => (|| {
                // The caller's own card; the owner (or a script acting for one) may name a session.
                let target = match s(a, "sessionId") {
                    Some(sid) if inv.owner => self.find(&sid)?.id,
                    Some(sid) if Some(&sid) != inv.active_session.as_ref() && Some(&sid) != inv.session_id.as_ref() => {
                        return Err("only your own session's progress (a script names sessionId through its hook)".into())
                    }
                    _ => inv.active_session.clone().or_else(|| inv.session_id.clone()).ok_or("no session to report on (pass sessionId)")?,
                };
                let mut report = Value::Object(a.clone());
                report.as_object_mut().map(|o| o.remove("sessionId"));
                let p = self.daemon.apply_progress(&target, &report)?;
                Ok(crate::sessions::progress::progress_line(p.as_ref()))
            })(),
            "infantry_event_emit" => (|| {
                let ev = s(a, "name").ok_or("name is required")?;
                if !event_name_ok(&ev) {
                    return Err(format!("'{ev}' is not an event name (lower-case words joined by '.', '_' or '-', at most {EVENT_NAME_MAX} chars)"));
                }
                if !inv.owner && RESERVED_EVENT_PREFIXES.iter().any(|p| ev.starts_with(p)) {
                    return Err(format!("'{ev}' is a daemon event ({}* are reserved)", RESERVED_EVENT_PREFIXES.join("*, ")));
                }
                let mut payload = match a.get("payload") {
                    None | Some(Value::Null) => Map::new(),
                    Some(Value::Object(o)) => o.clone(),
                    Some(Value::String(t)) => match serde_json::from_str::<Value>(t) {
                        Ok(Value::Object(o)) => o,
                        _ => return Err("payload must be a JSON object".into()),
                    },
                    Some(_) => return Err("payload must be a JSON object".into()),
                };
                // Project isolation: the event belongs to the caller's project (hooks of other projects never see it).
                if let Some(p) = self.caller_project(inv) {
                    payload.insert("projectId".into(), Value::String(p));
                }
                if let Some(sid) = inv.active_session.clone().or_else(|| inv.session_id.clone()) {
                    payload.entry("emittedBy").or_insert(Value::String(sid));
                }
                let n = self.bus.emit(ev.clone(), Value::Object(payload));
                Ok(format!("emitted {ev} ({n} listener{})", if n == 1 { "" } else { "s" }))
            })(),
            "infantry_spawn" => (|| {
                if let Some(t) = s(a, "ticketId") {
                    if s(a, "task").is_some() {
                        return Err("task is refused with ticketId: the ticket's description is the brief".to_string());
                    }
                    let board = self.work.board();
                    let tk = board.find(&t).map_err(|e| format!("{e:?}"))?;
                    let started = self
                        .work
                        .start(&tk.id, StartOrigin::Move, Some(inv.session_id.as_deref().unwrap_or("owner")))
                        .map_err(|e| format!("{e:?}"))?
                        .ticket;
                    return Ok(format!("started {}: worker session {}", started.id, started.session_id.as_deref().unwrap_or("(queued)")));
                }
                let project = s(a, "projectId").or_else(|| self.caller_project(inv));
                let cwd = project.as_deref().and_then(|p| (self.project_dir)(p)).map(|d| d.display().to_string());
                let opts = NewOpts {
                    cwd,
                    model: s(a, "model"),
                    effort: s(a, "effort").filter(|e| e != "off"),
                    role: s(a, "role"),
                    prompt: s(a, "task"),
                    provider: s(a, "provider"),
                    project_id: project,
                    agent_id: s(a, "agentId"),
                    spawner: inv.session_id.clone(),
                    ..Default::default()
                };
                let id = self.daemon.launch(opts)?;
                let r = self.daemon.record(&id);
                Ok(format!("spawned {}", r.as_ref().map(Self::row).unwrap_or(id)))
            })(),
            "infantry_prompt" => (|| {
                let r = self.find(&s(a, "sessionId").unwrap_or_default())?;
                if let Some(why) = owner_guard(inv, "prompt", &r) {
                    return Err(why);
                }
                let text = a.get("prompt").and_then(|v| v.as_str()).unwrap_or("").to_string();
                if text.trim().is_empty() {
                    return Err("prompt is required".to_string());
                }
                if text.trim_start().starts_with('/') {
                    return Err("slash commands are refused".to_string());
                }
                let busy = self.daemon.turn_active(&r.id).unwrap_or(false);
                self.daemon.deliver(&r.id, &text)?;
                Ok(format!("{} {}", if busy { "queued for" } else { "sent to" }, r.id))
            })(),
            "infantry_kill" => (|| {
                let r = self.find(&s(a, "sessionId").unwrap_or_default())?;
                if let Some(why) = owner_guard(inv, "kill", &r) {
                    return Err(why);
                }
                let mine = inv.session_id.is_some() && r.spawner_session_id == inv.session_id;
                let same_project = self.caller_project(inv).is_none() || self.caller_project(inv) == r.project_id;
                if !(mine || (pm(inv) && same_project)) {
                    return Err("only the pm or the owner (own project), or the session's spawner, may kill it".to_string());
                }
                let by = inv.session_id.clone().unwrap_or_else(|| "owner".into());
                self.daemon.stop_session(&r.id, &format!("infantry_kill by {by}"))?;
                Ok(format!("killed {}", r.id))
            })(),
            "infantry_message" => (|| {
                use infantry_mail::mail::mailbox::MailResult;
                let from = inv.active_session.clone().or_else(|| inv.session_id.clone()).unwrap_or_else(|| if inv.owner { "system".into() } else { String::new() });
                if from.is_empty() {
                    return Err("no sender: call it from a session".to_string());
                }
                let req = infantry_mail::mail::message::MailSendRequest {
                    from: from.clone(),
                    to: s(a, "to").ok_or("to is required")?,
                    subject: s(a, "subject").unwrap_or_default(),
                    body: a.get("body").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                    thread_id: s(a, "threadId"),
                    reply_to: s(a, "replyTo"),
                    checkpoint: None,
                    needs_input: flag(a, "needsInput"),
                    allow_cross_project: flag(a, "allowCrossProject"),
                };
                if req.body.trim().is_empty() {
                    return Err("body is required".to_string());
                }
                // A hook (no session, so no project of its own) mails a role of ITS project, never one with the same role elsewhere.
                let mut req = req;
                if infantry_mail::mail::message::is_automation(&from) && !req.to.contains('@') {
                    if let Some(p) = self.caller_project(inv) {
                        let scoped = format!("{}@{p}", req.to);
                        if self.mail.resolve(&from, &scoped, true).is_ok() {
                            req.to = scoped;
                        }
                    }
                }
                // WAKE-2: a mail with a kind goes through the wake policy: wake kinds are mailed, the rest wait in the inbox.
                if let Some(kind) = s(a, "kind") {
                    let key = s(a, "key").unwrap_or_default();
                    // MAIL-OWNER-2: every notice with a kind is a generic bus event (the owner's alert mail listens).
                    self.bus.emit(infantry_board::owner_mail::MAIL_NOTICE, serde_json::json!({ "kind": kind, "key": key, "from": from, "to": req.to, "subject": req.subject, "body": req.body }));
                    let to = self.mail.resolve(&from, &req.to, true).unwrap_or_else(|_| req.to.clone());
                    let role = self.daemon.record(&to).and_then(|r| r.role);
                    match super::adapters::decide_wake(&self.settings, &self.wake_gate, role.as_deref(), &to, &kind, &key) {
                        infantry_mail::notify::wake::Wake::Now => {}
                        infantry_mail::notify::wake::Wake::Duplicate => return Ok(format!("not sent: {kind} {key} already woke {to}")),
                        infantry_mail::notify::wake::Wake::Inbox => {
                            let _ = self.attention.raise(&infantry_mail::notify::attention::AttentionRaise {
                                kind: "notice".into(),
                                session_id: Some(to.clone()),
                                outcome: format!("inbox:{kind}"),
                                detail: format!("{}
{}", req.subject, req.body),
                                ..Default::default()
                            });
                            return Ok(format!("inbox: {kind} does not wake {to}; kept in the attention list"));
                        }
                    }
                }
                match self.mail.send(&req, infantry_mail::mail::message::is_automation(&from)) {
                    MailResult::Sent(m) => Ok(format!("sent {} to {} (thread {})", m.short_id(), m.to, m.thread_id)),
                    MailResult::Refused(why) => Err(why),
                }
            })(),
            "infantry_inbox" => (|| {
                let me = inv.active_session.clone().or_else(|| inv.session_id.clone()).unwrap_or_else(|| "owner".into());
                let only_unread = flag(a, "unreadOnly");
                let limit = n(a, "limit").filter(|x| *x > 0).map(|x| x as usize).unwrap_or(LIST_LIMIT);
                let mut list = self.mail.mailbox().inbox(&me);
                list.retain(|m| !only_unread || !m.read());
                list.sort_by_key(|m| std::cmp::Reverse(m.sent_at));
                if list.is_empty() {
                    return Ok("no messages".to_string());
                }
                Ok(list
                    .iter()
                    .take(limit)
                    .map(|m| format!("{} | from {} | {} | {:?}{}", m.short_id(), m.from, if m.subject.is_empty() { "(no subject)" } else { &m.subject }, m.state, if m.read() { "" } else { " | unread" }))
                    .collect::<Vec<_>>()
                    .join("\n"))
            })(),
            "infantry_read" => (|| {
                let id = s(a, "id").ok_or("id is required")?;
                let mb = self.mail.mailbox();
                let m = mb.find(&id)?.ok_or_else(|| format!("unknown message '{id}'"))?;
                let me = inv.active_session.clone().or_else(|| inv.session_id.clone());
                if let Some(me) = &me {
                    if &m.to != me && &m.from != me && !inv.owner {
                        return Err(format!("message '{id}' is not yours"));
                    }
                }
                let read = if me.as_deref() == Some(m.to.as_str()) || me.is_none() { mb.mark_read(&m.id).unwrap_or(m) } else { m };
                Ok(format!("id: {}\nfrom: {}\nto: {}\nthread: {}\nsubject: {}\n\n{}", read.id, read.from, read.to, read.thread_id, read.subject, read.body))
            })(),
            "infantry_session_remove" => (|| {
                // Idempotent: an id no session matches is already gone (two park hooks fire on one ticket move).
                let want = s(a, "sessionId").unwrap_or_default();
                let want = want.trim();
                if !want.is_empty() && !self.daemon.records().iter().any(|r| r.id.starts_with(want)) {
                    return Ok(format!("already-removed {want}"));
                }
                let r = self.find(want)?;
                if let Some(why) = owner_guard(inv, "remove", &r) {
                    return Err(why);
                }
                let mine = inv.session_id.is_some() && r.spawner_session_id == inv.session_id;
                let same_project = self.caller_project(inv).is_none() || self.caller_project(inv) == r.project_id;
                if !(mine || inv.owner || (pm(inv) && same_project)) {
                    return Err("only the pm or the owner (own project), or the session's spawner, may remove it".to_string());
                }
                let live = !matches!(r.state, SessionState::Exited | SessionState::Orphaned | SessionState::Failed);
                if live && !flag(a, "force") {
                    return Err(format!("session {} is still running; pass force=true to end and remove it", r.id));
                }
                self.daemon.delete_session(&r.id)?;
                Ok(format!("removed {}", r.id))
            })(),
            "infantry_list_harnesses" => {
                let probe = infantry_harness::LocalProbe;
                let providers = self.daemon.providers();
                let mut out = Vec::new();
                for (i, p) in providers.iter().enumerate() {
                    let Some(h) = self.daemon.registry().neutral(p) else {
                        out.push(format!("{p}{}", if i == 0 { " (default)" } else { "" }));
                        continue;
                    };
                    let problem = h.sign_in_problem(&probe);
                    let models: Vec<String> = h.models(&probe).into_iter().map(|m| m.id).collect();
                    out.push(format!(
                        "{p}{}: {}; models: {}",
                        if i == 0 { " (default)" } else { "" },
                        problem.map(|x| format!("unavailable ({x})")).unwrap_or_else(|| "available".into()),
                        if models.is_empty() { "(the CLI's default)".into() } else { models.join(", ") }
                    ));
                }
                Ok(out.join("\n"))
            }
            "infantry_usage" => {
                let rep = self.usage.report();
                let mut out = Vec::new();
                for m in &rep.meters {
                    let pct = m.percent.map(|p| format!("{p:.0}%")).unwrap_or_else(|| "unknown".into());
                    let mut l = format!("{}: {pct}", m.meter);
                    if let Some(w) = &m.window {
                        l.push_str(&format!(" of {w}"));
                    }
                    if let Some(d) = m.pace_delta {
                        l.push_str(&format!(", {:+.0} pts vs even burn", d));
                    }
                    l.push_str(&format!(", workers {}/{}", m.running, m.allowance.map(|x| x.to_string()).unwrap_or_else(|| "-".into())));
                    if !m.can_start {
                        l.push_str(", starts held");
                    }
                    if let Some(w) = &m.warning {
                        l.push_str(&format!(" ({w})"));
                    }
                    out.push(l);
                }
                if out.is_empty() {
                    out.push("no usage known (no pacing: starts are not held)".into());
                }
                if flag(a, "json") {
                    out.push(serde_json::to_string(&rep).unwrap_or_default());
                }
                Ok(out.join("\n"))
            }
            "infantry_usage_override" => (|| {
                let meter = s(a, "meter").ok_or("meter is required")?;
                let value = s(a, "value").ok_or("value is required (a worker count, none or clear)")?;
                let hours = match a.get("hours") {
                    Some(Value::Number(x)) => x.as_f64(),
                    Some(Value::String(x)) => x.trim().parse().ok(),
                    _ => None,
                };
                let role = if inv.owner || inv.session_id.is_none() { "owner".to_string() } else { inv.role.clone().unwrap_or_default() };
                self.usage.override_allowance(&meter, &value, hours, &role)
            })(),
            "infantry_agent_list" => {
                let all = self.registry.list();
                Ok(if all.is_empty() { "no agents".to_string() } else { all.iter().map(Self::agent_line).collect::<Vec<_>>().join("
") })
            }
            "infantry_agent_get" => (|| {
                let id = s(a, "id").ok_or("id is required")?;
                self.registry.get(&id).map(|v| serde_json::to_string_pretty(&v).unwrap_or_default()).map_err(|_| format!("no agent '{id}'"))
            })(),
            "infantry_agent_enable" | "infantry_agent_disable" => (|| {
                let enable = name.ends_with("enable") && !name.ends_with("disable");
                // The user's override: an agent caller (a session's MCP process) is refused.
                if inv.session_id.is_some() && !inv.owner {
                    return Err(format!("an agent may not {} agents; ask the owner", if enable { "enable" } else { "disable" }));
                }
                let id = s(a, "id").ok_or("id is required")?;
                let e = self.registry.set_enabled(&id, enable).map_err(|_| format!("no agent '{id}'"))?;
                Ok(format!("agent '{}' is {}", e["definition"]["id"].as_str().unwrap_or(&id), if e["enabled"].as_bool().unwrap_or(true) { "enabled" } else { "disabled" }))
            })(),
            "infantry_waiting" => (|| {
                let session = inv.session_id.clone().ok_or("not running inside an Infantry AI session (no INFANTRY_SESSION_ID): nothing flagged")?;
                let op = s(a, "mode").map(|m| m.to_lowercase()).filter(|m| matches!(m.as_str(), "on" | "off" | "get")).ok_or("needs mode (on, off, get)")?;
                let req = infantry_protocol::session::WaitingRequest { op, reason: s(a, "reason"), minutes: n(a, "untilMinutes").map(|m| m.clamp(i32::MIN as i64, i32::MAX as i64) as i32) };
                let w = self.daemon.waiting(&session, &req)?;
                Ok(crate::sessions::waiting_line(w.as_ref(), crate::sessions::now_ms()))
            })(),
            "infantry_prompt_answer" => (|| {
                if !pm(inv) {
                    return Err("only the pm or the owner answers an agent's question".to_string());
                }
                let mine = self.caller_project(inv);
                let open: Vec<_> = self.prompts.snapshot().into_iter().filter(|r| mine.is_none() || self.daemon.record(&r.session_id).and_then(|x| x.project_id) == mine).collect();
                let Some(id) = s(a, "promptId") else {
                    if open.is_empty() {
                        return Ok("no open prompts".into());
                    }
                    return Ok(open.iter().map(|r| format!("{} session {} [{}{}] {}", r.prompt_id, r.session_id.chars().take(8).collect::<String>(), r.kind, r.route.as_deref().map(|x| format!(", route {x}")).unwrap_or_default(), r.question.clone().or_else(|| r.tool.clone()).unwrap_or_default().replace('\n', " ").chars().take(160).collect::<String>())).collect::<Vec<_>>().join("\n"));
                };
                if !open.iter().any(|r| r.prompt_id == id) {
                    return Err(format!("no open prompt '{id}' in your project (infantry_prompt_answer with no promptId lists them)"));
                }
                let answers: Vec<String> = a.get("answers").and_then(Value::as_array).map(|v| v.iter().filter_map(|x| x.as_str().map(str::to_string)).collect()).unwrap_or_default();
                let decision = if flag(a, "escalate") { Some(infantry_mail::notify::prompts::ESCALATE.to_string()) } else { s(a, "decision") };
                let resp = infantry_protocol::prompts::PromptResponse { prompt_id: id.clone(), decision, answers, other_text: s(a, "otherText") };
                let who = if inv.owner { "owner".to_string() } else { format!("pm:{}", inv.session_id.clone().unwrap_or_default().chars().take(8).collect::<String>()) };
                let done = self.prompts.respond(&resp, &who)?;
                Ok(format!("{}: {}", done.prompt_id, done.outcome))
            })(),
            "infantry_session_restart" => (|| {
                let r = self.find(&s(a, "sessionId").unwrap_or_default())?;
                if let Some(why) = owner_guard(inv, "restart", &r) {
                    return Err(why);
                }
                let mine = inv.session_id.is_some() && r.spawner_session_id == inv.session_id;
                let itself = inv.session_id.as_deref() == Some(r.id.as_str());
                let same_project = self.caller_project(inv).is_none() || self.caller_project(inv) == r.project_id;
                if !(mine || itself || (pm(inv) && same_project)) {
                    return Err("only the pm or the owner (own project), the session's spawner, or the session itself may restart it".to_string());
                }
                self.daemon.restart(&r.id, flag(a, "force"))?;
                Ok(format!("restarted {}: its next turn starts a fresh conversation", r.id))
            })(),
            "infantry_ask" => (|| {
                let session = inv.active_session.clone().or_else(|| inv.session_id.clone()).ok_or("infantry_ask is for an agent session (the answer is mailed to it)")?;
                let options: Vec<infantry_protocol::prompts::PromptOption> = a
                    .get("options")
                    .and_then(Value::as_array)
                    .ok_or("needs options (2 to 8 answers)")?
                    .iter()
                    .map(|o| match o {
                        Value::String(l) => infantry_protocol::prompts::PromptOption { label: l.clone(), description: String::new() },
                        o => infantry_protocol::prompts::PromptOption {
                            label: o["label"].as_str().unwrap_or_default().to_string(),
                            description: o["description"].as_str().unwrap_or_default().to_string(),
                        },
                    })
                    .collect();
                let req = infantry_protocol::prompts::QuestionAsk {
                    question: s(a, "question").unwrap_or_default(),
                    options,
                    recommended: s(a, "recommended").unwrap_or_default(),
                    reason: s(a, "reason").unwrap_or_default(),
                    allow_other: true,
                    default_after_min: n(a, "defaultAfterMin").map(|m| m.clamp(i32::MIN as i64, i32::MAX as i64) as i32),
                };
                let q = self.questions.ask(&(self.asker_of)(&session), &req)?;
                Ok(json!({ "questionId": q.id, "thread": infantry_mail::notify::questions::thread_of(&q.id), "note": infantry_protocol::prompts::QuestionAsked::NON_BLOCKING_NOTE }).to_string())
            })(),
            "infantry_attention_raise" => (|| {
                let need = |k: &str| s(a, k).ok_or(format!("needs a {k}"));
                let r = infantry_mail::notify::attention::AttentionRaise {
                    kind: need("kind")?,
                    outcome: need("outcome")?,
                    detail: need("detail")?.chars().take(ATTENTION_DETAIL_MAX).collect(),
                    ticket_id: s(a, "ticketId"),
                    session_id: s(a, "sessionId"),
                    ..Default::default()
                };
                let i = self.attention.raise(&r)?;
                Ok(json!({ "id": i.id }).to_string())
            })(),
            "infantry_pod_state" => (|| {
                let scope = match s(a, "projectId").as_deref() {
                    Some("all") if !pm(inv) => return Err("only the pm or the owner may read every project".to_string()),
                    Some("all") => None,
                    Some(p) => Some(p.to_string()),
                    None => self.caller_project(inv),
                };
                let profiles = self.registry.list();
                let mut by_role: std::collections::BTreeMap<String, Vec<String>> = Default::default();
                for e in &profiles {
                    by_role.entry(e["definition"]["role"].as_str().unwrap_or("").to_string()).or_default().push(e["definition"]["id"].as_str().unwrap_or("").to_string());
                }
                let rows: Vec<SessionRecord> =
                    self.daemon.records().into_iter().filter(|r| scope.as_deref().map(|p| r.project_id.as_deref() == Some(p)).unwrap_or(true)).collect();
                let mut by_state: std::collections::BTreeMap<String, usize> = Default::default();
                for r in &rows {
                    *by_state.entry(state_word(&r.state).to_lowercase()).or_default() += 1;
                }
                let mut out = json!({
                    "scope": scope.as_deref().unwrap_or("all projects"),
                    "agents": { "total": profiles.len(), "byRole": by_role },
                    "sessions": { "total": rows.len(), "byState": by_state },
                });
                if let Some(p) = &scope {
                    out["projectId"] = json!(p);
                    let mut roster: Vec<Value> = Vec::new();
                    for r in rows.iter().filter(|r| r.agent_id.is_some()) {
                        let status = if !live(r) { "OFFLINE" } else if self.daemon.turn_active(&r.id).unwrap_or(false) { "BUSY" } else { "IDLE" };
                        // A live session of an agent outranks an older, exited one.
                        match roster.iter_mut().find(|x| x["agent"] == json!(r.agent_id)) {
                            Some(x) if x["status"] == "OFFLINE" && status != "OFFLINE" => *x = json!({ "agent": r.agent_id, "role": r.role, "session": r.id, "status": status }),
                            Some(_) => {}
                            None => roster.push(json!({ "agent": r.agent_id, "role": r.role, "session": r.id, "status": status })),
                        }
                    }
                    out["roster"] = json!(roster);
                }
                if let Some(id) = s(a, "podId") {
                    out["pod"] = self.pods.status(&id).ok_or_else(|| format!("no pod '{id}'"))?;
                }
                Ok(out.to_string())
            })(),
            "infantry_notify" => (|| {
                use infantry_mail::notify::hub::{Notification, NotificationLevel};
                let title = s(a, "title").ok_or("needs a title")?;
                let n = Notification {
                    title,
                    body: s(a, "body").unwrap_or_default(),
                    level: NotificationLevel::of(&s(a, "level").unwrap_or_default()),
                    sticky: flag(a, "sticky"),
                    dedupe_key: s(a, "dedupeKey"),
                    ticket_id: s(a, "ticketId"),
                    session_id: s(a, "sessionId"),
                    project_id: s(a, "projectId").or_else(|| inv.project_id.clone()),
                    ..Default::default()
                };
                let id = self.hub.post(n, &inv.label);
                Ok(json!({ "id": id }).to_string())
            })(),
            // PARITY-AUDIT-1 #15: the full notification post (id, actions, channels, timeout); a bad level is refused.
            "infantry_notification_post" => (|| {
                use infantry_mail::notify::hub::{Notification, NotificationAction, NotificationLevel};
                let title = s(a, "title").ok_or("needs a title")?;
                let level = match s(a, "level") {
                    None => NotificationLevel::Info,
                    Some(t) => match t.to_ascii_lowercase().as_str() {
                        "info" | "success" | "warning" | "error" | "urgent" => NotificationLevel::of(&t),
                        _ => return Err(format!("level must be info, success, warning, error or urgent (got '{t}')")),
                    },
                };
                let arr = |k: &str| a.get(k).and_then(Value::as_array).cloned().unwrap_or_default();
                let actions = arr("actions")
                    .iter()
                    .filter_map(|o| {
                        let id = o["id"].as_str().map(str::trim).filter(|x| !x.is_empty())?.to_string();
                        let label = o["label"].as_str().map(str::trim).filter(|x| !x.is_empty()).map(str::to_string).unwrap_or_else(|| id.clone());
                        Some(NotificationAction { id, label })
                    })
                    .collect();
                let channels = arr("channels").iter().filter_map(|c| c.as_str().map(str::trim).filter(|x| !x.is_empty()).map(str::to_string)).collect();
                let n = Notification {
                    title,
                    body: s(a, "body").unwrap_or_default(),
                    level,
                    id: s(a, "id").unwrap_or_default(),
                    source: "script".into(),
                    actions,
                    sticky: flag(a, "sticky"),
                    timeout_ms: a.get("timeoutMs").and_then(Value::as_i64).unwrap_or(0).max(0),
                    dedupe_key: s(a, "dedupeKey"),
                    channels,
                    ticket_id: s(a, "ticketId"),
                    session_id: s(a, "sessionId").or_else(|| inv.session_id.clone()),
                    project_id: s(a, "projectId").or_else(|| inv.project_id.clone()),
                    ..Default::default()
                };
                let id = self.hub.post(n, &inv.label);
                Ok(json!({ "ok": true, "id": id }).to_string())
            })(),
            // PARITY-AUDIT-1 #15: mark a ticket so ONE milestone notice fires when it enters done (compose/mod.rs listens).
            "infantry_notify_milestone" => (|| {
                let Some(id) = s(a, "ticketId") else {
                    let l = self.milestones.list();
                    return Ok(if l.is_empty() {
                        "no milestone tickets".to_string()
                    } else {
                        l.iter().map(|m| format!("{} sound {}", m.ticket_id, m.sound.as_deref().unwrap_or("(default)"))).collect::<Vec<_>>().join("
")
                    });
                };
                let on = a.get("on").map(|v| v.as_bool().unwrap_or_else(|| v.as_str().is_some_and(|t| t.eq_ignore_ascii_case("true")))).unwrap_or(true);
                if on {
                    self.work.board().get(&id).map_err(|_| format!("no ticket '{id}'"))?;
                }
                let sound = s(a, "sound");
                Ok(match self.milestones.set(&id, on, sound.as_deref(), crate::sessions::now_ms())? {
                    None => format!("{id}: milestone mark cleared"),
                    Some(m) => format!("{id}: notifies on done (sound {})", m.sound.as_deref().unwrap_or("notify.milestoneSound")),
                })
            })(),
            "infantry_session_act" => (|| {
                if !pm(inv) {
                    return Err("only the pm, the owner or a script may act on another session".to_string());
                }
                let r = self.find(&s(a, "sessionId").unwrap_or_default())?;
                if let Some(why) = owner_guard(inv, "act on", &r) {
                    return Err(why);
                }
                let action = s(a, "action").ok_or("needs an action (queue_add | deliver_urgent)")?;
                let text = s(a, "text").ok_or("needs text")?;
                let how = match action.as_str() {
                    "queue_add" => self.daemon.deliver(&r.id, &text).map(|_| "queued"),
                    "deliver_urgent" => self.daemon.deliver_urgent(&r.id, &text),
                    other => return Err(format!("unknown action '{other}' (queue_add | deliver_urgent)")),
                }?;
                Ok(json!({ "sessionId": r.id, "action": action, "delivered": how }).to_string())
            })(),
            "infantry_pace" => Ok(serde_json::to_string(&self.usage.pace_report(&Value::Object(a.clone()).to_string())).unwrap_or_default()),
            _ => return None,
        };
        Some(match r {
            Ok(t) => ToolResult::ok(t),
            Err(e) => ToolResult::err(format!("{name} refused: {e}")),
        })
    }
}

#[cfg(test)]
mod catalog_guard_tests {
    use super::*;

    #[test]
    fn usage_override_is_declared_and_writable() {
        let t = agent_catalog().into_iter().find(|t| t.name == "infantry_usage_override").expect("infantry_usage_override must be declared");
        assert!(!t.read_only);
        assert_eq!(t.input_schema["required"], json!(["meter", "value"]));
    }

    #[test]
    fn parity_tools_are_declared() {
        let cat = agent_catalog();
        for (name, ro) in [("infantry_waiting", false), ("infantry_pod_state", true), ("infantry_session_restart", false), ("infantry_capability_list", true), ("infantry_attention_raise", false), ("infantry_ask", false)] {
            let t = cat.iter().find(|t| t.name == name).unwrap_or_else(|| panic!("{name} must be declared"));
            assert_eq!(t.read_only, ro, "{name}");
        }
    }
}

/// WAKE-2: the senders that reach a session by mail (build notices, DONE-PING, the failure mail) go through the wake policy.
#[cfg(test)]
mod wake_route_tests {
    use super::*;
    use crate::sessions::{Config, NewOpts};
    use infantry_mail::notify::wake::Wake;
    use infantry_scripting::host::ScriptHost;

    fn setup() -> (std::path::PathBuf, Arc<Daemon>, Arc<crate::compose::Composed>, String) {
        // TESTS-RED-1: the daemon's own WAKE-2 tool is under test, not an installed plugin's. A worker shell under
        // BattalionOS inherits BATTALION_APP_DIR, so without this the owner's installed plugins load and Infantry_Chat's
        // `infantry_message` ("ok <id> QUEUED") answers first. An empty plugins dir (it wins over the package dir) = CI.
        let none = std::env::temp_dir().join("infantry-wake2-no-plugins");
        std::fs::create_dir_all(&none).unwrap();
        std::env::set_var(crate::compose::PLUGINS_DIR_ENV, &none);
        let dir = std::env::temp_dir().join(format!("infantry-wake2-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let daemon = Daemon::new(Arc::new(crate::harness::stub::Stub { start_ms: 5_000, line_ms: 25 }), Config::new(dir.clone(), dir.clone())).unwrap();
        let c = crate::compose::build(&daemon, &dir).unwrap();
        let pm = daemon.launch(NewOpts::default()).unwrap();
        (dir, daemon, c, pm)
    }

    fn send(c: &crate::compose::Composed, pm: &str, kind: &str, key: &str) -> String {
        let inv = ToolInvoker::automation(None, "script:test");
        let a = json!({"to": pm, "body": format!("{kind} {key}"), "subject": kind, "kind": kind, "key": key});
        c.tools.invoke("infantry_message", a.as_object().unwrap(), &inv).text
    }

    /// SCRIPT-TOOLS-MISSING-1: every tool a shipped alias or a global script names is served by the composed daemon, so
    /// no script can queue a call the daemon would refuse as unknown (the smoke tests mock the host, this does not).
    #[tokio::test(flavor = "multi_thread")]
    async fn every_tool_scripts_name_is_served() {
        let (dir, _daemon, c, _pm) = setup();
        let mut served: std::collections::BTreeSet<String> = c.tools.catalog().into_iter().map(|t| t.name).collect();
        // The official plugins load in production (none load here): their declared daemon tools count as served.
        let official = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../plugins/official");
        for e in std::fs::read_dir(&official).unwrap().flatten() {
            let Ok(raw) = std::fs::read_to_string(e.path().join("module.json")) else { continue };
            let m: Value = serde_json::from_str(&raw).unwrap_or_default();
            for t in m["daemon"]["tools"].as_array().into_iter().flatten() {
                if let Some(n) = t["name"].as_str() {
                    served.insert(n.to_string());
                }
            }
        }
        // Handled by the dispatcher itself, never by a host tool.
        let dispatcher_own = [infantry_scripting::dispatcher::PROGRESS, infantry_scripting::dispatcher::CAPABILITY_CALL];
        let mut named: Vec<(String, String)> = infantry_scripting::dispatcher::BUILTIN_ALIASES
            .iter()
            .chain(infantry_scripting::dispatcher::DEFAULT_ALIASES)
            .map(|(k, v)| (format!("alias {k}"), v.to_string()))
            .collect();
        let scripts = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../infantry-scripting/global-scripts");
        for e in std::fs::read_dir(&scripts).unwrap().flatten() {
            let text = std::fs::read_to_string(e.path()).unwrap_or_default();
            for start in ["ih.call(\"", "ih.call('", "ih."] {
                for (i, _) in text.match_indices(start) {
                    let rest = &text[i + start.len()..];
                    let tool: String = rest.chars().take_while(|c| c.is_ascii_lowercase() || *c == '_').collect();
                    if tool.starts_with("infantry_") {
                        named.push((e.file_name().to_string_lossy().into_owned(), tool));
                    }
                }
            }
        }
        let missing: Vec<String> = named.iter().filter(|(_, t)| !served.contains(t) && !dispatcher_own.contains(&t.as_str())).map(|(w, t)| format!("{w}: {t}")).collect();
        assert!(missing.is_empty(), "named by scripts but served by no daemon tool: {missing:?}");
        let _ = std::fs::remove_dir_all(dir);
    }

    /// PARITY-AUDIT-1 #15: infantry_notification_post posts the full notice; a bad level or no title is refused.
    #[tokio::test(flavor = "multi_thread")]
    async fn notification_post_posts_on_the_bus_and_refuses_a_bad_level() {
        let (dir, _daemon, c, _pm) = setup();
        let inv = ToolInvoker::automation(Some("p1".into()), "script:test");
        let a = json!({"title": "Build failed", "level": "error", "id": "n-1", "channels": ["inApp"], "actions": [{"id": "open"}], "timeoutMs": 5000});
        let r = c.tools.invoke("infantry_notification_post", a.as_object().unwrap(), &inv);
        assert!(!r.is_error, "{}", r.text);
        let n = c.hub.history().into_iter().find(|n| n.title == "Build failed").expect("notice posted");
        assert_eq!((n.level.wire(), n.actions[0].label.as_str(), n.timeout_ms), ("error", "open", 5000));
        assert_eq!(n.project_id.as_deref(), Some("p1"));
        assert!(c.tools.invoke("infantry_notification_post", json!({"title": "x", "level": "loud"}).as_object().unwrap(), &inv).text.contains("level must be"));
        assert!(c.tools.invoke("infantry_notification_post", json!({"body": "x"}).as_object().unwrap(), &inv).is_error);
        let _ = std::fs::remove_dir_all(dir);
    }

    /// PARITY-AUDIT-1 #15: a marked ticket posts ONE milestone notice when ticket.done fires; an unknown ticket is refused.
    #[tokio::test(flavor = "multi_thread")]
    async fn notify_milestone_marks_and_fires_once_on_done() {
        let (dir, _daemon, c, _pm) = setup();
        let inv = ToolInvoker::automation(None, "script:test");
        let call = |a: Value| c.tools.invoke("infantry_notify_milestone", a.as_object().unwrap(), &inv);
        assert!(call(json!({"ticketId": "ticket-ghost"})).text.contains("no ticket"));
        assert_eq!(call(json!({})).text, "no milestone tickets");
        c.milestones.set("ticket-m", true, Some("bell"), 1).unwrap();
        assert!(call(json!({})).text.contains("ticket-m sound bell"));
        assert!(call(json!({"ticketId": "ticket-m", "on": false})).text.contains("cleared"));
        c.milestones.set("ticket-m", true, None, 1).unwrap();
        for _ in 0..2 {
            c.bus.emit(infantry_orchestrator_api::hooks::TICKET_DONE, json!({"ticketId": "ticket-m", "projectId": "p1"}));
        }
        let mut seen = 0;
        for _ in 0..50 {
            tokio::time::sleep(std::time::Duration::from_millis(20)).await;
            seen = c.hub.history().iter().filter(|n| n.title == "Milestone reached" && n.ticket_id.as_deref() == Some("ticket-m")).count();
            if seen > 0 {
                break;
            }
        }
        tokio::time::sleep(std::time::Duration::from_millis(100)).await;
        assert_eq!(seen, 1);
        assert_eq!(c.hub.history().iter().filter(|n| n.title == "Milestone reached").count(), 1, "fires once");
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn notify_posts_an_owner_notice_and_session_act_queues_a_turn() {
        let (dir, _daemon, c, pm) = setup();
        let inv = ToolInvoker::automation(None, "script:needs-owner-alert");
        let r = c.tools.invoke("infantry_notify", json!({"title": "Infantry needs you", "body": "x: needs owner", "level": "milestone", "sound": "bell", "ticketId": "ticket-x"}).as_object().unwrap(), &inv);
        assert!(!r.is_error, "{}", r.text);
        let n = c.hub.history().into_iter().find(|n| n.title == "Infantry needs you").expect("notice posted");
        assert_eq!(n.ticket_id.as_deref(), Some("ticket-x"));
        assert!(c.tools.invoke("infantry_notify", json!({"body": "no title"}).as_object().unwrap(), &inv).is_error);
        let act = |a: Value, i: &ToolInvoker| c.tools.invoke("infantry_session_act", a.as_object().unwrap(), i);
        let r = act(json!({"sessionId": pm, "action": "queue_add", "text": "nudge: your ticket waits"}), &inv);
        assert!(!r.is_error, "{}", r.text);
        assert!(r.text.contains("queued"), "{}", r.text);
        assert!(act(json!({"sessionId": pm, "action": "explode", "text": "x"}), &inv).text.contains("unknown action"));
        assert!(act(json!({"sessionId": "ghost", "action": "queue_add", "text": "x"}), &inv).is_error);
        let worker = ToolInvoker::agent("w1", "backend", None, "agent");
        assert!(act(json!({"sessionId": pm, "action": "queue_add", "text": "x"}), &worker).text.contains("only the pm"));
        let _ = std::fs::remove_dir_all(dir);
    }

    /// OWNER-SESSION-GUARD-1: no agent or script may remove, kill, restart, prompt or act on the owner's Assistant; the
    /// owner's own client may; reading it and mail to it still work.
    #[tokio::test(flavor = "multi_thread")]
    async fn agents_and_scripts_cannot_control_the_owner_session() {
        let (dir, daemon, c, pm) = setup();
        let asst = daemon.ensure_assistant().unwrap().id;
        let listed = c.tools.invoke("infantry_get_session", json!({"sessionId": asst}).as_object().unwrap(), &ToolInvoker::agent(&pm, "pm", None, "agent"));
        assert!(!listed.is_error, "reading the owner's session stays open: {}", listed.text);
        assert!(daemon.record(&asst).unwrap().is_owner_owned());
        let script = ToolInvoker::automation(None, "script:worker-watch");
        let agent_pm = ToolInvoker::agent(&pm, "pm", None, "agent");
        let calls = [
            ("infantry_session_remove", json!({"sessionId": asst, "force": true})),
            ("infantry_kill", json!({"sessionId": asst})),
            ("infantry_session_restart", json!({"sessionId": asst})),
            ("infantry_prompt", json!({"sessionId": asst, "prompt": "hi"})),
            ("infantry_session_act", json!({"sessionId": asst, "action": "queue_add", "text": "hi"})),
        ];
        for inv in [&script, &agent_pm] {
            for (tool, args) in &calls {
                let r = c.tools.invoke(tool, args.as_object().unwrap(), inv);
                assert!(r.is_error && r.text.contains("OWNER-SESSION-GUARD-1"), "{tool} by {}: {}", inv.label, r.text);
            }
        }
        assert!(daemon.record(&asst).is_some(), "the Assistant survives every refused call");
        let mail = send(&c, &asst, "note", "owner-guard");
        assert!(!mail.to_lowercase().contains("refused"), "mail to the owner's session is delivered: {mail}");
        let owner = ToolInvoker { owner: true, owner_client: true, label: "tool:infantry_session_act".into(), ..Default::default() };
        let r = c.tools.invoke("infantry_session_act", json!({"sessionId": asst, "action": "queue_add", "text": "from the owner"}).as_object().unwrap(), &owner);
        assert!(!r.is_error, "the owner keeps control: {}", r.text);
        let r = c.tools.invoke("infantry_session_remove", json!({"sessionId": asst, "force": true}).as_object().unwrap(), &owner);
        assert!(!r.is_error, "the owner may remove it: {}", r.text);
        let _ = std::fs::remove_dir_all(dir);
    }

    /// MAIL-OWNER-1: an agent raises an owner decision with `infantry_ask`; the owner's Inbox shows it.
    #[tokio::test(flavor = "multi_thread")]
    async fn infantry_ask_raises_an_owner_question_for_the_calling_session() {
        let (dir, _daemon, c, pm) = setup();
        let inv = ToolInvoker::agent(&pm, "pm", None, "agent");
        let ask = |a: Value| c.tools.invoke("infantry_ask", a.as_object().unwrap(), &inv);
        let ok = ask(json!({"question": "Ship tonight?", "options": ["Ship", {"label": "Wait", "description": "for the phone build"}], "recommended": "Ship", "reason": "tests green"}));
        assert!(!ok.is_error, "{}", ok.text);
        let v: Value = serde_json::from_str(&ok.text).unwrap();
        assert!(v["thread"].as_str().unwrap().starts_with("question:"), "{v}");
        let one = ask(json!({"question": "?", "options": ["Only"], "recommended": "Only", "reason": "r"}));
        assert!(one.is_error && one.text.contains("at least two options"), "{}", one.text);
        let owner = c.tools.invoke("infantry_ask", json!({"question": "?", "options": ["a", "b"], "recommended": "a", "reason": "r"}).as_object().unwrap(), &ToolInvoker::automation(None, "script:t"));
        assert!(owner.is_error, "only an agent session asks: {}", owner.text);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_green_build_is_inbox_a_failed_build_wakes_once_and_done_ping_stays_inbox() {
        let (dir, _daemon, c, pm) = setup();
        // green build: no mail, one attention item
        assert!(send(&c, &pm, "build.success", "run1").starts_with("inbox"));
        assert!(c.mail.mailbox().inbox(&pm).is_empty());
        // failed build: mailed once, the same run id again is a duplicate
        assert!(send(&c, &pm, "build.failed", "run2").starts_with("sent"));
        assert!(send(&c, &pm, "build.failed", "run2").starts_with("not sent"));
        assert_eq!(c.mail.mailbox().inbox(&pm).len(), 1);
        // DONE-PING shares the auto done notice's kind and ticket-id key: inbox by default
        assert!(send(&c, &pm, "ticket.done.auto", "t1").starts_with("inbox"));
        assert!(c.mail.mailbox().inbox(&pm).len() == 1);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// WAKE-UI-2: a plain COMMS mail has no kind: it is `mail.direct` and is delivered (it wakes), not parked. A build notice
    /// that reads green only when its sender names kind `build.success`; the live-test fixture line carried no conclusion.
    #[tokio::test(flavor = "multi_thread")]
    async fn a_mail_without_a_kind_is_direct_and_delivered() {
        let (dir, _daemon, c, pm) = setup();
        let inv = ToolInvoker::automation(None, "script:test");
        let a = json!({"to": pm, "body": "plain", "subject": "plain"});
        assert!(c.tools.invoke("infantry_message", a.as_object().unwrap(), &inv).text.starts_with("sent"));
        assert_eq!(c.mail.mailbox().inbox(&pm).len(), 1);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_failure_comment_and_the_failure_mail_wake_once() {
        let (dir, _daemon, c, pm) = setup();
        // the ticket comment copy (board notice) took the (worker.failed, session:outcome) slot first ...
        let gate = c.wake_gate.clone();
        let policy = infantry_mail::notify::wake::WakePolicy::parse(None);
        assert_eq!(gate.decide(&policy, &pm, "worker.failed", "s1:FAILED"), Wake::Now);
        // ... so the COMMS mail copy of the same failure does not wake again
        assert!(send(&c, &pm, "worker.failed", "s1:FAILED").starts_with("not sent"));
        assert!(c.mail.mailbox().inbox(&pm).is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// PARITY-AUDIT-1 #10: infantry_session_read, infantry_progress, infantry_event_emit and infantry_capability_call.
    #[tokio::test(flavor = "multi_thread")]
    async fn session_read_progress_emit_and_capability_call() {
        let (dir, daemon, c, pm) = setup();
        let me = ToolInvoker::agent(&pm, "pm", Some("p1"), "agent");
        let call = |name: &str, a: Value, inv: &ToolInvoker| c.tools.invoke(name, a.as_object().unwrap(), inv);

        let r = call("infantry_session_read", json!({"sessionId": pm, "lines": 3}), &me);
        assert!(!r.is_error, "{}", r.text);
        let v: Value = serde_json::from_str(&r.text).unwrap();
        assert_eq!(v["sessionId"], pm.as_str());
        assert!(v["frames"].is_array());
        assert!(call("infantry_session_read", json!({"sessionId": "nope-none"}), &me).is_error);
        assert!(call("infantry_session_read", json!({}), &me).is_error);

        let r = call("infantry_progress", json!({"percent": 40, "label": "compile"}), &me);
        assert!(!r.is_error && r.text.contains("40%"), "{}", r.text);
        let p = daemon.record(&pm).unwrap().progress.unwrap();
        assert_eq!((p.percent, p.label.as_deref()), (Some(40.0), Some("compile")));
        assert!(call("infantry_progress", json!({}), &me).is_error, "an empty report is refused");
        let other = daemon.launch(NewOpts::default()).unwrap();
        assert!(call("infantry_progress", json!({"percent": 1, "sessionId": other}), &me).text.contains("refused"));
        assert!(!call("infantry_progress", json!({"clear": true}), &me).is_error);
        assert!(daemon.record(&pm).unwrap().progress.is_none());

        let mut rx = c.bus.subscribe_events();
        let r = call("infantry_event_emit", json!({"name": "build.green", "payload": {"n": 1}}), &me);
        assert!(!r.is_error, "{}", r.text);
        let e = loop {
            let e = rx.recv().await.unwrap();
            if e.name == "build.green" {
                break e;
            }
        };
        assert_eq!((e.payload["n"].as_i64(), e.payload["projectId"].as_str(), e.payload["emittedBy"].as_str()), (Some(1), Some("p1"), Some(pm.as_str())));
        assert!(call("infantry_event_emit", json!({"name": "ticket.moved"}), &me).is_error, "a daemon event is reserved");
        assert!(call("infantry_event_emit", json!({"name": "Bad Name"}), &me).is_error);
        assert!(call("infantry_event_emit", json!({"name": "x.y", "payload": 3}), &me).is_error);

        // No plugins load here: the call is refused with the reason, never "no such tool".
        let r = call("infantry_capability_call", json!({"capability": "docs.list"}), &me);
        assert!(r.is_error && r.text.contains("docs.list"), "{}", r.text);
        assert!(call("infantry_capability_call", json!({}), &me).text.contains("capability is required"));
        assert!(call("infantry_capability_call", json!({"capability": "a.b", "target": "client"}), &me).text.contains("not served"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// PARITY-AUDIT-1 #10: an `infantry_exec` job with `progress` puts its marker lines on the calling session's card.
    #[tokio::test(flavor = "multi_thread")]
    async fn exec_progress_lands_on_the_callers_card() {
        let (dir, daemon, c, pm) = setup();
        let st = crate::compose::settings::SettingsUnit { db: c.db.clone() };
        st.set("scripting.exec.enabled", Some(&json!(true)), &json!({})).unwrap();
        let me = ToolInvoker::agent(&pm, "pm", None, "agent");
        let cmd = if cfg!(windows) {
            json!(["cmd", "/c", "echo ##infantry plan a^|b& echo ##infantry step a"])
        } else {
            json!(["sh", "-c", "echo '##infantry plan a|b'; echo '##infantry step a'"])
        };
        let a = json!({"command": cmd, "cwd": dir.display().to_string(), "progress": {"label": "job"}});
        let r = c.tools.invoke("infantry_exec", a.as_object().unwrap(), &me);
        assert!(!r.is_error, "{}", r.text);
        let p = daemon.record(&pm).unwrap().progress.expect("progress on the card");
        assert_eq!((p.state.as_str(), p.steps_total, p.steps_done, p.label.as_deref()), ("done", Some(2), Some(1), Some("job")));
        let bad = json!({"command": cmd, "cwd": dir.display().to_string(), "progress": {"percentRegex": "("}});
        assert!(c.tools.invoke("infantry_exec", bad.as_object().unwrap(), &me).text.contains("not a regex"));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
