// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The daemon side of each unit's ports: the board's [`BoardEnv`], the work half's [`SessionLauncher`] and
//! [`WorkspaceProvider`], the mail router's directory and turn sink, the questions host, and the session observer that
//! feeds usage, mail wakes and the done protocol.

use crate::board::Board as ProfileBoard;
use crate::sessions::{Daemon, NewOpts, SessionObserver};
use infantry_board::env::{BoardAgent, BoardEnv, ProviderFacts};
use infantry_board::Dispatcher;
use infantry_mail::mail::router::{MailRouter, ProjectRef, SessionDirectory, SessionInfo as MailSession, TurnSink};
use infantry_mail::notify::questions::QuestionHost;
use infantry_orchestrator_api::{
    DoneReport, LaunchRequest, LaunchedSession, RoutingRule as OrchRule, SessionLauncher, WorkerState, Workspace, WorkspaceProvider, WorkspaceRelease, WorkspaceRequest,
};
use infantry_projects::ticket_branches::{TicketBranchOutcome, TicketBranchRequest};
use infantry_projects::Projects;
use infantry_protocol::projects::RoutingRule;
use infantry_protocol::session::{SessionRecord, SessionState};
use infantry_protocol::MessageOrigin;
use infantry_usage::meter::{SessionInfo as UsageSession, TurnOrigin};
use infantry_usage::pacing::{meter_for, PacingVerdict};
use infantry_usage::UsageService;
use std::path::{Path, PathBuf};
use std::sync::Arc;

/// The session's process is gone for good (killed, exited, failed at start).
pub fn ended(r: &SessionRecord) -> bool {
    matches!(r.state, SessionState::Exited | SessionState::Failed)
}

/// The usage pacer's "running workers": a session with a ticket that is live AND working (starting or in a turn).
/// Idle, waiting-for-input, exited sessions and the PM do not hold a worker slot.
pub fn counts_as_worker(r: &SessionRecord) -> bool {
    r.ticket_id.is_some()
        && matches!(r.state, SessionState::Starting | SessionState::Busy)
        && !r.role.as_deref().map(|x| x.trim().eq_ignore_ascii_case("pm")).unwrap_or(false)
}

/// ROUTING-1: a rule list is accepted when every rule names a harness this daemon runs.
pub fn validate_routing(daemon: &Daemon, rules: Vec<RoutingRule>) -> Result<Vec<RoutingRule>, String> {
    let have = daemon.providers();
    for r in &rules {
        let p = r.provider.trim();
        if p.is_empty() || r.model.trim().is_empty() {
            return Err(format!("routing rule `{}` needs a provider and a model", r.name));
        }
        if !have.iter().any(|h| h == p) {
            return Err(format!("routing rule `{}` names provider `{p}`, which this daemon does not run ({})", r.name, have.join(", ")));
        }
    }
    Ok(rules)
}

fn to_orch(r: &RoutingRule) -> Option<OrchRule> {
    serde_json::from_value(serde_json::to_value(r).ok()?).ok()
}

/// Local minute of the day; the daemon has no time-zone database, so this is UTC (quiet hours are off by default).
pub fn minute_of_day() -> u32 {
    let secs = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    ((secs / 60) % (24 * 60)) as u32
}

// ---- the board -----------------------------------------------------------------------------------------------

pub struct DaemonBoardEnv {
    daemon: Arc<Daemon>,
    projects: Arc<Projects>,
    profiles: ProfileBoard,
    usage: Arc<UsageService>,
    /// The notification service, set once it exists (it is built after the board env).
    notify: std::sync::OnceLock<Arc<infantry_mail::notify::push::NotifyService>>,
    /// Scoped settings (`lanes.maxInProgress`).
    settings: super::settings::SettingsUnit,
    /// The script hook store, set once it exists (`autoStart=false` hooks).
    hooks: std::sync::OnceLock<Arc<infantry_scripting::hooks::HookStore>>,
    /// The mail router and the attention list a worker's bad end is told through (PIPE-RESTART-1; set once they exist).
    lead_notice: std::sync::OnceLock<(Arc<infantry_mail::mail::router::MailRouter>, Arc<infantry_mail::notify::attention::AttentionStore>)>,
}

