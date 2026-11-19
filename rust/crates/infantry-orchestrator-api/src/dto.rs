// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The board's wire DTOs, byte-compatible with the Kotlin `infantryProtocol` `Tickets.kt` as the daemon
//! encodes them (`encodeDefaults = true`, nulls written, declaration order). Requests decode strictly
//! (unknown keys refused), like Kotlin's `StrictJson`.
use serde::{Deserialize, Serialize};

/// The board's columns, as the wire spells them, in board order.
pub mod status {
    pub const BACKLOG: &str = "backlog";
    pub const TODO: &str = "todo";
    pub const IN_PROGRESS: &str = "in_progress";
    pub const PENDING: &str = "pending";
    pub const DONE: &str = "done";
    pub const ALL: [&str; 5] = [BACKLOG, TODO, IN_PROGRESS, PENDING, DONE];
}

/// A ticket's type; [`ticket_type::normalize`] maps a retired spelling to its replacement.
pub mod ticket_type {
    pub const ALL: [&str; 9] =
        ["enhancement", "defect", "restructure", "upkeep", "documentation", "cosmetic", "performance", "testing", "hardening"];
    const RETIRED: [(&str, &str); 9] = [
        ("feature", "enhancement"),
        ("bug", "defect"),
        ("refactor", "restructure"),
        ("chore", "upkeep"),
        ("docs", "documentation"),
        ("style", "cosmetic"),
        ("perf", "performance"),
        ("test", "testing"),
        ("security", "hardening"),
    ];
    pub fn normalize(t: &str) -> &str {
        RETIRED.iter().find(|(old, _)| *old == t).map(|(_, new)| *new).unwrap_or(t)
    }
}

/// Link types: four stored directions and their reverses.
pub mod link_type {
    pub const BLOCKED_BY: &str = "blocked_by";
    pub const BLOCKS: &str = "blocks";
    pub const DEPENDS_ON: &str = "depends_on";
    pub const CHILD_OF: &str = "child_of";
    pub const PARENT_OF: &str = "parent_of";
    pub const RELATES_TO: &str = "relates_to";
    pub const DUPLICATES: &str = "duplicates";
    pub const DUPLICATED_BY: &str = "duplicated_by";
    pub const ALL: [&str; 8] = [BLOCKED_BY, BLOCKS, DEPENDS_ON, CHILD_OF, PARENT_OF, RELATES_TO, DUPLICATES, DUPLICATED_BY];

    /// How a stored direction reads from the other ticket's side.
    pub fn reverse(t: &str) -> &str {
        match t {
            BLOCKED_BY => BLOCKS,
            CHILD_OF => PARENT_OF,
            DUPLICATES => DUPLICATED_BY,
            other => other,
        }
    }
}

