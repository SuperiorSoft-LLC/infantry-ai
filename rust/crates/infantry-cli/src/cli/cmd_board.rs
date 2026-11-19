// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `infantry board` and `infantry tickets` (RUST-CLI-1), with the Kotlin text: `BoardLines`, `SessionText`, `ProgressText`,
//! `printTicketRows`, plus the board helpers every ticket command shares (`checkContract`, `fetchBoard`, `resolveTicket`,
//! `resolveEpicId`, `matchTicketPrefix`).

use super::args::{Parsed, TICKET_STATUSES};
use super::conn::Envelope;
use super::{CmdResult, Ctx, Getenv, CONTRACT};
use serde_json::Value;
use std::sync::atomic::{AtomicBool, Ordering};

/// `TICKET_HEADER`.
pub const TICKET_HEADER: &str = "ID | STATUS | TITLE | AGENT";
pub const SHORT_ID_LEN: usize = 8;
pub const TITLE_WIDTH: usize = 100;
pub const ELLIPSIS: &str = "...";
/// `LimitRegistry.TOOLS_BOARD_QUEUE_SHOWN` / `TOOLS_BOARD_DONE_SHOWN`.
pub const QUEUE_SHOWN_KEY: (&str, i64) = ("limits.tools.boardQueueShown", 10);
pub const DONE_SHOWN_KEY: (&str, i64) = ("limits.tools.boardDoneShown", 5);
/// `TicketRefs.SCHEME`.
pub const TICKET_ID_SCHEME: &str = "ticket-";
/// `git rev-parse` bound for a board line's commit.
const GIT_TIMEOUT_MS: u64 = 5_000;

// ---- small JSON readers ----

pub fn s(v: &Value, k: &str) -> Option<String> {
    v.get(k).and_then(|x| x.as_str()).map(str::to_string)
}
pub fn n(v: &Value, k: &str) -> Option<i64> {
    v.get(k).and_then(|x| x.as_i64().or_else(|| x.as_f64().map(|f| f as i64)))
}
pub fn f(v: &Value, k: &str) -> Option<f64> {
    v.get(k).and_then(|x| x.as_f64())
}
fn take(t: &str, k: usize) -> String {
    t.chars().take(k).collect()
}
fn take_last(t: &str, k: usize) -> String {
    let c: Vec<char> = t.chars().collect();
    c[c.len().saturating_sub(k)..].iter().collect()
}
pub fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// `shortTicketId`: the id's last 8 characters.
pub fn short_id(id: &str) -> String {
    take_last(id, SHORT_ID_LEN)
}

/// `BoardLines.cutTitle`.
pub fn cut_title(title: &str) -> String {
    let flat = title.split_whitespace().collect::<Vec<_>>().join(" ");
    if flat.chars().count() <= TITLE_WIDTH {
        flat
    } else {
        take(&flat, TITLE_WIDTH - ELLIPSIS.len()).trim_end().to_string() + ELLIPSIS
    }
}

// ---- contract ----

static HINT_SHOWN: AtomicBool = AtomicBool::new(false);

/// `contractNotice`: (major, line), or None when the versions match.
pub fn contract_notice(cli: &str, ready: &str) -> Option<(bool, String)> {
    let Some(daemon) = ready.split(' ').find(|w| w.starts_with("contract=")).map(|w| {
        let w = &w["contract=".len()..];
        w.strip_prefix("infantry/").unwrap_or(w).to_string()
    }) else {
        return Some((true, format!("warning: no contract version in ready frame: {ready}")));
    };
    if daemon == cli {
        return None;
    }
    let major = |v: &str| v.split('.').next().unwrap_or("").to_string();
    let short = |v: &str| v.split('.').take(2).collect::<Vec<_>>().join(".");
    if major(&daemon) != major(cli) {
        Some((true, format!("warning: incompatible contract: CLI {cli}, daemon {daemon} (ready frame: {ready})")))
    } else {
        Some((false, format!("hint: CLI {}, daemon {}: rebuild/reinstall both for new features", short(cli), short(&daemon))))
    }
}

/// `checkContract`: waits for `ready`; a major difference warns every call, a minor one hints once per process.
pub fn check_contract(ctx: &mut Ctx) -> Result<(), String> {
    let t = ctx.stream().answer_timeout_ms();
    let ready = ctx.stream().await_type("ready", t)?;
    if let Some((major, line)) = contract_notice(CONTRACT, &ready.data) {
        if major || !HINT_SHOWN.swap(true, Ordering::SeqCst) {
            ctx.err(line);
        }
    }
    Ok(())
}

// ---- board reads ----

