// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The runner link wire: the `runner_link_*` frame family, contract 1.11.0, byte for byte what the Kotlin link speaks.
//!
//! One connection to `<url>/runner-link` per call: the caller's id and pair credential travel in the upgrade request's
//! [`ORCHESTRATOR_HEADER`] / [`CREDENTIAL_HEADER`] (never in a frame), then ONE text frame request ([`LinkEnvelope`]) and ONE
//! text frame answer: type [`REPLY`] (data = the answer JSON) or [`ERROR`] (data = the reason). [`PAIR`] is the only frame a
//! runner accepts without a credential. Answer bodies are written with every field (Kotlin `encodeDefaults = true`), in
//! declaration order; unknown fields are ignored on read, so either side may be the newer one.

use serde::{Deserialize, Serialize};

pub const CONTRACT: &str = "1.11.0";
/// The oldest runner contract an orchestrator still drives; an older runner is `outdated`.
pub const MIN_RUNNER_CONTRACT: &str = "1.11.0";
/// What an outdated runner's refusal tells the owner to do.
pub const OUTDATED_HINT: &str = "update it: Settings > Runners > Update runner";

pub const PATH: &str = "/runner-link";
pub const CREDENTIAL_HEADER: &str = "X-Infantry-Runner-Credential";
pub const ORCHESTRATOR_HEADER: &str = "X-Infantry-Orchestrator";

pub const PAIR: &str = "runner_link_pair";
pub const HELLO: &str = "runner_link_hello";
pub const REKEY: &str = "runner_link_rekey";
pub const DETACH: &str = "runner_link_detach";
pub const START: &str = "runner_link_start";
pub const STOP: &str = "runner_link_stop";
pub const REMOVE: &str = "runner_link_remove";
pub const HEALTH: &str = "runner_link_health";
pub const DELIVER: &str = "runner_link_deliver";
pub const EVENTS: &str = "runner_link_events";
pub const TRANSCRIPT: &str = "runner_link_transcript";
pub const CHECK: &str = "runner_link_check";
pub const COMMAND: &str = "runner_link_command";
pub const PROMPT_ANSWER: &str = "runner_link_prompt_answer";
pub const FRAMES_PULL: &str = "runner_link_frames_pull";
pub const FORWARD_PULL: &str = "runner_link_forward_pull";
pub const FORWARD_ANSWER: &str = "runner_link_forward_answer";
/// RR-13: one runner-scope frame answered by the runner's own units (the plugin decides which frames it offers).
pub const FRAME: &str = "runner_link_frame";
pub const REPLY: &str = "runner_link_reply";
pub const ERROR: &str = "runner_link_error";
/// The words every runner's refusal of an operation it does not know starts its reason with.
pub const NOT_OFFERED: &str = "not a runner link operation";
/// The refusal of a frame from an orchestrator that is not (or no longer) the paired one.
pub const NOT_PAIRED: &str = "not the paired orchestrator or not its live credential";

/// Whether contract `peer` is older than `min` (`major.minor.patch`, numeric). Blank or unreadable is NOT older.
pub fn contract_older(peer: Option<&str>, min: &str) -> bool {
    fn parts(v: &str) -> Option<[u64; 3]> {
        let p: Vec<&str> = v.trim().split('.').collect();
        if p.len() != 3 {
            return None;
        }
        Some([p[0].parse().ok()?, p[1].parse().ok()?, p[2].parse().ok()?])
    }
    let (Some(p), Some(m)) = (peer.and_then(parts), parts(min)) else { return false };
    p < m
}

/// The transport frame: `type`, `data`, `requestId` (Kotlin `LinkWire.Envelope`; defaults are omitted).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct LinkEnvelope {
    #[serde(rename = "type")]
    pub r#type: String,
    #[serde(default, skip_serializing_if = "String::is_empty")]
    pub data: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub session_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_id: Option<String>,
}

/// One link answer: [`REPLY`] or [`ERROR`] and its data.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Answer {
    pub r#type: String,
    pub data: String,
}

