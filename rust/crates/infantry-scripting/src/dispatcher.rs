// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Runs a script's queued actions through the host, engine-agnostic, TWO-PHASE.
//!
//! A run executes the actions the script queued, in order. A script that wants their results queues the
//! reserved action `again` (Lua `ih.again()`): the dispatcher re-runs it with `input.phase` = 2.. and
//! `input.results` = every executed action's `{name, tool, ok, text, json}` so far. At most `max_phases`
//! runs; a dry run is one phase. The first failing action stops the run (unless it says `_optional = true`).
use crate::engine::{ScriptCaller, ScriptEngine, ScriptLimits, ScriptOutcome};
use crate::host::{ScriptHost, ToolInvoker, ToolResult};
use regex::Regex;
use serde_json::{json, Map, Value};
use std::collections::BTreeMap;
use std::sync::{Arc, OnceLock};
use std::time::Instant;

pub const AGAIN: &str = "again";
pub const HANDLED: &str = "handled";
pub const DELIVER_TEXT: &str = "deliver_text";
pub const OPTIONAL: &str = "_optional";
pub const TARGET: &str = "_target";
pub const CAPABILITY_CALL: &str = "infantry_capability_call";
pub const PROGRESS: &str = "infantry_progress";
pub const SESSION_ID: &str = "sessionId";
pub const HARD_MAX_PHASES: u32 = 10;
const RESULT_SHOWN: usize = 600;

/// Names every project has without configuring (the process bridge and friends).
pub const BUILTIN_ALIASES: &[(&str, &str)] = &[
    ("exec", "infantry_exec"),
    ("cli", "infantry_exec"),
    ("exec_status", "infantry_exec_status"),
    ("progress", "infantry_progress"),
    ("chat_note", "infantry_chat_note"),
    ("chat_image", "infantry_chat_image"),
    ("session_act", "infantry_session_act"),
    ("notify", "infantry_notify"),
    ("spawn", "infantry_spawn"),
    ("prompt_answer", "infantry_prompt_answer"),
];

/// The shipped alias table (short name to tool name), used while the `scripting.aliases` setting is unset. Equal to the default the
/// Infantry_Scripting `module.json` declares. Without it `ih.board{}` / `ih.move{}` fail with "unknown tool or alias" (MOVE-1).
pub const DEFAULT_ALIASES: &[(&str, &str)] = &[
    ("move", "infantry_ticket_move"),
    ("comment", "infantry_ticket_comment"),
    ("message", "infantry_message"),
    ("start", "infantry_ticket_start"),
    ("close_session", "infantry_session_remove"),
    ("kill", "infantry_kill"),
    ("prompt", "infantry_prompt"),
    ("ask", "infantry_ask"),
    ("ticket_get", "infantry_ticket_get"),
    ("ticket_update", "infantry_ticket_update"),
    ("ticket_create", "infantry_ticket_create"),
    ("board", "infantry_board"),
    ("secret_render", "infantry_secret_render"),
];

/// The table a run uses: the stored setting's JSON object when one is set (a stored `{}` means none), else [`DEFAULT_ALIASES`].
pub fn effective_aliases(setting: Option<&str>) -> BTreeMap<String, String> {
    match setting.map(str::trim).filter(|t| !t.is_empty()) {
        None => DEFAULT_ALIASES.iter().map(|(k, v)| (k.to_string(), v.to_string())).collect(),
        Some(t) => serde_json::from_str::<Value>(t)
            .ok()
            .and_then(|v| v.as_object().cloned())
            .map(|o| o.into_iter().filter_map(|(k, v)| v.as_str().map(str::trim).filter(|s| !s.is_empty()).map(|s| (k, s.to_string()))).collect())
            .unwrap_or_default(),
    }
}

fn capability_id() -> &'static Regex {
    static R: OnceLock<Regex> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"^[a-z][a-z0-9_-]*(\.[a-z][a-z0-9_-]*)+$").unwrap())
}

