// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `infantry ticket ...` (RUST-CLI-1), with the Kotlin text and exit codes: `ticketUsageError`, `roleUsageError` and
//! `ticket(...)`'s dispatch (create, update, move, unassign, assign, start, link, files, archive/restore, comment,
//! epic, related, show), plus `TicketStarter`, `TicketPlacement`, `TicketImageFiles`, `TicketFilesCli`, `ArchiveCli`,
//! `TicketCommentCli` and `TicketEpicCli`.

use super::args::{Parsed, TICKET_STATUSES};
use super::cmd_board::{
    board_project, check_contract, context_label, decode_ticket, fetch_board, match_prefix, n, parse_context_limit,
    parse_effort, parse_mcp, raw_flag_error, resolve_epic_id, resolve_ticket, s, sessions, short_id,
};
use super::conn::Envelope;
use super::{CmdResult, Ctx, Getenv};
use base64::Engine;
use serde_json::{json, Map, Value};
use std::path::{Component, Path, PathBuf};
use std::time::{Duration, Instant};

/// `TicketLinkType.ALL`.
pub const LINK_TYPES: [&str; 8] =
    ["blocked_by", "blocks", "depends_on", "child_of", "parent_of", "relates_to", "duplicates", "duplicated_by"];
/// `RoleIds.CANONICAL`.
pub const CANONICAL_ROLES: [&str; 16] = [
    "architect", "backend", "frontend", "fullstack", "qa", "devops", "security", "mobile", "pm", "marketing",
    "version-control", "seo", "localization", "ideation", "automation", "assistant",
];
/// `RelatedDescriptionBlock.BEGIN` / `END`.
pub const RELATED_BEGIN: &str = "<!-- RELATED (auto) -->";
pub const RELATED_END: &str = "<!-- /RELATED -->";
/// `TicketImages.TICKET_UPLOADS` / `MAX_BYTES`.
pub const TICKET_UPLOADS: &str = ".infantry/uploads/ticket/";
pub const IMAGE_MAX_BYTES: u64 = 10 * 1024 * 1024;
/// `TicketStart.LAUNCH_WARNING` / `ALREADY_IN_PROGRESS`.
const LAUNCH_WARNING: &str = "launch warning: ";
const ALREADY_IN_PROGRESS: &str = "already_in_progress";
const IN_PROGRESS: &str = "in_progress";
const POLL_MS: u64 = 100;
const LOST: &str = "the connection to the daemon dropped before it answered the start, so it is unknown whether the ticket started: read the ticket again before retrying";
/// `TicketFilesCli.request`: a backfill runs one git diff per done ticket (minutes); the reads are SQL.
const FILES_BACKFILL_TIMEOUT_MS: u64 = 30 * 60_000;
const FILES_TIMEOUT_MS: u64 = 45_000;
/// `ArchiveCli.request`.
const ARCHIVE_TIMEOUT_MS: u64 = 120_000;

pub const COMMENT_USAGE: &str = "infantry ticket comment <id> [--kind done|issue|note|handoff] [--state open|resolved] <text...> | infantry ticket comment <id> [done|issue|note] <text...> | infantry ticket comment resolve <commentId> | infantry ticket comment list <id> [latestOpen|latest|first|all] [done|issue|note]";
const COMMENT_KINDS: [&str; 4] = ["done", "issue", "note", "handoff"];
const COMMENT_STATES: [&str; 2] = ["open", "resolved"];
pub const EPIC_USAGE: &str = "infantry ticket epic <id|prefix>... <epic id|epic name|none> [--force]";
const EPIC_TOOL: &str = "infantry_ticket_epic";
pub const FILES_USAGE: &str = "usage: infantry ticket files <id> [--related] [--limit <n>] | infantry ticket files --path <glob> [--path <glob>...] [--limit <n>] | infantry ticket files --backfill [--all]";
pub const ARCHIVE_USAGE: &str = "usage: infantry ticket archive <id>... | --epic <id> | --filter <lane>[:<days>] [--project <id>] [--dry-run] | infantry ticket restore <id>... | infantry archive list|show <id>|search <text>|count|restore <id>...|--epic <id> [--project <id>] [--limit <n>]";

fn opt(v: Option<String>) -> Value {
    v.map_or(Value::Null, Value::String)
}
fn trimmed(v: Option<&str>) -> Option<String> {
    v.map(|x| x.trim().to_string())
}
fn args_from(p: &Parsed, from: usize) -> Vec<String> {
    p.args.iter().skip(from).cloned().collect()
}

// ---- usage errors ----

/// `roleUsageError`.
pub fn role_usage_error(role: Option<&str>) -> Option<String> {
    let role = role?;
    let lower = role.trim().to_lowercase();
    let id = lower.strip_prefix("role:").unwrap_or(&lower);
    let ok = !id.is_empty()
        && id.len() <= 32
        && id.chars().next().is_some_and(|c| c.is_ascii_lowercase())
        && id.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '-');
    if ok {
        None
    } else {
        Some(format!(
            "--role '{role}' is not a role id (a lowercase letter, then lowercase letters, digits or '-'); valid role ids: {} (or the id of a custom role)",
            CANONICAL_ROLES.join(", ")
        ))
    }
}

/// `TicketCommentCli.usageError`: [args] are the words after `ticket comment`.
pub fn comment_usage_error(args: &[String]) -> Option<String> {
    let bad = match args.first().map(String::as_str) {
        None => true,
        Some("resolve") => args.len() != 2,
        Some("list") => !(2..=4).contains(&args.len()),
        Some(_) => args.len() < 2 || (COMMENT_KINDS.contains(&args[1].as_str()) && args.len() < 3),
    };
    bad.then(|| COMMENT_USAGE.to_string())
}

/// The `--kind` / `--state` flags of `ticket comment <id> ...`: a refusal for a value the board does not take.
pub fn comment_flags_error(kind: Option<&str>, state: Option<&str>) -> Option<String> {
    if let Some(k) = kind.map(str::trim).filter(|k| !COMMENT_KINDS.contains(k)) {
        return Some(format!("--kind '{k}' is not a comment kind; expected one of {}", COMMENT_KINDS.join(", ")));
    }
    if let Some(st) = state.map(str::trim).filter(|st| !COMMENT_STATES.contains(st)) {
        return Some(format!("--state '{st}' is not a comment state; expected one of {}", COMMENT_STATES.join(", ")));
    }
    None
}

pub const COMMENTS_USAGE: &str = "infantry ticket comments <id|prefix> [--which latestOpen|latest|first|all] [--kind done|issue|note] [--limit N]";

/// `ticket comments`: one ticket id; --limit a whole number > 0.
pub fn comments_usage_error(p: &Parsed) -> Option<String> {
    let bad_id = p.args.len() != 2 || p.args[1].trim().is_empty();
    let bad_limit = p.val("limit").is_some_and(|l| l.parse::<u32>().map_or(true, |n| n == 0));
    (bad_id || bad_limit).then(|| COMMENTS_USAGE.to_string())
}

/// `TicketEpicCli.usageError`.
pub fn epic_usage_error(args: &[String]) -> Option<String> {
    (args.len() < 2 || args.iter().any(|a| a.trim().is_empty())).then(|| EPIC_USAGE.to_string())
}

/// `TicketFilesCli.usageError`.
pub fn files_usage_error(id: Option<&str>, paths: &[String], backfill: bool, limit: Option<&str>) -> Option<String> {
    if let Some(l) = limit {
        if l.parse::<i32>().unwrap_or(0) < 1 {
            return Some("--limit expects a whole number of lines, 1 or more".into());
        }
    }
    let named = [id.is_some(), !paths.is_empty(), backfill].iter().filter(|x| **x).count();
    (named != 1).then(|| FILES_USAGE.to_string())
}

/// `ArchiveCli.usageError`.
pub fn archive_usage_error(action: Option<&str>, ids: &[String], epic: Option<&str>, filter: Option<&str>) -> Option<String> {
    let bad = match action {
        Some("archive") => ids.is_empty() && epic.is_none() && filter.is_none(),
        Some("restore") => ids.is_empty() && epic.is_none(),
        Some("show") => ids.len() != 1,
        Some("search") => ids.is_empty(),
        Some("list") | Some("count") => false,
        _ => true,
    };
    bad.then(|| ARCHIVE_USAGE.to_string())
}

/// `ticketUsageError`, after the raw flag refusals.
pub fn validate(p: &Parsed, _getenv: Getenv) -> Option<String> {
    if let Some(e) = raw_flag_error(p) {
        return Some(e);
    }
    usage_error(p)
}

