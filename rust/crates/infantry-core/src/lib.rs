// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry AI platform core (layer L1, see docs/rust-port/LAYERS.md).
//!
//! Generic mechanisms only: the [`frames::FrameUnit`] contract and its [`frames::Registry`],
//! the [`bus::EventBus`], the [`db::Db`] handle and the read-only [`settings::Settings`].
//! This crate never names a plugin id; infantry-guards enforces that.
pub mod bus;
pub mod db;
pub mod frames;
pub mod settings;

pub use infantry_protocol::envelope::Envelope;
