// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The start brief: the worker's first prompt, composed from the ticket, its links, the live RELATED
//! block, its ledger (earlier runs and comments), the latest handoff and the host's own sections
//! (instructions, knowledge, skills). Each section is named, so a start can say how big each part was.
use std::collections::HashMap;

use infantry_orchestrator_api::dto::{link_type, status, Comment, TicketMcp};

use crate::env::BoardEnv;
use crate::rules::take;
use crate::store::{LinkRow, RunRow, TicketRow};

/// The stored RELATED block's markers (KNOWLEDGE-PTR-1).
pub const RELATED_BEGIN: &str = "<!-- RELATED (auto) -->";
pub const RELATED_END: &str = "<!-- /RELATED -->";
/// A line in the human text that turns the RELATED block off.
pub const RELATED_OPT_OUT: &str = "related: off";
/// Most tickets the live RELATED block names.
pub const RELATED_MAX_TICKETS: usize = 8;
/// Most files shown per related ticket.
pub const RELATED_MAX_FILES: usize = 3;
/// Earlier runs and comments the ledger lists (newest kept).
pub const LEDGER_RUNS: usize = 5;
pub const LEDGER_COMMENTS: usize = 8;

pub const FINISH_LINE: &str = "WHEN YOU ARE FINISHED: post a done comment (`infantry ticket comment <id> --kind done \"<summary>\"`: \
the commit sha, what changed, what you tested and did not test, any problem, and a line 'Files read: <paths>'), \
then run `infantry ticket move <id> done`. The board then starts the next ticket of your lane. Then stop.";

pub const BLOCKED_LINE: &str = "If you are blocked, leave the ticket in_progress and post an issue comment saying what blocks you \
(pending is set by the daemon from a blocked_by link; a move there by hand is refused).";

/// A composed brief.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Brief {
    pub prompt: String,
    pub sources: Vec<(String, usize)>,
}

/// What a brief is composed from.
pub struct BriefInput<'a> {
    pub row: &'a TicketRow,
    pub rows: &'a HashMap<String, TicketRow>,
    pub links: &'a [LinkRow],
    pub runs: &'a [RunRow],
    pub comments: &'a [Comment],
    pub role: Option<&'a str>,
    pub agent_id: Option<&'a str>,
    pub agent_name: Option<&'a str>,
    /// Who the worker reports to (the starter's label), None = the owner.
    pub report_to: Option<String>,
    /// The ticket's linked-tasks switch, resolved.
    pub include_linked: bool,
}

/// The description without its stored RELATED block (the live one replaces it).
pub fn strip_related(description: &str) -> &str {
    if !description.starts_with(RELATED_BEGIN) {
        return description;
    }
    let Some(end) = description.find(RELATED_END) else { return description };
    let rest = &description[end + RELATED_END.len()..];
    rest.strip_prefix("\n\n").or_else(|| rest.strip_prefix('\n')).unwrap_or(rest)
}

fn opted_out(description: &str) -> bool {
    strip_related(description).lines().any(|l| l.trim().eq_ignore_ascii_case(RELATED_OPT_OUT))
}

/// A series label and number from a title (`RUST-ORCH-2: ...` -> ("RUST-ORCH", 2)).
pub fn series_of(title: &str) -> Option<(String, u32)> {
    let word: String = title.chars().take_while(|c| c.is_ascii_alphanumeric() || *c == '-').collect();
    let (label, num) = word.rsplit_once('-')?;
    let n: u32 = num.parse().ok()?;
    let ok_part = |p: &str| {
        let mut cs = p.chars();
        matches!(cs.next(), Some('A'..='Z')) && cs.all(|c| c.is_ascii_uppercase() || c.is_ascii_digit())
    };
    (!label.is_empty() && label.split('-').all(ok_part)).then(|| (label.to_string(), n))
}

