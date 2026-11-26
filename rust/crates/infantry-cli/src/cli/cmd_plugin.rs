// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `infantry plugin <op> [args...] [--approve]`: the owner's plugin manager from the command line. Every word is handed
//! to the daemon's `plugin_admin` frame (`{"args": [...]}`), which answers `{code, out, err}`; this prints `out` to
//! stdout and `err` to stderr and exits with `code`. The operations are the daemon's (`status`, `available`, `search`,
//! `show`, `install`, `uninstall`, `enable`, `disable`, `updates`, `update`, `sources`, `source-add`, ...), so a new one
//! needs no CLI change. Owner only: the CLI sends the owner token of the state dir.

use super::args::Parsed;
use super::conn::Envelope;
use super::{CmdResult, Command, Ctx, Getenv, Needs};
use serde_json::{json, Value};

pub const PLUGIN_USAGE: &str = "usage: infantry plugin <status|available|search|show|install|uninstall|enable|disable|updates|update|sources|source-add|source-remove|source-refresh|signers|signer-add|signer-remove> [id...] [--approve]";
/// An install clones or downloads a module, so the answer can take a while.
const ANSWER_TIMEOUT_MS: u64 = 300_000;

fn validate(p: &Parsed, _: Getenv) -> Option<String> {
    if p.args.is_empty() { Some(PLUGIN_USAGE.into()) } else { None }
}

/// The `plugin_admin` request data for these words; pure, so it is tested.
pub fn request_data(args: &[String], approve: bool) -> String {
    let mut words: Vec<&str> = args.iter().map(String::as_str).collect();
    if approve {
        words.push("--approve");
    }
    json!({ "args": words }).to_string()
}

fn run(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let env = Envelope::new("plugin_admin", request_data(&p.args, p.flag("approve")));
    let answer = ctx.stream().request_within(&env, "plugin_admin", "plugin_admin", false, ANSWER_TIMEOUT_MS)?;
    let v: Value = serde_json::from_str(&answer.data).map_err(|e| format!("unreadable plugin_admin answer: {e}"))?;
    if let Some(out) = v["out"].as_str().filter(|s| !s.is_empty()) {
        ctx.out(out.to_string());
    }
    if let Some(err) = v["err"].as_str().filter(|s| !s.is_empty()) {
        ctx.err(err.to_string());
    }
    Ok(v["code"].as_i64().unwrap_or(1) as i32)
}

pub static PLUGIN: Command = Command { name: "plugin", needs: Needs::OwnerStream, validate, run };

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_words_and_the_approval_go_to_plugin_admin() {
        let v = |a: &[&str]| a.iter().map(|s| s.to_string()).collect::<Vec<_>>();
        assert_eq!(request_data(&v(&["install", "Acme_Hello"]), false), r#"{"args":["install","Acme_Hello"]}"#);
        assert_eq!(request_data(&v(&["install", "Acme_Hello"]), true), r#"{"args":["install","Acme_Hello","--approve"]}"#);
        assert_eq!(request_data(&v(&["status"]), false), r#"{"args":["status"]}"#);
    }
}
