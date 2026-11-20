//! One integration-test binary for infantry-daemon: each module is one former `tests/<name>.rs` file, so the crate links once.

mod agent_frames;
mod fresh_state;
mod gateway;
mod hung_child;
mod migrated_state;
mod queue_ack;
mod sessions_suite;
mod spawn_load;
mod waiting_flag;
