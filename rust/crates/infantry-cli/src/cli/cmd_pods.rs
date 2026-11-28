// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! PARITY-AUDIT-1 #21: `infantry submit <goal>`, `watch <podId>`, `pods`, `runs <podId>`, `logs <sessionId>`,
//! `cancel <podId> [--force]` on the frames the daemon's pods unit serves (`pod_submit`, `pod_query`, `pod_cancel`,
//! answered by `pod`) and the session list (`sessions`). `submit` and `cancel` are owner-only in the daemon, so they
//! present the owner token. A pod is a pull: `watch` re-asks `pod_query` whenever the socket goes quiet.

use super::args::Parsed;
use super::cmd_board::check_contract;
use super::conn::Envelope;
use super::{CmdResult, Command, Ctx, Getenv, Needs};
use serde_json::{json, Value};

const TERMINAL: [&str; 4] = ["CONVERGED", "FAILED", "CANCELLED", "INTERRUPTED"];
/// How long `watch` waits for a frame before it asks `pod_query` again.
const WATCH_IDLE_MS: u64 = 500;
/// How long `logs` waits for the next line before it ends (exit 1 when nothing came at all).
const LOGS_IDLE_MS: u64 = 30_000;

fn st(v: &Value, k: &str) -> String {
    match &v[k] {
        Value::String(s) => s.clone(),
        Value::Null => String::new(),
        o => o.to_string(),
    }
}

fn first8(s: &str) -> String {
    s.chars().take(8).collect()
}

fn need_arg(p: &Parsed, msg: &str) -> Option<String> {
    p.args.is_empty().then(|| msg.to_string())
}

fn v_submit(p: &Parsed, _: Getenv) -> Option<String> {
    need_arg(p, "submit requires a goal")
}
fn v_watch(p: &Parsed, _: Getenv) -> Option<String> {
    need_arg(p, "watch requires a pod id")
}
fn v_runs(p: &Parsed, _: Getenv) -> Option<String> {
    need_arg(p, "runs requires a pod id")
}
fn v_logs(p: &Parsed, _: Getenv) -> Option<String> {
    need_arg(p, "logs requires a session id (see: infantry runs <podId>)")
}
fn v_cancel(p: &Parsed, _: Getenv) -> Option<String> {
    need_arg(p, "cancel requires a pod id")
}
fn v_pods(p: &Parsed, _: Getenv) -> Option<String> {
    (!p.args.is_empty()).then(|| "pods takes no arguments: infantry pods".to_string())
}

/// The `pod_submit` payload for a one-node pod with [goal].
pub fn submit_body(goal: &str, project: Option<&str>) -> Value {
    let mut b = json!({ "goal": goal, "nodes": [{ "name": "main", "prompt": goal }], "maxConcurrency": 1 });
    if let Some(p) = project {
        b["projectId"] = json!(p);
    }
    b
}

/// The lines a pod status prints (`renderPod`).
pub fn pod_lines(s: &Value) -> Vec<String> {
    let pod = &s["pod"];
    let mut out = vec![format!("[pod] {} {} goal={}", first8(&st(pod, "id")), st(pod, "state"), st(pod, "goal"))];
    for n in s["nodes"].as_array().into_iter().flatten() {
        out.push(format!("  [node] {} {} {} attempts={}/{}", first8(&st(n, "id")), st(n, "name"), st(n, "state"), st(n, "attempts"), st(n, "maxAttempts")));
    }
    out
}

/// A run's session state as an outcome (`outcomeOf`).
pub fn outcome_of(state: &str) -> &str {
    match state {
        "IDLE" => "SUCCEEDED",
        "FAILED" => "FAILED",
        "EXITED" => "KILLED",
        "ORPHANED" => "ORPHANED",
        "BUSY" | "STARTING" => "RUNNING",
        "WAITING_INPUT" => "WAITING",
        o => o,
    }
}

/// The `runs` listing of a pod status.
pub fn runs_lines(pod_id: &str, s: &Value) -> Vec<String> {
    let mut out = vec![format!("RUNS for pod {pod_id}:")];
    let runs = s["runs"].as_object().filter(|m| m.values().any(|v| v.as_array().is_some_and(|a| !a.is_empty())));
    let Some(runs) = runs else {
        out.push("  (no runs yet)".into());
        return out;
    };
    for (node, list) in runs {
        out.push(format!("  node {}:", first8(node)));
        for r in list.as_array().into_iter().flatten() {
            let exit = if r["exitCode"].is_null() { "?".into() } else { st(r, "exitCode") };
            out.push(format!("    {} outcome={} exit={} session={}", first8(&st(r, "id")), outcome_of(&st(r, "state")), exit, st(r, "sessionId")));
        }
    }
    out
}

