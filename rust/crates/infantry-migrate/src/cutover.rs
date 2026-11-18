// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RUST-MIGRATE-2: `cutover` moves the live sessions from the Kotlin app to the Rust daemon.
//!
//! - plan (default): take a fresh snapshot into `--to`, then list the sessions that can be resumed (a provider
//!   conversation id is recorded) and write them to `<to>/cutover-plan.json`. Changes nothing else.
//! - `--apply`: refused while the Kotlin daemon answers, or while no Rust daemon serves `--to`. Sends each chosen
//!   session one message through the Rust daemon's MCP bridge (`infantry mcp serve`); the daemon resumes the SAME
//!   provider conversation (`claude --resume <id>`, same folder, role and model, MCP pointed at the Rust bridge).
//!   The result is recorded in `<to>/cutover.json`.
//! - `--rollback`: stops the applied sessions in the Rust daemon and marks `cutover.json` rolled back; the Kotlin
//!   state dir was never written, so the Kotlin app starts as before.
use crate::{manifest, now_ms, snapshot};
use serde_json::{json, Value};
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};

pub const PLAN_FILE: &str = "cutover-plan.json";
pub const RECORD_FILE: &str = "cutover.json";
/// The message a resumed session gets when `--message` is not given.
pub const DEFAULT_MESSAGE: &str = "Infantry AI moved this session to the Rust daemon. Your infantry_* tools now talk to it. Reply with one short line to confirm, then carry on with what you were doing.";
/// Session states that are not resumable (the session ended for good).
const ENDED: [&str; 4] = ["KILLED", "REMOVED", "FAILED", "CLOSED"];

pub struct Options {
    pub from: PathBuf,
    pub to: PathBuf,
    pub label: String,
    pub force: bool,
    /// Ids (or 8-char prefixes) to apply; empty with `all` = every planned session.
    pub sessions: Vec<String>,
    pub all: bool,
    pub message: String,
    /// The `infantry` CLI that serves the MCP bridge; None = beside this exe, else `infantry` on PATH.
    pub infantry: Option<PathBuf>,
}

/// One resumable session from the copy's `sessions.json`.
pub fn plan_entries(sessions_json: &Path) -> Result<Vec<Value>, String> {
    let raw = std::fs::read(sessions_json).map_err(|e| format!("{}: {e}", sessions_json.display()))?;
    let v: Value = serde_json::from_slice(&raw).map_err(|e| format!("{}: {e}", sessions_json.display()))?;
    let list = match v.get("sessions").unwrap_or(&v) {
        Value::Array(a) => a.clone(),
        Value::Object(o) => o.values().cloned().collect(),
        _ => vec![],
    };
    let s = |x: &Value, k: &str| x.get(k).and_then(Value::as_str).unwrap_or("").to_string();
    Ok(list
        .iter()
        .filter(|x| !s(x, "providerSessionId").is_empty() && !ENDED.contains(&s(x, "state").to_uppercase().as_str()))
        .map(|x| {
            json!({
                "id": s(x, "id"), "provider": s(x, "provider"), "model": s(x, "model"), "role": s(x, "role"),
                "projectId": s(x, "projectId"), "ticketId": s(x, "ticketId"), "state": s(x, "state"),
                "providerSessionId": s(x, "providerSessionId"), "workingDir": s(x, "workingDir"),
            })
        })
        .collect())
}

pub fn plan(o: &Options, log: &mut dyn FnMut(&str)) -> Result<Vec<String>, String> {
    let m = snapshot::run(&snapshot::Options { from: o.from.clone(), to: o.to.clone(), label: o.label.clone(), force: o.force }, log)?;
    let entries = plan_entries(&o.to.join("sessions.json"))?;
    let doc = json!({"plannedAt": now_ms(), "snapshotTakenAt": m.taken_at, "source": m.source, "sessions": entries});
    std::fs::write(o.to.join(PLAN_FILE), serde_json::to_vec_pretty(&doc).unwrap_or_default()).map_err(|e| e.to_string())?;
    let mut out = vec![format!("snapshot  {} sessions copied into {}", m.counts.sessions, o.to.display())];
    out.push(format!("plan      {} resumable session(s), written to {}", entries.len(), o.to.join(PLAN_FILE).display()));
    for e in &entries {
        let g = |k: &str| e[k].as_str().unwrap_or("").to_string();
        out.push(format!(
            "  {}  {:<5} {}/{} role={} project={} ticket={} folder={}",
            &g("id").chars().take(8).collect::<String>(),
            g("state"),
            g("provider"),
            if g("model").is_empty() { "-".into() } else { g("model") },
            g("role"),
            g("projectId"),
            if g("ticketId").is_empty() { "-".into() } else { g("ticketId") },
            g("workingDir")
        ));
    }
    if let Some(p) = snapshot::daemon_running(&o.from) {
        out.push(format!("note      the Kotlin daemon still answers on port {p}; quit it before --apply"));
    }
    out.push(format!("next      start the Rust daemon on {}, then: infantry-migrate cutover --apply --all (or --sessions ID,ID)", o.to.display()));
    Ok(out)
}

