// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The Lua engine: Lua 5.2 (mlua `lua52`, vendored), one fresh sandboxed state per run.
//!
//! Globals: base (minus `dofile`, `loadfile`, `load`, `loadstring`, `require`, `collectgarbage`, `module`),
//! `string`, `table`, `math`, `os.time`, `os.clock`, `ih`, `input`. No io, os.*, package, debug, coroutine, bit32
//! (the LuaJ build loaded none of them either). `ih.call` / `ih.<alias>` / `ih.log` only queue.
//!
//! Limits: a VM hook every [`HOOK_EVERY`] instructions enforces the step budget and the wall clock; the
//! allocator caps the live heap at the fresh state's size plus `max_alloc_bytes`. A run also executes on a
//! thread of its own that the caller abandons after `timeout_ms + 500` (a C call the hook cannot interrupt).
//!
//! LuaJ quirks mirrored (each because the bundled scripts or their callers rely on it):
//! - Every number crossing into Lua is a double (LuaJ `valueOf(toDouble)`); `input.phase == 1` holds either way.
//! - Back to JSON, an integral number below 1e15 becomes an integer (`n=2` -> `2`, not `2.0`), else a float.
//! - A string that looks numeric stays a string (`"39e65168"`): ticket ids look like numbers.
//! - A table is a JSON array when its border `#t` > 0 equals its key count; any other table (also `{}`) is an object.
//! - `ih.exec "cmd"` / `ih.cli "cmd"`: a bare string is `{command = "cmd"}`.
//! - Error text is `script:<line>: <message>` (the chunk is named `script`).
//! - `string.rep` refuses a result over a quarter of the allocation cap up front (`ALLOC_EXCEEDED`).
//!
//! Deliberate differences from LuaJ (documented, not mirrored):
//! - The memory cap is the LIVE heap, not cumulative thread allocation (LuaJ's JVM counter also counted garbage).
//! - `print` appends to the run log instead of the daemon's stdout.
//! - `pairs` order follows Lua 5.2's hash order, so a script that serialises a table by `pairs`
//!   (usage-governor's `enc`) can emit the same keys in another order; the JSON value is equal.
use crate::engine::{QueuedAction, ScriptCaller, ScriptEngine, ScriptLimits, ScriptOutcome, ScriptResult};
use mlua::{HookTriggers, Lua, LuaOptions, MultiValue, StdLib, Table, Value as LuaValue, VmState};
use serde_json::{Map, Number, Value};
use std::cell::RefCell;
use std::rc::Rc;
use std::sync::atomic::{AtomicU64, AtomicU8, Ordering};
use std::sync::Arc;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

pub const HOOK_EVERY: u32 = 1000;
pub const MAX_DEPTH: usize = 16;
pub const MAX_ACTIONS: usize = 100;
pub const MAX_LOGS: usize = 200;
pub const MAX_LOG_LEN: usize = 2_000;
const CHUNK: &str = "=script";

const ABORT_NONE: u8 = 0;
const ABORT_BUDGET: u8 = 1;
const ABORT_TIMEOUT: u8 = 2;
const ABORT_ALLOC: u8 = 3;