/// `boardProjectOf`: `--project` trimmed, else a non-blank `INFANTRY_PROJECT_ID`, else "" (the whole board).
pub fn board_project(p: &Parsed, getenv: Getenv) -> String {
    p.val("project")
        .map(|v| v.trim().to_string())
        .or_else(|| getenv("INFANTRY_PROJECT_ID").filter(|v| !v.trim().is_empty()))
        .unwrap_or_default()
}

/// `fetchBoard`: one `ticket_list`; the bare project id, or the JSON query when an epic narrows it.
pub fn fetch_board(ctx: &mut Ctx, project: &str, epic: Option<&str>) -> Result<Vec<Value>, String> {
    let payload = match epic {
        None => project.to_string(),
        Some(e) => {
            let pid = if project.trim().is_empty() { Value::Null } else { Value::from(project) };
            serde_json::json!({"projectId": pid, "epicId": e}).to_string()
        }
    };
    let answer = ctx.stream().request(&Envelope::new("ticket_list", payload), "tickets", "ticket_list", true)?;
    decode_tickets(&answer.data)
}

pub fn decode_tickets(data: &str) -> Result<Vec<Value>, String> {
    let bad = |m: String| format!("malformed tickets answer from the daemon: {m}");
    let v: Value = serde_json::from_str(data).map_err(|e| bad(e.to_string()))?;
    let list = v.as_array().ok_or_else(|| bad("expected a JSON array".into()))?;
    for t in list {
        if s(t, "id").is_none() || s(t, "title").is_none() || s(t, "status").is_none() {
            return Err(bad("a ticket lacks id, title or status".into()));
        }
    }
    Ok(list.clone())
}

pub fn decode_ticket(data: &str) -> Result<Value, String> {
    let v: Value = serde_json::from_str(data).map_err(|e| format!("malformed ticket answer from the daemon: {e}"))?;
    if s(&v, "id").is_none() {
        return Err("malformed ticket answer from the daemon: no id".into());
    }
    Ok(v)
}

/// `sessions`: every session the daemon lists (empty when unreadable, as `TicketStarter.sessions`).
pub fn sessions(ctx: &mut Ctx) -> Result<Vec<Value>, String> {
    let answer = ctx.stream().request(&Envelope::new("sessions", ""), "sessions", "sessions", true)?;
    Ok(serde_json::from_str::<Value>(&answer.data).ok().and_then(|v| v.as_array().cloned()).unwrap_or_default())
}

/// `TicketRefs.match`: the exact id, else a head prefix of the tail after `ticket-`, else a suffix.
pub fn match_prefix<'a>(prefix: &str, tickets: &'a [Value]) -> Vec<&'a Value> {
    let wanted = prefix.trim();
    if wanted.is_empty() {
        return vec![];
    }
    let id = |t: &Value| s(t, "id").unwrap_or_default();
    let exact: Vec<_> = tickets.iter().filter(|t| id(t) == wanted).collect();
    if !exact.is_empty() {
        return exact;
    }
    let head = wanted.strip_prefix(TICKET_ID_SCHEME).unwrap_or(wanted);
    if !head.is_empty() {
        let m: Vec<_> = tickets
            .iter()
            .filter(|t| {
                let i = id(t);
                i.strip_prefix(TICKET_ID_SCHEME).unwrap_or(&i).starts_with(head)
            })
            .collect();
        if !m.is_empty() {
            return m;
        }
    }
    tickets.iter().filter(|t| id(t).ends_with(wanted)).collect()
}

/// `resolveTicket`'s report for no match or several, as lines for stderr; empty when [matches] holds exactly one.
pub fn prefix_report(prefix: &str, board: &[Value], matches: &[&Value]) -> Vec<String> {
    match matches.len() {
        1 => vec![],
        0 => {
            let mut l = vec![
                format!("error: no ticket {prefix}"),
                "ticket commands take a ticket id (the full id or the 8-char id `ticket create` and `tickets` print, e.g. 09126a31), not a title".to_string(),
            ];
            for t in board.iter().filter(|t| s(t, "title").unwrap_or_default().to_lowercase() == prefix.to_lowercase()) {
                l.push(format!("  a ticket titled '{}' has the id {}: use that", s(t, "title").unwrap_or_default(), s(t, "id").unwrap_or_default()));
            }
            l
        }
        _ => {
            let mut l = vec![format!("error: ticket id '{prefix}' is ambiguous; use a longer id or the full id:")];
            for t in matches {
                l.push(format!("  {} | {} | {}", s(t, "id").unwrap_or_default(), s(t, "status").unwrap_or_default(), s(t, "title").unwrap_or_default()));
            }
            l
        }
    }
}

