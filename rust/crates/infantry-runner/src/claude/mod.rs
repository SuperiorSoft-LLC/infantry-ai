// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Claude Code as ONE [`Harness`] implementation: the interactive `claude` (no `-p`) in a PTY, as a person runs it. Port of
//! `ClaudeTtySession.kt` (launch) and `ClaudeCommand.kt` (argv, settings). Nothing is spoofed: the runner types into the terminal
//! and reads the files the CLI writes (its transcript, the hook events file) plus the hook calls it makes.

pub mod driver;
pub mod hooks;
pub mod transcript;

use crate::harness::{Harness, Host, LaunchRequest, LaunchSpec, ToolApproval};
use crate::hooks::{event_command, HookHandler};
use driver::{ClaudeDriver, DriverConfig, HookState, EV_START, EV_STOP, EV_SUBMIT};
use serde_json::{json, Map, Value};
use std::path::{Path, PathBuf};
use std::sync::atomic::Ordering;
use std::sync::Arc;

/// Per-session files under the runner's state folder.
pub const TTY_DIR: &str = "claude-tty";
/// How long a CLI may take to come up before its queued turns fail.
pub const BOOT_TIMEOUT_MS: u64 = 120_000;

pub struct Claude {
    /// The executable; None = `claude` resolved from PATH (an npm `.cmd` shim is followed to the binary it runs).
    pub executable: Option<String>,
    /// `--allowedTools` rules (one comma-joined value).
    pub allowed_tools: Vec<String>,
    /// `--strict-mcp-config`: only the MCP servers passed here load (keeps a project `.mcp.json` and its dialog out).
    pub strict_mcp: bool,
    pub boot_timeout_ms: u64,
    /// FIRST-TURN-1: how long the first paste may wait to be taken before one recovery (0 = never).
    pub first_submit_ms: u64,
    /// Seconds the CLI waits for a hook answer.
    pub hook_timeout_sec: u64,
    /// claude's config folder; None = `CLAUDE_CONFIG_DIR` or `~/.claude`.
    pub config_dir: Option<PathBuf>,
}

impl Default for Claude {
    fn default() -> Self {
        Claude {
            executable: None,
            allowed_tools: Vec::new(),
            strict_mcp: true,
            boot_timeout_ms: BOOT_TIMEOUT_MS,
            first_submit_ms: driver::FIRST_SUBMIT_MS,
            hook_timeout_sec: hooks::DEFAULT_TIMEOUT_SEC,
            config_dir: None,
        }
    }
}

/// `--permission-mode` per Tool approval (ask names `default`, so a user's own `auto` mode never skips the owner).
pub fn permission_mode(a: ToolApproval) -> &'static str {
    match a {
        ToolApproval::Deny => "dontAsk",
        ToolApproval::Ask => "default",
        ToolApproval::AllowEdits => "acceptEdits",
        ToolApproval::AllowAll => "bypassPermissions",
    }
}

/// The interactive argv after the executable (no `-p`, no stream flags), in `ClaudeCommand.interactiveCommand`'s order.
pub struct Args<'a> {
    pub session_id: Option<&'a str>,
    pub resume: Option<&'a str>,
    pub model: Option<&'a str>,
    pub effort: Option<&'a str>,
    pub permission_mode: Option<&'a str>,
    pub allowed_tools: &'a [String],
    pub settings: Option<&'a str>,
    pub strict_mcp: bool,
}

pub fn interactive_args(a: &Args<'_>) -> Vec<String> {
    let mut v: Vec<String> = Vec::new();
    let nb = |s: Option<&str>| s.filter(|s| !s.trim().is_empty()).map(str::to_string);
    if let Some(r) = nb(a.resume) {
        v.extend(["--resume".into(), r]);
    } else if let Some(id) = nb(a.session_id) {
        v.extend(["--session-id".into(), id]);
    }
    if let Some(m) = nb(a.model) {
        v.extend(["--model".into(), m]);
    }
    if let Some(e) = nb(a.effort) {
        v.extend(["--effort".into(), e]);
    }
    // CLI-PTY-2: without it the transcript stores thinking blocks as a signature only.
    v.extend(["--thinking-display".into(), "summarized".into()]);
    if let Some(p) = nb(a.permission_mode) {
        v.extend(["--permission-mode".into(), p]);
    }
    if !a.allowed_tools.is_empty() {
        v.extend(["--allowedTools".into(), a.allowed_tools.join(",")]);
    }
    if let Some(s) = nb(a.settings) {
        v.extend(["--settings".into(), s]);
    }
    if a.strict_mcp {
        v.push("--strict-mcp-config".into());
    }
    v
}

