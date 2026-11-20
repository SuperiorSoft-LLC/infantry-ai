//! One integration-test binary for infantry-board: each module is one former `tests/<name>.rs` file, so the crate links once.

mod common;
mod board;
mod features;
mod kotlin_xcheck;
mod schema_vs_kotlin;
mod work;
