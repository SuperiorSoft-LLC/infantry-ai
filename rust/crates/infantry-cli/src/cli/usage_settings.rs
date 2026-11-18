// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `infantry usage` (Kotlin `UsageCli`: show, set, clear, override, rule, history) and `infantry settings`
//! (`SettingsCli`: get, set, explain, limits). The usage text is the daemon's own renderer (infantry-usage).

use super::args::Parsed;
use super::conn::{Envelope, FrameStream};
use super::{CmdResult, Command, Ctx, Getenv, Needs};
use infantry_protocol::usage::{PacingRule, UsageReport};
use infantry_usage::pacing::text as usage_text;
use serde_json::{Map, Value};

// ---- usage ----

pub const USAGE_USAGE: &str = "usage: infantry usage [show] | set <meter> <percent> [<resetsInMinutes>] | clear <meter> | override <meter> <workers|none> [<hours>] | override <meter> clear | rule <60:3,80:2,1|on|off|default> | history [<meter>] [<window label>] [<90m|24h|7d>]";

/// `usage_get` waits this long (the daemon may fetch live usage first).
const USAGE_GET_TIMEOUT_MS: u64 = 45_000;
/// An override with no duration and no known reset lasts this long.
const DEFAULT_OVERRIDE_MS: i64 = 2 * 3_600_000;

fn is_f64(s: &str) -> bool {
    s.parse::<f64>().is_ok()
}

pub fn usage_usage_error(args: &[String]) -> Option<String> {
    let Some(verb) = args.first().map(|v| v.to_lowercase()) else { return None };
    let ok = match verb.as_str() {
        "show" => args.len() == 1,
        "set" => (3..=4).contains(&args.len()) && is_f64(&args[2]) && (args.len() == 3 || args[3].parse::<i64>().is_ok()),
        "clear" => args.len() == 2,
        "override" => {
            (3..=4).contains(&args.len())
                && (matches!(args[2].to_lowercase().as_str(), "none" | "clear") || args[2].parse::<i32>().is_ok())
                && (args.len() == 3 || (args[2].to_lowercase() != "clear" && is_f64(&args[3])))
        }
        "rule" => args.len() == 2,
        "history" => history_query(&args[1..]).is_some(),
        _ => false,
    };
    (!ok).then(|| USAGE_USAGE.to_string())
}

/// `historyQuery`: the `pace_get` JSON for `[<meter>] [<window label>] [<since>]`; None = malformed.
pub fn history_query(words: &[String]) -> Option<String> {
    let mut o = Map::new();
    let mut names: Vec<&String> = Vec::new();
    for w in words {
        let since = w.len() > 1
            && matches!(w.chars().last(), Some('m' | 'h' | 'd' | 'M' | 'H' | 'D'))
            && {
                let n = &w[..w.len() - 1];
                let mut parts = n.splitn(2, '.');
                let a = parts.next().unwrap_or("");
                let b = parts.next();
                !a.is_empty() && a.chars().all(|c| c.is_ascii_digit()) && b.map_or(true, |b| !b.is_empty() && b.chars().all(|c| c.is_ascii_digit()))
            };
        if since {
            if o.contains_key("sinceHours") {
                return None;
            }
            let n: f64 = w[..w.len() - 1].parse().ok()?;
            let h = match w.chars().last().unwrap().to_ascii_lowercase() {
                'm' => n / 60.0,
                'h' => n,
                _ => n * 24.0,
            };
            o.insert("sinceHours".into(), serde_json::json!(h));
        } else {
            names.push(w);
        }
    }
    if names.len() > 2 {
        return None;
    }
    if let Some(p) = names.first() {
        o.insert("provider".into(), Value::String(p.to_string()));
    }
    if let Some(w) = names.get(1) {
        o.insert("window".into(), Value::String(w.to_string()));
    }
    Some(Value::Object(o).to_string())
}

fn usage_validate(p: &Parsed, _: Getenv) -> Option<String> {
    usage_usage_error(&p.args)
}

