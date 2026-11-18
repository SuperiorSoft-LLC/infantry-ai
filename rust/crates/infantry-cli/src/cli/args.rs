// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The command line, parsed as the Kotlin `parseArgs` parses it: one flag table for every command, flags anywhere,
//! the same refusal texts (`error: <message>`, exit 2). Values are kept by flag name without the dashes
//! (`--desc-file` -> `desc-file`); a repeatable flag keeps every value.

use std::collections::{BTreeMap, BTreeSet};

pub const DEFAULT_HOST: &str = "127.0.0.1";
/// The Kotlin CLI's default port, kept for the "not running on host:port" message when nothing else names one.
pub const DEFAULT_PORT: i32 = 8787;
/// `TicketWeight.MIN` / `MAX`.
pub const WEIGHT_MIN: i32 = 1;
pub const WEIGHT_MAX: i32 = 5;
/// `TicketStatus.ALL`.
pub const TICKET_STATUSES: [&str; 5] = ["backlog", "todo", "in_progress", "pending", "done"];

#[derive(Clone, Copy, PartialEq)]
enum Kind {
    /// A value that must not start with `-` (`flagValue`).
    Value,
    /// Any next word, even one starting with `-` (`args.getOrNull`).
    Any,
    /// A value that must not start with `-`, repeatable.
    Multi,
    /// Any next word, repeatable.
    MultiAny,
    /// No value.
    Bool,
}

