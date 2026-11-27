// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The Agents surface's edit and live frames (RUST PARITY of Kotlin `agent_save`, `agent_delete`, `agent_swap`,
//! `agent_roster`): the profile store in `infantry.db` (written here), the swap of one agent onto another executor and
//! the project-scoped live roster.
//!
//! - `agent_save` `AgentProfile` -> `agent` (the profile as stored). A NEW agent is bound to the project it names, else
//!   to the default project; a known agent keeps its project and may not move.
//! - `agent_delete` `<id>` -> `agents` (the list after).
//! - `agent_swap` `AgentSwap` -> an `agent_swapped` push to every client; nothing written on a refusal.
//! - `agent_roster` `{projectId}` -> `roster`: one row per agent with a session a message can still reach (the mail
//!   router's rule), never another project's.
//!
//! Not checked here (the Kotlin daemon asks the harness): that a model name is one the provider lists.
use crate::board::Board;
use crate::sessions::{Daemon, NewOpts};
use infantry_board::Dispatcher;
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_mail::mail::mailbox::Mailbox;
use infantry_projects::store::ProjectStore;
use infantry_protocol::agents::{AgentProfile, AgentSwap, AgentSwapped, RosterRequest, SessionSwap, SwapExecutor, SwapSession};
use infantry_protocol::frames as pf;
use infantry_protocol::session::SessionRecord;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

pub const EFFORT_LEVELS: [&str; 5] = ["low", "medium", "high", "xhigh", "max"];
pub const MAX_NAME_LENGTH: usize = 100;
pub const MAX_INSTRUCTIONS_LENGTH: usize = 20_000;
pub const MAX_TRAIT_IDS: usize = 8;
const FRAMES: &[&str] = &[pf::AGENT_SAVE, pf::AGENT_DELETE, pf::AGENT_SWAP, pf::AGENT_ROSTER];

pub struct AgentFrames {
    pub daemon: Arc<Daemon>,
    pub profiles: Board,
    pub mailbox: Arc<Mailbox>,
    pub work: Arc<Dispatcher>,
    pub projects: Arc<ProjectStore>,
    pub project_dir: Arc<dyn Fn(&str) -> Option<PathBuf> + Send + Sync>,
    /// Saves and swaps run one at a time (the read, check and write of a profile are one step).
    lock: Mutex<()>,
}

fn is_id(id: &str) -> bool {
    let mut c = id.chars();
    c.next().is_some_and(|f| f.is_ascii_alphanumeric()) && id.len() <= 64 && c.all(|x| x.is_ascii_alphanumeric() || matches!(x, '.' | '_' | '-'))
}

fn is_role(r: &str) -> bool {
    let mut c = r.chars();
    c.next().is_some_and(|f| f.is_ascii_lowercase()) && r.len() <= 32 && c.all(|x| x.is_ascii_lowercase() || x.is_ascii_digit() || x == '-')
}

fn blank(v: &Option<String>) -> Option<String> {
    v.as_deref().map(str::trim).filter(|x| !x.is_empty()).map(String::from)
}

fn cut(s: &str, n: usize) -> String {
    s.chars().take(n).collect()
}

fn to_json<T: serde::Serialize>(v: &T) -> String {
    serde_json::to_string(v).unwrap_or_default()
}

impl AgentFrames {
    pub fn new(
        daemon: Arc<Daemon>,
        profiles: Board,
        mailbox: Arc<Mailbox>,
        work: Arc<Dispatcher>,
        projects: Arc<ProjectStore>,
        project_dir: Arc<dyn Fn(&str) -> Option<PathBuf> + Send + Sync>,
    ) -> AgentFrames {
        AgentFrames { daemon, profiles, mailbox, work, projects, project_dir, lock: Mutex::new(()) }
    }

