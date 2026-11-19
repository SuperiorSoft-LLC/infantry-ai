// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `infantry runner pair|up|bearer|connect|add|list|check|test|rotate|detach|audit` (RUNNER-POOL-5, RunnerCli.kt): the same
//! words, flags, printed text and exit codes. `pair`, `up`, `bearer`, `connect`, `add`, `check`, `test`, `rotate` and
//! `detach <name>` go to the daemon as the owner-only `runner_admin` frame (the owner token from `<stateDir>/owner.token`);
//! `list`, `audit` and the runner-side `detach` read and write `<stateDir>/runner-link` here. `runner up` starts the Rust
//! daemon in the background when none answers (RunnerUpLauncher.kt).

use super::args::Parsed;
use super::conn::{ConnError, Endpoint, Envelope, FrameStream};
use super::{CmdResult, Command, Ctx, Getenv, Needs};
use serde_json::{Map, Value};
use std::collections::HashMap;
use std::io::{IsTerminal, Write};
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

pub const USAGE: &str = "usage: infantry runner bearer [--name <n>] [--host <h>] [--port <p>] [--allow-projects <ids|*>] [--allow-harnesses <ids|*>] [--rotate] | add <endpoint> --bearer <token|-> | connect --name <n> --via \"<command>\" [--host <h>] [--port <p>] | up --name <n> [--host <h>]... [--allow-projects <ids|*>] [--allow-harnesses <ids|*>] | pair --name <n> [--host <h>]... [--allow-projects <ids|*>] [--allow-harnesses <ids|*>] [--grant-on-pair [--grant-harnesses-max <ids>]] | add <name> <wss-url> --code <code> [--fingerprint <fp>] | add '<infantry://runner?...>' [--name <n>] [--grant [--project <id>]] | add --link <link|-> [--name <n>] [--grant [--project <id>]] | list | check <name> [--project <id>] [--harness <id>] | test <name> [--harness <id>] [--model <id>] | rotate <name> | detach [<name>] | audit [--limit N]";
const QUOTE_HINT: &str = "Quote the whole link, e.g. infantry runner add 'infantry://runner?v=1&...'. A shell without quotes cuts it at &.";
const LINK_PREFIX: &str = "infantry://runner?";
const ADMIN: &str = "runner_admin";
const OWNER_LOCAL: &str = "owner (local console)";
const MS_PER_SECOND: u64 = 1000;
const ADMIN_CALLS: u64 = 2;
const ADMIN_SLACK_SECONDS: u64 = 10;
const PAIR_POLL_STEP_MS: u64 = 500;
const ID_COLUMN: usize = 15;
const LINK_DIR: &str = "runner-link";
const AUDIT_FILE: &str = "audit.jsonl";
const SELF_KEY: &str = "runner-link.self";
const ORCHESTRATOR_KEY: &str = "runner-link.orchestrator";
const PEER_PREFIX: &str = "runner-link.peer.";
const PROJECTS_SETTING: &str = "runner.projects";
const HARNESSES_SETTING: &str = "runner.harnesses";
const ALLOW_PLAIN_TAILNET_TEXT: &str = "Serve the runner link as plain ws:// instead of wss://. RISK: the pair credential and every start/stop, brief and mail text cross the network unencrypted; anyone on the path can read them and replay the credential to start agents with tool permissions on this machine. Only for a private tailnet (Tailscale/WireGuard) you fully control. Default false.";

/// The `limits.runners.*` values this command reads, with their registry defaults.
struct LimitDef {
    key: &'static str,
    default: u64,
}
const CREDENTIAL_ROTATE_DAYS: LimitDef = LimitDef { key: "limits.runners.credentialRotateDays", default: 30 };
const UP_START_SEC: LimitDef = LimitDef { key: "limits.runners.upStartSec", default: 60 };
const AUDIT_SHOWN: LimitDef = LimitDef { key: "limits.runners.auditShown", default: 50 };
const LINK_CALL_SECONDS: LimitDef = LimitDef { key: "limits.runners.linkCallSeconds", default: 20 };
const CHECK_GIT_SEC: LimitDef = LimitDef { key: "limits.runners.checkGitSec", default: 15 };
const CONNECT_SEC: LimitDef = LimitDef { key: "limits.runners.connectSec", default: 60 };
const ADD_HOST_TIMEOUT_SEC: LimitDef = LimitDef { key: "limits.runners.addHostTimeoutSec", default: 5 };
const TEST_TURN_SEC: LimitDef = LimitDef { key: "limits.runners.testTurnSec", default: 90 };

pub static RUNNER: Command = Command { name: "runner", needs: Needs::Target, validate, run };

/// The words after `runner` and the flags the parser took for it (`RunnerCli.Args`).
struct Args {
    words: Vec<String>,
    name: Option<String>,
    code: Option<String>,
    fingerprint: Option<String>,
    limit: Option<String>,
    allow_projects: Option<String>,
    allow_harnesses: Option<String>,
    hosts: Option<String>,
    link: Option<String>,
    project: Option<String>,
    harness: Option<String>,
    model: Option<String>,
    grant_on_pair: bool,
    grant_harnesses_max: Option<String>,
    grant: bool,
    port: Option<String>,
    via: Option<String>,
    bearer: Option<String>,
    rotate: bool,
}

impl Args {
    fn of(p: &Parsed, project: Option<String>) -> Args {
        let v = |k: &str| p.val(k).map(str::to_string);
        Args {
            words: p.args.clone(),
            name: v("name"),
            code: v("code"),
            fingerprint: v("fingerprint"),
            limit: v("limit"),
            allow_projects: v("allow-projects"),
            allow_harnesses: v("allow-harnesses"),
            hosts: p.runner_hosts.clone(),
            link: v("link"),
            project,
            harness: v("harness"),
            model: v("model"),
            grant_on_pair: p.flag("grant-on-pair"),
            grant_harnesses_max: v("grant-harnesses-max"),
            grant: p.flag("grant"),
            port: p.runner_port.clone(),
            via: v("via"),
            bearer: p.bearer.clone(),
            rotate: p.flag("rotate"),
        }
    }

    fn action(&self) -> Option<String> {
        self.words.first().map(|w| w.to_lowercase())
    }

    fn positional(&self) -> &[String] {
        if self.words.is_empty() {
            &[]
        } else {
            &self.words[1..]
        }
    }

    /// The flag names given (the Kotlin `flags` map's keys).
    fn flags(&self) -> Vec<&'static str> {
        let mut f = Vec::new();
        let mut opt = |k: &'static str, v: &Option<String>| {
            if v.is_some() {
                f.push(k)
            }
        };
        opt("name", &self.name);
        opt("code", &self.code);
        opt("fingerprint", &self.fingerprint);
        opt("limit", &self.limit);
        opt("allow-projects", &self.allow_projects);
        opt("allow-harnesses", &self.allow_harnesses);
        opt("host", &self.hosts);
        opt("port", &self.port);
        opt("link", &self.link);
        opt("project", &self.project);
        opt("harness", &self.harness);
        opt("model", &self.model);
        opt("grant-harnesses-max", &self.grant_harnesses_max);
        opt("via", &self.via);
        opt("bearer", &self.bearer);
        if self.grant_on_pair {
            f.push("grant-on-pair");
        }
        if self.grant {
            f.push("grant");
        }
        if self.rotate {
            f.push("rotate");
        }
        f
    }

    fn flags_within(&self, allowed: &[&str]) -> bool {
        self.flags().iter().all(|k| allowed.contains(k))
    }
}

fn blank(v: &Option<String>) -> bool {
    v.as_deref().map_or(true, |s| s.trim().is_empty())
}

fn port_ok(p: &Option<String>) -> bool {
    p.as_deref().map_or(true, |p| p.parse::<i32>().is_ok_and(|n| (1..=65535).contains(&n)))
}

fn is_link(text: &str) -> bool {
    text.trim().to_lowercase().starts_with(LINK_PREFIX)
}

