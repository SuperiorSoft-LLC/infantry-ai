// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Usage, limits and pacing (layer L1, platform level).
//!
//! - [`limits`]: each provider's plan meter read live from its own usage endpoint (Anthropic OAuth, OpenCode Go), with
//!   per-source backoff and the last good reading kept while it is young ([`limits::LimitService`]).
//! - [`reading_cache`]: the last good reading and the backoff of each source, shared on disk by the processes of one
//!   operator (they read the same token, which the provider rate-limits).
//! - [`refresher`]: re-reads every live meter on its own, backing off on failure.
//! - [`pacing`]: the pacing judge (pace or bands), the usage report, the start verdict and the shared text.
//! - [`config`]: the owner's pacing rule, typed percents and overrides ("allowance raised to no limit, ends in ...").
//! - [`tracker`]: provider limit state seen on the stream (quota hits, stalls) and the operator budget.
//! - [`stream`] + [`meter`]: provider-reported tokens per request and per run, kept in `usage-meter.db`.
//! - [`pace`]: the Pace history and forecast (momentum, landing at reset).
//! - [`governor`]: the per-meter "workers allowed" answer the usage-governor Lua script reads.
//! - [`service`] + [`unit`]: everything wired together and exposed as one [`infantry_core::frames::FrameUnit`].
//!
//! SECURITY: a credential is read from the operator's local files only when a source runs, sent only as the
//! Authorization header of its own provider's endpoint, then dropped. It is never logged, stored, put on the wire or
//! put in a reason (a reason is a fixed code).
pub mod config;
pub mod credentials;
pub mod fit;
pub mod governor;
pub mod limits;
pub mod meter;
pub mod pace;
pub mod pacing;
pub mod reading_cache;
pub mod refresher;
pub mod service;
pub mod stream;
pub mod tracker;
pub mod unit;
pub mod util;

pub use service::UsageService;
pub use unit::UsageUnit;
