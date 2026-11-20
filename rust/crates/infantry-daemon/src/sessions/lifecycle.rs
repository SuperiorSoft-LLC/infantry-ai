// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-SESS-2: the rest of a session's life beside the turn loop in [`super`].
//!
//! - `session_restart`: the same session, a fresh conversation (the next turn is a first turn).
//! - `session_swap`: another model, effort, harness or role, in place; across harnesses or roles the conversation is
//!   carried into the next first turn as text (HARNESS-CARRY-1).
//! - `session_stats_get`: what the harness reported for this session (prompts, turns, tools, tokens). Nothing is estimated:
//!   a field the harness never reported is null.
//! - the watchdog: WORKING / QUIET / WAITING / STALLED / IDLE on `session.watch`; a silent or stuck session raises an
//!   attention item. It NEVER changes a session's state (no false FAILED for a CLI that is working quietly).
//! - the done-worker reaper: a done ticket's worker is killed and removed once at rest; a waiting, parked or held one
//!   never is.

use super::{now_ms, state_word, Daemon, Entry};
use crate::wire;
use infantry_protocol::agents::{SessionSwap, SessionSwapped, SwapExecutor};
use infantry_protocol::session::{AgentWatch, SessionRecord, SessionState, SessionWaiting, WaitingRequest};
use infantry_protocol::Envelope;
use serde::{Deserialize, Serialize};
use serde_json::json;
use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

/// HARNESS-CARRY-1: the most conversation text a swapped session's first turn carries (the newest part is kept).
pub const CARRY_MAX_CHARS: usize = 60_000;
/// Points kept in a session's stats series (one per model request).
pub const STATS_SERIES_KEPT: usize = 2_000;

/// What the harness reported for one session, accumulated by the daemon (kept in `session-stats/<id>.json`).
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct Stats {
    pub user_prompts: i32,
    pub assistant_turns: i32,
    pub tool_uses: i32,
    /// Calls per tool name.
    pub tools: BTreeMap<String, i32>,
    pub models: Vec<String>,
    /// Whether the harness reported any token usage (else every token field is null).
    pub usage_seen: bool,
    pub first_context_tokens: Option<u64>,
    pub last_context_tokens: Option<u64>,
    pub billed_tokens: u64,
    /// (at, context tokens, cumulative billed tokens).
    pub series: Vec<(i64, u64, u64)>,
    pub first_at: Option<i64>,
    pub last_at: Option<i64>,
}

impl Stats {
    pub(super) fn turn(&mut self, model: Option<&str>) {
        self.user_prompts += 1;
        let now = now_ms();
        self.first_at.get_or_insert(now);
        self.last_at = Some(now);
        if let Some(m) = model.map(str::trim).filter(|m| !m.is_empty()) {
            if !self.models.iter().any(|x| x == m) {
                self.models.push(m.to_string());
            }
        }
    }

    pub(super) fn usage(&mut self, u: crate::harness::TokenUsage) {
        let ctx = u.input + u.cached;
        self.usage_seen = true;
        self.first_context_tokens.get_or_insert(ctx);
        self.last_context_tokens = Some(ctx);
        self.billed_tokens += u.input + u.cached + u.output;
        let now = now_ms();
        self.last_at = Some(now);
        self.series.push((now, ctx, self.billed_tokens));
        if self.series.len() > STATS_SERIES_KEPT {
            self.series.remove(0);
        }
    }

    pub(super) fn tool(&mut self, name: &str) {
        self.tool_uses += 1;
        *self.tools.entry(name.to_string()).or_default() += 1;
    }

    fn path(state_dir: &std::path::Path, id: &str) -> PathBuf {
        state_dir.join("session-stats").join(format!("{id}.json"))
    }

    pub(super) fn load(state_dir: &std::path::Path, id: &str) -> Stats {
        std::fs::read_to_string(Self::path(state_dir, id)).ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default()
    }

    pub(super) fn save(&self, state_dir: &std::path::Path, id: &str) {
        let p = Self::path(state_dir, id);
        if let Some(d) = p.parent() {
            let _ = std::fs::create_dir_all(d);
        }
        let tmp = p.with_extension("json.tmp");
        if let Ok(s) = serde_json::to_string(self) {
            if std::fs::write(&tmp, s).is_ok() {
                let _ = std::fs::rename(&tmp, &p);
            }
        }
    }

