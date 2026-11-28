// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `infantry project list | create <name> | delete <id> [--force] | find <query> | config get|set|unset <id> ... |
//! routing <id> [--file <rules.json> | --clear] | branches <id> | rename <id> <name> | base <id> [<branch>|--clear]`.
//! Each runs on the daemon's existing project frames (`project_list`, `project_save`, `project_config`, `project_base`,
//! `project_git_branches`); a refused project id exits 1 with the daemon's reason.
//!
//! A write (`create`, `delete`) first prints the daemon it is about to change (`daemon: <url>`) on stderr, so a test
//! that inherited `INFANTRY_URL` of a live daemon sees where it is writing. `delete` refuses while a session of the
//! project is live unless `--force`.

use super::args::Parsed;
use super::conn::Envelope;
use super::{CmdResult, Command, Ctx, Getenv, Needs};
use serde_json::{json, Value};

const USAGE: &str = "usage: infantry project list | create <name> [--description <goal>] [--location <dir>] | delete <projectId> [--force] | find [<query>] | config get <projectId> | config set <projectId> <role|dotted.key|auto-remove-done-workers> <value> | config unset <projectId> <dotted.key> | routing <projectId> [--file <rules.json> | --clear] | branches <projectId> | rename <projectId> <name> | base <projectId> [<branch> | --clear]";

fn validate(p: &Parsed, _: Getenv) -> Option<String> {
    let n = p.args.len();
    match p.args.first().map(String::as_str) {
        Some("list") if n == 1 => None,
        Some("create") if n == 2 && !p.args[1].trim().is_empty() => None,
        Some("delete") if n == 2 && !p.args[1].trim().is_empty() => None,
        Some("find") => None,
        Some("config") => match (p.args.get(1).map(String::as_str), n) {
            (Some("get"), 3) | (Some("set"), 5) | (Some("unset"), 4) => None,
            _ => Some(USAGE.into()),
        },
        Some("routing") if n == 2 && !(p.val("file").is_some() && p.flag("clear")) => None,
        Some("branches") if n == 2 => None,
        Some("rename") if n >= 3 && !p.args[2..].join(" ").trim().is_empty() => None,
        Some("base") if n == 2 || (n == 3 && !p.flag("clear")) => None,
        _ => Some(USAGE.into()),
    }
}

fn announce_target(ctx: &mut Ctx) {
    let url = ctx.endpoint.as_ref().map(|e| e.target.url.clone()).unwrap_or_default();
    ctx.err(format!("daemon: {url}"));
}

/// The project of a `project` answer.
fn project_of(data: &str) -> Result<Value, String> {
    serde_json::from_str(data).map_err(|e| format!("unreadable project: {e}"))
}

/// `project_config` with this body; answers the changed (or, with only the id, the current) project.
fn config_request(ctx: &mut Ctx, body: Value, write: bool) -> Result<Value, String> {
    let ans = ctx.stream().request(&Envelope::new("project_config", body.to_string()), "project", "project_config", !write)?;
    project_of(&ans.data)
}

fn print_config(ctx: &mut Ctx, v: &Value) {
    ctx.out(format!("project {}  {}", v["id"].as_str().unwrap_or(""), v["name"].as_str().unwrap_or("")));
    let caps = v["laneCaps"].as_object().cloned().unwrap_or_default();
    if caps.is_empty() {
        ctx.out("lane caps: none (every role runs 1)");
    }
    for (role, n) in &caps {
        ctx.out(format!("cap {role} = {n}"));
    }
    ctx.out(format!("auto-remove-done-workers = {}", if v["autoRemoveDoneWorkers"].as_bool().unwrap_or(false) { "on" } else { "off" }));
    for (k, val) in v["data"].as_object().cloned().unwrap_or_default() {
        ctx.out(format!("{k} = {}", val.as_str().unwrap_or("")));
    }
}

fn config(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let id = p.args[2].trim().to_string();
    match p.args[1].as_str() {
        "get" => {
            let v = config_request(ctx, json!({ "projectId": id }), false)?;
            print_config(ctx, &v);
            Ok(0)
        }
        "unset" => {
            announce_target(ctx);
            let key = p.args[3].trim();
            config_request(ctx, json!({ "projectId": id, "unsetData": [key] }), true)?;
            ctx.out(format!("unset {key} on project {id}"));
            Ok(0)
        }
        _ => {
            let (key, value) = (p.args[3].trim(), p.args[4].trim());
            let body = if key == "auto-remove-done-workers" {
                let on = match value {
                    "on" | "true" => true,
                    "off" | "false" => false,
                    _ => return Err("auto-remove-done-workers takes on|off".into()),
                };
                json!({ "projectId": id, "autoRemoveDoneWorkers": on })
            } else if key.contains('.') {
                json!({ "projectId": id, "data": { key: value } })
            } else {
                let n: i32 = value.parse().ok().filter(|n| *n >= 1).ok_or_else(|| format!("a role's cap is a whole number >= 1, not '{value}'"))?;
                json!({ "projectId": id, "laneCaps": { key: n } })
            };
            announce_target(ctx);
            config_request(ctx, body, true)?;
            ctx.out(format!("set {key} = {value} on project {id}"));
            Ok(0)
        }
    }
}

