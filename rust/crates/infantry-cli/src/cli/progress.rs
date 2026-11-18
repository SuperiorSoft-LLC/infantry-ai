// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `infantry progress ...`, `progress --watch` and `infantry run -- <cmd...>` (Kotlin `ProgressCli`, contract 1.117.0).
//!
//! Harmless outside Infantry AI, by construction: with no `INFANTRY_SESSION_ID`, or a daemon that cannot be reached
//! or refuses, every report is dropped silently, every progress command exits 0 and prints nothing, and `--watch` /
//! `run` still copy the bytes through unchanged and keep the wrapped command's exit code. Reports go out on a
//! background thread (which owns the connection), so a slow daemon never stalls the pipe. Only the parsed marker
//! fields leave the machine; the output text is never sent. `INFANTRY_PROGRESS_DEBUG=1` says why a report was dropped.
//!
//! `--reader junit|gate` read a tool's own lines (tool-profile readers are not ported). Not ported yet: the countdown part of
//! `progress show` (the line has state, number, label and age).

use super::args::Parsed;
use super::conn::{Endpoint, Envelope, FrameStream};
use super::{Getenv, Io};
use serde_json::{json, Map, Value};
use std::io::{Read, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

pub const DEBUG_ENV: &str = "INFANTRY_PROGRESS_DEBUG";
pub const PIPE_ENV: &str = "INFANTRY_PROGRESS_PIPE";
/// How long a command waits, at the end, for its last reports to reach the daemon.
const FLUSH_MS: u64 = 3_000;
/// A streaming command sends a numeric update at most this often.
const COALESCE_MS: u64 = 250;
/// A job whose report did not change still says it is alive this often.
const HEARTBEAT_MS: u64 = 5_000;
/// The daemon's answer to one report.
const REPORT_TIMEOUT_MS: u64 = 2_000;
/// A line longer than this is skipped, never buffered whole.
const MAX_LINE: usize = 16 * 1024;
/// `ProgressReducer.MAX_LABEL`.
const MAX_LABEL: usize = 200;
const NUMERIC: [&str; 4] = ["percent", "bytes", "count", "output"];
pub const PREFIX: &str = "##infantry";

pub const USAGE: &str = r#"infantry progress <0-100> ["label"]            percent
infantry progress plan "a" "b" ... | plan <N>   declare checkpoints (names, or a count) [--label x]
infantry progress step ["name"]                 that step completed (no name: the current one); unplanned names are added
infantry progress bytes <done> <total|?> ["label"]   a transfer (speed is derived)
infantry progress count <done> <total|?> ["label"]   items done of a total
infantry progress status "label"                indeterminate: a label and an elapsed timer, no number
infantry progress estimate "label" (= status "label" --estimate)  indeterminate + "~N min left (estimated)" from this label's past runs (opt-in; prefer a measurement)
infantry progress done ["label"] | fail ["reason"] | clear | show
infantry progress --watch [--label x [--estimate]] [--reader <name>]...   stdin -> stdout unchanged, reading ##infantry markers
infantry run [--label x] [--estimate] [--reader r]... -- <command...>    run it, output and exit code unchanged; done on 0, failed otherwise
Markers (any language, one line): ##infantry progress 40 compile | ##infantry plan a|b|c | ##infantry plan 4
  ##infantry step [name] | ##infantry bytes <done> <total> [label] | ##infantry count <done> <total> [label]
  ##infantry status [--estimate] <label> | ##infantry estimate <label> | ##infantry done [label] | ##infantry fail [reason] | ##infantry clear
Outside Infantry AI (no INFANTRY_SESSION_ID, no daemon) every progress command does nothing and exits 0."#;

/// One report (`ProgressReport`), encoded as Kotlin does: only the fields that are set.
#[derive(Clone, Debug, PartialEq, Default)]
pub struct Report {
    pub op: String,
    pub percent: Option<f64>,
    pub label: Option<String>,
    pub steps: Vec<String>,
    pub count: Option<i32>,
    pub step: Option<String>,
    pub done: Option<i64>,
    pub total: Option<i64>,
    pub estimate: Option<bool>,
    pub estimate_ms: Option<i64>,
}

impl Report {
    fn op(op: &str) -> Report {
        Report { op: op.into(), ..Default::default() }
    }
    fn labelled(op: &str, label: Option<String>) -> Report {
        Report { op: op.into(), label, ..Default::default() }
    }
    pub fn encode(&self) -> String {
        let mut m = Map::new();
        m.insert("op".into(), json!(self.op));
        if let Some(p) = self.percent {
            m.insert("percent".into(), json!(p));
        }
        if let Some(l) = &self.label {
            m.insert("label".into(), json!(l));
        }
        if !self.steps.is_empty() {
            m.insert("steps".into(), json!(self.steps));
        }
        if let Some(c) = self.count {
            m.insert("count".into(), json!(c));
        }
        if let Some(s) = &self.step {
            m.insert("step".into(), json!(s));
        }
        if let Some(d) = self.done {
            m.insert("done".into(), json!(d));
        }
        if let Some(t) = self.total {
            m.insert("total".into(), json!(t));
        }
        if let Some(e) = self.estimate {
            m.insert("estimate".into(), json!(e));
        }
        if let Some(e) = self.estimate_ms {
            m.insert("estimateMs".into(), json!(e));
        }
        Value::Object(m).to_string()
    }
}

fn tail(words: &[String]) -> Option<String> {
    let t = words.join(" ");
    (!t.trim().is_empty()).then_some(t)
}

pub fn amounts(op: &str, rest: &[String]) -> Option<Report> {
    let done = rest.first()?.parse::<i64>().ok()?;
    let Some(total_word) = rest.get(1) else { return Some(Report { done: Some(done), ..Report::op(op) }) };
    let total = if total_word == "?" { None } else { Some(total_word.parse::<i64>().ok()?) };
    Some(Report { done: Some(done), total, label: tail(&rest[2..]), ..Report::op(op) })
}

fn kotlin_double(s: &str) -> Option<f64> {
    // `toDoubleOrNull` accepts "NaN"/"Infinity" too; a percent must still be a number.
    s.trim().parse::<f64>().ok()
}

/// `parseCommand`: the words after `progress` as one report; None = not a form this command knows.
pub fn parse_command(args: &[String]) -> Option<Report> {
    let verb = args.first()?;
    let rest = &args[1..];
    if let Some(p) = kotlin_double(verb.strip_suffix('%').unwrap_or(verb)) {
        return Some(Report { percent: Some(p), label: tail(rest), ..Report::op("percent") });
    }
    match verb.as_str() {
        "plan" => {
            let (mut label, mut count, mut names) = (None, None, Vec::new());
            let mut i = 0;
            while i < rest.len() {
                match rest[i].as_str() {
                    "--label" => {
                        i += 1;
                        label = Some(rest.get(i)?.clone());
                    }
                    "--count" => {
                        i += 1;
                        count = Some(rest.get(i)?.parse::<i32>().ok()?);
                    }
                    _ => names.push(rest[i].clone()),
                }
                i += 1;
            }
            if count.is_none() && names.len() == 1 {
                if let Ok(n) = names[0].parse::<i32>() {
                    count = Some(n);
                    names.clear();
                }
            }
            if names.is_empty() && count.unwrap_or(0) <= 0 {
                None
            } else {
                Some(Report { steps: names, count, label, ..Report::op("plan") })
            }
        }
        "step" => Some(Report { step: tail(rest), ..Report::op("step") }),
        "bytes" | "count" => amounts(verb, rest),
        "status" | "start" => {
            let est = rest.iter().any(|w| w == "--estimate");
            let label = tail(&rest.iter().filter(|w| *w != "--estimate").cloned().collect::<Vec<_>>());
            if est && label.is_none() {
                None
            } else {
                Some(Report { label, estimate: est.then_some(true), ..Report::op("status") })
            }
        }
        "estimate" => tail(rest).map(|l| Report { label: Some(l), estimate: Some(true), ..Report::op("status") }),
        "done" => Some(Report::labelled("done", tail(rest))),
        "fail" => Some(Report::labelled("fail", tail(rest))),
        "clear" => rest.is_empty().then(|| Report::op("clear")),
        "show" | "get" => rest.is_empty().then(|| Report::op("get")),
        _ => None,
    }
}

/// `ProgressMarkers.parse`: a `##infantry <verb> ...` line, or None (a marker never breaks the program printing it).
pub fn parse_marker(line: &str) -> Option<Report> {
    let t = line.trim();
    let after = t.strip_prefix(PREFIX)?;
    if !after.chars().next().is_some_and(char::is_whitespace) {
        return None;
    }
    let words: Vec<String> = after.split_whitespace().map(String::from).collect();
    let verb = words.first()?.as_str();
    let rest = &words[1..];
    let tl = tail(rest);
    match verb {
        "progress" => {
            let first = rest.first()?;
            let p = kotlin_double(first.strip_suffix('%').unwrap_or(first)).filter(|p| p.is_finite())?;
            Some(Report { percent: Some(p), label: tail(&rest[1..]), ..Report::op("percent") })
        }
        "plan" => match rest.first().and_then(|n| n.parse::<i32>().ok()) {
            Some(n) => (n > 0).then(|| Report { count: Some(n), label: tail(&rest[1..]), ..Report::op("plan") }),
            None => {
                let names: Vec<String> = tl?.split('|').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect();
                (!names.is_empty()).then(|| Report { steps: names, ..Report::op("plan") })
            }
        },
        "step" => Some(Report { step: tl, ..Report::op("step") }),
        "bytes" | "count" => amounts(verb, rest),
        "status" => tail(&rest.iter().filter(|w| *w != "--estimate").cloned().collect::<Vec<_>>())
            .map(|l| Report { label: Some(l), estimate: rest.iter().any(|w| w == "--estimate").then_some(true), ..Report::op("status") }),
        "estimate" => tl.map(|l| Report { label: Some(l), estimate: Some(true), ..Report::op("status") }),
        "eta" => rest.first().and_then(|s| s.parse::<i64>().ok()).filter(|s| *s > 0).map(|s| Report {
            label: tail(&rest[1..]),
            estimate_ms: Some(s * 1000),
            ..Report::op("status")
        }),
        "done" => Some(Report::labelled("done", tl)),
        "fail" => Some(Report::labelled("fail", tl)),
        "clear" => Some(Report::op("clear")),
        _ => None,
    }
}

/// Sends reports on its own thread; the first failure turns it off for good.
pub struct Reporter {
    tx: Option<mpsc::Sender<Report>>,
    done: Mutex<Option<mpsc::Receiver<()>>>,
    dead: Arc<AtomicBool>,
    /// The job is running (a report arrived and no done / fail / clear after it): heartbeats go out.
    running: Arc<AtomicBool>,
}

impl Reporter {
    pub fn new(session: Option<String>, endpoint: Option<Endpoint>, debug: bool) -> Reporter {
        let dead = Arc::new(AtomicBool::new(session.is_none() || endpoint.is_none()));
        let running = Arc::new(AtomicBool::new(false));
        if dead.load(Ordering::SeqCst) {
            return Reporter { tx: None, done: Mutex::new(None), dead, running };
        }
        let (tx, rx) = mpsc::channel::<Report>();
        let (done_tx, done_rx) = mpsc::channel::<()>();
        let d = dead.clone();
        let (session, endpoint) = (session.unwrap_or_default(), endpoint.unwrap());
        std::thread::spawn(move || {
            let mut stream: Option<FrameStream> = None;
            let drop_it = |why: String| {
                d.store(true, Ordering::SeqCst);
                if debug {
                    eprintln!("infantry progress: report dropped ({why})");
                }
            };
            let mut queue: Vec<Report> = Vec::new();
            loop {
                if queue.is_empty() {
                    match rx.recv() {
                        Ok(r) => queue.push(r),
                        Err(_) => break,
                    }
                }
                while let Ok(r) = rx.try_recv() {
                    queue.push(r);
                }
                // Coalesce: a numeric report replaced by the next of the same op is not sent.
                let next = queue.remove(0);
                if queue.first().is_some_and(|n| n.op == next.op && NUMERIC.contains(&next.op.as_str())) {
                    continue;
                }
                if d.load(Ordering::SeqCst) {
                    continue;
                }
                if stream.is_none() {
                    match FrameStream::open(&endpoint, Box::new(|_| {})) {
                        Ok(s) => stream = Some(s),
                        Err(e) => {
                            drop_it(e.message());
                            continue;
                        }
                    }
                }
                let env = Envelope::new("progress", next.encode()).with_session(Some(session.clone()));
                if let Err(e) = stream.as_mut().unwrap().request_within(&env, "progress", "progress", false, REPORT_TIMEOUT_MS) {
                    drop_it(e.message());
                }
                if NUMERIC.contains(&next.op.as_str()) && queue.is_empty() {
                    std::thread::sleep(Duration::from_millis(COALESCE_MS));
                }
            }
            if let Some(s) = stream {
                s.close();
            }
            let _ = done_tx.send(());
        });
        Reporter { tx: Some(tx), done: Mutex::new(Some(done_rx)), dead, running }
    }

    pub fn enabled(&self) -> bool {
        !self.dead.load(Ordering::SeqCst)
    }

    pub fn submit(&self, r: Report) {
        if !self.enabled() {
            return;
        }
        let on = !matches!(r.op.as_str(), "done" | "fail" | "clear");
        self.running.store(on, Ordering::SeqCst);
        if let Some(tx) = &self.tx {
            let _ = tx.send(r);
        }
    }

    pub fn close(mut self, timeout_ms: u64) {
        self.tx.take();
        if let Some(d) = self.done.lock().ok().and_then(|mut g| g.take()) {
            let _ = d.recv_timeout(Duration::from_millis(timeout_ms.max(1)));
        }
    }
}

/// Turns lines into reports: a marker always, else a heartbeat while the job runs.
struct Scanner<'a> {
    reporter: &'a Reporter,
    last_sent: Instant,
    readers: Vec<Reader>,
}

