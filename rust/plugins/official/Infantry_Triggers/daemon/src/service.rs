// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The trigger service (Kotlin `TriggerService` + `TriggerNudges`): stored triggers, each behind its own [`Guard`];
//! every event's disposition recorded; the actions (`start_next`, an unattended ticket hand-out; `prompt`, delivered as
//! MAIL to a session of the target, or a started session when there is none); run outcomes from the session stream;
//! attention on a trip; restart honesty (a run in flight at the last stop comes back TRIPPED); schedules, one-shots
//! (`at`), ship (a ticket moved into done) and webhooks. Nothing is ever retried; only [`Service::arm`] re-arms.
//!
//! The service reaches the board, the sessions, mail and attention ONLY through [`Ports`] (TriggerPortsGuard). It runs
//! single-threaded inside the plugin: a launch is queued while an operation runs and performed when it ends, after its
//! event rows are written (the order Kotlin's launch thread waits for).
use crate::guard::{FireResult, Guard, Out, Outcome, Run};
use crate::model::{disposition as d, state, EventView, TriggerSave, TriggerView, MAX_PROMPT_CHARS};
use crate::store::{Sql, Store};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};

pub const MIN_INTERVAL_MS: i64 = 60_000;
pub const DEFAULT_AT_MAX_LATE_MS: i64 = 900_000;
pub const MAX_REF_CHARS: usize = 200;
pub const MAX_NAME_CHARS: usize = 100;
pub const MIN_WEBHOOK_SECRET: usize = 32;
pub const MAX_WEBHOOK_SECRET: usize = 256;
pub const MAX_DIGEST: usize = 200;
/// Remembered exits of sessions not yet mapped to a run, before the map is cleared.
pub const EXITS_KEPT: usize = 1_000;
pub const DUPLICATE_EVENT_PREFIX: &str = "dup-";
pub const SERVED_KINDS: [&str; 5] = ["manual", "schedule", "ship", "webhook", "at"];
pub const AT: &str = "at";
pub const SENDER_PREFIX: &str = "trigger:";
const ROLE_PREFIX: &str = "role:";
const SESSION_PREFIX: &str = "session:";
/// The named events a recorded disposition publishes (`TriggerEvents`).
pub const EV_FIRED: &str = "trigger.fired";
pub const EV_REFUSED: &str = "trigger.refused";
pub const EV_DELIVERED: &str = "trigger.delivered";
const REFUSALS: [&str; 6] = [d::REFUSED_CEILING, d::REFUSED_LAUNCH_GATE, d::SUPPRESSED_TRIPPED, d::SUPPRESSED_DISABLED, d::REFUSED, d::REFUSED_UNKNOWN_ACTION];

/// What `start_next` handed out.
#[derive(Debug, Clone, PartialEq)]
pub struct StartAnswer {
    /// `started`, `deferred`, a refusal reason...
    pub reason: String,
    pub session_id: Option<String>,
    pub detail: Option<String>,
}

/// A session as a prompt nudge sees it.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct SessionInfo {
    pub id: String,
    pub agent_id: Option<String>,
    pub role: Option<String>,
    pub project_id: Option<String>,
    pub spawned_at: i64,
    /// Mail can still reach it (in flight, resumable, or bound and never run).
    pub reachable: bool,
    /// It is up now.
    pub live: bool,
}

/// One nudge mail.
#[derive(Debug, Clone, PartialEq)]
pub struct MailRequest {
    pub from: String,
    pub to: String,
    pub subject: String,
    pub body: String,
    pub thread_id: String,
}

