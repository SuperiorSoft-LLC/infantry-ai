// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-SESS-2: the Run tab's daemon side. Saved run commands (`commands.json` in the state dir), one pseudo-terminal per
//! run streamed as `run_bytes` (xterm.js on the client), the bounded history of runs, and the processes list / reap.
//!
//! Frames and limits are the Kotlin `Infantry_Run` plugin's (contract 1.33 / 1.108 / 1.134): `command_list|save|delete`,
//! `run_start|stop|list|attach|resize|input`, `run`, `run_bytes`, `run_finished`, `processes`, `processes_reap`.

pub mod host_stats;
pub mod mem;
pub mod prep;
pub mod tools;

use crate::sessions::{now_ms, Daemon};
use base64::Engine as _;
use infantry_core::bus::EventBus;
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_protocol::frames as f;
use infantry_runner::job::Job;
use infantry_runner::pty::{self, Console, Process};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::VecDeque;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

/// `limits.run.*` defaults (Kotlin `RunManager`).
#[derive(Debug, Clone, Copy)]
pub struct RunLimits {
    /// Terminal bytes a run keeps for `run_attach` (the oldest go first).
    pub scrollback_bytes: usize,
    /// Finished runs kept in `run_list`.
    pub history: usize,
    pub input_bytes_per_second: usize,
    pub input_max_frame_bytes: usize,
    pub max_cols: u16,
    pub max_rows: u16,
    /// How long a graceful stop waits after Ctrl+C before the tree is killed (a command's own `graceMillis` wins).
    pub grace_ms: u64,
    /// Exit polling interval.
    pub poll_ms: u64,
    /// Free RAM below this marks the report under pressure (`processes.floorMb`).
    pub floor_mb: u64,
}

impl Default for RunLimits {
    fn default() -> Self {
        RunLimits {
            scrollback_bytes: 2 * 1024 * 1024,
            history: 20,
            input_bytes_per_second: 64 * 1024,
            input_max_frame_bytes: 16 * 1024,
            max_cols: 1000,
            max_rows: 1000,
            grace_ms: 5_000,
            poll_ms: 100,
            floor_mb: 4096,
        }
    }
}

/// One saved command (the wire's `CommandSpec`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CommandSpec {
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub cwd: Option<String>,
    pub argv: Vec<String>,
    #[serde(default = "default_grace")]
    pub grace_millis: i64,
    #[serde(default)]
    pub secrets: Vec<String>,
    #[serde(default)]
    pub project: Option<String>,
    #[serde(default)]
    pub rooted: bool,
    #[serde(default)]
    pub renders: Vec<String>,
    #[serde(default)]
    pub detached: bool,
    /// The command names `{worktree}` or `{branch}`: each start names a branch.
    #[serde(default, deserialize_with = "lenient_bool")]
    pub branch: bool,
}

/// `branch` was once written as a string; any non-empty string reads as true.
fn lenient_bool<'de, D: serde::Deserializer<'de>>(d: D) -> Result<bool, D::Error> {
    Ok(match Value::deserialize(d)? {
        Value::Bool(b) => b,
        Value::String(s) => !s.is_empty(),
        _ => false,
    })
}

fn default_grace() -> i64 {
    5_000
}

/// `command_save` body (an upsert by id).
#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CommandSave {
    pub id: String,
    pub name: Option<String>,
    #[serde(default)]
    pub argv: Vec<String>,
    pub cwd: Option<String>,
    pub grace_millis: Option<i64>,
    pub project: Option<String>,
    pub renders: Option<Vec<String>>,
    pub secrets: Option<Vec<String>>,
    pub detached: Option<bool>,
}

fn dedup(v: Vec<String>) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for s in v.into_iter().map(|s| s.trim().to_string()).filter(|s| !s.is_empty()) {
        if !out.contains(&s) {
            out.push(s);
        }
    }
    out
}

/// The saved commands, in `<state>/commands.json`.
pub struct CommandStore {
    path: PathBuf,
    list: Mutex<Vec<CommandSpec>>,
}

impl CommandStore {
    pub fn open(state_dir: &Path) -> CommandStore {
        let path = state_dir.join("commands.json");
        let list = std::fs::read_to_string(&path).ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default();
        CommandStore { path, list: Mutex::new(list) }
    }

    pub fn list(&self, project: Option<&str>) -> Vec<CommandSpec> {
        let l = self.list.lock().unwrap();
        l.iter().filter(|c| project.is_none_or(|p| c.project.as_deref().is_none_or(|cp| cp == p))).cloned().collect()
    }

    pub fn get(&self, id: &str) -> Option<CommandSpec> {
        self.list.lock().unwrap().iter().find(|c| c.id == id).cloned()
    }

