// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The native `infantry` CLI (RUST-CLI-1): every command but `mcp serve` (which `main.rs` keeps), with the Kotlin CLI's
//! commands, flags, output text and exit codes (0 success, 1 runtime failure, 2 usage error). Scripts and agents parse
//! this output, so the text is the contract. A command not ported yet goes to the JVM launcher when one is found.
//!
//! Connection: as the Kotlin `resolveTarget`, but the default state dir is the Rust app's
//! (`INFANTRY_STATE_DIR`, else `%LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust`) and the default port its `daemon.port`,
//! else 18990.

#![allow(dead_code)]
pub mod args;
pub mod cmd_mail;
pub mod cmd_sessions;
pub mod cmd_runner;
pub mod cmd_board;
mod cmd_harness;
pub mod cmd_ticket;
pub mod plugin_tools;
pub mod tools_cmds;
pub mod usage_settings;
pub mod cmd_worktrees;
pub mod cmd_project;
pub mod health;
pub mod local_cmds;
pub mod progress;
pub mod issue;
pub mod conn;
pub mod registry;
pub mod usage;

use args::Parsed;
use conn::{ConnError, Endpoint, FrameStream};
use std::path::PathBuf;

/// The Rust daemon's default port and state dir (infantry-daemon `main.rs`).
pub const RUST_DEFAULT_PORT: i32 = 18990;
pub const RUST_STATE_SUBDIR: [&str; 2] = ["SuperiorSoft", "InfantryAI-Rust"];
/// `VERSION_PROBE_MS`.
const VERSION_PROBE_MS: u64 = 2_000;
/// The CLI's contract (`EventVocabulary.VERSION`), read from the Kotlin source at build time.
pub const CONTRACT: &str = env!("INFANTRY_CONTRACT_VERSION");
/// `Message.from` of a message typed at a terminal (no session named).
pub const HUMAN_SENDER: &str = "user";

/// The getter every command reads the environment through (`InfantryEnv.get`: `INFANTRY_X`, else `AGENTHQ_X`).
pub type Getenv<'a> = &'a dyn Fn(&str) -> Option<String>;

/// Where output goes: the process's streams, or buffers in tests.
pub trait Io {
    fn out(&mut self, line: &str);
    fn err(&mut self, line: &str);
    /// All of stdin, as text.
    fn stdin(&mut self) -> String;
}

/// UTF-8 (or US-ASCII with `INFANTRY_CLI_ASCII=1`, each non-ASCII char `?`), `\n` line ends, flushed per line.
pub struct StdIo {
    pub ascii: bool,
}

impl StdIo {
    fn bytes(&self, line: &str) -> Vec<u8> {
        let mut b: Vec<u8> = if self.ascii { line.chars().map(|c| if c.is_ascii() { c as u8 } else { b'?' }).collect() } else { line.as_bytes().to_vec() };
        b.push(b'\n');
        b
    }
}

impl Io for StdIo {
    fn out(&mut self, line: &str) {
        use std::io::Write;
        let mut o = std::io::stdout().lock();
        let _ = o.write_all(&self.bytes(line));
        let _ = o.flush();
    }
    fn err(&mut self, line: &str) {
        use std::io::Write;
        let mut e = std::io::stderr().lock();
        let _ = e.write_all(&self.bytes(line));
        let _ = e.flush();
    }
    fn stdin(&mut self) -> String {
        use std::io::Read;
        let mut s = String::new();
        let _ = std::io::stdin().read_to_string(&mut s);
        s
    }
}

/// Buffers, for golden-output tests.
#[derive(Default)]
pub struct BufIo {
    pub out: Vec<String>,
    pub err: Vec<String>,
    pub input: String,
}

impl Io for BufIo {
    fn out(&mut self, line: &str) {
        self.out.push(line.to_string());
    }
    fn err(&mut self, line: &str) {
        self.err.push(line.to_string());
    }
    fn stdin(&mut self) -> String {
        std::mem::take(&mut self.input)
    }
}

