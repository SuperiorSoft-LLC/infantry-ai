// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Sessions: the lifecycle the v1 sessions frames drive (`prompt`, `interrupt`, `kill`, `session_delete`, `sessions`),
//! the frames it pushes, and the store. Vendor-neutral: a provider is a [`Harness`] behind the trait.
//!
//! Lock order: the session list, then one session, then the record cache. Nothing takes the list while holding a session.

use crate::harness::{EventSink, Harness, HarnessEvent, HarnessSession, OpenRequest, Turn, TurnEnd};
use crate::store::JsonSessionStore;
use crate::wire;
use infantry_protocol::session::{AgentWatch, KillOutcome, SessionRecord, SessionState};
use infantry_protocol::Envelope;
use serde::Deserialize;
use serde_json::json;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::sync::broadcast;

/// Frames buffered per connection before a slow client starts missing them.
pub const BROADCAST_CAPACITY: usize = 4096;
/// After a kill the run activity settles to `idle` on the next watch tick (Kotlin's `AgentWatchdog`), not at once.
pub const KILL_SETTLE_MS: u64 = 1_500;

pub fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

pub struct Config {
    pub state_dir: PathBuf,
    /// Where a session runs when its `new` names no folder (Kotlin: the daemon's working directory).
    pub default_cwd: PathBuf,
    pub kill_settle_ms: u64,
}

/// What a `new` frame may carry (JSON data, every field optional).
#[derive(Debug, Default, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewOpts {
    pub cwd: Option<String>,
    pub model: Option<String>,
    pub effort: Option<String>,
}

/// Per connection: a `new` waiting for its first prompt.
#[derive(Default)]
pub struct Conn {
    pub pending_new: Option<NewOpts>,
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
}

