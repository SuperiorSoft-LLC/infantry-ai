// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! File changes of a CLI that reports only a summary (CHANGES-CODEX-1). Codex's `file_change` item lists `<kind> <path>`
//! with no text, so the diff is read from the working folder afterwards: a new file's content, or `git diff HEAD` of the
//! path for an update. The result is the same wire `FileWrite` JSON `infantry_harness::writes` makes for Claude's tools.
use serde_json::{json, Value};
use std::path::Path;

fn clip(s: String, max: usize) -> (String, bool) {
    if s.len() <= max {
        return (s, false);
    }
    let mut end = max;
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    (s[..end].to_string(), true)
}

fn git_diff(cwd: &Path, path: &str) -> Option<String> {
    let out = infantry_proc::command("git").args(["diff", "HEAD", "--no-color", "--no-ext-diff", "--", path]).current_dir(cwd).output().ok()?;
    let t = String::from_utf8_lossy(&out.stdout).to_string();
    (out.status.success() && !t.trim().is_empty()).then_some(t)
}

/// The writes of a summary (`add a.txt` / `update b.rs` / `delete c`, one per line). Deletes and unreadable files are left out.
pub fn summary_writes(cwd: &Path, summary: &str, max: usize) -> Vec<Value> {
    let mut out = Vec::new();
    for l in summary.lines() {
        let Some((kind, path)) = l.split_once(' ') else { continue };
        let full = cwd.join(path);
        let shown = full.to_string_lossy().to_string();
        match kind {
            "add" => {
                if let Ok(c) = std::fs::read_to_string(&full) {
                    let (c, cut) = clip(c, max);
                    let mut w = json!({"path": shown, "content": c});
                    if cut {
                        w["truncated"] = json!(true);
                    }
                    out.push(w);
                }
            }
            "update" => {
                if let Some(d) = git_diff(cwd, path) {
                    let (d, cut) = clip(d, max);
                    let mut w = json!({"path": shown, "patch": d});
                    if cut {
                        w["truncated"] = json!(true);
                    }
                    out.push(w);
                }
            }
            _ => {}
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn git(d: &Path, a: &[&str]) {
        let s = infantry_proc::command("git").args(["-c", "user.name=t", "-c", "user.email=t@t"]).args(a).current_dir(d).status().unwrap();
        assert!(s.success());
    }

    #[test]
    fn an_added_and_an_updated_file_become_writes() {
        let d = std::env::temp_dir().join(format!("changes-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        git(&d, &["init", "-q"]);
        std::fs::write(d.join("a.txt"), "one\n").unwrap();
        git(&d, &["add", "."]);
        git(&d, &["commit", "-qm", "i"]);
        std::fs::write(d.join("a.txt"), "two\n").unwrap();
        std::fs::write(d.join("b.txt"), "new\n").unwrap();
        let w = summary_writes(&d, "update a.txt\nadd b.txt\ndelete c.txt", 10_000);
        assert_eq!(w.len(), 2);
        assert!(w[0]["patch"].as_str().unwrap().contains("-one"));
        assert!(w[0]["patch"].as_str().unwrap().contains("+two"));
        assert_eq!(w[1]["content"], "new\n");
        let _ = std::fs::remove_dir_all(&d);
    }
}
