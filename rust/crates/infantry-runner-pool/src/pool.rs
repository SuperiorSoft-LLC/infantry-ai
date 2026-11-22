// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
use infantry_runner_api::Runner;
use infantry_runner_link::wire::{self, HelloAnswer};
use infantry_runner_link::RemoteRunner;
use infantry_runner_source::{Availability, RunnerEntry, RunnerInfo, RunnerScope, RunnerSource};
use infantry_runner_link::{CallError, LinkClient, LinkStore, PairedRunner, Ping};
use std::collections::{BTreeMap, HashMap};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, OnceLock, Weak};
use std::time::Duration;

/// The setting that sets the heartbeat interval (seconds), and its default.
pub const HEARTBEAT_KEY: &str = "limits.runners.heartbeatSec";
pub const DEFAULT_HEARTBEAT_SEC: u64 = 30;
/// `runners.allowPlainTailnet` (default false): serve and call the runner link as plain `ws://`. Plain traffic carries the
/// runner credential, every command and its output unencrypted: anyone on the path can read it or take the runner over.
/// Turn it on only for a private network you trust, on the runner and on the orchestrator.
pub const ALLOW_PLAIN_KEY: &str = "runners.allowPlainTailnet";
const LINK_DIR: &str = "runner-link";
const SLEEP_STEP: Duration = Duration::from_millis(100);
const CALL_TIMEOUT: Duration = Duration::from_secs(20);

/// What the last heartbeat (or check) learned about one runner.
#[derive(Debug, Clone, PartialEq)]
pub enum RunnerState {
    /// Not probed yet.
    Unknown,
    Online { rtt_ms: u64, contract: String },
    /// No answer; `reason` is the link error.
    Offline { reason: String },
    /// Answers, but its contract is older than the app's minimum.
    Outdated { contract: String, reason: String },
}

impl RunnerState {
    pub fn word(&self) -> &'static str {
        match self {
            RunnerState::Unknown => "unknown",
            RunnerState::Online { .. } => "online",
            RunnerState::Offline { .. } => "offline",
            RunnerState::Outdated { .. } => "outdated",
        }
    }
}

/// One store per state dir, for the life of the process (the RUNNER-PUSH-WAKE-1 lesson: never reopen per call).
fn store_for(state_dir: &Path) -> Arc<LinkStore> {
    static STORES: OnceLock<Mutex<HashMap<PathBuf, Arc<LinkStore>>>> = OnceLock::new();
    let dir = state_dir.join(LINK_DIR);
    let mut all = STORES.get_or_init(Default::default).lock().unwrap_or_else(|e| e.into_inner());
    all.entry(dir.clone()).or_insert_with(|| Arc::new(LinkStore::open(&dir))).clone()
}

pub struct RunnerPool {
    store: Arc<LinkStore>,
    states: Mutex<BTreeMap<String, RunnerState>>,
    pub call_timeout: Duration,
    /// Reads `runners.allowPlainTailnet` live (the root sets it); unset = false.
    allow_plain: OnceLock<Arc<dyn Fn() -> bool + Send + Sync>>,
    /// Free session slots each runner reported at its last health answer (unlimited is not stored).
    slots: Mutex<BTreeMap<String, i32>>,
}

impl RunnerPool {
    /// The pool of `<state_dir>/runner-link`.
    pub fn open(state_dir: &Path) -> Arc<RunnerPool> {
        Arc::new(RunnerPool { store: store_for(state_dir), states: Mutex::new(BTreeMap::new()), call_timeout: CALL_TIMEOUT, allow_plain: OnceLock::new(), slots: Mutex::new(BTreeMap::new()) })
    }

    /// Wires the live reader of `runners.allowPlainTailnet`.
    pub fn set_allow_plain(&self, f: Arc<dyn Fn() -> bool + Send + Sync>) {
        let _ = self.allow_plain.set(f);
    }

    pub fn allow_plain(&self) -> bool {
        self.allow_plain.get().is_some_and(|f| f())
    }

    pub fn store(&self) -> &Arc<LinkStore> {
        &self.store
    }

    pub fn orchestrator_id(&self) -> String {
        self.store.self_id()
    }