/// `board.restartResumeSeconds`: how long a resumed session must live before a restart counts as working.
pub const BOARD_RESTART_RESUME_SECONDS: &str = "board.restartResumeSeconds";

/// `lanes.maxInProgress`: scopes app, project, role; default = the usage pacing allowance.
pub const LANES_MAX_IN_PROGRESS: &str = "lanes.maxInProgress";

impl DaemonBoardEnv {
    pub fn new(daemon: Arc<Daemon>, projects: Arc<Projects>, state_dir: &Path, usage: Arc<UsageService>, db: Arc<infantry_core::db::Db>) -> DaemonBoardEnv {
        DaemonBoardEnv {
            daemon,
            projects,
            profiles: ProfileBoard::new(state_dir),
            usage,
            notify: std::sync::OnceLock::new(),
            settings: super::settings::SettingsUnit { db },
            hooks: std::sync::OnceLock::new(),
            lead_notice: std::sync::OnceLock::new(),
        }
    }

    pub fn set_lead_notice(&self, mail: Arc<infantry_mail::mail::router::MailRouter>, attention: Arc<infantry_mail::notify::attention::AttentionStore>) {
        let _ = self.lead_notice.set((mail, attention));
    }

    pub fn set_hooks(&self, h: Arc<infantry_scripting::hooks::HookStore>) {
        let _ = self.hooks.set(h);
    }

    pub fn set_notify(&self, n: Arc<infantry_mail::notify::push::NotifyService>) {
        let _ = self.notify.set(n);
    }

    fn project(&self, id: Option<&str>) -> Option<infantry_protocol::projects::Project> {
        self.projects.store.get(id?).ok()
    }

    /// The most workers the usage pacing allows now: the largest allowance over the meters (no limit, or no reading,
    /// follows the lane cap). At least 1. The meter's own start check still refuses a start over its allowance.
    fn pacing_allowance(&self, project_id: Option<&str>, role: &str) -> u32 {
        let meters = self.usage.report().meters;
        let lane = BoardEnv::lane_cap(self, project_id, role);
        if meters.is_empty() || meters.iter().any(|m| m.allowance.is_none()) {
            return lane;
        }
        meters.iter().filter_map(|m| m.allowance).max().unwrap_or(1).max(1) as u32
    }
}