/// What a command runs with.
pub struct Ctx<'a> {
    pub io: &'a mut dyn Io,
    pub getenv: Getenv<'a>,
    /// Where the daemon is (None for a local command).
    pub endpoint: Option<Endpoint>,
    /// The open connection (only for [Needs::Stream] / [Needs::OwnerStream] commands).
    pub stream: Option<FrameStream>,
    pub cwd: PathBuf,
}

impl Ctx<'_> {
    pub fn out(&mut self, line: impl AsRef<str>) {
        // A multi-line string is written as Kotlin's appendLine writes it: one line end at the end.
        self.io.out(line.as_ref());
    }
    pub fn err(&mut self, line: impl AsRef<str>) {
        self.io.err(line.as_ref());
    }
    /// The open stream; a command declared [Needs::Stream] always has one.
    pub fn stream(&mut self) -> &mut FrameStream {
        self.stream.as_mut().expect("a stream command runs with an open stream")
    }
    pub fn env(&self, name: &str) -> Option<String> {
        (self.getenv)(name)
    }
    /// `INFANTRY_SESSION_ID`, trimmed, or None.
    pub fn session_id(&self) -> Option<String> {
        self.env("INFANTRY_SESSION_ID").map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
    }
    /// The state dir of the daemon this CLI talks to (the Rust app's by default).
    pub fn state_dir(&self) -> PathBuf {
        state_dir(self.getenv)
    }
}

/// A command's result: Ok(exit code), or Err(message) printed as `error: <message>` with exit 1.
pub type CmdResult = Result<i32, String>;

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Needs {
    /// No daemon at all (`install-cli`, `remote`).
    Local,
    /// The resolved endpoint, but the command opens (or not) its own connection (`health`, `version`).
    Target,
    /// An open connection.
    Stream,
    /// An open connection presenting this machine's owner token (`runner`, `settings`, `harness`).
    OwnerStream,
}

pub type Validate = fn(&Parsed, Getenv) -> Option<String>;
pub type Run = fn(&mut Ctx, &Parsed) -> CmdResult;

/// One command of the native CLI.
pub struct Command {
    pub name: &'static str,
    pub needs: Needs,
    /// The usage error, checked before connecting (exit 2), or None.
    pub validate: Validate,
    pub run: Run,
}

/// A command that bypasses the global flag parser (`bg`, `run`, `progress`, `waiting on|off|show`): gets the raw words.
pub struct RawCommand {
    /// True when this raw command takes [args] (args[0] is the command word).
    pub matches: fn(&[String]) -> bool,
    pub run: fn(&[String], &mut dyn Io, Getenv) -> i32,
}

/// `COMMANDS`: every command the Kotlin CLI knows (an unknown one is a usage error before connecting).
pub const COMMANDS: &[&str] = &[
    "submit", "watch", "pods", "runs", "logs", "cancel", "tickets", "board", "ticket", "agents", "send", "inbox", "read", "threads",
    "thread", "waiting", "epics", "stories", "epic", "project", "mcp", "knowledge", "memory", "decision", "pipeline", "notify", "issue",
    "trait", "skill", "role", "ask", "harnesses", "harness-status", "harness", "archive", "harness-install", "image", "session", "remote",
    "bg", "kill", "usage", "processes", "health", "settings", "agent", "agent-def", "run-cmd", "secret", "event", "tests", "card", "docs",
    "account", "tool", "script", "worktrees", "runner", "install-cli", "uninstall-cli", "login", "logout", "whoami",
];

pub fn no_validation(_: &Parsed, _: Getenv) -> Option<String> {
    None
}

/// The user's home.
pub fn home() -> PathBuf {
    crate::target::home()
}

/// The Rust app's default state dir: `%LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust`, else `~/.infantry-rust`.
pub fn rust_default_state_dir(getenv: Getenv) -> PathBuf {
    if let Some(l) = getenv("LOCALAPPDATA").filter(|v| !v.trim().is_empty()) {
        return RUST_STATE_SUBDIR.iter().fold(PathBuf::from(l), |p, s| p.join(s));
    }
    home().join(".infantry-rust")
}

