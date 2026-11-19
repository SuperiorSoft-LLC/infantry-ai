// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `infantry health [--json]` (Kotlin `HealthCli`): the daemon's `health` report, or, when it does not answer, the
//! report read from the state dir's journal (`health/status.json`, `health/events.jsonl`: Kotlin `OfflineHealth`).
//! Exit 0 when the daemon is up, 1 when it is down. The text is Kotlin's `HealthText.render`.

use super::args::Parsed;
use super::conn::{Envelope, FrameStream};
use super::{CmdResult, Command, Ctx, Getenv, Needs};
use serde_json::{json, Value};
use std::path::Path;

/// `RECENT_EVENTS`: restart / disconnect lines the offline report carries.
const RECENT_EVENTS: usize = 20;
/// The owner's daemon and UI and the PM's MCP pair: off limits to every agent and test (dev default).
const DEV_OWNER_PORTS: [u16; 4] = [8787, 8788, 8797, 8798];
const TEST_PORT_RANGE: &str = "18900-18999";

fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

fn live(ctx: &mut Ctx) -> Option<Value> {
    let ep = ctx.endpoint.as_ref()?;
    let mut stream = FrameStream::open(ep, Box::new(|_| {})).ok()?;
    let data = match ctx.session_id() {
        Some(s) => json!({"sessionId": s.replace('"', "")}).to_string(),
        None => "{}".into(),
    };
    let answer = stream.request(&Envelope::new("health", data), "health", "health", true).ok();
    stream.close();
    answer.filter(|a| !a.data.trim().is_empty()).and_then(|a| serde_json::from_str::<Value>(&a.data).ok()).filter(Value::is_object)
}

/// True when process [pid] runs (no window: CREATE_NO_WINDOW).
pub fn alive(pid: i64) -> bool {
    if pid <= 0 {
        return false;
    }
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        std::process::Command::new("tasklist")
            .args(["/FI", &format!("PID eq {pid}"), "/NH", "/FO", "CSV"])
            .creation_flags(0x0800_0000)
            .output()
            .map(|o| String::from_utf8_lossy(&o.stdout).contains(&format!("\"{pid}\"")))
            .unwrap_or(false)
    }
    #[cfg(not(windows))]
    {
        Path::new(&format!("/proc/{pid}")).exists()
            || std::process::Command::new("kill").args(["-0", &pid.to_string()]).output().map(|o| o.status.success()).unwrap_or(false)
    }
}

fn owner_ports(getenv: Getenv) -> Vec<u16> {
    if let Some(v) = getenv("INFANTRY_OWNER_PORTS") {
        return v.split(',').filter_map(|p| p.trim().parse().ok()).collect();
    }
    if getenv("INFANTRY_OWN_URL_ONLY").is_some_and(|v| !v.trim().is_empty()) {
        return Vec::new();
    }
    DEV_OWNER_PORTS.to_vec()
}

fn shells(os: &str) -> &'static str {
    if os.to_lowercase().starts_with("windows") {
        "PowerShell (pwsh 7: && and || work; no head/tail/grep/rm -rf) and Git Bash (POSIX; /dev/null; forward slashes); each tool call is one shell, state does not persist"
    } else {
        "bash/zsh (POSIX)"
    }
}

fn os_name() -> String {
    match std::env::consts::OS {
        "windows" => "Windows".into(),
        "macos" => "Mac OS X".into(),
        "linux" => "Linux".into(),
        o => o.into(),
    }
}

/// Where `infantry` resolves on PATH (read-only).
fn cli_path_line(getenv: Getenv) -> Option<String> {
    let path = getenv("PATH")?;
    let names: &[&str] = if cfg!(windows) { &["infantry.exe", "infantry.cmd", "infantry.bat"] } else { &["infantry"] };
    for d in std::env::split_paths(&path) {
        for n in names {
            if d.join(n).is_file() {
                return Some(format!("`infantry` is on PATH at {}", d.display()));
            }
        }
    }
    Some("`infantry` is NOT on PATH. Add it: infantry install-cli".into())
}

