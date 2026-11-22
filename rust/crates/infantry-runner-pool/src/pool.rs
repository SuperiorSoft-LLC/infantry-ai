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
use crate::update::{self, CliDocker, Docker};

/// The setting that sets the heartbeat interval (seconds), and its default.
pub const HEARTBEAT_KEY: &str = "limits.runners.heartbeatSec";
pub const DEFAULT_HEARTBEAT_SEC: u64 = 30;
/// `runners.allowPlainTailnet` (default false): serve and call the runner link as plain `ws://`. Plain traffic carries the
/// runner credential, every command and its output unencrypted: anyone on the path can read it or take the runner over.
/// Turn it on only for a private network you trust, on the runner and on the orchestrator.
pub const ALLOW_PLAIN_KEY: &str = "runners.allowPlainTailnet";
/// RR-VIS: the setup log keeps this many entries per runner, and this many characters of each step's output.
pub const LOG_ENTRIES_MAX: usize = 200;
pub const LOG_OUTPUT_MAX: usize = 2000;
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
    /// RR-8: each remote process's consumed stream position, kept across a restart of this orchestrator.
    cursors: Arc<infantry_runner_link::Cursors>,
    /// RR-11: the app version each runner reported in hello (absent for a Kotlin runner).
    app_versions: Mutex<BTreeMap<String, String>>,
    /// RR-11: the running or last update of each runner.
    updates: Mutex<BTreeMap<String, UpdateStatus>>,
    /// Reads `limits.runners.updateWaitSec` live; unset = the default.
    update_wait: OnceLock<Arc<dyn Fn() -> u64 + Send + Sync>>,
    /// The docker engine an update uses (tests swap it).
    docker: Mutex<Arc<dyn Docker>>,
    /// The version this app is (an older runner app is outdated, and an update moves the image to this tag).
    pub app_version: String,
    /// RR-VIS: what the last answered ping said (kind, os, offers, harness login), for the detail panel.
    seen: Mutex<BTreeMap<String, Seen>>,
    /// RR-VIS: the rows of the last `check` of each runner, with the time.
    last_checks: Mutex<BTreeMap<String, (i64, Vec<serde_json::Value>)>>,
    /// RR-VIS: the setup log of each runner: one entry per check, install or update step (runner, command, output, exit, reason).
    steps: Mutex<BTreeMap<String, Vec<serde_json::Value>>>,
    dir: PathBuf,
}

/// RR-VIS: facts from a runner's last answered ping.
#[derive(Debug, Clone, Default)]
pub struct Seen {
    pub kind: Option<String>,
    pub os: String,
    pub offers: Vec<String>,
    /// (harness id, logged in) as the runner's hello says.
    pub harnesses: Vec<(String, bool)>,
    pub at_ms: i64,
}

/// RR-VIS: a link error in plain words, with the next thing to do. The raw text stays as the detail beside it.
pub fn plain_reason(raw: &str) -> (String, String) {
    let l = raw.to_lowercase();
    let (w, f) = if l.contains("not the pinned one") || l.contains("pin") && l.contains("certificate") {
        ("TLS handshake failed: the certificate is not the pinned one", "add the runner again with its current bearer")
    } else if l.contains("tls") || l.contains("handshake") || l.contains("certificate") {
        ("TLS handshake failed", "check the runner serves wss:// and the bearer's certificate is its own")
    } else if l.contains("refused") && (l.contains("connection") || l.contains("os error 111") || l.contains("10061")) {
        ("nothing is listening at that address: the runner is stopped", "start the runner, then check again")
    } else if l.contains("no route") || l.contains("unreachable") || l.contains("dns") || l.contains("resolve") || l.contains("lookup") {
        ("no route to host", "check the host name and the network between this machine and the runner")
    } else if l.contains("timed out") || l.contains("timeout") {
        ("the runner did not answer in time", "check the runner is running and the port is open")
    } else if l.contains("401") || l.contains("unauthor") || l.contains("bearer") && l.contains("reject") {
        ("the runner rejected this app's credential", "pair the runner again with a fresh bearer")
    } else {
        ("the runner could not be reached", "start the runner, then check the endpoint")
    };
    (w.to_string(), f.to_string())
}

/// RR-11: how an Update runner is going.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpdateStatus {
    /// `running`, `done` or `failed`.
    pub state: &'static str,
    pub steps: Vec<String>,
    /// The final text: the new image, or why it failed (the old runner is restored).
    pub detail: String,
}

