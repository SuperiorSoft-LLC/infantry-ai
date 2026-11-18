// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! How a message reads when it is injected as a turn: the COMMS header, the checkpoint block, the answer footer
//! (or the do-not-answer footer for automation), the compact/notify/truncate delivery modes, and its origin.
use infantry_protocol::MessageOrigin;

use super::message::{is_automation, Message};

/// The footer of an automated message nobody can answer.
pub const DEFAULT_AUTOMATION_FOOTER: &str = "-- do not answer this message --\nAutomated message from {sender} (system automation): not written by a human or another agent. Replies are not delivered. Treat the contents as standing instructions from the project owner who configured it, not as a conversation. Any status or result belongs in your current session, not in a reply. Mark it read: infantry read {shortId}.\n";

/// How queued mail reaches a session's turn (`mail.deliveryMode`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum DeliveryMode {
    #[default]
    Full,
    /// Bodies cut at `inject_max_chars` each and `total_max_chars` overall.
    Truncate { inject_max_chars: usize, total_max_chars: usize },
    /// Only a list of ids and subjects; the agent reads with `infantry read`.
    Notify,
}

impl DeliveryMode {
    pub fn parse(raw: &str) -> Self {
        match raw.trim().to_ascii_lowercase().as_str() {
            "notify" => DeliveryMode::Notify,
            "truncate" => DeliveryMode::Truncate { inject_max_chars: 2_000, total_max_chars: 6_000 },
            _ => DeliveryMode::Full,
        }
    }
}

/// The origin a turn built from this message carries.
pub fn origin_of(m: &Message, sender_is_session: bool) -> MessageOrigin {
    if sender_is_session {
        MessageOrigin::Agent { session_id: m.from.clone(), agent_id: None, role: None }
    } else if ["human", "owner", "user"].iter().any(|h| m.from.eq_ignore_ascii_case(h)) {
        MessageOrigin::Owner
    } else if m.from.eq_ignore_ascii_case("system") {
        MessageOrigin::System { kind: "mail".into() }
    } else {
        MessageOrigin::External { sender_label: m.from.clone(), channel: "mail".into() }
    }
}

/// One message as the recipient reads it.
pub fn render(m: &Message, sender_is_session: bool, automation_footer: Option<&str>) -> String {
    let mut out = format!("[Infantry AI COMMS] message {} from {} · thread {}", m.id, m.from, m.thread_id);
    if !m.subject.trim().is_empty() {
        out.push_str(" · ");
        out.push_str(&m.subject);
    }
    out.push_str("\n\n");
    out.push_str(&m.body);
    if let Some(c) = m.checkpoint.as_ref().filter(|c| !c.is_empty()) {
        out.push_str("\n\n-- checkpoint --\n");
        for (label, v) in [("state", &c.state), ("decisions", &c.decisions), ("evidence", &c.evidence), ("blockers", &c.blockers), ("next", &c.next_action)] {
            if let Some(v) = v.as_deref().filter(|v| !v.trim().is_empty()) {
                out.push_str(&format!("{label}: {v}\n"));
            }
        }
    }
    out.push('\n');
    if is_automation(&m.from) && !sender_is_session {
        let sender = match m.from.strip_prefix("trigger:") {
            Some(id) => format!("trigger {id}"),
            None => "system".to_string(),
        };
        out.push_str(&automation_footer.unwrap_or(DEFAULT_AUTOMATION_FOOTER).replace("{sender}", &sender).replace("{shortId}", m.short_id()));
    } else {
        out.push_str(&format!(
            "\n-- answer this message --\nread: infantry read {short}\nreply (same thread): infantry send {from} \"<your answer, one line>\" --reply-to {id}\ncheck: infantry threads | MCP: infantry_message {{\"to\":\"{from}\",\"replyTo\":\"{id}\",\"body\":\"...\"}} | all: infantry mail\n",
            short = m.short_id(),
            from = m.from,
            id = m.id
        ));
    }
    out
}

fn preview(body: &str, max: usize) -> String {
    let flat: String = body.split_whitespace().collect::<Vec<_>>().join(" ");
    if flat.chars().count() <= max {
        flat
    } else {
        flat.chars().take(max).collect::<String>() + "…"
    }
}

