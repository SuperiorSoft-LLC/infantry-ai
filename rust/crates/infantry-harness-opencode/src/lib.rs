// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The opencode CLI as an Infantry harness: ONE persistent `opencode serve --hostname 127.0.0.1 --port 0` process per
//! session (the mode the product uses), driven over HTTP + server-sent events with a random basic-auth password
//! (`OPENCODE_SERVER_PASSWORD`, so no other local process can drive it). The protocol is [`serve::ServeTranslator`];
//! the inline config ([`config`]) carries the permission rules and, under allow-all, the watch plugin
//! (`infantry-watch.js`, staged on the runner's host).
//!
//! The HTTP goes through [`infantry_harness::Runner::http`]: the server listens on the RUNNER host's loopback, so the
//! runner makes the calls (a remote runner forwards them). Verified against opencode 1.18.35 (`opencode serve --help`:
//! `--port` defaults to 0, a free port, printed as `listening on <url>`).

pub mod config;
pub mod serve;

use infantry_harness::{
    shim, ApprovalMode, Asset, Capabilities, Harness, LaunchError, LaunchPlan, LaunchRequest, ModelInfo, Probe, ProcessSpec,
    TurnDelivery,
};

pub const ID: &str = "opencode";
/// Free model: never burn a metered model by default.
pub const DEFAULT_MODEL: &str = "opencode/deepseek-v4.1-flash-free";
/// The daemon-wide default model (blank or `default` is not a model id).
pub const MODEL_ENV: &str = "INFANTRY_OPENCODE_MODEL";
/// `opencode serve` reads its basic-auth password from here.
pub const PASSWORD_ENV: &str = "OPENCODE_SERVER_PASSWORD";
pub const WATCH_PLUGIN: &str = "infantry-watch.js";
const WATCH_SOURCE: &str = include_str!("../assets/infantry-watch.js");

#[derive(Debug, Clone)]
pub struct OpenCodeHarness {
    /// The program to run (default `opencode`; [`OpenCodeHarness::resolved`] prefers the native exe behind an npm shim).
    pub program: String,
    /// Provider retries before a turn is given up (0 = never). Setting `harness.opencode.maxRetries`.
    pub max_retries: u32,
    /// The session's own MCP server (`infantry mcp serve` argv + env), merged as `mcp.infantry`; None = none.
    pub mcp: Option<(Vec<String>, Vec<(String, String)>)>,
}

impl Default for OpenCodeHarness {
    fn default() -> Self {
        OpenCodeHarness { program: "opencode".into(), max_retries: serve::DEFAULT_MAX_RETRIES, mcp: None }
    }
}

fn real_model(v: Option<&str>) -> Option<String> {
    v.map(str::trim).filter(|m| !m.is_empty() && !m.eq_ignore_ascii_case("default")).map(String::from)
}

fn env_of(req: &LaunchRequest, name: &str) -> Option<String> {
    req.env.iter().rev().find(|(k, _)| k == name).map(|(_, v)| v.clone()).or_else(|| std::env::var(name).ok()).filter(|v| !v.is_empty())
}

/// The session's model, then `INFANTRY_OPENCODE_MODEL`, then [`DEFAULT_MODEL`].
pub fn effective_model(req: &LaunchRequest) -> String {
    real_model(req.model.as_deref())
        .or_else(|| real_model(env_of(req, MODEL_ENV).as_deref()))
        .unwrap_or_else(|| DEFAULT_MODEL.to_string())
}

fn random_password() -> String {
    use base64::Engine;
    let mut b = [0u8; 24];
    getrandom::getrandom(&mut b).expect("OS random source");
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(b)
}

impl OpenCodeHarness {
    /// This harness with [`OpenCodeHarness::program`] resolved on the machine [probe] sees (the native `opencode.exe`
    /// behind npm's `opencode.cmd`).
    pub fn resolved(mut self, probe: &dyn Probe) -> Self {
        if let Some(first) = shim::launcher(probe, &self.program, "node").into_iter().next() {
            self.program = first;
        }
        self
    }

    pub fn args() -> Vec<String> {
        ["serve", "--hostname", "127.0.0.1", "--port", "0"].map(String::from).to_vec()
    }

    /// The inline config the server gets for [req].
    pub fn config(&self, req: &LaunchRequest) -> String {
        let inherited = env_of(req, config::CONFIG_CONTENT_ENV);
        let mut c = config::serve_config(req.approval, inherited.as_deref());
        if let Some((cmd, env)) = &self.mcp {
            c = config::merge_mcp(&c, cmd, env);
        } else if let Some((cmd, env)) = req.mcp.infantry_stdio() {
            c = config::merge_mcp(&c, cmd, env);
        }
        // STALL-2: the watch plugin under allow-all only (a rewrite must never widen what the session may run).
        if req.approval == ApprovalMode::AllowAll {
            if let Some(path) = req.staged_path(WATCH_PLUGIN) {
                c = config::append_plugin(&c, path);
            }
        }
        c
    }

    /// The `opencode models` listing a host may run (its output parsed by [`config::parse_models_output`]) when the
    /// config files name no model.
    pub fn list_models_spec(&self) -> ProcessSpec {
        ProcessSpec::new(self.program.clone(), ["models"])
    }
}

impl Harness for OpenCodeHarness {
    fn id(&self) -> &str {
        ID
    }

    fn capabilities(&self) -> Capabilities {
        Capabilities {
            delivery: TurnDelivery::Persistent,
            pty_screen: false,
            file_tail: false,
            hook_channel: false,
            http: true,
            resume: true,
            usage: true,
            model_selection: true,
            approval_modes: vec![ApprovalMode::Deny, ApprovalMode::Ask, ApprovalMode::AllowEdits, ApprovalMode::AllowAll],
        }
    }

    /// The user's own opencode setup (config files, `auth.json`, the models.dev cache); empty = the CLI's default.
    fn models(&self, probe: &dyn Probe) -> Vec<ModelInfo> {
        config::Locations::from_probe(probe).map(|l| config::catalogue(probe, &l)).unwrap_or_default()
    }

    /// opencode's free tier needs no sign-in: only a missing CLI is a problem.
    fn sign_in_problem(&self, probe: &dyn Probe) -> Option<String> {
        (!shim::installed(probe, &self.program)).then(|| format!("{} is not installed (not on PATH)", self.program))
    }

    fn assets(&self, req: &LaunchRequest) -> Vec<Asset> {
        if req.approval == ApprovalMode::AllowAll {
            vec![Asset { name: WATCH_PLUGIN.into(), contents: WATCH_SOURCE.as_bytes().to_vec() }]
        } else {
            Vec::new()
        }
    }

    fn launch(&self, req: &LaunchRequest) -> Result<LaunchPlan, LaunchError> {
        let password = random_password();
        let mut spec = ProcessSpec::new(self.program.clone(), Self::args()).cwd(req.cwd.to_string_lossy());
        spec.env = req.env.clone();
        spec.env.retain(|(k, _)| k != config::CONFIG_CONTENT_ENV && k != PASSWORD_ENV);
        spec.env.push((config::CONFIG_CONTENT_ENV.into(), self.config(req)));
        spec.env.push((PASSWORD_ENV.into(), password.clone()));
        spec.env_remove = req.env_remove.clone();
        let translator =
            serve::ServeTranslator::new(&password, req.approval, effective_model(req), req.resume.clone(), self.max_retries);
        Ok(LaunchPlan { spec, stdin: None, translator: Box::new(translator), tail: Vec::new() })
    }
}