impl RunnerPool {
    /// The pool of `<state_dir>/runner-link`.
    pub fn open(state_dir: &Path) -> Arc<RunnerPool> {
        Arc::new(RunnerPool { store: store_for(state_dir), states: Mutex::new(BTreeMap::new()), call_timeout: CALL_TIMEOUT, allow_plain: OnceLock::new(), slots: Mutex::new(BTreeMap::new()), cursors: Arc::new(infantry_runner_link::Cursors::open(&state_dir.join(LINK_DIR))), app_versions: Mutex::new(BTreeMap::new()), updates: Mutex::new(BTreeMap::new()), update_wait: OnceLock::new(), docker: Mutex::new(Arc::new(CliDocker)), app_version: env!("CARGO_PKG_VERSION").to_string(), seen: Mutex::new(BTreeMap::new()), last_checks: Mutex::new(BTreeMap::new()), steps: Mutex::new(BTreeMap::new()), dir: state_dir.join(LINK_DIR) })
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
            // RR-11: one app, one runner model: a runner whose APP is older than this one is outdated even when its contract matches.
            Ok(p) if wire::contract_older(p.hello.app_version.as_deref(), &self.app_version) => {
                let why = format!("runner '{}' runs app {} and this app is {}: {}", p.hello.runner_name, p.hello.app_version.as_deref().unwrap_or("?"), self.app_version, wire::OUTDATED_HINT);
                (RunnerState::Outdated { contract: p.hello.contract.clone(), reason: why }, Some(p))
            }
            Ok(p) => (RunnerState::Online { rtt_ms: p.rtt.as_millis() as u64, contract: p.hello.contract.clone() }, Some(p)),
            Err(CallError::Refused(reason)) if reason.contains(wire::OUTDATED_HINT) => {
                let contract = client.typed::<_, HelloAnswer>(wire::HELLO, &serde_json::json!({})).map(|h| h.contract).unwrap_or_default();
                (RunnerState::Outdated { contract, reason }, None)
            }
            Err(e) => (RunnerState::Offline { reason: e.to_string() }, None),
        };
        if let Some(v) = ping.as_ref().and_then(|p| p.hello.app_version.clone()) {
            self.app_versions.lock().unwrap_or_else(|e| e.into_inner()).insert(name.to_string(), v);
        }
        if let Some(p) = ping.as_ref() {
            let seen = Seen { kind: p.hello.kind.clone(), os: p.hello.os.clone(), offers: p.hello.offers.clone(), harnesses: p.hello.harnesses.iter().map(|h| (h.id.clone(), h.logged_in)).collect(), at_ms: infantry_runner_link::server::now_ms() };
            self.seen.lock().unwrap_or_else(|e| e.into_inner()).insert(name.to_string(), seen);
        }
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

    /// RR-6: the runner-side rows of a check (`credentials`, `git`): one `runner_link_check` with `req`. The request may
    /// carry the git credential a start would lend; it is never logged.
    pub fn check_rows(&self, name: &str, req: &wire::CheckRequest) -> Result<Vec<wire::CheckRow>, String> {
        let r = self.store.runner(name).ok_or_else(|| format!("no runner named '{name}'"))?;
        self.client(&r).typed::<_, wire::CheckAnswer>(wire::CHECK, req).map(|a| a.rows).map_err(|e| e.to_string())
    }

    /// RR-6: the secret names set on runner `name` (its own vault, over the runner-scope frame `secret_status`). Names only.
    pub fn runner_secret_names(&self, name: &str) -> Option<Vec<String>> {
        let entry = RunnerSource::entry(self, name)?;
        let answer = entry.scope.request("secret_status", "{}").ok()?;
        let v: serde_json::Value = serde_json::from_str(&answer).ok()?;
        Some(v["secrets"].as_array()?.iter().filter(|s| s["set"].as_bool() == Some(true)).filter_map(|s| s["name"].as_str().map(str::to_string)).collect())
    }

    /// RR-VIS: the runner's own `providers` answer, as (id, signed in, where the login lives): what its harnesses really test.
    pub fn runner_logins(&self, name: &str) -> Option<Vec<(String, bool, Option<String>, Option<String>)>> {
        Some(self.runner_provider_rows(name)?.iter().filter_map(|p| Some((p["id"].as_str()?.to_string(), p["available"].as_bool() != Some(false), p["loginAt"].as_str().map(str::to_string), p["unavailableReason"].as_str().map(str::to_string)))).collect())
    }