impl Scanner<'_> {
    fn line(&mut self, text: &str) {
        if !self.reporter.enabled() {
            return;
        }
        if let Some(m) = parse_marker(text) {
            self.reporter.submit(m);
            self.last_sent = Instant::now();
            return;
        }
        let mut sent = false;
        for r in self.readers.iter_mut() {
            for rep in r.read(text) {
                self.reporter.submit(rep);
                sent = true;
            }
        }
        if sent {
            self.last_sent = Instant::now();
        } else if self.last_sent.elapsed() >= Duration::from_millis(HEARTBEAT_MS) && self.reporter.running.load(Ordering::SeqCst) {
            self.reporter.submit(Report::op("output"));
            self.last_sent = Instant::now();
        }
    }
}

/// Splits bytes into lines (LF; a trailing CR dropped); an over-long line is skipped.
#[derive(Default)]
struct LineBuffer {
    bytes: Vec<u8>,
    overflow: bool,
}

impl LineBuffer {
    fn feed(&mut self, buf: &[u8], on_line: &mut dyn FnMut(&str)) {
        let mut start = 0;
        for (i, b) in buf.iter().enumerate() {
            if *b == b'\n' {
                self.append(&buf[start..i]);
                self.emit(on_line);
                start = i + 1;
            }
        }
        self.append(&buf[start..]);
    }
    fn finish(&mut self, on_line: &mut dyn FnMut(&str)) {
        if !self.bytes.is_empty() || self.overflow {
            self.emit(on_line);
        }
    }
    fn append(&mut self, b: &[u8]) {
        if b.is_empty() || self.overflow {
            return;
        }
        if self.bytes.len() + b.len() > MAX_LINE {
            self.overflow = true;
            self.bytes.clear();
        } else {
            self.bytes.extend_from_slice(b);
        }
    }
    fn emit(&mut self, on_line: &mut dyn FnMut(&str)) {
        if !self.overflow {
            let s = String::from_utf8_lossy(&self.bytes).to_string();
            on_line(s.strip_suffix('\r').unwrap_or(&s));
        }
        self.bytes.clear();
        self.overflow = false;
    }
}