    /// The ONE executor check of save and swap: provider (a harness this daemon runs), model (no whitespace or control
    /// characters), effort. Returns the normalised (provider, model, effort).
    fn executor(&self, provider: &Option<String>, model: &Option<String>, effort: &Option<String>) -> Result<(Option<String>, Option<String>, Option<String>), String> {
        let p = blank(provider).map(|p| p.to_lowercase());
        let m = blank(model);
        if let Some(m) = &m {
            if m.chars().any(|c| c.is_whitespace() || c.is_control()) {
                return Err("model contains whitespace or a control character".into());
            }
            if m.chars().count() > 200 {
                return Err("model is longer than 200 characters".into());
            }
        }
        if let Some(p) = &p {
            if self.daemon.harness_for(Some(p)).is_none() {
                return Err(format!("no harness named `{}` on this daemon (it runs {})", cut(p, 40), self.daemon.providers().join(", ")));
            }
        }
        let e = blank(effort);
        if let Some(e) = &e {
            if !EFFORT_LEVELS.contains(&e.as_str()) {
                return Err(format!("unknown effort '{}': one of [{}]", cut(e, 40), EFFORT_LEVELS.join(", ")));
            }
        }
        Ok((p, m, e))
    }

    /// Validate `req` and create or overwrite the profile; the stored profile comes back normalised.
    pub fn save(&self, req: AgentProfile) -> Result<AgentProfile, String> {
        let _g = self.lock.lock().unwrap();
        let given = blank(&req.id);
        if let Some(id) = &given {
            if !is_id(id) {
                return Err(format!("id '{}' is not a profile id: a letter or digit, then up to 63 letters, digits, '.', '_' or '-'", cut(id, 80)));
            }
        }
        let id = given.unwrap_or_else(|| format!("agent-{}", uuid::Uuid::new_v4()));
        let existing = self.profiles.agent(&id)?;
        let requested = blank(&req.project_id);
        if let Some(r) = &requested {
            if r.chars().any(|c| c.is_control()) || r.chars().count() > 200 {
                return Err("projectId is invalid".into());
            }
        }
        let owner = existing.as_ref().and_then(|e| e.project_id.clone());
        if let (Some(o), Some(r)) = (&owner, &requested) {
            if o != r {
                return Err(format!("agent '{id}' belongs to project '{o}' and cannot move to '{r}'"));
            }
        }
        // A NEW agent with no project is bound to the default project, never left global.
        let project_id = requested.or(owner).or_else(|| if existing.is_none() { self.projects.default_project_id() } else { None });
        let name = req.name.trim().to_string();
        if name.is_empty() {
            return Err("name is blank".into());
        }
        if name.chars().count() > MAX_NAME_LENGTH {
            return Err(format!("name is longer than {MAX_NAME_LENGTH} characters"));
        }
        if name.chars().any(|c| c.is_control()) {
            return Err("name contains a control character".into());
        }
        let role = req.role.trim().to_lowercase();
        if !is_role(&role) {
            return Err(format!("role '{}' is not a role id: a lowercase letter, then up to 31 lowercase letters, digits or '-' (e.g. backend, qa)", cut(&role, 40)));
        }
        let (provider, model, effort) = self.executor(&req.provider, &req.model, &req.effort)?;
        let instructions = blank(&req.instructions);
        if instructions.as_ref().is_some_and(|i| i.chars().count() > MAX_INSTRUCTIONS_LENGTH) {
            return Err(format!("instructions are longer than {MAX_INSTRUCTIONS_LENGTH} characters"));
        }
        // Absent keeps the stored list (an older client must not strip it); [] clears it.
        let trait_ids = match &req.trait_ids {
            Some(t) => {
                let t: Vec<String> = t.iter().map(|x| x.trim().to_string()).collect();
                if t.len() > MAX_TRAIT_IDS {
                    return Err(format!("an agent adds at most {MAX_TRAIT_IDS} traits; got {}", t.len()));
                }
                if let Some(d) = t.iter().find(|x| t.iter().filter(|y| y == x).count() > 1) {
                    return Err(format!("trait '{}' is listed twice", cut(d, 40)));
                }
                Some(t)
            }
            None => existing.as_ref().and_then(|e| e.trait_ids.clone()),
        };
        let skill_ids = match &req.skill_ids {
            Some(s) => {
                let s: Vec<String> = s.iter().map(|x| x.trim().to_string()).collect();
                if let Some(u) = s.first() {
                    return Err(format!("skillIds: unknown skill '{}'", cut(u, 80)));
                }
                Some(s)
            }
            None => existing.as_ref().and_then(|e| e.skill_ids.clone()),
        };
        let runner = match req.runner.as_deref().map(str::trim) {
            Some(r) if !r.is_empty() => {
                let ok = r.len() <= 64 && r.chars().next().is_some_and(|c| c.is_ascii_alphanumeric()) && r.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '-'));
                if !ok {
                    return Err(format!("runner '{}' must be local, any or a runner name ([A-Za-z0-9._-], up to 64 characters)", cut(r, 40)));
                }
                Some(r.to_string())
            }
            Some(_) => None,
            None => existing.as_ref().and_then(|e| e.runner.clone()),
        };
        let stored = AgentProfile { id: Some(id.clone()), name, role, provider, model, instructions, project_id, effort, trait_ids, skill_ids, runner };
        self.profiles.save_agent(&stored, super::now_ms())?;
        self.profiles.agent(&id)?.ok_or_else(|| format!("profile {id} was saved but cannot be read back"))
    }

    pub fn delete(&self, id: &str) -> Result<(), String> {
        let _g = self.lock.lock().unwrap();
        let id = id.trim();
        if id.is_empty() {
            return Err("agent_delete expects a profile id".into());
        }
        if self.profiles.delete_agent(id)? {
            Ok(())
        } else {
            Err(format!("no agent profile '{}'", cut(id, 80)))
        }
    }

    /// The agent's current session: the one in flight, else the latest it started.
    fn current_session(&self, agent_id: &str) -> Option<SessionRecord> {
        let mut mine: Vec<SessionRecord> = self.daemon.records().into_iter().filter(|r| r.agent_id.as_deref() == Some(agent_id)).collect();
        mine.sort_by_key(|r| std::cmp::Reverse(r.spawned_at));
        mine.iter().find(|r| r.live()).cloned().or_else(|| mine.into_iter().next())
    }

    /// Point agent `req.agent_id` at another executor and decide what happens to its current session (the Kotlin table):
    /// no session -> `none`; a session at rest moves with the agent (`kept`, its conversation carried across harnesses);
    /// a running one is never interrupted (`kept` on the same harness, `none` across); `keep` across harnesses is refused;
    /// `new` always cuts a fresh session at rest. Every check runs before the profile is written.
    pub fn swap(&self, req: AgentSwap) -> Result<AgentSwapped, String> {
        let _g = self.lock.lock().unwrap();
        let mode = req.session.trim().to_lowercase();
        if !AgentSwap::SESSION_MODES.contains(&mode.as_str()) {
            return Err(format!("session '{}' is not one of {:?}", cut(&mode, 20), AgentSwap::SESSION_MODES));
        }
        let id = req.agent_id.trim().to_string();
        if id.is_empty() {
            return Err("agentId is required".into());
        }
        let before = self.profiles.agent(&id)?.ok_or_else(|| format!("no agent profile '{}' (agent_list shows the profiles)", cut(&id, 80)))?;
        if blank(&Some(req.provider.clone())).is_none() {
            return Err("provider is required: the harness the agent runs on".into());
        }
        let (provider, model, effort) = self.executor(&Some(req.provider.clone()), &req.model, &req.effort)?;
        let default_harness = self.daemon.harness_for(None).map(|h| h.name().to_string()).unwrap_or_default();
        let session = self.current_session(&id);
        let old_provider = before.provider.clone().unwrap_or_else(|| default_harness.clone());
        let old_harness = session.as_ref().map(|s| s.provider.clone()).unwrap_or(old_provider.clone());
        let new_harness = provider.clone().unwrap_or(default_harness);
        let crosses = old_harness != new_harness;
        if let Some(s) = &session {
            if s.held_by.is_some() {
                return Err("this agent's session is being run by another app; wait for that to finish".into());
            }
        }
        if mode == AgentSwap::KEEP && crosses {
            return Err(format!("session 'keep' cannot cross harnesses ('{old_harness}' -> '{new_harness}'): a conversation belongs to one harness; use 'auto' or 'new'"));
        }
        let from = SwapExecutor { provider: Some(old_provider), model: before.model.clone(), effort: before.effort.clone() };
        let running = session.as_ref().is_some_and(|s| s.live());
        let mut outcome = if mode == AgentSwap::NEW {
            SwapSession::NEW
        } else if session.is_none() {
            SwapSession::NONE
        } else if crosses && running {
            SwapSession::NONE
        } else {
            SwapSession::KEPT
        };

        // All checks passed: write the profile (provider, model, effort only; the rest stays as stored).
        let after = AgentProfile { provider, model, effort, ..before };
        self.profiles.save_agent(&after, super::now_ms())?;
        let stored = self.profiles.agent(&id)?.unwrap_or(after);

        let mut session_id = None;
        match outcome {
            SwapSession::KEPT => {
                let s = session.expect("a kept session exists");
                if self.daemon.turn_active(&s.id).unwrap_or(false) {
                    // The run in flight finishes on the old executor; the next run takes the new one.
                    session_id = Some(s.id);
                } else {
                    let moved = self.daemon.swap_agent_session(SessionSwap {
                        session_id: s.id.clone(),
                        provider: new_harness.clone(),
                        model: stored.model.clone(),
                        effort: stored.effort.clone(),
                        role: None,
                        display_name: None,
                    });
                    match moved {
                        Ok(m) => session_id = Some(m.session_id),
                        Err(_) => outcome = SwapSession::NONE,
                    }
                }
            }
            SwapSession::NEW => {
                let cwd = stored.project_id.as_deref().and_then(|p| (self.project_dir)(p)).map(|d| d.display().to_string());
                let opts = NewOpts {
                    cwd,
                    model: stored.model.clone(),
                    effort: stored.effort.clone(),
                    role: Some(stored.role.clone()),
                    provider: stored.provider.clone(),
                    project_id: stored.project_id.clone(),
                    agent_id: stored.id.clone(),
                    ..Default::default()
                };
                session_id = Some(self.daemon.launch(opts)?);
            }
            _ => {}
        }
        Ok(AgentSwapped { agent: stored, from, session: SwapSession { outcome: outcome.into(), session_id } })
    }

    /// The live roster of `project_id` (Kotlin `projectAgents` + `roster`).
    pub fn roster(&self, project_id: &str) -> Result<Value, String> {
        let sessions = self.daemon.records();
        let profiles: HashMap<String, AgentProfile> = self.profiles.agents(None)?.into_iter().filter_map(|a| a.id.clone().map(|i| (i, a))).collect();
        let titles: HashMap<String, String> = self
            .work
            .board()
            .list(None, None)
            .map_err(|e| e.to_string())?
            .into_iter()
            .filter_map(|t| t.session_id.clone().map(|s| (s, t.title)))
            .collect();
        let in_session = |s: &SessionRecord| s.live() || s.resumable() || s.never_run();
        let mut mine: Vec<&SessionRecord> = sessions.iter().filter(|s| s.project_id.as_deref() == Some(project_id) && in_session(s)).collect();
        mine.sort_by_key(|s| std::cmp::Reverse(s.spawned_at));
        // One agent per key: a profile-backed session by its agent id, a role-only one by its session id.
        let mut agents: Vec<(String, String, String)> = vec![];
        for s in mine {
            let agent_id = s.agent_id.clone().filter(|a| !a.trim().is_empty());
            let profile = agent_id.as_ref().and_then(|a| profiles.get(a));
            if let Some(p) = profile {
                if p.project_id.as_deref().is_some_and(|x| x != project_id) {
                    continue;
                }
            }
            let role = s.role.clone().filter(|r| !r.trim().is_empty()).or_else(|| profile.map(|p| p.role.clone())).unwrap_or_default();
            let key = agent_id.unwrap_or_else(|| s.id.clone());
            if agents.iter().any(|a| a.0 == key) {
                continue;
            }
            let name = profile.map(|p| p.name.clone()).unwrap_or_else(|| if role.is_empty() { s.id.clone() } else { role.clone() });
            agents.push((key, name, role));
        }
        let rows: Vec<Value> = agents
            .into_iter()
            .map(|(agent_id, name, role)| {
                let mut theirs: Vec<&SessionRecord> = sessions.iter().filter(|s| s.agent_id.clone().filter(|a| !a.trim().is_empty()).unwrap_or_else(|| s.id.clone()) == agent_id).collect();
                theirs.sort_by_key(|s| std::cmp::Reverse(s.spawned_at));
                let running = |s: &SessionRecord| s.is_busy() || s.background_work.is_some() || !s.bg_jobs.is_empty();
                let session = theirs.iter().find(|s| running(s)).or(theirs.first()).copied();
                let status = match session {
                    None => "OFFLINE",
                    Some(s) if running(s) => "BUSY",
                    Some(s) if !s.is_busy() && (s.resumable() || s.never_run()) => "IDLE",
                    Some(_) => "OFFLINE",
                };
                let pending = self.mailbox.pending_count(&agent_id) + session.map(|s| self.mailbox.pending_count(&s.id)).unwrap_or(0);
                json!({
                    "agentId": agent_id, "name": name, "role": role, "status": status,
                    "sessionId": session.map(|s| s.id.clone()), "title": session.and_then(|s| titles.get(&s.id)), "pending": pending,
                })
            })
            .collect();
        Ok(Value::Array(rows))
    }
}