pub const BINDING: &str = r#"Lua 5.2, sandboxed: no io/require/load; os.time, os.clock, string, table, math only.
ih.call("infantry_ticket_move", {ticket="t1", status="done"})   queue any tool by registry name
ih.move{ticket="t1", status="done"}                            same, by an alias (ih.<alias>{...} or ih.<toolname>{...}); args are ONE table of the tool's named arguments
ih.call("readaloud.speak", {text="hi", _target="all"})         call a plugin capability by its dotted id (infantry_capability_list); _target = default | all | <host id>
ih.notify{title="Build failed", level="error", dedupeKey="build"} post on the core notification bus (level info|success|warning|error|urgent)
ih.exec{command={"prog","arg"}, cwd=".", timeoutSec=60}        run a process (needs scripting.exec.enabled); phase 2 input.results[i].json = {exitCode, durationMs, stdoutTail, stderrTail, timedOut}
ih.cli("prog arg")  or  ih.cli{command=..., ...}                same as ih.exec (a bare string is the command line)
ih.log("text")                                                 add to the run log
ih.again()                                                     after this run's actions execute, re-run the script with the results
Nothing runs inside the script: actions are queued and executed in order after it ends; the first failure stops the run.
input.args   the --args object;  input.phase  1, then 2.. after ih.again();  input.caller  {id, role}
input.results (phase 2+)  list, one per executed action in order: {name, tool, ok, text, json}  (json = the tool's answer when it is JSON)
Example: if not input.results then ih.move{ticket=input.args.ticket, status="done"}; ih.again()
         else ih.comment{ticket=input.args.ticket, text="moved: "..input.results[1].text} end"#;

/// The Lua [`ScriptEngine`]. Stateless: every run builds its own `Lua`.
#[derive(Debug, Default, Clone)]
pub struct LuaEngine;

impl LuaEngine {
    pub fn new() -> Self {
        LuaEngine
    }
}

impl ScriptEngine for LuaEngine {
    fn id(&self) -> &str {
        "lua"
    }
    fn extensions(&self) -> &[&str] {
        &["lua"]
    }
    fn binding(&self) -> &str {
        BINDING
    }

    fn check(&self, source: &str) -> Option<String> {
        let lua = Lua::new_with(StdLib::NONE, LuaOptions::new()).ok()?;
        match lua.load(source).set_name(CHUNK).into_function() {
            Ok(_) => None,
            Err(e) => Some(take(&error_text(&e), 300)),
        }
    }

    fn run(&self, source: &str, input: &Value, limits: ScriptLimits, _caller: &ScriptCaller) -> ScriptResult {
        let (tx, rx) = std::sync::mpsc::channel();
        let src = source.to_string();
        let input = input.clone();
        let spawned = std::thread::Builder::new().name("lua-script".into()).spawn(move || {
            let _ = tx.send(run_here(&src, &input, limits));
        });
        if let Err(e) = spawned {
            return ScriptResult::failed(ScriptOutcome::Error, format!("cannot start the script thread: {e}"));
        }
        let wait = Duration::from_millis(limits.timeout_ms.saturating_add(500));
        match rx.recv_timeout(wait) {
            Ok(r) => r,
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => ScriptResult::failed(ScriptOutcome::Timeout, "timeout"),
            Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => ScriptResult::failed(ScriptOutcome::Error, "script thread died"),
        }
    }
}

fn take(s: &str, n: usize) -> String {
    s.chars().take(n).collect()
}

fn run_here(source: &str, input: &Value, limits: ScriptLimits) -> ScriptResult {
    let actions: Rc<RefCell<Vec<QueuedAction>>> = Rc::new(RefCell::new(Vec::new()));
    let logs: Rc<RefCell<Vec<String>>> = Rc::new(RefCell::new(Vec::new()));
    let abort = Arc::new(AtomicU8::new(ABORT_NONE));
    let started = Instant::now();
    let result = (|| -> mlua::Result<()> {
        let lua = Lua::new_with(StdLib::TABLE | StdLib::STRING | StdLib::MATH, LuaOptions::new())?;
        setup(&lua, input, limits, started, actions.clone(), logs.clone(), abort.clone())?;
        // The cap: the fresh state plus the script's allowance.
        let base = lua.used_memory();
        let cap = usize::try_from(limits.max_alloc_bytes).unwrap_or(usize::MAX);
        let _ = lua.set_memory_limit(base.saturating_add(cap));
        let steps = Arc::new(AtomicU64::new(0));
        let deadline = started.checked_add(Duration::from_millis(limits.timeout_ms));
        let (ab, max_steps) = (abort.clone(), limits.max_steps);
        lua.set_hook(HookTriggers::new().every_nth_instruction(HOOK_EVERY), move |_, _| {
            let n = steps.fetch_add(HOOK_EVERY as u64, Ordering::Relaxed) + HOOK_EVERY as u64;
            if n > max_steps {
                ab.store(ABORT_BUDGET, Ordering::Relaxed);
                return Err(mlua::Error::RuntimeError("ABORT:BUDGET".into()));
            }
            if deadline.is_some_and(|d| Instant::now() > d) {
                ab.store(ABORT_TIMEOUT, Ordering::Relaxed);
                return Err(mlua::Error::RuntimeError("ABORT:TIMEOUT".into()));
            }
            Ok(VmState::Continue)
        });
        let r = lua.load(source).set_name(CHUNK).exec();
        lua.remove_hook();
        r
    })();
    let actions = actions.borrow().clone();
    let logs = logs.borrow().clone();
    let done = |outcome, error: Option<String>| ScriptResult { outcome, actions: actions.clone(), logs: logs.clone(), error };
    // A sticky abort wins even when a pcall swallowed it.
    match abort.load(Ordering::Relaxed) {
        ABORT_BUDGET => return done(ScriptOutcome::BudgetExceeded, Some("step budget exceeded".into())),
        ABORT_TIMEOUT => return done(ScriptOutcome::Timeout, Some("timeout".into())),
        ABORT_ALLOC => return done(ScriptOutcome::AllocExceeded, Some("allocation cap exceeded".into())),
        _ => {}
    }
    match result {
        Ok(()) => done(ScriptOutcome::Ok, None),
        Err(e) if is_memory(&e) => done(ScriptOutcome::AllocExceeded, Some("allocation cap exceeded".into())),
        Err(e) => done(ScriptOutcome::Error, Some(take(&error_text(&e), 500))),
    }
}

fn setup(
    lua: &Lua,
    input: &Value,
    limits: ScriptLimits,
    started: Instant,
    actions: Rc<RefCell<Vec<QueuedAction>>>,
    logs: Rc<RefCell<Vec<String>>>,
    abort: Arc<AtomicU8>,
) -> mlua::Result<()> {
    let g = lua.globals();
    for k in ["dofile", "loadfile", "require", "load", "loadstring", "collectgarbage", "module", "package", "debug", "io", "os", "coroutine", "bit32"] {
        g.raw_set(k, LuaValue::Nil)?;
    }
    let tostring: mlua::Function = g.get("tostring")?;

    // print -> the run log (LuaJ wrote to stdout).
    {
        let logs = logs.clone();
        let ts = tostring.clone();
        g.set(
            "print",
            lua.create_function(move |_, args: MultiValue| {
                let parts: Vec<String> = args.into_iter().map(|v| ts.call::<mlua::String>(v).map(|s| s.to_string_lossy()).unwrap_or_default()).collect();
                push_log(&logs, parts.join("\t"));
                Ok(())
            })?,
        )?;
    }

    // string.rep guard (the string metatable shares this table, so s:rep() is guarded too).
    let string: Table = g.get("string")?;
    let rep: mlua::Function = string.get("rep")?;
    let quarter = limits.max_alloc_bytes / 4;
    {
        let abort = abort.clone();
        string.set(
            "rep",
            lua.create_function(move |lua, args: MultiValue| {
                let v: Vec<LuaValue> = args.iter().cloned().collect();
                let len = v.first().and_then(|s| lua.coerce_string(s.clone()).ok().flatten()).map(|s| s.as_bytes().len()).unwrap_or(0) as f64;
                let n = v.get(1).and_then(|x| lua.coerce_number(x.clone()).ok().flatten()).unwrap_or(0.0);
                if len * n > quarter as f64 {
                    abort.store(ABORT_ALLOC, Ordering::Relaxed);
                    return Err(mlua::Error::RuntimeError("ABORT:ALLOC".into()));
                }
                rep.call::<MultiValue>(args)
            })?,
        )?;
    }

    let os = lua.create_table()?;
    os.set(
        "time",
        lua.create_function(|_, ()| {
            let ms = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0);
            Ok((ms / 1000) as f64)
        })?,
    )?;
    os.set("clock", lua.create_function(move |_, ()| Ok(started.elapsed().as_secs_f64()))?)?;
    g.set("os", os)?;

    let ih = lua.create_table()?;
    {
        let actions = actions.clone();
        ih.set(
            "call",
            lua.create_function(move |lua, (name, args): (LuaValue, LuaValue)| {
                let name = match name {
                    LuaValue::String(s) => s.to_string_lossy(),
                    LuaValue::Integer(_) | LuaValue::Number(_) => lua.coerce_string(name)?.map(|s| s.to_string_lossy()).unwrap_or_default(),
                    _ => return Err(mlua::Error::RuntimeError("ih.call: name must be a string".into())),
                };
                queue(lua, &actions, name, args)
            })?,
        )?;
    }
    {
        let logs = logs.clone();
        let ts = tostring.clone();
        ih.set(
            "log",
            lua.create_function(move |_, msg: LuaValue| {
                let s = ts.call::<mlua::String>(msg).map(|s| s.to_string_lossy()).unwrap_or_default();
                push_log(&logs, s);
                Ok(())
            })?,
        )?;
    }
    // Any other name is an alias or tool name the dispatcher resolves: ih.move{...} == ih.call("move", {...}).
    let meta = lua.create_table()?;
    {
        let actions = actions.clone();
        meta.set(
            "__index",
            lua.create_function(move |lua, (_t, key): (LuaValue, LuaValue)| {
                let LuaValue::String(k) = key else { return Ok(LuaValue::Nil) };
                let name = k.to_string_lossy();
                let actions = actions.clone();
                let f = lua.create_function(move |lua, args: LuaValue| queue(lua, &actions, name.clone(), args))?;
                Ok(LuaValue::Function(f))
            })?,
        )?;
    }
    ih.set_metatable(Some(meta));
    g.set("ih", ih)?;
    g.set("input", to_lua(lua, input, 0)?)?;
    Ok(())
}