/// `resolveTicket`: the ONE ticket [prefix] names on the board; None after printing why (the caller exits 1).
pub fn resolve_ticket(ctx: &mut Ctx, prefix: &str, project: &str) -> Result<Option<Value>, String> {
    let board = fetch_board(ctx, project, None)?;
    let matches = match_prefix(prefix, &board);
    if matches.len() == 1 {
        return Ok(Some(matches[0].clone()));
    }
    for l in prefix_report(prefix, &board, &matches) {
        ctx.err(l);
    }
    Ok(None)
}

/// `resolveEpicId`: the ONE epic of [project] whose id ends with [prefix]; None after printing why.
pub fn resolve_epic_id(ctx: &mut Ctx, prefix: &str, project: Option<&str>) -> Result<Option<String>, String> {
    let Some(project) = project else {
        ctx.err("error: --epic needs a project to find its epic in: pass --project <id>");
        return Ok(None);
    };
    let answer = ctx.stream().request(&Envelope::new("epic_list", project), "epics", "epic_list", true)?;
    let epics = match serde_json::from_str::<Value>(&answer.data).ok().and_then(|v| v.as_array().cloned()) {
        Some(e) => e,
        None => {
            ctx.err("error: malformed epics answer from the daemon: expected a JSON array");
            return Ok(None);
        }
    };
    let matches: Vec<&Value> = epics.iter().filter(|e| s(e, "id").unwrap_or_default().ends_with(prefix)).collect();
    match matches.len() {
        0 => {
            ctx.err(format!("error: no epic matches '{prefix}' in project {project}"));
            Ok(None)
        }
        1 => Ok(s(matches[0], "id")),
        _ => {
            ctx.err(format!("error: epic id '{prefix}' is ambiguous; use a longer id or the full id:"));
            for e in matches {
                ctx.err(format!("  {} | {}", s(e, "id").unwrap_or_default(), s(e, "name").unwrap_or_default()));
            }
            Ok(None)
        }
    }
}

/// `cliLimit`: the daemon's value of a limit, else its default.
fn cli_limit(ctx: &mut Ctx, def: (&str, i64)) -> i64 {
    let Ok(answer) = ctx.stream().request(&Envelope::new("limit_catalog", ""), "limit_catalog", "limit_catalog", true) else {
        return def.1;
    };
    serde_json::from_str::<Value>(&answer.data)
        .ok()
        .and_then(|v| v.get("limits").and_then(|l| l.as_array()).cloned())
        .and_then(|l| l.into_iter().find(|e| s(e, "key").as_deref() == Some(def.0)))
        .and_then(|e| n(&e, "value"))
        .unwrap_or(def.1)
}

// ---- text: SessionText / ProgressText / BoardLines ----

fn background_work(sess: &Value) -> Option<String> {
    sess.get("backgroundWork")
        .and_then(|b| s(b, "label"))
        .or_else(|| sess.get("bgJobs").and_then(|j| j.as_array()).and_then(|j| j.first()).and_then(|j| s(j, "label")))
}

/// `SessionText.liveState`.
pub fn live_state(sess: &Value) -> String {
    if let Some(b) = background_work(sess) {
        return format!("working (background: {})", take(&b, 40));
    }
    match s(sess, "state").unwrap_or_default().as_str() {
        "BUSY" | "STARTING" => "busy".into(),
        "WAITING_INPUT" => "waiting for input".into(),
        "IDLE" => "idle".into(),
        other => other.to_lowercase(),
    }
}

/// `SessionText.ago`.
pub fn ago(at: Option<i64>, now: i64) -> String {
    let Some(at) = at else { return "never".into() };
    let sec = (now - at).max(0) / 1000;
    if sec < 60 {
        format!("{sec}s ago")
    } else if sec < 3600 {
        format!("{}m ago", sec / 60)
    } else if sec < 86400 {
        format!("{}h ago", sec / 3600)
    } else {
        format!("{}d ago", sec / 86400)
    }
}

fn waiting(sess: &Value) -> Option<&Value> {
    sess.get("waiting").filter(|w| s(w, "reason").is_some())
}

/// `BoardLines.waitingActive`.
pub fn waiting_active(sess: &Value, now: i64) -> bool {
    waiting(sess).map(|w| now <= n(w, "expiresAt").unwrap_or(i64::MAX)).unwrap_or(false)
}

/// `BoardLines.waitingText`.
pub fn waiting_text(sess: &Value, now: i64) -> Option<String> {
    let w = waiting(sess)?;
    let reason = s(w, "reason")?;
    let since = n(w, "since").unwrap_or(now);
    Some(if waiting_active(sess, now) {
        format!("WAITING {}m: {reason}", (now - since) / 60_000)
    } else {
        format!("WAITING EXPIRED {}m ago: {reason}", (now - n(w, "untilAt").unwrap_or(since)) / 60_000)
    })
}