impl FrameUnit for AgentFrames {
    fn name(&self) -> &'static str {
        "agent-frames"
    }
    fn frames(&self) -> &'static [&'static str] {
        FRAMES
    }
    fn handle(&self, env: &Envelope, ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let answer = |ty: &str, data: String| {
            let mut e = Envelope::new(ty, data);
            e.session_id = env.session_id.clone();
            e.request_id = env.request_id.clone();
            vec![e]
        };
        match env.r#type.as_str() {
            pf::AGENT_SAVE => {
                let t = pf::AGENT_SAVE;
                let req: AgentProfile = serde_json::from_str(&env.data).map_err(|e| Refusal::new(t, format!("invalid profile ({e})")))?;
                let saved = self.save(req).map_err(|m| Refusal::new(t, m))?;
                Ok(answer(pf::AGENT, to_json(&saved)))
            }
            pf::AGENT_DELETE => {
                self.delete(&env.data).map_err(|m| Refusal::new(pf::AGENT_DELETE, m))?;
                let all = self.profiles.agents(None).map_err(|m| Refusal::new(pf::AGENT_DELETE, m))?;
                Ok(answer(pf::AGENTS, to_json(&all)))
            }
            pf::AGENT_SWAP => {
                let t = pf::AGENT_SWAP;
                let req: AgentSwap = serde_json::from_str(&env.data).map_err(|e| Refusal::new(t, format!("invalid swap ({e})")))?;
                let swapped = self.swap(req).map_err(|m| Refusal::new(t, m))?;
                // Every client sees it (the requester included), like `role_binding_set`.
                ctx.bus.publish(Envelope::new(pf::AGENT_SWAPPED, to_json(&swapped)));
                Ok(Vec::new())
            }
            pf::AGENT_ROSTER => {
                let t = pf::AGENT_ROSTER;
                let req: RosterRequest = serde_json::from_str(if env.data.trim().is_empty() { "{}" } else { &env.data }).map_err(|e| Refusal::new(t, format!("invalid request ({e})")))?;
                let project = req.project_id.as_deref().map(str::trim).unwrap_or("");
                if project.is_empty() {
                    return Err(Refusal::new(t, "projectId is blank"));
                }
                let rows = self.roster(project).map_err(|m| Refusal::new(t, m))?;
                Ok(answer(pf::ROSTER, rows.to_string()))
            }
            other => Err(Refusal::new(other, "not an agent frame")),
        }
    }
}