/// Copies [input] to [output] byte for byte (flushed per chunk), scanning each complete line.
fn pump(input: &mut dyn Read, output: &mut dyn Write, on_line: &mut dyn FnMut(&str)) {
    let mut buf = [0u8; 8192];
    let mut lines = LineBuffer::default();
    let mut writable = true;
    loop {
        let n = match input.read(&mut buf) {
            Ok(0) | Err(_) => break,
            Ok(n) => n,
        };
        if writable && (output.write_all(&buf[..n]).is_err() || output.flush().is_err()) {
            writable = false;
        }
        lines.feed(&buf[..n], on_line);
    }
    lines.finish(on_line);
}

/// An opt-in reader of one tool's own output (`--reader <name>`); it never guesses for a tool it was not asked for.
#[derive(Debug, Clone, PartialEq)]
pub enum Reader {
    /// Test runs: each `Class > test PASSED|FAILED|SKIPPED` line counts one; `N tests completed` sets the count.
    Junit { seen: i64 },
    /// The gate script's `stage k of n [name]`: a plan of n checkpoints, k-1 of them complete.
    Gate { planned: Option<i32>, done: i32 },
}

/// The built-in reader names (tool-profile readers are not in the native CLI).
pub const READERS: [&str; 2] = ["junit", "gate"];

