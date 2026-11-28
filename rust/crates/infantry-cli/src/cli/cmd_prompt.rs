// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `infantry prompt answer <promptId> [--answer <label>]... [--text <t>] | --escalate` (NEEDS-INPUT-1): a PM answers a
//! question an agent is blocked on (the card routed to it by `questions.route`), or hands it to the owner. The answer
//! goes back to the blocked tool call through the daemon's `prompt_response` frame; nothing here knows the harness.

use super::args::Parsed;
use super::conn::Envelope;
use super::{CmdResult, Command, Ctx, Getenv, Needs};
use serde_json::json;

const USAGE: &str = "usage: infantry prompt <sessionId> \"<text>\" | prompt answer <promptId> (--answer <label>... [--text <t>] | --text <t> | --escalate)";

/// `prompt <sessionId> <text...>`: the words after the session are the text a session gets as its next turn.
fn is_send(p: &Parsed) -> bool {
    p.arg(0).is_some_and(|a| a != "answer")
}

fn validate(p: &Parsed, _: Getenv) -> Option<String> {
    if is_send(p) {
        return (p.args.len() < 2 || p.args[1..].join(" ").trim().is_empty()).then(|| USAGE.into());
    }
    if p.arg(0) != Some("answer") || p.arg(1).is_none() {
        return Some(USAGE.into());
    }
    if p.flag("escalate") && (!p.vals("answer").is_empty() || p.val("text").is_some()) {
        return Some("--escalate takes no answer".into());
    }
    if !p.flag("escalate") && p.vals("answer").is_empty() && p.val("text").is_none() {
        return Some(USAGE.into());
    }
    None
}

/// The `prompt_response` body of the parsed words.
pub fn body_of(p: &Parsed) -> serde_json::Value {
    let id = p.arg(1).unwrap_or("");
    if p.flag("escalate") {
        return json!({ "promptId": id, "decision": "escalate" });
    }
    json!({ "promptId": id, "answers": p.vals("answer"), "otherText": p.val("text") })
}

/// Sends the text as the session's next prompt (the same `prompt` frame the chat sends, with a `send:` request id so
/// the daemon acknowledges it); a refused or unknown session exits 1 with the daemon's reason.
fn send(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let sid = p.args[0].trim().to_string();
    let text = p.args[1..].join(" ").trim().to_string();
    let mut env = Envelope::new("prompt", text).with_session(Some(sid.clone()));
    env.request_id = Some(format!("send:{}", uuid::Uuid::new_v4()));
    let ans = ctx.stream().request(&env, "prompt_ack", "prompt", false)?;
    ctx.out(format!("prompt {} to {sid}", if ans.data.trim() == "duplicate" { "already sent" } else { "sent" }));
    Ok(0)
}

fn run(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    if is_send(p) {
        return send(ctx, p);
    }
    let ans = ctx.stream().request(&Envelope::new("prompt_response", body_of(p).to_string()), "prompt_resolved", "prompt_response", false)?;
    let v: serde_json::Value = serde_json::from_str(&ans.data).unwrap_or_default();
    ctx.out(format!("{}: {}", v["promptId"].as_str().unwrap_or(p.arg(1).unwrap_or("")), v["outcome"].as_str().unwrap_or("done")));
    Ok(0)
}

pub static PROMPT: Command = Command { name: "prompt", needs: Needs::Stream, validate, run };

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(words: &[&str]) -> Parsed {
        super::super::args::parse(&words.iter().map(|w| w.to_string()).collect::<Vec<_>>()).expect("parses")
    }

    #[test]
    fn an_answer_names_labels_or_text_and_escalate_names_nothing() {
        let p = parse(&["prompt", "answer", "p1", "--answer", "A", "--text", "why"]);
        assert_eq!(validate(&p, &|_| None), None);
        assert_eq!(body_of(&p), json!({"promptId": "p1", "answers": ["A"], "otherText": "why"}));
        let e = parse(&["prompt", "answer", "p1", "--escalate"]);
        assert_eq!(validate(&e, &|_| None), None);
        assert_eq!(body_of(&e), json!({"promptId": "p1", "decision": "escalate"}));
        assert!(validate(&parse(&["prompt", "answer", "p1"]), &|_| None).is_some());
        assert!(validate(&parse(&["prompt", "answer", "p1", "--escalate", "--text", "x"]), &|_| None).is_some());
    }

    #[test]
    fn a_send_names_a_session_and_text() {
        assert_eq!(validate(&parse(&["prompt", "s1", "hello", "there"]), &|_| None), None);
        assert!(validate(&parse(&["prompt", "s1"]), &|_| None).is_some());
        assert!(validate(&parse(&["prompt"]), &|_| None).is_some());
    }
}