fn coarse(ms: i64, up: bool) -> String {
    let sec = (ms / 1000).max(0);
    if sec < 60 {
        format!("{sec}s")
    } else if sec < 3600 {
        format!("{}m", if up { (sec + 59) / 60 } else { sec / 60 })
    } else {
        format!("{}h{:02}m", sec / 3600, (sec % 3600) / 60)
    }
}

pub fn duration(ms: i64) -> String {
    let sec = (ms / 1000).max(0);
    if sec < 60 {
        format!("{sec}s")
    } else if sec < 3600 {
        format!("{}m{:02}s", sec / 60, sec % 60)
    } else {
        format!("{}h{:02}m", sec / 3600, (sec % 3600) / 60)
    }
}

pub fn bytes(v: i64) -> String {
    const K: i64 = 1 << 10;
    const M: i64 = 1 << 20;
    const G: i64 = 1 << 30;
    if v >= G {
        format!("{:.1} GB", v as f64 / G as f64)
    } else if v >= M {
        format!("{:.1} MB", v as f64 / M as f64)
    } else if v >= K {
        format!("{:.1} KB", v as f64 / K as f64)
    } else {
        format!("{v} B")
    }
}

/// `ProgressText.line`.
pub fn progress_line(p: &Value, now: i64) -> String {
    let state = s(p, "state").unwrap_or_else(|| "running".into());
    let running = state == "running";
    let mode = s(p, "mode").unwrap_or_default();
    let started = n(p, "startedAt").unwrap_or(0);
    let updated = n(p, "updatedAt").unwrap_or(0);
    // eta: (percent, remaining, over, noEstimate)
    let (pct, left, over, no_est) = if !running {
        (f(p, "percent"), None, None, false)
    } else if let (true, Some(pc)) = (mode != "indeterminate", f(p, "percent")) {
        let el = (now - started).max(0);
        let l = if pc > 0.0 && pc < 100.0 && el >= 2_000 { Some((el as f64 * (100.0 - pc) / pc) as i64) } else { None };
        (Some(pc), l, None, false)
    } else if let Some(est) = n(p, "estimateMs") {
        let el = (now - n(p, "labelStartedAt").unwrap_or(started)).max(0);
        if el <= est {
            (Some(f64::min(99.0, el as f64 * 100.0 / est as f64)), Some(est - el), None, false)
        } else {
            (Some(99.0), None, Some(el - est), false)
        }
    } else {
        (None, None, None, mode == "indeterminate")
    };
    let mut parts: Vec<String> = vec![];
    if !running {
        parts.push(state.clone());
    }
    if let (Some(pc), None) = (pct, over) {
        parts.push(format!("{}%", (pc + 0.5).floor() as i64));
    }
    let step = s(p, "step").map(|x| format!(" {x}")).unwrap_or_default();
    match mode.as_str() {
        "steps" => {
            let total = n(p, "stepsTotal").unwrap_or(0);
            let cur = (n(p, "stepsDone").unwrap_or(0) + if running { 1 } else { 0 }).min(total);
            parts.push(format!("step {cur}/{total}{step}"));
        }
        "bytes" => parts.push(format!(
            "{}{}{}",
            bytes(n(p, "done").unwrap_or(0)),
            n(p, "total").map(|t| format!("/{}", bytes(t))).unwrap_or_default(),
            f(p, "bytesPerSec").map(|b| format!(", {}/s", bytes(b as i64))).unwrap_or_default()
        )),
        "count" => parts.push(format!("{}{}{step}", n(p, "done").unwrap_or(0), n(p, "total").map(|t| format!("/{t}")).unwrap_or_default())),
        _ => {}
    }
    if running {
        let cd = if let Some(l) = left {
            Some(format!("~{} left", coarse(l, true)))
        } else if let Some(o) = over {
            Some(format!("over estimate by {}", coarse(o, false)))
        } else if no_est {
            Some("no estimate yet".into())
        } else {
            None
        };
        if let Some(c) = cd {
            parts.push(format!("· {c}"));
        }
        if no_est {
            parts.push(format!("· last output {} ago", duration(now - n(p, "lastOutputAt").unwrap_or(updated))));
        }
    }
    if let Some(l) = s(p, "label") {
        parts.push(format!("- {l}"));
    }
    parts.push(format!("(updated {} ago)", duration(now - updated)));
    parts.join(" ")
}

/// `BoardLines.Options`.
#[derive(Clone, Debug)]
pub struct Options {
    pub columns: Option<Vec<String>>,
    pub limit: Option<usize>,
    pub compact: bool,
    pub queue_shown: usize,
    pub done_shown: usize,
}

impl Default for Options {
    fn default() -> Self {
        Options { columns: None, limit: None, compact: false, queue_shown: QUEUE_SHOWN_KEY.1 as usize, done_shown: DONE_SHOWN_KEY.1 as usize }
    }
}

