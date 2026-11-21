// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! ```text
//! infantry-daemon [--port N] [--host H] [--state-dir DIR] [--provider NAME]
//!                 [--legacy-backend "<kotlin daemon command>"] [--legacy-port N] [--gateway-routes JSON]
//! ```
//! Gateway mode (`--legacy-backend`, or `INFANTRY_LEGACY_BACKEND`): the Rust daemon is the front door and starts the
//! Kotlin daemon on a private loopback port (`--legacy-port`, else `INFANTRY_LEGACY_PORT`, else a free one) with the
//! same state dir; frames no Rust unit owns are forwarded to it. `--gateway-routes` / `INFANTRY_GATEWAY_ROUTES`
//! overrides the route of single frame types (`{"prompt":"legacy"}`; `"*"` for all).
//! Defaults (the exe runs on its own, no launcher script): `INFANTRY_PORT` else 18990, `INFANTRY_HOST` else
//! 127.0.0.1, `INFANTRY_STATE_DIR` else `%LOCALAPPDATA%\SuperiorSoft\InfantryAI-Rust` (`~/.infantry-rust` where there is
//! no LOCALAPPDATA), `INFANTRY_PROVIDER` else `claude`. The token comes from `INFANTRY_TOKEN_FILE` / `INFANTRY_TOKEN`.
//! Port 8797 (the Kotlin app's daemon) is refused: this daemon never takes over or shares the Kotlin app's port.
use infantry_daemon::auth::{self, AccessPolicy};
use infantry_daemon::gateway::{self, legacy, Gateway, GatewayConfig, RouteTable};
use infantry_daemon::harness;
use infantry_daemon::server::{self, App};
use infantry_daemon::sessions::{Config, Daemon};
use std::path::PathBuf;
use std::sync::Arc;

pub const DEFAULT_PORT: u16 = 18990;
/// The Kotlin app's daemon port; never bound by this daemon.
pub const KOTLIN_APP_PORT: u16 = 8797;
/// The default state dir under `%LOCALAPPDATA%` (the Rust app's own, never the Kotlin app's `InfantryAI`).
pub const STATE_SUBDIR: [&str; 2] = ["SuperiorSoft", "InfantryAI-Rust"];

/// The agent-CLI harnesses this build can run, in the order the harness list shows them; the first is the default.
/// HARNESS REGISTRATION SPOT: a new provider crate adds one line here; nothing else in the daemon names a provider.
fn harness_registry() -> harness::HarnessRegistry {
    harness::HarnessRegistry::new(vec![
        Arc::new(infantry_harness_claude::ClaudeHarness::default()),
        Arc::new(infantry_harness_codex::CodexHarness::default()),
        Arc::new(infantry_harness_opencode::OpenCodeHarness::default()),
        Arc::new(infantry_harness_pi::PiHarness::default()),
    ])
}

fn default_state_dir() -> PathBuf {
    if let Some(l) = std::env::var_os("LOCALAPPDATA").filter(|v| !v.is_empty()) {
        return STATE_SUBDIR.iter().fold(PathBuf::from(l), |p, s| p.join(s));
    }
    let home = std::env::var("USERPROFILE").or_else(|_| std::env::var("HOME")).unwrap_or_else(|_| ".".into());
    PathBuf::from(home).join(".infantry-rust")
}

fn fail(msg: String) -> ! {
    eprintln!("[infantry] refusing to start: {msg}");
    std::process::exit(2);
}

