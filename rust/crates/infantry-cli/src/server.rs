// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The MCP stdio server: one JSON-RPC 2.0 message per line in, one per line out; stdout carries the protocol only.
//! A port of `McpServer`: the same methods, answers, error codes and texts, the same key order, and the same tools
//! watch that sends `notifications/tools/list_changed` when the daemon's tool list changes.

use crate::env;
use crate::output::Output;
use crate::tools::{self, Tools};
use serde_json::{Map, Value};
use crate::tools::ToolResult;
use std::collections::HashMap;
use std::io::BufRead;
use std::net::{Shutdown, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, RecvTimeoutError};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::Duration;

pub const SERVER_NAME: &str = "infantry";
pub const SERVER_VERSION: &str = infantry_protocol::CONTRACT_VERSION;
const SUPPORTED_PROTOCOL_VERSIONS: [&str; 3] = ["2025-06-18", "2025-03-26", "2024-11-05"];
const PARSE_ERROR: i64 = -32700;
const INVALID_REQUEST: i64 = -32600;
const METHOD_NOT_FOUND: i64 = -32601;
const INVALID_PARAMS: i64 = -32602;
/// A `tools/call` past `limits.mcp.maxConcurrentCalls` (JSON-RPC server-error range).
const SERVER_BUSY: i64 = -32000;

/// The core text every agent gets (communication: sessions, message, inbox, read, ask). A feature adds its own
/// paragraph through the `instructions` key of one of its tool descriptors (AR-6): no board, no ticket text.
const INSTRUCTIONS: &str = "Infantry AI daemon: list sessions, inspect one session's status and transcript, summarise the agents/roles of the pod; spawn, prompt and kill sessions and send COMMS mail, within this server's depth, concurrency and kill-scope guardrails. Every tool is named infantry_<area>[_<verb>] (areas such as message, ask, script, secret, run, project); when your client loads tools on demand, search by area. A tool that needs a project takes projectId; left out, it is the project registered for this folder, if any.";