pub fn usage_error(p: &Parsed) -> Option<String> {
    let args = &p.args;
    let blank = |i: usize| args.get(i).map_or(true, |a| a.trim().is_empty());
    let sub = args.first().map(String::as_str);
    match sub {
        Some("related") => blank(1).then(|| "ticket related requires a ticket id: infantry ticket related <id|prefix> [--suggest]".into()),
        Some("comment") => comment_flags_error(p.val("kind"), p.val("state")).or_else(|| {
            let words = &args[1..];
            let flagged = p.val("kind").is_some() && !matches!(words.first().map(String::as_str), Some("resolve" | "list"));
            if flagged { (words.len() < 2).then(|| COMMENT_USAGE.to_string()) } else { comment_usage_error(words) }
        }),
        Some("comments") => comments_usage_error(p),
        Some("epic") => epic_usage_error(&args[1..]),
        Some(a @ ("archive" | "restore")) => archive_usage_error(Some(a), &args[1..], p.val("epic"), p.val("filter")),
        Some("files") => {
            let id = args.get(1).map(String::as_str).filter(|a| !a.trim().is_empty());
            files_usage_error(id, &p.vals("path"), p.flag("backfill"), p.val("limit"))
        }
        Some("show") => blank(1).then(|| "ticket show requires a ticket id: infantry ticket show <id|prefix> (see: infantry tickets)".into()),
        Some("update") => {
            if blank(1) {
                return Some("ticket update requires a ticket id: infantry ticket update <id|prefix> [--title ...] [--desc ...] [--role <id>] [--provider <id>] [--model <id>] [--epic <id|prefix>]".into());
            }
            let nothing = p.val("title").is_none()
                && p.description.is_none()
                && ["role", "provider", "model", "context-limit", "effort", "runner", "epic"].iter().all(|f| p.val(f).is_none())
                && p.weight.is_none()
                && ["mcp", "image", "remove-image", "related-to", "notify", "add-notify", "remove-notify"].iter().all(|f| p.vals(f).is_empty());
            if nothing {
                return Some("ticket update needs something to change: --title, --desc, --role, --provider, --model, --weight, --context-limit, --effort, --runner, --mcp, --epic, --image, --remove-image or --related-to".into());
            }
            if p.val("title").is_some_and(|t| t.trim().is_empty()) {
                return Some("ticket update --title cannot be blank".into());
            }
            role_usage_error(p.val("role"))
        }
        Some("create") => {
            if !p.vals("remove-image").is_empty() {
                Some("ticket create has no image to remove: --remove-image is for ticket update".into())
            } else if p.val("title").map_or(true, |t| t.trim().is_empty()) {
                Some("ticket create requires a title: infantry ticket create --title \"...\" [--desc \"...\"] [--project <id>]".into())
            } else if p.val("epic").is_some_and(|e| e.trim().is_empty()) {
                Some("ticket create --epic needs an epic id or prefix: infantry ticket create --title \"...\" [--epic <id|prefix>] [--project <id>]".into())
            } else if let Some(st) = p.val("status").filter(|st| !["todo", "backlog"].contains(&st.trim().to_lowercase().as_str())) {
                Some(format!("ticket create --status is todo (the sprint, default) or backlog, not '{st}'"))
            } else {
                role_usage_error(p.val("role"))
            }
        }
        Some("move") => {
            let (before, after, top, bottom, position) =
                (p.val("before").is_some(), p.val("after").is_some(), p.flag("top"), p.flag("bottom"), p.val("position"));
            let named = [before, after, top, bottom, position.is_some()].iter().filter(|x| **x).count();
            if blank(1) {
                Some("ticket move requires a ticket id: infantry ticket move <id|prefix> <status>".into())
            } else if named > 1 {
                Some("ticket move takes one of --before, --after, --top, --bottom, --position".into())
            } else if let Some(pos) = position.filter(|x| x.trim().parse::<i32>().ok().filter(|v| *v >= 0).is_none()) {
                Some(format!("--position expects a whole number, 0 = the top of the column; was '{pos}'"))
            } else if args.len() == 2 && named > 0 {
                None
            } else if blank(2) {
                Some("ticket move requires a status: infantry ticket move <id|prefix> <status|--before <id>|--after <id>|--top|--bottom|--position <n>>".into())
            } else if !TICKET_STATUSES.contains(&args[2].trim()) {
                Some(format!("unknown status '{}'; expected one of {}", args[2], TICKET_STATUSES.join(", ")))
            } else {
                None
            }
        }
        Some("start") => {
            if blank(1) {
                Some("ticket start requires a ticket id: infantry ticket start <id|prefix> (see: infantry tickets)".into())
            } else if args.len() > 2 {
                Some("ticket start takes one ticket id: infantry ticket start <id|prefix>".into())
            } else {
                None
            }
        }
        Some("link") => {
            if blank(1) {
                Some("ticket link requires a ticket id: infantry ticket link <id|prefix> <otherId|prefix> <type>".into())
            } else if blank(2) {
                Some("ticket link requires a second ticket id: infantry ticket link <id|prefix> <otherId|prefix> <type>".into())
            } else if blank(3) {
                Some(format!("ticket link requires a link type: expected one of {}", LINK_TYPES.join(", ")))
            } else if !LINK_TYPES.contains(&args[3].trim()) {
                Some(format!("unknown link type '{}'; expected one of {}", args[3], LINK_TYPES.join(", ")))
            } else {
                None
            }
        }
        // Kotlin's `ticketUsageError` has no branch for these two, so the frozen CLI refuses them with the subcommand
        // line before their own usage checks can run; here they reach their commands, which print their own usage.
        Some("assign") | Some("unassign") | Some("close") | Some("restart") => None,
        _ => Some("ticket needs a subcommand: infantry ticket show <id|prefix> | create --title \"...\" | update <id|prefix> ... | move <id|prefix> <status> | start <id|prefix> | link <id|prefix> <otherId|prefix> <type>".into()),
    }
}

// ---- dispatch ----

/// `ticket(...)`.
pub fn run(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    match p.arg(0) {
        Some("create") => create(ctx, p),
        Some("update") => update(ctx, p),
        Some("move") => move_ticket(ctx, p),
        Some("unassign") => unassign(ctx, p),
        Some("assign") => assign(ctx, p),
        Some("start") => start_ticket(ctx, p),
        Some("close") => close_ticket(ctx, p),
        Some("restart") => restart_ticket(ctx, p),
        Some("link") => link(ctx, p),
        Some("files") => files(ctx, p),
        Some("archive") | Some("restore") => archive_command(ctx, p),
        Some("comment") => comment(ctx, &args_from(p, 1), p.val("kind"), p.val("state")),
        Some("comments") => comments(ctx, p),
        Some("epic") => epic(ctx, &args_from(p, 1), p.flag("force")),
        Some("related") => related(ctx, p),
        _ => show(ctx, p),
    }
}

fn session_of(ctx: &Ctx) -> Option<String> {
    ctx.session_id()
}

/// `resolveTicketId`.
fn resolve_id(ctx: &mut Ctx, prefix: &str, project: &str) -> Result<Option<String>, String> {
    Ok(resolve_ticket(ctx, prefix, project)?.and_then(|t| s(&t, "id")))
}

fn ticket_get(ctx: &mut Ctx, id: &str) -> Result<Value, String> {
    let answer = ctx.stream().request(&Envelope::new("ticket_get", id), "ticket", "ticket_get", true)?;
    decode_ticket(&answer.data)
}

/// `roleAgentId`.
pub fn role_agent_id(role: Option<&str>) -> Option<String> {
    role.map(|r| {
        let id = r.trim().to_lowercase();
        if id.starts_with("role:") {
            id
        } else {
            format!("role:{id}")
        }
    })
}

/// The `--effort`, `--context-limit`, `--runner`, `--mcp` values as `TicketSave` sends them.
fn effort_value(p: &Parsed) -> Option<String> {
    p.val("effort").map(|e| parse_effort(e).ok().flatten().unwrap_or_default())
}
fn context_value(p: &Parsed) -> Option<i64> {
    p.val("context-limit").map(|c| parse_context_limit(c).ok().flatten().unwrap_or(0))
}
fn runner_value(p: &Parsed) -> Option<String> {
    p.val("runner").map(|r| r.trim().to_lowercase())
}
fn mcp_value(p: &Parsed) -> Value {
    match parse_mcp(&p.vals("mcp")).ok().flatten() {
        None => Value::Null,
        Some(req) => json!({"require": req.into_iter().map(|(server, purpose)| json!({"server": server, "capability": null, "purpose": purpose})).collect::<Vec<_>>()}),
    }
}

/// A `TicketSave` with kotlinx's `encodeDefaults = true`: every field written, nulls included.
#[derive(Default)]
struct Save {
    id: Option<String>,
    title: Option<String>,
    description: Option<String>,
    project_id: Option<String>,
    epic_id: Option<String>,
    agent_id: Option<String>,
    provider: Option<String>,
    model: Option<String>,
    images: Option<Vec<String>>,
    weight: Option<i32>,
    context_limit: Option<i64>,
    effort: Option<String>,
    runner: Option<String>,
    mcp: Value,
    status: Option<String>,
}

impl Save {
    fn encode(self) -> String {
        let mut m = Map::new();
        m.insert("id".into(), opt(self.id));
        m.insert("title".into(), opt(self.title));
        m.insert("description".into(), opt(self.description));
        m.insert("ticketType".into(), Value::Null);
        m.insert("projectId".into(), opt(self.project_id));
        m.insert("epicId".into(), opt(self.epic_id));
        m.insert("agentId".into(), opt(self.agent_id));
        m.insert("provider".into(), opt(self.provider));
        m.insert("model".into(), opt(self.model));
        m.insert("useWorktree".into(), Value::Null);
        m.insert("worktreeBranch".into(), Value::Null);
        m.insert("includeLinkedTasksContext".into(), Value::Null);
        m.insert("skillIds".into(), Value::Null);
        m.insert("skillsMode".into(), Value::Null);
        m.insert("includeSkillsContext".into(), Value::Null);
        m.insert("images".into(), self.images.map_or(Value::Null, |i| json!(i)));
        m.insert("weight".into(), self.weight.map_or(Value::Null, |w| json!(w)));
        m.insert("contextLimit".into(), self.context_limit.map_or(Value::Null, |c| json!(c)));
        m.insert("effort".into(), opt(self.effort));
        m.insert("runner".into(), opt(self.runner));
        m.insert("mcp".into(), self.mcp);
        m.insert("status".into(), opt(self.status));
        Value::Object(m).to_string()
    }
}

fn save(ctx: &mut Ctx, body: Save, idempotent: bool) -> Result<Value, String> {
    let answer = ctx.stream().request(&Envelope::new("ticket_save", body.encode()), "ticket", "ticket_save", idempotent)?;
    decode_ticket(&answer.data)
}

/// `storedRelatedBlock`.
pub fn stored_related_block(description: &str) -> Option<String> {
    if !description.starts_with(RELATED_BEGIN) {
        return None;
    }
    let stop = description.find(RELATED_END).filter(|s| *s > 0)?;
    Some(description[RELATED_BEGIN.len()..stop].trim().to_string())
}

/// `RelatedDescriptionBlock.strip`.
pub fn strip_related(description: &str) -> String {
    if !description.starts_with(RELATED_BEGIN) {
        return description.to_string();
    }
    let Some(end) = description.find(RELATED_END) else { return description.to_string() };
    let mut rest = end + RELATED_END.len();
    if description[rest..].starts_with("\n\n") {
        rest += 2;
    } else if description[rest..].starts_with('\n') {
        rest += 1;
    }
    description[rest..].to_string()
}

/// `relatedNote`.
pub fn related_note(ticket: &Value) -> Vec<String> {
    let Some(block) = stored_related_block(&s(ticket, "description").unwrap_or_default()) else { return vec![] };
    let lines: Vec<&str> = block.lines().collect();
    let mut out = vec![];
    if let Some(first) = lines.first().filter(|l| l.starts_with("RELATED (auto, ")) {
        let head = first.split(';').next().unwrap_or(first);
        out.push(format!("{head}) atop the description: read those files first"));
    }
    if let Some(series) = lines.iter().find(|l| l.starts_with("Series ")) {
        out.push(series.to_string());
    }
    out
}

