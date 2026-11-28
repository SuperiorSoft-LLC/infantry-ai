// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! TICKET-FILES (contract 1.137.0): the files each ticket changed, and the tickets each file was changed by.
//!
//! WRITE SIDE (git, once per ticket, through the host's [`BoardEnv::git`] seam). [`TicketFiles::record`] takes one
//! `git diff -z --raw --numstat -M` and replaces the ticket's rows. The diff comes from, in order: every
//! `Merge ticket/<short> ...` commit (`<m>^1..<m>`, unioned); else the ticket's branch `<base>..<tip>`; else every plain
//! commit whose subject names the short id. The tip is pinned at `refs/agenthq/tickets/<id>`. Rows are written when a
//! ticket goes done; `backfill` rebuilds done tickets that have none.
//!
//! READ SIDE (SQL only, table `ticket_files`). The frame `ticket_files` is answered with `ticket_files_report`
//! (`{text}`), capped at `limits.ticketFiles.maxLines`; `related` ranks tickets by Jaccard overlap of changed paths
//! (top `limits.ticketFiles.relatedTop`). The archive never touches these rows, so archived tickets stay related.
use std::collections::{BTreeMap, BTreeSet, HashMap};

use infantry_orchestrator_api::dto::status;
use infantry_orchestrator_api::OrchError;
use infantry_protocol::Envelope;
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};

use crate::board::Board;
use crate::store::{self, TicketRow};

pub const FRAME: &str = "ticket_files";
pub const REPORT_FRAME: &str = "ticket_files_report";
pub const MAX_LINES_KEY: &str = "limits.ticketFiles.maxLines";
pub const RELATED_TOP_KEY: &str = "limits.ticketFiles.relatedTop";
pub const DEFAULT_MAX_LINES: i64 = 60;
pub const DEFAULT_RELATED_TOP: i64 = 5;
pub const REF_PREFIX: &str = "refs/agenthq/tickets/";
/// Over this many related paths, the paths answer starts with a count line.
pub const BIG_LIST: usize = 500;

/// `TicketFilesQuery`: exactly one of `ticketId` (with `related`), `paths` or `backfill`.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct TicketFilesQuery {
    pub ticket_id: Option<String>,
    pub paths: Vec<String>,
    pub related: bool,
    pub backfill: bool,
    pub all: bool,
    pub limit: Option<i64>,
    pub top: Option<i64>,
}

/// One changed file of one ticket.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TicketFile {
    pub ticket_id: String,
    pub path: String,
    /// git's letter: A, M, D, R, C or T.
    pub status: String,
    pub old_path: Option<String>,
    /// None for a binary file.
    pub added: Option<i64>,
    pub removed: Option<i64>,
    pub tip_sha: Option<String>,
    pub merge_sha: Option<String>,
    pub recorded_at: i64,
}

/// A ticket sharing changed paths with another: `shared` of its `total`, and the Jaccard overlap.
#[derive(Debug, Clone, PartialEq)]
pub struct RelatedTicket {
    pub ticket_id: String,
    pub shared: i64,
    pub total: i64,
    pub jaccard: f64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum FileSource {
    Merge,
    Branch,
    Commits,
}

impl FileSource {
    fn name(self) -> &'static str {
        match self {
            FileSource::Merge => "merge",
            FileSource::Branch => "branch",
            FileSource::Commits => "commits",
        }
    }
}

// ---- SQL -----------------------------------------------------------------------------------------------------------

const SELECT: &str = "SELECT ticket_id, path, status, old_path, added, removed, tip_sha, merge_sha, recorded_at FROM ticket_files";

fn file_row(r: &rusqlite::Row<'_>) -> rusqlite::Result<TicketFile> {
    Ok(TicketFile {
        ticket_id: r.get(0)?,
        path: r.get(1)?,
        status: r.get(2)?,
        old_path: r.get(3)?,
        added: r.get(4)?,
        removed: r.get(5)?,
        tip_sha: r.get(6)?,
        merge_sha: r.get(7)?,
        recorded_at: r.get(8)?,
    })
}

