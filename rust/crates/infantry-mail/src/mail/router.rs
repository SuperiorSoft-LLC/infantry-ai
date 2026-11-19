// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The mail router: addressing (session id, agent id, `role`, `role@project`, `starter`), the cross-project guard,
//! the pre-delivery hook, wake policy (mode, debounce, hard bound) and delivery into session turns.
//!
//! Sessions are reached through two seams the daemon implements: [`SessionDirectory`] (who exists, who is busy,
//! which project) and [`TurnSink`] (inject a turn). Timers are explicit: the daemon calls [`MailRouter::tick`] on
//! its scheduler and [`MailRouter::session_idle`] when a turn ends; nothing here spawns a thread.
use std::collections::HashSet;
use std::sync::{Arc, Mutex, RwLock};

use infantry_protocol::MessageOrigin;
use regex::Regex;

use super::mailbox::{DeliveryOutcome, FanOutResult, MailDelivery, MailResult, Mailbox};
use super::message::{is_automation, MailFanOutRequest, MailSendRequest, Message, WaitingOn, STARTER, SYSTEM};
use super::render::{self, DeliveryMode};

/// One session as the router sees it.
#[derive(Debug, Clone, PartialEq, Default)]
pub struct SessionInfo {
    pub id: String,
    pub project_id: Option<String>,
    pub role: Option<String>,
    pub agent_id: Option<String>,
    /// A turn is in flight.
    pub busy: bool,
    /// Its process is up.
    pub live: bool,
    /// It can be resumed to take a turn.
    pub resumable: bool,
    pub last_active: i64,
}

/// A project as addressing knows it: `role@<key>` matches the id or the key (case-insensitive).
#[derive(Debug, Clone, PartialEq)]
pub struct ProjectRef {
    pub id: String,
    pub key: String,
}

pub trait SessionDirectory: Send + Sync {
    fn sessions(&self) -> Vec<SessionInfo>;
    fn projects(&self) -> Vec<ProjectRef>;
    /// The session that started `session`'s ticket, when one did.
    fn starter_of(&self, session: &str) -> Option<String>;
    fn session(&self, id: &str) -> Option<SessionInfo> {
        self.sessions().into_iter().find(|s| s.id == id)
    }
}

pub trait TurnSink: Send + Sync {
    /// Starts (or resumes into) a turn of `session` with `text`. Err = could not.
    fn inject(&self, session: &str, text: &str, origin: &MessageOrigin) -> Result<(), String>;
}

/// The pre-delivery hook's answer for one message.
#[derive(Debug, Clone, PartialEq)]
pub enum IncomingVerdict {
    /// Deliver as is.
    Pass,
    /// Handled by the hook: mark read, wake nobody.
    Handled,
    /// Deliver this text instead of the rendered message.
    Replace(String),
}

type IncomingHook = Arc<dyn Fn(&Message) -> IncomingVerdict + Send + Sync>;
type SentListener = Arc<dyn Fn(&Message) + Send + Sync>;

/// Wake policy (`mail.wakeMode`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum WakeMode {
    Never,
    /// Automation mail does not wake, except a bg job's final notice.
    #[default]
    Real,
    All,
}

impl WakeMode {
    pub fn parse(raw: &str) -> Self {
        match raw.trim().to_ascii_lowercase().as_str() {
            "never" => WakeMode::Never,
            "all" => WakeMode::All,
            _ => WakeMode::Real,
        }
    }
}

/// Router settings, read on every use.
#[derive(Debug, Clone, PartialEq)]
pub struct RouterConfig {
    pub wake_mode: WakeMode,
    pub wake_max_ms: i64,
    pub wake_debounce_ms: i64,
    pub coalesce_max: usize,
    pub delivery_mode: DeliveryMode,
    pub automation_footer: Option<String>,
}

impl Default for RouterConfig {
    fn default() -> Self {
        RouterConfig { wake_mode: WakeMode::Real, wake_max_ms: 120_000, wake_debounce_ms: 20_000, coalesce_max: 10, delivery_mode: DeliveryMode::Full, automation_footer: None }
    }
}

