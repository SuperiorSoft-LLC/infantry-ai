// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The generic harness contract (layer L3). A [`Harness`] describes how one agent CLI is run; it never starts a process.
//!
//! - [`Harness::launch`] turns a [`LaunchRequest`] into a [`LaunchPlan`]: the [`ProcessSpec`] to run and the
//!   [`Translator`] that reads it.
//! - A [`Translator`] is fed what the process does ([`TranslatorInput`]: process output, hook calls, tailed file lines,
//!   screen snapshots) and returns neutral [`HarnessEvent`]s. It may queue [`Outbound`] traffic (keys for a PTY, RPC
//!   lines for stdin) that the driver delivers.
//! - [`HarnessDriver`] runs ANY harness on ANY [`Runner`].
//!
//! The five CLIs this shape is built for ([`TurnDelivery`] says how a turn reaches each):
//!
//! | CLI | Process | Turn delivery | Output |
//! |---|---|---|---|
//! | Claude (interactive) | one PTY for the session | keys typed into the PTY | screen + hook channel + transcript tail |
//! | Codex | one per turn | prompt on stdin, `resume <thread>` | stdout JSONL |
//! | OpenCode | `serve`, one for the session | HTTP request | SSE stream |
//! | Pi | one for the session | RPC JSON line on stdin | stdout JSON lines |
//! | Gemini | one per turn | prompt argument / stdin | stdout stream-json |
//!
//! Nothing here knows orchestration (boards, tickets): an agent runs with no orchestrator.

pub mod background;
pub mod driver;
pub mod shim;
pub mod stats;
pub mod writes;

pub use driver::{DriverError, HarnessDriver};
/// The runner contract (RUST-RUN-1), re-exported so a harness crate needs only this one.
pub use infantry_runner_api as runner;
pub use infantry_runner_api::{HttpRequest, ProcessSpec, Runner, Size, TailFrom, TailSpec};

use std::path::{Path, PathBuf};


/// What a session may do without asking. Each harness maps it onto its own flags.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ApprovalMode {
    /// Read only; every write or command refused.
    Deny,
    /// Ask the owner for each risky action (a harness that cannot ask falls back to [`ApprovalMode::Deny`]).
    Ask,
    /// Edits inside the workspace allowed; anything else asks.
    AllowEdits,
    /// Everything allowed (externally sandboxed).
    AllowAll,
}

/// How a turn reaches the CLI.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TurnDelivery {
    /// A new process per turn; the prompt is part of the [`LaunchRequest`]; resume via [`LaunchRequest::resume`].
    SpawnPerTurn,
    /// One process for the session; a turn is [`Translator::send_turn`] traffic to its stdin or PTY.
    Persistent,
}

/// What a harness needs from the host and what it can report.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Capabilities {
    pub delivery: TurnDelivery,
    /// Runs in a PTY and reads the screen ([`TranslatorInput::Screen`]).
    pub pty_screen: bool,
    /// Reads files the CLI writes ([`LaunchPlan::tail`], [`TranslatorInput::Tail`]).
    pub file_tail: bool,
    /// Receives the CLI's hook calls ([`TranslatorInput::Hook`]).
    pub hook_channel: bool,
    /// Talks HTTP to its own server process ([`Outbound::Http`]).
    pub http: bool,
    /// Can continue a conversation from a [`HarnessEvent::ProviderSession`] token.
    pub resume: bool,
    /// Reports token usage ([`HarnessEvent::Usage`]).
    pub usage: bool,
    /// Accepts [`LaunchRequest::model`].
    pub model_selection: bool,
    /// The approval modes it honours as asked (others are mapped to the nearest safer one).
    pub approval_modes: Vec<ApprovalMode>,
}

/// One model a harness offers.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ModelInfo {
    pub id: String,
    pub label: String,
}