fn routing(ctx: &mut Ctx, p: &Parsed, id: &str) -> CmdResult {
    let rules: Option<Value> = if p.flag("clear") {
        Some(json!([]))
    } else if let Some(f) = p.val("file") {
        let text = std::fs::read_to_string(f).map_err(|e| format!("cannot read --file {f}: {e}"))?;
        let v: Value = serde_json::from_str(&text).map_err(|e| format!("--file {f} is not JSON: {e}"))?;
        if !v.is_array() {
            return Err(format!("--file {f} must hold a JSON array of routing rules"));
        }
        Some(v)
    } else {
        None
    };
    let write = rules.is_some();
    if write {
        announce_target(ctx);
    }
    let body = match &rules {
        Some(r) => json!({ "projectId": id, "routingRules": r }),
        None => json!({ "projectId": id }),
    };
    let v = config_request(ctx, body, write)?;
    let shown = v["routingRules"].as_array().cloned().unwrap_or_default();
    if shown.is_empty() {
        ctx.out(format!("project {id}: no routing rules"));
    } else {
        ctx.out(serde_json::to_string_pretty(&shown).unwrap_or_default());
    }
    Ok(0)
}

fn find(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let query = p.args[1..].join(" ").trim().to_lowercase();
    let ans = ctx.stream().request(&Envelope::new("project_list", ""), "projects", "project_list", true)?;
    let rows: Vec<Value> = serde_json::from_str(&ans.data).map_err(|e| format!("unreadable project list: {e}"))?;
    let desc = |r: &Value| r["data"]["project.description"].as_str().filter(|s| !s.is_empty()).or(r["goal"].as_str()).unwrap_or("").to_string();
    let hit = |r: &Value| {
        query.is_empty() || [r["name"].as_str().unwrap_or(""), r["id"].as_str().unwrap_or(""), &desc(r)].iter().any(|s| s.to_lowercase().contains(&query))
    };
    let shown: Vec<&Value> = rows.iter().filter(|r| hit(r)).collect();
    if shown.is_empty() {
        ctx.out(format!("no project matches '{query}'"));
        return Ok(0);
    }
    for r in shown {
        ctx.out(format!("{} | {} | {}", r["id"].as_str().unwrap_or(""), r["name"].as_str().unwrap_or(""), desc(r)));
    }
    ctx.out("mail a role there: infantry send <role>@<project id or name> \"text\" --cross-project");
    Ok(0)
}

fn branches(ctx: &mut Ctx, id: &str) -> CmdResult {
    let body = json!({ "projectId": id });
    let ans = ctx.stream().request(&Envelope::new("project_git_branches", body.to_string()), "project_git_branch_list", "project_git_branches", true)?;
    let v: Value = serde_json::from_str(&ans.data).map_err(|e| format!("unreadable branch list: {e}"))?;
    if !v["available"].as_bool().unwrap_or(false) {
        return Err(format!("no branches for project {id}: {}", v["reason"].as_str().unwrap_or("not a git repository")));
    }
    let current = v["current"].as_str().unwrap_or("");
    for b in v["branches"].as_array().cloned().unwrap_or_default() {
        let name = b["name"].as_str().unwrap_or("");
        ctx.out(format!("{} {name}", if name == current { "*" } else { " " }));
    }
    if v["truncated"].as_bool().unwrap_or(false) {
        ctx.out("(list truncated)");
    }
    Ok(0)
}

fn base(ctx: &mut Ctx, p: &Parsed, id: &str) -> CmdResult {
    let branch = p.args.get(2).map(|b| b.trim().to_string());
    let write = branch.is_some() || p.flag("clear");
    if write {
        announce_target(ctx);
    }
    let body = json!({ "projectId": id, "branch": branch, "clear": p.flag("clear") });
    let ans = ctx.stream().request(&Envelope::new("project_base", body.to_string()), "project", "project_base", !write)?;
    let v = project_of(&ans.data)?;
    let b = v["baseBranch"].as_str().unwrap_or("");
    ctx.out(if b.is_empty() { format!("project {id}: no base branch (INFANTRY_BASE_BRANCH applies, else a start is refused)") } else { format!("project {id}: base {b}") });
    Ok(0)
}

