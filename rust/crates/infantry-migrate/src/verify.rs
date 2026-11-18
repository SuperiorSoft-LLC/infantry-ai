// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `verify`: is the copy whole, can the Rust side read all of it, and how far has each side moved since the snapshot.
//!
//! It FAILS when the copy is damaged or unreadable: a db whose `integrity_check` is not `ok` or a table that cannot be
//! read, a file in the manifest missing from the copy, a session record the Rust daemon cannot load, or a protected
//! access list that differs from the source's. Differences that are expected are only reported: the source moved on
//! (Kotlin keeps working; run `snapshot` again for a fresh copy), or the copy changed (the Rust daemon uses it).
//!
//! It prints counts only: never a file's content, never a hash.

use crate::manifest::{self, Manifest};
use crate::{acl, sha256_hex, snapshot, sqlite};
use infantry_protocol::session::SessionRecord;
use std::collections::BTreeSet;
use std::path::{Path, PathBuf};

#[derive(Debug, Default)]
pub struct Report {
    pub lines: Vec<String>,
    pub failures: Vec<String>,
}

impl Report {
    pub fn ok(&self) -> bool {
        self.failures.is_empty()
    }
    fn line(&mut self, s: String) {
        self.lines.push(s);
    }
    fn fail(&mut self, s: String) {
        self.failures.push(s);
    }
}

/// The session ids in a store, and how many of its records the Rust `SessionRecord` refuses.
fn sessions(file: &Path) -> (BTreeSet<String>, usize) {
    let Ok(bytes) = std::fs::read(file) else { return (BTreeSet::new(), 0) };
    let Ok(v) = serde_json::from_slice::<serde_json::Value>(&bytes) else { return (BTreeSet::new(), usize::MAX) };
    let list = v.get("sessions").and_then(|s| s.as_array()).cloned().unwrap_or_default();
    let mut ids = BTreeSet::new();
    let mut bad = 0;
    for r in list {
        match serde_json::from_value::<SessionRecord>(r) {
            Ok(rec) => {
                ids.insert(rec.id);
            }
            Err(_) => bad += 1,
        }
    }
    (ids, bad)
}

fn short_list(names: &[String]) -> String {
    const SHOWN: usize = 4;
    let mut s = names.iter().take(SHOWN).cloned().collect::<Vec<_>>().join(", ");
    if names.len() > SHOWN {
        s.push_str(&format!(", +{} more", names.len() - SHOWN));
    }
    s
}

fn scratch_dir() -> PathBuf {
    std::env::temp_dir().join(format!("infantry-migrate-verify-{}-{}", std::process::id(), crate::now_ms()))
}