impl Reader {
    pub fn by_name(name: &str) -> Option<Reader> {
        match name {
            "junit" => Some(Reader::Junit { seen: 0 }),
            "gate" => Some(Reader::Gate { planned: None, done: 0 }),
            _ => None,
        }
    }

    /// The reports `line` stands for.
    pub fn read(&mut self, line: &str) -> Vec<Report> {
        match self {
            Reader::Junit { seen } => {
                let words: Vec<&str> = line.split_whitespace().collect();
                if let Some(i) = words.iter().position(|w| w.trim_end_matches(|c: char| !c.is_alphanumeric()) == "completed").filter(|i| *i >= 2 && matches!(words[i - 1], "test" | "tests")) {
                    if let Ok(n) = words[i - 2].parse::<i64>() {
                        *seen = n;
                        return vec![Report { done: Some(n), label: Some("tests".into()), ..Report::op("count") }];
                    }
                }
                let t = line.trim_end();
                let event = !line.starts_with(char::is_whitespace) && t.contains(" > ") && ["PASSED", "FAILED", "SKIPPED"].iter().any(|v| t.ends_with(&format!(" {v}")));
                if !event {
                    return Vec::new();
                }
                *seen += 1;
                vec![Report { done: Some(*seen), label: Some("tests".into()), ..Report::op("count") }]
            }
            Reader::Gate { planned, done } => {
                let Some(i) = line.find("stage ") else { return Vec::new() };
                let rest: Vec<&str> = line[i + 6..].splitn(4, ' ').collect();
                let (Some(k), Some("of"), Some(n)) = (rest.first().and_then(|x| x.parse::<i32>().ok()), rest.get(1).copied(), rest.get(2).map(|x| x.trim_end_matches(':'))) else { return Vec::new() };
                let Ok(n) = n.parse::<i32>() else { return Vec::new() };
                if n <= 0 || k <= 0 {
                    return Vec::new();
                }
                let mut out = Vec::new();
                if *planned != Some(n) {
                    let name = rest.get(3).map(|x| x.trim_start_matches([':', '-', ' ']).trim().to_string()).filter(|x| !x.is_empty());
                    out.push(Report { count: Some(n), label: name, ..Report::op("plan") });
                    *planned = Some(n);
                    *done = 0;
                }
                while *done < k - 1 {
                    out.push(Report::op("step"));
                    *done += 1;
                }
                out
            }
        }
    }
}