/// `linkRelatedTo`: None after printing why.
fn link_related_to(ctx: &mut Ctx, ticket: Value, ids: &[String], project: &str) -> Result<Option<Value>, String> {
    let mut current = ticket;
    for given in ids {
        let Some(other) = resolve_id(ctx, given.trim(), project)? else { return Ok(None) };
        let req = json!({"ticketId": s(&current, "id").unwrap_or_default(), "relatedId": other, "type": "relates_to"});
        let answer = ctx.stream().request(&Envelope::new("ticket_link", req.to_string()), "ticket", "ticket_link", false)?;
        current = decode_ticket(&answer.data)?;
    }
    Ok(Some(current))
}

fn epic_for(ctx: &mut Ctx, p: &Parsed, project: Option<&str>) -> Result<Result<Option<String>, ()>, String> {
    match p.val("epic") {
        None => Ok(Ok(None)),
        Some(prefix) => Ok(resolve_epic_id(ctx, prefix.trim(), project)?.map(Some).ok_or(())),
    }
}

/// Comma and repeat separated values of a flag.
fn csv(p: &Parsed, flag: &str) -> Vec<String> {
    p.vals(flag).iter().flat_map(|v| v.split(',').map(|x| x.trim().to_string()).collect::<Vec<_>>()).filter(|x| !x.is_empty()).collect()
}

/// `--notify` / `--add-notify` / `--remove-notify`: agent ids, session ids and `role:<name>`, saved through the
/// board plugin's `infantry_ticket_update` (the notify list lives in its tables). A created ticket always lists its
/// creator's session (INFANTRY_SESSION_ID). Err = printed, exit code.
fn save_notify(ctx: &mut Ctx, p: &Parsed, ticket_id: &str, created: bool) -> Result<Option<String>, i32> {
    let (set, add, remove) = (csv(p, "notify"), csv(p, "add-notify"), csv(p, "remove-notify"));
    let me = ctx.env("INFANTRY_SESSION_ID").map(|v| v.trim().to_string()).filter(|v| !v.is_empty());
    let creator = |ctx: &mut Ctx| {
        // Best effort: a session this daemon does not know cannot be listed, and that never fails the create.
        if let (true, Some(me)) = (created, me.clone()) {
            let mut a = Map::new();
            a.insert("ticketId".into(), Value::String(ticket_id.to_string()));
            a.insert("addNotify".into(), Value::Array(vec![Value::String(me)]));
            let _ = plugin_tool_call(ctx, "infantry_ticket_update", a);
        }
    };
    if set.is_empty() && add.is_empty() && remove.is_empty() {
        creator(ctx);
        return Ok(None);
    }
    let arr = |v: &[String]| Value::Array(v.iter().map(|x| Value::String(x.clone())).collect());
    let mut a = Map::new();
    a.insert("ticketId".into(), Value::String(ticket_id.to_string()));
    if !set.is_empty() {
        a.insert("notify".into(), arr(&set));
    }
    if !add.is_empty() {
        a.insert("addNotify".into(), arr(&add));
    }
    if !remove.is_empty() {
        a.insert("removeNotify".into(), arr(&remove));
    }
    let done = plugin_tool_call(ctx, "infantry_ticket_update", a);
    creator(ctx);
    match done {
        Ok((text, false)) => Ok(Some(text)),
        Ok((text, true)) => {
            ctx.err(format!("error: notify list not saved: {text}"));
            Err(1)
        }
        Err(e) => {
            ctx.err(format!("error: notify list not saved: {e}"));
            Err(1)
        }
    }
}

/// Whether the daemon lists a project with this exact id (a ticket under an unknown project is refused).
fn project_known(ctx: &mut Ctx, id: &str) -> Result<bool, String> {
    let answer = ctx.stream().request(&Envelope::new("project_list", ""), "projects", "project_list", true)?;
    let rows: Vec<Value> = serde_json::from_str(&answer.data).map_err(|e| format!("malformed projects answer from the daemon: {e}"))?;
    Ok(rows.iter().any(|r| r.get("id").and_then(Value::as_str) == Some(id.trim())))
}

/// `createTicket`.
fn create(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let title = p.val("title").unwrap_or("").trim().to_string();
    let project = Some(board_project(p, ctx.getenv)).filter(|x| !x.trim().is_empty());
    if let Some(pid) = project.as_deref() {
        if !project_known(ctx, pid)? {
            ctx.err(format!("error: unknown project '{}': `infantry project list` shows the project ids", pid.chars().take(80).collect::<String>()));
            return Ok(2);
        }
    }
    let Ok(epic_id) = epic_for(ctx, p, project.as_deref())? else { return Ok(2) };
    let paths = p.vals("image");
    let images = if paths.is_empty() {
        None
    } else {
        match attach_images(ctx, project.as_deref(), &paths)? {
            Some(i) => Some(i),
            None => return Ok(1),
        }
    };
    let had_images = images.is_some();
    let ticket = save(
        ctx,
        Save {
            status: trimmed(p.val("status")).map(|x| x.to_lowercase()),
            title: Some(title),
            description: p.description.clone(),
            project_id: project.clone(),
            epic_id,
            agent_id: role_agent_id(p.val("role")),
            provider: trimmed(p.val("provider")),
            model: trimmed(p.val("model")),
            weight: p.weight,
            context_limit: context_value(p),
            effort: effort_value(p),
            runner: runner_value(p),
            mcp: mcp_value(p),
            images,
            ..Default::default()
        },
        false,
    )?;
    let count = ticket.get("images").and_then(|i| i.as_array()).map_or(0, |i| i.len());
    let id = s(&ticket, "id").unwrap_or_default();
    ctx.out(format!("created {}{}", short_id(&id), if had_images { format!(" ({count} image(s))") } else { String::new() }));
    match save_notify(ctx, p, &id, true) {
        Ok(Some(t)) => ctx.out(t),
        Ok(None) => {}
        Err(code) => return Ok(code),
    }
    let Some(linked) = link_related_to(ctx, ticket, &p.vals("related-to"), project.as_deref().unwrap_or(""))? else { return Ok(1) };
    for l in related_note(&linked) {
        ctx.out(l);
    }
    Ok(0)
}

/// `updateTicket`.
fn update(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let project = Some(board_project(p, ctx.getenv)).filter(|x| !x.trim().is_empty());
    let Some(full) = resolve_id(ctx, p.args[1].trim(), project.as_deref().unwrap_or(""))? else { return Ok(1) };
    let Ok(epic_id) = epic_for(ctx, p, project.as_deref())? else { return Ok(2) };
    let (add, remove) = (p.vals("image"), p.vals("remove-image"));
    let images = if add.is_empty() && remove.is_empty() {
        None
    } else {
        match edit_images(ctx, &full, &add, &remove)? {
            Some(i) => Some(i),
            None => return Ok(1),
        }
    };
    let had_images = images.is_some();
    let had_epic = epic_id.is_some();
    let ticket = save(
        ctx,
        Save {
            id: Some(full),
            title: trimmed(p.val("title")),
            description: p.description.clone(),
            epic_id,
            agent_id: role_agent_id(p.val("role")),
            provider: trimmed(p.val("provider")),
            model: trimmed(p.val("model")),
            weight: p.weight,
            context_limit: context_value(p),
            effort: effort_value(p),
            runner: runner_value(p),
            mcp: mcp_value(p),
            images,
            ..Default::default()
        },
        false,
    )?;
    let dash = |v: Option<String>| v.unwrap_or_else(|| "-".into());
    let mut saved: Vec<String> = vec![];
    if p.val("title").is_some() {
        saved.push("title".into());
    }
    if p.description.is_some() {
        saved.push("description".into());
    }
    if had_epic {
        saved.push(format!("epic {}", dash(s(&ticket, "epicId").map(|e| short_id(&e)))));
    }
    if p.val("role").is_some() {
        saved.push(format!("role {}", dash(s(&ticket, "agentId").map(|a| a.strip_prefix("role:").unwrap_or(&a).to_string()))));
    }
    if p.val("provider").is_some() {
        saved.push(format!("provider {}", dash(s(&ticket, "provider"))));
    }
    if p.val("model").is_some() {
        saved.push(format!("model {}", dash(s(&ticket, "model"))));
    }
    if p.weight.is_some() {
        saved.push(format!("weight {}", dash(n(&ticket, "weight").map(|w| w.to_string()))));
    }
    if p.val("context-limit").is_some() {
        saved.push(format!("context limit {}", dash(n(&ticket, "contextLimit").map(context_label))));
    }
    if p.val("effort").is_some() {
        saved.push(format!("effort {}", dash(s(&ticket, "effort"))));
    }
    if p.val("runner").is_some() {
        saved.push(format!("runner {}", dash(s(&ticket, "runner"))));
    }
    if !p.vals("mcp").is_empty() {
        let servers = mcp_servers(ticket.get("mcp")).join(",");
        saved.push(format!("mcp {}", if servers.is_empty() { "-".to_string() } else { servers }));
    }
    if had_images {
        saved.push(format!("{} image(s)", ticket.get("images").and_then(|i| i.as_array()).map_or(0, |i| i.len())));
    }
    let related_to = p.vals("related-to");
    let id = s(&ticket, "id").unwrap_or_default();
    match save_notify(ctx, p, &id, false) {
        Ok(Some(_)) => saved.push("notify".into()),
        Ok(None) => {}
        Err(code) => return Ok(code),
    }
    let Some(linked) = link_related_to(ctx, ticket, &related_to, project.as_deref().unwrap_or(""))? else { return Ok(1) };
    if !related_to.is_empty() {
        saved.push(format!("related {}", related_to.len()));
    }
    ctx.out(format!("updated {} (saved: {})", short_id(&id), saved.join(", ")));
    for l in related_note(&linked) {
        ctx.out(l);
    }
    Ok(0)
}

/// `TicketMcp.servers()` of a ticket's `mcp` (an object, or its stored JSON text).
fn mcp_servers(v: Option<&Value>) -> Vec<String> {
    let parsed = match v {
        Some(Value::String(t)) => serde_json::from_str::<Value>(t).ok(),
        Some(o @ Value::Object(_)) => Some(o.clone()),
        _ => None,
    };
    let mut out: Vec<String> = vec![];
    for r in parsed.as_ref().and_then(|m| m.get("require")).and_then(|r| r.as_array()).into_iter().flatten() {
        if let Some(sv) = s(r, "server").map(|x| x.trim().to_string()).filter(|x| !x.is_empty()) {
            if !out.contains(&sv) {
                out.push(sv);
            }
        }
    }
    out
}

