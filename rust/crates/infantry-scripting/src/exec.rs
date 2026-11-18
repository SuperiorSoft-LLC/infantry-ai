// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The process bridge (`infantry_exec`, `infantry_exec_status`; Lua `ih.exec` / `ih.cli`).
//!
//! Runs a command line as the caller and answers `{exitCode, durationMs, stdoutTail, stderrTail, timedOut}`.
//! Never through a shell (an argv runs directly, a string is split on spaces/quotes); a timeout kills the
//! process tree. `background=true` answers a run id at once; `exec.done` announces its end on the bus and
//! `exec.tick` reports each live run. `serial=<key>` runs one command per key at a time, in arrival order.
//! Off by default: setting `scripting.exec.enabled`; allow-list `scripting.exec.allow`; roles `scripting.exec.roles`.
use crate::host::{ToolInvoker, ToolResult};
use infantry_core::bus::EventBus;
use serde_json::{json, Map, Value};
use std::collections::{HashMap, VecDeque};
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Condvar, Mutex};
use std::time::{Duration, Instant};

pub const EXEC: &str = "infantry_exec";
pub const STATUS: &str = "infantry_exec_status";
const MAX_RUNS: usize = 100;
const MAX_NEW_LINES: usize = 500;
const MAX_LINE: usize = 1000;
const MAX_STATE: usize = 4000;

pub type Config = Arc<dyn Fn(&str) -> Option<String> + Send + Sync>;

/// The guard and limits (settings `scripting.exec.*`); a bad value falls back to the default.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ExecSettings {
    pub enabled: bool,
    pub allow: Vec<String>,
    pub roles: Vec<String>,
    pub default_timeout_sec: u64,
    pub max_timeout_sec: u64,
    pub tail_chars: usize,
}

impl ExecSettings {
    pub fn read(config: &dyn Fn(&str) -> Option<String>) -> Self {
        let num = |k: &str, lo: u64, hi: u64, d: u64| config(k).and_then(|v| v.trim().parse::<u64>().ok()).filter(|v| (lo..=hi).contains(v)).unwrap_or(d);
        let max = num("scripting.exec.maxTimeoutSec", 1, 3600, 600);
        ExecSettings {
            enabled: config("scripting.exec.enabled").is_some_and(|v| v.trim().eq_ignore_ascii_case("true")),
            allow: list(config("scripting.exec.allow").as_deref()),
            roles: list(config("scripting.exec.roles").as_deref()),
            default_timeout_sec: num("scripting.exec.defaultTimeoutSec", 1, 3600, 60).min(max),
            max_timeout_sec: max,
            tail_chars: num("scripting.exec.tailChars", 100, 1_000_000, 8000) as usize,
        }
    }
}

/// Seconds between exec.tick events: setting scripting.exec.tickSec, default 10, 1..3600.
pub fn tick_sec(config: &dyn Fn(&str) -> Option<String>) -> u64 {
    config("scripting.exec.tickSec").and_then(|v| v.trim().parse().ok()).filter(|v| (1..=3600).contains(v)).unwrap_or(10)
}

/// A JSON array of strings, or one entry per line / comma-separated.
pub fn list(text: Option<&str>) -> Vec<String> {
    let t = text.unwrap_or("").trim();
    if t.is_empty() {
        return Vec::new();
    }
    if t.starts_with('[') {
        if let Ok(Value::Array(a)) = serde_json::from_str::<Value>(t) {
            return a.iter().filter_map(|v| v.as_str().map(str::trim).filter(|s| !s.is_empty()).map(str::to_string)).collect();
        }
    }
    t.split(['\n', ',']).map(str::trim).filter(|s| !s.is_empty()).map(str::to_string).collect()
}

/// Splits a command line: whitespace separates, single or double quotes group. No shell expansion.
pub fn split_command(line: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut quote: Option<char> = None;
    let mut has = false;
    for c in line.chars() {
        match quote {
            Some(q) => {
                if c == q {
                    quote = None
                } else {
                    cur.push(c)
                }
            }
            None if c == '"' || c == '\'' => {
                quote = Some(c);
                has = true;
            }
            None if c.is_whitespace() => {
                if has || !cur.is_empty() {
                    out.push(std::mem::take(&mut cur));
                    has = false;
                }
            }
            None => cur.push(c),
        }
    }
    if has || !cur.is_empty() {
        out.push(cur);
    }
    out
}

/// `re:<regex>` must match the whole command line; anything else is a prefix.
pub fn allow_matches(pattern: &str, line: &str) -> bool {
    match pattern.strip_prefix("re:") {
        Some(r) => regex::Regex::new(&format!("^(?:{r})$")).map(|re| re.is_match(line)).unwrap_or(false),
        None => line.starts_with(pattern),
    }
}