pub struct StreamOptions {
    pub readers: Vec<Reader>,
    pub label: Option<String>,
    pub command: Vec<String>,
    pub bad: Option<String>,
    pub estimate: bool,
}

/// `parseStreamOptions`.
pub fn parse_stream_options(args: &[String], allow_command: bool) -> StreamOptions {
    let split = args.iter().position(|a| a == "--");
    let (opts, command) = match split {
        Some(i) => (&args[..i], args[i + 1..].to_vec()),
        None => (args, Vec::new()),
    };
    let bad = |m: String, command: Vec<String>| StreamOptions { readers: Vec::new(), label: None, command, bad: Some(m), estimate: false };
    if !allow_command && split.is_some() {
        return bad("takes no command (use infantry run -- <cmd>)".into(), Vec::new());
    }
    let (mut label, mut estimate, mut readers) = (None, false, Vec::new());
    let mut i = 0;
    while i < opts.len() {
        match opts[i].as_str() {
            "--label" => {
                i += 1;
                match opts.get(i) {
                    Some(l) => label = Some(l.clone()),
                    None => return bad("--label expects a label".into(), command),
                }
            }
            "--estimate" => estimate = true,
            "--reader" => {
                i += 1;
                match opts.get(i).and_then(|n| Reader::by_name(n)) {
                    Some(r) => readers.push(r),
                    None => return bad(format!("--reader expects one of {}", READERS.join(", ")), command),
                }
            }
            o => {
                let short: String = o.chars().take(40).collect();
                return bad(format!("unknown option '{short}'{}", if allow_command { " (put the command after --)" } else { "" }), command);
            }
        }
        i += 1;
    }
    if estimate && !allow_command && label.is_none() {
        return bad("--estimate needs --label (past runs are remembered by it)".into(), command);
    }
    StreamOptions { readers, label, command, bad: None, estimate }
}