impl BoardEnv for DaemonBoardEnv {
    fn now_ms(&self) -> i64 {
        super::now_ms()
    }
    fn agents(&self) -> Vec<BoardAgent> {
        self.profiles
            .agents(None)
            .unwrap_or_default()
            .into_iter()
            .map(|a| BoardAgent { id: a.id, name: a.name, role: Some(a.role).filter(|r| !r.is_empty()), provider: a.provider, model: a.model, effort: a.effort })
            .collect()
    }
    fn sessions_on(&self) -> bool {
        true
    }
    fn default_provider(&self) -> Option<String> {
        Some(self.daemon.harness.name().to_string())
    }
    fn provider(&self, id: &str) -> Option<ProviderFacts> {
        self.daemon.harness_for(Some(id)).map(|h| ProviderFacts { id: h.name().to_string(), ..Default::default() })
    }
    fn check_spawn(&self, provider: Option<&str>, _model: Option<&str>) -> Option<String> {
        let p = provider.filter(|p| !p.trim().is_empty())?;
        match self.daemon.harness_for(Some(p)) {
            Some(_) => None,
            None => Some(format!("no harness named `{p}` on this daemon (it runs {})", self.daemon.providers().join(", "))),
        }
    }
    fn branch_name(&self, raw: &str) -> Result<String, String> {
        infantry_projects::store::check_base_branch_name(raw).map_err(|e| e.0)
    }
    fn check_base(&self, project_id: &str, branch: &str) -> Result<(), String> {
        let dir = super::project_dir(&self.projects, project_id).ok_or_else(|| format!("project {project_id} has no folder to check `{branch}` in"))?;
        match self.projects.branches.resolve_base(&dir, branch) {
            Some(_) => Ok(()),
            None => Err(format!("base branch '{branch}' does not resolve to a commit in {}", dir.display())),
        }
    }
    fn is_registry_agent(&self, id: &str) -> bool {
        self.agents().iter().any(|a| a.id.as_deref() == Some(id))
    }
    fn is_session(&self, id: &str) -> bool {
        self.daemon.record(id).is_some()
    }
    fn live_sessions(&self, agent_id: &str, project_id: Option<&str>) -> Vec<String> {
        self.daemon
            .records()
            .into_iter()
            .filter(|r| r.agent_id.as_deref() == Some(agent_id) && !ended(r))
            .filter(|r| project_id.is_none() || r.project_id.as_deref() == project_id)
            .map(|r| r.id)
            .collect()
    }
    fn notify_owner(&self, title: &str, body: &str, ticket_id: &str, project_id: Option<&str>) -> bool {
        let Some(n) = self.notify.get() else { return false };
        let req = infantry_mail::notify::push::NotifyRequest {
            title: title.chars().take(infantry_mail::notify::push::MAX_TITLE).collect(),
            body: body.chars().take(infantry_mail::notify::push::MAX_BODY).collect(),
            ticket_id: Some(ticket_id.to_string()),
            ..Default::default()
        };
        n.notify(&req, project_id).is_ok()
    }
    fn live_role_sessions(&self, role: &str, project_id: Option<&str>) -> Vec<String> {
        self.daemon
            .records()
            .into_iter()
            .filter(|r| r.role.as_deref() == Some(role) && !ended(r))
            .filter(|r| project_id.is_none() || r.project_id.as_deref() == project_id)
            .map(|r| r.id)
            .collect()
    }
    fn is_role(&self, role: &str) -> bool {
        self.agents().iter().any(|a| a.role.as_deref() == Some(role)) || self.daemon.records().iter().any(|r| r.role.as_deref() == Some(role))
    }
    fn is_live_session(&self, id: &str) -> bool {
        self.daemon.record(id).is_some_and(|r| !ended(&r))
    }
    fn notify_session(&self, session_id: &str, subject: &str, line: &str) -> bool {
        self.daemon.deliver(session_id, &format!("{subject}\n{line}")).is_ok()
    }
    fn alert_session(&self, session_id: &str, subject: &str, line: &str, ticket_id: &str) -> bool {
        let t0 = std::time::Instant::now();
        let r = self.daemon.deliver_urgent(session_id, &format!("{subject}\n{line}"));
        let (how, ok) = match &r {
            Ok(h) => (h.to_string(), true),
            Err(e) => (format!("refused: {e}"), false),
        };
        crate::health::record_notice(subject, ticket_id, session_id, &how, t0.elapsed().as_millis() as i64, line);
        ok
    }
    fn worker_failed(&self, f: &infantry_board::env::WorkerFailed<'_>) -> bool {
        let Some((mail, attention)) = self.lead_notice.get() else { return false };
        let down = super::watch::WorkerDown { ticket_id: f.ticket_id, session_id: f.session_id, lead: f.lead, outcome: f.outcome, subject: f.subject, detail: f.detail };
        super::watch::worker_down(attention, mail, &down)
    }
    fn restart_resume_window_ms(&self) -> u64 {
        let s = self.settings.app(BOARD_RESTART_RESUME_SECONDS);
        let secs = s.and_then(|v| v.as_u64().or_else(|| v.as_str().and_then(|x| x.trim().parse().ok())));
        secs.unwrap_or(20) * 1000
    }
    fn branch_facts(&self, project_id: Option<&str>, branch: Option<&str>, base: Option<&str>) -> Option<String> {
        let dir = super::project_dir(&self.projects, project_id?)?;
        let run = |args: &[&str]| infantry_proc::command("git").arg("-C").arg(&dir).args(args).output().ok();
        let out = |args: &[&str]| run(args).filter(|o| o.status.success()).map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string()).filter(|t| !t.is_empty());
        let branch = branch?;
        let short = |r: &str| out(&["rev-parse", "--short=9", "--verify", "--quiet", r]);
        let tip = short(branch)?;
        let target = base.map(str::to_string).or_else(|| out(&["rev-parse", "--abbrev-ref", "HEAD"]))?;
        let merged = run(&["merge-base", "--is-ancestor", branch, &target]).is_some_and(|o| o.status.success());
        let target_tip = short(&target).unwrap_or_default();
        Some(if merged {
            format!("branch {branch} @ {tip} merged into {target} @ {target_tip}")
        } else {
            format!("branch {branch} @ {tip} NOT merged into {target} @ {target_tip} yet")
        })
    }
    fn lane_cap(&self, project_id: Option<&str>, role: &str) -> u32 {
        project_id.map(|p| self.projects.store.lane_cap_of(p, role)).unwrap_or(1).max(1) as u32
    }
    fn max_in_progress(&self, project_id: Option<&str>, role: &str) -> u32 {
        let d = serde_json::json!({ "project": project_id, "role": role });
        let v = self.settings.resolve(LANES_MAX_IN_PROGRESS, &d)["value"].clone();
        // An explicit setting wins; unset, the cap is the usage pacing allowance (`infantry usage`), so workers of a role
        // wait on the owner's pacing and on nothing else.
        v.as_u64().or_else(|| v.as_str().and_then(|s| s.trim().parse().ok())).filter(|n| *n >= 1).map(|n| n as u32).unwrap_or_else(|| self.pacing_allowance(project_id, role))
    }
    fn auto_start_next(&self, project_id: Option<&str>) -> bool {
        let off = |v: Option<&serde_json::Value>| match v {
            Some(serde_json::Value::Bool(b)) => !*b,
            Some(serde_json::Value::String(s)) => matches!(s.trim().to_lowercase().as_str(), "false" | "off" | "0" | "no"),
            _ => false,
        };
        let Some(h) = self.hooks.get() else { return true };
        !h.list(project_id.unwrap_or("")).iter().any(|hook| hook.enabled && off(hook.args.get("autoStart")))
    }
    fn routing_rules(&self, project_id: Option<&str>) -> Vec<OrchRule> {
        self.project(project_id).map(|p| p.routing_rules.iter().filter_map(to_orch).collect()).unwrap_or_default()
    }
    fn pacing_refusal(&self, provider: Option<&str>, model: Option<&str>) -> Option<String> {
        let p = provider.map(str::to_string).unwrap_or_else(|| self.daemon.harness.name().to_string());
        match self.usage.check(&meter_for(&p, model)) {
            PacingVerdict::Over { reason } => Some(reason),
            PacingVerdict::Allow { .. } => None,
        }
    }
    fn session_label(&self, session_id: &str) -> Option<String> {
        let r = self.daemon.record(session_id)?;
        let name = r.agent_id.as_deref().and_then(|a| self.agent(a)).map(|a| a.name);
        Some(name.or(r.role.clone()).unwrap_or_else(|| session_id.to_string()))
    }
    fn image_path(&self, project_id: Option<&str>, rel: &str) -> Option<String> {
        match project_id.and_then(|p| super::project_dir(&self.projects, p)) {
            Some(d) => Some(d.join(rel).to_string_lossy().into_owned()),
            None => Some(rel.to_string()),
        }
    }
    fn normalize_images(&self, _project_id: Option<&str>, images: &[String]) -> Result<Vec<String>, String> {
        Ok(images.iter().map(|i| i.trim().to_string()).filter(|i| !i.is_empty()).collect())
    }
}