fn report(stream: &mut FrameStream) -> Result<UsageReport, String> {
    let a = stream.request_within(&Envelope::new("usage_get", ""), "usage_report", "usage_get", true, USAGE_GET_TIMEOUT_MS)?;
    serde_json::from_str(&a.data).map_err(|e| format!("malformed usage_report: {e}"))
}

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// `toSet`: the `usage_set` body for a write verb (only the fields set, as Kotlin encodes it); None = just show.
/// Err((message, exit)) for a rule that does not parse (exit 2).
fn to_set(stream: &mut FrameStream, args: &[String], now: i64) -> Result<Option<Value>, (String, i32)> {
    let Some(verb) = args.first().map(|v| v.to_lowercase()) else { return Ok(None) };
    let mut m = Map::new();
    match verb.as_str() {
        "set" => {
            m.insert("meter".into(), Value::String(args[1].clone()));
            m.insert("manualPercent".into(), serde_json::json!(args[2].parse::<f64>().unwrap_or(0.0)));
            if let Some(mins) = args.get(3).and_then(|v| v.parse::<i64>().ok()) {
                m.insert("manualResetsAt".into(), serde_json::json!(now + mins * 60_000));
            }
        }
        "clear" => {
            m.insert("meter".into(), Value::String(args[1].clone()));
            m.insert("clearManual".into(), Value::Bool(true));
        }
        "override" => {
            m.insert("meter".into(), Value::String(args[1].clone()));
            let w = args[2].to_lowercase();
            if w == "clear" {
                m.insert("clearOverride".into(), Value::Bool(true));
            } else {
                let until = match args.get(3).and_then(|h| h.parse::<f64>().ok()) {
                    Some(h) => now + (h * 3_600_000.0) as i64,
                    None => {
                        let r = report(stream).map_err(|e| (e, 1))?;
                        let meter = args[1].to_lowercase();
                        r.meters.iter().find(|x| x.meter == meter).and_then(|x| x.resets_at).filter(|t| *t > now).unwrap_or(now + DEFAULT_OVERRIDE_MS)
                    }
                };
                if w != "none" {
                    m.insert("overrideWorkers".into(), serde_json::json!(w.parse::<i32>().unwrap_or(0)));
                }
                m.insert("overrideUntil".into(), serde_json::json!(until));
            }
        }
        "rule" => {
            let current = if matches!(args[1].to_lowercase().as_str(), "on" | "off") { report(stream).map_err(|e| (e, 1))?.rule } else { PacingRule::default() };
            let rule = usage_text::parse_rule_text(&args[1], &current).map_err(|e| (e, 2))?;
            m.insert("rule".into(), serde_json::to_value(rule).unwrap_or(Value::Null));
        }
        _ => return Ok(None),
    }
    Ok(Some(Value::Object(m)))
}

fn history(ctx: &mut Ctx, args: &[String]) -> CmdResult {
    let Some(query) = history_query(&args[1..]) else {
        ctx.err(format!("error: {USAGE_USAGE}"));
        return Ok(2);
    };
    match ctx.stream().request_within(&Envelope::new("pace_get", query), "pace_report", "pace_get", true, USAGE_GET_TIMEOUT_MS) {
        Ok(a) => {
            let text = serde_json::from_str::<Value>(&a.data).ok().and_then(|v| v.get("text").and_then(Value::as_str).map(String::from));
            ctx.out(text.unwrap_or(a.data));
            Ok(0)
        }
        Err(e) => {
            ctx.err(format!("error: {} (is the Infantry_Pace plugin installed and enabled?)", e.message()));
            Ok(1)
        }
    }
}

fn usage_run(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let args = p.args.clone();
    if args.first().map(|v| v.to_lowercase()).as_deref() == Some("history") {
        return history(ctx, &args);
    }
    let set = match to_set(ctx.stream(), &args, now_ms()) {
        Ok(s) => s,
        Err((m, code)) => {
            ctx.err(format!("error: {m}"));
            return Ok(code);
        }
    };
    let r = match set {
        None => report(ctx.stream())?,
        Some(body) => {
            let a = ctx.stream().request(&Envelope::new("usage_set", body.to_string()), "usage_report", "usage_set", true)?;
            serde_json::from_str(&a.data).map_err(|e| format!("malformed usage_report: {e}"))?
        }
    };
    ctx.out(usage_text::render(&r));
    Ok(0)
}

pub static USAGE: Command = Command { name: "usage", needs: Needs::Stream, validate: usage_validate, run: usage_run };

// ---- settings ----

pub const SETTINGS_USAGE: &str = "usage: infantry settings [get [<key>] [--project p] [--role r] [--session s]] | set <key> <value> [--scope app|project|role|session] [--project p] [--role r] [--session s] | explain <key> [--project p] [--role r] [--session s] | limits";

const SETTINGS_FLAGS: [&str; 4] = ["scope", "project", "role", "session"];

/// `split`: positionals and `--flag value` pairs.
pub fn split(args: &[String]) -> (Vec<String>, Vec<(String, String)>) {
    let mut pos = Vec::new();
    let mut flags: Vec<(String, String)> = Vec::new();
    let mut i = 0;
    while i < args.len() {
        let a = &args[i];
        if a.starts_with("--") && a.len() > 2 && i + 1 < args.len() {
            let k = a[2..].to_string();
            flags.retain(|(x, _)| *x != k);
            flags.push((k, args[i + 1].clone()));
            i += 2;
        } else {
            pos.push(a.clone());
            i += 1;
        }
    }
    (pos, flags)
}

