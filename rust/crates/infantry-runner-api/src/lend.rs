// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! RR-6 credentials a remote start lends: the harness key, the project secrets and a git token.
//!
//! - [`LentCredentials`] rides on [`crate::ProcessSpec::lent`] (and on the link's start as `credentials`). It never prints
//!   a value: its `Debug` names only how many it holds.
//! - The runner applies it to the process it starts ([`LentCredentials::apply`]): variables into the process env, the git
//!   token ONLY as `http.extraHeader` through `GIT_CONFIG_COUNT/KEY_n/VALUE_n` (never argv, a file, the URL or a credential
//!   store). The values live in memory for that process only.
//! - [`Masker`] hides every lent value in the process output, also a value split across two chunks.
//! - [`LendingRunner`] wraps a runner so every spawn through it carries one set of lent credentials.

use crate::{KillScope, ProcHandle, ProcId, ProcessSpec, Runner, RunnerFuture, ScreenSnapshot, Size, TailSpec};
use base64::Engine;
use serde::{Deserialize, Serialize};
use std::sync::Arc;

/// What replaces a lent value in output.
pub const MASK: &str = "***";
/// A value shorter than this is not masked (masking a 1-character value would shred the output); lent values are keys and
/// tokens, far longer.
pub const MIN_MASKED_LEN: usize = 6;
/// The user name git sends with a token (GitHub's convention for an access token over https).
const GIT_USER: &str = "x-access-token";

/// One lent environment variable.
#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct LentVar {
    pub name: String,
    pub value: String,
    /// Set for a harness key: the harness it belongs to. A runner whose owner chose `harness.<id>.authMode=subscription-login`
    /// keeps its own login over this variable (it is not applied).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub harness: Option<String>,
}

#[derive(Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct LentCredentials {
    #[serde(default)]
    pub env: Vec<LentVar>,
    /// The git token (see the module doc for how it is applied).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub git_token: Option<String>,
}

impl std::fmt::Debug for LentCredentials {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "LentCredentials({} variables, git token {}, values redacted)", self.env.len(), if self.git_token.is_some() { "yes" } else { "no" })
    }
}

impl std::fmt::Debug for LentVar {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "LentVar({}, value redacted)", self.name)
    }
}

fn header_value(token: &str) -> String {
    format!("Authorization: Basic {}", base64::engine::general_purpose::STANDARD.encode(format!("{GIT_USER}:{token}")))
}

impl LentCredentials {
    pub fn is_empty(&self) -> bool {
        self.env.is_empty() && self.git_token.is_none()
    }

    /// Every string that must never reach a log or an output: the values, the token and the encoded header derived from it.
    pub fn secrets(&self) -> Vec<String> {
        let mut v: Vec<String> = self.env.iter().map(|e| e.value.clone()).collect();
        if let Some(t) = &self.git_token {
            v.push(t.clone());
            v.push(base64::engine::general_purpose::STANDARD.encode(format!("{GIT_USER}:{t}")));
        }
        v.retain(|s| s.len() >= MIN_MASKED_LEN);
        v
    }

    /// Puts the credentials in `spec`'s env. `keeps_own_login(harness)` is true when the runner's owner chose to keep that
    /// harness's own sign-in: its key is then not applied. Existing `GIT_CONFIG_COUNT` entries are kept (the token is added
    /// after them).
    pub fn apply(&self, spec: &mut ProcessSpec, keeps_own_login: &dyn Fn(&str) -> bool) {
        for e in &self.env {
            if e.harness.as_deref().is_some_and(keeps_own_login) {
                continue;
            }
            spec.env.retain(|(k, _)| k != &e.name);
            spec.env.push((e.name.clone(), e.value.clone()));
        }
        if let Some(t) = &self.git_token {
            let count = spec.env.iter().find(|(k, _)| k == "GIT_CONFIG_COUNT").and_then(|(_, v)| v.trim().parse::<usize>().ok()).unwrap_or(0);
            spec.env.retain(|(k, _)| k != "GIT_CONFIG_COUNT");
            spec.env.push((format!("GIT_CONFIG_KEY_{count}"), "http.extraHeader".into()));
            spec.env.push((format!("GIT_CONFIG_VALUE_{count}"), header_value(t)));
            spec.env.push(("GIT_CONFIG_COUNT".into(), (count + 1).to_string()));
        }
    }
}