fn session(getenv: Getenv) -> Option<String> {
    getenv("INFANTRY_SESSION_ID").map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
}

fn endpoint(getenv: Getenv) -> Option<Endpoint> {
    super::resolve_endpoint(&Parsed::default(), getenv, false).ok()
}

fn reporter(getenv: Getenv) -> Reporter {
    let s = session(getenv);
    let ep = if s.is_some() { endpoint(getenv) } else { None };
    Reporter::new(s, ep, getenv(DEBUG_ENV).map(|v| v.trim() == "1").unwrap_or(false))
}

fn duration(ms: i64) -> String {
    let s = (ms / 1000).max(0);
    if s < 60 {
        format!("{s}s")
    } else if s < 3600 {
        format!("{}m {}s", s / 60, s % 60)
    } else {
        format!("{}h {}m", s / 3600, s % 3600 / 60)
    }
}

fn bytes(n: i64) -> String {
    let f = n as f64;
    if n >= 1 << 30 {
        format!("{:.1} GB", f / (1u64 << 30) as f64)
    } else if n >= 1 << 20 {
        format!("{:.1} MB", f / (1u64 << 20) as f64)
    } else if n >= 1 << 10 {
        format!("{:.1} KB", f / (1u64 << 10) as f64)
    } else {
        format!("{n} B")
    }
}