pub fn files_for_ticket(c: &Connection, ticket_id: &str) -> rusqlite::Result<Vec<TicketFile>> {
    c.prepare_cached(&format!("{SELECT} WHERE ticket_id = ?1 ORDER BY path"))?.query_map([ticket_id], file_row)?.collect()
}

/// Rows whose path matches any glob (see [`sql_glob`]), newest first, one per (ticket, path).
pub fn files_for_globs(c: &Connection, globs: &[String]) -> rusqlite::Result<Vec<TicketFile>> {
    let mut seen = BTreeSet::new();
    let mut out = Vec::new();
    let mut done = BTreeSet::new();
    for g in globs.iter().map(|g| sql_glob(g)) {
        if !done.insert(g.clone()) {
            continue;
        }
        let rows: Vec<TicketFile> =
            c.prepare_cached(&format!("{SELECT} WHERE path GLOB ?1 ORDER BY recorded_at DESC, ticket_id"))?.query_map([g], file_row)?.collect::<Result<_, _>>()?;
        for r in rows {
            if seen.insert((r.ticket_id.clone(), r.path.clone())) {
                out.push(r);
            }
        }
    }
    Ok(out)
}

/// Tickets sharing changed paths with `ticket_id`, best Jaccard first (ties: more shared, then id).
pub fn related(c: &Connection, ticket_id: &str) -> rusqlite::Result<Vec<RelatedTicket>> {
    let own: i64 = c.query_row("SELECT count(*) FROM ticket_files WHERE ticket_id = ?1", [ticket_id], |r| r.get(0))?;
    if own == 0 {
        return Ok(Vec::new());
    }
    let sql = "SELECT o.ticket_id, count(*) AS shared, (SELECT count(*) FROM ticket_files t WHERE t.ticket_id = o.ticket_id) \
               FROM ticket_files o JOIN ticket_files m ON m.path = o.path AND m.ticket_id = ?1 \
               WHERE o.ticket_id <> ?1 GROUP BY o.ticket_id";
    let mut out: Vec<RelatedTicket> = c
        .prepare_cached(sql)?
        .query_map([ticket_id], |r| {
            let (id, shared, total): (String, i64, i64) = (r.get(0)?, r.get(1)?, r.get(2)?);
            Ok(RelatedTicket { ticket_id: id, shared, total, jaccard: shared as f64 / (own + total - shared).max(1) as f64 })
        })?
        .collect::<Result<_, _>>()?;
    out.sort_by(|a, b| b.jaccard.total_cmp(&a.jaccard).then(b.shared.cmp(&a.shared)).then(a.ticket_id.cmp(&b.ticket_id)));
    Ok(out)
}

/// Every path changed by a ticket related to `ticket_id`, sorted, unique.
pub fn related_paths(c: &Connection, ticket_id: &str) -> rusqlite::Result<Vec<String>> {
    let mut paths = BTreeSet::new();
    for r in related(c, ticket_id)? {
        for f in files_for_ticket(c, &r.ticket_id)? {
            paths.insert(f.path);
        }
    }
    Ok(paths.into_iter().collect())
}

pub fn recorded_tickets(c: &Connection) -> rusqlite::Result<BTreeSet<String>> {
    c.prepare_cached("SELECT DISTINCT ticket_id FROM ticket_files")?.query_map([], |r| r.get(0))?.collect()
}

/// Replaces every row of `ticket_id` with `rows`, in one transaction.
pub fn replace_files(c: &mut Connection, ticket_id: &str, rows: &[TicketFile]) -> rusqlite::Result<()> {
    let tx = c.transaction()?;
    tx.execute("DELETE FROM ticket_files WHERE ticket_id = ?1", [ticket_id])?;
    {
        let mut ins = tx.prepare_cached(
            "INSERT OR REPLACE INTO ticket_files(ticket_id, path, status, old_path, added, removed, tip_sha, merge_sha, recorded_at) \
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
        )?;
        for f in rows {
            ins.execute(params![ticket_id, f.path, f.status, f.old_path, f.added, f.removed, f.tip_sha, f.merge_sha, f.recorded_at])?;
        }
    }
    tx.commit()
}

// ---- pure helpers --------------------------------------------------------------------------------------------------