/// Read-only access to the machine the CLI runs on (local or remote), so a harness can check sign-in and list models
/// without spawning anything.
pub trait Probe {
    fn env(&self, name: &str) -> Option<String>;
    fn home(&self) -> Option<PathBuf>;
    fn read_to_string(&self, path: &Path) -> Option<String>;
    fn exists(&self, path: &Path) -> bool;
    /// The entries of a directory; empty when it cannot be read (a probe without listing sees none).
    fn list_dir(&self, _path: &Path) -> Vec<PathBuf> {
        Vec::new()
    }
    /// The lines of a file, streamed when the probe can (session records run to tens of MB); None when it cannot be read.
    fn lines(&self, path: &Path) -> Option<Box<dyn Iterator<Item = String> + '_>> {
        let text = self.read_to_string(path)?;
        Some(Box::new(text.lines().map(String::from).collect::<Vec<_>>().into_iter()))
    }
}

/// [`Probe`] over this process's own environment and file system.
pub struct LocalProbe;

impl Probe for LocalProbe {
    fn env(&self, name: &str) -> Option<String> {
        std::env::var(name).ok().filter(|v| !v.is_empty())
    }
    fn home(&self) -> Option<PathBuf> {
        self.env("USERPROFILE").or_else(|| self.env("HOME")).map(PathBuf::from)
    }
    fn read_to_string(&self, path: &Path) -> Option<String> {
        std::fs::read_to_string(path).ok()
    }
    fn exists(&self, path: &Path) -> bool {
        path.exists()
    }
    fn list_dir(&self, path: &Path) -> Vec<PathBuf> {
        std::fs::read_dir(path).map(|r| r.flatten().map(|e| e.path()).collect()).unwrap_or_default()
    }
    fn lines(&self, path: &Path) -> Option<Box<dyn Iterator<Item = String> + '_>> {
        use std::io::BufRead;
        let f = std::fs::File::open(path).ok()?;
        Some(Box::new(std::io::BufReader::new(f).lines().map_while(Result::ok)))
    }
}

/// One launch of a harness.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LaunchRequest {
    /// The folder the CLI works in.
    pub cwd: PathBuf,
    pub model: Option<String>,
    pub effort: Option<String>,
    /// The context window (tokens) the CLI compacts at; None = its own default. A harness without one ignores it.
    pub context_limit: Option<u64>,
    pub approval: ApprovalMode,
    /// A [`HarnessEvent::ProviderSession`] token to continue; None = a new conversation.
    pub resume: Option<String>,
    /// The turn's prompt ([`TurnDelivery::SpawnPerTurn`]); None for a persistent launch.
    pub prompt: Option<String>,
    /// Extra environment for the child.
    pub env: Vec<(String, String)>,
    /// Environment names or prefixes (ending in `*`) removed from the inherited environment.
    pub env_remove: Vec<String>,
    /// The harness's [`Asset`]s the driver staged on the runner's host: (asset name, absolute path there). An asset that
    /// could not be staged is missing; the harness decides whether it can launch without it.
    pub staged: Vec<(String, String)>,
    /// The hook channel the driver opened on the runner ([`Capabilities::hook_channel`]); the harness wires its CLI's
    /// hooks to it.
    pub hooks: Option<HookTarget>,
    /// The MCP servers the session gets (Kotlin `HarnessHost.mcpServers`): the harness delivers [`McpPlan::delivered`]
    /// through its CLI's own config and turns [`McpPlan::deny`] off where the CLI loads the user's servers itself.
    pub mcp: McpPlan,
    /// The watched-runner launcher a harness hook rewrites shell commands to (Kotlin `HarnessHost.watchLauncher`);
    /// None = the watch is off for this session.
    pub watch_launcher: Option<String>,
}

/// The name of the daemon's own MCP server (`infantry mcp`), the infantry entry of every [`McpPlan`].
pub const INFANTRY_MCP_SERVER: &str = "infantry";

/// How an MCP server is reached. Secret values ([`McpTransport::Stdio::env`], [`McpTransport::Http::headers`]) are
/// never logged: `Debug` prints their keys only.
#[derive(Clone, PartialEq, Eq)]
pub enum McpTransport {
    /// A child process speaking MCP on stdin/stdout.
    Stdio { command: Vec<String>, env: Vec<(String, String)>, working_dir: Option<String> },
    /// A remote server over streamable HTTP.
    Http { url: String, headers: Vec<(String, String)> },
}

