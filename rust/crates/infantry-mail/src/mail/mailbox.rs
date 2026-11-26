// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The mailbox: threads of messages between agents, with durable delivery and read receipts.
//!
//! Transport-free and session-free: it owns messages (thread order, persistence, state, limits) and hands delivery
//! to a [`MailDelivery`] the router implements. INVARIANT: persist before deliver. Every mutation runs under one
//! lock; delivery and event emission happen after it is released, so a delivery that re-enters is safe.
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{Arc, Mutex, RwLock};

use super::message::{is_automation, threads, Message, MessageState, MailFanOutRequest, MailSendRequest, ThreadSummary, WaitingOn};
use super::store::MailStore;

/// The boundaries around a conversation, read on every send so a changed setting applies at once.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MailLimits {
    pub per_sender_per_window: usize,
    pub window_ms: i64,
    pub max_per_thread: usize,
}

pub const KEY_PER_SENDER: &str = "limits.mail.perSenderPerWindow";
pub const KEY_WINDOW_MINUTES: &str = "limits.mail.windowMinutes";
pub const KEY_MAX_PER_THREAD: &str = "limits.mail.maxPerThread";
const MS_PER_MINUTE: i64 = 60_000;

impl Default for MailLimits {
    fn default() -> Self {
        MailLimits { per_sender_per_window: 12, window_ms: 10 * MS_PER_MINUTE, max_per_thread: 60 }
    }
}

impl MailLimits {
    /// From the three settings; `perSenderPerWindow = 0` means no sender limit.
    pub fn of(limit: impl Fn(&str) -> Option<i64>) -> Self {
        let d = MailLimits::default();
        MailLimits {
            per_sender_per_window: match limit(KEY_PER_SENDER) {
                Some(n) if n <= 0 => usize::MAX,
                Some(n) => n as usize,
                None => d.per_sender_per_window,
            },
            window_ms: limit(KEY_WINDOW_MINUTES).map(|m| m.max(1) * MS_PER_MINUTE).unwrap_or(d.window_ms),
            max_per_thread: limit(KEY_MAX_PER_THREAD).map(|n| n.max(1) as usize).unwrap_or(d.max_per_thread),
        }
    }
    pub fn window_minutes(&self) -> i64 {
        self.window_ms / MS_PER_MINUTE
    }
}

fn hint(key: &str) -> String {
    let env: String = key
        .trim_start_matches("limits.")
        .chars()
        .flat_map(|c| if c.is_ascii_uppercase() { vec!['_', c] } else if c == '.' { vec!['_'] } else { vec![c.to_ascii_uppercase()] })
        .collect();
    format!("Change it with the setting '{key}' (Settings > Limits, `infantry settings set {key} <n>`, or env INFANTRY_LIMIT_{env}).")
}

fn rate_hint() -> String {
    format!(
        "{}; the window is the setting '{KEY_WINDOW_MINUTES}'. Automation mail (system, triggers, script hooks) is exempt.",
        hint(KEY_PER_SENDER).trim_end_matches('.').to_string() + "; 0 = no sender limit"
    )
}

#[derive(Debug, Clone, PartialEq)]
pub enum MailResult {
    Sent(Message),
    Refused(String),
}

#[derive(Debug, Clone, PartialEq)]
pub enum FanOutResult {
    Sent(Vec<Message>),
    Refused(String),
}

/// A mailbox event the daemon turns into a frame.
#[derive(Debug, Clone, PartialEq)]
pub enum MailEvent {
    Sent(Message),
    Changed(Message),
    Refused { from: String, to: String, reason: String },
    CeilingReached { thread_id: String, limit: usize },
}

#[derive(Debug, Clone, PartialEq)]
pub enum DeliveryOutcome {
    /// Handed over; `also_delivered` = later queued messages folded into the same turn.
    Delivered { also_delivered: Vec<String> },
    /// Busy: keep it QUEUED.
    Retry(String),
    /// Can never be delivered: record it UNDELIVERABLE.
    Failed(String),
}

pub trait MailDelivery {
    /// `rest` = the other QUEUED messages for the same recipient, oldest first.
    fn deliver(&self, message: &Message, rest: &[Message]) -> DeliveryOutcome;
    /// False = this message must not start a wake (it rides in the next turn).
    fn wakes(&self, _message: &Message) -> bool {
        true
    }
}

type Clock = Arc<dyn Fn() -> i64 + Send + Sync>;
type IdFactory = Arc<dyn Fn() -> String + Send + Sync>;
type Sink = Arc<dyn Fn(&MailEvent) + Send + Sync>;