fn ticket_line(t: &Value, worker: Option<&Value>, now: i64, commit_of: &dyn Fn(&Value) -> Option<String>) -> String {
    let mut parts = vec![short_id(&s(t, "id").unwrap_or_default()), cut_title(&s(t, "title").unwrap_or_default())];
    let sid = s(t, "sessionId");
    if let Some(w) = worker {
        parts.push(format!("{} {} {}", s(w, "role").unwrap_or_else(|| "?".into()), take(&s(w, "id").unwrap_or_default(), 8), live_state(w)));
        if let Some(a) = s(w, "lastAction") {
            parts.push(format!("doing '{a}'"));
        }
        parts.push(format!("out {}", ago(n(w, "lastOutputAt"), now)));
        if let Some(p) = w.get("progress").filter(|p| p.is_object()) {
            parts.push(format!("progress {}", progress_line(p, now)));
        }
        if let Some(x) = waiting_text(w, now) {
            parts.push(x);
        }
    } else if let Some(sid) = &sid {
        parts.push(format!("worker {} gone", take(sid, 8)));
    }
    if let Some(b) = s(t, "branch") {
        parts.push(format!("{b}@{}", commit_of(t).unwrap_or_else(|| "no commit".into())));
    }
    if let Some(hw) = t.get("hasWork").filter(|h| h.is_object()) {
        if let (Some(tip), Some(ahead)) = (s(hw, "tip"), n(hw, "ahead")) {
            parts.push(format!("[WORK {} +{ahead}]", take(&tip, 7)));
        }
    }
    parts.join(" | ")
}

/// `BoardLines.render` over wire tickets and sessions.
pub fn render(tickets: &[Value], sessions: &[Value], now: i64, commit_of: &dyn Fn(&Value) -> Option<String>, o: &Options) -> Vec<String> {
    if tickets.is_empty() {
        return vec!["board is empty: no tickets".into()];
    }
    let status = |t: &Value| s(t, "status").unwrap_or_default();
    let mut groups: Vec<(String, Vec<&Value>)> = TICKET_STATUSES
        .iter()
        .map(|c| {
            let mut g: Vec<&Value> = tickets.iter().filter(|t| status(t) == *c).collect();
            g.sort_by_key(|t| n(t, "position").unwrap_or(0));
            (c.to_string(), g)
        })
        .collect();
    for t in tickets.iter().filter(|t| !TICKET_STATUSES.contains(&status(t).as_str())) {
        let st = status(t);
        match groups.iter_mut().find(|(c, _)| *c == st) {
            Some((_, g)) => g.push(t),
            None => groups.push((st, vec![t])),
        }
    }
    let mut lines = vec![];
    for (column, group) in groups {
        if group.is_empty() || o.columns.as_ref().is_some_and(|c| !c.contains(&column)) {
            continue;
        }
        lines.push(format!("{column} ({})", group.len()));
        let done = column == "done";
        let last = |k: usize| group[group.len().saturating_sub(k)..].to_vec();
        let first = |k: usize| group[..k.min(group.len())].to_vec();
        let shown: Vec<&Value> = if let Some(l) = o.limit {
            if done { last(l) } else { first(l) }
        } else if done {
            last(o.done_shown)
        } else if o.compact && o.columns.is_none() && (column == "backlog" || column == "todo") {
            first(o.queue_shown)
        } else {
            group.clone()
        };
        if shown.len() < group.len() {
            lines.push(format!("  ... {} {} not shown", group.len() - shown.len(), if done { "older done" } else { "more" }));
        }
        for t in shown {
            let worker = s(t, "sessionId").and_then(|id| sessions.iter().find(|x| s(x, "id").as_deref() == Some(id.as_str())));
            lines.push(format!("  {}", ticket_line(t, worker, now, commit_of)));
        }
    }
    lines
}

/// `BoardSummary.gitCommit`: `git -C <dir> rev-parse --short <branch>`, first non-blank line; None when git cannot answer.
pub fn git_commit(branch: Option<&str>, dir: Option<&str>) -> Option<String> {
    let (branch, dir) = (branch?, dir?);
    let mut child = infantry_proc::command("git")
        .args(["-C", dir, "rev-parse", "--short", branch])
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .stdin(std::process::Stdio::null())
        .spawn()
        .ok()?;
    let deadline = std::time::Instant::now() + std::time::Duration::from_millis(GIT_TIMEOUT_MS);
    loop {
        match child.try_wait().ok()? {
            Some(status) => {
                if !status.success() {
                    return None;
                }
                let mut out = String::new();
                std::io::Read::read_to_string(child.stdout.as_mut()?, &mut out).ok()?;
                return out.trim().lines().next().map(str::to_string).filter(|l| !l.trim().is_empty());
            }
            None if std::time::Instant::now() >= deadline => {
                let _ = child.kill();
                return None;
            }
            None => std::thread::sleep(std::time::Duration::from_millis(20)),
        }
    }
}