fn add_usage_ok(a: &Args) -> bool {
    let pos = a.positional();
    if let Some(link) = &a.link {
        return pos.is_empty() && a.code.is_none() && a.fingerprint.is_none() && a.flags_within(&["link", "name", "grant", "project"]) && !link.trim().is_empty();
    }
    pos.len() == 1 && is_link(&pos[0]) && a.flags_within(&["name", "grant", "project"])
}

/// `RunnerCli.usageError`.
fn usage_error(a: &Args) -> Option<String> {
    let pos = a.positional();
    let ok = match a.action().as_deref() {
        Some("pair") => {
            pos.is_empty()
                && !blank(&a.name)
                && a.flags_within(&["name", "allow-projects", "allow-harnesses", "host", "port", "grant-on-pair", "grant-harnesses-max"])
                && !(a.grant_on_pair && a.allow_projects.is_some())
                && port_ok(&a.port)
        }
        Some("connect") => pos.is_empty() && !blank(&a.name) && !blank(&a.via) && a.flags_within(&["name", "via", "host", "port"]) && port_ok(&a.port),
        Some("up") => pos.is_empty() && !blank(&a.name) && a.flags_within(&["name", "allow-projects", "allow-harnesses", "host", "port"]) && port_ok(&a.port),
        Some("bearer") => pos.is_empty() && a.flags_within(&["name", "host", "port", "allow-projects", "allow-harnesses", "rotate"]) && port_ok(&a.port),
        Some("add") => {
            (pos.len() == 1 && !is_link(&pos[0]) && !blank(&a.bearer) && a.flags_within(&["bearer", "name"]))
                || add_usage_ok(a)
                || (pos.len() == 2 && !blank(&a.code) && a.flags_within(&["code", "fingerprint"]))
        }
        Some("list") => pos.is_empty() && a.flags().is_empty(),
        Some("rotate") => pos.len() == 1 && a.flags().is_empty(),
        Some("detach") => pos.len() <= 1 && a.flags().is_empty(),
        Some("check") => pos.len() == 1 && !pos[0].trim().is_empty() && a.flags_within(&["project", "harness"]),
        Some("test") => pos.len() == 1 && !pos[0].trim().is_empty() && a.flags_within(&["harness", "model"]),
        Some("audit") => pos.is_empty() && a.flags_within(&["limit"]) && a.limit.as_deref().map_or(true, |l| l.parse::<i32>().unwrap_or(0) > 0),
        _ => false,
    };
    if ok {
        None
    } else if pos.iter().any(|p| is_link(p)) {
        Some(format!("{USAGE}\n{QUOTE_HINT}"))
    } else {
        Some(USAGE.to_string())
    }
}

fn validate(p: &Parsed, _: Getenv) -> Option<String> {
    usage_error(&Args::of(p, p.val("project").map(str::to_string)))
}

/// One `runner_admin` answer object.
struct Answer(Map<String, Value>);

impl Answer {
    fn failed(&self) -> bool {
        self.get("ok").as_deref() != Some("true")
    }
    fn error(&self) -> String {
        self.get("error").unwrap_or_else(|| "the daemon gave no reason".into())
    }
    /// `RunnerLinkService.field`: a primitive's content, None for null / absent / a structure.
    fn get(&self, key: &str) -> Option<String> {
        primitive(self.0.get(key)?)
    }
    fn list(&self, key: &str) -> Vec<String> {
        match self.0.get(key) {
            Some(Value::Array(a)) => a.iter().filter_map(primitive).collect(),
            _ => Vec::new(),
        }
    }
    /// The check rows (`id`, `ok`, `detail`, `fix`), in the runner's order; a row that does not decode is skipped.
    fn checks(&self) -> Vec<CheckRow> {
        match self.0.get("rows") {
            Some(Value::Array(a)) => a
                .iter()
                .filter_map(|r| {
                    Some(CheckRow {
                        id: r.get("id")?.as_str()?.to_string(),
                        ok: r.get("ok")?.as_bool()?,
                        detail: r.get("detail")?.as_str()?.to_string(),
                        fix: r.get("fix").and_then(|f| f.as_str()).map(str::to_string),
                    })
                })
                .collect(),
            _ => Vec::new(),
        }
    }
}

struct CheckRow {
    id: String,
    ok: bool,
    detail: String,
    fix: Option<String>,
}

fn primitive(v: &Value) -> Option<String> {
    match v {
        Value::String(s) => Some(s.clone()),
        Value::Bool(b) => Some(b.to_string()),
        Value::Number(n) => Some(n.to_string()),
        _ => None,
    }
}

/// Kotlin `List.toString()`: `[a, b]`.
fn kotlin_list(l: &[String]) -> String {
    format!("[{}]", l.join(", "))
}

/// The run-time state of one `runner` command.
struct Runner<'s> {
    stream: &'s mut FrameStream,
    limits: HashMap<String, u64>,
}

impl Runner<'_> {
    fn limit(&self, d: &LimitDef) -> u64 {
        self.limits.get(d.key).copied().unwrap_or(d.default)
    }

    /// Sends one `runner_admin` request (fields with a None value are left out).
    fn admin(&mut self, fields: &[(&str, Option<String>)], link_hosts: u64, extra_seconds: u64) -> Result<Answer, String> {
        let mut body = Map::new();
        for (k, v) in fields {
            if let Some(v) = v {
                body.insert((*k).to_string(), Value::String(v.clone()));
            }
        }
        let secs = self.limit(&LINK_CALL_SECONDS) * ADMIN_CALLS + ADMIN_SLACK_SECONDS + extra_seconds + link_hosts * self.limit(&ADD_HOST_TIMEOUT_SEC);
        let wait = self.stream.answer_timeout_ms().max(secs * MS_PER_SECOND);
        let env = Envelope::new(ADMIN, Value::Object(body).to_string());
        let answer = self.stream.request_within(&env, ADMIN, ADMIN, false, wait).map_err(|e| e.message())?;
        match serde_json::from_str::<Value>(&answer.data) {
            Ok(Value::Object(o)) => Ok(Answer(o)),
            _ => Err(format!("the daemon's {ADMIN} answer is not a JSON object")),
        }
    }

    fn op(&mut self, fields: &[(&str, Option<String>)]) -> Result<Answer, String> {
        self.admin(fields, 0, 0)
    }
}

fn s(v: &str) -> Option<String> {
    Some(v.to_string())
}

/// `limit_catalog`: key -> value; empty when it cannot be read (the registry defaults then).
fn limits(stream: &mut FrameStream) -> HashMap<String, u64> {
    let Ok(answer) = stream.request(&Envelope::new("limit_catalog", ""), "limit_catalog", "limit_catalog", true) else { return HashMap::new() };
    let Ok(v) = serde_json::from_str::<Value>(&answer.data) else { return HashMap::new() };
    v.get("limits")
        .and_then(|l| l.as_array())
        .map(|a| {
            a.iter()
                .filter_map(|e| Some((e.get("key")?.as_str()?.to_string(), primitive(e.get("value")?)?.parse::<u64>().ok()?)))
                .collect()
        })
        .unwrap_or_default()
}

/// `InetAddress.getLocalHost().hostName`, else "orchestrator".
fn host_name(getenv: Getenv) -> String {
    getenv("COMPUTERNAME").or_else(|| getenv("HOSTNAME")).map(|h| h.trim().to_string()).filter(|h| !h.is_empty()).unwrap_or_else(|| "orchestrator".into())
}

/// `RemoteAccess.tailscaleAddress`: this machine's tailnet (100.64.0.0/10) IPv4 address. The source address the OS picks
/// toward Tailscale's resolver is the tailnet one when Tailscale is up (a UDP connect sends nothing).
fn tailscale_address() -> Option<String> {
    let sock = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    sock.connect("100.100.100.100:53").ok()?;
    let ip = sock.local_addr().ok()?.ip().to_string();
    is_tailscale_address(&ip).then_some(ip)
}