// ---- the work half -------------------------------------------------------------------------------------------

/// Ticket worktrees through infantry-projects' ticket branches.
pub struct DaemonWorkspaces {
    pub projects: Arc<Projects>,
    /// Where ticket starts cut when the project's own folder is not a repository.
    pub repo: Option<PathBuf>,
}

impl DaemonWorkspaces {
    fn repo_of(&self, project_id: Option<&str>) -> Option<PathBuf> {
        if let Some(d) = project_id.and_then(|p| super::project_dir(&self.projects, p)) {
            if self.projects.branches.common_dir(&d).is_some() {
                return Some(d);
            }
        }
        self.repo.clone().filter(|r| self.projects.branches.common_dir(r).is_some())
    }
}

impl WorkspaceProvider for DaemonWorkspaces {
    fn wants_worktree(&self, project_id: Option<&str>) -> bool {
        self.repo_of(project_id).is_some()
    }
    fn ensure(&self, req: &WorkspaceRequest) -> Result<Workspace, String> {
        let repo = self.repo_of(req.project_id.as_deref()).ok_or("no repository to cut a ticket branch in")?;
        let base = req
            .base_branch
            .clone()
            .filter(|b| !b.trim().is_empty())
            .or_else(|| req.project_id.as_deref().and_then(|p| self.projects.store.base_branch_of(p)))
            .unwrap_or_else(|| "HEAD".into());
        let out = self.projects.branches.ensure(&TicketBranchRequest {
            ticket_id: req.ticket_id.clone(),
            title: req.title.clone(),
            repo,
            base_branch: base,
            recorded_branch: req.recorded_branch.clone(),
            recorded_base: req.recorded_base.clone(),
        });
        match out {
            TicketBranchOutcome::Ready { worktree, note, warning } => Ok(Workspace {
                branch: worktree.branch,
                base: worktree.base,
                path: worktree.path,
                note: match (note, warning) {
                    (Some(n), Some(w)) => Some(format!("{n}; {w}")),
                    (n, w) => n.or(w),
                },
            }),
            TicketBranchOutcome::Refused(why) => Err(why),
        }
    }
    fn folder(&self, project_id: Option<&str>) -> Result<Option<String>, String> {
        Ok(project_id.and_then(|p| super::project_dir(&self.projects, p)).map(|d| d.to_string_lossy().into_owned()))
    }
    fn release(&self, req: &WorkspaceRelease) -> Result<Option<String>, String> {
        match self.repo_of(req.project_id.as_deref()) {
            Some(repo) => super::worktree_sweep::release_ticket_worktree(&self.projects, &repo, req),
            None => Ok(None),
        }
    }
}

