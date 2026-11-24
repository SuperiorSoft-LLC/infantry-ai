// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Session records (`SessionRecord`, `SessionState`, and the types nested in it).
use crate::dto;
use crate::skip::{is_false, is_local};
use serde::{Deserialize, Serialize};
use ts_rs::TS;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[ts(export)]
pub enum SessionState {
    Starting,
    Busy,
    WaitingInput,
    Idle,
    Exited,
    Orphaned,
    Failed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
#[ts(export)]
pub enum KillOutcome {
    Graceful,
    Forced,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, TS)]
#[serde(rename_all = "lowercase")]
#[ts(export)]
pub enum ReportStatus {
    Pending,
    Reported,
    Unreported,
}

dto! { pub struct QueuedTurn { pub id: String, pub text: String, pub at: i64 } }
dto! { pub struct SessionAutomation { pub script: String, pub hook_id: String, pub since: i64 } }
dto! { pub struct ContextSource { pub source: String, pub chars: i32 } }
dto! {
    pub struct SessionReport {
        #[serde(default = "pending")] pub status: ReportStatus,
        pub trait_id: String,
        #[serde(default)] pub reprompted: bool,
        #[serde(default)] pub checked_run: i32,
        #[serde(default)] pub report_message_id: Option<String>,
        #[serde(default)] pub attention_id: Option<String>,
    }
}
fn pending() -> ReportStatus {
    ReportStatus::Pending
}
dto! {
    pub struct KeptProcess {
        pub pid: i64,
        #[serde(default)] pub started_at: Option<i64>,
        pub label: String,
        #[serde(default)] pub owner_id: Option<String>,
    }
}
fn one() -> i32 {
    1
}
dto! {
    pub struct BackgroundWork {
        pub pid: i64,
        pub label: String,
        pub started_at: i64,
        #[serde(default = "one")] pub count: i32,
    }
}
dto! {
    /// A task the agent CLI itself runs in the background (a `run_in_background` command, a Monitor, a subagent).
    pub struct BackgroundTaskView {
        pub id: String,
        pub label: String,
        pub started_at: i64,
    }
}
dto! { pub struct BgMember { pub pid: i64, #[serde(default)] pub started_at: Option<i64> } }
dto! {
    pub struct BgNotify {
        #[serde(default)] pub tail_lines: Option<i32>,
        #[serde(default)] pub patterns: Vec<String>,
        #[serde(default)] pub timeout_minutes: Option<i32>,
    }
}
dto! {
    pub struct BgJob {
        pub id: String,
        pub label: String,
        pub pid: i64,
        pub started_at: i64,
        #[serde(default)] pub last_beat: i64,
        #[serde(default)] pub log_path: Option<String>,
        #[serde(default)] pub keep: bool,
        #[serde(default)] pub notify: Option<BgNotify>,
        #[serde(default)] pub attached: bool,
        #[serde(default)] pub opt_out: Option<String>,
        #[serde(default)] pub cmd_pid: Option<i64>,
        #[serde(default)] pub follow: Vec<String>,
        #[serde(default)] pub redirects: Vec<String>,
        #[serde(default)] pub log_paused: Option<String>,
        #[serde(default)] pub idle_seconds: Option<i64>,
        #[serde(default)] pub pid_start: Option<i64>,
        #[serde(default)] pub members: Vec<BgMember>,
    }
}
dto! {
    pub struct AgentWatch {
        pub state: String,
        #[serde(default)] pub pending_since: Option<i64>,
        #[serde(default)] pub pending: Option<String>,
        pub last_activity_at: i64,
        #[serde(default)] pub nudges: i32,
    }
}
fn running() -> String {
    "running".into()
}
dto! {
    pub struct SessionProgress {
        pub mode: String,
        #[serde(default = "running")] pub state: String,
        #[serde(default, skip_serializing_if = "Option::is_none")] pub percent: Option<f64>,
        #[serde(default, skip_serializing_if = "Option::is_none")] pub label: Option<String>,
        #[serde(default, skip_serializing_if = "Vec::is_empty")] pub steps: Vec<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")] pub steps_total: Option<i32>,
        #[serde(default, skip_serializing_if = "Option::is_none")] pub steps_done: Option<i32>,
        #[serde(default, skip_serializing_if = "Option::is_none")] pub step: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")] pub done: Option<i64>,
        #[serde(default, skip_serializing_if = "Option::is_none")] pub total: Option<i64>,
        #[serde(default, skip_serializing_if = "Option::is_none")] pub bytes_per_sec: Option<f64>,
        #[serde(default, skip_serializing_if = "Option::is_none")] pub estimated: Option<bool>,
        #[serde(default, skip_serializing_if = "Option::is_none")] pub estimate_ms: Option<i64>,
        pub started_at: i64,
        pub updated_at: i64,
        #[serde(default, skip_serializing_if = "Option::is_none")] pub label_started_at: Option<i64>,
        #[serde(default, skip_serializing_if = "Option::is_none")] pub estimate_source: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")] pub last_output_at: Option<i64>,
        #[serde(default, skip_serializing_if = "Option::is_none")] pub origin: Option<String>,
        #[serde(default, skip_serializing_if = "Option::is_none")] pub stale: Option<bool>,
    }
}
dto! {
    pub struct SessionWaiting {
        pub reason: String,
        pub since: i64,
        pub until_at: i64,
        pub expires_at: i64,
        #[serde(default, skip_serializing_if = "is_false")] pub clear_on_output: bool,
        #[serde(default, skip_serializing_if = "is_zero")] pub output_grace_ms: i64,
    }
}
fn is_zero(n: &i64) -> bool {
    *n == 0
}
dto! {
    pub struct WaitingRequest {
        pub op: String,
        #[serde(default)] pub reason: Option<String>,
        #[serde(default)] pub minutes: Option<i32>,
    }
}
dto! {
    pub struct McpServerStatusView {
        pub name: String,
        pub state: String,
        #[serde(default)] pub detail: Option<String>,
        #[serde(default)] pub tool_count: Option<i32>,
    }
}

fn local() -> String {
    "local".into()
}

/// Declares `SessionRecord` (deserialization, TS) plus a private serialize mirror, so the manual
/// `Serialize` can append the computed fields after the declared ones.
macro_rules! session_record {
    ($($(#[$fm:meta])* $f:ident : $t:ty),* $(,)?) => {
        /// One session snapshot. Serializes like Kotlin's `SessionRecordSerializer`: declared fields
        /// in order (NEVER-default fields omitted), then the computed `live` and `turnActive`;
        /// an IDLE session with background work reads BUSY on the wire.
        #[derive(Debug, Clone, PartialEq, Deserialize, TS)]
        // Two attributes: ts-rs cannot parse `from`, and on one line it then drops `rename_all` too (snake_case keys in TS).
        #[serde(rename_all = "camelCase")]
        #[serde(from = "SessionRecordRaw")]
        #[ts(export)]
        pub struct SessionRecord { $($(#[$fm])* pub $f: $t,)* }

        #[derive(Deserialize)]
        #[serde(rename_all = "camelCase")]
        struct SessionRecordRaw { $($(#[$fm])* $f: $t,)* #[serde(default)] turn_active: Option<bool> }

        impl From<SessionRecordRaw> for SessionRecord {
            fn from(r: SessionRecordRaw) -> Self {
                let mut rec = SessionRecord { $($f: r.$f,)* };
                if r.turn_active == Some(false) && rec.state == SessionState::Busy {
                    rec.state = SessionState::Idle;
                }
                rec
            }
        }

        #[derive(Serialize)]
        #[serde(rename_all = "camelCase", remote = "SessionRecord")]
        struct SessionRecordBase { $($(#[$fm])* $f: $t,)* }
    };
}

session_record! {
    id: String,
    provider: String,
    working_dir: String,
    spawned_at: i64,
    last_heartbeat: i64,
    state: SessionState,
    #[serde(default)] provider_session_id: Option<String>,
    #[serde(default)] child_pid: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")] child_started_at: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")] owner_id: Option<String>,
    #[serde(default)] run_count: i32,
    #[serde(default)] resume_count: i32,
    #[serde(default)] last_exit_code: Option<i32>,
    #[serde(default)] kill_outcome: Option<KillOutcome>,
    #[serde(default, skip_serializing_if = "Option::is_none")] killed_by: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")] kill_reason: Option<String>,
    #[serde(default)] last_error: Option<String>,
    #[serde(default)] worktree_path: Option<String>,
    #[serde(default)] worktree_branch: Option<String>,
    #[serde(default)] repo_root: Option<String>,
    #[serde(default)] worktree_base_ref: Option<String>,
    #[serde(default)] model: Option<String>,
    #[serde(default)] effort: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")] context_limit: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")] context_limit_support: Option<String>,
    /// Where `context_limit` came from: `ticket`, `session`, `role`, `project`, `app` or `default`.
    #[serde(default, skip_serializing_if = "Option::is_none")] context_limit_source: Option<String>,
    /// How the harness applies it (or why it does not).
    #[serde(default, skip_serializing_if = "Option::is_none")] context_limit_how: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")] effort_support: Option<String>,
    #[serde(default)] agent_id: Option<String>,
    #[serde(default)] role: Option<String>,
    #[serde(default)] ticket_id: Option<String>,
    #[serde(default)] context_sources: Vec<ContextSource>,
    #[serde(default)] project_id: Option<String>,
    #[serde(default)] unattended: bool,
    #[serde(default)] report: Option<SessionReport>,
    #[serde(default)] is_process_alive: bool,
    #[serde(default)] dormant: bool,
    #[serde(default = "local", skip_serializing_if = "is_local")] runner: String,
    /// RR-4: the id of the harness process on `runner` (the placement is kept with the session across a daemon restart).
    #[serde(default, skip_serializing_if = "Option::is_none")] runner_proc: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")] kept_process: Option<KeptProcess>,
    #[serde(default, skip_serializing_if = "Option::is_none")] background_work: Option<BackgroundWork>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")] bg_jobs: Vec<BgJob>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")] background_tasks: Vec<BackgroundTaskView>,
    #[serde(default, skip_serializing_if = "Option::is_none")] automation: Option<SessionAutomation>,
    #[serde(default, skip_serializing_if = "Option::is_none")] last_action: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")] last_output_at: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")] last_event_at: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")] last_turn_at: Option<i64>,
    #[serde(default, skip_serializing_if = "Option::is_none")] watch: Option<AgentWatch>,
    #[serde(default, skip_serializing_if = "Option::is_none")] watch_label: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")] run_phase: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")] spawner_session_id: Option<String>,
    #[serde(default, skip_serializing_if = "is_false")] global: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")] progress: Option<SessionProgress>,
    #[serde(default, skip_serializing_if = "Option::is_none")] waiting: Option<SessionWaiting>,
    #[serde(default, skip_serializing_if = "Option::is_none")] mcp: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")] mcp_changed_at: Option<i64>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")] mcp_servers: Vec<McpServerStatusView>,
    #[serde(default, skip_serializing_if = "Vec::is_empty")] queued: Vec<QueuedTurn>,
    /// RUST-MIGRATE-1 (additive, Rust daemon only): the other app that is running this session right now (a session
    /// migrated from the Kotlin app that is live there). Computed per listing, never stored; a turn is refused while set.
    #[serde(default, skip_serializing_if = "Option::is_none")] held_by: Option<String>,
}

impl SessionRecord {
    /// STARTING, BUSY or WAITING_INPUT.
    pub fn is_busy(&self) -> bool {
        matches!(self.state, SessionState::Starting | SessionState::Busy | SessionState::WaitingInput)
    }
    /// The computed `live` field.
    pub fn live(&self) -> bool {
        self.is_busy() || (self.child_pid.is_some() && self.is_process_alive)
    }
    pub fn resumable(&self) -> bool {
        self.provider_session_id.is_some() && !self.is_busy()
    }
    pub fn never_run(&self) -> bool {
        self.provider_session_id.is_none() && self.run_count == 0 && self.state == SessionState::Idle
    }
}

impl Serialize for SessionRecord {
    fn serialize<S: serde::Serializer>(&self, s: S) -> Result<S::Ok, S::Error> {
        #[derive(Serialize)]
        struct Out<'a> {
            #[serde(flatten, serialize_with = "SessionRecordBase::serialize")]
            base: &'a SessionRecord,
            live: bool,
            #[serde(rename = "turnActive")]
            turn_active: bool,
        }
        let mut rec = self.clone();
        if (rec.background_work.is_some() || !rec.bg_jobs.is_empty() || !rec.background_tasks.is_empty()) && rec.state == SessionState::Idle {
            rec.state = SessionState::Busy;
        }
        Out { base: &rec, live: self.live(), turn_active: self.is_busy() }.serialize(s)
    }
}