/// `showTicket`.
fn show(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let project = board_project(p, ctx.getenv);
    let Some(full) = resolve_id(ctx, p.args[1].trim(), &project)? else { return Ok(1) };
    let t = ticket_get(ctx, &full)?;
    let dash = |k: &str| s(&t, k).unwrap_or_else(|| "-".into());
    let mut lines = vec![
        format!("ID: {}", dash("id")),
        format!("TITLE: {}", s(&t, "title").unwrap_or_default()),
        format!("STATUS: {}", s(&t, "status").unwrap_or_default()),
        format!("AGENT: {}", dash("agentId")),
        format!("PROVIDER: {}", dash("provider")),
        format!("MODEL: {}", dash("model")),
        format!(
            "WEIGHT: {}",
            n(&t, "weight").map_or("- (unweighted: set one with ticket update --weight 1-5 before start)".to_string(), |w| w.to_string())
        ),
    ];
    if let Some(e) = t.get("effectiveExecutor").filter(|e| !e.is_null()) {
        let part = |k: &str, f: &str| e.get(k).and_then(|x| s(x, f)).unwrap_or_else(|| "-".into());
        lines.push(format!(
            "EFFECTIVE: {} ({}) / {} ({}){}",
            part("provider", "value"),
            part("provider", "source"),
            part("model", "value"),
            part("model", "source"),
            s(e, "rule").map_or(String::new(), |r| format!(" via rule '{r}'"))
        ));
    }
    if let Some(c) = t.get("effectiveContextLimit").filter(|e| !e.is_null()) {
        lines.push(format!(
            "CONTEXT LIMIT: {} ({}){}",
            n(c, "value").map_or("- (none)".to_string(), context_label),
            s(c, "source").unwrap_or_default(),
            s(c, "support").map_or(String::new(), |x| format!(" / harness: {x}"))
        ));
    }
    if let Some(e) = t.get("effectiveEffort").filter(|e| !e.is_null()) {
        lines.push(format!(
            "EFFORT: {} ({}){}",
            s(e, "value").unwrap_or_else(|| "- (harness default)".into()),
            s(e, "source").unwrap_or_default(),
            s(e, "support").map_or(String::new(), |x| format!(" / harness: {x}"))
        ));
    }
    lines.push(format!("PROJECT: {}", dash("projectId")));
    lines.push(format!("EPIC: {}", dash("epicId")));
    lines.push(format!("BRANCH: {}", dash("branch")));
    lines.push(format!("BASE: {}", dash("base")));
    let links = t.get("links").and_then(|l| l.as_array()).cloned().unwrap_or_default();
    if links.is_empty() {
        lines.push("LINKS: none".into());
    } else {
        lines.push("LINKS:".into());
        for l in &links {
            lines.push(format!("  {} {}", s(l, "type").unwrap_or_default(), s(l, "ticketId").unwrap_or_default()));
        }
    }
    let images: Vec<String> =
        t.get("images").and_then(|i| i.as_array()).map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect()).unwrap_or_default();
    for l in lines.drain(..) {
        ctx.out(l);
    }
    if images.is_empty() {
        ctx.out("IMAGES: none");
    } else {
        let directory = match s(&t, "projectId") {
            Some(id) => ctx
                .stream()
                .request(&Envelope::new("project_get", id), "project", "project_get", true)
                .ok()
                .and_then(|a| serde_json::from_str::<Value>(&a.data).ok())
                .and_then(|v| s(&v, "directory"))
                .filter(|d| !d.trim().is_empty()),
            None => None,
        };
        ctx.out("IMAGES:");
        for image in &images {
            let shown = directory.as_ref().map_or(image.clone(), |d| normalize(&absolute(&Path::new(d).join(image))).display().to_string());
            ctx.out(format!("  {shown}"));
        }
    }
    ctx.out("DESCRIPTION:");
    let description = s(&t, "description").unwrap_or_default();
    let shown = if p.flag("related") { description } else { strip_related(&description) };
    ctx.out(if shown.trim().is_empty() { "(none)".to_string() } else { shown });
    Ok(0)
}

fn absolute(p: &Path) -> PathBuf {
    if p.is_absolute() {
        p.to_path_buf()
    } else {
        std::env::current_dir().map(|c| c.join(p)).unwrap_or_else(|_| p.to_path_buf())
    }
}

/// `File.normalize()`: `.` dropped, `..` folded, no filesystem access.
fn normalize(p: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for c in p.components() {
        match c {
            Component::CurDir => {}
            Component::ParentDir => {
                if !out.pop() {
                    out.push("..");
                }
            }
            other => out.push(other.as_os_str()),
        }
    }
    out
}

/// `linkTicket`.
fn link(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let project = board_project(p, ctx.getenv);
    let Some(from) = resolve_id(ctx, p.args[1].trim(), &project)? else { return Ok(1) };
    let Some(to) = resolve_id(ctx, p.args[2].trim(), &project)? else { return Ok(1) };
    let kind = p.args[3].trim().to_string();
    let req = json!({"ticketId": from, "relatedId": to, "type": kind});
    ctx.stream().request(&Envelope::new("ticket_link", req.to_string()), "ticket", "ticket_link", false)?;
    ctx.out(format!("linked {from} {kind} {to}"));
    Ok(0)
}

/// `relatedTicket`.
fn related(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let project = board_project(p, ctx.getenv);
    let Some(found) = resolve_ticket(ctx, p.args[1].trim(), &project)? else { return Ok(1) };
    let suggest = p.flag("suggest");
    let ticket = if suggest {
        save(ctx, Save { id: s(&found, "id"), title: s(&found, "title"), ..Default::default() }, true)?
    } else {
        ticket_get(ctx, &s(&found, "id").unwrap_or_default())?
    };
    let short = short_id(&s(&ticket, "id").unwrap_or_default());
    let line = stored_related_block(&s(&ticket, "description").unwrap_or_default()).unwrap_or_else(|| {
        format!(
            "{short}: no RELATED block (nothing related found{}). Files: infantry ticket files {short} --related",
            if suggest { String::new() } else { format!("; `infantry ticket related {short} --suggest` rebuilds it") }
        )
    });
    ctx.out(line);
    Ok(0)
}

/// `unassignTicket`.
fn unassign(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let Some(id) = p.arg(1).map(|a| a.trim().to_string()).filter(|a| !a.is_empty()) else {
        ctx.err("usage: infantry ticket unassign <id|prefix> [--now] [--note <text>]");
        return Ok(1);
    };
    let project = board_project(p, ctx.getenv);
    let Some(ticket) = resolve_ticket(ctx, &id, &project)? else { return Ok(1) };
    let tid = s(&ticket, "id").unwrap_or_default();
    let req = json!({"id": tid, "now": p.flag("now"), "note": p.val("note")});
    let env = Envelope::new("ticket_unassign", req.to_string()).with_session(session_of(ctx));
    let answer = ctx.stream().request(&env, "ticket", "ticket_unassign", false)?;
    let moved = decode_ticket(&answer.data)?;
    ctx.out(format!("unassigned {tid} -> {}", s(&moved, "status").unwrap_or_default()));
    Ok(0)
}


/// PIPE-CLOSE-1: `infantry ticket close <id|prefix> [--summary <text>] [--keep-worktree]`: done, the worker stopped and
/// removed, the clean merged worktree removed, the lane's next ticket started, all in one call.
fn close_ticket(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let Some(id) = p.arg(1).map(|a| a.trim().to_string()).filter(|a| !a.is_empty()) else {
        ctx.err("usage: infantry ticket close <id|prefix> [--summary <text>] [--keep-worktree]");
        return Ok(1);
    };
    let project = board_project(p, ctx.getenv);
    let Some(ticket) = resolve_ticket(ctx, &id, &project)? else { return Ok(1) };
    let tid = s(&ticket, "id").unwrap_or_default();
    let req = json!({"ticketId": tid, "summary": p.val("summary"), "keepWorktree": p.flag("keep-worktree"), "closedBy": session_of(ctx)});
    let env = Envelope::new("ticket_close", req.to_string()).with_session(session_of(ctx));
    let answer = ctx.stream().request(&env, "ticket_closed", "ticket_close", false)?;
    let c: Value = serde_json::from_str(&answer.data).map_err(|e| format!("ticket_closed answer is not JSON: {e}"))?;
    ctx.out(format!("closed {tid} -> {}", c.get("ticket").and_then(|t| s(t, "status")).unwrap_or_default()));
    ctx.out(format!("  worker:   {}", s(&c, "workerRemoved").map(|w| format!("{w} stopped and removed")).unwrap_or_else(|| "none".into())));
    ctx.out(format!("  worktree: {}", s(&c, "worktreeRemoved").map(|w| format!("{w} removed")).unwrap_or_else(|| "kept".into())));
    ctx.out(format!("  next:     {}", c.get("next").filter(|n| !n.is_null()).and_then(|n| s(n, "id")).unwrap_or_else(|| "none started".into())));
    for w in c.get("warnings").and_then(|w| w.as_array()).into_iter().flatten().filter_map(|w| w.as_str()) {
        ctx.out(format!("  warning:  {w}"));
    }
    Ok(0)
}

/// PIPE-RESTART-1: `infantry ticket restart <id|prefix> [--fresh]`: puts a worker back on an in_progress ticket whose
/// worker died. It resumes the dead session; if that dies inside `board.restartResumeSeconds` (or `--fresh` is given) a
/// fresh session starts on the same worktree and branch with the brief and a continue-from-your-last-commit note.
fn restart_ticket(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let Some(id) = p.arg(1).map(|a| a.trim().to_string()).filter(|a| !a.is_empty()) else {
        ctx.err("usage: infantry ticket restart <id|prefix> [--fresh]");
        return Ok(1);
    };
    let project = board_project(p, ctx.getenv);
    let Some(ticket) = resolve_ticket(ctx, &id, &project)? else { return Ok(1) };
    let tid = s(&ticket, "id").unwrap_or_default();
    let req = json!({"ticketId": tid, "fresh": p.flag("fresh"), "restartedBy": session_of(ctx)});
    let env = Envelope::new("ticket_restart", req.to_string()).with_session(session_of(ctx));
    let answer = ctx.stream().request(&env, "ticket_restarted", "ticket_restart", false)?;
    let r: Value = serde_json::from_str(&answer.data).map_err(|e| format!("ticket_restarted answer is not JSON: {e}"))?;
    let mode = s(&r, "mode").unwrap_or_default();
    ctx.out(format!("restarted {tid} ({mode}): {}", s(&r, "detail").unwrap_or_default()));
    ctx.out(format!("  session:  {}", s(&r, "sessionId").unwrap_or_else(|| "none".into())));
    if let Some(prev) = s(&r, "previousSessionId").filter(|prev| s(&r, "sessionId").as_deref() != Some(prev.as_str())) {
        ctx.out(format!("  replaced: {prev}"));
    }
    Ok(0)
}