/// A user glob as an SQLite GLOB: `**` is `*` (GLOB's `*` crosses `/`); no wildcard = any path containing it.
pub fn sql_glob(glob: &str) -> String {
    let g = glob.trim().replace('\\', "/").replace("**", "*");
    if g.contains(['*', '?', '[']) {
        g
    } else {
        format!("*{g}*")
    }
}

/// `ticket-55476f3c` -> `55476f3c`.
pub fn short_id(ticket_id: &str) -> &str {
    ticket_id.strip_prefix("ticket-").unwrap_or(ticket_id)
}

/// A bare short id is the ticket `ticket-<id>`.
pub fn full_id(raw: &str) -> String {
    let t = raw.trim();
    if t.starts_with("ticket-") {
        t.to_string()
    } else {
        format!("ticket-{t}")
    }
}

/// Parses `git diff -z --raw --numstat -M`: the raw records, then the numstat records.
pub fn parse_diff(out: &str) -> Vec<TicketFile> {
    let tokens: Vec<&str> = out.split('\0').collect();
    let mut raw: Vec<TicketFile> = Vec::new();
    let mut index: HashMap<String, usize> = HashMap::new();
    let mut counts: HashMap<String, (Option<i64>, Option<i64>)> = HashMap::new();
    let mut i = 0;
    let get = |k: usize| tokens.get(k).copied().unwrap_or("").to_string();
    while i < tokens.len() {
        let t = tokens[i].trim_start_matches('\n');
        if t.is_empty() {
            i += 1;
            continue;
        }
        if t.starts_with(':') {
            let letter: String = t.rsplit(' ').next().unwrap_or("").chars().take(1).collect();
            let (path, old, step) = if letter == "R" || letter == "C" { (get(i + 2), Some(get(i + 1)), 3) } else { (get(i + 1), None, 2) };
            let f = TicketFile { path: path.clone(), status: letter, old_path: old, ..Default::default() };
            match index.get(&path) {
                Some(&k) => raw[k] = f,
                None => {
                    index.insert(path, raw.len());
                    raw.push(f);
                }
            }
            i += step;
        } else {
            let fields: Vec<&str> = t.split('\t').collect();
            if fields.len() < 3 {
                i += 1;
                continue;
            }
            let (a, r) = (fields[0].parse().ok(), fields[1].parse().ok());
            if fields[2].is_empty() {
                // A rename: `a\tr\t\0old\0new`.
                counts.insert(get(i + 2), (a, r));
                i += 3;
            } else {
                counts.insert(fields[2].to_string(), (a, r));
                i += 1;
            }
        }
    }
    raw.into_iter()
        .filter(|f| !f.path.is_empty())
        .map(|mut f| {
            if let Some((a, r)) = counts.get(&f.path) {
                f.added = *a;
                f.removed = *r;
            }
            f
        })
        .collect()
}

/// Several diffs of one ticket as one: a path keeps its latest status and its largest counts.
pub fn union(diffs: Vec<Vec<TicketFile>>) -> Vec<TicketFile> {
    let mut by: BTreeMap<String, TicketFile> = BTreeMap::new();
    let max = |x: Option<i64>, y: Option<i64>| if x.is_none() && y.is_none() { None } else { Some(x.unwrap_or(0).max(y.unwrap_or(0))) };
    for f in diffs.into_iter().flatten() {
        let merged = match by.get(&f.path) {
            None => f,
            Some(p) => TicketFile { added: max(p.added, f.added), removed: max(p.removed, f.removed), ..f },
        };
        by.insert(merged.path.clone(), merged);
    }
    by.into_values().collect()
}

pub fn file_line(f: &TicketFile) -> String {
    let counts = if f.added.is_none() && f.removed.is_none() { "bin".to_string() } else { format!("+{} -{}", f.added.unwrap_or(0), f.removed.unwrap_or(0)) };
    format!("{} {counts}  {}{}", f.status, f.path, f.old_path.as_ref().map(|o| format!(" (from {o})")).unwrap_or_default())
}

fn capped(head: String, lines: Vec<String>, limit: usize) -> String {
    let more = lines.len().saturating_sub(limit);
    let mut out = vec![head];
    out.extend(lines.into_iter().take(limit));
    if more > 0 {
        out.push(format!("... {more} more lines (--limit)"));
    }
    out.join("\n")
}