/// `OfflineHealth.report`: the daemon row from `status.json`, the restart tail from `events.jsonl`.
pub fn offline(state_dir: &Path, alive: &dyn Fn(i64) -> bool, now: i64, getenv: Getenv) -> Value {
    let dir = state_dir.join("health");
    let st: Option<Value> = std::fs::read_to_string(dir.join("status.json")).ok().and_then(|t| serde_json::from_str(&t).ok());
    let running = st.as_ref().map(|s| s["running"].as_bool().unwrap_or(true)).unwrap_or(false);
    let pid = st.as_ref().and_then(|s| s["pid"].as_i64());
    let up = st.is_some() && running && pid.is_some_and(alive);
    let g = |k: &str| st.as_ref().map(|s| s[k].clone()).unwrap_or(Value::Null);
    let cause: Value = match &st {
        None => json!(format!("no health record in {} (the daemon never started here, or predates DAEMON-HEALTH-1)", state_dir.display())),
        Some(s) if !running => json!(format!("clean stop: {}", s["stopReason"].as_str().unwrap_or("no reason"))),
        Some(_) if !up => json!("died without a clean stop"),
        Some(s) => s["lastRestartCause"].clone(),
    };
    let uptime = if up { g("startedAt").as_i64().map(|t| json!(now - t)).unwrap_or(Value::Null) } else { Value::Null };
    let text = std::fs::read_to_string(dir.join("events.jsonl")).unwrap_or_default();
    let lines: Vec<&str> = text.lines().filter(|l| !l.trim().is_empty()).collect();
    let events: Vec<Value> = lines[lines.len().saturating_sub(RECENT_EVENTS)..].iter().filter_map(|l| serde_json::from_str(l).ok()).collect();
    let os = os_name();
    json!({
        "at": now,
        "daemon": {
            "up": up, "pid": g("pid"), "port": g("port"), "build": g("build"), "startedAt": g("startedAt"), "uptimeMs": uptime,
            "lastSeen": g("lastSeen"), "lastRestartAt": g("lastRestartAt"), "lastRestartCause": cause, "source": "files",
        },
        "plugins": [], "pluginRows": {}, "mcp": [], "mcpServers": [], "hooks": null, "hookFailures": null,
        "freeMemoryMb": null, "freeDiskMb": null,
        "environment": {
            "os": os, "arch": std::env::consts::ARCH, "shells": shells(&os), "projectPath": null, "worktreePath": null,
            "gitBranch": null, "jdk": "none (Rust CLI)", "buildTools": {}, "ownerPorts": owner_ports(getenv),
            "testPortRange": TEST_PORT_RANGE, "harnesses": [], "stateDir": state_dir.to_string_lossy(), "cliPath": cli_path_line(getenv),
        },
        "events": events,
    })
}

pub fn age(ms: i64) -> String {
    let s = (ms / 1000).max(0);
    match s {
        s if s < 60 => format!("{s}s"),
        s if s < 3600 => format!("{}m", s / 60),
        s if s < 86_400 => format!("{}h {}m", s / 3600, s % 3600 / 60),
        s => format!("{}d", s / 86_400),
    }
}

/// `Instant.toString`: ISO-8601 UTC, fraction only when non-zero.
pub fn instant(ms: i64) -> String {
    let Some(t) = chrono::DateTime::from_timestamp_millis(ms) else { return ms.to_string() };
    let frac = ms.rem_euclid(1000);
    if frac == 0 {
        t.format("%Y-%m-%dT%H:%M:%SZ").to_string()
    } else {
        format!("{}.{frac:03}Z", t.format("%Y-%m-%dT%H:%M:%S"))
    }
}

fn opt(v: &Value) -> String {
    match v {
        Value::Null => "?".into(),
        Value::String(s) => s.clone(),
        o => o.to_string(),
    }
}

