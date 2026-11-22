// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The on-disk copy of a session's frame history (CHANGES-PERSIST-1): one JSON line per frame, `{"s":<sequence>,"f":<encoded
//! frame>}`, in `<state dir>/session-frames/<session id>.jsonl`. The daemon appends each frame it emits and reads the tail
//! back at startup, so a restart still replays the chat feed (file-change boxes included). Bounded by the named setting
//! `Config::replay_kept`: the file is rewritten from the kept frames once it holds twice that many.
use std::collections::VecDeque;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};

fn dir(state_dir: &Path) -> PathBuf {
    state_dir.join("session-frames")
}

fn file(state_dir: &Path, sid: &str) -> PathBuf {
    // A session id is a uuid or a short word; never let one name a path outside the folder.
    let safe: String = sid.chars().map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' { c } else { '_' }).collect();
    dir(state_dir).join(format!("{safe}.jsonl"))
}

fn line(seq: i64, frame: &str) -> String {
    let mut s = serde_json::json!({"s": seq, "f": frame}).to_string();
    s.push('\n');
    s
}

/// The last `keep` frames of a session, oldest first. A missing or damaged file gives what could be read.
pub fn load(state_dir: &Path, sid: &str, keep: usize) -> VecDeque<(i64, String)> {
    let Ok(text) = fs::read_to_string(file(state_dir, sid)) else { return VecDeque::new() };
    let mut out: VecDeque<(i64, String)> = text
        .lines()
        .filter_map(|l| serde_json::from_str::<serde_json::Value>(l).ok())
        .filter_map(|v| Some((v.get("s")?.as_i64()?, v.get("f")?.as_str()?.to_string())))
        .collect();
    while out.len() > keep.max(1) {
        out.pop_front();
    }
    out
}

/// Appends one frame. `lines` is how many lines the file holds (the caller tracks it); past twice `keep` the file is
/// rewritten from `history` (which already holds this frame). Returns the new line count. Failures are silent: a log that
/// cannot be written must never stop a session.
pub fn append(state_dir: &Path, sid: &str, seq: i64, frame: &str, history: &VecDeque<(i64, String)>, lines: usize, keep: usize) -> usize {
    let path = file(state_dir, sid);
    if lines + 1 > keep.max(1) * 2 {
        let body: String = history.iter().map(|(s, f)| line(*s, f)).collect();
        let tmp = path.with_extension("tmp");
        if fs::create_dir_all(dir(state_dir)).is_ok() && fs::write(&tmp, body).is_ok() && fs::rename(&tmp, &path).is_ok() {
            return history.len();
        }
        return lines;
    }
    let _ = fs::create_dir_all(dir(state_dir));
    let Ok(mut f) = OpenOptions::new().create(true).append(true).open(&path) else { return lines };
    if f.write_all(line(seq, frame).as_bytes()).is_ok() {
        lines + 1
    } else {
        lines
    }
}

/// Drops a session's log (the session was deleted).
pub fn remove(state_dir: &Path, sid: &str) {
    let _ = fs::remove_file(file(state_dir, sid));
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("frame-log-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        d
    }

    #[test]
    fn frames_survive_a_reload_in_order() {
        let d = tmp("reload");
        let mut h = VecDeque::new();
        let mut n = 0;
        for i in 1..=4 {
            let f = format!(r#"{{"type":"status","sequence":{i}}}"#);
            h.push_back((i, f.clone()));
            n = append(&d, "s1", i, &f, &h, n, 100);
        }
        let back = load(&d, "s1", 100);
        assert_eq!(back.iter().map(|x| x.0).collect::<Vec<_>>(), vec![1, 2, 3, 4]);
        assert_eq!(back[2].1, r#"{"type":"status","sequence":3}"#);
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn the_file_is_bounded_by_the_kept_count() {
        let d = tmp("bound");
        let keep = 3;
        let mut h = VecDeque::new();
        let mut n = 0;
        for i in 1..=20 {
            h.push_back((i, format!("f{i}")));
            while h.len() > keep {
                h.pop_front();
            }
            n = append(&d, "s1", i, &format!("f{i}"), &h, n, keep);
        }
        assert!(n <= keep * 2, "{n}");
        let back = load(&d, "s1", keep);
        assert_eq!(back.iter().map(|x| x.0).collect::<Vec<_>>(), vec![18, 19, 20]);
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn a_damaged_line_is_skipped_and_remove_deletes() {
        let d = tmp("bad");
        fs::create_dir_all(dir(&d)).unwrap();
        fs::write(file(&d, "s1"), "not json\n{\"s\":1,\"f\":\"a\"}\n{\"s\":\n").unwrap();
        assert_eq!(load(&d, "s1", 10).len(), 1);
        remove(&d, "s1");
        assert!(load(&d, "s1", 10).is_empty());
        assert!(load(&d, "../x", 10).is_empty());
        let _ = fs::remove_dir_all(&d);
    }
}