/// The state dir this CLI reads: `INFANTRY_STATE_DIR` (or `AGENTHQ_STATE_DIR`), else the Rust app's.
pub fn state_dir(getenv: Getenv) -> PathBuf {
    match getenv("INFANTRY_STATE_DIR").filter(|v| !v.trim().is_empty()) {
        Some(d) => PathBuf::from(d.trim()),
        None => rust_default_state_dir(getenv),
    }
}

/// The environment as the target resolution sees it: `INFANTRY_STATE_DIR` defaults to the Rust state dir, and when nothing
/// names a daemon (`INFANTRY_URL`, `INFANTRY_PORT`, an explicit `INFANTRY_STATE_DIR`, a `daemon.port` in the default one)
/// `INFANTRY_PORT` defaults to 18990.
pub fn rust_env(getenv: Getenv, name: &str) -> Option<String> {
    let given = getenv(name).filter(|v| !v.trim().is_empty());
    match name {
        "INFANTRY_STATE_DIR" | "AGENTHQ_STATE_DIR" => given.or_else(|| Some(rust_default_state_dir(getenv).to_string_lossy().to_string())),
        "INFANTRY_PORT" | "AGENTHQ_PORT" => given.or_else(|| {
            // A state dir named explicitly never falls back to the default daemon (Kotlin's `envTargetUrl` refuses).
            if getenv("INFANTRY_STATE_DIR").is_some_and(|v| !v.trim().is_empty()) {
                return None;
            }
            let dir = state_dir(getenv);
            let recorded = dir.join("daemon.port").is_file() || dir.join("network.properties").is_file();
            let url = getenv("INFANTRY_URL").filter(|v| !v.trim().is_empty());
            if recorded || url.is_some() {
                None
            } else {
                Some(RUST_DEFAULT_PORT.to_string())
            }
        }),
        _ => given,
    }
}

/// `resolveTarget` over the Rust defaults. Err = a usage error.
pub fn resolve_endpoint(p: &Parsed, getenv: Getenv, owner: bool) -> Result<Endpoint, String> {
    let conn = crate::target::Connection {
        host: p.host.clone(),
        port: p.port,
        url: p.val("url").map(str::to_string),
        endpoint: p.val("endpoint").map(str::to_string),
        endpoints_file: p.val("endpoints").map(str::to_string),
        token_file: p.val("token-file").map(str::to_string),
    };
    let env = |n: &str| rust_env(getenv, n);
    let target = crate::target::resolve(&conn, &env)?;
    let owner_token = if owner { owner_token(getenv) } else { None };
    Ok(Endpoint { target, owner_token })
}

/// `OwnerCredential.stored`: `<stateDir>/owner.token`, trimmed, at least 32 characters.
pub fn owner_token(getenv: Getenv) -> Option<String> {
    let f = state_dir(getenv).join("owner.token");
    std::fs::read_to_string(f).ok().map(|t| t.trim().to_string()).filter(|t| t.len() >= 32)
}

fn warn_cleartext(ep: &Endpoint, io: &mut dyn Io) {
    let t = &ep.target;
    if t.token.is_none() || !t.url.starts_with("ws://") {
        return;
    }
    if let Some(host) = crate::target::url_host(&t.url) {
        if !crate::target::is_loopback_host(&host) {
            io.err(&format!(
                "warning: the token is sent in cleartext over ws:// to {host}; use wss:// through a TLS proxy or a tunnel (see README, \"Headless server\")"
            ));
        }
    }
}

/// `infantry <cli> build <b> (daemon: <contract> build <b>|not running)`.
pub fn version_line(cli_build: &str, ready: Option<&conn::Envelope>) -> String {
    let daemon = match ready {
        None => "not running".to_string(),
        Some(r) => {
            let contract = r.ready_field("contract").unwrap_or_default();
            let contract = contract.trim_start_matches("infantry/").to_string();
            match r.ready_field("build") {
                Some(b) => format!("{contract} build {b}"),
                None => contract,
            }
        }
    };
    format!("infantry {CONTRACT} build {cli_build} (daemon: {daemon})")
}