/// The server instructions: the core text, then each paragraph the listed tools contribute, in list order.
pub fn instructions(contributed: &[String]) -> String {
    let mut out = INSTRUCTIONS.to_string();
    for p in contributed {
        out.push_str("

");
        out.push_str(p);
    }
    out
}

struct State {
    tools: Tools,
    fingerprint: Option<String>,
}

/// Runs one tool call on a snapshot of the tools; `opened` receives the call's socket so a cancel can cut it.
pub type Exec = dyn Fn(&mut Tools, &str, Map<String, Value>, &dyn Fn(Option<TcpStream>)) -> ToolResult + Send + Sync;

/// The `tools/call` requests running now, by JSON-RPC id (its JSON text). The value is the call's socket, once open.
type Inflight = Arc<Mutex<HashMap<String, Option<TcpStream>>>>;

pub struct Server {
    state: Arc<Mutex<State>>,
    out: Output,
    exec: Arc<Exec>,
    inflight: Inflight,
    broken: Arc<AtomicBool>,
    max_calls: usize,
}

enum Dispatch {
    Answer(Value),
    Spawned(JoinHandle<()>),
    Nothing,
}

struct Parsed {
    id: Option<Value>,
    method: String,
    params: Map<String, Value>,
}

fn obj(pairs: Vec<(&str, Value)>) -> Value {
    let mut m = Map::new();
    for (k, v) in pairs {
        m.insert(k.to_string(), v);
    }
    Value::Object(m)
}

fn result(id: Value, result: Value) -> Value {
    obj(vec![("jsonrpc", Value::String("2.0".into())), ("id", id), ("result", result)])
}

fn error(id: Value, code: i64, message: String) -> Value {
    obj(vec![
        ("jsonrpc", Value::String("2.0".into())),
        ("id", id),
        ("error", obj(vec![("code", Value::Number(code.into())), ("message", Value::String(message))])),
    ])
}

fn fingerprint(list: &[Value]) -> String {
    serde_json::to_string(list).unwrap_or_default()
}

/// Reads lines as `BufferedReader.readLine` does: a line ends at `\n`, `\r\n` or a lone `\r`; bytes are UTF-8 (lossy).
pub struct LineReader<R: BufRead> {
    inner: R,
    skip_lf: bool,
}

impl<R: BufRead> LineReader<R> {
    pub fn new(inner: R) -> Self {
        LineReader { inner, skip_lf: false }
    }

    pub fn read_line(&mut self) -> Option<String> {
        let mut bytes = Vec::new();
        let mut any = false;
        loop {
            let buf = match self.inner.fill_buf() {
                Ok(b) => b,
                Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
                Err(_) => &[],
            };
            if buf.is_empty() {
                return if any { Some(String::from_utf8_lossy(&bytes).into_owned()) } else { None };
            }
            let mut used = 0;
            let mut done = false;
            for &b in buf {
                used += 1;
                if self.skip_lf {
                    self.skip_lf = false;
                    if b == b'\n' {
                        continue;
                    }
                }
                any = true;
                match b {
                    b'\n' => {
                        done = true;
                        break;
                    }
                    b'\r' => {
                        self.skip_lf = true;
                        done = true;
                        break;
                    }
                    _ => bytes.push(b),
                }
            }
            self.inner.consume(used);
            if done {
                return Some(String::from_utf8_lossy(&bytes).into_owned());
            }
        }
    }
}

impl Server {
    pub fn new(tools: Tools, out: Output) -> Self {
        let max = env::MCP_MAX_CONCURRENT_CALLS.from_env(&env::raw).unwrap_or(env::MCP_MAX_CONCURRENT_CALLS.default);
        let exec: Arc<Exec> = Arc::new(|t: &mut Tools, name: &str, args: Map<String, Value>, opened: &dyn Fn(Option<TcpStream>)| {
            t.call_with(name, args, opened)
        });
        Self::with_exec(tools, out, exec, max.max(1) as usize)
    }

    pub fn with_exec(tools: Tools, out: Output, exec: Arc<Exec>, max_calls: usize) -> Self {
        Server {
            state: Arc::new(Mutex::new(State { tools, fingerprint: None })),
            out,
            exec,
            inflight: Arc::new(Mutex::new(HashMap::new())),
            broken: Arc::new(AtomicBool::new(false)),
            max_calls: max_calls.max(1),
        }
    }

    /// Serves until stdin closes or stdout breaks; returns the exit code (0).
    ///
    /// `tools/call` runs on its own thread (at most `limits.mcp.maxConcurrentCalls` at once) and answers by its id when
    /// done, so a long call never holds up the others. Everything else (`initialize`, `ping`, `tools/list`, notices)
    /// is answered on the reading thread in arrival order. A `notifications/cancelled` ends its call; it gets no answer.
    pub fn serve<R: BufRead>(&self, input: R) -> i32 {
        self.out.err(&format!("infantry mcp: serving MCP over stdio ({SERVER_NAME} {SERVER_VERSION})"));
        let stop = self.start_tools_watch();
        let mut reader = LineReader::new(input);
        let mut workers: Vec<JoinHandle<()>> = Vec::new();
        while !self.broken.load(Ordering::SeqCst) {
            let Some(line) = reader.read_line() else { break };
            if line.trim().is_empty() {
                continue;
            }
            workers.retain(|w| !w.is_finished());
            match self.dispatch(&line) {
                Dispatch::Answer(r) => {
                    if !self.out.message(&r) {
                        self.broken.store(true, Ordering::SeqCst);
                    }
                }
                Dispatch::Spawned(w) => workers.push(w),
                Dispatch::Nothing => {}
            }
        }
        if self.broken.load(Ordering::SeqCst) {
            // Nobody can read an answer any more: end every running call.
            self.out.err("infantry mcp: stdout closed, ending the running calls");
            self.abort_all();
        }
        // Stdin closed: the running calls still answer (each ends within its own timeout).
        for w in workers {
            let _ = w.join();
        }
        drop(stop);
        self.out.err("infantry mcp: stdin closed, exiting");
        0
    }

    fn abort_all(&self) {
        let mut map = self.inflight.lock().unwrap_or_else(|p| p.into_inner());
        for (_, sock) in map.drain() {
            if let Some(s) = sock {
                let _ = s.shutdown(Shutdown::Both);
            }
        }
    }

    /// Ends the call with this id: its answer is dropped and its socket shut. False when no such call runs.
    fn cancel(&self, key: &str) -> bool {
        let removed = self.inflight.lock().unwrap_or_else(|p| p.into_inner()).remove(key);
        match removed {
            Some(sock) => {
                if let Some(s) = sock {
                    let _ = s.shutdown(Shutdown::Both);
                }
                true
            }
            None => false,
        }
    }

    fn dispatch(&self, line: &str) -> Dispatch {
        let parsed = match parse(line) {
            Ok(p) => p,
            Err(answer) => return Dispatch::Answer(answer),
        };
        if parsed.id.is_none() && parsed.method == "notifications/cancelled" {
            if let Some(rid) = parsed.params.get("requestId") {
                let key = serde_json::to_string(rid).unwrap_or_default();
                if self.cancel(&key) {
                    self.out.err(&format!("infantry mcp: call {key} cancelled by the client"));
                }
            }
            return Dispatch::Nothing;
        }
        if let (Some(id), "tools/call") = (&parsed.id, parsed.method.as_str()) {
            return self.start_call(id.clone(), &parsed.params);
        }
        let mut state = self.state.lock().unwrap_or_else(|p| p.into_inner());
        match handle_parsed(&mut state, &self.out, parsed) {
            Some(r) => Dispatch::Answer(r),
            None => Dispatch::Nothing,
        }
    }

    fn start_call(&self, id: Value, params: &Map<String, Value>) -> Dispatch {
        let (name, mut snapshot) = {
            let mut state = self.state.lock().unwrap_or_else(|p| p.into_inner());
            match check_call(&mut state, params) {
                Ok(name) => (name, state.tools.clone()),
                Err((code, msg)) => return Dispatch::Answer(error(id, code, msg)),
            }
        };
        let key = serde_json::to_string(&id).unwrap_or_default();
        {
            let mut map = self.inflight.lock().unwrap_or_else(|p| p.into_inner());
            if map.contains_key(&key) {
                return Dispatch::Answer(error(id, INVALID_REQUEST, format!("Invalid Request: call {key} is already running")));
            }
            if map.len() >= self.max_calls {
                let msg = format!(
                    "Server busy: {} tool calls already running (limits.mcp.maxConcurrentCalls = {}); retry when one ends",
                    map.len(),
                    self.max_calls
                );
                return Dispatch::Answer(error(id, SERVER_BUSY, msg));
            }
            map.insert(key.clone(), None);
        }
        let arguments = match params.get("arguments") {
            Some(Value::Object(a)) => a.clone(),
            _ => Map::new(),
        };
        let exec = Arc::clone(&self.exec);
        let inflight = Arc::clone(&self.inflight);
        let out = self.out.clone();
        let broken = Arc::clone(&self.broken);
        let worker_key = key.clone();
        let spawned = std::thread::Builder::new().name("infantry-mcp-call".into()).spawn(move || {
            let opened = |sock: Option<TcpStream>| {
                let mut map = inflight.lock().unwrap_or_else(|p| p.into_inner());
                match map.get_mut(&worker_key) {
                    Some(slot) => *slot = sock,
                    // Cancelled before the socket opened: cut it now.
                    None => {
                        if let Some(s) = sock {
                            let _ = s.shutdown(Shutdown::Both);
                        }
                    }
                }
            };
            let r = exec(&mut snapshot, &name, arguments, &opened);
            let wanted = inflight.lock().unwrap_or_else(|p| p.into_inner()).remove(&worker_key).is_some();
            if wanted && !broken.load(Ordering::SeqCst) && !out.message(&result(id, r.to_json())) {
                broken.store(true, Ordering::SeqCst);
            }
        });
        match spawned {
            Ok(w) => Dispatch::Spawned(w),
            Err(e) => {
                self.inflight.lock().unwrap_or_else(|p| p.into_inner()).remove(&key);
                Dispatch::Answer(error(Value::Null, SERVER_BUSY, format!("Server busy: could not start the call: {e}")))
            }
        }
    }

    /// The tools watch (`limits.mcp.toolsWatchSeconds`, 0 = off). Dropping the returned sender stops it.
    fn start_tools_watch(&self) -> Option<mpsc::Sender<()>> {
        let seconds = env::MCP_TOOLS_WATCH_SECONDS.from_env(&env::raw).unwrap_or(env::MCP_TOOLS_WATCH_SECONDS.default);
        if seconds <= 0 {
            return None;
        }
        let (tx, rx) = mpsc::channel::<()>();
        let state = Arc::clone(&self.state);
        let out = self.out.clone();
        std::thread::Builder::new()
            .name("infantry-mcp-tools-watch".into())
            .spawn(move || loop {
                match rx.recv_timeout(Duration::from_secs(seconds as u64)) {
                    Err(RecvTimeoutError::Timeout) => {
                        let mut s = state.lock().unwrap_or_else(|p| p.into_inner());
                        check_tools_changed(&mut s, &out);
                    }
                    _ => return,
                }
            })
            .ok()?;
        Some(tx)
    }
}

/// `checkToolsChanged`: true when a notification was sent. The first look only records the baseline.
fn check_tools_changed(state: &mut State, out: &Output) -> bool {
    let list = state.tools.plugin_descriptors();
    if !state.tools.daemon_answered {
        return false;
    }
    let now = fingerprint(&list);
    let before = state.fingerprint.replace(now.clone());
    match before {
        Some(b) if b != now => {
            out.message(&obj(vec![
                ("jsonrpc", Value::String("2.0".into())),
                ("method", Value::String("notifications/tools/list_changed".into())),
            ]));
            true
        }
        _ => false,
    }
}

fn initialize_result(params: &Map<String, Value>, contributed: &[String]) -> Value {
    let requested = tools::content_or_null(params.get("protocolVersion"));
    let version = requested
        .filter(|r| SUPPORTED_PROTOCOL_VERSIONS.contains(&r.as_str()))
        .unwrap_or_else(|| SUPPORTED_PROTOCOL_VERSIONS[0].to_string());
    obj(vec![
        ("protocolVersion", Value::String(version)),
        ("capabilities", obj(vec![("tools", obj(vec![("listChanged", Value::Bool(true))]))])),
        (
            "serverInfo",
            obj(vec![("name", Value::String(SERVER_NAME.into())), ("version", Value::String(SERVER_VERSION.into()))]),
        ),
        ("instructions", Value::String(instructions(contributed))),
    ])
}

fn canonical_tool_name(name: String) -> String {
    match name.strip_prefix("agenthq_") {
        Some(rest) => format!("infantry_{rest}"),
        None => name,
    }
}

/// The envelope checks of `handleLine`: a request or notification, or the error answer. A notification without a
/// method comes back with an empty method (no answer).
fn parse(line: &str) -> Result<Parsed, Value> {
    let message: Value = match serde_json::from_str(line) {
        Ok(m) => m,
        Err(e) => {
            let first = e.to_string().lines().next().unwrap_or("invalid JSON").to_string();
            return Err(error(Value::Null, PARSE_ERROR, format!("Parse error: {first}")));
        }
    };
    let Value::Object(message) = message else {
        return Err(error(Value::Null, INVALID_REQUEST, "Invalid Request: expected a single JSON-RPC object".into()));
    };
    let id = message.get("id").cloned();
    let method = match message.get("method") {
        Some(Value::String(m)) => m.clone(),
        _ => {
            return match id {
                Some(id) => Err(error(id, INVALID_REQUEST, "Invalid Request: missing method".into())),
                None => Ok(Parsed { id: None, method: String::new(), params: Map::new() }),
            }
        }
    };
    let params = match message.get("params") {
        Some(Value::Object(p)) => p.clone(),
        _ => Map::new(),
    };
    Ok(Parsed { id, method, params })
}

/// The tool name of a `tools/call`, or the error code and text.
fn check_call(state: &mut State, params: &Map<String, Value>) -> Result<String, (i64, String)> {
    let Some(name) = tools::content_or_null(params.get("name")).map(canonical_tool_name) else {
        return Err((INVALID_PARAMS, "Invalid params: tools/call requires a tool name".into()));
    };
    if name != tools::WATCH_FILE && name != crate::localrepo::MCP_NAME && !state.tools.is_plugin_tool(&name) && state.tools.daemon_answered {
        return Err((INVALID_PARAMS, format!("Unknown tool: {name}")));
    }
    Ok(name)
}

/// The answer to a parsed line handled on the reading thread, or None (a notification). `tools/call` normally runs on
/// its own thread ([Server::start_call]); here it runs inline (the serial path, kept for the tests).
fn handle_parsed(state: &mut State, out: &Output, parsed: Parsed) -> Option<Value> {
    let Parsed { id, method, params } = parsed;
    let Some(id) = id else {
        if !method.is_empty() && method != "notifications/initialized" {
            out.err(&format!("infantry mcp: ignoring notification '{method}'"));
        }
        return None;
    };
    Some(match method.as_str() {
        "initialize" => {
            state.tools.plugin_descriptors();
            let contributed = state.tools.instructions.clone();
            result(id, initialize_result(&params, &contributed))
        }
        "ping" => result(id, Value::Object(Map::new())),
        "tools/list" => {
            let plugin = state.tools.plugin_descriptors();
            if state.tools.daemon_answered {
                state.fingerprint = Some(fingerprint(&plugin));
            }
            let own: Value = serde_json::from_str(tools::WATCH_FILE_DESCRIPTOR).unwrap_or(Value::Null);
            let mut all = vec![own, serde_json::from_str(crate::localrepo::MCP_DESCRIPTOR).unwrap_or(Value::Null)];
            all.extend(plugin);
            result(id, obj(vec![("tools", Value::Array(all))]))
        }
        "tools/call" => match check_call(state, &params) {
            Err((code, msg)) => error(id, code, msg),
            Ok(name) => {
                let arguments = match params.get("arguments") {
                    Some(Value::Object(a)) => a.clone(),
                    _ => Map::new(),
                };
                result(id, state.tools.call(&name, arguments).to_json())
            }
        },
        _ => error(id, METHOD_NOT_FOUND, format!("Method not found: {method}")),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::time::Instant;

    /// A stand-in daemon port that takes connections and holds them open (a call waits on it until cut).
    fn holder() -> (Arc<Mutex<Vec<TcpStream>>>, std::net::SocketAddr) {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = l.local_addr().unwrap();
        let held = Arc::new(Mutex::new(Vec::new()));
        let h = Arc::clone(&held);
        std::thread::spawn(move || {
            for s in l.incoming().flatten() {
                h.lock().unwrap().push(s);
            }
        });
        (held, addr)
    }

    fn tools(addr: std::net::SocketAddr) -> Tools {
        let target = crate::target::Target {
            url: format!("ws://{addr}/ws"),
            token: None,
            token_source: String::new(),
            answer_timeout_ms: 300,
            connect_timeout_ms: 300,
            session_id: None,
        };
        Tools::new(target, |_| {}, |_| None, std::path::PathBuf::from("."), Default::default(), None)
    }

    /// "slow" holds a socket to [addr] until it is cut or 3 s pass; anything else answers at once.
    fn exec(addr: std::net::SocketAddr) -> Arc<Exec> {
        Arc::new(move |_t: &mut Tools, name: &str, _a: Map<String, Value>, opened: &dyn Fn(Option<TcpStream>)| {
            if name == "slow" {
                let mut s = TcpStream::connect(addr).unwrap();
                s.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
                opened(s.try_clone().ok());
                let _ = s.read(&mut [0u8; 1]);
            }
            ToolResult { text: name.to_string(), is_error: false }
        })
    }

    #[derive(Clone, Default)]
    struct Sink(Arc<Mutex<Vec<u8>>>, bool);
    impl Write for Sink {
        fn write(&mut self, b: &[u8]) -> std::io::Result<usize> {
            if self.1 {
                return Err(std::io::Error::from(std::io::ErrorKind::BrokenPipe));
            }
            self.0.lock().unwrap().extend_from_slice(b);
            Ok(b.len())
        }
        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    fn call(id: i64, name: &str) -> String {
        format!(r#"{{"jsonrpc":"2.0","id":{id},"method":"tools/call","params":{{"name":"{name}"}}}}"#)
    }

    fn run(max: usize, broken: bool, lines: &[String]) -> (Vec<Value>, Duration) {
        std::env::set_var("INFANTRY_LIMIT_MCP_TOOLS_WATCH_SECONDS", "0");
        let (_held, addr) = holder();
        let sink = Sink(Arc::default(), broken);
        let server = Server::with_exec(tools(addr), Output::with_sink(false, Box::new(sink.clone())), exec(addr), max);
        let input = lines.join("\n") + "\n";
        let t = Instant::now();
        server.serve(input.as_bytes());
        let took = t.elapsed();
        let text = String::from_utf8(sink.0.lock().unwrap().clone()).unwrap();
        (text.lines().map(|l| serde_json::from_str(l).unwrap()).collect(), took)
    }

    fn ids(msgs: &[Value]) -> Vec<i64> {
        msgs.iter().filter_map(|m| m["id"].as_i64()).collect()
    }

    #[test]
    fn list_and_calls_answer_during_a_long_call() {
        let list = r#"{"jsonrpc":"2.0","id":2,"method":"tools/list"}"#.to_string();
        let (msgs, _) = run(8, false, &[call(1, "slow"), list, call(3, "fast")]);
        // The slow call answers last (after its 3 s hold); the list and the fast call do not wait for it.
        assert_eq!(ids(&msgs), vec![2, 3, 1]);
        assert_eq!(msgs[1]["result"]["content"][0]["text"], "fast");
    }

    #[test]
    fn a_call_past_the_bound_is_refused_at_once() {
        let (msgs, _) = run(1, false, &[call(1, "slow"), call(2, "slow")]);
        assert_eq!(ids(&msgs), vec![2, 1]);
        assert_eq!(msgs[0]["error"]["code"], SERVER_BUSY);
        assert!(msgs[0]["error"]["message"].as_str().unwrap().contains("limits.mcp.maxConcurrentCalls"));
    }

    #[test]
    fn a_cancelled_call_ends_and_is_not_answered() {
        let cancel = r#"{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":1}}"#.to_string();
        let (msgs, took) = run(8, false, &[call(1, "slow"), cancel]);
        assert!(msgs.is_empty(), "{msgs:?}");
        assert!(took < Duration::from_secs(2), "the call was not cut: {took:?}");
    }

    #[test]
    fn broken_stdout_ends_the_running_calls() {
        let ping = r#"{"jsonrpc":"2.0","id":2,"method":"ping"}"#.to_string();
        let (msgs, took) = run(8, true, &[call(1, "slow"), ping, call(3, "slow")]);
        assert!(msgs.is_empty());
        assert!(took < Duration::from_secs(2), "the running call was not cut: {took:?}");
    }

    #[test]
    fn line_reader_ends_lines_like_java() {
        let mut r = LineReader::new(&b"a\r\nb\rc\n\nd"[..]);
        let lines: Vec<String> = std::iter::from_fn(|| r.read_line()).collect();
        assert_eq!(lines, vec!["a", "b", "c", "", "d"]);
        let mut r = LineReader::new(&b"x\r"[..]);
        assert_eq!(r.read_line().as_deref(), Some("x"));
        assert_eq!(r.read_line(), None);
    }

    #[test]
    fn envelopes_in_kotlin_key_order() {
        assert_eq!(
            serde_json::to_string(&error(Value::Null, PARSE_ERROR, "x".into())).unwrap(),
            r#"{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"x"}}"#
        );
        let id: Value = serde_json::from_str("1.50").unwrap();
        assert_eq!(serde_json::to_string(&result(id, Value::Object(Map::new()))).unwrap(), r#"{"jsonrpc":"2.0","id":1.50,"result":{}}"#);
    }

    #[test]
    fn initialize_picks_a_supported_version() {
        let mut p = Map::new();
        p.insert("protocolVersion".into(), Value::String("2025-03-26".into()));
        assert_eq!(initialize_result(&p, &[])["protocolVersion"], "2025-03-26");
        p.insert("protocolVersion".into(), Value::String("1999-01-01".into()));
        assert_eq!(initialize_result(&p, &[])["protocolVersion"], "2025-06-18");
    }

    /// AR-6 (NoBoardToolsTest): with no board tools listed, the instructions carry no ticket text.
    #[test]
    fn no_board_tools_means_no_ticket_sentence() {
        let text = initialize_result(&Map::new(), &[])["instructions"].as_str().unwrap().to_string();
        assert!(!text.to_lowercase().contains("ticket"), "{text}");
        assert!(text.starts_with("Infantry AI daemon:"));
    }

    /// AR-6: a contributed paragraph follows the core text.
    #[test]
    fn contributed_paragraphs_follow_the_core_text() {
        let text = instructions(&["Board: tickets.".to_string()]);
        assert!(text.starts_with(INSTRUCTIONS));
        assert!(text.ends_with("

Board: tickets."));
    }
}
