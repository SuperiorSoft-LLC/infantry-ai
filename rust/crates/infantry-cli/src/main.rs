// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `infantry` (native): serves `infantry mcp serve` itself, with the Kotlin command's arguments, environment, output and
//! exit codes, and hands every other command to the JVM launcher unchanged. Swapping the binary a session's MCP entry runs
//! is the only change (docs/rust-port/MCP-BRIDGE.md).

mod config;
mod env;
mod frames;
mod kjson;
mod launcher;
mod output;
mod server;
mod target;
mod tools;

use output::Output;
use std::path::PathBuf;
use std::sync::OnceLock;

static OUT: OnceLock<Output> = OnceLock::new();

fn out() -> &'static Output {
    OUT.get_or_init(|| Output::new(env::get("INFANTRY_CLI_ASCII").is_some_and(|v| v.trim() == "1")))
}

fn err_line(line: &str) {
    out().err(line);
}

fn discard(_: &str) {}

fn getenv(name: &str) -> Option<String> {
    env::get(name)
}

/// The arguments of `mcp serve`. None = not ours (another command or a flag this binary does not parse).
#[derive(Default)]
struct ServeArgs {
    conn: target::Connection,
    project: Option<String>,
    mcp_depth: Option<i32>,
    max_depth: Option<i32>,
    max_spawns: Option<i32>,
    allow_kill_any: bool,
}

enum Parsed {
    Serve(ServeArgs),
    /// A usage error in a flag this binary parses: `error: <message>`, exit 2 (as the Kotlin parser).
    Bad(String),
    Delegate,
}

fn flag_value(args: &[String], i: usize) -> Option<String> {
    args.get(i).filter(|v| !v.starts_with('-')).cloned()
}

fn count_flag(args: &[String], i: usize, flag: &str) -> Result<i32, String> {
    flag_value(args, i)
        .and_then(|v| v.parse::<i32>().ok())
        .filter(|n| *n >= 0)
        .ok_or_else(|| format!("{flag} expects a whole number >= 0"))
}

fn parse(args: &[String]) -> Parsed {
    let mut a = ServeArgs::default();
    let mut rest = Vec::new();
    let mut i = 0;
    macro_rules! value {
        ($msg:expr) => {
            match flag_value(args, i + 1) {
                Some(v) => {
                    i += 2;
                    v
                }
                None => return Parsed::Bad($msg.into()),
            }
        };
    }
    while i < args.len() {
        let arg = args[i].as_str();
        match arg {
            "--host" => a.conn.host = Some(value!("--host expects a value")),
            "--port" => {
                let Some(p) = args.get(i + 1).and_then(|v| v.parse::<i32>().ok()) else {
                    return Parsed::Bad("--port expects a number".into());
                };
                a.conn.port = Some(p);
                i += 2;
            }
            "--url" => a.conn.url = Some(value!("--url expects a value")),
            "--endpoint" => a.conn.endpoint = Some(value!("--endpoint expects a name")),
            "--endpoints" => a.conn.endpoints_file = Some(value!("--endpoints expects a file path")),
            "--token-file" => a.conn.token_file = Some(value!("--token-file expects a file path")),
            "--project" => a.project = Some(value!("--project expects a project id")),
            "--mcp-depth" | "--max-depth" | "--max-spawns" => {
                let n = match count_flag(args, i + 1, arg) {
                    Ok(n) => n,
                    Err(e) => return Parsed::Bad(e),
                };
                match arg {
                    "--mcp-depth" => a.mcp_depth = Some(n),
                    "--max-depth" => a.max_depth = Some(n),
                    _ => a.max_spawns = Some(n),
                }
                i += 2;
            }
            "--allow-kill-any" => {
                a.allow_kill_any = true;
                i += 1;
            }
            _ if arg.starts_with('-') => return Parsed::Delegate,
            _ => {
                rest.push(arg.to_string());
                i += 1;
            }
        }
    }
    if rest == ["mcp", "serve"] {
        Parsed::Serve(a)
    } else {
        Parsed::Delegate
    }
}

/// `projectOfFolder`: the registered project whose folder holds [dir]; any failure is "none".
fn project_of_folder(target: &target::Target, dir: &std::path::Path) -> Option<String> {
    let mut stream = frames::FrameStream::open(target, discard).ok()?;
    let answer = stream.request("project_list", "", "projects", "project_list", true, target.answer_timeout_ms);
    stream.close();
    let list: serde_json::Value = serde_json::from_str(&answer.ok()?.data).ok()?;
    let projects: Vec<(String, String)> = list
        .as_array()?
        .iter()
        .map(|p| {
            let id = p.get("id")?.as_str()?.to_string();
            let directory = p.get("directory").and_then(|d| d.as_str()).unwrap_or("").to_string();
            Some((id, directory))
        })
        .collect::<Option<Vec<_>>>()?;
    config::project_for_dir(&projects, dir)
}

