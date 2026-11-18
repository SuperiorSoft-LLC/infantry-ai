// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `agents`, `session rm|restart|mcp-reconnect` and `kill`, as the Kotlin CLI prints them, plus the helpers the mail and
//! session commands share (`checkContract`, the LOCAL-TIME-1 stamp, the session list).

use super::args::Parsed;
use super::conn::Envelope;
use super::{Command, CmdResult, Ctx, Getenv, Needs};
use serde::Deserialize;
use serde_json::Value;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant};

/// SESSION-RM-1: how long `session rm` / `kill` / `restart` wait for the daemon to confirm, and the poll step.
const SESSION_RM_TIMEOUT_MS: u64 = 30_000;
const SESSION_RM_POLL_MS: u64 = 100;
/// `KillScope.DELETE_REFUSED`.
const DELETE_REFUSED: &str = "session_delete refused: ";
/// `ProjectScope.PRIVILEGED_ROLES`.
const PRIVILEGED_ROLES: [&str; 3] = ["pm", "owner", "assistant"];

static CONTRACT_HINT_SHOWN: AtomicBool = AtomicBool::new(false);

/// LOCAL-TIME-1 (`DisplayZone.stamp`): `2026-11-03 20:41 -05:00`, local time with its offset.
pub fn iso(epoch_millis: i64) -> String {
    use chrono::TimeZone;
    match chrono::Local.timestamp_millis_opt(epoch_millis).single() {
        Some(t) => t.format("%Y-%m-%d %H:%M %:z").to_string(),
        None => epoch_millis.to_string(),
    }
}

/// Kotlin `take(n)` on a string: the first [n] characters.
pub fn take(s: &str, n: usize) -> String {
    s.chars().take(n).collect()
}

/// `contractNotice`: the CLI's contract against a ready frame's `contract=`; (major, line), or None when equal.
pub fn contract_notice(cli: &str, ready_data: &str) -> Option<(bool, String)> {
    let Some(daemon) = ready_data.split(' ').find_map(|f| f.strip_prefix("contract=")).map(|c| c.trim_start_matches("infantry/").to_string()) else {
        return Some((true, format!("warning: no contract version in ready frame: {ready_data}")));
    };
    if daemon == cli {
        return None;
    }
    let major = |v: &str| v.split('.').next().unwrap_or("").to_string();
    let short = |v: &str| v.split('.').take(2).collect::<Vec<_>>().join(".");
    Some(if major(&daemon) != major(cli) {
        (true, format!("warning: incompatible contract: CLI {cli}, daemon {daemon} (ready frame: {ready_data})"))
    } else {
        (false, format!("hint: CLI {}, daemon {}: rebuild/reinstall both for new features", short(cli), short(&daemon)))
    })
}

/// `checkContract`: waits for `ready` and prints the contract notice (a minor one once per process).
pub fn check_contract(ctx: &mut Ctx) -> Result<(), String> {
    let timeout = ctx.stream().answer_timeout_ms();
    let ready = ctx.stream().await_type("ready", timeout)?;
    if let Some((major, line)) = contract_notice(super::CONTRACT, &ready.data) {
        if major || !CONTRACT_HINT_SHOWN.swap(true, Ordering::SeqCst) {
            ctx.err(line);
        }
    }
    Ok(())
}

/// One session as the `sessions` frame carries it (only the fields these commands read).
#[derive(Deserialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Session {
    pub id: String,
    pub state: String,
    #[serde(default)]
    pub live: bool,
    #[serde(default)]
    pub turn_active: Option<bool>,
    #[serde(default)]
    pub child_pid: Option<i64>,
    #[serde(default)]
    pub ticket_id: Option<String>,
    #[serde(default)]
    pub spawner_session_id: Option<String>,
    #[serde(default)]
    pub project_id: Option<String>,
    #[serde(default)]
    pub role: Option<String>,
    #[serde(default)]
    pub provider_session_id: Option<String>,
    #[serde(default)]
    pub run_count: i64,
}

