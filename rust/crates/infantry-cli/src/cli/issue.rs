// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `infantry issue list | show | open | attempt | fix | precheck` (Kotlin `CliMain.issue`): the project's issue log.
//! Every subcommand needs a project (`--project`, else `INFANTRY_PROJECT_ID`). The writes are never re-sent.

use super::args::Parsed;
use super::conn::Envelope;
use super::{CmdResult, Command, Ctx, Getenv, Needs};
use serde_json::{json, Value};

const OUTCOMES: [&str; 3] = ["worked", "failed", "partial"];

/// `parseIssueRef`: `I7`, `i7` or `7` (>= 1).
pub fn valid_ref(r: &str) -> bool {
    let t = r.trim();
    let d = t.strip_prefix(['I', 'i']).unwrap_or(t);
    !d.is_empty() && d.chars().all(|c| c.is_ascii_digit()) && d.parse::<i32>().is_ok_and(|n| n >= 1)
}

/// `issueUsageError`.
pub fn usage_error(p: &Parsed, getenv: Getenv) -> Option<String> {
    let args = &p.args;
    let usage = "issue needs a subcommand: infantry issue list [--all] [--location <p>] | show <I7|7> | open --summary <s> --location <p> [--ticket <id>] | attempt [I<n>] --summary <s> [--outcome worked|failed|partial] [--location <p>] | fix [I<n>] --summary <s> | precheck <path>";
    let Some(sub) = args.first() else { return Some(usage.into()) };
    let ref_arg = |command: &str| -> Option<String> {
        if args.len() > 2 {
            Some(format!("issue {command} takes at most one issue ref; put the text in --summary"))
        } else if args.len() == 2 && !valid_ref(&args[1]) {
            Some(format!("invalid issue ref '{}'; expected I<n> or <n>", args[1]))
        } else {
            None
        }
    };
    let no_summary = p.val("summary").is_none_or(|s| s.trim().is_empty());
    let blank1 = args.get(1).is_none_or(|a| a.trim().is_empty());
    let error = match sub.as_str() {
        "list" => (args.len() > 1).then(|| "issue list takes no argument (use --location <p>)".to_string()),
        "show" => {
            if blank1 {
                Some("issue show requires a ref: infantry issue show <I7|7>".into())
            } else if args.len() > 2 || !valid_ref(&args[1]) {
                Some(format!("invalid issue ref '{}'; expected I<n> or <n>", args[1..].join(" ")))
            } else {
                None
            }
        }
        "precheck" => {
            if blank1 {
                Some("issue precheck requires a path: infantry issue precheck <path>".into())
            } else if args.len() > 2 {
                Some("issue precheck takes one path".into())
            } else {
                None
            }
        }
        "open" => {
            if args.len() > 1 {
                Some("issue open takes no argument; use --summary <s> --location <p>".into())
            } else if no_summary {
                Some("issue open requires --summary <s>".into())
            } else if p.val("location").is_none_or(|l| l.trim().is_empty()) {
                Some("issue open requires --location <path>: the path the issue is about".into())
            } else if p.val("outcome").is_some() {
                Some("issue open does not take --outcome".into())
            } else {
                None
            }
        }
        "attempt" => ref_arg(sub).or_else(|| {
            if no_summary {
                Some("issue attempt requires --summary <s>: what was tried".into())
            } else if let Some(o) = p.val("outcome").filter(|o| !OUTCOMES.contains(&o.trim())) {
                Some(format!("invalid --outcome '{o}'; expected worked, failed or partial"))
            } else if p.val("ticket").is_some() {
                Some("issue attempt does not take --ticket".into())
            } else {
                None
            }
        }),
        "fix" => ref_arg(sub).or_else(|| {
            if no_summary {
                Some("issue fix requires --summary <s>: what fixed it".into())
            } else if p.val("outcome").is_some() || p.val("location").is_some() || p.val("ticket").is_some() {
                Some("issue fix takes only [I<n>] and --summary".into())
            } else {
                None
            }
        }),
        _ => return Some(usage.into()),
    };
    error.or_else(|| p.project(getenv).is_none().then(|| format!("issue {sub} needs a project id (--project <id>, or INFANTRY_PROJECT_ID)")))
}