fn serve(a: ServeArgs) -> i32 {
    let target = match target::resolve(&a.conn, &getenv) {
        Ok(t) => t,
        Err(e) => {
            err_line(&format!("error: {e}"));
            return 2;
        }
    };
    if target.token.is_some() && target.url.starts_with("ws://") {
        if let Some(host) = target::url_host(&target.url) {
            if !target::is_loopback_host(&host) {
                err_line(&format!(
                    "warning: the token is sent in cleartext over ws:// to {host}; use wss:// through a TLS proxy or a tunnel (see README, \"Headless server\")"
                ));
            }
        }
    }
    let cwd = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."));
    let flags = config::McpServeConfig {
        project_id: a.project.clone(),
        max_depth: a.max_depth,
        max_spawns: a.max_spawns,
        allow_kill_any: a.allow_kill_any.then_some(true),
    };
    let config = match config::load(&cwd) {
        Ok(file) => config::merge(flags, file),
        Err(e) => {
            err_line(&format!("error: {e}"));
            return 2;
        }
    };
    let env_depth = match env::non_blank(&getenv, "INFANTRY_MCP_DEPTH") {
        None => None,
        Some(v) => match v.parse::<i32>().ok().filter(|d| *d >= 0) {
            Some(d) => Some(d),
            None => {
                err_line(&format!("error: INFANTRY_MCP_DEPTH must be a whole number >= 0, got '{v}'"));
                return 2;
            }
        },
    };
    let narrowing = tools::Narrowing {
        depth: a.mcp_depth.or(env_depth),
        max_depth: config.max_depth,
        max_spawns: config.max_spawns,
        client_key: Some(uuid::Uuid::new_v4().to_string()),
    };
    let folder_project = if config.project_id.is_some() || getenv("INFANTRY_PROJECT_ID").is_some_and(|p| !p.trim().is_empty()) {
        None
    } else {
        project_of_folder(&target, &cwd)
    };
    let default_project = config.project_id.clone().or_else(|| folder_project.clone());
    let project_text = match &default_project {
        Some(p) => format!("project {p}{}", if folder_project.is_some() { " (registered for this folder)" } else { "" }),
        None => "no default project (pass projectId to tools; infantry_projects lists them)".to_string(),
    };
    err_line(&format!(
        "infantry mcp: depth {}/{}, max {} open spawns, {project_text}",
        narrowing.depth.unwrap_or(0),
        narrowing.max_depth.map_or("daemon limits.concurrency.maxDepth".to_string(), |d| d.to_string()),
        narrowing.max_spawns.map_or("daemon limits.concurrency.mcpMaxSpawns".to_string(), |s| s.to_string()),
    ));
    if let Some(path) = getenv("INFANTRY_MCP_PID_FILE").filter(|p| !p.trim().is_empty()) {
        let p = PathBuf::from(&path);
        if let Some(parent) = p.parent().filter(|d| !d.as_os_str().is_empty()) {
            let _ = std::fs::create_dir_all(parent);
        }
        if let Err(e) = std::fs::write(&p, std::process::id().to_string()) {
            err_line(&format!("infantry mcp: could not write the pid file {path}: {e}"));
        }
    }
    let tools = tools::Tools::new(target, err_line, getenv, cwd, narrowing, default_project);
    let server = server::Server::new(tools, out().clone());
    server.serve(std::io::stdin().lock())
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let code = match parse(&args) {
        Parsed::Serve(a) => serve(a),
        Parsed::Bad(m) => {
            err_line(&format!("error: {m}"));
            2
        }
        Parsed::Delegate => launcher::run_inherited(&getenv, &args).unwrap_or_else(|e| {
            err_line(&format!(
                "error: this native infantry serves only `infantry mcp serve`, and no JVM launcher was found for the rest ({e}); set INFANTRY_BIN to the folder of infantry.cmd"
            ));
            2
        }),
    };
    std::process::exit(code);
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(s: &[&str]) -> Vec<String> {
        s.iter().map(|x| x.to_string()).collect()
    }

    #[test]
    fn serve_args_like_kotlin() {
        assert!(matches!(parse(&v(&["mcp", "serve", "--port", "18901", "--max-depth", "2"])), Parsed::Serve(a) if a.conn.port == Some(18901) && a.max_depth == Some(2)));
        assert!(matches!(parse(&v(&["--project", "p", "mcp", "serve"])), Parsed::Serve(a) if a.project.as_deref() == Some("p")));
        assert!(matches!(parse(&v(&["mcp", "list"])), Parsed::Delegate));
        assert!(matches!(parse(&v(&["mcp", "serve", "--json"])), Parsed::Delegate));
        assert!(matches!(parse(&v(&["mcp", "serve", "--max-spawns", "-1"])), Parsed::Bad(m) if m == "--max-spawns expects a whole number >= 0"));
        assert!(matches!(parse(&v(&["mcp", "serve", "--url"])), Parsed::Bad(m) if m == "--url expects a value"));
    }
}
