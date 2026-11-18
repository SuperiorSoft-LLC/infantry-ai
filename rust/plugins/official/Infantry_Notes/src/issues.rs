// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Append-only issues over the adopted `issues` and `issue_events` tables. One fix per issue, nothing after it;
//! the ACTIVE issue of a session is the open issue it most recently opened or named in an attempt.
use crate::db::*;
use serde_json::{json, Value};
use std::collections::HashMap;

pub const SUMMARY_MAX: usize = 200;
pub const EVENT_SUMMARY_MAX: usize = 4_000;
pub const LOCATION_MAX: usize = 500;
pub const OUTCOMES: [&str; 3] = ["worked", "failed", "partial"];

pub fn parse_ref(r: &str) -> Result<i64, String> {
    let t = r.trim();
    let d = t.strip_prefix(['I', 'i']).unwrap_or(t);
    match d.parse::<i64>() {
        Ok(n) if n >= 1 && d.chars().all(|c| c.is_ascii_digit()) => Ok(n),
        _ => Err(format!("invalid issue ref '{r}'; expected I<n> or <n>")),
    }
}

fn segments(loc: &str) -> Vec<String> {
    let mut s = loc.trim().replace('\\', "/");
    // strip a trailing `:12` / `:12:3` line suffix
    loop {
        match s.rfind(':') {
            Some(i) if i + 1 < s.len() && s[i + 1..].chars().all(|c| c.is_ascii_digit()) => s.truncate(i),
            _ => break,
        }
    }
    s.split('/').filter(|x| !x.is_empty() && *x != ".").map(String::from).collect()
}

pub fn location_under(location: &str, path: &str) -> bool {
    let prefix = segments(path);
    if prefix.is_empty() {
        return false;
    }
    let seg = segments(location);
    seg.len() >= prefix.len() && seg[..prefix.len()] == prefix[..]
}

fn project(p: &str) -> Result<String, String> {
    let t = p.trim();
    if t.is_empty() {
        Err("projectId is required".into())
    } else {
        Ok(t.to_string())
    }
}

fn one_line(label: &str, what: &str, s: &str, max: usize) -> Result<String, String> {
    let t = s.trim();
    if t.is_empty() {
        return Err(format!("{label} is required: {what}"));
    }
    if t.contains(['\n', '\r']) {
        return Err(format!("{label} must be one line"));
    }
    let n = t.chars().count();
    if n > max {
        return Err(format!("{label} is {n} chars; at most {max}"));
    }
    Ok(t.to_string())
}

fn location(l: Option<&str>) -> Result<Option<String>, String> {
    let Some(w) = l.map(str::trim).filter(|s| !s.is_empty()) else { return Ok(None) };
    if w.contains(['\n', '\r']) {
        return Err("location must be one line".into());
    }
    let n = w.chars().count();
    if n > LOCATION_MAX {
        return Err(format!("location is {n} chars; at most {LOCATION_MAX}"));
    }
    Ok(Some(w.to_string()))
}

/// Strictly past every row already written, so the active-issue order never ties.
fn now(db: &dyn Db, p: &dyn Ports) -> Result<i64, String> {
    let a = db.query("SELECT COALESCE(MAX(created_at), 0) AS n FROM issues", &[])?.first().map(|r| r.i("n")).unwrap_or(0);
    let b = db.query("SELECT COALESCE(MAX(created_at), 0) AS n FROM issue_events", &[])?.first().map(|r| r.i("n")).unwrap_or(0);
    Ok(p.now_ms().max(a.max(b) + 1))
}

fn issue_json(row: &Row, events: &[Row]) -> Value {
    let evs: Vec<Value> = events
        .iter()
        .map(|e| {
            json!({
                "seq": e.i("seq"), "kind": e.s("kind"), "summary": e.s("summary"), "outcome": e.opt("outcome"),
                "location": e.opt("location"), "sessionId": e.opt("session_id"), "agentId": e.opt("agent_id"), "createdAt": e.i("created_at"),
            })
        })
        .collect();
    let n = row.i("number");
    let fixed = events.iter().any(|e| e.s("kind") == "fix");
    json!({
        "projectId": row.s("project_id"), "number": n, "ref": format!("I{n}"), "summary": row.s("summary"), "location": row.s("location"),
        "ticketId": row.opt("ticket_id"), "implicit": row.i("implicit") == 1, "status": if fixed { "fixed" } else { "open" },
        "openedBySession": row.opt("opened_by_session"), "openedByAgent": row.opt("opened_by_agent"), "createdAt": row.i("created_at"), "events": evs,
    })
}

