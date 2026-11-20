// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `infantry project list | create <name> | delete <id> [--force]`.
//!
//! A write (`create`, `delete`) first prints the daemon it is about to change (`daemon: <url>`) on stderr, so a test
//! that inherited `INFANTRY_URL` of a live daemon sees where it is writing. `delete` refuses while a session of the
//! project is live unless `--force`.

use super::args::Parsed;
use super::conn::Envelope;
use super::{CmdResult, Command, Ctx, Getenv, Needs};
use serde_json::{json, Value};

const USAGE: &str = "usage: infantry project list | create <name> [--description <goal>] [--location <dir>] | delete <projectId> [--force]";

fn validate(p: &Parsed, _: Getenv) -> Option<String> {
    let n = p.args.len();
    match p.args.first().map(String::as_str) {
        Some("list") if n == 1 => None,
        Some("create") if n == 2 && !p.args[1].trim().is_empty() => None,
        Some("delete") if n == 2 && !p.args[1].trim().is_empty() => None,
        _ => Some(USAGE.into()),
    }
}

fn announce_target(ctx: &mut Ctx) {
    let url = ctx.endpoint.as_ref().map(|e| e.target.url.clone()).unwrap_or_default();
    ctx.err(format!("daemon: {url}"));
}

fn run(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let id = p.args.get(1).cloned().unwrap_or_default();
    match p.args[0].as_str() {
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
    }
}
