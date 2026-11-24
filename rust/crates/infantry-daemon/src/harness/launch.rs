// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! What every agent process this daemon starts is told about the daemon (Kotlin `SessionEnv` + `InfantryMcp.launch`):
//! the session env (`INFANTRY_SESSION_ID`, the daemon's port and state dir, the project, the role, the bearer when the
//! daemon has one) and the MCP plan that gives the agent the infantry tools (`infantry mcp serve` with that env).
//!
//! The daemon sets the base once it has bound its port ([`LaunchBase::for_daemon`]); a session adds its own ids.
use infantry_harness::{McpPlan, McpServerSpec, McpTransport, INFANTRY_MCP_SERVER};
use std::path::{Path, PathBuf};

/// The launcher of the `infantry` CLI a session's MCP server runs: `INFANTRY_BIN`, else the one beside this exe.
pub const BIN_ENV: &str = "INFANTRY_BIN";

/// The daemon-wide half of a session's launch.
#[derive(Debug, Clone, Default)]
pub struct LaunchBase {
    /// The env every session gets.
    pub env: Vec<(String, String)>,
    /// The `infantry` launcher argv (no `mcp serve`); None = no infantry MCP (the CLI is not found).
    pub cli: Option<Vec<String>>,
}

impl LaunchBase {
    /// The base for a daemon on loopback [port] with [state_dir]; [token] (the daemon's bearer) goes by file when
    /// the daemon's own env names one, else by value.
    pub fn for_daemon(port: u16, state_dir: &Path, token: Option<&str>, get: &dyn Fn(&str) -> Option<String>) -> LaunchBase {
        let mut env = vec![
            ("INFANTRY_PORT".to_string(), port.to_string()),
            ("INFANTRY_URL".to_string(), format!("ws://127.0.0.1:{port}/ws")),
            ("INFANTRY_STATE_DIR".to_string(), state_dir.display().to_string()),
        ];
        if let Some(token) = token {
            match get(crate::auth::TOKEN_FILE_ENV).filter(|f| !f.trim().is_empty()) {
                Some(file) => env.push((crate::auth::TOKEN_FILE_ENV.into(), file)),
                None => env.push((crate::auth::TOKEN_ENV.into(), token.to_string())),
            }
        }
        let cli = cli_launcher(get, crate::own_files::bin_dir().map(|d| d.join("infantry-daemon")).as_deref());
        if let Some(c) = &cli {
            env.push((BIN_ENV.into(), c[0].clone()));
        }
        LaunchBase { env, cli }
    }

    /// One session's env and MCP plan.
    pub fn session(&self, session_id: &str, project_id: Option<&str>, role: Option<&str>) -> (Vec<(String, String)>, McpPlan) {
        let mut env = self.env.clone();
        env.push(("INFANTRY_SESSION_ID".into(), session_id.into()));
        for (k, v) in [("INFANTRY_PROJECT_ID", project_id), ("INFANTRY_ROLE", role)] {
            if let Some(v) = v.map(str::trim).filter(|v| !v.is_empty()) {
                env.push((k.into(), v.into()));
            }
        }
        let plan = match &self.cli {
            Some(cli) => {
                let mut command = cli.clone();
                command.extend(["mcp".to_string(), "serve".to_string()]);
                McpPlan {
                    add: vec![McpServerSpec {
                        name: INFANTRY_MCP_SERVER.into(),
                        transport: McpTransport::Stdio { command, env: env.clone(), working_dir: None },
                        source: INFANTRY_MCP_SERVER.into(),
                        local_only: true,
                    }],
                    deny: Vec::new(),
                }
            }
            None => McpPlan::default(),
        };
        (env, plan)
    }
}

/// The `infantry` launcher: [`BIN_ENV`] when it names a file, else `infantry(.exe)` beside [exe], else None.
pub fn cli_launcher(get: &dyn Fn(&str) -> Option<String>, exe: Option<&Path>) -> Option<Vec<String>> {
    if let Some(b) = get(BIN_ENV).map(|b| b.trim().to_string()).filter(|b| !b.is_empty() && Path::new(b).is_file()) {
        return Some(vec![b]);
    }
    let name = if cfg!(windows) { "infantry.exe" } else { "infantry" };
    let beside: PathBuf = exe?.parent()?.join(name);
    beside.is_file().then(|| vec![beside.display().to_string()])
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_session_gets_its_ids_and_the_infantry_server_with_the_same_env() {
        let base = LaunchBase { env: vec![("INFANTRY_PORT".into(), "18990".into())], cli: Some(vec!["C:/x/infantry.exe".into()]) };
        let (env, plan) = base.session("s1", Some("p"), None);
        assert!(env.contains(&("INFANTRY_SESSION_ID".into(), "s1".into())) && env.contains(&("INFANTRY_PROJECT_ID".into(), "p".into())));
        assert!(!env.iter().any(|(k, _)| k == "INFANTRY_ROLE"));
        let (cmd, senv) = plan.infantry_stdio().unwrap();
        assert_eq!(cmd, ["C:/x/infantry.exe", "mcp", "serve"]);
        assert_eq!(senv, env.as_slice());
        assert_eq!(plan.mcp_servers_json()["mcpServers"]["infantry"]["args"][0], "mcp");
        assert!(LaunchBase::default().session("s", None, None).1.add.is_empty());
    }

    #[test]
    fn the_token_goes_by_file_when_the_daemon_has_one() {
        let token = "x".repeat(32);
        let get = |k: &str| (k == crate::auth::TOKEN_FILE_ENV).then(|| "C:/t.txt".to_string());
        let b = LaunchBase::for_daemon(1, Path::new("."), Some(&token), &get);
        assert!(b.env.iter().any(|(k, v)| k == crate::auth::TOKEN_FILE_ENV && v == "C:/t.txt"));
        assert!(!b.env.iter().any(|(k, _)| k == crate::auth::TOKEN_ENV));
        assert!(!LaunchBase::for_daemon(1, Path::new("."), None, &|_| None).env.iter().any(|(k, _)| k.contains("TOKEN")));
    }
}