/// `BuildInfo.current`: `INFANTRY_APP_HOME/BUILD`, else this crate's version is not a build stamp, so "dev".
fn cli_build(getenv: Getenv) -> String {
    getenv("INFANTRY_APP_HOME")
        .map(PathBuf::from)
        .filter(|d| d.is_dir())
        .and_then(|d| std::fs::read_to_string(d.join("BUILD")).ok())
        .map(|s| s.split_whitespace().collect::<String>())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| "dev".into())
}

fn unreachable_line(ep: &Endpoint, p: &Parsed) -> String {
    let url = &ep.target.url;
    let host = crate::target::url_host(url).unwrap_or_else(|| p.host.clone().unwrap_or_else(|| args::DEFAULT_HOST.into()));
    let port = url
        .split_once("://")
        .and_then(|(_, r)| r.split(['/', '?', '#']).next())
        .and_then(|a| a.rsplit_once(':'))
        .and_then(|(_, p)| p.parse::<i32>().ok())
        .unwrap_or_else(|| p.port.unwrap_or(args::DEFAULT_PORT));
    format!("error: Infantry AI daemon not running on {host}:{port} — start it with `infantry daemon`")
}

/// Runs one command line. Returns the exit code. [delegate] runs a command this CLI does not have natively (None = none).
pub fn run(argv: &[String], io: &mut dyn Io, getenv: Getenv, delegate: Option<&dyn Fn(&[String]) -> Option<i32>>) -> i32 {
    if let Some(raw) = registry::RAW.iter().find(|r| (r.matches)(argv)) {
        return (raw.run)(argv, io, getenv);
    }
    let parsed = match args::parse(argv) {
        Ok(p) => p,
        Err(m) => {
            io.err(&format!("error: {m}"));
            return 2;
        }
    };
    let parsed = {
        let mut read = || io.stdin();
        match args::resolve_description(parsed, &mut read) {
            Ok(p) => p,
            Err(m) => {
                io.err(&format!("error: {m}"));
                return 2;
            }
        }
    };
    if matches!(parsed.command.as_deref(), Some("mail" | "mailbox")) {
        io.out(&usage::mail_usage());
        return 0;
    }
    if parsed.help {
        io.out(usage::USAGE);
        return 0;
    }
    if parsed.version || (parsed.command.as_deref() == Some("version") && parsed.args.is_empty()) {
        let ready = resolve_endpoint(&parsed, getenv, false).ok().and_then(|ep| conn::daemon_ready(&ep, VERSION_PROBE_MS));
        io.out(&version_line(&cli_build(getenv), ready.as_ref()));
        return 0;
    }
    let Some(command) = parsed.command.clone() else {
        io.err("error: no command given");
        io.err(usage::USAGE);
        return 2;
    };
    if !COMMANDS.contains(&command.as_str()) {
        io.err(&format!("error: unknown command '{command}'"));
        if command == "daemon" {
            io.err("hint: the CLI does not start a daemon (there is no `daemon` command and no --mode flag here). The daemon is com.superiorsoftllc.infantry.daemon.DaemonMainKt (`--mode both|runner|orchestrator`); for a TEST daemon use scripts/testdaemon.sh (docs/wiki/recipes/test-daemon-for-runner-repros.md)");
        }
        io.err("hint: if the daemon documents this command, this CLI may be older than the daemon; use the launcher the daemon exports as INFANTRY_BIN");
        io.err(usage::USAGE);
        return 2;
    }
    let Some(cmd) = registry::find(&command) else {
        if let Some(code) = delegate.and_then(|d| d(argv)) {
            return code;
        }
        io.err(&format!(
            "error: `{command}` is not in the native infantry CLI yet, and no JVM launcher was found for it; set INFANTRY_BIN to the folder of infantry.cmd"
        ));
        return 2;
    };
    if let Some(e) = (cmd.validate)(&parsed, getenv) {
        io.err(&format!("error: {e}"));
        if command == "send" {
            io.err(usage::USAGE);
        }
        return 2;
    }
    let cwd = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    if cmd.needs == Needs::Local {
        let mut ctx = Ctx { io, getenv, endpoint: None, stream: None, cwd };
        let r = (cmd.run)(&mut ctx, &parsed);
        return finish(&mut ctx, r);
    }
    let ep = match resolve_endpoint(&parsed, getenv, cmd.needs == Needs::OwnerStream) {
        Ok(e) => e,
        Err(m) => {
            io.err(&format!("error: {m}"));
            return 2;
        }
    };
    warn_cleartext(&ep, io);
    if cmd.needs == Needs::Target {
        let mut ctx = Ctx { io, getenv, endpoint: Some(ep), stream: None, cwd };
        let r = (cmd.run)(&mut ctx, &parsed);
        return finish(&mut ctx, r);
    }
    let stream = {
        let sink: Box<dyn Fn(&str)> = Box::new(|l: &str| eprintln!("{l}"));
        match FrameStream::open(&ep, sink) {
            Ok(s) => s,
            Err(ConnError::AuthRefused(m)) => {
                io.err(&format!("error: {m}"));
                return 1;
            }
            Err(_) => {
                io.err(&unreachable_line(&ep, &parsed));
                return 2;
            }
        }
    };
    let mut ctx = Ctx { io, getenv, endpoint: Some(ep), stream: Some(stream), cwd };
    let result = (cmd.run)(&mut ctx, &parsed);
    let code = finish(&mut ctx, result);
    if let Some(s) = ctx.stream.take() {
        s.close();
    }
    code
}