#[derive(Default)]
struct Inner {
    messages: HashMap<String, Message>,
    order: Vec<String>,
    seq_by_thread: HashMap<String, i64>,
    /// Per-sender sliding windows. In memory only: enforced, not durable.
    send_history: HashMap<String, VecDeque<i64>>,
}

impl Inner {
    fn insert(&mut self, m: Message) {
        if !self.messages.contains_key(&m.id) {
            self.order.push(m.id.clone());
        }
        self.messages.insert(m.id.clone(), m);
    }
    fn all(&self) -> impl Iterator<Item = &Message> {
        self.order.iter().filter_map(|id| self.messages.get(id))
    }
    fn count_in(&self, thread: &str) -> usize {
        self.messages.values().filter(|m| m.thread_id == thread).count()
    }
    fn next_seq(&mut self, thread: &str) -> i64 {
        let s = self.seq_by_thread.entry(thread.to_string()).or_insert(0);
        *s += 1;
        *s
    }
}

pub struct Mailbox {
    store: Arc<dyn MailStore>,
    limits: Arc<dyn Fn() -> MailLimits + Send + Sync>,
    clock: Clock,
    ids: IdFactory,
    sink: RwLock<Option<Sink>>,
    inner: Mutex<Inner>,
    held: Mutex<HashSet<String>>,
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

impl Mailbox {
    pub fn new(store: Arc<dyn MailStore>) -> Self {
        Self::with(store, Arc::new(MailLimits::default), Arc::new(now_ms), Arc::new(|| uuid::Uuid::new_v4().to_string()))
    }

    pub fn with(store: Arc<dyn MailStore>, limits: Arc<dyn Fn() -> MailLimits + Send + Sync>, clock: Clock, ids: IdFactory) -> Self {
        Mailbox { store, limits, clock, ids, sink: RwLock::new(None), inner: Mutex::new(Inner::default()), held: Mutex::new(HashSet::new()) }
    }

    pub fn set_event_sink(&self, sink: impl Fn(&MailEvent) + Send + Sync + 'static) {
        *self.sink.write().unwrap() = Some(Arc::new(sink));
    }

    pub fn now(&self) -> i64 {
        (self.clock)()
    }

    /// Loads the persisted messages and rebuilds the per-thread counters.
    pub fn load(&self) {
        let loaded = self.store.load();
        let mut g = self.inner.lock().unwrap();
        let mut sorted = loaded;
        sorted.sort_by(|a, b| a.sent_at.cmp(&b.sent_at).then(a.seq.cmp(&b.seq)));
        for mut m in sorted {
            // MAIL-OWNER-1: mail to the human user is never undeliverable (it reaches the owner in every project). A
            // Kotlin-era row that failed with "no owner agent in this project" is the owner's delivered mail.
            if m.to_owner() && m.state == MessageState::Undeliverable {
                m.state = MessageState::Delivered;
                m.delivered_at = m.delivered_at.or(Some(m.sent_at));
                m.failure_reason = None;
            }
            let cur = g.seq_by_thread.get(&m.thread_id).copied().unwrap_or(0);
            if m.seq > cur {
                g.seq_by_thread.insert(m.thread_id.clone(), m.seq);
            }
            g.insert(m);
        }
    }

    // ---- reads ----

    pub fn message(&self, id: &str) -> Option<Message> {
        self.inner.lock().unwrap().messages.get(id).cloned()
    }

    /// A full id, or a unique prefix of one (notices print 8 characters).
    pub fn find(&self, id_or_prefix: &str) -> Result<Option<Message>, String> {
        let g = self.inner.lock().unwrap();
        let wanted = id_or_prefix.trim();
        if let Some(m) = g.messages.get(wanted) {
            return Ok(Some(m.clone()));
        }
        if wanted.is_empty() {
            return Ok(None);
        }
        let matches: Vec<&Message> = g.messages.values().filter(|m| m.id.starts_with(wanted)).collect();
        match matches.len() {
            0 => Ok(None),
            1 => Ok(Some(matches[0].clone())),
            _ => Err(format!(
                "'{wanted}' is ambiguous: {}; use a longer id",
                matches.iter().map(|m| m.id.chars().take(12).collect::<String>()).collect::<Vec<_>>().join(", ")
            )),
        }
    }

    pub fn thread(&self, thread_id: &str) -> Vec<Message> {
        let g = self.inner.lock().unwrap();
        let mut v: Vec<Message> = g.all().filter(|m| m.thread_id == thread_id).cloned().collect();
        v.sort_by_key(|m| m.seq);
        v
    }

    fn by_time(mut v: Vec<Message>) -> Vec<Message> {
        v.sort_by(|a, b| a.sent_at.cmp(&b.sent_at).then(a.seq.cmp(&b.seq)));
        v
    }