fn push_log(logs: &Rc<RefCell<Vec<String>>>, s: String) {
    let mut l = logs.borrow_mut();
    if l.len() < MAX_LOGS {
        l.push(take(&s, MAX_LOG_LEN));
    }
}

fn queue(lua: &Lua, actions: &Rc<RefCell<Vec<QueuedAction>>>, name: String, args: LuaValue) -> mlua::Result<()> {
    if actions.borrow().len() >= MAX_ACTIONS {
        return Err(mlua::Error::RuntimeError("ih.call: too many actions".into()));
    }
    let a = match args {
        LuaValue::Nil => Map::new(),
        LuaValue::String(s) if name == "cli" || name == "exec" => {
            let mut m = Map::new();
            m.insert("command".into(), Value::String(s.to_string_lossy()));
            m
        }
        other => match from_lua(lua, other, 0)? {
            Value::Object(m) => m,
            _ => return Err(mlua::Error::RuntimeError("ih.call: args must be a table of named values".into())),
        },
    };
    actions.borrow_mut().push(QueuedAction { name, args: a });
    Ok(())
}

/// JSON -> Lua: numbers become doubles, null is nil (an object key with null is absent), arrays are 1-based.
pub(crate) fn to_lua(lua: &Lua, v: &Value, depth: usize) -> mlua::Result<LuaValue> {
    if depth > MAX_DEPTH {
        return Ok(LuaValue::Nil);
    }
    Ok(match v {
        Value::Null => LuaValue::Nil,
        Value::Bool(b) => LuaValue::Boolean(*b),
        Value::Number(n) => LuaValue::Number(n.as_f64().unwrap_or(0.0)),
        Value::String(s) => LuaValue::String(lua.create_string(s)?),
        Value::Array(a) => {
            let t = lua.create_table()?;
            for (i, x) in a.iter().enumerate() {
                t.raw_set(i + 1, to_lua(lua, x, depth + 1)?)?;
            }
            LuaValue::Table(t)
        }
        Value::Object(o) => {
            let t = lua.create_table()?;
            for (k, x) in o {
                t.raw_set(k.as_str(), to_lua(lua, x, depth + 1)?)?;
            }
            LuaValue::Table(t)
        }
    })
}