fn event_hook(event: &str, file: &Path) -> Value {
    json!({"hooks": [{"type": "command", "command": event_command(event, file)}]})
}

/// The session's ONE `--settings` file: SessionStart / UserPromptSubmit / Stop append `start` / `submit` / `stop` to [events];
/// [bypass] adds `skipDangerousModePermissionPrompt`; the permission and question hook entries are added when given.
pub fn tty_settings(events: &Path, bypass: bool, permission_hook: Option<Value>, question_hook: Option<Value>) -> String {
    let mut hooks = Map::new();
    for (hook, ev) in [("SessionStart", EV_START), ("UserPromptSubmit", EV_SUBMIT), ("Stop", EV_STOP)] {
        hooks.insert(hook.into(), json!([event_hook(ev, events)]));
    }
    if let Some(p) = permission_hook {
        hooks.insert(hooks::PERMISSION_EVENT.into(), json!([p]));
    }
    if let Some(q) = question_hook {
        hooks.insert(hooks::QUESTION_EVENT.into(), json!([q]));
    }
    let mut root = Map::new();
    if bypass {
        root.insert("skipDangerousModePermissionPrompt".into(), json!(true));
    }
    root.insert("enabledMcpjsonServers".into(), json!([]));
    root.insert("showThinkingSummaries".into(), json!(true));
    root.insert("hooks".into(), Value::Object(hooks));
    Value::Object(root).to_string()
}

/// The program to run for [name] on PATH. On Windows an npm `.cmd` shim is followed to the executable it calls (a `.cmd` needs
/// `cmd.exe` to run, and `cmd.exe` between the runner and the CLI would own the console and the exit code).
pub fn resolve_executable(name: &str) -> String {
    let p = Path::new(name);
    if p.is_absolute() && p.is_file() {
        return name.into();
    }
    let paths: Vec<PathBuf> = std::env::var_os("PATH").map(|v| std::env::split_paths(&v).collect()).unwrap_or_default();
    if cfg!(windows) {
        for dir in &paths {
            let exe = dir.join(format!("{name}.exe"));
            if exe.is_file() {
                return exe.to_string_lossy().into_owned();
            }
            let cmd = dir.join(format!("{name}.cmd"));
            if let Some(target) = std::fs::read_to_string(&cmd).ok().and_then(|t| shim_target(&t, dir)) {
                return target;
            }
        }
    } else {
        for dir in &paths {
            let f = dir.join(name);
            if f.is_file() {
                return f.to_string_lossy().into_owned();
            }
        }
    }
    name.into()
}

/// The `.exe` an npm `.cmd` shim runs (`"%dp0%\node_modules\...\x.exe" %*`), when it exists.
pub fn shim_target(shim: &str, dir: &Path) -> Option<String> {
    shim.lines().find_map(|l| {
        let l = l.trim();
        let rest = l.strip_prefix("\"%dp0%\\")?;
        let rel = &rest[..rest.find('"')?];
        if !rel.to_ascii_lowercase().ends_with(".exe") {
            return None;
        }
        let p = dir.join(rel);
        p.is_file().then(|| p.to_string_lossy().into_owned())
    })
}

impl Harness for Claude {
    fn name(&self) -> &str {
        "claude"
    }