impl std::fmt::Debug for McpTransport {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let keys = |kv: &[(String, String)]| kv.iter().map(|(k, _)| k.clone()).collect::<Vec<_>>();
        match self {
            McpTransport::Stdio { command, env, working_dir } => {
                f.debug_struct("Stdio").field("command", command).field("env", &keys(env)).field("working_dir", working_dir).finish()
            }
            McpTransport::Http { url, headers } => f.debug_struct("Http").field("url", url).field("headers", &keys(headers)).finish(),
        }
    }
}

/// One MCP server, harness-neutral (Kotlin `McpServerSpec`).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct McpServerSpec {
    /// The key the agent sees: `mcp__<name>__*` / `<name>_*`.
    pub name: String,
    pub transport: McpTransport,
    /// Where it came from (shown to the owner): `infantry`, a plugin id, `user`.
    pub source: String,
    /// The command or paths exist only on this machine: never sent to a remote runner.
    pub local_only: bool,
}

/// The MCP servers one session gets: [`McpPlan::add`] the host delivers, [`McpPlan::deny`] the names turned off. A name
/// in both is denied.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct McpPlan {
    pub add: Vec<McpServerSpec>,
    pub deny: Vec<String>,
}

impl McpPlan {
    /// [`McpPlan::add`] without the denied names.
    pub fn delivered(&self) -> impl Iterator<Item = &McpServerSpec> {
        self.add.iter().filter(|s| !self.deny.contains(&s.name))
    }

    /// The delivered infantry entry, when it is a stdio server: (command, env).
    pub fn infantry_stdio(&self) -> Option<(&[String], &[(String, String)])> {
        self.delivered().find(|s| s.name == INFANTRY_MCP_SERVER).and_then(|s| match &s.transport {
            McpTransport::Stdio { command, env, .. } if !command.is_empty() => Some((command.as_slice(), env.as_slice())),
            _ => None,
        })
    }

    /// The delivered servers in the common `mcpServers` JSON shape (claude `--mcp-config`):
    /// `{"mcpServers": {name: {"command","args","env"} | {"type":"http","url","headers"}}}`.
    pub fn mcp_servers_json(&self) -> serde_json::Value {
        let mut servers = serde_json::Map::new();
        for s in self.delivered() {
            let obj = |kv: &[(String, String)]| kv.iter().map(|(k, v)| (k.clone(), serde_json::Value::String(v.clone()))).collect::<serde_json::Map<_, _>>();
            let v = match &s.transport {
                McpTransport::Stdio { command, env, .. } if !command.is_empty() => {
                    serde_json::json!({ "command": command[0], "args": &command[1..], "env": obj(env) })
                }
                McpTransport::Stdio { .. } => continue,
                McpTransport::Http { url, headers } => serde_json::json!({ "type": "http", "url": url, "headers": obj(headers) }),
            };
            servers.insert(s.name.clone(), v);
        }
        serde_json::json!({ "mcpServers": servers })
    }
}

/// Where a CLI's hooks report, on the runner's host.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HookTarget {
    /// The loopback URL a blocking hook POSTs its JSON to (answered through [`Outbound::HookReply`]).
    pub url: String,
    /// The file a command hook appends one event word per line to (fed as [`TranslatorInput::HookEvent`]).
    pub events_file: String,
}

impl LaunchRequest {
    pub fn new(cwd: PathBuf) -> Self {
        LaunchRequest {
            cwd,
            model: None,
            effort: None,
            context_limit: None,
            approval: ApprovalMode::Deny,
            resume: None,
            prompt: None,
            env: Vec::new(),
            env_remove: Vec::new(),
            staged: Vec::new(),
            hooks: None,
            mcp: McpPlan::default(),
            watch_launcher: None,
        }
    }

    /// The runner-host path of staged asset [name].
    pub fn staged_path(&self, name: &str) -> Option<&str> {
        self.staged.iter().find(|(n, _)| n == name).map(|(_, p)| p.as_str())
    }
}

