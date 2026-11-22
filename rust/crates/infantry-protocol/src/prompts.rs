// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Prompt library, tool-approval prompts and owner questions
//! (`Prompts.kt`, `PromptFrames.kt`, `QuestionFrames.kt`, `ToolApprovalFrames.kt`).
use crate::dto;

pub mod prompt_kinds {
    pub const TOOL_APPROVAL: &str = "tool_approval";
    pub const QUESTION: &str = "question";
}
pub mod prompt_decisions {
    pub const ALLOW_ONCE: &str = "allow_once";
    pub const ALLOW_SESSION: &str = "allow_session";
    pub const ALLOW_ALWAYS: &str = "allow_always";
    pub const DENY: &str = "deny";
    pub const ALL: [&str; 4] = [ALLOW_ONCE, ALLOW_SESSION, ALLOW_ALWAYS, DENY];
}
pub mod prompt_resolved_by {
    pub const TIMEOUT: &str = "timeout";
    pub const SESSION_END: &str = "session_end";
    pub const DAEMON_STOP: &str = "daemon_stop";
    pub const CANCELLED: &str = "cancelled";
}
pub mod prompt_outcomes {
    pub const ANSWERED: &str = "answered";
    pub const NO_ANSWER: &str = "no_answer";
}
pub mod question_states {
    pub const OPEN: &str = "open";
    pub const ANSWERED: &str = "answered";
    pub const AUTO: &str = "auto";
    pub const CANCELLED: &str = "cancelled";
    pub const ALL: [&str; 4] = [OPEN, ANSWERED, AUTO, CANCELLED];
}
pub mod question_resolved_by {
    pub const OWNER: &str = "owner";
    pub const AUTO: &str = "auto";
    pub const ASKER: &str = "asker";
    pub const ORPHANED: &str = "orphaned";
}

fn open() -> String {
    "open".into()
}
const NON_BLOCKING_NOTE: &str =
    "non-blocking: carry on with other work; the owner's answer arrives as mail in thread question:<id>";
fn note() -> String {
    NON_BLOCKING_NOTE.into()
}
fn yes() -> bool {
    true
}

dto! { pub struct PromptSave { pub name: String, pub body: String, #[serde(default)] pub tags: Vec<String> } }
dto! {
    pub struct PromptView {
        pub id: String,
        pub name: String,
        pub body: String,
        #[serde(default)] pub tags: Vec<String>,
        pub created_at: i64,
        pub updated_at: i64,
    }
}

dto! { pub struct PromptOption { pub label: String, #[serde(default)] pub description: String } }

dto! {
    /// Daemon -> client: an open tool-approval prompt or question.
    pub struct PromptRequest {
        pub prompt_id: String,
        pub session_id: String,
        pub kind: String,
        pub created_at: i64,
        #[serde(default)] pub tool: Option<String>,
        #[serde(default)] pub input: Option<String>,
        #[serde(default)] pub question: Option<String>,
        #[serde(default)] pub options: Vec<PromptOption>,
        #[serde(default)] pub multi_select: bool,
        #[serde(default)] pub allow_other: bool,
        #[serde(default)] pub expires_at: Option<i64>,
        #[serde(default)] pub on_timeout: Option<String>,
        #[serde(default)] pub recommended: Option<String>,
        /// NEEDS-INPUT-1: who must answer first. `pm` = the project's PM session (the owner's client does not draw the card);
        /// absent = the owner. The PM escalates with `decision: "escalate"`, which clears it and re-sends the card.
        #[serde(default, skip_serializing_if = "Option::is_none")] pub route: Option<String>,
    }
}
dto! {
    pub struct PromptResponse {
        pub prompt_id: String,
        #[serde(default)] pub decision: Option<String>,
        #[serde(default)] pub answers: Vec<String>,
        #[serde(default)] pub other_text: Option<String>,
    }
}
dto! { pub struct PromptResolved { pub prompt_id: String, pub resolved_by: String, pub outcome: String } }

dto! {
    pub struct ApprovalRule {
        pub rule_id: String,
        pub project_id: String,
        pub tool: String,
        #[serde(default)] pub prefix: Option<String>,
        pub created_at: i64,
    }
}
dto! { pub struct ApprovalRulesQuery { #[serde(default)] pub project_id: Option<String> } }
dto! {
    pub struct ApprovalRulesList {
        #[serde(default)] pub project_id: Option<String>,
        #[serde(default)] pub rules: Vec<ApprovalRule>,
    }
}
dto! { pub struct ApprovalRuleDelete { pub rule_id: String } }

dto! {
    pub struct PromptAsk {
        pub question: String,
        pub options: Vec<PromptOption>,
        #[serde(default)] pub multi_select: bool,
        #[serde(default)] pub allow_other: bool,
        #[serde(default)] pub timeout_sec: Option<i32>,
        #[serde(default)] pub recommended: Option<String>,
    }
}
impl PromptAsk {
    pub const TIMEOUT_SEC_RANGE: std::ops::RangeInclusive<i32> = 1..=86_400;
}
dto! {
    pub struct PromptAnswer {
        pub answered: bool,
        #[serde(default)] pub answers: Vec<String>,
        #[serde(default)] pub other_text: Option<String>,
    }
}

dto! {
    pub struct QuestionAsk {
        pub question: String,
        pub options: Vec<PromptOption>,
        pub recommended: String,
        pub reason: String,
        #[serde(default = "yes")] pub allow_other: bool,
        #[serde(default)] pub default_after_min: Option<i32>,
    }
}
impl QuestionAsk {
    pub const QUESTION_MAX: usize = 2_000;
    pub const REASON_MAX: usize = 200;
    pub const OPTIONS_MAX: usize = 8;
    pub const DEFAULT_AFTER_MIN_RANGE: std::ops::RangeInclusive<i32> = 1..=1_440;
}
dto! {
    pub struct QuestionAsked {
        pub question_id: String,
        #[serde(default = "note")] pub note: String,
    }
}
impl QuestionAsked {
    pub const NON_BLOCKING_NOTE: &'static str = NON_BLOCKING_NOTE;
}
dto! {
    pub struct OwnerQuestion {
        pub id: String,
        #[serde(default)] pub project_id: Option<String>,
        pub session_id: String,
        #[serde(default)] pub agent_id: Option<String>,
        #[serde(default)] pub role: Option<String>,
        pub question: String,
        pub options: Vec<PromptOption>,
        pub recommended: String,
        pub reason: String,
        #[serde(default)] pub allow_other: bool,
        #[serde(default)] pub default_after_min: Option<i32>,
        #[serde(default = "open")] pub state: String,
        #[serde(default)] pub answer: Option<String>,
        #[serde(default)] pub other_text: Option<String>,
        #[serde(default)] pub resolved_by: Option<String>,
        pub created_at: i64,
        #[serde(default)] pub resolved_at: Option<i64>,
        #[serde(default)] pub overridden: bool,
    }
}
dto! {
    pub struct QuestionAnswer {
        pub question_id: String,
        #[serde(default)] pub answer: Option<String>,
        #[serde(default)] pub other_text: Option<String>,
    }
}
dto! { pub struct QuestionCancel { pub question_id: String } }

dto! {
    pub struct ToolApprovalInfo {
        #[serde(default)] pub mode: Option<String>,
        pub effective: String,
        pub source: String,
        #[serde(default)] pub choices: Vec<String>,
    }
}
dto! { pub struct ToolApprovalSet { #[serde(default)] pub mode: Option<String> } }
