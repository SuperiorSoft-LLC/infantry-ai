// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The mailbox data model: one [`Message`] is both the stored row (`mailbox.json`) and the wire record (`mail`,
//! `mails`). Field order and names match the Kotlin `Message` byte for byte, so the migrated `mailbox.json` reads as is.
use serde::{Deserialize, Serialize};

/// Where one message is in its life: QUEUED -> DELIVERED -> READ -> ANSWERED, or the terminal UNDELIVERABLE.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize, Default)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum MessageState {
    #[default]
    Queued,
    Delivered,
    Read,
    Answered,
    Undeliverable,
}

/// What a thread is keyed by.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ThreadKind {
    AgentPair,
    Run,
    Pod,
    Custom,
}

/// Durable handoff state; every part optional, an all-blank checkpoint is no checkpoint.
#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Checkpoint {
    #[serde(default)]
    pub state: Option<String>,
    #[serde(default)]
    pub decisions: Option<String>,
    #[serde(default)]
    pub evidence: Option<String>,
    #[serde(default)]
    pub blockers: Option<String>,
    #[serde(default)]
    pub next_action: Option<String>,
}

impl Checkpoint {
    pub fn is_empty(&self) -> bool {
        [&self.state, &self.decisions, &self.evidence, &self.blockers, &self.next_action]
            .iter()
            .all(|v| v.as_deref().map(str::trim).unwrap_or("").is_empty())
    }
}

/// One mailbox message. `seq` is monotonic within `thread_id`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Message {
    pub id: String,
    pub thread_id: String,
    pub seq: i64,
    pub from: String,
    pub to: String,
    #[serde(default)]
    pub subject: String,
    pub body: String,
    pub sent_at: i64,
    #[serde(default)]
    pub state: MessageState,
    #[serde(default)]
    pub reply_to: Option<String>,
    #[serde(default)]
    pub checkpoint: Option<Checkpoint>,
    #[serde(default)]
    pub needs_input: bool,
    #[serde(default)]
    pub delivered_at: Option<i64>,
    #[serde(default)]
    pub read_at: Option<i64>,
    #[serde(default)]
    pub answered_at: Option<i64>,
    #[serde(default)]
    pub failure_reason: Option<String>,
}

