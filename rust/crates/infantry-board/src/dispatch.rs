// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The work-running half of the board: assign / unassign, queues, starts (`ticket_start`,
//! `ticket_start_next`, a move into in_progress), lane caps, routing, the start brief, and the done
//! protocol (done comment, move to done, the lane's next ticket starts; failure notices otherwise).
//!
//! # Starts never block each other (START-HANG-1 does not exist here)
//!
//! A start has three phases, and the board lock is held only inside the two short ones:
//! 1. **plan** (no board lock, the dispatcher's own `flight` lock for a few map operations): read the
//!    board, resolve the lane and profile, RESERVE the ticket and its lane seat. A second start of the
//!    same ticket is refused at once; a full lane queues the ticket at once.
//! 2. **launch** (no lock at all): cut / reuse the worktree, compose the brief, launch the worker. It may
//!    take as long as git or a runner take; every board read and write answers meanwhile. At most
//!    [`BoardEnv::max_parallel_starts`] launch phases run at once; one more is DEFERRED (answered at once,
//!    run on its own thread when a slot frees), never waited on.
//! 3. **commit** (one board transaction): re-check the ticket did not change, put it in_progress with its
//!    session and run row. If it changed, or the commit fails, the new worker is stopped.
//!
//! No lock is ever held while calling the [`SessionLauncher`] or [`WorkspaceProvider`], so they may call
//! back into the board freely.
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{Arc, Mutex, MutexGuard, Weak};
use std::time::{Duration, Instant};

use infantry_core::bus::EventBus;
use infantry_orchestrator_api::dto::{link_type, status, AgentSwitch, CommentAdd, CommentAuthor, TicketMove, TicketStart};
use infantry_orchestrator_api::{
    hooks, AgentQueueEntry, AgentQueueRequest, AgentQueueState, DoneAnswer, DoneReport, LaunchRequest, Limit, OrchError,
    SessionLauncher, StartNextRequest, StartOrigin, TicketAnswer, TicketAssign, TicketDone, TicketUnassign, WorkerState,
    WorkspaceProvider, WorkspaceRequest,
};
use infantry_protocol::Envelope;
use serde_json::{json, Value};

use crate::board::{ticket_envelope, Board};
use crate::brief::{self, BriefInput};
use crate::env::{BoardAgent, BoardEnv};
use crate::rules::{self, board_order, holders, take};
use crate::store::{self, LinkRow, TicketRow};
use crate::views::{executor_of, effort_of, resolve_context_limit, routing_choice};

/// `TicketStart.reason` values.
pub mod reason {
    pub const STARTED: &str = "started";
    pub const QUEUED: &str = "queued";
    pub const DEFERRED: &str = "deferred";
    pub const HELD: &str = "held";
    pub const REFUSED: &str = "start_refused";
    pub const ALREADY_IN_PROGRESS: &str = "already_in_progress";
}

/// The run outcome of a clean exit (`FinishedPayload.Outcome.SUCCEEDED`).
pub const SUCCEEDED: &str = "SUCCEEDED";
/// How often a pending unassign looks whether the busy worker's turn ended.
pub const UNASSIGN_POLL: Duration = Duration::from_millis(500);
/// Candidates `ticket_start_next` names in its refusal.
pub const MAX_REFUSALS_SHOWN: usize = 3;
pub const QUEUE_ACTIONS: [&str; 5] = ["list", "add", "remove", "move", "clear"];

/// Who a worker runs as.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Seat {
    pub agent_id: Option<String>,
    pub name: Option<String>,
    pub role: Option<String>,
    pub project_id: Option<String>,
}

/// The executor a start resolved.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Executor {
    pub provider: Option<String>,
    pub model: Option<String>,
    pub effort: Option<String>,
    pub context_limit: Option<i32>,
    pub runner: String,
    pub notes: Vec<String>,
}

#[derive(Default)]
struct Flight {
    /// Tickets between plan and commit, with the seat they hold.
    starting: HashMap<String, Seat>,
    /// Launch phases running now.
    slots: usize,
    deferred: VecDeque<Deferred>,
    /// Who each started worker runs as (session id -> seat).
    running: HashMap<String, Seat>,
    /// Workers reported ended (they hold no seat even if the launcher still lists them).
    ended: HashSet<String>,
}

struct Deferred {
    id: String,
    origin: StartOrigin,
    started_by: Option<String>,
    seat: Seat,
}

/// One live worker of an in_progress ticket.
struct Worker {
    ticket_id: String,
    seat: Seat,
}

enum Lane {
    Free(Seat),
    Busy(String),
    Refuse(String),
}

pub struct Dispatcher {
    board: Arc<Board>,
    env: Arc<dyn BoardEnv>,
    workspaces: Arc<dyn WorkspaceProvider>,
    sessions: Arc<dyn SessionLauncher>,
    bus: EventBus,
    flight: Mutex<Flight>,
    me: Weak<Dispatcher>,
}

fn started(detail: Option<String>) -> TicketStart {
    TicketStart { ok: true, reason: reason::STARTED.into(), detail }
}

fn not_started(why: &str, detail: impl Into<String>) -> TicketStart {
    TicketStart { ok: false, reason: why.into(), detail: Some(detail.into()) }
}

/// The role a ticket's agent id names (`role:x`, or the profile's role).
fn role_of(env: &dyn BoardEnv, agent_id: Option<&str>) -> Option<String> {
    let a = agent_id?;
    match a.strip_prefix("role:") {
        Some(r) => Some(r.to_string()),
        None => env.agent(a).and_then(|p| p.role),
    }
}

fn seat_of_row(env: &dyn BoardEnv, row: &TicketRow) -> Seat {
    let agent = row.agent_id.as_deref().filter(|a| !a.starts_with("role:"));
    Seat {
        agent_id: agent.map(str::to_string),
        name: agent.and_then(|a| env.agent(a)).map(|p| p.name),
        role: role_of(env, row.agent_id.as_deref()),
        project_id: row.project_id.clone(),
    }
}

