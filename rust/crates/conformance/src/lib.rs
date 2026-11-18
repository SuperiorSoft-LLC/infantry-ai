// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Black-box conformance for the Infantry v1 WebSocket wire (CONTRACT.md). A scenario drives ANY daemon URL; the
//! frames it sees are normalised (ids and times blanked), recorded as golden fixtures, and later diffed.
pub mod client;
pub mod fixture;
pub mod normalize;
pub mod scenarios;