#[derive(Default)]
struct Tail {
    buf: Mutex<String>,
    total: AtomicU64,
    cap: usize,
}

impl Tail {
    fn new(cap: usize) -> Arc<Self> {
        Arc::new(Tail { cap, ..Default::default() })
    }
    fn push(&self, s: &str) {
        self.total.fetch_add(s.len() as u64, Ordering::Relaxed);
        let mut b = self.buf.lock().unwrap();
        b.push_str(s);
        if b.len() > self.cap * 2 {
            let cut = b.len() - self.cap;
            let cut = (cut..b.len()).find(|i| b.is_char_boundary(*i)).unwrap_or(b.len());
            b.drain(..cut);
        }
    }
    fn text(&self) -> String {
        let b = self.buf.lock().unwrap();
        let n = b.chars().count();
        if n > self.cap {
            b.chars().skip(n - self.cap).collect()
        } else {
            b.clone()
        }
    }
}

struct Run {
    id: String,
    command: String,
    script: String,
    session: String,
    project: Option<String>,
    label: String,
    started: Instant,
    state: Mutex<String>,
    out: Mutex<Option<Arc<Tail>>>,
    err: Mutex<Option<Arc<Tail>>>,
    result: Mutex<Option<Map<String, Value>>>,
    new_lines: Mutex<VecDeque<String>>,
    job_state: Mutex<Option<Value>>,
    last_tick_bytes: AtomicU64,
    ended_ms: AtomicU64,
}

impl Run {
    fn on_line(&self, line: &str) {
        let mut q = self.new_lines.lock().unwrap();
        q.push_back(line.chars().take(MAX_LINE).collect());
        while q.len() > MAX_NEW_LINES {
            q.pop_front();
        }
    }
}

/// One FIFO lane per serial key: tickets handed out in arrival order, served in order.
#[derive(Default)]
struct Lane {
    state: Mutex<(u64, u64)>, // (next ticket, now serving)
    cv: Condvar,
}

/// The process bridge. Share as `Arc<ExecTools>`.
pub struct ExecTools {
    config: Config,
    /// The project's root directory (the parent of `.infantry/`).
    root_of: Arc<dyn Fn(&str) -> Option<PathBuf> + Send + Sync>,
    bus: Option<EventBus>,
    log: Arc<dyn Fn(&str) + Send + Sync>,
    runs: Mutex<HashMap<String, Arc<Run>>>,
    lanes: Mutex<HashMap<String, Arc<Lane>>>,
    seq: AtomicU64,
}

fn s<'a>(a: &'a Map<String, Value>, k: &str) -> Option<&'a str> {
    a.get(k).and_then(Value::as_str).map(str::trim).filter(|v| !v.is_empty())
}

impl ExecTools {
    pub fn new(config: Config, root_of: Arc<dyn Fn(&str) -> Option<PathBuf> + Send + Sync>, bus: Option<EventBus>, log: Arc<dyn Fn(&str) + Send + Sync>) -> Self {
        ExecTools { config, root_of, bus, log, runs: Mutex::new(HashMap::new()), lanes: Mutex::new(HashMap::new()), seq: AtomicU64::new(0) }
    }

    fn emit(&self, name: &str, payload: Value) {
        if let Some(b) = &self.bus {
            b.emit(name, payload);
        }
    }

    /// `infantry_exec`.
    pub fn exec(self: &Arc<Self>, a: &Map<String, Value>, inv: &ToolInvoker) -> ToolResult {
        match self.run_exec(a, inv) {
            Ok(r) => r,
            Err(why) => ToolResult::err(format!("{EXEC} refused: {why}")),
        }
    }