fn query(ctx: &mut Ctx, pod: &str) -> Result<Value, String> {
    let a = ctx.stream().request(&Envelope::new("pod_query", pod), "pod", "pod_query", true)?;
    serde_json::from_str(&a.data).map_err(|e| format!("malformed pod answer from the daemon: {e}"))
}

fn state_of(s: &Value) -> String {
    st(&s["pod"], "state")
}

fn run_submit(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let goal = p.args.join(" ").trim().to_string();
    check_contract(ctx)?;
    let project = p.val("project").map(str::trim).filter(|v| !v.is_empty()).map(String::from).or_else(|| ctx.env("INFANTRY_PROJECT_ID").filter(|v| !v.trim().is_empty()));
    let env = Envelope::new("pod_submit", submit_body(&goal, project.as_deref()).to_string()).with_session(ctx.session_id());
    // Never re-sent: a second send would make a second pod.
    let a = ctx.stream().request(&env, "pod", "pod_submit", false)?;
    let v: Value = serde_json::from_str(&a.data).map_err(|e| format!("malformed pod answer from the daemon: {e}"))?;
    ctx.out(st(&v["pod"], "id"));
    Ok(0)
}

fn run_watch(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let pod = p.args[0].clone();
    check_contract(ctx)?;
    let mut status = query(ctx, &pod)?;
    for l in pod_lines(&status) {
        ctx.out(l);
    }
    while !TERMINAL.contains(&state_of(&status).as_str()) {
        let Some(f) = ctx.stream().next(WATCH_IDLE_MS)? else {
            let now = query(ctx, &pod)?;
            if state_of(&now) != state_of(&status) {
                for l in pod_lines(&now) {
                    ctx.out(l);
                }
            }
            status = now;
            continue;
        };
        match f.kind.as_str() {
            "text_delta" => ctx.out(f.data),
            "stderr" => ctx.err(f.data),
            "error" => ctx.err(format!("[error] {}", f.data)),
            "finished" => {
                let v: Value = serde_json::from_str(&f.data).unwrap_or(Value::Null);
                let o = v["outcome"].as_str().unwrap_or("UNKNOWN").to_string();
                let e = if v["exitCode"].is_null() { "?".into() } else { st(&v, "exitCode") };
                ctx.out(format!("[finished] {o} exit={e}"));
            }
            "pod" => {
                if let Ok(pushed) = serde_json::from_str::<Value>(&f.data) {
                    if st(&pushed["pod"], "id") == st(&status["pod"], "id") && state_of(&pushed) != state_of(&status) {
                        for l in pod_lines(&pushed) {
                            ctx.out(l);
                        }
                        status = pushed;
                    }
                }
            }
            _ => {}
        }
    }
    Ok(0)
}

fn run_pods(ctx: &mut Ctx, _: &Parsed) -> CmdResult {
    check_contract(ctx)?;
    let a = ctx.stream().request(&Envelope::new("sessions", ""), "sessions", "sessions", true)?;
    let Ok(Value::Array(list)) = serde_json::from_str::<Value>(&a.data) else {
        ctx.err("error: malformed sessions answer from the daemon");
        return Ok(1);
    };
    ctx.out(format!("SESSIONS: {} (pod listing: infantry runs <podId>)", list.len()));
    for s in &list {
        let state = if s["runPhase"] == "compacting" { "compacting".into() } else { st(s, "state") };
        let project = s["projectId"].as_str().map(|p| format!(" project={p}")).unwrap_or_default();
        ctx.out(format!("  {} {} started={}{}", first8(&st(s, "id")), state, st(s, "spawnedAt"), project));
    }
    Ok(0)
}

fn run_runs(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let pod = p.args[0].clone();
    check_contract(ctx)?;
    let s = query(ctx, &pod)?;
    for l in runs_lines(&pod, &s) {
        ctx.out(l);
    }
    Ok(0)
}

