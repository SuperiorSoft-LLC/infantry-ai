// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-PERF-1: agents in the app. The Tauri backend runs claude through `infantry-harness-claude` on the local runner
//! (`infantry-runner-local`), the same driver the daemon uses (RUST-HARN-CLAUDE-1), and serves its sessions to the
//! WebView over Tauri IPC with the SAME envelope frames the daemon speaks (`ready`, `sessions`, `session`, `turn`,
//! `text_delta`, `reasoning`, `status`, `finished`, `usage`, `replay_request`...). The React store, the transcript
//! reducer and every component are unchanged: only the transport differs (`local-transport.ts`).
//!
//! This is the spike shape, not the plan's end state (PLAN.md 1.1 keeps the daemon a separate process): there is no
//! board, mail, MCP bridge, plugin or database here. Sessions live as long as the app; closing the window kills every
//! session's process tree.

use infantry_harness::{ApprovalMode, HarnessDriver, HarnessEvent as Ev, LaunchRequest, Runner, TurnEnd, Usage};
use infantry_harness_claude::{model_arg, transcript, ClaudeHarness};
use infantry_runner_local::{LocalConfig, LocalRunner};
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};
use std::collections::VecDeque;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc as std_mpsc, Arc, Mutex, OnceLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tauri::ipc::Channel;
use tokio::sync::mpsc;

/// Inherited variables a child must not see: this app's own identity (the harness itself removes claude's
/// nested-session guard, `CLAUDECODE` / `CLAUDE_CODE_*`).
const ENV_REMOVE: [&str; 2] = ["INFANTRY_*", "AGENTHQ_*"];
/// Recent project folders kept for the picker.
const MAX_PROJECTS: usize = 12;
/// Transcript files listed for resume, newest first.
const MAX_RESUMABLE: usize = 100;
/// How often a live session's driver is stepped when no command arrives (screen reads, transcript tail, timers).
const POLL_STEP: Duration = Duration::from_millis(100);
/// How long an interrupt or a close waits for the session's worker to answer.
const CMD_WAIT: Duration = Duration::from_secs(5);

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

type Sink = Arc<Mutex<Option<Channel<String>>>>;

fn envelope(kind: &str, data: String, session: Option<&str>, seq: Option<u64>, request: Option<&str>) -> String {
    let mut m = Map::new();
    m.insert("type".into(), json!(kind));
    m.insert("data".into(), json!(data));
    m.insert("sessionId".into(), session.map_or(Value::Null, |s| json!(s)));
    m.insert("sequence".into(), seq.map_or(Value::Null, |s| json!(s)));
    if let Some(r) = request {
        m.insert("requestId".into(), json!(r));
    }
    Value::Object(m).to_string()
}

fn push(sink: &Sink, text: String) {
    if let Some(ch) = sink.lock().unwrap().as_ref() {
        let _ = ch.send(text);
    }
}

/// What a `new` frame may carry (JSON data); every field optional.
#[derive(Debug, Default, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SpawnOpts {
    pub name: Option<String>,
    pub model: Option<String>,
    pub cwd: Option<String>,
    pub resume: Option<String>,
    /// A first turn, typed once the CLI is up.
    pub prompt: Option<String>,
}

/// One session's sequenced stream: its record and every frame sent so far (replayed on `replay_request`).
struct Stream {
    record: Map<String, Value>,
    seq: u64,
    history: Vec<(u64, String)>,
    last_text: bool,
}

/// What the WebView asks of a session's worker.
enum Cmd {
    Turn(String),
    Interrupt(std_mpsc::Sender<bool>),
    Close(std_mpsc::Sender<()>),
}

struct Agent {
    id: String,
    /// The running worker's command channel (None = the process ended; a resume starts a new worker).
    tx: Mutex<Option<mpsc::UnboundedSender<Cmd>>>,
    alive: AtomicBool,
    /// A turn is running or queued.
    busy: AtomicBool,
    st: Mutex<Stream>,
    sink: Sink,
}

impl Agent {
    fn emit(&self, kind: &str, data: String) {
        let mut st = self.st.lock().unwrap();
        st.seq += 1;
        let seq = st.seq;
        let text = envelope(kind, data, Some(&self.id), Some(seq), None);
        st.history.push((seq, text.clone()));
        push(&self.sink, text);
    }

