// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The pi coding agent (`@earendil-works/pi-coding-agent`) as an Infantry harness: ONE persistent
//! `pi --mode rpc [--model m] [--thinking t] [--session id]` process per session (the mode the product uses), driven
//! with RPC JSON lines ([`rpc::RpcTranslator`]).
//!
//! Facts kept from the product (not to be "simplified"):
//! - The launcher is `node <cli.js>` behind npm's shim ([`PiHarness::resolved`]): no `cmd.exe` between us and pi.
//! - Sessions are persisted (no `--no-session`), so the `get_state` session id can be resumed with `--session`.
//! - pi has NO approval gate of its own: the modes are enforced by the `infantry-approval.ts` extension (env
//!   `INFANTRY_TOOL_APPROVAL`; DENY lets only read tools run, ALLOW_EDITS also edit/write, ASK relays an rpc confirm
//!   dialog). Anything short of allow-all refuses to launch when that extension could not be staged: fail closed.
//! - The extensions ship with this crate and are staged on the runner's host ([`infantry_harness::Asset`]): the to-do
//!   tool, the approval gate, the Command Code provider, the watch hook (allow-all) and the MCP bridge (when the host
//!   sets `INFANTRY_PI_MCP`).

pub mod rpc;
pub mod store;

use infantry_harness::{
    shim, ApprovalMode, Asset, Capabilities, Harness, LaunchError, LaunchPlan, LaunchRequest, ModelInfo, Probe, ProcessSpec,
    TurnDelivery,
};
use serde_json::Value;
use std::path::PathBuf;

pub const ID: &str = "pi";
pub const APPROVAL_ENV: &str = "INFANTRY_TOOL_APPROVAL";
pub const MCP_ENV: &str = "INFANTRY_PI_MCP";
pub const AGENT_DIR_ENV: &str = "PI_CODING_AGENT_DIR";

pub const TODO: &str = "infantry-todo.ts";
pub const APPROVAL: &str = "infantry-approval.ts";
pub const COMMANDCODE: &str = "infantry-commandcode.ts";
pub const WATCH: &str = "infantry-watch.ts";
pub const MCP: &str = "infantry-mcp.ts";

fn source(name: &str) -> &'static str {
    match name {
        TODO => include_str!("../assets/infantry-todo.ts"),
        APPROVAL => include_str!("../assets/infantry-approval.ts"),
        COMMANDCODE => include_str!("../assets/infantry-commandcode.ts"),
        WATCH => include_str!("../assets/infantry-watch.ts"),
        MCP => include_str!("../assets/infantry-mcp.ts"),
        _ => "",
    }
}

#[derive(Debug, Clone)]
pub struct PiHarness {
    /// The argv prefix that starts pi (default `pi`; [`PiHarness::resolved`] gives `node <cli.js>`).
    pub launcher: Vec<String>,
    /// Compact after a turn that ends over this many context tokens (best effort); None = pi's own policy only.
    pub context_limit: Option<u64>,
    /// MCP user servers the session turns off (`--exclude-tools mcp__<server>__*`).
    pub deny_mcp: Vec<String>,
}

impl Default for PiHarness {
    fn default() -> Self {
        PiHarness { launcher: vec!["pi".into()], context_limit: None, deny_mcp: Vec::new() }
    }
}

/// [`MCP_ENV`] for the plan's delivered stdio servers (`{"servers":[{"name","prefix","command","env"}]}`); None = none.
pub fn plan_env(req: &LaunchRequest) -> Option<String> {
    let servers: Vec<serde_json::Value> = req
        .mcp
        .delivered()
        .filter_map(|s| match &s.transport {
            infantry_harness::McpTransport::Stdio { command, env, .. } if !command.is_empty() => Some(serde_json::json!({
                "name": s.name, "prefix": true, "command": command,
                "env": env.iter().map(|(k, v)| (k.clone(), serde_json::Value::String(v.clone()))).collect::<serde_json::Map<_, _>>(),
            })),
            _ => None,
        })
        .collect();
    (!servers.is_empty()).then(|| serde_json::json!({ "servers": servers }).to_string())
}

fn has_env(req: &LaunchRequest, name: &str) -> bool {
    req.env.iter().any(|(k, v)| k == name && !v.is_empty()) || std::env::var(name).is_ok_and(|v| !v.is_empty())
}

pub fn approval_value(mode: ApprovalMode) -> &'static str {
    match mode {
        ApprovalMode::Deny => "DENY",
        ApprovalMode::Ask => "ASK",
        ApprovalMode::AllowEdits => "ALLOW_EDITS",
        ApprovalMode::AllowAll => "ALLOW_ALL",
    }
}