    pub fn save(&self, req: CommandSave) -> Result<CommandSpec, String> {
        let id = req.id.trim().to_string();
        if id.is_empty() || !id.chars().all(|c| c.is_ascii_alphanumeric() || "-_.".contains(c)) {
            return Err("id must be letters, digits, '-', '_' or '.'".into());
        }
        let mut l = self.list.lock().unwrap();
        let old = l.iter().find(|c| c.id == id).cloned();
        let argv: Vec<String> = req.argv.into_iter().filter(|a| !a.is_empty()).collect();
        let argv = if argv.is_empty() { old.as_ref().map(|o| o.argv.clone()).unwrap_or_default() } else { argv };
        if argv.is_empty() {
            return Err("argv is empty: give the program and its arguments".into());
        }
        let spec = CommandSpec {
            name: req.name.filter(|n| !n.trim().is_empty()).or(old.as_ref().map(|o| o.name.clone())).unwrap_or_else(|| id.clone()),
            cwd: req.cwd.filter(|c| !c.trim().is_empty()).or(old.as_ref().and_then(|o| o.cwd.clone())),
            argv,
            grace_millis: req.grace_millis.or(old.as_ref().map(|o| o.grace_millis)).unwrap_or_else(default_grace).max(0),
            secrets: req.secrets.map(dedup).or(old.as_ref().map(|o| o.secrets.clone())).unwrap_or_default(),
            project: req.project.filter(|p| !p.trim().is_empty()).or(old.as_ref().and_then(|o| o.project.clone())),
            rooted: old.as_ref().is_some_and(|o| o.rooted),
            renders: req.renders.map(dedup).or(old.as_ref().map(|o| o.renders.clone())).unwrap_or_default(),
            detached: req.detached.or(old.as_ref().map(|o| o.detached)).unwrap_or(false),
            branch: false,
            id: id.clone(),
        };
        prep::check_command(&spec.argv, spec.cwd.as_deref(), &spec.secrets, &spec.renders)?;
        let spec = CommandSpec { branch: prep::is_branch_command(&spec.argv, spec.cwd.as_deref()), ..spec };
        match l.iter_mut().find(|c| c.id == id) {
            Some(c) => *c = spec.clone(),
            None => l.push(spec.clone()),
        }
        self.write(&l)?;
        Ok(spec)
    }

    pub fn delete(&self, id: &str) -> Result<(), String> {
        let mut l = self.list.lock().unwrap();
        let before = l.len();
        l.retain(|c| c.id != id);
        if l.len() == before {
            return Err(format!("no saved command '{id}'"));
        }
        self.write(&l)
    }

    fn write(&self, l: &[CommandSpec]) -> Result<(), String> {
        if let Some(d) = self.path.parent() {
            let _ = std::fs::create_dir_all(d);
        }
        let tmp = self.path.with_extension("json.tmp");
        let s = serde_json::to_string_pretty(l).map_err(|e| e.to_string())?;
        std::fs::write(&tmp, s).and_then(|_| std::fs::rename(&tmp, &self.path)).map_err(|e| format!("could not save commands: {e}"))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum State {
    Running,
    Finished,
}

impl State {
    fn word(self) -> &'static str {
        match self {
            State::Running => "RUNNING",
            State::Finished => "FINISHED",
        }
    }
}

struct Live {
    state: State,
    seq: i64,
    scrollback: VecDeque<u8>,
    dropped: u64,
    ended_at: Option<i64>,
    exit_code: Option<i32>,
    outcome: Option<String>,
    detail: Option<String>,
    stop: Option<&'static str>,
    /// Input budget: (window start, bytes in it).
    input: (i64, usize),
    /// A client typed: the terminal's own cursor queries are its to answer from then on.
    typed: bool,
}

/// One run.
pub struct Run {
    pub id: String,
    pub command_id: String,
    pub name: String,
    pub branch: Option<String>,
    pub started_at: i64,
    pub pid: Option<u32>,
    pub grace_ms: u64,
    live: Mutex<Live>,
    writer: Mutex<Option<Box<dyn Write + Send>>>,
    console: Mutex<Option<Console>>,
    process: Option<Arc<Process>>,
    job: Option<Job>,
    /// Rendered secret files to delete and the preview worktree to remove when the run ends.
    cleanup: Mutex<Cleanup>,
}

#[derive(Default)]
struct Cleanup {
    files: Vec<PathBuf>,
    preview: Option<prep::Prepared>,
}

impl Run {
    fn record(&self) -> Value {
        let l = self.live.lock().unwrap();
        json!({
            "runId": self.id, "commandId": self.command_id, "state": l.state.word(), "startedAt": self.started_at,
            "pid": self.pid, "lastSequence": l.seq, "name": self.name, "terminal": true, "interactive": true, "branch": self.branch,
        })
    }

    fn summary(&self) -> Value {
        let l = self.live.lock().unwrap();
        json!({
            "runId": self.id, "commandId": self.command_id, "name": self.name, "state": l.state.word(), "startedAt": self.started_at,
            "endedAt": l.ended_at, "exitCode": l.exit_code, "outcome": l.outcome, "terminal": true, "interactive": true, "branch": self.branch,
        })
    }

    fn finished(&self) -> bool {
        self.live.lock().unwrap().state == State::Finished
    }

