// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The native commands. One line per command; a command missing here goes to the JVM launcher.

use super::{cmd_board, cmd_ticket, Command, Needs, RawCommand};

/// Commands that take their raw words (they never meet the global flag parser).
pub static RAW: &[RawCommand] = &[
    // -- raw commands --
    super::cmd_mail::WAITING_FLAG,
    super::cmd_app::APP,
    RawCommand { matches: super::progress::is_progress, run: super::progress::progress },
    RawCommand { matches: is_secret_run, run: super::run_secret::run },
    RawCommand { matches: super::progress::is_run, run: super::progress::run },
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
    &super::cmd_prompt::PROMPT,
    &super::cmd_ask::ASK,
    // -- health / usage / settings / scripts / secrets / run / install --
    &super::tools_cmds::SCRIPT,
    &super::tools_cmds::SECRET,
    &super::tools_cmds::RUN_CMD,
    &super::usage_settings::USAGE,
    &super::usage_settings::SETTINGS,
    &super::health::HEALTH,
    &super::cmd_worktrees::WORKTREES,
    &super::cmd_plugin::PLUGIN,
    &super::cmd_project::PROJECT,
    &super::local_cmds::INSTALL_CLI,
    &super::local_cmds::UNINSTALL_CLI,
    &super::local_cmds::REMOTE,
    &super::issue::ISSUE,
    &super::cmd_harness::HARNESS_STATUS,
    &super::cmd_harness::HARNESS_INSTALL,
    // -- pods / runs / work (PARITY-AUDIT-1 #21) --
    &super::cmd_pods::SUBMIT,
    &super::cmd_pods::WATCH,
    &super::cmd_pods::PODS,
    &super::cmd_pods::RUNS,
    &super::cmd_pods::LOGS,
    &super::cmd_pods::CANCEL,
    &super::cmd_work::EPICS,
    &super::cmd_work::PROCESSES,
    &super::cmd_work::HARNESSES,
    &super::cmd_work::ARCHIVE,
    &super::cmd_work::AGENT,
    // -- knowledge / admin (PARITY-AUDIT-1 #22) --
    &super::cmd_knowledge::EVENT,
    &super::cmd_knowledge::CARD,
    &super::cmd_knowledge::DOCS,
    &super::cmd_knowledge::MCP,
    &super::cmd_knowledge::ROLE,
    &super::cmd_knowledge::SKILL,
    &super::cmd_knowledge::MEMORY,
    &super::cmd_knowledge::NOTIFY,
    // -- runner --
    &super::cmd_runner::RUNNER,
];

/// SECRETS-3: `run --secret ...` runs in the daemon; plain `run` stays local.
fn is_secret_run(a: &[String]) -> bool {
    super::progress::is_run(a) && super::run_secret::wants_secret(a)
}

pub fn find(name: &str) -> Option<&'static Command> {
    COMMANDS.iter().copied().find(|c| c.name == name)
}

/// [find], for these words: a command only partly native leaves its other forms to the launcher.
pub fn find_for(name: &str, p: &super::args::Parsed) -> Option<&'static Command> {
    find(name).filter(|_| match name {
        "ask" => super::cmd_ask::is_native(p),
        "agent" => super::cmd_work::is_native(p),
        "event" | "card" | "docs" | "mcp" | "role" | "skill" | "memory" | "notify" => super::cmd_knowledge::is_native(name, p),
        _ => true,
    })
}