pub mod skills_mode {
    pub const MERGE: &str = "merge";
    pub const REPLACE: &str = "replace";
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TicketLink {
    #[serde(rename = "type")]
    pub r#type: String,
    pub ticket_id: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HasWork {
    pub branch: String,
    pub tip: String,
    pub ahead: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ExecutorOverrides {
    #[serde(default)]
    pub provider: Option<String>,
    #[serde(default)]
    pub model: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExecutorField {
    #[serde(default)]
    pub value: Option<String>,
    pub source: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct EffectiveExecutor {
    #[serde(default)]
    pub agent_id: Option<String>,
    #[serde(default)]
    pub role: Option<String>,
    #[serde(default)]
    pub provider: Option<ExecutorField>,
    #[serde(default)]
    pub model: Option<ExecutorField>,
    #[serde(default)]
    pub rule: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ContextSwitch {
    pub value: bool,
    pub source: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EffectiveContext {
    pub include_linked_tasks_context: ContextSwitch,
    #[serde(default)]
    pub include_skills_context: Option<ContextSwitch>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TicketRun {
    pub session_id: String,
    pub started_at: i64,
    #[serde(default)]
    pub ended_at: Option<i64>,
    #[serde(default)]
    pub outcome: Option<String>,
    #[serde(default)]
    pub detail: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TicketStart {
    pub ok: bool,
    pub reason: String,
    #[serde(default)]
    pub detail: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EffectiveContextLimit {
    #[serde(default)]
    pub value: Option<i32>,
    pub source: String,
    #[serde(default)]
    pub support: Option<String>,
    #[serde(default)]
    pub how: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EffectiveEffort {
    #[serde(default)]
    pub value: Option<String>,
    pub source: String,
    #[serde(default)]
    pub support: Option<String>,
    #[serde(default)]
    pub how: Option<String>,
}

/// One MCP need of a ticket's worker. On the wire every field is written; in the db the `mcp`
/// column holds the compact form (see [`TicketMcp::to_stored`]).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TicketMcpRequire {
    #[serde(default)]
    pub server: Option<String>,
    #[serde(default)]
    pub capability: Option<String>,
    #[serde(default)]
    pub purpose: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TicketMcp {
    #[serde(default)]
    pub require: Vec<TicketMcpRequire>,
}

impl TicketMcp {
    /// Why this declaration cannot be stored, or None.
    pub fn problem(&self) -> Option<String> {
        self.require.iter().find_map(|r| {
            let s = r.server.as_deref().unwrap_or("").trim();
            let c = r.capability.as_deref().unwrap_or("").trim();
            if (s.is_empty() && c.is_empty()) || (!s.is_empty() && !c.is_empty()) {
                Some("an mcp entry names exactly one of server or capability".to_string())
            } else if r.purpose.trim().is_empty() {
                let name: String = if s.is_empty() { c } else { s }.chars().take(60).collect();
                Some(format!("an mcp entry needs a purpose: why this ticket needs '{name}'"))
            } else {
                None
            }
        })
    }

    /// The stored text: defaults and nulls left out (Kotlin `encodeDefaults = false, explicitNulls = false`).
    pub fn to_stored(&self) -> String {
        // Field order matters (byte-equal with Kotlin): a struct, not a (sorted) serde_json::Map.
        #[derive(Serialize)]
        struct Req<'a> {
            #[serde(skip_serializing_if = "Option::is_none")]
            server: Option<&'a str>,
            #[serde(skip_serializing_if = "Option::is_none")]
            capability: Option<&'a str>,
            #[serde(skip_serializing_if = "str::is_empty")]
            purpose: &'a str,
        }
        #[derive(Serialize)]
        struct Top<'a> {
            #[serde(skip_serializing_if = "Vec::is_empty")]
            require: Vec<Req<'a>>,
        }
        let top = Top {
            require: self
                .require
                .iter()
                .map(|r| Req { server: r.server.as_deref(), capability: r.capability.as_deref(), purpose: &r.purpose })
                .collect(),
        };
        serde_json::to_string(&top).expect("plain strings serialize")
    }

    /// A stored text as a declaration: None when blank, unreadable or empty (Kotlin `TicketMcp.stored`, lenient).
    pub fn stored(text: Option<&str>) -> Option<TicketMcp> {
        #[derive(Deserialize)]
        struct Lenient {
            #[serde(default)]
            require: Vec<LenientReq>,
        }
        #[derive(Deserialize)]
        struct LenientReq {
            #[serde(default)]
            server: Option<String>,
            #[serde(default)]
            capability: Option<String>,
            #[serde(default)]
            purpose: String,
        }
        let t = text?.trim();
        if t.is_empty() {
            return None;
        }
        let l: Lenient = serde_json::from_str(t).ok()?;
        let mcp = TicketMcp {
            require: l
                .require
                .into_iter()
                .map(|r| TicketMcpRequire { server: r.server, capability: r.capability, purpose: r.purpose })
                .collect(),
        };
        (!mcp.require.is_empty()).then_some(mcp)
    }
}

/// One ticket, as the daemon holds it: the answer to `ticket_save` / `ticket_get` / `ticket_move`, the
/// `ticket` push, and one element of `tickets`. Field order is the wire's.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Ticket {
    pub id: String,
    pub title: String,
    #[serde(default)]
    pub description: String,
    #[serde(default)]
    pub ticket_type: Option<String>,
    #[serde(default)]
    pub project_id: Option<String>,
    #[serde(default)]
    pub epic_id: Option<String>,
    pub status: String,
    pub position: i32,
    #[serde(default)]
    pub wont_fix: bool,
    #[serde(default)]
    pub links: Vec<TicketLink>,
    #[serde(default)]
    pub held_by: Vec<String>,
    #[serde(default)]
    pub hold_reason: Option<String>,
    pub created_at: i64,
    pub updated_at: i64,
    #[serde(default)]
    pub agent_id: Option<String>,
    #[serde(default)]
    pub provider: Option<String>,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub use_worktree: Option<bool>,
    #[serde(default)]
    pub worktree_branch: Option<String>,
    #[serde(default)]
    pub include_linked_tasks_context: Option<bool>,
    #[serde(default)]
    pub queued_for: Option<String>,
    #[serde(default)]
    pub queued_at: Option<i64>,
    #[serde(default)]
    pub queued_agent: Option<String>,
    #[serde(default)]
    pub queue_rank: Option<i32>,
    #[serde(default)]
    pub runner: Option<String>,
    #[serde(default)]
    pub queued_agent_name: Option<String>,
    #[serde(default)]
    pub session_id: Option<String>,
    #[serde(default)]
    pub has_work: Option<HasWork>,
    #[serde(default)]
    pub executor_overrides: Option<ExecutorOverrides>,
    #[serde(default)]
    pub effective_executor: Option<EffectiveExecutor>,
    #[serde(default)]
    pub effective_context: Option<EffectiveContext>,
    #[serde(default)]
    pub last_run: Option<TicketRun>,
    #[serde(default)]
    pub start: Option<TicketStart>,
    #[serde(default)]
    pub branch: Option<String>,
    #[serde(default)]
    pub base: Option<String>,
    #[serde(default)]
    pub skill_ids: Vec<String>,
    #[serde(default = "default_skills_mode")]
    pub skills_mode: String,
    #[serde(default)]
    pub include_skills_context: Option<bool>,
    #[serde(default)]
    pub images: Vec<String>,
    #[serde(default)]
    pub weight: Option<i32>,
    #[serde(default)]
    pub context_limit: Option<i32>,
    #[serde(default)]
    pub effective_context_limit: Option<EffectiveContextLimit>,
    #[serde(default)]
    pub effort: Option<String>,
    #[serde(default)]
    pub effective_effort: Option<EffectiveEffort>,
    #[serde(default)]
    pub mcp: Option<TicketMcp>,
}

fn default_skills_mode() -> String {
    skills_mode::MERGE.to_string()
}

/// `ticket_save`: create (no id) or edit. A null field keeps the stored value.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TicketSave {
    #[serde(default)]
    pub id: Option<String>,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
    #[serde(default)]
    pub ticket_type: Option<String>,
    #[serde(default)]
    pub project_id: Option<String>,
    #[serde(default)]
    pub epic_id: Option<String>,
    #[serde(default)]
    pub agent_id: Option<String>,
    #[serde(default)]
    pub provider: Option<String>,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub use_worktree: Option<bool>,
    #[serde(default)]
    pub worktree_branch: Option<String>,
    #[serde(default)]
    pub include_linked_tasks_context: Option<bool>,
    #[serde(default)]
    pub skill_ids: Option<Vec<String>>,
    #[serde(default)]
    pub skills_mode: Option<String>,
    #[serde(default)]
    pub include_skills_context: Option<bool>,
    #[serde(default)]
    pub images: Option<Vec<String>>,
    #[serde(default)]
    pub weight: Option<i32>,
    #[serde(default)]
    pub context_limit: Option<i32>,
    #[serde(default)]
    pub effort: Option<String>,
    #[serde(default)]
    pub runner: Option<String>,
    #[serde(default)]
    pub mcp: Option<TicketMcp>,
    #[serde(default)]
    pub status: Option<String>,
}

/// `ticket_move`: column (None = stay), place (None = bottom of a new column / stay), won't-fix.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TicketMove {
    pub id: String,
    #[serde(default)]
    pub status: Option<String>,
    #[serde(default)]
    pub position: Option<i32>,
    #[serde(default)]
    pub wont_fix: Option<bool>,
}

/// `ticket_list`'s filters. A blank field is no filter.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TicketListQuery {
    #[serde(default)]
    pub project_id: Option<String>,
    #[serde(default)]
    pub epic_id: Option<String>,
}

impl TicketListQuery {
    /// The frame data as a query: a JSON object when it starts with `{`, otherwise a bare project id.
    pub fn decode(text: &str) -> Result<TicketListQuery, String> {
        let trimmed = text.trim();
        let raw = if trimmed.starts_with('{') {
            decode_strict::<TicketListQuery>(trimmed, "ticket_list payload")?
        } else {
            TicketListQuery { project_id: Some(trimmed.to_string()), epic_id: None }
        };
        let clean = |v: Option<String>| v.map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
        Ok(TicketListQuery { project_id: clean(raw.project_id), epic_id: clean(raw.epic_id) })
    }
}

/// `ticket_assign`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TicketAssign {
    pub id: String,
    pub agent_id: String,
    #[serde(default)]
    pub queue: bool,
    #[serde(default)]
    pub at: Option<i32>,
}

/// `ticket_unassign`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TicketUnassign {
    pub id: String,
    #[serde(default)]
    pub now: bool,
    #[serde(default)]
    pub note: Option<String>,
}

/// `agent_switch` (AGENT-TASKS-4): `agent_id` leaves its in-progress ticket (handoff, back to todo) for `ticket_id`; `now`
/// ends its worker at once instead of after its turn.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AgentSwitch {
    pub agent_id: String,
    pub ticket_id: String,
    #[serde(default)]
    pub now: bool,
}

/// `ticket_link` / `ticket_unlink`: `type` is any [`link_type::ALL`] name, stored in one direction.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TicketLinkRequest {
    pub ticket_id: String,
    pub related_id: String,
    pub r#type: String,
}

/// `ticket_reorder`: `ids` go to the top of their columns in that order; with `status`, all must be in it.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct TicketReorder {
    #[serde(default)]
    pub status: Option<String>,
    pub ids: Vec<String>,
}

