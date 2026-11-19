// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The sessions' [`Prompter`]: a CLI's tool approval or question becomes an owner prompt card in the mail crate's
//! [`PendingPrompts`] (`prompt_request` to every client, closed by `prompt_response` with `prompt_resolved`), and the
//! answer goes back to the CLI. The approval mode is Kotlin's `ToolApprovals` order: the `toolApproval.mode` setting
//! (session, role, project, then app scope), then `INFANTRY_TOOL_APPROVAL`, then `ask` (FIRSTRUN-PERM-1).
use std::sync::Arc;

use infantry_harness::{ApprovalMode, HarnessAnswer, HarnessQuestion};
use infantry_mail::notify::prompts::{PendingPrompts, QuestionSpec};
use infantry_protocol::prompts::PromptOption;
use serde_json::{json, Value};

use super::settings::SettingsUnit;
use crate::harness::Prompter;

pub const MODE_KEY: &str = "toolApproval.mode";
pub const MODE_ENV: [&str; 2] = ["INFANTRY_TOOL_APPROVAL", "AGENTHQ_TOOL_APPROVAL"];
pub const DEFAULT_MODE: ApprovalMode = ApprovalMode::Ask;

/// A mode's wire name (`deny`, `ask`, `allowEdits`, `allowAll`; case and `_`/`-` ignored).
pub fn parse_mode(text: &str) -> Option<ApprovalMode> {
    match text.trim().to_ascii_lowercase().replace(['_', '-', ' '], "").as_str() {
        "deny" => Some(ApprovalMode::Deny),
        "ask" => Some(ApprovalMode::Ask),
        "allowedits" => Some(ApprovalMode::AllowEdits),
        "allowall" => Some(ApprovalMode::AllowAll),
        _ => None,
    }
}

pub struct SessionPrompter {
    pub prompts: Arc<PendingPrompts>,
    pub settings: SettingsUnit,
    pub handle: tokio::runtime::Handle,
}

impl SessionPrompter {
    fn resolve(&self, session: &str, project: Option<&str>, role: Option<&str>, env: impl Fn(&str) -> Option<String>) -> ApprovalMode {
        let d = json!({"session": session, "project": project, "role": role});
        let stored = self.settings.resolve(MODE_KEY, &d);
        if let Some(m) = stored.get("value").and_then(Value::as_str).and_then(parse_mode) {
            return m;
        }
        MODE_ENV.iter().find_map(|k| env(k).and_then(|v| parse_mode(&v))).unwrap_or(DEFAULT_MODE)
    }
}

/// A CLI question as a card.
pub fn spec_of(q: &HarnessQuestion) -> QuestionSpec {
    QuestionSpec {
        question: q.text.clone(),
        options: q.options.iter().map(|(label, description)| PromptOption { label: label.clone(), description: description.clone() }).collect(),
        multi_select: q.multi_select,
        allow_other: q.allow_other,
        timeout_ms: None,
        recommended: q.recommended.clone(),
    }
}

impl Prompter for SessionPrompter {
    fn mode(&self, session: &str, project: Option<&str>, role: Option<&str>) -> ApprovalMode {
        self.resolve(session, project, role, |k| std::env::var(k).ok())
    }

    fn approve(&self, session: &str, tool: &str, input: &str, done: Box<dyn FnOnce(bool) + Send>) {
        let (p, s, t, i) = (self.prompts.clone(), session.to_string(), tool.to_string(), input.to_string());
        self.handle.spawn(async move {
            let out = p.request_approval(&s, &t, &i, None).await;
            done(out.decision.allows());
        });
    }

    fn ask(&self, session: &str, questions: Vec<HarnessQuestion>, done: Box<dyn FnOnce(Vec<HarnessAnswer>) + Send>) {
        let (p, s) = (self.prompts.clone(), session.to_string());
        self.handle.spawn(async move {
            let mut answers = Vec::with_capacity(questions.len());
            for q in &questions {
                let a = p.ask_question(&s, &spec_of(q)).await;
                let answered = a.answered;
                answers.push(HarnessAnswer { answered, answers: a.answers, other_text: a.other_text });
                if !answered {
                    break;
                }
            }
            done(answers);
        });
    }

    fn end(&self, session: &str) {
        self.prompts.end_session(session);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn modes_parse_from_their_wire_names() {
        assert_eq!(parse_mode("allowAll"), Some(ApprovalMode::AllowAll));
        assert_eq!(parse_mode("allow_edits"), Some(ApprovalMode::AllowEdits));
        assert_eq!(parse_mode(" ASK "), Some(ApprovalMode::Ask));
        assert_eq!(parse_mode("deny"), Some(ApprovalMode::Deny));
        assert_eq!(parse_mode("yolo"), None);
    }

    #[test]
    fn a_cli_question_keeps_its_labels_and_recommendation() {
        let q = HarnessQuestion { text: "Which?".into(), options: vec![("A".into(), "first".into()), ("B".into(), String::new())], multi_select: true, allow_other: true, recommended: Some("A".into()) };
        let s = spec_of(&q);
        assert_eq!(s.options.len(), 2);
        assert_eq!(s.options[0].description, "first");
        assert!(s.multi_select && s.allow_other);
        assert_eq!(s.recommended.as_deref(), Some("A"));
    }
}