/// Ticket workers through the daemon's session manager.
pub struct DaemonLauncher {
    pub daemon: Arc<Daemon>,
    pub handle: tokio::runtime::Handle,
    /// The worktree registry a ticket worker's checkout is registered in (the hello `worktrees` frame lists it).
    pub worktrees: Arc<infantry_projects::worktree::WorktreeManager>,
}

/// A session's worktree as the registry knows it (Kotlin `SessionRecord.toWorktreeRef`); None without all four parts.
pub fn worktree_ref(r: &SessionRecord) -> Option<infantry_projects::wire::WorktreeRef> {
    let some = |v: &Option<String>| v.clone().filter(|s| !s.trim().is_empty());
    Some(infantry_projects::wire::WorktreeRef {
        session_id: r.id.clone(),
        repo_root: some(&r.repo_root)?,
        path: some(&r.worktree_path)?,
        branch: some(&r.worktree_branch)?,
        base_ref: r.worktree_base_ref.clone()?,
    })
}

impl SessionLauncher for DaemonLauncher {
    fn launch(&self, r: &LaunchRequest) -> Result<LaunchedSession, String> {
        let _rt = self.handle.enter();
        let cwd = r.workspace.as_ref().map(|w| w.path.clone()).or_else(|| r.folder.clone());
        let opts = NewOpts {
            cwd,
            model: r.model.clone(),
            effort: r.effort.clone(),
            role: r.role.clone(),
            name: None,
            prompt: Some(r.brief.clone()),
            provider: r.provider.clone(),
            ticket_id: Some(r.ticket_id.clone()),
            project_id: r.project_id.clone(),
            agent_id: r.agent_id.clone().filter(|a| !a.starts_with("role:")),
            context_limit: r.context_limit,
            runner: Some(r.runner.clone()),
            unattended: r.origin.unattended(),
            spawner: None,
            worktree: r.workspace.as_ref().map(|w| (w.branch.clone(), w.base.clone(), r.folder.clone().unwrap_or_default())),
        };
        let id = self.daemon.launch(opts)?;
        if let Some(w) = self.daemon.record(&id).as_ref().and_then(worktree_ref) {
            self.worktrees.reconcile(&[w]);
        }
        Ok(LaunchedSession { session_id: id, note: None, workspace: r.workspace.clone() })
    }
    fn stop(&self, session_id: &str, reason: &str) {
        let _rt = self.handle.enter();
        let _ = self.daemon.stop_session(session_id, reason);
    }
    fn remove(&self, session_id: &str, reason: &str) -> bool {
        let _rt = self.handle.enter();
        let _ = self.daemon.stop_session(session_id, reason);
        match self.daemon.delete_session(session_id) {
            Ok(()) => true,
            // Already gone is the goal.
            Err(_) => self.daemon.record(session_id).is_none(),
        }
    }
    fn state(&self, session_id: &str) -> WorkerState {
        match self.daemon.record(session_id) {
            None => WorkerState::Ended,
            Some(r) if ended(&r) => WorkerState::Ended,
            // PIPE-RESTART-1: a process that died at rest (killed, crashed: a non-zero exit) is a dead worker even though the
            // session reads IDLE; a normal rest between turns has no exit code or 0.
            Some(r) if r.state == SessionState::Idle && !r.is_process_alive && r.last_exit_code.is_some_and(|c| c != 0) => WorkerState::Ended,
            Some(_) if self.daemon.turn_active(session_id) == Some(true) => WorkerState::Busy,
            Some(_) => WorkerState::Idle,
        }
    }
    fn prompt(&self, session_id: &str, text: &str) -> bool {
        let _rt = self.handle.enter();
        self.daemon.deliver(session_id, text).is_ok()
    }
}

