// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The composition root (RUST-WIRE-1): every unit built for the port, registered in one infantry-core [`Registry`]
//! with its real dependencies. The daemon's session manager stays the owner of the sessions frames; everything else
//! a client sends goes through the registry here (on the blocking pool), and every push a unit makes on the core
//! [`EventBus`] reaches every client.
//!
//! What is wired: projects + project git + worktrees (infantry-projects), the board with its work half (Dispatcher:
//! starts, lanes, queues, the done protocol) over a session launcher and a workspace provider built on the session
//! manager and the projects' ticket branches, usage, mail and the notification units, scripting (tools, hooks,
//! timers, `mail.incoming`, exec), the plugin host (WASM daemon halves of `plugins/official`), and the tool frames
//! (`plugin_tools` / `plugin_tool_call`) over one tool catalog.

mod adapters;
mod agent_frames;
mod agent_registry;
mod agent_tools;
mod harness_frames;
mod harness_install;
pub mod host_frames;
pub mod project_files;
pub mod timeline;
pub mod plugins;
mod prompter;
mod settings;
pub mod scope;
mod tools;
mod watch;
pub mod worktree_sweep;

use crate::sessions::{Daemon, FrameUnits};
use crate::wire;
use infantry_board::{Board as TicketBoard, BoardUnit, Dispatcher, LocalOrchestrator};
use infantry_core::db::Db;
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Registry};
use infantry_core::bus::EventBus;
use infantry_mail::mail::mailbox::Mailbox;
use infantry_mail::mail::router::{IncomingVerdict, MailRouter, RouterConfig};
use infantry_mail::mail::store::JsonMailStore;
use infantry_mail::notify::approvals::ApprovalRules;
use infantry_mail::notify::attention::AttentionStore;
use infantry_mail::notify::hub::{NotificationHub, NotificationSettings};
use infantry_mail::notify::prompts::{ApprovalRulesHook, PendingPrompts, TimeoutPolicy};
use infantry_mail::notify::push::{NotifyService, NotifySettings};
use infantry_mail::notify::questions::{Asker, OwnerQuestions, QuestionLimits};
use infantry_mail::units::{AttentionUnit, MailUnit, NotificationsUnit, NotifyUnit, PromptsUnit, QuestionsUnit};
use infantry_projects::git::SystemGit;
use infantry_projects::service::GitViewConfig;
use infantry_projects::Projects;
use infantry_scripting::dispatcher::ScriptDispatcher;
use infantry_scripting::engine::{ScriptEngines, ScriptLimits};
use infantry_scripting::exec::ExecTools;
use infantry_scripting::hooks::{BuiltinHook, HookStore, HooksConfig, IncomingMail, ScriptHooks};
use infantry_scripting::runlog::ScriptRunLog;
use infantry_scripting::store::{self as script_store, ScriptStore};
use infantry_scripting::tools::ScriptingTools;
use infantry_usage::service::UsageDeps;
use infantry_usage::{UsageService, UsageUnit};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

pub use tools::{Tools, PLUGIN_TOOL_CALL};

/// Core bus capacity (frames + named events buffered per subscriber).
pub const BUS_CAPACITY: usize = 4096;
/// How often the mail router re-checks deferred wakes.
pub const MAIL_TICK_MS: u64 = 5_000;
/// How often the generic `clock.tick` bus event is emitted (plugins pump timers on it: the plugin ABI has no timer).
pub const CLOCK_TICK_MS: u64 = 1_000;
/// The named event of the daemon's clock, payload `{"at": <epoch ms>}`.
pub const CLOCK_TICK: &str = "clock.tick";
/// The shortest timer-hook period (`scripting.hooks.timerMinSeconds` default).
pub const TIMER_MIN_SECS: u64 = 60;
/// Where plugin daemon halves are installed (`<dir>/official/<Module>/module.json`); else `<exe dir>/plugins`.
pub const PLUGINS_DIR_ENV: &str = "INFANTRY_PLUGINS_DIR";
/// The repository ticket starts cut from when a project's own folder is not one.
pub const REPO_ENV: &str = "INFANTRY_REPO";

pub fn now_ms() -> i64 {
    crate::sessions::now_ms()
}

/// Everything the composition built; the daemon holds it as its [`FrameUnits`].
pub struct Composed {
    pub state_dir: PathBuf,
    pub db: Arc<Db>,
    pub bus: EventBus,
    pub registry: Registry,
    pub daemon: Arc<Daemon>,
    pub projects: Arc<Projects>,
    pub board: Arc<TicketBoard>,
    pub dispatcher: Arc<Dispatcher>,
    pub orchestrator: Arc<LocalOrchestrator>,
    pub usage: Arc<UsageService>,
    pub mail: Arc<MailRouter>,
    pub hooks: Arc<ScriptHooks>,
    pub tools: Arc<Tools>,
    pub knowledge: Arc<infantry_knowledge::Knowledge>,
    pub plugins: Option<Arc<infantry_plugin_host::PluginHost>>,
    /// The open owner prompts (a new client is shown them).
    pub prompts: Arc<PendingPrompts>,
    next_conn: AtomicU64,
}

