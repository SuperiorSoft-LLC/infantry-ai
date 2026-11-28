// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! PARITY-AUDIT-1 #22: `infantry event emit`, `card list|show`, `docs list|show`, `mcp list|status`, `role
//! list|show|notes|describe|delete`, `skill ... --scope <layer>`, `memory index|show|search|delete` and `notify --title`,
//! native on the tools the daemon already serves (`infantry_event_emit`, `cards_*`, `docs_*`, `infantry_mcp`,
//! `infantry_role_*`, `infantry_skill_*`, `infantry_memory`, `infantry_notify`). Every other form of these commands
//! (and `knowledge`, `decision`, `trait`, `tool`, `account`, `login`, `logout`, `whoami`, and the `mcp` forms that edit
//! a harness config) stays on the launcher: see [is_native].

use super::args::Parsed;
use super::cmd_board::check_contract;
use super::plugin_tools::{self, ToolResult};
use super::{CmdResult, Command, Ctx, Getenv, Needs};
use serde_json::{json, Map, Value};

fn obj(v: Value) -> Map<String, Value> {
    match v {
        Value::Object(m) => m,
        _ => Map::new(),
    }
}

fn str_of(v: &Value, k: &str) -> String {
    v.get(k).and_then(Value::as_str).unwrap_or_default().to_string()
}

fn nb(v: Option<&str>) -> Option<String> {
    v.map(str::trim).filter(|s| !s.is_empty()).map(String::from)
}

/// The words this binary answers itself; the rest go to the launcher.
pub fn is_native(name: &str, p: &Parsed) -> bool {
    let a = |i| p.arg(i);
    let n = p.args.len();
    match name {
        "event" | "card" | "docs" => true,
        "mcp" => matches!((a(0), n), (Some("list"), 1) | (Some("status"), 2)),
        "role" => a(0) != Some("traits"),
        "skill" => p.val("scope").is_some(),
        "memory" => matches!(a(0), Some("index" | "show" | "search" | "delete")) && p.val("scope").is_none() && p.val("role").is_none() && p.val("folder").is_none(),
        "notify" => p.val("title").is_some() && ["say", "target", "url", "on-done"].iter().all(|k| p.val(k).is_none()) && !p.flag("off"),
        _ => false,
    }
}

/// A tool answer that is a JSON object, else None.
fn parsed_obj(r: &ToolResult) -> Option<Value> {
    serde_json::from_str::<Value>(&r.text).ok().filter(Value::is_object)
}

fn print_or(ctx: &mut Ctx, r: &ToolResult, render: impl FnOnce(&Value) -> String) -> i32 {
    if r.is_error {
        return plugin_tools::print(ctx, r);
    }
    match parsed_obj(r) {
        Some(v) => ctx.out(render(&v).trim_end().to_string()),
        None => ctx.out(&r.text),
    }
    0
}

// ---- event ----

pub const EVENT_USAGE: &str = "usage: infantry event emit <name> [--payload <json object>|-]";

fn v_event(p: &Parsed, _: Getenv) -> Option<String> {
    (p.args.len() != 2 || p.arg(0) != Some("emit")).then(|| EVENT_USAGE.to_string())
}

/// The `--payload` text as the object the tool takes.
pub fn payload_object(text: Option<&str>) -> Result<Option<Value>, String> {
    match text.map(str::trim).filter(|t| !t.is_empty()) {
        None => Ok(None),
        Some(t) => match serde_json::from_str::<Value>(t) {
            Ok(v @ Value::Object(_)) => Ok(Some(v)),
            Ok(_) => Err("--payload must be a JSON object".into()),
            Err(e) => Err(format!("--payload must be a JSON object ({e})")),
        },
    }
}

fn run_event(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    // `--payload` lands in `body` (the parser's `--body` / `--payload` slot).
    let text = match p.body.as_deref() {
        Some("-") => Some(ctx.io.stdin()),
        v => v.map(String::from),
    };
    let payload = match payload_object(text.as_deref()) {
        Ok(v) => v,
        Err(e) => {
            ctx.err(&e);
            return Ok(1);
        }
    };
    let mut a = Map::new();
    a.insert("name".into(), json!(p.args[1]));
    if let Some(v) = payload {
        a.insert("payload".into(), v);
    }
    if let Some(c) = nb((ctx.getenv)("INFANTRY_EVENT_CAUSE").as_deref()) {
        a.insert("causeId".into(), json!(c));
    }
    let r = plugin_tools::call(ctx, "infantry_event_emit", a)?;
    Ok(plugin_tools::print(ctx, &r))
}

// ---- card / docs ----