// ---- mail ----------------------------------------------------------------------------------------------------

pub struct Directory {
    pub daemon: Arc<Daemon>,
    pub projects: Arc<Projects>,
}

impl SessionDirectory for Directory {
    fn sessions(&self) -> Vec<MailSession> {
        self.daemon
            .records()
            .into_iter()
            .map(|r| MailSession {
                busy: r.is_busy(),
                live: r.live(),
                resumable: !ended(&r) || r.resumable(),
                last_active: r.last_event_at.unwrap_or(r.last_heartbeat),
                id: r.id,
                project_id: r.project_id,
                role: r.role,
                agent_id: r.agent_id,
            })
            .collect()
    }
    fn projects(&self) -> Vec<ProjectRef> {
        self.projects.store.list().into_iter().map(|p| ProjectRef { key: p.name.clone(), id: p.id }).collect()
    }
    fn starter_of(&self, session: &str) -> Option<String> {
        self.daemon.record(session)?.spawner_session_id
    }
}

pub struct Turns {
    pub daemon: Arc<Daemon>,
    pub handle: tokio::runtime::Handle,
}

impl TurnSink for Turns {
    fn inject(&self, session: &str, text: &str, _origin: &MessageOrigin) -> Result<(), String> {
        let _rt = self.handle.enter();
        self.daemon.deliver(session, text)
    }
}

pub struct Questions {
    pub daemon: Arc<Daemon>,
    pub handle: tokio::runtime::Handle,
}

impl QuestionHost for Questions {
    fn notify(&self, session: &str, subject: &str, body: &str, _thread: &str) -> bool {
        let _rt = self.handle.enter();
        self.daemon.deliver(session, &format!("{subject}\n\n{body}")).is_ok()
    }
    fn session_exists(&self, session: &str) -> bool {
        self.daemon.record(session).is_some()
    }
    fn session_busy(&self, session: &str) -> bool {
        self.daemon.turn_active(session) == Some(true)
    }
}

