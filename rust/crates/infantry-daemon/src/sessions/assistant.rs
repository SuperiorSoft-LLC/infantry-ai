// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! ASSISTANT-1: the ONE Assistant of an installation (the `assistant` frame; Kotlin `AssistantService`).
//!
//! It is an ordinary session in every way a client can see (the chat, `session_swap`, resume and kill all work on it), with
//! three differences: it belongs to no project (so a project switch never hides it), `SessionRecord.global` marks it, and it
//! runs in its own folder under the state dir, never the user's home. Its role stays (`session_swap` refuses a role change
//! on a global session); its model is free.
//!
//! What it runs on is not decided here. The composition root hands in an [`AssistantSpec`]: the role it plays and the
//! harness, model and effort bound to that role (Roles > binding). A field the binding leaves open is the harness default's,
//! and a binding that names a harness this daemon does not run is refused with that reason, never swapped for another one.
//!
//! One at a time: the "is there one?" check and the creation are a single step under `assistant_lock`, so two clients (two
//! windows, a phone, a dev build's double effect) asking at once are given the same session.

use super::{Daemon, NewOpts};
use infantry_protocol::session::SessionRecord;
use std::sync::Arc;

/// The role id the catalog ships for the Assistant: the default of the [`ROLE_KEY`] setting.
pub const ROLE: &str = "assistant";
/// The app setting that names the role the Assistant plays (its binding gives the harness, model and effort).
pub const ROLE_KEY: &str = "assistant.role";
/// The Assistant's folder, under the state dir.
pub const DIR: &str = "assistant";

/// What the Assistant runs on: the role it plays and the executor bound to it. A `None` is left to the harness.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AssistantSpec {
    pub role: String,
    pub provider: Option<String>,
    pub model: Option<String>,
    pub effort: Option<String>,
}

impl Default for AssistantSpec {
    /// A daemon with no role bindings (no composition root): the catalog's role on the default harness.
    fn default() -> Self {
        AssistantSpec { role: ROLE.into(), provider: None, model: None, effort: None }
    }
}

impl Daemon {
    /// Sets where the Assistant's role and executor come from (set once, at composition); read at each creation, so a
    /// change of the binding applies to an Assistant made afterwards.
    pub fn set_assistant_spec(&self, f: Arc<dyn Fn() -> AssistantSpec + Send + Sync>) {
        let _ = self.assistant_spec.set(f);
    }

    /// The installation's Assistant, made at rest on the first call; every later call (from any client) returns the same
    /// session. The creation reaches every client as a `session` frame like any other session's.
    pub fn ensure_assistant(&self) -> Result<SessionRecord, String> {
        // A poisoned lock guards nothing (it holds `()`): the check below is the truth.
        let _one = self.assistant_lock.lock().unwrap_or_else(|poisoned| poisoned.into_inner());
        if let Some(existing) = self.records().into_iter().find(|r| r.global) {
            return Ok(existing);
        }
        let spec = self.assistant_spec.get().map(|f| f()).unwrap_or_default();
        let dir = self.cfg.state_dir.join(DIR);
        std::fs::create_dir_all(&dir).map_err(|e| format!("assistant refused: cannot make its folder {}: {e}", dir.display()))?;
        let opts = NewOpts {
            cwd: Some(dir.to_string_lossy().into_owned()),
            role: Some(spec.role),
            provider: spec.provider,
            model: spec.model,
            effort: spec.effort,
            ..Default::default()
        };
        let mut rec = self.new_record(&opts, "IDLE", "assistant")?;
        rec.global = true;
        let id = self.list_idle(rec);
        self.record(&id).ok_or_else(|| "assistant refused: the new session vanished before it could be returned".to_string())
    }
}