    /// The runner's own `providers` rows (what its harnesses report: sign-in, login place, auth kinds, key variable name).
    pub fn runner_provider_rows(&self, name: &str) -> Option<Vec<serde_json::Value>> {
        let entry = RunnerSource::entry(self, name)?;
        let answer = entry.scope.request("providers", "{\"refresh\":true}").ok()?;
        let v: serde_json::Value = serde_json::from_str(&answer).ok()?;
        v.as_array().cloned()
    }

    /// RR-VIS: the runner daemon's own log tail (its `runner_log` frame); Err = why it could not be read, in plain words.
    pub fn runner_log(&self, name: &str) -> Result<Vec<String>, String> {
        let entry = RunnerSource::entry(self, name).ok_or_else(|| "the runner is not reachable".to_string())?;
        let answer = entry.scope.request("runner_log", "{}").map_err(|e| if e.contains("not offered") { "this runner is too old to serve its log: Update runner".to_string() } else { e.to_string() })?;
        let v: serde_json::Value = serde_json::from_str(&answer).map_err(|_| "the runner's log answer could not be read".to_string())?;
        Ok(v["lines"].as_array().map(|a| a.iter().filter_map(|l| l.as_str().map(str::to_string)).collect()).unwrap_or_default())
    }

    /// The app version runner `name` reported at its last answer.
    pub fn runner_app_version(&self, name: &str) -> Option<String> {
        self.app_versions.lock().unwrap_or_else(|e| e.into_inner()).get(name).cloned()
    }

    /// RR-VIS: appends one step to runner `name`'s setup log. `output` is trimmed to [`LOG_OUTPUT_MAX`] chars (a named bound).
    pub fn log_step(&self, name: &str, step: &str, command: &str, output: &str, exit: Option<i32>, reason: &str) {
        let out: String = output.chars().take(LOG_OUTPUT_MAX).collect();
        let e = serde_json::json!({"at": infantry_runner_link::server::now_ms(), "runner": name, "step": step, "command": command, "output": out, "exit": exit, "reason": reason});
        let mut all = self.steps.lock().unwrap_or_else(|e| e.into_inner());
        let v = all.entry(name.to_string()).or_default();
        v.push(e);
        if v.len() > LOG_ENTRIES_MAX {
            let cut = v.len() - LOG_ENTRIES_MAX;
            v.drain(..cut);
        }
    }

    pub fn step_log(&self, name: &str) -> Vec<serde_json::Value> {
        self.steps.lock().unwrap_or_else(|e| e.into_inner()).get(name).cloned().unwrap_or_default()
    }

    pub fn seen(&self, name: &str) -> Option<Seen> {
        self.seen.lock().unwrap_or_else(|e| e.into_inner()).get(name).cloned()
    }

    /// Keeps the rows of a check so the panel shows them after a reload.
    pub fn keep_check(&self, name: &str, rows: Vec<serde_json::Value>) {
        self.last_checks.lock().unwrap_or_else(|e| e.into_inner()).insert(name.to_string(), (infantry_runner_link::server::now_ms(), rows));
    }

    pub fn last_check(&self, name: &str) -> Option<(i64, Vec<serde_json::Value>)> {
        self.last_checks.lock().unwrap_or_else(|e| e.into_inner()).get(name).cloned()
    }

    pub fn set_update_wait(&self, f: Arc<dyn Fn() -> u64 + Send + Sync>) {
        let _ = self.update_wait.set(f);
    }

    pub fn set_docker(&self, d: Arc<dyn Docker>) {
        *self.docker.lock().unwrap_or_else(|e| e.into_inner()) = d;
    }

    pub fn update_status(&self, name: &str) -> Option<UpdateStatus> {
        self.updates.lock().unwrap_or_else(|e| e.into_inner()).get(name).cloned()
    }

    fn note(&self, name: &str, state: &'static str, step: Option<&str>, detail: &str) {
        let mut all = self.updates.lock().unwrap_or_else(|e| e.into_inner());
        let u = all.entry(name.to_string()).or_insert(UpdateStatus { state: "running", steps: vec![], detail: String::new() });
        u.state = state;
        if let Some(s) = step {
            u.steps.push(s.to_string());
        }
        drop(all);
        if let Some(s) = step {
            self.log_step(name, "update", "update runner", s, None, if state == "failed" { detail } else { "" });
        }
        let mut all = self.updates.lock().unwrap_or_else(|e| e.into_inner());
        let u = all.entry(name.to_string()).or_insert(UpdateStatus { state: "running", steps: vec![], detail: String::new() });
        if !detail.is_empty() {
            u.detail = detail.to_string();
        }
    }

