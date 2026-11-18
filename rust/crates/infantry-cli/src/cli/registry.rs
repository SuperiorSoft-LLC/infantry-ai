// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The native commands. One line per command; a command missing here goes to the JVM launcher.

use super::{cmd_board, cmd_ticket, Command, Needs, RawCommand};

/// Commands that take their raw words (they never meet the global flag parser).
pub static RAW: &[RawCommand] = &[
    // -- raw commands --
    super::cmd_mail::WAITING_FLAG,
];

pub static COMMANDS: &[&Command] = &[
    // -- board / tickets --
    &Command { name: "board", needs: Needs::Stream, validate: cmd_board::validate_board, run: cmd_board::run_board },
    &Command { name: "tickets", needs: Needs::Stream, validate: cmd_board::validate_tickets, run: cmd_board::run_tickets },
    &Command { name: "ticket", needs: Needs::Stream, validate: cmd_ticket::validate, run: cmd_ticket::run },
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
    &super::cmd_runner::RUNNER,
];

pub fn find(name: &str) -> Option<&'static Command> {
    COMMANDS.iter().copied().find(|c| c.name == name)
}