fn clock() -> Arc<dyn Fn() -> i64 + Send + Sync> {
    Arc::new(now_ms)
}

fn log_line(m: &str) {
    eprintln!("[infantry] {m}");
}

/// `<dir>` of a project (its `directory`), when it names one.
pub fn project_dir(projects: &Projects, id: &str) -> Option<PathBuf> {
    let p = projects.store.get(id).ok()?;
    let d = p.directory.trim();
    (!d.is_empty()).then(|| PathBuf::from(d))
}

fn plugins_dir() -> Option<PathBuf> {
    if let Some(d) = crate::auth::env(PLUGINS_DIR_ENV) {
        return Some(PathBuf::from(d));
    }
    std::env::current_exe().ok()?.parent().map(|p| p.join("plugins"))
}

/// PM-ROLE-1: the role catalog file of every module that declares one (`module.json` `roleCatalog`, a path inside the
/// module folder), in tier precedence order.
fn role_catalogs(plugins_dir: &Option<PathBuf>) -> Vec<PathBuf> {
    host_frames::scan_modules(plugins_dir)
        .into_iter()
        .filter_map(|m| {
            let rel = host_frames::s(&m.manifest, "roleCatalog");
            if rel.is_empty() || rel.contains("..") || Path::new(rel).is_absolute() {
                return None;
            }
            Some(m.dir.join(rel)).filter(|p| p.is_file())
        })
        .collect()
}

/// Builds every unit over `daemon` and attaches them to it. Call inside the tokio runtime.
pub fn build(daemon: &Arc<Daemon>, state_dir: &Path) -> Result<Arc<Composed>, String> {
    build_with_mode(daemon, state_dir, crate::mode::Mode::Both)
}