pub const CARD_USAGE: &str = "usage: infantry card list | infantry card show <type>";
pub const DOCS_USAGE: &str = "usage: infantry docs list | infantry docs show <page>";

fn list_show_ok(p: &Parsed) -> bool {
    matches!((p.arg(0), p.args.len()), (Some("list"), 1) | (Some("show"), 2))
}

fn v_card(p: &Parsed, _: Getenv) -> Option<String> {
    (!list_show_ok(p)).then(|| CARD_USAGE.to_string())
}

fn v_docs(p: &Parsed, _: Getenv) -> Option<String> {
    (!list_show_ok(p)).then(|| DOCS_USAGE.to_string())
}

fn padded(rows: &[(String, String)]) -> String {
    let w = rows.iter().map(|r| r.0.chars().count()).max().unwrap_or(0);
    rows.iter().map(|(a, b)| format!("{a:<w$}  {b}\n")).collect()
}

/// `CardCli.renderList`.
pub fn render_cards(v: &Value) -> String {
    let cards = v["cards"].as_array().cloned().unwrap_or_default();
    if cards.is_empty() {
        return "no chat cards installed (no loaded plugin declares module.json cards)".into();
    }
    let rows: Vec<_> = cards.iter().map(|c| (str_of(c, "type"), format!("{}  ({})", str_of(c, "summary"), str_of(c, "module")))).collect();
    padded(&rows) + "Write one as a ```card:<type> fenced block; `infantry card show <type>` gives its spec."
}

/// `CardCli.renderShow`.
pub fn render_card(v: &Value) -> String {
    format!("card:{} ({}): {}\n\n{}", str_of(v, "type"), str_of(v, "module"), str_of(v, "summary"), str_of(v, "spec").trim_end())
}

/// `DocsCli.renderList`.
pub fn render_pages(v: &Value) -> String {
    let pages = v["pages"].as_array().cloned().unwrap_or_default();
    if pages.is_empty() {
        return "no docs pages bundled (the docs plugin is not loaded)".into();
    }
    let rows: Vec<_> = pages.iter().map(|p| (str_of(p, "page"), str_of(p, "title"))).collect();
    padded(&rows) + "`infantry docs show <page>` prints one."
}

fn run_card(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let show = p.arg(0) == Some("show");
    let a = if show { obj(json!({"type": p.args[1]})) } else { Map::new() };
    let r = plugin_tools::call(ctx, if show { "cards_show" } else { "cards_list" }, a)?;
    Ok(print_or(ctx, &r, if show { render_card } else { render_cards }))
}

fn run_docs(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let show = p.arg(0) == Some("show");
    let a = if show { obj(json!({"page": p.args[1]})) } else { Map::new() };
    let r = plugin_tools::call(ctx, if show { "docs_show" } else { "docs_list" }, a)?;
    Ok(print_or(ctx, &r, |v| if show { str_of(v, "text") } else { render_pages(v) }))
}

// ---- mcp list / status ----

fn run_mcp(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let mut a = obj(json!({"op": p.args[0]}));
    if let Some(n) = p.arg(1) {
        a.insert("name".into(), json!(n));
    }
    let r = plugin_tools::call(ctx, "infantry_mcp", a)?;
    Ok(plugin_tools::print(ctx, &r))
}

// ---- role ----

pub const ROLE_USAGE: &str = "usage: infantry role list | show <id> | notes <id> --body <text|-> | describe <id> --body <text> | delete <id>";

/// `role` usage error for the native forms.
pub fn role_usage_error(args: &[String], body: bool) -> Option<String> {
    let ok = match (args.first().map(String::as_str), args.len()) {
        (Some("list"), 1) => true,
        (Some("show" | "delete"), 2) => true,
        (Some("notes" | "describe"), 2) => body,
        _ => false,
    };
    (!ok).then(|| ROLE_USAGE.to_string())
}

fn v_role(p: &Parsed, _: Getenv) -> Option<String> {
    role_usage_error(&p.args, p.body.is_some())
}

/// `role list`: one tab-separated line per role (`id name source description`).
pub fn role_lines(roles: &[Value]) -> Vec<String> {
    roles.iter().map(|r| format!("{}\t{}\t{}\t{}", str_of(r, "id"), str_of(r, "name"), str_of(r, "source").to_lowercase(), str_of(r, "description"))).collect()
}

