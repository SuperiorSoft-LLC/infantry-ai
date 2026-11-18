// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Test support: build a FRESH infantry.db exactly as the Kotlin daemon's `InfantryDatabase.Schema.create`
//! does, from the SQLDelight sources (`*.sq` schema statements; `user_version` = newest `.sqm` + 1).
//!
//! The board itself never runs this: it writes no migrations and works on whatever schema it finds.
use std::fs;
use std::path::{Path, PathBuf};

use rusqlite::Connection;

/// The SQLDelight folder in this repository.
pub fn kotlin_sq_dir() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../superiorSoftLibrary/infantryCore/src/main/sqldelight/com/superiorsoftllc/infantry/db")
}

/// The schema version Kotlin stamps on a fresh database: the newest `N.sqm` migrates N -> N+1.
pub fn schema_version(sq_dir: &Path) -> std::io::Result<i64> {
    let mut max = 0;
    for e in fs::read_dir(sq_dir)? {
        let name = e?.file_name().to_string_lossy().to_string();
        if let Some(n) = name.strip_suffix(".sqm").and_then(|n| n.parse::<i64>().ok()) {
            max = max.max(n);
        }
    }
    Ok(max + 1)
}

/// The schema statements (CREATE TABLE / INDEX / TRIGGER / VIEW) of one `.sq` file, labelled queries dropped.
pub fn schema_statements(sq: &str) -> Vec<String> {
    // Strip `--` comments (no .sq string literal holds `--`).
    let text: String = sq
        .lines()
        .map(|l| match l.find("--") {
            Some(i) => &l[..i],
            None => l,
        })
        .collect::<Vec<_>>()
        .join("\n");
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut in_trigger = false;
    for ch in text.chars() {
        cur.push(ch);
        if ch != ';' {
            continue;
        }
        let head = strip_label(&cur);
        let upper = head.to_uppercase();
        if upper.starts_with("CREATE TRIGGER") || in_trigger {
            in_trigger = true;
            if !upper.trim_end_matches(';').trim_end().ends_with("END") {
                continue;
            }
            in_trigger = false;
        }
        if upper.starts_with("CREATE ") {
            out.push(strip_type_adapters(head));
        }
        cur.clear();
    }
    out
}

/// Drops SQLDelight's column type adapters (`INTEGER AS kotlin.Int`): not SQL, absent from the real file.
fn strip_type_adapters(stmt: &str) -> String {
    let mut out = stmt.to_string();
    for ty in ["INTEGER", "TEXT", "REAL", "BLOB"] {
        let pat = format!("{ty} AS ");
        while let Some(i) = out.find(&pat) {
            let start = i + ty.len();
            let after = start + " AS ".len();
            let end = out[after..].find(|c: char| c.is_whitespace() || c == ',' || c == ')').map_or(out.len(), |j| after + j);
            out.replace_range(start..end, "");
        }
    }
    out
}

/// A statement without the SQLDelight `label:` line in front of it.
fn strip_label(stmt: &str) -> &str {
    let t = stmt.trim();
    if let Some((first, rest)) = t.split_once('\n') {
        let f = first.trim();
        if f.ends_with(':') && f[..f.len() - 1].chars().all(|c| c.is_ascii_alphanumeric() || c == '_') {
            return rest.trim();
        }
    }
    t
}

/// Creates the full Kotlin schema in `conn` (tables first, then indexes, triggers and views) and stamps
/// `user_version`.
pub fn create_kotlin_schema(conn: &Connection, sq_dir: &Path) -> Result<i64, String> {
    let mut files: Vec<PathBuf> = fs::read_dir(sq_dir)
        .map_err(|e| format!("{}: {e}", sq_dir.display()))?
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| p.extension().is_some_and(|x| x == "sq"))
        .collect();
    files.sort();
    let mut tables = Vec::new();
    let mut rest = Vec::new();
    for f in &files {
        let text = fs::read_to_string(f).map_err(|e| format!("{}: {e}", f.display()))?;
        for s in schema_statements(&text) {
            if s.to_uppercase().starts_with("CREATE TABLE") {
                tables.push(s);
            } else {
                rest.push(s);
            }
        }
    }
    for s in tables.iter().chain(rest.iter()) {
        conn.execute_batch(s).map_err(|e| format!("{e}: {s}"))?;
    }
    let version = schema_version(sq_dir).map_err(|e| e.to_string())?;
    conn.execute_batch(&format!("PRAGMA user_version = {version};")).map_err(|e| e.to_string())?;
    Ok(version)
}

/// A fresh temp directory holding `infantry.db` with the Kotlin schema; deleted on drop.
pub struct TempDb {
    pub dir: PathBuf,
    pub path: PathBuf,
}

impl TempDb {
    pub fn new(tag: &str) -> TempDb {
        let dir = std::env::temp_dir().join(format!(
            "infantry-board-{tag}-{}-{}",
            std::process::id(),
            uuid::Uuid::new_v4().simple()
        ));
        fs::create_dir_all(&dir).expect("temp dir");
        let path = dir.join("infantry.db");
        let conn = Connection::open(&path).expect("open temp db");
        create_kotlin_schema(&conn, &kotlin_sq_dir()).expect("kotlin schema");
        TempDb { dir, path }
    }
}

impl Drop for TempDb {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.dir);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn labels_and_queries_are_dropped_triggers_kept() {
        let sq = "-- c;\nCREATE TABLE a (x TEXT); -- tail\n\nq:\nSELECT * FROM a;\n\
                  CREATE TRIGGER t BEFORE DELETE ON a BEGIN SELECT RAISE(ABORT, 'no'); END;\nins:\nINSERT INTO a VALUES (?);";
        let s = schema_statements(sq);
        assert_eq!(s.len(), 2, "{s:?}");
        assert!(s[1].starts_with("CREATE TRIGGER") && s[1].ends_with("END;"));
    }

    #[test]
    fn the_kotlin_schema_builds() {
        let db = TempDb::new("schema");
        let c = Connection::open(&db.path).unwrap();
        let v: i64 = c.query_row("PRAGMA user_version", [], |r| r.get(0)).unwrap();
        assert_eq!(v, schema_version(&kotlin_sq_dir()).unwrap());
        let n: i64 = c
            .query_row("SELECT count(*) FROM sqlite_master WHERE name IN ('tickets','ticket_links','ticket_runs','ticket_events')", [], |r| r.get(0))
            .unwrap();
        assert_eq!(n, 4);
    }
}
