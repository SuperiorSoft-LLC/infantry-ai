// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! `hook-channel` as a harness sees it: an [`HookEndpoint`] (a loopback URL for blocking hook calls and an events file for
//! command hooks, both on the runner's host) and the pure helpers to write and read the events file. No vendor names.

use tokio::sync::{mpsc, oneshot};

/// One blocking hook call (a pre-tool or permission hook POSTed its JSON). Answer it with [`HookCall::reply`]; dropping it
/// unanswered answers `{}` (no decision).
#[derive(Debug)]
pub struct HookCall {
    /// The request body (JSON) the hook sent.
    pub body: String,
    reply: oneshot::Sender<String>,
}

impl HookCall {
    pub fn new(body: String, reply: oneshot::Sender<String>) -> Self {
        HookCall { body, reply }
    }

    /// Sends the response body (JSON) back to the waiting hook.
    pub fn reply(self, body: impl Into<String>) {
        let _ = self.reply.send(body.into());
    }
}

/// A fresh hook channel on the runner's host. Dropping it unregisters the URL.
pub struct HookEndpoint {
    /// The channel's token (also the last path segment of [`HookEndpoint::url`]).
    pub token: String,
    /// `http://127.0.0.1:<port>/hook/<token>` on the runner's host: where a blocking hook POSTs its JSON.
    pub url: String,
    /// A file on the runner's host a command hook appends one event word per line to ([`event_command`]); follow it with
    /// [`crate::Runner::tail`] and read each line with [`event_of`]. It does not exist until the first event.
    pub events_file: String,
    /// The blocking hook calls, in arrival order.
    pub calls: mpsc::UnboundedReceiver<HookCall>,
    _guard: Box<dyn Send + Sync>,
}

impl HookEndpoint {
    /// For runner implementations: [guard] is dropped with the endpoint (it unregisters the token).
    pub fn new(
        token: String,
        url: String,
        events_file: String,
        calls: mpsc::UnboundedReceiver<HookCall>,
        guard: Box<dyn Send + Sync>,
    ) -> Self {
        HookEndpoint { token, url, events_file, calls, _guard: guard }
    }

    /// The shell command of a command hook that appends [event] to this endpoint's events file.
    pub fn event_command(&self, event: &str) -> String {
        event_command(event, &self.events_file)
    }
}

impl std::fmt::Debug for HookEndpoint {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("HookEndpoint").field("url", &self.url).field("events_file", &self.events_file).finish_non_exhaustive()
    }
}

/// `echo <event> >> "<file>"`: the same meaning in bash, sh, PowerShell 5.1, pwsh and cmd. The spaces around `>>` matter
/// (PowerShell reads `echo stop>>"f"` as one word); forward slashes so no shell reads a backslash as an escape.
pub fn event_command(event: &str, file: &str) -> String {
    format!("echo {} >> \"{}\"", event, file.replace('\\', "/"))
}

/// The event word of one events-file line: ASCII letters only, lower-cased (UTF-16 NULs, a BOM, spaces and CR go).
pub fn event_of(line: &str) -> String {
    line.chars().filter(|c| c.is_ascii_alphabetic()).map(|c| c.to_ascii_lowercase()).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn event_command_and_event_of_round_trip() {
        assert_eq!(event_command("stop", r"C:\t\e.txt"), "echo stop >> \"C:/t/e.txt\"");
        assert_eq!(event_of("\u{feff}S\0t\0o\0p\0 \r"), "stop");
    }
}