/// A file a CLI loads by path (a plugin, an extension) that the harness ships; the driver stages it on the runner's host
/// ([`Runner::stage_file`]) before each launch and passes its path in [`LaunchRequest::staged`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Asset {
    /// A plain file name, unique per harness.
    pub name: String,
    pub contents: Vec<u8>,
}

/// Why a harness cannot launch.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LaunchError(pub String);

impl std::fmt::Display for LaunchError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

/// What to run and how to read it.
pub struct LaunchPlan {
    pub spec: ProcessSpec,
    /// Bytes written to stdin right after spawn, then stdin is closed (a prompt for a CLI that reads to EOF).
    /// None = stdin stays open for [`Outbound::Stdin`].
    pub stdin: Option<Vec<u8>>,
    pub translator: Box<dyn Translator>,
    /// Files on the runner's host the driver tails for the translator ([`Capabilities::file_tail`]); [`TailFrom::End`]
    /// skips what a resumed file already holds.
    pub tail: Vec<TailSpec>,
}

/// What a translator is fed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TranslatorInput {
    /// One stdout line of a piped process (line ending trimmed).
    Stdout(String),
    /// One stderr line of a piped process.
    Stderr(String),
    /// Raw terminal bytes of a PTY process.
    Pty(Vec<u8>),
    /// The process ended (always the last process input).
    Exited { code: i32 },
    /// A blocking hook call: answer it with [`Outbound::HookReply`] carrying the same [id].
    Hook { id: u64, body: String },
    /// One event word a command hook appended to [`HookTarget::events_file`].
    HookEvent(String),
    /// One new line in a tailed file.
    Tail { path: String, line: String },
    /// The PTY screen's visible rows, [now] ms after spawn.
    Screen { rows: Vec<String>, now: u64 },
    /// The whole answer to [`Outbound::Http`] [id] (`stream: false`). `status` None = the request failed; `body` is then why.
    HttpResponse { id: u64, status: Option<u16>, body: String },
    /// A streamed [`Outbound::Http`] [id] (`stream: true`) answered with this status; its body follows as
    /// [`TranslatorInput::HttpLine`]s.
    HttpOpened { id: u64, status: u16 },
    /// One body line of streamed request [id] (line ending trimmed; a server-sent events stream gives `data: ...` lines).
    HttpLine { id: u64, line: String },
    /// Streamed request [id] ended (the server closed it: `error` None) or failed (it never opened, or broke).
    HttpClosed { id: u64, error: Option<String> },
}

/// Traffic a translator asks the driver to deliver.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outbound {
    /// Bytes to the process's stdin or PTY.
    Stdin(Vec<u8>),
    /// A request to the harness's own server ([`Capabilities::http`]), made by the runner on its host
    /// ([`Runner::http`]). The answer comes back as [`TranslatorInput::HttpResponse`] [id], or, with [stream], as
    /// [`TranslatorInput::HttpOpened`], `HttpLine`s and `HttpClosed`. [id] is the translator's own, unique per process.
    Http { id: u64, request: HttpRequest, stream: bool },
    /// The response body (JSON) to the blocking hook call [id] ([`TranslatorInput::Hook`]).
    HookReply { id: u64, body: String },
}

/// Token usage of one turn.
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Usage {
    pub input_tokens: u64,
    pub cached_input_tokens: u64,
    pub output_tokens: u64,
    pub reasoning_tokens: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TurnEnd {
    Completed,
    Interrupted,
    Failed,
}

/// One question a CLI puts to the owner (a choice menu).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct HarnessQuestion {
    pub text: String,
    /// (label, description).
    pub options: Vec<(String, String)>,
    pub multi_select: bool,
    /// The owner may type an answer of their own.
    pub allow_other: bool,
    /// The label the CLI marked as its recommendation.
    pub recommended: Option<String>,
}

/// The owner's answer to one [`HarnessQuestion`]; `answered = false` = no answer (timeout, session end).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct HarnessAnswer {
    pub answered: bool,
    pub answers: Vec<String>,
    pub other_text: Option<String>,
}