    pub(super) fn remove(state_dir: &std::path::Path, id: &str) {
        let _ = std::fs::remove_file(Self::path(state_dir, id));
    }
}

/// What the watchdog reads and does outside the session manager (the composition root wires mail and attention).
pub trait WatchPorts: Send + Sync {
    /// Whether the watchdog acts for this project (read every tick).
    fn enabled(&self, _project: Option<&str>) -> bool {
        true
    }
    /// The oldest unanswered item addressed to this session, if any.
    fn pending(&self, _rec: &SessionRecord, _now: i64) -> Option<Pending> {
        None
    }
    /// (`session.stalledMinutes`, `session.hungKillMinutes`) in ms when set; None keeps the [`WatchConfig`] value.
    fn limits(&self) -> (Option<i64>, Option<i64>) {
        (None, None)
    }
    /// Raises one attention item for the owner (kind `stalled`).
    fn raise(&self, rec: &SessionRecord, outcome: &str, detail: &str);
    /// Sends the session one nudge; false = it could not be sent.
    fn nudge(&self, _rec: &SessionRecord, _body: &str) -> bool {
        false
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Pending {
    pub key: String,
    pub since: i64,
    pub summary: String,
}

/// The watchdog's thresholds (Kotlin's `WatchdogConfig` defaults).
#[derive(Debug, Clone, Copy)]
pub struct WatchConfig {
    pub poll_ms: u64,
    /// A turn with no output this long is QUIET (attention, never FAILED).
    pub quiet_ms: i64,
    /// A BUSY turn with no output this long marks its child hung: the next turn kills that child (by pid) and starts a
    /// fresh one instead of attaching to it. 0 = never.
    pub hung_kill_ms: i64,
    /// A session at rest with an unanswered item this long is STALLED.
    pub stall_ms: i64,
    /// Between a nudge and the owner's attention item.
    pub nudge_interval_ms: i64,
}

impl Default for WatchConfig {
    fn default() -> Self {
        const MIN: i64 = 60_000;
        WatchConfig { poll_ms: 30_000, quiet_ms: 15 * MIN, hung_kill_ms: 30 * MIN, stall_ms: 5 * MIN, nudge_interval_ms: 5 * MIN }
    }
}

/// One watch episode of a session (reset when its key changes).
#[derive(Debug, Clone, Default)]
pub(super) struct Episode {
    key: String,
    nudges: i32,
    nudged_at: i64,
    raised: bool,
}

enum Act {
    Raise(SessionRecord, String, String),
    Nudge(SessionRecord, String),
}

/// The watch state of one session now (pure: the tests call it).
pub fn evaluate(rec: &SessionRecord, turn_active: bool, turn_started: i64, pending: Option<&Pending>, now: i64, cfg: &WatchConfig) -> (AgentWatch, Option<String>) {
    let activity = rec.last_output_at.unwrap_or(0).max(turn_started).max(rec.last_turn_at.unwrap_or(0));
    let w = |state: &str, p: Option<&Pending>| AgentWatch {
        state: state.into(),
        pending_since: p.map(|p| p.since),
        pending: p.map(|p| p.summary.clone()),
        last_activity_at: activity.max(rec.spawned_at),
        nudges: 0,
    };
    if turn_active {
        let state = if now - activity >= cfg.quiet_ms { "QUIET" } else { "WORKING" };
        return (w(state, pending), Some(format!("run:{}:{}", rec.id, rec.run_count)));
    }
    if rec.state != SessionState::Idle {
        return (w("IDLE", None), None);
    }
    // The agent said it is waiting (an outside event, the owner): shown, never escalated.
    if rec.waiting.is_some() {
        return (w("WAITING", pending), None);
    }
    match pending {
        Some(p) => {
            let state = if now - p.since.max(activity) >= cfg.stall_ms { "STALLED" } else { "WAITING" };
            (w(state, Some(p)), Some(p.key.clone()))
        }
        None => (w("IDLE", None), None),
    }
}

fn label(r: &SessionRecord) -> String {
    format!("{}/{}", r.role.as_deref().unwrap_or("-"), r.agent_id.clone().unwrap_or_else(|| r.id.chars().take(8).collect()))
}

impl Daemon {
    /// `waiting` `{op: on|off|get, reason?, minutes?}`: flags the session "active but waiting" (or clears it); `get`
    /// reads. Returns the flag now in force (`None` = not waiting). `on` needs a reason and 1..=`WAITING_MAX_MINUTES`.
    pub fn waiting(self: &Arc<Self>, sid: &str, req: &WaitingRequest) -> Result<Option<SessionWaiting>, String> {
        let entry = self.find(sid).ok_or_else(|| format!("unknown session '{sid}'"))?;
        let mut e = entry.lock().unwrap();
        match req.op.as_str() {
            "get" => Ok(e.rec.waiting.clone()),
            "off" => {
                if e.rec.waiting.take().is_some() {
                    self.push(&mut e);
                }
                Ok(None)
            }
            "on" => {
                let reason: String = req.reason.as_deref().unwrap_or("").trim().chars().take(WAITING_REASON_CHARS).collect();
                if reason.is_empty() {
                    return Err("waiting on needs a reason (what you are waiting for)".into());
                }
                let minutes = req.minutes.map(i64::from).unwrap_or(WAITING_DEFAULT_MINUTES);
                if !(1..=WAITING_MAX_MINUTES).contains(&minutes) {
                    return Err(format!("minutes must be 1..{WAITING_MAX_MINUTES} (limits.waiting.maxMinutes)"));
                }
                let now = now_ms();
                let until = now + minutes * 60_000;
                let w = SessionWaiting { reason, since: now, until_at: until, expires_at: until + WAITING_GRACE_MINUTES * 60_000, clear_on_output: false, output_grace_ms: 0 };
                e.rec.waiting = Some(w.clone());
                self.push(&mut e);
                Ok(Some(w))
            }
            other => Err(format!("unknown waiting op '{}' (on, off, get)", other.chars().take(40).collect::<String>())),
        }
    }
}

/// `limits.waiting.*` defaults.
pub const WAITING_DEFAULT_MINUTES: i64 = 30;
pub const WAITING_GRACE_MINUTES: i64 = 5;
pub const WAITING_MAX_MINUTES: i64 = 10_080;
pub const WAITING_REASON_CHARS: usize = 200;

/// The one-line answer of the `waiting` frame / `infantry_waiting` tool.
pub fn waiting_line(w: Option<&SessionWaiting>, now: i64) -> String {
    match w {
        None => "not waiting".into(),
        Some(w) if now > w.expires_at => format!("waiting EXPIRED {} min past the expected end: {}", (now - w.until_at) / 60_000, w.reason),
        Some(w) => format!("waiting {} min of ~{}: {}", (now - w.since) / 60_000, (w.until_at - w.since) / 60_000, w.reason),
    }
}

impl Daemon {
    /// `session_restart` `{sessionId, force}`: the next turn is a first turn (fresh conversation); a running turn is
    /// refused unless `force`, which stops it first.
    pub fn restart(self: &Arc<Self>, sid: &str, force: bool) -> Result<(), String> {
        self.not_held("session_restart", sid)?;
        let entry = self.find(sid).ok_or_else(|| format!("unknown session '{sid}'"))?;
        let mut e = entry.lock().unwrap();
        if e.turn_active && !force {
            return Err(format!(
                "session '{sid}' is mid-turn (state={}); wait for the reply to finish, or confirm with force to stop it first",
                state_word(e.rec.state).to_ascii_uppercase()
            ));
        }
        let had_process = e.hs.is_some();
        if let Some(mut hs) = e.hs.take() {
            hs.close(true);
        }
        e.turn_active = false;
        e.gen += 1;
        e.carry = None;
        Self::fresh(&mut e);
        Self::set_state(&mut e, SessionState::Idle);
        self.push(&mut e);
        self.emit(&mut e, wire::STATUS, wire::status("IDLE"));
        self.emit(&mut e, wire::STDERR, "[infantry] session restarted: the next turn starts a fresh conversation".into());
        if had_process {
            self.notify(|o| o.on_exit(&e.rec));
        }
        Ok(())
    }

    /// The record of a session that never ran (restart, a swap across harness or role).
    fn fresh(e: &mut Entry) {
        let r = &mut e.rec;
        r.provider_session_id = None;
        r.run_count = 0;
        r.kill_outcome = None;
        r.killed_by = None;
        r.last_error = None;
        r.last_exit_code = None;
        r.child_pid = None;
        r.kept_process = None;
        r.is_process_alive = false;
        r.owner_id = None;
    }

    /// Sets the role check a role swap uses (the role catalog); unset = every role is accepted.
    pub fn set_role_check(&self, f: Arc<dyn Fn(&str) -> bool + Send + Sync>) {
        let _ = self.role_known.set(f);
    }

    /// `session_swap`: moves one session (no agent's) onto another provider / model / effort / role, in place.
    pub fn swap(self: &Arc<Self>, req: SessionSwap) -> Result<SessionSwapped, String> {
        self.swap_session(req, false)
    }

    /// `agent_swap`'s session consequence: the same in-place move for a session that belongs to an agent.
    pub fn swap_agent_session(self: &Arc<Self>, req: SessionSwap) -> Result<SessionSwapped, String> {
        self.swap_session(req, true)
    }

    fn swap_session(self: &Arc<Self>, req: SessionSwap, of_agent: bool) -> Result<SessionSwapped, String> {
        let sid = req.session_id.trim().to_string();
        let provider = req.provider.trim().to_lowercase();
        if provider.is_empty() {
            return Err("pick a provider to switch to".into());
        }
        let entry = self.find(&sid).ok_or("this session no longer exists")?;
        self.not_held("session_swap", &sid)?;
        let harness = self.harness_for(Some(&provider)).ok_or_else(|| format!("'{}' isn't available here; pick another provider", trunc(&provider, 40)))?;
        let mut e = entry.lock().unwrap();
        if !of_agent && e.rec.agent_id.as_deref().is_some_and(|a| !a.trim().is_empty()) {
            return Err("this session belongs to an agent; switch the agent instead (agent_swap)".into());
        }
        if e.turn_active {
            return Err("a reply is still running; wait for it to finish or stop it, then switch".into());
        }
        let role = req.role.as_deref().map(|r| r.trim().to_lowercase());
        let role_changes = role.as_ref().is_some_and(|r| Some(r.as_str()).filter(|x| !x.is_empty()) != e.rec.role.as_deref().filter(|x| !x.is_empty()));
        if role_changes {
            if e.rec.global {
                return Err("the Assistant's role is fixed; change its model, not its role".into());
            }
            if let Some(r) = role.as_deref().filter(|r| !r.is_empty()) {
                let ok = r.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_') && self.role_known.get().is_none_or(|k| k(r));
                if !ok {
                    return Err(format!("there is no role '{}'; pick another role", trunc(r, 40)));
                }
            }
        }
        let clean = |v: &Option<String>| v.as_deref().map(str::trim).filter(|v| !v.is_empty()).map(String::from);
        let from = SwapExecutor { provider: Some(e.rec.provider.clone()), model: e.rec.model.clone(), effort: e.rec.effort.clone() };
        let from_role = e.rec.role.clone();
        let crosses = harness.name() != e.rec.provider;
        // The process runs with the old model / CLI / persona: it goes; a same-harness model change resumes the conversation.
        if let Some(mut hs) = e.hs.take() {
            hs.close(false);
            e.rec.is_process_alive = false;
            e.rec.child_pid = None;
        }
        e.rec.model = clean(&req.model);
        e.rec.effort = clean(&req.effort);
        if crosses || role_changes {
            let carry = self.carry_text(&e, &from);
            e.rec.provider = harness.name().to_string();
            if role_changes {
                e.rec.role = role.filter(|r| !r.is_empty());
            }
            Self::fresh(&mut e);
            e.carry = carry;
        }
        Self::set_state(&mut e, SessionState::Idle);
        e.gen += 1;
        self.push(&mut e);
        let line = format!(
            "[infantry] switched to {} {}{}",
            e.rec.provider,
            e.rec.model.as_deref().unwrap_or("default"),
            if crosses || role_changes { "; the conversation so far goes with the next turn" } else { "" }
        );
        self.emit(&mut e, wire::STDERR, line);
        Ok(SessionSwapped {
            session_id: e.rec.id.clone(),
            provider: e.rec.provider.clone(),
            model: e.rec.model.clone(),
            default_model: None,
            from,
            new_conversation: false,
            role: e.rec.role.clone(),
            from_role,
        })
    }

    /// HARNESS-CARRY-1: the conversation so far as text for the next first turn (None when there is none).
    fn carry_text(&self, e: &Entry, from: &SwapExecutor) -> Option<String> {
        let mut lines: Vec<String> = Vec::new();
        let mut reply = String::new();
        let flush = |reply: &mut String, lines: &mut Vec<String>| {
            if !reply.trim().is_empty() {
                lines.push(format!("Assistant: {}", reply.trim()));
            }
            reply.clear();
        };
        for (_, frame) in &e.history {
            let Ok(env) = serde_json::from_str::<Envelope>(frame) else { continue };
            match env.r#type.as_str() {
                wire::TURN => {
                    flush(&mut reply, &mut lines);
                    lines.push(format!("User: {}", env.data.trim()));
                }
                wire::TEXT_DELTA => reply.push_str(&env.data),
                _ => {}
            }
        }
        flush(&mut reply, &mut lines);
        // A carry not yet delivered (two swaps in a row) still holds the older part.
        let older = e.carry.clone().unwrap_or_default();
        if lines.is_empty() && older.is_empty() {
            return None;
        }
        let mut body = lines.join("\n\n");
        if body.chars().count() > CARRY_MAX_CHARS {
            let skip = body.chars().count() - CARRY_MAX_CHARS;
            body = format!("[... older part left out]\n{}", body.chars().skip(skip).collect::<String>());
        }
        let head = format!(
            "[infantry] This conversation moved here from {} {}. The conversation so far:",
            from.provider.as_deref().unwrap_or("another harness"),
            from.model.as_deref().unwrap_or("default")
        );
        Some(if older.is_empty() { format!("{head}\n\n{body}\n\n[end of the earlier conversation]") } else { format!("{older}\n\n{body}") })
    }

    /// `session_stats_get` `{sessionId}`: the `session_stats` body.
    pub fn stats_json(&self, sid: &str) -> Result<String, String> {
        let entry = self.find(sid).ok_or_else(|| format!("unknown session '{sid}'"))?;
        let e = entry.lock().unwrap();
        let s = &e.stats;
        let r = &e.rec;
        let tok = |v: Option<u64>| if s.usage_seen { v } else { None };
        let mut top: Vec<_> = s.tools.iter().map(|(k, v)| json!({"tool": k, "calls": v, "tokensAdded": null})).collect();
        top.sort_by_key(|v| -v["calls"].as_i64().unwrap_or(0));
        let v = json!({
            "sessionId": r.id, "harness": r.provider, "providerSessionId": r.provider_session_id,
            "status": state_word(r.state), "startedAt": s.first_at, "endedAt": s.last_at,
            "durationMs": s.first_at.zip(s.last_at).map(|(a, b)| b - a),
            "userPrompts": s.user_prompts, "assistantTurns": s.assistant_turns, "toolUses": s.tool_uses,
            "models": if s.models.is_empty() { None } else { Some(&s.models) },
            "lastContextTokens": tok(s.last_context_tokens), "modelContextLimit": null,
            "billedTokens": tok(Some(s.billed_tokens)),
            "series": if s.usage_seen { Some(s.series.iter().map(|(at, c, b)| json!({"at": at, "contextTokens": c, "cumulativeBilledTokens": b})).collect::<Vec<_>>()) } else { None },
            "topSources": if top.is_empty() { None } else { Some(top) },
            "costTotal": null, "firstContextTokens": tok(s.first_context_tokens),
        });
        Ok(v.to_string())
    }

    /// Starts the watchdog loop (once).
    pub fn start_watchdog(self: &Arc<Self>, ports: Arc<dyn WatchPorts>, cfg: WatchConfig) {
        let d = self.clone();
        crate::sessions::spawn_bg(async move {
            let mut t = tokio::time::interval(Duration::from_millis(cfg.poll_ms.max(1)));
            t.tick().await;
            loop {
                t.tick().await;
                let (d, p) = (d.clone(), ports.clone());
                let _ = tokio::task::spawn_blocking(move || d.watch_tick(&*p, &cfg, now_ms())).await;
            }
        });
    }

    /// One watchdog pass: `session.watch` on every record whose watch changed, then the nudges and attention items.
    pub fn watch_tick(self: &Arc<Self>, ports: &dyn WatchPorts, cfg: &WatchConfig, now: i64) {
        let (quiet, hung) = ports.limits();
        let cfg = &WatchConfig { quiet_ms: quiet.unwrap_or(cfg.quiet_ms), hung_kill_ms: hung.unwrap_or(cfg.hung_kill_ms), ..cfg.clone() };
        let entries: Vec<_> = self.list.lock().unwrap().iter().map(|(_, e)| e.clone()).collect();
        let mut acts = Vec::new();
        for entry in entries {
            let mut e = entry.lock().unwrap();
            if e.removed {
                continue;
            }
            let (mut watch, key) = if ports.enabled(e.rec.project_id.as_deref()) {
                let pending = if e.rec.state == SessionState::Idle || e.turn_active { ports.pending(&e.rec, now) } else { None };
                evaluate(&e.rec, e.turn_active, e.turn_started, pending.as_ref(), now, cfg)
            } else {
                (AgentWatch { state: "IDLE".into(), pending_since: None, pending: None, last_activity_at: e.rec.last_heartbeat, nudges: 0 }, None)
            };
            // A BUSY turn silent past the kill limit: its child is hung, the next turn replaces it.
            let silent = now - watch.last_activity_at;
            if e.turn_active && cfg.hung_kill_ms > 0 && silent >= cfg.hung_kill_ms {
                e.hung = true;
            }
            match &key {
                None => e.episode = None,
                Some(k) if e.episode.as_ref().is_none_or(|ep| &ep.key != k) => e.episode = Some(Episode { key: k.clone(), ..Default::default() }),
                _ => {}
            }
            let mut episode = e.episode.take();
            if let Some(ep) = episode.as_mut() {
                match watch.state.as_str() {
                    "QUIET" if !ep.raised => {
                        ep.raised = true;
                        let mins = (now - watch.last_activity_at) / 60_000;
                        acts.push(Act::Raise(
                            e.rec.clone(),
                            "watchdog_quiet".into(),
                            format!("Agent {} has printed nothing for {mins} min while its turn runs; it may be stuck (the session is left running).", label(&e.rec)),
                        ));
                    }
                    "STALLED" => {
                        if ep.nudges < 1 {
                            ep.nudges += 1;
                            ep.nudged_at = now;
                            let body = format!("You have a pending item: {}. Reply with status or finish it.", watch.pending.clone().unwrap_or_default());
                            acts.push(Act::Nudge(e.rec.clone(), body));
                        } else if !ep.raised && now - ep.nudged_at >= cfg.nudge_interval_ms {
                            ep.raised = true;
                            acts.push(Act::Raise(
                                e.rec.clone(),
                                "watchdog_stalled".into(),
                                format!("Agent {} is STALLED: {}; {} nudge(s) did not resolve it.", label(&e.rec), watch.pending.clone().unwrap_or_default(), ep.nudges),
                            ));
                        }
                    }
                    _ => {}
                }
                watch.nudges = ep.nudges;
            }
            e.episode = episode;
            // The card's status line: a BUSY turn silent past `session.stalledMinutes` reads as stalled.
            let label = (watch.state == "QUIET").then(|| format!("stalled: BUSY, no output for {} min", (now - watch.last_activity_at) / 60_000));
            let changed = e.rec.watch.as_ref().is_none_or(|w| w.state != watch.state || w.pending != watch.pending || w.nudges != watch.nudges) || e.rec.watch_label != label;
            if changed {
                e.rec.watch_label = label;
                e.rec.watch = Some(watch);
                self.push(&mut e);
            }
        }
        for a in acts {
            match a {
                Act::Raise(rec, outcome, detail) => ports.raise(&rec, &outcome, &detail),
                Act::Nudge(rec, body) => {
                    ports.nudge(&rec, &body);
                }
            }
        }
    }

    /// BASICS-KILL-1: ticket `ticket_id` is done: its worker sessions are killed and removed once at rest. A session that
    /// flagged itself waiting, a dormant (parked) one and one live in another app are never removed. Returns the ids
    /// removed now (a busy one is removed when its turn ends).
    pub fn reap_done(self: &Arc<Self>, ticket_id: &str, also: Option<&str>) -> Vec<String> {
        let ids: Vec<String> = self
            .records()
            .into_iter()
            .filter(|r| r.ticket_id.as_deref() == Some(ticket_id) || also.is_some_and(|s| s == r.id))
            .map(|r| r.id)
            .collect();
        let mut out = Vec::new();
        for id in ids {
            if self.reap_one(&id) {
                out.push(id);
            }
        }
        out
    }

    /// Reaps one worker now, or marks it for its turn's end. False = not removed now.
    pub(super) fn reap_one(self: &Arc<Self>, id: &str) -> bool {
        if self.held.lock().unwrap().contains_key(id) {
            return false;
        }
        let Some(entry) = self.find(id) else { return false };
        {
            let mut e = entry.lock().unwrap();
            if e.rec.waiting.is_some() || e.rec.dormant || !e.rec.queued.is_empty() {
                e.reap_pending = false;
                eprintln!("[infantry] done-worker reaper kept session {id}: it is waiting, parked or has queued messages");
                return false;
            }
            if e.turn_active {
                e.reap_pending = true;
                return false;
            }
        }
        let _ = self.stop_session(id, "done-worker reaper");
        if self.delete(id).is_ok() {
            eprintln!("[infantry] removed worker session {id}: its ticket is done (autoRemoveDoneWorkers)");
            true
        } else {
            false
        }
    }
}

fn trunc(s: &str, n: usize) -> String {
    s.chars().take(n).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use infantry_protocol::frames as f;

    fn rec(state: &str) -> SessionRecord {
        serde_json::from_value(json!({"id": "s1", "provider": "stub", "workingDir": ".", "spawnedAt": 0, "lastHeartbeat": 0, "state": state})).unwrap()
    }

    #[test]
    fn a_silent_turn_is_quiet_never_failed() {
        let cfg = WatchConfig::default();
        let r = rec("BUSY");
        let (w, key) = evaluate(&r, true, 1_000, None, 1_000 + cfg.quiet_ms, &cfg);
        assert_eq!(w.state, "QUIET");
        assert!(key.is_some());
        let (w, _) = evaluate(&r, true, 1_000, None, 1_000 + 60_000, &cfg);
        assert_eq!(w.state, "WORKING", "an interactive CLI working for a minute is not stuck (bug 800ec30c)");
    }

    #[test]
    fn at_rest_with_a_pending_item_stalls_and_waiting_flag_never_escalates() {
        let cfg = WatchConfig::default();
        let mut r = rec("IDLE");
        let p = Pending { key: "m1".into(), since: 0, summary: "mail from pm".into() };
        assert_eq!(evaluate(&r, false, 0, Some(&p), 60_000, &cfg).0.state, "WAITING");
        let (w, key) = evaluate(&r, false, 0, Some(&p), cfg.stall_ms, &cfg);
        assert_eq!((w.state.as_str(), key.as_deref()), ("STALLED", Some("m1")));
        r.waiting = serde_json::from_value(json!({"reason": "owner", "since": 0})).ok();
        if r.waiting.is_some() {
            let (w, key) = evaluate(&r, false, 0, Some(&p), cfg.stall_ms * 10, &cfg);
            assert_eq!((w.state.as_str(), key), ("WAITING", None));
        }
        assert_eq!(evaluate(&rec("IDLE"), false, 0, None, 1 << 40, &cfg).0.state, "IDLE");
    }

    #[test]
    fn stats_count_only_what_was_reported() {
        let mut s = Stats::default();
        s.turn(Some("m"));
        s.tool("Bash");
        assert!(!s.usage_seen);
        s.usage(crate::harness::TokenUsage { input: 10, cached: 5, output: 3, reasoning: 0 });
        assert_eq!((s.last_context_tokens, s.billed_tokens, s.tool_uses), (Some(15), 18, 1));
    }

    #[test]
    fn frames_have_kotlin_names() {
        assert_eq!((f::SESSION_RESTART, f::SESSION_SWAP, f::SESSION_SWAPPED, f::SESSION_STATS_GET, f::SESSION_STATS), (
            "session_restart", "session_swap", "session_swapped", "session_stats_get", "session_stats"
        ));
    }
}