pub struct MailRouter {
    mailbox: Arc<Mailbox>,
    dir: Arc<dyn SessionDirectory>,
    turns: Arc<dyn TurnSink>,
    config: Arc<dyn Fn() -> RouterConfig + Send + Sync>,
    incoming: RwLock<Option<IncomingHook>>,
    sent: RwLock<Vec<SentListener>>,
    pumping: Mutex<HashSet<String>>,
    bg_final: Regex,
}

impl MailRouter {
    pub fn new(mailbox: Arc<Mailbox>, dir: Arc<dyn SessionDirectory>, turns: Arc<dyn TurnSink>, config: Arc<dyn Fn() -> RouterConfig + Send + Sync>) -> Self {
        MailRouter {
            mailbox,
            dir,
            turns,
            config,
            incoming: RwLock::new(None),
            sent: RwLock::new(Vec::new()),
            pumping: Mutex::new(HashSet::new()),
            bg_final: Regex::new(r"bg job \S+ (finished|ended without an exit code|still running after)").unwrap(),
        }
    }

    pub fn mailbox(&self) -> &Arc<Mailbox> {
        &self.mailbox
    }

    pub fn on_incoming(&self, hook: impl Fn(&Message) -> IncomingVerdict + Send + Sync + 'static) {
        *self.incoming.write().unwrap() = Some(Arc::new(hook));
    }

    pub fn on_sent(&self, listener: impl Fn(&Message) + Send + Sync + 'static) {
        self.sent.write().unwrap().push(Arc::new(listener));
    }

    // ---- addressing ----

    /// Resolves a recipient address to a mailbox id, or a refusal reason.
    pub fn resolve(&self, sender: &str, to: &str, allow_cross_project: bool) -> Result<String, String> {
        let to = to.trim();
        let sessions = self.dir.sessions();
        if sessions.iter().any(|s| s.id == to) || is_automation(to) || ["human", "owner", "user"].iter().any(|h| to.eq_ignore_ascii_case(h)) {
            return Ok(to.to_string());
        }
        let sender_project = sessions.iter().find(|s| s.id == sender).and_then(|s| s.project_id.clone());
        if to.eq_ignore_ascii_case(STARTER) {
            return self.dir.starter_of(sender).ok_or_else(|| "no starter recorded for your ticket (it was not started from a session)".to_string());
        }
        // Agent profile id: its live session, else the newest resumable one.
        let mut of_agent: Vec<&SessionInfo> = sessions.iter().filter(|s| s.agent_id.as_deref() == Some(to)).collect();
        if !of_agent.is_empty() {
            if let Some(live) = of_agent.iter().find(|s| s.live) {
                return Ok(live.id.clone());
            }
            of_agent.sort_by_key(|s| std::cmp::Reverse(s.last_active));
            return of_agent
                .iter()
                .find(|s| s.resumable)
                .map(|s| s.id.clone())
                .ok_or_else(|| format!("agent {to} has no running session — start it first"));
        }
        if let Some((role, key)) = to.split_once('@') {
            let projects = self.dir.projects();
            let hits: Vec<&ProjectRef> = projects.iter().filter(|p| p.id.eq_ignore_ascii_case(key) || p.key.eq_ignore_ascii_case(key)).collect();
            let project = match hits.len() {
                0 => return Err(format!("no project '{key}'")),
                1 => hits[0],
                _ => return Err(format!("project '{key}' is ambiguous: {}", hits.iter().map(|p| p.id.as_str()).collect::<Vec<_>>().join(", "))),
            };
            if sender_project.as_deref().is_some_and(|sp| sp != project.id) && !allow_cross_project {
                return Err(format!(
                    "cross-project mail refused: '{to}' is in project {} and you are in {}; pass allowCrossProject (--cross-project) to send anyway",
                    project.key,
                    sender_project.unwrap_or_default()
                ));
            }
            return Self::pick_role(&sessions, role, Some(&project.id), &format!("no live {role} session in project {}", project.key), &format!("project {}", project.key));
        }
        Self::pick_role(&sessions, to, sender_project.as_deref(), &format!("no {to} agent in this project"), "this project")
    }