impl Session {
    /// The record as Kotlin decodes it: a BUSY with `turnActive=false` is an at-rest IDLE (BASICS-KILL-1).
    fn normalised(mut self) -> Session {
        if self.turn_active == Some(false) && self.state == "BUSY" {
            self.state = "IDLE".into();
        }
        let running = matches!(self.state.as_str(), "STARTING" | "BUSY" | "WAITING_INPUT");
        self.live = running || (self.child_pid.is_some() && self.live);
        self
    }

    pub fn never_run(&self) -> bool {
        self.provider_session_id.is_none() && self.run_count == 0 && self.state == "IDLE"
    }
}

pub fn decode_sessions(data: &str) -> Result<Vec<Session>, String> {
    let list: Vec<Session> = serde_json::from_str(data).map_err(|e| e.to_string())?;
    Ok(list.into_iter().map(Session::normalised).collect())
}

fn sessions_snapshot(ctx: &mut Ctx) -> Result<Vec<Session>, String> {
    let a = ctx.stream().request(&Envelope::new("sessions", ""), "sessions", "sessions", true)?;
    decode_sessions(&a.data)
}

/// An exact id, else a unique prefix; the refusal text otherwise.
pub fn match_session<'a>(snapshot: &'a [Session], requested: &str) -> Result<&'a Session, String> {
    let exact: Vec<&Session> = snapshot.iter().filter(|s| s.id == requested).collect();
    let matches = if exact.is_empty() { snapshot.iter().filter(|s| s.id.starts_with(requested)).collect() } else { exact };
    match matches.len() {
        1 => Ok(matches[0]),
        0 => Err(format!("no session '{requested}' on this daemon")),
        _ => Err(format!(
            "session id '{requested}' is ambiguous: {}",
            matches.iter().map(|s| s.id.as_str()).collect::<Vec<_>>().join(", ")
        )),
    }
}

/// `KillScope.liveRefusal`.
pub fn live_refusal(s: &Session) -> String {
    format!(
        "session {} is still running ({}); kill it first: call infantry_session_remove with force=true (CLI: infantry session rm {} --force) to kill and remove it in one step",
        s.id, s.state, s.id
    )
}

/// `KillScope.refusal`: who may kill [s], or None when the caller may.
pub fn kill_scope_refusal(s: &Session, snapshot: &[Session], getenv: Getenv) -> Option<String> {
    let caller_id = getenv("INFANTRY_SESSION_ID").map(|v| v.trim().to_string()).filter(|v| !v.is_empty())?;
    if s.id == caller_id {
        return Some(format!(
            "session {} is this session itself; an agent may not kill its own session. End your turn instead, or ask the owner to stop it.",
            s.id
        ));
    }
    if s.spawner_session_id.as_deref() == Some(caller_id.as_str()) {
        return None;
    }
    let caller = snapshot.iter().find(|c| c.id == caller_id);
    let privileged = caller.is_some_and(|c| c.role.as_deref().is_some_and(|r| PRIVILEGED_ROLES.contains(&r.to_lowercase().as_str())));
    if privileged {
        let own = caller
            .and_then(|c| c.project_id.clone())
            .or_else(|| getenv("INFANTRY_PROJECT_ID").map(|p| p.trim().to_string()).filter(|p| !p.is_empty()));
        match own {
            None => return None,
            Some(o) if s.project_id.as_deref() == Some(o.as_str()) => return None,
            Some(o) => {
                return Some(format!(
                    "session {} belongs to project '{}', not this session's project '{o}'; a pm may only kill sessions in its own project. Ask a pm of that project to do it.",
                    s.id,
                    s.project_id.as_deref().unwrap_or("none")
                ))
            }
        }
    }
    Some(format!(
        "session {} was not spawned by this session ({caller_id}); a {} may only kill sessions it spawned itself. Ask the pm to do it: infantry send pm \"please remove session {}\"",
        s.id,
        caller.and_then(|c| c.role.clone()).unwrap_or_else(|| "worker".into()),
        s.id
    ))
}