/// What a harness reports, in neutral terms.
#[derive(Debug, Clone, PartialEq)]
pub enum HarnessEvent {
    /// The resume token (a later turn passes it as [`LaunchRequest::resume`]).
    ProviderSession(String),
    TurnStarted,
    /// One assistant message (whole).
    Text(String),
    /// A piece of an assistant message still being written.
    TextDelta(String),
    Reasoning(String),
    /// A tool call began; `input` is the tool's input as text (a command line, JSON).
    ToolStarted { id: String, name: String, input: String },
    ToolFinished { id: String, output: String, exit_code: Option<i32>, ok: bool },
    /// The CLI wants permission; the host answers with [`HarnessDriver::answer`] (same [id]).
    ApprovalRequested { id: String, tool: String, input: String },
    /// The CLI asks the owner one or more questions (a choice menu); the host answers every one, in order, with
    /// [`HarnessDriver::answer_questions`] (same [id]).
    QuestionAsked { id: String, questions: Vec<HarnessQuestion> },
    Usage(Usage),
    /// A non-fatal problem the CLI reported.
    Warning(String),
    /// A fatal problem of the turn (a [`HarnessEvent::TurnEnded`] with `Failed` follows).
    Error(String),
    /// One raw stdout line (for the transcript).
    Stdout(String),
    /// One stderr line.
    Stderr(String),
    TurnEnded { end: TurnEnd, reason: Option<String> },
    /// The process ended.
    Exited { code: Option<i32> },
    /// The state of the session's own MCP server in the CLI (`connected`, `failed`, `needs_auth`, `disabled`, ...) and
    /// of every server it reports, sent when it changes (Kotlin `SessionOutput.mcpState` / `mcpServers`).
    McpState { state: String, servers: Vec<(String, String)> },
    /// The CLI's notice that background task(s) ended (`status`: completed, failed, killed, stopped).
    TaskNotice { ids: Vec<String>, status: String, exit_code: Option<i32> },
    /// A background task started or ended, from the driver's [`background::BackgroundTracker`] (never from a translator).
    Background(background::BackgroundChange),
}

/// Reads one launched process.
pub trait Translator: Send {
    fn feed(&mut self, input: TranslatorInput) -> Vec<HarnessEvent>;
    /// Queues one turn for a persistent process ([`TurnDelivery::Persistent`]); delivered via [`Translator::take_outbound`].
    fn send_turn(&mut self, _prompt: &str) {}
    /// Asks the CLI to stop its turn. False = it has no way; the driver kills the process.
    fn interrupt(&mut self) -> bool {
        false
    }
    /// The host's answer to [`HarnessEvent::ApprovalRequested`] [id] (typically queued as an [`Outbound::HookReply`]).
    fn answer(&mut self, _id: &str, _allow: bool) {}
    /// The host's answers to [`HarnessEvent::QuestionAsked`] [id], one per question in order.
    fn answer_questions(&mut self, _id: &str, _answers: Vec<HarnessAnswer>) {}
    /// Types [text] into the running turn (the CLI takes it at its next step without stopping). False = it cannot now.
    fn inject(&mut self, _text: &str) -> bool {
        false
    }
    /// Called on every driver poll with the time (ms since the epoch): a translator that watches something by asking
    /// (a status endpoint) queues its request here, throttled by itself.
    fn on_timer(&mut self, _now_ms: u64) {}
    /// Reconnects the session's own MCP server inside the CLI. False = the CLI cannot.
    fn reconnect_mcp(&mut self) -> bool {
        false
    }
    /// Starts the CLI's own exit (keys, a command). False = it has none; the driver kills the process.
    fn close(&mut self) -> bool {
        false
    }
    /// Drains the traffic to deliver.
    fn take_outbound(&mut self) -> Vec<Outbound> {
        Vec::new()
    }
}