fn run_logs(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let session = p.args[0].clone();
    check_contract(ctx)?;
    ctx.out(format!("streaming raw stdout/stderr for session {}", first8(&session)));
    ctx.stream().send(&Envelope::new("replay_request", r#"{"lastSequence":0}"#).with_session(Some(session.clone())))?;
    let mut seen = 0usize;
    loop {
        let Some(f) = ctx.stream().next(LOGS_IDLE_MS)? else {
            if ctx.stream().take_reconnected() {
                continue;
            }
            if seen == 0 {
                ctx.err(format!("error: no output from session {} within {LOGS_IDLE_MS}ms", first8(&session)));
                return Ok(1);
            }
            return Ok(0);
        };
        if f.session_id.as_deref().is_some_and(|s| !s.starts_with(&session)) {
            continue;
        }
        match f.kind.as_str() {
            "stdout" => {
                ctx.out(f.data);
                seen += 1;
            }
            "stderr" => {
                ctx.err(f.data);
                seen += 1;
            }
            "finished" => {
                let v: Value = serde_json::from_str(&f.data).unwrap_or(Value::Null);
                let e = if v["exitCode"].is_null() { "?".into() } else { st(&v, "exitCode") };
                ctx.out(format!("[finished] {} exit={e} lines={seen}", v["outcome"].as_str().unwrap_or("UNKNOWN")));
                return Ok(0);
            }
            "replay_gap" => ctx.err(format!("[replay gap] {}", f.data)),
            "error" => ctx.err(format!("[error] {}", f.data)),
            _ => {}
        }
    }
}

fn run_cancel(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let pod = p.args[0].clone();
    check_contract(ctx)?;
    let data = if p.flag("force") { format!("{pod} force") } else { pod.clone() };
    // Cancelling a terminal pod is a no-op, so a retry is safe.
    let a = ctx.stream().request(&Envelope::new("pod_cancel", data), "pod", "pod_cancel", true)?;
    let v: Value = serde_json::from_str(&a.data).map_err(|e| format!("malformed pod answer from the daemon: {e}"))?;
    ctx.out(format!("cancelled {pod} -> {}", state_of(&v)));
    Ok(0)
}

pub static SUBMIT: Command = Command { name: "submit", needs: Needs::OwnerStream, validate: v_submit, run: run_submit };
pub static WATCH: Command = Command { name: "watch", needs: Needs::Stream, validate: v_watch, run: run_watch };
pub static PODS: Command = Command { name: "pods", needs: Needs::Stream, validate: v_pods, run: run_pods };
pub static RUNS: Command = Command { name: "runs", needs: Needs::Stream, validate: v_runs, run: run_runs };
pub static LOGS: Command = Command { name: "logs", needs: Needs::Stream, validate: v_logs, run: run_logs };
pub static CANCEL: Command = Command { name: "cancel", needs: Needs::OwnerStream, validate: v_cancel, run: run_cancel };

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn submit_is_a_one_node_pod_with_the_project_when_given() {
        let b = submit_body("fix it", Some("p1"));
        assert_eq!(b["nodes"][0]["prompt"], "fix it");
        assert_eq!(b["maxConcurrency"], 1);
        assert_eq!(b["projectId"], "p1");
        assert!(submit_body("x", None).get("projectId").is_none());
    }

    #[test]
    fn pod_and_runs_lines_have_the_kotlin_shape() {
        let s = json!({"pod": {"id": "abcdefghij", "state": "RUNNING", "goal": "g"},
            "nodes": [{"id": "n123456789", "name": "main", "state": "RUNNING", "attempts": 1, "maxAttempts": 3}],
            "runs": {"n123456789": [{"id": "r123456789", "state": "IDLE", "exitCode": 0, "sessionId": "s1"}]}});
        assert_eq!(pod_lines(&s), vec!["[pod] abcdefgh RUNNING goal=g", "  [node] n1234567 main RUNNING attempts=1/3"]);
        assert_eq!(runs_lines("abc", &s), vec!["RUNS for pod abc:", "  node n1234567:", "    r1234567 outcome=SUCCEEDED exit=0 session=s1"]);
        assert_eq!(runs_lines("abc", &json!({"runs": {}}))[1], "  (no runs yet)");
    }

    #[test]
    fn each_pod_command_needs_its_argument() {
        let p = Parsed::default();
        let ge: Getenv = &|_| None;
        assert_eq!(v_watch(&p, ge).unwrap(), "watch requires a pod id");
        assert_eq!(v_logs(&p, ge).unwrap(), "logs requires a session id (see: infantry runs <podId>)");
        assert!(v_pods(&p, ge).is_none());
    }
}