/// One queued action as the dispatcher handled it. `tool` is the registry name after the alias table; `ok` is `None` when nothing ran.
#[derive(Debug, Clone, PartialEq)]
pub struct ActionRecord {
    pub requested: String,
    pub tool: String,
    pub args: Map<String, Value>,
    pub phase: u32,
    pub ok: Option<bool>,
    pub text: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct RunReport {
    /// OK | ERROR | BUDGET_EXCEEDED | TIMEOUT | ALLOC_EXCEEDED | ACTION_FAILED | PHASES_EXHAUSTED
    pub outcome: String,
    pub records: Vec<ActionRecord>,
    pub logs: Vec<String>,
    pub phases: u32,
    pub dry_run: bool,
    pub error: Option<String>,
    /// 1-based index in `records` of the action that failed (or could not be resolved).
    pub failed_at: Option<usize>,
    /// The script queued `handled` (Lua `ih.handled()`).
    pub handled: bool,
    /// The script queued `deliver_text{text=...}`.
    pub deliver_text: Option<String>,
    pub duration_ms: Option<u64>,
}

impl RunReport {
    pub fn ok(&self) -> bool {
        self.error.is_none()
    }

    /// The compact text a caller sees: actions (or plan), each result, the log, the failure.
    pub fn render(&self, name: &str) -> String {
        let mut s = format!(
            "script {name} {}outcome={} phases={} actions={}\n",
            if self.dry_run { "DRY-RUN " } else { "" },
            self.outcome,
            self.phases,
            self.records.len()
        );
        for (i, r) in self.records.iter().enumerate() {
            let shown = if r.requested != r.tool { format!("{} ({})", r.requested, r.tool) } else { r.tool.clone() };
            let status = match r.ok {
                None => "planned",
                Some(true) => "ok",
                Some(false) => "FAILED",
            };
            s.push_str(&format!("{}. {shown} {} -> {status}", i + 1, Value::Object(r.args.clone())));
            if let Some(t) = &r.text {
                s.push_str(": ");
                s.push_str(&t.chars().take(RESULT_SHOWN).collect::<String>().replace('\n', " "));
            }
            s.push('\n');
        }
        if !self.logs.is_empty() {
            s.push_str("log:\n");
            s.push_str(&self.logs.iter().map(|l| format!("  {l}")).collect::<Vec<_>>().join("\n"));
            s.push('\n');
        }
        if let Some(e) = &self.error {
            s.push_str(&format!("error: {e}\n"));
        }
        s.trim_end().to_string()
    }

    fn fail(outcome: &str, records: Vec<ActionRecord>, logs: Vec<String>, phases: u32, dry_run: bool, error: String, failed_at: Option<usize>) -> Self {
        RunReport { outcome: outcome.into(), records, logs, phases, dry_run, error: Some(error), failed_at, ..Default::default() }
    }
}

pub type Aliases = Arc<dyn Fn() -> BTreeMap<String, String> + Send + Sync>;

/// The dispatcher. Cheap to clone.
#[derive(Clone)]
pub struct ScriptDispatcher {
    host: Arc<dyn ScriptHost>,
    aliases: Aliases,
    max_phases: Arc<dyn Fn() -> u32 + Send + Sync>,
}

/// Options of one run.
#[derive(Default, Clone)]
pub struct RunOptions<'a> {
    pub dry_run: bool,
    /// The session a hook acts for (its `to`): `infantry_progress` lands on its card unless the call names a `sessionId`.
    pub progress_to: Option<&'a str>,
}

impl ScriptDispatcher {
    pub fn new(host: Arc<dyn ScriptHost>, aliases: Aliases, max_phases: Arc<dyn Fn() -> u32 + Send + Sync>) -> Self {
        ScriptDispatcher { host, aliases, max_phases }
    }

    pub fn host(&self) -> &Arc<dyn ScriptHost> {
        &self.host
    }

    pub fn aliases(&self) -> BTreeMap<String, String> {
        (self.aliases)()
    }

    /// Runs `source`. `on_planned` is told each phase's resolved actions BEFORE any of them runs (the hook loop guard uses it).
    #[allow(clippy::too_many_arguments)]
    pub fn run(
        &self,
        engine: &dyn ScriptEngine,
        source: &str,
        args: &Map<String, Value>,
        limits: ScriptLimits,
        caller: &ScriptCaller,
        invoker: &ToolInvoker,
        opts: RunOptions<'_>,
        on_planned: &mut dyn FnMut(&[ActionRecord]),
    ) -> RunReport {
        let started = Instant::now();
        let mut r = self.run_untimed(engine, source, args, limits, caller, invoker, opts, on_planned);
        r.duration_ms = Some(started.elapsed().as_millis() as u64);
        r
    }