impl PiHarness {
    /// This harness with the launcher resolved on the machine [probe] sees: `node <cli.js>` behind npm's `pi.cmd`
    /// (`INFANTRY_NODE_EXE`, else `node`, runs it).
    pub fn resolved(mut self, probe: &dyn Probe) -> Self {
        let node = probe.env("INFANTRY_NODE_EXE").unwrap_or_else(|| "node".into());
        let name = self.launcher.first().cloned().unwrap_or_else(|| "pi".into());
        self.launcher = shim::launcher(probe, &name, &node);
        self
    }

    /// The command line for [req] (without the launcher's extensions when [req] has none staged).
    pub fn args(&self, req: &LaunchRequest) -> Vec<String> {
        let mut a: Vec<String> = self.launcher[1..].to_vec();
        a.extend(["--mode".into(), "rpc".into()]);
        if let Some(m) = req.model.as_deref().filter(|m| !m.trim().is_empty()) {
            a.extend(["--model".into(), m.into()]);
        }
        if let Some(e) = req.effort.as_deref().filter(|e| !e.trim().is_empty()) {
            a.extend(["--thinking".into(), e.into()]); // pi calls reasoning effort "thinking"
        }
        if let Some(r) = req.resume.as_deref().filter(|r| !r.trim().is_empty()) {
            a.extend(["--session".into(), r.into()]);
        }
        let mut deny: Vec<String> =
            self.deny_mcp.iter().chain(req.mcp.deny.iter()).filter(|s| *s != "infantry").map(|s| format!("mcp__{s}__*")).collect();
        deny.dedup();
        deny.sort();
        if !deny.is_empty() {
            a.extend(["--exclude-tools".into(), deny.join(",")]);
        }
        for (name, path) in &req.staged {
            if [TODO, APPROVAL, COMMANDCODE, WATCH, MCP].contains(&name.as_str()) {
                a.extend(["-e".into(), path.clone()]);
            }
        }
        a
    }

    /// `pi --list-models`, for a host that runs it (parse with [`parse_list_models`]).
    pub fn list_models_spec(&self) -> ProcessSpec {
        let mut args = self.launcher[1..].to_vec();
        args.push("--list-models".into());
        ProcessSpec::new(self.launcher[0].clone(), args)
    }

    fn agent_dir(probe: &dyn Probe) -> Option<PathBuf> {
        probe.env(AGENT_DIR_ENV).map(PathBuf::from).or_else(|| probe.home().map(|h| h.join(".pi").join("agent")))
    }
}

fn token(s: &str, slash: bool) -> bool {
    let mut cs = s.chars();
    cs.next().is_some_and(|c| c.is_ascii_alphanumeric())
        && s.chars().all(|c| c.is_ascii_alphanumeric() || "._:@+-".contains(c) || (slash && c == '/'))
}

/// `pi --list-models` stdout (a `provider  model  context ...` table) -> `provider/model`, in printed order.
pub fn parse_list_models(output: &str) -> Vec<ModelInfo> {
    let mut out: Vec<ModelInfo> = Vec::new();
    let mut in_table = false;
    for raw in output.lines() {
        let cells: Vec<&str> = raw.split_whitespace().collect();
        if cells.len() < 2 {
            continue;
        }
        if !in_table {
            in_table = cells[0].eq_ignore_ascii_case("provider") && cells[1].eq_ignore_ascii_case("model");
            continue;
        }
        if token(cells[0], false) && token(cells[1], true) {
            let id = format!("{}/{}", cells[0], cells[1]);
            if !out.iter().any(|m| m.id == id) {
                out.push(ModelInfo { label: id.clone(), id });
            }
        }
    }
    out
}

/// pi's per-provider cache `models-store.json` -> `provider/id`.
pub fn parse_models_store(text: &str) -> Vec<ModelInfo> {
    let Ok(Value::Object(root)) = serde_json::from_str::<Value>(text) else { return Vec::new() };
    let mut out = Vec::new();
    for (provider, entry) in &root {
        if !token(provider, false) {
            continue;
        }
        for m in entry.get("models").and_then(Value::as_array).into_iter().flatten() {
            if let Some(id) = m.get("id").and_then(Value::as_str).filter(|i| token(i, true)) {
                let full = format!("{provider}/{id}");
                if !out.iter().any(|x: &ModelInfo| x.id == full) {
                    let label = m.get("name").and_then(Value::as_str).unwrap_or(&full).to_string();
                    out.push(ModelInfo { id: full, label });
                }
            }
        }
    }
    out
}

/// Provider key variables pi reads (any `*_API_KEY` counts in the product; a probe cannot list the environment, so these
/// are the ones pi documents).
const KEY_VARS: &[&str] = &[
    "ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GEMINI_API_KEY", "GOOGLE_API_KEY", "DEEPSEEK_API_KEY", "CMD_API_KEY",
    "OPENCODE_API_KEY", "GROQ_API_KEY", "XAI_API_KEY", "MISTRAL_API_KEY", "OPENROUTER_API_KEY", "CEREBRAS_API_KEY",
    "ZAI_API_KEY", "MOONSHOT_API_KEY", "DASHSCOPE_API_KEY", "AZURE_OPENAI_API_KEY", "ANTHROPIC_OAUTH_TOKEN", "HF_TOKEN",
    "AWS_ACCESS_KEY_ID", "AWS_PROFILE", "AWS_BEARER_TOKEN_BEDROCK", "GOOGLE_APPLICATION_CREDENTIALS",
];

