// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! RR-6 what a remote start lends: the harness key, the project's secrets and a git token, resolved at each spawn from
//! `infantry-vault` and the harness's own key setting. A value is read here and handed to the runner with the start; it is
//! never logged, never put in an answer or a ticket, and the runner keeps it in memory for that process only.
//!
//! Settings (all read per spawn, so a change takes effect live):
//! - `runners.lendCredentials`: which runners may be lent anything (a list of names or comma text; unset or `*` = every runner);
//! - `runners.credentials`: per runner `lend` (default) or `runner` (no harness key is lent: the runner uses the key and
//!   sign-in stored ON it);
//! - `runners.lendSecretsMax`: how many project secrets one start lends (default [`DEFAULT_LEND_SECRETS_MAX`]);
//! - `runners.gitSecret`: the vault secret that holds the git token (empty = no git token is lent).
use infantry_runner_api::{LentCredentials, LentVar};
use infantry_vault::keystore::{Keystore, VALUE_FIELD};
use serde_json::Value;
use std::sync::Arc;

pub const LEND_ALLOW_KEY: &str = "runners.lendCredentials";
pub const CREDENTIALS_KEY: &str = "runners.credentials";
pub const LEND_SECRETS_MAX_KEY: &str = "runners.lendSecretsMax";
pub const GIT_SECRET_KEY: &str = "runners.gitSecret";
pub const DEFAULT_LEND_SECRETS_MAX: usize = 20;
pub const MODE_LEND: &str = "lend";
pub const MODE_RUNNER: &str = "runner";

pub type Setting = Arc<dyn Fn(&str) -> Option<Value> + Send + Sync>;
/// `(harness id) -> the env its session gets` ([`crate::harness::credentials::HarnessCredentials::session_env`]).
pub type HarnessEnv = Arc<dyn Fn(&str) -> Vec<(String, String)> + Send + Sync>;

pub struct Lender {
    pub setting: Setting,
    pub keystore: Arc<Keystore>,
    pub harness_env: HarnessEnv,
}

fn text(v: &Value) -> String {
    v.as_str().map(|s| s.trim().to_string()).unwrap_or_default()
}

/// A valid environment variable name.
fn env_name(n: &str) -> bool {
    let mut c = n.chars();
    c.next().is_some_and(|f| f.is_ascii_alphabetic() || f == '_') && c.all(|c| c.is_ascii_alphanumeric() || c == '_')
}

impl Lender {
    /// May runner `name` be lent anything? (`runners.lendCredentials`: unset, empty or `*` = every runner.)
    pub fn allowed(&self, runner: &str) -> bool {
        match (self.setting)(LEND_ALLOW_KEY) {
            None | Some(Value::Null) => true,
            Some(Value::Array(a)) => a.is_empty() || a.iter().any(|v| matches!(v.as_str(), Some(s) if s.trim() == "*" || s.trim() == runner)),
            Some(Value::String(s)) => s.trim().is_empty() || s.split(',').any(|n| n.trim() == "*" || n.trim() == runner),
            Some(_) => true,
        }
    }

    /// Where runner `name`'s harness and provider keys come from: `lend` (the default) or `runner`.
    pub fn mode(&self, runner: &str) -> String {
        match (self.setting)(CREDENTIALS_KEY) {
            Some(Value::Object(m)) => m.get(runner).map(text).filter(|s| s == MODE_RUNNER || s == MODE_LEND).unwrap_or_else(|| MODE_LEND.into()),
            _ => MODE_LEND.into(),
        }
    }

    fn git_secret(&self) -> Option<String> {
        (self.setting)(GIT_SECRET_KEY).map(|v| text(&v)).filter(|s| !s.is_empty())
    }

    /// The git token a start (or a check) lends, if a git secret is named and set.
    pub fn git_token(&self, project: Option<&str>) -> Option<String> {
        let name = self.git_secret()?;
        self.keystore.resolve(&name, project).ok().flatten().and_then(|f| f.get(VALUE_FIELD).cloned()).filter(|v| !v.is_empty())
    }

    /// The git credential a check of `runner` sends: the same one a start would lend (nothing when the runner may not be lent).
    pub fn git_credentials(&self, runner: &str, project: Option<&str>) -> Option<LentCredentials> {
        if !self.allowed(runner) {
            return None;
        }
        self.git_token(project).map(|t| LentCredentials { git_token: Some(t), ..Default::default() })
    }

    /// Would a start of `harness` on `runner` lend a harness key? (Only that a non-empty one exists, never its value.)
    pub fn lends_key(&self, runner: &str, harness: &str) -> bool {
        self.allowed(runner) && self.mode(runner) == MODE_LEND && (self.harness_env)(harness).iter().any(|(_, v)| !v.is_empty())
    }

    /// What a start of `provider` for `project` on runner `runner` lends.
    pub fn lend(&self, runner: &str, provider: &str, project: Option<&str>) -> LentCredentials {
        if !self.allowed(runner) {
            return LentCredentials::default();
        }
        let mut lent = LentCredentials { git_token: self.git_token(project), ..Default::default() };
        if self.mode(runner) == MODE_LEND {
            // RUNNER-CRED-1: a key that exists is lent whatever the sign-in state says; only an absent key is not.
            for (name, value) in (self.harness_env)(provider) {
                lent.env.push(LentVar { name, value, harness: Some(provider.to_string()) });
            }
        }
        let max = (self.setting)(LEND_SECRETS_MAX_KEY).and_then(|v| v.as_u64().or_else(|| v.as_str().and_then(|s| s.trim().parse().ok()))).map(|n| n as usize).unwrap_or(DEFAULT_LEND_SECRETS_MAX);
        let git = self.git_secret();
        let names = self.keystore.names(project.filter(|p| !p.trim().is_empty())).unwrap_or_default();
        let mut taken = 0;
        for name in names {
            if taken >= max {
                break;
            }
            // Harness keys have their own path; the git secret goes only as the git credential.
            if name.starts_with("harness.") || git.as_deref() == Some(name.as_str()) || !env_name(&name) {
                continue;
            }
            let Some(fields) = self.keystore.resolve(&name, project).ok().flatten() else { continue };
            let Some(value) = fields.get(VALUE_FIELD).filter(|v| !v.is_empty()) else { continue };
            if lent.env.iter().any(|e| e.name == name) {
                continue;
            }
            lent.env.push(LentVar { name, value: value.clone(), harness: None });
            taken += 1;
        }
        lent
    }
}
