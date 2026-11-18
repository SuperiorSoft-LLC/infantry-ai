// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Sessions: the lifecycle the v1 sessions frames drive (`prompt`, `interrupt`, `kill`, `session_delete`, `sessions`),
//! the frames it pushes, and the store. Vendor-neutral: a provider is a [`Harness`] behind the trait.
//!
//! Lock order: the session list, then one session, then the record cache. Nothing takes the list while holding a session.

use crate::harness::{EventSink, Harness, HarnessEvent, HarnessSession, OpenRequest, Turn, TurnEnd};
use crate::board::Board;
use crate::held;
use crate::store::JsonSessionStore;
use crate::wire;
use infantry_protocol::session::{AgentWatch, KillOutcome, QueuedTurn, SessionRecord, SessionState};
use infantry_protocol::agents::AgentListRequest;
use infantry_protocol::Envelope;
use serde::Deserialize;
use serde_json::json;
use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::sync::broadcast;

/// Frames buffered per connection before a slow client starts missing them.
pub const BROADCAST_CAPACITY: usize = 4096;
/// After a kill the run activity settles to `idle` on the next watch tick (Kotlin's `AgentWatchdog`), not at once.
pub const KILL_SETTLE_MS: u64 = 1_500;
/// `limits.chat.queueMax` default (CHAT-QUEUE-1): messages a session holds while a turn runs.
pub const QUEUE_MAX: usize = 20;
/// `limits.chat.sentIdsKept` default (CHAT-SENDING-1): client message ids remembered daemon-wide.
pub const SENT_IDS_KEPT: usize = 500;
/// Frames kept per session for `replay_request` (a reconnecting or late client rebuilds the transcript from them).
pub const REPLAY_KEPT: usize = 5_000;

pub fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

pub struct Config {
    pub state_dir: PathBuf,
    /// Where a session runs when its `new` names no folder (Kotlin: the daemon's working directory).
    pub default_cwd: PathBuf,
    pub kill_settle_ms: u64,
    /// `limits.chat.queueMax`.
    pub queue_max: usize,
    /// `limits.chat.sentIdsKept`.
    pub sent_ids_kept: usize,
    /// Frames kept per session for `replay_request`.
    pub replay_kept: usize,
}

impl Config {
    /// The defaults Kotlin ships with.
    pub fn new(state_dir: PathBuf, default_cwd: PathBuf) -> Config {
        Config { state_dir, default_cwd, kill_settle_ms: KILL_SETTLE_MS, queue_max: QUEUE_MAX, sent_ids_kept: SENT_IDS_KEPT, replay_kept: REPLAY_KEPT }
    }
}

/// What a `new` frame may carry (JSON data, every field optional).
#[derive(Debug, Default, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewOpts {
    pub cwd: Option<String>,
    pub model: Option<String>,
    pub effort: Option<String>,
    pub role: Option<String>,
    /// A display name; without a role it becomes the role (lowercased), as the app's embedded runner does.
    pub name: Option<String>,
    /// A first turn to send at once (the app's `spawnWith({prompt})`); without it the session waits at rest.
    pub prompt: Option<String>,
}

/// Per connection: a bare `new` waiting for its first prompt, or the session a JSON `new` bound (BIND-AT-CREATION).
#[derive(Default)]
pub struct Conn {
    pub pending_new: Option<NewOpts>,
    /// The session the last JSON `new` created: a session-less `prompt` on this connection goes into it.
    pub active: Option<String>,
}

struct Entry {
    rec: SessionRecord,
    seq: i64,
    hs: Option<Box<dyn HarnessSession>>,
    turn_active: bool,
    turn_started: i64,
    /// Bumped by each kill and turn: a delayed settle that sees another value is stale.
    gen: u64,
    removed: bool,
    /// (sequence, encoded frame) of the last [Config::replay_kept] frames.
    history: std::collections::VecDeque<(i64, String)>,
}

impl Entry {
    fn new(rec: SessionRecord, turn_active: bool, turn_started: i64) -> Entry {
        Entry { rec, seq: 0, hs: None, turn_active, turn_started, gen: 0, removed: false, history: Default::default() }
    }
}

pub struct Daemon {
    pub harness: Arc<dyn Harness>,
    pub cfg: Config,
    pub instance: String,
    out: broadcast::Sender<String>,
    /// (id, session): the id beside it so a lookup locks no session.
    list: Mutex<Vec<(String, Arc<Mutex<Entry>>)>>,
    cache: Mutex<Vec<SessionRecord>>,
    dirty: AtomicBool,
    store: Mutex<JsonSessionStore>,
    /// CHAT-SENDING-1: `<session>\n<send:id>` of the prompts taken, oldest first.
    sent: Mutex<std::collections::VecDeque<String>>,
    /// Projects and agent profiles, read-only from the state dir's db (RUST-MIGRATE-1).
    board: Board,
    /// The app this state dir was migrated from, when it was (RUST-MIGRATE-1).
    source: Option<held::Source>,
    /// Session id -> the app running it now: listed as `heldBy`, a turn into it refused.
    held: Mutex<BTreeMap<String, String>>,
}