/// The words `SettingsCli` sees: the positionals, then the scope flags the global parser took
/// (`--scope`, `--project`, `--role` are global flags in both CLIs; the usage line documents them for settings).
fn settings_words(p: &Parsed) -> Vec<String> {
    let mut w = p.args.clone();
    for f in ["scope", "project", "role"] {
        if let Some(v) = p.val(f) {
            w.push(format!("--{f}"));
            w.push(v.to_string());
        }
    }
    w
}

pub fn settings_usage_error(args: &[String]) -> Option<String> {
    let rest = if args.is_empty() { &[][..] } else { &args[1..] };
    let (pos, flags) = split(rest);
    let ok = match args.first().map(|v| v.to_lowercase()).as_deref() {
        None => true,
        Some("get") => pos.len() <= 1,
        Some("set") => pos.len() == 2,
        Some("explain") => pos.len() == 1,
        Some("limits") => args.len() == 1,
        _ => false,
    } && flags.iter().all(|(k, _)| SETTINGS_FLAGS.contains(&k.as_str()));
    (!ok).then(|| SETTINGS_USAGE.to_string())
}

fn settings_validate(p: &Parsed, _: Getenv) -> Option<String> {
    settings_usage_error(&settings_words(p))
}

/// `parseValue`: JSON when it parses (a bare word stays a string).
pub fn parse_value(raw: &str) -> Value {
    serde_json::from_str::<Value>(raw.trim()).unwrap_or_else(|_| Value::String(raw.to_string()))
}

/// `contractNotice`: only a different MAJOR warns; a different minor/patch is a hint; no `contract=` warns.
pub fn contract_notice(cli: &str, ready: &str) -> Option<(bool, String)> {
    let Some(daemon) = ready.split(' ').find_map(|w| w.strip_prefix("contract=")).map(|c| c.trim_start_matches("infantry/").to_string()) else {
        return Some((true, format!("warning: no contract version in ready frame: {ready}")));
    };
    if daemon == cli {
        return None;
    }
    let major = |v: &str| v.split('.').next().unwrap_or("").to_string();
    let short = |v: &str| v.split('.').take(2).collect::<Vec<_>>().join(".");
    Some(if major(&daemon) != major(cli) {
        (true, format!("warning: incompatible contract: CLI {cli}, daemon {daemon} (ready frame: {ready})"))
    } else {
        (false, format!("hint: CLI {}, daemon {}: rebuild/reinstall both for new features", short(cli), short(&daemon)))
    })
}

pub fn check_contract(ctx: &mut Ctx) {
    if let Some(ready) = ctx.stream().ready_data() {
        if let Some((_, line)) = contract_notice(super::CONTRACT, &ready) {
            ctx.err(line);
        }
    }
}

fn prim(v: Option<&Value>) -> Option<String> {
    v.and_then(|v| match v {
        Value::Null => None,
        Value::String(s) => Some(s.clone()),
        o => Some(o.to_string()),
    })
}

fn shown(v: Option<&Value>) -> String {
    v.map(Value::to_string).unwrap_or_else(|| "null".into())
}

pub fn render(settings: &Map<String, Value>) -> Vec<String> {
    let mut keys: Vec<&String> = settings.keys().collect();
    keys.sort();
    keys.into_iter().map(|k| format!("{k} = {}", if k == "user.portrait" { "(set)".to_string() } else { settings[k].to_string() })).collect()
}

pub fn render_limits(catalog: &Value) -> Vec<String> {
    let mut out = Vec::new();
    for o in catalog["limits"].as_array().cloned().unwrap_or_default() {
        let s = |k: &str| prim(o.get(k)).unwrap_or_else(|| "null".into());
        let zero = prim(o.get("zeroMeans")).map(|z| format!("; 0 = {z}")).unwrap_or_default();
        let range = format!("{}..{}", s("min"), prim(o.get("max")).unwrap_or_else(|| "no ceiling".into()));
        out.push(format!("{} = {} {} (default {}, range {range}{zero})", s("key"), s("value"), s("unit"), s("default")));
        out.push(format!("    {}", s("description")));
        out.push(format!("    change: infantry settings set {} <n>  |  Settings > Limits  |  env {}", s("key"), s("env")));
    }
    out
}