fn number(n: f64) -> Value {
    if n.is_finite() && n == n.round() && n.abs() < 1e15 {
        Value::Number(Number::from(n as i64))
    } else {
        Number::from_f64(n).map(Value::Number).unwrap_or(Value::Null)
    }
}

/// Lua -> JSON, the LuaJ rules (see the module doc).
pub(crate) fn from_lua(lua: &Lua, v: LuaValue, depth: usize) -> mlua::Result<Value> {
    if depth > MAX_DEPTH {
        return Err(mlua::Error::RuntimeError("ih.call: args nested too deeply".into()));
    }
    Ok(match v {
        LuaValue::Nil => Value::Null,
        LuaValue::Boolean(b) => Value::Bool(b),
        LuaValue::String(s) => Value::String(s.to_string_lossy()),
        LuaValue::Integer(i) => number(i as f64),
        LuaValue::Number(n) => number(n),
        LuaValue::Table(t) => {
            let len = t.raw_len();
            let mut count = 0usize;
            for pair in t.clone().pairs::<LuaValue, LuaValue>() {
                pair?;
                count += 1;
            }
            if len > 0 && count == len {
                let mut out = Vec::with_capacity(len);
                for i in 1..=len {
                    out.push(from_lua(lua, t.raw_get::<LuaValue>(i)?, depth + 1)?);
                }
                Value::Array(out)
            } else {
                let mut m = Map::new();
                for pair in t.pairs::<LuaValue, LuaValue>() {
                    let (k, x) = pair?;
                    m.insert(key_string(lua, k), from_lua(lua, x, depth + 1)?);
                }
                Value::Object(m)
            }
        }
        other => return Err(mlua::Error::RuntimeError(format!("ih.call: unsupported value type {}", other.type_name()))),
    })
}

