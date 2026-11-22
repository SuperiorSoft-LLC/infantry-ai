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
        println!("infantry-daemon [--port N] [--host H] [--mode both|runner|orchestrator] [--link-port N] [--link-bind H] [--runner-host H] [--runner-public-port N] [--state-dir DIR] [--provider NAME] [--legacy-backend CMD] [--legacy-port N] [--gateway-routes JSON]");
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
    match rt.block_on(async { infantry_daemon::compose::build_with_mode(&daemon, &state_dir, mode) }) {
        Ok(composed) => register_runner_pool(&composed, &state_dir, mode, &arg),
        Err(e) => eprintln!("[infantry] units not composed, serving the sessions frames only: {e}"),
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

/// RUNNER POOL REGISTRATION SPOT (RR-1): the paired remote runners and the owner-only `runner_admin` frame. The daemon core
/// never names the pool; this root adds it as a late unit. A runner-only daemon has no runners to drive.
fn register_runner_pool(composed: &Arc<infantry_daemon::compose::Composed>, state_dir: &std::path::Path, mode: infantry_daemon::mode::Mode, arg: &dyn Fn(&str) -> Option<String>) {
    if mode == infantry_daemon::mode::Mode::Runner {
        serve_runner_link(composed, state_dir, arg);
        return;
    }
    let pool = infantry_runner_pool::RunnerPool::open(state_dir);
    let frames = [infantry_runner_pool::ADMIN_FRAME.to_string()];
    composed.late.add("runner-pool", &frames, Arc::new(infantry_runner_pool::AdminUnit { pool: pool.clone() }));
    println!("[infantry] runner pool registered (runner_admin)");
    // The heartbeat interval is the setting `limits.runners.heartbeatSec`, read every round (unset or bad = the default).
    let c = composed.clone();
    let c2 = composed.clone();
    pool.set_allow_plain(Arc::new(move || plain_allowed(&|k: &str| read_setting(&c2, k))));
    pool.start_heartbeat(Arc::new(move || {
        let ask = infantry_protocol::envelope::Envelope::new("settings", serde_json::json!({"key": infantry_runner_pool::HEARTBEAT_KEY}).to_string());
        c.request(&ask, true)
            .ok()
            .and_then(|r| r.into_iter().next())
            .and_then(|e| serde_json::from_str::<serde_json::Value>(&e.data).ok())
            .and_then(|v| v["value"].as_u64().or_else(|| v["value"].as_str().and_then(|s| s.trim().parse().ok())))
            .filter(|n| *n > 0)
            .unwrap_or(infantry_runner_pool::DEFAULT_HEARTBEAT_SEC)
    }));
}

fn read_setting(composed: &Arc<infantry_daemon::compose::Composed>, key: &str) -> Option<serde_json::Value> {
    let ask = infantry_protocol::envelope::Envelope::new("settings", serde_json::json!({"key": key}).to_string());
    let v = composed.request(&ask, true).ok()?.into_iter().next().and_then(|e| serde_json::from_str::<serde_json::Value>(&e.data).ok())?;
    Some(v["value"].clone()).filter(|v| !v.is_null())
}

/// The setting `runners.allowPlainTailnet` (default false): a boolean or the text "true".
fn plain_allowed(setting: &dyn Fn(&str) -> Option<serde_json::Value>) -> bool {
    setting(infantry_runner_pool::ALLOW_PLAIN_KEY).is_some_and(|v| v.as_bool().unwrap_or_else(|| v.as_str().is_some_and(|s| s.trim().eq_ignore_ascii_case("true"))))
}

/// RUNNER SERVE SPOT (RR-2): a `--mode runner` daemon serves the runner link from start, over its own LocalRunner. The port
/// is `--link-port`, else `INFANTRY_RUNNER_LINK_PORT`, else the setting `limits.runners.linkPort` (8790); the bind host
/// `--link-bind`, `INFANTRY_RUNNER_BIND`, the setting `runners.linkBindHost` (127.0.0.1). The printed endpoint is
/// `--runner-host` / `--runner-public-port`, then `INFANTRY_RUNNER_HOST` / `INFANTRY_RUNNER_PUBLIC_PORT`, then the setting
/// `runners.advertiseHosts`, then `localhost`.
fn serve_runner_link(composed: &Arc<infantry_daemon::compose::Composed>, state_dir: &std::path::Path, arg: &dyn Fn(&str) -> Option<String>) {
    let setting = |key: &str| -> Option<serde_json::Value> {
        let ask = infantry_protocol::envelope::Envelope::new("settings", serde_json::json!({"key": key}).to_string());
        let v = composed.request(&ask, true).ok()?.into_iter().next().and_then(|e| serde_json::from_str::<serde_json::Value>(&e.data).ok())?;
        Some(v["value"].clone()).filter(|v| !v.is_null() && v.as_str().map_or(true, |s| !s.trim().is_empty()))
    };
    let num = |v: Option<serde_json::Value>| v.and_then(|v| v.as_u64().or_else(|| v.as_str().and_then(|s| s.trim().parse().ok()))).and_then(|n| u16::try_from(n).ok());
    let env = |k: &str| infantry_daemon::auth::env(k).filter(|s| !s.trim().is_empty());
    let port = arg("--link-port")
        .or_else(|| env("INFANTRY_RUNNER_LINK_PORT"))
        .and_then(|s| s.trim().parse().ok())
        .or_else(|| num(setting(infantry_runner_pool::LINK_PORT_KEY)))
        .unwrap_or(infantry_runner_pool::DEFAULT_LINK_PORT);
    if [8787, 8788, 8797, 8798].contains(&port) {
        fail(format!("the runner link port {port} belongs to the owner's daemon or PM; pick another (limits.runners.linkPort)"));
    }
    let bind_host = arg("--link-bind")
        .or_else(|| env("INFANTRY_RUNNER_BIND"))
        .or_else(|| setting(infantry_runner_pool::LINK_BIND_KEY).and_then(|v| v.as_str().map(str::to_string)))
        .unwrap_or_else(|| infantry_runner_pool::DEFAULT_LINK_BIND.into());
    let opts = infantry_runner_pool::ServeOptions {
        bind_host,
        port,
        name: env("INFANTRY_RUNNER_NAME"),
        public_host: arg("--runner-host").or_else(|| env("INFANTRY_RUNNER_HOST")),
        public_port: arg("--runner-public-port").or_else(|| env("INFANTRY_RUNNER_PUBLIC_PORT")).and_then(|s| s.trim().parse().ok()),
        advertise_hosts: setting(infantry_runner_pool::ADVERTISE_KEY).map(|v| infantry_runner_pool::advertise_hosts(&v)).unwrap_or_default(),
        allow_projects: env("INFANTRY_RUNNER_ALLOW_PROJECTS").unwrap_or_else(|| "*".into()),
        allow_harnesses: env("INFANTRY_RUNNER_ALLOW_HARNESSES").unwrap_or_else(|| "*".into()),
        app_version: env!("CARGO_PKG_VERSION").into(),
        allow_plain: plain_allowed(&|k: &str| setting(k)),
    };
    let backend = infantry_runner_link::process::ProcessBackend::new(Arc::new(infantry_runner_local::LocalRunner::default()));
    match infantry_runner_pool::RunnerServe::start(state_dir, Box::new(backend), opts) {
        Ok(serve) => {
            let frames = [infantry_runner_pool::ADMIN_FRAME.to_string()];
            composed.late.add("runner-pool", &frames, Arc::new(infantry_runner_pool::RunnerSideAdmin { serve }));
        }
        Err(e) => fail(e),
    }
}
