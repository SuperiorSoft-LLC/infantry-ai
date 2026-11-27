// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The tools: the one tool the bridge serves itself (`infantry_watch_file`) and the daemon's plugin tools, relayed over
//! `plugin_tools` / `plugin_tool_call` exactly as `McpTools` + `McpPluginTools` + `McpWatchTools` relay them.

use crate::env;
use crate::frames::{FrameError, FrameStream};
use crate::target::Target;
use base64::Engine;
use serde_json::{Map, Value};
use std::collections::{HashMap, HashSet};
use std::path::{Path, PathBuf};

pub const WATCH_FILE: &str = "infantry_watch_file";
pub const DAEMON_UNREACHABLE: &str =
    "Infantry AI daemon is not reachable. Start it with `infantry daemon` (or check --url / --port), then retry.";
const LOCAL_FILES_KEY: &str = "localFiles";
/// A descriptor's paragraph for the MCP server instructions (AR-6); stripped from `tools/list`.
const INSTRUCTIONS_KEY: &str = "instructions";
const LOCAL_FILE_MAX_BYTES: u64 = 10 * 1024 * 1024;
const DETAIL_DESCRIPTION: &str =
    "How much to send back: ack (tiny), summary (compact), full. Default: the tool's own, or setting limits.mcp.defaultDetail.";
const MAX_CHARS_DESCRIPTION: &str =
    "Cut this call's result at this many characters (default: setting limits.mcp.maxResultChars).";

/// The descriptor of `infantry_watch_file`, byte for byte the Kotlin one.
pub const WATCH_FILE_DESCRIPTOR: &str = r#"{"name":"infantry_watch_file","description":"Watch a file another process writes (a build sent to a log with > or tee): you are mailed when it goes quiet, prints a watched pattern (BUILD FAILED, e: ...), runs long, or is done (the process untilPid exited, or the file stopped growing for idleSeconds). Only appended bytes are read, never a copy. Start it, then end your turn; no sleep, no polling.","inputSchema":{"type":"object","properties":{"path":{"type":"string","description":"The file to follow."},"untilPid":{"type":"integer","description":"Done when this process exits."},"idleSeconds":{"type":"integer","description":"No pid: done once the file has not grown this long (default limits.watch.fileIdleSeconds)."},"name":{"type":"string","description":"A label for the notices."}},"required":["path"],"additionalProperties":false},"annotations":{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":false,"openWorldHint":false}}"#;

// ---- kotlinx JsonPrimitive accessors -------------------------------------------------------------------------------

/// `(e as? JsonPrimitive)?.contentOrNull`: a string's text, a number or boolean as written; None for null / array / object.
pub fn content_or_null(v: Option<&Value>) -> Option<String> {
    match v? {
        Value::String(s) => Some(s.clone()),
        Value::Number(n) => Some(n.to_string()),
        Value::Bool(b) => Some(b.to_string()),
        _ => None,
    }
}

/// `longOrNull`: the primitive's content read as a whole number (a numeric string counts, as in kotlinx).
pub fn long_or_null(v: Option<&Value>) -> Option<i64> {
    let c = content_or_null(v)?;
    if let Ok(n) = c.parse::<i64>() {
        return Some(n);
    }
    if c.contains(['e', 'E']) && !c.contains('.') {
        let f = c.parse::<f64>().ok()?;
        if f.fract() == 0.0 && f.abs() < 9.2e18 {
            return Some(f as i64);
        }
    }
    None
}

/// `booleanOrNull`: exactly `true` or `false`.
pub fn boolean_or_null(v: Option<&Value>) -> Option<bool> {
    match content_or_null(v)?.as_str() {
        "true" => Some(true),
        "false" => Some(false),
        _ => None,
    }
}

// ---- results --------------------------------------------------------------------------------------------------------

pub struct ToolResult {
    pub text: String,
    pub is_error: bool,
}

impl ToolResult {
    pub fn error(text: impl Into<String>) -> Self {
        ToolResult { text: text.into(), is_error: true }
    }

