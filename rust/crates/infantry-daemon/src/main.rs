// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! ```text
//! infantry-daemon [--port N] [--host H] [--state-dir DIR] [--provider NAME]
//! ```
//! Defaults: `INFANTRY_PORT` else 8787, `INFANTRY_HOST` else 127.0.0.1, `INFANTRY_STATE_DIR` else `~/.infantry`,
//! `INFANTRY_PROVIDER` else `claude`. The token comes from `INFANTRY_TOKEN_FILE` / `INFANTRY_TOKEN`.
use infantry_daemon::auth::{self, AccessPolicy};
use infantry_daemon::harness;
use infantry_daemon::server::{self, App};
use infantry_daemon::sessions::{Config, Daemon};
use std::path::PathBuf;
use std::sync::Arc;

pub const DEFAULT_PORT: u16 = 8787;

fn fail(msg: String) -> ! {
    eprintln!("[infantry] refusing to start: {msg}");
    std::process::exit(2);
}

fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    let arg = |k: &str| a.iter().position(|x| x == k).and_then(|i| a.get(i + 1)).cloned();
    if a.iter().any(|x| x == "--help" || x == "-h") {
        println!("infantry-daemon [--port N] [--host H] [--state-dir DIR] [--provider NAME]");
        return;
    }
    let port: u16 = match arg("--port").or_else(|| auth::env("INFANTRY_PORT")) {
        Some(p) => p.trim().parse().unwrap_or_else(|_| fail(format!("bad port `{p}`"))),
        None => DEFAULT_PORT,
    };
    let host = arg("--host").or_else(|| auth::env("INFANTRY_HOST")).unwrap_or_else(|| "127.0.0.1".into());
    let state_dir = arg("--state-dir").or_else(|| auth::env("INFANTRY_STATE_DIR")).map(PathBuf::from).unwrap_or_else(|| {
        let home = std::env::var("USERPROFILE").or_else(|_| std::env::var("HOME")).unwrap_or_else(|_| ".".into());
        PathBuf::from(home).join(".infantry")
    });
    if let Err(e) = std::fs::create_dir_all(&state_dir) {
        fail(format!("state dir {}: {e}", state_dir.display()));
    }
    let provider = arg("--provider").or_else(|| auth::env("INFANTRY_PROVIDER")).unwrap_or_else(|| harness::DEFAULT_PROVIDER.into());
    let harness: Arc<dyn harness::Harness> = match harness::by_name(provider.trim()) {
        Some(h) => Arc::from(h),
        None => fail(format!("no harness named `{provider}`")),
    };
    let token = auth::token_from_environment().unwrap_or_else(|e| fail(e));
    let policy = AccessPolicy::new(&host, token.as_deref()).unwrap_or_else(|e| fail(e));
    let cfg = Config::new(state_dir.clone(), std::env::current_dir().unwrap_or_else(|_| state_dir.clone()));
    let daemon = Daemon::new(harness, cfg).unwrap_or_else(|e| fail(e));
    let rt = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap_or_else(|e| fail(e.to_string()));
    rt.block_on(async move {
        let listener = tokio::net::TcpListener::bind((host.as_str(), port)).await.unwrap_or_else(|e| fail(format!("port {port} could not be bound ({e})")));
        let bound = listener.local_addr().map(|a| a.port()).unwrap_or(port);
        let _ = std::fs::write(state_dir.join("daemon.port"), bound.to_string());
        println!("[infantry] listening on ws://{host}:{bound}/ws (health: http://{host}:{bound}/health)");
        println!(
            "{}",
            if !policy.token_required() {
                "[infantry] connect from this machine only: no token is needed"
            } else {
                "[infantry] connect with the bearer token from INFANTRY_TOKEN_FILE / INFANTRY_TOKEN"
            }
        );
        let app = App { daemon, policy: Arc::new(policy) };
        let stop = async {
            let _ = tokio::signal::ctrl_c().await;
        };
        if let Err(e) = server::serve(listener, app, stop).await {
            eprintln!("[infantry] server stopped: {e}");
        }
        let _ = std::fs::remove_file(state_dir.join("daemon.port"));
    });
}