/// [`build`] for a deployment mode: a runner daemon keeps the orchestration units only to refuse their frames.
pub fn build_with_mode(daemon: &Arc<Daemon>, state_dir: &Path, mode: crate::mode::Mode) -> Result<Arc<Composed>, String> {
    let handle = tokio::runtime::Handle::current();
    let db = Arc::new(Db::open(state_dir.join("infantry.db")).map_err(|e| format!("infantry.db: {e}"))?);
    // A fresh state dir: every table exists before any unit reads or adds its own.
    if crate::fresh_schema::ensure(&db).map_err(|e| format!("infantry.db: {e}"))? {
        log_line("infantry.db: a fresh database; the full schema was created");
    }
    let bus = EventBus::new(BUS_CAPACITY);
    for h in crate::harness::all_except(daemon.harness.name()) {
        daemon.add_harness(h);
    }
    let repo = crate::auth::env(REPO_ENV).map(PathBuf::from);

    // ---- projects ------------------------------------------------------------------------------------------
    let projects = Arc::new(Projects::new(SystemGit::shared(), Some(db.clone()), GitViewConfig::default())?);
    let mut projects_unit = projects.projects_unit(repo.clone());
    {
        let d = daemon.clone();
        projects_unit.routing = Arc::new(move |rules| adapters::validate_routing(&d, rules));
        let d = daemon.clone();
        projects_unit.live_sessions = Arc::new(move |project: &str| {
            d.records().iter().filter(|r| r.live() && r.project_id.as_deref() == Some(project)).map(|r| (r.id.clone(), r.role.clone().unwrap_or_default())).collect()
        });
    }
    let mut git_unit = projects.git_unit();
    {
        let d = daemon.clone();
        git_unit.session_checkout = Arc::new(move |sid: &str| {
            let r = d.record(sid)?;
            Some(r.worktree_path.clone().filter(|p| !p.trim().is_empty()).unwrap_or(r.working_dir))
        });
    }
    // The worktree registry lives in memory: rebuild it from the persisted sessions (Kotlin's startup reconcile), or
    // the hello `worktrees` frame lists nothing after a restart or a migration.
    projects.worktrees.reconcile(&daemon.records().iter().filter_map(adapters::worktree_ref).collect::<Vec<_>>());
    let worktrees_unit = projects.worktrees_unit(repo.clone());

    // ---- usage ---------------------------------------------------------------------------------------------
    let usage = {
        let d = daemon.clone();
        let meters: infantry_usage::service::WorkerMeters = Arc::new(move || {
            d.records()
                .iter()
                .filter(|r| adapters::counts_as_worker(r))
                .map(|r| infantry_usage::pacing::meter_for(&r.provider, r.model.as_deref()))
                .collect()
        });
        let svc = UsageService::open(UsageDeps::new(state_dir, meters));
        // The Stats "Job watcher" line: the mail crate's watcher counters on the meter report.
        svc.set_watch_counts(Arc::new(|| infantry_mail::mail::watch_meter::WATCH_METER.counts()));
        svc
    };

    // ---- the board + its work half -------------------------------------------------------------------------
    let env = Arc::new(adapters::DaemonBoardEnv::new(daemon.clone(), projects.clone(), state_dir, usage.clone(), db.clone()));
    let board = Arc::new(TicketBoard::new(db.clone(), env.clone()));
    let workspaces = Arc::new(adapters::DaemonWorkspaces { projects: projects.clone(), repo: repo.clone() });
    let launcher = Arc::new(adapters::DaemonLauncher { daemon: daemon.clone(), handle: handle.clone(), worktrees: projects.worktrees.clone() });
    let dispatcher = Dispatcher::new(board.clone(), workspaces, launcher, bus.clone());
    let orchestrator = Arc::new(LocalOrchestrator::with_dispatcher(dispatcher.clone(), bus.clone()));

    // ---- mail + notifications -------------------------------------------------------------------------------
    let mailbox = Arc::new(Mailbox::new(Arc::new(JsonMailStore::in_state_dir(state_dir))));
    mailbox.load();
    let dir = Arc::new(adapters::Directory { daemon: daemon.clone(), projects: projects.clone() });
    let turns = Arc::new(adapters::Turns { daemon: daemon.clone(), handle: handle.clone() });
    let mail = Arc::new(MailRouter::new(mailbox, dir, turns, Arc::new(RouterConfig::default)));
    MailUnit::attach_bus(&mail, bus.clone());

    let attention = Arc::new(AttentionStore::new(db.clone(), clock()).map_err(|e| format!("attention: {e}"))?);
    {
        let b = bus.clone();
        attention.set_broadcast(move |item| {
            b.publish(Envelope::new(infantry_protocol::frames::ATTENTION, serde_json::to_string(item).unwrap_or_default()));
        });
    }
    let questions = Arc::new(
        OwnerQuestions::new(db.clone(), Arc::new(adapters::Questions { daemon: daemon.clone(), handle: handle.clone() }), Arc::new(QuestionLimits::default), clock())
            .map_err(|e| format!("questions: {e}"))?,
    );
    {
        let b = bus.clone();
        questions.set_sink(move |q| {
            b.publish(Envelope::new(infantry_protocol::frames::QUESTION, serde_json::to_string(q).unwrap_or_default()));
        });
    }
    let asker_of: infantry_mail::notify::units::AskerOf = {
        let d = daemon.clone();
        Arc::new(move |sid: &str| match d.record(sid) {
            Some(r) => Asker { session_id: sid.into(), agent_id: r.agent_id.clone(), role: r.role.clone(), project_id: r.project_id.clone() },
            None => Asker { session_id: sid.into(), ..Default::default() },
        })
    };
    let rules = Arc::new(ApprovalRules::new(db.clone(), clock()).map_err(|e| format!("approval rules: {e}"))?);
    let prompts = {
        let d = daemon.clone();
        let hook: Arc<dyn ApprovalRulesHook> = rules.clone();
        Arc::new(PendingPrompts::new(Some(hook), Arc::new(move |sid: &str| d.record(sid).and_then(|r| r.project_id)), Arc::new(|_: &str| TimeoutPolicy::default()), clock()))
    };
    {
        let b = bus.clone();
        prompts.set_sink(move |ty, data, session| {
            let mut e = Envelope::new(ty, data);
            e.session_id = (!session.is_empty()).then(|| session.to_string());
            b.publish(e);
        });
    }
    daemon.set_prompter(Arc::new(prompter::SessionPrompter {
        prompts: prompts.clone(),
        settings: settings::SettingsUnit { db: db.clone() },
        handle: handle.clone(),
    }));
    // The `notify.*` settings (Infantry_Notify module.json), read on every request so a change applies at once.
    let notify_settings = {
        let s = settings::SettingsUnit { db: db.clone() };
        move || {
            let d = NotifySettings::default();
            let txt = |k: &str| s.app(k).map(|v| v.as_str().map(String::from).unwrap_or_else(|| v.to_string()));
            let num = |k: &str| s.app(k).and_then(|v| v.as_i64().or_else(|| v.as_str().and_then(|x| x.trim().parse().ok())));
            NotifySettings {
                enabled: s.app("notify.enabled").map(|v| v != serde_json::json!(false) && v != serde_json::json!("false")).unwrap_or(d.enabled),
                quiet_hours: txt("notify.quietHours").unwrap_or(d.quiet_hours),
                default_sound: txt("notify.defaultSound").unwrap_or(d.default_sound),
                milestone_sound: txt("notify.milestoneSound").unwrap_or(d.milestone_sound),
                queue_max_age_minutes: num("notify.queueMaxAgeMinutes").filter(|n| *n >= 0).unwrap_or(d.queue_max_age_minutes),
                queue_max: num("notify.queueMax").filter(|n| *n >= 0).map(|n| n as usize).unwrap_or(d.queue_max),
                max_sound_bytes: num("notify.maxSoundBytes").filter(|n| *n >= 0).map(|n| n as usize).unwrap_or(d.max_sound_bytes),
            }
        }
    };
    let notify = Arc::new(NotifyService::new(state_dir.to_path_buf(), Arc::new(notify_settings), clock(), Arc::new(adapters::minute_of_day)));
    env.set_notify(notify.clone());
    {
        let b = bus.clone();
        // Kotlin NOTIFY-1: devices listen for `notify_push` (the request frame `notify` only answers its sender).
        notify.set_push(move |m| {
            b.publish(Envelope::new(infantry_mail::notify::push::NOTIFY_PUSH, serde_json::to_string(m).unwrap_or_default()));
        });
    }
    let hub = Arc::new(NotificationHub::new(Arc::new(NotificationSettings::default)));
    {
        let b = bus.clone();
        hub.set_broadcast(move |ty, data| {
            b.publish(Envelope::new(ty, data));
        });
    }

    // ---- knowledge (roles, traits, skills, prompts, avatars) and the secrets vault ------------------------
    // PM-ROLE-1: role texts come from the plugins that ship a catalog (module.json `roleCatalog`); core holds none.
    let role_bundle = {
        let (bundle, problems) = infantry_knowledge::roles::plugin_bundle(&role_catalogs(&plugins_dir()));
        for p in problems {
            log_line(&format!("role catalog skipped: {p}"));
        }
        bundle
    };
    let knowledge = Arc::new(infantry_knowledge::Knowledge::open_with_roles(db.clone(), state_dir, Arc::new(adapters::KnowledgePorts { daemon: daemon.clone() }), role_bundle));
    {
        let roles = knowledge.roles.clone();
        daemon.set_role_brief(Arc::new(move |id: &str| roles.role(id).filter(|r| !r.disabled).map(|r| r.persona)));
    }
    let vault_projects = projects.clone();
    let vault = Arc::new(infantry_vault::unit::VaultUnit::open(
        state_dir,
        Default::default(),
        Default::default(),
        Arc::new(move |p: Option<&str>| p.and_then(|p| project_dir(&vault_projects, p))),
    ));

    // ---- the MCP registry: owned + plugin servers, grants, per-session plans (walled by default) ------------
    let mcp = {
        let ks = vault.keystore().clone();
        let secret: crate::mcp::SecretFn = Arc::new(move |name, project| ks.resolve(name, project).ok().flatten().and_then(|f| f.get(infantry_vault::keystore::VALUE_FIELD).cloned()));
        let d = daemon.clone();
        let session: crate::mcp::SessionFn = Arc::new(move |id| d.record(id).map(|r| crate::mcp::SessionCtx::of(&r)));
        let pdir = plugins_dir();
        let plugin_servers: crate::mcp::PluginServersFn = Arc::new(move || pdir.as_deref().map(crate::mcp::plugin_servers).unwrap_or_default());
        Arc::new(crate::mcp::Registry::new(state_dir, secret, plugin_servers, session))
    };
    daemon.set_mcp(mcp.clone());
    // No shared cargo target: every worktree builds in its own `target` (a shared one is locked by cargo, so workers
    // would wait on each other). The sweep deletes a finished ticket's target folders.

    // ---- the account hub: providers modules declare, device sign-in, refresh tokens in the vault ----------
    let account = crate::account::Account::new(
        plugins_dir().as_deref().map(|d| crate::account::declared_providers(d, &|m| log_line(m))).unwrap_or_default(),
        Arc::new(crate::account::UreqHttp),
        Arc::new(crate::account::VaultTokens { keystore: vault.keystore().clone() }),
    );

    // ---- pods: DAGs of unattended sessions; open pods from the last run are INTERRUPTED -------------------
    let pods = crate::pods::Pods::new(db.clone(), Arc::new(adapters::PodSessions { daemon: daemon.clone(), bus: bus.clone() }));
    let interrupted = pods.recover();
    if !interrupted.is_empty() {
        log_line(&format!("pods interrupted by the restart: {}", interrupted.len()));
    }
    daemon.observe(Arc::new(crate::pods::PodObserver::new(pods.clone())));

    // ---- the tool catalog + scripting ---------------------------------------------------------------------
    let agent_registry = Arc::new(agent_registry::AgentRegistry { plugins_dir: plugins_dir(), roles: knowledge.roles.clone(), state_dir: state_dir.to_path_buf() });
    let tools = Arc::new(Tools::new(daemon.clone()));
    let runs = {
        let p = projects.clone();
        crate::run::RunManager::new(state_dir, daemon.cfg.default_cwd.clone(), bus.clone(), daemon.clone(), Arc::new(move |id: &str| project_dir(&p, id)))
    };
    runs.set_extras(crate::run::prep::Extras { vault: vault.clone(), projects: projects.clone() });
    tools.set_runs(runs.clone());
    {
        let p = projects.clone();
        tools.set_agents(Arc::new(agent_tools::AgentTools {
            daemon: daemon.clone(),
            work: dispatcher.clone(),
            bus: bus.clone(),
            usage: usage.clone(),
            mail: mail.clone(),
            registry: agent_registry.clone(),
            pods: pods.clone(),
            attention: attention.clone(),
            project_dir: Arc::new(move |id: &str| project_dir(&p, id)),
        }));
    }
    tools.set_knowledge(knowledge.clone());
    let engines = ScriptEngines::standard();
    let exts = engines.extensions();
    let global_dir = state_dir.join("global-scripts");
    let project_dirs: script_store::ProjectDirs = {
        let p = projects.clone();
        Arc::new(move |id: &str| project_dir(&p, id).map(|d| d.join(".infantry")))
    };
    let ext_list = exts.clone();
    // Scripts live in the database (title, description, versions, stats); the folders are read once by the import below.
    let library = Arc::new(infantry_scripting::library::ScriptLibrary::new(db.clone()).map_err(|e| format!("script library: {e}"))?);
    let scripts = ScriptStore::new(project_dirs.clone(), Arc::new(move || ext_list.clone())).with_library(library.clone());
    {
        let known: Vec<(String, PathBuf)> = projects.store.list().into_iter().filter_map(|p| Some((p.id.clone(), project_dir(&projects, &p.id)?.join(".infantry")))).collect();
        for line in library.migrate_files(Some(&global_dir), Some(&state_dir.join("global-hooks.json")), &known, &exts) {
            log_line(&line);
        }
        let seeded = script_store::seed_library(&library, &script_store::bundled(), &log_line);
        if !seeded.is_empty() {
            log_line(&format!("bundled scripts seeded into the library: {}", seeded.join(", ")));
        }
    }
    let host: Arc<dyn infantry_scripting::host::ScriptHost> = tools.clone();
    let script_dispatcher = {
        let s = settings::SettingsUnit { db: db.clone() };
        // `scripting.aliases` is data, read on every run; unset means the shipped table (MOVE-1).
        let aliases = move || {
            let stored = s.app("scripting.aliases").map(|v| v.as_str().map(String::from).unwrap_or_else(|| v.to_string()));
            infantry_scripting::dispatcher::effective_aliases(stored.as_deref())
        };
        ScriptDispatcher::new(host, Arc::new(aliases), Arc::new(|| 3))
    };
    let hook_store = Arc::new(HookStore::new(project_dirs, Some(state_dir.join("global-hooks.json"))).with_library(library.clone()));
    env.set_hooks(hook_store.clone());
    let run_log = Arc::new(ScriptRunLog::new(Arc::new(|| 200), Arc::new(infantry_scripting::runlog::now_ms), Some(library.clone() as Arc<dyn infantry_scripting::runlog::RunStore>)));
    let project_ids: Arc<dyn Fn() -> Vec<String> + Send + Sync> = {
        let p = projects.clone();
        Arc::new(move || p.store.list().into_iter().map(|p| p.id).collect())
    };
    let tip: Arc<dyn Fn(&str, &str) -> Option<String> + Send + Sync> = {
        let p = projects.clone();
        Arc::new(move |project: &str, branch: &str| infantry_scripting::hooks::git_tip(&project_dir(&p, project)?, branch))
    };
    let hooks = Arc::new(ScriptHooks::new(
        hook_store.clone(),
        scripts.clone(),
        engines.clone(),
        script_dispatcher.clone(),
        run_log.clone(),
        HooksConfig {
            projects: project_ids,
            tip,
            log: Arc::new(log_line),
            builtins: Arc::new(BuiltinHook::shipped),
            executor: infantry_scripting::hooks::Executor::Tokio(handle.clone()),
            ..HooksConfig::default()
        },
    ));
    // Plugin settings (`scripting.exec.*`, `scripting.hooks.*`) are app-scope rows of the SAME settings table Kotlin wrote,
    // so the owner's effective behaviour survives the migration. A string is read raw, any other JSON as its text.
    let script_config: Arc<dyn Fn(&str) -> Option<String> + Send + Sync> = {
        let s = settings::SettingsUnit { db: db.clone() };
        Arc::new(move |k: &str| s.app(k).map(|v| v.as_str().map(String::from).unwrap_or_else(|| v.to_string())))
    };
    let exec = {
        let p = projects.clone();
        Arc::new(ExecTools::new(script_config.clone(), Arc::new(move |id: &str| project_dir(&p, id)), Some(bus.clone()), Arc::new(log_line)))
    };
    timeline::wire(&run_log, &bus);
    daemon.observe(Arc::new(timeline::WakeObserver { run_log: run_log.clone() }));
    let timeline_unit = timeline::TimelineUnit { run_log: run_log.clone() };
    let chat = {
        let (d, p, w, h) = (daemon.clone(), projects.clone(), daemon.clone(), handle.clone());
        infantry_scripting::chat::ChatTools::new(
            run_log.clone(),
            Arc::new(move |s: &str| d.record(s)?.project_id),
            Arc::new(move |id: &str| project_dir(&p, id)),
            Arc::new(move |s: &str, t: &str| {
                let _rt = h.enter();
                w.deliver(s, t)
            }),
            script_config.clone(),
            Arc::new(|| now_ms() as u64),
        )
    };
    let manager = Arc::new(infantry_scripting::manage::ScriptManager {
        library: library.clone(),
        hooks: hook_store.clone(),
        engines: engines.clone(),
        plugins: None,
        min_every_sec: {
            let c = script_config.clone();
            Arc::new(move || c("scripting.hooks.minEverySec").and_then(|v| v.trim().parse().ok()).unwrap_or(10))
        },
    });
    let scripts_unit = infantry_scripting::unit::ScriptsUnit { manager: manager.clone(), host: tools.clone() };
    tools.set_scripting(ScriptingTools {
        manager: Some(manager),
        chat,
        engines,
        store: scripts,
        hooks: hook_store,
        dispatcher: script_dispatcher,
        run_log: run_log.clone(),
        exec: exec.clone(),
        limits: Arc::new(ScriptLimits::default),
        config: script_config.clone(),
    });
    {
        let h = hooks.clone();
        mail.on_incoming(move |m| {
            let im = IncomingMail {
                id: m.id.clone(),
                from: m.from.clone(),
                to: m.to.clone(),
                subject: m.subject.clone(),
                body: m.body.clone(),
                recipient_session_id: Some(m.to.clone()),
                thread_id: Some(m.thread_id.clone()),
                ..Default::default()
            };
            match h.incoming(&im) {
                Some(v) if v.handled => IncomingVerdict::Handled,
                Some(v) => v.text.map(IncomingVerdict::Replace).unwrap_or(IncomingVerdict::Pass),
                None => IncomingVerdict::Pass,
            }
        });
    }

    // ---- plugins ------------------------------------------------------------------------------------------
    let plugin_set = plugins::start(state_dir, plugins_dir(), bus.clone(), handle.clone());
    let plugin_host = plugin_set.as_ref().map(|p| p.host.clone());
    tools.set_plugins(plugin_host.clone());

    // ---- the registry --------------------------------------------------------------------------------------
    let mut registry = Registry::new();
    // The board family (tickets, pipeline, pods, mail, knowledge records) lives on the orchestrator.
    let board_side = |u: Arc<dyn FrameUnit>| -> Arc<dyn FrameUnit> { if mode.has_board() { u } else { Arc::new(crate::mode::ModeGated { inner: u }) } };
    let sweep = Arc::new(worktree_sweep::WorktreeSweep::new(board.clone(), projects.clone(), daemon.clone(), settings::SettingsUnit { db: db.clone() }, repo.clone()));
    {
        let s = sweep.clone();
        *worktrees_unit.stale.lock().unwrap_or_else(|e| e.into_inner()) = Some(Arc::new(move || s.report_json()));
    }
    if mode.has_board() {
        sweep.clone().spawn();
    }
    let mut units: Vec<Arc<dyn FrameUnit>> = vec![
        Arc::new(projects_unit),
        Arc::new(git_unit),
        Arc::new(worktrees_unit),
        board_side(Arc::new(BoardUnit::with_dispatcher(dispatcher.clone()))),
        Arc::new(UsageUnit::new(usage.clone())),
        board_side(Arc::new(MailUnit::new(mail.clone()))),
        Arc::new(AttentionUnit::new(attention.clone())),
        Arc::new(NotificationsUnit::new(hub)),
        Arc::new(NotifyUnit::new(notify)),
        Arc::new(PromptsUnit::new(prompts.clone(), Some(rules))),
        Arc::new(QuestionsUnit::new(questions.clone(), asker_of)),
        Arc::new(tools::ToolsUnit { tools: tools.clone() }),
        Arc::new(crate::run::RunUnit { runs: runs.clone() }),
        Arc::new(settings::SettingsUnit { db: db.clone() }),
        vault.clone(),
        Arc::new(crate::mcp::McpUnit { registry: mcp.clone() }),
        board_side(Arc::new(crate::pods::PodsUnit { pods: pods.clone() })),
        Arc::new(crate::account::AccountUnit { account }),
        Arc::new(timeline_unit),
        Arc::new(scripts_unit),
    ];
    units.extend(knowledge.units().into_iter().map(board_side));
    if let Some(p) = &plugin_set {
        units.extend(plugins::frame_units(p));
    }
    let min_disk_db = db.clone();
    units.push(Arc::new(health_unit(daemon, &projects, plugin_host.clone(), hooks.store().clone(), Box::new(move || {
        let v = settings::SettingsUnit { db: min_disk_db.clone() }.app(crate::health::MIN_DISK_SETTING);
        v.and_then(|v| v.as_u64().or_else(|| v.as_str().and_then(|s| s.trim().parse().ok())))
    }))));
    units.push(board_side(Arc::new(agent_frames::AgentFrames::new(
        daemon.clone(),
        crate::board::Board::new(state_dir),
        mail.mailbox().clone(),
        dispatcher.clone(),
        projects.store.clone(),
        {
            let p = projects.clone();
            Arc::new(move |id: &str| project_dir(&p, id))
        },
    ))));
    let harness_list = harness_frames::HarnessFrames::new(daemon.clone());
    units.push(harness_install::HarnessInstall::new(state_dir, {
        let h = harness_list.clone();
        Arc::new(move || h.rebuild_now())
    }));
    units.push(harness_list);
    units.push(Arc::new(host_frames::HostFrames {
        daemon: daemon.clone(),
        plugins: plugin_host.clone(),
        plugins_dir: plugins_dir(),
        registry: agent_registry.clone(),
        questions,
        state_dir: state_dir.to_path_buf(),
        projects: projects.store.clone(),
    }));
    for u in units {
        let name = u.name();
        if let Err(e) = registry.register(u) {
            log_line(&format!("unit {name} not registered: {e}"));
        }
    }

    let c = Arc::new(Composed {
        state_dir: state_dir.to_path_buf(),
        db: db.clone(),
        bus: bus.clone(),
        registry,
        daemon: daemon.clone(),
        projects,
        board: board.clone(),
        dispatcher: dispatcher.clone(),
        orchestrator,
        usage: usage.clone(),
        mail: mail.clone(),
        hooks: hooks.clone(),
        tools,
        knowledge: knowledge.clone(),
        plugins: plugin_host.clone(),
        prompts: prompts.clone(),
        next_conn: AtomicU64::new(1),
    });
    if let Some(p) = &plugin_set {
        plugins::attach(p, &c);
    }

    // ---- session life -> usage, mail wakes, the done protocol ----------------------------------------------
    daemon.observe(Arc::new(adapters::WorkObserver { dispatcher, usage: usage.clone(), mail: mail.clone(), handle: handle.clone() }));
    daemon.observe(Arc::new(adapters::RunEvents { bus: bus.clone() }));

    // ---- RUST-SESS-2: the watchdog (never fails a session) and the done-worker reaper -----------------------
    daemon.start_watchdog(Arc::new(watch::Ports { daemon: daemon.clone(), mail: mail.clone(), attention: attention.clone(), settings: settings::SettingsUnit { db: db.clone() } }), crate::sessions::WatchConfig::default());
    watch::spawn_reaper(daemon.clone(), c.projects.clone(), board.clone(), settings::SettingsUnit { db: db.clone() }, &bus);

    // ---- background: bus -> clients, hooks, timers, usage, mail tick, exec ticker -------------------------
    {
        let mut rx = bus.subscribe();
        let d = daemon.clone();
        tokio::spawn(async move {
            loop {
                match rx.recv().await {
                    Ok(e) => d.publish_raw(serde_json::to_string(&e).unwrap_or_default()),
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(_) => break,
                }
            }
        });
    }
    {
        let b = bus.clone();
        tokio::spawn(async move {
            let mut t = tokio::time::interval(Duration::from_millis(CLOCK_TICK_MS));
            t.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Skip);
            loop {
                t.tick().await;
                b.emit(CLOCK_TICK, serde_json::json!({ "at": now_ms() }));
            }
        });
    }
    hooks.attach(&bus);
    hooks.spawn_timers(Arc::new(|| TIMER_MIN_SECS));
    exec.spawn_ticker();
    usage.start_background(bus.clone());
    {
        let m = mail.clone();
        tokio::spawn(async move {
            let mut t = tokio::time::interval(Duration::from_millis(MAIL_TICK_MS));
            loop {
                t.tick().await;
                let m = m.clone();
                let _ = tokio::task::spawn_blocking(move || m.tick()).await;
            }
        });
    }
    let frames = c.registry.frame_table().len();
    log_line(&format!(
        "composed {} units ({frames} frame types){}",
        c.registry.units().len(),
        plugin_host.as_ref().map(|h| format!(", plugins: {}", h.loaded().iter().map(|p| p.id.clone()).collect::<Vec<_>>().join(", "))).unwrap_or_default()
    ));
    daemon.set_units(c.clone());
    Ok(c)
}