fn encode(ty: &str, data: String, session: Option<&str>, seq: Option<i64>) -> String {
    let mut e = Envelope::new(ty, data);
    e.session_id = session.map(String::from);
    e.sequence = seq;
    serde_json::to_string(&e).unwrap_or_default()
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
            list.push((rec.id.clone(), Arc::new(Mutex::new(Entry { rec, seq: 0, hs: None, turn_active: false, turn_started: 0, gen: 0, removed: false }))));
        }
        Ok(Arc::new(Daemon {
            harness,
            cfg,
            instance: uuid::Uuid::new_v4().to_string(),
            out,
            list: Mutex::new(list),
            cache: Mutex::new(cache),
            dirty: AtomicBool::new(false),
            store: Mutex::new(store),
        }))
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
        let snapshot = self.cache.lock().unwrap().clone();
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
        let _ = self.out.send(encode(ty, data, Some(&e.rec.id), Some(e.seq)));
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

    /// One inbound frame; returns the replies for this connection only (session frames go to every connection).
    pub fn handle(self: &Arc<Self>, env: Envelope, conn: &mut Conn) -> Vec<String> {
        let sid = env.session_id.clone().filter(|s| !s.is_empty());
        let r = match env.r#type.as_str() {
            wire::SESSIONS => return vec![encode(wire::SESSIONS, self.sessions_json(), None, None)],
            wire::NEW => {
                conn.pending_new = Some(serde_json::from_str::<NewOpts>(&env.data).unwrap_or_default());
                Ok(())
            }
            wire::PROMPT => match &sid {
                None => self.prompt_new(conn.pending_new.take().unwrap_or_default(), env.data),
                Some(s) => self.prompt_into(s, env.data),
            },
            wire::INTERRUPT => self.interrupt(sid.as_deref().unwrap_or(env.data.trim())),
            wire::KILL => {
                let mut words = env.data.split_whitespace();
                let first = words.next().unwrap_or("").to_string();
                let (target, rest): (String, Vec<&str>) = match &sid {
                    Some(s) => (s.clone(), std::iter::once(first.as_str()).chain(words).collect()),
                    None => (first.clone(), words.collect()),
                };
                self.kill(&target, rest.contains(&"force"))
            }
            wire::SESSION_DELETE => self.delete(sid.as_deref().unwrap_or(env.data.trim())),
            other => Err(format!("{other} refused: this daemon does not serve that frame yet")),
        };
        match r {
            Ok(()) => Vec::new(),
            Err(msg) => vec![encode(wire::ERROR, msg, sid.as_deref(), None)],
        }
    }

    fn prompt_new(self: &Arc<Self>, opts: NewOpts, text: String) -> Result<(), String> {
        let cwd = opts.cwd.as_deref().filter(|c| !c.trim().is_empty()).map(PathBuf::from).unwrap_or_else(|| self.cfg.default_cwd.clone());
        if !cwd.is_dir() {
            return Err(format!("prompt refused: {} is not a folder", cwd.display()));
        }
        let now = now_ms();
        let id = uuid::Uuid::new_v4().to_string();
        let mut rec: SessionRecord = serde_json::from_value(json!({
            "id": id, "provider": self.harness.name(), "workingDir": cwd.to_string_lossy(),
            "spawnedAt": now, "lastHeartbeat": now, "state": "BUSY",
        }))
        .map_err(|e| e.to_string())?;
        rec.model = opts.model.filter(|m| !m.trim().is_empty());
        rec.effort = opts.effort.filter(|m| !m.trim().is_empty());
        let entry = Arc::new(Mutex::new(Entry { rec, seq: 0, hs: None, turn_active: true, turn_started: now, gen: 0, removed: false }));
        self.list.lock().unwrap().push((id.clone(), entry.clone()));
        let mut e = entry.lock().unwrap();
        Self::set_state(&mut e, SessionState::Busy);
        self.push(&mut e);
        self.emit(&mut e, wire::STATUS, wire::status_run("BUSY", "thinking", now, now));
        self.emit(&mut e, wire::TURN, text.clone());
        self.run(&mut e, text);
        Ok(())
    }

    fn prompt_into(self: &Arc<Self>, sid: &str, text: String) -> Result<(), String> {
        let entry = self.find(sid).ok_or("prompt refused: no such session")?;
        let mut e = entry.lock().unwrap();
        if e.turn_active {
            return Err("prompt refused: a turn is running; interrupt it first".into());
        }
        let now = now_ms();
        e.rec.last_turn_at = Some(now);
        // A turn into a session whose process is gone resumes it (a fresh process continues the provider session).
        if e.rec.run_count > 0 && !e.hs.as_ref().is_some_and(|h| h.process_alive()) {
            e.rec.resume_count += 1;
        }
        self.push(&mut e);
        let st = state_word(e.rec.state).to_ascii_uppercase();
        self.emit(&mut e, wire::STATUS, wire::status(&st));
        self.emit(&mut e, wire::TURN, text.clone());
        e.turn_active = true;
        e.turn_started = now;
        e.gen += 1;
        self.emit(&mut e, wire::STATUS, wire::status_run("BUSY", "thinking", now, now));
        e.rec.kill_outcome = None;
        e.rec.killed_by = None;
        self.run(&mut e, text);
        Ok(())
    }

    /// Starts the turn's run: the start-up lines, the harness turn, then the record that announces the run.
    fn run(self: &Arc<Self>, e: &mut Entry, text: String) {
        let provider = self.harness.name().to_string();
        let model = e.rec.model.clone().unwrap_or_else(|| "default".into());
        let resumed = e.rec.run_count > 0;
        if resumed {
            let id = e.rec.id.clone();
            self.emit(e, wire::STATUS, wire::status_activity(&provider, &format!("Resuming session {id}...")));
        }
        let start = format!("starting {provider} model {model} in {}", e.rec.working_dir);
        self.emit(e, wire::STATUS, wire::status_activity(&provider, &start));
        if !resumed {
            self.emit(e, wire::STDERR, format!("[infantry] * session started ({provider} {} {model})", wire::MIDDOT));
        } else if let Some(p) = e.rec.provider_session_id.clone() {
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

    fn on_event(&self, id: &str, ev: HarnessEvent) {
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

    fn end_turn(&self, e: &mut Entry, end: TurnEnd, code: Option<i32>, reason: Option<String>, alive: bool) {
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
    }

    fn interrupt(&self, sid: &str) -> Result<(), String> {
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