    fn kill_tree(&self) {
        match (&self.job, &self.process) {
            (Some(j), _) => j.kill(),
            (None, Some(p)) => p.kill(),
            _ => {}
        }
    }
}

/// The runs of this daemon.
pub struct RunManager {
    pub commands: CommandStore,
    pub limits: RunLimits,
    runs: Mutex<VecDeque<Arc<Run>>>,
    bus: EventBus,
    /// Where a command with no cwd runs: its project's folder, else this.
    default_cwd: PathBuf,
    project_dir: Arc<dyn Fn(&str) -> Option<PathBuf> + Send + Sync>,
    daemon: Arc<Daemon>,
    ext: OnceLock<prep::Extras>,
}

fn env_of(ty: &str, data: String) -> Envelope {
    Envelope::new(ty, data)
}

/// Windows: the line runs through `cmd.exe /d /c` so `.cmd`/`.bat` shims and PATH lookups work as in a shell.
fn command_for(argv: &[String], cwd: PathBuf, env: Vec<(String, String)>) -> pty::Command {
    #[cfg(windows)]
    {
        let line = argv.iter().map(|a| pty::quote_arg(a)).collect::<Vec<_>>().join(" ");
        let comspec = std::env::var("ComSpec").unwrap_or_else(|_| r"C:\Windows\System32\cmd.exe".into());
        pty::Command { program: comspec, args: vec!["/d".into(), "/c".into(), line], cwd, env, env_remove: vec![] }
    }
    #[cfg(not(windows))]
    {
        pty::Command { program: argv[0].clone(), args: argv[1..].to_vec(), cwd, env, env_remove: vec![] }
    }
}

/// Why a session's live process is rogue: its session ended and the process stayed. A live session's own harness
/// process is never rogue by idleness (Kotlin `ProcessWatch.rogueReason`).
fn rogue_reason(s: &infantry_protocol::session::SessionRecord) -> Option<String> {
    (s.is_process_alive && s.state == infantry_protocol::session::SessionState::Exited).then(|| "session ended (EXITED)".to_string())
}

/// Splits a shell line into argv (quotes and backslash-escaped quotes respected), for the tools' `command`.
pub fn split_line(line: &str) -> Vec<String> {
    let (mut out, mut cur, mut quote, mut any) = (Vec::new(), String::new(), None::<char>, false);
    let mut chars = line.chars().peekable();
    while let Some(c) = chars.next() {
        match (quote, c) {
            (Some(q), c) if c == q => quote = None,
            (Some(_), '\\') if chars.peek().is_some_and(|n| *n == '"') => cur.push(chars.next().unwrap()),
            (Some(_), c) => cur.push(c),
            (None, '"' | '\'') => {
                quote = Some(c);
                any = true;
            }
            (None, c) if c.is_whitespace() => {
                if any || !cur.is_empty() {
                    out.push(std::mem::take(&mut cur));
                    any = false;
                }
            }
            (None, c) => cur.push(c),
        }
    }
    if any || !cur.is_empty() {
        out.push(cur);
    }
    out
}

impl RunManager {
    pub fn new(
        state_dir: &Path,
        default_cwd: PathBuf,
        bus: EventBus,
        daemon: Arc<Daemon>,
        project_dir: Arc<dyn Fn(&str) -> Option<PathBuf> + Send + Sync>,
    ) -> Arc<RunManager> {
        Arc::new(RunManager {
            commands: CommandStore::open(state_dir),
            limits: RunLimits::default(),
            runs: Mutex::new(VecDeque::new()),
            bus,
            default_cwd,
            project_dir,
            daemon,
            ext: OnceLock::new(),
        })
    }

    /// The vault (run secrets, renders) and the project git view (branch runs), once composed.
    pub fn set_extras(&self, e: prep::Extras) {
        let _ = self.ext.set(e);
    }

    pub fn extras(&self) -> Option<&prep::Extras> {
        self.ext.get()
    }

    pub fn project_folder(&self, project: &str) -> Option<PathBuf> {
        (self.project_dir)(project)
    }

    pub fn default_folder(&self) -> PathBuf {
        self.default_cwd.clone()
    }

    /// The folder a command's relative paths start from; None = its project has no folder registered.
    fn command_root(&self, cmd: &CommandSpec) -> Option<PathBuf> {
        match cmd.project.as_deref() {
            Some(p) => (self.project_dir)(p),
            None => Some(self.default_cwd.clone()),
        }
    }

    fn undo_preview(&self, p: &Option<prep::Prepared>) {
        if let (Some(p), Some(ext)) = (p.as_ref().filter(|p| p.created), self.ext.get()) {
            prep::remove_preview(&ext.projects.git, &p.root, &p.dir);
        }
    }

    /// The secret env and rendered files for a run in `cwd`.
    fn secrets_for(&self, cmd: &CommandSpec, cwd: &Path) -> Result<(Vec<(String, String)>, Vec<PathBuf>), String> {
        if cmd.secrets.is_empty() && cmd.renders.is_empty() {
            return Ok((vec![], vec![]));
        }
        let ext = self.ext.get().ok_or("secrets are not available on this daemon")?;
        let pid = cmd.project.as_deref();
        let env = prep::secret_env(&ext.vault, &cmd.secrets, pid)?;
        let files = prep::render_all(&ext.vault, cwd, &cmd.renders, pid)?;
        Ok((env, files))
    }

    fn publish(&self, ty: &str, data: String) {
        self.bus.publish(env_of(ty, data));
    }

    pub fn commands_json(&self, project: Option<&str>) -> String {
        let list: Vec<CommandSpec> =
            self.commands.list(project).into_iter().map(|c| CommandSpec { rooted: self.command_root(&c).is_some_and(|d| d.is_dir()), ..c }).collect();
        serde_json::to_string(&list).unwrap_or_else(|_| "[]".into())
    }

    /// Saves and pushes the new list to every client.
    pub fn save(&self, req: CommandSave) -> Result<CommandSpec, String> {
        let s = self.commands.save(req)?;
        self.publish(f::COMMANDS, self.commands_json(None));
        Ok(s)
    }