/// (flag, kind, the refusal when its value is missing).
const FLAGS: &[(&str, Kind, &str)] = &[
    ("--url", Kind::Value, "--url expects a value"),
    ("--endpoint", Kind::Value, "--endpoint expects a name"),
    ("--endpoints", Kind::Value, "--endpoints expects a file path"),
    ("--token-file", Kind::Value, "--token-file expects a file path"),
    ("--force", Kind::Bool, ""),
    ("--out", Kind::Value, "--out expects a file path"),
    ("--allow-missing", Kind::Bool, ""),
    ("--update", Kind::Bool, ""),
    ("--check", Kind::Bool, ""),
    ("--live", Kind::Bool, ""),
    ("--profiles", Kind::Bool, ""),
    ("--project", Kind::Value, "--project expects a project id"),
    ("--status", Kind::Value, "--status expects a column: backlog, todo, in_progress, pending, done"),
    ("--title", Kind::Value, "--title expects a value"),
    ("--purpose", Kind::Value, "--purpose expects chat or ticket"),
    ("--image", Kind::Multi, "--image expects an image file path"),
    ("--remove-image", Kind::Multi, "--remove-image expects an attached image (its path or file name)"),
    ("--description", Kind::Any, "--description expects a value"),
    ("--content", Kind::Any, "--content expects a value"),
    ("--name", Kind::Any, "--name expects a value"),
    ("--code", Kind::Value, "--code expects the one-time code `infantry runner pair` printed"),
    ("--fingerprint", Kind::Value, "--fingerprint expects the certificate fingerprint `infantry runner pair` printed"),
    ("--via", Kind::Any, "--via expects the command that runs a command on the runner machine, in quotes"),
    ("--allow-projects", Kind::Value, "--allow-projects expects comma-separated project ids (or * for every project)"),
    ("--allow-harnesses", Kind::Value, "--allow-harnesses expects comma-separated harness ids (or * for every harness)"),
    ("--grant-on-pair", Kind::Bool, ""),
    ("--grant-harnesses-max", Kind::Value, "--grant-harnesses-max expects comma-separated harness ids (the ceiling of the grant at pairing)"),
    ("--grant", Kind::Bool, ""),
    ("--rotate", Kind::Bool, ""),
    ("--link", Kind::Any, "--link expects a runner pairing link (infantry://runner?v=1...), or - to read it from stdin"),
    ("--harness", Kind::Value, "--harness expects a harness id, e.g. the one you mean to run there"),
    ("--report-to", Kind::Value, "--report-to expects a role id"),
    ("--report-fields", Kind::Any, "--report-fields expects a comma-separated list"),
    ("--set", Kind::Value, "--set expects comma-separated trait ids"),
    ("--clear", Kind::Bool, ""),
    ("--all", Kind::Bool, ""),
    ("--json", Kind::Bool, ""),
    ("--use-secret", Kind::Value, "--use-secret expects a shared secret name"),
    ("--supersedes", Kind::Value, "--supersedes expects a decision ref (D1 or 1)"),
    ("--summary", Kind::Any, "--summary expects a one-line summary"),
    ("--location", Kind::Value, "--location expects a path"),
    ("--ticket", Kind::Value, "--ticket expects a ticket id"),
    ("--outcome", Kind::Value, "--outcome expects worked, failed or partial"),
    ("--find", Kind::Any, "--find expects the passage to replace"),
    ("--folder", Kind::Value, "--folder expects a folder"),
    ("--dir", Kind::Value, "--dir expects the folder that holds the infantry launcher"),
    ("--say", Kind::Value, "--say expects the text to speak"),
    ("--level", Kind::Value, "--level expects info, needs-owner or urgent"),
    ("--sound", Kind::Value, "--sound expects a sound name (or none)"),
    ("--target", Kind::Value, "--target expects all, desktop, android or web"),
    ("--on-done", Kind::Value, "--on-done expects a ticket id"),
    ("--off", Kind::Bool, ""),
    ("--scope", Kind::Value, "--scope expects project, role or session"),
    ("--file", Kind::Value, "--file expects a file path"),
    ("--exclusive", Kind::Bool, ""),
    ("--append", Kind::Bool, ""),
    ("--edit", Kind::Bool, ""),
    ("--role", Kind::Value, "--role expects a role id (see: infantry role list)"),
    ("--provider", Kind::Value, "--provider expects a harness id (claude, opencode, pi, ...)"),
    ("--model", Kind::Value, "--model expects a model id the provider lists"),
    ("--effort", Kind::Value, "--effort expects a level (low, medium, high, xhigh, max) or off"),
    ("--runner", Kind::Value, "--runner expects local, any, none or a runner name"),
    ("--mcp", Kind::Multi, "--mcp expects <server>=<purpose> or none"),
    ("--context-limit", Kind::Value, "--context-limit expects a token count (200000, 200k, 1m) or off"),
    ("--question", Kind::Any, "--question expects the question text"),
    ("--option", Kind::MultiAny, "--option expects \"label|description\""),
    ("--multi", Kind::Bool, ""),
    ("--no-other", Kind::Bool, ""),
    ("--other", Kind::Bool, ""),
    ("--recommend", Kind::Any, "--recommend expects the label of the recommended option"),
    ("--test", Kind::Bool, ""),
    ("--reason", Kind::Any, "--reason expects why the recommended option is recommended"),
    ("--default-after", Kind::Any, "--default-after expects whole minutes"),
    ("--timeout", Kind::Any, "--timeout expects whole seconds"),
    ("--before", Kind::Value, "--before expects a ticket id or prefix (see: infantry tickets)"),
    ("--path", Kind::Multi, "--path expects a path or glob, e.g. --path '**/TicketBoard.kt'"),
    ("--related", Kind::Bool, ""),
    ("--related-to", Kind::Multi, "--related-to expects a ticket id or prefix (see: infantry tickets)"),
    ("--suggest", Kind::Bool, ""),
    ("--backfill", Kind::Bool, ""),
    ("--columns", Kind::Value, "--columns expects columns, e.g. in_progress,pending"),
    ("--offset", Kind::Value, "--offset expects a whole number"),
    ("--previews", Kind::Bool, ""),
    ("--filter", Kind::Value, "--filter expects <lane>[:<days>]"),
    ("--now", Kind::Bool, ""),
    ("--queue", Kind::Bool, ""),
    ("--note", Kind::Value, "--note expects the note text"),
    ("--dry-run", Kind::Bool, ""),
    ("--limit", Kind::Value, "--limit expects a whole number of lines"),
    ("--top", Kind::Bool, ""),
    ("--after", Kind::Value, "--after expects a ticket id or prefix (see: infantry tickets)"),
    ("--position", Kind::Value, "--position expects a whole number, 0 = the top of the column"),
    ("--bottom", Kind::Bool, ""),
    ("--epic", Kind::Value, "--epic expects an epic id or prefix (see: infantry epics --project <id>)"),
    ("--reply-to", Kind::Value, "--reply-to expects a message id (see: infantry send)"),
    ("--unread", Kind::Bool, ""),
    ("--cross-project", Kind::Bool, ""),
    ("--allow-kill-any", Kind::Bool, ""),
];

/// A parsed command line (`ParsedArgs`).
#[derive(Clone, Debug, Default)]
pub struct Parsed {
    pub command: Option<String>,
    pub args: Vec<String>,
    pub host: Option<String>,
    pub port: Option<i32>,
    pub help: bool,
    pub version: bool,
    /// `-` alone: read the text from stdin.
    pub stdin: bool,
    /// `--desc` / `--desc -` / `--desc-file`: the description, once resolved.
    pub description: Option<String>,
    pub desc_file: Option<String>,
    pub desc_stdin: bool,
    /// `--body` / `--payload` (any value; `-` = stdin, read by the command).
    pub body: Option<String>,
    /// RUNNER-LINK-1: `runner pair|up|connect|bearer --host` (comma-joined) and `--port`.
    pub runner_hosts: Option<String>,
    pub runner_port: Option<String>,
    pub bearer: Option<String>,
    pub weight: Option<i32>,
    pub queue_at: Option<i32>,
    pub mcp_depth: Option<i32>,
    pub max_depth: Option<i32>,
    pub max_spawns: Option<i32>,
    values: BTreeMap<String, Vec<String>>,
    flags: BTreeSet<String>,
}