/// One epic of one project (`epic` frame). `story_ids`: the ids of the tickets filed under it, in board order.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Epic {
    pub id: String,
    pub project_id: String,
    pub name: String,
    #[serde(default)]
    pub goal: String,
    #[serde(default)]
    pub story_ids: Vec<String>,
    /// Empty = none set (the project's base applies).
    #[serde(default)]
    pub base_branch: String,
}

/// `epic_save`: create an epic in a project.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EpicSave {
    pub project_id: String,
    pub name: String,
    #[serde(default)]
    pub goal: String,
}

/// `epic_base`: `branch` sets, `clear` removes, neither reads.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct EpicBaseRequest {
    pub epic_id: String,
    #[serde(default)]
    pub branch: Option<String>,
    #[serde(default)]
    pub clear: bool,
}

/// Who wrote a comment: `owner`, `daemon`, or `session` (with its session and agent).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CommentAuthor {
    pub kind: String,
    #[serde(default)]
    pub session_id: Option<String>,
    #[serde(default)]
    pub agent_id: Option<String>,
}

impl CommentAuthor {
    pub const OWNER: &'static str = "owner";
    pub const DAEMON: &'static str = "daemon";
    pub const SESSION: &'static str = "session";

    pub fn owner() -> CommentAuthor {
        CommentAuthor { kind: Self::OWNER.into(), session_id: None, agent_id: None }
    }

