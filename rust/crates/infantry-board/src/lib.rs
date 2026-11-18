// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry AI board (layer L4, above infantry-orchestrator-api): tickets CRUD on the SAME infantry.db
//! schema the Kotlin daemon uses, its lane rules, the `ticket_*` [`unit::BoardUnit`] and the in-process
//! [`local::LocalOrchestrator`].
//!
//! It lives in `rust/crates/` for now: a crate under `plugins/official/*` may depend only on the plugin
//! WIT/SDK, and the WIT storage SQL does not exist yet. Once it does this becomes the Infantry_Board
//! wasm plugin.
//!
//! Not here yet: worktrees, ticket start / hand-out (`ticket_start_next`, a move into in_progress), the
//! done protocol, Lua hooks, links/reorder frames, `hasWork`, routing rules, the RELATED description block.
pub mod board;
pub mod env;
pub mod local;
pub mod rules;
pub mod schema;
pub mod store;
pub mod unit;
pub mod views;

pub use board::Board;
pub use env::{BoardEnv, StaticEnv};
pub use local::LocalOrchestrator;
pub use unit::BoardUnit;