/// The `health` frame over this composition: plugin statuses, hooks, harnesses and a call's session or project paths.
fn health_unit(
    daemon: &Arc<Daemon>,
    projects: &Arc<Projects>,
    plugins: Option<Arc<infantry_plugin_host::PluginHost>>,
    hook_store: Arc<HookStore>,
    min_disk_mb: Box<dyn Fn() -> Option<u64> + Send + Sync>,
) -> crate::health::HealthUnit {
    use crate::health::{current_branch, HealthSources, HealthUnit};
    let hook_projects = projects.clone();
    let d = daemon.clone();
    let paths_d = daemon.clone();
    let paths_p = projects.clone();
    HealthUnit {
        health: daemon.health.clone(),
        sources: HealthSources {
            build: crate::server::BUILD.into(),
            plugins: Box::new(move || plugins.as_ref().map(|h| h.statuses()).unwrap_or_default()),
            hooks: Box::new(move || {
                let mut ids = std::collections::BTreeSet::new();
                for p in hook_projects.store.list() {
                    ids.extend(hook_store.list(&p.id).into_iter().map(|h| h.id));
                }
                Some(ids.len())
            }),
            min_disk_mb,
            harnesses: Box::new(move || d.providers()),
            paths: Box::new(move |session, project| {
                let dir = |p: &Path| Some(p.to_string_lossy().into_owned());
                if let Some(r) = session.and_then(|s| paths_d.record(s)) {
                    let project = r.project_id.as_deref().and_then(|p| project_dir(&paths_p, p)).unwrap_or_else(|| PathBuf::from(&r.working_dir));
                    let wt = r.worktree_path.clone().filter(|w| !w.trim().is_empty());
                    let branch = r.worktree_branch.clone().or_else(|| current_branch(Path::new(wt.as_deref().unwrap_or(&r.working_dir))));
                    return (dir(&project), wt, branch);
                }
                match project.and_then(|p| project_dir(&paths_p, p)) {
                    Some(p) => (dir(&p), None, current_branch(&p)),
                    None => (None, None, None),
                }
            }),
        },
    }
}