    pub fn daemon() -> CommentAuthor {
        CommentAuthor { kind: Self::DAEMON.into(), session_id: None, agent_id: None }
    }

    /// `owner`, `daemon`, or `<agent> (<last 8 of the session>)`.
    pub fn label(&self) -> String {
        match self.kind.as_str() {
            Self::OWNER => "owner".into(),
            Self::DAEMON => "daemon".into(),
            _ => {
                let agent = self.agent_id.clone().unwrap_or_else(|| "agent".into());
                match &self.session_id {
                    Some(s) => {
                        let tail: String = s.chars().rev().take(8).collect::<Vec<_>>().into_iter().rev().collect();
                        format!("{agent} ({tail})")
                    }
                    None => agent,
                }
            }
        }
    }
}

/// One ticket comment.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Comment {
    pub id: String,
    pub ticket_id: String,
    pub author: CommentAuthor,
    /// `done` | `issue` | `note` | `handoff`.
    pub kind: String,
    pub body: String,
    /// `open` | `resolved`.
    pub state: String,
    pub created_at: i64,
    #[serde(default)]
    pub resolved_at: Option<i64>,
    #[serde(default)]
    pub resolved_by: Option<String>,
    /// Notified agents that had no live session to wake.
    #[serde(default)]
    pub undelivered: Vec<String>,
}

