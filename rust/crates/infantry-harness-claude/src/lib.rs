// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The Claude Code harness: the installed `claude` CLI behind the neutral [`Harness`] contract, on any runner.
//!
//! - **Interactive** (the default, [`Mode::Interactive`]): one `claude` per session in a PTY. The translator reads its
//!   screen (folder trust, dialogs, setup), its hooks (turn boundaries on the events file; permission and question calls
//!   on the hook channel) and its transcript (tailed; the output and the usage). See [`interactive`].
//! - **Headless** (the fallback, [`Mode::Headless`]): one `claude -p --output-format stream-json` per turn. See [`headless`].
//!
//! The CLI is never recreated: this crate only starts it, types into it and reads what it writes. WHAT it does is the
//! Kotlin claude harness (`ClaudeCommand.kt`, `ClaudeTtySession.kt`, `ClaudeSignIn.kt`).

pub mod headless;
pub mod hooks;
pub mod interactive;
pub mod screen;
pub mod stream;
pub mod transcript;

pub use interactive::Timings;

use infantry_harness::runner::hooks::event_command;
use infantry_harness::{
    ApprovalMode, Capabilities, Harness, LaunchError, LaunchPlan, LaunchRequest, LocalProbe, ModelInfo, Probe, ProcessSpec,
    Size, TailFrom, TailSpec, TurnDelivery,
};
use std::path::{Path, PathBuf};
use std::sync::Arc;

/// The provider id on the wire.
pub const ID: &str = "claude";
/// The model value that means "the CLI's own default" (never passed as `--model`).
pub const DEFAULT_MODEL: &str = "default";
pub const CONFIG_DIR_ENV: &str = "CLAUDE_CONFIG_DIR";
pub const OAUTH_TOKEN_ENV: &str = "CLAUDE_CODE_OAUTH_TOKEN";
/// The Anthropic API key claude reads (the app's "Use an Anthropic API key" choice).
pub const API_KEY_ENV: &str = "ANTHROPIC_API_KEY";
pub const CREDENTIALS_FILE: &str = ".credentials.json";
/// The env that sets the context window claude compacts at.
pub const CONTEXT_ENV: &str = "CLAUDE_CODE_AUTO_COMPACT_WINDOW";
/// The env claude reads for its todo tools (on in every session).
pub const TODO_ENV: &str = "CLAUDE_CODE_ENABLE_TODO_TOOLS";
/// Inherited env never passed on: a parent claude's markers, so the child does not think it is nested.
pub const ENV_REMOVE: [&str; 2] = ["CLAUDECODE", "CLAUDE_CODE_*"];
/// Seconds a blocking hook (permission, question) waits for its answer.
pub const HOOK_TIMEOUT_SEC: u64 = 600;
/// The terminal the interactive claude draws in.
pub const PTY_SIZE: Size = Size { rows: 50, cols: 200 };

pub const EFFORTS: [&str; 5] = ["low", "medium", "high", "xhigh", "max"];

/// The models the picker offers (claude takes an alias or a full id).
pub const MODELS: [(&str, &str); 10] = [
    ("opus", "Opus (latest)"),
    ("sonnet", "Sonnet (latest)"),
    ("haiku", "Haiku (latest)"),
    ("opus[1m]", "Opus, 1M context"),
    ("sonnet[1m]", "Sonnet, 1M context"),
    ("opusplan", "Opus plan, Sonnet build"),
    ("claude-opus-5-5", "Claude Opus 5.5"),
    ("claude-sonnet-5-5", "Claude Sonnet 5.5"),
    ("claude-haiku-5-5", "Claude Haiku 5.5"),
    ("claude-fable-5-1", "Claude Fable 5.1"),
];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    Interactive,
    Headless,
}

pub struct ClaudeHarness {
    pub program: String,
    pub mode: Mode,
    /// The machine the runner starts claude on (the transcript check of a resume).
    pub probe: Arc<dyn Probe + Send + Sync>,
    /// claude's config dir on that machine (transcripts live under it).
    pub config_dir: Option<PathBuf>,
    /// `--allowedTools` (empty = none passed).
    pub allowed_tools: Vec<String>,
    /// `--strict-mcp-config`: no MCP server the project's files name.
    pub strict_mcp: bool,
    pub hook_timeout_sec: u64,
    pub timings: Timings,
    pub pty: Size,
}

