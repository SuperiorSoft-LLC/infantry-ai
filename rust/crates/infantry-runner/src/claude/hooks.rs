// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Claude's hook answers over the generic [`crate::hooks::HookServer`] channel:
//! - ASK-STUCK-1 (`ClaudeQuestionHook.kt`): a `PreToolUse` hook on `AskUserQuestion` puts each question to the owner and answers the
//!   call with `permissionDecision: allow` + `updatedInput.answers`, so the CLI never draws its menu in the hidden terminal;
//! - G3 (`ClaudePermissionHook.kt`): a `PermissionRequest` hook answers a permission dialog from the Tool approval.

use crate::harness::{Answer, Question, ToolApproval};
use serde_json::{json, Map, Value};

pub const QUESTION_EVENT: &str = "PreToolUse";
pub const QUESTION_TOOL: &str = "AskUserQuestion";
pub const PERMISSION_EVENT: &str = "PermissionRequest";
/// Seconds claude waits for a hook's answer (an ask waits for the owner); past it claude shows its own dialog.
pub const DEFAULT_TIMEOUT_SEC: u64 = 600;

pub const NO_ANSWER: &str = "The owner did not answer this question in Infantry AI (it timed out or the session is ending). \
Do not ask it again right away: carry on with your best judgement and say what you assumed.";

/// The `PreToolUse` settings entry (only AskUserQuestion).
pub fn question_entry(url: &str, timeout_sec: u64) -> Value {
    json!({"matcher": QUESTION_TOOL, "hooks": [{"type": "http", "url": url, "timeout": timeout_sec}]})
}

/// The `PermissionRequest` settings entry (every tool).
pub fn permission_entry(url: &str, timeout_sec: u64) -> Value {
    json!({"hooks": [{"type": "http", "url": url, "timeout": timeout_sec}]})
}

/// Whether a Tool approval gets the permission hook (every mode that can see a dialog: all but allowAll).
pub fn permission_wanted(a: ToolApproval) -> bool {
    a != ToolApproval::AllowAll
}

/// One question of an AskUserQuestion call: (question text, header, [(label, description)], multiSelect).
#[derive(Debug, Clone, PartialEq)]
pub struct AskItem {
    pub text: String,
    pub header: String,
    pub options: Vec<(String, String)>,
    pub multi_select: bool,
}

/// The call's tool input and its questions; None when it is not an AskUserQuestion call with at least one question.
pub fn parse_question(body: &str) -> Option<(Map<String, Value>, Vec<AskItem>)> {
    let root: Value = serde_json::from_str(body).ok()?;
    if root.get("tool_name")?.as_str()? != QUESTION_TOOL {
        return None;
    }
    let input = root.get("tool_input")?.as_object()?.clone();
    let mut items = Vec::new();
    for q in input.get("questions").and_then(Value::as_array).into_iter().flatten() {
        let Some(text) = q.get("question").and_then(Value::as_str).filter(|t| !t.trim().is_empty()) else { continue };
        let mut options: Vec<(String, String)> = Vec::new();
        for o in q.get("options").and_then(Value::as_array).into_iter().flatten() {
            let Some(label) = o.get("label").and_then(Value::as_str).map(str::trim).filter(|l| !l.is_empty()) else { continue };
            if options.iter().any(|(l, _)| l == label) {
                continue;
            }
            options.push((label.into(), o.get("description").and_then(Value::as_str).unwrap_or("").trim().into()));
        }
        items.push(AskItem {
            text: text.into(),
            header: q.get("header").and_then(Value::as_str).unwrap_or("").trim().into(),
            options,
            multi_select: q.get("multiSelect").and_then(Value::as_bool).unwrap_or(false),
        });
    }
    if items.is_empty() { None } else { Some((input, items)) }
}

