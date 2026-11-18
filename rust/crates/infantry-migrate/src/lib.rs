// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-MIGRATE-1: `infantry-migrate` copies the live Kotlin state dir into a state dir for the Rust daemon, without
//! ever writing to the source. `snapshot` makes the copy (SQLite through the backup API, protected access lists
//! kept, the previous copy kept as `.prev`), `verify` checks it and `status` says when it was taken.
//! The process is in docs/rust-port/MIGRATION.md.
pub mod acl;
pub mod manifest;
pub mod snapshot;
pub mod sqlite;
pub mod status;
pub mod verify;

use sha2::{Digest, Sha256};
use std::path::{Path, PathBuf};

pub fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    Sha256::digest(bytes).iter().map(|b| format!("{b:02x}")).collect()
}

/// `p` relative to `root`, `/` separated.
pub fn rel_string(root: &Path, p: &Path) -> String {
    p.strip_prefix(root).unwrap_or(p).components().map(|c| c.as_os_str().to_string_lossy().into_owned()).collect::<Vec<_>>().join("/")
}

/// `dir` with `suffix` added to its last name (`InfantryAI-Rust` -> `InfantryAI-Rust.prev`).
pub fn sibling(dir: &Path, suffix: &str) -> PathBuf {
    let mut s = dir.as_os_str().to_owned();
    s.push(suffix);
    PathBuf::from(s)
}

/// A canonical path without the Windows verbatim prefix, so it prints and compares like the path the user typed.
pub fn plain(p: &Path) -> PathBuf {
    let s = p.to_string_lossy();
    match s.strip_prefix(r"\\?\") {
        Some(rest) if !rest.starts_with("UNC\\") => PathBuf::from(rest),
        _ => p.to_path_buf(),
    }
}

pub fn human_age(secs: i64) -> String {
    match secs {
        s if s < 60 => format!("{s} s"),
        s if s < 3_600 => format!("{} min", s / 60),
        s if s < 86_400 => format!("{} h {} min", s / 3_600, (s % 3_600) / 60),
        s => format!("{} d {} h", s / 86_400, (s % 86_400) / 3_600),
    }
}

/// The Kotlin app's state dir and the default destination, under `%LOCALAPPDATA%\SuperiorSoft` (Windows) or
/// `~/.local/share/SuperiorSoft`.
pub fn default_dirs() -> (PathBuf, PathBuf) {
    let base = std::env::var_os("LOCALAPPDATA")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local").join("share")))
        .unwrap_or_else(|| PathBuf::from("."))
        .join("SuperiorSoft");
    (base.join("InfantryAI"), base.join("InfantryAI-Rust"))
}