    /// Updates the record and sends it as a `session` frame.
    fn record(&self, f: impl FnOnce(&mut Map<String, Value>)) {
        let data = {
            let mut st = self.st.lock().unwrap();
            f(&mut st.record);
            st.record.insert("lastHeartbeat".into(), json!(now_ms()));
            Value::Object(st.record.clone()).to_string()
        };
        self.emit("session", data);
    }

    fn set_state(&self, state: &str, turn: bool) {
        self.record(|r| {
            r.insert("state".into(), json!(state));
            r.insert("turnActive".into(), json!(turn));
        });
    }

    fn alive(&self) -> bool {
        self.alive.load(Ordering::SeqCst)
    }

    fn command(&self, c: Cmd) -> bool {
        self.tx.lock().unwrap().as_ref().is_some_and(|tx| tx.send(c).is_ok())
    }

    /// Hands [text] to the worker: it starts now, or once the running turn has ended (the queue).
    fn send_turn(&self, text: &str) -> bool {
        if !self.alive() || !self.command(Cmd::Turn(text.to_string())) {
            return false;
        }
        self.busy.store(true, Ordering::SeqCst);
        self.st.lock().unwrap().last_text = false;
        self.emit("turn", text.to_string());
        self.record(|r| {
            r.insert("state".into(), json!("BUSY"));
            r.insert("turnActive".into(), json!(true));
            let n = r.get("runCount").and_then(Value::as_u64).unwrap_or(0) + 1;
            r.insert("runCount".into(), json!(n));
        });
        true
    }

    /// Stops the running turn (and drops the queued ones). False = nothing was running.
    fn interrupt(&self) -> bool {
        let (reply, answer) = std_mpsc::channel();
        self.command(Cmd::Interrupt(reply)) && answer.recv_timeout(CMD_WAIT).unwrap_or(false)
    }

    /// Kills the process tree; waits up to [CMD_WAIT] for it.
    fn close(&self) {
        let (reply, done) = std_mpsc::channel();
        if self.command(Cmd::Close(reply)) {
            let _ = done.recv_timeout(CMD_WAIT);
        }
    }

    fn on_exit(&self, code: Option<i32>) {
        if !self.alive.swap(false, Ordering::SeqCst) {
            return;
        }
        self.busy.store(false, Ordering::SeqCst);
        *self.tx.lock().unwrap() = None;
        self.record(|r| {
            r.insert("state".into(), json!("EXITED"));
            r.insert("turnActive".into(), json!(false));
            r.insert("lastExitCode".into(), json!(code));
        });
    }

