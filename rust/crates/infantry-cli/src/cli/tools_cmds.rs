// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `infantry script *`, `secret *` and `run-cmd *` (Kotlin `ScriptCli`, `SecretCli`, `RunCmdCli`): each is one plugin
//! tool call, its text printed as the tool answered it.

use super::args::Parsed;
use super::plugin_tools::{self, ToolResult};
use super::{CmdResult, Command, Ctx, Getenv, Needs};
use serde_json::{Map, Value};
use std::path::{Path, PathBuf};

fn s(v: &str) -> Value {
    Value::String(v.to_string())
}

pub fn absolute(cwd: &Path, p: &str) -> String {
    let f = PathBuf::from(p);
    let f = if f.is_absolute() { f } else { cwd.join(f) };
    f.to_string_lossy().to_string()
}

const NO_RUN_TOOLS: &str = "error: this daemon has no Run tools (Infantry_Run's daemon half is not loaded)";

fn print_run_tool(ctx: &mut Ctx, r: ToolResult) -> CmdResult {
    if r.text.starts_with("Unknown tool:") {
        ctx.err(NO_RUN_TOOLS);
        return Ok(1);
    }
    Ok(plugin_tools::print(ctx, &r))
}

// ---- script ----

pub const SCRIPT_USAGE: &str = "usage: infantry script hook list | add <on> <script> [--payload <args json>|-] | remove <id> | save <name> --title <t> --description <d> [--tags a,b] [--scope global] [--file <path> | --body -] | list [--query <text>] [--sort used|recent|failing|unused] | delete <name> | files export|import [--scope global] [--dir <path>]   (<on>: \"lane enter done\", \"lane exit in_progress\" or an event name like ticket.commented)";

pub fn script_usage_error(args: &[String]) -> Option<String> {
    let a = |i: usize| args.get(i).map(String::as_str);
    let ok = matches!(
        (args.len(), a(0), a(1)),
        (2, Some("save"), _)
            | (2, Some("files"), Some("export" | "import"))
            | (1, Some("list"), _)
            | (2, Some("delete"), _)
            | (2, Some("hook"), Some("list"))
            | (4, Some("hook"), Some("add"))
            | (3, Some("hook"), Some("remove"))
    );
    (!ok).then(|| SCRIPT_USAGE.to_string())
}

fn script_validate(p: &Parsed, _: Getenv) -> Option<String> {
    script_usage_error(&p.args)
}

fn script_run(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let payload: Option<String> = match p.body.as_deref() {
        Some("-") => Some(ctx.io.stdin()),
        Some(b) => Some(b.to_string()),
        None => p.val("file").and_then(|f| std::fs::read_to_string(absolute(&ctx.cwd, f)).ok()),
    };
    let args = &p.args;
    if args[0] != "hook" {
        if args[0] == "save" && payload.is_none() {
            ctx.err("infantry script save <name> needs the source: --file <path> or --body -");
            return Ok(2);
        }
        let mut a = Map::new();
        if args.len() > 1 {
            a.insert("name".into(), s(&args[1]));
        }
        if args[0] == "save" {
            a.insert("source".into(), s(payload.as_deref().unwrap_or_default()));
        }
        for k in ["title", "description", "tags", "scope", "query", "sort", "dir"] {
            if let Some(v) = p.val(k) {
                a.insert(k.into(), s(v));
            }
        }
        if args[0] == "files" {
            a.remove("name");
            a.insert("action".into(), s(&args[1]));
        }
        let r = plugin_tools::call(ctx, &format!("infantry_script_{}", args[0]), a)?;
        return Ok(plugin_tools::print(ctx, &r));
    }
    let mut a = Map::new();
    a.insert("action".into(), s(&args[1]));
    match args[1].as_str() {
        "add" => {
            a.insert("on".into(), s(&args[2]));
            a.insert("script".into(), s(&args[3]));
            if let Some(pl) = &payload {
                a.insert("args".into(), s(pl));
            }
        }
        "remove" => {
            a.insert("id".into(), s(&args[2]));
        }
        _ => {}
    }
    let r = plugin_tools::call(ctx, "infantry_script_hook", a)?;
    Ok(plugin_tools::print(ctx, &r))
}

pub static SCRIPT: Command = Command { name: "script", needs: Needs::Stream, validate: script_validate, run: script_run };

// ---- secret ----

pub const SECRET_USAGE: &str = "infantry secret render <template> --out <file> [--project <id>] [--allow-missing] | infantry secret render <template> --check | infantry secret find <text> [--project <id>]";

pub fn secret_usage_error(args: &[String], out: Option<&str>, check: bool) -> Option<String> {
    if args.first().map(String::as_str) == Some("find") {
        return (args.len() != 2).then(|| format!("secret find takes one search text: {SECRET_USAGE}"));
    }
    if args.first().map(String::as_str) != Some("render") {
        return Some(format!("secret needs a subcommand: {SECRET_USAGE}"));
    }
    if args.len() != 2 {
        return Some(format!("secret render takes one template file: {SECRET_USAGE}"));
    }
    if !check && out.is_none() {
        return Some(format!("secret render needs --out <file> (or --check to write nothing): {SECRET_USAGE}"));
    }
    None
}

fn secret_validate(p: &Parsed, _: Getenv) -> Option<String> {
    secret_usage_error(&p.args, p.val("out"), p.flag("check"))
}