fn prerequisites(id: &str, links: &[LinkRow]) -> Vec<String> {
    links.iter().filter(|l| l.r#type == link_type::BLOCKED_BY && l.ticket_id == id).map(|l| l.related_id.clone()).collect()
}

fn title_case(role: &str) -> String {
    role.split('-')
        .map(|p| {
            let mut cs = p.chars();
            cs.next().map(|c| c.to_uppercase().collect::<String>() + cs.as_str()).unwrap_or_default()
        })
        .collect::<Vec<_>>()
        .join(" ")
}

impl Dispatcher {
    pub fn new(
        board: Arc<Board>,
        workspaces: Arc<dyn WorkspaceProvider>,
        sessions: Arc<dyn SessionLauncher>,
        bus: EventBus,
    ) -> Arc<Dispatcher> {
        let env = board.env().clone();
        Arc::new_cyclic(|me| Dispatcher { board, env, workspaces, sessions, bus, flight: Mutex::new(Flight::default()), me: me.clone() })
    }

    pub fn board(&self) -> &Arc<Board> {
        &self.board
    }

    fn flight(&self) -> MutexGuard<'_, Flight> {
        self.flight.lock().unwrap_or_else(|p| p.into_inner())
    }

    fn publish(&self) -> impl Fn(Envelope) + '_ {
        move |e| {
            self.bus.publish(e);
        }
    }

    /// The `ticket.moved` payload: the lane change plus what a lane hook (on-ticket-done) names: the ticket's branch and title and its
    /// worker session (hook args `branch`, `title`, `workerSession`).
    fn moved(row: &store::TicketRow, from: &str, to: &str, branch: Option<&str>) -> Value {
        let mut v = json!({ "from": from, "to": to, "title": row.title });
        if let Some(b) = branch.or(row.branch.as_deref()) {
            v["branch"] = json!(b);
        }
        if let Some(w) = row.session_id.as_deref() {
            v["workerSession"] = json!(w);
        }
        v
    }

    fn hook(&self, name: &str, ticket_id: &str, project_id: Option<&str>, extra: Value) {
        let mut payload = json!({ "ticketId": ticket_id, "projectId": project_id });
        if let (Some(p), Value::Object(e)) = (payload.as_object_mut(), extra) {
            p.extend(e);
        }
        self.bus.emit(name, payload);
    }

    fn refused(&self, id: &str, project_id: Option<&str>, detail: impl Into<String>) -> Result<TicketAnswer, OrchError> {
        let detail = detail.into();
        self.hook(hooks::TICKET_START_REFUSED, id, project_id, json!({ "reason": reason::REFUSED, "detail": detail }));
        Ok(TicketAnswer { ticket: self.board.view_with_start(id, not_started(reason::REFUSED, detail))?, pushed: false })
    }

    /// The live workers of in_progress tickets. Asks the launcher outside every lock.
    fn workers(&self, rows: &HashMap<String, TicketRow>) -> Vec<Worker> {
        let candidates: Vec<(&TicketRow, String)> = rows
            .values()
            .filter(|r| r.status == status::IN_PROGRESS)
            .filter_map(|r| r.session_id.clone().map(|s| (r, s)))
            .collect();
        let live: Vec<(&TicketRow, String)> =
            candidates.into_iter().filter(|(_, s)| self.sessions.state(s) != WorkerState::Ended).collect();
        let f = self.flight();
        live.into_iter()
            .filter(|(_, s)| !f.ended.contains(s))
            .map(|(r, s)| Worker {
                ticket_id: r.id.clone(),
                seat: f.running.get(&s).cloned().unwrap_or_else(|| seat_of_row(self.env.as_ref(), r)),
            })
            .collect()
    }

    /// The seats in use: live workers and reservations.
    fn seats<'a>(workers: &'a [Worker], f: &'a Flight) -> impl Iterator<Item = &'a Seat> {
        workers.iter().map(|w| &w.seat).chain(f.starting.values()).chain(f.deferred.iter().map(|d| &d.seat))
    }

    /// Lane resolution, under the flight lock (pure: env reads only, plus a profile save for a lane clone).
    fn lane(&self, assigned: &str, project: Option<&str>, workers: &[Worker], f: &Flight) -> Lane {
        let env = self.env.as_ref();
        let in_project: Vec<&Seat> = Self::seats(workers, f).filter(|s| s.project_id.as_deref() == project).collect();
        if let Some(max) = env.max_workers(project) {
            if in_project.len() as u32 >= max {
                return Lane::Busy(format!("project:{}", project.unwrap_or("none")));
            }
        }
        let occupied: HashSet<&str> = Self::seats(workers, f).filter_map(|s| s.agent_id.as_deref()).collect();
        if let Some(role) = assigned.strip_prefix("role:") {
            let running = in_project.iter().filter(|s| s.role.as_deref() == Some(role)).count() as u32;
            if running >= env.lane_cap(project, role) {
                return Lane::Busy(assigned.to_string());
            }
            let seat = |p: BoardAgent| Seat { agent_id: p.id, name: Some(p.name), role: p.role, project_id: project.map(str::to_string) };
            let matching: Vec<BoardAgent> = env.agents().into_iter().filter(|a| a.role.as_deref() == Some(role)).collect();
            if matching.is_empty() {
                // INSTALL-PROFILE-1: the role's first profile; a host that keeps no profiles runs the role itself.
                let template = BoardAgent { name: title_case(role), role: Some(role.to_string()), ..Default::default() };
                return Lane::Free(match env.create_agent(&template) {
                    Some(p) => seat(p),
                    None => Seat { agent_id: None, name: None, role: Some(role.to_string()), project_id: project.map(str::to_string) },
                });
            }
            if let Some(p) = matching.iter().find(|a| a.id.as_deref().is_some_and(|i| !occupied.contains(i))) {
                return Lane::Free(seat(p.clone()));
            }
            // LANE-CAPS: below the cap, one more agent of the role ("<name> <n>").
            let taken: HashSet<String> = env.agents().into_iter().map(|a| a.name).collect();
            let base = matching[0].clone();
            let name = (2..).map(|n| format!("{} {n}", base.name)).find(|n| !taken.contains(n)).expect("a free name");
            return match env.create_agent(&BoardAgent { id: None, name, ..base }) {
                Some(p) => Lane::Free(seat(p)),
                None => Lane::Busy(assigned.to_string()),
            };
        }
        let Some(p) = env.agent(assigned) else { return Lane::Refuse(format!("no agent profile '{}'", take(assigned, 80))) };
        if occupied.contains(assigned) {
            return Lane::Busy(assigned.to_string());
        }
        Lane::Free(Seat { agent_id: p.id, name: Some(p.name), role: p.role, project_id: project.map(str::to_string) })
    }

    /// The executor a start of `row` on `seat` runs with, or the refusal.
    fn executor(&self, row: &TicketRow, seat: &Seat, origin: StartOrigin, started_by: Option<&str>) -> Result<Executor, String> {
        let env = self.env.as_ref();
        let mut r = row.clone();
        if let Some(a) = &seat.agent_id {
            r.agent_id = Some(a.clone());
        } else if r.agent_id.is_none() {
            r.agent_id = seat.role.as_ref().map(|x| format!("role:{x}"));
        }
        let view = executor_of(env, &r);
        let field = |f: Option<&infantry_orchestrator_api::ExecutorField>| f.and_then(|x| x.value.clone());
        let provider = view.as_ref().and_then(|v| field(v.provider.as_ref())).or_else(|| env.default_provider());
        let model = view.as_ref().and_then(|v| field(v.model.as_ref()));
        let rule = routing_choice(env, &r, seat.role.as_deref());
        let effort = row
            .effort
            .clone()
            .or_else(|| rule.as_ref().and_then(|x| x.effort.clone()))
            .or_else(|| effort_of(env, &r))
            .or_else(|| env.weight_context(row.weight).and_then(|w| w.effort));
        let context_limit = resolve_context_limit(env, row).map(|c| c.0);
        let runner = row
            .runner
            .clone()
            .or_else(|| rule.as_ref().and_then(|x| x.runner.clone()))
            .unwrap_or_else(|| env.default_runner());
        let pm = format!("{}/{}", provider.as_deref().unwrap_or("default provider"), model.as_deref().unwrap_or("default model"));
        if let Some(why) = env.check_spawn(provider.as_deref(), model.as_deref()) {
            return Err(match &rule {
                Some(x) => format!("rule '{}': {pm} refused: {why}; the ticket stays in todo (no fallback): fix the rule or pin the ticket", x.name),
                None => why,
            });
        }
        let mut notes = Vec::new();
        if let Some(why) = env.pacing_refusal(provider.as_deref(), model.as_deref()) {
            // USAGE-PACE: only the owner's own move may go past the allowance, with a warning.
            if origin == StartOrigin::Move && started_by.is_none() {
                notes.push(why);
            } else {
                return Err(why);
            }
        }
        if let Some(x) = &rule {
            notes.push(format!("rule '{}': {pm}", x.name));
        } else if view.as_ref().and_then(|v| v.provider.as_ref()).is_some_and(|f| f.source == "weight") {
            notes.push(format!("weight {}: {pm}", row.weight.unwrap_or(0)));
        }
        if let Some(l) = context_limit {
            notes.push(format!("context limit {l}"));
        }
        if let Some(e) = &effort {
            notes.push(format!("effort {e}"));
        }
        if runner != "local" {
            notes.push(format!("on runner {runner}"));
        }
        Ok(Executor { provider, model, effort, context_limit, runner, notes })
    }

    // ---- start ---------------------------------------------------------------------------------------------

    /// Start one ticket. Answers at once whether it started, queued, was deferred, is held or was refused.
    pub fn start(&self, ticket_id: &str, origin: StartOrigin, started_by: Option<&str>) -> Result<TicketAnswer, OrchError> {
        let id = rules::check_id(ticket_id).map_err(OrchError::Refused)?;
        let (rows, links, _) = self.board.rows()?;
        let row = rows.get(&id).ok_or_else(|| OrchError::Refused(format!("no ticket '{}'", take(&id, 80))))?.clone();
        let project = row.project_id.clone();
        if let Some(why) = brief::status_refusal(&row) {
            return self.refused(&id, project.as_deref(), why);
        }
        if row.status == status::IN_PROGRESS {
            // GAP-006b: a start of an in_progress ticket restarts it only when its worker is gone.
            let gone = row.session_id.as_deref().is_none_or(|s| self.sessions.state(s) == WorkerState::Ended || self.flight().ended.contains(s));
            if !gone {
                let t = self.board.view_with_start(&id, TicketStart { ok: false, reason: reason::ALREADY_IN_PROGRESS.into(), detail: None })?;
                return Ok(TicketAnswer { ticket: t, pushed: false });
            }
        }
        let holding = holders(&prerequisites(&id, &links), &rows);
        if !holding.is_empty() {
            let ids: Vec<&str> = holding.iter().map(|r| r.id.as_str()).collect();
            return Ok(TicketAnswer { ticket: self.board.view_with_start(&id, not_started(reason::HELD, ids.join(", ")))?, pushed: false });
        }
        let env = self.env.as_ref();
        // ROUTING-1: a rule's setRole is the role of a ticket that has none; it never replaces the ticket's own.
        let rule = routing_choice(env, &row, role_of(env, row.agent_id.as_deref()).as_deref());
        let assigned = (if origin == StartOrigin::QueueDrain { row.queued_agent.clone() } else { None })
            .or_else(|| row.agent_id.clone())
            .or_else(|| rule.as_ref().and_then(|r| r.set_role.clone()).map(|r| format!("role:{r}")));
        let Some(assigned) = assigned else {
            return self.refused(&id, project.as_deref(), "ticket has no role; set one with `infantry ticket update <id> --role <role id>` or infantry_ticket_update's role");
        };
        if let Some(why) = row.mcp.as_deref().and_then(|m| env.mcp_preflight(&id, project.as_deref(), Some(&assigned), m)) {
            return self.refused(&id, project.as_deref(), why);
        }
        let workers = self.workers(&rows);
        let mut f = self.flight();
        if f.starting.contains_key(&id) || f.deferred.iter().any(|d| d.id == id) {
            drop(f);
            return self.refused(&id, project.as_deref(), "this ticket is already starting");
        }
        let seat = match self.lane(&assigned, project.as_deref(), &workers, &f) {
            Lane::Refuse(m) => {
                drop(f);
                return self.refused(&id, project.as_deref(), m);
            }
            Lane::Busy(lane) => {
                drop(f);
                return self.queue_on_lane(&row, &lane, origin);
            }
            Lane::Free(seat) => seat,
        };
        if f.slots >= self.env.max_parallel_starts().max(1) {
            f.deferred.push_back(Deferred { id: id.clone(), origin, started_by: started_by.map(str::to_string), seat });
            drop(f);
            let detail = "every start slot is busy: it starts when one frees (limits: max parallel starts)";
            return Ok(TicketAnswer { ticket: self.board.view_with_start(&id, not_started(reason::DEFERRED, detail))?, pushed: false });
        }
        f.slots += 1;
        f.starting.insert(id.clone(), seat.clone());
        drop(f);
        let answer = self.launch(&id, origin, started_by, &seat);
        self.release(&id);
        answer
    }

    /// Frees the ticket's reservation and its slot; a deferred start takes the slot on its own thread.
    fn release(&self, id: &str) {
        let next = {
            let mut f = self.flight();
            f.starting.remove(id);
            match f.deferred.pop_front() {
                Some(d) => {
                    f.starting.insert(d.id.clone(), d.seat.clone());
                    Some(d) // the slot passes to it
                }
                None => {
                    f.slots = f.slots.saturating_sub(1);
                    None
                }
            }
        };
        if let (Some(d), Some(me)) = (next, self.me.upgrade()) {
            std::thread::Builder::new()
                .name("board-start-deferred".into())
                .spawn(move || {
                    let _ = me.launch(&d.id, d.origin, d.started_by.as_deref(), &d.seat);
                    me.release(&d.id);
                })
                .map(|_| ())
                .unwrap_or_else(|_| self.release_slot());
        }
    }

    fn release_slot(&self) {
        let mut f = self.flight();
        f.slots = f.slots.saturating_sub(1);
    }

    /// A full lane: a start someone asked for waits in it; start-next and triggers are refused.
    fn queue_on_lane(&self, row: &TicketRow, lane: &str, origin: StartOrigin) -> Result<TicketAnswer, OrchError> {
        let project = row.project_id.as_deref();
        if matches!(origin, StartOrigin::StartNext | StartOrigin::Trigger) {
            return self.refused(&row.id, project, format!("profile lane '{lane}' is at capacity"));
        }
        let start = not_started(reason::QUEUED, lane);
        if row.queued_for.as_deref() == Some(lane) && row.queued_at.is_some() {
            return Ok(TicketAnswer { ticket: self.board.view_with_start(&row.id, start)?, pushed: false });
        }
        let (id, lane_s) = (row.id.clone(), lane.to_string());
        let a = self.board.apply(
            &id,
            Some(start),
            |s, _| {
                let now = s.now;
                let r = s.rows.get_mut(&id).ok_or("the ticket is gone")?;
                r.queued_for = Some(lane_s.clone());
                r.queued_at = Some(now);
                Ok(())
            },
            &self.publish(),
        )?;
        self.hook(hooks::TICKET_QUEUED, &row.id, project, json!({ "lane": lane }));
        Ok(a)
    }

    /// Phase 2 and 3: worktree, brief, launch (no lock), then the commit.
    fn launch(&self, id: &str, origin: StartOrigin, started_by: Option<&str>, seat: &Seat) -> Result<TicketAnswer, OrchError> {
        let (rows, links, runs) = self.board.rows()?;
        let Some(row) = rows.get(id).cloned() else { return Err(OrchError::Refused(format!("no ticket '{}'", take(id, 80)))) };
        let project = row.project_id.clone();
        let p = project.as_deref();
        let exec = match self.executor(&row, seat, origin, started_by) {
            Ok(e) => e,
            Err(m) => return self.refused(id, p, m),
        };
        let use_worktree = row.use_worktree.unwrap_or_else(|| self.workspaces.wants_worktree(p));
        let mut notes = Vec::new();
        let (workspace, folder) = if use_worktree {
            let base = row.epic_id.as_deref().and_then(|e| self.board.epic_base_branch(e));
            let req = WorkspaceRequest {
                ticket_id: id.to_string(),
                title: row.title.clone(),
                project_id: project.clone(),
                epic_id: row.epic_id.clone(),
                base_branch: base,
                recorded_branch: row.branch.clone(),
                recorded_base: row.base.clone(),
            };
            match self.workspaces.ensure(&req) {
                Ok(w) => {
                    notes.extend(w.note.clone());
                    (Some(w), None)
                }
                Err(m) => return self.refused(id, p, m),
            }
        } else {
            match self.workspaces.folder(p) {
                Ok(f) => (None, f),
                Err(m) => return self.refused(id, p, m),
            }
        };
        // Recorded before the launch (not pushed): a start refused after the cut leaves a branch the ticket knows about.
        if let Some(w) = &workspace {
            if row.branch.as_deref() != Some(&w.branch) || row.base.as_deref() != Some(&w.base) {
                let (b, base, tid) = (w.branch.clone(), w.base.clone(), id.to_string());
                self.board.apply(
                    id,
                    None,
                    |s, _| {
                        let r = s.rows.get_mut(&tid).ok_or("the ticket is gone")?;
                        r.branch = Some(b.clone());
                        r.base = Some(base.clone());
                        Ok(())
                    },
                    &|_| {},
                )?;
            }
        }
        let comments = self.board.comment_store().and_then(|c| c.read(id, "all", None, None)).unwrap_or_default();
        let input = BriefInput {
            row: &row,
            rows: &rows,
            links: &links,
            runs: &runs.iter().filter(|r| r.ticket_id == id).cloned().collect::<Vec<_>>(),
            comments: &comments,
            role: seat.role.as_deref(),
            agent_id: seat.agent_id.as_deref(),
            agent_name: seat.name.as_deref(),
            report_to: started_by.map(|s| self.env.session_label(s).unwrap_or_else(|| s.to_string())),
            include_linked: row
                .include_linked_tasks_context
                .or_else(|| self.env.context_settings(p).and_then(|c| c.include_linked_tasks_context))
                .unwrap_or(true),
        };
        let composed = brief::compose(self.env.as_ref(), &input);
        let request = LaunchRequest {
            ticket_id: id.to_string(),
            project_id: project.clone(),
            title: row.title.clone(),
            role: seat.role.clone(),
            agent_id: seat.agent_id.clone(),
            provider: exec.provider.clone(),
            model: exec.model.clone(),
            effort: exec.effort.clone(),
            context_limit: exec.context_limit,
            runner: exec.runner.clone(),
            workspace: workspace.clone(),
            folder,
            brief: composed.prompt,
            brief_sources: composed.sources,
            origin,
        };
        let launched = match self.sessions.launch(&request) {
            Ok(l) => l,
            Err(m) => return self.refused(id, p, m),
        };
        let sid = launched.session_id.clone();
        let ws = launched.workspace.clone().or(workspace);
        notes.extend(exec.notes.iter().cloned());
        notes.extend(launched.note.clone());
        let detail = (!notes.is_empty()).then(|| notes.join("; "));
        let (orig_status, orig_session) = (row.status.clone(), row.session_id.clone());
        let tid = id.to_string();
        let starter = started_by.map(str::to_string);
        let seat_agent = seat.agent_id.clone();
        let committed = self.board.apply(
            id,
            Some(started(detail)),
            |s, conn| {
                let cur = s.require(&tid)?;
                if cur.status != orig_status || cur.session_id != orig_session {
                    return Err("the ticket changed while its worker was starting; the new worker was stopped".into());
                }
                s.place(&tid, status::IN_PROGRESS, None);
                let now = s.now;
                let r = s.rows.get_mut(&tid).expect("placed");
                if origin == StartOrigin::QueueDrain && cur.queued_agent.is_some() {
                    r.agent_id = cur.queued_agent.clone();
                } else if r.agent_id.is_none() {
                    r.agent_id = seat_agent.clone();
                }
                r.session_id = Some(sid.clone());
                r.started_by = starter.clone();
                r.queued_for = None;
                r.queued_at = None;
                if let Some(w) = &ws {
                    r.branch = Some(w.branch.clone());
                    r.base = Some(w.base.clone());
                }
                s.named.insert(tid.clone());
                store::insert_run(conn, &tid, &sid, now).map_err(|e| format!("could not record the run: {e}"))?;
                Ok(())
            },
            &self.publish(),
        );
        let answer = match committed {
            Ok(a) => a,
            Err(e) => {
                // Spawn first, commit second: a failed commit never leaves a worker running against a todo ticket.
                self.sessions.stop(&launched.session_id, "ticket start rollback");
                return match e {
                    OrchError::Refused(m) => self.refused(id, p, m),
                    other => Err(other),
                };
            }
        };
        self.flight().running.insert(launched.session_id.clone(), seat.clone());
        self.hook(
            hooks::TICKET_STARTED,
            id,
            p,
            json!({
                "sessionId": launched.session_id, "agentId": seat.agent_id, "role": seat.role,
                "provider": exec.provider, "model": exec.model, "branch": answer.ticket.branch, "startedBy": started_by,
            }),
        );
        self.hook(hooks::TICKET_MOVED, id, p, json!({ "from": orig_status, "to": status::IN_PROGRESS }));
        Ok(answer)
    }

    /// `ticket_start_next`: the highest ready todo ticket (not in an agent's queue), optionally of a project / agent.
    pub fn start_next(&self, req: &StartNextRequest) -> Result<TicketAnswer, OrchError> {
        let (rows, links, _) = self.board.rows()?;
        let wanted_agent = req.agent_id.as_deref().map(str::trim).filter(|s| !s.is_empty());
        let candidates: Vec<String> = board_order(rows.values())
            .into_iter()
            .filter(|r| r.status == status::TODO && r.queued_agent.is_none())
            .filter(|r| holders(&prerequisites(&r.id, &links), &rows).is_empty())
            .filter(|r| req.project_id.is_none() || r.project_id == req.project_id)
            .filter(|r| wanted_agent.is_none_or(|a| self.matches_agent(r, a)))
            .map(|r| r.id.clone())
            .collect();
        let mut missed = Vec::new();
        for id in candidates {
            let a = self.start(&id, StartOrigin::StartNext, req.started_by.as_deref())?;
            match a.ticket.start.as_ref().map(|s| s.reason.as_str()) {
                Some(reason::STARTED) | Some(reason::DEFERRED) => return Ok(a),
                _ => missed.push(a.ticket),
            }
        }
        if missed.is_empty() {
            return Err(OrchError::Refused("nothing startable".into()));
        }
        let shown: Vec<String> = missed
            .iter()
            .take(MAX_REFUSALS_SHOWN)
            .map(|t| {
                let s = t.start.as_ref();
                let why: Vec<&str> = [s.map(|s| s.reason.as_str()).filter(|r| *r != reason::REFUSED), s.and_then(|s| s.detail.as_deref())]
                    .into_iter()
                    .flatten()
                    .collect();
                let why = if why.is_empty() { "not started".to_string() } else { why.join(": ") };
                format!("{} \"{}\" did not start: {why}", t.id, take(&t.title, 60))
            })
            .collect();
        let more = missed.len().saturating_sub(MAX_REFUSALS_SHOWN);
        let tail = if more > 0 { format!("; and {more} more did not start") } else { String::new() };
        Err(OrchError::Refused(format!("nothing startable: {}{tail}", shown.join("; "))))
    }

    fn matches_agent(&self, row: &TicketRow, filter: &str) -> bool {
        match (row.agent_id.as_deref(), filter.strip_prefix("role:")) {
            (Some(a), _) if a == filter => true,
            (Some(a), Some(role)) => role_of(self.env.as_ref(), Some(a)).as_deref() == Some(role),
            _ => false,
        }
    }

    // ---- moves, assign, unassign, queues ---------------------------------------------------------------

    /// `ticket_move` with the work half: a move into in_progress starts the ticket; a worker's ticket leaving
    /// in_progress frees its lane.
    pub fn move_lane(&self, req: TicketMove, started_by: Option<&str>) -> Result<TicketAnswer, OrchError> {
        let id = rules::check_id(&req.id).map_err(OrchError::Refused)?;
        let target = req.status.as_deref().map(rules::check_status).transpose().map_err(OrchError::Refused)?;
        let (rows, _, _) = self.board.rows()?;
        let before = rows.get(&id).cloned().ok_or_else(|| OrchError::Refused(format!("no ticket '{}'", take(&id, 80))))?;
        if target.as_deref() == Some(status::IN_PROGRESS) && (before.status != status::IN_PROGRESS || req.position.is_none()) {
            return self.start(&id, StartOrigin::Move, started_by);
        }
        let a = self.board.move_lane(req, &self.publish())?;
        if a.ticket.status != before.status {
            self.hook(hooks::TICKET_MOVED, &id, before.project_id.as_deref(), Self::moved(&before, &before.status, &a.ticket.status, a.ticket.branch.as_deref()));
        }
        if before.status == status::IN_PROGRESS && a.ticket.status != status::IN_PROGRESS {
            if let Some(sid) = before.session_id.as_deref() {
                if a.ticket.status == status::DONE {
                    self.hook(hooks::TICKET_DONE, &id, before.project_id.as_deref(), json!({ "sessionId": sid, "commentId": null }));
                }
                // AUTOSTART-SAME-AGENT-1: a worker at rest held its seat; no exit follows, so its lane drains now.
                if self.sessions.state(sid) != WorkerState::Busy {
                    let seat = self.leave(sid, &before);
                    self.drain(&seat);
                }
            }
        }
        Ok(a)
    }

    /// The worker `sid` of `row` no longer holds a seat; answers the seat it held.
    fn leave(&self, sid: &str, row: &TicketRow) -> Seat {
        let mut f = self.flight();
        f.ended.insert(sid.to_string());
        f.running.remove(sid).unwrap_or_else(|| seat_of_row(self.env.as_ref(), row))
    }

    fn resolve_agent(&self, reference: &str) -> Result<BoardAgent, OrchError> {
        let r = reference.trim();
        if let Some(a) = self.env.agent(r) {
            return Ok(a);
        }
        let named: Vec<BoardAgent> = self.env.agents().into_iter().filter(|a| a.name.eq_ignore_ascii_case(r)).collect();
        match named.len() {
            1 => Ok(named.into_iter().next().expect("one")),
            0 => Err(OrchError::Refused(format!("no agent '{}' (agent_list shows the profiles)", take(r, 80)))),
            n => Err(OrchError::Refused(format!("'{}' names {n} agents: use the profile id", take(r, 80)))),
        }
    }

    /// AGENT-TASKS-4: give a todo ticket to an agent: start it now, or (queue) add it to the agent's queue.
    pub fn assign(&self, req: &TicketAssign) -> Result<TicketAnswer, OrchError> {
        let id = rules::check_id(&req.id).map_err(OrchError::Refused)?;
        let agent = self.resolve_agent(&req.agent_id)?;
        let agent_id = agent.id.clone().ok_or_else(|| OrchError::Refused(format!("agent '{}' has no profile id", agent.name)))?;
        let (rows, _, _) = self.board.rows()?;
        let row = rows.get(&id).ok_or_else(|| OrchError::Refused(format!("no ticket '{}'", take(&id, 80))))?;
        if row.status != status::TODO {
            return Err(OrchError::Refused(format!("ticket {id} is {}: only a todo ticket can be assigned", row.status)));
        }
        if let Some(m) = self.env.role_refusal(agent.role.as_deref()) {
            return Err(OrchError::Refused(m));
        }
        if req.queue {
            self.agent_queue(&AgentQueueRequest { agent_id: agent_id.clone(), action: "add".into(), ticket_id: Some(id.clone()), at: req.at })?;
            return Ok(TicketAnswer { ticket: self.board.get(&id)?, pushed: false });
        }
        if let Some(w) = self.workers(&rows).into_iter().find(|w| w.seat.agent_id.as_deref() == Some(agent_id.as_str())) {
            return Err(OrchError::Refused(format!("agent {} is on {}; use assign --queue", agent.name, w.ticket_id)));
        }
        let (tid, aid) = (id.clone(), agent_id.clone());
        self.board.apply(
            &id,
            None,
            |s, _| {
                s.rows.get_mut(&tid).ok_or("the ticket is gone")?.agent_id = Some(aid.clone());
                Ok(())
            },
            &self.publish(),
        )?;
        self.start(&id, StartOrigin::Move, None)
    }

    /// AGENT-TASKS-2: an in_progress ticket goes back to the top of todo (branch, base and agent kept); its worker
    /// ends now (idle, or `now`) or when its turn ends (bounded by `unassign_wait_secs`). A handoff comment records it.
    pub fn unassign(&self, req: &TicketUnassign) -> Result<TicketAnswer, OrchError> {
        self.unassign_with(req, None)
    }

    /// AGENT-TASKS-4: move `agent_ref` from its in-progress ticket to `ticket_id`. ONE commit: the current ticket goes to
    /// todo (top, handoff) and `ticket_id` heads the agent's queue; the worker then ends like an unassign (`now` honoured)
    /// and its exit starts the new ticket, so the agent never holds two in-progress tickets. With no current ticket this
    /// is an assign that starts.
    pub fn switch(&self, req: &AgentSwitch) -> Result<TicketAnswer, OrchError> {
        let to = rules::check_id(&req.ticket_id).map_err(OrchError::Refused)?;
        let agent = self.resolve_agent(&req.agent_id)?;
        let agent_id = agent.id.clone().ok_or_else(|| OrchError::Refused(format!("agent '{}' has no profile id", agent.name)))?;
        let (rows, _, _) = self.board.rows()?;
        let current = self.workers(&rows).into_iter().find(|w| w.seat.agent_id.as_deref() == Some(agent_id.as_str())).map(|w| w.ticket_id);
        let Some(current) = current else {
            return self.assign(&TicketAssign { id: to, agent_id, queue: false, at: None });
        };
        if current == to {
            return Err(OrchError::Refused(format!("agent {} is already on {to}", agent.name)));
        }
        let target = rows.get(&to).ok_or_else(|| OrchError::Refused(format!("no ticket '{}'", take(&to, 80))))?;
        if target.status != status::TODO {
            return Err(OrchError::Refused(format!("ticket {to} is {}: only a todo ticket can be switched to", target.status)));
        }
        if let Some(m) = self.env.role_refusal(agent.role.as_deref()) {
            return Err(OrchError::Refused(m));
        }
        let queued = self.queue_ids(&agent_id)?.into_iter().map(|(i, _)| i).collect::<Vec<_>>();
        let rest: Vec<String> = queued.iter().filter(|q| **q != to).cloned().collect();
        if !queued.contains(&to) && rest.len() >= self.env.queue_max() {
            return Err(OrchError::Refused(format!("agent {}'s queue is full (limits.agentTasks.queueMax = {})", agent.name, self.env.queue_max())));
        }
        let next: Vec<String> = std::iter::once(to.clone()).chain(rest).collect();
        let aid = agent_id.clone();
        let also = move |s: &mut rules::Snapshot| -> rules::Rule<()> {
            for r in s.rows.values_mut().filter(|r| r.queued_agent.as_deref() == Some(aid.as_str()) && !next.contains(&r.id)) {
                r.queued_agent = None;
                r.queue_rank = None;
            }
            for (i, tid) in next.iter().enumerate() {
                let r = s.rows.get_mut(tid).ok_or("a queued ticket is gone")?;
                r.agent_id = Some(aid.clone());
                r.queued_agent = Some(aid.clone());
                r.queue_rank = Some(i as i32 + 1);
            }
            Ok(())
        };
        self.unassign_with(&TicketUnassign { id: current, now: req.now, note: None }, Some(&also))?;
        let mut answer = TicketAnswer { ticket: self.board.get(&to)?, pushed: false };
        answer.ticket.start = Some(TicketStart { ok: true, reason: "deferred".into(), detail: Some(format!("worker ends after its turn; {to} starts on exit")) });
        Ok(answer)
    }

    fn unassign_with(&self, req: &TicketUnassign, also: Option<&dyn Fn(&mut rules::Snapshot) -> rules::Rule<()>>) -> Result<TicketAnswer, OrchError> {
        let id = rules::check_id(&req.id).map_err(OrchError::Refused)?;
        let (rows, _, _) = self.board.rows()?;
        let row = rows.get(&id).cloned().ok_or_else(|| OrchError::Refused(format!("no ticket '{}'", take(&id, 80))))?;
        if row.status != status::IN_PROGRESS {
            return Err(OrchError::Refused(format!(
                "ticket {id} is {}, not in_progress: only an in_progress ticket can be unassigned",
                row.status
            )));
        }
        let sid = row.session_id.clone();
        let (tid, s2) = (id.clone(), sid.clone());
        let answer = self.board.apply(
            &id,
            None,
            |s, conn| {
                s.place(&tid, status::TODO, Some(0));
                s.rows.get_mut(&tid).expect("placed").session_id = None;
                s.named.insert(tid.clone());
                if let Some(sid) = &s2 {
                    store::finish_run(conn, &tid, sid, s.now, "KILLED_GRACEFUL", "unassigned").map_err(|e| e.to_string())?;
                }
                if let Some(f) = also {
                    f(s)?;
                }
                Ok(())
            },
            &self.publish(),
        )?;
        let note = req.note.as_deref().map(str::trim).filter(|n| !n.is_empty());
        let body = format!(
            "Unassigned{}. Branch: {}. {}",
            sid.as_deref().map(|s| format!(" from worker {s}")).unwrap_or_default(),
            row.branch.as_deref().unwrap_or("none"),
            note.unwrap_or("No handoff note was given.")
        );
        let _ = self.comment(&id, &body, "handoff", CommentAuthor::daemon());
        self.hook(hooks::TICKET_UNASSIGNED, &id, row.project_id.as_deref(), json!({ "sessionId": sid, "note": note }));
        if let Some(sid) = sid {
            let seat = self.leave(&sid, &row);
            if req.now || self.sessions.state(&sid) != WorkerState::Busy {
                self.sessions.stop(&sid, "unassigned");
                self.drain(&seat);
            } else if let Some(me) = self.me.upgrade() {
                let _ = self.sessions.prompt(&sid, "Your ticket was unassigned: finish this turn and write a short handoff note; you will be stopped.");
                let wait = Duration::from_secs(self.env.unassign_wait_secs());
                std::thread::Builder::new()
                    .name("board-unassign-wait".into())
                    .spawn(move || {
                        let until = Instant::now() + wait;
                        while Instant::now() < until && me.sessions.state(&sid) == WorkerState::Busy {
                            std::thread::sleep(UNASSIGN_POLL);
                        }
                        me.sessions.stop(&sid, "unassigned");
                        me.drain(&seat);
                    })
                    .ok();
            }
        }
        Ok(answer)
    }

    fn queue_ids(&self, agent_id: &str) -> Result<Vec<(String, String)>, OrchError> {
        let (rows, links, _) = self.board.rows()?;
        let mut q: Vec<&TicketRow> = board_order(rows.values())
            .into_iter()
            .filter(|r| r.status == status::TODO && r.queued_agent.as_deref() == Some(agent_id))
            .filter(|r| holders(&prerequisites(&r.id, &links), &rows).is_empty())
            .collect();
        q.sort_by_key(|r| r.queue_rank.unwrap_or(i32::MAX));
        Ok(q.into_iter().map(|r| (r.id.clone(), r.title.clone())).collect())
    }

    /// AGENT-TASKS-3: list or edit an agent's queue.
    pub fn agent_queue(&self, req: &AgentQueueRequest) -> Result<AgentQueueState, OrchError> {
        let agent = self.resolve_agent(&req.agent_id)?;
        let agent_id = agent.id.clone().ok_or_else(|| OrchError::Refused(format!("agent '{}' has no profile id", agent.name)))?;
        let action = req.action.trim().to_lowercase();
        if !QUEUE_ACTIONS.contains(&action.as_str()) {
            return Err(OrchError::Refused(format!("unknown queue action '{}': use {}", take(&action, 40), QUEUE_ACTIONS.join("|"))));
        }
        let mut message = None;
        if action != "list" {
            let refuse = |m: String| Err(OrchError::Refused(m));
            let id = if action == "clear" {
                None
            } else {
                Some(rules::check_id(req.ticket_id.as_deref().unwrap_or("")).map_err(|_| OrchError::Refused(format!("queue {action} needs a ticket id")))?)
            };
            if action == "move" && req.at.is_none() {
                return refuse("queue move needs a place (at)".into());
            }
            if req.at.is_some_and(|a| a < 1) {
                return refuse("place must be 1 or more".into());
            }
            let mut ordered: Vec<String> = self.queue_ids(&agent_id)?.into_iter().map(|(i, _)| i).collect();
            match action.as_str() {
                "add" | "move" => {
                    let id = id.clone().expect("id");
                    let row = self.board.rows()?.0.remove(&id).ok_or_else(|| OrchError::Refused(format!("no ticket '{}'", take(&id, 80))))?;
                    if row.status != status::TODO {
                        return refuse(format!("ticket {id} is {}: only a todo ticket can be queued", row.status));
                    }
                    if row.queued_agent.as_deref().is_some_and(|q| q != agent_id) {
                        return refuse(format!("ticket {id} is already queued for agent {}: remove it there first", row.queued_agent.unwrap_or_default()));
                    }
                    let member = ordered.contains(&id);
                    if action == "move" && !member {
                        return refuse(format!("ticket {id} is not in agent {}'s queue", agent.name));
                    }
                    if !member && ordered.len() >= self.env.queue_max() {
                        return refuse(format!("agent {}'s queue is full (limits.agentTasks.queueMax = {})", agent.name, self.env.queue_max()));
                    }
                    ordered.retain(|x| *x != id);
                    let at = (req.at.unwrap_or(ordered.len() as i32 + 1) - 1).clamp(0, ordered.len() as i32) as usize;
                    ordered.insert(at, id);
                }
                "remove" => {
                    let id = id.clone().expect("id");
                    if !ordered.contains(&id) {
                        return refuse(format!("ticket {id} is not in agent {}'s queue", agent.name));
                    }
                    ordered.retain(|x| *x != id);
                }
                _ => {
                    message = Some(format!("cleared {}", ordered.len()));
                    ordered.clear();
                }
            }
            let primary = id.clone().or_else(|| ordered.first().cloned());
            let aid = agent_id.clone();
            let list = ordered.clone();
            let op = |s: &mut rules::Snapshot, _: &rusqlite::Connection| -> rules::Rule<()> {
                for r in s.rows.values_mut().filter(|r| r.queued_agent.as_deref() == Some(aid.as_str()) && !list.contains(&r.id)) {
                    r.queued_agent = None;
                    r.queue_rank = None;
                }
                for (i, tid) in list.iter().enumerate() {
                    let r = s.rows.get_mut(tid).ok_or("a queued ticket is gone")?;
                    r.agent_id = Some(aid.clone());
                    r.queued_agent = Some(aid.clone());
                    r.queue_rank = Some(i as i32 + 1);
                }
                Ok(())
            };
            match primary {
                Some(p) => {
                    self.board.apply(&p, None, op, &self.publish())?;
                }
                None => {
                    // A clear of an empty queue: nothing to write.
                }
            }
            self.bus.emit(hooks::QUEUE_CHANGED, json!({ "agentId": agent_id, "tickets": ordered }));
        }
        let tickets = self
            .queue_ids(&agent_id)?
            .into_iter()
            .enumerate()
            .map(|(i, (ticket_id, title))| AgentQueueEntry { ticket_id, title, rank: i as i32 + 1 })
            .collect();
        Ok(AgentQueueState { agent_id, name: Some(agent.name), tickets, message })
    }

    /// How many workers a project runs and may run.
    pub fn spawn_limit(&self, project_id: Option<&str>) -> Result<Limit, OrchError> {
        let (rows, _, _) = self.board.rows()?;
        let workers = self.workers(&rows);
        let f = self.flight();
        let running = Self::seats(&workers, &f).filter(|s| project_id.is_none() || s.project_id.as_deref() == project_id).count() as u32;
        Ok(Limit { project_id: project_id.map(str::to_string), max: self.env.max_workers(project_id), running })
    }

    /// LANE-CAPS follow-up: a project's caps changed; its queued role-lane tickets start while their lane has room.
    pub fn lanes_changed(&self, project_id: &str) -> Result<Vec<TicketAnswer>, OrchError> {
        let (rows, _, _) = self.board.rows()?;
        let queued: Vec<String> = board_order(rows.values())
            .into_iter()
            .filter(|r| r.status == status::TODO && r.project_id.as_deref() == Some(project_id) && r.queued_agent.is_none())
            .filter(|r| r.queued_for.as_deref().is_some_and(|q| q.starts_with("role:")))
            .map(|r| r.id.clone())
            .collect();
        let mut full: HashSet<String> = HashSet::new();
        let mut out = Vec::new();
        for id in queued {
            let lane = rows[&id].queued_for.clone().unwrap_or_default();
            if full.contains(&lane) {
                continue;
            }
            let a = self.start(&id, StartOrigin::QueueDrain, None)?;
            if !a.ticket.start.as_ref().is_some_and(|s| s.ok || s.reason == reason::DEFERRED) {
                full.insert(lane);
            }
            out.push(a);
        }
        Ok(out)
    }

    // ---- the done protocol ------------------------------------------------------------------------------

    fn comment(&self, ticket_id: &str, body: &str, kind: &str, author: CommentAuthor) -> Result<infantry_orchestrator_api::Comment, OrchError> {
        let c = self.board.comment_add(CommentAdd {
            ticket_id: ticket_id.to_string(),
            body: body.to_string(),
            kind: Some(kind.to_string()),
            state: None,
            author,
        })?;
        crate::local::emit_commented(&self.bus, &c);
        Ok(c)
    }

    /// The next ticket of a seat's lane: the agent's own queue, then what waits on its lane, then (auto start) the
    /// top ready ticket assigned to the same agent or role. None = nothing started.
    pub fn drain(&self, seat: &Seat) -> Option<TicketAnswer> {
        if !self.env.auto_start_next() {
            return None;
        }
        let (rows, links, _) = self.board.rows().ok()?;
        let ready = |r: &&TicketRow| r.status == status::TODO && holders(&prerequisites(&r.id, &links), &rows).is_empty();
        let ordered: Vec<&TicketRow> = board_order(rows.values()).into_iter().filter(ready).collect();
        let lane_role = seat.role.as_ref().map(|r| format!("role:{r}"));
        let mut candidates: Vec<String> = Vec::new();
        if let Some(a) = &seat.agent_id {
            let mut own: Vec<&&TicketRow> = ordered.iter().filter(|r| r.queued_agent.as_deref() == Some(a.as_str())).collect();
            own.sort_by_key(|r| r.queue_rank.unwrap_or(i32::MAX));
            candidates.extend(own.into_iter().map(|r| r.id.clone()));
        }
        let on_lane = |q: Option<&str>| q.is_some() && (q == seat.agent_id.as_deref() || q == lane_role.as_deref());
        candidates.extend(ordered.iter().filter(|r| r.queued_agent.is_none() && on_lane(r.queued_for.as_deref())).map(|r| r.id.clone()));
        candidates.extend(
            ordered
                .iter()
                .filter(|r| r.queued_agent.is_none() && r.queued_for.is_none() && r.project_id == seat.project_id)
                .filter(|r| {
                    (seat.agent_id.is_some() && r.agent_id == seat.agent_id)
                        || (lane_role.is_some() && (r.agent_id == lane_role || role_of(self.env.as_ref(), r.agent_id.as_deref()) == seat.role))
                })
                .map(|r| r.id.clone()),
        );
        let mut seen = HashSet::new();
        for id in candidates.into_iter().filter(|i| seen.insert(i.clone())) {
            if let Ok(a) = self.start(&id, StartOrigin::QueueDrain, None) {
                if a.ticket.start.as_ref().is_some_and(|s| s.ok || s.reason == reason::DEFERRED) {
                    return Some(a);
                }
            }
        }
        None
    }

    fn starter_of(&self, ticket_id: &str) -> Option<String> {
        self.board.rows().ok()?.0.remove(ticket_id)?.started_by
    }

    /// The worker's done: its done comment, the move to done, the worker ends, the lane's next ticket starts.
    pub fn done(&self, req: &TicketDone) -> Result<DoneAnswer, OrchError> {
        let t = self.board.find(&req.ticket_id)?;
        let (rows, _, _) = self.board.rows()?;
        let row = rows.get(&t.id).cloned().ok_or_else(|| OrchError::Refused(format!("no ticket '{}'", t.id)))?;
        if row.status != status::IN_PROGRESS || row.session_id.as_deref() != Some(req.session_id.as_str()) {
            return Err(OrchError::Refused(format!(
                "session {} does not run ticket {} (it is {}{})",
                req.session_id,
                row.id,
                row.status,
                row.session_id.as_deref().map(|s| format!(", worker {s}")).unwrap_or_default()
            )));
        }
        if req.summary.trim().is_empty() {
            return Err(OrchError::Refused("a done needs a summary: it is the done comment (commit, what changed, what was tested)".into()));
        }
        let seat = self.flight().running.get(&req.session_id).cloned().unwrap_or_else(|| seat_of_row(self.env.as_ref(), &row));
        let author = CommentAuthor {
            kind: CommentAuthor::SESSION.into(),
            session_id: Some(req.session_id.clone()),
            agent_id: seat.agent_id.clone().or(row.agent_id.clone()),
        };
        // 1. the done comment, 2. the move (with the run's end), 3. the worker ends, 4. the next ticket.
        let comment = self.comment(&row.id, &req.summary, "done", author)?;
        let (tid, sid) = (row.id.clone(), req.session_id.clone());
        let answer = self.board.apply(
            &row.id,
            None,
            |s, conn| {
                s.require(&tid)?;
                s.place(&tid, status::DONE, None);
                s.named.insert(tid.clone());
                store::finish_run(conn, &tid, &sid, s.now, SUCCEEDED, "done").map_err(|e| e.to_string())?;
                Ok(())
            },
            &self.publish(),
        )?;
        let p = row.project_id.as_deref();
        self.hook(hooks::TICKET_MOVED, &row.id, p, Self::moved(&row, status::IN_PROGRESS, status::DONE, None));
        self.hook(hooks::TICKET_DONE, &row.id, p, json!({ "sessionId": req.session_id, "commentId": comment.id }));
        if let Some(starter) = &row.started_by {
            let first = req.summary.lines().next().unwrap_or("");
            self.env.notify_session(starter, "[AUTOMATIC] ticket done", &format!("{} \"{}\" is done: {}", row.id, take(&row.title, 60), take(first, 200)));
        }
        let seat = self.leave(&req.session_id, &row);
        if req.stop_worker {
            self.sessions.stop(&req.session_id, "ticket done");
        }
        let next = self.drain(&seat).map(|a| a.ticket);
        Ok(DoneAnswer { ticket: answer.ticket, next })
    }

    /// A worker's run ended. A clean end after a done comment closes the ticket (the done protocol); any other end
    /// leaves it in_progress with a failure notice (an issue comment that wakes its notify list, a line to its
    /// starter, the `ticket.run_failed` hook). Either way the lane's next ticket starts.
    pub fn on_done(&self, report: &DoneReport) -> Result<(), OrchError> {
        let (rows, _, runs) = self.board.rows()?;
        let Some(row) = rows.get(&report.ticket_id).cloned() else { return Ok(()) };
        let detail = report.detail.clone().unwrap_or_default();
        let first = self.board.finish_run(&row.id, &report.session_id, &report.outcome, &detail)?;
        let p = row.project_id.as_deref();
        self.hook(hooks::RUN_ENDED, &row.id, p, json!({ "sessionId": report.session_id, "outcome": report.outcome, "detail": detail }));
        if !first {
            return Ok(()); // already ended (a done, an unassign): the lane was drained then
        }
        let seat = self.leave(&report.session_id, &row);
        let current = row.status == status::IN_PROGRESS && row.session_id.as_deref() == Some(report.session_id.as_str());
        if current {
            let since = runs.iter().find(|r| r.ticket_id == row.id && r.session_id == report.session_id).map(|r| r.started_at).unwrap_or(0);
            let done_comment = self
                .board
                .comment_store()
                .and_then(|c| c.read(&row.id, "latestOpen", Some("done"), None))
                .unwrap_or_default()
                .into_iter()
                .find(|c| c.created_at >= since && c.author.session_id.as_deref() == Some(report.session_id.as_str()));
            match (report.outcome == SUCCEEDED, done_comment) {
                (true, Some(c)) => {
                    let tid = row.id.clone();
                    self.board.apply(
                        &row.id,
                        None,
                        |s, _| {
                            s.require(&tid)?;
                            s.place(&tid, status::DONE, None);
                            s.named.insert(tid.clone());
                            Ok(())
                        },
                        &self.publish(),
                    )?;
                    self.hook(hooks::TICKET_MOVED, &row.id, p, Self::moved(&row, status::IN_PROGRESS, status::DONE, None));
                    self.hook(hooks::TICKET_DONE, &row.id, p, json!({ "sessionId": report.session_id, "commentId": c.id }));
                }
                (true, None) => self.failure(&row, report, "the worker ended cleanly without a done comment, so the ticket stays in_progress"),
                (false, _) => self.failure(&row, report, &format!("the worker's run ended {}{}", report.outcome, if detail.is_empty() { String::new() } else { format!(": {detail}") })),
            }
        }
        if let Ok(t) = self.board.get(&row.id) {
            self.bus.publish(ticket_envelope(&t));
        }
        self.drain(&seat);
        Ok(())
    }

    fn failure(&self, row: &TicketRow, report: &DoneReport, what: &str) {
        let body = format!(
            "[AUTOMATIC FAILURE] {what}. Worker {}. Restart it with ticket_start, unassign it, or move it.",
            report.session_id
        );
        let _ = self.comment(&row.id, &body, "issue", CommentAuthor::daemon());
        if let Some(starter) = row.started_by.clone().or_else(|| self.starter_of(&row.id)) {
            self.env.notify_session(&starter, "[AUTOMATIC FAILURE] ticket run", &format!("{} \"{}\": {what}", row.id, take(&row.title, 60)));
        }
        self.hook(
            hooks::RUN_FAILED,
            &row.id,
            row.project_id.as_deref(),
            json!({ "sessionId": report.session_id, "outcome": report.outcome, "detail": what }),
        );
    }
}
