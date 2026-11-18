// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Append-only decisions over the adopted `decisions` table (the 31.sqm triggers abort UPDATE / DELETE).
//! Numbering is per project from 1; `supersedes` must name an ACTIVE decision of the same project.
use crate::db::*;
use serde_json::{json, Value};

pub const SUMMARY_MAX: usize = 200;
pub const BODY_MAX: usize = 16_000;

pub fn parse_ref(r: &str) -> Result<i64, String> {
    let t = r.trim();
    let d = t.strip_prefix(['D', 'd']).unwrap_or(t);
    match d.parse::<i64>() {
        Ok(n) if n >= 1 && d.chars().all(|c| c.is_ascii_digit()) => Ok(n),
        _ => Err(format!("invalid decision ref '{r}'; expected D<n> or <n>")),
    }
}

fn to_json(row: &Row, superseded_by: Option<i64>) -> Value {
    let n = row.i("number");
    json!({
        "projectId": row.s("project_id"), "number": n, "ref": format!("D{n}"),
        "summary": row.s("summary"), "body": row.s("body"),
        "supersedes": row.opt_i("supersedes"), "supersededBy": superseded_by,
        "authorSessionId": row.opt("author_session_id"), "authorAgentId": row.opt("author_agent_id"),
        "authorRole": row.opt("author_role"), "createdAt": row.i("created_at"),
    })
}

pub fn add(db: &dyn Db, p: &dyn Ports, project: &str, summary: &str, body: Option<&str>, supersedes: Option<&str>, by: Option<&SessionInfo>) -> Result<Value, String> {
    let project = project.trim();
    if project.is_empty() {
        return Err("projectId is required".into());
    }
    let line = summary.trim();
    if line.is_empty() {
        return Err("summary is required: one line stating the decision".into());
    }
    if line.contains(['\n', '\r']) {
        return Err("summary must be one line; put the reasons in body".into());
    }
    let n = line.chars().count();
    if n > SUMMARY_MAX {
        return Err(format!("summary is {n} chars; at most {SUMMARY_MAX}"));
    }
    let text = body.unwrap_or("");
    let bn = text.chars().count();
    if bn > BODY_MAX {
        return Err(format!("body is {bn} chars; at most {BODY_MAX}"));
    }
    let target = supersedes.filter(|s| !s.trim().is_empty()).map(parse_ref).transpose()?;
    if let Some(t) = target {
        if db.query("SELECT number FROM decisions WHERE project_id = ? AND number = ?", &[project.into(), t.into()])?.is_empty() {
            return Err(refuse("decision_not_found", format!("no decision D{t} in project '{project}'")));
        }
        if let Some(later) = db.query("SELECT number FROM decisions WHERE project_id = ? AND supersedes = ?", &[project.into(), t.into()])?.first() {
            return Err(refuse("already_superseded", format!("D{t} is already superseded by D{}; supersede the head of its chain instead", later.i("number"))));
        }
    }
    // The number is taken inside the INSERT itself, so two adds can never take the same one.
    db.exec(
        "INSERT INTO decisions(project_id, number, summary, body, supersedes, author_session_id, author_agent_id, author_role, created_at) \
         SELECT ?, COALESCE(MAX(number), 0) + 1, ?, ?, ?, ?, ?, ?, ? FROM decisions WHERE project_id = ?",
        &[
            project.into(),
            line.into(),
            text.into(),
            target.map(V::I).unwrap_or(V::Null),
            by.map(|b| V::T(b.id.clone())).unwrap_or(V::Null),
            by.map(|b| b.agent_id.clone().into()).unwrap_or(V::Null),
            by.map(|b| V::T(b.role.clone())).unwrap_or(V::Null),
            p.now_ms().into(),
            project.into(),
        ],
    )?;
    let number = db.query("SELECT MAX(number) AS n FROM decisions WHERE project_id = ?", &[project.into()])?.first().map(|r| r.i("n")).unwrap_or(0);
    get(db, project, &number.to_string())?.ok_or_else(|| "decision vanished after insert".into())
}

pub fn get(db: &dyn Db, project: &str, r: &str) -> Result<Option<Value>, String> {
    let project = project.trim();
    let n = parse_ref(r)?;
    let Some(row) = db.query("SELECT * FROM decisions WHERE project_id = ? AND number = ?", &[project.into(), n.into()])?.into_iter().next() else {
        return Ok(None);
    };
    let by = db.query("SELECT number FROM decisions WHERE project_id = ? AND supersedes = ?", &[project.into(), n.into()])?.first().map(|r| r.i("number"));
    Ok(Some(to_json(&row, by)))
}

pub fn list(db: &dyn Db, project: &str, include_superseded: bool) -> Result<Vec<Value>, String> {
    let rows = db.query("SELECT * FROM decisions WHERE project_id = ? ORDER BY number ASC", &[project.trim().into()])?;
    let by: std::collections::HashMap<i64, i64> = rows.iter().filter_map(|r| r.opt_i("supersedes").map(|s| (s, r.i("number")))).collect();
    Ok(rows.iter().map(|r| to_json(r, by.get(&r.i("number")).copied())).filter(|d| include_superseded || d["supersededBy"].is_null()).collect())
}