    pub fn runners(&self) -> Vec<PairedRunner> {
        self.store.runners()
    }

    pub fn state(&self, name: &str) -> RunnerState {
        self.states.lock().unwrap_or_else(|e| e.into_inner()).get(name).cloned().unwrap_or(RunnerState::Unknown)
    }

    fn set_state(&self, name: &str, s: RunnerState) {
        self.states.lock().unwrap_or_else(|e| e.into_inner()).insert(name.to_string(), s);
    }

    fn client(&self, r: &PairedRunner) -> LinkClient {
        let mut c = LinkClient::of(r, &self.orchestrator_id()).allow_plain(self.allow_plain());
        c.timeout = self.call_timeout;
        c
    }

    /// Pairs with the runner at `url` by its bearer `secret`; `pin` is the certificate SHA-256 from the bearer (None = a plain
    /// bearer). `wss://` needs the pin; `ws://` needs a plain bearer and `runners.allowPlainTailnet`. Keeps the runner under
    /// `name` (its own name when None).
    pub fn add(&self, url: &str, secret: &str, pin: Option<&str>, name: Option<&str>, orchestrator_name: &str) -> Result<PairedRunner, CallError> {
        let tls = infantry_runner_link::transport::is_tls(url);
        let pin = pin.map(str::trim).filter(|p| !p.is_empty());
        if tls && pin.is_none() {
            return Err(CallError::Refused("this bearer is plain (it names no certificate), so it cannot pair over wss://; copy the bearer of a runner that serves wss, or use ws:// with runners.allowPlainTailnet on".into()));
        }
        if !tls && pin.is_some() {
            return Err(CallError::Refused("this bearer carries a certificate pin: the runner serves wss://. Use its wss:// endpoint".into()));
        }
        let mut c = LinkClient::new(url, self.orchestrator_id(), None).with_pin(pin.map(str::to_string)).allow_plain(self.allow_plain());
        c.timeout = self.call_timeout;
        let mut paired = c.pair(secret, orchestrator_name)?;
        if let Some(n) = name.map(str::trim).filter(|n| !n.is_empty()) {
            paired.name = n.to_string();
        }
        self.store.save_runner(paired.clone());
        self.set_state(&paired.name, RunnerState::Unknown);
        Ok(paired)
    }

    /// Hello + health (ping/pong) on runner `name`; records and returns the state, with the ping when it answered.
    pub fn check(&self, name: &str) -> Option<(RunnerState, Option<Ping>)> {
        let r = self.store.runner(name)?;
        let client = self.client(&r);
        let (state, ping) = match client.ping() {
            Ok(p) => (RunnerState::Online { rtt_ms: p.rtt.as_millis() as u64, contract: p.hello.contract.clone() }, Some(p)),
            Err(CallError::Refused(reason)) if reason.contains(wire::OUTDATED_HINT) => {
                let contract = client.typed::<_, HelloAnswer>(wire::HELLO, &serde_json::json!({})).map(|h| h.contract).unwrap_or_default();
                (RunnerState::Outdated { contract, reason }, None)
            }
            Err(e) => (RunnerState::Offline { reason: e.to_string() }, None),
        };
        {
            let mut slots = self.slots.lock().unwrap_or_else(|e| e.into_inner());
            match ping.as_ref().map(|p| p.health.free_slots) {
                Some(n) if n >= 0 => slots.insert(name.to_string(), n),
                _ => slots.remove(name),
            };
        }
        self.set_state(name, state.clone());
        Some((state, ping))
    }

    /// Forgets runner `name`; tells the runner too when it answers. Returns what happened at the runner.
    pub fn detach(&self, name: &str) -> Option<String> {
        let r = self.store.runner(name)?;
        let remote = match self.client(&r).detach() {
            Ok(()) => "the runner forgot this orchestrator".to_string(),
            Err(e) => format!("the runner was not told ({e}); it keeps the pairing until `infantry runner detach` runs there"),
        };
        self.store.remove_runner(name);
        self.states.lock().unwrap_or_else(|e| e.into_inner()).remove(name);
        Some(remote)
    }