fn main() {
    let a: Vec<String> = std::env::args().skip(1).collect();
    let arg = |k: &str| a.iter().position(|x| x == k).and_then(|i| a.get(i + 1)).cloned();
    if a.iter().any(|x| x == "--build-id") {
        println!("{}", infantry_daemon::server::build_id());
        return;
    }
    if a.iter().any(|x| x == "--help" || x == "-h") {
        println!("infantry-daemon [--port N] [--host H] [--mode both|runner|orchestrator] [--state-dir DIR] [--provider NAME] [--legacy-backend CMD] [--legacy-port N] [--gateway-routes JSON]");
        return;
    }
    let port: u16 = match arg("--port").or_else(|| auth::env("INFANTRY_PORT")) {
        Some(p) => p.trim().parse().unwrap_or_else(|_| fail(format!("bad port `{p}`"))),
        None => DEFAULT_PORT,
    };
    if port == KOTLIN_APP_PORT {
        fail(format!("port {KOTLIN_APP_PORT} belongs to the Kotlin app's daemon; pick another"));
    }
    // RUNNER-POOL-10: --mode, then INFANTRY_MODE; an unknown value refuses to start.
    let mode = infantry_daemon::mode::Mode::resolve(&[arg("--mode").as_deref(), auth::env("INFANTRY_MODE").as_deref()]).unwrap_or_else(|e| fail(e));
    let host = arg("--host").or_else(|| auth::env("INFANTRY_HOST")).unwrap_or_else(|| "127.0.0.1".into());
    let state_dir = arg("--state-dir").or_else(|| auth::env("INFANTRY_STATE_DIR")).map(PathBuf::from).unwrap_or_else(default_state_dir);
    if let Err(e) = std::fs::create_dir_all(&state_dir) {
        fail(format!("state dir {}: {e}", state_dir.display()));
    }
    // RUST-BUG e0c433c9: a panic is written to <state>/health/panics.log, and the next start's health event names it.
    infantry_daemon::health::install_panic_log(&state_dir);
    let registry = harness_registry();
    let provider = arg("--provider").or_else(|| auth::env("INFANTRY_PROVIDER")).unwrap_or_else(|| registry.default_id().into());
    let harness: Arc<dyn harness::Harness> = match registry.by_name(provider.trim()) {
        Some(h) => Arc::from(h),
        None => fail(format!("no harness named `{provider}`")),
    };
    let token = auth::token_from_environment().unwrap_or_else(|e| fail(e));
    let policy = AccessPolicy::new(&host, token.as_deref()).unwrap_or_else(|e| fail(e));
    let mut cfg = Config::new(state_dir.clone(), std::env::current_dir().unwrap_or_else(|_| state_dir.clone()));
    cfg.registry = registry;
    let daemon = Daemon::new(harness, cfg).unwrap_or_else(|e| fail(e));
    let rt = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap_or_else(|e| fail(e.to_string()));
    // Every unit, registered over the session manager (inside the runtime: units start background tasks).
    if let Err(e) = rt.block_on(async { infantry_daemon::compose::build_with_mode(&daemon, &state_dir, mode).map(|_| ()) }) {
        eprintln!("[infantry] units not composed, serving the sessions frames only: {e}");
    }
    let gateway = arg("--legacy-backend").or_else(|| auth::env("INFANTRY_LEGACY_BACKEND")).map(|cmdline| {
        let legacy_port: u16 = match arg("--legacy-port").or_else(|| auth::env("INFANTRY_LEGACY_PORT")) {
            Some(p) => p.trim().parse().unwrap_or_else(|_| fail(format!("bad legacy port `{p}`"))),
            None => legacy::free_port().unwrap_or_else(|e| fail(format!("no free port for the legacy backend ({e})"))),
        };
        if legacy_port == port {
            fail(format!("the legacy backend needs its own port, not {port}"));
        }
        let overrides = arg("--gateway-routes").or_else(|| auth::env(gateway::routes::ROUTES_SETTING)).unwrap_or_default();
        let d = daemon.clone();
        let routes = RouteTable::new(move |t: &str| d.owns_frame(t)).with_overrides(&overrides).unwrap_or_else(|e| fail(e));
        Gateway::new(GatewayConfig {
            legacy_command: legacy::split_command(&cmdline),
            legacy_port,
            state_dir: state_dir.clone(),
            access_token: token.clone(),
            routes,
        })
        .unwrap_or_else(|e| fail(e))
    });
    rt.block_on(async move {
        let listener = tokio::net::TcpListener::bind((host.as_str(), port)).await.unwrap_or_else(|e| fail(format!("port {port} could not be bound ({e})")));
        let bound = listener.local_addr().map(|a| a.port()).unwrap_or(port);
        let _ = std::fs::write(state_dir.join("daemon.port"), bound.to_string());
        daemon.health.started(bound, server::BUILD);
        daemon.set_launch(infantry_daemon::harness::launch::LaunchBase::for_daemon(bound, &state_dir, token.as_deref(), &auth::env));
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
        if let Some(g) = &gateway {
            println!("[infantry] gateway mode: frames no Rust unit owns go to the legacy backend on 127.0.0.1:{}", g.legacy.port);
        }
        if let Err(e) = server::serve_with(listener, app, gateway, stop).await {
            eprintln!("[infantry] server stopped: {e}");
        }
        let _ = std::fs::remove_file(state_dir.join("daemon.port"));
    });
}