impl Parsed {
    /// The (last) value of `--<name>`.
    pub fn val(&self, name: &str) -> Option<&str> {
        self.values.get(name).and_then(|v| v.last()).map(String::as_str)
    }

    /// Every value of a repeatable `--<name>`.
    pub fn vals(&self, name: &str) -> Vec<String> {
        self.values.get(name).cloned().unwrap_or_default()
    }

    /// True when the boolean `--<name>` was given.
    pub fn flag(&self, name: &str) -> bool {
        self.flags.contains(name)
    }

    /// `--host` or `--port` was typed (BUG-G2d: it beats an inherited `INFANTRY_URL`).
    pub fn address_given(&self) -> bool {
        self.host.is_some() || self.port.is_some()
    }

    pub fn arg(&self, i: usize) -> Option<&str> {
        self.args.get(i).map(String::as_str)
    }

    /// The project: `--project`, else `INFANTRY_PROJECT_ID` (`projectIdOf`).
    pub fn project(&self, getenv: &dyn Fn(&str) -> Option<String>) -> Option<String> {
        self.val("project")
            .map(str::to_string)
            .or_else(|| getenv("INFANTRY_PROJECT_ID"))
            .map(|p| p.trim().to_string())
            .filter(|p| !p.is_empty())
    }

    #[cfg(test)]
    pub fn set_val(&mut self, name: &str, v: &str) {
        self.values.entry(name.into()).or_default().push(v.into());
    }
}

fn flag_value(args: &[String], i: usize) -> Option<String> {
    args.get(i).filter(|v| !v.starts_with('-')).cloned()
}

fn count(args: &[String], i: usize, flag: &str) -> Result<i32, String> {
    flag_value(args, i).and_then(|v| v.parse::<i32>().ok()).filter(|n| *n >= 0).ok_or_else(|| format!("{flag} expects a whole number >= 0"))
}

/// `parseArgs`. Err = the refusal (printed as `error: <message>`, exit 2).
pub fn parse(args: &[String]) -> Result<Parsed, String> {
    let mut p = Parsed::default();
    let mut rest: Vec<String> = Vec::new();
    let mut desc_sources = 0;
    let mut i = 0;
    let runner_link_words =
        |rest: &Vec<String>| rest.first().map(String::as_str) == Some("runner") && matches!(rest.get(1).map(String::as_str), Some("pair" | "up" | "connect" | "bearer"));
    while i < args.len() {
        let arg = args[i].as_str();
        match arg {
            "--host" => {
                let v = args.get(i + 1).filter(|v| !v.starts_with('-')).ok_or("--host expects a value")?.clone();
                if runner_link_words(&rest) {
                    p.runner_hosts = Some(match p.runner_hosts.take() {
                        Some(h) => format!("{h},{v}"),
                        None => v,
                    });
                } else {
                    p.host = Some(v);
                }
                i += 2;
            }
            "--port" => {
                if runner_link_words(&rest) {
                    p.runner_port = Some(
                        flag_value(args, i + 1).ok_or("--port expects a port number (the port the runner pairing link advertises)")?,
                    );
                } else {
                    p.port = Some(args.get(i + 1).and_then(|v| v.parse::<i32>().ok()).ok_or("--port expects a number")?);
                }
                i += 2;
            }
            "--self" => {
                rest.push("self".into());
                i += 1;
            }
            "-" => {
                p.stdin = true;
                i += 1;
            }
            "--desc" => {
                let v = args.get(i + 1).ok_or("--desc expects a value (or - for stdin)")?;
                if v == "-" {
                    p.desc_stdin = true;
                } else {
                    p.description = Some(v.clone());
                }
                desc_sources += 1;
                i += 2;
            }
            "--desc-file" => {
                p.desc_file = Some(args.get(i + 1).filter(|v| !v.trim().is_empty()).ok_or("--desc-file expects a file path")?.clone());
                desc_sources += 1;
                i += 2;
            }
            "--body" | "--payload" => {
                p.body = Some(args.get(i + 1).ok_or_else(|| format!("{arg} expects a value (or - for stdin)"))?.clone());
                i += 2;
            }
            "--bearer" => {
                p.bearer = Some(
                    args.get(i + 1)
                        .filter(|v| *v == "-" || !v.starts_with('-'))
                        .ok_or("--bearer expects the bearer the runner printed (irb1....), or - to read it from stdin")?
                        .clone(),
                );
                i += 2;
            }
            "--weight" => {
                p.weight = Some(
                    flag_value(args, i + 1)
                        .and_then(|v| v.parse::<i32>().ok())
                        .filter(|w| (0..=WEIGHT_MAX).contains(w))
                        .ok_or_else(|| format!("--weight expects {WEIGHT_MIN}..{WEIGHT_MAX} (0 clears it)"))?,
                );
                i += 2;
            }
            "--at" => {
                p.queue_at = Some(flag_value(args, i + 1).and_then(|v| v.parse().ok()).ok_or("--at expects a place number")?);
                i += 2;
            }
            "--mcp-depth" => {
                p.mcp_depth = Some(count(args, i + 1, arg)?);
                i += 2;
            }
            "--max-depth" => {
                p.max_depth = Some(count(args, i + 1, arg)?);
                i += 2;
            }
            "--max-spawns" => {
                p.max_spawns = Some(count(args, i + 1, arg)?);
                i += 2;
            }
            "--help" | "-h" => {
                p.help = true;
                i += 1;
            }
            "--version" => {
                p.version = true;
                i += 1;
            }
            _ => {
                if let Some((flag, kind, msg)) = FLAGS.iter().find(|(f, _, _)| *f == arg) {
                    let key = flag.trim_start_matches('-').to_string();
                    match kind {
                        Kind::Bool => {
                            p.flags.insert(key);
                            i += 1;
                        }
                        Kind::Value | Kind::Multi => {
                            let v = flag_value(args, i + 1).ok_or_else(|| msg.to_string())?;
                            let e = p.values.entry(key).or_default();
                            if *kind == Kind::Value {
                                e.clear();
                            }
                            e.push(v);
                            i += 2;
                        }
                        Kind::Any | Kind::MultiAny => {
                            let v = args.get(i + 1).ok_or_else(|| msg.to_string())?.clone();
                            let e = p.values.entry(key).or_default();
                            if *kind == Kind::Any {
                                e.clear();
                            }
                            e.push(v);
                            i += 2;
                        }
                    }
                } else if arg.starts_with('-') {
                    return Err(format!("unknown flag '{arg}'"));
                } else {
                    rest.push(arg.to_string());
                    i += 1;
                }
            }
        }
    }
    if desc_sources > 1 {
        return Err("give the description once: --desc <text>, --desc - (stdin) or --desc-file <path>".into());
    }
    p.command = rest.first().cloned();
    p.args = rest.into_iter().skip(1).collect();
    Ok(p)
}

