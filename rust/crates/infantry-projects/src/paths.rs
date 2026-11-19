// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Path comparison that survives Windows: `\\?\` prefixes, mixed separators and case.
use std::path::{Path, PathBuf};

/// `p` with the platform's separators (git prints `C:/x/y` on Windows).
pub fn native(p: &Path) -> PathBuf {
    if cfg!(windows) {
        PathBuf::from(p.to_string_lossy().replace('/', "\\"))
    } else {
        p.to_path_buf()
    }
}

/// The canonical form of `p` without the verbatim prefix, or its absolute native form when it does not exist.
pub fn canonical(p: &Path) -> PathBuf {
    match std::fs::canonicalize(p) {
        Ok(c) => strip_verbatim(&c),
        Err(_) => {
            let n = native(p);
            if n.is_absolute() {
                n
            } else {
                std::env::current_dir().map(|d| d.join(&n)).unwrap_or(n)
            }
        }
    }
}

fn strip_verbatim(p: &Path) -> PathBuf {
    let s = p.to_string_lossy();
    if let Some(rest) = s.strip_prefix(r"\\?\UNC\") {
        PathBuf::from(format!(r"\\{rest}"))
    } else if let Some(rest) = s.strip_prefix(r"\\?\") {
        PathBuf::from(rest)
    } else {
        p.to_path_buf()
    }
}

/// A comparable key: canonical, no trailing separator, lowercase on Windows.
fn key(p: &Path) -> String {
    let s = canonical(p).to_string_lossy().trim_end_matches(['/', '\\']).to_string();
    if cfg!(windows) {
        s.replace('/', "\\").to_lowercase()
    } else {
        s
    }
}

pub fn same_dir(a: &Path, b: &Path) -> bool {
    key(a) == key(b)
}

/// True when `dir` is `top` or below it.
pub fn contains(top: &Path, dir: &Path) -> bool {
    let t = key(top);
    let d = key(dir);
    let sep = if cfg!(windows) { '\\' } else { '/' };
    d == t || d.starts_with(&format!("{t}{sep}"))
}

/// The length of the canonical form, to pick the deepest of several containing worktrees.
pub fn depth_key(p: &Path) -> usize {
    key(p).len()
}