    /// Messages addressed TO a session, oldest first, in every state.
    pub fn inbox(&self, session: &str) -> Vec<Message> {
        Self::by_time(self.inner.lock().unwrap().all().filter(|m| m.to == session).cloned().collect())
    }

    /// Messages sent BY a session, oldest first.
    pub fn outbox(&self, session: &str) -> Vec<Message> {
        Self::by_time(self.inner.lock().unwrap().all().filter(|m| m.from == session).cloned().collect())
    }

    /// Every message, oldest first (the owner's whole mail view).
    pub fn all(&self) -> Vec<Message> {
        Self::by_time(self.inner.lock().unwrap().all().cloned().collect())
    }

    /// The distinct recipients that have QUEUED mail, sorted. Reads under the lock and copies only the ids: the
    /// scheduler tick calls this every few seconds, and cloning the whole mailbox there (RUST-BUG e0c433c9) made a
    /// large allocation each tick, so the daemon was the first process to abort when the machine ran out of commit.
    pub fn queued_recipients(&self) -> Vec<String> {
        let g = self.inner.lock().unwrap();
        let set: std::collections::BTreeSet<&str> = g.messages.values().filter(|m| m.state == MessageState::Queued).map(|m| m.to.as_str()).collect();
        set.into_iter().map(str::to_string).collect()
    }

    /// All threads, most recently active first.
    pub fn threads(&self) -> Vec<ThreadSummary> {
        let limit = (self.limits)().max_per_thread;
        let g = self.inner.lock().unwrap();
        let mut by: HashMap<&str, Vec<&Message>> = HashMap::new();
        for m in g.all() {
            by.entry(m.thread_id.as_str()).or_default().push(m);
        }
        let mut out: Vec<ThreadSummary> = by
            .into_iter()
            .map(|(id, mut msgs)| {
                msgs.sort_by_key(|m| m.seq);
                let mut participants = threads::pair_members(id);
                if participants.is_empty() {
                    let mut p: Vec<String> = msgs.iter().flat_map(|m| [m.from.clone(), m.to.clone()]).collect();
                    p.sort();
                    p.dedup();
                    participants = p;
                }
                ThreadSummary {
                    id: id.to_string(),
                    kind: threads::kind_of(id),
                    participants,
                    group: threads::group_of(id),
                    message_count: msgs.len(),
                    last_at: msgs.iter().map(|m| m.sent_at).max().unwrap_or(0),
                    ceiling_reached: msgs.len() >= limit,
                    open_needs_input: msgs.iter().filter(|m| m.open_question()).count(),
                }
            })
            .collect();
        out.sort_by(|a, b| b.last_at.cmp(&a.last_at).then(a.id.cmp(&b.id)));
        out
    }

    /// Open needs-input signals (the router narrows them to senders at rest).
    pub fn waiting(&self) -> Vec<WaitingOn> {
        let g = self.inner.lock().unwrap();
        let mut v: Vec<&Message> = g.all().filter(|m| m.open_question()).collect();
        v.sort_by_key(|m| m.sent_at);
        v.into_iter()
            .map(|m| WaitingOn {
                session: m.from.clone(),
                message_id: m.id.clone(),
                thread_id: m.thread_id.clone(),
                waiting_on: m.to.clone(),
                since: m.sent_at,
                subject: m.subject.clone(),
            })
            .collect()
    }

    pub fn pending_count(&self, recipient: &str) -> usize {
        self.inner.lock().unwrap().messages.values().filter(|m| m.to == recipient && m.state == MessageState::Queued).count()
    }

    // ---- send ----

    /// Persists and records a message, or refuses it (a refused message is NOT stored). A reply inherits its
    /// thread and recipient from the message it answers, which becomes ANSWERED. `automation` mail is exempt
    /// from the sender rate limit.
    pub fn send(&self, request: &MailSendRequest, automation: bool) -> MailResult {
        let (result, events) = {
            let mut g = self.inner.lock().unwrap();
            self.send_locked(&mut g, request, automation)
        };
        self.emit(&events);
        result
    }

    fn refuse(from: &str, to: &str, reason: String) -> (MailResult, Vec<MailEvent>) {
        let ev = MailEvent::Refused { from: from.into(), to: to.into(), reason: reason.clone() };
        (MailResult::Refused(reason), vec![ev])
    }