fn key_string(lua: &Lua, k: LuaValue) -> String {
    match k {
        LuaValue::String(s) => s.to_string_lossy(),
        LuaValue::Boolean(b) => b.to_string(),
        LuaValue::Integer(i) => i.to_string(),
        other @ LuaValue::Number(_) => lua.coerce_string(other).ok().flatten().map(|s| s.to_string_lossy()).unwrap_or_default(),
        other => other.type_name().to_string(),
    }
}

fn is_memory(e: &mlua::Error) -> bool {
    match e {
        mlua::Error::MemoryError(_) => true,
        mlua::Error::CallbackError { cause, .. } => is_memory(cause),
        mlua::Error::WithContext { cause, .. } => is_memory(cause),
        mlua::Error::RuntimeError(m) => m.contains("not enough memory") || m.contains("ABORT:ALLOC"),
        _ => false,
    }
}

/// The one-line error text, with `script:<line>:` when Lua knows it.
pub(crate) fn error_text(e: &mlua::Error) -> String {
    match e {
        mlua::Error::RuntimeError(m) => m.clone(),
        mlua::Error::SyntaxError { message, .. } => message.clone(),
        mlua::Error::MemoryError(m) => m.clone(),
        mlua::Error::WithContext { cause, .. } => error_text(cause),
        mlua::Error::CallbackError { traceback, cause } => {
            let c = error_text(cause);
            if c.starts_with("script:") {
                return c;
            }
            // The first frame of the script in the traceback names the line the failing call is on.
            let line = traceback.lines().map(str::trim).find_map(|l| {
                let rest = l.strip_prefix("script:")?;
                let n: String = rest.chars().take_while(|c| c.is_ascii_digit()).collect();
                (!n.is_empty()).then(|| format!("script:{n}:"))
            });
            match line {
                Some(l) => format!("{l} {c}"),
                None => c,
            }
        }
        other => other.to_string(),
    }
}
