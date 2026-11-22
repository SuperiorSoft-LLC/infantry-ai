// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The generic runner seam (RR-4). The daemon resolves a runner BY NAME through [`RunnerRegistry`]; every entry is the same
//! shape ([`RunnerEntry`]): an `Arc<dyn Runner>` (processes) and a [`RunnerScope`] (runner-scope frames). `local` (the
//! embedded runner) is built in and is an entry like any other; a paired remote runner arrives through a [`RunnerSource`]
//! (the pool registers itself at the composition root). Nothing above this seam branches on local vs remote.
//!
//! A start that names a runner is NEVER answered with another one: [`RunnerRegistry::resolve`] returns a [`Refusal`] that
//! names the runner and the reason (unknown, offline, outdated), so the caller refuses loudly and keeps the ticket where it is.

pub mod remote;

use infantry_runner_api::Runner;
use std::sync::{Arc, Mutex};

/// The name of the embedded runner.
pub const LOCAL: &str = "local";
/// The placement value that means "the first online runner with a free slot".
pub const ANY: &str = "any";

/// Runner-scope frames (a runner's own units: harness status, providers, models). `local` answers in-process, a remote
/// runner over its link.
pub trait RunnerScope: Send + Sync {
    /// One frame `frame` with JSON `data`; the JSON answer, or why not.
    fn request(&self, frame: &str, data: &str) -> Result<String, String>;
    /// Like [`request`](Self::request) but with the envelope type the runner's unit answered with (`(type, data)`). The default
    /// assumes the unit answers with its own frame name.
    fn request_typed(&self, frame: &str, data: &str) -> Result<(String, String), String> {
        self.request(frame, data).map(|d| (frame.to_string(), d))
    }
}

/// A scope backed by a function `(frame, data) -> (type, data)` (the daemon's own `composed.request` for `local`).
pub struct FnScope(pub Box<dyn Fn(&str, &str) -> Result<(String, String), String> + Send + Sync>);

impl RunnerScope for FnScope {
    fn request(&self, frame: &str, data: &str) -> Result<String, String> {
        (self.0)(frame, data).map(|(_, d)| d)
    }
    fn request_typed(&self, frame: &str, data: &str) -> Result<(String, String), String> {
        (self.0)(frame, data)
    }
}

/// A scope that answers nothing (a runner without runner-scope units).
pub struct NoScope(pub String);

impl RunnerScope for NoScope {
    fn request(&self, frame: &str, _data: &str) -> Result<String, String> {
        Err(format!("runner '{}' does not answer the frame '{frame}'", self.0))
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Availability {
    Online,
    /// No answer; the reason is the link error.
    Offline(String),
    /// Answers, but its contract is older than the app's minimum.
    Outdated(String),
    /// Not probed yet.
    Unknown,
}

impl Availability {
    pub fn word(&self) -> &'static str {
        match self {
            Availability::Online => "online",
            Availability::Offline(_) => "offline",
            Availability::Outdated(_) => "outdated",
            Availability::Unknown => "unknown",
        }
    }
}

/// What a listing shows of one runner.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RunnerInfo {
    pub name: String,
    /// `embedded` for `local`, `remote` for a paired runner.
    pub kind: String,
    pub availability: Availability,
    /// Free session slots; `None` = unlimited or not known.
    pub free_slots: Option<i32>,
}

/// One resolved runner.
#[derive(Clone)]
pub struct RunnerEntry {
    pub name: String,
    pub kind: String,
    pub runner: Arc<dyn Runner>,
    pub scope: Arc<dyn RunnerScope>,
}

/// Why a start was refused. The text names the runner and the reason.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Refusal(pub String);

impl std::fmt::Display for Refusal {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

/// A set of remote runners the registry can resolve (the paired runners of a pool).
pub trait RunnerSource: Send + Sync {
    /// Every runner of this source, in listing order.
    fn list(&self) -> Vec<RunnerInfo>;
    /// The entry for `name`; `None` = not one of this source's runners.
    fn entry(&self, name: &str) -> Option<RunnerEntry>;
    /// Reads the runner's health again now (one probe) and returns what it found.
    fn refresh(&self, name: &str) -> Availability;
}

pub struct RunnerRegistry {
    local: RunnerEntry,
    /// The scope the composition root gives `local` (set once); until then `local` answers no runner-scope frame.
    local_scope: std::sync::OnceLock<Arc<dyn RunnerScope>>,
    sources: Mutex<Vec<Arc<dyn RunnerSource>>>,
}

impl RunnerRegistry {
    /// `local` is the embedded runner; its name is forced to `local`.
    pub fn new(mut local: RunnerEntry) -> Arc<RunnerRegistry> {
        local.name = LOCAL.into();
        local.kind = "embedded".into();
        Arc::new(RunnerRegistry { local, local_scope: std::sync::OnceLock::new(), sources: Mutex::new(Vec::new()) })
    }

    pub fn add_source(&self, s: Arc<dyn RunnerSource>) {
        self.sources.lock().unwrap_or_else(|e| e.into_inner()).push(s);
    }

    fn sources(&self) -> Vec<Arc<dyn RunnerSource>> {
        self.sources.lock().unwrap_or_else(|e| e.into_inner()).clone()
    }