/// The daemon as the service reaches it.
pub trait Ports {
    fn now(&self) -> i64;
    fn new_id(&self) -> String;
    /// `ticket_start_next`: None = nothing was handed out (nothing startable, or refused).
    fn start_next(&self, agent: Option<&str>) -> Result<Option<StartAnswer>, String>;
    /// An unattended session for `agent_id` (a profile id or `role:<role>`) with `prompt` as its first turn: its id.
    fn start_prompt(&self, agent_id: &str, prompt: &str, project_id: Option<&str>) -> Result<String, String>;
    fn sessions(&self) -> Vec<SessionInfo>;
    /// Sends mail: (mail id, thread id), or why it was refused.
    fn send_mail(&self, m: &MailRequest) -> Result<(String, String), String>;
    /// True while mail `mail_id` (in thread `thread_id`) still waits for its recipient.
    fn mail_pending(&self, mail_id: &str, thread_id: Option<&str>) -> bool;
    fn attention(&self, kind: &str, trigger_id: &str, outcome: &str, detail: &str);
    /// Pushes one changed trigger to every client.
    fn push(&self, view: &TriggerView);
    /// Pushes the whole list (after a delete).
    fn push_all(&self, all: &[TriggerView]);
    fn publish(&self, name: &str, payload: Value);
    fn log(&self, line: &str);
    fn at_max_late_ms(&self) -> i64 {
        DEFAULT_AT_MAX_LATE_MS
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum Delivery {
    Mailed(String, String),
    Started(String),
    Busy(String),
    TargetGone(String),
    Refused(String),
}

pub struct Service<S: Sql, P: Ports> {
    pub repo: Store<S>,
    pub ports: P,
    guards: HashMap<String, Guard>,
    run_triggers: HashMap<String, String>,
    run_events: HashMap<String, Run>,
    run_sessions: HashMap<String, String>,
    exits: HashMap<String, Outcome>,
    overrides: HashMap<String, (&'static str, Option<String>)>,
    intervals: HashMap<String, i64>,
    one_shots: HashMap<String, i64>,
    fired_to_delete: HashSet<String>,
    next_due_at: HashMap<String, i64>,
    launches: Vec<Run>,
    started: bool,
}

type R<T> = Result<T, String>;

fn bad(msg: impl Into<String>) -> String {
    msg.into()
}

fn short(s: &str, n: usize) -> String {
    s.chars().take(n).collect()
}

impl<S: Sql, P: Ports> Service<S, P> {
    /// Restores the schedules and one-shots and claims the runs the last daemon left in flight. The pump is off until
    /// [`Service::start`].
    pub fn new(repo: Store<S>, ports: P) -> Self {
        let mut s = Service {
            repo,
            ports,
            guards: HashMap::new(),
            run_triggers: HashMap::new(),
            run_events: HashMap::new(),
            run_sessions: HashMap::new(),
            exits: HashMap::new(),
            overrides: HashMap::new(),
            intervals: HashMap::new(),
            one_shots: HashMap::new(),
            fired_to_delete: HashSet::new(),
            next_due_at: HashMap::new(),
            launches: Vec::new(),
            started: false,
        };
        let now = s.ports.now();
        for (id, interval) in s.repo.intervals() {
            s.intervals.insert(id.clone(), interval);
            let row = s.repo.get(&id);
            let due = row.as_ref().and_then(|r| r.next_fire_at).unwrap_or(now + interval);
            s.next_due_at.insert(id.clone(), due);
            if let Some(r) = row.filter(|r| r.next_fire_at.is_none()) {
                let _ = s.repo.upsert(&TriggerView { next_fire_at: Some(due), ..r }, Some(interval));
            }
        }
        for t in s.repo.all().into_iter().filter(|t| t.state == state::RUNNING) {
            // A prompt-as-mail run only ever spans one hand-off: re-arm it rather than silence a recurring nudge.
            if t.action.kind == "prompt" {
                s.store(TriggerView { state: state::ARMED.into(), ..t });
            } else {
                s.trip_stored(t, "daemon restarted mid-run");
            }
        }
        for t in s.repo.all().into_iter().filter(|t| t.kind == AT) {
            match t.next_fire_at {
                Some(due) => {
                    s.one_shots.insert(t.id.clone(), due);
                }
                None => {
                    if t.delete_after_fire && s.repo.events(&t.id).iter().any(|e| e.source == AT && e.disposition != d::MISSED) {
                        s.fired_to_delete.insert(t.id.clone());
                    }
                }
            }
        }
        s
    }

    /// Lets the pump fire schedules and one-shots (a slot missed while down fires on the first tick).
    pub fn start(&mut self) {
        self.started = true;
    }

    pub fn list(&self) -> Vec<TriggerView> {
        self.repo.all()
    }

    pub fn get(&self, id: &str) -> Option<TriggerView> {
        self.repo.get(id.trim())
    }

    pub fn events(&self, id: &str) -> R<Vec<EventView>> {
        let id = id.trim();
        if self.repo.get(id).is_none() {
            return Err(bad(format!("no trigger '{}'", short(id, 80))));
        }
        Ok(self.repo.events(id))
    }

    pub fn tripped_count(&self) -> usize {
        self.repo.all().iter().filter(|t| t.state == state::TRIPPED).count()
    }

    /// A committed ticket move: only a move INTO done is a ship event.
    pub fn on_ticket_moved(&mut self, to: &str) {
        if to != "done" {
            return;
        }
        for t in self.repo.all().into_iter().filter(|t| t.kind == "ship") {
            if let Err(e) = self.fire_from(&t.id, "ship", None, None) {
                self.ports.log(&format!("ship fire for {} failed: {e}", t.id));
            }
        }
        self.drain();
    }

    pub fn record_duplicate(&mut self, id: &str, delivery: &str, digest: Option<String>) -> R<()> {
        if self.repo.get(id).is_none() {
            return Err(bad(format!("no trigger '{}'", short(id, 80))));
        }
        self.record(&format!("{DUPLICATE_EVENT_PREFIX}{id}-{delivery}"), id, d::DUPLICATE, "webhook", None, Some(delivery.into()), digest);
        Ok(())
    }

    /// Create (no id) or edit a trigger. `owner` is the TRUSTED owner (the calling module), None for a person.
    pub fn save(&mut self, req: TriggerSave, owner: Option<&str>) -> R<TriggerView> {
        let name = req.name.trim().to_string();
        if name.is_empty() {
            return Err(bad("name is blank"));
        }
        if name.chars().count() > MAX_NAME_CHARS {
            return Err(bad(format!("name is longer than {MAX_NAME_CHARS} characters")));
        }
        if !SERVED_KINDS.contains(&req.kind.as_str()) {
            return Err(bad(format!("kind '{}' is not served yet (served: {})", short(&req.kind, 40), SERVED_KINDS.join(", "))));
        }
        let claimed = req.owner.as_deref().map(str::trim).filter(|s| !s.is_empty());
        if claimed.is_some() && claimed != owner {
            return Err(match owner {
                None => bad("owner is stamped from the calling module; a client cannot set it"),
                Some(o) => bad(format!("trigger owner '{}' is not the calling module '{o}'", short(claimed.unwrap_or(""), 80))),
            });
        }
        let reference = req.reference.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_string);
        if reference.as_ref().is_some_and(|r| r.chars().count() > MAX_REF_CHARS) {
            return Err(bad(format!("ref is longer than {MAX_REF_CHARS} characters")));
        }
        let is_at = req.kind == AT;
        if is_at {
            if req.fire_at.unwrap_or(0) <= 0 {
                return Err(bad("kind 'at' needs fireAt (epoch ms)"));
            }
        } else {
            if req.fire_at.is_some() {
                return Err(bad("fireAt is only valid on kind 'at'"));
            }
            if req.delete_after_fire {
                return Err(bad("deleteAfterFire is only valid on kind 'at'"));
            }
        }
        validate_action(&req.action)?;
        let project_id = req.project_id.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_string);
        if project_id.is_some() && req.action.kind != "prompt" {
            return Err(bad("projectId is only valid on action 'prompt'"));
        }
        if req.coalesce_ms < 0 {
            return Err(bad("coalesceMs must be >= 0"));
        }
        if req.ceiling_runs < 1 {
            return Err(bad("ceilingRuns must be >= 1"));
        }
        if req.ceiling_window_ms < 1_000 {
            return Err(bad("ceilingWindowMs must be >= 1000"));
        }
        if let Some(i) = req.interval_ms.filter(|i| *i < MIN_INTERVAL_MS) {
            return Err(bad(format!("a schedule interval must be at least 1 minute (intervalMs >= {MIN_INTERVAL_MS}), got {i}")));
        }
        let existing = match req.id.as_deref().map(str::trim).filter(|s| !s.is_empty()) {
            Some(id) => Some(self.repo.get(id).ok_or_else(|| bad(format!("no trigger '{}'", short(id, 80))))?),
            None => None,
        };
        if let Some(e) = &existing {
            require_owner(e, owner)?;
        }
        if is_at && req.interval_ms.is_some() {
            return Err(bad("intervalMs is only valid on kind 'schedule'"));
        }
        let secret = req.webhook_secret.clone();
        if req.kind == "webhook" {
            let has_old = existing.as_ref().is_some_and(|e| self.repo.webhook_secret(&e.id).is_some());
            if secret.is_none() && !has_old {
                return Err(bad("kind 'webhook' needs a webhookSecret"));
            }
            if let Some(s) = &secret {
                validate_secret(s)?;
            }
        } else if secret.is_some() {
            return Err(bad("webhookSecret is only valid on kind 'webhook'"));
        }
        let st = if !req.enabled {
            state::DISABLED.to_string()
        } else {
            match &existing {
                None => state::ARMED.into(),
                Some(e) if e.state == state::DISABLED => state::ARMED.into(),
                Some(e) => e.state.clone(),
            }
        };
        let tripped = st == state::TRIPPED;
        let mut action = req.action.clone();
        action.agent_id = action.agent_id.as_deref().map(str::trim).filter(|s| !s.is_empty()).map(str::to_string);
        let id = existing.as_ref().map(|e| e.id.clone()).unwrap_or_else(|| format!("trg-{}", short(&self.ports.new_id(), 8)));
        let view = TriggerView {
            id: id.clone(),
            name,
            kind: req.kind.clone(),
            action,
            enabled: req.enabled,
            state: st,
            coalesce_ms: req.coalesce_ms,
            ceiling_runs: req.ceiling_runs,
            ceiling_window_ms: req.ceiling_window_ms,
            created_at: existing.as_ref().map(|e| e.created_at).unwrap_or_else(|| self.ports.now()),
            tripped_at: existing.as_ref().and_then(|e| e.tripped_at).filter(|_| tripped),
            tripped_reason: existing.as_ref().and_then(|e| e.tripped_reason.clone()).filter(|_| tripped),
            interval_ms: req.interval_ms.filter(|_| req.kind == "schedule"),
            project_id,
            last_fired_at: existing.as_ref().and_then(|e| e.last_fired_at),
            next_fire_at: None,
            fire_at: req.fire_at.filter(|_| is_at),
            delete_after_fire: is_at && req.delete_after_fire,
            owner: owner.map(str::to_string),
            reference,
        };
        // New settings take effect on a fresh guard, unless a run is in flight (it keeps its own).
        if !self.run_triggers.values().any(|t| *t == id) {
            self.guards.remove(&id);
        }
        match req.interval_ms.filter(|_| view.kind == "schedule") {
            Some(interval) => {
                let keep = existing
                    .as_ref()
                    .filter(|e| e.kind == "schedule" && e.interval_ms == Some(interval))
                    .and_then(|e| self.next_due_at.get(&e.id).copied());
                self.intervals.insert(id.clone(), interval);
                let now = self.ports.now();
                self.next_due_at.insert(id.clone(), keep.unwrap_or(now + interval));
            }
            None => {
                self.intervals.remove(&id);
                self.next_due_at.remove(&id);
            }
        }
        if view.action.kind != "prompt" && existing.is_some() {
            self.repo.set_pending_mail(&id, None)?;
        }
        if view.kind == "webhook" {
            if let Some(s) = &secret {
                self.repo.set_webhook_secret(&id, s)?;
            }
        } else {
            self.repo.delete_webhook_secret(&id)?;
        }
        self.fired_to_delete.remove(&id);
        let at_next = if is_at {
            match &existing {
                Some(e) if e.kind == AT && e.fire_at == view.fire_at => e.next_fire_at,
                _ => view.fire_at,
            }
        } else {
            None
        };
        match at_next {
            Some(due) => {
                self.one_shots.insert(id.clone(), due);
            }
            None => {
                self.one_shots.remove(&id);
            }
        }
        let next = if is_at { at_next } else { self.next_due_at.get(&id).copied() };
        Ok(self.store(TriggerView { next_fire_at: next, ..view }))
    }

    /// Feeds one event to a trigger: folded, started, refused or suppressed, and always recorded. Launches run before
    /// it returns.
    pub fn fire(&mut self, id: &str, source: &str, delivery: Option<String>, digest: Option<String>) -> R<TriggerView> {
        let v = self.fire_from(id, source, delivery, digest);
        self.drain();
        v.map(|v| self.repo.get(&v.id).unwrap_or(v))
    }

    fn fire_from(&mut self, id: &str, source: &str, delivery: Option<String>, digest: Option<String>) -> R<TriggerView> {
        let id = id.trim();
        let t = self.repo.get(id).ok_or_else(|| bad(format!("no trigger '{}'", short(id, 80))))?;
        let event = self.ports.new_id();
        if !t.enabled || t.state == state::DISABLED {
            self.record(&event, &t.id, d::SUPPRESSED_DISABLED, source, None, delivery, digest);
            return Ok(t);
        }
        if t.state == state::TRIPPED {
            self.record(&event, &t.id, d::SUPPRESSED_TRIPPED, source, None, delivery, digest);
            return Ok(t);
        }
        // At most one undelivered prompt mail per trigger: while it waits, a new event adds nothing.
        if t.action.kind == "prompt" {
            if let Some(pending) = self.repo.pending_mail(&t.id) {
                let thread = self.repo.pending_thread(&t.id);
                if self.ports.mail_pending(&pending, thread.as_deref()) {
                    self.record(&event, &t.id, d::COALESCED_PENDING, source, None, delivery, digest);
                    return Ok(t);
                }
                self.repo.set_pending_mail(&t.id, None)?;
            }
        }
        let now = self.ports.now();
        let mut out = Vec::new();
        let result = self.guard_for(&t).fire(&event, now, &mut out);
        let label = match result {
            FireResult::Started => d::STARTED,
            FireResult::Folded => d::FOLDED,
            FireResult::FollowupFolded => d::FOLLOWUP_FOLDED,
            FireResult::RefusedCeiling => d::REFUSED_CEILING,
            FireResult::SuppressedTripped => d::SUPPRESSED_TRIPPED,
        };
        let (label, over) = self.overrides.remove(&event).map(|(l, dg)| (l, dg)).unwrap_or((label, None));
        self.record(&event, &t.id, label, source, None, delivery, over.or(digest));
        self.apply(&t.id, out);
        if matches!(result, FireResult::Started | FireResult::Folded | FireResult::FollowupFolded) {
            if let Some(cur) = self.repo.get(&t.id) {
                let now = self.ports.now();
                self.store(TriggerView { last_fired_at: Some(now), ..cur });
            }
        }
        self.sync(&t.id)
    }

    /// Re-arms a TRIPPED trigger, or enables a DISABLED one.
    pub fn arm(&mut self, id: &str) -> R<TriggerView> {
        let t = self.repo.get(id.trim()).ok_or_else(|| bad(format!("no trigger '{}'", short(id, 80))))?;
        if t.state != state::TRIPPED && t.state != state::DISABLED {
            return Err(bad(format!("trigger '{}' is {}, not tripped or disabled", t.id, t.state)));
        }
        self.guards.remove(&t.id);
        Ok(self.store(TriggerView { enabled: true, state: state::ARMED.into(), tripped_at: None, tripped_reason: None, ..t }))
    }

    pub fn disable(&mut self, id: &str) -> R<TriggerView> {
        let t = self.repo.get(id.trim()).ok_or_else(|| bad(format!("no trigger '{}'", short(id, 80))))?;
        Ok(self.store(TriggerView { enabled: false, state: state::DISABLED.into(), ..t }))
    }

    pub fn delete(&mut self, id: &str) -> R<()> {
        if self.forget(id.trim())? {
            self.ports.push_all(&self.repo.all());
            Ok(())
        } else {
            Err(bad(format!("no trigger '{}'", short(id, 80))))
        }
    }

    fn forget(&mut self, id: &str) -> R<bool> {
        let existed = self.repo.delete(id)?;
        self.guards.remove(id);
        self.intervals.remove(id);
        self.next_due_at.remove(id);
        self.one_shots.remove(id);
        self.fired_to_delete.remove(id);
        Ok(existed)
    }

    /// TRIG-AT-1: drops every OWNED trigger whose module is no longer loaded (never a person's, never one in flight).
    pub fn prune_orphans(&mut self, loaded: &HashSet<String>) -> Vec<String> {
        let gone: Vec<TriggerView> = self
            .repo
            .all()
            .into_iter()
            .filter(|t| t.owner.as_ref().is_some_and(|o| !loaded.contains(o)) && !self.run_triggers.values().any(|r| *r == t.id))
            .collect();
        let mut ids = Vec::new();
        for t in gone {
            self.ports.log(&format!("trigger {} ('{}') dropped: its owner {} is no longer loaded", t.id, t.name, t.owner.clone().unwrap_or_default()));
            if self.forget(&t.id).unwrap_or(false) {
                ids.push(t.id);
            }
        }
        if !ids.is_empty() {
            self.ports.push_all(&self.repo.all());
        }
        ids
    }

    pub fn webhook_secret(&self, id: &str) -> Option<String> {
        let t = self.repo.get(id)?;
        if t.kind == "webhook" {
            self.repo.webhook_secret(&t.id)
        } else {
            None
        }
    }

    /// A session's run ended (`killed` = the owner killed it).
    pub fn on_run_ended(&mut self, session: &str, outcome: Outcome) {
        match self.run_sessions.remove(session) {
            Some(run) => self.complete(&run, outcome),
            None => {
                if self.exits.len() > EXITS_KEPT {
                    self.exits.clear();
                }
                self.exits.insert(session.into(), outcome);
            }
        }
        self.drain();
    }

    /// One pump tick: due schedules and one-shots, fired one-shots deleted, elapsed windows released.
    pub fn pump(&mut self) {
        if !self.started {
            return;
        }
        self.fire_due_schedules();
        self.fire_due_one_shots();
        self.delete_fired_one_shots();
        let now = self.ports.now();
        let ids: Vec<String> = self.guards.keys().cloned().collect();
        for id in ids {
            let mut out = Vec::new();
            let released = self.guards.get_mut(&id).is_some_and(|g| g.pump(now, &mut out));
            self.apply(&id, out);
            if released {
                let _ = self.sync(&id);
            }
        }
        self.drain();
    }

    // ---- internals ------------------------------------------------------------------------------------------

    fn guard_for(&mut self, t: &TriggerView) -> &mut Guard {
        let at = t.kind == AT;
        self.guards
            .entry(t.id.clone())
            .or_insert_with(|| Guard::new(&t.id, if at { 0 } else { t.coalesce_ms }, if at { i64::MAX } else { t.ceiling_runs }, t.ceiling_window_ms))
    }

    /// Performs a guard's effects.
    fn apply(&mut self, trigger_id: &str, out: Vec<Out>) {
        for o in out {
            match o {
                Out::Launch(run) => {
                    self.run_triggers.insert(run.run_id.clone(), trigger_id.into());
                    self.run_events.insert(run.run_id.clone(), run.clone());
                    self.launches.push(run);
                }
                Out::Trip(trip) => {
                    if let Some(t) = self.repo.get(trigger_id) {
                        let now = self.ports.now();
                        self.store(TriggerView { state: state::TRIPPED.into(), tripped_at: Some(now), tripped_reason: Some(trip.detail.clone()), ..t });
                        self.ports.attention(trip.attention_kind, trigger_id, &trip.outcome, &trip.detail);
                    }
                }
                Out::Relabel(events, label) => {
                    for e in events {
                        self.relabel(trigger_id, &e, label, None);
                    }
                }
            }
        }
    }

    /// Runs the queued launches (each with no state borrowed by the port call), then whatever they queued.
    fn drain(&mut self) {
        while !self.launches.is_empty() {
            let batch = std::mem::take(&mut self.launches);
            for run in batch {
                self.launch(run);
            }
        }
    }

    fn launch(&mut self, run: Run) {
        let Some(t) = self.repo.get(&run.trigger_id) else { return };
        match t.action.kind.as_str() {
            "start_next" => self.launch_start_next(&t, &run),
            "prompt" => self.launch_nudge(&t, &run),
            _ => {
                // TRIG-ACTIONS-1: no handler for a plugin's action kind is registered on this host.
                self.refund(&t.id, &run.run_id);
                self.complete(&run.run_id, Outcome::Succeeded);
                self.redispose(&run, d::REFUSED_UNKNOWN_ACTION, Some(format!("no handler for '{}'", t.action.kind)));
            }
        }
    }

    fn launch_start_next(&mut self, t: &TriggerView, run: &Run) {
        let answer = self.ports.start_next(t.action.agent_id.as_deref());
        match answer {
            Err(e) => {
                self.ports.log(&format!("trigger {} start_next failed: {e}", t.id));
                self.finish_refused(run, &format!("start_next failed: {e}"));
            }
            Ok(None) => {
                self.complete(&run.run_id, Outcome::Succeeded);
                self.redispose(run, d::NOTHING_TO_START, None);
            }
            Ok(Some(a)) if a.reason == "started" && a.session_id.is_some() => {
                let sid = a.session_id.unwrap_or_default();
                match self.exits.remove(&sid) {
                    Some(ended) => self.complete(&run.run_id, ended),
                    None => {
                        self.run_sessions.insert(sid, run.run_id.clone());
                    }
                }
            }
            Ok(Some(a)) => {
                let why = a.detail.filter(|s| !s.is_empty()).unwrap_or_else(|| if a.reason.is_empty() { "start refused".into() } else { a.reason.clone() });
                self.finish_refused(run, &why);
            }
        }
    }

    fn launch_nudge(&mut self, t: &TriggerView, run: &Run) {
        let delivery = self.deliver(t);
        match delivery {
            Delivery::Mailed(mail, thread) => {
                let _ = self.repo.set_pending_mail(&t.id, Some((&mail, &thread)));
                self.refund(&t.id, &run.run_id);
                self.complete(&run.run_id, Outcome::Succeeded);
                self.redispose(run, d::MAILED, None);
            }
            Delivery::Started(_) => self.complete(&run.run_id, Outcome::Succeeded),
            Delivery::Busy(_) => {
                self.refund(&t.id, &run.run_id);
                self.complete(&run.run_id, Outcome::Succeeded);
                self.redispose(run, d::DEFERRED_BUSY, None);
            }
            Delivery::TargetGone(_) => {
                self.refund(&t.id, &run.run_id);
                self.complete(&run.run_id, Outcome::Succeeded);
                self.redispose(run, d::TARGET_GONE, None);
            }
            Delivery::Refused(why) => self.finish_refused(run, &why),
        }
    }

    /// TRIGGER-NUDGE: the prompt as mail to the target's session, else a started session.
    pub fn deliver(&self, t: &TriggerView) -> Delivery {
        let agent = t.action.agent_id.clone().unwrap_or_default();
        let prompt = t.action.prompt.clone().unwrap_or_default();
        let target = self.target(&agent, t.project_id.as_deref());
        let Some(target) = target else {
            if let Some(sid) = agent.strip_prefix(SESSION_PREFIX) {
                return Delivery::TargetGone(sid.into());
            }
            return match self.ports.start_prompt(&agent, &prompt, t.project_id.as_deref()) {
                Ok(sid) => Delivery::Started(sid),
                Err(e) if e.contains("at capacity") => Delivery::Busy(e),
                Err(e) => Delivery::Refused(e),
            };
        };
        let req = MailRequest {
            from: format!("{SENDER_PREFIX}{}", t.id),
            to: target.agent_id.clone().filter(|a| !a.trim().is_empty()).unwrap_or(target.id.clone()),
            subject: format!("Trigger: {}", t.name),
            body: prompt,
            thread_id: format!("{SENDER_PREFIX}{}:{}", t.id, self.ports.now()),
        };
        match self.ports.send_mail(&req) {
            Ok((mail, thread)) => Delivery::Mailed(mail, thread),
            Err(why) => Delivery::Refused(format!("prompt mail refused: {why}")),
        }
    }

    /// `session:<id>`: exactly that session; `role:<role>`: that role in the trigger's project; else that agent's
    /// sessions. The live one first, then the newest.
    pub fn target(&self, agent: &str, project: Option<&str>) -> Option<SessionInfo> {
        let reachable: Vec<SessionInfo> = self.ports.sessions().into_iter().filter(|s| s.reachable).collect();
        if let Some(id) = agent.strip_prefix(SESSION_PREFIX) {
            return reachable.into_iter().find(|s| s.id == id);
        }
        let mut c: Vec<SessionInfo> = if let Some(role) = agent.strip_prefix(ROLE_PREFIX) {
            let p = project.filter(|p| !p.trim().is_empty());
            reachable.into_iter().filter(|s| s.role.as_deref() == Some(role) && s.project_id.as_deref().filter(|x| !x.trim().is_empty()) == p).collect()
        } else {
            reachable.into_iter().filter(|s| s.agent_id.as_deref() == Some(agent)).collect()
        };
        c.sort_by(|a, b| b.spawned_at.cmp(&a.spawned_at));
        c.iter().find(|s| s.live).cloned().or_else(|| c.into_iter().next())
    }

    fn refund(&mut self, trigger_id: &str, run_id: &str) {
        if let Some(g) = self.guards.get_mut(trigger_id) {
            g.refund_start(run_id);
        }
    }

    fn finish_refused(&mut self, run: &Run, detail: &str) {
        self.complete(&run.run_id, Outcome::StartRefused);
        if detail.contains("launch gate") {
            self.redispose(run, d::REFUSED_LAUNCH_GATE, None);
        }
    }

    /// Feeds the outcome to the guard (which may trip) and records the follow-ups a failure dropped.
    fn complete(&mut self, run_id: &str, outcome: Outcome) {
        let Some(trigger_id) = self.run_triggers.remove(run_id) else { return };
        self.run_events.remove(run_id);
        let now = self.ports.now();
        let mut out = Vec::new();
        let dropped = match self.guards.get_mut(&trigger_id) {
            Some(g) => {
                g.complete(run_id, outcome, now, &mut out);
                std::mem::take(&mut g.dropped)
            }
            None => return,
        };
        for (event, at) in dropped {
            let prior = self.repo.events(&trigger_id).into_iter().find(|e| e.event_id == event);
            let source = prior.as_ref().map(|p| p.source.clone()).unwrap_or_else(|| "manual".into());
            self.record(&event, &trigger_id, d::DROPPED_AFTER_FAILURE, &source, Some(at), prior.as_ref().and_then(|p| p.delivery_id.clone()), prior.and_then(|p| p.digest));
        }
        self.apply(&trigger_id, out);
        let _ = self.sync(&trigger_id);
    }

    fn trip_stored(&mut self, t: TriggerView, reason: &str) {
        let now = self.ports.now();
        let (id, name) = (t.id.clone(), t.name.clone());
        self.store(TriggerView { state: state::TRIPPED.into(), tripped_at: Some(now), tripped_reason: Some(reason.into()), ..t });
        self.ports.attention(crate::guard::ATTENTION_TRIPPED, &id, "INTERRUPTED", &format!("Trigger '{name}' tripped: {reason}; nothing was retried."));
    }

    /// Re-labels the events a run carries with what actually happened.
    fn redispose(&mut self, run: &Run, label: &'static str, digest: Option<String>) {
        for e in run.events() {
            self.relabel(&run.trigger_id, &e, label, digest.clone());
        }
    }

    fn relabel(&mut self, trigger_id: &str, event: &str, label: &'static str, digest: Option<String>) {
        match self.repo.events(trigger_id).into_iter().find(|e| e.event_id == event) {
            Some(prior) => self.record(event, trigger_id, label, &prior.source, Some(prior.received_at), prior.delivery_id, digest.or(prior.digest)),
            None => {
                self.overrides.insert(event.into(), (label, digest));
            }
        }
    }

    /// The stored state follows the guard: tripped wins, then a run in flight, else armed.
    fn sync(&mut self, id: &str) -> R<TriggerView> {
        let t = self.repo.get(id).ok_or_else(|| bad(format!("no trigger '{id}'")))?;
        if t.state == state::TRIPPED || t.state == state::DISABLED {
            return Ok(t);
        }
        let running = self.run_triggers.values().any(|r| r == id);
        let next = if running { state::RUNNING } else { state::ARMED };
        if t.state == next {
            Ok(t)
        } else {
            Ok(self.store(TriggerView { state: next.into(), ..t }))
        }
    }

    fn fire_due_schedules(&mut self) {
        let now = self.ports.now();
        let due: Vec<(String, i64)> = self.next_due_at.iter().filter(|(_, at)| now >= **at).map(|(k, v)| (k.clone(), *v)).collect();
        for (id, _) in due {
            let Some(interval) = self.intervals.get(&id).copied() else { continue };
            self.next_due_at.insert(id.clone(), now + interval);
            if let Some(t) = self.repo.get(&id) {
                self.store(TriggerView { next_fire_at: Some(now + interval), ..t });
            }
            if let Err(e) = self.fire_from(&id, "schedule", None, None) {
                self.ports.log(&format!("schedule fire for {id} failed: {e}"));
            }
        }
    }

    fn fire_due_one_shots(&mut self) {
        if self.one_shots.is_empty() {
            return;
        }
        let now = self.ports.now();
        let due: Vec<(String, i64)> = self.one_shots.iter().filter(|(_, at)| now >= **at).map(|(k, v)| (k.clone(), *v)).collect();
        for (id, at) in due {
            let Some(t) = self.repo.get(&id) else {
                self.one_shots.remove(&id);
                continue;
            };
            if !t.enabled || t.state == state::DISABLED || t.state == state::TRIPPED {
                continue;
            }
            self.one_shots.remove(&id);
            let late = now - at;
            if late > self.ports.at_max_late_ms().max(0) {
                let ev = self.ports.new_id();
                self.record(&ev, &id, d::MISSED, AT, None, None, None);
                self.ports.log(&format!("trigger {id} ('{}') missed its one-shot by {}s; not fired", t.name, late / 1000));
                self.store(TriggerView { enabled: false, state: state::DISABLED.into(), next_fire_at: None, ..t });
                continue;
            }
            // The slot is spent BEFORE the fire: a crash between the two loses a fire rather than repeating one.
            let delete_after = t.delete_after_fire;
            self.store(TriggerView { next_fire_at: None, ..t });
            if let Err(e) = self.fire_from(&id, AT, None, None) {
                self.ports.log(&format!("one-shot fire for {id} failed: {e}"));
            }
            if let Some(after) = self.repo.get(&id) {
                let st = if after.state == state::TRIPPED { state::TRIPPED } else { state::DISABLED };
                self.store(TriggerView { enabled: false, state: st.into(), ..after });
            }
            if delete_after {
                self.fired_to_delete.insert(id);
            }
        }
    }

    fn delete_fired_one_shots(&mut self) {
        if self.fired_to_delete.is_empty() {
            return;
        }
        let done: Vec<String> = self.fired_to_delete.iter().filter(|id| !self.run_triggers.values().any(|r| r == *id)).cloned().collect();
        if done.is_empty() {
            return;
        }
        for id in &done {
            let _ = self.forget(id);
        }
        self.ports.push_all(&self.repo.all());
    }

    fn store(&mut self, v: TriggerView) -> TriggerView {
        if let Err(e) = self.repo.upsert(&v, self.intervals.get(&v.id).copied()) {
            self.ports.log(&format!("trigger {} not stored: {e}", v.id));
        }
        self.ports.push(&v);
        v
    }

    #[allow(clippy::too_many_arguments)]
    fn record(&mut self, event: &str, trigger_id: &str, label: &str, source: &str, at: Option<i64>, delivery: Option<String>, digest: Option<String>) {
        let at = at.unwrap_or_else(|| self.ports.now());
        let row = EventView { event_id: event.into(), trigger_id: trigger_id.into(), received_at: at, source: source.into(), disposition: label.into(), delivery_id: delivery, digest };
        if let Err(e) = self.repo.insert_event(&row) {
            self.ports.log(&format!("trigger {trigger_id} event not recorded: {e}"));
        }
        let name = match label {
            d::STARTED | d::MAILED => EV_FIRED,
            d::DELIVERED => EV_DELIVERED,
            l if REFUSALS.contains(&l) => EV_REFUSED,
            _ => return,
        };
        let t = self.repo.get(trigger_id);
        self.ports.publish(
            name,
            json!({"id": trigger_id, "owner": t.as_ref().and_then(|t| t.owner.clone()), "ref": t.and_then(|t| t.reference), "disposition": label, "sessionId": null}),
        );
    }
}

/// TRIG-AT-1: only a trigger's owner may change it (None = a person).
pub fn require_owner(t: &TriggerView, owner: Option<&str>) -> R<()> {
    if t.owner.as_deref() == owner {
        return Ok(());
    }
    Err(match &t.owner {
        Some(o) => bad(format!("trigger {} is owned by {o}; it is changed through that module", t.id)),
        None => bad(format!("trigger {} is a person's; a module cannot change it", t.id)),
    })
}

fn plugin_kind(kind: &str) -> bool {
    let mut parts = kind.split('.');
    let Some(first) = parts.next() else { return false };
    let head_ok = first.chars().next().is_some_and(|c| c.is_ascii_lowercase()) && first.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_');
    let rest: Vec<&str> = parts.collect();
    head_ok && !rest.is_empty() && rest.iter().all(|p| !p.is_empty() && p.chars().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '-'))
}

