// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-MCP-1 conformance: runs the same MCP request script against the Kotlin bridge and the native one and diffs what
//! they write, byte for byte.
//!
//! Phase A runs against a TEST daemon (the caller passes its `INFANTRY_URL` / `INFANTRY_STATE_DIR` with `--env`):
//! initialize, ping, tools/list, tool calls, the error paths. Phase B runs against a scripted stub daemon this driver
//! serves on an ephemeral loopback port: the tool list changes mid-session (list_changed), and every relay rule (detail,
//! maxChars, verbose, bodyChars, localFiles, isError, malformed answers, daemon errors, capping, Unicode) is echoed back so
//! the two bridges' `plugin_tool_call` payloads are compared too. It also samples each bridge's memory (Windows).
//!
//! ```text
//! mcp-conformance --kotlin <launcher.bat> --rust <infantry.exe> [--golden <file>] [--out <dir>] [--env K=V]...
//! ```
//! Exit 0 = every phase identical.

use serde_json::Value;
use std::io::{BufRead, BufReader, Write};
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver};
use std::sync::Arc;
use std::time::{Duration, Instant};

const ANSWER_TIMEOUT: Duration = Duration::from_secs(120);
const NOTIFY_TIMEOUT: Duration = Duration::from_secs(15);

struct Args {
    kotlin: String,
    rust: String,
    golden: Option<PathBuf>,
    out: PathBuf,
    env: Vec<(String, String)>,
}

fn parse_args() -> Result<Args, String> {
    let mut kotlin = None;
    let mut rust = None;
    let mut golden = None;
    let mut out = std::env::temp_dir().join("infantry-mcp-conformance");
    let mut env = Vec::new();
    let a: Vec<String> = std::env::args().skip(1).collect();
    let mut i = 0;
    while i < a.len() {
        let v = a.get(i + 1).cloned().ok_or_else(|| format!("{} expects a value", a[i]))?;
        match a[i].as_str() {
            "--kotlin" => kotlin = Some(v),
            "--rust" => rust = Some(v),
            "--golden" => golden = Some(PathBuf::from(v)),
            "--out" => out = PathBuf::from(v),
            "--env" => {
                let (k, val) = v.split_once('=').ok_or("--env expects K=V")?;
                env.push((k.to_string(), val.to_string()));
            }
            other => return Err(format!("unknown argument {other}")),
        }
        i += 2;
    }
    Ok(Args { kotlin: kotlin.ok_or("--kotlin is required")?, rust: rust.ok_or("--rust is required")?, golden, out, env })
}

// ---- one bridge process ---------------------------------------------------------------------------------------------

struct Bridge {
    child: Child,
    stdin: std::process::ChildStdin,
    lines: Receiver<String>,
    stdout: Vec<String>,
    stderr: Arc<std::sync::Mutex<Vec<String>>>,
}

fn command_for(program: &str) -> Command {
    let lower = program.to_ascii_lowercase();
    if cfg!(windows) && (lower.ends_with(".bat") || lower.ends_with(".cmd")) {
        let mut c = Command::new("cmd");
        c.arg("/c").arg(program);
        c
    } else {
        Command::new(program)
    }
}

impl Bridge {
    fn start(program: &str, extra: &[String], env: &[(String, String)], cwd: &Path) -> Bridge {
        let mut c = command_for(program);
        c.arg("mcp").arg("serve").args(extra).current_dir(cwd);
        // Never inherit the calling agent's session: scrub every INFANTRY_ / AGENTHQ_ / CLAUDE variable, then set the phase's.
        for (k, _) in std::env::vars() {
            if k.starts_with("INFANTRY_") || k.starts_with("AGENTHQ_") || k.starts_with("CLAUDE") {
                c.env_remove(&k);
            }
        }
        for (k, v) in env {
            c.env(k, v);
        }
        let mut child = c
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap_or_else(|e| panic!("cannot start {program}: {e}"));
        let stdin = child.stdin.take().unwrap();
        let stdout = child.stdout.take().unwrap();
        let stderr = child.stderr.take().unwrap();
        let (tx, rx) = mpsc::channel();
        std::thread::spawn(move || {
            for line in BufReader::new(stdout).split(b'\n') {
                let Ok(line) = line else { break };
                if tx.send(String::from_utf8_lossy(&line).into_owned()).is_err() {
                    break;
                }
            }
        });
        let errs = Arc::new(std::sync::Mutex::new(Vec::new()));
        let e2 = Arc::clone(&errs);
        std::thread::spawn(move || {
            for line in BufReader::new(stderr).split(b'\n') {
                let Ok(line) = line else { break };
                e2.lock().unwrap().push(String::from_utf8_lossy(&line).trim_end_matches('\r').to_string());
            }
        });
        Bridge { child, stdin, lines: rx, stdout: Vec::new(), stderr: errs }
    }

