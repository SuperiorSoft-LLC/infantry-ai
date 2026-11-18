// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `migration.json`, written at the root of every snapshot: where it came from, when, and what it holds (each file's
//! size and SHA-256, each db's row counts). `verify` and `status` read it; the Rust daemon reads `source` and
//! `sourceLabel` from it to know which other app's sessions it must not run (the live check).

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::path::Path;

pub const FILE: &str = "migration.json";
pub const VERSION: u32 = 1;
/// The name the Rust app shows for a session the source app is running ("live in Infantry (Kotlin)").
pub const DEFAULT_SOURCE_LABEL: &str = "Infantry (Kotlin)";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Manifest {
    pub version: u32,
    pub tool: String,
    /// The source state dir, absolute.
    pub source: String,
    pub source_label: String,
    /// Epoch ms when the snapshot finished.
    pub taken_at: i64,
    pub took_ms: i64,
    pub counts: Counts,
    pub files: Vec<FileEntry>,
    pub databases: Vec<DbEntry>,
    pub skipped: Vec<Skipped>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Counts {
    pub sessions: usize,
    #[serde(default)]
    pub projects: Option<i64>,
    #[serde(default)]
    pub agents: Option<i64>,
    pub roles: usize,
    pub files: usize,
    pub bytes: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct FileEntry {
    /// Relative to the state dir, `/` separated.
    pub path: String,
    pub bytes: u64,
    pub sha256: String,
    /// Its protected access list was copied (see `acl`).
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub acl: bool,
    /// A `.json` file that never parsed in any read (a writer that does not replace atomically); copied as read.
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub json_invalid: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DbEntry {
    pub path: String,
    /// `live` or `quiet`: how the source was read (`sqlite` module doc).
    pub mode: String,
    pub user_version: i64,
    pub integrity: String,
    /// Row count per table; null = the table could not be read.
    pub tables: BTreeMap<String, Option<i64>>,
    #[serde(default, skip_serializing_if = "std::ops::Not::not")]
    pub acl: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Skipped {
    pub path: String,
    pub reason: String,
}

pub fn read(dir: &Path) -> Result<Manifest, String> {
    let p = dir.join(FILE);
    let text = std::fs::read_to_string(&p).map_err(|e| format!("{}: {e} (no snapshot here; run `infantry-migrate snapshot`)", p.display()))?;
    serde_json::from_str(&text).map_err(|e| format!("{}: {e}", p.display()))
}

pub fn write(dir: &Path, m: &Manifest) -> Result<(), String> {
    let text = serde_json::to_string_pretty(m).map_err(|e| e.to_string())?;
    std::fs::write(dir.join(FILE), text).map_err(|e| format!("{}: {e}", dir.join(FILE).display()))
}