// ---- session life ----------------------------------------------------------------------------------------------

/// Feeds usage at once; the done protocol and mail wakes run later on the blocking pool (the observer is called
/// with the session's lock held, and both call back into the session manager).
pub struct WorkObserver {
    pub dispatcher: Arc<Dispatcher>,
    pub usage: Arc<UsageService>,
    pub mail: Arc<MailRouter>,
    pub handle: tokio::runtime::Handle,
}

fn usage_info(r: &SessionRecord) -> UsageSession {
    UsageSession { id: r.id.clone(), run_count: r.run_count as i64, ticket_id: r.ticket_id.clone(), role: r.role.clone(), provider: r.provider.clone(), model: r.model.clone() }
}

/// Session life as named bus events, for plugins (a generic seam): `session.run_ended` `{sessionId, outcome, detail,
/// role, projectId, ticketId}` when a turn ends (`outcome` = SUCCEEDED, FAILED, KILLED_*), `session.exited`
/// `{sessionId}` when its process is gone.
pub struct RunEvents {
    pub bus: infantry_core::bus::EventBus,
}

/// Pods start their node sessions through the session manager's own `new` frame (unattended).
pub struct PodSessions {
    pub daemon: Arc<crate::sessions::Daemon>,
    pub bus: infantry_core::bus::EventBus,
}

impl crate::pods::PodSessions for PodSessions {
    fn start(&self, l: &crate::pods::Launch) -> Result<crate::pods::Started, String> {
        let body = serde_json::json!({
            "prompt": l.prompt, "unattended": true, "projectId": l.project_id, "cwd": l.working_dir,
            "agentId": l.agent_id, "provider": l.provider, "model": l.model,
        });
        let mut env = infantry_protocol::Envelope::new(crate::wire::NEW, body.to_string());
        env.request_id = Some(format!("pod-{}", uuid::Uuid::new_v4()));
        let mut conn = crate::sessions::Conn::default();
        let out = self.daemon.handle(env, &mut conn);
        if let Some(err) = out.iter().filter_map(|t| serde_json::from_str::<infantry_protocol::Envelope>(t).ok()).find(|e| e.r#type == crate::wire::ERROR) {
            return Err(err.data);
        }
        let id = conn.active.ok_or("the daemon started no session")?;
        let rec = self.daemon.record(&id).ok_or("the session is gone")?;
        Ok(crate::pods::Started { session_id: id, provider: rec.provider, working_dir: rec.working_dir })
    }
    fn kill(&self, session_id: &str, force: bool) {
        let data = if force { format!("{session_id} force") } else { session_id.to_string() };
        let mut conn = crate::sessions::Conn::default();
        let _ = self.daemon.handle(infantry_protocol::Envelope::new(crate::wire::KILL, data), &mut conn);
    }
    fn push(&self, status: &serde_json::Value) {
        self.bus.publish(infantry_protocol::Envelope::new(crate::pods::frames::POD, status.to_string()));
    }
}

pub const SESSION_RUN_ENDED: &str = "session.run_ended";
pub const SESSION_EXITED: &str = "session.exited";

impl SessionObserver for RunEvents {
    fn on_turn_end(&self, rec: &SessionRecord, outcome: &str, detail: &str) {
        self.bus.emit(
            SESSION_RUN_ENDED,
            serde_json::json!({"sessionId": rec.id, "outcome": outcome, "detail": detail, "role": rec.role, "projectId": rec.project_id, "ticketId": rec.ticket_id}),
        );
    }
    fn on_exit(&self, rec: &SessionRecord) {
        self.bus.emit(SESSION_EXITED, serde_json::json!({"sessionId": rec.id}));
    }
}

