// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Architecture guards for the Rust workspace (docs/rust-port/LAYERS.md).
//!
//! Test-only: nothing links this crate. `cargo test -p infantry-guards` runs
//! `cargo metadata`, maps every workspace crate to a layer ([`layers`]) and fails on any
//! edge the rules forbid ([`check`]).
pub mod check;
pub mod layers;
