// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `infantry worktrees stale [all]`: a read-only report of every ticket worktree (ticket, status, merged, dirty,
//! live session, size on disk). It deletes nothing; the owner approves any cleanup. The top 20 by size are listed,
//! `all` lists every row.

use super::args::Parsed;
use super::conn::Envelope;
use super::{CmdResult, Command, Ctx, Getenv, Needs};
use serde_json::Value;

const TOP: usize = 20;
/// The daemon walks every worktree folder, so the answer can take minutes on a big board.
const ANSWER_TIMEOUT_MS: u64 = 600_000;

fn validate(p: &Parsed, _: Getenv) -> Option<String> {
    match p.args.first().map(String::as_str) {
        Some("stale") if p.args.len() <= 2 && p.args.get(1).map_or(true, |a| a == "all") => None,
        _ => Some("usage: infantry worktrees stale [all]".into()),
    }
}

fn gb(bytes: u64) -> String {
    format!("{:.2} GB", bytes as f64 / 1_073_741_824.0)
}

fn yn(v: bool) -> &'static str {
    if v { "yes" } else { "no" }
}

/// The report text for the rows the daemon answered; pure, so it is tested.
pub fn render(rows: &[Value], all: bool) -> Vec<String> {
    let size = |r: &Value| r["sizeBytes"].as_u64().unwrap_or(0);
    let flag = |r: &Value, k: &str| r[k].as_bool().unwrap_or(false);
    let mut sorted: Vec<&Value> = rows.iter().collect();
    sorted.sort_by_key(|r| std::cmp::Reverse(size(r)));
    let total: u64 = sorted.iter().map(|r| size(r)).sum();
    let count = |f: &dyn Fn(&Value) -> bool| sorted.iter().filter(|r| f(r)).count();
    let done = count(&|r| r["ticketStatus"].as_str() == Some("done"));
    let safe = count(&|r| r["ticketStatus"].as_str() == Some("done") && flag(r, "merged") && !flag(r, "dirty") && !flag(r, "live"));
    let mut out = vec![format!(
        "{} worktrees, {} on disk; ticket done: {done}; merged: {}; dirty: {}; live session: {}; no ticket found: {}",
        sorted.len(),
        gb(total),
        count(&|r| flag(r, "merged")),
        count(&|r| flag(r, "dirty")),
        count(&|r| flag(r, "live")),
        count(&|r| r["ticketId"].is_null()),
    )];
    let safe_bytes: u64 = sorted.iter().filter(|r| r["ticketStatus"].as_str() == Some("done") && flag(r, "merged") && !flag(r, "dirty") && !flag(r, "live")).map(|r| size(r)).sum();
    out.push(format!("removable (done + merged + clean + no live session): {safe} worktrees, {}", gb(safe_bytes)));
    out.push(if all { "all worktrees by size:".into() } else { format!("top {TOP} by size:") });
    for r in sorted.iter().take(if all { usize::MAX } else { TOP }) {
        out.push(format!(
            "{:>9}  {}  status={}  merged={} dirty={} live={}  {}",
            gb(size(r)),
            r["ticketId"].as_str().unwrap_or("(no ticket)"),
            r["ticketStatus"].as_str().unwrap_or("-"),
            yn(flag(r, "merged")),
            yn(flag(r, "dirty")),
            yn(flag(r, "live")),
            r["path"].as_str().unwrap_or(""),
        ));
    }
    out
}

fn run(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let all = p.args.get(1).is_some();
    let answer = ctx.stream().request_within(&Envelope::new("worktrees", "stale"), "worktree", "the worktree report", true, ANSWER_TIMEOUT_MS)?;
    let rows: Vec<Value> = serde_json::from_str(&answer.data).map_err(|e| format!("unreadable worktree report: {e}"))?;
    for l in render(&rows, all) {
        ctx.out(l);
    }
    Ok(0)
}

pub static WORKTREES: Command = Command { name: "worktrees", needs: Needs::Stream, validate, run };

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn report_counts_and_orders_by_size() {
        let rows = vec![
            json!({"path":"a","ticketId":"ticket-1","ticketStatus":"done","merged":true,"dirty":false,"live":false,"sizeBytes":3_221_225_472u64}),
            json!({"path":"b","ticketId":null,"merged":false,"dirty":true,"live":false,"sizeBytes":1u64}),
        ];
        let t = render(&rows, false);
        assert!(t[0].starts_with("2 worktrees, 3.00 GB"), "{}", t[0]);
        assert!(t[1].contains("1 worktrees, 3.00 GB"), "{}", t[1]);
        assert!(t[3].contains("ticket-1") && t[4].contains("(no ticket)"));
    }
}
