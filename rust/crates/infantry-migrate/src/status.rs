// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `status`: when the snapshot in a folder was taken, from where, and what it holds (from its manifest).
use crate::manifest;
use crate::{human_age, now_ms, sibling, snapshot};
use std::path::Path;

fn opt(v: Option<i64>) -> String {
    v.map(|v| v.to_string()).unwrap_or_else(|| "-".into())
}

pub fn run(to: &Path) -> Result<Vec<String>, String> {
    let m = manifest::read(to)?;
    let age = (now_ms() - m.taken_at).max(0) / 1000;
    let mut out = vec![
        format!("snapshot  {} ({} ago, took {} ms, {})", to.display(), human_age(age), m.took_ms, m.tool),
        format!("source    {} (shown as \"{}\")", m.source, m.source_label),
        format!(
            "holds     {} sessions, {} projects, {} agents, {} roles; {} files + {} dbs, {:.1} MB; {} runtime files skipped",
            m.counts.sessions,
            opt(m.counts.projects),
            opt(m.counts.agents),
            m.counts.roles,
            m.files.len(),
            m.databases.len(),
            m.counts.bytes as f64 / 1_048_576.0,
            m.skipped.len()
        ),
    ];
    for d in &m.databases {
        out.push(format!("db        {}: user_version {}, {} tables, read {}, integrity {}", d.path, d.user_version, d.tables.len(), d.mode, d.integrity));
    }
    out.push(match snapshot::daemon_running(to) {
        Some(p) => format!("daemon    a daemon is serving this copy on port {p}"),
        None => "daemon    none is serving this copy".into(),
    });
    let prev = sibling(to, ".prev");
    out.push(match manifest::read(&prev) {
        Ok(p) => format!("previous  {} ({} ago)", prev.display(), human_age((now_ms() - p.taken_at).max(0) / 1000)),
        Err(_) => "previous  none".into(),
    });
    Ok(out)
}
