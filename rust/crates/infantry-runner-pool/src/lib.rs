// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The paired remote runners of one daemon (RR-1): [`RunnerPool`] holds them (ONE `LinkStore` per state dir for the life
//! of the process, never reopened per call), a heartbeat keeps each one's [`RunnerState`], and [`AdminUnit`] answers the
//! owner-only `runner_admin` frame (`add`, `list`, `check`, `detach`). Plain `ws://` only; TLS is RR-3.
//! Secrets (the bearer, the pair credential) never reach a log, an answer or an error text.

mod admin;
mod pool;
mod scoped;
mod serve;

pub use scoped::{is_offered, Ask, FrameBackend, RunnerFrameUnit, RUNNER_FRAME, RUNNER_FRAMES};

pub use admin::{AdminUnit, CheckLend, CredentialSource, ADMIN_FRAME};
pub use serve::{advertise_hosts, RunnerServe, RunnerSideAdmin, ServeOptions, ADVERTISE_KEY, DEFAULT_LINK_BIND, DEFAULT_LINK_PORT, LINK_BIND_KEY, LINK_PORT_KEY};
pub use pool::{RunnerPool, RunnerState, ALLOW_PLAIN_KEY, DEFAULT_HEARTBEAT_SEC, HEARTBEAT_KEY};