    #[allow(clippy::too_many_arguments)]
    fn run_untimed(
        &self,
        engine: &dyn ScriptEngine,
        source: &str,
        args: &Map<String, Value>,
        limits: ScriptLimits,
        caller: &ScriptCaller,
        invoker: &ToolInvoker,
        opts: RunOptions<'_>,
        on_planned: &mut dyn FnMut(&[ActionRecord]),
    ) -> RunReport {
        let known: std::collections::HashSet<String> = self.host.catalog().into_iter().map(|t| t.name).collect();
        let table = (self.aliases)();
        let mut records: Vec<ActionRecord> = Vec::new();
        let mut logs: Vec<String> = Vec::new();
        let mut results: Vec<Value> = Vec::new();
        let phases = if opts.dry_run { 1 } else { (self.max_phases)().clamp(1, HARD_MAX_PHASES) };
        let mut phase = 0u32;
        let mut handled = false;
        let mut deliver_text: Option<String> = None;
        while phase < phases {
            phase += 1;
            let mut input = Map::new();
            input.insert("args".into(), Value::Object(args.clone()));
            input.insert("phase".into(), json!(phase));
            input.insert("caller".into(), json!({"id": caller.id, "role": caller.role}));
            if phase > 1 {
                input.insert("results".into(), Value::Array(results.clone()));
            }
            let r = engine.run(source, &Value::Object(input), limits, caller);
            logs.extend(r.logs.iter().map(|l| if phase > 1 { format!("[phase {phase}] {l}") } else { l.clone() }));
            if r.outcome != ScriptOutcome::Ok {
                let e = r.error.clone().unwrap_or_else(|| r.outcome.as_str().to_string());
                return RunReport::fail(r.outcome.as_str(), records, logs, phase, opts.dry_run, e, None);
            }
            // Resolve every name first: an unknown tool fails the run before this phase executes anything.
            let mut again = false;
            let mut planned: Vec<ActionRecord> = Vec::new();
            for a in r.actions {
                match a.name.as_str() {
                    AGAIN => {
                        again = true;
                        continue;
                    }
                    HANDLED => {
                        handled = true;
                        continue;
                    }
                    DELIVER_TEXT => {
                        deliver_text = a.args.get("text").and_then(Value::as_str).filter(|s| !s.is_empty()).map(str::to_string);
                        continue;
                    }
                    _ => {}
                }
                let tool = if known.contains(&a.name) {
                    Some(a.name.clone())
                } else {
                    let mapped = table
                        .get(&a.name)
                        .cloned()
                        .or_else(|| BUILTIN_ALIASES.iter().find(|(k, _)| *k == a.name).map(|(_, v)| v.to_string()))
                        .unwrap_or_else(|| a.name.clone());
                    Some(legacy_tool_name(&mapped)).filter(|t| known.contains(t))
                };
                if tool.is_none() && capability_id().is_match(&a.name) && known.contains(CAPABILITY_CALL) {
                    // A dotted name is a plugin capability: runs infantry_capability_call; `_target` picks the client host.
                    let mut inner = a.args.clone();
                    let optional = inner.remove(OPTIONAL);
                    let target = inner.remove(TARGET);
                    let mut cap = Map::new();
                    cap.insert("capability".into(), Value::String(a.name.clone()));
                    cap.insert("args".into(), Value::Object(inner));
                    if let Some(t) = target {
                        cap.insert("target".into(), Value::String(value_text(&t)));
                    }
                    if let Some(o) = optional {
                        cap.insert(OPTIONAL.into(), o);
                    }
                    planned.push(ActionRecord { requested: a.name.clone(), tool: CAPABILITY_CALL.into(), args: cap, phase, ok: None, text: None });
                    continue;
                }
                let Some(tool) = tool else {
                    let at = records.len() + planned.len() + 1;
                    records.extend(planned);
                    return RunReport::fail(
                        "ERROR",
                        records,
                        logs,
                        phase,
                        opts.dry_run,
                        format!("unknown tool or alias '{}', nor an installed capability (see script_api, infantry_capability_list)", a.name),
                        Some(at),
                    );
                };
                planned.push(ActionRecord { requested: a.name, tool, args: a.args, phase, ok: None, text: None });
            }
            on_planned(&planned);
            if opts.dry_run {
                records.extend(planned);
                return RunReport { outcome: "OK".into(), records, logs, phases: phase, dry_run: true, ..Default::default() };
            }
            for p in planned {
                let optional = p.args.get(OPTIONAL) == Some(&Value::Bool(true));
                let t0 = Instant::now();
                let res = if p.tool == PROGRESS {
                    self.progress(&p, optional, invoker, opts.progress_to)
                } else {
                    let mut a = p.args.clone();
                    if optional {
                        a.remove(OPTIONAL);
                    }
                    self.host.invoke(&p.tool, &a, invoker)
                };
                logs.push(format!("timing {} {}ms", p.tool, t0.elapsed().as_millis()));
                results.push(json!({"name": p.requested, "tool": p.tool, "ok": !res.is_error, "text": res.text, "json": json_of(&res.text)}));
                let tool = p.tool.clone();
                records.push(ActionRecord { ok: Some(!res.is_error), text: Some(res.text.clone()), ..p });
                if res.is_error && !optional {
                    let n = records.len();
                    let e = format!("action {n} ({tool}) failed: {}", res.text.chars().take(300).collect::<String>());
                    return RunReport::fail("ACTION_FAILED", records, logs, phase, false, e, Some(n));
                }
            }
            if !again {
                return RunReport { outcome: "OK".into(), records, logs, phases: phase, dry_run: false, handled, deliver_text, ..Default::default() };
            }
        }
        RunReport::fail("PHASES_EXHAUSTED", records, logs, phase, opts.dry_run, format!("ih.again() ignored: maxPhases ({phases}) reached"), None)
    }