/// `ProgressReducer.line`, without the countdown.
pub fn show_line(p: &Value, now: i64) -> String {
    let mut parts: Vec<String> = Vec::new();
    let state = p["state"].as_str().unwrap_or("running");
    let running = state == "running";
    if !running {
        parts.push(state.into());
    }
    if let Some(pc) = p["percent"].as_f64() {
        parts.push(format!("{}%", pc.round() as i64));
    }
    let i = |k: &str| p[k].as_i64();
    match p["mode"].as_str() {
        Some("steps") => {
            let total = i("stepsTotal").unwrap_or(0);
            parts.push(format!(
                "step {}/{total}{}",
                (i("stepsDone").unwrap_or(0) + if running { 1 } else { 0 }).min(total),
                p["step"].as_str().map(|s| format!(" {s}")).unwrap_or_default()
            ));
        }
        Some("bytes") => parts.push(format!(
            "{}{}{}",
            bytes(i("done").unwrap_or(0)),
            i("total").map(|t| format!("/{}", bytes(t))).unwrap_or_default(),
            p["bytesPerSec"].as_f64().map(|b| format!(", {}/s", bytes(b as i64))).unwrap_or_default()
        )),
        Some("count") => parts.push(format!(
            "{}{}{}",
            i("done").unwrap_or(0),
            i("total").map(|t| format!("/{t}")).unwrap_or_default(),
            p["step"].as_str().map(|s| format!(" {s}")).unwrap_or_default()
        )),
        _ => {}
    }
    let updated = i("updatedAt").unwrap_or(now);
    if running && p["percent"].is_null() {
        parts.push(format!("· last output {} ago", duration(now - i("lastOutputAt").unwrap_or(updated))));
    }
    if let Some(l) = p["label"].as_str() {
        parts.push(format!("- {l}"));
    }
    parts.push(format!("(updated {} ago)", duration(now - updated)));
    parts.join(" ")
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// `progress <args>`: never fails the caller outside a session.
pub fn progress(args: &[String], io: &mut dyn Io, getenv: Getenv) -> i32 {
    let args = &args[1..];
    if matches!(args.first().map(String::as_str), Some("--help" | "-h")) {
        io.out(USAGE);
        return 0;
    }
    if args.first().map(String::as_str) == Some("--watch") {
        let opts = parse_stream_options(&args[1..], false);
        let rep = reporter(getenv);
        let (mut stdin, mut stdout) = (std::io::stdin().lock(), std::io::stdout().lock());
        if let Some(b) = opts.bad {
            io.err(&format!("error: progress --watch: {b}"));
            pump(&mut stdin, &mut stdout, &mut |_| {});
            return 0;
        }
        if let Some(l) = opts.label {
            rep.submit(Report { label: Some(l), estimate: opts.estimate.then_some(true), ..Report::op("status") });
        }
        let mut sc = Scanner { reporter: &rep, last_sent: Instant::now(), readers: opts.readers };
        pump(&mut stdin, &mut stdout, &mut |l| sc.line(l));
        rep.close(FLUSH_MS);
        return 0;
    }
    let Some(report) = parse_command(args) else {
        let short: String = args.join(" ").chars().take(80).collect();
        io.err(&format!("error: progress: unknown form '{short}'\n{USAGE}"));
        return if session(getenv).is_some() { 2 } else { 0 };
    };
    if report.op == "get" {
        let (Some(s), Some(ep)) = (session(getenv), endpoint(getenv)) else { return 0 };
        if let Ok(mut st) = FrameStream::open(&ep, Box::new(|_| {})) {
            let env = Envelope::new("progress", report.encode()).with_session(Some(s));
            if let Ok(a) = st.request_within(&env, "progress", "progress", false, REPORT_TIMEOUT_MS) {
                if let Ok(v) = serde_json::from_str::<Value>(&a.data) {
                    if v.is_object() {
                        io.out(&show_line(&v, now_ms()));
                    }
                }
            }
            st.close();
        }
        return 0;
    }
    let rep = reporter(getenv);
    rep.submit(report);
    rep.close(FLUSH_MS);
    0
}

/// `run [--label x] [--estimate] -- <command...>`: the child's exit code, or 127 when it cannot be started.
pub fn run(args: &[String], io: &mut dyn Io, getenv: Getenv) -> i32 {
    let opts = parse_stream_options(&args[1..], true);
    if opts.bad.is_some() || opts.command.is_empty() {
        io.err(&format!(
            "error: run: {}",
            opts.bad.unwrap_or_else(|| "expects a command after --, e.g. infantry run --label tests -- <your build or test command>".into())
        ));
        return 2;
    }
    let mut cmd = std::process::Command::new(&opts.command[0]);
    cmd.args(&opts.command[1..]).env(PIPE_ENV, "1").stdin(std::process::Stdio::inherit()).stdout(std::process::Stdio::piped()).stderr(std::process::Stdio::piped());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000);
    }
    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            io.err(&format!("infantry run: cannot run '{}': {e}", opts.command[0]));
            return 127;
        }
    };
    let rep = reporter(getenv);
    let label = opts.label.clone().unwrap_or_else(|| opts.command.join(" ").chars().take(MAX_LABEL).collect());
    rep.submit(Report { label: Some(label), estimate: opts.estimate.then_some(true), ..Report::op("status") });
    let scanner = Mutex::new(Scanner { reporter: &rep, last_sent: Instant::now(), readers: opts.readers.clone() });
    let mut out = child.stdout.take().unwrap();
    let mut err = child.stderr.take().unwrap();
    std::thread::scope(|s| {
        s.spawn(|| pump(&mut err, &mut std::io::stderr(), &mut |l| scanner.lock().unwrap().line(l)));
        pump(&mut out, &mut std::io::stdout(), &mut |l| scanner.lock().unwrap().line(l));
    });
    let code = child.wait().ok().and_then(|s| s.code()).unwrap_or(1);
    rep.submit(if code == 0 { Report::op("done") } else { Report::labelled("fail", Some(format!("exit {code}"))) });
    rep.close(FLUSH_MS);
    code
}

