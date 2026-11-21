// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! One integration-test binary for infantry-daemon: each module is one former `tests/<name>.rs` file, so the crate links once.

mod agent_frames;
mod assistant;
mod fresh_state;
mod gateway;
mod hung_child;
mod migrated_state;
mod queue_ack;
mod session_remove;
mod sessions_suite;
mod spawn_load;
mod stats_consistency;
mod waiting_flag;