    /// `infantry_progress` for the session the script acts for: `sessionId` arg, else `progress_to`, else the invoker's own. Same project only.
    fn progress(&self, p: &ActionRecord, optional: bool, invoker: &ToolInvoker, progress_to: Option<&str>) -> ToolResult {
        let named = p.args.get(SESSION_ID).and_then(Value::as_str).map(str::trim).filter(|s| !s.is_empty()).map(str::to_string);
        let mut args = p.args.clone();
        args.remove(SESSION_ID);
        if optional {
            args.remove(OPTIONAL);
        }
        let target = named.or_else(|| progress_to.map(str::to_string));
        let Some(target) = target.filter(|t| Some(t) != invoker.session_id.as_ref()) else {
            return self.host.invoke(&p.tool, &args, invoker);
        };
        match self.host.session_project(&target) {
            Some(project) if Some(&project) == invoker.project_id.as_ref() => {
                let mut as2 = invoker.clone();
                as2.session_id = Some(target.clone());
                if !as2.owner {
                    as2.active_session = Some(target);
                }
                self.host.invoke(&p.tool, &args, &as2)
            }
            _ => ToolResult::err(format!("{PROGRESS} refused: session '{target}' is not a session of this project")),
        }
    }
}

fn value_text(v: &Value) -> String {
    match v {
        Value::String(s) => s.clone(),
        other => other.to_string(),
    }
}

/// A tool's answer as JSON when it is a JSON object or array, else null.
pub fn json_of(text: &str) -> Value {
    let t = text.trim();
    if !(t.starts_with('{') || t.starts_with('[')) {
        return Value::Null;
    }
    match serde_json::from_str::<Value>(t) {
        Ok(v @ (Value::Object(_) | Value::Array(_))) => v,
        _ => Value::Null,
    }
}

/// A pre-rename `agenthq_*` tool name is the `infantry_*` tool.
pub fn legacy_tool_name(n: &str) -> String {
    match n.strip_prefix("agenthq_") {
        Some(rest) => format!("infantry_{rest}"),
        None => n.to_string(),
    }
}