    fn send_locked(&self, g: &mut Inner, request: &MailSendRequest, automation: bool) -> (MailResult, Vec<MailEvent>) {
        let from = request.from.trim();
        let to = request.to.trim();
        if from.is_empty() || to.is_empty() || from == to {
            return Self::refuse(from, to, "sender and recipient must be two distinct, non-empty sessions".into());
        }
        if request.body.trim().is_empty() && request.subject.trim().is_empty() {
            return Self::refuse(from, to, "a message needs a subject or a body".into());
        }
        let mut events = Vec::new();
        let wanted = request.reply_to.as_deref().map(str::trim);
        let mut original = wanted.and_then(|w| g.messages.get(w).cloned());
        if let (Some(w), None) = (wanted, &original) {
            let matches: Vec<&Message> = if w.is_empty() { vec![] } else { g.messages.values().filter(|m| m.id.starts_with(w)).collect() };
            if matches.len() > 1 {
                let ids = matches.iter().map(|m| m.id.chars().take(12).collect::<String>()).collect::<Vec<_>>().join(", ");
                return Self::refuse(from, to, format!("replyTo '{w}' is ambiguous: {ids}; use a longer id"));
            }
            original = matches.first().map(|m| (*m).clone());
            if original.is_none() {
                return Self::refuse(from, to, format!("unknown replyTo '{w}'"));
            }
        }
        let limits = (self.limits)();
        let recipient = original.as_ref().map(|o| o.from.clone()).unwrap_or_else(|| to.to_string());
        let explicit = original.as_ref().map(|o| o.thread_id.clone()).or_else(|| threads::normalize(request.thread_id.as_deref()));
        let thread_id = match explicit {
            Some(t) => t,
            None => {
                let base = threads::pair(from, &recipient);
                let mut n = 1;
                loop {
                    let id = threads::rolled(&base, n);
                    if g.count_in(&id) < limits.max_per_thread {
                        break id;
                    }
                    n += 1;
                }
            }
        };
        let subject = if request.subject.trim().is_empty() {
            original.as_ref().map(|o| format!("Re: {}", o.subject)).unwrap_or_default()
        } else {
            request.subject.clone()
        };

        let in_thread = g.count_in(&thread_id);
        if in_thread >= limits.max_per_thread {
            let mut reason = format!(
                "conversation ceiling reached: thread '{thread_id}' has {in_thread} messages (limit {}); stop and report to the human",
                limits.max_per_thread
            );
            if original.is_some() {
                reason.push_str(". To continue, send a new message without replyTo (it starts a fresh thread)");
            }
            reason.push_str(". ");
            reason.push_str(&hint(KEY_MAX_PER_THREAD));
            events.push(MailEvent::CeilingReached { thread_id, limit: limits.max_per_thread });
            events.push(MailEvent::Refused { from: from.into(), to: recipient, reason: reason.clone() });
            return (MailResult::Refused(reason), events);
        }

        let now = (self.clock)();
        let exempt = automation || is_automation(from);
        if !exempt {
            let window = g.send_history.entry(from.to_string()).or_default();
            while window.front().is_some_and(|t| now - t > limits.window_ms) {
                window.pop_front();
            }
            if window.len() >= limits.per_sender_per_window {
                let reason = format!(
                    "rate limit: '{from}' already sent {} messages in the last {} min (limit {}); this message was NOT sent. {}",
                    window.len(),
                    limits.window_minutes(),
                    limits.per_sender_per_window,
                    rate_hint()
                );
                events.push(MailEvent::Refused { from: from.into(), to: recipient, reason: reason.clone() });
                return (MailResult::Refused(reason), events);
            }
            window.push_back(now);
        }

        let seq = g.next_seq(&thread_id);
        let message = Message {
            id: (self.ids)(),
            thread_id,
            seq,
            from: from.into(),
            to: recipient,
            subject,
            body: request.body.clone(),
            sent_at: now,
            state: MessageState::Queued,
            reply_to: original.as_ref().map(|o| o.id.clone()),
            checkpoint: request.checkpoint.clone().filter(|c| !c.is_empty()),
            needs_input: request.needs_input,
            delivered_at: None,
            read_at: None,
            answered_at: None,
            failure_reason: None,
            archived_at: None,
        };
        g.insert(message.clone());
        if let Some(o) = original {
            // Answering proves delivery and reading: record both receipts honestly.
            let answered = Message {
                state: MessageState::Answered,
                delivered_at: o.delivered_at.or(Some(now)),
                read_at: o.read_at.or(Some(now)),
                answered_at: Some(now),
                ..o
            };
            g.insert(answered.clone());
            events.push(MailEvent::Changed(answered));
        }
        self.persist(g);
        events.push(MailEvent::Sent(message.clone()));
        (MailResult::Sent(message), events)
    }