/// `HealthText.render`.
pub fn render(r: &Value) -> String {
    let mut o: Vec<String> = Vec::new();
    let at = r["at"].as_i64().unwrap_or(0);
    let d = &r["daemon"];
    let source = d["source"].as_str().unwrap_or("live");
    if d["up"].as_bool().unwrap_or(false) {
        o.push(format!("daemon: UP ({source})"));
    } else {
        o.push(format!("daemon: DOWN ({source}{})", d["lastSeen"].as_i64().map(|t| format!(", last seen {} ago", age(at - t))).unwrap_or_default()));
    }
    if !d["pid"].is_null() {
        o.push(format!("  pid {}, port {}, build {}", opt(&d["pid"]), opt(&d["port"]), opt(&d["build"])));
    }
    if let Some(u) = d["uptimeMs"].as_i64() {
        o.push(format!("  uptime {}", age(u)));
    }
    if let Some(c) = d["lastRestartCause"].as_str() {
        o.push(format!("  last restart: {c}{}", d["lastRestartAt"].as_i64().map(|t| format!(" ({} ago)", age(at - t))).unwrap_or_default()));
    }
    let arr = |v: &Value| v.as_array().cloned().unwrap_or_default();
    let plugins = arr(&r["plugins"]);
    let bad: Vec<&Value> = plugins.iter().filter(|p| p["status"] != "loaded").collect();
    o.push(format!("plugins: {} loaded, {} not", plugins.len() - bad.len(), bad.len()));
    for p in bad {
        o.push(format!("  {}: {}", opt(&p["id"]), opt(&p["status"])));
    }
    if let Some(rows) = r["pluginRows"].as_object() {
        for (k, v) in rows {
            o.push(format!("  [{k}] {}", opt(v)));
        }
    }
    let mcp = arr(&r["mcp"]);
    let up = mcp.iter().filter(|m| m["connected"] == true).count();
    o.push(format!("mcp bridges: {up} connected, {} dropped", mcp.len() - up));
    for m in &mcp {
        o.push(format!(
            "  {}: {}, last seen {} ago, {} reconnect(s)",
            opt(&m["sessionId"]),
            if m["connected"] == true { "connected" } else { "DROPPED" },
            age(at - m["lastSeen"].as_i64().unwrap_or(at)),
            m["reconnects"].as_i64().unwrap_or(0)
        ));
    }
    for s in arr(&r["mcpServers"]) {
        let names: Vec<String> = arr(&s["servers"]).iter().map(opt).collect();
        o.push(format!("  {} servers: {}", opt(&s["sessionId"]), names.join(", ")));
    }
    if !r["hooks"].is_null() {
        o.push(format!("hooks: {}{}", opt(&r["hooks"]), if r["hookFailures"].is_null() { String::new() } else { format!(", {} with failures", opt(&r["hookFailures"])) }));
    }
    if !r["freeMemoryMb"].is_null() || !r["freeDiskMb"].is_null() {
        o.push(format!("free: memory {} MB, disk {} MB", opt(&r["freeMemoryMb"]), opt(&r["freeDiskMb"])));
    }
    let e = &r["environment"];
    let s = |k: &str| e[k].as_str().map(String::from);
    o.push("environment:".into());
    let tools: String = e["buildTools"].as_object().map(|m| m.iter().map(|(k, v)| format!("; {k} {}", opt(v))).collect()).unwrap_or_default();
    o.push(format!("  os {} {}; jdk {}{tools}", s("os").unwrap_or_default(), s("arch").unwrap_or_default(), s("jdk").unwrap_or_default()));
    o.push(format!("  shells: {}", s("shells").unwrap_or_default()));
    o.push(if s("projectPath").is_none() && s("worktreePath").is_none() {
        "  project: none applies (no session and no project for this call)".into()
    } else {
        format!(
            "  project {}; worktree {}; branch {}",
            s("projectPath").unwrap_or("-".into()),
            s("worktreePath").unwrap_or("-".into()),
            s("gitBranch").unwrap_or("-".into())
        )
    });
    let ports: Vec<String> = arr(&e["ownerPorts"]).iter().map(opt).collect();
    if !ports.is_empty() {
        o.push(format!("  OWNER ports (off limits): {}; free for test daemons: {}", ports.join(", "), s("testPortRange").unwrap_or_default()));
    }
    let hs: Vec<String> = arr(&e["harnesses"]).iter().map(opt).collect();
    o.push(format!("  harnesses: {}", if hs.is_empty() { "none".into() } else { hs.join(", ") }));
    o.push(format!("  state dir: {}", s("stateDir").unwrap_or_default()));
    if let Some(c) = s("cliPath") {
        o.push(format!("  CLI: {c}"));
    }
    let events = arr(&r["events"]);
    if !events.is_empty() {
        o.push("recent restarts / disconnects:".into());
        for ev in events {
            o.push(format!(
                "  {} {}{}{}",
                instant(ev["at"].as_i64().unwrap_or(0)),
                opt(&ev["kind"]),
                ev["cause"].as_str().map(|c| format!(" ({c})")).unwrap_or_default(),
                ev["detail"].as_str().map(|d| format!(" {d}")).unwrap_or_default()
            ));
        }
    }
    o.join("\n").trim_end().to_string()
}

/// Kotlin's `prettyPrint` JSON: four-space indent.
pub fn pretty(v: &Value) -> String {
    use serde::Serialize;
    let mut buf = Vec::new();
    let fmt = serde_json::ser::PrettyFormatter::with_indent(b"    ");
    let mut ser = serde_json::Serializer::with_formatter(&mut buf, fmt);
    let _ = v.serialize(&mut ser);
    String::from_utf8(buf).unwrap_or_default()
}

fn run(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let report = match live(ctx) {
        Some(r) => r,
        None => offline(&ctx.state_dir(), &alive, now_ms(), ctx.getenv),
    };
    ctx.out(if p.flag("json") { pretty(&report) } else { render(&report) });
    Ok(if report["daemon"]["up"].as_bool().unwrap_or(false) { 0 } else { 1 })
}

pub static HEALTH: Command = Command { name: "health", needs: Needs::Target, validate: super::no_validation, run };

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn offline_report_from_the_journal() {
        let dir = std::env::temp_dir().join(format!("infantry-cli-health-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(dir.join("health")).unwrap();
        std::fs::write(
            dir.join("health").join("status.json"),
            r#"{"pid":4242,"port":18990,"build":"b1","startedAt":1000,"lastSeen":61000,"running":false,"stopReason":"owner quit"}"#,
        )
        .unwrap();
        std::fs::write(dir.join("health").join("events.jsonl"), "{\"at\":0,\"kind\":\"stop\",\"cause\":\"owner quit\"}\n").unwrap();
        let env = |k: &str| (k == "INFANTRY_OWNER_PORTS").then(|| "".to_string());
        let r = offline(&dir, &|_| true, 121_000, &env);
        let text = render(&r);
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(lines[0], "daemon: DOWN (files, last seen 1m ago)");
        assert_eq!(lines[1], "  pid 4242, port 18990, build b1");
        assert_eq!(lines[2], "  last restart: clean stop: owner quit");
        assert_eq!(lines[3], "plugins: 0 loaded, 0 not");
        assert_eq!(*lines.last().unwrap(), "  1970-01-01T00:00:00Z stop (owner quit)");
        assert!(pretty(&r).contains("\n    \"at\": 121000"));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