impl Harness for PiHarness {
    fn id(&self) -> &str {
        ID
    }

    fn capabilities(&self) -> Capabilities {
        Capabilities {
            delivery: TurnDelivery::Persistent,
            pty_screen: false,
            file_tail: false,
            hook_channel: false,
            http: false,
            resume: true,
            usage: true,
            model_selection: true,
            approval_modes: vec![ApprovalMode::Deny, ApprovalMode::Ask, ApprovalMode::AllowEdits, ApprovalMode::AllowAll],
        }
    }

    /// pi's cached `models-store.json` (the live list is `pi --list-models`: [`PiHarness::list_models_spec`]).
    fn models(&self, probe: &dyn Probe) -> Vec<ModelInfo> {
        Self::agent_dir(probe)
            .and_then(|d| probe.read_to_string(&d.join("models-store.json")))
            .map(|t| parse_models_store(&t))
            .unwrap_or_default()
    }

    /// Proof only: pi cannot answer when no provider key variable is set and its agent dir holds no `auth.json` entry, no
    /// `models.json` and no extensions folder.
    /// pi's own session record, `<agent dir>/sessions/<any>/*_<psid>.jsonl`.
    fn store_stats(&self, probe: &dyn Probe, provider_session_id: &str) -> Option<Vec<infantry_harness::stats::StatEvent>> {
        let file = store::find(probe, &Self::agent_dir(probe)?.join("sessions"), provider_session_id)?;
        Some(store::read(probe.lines(&file)?))
    }

    /// The window pi's local catalog `models-store.json` states.
    fn context_window(&self, probe: &dyn Probe, model: &str) -> Option<u64> {
        store::context_window(&probe.read_to_string(&Self::agent_dir(probe)?.join("models-store.json"))?, model)
    }

    fn sign_in_problem(&self, probe: &dyn Probe) -> Option<String> {
        let program = self.launcher.last().cloned().unwrap_or_else(|| "pi".into());
        if !shim::installed(probe, &program) {
            return Some(format!("{program} is not installed (not on PATH)"));
        }
        if KEY_VARS.iter().any(|k| probe.env(k).is_some()) {
            return None;
        }
        let dir = Self::agent_dir(probe)?;
        let auth = dir.join("auth.json");
        let has_auth = probe.exists(&auth)
            && probe
                .read_to_string(&auth)
                .map(|t| serde_json::from_str::<Value>(t.trim_start_matches('\u{feff}')).map_or(true, |v| v.as_object().is_none_or(|o| !o.is_empty())))
                .unwrap_or(true);
        if has_auth || probe.exists(&dir.join("models.json")) || probe.exists(&dir.join("extensions")) {
            return None;
        }
        Some("pi has no provider: no API key variable, no auth.json login (/login), no models.json or extension".into())
    }

    fn assets(&self, req: &LaunchRequest) -> Vec<Asset> {
        let mut names = vec![TODO, APPROVAL, COMMANDCODE];
        if req.approval == ApprovalMode::AllowAll {
            names.push(WATCH);
        }
        if has_env(req, MCP_ENV) || plan_env(req).is_some() {
            names.push(MCP);
        }
        names.into_iter().map(|n| Asset { name: n.into(), contents: source(n).as_bytes().to_vec() }).collect()
    }

    fn launch(&self, req: &LaunchRequest) -> Result<LaunchPlan, LaunchError> {
        if self.launcher.is_empty() {
            return Err(LaunchError("pi: empty launcher".into()));
        }
        if req.approval != ApprovalMode::AllowAll && req.staged_path(APPROVAL).is_none() {
            return Err(LaunchError(format!(
                "pi has no approval gate of its own and {APPROVAL} could not be staged on the runner: refusing to run under {}",
                approval_value(req.approval)
            )));
        }
        let mut spec = ProcessSpec::new(self.launcher[0].clone(), self.args(req)).cwd(req.cwd.to_string_lossy());
        spec.env = req.env.clone();
        spec.env.retain(|(k, _)| k != APPROVAL_ENV);
        spec.env.push((APPROVAL_ENV.into(), approval_value(req.approval).into()));
        if !has_env(req, MCP_ENV) {
            if let Some(v) = plan_env(req) {
                spec.env.push((MCP_ENV.into(), v));
            }
        }
        spec.env_remove = req.env_remove.clone();
        Ok(LaunchPlan {
            spec,
            stdin: None,
            translator: Box::new(rpc::RpcTranslator::new().with_context_limit(self.context_limit)),
            tail: Vec::new(),
        })
    }
}