impl SessionObserver for WorkObserver {
    fn on_turn(&self, rec: &SessionRecord, _text: &str) {
        let origin = if rec.ticket_id.is_some() || rec.spawner_session_id.is_some() { TurnOrigin::Agent } else { TurnOrigin::Owner };
        self.usage.on_turn(&usage_info(rec), &origin);
    }
    fn on_stdout(&self, rec: &SessionRecord, line: &str) {
        self.usage.on_stdout(&usage_info(rec), line);
    }
    fn on_exit(&self, rec: &SessionRecord) {
        self.usage.on_exit(&usage_info(rec));
    }
    fn on_rest_exit(&self, rec: &SessionRecord, code: Option<i32>) {
        let Some(ticket) = rec.ticket_id.clone() else { return };
        let detail = format!("the worker's process died while it was idle (exit code {})", code.map(|c| c.to_string()).unwrap_or_else(|| "none".into()));
        let report = DoneReport { ticket_id: ticket, session_id: rec.id.clone(), outcome: "FAILED".into(), detail: Some(detail) };
        let d = self.dispatcher.clone();
        self.handle.spawn_blocking(move || {
            if let Err(e) = d.on_worker_died(&report) {
                eprintln!("[infantry] dead-worker notice for {} failed: {e:?}", report.ticket_id);
            }
        });
    }
    fn on_turn_end(&self, rec: &SessionRecord, outcome: &str, detail: &str) {
        let (id, ticket) = (rec.id.clone(), rec.ticket_id.clone());
        let (d, m) = (self.dispatcher.clone(), self.mail.clone());
        let report = ticket.map(|t| DoneReport { ticket_id: t, session_id: id.clone(), outcome: outcome.to_string(), detail: Some(detail.to_string()).filter(|s| !s.is_empty()) });
        self.handle.spawn_blocking(move || {
            if let Some(r) = report {
                if let Err(e) = d.on_done(&r) {
                    eprintln!("[infantry] done protocol for {} failed: {e:?}", r.ticket_id);
                }
            }
            m.session_idle(&id);
        });
    }
}

// ---- knowledge -----------------------------------------------------------------------------------------------

/// What the roles and library units ask of the daemon: the providers it runs and the roles live sessions hold.
pub struct KnowledgePorts {
    pub daemon: Arc<Daemon>,
}

impl infantry_knowledge::DaemonPorts for KnowledgePorts {
    fn provider_ids(&self) -> std::collections::HashSet<String> {
        self.daemon.providers().into_iter().collect()
    }
    fn live_role_ids(&self) -> std::collections::HashSet<String> {
        self.daemon.records().into_iter().filter(|r| !ended(r)).filter_map(|r| r.role).map(|r| r.trim().to_ascii_lowercase()).collect()
    }
}

#[cfg(test)]
mod worker_count_tests {
    use super::*;

    fn rec(state: &str, ticket: Option<&str>, role: Option<&str>) -> SessionRecord {
        let mut v = serde_json::json!({ "id": "s1", "provider": "claude", "workingDir": ".", "spawnedAt": 0, "lastHeartbeat": 0, "state": state });
        if let Some(t) = ticket {
            v["ticketId"] = t.into();
        }
        if let Some(r) = role {
            v["role"] = r.into();
        }
        serde_json::from_value(v).expect("record")
    }

    #[test]
    fn only_a_working_ticket_session_counts() {
        assert!(counts_as_worker(&rec("BUSY", Some("t1"), Some("backend"))));
        assert!(counts_as_worker(&rec("STARTING", Some("t1"), None)));
        assert!(!counts_as_worker(&rec("IDLE", Some("t1"), Some("backend"))), "idle");
        assert!(!counts_as_worker(&rec("WAITING_INPUT", Some("t1"), None)), "waiting");
        assert!(!counts_as_worker(&rec("EXITED", Some("t1"), None)), "exited");
        assert!(!counts_as_worker(&rec("BUSY", None, Some("backend"))), "no ticket");
        assert!(!counts_as_worker(&rec("BUSY", Some("t1"), Some("PM"))), "the pm");
    }
}