    pub fn delete(&self, id: &str) -> Result<(), String> {
        self.commands.delete(id)?;
        self.publish(f::COMMANDS, self.commands_json(None));
        Ok(())
    }

    fn find(&self, run_id: &str) -> Option<Arc<Run>> {
        self.runs.lock().unwrap().iter().find(|r| r.id == run_id).cloned()
    }

    /// Runs, newest first.
    pub fn list(&self) -> Vec<Value> {
        self.runs.lock().unwrap().iter().rev().map(|r| r.summary()).collect()
    }

    /// `run_start`: the command in a new pseudo-terminal; the `run` record (also pushed).
    pub fn start(self: &Arc<Self>, command_id: &str, branch: Option<String>) -> Result<Value, String> {
        let cmd = self.commands.get(command_id).ok_or_else(|| format!("no saved command '{command_id}'"))?;
        self.start_spec(cmd, branch)
    }

    /// SECRETS-3: `run_start {"argv", "cwd"?, "secrets"?, "project"?}`: an unsaved command (`infantry run --secret`).
    /// The daemon spawns it and injects the secret fields; no value ever goes back to the caller.
    pub fn start_inline(self: &Arc<Self>, argv: Vec<String>, cwd: Option<String>, secrets: Vec<String>, project: Option<String>) -> Result<Value, String> {
        if argv.first().is_none_or(|a| a.trim().is_empty()) {
            return Err("an inline run needs argv (the command words)".into());
        }
        let cwd = cwd.filter(|c| !c.trim().is_empty());
        if cwd.as_deref().is_some_and(|c| !Path::new(c).is_absolute()) {
            return Err("an inline run's cwd must be an absolute folder".into());
        }
        let name: String = argv.join(" ").chars().take(80).collect();
        let cmd = CommandSpec {
            id: "inline".into(),
            name,
            cwd,
            argv,
            grace_millis: default_grace(),
            secrets: dedup(secrets),
            project,
            rooted: true,
            renders: Vec::new(),
            detached: false,
            branch: false,
        };
        self.start_spec(cmd, None)
    }

    fn start_spec(self: &Arc<Self>, cmd: CommandSpec, branch: Option<String>) -> Result<Value, String> {
        let command_id = cmd.id.as_str();
        let root = self.command_root(&cmd).ok_or_else(|| match &cmd.project {
            Some(p) => format!("project '{p}' has no folder registered; set the project folder first"),
            None => "the command belongs to no project".to_string(),
        })?;
        let branch = branch.map(|b| b.trim().to_string()).filter(|b| !b.is_empty());
        let (mut argv, mut cwd_text, mut preview, mut base) = (cmd.argv.clone(), cmd.cwd.clone(), None, root.clone());
        if cmd.branch {
            let b = branch.clone().ok_or_else(|| format!("command '{command_id}' runs on a branch; pass \"branch\" (infantry_run_branches lists them)"))?;
            let ext = self.ext.get().ok_or("branch runs are not available on this daemon")?;
            let pid = cmd.project.as_deref().ok_or("a branch command needs a project")?;
            let prepared = prep::prepare(&ext.projects, pid, &root, &b)?;
            argv = argv.iter().map(|a| prep::substitute(a, &prepared.dir, &b, &root)).collect();
            cwd_text = cwd_text.map(|c| prep::substitute(&c, &prepared.dir, &b, &root));
            base = prepared.dir.clone();
            preview = Some(prepared);
        } else if branch.is_some() {
            return Err(format!("command '{command_id}' does not run on a branch (its cwd and argv name no {} or {})", prep::WORKTREE, prep::BRANCH));
        }
        let cwd = match cwd_text.as_deref().map(PathBuf::from) {
            Some(p) if p.is_absolute() => p,
            Some(rel) => base.join(rel),
            None => base.clone(),
        };
        if !cwd.is_dir() {
            self.undo_preview(&preview);
            return Err(format!("{} is not a folder", cwd.display()));
        }
        let (env, files) = self.secrets_for(&cmd, &cwd).inspect_err(|_| self.undo_preview(&preview))?;
        let mut child = match pty::spawn(&command_for(&argv, cwd, env), pty::DEFAULT_ROWS, pty::DEFAULT_COLS) {
            Ok(c) => c,
            Err(e) => {
                files.iter().for_each(|f| drop(std::fs::remove_file(f)));
                self.undo_preview(&preview);
                return Err(format!("could not start: {e}"));
            }
        };
        let job = Job::new().ok().filter(|j| j.assign(child.pid).is_ok());
        let reader = child.reader.take();
        let run = Arc::new(Run {
            id: format!("run-{}", &uuid::Uuid::new_v4().to_string()[..8]),
            command_id: cmd.id.clone(),
            name: cmd.name.clone(),
            branch: branch.filter(|_| cmd.branch),
            started_at: now_ms(),
            pid: Some(child.pid),
            grace_ms: if cmd.grace_millis > 0 { cmd.grace_millis as u64 } else { self.limits.grace_ms },
            live: Mutex::new(Live {
                state: State::Running,
                seq: 0,
                scrollback: VecDeque::new(),
                dropped: 0,
                ended_at: None,
                exit_code: None,
                outcome: None,
                detail: None,
                stop: None,
                input: (0, 0),
                typed: false,
            }),
            writer: Mutex::new(Some(child.writer)),
            console: Mutex::new(child.console.take()),
            process: Some(Arc::new(child.process)),
            job,
            cleanup: Mutex::new(Cleanup { files, preview }),
        });
        {
            let mut runs = self.runs.lock().unwrap();
            runs.push_back(run.clone());
            // Finished runs beyond the history go, oldest first; running ones always stay.
            while runs.iter().filter(|r| r.finished()).count() > self.limits.history {
                let i = runs.iter().position(|r| r.finished()).unwrap();
                runs.remove(i);
            }
        }
        if let Some(r) = reader {
            let (m, run) = (self.clone(), run.clone());
            std::thread::Builder::new().name(format!("run-read-{}", run.id)).spawn(move || m.read_loop(&run, r)).map_err(|e| e.to_string())?;
        }
        {
            let (m, run) = (self.clone(), run.clone());
            std::thread::Builder::new().name(format!("run-wait-{}", run.id)).spawn(move || m.wait_loop(&run)).map_err(|e| e.to_string())?;
        }
        let rec = run.record();
        self.publish(f::RUN, rec.to_string());
        Ok(rec)
    }