    fn on_event(&self, e: Ev) {
        match e {
            Ev::ProviderSession(id) => self.record(|r| {
                r.insert("providerSessionId".into(), json!(id));
            }),
            Ev::TurnStarted => self.set_state("BUSY", true),
            Ev::Text(text) => {
                let joined = {
                    let mut st = self.st.lock().unwrap();
                    let j = if st.last_text { format!("\n\n{text}") } else { text };
                    st.last_text = true;
                    j
                };
                self.emit("text_delta", joined);
            }
            Ev::TextDelta(text) => {
                self.st.lock().unwrap().last_text = true;
                self.emit("text_delta", text);
            }
            Ev::Reasoning(text) => {
                self.st.lock().unwrap().last_text = false;
                self.emit("reasoning", text);
            }
            Ev::ToolStarted { name, input, .. } => {
                self.st.lock().unwrap().last_text = false;
                let input: Value = serde_json::from_str(&input).unwrap_or(Value::String(input));
                let s = |k: &str| input.get(k).and_then(Value::as_str).unwrap_or("").to_string();
                let write = match name.as_str() {
                    "Edit" => Some(json!({"path": s("file_path"), "edits": [{"oldText": s("old_string"), "newText": s("new_string")}]})),
                    "Write" => Some(json!({"path": s("file_path"), "content": s("content")})),
                    _ => None,
                };
                let activity = tool_activity(&input);
                self.emit("status", json!({"state": "BUSY", "tool": name, "activity": activity}).to_string());
                if let Some(w) = write {
                    self.emit("status", json!({"state": "BUSY", "write": w}).to_string());
                }
            }
            Ev::McpState { .. } => {} // not surfaced by the embedded runner yet
            Ev::Usage(u) => self.usage(&u),
            Ev::Warning(w) => self.emit("stderr", format!("[runner] {w}")),
            Ev::Error(err) => {
                if !err.trim().is_empty() {
                    self.emit("error", err);
                }
            }
            Ev::Stderr(l) => self.emit("stderr", l),
            Ev::TurnEnded { end, reason } => {
                let outcome = match end {
                    TurnEnd::Completed => "SUCCEEDED",
                    TurnEnd::Interrupted => "INTERRUPTED",
                    TurnEnd::Failed => "FAILED",
                };
                if end != TurnEnd::Completed {
                    if let Some(r) = reason.filter(|r| !r.trim().is_empty()) {
                        self.emit("stderr", r);
                    }
                }
                self.st.lock().unwrap().last_text = false;
                self.emit("finished", json!({"exitCode": Value::Null, "outcome": outcome}).to_string());
                if self.busy.load(Ordering::SeqCst) {
                    self.set_state("BUSY", true);
                } else {
                    self.set_state("IDLE", false);
                }
            }
            Ev::Exited { code } => self.on_exit(code),
            // Approvals are answered by the worker; a PTY's raw stdout is screen bytes; tool ends draw nothing yet.
            // Questions (RUST-PROMPT-1) are answered by the daemon path; the embedded runner has no prompt card yet.
            Ev::ApprovalRequested { .. } | Ev::QuestionAsked { .. } | Ev::ToolFinished { .. } | Ev::Stdout(_) => {}
        }
    }

    /// A `usage` frame. Neutral `input_tokens` counts every prompt token; the wire counts the cache reads apart.
    fn usage(&self, u: &Usage) {
        let (model, run) = {
            let st = self.st.lock().unwrap();
            (st.record.get("model").cloned().unwrap_or(json!(infantry_harness_claude::DEFAULT_MODEL)), st.seq)
        };
        self.emit("usage", usage_frame(&format!("{}-{run}", self.id), model, u).to_string());
    }
}

fn usage_frame(run_id: &str, model: Value, u: &Usage) -> Value {
    json!({
        "runId": run_id, "provider": infantry_harness_claude::ID, "model": model,
        "input": u.input_tokens.saturating_sub(u.cached_input_tokens), "output": u.output_tokens,
        "cacheRead": u.cached_input_tokens, "cacheWrite": 0, "costUsd": Value::Null,
    })
}

/// One session's driver on its own thread and runtime: commands from the WebView in, frames out. Questions are not
/// routed to the UI yet (a known gap); sessions run with approval = allow all, so an approval asked anyway is allowed.
fn work(agent: Arc<Agent>, mut driver: HarnessDriver, mut rx: mpsc::UnboundedReceiver<Cmd>) {
    let rt = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
        Ok(rt) => rt,
        Err(e) => {
            agent.emit("stderr", format!("[runner] no runtime: {e}"));
            agent.on_exit(None);
            return;
        }
    };
    rt.block_on(async move {
        let mut queue: VecDeque<String> = VecDeque::new();
        let mut spawned = false;
        loop {
            // The next queued turn starts once the running one has ended.
            if !driver.busy() {
                if let Some(p) = queue.pop_front() {
                    if let Err(e) = driver.start_turn(&p).await {
                        agent.emit("error", format!("could not start claude: {e}"));
                        if driver.proc_id().is_none() {
                            break;
                        }
                    }
                    spawned = true;
                }
                agent.busy.store(driver.busy() || !queue.is_empty(), Ordering::SeqCst);
            }
            let cmd = if driver.proc_id().is_some() {
                match rx.try_recv() {
                    Ok(c) => Some(c),
                    Err(mpsc::error::TryRecvError::Empty) => None,
                    Err(mpsc::error::TryRecvError::Disconnected) => break,
                }
            } else if spawned {
                // The process ended; its Exited event was forwarded by the last poll.
                break;
            } else {
                // Not started yet: claude is launched by the first turn.
                match rx.recv().await {
                    Some(c) => Some(c),
                    None => break,
                }
            };
            match cmd {
                Some(Cmd::Turn(p)) => queue.push_back(p),
                Some(Cmd::Interrupt(reply)) => {
                    queue.clear();
                    let _ = reply.send(driver.interrupt().await);
                }
                Some(Cmd::Close(done)) => {
                    driver.close().await;
                    agent.on_exit(None);
                    let _ = done.send(());
                    break;
                }
                None => {
                    for e in driver.poll(POLL_STEP).await {
                        if let Ev::ApprovalRequested { id, .. } = &e {
                            driver.answer(id, true).await;
                        }
                        if matches!(e, Ev::TurnEnded { .. }) {
                            agent.busy.store(!queue.is_empty(), Ordering::SeqCst);
                        }
                        agent.on_event(e);
                    }
                }
            }
        }
        driver.close().await;
        agent.on_exit(None);
    });
}

