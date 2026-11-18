// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Memory notes over the adopted `memory_notes` table. A note is (project, scope, owner, name); `replace`, `append`
//! and `edit` save modes; project reads fall back to the global notes (project id `*global*`).
use crate::db::*;
use serde_json::{json, Value};

pub const GLOBAL_PROJECT: &str = "*global*";
pub const MAX_NAME: usize = 200;
const SNIPPET_RADIUS: usize = 40;
const SNIPPET_HEAD: usize = 80;
pub const SCOPES: [&str; 3] = ["project", "role", "session"];
const PM_ROLE: &str = "pm";

#[derive(Debug, Clone)]
pub struct Note {
    pub id: String,
    pub project_id: String,
    pub name: String,
    pub folder: String,
    pub description: String,
    pub body: String,
    pub updated_at: String,
    pub scope: String,
    pub owner_id: String,
}

impl Note {
    pub fn json(&self) -> Value {
        json!({ "id": self.id, "projectId": self.project_id, "name": self.name, "folder": self.folder, "description": self.description,
                "body": self.body, "updatedAt": self.updated_at, "scope": self.scope, "ownerId": self.owner_id })
    }
    fn from_row(r: &Row) -> Note {
        Note {
            id: r.s("id"),
            project_id: r.s("project_id"),
            name: r.s("name"),
            folder: r.s("folder"),
            description: r.s("description"),
            body: r.s("body"),
            updated_at: r.s("updated_at"),
            scope: r.s("scope"),
            owner_id: r.s("owner_id"),
        }
    }
}

/// The (scope, owner) a request addresses, and the project override for global.
#[derive(Debug, Clone)]
pub struct Target {
    pub scope: String,
    pub owner: String,
    pub global: bool,
}

/// The calling session as the store needs it.
#[derive(Debug, Clone)]
pub struct Caller {
    pub session_id: String,
    pub role: String,
}

pub fn check_name(raw: &str) -> Result<String, String> {
    let n = raw.trim();
    if n.is_empty() {
        return Err("name is blank".into());
    }
    if n.chars().count() > MAX_NAME {
        return Err(format!("name is longer than {MAX_NAME} characters"));
    }
    if n.chars().any(|c| c.is_control()) {
        return Err("name contains a control character".into());
    }
    if (n.contains('/') && !n.starts_with("plugin:")) || n.contains('\\') {
        return Err("name contains a path separator".into());
    }
    if n.contains("..") {
        return Err("name contains a '..' path traversal".into());
    }
    if n.eq_ignore_ascii_case("INDEX") {
        return Err("name is reserved for the mirror index (INDEX.md)".into());
    }
    Ok(n.to_string())
}

fn check_scope(scope: &str, owner: &str) -> Result<String, String> {
    if !SCOPES.contains(&scope) {
        return Err(format!("scope '{scope}' is not one of {}", SCOPES.join("|")));
    }
    let o = owner.trim();
    if scope == "project" {
        if !o.is_empty() {
            return Err("a project note has no owner id".into());
        }
    } else if o.is_empty() {
        return Err(format!("a {scope} note expects its {} id", if scope == "role" { "role" } else { "session" }));
    }
    Ok(o.to_string())
}

/// Turn (scope, sessionId, role) into the (scope, owner) addressed (NOTES-1 rules).
pub fn resolve(scope: Option<&str>, role: Option<&str>, who: Option<&Caller>) -> Result<Target, String> {
    let scope = scope.map(str::trim).filter(|s| !s.is_empty()).unwrap_or("project");
    match scope {
        "global" => Ok(Target { scope: "project".into(), owner: String::new(), global: true }),
        "project" => Ok(Target { scope: "project".into(), owner: String::new(), global: false }),
        "session" => {
            let w = who.ok_or("a session note expects the calling session (sessionId)")?;
            Ok(Target { scope: "session".into(), owner: w.session_id.clone(), global: false })
        }
        "role" => {
            let wanted = role.map(str::trim).filter(|s| !s.is_empty());
            let r = wanted.map(String::from).or_else(|| who.map(|w| w.role.clone())).ok_or("a role note expects a role (or the calling session's own role)")?;
            if let Some(w) = who {
                if r != w.role && w.role != PM_ROLE {
                    return Err(format!("role '{}' may only address its own role notes; only the pm and the owner may address '{r}'", w.role));
                }
            }
            Ok(Target { scope: "role".into(), owner: r, global: false })
        }
        other => Err(format!("scope '{other}' is not one of project|role|session|global")),
    }
}

fn find(db: &dyn Db, project: &str, scope: &str, owner: &str, name: &str) -> Result<Option<Note>, String> {
    Ok(db
        .query("SELECT * FROM memory_notes WHERE project_id = ? AND scope = ? AND owner_id = ? AND name = ? ORDER BY created_at ASC, id ASC", &[project.into(), scope.into(), owner.into(), name.into()])?
        .first()
        .map(Note::from_row))
}

