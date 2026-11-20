// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Which tool calls change a file, and what they changed (the Kotlin WRITE transcript line).
//!
//! One classifier for every harness: Claude `Edit`/`Write`/`MultiEdit`/`NotebookEdit`, OpenCode `edit`/`write`/`patch`,
//! Pi `edit`/`write`, Codex `apply_patch`. The result is the wire `FileWrite`:
//! `{"path", "content"?, "edits"?: [{"oldText","newText"}], "patch"?, "truncated"?}`.

use serde_json::{json, Map, Value};

fn text<'a>(v: &'a Value, keys: &[&str]) -> Option<&'a str> {
    keys.iter().find_map(|k| v.get(*k).and_then(Value::as_str))
}

/// Bytes kept of one text of a file write in the feed (`limits.chat.writeMaxBytes`); more is cut and flagged `truncated`.
pub const WRITE_MAX_BYTES: usize = 200_000;

/// Cuts [s] to at most [max] bytes on a char boundary; true when it cut.
fn clip(s: &str, max: usize) -> (String, bool) {
    if s.len() <= max {
        return (s.to_string(), false);
    }
    let mut end = max;
    while !s.is_char_boundary(end) {
        end -= 1;
    }
    (s[..end].to_string(), true)
}

/// The file writes of one tool call: none when the tool does not change a file. [max_bytes] caps each text.
pub fn file_writes(tool: &str, input: &str, max_bytes: usize) -> Vec<Value> {
    let name = tool.to_ascii_lowercase();
    let v: Value = serde_json::from_str(input).unwrap_or(Value::Null);
    match name.as_str() {
        "write" | "create" | "write_file" => {
            let (Some(path), Some(content)) = (text(&v, &["file_path", "filePath", "path"]), text(&v, &["content"])) else { return vec![] };
            let (content, cut) = clip(content, max_bytes);
            vec![write_json(path, ("content", json!(content)), cut)]
        }
        "edit" | "str_replace" | "str_replace_editor" => {
            let Some(path) = text(&v, &["file_path", "filePath", "path"]) else { return vec![] };
            let mut pairs: Vec<(String, String)> = Vec::new();
            if let (Some(o), Some(n)) = (text(&v, &["old_string", "oldString", "oldText", "old_str"]), text(&v, &["new_string", "newString", "newText", "new_str"])) {
                pairs.push((o.into(), n.into()));
            }
            if let Some(es) = v.get("edits").and_then(Value::as_array) {
                for e in es {
                    if let (Some(o), Some(n)) = (text(e, &["old_string", "oldString", "oldText"]), text(e, &["new_string", "newString", "newText"])) {
                        pairs.push((o.into(), n.into()));
                    }
                }
            }
            edits_write(path, pairs, max_bytes)
        }
        "multiedit" => {
            let Some(path) = text(&v, &["file_path", "filePath", "path"]) else { return vec![] };
            let pairs = v
                .get("edits")
                .and_then(Value::as_array)
                .map(|es| es.iter().filter_map(|e| Some((text(e, &["old_string", "oldString"])?.to_string(), text(e, &["new_string", "newString"])?.to_string()))).collect())
                .unwrap_or_default();
            edits_write(path, pairs, max_bytes)
        }
        "notebookedit" => {
            let (Some(path), Some(src)) = (text(&v, &["notebook_path"]), text(&v, &["new_source"])) else { return vec![] };
            let (src, cut) = clip(src, max_bytes);
            vec![write_json(path, ("content", json!(src)), cut)]
        }
        "apply_patch" | "patch" => {
            let body = text(&v, &["patchText", "patch", "input"]).unwrap_or(input);
            patch_writes(body, max_bytes)
        }
        _ => vec![],
    }
}

fn write_json(path: &str, field: (&str, Value), truncated: bool) -> Value {
    let mut m = Map::new();
    m.insert("path".into(), json!(path));
    m.insert(field.0.into(), field.1);
    if truncated {
        m.insert("truncated".into(), json!(true));
    }
    Value::Object(m)
}

fn edits_write(path: &str, pairs: Vec<(String, String)>, max: usize) -> Vec<Value> {
    if pairs.is_empty() {
        return vec![];
    }
    let mut cut = false;
    let edits: Vec<Value> = pairs
        .iter()
        .map(|(o, n)| {
            let (o, c1) = clip(o, max);
            let (n, c2) = clip(n, max);
            cut |= c1 || c2;
            json!({"oldText": o, "newText": n})
        })
        .collect();
    vec![write_json(path, ("edits", Value::Array(edits)), cut)]
}