fn run_role(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let sub = p.args[0].as_str();
    if sub == "list" {
        let r = plugin_tools::call(ctx, "infantry_role_list", Map::new())?;
        if r.is_error {
            return Ok(plugin_tools::print(ctx, &r));
        }
        let roles = serde_json::from_str::<Value>(&r.text).ok().and_then(|v| v.as_array().cloned()).unwrap_or_default();
        for l in role_lines(&roles) {
            ctx.out(l);
        }
        return Ok(0);
    }
    let id = p.args[1].trim().to_string();
    match sub {
        "show" => {
            let r = plugin_tools::call(ctx, "infantry_role_get", obj(json!({"id": id})))?;
            Ok(print_or(ctx, &r, |v| {
                let role = if v.get("role").is_some_and(Value::is_object) { &v["role"] } else { v };
                format!(
                    "ID: {}\nNAME: {}\nSOURCE: {}\nDESCRIPTION: {}\n\n{}",
                    str_of(role, "id"),
                    str_of(role, "name"),
                    str_of(role, "source").to_lowercase(),
                    str_of(role, "description"),
                    str_of(role, "persona")
                )
            }))
        }
        "notes" | "describe" => {
            let text = match p.body.as_deref() {
                Some("-") => ctx.io.stdin(),
                b => b.unwrap_or_default().to_string(),
            };
            let (k, v) = if sub == "notes" { ("persona", text) } else { ("description", text.trim().to_string()) };
            let r = plugin_tools::call(ctx, "infantry_role_update", obj(json!({"id": id, k: v})))?;
            if r.is_error {
                return Ok(plugin_tools::print(ctx, &r));
            }
            ctx.out(format!("saved {id} ({sub}); it applies to new sessions, not running ones"));
            Ok(0)
        }
        _ => {
            let agent = [("INFANTRY_SESSION_ID"), ("INFANTRY_ROLE")].iter().any(|k| nb((ctx.getenv)(k).as_deref()).is_some());
            if agent {
                ctx.err("error: role delete needs a non-agent caller: an agent may only change a role's notes and description");
                return Ok(1);
            }
            let r = plugin_tools::call(ctx, "infantry_role_delete", obj(json!({"id": id})))?;
            Ok(print_or(ctx, &r, |v| if v["reset"] == json!(true) { format!("reset {id} to its bundled definition") } else { format!("deleted {id}") }))
        }
    }
}

// ---- skill --scope ----

pub const SKILL_USAGE: &str = "usage: infantry skill list|show <name>|create <name>|update <name>|delete <name> --scope global|project|role [--project <id>] [--role <id>] [--description <d>] [--body <text|-> | --file <path>]";

/// `skill ... --scope` usage error.
pub fn skill_usage_error(args: &[String], scope: Option<&str>) -> Option<String> {
    let scope_ok = scope.is_some_and(|s| matches!(s.trim().to_lowercase().as_str(), "global" | "project" | "role"));
    let ok = scope_ok
        && match args.first().map(String::as_str) {
            Some("list") => args.len() == 1,
            Some("show" | "create" | "update" | "delete") => args.len() >= 2,
            _ => false,
        };
    (!ok).then(|| SKILL_USAGE.to_string())
}

fn v_skill(p: &Parsed, _: Getenv) -> Option<String> {
    skill_usage_error(&p.args, p.val("scope"))
}

/// The layer arguments every skill tool takes.
pub fn skill_layer(scope: &str, project: Option<String>, role: Option<String>) -> Map<String, Value> {
    let scope = scope.trim().to_lowercase();
    let mut a = obj(json!({"scope": scope}));
    if scope != "global" {
        if let Some(p) = project {
            a.insert("projectId".into(), json!(p));
        }
    }
    if scope == "role" {
        if let Some(r) = role {
            a.insert("role".into(), json!(r));
        }
    }
    a
}

