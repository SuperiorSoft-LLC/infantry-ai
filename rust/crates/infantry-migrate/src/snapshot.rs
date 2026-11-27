// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `snapshot`: a fresh copy of a state dir, built beside the destination and swapped in at the end.
//!
//! 1. The whole tree is copied into `<to>.snapshot` (a crash leaves only that folder, removed on the next run).
//! 2. SQLite files go through the backup API (`sqlite`), every other file is read whole and written once; a `.json`
//!    file is read until it parses (the Kotlin stores replace their file atomically, so this only waits out a
//!    rename in progress). Files of the running daemon (locks, its port, half-written `.tmp` files, SQLite sidecars)
//!    are left out and listed in the manifest.
//! 3. A file with a protected access list keeps it (`acl`). Nothing is printed from any file.
//! 4. `migration.json` is written, then `<to>` becomes `<to>.prev` (the previous snapshot) and the new tree becomes
//!    `<to>`. A destination that a daemon is serving, or that is not a snapshot, is refused.
//!
//! Nothing is ever created, changed, locked or renamed in the source.

use crate::manifest::{self, Counts, DbEntry, FileEntry, Manifest, Skipped};
use crate::{acl, now_ms, rel_string, sha256_hex, sibling, sqlite};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

/// Files of a running daemon: they describe a process, not state, and the Rust daemon writes its own.
pub const RUNTIME_FILES: [&str; 4] = ["daemon.lock", "daemon.pid", "daemon.port", "state.lock"];
/// How long a read may retry a file another process holds (a Windows sharing violation).
pub const READ_BUDGET_MS: u64 = 3_000;
/// How often a `.json` file is read before it is copied as it is.
pub const JSON_ATTEMPTS: usize = 10;

pub struct Options {
    pub from: PathBuf,
    pub to: PathBuf,
    pub label: String,
    /// Replace a destination that is not a snapshot (it is kept as `<to>.prev`).
    pub force: bool,
}

/// The daemon port written in `dir`, when something answers on it.
pub fn daemon_running(dir: &Path) -> Option<u16> {
    let port: u16 = std::fs::read_to_string(dir.join("daemon.port")).ok()?.trim().parse().ok()?;
    let addr = std::net::SocketAddr::from(([127, 0, 0, 1], port));
    std::net::TcpStream::connect_timeout(&addr, Duration::from_millis(300)).ok().map(|_| port)
}

fn is_snapshot(dir: &Path) -> bool {
    dir.join(manifest::FILE).is_file()
}

fn empty_dir(dir: &Path) -> bool {
    std::fs::read_dir(dir).map(|mut d| d.next().is_none()).unwrap_or(false)
}

/// Why `rel` is left out, or None when it is copied.
fn skip_reason(path: &Path, name: &str, is_dir: bool) -> Option<&'static str> {
    if is_dir {
        return name.ends_with(".db.holders").then_some("lock holders of the running daemon");
    }
    if RUNTIME_FILES.contains(&name) {
        return Some("lock or port of the running daemon");
    }
    if name.ends_with(".tmp") {
        return Some("half-written temp file");
    }
    for s in sqlite::SIDECARS {
        if let Some(base) = name.strip_suffix(s) {
            if path.with_file_name(base).is_file() {
                return Some("SQLite sidecar (its db is copied with the backup API)");
            }
        }
    }
    None
}

fn sharing_violation(e: &std::io::Error) -> bool {
    // ERROR_SHARING_VIOLATION, ERROR_LOCK_VIOLATION.
    matches!(e.raw_os_error(), Some(32) | Some(33))
}

fn read_retry(p: &Path) -> std::io::Result<Vec<u8>> {
    let start = Instant::now();
    loop {
        match std::fs::read(p) {
            Err(e) if sharing_violation(&e) && start.elapsed() < Duration::from_millis(READ_BUDGET_MS) => std::thread::sleep(Duration::from_millis(25)),
            r => return r,
        }
    }
}

/// The file's bytes; a `.json` file is read until it parses. The flag says it never did.
fn read_file(p: &Path) -> std::io::Result<(Vec<u8>, bool)> {
    let json = p.extension().is_some_and(|e| e.eq_ignore_ascii_case("json"));
    let mut bytes = read_retry(p)?;
    if !json {
        return Ok((bytes, false));
    }
    for _ in 1..JSON_ATTEMPTS {
        if serde_json::from_slice::<serde::de::IgnoredAny>(&bytes).is_ok() {
            return Ok((bytes, false));
        }
        std::thread::sleep(Duration::from_millis(50));
        bytes = read_retry(p)?;
    }
    let ok = serde_json::from_slice::<serde::de::IgnoredAny>(&bytes).is_ok();
    Ok((bytes, !ok))
}