impl Answer {
    pub fn reply(data: impl Into<String>) -> Self {
        Answer { r#type: REPLY.into(), data: data.into() }
    }
    pub fn error(reason: impl Into<String>) -> Self {
        Answer { r#type: ERROR.into(), data: reason.into() }
    }
    pub fn ok(&self) -> bool {
        self.r#type == REPLY
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairRequest {
    pub code: String,
    pub orchestrator_id: String,
    #[serde(default)]
    pub orchestrator_name: String,
    #[serde(default)]
    pub grant_projects: Vec<String>,
    #[serde(default)]
    pub grant_harnesses: Vec<String>,
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PairAnswer {
    pub runner_id: String,
    pub runner_name: String,
    pub credential: String,
    #[serde(default = "contract")]
    pub contract: String,
    #[serde(default)]
    pub granted_projects: Vec<String>,
    #[serde(default)]
    pub granted_harnesses: Vec<String>,
    #[serde(default)]
    pub grant_refusal: Option<String>,
    #[serde(default)]
    pub replaced_orchestrator: Option<String>,
}

impl std::fmt::Debug for PairAnswer {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("PairAnswer")
            .field("runner_id", &self.runner_id)
            .field("runner_name", &self.runner_name)
            .field("credential", &"redacted")
            .field("contract", &self.contract)
            .finish_non_exhaustive()
    }
}

fn contract() -> String {
    CONTRACT.to_string()
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HarnessWire {
    pub id: String,
    #[serde(default)]
    pub models: Vec<String>,
    #[serde(default)]
    pub logged_in: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HelloAnswer {
    pub runner_id: String,
    pub runner_name: String,
    #[serde(default = "contract")]
    pub contract: String,
    pub issued_at: i64,
    #[serde(default)]
    pub harnesses: Vec<HarnessWire>,
    #[serde(default)]
    pub projects: Vec<String>,
    #[serde(default)]
    pub capacity: i32,
    #[serde(default)]
    pub os: String,
    /// Rust runner extension (additive; a Kotlin peer neither sends nor reads it): the operations beyond the Kotlin
    /// contract this runner offers, e.g. `process` (a [`StartRequest::process`] start). Omitted when empty.
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    pub offers: Vec<String>,
    /// Rust runner extension (additive, RR-2): what this peer is (`runner` = the app started with `--mode runner`) and the
    /// app version it runs. Omitted by a Kotlin runner.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub kind: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub app_version: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RekeyAnswer {
    pub credential: String,
    pub issued_at: i64,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HealthAnswer {
    pub state: String,
    #[serde(default)]
    pub running: i32,
    #[serde(default = "minus_one")]
    pub free_slots: i32,
    #[serde(default)]
    pub load: Option<f64>,
}

fn minus_one() -> i32 {
    -1
}

/// A remote start (the Kotlin field set; every field after `startId` has a default).
#[derive(Clone, PartialEq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartRequest {
    pub start_id: String,
    #[serde(default)]
    pub project_id: Option<String>,
    #[serde(default)]
    pub provider: Option<String>,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub role: Option<String>,
    #[serde(default)]
    pub agent_id: Option<String>,
    #[serde(default)]
    pub effort: Option<String>,
    #[serde(default)]
    pub ticket_id: Option<String>,
    #[serde(default)]
    pub context_limit: Option<i32>,
    #[serde(default)]
    pub prompt: Option<String>,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub git_remote: Option<String>,
    #[serde(default)]
    pub base: Option<String>,
    #[serde(default)]
    pub branch: Option<String>,
    #[serde(default)]
    pub env_names: Vec<String>,
    #[serde(default)]
    pub push_on_commit: bool,
    #[serde(default)]
    pub git_name: Option<String>,
    #[serde(default)]
    pub git_email: Option<String>,
    #[serde(default)]
    pub tool_approval: Option<String>,
    #[serde(default)]
    pub prompt_relay: bool,
    /// Secret values: carried opaque, never logged (Debug names no value).
    #[serde(default)]
    pub credentials: Option<serde_json::Value>,
    /// Rust runner extension (additive): run this process instead of a harness session. Only sent to a runner whose
    /// hello [`HelloAnswer::offers`] `process`; omitted otherwise, so a Kotlin runner never sees it.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub process: Option<infantry_runner_api::ProcessSpec>,
}

impl std::fmt::Debug for StartRequest {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("StartRequest")
            .field("start_id", &self.start_id)
            .field("provider", &self.provider)
            .field("process", &self.process.as_ref().map(|p| &p.program))
            .field("credentials", &self.credentials.as_ref().map(|_| "redacted"))
            .finish_non_exhaustive()
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartAnswer {
    #[serde(default)]
    pub session_id: Option<String>,
    #[serde(default)]
    pub refusal: Option<String>,
    #[serde(default)]
    pub branch: Option<String>,
    #[serde(default)]
    pub base: Option<String>,
    #[serde(default)]
    pub worktree_path: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StopRequest {
    pub session_id: String,
    #[serde(default)]
    pub reason: Option<String>,
    #[serde(default)]
    pub force: bool,
    #[serde(default)]
    pub ticket_id: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct StopAnswer {
    pub stopped: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RemoveRequest {
    pub session_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct RemoveAnswer {
    #[serde(default)]
    pub removed: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CommandRequest {
    pub session_id: String,
    pub kind: String,
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub wake_reason: Option<String>,
    #[serde(default)]
    pub force: bool,
    #[serde(default)]
    pub by: Option<String>,
    #[serde(default)]
    pub reason: Option<String>,
    #[serde(default)]
    pub max_queued: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CommandAnswer {
    pub kind: String,
    #[serde(default)]
    pub reason: Option<String>,
    #[serde(default)]
    pub error_type: String,
    #[serde(default)]
    pub started: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EventsRequest {
    #[serde(default)]
    pub since_seq: i64,
    #[serde(default)]
    pub acked: std::collections::BTreeMap<String, i64>,
}

/// One session event: kind `status` (payload = state) or `finished` (payload = exit code).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EventWire {
    pub session_id: String,
    pub seq: i64,
    pub kind: String,
    #[serde(default)]
    pub payload: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct EventsAnswer {
    #[serde(default)]
    pub events: Vec<EventWire>,
}

/// The orchestrator's read-only health check of a runner (the fields a Rust runner reads; the rest are ignored).
#[derive(Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CheckRequest {
    #[serde(default)]
    pub project_id: Option<String>,
    #[serde(default)]
    pub harness: Option<String>,
    #[serde(default)]
    pub git_remote: Option<String>,
    #[serde(default)]
    pub contract: Option<String>,
    /// RR-6 (additive): the git credential a start would lend, so the `git` row proves the access a worker really has.
    /// Secret values: carried opaque, never logged (Debug names no value).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub credentials: Option<serde_json::Value>,
}

impl std::fmt::Debug for CheckRequest {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("CheckRequest").field("harness", &self.harness).field("credentials", &self.credentials.as_ref().map(|_| "redacted")).finish_non_exhaustive()
    }
}

/// One checked thing (`contract`, `harness:<id>`, `disk`, ...) and the fix when it failed.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CheckRow {
    pub id: String,
    pub ok: bool,
    pub detail: String,
    #[serde(default)]
    pub fix: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
pub struct CheckAnswer {
    #[serde(default)]
    pub rows: Vec<CheckRow>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FramesPullRequest {
    #[serde(default)]
    pub resume_from: std::collections::BTreeMap<String, i64>,
    #[serde(default)]
    pub wait_ms: i64,
    #[serde(default)]
    pub max: i32,
}

#[derive(Debug, Clone, PartialEq, Default, Serialize, Deserialize)]
pub struct FramesPullAnswer {
    #[serde(default)]
    pub frames: Vec<infantry_protocol::Envelope>,
    #[serde(default)]
    pub more: bool,
}

/// Process frame types a Rust runner pushes on [`FRAMES_PULL`] for a process start (data = base64 bytes, or the exit
/// code). They are link-only: never sent to a client.
pub const PROC_OUTPUT: &str = "runner_proc_output";
pub const PROC_STDERR: &str = "runner_proc_stderr";
pub const PROC_EXIT: &str = "runner_proc_exit";
/// [`CommandRequest::kind`]s a Rust runner answers for a process start; a Kotlin runner answers kind `unknown`.
pub const CMD_WRITE: &str = "proc_write";
pub const CMD_CLOSE_INPUT: &str = "proc_close_input";
/// Answers `done` when the runner knows the process (a probe before a stream is read).
pub const CMD_STATUS: &str = "proc_status";
/// The [`HelloAnswer::offers`] word of a runner that runs [`StartRequest::process`] starts.
pub const OFFER_PROCESS: &str = "process";

/// RR-5: [`CommandRequest::kind`] resizing a pty process (text = `rows,cols`).
pub const CMD_RESIZE: &str = "proc_resize";
/// RR-5 `hook-channel`: the runner opens a hook endpoint on its host and pushes each blocking call as a [`HOOK_CALL`] frame
/// (data = [`HookCallWire`] JSON) under the answered stream id; the orchestrator answers with [`FORWARD_ANSWER`]; [`REMOVE`]
/// of the stream id closes the endpoint. The pairing is what authorises it; nothing but a call's body and answer crosses.
pub const HOOK_OPEN: &str = "runner_link_hook_open";
pub const HOOK_CALL: &str = "runner_hook_call";
/// RR-5 `tail`: follows a file on the runner host (a hook channel's events file, a harness transcript); each line is a
/// [`TAIL_LINE`] frame. The paired orchestrator can already start any process there, so a tail reads nothing more.
pub const TAIL_OPEN: &str = "runner_link_tail_open";
pub const TAIL_LINE: &str = "runner_tail_line";
/// The offers words of a runner that answers [`HOOK_OPEN`] and [`TAIL_OPEN`].
pub const OFFER_HOOK_CHANNEL: &str = "hook-channel";
/// A runner that resizes a pty process on [`CMD_RESIZE`].
pub const OFFER_PTY_RESIZE: &str = "pty-resize";

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HookOpenAnswer {
    #[serde(default)]
    pub stream_id: Option<String>,
    /// `http://127.0.0.1:<port>/hook/<token>` on the runner host.
    #[serde(default)]
    pub url: String,
    #[serde(default)]
    pub events_file: String,
    #[serde(default)]
    pub refusal: Option<String>,
    #[serde(default)]
    pub error_type: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HookCallWire {
    pub call_id: String,
    pub body: String,
}

/// The answer to one forwarded call.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ForwardAnswerRequest {
    pub stream_id: String,
    pub call_id: String,
    pub body: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct ForwardAnswerAnswer {
    #[serde(default)]
    pub delivered: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TailOpenRequest {
    pub path: String,
    /// `start`, `end` or a byte offset.
    #[serde(default)]
    pub from: String,
}

/// RR-5 `loopback-forward`: a TCP tunnel from a loopback port of the runner host to a loopback port of the orchestrator.
/// [`TUNNEL_OPEN`] makes the listener (answer: its port and the stream id); frames on [`FRAMES_PULL`] under that stream id
/// carry what happens on it: [`TUNNEL_CONN`] (a client connected), [`TUNNEL_DATA`] (bytes the client sent) and
/// [`TUNNEL_CLOSE`]; the orchestrator sends the bytes back with [`TUNNEL_SEND`]. Data is [`TunnelFrame`] JSON.
pub const TUNNEL_OPEN: &str = "runner_link_tunnel_open";
pub const TUNNEL_SEND: &str = "runner_link_tunnel_send";
pub const TUNNEL_CONN: &str = "runner_tunnel_conn";
pub const TUNNEL_DATA: &str = "runner_tunnel_data";
pub const TUNNEL_CLOSE: &str = "runner_tunnel_close";
pub const OFFER_LOOPBACK_FORWARD: &str = "loopback-forward";

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TunnelOpenAnswer {
    #[serde(default)]
    pub stream_id: Option<String>,
    /// The port on the runner host's loopback.
    #[serde(default)]
    pub port: u16,
    #[serde(default)]
    pub refusal: Option<String>,
    #[serde(default)]
    pub error_type: String,
}

/// One tunnel frame's data: the connection and its bytes (base64; empty for a connect or a close).
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct TunnelFrame {
    pub conn: u64,
    #[serde(default)]
    pub data: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TunnelSendRequest {
    pub stream_id: String,
    pub conn: u64,
    /// Base64 bytes for the client.
    #[serde(default)]
    pub data: String,
    /// Close the connection (after any `data`).
    #[serde(default)]
    pub close: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct TunnelSendAnswer {
    #[serde(default)]
    pub delivered: bool,
}

/// `staged-files` over the link: the runner writes a file in its own staging folder and answers its path.
pub const STAGE_FILE: &str = "runner_link_stage_file";
/// `loopback-http` over the link: the runner makes one request on its host's loopback; the response streams back as
/// [`HTTP_HEAD`] / [`HTTP_BODY`] / [`HTTP_END`] / [`HTTP_FAILED`] frames on [`FRAMES_PULL`] under the answered stream id,
/// which the caller then removes ([`REMOVE`]).
pub const HTTP_OPEN: &str = "runner_link_http_open";
pub const HTTP_HEAD: &str = "runner_http_head";
pub const HTTP_BODY: &str = "runner_http_body";
pub const HTTP_END: &str = "runner_http_end";
pub const HTTP_FAILED: &str = "runner_http_failed";
/// The offers words of a runner that answers [`STAGE_FILE`] and [`HTTP_OPEN`] (the Runner capability names).
pub const OFFER_STAGED_FILES: &str = "staged-files";
pub const OFFER_LOOPBACK_HTTP: &str = "loopback-http";

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StageFileRequest {
    pub name: String,
    /// Base64 contents.
    pub contents: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StageFileAnswer {
    #[serde(default)]
    pub path: Option<String>,
    #[serde(default)]
    pub refusal: Option<String>,
    #[serde(default)]
    pub error_type: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HttpOpenRequest {
    pub method: String,
    pub url: String,
    #[serde(default)]
    pub headers: Vec<(String, String)>,
    /// Base64 body.
    #[serde(default)]
    pub body: String,
    #[serde(default)]
    pub timeout_ms: Option<u64>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HttpOpenAnswer {
    #[serde(default)]
    pub stream_id: Option<String>,
    #[serde(default)]
    pub refusal: Option<String>,
    #[serde(default)]
    pub error_type: String,
}

/// The [`HTTP_HEAD`] frame's data.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct HttpHeadWire {
    pub status: u16,
    #[serde(default)]
    pub headers: Vec<(String, String)>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn contract_compare() {
        assert!(contract_older(Some("1.10.9"), "1.11.0"));
        assert!(!contract_older(Some("1.11.0"), "1.11.0"));
        assert!(!contract_older(Some("2.0.0"), "1.11.0"));
        assert!(!contract_older(Some(""), "1.11.0"));
        assert!(!contract_older(None, "1.11.0"));
        assert!(!contract_older(Some("1.x.0"), "1.11.0"));
    }

    #[test]
    fn envelope_omits_defaults_like_kotlin() {
        let e = LinkEnvelope { r#type: HEALTH.into(), data: String::new(), session_id: None, request_id: Some("r1".into()) };
        assert_eq!(serde_json::to_string(&e).unwrap(), r#"{"type":"runner_link_health","requestId":"r1"}"#);
    }

    #[test]
    fn pair_answer_writes_every_field() {
        let a = PairAnswer {
            runner_id: "inf-x".into(),
            runner_name: "r".into(),
            credential: "c".into(),
            contract: CONTRACT.into(),
            granted_projects: vec![],
            granted_harnesses: vec![],
            grant_refusal: None,
            replaced_orchestrator: None,
        };
        assert_eq!(
            serde_json::to_string(&a).unwrap(),
            r#"{"runnerId":"inf-x","runnerName":"r","credential":"c","contract":"1.11.0","grantedProjects":[],"grantedHarnesses":[],"grantRefusal":null,"replacedOrchestrator":null}"#
        );
    }
}

/// [`FRAME`] request: the frame name and its JSON data text.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FrameRequest {
    pub frame: String,
    #[serde(default)]
    pub data: String,
}

/// [`FRAME`] answer: the envelope type and data the runner's own unit answered with.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FrameAnswer {
    #[serde(rename = "type")]
    pub r#type: String,
    #[serde(default)]
    pub data: String,
}
