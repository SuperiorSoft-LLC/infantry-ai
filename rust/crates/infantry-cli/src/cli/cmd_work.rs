// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! PARITY-AUDIT-1 #21: `infantry epics`, `processes [--all] | reap <pid> | reap --all`, `harnesses`, `archive ...` and
//! `agent queue|switch ...`, native on what the daemon already serves: the `epic_list`, `archive_query/apply`,
//! `agent_queue` and `agent_switch` frames, and the `infantry_processes(_reap)` / `infantry_list_harnesses` tools.
//! `agent runner` has no native form yet and goes to the launcher (see [is_native]).
//! A reap only ever reaches processes in the daemon's own registry (the tool refuses any other pid).

use super::args::Parsed;
use super::cmd_board::{board_project, check_contract, resolve_ticket, s, short_id};
use super::conn::Envelope;
use super::plugin_tools;
use super::{CmdResult, Command, Ctx, Getenv, Needs};
use serde_json::{json, Map, Value};

// ---- epics ----

fn project_of(p: &Parsed, getenv: Getenv) -> Option<String> {
    p.val("project").map(|v| v.trim().to_string()).or_else(|| getenv("INFANTRY_PROJECT_ID")).filter(|v| !v.trim().is_empty())
}

fn v_epics(p: &Parsed, getenv: Getenv) -> Option<String> {
    project_of(p, getenv).is_none().then(|| "epics requires a project id (--project <id>, or INFANTRY_PROJECT_ID)".to_string())
}

/// The `epics` listing.
pub fn epic_lines(project: &str, epics: &[Value]) -> Vec<String> {
    if epics.is_empty() {
        return vec![format!("No epics in project {project}.")];
    }
    let mut out = vec!["ID | NAME | GOAL | STORIES".to_string()];
    for e in epics {
        let goal = s(e, "goal").filter(|g| !g.trim().is_empty()).unwrap_or_else(|| "-".into());
        let n = e["storyIds"].as_array().map_or(0, Vec::len);
        out.push(format!("{} | {} | {goal} | {n}", short_id(&s(e, "id").unwrap_or_default()), s(e, "name").unwrap_or_default()));
    }
    out
}

fn run_epics(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let project = project_of(p, ctx.getenv).unwrap_or_default();
    let a = ctx.stream().request(&Envelope::new("epic_list", project.clone()), "epics", "epic_list", true)?;
    let Ok(Value::Array(epics)) = serde_json::from_str::<Value>(&a.data) else {
        ctx.err("error: malformed epics answer from the daemon");
        return Ok(1);
    };
    for l in epic_lines(&project, &epics) {
        ctx.out(l);
    }
    Ok(0)
}

// ---- processes ----

/// `ProcessesCli.usageError`.
pub fn processes_usage_error(args: &[String], all: bool) -> Option<String> {
    match args {
        [] => None,
        [w, ..] if w != "reap" => Some("usage: infantry processes [--all] | infantry processes reap <pid> | infantry processes reap --all".into()),
        [_] if all => None,
        [_, pid] if !all && pid.parse::<i64>().is_ok() => None,
        _ => Some("usage: infantry processes reap <pid> | infantry processes reap --all".into()),
    }
}

fn v_processes(p: &Parsed, _: Getenv) -> Option<String> {
    processes_usage_error(&p.args, p.flag("all"))
}

/// The tool call the words mean: (tool, arguments).
pub fn processes_call(args: &[String], all: bool) -> (&'static str, Map<String, Value>) {
    let mut a = Map::new();
    if args.is_empty() {
        a.insert("all".into(), json!(all));
        return ("infantry_processes", a);
    }
    if all {
        a.insert("all".into(), json!(true));
    } else {
        a.insert("pid".into(), json!(args[1].parse::<i64>().unwrap_or(0)));
    }
    ("infantry_processes_reap", a)
}