fn encode(ty: &str, data: String, session: Option<&str>, seq: Option<i64>) -> String {
    encode_req(ty, data, session, seq, None)
}

fn encode_req(ty: &str, data: String, session: Option<&str>, seq: Option<i64>, request: Option<&str>) -> String {
    let mut e = Envelope::new(ty, data);
    e.session_id = session.map(String::from);
    e.sequence = seq;
    e.request_id = request.map(String::from);
    serde_json::to_string(&e).unwrap_or_default()
}

/// Why a frame was refused: a plain reason (an `error` on the frame's session), or the typed `run_in_flight` (session-less).
enum Refusal {
    Plain(String),
    InFlight(String),
}

impl From<String> for Refusal {
    fn from(s: String) -> Self {
        Refusal::Plain(s)
    }
}
impl From<&str> for Refusal {
    fn from(s: &str) -> Self {
        Refusal::Plain(s.into())
    }
}

impl Daemon {
    /// Opens the store in the state dir; sessions that were running when the daemon stopped come back at rest.
    pub fn new(harness: Arc<dyn Harness>, cfg: Config) -> Result<Arc<Daemon>, String> {
        let mut store = JsonSessionStore::new(&cfg.state_dir);
        let loaded = match store.load() {
            Ok(l) => l,
            Err(e) => {
                eprintln!("[infantry] session store unreadable, starting with no sessions: {e}");
                Vec::new()
            }
        };
        let (out, _) = broadcast::channel(BROADCAST_CAPACITY);
        let mut list = Vec::new();
        let mut cache = Vec::new();
        for mut rec in loaded {
            if rec.is_busy() {
                rec.state = if rec.provider_session_id.is_some() { SessionState::Idle } else { SessionState::Exited };
                rec.last_action = Some(format!("state {}", state_word(rec.state)));
            }
            rec.is_process_alive = false;
            rec.child_pid = None;
            cache.push(rec.clone());
            list.push((rec.id.clone(), Arc::new(Mutex::new(Entry::new(rec, false, 0)))));
        }
        let cfg_dir = cfg.state_dir.clone();
        let d = Arc::new(Daemon {
            harness,
            cfg,
            instance: uuid::Uuid::new_v4().to_string(),
            out,
            list: Mutex::new(list),
            cache: Mutex::new(cache),
            dirty: AtomicBool::new(false),
            store: Mutex::new(store),
            sent: Mutex::new(Default::default()),
            board: Board::new(&cfg_dir),
            source: held::Source::of(&cfg_dir),
            held: Mutex::new(BTreeMap::new()),
        });
        d.refresh_held();
        Ok(d)
    }

    /// Re-reads which sessions the app this dir was migrated from is running (no-op for a dir that is not a copy).
    /// When that changed, the records carry the new `heldBy` and every client gets a fresh `sessions`.
    pub fn refresh_held(&self) -> bool {
        let Some(src) = &self.source else { return false };
        let Some(now) = src.held() else { return false };
        {
            let mut held = self.held.lock().unwrap();
            if *held == now {
                return false;
            }
            *held = now.clone();
        }
        let entries: Vec<_> = self.list.lock().unwrap().iter().map(|(_, e)| e.clone()).collect();
        for e in entries {
            let mut e = e.lock().unwrap();
            e.rec.held_by = now.get(&e.rec.id).cloned();
        }
        for r in self.cache.lock().unwrap().iter_mut() {
            r.held_by = now.get(&r.id).cloned();
        }
        self.broadcast(wire::SESSIONS, self.sessions_json());
        true
    }

    /// `Err` with the refusal when another app is running this session.
    fn not_held(&self, verb: &str, sid: &str) -> Result<(), String> {
        match self.held.lock().unwrap().get(sid) {
            Some(app) => Err(format!("{verb} refused: session {sid} is live in {app}; stop it there first (one session never runs in two apps)")),
            None => Ok(()),
        }
    }

    pub fn held_count(&self) -> usize {
        self.held.lock().unwrap().len()
    }

    pub fn subscribe(&self) -> broadcast::Receiver<String> {
        self.out.subscribe()
    }

    /// The connection burst after the handshake: ready, policy, sessions, worktrees.
    pub fn hello(&self, build: &str) -> Vec<String> {
        vec![
            encode(wire::READY, wire::ready(self.harness.name(), &self.instance, build), None, None),
            encode(wire::POLICY, wire::policy(), None, None),
            encode(wire::SESSIONS, self.sessions_json(), None, None),
            encode(wire::WORKTREES, wire::array(vec![]), None, None),
        ]
    }