impl Default for ClaudeHarness {
    fn default() -> Self {
        Self::for_host(Arc::new(LocalProbe))
    }
}

impl ClaudeHarness {
    /// The harness for claude on the machine [probe] looks at.
    pub fn for_host(probe: Arc<dyn Probe + Send + Sync>) -> Self {
        let config_dir = config_dir(probe.as_ref());
        ClaudeHarness {
            program: "claude".into(),
            mode: Mode::Interactive,
            probe,
            config_dir,
            allowed_tools: Vec::new(),
            strict_mcp: true,
            hook_timeout_sec: HOOK_TIMEOUT_SEC,
            timings: Timings::default(),
            pty: PTY_SIZE,
        }
    }

    pub fn headless(mut self) -> Self {
        self.mode = Mode::Headless;
        self
    }

    /// Where claude writes the transcript of session [id] started in [cwd].
    pub fn transcript_path(&self, cwd: &Path, id: &str) -> Option<PathBuf> {
        let dir = self.config_dir.as_ref()?;
        Some(dir.join("projects").join(transcript::slug(&cwd.to_string_lossy())).join(format!("{id}.jsonl")))
    }

    /// The session id and whether claude is to resume it. G2: a resume id whose transcript is gone starts a fresh
    /// conversation under the same id (claude refuses `--resume` of an unknown id).
    fn session(&self, req: &LaunchRequest) -> (String, bool) {
        match req.resume.as_deref().map(str::trim).filter(|r| !r.is_empty()) {
            Some(id) => {
                let known = self.transcript_path(&req.cwd, id).is_none_or(|p| self.probe.exists(&p));
                (id.to_string(), known)
            }
            None => (uuid::Uuid::new_v4().to_string(), false),
        }
    }

    /// The argv after the program.
    pub fn args(&self, req: &LaunchRequest, session_id: &str, resume: bool) -> Vec<String> {
        let mut a: Vec<String> = Vec::new();
        if self.mode == Mode::Headless {
            a.extend(
                ["-p", "--output-format", "stream-json", "--verbose", "--thinking", "adaptive", "--thinking-display", "summarized"]
                    .map(String::from),
            );
        }
        if let Some(m) = model_arg(req.model.as_deref()) {
            a.extend(["--model".into(), m]);
        }
        if let Some(e) = req.effort.as_deref().map(str::trim).filter(|e| !e.is_empty()) {
            a.extend(["--effort".into(), e.to_string()]);
        }
        a.extend(["--permission-mode".into(), permission_mode(req.approval).into()]);
        if !self.allowed_tools.is_empty() {
            a.extend(["--allowedTools".into(), self.allowed_tools.join(",")]);
        }
        let ev = |e: &str| req.hooks.as_ref().map(|h| event_command(e, &h.events_file)).unwrap_or_default();
        let wiring = req.hooks.as_ref().map(|h| hooks::HookWiring {
            event_command: &ev,
            url: &h.url,
            approval: req.approval,
            timeout_sec: self.hook_timeout_sec,
            watch_url: hooks::watch_url(&req.env),
        });
        a.extend(["--settings".into(), hooks::settings(wiring.as_ref(), req.approval == ApprovalMode::AllowAll)]);
        if req.mcp.delivered().next().is_some() {
            a.extend(["--mcp-config".into(), req.mcp.mcp_servers_json().to_string()]);
        }
        if self.strict_mcp {
            a.push("--strict-mcp-config".into());
        }
        a.extend([if resume { "--resume" } else { "--session-id" }.into(), session_id.to_string()]);
        a
    }
}

/// The `--model` value of a pick: none for blank or [`DEFAULT_MODEL`].
pub fn model_arg(model: Option<&str>) -> Option<String> {
    model.map(str::trim).filter(|m| !m.is_empty() && !m.eq_ignore_ascii_case(DEFAULT_MODEL)).map(str::to_string)
}

/// claude's `--permission-mode` for an approval mode.
pub fn permission_mode(a: ApprovalMode) -> &'static str {
    match a {
        ApprovalMode::Deny => "dontAsk",
        ApprovalMode::Ask => "default",
        ApprovalMode::AllowEdits => "acceptEdits",
        ApprovalMode::AllowAll => "bypassPermissions",
    }
}