fn is_tailscale_address(a: &str) -> bool {
    let parts: Vec<i32> = a.split('.').filter_map(|p| p.parse().ok()).collect();
    parts.len() == 4 && parts[0] == 100 && (64..=127).contains(&parts[1])
}

/// `DisplayZone.stamp`: `2026-11-03 20:41 -05:00`, the local zone.
fn stamp(epoch_ms: i64) -> String {
    use chrono::TimeZone;
    match chrono::Local.timestamp_millis_opt(epoch_ms).single() {
        Some(t) => t.format("%Y-%m-%d %H:%M %:z").to_string(),
        None => epoch_ms.to_string(),
    }
}

/// A test turn's duration: `412 ms` under a second, `3.2 s` above it.
fn took_text(ms: i64) -> String {
    if ms < MS_PER_SECOND as i64 {
        format!("{ms} ms")
    } else {
        format!("{:.1} s", ms as f64 / MS_PER_SECOND as f64)
    }
}

/// `RunnerLinkStore.NAME`: 1-32 of a-z, 0-9 and '-', starting with a letter or digit.
fn runner_name_ok(n: &str) -> bool {
    let b = n.as_bytes();
    !b.is_empty()
        && b.len() <= 32
        && (b[0].is_ascii_lowercase() || b[0].is_ascii_digit())
        && b.iter().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'-')
}

/// A console prompt (`System.console().readLine(prompt)`): None without a console.
fn console_read_line(prompt: &str) -> Option<String> {
    if !(std::io::stdin().is_terminal() && std::io::stdout().is_terminal()) {
        return None;
    }
    let mut o = std::io::stdout().lock();
    let _ = o.write_all(prompt.as_bytes());
    let _ = o.flush();
    drop(o);
    let mut line = String::new();
    std::io::stdin().read_line(&mut line).ok()?;
    Some(line.trim_end_matches(['\r', '\n']).to_string())
}

fn has_console() -> bool {
    std::io::stdin().is_terminal() && std::io::stdout().is_terminal()
}

// ---- the pairing link (RunnerPairLink.parse) ----

#[derive(Debug, PartialEq)]
struct PairLink {
    name: String,
    hosts: Vec<String>,
    grant: bool,
}

fn decode_pct(s: &str) -> Option<String> {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' {
            let hi = (*b.get(i + 1)? as char).to_digit(16)?;
            let lo = (*b.get(i + 2)? as char).to_digit(16)?;
            out.push((hi * 16 + lo) as u8);
            i += 3;
        } else {
            out.push(b[i]);
            i += 1;
        }
    }
    Some(String::from_utf8_lossy(&out).to_string())
}

fn host_ok(h: &str) -> bool {
    let hex = |c: char| c.is_ascii_hexdigit();
    if let Some(inner) = h.strip_prefix('[').and_then(|r| r.strip_suffix(']')) {
        return (2..=45).contains(&inner.len()) && inner.chars().all(|c| hex(c) || c == ':' || c == '.');
    }
    if let Some(at) = h.find(':') {
        let (a, b) = (&h[..at], &h[at + 1..]);
        if a.chars().all(|c| hex(c) || c == '.') && (1..=45).contains(&b.len()) && b.chars().all(|c| hex(c) || c == ':' || c == '.') {
            return true;
        }
        return false;
    }
    let b = h.as_bytes();
    let alnum = |c: &u8| c.is_ascii_alphanumeric();
    match b.len() {
        0 => false,
        1 => alnum(&b[0]),
        n => n <= 253 && alnum(&b[0]) && alnum(&b[n - 1]) && b[1..n - 1].iter().all(|c| alnum(c) || *c == b'.' || *c == b'-'),
    }
}

fn bracketed(h: &str) -> String {
    if h.contains(':') && !h.starts_with('[') {
        format!("[{h}]")
    } else {
        h.to_string()
    }
}

fn code_ok(c: &str) -> bool {
    let parts: Vec<&str> = c.split('-').collect();
    parts.len() == 4 && parts.iter().all(|p| p.len() == 4 && p.chars().all(|c| c.is_ascii_alphanumeric()))
}

fn parse_link(text: &str) -> Result<PairLink, &'static str> {
    let t = text.trim();
    let t = t.strip_prefix('\'').and_then(|r| r.strip_suffix('\'')).unwrap_or(t);
    let t = t.strip_prefix('"').and_then(|r| r.strip_suffix('"')).unwrap_or(t).trim();
    if !t.to_lowercase().starts_with(LINK_PREFIX) {
        return Err("Not an Infantry AI runner pairing link (it starts with infantry://runner?).");
    }
    let mut single: HashMap<String, String> = HashMap::new();
    let mut hosts = Vec::new();
    let query = t[LINK_PREFIX.len()..].split('#').next().unwrap_or("");
    for part in query.split('&') {
        if part.is_empty() {
            continue;
        }
        let key = part.split('=').next().unwrap_or("").to_lowercase();
        let raw = part.split_once('=').map_or("", |(_, v)| v);
        let value = decode_pct(raw).ok_or("The runner pairing link is malformed.")?.trim().to_string();
        if key == "host" {
            if !value.is_empty() {
                hosts.push(value);
            }
        } else {
            single.entry(key).or_insert(value);
        }
    }
    let v = single.get("v").ok_or("The runner pairing link has no version (v).")?;
    let version: i32 = v.parse().map_err(|_| "The runner pairing link has a bad version (v).")?;
    if version > 1 {
        return Err("This pairing link was made by a newer runner; update this app, then paste it again.");
    }
    if version < 1 {
        return Err("The runner pairing link has an unknown version (v).");
    }
    let name = single.get("name").cloned().unwrap_or_default();
    if name.is_empty() {
        return Err("The runner pairing link has no runner name.");
    }
    if !runner_name_ok(&name) {
        return Err("The runner name in the link must be 1-32 of a-z, 0-9 and '-'.");
    }
    if hosts.is_empty() {
        return Err("The runner pairing link has no host.");
    }
    if hosts.iter().any(|h| !host_ok(h)) {
        return Err("A host in the runner pairing link is not a host name or address.");
    }
    let port = single.get("port").cloned().unwrap_or_default();
    if port.is_empty() {
        return Err("The runner pairing link has no port.");
    }
    if !port.parse::<i32>().is_ok_and(|p| (1..=65535).contains(&p)) {
        return Err("The port in the runner pairing link is not a port number.");
    }
    let code = single.get("code").cloned().unwrap_or_default();
    if code.is_empty() {
        return Err("The runner pairing link has no pairing code.");
    }
    if !code_ok(&code) {
        return Err("The pairing code in the link is malformed.");
    }
    let plain = single.get("plain").map(String::as_str) == Some("1");
    let fp = single.get("fp").map(|f| f.chars().filter(|c| c.is_alphanumeric()).collect::<String>().to_lowercase()).filter(|f| !f.is_empty());
    if fp.is_none() && !plain {
        return Err("The runner pairing link has no certificate fingerprint (fp).");
    }
    if let Some(f) = &fp {
        if f.len() != 64 || !f.chars().all(|c| c.is_ascii_digit() || ('a'..='f').contains(&c)) {
            return Err("The certificate fingerprint in the link is not a SHA-256.");
        }
    }
    let mut hs: Vec<String> = Vec::new();
    for h in hosts.iter().map(|h| bracketed(h)) {
        if !hs.contains(&h) {
            hs.push(h);
        }
    }
    Ok(PairLink { name, hosts: hs, grant: single.get("grant").map(String::as_str) == Some("1") })
}

// ---- the local link folder (RunnerLinkStore, RunnerAudit) ----

fn link_dir(state_dir: &Path) -> PathBuf {
    state_dir.join(LINK_DIR)
}

fn link_exists(state_dir: &Path) -> bool {
    link_dir(state_dir).join(infantry_vault::keystore::FILE_NAME).is_file()
}

fn open_store(state_dir: &Path) -> infantry_vault::Keystore {
    let dir = link_dir(state_dir);
    let _ = std::fs::create_dir_all(&dir);
    infantry_vault::Keystore::open(dir, infantry_vault::limits::Limits::default())
}