    pub fn session_count(&self) -> usize {
        self.cache.lock().unwrap().len()
    }

    fn sessions_json(&self) -> String {
        serde_json::to_string(&*self.cache.lock().unwrap()).unwrap_or_else(|_| "[]".into())
    }

    /// Writes the store when something changed since the last flush.
    pub fn flush(&self) {
        if !self.dirty.swap(false, Ordering::SeqCst) {
            return;
        }
        let mut snapshot = self.cache.lock().unwrap().clone();
        // `heldBy` is computed per listing, never stored.
        snapshot.iter_mut().for_each(|r| r.held_by = None);
        if let Err(e) = self.store.lock().unwrap().save(&snapshot) {
            eprintln!("[infantry] could not save sessions: {e}");
        }
    }

    fn find(&self, id: &str) -> Option<Arc<Mutex<Entry>>> {
        let list = self.list.lock().unwrap();
        list.iter().find(|(i, _)| i == id).map(|(_, e)| e.clone())
    }

    fn emit(&self, e: &mut Entry, ty: &str, data: String) {
        e.seq += 1;
        let f = encode(ty, data, Some(&e.rec.id), Some(e.seq));
        e.history.push_back((e.seq, f.clone()));
        while e.history.len() > self.cfg.replay_kept.max(1) {
            e.history.pop_front();
        }
        let _ = self.out.send(f);
    }

    /// `replay_request` (`{lastSequence}`): the session's kept frames after that sequence, a `replay_gap` first when some
    /// were dropped. Answers this connection only.
    fn replay(&self, sid: &str, data: &str) -> Result<Vec<String>, String> {
        let entry = self.find(sid).ok_or_else(|| format!("replay_request refused: unknown session {sid}"))?;
        let last = serde_json::from_str::<serde_json::Value>(data).ok().and_then(|v| v["lastSequence"].as_i64()).unwrap_or(0);
        let e = entry.lock().unwrap();
        let mut out = Vec::new();
        if let (Some(first), Some(latest)) = (e.history.front().map(|h| h.0), e.history.back().map(|h| h.0)) {
            if first > last + 1 {
                let gap = json!({
                    "requestedAfterSequence": last, "oldestAvailableSequence": first,
                    "latestAvailableSequence": latest, "evictedCount": first - last - 1,
                });
                out.push(encode(wire::REPLAY_GAP, gap.to_string(), Some(sid), None));
            }
        }
        out.extend(e.history.iter().filter(|(s, _)| *s > last).map(|(_, f)| f.clone()));
        Ok(out)
    }

    fn broadcast(&self, ty: &str, data: String) {
        let _ = self.out.send(encode(ty, data, None, None));
    }

    /// Pushes the record as a `session` frame and stores it.
    fn push(&self, e: &mut Entry) {
        let now = now_ms();
        e.rec.last_heartbeat = now;
        e.rec.last_event_at = Some(now);
        let data = serde_json::to_string(&e.rec).unwrap_or_default();
        self.emit(e, wire::SESSION, data);
        let mut cache = self.cache.lock().unwrap();
        match cache.iter_mut().find(|r| r.id == e.rec.id) {
            Some(r) => *r = e.rec.clone(),
            None => cache.push(e.rec.clone()),
        }
        self.dirty.store(true, Ordering::SeqCst);
    }

    fn set_state(e: &mut Entry, s: SessionState) {
        e.rec.state = s;
        e.rec.last_action = Some(format!("state {}", state_word(s)));
    }

    /// A list frame from the board, or an `error` when the db could not be read.
    fn board_reply(&self, ty: &str, req: Option<&str>, r: Result<serde_json::Result<String>, String>) -> Vec<String> {
        match r {
            Ok(Ok(json)) => vec![encode_req(ty, json, None, None, req)],
            Ok(Err(e)) => vec![encode_req(wire::ERROR, format!("{ty}: {e}"), None, None, req)],
            Err(e) => vec![encode_req(wire::ERROR, format!("{ty}: {e}"), None, None, req)],
        }
    }

