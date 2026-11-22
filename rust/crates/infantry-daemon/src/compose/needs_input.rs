// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! NEEDS-INPUT-1: an agent that needs an ANSWER (a question card, an `ask`-mode approval) is not an agent that waits on a
//! build. This wires the prompt store to two generic mechanisms and names no harness:
//!
//! - hook events on the core bus (`session.needs_input`, `session.input_answered`; the waiting flag's
//!   `session.waiting_on` / `session.waiting_off` come from the session manager), which scripts subscribe to;
//! - routing, the setting `questions.route` (`owner` | `pm` | `pm-then-owner`; scopes app, project, role; default `owner`):
//!   with `pm` the card goes to the project's PM session as ONE wake (kind `session.needs_input`, key = the prompt id,
//!   through the WAKE-1 policy), the PM answers with `infantry prompt answer`, and with `pm-then-owner` it may escalate.
//!   No live PM in the project = the owner's card, as before.
use std::sync::Arc;

use infantry_core::bus::EventBus;
use infantry_core::db::Db;
use infantry_mail::notify::prompts::{PendingPrompts, PromptEvent, ROUTE_PM, ROUTE_PM_THEN_OWNER};
use infantry_protocol::prompts::PromptRequest;
use serde_json::{json, Value};

use super::adapters::DaemonBoardEnv;
use super::settings::SettingsUnit;
use crate::sessions::Daemon;

pub const ROUTE_KEY: &str = "questions.route";
pub const NEEDS_INPUT: &str = "session.needs_input";
pub const INPUT_ANSWERED: &str = "session.input_answered";

/// A `questions.route` value: Some(route a card carries), None = the owner (also for any unknown text).
pub fn parse_route(text: &str) -> Option<&'static str> {
    let t = text.trim().to_ascii_lowercase().replace(['_', ' '], "-");
    [ROUTE_PM, ROUTE_PM_THEN_OWNER].into_iter().find(|r| *r == t)
}

fn text_of(v: &Value) -> Option<String> {
    v.get("value").and_then(Value::as_str).map(str::to_string)
}

/// The route a new card of [session] takes: the setting, and only when a PM can take it.
fn route_for(daemon: &Daemon, settings: &SettingsUnit, env: &DaemonBoardEnv, session: &str) -> Option<String> {
    let me = daemon.record(session)?;
    let d = json!({"session": session, "project": me.project_id, "role": me.role});
    let route = parse_route(&text_of(&settings.resolve(ROUTE_KEY, &d))?)?;
    env.question_answerer(session)?;
    Some(route.to_string())
}

/// The event payload of an opened card.
pub fn asked_payload(r: &PromptRequest, ticket_id: Option<&str>, project_id: Option<&str>) -> Value {
    json!({
        "sessionId": r.session_id, "ticketId": ticket_id, "projectId": project_id, "promptId": r.prompt_id, "kind": r.kind,
        "question": r.question, "options": r.options.iter().map(|o| o.label.clone()).collect::<Vec<_>>(),
        "multiSelect": r.multi_select, "tool": r.tool, "input": r.input, "route": r.route.clone().unwrap_or_else(|| "owner".into()),
    })
}

/// The ONE line the PM is woken with: (subject, body).
pub fn wake_line(r: &PromptRequest, ticket_id: Option<&str>) -> (String, String) {
    let what = r.question.clone().or_else(|| r.tool.as_ref().map(|t| format!("approve {t}: {}", r.input.clone().unwrap_or_default()))).unwrap_or_default();
    let opts = if r.options.is_empty() { String::new() } else { format!("\nOptions: {}", r.options.iter().map(|o| o.label.as_str()).collect::<Vec<_>>().join(" | ")) };
    let escalate = if r.route.as_deref() == Some(ROUTE_PM_THEN_OWNER) { " Or hand it to the owner: --escalate." } else { "" };
    let ticket = ticket_id.map(|t| format!(" (ticket {t})")).unwrap_or_default();
    (
        format!("session {} needs an answer{ticket}", r.session_id.chars().take(8).collect::<String>()),
        format!(
            "{what}{opts}\nAnswer with the infantry_prompt_answer tool (promptId {id}, answers [\"<option>\"], otherText) or: infantry prompt answer {id} --answer \"<option>\" [--text \"<own words>\"].{escalate}",
            id = r.prompt_id
        ),
    )
}

pub fn wire(prompts: &Arc<PendingPrompts>, daemon: &Arc<Daemon>, db: &Arc<Db>, env: &Arc<DaemonBoardEnv>, bus: &EventBus) {
    {
        let (d, s, e) = (daemon.clone(), SettingsUnit { db: db.clone() }, env.clone());
        prompts.set_router(move |session| route_for(&d, &s, &e, session));
    }
    let (d, env, bus) = (daemon.clone(), env.clone(), bus.clone());
    prompts.set_observer(move |ev| match ev {
        PromptEvent::Asked(r) => {
            let rec = d.record(&r.session_id);
            let ticket = rec.as_ref().and_then(|x| x.ticket_id.clone());
            bus.emit(NEEDS_INPUT, asked_payload(r, ticket.as_deref(), rec.as_ref().and_then(|x| x.project_id.as_deref())));
            if r.route.is_some() {
                if let Some(pm) = env.question_answerer(&r.session_id) {
                    let (subject, line) = wake_line(r, ticket.as_deref());
                    env.wake_session(&pm, NEEDS_INPUT, &r.prompt_id, &subject, &line);
                }
            }
        }
        PromptEvent::Resolved(r, done) => {
            let ticket = d.record(&r.session_id).and_then(|x| x.ticket_id);
            bus.emit(INPUT_ANSWERED, json!({"sessionId": r.session_id, "ticketId": ticket, "promptId": r.prompt_id, "outcome": done.outcome, "resolvedBy": done.resolved_by}));
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;
    use infantry_protocol::prompts::PromptOption;

    fn card(route: Option<&str>) -> PromptRequest {
        PromptRequest {
            prompt_id: "p1".into(),
            session_id: "sess-1234-abcd".into(),
            kind: "question".into(),
            created_at: 0,
            tool: None,
            input: None,
            question: Some("Pick?".into()),
            options: vec![PromptOption { label: "A".into(), description: String::new() }],
            multi_select: false,
            allow_other: true,
            expires_at: None,
            on_timeout: None,
            recommended: None,
            route: route.map(String::from),
        }
    }

    #[test]
    fn the_route_setting_reads_three_words_and_anything_else_is_the_owner() {
        assert_eq!(parse_route("pm"), Some("pm"));
        assert_eq!(parse_route(" PM_then_owner "), Some("pm-then-owner"));
        assert_eq!(parse_route("owner"), None);
        assert_eq!(parse_route("nonsense"), None);
    }

    #[test]
    fn the_event_and_the_wake_line_carry_the_question_and_how_to_answer_it() {
        let p = asked_payload(&card(Some("pm")), Some("t1"), Some("proj"));
        assert_eq!((p["question"].as_str(), p["options"][0].as_str(), p["ticketId"].as_str(), p["route"].as_str()), (Some("Pick?"), Some("A"), Some("t1"), Some("pm")));
        let (subject, line) = wake_line(&card(Some("pm")), Some("t1"));
        assert!(subject.contains("sess-123") && subject.contains("t1"));
        assert!(line.contains("infantry prompt answer p1") && !line.contains("--escalate"));
        assert!(wake_line(&card(Some("pm-then-owner")), None).1.contains("--escalate"));
    }
}