    fn pick_role(sessions: &[SessionInfo], role: &str, project: Option<&str>, none: &str, scope: &str) -> Result<String, String> {
        let hits: Vec<&SessionInfo> = sessions
            .iter()
            .filter(|s| s.role.as_deref().is_some_and(|r| r.eq_ignore_ascii_case(role)) && (project.is_none() || s.project_id.as_deref() == project))
            .collect();
        let live: Vec<&&SessionInfo> = hits.iter().filter(|s| s.live).collect();
        let pool: Vec<&SessionInfo> = if live.is_empty() { hits.clone() } else { live.into_iter().copied().collect() };
        let mut agents: Vec<String> = pool.iter().map(|s| s.agent_id.clone().unwrap_or_else(|| s.id.clone())).collect();
        agents.sort();
        agents.dedup();
        match agents.len() {
            0 => Err(none.to_string()),
            1 => {
                let mut p = pool;
                p.sort_by_key(|s| (std::cmp::Reverse(s.live), std::cmp::Reverse(s.last_active)));
                Ok(p[0].id.clone())
            }
            _ => Err(format!("multiple {role} agents in {scope}: {}", agents.join(", "))),
        }
    }

    // ---- send ----

    /// Resolves, guards and sends; queued mail is pumped right away (subject to the wake policy).
    pub fn send(&self, request: &MailSendRequest, automation: bool) -> MailResult {
        let mut req = request.clone();
        if req.reply_to.as_deref().map(str::trim).is_none_or(str::is_empty) {
            match self.resolve(&req.from, &req.to, req.allow_cross_project) {
                Ok(to) => req.to = to,
                Err(reason) => return MailResult::Refused(reason),
            }
            if let Some(reason) = self.cross_project_refusal(&req) {
                return MailResult::Refused(reason);
            }
            if request.to.trim().eq_ignore_ascii_case(STARTER) {
                let unread = self.mailbox.claim_queued(&[req.from.as_str()]);
                if !unread.is_empty() {
                    let mails = unread.iter().map(|m| render::render(m, self.is_session(&m.from), None)).collect::<Vec<_>>().join("\n\n---\n\n");
                    return MailResult::Refused(format!("Not sent: mail reached you while you worked. Read it, act on it, then report again.\n\n{mails}"));
                }
            }
        }
        let result = self.mailbox.send(&req, automation);
        if let MailResult::Sent(m) = &result {
            self.after_send(m);
        }
        result
    }

    pub fn fan_out(&self, request: &MailFanOutRequest, automation: bool) -> FanOutResult {
        let mut req = request.clone();
        let mut resolved = Vec::new();
        for r in &request.recipients {
            match self.resolve(&req.from, r, false) {
                Ok(id) => resolved.push(id),
                Err(reason) => return FanOutResult::Refused(reason),
            }
        }
        req.recipients = resolved;
        let result = self.mailbox.fan_out(&req, automation);
        if let FanOutResult::Sent(ms) = &result {
            for m in ms {
                self.after_send(m);
            }
        }
        result
    }

    fn cross_project_refusal(&self, req: &MailSendRequest) -> Option<String> {
        if req.allow_cross_project {
            return None;
        }
        let from = self.dir.session(&req.from)?;
        let to = self.dir.session(&req.to)?;
        let (fp, tp) = (from.project_id.as_deref()?, to.project_id.as_deref()?);
        if fp == tp {
            return None;
        }
        let pair = self.dir.starter_of(&from.id).as_deref() == Some(to.id.as_str()) || self.dir.starter_of(&to.id).as_deref() == Some(from.id.as_str());
        (!pair).then(|| format!("cross-project mail refused: session {} is in project {tp} and you are in {fp}; pass allowCrossProject (--cross-project) to send anyway", to.id))
    }

    fn after_send(&self, m: &Message) {
        for l in self.sent.read().unwrap().clone() {
            l(m);
        }
        let hook = self.incoming.read().unwrap().clone();
        if let Some(h) = hook {
            self.mailbox.hold(&m.id);
            let verdict = h(m);
            self.mailbox.release(&m.id);
            match verdict {
                IncomingVerdict::Handled => {
                    self.mailbox.mark_handled(&m.id);
                    return;
                }
                IncomingVerdict::Replace(text) => {
                    let recipient = m.to.clone();
                    if self.deliverable_now(&recipient) && self.turns.inject(&recipient, &text, &render::origin_of(m, self.is_session(&m.from))).is_ok() {
                        self.mailbox.claim_queued(&[recipient.as_str()]);
                    }
                    return;
                }
                IncomingVerdict::Pass => {}
            }
        }
        self.pump(&m.to);
    }