    /// `ToolResult.toJson()`: one text block, capped at `limits.mcp.maxResultChars`.
    pub fn to_json(&self) -> Value {
        let max = env::MCP_MAX_RESULT_CHARS.from_env(&env::raw).unwrap_or(env::MCP_MAX_RESULT_CHARS.default);
        let mut block = Map::new();
        block.insert("type".into(), Value::String("text".into()));
        block.insert("text".into(), Value::String(capped(&self.text, max)));
        let mut m = Map::new();
        m.insert("content".into(), Value::Array(vec![Value::Object(block)]));
        m.insert("isError".into(), Value::Bool(self.is_error));
        Value::Object(m)
    }
}

/// Java's `String.length` (UTF-16 code units).
pub fn utf16_len(s: &str) -> i64 {
    s.chars().map(|c| c.len_utf16() as i64).sum()
}

/// `String.take(n)` in UTF-16 units. A cut through a surrogate pair leaves Java a lone surrogate, which the UTF-8 / ASCII
/// output then writes as `?`: write that `?` here (also one unit, so a second cap counts the same length).
fn take_utf16(s: &str, n: i64) -> String {
    let mut out = String::new();
    let mut used = 0i64;
    for c in s.chars() {
        let w = c.len_utf16() as i64;
        if used + w > n {
            if used < n {
                out.push('?');
            }
            break;
        }
        out.push(c);
        used += w;
    }
    out
}

/// `ToolResult.capped(max)`.
pub fn capped(text: &str, max: i64) -> String {
    let len = utf16_len(text);
    if max <= 0 || len <= max {
        return text.to_string();
    }
    format!("{}\n...{} chars cut, narrow with limit/columns/ticketId", take_utf16(text, max), len - max)
}

// ---- local files ----------------------------------------------------------------------------------------------------

fn resolve(cwd: &Path, path: &str) -> PathBuf {
    let p = Path::new(path.trim());
    if p.is_absolute() {
        p.to_path_buf()
    } else {
        cwd.join(p)
    }
}

/// `LocalFiles.encode`: a readable file becomes `{path, size, base64}` (no base64 past 10 MiB); anything else stays a string.
fn encode_local_file(cwd: &Path, path: &str) -> Value {
    let file = resolve(cwd, path);
    let Ok(meta) = std::fs::metadata(&file) else { return Value::String(path.into()) };
    if !meta.is_file() {
        return Value::String(path.into());
    }
    let size = meta.len();
    let mut m = Map::new();
    m.insert("path".into(), Value::String(path.into()));
    m.insert("size".into(), Value::Number(size.into()));
    if size <= LOCAL_FILE_MAX_BYTES {
        let bytes = std::fs::read(&file).unwrap_or_default();
        m.insert("base64".into(), Value::String(base64::engine::general_purpose::STANDARD.encode(bytes)));
    }
    Value::Object(m)
}

fn encode_arguments(cwd: &Path, arguments: Map<String, Value>, keys: &[String]) -> Map<String, Value> {
    if keys.is_empty() {
        return arguments;
    }
    arguments
        .into_iter()
        .map(|(k, v)| {
            let v = match v {
                Value::Array(items) if keys.contains(&k) => Value::Array(
                    items
                        .into_iter()
                        .map(|i| match i {
                            Value::String(s) => encode_local_file(cwd, &s),
                            other => other,
                        })
                        .collect(),
                ),
                other => other,
            };
            (k, v)
        })
        .collect()
}

// ---- the tools ------------------------------------------------------------------------------------------------------

/// The narrowing every plugin call carries (`McpNarrowing.toClient`).
#[derive(Default, Clone)]
pub struct Narrowing {
    pub depth: Option<i32>,
    pub max_depth: Option<i32>,
    pub max_spawns: Option<i32>,
    pub client_key: Option<String>,
}