fn validate_action(a: &crate::model::Action) -> R<()> {
    match a.kind.as_str() {
        "start_next" => {
            if a.prompt.is_some() {
                return Err(bad("action 'start_next' takes no prompt (use action 'prompt')"));
            }
        }
        "prompt" => {
            if a.agent_id.as_deref().is_none_or(|s| s.trim().is_empty()) {
                return Err(bad("action 'prompt' needs an agentId (a profile id or role:<role>)"));
            }
            let p = a.prompt.as_deref().unwrap_or("");
            if p.trim().is_empty() {
                return Err(bad("action 'prompt' needs a non-blank prompt"));
            }
            if p.chars().count() > MAX_PROMPT_CHARS {
                return Err(bad(format!("action 'prompt' text is longer than {MAX_PROMPT_CHARS} characters")));
            }
        }
        k if plugin_kind(k) => {}
        k => return Err(bad(format!("action '{}' is not served (served: start_next, prompt, or a plugin kind like 'acme.notify')", short(k, 40)))),
    }
    Ok(())
}

fn validate_secret(s: &str) -> R<()> {
    if !(MIN_WEBHOOK_SECRET..=MAX_WEBHOOK_SECRET).contains(&s.chars().count()) {
        return Err(bad(format!("webhookSecret must be {MIN_WEBHOOK_SECRET}..{MAX_WEBHOOK_SECRET} characters")));
    }
    if s.chars().any(|c| c.is_whitespace() || c.is_control()) {
        return Err(bad("webhookSecret must not contain whitespace or control characters"));
    }
    Ok(())
}