/// The git history a recording matches ids in: merges and plain commits by short id, oldest first.
#[derive(Debug, Default)]
pub struct History {
    pub merges: HashMap<String, Vec<String>>,
    pub commits: HashMap<String, Vec<String>>,
}

fn is_short_id(w: &str) -> bool {
    w.len() == 8 && w.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase()) && w.bytes().any(|b| b.is_ascii_alphabetic()) && w.bytes().any(|b| b.is_ascii_digit())
}

impl History {
    /// From `git log --all --reverse --format=%H%x1f%P%x1f%s`.
    pub fn parse(log: &str) -> History {
        let mut h = History::default();
        for line in log.lines() {
            let parts: Vec<&str> = line.split('\u{1f}').collect();
            if parts.len() < 3 {
                continue;
            }
            let (sha, parents, subject) = (parts[0], parts[1], parts[2]);
            if parents.trim().contains(' ') {
                if !subject.to_ascii_lowercase().starts_with("merge") {
                    continue;
                }
                // `Merge branch 'x' into ticket/<id>` merges the other way: only what precedes " into " counts.
                let head = subject.split(" into ").next().unwrap_or("");
                let mut ids = BTreeSet::new();
                for (k, _) in head.match_indices("ticket/") {
                    let w: String = head[k + 7..].chars().take(8).collect();
                    if w.len() == 8 && w.bytes().all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase()) {
                        ids.insert(w);
                    }
                }
                for id in ids {
                    h.merges.entry(id).or_default().push(sha.to_string());
                }
            } else {
                let ids: BTreeSet<&str> = subject.split(|c: char| !c.is_ascii_alphanumeric()).filter(|w| is_short_id(w)).collect();
                for id in ids {
                    h.commits.entry(id.to_string()).or_default().push(sha.to_string());
                }
            }
        }
        h
    }
}

// ---- board side ----------------------------------------------------------------------------------------------------

/// What one recording did: None = nothing in git names the ticket.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Recorded {
    pub source: Option<FileSource>,
    pub files: usize,
}

fn failed(e: impl std::fmt::Display) -> OrchError {
    OrchError::Failed(format!("ticket_files: {e}"))
}

fn setting_int(c: &Connection, key: &str, default: i64) -> i64 {
    store::setting(c, key)
        .ok()
        .flatten()
        .and_then(|v| v.trim().trim_matches('"').parse::<i64>().ok())
        .filter(|n| *n > 0)
        .unwrap_or(default)
}

impl Board {
    fn git_text(&self, project_id: Option<&str>, args: &[&str]) -> Option<String> {
        self.env().git(project_id, args).map(|b| String::from_utf8_lossy(&b).into_owned())
    }

    fn rev_parse(&self, p: Option<&str>, rev: &str) -> Option<String> {
        self.git_text(p, &["rev-parse", "--verify", "--quiet", &format!("{rev}^{{commit}}")]).map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
    }

    fn diff(&self, p: Option<&str>, from: &str, to: &str) -> Vec<TicketFile> {
        self.git_text(p, &["-c", "core.quotepath=off", "diff", "-z", "--raw", "--numstat", "-M", from, to]).map(|o| parse_diff(&o)).unwrap_or_default()
    }

    fn history(&self, p: Option<&str>) -> Option<History> {
        self.git_text(p, &["log", "--all", "--reverse", "--format=%H%x1f%P%x1f%s"]).map(|l| History::parse(&l))
    }

