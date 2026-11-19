// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The board's agent / MCP tools (RUST-MIGRATE-2): `infantry_ticket_create|get|update|move|start|comment|comments` and
//! `infantry_board`, with the Kotlin tools' names and arguments (Infantry_Board `TicketSaveTools`, `TicketGetTool`,
//! `TicketMoveTools`, `TicketStartTool`, `TicketCommentTools`, `BoardTool`). The daemon's tool catalog lists
//! [`descriptors`] and routes a call here through [`call`]; MCP bridges reach them over `plugin_tool_call`.
//!
//! Answers are short text (one line per ticket); `verbose` / `detail=full` add the stored ticket as JSON.
use infantry_orchestrator_api::dto::{status, AgentSwitch, CommentAdd, EpicBaseRequest, CommentAuthor, CommentQuery, NotifyEdit, Ticket, TicketMove, TicketSave};
use infantry_orchestrator_api::{AgentQueueRequest, OrchError, StartOrigin};
use infantry_protocol::Envelope;
use serde_json::{Map, Value};

use crate::dispatch::Dispatcher;

const DESCRIPTORS: &str = include_str!("tools.json");
/// First characters of a description in a `ticket_get` summary.
pub const SUMMARY_DESCRIPTION_CHARS: usize = 400;
/// Compact board: lines kept for backlog / todo (first) and done (newest).
pub const BOARD_OPEN_LINES: usize = 10;
pub const BOARD_DONE_LINES: usize = 5;

/// Who calls a tool.
#[derive(Debug, Clone, Default)]
pub struct Caller {
    pub owner: bool,
    pub session_id: Option<String>,
    pub agent_id: Option<String>,
    pub role: Option<String>,
    pub project_id: Option<String>,
}

impl Caller {
    fn author(&self) -> CommentAuthor {
        match &self.session_id {
            Some(s) => CommentAuthor { kind: CommentAuthor::SESSION.into(), session_id: Some(s.clone()), agent_id: self.agent_id.clone() },
            None => CommentAuthor::owner(),
        }
    }
    fn privileged(&self) -> bool {
        self.owner || self.session_id.is_none() || self.role.as_deref().map(|r| r.eq_ignore_ascii_case("pm")).unwrap_or(false)
    }
    fn started_by(&self) -> String {
        self.session_id.clone().unwrap_or_else(|| "owner".into())
    }
}

/// What the tools need from the daemon besides the board: a worker session's live state line, and the bus.
pub struct Ctx<'a> {
    pub work: &'a Dispatcher,
    pub publish: &'a dyn Fn(Envelope),
    /// `session id -> "busy" | "idle" | "exited" ...`; None = unknown session.
    pub session_state: &'a dyn Fn(&str) -> Option<String>,
}

/// The tools' MCP descriptors (`name`, `description`, `inputSchema`, `readOnly`).
pub fn descriptors() -> Vec<Value> {
    serde_json::from_str(DESCRIPTORS).expect("board tools.json")
}

pub fn names() -> Vec<String> {
    descriptors().iter().filter_map(|d| d["name"].as_str().map(str::to_string)).collect()
}

