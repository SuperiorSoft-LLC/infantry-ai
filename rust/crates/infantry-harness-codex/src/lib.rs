// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The OpenAI Codex CLI as an Infantry harness: one `codex exec --json` process per turn, the prompt on stdin, the
//! conversation continued with `codex exec resume <thread_id>`. Its stdout is JSONL (`thread.started`, `turn.started`,
//! `item.started|updated|completed`, `turn.completed`, `turn.failed`, `error`); [`CodexTranslator`] maps it to neutral
//! [`HarnessEvent`]s.
//!
//! Verified against codex-cli 0.162.1 (`codex exec --help`, `codex exec resume --help`): `resume` takes `--json`,
//! `--skip-git-repo-check`, `-m`, `-c` and `--dangerously-bypass-approvals-and-sandbox` but NOT `-s/--sandbox`, so the
//! sandbox is set with `-c sandbox_mode="..."`, which both forms accept.

use infantry_harness::{
    ApprovalMode, Capabilities, Harness, HarnessEvent, LaunchError, LaunchPlan, LaunchRequest, ModelInfo, Probe, ProcessSpec,
    Translator, TranslatorInput, TurnDelivery, TurnEnd, Usage,
};
use serde_json::Value;
use std::path::PathBuf;

pub const ID: &str = "codex";

#[derive(Debug, Clone)]
pub struct CodexHarness {
    /// The program to run (default `codex`, resolved on the runner's PATH).
    pub program: String,
}

impl Default for CodexHarness {
    fn default() -> Self {
        CodexHarness { program: "codex".into() }
    }
}

impl CodexHarness {
    /// The command-line arguments for one turn of [req].
    pub fn args(&self, req: &LaunchRequest) -> Vec<String> {
        let mut a: Vec<String> = vec!["exec".into()];
        if let Some(thread) = req.resume.as_deref().filter(|t| !t.is_empty()) {
            a.push("resume".into());
            a.push(thread.into());
        }
        a.push("--json".into());
        a.push("--skip-git-repo-check".into());
        a.extend(approval_args(req.approval));
        if let Some(m) = req.model.as_deref().filter(|m| !m.is_empty()) {
            a.push("-m".into());
            a.push(m.into());
        }
        if let Some(e) = req.effort.as_deref().filter(|e| !e.is_empty()) {
            a.push("-c".into());
            a.push(format!("model_reasoning_effort={}", toml_str(e)));
        }
        a.extend(mcp_args(&req.mcp));
        a.push("-".into()); // the prompt comes on stdin
        a
    }

    fn codex_home(probe: &dyn Probe) -> Option<PathBuf> {
        probe.env("CODEX_HOME").map(PathBuf::from).or_else(|| probe.home().map(|h| h.join(".codex")))
    }
}

/// The plan's delivered stdio servers as codex `-c mcp_servers.<name>.{command,args,env}` overrides.
pub fn mcp_args(plan: &infantry_harness::McpPlan) -> Vec<String> {
    let mut a = Vec::new();
    for s in plan.delivered() {
        let infantry_harness::McpTransport::Stdio { command, env, .. } = &s.transport else { continue };
        let Some((program, args)) = command.split_first() else { continue };
        let key: String = s.name.chars().map(|c| if c.is_ascii_alphanumeric() || c == '_' || c == '-' { c } else { '_' }).collect();
        let list = args.iter().map(|x| toml_str(x)).collect::<Vec<_>>().join(",");
        let table = env.iter().map(|(k, v)| format!("{}={}", toml_str(k), toml_str(v))).collect::<Vec<_>>().join(",");
        a.extend(["-c".into(), format!("mcp_servers.{key}.command={}", toml_str(program))]);
        a.extend(["-c".into(), format!("mcp_servers.{key}.args=[{list}]")]);
        a.extend(["-c".into(), format!("mcp_servers.{key}.env={{{table}}}")]);
    }
    a
}

/// The flags for an approval mode. `codex exec` cannot put a question to the host, so [`ApprovalMode::Ask`] runs as
/// [`ApprovalMode::Deny`] (read-only).
pub fn approval_args(mode: ApprovalMode) -> Vec<String> {
    match mode {
        ApprovalMode::Deny | ApprovalMode::Ask => vec!["-c".into(), "sandbox_mode=\"read-only\"".into()],
        ApprovalMode::AllowEdits => vec!["-c".into(), "sandbox_mode=\"workspace-write\"".into()],
        ApprovalMode::AllowAll => vec!["--dangerously-bypass-approvals-and-sandbox".into()],
    }
}

fn toml_str(s: &str) -> String {
    format!("\"{}\"", s.replace('\\', "\\\\").replace('"', "\\\""))
}

impl Harness for CodexHarness {
    fn id(&self) -> &str {
        ID
    }

