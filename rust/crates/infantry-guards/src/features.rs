// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! What core still names of the features that leave it (DECOUPLE-1 #45, EPIC #41; docs/rust-port/DECOUPLE-PLAN.md
//! section 3).
//!
//! Core holds generic mechanisms only (AGENTS.md rule 1). [`CORE_CRATES`] are the crates that stay core once the moves
//! land (DECOUPLE-12 swaps `infantry-daemon` for `infantry-shell` here); [`FEATURE_CRATES`] and [`FEATURE_TERMS`] are
//! what they may not name. The scan reads shipped code only: `src/` and `build.rs`, without comment lines and without
//! the trailing `#[cfg(test)] mod`.
use std::path::{Path, PathBuf};

use crate::check::{crate_dir, rel_path, Crate};

/// The crates that stay core (and the API leaves, which name no feature either).
pub const CORE_CRATES: &[&str] = &[
    "infantry-protocol",
    "infantry-proc",
    "infantry-core",
    "infantry-plugin-wit",
    "infantry-plugin-host",
    "infantry-plugin-sdk",
    "infantry-runner-api",
    "infantry-runner",
    "infantry-runner-local",
    "infantry-runner-link",
    "infantry-harness",
    "infantry-vault",
    "infantry-daemon",
];

/// Crates that are features (or hold one): a core crate may not depend on them.
pub const FEATURE_CRATES: &[&str] = &[
    "infantry-board",
    "infantry-orchestrator-api",
    "infantry-mail",
    "infantry-scripting",
    "infantry-usage",
    "infantry-knowledge",
    "infantry-projects",
    "infantry-harness-claude",
    "infantry-harness-codex",
    "infantry-harness-opencode",
    "infantry-harness-pi",
];

/// `(text, the feature it names)`. `infantry_mail::notify` (the notification bus) and the project registry
/// (`infantry_projects::{store, wire, paths}`) are not here: they stay core (DECOUPLE-PLAN.md section 2).
pub const FEATURE_TERMS: &[(&str, &str)] = &[
    ("infantry_board", "the board"),
    ("infantry_orchestrator_api", "the orchestrator API"),
    ("infantry_mail::mail", "agent mail"),
    ("MailUnit", "agent mail"),
    ("infantry_scripting", "Lua scripting"),
    ("infantry_usage", "usage and pacing"),
    ("infantry_knowledge", "roles, skills, briefs"),
    ("infantry_projects::git", "the project git view"),
    ("infantry_projects::service", "the project git view"),
    ("infantry_projects::changes", "the project git view"),
    ("infantry_projects::worktree", "worktrees"),
    ("infantry_projects::ticket_branches", "ticket branches"),
    ("infantry_projects::stale", "the worktree sweep"),
    ("infantry_harness_claude", "a concrete harness"),
    ("infantry_harness_codex", "a concrete harness"),
    ("infantry_harness_opencode", "a concrete harness"),
    ("infantry_harness_pi", "a concrete harness"),
    ("\"claude\"", "a concrete harness name"),
    ("\"codex\"", "a concrete harness name"),
    ("\"opencode\"", "a concrete harness name"),
    ("Infantry_", "a plugin id"),
    ("\"pm\"", "the PM role"),
    // The orchestrator's wire names (frames, fields, settings), as string literals.
    ("\"ticket_", "a board frame or field"),
    ("\"epic_", "a board frame or field"),
    ("\"pod_", "a pod frame or field"),
    ("\"worktree", "worktrees"),
];

/// One line of core that names a feature.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Hit {
    /// From the rust workspace dir, with `/`.
    pub file: String,
    pub line: usize,
    pub term: &'static str,
}