/// One short line for a tool call: its command, path, pattern or url.
fn tool_activity(input: &Value) -> String {
    if let Some(s) = input.as_str().filter(|s| !s.trim().is_empty()) {
        return s.lines().next().unwrap_or("").trim().chars().take(120).collect();
    }
    for k in ["command", "file_path", "path", "pattern", "url", "description", "prompt"] {
        if let Some(v) = input.get(k).and_then(Value::as_str).filter(|v| !v.trim().is_empty()) {
            let line = v.lines().next().unwrap_or("").trim();
            return line.chars().take(120).collect();
        }
    }
    "running".into()
}

/// A transcript file under a project's claude folder that `claude --resume` can continue.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Resumable {
    pub id: String,
    pub modified: u64,
    pub bytes: u64,
    pub title: String,
    /// Running in this app right now (resuming it again is refused).
    pub running: bool,
}

#[derive(Debug, Serialize, Clone)]
pub struct Project {
    pub id: String,
    pub name: String,
    pub directory: String,
}

pub struct Local {
    sink: Sink,
    agents: Mutex<Vec<Arc<Agent>>>,
    pending_new: Mutex<Option<SpawnOpts>>,
    projects: Mutex<Vec<PathBuf>>,
    harness: Arc<ClaudeHarness>,
    runner: OnceLock<Arc<LocalRunner>>,
    state_dir: PathBuf,
    instance: String,
}

impl Local {
    pub fn new(state_dir: PathBuf) -> Local {
        Local {
            sink: Arc::default(),
            agents: Mutex::default(),
            pending_new: Mutex::default(),
            projects: Mutex::default(),
            harness: Arc::new(ClaudeHarness::default()),
            runner: OnceLock::new(),
            instance: format!("local-{}", now_ms()),
            state_dir,
        }
    }

    /// The folder a session runs in when none was picked: an empty scratch folder under the app's state.
    fn scratch(&self) -> PathBuf {
        let p = self.state_dir.join("scratch");
        let _ = std::fs::create_dir_all(&p);
        p
    }

    fn runner(&self) -> Arc<LocalRunner> {
        self.runner
            .get_or_init(|| Arc::new(LocalRunner::new(LocalConfig { scratch_dir: Some(self.state_dir.join("runner")), ..Default::default() })))
            .clone()
    }

    fn send(&self, kind: &str, data: String, request: Option<&str>) {
        push(&self.sink, envelope(kind, data, None, None, request));
    }

    pub fn connect(&self, ch: Channel<String>) {
        *self.sink.lock().unwrap() = Some(ch);
        self.send(
            "ready",
            format!("provider={} contract=local instance={} since=0 build=embedded-runner", infantry_harness_claude::ID, self.instance),
            None,
        );
        let records: Vec<Value> = self.agents.lock().unwrap().iter().map(|a| Value::Object(a.st.lock().unwrap().record.clone())).collect();
        self.send("sessions", Value::Array(records).to_string(), None);
    }

    fn agent(&self, id: Option<&str>) -> Option<Arc<Agent>> {
        let id = id?;
        self.agents.lock().unwrap().iter().find(|a| a.id == id).cloned()
    }