fn settings_run(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    check_contract(ctx);
    let args = settings_words(p);
    let verb = args.first().map(|v| v.to_lowercase());
    let rest = if args.is_empty() { &[][..] } else { &args[1..] };
    match verb.as_deref() {
        None | Some("get") | Some("explain") => {
            let explain = verb.as_deref() == Some("explain");
            let (pos, flags) = split(rest);
            let key = pos.first().map(|k| k.trim().to_string()).unwrap_or_default();
            let scoped = explain || flags.iter().any(|(k, _)| k != "scope");
            let data = if !scoped {
                key.clone()
            } else {
                let mut m = Map::new();
                m.insert("key".into(), Value::String(key.clone()));
                for (k, v) in &flags {
                    if k != "scope" {
                        m.insert(k.clone(), Value::String(v.clone()));
                    }
                }
                if explain {
                    m.insert("explain".into(), Value::String("true".into()));
                }
                Value::Object(m).to_string()
            };
            let a = ctx.stream().request(&Envelope::new("settings_get", data), "settings", "settings_get", true)?;
            let body = serde_json::from_str::<Value>(&a.data).ok().and_then(|v| v.as_object().cloned()).ok_or("malformed settings answer")?;
            if explain {
                ctx.out(prim(body.get("explain")).unwrap_or_else(|| Value::Object(body.clone()).to_string()));
            } else if scoped {
                ctx.out(format!("{key} = {}  (from {})", shown(body.get("value")), prim(body.get("scope")).unwrap_or_else(|| "null".into())));
            } else {
                for l in render(&body) {
                    ctx.out(l);
                }
            }
            Ok(0)
        }
        Some("set") => {
            let (pos, flags) = split(rest);
            let key = pos[0].trim().to_string();
            let flag = |k: &str| flags.iter().find(|(x, _)| x == k).map(|(_, v)| v.clone());
            let scope = flag("scope").unwrap_or_else(|| "app".into());
            let mut m = Map::new();
            m.insert("key".into(), Value::String(key.clone()));
            m.insert("value".into(), parse_value(&pos[1]));
            if scope != "app" {
                m.insert("scope".into(), Value::String(scope.clone()));
            }
            for f in ["project", "role", "session"] {
                if let Some(v) = flag(f) {
                    m.insert(f.into(), Value::String(v));
                }
            }
            let a = ctx.stream().request(&Envelope::new("settings_set", Value::Object(m).to_string()), "settings", "settings_set", true)?;
            let all = serde_json::from_str::<Value>(&a.data).ok().and_then(|v| v.as_object().cloned()).ok_or("malformed settings answer")?;
            if scope == "app" {
                ctx.out(format!("{key} = {}", all.get(&key).map(Value::to_string).unwrap_or_else(|| "(set)".into())));
            } else {
                ctx.out(format!("{key} = {}  (from {}; set at {scope})", shown(all.get("value")), prim(all.get("scope")).unwrap_or_else(|| "null".into())));
            }
            Ok(0)
        }
        Some("limits") => {
            let a = ctx.stream().request(&Envelope::new("limit_catalog", ""), "limit_catalog", "limit_catalog", true)?;
            let v: Value = serde_json::from_str(&a.data).map_err(|e| format!("malformed limit_catalog: {e}"))?;
            for l in render_limits(&v) {
                ctx.out(l);
            }
            Ok(0)
        }
        _ => {
            ctx.err(SETTINGS_USAGE);
            Ok(2)
        }
    }
}

pub static SETTINGS: Command = Command { name: "settings", needs: Needs::OwnerStream, validate: settings_validate, run: settings_run };

#[cfg(test)]
mod tests {
    use super::*;

    fn v(a: &[&str]) -> Vec<String> {
        a.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn usage_verbs_and_history_query() {
        assert_eq!(usage_usage_error(&v(&["set", "claude", "40", "90"])), None);
        assert_eq!(usage_usage_error(&v(&["override", "claude", "clear", "2"])).as_deref(), Some(USAGE_USAGE));
        assert_eq!(history_query(&v(&["claude", "90m"])).as_deref(), Some(r#"{"sinceHours":1.5,"provider":"claude"}"#));
        assert_eq!(history_query(&v(&["1h", "2h"])), None);
    }

    #[test]
    fn settings_render_and_notice() {
        let m: Map<String, Value> = serde_json::from_str(r#"{"b":1,"user.portrait":"xx","a":"s"}"#).unwrap();
        assert_eq!(render(&m), vec!["a = \"s\"", "b = 1", "user.portrait = (set)"]);
        assert_eq!(parse_value("true"), Value::Bool(true));
        assert_eq!(parse_value("abc"), Value::String("abc".into()));
        assert_eq!(contract_notice("1.2.3", "x contract=infantry/1.2.3"), None);
        assert_eq!(contract_notice("1.2.3", "contract=1.5.0").unwrap().1, "hint: CLI 1.2, daemon 1.5: rebuild/reinstall both for new features");
        assert!(contract_notice("1.2.3", "contract=2.0.0").unwrap().0);
    }
}