/// Sends `kill` and waits for the list that shows the session ended; None with the reason written.
fn kill_and_wait(ctx: &mut Ctx, s: &Session, force: bool) -> Result<Option<Session>, String> {
    let data = if force { format!("{} force", s.id) } else { s.id.clone() };
    ctx.stream().send(&Envelope::new("kill", data))?;
    ctx.stream().send(&Envelope::new("sessions", ""))?;
    let deadline = Instant::now() + Duration::from_millis(SESSION_RM_TIMEOUT_MS);
    while Instant::now() < deadline {
        let Some(f) = ctx.stream().next(SESSION_RM_POLL_MS)? else { continue };
        if f.kind == "error" && f.session_id.is_none() {
            ctx.err(format!("error: kill failed: {}", f.data));
            return Ok(None);
        }
        if f.kind == "sessions" {
            let list = decode_sessions(&f.data)?;
            if let Some(after) = list.into_iter().find(|x| x.id == s.id) {
                if !after.live {
                    return Ok(Some(after));
                }
            }
        }
    }
    ctx.err(format!("error: session {} is still running after the kill; retry with --force", s.id));
    Ok(None)
}

fn kill(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let snapshot = sessions_snapshot(ctx)?;
    let s = match match_session(&snapshot, &p.args[0]) {
        Ok(s) => s.clone(),
        Err(e) => {
            ctx.err(format!("error: {e}"));
            return Ok(1);
        }
    };
    if let Some(r) = kill_scope_refusal(&s, &snapshot, ctx.getenv) {
        ctx.err(format!("error: kill refused: {r}"));
        return Ok(1);
    }
    let Some(after) = kill_and_wait(ctx, &s, p.flag("force"))? else { return Ok(1) };
    ctx.out(format!("killed {} ({}); remove it with: infantry session rm {}", s.id, after.state, s.id));
    Ok(0)
}

fn session_restart(ctx: &mut Ctx, requested: &str, force: bool) -> CmdResult {
    let snapshot = sessions_snapshot(ctx)?;
    let s = match match_session(&snapshot, requested) {
        Ok(s) => s.clone(),
        Err(e) => {
            ctx.err(format!("error: {e}"));
            return Ok(1);
        }
    };
    if s.live && !force {
        ctx.err(format!("error: session {} is mid-turn; wait for it, or pass --force to stop it and restart", s.id));
        return Ok(1);
    }
    let body = format!("{{\"sessionId\":{},\"force\":{force}}}", Value::String(s.id.clone()));
    ctx.stream().send(&Envelope::new("session_restart", body))?;
    let deadline = Instant::now() + Duration::from_millis(SESSION_RM_TIMEOUT_MS);
    while Instant::now() < deadline {
        let Some(f) = ctx.stream().next(SESSION_RM_POLL_MS)? else { continue };
        if f.kind == "error" && f.session_id.is_none() {
            ctx.err(format!("error: {}", f.data));
            return Ok(1);
        }
        if f.kind == "session" {
            if let Ok(rec) = serde_json::from_str::<Session>(&f.data).map(Session::normalised) {
                if rec.id == s.id && rec.never_run() {
                    ctx.out(format!("restarted {}; its next turn is composed anew (role notes, project notes, identity)", s.id));
                    return Ok(0);
                }
            }
        }
    }
    ctx.err(format!("error: no confirmation that {} restarted; check `infantry agents --live`", s.id));
    Ok(1)
}