    fn deliverable_now(&self, session: &str) -> bool {
        self.dir.session(session).is_some_and(|s| !s.busy)
    }

    fn is_session(&self, id: &str) -> bool {
        self.dir.session(id).is_some()
    }

    // ---- delivery ----

    /// Delivers what may be delivered to `recipient` now. Re-entrant calls for the same session are dropped.
    pub fn pump(&self, recipient: &str) {
        if !self.pumping.lock().unwrap().insert(recipient.to_string()) {
            return;
        }
        let delivery = Delivery { router: self, now: self.mailbox.now(), cfg: (self.config)() };
        self.mailbox.deliver_queued(recipient, &delivery);
        // Mail addressed to the session's agent id waits in that queue too.
        if let Some(agent) = self.dir.session(recipient).and_then(|s| s.agent_id) {
            if agent != recipient {
                self.mailbox.deliver_queued(&agent, &AgentQueue { inner: &delivery, session: recipient });
            }
        }
        self.pumping.lock().unwrap().remove(recipient);
    }

    /// A session's turn ended: deliver what waited for it.
    pub fn session_idle(&self, session: &str) {
        self.pump(session);
    }

    /// Scheduler tick: pumps every recipient with queued mail, so debounce windows and the hard wake bound apply.
    pub fn tick(&self) {
        // Only the recipient ids, never a copy of every message (RUST-BUG e0c433c9: that clone ran out of memory).
        for r in self.mailbox.queued_recipients() {
            self.pump(&r);
        }
    }

    /// Open needs-input signals whose sender is at rest (a busy sender is still working, not waiting).
    pub fn waiting(&self) -> Vec<WaitingOn> {
        self.mailbox.waiting().into_iter().filter(|w| !self.dir.session(&w.session).is_some_and(|s| s.busy)).collect()
    }

    fn is_bg_final(&self, m: &Message) -> bool {
        m.from.eq_ignore_ascii_case(SYSTEM) && m.thread_id.starts_with("bg:") && self.bg_final.is_match(&m.subject)
    }
}

struct Delivery<'a> {
    router: &'a MailRouter,
    now: i64,
    cfg: RouterConfig,
}

impl Delivery<'_> {
    fn deliver_to(&self, session_id: &str, message: &Message, rest: &[Message]) -> DeliveryOutcome {
        let r = self.router;
        let Some(s) = r.dir.session(session_id) else {
            return if is_automation(&message.to) || ["human", "owner", "user"].iter().any(|h| message.to.eq_ignore_ascii_case(h)) {
                // The owner reads in the inbox: it never takes a turn.
                DeliveryOutcome::Retry("read in the inbox".into())
            } else {
                DeliveryOutcome::Failed(format!("unknown recipient session '{}'", message.to))
            };
        };
        if s.busy {
            return DeliveryOutcome::Retry(format!("recipient '{}' has a run in flight", s.id));
        }
        if !s.live && !s.resumable {
            return DeliveryOutcome::Failed(format!("session '{}' has ended and cannot be resumed", s.id));
        }
        let folded: Vec<&Message> = rest.iter().take(self.cfg.coalesce_max).collect();
        let mut batch = vec![message];
        batch.extend(folded.iter().copied());
        let text = render::turn_text(&batch, self.cfg.delivery_mode, &|id| r.is_session(id), self.cfg.automation_footer.as_deref());
        match r.turns.inject(&s.id, &text, &render::origin_of(message, r.is_session(&message.from))) {
            Ok(()) => DeliveryOutcome::Delivered { also_delivered: folded.iter().map(|m| m.id.clone()).collect() },
            Err(e) => DeliveryOutcome::Retry(e),
        }
    }
}

impl MailDelivery for Delivery<'_> {
    fn deliver(&self, message: &Message, rest: &[Message]) -> DeliveryOutcome {
        self.deliver_to(&message.to, message, rest)
    }

    fn wakes(&self, m: &Message) -> bool {
        let age = self.now - m.sent_at;
        if age >= self.cfg.wake_max_ms {
            return true;
        }
        let by_mode = match self.cfg.wake_mode {
            WakeMode::Never => false,
            WakeMode::All => true,
            WakeMode::Real => !is_automation(&m.from) || self.router.is_bg_final(m),
        };
        by_mode && age >= self.cfg.wake_debounce_ms
    }
}

