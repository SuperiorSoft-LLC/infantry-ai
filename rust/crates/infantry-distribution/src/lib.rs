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
pub mod os_lib;

use infantry_daemon::auth::{self, AccessPolicy};
use infantry_daemon::gateway::{self, legacy, Gateway, GatewayConfig, RouteTable};
use infantry_daemon::harness;
use infantry_daemon::os_app;
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

/// Ends the daemon the way ctrl-c does (the graceful path): the library's `battalion_app_stop`, or a caller of
/// [`run_daemon`] that wants it to return. A stop before the daemon listens is kept and ends it at once.
pub static STOP: tokio::sync::Notify = tokio::sync::Notify::const_new();

/// The daemon: today's `infantry-daemon` program body, run with its arguments (without the program name). Returns when it
/// has stopped. The program (`main.rs`) and the BattalionOS library entry (`os_lib`) both call it; nothing else differs.
pub fn run_daemon(a: Vec<String>) {
    if let Err(e) = run_checked(a) {
        fail(e);
    }
}

/// [`run_daemon`] for a caller that must not lose its process (the BattalionOS library): a refused start because the data
/// folder is in use (DAEMON-LOCK) is the returned error, in plain words. The lock is held until this returns, so a stop
/// followed by a start in the same process, or the host's update, takes it again. Other start failures still exit.
pub fn run_checked(a: Vec<String>) -> Result<(), String> {
    let arg = |k: &str| a.iter().position(|x| x == k).and_then(|i| a.get(i + 1)).cloned();
    if a.iter().any(|x| x == "--build-id") {
        println!("{}", infantry_daemon::server::build_id());
        return Ok(());
    }
    if a.iter().any(|x| x == "--help" || x == "-h") {
        println!("infantry-daemon [--port N] [--host H] [--mode both|runner|orchestrator] [--link-port N] [--link-bind H] [--runner-host H] [--runner-public-port N] [--state-dir DIR] [--provider NAME] [--legacy-backend CMD] [--legacy-port N] [--gateway-routes JSON] [--lock-wait-secs N]");
        return Ok(());
    }
    // BOS-7: an app of BattalionOS only when the OS says so (`BATTALION_CONTRACT`); without it nothing below changes.
    let os_env = os_app::OsEnv::from_env(&auth::env).unwrap_or_else(|e| fail(e));
    let state_dir = arg("--state-dir").or_else(|| auth::env("INFANTRY_STATE_DIR")).map(PathBuf::from).or_else(|| os_env.as_ref().and_then(|o| o.state_dir.clone())).unwrap_or_else(default_state_dir);
    if let Err(e) = std::fs::create_dir_all(&state_dir) {
        fail(format!("state dir {}: {e}", state_dir.display()));
    }
    // DAEMON-LOCK: one live Infantry AI per data folder, program or library alike; taken before any other file of the folder
    // is opened (launch.json included), held until this function returns. A bounded wait covers a holder that is closing.
    let lock_wait = arg("--lock-wait-secs").or_else(|| auth::env("INFANTRY_LOCK_WAIT_SECS")).map(|v| v.trim().parse::<u64>().map(std::time::Duration::from_secs).unwrap_or_else(|_| fail(format!("bad lock wait `{v}`")))).unwrap_or(infantry_daemon::state_lock::DEFAULT_WAIT);
    let _state_lock = infantry_daemon::state_lock::StateLock::acquire(&state_dir, lock_wait).map_err(|e| e.to_string())?;
    let launch = match &os_env {
        Some(_) => os_app::Launch::read(&state_dir).unwrap_or_else(|e| fail(e)),
        None => os_app::Launch::default(),
    };
    let port: u16 = match arg("--port").or_else(|| auth::env("INFANTRY_PORT")).or_else(|| launch.port.map(|p| p.to_string())) {
        Some(p) => p.trim().parse().unwrap_or_else(|_| fail(format!("bad port `{p}`"))),
        None => DEFAULT_PORT,
    };
    if port == KOTLIN_APP_PORT {
        fail(format!("port {KOTLIN_APP_PORT} belongs to the Kotlin app's daemon; pick another"));
    }
    // RUNNER-POOL-10: --mode, then INFANTRY_MODE; an unknown value refuses to start.
    let mode = infantry_daemon::mode::Mode::resolve(&[arg("--mode").as_deref(), auth::env("INFANTRY_MODE").as_deref()]).unwrap_or_else(|e| fail(e));
    let host = arg("--host").or_else(|| auth::env("INFANTRY_HOST")).or_else(|| launch.host.clone()).unwrap_or_else(|| "127.0.0.1".into());
    let os_runtime = os_env.map(|o| {
        // Plugins live in the state dir; the package's own are only the bundled source (set before any thread starts).
        let exe_plugins = infantry_daemon::own_files::package_dir().map(|p| p.join("plugins"));
        let bundled = ["INFANTRY_BUNDLED_PLUGINS_DIR", "INFANTRY_PLUGINS_DIR"].iter().find_map(|k| auth::env(k)).map(PathBuf::from).or_else(|| o.app_dir.as_ref().map(|d| d.join("plugins"))).or_else(|| exe_plugins.clone()).filter(|p| p.is_dir());
        let legacy = auth::env(os_app::LEGACY_PLUGINS_ENV).map(PathBuf::from).or(exe_plugins);
        let installed = state_dir.join(os_app::INSTALLED_DIR);
        match os_app::prepare_plugins(&installed, bundled.as_deref(), legacy.as_deref()) {
            Ok(r) => println!("[infantry] app mode: plugins in {} (migrated {}, synced {})", installed.display(), r.migrated.len(), r.synced.len()),
            Err(e) => fail(format!("plugins dir {}: {e}", installed.display())),
        }
        std::env::set_var("INFANTRY_PLUGINS_DIR", &installed);
        if let Some(b) = &bundled {
            std::env::set_var("INFANTRY_BUNDLED_PLUGINS_DIR", b);
        }
        os_app::OsRuntime::new(o).unwrap_or_else(|e| fail(e))
    });
    // RUST-BUG e0c433c9: a panic is written to <state>/health/panics.log, and the next start's health event names it.
    infantry_daemon::health::install_panic_log(&state_dir);
    let registry = harness_registry();
    let provider = arg("--provider").or_else(|| auth::env("INFANTRY_PROVIDER")).unwrap_or_else(|| registry.default_id().into());
    let harness: Arc<dyn harness::Harness> = match registry.by_name(provider.trim()) {
        Some(h) => Arc::from(h),
        None => fail(format!("no harness named `{provider}`")),
    };
    let mut token = auth::token_from_environment().unwrap_or_else(|e| fail(e));
    if token.is_none() {
        // BOS-7: the launch file's token file (phone access) when no `INFANTRY_TOKEN*` is set.
        if let Some(f) = &launch.token_file {
            let t = std::fs::read_to_string(f).unwrap_or_else(|e| fail(format!("launch.json tokenFile {}: {e}", f.display())));
            token = Some(t.trim_start_matches('\u{feff}').trim().to_string()).filter(|t| !t.is_empty());
            if token.is_none() {
                fail(format!("launch.json tokenFile {} is empty", f.display()));
            }
        }
    }
    let mut policy = AccessPolicy::new(&host, token.as_deref()).unwrap_or_else(|e| fail(e));
    if let Some(origin) = os_runtime.as_ref().and_then(|o| o.env.ui_origin.as_deref()) {
        policy = policy.with_app_origin(origin);
    }
    let mut cfg = Config::new(state_dir.clone(), std::env::current_dir().unwrap_or_else(|_| state_dir.clone()));
    cfg.registry = registry;
    let daemon = Daemon::new(harness, cfg).unwrap_or_else(|e| fail(e));
    let rt = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap_or_else(|e| fail(e.to_string()));
    // Every unit, registered over the session manager (inside the runtime: units start background tasks).
    match rt.block_on(async { infantry_daemon::compose::build_with_mode(&daemon, &state_dir, mode) }) {
        Ok(composed) => register_runner_pool(&composed, &state_dir, mode, &arg, rt.handle().clone()),
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
        if let Some(os) = &os_runtime {
            if let Err(e) = os.write_ready(&host, bound) {
                fail(format!("ready file: {e}"));
            }
        }
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
        let os_stop = os_runtime.clone();
        let stop = async move {
            match os_stop {
                // An app daemon ends on the OS's stop call too (the same graceful path as ctrl-c).
                Some(os) => tokio::select! { _ = tokio::signal::ctrl_c() => {}, _ = os.stopped() => {}, _ = STOP.notified() => {} },
                None => {
                    tokio::select! { _ = tokio::signal::ctrl_c() => {}, _ = STOP.notified() => {} }
                }
            }
        };
        if let Some(g) = &gateway {
            println!("[infantry] gateway mode: frames no Rust unit owns go to the legacy backend on 127.0.0.1:{}", g.legacy.port);
        }
        if let Err(e) = server::serve_os(listener, app, gateway, os_runtime.clone(), stop).await {
            eprintln!("[infantry] server stopped: {e}");
        }
        let _ = std::fs::remove_file(state_dir.join("daemon.port"));
        if let Some(os) = &os_runtime {
            os.clear_ready();
        }
    });
    Ok(())
}

