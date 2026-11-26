// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The Infantry AI daemon, Rust port (RUST-DAEMON-1): the v1 WebSocket at `/ws` for the sessions frames, bearer auth as
//! Kotlin's `AccessPolicy`, the session store in the state dir, and providers behind the [`harness::Harness`] seam.
pub mod account;
pub mod build_env;
pub mod auth;
pub mod gateway;
pub mod board;
pub mod role_policy;
pub mod compose;
pub mod fresh_schema;
pub mod frame_log;
pub mod harness;
pub mod lender;
pub mod tunnel;
pub mod boot;
pub mod health;
pub mod mcp;
pub mod mode;
pub mod os_app;
pub mod os_update;
pub mod phone;
pub mod own_files;
pub mod pods;
pub mod held;
pub mod run;
pub mod server;
pub mod sessions;
pub mod listen;
pub mod state_lock;
pub mod store;
pub mod wire;
