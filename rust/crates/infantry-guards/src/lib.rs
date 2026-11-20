// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Architecture guards for the Rust workspace (docs/rust-port/LAYERS.md).
//!
//! Test-only: nothing links this crate. `cargo test -p infantry-guards` runs
//! `cargo metadata`, maps every workspace crate to a layer ([`layers`]) and fails on any
//! edge the rules forbid ([`check`]). It also holds the licence line ([`licences`]) and the inventory of what core
//! still names of the features that leave it ([`features`], DECOUPLE-1).
pub mod check;
pub mod features;
pub mod layers;
pub mod licences;