fn s(a: &Map<String, Value>, k: &str) -> Option<String> {
    a.get(k).and_then(|v| match v {
        Value::String(x) => Some(x.trim().to_string()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    })
    .filter(|x| !x.is_empty())
}

fn b(a: &Map<String, Value>, k: &str) -> bool {
    matches!(a.get(k), Some(Value::Bool(true))) || matches!(a.get(k), Some(Value::String(x)) if x == "true")
}

fn i(a: &Map<String, Value>, k: &str) -> Option<i64> {
    match a.get(k)? {
        Value::Number(n) => n.as_i64(),
        Value::String(x) => x.trim().parse().ok(),
        _ => None,
    }
}

fn list(a: &Map<String, Value>, k: &str) -> Option<Vec<String>> {
    match a.get(k)? {
        Value::Array(xs) => Some(xs.iter().filter_map(|v| v.as_str().map(|x| x.trim().to_string())).filter(|x| !x.is_empty()).collect()),
        Value::String(x) => Some(x.split(',').map(|p| p.trim().to_string()).filter(|p| !p.is_empty()).collect()),
        _ => None,
    }
}

/// `200000`, `200k`, `1m`, `off` (= 0, which clears).
pub fn parse_context_limit(raw: &str) -> Result<i32, String> {
    let t = raw.trim().to_lowercase().replace(['_', ','], "");
    if t.is_empty() || t == "off" || t == "none" {
        return Ok(0);
    }
    let (num, mul) = match t.chars().last() {
        Some('k') => (&t[..t.len() - 1], 1_000f64),
        Some('m') => (&t[..t.len() - 1], 1_000_000f64),
        _ => (t.as_str(), 1f64),
    };
    let n: f64 = num.parse().map_err(|_| format!("context limit '{raw}' is not a number (e.g. 200000, 200k, 1m, off)"))?;
    Ok((n * mul).round() as i32)
}

pub fn short(id: &str) -> String {
    let n = id.chars().count();
    id.chars().skip(n.saturating_sub(8)).collect()
}

fn role_of(t: &Ticket) -> Option<String> {
    t.agent_id.as_deref().map(|a| a.strip_prefix("role:").unwrap_or(a).to_string())
}

fn err(e: OrchError) -> String {
    match e {
        OrchError::Refused(m) => format!("refused: {m}"),
        OrchError::Failed(m) => format!("failed: {m}"),
        OrchError::Unsupported(m) => format!("not supported: {m}"),
    }
}

fn line(t: &Ticket, ctx: &Ctx, with_project: bool) -> String {
    let mut out = String::new();
    if with_project {
        out.push_str(&format!("[{}] ", t.project_id.as_deref().unwrap_or("-")));
    }
    out.push_str(&format!("{} {}", short(&t.id), t.title));
    let mut tags = Vec::new();
    if let Some(r) = role_of(t) {
        tags.push(r);
    }
    if let Some(p) = &t.provider {
        tags.push(match &t.model {
            Some(m) => format!("{p}/{m}"),
            None => p.clone(),
        });
    }
    if let Some(w) = t.weight {
        tags.push(format!("w{w}"));
    }
    if !tags.is_empty() {
        out.push_str(&format!(" [{}]", tags.join(" ")));
    }
    if let Some(sid) = &t.session_id {
        let st = (ctx.session_state)(sid).unwrap_or_else(|| "gone".into());
        out.push_str(&format!(" worker {} {st}", short(sid)));
    }
    if let Some(q) = &t.queued_for {
        out.push_str(&format!(" queued for {q}"));
    }
    if let Some(h) = &t.hold_reason {
        out.push_str(&format!(" held: {h}"));
    }
    if let Some(br) = t.branch.as_ref().or(t.worktree_branch.as_ref()) {
        out.push_str(&format!(" ({br})"));
    }
    out
}

fn saved(verb: &str, t: &Ticket, verbose: bool) -> String {
    let mut out = format!("{verb} {} \"{}\" in {} at position {}", t.id, t.title, t.status, t.position);
    if let Some(r) = role_of(t) {
        out.push_str(&format!(", role {r}"));
    }
    if let Some(p) = &t.provider {
        out.push_str(&format!(", provider {p}"));
    }
    if let Some(m) = &t.model {
        out.push_str(&format!(", model {m}"));
    }
    if let Some(p) = &t.project_id {
        out.push_str(&format!(", project {p}"));
    }
    if verbose {
        out.push('\n');
        out.push_str(&serde_json::to_string_pretty(t).unwrap_or_default());
    }
    out
}

fn epic_id(work: &Dispatcher, project: Option<&str>, want: &str) -> Result<Option<String>, String> {
    if want.eq_ignore_ascii_case("none") {
        return Ok(Some(String::new()));
    }
    let Some(p) = project else { return Err("an epic needs the ticket's project".into()) };
    let epics = work.board().epics(p).map_err(err)?;
    epics
        .iter()
        .find(|e| e.id == want || e.name.eq_ignore_ascii_case(want))
        .map(|e| Some(e.id.clone()))
        .ok_or_else(|| format!("no epic '{want}' in project {p}"))
}

fn save_fields(a: &Map<String, Value>, req: &mut TicketSave) -> Result<(), String> {
    req.title = s(a, "title");
    req.description = a.get("description").and_then(|v| v.as_str()).map(str::to_string);
    if let Some(r) = s(a, "role") {
        req.agent_id = Some(if r.contains(':') { r } else { format!("role:{r}") });
    }
    req.provider = s(a, "provider");
    req.model = s(a, "model");
    req.weight = i(a, "weight").map(|w| w as i32);
    req.effort = s(a, "effort");
    req.runner = s(a, "runner");
    if let Some(c) = s(a, "contextLimit") {
        req.context_limit = Some(parse_context_limit(&c)?);
    }
    Ok(())
}

/// Runs tool [name]; None = not a board tool. Ok = the answer text, Err = a refusal (isError).
pub fn call(ctx: &Ctx, name: &str, a: &Map<String, Value>, who: &Caller) -> Option<Result<String, String>> {
    let board = ctx.work.board();
    let r = match name {
        "infantry_ticket_create" => (|| {
            let mut req = TicketSave::default();
            save_fields(a, &mut req)?;
            req.project_id = s(a, "projectId").or_else(|| who.project_id.clone());
            req.status = s(a, "status");
            if let Some(e) = s(a, "epic").or_else(|| s(a, "epicId")) {
                req.epic_id = epic_id(ctx.work, req.project_id.as_deref(), &e)?;
            }
            let t = board.save(req, ctx.publish).map_err(err)?.ticket;
            let _ = board.record_creator(&t.id, &who.author(), list(a, "notify"));
            Ok(saved("created", &t, b(a, "verbose")))
        })(),
        "infantry_agent_switch" => (|| {
            let agent = s(a, "agentId").ok_or("infantry_agent_switch needs an agentId")?;
            let to = board.find(&s(a, "ticketId").ok_or("infantry_agent_switch needs a ticketId")?).map_err(err)?;
            let moved = ctx.work.switch(&AgentSwitch { agent_id: agent, ticket_id: to.id.clone(), now: b(a, "now") }).map_err(err)?.ticket;
            let note = moved.start.as_ref().filter(|st| st.reason == "deferred").and_then(|st| st.detail.as_ref()).map(|d| format!(" ({d})")).unwrap_or_default();
            if b(a, "verbose") {
                return Ok(serde_json::to_string_pretty(&moved).unwrap_or_default());
            }
            Ok(format!("ok {} {}{note}", short(&moved.id), moved.status))
        })(),
        "infantry_agent_queue" => (|| {
            let agent_id = s(a, "agentId").ok_or("infantry_agent_queue needs an agentId")?;
            let at = match s(a, "at") {
                Some(x) => Some(x.parse::<i32>().map_err(|_| "infantry_agent_queue: at must be a whole number".to_string())?),
                None => None,
            };
            let ticket_id = match s(a, "ticketId") {
                Some(r) => Some(board.find(&r).map_err(err)?.id),
                None => None,
            };
            let st = ctx.work.agent_queue(&AgentQueueRequest { agent_id, action: s(a, "action").unwrap_or_else(|| "list".into()), ticket_id, at }).map_err(err)?;
            let head = format!("queue of {}: {} ticket(s){}", st.name.as_deref().unwrap_or(&st.agent_id), st.tickets.len(), st.message.as_ref().map(|m| format!(" ({m})")).unwrap_or_default());
            let lines: Vec<String> = st.tickets.iter().map(|q| format!("#{} {} {}", q.rank, short(&q.ticket_id), q.title)).collect();
            Ok(if lines.is_empty() { head } else { format!("{head}
{}", lines.join("
")) })
        })(),
        "infantry_epic_base" => (|| {
            let epic_id = s(a, "epicId").ok_or("epicId is required")?;
            let req = EpicBaseRequest { epic_id, branch: s(a, "branch"), clear: b(a, "clear") };
            let e = board.epic_base(req).map_err(err)?;
            Ok(serde_json::json!({"epicId": e.id, "name": e.name, "projectId": e.project_id, "baseBranch": e.base_branch}).to_string())
        })(),
        "infantry_ticket_get" => (|| {
            let t = board.find(&s(a, "ticketId").ok_or("ticketId is required")?).map_err(err)?;
            if s(a, "detail").as_deref() == Some("full") {
                return Ok(serde_json::to_string_pretty(&t).unwrap_or_default());
            }
            let open = board.comment_store().ok().and_then(|c| c.summary(&t.id).ok()).map(|s| s.open).unwrap_or(0);
            let desc: String = t.description.chars().take(SUMMARY_DESCRIPTION_CHARS).collect();
            let cut = if t.description.chars().count() > SUMMARY_DESCRIPTION_CHARS { "\n[... detail=full for the whole description]" } else { "" };
            Ok(format!(
                "{}\ntitle: {}\nstatus: {} (position {})\nproject: {}\nrole: {}\nprovider/model: {}/{}\nbranch: {}\nepic: {}\nworker: {}\nopen comments: {open}\n---\n{desc}{cut}",
                t.id,
                t.title,
                t.status,
                t.position,
                t.project_id.as_deref().unwrap_or("-"),
                role_of(&t).unwrap_or_else(|| "-".into()),
                t.provider.as_deref().unwrap_or("-"),
                t.model.as_deref().unwrap_or("-"),
                t.branch.as_deref().or(t.worktree_branch.as_deref()).unwrap_or("-"),
                t.epic_id.as_deref().unwrap_or("-"),
                t.session_id.as_deref().unwrap_or("-"),
            ))
        })(),
        "infantry_ticket_update" => (|| {
            let t = board.find(&s(a, "ticketId").ok_or("ticketId is required")?).map_err(err)?;
            let mut req = TicketSave { id: Some(t.id.clone()), ..Default::default() };
            save_fields(a, &mut req)?;
            if let Some(e) = s(a, "epic") {
                req.epic_id = epic_id(ctx.work, t.project_id.as_deref(), &e)?;
            }
            let saved_t = board.save(req, ctx.publish).map_err(err)?.ticket;
            let (set, add, remove) = (list(a, "notify"), list(a, "addNotify").unwrap_or_default(), list(a, "removeNotify").unwrap_or_default());
            if set.is_some() || !add.is_empty() || !remove.is_empty() {
                board.notify_edit(NotifyEdit { ticket_id: t.id.clone(), set, add, remove }).map_err(err)?;
            }
            Ok(saved("saved", &saved_t, b(a, "verbose")))
        })(),
        "infantry_ticket_move" => (|| {
            let t = board.find(&s(a, "ticketId").ok_or("ticketId is required")?).map_err(err)?;
            let target = s(a, "status").map(|x| x.to_lowercase());
            if target.as_deref() == Some(status::IN_PROGRESS) {
                return Err("to start a ticket use infantry_ticket_start".to_string());
            }
            if target.as_deref() == Some(status::PENDING) {
                return Err("pending is set by the daemon from open prerequisites".to_string());
            }
            let column = target.clone().unwrap_or_else(|| t.status.clone());
            let mut col: Vec<Ticket> = board.list(t.project_id.as_deref(), None).map_err(err)?.into_iter().filter(|x| x.status == column && x.id != t.id).collect();
            col.sort_by_key(|x| x.position);
            let place = |r: &str| -> Result<usize, String> {
                let other = board.find(r).map_err(err)?;
                col.iter().position(|x| x.id == other.id).ok_or_else(|| format!("{} is not in {column}", short(&other.id)))
            };
            let position = if let Some(r) = s(a, "before") {
                Some(place(&r)? as i32)
            } else if let Some(r) = s(a, "after") {
                Some(place(&r)? as i32 + 1)
            } else if b(a, "top") {
                Some(0)
            } else if b(a, "bottom") {
                Some(col.len() as i32)
            } else {
                i(a, "position").map(|p| p.clamp(0, col.len() as i64) as i32)
            };
            if target.is_none() && position.is_none() {
                return Err("give a status and/or one of before, after, top, bottom, position".to_string());
            }
            let req = TicketMove { id: t.id.clone(), status: target, position, wont_fix: None };
            let moved = ctx.work.move_lane(req, Some(&who.started_by())).map_err(err)?.ticket;
            Ok(saved("moved", &moved, b(a, "verbose")))
        })(),
        "infantry_ticket_start" => (|| {
            let t = board.find(&s(a, "ticketId").ok_or("ticketId is required")?).map_err(err)?;
            let started = ctx.work.start(&t.id, StartOrigin::Move, Some(&who.started_by())).map_err(err)?.ticket;
            let mut out = match (&started.session_id, &started.queued_for) {
                (Some(sid), _) if started.status == status::IN_PROGRESS => format!("started {}: worker session {sid}", started.id),
                (_, Some(q)) => format!("queued {} for {q}", started.id),
                _ => format!("{} is {}", started.id, started.status),
            };
            if let Some(st) = started.start.as_ref().filter(|x| !x.ok) {
                out.push_str(&format!(" (start: {}{})", st.reason, st.detail.as_deref().map(|d| format!(": {d}")).unwrap_or_default()));
            }
            if let Some(br) = started.branch.as_ref().or(started.worktree_branch.as_ref()) {
                out.push_str(&format!(" on {br}"));
            }
            if b(a, "verbose") {
                out.push('\n');
                out.push_str(&serde_json::to_string_pretty(&started).unwrap_or_default());
            }
            Ok(out)
        })(),
        "infantry_ticket_comment" => (|| {
            let (tid, body) = (s(a, "ticketId"), a.get("body").and_then(|v| v.as_str()).map(str::to_string).filter(|x| !x.trim().is_empty()));
            if let (Some(cid), None, None) = (s(a, "commentId"), &tid, &body) {
                let c = board.comment_resolve(&cid, &who.author()).map_err(err)?;
                return Ok(format!("resolved comment {} on {}", short(&c.id), short(&c.ticket_id)));
            }
            let req = CommentAdd {
                ticket_id: tid.ok_or("ticketId is required")?,
                body: body.ok_or("body is required")?,
                kind: s(a, "kind"),
                state: s(a, "state"),
                author: who.author(),
            };
            let c = board.comment_add(req).map_err(err)?;
            let mut out = format!("comment {} ({}/{}) on {}", short(&c.id), c.kind, c.state, short(&c.ticket_id));
            if !c.undelivered.is_empty() {
                out.push_str(&format!("; not delivered to: {}", c.undelivered.join(", ")));
            }
            Ok(out)
        })(),
        "infantry_ticket_comments" => (|| {
            let full = s(a, "detail").as_deref() == Some("full");
            let comments = if let Some(cid) = s(a, "commentId") {
                let c = board.comment_store().map_err(err)?.get(&cid).map_err(err)?.ok_or_else(|| format!("no comment '{cid}'"))?;
                return Ok(format!("{} {}/{} by {} {}: {}", short(&c.id), c.kind, c.state, c.author.label(), display_stamp(c.created_at), c.body));
            } else {
                let q = CommentQuery { ticket_id: s(a, "ticketId").ok_or("ticketId is required")?, which: s(a, "which"), kind: s(a, "kind"), limit: i(a, "limit").map(|x| x as i32) };
                let all = q.which.as_deref() == Some("all");
                (board.comments(q).map_err(err)?, all)
            };
            let (list, all) = comments;
            if list.is_empty() {
                return Ok("no comments".into());
            }
            Ok(list
                .iter()
                .map(|c| {
                    let body = if full || !all { c.body.clone() } else { c.body.lines().next().unwrap_or("").chars().take(160).collect() };
                    format!("{} {}/{} by {} {}: {}", short(&c.id), c.kind, c.state, c.author.label(), display_stamp(c.created_at), body)
                })
                .collect::<Vec<_>>()
                .join("\n"))
        })(),
        "infantry_board" => (|| {
            let want = s(a, "projectId");
            let scope = match want.as_deref() {
                Some("all") if !who.privileged() => return Err("only the pm or the owner may read every project".to_string()),
                Some("all") => None,
                Some(p) => Some(p.to_string()),
                None => who.project_id.clone(),
            };
            let tickets = board.list(scope.as_deref(), None).map_err(err)?;
            let cols: Vec<String> = s(a, "columns").map(|c| c.split(',').map(|x| x.trim().to_lowercase()).filter(|x| !x.is_empty()).collect()).unwrap_or_else(|| status::ALL.iter().map(|x| x.to_string()).collect());
            let limit = i(a, "limit").filter(|n| *n > 0).map(|n| n as usize);
            let mut out = Vec::new();
            for c in &cols {
                let mut ts: Vec<&Ticket> = tickets.iter().filter(|t| &t.status == c).collect();
                if c == status::DONE {
                    ts.sort_by(|x, y| y.updated_at.cmp(&x.updated_at));
                } else {
                    ts.sort_by_key(|t| t.position);
                }
                let cap = limit.unwrap_or(match c.as_str() {
                    status::DONE => BOARD_DONE_LINES,
                    status::BACKLOG | status::TODO => BOARD_OPEN_LINES,
                    _ => usize::MAX,
                });
                out.push(format!("== {c} ({})", ts.len()));
                for t in ts.iter().take(cap) {
                    out.push(format!("  {}", line(t, ctx, scope.is_none())));
                }
                if ts.len() > cap {
                    out.push(format!("  ... {} more (limit)", ts.len() - cap));
                }
            }
            Ok(out.join("\n"))
        })(),
        _ => return None,
    };
    Some(r)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn context_limits_parse_like_the_kotlin_tool() {
        assert_eq!(parse_context_limit("200k").unwrap(), 200_000);
        assert_eq!(parse_context_limit("1m").unwrap(), 1_000_000);
        assert_eq!(parse_context_limit("150000").unwrap(), 150_000);
        assert_eq!(parse_context_limit("off").unwrap(), 0);
        assert!(parse_context_limit("lots").is_err());
    }

    #[test]
    fn descriptors_name_the_pm_tools() {
        let n = names();
        for t in ["infantry_agent_switch", "infantry_agent_queue", "infantry_ticket_create", "infantry_ticket_get", "infantry_ticket_update", "infantry_ticket_move", "infantry_ticket_start", "infantry_ticket_comment", "infantry_ticket_comments", "infantry_board", "infantry_epic_base"] {
            assert!(n.iter().any(|x| x == t), "{t}");
        }
        assert_eq!(short("0123456789abcdef"), "89abcdef");
    }
}

/// `2026-11-03 20:41 -05:00`: local time with its offset (Kotlin `DisplayZone.stamp`), for agent-facing lines.
pub fn display_stamp(epoch_ms: i64) -> String {
    use chrono::TimeZone;
    match chrono::Local.timestamp_millis_opt(epoch_ms).single() {
        Some(t) => t.format("%Y-%m-%d %H:%M %:z").to_string(),
        None => epoch_ms.to_string(),
    }
}