/// What core names today, per file: `(file, the terms it still names, the moves that clear it)` (DECOUPLE-PLAN.md
/// section 3). The ratchet [`check_ratchet`] holds core to it: a new file or a new term fails, and so does an entry
/// that no longer applies (the move that clears it deletes or trims the entry). Shrinks to zero. A move that splits a
/// core file may carry its entry to the new file, with the same terms or fewer, and says so in its commit; no move
/// adds a term.
pub const BASELINE: &[(&str, &[&str], &str)] = &[
    ("crates/infantry-protocol/src/frames.rs", &["\"epic_", "\"pod_", "\"ticket_", "\"worktree"], "DECOUPLE-10: the orchestrator's frame names leave the wire crate"),
    (
        "crates/infantry-daemon/src/compose/adapters.rs",
        &["\"pm\"", "infantry_board", "infantry_knowledge", "infantry_mail::mail", "infantry_orchestrator_api", "infantry_projects::ticket_branches", "infantry_projects::worktree", "infantry_scripting", "infantry_usage"],
        "DECOUPLE-7, -9, -10, -11",
    ),
    ("crates/infantry-daemon/src/compose/agent_frames.rs", &["infantry_board", "infantry_mail::mail"], "DECOUPLE-9, -10"),
    ("crates/infantry-daemon/src/compose/agent_registry.rs", &["infantry_knowledge"], "DECOUPLE-9"),
    (
        "crates/infantry-daemon/src/compose/agent_tools.rs",
        &["\"pm\"", "infantry_board", "infantry_mail::mail", "infantry_orchestrator_api", "infantry_scripting", "infantry_usage"],
        "DECOUPLE-4, -7, -9, -10, -11",
    ),
    ("crates/infantry-daemon/src/compose/harness_install.rs", &["\"claude\"", "\"codex\"", "\"opencode\""], "DECOUPLE-6"),
    (
        "crates/infantry-daemon/src/compose/mod.rs",
        &["MailUnit", "infantry_board", "infantry_knowledge", "infantry_mail::mail", "infantry_projects::git", "infantry_projects::service", "infantry_scripting", "infantry_usage"],
        "DECOUPLE-3, -12",
    ),
    ("crates/infantry-daemon/src/compose/scope.rs", &["\"pm\"", "\"ticket_"], "DECOUPLE-7"),
    ("crates/infantry-daemon/src/compose/timeline.rs", &["infantry_scripting"], "DECOUPLE-11"),
    ("crates/infantry-daemon/src/compose/tools.rs", &["infantry_knowledge", "infantry_scripting"], "DECOUPLE-4"),
    ("crates/infantry-daemon/src/compose/watch.rs", &["infantry_mail::mail", "infantry_orchestrator_api"], "DECOUPLE-9, -10"),
    ("crates/infantry-daemon/src/compose/worktree_sweep.rs", &["\"worktree", "infantry_board", "infantry_orchestrator_api", "infantry_projects::stale"], "DECOUPLE-10"),
    ("crates/infantry-daemon/src/health.rs", &["\"worktree"], "DECOUPLE-7"),
    ("crates/infantry-daemon/src/pods.rs", &["\"pod_"], "DECOUPLE-10"),
    // The Run tab (AUDIT-1a A2) stays in the distribution crate at DECOUPLE-12, out of core; it becomes a plugin later.
    ("crates/infantry-daemon/src/run/mod.rs", &["\"worktree"], "DECOUPLE-12"),
    ("crates/infantry-daemon/src/run/prep.rs", &["\"worktree", "infantry_projects::git", "infantry_projects::service"], "DECOUPLE-12"),
    ("crates/infantry-daemon/src/run/tools.rs", &["infantry_scripting"], "DECOUPLE-4"),
    ("crates/infantry-daemon/src/wire.rs", &["\"ticket_", "\"worktree"], "DECOUPLE-3, -10"),
];