fn field(ks: &infantry_vault::Keystore, name: &str, f: &str) -> Result<Option<String>, String> {
    ks.field(name, f, None).map_err(|e| e.to_string())
}

fn random_token(bytes: usize) -> String {
    use base64::Engine;
    let raw: Vec<u8> = (0..bytes.div_ceil(16)).flat_map(|_| *uuid::Uuid::new_v4().as_bytes()).take(bytes).collect();
    base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(raw)
}

/// `selfId`: made once (`inf-` + 9 random bytes).
fn self_id(ks: &infantry_vault::Keystore) -> Result<String, String> {
    if let Some(id) = field(ks, SELF_KEY, "id")? {
        return Ok(id);
    }
    let id = format!("inf-{}", random_token(9));
    ks.set_field(SELF_KEY, "id", &id, None).map_err(|e| e.to_string())?;
    Ok(id)
}

struct Orchestrator {
    id: String,
    name: String,
    issued_at: i64,
    runner_name: String,
}

fn orchestrator(ks: &infantry_vault::Keystore) -> Result<Option<Orchestrator>, String> {
    let Some(id) = field(ks, ORCHESTRATOR_KEY, "id")? else { return Ok(None) };
    if field(ks, ORCHESTRATOR_KEY, "credentialHash")?.is_none() {
        return Ok(None);
    }
    Ok(Some(Orchestrator {
        name: field(ks, ORCHESTRATOR_KEY, "name")?.unwrap_or_else(|| id.clone()),
        issued_at: field(ks, ORCHESTRATOR_KEY, "issuedAt")?.and_then(|v| v.parse().ok()).unwrap_or(0),
        runner_name: field(ks, ORCHESTRATOR_KEY, "runnerName")?.unwrap_or_default(),
        id,
    }))
}

struct PairedRunner {
    name: String,
    url: String,
    issued_at: i64,
    runner_id: String,
}

fn runners(ks: &infantry_vault::Keystore) -> Result<Vec<PairedRunner>, String> {
    let mut out = Vec::new();
    for key in ks.names(None).map_err(|e| e.to_string())? {
        let Some(name) = key.strip_prefix(PEER_PREFIX) else { continue };
        let (Some(url), Some(_)) = (field(ks, &key, "url")?, field(ks, &key, "credential")?) else { continue };
        out.push(PairedRunner {
            name: name.to_string(),
            url,
            issued_at: field(ks, &key, "issuedAt")?.and_then(|v| v.parse().ok()).unwrap_or(0),
            runner_id: field(ks, &key, "runnerId")?.unwrap_or_default(),
        });
    }
    Ok(out)
}

/// `RunnerAudit.record`: one JSON line (null fields left out).
fn audit_record(file: &Path, who: &str, op: &str, result: &str) {
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0);
    let mut m = Map::new();
    m.insert("at".into(), Value::from(now));
    m.insert("who".into(), Value::String(who.into()));
    m.insert("op".into(), Value::String(op.into()));
    m.insert("result".into(), Value::String(result.into()));
    if let Some(d) = file.parent() {
        let _ = std::fs::create_dir_all(d);
    }
    let line = format!("{}\n", Value::Object(m));
    let ok = std::fs::OpenOptions::new().create(true).append(true).open(file).and_then(|mut f| f.write_all(line.as_bytes()));
    if let Err(e) = ok {
        eprintln!("[infantry] runner audit write failed: {e}");
    }
}

/// `RunnerAudit.tail`: the newest [limit] entries as printed lines, oldest first.
fn audit_lines(file: &Path, limit: usize) -> Vec<String> {
    let Ok(text) = std::fs::read_to_string(file) else { return Vec::new() };
    let lines: Vec<&str> = text.lines().collect();
    let from = lines.len().saturating_sub(limit);
    lines[from..].iter().filter_map(|l| audit_line(l)).collect()
}

fn audit_line(json: &str) -> Option<String> {
    let v: Value = serde_json::from_str(json).ok()?;
    let at = v.get("at")?.as_i64()?;
    let s = |k: &str| v.get(k).and_then(|x| x.as_str()).map(str::to_string);
    let (who, op, result) = (s("who")?, s("op")?, s("result")?);
    let mut parts = vec![stamp(at), who, op];
    if let Some(t) = s("ticket") {
        parts.push(format!("ticket {t}"));
    }
    if let Some(f) = s("from") {
        parts.push(format!("from {f}"));
    }
    parts.push(result);
    Some(parts.join(" | "))
}

// ---- running ----

fn unreachable_line(ep: &Endpoint) -> String {
    let url = &ep.target.url;
    let host = crate::target::url_host(url).unwrap_or_else(|| super::args::DEFAULT_HOST.into());
    let port = url
        .split_once("://")
        .and_then(|(_, r)| r.split(['/', '?', '#']).next())
        .and_then(|a| a.rsplit_once(':'))
        .and_then(|(_, p)| p.parse::<i32>().ok())
        .unwrap_or(super::args::DEFAULT_PORT);
    format!("error: Infantry AI daemon not running on {host}:{port} — start it with `infantry daemon`")
}

fn err_sink() -> Box<dyn Fn(&str)> {
    Box::new(|l: &str| eprintln!("{l}"))
}

fn run(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let mut ep = ctx.endpoint.clone().ok_or("no connection target")?;
    ep.owner_token = super::owner_token(ctx.getenv);
    let a = Args::of(p, p.project(ctx.getenv));
    let stream = match FrameStream::open(&ep, err_sink()) {
        Ok(s) => s,
        Err(ConnError::AuthRefused(m)) => {
            ctx.err(format!("error: {m}"));
            return Ok(1);
        }
        Err(_) if a.action().as_deref() == Some("up") => match start_and_connect(ctx, &ep) {
            Some(s) => s,
            None => return Ok(2),
        },
        Err(_) => {
            ctx.err(unreachable_line(&ep));
            return Ok(2);
        }
    };
    ctx.stream = Some(stream);
    let result = {
        let state_dir = ctx.state_dir();
        let getenv = ctx.getenv;
        let mut stream = ctx.stream.take().expect("opened above");
        let r = run_open(ctx, &mut stream, &a, &state_dir, getenv);
        ctx.stream = Some(stream);
        r
    };
    if let Some(s) = ctx.stream.take() {
        s.close();
    }
    result
}