/// The `*** Begin Patch` format (Codex and OpenCode `apply_patch`) or a plain unified diff, one write per file.
fn patch_writes(body: &str, max: usize) -> Vec<Value> {
    let mut out: Vec<(String, String)> = Vec::new();
    for raw in body.lines() {
        let file = raw
            .strip_prefix("*** Update File:")
            .or_else(|| raw.strip_prefix("*** Add File:"))
            .or_else(|| raw.strip_prefix("*** Delete File:"))
            .or_else(|| raw.strip_prefix("+++ b/"));
        if let Some(p) = file {
            out.push((p.trim().to_string(), String::new()));
            continue;
        }
        let Some((_, patch)) = out.last_mut() else { continue };
        if raw.starts_with("*** ") || raw.starts_with("--- ") || raw.starts_with("diff ") || raw.starts_with("index ") {
            continue;
        }
        // "@@ context" in the Begin Patch format carries no line numbers; a unified hunk header does.
        if raw.starts_with("@@") || raw.starts_with('+') || raw.starts_with('-') || raw.starts_with(' ') {
            patch.push_str(raw);
            patch.push('\n');
        }
    }
    out.into_iter()
        .filter(|(_, p)| !p.is_empty())
        .map(|(path, p)| {
            let (p, cut) = clip(&p, max);
            write_json(&path, ("patch", json!(p)), cut)
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn claude_edit_write_multiedit_and_notebook() {
        let e = file_writes("Edit", r#"{"file_path":"a.rs","old_string":"x","new_string":"y"}"#, 1000);
        assert_eq!(e, vec![json!({"path":"a.rs","edits":[{"oldText":"x","newText":"y"}]})]);
        let w = file_writes("Write", r#"{"file_path":"b.txt","content":"hi"}"#, 1000);
        assert_eq!(w, vec![json!({"path":"b.txt","content":"hi"})]);
        let m = file_writes("MultiEdit", r#"{"file_path":"c","edits":[{"old_string":"1","new_string":"2"},{"old_string":"3","new_string":"4"}]}"#, 1000);
        assert_eq!(m[0]["edits"].as_array().unwrap().len(), 2);
        let n = file_writes("NotebookEdit", r#"{"notebook_path":"n.ipynb","new_source":"print(1)"}"#, 1000);
        assert_eq!(n[0]["path"], "n.ipynb");
    }

    #[test]
    fn other_harness_spellings() {
        assert_eq!(file_writes("edit", r#"{"filePath":"a","oldString":"x","newString":"y"}"#, 100)[0]["edits"][0]["newText"], "y");
        assert_eq!(file_writes("write", r#"{"path":"a","content":"z"}"#, 100)[0]["content"], "z");
        assert_eq!(file_writes("edit", r#"{"path":"a","edits":[{"oldText":"x","newText":"y"}]}"#, 100)[0]["edits"][0]["oldText"], "x");
    }

    #[test]
    fn a_non_writing_tool_is_none() {
        assert!(file_writes("Read", r#"{"file_path":"a"}"#, 100).is_empty());
        assert!(file_writes("Bash", r#"{"command":"ls"}"#, 100).is_empty());
        assert!(file_writes("Edit", "not json", 100).is_empty());
    }

    #[test]
    fn a_long_text_is_cut_and_flagged() {
        let w = file_writes("Write", &json!({"file_path":"a","content":"é".repeat(100)}).to_string(), 11);
        assert_eq!(w[0]["truncated"], true);
        assert!(w[0]["content"].as_str().unwrap().len() <= 11);
    }

    #[test]
    fn apply_patch_gives_one_write_per_file() {
        let p = "*** Begin Patch\n*** Update File: src/a.rs\n@@\n ctx\n-old\n+new\n*** Add File: b.txt\n+hello\n*** End Patch";
        let w = file_writes("apply_patch", p, 1000);
        assert_eq!(w.len(), 2);
        assert_eq!(w[0]["path"], "src/a.rs");
        assert!(w[0]["patch"].as_str().unwrap().contains("-old\n+new"));
        assert_eq!(w[1]["patch"], "+hello\n");
    }
}
