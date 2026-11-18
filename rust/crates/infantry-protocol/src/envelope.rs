// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
use serde::{Deserialize, Serialize};
use ts_rs::TS;

/// One WebSocket frame, in both directions. `data` is a string that often holds JSON (double encoding).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, TS)]
#[serde(rename_all = "camelCase")]
#[ts(export)]
pub struct Envelope {
    #[serde(rename = "type")]
    #[ts(rename = "type")]
    pub r#type: String,
    #[serde(default)]
    pub data: String,
    /// Written out as `null` when absent.
    #[serde(default)]
    pub session_id: Option<String>,
    /// Per-session sequence (F2); written out as `null` when absent.
    #[serde(default)]
    pub sequence: Option<i64>,
    /// Contract 1.87.0 correlation id; omitted when null.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_id: Option<String>,
    /// Contract 1.125.0: who a `turn` frame came from; omitted when null.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub origin: Option<MessageOrigin>,
}

impl Envelope {
    pub fn new(r#type: impl Into<String>, data: impl Into<String>) -> Self {
        Envelope { r#type: r#type.into(), data: data.into(), session_id: None, sequence: None, request_id: None, origin: None }
    }
}

/// Who a message came from. The `type` discriminator is the serial name.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize, TS)]
#[serde(tag = "type", rename_all = "lowercase", rename_all_fields = "camelCase")]
#[ts(export)]
pub enum MessageOrigin {
    Owner,
    Agent {
        session_id: String,
        #[serde(default)]
        agent_id: Option<String>,
        #[serde(default)]
        role: Option<String>,
    },
    External {
        sender_label: String,
        #[serde(default)]
        channel: Option<String>,
    },
    System { kind: String },
}

impl MessageOrigin {
    /// `MessageOrigin.label()`.
    pub fn label(&self) -> String {
        let who = match self {
            MessageOrigin::Owner => "owner".to_string(),
            MessageOrigin::Agent { session_id, agent_id, role } => {
                let id: String = agent_id.as_deref().unwrap_or(session_id).chars().take(8).collect();
                match role {
                    Some(r) if !r.trim().is_empty() => format!("{r} ({id})"),
                    _ => id,
                }
            }
            MessageOrigin::External { sender_label, .. } => sender_label.clone(),
            MessageOrigin::System { kind } => format!("system ({kind})"),
        };
        format!("from {who}")
    }
}

/// `SystemKind` constants.
pub mod system_kind {
    pub const HANDOVER: &str = "handover";
    pub const SLASH_COMMAND: &str = "slash_command";
    pub const SCHEDULER: &str = "scheduler";
    pub const RESTART: &str = "restart";
}
