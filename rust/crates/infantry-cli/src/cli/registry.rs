// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The native commands. One line per command; a command missing here goes to the JVM launcher.

use super::{Command, RawCommand};

/// Commands that take their raw words (they never meet the global flag parser).
pub static RAW: &[RawCommand] = &[
    // -- raw commands --
];

pub static COMMANDS: &[&Command] = &[
    // -- board / tickets --
    // -- mail / sessions --
    // -- health / usage / settings / scripts / secrets / run / install --
    // -- runner --
];

pub fn find(name: &str) -> Option<&'static Command> {
    COMMANDS.iter().copied().find(|c| c.name == name)
}
