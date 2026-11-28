// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Frame bodies as the Kotlin daemon writes them (field order included; `data` is double-encoded JSON).

use serde_json::{json, Value};

/// The contract this daemon reports (ready frame, /health): the one source in `infantry-protocol`, same as the CLI.
pub const CONTRACT: &str = infantry_protocol::CONTRACT_VERSION;

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
/// ANDROID-REPLAY-1: ends every `replay_request` answer: `{fromSequence, toSequence, hasMore}` (no sequence of its own).
pub const REPLAY_DONE: &str = "replay_done";
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
/// CHAT-GONE-1: the typed refusal of a send into a session that is gone (removed, ended, unknown after a restart): `session_gone <id>: <reason>`.
pub const SESSION_GONE: &str = "session_gone";
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

/// The longest tool activity text, in characters (the chat shows one line per tool call; the rest is cut with an ellipsis).
pub const TOOL_ACTIVITY_MAX_CHARS: usize = 160;

/// What a tool call is doing, as one line: the command or path of its input (a JSON object's `command`, `cmd`, `file_path`,
/// `filePath`, `path`, `pattern`, `url` or `query`, else its first string value; a plain string as it is), cut to
/// [`TOOL_ACTIVITY_MAX_CHARS`].
pub fn tool_activity(input: &str) -> String {
    let text = match serde_json::from_str::<Value>(input) {
        Ok(Value::Object(o)) => ["command", "cmd", "file_path", "filePath", "path", "pattern", "url", "query"]
            .iter()
            .find_map(|k| o.get(*k).and_then(Value::as_str))
            .or_else(|| o.values().find_map(Value::as_str))
            .unwrap_or("")
            .to_string(),
        Ok(Value::String(s)) => s,
        _ => input.to_string(),
    };
    let one_line = text.split_whitespace().collect::<Vec<_>>().join(" ");
    if one_line.chars().count() <= TOOL_ACTIVITY_MAX_CHARS {
        return one_line;
    }
    let cut: String = one_line.chars().take(TOOL_ACTIVITY_MAX_CHARS).collect();
    format!("{cut}...")
}

/// A file write (a change box): `{"state":"BUSY","detail":"","write":{...}}`; [write] is the `FileWrite` JSON.
pub fn status_write(write: &str) -> String {
    let w: Value = serde_json::from_str(write).unwrap_or(Value::Null);
    json!({"state": "BUSY", "detail": "", "write": w}).to_string()
}

/// The agent's to-do list (the chat's to-do box): `{"state":"BUSY","detail":"","todos":[...]}`; [todos] is the wire array.
pub fn status_todos(todos: &str) -> String {
    let t: Value = serde_json::from_str(todos).unwrap_or(Value::Array(vec![]));
    json!({"state": "BUSY", "detail": "", "todos": t}).to_string()
}

/// Context compaction began (true) or ended (false), with the session's current [state]:
/// `{"state":"BUSY","detail":"","compacting":true}`.
pub fn status_compacting(state: &str, on: bool) -> String {
    json!({"state": state, "detail": "", "compacting": on}).to_string()
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
    fn a_tool_activity_is_the_command_or_path_on_one_line() {
        assert_eq!(tool_activity(r#"{"command":"ls -la\n  /tmp","description":"list"}"#), "ls -la /tmp");
        assert_eq!(tool_activity(r#"{"filePath":"/a/b.rs"}"#), "/a/b.rs");
        assert_eq!(tool_activity(r#"{"other":"first string","n":1}"#), "first string");
        assert_eq!(tool_activity("echo hi"), "echo hi");
        assert_eq!(tool_activity("{}"), "");
        let long = tool_activity(&format!(r#"{{"command":"{}"}}"#, "x".repeat(500)));
        assert_eq!(long.chars().count(), TOOL_ACTIVITY_MAX_CHARS + 3);
        assert!(long.ends_with("..."));
    }

    #[test]
    fn a_write_status_carries_the_file_write_object() {
        let c: Value = serde_json::from_str(&status_compacting("BUSY", true)).unwrap();
        assert_eq!(c["compacting"], true);
        assert_eq!(serde_json::from_str::<Value>(&status_compacting("IDLE", false)).unwrap()["compacting"], false);
        let t: Value = serde_json::from_str(&status_todos(r#"[{"text":"a","status":"pending"}]"#)).unwrap();
        assert_eq!(t["todos"][0]["text"], "a");
        assert_eq!(t["state"], "BUSY");
        let s: Value = serde_json::from_str(&status_write(r#"{"path":"a.rs","edits":[{"oldText":"x","newText":"y"}]}"#)).unwrap();
        assert_eq!(s["state"], "BUSY");
        assert_eq!(s["write"]["path"], "a.rs");
        assert_eq!(s["write"]["edits"][0]["newText"], "y");
    }

    #[test]
    fn a_claude_edit_tool_use_becomes_a_write() {
        // The daemon no longer links the Claude harness (it is a separate crate behind the harness seam), so the
        // event the Claude stream parser emits for an Edit tool call is built here: its input is the tool's JSON.
        let ev = infantry_harness::HarnessEvent::ToolStarted {
            id: "t1".into(),
            name: "Edit".into(),
            input: r#"{"file_path":"a.rs","old_string":"x","new_string":"y"}"#.into(),
        };
        let infantry_harness::HarnessEvent::ToolStarted { name, input, .. } = &ev else { panic!("no tool") };
        let w = infantry_harness::writes::file_writes(name, input, infantry_harness::writes::WRITE_MAX_BYTES);
        assert_eq!(w[0]["path"], "a.rs");
        assert_eq!(w[0]["edits"][0]["oldText"], "x");
    }
}

#[cfg(test)]
mod contract_version_tests {
    /// CONTRACT-VER-1: the daemon must report the same contract as the CLI built from the same tree.
    #[test]
    fn daemon_contract_is_the_protocol_contract() {
        assert_eq!(super::CONTRACT, infantry_protocol::CONTRACT_VERSION);
        assert!(super::ready("stub", "i", "b").contains(&format!(" contract={} ", infantry_protocol::CONTRACT_VERSION)));
    }
}