fn s(v: &Value) -> String {
    v.as_str().unwrap_or_default().to_string()
}

pub fn issue_line(i: &Value) -> String {
    let loc = s(&i["location"]);
    format!(
        "{} | {} | {}{}{}",
        s(&i["ref"]),
        s(&i["status"]),
        s(&i["summary"]),
        if loc.is_empty() { String::new() } else { format!(" ({loc})") },
        if i["implicit"] == true { " [implicit]" } else { "" }
    )
}

pub fn event_line(e: &Value) -> String {
    let summary = s(&e["summary"]);
    format!(
        "#{} {}: {}{}{}",
        e["seq"],
        if e["kind"] == "fix" { "fix".to_string() } else { format!("attempt {}", s(&e["outcome"])) },
        summary.lines().next().unwrap_or(""),
        e["location"].as_str().map(|l| format!(" ({l})")).unwrap_or_default(),
        e["sessionId"].as_str().map(|x| format!(" [session {x}]")).unwrap_or_default()
    )
}

/// `DisplayZone.stamp`: `yyyy-MM-dd HH:mm xxx` in local time.
fn stamp(ms: i64) -> String {
    chrono::DateTime::from_timestamp_millis(ms)
        .map(|t| t.with_timezone(&chrono::Local).format("%Y-%m-%d %H:%M %:z").to_string())
        .unwrap_or_else(|| ms.to_string())
}

fn print_issue(ctx: &mut Ctx, i: &Value) {
    ctx.out(format!("{}: {}", s(&i["ref"]), s(&i["summary"])));
    ctx.out(format!("STATUS: {}{}", s(&i["status"]), if i["implicit"] == true { " (implicit: opened by an attempt)" } else { "" }));
    let loc = s(&i["location"]);
    ctx.out(format!("LOCATION: {}", if loc.is_empty() { "-".into() } else { loc }));
    ctx.out(format!("TICKET: {}", i["ticketId"].as_str().unwrap_or("-")));
    let mut by: Vec<String> = Vec::new();
    if let Some(a) = i["openedByAgent"].as_str() {
        by.push(format!("agent {a}"));
    }
    if let Some(x) = i["openedBySession"].as_str() {
        by.push(format!("session {x}"));
    }
    ctx.out(format!("OPENED BY: {}", if by.is_empty() { "-".into() } else { by.join(", ") }));
    ctx.out(format!("CREATED: {}", stamp(i["createdAt"].as_i64().unwrap_or(0))));
    let events = i["events"].as_array().cloned().unwrap_or_default();
    if events.is_empty() {
        ctx.out("No attempts yet.");
        return;
    }
    ctx.out("");
    for e in &events {
        ctx.out(event_line(e));
        for l in s(&e["summary"]).split('\n').skip(1) {
            ctx.out(format!("    {l}"));
        }
    }
}

fn listing(ctx: &mut Ctx, kind: &str, body: Value, what: &str, empty: String, with_events: bool) -> CmdResult {
    let a = ctx.stream().request(&Envelope::new(kind, body.to_string()), "issues", what, true)?;
    let issues = match serde_json::from_str::<Value>(&a.data) {
        Ok(Value::Array(v)) => v,
        Ok(_) => {
            ctx.err("error: malformed issues answer from the daemon: not a list");
            return Ok(1);
        }
        Err(e) => {
            ctx.err(format!("error: malformed issues answer from the daemon: {e}"));
            return Ok(1);
        }
    };
    if issues.is_empty() {
        ctx.out(empty);
        return Ok(0);
    }
    for i in &issues {
        ctx.out(issue_line(i));
        if with_events {
            for e in i["events"].as_array().cloned().unwrap_or_default() {
                ctx.out(format!("    {}", event_line(&e)));
            }
        }
    }
    Ok(0)
}