/// `printStarted`.
fn started_line(moved: &Value, label: &str) -> String {
    let deferred = moved
        .get("start")
        .filter(|st| s(st, "reason").as_deref() == Some("deferred"))
        .and_then(|st| s(st, "detail"))
        .map_or(String::new(), |d| format!(" ({d})"));
    format!("{label} {} -> {}{deferred}", s(moved, "id").unwrap_or_default(), s(moved, "status").unwrap_or_default())
}

/// `assignTicket`.
fn assign(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let id = p.arg(1).map(|a| a.trim().to_string()).filter(|a| !a.is_empty());
    let agent = p.arg(2).map(|a| a.trim().to_string()).filter(|a| !a.is_empty());
    let (Some(id), Some(agent)) = (id, agent) else {
        ctx.err("usage: infantry ticket assign <id|prefix> <agent> [--queue] [--at n]");
        return Ok(1);
    };
    let project = board_project(p, ctx.getenv);
    let Some(ticket) = resolve_ticket(ctx, &id, &project)? else { return Ok(1) };
    let req = json!({"id": s(&ticket, "id"), "agentId": agent, "queue": p.flag("queue"), "at": p.queue_at});
    let answer = ctx.stream().request(&Envelope::new("ticket_assign", req.to_string()), "ticket", "ticket_assign", false)?;
    let moved = decode_ticket(&answer.data)?;
    ctx.out(started_line(&moved, "assigned"));
    Ok(0)
}

// ---- TicketPlacement ----

/// `TicketPlacement.resolve`: the index to send, or why it cannot be had.
pub fn resolve_placement(p: &Parsed, ticket: &Value, status: Option<&str>, board: &[Value]) -> Result<i64, String> {
    let (top, bottom, before, after) = (p.flag("top"), p.flag("bottom"), p.val("before"), p.val("after"));
    let position = p.val("position").and_then(|x| x.trim().parse::<i64>().ok());
    let named = [top, bottom, before.is_some(), after.is_some(), position.is_some()].iter().filter(|x| **x).count();
    if named > 1 {
        return Err("name only one of top, bottom, before, after, position".into());
    }
    if top {
        return Ok(0);
    }
    if bottom {
        return Ok(i32::MAX as i64);
    }
    if let Some(pos) = position {
        return if pos < 0 { Err(format!("position must be 0 or more, was {pos}")) } else { Ok(pos) };
    }
    let Some(r) = before.or(after) else { return Err("no placement given".into()) };
    let word = if before.is_some() { "before" } else { "after" };
    let matches = match_prefix(r.trim(), board);
    let target = match matches.len() {
        1 => matches[0],
        0 => return Err(format!("no ticket {r} on this board (the {word} target)")),
        _ => return Err(format!("ticket id '{r}' is ambiguous; use a longer id or the full id")),
    };
    let (tid, own) = (s(target, "id").unwrap_or_default(), s(ticket, "id").unwrap_or_default());
    if tid == own {
        return Err(format!("a ticket cannot be moved {word} itself"));
    }
    let own_status = s(ticket, "status").unwrap_or_default();
    let column = status.map(str::to_lowercase).unwrap_or_else(|| own_status.clone());
    let target_status = s(target, "status").unwrap_or_default();
    if target_status != column {
        return Err(format!("{tid} is in {target_status}, not {column}: name the column or pick a ticket in it"));
    }
    let (tpos, opos) = (n(target, "position").unwrap_or(0), n(ticket, "position").unwrap_or(0));
    let lifted = if own_status == column && opos < tpos { 1 } else { 0 };
    Ok(tpos - lifted + if after.is_some() { 1 } else { 0 })
}

/// `moveTicket`.
fn move_ticket(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let project = board_project(p, ctx.getenv);
    let Some(ticket) = resolve_ticket(ctx, p.args[1].trim(), &project)? else { return Ok(1) };
    let full = s(&ticket, "id").unwrap_or_default();
    let status = p.arg(2).map(|x| x.trim().to_string());
    let session = session_of(ctx);
    if status.as_deref().map(str::to_lowercase).as_deref() == Some(IN_PROGRESS) && s(&ticket, "status").as_deref() != Some(IN_PROGRESS) {
        return match start(ctx, &ticket, session)? {
            Outcome::Started { ticket: t, note, warning, .. } => {
                ctx.out(format!("moved {full} -> {}", s(&t, "status").unwrap_or_default()));
                start_notes(ctx, note, warning);
                Ok(0)
            }
            Outcome::AlreadyInProgress { ticket: t, .. } => {
                ctx.out(format!("moved {full} -> {}", s(&t, "status").unwrap_or_default()));
                Ok(0)
            }
            other => Ok(report_not_started(ctx, other)),
        };
    }
    let named = p.flag("top") || p.flag("bottom") || p.val("before").is_some() || p.val("after").is_some() || p.val("position").is_some();
    let position = if named {
        let board = if p.val("before").is_some() || p.val("after").is_some() { fetch_board(ctx, &project, None)? } else { vec![] };
        match resolve_placement(p, &ticket, status.as_deref(), &board) {
            Ok(v) => Some(v),
            Err(m) => {
                ctx.err(format!("error: {m}"));
                return Ok(1);
            }
        }
    } else {
        None
    };
    if status.as_deref().map(str::to_lowercase).as_deref() == Some("done") && s(&ticket, "status").as_deref() != Some("done") {
        if let Some(w) = done_comment_warning(ctx, &full) {
            ctx.err(w);
        }
    }
    let req = json!({"id": full, "status": status, "position": position, "wontFix": null});
    let env = Envelope::new("ticket_move", req.to_string()).with_session(session);
    let answer = ctx.stream().request(&env, "ticket", "ticket_move", false)?;
    let moved = decode_ticket(&answer.data)?;
    ctx.out(format!("moved {full} -> {}", s(&moved, "status").unwrap_or_default()));
    Ok(0)
}

/// A warning when [id] has no done comment yet (a move to done should follow one); None when it has one or the
/// board cannot say (the move goes ahead either way).
fn done_comment_warning(ctx: &mut Ctx, id: &str) -> Option<String> {
    let mut a = Map::new();
    a.insert("ticketId".into(), Value::String(id.to_string()));
    a.insert("kind".into(), Value::String("done".into()));
    a.insert("which".into(), Value::String("latest".into()));
    let (text, is_error) = plugin_tool_call(ctx, "infantry_ticket_comments", a).ok()?;
    let short = short_id(id);
    no_done_comment(&text, is_error)
        .then(|| format!("warning: {short} has no done comment yet: done comment first (infantry ticket comment {short} --kind done \"<summary>\")"))
}

/// True when the comments answer says there is no done comment.
pub fn no_done_comment(text: &str, is_error: bool) -> bool {
    !is_error && text.trim() == "no comments"
}

// ---- TicketStarter ----

pub enum Outcome {
    Started { ticket: Value, session: Option<Value>, warning: Option<String>, note: Option<String> },
    AlreadyInProgress { ticket: Value, session: Option<Value> },
    Queued { ticket: Value, lane: Option<String> },
    Held { ticket: Value, detail: Option<String> },
    Refused { ticket: Value, detail: String },
}

/// `SessionRecord.isBusy`.
fn is_busy(worker: &Value) -> bool {
    matches!(s(worker, "state").as_deref(), Some("starting") | Some("busy") | Some("waiting_input"))
}

/// `TicketWorkerHealth.problem` is null: the worker can carry the ticket.
fn worker_fine(worker: Option<&Value>) -> bool {
    worker.is_some_and(|w| is_busy(w) || w.get("providerSessionId").is_some_and(|x| !x.is_null()))
}

fn find_session(list: &[Value], id: Option<&str>) -> Option<Value> {
    let id = id?;
    list.iter().find(|x| s(x, "id").as_deref() == Some(id)).cloned()
}

/// `TicketStarter.startedDetail`: (note, warning).
pub fn started_detail(detail: Option<&str>) -> (Option<String>, Option<String>) {
    let text = detail.unwrap_or("").trim();
    match text.find(LAUNCH_WARNING) {
        None => (Some(text.to_string()).filter(|t| !t.is_empty()), None),
        Some(at) => {
            let note = text[..at].trim();
            let note = note.strip_suffix(';').unwrap_or(note).trim();
            (Some(note.to_string()).filter(|t| !t.is_empty()), Some(text[at..].to_string()))
        }
    }
}

/// `TicketStarter.start`: a `ticket_move` to in_progress, then this ticket's `ticket` frame that carries `start`.
pub fn start(ctx: &mut Ctx, ticket: &Value, origin: Option<String>) -> Result<Outcome, String> {
    let tid = s(ticket, "id").unwrap_or_default();
    if s(ticket, "status").as_deref() == Some(IN_PROGRESS) {
        let recorded = s(ticket, "sessionId");
        let worker = match &recorded {
            Some(_) => find_session(&sessions(ctx)?, recorded.as_deref()),
            None => None,
        };
        if worker_fine(worker.as_ref()) {
            return Ok(Outcome::AlreadyInProgress { ticket: ticket.clone(), session: worker });
        }
    }
    let mv = json!({"id": tid, "status": IN_PROGRESS, "position": null, "wontFix": null});
    let timeout = ctx.stream().answer_timeout_ms();
    ctx.stream().send(&Envelope::new("ticket_move", mv.to_string()).with_session(origin))?;
    ctx.stream().take_reconnected();
    let deadline = Instant::now() + Duration::from_millis(timeout);
    while Instant::now() < deadline {
        let Some(frame) = ctx.stream().next(POLL_MS)? else {
            if ctx.stream().take_reconnected() {
                return Ok(Outcome::Refused { ticket: ticket.clone(), detail: LOST.into() });
            }
            continue;
        };
        if frame.kind == "ticket" {
            let Ok(answered) = serde_json::from_str::<Value>(&frame.data) else { continue };
            let Some(st) = answered.get("start").filter(|x| !x.is_null()).cloned() else { continue };
            if s(&answered, "id").as_deref() == Some(tid.as_str()) {
                return outcome_of(ctx, answered, &st);
            }
        } else if frame.kind == "error" && frame.session_id.is_none() && !frame.data.starts_with(LAUNCH_WARNING) {
            return Ok(Outcome::Refused { ticket: ticket.clone(), detail: frame.data });
        }
    }
    Ok(Outcome::Refused {
        ticket: ticket.clone(),
        detail: format!("the daemon did not answer the start within {timeout}ms; read the ticket again before retrying"),
    })
}

