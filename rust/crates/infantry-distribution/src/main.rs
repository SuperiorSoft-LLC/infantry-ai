// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The `infantry-daemon` program. The whole daemon is `infantry_distribution::run_daemon` (see its docs for the
//! arguments and the environment); this program is that function behind a process.
fn main() {
    infantry_distribution::run_daemon(std::env::args().skip(1).collect());
}