    /// One message per recipient in a single collecting thread, all or none. N recipients count as N sends.
    pub fn fan_out(&self, request: &MailFanOutRequest, automation: bool) -> FanOutResult {
        let (result, events) = {
            let mut g = self.inner.lock().unwrap();
            self.fan_out_locked(&mut g, request, automation)
        };
        self.emit(&events);
        result
    }

    fn fan_out_locked(&self, g: &mut Inner, request: &MailFanOutRequest, automation: bool) -> (FanOutResult, Vec<MailEvent>) {
        let limits = (self.limits)();
        let from = request.from.trim();
        let mut recipients: Vec<String> = Vec::new();
        for r in &request.recipients {
            let r = r.trim();
            if !r.is_empty() && !recipients.iter().any(|x| x == r) {
                recipients.push(r.to_string());
            }
        }
        if from.is_empty() || recipients.is_empty() {
            return (FanOutResult::Refused("fan-out needs a sender and at least one recipient".into()), vec![]);
        }
        if recipients.iter().any(|r| r == from) {
            return (FanOutResult::Refused(format!("a fan-out cannot address its own sender '{from}'")), vec![]);
        }
        if request.body.trim().is_empty() && request.subject.trim().is_empty() {
            return (FanOutResult::Refused("a message needs a subject or a body".into()), vec![]);
        }
        let mut events = Vec::new();
        let thread_id = threads::normalize(request.thread_id.as_deref()).unwrap_or_else(|| threads::fan_out(&(self.ids)()));
        let in_thread = g.count_in(&thread_id);
        if in_thread + recipients.len() > limits.max_per_thread {
            let reason = format!(
                "conversation ceiling reached: thread '{thread_id}' would hold {} messages (limit {}); stop and report to the human. {}",
                in_thread + recipients.len(),
                limits.max_per_thread,
                hint(KEY_MAX_PER_THREAD)
            );
            events.push(MailEvent::CeilingReached { thread_id, limit: limits.max_per_thread });
            return (FanOutResult::Refused(reason), events);
        }
        let now = (self.clock)();
        if !(automation || is_automation(from)) {
            let window = g.send_history.entry(from.to_string()).or_default();
            while window.front().is_some_and(|t| now - t > limits.window_ms) {
                window.pop_front();
            }
            if window.len().saturating_add(recipients.len()) > limits.per_sender_per_window {
                let reason = format!(
                    "rate limit: fan-out of {} from '{from}' would exceed {} in the last {} min; nothing was sent. {}",
                    recipients.len(),
                    limits.per_sender_per_window,
                    limits.window_minutes(),
                    rate_hint()
                );
                events.push(MailEvent::Refused { from: from.into(), to: recipients.join(","), reason: reason.clone() });
                return (FanOutResult::Refused(reason), events);
            }
            for _ in 0..recipients.len() {
                window.push_back(now);
            }
        }
        let mut created = Vec::new();
        for r in recipients {
            let seq = g.next_seq(&thread_id);
            let m = Message {
                id: (self.ids)(),
                thread_id: thread_id.clone(),
                seq,
                from: from.into(),
                to: r,
                subject: request.subject.clone(),
                body: request.body.clone(),
                sent_at: now,
                state: MessageState::Queued,
                reply_to: None,
                checkpoint: request.checkpoint.clone().filter(|c| !c.is_empty()),
                needs_input: request.needs_input,
                delivered_at: None,
                read_at: None,
                answered_at: None,
                failure_reason: None,
                archived_at: None,
            };
            g.insert(m.clone());
            created.push(m);
        }
        self.persist(g);
        events.extend(created.iter().cloned().map(MailEvent::Sent));
        (FanOutResult::Sent(created), events)
    }

    // ---- state transitions ----

    fn transition(&self, id: &str, f: impl FnOnce(&Message, i64) -> Option<Message>) -> Option<Message> {
        let (changed, result) = {
            let mut g = self.inner.lock().unwrap();
            let cur = g.messages.get(id)?.clone();
            match f(&cur, (self.clock)()) {
                Some(next) if next != cur => {
                    g.insert(next.clone());
                    self.persist(&g);
                    (Some(next.clone()), Some(next))
                }
                Some(same) => (None, Some(same)),
                None => (None, None),
            }
        };
        if let Some(m) = changed {
            self.emit(&[MailEvent::Changed(m)]);
        }
        result
    }

    /// A pre-delivery hook handled a QUEUED message: delivered and read at once, nobody woken.
    pub fn mark_handled(&self, id: &str) -> Option<Message> {
        self.transition(id, |m, now| {
            (m.state == MessageState::Queued).then(|| Message { state: MessageState::Read, delivered_at: Some(now), read_at: Some(now), ..m.clone() })
        })
    }