/// The card for one question: header and option descriptions go into the text; the one "(Recommended)" label is the default.
pub fn question_card(q: &AskItem) -> Question {
    let mut text = String::new();
    if !q.header.is_empty() {
        text.push_str(&format!("[{}] ", q.header));
    }
    text.push_str(q.text.trim());
    let described: Vec<_> = q.options.iter().filter(|(_, d)| !d.is_empty()).collect();
    if !described.is_empty() {
        text.push('\n');
        for (l, d) in described {
            text.push_str(&format!("\n- {l}: {d}"));
        }
    }
    let rec: Vec<&String> = q.options.iter().map(|(l, _)| l).filter(|l| l.to_lowercase().replace(' ', "").contains("(recommended)")).collect();
    Question {
        text,
        options: q.options.iter().map(|(l, _)| l.clone()).collect(),
        recommended: if rec.len() == 1 { Some(rec[0].clone()) } else { None },
        multi_select: q.multi_select,
    }
}

/// The answer as AskUserQuestion takes it (labels comma separated, then the owner's own text); None = not answered.
pub fn answer_text(a: &Answer) -> Option<String> {
    if !a.answered() {
        return None;
    }
    let mut parts: Vec<String> = a.picked.iter().map(|p| p.trim().to_string()).filter(|p| !p.is_empty()).collect();
    if let Some(o) = a.other.as_deref().map(str::trim).filter(|o| !o.is_empty()) {
        parts.push(o.into());
    }
    if parts.is_empty() { None } else { Some(parts.join(", ")) }
}

fn question_output(fields: Value) -> String {
    let mut o = Map::new();
    o.insert("hookEventName".into(), json!(QUESTION_EVENT));
    if let Value::Object(f) = fields {
        o.extend(f);
    }
    json!({ "hookSpecificOutput": o }).to_string()
}

pub fn question_deny(reason: &str) -> String {
    question_output(json!({"permissionDecision": "deny", "permissionDecisionReason": reason}))
}

/// The hook's answer to one AskUserQuestion call; [ask] puts one question to the owner (blocking).
pub fn decide_question(body: &str, mut ask: impl FnMut(&Question) -> Answer) -> String {
    let Some((mut input, items)) = parse_question(body) else {
        return question_deny("unreadable AskUserQuestion call; ask the owner in plain text instead");
    };
    let mut answers = Map::new();
    for q in &items {
        match answer_text(&ask(&question_card(q))) {
            Some(t) => {
                answers.insert(q.text.clone(), json!(t));
            }
            None => return question_deny(NO_ANSWER),
        }
    }
    input.insert("answers".into(), Value::Object(answers));
    question_output(json!({"permissionDecision": "allow", "updatedInput": Value::Object(input)}))
}

/// (tool, input JSON) of a PermissionRequest call.
pub fn parse_permission(body: &str) -> Option<(String, String)> {
    let root: Value = serde_json::from_str(body).ok()?;
    let tool = root.get("tool_name")?.as_str()?.to_string();
    Some((tool, root.get("tool_input").map(Value::to_string).unwrap_or_else(|| "{}".into())))
}

pub fn permission_reply(allow: bool, message: Option<&str>) -> String {
    let mut d = Map::new();
    d.insert("behavior".into(), json!(if allow { "allow" } else { "deny" }));
    if let (false, Some(m)) = (allow, message) {
        d.insert("message".into(), json!(m));
    }
    json!({"hookSpecificOutput": {"hookEventName": PERMISSION_EVENT, "decision": d}}).to_string()
}

/// The answer to one PermissionRequest call: only Ask asks the owner; Deny and AllowEdits deny what reached a dialog.
pub fn decide_permission(approval: ToolApproval, body: &str, ask: impl FnOnce(&str, &str) -> bool) -> (String, Option<String>) {
    let Some((tool, input)) = parse_permission(body) else { return (permission_reply(false, Some("unreadable permission request")), None) };
    let allow = approval == ToolApproval::Ask && ask(&tool, &input);
    if allow {
        (permission_reply(true, None), None)
    } else {
        let msg = format!("the tool approval ({approval:?}) does not allow {tool}");
        (permission_reply(false, Some(&msg)), Some(tool))
    }
}
