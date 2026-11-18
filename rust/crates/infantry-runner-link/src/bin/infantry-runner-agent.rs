// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `infantry-runner-agent --port N [--host H] [--advertise H] [--state DIR] [--name NAME] [--rotate]`: a Rust runner
//! serving the runner link (pair, hello, health, check: ping/pong). See [`infantry_runner_link::agent`].

fn main() {
    infantry_runner_link::agent::run("infantry-runner-agent", std::env::args().skip(1), Box::new(infantry_runner_link::InfoOnly))
}