    fn launch(&self, req: &LaunchRequest, host: &Host) -> std::io::Result<LaunchSpec> {
        let config_dir = self.config_dir.clone().unwrap_or_else(transcript::config_dir);
        let cwd = req.cwd.to_string_lossy().into_owned();
        let stored = req.resume.as_deref().filter(|s| !s.trim().is_empty());
        let resumed = stored.and_then(|id| transcript::find(&config_dir, Some(&cwd), id));
        // G2: an id claude never wrote a transcript for cannot be resumed: start it fresh under the same id.
        let resume = stored.filter(|_| resumed.is_some());
        let id = stored
            .map(str::to_string)
            .or_else(|| req.session_id.clone())
            .unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
        let dir = host.state_dir.join(TTY_DIR);
        std::fs::create_dir_all(&dir)?;
        let events = dir.join(format!("{id}.events"));
        let settings = dir.join(format!("{id}.settings.json"));

        let state = Arc::new(HookState::default());
        let mut tokens = Vec::new();
        let permission_hook = hooks::permission_wanted(req.approval).then(|| {
            let token = uuid::Uuid::new_v4().to_string();
            let (owner, st, approval, sid) = (host.owner.clone(), state.clone(), req.approval, id.clone());
            let h: HookHandler = Arc::new(move |body: &str| {
                st.open.fetch_add(1, Ordering::SeqCst);
                let (reply, denied) = hooks::decide_permission(approval, body, |tool, input| owner.approve(&sid, tool, input));
                if let Some(tool) = denied {
                    st.note(format!("interactive claude: denied {tool} (tool approval {approval:?})"));
                }
                st.open.fetch_sub(1, Ordering::SeqCst);
                st.answered.store(true, Ordering::SeqCst);
                reply
            });
            host.hooks.register(&token, h);
            let entry = hooks::permission_entry(&host.hooks.url(&token), self.hook_timeout_sec);
            tokens.push(token);
            entry
        });
        // ASK-STUCK-1: every mode gets the AskUserQuestion hook (the CLI's own menu would wait on keys nobody sends).
        let question_hook = {
            let token = uuid::Uuid::new_v4().to_string();
            let (owner, st, sid) = (host.owner.clone(), state.clone(), id.clone());
            let h: HookHandler = Arc::new(move |body: &str| {
                st.open.fetch_add(1, Ordering::SeqCst);
                let reply = hooks::decide_question(body, |q| owner.ask(&sid, q));
                st.note("interactive claude: AskUserQuestion answered through the owner's prompt");
                st.open.fetch_sub(1, Ordering::SeqCst);
                st.answered.store(true, Ordering::SeqCst);
                reply
            });
            host.hooks.register(&token, h);
            let entry = hooks::question_entry(&host.hooks.url(&token), self.hook_timeout_sec);
            tokens.push(token);
            Some(entry)
        };
        let mode = permission_mode(req.approval);
        std::fs::write(&settings, tty_settings(&events, mode == "bypassPermissions", permission_hook, question_hook))?;
        let settings_s = settings.to_string_lossy().into_owned();
        let args = interactive_args(&Args {
            session_id: if resume.is_none() { Some(&id) } else { None },
            resume,
            model: req.model.as_deref(),
            effort: req.effort.as_deref(),
            permission_mode: Some(mode),
            allowed_tools: &self.allowed_tools,
            settings: Some(&settings_s),
            strict_mcp: self.strict_mcp,
        });
        let transcript_start = resumed.as_ref().and_then(|p| std::fs::metadata(p).ok()).map(|m| m.len()).unwrap_or(0);
        let driver = ClaudeDriver::new(DriverConfig {
            session_id: id.clone(),
            cwd: cwd.clone(),
            config_dir,
            events,
            transcript_start,
            boot_timeout_ms: self.boot_timeout_ms,
            first_submit_ms: self.first_submit_ms,
            hooks: host.hooks.clone(),
            hook_tokens: tokens,
            hook_state: state,
        });
        Ok(LaunchSpec {
            program: resolve_executable(self.executable.as_deref().unwrap_or("claude")),
            args,
            env: vec![("CLAUDE_CODE_ENABLE_TODO_TOOLS".into(), "1".into())],
            cwd: req.cwd.clone(),
            session_id: id,
            driver: Box::new(driver),
        })
    }
}