    /// Records `row`'s files from git, replacing its rows. Never fails on git: no repository = nothing recorded.
    pub fn record_ticket_files(&self, row: &TicketRow, history: Option<&History>) -> Result<Recorded, OrchError> {
        let p = row.project_id.as_deref();
        let owned;
        let log = match history {
            Some(h) => h,
            None => match self.history(p) {
                Some(h) => {
                    owned = h;
                    &owned
                }
                None => return Ok(Recorded { source: None, files: 0 }),
            },
        };
        let short = short_id(&row.id);
        let now = self.env().now_ms();
        let refname = format!("{REF_PREFIX}{}", row.id);
        let tip = row.worktree_branch.as_deref().or(row.branch.as_deref()).and_then(|b| self.rev_parse(p, b)).or_else(|| self.rev_parse(p, &refname));
        let stamp = |rows: Vec<TicketFile>, tip: Option<String>| -> Vec<TicketFile> {
            rows.into_iter().map(|f| TicketFile { ticket_id: row.id.clone(), tip_sha: tip.clone(), recorded_at: now, ..f }).collect()
        };
        let (rows, keep, source) = if let Some(merges) = log.merges.get(short).filter(|m| !m.is_empty()) {
            let diffs = merges.iter().map(|m| self.diff(p, &format!("{m}^1"), m).into_iter().map(|f| TicketFile { merge_sha: Some(m.clone()), ..f }).collect()).collect();
            (stamp(union(diffs), tip.clone()), tip.clone().unwrap_or_else(|| merges.last().cloned().unwrap_or_default()), FileSource::Merge)
        } else if let (Some(t), Some(base)) = (tip.clone(), row.base.as_deref().and_then(|b| self.rev_parse(p, b))) {
            (stamp(self.diff(p, &base, &t), Some(t.clone())), t, FileSource::Branch)
        } else if let Some(commits) = log.commits.get(short).filter(|c| !c.is_empty()) {
            let keep = tip.clone().unwrap_or_else(|| commits.last().cloned().unwrap_or_default());
            let diffs = commits.iter().map(|c| self.diff(p, &format!("{c}^"), c)).collect();
            (stamp(union(diffs), Some(keep.clone())), keep, FileSource::Commits)
        } else {
            return Ok(Recorded { source: None, files: 0 });
        };
        // Keeps the tip reachable after its branch is deleted; a failure only costs reproducibility, never the rows.
        let _ = self.env().git(p, &["update-ref", &refname, &keep]);
        let n = rows.len();
        self.db().write(|c| replace_files(c, &row.id, &rows)).map_err(failed)?;
        Ok(Recorded { source: Some(source), files: n })
    }

    /// The file paths a ticket changed, for the brief's RELATED block (most changed first).
    pub fn ticket_file_paths(&self, ticket_id: &str) -> Vec<String> {
        let mut rows = self.db().read(|c| files_for_ticket(c, ticket_id)).unwrap_or_default();
        rows.sort_by_key(|f| std::cmp::Reverse(f.added.unwrap_or(0) + f.removed.unwrap_or(0)));
        rows.into_iter().map(|f| f.path).collect()
    }

    fn title_of(&self, rows: &HashMap<String, TicketRow>, id: &str) -> String {
        rows.get(id).map(|r| format!("\"{}\"", r.title.chars().take(60).collect::<String>())).unwrap_or_default()
    }