/// RUNNER POOL REGISTRATION SPOT (RR-1): the paired remote runners and the owner-only `runner_admin` frame. The daemon core
/// never names the pool; this root adds it as a late unit. A runner-only daemon has no runners to drive.
fn register_runner_pool(composed: &Arc<infantry_daemon::compose::Composed>, state_dir: &std::path::Path, mode: infantry_daemon::mode::Mode, arg: &dyn Fn(&str) -> Option<String>, handle: tokio::runtime::Handle) {
    if mode == infantry_daemon::mode::Mode::Runner {
        serve_runner_link(composed, state_dir, arg);
        return;
    }
    let pool = infantry_runner_pool::RunnerPool::open(state_dir);
    let frames = [infantry_runner_pool::ADMIN_FRAME.to_string()];
    composed.late.add("runner-pool", &frames, Arc::new(infantry_runner_pool::AdminUnit { pool: pool.clone(), lend: composed.daemon.lender().map(|l| Arc::new(CheckLendOver(l, composed.daemon.providers())) as Arc<dyn infantry_runner_pool::CheckLend>) }));
    // RR-4: the paired runners join the registry a session is placed through (`local` is already there).
    composed.daemon.runner_registry().add_source(pool.clone());
    // RR-7: a ticket pinned to a paired runner works in its own branch ON that runner (clone cache, worktree, push).
    composed.daemon.set_remote_workspace(infantry_runner_pool::workspace::RemoteWorkspaces::new(composed.remote_host.clone(), handle));
    // RR-13: `runner_frame {runner, frame, data}`; `local` answers in-process through this daemon's own frames.
    let registry = composed.daemon.runner_registry();
    registry.set_local_scope(Arc::new(infantry_runner_source::FnScope(Box::new(owner_ask(composed)))));
    composed.late.add("runner-frame", &[infantry_runner_pool::RUNNER_FRAME.to_string()], Arc::new(infantry_runner_pool::RunnerFrameUnit { registry }));
    println!("[infantry] runner pool registered (runner_admin, runner_frame, placement)");
    // RR-8: sessions that were running on a paired runner when this daemon stopped are re-attached from their saved cursors.
    composed.daemon.reattach_placed();
    // The heartbeat interval is the setting `limits.runners.heartbeatSec`, read every round (unset or bad = the default).
    let c = composed.clone();
    let c2 = composed.clone();
    pool.set_allow_plain(Arc::new(move || plain_allowed(&|k: &str| read_setting(&c2, k))));
    let c3 = composed.clone();
    pool.set_update_wait(Arc::new(move || read_setting(&c3, infantry_runner_pool::update::UPDATE_WAIT_KEY).and_then(|v| v.as_u64().or_else(|| v.as_str().and_then(|s| s.trim().parse().ok()))).unwrap_or(0)));
    // RUNNER-OFFLINE-1: a session on a runner that stays offline this long (seconds) fails with "the runner went offline".
    let c4 = composed.clone();
    composed.daemon.start_runner_watch(Arc::new(move || read_setting(&c4, "limits.runners.offlineSessionSec").and_then(|v| v.as_u64().or_else(|| v.as_str().and_then(|s| s.trim().parse().ok()))).filter(|n| *n > 0).unwrap_or(60)));
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

/// One frame of this daemon, asked as owner: the first answer's (type, data), or why not (RR-13).
fn owner_ask(composed: &Arc<infantry_daemon::compose::Composed>) -> impl Fn(&str, &str) -> Result<(String, String), String> + Send + Sync + 'static {
    let c = composed.clone();
    move |frame: &str, data: &str| {
        let mut ask = infantry_protocol::envelope::Envelope::new(frame, data.to_string());
        // A unit that answers a change by push (the vault's `secret_set`) returns its reply only to a request that has an id.
        static SEQ: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);
        ask.request_id = Some(format!("runner-frame-{}", SEQ.fetch_add(1, std::sync::atomic::Ordering::Relaxed)));
        let first = c.request(&ask, true).map_err(|e| e.to_string())?.into_iter().next();
        first.map(|e| (e.r#type.clone(), e.data)).ok_or_else(|| format!("{frame} answered nothing"))
    }
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
    // RR-6: this runner's owner decides what it takes of the credentials an orchestrator lends: `runner.acceptCredentials`
    // (default true) and, per harness, keeping its own sign-in (`harness.<id>.authMode=subscription-login`). Read per start.
    let (c_accept, c_login) = (composed.clone(), composed.clone());
    let lend = infantry_runner_link::process::LendPolicy {
        accept: Arc::new(move || read_setting(&c_accept, "runner.acceptCredentials").map_or(true, |v| v.as_bool().unwrap_or_else(|| !v.as_str().is_some_and(|s| s.trim().eq_ignore_ascii_case("false"))))),
        keeps_own_login: Arc::new(move |harness| read_setting(&c_login, &format!("harness.{harness}.authMode")).and_then(|v| v.as_str().map(str::to_string)).is_some_and(|m| m == infantry_harness::AUTH_SUBSCRIPTION)),
    };
    // RR-8: the link sessions' stream marks live in the runner-link folder; ids carry a per-boot tag so a restart never reuses one.
    let boot_tag = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| format!("{:x}", d.as_millis())).unwrap_or_default();
    let backend = infantry_runner_link::process::ProcessBackend::new(Arc::new(infantry_runner_local::LocalRunner::default().with_id_tag(boot_tag)))
        .with_state_dir(&state_dir.join(infantry_runner_pool::LINK_DIR))
        .with_lend_policy(lend)
        // ORPHAN-1: a process no paired orchestrator has asked after for this long (seconds; 0 = never) is ended and logged.
        .with_orphan_reaper({
            let c_orphan = composed.clone();
            Arc::new(move || read_setting(&c_orphan, "limits.runners.orphanGraceSec").and_then(|v| v.as_u64().or_else(|| v.as_str().and_then(|s| s.trim().parse().ok()))).unwrap_or(900))
        });
    // RR-13: this runner answers its own harness/provider/key frames over the link, only the ones the plugin offers.
    let backend = infantry_runner_pool::FrameBackend::new(Box::new(backend), Arc::new(owner_ask(composed)));
    match infantry_runner_pool::RunnerServe::start(state_dir, Box::new(backend), opts) {
        Ok(serve) => {
            let frames = [infantry_runner_pool::ADMIN_FRAME.to_string()];
            composed.late.add("runner-pool", &frames, Arc::new(infantry_runner_pool::RunnerSideAdmin { serve }));
        }
        Err(e) => fail(e),
    }
}

/// RR-6: the daemon's lending policy as the runner check sees it.
struct CheckLendOver(Arc<infantry_daemon::lender::Lender>, Vec<String>);

impl infantry_runner_pool::CheckLend for CheckLendOver {
    fn harnesses(&self) -> Vec<String> {
        self.1.clone()
    }

    fn git_credentials(&self, runner: &str, project: Option<&str>) -> Option<serde_json::Value> {
        self.0.git_credentials(runner, project).and_then(|l| serde_json::to_value(l).ok())
    }

    fn source(&self, runner: &str, harness: &str, runner_has_key: bool) -> infantry_runner_pool::CredentialSource {
        use infantry_runner_pool::CredentialSource::*;
        if self.0.lends_key(runner, harness) {
            Lent
        } else if runner_has_key {
            StoredOnRunner
        } else {
            None
        }
    }
}