    fn read_loop(&self, run: &Arc<Run>, mut r: Box<dyn Read + Send>) {
        let mut buf = vec![0u8; 16 * 1024];
        loop {
            let n = match r.read(&mut buf) {
                Ok(0) | Err(_) => break,
                Ok(n) => n,
            };
            let chunk = &buf[..n];
            let (seq, answer) = {
                let mut l = run.live.lock().unwrap();
                l.seq += 1;
                l.scrollback.extend(chunk.iter().copied());
                let over = l.scrollback.len().saturating_sub(self.limits.scrollback_bytes);
                if over > 0 {
                    l.scrollback.drain(..over);
                    l.dropped += over as u64;
                }
                // Until a client types, the daemon answers the console's cursor query so a run never waits on a viewer.
                (l.seq, !l.typed && chunk.windows(4).any(|w| w == b"\x1b[6n"))
            };
            if answer {
                if let Some(w) = run.writer.lock().unwrap().as_mut() {
                    let _ = w.write_all(b"\x1b[1;1R").and_then(|_| w.flush());
                }
            }
            let data = base64::engine::general_purpose::STANDARD.encode(chunk);
            self.publish(f::RUN_BYTES, json!({"runId": run.id, "sequence": seq, "data": data, "replay": false, "droppedBytes": 0}).to_string());
        }
    }

    fn wait_loop(&self, run: &Arc<Run>) {
        let Some(p) = run.process.clone() else { return };
        let code = loop {
            if let Some(c) = p.try_wait() {
                break c;
            }
            std::thread::sleep(Duration::from_millis(self.limits.poll_ms.max(1)));
        };
        // Let the last output through, then close the console (the reader sees EOF).
        std::thread::sleep(Duration::from_millis(200));
        run.console.lock().unwrap().take();
        run.writer.lock().unwrap().take();
        let body = {
            let mut l = run.live.lock().unwrap();
            let outcome = match (l.stop, code) {
                (Some(s), _) => s,
                (None, 0) => "SUCCEEDED",
                (None, _) => "FAILED",
            };
            l.state = State::Finished;
            l.ended_at = Some(now_ms());
            l.exit_code = Some(code);
            l.outcome = Some(outcome.into());
            l.detail = (outcome == "FAILED").then(|| format!("exited with code {code}"));
            json!({"runId": run.id, "exitCode": code, "outcome": outcome, "endedAt": l.ended_at, "detail": l.detail})
        };
        self.publish(f::RUN_FINISHED, body.to_string());
        self.publish(f::RUN, run.record().to_string());
        let c = std::mem::take(&mut *run.cleanup.lock().unwrap());
        c.files.iter().for_each(|f| drop(std::fs::remove_file(f)));
        self.undo_preview(&c.preview);
    }