fn run_skill(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let g = ctx.getenv;
    let scope = p.val("scope").unwrap_or_default().trim().to_lowercase();
    let layer = skill_layer(&scope, nb(p.val("project")).or_else(|| nb(g("INFANTRY_PROJECT_ID").as_deref())), nb(p.val("role")).or_else(|| nb(g("INFANTRY_ROLE").as_deref())));
    let sub = p.args[0].as_str();
    let name = p.args[1..].join(" ").trim().to_string();
    if sub == "delete" {
        let mut a = layer;
        a.insert("name".into(), json!(name));
        let r = plugin_tools::call(ctx, "infantry_skill_delete", a)?;
        if r.is_error {
            return Ok(plugin_tools::print(ctx, &r));
        }
        ctx.out(format!("deleted {scope} skill {name}"));
        return Ok(0);
    }
    let r = plugin_tools::call(ctx, "infantry_skill_list", layer.clone())?;
    if r.is_error {
        return Ok(plugin_tools::print(ctx, &r));
    }
    let skills = parsed_obj(&r).and_then(|v| v["skills"].as_array().cloned()).unwrap_or_default();
    let existing = skills.iter().find(|s| str_of(s, "name") == name).cloned();
    match sub {
        "list" => {
            if skills.is_empty() {
                ctx.out(format!("No {scope} skills."));
            }
            for s in &skills {
                let d = str_of(s, "description");
                ctx.out(if d.is_empty() { str_of(s, "name") } else { format!("{}: {d}", str_of(s, "name")) });
            }
            Ok(0)
        }
        "show" => {
            let Some(s) = existing else {
                ctx.err(&format!("error: unknown {scope} skill '{name}'"));
                return Ok(1);
            };
            let d = str_of(&s, "description");
            ctx.out(format!("NAME: {}\nSCOPE: {scope}\nDESCRIPTION: {}\n\n{}", str_of(&s, "name"), if d.is_empty() { "-".into() } else { d }, str_of(&s, "body")));
            Ok(0)
        }
        _ => {
            if sub == "create" && existing.is_some() {
                ctx.err(&format!("error: {scope} skill '{name}' exists: use skill update"));
                return Ok(1);
            }
            if sub == "update" && existing.is_none() {
                ctx.err(&format!("error: unknown {scope} skill '{name}': use skill create"));
                return Ok(1);
            }
            let body = match (p.body.as_deref(), p.val("file")) {
                (Some("-"), _) => Some(ctx.io.stdin()),
                (Some(b), _) => Some(b.to_string()),
                (None, Some(f)) => match std::fs::read_to_string(super::tools_cmds::absolute(&ctx.cwd, f)) {
                    Ok(t) => Some(t),
                    Err(e) => {
                        ctx.err(&format!("error: cannot read --file {f}: {e}"));
                        return Ok(1);
                    }
                },
                _ => existing.as_ref().map(|s| str_of(s, "body")),
            };
            let Some(body) = body else {
                ctx.err("error: skill create needs a body: --body <text|-> or --file <path>");
                return Ok(2);
            };
            let desc = p.val("description").map(String::from).or_else(|| p.description.clone()).or_else(|| existing.as_ref().map(|s| str_of(s, "description"))).unwrap_or_default();
            let mut a = layer;
            a.insert("name".into(), json!(name));
            a.insert("body".into(), json!(body));
            a.insert("description".into(), json!(desc));
            let r = plugin_tools::call(ctx, "infantry_skill_save", a)?;
            if r.is_error {
                return Ok(plugin_tools::print(ctx, &r));
            }
            ctx.out(format!("saved {scope} skill {name}"));
            Ok(0)
        }
    }
}

// ---- memory ----

pub const MEMORY_USAGE: &str = "usage: infantry memory index | show <name> | search <text> | delete <name>   (other forms and --scope/--role/--folder use the launcher)";

/// `memory` usage error for the native forms.
pub fn memory_usage_error(args: &[String]) -> Option<String> {
    let ok = match args.first().map(String::as_str) {
        Some("index") => args.len() == 1,
        Some("show" | "delete") => args.len() == 2,
        Some("search") => args.len() >= 2,
        _ => false,
    };
    (!ok).then(|| MEMORY_USAGE.to_string())
}

fn v_memory(p: &Parsed, getenv: Getenv) -> Option<String> {
    memory_usage_error(&p.args).or_else(|| p.project(&getenv).is_none().then(|| "memory requires a project id (--project <id>, or INFANTRY_PROJECT_ID)".to_string()))
}

/// The `infantry_memory` arguments for these words.
pub fn memory_args(args: &[String], project: &str) -> Map<String, Value> {
    let mut a = obj(json!({"action": args[0], "projectId": project}));
    match args[0].as_str() {
        "search" => {
            a.insert("query".into(), json!(args[1..].join(" ").trim()));
        }
        "show" | "delete" => {
            a.insert("name".into(), json!(args[1].trim()));
        }
        _ => {}
    }
    a
}

fn run_memory(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let project = p.project(&ctx.getenv).unwrap_or_default();
    let r = plugin_tools::call(ctx, "infantry_memory", memory_args(&p.args, &project))?;
    Ok(plugin_tools::print(ctx, &r))
}

// ---- notify --title ----

pub const NOTIFY_USAGE: &str = "usage: infantry notify --title <text> [--body <text>] [--level info|milestone|warning|error|urgent] [--sound <name>|none] [--ticket <id>]";

fn v_notify(p: &Parsed, _: Getenv) -> Option<String> {
    (nb(p.val("title")).is_none() || !p.args.is_empty()).then(|| NOTIFY_USAGE.to_string())
}