fn run_open(ctx: &mut Ctx, stream: &mut FrameStream, a: &Args, state_dir: &Path, getenv: Getenv) -> CmdResult {
    let limits = limits(stream);
    let mut r = Runner { stream, limits };
    let pos: Vec<String> = a.positional().to_vec();
    macro_rules! fail_if {
        ($ans:expr) => {
            if $ans.failed() {
                ctx.err(format!("error: {}", $ans.error()));
                return Ok(1);
            }
        };
    }
    match a.action().as_deref() {
        Some("pair") => pair(ctx, &mut r, a, a.name.clone().unwrap_or_default(), true, a.grant_on_pair),
        Some("up") => up(ctx, &mut r, a),
        Some("add") => {
            if a.bearer.is_some() {
                add_by_bearer(ctx, &mut r, a, getenv)
            } else {
                add(ctx, &mut r, a, getenv)
            }
        }
        Some("bearer") => {
            let ans = r.op(&[
                ("op", s("bearer")),
                ("name", a.name.clone()),
                ("rotate", a.rotate.then(|| "true".to_string())),
                ("hosts", a.hosts.clone()),
                ("port", a.port.clone()),
                ("allowProjects", a.allow_projects.clone()),
                ("allowHarnesses", a.allow_harnesses.clone()),
            ])?;
            fail_if!(ans);
            for line in bearer_lines(&ans) {
                ctx.out(line);
            }
            Ok(0)
        }
        Some("connect") => {
            let extra = ADMIN_CALLS * r.limit(&CONNECT_SEC);
            let ans = r.admin(
                &[
                    ("op", s("connect")),
                    ("name", a.name.clone()),
                    ("via", a.via.clone()),
                    ("host", a.hosts.clone()),
                    ("port", a.port.clone()),
                    ("orchestratorName", Some(host_name(getenv))),
                ],
                0,
                extra,
            )?;
            fail_if!(ans);
            let (name, url, id) = (ans.get("name").unwrap_or_default(), ans.get("url").unwrap_or_default(), ans.get("runnerId").unwrap_or_default());
            if ans.get("already").as_deref() == Some("true") {
                ctx.out(format!("runner '{name}' is already connected at {url} ({id}) and answers; nothing to pair"));
            } else {
                ctx.out(format!("runner '{name}' paired at {url} ({id}); every project and harness may run there"));
            }
            if let Some(sk) = ans.get("skipped") {
                ctx.out(format!("skipped: {sk}"));
            }
            if ans.get("enabledTurnedOn").as_deref() == Some("true") {
                ctx.out("runners.enabled is now on");
            }
            ctx.out(format!("Next: infantry runner test {name}"));
            Ok(0)
        }
        Some("list") => {
            if !link_exists(state_dir) {
                ctx.out("No runner link: this machine never paired.");
                return Ok(0);
            }
            let ks = open_store(state_dir);
            ctx.out(format!("this machine: {}", self_id(&ks)?));
            if let Some(o) = orchestrator(&ks)? {
                ctx.out(format!(
                    "paired as runner '{}' with orchestrator {} ({}), credential issued {}",
                    o.runner_name,
                    o.name,
                    o.id,
                    stamp(o.issued_at)
                ));
            }
            let list = runners(&ks)?;
            if list.is_empty() {
                ctx.out("paired runners: none");
            }
            for x in list {
                ctx.out(format!("runner {} | {} | {} | credential issued {}", x.name, x.url, x.runner_id, stamp(x.issued_at)));
            }
            Ok(0)
        }
        Some("rotate") => {
            let ans = r.op(&[("op", s("rotate")), ("name", Some(pos[0].clone()))])?;
            fail_if!(ans);
            ctx.out(format!("runner '{}': credential rotated; the old one no longer works", ans.get("name").unwrap_or_default()));
            Ok(0)
        }
        Some("check") => {
            let extra = r.limit(&CHECK_GIT_SEC);
            let ans = r.admin(&[("op", s("check")), ("name", Some(pos[0].clone())), ("projectId", a.project.clone()), ("harness", a.harness.clone())], 0, extra)?;
            fail_if!(ans);
            let rows = ans.checks();
            if rows.is_empty() {
                ctx.err("error: the daemon returned no check rows");
                return Ok(1);
            }
            let (lines, bad) = check_lines(&pos[0], &rows);
            for l in lines {
                ctx.out(l);
            }
            Ok(if bad > 0 { 1 } else { 0 })
        }
        Some("test") => test(ctx, &mut r, &pos[0], a.harness.clone(), a.model.clone()),
        Some("detach") => {
            if let Some(name) = pos.first() {
                let ans = r.op(&[("op", s("detach")), ("name", Some(name.clone()))])?;
                fail_if!(ans);
                ctx.out(format!("runner '{name}' detached here; {}", ans.get("remote").unwrap_or_else(|| "null".into())));
            } else {
                if !link_exists(state_dir) {
                    ctx.err("error: this machine never paired");
                    return Ok(1);
                }
                let ks = open_store(state_dir);
                let was = orchestrator(&ks)?;
                let had = was.is_some();
                let _ = ks.clear(ORCHESTRATOR_KEY, None);
                if !had {
                    ctx.out("this runner is not paired");
                    return Ok(0);
                }
                let (wname, wid) = was.map(|w| (w.name, w.id)).unwrap_or_default();
                audit_record(&link_dir(state_dir).join(AUDIT_FILE), OWNER_LOCAL, "detach", &format!("ok: orchestrator {wname} revoked"));
                ctx.out(format!(
                    "orchestrator {wname} ({wid}) revoked: its credential no longer works. The daemon stops serving the link on its next check."
                ));
            }
            Ok(0)
        }
        Some("audit") => {
            let shown = a.limit.as_deref().and_then(|l| l.parse::<usize>().ok()).unwrap_or(r.limit(&AUDIT_SHOWN) as usize);
            let lines = audit_lines(&link_dir(state_dir).join(AUDIT_FILE), shown);
            if lines.is_empty() {
                ctx.out("The runner audit log is empty.");
            }
            for l in lines {
                ctx.out(l);
            }
            Ok(0)
        }
        _ => {
            ctx.err(USAGE);
            Ok(2)
        }
    }
}

fn bearer_lines(ans: &Answer) -> Vec<String> {
    let mut out = vec![
        format!("endpoint: {}", ans.get("endpoint").unwrap_or_else(|| "null".into())),
        format!("bearer:   {}", ans.get("bearer").unwrap_or_else(|| "null".into())),
    ];
    if ans.get("hostGuessed").as_deref() == Some("true") {
        out.push("note: the endpoint's host is this machine's own address as it sees it; if the PC reaches it at another address (a server's public IP, a published Docker port), use that host and port in the app".into());
    }
    if ans.get("rotated").as_deref() == Some("true") {
        out.push("rotated: every earlier bearer is refused from now on".into());
    }
    if let Some(w) = ans.get("pairedWith") {
        out.push(format!("last paired with orchestrator {w}; pasting this bearer in any app pairs it there and replaces that link"));
    }
    let or_none = |l: Vec<String>| if l.is_empty() { "none".to_string() } else { l.join(",") };
    out.push(format!(
        "runner '{}' may run projects {} and harnesses {}",
        ans.get("name").unwrap_or_else(|| "null".into()),
        or_none(ans.list("projects")),
        or_none(ans.list("harnesses"))
    ));
    out.push("In the app: Settings > Runners > Add runner, paste the endpoint and the bearer.".into());
    out
}

/// The check's printed lines and the number of failed rows.
fn check_lines(name: &str, rows: &[CheckRow]) -> (Vec<String>, usize) {
    let mut out = Vec::new();
    for row in rows {
        let id: String = format!("{:<width$}", row.id, width = ID_COLUMN).chars().take(ID_COLUMN).collect();
        out.push(format!("{id}  {}    {}", if row.ok { "ok" } else { "FAIL" }, row.detail));
        if let Some(fix) = row.fix.as_ref().filter(|_| !row.ok) {
            out.push(format!("{}fix: {fix}", " ".repeat(ID_COLUMN + 8)));
        }
    }
    let bad = rows.iter().filter(|r| !r.ok).count();
    out.push(if bad == 0 { format!("runner '{name}': every check passed") } else { format!("runner '{name}': {bad} of {} checks failed", rows.len()) });
    (out, bad)
}

fn up(ctx: &mut Ctx, r: &mut Runner, a: &Args) -> CmdResult {
    let name = a.name.clone().unwrap_or_default();
    let preview = r.op(&[("op", s("pair_preview"))])?;
    if preview.failed() {
        ctx.err(format!("error: {}", preview.error()));
        return Ok(1);
    }
    if let Some(mode) = preview.get("mode").filter(|m| m != "runner" && m != "both") {
        ctx.err(format!("error: this daemon runs in {mode} mode; `runner up` needs a runner (or both) daemon: infantry daemon --mode runner"));
        return Ok(1);
    }
    let status = r.op(&[("op", s("pair_status"))])?;
    if status.failed() {
        ctx.err(format!("error: {}", status.error()));
        return Ok(1);
    }
    match status.get("state").as_deref() {
        Some("paired") => {
            ctx.out(format!(
                "already paired with {}; `infantry runner detach` to pair again",
                status.get("orchestratorName").unwrap_or_else(|| "null".into())
            ));
            return Ok(0);
        }
        Some("waiting") => {
            ctx.out(format!(
                "waiting: a pairing link was already issued and is not used yet; it expires on its own. For a new one: infantry runner pair --name {name}"
            ));
            return Ok(0);
        }
        _ => {}
    }
    pair(ctx, r, a, name, false, false)
}