/// One linked ticket as seen from `id`.
fn linked<'a>(id: &str, links: &'a [LinkRow]) -> Vec<(String, &'a str)> {
    links
        .iter()
        .filter_map(|l| {
            if l.ticket_id == id {
                Some((l.r#type.clone(), l.related_id.as_str()))
            } else if l.related_id == id {
                Some((link_type::reverse(&l.r#type).to_string(), l.ticket_id.as_str()))
            } else {
                None
            }
        })
        .collect()
}

/// The live RELATED block: linked tickets first, then the ticket's series, each with its top files.
pub fn related_block(env: &dyn BoardEnv, row: &TicketRow, rows: &HashMap<String, TicketRow>, links: &[LinkRow]) -> Option<String> {
    if opted_out(&row.description) {
        return None;
    }
    let mut entries: Vec<(&TicketRow, String)> = Vec::new();
    for (ty, other) in linked(&row.id, links) {
        if let Some(r) = rows.get(other) {
            if !entries.iter().any(|(e, _)| e.id == r.id) {
                entries.push((r, format!("linked: {ty}")));
            }
        }
    }
    if let Some((label, _)) = series_of(&row.title) {
        let mut family: Vec<(&TicketRow, u32)> = rows
            .values()
            .filter(|r| r.id != row.id && r.project_id == row.project_id)
            .filter_map(|r| series_of(&r.title).filter(|(l, _)| *l == label).map(|(_, n)| (r, n)))
            .collect();
        family.sort_by_key(|(r, n)| (*n, r.id.clone()));
        for (r, _) in family {
            if !entries.iter().any(|(e, _)| e.id == r.id) {
                entries.push((r, "series".into()));
            }
        }
    }
    entries.truncate(RELATED_MAX_TICKETS);
    if entries.is_empty() {
        return None;
    }
    let mut lines = vec![format!(
        "RELATED (auto, {} tickets; suggestions, regenerated at every start; a line '{RELATED_OPT_OUT}' in the description turns it off):",
        entries.len()
    )];
    for (r, why) in entries {
        let files = env.ticket_files(&r.id);
        let shown: Vec<&str> = files.iter().take(RELATED_MAX_FILES).map(String::as_str).collect();
        let more = files.len().saturating_sub(shown.len());
        let mut line = format!("- {} \"{}\" [{}] ({why})", r.id, take(&one_line(&r.title), 80), r.status);
        if !shown.is_empty() {
            line.push_str(&format!(" files: {}", shown.join(", ")));
            if more > 0 {
                line.push_str(&format!(" (+{more} more)"));
            }
        }
        lines.push(line);
    }
    Some(lines.join("\n"))
}

fn one_line(s: &str) -> String {
    s.replace(['\r', '\n'], " ")
}

/// The ledger: what earlier runs and comments of this ticket left behind.
pub fn ledger(runs: &[RunRow], comments: &[Comment]) -> Option<String> {
    let ended: Vec<&RunRow> = runs.iter().filter(|r| r.ended_at.is_some()).collect();
    let skip = ended.len().saturating_sub(LEDGER_RUNS);
    let mut lines = Vec::new();
    for r in ended.into_iter().skip(skip) {
        let tail: String = r.session_id.chars().rev().take(8).collect::<Vec<_>>().into_iter().rev().collect();
        let detail = r.detail.as_deref().filter(|d| !d.is_empty()).map(|d| format!(": {}", take(&one_line(d), 160))).unwrap_or_default();
        lines.push(format!("- run {tail}: {}{detail}", r.outcome.as_deref().unwrap_or("ended")));
    }
    let shown: Vec<&Comment> = comments.iter().filter(|c| c.kind != "handoff").collect();
    let skip = shown.len().saturating_sub(LEDGER_COMMENTS);
    for c in shown.into_iter().skip(skip) {
        let first = c.body.lines().next().unwrap_or("");
        lines.push(format!("- {} by {} ({}): {}", c.kind, c.author.label(), c.state, take(first, 200)));
    }
    (!lines.is_empty()).then(|| format!("Ticket ledger (earlier runs and comments, oldest first):\n{}", lines.join("\n")))
}

/// Composes the brief.
pub fn compose(env: &dyn BoardEnv, input: &BriefInput<'_>) -> Brief {
    let row = input.row;
    let mut sections: Vec<(String, String)> = Vec::new();
    sections.push((
        "infantry".into(),
        format!(
            "You are running inside Infantry AI. Ticket: {}. Project: {}. Tickets, agents, mail and project knowledge live in the \
             Infantry AI daemon and are reached with the `infantry` CLI (or the infantry_* MCP tools), never through files.\n{}",
            row.id,
            row.project_id.as_deref().unwrap_or("none"),
            BLOCKED_LINE
        ),
    ));
    let mut team = String::from("Team (scoped to your project): `infantry agents --live` lists who is live; `infantry send <role|agentId> \"msg\"` mails a colleague.");
    if let Some(r) = &input.report_to {
        team.push_str(&format!("\nYou report to {r} (`infantry send starter \"<message>\"`)."));
    }
    team.push('\n');
    team.push_str(&FINISH_LINE.replace("<id>", &row.id));
    sections.push(("team".into(), team));
    if let Some(i) = env.instructions(input.role, input.agent_id, row.project_id.as_deref()).filter(|i| !i.trim().is_empty()) {
        sections.push(("instructions".into(), i));
    }
    let who = match (input.agent_name, input.role) {
        (Some(n), Some(r)) => Some(format!("You are {n}, the {r} of this project.")),
        (None, Some(r)) => Some(format!("You work as the {r} of this project.")),
        (Some(n), None) => Some(format!("You are {n}.")),
        _ => None,
    };
    if let Some(w) = who {
        sections.push(("identity".into(), w));
    }
    for (source, text) in env.brief_sections(row.project_id.as_deref(), input.role, input.agent_id, &row.id) {
        if !text.trim().is_empty() {
            sections.push((source, text));
        }
    }
    let related = related_block(env, row, input.rows, input.links);
    let description = if related.is_some() { strip_related(&row.description) } else { row.description.as_str() };
    sections.push(("ticket".into(), format!("{}\n\n{}", row.title, description)));
    // AGENT-TASKS-2: only the LATEST handoff rides along.
    if let Some(h) = input.comments.iter().rev().find(|c| c.kind == "handoff") {
        sections.push(("handoff".into(), format!("## Handoff from the previous worker\n{}", h.body)));
    }
    let images: Vec<String> = row.images.iter().filter_map(|i| env.image_path(row.project_id.as_deref(), i)).collect();
    if !images.is_empty() {
        let lines: Vec<String> = images.iter().map(|p| format!("[image: {p}]")).collect();
        sections.push(("images".into(), format!("Attached images (read each file to see it):\n{}", lines.join("\n"))));
    }
    if let Some(m) = TicketMcp::stored(row.mcp.as_deref()) {
        let lines: Vec<String> = m
            .require
            .iter()
            .map(|r| format!("- {}: {}", r.server.as_deref().or(r.capability.as_deref()).unwrap_or(""), r.purpose.trim()))
            .collect();
        sections.push(("mcp".into(), format!("MCP tools granted to this ticket (use them only for the purpose given):\n{}", lines.join("\n"))));
    }
    if let Some(r) = related {
        sections.push(("related".into(), r));
    }
    if input.include_linked {
        let lines: Vec<String> = linked(&row.id, input.links)
            .into_iter()
            .filter_map(|(ty, other)| input.rows.get(other).map(|r| format!("{} | {} | {} | {ty}", r.id, one_line(&r.title), r.status)))
            .collect();
        if !lines.is_empty() {
            sections.push(("linkedTickets".into(), lines.join("\n")));
        }
    }
    if let Some(l) = ledger(input.runs, input.comments) {
        sections.push(("ledger".into(), l));
    }
    let prompt = sections.iter().map(|(_, t)| t.as_str()).collect::<Vec<_>>().join("\n\n");
    Brief { prompt, sources: sections.into_iter().map(|(s, t)| (s, t.len())).collect() }
}

/// Whether `row` may be started at all (shared by every start path), as the start refusal.
pub fn status_refusal(row: &TicketRow) -> Option<String> {
    match row.status.as_str() {
        status::BACKLOG => Some("a backlog ticket is never started: move it to todo first".into()),
        status::DONE => Some("a done ticket is not started again: move it to todo first".into()),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn series_and_strip() {
        assert_eq!(series_of("RUST-ORCH-2: start"), Some(("RUST-ORCH".into(), 2)));
        assert_eq!(series_of("fix the thing"), None);
        let d = format!("{RELATED_BEGIN}\nold\n{RELATED_END}\n\nhuman");
        assert_eq!(strip_related(&d), "human");
    }
}