    pub fn local(&self) -> RunnerEntry {
        let mut e = self.local.clone();
        if let Some(s) = self.local_scope.get() {
            e.scope = s.clone();
        }
        e
    }

    /// Wires how `local` answers runner-scope frames (in-process); set once.
    pub fn set_local_scope(&self, s: Arc<dyn RunnerScope>) {
        let _ = self.local_scope.set(s);
    }

    /// The runner for a runner-scope frame: `local` (or empty) or a paired runner by name. Never `any`. A refusal names the
    /// runner and the reason and nothing is answered by another runner.
    pub fn resolve_scope(&self, spec: &str) -> Result<RunnerEntry, Refusal> {
        let spec = spec.trim();
        if spec.eq_ignore_ascii_case(ANY) {
            return Err(Refusal("`any` is not a runner: name one (infantry runner list); nothing was answered locally".into()));
        }
        self.resolve_with(spec, "nothing was answered locally")
    }

    /// `local` first (always online), then every source's runners.
    pub fn list(&self) -> Vec<RunnerInfo> {
        let mut all = vec![RunnerInfo { name: LOCAL.into(), kind: "embedded".into(), availability: Availability::Online, free_slots: None }];
        for s in self.sources() {
            all.extend(s.list());
        }
        all
    }

    /// `spec` is a runner name, `local`, or `any`; an empty one is `local`. Never answers with a different runner than the
    /// one asked for.
    pub fn resolve(&self, spec: &str) -> Result<RunnerEntry, Refusal> {
        self.resolve_with(spec, "the session was not started and nothing ran locally")
    }

    fn resolve_with(&self, spec: &str, tail: &str) -> Result<RunnerEntry, Refusal> {
        let spec = spec.trim();
        if spec.is_empty() || spec.eq_ignore_ascii_case(LOCAL) {
            return Ok(self.local());
        }
        if spec.eq_ignore_ascii_case(ANY) {
            return self.any();
        }
        for s in self.sources() {
            if s.list().iter().any(|i| i.name == spec) {
                return Self::usable(&*s, spec, tail);
            }
        }
        Err(Refusal(format!("runner '{spec}' is not paired with this daemon (known: {}); {tail}", self.names())))
    }

    /// Re-reads the runner's health once before refusing: a heartbeat may be stale.
    fn usable(s: &dyn RunnerSource, name: &str, tail: &str) -> Result<RunnerEntry, Refusal> {
        let known = s.list().into_iter().find(|i| i.name == name).map(|i| i.availability).unwrap_or(Availability::Unknown);
        // A start always re-reads the runner's health: the heartbeat may be stale, and a refusal must be true.
        let _ = known;
        let now = s.refresh(name);
        match now {
            Availability::Online => s.entry(name).ok_or_else(|| Refusal(format!("runner '{name}' was detached while the session started"))),
            Availability::Offline(why) => Err(Refusal(format!("runner '{name}' is offline: {why}; {tail}"))),
            Availability::Outdated(why) => Err(Refusal(format!("runner '{name}' is outdated: {why}; {tail}"))),
            Availability::Unknown => Err(Refusal(format!("runner '{name}' did not answer; {tail}"))),
        }
    }

    fn any(&self) -> Result<RunnerEntry, Refusal> {
        let mut why = Vec::new();
        for s in self.sources() {
            for i in s.list() {
                match Self::usable(&*s, &i.name, "the session was not started and nothing ran locally") {
                    Ok(e) => {
                        // Re-read: the listing's slot count may be stale too.
                        let free = s.list().into_iter().find(|x| x.name == i.name).and_then(|x| x.free_slots);
                        if free != Some(0) {
                            return Ok(e);
                        }
                        why.push(format!("'{}' has no free slot", i.name));
                    }
                    Err(r) => why.push(r.0),
                }
            }
        }
        if why.is_empty() {
            return Err(Refusal("no runner is paired with this daemon, so `any` has nothing to run on; nothing ran locally".into()));
        }
        Err(Refusal(format!("no online runner has a free slot ({}); nothing ran locally", why.join("; "))))
    }

    fn names(&self) -> String {
        self.list().into_iter().map(|i| i.name).collect::<Vec<_>>().join(", ")
    }

    /// The folder on the runner's host a session of this entry runs in when its ticket names none (the workspace is RR-7): the
    /// folder the runner stages files in, so it exists and is writable there.
    pub fn scratch_dir(entry: &RunnerEntry) -> Result<String, String> {
        // On its own thread, so it is safe to call from inside an async runtime too.
        let file = std::thread::scope(|s| {
            s.spawn(|| {
                let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(|e| e.to_string())?;
                rt.block_on(entry.runner.stage_file(".infantry-scratch", b"")).map_err(|e| format!("runner '{}' has no scratch folder: {e}", entry.name))
            })
            .join()
            .unwrap_or_else(|_| Err("the scratch lookup panicked".to_string()))
        })?;
        let dir = std::path::Path::new(&file).parent().map(|d| d.to_string_lossy().into_owned()).unwrap_or(file);
        Ok(dir)
    }
}
