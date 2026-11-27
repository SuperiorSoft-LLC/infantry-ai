// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! One integration-test binary for infantry-daemon: each module is one former `tests/<name>.rs` file, so the crate links once.

mod agent_frames;
mod agent_owner_token;
mod agent_tool_allow_list;
mod assistant;
mod background_tasks;
mod context_limit;
mod fresh_state;
mod gateway;
mod hung_child;
mod migrated_state;
mod owner_first;
mod queue_ack;
mod runner_setting_read;
mod session_display_name;
mod session_remove;
mod slow_frame;
mod sessions_suite;
mod spawn_load;
mod stats_consistency;
mod tunnel_token;
mod waiting_flag;