pub fn run(to: &Path, from: Option<&Path>) -> Result<Report, String> {
    let m: Manifest = manifest::read(to)?;
    let from = from.map(Path::to_path_buf).unwrap_or_else(|| PathBuf::from(&m.source));
    let mut r = Report::default();
    let age_s = (crate::now_ms() - m.taken_at).max(0) / 1000;
    r.line(format!("snapshot  {} from {} ({} ago, took {} ms)", to.display(), from.display(), crate::human_age(age_s), m.took_ms));

    // Files: the copy against the manifest, the source against the manifest.
    let (mut intact, mut copy_changed, mut src_same, mut src_changed, mut src_gone) = (0, vec![], 0, vec![], vec![]);
    let (mut acl_checked, mut acl_equal) = (0, 0);
    for f in &m.files {
        let rel = Path::new(&f.path);
        let copy = to.join(rel);
        match std::fs::read(&copy) {
            Ok(b) if sha256_hex(&b) == f.sha256 => intact += 1,
            Ok(_) => copy_changed.push(f.path.clone()),
            Err(_) => r.fail(format!("missing from the copy: {}", f.path)),
        }
        match std::fs::read(from.join(rel)) {
            Ok(b) if sha256_hex(&b) == f.sha256 => src_same += 1,
            Ok(_) => src_changed.push(f.path.clone()),
            Err(_) => src_gone.push(f.path.clone()),
        }
        if f.acl {
            acl_checked += 1;
            match (acl::access(&from.join(rel)), acl::access(&copy)) {
                (Ok(a), Ok(b)) if a == b => acl_equal += 1,
                (Err(_), _) => {} // gone from the source: nothing to compare
                _ => r.fail(format!("access list differs from the source: {}", f.path)),
            }
        }
    }
    for d in m.databases.iter().filter(|d| d.acl) {
        acl_checked += 1;
        match (acl::access(&from.join(&d.path)), acl::access(&to.join(&d.path))) {
            (Ok(a), Ok(b)) if a == b => acl_equal += 1,
            _ => r.fail(format!("access list differs from the source: {}", d.path)),
        }
    }
    r.line(format!(
        "copy      {} files + {} dbs, {} intact{}",
        m.files.len(),
        m.databases.len(),
        intact,
        if copy_changed.is_empty() { String::new() } else { format!(", {} changed by the Rust side ({})", copy_changed.len(), short_list(&copy_changed)) }
    ));
    r.line(format!("secrets   {acl_checked} files with protected access lists, {acl_equal} equal to the source (contents never shown)"));

    // Sessions: every record must load as the Rust daemon's SessionRecord.
    let (copy_ids, copy_bad) = sessions(&to.join("sessions.json"));
    let (src_ids, _) = sessions(&from.join("sessions.json"));
    if copy_bad > 0 {
        r.fail(format!("{copy_bad} session records in the copy do not load in the Rust daemon"));
    }
    let added = src_ids.difference(&copy_ids).count();
    let removed = copy_ids.difference(&src_ids).count();
    r.line(format!(
        "sessions  {} in the copy (snapshot {}), all load: {}; source now {}{}",
        copy_ids.len(),
        m.counts.sessions,
        if copy_bad == 0 { "yes" } else { "NO" },
        src_ids.len(),
        if added + removed > 0 { format!(" ({added} new, {removed} gone since)") } else { String::new() }
    ));

    // Databases: integrity and every table readable in the copy; per-table drift against the source now.
    let scratch = scratch_dir();
    for (i, d) in m.databases.iter().enumerate() {
        let copy = sqlite::open_copy(&to.join(&d.path)).and_then(|c| sqlite::facts(&c, true));
        let copy = match copy {
            Ok(f) => f,
            Err(e) => {
                r.fail(format!("{}: {e}", d.path));
                continue;
            }
        };
        if copy.integrity != "ok" {
            r.fail(format!("{}: integrity_check says {}", d.path, copy.integrity));
        }
        let unreadable: Vec<String> = copy.tables.iter().filter(|(_, n)| n.is_none()).map(|(t, _)| t.clone()).collect();
        if !unreadable.is_empty() {
            r.fail(format!("{}: tables the Rust side cannot read: {}", d.path, short_list(&unreadable)));
        }
        let src = sqlite::SourceDb::open(&from.join(&d.path), &scratch.join(i.to_string())).and_then(|s| sqlite::facts(&s.conn, false));
        let (mut equal, mut moved) = (0, vec![]);
        if let Ok(s) = &src {
            for (t, n) in &copy.tables {
                match (n, s.tables.get(t).copied().flatten()) {
                    (Some(a), Some(b)) if *a == b => equal += 1,
                    (Some(a), Some(b)) => moved.push(format!("{t} {:+}", b - a)),
                    (_, None) => moved.push(format!("{t} gone")),
                    _ => {}
                }
            }
            for t in s.tables.keys().filter(|t| !copy.tables.contains_key(*t)) {
                moved.push(format!("{t} new"));
            }
        }
        let rows: i64 = copy.tables.values().flatten().sum();
        r.line(format!(
            "db        {}: user_version {}, {} tables, {} rows, integrity {}; vs source now: {}",
            d.path,
            copy.user_version,
            copy.tables.len(),
            rows,
            copy.integrity,
            match &src {
                Err(e) => format!("not read ({e})"),
                Ok(_) if moved.is_empty() => format!("all {equal} tables equal"),
                Ok(_) => format!("{equal} equal, {} moved on ({})", moved.len(), short_list(&moved)),
            }
        ));
        if d.path == "infantry.db" {
            let n = |t: &str| copy.tables.get(t).copied().flatten().map(|v| v.to_string()).unwrap_or_else(|| "-".into());
            let s = |t: &str| src.as_ref().ok().and_then(|f| f.tables.get(t).copied().flatten()).map(|v| v.to_string()).unwrap_or_else(|| "-".into());
            r.line(format!("projects  {} in the copy (snapshot {}); source now {}", n("projects"), m.counts.projects.map(|v| v.to_string()).unwrap_or("-".into()), s("projects")));
            r.line(format!("agents    {} in the copy (snapshot {}); source now {}", n("agents"), m.counts.agents.map(|v| v.to_string()).unwrap_or("-".into()), s("agents")));
        }
    }
    let _ = std::fs::remove_dir_all(&scratch);
    r.line(format!("roles     {} in the copy (snapshot {}); source now {}", snapshot::role_count(to), m.counts.roles, snapshot::role_count(&from)));
    r.line(format!(
        "source    {src_same} files unchanged since the snapshot, {} changed{}, {} gone",
        src_changed.len(),
        if src_changed.is_empty() { String::new() } else { format!(" ({})", short_list(&src_changed)) },
        src_gone.len()
    ));
    r.line(format!("skipped   {} runtime files (locks, port, temp files, SQLite sidecars)", m.skipped.len()));
    Ok(r)
}