fn read_json(p: &Path) -> Result<Value, String> {
    let raw = std::fs::read(p).map_err(|e| format!("{}: {e}", p.display()))?;
    serde_json::from_slice(&raw).map_err(|e| format!("{}: {e}", p.display()))
}

fn infantry_cli(o: &Options) -> PathBuf {
    if let Some(p) = &o.infantry {
        return p.clone();
    }
    let exe = if cfg!(windows) { "infantry.exe" } else { "infantry" };
    std::env::current_exe().ok().and_then(|e| e.parent().map(|d| d.join(exe))).filter(|p| p.is_file()).unwrap_or_else(|| PathBuf::from(exe))
}

/// Call MCP tools through `infantry mcp serve --port <port>` as the owner (no session identity). One answer per call.
pub fn mcp_calls(cli: &Path, port: u16, calls: &[(String, Value)]) -> Result<Vec<(bool, String)>, String> {
    let mut child = Command::new(cli)
        .args(["mcp", "serve", "--port", &port.to_string()])
        .env_remove("INFANTRY_URL")
        .env_remove("INFANTRY_SESSION_ID")
        .env_remove("AGENTHQ_SESSION_ID")
        .env_remove("INFANTRY_ROLE")
        .env_remove("INFANTRY_PROJECT_ID")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .map_err(|e| format!("cannot start {} mcp serve: {e}", cli.display()))?;
    {
        let mut w = child.stdin.take().ok_or("no stdin")?;
        let mut lines = vec![
            json!({"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"infantry-migrate","version":"1"}}}),
            json!({"jsonrpc":"2.0","method":"notifications/initialized"}),
        ];
        for (i, (name, args)) in calls.iter().enumerate() {
            lines.push(json!({"jsonrpc":"2.0","id": i + 1,"method":"tools/call","params":{"name": name, "arguments": args}}));
        }
        for l in lines {
            writeln!(w, "{l}").map_err(|e| e.to_string())?;
        }
    }
    let mut answers: Vec<(bool, String)> = vec![(false, "no answer".into()); calls.len()];
    for line in BufReader::new(child.stdout.take().ok_or("no stdout")?).lines().map_while(Result::ok) {
        let Ok(v) = serde_json::from_str::<Value>(&line) else { continue };
        let Some(i) = v.get("id").and_then(Value::as_u64).filter(|i| *i >= 1).map(|i| i as usize - 1) else { continue };
        if i >= answers.len() {
            continue;
        }
        answers[i] = match (v.get("result"), v.get("error")) {
            (Some(r), _) => (!r["isError"].as_bool().unwrap_or(false), r["content"][0]["text"].as_str().unwrap_or("").to_string()),
            (_, Some(e)) => (false, e["message"].as_str().unwrap_or("error").to_string()),
            _ => (false, "empty answer".into()),
        };
    }
    let _ = child.wait();
    Ok(answers)
}

fn chosen(o: &Options, planned: &[Value]) -> Result<Vec<Value>, String> {
    if o.all {
        return Ok(planned.to_vec());
    }
    if o.sessions.is_empty() {
        return Err("name the sessions to resume: --sessions ID,ID (8-char prefixes work) or --all".into());
    }
    o.sessions
        .iter()
        .map(|want| {
            let hits: Vec<&Value> = planned.iter().filter(|e| e["id"].as_str().unwrap_or("").starts_with(want.as_str())).collect();
            match hits.as_slice() {
                [one] => Ok((*one).clone()),
                [] => Err(format!("{want}: not in the plan ({PLAN_FILE}); run cutover without --apply first")),
                _ => Err(format!("{want}: matches {} sessions; give more of the id", hits.len())),
            }
        })
        .collect()
}

pub fn apply(o: &Options) -> Result<Vec<String>, String> {
    manifest::read(&o.to)?;
    let src = manifest::read(&o.to).map(|m| PathBuf::from(m.source)).unwrap_or_else(|_| o.from.clone());
    if let Some(p) = snapshot::daemon_running(&src) {
        return Err(format!("the Kotlin daemon still answers on port {p} ({}); quit the Kotlin app first", src.display()));
    }
    let port = snapshot::daemon_running(&o.to).ok_or_else(|| format!("no Rust daemon serves {}; start it on the copy first", o.to.display()))?;
    let plan = read_json(&o.to.join(PLAN_FILE))?;
    let picked = chosen(o, plan["sessions"].as_array().map(Vec::as_slice).unwrap_or(&[]))?;
    let calls: Vec<(String, Value)> = picked.iter().map(|e| ("infantry_prompt".to_string(), json!({"sessionId": e["id"], "prompt": o.message}))).collect();
    let answers = mcp_calls(&infantry_cli(o), port, &calls)?;
    let mut out = vec![format!("apply     Rust daemon on port {port}, {} session(s)", picked.len())];
    let mut rows = Vec::new();
    for (e, (ok, text)) in picked.iter().zip(&answers) {
        let id = e["id"].as_str().unwrap_or("");
        out.push(format!("  {}  {}  {}", &id.chars().take(8).collect::<String>(), if *ok { "resumed" } else { "FAILED " }, text.lines().next().unwrap_or("")));
        rows.push(json!({"id": id, "providerSessionId": e["providerSessionId"], "ok": ok, "answer": text}));
    }
    let failed = answers.iter().filter(|(ok, _)| !ok).count();
    let doc = json!({"appliedAt": now_ms(), "port": port, "sessions": rows, "rolledBackAt": null});
    std::fs::write(o.to.join(RECORD_FILE), serde_json::to_vec_pretty(&doc).unwrap_or_default()).map_err(|e| e.to_string())?;
    out.push(format!("recorded  {}", o.to.join(RECORD_FILE).display()));
    if failed > 0 {
        return Err(format!("{}\n{failed} session(s) failed to resume", out.join("\n")));
    }
    Ok(out)
}

pub fn rollback(o: &Options) -> Result<Vec<String>, String> {
    let path = o.to.join(RECORD_FILE);
    let mut doc = read_json(&path)?;
    let ids: Vec<String> = doc["sessions"].as_array().into_iter().flatten().filter(|r| r["ok"].as_bool() == Some(true)).filter_map(|r| r["id"].as_str().map(str::to_string)).collect();
    let mut out = Vec::new();
    match snapshot::daemon_running(&o.to) {
        Some(port) => {
            let calls: Vec<(String, Value)> = ids.iter().map(|id| ("infantry_kill".to_string(), json!({"sessionId": id}))).collect();
            for (id, (ok, text)) in ids.iter().zip(mcp_calls(&infantry_cli(o), port, &calls)?) {
                out.push(format!("  {}  {}  {}", &id.chars().take(8).collect::<String>(), if ok { "stopped" } else { "kill failed" }, text.lines().next().unwrap_or("")));
            }
        }
        None => out.push("no Rust daemon serves the copy; nothing to stop".into()),
    }
    doc["rolledBackAt"] = json!(now_ms());
    std::fs::write(&path, serde_json::to_vec_pretty(&doc).unwrap_or_default()).map_err(|e| e.to_string())?;
    out.push(format!("rollback  recorded in {}; stop the Rust app and start the Kotlin app (its state dir was never written)", path.display()));
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn plan_keeps_resumable_sessions_and_choice_matches_prefixes() {
        let dir = std::env::temp_dir().join(format!("rm2-cutover-{}", now_ms()));
        std::fs::create_dir_all(&dir).unwrap();
        let f = dir.join("sessions.json");
        std::fs::write(
            &f,
            json!({"version":1,"sessions":[
                {"id":"aaaaaaaa-1","state":"IDLE","provider":"claude","providerSessionId":"c1"},
                {"id":"bbbbbbbb-2","state":"KILLED","provider":"claude","providerSessionId":"c2"},
                {"id":"cccccccc-3","state":"BUSY","provider":"claude","providerSessionId":""}]})
            .to_string(),
        )
        .unwrap();
        let p = plan_entries(&f).unwrap();
        assert_eq!(p.len(), 1);
        assert_eq!(p[0]["providerSessionId"], "c1");
        let mut o = Options { from: dir.clone(), to: dir.clone(), label: String::new(), force: false, sessions: vec!["aaaa".into()], all: false, message: String::new(), infantry: None };
        assert_eq!(chosen(&o, &p).unwrap().len(), 1);
        o.sessions = vec!["zz".into()];
        assert!(chosen(&o, &p).is_err());
        o.sessions.clear();
        assert!(chosen(&o, &p).is_err());
        let _ = std::fs::remove_dir_all(dir);
    }
}