struct AgentQueue<'a, 'b> {
    inner: &'a Delivery<'b>,
    session: &'a str,
}

impl MailDelivery for AgentQueue<'_, '_> {
    fn deliver(&self, message: &Message, rest: &[Message]) -> DeliveryOutcome {
        self.inner.deliver_to(self.session, message, rest)
    }
    fn wakes(&self, m: &Message) -> bool {
        self.inner.wakes(m)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::mail::mailbox::{tests::mailbox, MailLimits};
    use crate::mail::message::MessageState;
    use std::sync::atomic::Ordering;

    #[derive(Default)]
    struct Dir(Mutex<Vec<SessionInfo>>);
    impl SessionDirectory for Dir {
        fn sessions(&self) -> Vec<SessionInfo> {
            self.0.lock().unwrap().clone()
        }
        fn projects(&self) -> Vec<ProjectRef> {
            vec![ProjectRef { id: "p1".into(), key: "alpha".into() }, ProjectRef { id: "p2".into(), key: "beta".into() }]
        }
        fn starter_of(&self, s: &str) -> Option<String> {
            (s == "w").then(|| "pm".to_string())
        }
    }
    #[derive(Default)]
    struct Turns(Mutex<Vec<(String, String)>>);
    impl TurnSink for Turns {
        fn inject(&self, s: &str, t: &str, _o: &MessageOrigin) -> Result<(), String> {
            self.0.lock().unwrap().push((s.into(), t.into()));
            Ok(())
        }
    }

    fn s(id: &str, p: &str, role: &str, busy: bool) -> SessionInfo {
        SessionInfo { id: id.into(), project_id: Some(p.into()), role: Some(role.into()), live: true, busy, ..Default::default() }
    }

    #[test]
    fn addresses_wakes_and_guards() {
        let (mb, clock) = mailbox(MailLimits::default());
        let dir = Arc::new(Dir::default());
        *dir.0.lock().unwrap() = vec![s("pm", "p1", "pm", false), s("w", "p2", "dev", false), s("d", "p1", "dev", true), s("q", "p2", "qa", false)];
        let turns = Arc::new(Turns::default());
        let cfg = RouterConfig { wake_debounce_ms: 0, ..Default::default() };
        let r = MailRouter::new(mb.clone(), dir.clone(), turns.clone(), Arc::new(move || cfg.clone()));
        assert_eq!(r.resolve("pm", "dev", false).unwrap(), "d");
        assert_eq!(r.resolve("w", "starter", false).unwrap(), "pm");
        assert!(r.resolve("pm", "qa@beta", false).unwrap_err().starts_with("cross-project mail refused"));
        assert_eq!(r.resolve("pm", "qa@beta", true).unwrap(), "q");
        // A worker may mail its starter across projects; anyone else is refused.
        assert!(matches!(r.send(&MailSendRequest { from: "w".into(), to: "starter".into(), body: "done".into(), ..Default::default() }, false), MailResult::Sent(_)));
        assert_eq!(turns.0.lock().unwrap().len(), 1);
        let MailResult::Refused(why) = r.send(&MailSendRequest { from: "q".into(), to: "pm".into(), body: "x".into(), ..Default::default() }, false) else { panic!() };
        assert!(why.starts_with("cross-project mail refused"));
        // A busy recipient keeps its mail queued until its turn ends.
        r.send(&MailSendRequest { from: "pm".into(), to: "d".into(), body: "go".into(), ..Default::default() }, false);
        assert_eq!(mb.pending_count("d"), 1);
        dir.0.lock().unwrap()[2].busy = false;
        r.session_idle("d");
        assert_eq!(mb.pending_count("d"), 0);
        // Under "real", automation mail rides until the hard bound.
        r.send(&MailSendRequest { from: "system".into(), to: "pm".into(), subject: "note".into(), body: "x".into(), ..Default::default() }, true);
        assert_eq!(mb.pending_count("pm"), 1);
        assert_eq!(mb.queued_recipients(), vec!["pm".to_string()]);
        clock.fetch_add(120_000, Ordering::SeqCst);
        r.tick();
        assert_eq!(mb.pending_count("pm"), 0);
        assert!(mb.queued_recipients().is_empty());
        assert!(mb.all().iter().all(|m| m.state != MessageState::Queued));
    }
}