impl Message {
    pub fn delivered(&self) -> bool {
        self.delivered_at.is_some() || matches!(self.state, MessageState::Delivered | MessageState::Read | MessageState::Answered)
    }
    pub fn read(&self) -> bool {
        self.read_at.is_some() || matches!(self.state, MessageState::Read | MessageState::Answered)
    }
    pub fn answered(&self) -> bool {
        self.state == MessageState::Answered
    }
    pub fn failed(&self) -> bool {
        self.state == MessageState::Undeliverable
    }
    /// An open needs-input signal: flagged and neither answered nor failed.
    pub fn open_question(&self) -> bool {
        self.needs_input && !matches!(self.state, MessageState::Answered | MessageState::Undeliverable)
    }
    /// The 8-character id `infantry read` takes.
    pub fn short_id(&self) -> &str {
        let end = self.id.char_indices().nth(8).map(|(i, _)| i).unwrap_or(self.id.len());
        &self.id[..end]
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ThreadSummary {
    pub id: String,
    pub kind: ThreadKind,
    pub participants: Vec<String>,
    pub group: String,
    pub message_count: usize,
    pub last_at: i64,
    pub ceiling_reached: bool,
    pub open_needs_input: usize,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WaitingOn {
    pub session: String,
    pub message_id: String,
    pub thread_id: String,
    pub waiting_on: String,
    pub since: i64,
    pub subject: String,
}

/// `mail_send` payload. A blank `from` is filled by the frame handler with the acting session.
#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MailSendRequest {
    #[serde(default)]
    pub from: String,
    pub to: String,
    #[serde(default)]
    pub subject: String,
    #[serde(default)]
    pub body: String,
    #[serde(default)]
    pub thread_id: Option<String>,
    #[serde(default)]
    pub reply_to: Option<String>,
    #[serde(default)]
    pub checkpoint: Option<Checkpoint>,
    #[serde(default)]
    pub needs_input: bool,
    #[serde(default)]
    pub allow_cross_project: bool,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MailRefused {
    pub from: String,
    pub to: String,
    #[serde(default)]
    pub thread_id: Option<String>,
    pub reason: String,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MailReadRequest {
    pub message_id: String,
    #[serde(default)]
    pub session: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MailFanOutRequest {
    pub from: String,
    pub recipients: Vec<String>,
    #[serde(default)]
    pub subject: String,
    #[serde(default)]
    pub body: String,
    #[serde(default)]
    pub thread_id: Option<String>,
    #[serde(default)]
    pub checkpoint: Option<Checkpoint>,
    #[serde(default)]
    pub needs_input: bool,
}

/// Thread naming.
pub mod threads {
    use super::ThreadKind;

    /// The order-independent agent-pair thread.
    pub fn pair(a: &str, b: &str) -> String {
        let (x, y) = if a <= b { (a, b) } else { (b, a) };
        format!("pair:{x}|{y}")
    }
    /// Roll `n` of a pair thread: roll 1 is the base itself.
    pub fn rolled(base: &str, n: u32) -> String {
        if n <= 1 {
            base.to_string()
        } else {
            format!("{base}:{n}")
        }
    }
    /// The pair thread a rolled-over id belongs to; any other id is its own group.
    pub fn group_of(thread_id: &str) -> String {
        if let Some(rest) = thread_id.strip_prefix("pair:") {
            if let Some(i) = rest.rfind(':') {
                if i + 1 < rest.len() && rest[i + 1..].bytes().all(|b| b.is_ascii_digit()) {
                    return format!("pair:{}", &rest[..i]);
                }
            }
        }
        thread_id.to_string()
    }
    pub fn fan_out(id: &str) -> String {
        format!("fanout:{id}")
    }
    pub fn kind_of(thread_id: &str) -> ThreadKind {
        if thread_id.starts_with("pair:") {
            ThreadKind::AgentPair
        } else if thread_id.starts_with("run:") {
            ThreadKind::Run
        } else if thread_id.starts_with("pod:") {
            ThreadKind::Pod
        } else {
            ThreadKind::Custom
        }
    }
    pub fn pair_members(thread_id: &str) -> Vec<String> {
        if !thread_id.starts_with("pair:") {
            return Vec::new();
        }
        group_of(thread_id)["pair:".len()..].split('|').map(str::to_string).collect()
    }
    /// A bare name becomes a `t:` thread; a qualified id is kept.
    pub fn normalize(raw: Option<&str>) -> Option<String> {
        let t = raw?.trim();
        if t.is_empty() {
            None
        } else if t.contains(':') {
            Some(t.to_string())
        } else {
            Some(format!("t:{t}"))
        }
    }
}

/// Mail from an automatic sender (`system`, `trigger:<id>`) has no mailbox to answer into.
pub fn is_automation(from: &str) -> bool {
    from.starts_with("trigger:") || from.eq_ignore_ascii_case("system")
}

/// The recipient alias for the session that started the sender's ticket.
pub const STARTER: &str = "starter";
/// The sender of every daemon notice.
pub const SYSTEM: &str = "system";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn thread_names() {
        assert_eq!(threads::pair("b", "a"), "pair:a|b");
        assert_eq!(threads::group_of("pair:a|b:3"), "pair:a|b");
        assert_eq!(threads::group_of("run:x:3"), "run:x:3");
        assert_eq!(threads::pair_members("pair:a|b:2"), vec!["a", "b"]);
        assert_eq!(threads::normalize(Some(" x ")).as_deref(), Some("t:x"));
        assert_eq!(threads::normalize(Some("run:1")).as_deref(), Some("run:1"));
        assert_eq!(threads::kind_of("pod:1"), ThreadKind::Pod);
    }

    #[test]
    fn message_round_trips_kotlin_bytes() {
        let text = r#"{"id":"m1","threadId":"pair:a|b","seq":1,"from":"a","to":"b","subject":"","body":"hi","sentAt":5,"state":"QUEUED","replyTo":null,"checkpoint":null,"needsInput":false,"deliveredAt":null,"readAt":null,"answeredAt":null,"failureReason":null}"#;
        let m: Message = serde_json::from_str(text).unwrap();
        assert_eq!(serde_json::to_string(&m).unwrap(), text);
        // An older row with defaults left out still reads.
        let m: Message = serde_json::from_str(r#"{"id":"m","threadId":"t","seq":1,"from":"a","to":"b","body":"x","sentAt":1}"#).unwrap();
        assert_eq!(m.state, MessageState::Queued);
    }
}
