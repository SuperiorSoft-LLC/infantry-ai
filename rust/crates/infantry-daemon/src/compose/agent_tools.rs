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

pub struct AgentTools {
    pub daemon: Arc<Daemon>,
    pub work: Arc<Dispatcher>,
    pub bus: EventBus,
    pub usage: Arc<UsageService>,
    pub mail: Arc<infantry_mail::mail::router::MailRouter>,
    pub project_dir: Arc<dyn Fn(&str) -> Option<std::path::PathBuf> + Send + Sync>,
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
                let r = self.find(&s(a, "sessionId").unwrap_or_default())?;
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
                    let Some(h) = crate::harness::neutral(p) else {
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
            "infantry_pace" => Ok(serde_json::to_string(&self.usage.pace_report(&Value::Object(a.clone()).to_string())).unwrap_or_default()),
            _ => return None,
        };
        Some(match r {
            Ok(t) => ToolResult::ok(t),
            Err(e) => ToolResult::err(format!("{name} refused: {e}")),
        })
    }
}