    fn error(&self, msg: String, session: Option<&str>) {
        push(&self.sink, envelope("error", msg, session, None, None));
    }

    /// One inbound frame from the WebView (an encoded envelope).
    pub fn handle(&self, text: &str) {
        let Ok(v) = serde_json::from_str::<Value>(text) else { return };
        let kind = v.get("type").and_then(Value::as_str).unwrap_or("");
        let data = v.get("data").and_then(Value::as_str).unwrap_or("").to_string();
        let sid = v.get("sessionId").and_then(Value::as_str);
        let req = v.get("requestId").and_then(Value::as_str);
        match kind {
            "new" => {
                let opts: Option<SpawnOpts> = serde_json::from_str(&data).ok();
                match opts {
                    // An options object starts the session now; empty data waits for the prompt that follows.
                    Some(o) => {
                        if let Err(e) = self.spawn(o) {
                            self.error(format!("new refused: {e}"), None);
                        }
                    }
                    None => *self.pending_new.lock().unwrap() = Some(SpawnOpts::default()),
                }
            }
            "prompt" | "queue_add" => {
                let pending = if sid.is_none() { self.pending_new.lock().unwrap().take() } else { None };
                if let Some(mut o) = pending {
                    o.prompt = Some(data);
                    match self.spawn(o) {
                        Ok(_) => self.send("prompt_ack", String::new(), req),
                        Err(e) => self.error(format!("prompt refused: {e}"), None),
                    }
                } else if let Some(a) = self.agent(sid) {
                    if a.send_turn(&data) {
                        if let Some(r) = req {
                            push(&self.sink, envelope("prompt_ack", String::new(), sid, None, Some(r)));
                        }
                    } else {
                        self.error(format!("{kind} refused: the session has exited; resume it"), sid);
                    }
                } else {
                    self.error(format!("{kind} refused: no such session"), sid);
                }
            }
            "interrupt" => {
                if let Some(a) = self.agent(sid.or(Some(data.as_str()))) {
                    if !a.interrupt() {
                        a.emit("stderr", "[runner] nothing to interrupt".into());
                    }
                }
            }
            "kill" => {
                if let Some(a) = self.agent(sid.or(Some(data.as_str()))) {
                    a.close();
                }
            }
            "replay_request" => {
                let from = serde_json::from_str::<Value>(&data).ok().and_then(|v| v.get("lastSequence").and_then(Value::as_u64)).unwrap_or(0);
                if let Some(a) = self.agent(sid) {
                    let frames: Vec<String> = a.st.lock().unwrap().history.iter().filter(|(s, _)| *s > from).map(|(_, t)| t.clone()).collect();
                    for f in frames {
                        push(&self.sink, f);
                    }
                }
            }
            "agent_list" => self.send("agents", "[]".into(), None),
            "ticket_list" => self.send("tickets", "[]".into(), None),
            "project_list" => self.send_projects(),
            _ => {}
        }
    }

    fn send_projects(&self) {
        let list: Vec<Project> = self.projects.lock().unwrap().iter().map(|p| project(p)).collect();
        self.send("projects", serde_json::to_string(&list).unwrap_or_else(|_| "[]".into()), None);
    }

    /// The folder new sessions run in (first in the list); it moves to the front when picked again.
    pub fn set_project(&self, folder: &str) -> Result<Project, String> {
        let p = PathBuf::from(folder);
        if !p.is_dir() {
            return Err(format!("{folder} is not a folder"));
        }
        {
            let mut g = self.projects.lock().unwrap();
            g.retain(|q| q != &p);
            g.insert(0, p.clone());
            g.truncate(MAX_PROJECTS);
        }
        self.send_projects();
        Ok(project(&p))
    }

    fn current_project(&self) -> Option<PathBuf> {
        self.projects.lock().unwrap().first().cloned()
    }

