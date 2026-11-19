// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! SQLite files are copied with the online backup API, never as raw bytes: a raw copy of a db that the Kotlin daemon
//! is writing can catch half a transaction, and the WAL holds commits the main file does not have yet.
//!
//! The source is opened READ-ONLY, in one of two ways, so nothing is ever written to the source folder:
//! - **live**: another process has the db open (a WAL db with its `-shm`, or a rollback-journal db with no hot
//!   journal). A read-only connection joins as one more reader: it creates no file and cannot checkpoint. The backup
//!   runs in ONE step, which holds one read transaction, so the copy is the db at one commit.
//! - **quiet**: nobody has it open (a WAL db without `-shm`, or a db with a hot journal). A read-only open could
//!   then create `-wal`/`-shm` in the source folder, so the db and its sidecars are copied as bytes into a scratch
//!   folder instead and opened there, where SQLite replays them. If the source changed while it was copied, the
//!   copy is retried (by then it is live).

use rusqlite::backup::{Backup, StepResult};
use rusqlite::{Connection, OpenFlags};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant, SystemTime};

pub const MAGIC: &[u8; 16] = b"SQLite format 3\0";
/// How long one open or backup step may wait on a busy db.
pub const BUSY_TIMEOUT_MS: u64 = 5_000;
/// How long a backup may keep retrying a busy or locked source.
pub const BACKUP_BUDGET_MS: u64 = 60_000;
/// How often a quiet copy is retried when the source changed under it.
pub const QUIET_ATTEMPTS: usize = 3;

/// True when `p` starts with the SQLite header.
pub fn is_sqlite(p: &Path) -> bool {
    use std::io::Read;
    let mut head = [0u8; 16];
    std::fs::File::open(p).and_then(|mut f| f.read_exact(&mut head)).is_ok() && &head == MAGIC
}

fn sidecar(p: &Path, suffix: &str) -> PathBuf {
    let mut s = p.as_os_str().to_owned();
    s.push(suffix);
    PathBuf::from(s)
}

/// The sidecar suffixes SQLite keeps next to a db.
pub const SIDECARS: [&str; 3] = ["-wal", "-shm", "-journal"];

fn wal_mode(p: &Path) -> bool {
    use std::io::Read;
    let mut head = [0u8; 20];
    std::fs::File::open(p).and_then(|mut f| f.read_exact(&mut head)).is_ok() && head[18] == 2
}

/// Someone has the db open, so a read-only reader writes nothing new to the folder.
fn live(p: &Path) -> bool {
    if wal_mode(p) {
        sidecar(p, "-shm").is_file()
    } else {
        !sidecar(p, "-journal").is_file()
    }
}

/// A consistent read-only view of a source db.
pub struct SourceDb {
    pub conn: Connection,
    /// `live` or `quiet` (see the module doc).
    pub mode: &'static str,
    scratch: Option<PathBuf>,
}

impl Drop for SourceDb {
    fn drop(&mut self) {
        if let Some(s) = self.scratch.take() {
            // The connection must close before its folder can go.
            let c = std::mem::replace(&mut self.conn, Connection::open_in_memory().expect("in-memory db"));
            drop(c);
            let _ = std::fs::remove_dir_all(s);
        }
    }
}

type Stamp = Vec<(bool, u64, Option<SystemTime>)>;

fn stamp(p: &Path) -> Stamp {
    std::iter::once(p.to_path_buf())
        .chain(SIDECARS.iter().map(|s| sidecar(p, s)))
        .map(|f| match std::fs::metadata(&f) {
            Ok(m) => (true, m.len(), m.modified().ok()),
            Err(_) => (false, 0, None),
        })
        .collect()
}