#[derive(Clone)]
pub struct Tools {
    target: Target,
    err: fn(&str),
    getenv: fn(&str) -> Option<String>,
    cwd: PathBuf,
    narrowing: Narrowing,
    default_project: Option<String>,
    timeouts: HashMap<String, i64>,
    local_files: HashMap<String, Vec<String>>,
    verbose_tools: HashSet<String>,
    own_detail_tools: HashSet<String>,
    /// The instruction paragraphs the daemon's tools contribute, in list order, deduplicated.
    pub instructions: Vec<String>,
    plugin_names: Option<HashSet<String>>,
    pub daemon_answered: bool,
}

impl Tools {
    pub fn new(
        target: Target,
        err: fn(&str),
        getenv: fn(&str) -> Option<String>,
        cwd: PathBuf,
        narrowing: Narrowing,
        default_project: Option<String>,
    ) -> Self {
        Tools {
            target,
            err,
            getenv,
            cwd,
            narrowing,
            default_project,
            timeouts: HashMap::new(),
            local_files: HashMap::new(),
            verbose_tools: HashSet::new(),
            own_detail_tools: HashSet::new(),
            instructions: Vec::new(),
            plugin_names: None,
            daemon_answered: true,
        }
    }

    fn connect(&self) -> Result<FrameStream, FrameError> {
        FrameStream::open(&self.target, self.err)
    }

    /// `McpTools.pluginDescriptors`: the daemon's tool list; empty (and `daemon_answered` false) when it cannot be read.
    pub fn plugin_descriptors(&mut self) -> Vec<Value> {
        let list = match self.connect() {
            Ok(mut stream) => {
                let r = self.descriptors(&mut stream);
                stream.close();
                r
            }
            Err(e) => Err(e),
        };
        let list = match list {
            Ok(l) => {
                self.daemon_answered = true;
                l
            }
            Err(_) => {
                self.daemon_answered = false;
                Vec::new()
            }
        };
        self.plugin_names = Some(list.iter().filter_map(|d| content_or_null(d.get("name"))).collect());
        list
    }

    pub fn is_plugin_tool(&mut self, name: &str) -> bool {
        if self.plugin_names.as_ref().is_some_and(|n| n.contains(name)) {
            return true;
        }
        self.plugin_descriptors();
        self.plugin_names.as_ref().is_some_and(|n| n.contains(name))
    }

    /// `McpPluginTools.descriptors`.
    fn descriptors(&mut self, stream: &mut FrameStream) -> Result<Vec<Value>, FrameError> {
        let answer =
            stream.request("plugin_tools", &self.list_request(), "plugin_tools", "plugin_tools", true, self.target.answer_timeout_ms)?;
        let parsed: Value = serde_json::from_str(&answer.data).map_err(|e| FrameError::Failed(e.to_string()))?;
        let Value::Array(all) = parsed else { return Ok(Vec::new()) };
        self.timeouts.clear();
        self.local_files.clear();
        self.instructions.clear();
        let mut out = Vec::new();
        for d in all {
            let Value::Object(mut d) = d else { continue };
            let name = content_or_null(d.get("name"));
            if let Some(name) = &name {
                if let Some(ms) = long_or_null(d.get("timeoutMs")) {
                    self.timeouts.insert(name.clone(), ms);
                }
                if let Some(Value::Array(files)) = d.get(LOCAL_FILES_KEY) {
                    let files: Vec<String> = files.iter().filter_map(|f| content_or_null(Some(f))).collect();
                    if !files.is_empty() {
                        self.local_files.insert(name.clone(), files);
                    }
                }
                let props = d.get("inputSchema").and_then(|s| s.as_object()).and_then(|s| s.get("properties"));
                if let Some(Value::Object(p)) = props {
                    if p.contains_key("verbose") {
                        self.verbose_tools.insert(name.clone());
                    }
                    if p.contains_key("detail") {
                        self.own_detail_tools.insert(name.clone());
                    }
                }
            }
            if let Some(p) = content_or_null(d.get(INSTRUCTIONS_KEY)) {
                if !self.instructions.contains(&p) {
                    self.instructions.push(p);
                }
            }
            for k in ["module", "action", "timeoutMs", LOCAL_FILES_KEY, INSTRUCTIONS_KEY] {
                d.shift_remove(k);
            }
            with_detail(&mut d);
            out.push(Value::Object(d));
        }
        Ok(out)
    }