/// claude's config dir: `CLAUDE_CONFIG_DIR`, else `~/.claude`.
pub fn config_dir(probe: &dyn Probe) -> Option<PathBuf> {
    probe.env(CONFIG_DIR_ENV).map(PathBuf::from).or_else(|| probe.home().map(|h| h.join(".claude")))
}

impl Harness for ClaudeHarness {
    fn commands(&self) -> Vec<infantry_harness::HarnessCommand> {
        use infantry_harness::{CommandAction as A, HarnessCommand as C};
        vec![
            C::new("model", "Switch the model for the next turn", A::SetModel).argument("<model>"),
            C::new("effort", "Set the reasoning effort for the next turn", A::SetEffort).argument("<level>").values(&["low", "medium", "high", "xhigh", "max"]),
            C::new("compact", "Compact the conversation (the harness's own compaction)", A::Native).native("/compact"),
        ]
    }

    fn id(&self) -> &str {
        ID
    }

    fn capabilities(&self) -> Capabilities {
        let interactive = self.mode == Mode::Interactive;
        Capabilities {
            delivery: if interactive { TurnDelivery::Persistent } else { TurnDelivery::SpawnPerTurn },
            pty_screen: interactive,
            file_tail: interactive,
            hook_channel: true,
            http: false,
            resume: true,
            usage: true,
            model_selection: true,
            approval_modes: vec![ApprovalMode::Deny, ApprovalMode::Ask, ApprovalMode::AllowEdits, ApprovalMode::AllowAll],
        }
    }

    fn models(&self, _probe: &dyn Probe) -> Vec<ModelInfo> {
        MODELS.iter().map(|(id, label)| ModelInfo { id: (*id).into(), label: (*label).into() }).collect()
    }

    fn credential(&self) -> Option<infantry_harness::Credential> {
        Some(infantry_harness::Credential {
            env: API_KEY_ENV.into(),
            label: "Claude Code".into(),
            help: "Create an API key in the Anthropic Console (console.anthropic.com). Only needed when you choose API key; with a Claude subscription you log in instead.".into(),
            auth_modes: vec![infantry_harness::AUTH_API_KEY.into(), infantry_harness::AUTH_SUBSCRIPTION.into()],
            login_argv: vec![self.program.clone(), "auth".into(), "login".into()],
        })
    }

    fn sign_in_problem(&self, probe: &dyn Probe) -> Option<String> {
        if probe.env(OAUTH_TOKEN_ENV).is_some() || probe.env(API_KEY_ENV).is_some_and(|k| !k.trim().is_empty()) {
            return None;
        }
        let creds = config_dir(probe).map(|d| d.join(CREDENTIALS_FILE));
        match creds.and_then(|p| probe.read_to_string(&p)) {
            Some(t) if !t.trim().is_empty() => None,
            _ => Some(interactive::NOT_SIGNED_IN.into()),
        }
    }

    fn launch(&self, req: &LaunchRequest) -> Result<LaunchPlan, LaunchError> {
        let (session_id, resume) = self.session(req);
        let mut spec = ProcessSpec::new(self.program.clone(), self.args(req, &session_id, resume)).cwd(req.cwd.to_string_lossy());
        spec.env = req.env.clone();
        spec.env.push((TODO_ENV.into(), "1".into()));
        if let Some(n) = req.context_limit.filter(|n| *n > 0) {
            spec.env.push((CONTEXT_ENV.into(), n.to_string()));
        }
        spec.env_remove = req.env_remove.clone();
        spec.env_remove.extend(ENV_REMOVE.map(String::from));
        match self.mode {
            Mode::Headless => {
                let prompt =
                    req.prompt.as_deref().ok_or_else(|| LaunchError("headless claude runs a process per turn: a prompt is required".into()))?;
                Ok(LaunchPlan {
                    spec,
                    stdin: Some(prompt.as_bytes().to_vec()),
                    translator: Box::new(headless::Headless::new(req.approval)),
                    tail: Vec::new(),
                })
            }
            Mode::Interactive => {
                let tail = self
                    .transcript_path(&req.cwd, &session_id)
                    .map(|p| TailSpec { path: p.to_string_lossy().into_owned(), from: if resume { TailFrom::End } else { TailFrom::Start } })
                    .into_iter()
                    .collect();
                Ok(LaunchPlan {
                    spec: spec.pty(self.pty),
                    stdin: None,
                    translator: Box::new(interactive::Interactive::new(&session_id, req.approval, self.timings)),
                    tail,
                })
            }
        }
    }
}