fn run_processes(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let (tool, a) = processes_call(&p.args, p.flag("all"));
    let r = plugin_tools::call(ctx, tool, a)?;
    let code = plugin_tools::print(ctx, &r);
    // `ProcessesCli`: a refused reap exits 1.
    Ok(if r.text.trim_start().starts_with("refused") { 1 } else { code })
}

// ---- harnesses ----

fn v_harnesses(p: &Parsed, _: Getenv) -> Option<String> {
    (!p.args.is_empty()).then(|| "harnesses takes no arguments: infantry harnesses".to_string())
}

fn run_harnesses(ctx: &mut Ctx, _: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let r = plugin_tools::call(ctx, "infantry_list_harnesses", Map::new())?;
    if !r.is_error && r.text.trim().is_empty() {
        ctx.out("No harness installed. Install one: infantry harness-install <id> (MCP: infantry_harness_install), or drop a harness module into plugins/.");
        return Ok(0);
    }
    Ok(plugin_tools::print(ctx, &r))
}

// ---- archive ----

fn v_archive(p: &Parsed, _: Getenv) -> Option<String> {
    super::cmd_ticket::archive_usage_error(p.arg(0), p.args.get(1..).unwrap_or(&[]), p.val("epic"), p.val("filter"))
}

// ---- agent queue / switch ----

const QUEUE_USAGE: &str = "usage: infantry agent queue <agent> [add <id> [--at n] | rm <id> | mv <id> <n> | clear]";

/// `agentQueueUsage`.
pub fn agent_usage(args: &[String]) -> Option<String> {
    match args.first().map(String::as_str) {
        Some("switch") => (args.len() < 3).then(|| "usage: infantry agent switch <agent> <ticketId> [--now]".into()),
        Some("runner") => (!(2..=3).contains(&args.len())).then(|| "usage: infantry agent runner <agent> [<local|name|any|none>]".into()),
        Some("queue") if args.len() >= 2 => {
            let rest = &args[2..];
            match rest.first().map(String::as_str) {
                None | Some("clear") => None,
                Some(w @ ("add" | "rm")) => (rest.len() < 2).then(|| format!("usage: infantry agent queue <agent> {w} <id>{}", if w == "add" { " [--at n]" } else { "" })),
                Some("mv") => (rest.len() < 3 || rest[2].parse::<i32>().is_err()).then(|| "usage: infantry agent queue <agent> mv <id> <n>".into()),
                _ => Some(QUEUE_USAGE.into()),
            }
        }
        _ => Some(QUEUE_USAGE.into()),
    }
}

/// `agent runner` saves a whole agent profile; it stays on the launcher until it is native.
pub fn is_native(p: &Parsed) -> bool {
    p.arg(0) != Some("runner")
}

/// The `agent_queue` request.
pub fn queue_request(args: &[String], at: Option<i32>) -> Value {
    let rest = &args[2..];
    let (action, id, at) = match rest.first().map(String::as_str) {
        None => ("list", None, None),
        Some("add") => ("add", Some(&rest[1]), at),
        Some("rm") => ("remove", Some(&rest[1]), None),
        Some("mv") => ("move", Some(&rest[1]), rest[2].parse().ok()),
        _ => ("clear", None, None),
    };
    let mut m = json!({"agentId": args[1], "action": action});
    if let Some(id) = id {
        m["ticketId"] = json!(id);
    }
    if let Some(at) = at {
        m["at"] = json!(at);
    }
    m
}

fn v_agent(p: &Parsed, _: Getenv) -> Option<String> {
    agent_usage(&p.args)
}