fn outcome_of(ctx: &mut Ctx, answered: Value, st: &Value) -> Result<Outcome, String> {
    let ok = st.get("ok").and_then(|x| x.as_bool()).unwrap_or(false);
    let reason = s(st, "reason").unwrap_or_default();
    let detail = s(st, "detail");
    let sid = s(&answered, "sessionId");
    Ok(if ok {
        let listed = sessions(ctx)?;
        let (note, warning) = started_detail(detail.as_deref());
        Outcome::Started { session: find_session(&listed, sid.as_deref()), ticket: answered, warning, note }
    } else if reason == ALREADY_IN_PROGRESS {
        let session = match &sid {
            Some(_) => find_session(&sessions(ctx)?, sid.as_deref()),
            None => None,
        };
        Outcome::AlreadyInProgress { ticket: answered, session }
    } else if reason == "queued" {
        Outcome::Queued { ticket: answered, lane: detail }
    } else if reason == "held" {
        Outcome::Held { ticket: answered, detail }
    } else {
        Outcome::Refused { ticket: answered, detail: detail.unwrap_or(reason) }
    })
}

/// `printStartNotes`.
fn start_notes(ctx: &mut Ctx, note: Option<String>, warning: Option<String>) {
    if let Some(n) = note {
        ctx.out(format!("  note:     {n}"));
    }
    if let Some(w) = warning {
        ctx.out(format!("  warning:  {w}"));
    }
}

/// `reportNotStarted`.
fn report_not_started(ctx: &mut Ctx, outcome: Outcome) -> i32 {
    match outcome {
        Outcome::Queued { ticket, lane } => {
            let lane = s(&ticket, "queuedFor").or(lane).map_or(String::new(), |l| format!(" for {l}"));
            ctx.out(format!(
                "queued {}{lane}: every agent of its lane is busy; the board starts it when one frees up",
                s(&ticket, "id").unwrap_or_default()
            ));
            0
        }
        Outcome::Held { ticket, detail } => {
            ctx.err(format!(
                "error: ticket {} is held by open prerequisites{}; it starts once they are done",
                s(&ticket, "id").unwrap_or_default(),
                detail.map_or(String::new(), |d| format!(" ({d})"))
            ));
            1
        }
        Outcome::Refused { ticket, detail } => {
            ctx.err(format!("error: ticket {} did not start: {detail}", s(&ticket, "id").unwrap_or_default()));
            1
        }
        _ => 0,
    }
}

/// `worktreeLine`.
pub fn worktree_line(worker: &Value) -> String {
    let runner = s(worker, "runner").unwrap_or_default();
    if runner.trim().is_empty() || runner == "local" {
        return s(worker, "worktreePath").or_else(|| s(worker, "workingDir")).unwrap_or_default();
    }
    match s(worker, "worktreePath") {
        Some(p) => format!("{p} (on runner {runner})"),
        None => format!("(on runner {runner}; its path was not reported)"),
    }
}

/// `printTicketWorker`.
pub fn ticket_worker_lines(ticket: &Value, worker: Option<&Value>) -> Vec<String> {
    let Some(w) = worker else {
        let mut out = vec![format!("  session:  {}", s(ticket, "sessionId").unwrap_or_else(|| "(not listed by the daemon yet)".into()))];
        if let Some(b) = s(ticket, "branch") {
            out.push(format!("  branch:   {b}"));
        }
        return out;
    };
    let d = |k: &str| s(w, k).unwrap_or_else(|| "-".into());
    vec![
        format!("  session:  {}", s(w, "id").unwrap_or_default()),
        format!("  role:     {} (agent {})", d("role"), d("agentId")),
        format!("  harness:  {}", s(w, "provider").unwrap_or_default()),
        format!("  model:    {}", s(w, "model").unwrap_or_else(|| "(the harness's default)".into())),
        format!("  branch:   {}", s(w, "worktreeBranch").or_else(|| s(ticket, "branch")).unwrap_or_else(|| "-".into())),
        format!("  worktree: {}", worktree_line(w)),
    ]
}

/// `startTicket`.
fn start_ticket(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let project = board_project(p, ctx.getenv);
    let Some(ticket) = resolve_ticket(ctx, p.args[1].trim(), &project)? else { return Ok(1) };
    let session = session_of(ctx);
    Ok(match start(ctx, &ticket, session)? {
        Outcome::Started { ticket: t, session, warning, note } => {
            ctx.out(format!("started {} -> {}", s(&t, "id").unwrap_or_default(), s(&t, "status").unwrap_or_default()));
            for l in ticket_worker_lines(&t, session.as_ref()) {
                ctx.out(l);
            }
            start_notes(ctx, note, warning);
            0
        }
        Outcome::AlreadyInProgress { ticket: t, session } => {
            ctx.out(format!("already in progress {}: nothing new was started", s(&t, "id").unwrap_or_default()));
            for l in ticket_worker_lines(&t, session.as_ref()) {
                ctx.out(l);
            }
            0
        }
        other => report_not_started(ctx, other),
    })
}

// ---- TicketImageFiles ----

/// `TicketImages.isStored`.
pub fn is_stored(path: &str) -> bool {
    let slashed = path.trim().replace('\\', "/");
    slashed.starts_with(TICKET_UPLOADS) || slashed.contains(&format!("/{TICKET_UPLOADS}"))
}

/// `TicketImages.isImage`.
pub fn is_image(b: &[u8]) -> bool {
    b.starts_with(&[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
        || b.starts_with(&[0xFF, 0xD8, 0xFF])
        || b.starts_with(b"GIF87a")
        || b.starts_with(b"GIF89a")
        || (b.starts_with(b"RIFF") && b.len() >= 12 && &b[8..12] == b"WEBP")
}

fn take(t: &str, k: usize) -> String {
    t.chars().take(k).collect()
}

/// (size, bytes) of a local file; bytes only when within [IMAGE_MAX_BYTES].
fn local(path: &str) -> Option<(u64, Option<Vec<u8>>)> {
    let meta = std::fs::metadata(path.trim()).ok().filter(|m| m.is_file())?;
    let size = meta.len();
    Some((size, if size <= IMAGE_MAX_BYTES { std::fs::read(path.trim()).ok() } else { None }))
}

/// `TicketImageFiles.check`.
pub fn check_images(paths: &[String]) -> Option<String> {
    for raw in paths {
        let path = raw.trim();
        if path.is_empty() {
            return Some("an image path is blank".into());
        }
        if is_stored(path) {
            continue;
        }
        let shown = take(path, 200);
        let Some((size, bytes)) = local(path) else { return Some(format!("image '{shown}' does not exist (no such file)")) };
        if size == 0 {
            return Some(format!("image '{shown}' is empty"));
        }
        if size > IMAGE_MAX_BYTES {
            return Some(format!("image '{shown}' is over the 10 MB limit ({size} bytes, at most {IMAGE_MAX_BYTES})"));
        }
        if !is_image(&bytes.unwrap_or_default()) {
            return Some(format!("image '{shown}' is not a png, jpeg, gif or webp image (its first bytes match none of them)"));
        }
    }
    None
}

/// `TicketImages.matches`: the relPath, its absolute path, its file name, or a local file with the same bytes.
pub fn image_matches(stored: &str, given: &str) -> bool {
    let want = given.trim().replace('\\', "/");
    let name = stored.rsplit('/').next().unwrap_or(stored);
    if want == stored || want.ends_with(&format!("/{stored}")) || want == name {
        return true;
    }
    if is_stored(&want) {
        return false;
    }
    let Some((size, Some(bytes))) = local(given) else { return false };
    let stem = name.rsplit_once('.').map_or(name, |(a, _)| a);
    size <= IMAGE_MAX_BYTES && stem == sha256_hex(&bytes)
}

/// `TicketImageFiles.upload` / `attachImages`: None after printing why (nothing uploaded then).
fn attach_images(ctx: &mut Ctx, project: Option<&str>, paths: &[String]) -> Result<Option<Vec<String>>, String> {
    if paths.is_empty() {
        return Ok(Some(vec![]));
    }
    let refusal = check_images(paths).or_else(|| {
        project.map_or(true, |p| p.trim().is_empty()).then(|| "images need the ticket's project: pass a project id".to_string())
    });
    if let Some(m) = refusal {
        ctx.err(format!("error: {m}; nothing was saved"));
        return Ok(None);
    }
    let project = project.unwrap_or_default();
    let mut out: Vec<String> = vec![];
    for raw in paths {
        let path = raw.trim();
        let rel = if is_stored(path) {
            path.to_string()
        } else {
            let bytes = std::fs::read(path).map_err(|e| e.to_string())?;
            let file_name = Path::new(path).file_name().map(|f| f.to_string_lossy().to_string()).unwrap_or_default();
            let up = json!({
                "projectId": project, "purpose": "ticket", "fileName": file_name, "mimeType": "",
                "base64": base64::engine::general_purpose::STANDARD.encode(&bytes),
            });
            let answer = ctx.stream().request(&Envelope::new("image_upload", up.to_string()), "image", "image_upload", true)?;
            serde_json::from_str::<Value>(&answer.data)
                .ok()
                .and_then(|v| s(&v, "relPath"))
                .ok_or_else(|| "malformed image answer from the daemon: no relPath".to_string())?
        };
        if !out.contains(&rel) {
            out.push(rel);
        }
    }
    Ok(Some(out))
}

/// `editImages`.
fn edit_images(ctx: &mut Ctx, full: &str, add: &[String], remove: &[String]) -> Result<Option<Vec<String>>, String> {
    let ticket = ticket_get(ctx, full)?;
    let stored: Vec<String> =
        ticket.get("images").and_then(|i| i.as_array()).map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect()).unwrap_or_default();
    if let Some(unknown) = remove.iter().find(|g| !stored.iter().any(|st| image_matches(st, g))) {
        let short = short_id(full);
        ctx.err(format!(
            "error: ticket {short} has no image '{}' (see: infantry ticket show {short}); nothing was saved",
            take(unknown, 200)
        ));
        return Ok(None);
    }
    let kept: Vec<String> = stored.into_iter().filter(|st| !remove.iter().any(|g| image_matches(st, g))).collect();
    let Some(added) = attach_images(ctx, s(&ticket, "projectId").as_deref(), add)? else { return Ok(None) };
    let mut all: Vec<String> = vec![];
    for x in kept.into_iter().chain(added) {
        if !all.contains(&x) {
            all.push(x);
        }
    }
    Ok(Some(all))
}

/// SHA-256 as lowercase hex (`TicketImages.sha256`): stored images are named by it.
pub fn sha256_hex(data: &[u8]) -> String {
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01,
        0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
        0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116, 0x1e376c08,
        0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ];
    let mut h: [u32; 8] = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    let mut msg = data.to_vec();
    let bits = (data.len() as u64).wrapping_mul(8);
    msg.push(0x80);
    while msg.len() % 64 != 56 {
        msg.push(0);
    }
    msg.extend_from_slice(&bits.to_be_bytes());
    for chunk in msg.chunks(64) {
        let mut w = [0u32; 64];
        for i in 0..16 {
            w[i] = u32::from_be_bytes([chunk[4 * i], chunk[4 * i + 1], chunk[4 * i + 2], chunk[4 * i + 3]]);
        }
        for i in 16..64 {
            let s0 = w[i - 15].rotate_right(7) ^ w[i - 15].rotate_right(18) ^ (w[i - 15] >> 3);
            let s1 = w[i - 2].rotate_right(17) ^ w[i - 2].rotate_right(19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16].wrapping_add(s0).wrapping_add(w[i - 7]).wrapping_add(s1);
        }
        let mut v = h;
        for i in 0..64 {
            let s1 = v[4].rotate_right(6) ^ v[4].rotate_right(11) ^ v[4].rotate_right(25);
            let ch = (v[4] & v[5]) ^ (!v[4] & v[6]);
            let t1 = v[7].wrapping_add(s1).wrapping_add(ch).wrapping_add(K[i]).wrapping_add(w[i]);
            let s0 = v[0].rotate_right(2) ^ v[0].rotate_right(13) ^ v[0].rotate_right(22);
            let maj = (v[0] & v[1]) ^ (v[0] & v[2]) ^ (v[1] & v[2]);
            let t2 = s0.wrapping_add(maj);
            v = [t1.wrapping_add(t2), v[0], v[1], v[2], v[3].wrapping_add(t1), v[4], v[5], v[6]];
        }
        for i in 0..8 {
            h[i] = h[i].wrapping_add(v[i]);
        }
    }
    h.iter().map(|x| format!("{x:08x}")).collect()
}