/// Today's `core crate -> feature crate` edges, `(from, to, the moves that remove it)`. Shrinks to zero.
pub const EDGE_BASELINE: &[(&str, &str, &str)] = &[
    ("infantry-daemon", "infantry-board", "DECOUPLE-10, -12"),
    ("infantry-daemon", "infantry-knowledge", "DECOUPLE-4, -9, -10, -11"),
    ("infantry-daemon", "infantry-mail", "DECOUPLE-8 (notify into core), -9"),
    ("infantry-daemon", "infantry-orchestrator-api", "DECOUPLE-10"),
    ("infantry-daemon", "infantry-projects", "DECOUPLE-10 (the registry splits off), -12"),
    ("infantry-daemon", "infantry-scripting", "DECOUPLE-4, -11"),
    ("infantry-daemon", "infantry-usage", "DECOUPLE-9, -10, -11"),
];

/// `(core crate, feature crate)` edges (normal and build dependencies).
pub fn feature_edges(crates: &[Crate]) -> Vec<(String, String)> {
    let mut out = Vec::new();
    for c in crates.iter().filter(|c| CORE_CRATES.contains(&c.name.as_str())) {
        for d in c.deps.iter().filter(|d| FEATURE_CRATES.contains(&d.as_str())) {
            out.push((c.name.clone(), d.clone()));
        }
    }
    out
}

/// What core names beyond the baselines, and the baseline entries that no longer apply (empty = holds).
pub fn check_ratchet(edges: &[(String, String)], hits: &[Hit], baseline: &[(&str, &[&str], &str)], edge_baseline: &[(&str, &str, &str)]) -> Vec<String> {
    let mut out = Vec::new();
    for (from, to) in edges {
        if !edge_baseline.iter().any(|(f, t, _)| f == from && t == to) {
            out.push(format!("new edge: core crate `{from}` depends on feature crate `{to}`"));
        }
    }
    for (f, t, why) in edge_baseline {
        if !edges.iter().any(|(from, to)| from == f && to == t) {
            out.push(format!("stale edge entry `{f}` -> `{t}` ({why}): the edge is gone, delete it from EDGE_BASELINE"));
        }
    }
    for h in hits {
        let allowed = baseline.iter().any(|(file, terms, _)| *file == h.file && terms.contains(&h.term));
        if !allowed {
            out.push(format!("{}:{}: core names {} (`{}`)", h.file, h.line, feature_of(h.term), h.term));
        }
    }
    for (file, terms, why) in baseline {
        for t in terms.iter() {
            if !hits.iter().any(|h| h.file == *file && h.term == *t) {
                out.push(format!("stale entry: {file} no longer names `{t}` ({why}): trim it from BASELINE"));
            }
        }
    }
    out
}

fn feature_of(term: &str) -> &'static str {
    FEATURE_TERMS.iter().find(|(t, _)| *t == term).map(|(_, f)| *f).unwrap_or("a feature")
}

/// Every line of a core crate's shipped code that names a feature.
pub fn scan(rust_dir: &Path, crates: &[Crate]) -> Vec<Hit> {
    let mut out = Vec::new();
    for c in crates.iter().filter(|c| CORE_CRATES.contains(&c.name.as_str())) {
        let dir = crate_dir(c);
        let mut files = Vec::new();
        collect(&dir.join("src"), &mut files);
        if dir.join("build.rs").exists() {
            files.push(dir.join("build.rs"));
        }
        files.sort();
        for f in files {
            let text = std::fs::read_to_string(&f).unwrap_or_default();
            out.extend(scan_text(&rel_path(rust_dir, &f), &text));
        }
    }
    out
}

/// The hits in one file's text: code lines only, up to a trailing `#[cfg(test)] mod`.
pub fn scan_text(rel: &str, text: &str) -> Vec<Hit> {
    let lines: Vec<&str> = text.lines().collect();
    let end = (0..lines.len())
        .find(|&i| lines[i].trim() == "#[cfg(test)]" && lines[i + 1..].iter().map(|l| l.trim()).find(|l| !l.is_empty()).is_some_and(|l| l.starts_with("mod ")))
        .unwrap_or(lines.len());
    let mut out = Vec::new();
    for (i, line) in lines[..end].iter().enumerate() {
        let t = line.trim_start();
        if t.starts_with("//") {
            continue;
        }
        for (term, _) in FEATURE_TERMS {
            if line.contains(term) {
                out.push(Hit { file: rel.to_string(), line: i + 1, term });
            }
        }
    }
    out
}

