// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Which tool calls set the agent's own to-do list, and what the list is (the chat's to-do box).
//!
//! One classifier for every harness: Claude `TodoWrite` `{todos:[{content, status, activeForm}]}` and OpenCode
//! `todowrite` `{todos:[{content, status, priority}]}`. These tools send the FULL list each call, so a result replaces
//! the session's list. The result is the wire `todos` array: `[{"text", "status", "activeText"?}]`, where `status` is
//! `pending`, `in_progress` or `completed`. Only these fields leave the daemon.

use serde_json::{json, Value};

/// Items kept of one list; more are dropped.
pub const TODO_MAX_ITEMS: usize = 50;
/// Chars kept of one item's text.
pub const TODO_MAX_TEXT: usize = 200;

fn status(raw: Option<&str>) -> &'static str {
    match raw.map(str::to_ascii_lowercase).as_deref() {
        Some("completed" | "complete" | "done" | "cancelled" | "canceled") => "completed",
        Some("in_progress" | "in-progress" | "inprogress" | "active" | "running") => "in_progress",
        _ => "pending",
    }
}

fn clip(s: &str) -> String {
    s.chars().take(TODO_MAX_TEXT).collect()
}

/// The to-do list a tool call sets, or None when the call is not a to-do call. [input] is the tool's input as text.
/// An empty array is a valid, empty list (the agent cleared it).
pub fn todos(name: &str, input: &str) -> Option<Value> {
    if name != "TodoWrite" && name != "todowrite" {
        return None;
    }
    let v: Value = serde_json::from_str(input).ok()?;
    // `todos` may arrive as the array encoded as a JSON string (a captured haiku quirk).
    let arr = match v.get("todos")? {
        Value::Array(a) => a.clone(),
        Value::String(s) => match serde_json::from_str(s).ok()? {
            Value::Array(a) => a,
            _ => return None,
        },
        _ => return None,
    };
    let str_of = |o: &Value, k: &str| o.get(k).and_then(Value::as_str).filter(|s| !s.trim().is_empty()).map(str::to_string);
    let items: Vec<Value> = arr
        .iter()
        .filter_map(|o| {
            let text = str_of(o, "content")?;
            let mut item = json!({"text": clip(&text), "status": status(str_of(o, "status").as_deref())});
            if let Some(a) = str_of(o, "activeForm") {
                item["activeText"] = Value::String(clip(&a));
            }
            Some(item)
        })
        .take(TODO_MAX_ITEMS)
        .collect();
    Some(Value::Array(items))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn claude_todowrite_becomes_the_wire_list() {
        // As recorded from a claude stream-json tool_use input.
        let input = r#"{"todos":[{"content":"Read the code","status":"completed","activeForm":"Reading the code"},{"content":"Write the fix","status":"in_progress","activeForm":"Writing the fix"},{"content":"Run tests","status":"pending","activeForm":"Running tests"}]}"#;
        let t = todos("TodoWrite", input).unwrap();
        assert_eq!(
            t,
            json!([
                {"text":"Read the code","status":"completed","activeText":"Reading the code"},
                {"text":"Write the fix","status":"in_progress","activeText":"Writing the fix"},
                {"text":"Run tests","status":"pending","activeText":"Running tests"}
            ])
        );
    }

    #[test]
    fn opencode_todowrite_and_string_encoded_list() {
        let t = todos("todowrite", r#"{"todos":"[{\"content\":\"a\",\"status\":\"cancelled\",\"priority\":\"high\"},{\"status\":\"pending\"}]"}"#).unwrap();
        assert_eq!(t, json!([{"text":"a","status":"completed"}]));
    }

    #[test]
    fn other_tools_and_bad_input_are_not_todo_calls() {
        assert_eq!(todos("Bash", r#"{"todos":[]}"#), None);
        assert_eq!(todos("TodoWrite", "not json"), None);
        assert_eq!(todos("TodoWrite", r#"{"todos":[]}"#), Some(json!([])));
    }
}