// ---- files / archive ----

/// `ticketFiles`.
fn files(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let prefix = p.arg(1).map(|a| a.trim().to_string()).filter(|a| !a.is_empty());
    let id = match prefix {
        Some(pr) => {
            let project = board_project(p, ctx.getenv);
            match resolve_id(ctx, &pr, &project)? {
                Some(id) => Some(id),
                None => return Ok(1),
            }
        }
        None => None,
    };
    let backfill = p.flag("backfill");
    // encodeDefaults = false: only what differs from the defaults.
    let mut q = Map::new();
    if let Some(id) = id {
        q.insert("ticketId".into(), Value::String(id));
    }
    let paths = p.vals("path");
    if !paths.is_empty() {
        q.insert("paths".into(), json!(paths));
    }
    for (k, on) in [("related", p.flag("related")), ("backfill", backfill), ("all", p.flag("all"))] {
        if on {
            q.insert(k.into(), Value::Bool(true));
        }
    }
    if let Some(l) = p.val("limit").and_then(|l| l.parse::<i32>().ok()) {
        q.insert("limit".into(), json!(l));
    }
    let t = if backfill { FILES_BACKFILL_TIMEOUT_MS } else { FILES_TIMEOUT_MS };
    let env = Envelope::new("ticket_files", Value::Object(q).to_string());
    let answer = ctx.stream().request_within(&env, "ticket_files_report", "ticket_files", !backfill, t)?;
    let text = serde_json::from_str::<Value>(&answer.data).ok().and_then(|v| s(&v, "text")).ok_or("malformed ticket_files_report")?;
    ctx.out(text);
    Ok(0)
}

/// `ArchiveCli.parseFilter`.
pub fn parse_filter(filter: &str) -> Result<(String, Option<i64>), String> {
    let (lane, days) = match filter.split_once(':') {
        Some((l, d)) => (l.trim(), Some(d.trim()).filter(|d| !d.is_empty())),
        None => (filter.trim(), None),
    };
    if lane.is_empty() {
        return Err("--filter expects <lane>[:<days>]".into());
    }
    let days = match days {
        None => None,
        Some(d) => Some(d.parse::<i32>().ok().filter(|d| *d >= 0).ok_or("--filter days must be a whole number")? as i64),
    };
    Ok((lane.to_string(), days))
}

/// `ArchiveCli.build`: (is a read, the `ArchiveRequest` with `encodeDefaults = false`).
pub fn archive_build(action: &str, ids: &[String], p: &Parsed) -> Result<(bool, Value), String> {
    let (lane, days) = match p.val("filter") {
        Some(f) => {
            let (l, d) = parse_filter(f)?;
            (Some(l), d)
        }
        None => (None, None),
    };
    let lim = p.val("limit").and_then(|l| l.parse::<i64>().ok());
    let offset = p.val("offset").and_then(|o| o.parse::<i64>().ok());
    let (project, epic, dry) = (p.val("project").map(str::to_string), p.val("epic").map(str::to_string), p.flag("dry-run"));
    let mut m = Map::new();
    m.insert("op".into(), Value::String(action.into()));
    let mut put = |k: &str, v: Option<Value>| {
        if let Some(v) = v {
            m.insert(k.into(), v);
        }
    };
    let ids_v = (!ids.is_empty()).then(|| json!(ids));
    let read = match action {
        "archive" | "restore" => {
            put("ids", ids_v);
            put("projectId", project.map(Value::String));
            put("epicId", epic.map(Value::String));
            if action == "archive" {
                put("status", lane.map(Value::String));
                put("olderThanDays", days.map(|d| json!(d)));
            }
            put("dryRun", dry.then_some(Value::Bool(true)));
            false
        }
        "list" => {
            put("projectId", project.map(Value::String));
            put("epicId", epic.map(Value::String));
            put("limit", lim.map(|l| json!(l)));
            put("offset", offset.map(|o| json!(o)));
            true
        }
        "show" => {
            put("ids", ids_v);
            true
        }
        "search" => {
            put("projectId", project.map(Value::String));
            put("text", Some(Value::String(ids.join(" "))));
            put("limit", lim.map(|l| json!(l)));
            true
        }
        "count" => {
            put("projectId", project.map(Value::String));
            true
        }
        _ => return Err(ARCHIVE_USAGE.into()),
    };
    Ok((read, Value::Object(m)))
}

/// `archiveCommand`: `ticket archive|restore ...` and `archive list|show|search|count|restore ...`.
pub fn archive_command(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let Some(verb) = p.arg(0).map(str::to_string) else { return Ok(1) };
    let (read, req) = archive_build(&verb, &args_from(p, 1), p)?;
    let env = Envelope::new(if read { "archive_query" } else { "archive_apply" }, req.to_string());
    let answer = ctx.stream().request_within(&env, "archive_report", "archive", read, ARCHIVE_TIMEOUT_MS)?;
    let text = serde_json::from_str::<Value>(&answer.data).ok().and_then(|v| s(&v, "text")).ok_or("malformed archive_report")?;
    ctx.out(text);
    Ok(0)
}

// ---- comment / epic: one plugin tool call each ----

/// `McpPluginTools.call` as the CLI uses it: one `plugin_tool_call`, never re-sent. (text, isError).
pub fn plugin_tool_call(ctx: &mut Ctx, name: &str, arguments: Map<String, Value>) -> Result<(String, bool), String> {
    let mut client = Map::new();
    let env_val = |k: &str| ctx.env(k).map(|v| v.trim().to_string()).filter(|v| !v.is_empty());
    if let Some(v) = env_val("INFANTRY_SESSION_ID") {
        client.insert("sessionId".into(), Value::String(v));
    }
    if let Some(v) = env_val("INFANTRY_ROLE") {
        client.insert("role".into(), Value::String(v));
    }
    if let Some(v) = env_val("INFANTRY_PROJECT_ID") {
        client.insert("projectId".into(), Value::String(v));
    }
    // The tool's own timeout, from the daemon's descriptors; else limits.plugins.toolTimeoutMs.
    let listed = ctx
        .stream()
        .request(&Envelope::new("plugin_tools", ""), "plugin_tools", "plugin_tools", true)
        .ok()
        .and_then(|a| serde_json::from_str::<Value>(&a.data).ok())
        .and_then(|v| v.as_array().and_then(|a| a.iter().find(|d| s(d, "name").as_deref() == Some(name)).and_then(|d| n(d, "timeoutMs"))));
    let t = match listed {
        Some(t) => t,
        None => crate::env::PLUGIN_TOOL_TIMEOUT_MS.from_env(ctx.getenv)?,
    }
    .max(0) as u64;
    let payload = json!({"name": name, "arguments": Value::Object(arguments), "client": Value::Object(client)});
    let answer =
        ctx.stream().request_within(&Envelope::new("plugin_tool_call", payload.to_string()), "plugin_tool_result", "plugin_tool_call", false, t + t / 4)?;
    let Some(result) = serde_json::from_str::<Value>(&answer.data).ok().filter(|v| v.is_object()) else {
        return Ok(("malformed plugin_tool_result".into(), true));
    };
    Ok((s(&result, "text").unwrap_or_default(), result.get("isError").and_then(|x| x.as_bool()).unwrap_or(false)))
}

/// The `infantry_ticket_comments` arguments of `ticket comments <id> [--which] [--kind] [--limit]`.
pub fn comments_args(p: &Parsed) -> Map<String, Value> {
    let mut a = Map::new();
    a.insert("ticketId".into(), Value::String(p.args[1].clone()));
    for k in ["which", "kind"] {
        if let Some(v) = p.val(k) {
            a.insert(k.into(), Value::String(v.to_string()));
        }
    }
    if let Some(n) = p.val("limit").and_then(|l| l.parse::<u64>().ok()) {
        a.insert("limit".into(), Value::from(n));
    }
    a
}

