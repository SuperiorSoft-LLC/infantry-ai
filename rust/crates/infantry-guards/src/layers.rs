// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The layer table. Keep it in step with docs/rust-port/LAYERS.md.
//!
//! A new workspace crate must be added here (or live under `plugins/official/`, which maps to
//! [`Layer::Plugin`] automatically); an unknown crate fails the guard.

/// Dependencies point DOWN only: a crate in layer N may depend on layers below N.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum Layer {
    /// L0 wire contract.
    Wire,
    /// L1 platform core.
    Core,
    /// L2 runner.
    Runner,
    /// L3 agents / harnesses.
    Harness,
    /// L4 orchestration.
    Orchestration,
    /// A plugin: depends only on plugin-wit and plugin-sdk; nothing but a top crate depends on it.
    Plugin,
    /// A composition root: may depend on anything.
    Top,
    /// A test/tool crate with an explicit dependency list ([`TOOLING_DEPS`]).
    Tooling,
}

impl Layer {
    /// The ordered rank for the down-only rule; `None` for the layers with their own rule.
    pub fn rank(self) -> Option<u8> {
        match self {
            Layer::Wire => Some(0),
            Layer::Core => Some(1),
            Layer::Runner => Some(2),
            Layer::Harness => Some(3),
            Layer::Orchestration => Some(4),
            Layer::Plugin | Layer::Top | Layer::Tooling => None,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Layer::Wire => "L0 wire",
            Layer::Core => "L1 core",
            Layer::Runner => "L2 runner",
            Layer::Harness => "L3 harness",
            Layer::Orchestration => "L4 orchestration",
            Layer::Plugin => "plugin",
            Layer::Top => "top",
            Layer::Tooling => "tooling",
        }
    }
}

/// `(crate, layer, tier)`. Inside one layer a crate may depend only on a crate with a LOWER
/// tier (e.g. runner-local on runner-api); equal tiers in one layer may not depend on each other.
pub const LAYERS: &[(&str, Layer, u8)] = &[
    ("infantry-protocol", Layer::Wire, 0),
    ("infantry-core", Layer::Core, 0),
    ("infantry-plugin-wit", Layer::Core, 0),
    ("infantry-plugin-host", Layer::Core, 1),
    ("infantry-plugin-sdk", Layer::Core, 1),
    ("infantry-runner-api", Layer::Runner, 0),
    ("infantry-runner", Layer::Runner, 1),
    ("infantry-runner-local", Layer::Runner, 2),
    ("infantry-runner-link", Layer::Runner, 2),
    ("infantry-harness", Layer::Harness, 0),
    ("infantry-harness-codex", Layer::Harness, 1),
    ("infantry-orchestrator-api", Layer::Orchestration, 0),
    ("infantry-board-daemon", Layer::Plugin, 0),
    ("infantry-news-daemon", Layer::Plugin, 0),
    ("infantry-health-daemon", Layer::Plugin, 0),
    ("infantry-daemon", Layer::Top, 0),
    ("infantry-cli", Layer::Top, 0),
    ("infantry-migrate", Layer::Top, 0),
    // app/src-tauri is its own cargo workspace today; registered so it is known if it joins.
    ("infantry-tauri", Layer::Top, 0),
    ("infantry-conformance", Layer::Tooling, 0),
    ("infantry-guards", Layer::Tooling, 0),
];

/// The only workspace crates a plugin may depend on.
pub const PLUGIN_DEPS: &[&str] = &["infantry-plugin-wit", "infantry-plugin-sdk"];

/// Tooling crates and the workspace crates each may depend on.
pub const TOOLING_DEPS: &[(&str, &[&str])] = &[
    // conformance is black-box: it speaks the wire, nothing else.
    ("infantry-conformance", &["infantry-protocol"]),
    ("infantry-guards", &[]),
];

/// Harness drivers by folder name: such a module inside a crate below L3 is misplaced code.
pub const HARNESS_MODULE_NAMES: &[&str] = &["claude", "codex", "opencode", "gemini", "deepseek"];

/// Known violations, each with the reason it is tolerated: `(crate, path inside the crate, reason)`.
pub const ALLOWLIST: &[(&str, &str, &str)] = &[
    // The Claude driver is harness code (L3) living in the runner (L2). It predates the layer
    // map; a follow-up moves it into infantry-harness (or its own infantry-harness-claude).
    ("infantry-runner", "src/claude", "Claude driver is harness code in the runner layer; a follow-up moves it to L3"),
];

/// The layer and tier of a crate, by name, then by location (`plugins/official/*` = plugin).
/// `None` = unknown crate.
pub fn classify(name: &str, manifest_path: &str) -> Option<(Layer, u8)> {
    if let Some(&(_, layer, tier)) = LAYERS.iter().find(|(n, _, _)| *n == name) {
        return Some((layer, tier));
    }
    let p = manifest_path.replace('\\', "/");
    if p.contains("/plugins/official/") {
        return Some((Layer::Plugin, 0));
    }
    if p.ends_with("/app/src-tauri/Cargo.toml") {
        return Some((Layer::Top, 0));
    }
    None
}