    /// DELIVERED -> READ. A QUEUED or UNDELIVERABLE message answers None; READ/ANSWERED answer themselves.
    pub fn mark_read(&self, id: &str) -> Option<Message> {
        self.transition(id, |m, now| match m.state {
            MessageState::Read | MessageState::Answered => Some(m.clone()),
            MessageState::Delivered => Some(Message { state: MessageState::Read, read_at: Some(now), ..m.clone() }),
            _ => None,
        })
    }

    /// The owner's read of a message (the inbox UI): any delivered or queued-to-human message becomes READ.
    pub fn mark_read_by_owner(&self, id: &str) -> Option<Message> {
        self.transition(id, |m, now| match m.state {
            MessageState::Read | MessageState::Answered | MessageState::Undeliverable => Some(m.clone()),
            _ => Some(Message { state: MessageState::Read, delivered_at: m.delivered_at.or(Some(now)), read_at: Some(now), ..m.clone() }),
        })
    }

    /// MAIL-OWNER-1: the owner's "Archive read": each given message to the owner that is read (or answered) leaves the
    /// Inbox. Unread mail, and mail not to the owner, is left as it is. Answers the messages that changed.
    pub fn archive_by_owner(&self, ids: &[String]) -> Vec<Message> {
        ids.iter()
            .filter_map(|id| {
                let before = self.message(id)?;
                let after = self.transition(id, |m, now| {
                    (m.to_owner() && m.archived_at.is_none() && m.read()).then(|| Message { archived_at: Some(now), ..m.clone() })
                })?;
                (after != before).then_some(after)
            })
            .collect()
    }

    /// Ids a pre-delivery hook is still deciding: no pump may deliver or fold them meanwhile.
    pub fn hold(&self, id: &str) {
        self.held.lock().unwrap().insert(id.to_string());
    }
    pub fn release(&self, id: &str) {
        self.held.lock().unwrap().remove(id);
    }

    /// Hands every QUEUED message for `recipient` to `delivery`, oldest first; stops at the first Retry so order is
    /// kept, records a Failed one as UNDELIVERABLE.
    pub fn deliver_queued(&self, recipient: &str, delivery: &dyn MailDelivery) {
        loop {
            let queued: Vec<Message> = {
                let held = self.held.lock().unwrap().clone();
                let g = self.inner.lock().unwrap();
                Self::by_time(g.all().filter(|m| m.to == recipient && m.state == MessageState::Queued && !held.contains(&m.id)).cloned().collect())
            };
            let Some(next) = queued.iter().find(|m| delivery.wakes(m)).cloned() else { return };
            let rest: Vec<Message> = queued.into_iter().filter(|m| m.id != next.id).collect();
            match delivery.deliver(&next, &rest) {
                DeliveryOutcome::Delivered { also_delivered } => {
                    let events = {
                        let mut g = self.inner.lock().unwrap();
                        let mut ev = Vec::new();
                        for id in std::iter::once(&next.id).chain(also_delivered.iter()) {
                            ev.extend(self.mark_state_locked(&mut g, id, MessageState::Delivered, None));
                        }
                        ev
                    };
                    self.emit(&events);
                }
                DeliveryOutcome::Retry(_) => return,
                DeliveryOutcome::Failed(reason) => {
                    let events = {
                        let mut g = self.inner.lock().unwrap();
                        self.mark_state_locked(&mut g, &next.id, MessageState::Undeliverable, Some(reason))
                    };
                    self.emit(&events);
                }
            }
        }
    }

    fn mark_state_locked(&self, g: &mut Inner, id: &str, state: MessageState, reason: Option<String>) -> Vec<MailEvent> {
        let Some(cur) = g.messages.get(id).cloned() else { return vec![] };
        if cur.state != MessageState::Queued {
            return vec![];
        }
        // MAIL-OWNER-1: mail to the human user never fails; it is in the owner's inbox, so it is delivered.
        let (state, reason) = if state == MessageState::Undeliverable && cur.to_owner() { (MessageState::Delivered, None) } else { (state, reason) };
        let next = match state {
            MessageState::Delivered => Message { state, delivered_at: Some((self.clock)()), ..cur },
            _ => Message { state, failure_reason: reason, ..cur },
        };
        g.insert(next.clone());
        self.persist(g);
        vec![MailEvent::Changed(next)]
    }