fn test(ctx: &mut Ctx, r: &mut Runner, name: &str, harness: Option<String>, model: Option<String>) -> CmdResult {
    let extra = r.limit(&TEST_TURN_SEC);
    let ans = r.admin(&[("op", s("test")), ("name", s(name)), ("harness", harness), ("model", model)], 0, extra)?;
    let last_lines = |ctx: &mut Ctx, ans: &Answer| {
        let lines = ans.list("lastLines");
        if !lines.is_empty() {
            ctx.out("last lines of the test session:");
            for l in lines {
                ctx.out(format!("  {l}"));
            }
        }
    };
    if ans.failed() {
        ctx.err(format!("error: {}", ans.error()));
        last_lines(ctx, &ans);
        return Ok(1);
    }
    let took = match ans.get("ms").and_then(|m| m.parse::<i64>().ok()) {
        Some(ms) => took_text(ms),
        None => format!("{} s", ans.get("seconds").unwrap_or_default()),
    };
    let which: Vec<String> = [ans.get("harness"), ans.get("model")].into_iter().flatten().filter(|s| !s.trim().is_empty()).collect();
    let which = if which.is_empty() { String::new() } else { format!(" ({})", which.join(" ")) };
    match ans.get("reply").filter(|r| !r.trim().is_empty()) {
        None => {
            ctx.err(format!("error: runner '{name}'{which} gave no answer in {took}"));
            last_lines(ctx, &ans);
            Ok(1)
        }
        Some(reply) => {
            ctx.out(format!("runner '{name}'{which} answered in {took}:"));
            ctx.out(reply);
            if let Some(c) = ans.get("cleanup") {
                ctx.out(format!("note: {c}"));
            }
            Ok(0)
        }
    }
}

fn add(ctx: &mut Ctx, r: &mut Runner, a: &Args, getenv: Getenv) -> CmdResult {
    let pos = a.positional().to_vec();
    let pasted = match &a.link {
        Some(l) if l == "-" => Some(ctx.io.stdin().trim().to_string()),
        Some(l) => Some(l.clone()),
        None => pos.first().filter(|p| is_link(p)).cloned(),
    };
    let Some(pasted) = pasted else {
        let ans = r.op(&[
            ("op", s("add")),
            ("name", Some(pos[0].clone())),
            ("url", Some(pos[1].clone())),
            ("code", a.code.clone()),
            ("fingerprint", a.fingerprint.clone()),
            ("orchestratorName", Some(host_name(getenv))),
        ])?;
        if ans.failed() {
            ctx.err(format!("error: {}", ans.error()));
            if let Some(fp) = ans.get("presentedFingerprint").filter(|_| a.fingerprint.is_none()) {
                ctx.err(format!(
                    "If it matches, run: infantry runner add {} {} --code {} --fingerprint {fp}",
                    pos[0],
                    pos[1],
                    a.code.clone().unwrap_or_default()
                ));
            }
            return Ok(1);
        }
        return report_add(ctx, r, &ans);
    };
    if pasted.trim().is_empty() {
        ctx.err("error: --link - read nothing; pipe the link in, e.g. infantry runner add --link - < link.txt");
        return Ok(1);
    }
    let link = match parse_link(&pasted) {
        Ok(l) => l,
        Err(reason) => {
            ctx.err(format!("error: {reason}"));
            if a.link.is_none() {
                ctx.err(QUOTE_HINT);
            }
            return Ok(1);
        }
    };
    let project = a.project.clone().filter(|p| !p.trim().is_empty());
    if a.grant && !link.grant {
        ctx.err("error: that link's code carries no grant authority (it was not issued with `--grant-on-pair` on the runner), so this pairing may not grant the project; issue the code there with --grant-on-pair and pair again");
        return Ok(1);
    }
    let mut grant = false;
    if link.grant {
        match &project {
            None => {
                if a.grant {
                    ctx.err("error: --grant needs the project to grant: pass --project <id> (or set INFANTRY_PROJECT_ID)");
                    return Ok(1);
                }
                ctx.out("This link may grant the project it is added from, but none is known here; pass --project <id> to grant it at pairing.");
            }
            Some(project) => {
                let pv = r.op(&[("op", s("pair_preview"))])?;
                let harnesses = if pv.failed() { Vec::new() } else { pv.list("installedHarnesses") };
                let who = project_name_of(r.stream, project).map(|n| format!("{n} ({project})")).unwrap_or_else(|| project.clone());
                let list = if harnesses.is_empty() { "none installed on this machine".to_string() } else { harnesses.join(", ") };
                grant = a.grant || {
                    let prompt = format!("Grant runner {} access to project {who} and harnesses {list}? [y/N]: ", a.name.clone().unwrap_or(link.name.clone()));
                    console_read_line(&prompt).is_some_and(|ans| ans.trim().eq_ignore_ascii_case("y"))
                };
                if !grant {
                    ctx.out(format!(
                        "Pairing without a grant. To grant it, pair a new code with --grant, or grant on the runner: infantry settings set {PROJECTS_SETTING} '[\"{project}\"]'"
                    ));
                }
            }
        }
    }
    let ans = r.admin(
        &[
            ("op", s("add")),
            ("link", Some(pasted.clone())),
            ("name", a.name.clone().filter(|n| !n.trim().is_empty())),
            ("orchestratorName", Some(host_name(getenv))),
            ("grant", grant.then(|| "true".to_string())),
            ("projectId", if grant { project.clone() } else { None }),
        ],
        link.hosts.len() as u64,
        0,
    )?;
    if ans.failed() {
        ctx.err(format!("error: {}", ans.error()));
        return Ok(1);
    }
    if let Some(sk) = ans.get("skipped") {
        ctx.out(format!("skipped: {sk}"));
    }
    if ans.get("enabledTurnedOn").as_deref() == Some("true") {
        ctx.out("runners.enabled was off; turned on");
    }
    report_add(ctx, r, &ans)
}

fn add_by_bearer(ctx: &mut Ctx, r: &mut Runner, a: &Args, getenv: Getenv) -> CmdResult {
    let bearer = match a.bearer.as_deref() {
        Some("-") => ctx.io.stdin().trim().to_string(),
        Some(b) => b.to_string(),
        None => String::new(),
    };
    if bearer.trim().is_empty() {
        ctx.err("error: --bearer - read nothing; pipe the bearer in");
        return Ok(1);
    }
    let ans = r.op(&[
        ("op", s("add")),
        ("endpoint", Some(a.positional()[0].clone())),
        ("bearer", Some(bearer)),
        ("name", a.name.clone().filter(|n| !n.trim().is_empty())),
        ("orchestratorName", Some(host_name(getenv))),
    ])?;
    if ans.failed() {
        ctx.err(format!("error: {}", ans.error()));
        return Ok(1);
    }
    if ans.get("enabledTurnedOn").as_deref() == Some("true") {
        ctx.out("runners.enabled was off; turned on");
    }
    let code = report_add(ctx, r, &ans)?;
    ctx.out(format!("Next: infantry runner test {}", ans.get("name").unwrap_or_else(|| "null".into())));
    Ok(code)
}

fn report_add_lines(ans: &Answer, rotate_days: u64) -> Vec<String> {
    let mut out = vec![
        format!(
            "runner '{}' paired ({}); runner id {}",
            ans.get("name").unwrap_or_else(|| "null".into()),
            ans.get("url").unwrap_or_else(|| "null".into()),
            ans.get("runnerId").unwrap_or_else(|| "null".into())
        ),
        format!("The credential is stored in this machine's link keystore only; it rotates every {rotate_days} days."),
    ];
    let (projects, harnesses) = (ans.list("grantedProjects"), ans.list("grantedHarnesses"));
    if !projects.is_empty() || !harnesses.is_empty() {
        let mut parts = Vec::new();
        if !projects.is_empty() {
            parts.push(format!("projects {}", projects.join(", ")));
        }
        if !harnesses.is_empty() {
            parts.push(format!("harnesses {}", harnesses.join(", ")));
        }
        out.push(format!("granted on the runner: {} (its runner.projects / runner.harnesses, audited as runner_link_pair_grant)", parts.join(" and ")));
    }
    if let Some(g) = ans.get("grantRefusal").filter(|g| !g.trim().is_empty()) {
        out.push(format!("not granted: {g}"));
    }
    if let Some(rep) = ans.get("replaced").filter(|g| !g.trim().is_empty()) {
        out.push(format!("moved here: the runner was paired with orchestrator {rep}; that link no longer works"));
    }
    out
}