    /// One inbound frame; returns the replies for this connection only (session frames go to every connection).
    pub fn handle(self: &Arc<Self>, env: Envelope, conn: &mut Conn) -> Vec<String> {
        let sid = env.session_id.clone().filter(|s| !s.is_empty());
        let req = env.request_id.clone();
        // CHAT-SENDING-1: a `prompt` naming its session with a `send:` id is acknowledged; a resend of a taken id is not run again.
        let send_key = match (&sid, &req) {
            (Some(s), Some(r)) if env.r#type == wire::PROMPT && r.starts_with(wire::CLIENT_SEND_PREFIX) => Some(format!("{s}\n{r}")),
            _ => None,
        };
        if let Some(k) = &send_key {
            if !self.claim_send(k) {
                return vec![encode_req(wire::PROMPT_ACK, "duplicate".into(), sid.as_deref(), None, req.as_deref())];
            }
        }
        let r: Result<(), Refusal> = match env.r#type.as_str() {
            wire::SESSIONS => return vec![encode(wire::SESSIONS, self.sessions_json(), None, None)],
            wire::NEW => {
                conn.active = None;
                conn.pending_new = None;
                if env.data.trim_start().starts_with('{') {
                    // BIND-AT-CREATION (Kotlin): an options object creates the session now, at rest and addressable.
                    match serde_json::from_str::<NewOpts>(&env.data) {
                        Ok(o) => self.bind_new(o, conn).map_err(Refusal::from),
                        Err(e) => Err(format!("new refused: bad options ({e})").into()),
                    }
                } else {
                    Ok(())
                }
            }
            wire::REPLAY_REQUEST => {
                return match self.replay(sid.as_deref().unwrap_or(""), &env.data) {
                    Ok(frames) => frames,
                    Err(msg) => vec![encode_req(wire::ERROR, msg, sid.as_deref(), None, req.as_deref())],
                };
            }
            // Projects and profiles from the state dir's db, read-only (RUST-MIGRATE-1). No tickets here yet.
            wire::AGENT_LIST => {
                let p = serde_json::from_str::<AgentListRequest>(&env.data).ok().and_then(|r| r.project_id);
                return self.board_reply(wire::AGENTS, req.as_deref(), self.board.agents(p.as_deref()).map(|a| serde_json::to_string(&a)));
            }
            wire::PROJECT_LIST => return self.board_reply(wire::PROJECTS, req.as_deref(), self.board.projects().map(|p| serde_json::to_string(&p))),
            wire::PROJECT_GET => {
                let id = env.data.trim();
                return match self.board.project(id) {
                    Ok(Some(p)) => vec![encode_req(wire::PROJECT, serde_json::to_string(&p).unwrap_or_default(), None, None, req.as_deref())],
                    Ok(None) => vec![encode_req(wire::ERROR, format!("project_get refused: no project {id}"), None, None, req.as_deref())],
                    Err(e) => vec![encode_req(wire::ERROR, format!("project_get failed: {e}"), None, None, req.as_deref())],
                };
            }
            wire::TICKET_LIST => return vec![encode(wire::TICKETS, "[]".into(), None, None)],
            wire::PROMPT => match (&sid, conn.active.clone().filter(|a| self.find(a).is_some())) {
                (None, Some(active)) => self.prompt_into(&active, env.data),
                (None, None) => self.prompt_new(conn.pending_new.take().unwrap_or_default(), env.data).map_err(Refusal::from),
                (Some(s), _) => self.not_held("prompt", s).map_err(Refusal::from).and_then(|_| self.prompt_into(s, env.data)),
            },
            wire::QUEUE_ADD => {
                let s = sid.as_deref().unwrap_or("");
                self.not_held("queue_add", s).and_then(|_| self.queue_add(s, env.data)).map_err(Refusal::from)
            }
            wire::QUEUE_REMOVE => self.queue_remove(sid.as_deref().unwrap_or(""), env.data.trim()).map_err(Refusal::from),
            wire::QUEUE_FORCE => {
                let s = sid.as_deref().unwrap_or("");
                self.not_held("queue_force", s).and_then(|_| self.queue_force(s, env.data.trim())).map_err(Refusal::from)
            }
            wire::INTERRUPT => self.interrupt(sid.as_deref().unwrap_or(env.data.trim())).map_err(Refusal::from),
            wire::KILL => {
                let mut words = env.data.split_whitespace();
                let first = words.next().unwrap_or("").to_string();
                let (target, rest): (String, Vec<&str>) = match &sid {
                    Some(s) => (s.clone(), std::iter::once(first.as_str()).chain(words).collect()),
                    None => (first.clone(), words.collect()),
                };
                self.kill(&target, rest.contains(&"force")).map_err(Refusal::from)
            }
            wire::SESSION_DELETE => self.delete(sid.as_deref().unwrap_or(env.data.trim())).map_err(Refusal::from),
            other => Err(format!("{other} refused: this daemon does not serve that frame yet").into()),
        };
        match r {
            Ok(()) => match send_key {
                Some(_) => vec![encode_req(wire::PROMPT_ACK, "accepted".into(), sid.as_deref(), None, req.as_deref())],
                None => Vec::new(),
            },
            Err(refusal) => {
                // A refused prompt is forgotten, so a retry with the same id runs.
                if let Some(k) = &send_key {
                    self.release_send(k);
                }
                match refusal {
                    Refusal::Plain(msg) => vec![encode_req(wire::ERROR, msg, sid.as_deref(), None, req.as_deref())],
                    Refusal::InFlight(msg) => vec![encode_req(wire::ERROR, msg, None, None, req.as_deref())],
                }
            }
        }
    }

    fn claim_send(&self, key: &str) -> bool {
        let mut ids = self.sent.lock().unwrap();
        if ids.iter().any(|k| k == key) {
            return false;
        }
        ids.push_back(key.to_string());
        while ids.len() > self.cfg.sent_ids_kept.max(1) {
            ids.pop_front();
        }
        true
    }

    fn release_send(&self, key: &str) {
        self.sent.lock().unwrap().retain(|k| k != key);
    }

    /// A new session's record (not yet listed).
    fn new_record(&self, opts: &NewOpts, state: &str, verb: &str) -> Result<SessionRecord, String> {
        let cwd = opts.cwd.as_deref().filter(|c| !c.trim().is_empty()).map(PathBuf::from).unwrap_or_else(|| self.cfg.default_cwd.clone());
        if !cwd.is_dir() {
            return Err(format!("{verb} refused: {} is not a folder", cwd.display()));
        }
        let now = now_ms();
        let mut rec: SessionRecord = serde_json::from_value(json!({
            "id": uuid::Uuid::new_v4().to_string(), "provider": self.harness.name(), "workingDir": cwd.to_string_lossy(),
            "spawnedAt": now, "lastHeartbeat": now, "state": state,
        }))
        .map_err(|e| e.to_string())?;
        let some = |v: &Option<String>| v.clone().filter(|m| !m.trim().is_empty());
        rec.model = some(&opts.model);
        rec.effort = some(&opts.effort);
        rec.role = some(&opts.role).or_else(|| some(&opts.name).map(|n| n.trim().to_lowercase()));
        Ok(rec)
    }

    /// A JSON `new`: the session exists now, at rest with no run (`neverRun`); its first prompt is its first run.
    fn bind_new(self: &Arc<Self>, opts: NewOpts, conn: &mut Conn) -> Result<(), String> {
        let mut rec = self.new_record(&opts, "IDLE", "new")?;
        let id = rec.id.clone();
        rec.last_action = Some("state idle".into());
        let entry = Arc::new(Mutex::new(Entry::new(rec, false, 0)));
        self.list.lock().unwrap().push((id.clone(), entry.clone()));
        {
            let mut e = entry.lock().unwrap();
            self.push(&mut e);
            self.emit(&mut e, wire::STATUS, wire::status("IDLE"));
        }
        conn.active = Some(id.clone());
        match opts.prompt.filter(|p| !p.trim().is_empty()) {
            Some(p) => match self.prompt_into(&id, p) {
                Ok(()) => Ok(()),
                Err(Refusal::Plain(m) | Refusal::InFlight(m)) => Err(m),
            },
            None => Ok(()),
        }
    }

    fn prompt_new(self: &Arc<Self>, opts: NewOpts, text: String) -> Result<(), String> {
        let rec = self.new_record(&opts, "BUSY", "prompt")?;
        let (id, now) = (rec.id.clone(), rec.spawned_at);
        let entry = Arc::new(Mutex::new(Entry::new(rec, true, now)));
        self.list.lock().unwrap().push((id.clone(), entry.clone()));
        let mut e = entry.lock().unwrap();
        Self::set_state(&mut e, SessionState::Busy);
        self.push(&mut e);
        self.emit(&mut e, wire::STATUS, wire::status_run("BUSY", "thinking", now, now));
        self.emit(&mut e, wire::TURN, text.clone());
        self.run(&mut e, text);
        Ok(())
    }

    fn prompt_into(self: &Arc<Self>, sid: &str, text: String) -> Result<(), Refusal> {
        let entry = self.find(sid).ok_or("prompt refused: no such session")?;
        let mut e = entry.lock().unwrap();
        if e.turn_active {
            // Kotlin's typed refusal (1.96.0): nothing ran; the client queues it (`queue_add`) or sends it again later.
            return Err(Refusal::InFlight(format!("{} {sid}: session {sid} already has a run in flight", wire::RUN_IN_FLIGHT)));
        }
        self.turn_into(&mut e, text);
        Ok(())
    }

    /// CHAT-QUEUE-1 `queue_add`: held at the end of `session.queued`; a session at rest takes it as a turn now.
    fn queue_add(self: &Arc<Self>, sid: &str, text: String) -> Result<(), String> {
        let entry = self.find(sid).ok_or_else(|| format!("queue_add refused: unknown session {sid}"))?;
        let mut e = entry.lock().unwrap();
        if text.trim().is_empty() {
            return Err("queue_add refused: nothing to queue".into());
        }
        if !e.turn_active {
            self.turn_into(&mut e, text);
            return Ok(());
        }
        if e.rec.queued.len() >= self.cfg.queue_max {
            return Err(format!("queue_add refused: the queue of {sid} is full ({} messages)", self.cfg.queue_max));
        }
        let id = format!("q-{}", &uuid::Uuid::new_v4().to_string()[..8]);
        e.rec.queued.push(QueuedTurn { id, text, at: now_ms() });
        self.push(&mut e);
        Ok(())
    }

    fn queue_remove(&self, sid: &str, id: &str) -> Result<(), String> {
        let entry = self.find(sid).ok_or_else(|| format!("queue_remove refused: unknown session {sid}"))?;
        let mut e = entry.lock().unwrap();
        let before = e.rec.queued.len();
        e.rec.queued.retain(|q| q.id != id);
        if e.rec.queued.len() == before {
            return Err(format!("queue_remove refused: no queued message {id} on {sid}"));
        }
        self.push(&mut e);
        Ok(())
    }

    /// `queue_force`: at rest it is sent as the turn now; mid-turn it needs a harness that takes input mid-turn (none here yet).
    fn queue_force(self: &Arc<Self>, sid: &str, id: &str) -> Result<(), String> {
        let entry = self.find(sid).ok_or_else(|| format!("queue_force refused: unknown session {sid}"))?;
        let mut e = entry.lock().unwrap();
        let i = e.rec.queued.iter().position(|q| q.id == id).ok_or_else(|| format!("queue_force refused: no queued message {id} on {sid}"))?;
        if e.turn_active {
            return Err(format!(
                "queue_force refused: the {} harness cannot take a message mid-turn; it stays queued for the next turn",
                self.harness.name()
            ));
        }
        let item = e.rec.queued.remove(i);
        self.turn_into(&mut e, item.text);
        Ok(())
    }

    /// When the session is at rest, sends its oldest held message as the next turn (one per turn end), after the current frames.
    fn drain_later(self: &Arc<Self>, sid: &str) {
        let (d, sid) = (self.clone(), sid.to_string());
        tokio::spawn(async move {
            let Some(entry) = d.find(&sid) else { return };
            let mut e = entry.lock().unwrap();
            if e.removed || e.turn_active || e.rec.state == SessionState::Exited || e.rec.queued.is_empty() {
                return;
            }
            let head = e.rec.queued.remove(0);
            d.turn_into(&mut e, head.text);
        });
    }

    /// A turn into an existing session at rest.
    fn turn_into(self: &Arc<Self>, e: &mut Entry, text: String) {
        let now = now_ms();
        e.rec.last_turn_at = Some(now);
        // A turn into a session whose process is gone resumes it (a fresh process continues the provider session).
        if e.rec.run_count > 0 && !e.hs.as_ref().is_some_and(|h| h.process_alive()) {
            e.rec.resume_count += 1;
        }
        self.push(e);
        let st = state_word(e.rec.state).to_ascii_uppercase();
        self.emit(e, wire::STATUS, wire::status(&st));
        self.emit(e, wire::TURN, text.clone());
        e.turn_active = true;
        e.turn_started = now;
        e.gen += 1;
        self.emit(e, wire::STATUS, wire::status_run("BUSY", "thinking", now, now));
        e.rec.kill_outcome = None;
        e.rec.killed_by = None;
        self.run(e, text);
    }

    /// Starts the turn's run: the start-up lines, the harness turn, then the record that announces the run.
    fn run(self: &Arc<Self>, e: &mut Entry, text: String) {
        let provider = self.harness.name().to_string();
        let model = e.rec.model.clone().unwrap_or_else(|| "default".into());
        let resumed = e.rec.run_count > 0;
        // A persistent process (an interactive CLI) that is still up takes the turn as is: nothing is resumed.
        let warm = e.hs.as_ref().is_some_and(|h| h.process_alive());
        if resumed && !warm {
            let id = e.rec.id.clone();
            self.emit(e, wire::STATUS, wire::status_activity(&provider, &format!("Resuming session {id}...")));
        }
        let start = format!("starting {provider} model {model} in {}", e.rec.working_dir);
        self.emit(e, wire::STATUS, wire::status_activity(&provider, &start));
        if !resumed {
            self.emit(e, wire::STDERR, format!("[infantry] * session started ({provider} {} {model})", wire::MIDDOT));
        } else if let Some(p) = e.rec.provider_session_id.clone().filter(|_| !warm) {
            self.emit(e, wire::STATUS, wire::status_activity(&provider, &format!("resuming provider session {p}")));
        }
        if e.hs.is_none() {
            match self.open(e) {
                Ok(hs) => e.hs = Some(hs),
                Err(err) => return self.fail_start(e, err),
            }
        }
        let turn = Turn { prompt: text, resume: e.rec.provider_session_id.clone() };
        match e.hs.as_mut().unwrap().send_turn(turn) {
            Ok(start) => {
                let line = match start.pid {
                    Some(p) => format!("process started (pid {p})"),
                    None => "process started (no OS process)".into(),
                };
                self.emit(e, wire::STATUS, wire::status_activity(&provider, &line));
                e.rec.run_count += 1;
                e.rec.is_process_alive = true;
                e.rec.child_pid = start.pid.map(i64::from);
                if let Some(p) = start.provider_session_id {
                    e.rec.provider_session_id = Some(p);
                }
                Self::set_state(e, SessionState::Busy);
                self.push(e);
                self.emit(e, wire::STATUS, wire::status("BUSY"));
            }
            Err(err) => self.fail_start(e, err),
        }
    }

    fn open(self: &Arc<Self>, e: &Entry) -> Result<Box<dyn HarnessSession>, String> {
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
        let req = OpenRequest {
            session_id: e.rec.id.clone(),
            cwd: PathBuf::from(&e.rec.working_dir),
            model: e.rec.model.clone(),
            effort: e.rec.effort.clone(),
            state_dir: self.cfg.state_dir.clone(),
        };
        let hs = self.harness.open(req, EventSink(tx))?;
        let (d, id) = (self.clone(), e.rec.id.clone());
        tokio::spawn(async move {
            while let Some(ev) = rx.recv().await {
                d.on_event(&id, ev);
            }
        });
        Ok(hs)
    }

    fn fail_start(&self, e: &mut Entry, err: String) {
        e.turn_active = false;
        e.rec.last_error = Some(err.clone());
        e.rec.is_process_alive = false;
        Self::set_state(e, SessionState::Failed);
        self.push(e);
        let now = now_ms();
        self.emit(e, wire::STATUS, wire::status_run("FAILED", "finished", now, e.turn_started));
        self.emit(e, wire::FINISHED, wire::finished(None, "FAILED", &err));
    }

    fn on_event(self: &Arc<Self>, id: &str, ev: HarnessEvent) {
        let Some(entry) = self.find(id) else { return };
        let mut e = entry.lock().unwrap();
        if e.removed {
            return;
        }
        match ev {
            HarnessEvent::ProviderSession(p) => e.rec.provider_session_id = Some(p),
            HarnessEvent::Stdout(l) if e.turn_active => {
                e.rec.last_output_at = Some(now_ms());
                self.emit(&mut e, wire::STDOUT, l);
            }
            HarnessEvent::Text(t) if e.turn_active => {
                e.rec.last_output_at = Some(now_ms());
                self.emit(&mut e, wire::TEXT_DELTA, t);
            }
            HarnessEvent::Stderr(l) => self.emit(&mut e, wire::STDERR, l),
            HarnessEvent::TurnEnded { end, exit_code, reason, process_alive } if e.turn_active => {
                self.end_turn(&mut e, end, exit_code, reason, process_alive)
            }
            HarnessEvent::Exited { code } => {
                e.hs = None;
                if e.turn_active {
                    let end = if code == Some(0) { TurnEnd::Completed } else { TurnEnd::Failed };
                    self.end_turn(&mut e, end, code, None, false);
                } else if e.rec.is_process_alive {
                    e.rec.is_process_alive = false;
                    e.rec.child_pid = None;
                    e.rec.last_exit_code = code;
                    self.push(&mut e);
                }
            }
            _ => {}
        }
    }

    fn end_turn(self: &Arc<Self>, e: &mut Entry, end: TurnEnd, code: Option<i32>, reason: Option<String>, alive: bool) {
        e.turn_active = false;
        e.rec.last_exit_code = code;
        e.rec.is_process_alive = alive;
        if !alive {
            e.rec.child_pid = None;
        }
        let (state, outcome) = match end {
            TurnEnd::Completed => (SessionState::Idle, "SUCCEEDED"),
            TurnEnd::Failed => (SessionState::Failed, "FAILED"),
        };
        let detail = match end {
            TurnEnd::Completed => String::new(),
            TurnEnd::Failed => reason.filter(|r| !r.trim().is_empty()).unwrap_or_else(|| match code {
                Some(c) => format!("the process exited with code {c}"),
                None => "the turn failed with no exit code and no reason".into(),
            }),
        };
        if end == TurnEnd::Failed {
            e.rec.last_error = Some(detail.clone());
        }
        Self::set_state(e, state);
        self.push(e);
        let st = state_word(state).to_ascii_uppercase();
        self.emit(e, wire::STATUS, wire::status_run(&st, "finished", now_ms(), e.turn_started));
        self.emit(e, wire::FINISHED, wire::finished(code, outcome, &detail));
        self.emit(e, wire::STATUS, wire::status_run(&st, "idle", now_ms(), e.turn_started));
        if !e.rec.queued.is_empty() {
            self.drain_later(&e.rec.id.clone());
        }
    }

    fn interrupt(self: &Arc<Self>, sid: &str) -> Result<(), String> {
        let entry = self.find(sid).ok_or("interrupt refused: no such session")?;
        let mut e = entry.lock().unwrap();
        if !e.turn_active {
            self.emit(&mut e, wire::STDERR, "[infantry] nothing to interrupt: no turn is running".into());
            return Ok(());
        }
        if let Some(hs) = e.hs.as_mut() {
            hs.interrupt();
        }
        e.turn_active = false;
        let alive = e.hs.as_ref().is_some_and(|h| h.process_alive());
        e.rec.is_process_alive = alive;
        if !alive {
            e.rec.child_pid = None;
        }
        e.rec.last_exit_code = None;
        self.emit(&mut e, wire::STDERR, "[infantry] turn interrupted by the owner; the session is at rest and keeps its conversation".into());
        Self::set_state(&mut e, SessionState::Idle);
        self.push(&mut e);
        let started = e.turn_started;
        self.emit(&mut e, wire::STATUS, wire::status_run("IDLE", "finished", now_ms(), started));
        if !e.rec.queued.is_empty() {
            self.drain_later(&e.rec.id.clone());
        }
        Ok(())
    }

    fn kill(self: &Arc<Self>, sid: &str, force: bool) -> Result<(), String> {
        let entry = self.find(sid).ok_or("kill refused: no such session")?;
        let mut e = entry.lock().unwrap();
        if e.rec.state == SessionState::Exited {
            return Ok(());
        }
        if let Some(mut hs) = e.hs.take() {
            hs.close(force);
        }
        let now = now_ms();
        e.turn_active = false;
        e.gen += 1;
        let outcome = if force { KillOutcome::Forced } else { KillOutcome::Graceful };
        e.rec.kill_outcome = Some(outcome);
        e.rec.killed_by = Some("client request".into());
        e.rec.last_turn_at = Some(now);
        e.rec.last_exit_code = None;
        e.rec.is_process_alive = false;
        e.rec.child_pid = None;
        Self::set_state(&mut e, SessionState::Exited);
        self.push(&mut e);
        self.emit(&mut e, wire::STATUS, wire::status("EXITED"));
        let word = if force { "KILLED_FORCED" } else { "KILLED_GRACEFUL" };
        self.emit(&mut e, wire::FINISHED, wire::finished(None, word, ""));
        let started = e.turn_started;
        self.emit(&mut e, wire::STATUS, wire::status_run("IDLE", "finished", now, started));
        let (gen, d, ms) = (e.gen, self.clone(), self.cfg.kill_settle_ms);
        drop(e);
        tokio::spawn(async move {
            tokio::time::sleep(Duration::from_millis(ms)).await;
            let mut e = entry.lock().unwrap();
            if e.removed || e.gen != gen || e.rec.state != SessionState::Exited {
                return;
            }
            let at = e.rec.last_heartbeat;
            e.rec.watch = Some(AgentWatch { state: "IDLE".into(), pending_since: None, pending: None, last_activity_at: at, nudges: 0 });
            d.push(&mut e);
            let started = e.turn_started;
            d.emit(&mut e, wire::STATUS, wire::status_run("EXITED", "idle", now_ms(), started));
        });
        Ok(())
    }

    fn delete(&self, sid: &str) -> Result<(), String> {
        let entry = {
            let mut list = self.list.lock().unwrap();
            let i = list.iter().position(|(i, _)| i == sid).ok_or("session_delete refused: no such session")?;
            list.remove(i).1
        };
        {
            let mut e = entry.lock().unwrap();
            e.removed = true;
            if let Some(mut hs) = e.hs.take() {
                hs.close(true);
            }
        }
        self.cache.lock().unwrap().retain(|r| r.id != sid);
        self.dirty.store(true, Ordering::SeqCst);
        self.broadcast(wire::SESSIONS, self.sessions_json());
        Ok(())
    }

    /// Ends every session's process (the daemon is stopping) and writes the store.
    pub fn shutdown(&self) {
        let list: Vec<_> = self.list.lock().unwrap().iter().map(|(_, e)| e.clone()).collect();
        for entry in list {
            let mut e = entry.lock().unwrap();
            if let Some(mut hs) = e.hs.take() {
                hs.close(true);
            }
        }
        self.dirty.store(true, Ordering::SeqCst);
        self.flush();
    }
}

fn state_word(s: SessionState) -> &'static str {
    match s {
        SessionState::Starting => "starting",
        SessionState::Busy => "busy",
        SessionState::WaitingInput => "waiting_input",
        SessionState::Idle => "idle",
        SessionState::Exited => "exited",
        SessionState::Orphaned => "orphaned",
        SessionState::Failed => "failed",
    }
}