    /// Drops the still-QUEUED messages of `thread_id` to `to` that match: they become UNDELIVERABLE with `reason`.
    pub fn drop_queued(&self, thread_id: &str, to: &str, reason: &str, matches: impl Fn(&Message) -> bool) -> usize {
        let events = {
            let mut g = self.inner.lock().unwrap();
            let ids: Vec<String> = g
                .all()
                .filter(|m| m.thread_id == thread_id && m.to == to && m.state == MessageState::Queued && matches(m))
                .map(|m| m.id.clone())
                .collect();
            let mut ev = Vec::new();
            for id in ids {
                ev.extend(self.mark_state_locked(&mut g, &id, MessageState::Undeliverable, Some(reason.to_string())));
            }
            ev
        };
        self.emit(&events);
        events.len()
    }

    /// Hands the still-QUEUED mail of `recipients` (not from system, not held) over inline: DELIVERED, oldest first.
    pub fn claim_queued(&self, recipients: &[&str]) -> Vec<Message> {
        let held = self.held.lock().unwrap().clone();
        let (events, claimed) = {
            let mut g = self.inner.lock().unwrap();
            let list = Self::by_time(
                g.all()
                    .filter(|m| {
                        recipients.contains(&m.to.as_str()) && m.state == MessageState::Queued && !held.contains(&m.id) && !m.from.eq_ignore_ascii_case("system")
                    })
                    .cloned()
                    .collect(),
            );
            let mut ev = Vec::new();
            for m in &list {
                ev.extend(self.mark_state_locked(&mut g, &m.id, MessageState::Delivered, None));
            }
            let claimed: Vec<Message> = list.iter().filter_map(|m| g.messages.get(&m.id).cloned()).collect();
            (ev, claimed)
        };
        self.emit(&events);
        claimed
    }

    fn persist(&self, g: &Inner) {
        let all: Vec<Message> = g.all().cloned().collect();
        if let Err(e) = self.store.save_all(&all) {
            eprintln!("[infantry] mail persist failed: {e}");
        }
    }

    fn emit(&self, events: &[MailEvent]) {
        let sink = self.sink.read().unwrap().clone();
        if let Some(s) = sink {
            for e in events {
                s(e);
            }
        }
    }
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;
    use crate::mail::store::MemoryMailStore;
    use std::sync::atomic::{AtomicI64, Ordering};

    pub(crate) fn mailbox(limits: MailLimits) -> (Arc<Mailbox>, Arc<AtomicI64>) {
        let t = Arc::new(AtomicI64::new(1_000));
        let n = Arc::new(AtomicI64::new(0));
        let tc = t.clone();
        let mb = Mailbox::with(
            Arc::new(MemoryMailStore::default()),
            Arc::new(move || limits),
            Arc::new(move || tc.load(Ordering::SeqCst)),
            Arc::new(move || format!("m{}", n.fetch_add(1, Ordering::SeqCst) + 1)),
        );
        (Arc::new(mb), t)
    }

    fn req(from: &str, to: &str, body: &str) -> MailSendRequest {
        MailSendRequest { from: from.into(), to: to.into(), body: body.into(), ..Default::default() }
    }

    #[test]
    fn mail_to_the_owner_never_fails_and_old_failed_rows_load_as_delivered() {
        let store = Arc::new(MemoryMailStore::default());
        let old = Message {
            id: "k1".into(), thread_id: "pair:owner|w".into(), seq: 1, from: "w".into(), to: "owner".into(), subject: "s".into(), body: "b".into(),
            sent_at: 5, state: MessageState::Undeliverable, reply_to: None, checkpoint: None, needs_input: false, delivered_at: None, read_at: None,
            answered_at: None, failure_reason: Some("no owner agent in this project".into()), archived_at: None,
        };
        let other = Message { id: "k2".into(), to: "pm".into(), ..old.clone() };
        store.save_all(&[old, other]).unwrap();
        let mb = Mailbox::new(store);
        mb.load();
        let k1 = mb.message("k1").unwrap();
        assert_eq!((k1.state, k1.failure_reason, k1.delivered_at), (MessageState::Delivered, None, Some(5)));
        assert_eq!(mb.message("k2").unwrap().state, MessageState::Undeliverable, "only the owner's mail is mended");

        let (mb, _) = mailbox(MailLimits::default());
        mb.send(&req("w", "owner", "hi"), false);
        mb.deliver_queued("owner", &Fake(Default::default(), "gone"));
        assert_eq!(mb.message("m1").unwrap().state, MessageState::Delivered);
        assert_eq!(mb.drop_queued("pair:owner|w", "owner", "x", |_| true), 0, "nothing queued is left to fail");
    }

