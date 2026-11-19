// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Agent mail: the model, `mailbox.json` storage, the mailbox, the router, turn rendering, the bg-job notifier,
//! the `/mail/*` HTTP routes and the mail frames.
pub mod bgjob;
pub mod http;
pub mod mailbox;
pub mod message;
pub mod render;
pub mod router;
pub mod store;
pub mod unit;