    /// The `ticket_files` frame: the report's text.
    pub fn ticket_files_query(&self, q: &TicketFilesQuery) -> Result<String, OrchError> {
        let (max_lines, top_default) = self.db().read(|c| Ok((setting_int(c, MAX_LINES_KEY, DEFAULT_MAX_LINES), setting_int(c, RELATED_TOP_KEY, DEFAULT_RELATED_TOP)))).map_err(failed)?;
        let limit = q.limit.filter(|n| *n > 0).unwrap_or(max_lines).max(1) as usize;
        let top = q.top.filter(|n| *n > 0).unwrap_or(top_default).max(1) as usize;
        let id = q.ticket_id.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(full_id);
        let paths: Vec<String> = q.paths.iter().map(|p| p.trim().to_string()).filter(|p| !p.is_empty()).collect();
        let (rows, _, _) = self.rows()?;
        if q.backfill {
            return self.backfill_files(&rows, q.all, limit);
        }
        match (id, paths.is_empty()) {
            (Some(id), _) if q.related => self.related_text(&rows, &id, top, limit),
            (Some(id), _) => {
                let fs = self.db().read(|c| files_for_ticket(c, &id)).map_err(failed)?;
                if fs.is_empty() {
                    return Ok(format!("{id}: no files recorded (not done yet, or not found in git; `infantry ticket files --backfill`)"));
                }
                let (a, r): (i64, i64) = fs.iter().fold((0, 0), |(a, r), f| (a + f.added.unwrap_or(0), r + f.removed.unwrap_or(0)));
                let sha = fs.iter().find_map(|f| f.tip_sha.clone().or(f.merge_sha.clone())).map(|s| format!(" @{}", &s[..s.len().min(8)])).unwrap_or_default();
                let head = format!("{id} {}: {} files, +{a} -{r}{sha}", self.title_of(&rows, &id), fs.len());
                Ok(capped(head, fs.iter().map(file_line).collect(), limit))
            }
            (None, false) => {
                let fs = self.db().read(|c| files_for_globs(c, &paths)).map_err(failed)?;
                if fs.is_empty() {
                    return Ok(format!("no ticket changed {}", paths.join(", ")));
                }
                let mut by: BTreeMap<String, Vec<&TicketFile>> = BTreeMap::new();
                let mut tickets: Vec<String> = Vec::new();
                for f in &fs {
                    by.entry(f.path.clone()).or_default().push(f);
                    if !tickets.contains(&f.ticket_id) {
                        tickets.push(f.ticket_id.clone());
                    }
                }
                let mut entries: Vec<_> = by.into_iter().collect();
                entries.sort_by(|a, b| b.1.len().cmp(&a.1.len()).then(a.0.cmp(&b.0)));
                let head = format!("{} files changed by {} tickets ({})", entries.len(), tickets.len(), paths.join(", "));
                let lines = entries.iter().map(|(p, cs)| format!("{p}  {}", cs.iter().map(|f| format!("{}:{}", f.ticket_id, f.status)).collect::<Vec<_>>().join(" "))).collect();
                let mut out = capped(head, lines, limit);
                out.push_str("\ntickets:");
                for t in tickets.iter().take(limit) {
                    out.push_str(&format!("\n  {t} {}", self.title_of(&rows, t)));
                }
                if tickets.len() > limit {
                    out.push_str(&format!("\n  ... {} more", tickets.len() - limit));
                }
                Ok(out)
            }
            (None, true) => Err(OrchError::Refused("ticket_files needs a ticketId, a path glob, or backfill".into())),
        }
    }

    fn related_text(&self, rows: &HashMap<String, TicketRow>, id: &str, top: usize, limit: usize) -> Result<String, OrchError> {
        let (own, chosen) = self.db().read(|c| Ok((files_for_ticket(c, id)?.len(), related(c, id)?))).map_err(failed)?;
        if own == 0 {
            return Ok(format!("{id}: no files recorded, so no related tickets (`infantry ticket files --backfill`)"));
        }
        if chosen.is_empty() {
            return Ok(format!("{id}: no other ticket changed any of its files"));
        }
        let chosen: Vec<RelatedTicket> = chosen.into_iter().take(top).collect();
        let mut by: BTreeMap<String, Vec<TicketFile>> = BTreeMap::new();
        for r in &chosen {
            for f in self.db().read(|c| files_for_ticket(c, &r.ticket_id)).map_err(failed)? {
                by.entry(f.path.clone()).or_default().push(f);
            }
        }
        let mut head = format!("top {} tickets related to {id} by shared files:", chosen.len());
        for r in &chosen {
            head.push_str(&format!("\n  {} {}  shared {}/{} jaccard {:.2}", r.ticket_id, self.title_of(rows, &r.ticket_id), r.shared, r.total, r.jaccard));
        }
        head.push_str(&format!("\n{} files they changed (path  ticket:status):", by.len()));
        let mut entries: Vec<_> = by.into_iter().collect();
        entries.sort_by(|a, b| b.1.len().cmp(&a.1.len()).then(a.0.cmp(&b.0)));
        let lines = entries.iter().map(|(p, cs)| format!("{p}  {}", cs.iter().map(|f| format!("{}:{}", f.ticket_id, f.status)).collect::<Vec<_>>().join(" "))).collect();
        Ok(capped(head, lines, limit))
    }

