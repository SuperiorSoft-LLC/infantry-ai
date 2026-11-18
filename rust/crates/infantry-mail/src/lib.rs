// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry AI mail and notifications (layer L1 platform, RUST-MAIL-1).
//!
//! [`mail`]: the mailbox (`mailbox.json`, same bytes as the Kotlin daemon), the router (addressing, wake policy,
//! delivery into turns), the bg-job notifier and the `/mail/*` HTTP routes. [`notify`]: the notification bus with
//! presenters, attention, owner questions, pending prompts with approval rules, and device notify with sounds.
//!
//! The daemon wires sessions in through [`mail::router::SessionDirectory`] / [`mail::router::TurnSink`] and
//! [`mail::bgjob::BgHost`], registers the [`FrameUnit`](infantry_core::frames::FrameUnit)s in [`units`], merges
//! [`mail::http::routes`] into its axum router and calls the `tick`/`scanned`/`sweep` methods on its scheduler.
pub mod mail;
pub mod notify;

/// Every frame unit of this crate, for `Registry::register`.
pub mod units {
    pub use crate::mail::unit::MailUnit;
    pub use crate::notify::units::{AttentionUnit, NotificationsUnit, NotifyUnit, PromptsUnit, QuestionsUnit};
}