    fn capabilities(&self) -> Capabilities {
        Capabilities {
            delivery: TurnDelivery::SpawnPerTurn,
            pty_screen: false,
            file_tail: false,
            hook_channel: false,
            http: false,
            resume: true,
            usage: true,
            model_selection: true,
            approval_modes: vec![ApprovalMode::Deny, ApprovalMode::AllowEdits, ApprovalMode::AllowAll],
        }
    }

    /// The models the CLI's own cache lists as visible (`$CODEX_HOME/models_cache.json`).
    fn models(&self, probe: &dyn Probe) -> Vec<ModelInfo> {
        let Some(home) = Self::codex_home(probe) else { return Vec::new() };
        let Some(text) = probe.read_to_string(&home.join("models_cache.json")) else { return Vec::new() };
        parse_models_cache(&text)
    }

    fn sign_in_problem(&self, probe: &dyn Probe) -> Option<String> {
        let on_path = probe.env("PATH").is_some_and(|path| {
            let exts: &[&str] = if cfg!(windows) { &[".cmd", ".exe", ".ps1", ""] } else { &[""] };
            std::env::split_paths(&path)
                .any(|d| exts.iter().any(|e| probe.exists(&d.join(format!("{}{e}", self.program)))))
        });
        if !on_path && !probe.exists(std::path::Path::new(&self.program)) {
            return Some(format!("{} is not installed (not on PATH)", self.program));
        }
        if probe.env("CODEX_API_KEY").is_some() || probe.env("OPENAI_API_KEY").is_some() {
            return None;
        }
        match Self::codex_home(probe) {
            Some(home) if probe.exists(&home.join("auth.json")) => None,
            _ => Some("codex is not signed in: run `codex login`".into()),
        }
    }

    /// No codex plan window is read, and the usage meter does not parse its stream: `turn.completed` carries the thread's
    /// running token total, not one turn's, so adding it would count a resumed thread's earlier turns again.
    fn usage_note(&self) -> Option<String> {
        Some("Codex plan limits are not read yet, so there is no meter and no pacing for it, and its tokens are not counted in Stats yet.".into())
    }

    fn launch(&self, req: &LaunchRequest) -> Result<LaunchPlan, LaunchError> {
        let prompt = req.prompt.as_deref().ok_or_else(|| LaunchError("codex runs a process per turn: a prompt is required".into()))?;
        let mut spec = ProcessSpec::new(self.program.clone(), self.args(req)).cwd(req.cwd.to_string_lossy());
        spec.env = req.env.clone();
        spec.env_remove = req.env_remove.clone();
        Ok(LaunchPlan {
            spec,
            stdin: Some(prompt.as_bytes().to_vec()),
            translator: Box::new(CodexTranslator::default()),
            tail: Vec::new(),
        })
    }
}

/// The visible models in a `models_cache.json` text.
pub fn parse_models_cache(text: &str) -> Vec<ModelInfo> {
    let Ok(v) = serde_json::from_str::<Value>(text) else { return Vec::new() };
    v.get("models")
        .and_then(Value::as_array)
        .map(|ms| {
            ms.iter()
                .filter(|m| m.get("visibility").and_then(Value::as_str).is_none_or(|vis| vis == "list"))
                .filter_map(|m| {
                    let id = m.get("slug").or_else(|| m.get("id")).and_then(Value::as_str)?;
                    let label = m.get("display_name").and_then(Value::as_str).unwrap_or(id);
                    Some(ModelInfo { id: id.into(), label: label.into() })
                })
                .collect()
        })
        .unwrap_or_default()
}

/// Reads one `codex exec --json` process.
#[derive(Debug, Default)]
pub struct CodexTranslator {
    ended: bool,
    last_error: Option<String>,
    last_stderr: Option<String>,
}

fn s<'a>(v: &'a Value, key: &str) -> &'a str {
    v.get(key).and_then(Value::as_str).unwrap_or("")
}

impl CodexTranslator {
    fn line(&mut self, line: &str) -> Vec<HarnessEvent> {
        let mut out = vec![HarnessEvent::Stdout(line.to_string())];
        let Ok(v) = serde_json::from_str::<Value>(line) else { return out };
        match s(&v, "type") {
            "thread.started" => {
                let id = s(&v, "thread_id");
                if !id.is_empty() {
                    out.push(HarnessEvent::ProviderSession(id.into()));
                }
            }
            "turn.started" => out.push(HarnessEvent::TurnStarted),
            "item.started" => out.extend(item_started(v.get("item").unwrap_or(&Value::Null))),
            "item.completed" => out.extend(self.item_completed(v.get("item").unwrap_or(&Value::Null))),
            "turn.completed" => {
                if let Some(u) = v.get("usage") {
                    out.push(HarnessEvent::Usage(usage(u)));
                }
                self.ended = true;
                out.push(HarnessEvent::TurnEnded { end: TurnEnd::Completed, reason: None });
            }
            "turn.failed" => {
                let msg = v.get("error").map(|e| s(e, "message").to_string()).filter(|m| !m.is_empty());
                let msg = msg.or_else(|| self.last_error.clone()).unwrap_or_else(|| "turn failed".into());
                out.push(HarnessEvent::Error(msg.clone()));
                self.ended = true;
                out.push(HarnessEvent::TurnEnded { end: TurnEnd::Failed, reason: Some(msg) });
            }
            // A stream-level error (a reconnect, a transport hiccup). Fatal ones are followed by `turn.failed` or the exit.
            "error" => {
                let msg = s(&v, "message").to_string();
                self.last_error = Some(msg.clone());
                out.push(HarnessEvent::Warning(msg));
            }
            _ => {}
        }
        out
    }