    /// `run_stop`: Ctrl+C, then the tree after the grace; `force` kills it now.
    pub fn stop(self: &Arc<Self>, run_id: &str, force: bool) -> Result<(), String> {
        let run = self.find(run_id).ok_or_else(|| format!("no run '{run_id}'"))?;
        if run.finished() {
            return Ok(());
        }
        run.live.lock().unwrap().stop = Some(if force { "KILLED" } else { "STOPPED" });
        if force {
            run.kill_tree();
            return Ok(());
        }
        if let Some(w) = run.writer.lock().unwrap().as_mut() {
            let _ = w.write_all(b"\x03").and_then(|_| w.flush());
        }
        let grace = run.grace_ms;
        std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(grace));
            if !run.finished() {
                run.kill_tree();
            }
        });
        Ok(())
    }

    /// `run_attach`: the whole kept scrollback as one replay `run_bytes`.
    pub fn attach(&self, run_id: &str) -> Result<Value, String> {
        let run = self.find(run_id).ok_or_else(|| format!("no run '{run_id}'"))?;
        let l = run.live.lock().unwrap();
        let (a, b) = l.scrollback.as_slices();
        let bytes = [a, b].concat();
        let data = base64::engine::general_purpose::STANDARD.encode(bytes);
        Ok(json!({"runId": run.id, "sequence": l.seq, "data": data, "replay": true, "droppedBytes": l.dropped}))
    }

    pub fn resize(&self, run_id: &str, cols: i64, rows: i64) -> Result<(), String> {
        let run = self.find(run_id).ok_or_else(|| format!("no run '{run_id}'"))?;
        let cols = cols.clamp(1, self.limits.max_cols as i64) as u16;
        let rows = rows.clamp(1, self.limits.max_rows as i64) as u16;
        let r = match run.console.lock().unwrap().as_ref() {
            Some(c) => c.resize(rows, cols).map_err(|e| e.to_string()),
            None => Ok(()),
        };
        r
    }

    /// `run_input`: base64 bytes typed into the terminal, within the per-frame and per-second limits.
    pub fn input(&self, run_id: &str, data: &str) -> Result<(), String> {
        let run = self.find(run_id).ok_or_else(|| format!("no run '{run_id}'"))?;
        let bytes = base64::engine::general_purpose::STANDARD.decode(data.trim()).map_err(|_| "data is not base64".to_string())?;
        if bytes.len() > self.limits.input_max_frame_bytes {
            return Err(format!("one input frame holds at most {} bytes", self.limits.input_max_frame_bytes));
        }
        {
            let mut l = run.live.lock().unwrap();
            if l.state == State::Finished {
                return Err(format!("run '{run_id}' has finished"));
            }
            let now = now_ms();
            if now - l.input.0 >= 1_000 {
                l.input = (now, 0);
            }
            if l.input.1 + bytes.len() > self.limits.input_bytes_per_second {
                return Err(format!("input is limited to {} bytes a second", self.limits.input_bytes_per_second));
            }
            l.input.1 += bytes.len();
            l.typed = true;
        }
        let r = match run.writer.lock().unwrap().as_mut() {
            Some(w) => w.write_all(&bytes).and_then(|_| w.flush()).map_err(|e| e.to_string()),
            None => Err(format!("run '{run_id}' takes no input now")),
        };
        r
    }

    /// The processes this daemon started and still runs: live runs and session processes.
    pub fn processes(&self) -> Vec<Value> {
        let mut out: Vec<Value> = self
            .runs
            .lock()
            .unwrap()
            .iter()
            .filter(|r| !r.finished())
            .filter_map(|r| {
                let cmd: String = format!("run {}: {}", r.name, self.commands.get(&r.command_id).map(|c| c.argv.join(" ")).unwrap_or_default()).chars().take(80).collect();
                r.pid.map(|pid| json!({"pid": pid, "command": cmd, "sessionId": null, "startedAt": r.started_at, "memoryMb": mem::process_mb(pid), "rogue": false, "reason": null, "inferred": false, "cpuPercent": null, "stuck": null}))
            })
            .collect();
        for s in self.daemon.records() {
            if let (Some(pid), true) = (s.child_pid, s.is_process_alive) {
                let cmd: String = format!("{} session {}", s.provider, s.role.as_deref().unwrap_or("")).trim().chars().take(80).collect();
                let reason = rogue_reason(&s);
                out.push(json!({
                    "pid": pid, "command": cmd, "sessionId": s.id, "startedAt": s.last_turn_at, "memoryMb": u32::try_from(pid).ok().and_then(mem::process_mb),
                    "rogue": reason.is_some(), "reason": reason, "inferred": false, "cpuPercent": null, "stuck": null,
                }));
            }
        }
        out
    }

    pub fn report(&self) -> Value {
        let (free, total) = mem::machine_mb();
        json!({
            "freeMb": free, "totalMb": total, "floorMb": self.limits.floor_mb, "underPressure": free.is_some_and(|f| f < self.limits.floor_mb),
            "autoReap": false, "tracked": self.processes(), "unattributed": [],
        })
    }

    /// STATUS-LINE-1: one `host_stats` frame (free RAM, agent session processes' CPU) to every client.
    pub fn publish_host_stats(&self, cpu: &mut host_stats::CpuSampler) {
        let times: Vec<(u32, u64)> = self
            .daemon
            .records()
            .iter()
            .filter(|s| s.is_process_alive)
            .filter_map(|s| u32::try_from(s.child_pid?).ok())
            .filter_map(|pid| mem::cpu_time_ms(pid).map(|t| (pid, t)))
            .collect();
        let cores = std::thread::available_parallelism().map(|n| n.get() as u32).unwrap_or(1);
        let pct = cpu.sample(now_ms() as u64, &times, cores);
        let (free, total) = mem::machine_mb();
        self.publish(f::HOST_STATS, host_stats::body(free, total, times.len(), pct).to_string());
    }

    /// `processes_reap {op: reap_rogue}`: every rogue process (a session's process alive after its session ended).
    pub fn reap_rogue(self: &Arc<Self>) -> (Vec<i64>, Option<String>) {
        let (mut reaped, mut refused) = (Vec::new(), Vec::new());
        for s in self.daemon.records() {
            let (Some(pid), true) = (s.child_pid, s.is_process_alive) else { continue };
            if rogue_reason(&s).is_none() {
                continue;
            }
            match self.reap(pid) {
                Ok(p) => reaped.extend(p),
                Err(e) => refused.push(format!("pid {pid}: {e}")),
            }
        }
        (reaped, (!refused.is_empty()).then(|| refused.join("; ")))
    }

    /// `processes_reap` `{op: reap, pid}`: only a process this daemon tracks; a run is force-stopped, a session's process killed.
    pub fn reap(self: &Arc<Self>, pid: i64) -> Result<Vec<i64>, String> {
        let run = self.runs.lock().unwrap().iter().find(|r| !r.finished() && r.pid.map(i64::from) == Some(pid)).cloned();
        if let Some(r) = run {
            self.stop(&r.id.clone(), true)?;
            return Ok(vec![pid]);
        }
        let session = self.daemon.records().into_iter().find(|s| s.is_process_alive && s.child_pid == Some(pid));
        match session {
            Some(s) => {
                self.daemon.stop_session(&s.id, "processes_reap")?;
                Ok(vec![pid])
            }
            None => Err(format!("pid {pid} is not a process this daemon started")),
        }
    }

    /// Ends every run (the daemon is stopping).
    pub fn shutdown(&self) {
        for r in self.runs.lock().unwrap().iter() {
            if !r.finished() {
                r.kill_tree();
            }
        }
    }
}