    fn send(&mut self, line: &str) {
        self.stdin.write_all(line.as_bytes()).unwrap();
        self.stdin.write_all(b"\n").unwrap();
        self.stdin.flush().unwrap();
    }

    fn read(&mut self, timeout: Duration) -> Option<String> {
        let l = self.lines.recv_timeout(timeout).ok()?;
        self.stdout.push(l.clone());
        Some(l)
    }

    /// Sends [line] and reads [answers] lines (0 for a notification).
    fn exchange(&mut self, line: &str, answers: usize) {
        self.send(line);
        for _ in 0..answers {
            if self.read(ANSWER_TIMEOUT).is_none() {
                self.stdout.push(format!("<no answer to {line}>"));
                return;
            }
        }
    }

    /// Closes stdin and waits for the exit; returns (exit code, stderr lines).
    fn finish(mut self) -> (Vec<String>, i32, Vec<String>) {
        drop(self.stdin);
        let deadline = Instant::now() + Duration::from_secs(30);
        let code = loop {
            if let Ok(Some(s)) = self.child.try_wait() {
                break s.code().unwrap_or(-1);
            }
            if Instant::now() > deadline {
                let _ = self.child.kill();
                break -9;
            }
            std::thread::sleep(Duration::from_millis(50));
        };
        while let Ok(l) = self.lines.recv_timeout(Duration::from_millis(200)) {
            self.stdout.push(l);
        }
        std::thread::sleep(Duration::from_millis(100));
        let err = self.stderr.lock().unwrap().clone();
        (self.stdout, code, err)
    }
}

// ---- memory ---------------------------------------------------------------------------------------------------------

/// Working set of [pid] and its descendants, per process (Windows; empty elsewhere).
fn memory(pid: u32) -> Vec<(String, u64, u64)> {
    if !cfg!(windows) {
        return Vec::new();
    }
    let script = format!(
        "$all = Get-CimInstance Win32_Process -Property ProcessId,ParentProcessId,Name,WorkingSetSize,PeakWorkingSetSize; \
         $ids = @({pid}); $i = 0; while ($i -lt $ids.Count) {{ $all | Where-Object {{ $_.ParentProcessId -eq $ids[$i] }} | ForEach-Object {{ $ids += $_.ProcessId }}; $i++ }}; \
         $all | Where-Object {{ $ids -contains $_.ProcessId }} | ForEach-Object {{ \"$($_.Name) $($_.WorkingSetSize) $($_.PeakWorkingSetSize)\" }}"
    );
    let Ok(o) = Command::new("powershell").args(["-NoProfile", "-NonInteractive", "-Command", &script]).output() else {
        return Vec::new();
    };
    String::from_utf8_lossy(&o.stdout)
        .lines()
        .filter_map(|l| {
            let mut p = l.split_whitespace();
            let name = p.next()?.to_string();
            let ws = p.next()?.parse().ok()?;
            let peak_kb: u64 = p.next()?.parse().ok()?;
            Some((name, ws, peak_kb * 1024))
        })
        .collect()
}

fn mb(b: u64) -> String {
    format!("{:.1} MB", b as f64 / 1_048_576.0)
}

fn describe(sample: &[(String, u64, u64)]) -> String {
    let total: u64 = sample.iter().filter(|(n, _, _)| n != "conhost.exe").map(|(_, w, _)| w).sum();
    let parts: Vec<String> = sample.iter().map(|(n, w, p)| format!("{n} {} (peak {})", mb(*w), mb(*p))).collect();
    format!("{} [{}]", mb(total), parts.join(", "))
}

// ---- the stub daemon ------------------------------------------------------------------------------------------------