struct Walk<'a> {
    from: &'a Path,
    tmp: &'a Path,
    scratch: PathBuf,
    files: Vec<FileEntry>,
    dbs: Vec<DbEntry>,
    skipped: Vec<Skipped>,
    bytes: u64,
    log: &'a mut dyn FnMut(&str),
}

impl Walk<'_> {
    fn dir(&mut self, src: &Path, dst: &Path) -> Result<(), String> {
        std::fs::create_dir_all(dst).map_err(|e| format!("{}: {e}", dst.display()))?;
        acl::copy_protected(src, dst)?;
        let mut entries: Vec<_> = std::fs::read_dir(src).map_err(|e| format!("{}: {e}", src.display()))?.filter_map(Result::ok).collect();
        entries.sort_by_key(|e| e.file_name());
        for e in entries {
            let path = e.path();
            let name = e.file_name().to_string_lossy().into_owned();
            let rel = rel_string(self.from, &path);
            let meta = match std::fs::symlink_metadata(&path) {
                Ok(m) => m,
                // Gone between the listing and now (a temp file of the running daemon).
                Err(_) => {
                    self.skipped.push(Skipped { path: rel, reason: "vanished during the copy".into() });
                    continue;
                }
            };
            if meta.file_type().is_symlink() {
                self.skipped.push(Skipped { path: rel, reason: "link (not followed)".into() });
                continue;
            }
            if let Some(why) = skip_reason(&path, &name, meta.is_dir()) {
                self.skipped.push(Skipped { path: rel, reason: why.into() });
                continue;
            }
            let target = dst.join(&name);
            if meta.is_dir() {
                self.dir(&path, &target)?;
            } else if sqlite::is_sqlite(&path) {
                self.db(&path, &target, rel)?;
            } else {
                self.file(&path, &target, rel)?;
            }
        }
        Ok(())
    }

    fn file(&mut self, src: &Path, dst: &Path, rel: String) -> Result<(), String> {
        let (bytes, json_invalid) = match read_file(src) {
            Ok(b) => b,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
                self.skipped.push(Skipped { path: rel, reason: "vanished during the copy".into() });
                return Ok(());
            }
            Err(e) => return Err(format!("{}: {e}", src.display())),
        };
        std::fs::write(dst, &bytes).map_err(|e| format!("{}: {e}", dst.display()))?;
        let acl = acl::copy_protected(src, dst)?;
        self.bytes += bytes.len() as u64;
        self.files.push(FileEntry { path: rel, bytes: bytes.len() as u64, sha256: sha256_hex(&bytes), acl, json_invalid });
        Ok(())
    }

    fn db(&mut self, src: &Path, dst: &Path, rel: String) -> Result<(), String> {
        (self.log)(&format!("db {rel} (backup API)"));
        let scratch = self.scratch.join(format!("{}", self.dbs.len()));
        let source = sqlite::SourceDb::open(src, &scratch)?;
        source.backup_to(dst)?;
        let mode = source.mode.to_string();
        drop(source);
        let copy = sqlite::open_copy(dst)?;
        let f = sqlite::facts(&copy, true)?;
        copy.close().map_err(|(_, e)| e.to_string())?;
        let acl = acl::copy_protected(src, dst)?;
        self.bytes += std::fs::metadata(dst).map(|m| m.len()).unwrap_or(0);
        self.dbs.push(DbEntry { path: rel, mode, user_version: f.user_version, integrity: f.integrity, tables: f.tables, acl });
        Ok(())
    }
}