impl SourceDb {
    /// Opens `src` without writing to its folder; `scratch` is a fresh folder a quiet copy may use.
    pub fn open(src: &Path, scratch: &Path) -> Result<SourceDb, String> {
        for _ in 0..QUIET_ATTEMPTS {
            if live(src) {
                let flags = OpenFlags::SQLITE_OPEN_READ_ONLY | OpenFlags::SQLITE_OPEN_NO_MUTEX;
                let conn = Connection::open_with_flags(src, flags).map_err(|e| format!("{}: {e}", src.display()))?;
                conn.busy_timeout(Duration::from_millis(BUSY_TIMEOUT_MS)).map_err(|e| e.to_string())?;
                return Ok(SourceDb { conn, mode: "live", scratch: None });
            }
            let before = stamp(src);
            std::fs::create_dir_all(scratch).map_err(|e| format!("{}: {e}", scratch.display()))?;
            let name = src.file_name().ok_or("a db path has no file name")?;
            let copy = scratch.join(name);
            std::fs::copy(src, &copy).map_err(|e| format!("{}: {e}", src.display()))?;
            for s in ["-wal", "-journal"] {
                let side = sidecar(src, s);
                if side.is_file() {
                    std::fs::copy(&side, sidecar(&copy, s)).map_err(|e| format!("{}: {e}", side.display()))?;
                }
            }
            if stamp(src) != before {
                let _ = std::fs::remove_dir_all(scratch);
                continue;
            }
            let conn = Connection::open(&copy).map_err(|e| format!("{}: {e}", copy.display()))?;
            return Ok(SourceDb { conn, mode: "quiet", scratch: Some(scratch.to_path_buf()) });
        }
        Err(format!("{}: it kept changing while it was copied; try again", src.display()))
    }

    /// Writes a copy of the db to `dst` (a path with no file yet).
    pub fn backup_to(&self, dst: &Path) -> Result<(), String> {
        let mut out = Connection::open(dst).map_err(|e| format!("{}: {e}", dst.display()))?;
        {
            let b = Backup::new(&self.conn, &mut out).map_err(|e| e.to_string())?;
            let start = Instant::now();
            loop {
                // -1 = every page in one step: one read transaction, so a writer never restarts it half way.
                match b.step(-1).map_err(|e| e.to_string())? {
                    StepResult::Done => break,
                    StepResult::More => continue,
                    StepResult::Busy | StepResult::Locked if start.elapsed() < Duration::from_millis(BACKUP_BUDGET_MS) => {
                        std::thread::sleep(Duration::from_millis(50))
                    }
                    other => return Err(format!("backup did not finish ({other:?})")),
                }
            }
        }
        out.close().map_err(|(_, e)| e.to_string())
    }
}

/// What a db holds: every table's row count (None = it could not be read), `user_version` and the integrity check.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct DbFacts {
    pub user_version: i64,
    pub integrity: String,
    pub tables: BTreeMap<String, Option<i64>>,
}

/// Row counts read in ONE read transaction, so they agree with each other.
pub fn facts(conn: &Connection, integrity: bool) -> Result<DbFacts, String> {
    conn.execute_batch("BEGIN").map_err(|e| e.to_string())?;
    let r = (|| {
        let user_version: i64 = conn.query_row("PRAGMA user_version", [], |r| r.get(0)).map_err(|e| e.to_string())?;
        let names: Vec<String> = {
            let mut st = conn
                .prepare("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
                .map_err(|e| e.to_string())?;
            let rows = st.query_map([], |r| r.get(0)).map_err(|e| e.to_string())?;
            rows.filter_map(Result::ok).collect()
        };
        let mut tables = BTreeMap::new();
        for n in names {
            let q = format!("SELECT COUNT(*) FROM \"{}\"", n.replace('"', "\"\""));
            tables.insert(n, conn.query_row(&q, [], |r| r.get::<_, i64>(0)).ok());
        }
        let integrity = if integrity {
            let mut st = conn.prepare("PRAGMA integrity_check").map_err(|e| e.to_string())?;
            let lines: Vec<String> = st.query_map([], |r| r.get(0)).map_err(|e| e.to_string())?.filter_map(Result::ok).collect();
            lines.join("; ")
        } else {
            String::new()
        };
        Ok(DbFacts { user_version, integrity, tables })
    })();
    let _ = conn.execute_batch("COMMIT");
    r
}

/// Opens a db copy (one this tool wrote) for reading.
pub fn open_copy(p: &Path) -> Result<Connection, String> {
    let c = Connection::open_with_flags(p, OpenFlags::SQLITE_OPEN_READ_WRITE | OpenFlags::SQLITE_OPEN_NO_MUTEX).map_err(|e| format!("{}: {e}", p.display()))?;
    c.busy_timeout(Duration::from_millis(BUSY_TIMEOUT_MS)).map_err(|e| e.to_string())?;
    Ok(c)
}