const LIST_A: &str = r#"[{"name":"stub_echo","description":"echo","inputSchema":{"type":"object","properties":{"x":{"type":"integer"}}},"module":"Stub","action":"stub.read","timeoutMs":5000},{"name":"stub_verbose","inputSchema":{"type":"object","properties":{"verbose":{"type":"boolean"}},"required":[]},"timeoutMs":5000},{"name":"stub_own_detail","inputSchema":{"type":"object","properties":{"detail":{"type":"string","description":"own"},"maxChars":{"type":"integer"},"z":{}}},"timeoutMs":5000},{"name":"stub_files","inputSchema":{"type":"object","properties":{"files":{"type":"array"}}},"localFiles":["files"],"timeoutMs":5000},{"name":"infantry_read","inputSchema":{"type":"object","properties":{}},"timeoutMs":5000},{"name":"stub_big","inputSchema":{"type":"object"},"timeoutMs":5000},{"name":"stub_error_flag","annotations":{"readOnlyHint":true},"timeoutMs":5000},{"name":"stub_malformed","timeoutMs":5000},{"name":"stub_daemon_error","timeoutMs":5000},{"name":"stub_unicode","description":"é ü 😀 \u0001 \"q\" \\ /","timeoutMs":5000},{"name":"stub_no_timeout"},42,{"name":"stub_number","inputSchema":{"type":"object","properties":{"n":{"type":"number","default":1.50e3}}},"timeoutMs":5000}]"#;

fn list_b() -> String {
    let mut v: Vec<Value> = serde_json::from_str(LIST_A).unwrap();
    v.retain(|d| d.get("name").and_then(|n| n.as_str()) != Some("stub_big"));
    v.push(serde_json::json!({"name": "stub_new", "timeoutMs": 5000}));
    serde_json::to_string(&v).unwrap()
}

fn envelope(kind: &str, data: &str) -> String {
    let mut m = serde_json::Map::new();
    m.insert("type".into(), Value::String(kind.into()));
    m.insert("data".into(), Value::String(data.into()));
    serde_json::to_string(&Value::Object(m)).unwrap()
}

/// The payload text with the per-process random `clientKey` blanked (textually, so nothing else is re-encoded).
fn redact_client_key(payload: &str) -> String {
    const KEY: &str = "\"clientKey\":\"";
    match payload.find(KEY) {
        Some(at) => {
            let start = at + KEY.len();
            let end = payload[start..].find('"').map_or(payload.len(), |e| start + e);
            format!("{}<clientKey>{}", &payload[..start], &payload[end..])
        }
        None => payload.to_string(),
    }
}

fn stub_answer(payload: &str) -> Option<String> {
    let v: Value = serde_json::from_str(payload).ok()?;
    let name = v.get("name")?.as_str()?.to_string();
    let result = |text: &str, is_error: Value| {
        let mut m = serde_json::Map::new();
        m.insert("text".into(), Value::String(text.into()));
        m.insert("isError".into(), is_error);
        envelope("plugin_tool_result", &serde_json::to_string(&Value::Object(m)).unwrap())
    };
    Some(match name.as_str() {
        "stub_big" => result(&format!("{}\u{1F600}tail", "x".repeat(7999)), Value::Bool(false)),
        "stub_error_flag" => result("bad", Value::String("true".into())),
        "stub_malformed" => envelope("plugin_tool_result", "[1]"),
        "stub_daemon_error" => return Some(envelope("error", "stub refused")),
        "stub_unicode" => result("\u{e9}\u{1}\"\\ \u{1F600} \u{7f} \u{2028}", Value::Bool(false)),
        _ => result(&redact_client_key(payload), Value::Bool(false)),
    })
}

fn serve_stub(listener: TcpListener, flipped: Arc<AtomicBool>) {
    for stream in listener.incoming() {
        let Ok(stream) = stream else { continue };
        let flipped = Arc::clone(&flipped);
        std::thread::spawn(move || {
            let Ok(mut ws) = tungstenite::accept(stream) else { return };
            let _ = ws.send(tungstenite::Message::Text(envelope("ready", "contract=stub instance=stub-1")));
            while let Ok(msg) = ws.read() {
                let tungstenite::Message::Text(t) = msg else { continue };
                let Ok(v) = serde_json::from_str::<Value>(&t) else { continue };
                let kind = v.get("type").and_then(|k| k.as_str()).unwrap_or("");
                let data = v.get("data").and_then(|d| d.as_str()).unwrap_or("");
                let reply = match kind {
                    "plugin_tools" => Some(envelope(
                        "plugin_tools",
                        &if flipped.load(Ordering::SeqCst) { list_b() } else { LIST_A.to_string() },
                    )),
                    "project_list" => Some(envelope("projects", "[]")),
                    "plugin_tool_call" => stub_answer(data),
                    _ => None,
                };
                if let Some(r) = reply {
                    if ws.send(tungstenite::Message::Text(r)).is_err() {
                        return;
                    }
                }
            }
        });
    }
}