/// Add a comment. `ticket_id`: full id or its 8-char suffix.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CommentAdd {
    pub ticket_id: String,
    pub body: String,
    #[serde(default)]
    pub kind: Option<String>,
    #[serde(default)]
    pub state: Option<String>,
    pub author: CommentAuthor,
}

/// Read a ticket's comments: `which` = latestOpen (default) | latest | first | all.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct CommentQuery {
    pub ticket_id: String,
    #[serde(default)]
    pub which: Option<String>,
    #[serde(default)]
    pub kind: Option<String>,
    #[serde(default)]
    pub limit: Option<i32>,
}

/// Replace (`set`), add to and remove from a ticket's notify list (registry agent ids).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct NotifyEdit {
    pub ticket_id: String,
    #[serde(default)]
    pub set: Option<Vec<String>>,
    #[serde(default)]
    pub add: Vec<String>,
    #[serde(default)]
    pub remove: Vec<String>,
}

/// Strict decode (unknown keys refused): the error reads `malformed <what>: <why>`, Kotlin's prefix.
pub fn decode_strict<T: serde::de::DeserializeOwned>(text: &str, what: &str) -> Result<T, String> {
    serde_json::from_str(text).map_err(|e| format!("malformed {what}: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn list_query_forms() {
        assert_eq!(TicketListQuery::decode("").unwrap(), TicketListQuery::default());
        assert_eq!(TicketListQuery::decode(" p1 ").unwrap().project_id.as_deref(), Some("p1"));
        let q = TicketListQuery::decode(r#"{"epicId":" e ","projectId":""}"#).unwrap();
        assert_eq!((q.project_id, q.epic_id.as_deref()), (None, Some("e")));
        assert!(TicketListQuery::decode(r#"{"nope":1}"#).unwrap_err().starts_with("malformed ticket_list payload: "));
    }

    #[test]
    fn mcp_stored_form_is_compact() {
        let m = TicketMcp { require: vec![TicketMcpRequire { server: Some("gh".into()), capability: None, purpose: "prs".into() }] };
        assert_eq!(m.to_stored(), r#"{"require":[{"server":"gh","purpose":"prs"}]}"#);
        assert_eq!(TicketMcp::stored(Some(&m.to_stored())), Some(m));
        assert_eq!(TicketMcp::stored(Some("{}")), None);
    }

    #[test]
    fn save_refuses_unknown_keys() {
        assert!(decode_strict::<TicketSave>(r#"{"title":"x","bogus":1}"#, "ticket_save payload").is_err());
        let s: TicketSave = decode_strict(r#"{"title":"x","weight":null}"#, "ticket_save payload").unwrap();
        assert_eq!(s.title.as_deref(), Some("x"));
    }
}
