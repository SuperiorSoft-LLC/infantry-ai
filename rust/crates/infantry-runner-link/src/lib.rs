// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The runner link (RUST-LINK-1): the `runner_link_*` frames (contract 1.11.0) over a WebSocket, byte-compatible with the
//! Kotlin link, so a Rust orchestrator drives a Kotlin runner and the other way round.
//!
//! - [`wire`]: the frame names and bodies; [`transport`]: one call = one connection, one frame each way;
//! - [`pairing`] and [`store`]: one-time codes, the long-lived bearer, credentials;
//! - [`server::LinkServer`]: the runner end (the `infantry-runner-agent` bin serves it);
//! - [`client::LinkClient`]: the orchestrator end (pair, hello, health, ping).

pub mod client;
pub mod pairing;
pub mod process;
pub mod remote;
pub mod server;
pub mod store;
pub mod transport;
pub mod wire;

pub use client::{CallError, LinkClient, Ping};
pub use remote::RemoteRunner;
pub use server::{Backend, InfoOnly, LinkServer, RunnerInfo};
pub use store::{LinkStore, PairedRunner};
pub use transport::{serve, LinkFailure, LinkServing};