fn session(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let requested = p.args[1].clone();
    check_contract(ctx)?;
    if p.args[0] == "mcp-reconnect" {
        let id = if requested == "self" { ctx.env("INFANTRY_SESSION_ID").filter(|v| !v.trim().is_empty()) } else { Some(requested.clone()) };
        let Some(id) = id else {
            ctx.err("error: INFANTRY_SESSION_ID is not set; pass the session id instead of --self");
            return Ok(1);
        };
        let env = Envelope::new("session_mcp_reconnect", format!("{{\"sessionId\":\"{id}\"}}")).with_session(Some(id.clone()));
        let a = ctx.stream().request(&env, "session_mcp_reconnect", "session mcp-reconnect", false)?;
        ctx.out(a.data);
        return Ok(0);
    }
    if p.args[0] == "restart" {
        return session_restart(ctx, &requested, p.flag("force"));
    }
    let snapshot = sessions_snapshot(ctx)?;
    let s = match match_session(&snapshot, &requested) {
        Ok(s) => s.clone(),
        Err(e) => {
            ctx.err(format!("error: {e}"));
            return Ok(1);
        }
    };
    if s.live {
        if !p.flag("force") {
            ctx.err(format!("error: {}", live_refusal(&s)));
            return Ok(1);
        }
        if let Some(r) = kill_scope_refusal(&s, &snapshot, ctx.getenv) {
            ctx.err(format!("error: session rm refused: {r}"));
            return Ok(1);
        }
        if kill_and_wait(ctx, &s, true)?.is_none() {
            return Ok(1);
        }
    }
    ctx.stream().send(&Envelope::new("session_delete", s.id.clone()))?;
    ctx.stream().send(&Envelope::new("sessions", ""))?;
    let deadline = Instant::now() + Duration::from_millis(SESSION_RM_TIMEOUT_MS);
    while Instant::now() < deadline {
        let Some(f) = ctx.stream().next(SESSION_RM_POLL_MS)? else { continue };
        if f.kind == "error" && f.session_id.is_none() {
            ctx.err(format!("error: {}", f.data.strip_prefix(DELETE_REFUSED).unwrap_or(&f.data)));
            return Ok(1);
        }
        if f.kind == "sessions" {
            let after = decode_sessions(&f.data)?;
            if after.iter().all(|x| x.id != s.id) {
                let ticket = s.ticket_id.as_ref().map(|t| format!(", {t}")).unwrap_or_default();
                ctx.out(format!("removed {} ({}{ticket})", s.id, s.state));
                return Ok(0);
            }
        }
    }
    ctx.err(format!("error: the daemon did not confirm removing {}; check `infantry pods`", s.id));
    Ok(1)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Profile {
    #[serde(default)]
    id: Option<String>,
    name: String,
    role: String,
    #[serde(default)]
    model: Option<String>,
    #[serde(default)]
    project_id: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct RosterRow {
    agent_id: String,
    name: String,
    role: String,
    status: String,
    #[serde(default)]
    title: Option<String>,
    #[serde(default)]
    pending: i64,
}

fn nullable(v: &Option<String>) -> String {
    v.clone().unwrap_or_else(|| "null".into())
}

/// `agentsProjectId`: `--project` (trimmed, even blank), else a non-blank `INFANTRY_PROJECT_ID`, else "".
fn agents_project_id(p: &Parsed, getenv: Getenv) -> String {
    p.val("project")
        .map(|s| s.trim().to_string())
        .or_else(|| getenv("INFANTRY_PROJECT_ID").filter(|v| !v.trim().is_empty()))
        .unwrap_or_default()
}

fn fetch_roster(ctx: &mut Ctx, project: &str) -> Result<Option<Vec<RosterRow>>, String> {
    let body = format!("{{\"projectId\":{}}}", Value::String(project.into()));
    let a = ctx.stream().request(&Envelope::new("agent_roster", body), "roster", "agent_roster", true)?;
    match serde_json::from_str::<Vec<RosterRow>>(&a.data) {
        Ok(r) => Ok(Some(r)),
        Err(e) => {
            ctx.err(format!("error: malformed roster answer from the daemon: {e}"));
            Ok(None)
        }
    }
}

fn sole_project_id(ctx: &mut Ctx) -> Option<String> {
    let a = ctx.stream().request(&Envelope::new("project_list", ""), "projects", "project_list", true).ok()?;
    let v: Vec<Value> = serde_json::from_str(&a.data).ok()?;
    if v.len() == 1 {
        v[0].get("id")?.as_str().map(str::to_string)
    } else {
        None
    }
}

pub fn roster_lines(rows: &[RosterRowView]) -> Vec<String> {
    if rows.is_empty() {
        return vec!["No agents here.".into()];
    }
    let mut out = vec!["ID | NAME | ROLE | STATUS | TITLE | PENDING".to_string()];
    for r in rows {
        out.push(format!("{} | {} | {} | {} | {} | {}", r.0, r.1, r.2, r.3, r.4.as_deref().unwrap_or("-"), r.5));
    }
    out
}

/// (agentId, name, role, status, title, pending): the `agents --live` row.
pub type RosterRowView = (String, String, String, String, Option<String>, i64);

fn agents(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    if p.flag("live") && p.flag("profiles") {
        ctx.err("error: --live and --profiles are exclusive; omit both for the combined list");
        return Ok(1);
    }
    if p.flag("live") {
        let mut project = agents_project_id(p, ctx.getenv);
        if project.trim().is_empty() && p.val("project").is_none() {
            project = sole_project_id(ctx).unwrap_or_default();
        }
        let Some(rows) = fetch_roster(ctx, &project)? else { return Ok(1) };
        let view: Vec<RosterRowView> =
            rows.into_iter().map(|r| (r.agent_id, r.name, r.role, r.status, r.title, r.pending)).collect();
        for l in roster_lines(&view) {
            ctx.out(l);
        }
        return Ok(0);
    }
    let a = ctx.stream().request(&Envelope::new("agent_list", ""), "agents", "agent_list", true)?;
    let profiles: Vec<Profile> = match serde_json::from_str(&a.data) {
        Ok(v) => v,
        Err(e) => {
            ctx.err(format!("error: malformed agents answer from the daemon: {e}"));
            return Ok(1);
        }
    };
    let project = agents_project_id(p, ctx.getenv);
    if p.flag("profiles") || project.trim().is_empty() {
        if profiles.is_empty() {
            ctx.out("No agent profiles.");
        } else {
            ctx.out("ID | NAME | ROLE | MODEL");
            for pr in &profiles {
                ctx.out(format!("{} | {} | {} | {}", nullable(&pr.id), pr.name, pr.role, pr.model.as_deref().unwrap_or("-")));
            }
        }
        return Ok(0);
    }
    let Some(rows) = fetch_roster(ctx, &project)? else { return Ok(1) };
    let live: Vec<&str> = rows.iter().map(|r| r.agent_id.as_str()).collect();
    let idle: Vec<&Profile> = profiles
        .iter()
        .filter(|pr| !pr.id.as_deref().is_some_and(|i| live.contains(&i)) && (pr.project_id.is_none() || pr.project_id.as_deref() == Some(project.as_str())))
        .collect();
    if rows.is_empty() && idle.is_empty() {
        ctx.out("No agents or profiles.");
    } else {
        ctx.out("KIND | ID | NAME | ROLE | STATUS | DETAIL");
        for r in &rows {
            ctx.out(format!("agent | {} | {} | {} | {} | {}", r.agent_id, r.name, r.role, r.status, r.title.as_deref().unwrap_or("-")));
        }
        for pr in idle {
            ctx.out(format!("profile | {} | {} | {} | - | {}", nullable(&pr.id), pr.name, pr.role, pr.model.as_deref().unwrap_or("-")));
        }
    }
    Ok(0)
}

fn validate_kill(p: &Parsed, _: Getenv) -> Option<String> {
    (p.args.len() != 1).then(|| "usage: infantry kill <sessionId|prefix> [--force]".to_string())
}

fn validate_session(p: &Parsed, _: Getenv) -> Option<String> {
    (p.args.len() != 2 || !matches!(p.args[0].as_str(), "rm" | "restart" | "mcp-reconnect")).then(|| {
        "usage: infantry session rm|restart|mcp-reconnect <sessionId|prefix|--self> [--force] (rm --force kills a running session first)".to_string()
    })
}

pub static AGENTS: Command = Command { name: "agents", needs: Needs::Stream, validate: super::no_validation, run: agents };
pub static SESSION: Command = Command { name: "session", needs: Needs::Stream, validate: validate_session, run: session };
pub static KILL: Command = Command { name: "kill", needs: Needs::Stream, validate: validate_kill, run: kill };

#[cfg(test)]
mod tests {
    use super::*;

    fn sess(id: &str, role: Option<&str>, project: Option<&str>, spawner: Option<&str>) -> Session {
        Session {
            id: id.into(),
            state: "IDLE".into(),
            live: false,
            turn_active: None,
            child_pid: None,
            ticket_id: None,
            spawner_session_id: spawner.map(Into::into),
            project_id: project.map(Into::into),
            role: role.map(Into::into),
            provider_session_id: None,
            run_count: 0,
        }
    }

    #[test]
    fn contract_notices() {
        assert_eq!(contract_notice("1.157.0", "contract=1.157.0 instance=a"), None);
        assert_eq!(
            contract_notice("1.157.0", "contract=1.155.0").unwrap(),
            (false, "hint: CLI 1.157, daemon 1.155: rebuild/reinstall both for new features".to_string())
        );
        assert!(contract_notice("1.157.0", "contract=2.0.0").unwrap().1.starts_with("warning: incompatible contract: CLI 1.157.0, daemon 2.0.0"));
        assert_eq!(contract_notice("1.0.0", "x").unwrap().1, "warning: no contract version in ready frame: x");
    }

    #[test]
    fn session_prefix_and_busy_rewrite() {
        let list = decode_sessions(r#"[{"id":"abc1","state":"BUSY","turnActive":false,"live":true},{"id":"abd2","state":"BUSY"}]"#).unwrap();
        assert_eq!(list[0].state, "IDLE");
        assert!(!list[0].live);
        assert!(list[1].live);
        assert_eq!(match_session(&list, "ab").unwrap_err(), "session id 'ab' is ambiguous: abc1, abd2");
        assert_eq!(match_session(&list, "abd").unwrap().id, "abd2");
        assert_eq!(match_session(&list, "z").unwrap_err(), "no session 'z' on this daemon");
    }

    #[test]
    fn kill_scope() {
        let snap = vec![sess("me", Some("dev"), Some("p"), None), sess("kid", None, Some("p"), Some("me")), sess("other", None, Some("q"), None)];
        let env = |n: &str| (n == "INFANTRY_SESSION_ID").then(|| "me".to_string());
        assert!(kill_scope_refusal(&snap[1], &snap, &env).is_none());
        assert!(kill_scope_refusal(&snap[0], &snap, &env).unwrap().contains("is this session itself"));
        assert!(kill_scope_refusal(&snap[2], &snap, &env).unwrap().starts_with("session other was not spawned by this session (me); a dev may only"));
        let owner = |_: &str| None;
        assert!(kill_scope_refusal(&snap[2], &snap, &owner).is_none());
    }

    #[test]
    fn roster_golden() {
        assert_eq!(roster_lines(&[]), ["No agents here."]);
        let rows = vec![("a1".to_string(), "Ann".to_string(), "dev".to_string(), "IDLE".to_string(), None, 2)];
        assert_eq!(roster_lines(&rows), ["ID | NAME | ROLE | STATUS | TITLE | PENDING", "a1 | Ann | dev | IDLE | - | 2"]);
    }
}