/// `printTicketRows`.
pub fn ticket_rows(tickets: &[&Value], empty: &str) -> Vec<String> {
    if tickets.is_empty() {
        return vec![empty.to_string()];
    }
    let mut l = vec![TICKET_HEADER.to_string()];
    for t in tickets {
        let agent = s(t, "agentId").map(|a| take(&a, 8)).unwrap_or_else(|| "-".into());
        l.push(format!("{} | {} | {} | {agent}", short_id(&s(t, "id").unwrap_or_default()), s(t, "status").unwrap_or_default(), s(t, "title").unwrap_or_default()));
    }
    l
}

// ---- parse-level checks of the raw ticket flags (Kotlin refuses them in parseArgs) ----

pub const EFFORT_LEVELS: [&str; 5] = ["low", "medium", "high", "xhigh", "max"];
pub const CONTEXT_MIN: i64 = 10_000;
pub const CONTEXT_MAX: i64 = 10_000_000;

/// `EffortLevels.parse`: Ok(None) = cleared.
pub fn parse_effort(raw: &str) -> Result<Option<String>, String> {
    let t = raw.trim().to_lowercase();
    if t.is_empty() || t == "off" || t == "none" || t == "default" {
        return Ok(None);
    }
    if EFFORT_LEVELS.contains(&t.as_str()) {
        Ok(Some(t))
    } else {
        Err(format!("unknown effort '{}': one of [{}] (or off)", take(&t, 40), EFFORT_LEVELS.join(", ")))
    }
}

/// `ContextLimits.parse`: Ok(None) = cleared.
pub fn parse_context_limit(raw: &str) -> Result<Option<i64>, String> {
    let t = raw.trim().to_lowercase().replace(['_', ','], "");
    if t.is_empty() || t == "0" || t == "off" || t == "none" {
        return Ok(None);
    }
    let not = || format!("context limit '{}' is not a token count (e.g. 200000, 200k, 1m, or off)", take(raw, 20));
    let (num, mult) = match t.chars().last() {
        Some('k') => (&t[..t.len() - 1], 1_000.0),
        Some('m') => (&t[..t.len() - 1], 1_000_000.0),
        _ => (t.as_str(), 1.0),
    };
    let ok_shape = {
        let mut it = num.splitn(2, '.');
        let a = it.next().unwrap_or("");
        let b = it.next();
        !a.is_empty() && a.bytes().all(|c| c.is_ascii_digit()) && b.map_or(true, |b| !b.is_empty() && b.bytes().all(|c| c.is_ascii_digit()))
    };
    if !ok_shape {
        return Err(not());
    }
    let v = num.parse::<f64>().map_err(|_| not())? * mult;
    let v = if v > i32::MAX as f64 { i32::MAX as i64 } else { v as i64 };
    if !(CONTEXT_MIN..=CONTEXT_MAX).contains(&v) {
        return Err(format!("context limit {v} is out of range: {CONTEXT_MIN}..{CONTEXT_MAX} tokens (e.g. 200000 or 200k)"));
    }
    Ok(Some(v))
}

/// `ContextLimits.label`.
pub fn context_label(v: i64) -> String {
    if v % 1_000_000 == 0 {
        format!("{}m", v / 1_000_000)
    } else if v % 100_000 == 0 && v > 1_000_000 {
        format!("{}m", v as f64 / 1_000_000.0)
    } else if v % 1_000 == 0 {
        format!("{}k", v / 1_000)
    } else {
        v.to_string()
    }
}

/// `TicketRunner.problem` on a trimmed, lower-cased value (not `none`).
pub fn runner_problem(v: &str) -> Option<String> {
    let ok = !v.is_empty()
        && v.len() <= 40
        && v.chars().next().is_some_and(|c| c.is_ascii_alphanumeric())
        && v.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-');
    if ok {
        None
    } else {
        Some(format!("runner '{}' must be local, any or a runner name (letters, digits, . _ -, at most 40 characters)", take(v, 40)))
    }
}

/// The `--mcp` entries as (server, purpose); Ok(None) = no `--mcp`; Ok(Some(empty)) = cleared.
pub fn parse_mcp(vals: &[String]) -> Result<Option<Vec<(String, String)>>, String> {
    if vals.is_empty() {
        return Ok(None);
    }
    let mut req = vec![];
    for raw in vals {
        let v = raw.trim();
        if v.to_lowercase() == "none" {
            continue;
        }
        match v.find('=') {
            Some(at) if at > 0 && !v[at + 1..].trim().is_empty() => req.push((v[..at].trim().to_string(), v[at + 1..].trim().to_string())),
            _ => return Err("--mcp expects <server>=<purpose> (a purpose is required: walled by default) or none".into()),
        }
    }
    Ok(Some(req))
}