    /// `McpTools.call`: connects first (an unreachable daemon is a tool error), then runs the tool.
    pub fn call(&mut self, name: &str, arguments: Map<String, Value>) -> ToolResult {
        self.call_with(name, arguments, &|_| {})
    }

    /// [call], handing `opened` a clone of the call's socket once it is open so another thread can cut the call short.
    pub fn call_with(&mut self, name: &str, arguments: Map<String, Value>, opened: &dyn Fn(Option<std::net::TcpStream>)) -> ToolResult {
        let mut stream = match self.connect() {
            Ok(s) => {
                opened(s.abort_handle());
                s
            }
            Err(FrameError::AuthRefused(m)) => return ToolResult::error(m),
            Err(_) => return ToolResult::error(DAEMON_UNREACHABLE),
        };
        let result = if name == WATCH_FILE { Ok(self.watch_file(&arguments)) } else { self.plugin_call(&mut stream, name, arguments) };
        stream.close();
        result.unwrap_or_else(|e| ToolResult::error(format!("{name} failed: {}", e.message())))
    }

    /// `McpPluginTools.call`: the bridge's own `detail` / `maxChars` arguments, then the relay.
    fn plugin_call(&mut self, stream: &mut FrameStream, name: &str, arguments: Map<String, Value>) -> Result<ToolResult, FrameError> {
        let detail = match content_or_null(arguments.get("detail")) {
            Some(d) => Some(d.trim().to_lowercase()),
            None => match env::MCP_DEFAULT_DETAIL.from_env(&env::raw).map_err(FrameError::Failed)? {
                1 => Some("ack".to_string()),
                2 => Some("summary".to_string()),
                3 => Some("full".to_string()),
                _ => None,
            },
        };
        let max_chars = long_or_null(arguments.get("maxChars"));
        let body_chars = env::MCP_BODY_CHARS.from_env(&env::raw).map_err(FrameError::Failed)?;
        let wants_verbose = detail.as_deref() == Some("full") && !arguments.contains_key("verbose") && self.verbose_tools.contains(name);
        let mut bare = arguments;
        if name == "infantry_read" && !bare.contains_key("bodyChars") && body_chars > 0 {
            bare.insert("bodyChars".into(), Value::Number(body_chars.into()));
        }
        if !self.own_detail_tools.contains(name) {
            bare.shift_remove("detail");
        }
        bare.shift_remove("maxChars");
        if wants_verbose {
            bare.insert("verbose".into(), Value::Bool(true));
        }
        let result = self.call_tool(stream, name, bare)?;
        Ok(match max_chars {
            Some(m) => ToolResult { text: capped(&result.text, m), is_error: result.is_error },
            None => result,
        })
    }

    /// AR-8: the `plugin_tools` payload: empty for a client with no session (the owner's), else `{client: {sessionId, role}}`
    /// so the daemon lists only the tools the agent's role enables.
    fn list_request(&self) -> String {
        let c = self.client();
        if !c.contains_key("sessionId") {
            return String::new();
        }
        let mut who = Map::new();
        for k in ["sessionId", "role"] {
            if let Some(v) = c.get(k) {
                who.insert(k.into(), v.clone());
            }
        }
        serde_json::json!({ "client": who }).to_string()
    }

