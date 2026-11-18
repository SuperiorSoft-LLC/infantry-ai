// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The MCP stdio server: one JSON-RPC 2.0 message per line in, one per line out; stdout carries the protocol only.
//! A port of `McpServer`: the same methods, answers, error codes and texts, the same key order, and the same tools
//! watch that sends `notifications/tools/list_changed` when the daemon's tool list changes.

use crate::env;
use crate::output::Output;
use crate::tools::{self, Tools};
use serde_json::{Map, Value};
use std::io::BufRead;
use std::sync::mpsc::{self, RecvTimeoutError};
use std::sync::{Arc, Mutex};
use std::time::Duration;

pub const SERVER_NAME: &str = "infantry";
pub const SERVER_VERSION: &str = env!("INFANTRY_CONTRACT_VERSION");
const SUPPORTED_PROTOCOL_VERSIONS: [&str; 3] = ["2025-06-18", "2025-03-26", "2024-11-05"];
const PARSE_ERROR: i64 = -32700;
const INVALID_REQUEST: i64 = -32600;
const METHOD_NOT_FOUND: i64 = -32601;
const INVALID_PARAMS: i64 = -32602;

const INSTRUCTIONS: &str = "Infantry AI daemon: list sessions, inspect one session's status and transcript, summarise the \
agents/roles of the pod; spawn, prompt and kill sessions and send COMMS mail, within this server's depth, concurrency and \
kill-scope guardrails; create, update and read board tickets. To put an agent to work on a ticket, pin its role / provider \
/ model (infantry_list_harnesses lists the harnesses and their models), then start it with infantry_ticket_start: the \
worker runs on the ticket's own branch and the ticket goes In Progress. infantry_spawn alone works outside the board. \
Every tool is named infantry_<area>[_<verb>] (areas such as ticket, board, message, ask, script, secret, run, project); \
when your client loads tools on demand, search by area. A tool that needs a project takes projectId; left out, it is the \
project registered for this folder, if any.";

struct State {
    tools: Tools,
    fingerprint: Option<String>,
}

pub struct Server {
    state: Arc<Mutex<State>>,
    out: Output,
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
        Server { state: Arc::new(Mutex::new(State { tools, fingerprint: None })), out }
    }

    /// Serves until stdin closes; returns the exit code (0).
    pub fn serve<R: BufRead>(&self, input: R) -> i32 {
        self.out.err(&format!("infantry mcp: serving MCP over stdio ({SERVER_NAME} {SERVER_VERSION})"));
        let stop = self.start_tools_watch();
        let mut reader = LineReader::new(input);
        while let Some(line) = reader.read_line() {
            if line.trim().is_empty() {
                continue;
            }
            let response = {
                let mut state = self.state.lock().unwrap_or_else(|p| p.into_inner());
                handle_line(&mut state, &self.out, &line)
            };
            if let Some(r) = response {
                self.out.message(&r);
            }
        }
        drop(stop);
        self.out.err("infantry mcp: stdin closed, exiting");
        0
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

fn initialize_result(params: &Map<String, Value>) -> Value {
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
        ("instructions", Value::String(INSTRUCTIONS.into())),
    ])
}

fn canonical_tool_name(name: String) -> String {
    match name.strip_prefix("agenthq_") {
        Some(rest) => format!("infantry_{rest}"),
        None => name,
    }
}

/// `handleLine`: the answer to one line, or None (a notification).
fn handle_line(state: &mut State, out: &Output, line: &str) -> Option<Value> {
    let message: Value = match serde_json::from_str(line) {
        Ok(m) => m,
        Err(e) => {
            let first = e.to_string().lines().next().unwrap_or("invalid JSON").to_string();
            return Some(error(Value::Null, PARSE_ERROR, format!("Parse error: {first}")));
        }
    };
    let Value::Object(message) = message else {
        return Some(error(Value::Null, INVALID_REQUEST, "Invalid Request: expected a single JSON-RPC object".into()));
    };
    let id = message.get("id").cloned();
    let method = match message.get("method") {
        Some(Value::String(m)) => Some(m.clone()),
        _ => None,
    };
    let Some(method) = method else {
        return id.map(|id| error(id, INVALID_REQUEST, "Invalid Request: missing method".into()));
    };
    let params = match message.get("params") {
        Some(Value::Object(p)) => p.clone(),
        _ => Map::new(),
    };
    let Some(id) = id else {
        if method != "notifications/initialized" {
            out.err(&format!("infantry mcp: ignoring notification '{method}'"));
        }
        return None;
    };
    Some(match method.as_str() {
        "initialize" => result(id, initialize_result(&params)),
        "ping" => result(id, Value::Object(Map::new())),
        "tools/list" => {
            let plugin = state.tools.plugin_descriptors();
            if state.tools.daemon_answered {
                state.fingerprint = Some(fingerprint(&plugin));
            }
            let own: Value = serde_json::from_str(tools::WATCH_FILE_DESCRIPTOR).unwrap_or(Value::Null);
            let mut all = vec![own];
            all.extend(plugin);
            result(id, obj(vec![("tools", Value::Array(all))]))
        }
        "tools/call" => {
            let Some(name) = tools::content_or_null(params.get("name")).map(canonical_tool_name) else {
                return Some(error(id, INVALID_PARAMS, "Invalid params: tools/call requires a tool name".into()));
            };
            if name != tools::WATCH_FILE && !state.tools.is_plugin_tool(&name) && state.tools.daemon_answered {
                return Some(error(id, INVALID_PARAMS, format!("Unknown tool: {name}")));
            }
            let arguments = match params.get("arguments") {
                Some(Value::Object(a)) => a.clone(),
                _ => Map::new(),
            };
            result(id, state.tools.call(&name, arguments).to_json())
        }
        _ => error(id, METHOD_NOT_FOUND, format!("Method not found: {method}")),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

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
        assert_eq!(initialize_result(&p)["protocolVersion"], "2025-03-26");
        p.insert("protocolVersion".into(), Value::String("1999-01-01".into()));
        assert_eq!(initialize_result(&p)["protocolVersion"], "2025-06-18");
    }
}
