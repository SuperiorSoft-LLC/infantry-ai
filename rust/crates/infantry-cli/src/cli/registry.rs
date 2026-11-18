// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The native commands. One line per command; a command missing here goes to the JVM launcher.

use super::{Command, RawCommand};

/// Commands that take their raw words (they never meet the global flag parser).
pub static RAW: &[RawCommand] = &[
    // -- raw commands --
    super::cmd_mail::WAITING_FLAG,
];

pub static COMMANDS: &[&Command] = &[
    // -- board / tickets --
    // -- mail / sessions --
    &super::cmd_mail::SEND,
    &super::cmd_mail::INBOX,
    &super::cmd_mail::READ,
    &super::cmd_mail::THREADS,
    &super::cmd_mail::THREAD,
    &super::cmd_mail::WAITING,
    &super::cmd_sessions::AGENTS,
    &super::cmd_sessions::SESSION,
    &super::cmd_sessions::KILL,
    // -- health / usage / settings / scripts / secrets / run / install --
    // -- runner --
];

pub fn find(name: &str) -> Option<&'static Command> {
    COMMANDS.iter().copied().find(|c| c.name == name)
}