    fn client(&self) -> Map<String, Value> {
        let mut c = Map::new();
        let trimmed = |v: Option<String>| v.map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
        if let Some(s) = trimmed((self.getenv)("INFANTRY_SESSION_ID")) {
            c.insert("sessionId".into(), Value::String(s));
        }
        if let Some(r) = trimmed((self.getenv)("INFANTRY_ROLE")) {
            c.insert("role".into(), Value::String(r));
        }
        if let Some(p) = trimmed(self.default_project.clone()).or_else(|| trimmed((self.getenv)("INFANTRY_PROJECT_ID"))) {
            c.insert("projectId".into(), Value::String(p));
        }
        let n = &self.narrowing;
        if let Some(d) = n.depth {
            c.insert("depth".into(), Value::Number(d.into()));
        }
        if let Some(d) = n.max_depth {
            c.insert("maxDepth".into(), Value::Number(d.into()));
        }
        if let Some(s) = n.max_spawns {
            c.insert("maxSpawns".into(), Value::Number(s.into()));
        }
        if let Some(k) = &n.client_key {
            c.insert("clientKey".into(), Value::String(k.clone()));
        }
        c
    }

    /// `McpPluginTools.callTool`: one `plugin_tool_call`, never re-sent.
    fn call_tool(&mut self, stream: &mut FrameStream, name: &str, arguments: Map<String, Value>) -> Result<ToolResult, FrameError> {
        let client = self.client();
        if !self.timeouts.contains_key(name) {
            let _ = self.descriptors(stream);
        }
        let files = self.local_files.get(name).cloned().unwrap_or_default();
        let sent = encode_arguments(&self.cwd, arguments, &files);
        let mut payload = Map::new();
        payload.insert("name".into(), Value::String(name.into()));
        payload.insert("arguments".into(), Value::Object(sent));
        payload.insert("client".into(), Value::Object(client));
        // kotlinx re-encodes number literals (and refuses a non-finite one) where Kotlin builds this frame.
        let data = crate::kjson::encode(&Value::Object(payload)).map_err(FrameError::Failed)?;
        let t = match self.timeouts.get(name) {
            Some(t) => *t,
            None => env::PLUGIN_TOOL_TIMEOUT_MS.from_env(&env::raw).map_err(FrameError::Failed)?,
        };
        let answer = stream.request("plugin_tool_call", &data, "plugin_tool_result", "plugin_tool_call", false, t + t / 4)?;
        let parsed: Value = serde_json::from_str(&answer.data).map_err(|e| FrameError::Failed(e.to_string()))?;
        let Value::Object(result) = parsed else { return Ok(ToolResult::error("malformed plugin_tool_result")) };
        Ok(ToolResult {
            text: content_or_null(result.get("text")).unwrap_or_default(),
            is_error: boolean_or_null(result.get("isError")).unwrap_or(false),
        })
    }

    /// `McpWatchTools.watchFile`: the arguments mapped to `infantry watch-file`, run by the JVM launcher (the watcher it
    /// starts is the same detached process either way); its stdout + stderr is the answer.
    fn watch_file(&self, arguments: &Map<String, Value>) -> ToolResult {
        let Some(path) = content_or_null(arguments.get("path")).filter(|p| !p.trim().is_empty()) else {
            return ToolResult::error(format!("{WATCH_FILE}: needs path"));
        };
        let mut args = vec!["watch-file".to_string(), java_path(&self.cwd, &path)];
        if let Some(pid) = long_or_null(arguments.get("untilPid")) {
            args.extend(["--until-pid".into(), pid.to_string()]);
        }
        if let Some(s) = long_or_null(arguments.get("idleSeconds")) {
            args.extend(["--idle-seconds".into(), s.to_string()]);
        }
        if let Some(n) = content_or_null(arguments.get("name")).filter(|n| !n.trim().is_empty()) {
            args.extend(["--name".into(), n]);
        }
        match crate::launcher::run_captured(&self.getenv, &args) {
            Ok((code, text)) => {
                let text = text.trim();
                if text.is_empty() {
                    ToolResult { text: format!("{WATCH_FILE} exited {code}"), is_error: code != 0 }
                } else {
                    ToolResult { text: text.to_string(), is_error: code != 0 }
                }
            }
            Err(e) => ToolResult::error(format!("{WATCH_FILE} failed: {e}")),
        }
    }
}

