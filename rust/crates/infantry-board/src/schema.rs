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

pub use infantry_core::db::schema_statements;

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