fn report_add(ctx: &mut Ctx, r: &Runner, ans: &Answer) -> CmdResult {
    for l in report_add_lines(ans, r.limit(&CREDENTIAL_ROTATE_DAYS)) {
        ctx.out(l);
    }
    Ok(0)
}

fn project_name_of(stream: &mut FrameStream, project: &str) -> Option<String> {
    let ans = stream.request(&Envelope::new("project_get", project), "project", "project_get", true).ok()?;
    let v: Value = serde_json::from_str(&ans.data).ok()?;
    v.get("name")?.as_str().map(str::to_string).filter(|n| !n.trim().is_empty())
}

fn pair(ctx: &mut Ctx, r: &mut Runner, a: &Args, name: String, wait: bool, grant_on_pair: bool) -> CmdResult {
    if !runner_name_ok(&name) {
        return Err(format!("runner name '{name}' must be 1-32 of a-z, 0-9 and '-' (starting with a letter or digit)"));
    }
    if grant_on_pair && a.allow_projects.is_some() {
        return Err("--grant-on-pair replaces --allow-projects: the project is granted once, from the orchestrator, at pairing".into());
    }
    let preview = r.op(&[("op", s("pair_preview"))])?;
    let installed = if preview.failed() { Vec::new() } else { preview.list("installedHarnesses") };
    let mut projects = a.allow_projects.clone();
    let mut harnesses = a.allow_harnesses.clone();
    if has_console() && !grant_on_pair {
        if harnesses.is_none() && !installed.is_empty() {
            let ans = console_read_line(&format!(
                "Harnesses the orchestrator may run here [{}] (Enter = these, 'none', or ids / *): ",
                installed.join(",")
            ))
            .map(|s| s.trim().to_string())
            .unwrap_or_default();
            harnesses = Some(if ans.is_empty() {
                installed.join(",")
            } else if ans.eq_ignore_ascii_case("none") {
                String::new()
            } else {
                ans
            });
        }
        if projects.is_none() {
            projects = Some(
                console_read_line("Project ids the orchestrator may run here (comma separated, * = every project, Enter = none yet): ")
                    .map(|s| s.trim().to_string())
                    .unwrap_or_default(),
            );
        }
    }
    let ans = r.op(&[
        ("op", s("pair")),
        ("name", Some(name.clone())),
        ("allowProjects", projects),
        ("allowHarnesses", harnesses),
        ("hosts", a.hosts.clone()),
        ("port", a.port.clone()),
        ("grantOnPair", grant_on_pair.then(|| "true".to_string())),
        ("harnessCeiling", a.grant_harnesses_max.clone().filter(|h| !h.trim().is_empty())),
    ])?;
    if ans.failed() {
        ctx.err(format!("error: {}", ans.error()));
        return Ok(1);
    }
    for l in pair_lines(&ans, &name, grant_on_pair, &tailscale_address().unwrap_or_else(|| "<this-machine-address>".into())) {
        ctx.out(l);
    }
    let expiry = ans.get("expiryMin").unwrap_or_else(|| "null".into());
    if !wait {
        ctx.out(format!("The link is a secret for {expiry} minutes and useless once used or expired. Check later with: infantry runner list"));
        return Ok(0);
    }
    loop {
        let status = r.op(&[("op", s("pair_status"))])?;
        if status.failed() {
            ctx.err(format!("error: {}", status.error()));
            return Ok(1);
        }
        match status.get("state").as_deref() {
            Some("paired") => {
                ctx.out(format!(
                    "paired with orchestrator {} ({}). The daemon serves the link from its next check (limits.runners.linkCheckSeconds).",
                    status.get("orchestratorName").unwrap_or_else(|| "null".into()),
                    status.get("orchestratorId").unwrap_or_else(|| "null".into())
                ));
                return Ok(0);
            }
            Some("waiting") => std::thread::sleep(Duration::from_millis(PAIR_POLL_STEP_MS)),
            _ => {
                ctx.err(format!("error: the pair code expired unused; run `infantry runner pair --name {name}` again"));
                return Ok(1);
            }
        }
    }
}

/// What `pair` prints once the daemon issued the code (before it waits).
fn pair_lines(ans: &Answer, name: &str, grant_on_pair: bool, address: &str) -> Vec<String> {
    let mut out = Vec::new();
    let code = ans.get("code").unwrap_or_default();
    let plain = ans.get("plain").as_deref() == Some("true");
    let fp = ans.get("fingerprint").unwrap_or_else(|| "null".into());
    let scheme = if plain { "ws" } else { "wss" };
    if let Some(link) = ans.get("link") {
        out.push(format!("Paste this into Settings > Runners > Add, or run on your PC: infantry runner add '{link}'"));
        out.push("or by hand:".into());
    }
    out.push(format!("pair code:   {code}   (one use; expires in {} min)", ans.get("expiryMin").unwrap_or_else(|| "null".into())));
    if plain {
        out.push(format!("WARNING: {ALLOW_PLAIN_TAILNET_TEXT}"));
    } else {
        out.push(format!("fingerprint: {fp}"));
    }
    out.push("On the orchestrator run:".into());
    out.push(format!(
        "  infantry runner add {name} {scheme}://{address}:{} --code {code}{}",
        ans.get("port").unwrap_or_else(|| "null".into()),
        if plain { String::new() } else { format!(" --fingerprint {fp}") }
    ));
    if grant_on_pair {
        let mut ceiling = ans.list("harnessCeiling");
        if ceiling.is_empty() {
            ceiling = ans.list("installedHarnesses");
        }
        let list = if ceiling.is_empty() { "none installed".to_string() } else { ceiling.join(", ") };
        out.push(format!(
            "This code carries a ONE-TIME grant: the first pairing may add 1 project (`infantry runner add '<link>' --grant`) and these harnesses: {list}. Nothing is granted unless the owner confirms there."
        ));
    }
    let (projects, harnesses) = (ans.list("projects"), ans.list("harnesses"));
    out.push(format!(
        "Remote starts are limited to {PROJECTS_SETTING}={} and {HARNESSES_SETTING}={} (empty = none).",
        kotlin_list(&projects),
        kotlin_list(&harnesses)
    ));
    if projects.is_empty() || harnesses.is_empty() {
        out.push(format!("An empty list grants nothing. To grant: infantry runner pair --name {name} --allow-projects <ids|*> --allow-harnesses <ids|*>"));
        out.push(format!("  or grant the project once, from the orchestrator: infantry runner pair --name {name} --grant-on-pair"));
        out.push(format!(
            "  or: infantry settings set {PROJECTS_SETTING} '[\"<project-id>\"]'  /  infantry settings set {HARNESSES_SETTING} '[\"<harness>\"]'"
        ));
    }
    out
}

// ---- runner up: start the daemon (RunnerUpLauncher) ----

/// `INFANTRY_LIMIT_RUNNERS_UP_START_SEC`, else the registry default.
fn up_wait_seconds(getenv: Getenv) -> u64 {
    getenv("INFANTRY_LIMIT_RUNNERS_UP_START_SEC").and_then(|v| v.trim().parse::<u64>().ok()).filter(|v| *v > 0).unwrap_or(UP_START_SEC.default)
}

/// The Rust daemon binary: `infantry-daemon` beside this exe, else on the PATH.
fn daemon_program() -> PathBuf {
    let exe = if cfg!(windows) { "infantry-daemon.exe" } else { "infantry-daemon" };
    std::env::current_exe()
        .ok()
        .and_then(|p| p.parent().map(|d| d.join(exe)))
        .filter(|p| p.is_file())
        .unwrap_or_else(|| PathBuf::from(exe))
}

