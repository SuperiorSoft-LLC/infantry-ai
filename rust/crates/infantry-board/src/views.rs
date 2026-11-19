// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Every ticket as the wire shows it: the stored row plus the views the daemon derives (links from
//! both sides, what holds it, its last run, the effective executor / context / limit / effort).
//!
//! Not derived yet (always null here): `hasWork` (git), project routing rules (ROUTING-1).
use std::collections::HashMap;

use infantry_orchestrator_api::dto::{
    link_type, ContextSwitch, EffectiveContext, EffectiveContextLimit, EffectiveEffort, EffectiveExecutor, ExecutorField,
    ExecutorOverrides, Ticket, TicketLink, TicketMcp, TicketRun,
};

use crate::env::{BoardAgent, BoardEnv};
use crate::rules::{hold_reason, holders};
use crate::store::{LinkRow, RunRow, TicketRow};

/// `ContextLimits.MIN`: the lowest usable context limit.
pub const CONTEXT_LIMIT_MIN: i32 = 10_000;
pub const CONTEXT_LIMIT_MAX: i32 = 10_000_000;

/// Views of every row in `rows`.
pub fn views(env: &dyn BoardEnv, rows: &HashMap<String, TicketRow>, links: &[LinkRow], runs: &[RunRow]) -> HashMap<String, Ticket> {
    let agents = env.agents();
    let mut own: HashMap<&str, Vec<TicketLink>> = HashMap::new();
    let mut prerequisites: HashMap<&str, Vec<String>> = HashMap::new();
    for l in links {
        own.entry(&l.ticket_id).or_default().push(TicketLink { r#type: l.r#type.clone(), ticket_id: l.related_id.clone() });
        own.entry(&l.related_id)
            .or_default()
            .push(TicketLink { r#type: link_type::reverse(&l.r#type).to_string(), ticket_id: l.ticket_id.clone() });
        if l.r#type == link_type::BLOCKED_BY {
            prerequisites.entry(&l.ticket_id).or_default().push(l.related_id.clone());
        }
    }
    let mut last_run: HashMap<&str, &RunRow> = HashMap::new();
    for r in runs.iter().filter(|r| r.ended_at.is_some()) {
        last_run.insert(&r.ticket_id, r); // runs come oldest first: the last ended one wins
    }
    let v = Viewer { env, agents: &agents };
    rows.iter()
        .map(|(id, row)| {
            let holding = holders(prerequisites.get(id.as_str()).map(Vec::as_slice).unwrap_or(&[]), rows);
            let t = Ticket {
                id: row.id.clone(),
                title: row.title.clone(),
                description: row.description.clone(),
                ticket_type: row.ticket_type.clone(),
                project_id: row.project_id.clone(),
                epic_id: row.epic_id.clone(),
                status: row.status.clone(),
                position: row.position,
                wont_fix: row.wont_fix,
                links: own.get(id.as_str()).cloned().unwrap_or_default(),
                held_by: holding.iter().map(|r| r.id.clone()).collect(),
                hold_reason: hold_reason(&holding),
                created_at: row.created_at,
                updated_at: row.updated_at,
                agent_id: row.agent_id.clone(),
                provider: row.provider.clone(),
                model: row.model.clone(),
                use_worktree: row.use_worktree,
                worktree_branch: row.worktree_branch.clone(),
                include_linked_tasks_context: row.include_linked_tasks_context,
                queued_for: row.queued_for.clone(),
                queued_at: row.queued_at,
                queued_agent: row.queued_agent.clone(),
                queue_rank: row.queue_rank,
                runner: row.runner.clone(),
                queued_agent_name: row.queued_agent.as_deref().and_then(|a| env.agent(a)).map(|a| a.name),
                session_id: row.session_id.clone(),
                has_work: None,
                executor_overrides: Some(ExecutorOverrides { provider: row.provider.clone(), model: row.model.clone() }),
                effective_executor: v.effective_executor(row),
                effective_context: env.context_settings(row.project_id.as_deref()).map(|c| EffectiveContext {
                    include_linked_tasks_context: switch(row.include_linked_tasks_context, c.include_linked_tasks_context),
                    include_skills_context: Some(switch(row.include_skills_context, c.include_skills_context)),
                }),
                last_run: last_run.get(id.as_str()).map(|r| TicketRun {
                    session_id: r.session_id.clone(),
                    started_at: r.started_at,
                    ended_at: r.ended_at,
                    outcome: r.outcome.clone(),
                    detail: r.detail.clone(),
                }),
                start: None,
                branch: row.branch.clone(),
                base: row.base.clone(),
                skill_ids: row.skill_ids.clone(),
                skills_mode: row.skills_mode.clone(),
                include_skills_context: row.include_skills_context,
                images: row.images.clone(),
                weight: row.weight,
                context_limit: row.context_limit,
                effective_context_limit: Some(v.effective_context_limit(row)),
                effort: row.effort.clone(),
                effective_effort: Some(v.effective_effort(row)),
                mcp: TicketMcp::stored(row.mcp.as_deref()),
            };
            (id.clone(), t)
        })
        .collect()
}

fn switch(ticket: Option<bool>, project: Option<bool>) -> ContextSwitch {
    match (ticket, project) {
        (Some(v), _) => ContextSwitch { value: v, source: "ticket".into() },
        (None, Some(v)) => ContextSwitch { value: v, source: "project".into() },
        _ => ContextSwitch { value: true, source: "default".into() },
    }
}

/// The context limit a session of `row` runs with and its source (CONTEXT-DEFAULT-1).
pub fn resolve_context_limit(env: &dyn BoardEnv, row: &TicketRow) -> Option<(i32, &'static str)> {
    let (value, source) = match row.context_limit {
        Some(v) => (v, "ticket"),
        None => match env.weight_context(row.weight).and_then(|w| w.context_limit) {
            Some(v) => (v, "weight"),
            None => (env.default_context_limit()?, "default"),
        },
    };
    match env.compact_max_tokens().map(|c| c.max(CONTEXT_LIMIT_MIN)) {
        Some(ceiling) if value > ceiling => Some((ceiling, "compactMax")),
        _ => Some((value, source)),
    }
}

/// ROUTING-1: the first rule of `row`'s project that matches it; None when the ticket pins its own provider or model.
pub fn routing_choice(env: &dyn BoardEnv, row: &TicketRow, role: Option<&str>) -> Option<infantry_orchestrator_api::RoutingRule> {
    if row.provider.is_some() || row.model.is_some() {
        return None;
    }
    let rules = env.routing_rules(row.project_id.as_deref());
    infantry_orchestrator_api::RoutingRule::first(&rules, row.weight, row.ticket_type.as_deref(), role).cloned()
}

/// The executor a start of `row` resolves to (the same resolver the view shows).
pub fn executor_of(env: &dyn BoardEnv, row: &TicketRow) -> Option<EffectiveExecutor> {
    let agents = env.agents();
    Viewer { env, agents: &agents }.effective_executor(row)
}

/// The effort a start of `row` runs with (ticket, profile, role binding), before rule and weight defaults.
pub fn effort_of(env: &dyn BoardEnv, row: &TicketRow) -> Option<String> {
    let agents = env.agents();
    Viewer { env, agents: &agents }.effective_effort(row).value
}

struct Viewer<'a> {
    env: &'a dyn BoardEnv,
    agents: &'a [BoardAgent],
}

impl Viewer<'_> {
    fn ticket_profile(&self, agent_id: &str) -> Option<BoardAgent> {
        match agent_id.strip_prefix("role:") {
            Some(role) => self.agents.iter().find(|a| a.role.as_deref() == Some(role)).cloned(),
            None => self.env.agent(agent_id),
        }
    }

    /// The harness a start would use, when sessions are on.
    fn harness(&self, row: &TicketRow) -> Option<String> {
        if !self.env.sessions_on() {
            return None;
        }
        self.effective_executor(row).and_then(|e| e.provider).and_then(|p| p.value).or_else(|| self.env.default_provider())
    }

    fn effective_context_limit(&self, row: &TicketRow) -> EffectiveContextLimit {
        let resolved = resolve_context_limit(self.env, row);
        let cap = self.harness(row).and_then(|h| self.env.provider(&h));
        EffectiveContextLimit {
            value: resolved.map(|r| r.0),
            source: resolved.map(|r| r.1).unwrap_or("default").to_string(),
            support: cap.as_ref().and_then(|c| c.context_limit_support.clone()),
            how: cap.as_ref().and_then(|c| c.context_limit_how.clone()),
        }
    }

    fn effective_effort(&self, row: &TicketRow) -> EffectiveEffort {
        let cap = self.harness(row).and_then(|h| self.env.provider(&h));
        let assigned = row.agent_id.as_deref();
        let profile = assigned.and_then(|a| self.ticket_profile(a));
        let role_id = assigned.and_then(|a| a.strip_prefix("role:"));
        let (value, source) = if let Some(e) = &row.effort {
            (Some(e.clone()), "ticket")
        } else if let Some(e) = profile.as_ref().and_then(|p| p.effort.clone()) {
            (Some(e), "agent")
        } else {
            let subject = profile.clone().or_else(|| role_id.map(|r| BoardAgent { name: r.into(), role: Some(r.into()), ..Default::default() }));
            match subject.and_then(|s| self.env.executor_for(&s, None, None)).and_then(|b| b.effort) {
                Some(e) => (Some(e), "role"),
                None => (None, "default"),
            }
        };
        EffectiveEffort {
            value,
            source: source.into(),
            support: cap.as_ref().and_then(|c| c.effort_support.clone()),
            how: cap.as_ref().and_then(|c| c.effort_how.clone()),
        }
    }

    fn effective_executor(&self, row: &TicketRow) -> Option<EffectiveExecutor> {
        let assigned = row.agent_id.as_deref()?;
        let role_id = assigned.strip_prefix("role:");
        let is_role = role_id.is_some();
        let profile = self.ticket_profile(assigned);
        let role = profile.as_ref().and_then(|p| p.role.clone()).or_else(|| role_id.map(str::to_string));
        let profile_provider = profile.as_ref().and_then(|p| p.provider.clone());
        let subject = profile.clone().or_else(|| role_id.map(|r| BoardAgent { name: r.into(), role: Some(r.into()), ..Default::default() }));
        let bound = subject.and_then(|s| self.env.executor_for(&s, row.provider.as_deref(), row.model.as_deref()));
        let bound_provider = bound.as_ref().and_then(|b| b.provider.clone());
        let bound_model = bound.as_ref().and_then(|b| b.model.clone());
        let profile_model = profile.as_ref().and_then(|p| p.model.clone());
        // TICKET-WEIGHT: the mapping applies only to a weighted ticket nothing else pins, on an installed harness.
        let applies = row.weight.is_some()
            && row.provider.is_none()
            && row.model.is_none()
            && profile_provider.clone().or(bound_provider.clone()).is_none()
            && profile_model.clone().or(bound_model.clone()).is_none();
        let by_weight = if applies {
            self.env.weight_executor(row.weight).filter(|e| match &e.provider {
                Some(p) => !self.env.sessions_on() || self.env.has_provider(p),
                None => true,
            })
        } else {
            None
        };
        let field = |v: Option<String>, s: &str| ExecutorField { value: v, source: s.into() };
        // ROUTING-1: the project's first matching rule beats the profile, the role binding and the weight mapping.
        if let Some(rule) = routing_choice(self.env, row, role.as_deref()) {
            return Some(EffectiveExecutor {
                agent_id: profile.and_then(|p| p.id).or_else(|| (!is_role).then(|| assigned.to_string())),
                role,
                provider: Some(field(Some(rule.provider.clone()), "rule")),
                model: Some(field(Some(rule.model.clone()), "rule")),
                rule: Some(rule.name),
            });
        }
        let provider = if let Some(p) = &row.provider {
            field(Some(p.clone()), "ticket")
        } else if let Some(p) = by_weight.as_ref().and_then(|w| w.provider.clone()) {
            field(Some(p), "weight")
        } else if let Some(p) = &profile_provider {
            field(Some(p.clone()), "profile")
        } else if let Some(p) = &bound_provider {
            field(Some(p.clone()), "role")
        } else if is_role {
            field(None, "role")
        } else if self.env.sessions_on() {
            field(self.env.default_provider(), "default")
        } else {
            field(None, "default")
        };
        let provider_or_default =
            profile_provider.clone().or_else(|| if self.env.sessions_on() { self.env.default_provider() } else { None });
        let profile_model_applies =
            profile_model.is_some() && (row.provider.is_none() || row.provider == provider_or_default);
        let model = if let Some(m) = &row.model {
            field(Some(m.clone()), "ticket")
        } else if let Some(m) = by_weight.as_ref().and_then(|w| w.model.clone()) {
            field(Some(m), "weight")
        } else if profile_model_applies {
            field(profile_model.clone(), "profile")
        } else if let Some(m) = &bound_model {
            field(Some(m.clone()), "role")
        } else if is_role && row.provider.is_none() && profile_model.is_none() && profile_provider.is_none() {
            field(None, "role")
        } else {
            let pid = row.provider.clone().or(provider_or_default);
            field(pid.and_then(|p| self.env.provider(&p)).and_then(|p| p.default_model), "default")
        };
        Some(EffectiveExecutor {
            agent_id: profile.and_then(|p| p.id).or_else(|| (!is_role).then(|| assigned.to_string())),
            role,
            provider: Some(provider),
            model: Some(model),
            rule: None,
        })
    }
}