fn secret_run(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let mut m = Map::new();
    // Kotlin passes `parsed.project`: the typed flag only.
    let project = p.val("project").map(str::to_string);
    let cross = p.flag("cross-project");
    if p.args[0] == "find" {
        m.insert("q".into(), s(&p.args[1]));
        if let Some(pr) = &project {
            m.insert("project".into(), s(pr));
        }
        if cross {
            m.insert("allowCrossProject".into(), Value::Bool(true));
        }
        let r = plugin_tools::call(ctx, "infantry_secret_find", m)?;
        return Ok(plugin_tools::print(ctx, &r));
    }
    m.insert("template".into(), s(&absolute(&ctx.cwd, &p.args[1])));
    if let Some(o) = p.val("out") {
        m.insert("out".into(), s(&absolute(&ctx.cwd, o)));
    }
    if let Some(pr) = &project {
        m.insert("project".into(), s(pr));
    }
    if cross {
        m.insert("allowCrossProject".into(), Value::Bool(true));
    }
    if p.flag("allow-missing") {
        m.insert("allowMissing".into(), Value::Bool(true));
    }
    if p.flag("check") {
        m.insert("check".into(), Value::Bool(true));
    }
    let r = plugin_tools::call(ctx, "infantry_secret_render", m)?;
    print_run_tool(ctx, r)
}

pub static SECRET: Command = Command { name: "secret", needs: Needs::Stream, validate: secret_validate, run: secret_run };

// ---- run-cmd ----

const RUN_TOOLS: [(&str, &str); 6] = [
    ("list", "infantry_run_commands"),
    ("save", "infantry_run_command_save"),
    ("delete", "infantry_run_command_delete"),
    ("start", "infantry_run_start"),
    ("stop", "infantry_run_stop"),
    ("secrets", "infantry_run_secrets"),
];

pub const RUN_CMD_USAGE: &str = "infantry run-cmd list | save <id> <cwd|.> \"<command line>\" [--name N] | delete <id> | start <id> | stop <runId> [--force] | secrets [project|app|all] [--project P --cross-project]";

fn run_tool(sub: &str) -> Option<&'static str> {
    RUN_TOOLS.iter().find(|(k, _)| *k == sub).map(|(_, t)| *t)
}

pub fn run_cmd_usage_error(args: &[String]) -> Option<String> {
    let Some(sub) = args.first() else { return Some(format!("run-cmd needs a subcommand: {RUN_CMD_USAGE}")) };
    if run_tool(sub).is_none() {
        return Some(format!("unknown run-cmd subcommand '{sub}': {RUN_CMD_USAGE}"));
    }
    if sub == "secrets" {
        return (args.len() > 2).then(|| format!("run-cmd secrets takes at most 1 argument: {RUN_CMD_USAGE}"));
    }
    let wanted = match sub.as_str() {
        "list" => 1,
        "save" => 4,
        _ => 2,
    };
    (args.len() != wanted).then(|| format!("run-cmd {sub} takes {} argument(s): {RUN_CMD_USAGE}", wanted - 1))
}

fn run_cmd_validate(p: &Parsed, _: Getenv) -> Option<String> {
    run_cmd_usage_error(&p.args)
}

fn run_cmd_run(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let a = &p.args;
    let sub = a[0].as_str();
    let mut m = Map::new();
    match sub {
        "save" => {
            m.insert("id".into(), s(&a[1]));
            if a[2] != "." {
                m.insert("cwd".into(), s(&a[2]));
            }
            m.insert("command".into(), s(&a[3]));
            if let Some(n) = p.val("name") {
                m.insert("name".into(), s(n));
            }
        }
        "stop" => {
            m.insert("runId".into(), s(&a[1]));
            if p.flag("force") {
                m.insert("force".into(), Value::Bool(true));
            }
        }
        "delete" | "start" => {
            m.insert("id".into(), s(&a[1]));
        }
        "secrets" => {
            if let Some(sc) = a.get(1) {
                m.insert("scope".into(), s(sc));
            }
        }
        _ => {}
    }
    if sub != "list" && sub != "stop" {
        if let Some(pr) = p.val("project") {
            m.insert("project".into(), s(pr));
        }
        if p.flag("cross-project") {
            m.insert("allowCrossProject".into(), Value::Bool(true));
        }
    }
    let r = plugin_tools::call(ctx, run_tool(sub).unwrap_or_default(), m)?;
    print_run_tool(ctx, r)
}

pub static RUN_CMD: Command = Command { name: "run-cmd", needs: Needs::Stream, validate: run_cmd_validate, run: run_cmd_run };

#[cfg(test)]
mod tests {
    use super::*;

    fn v(a: &[&str]) -> Vec<String> {
        a.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn usage_errors_match_kotlin() {
        assert_eq!(script_usage_error(&v(&["hook", "add", "on", "s.lua"])), None);
        assert_eq!(script_usage_error(&v(&["hook"])).as_deref(), Some(SCRIPT_USAGE));
        assert_eq!(run_cmd_usage_error(&v(&["save", "x"])), Some(format!("run-cmd save takes 3 argument(s): {RUN_CMD_USAGE}")));
        assert_eq!(run_cmd_usage_error(&v(&["nope"])), Some(format!("unknown run-cmd subcommand 'nope': {RUN_CMD_USAGE}")));
        assert_eq!(
            secret_usage_error(&v(&["render", "t"]), None, false),
            Some(format!("secret render needs --out <file> (or --check to write nothing): {SECRET_USAGE}"))
        );
    }
}