/// `File(path).let { if (it.isAbsolute) it else File(cwd, path) }.path`: java.io.File normalises the separators.
fn java_path(cwd: &Path, path: &str) -> String {
    let joined = if Path::new(path).is_absolute() { PathBuf::from(path) } else { cwd.join(path) };
    let s = joined.to_string_lossy().to_string();
    if cfg!(windows) {
        let s = s.replace('/', "\\");
        let mut out = String::with_capacity(s.len());
        for (i, c) in s.chars().enumerate() {
            if c == '\\' && out.ends_with('\\') && i > 1 {
                continue;
            }
            out.push(c);
        }
        if out.len() > 3 && out.ends_with('\\') {
            out.pop();
        }
        out
    } else {
        let mut out = String::with_capacity(s.len());
        for c in s.chars() {
            if c == '/' && out.ends_with('/') {
                continue;
            }
            out.push(c);
        }
        if out.len() > 1 && out.ends_with('/') {
            out.pop();
        }
        out
    }
}

/// `McpPluginTools.withDetail`: every plugin tool takes the bridge's `detail` and `maxChars` (an existing key keeps its place).
fn with_detail(d: &mut Map<String, Value>) {
    let Some(Value::Object(schema)) = d.get_mut("inputSchema") else { return };
    let Some(Value::Object(props)) = schema.get_mut("properties") else { return };
    let detail = props.get("detail").cloned().unwrap_or_else(|| {
        let mut m = Map::new();
        m.insert("type".into(), Value::String("string".into()));
        m.insert("description".into(), Value::String(DETAIL_DESCRIPTION.into()));
        Value::Object(m)
    });
    let mut max_chars = Map::new();
    max_chars.insert("type".into(), Value::String("integer".into()));
    max_chars.insert("description".into(), Value::String(MAX_CHARS_DESCRIPTION.into()));
    props.insert("detail".into(), detail);
    props.insert("maxChars".into(), Value::Object(max_chars));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn capping_counts_utf16_units() {
        assert_eq!(capped("abcdef", 0), "abcdef");
        assert_eq!(capped("abcdef", 4), "abcd\n...2 chars cut, narrow with limit/columns/ticketId");
        // "a😀b" is 4 UTF-16 units; a cut at 2 splits the pair: Java writes '?'.
        assert_eq!(capped("a\u{1F600}b", 2), "a?\n...2 chars cut, narrow with limit/columns/ticketId");
        assert_eq!(capped("a\u{1F600}b", 3), "a\u{1F600}\n...1 chars cut, narrow with limit/columns/ticketId");
    }

    #[test]
    fn with_detail_keeps_existing_positions() {
        let mut d: Map<String, Value> = serde_json::from_str(
            r#"{"name":"t","inputSchema":{"type":"object","properties":{"maxChars":{"x":1},"a":{}},"required":[]}}"#,
        )
        .unwrap();
        with_detail(&mut d);
        let s = serde_json::to_string(&d).unwrap();
        assert!(s.starts_with(r#"{"name":"t","inputSchema":{"type":"object","properties":{"maxChars":{"type":"integer","#), "{s}");
        assert!(s.contains(r#""a":{},"detail":{"type":"string""#), "{s}");
        assert!(s.ends_with(r#""required":[]}}"#), "{s}");
    }

    #[test]
    fn primitive_accessors_follow_kotlinx() {
        let v: Value = serde_json::from_str(r#"{"a":"12","b":12,"c":1.5,"d":null,"e":"true","f":[1]}"#).unwrap();
        assert_eq!(long_or_null(v.get("a")), Some(12));
        assert_eq!(long_or_null(v.get("b")), Some(12));
        assert_eq!(long_or_null(v.get("c")), None);
        assert_eq!(content_or_null(v.get("d")), None);
        assert_eq!(boolean_or_null(v.get("e")), Some(true));
        assert_eq!(content_or_null(v.get("f")), None);
    }

    #[test]
    fn watch_descriptor_is_valid_json_in_order() {
        let v: Value = serde_json::from_str(WATCH_FILE_DESCRIPTOR).unwrap();
        assert_eq!(serde_json::to_string(&v).unwrap(), WATCH_FILE_DESCRIPTOR);
    }
}