    /// Starts (or resumes) a session. claude itself is launched by the session's first turn; the id is known now:
    /// a new session gets a fresh id, passed as the resume token (the harness starts a new conversation under an id
    /// that has no transcript yet).
    pub fn spawn(&self, o: SpawnOpts) -> Result<String, String> {
        let cwd = match o.cwd.as_deref().filter(|c| !c.trim().is_empty()) {
            Some(c) => PathBuf::from(c),
            None => self.current_project().unwrap_or_else(|| self.scratch()),
        };
        if !cwd.is_dir() {
            return Err(format!("{} is not a folder", cwd.display()));
        }
        let resume = o.resume.clone().filter(|r| !r.trim().is_empty());
        let existing = resume.as_deref().and_then(|id| self.agent(Some(id)));
        if existing.as_ref().is_some_and(|a| a.alive()) {
            return Err("that session is already running in this app".into());
        }
        let id = resume.clone().unwrap_or_else(|| uuid::Uuid::new_v4().to_string());
        let model = model_arg(o.model.as_deref());
        let mut req = LaunchRequest::new(cwd.clone());
        req.model = model.clone();
        req.approval = ApprovalMode::AllowAll;
        req.resume = Some(id.clone());
        req.env_remove = ENV_REMOVE.map(String::from).to_vec();
        let runner: Arc<dyn Runner> = self.runner();
        let driver = HarnessDriver::new(self.harness.clone(), runner, req);
        let (tx, rx) = mpsc::unbounded_channel();
        let name = o.name.clone().filter(|n| !n.trim().is_empty());
        let now = now_ms();

        let agent = match existing {
            Some(a) => {
                *a.tx.lock().unwrap() = Some(tx);
                a.alive.store(true, Ordering::SeqCst);
                a.record(|r| {
                    r.insert("state".into(), json!("IDLE"));
                    r.insert("turnActive".into(), json!(false));
                    let n = r.get("resumeCount").and_then(Value::as_u64).unwrap_or(0) + 1;
                    r.insert("resumeCount".into(), json!(n));
                });
                a
            }
            None => {
                let mut record = Map::new();
                record.insert("id".into(), json!(id));
                record.insert("provider".into(), json!(infantry_harness_claude::ID));
                record.insert("workingDir".into(), json!(cwd.to_string_lossy()));
                record.insert("spawnedAt".into(), json!(now));
                record.insert("lastHeartbeat".into(), json!(now));
                record.insert("state".into(), json!("IDLE"));
                record.insert("providerSessionId".into(), json!(id));
                record.insert("model".into(), json!(model.clone().unwrap_or_else(|| infantry_harness_claude::DEFAULT_MODEL.into())));
                record.insert("role".into(), json!(name.as_deref().map(str::to_lowercase).unwrap_or_else(|| "agent".into())));
                record.insert("runCount".into(), json!(0));
                record.insert("resumeCount".into(), json!(if resume.is_some() { 1 } else { 0 }));
                record.insert("turnActive".into(), json!(false));
                record.insert("runner".into(), json!("embedded"));
                let a = Arc::new(Agent {
                    id: id.clone(),
                    tx: Mutex::new(Some(tx)),
                    alive: AtomicBool::new(true),
                    busy: AtomicBool::new(false),
                    st: Mutex::new(Stream { record: record.clone(), seq: 0, history: Vec::new(), last_text: false }),
                    sink: self.sink.clone(),
                });
                self.agents.lock().unwrap().push(a.clone());
                a.emit("session", Value::Object(record).to_string());
                a
            }
        };
        if resume.is_some() {
            agent.emit("stderr", format!("[runner] claude --resume {id}"));
        }
        let worker = agent.clone();
        std::thread::Builder::new().name("infantry-agent".into()).spawn(move || work(worker, driver, rx)).map_err(|e| {
            agent.on_exit(None);
            e.to_string()
        })?;
        if let Some(p) = o.prompt.filter(|p| !p.trim().is_empty()) {
            agent.send_turn(&p);
        }
        Ok(id)
    }