impl Composed {
    /// Dispatches `env` through the registry as one client (`owner`); the replies encoded, a refusal as an `error`.
    pub fn dispatch(&self, env: &Envelope, conn_id: u64, owner: bool) -> Option<Vec<String>> {
        let ctx = FrameCtx { conn_id, owner, bus: self.bus.clone() };
        let r = self.registry.dispatch(env, &ctx)?;
        Some(match r {
            Ok(out) => out.iter().map(|e| serde_json::to_string(e).unwrap_or_default()).collect(),
            Err(refusal) => {
                let mut e = Envelope::new(wire::ERROR, refusal.0);
                e.request_id = env.request_id.clone();
                e.session_id = env.session_id.clone();
                vec![serde_json::to_string(&e).unwrap_or_default()]
            }
        })
    }

    /// The same, as envelopes (for in-process callers: plugins, tools).
    pub fn request(&self, env: &Envelope, owner: bool) -> Result<Vec<Envelope>, String> {
        let ctx = FrameCtx { conn_id: 0, owner, bus: self.bus.clone() };
        match self.registry.dispatch(env, &ctx) {
            Some(r) => r.map_err(|e| e.0),
            None => Err(format!("{} refused: no unit serves this frame", env.r#type)),
        }
    }
}

impl FrameUnits for Composed {
    fn owns(&self, ty: &str) -> bool {
        self.registry.owner(ty).is_some()
    }

    fn handle(&self, env: Envelope, owner: bool) -> Vec<String> {
        let id = self.next_conn.fetch_add(1, Ordering::Relaxed);
        self.dispatch(&env, id, owner).unwrap_or_default()
    }

    /// The other units' connection burst: the worktree list (the sessions unit sends ready/policy/sessions), then
    /// every open prompt card.
    fn hello(&self) -> Vec<String> {
        let mut out = self.dispatch(&Envelope::new(wire::WORKTREES, ""), 0, true).unwrap_or_default();
        for p in self.prompts.snapshot() {
            let mut e = Envelope::new(infantry_protocol::frames::PROMPT_REQUEST, serde_json::to_string(&p).unwrap_or_default());
            e.session_id = Some(p.session_id.clone());
            out.push(serde_json::to_string(&e).unwrap_or_default());
        }
        out
    }

    fn route(&self, method: &str, path: &str) -> Option<bool> {
        self.plugins.as_ref()?.route_owner(method, path).map(|(_, public)| public)
    }

    fn serve_route(&self, c: crate::sessions::RouteCall) -> (u16, Vec<(String, String)>, Vec<u8>) {
        let Some((p, _)) = self.plugins.as_ref().and_then(|h| h.route_owner(&c.method, &c.path)) else { return (404, Vec::new(), b"no such route".to_vec()) };
        let req = infantry_plugin_host::RouteRequest { method: c.method, path: c.path, query: c.query, headers: c.headers, body: c.body };
        match p.handle_route(&req) {
            Ok(r) => (r.status, r.headers, r.body),
            Err(e) => (500, vec![("Content-Type".into(), "text/plain".into())], e.message().as_bytes().to_vec()),
        }
    }

    fn http(&self) -> Option<axum::Router> {
        Some(infantry_mail::mail::http::routes(self.mail.clone()).merge(infantry_knowledge::avatars::router(self.knowledge.avatars.clone())))
    }
}