/// The frame unit.
pub struct RunUnit {
    pub runs: Arc<RunManager>,
}

/// PREVIEW-BRANCH-1: `run_branches {"project"?}` -> `run_branches {"project", "branches": [{"name", "worktree"?}]}`.
pub const RUN_BRANCHES: &str = "run_branches";
const FRAMES: &[&str] = &[RUN_BRANCHES, 
    f::COMMAND_LIST,
    f::COMMAND_SAVE,
    f::COMMAND_DELETE,
    f::RUN_START,
    f::RUN_STOP,
    f::RUN_LIST,
    f::RUN_ATTACH,
    f::RUN_RESIZE,
    f::RUN_INPUT,
    f::PROCESSES,
    f::PROCESSES_REAP,
];

fn reply(env: &Envelope, ty: &str, data: String) -> Envelope {
    let mut e = Envelope::new(ty, data);
    e.request_id = env.request_id.clone();
    e
}

fn field<'a>(v: &'a Value, k: &str) -> &'a str {
    v[k].as_str().map(str::trim).unwrap_or("")
}

impl FrameUnit for RunUnit {
    fn name(&self) -> &'static str {
        "run"
    }

    fn frames(&self) -> &'static [&'static str] {
        FRAMES
    }

    fn handle(&self, env: &Envelope, _ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let ty = env.r#type.as_str();
        let rf = |m: String| Refusal::new(ty, m);
        let v: Value = serde_json::from_str(&env.data).unwrap_or(Value::Null);
        let m = &self.runs;
        match ty {
            f::COMMAND_LIST => Ok(vec![reply(env, f::COMMANDS, m.commands_json(v["project"].as_str()))]),
            RUN_BRANCHES => {
                let project = v["project"].as_str().map(str::trim).filter(|p| !p.is_empty()).map(String::from);
                let p = project.clone().ok_or_else(|| rf("expects a project".into()))?;
                let ext = m.extras().ok_or_else(|| rf("branches are not available on this daemon".into()))?;
                let rows = prep::branches(&ext.projects, &p).map_err(rf)?;
                let branches: Vec<Value> = rows.iter().map(|b| serde_json::json!({"name": b.name, "worktree": b.worktree})).collect();
                Ok(vec![reply(env, RUN_BRANCHES, serde_json::json!({"project": project, "branches": branches}).to_string())])
            }
            f::COMMAND_SAVE => {
                let req: CommandSave = serde_json::from_str(&env.data).map_err(|e| rf(format!("bad request ({e})")))?;
                m.save(req).map_err(rf)?;
                Ok(Vec::new())
            }
            f::COMMAND_DELETE => {
                let id = if v.is_object() { field(&v, "id").to_string() } else { env.data.trim().to_string() };
                m.delete(&id).map_err(rf)?;
                Ok(Vec::new())
            }
            f::RUN_START => {
                if v["argv"].is_array() {
                    let words = |k: &str| v[k].as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default();
                    let opt = |k: &str| v[k].as_str().map(str::trim).filter(|s| !s.is_empty()).map(String::from);
                    let rec = m.start_inline(words("argv"), opt("cwd"), words("secrets"), opt("project")).map_err(rf)?;
                    return Ok(vec![reply(env, f::RUN, rec.to_string())]);
                }
                let id = if v.is_object() { field(&v, "commandId").to_string() } else { env.data.trim().to_string() };
                let branch = v["branch"].as_str().map(String::from);
                let rec = m.start(&id, branch).map_err(rf)?;
                Ok(vec![reply(env, f::RUN, rec.to_string())])
            }
            f::RUN_STOP => {
                let mut w = env.data.split_whitespace();
                let id = w.next().unwrap_or("").to_string();
                let force = w.any(|x| x == "force");
                m.stop(&id, force).map_err(rf)?;
                Ok(Vec::new())
            }
            f::RUN_LIST => Ok(vec![reply(env, f::RUNS, Value::Array(m.list()).to_string())]),
            f::RUN_ATTACH => Ok(vec![reply(env, f::RUN_BYTES, m.attach(field(&v, "runId")).map_err(rf)?.to_string())]),
            f::RUN_RESIZE => {
                m.resize(field(&v, "runId"), v["cols"].as_i64().unwrap_or(0), v["rows"].as_i64().unwrap_or(0)).map_err(rf)?;
                Ok(Vec::new())
            }
            f::RUN_INPUT => {
                m.input(field(&v, "runId"), field(&v, "data")).map_err(rf)?;
                Ok(Vec::new())
            }
            f::PROCESSES | f::PROCESSES_REAP => {
                let op = v["op"].as_str().unwrap_or("list");
                let (reaped, refused) = match op {
                    "list" if ty == f::PROCESSES_REAP => return Err(rf("processes_reap only reaps: list with `processes`".into())),
                    "list" => (Vec::new(), None),
                    "reap" => match v["pid"].as_i64() {
                        Some(pid) => match m.reap(pid) {
                            Ok(r) => (r, None),
                            Err(e) => (Vec::new(), Some(e)),
                        },
                        None => return Err(rf("reap needs a pid".into())),
                    },
                    "reap_rogue" => m.reap_rogue(),
                    other => return Err(rf(format!("unknown op '{other}' (list, reap, reap_rogue)"))),
                };
                Ok(vec![reply(env, f::PROCESSES, json!({"report": m.report(), "reaped": reaped, "refused": refused}).to_string())])
            }
            _ => Err(rf("not a run frame".into())),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_shell_line_splits_like_a_shell() {
        assert_eq!(split_line(r#"npm run "dev server" -- --port 0"#), vec!["npm", "run", "dev server", "--", "--port", "0"]);
        assert_eq!(split_line(r#"echo "" 'a b'"#), vec!["echo", "", "a b"]);
    }

    #[test]
    fn an_inline_start_refuses_without_argv_or_with_a_relative_cwd() {
        let dir = std::env::temp_dir().join(format!("infantry-run-inline-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let daemon = Daemon::new(Arc::new(crate::harness::stub::Stub::default()), crate::sessions::Config::new(dir.clone(), dir.clone())).unwrap();
        let m = RunManager::new(&dir, dir.clone(), EventBus::default(), daemon, Arc::new(|_: &str| None));
        assert!(m.start_inline(vec![], None, vec![], None).unwrap_err().contains("argv"));
        assert!(m.start_inline(vec!["x".into()], Some("rel".into()), vec![], None).unwrap_err().contains("absolute"));
        // A secret with no vault composed is refused before anything spawns; nothing is saved to the Run tab list.
        assert!(m.start_inline(vec!["x".into()], None, vec!["db".into()], None).unwrap_err().contains("secrets are not available"));
        assert_eq!(m.commands_json(None), "[]");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn commands_upsert_and_delete_in_the_state_dir() {
        let dir = std::env::temp_dir().join(format!("infantry-run-test-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let s = CommandStore::open(&dir);
        assert!(s.save(CommandSave { id: "bad id".into(), argv: vec!["x".into()], ..Default::default() }).is_err());
        s.save(CommandSave { id: "dev".into(), argv: vec!["npm".into(), "run".into()], ..Default::default() }).unwrap();
        let again = s.save(CommandSave { id: "dev".into(), name: Some("Dev".into()), ..Default::default() }).unwrap();
        assert_eq!((again.name.as_str(), again.argv.len(), again.grace_millis), ("Dev", 2, 5_000));
        assert_eq!(CommandStore::open(&dir).list(None).len(), 1);
        s.delete("dev").unwrap();
        assert!(s.delete("dev").is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_run_streams_its_terminal_and_finishes_once() {
        let dir = std::env::temp_dir().join(format!("infantry-run-pty-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let daemon = Daemon::new(Arc::new(crate::harness::stub::Stub::default()), crate::sessions::Config::new(dir.clone(), dir.clone())).unwrap();
        let bus = EventBus::default();
        let mut rx = bus.subscribe();
        let m = RunManager::new(&dir, dir.clone(), bus, daemon, Arc::new(|_: &str| None));
        #[cfg(windows)]
        let argv = vec!["echo".to_string(), "run-ok".to_string()];
        #[cfg(not(windows))]
        let argv = vec!["sh".to_string(), "-c".to_string(), "echo run-ok".to_string()];
        m.save(CommandSave { id: "echo".into(), argv, ..Default::default() }).unwrap();
        let rec = m.start("echo", None).unwrap();
        let run_id = rec["runId"].as_str().unwrap().to_string();
        let (mut seen, mut finished) = (String::new(), 0);
        let deadline = std::time::Instant::now() + Duration::from_secs(20);
        while std::time::Instant::now() < deadline && finished == 0 {
            match rx.try_recv() {
                Ok(e) if e.r#type == f::RUN_BYTES => {
                    let v: Value = serde_json::from_str(&e.data).unwrap();
                    seen.push_str(&String::from_utf8_lossy(&base64::engine::general_purpose::STANDARD.decode(v["data"].as_str().unwrap()).unwrap()));
                }
                Ok(e) if e.r#type == f::RUN_FINISHED => {
                    finished += 1;
                    assert_eq!(serde_json::from_str::<Value>(&e.data).unwrap()["outcome"], "SUCCEEDED");
                }
                Ok(_) => {}
                Err(_) => std::thread::sleep(Duration::from_millis(20)),
            }
        }
        assert_eq!(finished, 1, "the run finished");
        assert!(seen.contains("run-ok"), "terminal bytes streamed: {seen:?}");
        assert_eq!(m.list()[0]["state"], "FINISHED");
        let replay = m.attach(&run_id).unwrap();
        assert_eq!(replay["replay"], true);
        assert!(m.input(&run_id, "eA==").is_err(), "a finished run takes no input");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