    /// The transcripts claude keeps for [folder] (`<claude config>/projects/<slug>/*.jsonl`), newest first.
    pub fn resumable(&self, folder: &str) -> Vec<Resumable> {
        let Some(config) = self.harness.config_dir.as_ref() else { return Vec::new() };
        let dir = config.join("projects").join(transcript::slug(folder));
        let Ok(rd) = std::fs::read_dir(&dir) else { return Vec::new() };
        let mut out: Vec<Resumable> = rd
            .flatten()
            .filter_map(|e| {
                let p = e.path();
                if p.extension().and_then(|x| x.to_str()) != Some("jsonl") {
                    return None;
                }
                let id = p.file_stem()?.to_str()?.to_string();
                let md = e.metadata().ok()?;
                let modified = md.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|d| d.as_millis() as u64).unwrap_or(0);
                Some(Resumable { running: self.agent(Some(&id)).is_some_and(|a| a.alive()), title: String::new(), id, modified, bytes: md.len() })
            })
            .collect();
        out.sort_by(|a, b| b.modified.cmp(&a.modified));
        out.truncate(MAX_RESUMABLE);
        for r in &mut out {
            r.title = first_prompt(&dir.join(format!("{}.jsonl", r.id))).unwrap_or_else(|| "(no prompt)".into());
        }
        out
    }

    /// Kills every session's process tree (the app is closing).
    pub fn shutdown(&self) {
        let agents: Vec<Arc<Agent>> = self.agents.lock().unwrap().drain(..).collect();
        for a in agents {
            a.close();
        }
    }
}

fn project(p: &Path) -> Project {
    let s = p.to_string_lossy().into_owned();
    Project { name: p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| s.clone()), id: s.clone(), directory: s }
}

/// The first prompt the owner typed in a transcript (its first 400 lines), for the resume list.
fn first_prompt(path: &Path) -> Option<String> {
    use std::io::BufRead;
    let f = std::fs::File::open(path).ok()?;
    for line in std::io::BufReader::new(f).lines().take(400).map_while(Result::ok) {
        let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
        if v.get("type").and_then(Value::as_str) != Some("user") || v.get("isMeta").and_then(Value::as_bool) == Some(true) {
            continue;
        }
        let c = v.get("message").and_then(|m| m.get("content"));
        let text = match c {
            Some(Value::String(s)) => s.clone(),
            Some(Value::Array(a)) => a.iter().filter_map(|b| b.get("text").and_then(Value::as_str)).collect::<Vec<_>>().join(" "),
            _ => continue,
        };
        let t = text.trim();
        if t.is_empty() || t.starts_with('<') {
            continue;
        }
        return Some(t.chars().take(140).collect());
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn envelopes_match_the_wire() {
        let e = envelope("turn", "hi".into(), Some("s1"), Some(3), None);
        let v: Value = serde_json::from_str(&e).unwrap();
        assert_eq!(v["type"], "turn");
        assert_eq!(v["data"], "hi");
        assert_eq!(v["sessionId"], "s1");
        assert_eq!(v["sequence"], 3);
        assert!(v.get("requestId").is_none());
    }

    #[test]
    fn tool_activity_is_one_short_line() {
        assert_eq!(tool_activity(&json!({"command": "ls -la\necho x"})), "ls -la");
        assert_eq!(tool_activity(&json!("git status\nmore")), "git status");
        assert_eq!(tool_activity(&json!({})), "running");
    }

    #[test]
    fn usage_counts_cache_reads_apart() {
        let u = Usage { input_tokens: 1000, cached_input_tokens: 800, output_tokens: 50, reasoning_tokens: 0 };
        let v = usage_frame("s-1", json!("default"), &u);
        assert_eq!(v["input"], 200);
        assert_eq!(v["cacheRead"], 800);
        assert_eq!(v["output"], 50);
        assert_eq!(v["provider"], "claude");
    }

    #[test]
    fn first_prompt_skips_meta_and_commands() {
        let dir = std::env::temp_dir().join(format!("infantry-local-test-{}", now_ms()));
        std::fs::create_dir_all(&dir).unwrap();
        let f = dir.join("x.jsonl");
        std::fs::write(
            &f,
            concat!(
                "{\"type\":\"user\",\"isMeta\":true,\"message\":{\"content\":\"caveat\"}}\n",
                "{\"type\":\"user\",\"message\":{\"content\":\"<command-name>/clear</command-name>\"}}\n",
                "{\"type\":\"user\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"reply with ok\"}]}}\n"
            ),
        )
        .unwrap();
        assert_eq!(first_prompt(&f).as_deref(), Some("reply with ok"));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
