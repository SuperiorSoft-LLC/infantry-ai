// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The Infantry AI daemon, Rust port (RUST-DAEMON-1): the v1 WebSocket at `/ws` for the sessions frames, bearer auth as
//! Kotlin's `AccessPolicy`, the session store in the state dir, and providers behind the [`harness::Harness`] seam.
pub mod auth;
pub mod board;
pub mod harness;
pub mod held;
pub mod server;
pub mod sessions;
pub mod store;
pub mod wire;
