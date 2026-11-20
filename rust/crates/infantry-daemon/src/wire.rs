// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Frame bodies as the Kotlin daemon writes them (field order included; `data` is double-encoded JSON).

use serde_json::{json, Value};

/// The contract the sessions frames here are recorded against (the Kotlin fixtures say `contract=1.155.0`).
pub const CONTRACT: &str = "1.155.0";

pub const READY: &str = "ready";
pub const POLICY: &str = "policy";
pub const SESSIONS: &str = "sessions";
pub const WORKTREES: &str = "worktrees";
pub const SESSION: &str = "session";
pub const STATUS: &str = "status";
pub const TURN: &str = "turn";
pub const STDOUT: &str = "stdout";
pub const STDERR: &str = "stderr";
pub const TEXT_DELTA: &str = "text_delta";
pub const FINISHED: &str = "finished";
pub const ERROR: &str = "error";
pub const NEW: &str = "new";
pub const PROMPT: &str = "prompt";
pub const INTERRUPT: &str = "interrupt";
pub const KILL: &str = "kill";
pub const SESSION_DELETE: &str = "session_delete";
pub const PROMPT_ACK: &str = "prompt_ack";
pub const REPLAY_REQUEST: &str = "replay_request";
pub const REPLAY_GAP: &str = "replay_gap";
pub const AGENT_LIST: &str = "agent_list";
pub const AGENTS: &str = "agents";
pub const PROJECT_LIST: &str = "project_list";
pub const PROJECTS: &str = "projects";
pub const PROJECT_GET: &str = "project_get";
pub const PROJECT: &str = "project";
pub const TICKET_LIST: &str = "ticket_list";
pub const TICKETS: &str = "tickets";
pub const QUEUE_ADD: &str = "queue_add";
pub const QUEUE_REMOVE: &str = "queue_remove";
pub const QUEUE_FORCE: &str = "queue_force";
/// The typed refusal of a `prompt` into a session with a run in flight (contract 1.96.0): `run_in_flight <id>: <reason>`.
pub const RUN_IN_FLIGHT: &str = "run_in_flight";
/// The `requestId` prefix of a client message id (CHAT-SENDING-1, 1.151.0): only these are remembered for `prompt_ack`.
pub const CLIENT_SEND_PREFIX: &str = "send:";

/// The separator in `[infantry] * session started (<provider> · <model>)`.
pub const MIDDOT: &str = "\u{00B7}";

pub fn ready(provider: &str, instance: &str, build: &str) -> String {
    format!("provider={provider} contract={CONTRACT} instance={instance} since=0 build={build}")
}

/// The policy push with no policy plugin: the single `production` preset, nothing restricted.
pub fn policy() -> String {
    json!({
        "preset": "production", "label": "Production", "pinned": false, "pinnedBy": null, "canSwitch": false,
        "presets": [{"id": "production", "label": "Production", "description": "Everything enabled."}],
        "disable": [], "allow": [], "reason": null, "notice": null, "restricted": false, "notices": [],
        "ready": {}, "seatingError": null, "refusal": null, "frames": {}
    })
    .to_string()
}

/// `{"state":S,"detail":""}`.
pub fn status(state: &str) -> String {
    json!({"state": state, "detail": ""}).to_string()
}

/// A tool / activity line: `{"state":"BUSY","detail":"","tool":T,"activity":A}`.
pub fn status_activity(tool: &str, activity: &str) -> String {
    json!({"state": "BUSY", "detail": "", "tool": tool, "activity": activity}).to_string()
}

/// A file write (a change box): `{"state":"BUSY","detail":"","write":{...}}`; [write] is the `FileWrite` JSON.
pub fn status_write(write: &str) -> String {
    let w: Value = serde_json::from_str(write).unwrap_or(Value::Null);
    json!({"state": "BUSY", "detail": "", "write": w}).to_string()
}

/// The run activity (`thinking`, `finished`, `idle`) beside the session state.
pub fn status_run(state: &str, activity: &str, started_at: i64, turn_started_at: i64) -> String {
    json!({
        "state": state, "detail": "",
        "runActivity": {"state": activity, "startedAt": started_at, "turnStartedAt": turn_started_at, "lastEventAt": crate::sessions::now_ms()}
    })
    .to_string()
}

pub fn finished(exit_code: Option<i32>, outcome: &str, detail: &str) -> String {
    json!({"exitCode": exit_code, "outcome": outcome, "detail": detail}).to_string()
}

pub fn array(items: Vec<Value>) -> String {
    Value::Array(items).to_string()
}

#[cfg(test)]
mod write_tests {
    use super::*;

    #[test]
    fn a_write_status_carries_the_file_write_object() {
        let s: Value = serde_json::from_str(&status_write(r#"{"path":"a.rs","edits":[{"oldText":"x","newText":"y"}]}"#)).unwrap();
        assert_eq!(s["state"], "BUSY");
        assert_eq!(s["write"]["path"], "a.rs");
        assert_eq!(s["write"]["edits"][0]["newText"], "y");
    }

    #[test]
    fn a_claude_edit_tool_use_becomes_a_write() {
        let line: Value = serde_json::from_str(
            r#"{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Edit","input":{"file_path":"a.rs","old_string":"x","new_string":"y"}}]}}"#,
        )
        .unwrap();
        let ev = infantry_harness_claude::stream::events(&line);
        let infantry_harness::HarnessEvent::ToolStarted { name, input, .. } = &ev[0] else { panic!("no tool") };
        let w = infantry_harness::writes::file_writes(name, input, infantry_harness::writes::WRITE_MAX_BYTES);
        assert_eq!(w[0]["path"], "a.rs");
        assert_eq!(w[0]["edits"][0]["oldText"], "x");
    }
}