    fn item_completed(&mut self, item: &Value) -> Vec<HarnessEvent> {
        let id = s(item, "id").to_string();
        match s(item, "type") {
            "agent_message" => vec![HarnessEvent::Text(s(item, "text").into())],
            "reasoning" => vec![HarnessEvent::Reasoning(s(item, "text").into())],
            "command_execution" => {
                let exit_code = item.get("exit_code").and_then(Value::as_i64).map(|c| c as i32);
                let ok = s(item, "status") == "completed" && exit_code.is_none_or(|c| c == 0);
                vec![HarnessEvent::ToolFinished { id, output: s(item, "aggregated_output").into(), exit_code, ok }]
            }
            "file_change" => {
                let changes: Vec<String> = item
                    .get("changes")
                    .and_then(Value::as_array)
                    .map(|cs| cs.iter().map(|c| format!("{} {}", s(c, "kind"), s(c, "path"))).collect())
                    .unwrap_or_default();
                let summary = changes.join("\n");
                vec![
                    HarnessEvent::ToolStarted { id: id.clone(), name: "file_change".into(), input: summary.clone() },
                    HarnessEvent::ToolFinished { id, output: summary, exit_code: None, ok: s(item, "status") == "completed" },
                ]
            }
            "mcp_tool_call" => {
                let failed = s(item, "status") == "failed";
                let output = item
                    .get("error")
                    .filter(|e| !e.is_null())
                    .map(|e| if e.is_string() { s(item, "error").to_string() } else { s(e, "message").to_string() })
                    .or_else(|| item.get("result").filter(|r| !r.is_null()).map(|r| r.to_string()))
                    .unwrap_or_default();
                vec![HarnessEvent::ToolFinished { id, output, exit_code: None, ok: !failed }]
            }
            "web_search" => vec![HarnessEvent::ToolFinished { id, output: String::new(), exit_code: None, ok: true }],
            // A non-fatal notice codex reports as an item (config warnings and the like).
            "error" => vec![HarnessEvent::Warning(s(item, "message").into())],
            _ => Vec::new(), // todo_list and future item kinds: the raw line is already in Stdout
        }
    }

    fn exited(&mut self, code: Option<i32>) -> Vec<HarnessEvent> {
        let mut out = Vec::new();
        if !self.ended {
            self.ended = true;
            let reason = self
                .last_error
                .clone()
                .or_else(|| self.last_stderr.clone())
                .unwrap_or_else(|| format!("codex exited (code {code:?}) without turn.completed"));
            out.push(HarnessEvent::TurnEnded { end: TurnEnd::Failed, reason: Some(reason) });
        }
        out.push(HarnessEvent::Exited { code });
        out
    }
}

fn item_started(item: &Value) -> Vec<HarnessEvent> {
    let id = s(item, "id").to_string();
    match s(item, "type") {
        "command_execution" => vec![HarnessEvent::ToolStarted { id, name: "command".into(), input: s(item, "command").into() }],
        "mcp_tool_call" => {
            let name = format!("{}/{}", s(item, "server"), s(item, "tool"));
            let input = item.get("arguments").map(|a| a.to_string()).unwrap_or_default();
            vec![HarnessEvent::ToolStarted { id, name, input }]
        }
        "web_search" => vec![HarnessEvent::ToolStarted { id, name: "web_search".into(), input: s(item, "query").into() }],
        _ => Vec::new(),
    }
}

fn usage(u: &Value) -> Usage {
    let n = |k: &str| u.get(k).and_then(Value::as_u64).unwrap_or(0);
    Usage {
        input_tokens: n("input_tokens"),
        cached_input_tokens: n("cached_input_tokens"),
        output_tokens: n("output_tokens"),
        reasoning_tokens: n("reasoning_output_tokens"),
    }
}

impl Translator for CodexTranslator {
    fn feed(&mut self, input: TranslatorInput) -> Vec<HarnessEvent> {
        match input {
            TranslatorInput::Stdout(line) => {
                if line.trim().is_empty() {
                    Vec::new()
                } else {
                    self.line(&line)
                }
            }
            TranslatorInput::Stderr(line) => {
                if !line.trim().is_empty() {
                    self.last_stderr = Some(line.clone());
                }
                vec![HarnessEvent::Stderr(line)]
            }
            TranslatorInput::Exited { code } => self.exited(Some(code)),
            _ => Vec::new(),
        }
    }
}
