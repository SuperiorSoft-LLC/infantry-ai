// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry AI board (layer L4, above infantry-orchestrator-api): tickets, lanes, links, reorder, epics and comments on
//! the SAME infantry.db schema the Kotlin daemon uses, the `ticket_*` / `epic_*` [`unit::BoardUnit`] and the in-process
//! [`local::LocalOrchestrator`].
//!
//! It lives in `rust/crates/` for now: a crate under `plugins/official/*` may depend only on the plugin
//! WIT/SDK, and the WIT storage SQL does not exist yet. Once it does this becomes the Infantry_Board
//! wasm plugin.
//!
//! Not here yet (they need runners and sessions): worktrees, ticket start / assign / queue / hand-out (`ticket_start_next`,
//! a move into in_progress), the done protocol, Lua hooks, `hasWork`, routing rules, lane caps, the RELATED description block.
pub mod board;
pub mod comments;
pub mod env;
pub mod epics;
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
