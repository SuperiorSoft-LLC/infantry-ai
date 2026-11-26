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

/// The agent + usage tools' catalog entries.
pub fn agent_catalog() -> Vec<ToolInfo> {
    infos(DESCRIPTORS)
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
}