    fn backfill_files(&self, rows: &HashMap<String, TicketRow>, all: bool, limit: usize) -> Result<String, OrchError> {
        let mut done: Vec<&TicketRow> = rows.values().filter(|r| r.status == status::DONE).collect();
        done.sort_by(|a, b| a.id.cmp(&b.id));
        let have = if all { BTreeSet::new() } else { self.db().read(recorded_tickets).map_err(failed)? };
        let todo: Vec<&TicketRow> = done.iter().copied().filter(|r| !have.contains(&r.id)).collect();
        let mut histories: HashMap<Option<String>, Option<History>> = HashMap::new();
        let mut recovered: BTreeMap<FileSource, usize> = BTreeMap::new();
        let mut missed: Vec<String> = Vec::new();
        for r in &todo {
            let h = histories.entry(r.project_id.clone()).or_insert_with(|| self.history(r.project_id.as_deref()));
            match h.as_ref().map(|h| self.record_ticket_files(r, Some(h))) {
                Some(Ok(Recorded { source: Some(s), .. })) => *recovered.entry(s).or_default() += 1,
                _ => missed.push(r.id.clone()),
            }
        }
        let total: usize = recovered.values().sum();
        let by = [FileSource::Merge, FileSource::Branch, FileSource::Commits].iter().map(|s| format!("{} {}", s.name(), recovered.get(s).copied().unwrap_or(0))).collect::<Vec<_>>().join(", ");
        let mut out = format!("backfill: {} done tickets, {} already recorded, {total} recovered ({by}), {} not recovered", done.len(), done.len() - todo.len(), missed.len());
        if !missed.is_empty() {
            out.push_str(&format!(": {}", missed.iter().take(limit).cloned().collect::<Vec<_>>().join(" ")));
            if missed.len() > limit {
                out.push_str(&format!(" ... {} more", missed.len() - limit));
            }
        }
        Ok(out)
    }
}

/// The `ticket_files_report` envelope.
pub fn report_envelope(text: &str) -> Envelope {
    Envelope::new(REPORT_FRAME, serde_json::json!({ "text": text }).to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn globs_and_ids() {
        assert_eq!(sql_glob("TicketBoard.kt"), "*TicketBoard.kt*");
        assert_eq!(sql_glob("src/**/x.rs"), "src/*/x.rs");
        assert_eq!(full_id("55476f3c"), "ticket-55476f3c");
        assert_eq!(short_id("ticket-55476f3c"), "55476f3c");
    }

    #[test]
    fn parses_a_diff_with_a_rename_and_a_binary() {
        let out = ":100644 100644 a b M\0src/a.rs\0:100644 100644 a b R090\0old.rs\0new.rs\0:000000 100644 0 b A\0img.png\0\
                   3\t1\tsrc/a.rs\0" .to_string()
            + "2\t0\t\0old.rs\0new.rs\0-\t-\timg.png\0";
        let fs = parse_diff(&out);
        assert_eq!(fs.len(), 3);
        assert_eq!((fs[0].status.as_str(), fs[0].added, fs[0].removed), ("M", Some(3), Some(1)));
        assert_eq!((fs[1].path.as_str(), fs[1].old_path.as_deref(), fs[1].added), ("new.rs", Some("old.rs"), Some(2)));
        assert_eq!(file_line(&fs[2]), "A bin  img.png");
    }

    #[test]
    fn history_finds_merges_and_commits() {
        let log = "s1\u{1f}p1\u{1f}FIX 2a38cac2: thing\ns2\u{1f}p1 p2\u{1f}Merge ticket/55476f3c-x into main\ns3\u{1f}p1 p2\u{1f}Merge branch 'main' into ticket/aaaa1111\n";
        let h = History::parse(log);
        assert_eq!(h.commits.get("2a38cac2").unwrap(), &vec!["s1".to_string()]);
        assert_eq!(h.merges.get("55476f3c").unwrap(), &vec!["s2".to_string()]);
        assert!(!h.merges.contains_key("aaaa1111"));
    }

    #[test]
    fn union_keeps_largest_counts() {
        let f = |a| TicketFile { path: "x".into(), status: "M".into(), added: Some(a), removed: Some(1), ..Default::default() };
        let u = union(vec![vec![f(3)], vec![f(7)]]);
        assert_eq!(u.len(), 1);
        assert_eq!(u[0].added, Some(7));
    }
}
