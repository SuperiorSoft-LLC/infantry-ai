// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry AI wire contract.
//!
//! Byte-compatible with the Kotlin `infantryProtocol` module (kotlinx.serialization with
//! `encodeDefaults = true`): declaration-order fields, nulls written out, `@EncodeDefault(NEVER)`
//! fields omitted when equal to their default, `requestId` / `origin` omitted when null.

pub mod agents;
pub mod envelope;
pub mod frames;
pub mod projects;
pub mod prompts;
pub mod session;
pub mod usage;

pub use envelope::{Envelope, MessageOrigin};

/// Declares a wire DTO: camelCase, Debug/Clone/PartialEq, serde and ts-rs export.
macro_rules! dto {
    ($(#[$m:meta])* $v:vis struct $n:ident { $($body:tt)* }) => {
        $(#[$m])*
        #[derive(Debug, Clone, PartialEq, serde::Serialize, serde::Deserialize, ts_rs::TS)]
        #[serde(rename_all = "camelCase")]
        #[ts(export)]
        $v struct $n { $($body)* }
    };
}
pub(crate) use dto;

/// serde helpers for `@EncodeDefault(NEVER)` fields.
pub(crate) mod skip {
    pub fn is_false(b: &bool) -> bool {
        !*b
    }
    pub fn is_local(s: &str) -> bool {
        s == "local"
    }
}