    /// Update runner `name`, answered at once; the work runs on its own thread (poll `update_status`). A runner in a
    /// Docker container on this machine is recreated on this app's image tag; any other gets the command to run there.
    pub fn update(self: &Arc<Self>, name: &str) -> Result<String, String> {
        let r = self.store.runner(name).ok_or_else(|| format!("no runner named '{name}'"))?;
        if self.update_status(name).is_some_and(|u| u.state == "running") {
            return Err(format!("runner '{name}' is already updating"));
        }
        let host_port = r.url.split("://").nth(1).unwrap_or(&r.url).split('/').next().unwrap_or("");
        let (host, port) = host_port.rsplit_once(':').map(|(h, p)| (h.trim_matches(|c| c == '[' || c == ']'), p.parse::<u16>().ok())).unwrap_or((host_port, None));
        let local = matches!(host, "127.0.0.1" | "localhost" | "::1");
        let docker = self.docker.lock().unwrap_or_else(|e| e.into_inner()).clone();
        let found = match (local, port) {
            (true, Some(p)) => update::find_container(&*docker, p).map_err(|e| format!("docker: {e}"))?,
            _ => None,
        };
        let Some(inspect) = found else {
            return Ok(format!(
                "runner '{name}' is not a Docker container on this machine. On the machine it runs on, pull the app's image and recreate the container with the same name, volumes, ports and env: `docker pull <image>:{v}` then `docker rm -f <container>` and the same `docker run ... <image>:{v}`.",
                v = self.app_version
            ));
        };
        let plan = update::plan_from_inspect(&inspect, &self.app_version)?;
        self.updates.lock().unwrap_or_else(|e| e.into_inner()).insert(name.to_string(), UpdateStatus { state: "running", steps: vec![], detail: String::new() });
        let image = plan.image.clone();
        let (pool, name, dir) = (self.clone(), name.to_string(), self.store_dir());
        let _ = std::thread::Builder::new().name("runner-update".into()).spawn(move || {
            // The env goes through a file (it may hold secrets), removed when the work ends.
            let env_path = dir.join(format!("update-{}.env", std::process::id()));
            let have_env = !plan.env.is_empty() && std::fs::write(&env_path, plan.env.join("
")).is_ok();
            let wait = Duration::from_secs(pool.update_wait.get().map(|f| f()).filter(|n| *n > 0).unwrap_or(update::DEFAULT_UPDATE_WAIT_SEC));
            let env_arg = env_path.to_string_lossy().to_string();
            let (p2, n2) = (pool.clone(), name.clone());
            let answers = move || matches!(p2.check(&n2).map(|(s, _)| s), Some(RunnerState::Online { .. }));
            let progress = |s: &str| pool.note(&name, "running", Some(s), "");
            let out = update::recreate(&*docker, &plan, have_env.then_some(env_arg.as_str()), wait, &answers, &progress);
            let _ = std::fs::remove_file(&env_path);
            match out {
                Ok(img) => pool.note(&name, "done", Some("done"), &format!("runner '{name}' now runs {img}")),
                Err(e) => pool.note(&name, "failed", Some("failed"), &e),
            }
        });
        Ok(format!("updating runner '{}' to {image}: started", r.name))
    }

    fn store_dir(&self) -> PathBuf {
        self.dir.clone()
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
        let runner: Arc<dyn Runner> = Arc::new(RemoteRunner::new(r.name.clone(), self.client(&r)).with_cursors(self.cursors.clone()));
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

#[cfg(test)]
mod plain_reason_tests {
    use super::plain_reason;

    #[test]
    fn link_errors_become_plain_words_with_a_next_step() {
        assert!(plain_reason("TLS: the certificate is not the pinned one").0.starts_with("TLS handshake failed"));
        assert!(plain_reason("tcp connect: Connection refused (os error 111)").0.contains("runner is stopped"));
        assert_eq!(plain_reason("dns error: failed to lookup address").0, "no route to host");
        assert!(plain_reason("operation timed out").0.contains("did not answer in time"));
        assert!(!plain_reason("weird").1.is_empty());
    }
}