fn insert_issue(db: &dyn Db, p: &dyn Ports, project: &str, summary: &str, loc: &str, ticket: Option<String>, implicit: bool, who: Option<&SessionInfo>) -> Result<i64, String> {
    let at = now(db, p)?;
    db.exec(
        "INSERT INTO issues(project_id, number, summary, location, ticket_id, implicit, opened_by_session, opened_by_agent, created_at) \
         SELECT ?, COALESCE(MAX(number), 0) + 1, ?, ?, ?, ?, ?, ?, ? FROM issues WHERE project_id = ?",
        &[
            project.into(),
            summary.into(),
            loc.into(),
            ticket.into(),
            (implicit as i64).into(),
            who.map(|w| V::T(w.id.clone())).unwrap_or(V::Null),
            who.map(|w| w.agent_id.clone().into()).unwrap_or(V::Null),
            at.into(),
            project.into(),
        ],
    )?;
    Ok(db.query("SELECT MAX(number) AS n FROM issues WHERE project_id = ?", &[project.into()])?.first().map(|r| r.i("n")).unwrap_or(0))
}

#[allow(clippy::too_many_arguments)]
fn insert_event(db: &dyn Db, p: &dyn Ports, project: &str, issue: i64, kind: &str, summary: &str, outcome: Option<&str>, loc: Option<&str>, who: Option<&SessionInfo>) -> Result<(), String> {
    let at = now(db, p)?;
    db.exec(
        "INSERT INTO issue_events(project_id, issue_number, seq, kind, summary, outcome, location, session_id, agent_id, created_at) \
         SELECT ?, ?, COALESCE(MAX(seq), 0) + 1, ?, ?, ?, ?, ?, ?, ? FROM issue_events WHERE project_id = ? AND issue_number = ?",
        &[
            project.into(),
            issue.into(),
            kind.into(),
            summary.into(),
            outcome.map(|s| V::T(s.into())).unwrap_or(V::Null),
            loc.map(|s| V::T(s.into())).unwrap_or(V::Null),
            who.map(|w| V::T(w.id.clone())).unwrap_or(V::Null),
            who.map(|w| w.agent_id.clone().into()).unwrap_or(V::Null),
            at.into(),
            project.into(),
            issue.into(),
        ],
    )?;
    Ok(())
}

fn fixed_numbers(db: &dyn Db, project: &str) -> Result<Vec<i64>, String> {
    Ok(db.query("SELECT issue_number FROM issue_events WHERE project_id = ? AND kind = 'fix'", &[project.into()])?.iter().map(|r| r.i("issue_number")).collect())
}

fn active_in(db: &dyn Db, project: &str, session: Option<&str>) -> Result<Option<i64>, String> {
    let Some(s) = session.map(str::trim).filter(|s| !s.is_empty()) else { return Ok(None) };
    let mut touched: HashMap<i64, i64> = HashMap::new();
    for r in db.query("SELECT number, created_at FROM issues WHERE project_id = ? AND opened_by_session = ?", &[project.into(), s.into()])? {
        let e = touched.entry(r.i("number")).or_insert(0);
        *e = (*e).max(r.i("created_at"));
    }
    for r in db.query("SELECT issue_number, created_at FROM issue_events WHERE project_id = ? AND session_id = ? AND kind = 'attempt'", &[project.into(), s.into()])? {
        let e = touched.entry(r.i("issue_number")).or_insert(0);
        *e = (*e).max(r.i("created_at"));
    }
    let fixed = fixed_numbers(db, project)?;
    Ok(touched.into_iter().filter(|(k, _)| !fixed.contains(k)).max_by_key(|(k, v)| (*v, *k)).map(|(k, _)| k))
}

fn require_open(db: &dyn Db, project: &str, n: i64) -> Result<i64, String> {
    if db.query("SELECT number FROM issues WHERE project_id = ? AND number = ?", &[project.into(), n.into()])?.is_empty() {
        return Err(refuse("issue_not_found", format!("no issue I{n} in project '{project}'")));
    }
    if fixed_numbers(db, project)?.contains(&n) {
        return Err(refuse("already_fixed", format!("I{n} is already fixed; a recurrence is a new issue (infantry issue open)")));
    }
    Ok(n)
}

pub fn open(db: &dyn Db, p: &dyn Ports, project_id: &str, summary: &str, loc: &str, ticket: Option<&str>, who: Option<&SessionInfo>) -> Result<Value, String> {
    let pr = project(project_id)?;
    let line = one_line("summary", "one line naming the issue", summary, SUMMARY_MAX)?;
    let where_ = location(Some(loc))?.ok_or("location is required: the path the issue is about")?;
    let t = ticket.map(str::trim).filter(|s| !s.is_empty()).map(String::from);
    let n = insert_issue(db, p, &pr, &line, &where_, t, false, who)?;
    get(db, &pr, &n.to_string())?.ok_or_else(|| "issue vanished after insert".into())
}

