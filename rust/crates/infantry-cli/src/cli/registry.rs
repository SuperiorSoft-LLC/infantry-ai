// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The native commands. One line per command; a command missing here goes to the JVM launcher.

use super::{cmd_board, cmd_ticket, Command, Needs, RawCommand};

/// Commands that take their raw words (they never meet the global flag parser).
pub static RAW: &[RawCommand] = &[
    // -- raw commands --
];

pub static COMMANDS: &[&Command] = &[
    // -- board / tickets --
    &Command { name: "board", needs: Needs::Stream, validate: cmd_board::validate_board, run: cmd_board::run_board },
    &Command { name: "tickets", needs: Needs::Stream, validate: cmd_board::validate_tickets, run: cmd_board::run_tickets },
    &Command { name: "ticket", needs: Needs::Stream, validate: cmd_ticket::validate, run: cmd_ticket::run },
    // -- mail / sessions --
    // -- health / usage / settings / scripts / secrets / run / install --
    // -- runner --
];

pub fn find(name: &str) -> Option<&'static Command> {
    COMMANDS.iter().copied().find(|c| c.name == name)
}
