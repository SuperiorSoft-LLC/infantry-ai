// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The SQLite handle: WAL, busy_timeout, one writer behind a mutex and a small read pool.
//!
//! This module writes NO migrations; infantry-migrate owns the schema. [`Db::open`] only
//! refuses a database whose `user_version` is newer than this build understands.
use std::fmt;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Mutex, MutexGuard};
use std::time::Duration;

use rusqlite::{Connection, OpenFlags};

/// The newest schema version this build can read and write.
/// Kotlin stamps newest `.sqm` + 1: 62.sqm exists, so a fresh Kotlin database is version 63 (RUST-ORCH-1 checked one).
pub const MAX_USER_VERSION: i64 = 63;
/// How long a connection waits on a lock before SQLITE_BUSY.
pub const BUSY_TIMEOUT: Duration = Duration::from_secs(5);
/// Read connections kept open next to the writer.
pub const READ_POOL_SIZE: usize = 4;

#[derive(Debug)]
pub enum DbError {
    /// The file's `user_version` is newer than [`MAX_USER_VERSION`]: a newer app wrote it.
    TooNew { path: PathBuf, found: i64, max: i64 },
    Sqlite(rusqlite::Error),
}

impl fmt::Display for DbError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            DbError::TooNew { path, found, max } => write!(
                f,
                "database {} has schema version {found}, newer than this build supports ({max}); refusing to open it",
                path.display()
            ),
            DbError::Sqlite(e) => write!(f, "sqlite: {e}"),
        }
    }
}

impl std::error::Error for DbError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            DbError::Sqlite(e) => Some(e),
            DbError::TooNew { .. } => None,
        }
    }
}

impl From<rusqlite::Error> for DbError {
    fn from(e: rusqlite::Error) -> Self {
        DbError::Sqlite(e)
    }
}

pub struct Db {
    path: PathBuf,
    writer: Mutex<Connection>,
    readers: Vec<Mutex<Connection>>,
    next_reader: AtomicUsize,
}

impl Db {
    /// Opens (creating if absent) the database at `path` in WAL mode.
    pub fn open(path: impl AsRef<Path>) -> Result<Db, DbError> {
        let path = path.as_ref().to_path_buf();
        let writer = Connection::open(&path)?;
        writer.busy_timeout(BUSY_TIMEOUT)?;
        let found: i64 = writer.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        if found > MAX_USER_VERSION {
            return Err(DbError::TooNew { path, found, max: MAX_USER_VERSION });
        }
        let _mode: String = writer.query_row("PRAGMA journal_mode=WAL", [], |r| r.get(0))?;
        writer.execute_batch("PRAGMA foreign_keys=ON; PRAGMA synchronous=NORMAL;")?;

        let mut readers = Vec::with_capacity(READ_POOL_SIZE);
        for _ in 0..READ_POOL_SIZE {
            let c = Connection::open_with_flags(
                &path,
                OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_URI | OpenFlags::SQLITE_OPEN_NO_MUTEX,
            )?;
            c.busy_timeout(BUSY_TIMEOUT)?;
            readers.push(Mutex::new(c));
        }
        Ok(Db { path, writer: Mutex::new(writer), readers, next_reader: AtomicUsize::new(0) })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    /// The schema version stored in the file.
    pub fn user_version(&self) -> Result<i64, DbError> {
        self.read(|c| c.query_row("PRAGMA user_version", [], |r| r.get(0)))
    }

    /// Runs `f` on the single writer connection (serialised).
    pub fn write<T>(&self, f: impl FnOnce(&mut Connection) -> rusqlite::Result<T>) -> Result<T, DbError> {
        let mut c = lock(&self.writer);
        Ok(f(&mut c)?)
    }

    /// Runs `f` on a read-only pooled connection. Picks a free one, else waits on the next in turn.
    pub fn read<T>(&self, f: impl FnOnce(&Connection) -> rusqlite::Result<T>) -> Result<T, DbError> {
        for r in &self.readers {
            if let Ok(c) = r.try_lock() {
                return Ok(f(&c)?);
            }
        }
        let i = self.next_reader.fetch_add(1, Ordering::Relaxed) % self.readers.len();
        let c = lock(&self.readers[i]);
        Ok(f(&c)?)
    }
}

fn lock(m: &Mutex<Connection>) -> MutexGuard<'_, Connection> {
    // A panic inside a closure leaves the connection itself usable.
    m.lock().unwrap_or_else(|p| p.into_inner())
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

#[cfg(test)]
mod tests {
    #[test]
    fn labels_and_queries_are_dropped_triggers_kept() {
        let sq = "-- c;\nCREATE TABLE a (x TEXT); -- tail\n\nq:\nSELECT * FROM a;\n\
                  CREATE TRIGGER t BEFORE DELETE ON a BEGIN SELECT RAISE(ABORT, 'no'); END;\nins:\nINSERT INTO a VALUES (?);";
        let s = super::schema_statements(sq);
        assert_eq!(s.len(), 2, "{s:?}");
        assert!(s[1].starts_with("CREATE TRIGGER") && s[1].ends_with("END;"));
    }

    use super::*;

    fn temp_db(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("infantry-core-db-{tag}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir.join("infantry.db")
    }

    #[test]
    fn opens_in_wal_and_reads_writes() {
        let p = temp_db("ok");
        {
            let db = Db::open(&p).unwrap();
            let mode: String = db.read(|c| c.query_row("PRAGMA journal_mode", [], |r| r.get(0))).unwrap();
            assert_eq!(mode.to_lowercase(), "wal");
            db.write(|c| c.execute_batch("CREATE TABLE t(x); INSERT INTO t VALUES (41); PRAGMA user_version=63;")).unwrap();
            let x: i64 = db.read(|c| c.query_row("SELECT x+1 FROM t", [], |r| r.get(0))).unwrap();
            assert_eq!(x, 42);
            assert!(db.read(|c| c.execute("INSERT INTO t VALUES (1)", [])).is_err(), "readers are read-only");
            assert_eq!(db.user_version().unwrap(), 63);
        }
        Db::open(&p).expect("63 is supported");
        let _ = std::fs::remove_dir_all(p.parent().unwrap());
    }

    #[test]
    fn refuses_newer_user_version() {
        let src = temp_db("src");
        {
            let c = Connection::open(&src).unwrap();
            c.execute_batch("CREATE TABLE t(x); PRAGMA user_version=64;").unwrap();
        }
        // work on a copy made with the backup API, as with real data
        let copy = src.with_file_name("copy.db");
        {
            let from = Connection::open(&src).unwrap();
            let mut to = Connection::open(&copy).unwrap();
            rusqlite::backup::Backup::new(&from, &mut to).unwrap().run_to_completion(64, Duration::ZERO, None).unwrap();
        }
        match Db::open(&copy) {
            Err(DbError::TooNew { found, max, .. }) => assert_eq!((found, max), (64, 63)),
            Err(e) => panic!("wrong error: {e}"),
            Ok(_) => panic!("user_version 64 must be refused"),
        }
        let _ = std::fs::remove_dir_all(src.parent().unwrap());
    }
}