fn store(db: &dyn Db, p: &dyn Ports, n: &Note) -> Result<(), String> {
    let inserted = db.exec(
        "INSERT OR IGNORE INTO memory_notes(id, project_id, name, folder, description, body, updated_at, created_at, scope, owner_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        &[n.id.clone().into(), n.project_id.clone().into(), n.name.clone().into(), n.folder.clone().into(), n.description.clone().into(), n.body.clone().into(), n.updated_at.clone().into(), p.now_ms().into(), n.scope.clone().into(), n.owner_id.clone().into()],
    )?;
    if inserted == 0 {
        db.exec(
            "UPDATE memory_notes SET project_id = ?, name = ?, folder = ?, description = ?, body = ?, updated_at = ?, scope = ?, owner_id = ? WHERE id = ?",
            &[n.project_id.clone().into(), n.name.clone().into(), n.folder.clone().into(), n.description.clone().into(), n.body.clone().into(), n.updated_at.clone().into(), n.scope.clone().into(), n.owner_id.clone().into(), n.id.clone().into()],
        )?;
    }
    Ok(())
}

fn project_of(p: &str) -> Result<String, String> {
    let t = p.trim();
    if t.is_empty() {
        Err("expects a project id".into())
    } else {
        Ok(t.to_string())
    }
}

#[allow(clippy::too_many_arguments)]
pub fn save(db: &dyn Db, p: &dyn Ports, project: &str, name: &str, description: &str, body: &str, folder: Option<&str>, scope: &str, owner: &str) -> Result<Note, String> {
    let pr = project_of(project)?;
    let nm = check_name(name)?;
    let ow = check_scope(scope, owner)?;
    let existing = find(db, &pr, scope, &ow, &nm)?;
    let note = Note {
        id: existing.as_ref().map(|e| e.id.clone()).unwrap_or_else(|| format!("note-{}", p.new_id())),
        project_id: pr,
        name: nm,
        folder: folder.map(|f| f.trim().to_string()).or_else(|| existing.as_ref().map(|e| e.folder.clone())).unwrap_or_default(),
        description: description.trim().to_string(),
        body: body.to_string(),
        updated_at: p.now_iso(),
        scope: scope.into(),
        owner_id: ow,
    };
    store(db, p, &note)?;
    Ok(note)
}

fn existing_or_refuse(db: &dyn Db, project: &str, name: &str, scope: &str, owner: &str) -> Result<Note, String> {
    let pr = project_of(project)?;
    let nm = check_name(name)?;
    let ow = check_scope(scope, owner)?;
    find(db, &pr, scope, &ow, &nm)?.ok_or_else(|| refuse("note_not_found", format!("no note '{nm}' in project '{pr}'")))
}

fn rewrite(db: &dyn Db, p: &dyn Ports, e: Note, body: String, description: Option<&str>, folder: Option<&str>) -> Result<Note, String> {
    let n = Note {
        body,
        description: description.map(|d| d.trim().to_string()).unwrap_or(e.description.clone()),
        folder: folder.map(|f| f.trim().to_string()).unwrap_or(e.folder.clone()),
        updated_at: p.now_iso(),
        ..e
    };
    store(db, p, &n)?;
    Ok(n)
}

#[allow(clippy::too_many_arguments)]
pub fn append(db: &dyn Db, p: &dyn Ports, project: &str, name: &str, content: &str, description: Option<&str>, folder: Option<&str>, scope: &str, owner: &str) -> Result<Note, String> {
    let e = existing_or_refuse(db, project, name, scope, owner)?;
    let head = e.body.trim_end_matches(['\n', '\r']);
    let body = if head.is_empty() { content.to_string() } else { format!("{head}\n\n{content}") };
    rewrite(db, p, e, body, description, folder)
}

/// Occurrences of `needle` in `hay`, overlaps counted, byte for byte.
pub fn occurrences(hay: &str, needle: &str) -> usize {
    if needle.is_empty() {
        return 0;
    }
    let (h, n) = (hay.as_bytes(), needle.as_bytes());
    (0..=h.len().saturating_sub(n.len())).filter(|&i| h.len() >= n.len() && &h[i..i + n.len()] == n).count()
}

#[allow(clippy::too_many_arguments)]
pub fn edit(db: &dyn Db, p: &dyn Ports, project: &str, name: &str, find_: &str, content: &str, description: Option<&str>, folder: Option<&str>, scope: &str, owner: &str) -> Result<Note, String> {
    if find_.is_empty() {
        return Err("edit expects a non-empty 'find'".into());
    }
    let e = existing_or_refuse(db, project, name, scope, owner)?;
    match occurrences(&e.body, find_) {
        0 => Err(refuse("anchor_not_found", format!("'find' does not occur in note '{}'; read it with memory_get and pass an exact passage", e.name))),
        1 => {
            let at = e.body.find(find_).unwrap_or(0);
            let body = format!("{}{}{}", &e.body[..at], content, &e.body[at + find_.len()..]);
            rewrite(db, p, e, body, description, folder)
        }
        n => Err(refuse("anchor_ambiguous", format!("'find' occurs {n} times in note '{}'; pass a longer passage", e.name))),
    }
}