/// The hits grouped per file: `file: term (line, line), term (line)`, files in order.
pub fn report(hits: &[Hit]) -> Vec<String> {
    let mut files: Vec<&str> = hits.iter().map(|h| h.file.as_str()).collect();
    files.dedup();
    files
        .into_iter()
        .map(|f| {
            let mut terms: Vec<&str> = hits.iter().filter(|h| h.file == f).map(|h| h.term).collect();
            terms.sort();
            terms.dedup();
            let parts: Vec<String> = terms
                .iter()
                .map(|t| {
                    let ls: Vec<String> = hits.iter().filter(|h| h.file == f && h.term == *t).map(|h| h.line.to_string()).collect();
                    format!("{t} ({})", ls.join(", "))
                })
                .collect();
            format!("{f}: {}", parts.join("; "))
        })
        .collect()
}

fn collect(dir: &Path, out: &mut Vec<PathBuf>) {
    let Ok(rd) = std::fs::read_dir(dir) else { return };
    for e in rd.flatten() {
        let p = e.path();
        if p.is_dir() {
            collect(&p, out);
        } else if p.extension().is_some_and(|x| x == "rs") {
            out.push(p);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn code_lines_count_and_comments_and_the_test_module_do_not() {
        let src = "use infantry_board::TicketBoard;\n// infantry_board in a comment\nfn f() { let p = \"pm\"; }\n#[cfg(test)]\nmod tests {\n    use infantry_usage::X;\n}\n";
        let hits = scan_text("crates/x/src/lib.rs", src);
        assert_eq!(hits, vec![
            Hit { file: "crates/x/src/lib.rs".into(), line: 1, term: "infantry_board" },
            Hit { file: "crates/x/src/lib.rs".into(), line: 3, term: "\"pm\"" },
        ]);
    }

    #[test]
    fn notify_and_the_project_registry_are_not_features() {
        assert!(scan_text("a.rs", "use infantry_mail::notify::Hub;\nuse infantry_projects::store::Projects;\n").is_empty());
    }

    #[test]
    fn the_ratchet_holds_core_to_its_baseline() {
        let hit = |file: &str, term| Hit { file: file.into(), line: 1, term };
        let base: &[(&str, &[&str], &str)] = &[("a.rs", &["infantry_board"], "a move")];
        let edges = vec![("infantry-daemon".to_string(), "infantry-board".to_string())];
        let edge_base: &[(&str, &str, &str)] = &[("infantry-daemon", "infantry-board", "a move")];
        assert!(check_ratchet(&edges, &[hit("a.rs", "infantry_board")], base, edge_base).is_empty());
        // A new term in a known file, a new file, a new edge: each fails.
        let v = check_ratchet(&edges, &[hit("a.rs", "infantry_board"), hit("a.rs", "\"pm\""), hit("b.rs", "infantry_usage")], base, edge_base);
        assert_eq!(v.len(), 2, "{v:?}");
        let more = vec![edges[0].clone(), ("infantry-core".to_string(), "infantry-usage".to_string())];
        assert!(check_ratchet(&more, &[hit("a.rs", "infantry_board")], base, edge_base).iter().any(|l| l.starts_with("new edge")));
        // A cleared file and a removed edge leave stale entries, which fail too.
        let v = check_ratchet(&[], &[], base, edge_base);
        assert!(v.len() == 2 && v.iter().all(|l| l.starts_with("stale")), "{v:?}");
    }

    #[test]
    fn report_groups_per_file() {
        let h = |line, term| Hit { file: "a.rs".into(), line, term };
        assert_eq!(report(&[h(1, "infantry_board"), h(4, "infantry_board"), h(2, "\"pm\"")]), vec!["a.rs: \"pm\" (2); infantry_board (1, 4)".to_string()]);
    }
}
