// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! ASK-1 / MAIL-OWNER-1: `infantry ask --question "<q>" --option "<label>[|<description>]" --option ... --recommend <label>
//! --reason "<why>" [--default-after <min>]`: an agent asks the owner ONE decision, non-blocking (the Kotlin CLI's
//! `ask --recommend` contract). It is the `infantry_ask` tool: the decision shows at the top of the owner's Mail inbox
//! with one button per option and a free-text answer; the pick comes back to this session as mail on thread
//! `question:<id>`. Prints `{"questionId":..,"thread":..,"note":..}`; exit 2 refused, 1 the daemon failed.
//! The blocking `ask` without `--recommend` (a harness prompt) is not native yet: [is_native] leaves it to the launcher.

use super::args::Parsed;
use super::plugin_tools;
use super::{CmdResult, Command, Ctx, Getenv, Needs};
use serde_json::{json, Map, Value};

const USAGE: &str =
    "usage: infantry ask --question \"<question>\" --option \"<label>[|<description>]\" --option ... --recommend <label> --reason \"<why>\" [--default-after <min>]";

/// Only the non-blocking owner question (`--recommend`) is native.
pub fn is_native(p: &Parsed) -> bool {
    p.val("recommend").is_some()
}

fn question(p: &Parsed) -> String {
    p.val("question").map(str::to_string).unwrap_or_else(|| p.args.join(" "))
}

fn validate(p: &Parsed, getenv: Getenv) -> Option<String> {
    if getenv("INFANTRY_SESSION_ID").or_else(|| getenv("AGENTHQ_SESSION_ID")).is_none_or(|s| s.trim().is_empty()) {
        return Some("infantry ask needs the asking session (INFANTRY_SESSION_ID): run it from an agent session; the answer is mailed to it".into());
    }
    if question(p).trim().is_empty() || p.vals("option").len() < 2 || p.val("reason").is_none() {
        return Some(USAGE.into());
    }
    if p.val("default-after").is_some_and(|m| m.trim().parse::<i64>().is_err()) {
        return Some("--default-after expects whole minutes".into());
    }
    None
}

/// The `infantry_ask` arguments of the parsed words; `--option "label|description"` splits at the first `|`.
pub fn arguments_of(p: &Parsed) -> Map<String, Value> {
    let options: Vec<Value> = p
        .vals("option")
        .iter()
        .map(|raw| match raw.split_once('|') {
            Some((l, d)) => json!({"label": l.trim(), "description": d.trim()}),
            None => json!({"label": raw.trim()}),
        })
        .collect();
    let mut a = json!({ "question": question(p), "options": options, "recommended": p.val("recommend"), "reason": p.val("reason") });
    if let Some(m) = p.val("default-after").and_then(|m| m.trim().parse::<i64>().ok()) {
        a["defaultAfterMin"] = json!(m);
    }
    a.as_object().cloned().unwrap_or_default()
}

fn run(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let r = plugin_tools::call(ctx, "infantry_ask", arguments_of(p))?;
    Ok(if r.is_error { plugin_tools::print(ctx, &r) + 1 } else { plugin_tools::print(ctx, &r) })
}

pub static ASK: Command = Command { name: "ask", needs: Needs::Stream, validate, run };

#[cfg(test)]
mod tests {
    use super::*;

    fn parse(words: &[&str]) -> Parsed {
        super::super::args::parse(&words.iter().map(|w| w.to_string()).collect::<Vec<_>>()).expect("parses")
    }

    #[test]
    fn an_ask_names_the_question_options_a_recommendation_and_a_reason() {
        let env = |k: &str| (k == "INFANTRY_SESSION_ID").then(|| "s1".to_string());
        let p = parse(&["ask", "--question", "Ship tonight?", "--option", "Ship", "--option", "Wait|for the phone", "--recommend", "Ship", "--reason", "green", "--default-after", "30"]);
        assert!(is_native(&p));
        assert_eq!(validate(&p, &env), None);
        assert_eq!(
            Value::Object(arguments_of(&p)),
            json!({"question": "Ship tonight?", "options": [{"label": "Ship"}, {"label": "Wait", "description": "for the phone"}], "recommended": "Ship", "reason": "green", "defaultAfterMin": 30})
        );
        assert!(validate(&p, &|_| None).is_some(), "no session, no ask");
        assert!(validate(&parse(&["ask", "--question", "Q?", "--option", "A", "--recommend", "A", "--reason", "r"]), &env).is_some(), "one option is not a choice");
        assert!(!is_native(&parse(&["ask", "--question", "Q?", "--option", "A", "--option", "B"])), "the blocking form stays with the launcher");
    }
}