/// The parse-time refusals of `--effort`, `--runner`, `--mcp` and `--context-limit`, with the Kotlin messages.
pub fn raw_flag_error(p: &Parsed) -> Option<String> {
    if let Some(e) = p.val("effort") {
        if let Err(m) = parse_effort(e) {
            return Some(format!("--effort: {m}"));
        }
    }
    if let Some(r) = p.val("runner") {
        let v = r.trim().to_lowercase();
        if v != "none" {
            if let Some(m) = runner_problem(&v) {
                return Some(format!("--runner: {m}"));
            }
        }
    }
    if let Err(m) = parse_mcp(&p.vals("mcp")) {
        return Some(m);
    }
    if let Some(c) = p.val("context-limit") {
        if let Err(m) = parse_context_limit(c) {
            return Some(format!("--context-limit: {m}"));
        }
    }
    None
}

// ---- commands ----

pub fn validate_board(p: &Parsed, _: Getenv) -> Option<String> {
    raw_flag_error(p)
}

/// `boardListUsageError("tickets", ...)`.
pub fn validate_tickets(p: &Parsed, getenv: Getenv) -> Option<String> {
    if let Some(e) = raw_flag_error(p) {
        return Some(e);
    }
    board_list_usage_error("tickets", p, getenv)
}

pub fn board_list_usage_error(command: &str, p: &Parsed, getenv: Getenv) -> Option<String> {
    if let Some(st) = p.val("status") {
        if !TICKET_STATUSES.contains(&st) {
            return Some(format!("unknown status '{st}'; expected one of {}", TICKET_STATUSES.join(", ")));
        }
    }
    if p.val("epic").is_some() && p.project(getenv).is_none() {
        return Some(format!("{command} --epic needs a project id (--project <id>, or INFANTRY_PROJECT_ID)"));
    }
    None
}

/// `infantry board`.
pub fn run_board(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let project = board_project(p, ctx.getenv);
    let tickets = fetch_board(ctx, &project, None)?;
    let sess = {
        let answer = ctx.stream().request(&Envelope::new("sessions", ""), "sessions", "sessions", true)?;
        serde_json::from_str::<Value>(&answer.data)
            .ok()
            .and_then(|v| v.as_array().cloned())
            .ok_or_else(|| "malformed sessions answer from the daemon".to_string())?
    };
    let columns = p.val("columns").map(|c| {
        let mut v: Vec<String> = vec![];
        for x in c.split(',').map(|x| x.trim().to_lowercase()).filter(|x| !x.is_empty()) {
            if !v.contains(&x) {
                v.push(x);
            }
        }
        v
    });
    let limit = p.val("limit").and_then(|l| l.trim().parse::<i32>().ok()).filter(|l| *l > 0).map(|l| l as usize);
    let o = Options {
        columns,
        limit,
        compact: true,
        queue_shown: cli_limit(ctx, QUEUE_SHOWN_KEY).max(0) as usize,
        done_shown: cli_limit(ctx, DONE_SHOWN_KEY).max(0) as usize,
    };
    let commit_of = |t: &Value| {
        let dir = s(t, "sessionId")
            .and_then(|id| sess.iter().find(|x| s(x, "id").as_deref() == Some(id.as_str())))
            .and_then(|w| s(w, "repoRoot").or_else(|| s(w, "worktreePath")));
        git_commit(s(t, "branch").as_deref(), dir.as_deref())
    };
    for l in render(&tickets, &sess, now_ms(), &commit_of, &o) {
        ctx.out(l);
    }
    Ok(0)
}

