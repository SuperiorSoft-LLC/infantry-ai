// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `Project` and `RoutingRule` (Projects.kt): the `projects` / `project` frame payloads.
use crate::dto;
use std::collections::BTreeMap;

dto! {
    /// One routing rule of a project (ROUTING-1), in the project's order.
    pub struct RoutingRule {
        pub name: String,
        #[serde(default)] pub weights: Vec<i32>,
        #[serde(default)] pub ticket_types: Vec<String>,
        #[serde(default)] pub role: Option<String>,
        pub provider: String,
        pub model: String,
        #[serde(default)] pub effort: Option<String>,
        #[serde(default)] pub set_role: Option<String>,
        #[serde(default)] pub runner: Option<String>,
    }
}

dto! {
    /// A project. Every field after `name` has the default an older daemon's missing key reads as.
    pub struct Project {
        pub id: String,
        pub name: String,
        #[serde(default)] pub goal: String,
        #[serde(default)] pub directory: String,
        #[serde(default)] pub base_branch: String,
        #[serde(default)] pub lane_caps: BTreeMap<String, i32>,
        #[serde(default)] pub auto_remove_done_workers: bool,
        #[serde(default)] pub routing_rules: Vec<RoutingRule>,
        #[serde(default)] pub experiments: BTreeMap<String, String>,
        #[serde(default)] pub data: BTreeMap<String, String>,
    }
}