fn run_agent(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    if p.arg(0) == Some("switch") {
        let project = board_project(p, ctx.getenv);
        let Some(t) = resolve_ticket(ctx, p.args[2].trim(), &project)? else { return Ok(1) };
        let req = json!({"agentId": p.args[1].trim(), "ticketId": s(&t, "id"), "now": p.flag("now")});
        let a = ctx.stream().request(&Envelope::new("agent_switch", req.to_string()), "ticket", "agent_switch", false)?;
        let moved = super::cmd_board::decode_ticket(&a.data)?;
        ctx.out(format!("switched {} -> {}", s(&moved, "id").unwrap_or_default(), s(&moved, "status").unwrap_or_default()));
        return Ok(0);
    }
    let req = queue_request(&p.args, p.queue_at);
    let list = req["action"] == "list";
    let a = ctx.stream().request(&Envelope::new("agent_queue", req.to_string()), "agent_queue_state", "agent_queue", list)?;
    let Ok(v) = serde_json::from_str::<Value>(&a.data) else {
        ctx.err("error: malformed queue answer from the daemon");
        return Ok(1);
    };
    let tickets = v["tickets"].as_array().cloned().unwrap_or_default();
    let name = s(&v, "name").or_else(|| s(&v, "agentId")).unwrap_or_default();
    let msg = s(&v, "message").map(|m| format!(" ({m})")).unwrap_or_default();
    ctx.out(format!("queue of {name}: {} ticket(s){msg}", tickets.len()));
    for t in &tickets {
        ctx.out(format!("#{} {} {}", t["rank"], s(t, "ticketId").unwrap_or_default(), s(t, "title").unwrap_or_default()));
    }
    Ok(0)
}

pub static EPICS: Command = Command { name: "epics", needs: Needs::Stream, validate: v_epics, run: run_epics };
pub static PROCESSES: Command = Command { name: "processes", needs: Needs::Stream, validate: v_processes, run: run_processes };
pub static HARNESSES: Command = Command { name: "harnesses", needs: Needs::Stream, validate: v_harnesses, run: run_harnesses };
pub static ARCHIVE: Command = Command { name: "archive", needs: Needs::Stream, validate: v_archive, run: super::cmd_ticket::archive_command };
pub static AGENT: Command = Command { name: "agent", needs: Needs::Stream, validate: v_agent, run: run_agent };

#[cfg(test)]
mod tests {
    use super::*;

    fn v(a: &[&str]) -> Vec<String> {
        a.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn processes_words_map_to_the_tools_and_refuse_like_kotlin() {
        assert!(processes_usage_error(&[], false).is_none());
        assert!(processes_usage_error(&v(&["reap"]), true).is_none());
        assert!(processes_usage_error(&v(&["reap", "42"]), false).is_none());
        assert!(processes_usage_error(&v(&["reap"]), false).is_some(), "a bare reap kills nothing");
        assert!(processes_usage_error(&v(&["reap", "chrome.exe"]), false).is_some(), "never by image name");
        assert!(processes_usage_error(&v(&["kill", "1"]), false).is_some());
        assert_eq!(processes_call(&[], true).0, "infantry_processes");
        let (t, a) = processes_call(&v(&["reap", "42"]), false);
        assert_eq!((t, a["pid"].clone()), ("infantry_processes_reap", json!(42)));
    }

    #[test]
    fn agent_queue_requests_and_usage() {
        assert_eq!(queue_request(&v(&["queue", "a1"]), None), json!({"agentId": "a1", "action": "list"}));
        assert_eq!(queue_request(&v(&["queue", "a1", "add", "t1"]), Some(2)), json!({"agentId": "a1", "action": "add", "ticketId": "t1", "at": 2}));
        assert_eq!(queue_request(&v(&["queue", "a1", "mv", "t1", "3"]), None)["at"], 3);
        assert_eq!(queue_request(&v(&["queue", "a1", "rm", "t1"]), None)["action"], "remove");
        assert!(agent_usage(&v(&["queue", "a1", "mv", "t1", "x"])).is_some());
        assert!(agent_usage(&v(&["switch", "a1"])).is_some());
        assert!(agent_usage(&v(&["switch", "a1", "t1"])).is_none());
        assert!(agent_usage(&[]).is_some());
    }

    #[test]
    fn epics_listing() {
        assert_eq!(epic_lines("p", &[]), vec!["No epics in project p."]);
        let e = json!({"id": "epic-1234567890", "name": "N", "goal": "", "storyIds": ["a", "b"]});
        assert_eq!(epic_lines("p", &[e])[1], "34567890 | N | - | 2");
    }
}