/// The number of session records in a sessions store file (0 when there is none).
pub fn session_count(file: &Path) -> Result<usize, String> {
    match std::fs::read(file) {
        Ok(b) => {
            let v: serde_json::Value = serde_json::from_slice(&b).map_err(|e| format!("{}: {e}", file.display()))?;
            Ok(v.get("sessions").and_then(|s| s.as_array()).map(|a| a.len()).unwrap_or(0))
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(0),
        Err(e) => Err(format!("{}: {e}", file.display())),
    }
}

/// Role files (`roles/*.md`).
pub fn role_count(dir: &Path) -> usize {
    std::fs::read_dir(dir.join("roles"))
        .map(|d| d.filter_map(Result::ok).filter(|e| e.path().extension().is_some_and(|x| x == "md")).count())
        .unwrap_or(0)
}

/// Resolves and checks the two folders: the source exists, they are not the same and neither holds the other.
pub fn resolve(from: &Path, to: &Path) -> Result<(PathBuf, PathBuf), String> {
    let from = std::fs::canonicalize(from).map_err(|e| format!("source {}: {e}", from.display()))?;
    let from = crate::plain(&from);
    let to = std::path::absolute(to).map_err(|e| format!("destination {}: {e}", to.display()))?;
    // A destination that does not exist yet is resolved through its deepest existing folder, so an alias in its path
    // (an 8.3 short name such as RUNNER~1, a junction, a symlink) cannot hide that it lies inside the source.
    let to = {
        let mut base = to.clone();
        let mut rest = Vec::new();
        loop {
            if let Ok(c) = std::fs::canonicalize(&base) {
                break rest.iter().rev().fold(crate::plain(&c), |p: PathBuf, n| p.join(n));
            }
            match (base.file_name().map(|n| n.to_os_string()), base.parent().map(Path::to_path_buf)) {
                (Some(n), Some(parent)) => {
                    rest.push(n);
                    base = parent;
                }
                _ => break to.clone(),
            }
        }
    };
    let lower = |p: &Path| p.to_string_lossy().to_lowercase().replace('\\', "/").trim_end_matches('/').to_string();
    let (f, t) = (lower(&from), lower(&to));
    if f == t || t.starts_with(&format!("{f}/")) || f.starts_with(&format!("{t}/")) {
        return Err(format!("the destination {} must be outside the source {} (and not hold it)", to.display(), from.display()));
    }
    Ok((from, to))
}

pub fn run(o: &Options, log: &mut dyn FnMut(&str)) -> Result<Manifest, String> {
    let started = Instant::now();
    let (from, to) = resolve(&o.from, &o.to)?;
    if !from.is_dir() {
        return Err(format!("source {} is not a folder", from.display()));
    }
    let prev = sibling(&to, ".prev");
    let tmp = sibling(&to, ".snapshot");
    if to.exists() {
        if let Some(p) = daemon_running(&to) {
            return Err(format!("a daemon is serving {} on port {p}; stop it first", to.display()));
        }
        if !is_snapshot(&to) && !empty_dir(&to) && !o.force {
            return Err(format!("{} exists and is not a snapshot (no {}); pass --force to keep it as {}", to.display(), manifest::FILE, prev.display()));
        }
    }
    if prev.exists() && !is_snapshot(&prev) {
        return Err(format!("{} exists and is not a snapshot; move it away first", prev.display()));
    }
    if tmp.exists() {
        std::fs::remove_dir_all(&tmp).map_err(|e| format!("an old {} could not be removed: {e}", tmp.display()))?;
    }
    log(&format!("copying {} -> {}", from.display(), tmp.display()));
    let mut w = Walk { from: &from, tmp: &tmp, scratch: tmp.join(".scratch"), files: vec![], dbs: vec![], skipped: vec![], bytes: 0, log };
    let walked = w.dir(&from, &tmp);
    let _ = std::fs::remove_dir_all(&w.scratch);
    if let Err(e) = walked {
        let _ = std::fs::remove_dir_all(&tmp);
        return Err(e);
    }
    let table = |db: &str, t: &str| w.dbs.iter().find(|d| d.path == db).and_then(|d| d.tables.get(t).copied().flatten());
    let counts = Counts {
        sessions: session_count(&w.tmp.join("sessions.json"))?,
        projects: table("infantry.db", "projects"),
        agents: table("infantry.db", "agents"),
        roles: role_count(w.tmp),
        files: w.files.len() + w.dbs.len(),
        bytes: w.bytes,
    };
    let m = Manifest {
        version: manifest::VERSION,
        tool: format!("infantry-migrate {}", env!("CARGO_PKG_VERSION")),
        source: from.display().to_string(),
        source_label: o.label.clone(),
        taken_at: now_ms(),
        took_ms: started.elapsed().as_millis() as i64,
        counts,
        files: std::mem::take(&mut w.files),
        databases: std::mem::take(&mut w.dbs),
        skipped: std::mem::take(&mut w.skipped),
    };
    let log = w.log;
    if let Err(e) = manifest::write(&tmp, &m) {
        let _ = std::fs::remove_dir_all(&tmp);
        return Err(e);
    }
    // Swap in: the old snapshot becomes .prev, the new one takes its name.
    let mut moved_prev = false;
    if to.exists() {
        if prev.exists() {
            std::fs::remove_dir_all(&prev).map_err(|e| format!("the old {} could not be removed: {e}", prev.display()))?;
        }
        std::fs::rename(&to, &prev).map_err(|e| format!("{} could not become {} ({e}); is something using it?", to.display(), prev.display()))?;
        moved_prev = true;
    }
    if let Err(e) = std::fs::rename(&tmp, &to) {
        if moved_prev {
            let _ = std::fs::rename(&prev, &to);
        }
        return Err(format!("{} could not become {}: {e}", tmp.display(), to.display()));
    }
    if moved_prev {
        log(&format!("previous snapshot kept as {}", prev.display()));
    }
    Ok(m)
}