pub fn get(db: &dyn Db, project: &str, name: &str, scope: &str, owner: &str) -> Result<Option<Note>, String> {
    let (pr, nm) = (project.trim(), name.trim());
    if let Some(n) = find(db, pr, scope, owner.trim(), nm)? {
        return Ok(Some(n));
    }
    if scope == "project" && pr != GLOBAL_PROJECT {
        return find(db, GLOBAL_PROJECT, scope, "", nm);
    }
    Ok(None)
}

pub fn delete(db: &dyn Db, project: &str, name: &str, scope: &str, owner: &str) -> Result<bool, String> {
    let Some(n) = find(db, project.trim(), scope, owner.trim(), name.trim())? else { return Ok(false) };
    db.exec("DELETE FROM memory_notes WHERE id = ?", &[n.id.into()])?;
    Ok(true)
}

pub fn purge_role(db: &dyn Db, role: &str) -> Result<u64, String> {
    db.exec("DELETE FROM memory_notes WHERE scope = 'role' AND owner_id = ?", &[role.into()])
}

pub fn purge_session(db: &dyn Db, session: &str) -> Result<u64, String> {
    db.exec("DELETE FROM memory_notes WHERE scope = 'session' AND owner_id = ?", &[session.into()])
}

/// Notes of a (project, scope, owner) with the body cleared, in first-save order.
pub fn index(db: &dyn Db, project: &str, scope: &str, owner: &str) -> Result<Vec<Note>, String> {
    Ok(db
        .query("SELECT * FROM memory_notes WHERE project_id = ? AND scope = ? AND owner_id = ? ORDER BY created_at ASC, id ASC", &[project.trim().into(), scope.into(), owner.trim().into()])?
        .iter()
        .map(|r| Note { body: String::new(), ..Note::from_row(r) })
        .collect())
}

fn escape_like(t: &str) -> String {
    t.replace('\\', "\\\\").replace('%', "\\%").replace('_', "\\_")
}

fn lower(s: &str) -> Vec<char> {
    s.chars().map(|c| c.to_lowercase().next().unwrap_or(c)).collect()
}

fn find_ci(hay: &[char], needle: &[char]) -> Option<usize> {
    if needle.is_empty() || hay.len() < needle.len() {
        return None;
    }
    (0..=hay.len() - needle.len()).find(|&i| hay[i..i + needle.len()] == *needle)
}

fn snippet(body: &str, term: &str) -> String {
    let chars: Vec<char> = body.chars().collect();
    match find_ci(&lower(body), &lower(term)) {
        None => chars.iter().take(SNIPPET_HEAD).collect(),
        Some(i) => {
            let start = i.saturating_sub(SNIPPET_RADIUS);
            let end = (i + term.chars().count() + SNIPPET_RADIUS).min(chars.len());
            format!("{}{}{}", if start > 0 { "…" } else { "" }, chars[start..end].iter().collect::<String>(), if end < chars.len() { "…" } else { "" })
        }
    }
}

fn rank(n: &Note, term: &[char]) -> u8 {
    if find_ci(&lower(&n.name), term).is_some() {
        2
    } else if find_ci(&lower(&n.description), term).is_some() {
        1
    } else {
        0
    }
}

fn like(db: &dyn Db, project: &str, term: &str) -> Result<Vec<Note>, String> {
    let pat = format!("%{}%", escape_like(term));
    Ok(db
        .query(
            "SELECT * FROM memory_notes WHERE project_id = ? AND (name LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\' OR body LIKE ? ESCAPE '\\') ORDER BY created_at ASC, id ASC",
            &[project.into(), pat.clone().into(), pat.clone().into(), pat.into()],
        )?
        .iter()
        .map(Note::from_row)
        .collect())
}

/// Ranked hits `{name, description, snippet}`: name match, then description, then body; global notes joined for project scope.
pub fn search(db: &dyn Db, project: &str, query: &str, scope: &str, owner: &str) -> Result<Vec<Value>, String> {
    let (pr, term) = (project.trim(), query.trim());
    if pr.is_empty() || term.is_empty() {
        return Ok(vec![]);
    }
    let ow = owner.trim();
    let own: Vec<Note> = like(db, pr, term)?.into_iter().filter(|n| n.scope == scope && n.owner_id == ow).collect();
    let global: Vec<Note> = if scope == "project" && pr != GLOBAL_PROJECT {
        like(db, GLOBAL_PROJECT, term)?.into_iter().filter(|g| g.scope == scope && !own.iter().any(|o| o.name == g.name)).collect()
    } else {
        vec![]
    };
    let t = lower(term);
    let mut all: Vec<Note> = own.into_iter().chain(global).collect();
    all.sort_by_key(|n| std::cmp::Reverse(rank(n, &t))); // stable: ties keep first-save order
    Ok(all.iter().map(|n| json!({ "name": n.name, "description": n.description, "snippet": snippet(&n.body, term) })).collect())
}