pub fn is_progress(a: &[String]) -> bool {
    a.first().map(String::as_str) == Some("progress")
}

pub fn is_run(a: &[String]) -> bool {
    a.first().map(String::as_str) == Some("run")
}

#[cfg(test)]
mod tests {
    use super::*;

    fn v(a: &[&str]) -> Vec<String> {
        a.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn commands_and_markers_encode_as_kotlin() {
        assert_eq!(parse_command(&v(&["40%", "compile"])).unwrap().encode(), r#"{"op":"percent","percent":40.0,"label":"compile"}"#);
        assert_eq!(parse_command(&v(&["plan", "4"])).unwrap().encode(), r#"{"op":"plan","count":4}"#);
        assert_eq!(parse_command(&v(&["bytes", "10", "?", "dl"])).unwrap().encode(), r#"{"op":"bytes","label":"dl","done":10}"#);
        assert_eq!(parse_command(&v(&["status", "--estimate"])), None);
        assert_eq!(parse_marker("  ##infantry plan a | b|c").unwrap().steps, v(&["a", "b", "c"]));
        assert_eq!(parse_marker("##infantry eta 30 tests").unwrap().encode(), r#"{"op":"status","label":"tests","estimateMs":30000}"#);
        assert_eq!(parse_marker("##infantryx done"), None);
    }

    #[test]
    fn outside_infantry_progress_does_nothing() {
        let mut io = super::super::BufIo::default();
        let env = |_: &str| None;
        assert_eq!(progress(&v(&["progress", "50"]), &mut io, &env), 0);
        assert_eq!(progress(&v(&["progress", "bogus"]), &mut io, &env), 0);
        assert!(io.out.is_empty());
        assert_eq!(run(&v(&["run", "--nope"]), &mut io, &env), 2);
    }
}

#[cfg(test)]
mod reader_tests {
    use super::*;

    #[test]
    fn junit_counts_test_lines_and_gate_plans_then_steps() {
        let mut j = Reader::by_name("junit").unwrap();
        assert_eq!(j.read("com.x.FooTest > works PASSED")[0].done, Some(1));
        assert!(j.read("  indented > x PASSED").is_empty());
        assert_eq!(j.read("12 tests completed, 1 failed")[0].done, Some(12));
        let mut g = Reader::by_name("gate").unwrap();
        let first = g.read("== stage 1 of 4: compile");
        assert_eq!((first.len(), first[0].op.as_str(), first[0].count, first[0].label.as_deref()), (1, "plan", Some(4), Some("compile")));
        let third = g.read("stage 3 of 4 - tests");
        assert_eq!(third.iter().map(|r| r.op.as_str()).collect::<Vec<_>>(), vec!["step", "step"]);
        assert!(Reader::by_name("maven").is_none());
        assert!(parse_stream_options(&["--reader".into(), "nope".into()], false).bad.unwrap().contains("junit, gate"));
        assert_eq!(parse_stream_options(&["--reader".into(), "gate".into()], false).readers.len(), 1);
    }
}