fn run(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let id = p.args.get(1).cloned().unwrap_or_default();
    match p.args[0].as_str() {
        "find" => find(ctx, p),
        "config" => config(ctx, p),
        "routing" => routing(ctx, p, id.trim()),
        "branches" => branches(ctx, id.trim()),
        "base" => base(ctx, p, id.trim()),
        "rename" => {
            announce_target(ctx);
            let body = json!({ "id": id.trim(), "name": p.args[2..].join(" ").trim() });
            let ans = ctx.stream().request(&Envelope::new("project_save", body.to_string()), "project", "project_save", false)?;
            let v = project_of(&ans.data)?;
            ctx.out(format!("renamed project {}  {}", v["id"].as_str().unwrap_or(""), v["name"].as_str().unwrap_or("")));
            Ok(0)
        }
        "list" => {
            let ans = ctx.stream().request(&Envelope::new("project_list", ""), "projects", "project_list", true)?;
            let rows: Vec<Value> = serde_json::from_str(&ans.data).map_err(|e| format!("unreadable project list: {e}"))?;
            if rows.is_empty() {
                ctx.out("no projects");
            }
            for r in &rows {
                ctx.out(format!("{}  {}  {}", r["id"].as_str().unwrap_or(""), r["name"].as_str().unwrap_or(""), r["directory"].as_str().unwrap_or("")));
            }
            Ok(0)
        }
        "create" => {
            announce_target(ctx);
            let mut body = json!({ "name": id });
            if let Some(g) = p.val("description") {
                body["goal"] = json!(g);
            }
            if let Some(d) = p.val("location") {
                body["directory"] = json!(d);
            }
            let ans = ctx.stream().request(&Envelope::new("project_save", &body.to_string()), "project", "project_save", false)?;
            let v: Value = serde_json::from_str(&ans.data).map_err(|e| format!("unreadable project: {e}"))?;
            ctx.out(format!("created project {}  {}", v["id"].as_str().unwrap_or(""), v["name"].as_str().unwrap_or("")));
            Ok(0)
        }
        _ => {
            announce_target(ctx);
            let body = json!({ "projectId": id, "force": p.flag("force") });
            let ans = ctx.stream().request(&Envelope::new("project_delete", &body.to_string()), "project_deleted", "project_delete", false)?;
            let v: Value = serde_json::from_str(&ans.data).map_err(|e| format!("unreadable project: {e}"))?;
            ctx.out(format!("deleted project {}  {}", v["id"].as_str().unwrap_or(""), v["name"].as_str().unwrap_or("")));
            Ok(0)
        }
    }
}

pub static PROJECT: Command = Command { name: "project", needs: Needs::Stream, validate, run };

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cli::args::parse;

    fn v(a: &[&str]) -> Vec<String> {
        a.iter().map(|s| s.to_string()).collect()
    }

    fn check(a: &[&str]) -> Option<String> {
        validate(&parse(&v(a)).unwrap(), &|_| None)
    }

    #[test]
    fn usage_rules() {
        assert!(check(&["project", "list"]).is_none());
        assert!(check(&["project", "create", "Demo"]).is_none());
        assert!(check(&["project", "delete", "project-1", "--force"]).is_none());
        assert!(check(&["project"]).is_some());
        assert!(check(&["project", "delete"]).is_some());
        assert!(check(&["project", "list", "x"]).is_some());
        assert!(check(&["project", "find"]).is_none());
        assert!(check(&["project", "find", "demo", "app"]).is_none());
        assert!(check(&["project", "config", "get", "p1"]).is_none());
        assert!(check(&["project", "config", "set", "p1", "backend", "2"]).is_none());
        assert!(check(&["project", "config", "unset", "p1", "build.root"]).is_none());
        assert!(check(&["project", "config", "get"]).is_some());
        assert!(check(&["project", "config", "set", "p1", "backend"]).is_some());
        assert!(check(&["project", "routing", "p1"]).is_none());
        assert!(check(&["project", "routing", "p1", "--clear"]).is_none());
        assert!(check(&["project", "routing", "p1", "--file", "r.json", "--clear"]).is_some());
        assert!(check(&["project", "branches", "p1"]).is_none());
        assert!(check(&["project", "branches"]).is_some());
        assert!(check(&["project", "rename", "p1", "New", "Name"]).is_none());
        assert!(check(&["project", "rename", "p1"]).is_some());
        assert!(check(&["project", "base", "p1"]).is_none());
        assert!(check(&["project", "base", "p1", "main"]).is_none());
        assert!(check(&["project", "base", "p1", "--clear"]).is_none());
        assert!(check(&["project", "base", "p1", "main", "--clear"]).is_some());
    }
}