    /// One heartbeat: every paired runner pinged; a runner no longer paired loses its state.
    pub fn beat(&self) {
        let names: Vec<String> = self.runners().into_iter().map(|r| r.name).collect();
        for n in &names {
            self.check(n);
        }
        self.states.lock().unwrap_or_else(|e| e.into_inner()).retain(|k, _| names.contains(k));
    }

    /// Starts the heartbeat thread: every `interval_sec()` seconds (read each round, so the setting takes effect live).
    /// It ends when the pool is dropped.
    pub fn start_heartbeat(self: &Arc<Self>, interval_sec: Arc<dyn Fn() -> u64 + Send + Sync>) {
        let weak: Weak<RunnerPool> = Arc::downgrade(self);
        let _ = std::thread::Builder::new().name("runner-pool-heartbeat".into()).spawn(move || loop {
            let wanted = Duration::from_secs(interval_sec().max(1));
            let mut waited = Duration::ZERO;
            while waited < wanted {
                std::thread::sleep(SLEEP_STEP);
                waited += SLEEP_STEP;
                if weak.strong_count() == 0 {
                    return;
                }
            }
            match weak.upgrade() {
                Some(p) => p.beat(),
                None => return,
            }
        });
    }
}

/// A paired runner's frames over its link (runner-scope units on the runner: RR-13 serves them).
struct LinkScope {
    name: String,
    client: Arc<LinkClient>,
}

impl RunnerScope for LinkScope {
    fn request(&self, frame: &str, data: &str) -> Result<String, String> {
        self.request_typed(frame, data).map(|(_, d)| d)
    }

    /// One `runner_link_frame`: the runner answers through its own units, only for the frames its plugin offers.
    fn request_typed(&self, frame: &str, data: &str) -> Result<(String, String), String> {
        let body = serde_json::to_string(&wire::FrameRequest { frame: frame.into(), data: data.into() }).map_err(|e| e.to_string())?;
        let a = self.client.call(wire::FRAME, &body).map_err(|e| format!("runner '{}': {e}", self.name))?;
        if !a.ok() {
            return Err(format!("runner '{}' refused {frame}: {}", self.name, a.data));
        }
        let ans: wire::FrameAnswer = serde_json::from_str(&a.data).map_err(|_| format!("runner '{}' answered {frame} with something unreadable", self.name))?;
        Ok((ans.r#type, ans.data))
    }
}

/// The pool is the remote half of the runner registry: each paired runner is a [`RunnerEntry`] (a [`RemoteRunner`] plus its
/// link scope), so the daemon above cannot tell it from `local`.
impl RunnerSource for RunnerPool {
    fn list(&self) -> Vec<RunnerInfo> {
        let slots = self.slots.lock().unwrap_or_else(|e| e.into_inner()).clone();
        self.runners()
            .into_iter()
            .map(|r| {
                let availability = match self.state(&r.name) {
                    RunnerState::Unknown => Availability::Unknown,
                    RunnerState::Online { .. } => Availability::Online,
                    RunnerState::Offline { reason } => Availability::Offline(reason),
                    RunnerState::Outdated { reason, .. } => Availability::Outdated(reason),
                };
                RunnerInfo { free_slots: slots.get(&r.name).copied(), name: r.name, kind: "remote".into(), availability }
            })
            .collect()
    }

    fn entry(&self, name: &str) -> Option<RunnerEntry> {
        let r = self.store.runner(name)?;
        let client = Arc::new(self.client(&r));
        let runner: Arc<dyn Runner> = Arc::new(RemoteRunner::new(r.name.clone(), self.client(&r)));
        Some(RunnerEntry { name: r.name.clone(), kind: "remote".into(), runner, scope: Arc::new(LinkScope { name: r.name, client }) })
    }

    fn refresh(&self, name: &str) -> Availability {
        match self.check(name).map(|(s, _)| s) {
            Some(RunnerState::Online { .. }) => Availability::Online,
            Some(RunnerState::Offline { reason }) => Availability::Offline(reason),
            Some(RunnerState::Outdated { reason, .. }) => Availability::Outdated(reason),
            _ => Availability::Unknown,
        }
    }
}