/// `infantry tickets` (`listBoard`, epicOnly = false).
pub fn run_tickets(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let project = board_project(p, ctx.getenv);
    let epic = match p.val("epic") {
        Some(prefix) => {
            let pid = if project.trim().is_empty() { None } else { Some(project.as_str()) };
            match resolve_epic_id(ctx, prefix.trim(), pid)? {
                Some(e) => Some(e),
                None => return Ok(1),
            }
        }
        None => None,
    };
    let listed = fetch_board(ctx, &project, epic.as_deref())?;
    let status = p.val("status");
    let rows: Vec<&Value> = listed.iter().filter(|t| status.map_or(true, |st| s(t, "status").as_deref() == Some(st))).collect();
    for l in ticket_rows(&rows, "No tickets.") {
        ctx.out(l);
    }
    Ok(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn cut_title_flattens_and_cuts() {
        assert_eq!(cut_title("  a \n b\t c "), "a b c");
        let long = "x".repeat(120);
        let cut = cut_title(&long);
        assert_eq!(cut.len(), 100);
        assert!(cut.ends_with("..."));
    }

    #[test]
    fn board_golden() {
        let tickets = vec![
            json!({"id":"ticket-aaaa1111","title":"First","status":"todo","position":1}),
            json!({"id":"ticket-bbbb2222","title":"Second","status":"todo","position":0}),
            json!({"id":"ticket-cccc3333","title":"Work","status":"in_progress","position":0,"sessionId":"sess-1234567890","branch":"ticket/x"}),
            json!({"id":"ticket-dddd4444","title":"Gone","status":"pending","position":0,"sessionId":"deadbeef99"}),
        ];
        let sessions = vec![json!({"id":"sess-1234567890","role":"backend","state":"BUSY","lastOutputAt":40_000,"lastAction":"Bash: ls"})];
        let lines = render(&tickets, &sessions, 100_000, &|_| Some("abc1234".into()), &Options { compact: true, ..Options::default() });
        assert_eq!(
            lines,
            vec![
                "todo (2)",
                "  bbbb2222 | Second",
                "  aaaa1111 | First",
                "in_progress (1)",
                "  cccc3333 | Work | backend sess-123 busy | doing 'Bash: ls' | out 1m ago | ticket/x@abc1234",
                "pending (1)",
                "  dddd4444 | Gone | worker deadbeef gone",
            ]
        );
        assert_eq!(render(&[], &[], 0, &|_| None, &Options::default()), vec!["board is empty: no tickets"]);
    }

    #[test]
    fn board_folds_done_and_limit() {
        let tickets: Vec<Value> = (0..7).map(|i| json!({"id":format!("ticket-{i:08}"),"title":"t","status":"done","position":i})).collect();
        let lines = render(&tickets, &[], 0, &|_| None, &Options::default());
        assert_eq!(lines[0], "done (7)");
        assert_eq!(lines[1], "  ... 2 older done not shown");
        assert_eq!(lines[2], "  00000002 | t");
        let lim = render(&tickets, &[], 0, &|_| None, &Options { limit: Some(1), ..Options::default() });
        assert_eq!(lim, vec!["done (7)", "  ... 6 older done not shown", "  00000006 | t"]);
    }

    #[test]
    fn progress_and_waiting_text() {
        let p = json!({"mode":"steps","state":"running","stepsTotal":5,"stepsDone":2,"step":"unit tests","percent":40.0,"startedAt":0,"updatedAt":88_000,"label":"gate"});
        assert_eq!(progress_line(&p, 100_000), "40% step 3/5 unit tests · ~3m left - gate (updated 12s ago)");
        let w = json!({"id":"s","waiting":{"reason":"build","since":0}});
        assert_eq!(waiting_text(&w, 180_000).unwrap(), "WAITING 3m: build");
        assert_eq!(ago(None, 0), "never");
        assert_eq!(bytes(1536), "1.5 KB");
        assert_eq!(duration(3_725_000), "1h02m");
    }

    #[test]
    fn rows_and_prefix() {
        let t = vec![json!({"id":"ticket-63df1aba","title":"A","status":"todo","agentId":"role:backend"}), json!({"id":"ticket-63df9999","title":"B","status":"done"})];
        let refs: Vec<&Value> = t.iter().collect();
        assert_eq!(ticket_rows(&refs, "No tickets."), vec![TICKET_HEADER, "63df1aba | todo | A | role:bac", "63df9999 | done | B | -"]);
        assert_eq!(match_prefix("63df", &t).len(), 2);
        assert_eq!(match_prefix("ticket-63df1", &t).len(), 1);
        assert_eq!(match_prefix("9999", &t).len(), 1);
        let none = match_prefix("b", &t);
        assert_eq!(prefix_report("b", &t, &none)[2], "  a ticket titled 'B' has the id ticket-63df9999: use that");
    }

    #[test]
    fn contract_and_raw_flags() {
        assert_eq!(contract_notice("1.2.3", "contract=1.2.3"), None);
        assert_eq!(contract_notice("1.2.3", "contract=infantry/1.5.0").unwrap().1, "hint: CLI 1.2, daemon 1.5: rebuild/reinstall both for new features");
        assert!(contract_notice("1.2.3", "contract=2.0.0").unwrap().0);
        assert_eq!(parse_effort("bogus").unwrap_err(), "unknown effort 'bogus': one of [low, medium, high, xhigh, max] (or off)");
        assert_eq!(parse_context_limit("200k").unwrap(), Some(200_000));
        assert_eq!(parse_context_limit("1.5M").unwrap(), Some(1_500_000));
        assert!(parse_context_limit("200").unwrap_err().contains("out of range"));
        assert_eq!(context_label(1_500_000), "1.5m");
        assert_eq!(context_label(200_000), "200k");
        assert!(runner_problem("my-box").is_none());
        assert!(parse_mcp(&["x".into()]).is_err());
    }
}