/// Replaces lent values in a byte stream with [`MASK`], also a value that is split across two chunks (the tail of a chunk
/// that could be the start of a value is held back until the next chunk, or [`Masker::finish`]).
pub struct Masker {
    secrets: Vec<Vec<u8>>,
    carry: Vec<u8>,
}

impl Masker {
    pub fn new(secrets: impl IntoIterator<Item = String>) -> Self {
        let mut secrets: Vec<Vec<u8>> = secrets.into_iter().filter(|s| s.len() >= MIN_MASKED_LEN).map(String::into_bytes).collect();
        secrets.sort_by_key(|s| std::cmp::Reverse(s.len()));
        secrets.dedup();
        Masker { secrets, carry: Vec::new() }
    }

    pub fn is_empty(&self) -> bool {
        self.secrets.is_empty()
    }

    pub fn feed(&mut self, chunk: &[u8]) -> Vec<u8> {
        if self.secrets.is_empty() {
            return chunk.to_vec();
        }
        let mut buf = std::mem::take(&mut self.carry);
        buf.extend_from_slice(chunk);
        let mut out = Vec::with_capacity(buf.len());
        let mut i = 0;
        'scan: while i < buf.len() {
            for s in &self.secrets {
                if buf[i..].starts_with(s) {
                    out.extend_from_slice(MASK.as_bytes());
                    i += s.len();
                    continue 'scan;
                }
            }
            out.push(buf[i]);
            i += 1;
        }
        // Hold back the longest suffix of the output that is a proper prefix of some value.
        let longest = self.secrets.iter().map(Vec::len).max().unwrap_or(0);
        let start = out.len().saturating_sub(longest.saturating_sub(1));
        for from in start..out.len() {
            if self.secrets.iter().any(|s| s.len() > out.len() - from && s.starts_with(&out[from..])) {
                self.carry = out.split_off(from);
                break;
            }
        }
        out
    }

    /// What was held back (the stream ended: it was not a value after all).
    pub fn finish(&mut self) -> Vec<u8> {
        std::mem::take(&mut self.carry)
    }
}

/// A runner that lends the same credentials to every process it spawns. Everything else is the inner runner's.
pub struct LendingRunner {
    inner: Arc<dyn Runner>,
    lent: LentCredentials,
}

impl LendingRunner {
    pub fn new(inner: Arc<dyn Runner>, lent: LentCredentials) -> Self {
        LendingRunner { inner, lent }
    }
}

