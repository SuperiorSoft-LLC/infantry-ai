// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The user's real PATH. A process started by an installer or updater inherits THAT process's environment, not the user's login
//! PATH, so a CLI the user installed (`claude`) is "not found". On Windows the machine and user `Path` live in the registry; this
//! reads them and adds the missing directories to the END of the process PATH (what the process already has stays first).
//! `refresh` runs at start and before each spawn (throttled), so a relaunch or a freshly installed tool is found with no restart.

use std::ffi::{OsStr, OsString};
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{Duration, Instant};

/// How long a registry read is reused (a read starts `reg.exe`).
const REFRESH_EVERY: Duration = Duration::from_secs(20);
static LAST: Mutex<Option<Instant>> = Mutex::new(None);

fn same(a: &std::path::Path, b: &std::path::Path) -> bool {
    if cfg!(windows) {
        let (a, b) = (a.to_string_lossy(), b.to_string_lossy());
        a.trim_end_matches(['\\', '/']).eq_ignore_ascii_case(b.trim_end_matches(['\\', '/']))
    } else {
        a == b
    }
}

/// [current] followed by every directory of [extra] it does not already hold.
pub fn merge(current: &OsStr, extra: &[PathBuf]) -> OsString {
    let mut dirs: Vec<PathBuf> = std::env::split_paths(current).filter(|d| !d.as_os_str().is_empty()).collect();
    for d in extra {
        if !d.as_os_str().is_empty() && !dirs.iter().any(|x| same(x, d)) {
            dirs.push(d.clone());
        }
    }
    std::env::join_paths(dirs).unwrap_or_else(|_| current.to_os_string())
}

/// `%NAME%` references replaced from the process environment (an unknown name stays as written).
pub fn expand(s: &str) -> String {
    let mut out = String::new();
    let mut rest = s;
    while let Some(i) = rest.find('%') {
        out.push_str(&rest[..i]);
        let tail = &rest[i + 1..];
        match tail.find('%') {
            Some(j) => {
                match std::env::var(&tail[..j]) {
                    Ok(v) if j > 0 => out.push_str(&v),
                    _ => out.push_str(&rest[i..i + j + 2]),
                }
                rest = &tail[j + 1..];
            }
            None => {
                out.push_str(&rest[i..]);
                rest = "";
            }
        }
    }
    out.push_str(rest);
    out
}

/// The value of a `reg query <key> /v Path` answer.
pub fn parse_reg_path(text: &str) -> Vec<PathBuf> {
    text.lines()
        .find_map(|l| {
            let l = l.trim();
            let rest = l.strip_prefix("Path").or_else(|| l.strip_prefix("PATH"))?;
            let rest = rest.trim_start();
            let rest = rest.strip_prefix("REG_EXPAND_SZ").or_else(|| rest.strip_prefix("REG_SZ"))?;
            Some(rest.trim().to_string())
        })
        .map(|v| v.split(';').map(str::trim).filter(|d| !d.is_empty()).map(|d| PathBuf::from(expand(d))).collect())
        .unwrap_or_default()
}

/// The machine and user `Path` directories from the registry (Windows); empty elsewhere.
pub fn registry_dirs() -> Vec<PathBuf> {
    if !cfg!(windows) {
        return Vec::new();
    }
    let mut all = Vec::new();
    for key in [r"HKLM\SYSTEM\CurrentControlSet\Control\Session Manager\Environment", r"HKCU\Environment"] {
        if let Ok(out) = crate::command("reg").args(["query", key, "/v", "Path"]).stdin(std::process::Stdio::null()).output() {
            all.extend(parse_reg_path(&String::from_utf8_lossy(&out.stdout)));
        }
    }
    all
}

/// Adds the user's real PATH to this process's PATH (throttled; [force] ignores the throttle). Returns whether PATH changed.
pub fn refresh_with(dirs: impl FnOnce() -> Vec<PathBuf>, force: bool) -> bool {
    {
        let mut last = LAST.lock().unwrap_or_else(|e| e.into_inner());
        if !force && last.is_some_and(|t| t.elapsed() < REFRESH_EVERY) {
            return false;
        }
        *last = Some(Instant::now());
    }
    let cur = std::env::var_os("PATH").unwrap_or_default();
    let merged = merge(&cur, &dirs());
    if merged == cur {
        return false;
    }
    std::env::set_var("PATH", merged);
    true
}

/// [refresh_with] from the registry.
pub fn refresh() -> bool {
    refresh_with(registry_dirs, false)
}

/// [path] (a PATH value a caller set explicitly) plus the user's registry directories it lacks.
pub fn extend(path: &str) -> String {
    merge(OsStr::new(path), &registry_dirs()).to_string_lossy().into_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn merge_appends_only_missing_dirs_and_keeps_order() {
        let cur = std::env::join_paths(["/a", "/b"]).unwrap();
        let out = merge(&cur, &[PathBuf::from("/b"), PathBuf::from("/c")]);
        let dirs: Vec<_> = std::env::split_paths(&out).collect();
        assert_eq!(dirs, vec![PathBuf::from("/a"), PathBuf::from("/b"), PathBuf::from("/c")]);
    }

    #[test]
    fn a_stripped_path_gets_the_users_dirs_back() {
        let before = std::env::var_os("PATH");
        std::env::set_var("PATH", "");
        assert!(refresh_with(|| vec![PathBuf::from("/user/bin")], true));
        assert!(std::env::split_paths(&std::env::var_os("PATH").unwrap()).any(|d| d == PathBuf::from("/user/bin")));
        match before {
            Some(v) => std::env::set_var("PATH", v),
            None => std::env::remove_var("PATH"),
        }
    }

    #[test]
    fn reads_a_reg_query_answer_and_expands_variables() {
        std::env::set_var("INFANTRY_TEST_HOME", r"C:\Users\me");
        let text = "\r\nHKEY_CURRENT_USER\\Environment\r\n    Path    REG_EXPAND_SZ    %INFANTRY_TEST_HOME%\\.local\\bin;C:\\Tools;\r\n";
        assert_eq!(parse_reg_path(text), vec![PathBuf::from(r"C:\Users\me\.local\bin"), PathBuf::from(r"C:\Tools")]);
        assert_eq!(expand(r"%NOPE_X%\a"), r"%NOPE_X%\a");
    }

    #[cfg(windows)]
    #[test]
    fn the_real_registry_holds_the_system_dirs() {
        let dirs = registry_dirs();
        assert!(dirs.iter().any(|d| d.to_string_lossy().to_ascii_lowercase().ends_with("system32")), "{dirs:?}");
    }
}