/// One agent CLI.
pub trait Harness: Send + Sync {
    /// The provider id on the wire (`codex`, `claude`, ...).
    fn id(&self) -> &str;
    fn capabilities(&self) -> Capabilities;
    /// The models it offers on the machine [probe] looks at; empty = only the CLI's default.
    fn models(&self, probe: &dyn Probe) -> Vec<ModelInfo>;
    /// Why the CLI cannot run there (not installed, not signed in); None = fine as far as files and env tell.
    fn sign_in_problem(&self, probe: &dyn Probe) -> Option<String>;
    /// RR-VIS: where the login this harness checks lives on that machine (a file path or an env var name); None = it does not say.
    fn login_location(&self, _probe: &dyn Probe) -> Option<String> {
        None
    }
    /// The files this launch needs on the runner's host (staged by the driver before [`Harness::launch`]).
    fn assets(&self, _req: &LaunchRequest) -> Vec<Asset> {
        Vec::new()
    }
    fn launch(&self, req: &LaunchRequest) -> Result<LaunchPlan, LaunchError>;
    /// The `/` commands a client offers while the user types (CP-4); none by default.
    fn commands(&self) -> Vec<HarnessCommand> {
        Vec::new()
    }
    /// One session read from the CLI's OWN local record (its transcript or store), in record order, for
    /// `session_stats`; None = this harness keeps no record the daemon can read, or there is no such session.
    /// Read only: never writes, never opens a credential file, never goes on the network.
    fn store_stats(&self, _probe: &dyn Probe, _provider_session_id: &str) -> Option<Vec<stats::StatEvent>> {
        None
    }
    /// The context window of `model` when a LOCAL catalog of this CLI states it (never guessed from a name).
    fn context_window(&self, _probe: &dyn Probe, _model: &str) -> Option<u64> {
        None
    }
    /// How the CLI is given its account (Kotlin module.json `credential` + `authModes` + `authProbe.loginArgv`): the API-key
    /// variable the app's guided setup and key screen fill, and the CLI's own sign-in command. None = it needs no key.
    fn credential(&self) -> Option<Credential> {
        None
    }
    /// What the Usage tab says about a CLI whose plan limits the daemon does not read (it has no meter card), in one or
    /// two plain sentences: why there is none and what is still counted. The `providers` row carries it as `usageNote`
    /// so the tab lists the CLI with this text instead of leaving it out. None = it has a meter, or says nothing.
    fn usage_note(&self) -> Option<String> {
        None
    }
}

/// The sign-in a harness declares ([`Harness::credential`]).
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Credential {
    /// The API-key environment variable the CLI reads (`ANTHROPIC_API_KEY`).
    pub env: String,
    /// The name the owner knows the tool by.
    pub label: String,
    /// Where the key comes from, in one or two plain sentences.
    pub help: String,
    /// The ways it connects: [`AUTH_API_KEY`] and/or [`AUTH_SUBSCRIPTION`]; empty = a key only.
    pub auth_modes: Vec<String>,
    /// The CLI's own sign-in, argv form (`claude auth login`); empty = no in-app sign-in.
    pub login_argv: Vec<String>,
    /// The env var the CLI reads a long-lived subscription token from (`CLAUDE_CODE_OAUTH_TOKEN`); empty = no token sign-in.
    pub token_env: String,
    /// The CLI's own command that opens the browser and prints that token (`claude setup-token`); empty = paste only.
    pub token_argv: Vec<String>,
    /// What the printed token starts with, so the app can take it from the output by itself (`sk-ant-oat`); empty = never.
    pub token_prefix: String,
    /// Address prefixes (scheme, host, path) of the page the login ends on, which carries the code in its `code=` query (and `state=`).
    /// The app watches its own login window and reads the code from such an address; empty = the owner pastes the code.
    pub login_code_urls: Vec<String>,
    /// Hosts (and their subdomains) the login may stay on in the app's own window. A navigation to any other host (Google, Apple, a
    /// company SSO) hands the login to the system browser; empty = never hands off.
    pub login_allowed_hosts: Vec<String>,
}

/// The auth mode that passes the stored API key to the CLI.
pub const AUTH_API_KEY: &str = "api-key";
/// The auth mode that uses the CLI's own subscription login (the API-key variable is blanked so it never bills that key).
pub const AUTH_SUBSCRIPTION: &str = "subscription-login";

