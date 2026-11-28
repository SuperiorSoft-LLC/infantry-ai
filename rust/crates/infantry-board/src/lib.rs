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
//! The work-running half ([`dispatch::Dispatcher`]): assign / unassign, agent queues, starts (`ticket_start`,
//! `ticket_start_next`, a move into in_progress), lane caps, routing rules, the start brief ([`brief`]) and the done
//! protocol. Worktrees and workers come through the orchestrator-api `WorkspaceProvider` and `SessionLauncher` traits;
//! hook points are named events on the bus (`infantry_orchestrator_api::hooks`).
pub mod archive;
pub mod board;
pub mod brief;
pub mod comments;
pub mod dispatch;
pub mod env;
pub mod epics;
pub mod owner_mail;
pub mod local;
pub mod rules;
pub mod schema;
pub mod store;
pub mod ticket_files;
pub mod tools;
pub mod unit;
pub mod views;

pub use board::Board;
pub use dispatch::Dispatcher;
pub use env::{BoardEnv, StaticEnv};
pub use local::LocalOrchestrator;
pub use unit::BoardUnit;