    fn run_exec(self: &Arc<Self>, a: &Map<String, Value>, inv: &ToolInvoker) -> Result<ToolResult, String> {
        let st = ExecSettings::read(&*self.config);
        if !st.enabled {
            return Err("the process bridge is off. Enable it with the setting scripting.exec.enabled = true (optionally limit it with scripting.exec.allow and scripting.exec.roles)".into());
        }
        let role = inv.role.clone().unwrap_or_default();
        if !st.roles.is_empty() && !st.roles.iter().any(|r| r.eq_ignore_ascii_case(&role)) {
            return Err(format!("role '{}' may not exec (scripting.exec.roles = {})", if role.is_empty() { "none" } else { &role }, st.roles.join(", ")));
        }
        let argv: Vec<String> = match a.get("command") {
            Some(Value::Array(c)) => c.iter().map(|v| v.as_str().map(str::to_string).ok_or("command list entries must be strings")).collect::<Result<_, _>>()?,
            Some(Value::String(c)) => split_command(c),
            _ => return Err("command is required (a list or a string)".into()),
        };
        if argv.is_empty() || argv[0].trim().is_empty() {
            return Err("command is empty".into());
        }
        let line = argv.join(" ");
        if !st.allow.is_empty() && !st.allow.iter().any(|p| allow_matches(p, &line)) {
            return Err("command line is not in the allow-list scripting.exec.allow".into());
        }
        let project = s(a, "project").map(str::to_string).or_else(|| inv.project_id.clone());
        let root = project.as_deref().and_then(|p| (self.root_of)(p));
        let cwd = match s(a, "cwd") {
            None => root.ok_or("no project to take a working directory from (pass cwd or project)")?,
            Some(c) if Path::new(c).is_absolute() => PathBuf::from(c),
            Some(c) => root.ok_or("a relative cwd needs a project")?.join(c),
        };
        if !cwd.is_dir() {
            return Err(format!("cwd is not a directory: {}", cwd.display()));
        }
        let env: Vec<(String, String)> = a.get("env").and_then(Value::as_object).map(|o| o.iter().map(|(k, v)| (k.clone(), v.as_str().unwrap_or_default().to_string())).collect()).unwrap_or_default();
        let asked = a.get("timeoutSec").and_then(Value::as_f64).map(|v| v as i64);
        let timeout = asked.map(|v| v.max(1) as u64).unwrap_or(st.default_timeout_sec).clamp(1, st.max_timeout_sec);
        let stdin = a.get("stdin").and_then(Value::as_str).map(str::to_string);
        let script = a.get("script").and_then(Value::as_str).unwrap_or_default().to_string();
        let background = a.get("background").and_then(Value::as_bool) == Some(true);
        let serial = s(a, "serial").map(str::to_string);
        let n = self.seq.fetch_add(1, Ordering::Relaxed);
        let id = format!("{:08x}", (crate::runlog::now_ms() ^ (n.wrapping_mul(0x9E37_79B9))) as u32);
        let label = if script.is_empty() { argv[0].rsplit(['/', '\\']).next().unwrap_or(&argv[0]).to_string() } else { script.clone() };
        let run = Arc::new(Run {
            id: id.clone(),
            command: line,
            script,
            session: inv.session_id.clone().unwrap_or_default(),
            project,
            label,
            started: Instant::now(),
            state: Mutex::new(if serial.is_some() { "queued".into() } else { "running".into() }),
            out: Mutex::new(None),
            err: Mutex::new(None),
            result: Mutex::new(None),
            new_lines: Mutex::new(VecDeque::new()),
            job_state: Mutex::new(None),
            last_tick_bytes: AtomicU64::new(0),
            ended_ms: AtomicU64::new(0),
        });
        let me = self.clone();
        let r2 = run.clone();
        let tail = st.tail_chars;
        let work = move || {
            let _turn = serial.as_deref().map(|k| me.enter_lane(k));
            *r2.state.lock().unwrap() = "running".into();
            let res = me.execute(&r2, &argv, &cwd, &env, timeout, stdin.as_deref(), tail, &role, background);
            if let Some((k, _)) = &_turn {
                me.leave_lane(k);
            }
            res
        };
        if !background {
            return Ok(ToolResult::ok(Value::Object(work()).to_string()));
        }
        self.prune();
        self.runs.lock().unwrap().insert(id.clone(), run.clone());
        std::thread::Builder::new().name("exec-bridge".into()).spawn(move || drop(work())).map_err(|e| e.to_string())?;
        Ok(ToolResult::ok(json!({"id": id, "state": run.state.lock().unwrap().clone()}).to_string()))
    }

    fn enter_lane(&self, key: &str) -> (String, ()) {
        let lane = self.lanes.lock().unwrap().entry(key.into()).or_default().clone();
        let mut st = lane.state.lock().unwrap();
        let mine = st.0;
        st.0 += 1;
        while st.1 != mine {
            st = lane.cv.wait(st).unwrap();
        }
        (key.to_string(), ())
    }

    fn leave_lane(&self, key: &str) {
        let lane = self.lanes.lock().unwrap().get(key).cloned();
        if let Some(lane) = lane {
            let mut st = lane.state.lock().unwrap();
            st.1 += 1;
            lane.cv.notify_all();
            if st.0 == st.1 {
                drop(st);
                let mut lanes = self.lanes.lock().unwrap();
                if lanes.get(key).is_some_and(|l| Arc::ptr_eq(l, &lane)) {
                    let s = lane.state.lock().unwrap();
                    if s.0 == s.1 {
                        lanes.remove(key);
                    }
                }
            }
        }
    }