fn item(ctx: &mut Ctx, kind: &str, body: Value, what: &str, idempotent: bool) -> Result<Option<Value>, String> {
    let a = ctx.stream().request(&Envelope::new(kind, body.to_string()), "issue_item", what, idempotent)?;
    match serde_json::from_str::<Value>(&a.data) {
        Ok(v) if v.is_object() && v.get("ref").is_some() => Ok(Some(v)),
        r => {
            let why = r.err().map(|e| e.to_string()).unwrap_or_else(|| "not an issue".into());
            ctx.err(format!("error: malformed issue_item answer from the daemon: {why}"));
            Ok(None)
        }
    }
}

fn run(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    super::usage_settings::check_contract(ctx);
    let Some(project) = p.project(ctx.getenv) else { return Ok(2) };
    let session = ctx.session_id();
    let args = &p.args;
    let summary = p.val("summary").map(str::to_string);
    let location = p.val("location").map(str::to_string);
    let last_event = |i: &Value| i["events"].as_array().and_then(|e| e.last().cloned()).unwrap_or(Value::Null);
    match args[0].as_str() {
        "list" => {
            let all = p.flag("all");
            let empty = format!(
                "{} in project {project}{}.",
                if all { "No issues" } else { "No open issues" },
                location.as_ref().map(|l| format!(" at {l}")).unwrap_or_default()
            );
            listing(ctx, "issue_list", json!({"projectId": project, "includeFixed": all, "location": location}), "issue_list", empty, false)
        }
        "precheck" => {
            let path = args[1].trim().to_string();
            let empty = format!("No issues at {path} in project {project}.");
            listing(ctx, "issue_precheck", json!({"projectId": project, "path": path}), "issue_precheck", empty, true)
        }
        "show" => {
            let Some(i) = item(ctx, "issue_get", json!({"projectId": project, "ref": args[1].trim()}), "issue_get", true)? else { return Ok(1) };
            print_issue(ctx, &i);
            Ok(0)
        }
        "open" => {
            let body = json!({"projectId": project, "summary": summary, "location": location, "sessionId": session, "ticketId": p.val("ticket").map(str::trim)});
            let Some(i) = item(ctx, "issue_open", body, "issue_open", false)? else { return Ok(1) };
            ctx.out(format!("opened {}: {} ({})", s(&i["ref"]), s(&i["summary"]), s(&i["location"])));
            Ok(0)
        }
        "attempt" => {
            let named = args.get(1).map(|a| a.trim().to_string());
            let body = json!({
                "projectId": project, "summary": summary, "sessionId": session, "outcome": p.val("outcome").map(str::trim),
                "location": location, "issue": named,
            });
            let Some(i) = item(ctx, "issue_attempt", body, "issue_attempt", false)? else { return Ok(1) };
            let e = last_event(&i);
            if named.is_none() && i["implicit"] == true && i["events"].as_array().is_some_and(|v| v.len() == 1) {
                ctx.out(format!("opened implicit {}: {} (no active issue for this session)", s(&i["ref"]), s(&i["summary"])));
            }
            ctx.out(format!("attempt #{} on {} ({}): {}", e["seq"], s(&i["ref"]), s(&e["outcome"]), s(&e["summary"])));
            Ok(0)
        }
        _ => {
            let body = json!({"projectId": project, "summary": summary, "sessionId": session, "issue": args.get(1).map(|a| a.trim())});
            let Some(i) = item(ctx, "issue_fix", body, "issue_fix", false)? else { return Ok(1) };
            ctx.out(format!("fixed {}: {}", s(&i["ref"]), s(&last_event(&i)["summary"])));
            Ok(0)
        }
    }
}

pub static ISSUE: Command = Command { name: "issue", needs: Needs::Stream, validate: usage_error, run };

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn lines_and_refs() {
        assert!(valid_ref("I7") && valid_ref("7") && !valid_ref("I0") && !valid_ref("x"));
        let i: Value = serde_json::from_str(r#"{"ref":"I3","status":"open","summary":"flaky","location":"a/B.kt","implicit":true}"#).unwrap();
        assert_eq!(issue_line(&i), "I3 | open | flaky (a/B.kt) [implicit]");
        let e: Value = serde_json::from_str(r#"{"seq":2,"kind":"attempt","outcome":"failed","summary":"retry\nmore","sessionId":"s1"}"#).unwrap();
        assert_eq!(event_line(&e), "#2 attempt failed: retry [session s1]");
    }
}