    #[test]
    fn archive_by_owner_takes_only_read_owner_mail() {
        let (mb, _) = mailbox(MailLimits::default());
        mb.send(&req("w", "owner", "read one"), false);
        mb.send(&req("w", "owner", "unread one"), false);
        mb.send(&req("owner", "w", "mine"), false);
        mb.mark_read_by_owner("m1");
        let changed = mb.archive_by_owner(&["m1".into(), "m2".into(), "m3".into(), "nope".into()]);
        assert_eq!(changed.iter().map(|m| m.id.as_str()).collect::<Vec<_>>(), ["m1"]);
        assert!(mb.message("m1").unwrap().archived_at.is_some());
        assert!(mb.message("m2").unwrap().archived_at.is_none());
        assert!(mb.archive_by_owner(&["m1".into()]).is_empty(), "archiving twice changes nothing");
        let wire = serde_json::to_string(&mb.message("m2").unwrap()).unwrap();
        assert!(!wire.contains("archivedAt"), "unset stays off the wire: {wire}");
    }

    #[test]
    fn reply_answers_and_inherits_thread() {
        let (mb, _) = mailbox(MailLimits::default());
        let MailResult::Sent(q) = mb.send(&MailSendRequest { subject: "Q".into(), needs_input: true, ..req("a", "b", "?") }, false) else { panic!() };
        assert_eq!(q.thread_id, "pair:a|b");
        assert_eq!(mb.waiting().len(), 1);
        let MailResult::Sent(r) = mb.send(&MailSendRequest { reply_to: Some("m1".into()), ..req("b", "ignored", "yes") }, false) else { panic!() };
        assert_eq!(r.to, "a");
        assert_eq!(r.subject, "Re: Q");
        assert_eq!(r.seq, 2);
        assert_eq!(mb.message("m1").unwrap().state, MessageState::Answered);
        assert!(mb.waiting().is_empty());
    }

    #[test]
    fn rate_limit_and_ceiling_refuse_without_storing() {
        let (mb, _) = mailbox(MailLimits { per_sender_per_window: 2, window_ms: 60_000, max_per_thread: 60 });
        assert!(matches!(mb.send(&req("a", "b", "1"), false), MailResult::Sent(_)));
        assert!(matches!(mb.send(&req("a", "c", "2"), false), MailResult::Sent(_)));
        let MailResult::Refused(why) = mb.send(&req("a", "d", "3"), false) else { panic!() };
        assert!(why.starts_with("rate limit: 'a' already sent 2"));
        assert!(matches!(mb.send(&req("system", "d", "x"), false), MailResult::Sent(_)));
        assert_eq!(mb.all().len(), 3);

        let (mb, _) = mailbox(MailLimits { per_sender_per_window: 100, window_ms: 60_000, max_per_thread: 2 });
        mb.send(&req("a", "b", "1"), false);
        mb.send(&req("b", "a", "2"), false);
        // A new message on a full pair thread rolls over; a reply on the full thread is refused.
        let MailResult::Sent(m) = mb.send(&req("a", "b", "3"), false) else { panic!() };
        assert_eq!(m.thread_id, "pair:a|b:2");
        let MailResult::Refused(why) = mb.send(&MailSendRequest { reply_to: Some("m1".into()), ..req("b", "a", "r") }, false) else { panic!() };
        assert!(why.contains("conversation ceiling reached"));
    }

    struct Fake(std::cell::RefCell<Vec<String>>, &'static str);
    impl MailDelivery for Fake {
        fn deliver(&self, m: &Message, _rest: &[Message]) -> DeliveryOutcome {
            self.0.borrow_mut().push(m.id.clone());
            match self.1 {
                "ok" => DeliveryOutcome::Delivered { also_delivered: vec![] },
                "busy" => DeliveryOutcome::Retry("busy".into()),
                _ => DeliveryOutcome::Failed("gone".into()),
            }
        }
    }

    #[test]
    fn delivery_outcomes() {
        let (mb, _) = mailbox(MailLimits::default());
        mb.send(&req("a", "b", "1"), false);
        mb.send(&req("a", "b", "2"), false);
        let busy = Fake(Default::default(), "busy");
        mb.deliver_queued("b", &busy);
        assert_eq!(busy.0.borrow().len(), 1);
        assert_eq!(mb.pending_count("b"), 2);
        mb.deliver_queued("b", &Fake(Default::default(), "ok"));
        assert_eq!(mb.pending_count("b"), 0);
        assert!(mb.mark_read("m1").is_some());
        mb.send(&req("a", "c", "3"), false);
        mb.deliver_queued("c", &Fake(Default::default(), "fail"));
        let m = mb.message("m3").unwrap();
        assert_eq!(m.state, MessageState::Undeliverable);
        assert_eq!(m.failure_reason.as_deref(), Some("gone"));
        assert!(mb.mark_read("m3").is_none());
    }
}