pub fn attempt(db: &dyn Db, p: &dyn Ports, project_id: &str, summary: &str, outcome: Option<&str>, loc: Option<&str>, issue: Option<&str>, who: Option<&SessionInfo>) -> Result<Value, String> {
    let pr = project(project_id)?;
    let text = one_line_multi("summary", "what was tried, or what fixed it", summary)?;
    let result = outcome.map(str::trim).filter(|s| !s.is_empty()).unwrap_or("failed");
    if !OUTCOMES.contains(&result) {
        return Err(format!("invalid outcome '{}'; expected {}", outcome.unwrap_or(""), OUTCOMES.join(" | ")));
    }
    let where_ = location(loc)?;
    let named = issue.filter(|s| !s.trim().is_empty()).map(parse_ref).transpose()?;
    let target = match named {
        Some(n) => require_open(db, &pr, n)?,
        None => match active_in(db, &pr, who.map(|w| w.id.as_str()))? {
            Some(n) => n,
            None => insert_issue(db, p, &pr, &implicit_summary(&text), where_.as_deref().unwrap_or(""), None, true, who)?,
        },
    };
    insert_event(db, p, &pr, target, "attempt", &text, Some(result), where_.as_deref(), who)?;
    get(db, &pr, &target.to_string())?.ok_or_else(|| "issue vanished".into())
}

pub fn fix(db: &dyn Db, p: &dyn Ports, project_id: &str, summary: &str, issue: Option<&str>, who: Option<&SessionInfo>) -> Result<Value, String> {
    let pr = project(project_id)?;
    let text = one_line_multi("summary", "what was tried, or what fixed it", summary)?;
    let named = issue.filter(|s| !s.trim().is_empty()).map(parse_ref).transpose()?;
    let target = match named {
        Some(n) => require_open(db, &pr, n)?,
        None => active_in(db, &pr, who.map(|w| w.id.as_str()))?.ok_or_else(|| {
            refuse(
                "no_active_issue",
                match who {
                    None => "no issue named and no session declared; name one: I<n>".to_string(),
                    Some(w) => format!("session '{}' has no open issue in project '{pr}'; name one: I<n>", w.id),
                },
            )
        })?,
    };
    insert_event(db, p, &pr, target, "fix", &text, None, None, who)?;
    get(db, &pr, &target.to_string())?.ok_or_else(|| "issue vanished".into())
}

fn one_line_multi(label: &str, what: &str, s: &str) -> Result<String, String> {
    let t = s.trim();
    if t.is_empty() {
        return Err(format!("{label} is required: {what}"));
    }
    let n = t.chars().count();
    if n > EVENT_SUMMARY_MAX {
        return Err(format!("{label} is {n} chars; at most {EVENT_SUMMARY_MAX}"));
    }
    Ok(t.to_string())
}

fn implicit_summary(text: &str) -> String {
    let first = text.lines().next().unwrap_or("").trim();
    if first.chars().count() <= SUMMARY_MAX {
        first.to_string()
    } else {
        let cut: String = first.chars().take(SUMMARY_MAX - 3).collect();
        format!("{}...", cut.trim_end())
    }
}

pub fn get(db: &dyn Db, project_id: &str, r: &str) -> Result<Option<Value>, String> {
    let pr = project_id.trim();
    let n = parse_ref(r)?;
    let Some(row) = db.query("SELECT * FROM issues WHERE project_id = ? AND number = ?", &[pr.into(), n.into()])?.into_iter().next() else { return Ok(None) };
    let ev = db.query("SELECT * FROM issue_events WHERE project_id = ? AND issue_number = ? ORDER BY seq ASC", &[pr.into(), n.into()])?;
    Ok(Some(issue_json(&row, &ev)))
}

fn all(db: &dyn Db, pr: &str) -> Result<Vec<Value>, String> {
    let mut by: HashMap<i64, Vec<Row>> = HashMap::new();
    for e in db.query("SELECT * FROM issue_events WHERE project_id = ? ORDER BY issue_number ASC, seq ASC", &[pr.into()])? {
        by.entry(e.i("issue_number")).or_default().push(e);
    }
    Ok(db.query("SELECT * FROM issues WHERE project_id = ? ORDER BY number ASC", &[pr.into()])?.iter().map(|r| issue_json(r, by.get(&r.i("number")).map(|v| v.as_slice()).unwrap_or(&[]))).collect())
}

pub fn list(db: &dyn Db, project_id: &str, include_fixed: bool, loc: Option<&str>) -> Result<Vec<Value>, String> {
    let path = loc.map(str::trim).filter(|s| !s.is_empty());
    Ok(all(db, project_id.trim())?
        .into_iter()
        .filter(|i| include_fixed || i["status"] == "open")
        .filter(|i| path.map(|p| location_under(i["location"].as_str().unwrap_or(""), p)).unwrap_or(true))
        .collect())
}

pub fn precheck(db: &dyn Db, project_id: &str, path: &str) -> Result<Vec<Value>, String> {
    if segments(path).is_empty() {
        return Err("path is required".into());
    }
    let (open, fixed): (Vec<_>, Vec<_>) = all(db, project_id.trim())?.into_iter().filter(|i| location_under(i["location"].as_str().unwrap_or(""), path)).partition(|i| i["status"] == "open");
    Ok(open.into_iter().chain(fixed).collect())
}