/// The `infantry_notify` arguments.
pub fn notify_args(p: &Parsed, project: Option<String>) -> Map<String, Value> {
    let mut a = obj(json!({"title": p.val("title").unwrap_or_default().trim()}));
    if let Some(b) = p.body.as_deref() {
        a.insert("body".into(), json!(b));
    }
    for (flag, key) in [("level", "level"), ("sound", "sound"), ("ticket", "ticketId")] {
        if let Some(v) = nb(p.val(flag)) {
            a.insert(key.into(), json!(v));
        }
    }
    if let Some(pr) = project {
        a.insert("projectId".into(), json!(pr));
    }
    a
}

fn run_notify(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let a = notify_args(p, p.project(&ctx.getenv));
    let r = plugin_tools::call(ctx, "infantry_notify", a)?;
    Ok(plugin_tools::print(ctx, &r))
}

fn v_mcp(_: &Parsed, _: Getenv) -> Option<String> {
    None
}

pub static EVENT: Command = Command { name: "event", needs: Needs::Stream, validate: v_event, run: run_event };
pub static CARD: Command = Command { name: "card", needs: Needs::Stream, validate: v_card, run: run_card };
pub static DOCS: Command = Command { name: "docs", needs: Needs::Stream, validate: v_docs, run: run_docs };
pub static MCP: Command = Command { name: "mcp", needs: Needs::Stream, validate: v_mcp, run: run_mcp };
pub static ROLE: Command = Command { name: "role", needs: Needs::Stream, validate: v_role, run: run_role };
pub static SKILL: Command = Command { name: "skill", needs: Needs::Stream, validate: v_skill, run: run_skill };
pub static MEMORY: Command = Command { name: "memory", needs: Needs::Stream, validate: v_memory, run: run_memory };
pub static NOTIFY: Command = Command { name: "notify", needs: Needs::Stream, validate: v_notify, run: run_notify };

#[cfg(test)]
mod tests {
    use super::*;

    fn v(a: &[&str]) -> Vec<String> {
        a.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn payload_must_be_an_object() {
        assert_eq!(payload_object(None).unwrap(), None);
        assert_eq!(payload_object(Some(r#"{"a":1}"#)).unwrap(), Some(json!({"a": 1})));
        assert!(payload_object(Some("[1]")).is_err());
        assert!(payload_object(Some("nope")).is_err());
    }

    #[test]
    fn cards_and_docs_render_like_kotlin() {
        assert!(render_cards(&json!({"cards": []})).starts_with("no chat cards"));
        let l = render_cards(&json!({"cards": [{"type": "news", "module": "M", "summary": "S"}, {"type": "x", "module": "N", "summary": "T"}]}));
        assert!(l.starts_with("news  S  (M)\nx     T  (N)\n"), "{l}");
        assert_eq!(render_card(&json!({"type": "news", "module": "M", "summary": "S", "spec": "how\n"})), "card:news (M): S\n\nhow");
        assert!(render_pages(&json!({"pages": [{"page": "usage", "title": "Usage"}]})).starts_with("usage  Usage\n"));
    }

    #[test]
    fn role_and_skill_usage() {
        assert!(role_usage_error(&v(&["list"]), false).is_none());
        assert!(role_usage_error(&v(&["notes", "r"]), false).is_some(), "notes needs --body");
        assert!(role_usage_error(&v(&["notes", "r"]), true).is_none());
        assert!(role_usage_error(&v(&["create", "r"]), true).is_some());
        assert_eq!(role_lines(&[json!({"id": "a", "name": "A", "source": "BUNDLED", "description": "d"})]), vec!["a\tA\tbundled\td"]);
        assert!(skill_usage_error(&v(&["list"]), Some("global")).is_none());
        assert!(skill_usage_error(&v(&["list"]), Some("planet")).is_some());
        assert!(skill_usage_error(&v(&["create"]), Some("role")).is_some());
        assert_eq!(skill_layer("global", Some("p".into()), Some("r".into())), obj(json!({"scope": "global"})));
        assert_eq!(skill_layer("Role", Some("p".into()), Some("r".into())), obj(json!({"scope": "role", "projectId": "p", "role": "r"})));
    }

    #[test]
    fn memory_words_map_to_the_tool() {
        assert!(memory_usage_error(&v(&["index"])).is_none());
        assert!(memory_usage_error(&v(&["show"])).is_some());
        assert_eq!(memory_args(&v(&["search", "a", "b"]), "p"), obj(json!({"action": "search", "projectId": "p", "query": "a b"})));
        assert_eq!(memory_args(&v(&["delete", "n"]), "p")["name"], "n");
    }
}