/// What a `/` command does: the daemon knows these three, never a vendor.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CommandAction {
    SetModel,
    SetEffort,
    Native,
}

impl CommandAction {
    pub fn wire(self) -> &'static str {
        match self {
            CommandAction::SetModel => "SET_MODEL",
            CommandAction::SetEffort => "SET_EFFORT",
            CommandAction::Native => "NATIVE",
        }
    }
}

/// One `/` command: `name` a bare word, `argument` a hint, `values` the accepted (effort) or suggested (model) values,
/// `native` the text the harness gets for [`CommandAction::Native`] only.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HarnessCommand {
    pub name: String,
    pub description: String,
    pub action: CommandAction,
    pub argument: Option<String>,
    pub values: Vec<String>,
    pub native: Option<String>,
}

impl HarnessCommand {
    pub fn new(name: &str, description: &str, action: CommandAction) -> Self {
        HarnessCommand { name: name.into(), description: description.into(), action, argument: None, values: Vec::new(), native: None }
    }
    pub fn argument(mut self, a: &str) -> Self {
        self.argument = Some(a.into());
        self
    }
    pub fn values(mut self, v: &[&str]) -> Self {
        self.values = v.iter().map(|s| s.to_string()).collect();
        self
    }
    pub fn native(mut self, n: &str) -> Self {
        self.native = Some(n.into());
        self
    }
    /// The wire shape (Kotlin `HarnessCommand`).
    pub fn to_json(&self) -> serde_json::Value {
        serde_json::json!({
            "name": self.name, "description": self.description, "action": self.action.wire(),
            "argument": self.argument, "values": self.values, "native": self.native,
        })
    }
}

/// More values than this are never sent in one answer: a picker narrows them with the typed prefix.
pub const MAX_COMMAND_VALUES: usize = 50;

/// The catalog entries matching `typed` (the text after the slash; a leading `/` is tolerated). Without a space it is a
/// name prefix and the matches carry no values; with one it names ONE command and the rest narrows its values (a model
/// by any part of its id, others by prefix). Case is ignored.
pub fn complete_commands(catalog: &[HarnessCommand], typed: &str) -> Vec<HarnessCommand> {
    let body = typed.trim_start();
    let body = body.strip_prefix('/').unwrap_or(body);
    let Some(i) = body.find(char::is_whitespace) else {
        let p = body.to_lowercase();
        return catalog.iter().filter(|c| c.name.to_lowercase().starts_with(&p)).map(|c| HarnessCommand { values: Vec::new(), ..c.clone() }).collect();
    };
    let (name, args) = (&body[..i], body[i..].trim().to_lowercase());
    let Some(c) = catalog.iter().find(|c| c.name.eq_ignore_ascii_case(name)) else { return Vec::new() };
    let model = c.action == CommandAction::SetModel;
    let values = c.values.iter().filter(|v| if model { v.to_lowercase().contains(&args) } else { v.to_lowercase().starts_with(&args) }).take(MAX_COMMAND_VALUES).cloned().collect();
    vec![HarnessCommand { values, ..c.clone() }]
}

#[cfg(test)]
mod command_tests {
    use super::*;

    #[test]
    fn names_complete_without_values_and_a_named_command_narrows_its_values() {
        let cat = vec![
            HarnessCommand::new("model", "m", CommandAction::SetModel).values(&["claude-opus-5-5", "claude-sonnet-5-5"]),
            HarnessCommand::new("effort", "e", CommandAction::SetEffort).values(&["low", "medium", "max"]),
        ];
        let names = complete_commands(&cat, "/M");
        assert_eq!((names.len(), names[0].values.len()), (1, 0));
        assert_eq!(complete_commands(&cat, "model son")[0].values, vec!["claude-sonnet-5-5"]);
        assert_eq!(complete_commands(&cat, "effort m")[0].values, vec!["medium", "max"]);
        assert!(complete_commands(&cat, "nope x").is_empty());
        assert_eq!(cat[1].to_json()["action"], "SET_EFFORT");
    }
}