// ---- scripts --------------------------------------------------------------------------------------------------------

fn call(id: impl std::fmt::Display, name: &str, args: &str) -> String {
    format!(r#"{{"jsonrpc":"2.0","id":{id},"method":"tools/call","params":{{"name":"{name}","arguments":{args}}}}}"#)
}

const INIT: &str = r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"conformance","version":"1"}}}"#;
const INITIALIZED: &str = r#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#;

/// Phase A, against the test daemon: (line, answers expected).
fn script_a() -> Vec<(String, usize)> {
    vec![
        (INIT.into(), 1),
        (INITIALIZED.into(), 0),
        (r#"{"jsonrpc":"2.0","id":2,"method":"ping"}"#.into(), 1),
        (r#"{"jsonrpc":"2.0","id":3,"method":"tools/list"}"#.into(), 1),
        (call(4, "infantry_projects", "{}"), 1),
        (call(5, "agenthq_projects", r#"{"maxChars":40}"#), 1),
        (call(6, "infantry_role_list", r#"{"detail":"summary"}"#), 1),
        (call(7, "infantry_list_sessions", r#"{"projectId":"all"}"#), 1),
        (call(8, "infantry_no_such_tool", "{}"), 1),
        (r#"{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{}}"#.into(), 1),
        (call(10, "infantry_watch_file", "{}"), 1),
        (call(11, "infantry_watch_file", r#"{"path":"build.log","untilPid":1,"name":"conf"}"#), 1),
        (r#"{"jsonrpc":"2.0","id":12,"method":"resources/list"}"#.into(), 1),
        (r#"{"jsonrpc":"2.0","id":null,"method":"ping"}"#.into(), 1),
        (r#"{"jsonrpc":"2.0","id":"s-1","method":"ping","params":[1]}"#.into(), 1),
        (r#"[{"jsonrpc":"2.0","id":13,"method":"ping"}]"#.into(), 1),
        (r#"{"jsonrpc":"2.0","id":14}"#.into(), 1),
        (r#"{"jsonrpc":"2.0","method":7,"id":15}"#.into(), 1),
        (r#"{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":3}}"#.into(), 0),
        ("   ".into(), 0),
        (r#"{"jsonrpc":"2.0","id":16,"method":"initialize","params":{"protocolVersion":"1999-01-01"}}"#.into(), 1),
        (call(17, "infantry_projects", r#"{"detail":"ack","maxChars":0}"#), 1),
    ]
}

enum StepB {
    Line(String, usize),
    Flip,
}

/// Phase B, against the stub (whose tool list flips after the first tools/list).
fn script_b() -> Vec<StepB> {
    use StepB::*;
    vec![
        Line(INIT.into(), 1),
        Line(INITIALIZED.into(), 0),
        Line(r#"{"jsonrpc":"2.0","id":2,"method":"tools/list"}"#.into(), 1),
        Line(call(3, "stub_echo", r#"{"x":1,"detail":"FULL ","maxChars":"60","y":[1.0,-0,1e400]}"#), 1),
        Line(call(4, "stub_echo", r#"{"x":1,"y":{"b":1,"a":2},"s":"é😀\u0007"}"#), 1),
        Line(call(5, "stub_verbose", r#"{"detail":"full"}"#), 1),
        Line(call(6, "stub_verbose", r#"{"detail":"full","verbose":false}"#), 1),
        Line(call(7, "stub_own_detail", r#"{"detail":"ack","maxChars":500,"z":1}"#), 1),
        Line(call(8, "stub_files", r#"{"files":["input.txt","missing.txt",3,"sub"]}"#), 1),
        Line(call(9, "infantry_read", "{}"), 1),
        Line(call(10, "agenthq_read", r#"{"bodyChars":5}"#), 1),
        Line(call(11, "stub_big", "{}"), 1),
        Line(call(12, "stub_big", r#"{"maxChars":3}"#), 1),
        Line(call(13, "stub_error_flag", "{}"), 1),
        Line(call(14, "stub_malformed", "{}"), 1),
        Line(call(15, "stub_daemon_error", "{}"), 1),
        Line(call(16, "stub_unicode", "{}"), 1),
        Line(call(17, "stub_no_timeout", r#"{"a":null}"#), 1),
        Line(call(18, "stub_number", r#"{"n":12345678901234567890123}"#), 1),
        Line(call(19, "nope", "{}"), 1),
        Flip,
        Line(r#"{"jsonrpc":"2.0","id":20,"method":"tools/list"}"#.into(), 1),
        Line(call(21, "stub_new", "{}"), 1),
        Line(call(22, "stub_big", "{}"), 1),
    ]
}

struct Run {
    stdout: Vec<String>,
    stderr: Vec<String>,
    code: i32,
    idle: Vec<(String, u64, u64)>,
    busy: Vec<(String, u64, u64)>,
}

fn run_a(program: &str, env: &[(String, String)], cwd: &Path) -> Run {
    let mut b = Bridge::start(program, &[], env, cwd);
    let script = script_a();
    for (line, n) in &script[..4] {
        b.exchange(line, *n);
    }
    std::thread::sleep(Duration::from_secs(3));
    let idle = memory(b.child.id());
    for (line, n) in &script[4..] {
        b.exchange(line, *n);
    }
    let busy = memory(b.child.id());
    let (stdout, code, stderr) = b.finish();
    Run { stdout, stderr, code, idle, busy }
}

fn run_b(program: &str, env: &[(String, String)], cwd: &Path) -> Run {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind the stub");
    let port = listener.local_addr().unwrap().port();
    let flipped = Arc::new(AtomicBool::new(false));
    let f2 = Arc::clone(&flipped);
    std::thread::spawn(move || serve_stub(listener, f2));
    let extra = vec!["--url".to_string(), format!("ws://127.0.0.1:{port}"), "--max-spawns".into(), "2".into()];
    let mut b = Bridge::start(program, &extra, env, cwd);
    for step in script_b() {
        match step {
            StepB::Line(l, n) => b.exchange(&l, n),
            StepB::Flip => {
                flipped.store(true, Ordering::SeqCst);
                if b.read(NOTIFY_TIMEOUT).is_none() {
                    b.stdout.push("<no notifications/tools/list_changed>".into());
                }
            }
        }
    }
    let busy = memory(b.child.id());
    let (stdout, code, stderr) = b.finish();
    Run { stdout, stderr, code, idle: Vec::new(), busy }
}

/// The stderr lines that are the bridge's own (a JVM may add its own noise).
fn own_stderr(lines: &[String]) -> Vec<String> {
    lines.iter().filter(|l| l.starts_with("infantry mcp:") || l.starts_with("error:") || l.starts_with("warning:")).cloned().collect()
}

fn compare(label: &str, k: &[String], r: &[String]) -> bool {
    if k == r {
        println!("  {label}: IDENTICAL ({} lines)", k.len());
        return true;
    }
    println!("  {label}: DIFFERENT (kotlin {} lines, rust {} lines)", k.len(), r.len());
    for i in 0..k.len().max(r.len()) {
        let (a, b) = (k.get(i), r.get(i));
        if a != b {
            let cut = |s: Option<&String>| s.map_or("<none>".to_string(), |s| s.chars().take(400).collect());
            println!("    first difference at line {}:\n      kotlin: {}\n      rust:   {}", i + 1, cut(a), cut(b));
            break;
        }
    }
    false
}

/// The golden descriptors (byte for byte, per tool name) checked against a tools/list answer line.
fn golden_check(golden: &Path, tools_line: &str) -> (usize, usize, Vec<String>) {
    let text = std::fs::read_to_string(golden).unwrap_or_default();
    let golden: Vec<Value> = serde_json::from_str(&text).unwrap_or_default();
    let answer: Value = serde_json::from_str(tools_line).unwrap_or(Value::Null);
    let served = answer["result"]["tools"].as_array().cloned().unwrap_or_default();
    let (mut present, mut same, mut differ) = (0, 0, Vec::new());
    for g in &golden {
        let name = g["name"].as_str().unwrap_or("");
        if let Some(s) = served.iter().find(|s| s["name"].as_str() == Some(name)) {
            present += 1;
            if serde_json::to_string(s).unwrap() == serde_json::to_string(g).unwrap() {
                same += 1;
            } else {
                differ.push(name.to_string());
            }
        }
    }
    (present, same, differ)
}

fn main() {
    let args = match parse_args() {
        Ok(a) => a,
        Err(e) => {
            eprintln!("error: {e}");
            std::process::exit(2);
        }
    };
    let _ = std::fs::create_dir_all(&args.out);
    let cwd = args.out.join("cwd");
    let _ = std::fs::create_dir_all(cwd.join("sub"));
    std::fs::write(cwd.join("input.txt"), "local file \u{e9}\n").unwrap();

    let mut env_a = args.env.clone();
    env_a.push(("INFANTRY_LIMIT_MCP_TOOLS_WATCH_SECONDS".into(), "0".into()));
    let mut env_b: Vec<(String, String)> = args.env.iter().filter(|(k, _)| k == "INFANTRY_BIN").cloned().collect();
    env_b.extend([
        ("INFANTRY_LIMIT_MCP_TOOLS_WATCH_SECONDS".to_string(), "1".to_string()),
        ("INFANTRY_LIMIT_MCP_BODY_CHARS".to_string(), "123".to_string()),
        ("INFANTRY_SESSION_ID".to_string(), "conf-session".to_string()),
        ("INFANTRY_ROLE".to_string(), " backend ".to_string()),
        ("INFANTRY_MCP_DEPTH".to_string(), "1".to_string()),
    ]);

    let mut ok = true;
    let mut report = Vec::new();
    for (phase, run) in [("A (test daemon)", run_a as fn(&str, &[(String, String)], &Path) -> Run), ("B (stub daemon)", run_b)] {
        println!("phase {phase}");
        let env = if phase.starts_with('A') { &env_a } else { &env_b };
        let k = run(&args.kotlin, env, &cwd);
        let r = run(&args.rust, env, &cwd);
        let tag = &phase[..1];
        let _ = std::fs::write(args.out.join(format!("{tag}-kotlin.stdout")), k.stdout.join("\n"));
        let _ = std::fs::write(args.out.join(format!("{tag}-rust.stdout")), r.stdout.join("\n"));
        let _ = std::fs::write(args.out.join(format!("{tag}-kotlin.stderr")), k.stderr.join("\n"));
        let _ = std::fs::write(args.out.join(format!("{tag}-rust.stderr")), r.stderr.join("\n"));
        ok &= compare("stdout", &k.stdout, &r.stdout);
        ok &= compare("stderr (own lines)", &own_stderr(&k.stderr), &own_stderr(&r.stderr));
        if k.code == r.code {
            println!("  exit code: both {}", k.code);
        } else {
            println!("  exit code: DIFFERENT (kotlin {}, rust {})", k.code, r.code);
            ok = false;
        }
        if tag == "B" {
            let notified = r.stdout.iter().any(|l| l.contains("notifications/tools/list_changed"));
            println!("  list_changed sent after the flip: {}", if notified { "yes" } else { "NO" });
            ok &= notified;
        }
        if tag == "A" {
            if let (Some(g), Some(kl), Some(rl)) = (&args.golden, k.stdout.get(2), r.stdout.get(2)) {
                let (kp, ks, _) = golden_check(g, kl);
                let (rp, rs, rd) = golden_check(g, rl);
                println!("  golden: kotlin {ks}/{kp} served golden tools byte-identical, rust {rs}/{rp}");
                if rs < ks {
                    println!("    rust differs on: {}", rd.join(", "));
                    ok = false;
                }
            }
            report.push(format!("kotlin idle {}", describe(&k.idle)));
            report.push(format!("rust   idle {}", describe(&r.idle)));
        }
        report.push(format!("kotlin after phase {tag} calls {}", describe(&k.busy)));
        report.push(format!("rust   after phase {tag} calls {}", describe(&r.busy)));
    }
    println!("memory (working set):");
    for l in &report {
        println!("  {l}");
    }
    println!("{}", if ok { "CONFORMANCE: IDENTICAL" } else { "CONFORMANCE: DIFFERENCES FOUND" });
    std::process::exit(if ok { 0 } else { 1 });
}