fn finish(ctx: &mut Ctx, r: CmdResult) -> i32 {
    match r {
        Ok(c) => c,
        Err(m) => {
            ctx.err(format!("error: {m}"));
            1
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn run_buf(a: &[&str], env: &dyn Fn(&str) -> Option<String>) -> (i32, BufIo) {
        let mut io = BufIo::default();
        let argv: Vec<String> = a.iter().map(|s| s.to_string()).collect();
        let code = run(&argv, &mut io, env, None);
        (code, io)
    }

    #[test]
    fn unknown_command_is_usage_error() {
        let (code, io) = run_buf(&["frobnicate"], &|_| None);
        assert_eq!(code, 2);
        assert_eq!(io.err[0], "error: unknown command 'frobnicate'");
    }

    #[test]
    fn help_and_mail_help() {
        let (code, io) = run_buf(&["--help"], &|_| None);
        assert_eq!(code, 0);
        assert!(io.out[0].starts_with("Infantry AI CLI - control the Infantry AI daemon"));
        let (_, io) = run_buf(&["mail"], &|_| None);
        assert!(io.out[0].starts_with("Mail:"));
    }

    #[test]
    fn version_when_down() {
        let tmp = std::env::temp_dir().join(format!("infantry-cli-ver-{}", std::process::id()));
        std::fs::create_dir_all(&tmp).unwrap();
        let dir = tmp.to_string_lossy().to_string();
        // Port 1 on loopback: nothing listens there.
        let env = move |n: &str| match n {
            "INFANTRY_STATE_DIR" => Some(dir.clone()),
            "INFANTRY_PORT" => Some("1".into()),
            _ => None,
        };
        let (code, io) = run_buf(&["--version"], &env);
        assert_eq!(code, 0);
        assert_eq!(io.out[0], format!("infantry {CONTRACT} build dev (daemon: not running)"));
        let _ = std::fs::remove_dir_all(&tmp);
    }

    #[test]
    fn rust_defaults() {
        let env = |n: &str| (n == "LOCALAPPDATA").then(|| "C:\\L".to_string());
        assert_eq!(state_dir(&env), PathBuf::from("C:\\L").join("SuperiorSoft").join("InfantryAI-Rust"));
        assert_eq!(rust_env(&env, "INFANTRY_PORT").as_deref(), Some("18990"));
        let ep = resolve_endpoint(&Parsed::default(), &env, false).unwrap();
        assert_eq!(ep.target.url, "ws://127.0.0.1:18990/ws");
    }
}