fn url_port(url: &str) -> Option<i32> {
    url.split_once("://")?.1.split(['/', '?', '#']).next()?.rsplit_once(':')?.1.parse().ok()
}

/// Starts the daemon headless in the background and returns the open stream, or None (the reason on stderr).
fn start_and_connect(ctx: &mut Ctx, ep: &Endpoint) -> Option<FrameStream> {
    let state_dir = ctx.state_dir();
    let _ = std::fs::create_dir_all(&state_dir);
    let log = state_dir.join("runner-up.log");
    let file = |l: &Path| std::fs::OpenOptions::new().create(true).append(true).open(l);
    let (out, err) = match (file(&log), file(&log)) {
        (Ok(o), Ok(e)) => (o, e),
        (Err(e), _) | (_, Err(e)) => {
            ctx.err(format!("error: could not start the runner daemon: {e}"));
            return None;
        }
    };
    let mut cmd = std::process::Command::new(daemon_program());
    cmd.arg("--state-dir").arg(&state_dir);
    if let Some(port) = url_port(&ep.target.url).filter(|p| *p > 0) {
        cmd.arg("--port").arg(port.to_string()).env("INFANTRY_PORT", port.to_string());
    }
    cmd.stdin(std::process::Stdio::null()).stdout(out).stderr(err);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        const CREATE_NO_WINDOW: u32 = 0x0800_0000;
        const CREATE_NEW_PROCESS_GROUP: u32 = 0x0000_0200;
        cmd.creation_flags(CREATE_NO_WINDOW | CREATE_NEW_PROCESS_GROUP);
    }
    let mut child = match cmd.spawn() {
        Ok(c) => c,
        Err(e) => {
            ctx.err(format!("error: could not start the runner daemon: {e}"));
            return None;
        }
    };
    ctx.err(format!("no daemon answered; started one in runner mode (pid {}, log {})", child.id(), log.display()));
    let wait = up_wait_seconds(ctx.getenv);
    let deadline = Instant::now() + Duration::from_secs(wait);
    while Instant::now() < deadline {
        if let Ok(Some(status)) = child.try_wait() {
            ctx.err(format!("error: the runner daemon exited with {}; see {}", status.code().unwrap_or(-1), log.display()));
            return None;
        }
        // The daemon writes its owner token once it is up: read it again for each try.
        let mut e = ep.clone();
        e.owner_token = super::owner_token(ctx.getenv);
        match FrameStream::open(&e, err_sink()) {
            Ok(s) => return Some(s),
            Err(_) => std::thread::sleep(Duration::from_millis(MS_PER_SECOND / 2)),
        }
    }
    ctx.err(format!("error: the runner daemon did not answer within {wait} s (limits.runners.upStartSec); see {}", log.display()));
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn args(words: &[&str]) -> Parsed {
        let v: Vec<String> = words.iter().map(|s| s.to_string()).collect();
        super::super::args::parse(&v).unwrap()
    }

    fn err_of(words: &[&str]) -> Option<String> {
        validate(&args(words), &|_| None)
    }

    #[test]
    fn usage_like_kotlin() {
        assert_eq!(err_of(&["runner", "list"]), None);
        assert_eq!(err_of(&["runner", "pair", "--name", "r1", "--port", "7443", "--host", "a"]), None);
        assert_eq!(err_of(&["runner", "pair"]).as_deref(), Some(USAGE));
        assert_eq!(err_of(&["runner", "pair", "--name", "r", "--grant-on-pair", "--allow-projects", "p"]).as_deref(), Some(USAGE));
        assert_eq!(err_of(&["runner", "add", "infantry://runner?v=1"]), None);
        assert_eq!(err_of(&["runner", "add", "infantry://runner?v=1", "--code", "x"]), Some(format!("{USAGE}\n{QUOTE_HINT}")));
        assert_eq!(err_of(&["runner", "add", "r", "wss://h:1", "--code", "C"]), None);
        assert_eq!(err_of(&["runner", "add", "https://h:1", "--bearer", "irb1.x"]), None);
        assert_eq!(err_of(&["runner", "audit", "--limit", "0"]).as_deref(), Some(USAGE));
        assert_eq!(err_of(&["runner", "check", "r", "--project", "p"]), None);
        assert_eq!(err_of(&["runner", "frob"]).as_deref(), Some(USAGE));
    }

    #[test]
    fn link_parse() {
        let fp = "a".repeat(64);
        let l = parse_link(&format!("'infantry://runner?v=1&name=r1&host=100.64.0.7&host=::1&port=7443&code=abcd-EFGH-1234-5678&fp={fp}&grant=1'")).unwrap();
        assert_eq!(l, PairLink { name: "r1".into(), hosts: vec!["100.64.0.7".into(), "[::1]".into()], grant: true });
        assert_eq!(parse_link("infantry://runner?v=2").unwrap_err(), "This pairing link was made by a newer runner; update this app, then paste it again.");
        assert_eq!(parse_link("infantry://runner?v=1&name=r&host=h&port=1&code=abcd-abcd-abcd-abcd").unwrap_err(), "The runner pairing link has no certificate fingerprint (fp).");
        assert!(parse_link("infantry://runner?v=1&name=r&host=h&port=1&code=abcd-abcd-abcd-abcd&plain=1").is_ok());
    }

    #[test]
    fn golden_check_and_pair_lines() {
        let rows = vec![
            CheckRow { id: "contract".into(), ok: true, detail: "1.157.0".into(), fix: None },
            CheckRow { id: "harness-sign-in-long".into(), ok: false, detail: "claude: signed out".into(), fix: Some("claude login".into()) },
        ];
        let (lines, bad) = check_lines("r1", &rows);
        assert_eq!(bad, 1);
        assert_eq!(lines[0], "contract         ok    1.157.0");
        assert_eq!(lines[1], "harness-sign-in  FAIL    claude: signed out");
        assert_eq!(lines[2], format!("{}fix: claude login", " ".repeat(23)));
        assert_eq!(lines[3], "runner 'r1': 1 of 2 checks failed");

        let ans = Answer(serde_json::from_str(r#"{"ok":true,"code":"AAAA-BBBB-CCCC-DDDD","expiryMin":10,"fingerprint":"ff","port":7443,"projects":["p1"],"harnesses":[]}"#).unwrap());
        let lines = pair_lines(&ans, "r1", false, "100.64.0.7");
        assert_eq!(lines[0], "pair code:   AAAA-BBBB-CCCC-DDDD   (one use; expires in 10 min)");
        assert_eq!(lines[1], "fingerprint: ff");
        assert_eq!(lines[3], "  infantry runner add r1 wss://100.64.0.7:7443 --code AAAA-BBBB-CCCC-DDDD --fingerprint ff");
        assert_eq!(lines[4], "Remote starts are limited to runner.projects=[p1] and runner.harnesses=[] (empty = none).");
        assert_eq!(lines.len(), 8);
    }

    #[test]
    fn small_formats() {
        assert_eq!(took_text(412), "412 ms");
        assert_eq!(took_text(3200), "3.2 s");
        assert!(is_tailscale_address("100.64.0.7") && !is_tailscale_address("100.128.0.1"));
        assert!(runner_name_ok("r-1") && !runner_name_ok("-r") && !runner_name_ok("R"));
        let ans = Answer(serde_json::from_str(r#"{"ok":true,"name":"r1","url":"wss://h:1","runnerId":"inf-x","grantedProjects":["p"],"replaced":""}"#).unwrap());
        let l = report_add_lines(&ans, 30);
        assert_eq!(l[0], "runner 'r1' paired (wss://h:1); runner id inf-x");
        assert_eq!(l[2], "granted on the runner: projects p (its runner.projects / runner.harnesses, audited as runner_link_pair_grant)");
        assert_eq!(l.len(), 3);
    }
}
