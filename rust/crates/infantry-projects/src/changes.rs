// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `git status --porcelain=v1 -z` and `git diff --name-status/--numstat -z` into neutral change lists.
//!
//! The mapping, stated once because getting it wrong is silent:
//! - `??` is UNTRACKED (unstaged); `!!` (ignored) is skipped.
//! - `DD AU UD UA DU AA UU` are unmerged: ONE CONFLICTED entry, not staged.
//! - Otherwise each non-blank side is its own entry: X -> staged, Y -> unstaged (`MM` is two entries).
//! - A->ADDED, M/T->MODIFIED, D->DELETED, R->RENAMED, C->ADDED (with `fromPath`); an unknown letter is MODIFIED.
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// What happened to a path. The wire spells it in capitals (`"MODIFIED"`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ChangeKind {
    Added,
    Modified,
    Deleted,
    Renamed,
    Untracked,
    Conflicted,
}

/// One changed path. `staged` says which side of the index `status` describes.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChangedFile {
    pub path: String,
    pub status: ChangeKind,
    pub staged: bool,
    #[serde(default)]
    pub from_path: Option<String>,
}

const CONFLICT_CODES: &[&str] = &["DD", "AU", "UD", "UA", "DU", "AA", "UU"];

fn kind_of(letter: char) -> ChangeKind {
    match letter {
        'A' | 'C' => ChangeKind::Added,
        'D' => ChangeKind::Deleted,
        'R' => ChangeKind::Renamed,
        _ => ChangeKind::Modified,
    }
}

/// Parses `git status --porcelain=v1 -z`.
pub fn parse_porcelain(z: &str) -> Vec<ChangedFile> {
    let fields: Vec<&str> = z.split('\0').collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < fields.len() {
        let entry = fields[i];
        i += 1;
        let b = entry.as_bytes();
        if b.len() < 4 || b[2] != b' ' {
            continue;
        }
        let code = &entry[..2];
        let path = entry[3..].to_string();
        let (x, y) = (b[0] as char, b[1] as char);
        let from = if matches!(x, 'R' | 'C') || matches!(y, 'R' | 'C') {
            let f = fields.get(i).map(|s| s.to_string());
            i += 1;
            f
        } else {
            None
        };
        if code == "??" {
            out.push(ChangedFile { path, status: ChangeKind::Untracked, staged: false, from_path: None });
        } else if code == "!!" {
        } else if CONFLICT_CODES.contains(&code) {
            out.push(ChangedFile { path, status: ChangeKind::Conflicted, staged: false, from_path: None });
        } else {
            if x != ' ' {
                let fp = if matches!(x, 'R' | 'C') { from.clone() } else { None };
                out.push(ChangedFile { path: path.clone(), status: kind_of(x), staged: true, from_path: fp });
            }
            if y != ' ' {
                let fp = if matches!(y, 'R' | 'C') { from.clone() } else { None };
                out.push(ChangedFile { path, status: kind_of(y), staged: false, from_path: fp });
            }
        }
    }
    out
}

/// One path a branch changed since the merge-base. `added`/`removed` are line counts; 0 with `binary`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BranchChangedFile {
    pub path: String,
    pub status: ChangeKind,
    #[serde(default)]
    pub from_path: Option<String>,
    #[serde(default)]
    pub added: i64,
    #[serde(default)]
    pub removed: i64,
    #[serde(default)]
    pub binary: bool,
}

/// `--name-status -z`: `X\0path\0` or, for a rename/copy, `R100\0from\0to\0`.
pub fn parse_name_status(out: &str) -> Vec<BranchChangedFile> {
    let t: Vec<&str> = out.split('\0').collect();
    let mut files = Vec::new();
    let mut i = 0;
    while i < t.len() {
        let code = t[i].trim_start_matches(['\n', '\r']);
        if code.is_empty() {
            i += 1;
            continue;
        }
        let c = code.chars().next().unwrap();
        let mk = |path: &str, status, from: Option<&str>| BranchChangedFile {
            path: path.to_string(),
            status,
            from_path: from.map(str::to_string),
            added: 0,
            removed: 0,
            binary: false,
        };
        if c == 'R' || c == 'C' {
            let (Some(from), Some(to)) = (t.get(i + 1), t.get(i + 2)) else { break };
            files.push(mk(to, if c == 'R' { ChangeKind::Renamed } else { ChangeKind::Added }, Some(from)));
            i += 3;
        } else {
            let Some(path) = t.get(i + 1) else { break };
            let kind = match c {
                'A' => ChangeKind::Added,
                'D' => ChangeKind::Deleted,
                'U' => ChangeKind::Conflicted,
                _ => ChangeKind::Modified,
            };
            files.push(mk(path, kind, None));
            i += 2;
        }
    }
    files
}

/// `--numstat -z`: `a\tr\tpath\0` or for a rename `a\tr\t\0from\0to\0`. A binary file (`-\t-`) maps to `None`.
pub fn parse_numstat(out: &str) -> HashMap<String, Option<(i64, i64)>> {
    let t: Vec<&str> = out.split('\0').collect();
    let mut map = HashMap::new();
    let mut i = 0;
    while i < t.len() {
        let head = t[i].trim_start_matches(['\n', '\r']);
        if head.is_empty() {
            i += 1;
            continue;
        }
        let parts: Vec<&str> = head.splitn(3, '\t').collect();
        if parts.len() < 3 {
            i += 1;
            continue;
        }
        let counts = match (parts[0].parse::<i64>(), parts[1].parse::<i64>()) {
            (Ok(a), Ok(r)) => Some((a, r)),
            _ => None,
        };
        if parts[2].is_empty() {
            let Some(to) = t.get(i + 2) else { break };
            map.insert(to.to_string(), counts);
            i += 3;
        } else {
            map.insert(parts[2].to_string(), counts);
            i += 1;
        }
    }
    map
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn porcelain_maps_both_sides_renames_and_conflicts() {
        let z = "MM a.txt\0R  new.rs\0old.rs\0?? n.md\0UU c.txt\0!! ign\0\n";
        let c = parse_porcelain(z);
        assert_eq!(c.len(), 5);
        assert_eq!((c[0].staged, c[0].status), (true, ChangeKind::Modified));
        assert_eq!((c[1].staged, c[1].status), (false, ChangeKind::Modified));
        assert_eq!(c[2].status, ChangeKind::Renamed);
        assert_eq!(c[2].from_path.as_deref(), Some("old.rs"));
        assert_eq!(c[3].status, ChangeKind::Untracked);
        assert_eq!(c[4].status, ChangeKind::Conflicted);
        assert_eq!(serde_json::to_string(&c[3]).unwrap(), r#"{"path":"n.md","status":"UNTRACKED","staged":false,"fromPath":null}"#);
    }

    #[test]
    fn name_status_and_numstat() {
        let ns = parse_name_status("M\0a.rs\0R090\0old\0new\0D\0gone\0");
        assert_eq!(ns.len(), 3);
        assert_eq!(ns[1].from_path.as_deref(), Some("old"));
        let n = parse_numstat("3\t1\ta.rs\0-\t-\tbin.png\0" );
        assert_eq!(n["a.rs"], Some((3, 1)));
        assert_eq!(n["bin.png"], None);
        let r = parse_numstat("2\t0\t\0old\0new\0");
        assert_eq!(r["new"], Some((2, 0)));
    }
}
