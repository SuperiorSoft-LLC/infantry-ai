// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! One integration-test binary for infantry-board: each module is one former `tests/<name>.rs` file, so the crate links once.

mod common;
mod archive;
mod board;
mod features;
mod kotlin_xcheck;
mod schema_vs_kotlin;
mod work;