/// `resolveDescription`: `--desc -` reads stdin, `--desc-file` reads the file, before any command runs.
pub fn resolve_description(mut p: Parsed, stdin: &mut dyn FnMut() -> String) -> Result<Parsed, String> {
    if p.desc_stdin {
        p.description = Some(stdin());
        p.desc_stdin = false;
    } else if let Some(f) = p.desc_file.take() {
        match std::fs::read(&f) {
            Ok(b) => p.description = Some(String::from_utf8_lossy(&b).to_string()),
            Err(e) => {
                let why = if e.kind() == std::io::ErrorKind::NotFound {
                    format!("{f} (The system cannot find the file specified)")
                } else {
                    e.to_string()
                };
                return Err(format!("cannot read --desc-file {f}: {why}"));
            }
        }
    }
    Ok(p)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(s: &[&str]) -> Vec<String> {
        s.iter().map(|x| x.to_string()).collect()
    }

    #[test]
    fn flags_anywhere() {
        let p = parse(&v(&["--project", "p1", "ticket", "create", "--title", "T", "--image", "a.png", "--image", "b.png", "--force"])).unwrap();
        assert_eq!(p.command.as_deref(), Some("ticket"));
        assert_eq!(p.args, ["create"]);
        assert_eq!(p.val("project"), Some("p1"));
        assert_eq!(p.vals("image"), ["a.png", "b.png"]);
        assert!(p.flag("force"));
    }

    #[test]
    fn refusals_like_kotlin() {
        assert_eq!(parse(&v(&["board", "--project"])).unwrap_err(), "--project expects a project id");
        assert_eq!(parse(&v(&["board", "--project", "--force"])).unwrap_err(), "--project expects a project id");
        assert_eq!(parse(&v(&["board", "--zzz"])).unwrap_err(), "unknown flag '--zzz'");
        assert_eq!(parse(&v(&["x", "--weight", "9"])).unwrap_err(), "--weight expects 1..5 (0 clears it)");
        assert!(parse(&v(&["x", "--desc", "a", "--desc-file", "f"])).unwrap_err().starts_with("give the description once"));
    }

    #[test]
    fn runner_link_host() {
        let p = parse(&v(&["runner", "pair", "--host", "a", "--host", "b", "--port", "9"])).unwrap();
        assert_eq!(p.runner_hosts.as_deref(), Some("a,b"));
        assert_eq!(p.runner_port.as_deref(), Some("9"));
        assert!(!p.address_given());
    }
}