fn comments(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    run_comment_tool(ctx, "infantry_ticket_comments", comments_args(p))
}

/// `TicketCommentCli.run`.
fn comment(ctx: &mut Ctx, args: &[String], kind_flag: Option<&str>, state_flag: Option<&str>) -> CmdResult {
    let (tool, a) = comment_tool(args, kind_flag, state_flag);
    run_comment_tool(ctx, tool, a)
}

fn run_comment_tool(ctx: &mut Ctx, tool: &str, a: Map<String, Value>) -> CmdResult {
    let (text, is_error) = plugin_tool_call(ctx, tool, a)?;
    if text.starts_with("Unknown tool:") {
        ctx.err("error: this daemon has no ticket comment tools (Infantry_Board's daemon half is not loaded)");
        return Ok(1);
    }
    if is_error {
        ctx.err(text);
        Ok(1)
    } else {
        ctx.out(text);
        Ok(0)
    }
}

/// The tool and arguments `ticket comment ...` sends: the same call as MCP infantry_ticket_comment(s).
/// `--kind` / `--state` set the kind and state; without `--kind` a leading done|issue|note|handoff word is the kind.
pub fn comment_tool(args: &[String], kind_flag: Option<&str>, state_flag: Option<&str>) -> (&'static str, Map<String, Value>) {
    let str_v = |v: &str| Value::String(v.to_string());
    let mut a = Map::new();
    let tool = match args[0].as_str() {
        "resolve" => {
            a.insert("commentId".into(), str_v(&args[1]));
            "infantry_ticket_comment"
        }
        "list" => {
            a.insert("ticketId".into(), str_v(&args[1]));
            for w in &args[2..] {
                a.insert(if COMMENT_KINDS.contains(&w.as_str()) { "kind" } else { "which" }.into(), str_v(w));
            }
            "infantry_ticket_comments"
        }
        _ => {
            let kind = kind_flag.map(str::trim).or_else(|| Some(args[1].as_str()).filter(|k| COMMENT_KINDS.contains(k)));
            a.insert("ticketId".into(), str_v(&args[0]));
            if let Some(k) = kind {
                a.insert("kind".into(), str_v(k));
            }
            let body_from = if kind.is_some() && kind_flag.is_none() { 2 } else { 1 };
            a.insert("body".into(), str_v(&args[body_from..].join(" ")));
            if let Some(st) = state_flag.map(str::trim) {
                a.insert("state".into(), str_v(st));
            }
            "infantry_ticket_comment"
        }
    };
    (tool, a)
}

/// `TicketEpicCli.run`.
fn epic(ctx: &mut Ctx, args: &[String], force: bool) -> CmdResult {
    let mut a = Map::new();
    a.insert("tickets".into(), json!(args[..args.len() - 1].iter().map(|x| x.trim()).collect::<Vec<_>>()));
    a.insert("epic".into(), Value::String(args[args.len() - 1].trim().to_string()));
    if force {
        a.insert("force".into(), Value::Bool(true));
    }
    let (text, is_error) = plugin_tool_call(ctx, EPIC_TOOL, a)?;
    if text.starts_with("Unknown tool:") {
        ctx.err(format!("error: this daemon has no {EPIC_TOOL} tool (Infantry_Board's daemon half is not loaded, or it is older)"));
        return Ok(1);
    }
    let message = serde_json::from_str::<Value>(&text).ok().and_then(|v| s(&v, "message"));
    let line = message.unwrap_or(text);
    if is_error {
        ctx.err(line);
        Ok(1)
    } else {
        ctx.out(line);
        Ok(0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cli::args::parse;

    fn parsed(words: &[&str]) -> Parsed {
        parse(&words.iter().map(|w| w.to_string()).collect::<Vec<_>>()).expect("parses")
    }

    fn words(ws: &[&str]) -> Vec<String> {
        ws.iter().map(|w| w.to_string()).collect()
    }

    #[test]
    fn comment_kind_and_state_flags_map_to_the_mcp_tool_arguments() {
        let p = parsed(&["ticket", "comment", "d3efb233", "--kind", "done", "--state", "resolved", "all", "green"]);
        assert!(usage_error(&p).is_none());
        let (tool, a) = comment_tool(&args_from(&p, 1), p.val("kind"), p.val("state"));
        assert_eq!(tool, "infantry_ticket_comment");
        assert_eq!(a["ticketId"], "d3efb233");
        assert_eq!(a["kind"], "done");
        assert_eq!(a["state"], "resolved");
        assert_eq!(a["body"], "all green");
        // --kind wins and a body starting with a kind word stays whole.
        let (_, a) = comment_tool(&words(&["x", "note", "this"]), Some("issue"), None);
        assert_eq!((a["kind"].as_str(), a["body"].as_str()), (Some("issue"), Some("note this")));
        // The positional form still works.
        let (_, a) = comment_tool(&words(&["x", "done", "ok"]), None, None);
        assert_eq!((a["kind"].as_str(), a["body"].as_str(), a.get("state")), (Some("done"), Some("ok"), None));
    }

    #[test]
    fn comment_flags_refuse_unknown_values_and_a_missing_body() {
        assert!(usage_error(&parsed(&["ticket", "comment", "x", "--kind", "bogus", "b"])).unwrap().contains("not a comment kind"));
        assert!(usage_error(&parsed(&["ticket", "comment", "x", "--kind", "note", "--state", "closed", "b"])).unwrap().contains("not a comment state"));
        assert_eq!(usage_error(&parsed(&["ticket", "comment", "x", "--kind", "done"])).unwrap(), COMMENT_USAGE);
    }

    #[test]
    fn a_done_move_warns_only_when_no_done_comment_exists() {
        assert!(no_done_comment("no comments", false));
        assert!(!no_done_comment("ab12cd34 done/open by x 10:00: shipped", false));
        assert!(!no_done_comment("no comments", true));
    }

    #[test]
    fn ticket_comments_matches_the_mcp_tool() {
        let p = parsed(&["ticket", "comments", "7d707629", "--which", "all", "--kind", "done", "--limit", "5"]);
        assert_eq!(usage_error(&p), None);
        let a = comments_args(&p);
        assert_eq!(Value::Object(a), serde_json::json!({ "ticketId": "7d707629", "which": "all", "kind": "done", "limit": 5 }));
        let bare = parsed(&["ticket", "comments", "7d707629"]);
        assert_eq!(usage_error(&bare), None);
        assert_eq!(Value::Object(comments_args(&bare)), serde_json::json!({ "ticketId": "7d707629" }));
        assert_eq!(usage_error(&parsed(&["ticket", "comments"])).unwrap(), COMMENTS_USAGE);
        assert_eq!(usage_error(&parsed(&["ticket", "comments", "a", "--limit", "0"])).unwrap(), COMMENTS_USAGE);
        assert_eq!(usage_error(&parsed(&["ticket", "comments", "a", "--limit", "x"])).unwrap(), COMMENTS_USAGE);
    }

    #[test]
    fn usage_errors_match_kotlin() {
        assert_eq!(
            usage_error(&parsed(&["ticket"])).unwrap(),
            "ticket needs a subcommand: infantry ticket show <id|prefix> | create --title \"...\" | update <id|prefix> ... | move <id|prefix> <status> | start <id|prefix> | link <id|prefix> <otherId|prefix> <type>"
        );
        assert_eq!(
            usage_error(&parsed(&["ticket", "create", "--title", "x", "--status", "done"])).unwrap(),
            "ticket create --status is todo (the sprint, default) or backlog, not 'done'"
        );
        assert_eq!(
            usage_error(&parsed(&["ticket", "move", "abc", "--top", "--bottom"])).unwrap(),
            "ticket move takes one of --before, --after, --top, --bottom, --position"
        );
        assert!(usage_error(&parsed(&["ticket", "move", "abc", "--top"])).is_none());
        assert_eq!(
            usage_error(&parsed(&["ticket", "move", "abc", "doing"])).unwrap(),
            "unknown status 'doing'; expected one of backlog, todo, in_progress, pending, done"
        );
        assert_eq!(
            usage_error(&parsed(&["ticket", "link", "a", "b"])).unwrap(),
            "ticket link requires a link type: expected one of blocked_by, blocks, depends_on, child_of, parent_of, relates_to, duplicates, duplicated_by"
        );
        assert_eq!(usage_error(&parsed(&["ticket", "update", "abc"])).unwrap().split(':').next().unwrap(), "ticket update needs something to change");
        assert!(usage_error(&parsed(&["ticket", "update", "abc", "--role", "Backend Dev"])).unwrap().starts_with("--role 'Backend Dev' is not a role id"));
        assert_eq!(usage_error(&parsed(&["ticket", "files"])).unwrap(), FILES_USAGE);
        assert_eq!(usage_error(&parsed(&["ticket", "comment", "abc", "done"])).unwrap(), COMMENT_USAGE);
    }

    #[test]
    fn placement_and_related_text() {
        let board: Vec<Value> = vec![
            json!({"id": "ticket-aaaa1111", "status": "todo", "position": 0}),
            json!({"id": "ticket-bbbb2222", "status": "todo", "position": 1}),
            json!({"id": "ticket-cccc3333", "status": "todo", "position": 2}),
        ];
        let p = parsed(&["ticket", "move", "aaaa", "--after", "cccc"]);
        assert_eq!(resolve_placement(&p, &board[0], None, &board).unwrap(), 2);
        let p = parsed(&["ticket", "move", "cccc", "--before", "aaaa"]);
        assert_eq!(resolve_placement(&p, &board[2], None, &board).unwrap(), 0);
        let desc = format!("{RELATED_BEGIN}\nRELATED (auto, 3 files; x)\nSeries A\n{RELATED_END}\n\nbody");
        let t = json!({"id": "t", "description": desc});
        assert_eq!(related_note(&t), vec!["RELATED (auto, 3 files) atop the description: read those files first", "Series A"]);
        assert_eq!(strip_related(&desc), "body");
        assert_eq!(sha256_hex(b"abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assert_eq!(started_detail(Some("cut from main abc123; launch warning: x")), (Some("cut from main abc123".into()), Some("launch warning: x".into())));
    }
}