impl Runner for LendingRunner {
    fn id(&self) -> &str {
        self.inner.id()
    }
    fn ping(&self) -> RunnerFuture<'_, crate::Pong> {
        self.inner.ping()
    }
    fn spawn(&self, mut spec: ProcessSpec) -> RunnerFuture<'_, ProcHandle> {
        if !self.lent.is_empty() {
            // The same names must not also travel in the plain env.
            spec.env.retain(|(k, _)| !self.lent.env.iter().any(|e| &e.name == k));
            spec.lent = Some(self.lent.clone());
        }
        self.inner.spawn(spec)
    }
    fn write<'a>(&'a self, id: &'a ProcId, data: &'a [u8]) -> RunnerFuture<'a, ()> {
        self.inner.write(id, data)
    }
    fn close_input<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, ()> {
        self.inner.close_input(id)
    }
    fn resize<'a>(&'a self, id: &'a ProcId, size: Size) -> RunnerFuture<'a, ()> {
        self.inner.resize(id, size)
    }
    fn kill<'a>(&'a self, id: &'a ProcId, scope: KillScope) -> RunnerFuture<'a, ()> {
        self.inner.kill(id, scope)
    }
    fn events<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, tokio::sync::mpsc::UnboundedReceiver<crate::ProcEvent>> {
        self.inner.events(id)
    }
    fn screen<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, ScreenSnapshot> {
        self.inner.screen(id)
    }
    fn screen_text_since<'a>(&'a self, id: &'a ProcId, mark: u64, max: usize) -> RunnerFuture<'a, String> {
        self.inner.screen_text_since(id, mark, max)
    }
    fn tail(&self, spec: TailSpec) -> RunnerFuture<'_, tokio::sync::mpsc::UnboundedReceiver<String>> {
        self.inner.tail(spec)
    }
    fn hook_channel(&self) -> RunnerFuture<'_, crate::HookEndpoint> {
        self.inner.hook_channel()
    }
    fn release<'a>(&'a self, id: &'a ProcId) -> RunnerFuture<'a, ()> {
        self.inner.release(id)
    }
    fn forward_loopback(&self, target_port: u16) -> RunnerFuture<'_, u16> {
        self.inner.forward_loopback(target_port)
    }
    fn http(&self, req: crate::HttpRequest) -> RunnerFuture<'_, tokio::sync::mpsc::UnboundedReceiver<crate::HttpEvent>> {
        self.inner.http(req)
    }
    fn stage_file<'a>(&'a self, name: &'a str, contents: &'a [u8]) -> RunnerFuture<'a, String> {
        self.inner.stage_file(name, contents)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn lent() -> LentCredentials {
        LentCredentials { env: vec![LentVar { name: "PI_KEY".into(), value: "sk-lent-1234567890".into(), harness: Some("pi".into()) }], git_token: Some("ghp_tokentokentoken".into()) }
    }

    #[test]
    fn debug_never_shows_a_value() {
        let s = format!("{:?} {:?}", lent(), lent().env[0]);
        assert!(!s.contains("sk-lent") && !s.contains("ghp_"), "{s}");
        let spec = ProcessSpec { lent: Some(lent()), ..ProcessSpec::new("x", ["y"]) };
        let s = format!("{spec:?}");
        assert!(!s.contains("sk-lent") && !s.contains("ghp_"), "{s}");
    }

    #[test]
    fn apply_sets_env_and_puts_the_git_token_only_in_git_config_env() {
        let mut spec = ProcessSpec::new("git", ["ls-remote"]);
        spec.env.push(("GIT_CONFIG_COUNT".into(), "1".into()));
        lent().apply(&mut spec, &|_| false);
        let get = |k: &str| spec.env.iter().find(|(n, _)| n == k).map(|(_, v)| v.clone());
        assert_eq!(get("PI_KEY").as_deref(), Some("sk-lent-1234567890"));
        assert_eq!(get("GIT_CONFIG_COUNT").as_deref(), Some("2"));
        assert_eq!(get("GIT_CONFIG_KEY_1").as_deref(), Some("http.extraHeader"));
        let header = get("GIT_CONFIG_VALUE_1").unwrap();
        assert!(header.starts_with("Authorization: Basic "));
        assert!(!spec.args.iter().any(|a| a.contains("ghp_")) && !spec.program.contains("ghp_"));
        assert!(!header.contains("ghp_tokentokentoken"), "the header carries the token encoded, never in clear");
    }

    #[test]
    fn a_runner_keeping_its_login_does_not_take_the_harness_key() {
        let mut spec = ProcessSpec::new("pi", Vec::<String>::new());
        lent().apply(&mut spec, &|h| h == "pi");
        assert!(!spec.env.iter().any(|(k, _)| k == "PI_KEY"));
        assert!(spec.env.iter().any(|(k, _)| k == "GIT_CONFIG_COUNT"), "the git token is still applied");
    }

    #[test]
    fn masker_hides_a_value_also_split_across_chunks() {
        let mut m = Masker::new(lent().secrets());
        let mut out = m.feed(b"key is sk-lent-12");
        out.extend(m.feed(b"34567890 done"));
        out.extend(m.finish());
        assert_eq!(String::from_utf8(out).unwrap(), "key is *** done");
        let mut m = Masker::new(["sk-lent-1234567890".to_string()]);
        let mut out = m.feed(b"almost sk-lent-12");
        out.extend(m.feed(b" but not"));
        out.extend(m.finish());
        assert_eq!(String::from_utf8(out).unwrap(), "almost sk-lent-12 but not");
    }
}