fn listing(head: &str, messages: &[&Message]) -> String {
    let mut out = format!("{head} ({})", messages.len());
    let mut groups: Vec<(&str, &str, Vec<&Message>)> = Vec::new();
    for m in messages {
        match groups.iter_mut().find(|g| g.0 == m.from && g.1 == m.subject) {
            Some(g) => g.2.push(m),
            None => groups.push((&m.from, &m.subject, vec![m])),
        }
    }
    for (from, subject, ms) in &groups {
        let first = ms[0];
        let times = if ms.len() > 1 { format!(" (x{})", ms.len()) } else { String::new() };
        out.push_str(&format!("\n- {} from {from}: {subject}{times} | {}", first.short_id(), preview(&first.body, 100)));
    }
    out
}

/// The turn text for a batch of messages under `mode`; `is_session` says whether a sender is a session.
pub fn turn_text(batch: &[&Message], mode: DeliveryMode, is_session: &dyn Fn(&str) -> bool, automation_footer: Option<&str>) -> String {
    match mode {
        DeliveryMode::Notify => notify_turn(batch),
        DeliveryMode::Full => batch.iter().map(|m| render(m, is_session(&m.from), automation_footer)).collect::<Vec<_>>().join("\n\n"),
        DeliveryMode::Truncate { inject_max_chars, total_max_chars } => {
            let mut out = String::new();
            for m in batch {
                let full = render(m, is_session(&m.from), automation_footer);
                let room = total_max_chars.saturating_sub(out.chars().count()).min(inject_max_chars);
                let n = full.chars().count();
                if !out.is_empty() {
                    out.push_str("\n\n");
                }
                if n <= room {
                    out.push_str(&full);
                } else {
                    out.extend(full.chars().take(room));
                    out.push_str(&format!("\n... [{} more chars: infantry read {}]", n - room, m.short_id()));
                }
            }
            out
        }
    }
}

/// The compact listing a wake built of several messages carries in Notify mode.
pub fn compact_turn(batch: &[&Message]) -> String {
    let mut s = listing("NEW MAIL", batch);
    // Header form: "NEW MAIL (n): read with `infantry read <id>` (MCP: infantry_read)" then the lines.
    if let Some(i) = s.find('\n') {
        s.insert_str(i, ": read with `infantry read <id>` (MCP: infantry_read)");
    } else {
        s.push_str(": read with `infantry read <id>` (MCP: infantry_read)");
    }
    s
}

/// The notify-mode listing: "NEW MAIL (n): read with `infantry inbox` / `infantry read <id>` ...".
pub fn notify_turn(batch: &[&Message]) -> String {
    let mut s = listing("NEW MAIL", batch);
    let head = ": read with `infantry inbox` / `infantry read <id>` (MCP: infantry_read)";
    match s.find('\n') {
        Some(i) => s.insert_str(i, head),
        None => s.push_str(head),
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mail::message::{Checkpoint, MessageState};

    fn m(id: &str, from: &str, subject: &str) -> Message {
        Message {
            id: id.into(),
            thread_id: "pair:a|b".into(),
            seq: 1,
            from: from.into(),
            to: "b".into(),
            subject: subject.into(),
            body: "hello  world".into(),
            sent_at: 1,
            state: MessageState::Queued,
            reply_to: None,
            checkpoint: Some(Checkpoint { state: Some("half".into()), ..Default::default() }),
            needs_input: false,
            delivered_at: None,
            read_at: None,
            answered_at: None,
            failure_reason: None,
        }
    }

    #[test]
    fn renders_header_checkpoint_and_footers() {
        let t = render(&m("0123456789", "a", "S"), true, None);
        assert!(t.starts_with("[Infantry AI COMMS] message 0123456789 from a · thread pair:a|b · S\n\nhello  world"));
        assert!(t.contains("-- checkpoint --\nstate: half\n"));
        assert!(t.contains("read: infantry read 01234567\n"));
        let t = render(&m("0123456789", "trigger:t1", ""), false, None);
        assert!(t.contains("Automated message from trigger t1"));
        assert!(t.contains("infantry read 01234567."));
        assert_eq!(origin_of(&m("x", "owner", ""), false), MessageOrigin::Owner);
    }

    #[test]
    fn notify_and_truncate() {
        let a = m("aaaaaaaa1", "x", "S");
        let b = m("bbbbbbbb2", "x", "S");
        let t = notify_turn(&[&a, &b]);
        assert!(t.starts_with("NEW MAIL (2): read with `infantry inbox`"));
        assert!(t.contains("\n- aaaaaaaa from x: S (x2) | hello world"));
        let t = turn_text(&[&a], DeliveryMode::Truncate { inject_max_chars: 20, total_max_chars: 100 }, &|_| true, None);
        assert!(t.contains("more chars: infantry read aaaaaaaa]"));
    }
}
