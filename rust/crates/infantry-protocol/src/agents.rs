// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Agent profiles and swaps (`protocol/Agents.kt`).
use crate::dto;

dto! {
    /// `AgentProfile` (contract 1.4.0).
    pub struct AgentProfile {
        #[serde(default)] pub id: Option<String>,
        pub name: String,
        pub role: String,
        #[serde(default)] pub provider: Option<String>,
        #[serde(default)] pub model: Option<String>,
        #[serde(default)] pub instructions: Option<String>,
        #[serde(default)] pub project_id: Option<String>,
        #[serde(default)] pub effort: Option<String>,
        #[serde(default)] pub trait_ids: Option<Vec<String>>,
        #[serde(default)] pub skill_ids: Option<Vec<String>>,
        #[serde(default)] pub runner: Option<String>,
    }
}
dto! { pub struct RosterRequest { #[serde(default)] pub project_id: Option<String> } }
dto! { pub struct AgentListRequest { #[serde(default)] pub project_id: Option<String> } }

fn auto() -> String {
    "auto".into()
}
dto! {
    pub struct AgentSwap {
        pub agent_id: String,
        pub provider: String,
        #[serde(default)] pub model: Option<String>,
        #[serde(default)] pub effort: Option<String>,
        #[serde(default = "auto")] pub session: String,
    }
}
impl AgentSwap {
    pub const AUTO: &'static str = "auto";
    pub const KEEP: &'static str = "keep";
    pub const NEW: &'static str = "new";
    pub const SESSION_MODES: [&'static str; 3] = ["auto", "keep", "new"];
}
dto! {
    pub struct SwapExecutor {
        #[serde(default)] pub provider: Option<String>,
        #[serde(default)] pub model: Option<String>,
        #[serde(default)] pub effort: Option<String>,
    }
}
dto! {
    pub struct SwapSession {
        pub outcome: String,
        #[serde(default)] pub session_id: Option<String>,
    }
}
impl SwapSession {
    pub const KEPT: &'static str = "kept";
    pub const NEW: &'static str = "new";
    pub const NONE: &'static str = "none";
}
dto! { pub struct AgentSwapped { pub agent: AgentProfile, pub from: SwapExecutor, pub session: SwapSession } }
dto! {
    pub struct SessionSwap {
        pub session_id: String,
        pub provider: String,
        #[serde(default)] pub model: Option<String>,
        #[serde(default)] pub effort: Option<String>,
        #[serde(default)] pub role: Option<String>,
    }
}
dto! {
    pub struct SessionSwapped {
        pub session_id: String,
        pub provider: String,
        #[serde(default)] pub model: Option<String>,
        #[serde(default)] pub default_model: Option<String>,
        pub from: SwapExecutor,
        pub new_conversation: bool,
        #[serde(default)] pub role: Option<String>,
        #[serde(default)] pub from_role: Option<String>,
    }
}
