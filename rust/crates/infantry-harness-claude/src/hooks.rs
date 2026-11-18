// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The session's `--settings` JSON and the answers to claude's blocking hook calls. WHAT it does is Kotlin's
//! `ClaudeCommand.ttySettings` / `watchSettings`, `ClaudePermissionHook.kt` and `ClaudeQuestionHook.kt`:
//!
//! - `SessionStart` / `UserPromptSubmit` / `Stop` command hooks append `start` / `submit` / `stop` to the hook channel's
//!   events file (the turn boundaries);
//! - G3: a `PermissionRequest` http hook answers a permission dialog from the approval mode (only `ask` asks the host);
//! - ASK-STUCK-1: a `PreToolUse` http hook on `AskUserQuestion` answers it, so the CLI never draws its menu in a terminal
//!   nobody sees;
//! - STALL-2: the watch's `PreToolUse` / `PostToolUse` http hook on shell tools (the daemon rewrites a long command),
//!   when the session's env turns the watch on.

use infantry_harness::{ApprovalMode, HarnessEvent, Outbound};
use serde_json::{json, Map, Value};

pub const EV_START: &str = "start";
pub const EV_SUBMIT: &str = "submit";
pub const EV_STOP: &str = "stop";

pub const PRE_TOOL_USE: &str = "PreToolUse";
pub const POST_TOOL_USE: &str = "PostToolUse";
pub const PERMISSION_REQUEST: &str = "PermissionRequest";
pub const QUESTION_TOOL: &str = "AskUserQuestion";

/// The env that turns the STALL-2 watch on (`on`), the daemon base URL its hook posts to, and the env the hook forwards.
pub const WATCH_ENV: &str = "INFANTRY_WATCH";
pub const WATCH_BASE_ENV: &str = "INFANTRY_MAIL_BASE";
pub const WATCH_PATH: &str = "/watch/rewrite/claude";
pub const WATCH_TOKEN_ENV: &str = "INFANTRY_TOKEN";
pub const WATCH_SESSION_ENV: &str = "INFANTRY_SESSION_ID";
pub const WATCH_SESSION_HEADER: &str = "X-Infantry-Session";
pub const WATCH_MATCHER: &str = "Bash|PowerShell";
/// Seconds the watch hook may take (it only rewrites; on failure the command runs as written).
pub const WATCH_TIMEOUT_SEC: u64 = 5;

/// The question hook's answer while the contract has no question channel to the host: claude asks in its reply instead.
pub const QUESTION_DENIED: &str = "Questions cannot be answered through a menu in this session. Ask the owner in plain text \
in your reply (list the options and say which you recommend), then end your turn and wait for the answer.";

/// What the hooks need to be wired.
pub struct HookWiring<'a> {
    /// Events-file commands for start / submit / stop.
    pub event_command: &'a dyn Fn(&str) -> String,
    /// The hook channel's URL for blocking calls.
    pub url: &'a str,
    pub approval: ApprovalMode,
    pub timeout_sec: u64,
    /// The STALL-2 watch URL (None = off).
    pub watch_url: Option<String>,
}

/// The watch URL a session's env asks for: `<INFANTRY_MAIL_BASE>/watch/rewrite/claude` when `INFANTRY_WATCH=on`.
pub fn watch_url(env: &[(String, String)]) -> Option<String> {
    let get = |k: &str| env.iter().rev().find(|(n, _)| n == k).map(|(_, v)| v.trim().to_string());
    if get(WATCH_ENV).as_deref() != Some("on") {
        return None;
    }
    get(WATCH_BASE_ENV).filter(|b| !b.is_empty()).map(|b| format!("{}{WATCH_PATH}", b.trim_end_matches('/')))
}

/// Whether an approval mode gets the permission hook (every mode that can see a dialog: all but allow-all).
pub fn permission_wanted(a: ApprovalMode) -> bool {
    a != ApprovalMode::AllowAll
}

fn http(url: &str, timeout: u64) -> Value {
    json!({"type": "http", "url": url, "timeout": timeout})
}

fn watch_hook(url: &str) -> Value {
    json!({
        "type": "http",
        "url": url,
        "timeout": WATCH_TIMEOUT_SEC,
        "onFailure": "continue",
        "headers": {
            "Authorization": format!("Bearer ${WATCH_TOKEN_ENV}"),
            WATCH_SESSION_HEADER: format!("${WATCH_SESSION_ENV}"),
        },
        "allowedEnvVars": [WATCH_TOKEN_ENV, WATCH_SESSION_ENV],
    })
}