    #[allow(clippy::too_many_arguments)]
    fn execute(&self, run: &Arc<Run>, argv: &[String], cwd: &Path, env: &[(String, String)], timeout_sec: u64, stdin: Option<&str>, tail: usize, role: &str, background: bool) -> Map<String, Value> {
        let started = Instant::now();
        let out = Tail::new(tail);
        let err = Tail::new(tail);
        *run.out.lock().unwrap() = Some(out.clone());
        *run.err.lock().unwrap() = Some(err.clone());
        let mut exit: i64 = -1;
        let mut timed_out = false;
        let mut failure: Option<String> = None;
        let mut cmd = Command::new(&argv[0]);
        cmd.args(&argv[1..]).current_dir(cwd).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::piped());
        if std::env::var_os("INFANTRY_PROGRESS").is_none() {
            cmd.env("INFANTRY_PROGRESS", "1");
        }
        for (k, v) in env {
            cmd.env(k, v);
        }
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW: never a visible console
        }
        match cmd.spawn() {
            Err(e) => failure = Some(e.to_string().chars().take(200).collect()),
            Ok(mut child) => {
                let pump = |src: Option<Box<dyn Read + Send>>, t: Arc<Tail>, r: Arc<Run>| {
                    std::thread::spawn(move || {
                        let Some(mut src) = src else { return };
                        let mut buf = [0u8; 4096];
                        let mut line = String::new();
                        while let Ok(n) = src.read(&mut buf) {
                            if n == 0 {
                                break;
                            }
                            let chunk = String::from_utf8_lossy(&buf[..n]).to_string();
                            t.push(&chunk);
                            for c in chunk.chars() {
                                if c == '\n' {
                                    r.on_line(line.trim_end_matches('\r'));
                                    line.clear();
                                } else if line.len() < MAX_LINE * 2 {
                                    line.push(c);
                                }
                            }
                        }
                        if !line.is_empty() {
                            r.on_line(&line);
                        }
                    })
                };
                let h1 = pump(child.stdout.take().map(|s| Box::new(s) as Box<dyn Read + Send>), out.clone(), run.clone());
                let h2 = pump(child.stderr.take().map(|s| Box::new(s) as Box<dyn Read + Send>), err.clone(), run.clone());
                if let Some(mut w) = child.stdin.take() {
                    let data = stdin.map(str::to_string);
                    std::thread::spawn(move || {
                        if let Some(d) = data {
                            let _ = w.write_all(d.as_bytes());
                        }
                    });
                }
                let deadline = Instant::now() + Duration::from_secs(timeout_sec);
                loop {
                    match child.try_wait() {
                        Ok(Some(st)) => {
                            exit = st.code().map(i64::from).unwrap_or(-1);
                            break;
                        }
                        Ok(None) if Instant::now() >= deadline => {
                            timed_out = true;
                            kill_tree(&mut child);
                            break;
                        }
                        Ok(None) => std::thread::sleep(Duration::from_millis(20)),
                        Err(e) => {
                            failure = Some(e.to_string());
                            break;
                        }
                    }
                }
                let join_by = Instant::now() + Duration::from_secs(2);
                for h in [h1, h2] {
                    while !h.is_finished() && Instant::now() < join_by {
                        std::thread::sleep(Duration::from_millis(10));
                    }
                }
            }
        }
        let ms = started.elapsed().as_millis() as u64;
        let mut result = Map::new();
        result.insert("exitCode".into(), json!(exit));
        result.insert("durationMs".into(), json!(ms));
        result.insert("stdoutTail".into(), json!(out.text()));
        result.insert("stderrTail".into(), json!(match &failure {
            Some(f) => format!("could not start: {f}"),
            None => err.text(),
        }));
        result.insert("timedOut".into(), json!(timed_out));
        (self.log)(&format!(
            "exec by session={} role={} script={} cmd=[{}] exit={exit} timedOut={timed_out} ms={ms}",
            if run.session.is_empty() { "-" } else { &run.session },
            if role.is_empty() { "-" } else { role },
            if run.script.is_empty() { "-" } else { &run.script },
            run.command.chars().take(200).collect::<String>()
        ));
        if background {
            *run.result.lock().unwrap() = Some(result.clone());
            run.ended_ms.store(run.started.elapsed().as_millis() as u64, Ordering::Relaxed);
            *run.state.lock().unwrap() = if timed_out { "timeout".into() } else { "done".into() };
            let mut p = json!({"id": run.id, "exitCode": exit, "script": run.script, "session": run.session, "timedOut": timed_out});
            if let Some(pr) = &run.project {
                p["projectId"] = json!(pr);
            }
            self.emit("exec.done", p);
        }
        result
    }

    /// `infantry_exec_status`: no id = the job list; with id = that run (and `state` stores a small object for its next tick).
    pub fn status(&self, a: &Map<String, Value>) -> ToolResult {
        let id = a.get("id").and_then(Value::as_str).unwrap_or_default();
        if id.trim().is_empty() {
            return ToolResult::ok(self.job_list().to_string());
        }
        let Some(r) = self.runs.lock().unwrap().get(id).cloned() else { return ToolResult::err(format!("{STATUS}: no run '{id}'")) };
        if let Some(st) = a.get("state").filter(|v| v.is_object()) {
            if st.to_string().len() > MAX_STATE {
                return ToolResult::err(format!("{STATUS}: state is over {MAX_STATE} characters"));
            }
            *r.job_state.lock().unwrap() = Some(st.clone());
        }
        let mut o = Map::new();
        o.insert("id".into(), json!(r.id));
        o.insert("state".into(), json!(r.state.lock().unwrap().clone()));
        if let Some(res) = r.result.lock().unwrap().clone() {
            o.extend(res);
        }
        ToolResult::ok(Value::Object(o).to_string())
    }

    fn job_list(&self) -> Value {
        let mut runs: Vec<Arc<Run>> = self.runs.lock().unwrap().values().cloned().collect();
        runs.sort_by_key(|r| std::cmp::Reverse(r.started));
        Value::Array(
            runs.iter()
                .map(|r| {
                    let ended = r.ended_ms.load(Ordering::Relaxed);
                    let mut o = json!({"id": r.id, "label": r.label, "session": r.session, "state": r.state.lock().unwrap().clone(),
                        "elapsedMs": if ended > 0 { ended } else { r.started.elapsed().as_millis() as u64 }});
                    if let Some(js) = r.job_state.lock().unwrap().clone() {
                        o["jobState"] = js;
                    }
                    o
                })
                .collect(),
        )
    }

    /// Publishes `exec.tick` for each live background run. Call every `tick_sec()` seconds.
    pub fn tick(&self) {
        let runs: Vec<Arc<Run>> = self.runs.lock().unwrap().values().cloned().collect();
        for r in runs {
            if *r.state.lock().unwrap() != "running" {
                continue;
            }
            let o = r.out.lock().unwrap().clone();
            let e = r.err.lock().unwrap().clone();
            let total = o.as_ref().map(|t| t.total.load(Ordering::Relaxed)).unwrap_or(0) + e.as_ref().map(|t| t.total.load(Ordering::Relaxed)).unwrap_or(0);
            let since = total.saturating_sub(r.last_tick_bytes.swap(total, Ordering::Relaxed));
            let lines: Vec<String> = r.new_lines.lock().unwrap().drain(..).collect();
            let mut p = json!({"id": r.id, "script": r.script, "session": r.session, "elapsedMs": r.started.elapsed().as_millis() as u64,
                "newLines": lines, "stdoutTail": o.map(|t| t.text()).unwrap_or_default(), "stderrTail": e.map(|t| t.text()).unwrap_or_default(), "bytesSinceLast": since});
            if let Some(pr) = &r.project {
                p["projectId"] = json!(pr);
            }
            if let Some(js) = r.job_state.lock().unwrap().clone() {
                p["state"] = js;
            }
            self.emit("exec.tick", p);
        }
    }

    /// Ticks on tokio every `scripting.exec.tickSec`.
    pub fn spawn_ticker(self: &Arc<Self>) -> tokio::task::JoinHandle<()> {
        let me = self.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(Duration::from_secs(tick_sec(&*me.config))).await;
                me.tick();
            }
        })
    }

    fn prune(&self) {
        let mut runs = self.runs.lock().unwrap();
        if runs.len() < MAX_RUNS {
            return;
        }
        let drop_n = runs.len() - MAX_RUNS + 1;
        let done: Vec<String> = runs.values().filter(|r| !matches!(r.state.lock().unwrap().as_str(), "running" | "queued")).take(drop_n).map(|r| r.id.clone()).collect();
        for id in done {
            runs.remove(&id);
        }
    }
}

/// Kills the child and its descendants (by PID only).
fn kill_tree(child: &mut std::process::Child) {
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        let _ = Command::new("taskkill").args(["/PID", &child.id().to_string(), "/T", "/F"]).creation_flags(0x0800_0000).stdout(Stdio::null()).stderr(Stdio::null()).status();
    }
    let _ = child.kill();
    let _ = child.wait();
}