/// The ONE `--settings` value of a session (claude keeps only the last `--settings`, so everything goes in it). [hooks]
/// None = no hook channel (no events, no blocking hooks).
pub fn settings(hooks: Option<&HookWiring<'_>>, bypass: bool) -> String {
    let mut h = Map::new();
    if let Some(w) = hooks {
        for (hook, ev) in [("SessionStart", EV_START), ("UserPromptSubmit", EV_SUBMIT), ("Stop", EV_STOP)] {
            h.insert(hook.into(), json!([{"hooks": [{"type": "command", "command": (w.event_command)(ev)}]}]));
        }
        if permission_wanted(w.approval) {
            h.insert(PERMISSION_REQUEST.into(), json!([{"hooks": [http(w.url, w.timeout_sec)]}]));
        }
        let mut pre = vec![json!({"matcher": QUESTION_TOOL, "hooks": [http(w.url, w.timeout_sec)]})];
        if let Some(url) = &w.watch_url {
            pre.push(json!({"matcher": WATCH_MATCHER, "hooks": [watch_hook(url)]}));
            h.insert(POST_TOOL_USE.into(), json!([{"matcher": WATCH_MATCHER, "hooks": [watch_hook(&format!("{url}-post"))]}]));
        }
        h.insert(PRE_TOOL_USE.into(), Value::Array(pre));
    }
    let mut root = Map::new();
    if bypass {
        root.insert("skipDangerousModePermissionPrompt".into(), json!(true));
    }
    root.insert("enabledMcpjsonServers".into(), json!([]));
    // CLI-PTY-2: the settings twin of --thinking-display summarized.
    root.insert("showThinkingSummaries".into(), json!(true));
    if !h.is_empty() {
        root.insert("hooks".into(), Value::Object(h));
    }
    Value::Object(root).to_string()
}

/// One blocking hook call, read.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Call {
    /// A permission dialog the CLI wants answered: (tool, input JSON).
    Permission { tool: String, input: String },
    /// An AskUserQuestion call.
    Question,
    /// Anything else (answered with no decision).
    Other,
}

pub fn read_call(body: &str) -> Call {
    let Ok(v) = serde_json::from_str::<Value>(body) else { return Call::Other };
    let tool = v.get("tool_name").and_then(Value::as_str).unwrap_or("").to_string();
    let input = v.get("tool_input").map(Value::to_string).unwrap_or_else(|| "{}".into());
    match v.get("hook_event_name").and_then(Value::as_str) {
        Some(PERMISSION_REQUEST) => Call::Permission { tool, input },
        Some(PRE_TOOL_USE) if tool == QUESTION_TOOL => Call::Question,
        // An older CLI that does not name the event: a permission request carries a tool, a question names its tool.
        None if tool == QUESTION_TOOL => Call::Question,
        None if !tool.is_empty() => Call::Permission { tool, input },
        _ => Call::Other,
    }
}

pub fn permission_reply(allow: bool, message: Option<&str>) -> String {
    let mut d = Map::new();
    d.insert("behavior".into(), json!(if allow { "allow" } else { "deny" }));
    if let (false, Some(m)) = (allow, message) {
        d.insert("message".into(), json!(m));
    }
    json!({"hookSpecificOutput": {"hookEventName": PERMISSION_REQUEST, "decision": d}}).to_string()
}

/// Why a permission request is denied without asking (every mode but ask).
pub fn denied_message(a: ApprovalMode, tool: &str) -> String {
    format!("the tool approval ({a:?}) does not allow {tool}")
}

pub fn question_deny(reason: &str) -> String {
    json!({"hookSpecificOutput": {"hookEventName": PRE_TOOL_USE, "permissionDecision": "deny", "permissionDecisionReason": reason}})
        .to_string()
}

/// The blocking hook calls of one process and the host's answers to them (shared by both modes).
pub struct Answers {
    pub approval: ApprovalMode,
    /// Approval id -> the hook call waiting for it.
    pending: std::collections::HashMap<String, u64>,
}

impl Answers {
    pub fn new(approval: ApprovalMode) -> Self {
        Answers { approval, pending: Default::default() }
    }

    /// Permission requests waiting for the host.
    pub fn open(&self) -> bool {
        !self.pending.is_empty()
    }

    /// Reads one hook call: answers it now (into [outbound]) or asks the host (an event).
    pub fn call(&mut self, id: u64, body: &str, outbound: &mut Vec<Outbound>) -> Vec<HarnessEvent> {
        match read_call(body) {
            Call::Permission { tool, input } if self.approval == ApprovalMode::Ask => {
                let key = format!("hook-{id}");
                self.pending.insert(key.clone(), id);
                vec![HarnessEvent::ApprovalRequested { id: key, tool, input }]
            }
            Call::Permission { tool, .. } => {
                let msg = denied_message(self.approval, &tool);
                outbound.push(Outbound::HookReply { id, body: permission_reply(false, Some(&msg)) });
                vec![HarnessEvent::Warning(format!("claude: denied {tool} ({msg})"))]
            }
            Call::Question => {
                outbound.push(Outbound::HookReply { id, body: question_deny(QUESTION_DENIED) });
                vec![HarnessEvent::Warning("claude: AskUserQuestion turned into a plain-text question".into())]
            }
            Call::Other => {
                outbound.push(Outbound::HookReply { id, body: "{}".into() });
                Vec::new()
            }
        }
    }

    /// The host's answer to approval [key]; false = no such request.
    pub fn answer(&mut self, key: &str, allow: bool, outbound: &mut Vec<Outbound>) -> bool {
        let Some(id) = self.pending.remove(key) else { return false };
        let msg = (!allow).then(|| "the owner denied it".to_string());
        outbound.push(Outbound::HookReply { id, body: permission_reply(allow, msg.as_deref()) });
        true
    }
}
