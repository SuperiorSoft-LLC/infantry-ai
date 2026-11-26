// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Sessions: the lifecycle the v1 sessions frames drive (`prompt`, `interrupt`, `kill`, `session_delete`, `sessions`),
//! the frames it pushes, and the store. Vendor-neutral: a provider is a [`Harness`] behind the trait.
//!
//! Lock order: the session list, then one session, then the record cache. Nothing takes the list while holding a session.

use crate::harness::{EventSink, Harness, HarnessEvent, HarnessSession, OpenRequest, Turn, TurnEnd};
use crate::board::Board;
use crate::held;
use crate::store::JsonSessionStore;
use crate::wire;
use infantry_protocol::session::{AgentWatch, KillOutcome, QueuedTurn, SessionRecord, SessionState};
use infantry_protocol::agents::{AgentListRequest, SessionSwap};
use infantry_protocol::Envelope;
use serde::Deserialize;
use serde_json::json;
use std::collections::{BTreeMap, VecDeque};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};
use tokio::sync::broadcast;

mod assistant;
pub mod workspace;
mod lifecycle;
pub use workspace::{WorkspaceSpec, KEEP_KEY as WORKSPACE_KEEP_KEY, ROOT_KEY as WORKSPACE_ROOT_KEY};
pub use infantry_projects::store::HOME_ROOT_ENV;
pub use assistant::{AssistantSpec, DIR as ASSISTANT_DIR, ROLE as ASSISTANT_ROLE, ROLE_KEY as ASSISTANT_ROLE_KEY};
pub use lifecycle::{waiting_line, evaluate, apply_blocked, Pending, Stats, WatchConfig, WatchPorts, CARRY_MAX_CHARS};
use infantry_protocol::frames as pf;

/// The inbound frame types this unit serves; the strangler gateway routes these here (with `agent_list`, read from the
/// db until the agents unit moves).
pub fn owns(ty: &str) -> bool {
    matches!(
        ty,
        wire::SESSIONS
            | wire::AGENT_LIST
            | wire::NEW
            | wire::REPLAY_REQUEST
            | wire::PROMPT
            | wire::QUEUE_ADD
            | wire::QUEUE_REMOVE
            | wire::QUEUE_FORCE
            | wire::INTERRUPT
            | wire::KILL
            | wire::SESSION_DELETE
            | pf::SESSION_RESTART
            | pf::SESSION_CONTEXT_LIMIT
            | pf::WAITING
            | pf::SESSION_SWAP
            | pf::SESSION_STATS_GET
            | pf::SESSION_MCP_RECONNECT
            | pf::COMMAND_CATALOG
            | pf::ASSISTANT
    )
}

/// Frames buffered per connection before a slow client starts missing them.
pub const BROADCAST_CAPACITY: usize = 4096;
/// After a kill the run activity settles to `idle` on the next watch tick (Kotlin's `AgentWatchdog`), not at once.
pub const KILL_SETTLE_MS: u64 = 1_500;
/// `limits.ws.frameDetachMs` default: a unit frame still running after this long stops holding up the frames behind it on
/// its connection (the board's `ticket_list` waited 5 s behind a `harness_install_status` probe); its reply goes out when it ends.
pub const FRAME_DETACH_MS: u64 = 150;
/// `limits.chat.queueMax` default (CHAT-QUEUE-1): messages a session holds while a turn runs.
pub const QUEUE_MAX: usize = 20;
/// `limits.chat.sentIdsKept` default (CHAT-SENDING-1): client message ids remembered daemon-wide.
pub const SENT_IDS_KEPT: usize = 500;
/// Frames kept per session for `replay_request` (a reconnecting or late client rebuilds the transcript from them).
pub const REPLAY_KEPT: usize = 5_000;

/// Spawns a background task from any thread. `tokio::spawn` panics with "there is no reactor running" on a thread that
/// is not inside the runtime (a std thread, a script hook, a plugin host thread) and the session-open path ran into
/// exactly that (RUST-BUG 2617b342); this runs the task on the current runtime when there is one, else on a small
/// fallback runtime owned by the process.
pub fn spawn_bg<F>(fut: F)
where
    F: std::future::Future<Output = ()> + Send + 'static,
{
    static FALLBACK: std::sync::OnceLock<Option<tokio::runtime::Runtime>> = std::sync::OnceLock::new();
    if let Ok(h) = tokio::runtime::Handle::try_current() {
        h.spawn(fut);
        return;
    }
    let rt = FALLBACK.get_or_init(|| tokio::runtime::Builder::new_multi_thread().worker_threads(1).thread_name("infantry-bg").enable_all().build().ok());
    match rt {
        Some(rt) => {
            rt.spawn(fut);
        }
        None => eprintln!("[infantry] no runtime for a background task; it was dropped"),
    }
}

pub fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

pub struct Config {
    pub state_dir: PathBuf,
    /// Where a session runs when its `new` names no folder (Kotlin: the daemon's working directory).
    pub default_cwd: PathBuf,
    pub kill_settle_ms: u64,
    /// `limits.ws.frameDetachMs`: how long a unit frame may hold its connection's frame loop before later frames pass it.
    pub frame_detach_ms: u64,
    /// `limits.chat.queueMax`.
    pub queue_max: usize,
    /// `limits.chat.sentIdsKept`.
    pub sent_ids_kept: usize,
    /// Frames kept per session for `replay_request`.
    pub replay_kept: usize,
    /// The agent-CLI harnesses this daemon can run (built by the composition root; empty = only the one `Daemon::new` got).
    pub registry: crate::harness::HarnessRegistry,
}

impl Config {
    /// The defaults Kotlin ships with.
    pub fn new(state_dir: PathBuf, default_cwd: PathBuf) -> Config {
        Config { state_dir, default_cwd, kill_settle_ms: KILL_SETTLE_MS, frame_detach_ms: FRAME_DETACH_MS, queue_max: QUEUE_MAX, sent_ids_kept: SENT_IDS_KEPT, replay_kept: REPLAY_KEPT, registry: Default::default() }
    }
}

/// The default when no setting resolves (the setting's own default, 500k).
const CONTEXT_FALLBACK: i32 = 500_000;
/// The refusal text for a limit outside the range.
pub const CONTEXT_RANGE: &str = "Use 10k to 10M, or off.";
/// A context limit as a pin may carry it: 0 (off) or 10,000 to 10,000,000 tokens; anything else is refused, never clamped.
pub fn check_context_limit(n: i64) -> Result<i32, String> {
    if n == 0 || (10_000..=10_000_000).contains(&n) {
        Ok(n as i32)
    } else {
        Err(CONTEXT_RANGE.to_string())
    }
}

/// Resolves the default context limit of a session with no pin: `(project, role)` to `(tokens, source)`, 0 tokens = off.
pub type ContextDefault = Arc<dyn Fn(Option<&str>, Option<&str>) -> Option<(i32, String)> + Send + Sync>;

/// What a `new` frame may carry (JSON data, every field optional).
#[derive(Debug, Default, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NewOpts {
    pub cwd: Option<String>,
    pub model: Option<String>,
    pub effort: Option<String>,
    pub role: Option<String>,
    /// A display name; without a role it becomes the role (lowercased), as the app's embedded runner does.
    pub name: Option<String>,
    /// A first turn to send at once (the app's `spawnWith({prompt})`); without it the session waits at rest.
    pub prompt: Option<String>,
    /// The harness to run (a registered provider name); None = the daemon's default.
    pub provider: Option<String>,
    // Set by a board start (the work half), never by a client's `new`:
    #[serde(skip)]
    pub ticket_id: Option<String>,
    #[serde(skip)]
    pub project_id: Option<String>,
    #[serde(skip)]
    pub agent_id: Option<String>,
    /// The most context (tokens) the session may hold; 0 = off. None = the setting's default applies.
    pub context_limit: Option<i32>,
    /// RR-4: where the session runs: a runner name, `local` or `any`. A start that names a runner never falls back to local.
    pub runner: Option<String>,
    #[serde(skip)]
    pub unattended: bool,
    #[serde(skip)]
    pub spawner: Option<String>,
    /// (branch, base, repo root) of the worktree in `cwd`.
    #[serde(skip)]
    pub worktree: Option<(String, String, String)>,
    /// RR-7: the folder to run in ON the placed (remote) runner: a ticket worktree or the project's clone there.
    #[serde(skip)]
    pub runner_cwd: Option<String>,
}

/// What other units learn of a session's life (usage metering, mail wakes, the board's done protocol). Called with
/// the session's lock held: an observer must not call back into the session manager on the same thread.
pub trait SessionObserver: Send + Sync {
    /// A turn started with `text`.
    fn on_turn(&self, _rec: &SessionRecord, _text: &str) {}
    /// One raw provider stdout line.
    fn on_stdout(&self, _rec: &SessionRecord, _line: &str) {}
    /// The turn ended: `outcome` is a `FinishedPayload.Outcome` name (SUCCEEDED, FAILED, KILLED_*).
    fn on_turn_end(&self, _rec: &SessionRecord, _outcome: &str, _detail: &str) {}
    /// The session's process is gone (killed, deleted, crashed or exited).
    fn on_exit(&self, _rec: &SessionRecord) {}
    /// PIPE-RESTART-1: the process died (a non-zero exit code, or none) while NO turn was running, so no turn end tells
    /// it. Not called for a stop the daemon asked for (that session is already Exited).
    fn on_rest_exit(&self, _rec: &SessionRecord, _code: Option<i32>) {}
}

/// The other frame units beside the session manager (the composition root's registry): the server sends a frame
/// there when the session manager does not own it.
/// Which MCP servers beyond the daemon's own a session is handed (walled by default: only granted ones).
pub trait McpSelector: Send + Sync {
    fn select(&self, rec: &SessionRecord, plan: &mut infantry_harness::McpPlan);
}

/// FRAME-REFUSAL-WORDING-1: the user-facing refusal for a frame nothing serves: it names the plugin that would serve
/// it when the catalog knows one, else says the feature is missing from this version.
pub fn unserved_refusal(ty: &str, provider: Option<&str>) -> String {
    match provider.map(str::trim).filter(|p| !p.is_empty()) {
        Some(p) => {
            let words = ty.replace(['_', '.', '-'], " ");
            let mut c = words.trim().chars();
            let feature = c.next().map(|f| f.to_uppercase().collect::<String>() + c.as_str()).unwrap_or_else(|| "This feature".into());
            format!("{feature} needs the {p} plugin. Turn it on in Plugins.")
        }
        None => "This feature isn't available on this PC's Infantry AI version.".into(),
    }
}

/// HARNESS-OVERRIDE-USE-1: the program a session of harness `id` runs, from the owner's saved location `saved`: None = no
/// override (the harness's own lookup); a saved path that is not an existing file is refused, naming it.
pub(crate) fn saved_program(id: &str, saved: Option<String>) -> Result<Option<String>, String> {
    match saved {
        None => Ok(None),
        Some(p) if std::path::Path::new(&p).is_file() => Ok(Some(p)),
        Some(p) => Err(format!("the saved location for {id} doesn't exist: {p}; fix it in Settings")),
    }
}

#[cfg(test)]
mod saved_program_tests {
    use super::saved_program;

    #[test]
    fn no_override_is_unchanged_a_good_one_is_used_and_a_missing_one_is_refused() {
        assert_eq!(saved_program("claude", None), Ok(None));
        let exe = std::env::temp_dir().join(format!("saved-cli-{}.exe", uuid::Uuid::new_v4()));
        std::fs::write(&exe, b"x").unwrap();
        let p = exe.to_string_lossy().into_owned();
        assert_eq!(saved_program("claude", Some(p.clone())), Ok(Some(p)));
        let _ = std::fs::remove_file(&exe);
        let e = saved_program("claude", Some("/nope/claude.exe".into())).unwrap_err();
        assert_eq!(e, "the saved location for claude doesn't exist: /nope/claude.exe; fix it in Settings");
    }
}

#[cfg(test)]
mod unserved_refusal_tests {
    use super::unserved_refusal;

    #[test]
    fn names_the_plugin_when_known() {
        assert_eq!(unserved_refusal("image_upload", Some("Infantry Chat")), "Image upload needs the Infantry Chat plugin. Turn it on in Plugins.");
    }

    #[test]
    fn says_unavailable_when_no_plugin_serves_it() {
        let m = unserved_refusal("assistant", None);
        assert_eq!(m, "This feature isn't available on this PC's Infantry AI version.");
        assert!(!m.contains("does not serve") && !m.contains("refused"));
    }
}

pub trait FrameUnits: Send + Sync {
    fn owns(&self, ty: &str) -> bool;
    /// One frame from a client (`owner` = it holds the owner credential); the encoded replies for that client.
    /// Blocking: call it on the blocking pool.
    fn handle(&self, env: Envelope, owner: bool) -> Vec<String>;
    /// Frames for a new connection's burst, after the session manager's.
    fn hello(&self) -> Vec<String>;
    /// HTTP routes the units serve beside `/ws` (behind the same access policy).
    fn http(&self) -> Option<axum::Router>;
    /// A route a plugin declares (`daemon.routes` / `daemon.publicRoutes`): Some(public) when one matches.
    fn route(&self, _method: &str, _path: &str) -> Option<bool> {
        None
    }
    /// FRAME-REFUSAL-WORDING-1: the display name of the plugin whose manifest declares frame `ty` (it is off or not
    /// loaded when nothing serves the frame), if any plugin in the catalog does.
    fn frame_provider(&self, _ty: &str) -> Option<String> {
        None
    }
    /// Serves a declared plugin route: (status, headers, body). Blocking: call it on the blocking pool.
    fn serve_route(&self, _req: RouteCall) -> (u16, Vec<(String, String)>, Vec<u8>) {
        (404, Vec::new(), b"no such route".to_vec())
    }
}

/// One HTTP request to a plugin route.
#[derive(Debug, Clone, Default)]
pub struct RouteCall {
    pub method: String,
    pub path: String,
    pub query: String,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
}

/// Per connection: a bare `new` waiting for its first prompt, or the session a JSON `new` bound (BIND-AT-CREATION).
#[derive(Default)]
pub struct Conn {
    pub pending_new: Option<NewOpts>,
    /// The session the last JSON `new` created: a session-less `prompt` on this connection goes into it.
    pub active: Option<String>,
    /// The sessions whose MCP bridge called on this connection (dropped when it closes).
    pub bridges: Vec<String>,
}

struct Entry {
    rec: SessionRecord,
    seq: i64,
    hs: Option<Box<dyn HarnessSession>>,
    turn_active: bool,
    turn_started: i64,
    /// Bumped by each kill and turn: a delayed settle that sees another value is stale.
    gen: u64,
    removed: bool,
    /// (sequence, encoded frame) of the last [Config::replay_kept] frames.
    history: std::collections::VecDeque<(i64, String)>,
    /// Lines in the on-disk copy of `history` (`frame_log`).
    logged: usize,
    /// HARNESS-CARRY-1: the earlier conversation, sent with the next first turn (after a swap across harness or role).
    carry: Option<String>,
    /// What the harness reported (`session_stats`).
    stats: Stats,
    /// The watchdog's current episode.
    episode: Option<lifecycle::Episode>,
    /// The done-worker reaper waits for this turn's end.
    reap_pending: bool,
    /// A turn ended cleanly while background tasks ran: its done report is held until they all end.
    bg_done_held: bool,
    /// The watchdog saw this turn silent past `session.hungKillMinutes`: the child is hung (cleared by any output).
    hung: bool,
}

impl Entry {
    fn new(rec: SessionRecord, turn_active: bool, turn_started: i64) -> Entry {
        Entry {
            rec,
            seq: 0,
            hs: None,
            turn_active,
            turn_started,
            gen: 0,
            removed: false,
            history: Default::default(),
            logged: 0,
            carry: None,
            stats: Stats::default(),
            episode: None,
            reap_pending: false,
            bg_done_held: false,
            hung: false,
        }
    }
}

pub struct Daemon {
    pub harness: Arc<dyn Harness>,
    pub cfg: Config,
    pub instance: String,
    out: broadcast::Sender<String>,
    /// (id, session): the id beside it so a lookup locks no session.
    list: Mutex<Vec<(String, Arc<Mutex<Entry>>)>>,
    cache: Mutex<Vec<SessionRecord>>,
    dirty: AtomicBool,
    /// RR-8: sessions that were on a remote runner mid-turn when the daemon stopped (see `reattach_placed`).
    reattach: Mutex<Vec<String>>,
    store: Mutex<JsonSessionStore>,
    /// CHAT-SENDING-1: `<session>\n<send:id>` of the prompts taken, oldest first.
    sent: Mutex<std::collections::VecDeque<String>>,
    /// Projects and agent profiles, read-only from the state dir's db (RUST-MIGRATE-1).
    board: Board,
    /// The app this state dir was migrated from, when it was (RUST-MIGRATE-1).
    source: Option<held::Source>,
    /// Session id -> the app running it now: listed as `heldBy`, a turn into it refused.
    held: Mutex<BTreeMap<String, String>>,
    /// The other harnesses by provider name; a session runs its record's provider ([`Daemon::harness`] is the default).
    harnesses: Mutex<Vec<Arc<dyn Harness>>>,
    observers: Mutex<Vec<Arc<dyn SessionObserver>>>,
    units: std::sync::OnceLock<Arc<dyn FrameUnits>>,
    /// The restart journal, the MCP bridges and the bound port (the `health` frame).
    pub health: Arc<crate::health::Health>,
    /// What every agent process is told about this daemon (set once the port is bound).
    launch: Mutex<crate::harness::launch::LaunchBase>,
    env_extra: std::sync::OnceLock<Arc<dyn Fn() -> Vec<(String, String)> + Send + Sync>>,
    /// The env one provider's sessions get on top (its stored key or subscription token, the API key variable blanked).
    provider_env: std::sync::OnceLock<Arc<dyn Fn(&str) -> Vec<(String, String)> + Send + Sync>>,
    /// HARNESS-OVERRIDE-USE-1: the owner's saved CLI location for a harness id (`cli.executable.<id>`), None = not set.
    cli_override: std::sync::OnceLock<Arc<dyn Fn(&str) -> Option<String> + Send + Sync>>,
    /// The default context limit for a session the spawn did not pin: `(project, role)` to `(tokens, source)`; 0 tokens = off.
    context_default: std::sync::OnceLock<ContextDefault>,
    /// RR-6: what a start on a REMOTE runner lends `(runner, provider, project)`; unset = nothing is lent.
    lender: std::sync::OnceLock<Arc<crate::lender::Lender>>,
    /// RR-7: a ticket's workspace on a remote runner (set by the runners plugin at the composition root).
    remote_ws: std::sync::OnceLock<Arc<dyn infantry_runner_source::remote::RemoteWorkspace>>,
    /// RR-6b: the per-session tokens of the tunnel listener.
    pub tunnel: Arc<crate::tunnel::TunnelGate>,
    /// Whether a role exists (`session_swap` to a role); unset = any well-formed role.
    role_known: std::sync::OnceLock<Arc<dyn Fn(&str) -> bool + Send + Sync>>,
    /// PM-ROLE-1: the text a new conversation of a role starts with (the role catalog's persona); unset = none.
    role_brief: std::sync::OnceLock<Arc<dyn Fn(&str) -> Option<String> + Send + Sync>>,
    /// Where tool approvals and questions go (the owner's prompt cards); unset = sessions run with allow-all.
    prompter: std::sync::OnceLock<Arc<dyn crate::harness::Prompter>>,
    /// NEEDS-INPUT-1: where the session's named hook events (`session.waiting_on` ...) go (the core event bus).
    events: std::sync::OnceLock<Arc<dyn Fn(&str, serde_json::Value) + Send + Sync>>,
    mcp: std::sync::OnceLock<Arc<dyn McpSelector>>,
    /// ASSISTANT-1: the role the Assistant plays and what it runs on (the role binding); unset = the default role and harness.
    assistant_spec: std::sync::OnceLock<Arc<dyn Fn() -> AssistantSpec + Send + Sync>>,
    /// ASSISTANT-1: held across "is there an Assistant?" and its creation, so the installation never gets two.
    assistant_lock: Mutex<()>,
    /// PM-ENFORCE-1: (role, project) -> the role-policy env pair a session of that role starts with.
    role_policy: std::sync::OnceLock<Arc<dyn Fn(Option<&str>, Option<&str>) -> Option<(String, String)> + Send + Sync>>,
    workspace_spec: std::sync::OnceLock<Arc<dyn Fn() -> Option<WorkspaceSpec> + Send + Sync>>,
    /// RR-4: the runners a session can be placed on, by name (`local` built in; the composition root adds the paired ones).
    runners: std::sync::OnceLock<Arc<infantry_runner_source::RunnerRegistry>>,
    /// UPDATE-LIVE-1: no new turn starts (an update is being prepared); a turn into a session at rest waits in its queue.
    turns_paused: AtomicBool,
}

/// RR-6b: one encoded frame with no session or sequence (the tunnel's hello).
pub fn encode_frame(ty: &str, data: String) -> String {
    encode(ty, data, None, None)
}

fn encode(ty: &str, data: String, session: Option<&str>, seq: Option<i64>) -> String {
    encode_req(ty, data, session, seq, None)
}

fn encode_req(ty: &str, data: String, session: Option<&str>, seq: Option<i64>, request: Option<&str>) -> String {
    let mut e = Envelope::new(ty, data);
    e.session_id = session.map(String::from);
    e.sequence = seq;
    e.request_id = request.map(String::from);
    serde_json::to_string(&e).unwrap_or_default()
}

/// Why a frame was refused: a plain reason (an `error` on the frame's session), or the typed `run_in_flight` (session-less).
enum Refusal {
    Plain(String),
    InFlight(String),
}

impl From<String> for Refusal {
    fn from(s: String) -> Self {
        Refusal::Plain(s)
    }
}
impl From<&str> for Refusal {
    fn from(s: &str) -> Self {
        Refusal::Plain(s.into())
    }
}

/// CHAT-GONE-1: a send into a session that is gone, refused typed (`session_gone <id>: ...`) so a client can say so in
/// words; one daemon log line per refusal (the session and the reason, never the message text).
fn gone_refusal(verb: &str, sid: &str) -> String {
    let reason = format!("{verb} refused: unknown session {sid}");
    eprintln!("[infantry] send refused: session {sid} is gone ({reason})");
    format!("{} {sid}: {reason}", wire::SESSION_GONE)
}

/// RR-8: how often and how long a restarted daemon tries to reach a remote runner to re-attach to a session of it.
const REATTACH_TRIES: u32 = 40;
const REATTACH_WAIT: Duration = Duration::from_secs(3);
/// RR-8: a re-attached process silent this long has finished its turn.
const REATTACH_QUIET: Duration = Duration::from_secs(45);

impl Daemon {
    /// Opens the store in the state dir; sessions that were running when the daemon stopped come back at rest.
    pub fn new(harness: Arc<dyn Harness>, cfg: Config) -> Result<Arc<Daemon>, String> {
        let mut store = JsonSessionStore::new(&cfg.state_dir);
        let loaded = match store.load() {
            Ok(l) => l,
            Err(e) => {
                eprintln!("[infantry] session store unreadable, starting with no sessions: {e}");
                Vec::new()
            }
        };
        let (out, _) = broadcast::channel(BROADCAST_CAPACITY);
        let mut list = Vec::new();
        let mut cache = Vec::new();
        let mut reattach = Vec::new();
        for mut rec in loaded {
            // RR-8: a remote runner's process outlives this daemon's stop, so its session is re-attached, not reset.
            if rec.runner != infantry_runner_source::LOCAL && rec.runner_proc.is_some() && (rec.is_busy() || rec.is_process_alive) {
                reattach.push(rec.id.clone());
            }
            if rec.is_busy() {
                rec.state = if rec.provider_session_id.is_some() { SessionState::Idle } else { SessionState::Exited };
                rec.last_action = Some(format!("state {}", state_word(rec.state)));
            }
            rec.is_process_alive = false;
            rec.child_pid = None;
            cache.push(rec.clone());
            let mut entry = Entry::new(rec, false, 0);
            entry.stats = Stats::load(&cfg.state_dir, &entry.rec.id);
            entry.history = crate::frame_log::load(&cfg.state_dir, &entry.rec.id, cfg.replay_kept);
            entry.logged = entry.history.len();
            entry.seq = entry.history.back().map(|h| h.0).unwrap_or(0);
            list.push((entry.rec.id.clone(), Arc::new(Mutex::new(entry))));
        }
        let cfg_dir = cfg.state_dir.clone();
        let d = Arc::new(Daemon {
            harness,
            cfg,
            instance: uuid::Uuid::new_v4().to_string(),
            out,
            list: Mutex::new(list),
            cache: Mutex::new(cache),
            dirty: AtomicBool::new(false),
            reattach: Mutex::new(reattach),
            store: Mutex::new(store),
            sent: Mutex::new(Default::default()),
            board: Board::new(&cfg_dir),
            source: held::Source::of(&cfg_dir),
            held: Mutex::new(BTreeMap::new()),
            harnesses: Mutex::new(Vec::new()),
            observers: Mutex::new(Vec::new()),
            units: std::sync::OnceLock::new(),
            health: Arc::new(crate::health::Health::new(&cfg_dir)),
            launch: Mutex::new(Default::default()),
            env_extra: std::sync::OnceLock::new(),
            provider_env: std::sync::OnceLock::new(),
            cli_override: std::sync::OnceLock::new(),
            context_default: std::sync::OnceLock::new(),
            lender: std::sync::OnceLock::new(),
            remote_ws: std::sync::OnceLock::new(),
            tunnel: Arc::default(),
            role_known: std::sync::OnceLock::new(),
            role_brief: std::sync::OnceLock::new(),
            prompter: std::sync::OnceLock::new(),
            events: std::sync::OnceLock::new(),
            mcp: std::sync::OnceLock::new(),
            assistant_spec: std::sync::OnceLock::new(),
            assistant_lock: Mutex::new(()),
            role_policy: std::sync::OnceLock::new(),
            workspace_spec: std::sync::OnceLock::new(),
            runners: std::sync::OnceLock::new(),
            turns_paused: AtomicBool::new(false),
        });
        d.refresh_held();
        Ok(d)
    }

    /// Re-reads which sessions the app this dir was migrated from is running (no-op for a dir that is not a copy).
    /// When that changed, the records carry the new `heldBy` and every client gets a fresh `sessions`.
    pub fn refresh_held(&self) -> bool {
        let Some(src) = &self.source else { return false };
        let Some(now) = src.held() else { return false };
        {
            let mut held = self.held.lock().unwrap();
            if *held == now {
                return false;
            }
            *held = now.clone();
        }
        let entries: Vec<_> = self.list.lock().unwrap().iter().map(|(_, e)| e.clone()).collect();
        for e in entries {
            let mut e = e.lock().unwrap();
            e.rec.held_by = now.get(&e.rec.id).cloned();
        }
        for r in self.cache.lock().unwrap().iter_mut() {
            r.held_by = now.get(&r.id).cloned();
        }
        self.broadcast(wire::SESSIONS, self.sessions_json());
        true
    }

    /// `Err` with the refusal when another app is running this session.
    fn not_held(&self, verb: &str, sid: &str) -> Result<(), String> {
        match self.held.lock().unwrap().get(sid) {
            Some(app) => Err(format!("{verb} refused: session {sid} is live in {app}; stop it there first (one session never runs in two apps)")),
            None => Ok(()),
        }
    }

    /// Registers another harness (a provider a session or a board start may name); the same name is replaced.
    pub fn add_harness(&self, h: Arc<dyn Harness>) {
        let mut hs = self.harnesses.lock().unwrap();
        hs.retain(|x| x.name() != h.name());
        hs.push(h);
    }

    /// The harness for `provider` (the default one when it names none).
    pub fn harness_for(&self, provider: Option<&str>) -> Option<Arc<dyn Harness>> {
        match provider.map(str::trim).filter(|p| !p.is_empty()) {
            None => Some(self.harness.clone()),
            Some(p) if p == self.harness.name() => Some(self.harness.clone()),
            Some(p) => self.harnesses.lock().unwrap().iter().find(|h| h.name() == p).cloned(),
        }
    }

    /// The harness registry the composition root passed in.
    pub fn registry(&self) -> &crate::harness::HarnessRegistry {
        &self.cfg.registry
    }

    /// The provider names this daemon can run, the default first.
    pub fn providers(&self) -> Vec<String> {
        let mut out = vec![self.harness.name().to_string()];
        out.extend(self.harnesses.lock().unwrap().iter().map(|h| h.name().to_string()).filter(|n| n != self.harness.name()));
        out
    }

    pub fn observe(&self, o: Arc<dyn SessionObserver>) {
        self.observers.lock().unwrap().push(o);
    }

    /// Attaches the other frame units (once).
    pub fn set_units(&self, u: Arc<dyn FrameUnits>) {
        let _ = self.units.set(u);
    }

    pub fn units(&self) -> Option<Arc<dyn FrameUnits>> {
        self.units.get().cloned()
    }

    /// Whether this daemon answers `ty` itself (the session manager or a registered unit); the gateway routes the
    /// rest to the legacy backend.
    pub fn owns_frame(&self, ty: &str) -> bool {
        owns(ty) || self.units.get().is_some_and(|u| u.owns(ty))
    }

    fn notify(&self, f: impl Fn(&dyn SessionObserver)) {
        let obs: Vec<_> = self.observers.lock().unwrap().clone();
        for o in obs {
            f(&*o);
        }
    }

    /// Pushes an already encoded frame to every client (the bridge from the core event bus).
    pub fn publish_raw(&self, frame: String) {
        let _ = self.out.send(frame);
    }

    /// Every session record now.
    pub fn records(&self) -> Vec<SessionRecord> {
        self.cache.lock().unwrap().clone()
    }

    pub fn record(&self, id: &str) -> Option<SessionRecord> {
        self.cache.lock().unwrap().iter().find(|r| r.id == id).cloned()
    }

    /// UPDATE-LIVE-1: while paused no new turn starts: a prompt, mail or board start into a session at rest is held in that
    /// session's queue (stored with the session, so it survives a restart). Turns already running go on. Unpausing sends
    /// every held message (one per session, the rest after each turn as usual).
    pub fn pause_turns(self: &Arc<Self>, on: bool) {
        let was = self.turns_paused.swap(on, Ordering::SeqCst);
        if was && !on {
            self.send_held();
        }
    }

    pub fn turns_paused(&self) -> bool {
        self.turns_paused.load(Ordering::SeqCst)
    }

    /// The sessions with a turn running now.
    pub fn running_turns(&self) -> Vec<String> {
        let entries: Vec<(String, Arc<Mutex<Entry>>)> = self.list.lock().unwrap().iter().map(|(id, e)| (id.clone(), e.clone())).collect();
        entries.into_iter().filter(|(_, e)| e.lock().map(|e| !e.removed && e.turn_active).unwrap_or(false)).map(|(id, _)| id).collect()
    }

    /// Every session at rest with held messages takes the oldest as its next turn. Returns those sessions.
    pub fn send_held(self: &Arc<Self>) -> Vec<String> {
        let entries: Vec<(String, Arc<Mutex<Entry>>)> = self.list.lock().unwrap().iter().map(|(id, e)| (id.clone(), e.clone())).collect();
        let mut sent = vec![];
        for (id, e) in entries {
            let waiting = e.lock().map(|e| !e.removed && !e.turn_active && e.rec.state != SessionState::Exited && !e.rec.queued.is_empty()).unwrap_or(false);
            if waiting {
                self.drain_later(&id);
                sent.push(id);
            }
        }
        sent
    }

    /// Whether a turn of `id` is running now (None = no such session).
    pub fn turn_active(&self, id: &str) -> Option<bool> {
        self.find(id).map(|e| e.lock().unwrap().turn_active)
    }

    /// NEEDS-INPUT-2: a running session that waits on an owner answer (a tool approval or question card) is WAITING_INPUT
    /// while the card is open, and BUSY again when it closes; pushed, so every client's counters see it. Only a running
    /// turn is held: a session at rest, exited or failed keeps its state. Returns whether the state changed.
    pub fn set_answer_hold(&self, id: &str, on: bool) -> bool {
        let Some(entry) = self.find(id) else { return false };
        let mut e = entry.lock().unwrap();
        let next = match (on, e.rec.state) {
            (true, SessionState::Busy | SessionState::Starting) => SessionState::WaitingInput,
            (false, SessionState::WaitingInput) => SessionState::Busy,
            _ => return false,
        };
        Self::set_state(&mut e, next);
        self.push(&mut e);
        let st = state_word(next).to_ascii_uppercase();
        self.emit(&mut e, wire::STATUS, wire::status(&st));
        true
    }

    /// Creates a session at rest (running `opts.prompt` as its first turn when set) and returns its id: the board's
    /// session launcher uses this; a client sends `new`.
    pub fn launch(self: &Arc<Self>, opts: NewOpts) -> Result<String, String> {
        let mut conn = Conn::default();
        self.bind_new(opts, &mut conn)?;
        conn.active.ok_or_else(|| "launch failed: no session was created".to_string())
    }

    /// A turn into `id` now when it is at rest, else held in its queue (mail, a board nudge).
    /// OWNER-PRIO-1: daemon input never goes in front of what the owner typed: at rest with an owner prompt waiting in
    /// the queue, it joins the queue behind it and the owner's prompt is the next turn.
    pub fn deliver(self: &Arc<Self>, id: &str, text: &str) -> Result<(), String> {
        self.not_held("prompt", id)?;
        self.enqueue(id, text.to_string(), DELIVERED_PREFIX)
    }

    /// Like [`deliver`](Self::deliver), but a message that needs action now (a worker failed) goes into the receiver's
    /// RUNNING turn when its harness takes input mid-turn, instead of waiting behind it. Returns how it arrived:
    /// `"injected"`, `"turn"` (the session was at rest) or `"queued"` (the harness cannot take it mid-turn).
    pub fn deliver_urgent(self: &Arc<Self>, id: &str, text: &str) -> Result<&'static str, String> {
        self.not_held("prompt", id)?;
        let entry = self.find(id).ok_or_else(|| format!("deliver_urgent refused: unknown session {id}"))?;
        let inject = {
            let mut e = entry.lock().unwrap();
            if text.trim().is_empty() {
                return Err("deliver_urgent refused: nothing to deliver".into());
            }
            if !e.turn_active && !self.turns_paused() {
                self.turn_into(&mut e, text.to_string());
                return Ok("turn");
            }
            if !e.turn_active {
                drop(e);
                self.enqueue(id, text.to_string(), DELIVERED_PREFIX)?;
                return Ok("queued");
            }
            e.hs.as_ref().and_then(|h| h.injector())
        };
        if let Some(inject) = inject {
            if inject(text.to_string()) {
                let mut e = entry.lock().unwrap();
                self.emit(&mut e, wire::TURN, text.to_string());
                return Ok("injected");
            }
        }
        self.enqueue(id, text.to_string(), DELIVERED_PREFIX)?;
        Ok("queued")
    }

    /// Ends `id`'s process (idempotent); `reason` is recorded as who killed it.
    pub fn stop_session(self: &Arc<Self>, id: &str, reason: &str) -> Result<(), String> {
        self.kill(id, false)?;
        if let Some(entry) = self.find(id) {
            let mut e = entry.lock().unwrap();
            e.rec.killed_by = Some(reason.to_string());
            self.push(&mut e);
        }
        Ok(())
    }

    pub fn held_count(&self) -> usize {
        self.held.lock().unwrap().len()
    }

    pub fn subscribe(&self) -> broadcast::Receiver<String> {
        self.out.subscribe()
    }

    /// The connection burst after the handshake: ready, policy, sessions. The composition root adds the other units'
    /// burst frames (worktrees, ...) after these.
    pub fn hello(&self, build: &str) -> Vec<String> {
        vec![
            encode(wire::READY, wire::ready(self.harness.name(), &self.instance, build), None, None),
            encode(wire::POLICY, wire::policy(), None, None),
            encode(wire::SESSIONS, self.sessions_json(), None, None),
        ]
    }

    pub fn session_count(&self) -> usize {
        self.cache.lock().unwrap().len()
    }

    fn sessions_json(&self) -> String {
        serde_json::to_string(&*self.cache.lock().unwrap()).unwrap_or_else(|_| "[]".into())
    }

    /// Writes the store when something changed since the last flush.
    pub fn flush(&self) {
        if !self.dirty.swap(false, Ordering::SeqCst) {
            return;
        }
        let mut snapshot = self.cache.lock().unwrap().clone();
        // `heldBy` is computed per listing, never stored.
        snapshot.iter_mut().for_each(|r| r.held_by = None);
        if let Err(e) = self.store.lock().unwrap().save(&snapshot) {
            // Keep the store dirty so the next flush tries again: queued messages and session state must reach disk once space returns.
            self.dirty.store(true, Ordering::SeqCst);
            eprintln!("[infantry] could not save sessions (will retry, queued messages are only in memory until it works): {e}");
        }
    }

    fn find(&self, id: &str) -> Option<Arc<Mutex<Entry>>> {
        let list = self.list.lock().unwrap();
        list.iter().find(|(i, _)| i == id).map(|(_, e)| e.clone())
    }

    fn emit(&self, e: &mut Entry, ty: &str, data: String) {
        // Every frame of a session is activity: stamp the record and the cached copy the session list serves, so a
        // client that connects later still knows when the agent last moved.
        let now = now_ms();
        e.rec.last_event_at = Some(now);
        if let Some(r) = self.cache.lock().unwrap().iter_mut().find(|r| r.id == e.rec.id) {
            r.last_event_at = Some(now);
        }
        e.seq += 1;
        let f = encode(ty, data, Some(&e.rec.id), Some(e.seq));
        e.history.push_back((e.seq, f.clone()));
        while e.history.len() > self.cfg.replay_kept.max(1) {
            e.history.pop_front();
        }
        e.logged = crate::frame_log::append(&self.cfg.state_dir, &e.rec.id, e.seq, &f, &e.history, e.logged, self.cfg.replay_kept);
        let _ = self.out.send(f);
    }

    /// `replay_request` (`{lastSequence}`): the session's kept frames after that sequence, a `replay_gap` first when some
    /// were dropped. Answers this connection only.
    /// The last `lines` frames of a session's replay buffer (oldest first) and whether older ones were left out;
    /// None for an unknown session.
    pub fn transcript_tail(&self, sid: &str, lines: usize) -> Option<(Vec<serde_json::Value>, bool)> {
        let entry = self.find(sid)?;
        let e = entry.lock().unwrap();
        let skip = e.history.len().saturating_sub(lines);
        let tail = e.history.iter().skip(skip).filter_map(|(_, f)| serde_json::from_str(f).ok()).collect();
        Some((tail, skip > 0))
    }

    fn replay(&self, sid: &str, data: &str) -> Result<Vec<String>, String> {
        let entry = self.find(sid).ok_or_else(|| format!("replay_request refused: unknown session {sid}"))?;
        let last = serde_json::from_str::<serde_json::Value>(data).ok().and_then(|v| v["lastSequence"].as_i64()).unwrap_or(0);
        let e = entry.lock().unwrap();
        let out = replay_frames(&e.history, sid, last);
        Ok(out)
    }

    fn broadcast(&self, ty: &str, data: String) {
        let _ = self.out.send(encode(ty, data, None, None));
    }

    /// Pushes the record as a `session` frame and stores it.
    fn push(&self, e: &mut Entry) {
        let now = now_ms();
        e.rec.last_heartbeat = now;
        e.rec.last_event_at = Some(now);
        // SESSION-GHOST-1: a removed session's late write (a turn end, a status) never puts it back on the list.
        if e.removed {
            return;
        }
        let data = serde_json::to_string(&e.rec).unwrap_or_default();
        self.emit(e, wire::SESSION, data);
        let mut cache = self.cache.lock().unwrap();
        match cache.iter_mut().find(|r| r.id == e.rec.id) {
            Some(r) => *r = e.rec.clone(),
            None => cache.push(e.rec.clone()),
        }
        self.dirty.store(true, Ordering::SeqCst);
    }

    fn set_state(e: &mut Entry, s: SessionState) {
        e.rec.state = s;
        e.rec.last_action = Some(format!("state {}", state_word(s)));
    }

    /// A list frame from the board, or an `error` when the db could not be read.
    fn board_reply(&self, ty: &str, req: Option<&str>, r: Result<serde_json::Result<String>, String>) -> Vec<String> {
        match r {
            Ok(Ok(json)) => vec![encode_req(ty, json, None, None, req)],
            Ok(Err(e)) => vec![encode_req(wire::ERROR, format!("{ty}: {e}"), None, None, req)],
            Err(e) => vec![encode_req(wire::ERROR, format!("{ty}: {e}"), None, None, req)],
        }
    }

    /// One inbound frame; returns the replies for this connection only (session frames go to every connection).
    pub fn handle(self: &Arc<Self>, env: Envelope, conn: &mut Conn) -> Vec<String> {
        let sid = env.session_id.clone().filter(|s| !s.is_empty());
        let req = env.request_id.clone();
        // CHAT-SENDING-1: a `prompt` naming its session with a `send:` id is acknowledged; a resend of a taken id is not run again.
        let send_key = match (&sid, &req) {
            (Some(s), Some(r)) if env.r#type == wire::PROMPT && r.starts_with(wire::CLIENT_SEND_PREFIX) => Some(format!("{s}\n{r}")),
            _ => None,
        };
        if let Some(k) = &send_key {
            if !self.claim_send(k) {
                return vec![encode_req(wire::PROMPT_ACK, "duplicate".into(), sid.as_deref(), None, req.as_deref())];
            }
        }
        let r: Result<(), Refusal> = match env.r#type.as_str() {
            wire::SESSIONS => return vec![encode(wire::SESSIONS, self.sessions_json(), None, None)],
            wire::NEW => {
                conn.active = None;
                conn.pending_new = None;
                if env.data.trim_start().starts_with('{') {
                    // BIND-AT-CREATION (Kotlin): an options object creates the session now, at rest and addressable.
                    match serde_json::from_str::<NewOpts>(&env.data) {
                        Ok(o) => self.bind_new(o, conn).map_err(Refusal::from),
                        Err(e) => Err(format!("new refused: bad options ({e})").into()),
                    }
                } else {
                    Ok(())
                }
            }
            wire::REPLAY_REQUEST => {
                return match self.replay(sid.as_deref().unwrap_or(""), &env.data) {
                    Ok(frames) => frames,
                    Err(msg) => vec![encode_req(wire::ERROR, msg, sid.as_deref(), None, req.as_deref())],
                };
            }
            // Agent profiles from the state dir's db, read-only (RUST-MIGRATE-1). Projects and tickets are their units'.
            wire::AGENT_LIST => {
                let p = serde_json::from_str::<AgentListRequest>(&env.data).ok().and_then(|r| r.project_id);
                return self.board_reply(wire::AGENTS, req.as_deref(), self.board.agents(p.as_deref()).map(|a| serde_json::to_string(&a)));
            }
            wire::PROMPT => match (&sid, conn.active.clone().filter(|a| self.find(a).is_some())) {
                (None, Some(active)) => self.prompt_into(&active, env.data),
                (None, None) => self.prompt_new(conn.pending_new.take().unwrap_or_default(), env.data).map_err(Refusal::from),
                (Some(s), _) => self.not_held("prompt", s).map_err(Refusal::from).and_then(|_| self.prompt_into(s, env.data)),
            },
            wire::QUEUE_ADD => {
                let s = sid.as_deref().unwrap_or("");
                self.not_held("queue_add", s).and_then(|_| self.queue_add(s, env.data)).map_err(Refusal::from)
            }
            wire::QUEUE_REMOVE => self.queue_remove(sid.as_deref().unwrap_or(""), env.data.trim()).map_err(Refusal::from),
            wire::QUEUE_FORCE => {
                let s = sid.as_deref().unwrap_or("");
                self.not_held("queue_force", s).and_then(|_| self.queue_force(s, env.data.trim())).map_err(Refusal::from)
            }
            wire::INTERRUPT => self.interrupt(sid.as_deref().unwrap_or(env.data.trim())).map_err(Refusal::from),
            wire::KILL => {
                let mut words = env.data.split_whitespace();
                let first = words.next().unwrap_or("").to_string();
                let (target, rest): (String, Vec<&str>) = match &sid {
                    Some(s) => (s.clone(), std::iter::once(first.as_str()).chain(words).collect()),
                    None => (first.clone(), words.collect()),
                };
                self.kill(&target, rest.contains(&"force")).map_err(Refusal::from)
            }
            wire::SESSION_DELETE => self.delete(sid.as_deref().unwrap_or(env.data.trim())).map_err(Refusal::from),
            pf::WAITING => {
                let reply = |ty: &str, d: String| vec![encode_req(ty, d, sid.as_deref(), None, req.as_deref())];
                let Some(s) = sid.clone() else { return reply(wire::ERROR, "waiting refused: no session (the flag is for the calling session)".into()) };
                return match serde_json::from_str::<infantry_protocol::session::WaitingRequest>(&env.data).map_err(|e| format!("bad request ({e})")).and_then(|q| self.waiting(&s, &q)) {
                    Ok(w) => reply(pf::WAITING, w.and_then(|w| serde_json::to_string(&w).ok()).unwrap_or_else(|| "null".into())),
                    Err(m) => reply(wire::ERROR, format!("waiting refused: {m}")),
                };
            }
            pf::SESSION_RESTART => {
                let v: serde_json::Value = serde_json::from_str(&env.data).unwrap_or(serde_json::Value::Null);
                let s = v["sessionId"].as_str().map(String::from).or(sid.clone()).unwrap_or_default();
                let force = matches!(&v["force"], serde_json::Value::Bool(true)) || v["force"].as_str() == Some("true");
                return match self.restart(s.trim(), force) {
                    Ok(()) => vec![encode_req(wire::SESSIONS, self.sessions_json(), None, None, req.as_deref())],
                    Err(m) => vec![encode_req(wire::ERROR, format!("session_restart refused: {m}"), sid.as_deref(), None, req.as_deref())],
                };
            }
            pf::SESSION_CONTEXT_LIMIT => {
                let v: serde_json::Value = serde_json::from_str(&env.data).unwrap_or(serde_json::Value::Null);
                // `{"default": true, project?, role?}`: what a new agent gets with no pin (the New agent box shows it)
                if v["default"].as_bool() == Some(true) {
                    let d = self.context_default.get().and_then(|f| f(v["project"].as_str(), v["role"].as_str()));
                    let (n, src) = d.unwrap_or((CONTEXT_FALLBACK, "default".to_string()));
                    return vec![encode_req(pf::SESSION_CONTEXT_LIMIT, json!({"contextLimit": n, "source": src}).to_string(), None, None, req.as_deref())];
                }
                let s = v["sessionId"].as_str().map(String::from).or(sid.clone()).unwrap_or_default();
                // a number of tokens (0 = off), or null / absent = back to the settings' default; anything else is refused
                let limit = match &v["limit"] {
                    serde_json::Value::Null => Ok(None),
                    n => n.as_i64().ok_or_else(|| CONTEXT_RANGE.to_string()).and_then(check_context_limit).map(Some),
                };
                return match limit.and_then(|l| self.set_context_limit(s.trim(), l)) {
                    Ok(effect) => vec![encode_req(pf::SESSION_CONTEXT_LIMIT, effect.to_string(), Some(s.trim()), None, req.as_deref())],
                    Err(m) => vec![encode_req(wire::ERROR, format!("session_context_limit refused: {m}"), sid.as_deref(), None, req.as_deref())],
                };
            }
            pf::SESSION_SWAP => {
                let r = serde_json::from_str::<SessionSwap>(&env.data).map_err(|e| format!("bad request ({e})")).and_then(|q| self.swap(q));
                return match r {
                    Ok(done) => {
                        self.broadcast(pf::SESSION_SWAPPED, serde_json::to_string(&done).unwrap_or_default());
                        Vec::new()
                    }
                    Err(m) => vec![encode_req(wire::ERROR, format!("session_swap refused: {m}"), sid.as_deref(), None, req.as_deref())],
                };
            }
            pf::COMMAND_CATALOG => {
                return vec![match self.command_catalog(&env.data, sid.as_deref()) {
                    Ok(body) => encode_req(pf::COMMAND_CATALOG, body, sid.as_deref(), None, req.as_deref()),
                    Err(m) => encode_req(wire::ERROR, format!("command_catalog refused: {m}"), sid.as_deref(), None, req.as_deref()),
                }];
            }
            pf::SESSION_MCP_RECONNECT => {
                let v: serde_json::Value = serde_json::from_str(&env.data).unwrap_or(serde_json::Value::Null);
                let s = v["sessionId"].as_str().map(String::from).or(sid.clone()).unwrap_or_default();
                return match self.mcp_reconnect(s.trim()) {
                    Ok(body) => vec![encode_req(pf::SESSION_MCP_RECONNECT, body, Some(s.trim()), None, req.as_deref())],
                    Err(m) => vec![encode_req(wire::ERROR, m, sid.as_deref(), None, req.as_deref())],
                };
            }
            pf::SESSION_STATS_GET => {
                let v: serde_json::Value = serde_json::from_str(&env.data).unwrap_or(serde_json::Value::Null);
                let s = v["sessionId"].as_str().map(String::from).or(sid.clone()).unwrap_or_default();
                let s = s.trim();
                let r = if s.is_empty() { Err("sessionId is blank".to_string()) } else { self.stats_json(s) };
                return match r {
                    Ok(body) => vec![encode_req(pf::SESSION_STATS, body, Some(s), None, req.as_deref())],
                    Err(m) => vec![encode_req(wire::ERROR, format!("session_stats_get refused: {m}"), sid.as_deref(), None, req.as_deref())],
                };
            }
            // ASSISTANT-1: the installation-wide Assistant. Answers the sender with its `session` frame, making the session on
            // the first request (the creation itself reaches every client as a `session` push); a refusal says why.
            pf::ASSISTANT => {
                return vec![match self.ensure_assistant() {
                    Ok(rec) => encode_req(wire::SESSION, serde_json::to_string(&rec).unwrap_or_default(), Some(&rec.id), None, req.as_deref()),
                    Err(m) => encode_req(wire::ERROR, m, None, None, req.as_deref()),
                }];
            }
            other => Err(unserved_refusal(other, self.units.get().and_then(|u| u.frame_provider(other)).as_deref()).into()),
        };
        match r {
            Ok(()) => match send_key {
                Some(_) => vec![encode_req(wire::PROMPT_ACK, "accepted".into(), sid.as_deref(), None, req.as_deref())],
                None => Vec::new(),
            },
            Err(refusal) => {
                // A refused prompt is forgotten, so a retry with the same id runs.
                if let Some(k) = &send_key {
                    self.release_send(k);
                }
                match refusal {
                    Refusal::Plain(msg) => vec![encode_req(wire::ERROR, msg, sid.as_deref(), None, req.as_deref())],
                    Refusal::InFlight(msg) => vec![encode_req(wire::ERROR, msg, None, None, req.as_deref())],
                }
            }
        }
    }

    fn claim_send(&self, key: &str) -> bool {
        let mut ids = self.sent.lock().unwrap();
        if ids.iter().any(|k| k == key) {
            return false;
        }
        ids.push_back(key.to_string());
        while ids.len() > self.cfg.sent_ids_kept.max(1) {
            ids.pop_front();
        }
        true
    }

    fn release_send(&self, key: &str) {
        self.sent.lock().unwrap().retain(|k| k != key);
    }

    /// A new session's record (not yet listed).
    fn new_record(&self, opts: &NewOpts, state: &str, verb: &str) -> Result<SessionRecord, String> {
        let id = uuid::Uuid::new_v4().to_string();
        // RR-4: a start that names a runner is placed there or refused loudly; it never runs locally instead. A remote
        // session's folder is a scratch folder ON the runner (the workspace is RR-7), never the orchestrator's working dir.
        let placed = self.place(opts.runner.as_deref(), opts.provider.as_deref(), verb)?;
        let cwd = match &placed {
            Some((_, workdir)) => PathBuf::from(opts.runner_cwd.as_deref().filter(|c| !c.trim().is_empty()).unwrap_or(workdir)),
            None => match self.own_folder(opts, &id)? {
                Some(own) => own,
                None => opts.cwd.as_deref().filter(|c| !c.trim().is_empty()).map(PathBuf::from).unwrap_or_else(|| self.cfg.default_cwd.clone()),
            },
        };
        if placed.is_none() && !cwd.is_dir() {
            return Err(format!("{verb} refused: {} is not a folder", cwd.display()));
        }
        let harness = self.harness_for(opts.provider.as_deref()).ok_or_else(|| {
            format!("{verb} refused: no harness named `{}` (this daemon runs {})", opts.provider.as_deref().unwrap_or(""), self.providers().join(", "))
        })?;
        let now = now_ms();
        let mut rec: SessionRecord = serde_json::from_value(json!({
            "id": id, "provider": harness.name(), "workingDir": cwd.to_string_lossy(),
            "spawnedAt": now, "lastHeartbeat": now, "state": state,
        }))
        .map_err(|e| e.to_string())?;
        let some = |v: &Option<String>| v.clone().filter(|m| !m.trim().is_empty());
        rec.model = some(&opts.model);
        rec.effort = some(&opts.effort);
        rec.role = some(&opts.role).or_else(|| some(&opts.name).map(|n| n.trim().to_lowercase()));
        rec.ticket_id = some(&opts.ticket_id);
        rec.project_id = some(&opts.project_id);
        rec.agent_id = some(&opts.agent_id);
        // Order: the spawn's pin (a ticket's, or the New agent field) > the settings (session > role > project > app > default).
        if let Some(n) = opts.context_limit {
            check_context_limit(n as i64)?;
        }
        let (limit, source) = match opts.context_limit {
            Some(n) => (Some(n.max(0)), Some(if rec.ticket_id.is_some() { "ticket" } else { "session" }.to_string())),
            None => match self.context_default.get().and_then(|f| f(rec.project_id.as_deref(), rec.role.as_deref())) {
                Some((n, scope)) => (Some(n.max(0)), Some(scope)),
                None => (None, None),
            },
        };
        let (support, how) = harness.context_limit_support();
        rec.context_limit = limit;
        rec.context_limit_source = source;
        rec.context_limit_support = Some(support.to_string());
        rec.context_limit_how = Some(how.to_string());
        rec.unattended = opts.unattended;
        rec.spawner_session_id = some(&opts.spawner);
        if let Some((name, _)) = &placed {
            rec.runner = name.clone();
        }
        if let (Some((branch, base, root)), true) = (&opts.worktree, placed.is_none() || opts.runner_cwd.is_some()) {
            rec.worktree_path = Some(cwd.to_string_lossy().into_owned());
            rec.worktree_branch = Some(branch.clone());
            rec.worktree_base_ref = Some(base.clone());
            rec.repo_root = Some(root.clone());
        }
        Ok(rec)
    }

    /// Lists a new session at rest and announces it: its `session` frame to every client, then its idle status. Returns its id.
    fn list_idle(&self, mut rec: SessionRecord) -> String {
        let id = rec.id.clone();
        rec.last_action = Some("state idle".into());
        let entry = Arc::new(Mutex::new(Entry::new(rec, false, 0)));
        self.list.lock().unwrap().push((id.clone(), entry.clone()));
        let mut e = entry.lock().unwrap();
        self.push(&mut e);
        self.emit(&mut e, wire::STATUS, wire::status("IDLE"));
        id
    }

    /// A JSON `new`: the session exists now, at rest with no run (`neverRun`); its first prompt is its first run.
    fn bind_new(self: &Arc<Self>, opts: NewOpts, conn: &mut Conn) -> Result<(), String> {
        let rec = self.new_record(&opts, "IDLE", "new")?;
        let id = self.list_idle(rec);
        conn.active = Some(id.clone());
        match opts.prompt.filter(|p| !p.trim().is_empty()) {
            Some(p) => match self.prompt_into(&id, p) {
                Ok(()) => Ok(()),
                Err(Refusal::Plain(m) | Refusal::InFlight(m)) => Err(m),
            },
            None => Ok(()),
        }
    }

    fn prompt_new(self: &Arc<Self>, opts: NewOpts, text: String) -> Result<(), String> {
        let rec = self.new_record(&opts, "BUSY", "prompt")?;
        let (id, now) = (rec.id.clone(), rec.spawned_at);
        let entry = Arc::new(Mutex::new(Entry::new(rec, true, now)));
        self.list.lock().unwrap().push((id.clone(), entry.clone()));
        let mut e = entry.lock().unwrap();
        Self::set_state(&mut e, SessionState::Busy);
        self.push(&mut e);
        self.emit(&mut e, wire::STATUS, wire::status_run("BUSY", "thinking", now, now));
        self.emit(&mut e, wire::TURN, text.clone());
        self.run(&mut e, text);
        Ok(())
    }

    fn prompt_into(self: &Arc<Self>, sid: &str, text: String) -> Result<(), Refusal> {
        let entry = self.find(sid).ok_or_else(|| gone_refusal("prompt", sid))?;
        let mut e = entry.lock().unwrap();
        if e.turn_active {
            // Kotlin's typed refusal (1.96.0): nothing ran; the client queues it (`queue_add`) or sends it again later.
            return Err(Refusal::InFlight(format!("{} {sid}: session {sid} already has a run in flight", wire::RUN_IN_FLIGHT)));
        }
        if self.turns_paused() {
            // UPDATE-LIVE-1: an update is being prepared; the turn waits in the queue and runs after it.
            drop(e);
            return self.queue_add(sid, text).map_err(Refusal::Plain);
        }
        self.turn_into(&mut e, text);
        Ok(())
    }

    /// CHAT-QUEUE-1 `queue_add`: held at the end of `session.queued`; a session at rest takes it as a turn now.
    fn queue_add(self: &Arc<Self>, sid: &str, text: String) -> Result<(), String> {
        self.enqueue(sid, text, OWNER_PREFIX)
    }

    /// `prefix` marks who queued it: [`OWNER_PREFIX`] (typed by the owner) or [`DELIVERED_PREFIX`] (mail, a nudge).
    fn enqueue(self: &Arc<Self>, sid: &str, text: String, prefix: &str) -> Result<(), String> {
        let entry = self.find(sid).ok_or_else(|| gone_refusal("queue_add", sid))?;
        let mut e = entry.lock().unwrap();
        if text.trim().is_empty() {
            return Err("queue_add refused: nothing to queue".into());
        }
        let owner_waiting = prefix == DELIVERED_PREFIX && e.rec.queued.iter().any(is_owner_turn);
        if !e.turn_active && !self.turns_paused() && !owner_waiting {
            self.turn_into(&mut e, text);
            return Ok(());
        }
        if e.rec.queued.len() >= self.cfg.queue_max {
            return Err(format!("queue_add refused: the queue of {sid} is full ({} messages)", self.cfg.queue_max));
        }
        let id = format!("{prefix}{}", &uuid::Uuid::new_v4().to_string()[..8]);
        e.rec.queued.push(QueuedTurn { id, text, at: now_ms() });
        self.push(&mut e);
        if owner_waiting && !e.turn_active {
            drop(e);
            self.drain_later(sid);
        }
        Ok(())
    }

    fn queue_remove(&self, sid: &str, id: &str) -> Result<(), String> {
        let entry = self.find(sid).ok_or_else(|| format!("queue_remove refused: unknown session {sid}"))?;
        let mut e = entry.lock().unwrap();
        let before = e.rec.queued.len();
        e.rec.queued.retain(|q| q.id != id);
        if e.rec.queued.len() == before {
            return Err(format!("queue_remove refused: no queued message {id} on {sid}"));
        }
        self.push(&mut e);
        Ok(())
    }

    /// `queue_force`: at rest it is sent as the turn now; mid-turn it needs a harness that takes input mid-turn (none here yet).
    fn queue_force(self: &Arc<Self>, sid: &str, id: &str) -> Result<(), String> {
        let entry = self.find(sid).ok_or_else(|| format!("queue_force refused: unknown session {sid}"))?;
        let mut e = entry.lock().unwrap();
        let i = e.rec.queued.iter().position(|q| q.id == id).ok_or_else(|| format!("queue_force refused: no queued message {id} on {sid}"))?;
        if e.turn_active {
            drop(e);
            return self.force_into_turn(sid, id);
        }
        let item = e.rec.queued.remove(i);
        self.turn_into(&mut e, item.text);
        Ok(())
    }

    /// `queue_force` mid-turn: the message goes into the running turn when the CLI takes input mid-turn (the lock is let
    /// go while it answers); else it goes back where it was and the refusal says why.
    fn force_into_turn(self: &Arc<Self>, sid: &str, id: &str) -> Result<(), String> {
        let entry = self.find(sid).ok_or_else(|| format!("queue_force refused: unknown session {sid}"))?;
        let mut e = entry.lock().unwrap();
        let i = e.rec.queued.iter().position(|q| q.id == id).ok_or_else(|| format!("queue_force refused: no queued message {id} on {sid}"))?;
        let Some(inject) = e.hs.as_ref().and_then(|h| h.injector()) else {
            return Err(format!("queue_force refused: the {} harness cannot take a message mid-turn; it stays queued for the next turn", e.rec.provider));
        };
        let item = e.rec.queued.remove(i);
        self.push(&mut e);
        drop(e);
        let delivered = inject(item.text.clone());
        let mut e = entry.lock().unwrap();
        if delivered {
            // The message is in the running turn: it must show in the chat as the user's turn, never vanish from the queue unseen.
            self.emit(&mut e, wire::TURN, item.text);
            return Ok(());
        }
        let at = i.min(e.rec.queued.len());
        let provider = e.rec.provider.clone();
        e.rec.queued.insert(at, item);
        self.push(&mut e);
        Err(format!("queue_force refused: the {provider} harness could not take the message mid-turn; it stays queued for the next turn"))
    }

    /// CP-4 `command_catalog` `{provider?, prefix?}` (on a session or not): the provider's `/` commands matching the prefix.
    fn command_catalog(&self, data: &str, sid: Option<&str>) -> Result<String, String> {
        let d: serde_json::Value = if data.trim().is_empty() { serde_json::json!({}) } else { serde_json::from_str(data).map_err(|_| "payload must be a JSON object".to_string())? };
        let o = d.as_object().ok_or("payload must be a JSON object")?;
        if let Some(k) = o.keys().find(|k| *k != "provider" && *k != "prefix") {
            return Err(format!("unknown field '{}'", k.chars().take(40).collect::<String>()));
        }
        let session_provider = match sid {
            Some(id) => Some(self.find(id).ok_or_else(|| format!("unknown session '{id}'"))?.lock().unwrap().rec.provider.clone()),
            None => None,
        };
        let provider = o.get("provider").and_then(|v| v.as_str()).map(str::trim).filter(|p| !p.is_empty()).map(String::from).or(session_provider).unwrap_or_else(|| self.harness.name().to_string());
        let h = self.harness_for(Some(&provider)).ok_or_else(|| format!("unknown provider '{provider}'"))?;
        let prefix = o.get("prefix").and_then(|v| v.as_str()).unwrap_or("").to_string();
        let commands: Vec<serde_json::Value> = infantry_harness::complete_commands(&h.commands(), &prefix).iter().map(|c| c.to_json()).collect();
        Ok(serde_json::json!({ "provider": provider, "prefix": prefix, "commands": commands }).to_string())
    }

    /// `session_mcp_reconnect` `{sessionId}`: asks the CLI to reconnect the session's own MCP server.
    fn mcp_reconnect(&self, sid: &str) -> Result<String, String> {
        let entry = self.find(sid).ok_or_else(|| format!("session_mcp_reconnect refused: unknown session {sid}"))?;
        let mut e = entry.lock().unwrap();
        let provider = e.rec.provider.clone();
        match e.hs.as_mut().map(|h| h.reconnect_mcp()) {
            Some(true) => Ok(serde_json::json!({ "sessionId": sid, "reconnecting": true }).to_string()),
            Some(false) => Err(format!("session_mcp_reconnect refused: the {provider} harness cannot reconnect its MCP server")),
            None => Err(format!("session_mcp_reconnect refused: session {sid} has no running process")),
        }
    }

    /// When the session is at rest, sends its oldest held message as the next turn (one per turn end), after the current frames.
    fn drain_later(self: &Arc<Self>, sid: &str) {
        let (d, sid) = (self.clone(), sid.to_string());
        crate::sessions::spawn_bg(async move {
            let Some(entry) = d.find(&sid) else { return };
            let mut e = entry.lock().unwrap();
            if e.removed || e.turn_active || e.rec.state == SessionState::Exited || e.rec.queued.is_empty() || d.turns_paused() {
                return;
            }
            // OWNER-PRIO-1: the oldest owner prompt goes first; held mail and nudges follow in their order.
            let i = e.rec.queued.iter().position(is_owner_turn).unwrap_or(0);
            let head = e.rec.queued.remove(i);
            d.turn_into(&mut e, head.text);
        });
    }

    /// A turn into an existing session at rest.
    fn turn_into(self: &Arc<Self>, e: &mut Entry, text: String) {
        let now = now_ms();
        e.rec.last_turn_at = Some(now);
        // A turn into a session whose process is gone resumes it (a fresh process continues the provider session).
        if e.rec.run_count > 0 && !e.hs.as_ref().is_some_and(|h| h.process_alive()) {
            e.rec.resume_count += 1;
        }
        if e.rec.waiting.take().is_some() {
            self.named_event("session.waiting_off", serde_json::json!({"sessionId": e.rec.id, "projectId": e.rec.project_id, "why": "turn"}));
        }
        self.push(e);
        let st = state_word(e.rec.state).to_ascii_uppercase();
        self.emit(e, wire::STATUS, wire::status(&st));
        self.emit(e, wire::TURN, text.clone());
        e.turn_active = true;
        e.turn_started = now;
        e.gen += 1;
        self.emit(e, wire::STATUS, wire::status_run("BUSY", "thinking", now, now));
        e.rec.kill_outcome = None;
        e.rec.killed_by = None;
        self.run(e, text);
    }

    /// Starts the turn's run: the start-up lines, the harness turn, then the record that announces the run.
    fn run(self: &Arc<Self>, e: &mut Entry, text: String) {
        let provider = e.rec.provider.clone();
        self.notify(|o| o.on_turn(&e.rec, &text));
        let model = e.rec.model.clone().unwrap_or_else(|| "default".into());
        let resumed = e.rec.run_count > 0;
        // A persistent process (an interactive CLI) that is still up takes the turn as is: nothing is resumed.
        let mut warm = e.hs.as_ref().is_some_and(|h| h.process_alive());
        // A child the watchdog found hung is killed by its pid and replaced: the turn never attaches to it.
        if std::mem::take(&mut e.hung) && e.hs.is_some() {
            let pid = e.rec.child_pid;
            self.emit(e, wire::STATUS, wire::status_activity(&provider, &format!("previous process{} hung (no output past the limit): killing it", pid.map(|p| format!(" (pid {p})")).unwrap_or_default())));
            if let Some(mut hs) = e.hs.take() {
                hs.close(true);
            }
            if let Some(p) = pid.and_then(|p| u32::try_from(p).ok()) {
                infantry_runner::job::kill_pid(p);
            }
            e.rec.child_pid = None;
            e.rec.is_process_alive = false;
            warm = false;
        }
        if resumed && !warm {
            let id = e.rec.id.clone();
            self.emit(e, wire::STATUS, wire::status_activity(&provider, &format!("Resuming session {id}...")));
        }
        let start = format!("starting {provider} model {model} in {}", e.rec.working_dir);
        self.emit(e, wire::STATUS, wire::status_activity(&provider, &start));
        if !resumed {
            self.emit(e, wire::STDERR, format!("[infantry] * session started ({provider} {} {model})", wire::MIDDOT));
        } else if let Some(p) = e.rec.provider_session_id.clone().filter(|_| !warm) {
            self.emit(e, wire::STATUS, wire::status_activity(&provider, &format!("resuming provider session {p}")));
        }
        self.resolve_stored_limit(e);
        if e.hs.is_none() {
            match self.open(e) {
                Ok(hs) => e.hs = Some(hs),
                Err(err) => return self.fail_start(e, err),
            }
        }
        e.stats.turn(e.rec.model.as_deref());
        // HARNESS-CARRY-1: the first turn after a swap carries the conversation so far (the transcript shows the turn as typed).
        let carry = e.carry.take().filter(|_| e.rec.provider_session_id.is_none());
        // PM-ROLE-1: a new conversation (the first turn, or the first after a swap) starts with its role's text.
        let fresh = e.rec.provider_session_id.is_none() && (e.rec.run_count == 0 || carry.is_some());
        let brief = match (fresh, e.rec.role.as_deref(), self.role_brief.get()) {
            (true, Some(r), Some(f)) => f(r).filter(|b| !b.trim().is_empty()),
            _ => None,
        };
        let prompt = [brief, carry, Some(text)].into_iter().flatten().collect::<Vec<_>>().join("

");
        let turn = Turn { prompt, resume: e.rec.provider_session_id.clone() };
        match e.hs.as_mut().unwrap().send_turn(turn) {
            Ok(start) => {
                let line = match start.pid {
                    Some(p) if warm && e.rec.child_pid == Some(i64::from(p)) => format!("turn sent to the running process (pid {p})"),
                    Some(p) => format!("process started (pid {p})"),
                    None => "process started (no OS process)".into(),
                };
                self.emit(e, wire::STATUS, wire::status_activity(&provider, &line));
                e.rec.run_count += 1;
                e.rec.is_process_alive = true;
                e.rec.child_pid = start.pid.map(i64::from);
                if let Some(p) = start.provider_session_id {
                    e.rec.provider_session_id = Some(p);
                }
                Self::set_state(e, SessionState::Busy);
                self.push(e);
                self.emit(e, wire::STATUS, wire::status("BUSY"));
            }
            Err(err) => self.fail_start(e, err),
        }
    }

    /// Routes every session's tool approvals and questions (set once, at composition).
    /// `session_project`: moves a session to another project (the caller checked the project exists); pushed and stored.
    pub fn set_project(&self, sid: &str, project_id: &str) -> Result<(), String> {
        let entry = self.find(sid).ok_or_else(|| format!("unknown session '{sid}'"))?;
        let mut e = entry.lock().unwrap();
        e.rec.project_id = Some(project_id.to_string());
        self.push(&mut e);
        Ok(())
    }

    /// PM-ROLE-1: sets what the first turn of a new conversation of a role starts with (set once, at composition).
    pub fn set_role_brief(&self, f: Arc<dyn Fn(&str) -> Option<String> + Send + Sync>) {
        let _ = self.role_brief.set(f);
    }

    pub fn set_prompter(&self, p: Arc<dyn crate::harness::Prompter>) {
        let _ = self.prompter.set(p);
    }

    pub fn set_event_sink(&self, f: Arc<dyn Fn(&str, serde_json::Value) + Send + Sync>) {
        let _ = self.events.set(f);
    }

    /// A named hook event (`session.waiting_on` ...); nobody listening is fine.
    pub(crate) fn named_event(&self, name: &str, payload: serde_json::Value) {
        if let Some(f) = self.events.get() {
            f(name, payload);
        }
    }

    /// Adds the MCP servers each session was granted to its launch plan (set once, at composition).
    pub fn set_mcp(&self, m: Arc<dyn McpSelector>) {
        let _ = self.mcp.set(m);
    }

    /// Env added to every agent process, read at each start (so a setting change applies to the next start).
    /// Sets the env a session of a given provider gets (read at each start).
    /// RR-6: wires what a remote start lends.
    pub fn set_lender(&self, l: Arc<crate::lender::Lender>) {
        let _ = self.lender.set(l);
    }

    pub fn set_remote_workspace(&self, w: Arc<dyn infantry_runner_source::remote::RemoteWorkspace>) {
        let _ = self.remote_ws.set(w);
    }

    pub fn remote_workspace(&self) -> Option<Arc<dyn infantry_runner_source::remote::RemoteWorkspace>> {
        self.remote_ws.get().cloned()
    }

    pub fn lender(&self) -> Option<Arc<crate::lender::Lender>> {
        self.lender.get().cloned()
    }

    /// Changes a session's context limit (tokens, 0 = off; None = back to the settings' default) without touching its
    /// conversation. The harness reads the limit when its process starts, so a running process keeps the old one until
    /// the session is restarted; the answer says which of the two applies (the truth of the harness).
    pub fn set_context_limit(&self, id: &str, limit: Option<i32>) -> Result<serde_json::Value, String> {
        let entry = self.find(id).ok_or_else(|| format!("unknown session {id}"))?;
        let mut e = entry.lock().unwrap();
        let (value, source) = match limit {
            Some(n) => (Some(n.max(0)), Some("session".to_string())),
            None => match self.context_default.get().and_then(|f| f(e.rec.project_id.as_deref(), e.rec.role.as_deref())) {
                Some((n, scope)) => (Some(n.max(0)), Some(scope)),
                None => (None, None),
            },
        };
        e.rec.context_limit = value;
        e.rec.context_limit_source = source.clone();
        let running = e.hs.is_some();
        let (support, _) = self.harness_for(Some(&e.rec.provider)).map(|h| h.context_limit_support()).unwrap_or(("unsupported", ""));
        let effect = if support == "unsupported" {
            "recorded only: this harness cannot cap its context"
        } else if running {
            "applies when this session's process next starts (restart it to apply now); the conversation is kept"
        } else {
            "applies on its next turn"
        };
        self.push(&mut e);
        Ok(json!({"sessionId": id, "contextLimit": value, "source": source, "support": support, "effect": effect}))
    }

    /// A session saved before the limit existed has none stored: it takes the settings default now (role > project > app >
    /// default), stored with its source, support and how, so it launches capped. An explicit 0 (off) is kept.
    fn resolve_stored_limit(&self, e: &mut Entry) {
        if e.rec.context_limit.is_some() {
            return;
        }
        if let Some((n, scope)) = self.context_default.get().and_then(|f| f(e.rec.project_id.as_deref(), e.rec.role.as_deref())) {
            e.rec.context_limit = Some(n.max(0));
            e.rec.context_limit_source = Some(scope);
        }
        if let Some(h) = self.harness_for(Some(&e.rec.provider)) {
            let (support, how) = h.context_limit_support();
            e.rec.context_limit_support = Some(support.to_string());
            e.rec.context_limit_how = Some(how.to_string());
        }
    }

    pub fn set_context_default(&self, f: ContextDefault) {
        let _ = self.context_default.set(f);
    }

    /// Where the owner's saved CLI location of a harness is read from (HARNESS-OVERRIDE-USE-1).
    pub fn set_cli_override(&self, f: Arc<dyn Fn(&str) -> Option<String> + Send + Sync>) {
        let _ = self.cli_override.set(f);
    }

    pub fn set_provider_env(&self, f: Arc<dyn Fn(&str) -> Vec<(String, String)> + Send + Sync>) {
        let _ = self.provider_env.set(f);
    }

    pub fn set_env_extra(&self, f: Arc<dyn Fn() -> Vec<(String, String)> + Send + Sync>) {
        let _ = self.env_extra.set(f);
    }

    /// Sets how a session's role maps to its role-policy env (read at each start, so a setting applies to the next).
    pub fn set_role_policy(&self, f: Arc<dyn Fn(Option<&str>, Option<&str>) -> Option<(String, String)> + Send + Sync>) {
        let _ = self.role_policy.set(f);
    }

    /// Sets what every agent process started from now on is told about this daemon.
    pub fn set_launch(&self, base: crate::harness::launch::LaunchBase) {
        *self.launch.lock().unwrap() = base;
    }

    /// Wires the runner registry (the composition root adds the paired runners to it); set once.
    pub fn set_runners(&self, r: Arc<infantry_runner_source::RunnerRegistry>) {
        let _ = self.runners.set(r);
    }

    /// The runners a session can be placed on. Until the root sets one, `local` alone (the embedded runner).
    pub fn runner_registry(&self) -> Arc<infantry_runner_source::RunnerRegistry> {
        self.runners
            .get_or_init(|| {
                let runner = infantry_runner_local::LocalRunner::new(infantry_runner_local::LocalConfig { scratch_dir: Some(self.cfg.state_dir.join("runner")), ..Default::default() });
                let local = infantry_runner_source::RunnerEntry {
                    name: infantry_runner_source::LOCAL.into(),
                    kind: "embedded".into(),
                    runner: Arc::new(runner),
                    scope: Arc::new(infantry_runner_source::NoScope(infantry_runner_source::LOCAL.into())),
                };
                infantry_runner_source::RunnerRegistry::new(local)
            })
            .clone()
    }

    /// The runner a start asks for: `None` = `local` (nothing to place), else (runner name, scratch folder on it) or the loud
    /// refusal. `any` becomes the name of the runner it chose.
    fn place(&self, runner: Option<&str>, provider: Option<&str>, verb: &str) -> Result<Option<(String, String)>, String> {
        let spec = runner.map(str::trim).unwrap_or("");
        if spec.is_empty() || spec.eq_ignore_ascii_case(infantry_runner_source::LOCAL) {
            return Ok(None);
        }
        let entry = self.runner_registry().resolve(spec).map_err(|r| format!("{verb} refused: {r}"))?;
        // RR-13: the harness must be usable ON that runner: its own `providers` answer says (a runner that cannot answer is not
        // blocked here; the harness start on it then fails with its own reason).
        if let Some(why) = provider.map(str::trim).filter(|p| !p.is_empty()).and_then(|p| unusable_on(&entry, p)) {
            return Err(format!("{verb} refused: {why}"));
        }
        let workdir = infantry_runner_source::RunnerRegistry::scratch_dir(&entry).map_err(|e| format!("{verb} refused: {e}"))?;
        Ok(Some((entry.name, workdir)))
    }

    fn open(self: &Arc<Self>, e: &Entry) -> Result<Box<dyn HarnessSession>, String> {
        let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel();
        let (mut env, mut mcp) = self.launch.lock().unwrap().session(&e.rec.id, e.rec.project_id.as_deref(), e.rec.role.as_deref());
        if let Some(extra) = self.env_extra.get() {
            env.retain(|(k, _)| !extra().iter().any(|(ek, _)| ek == k));
            env.extend(extra());
        }
        if let Some(f) = self.provider_env.get() {
            let mine = f(&e.rec.provider);
            env.retain(|(k, _)| !mine.iter().any(|(mk, _)| mk == k));
            env.extend(mine);
        }
        env.retain(|(k, _)| k != crate::role_policy::ENV);
        if let Some(pair) = self.role_policy.get().and_then(|f| f(e.rec.role.as_deref(), e.rec.project_id.as_deref())) {
            env.push(pair);
        }
        if let Some(m) = self.mcp.get() {
            m.select(&e.rec, &mut mcp);
        }
        // RR-4: every session, local or remote, is opened on the runner its record names; an unknown, offline or outdated
        // runner fails the start loudly (no local fallback).
        let mut entry = self.runner_registry().resolve(&e.rec.runner).map_err(|r| r.to_string())?;
        // Logging only: where the session opens (the launch command itself is logged by the runner that spawns it).
        eprintln!("[infantry] session {} ({}) opening on runner {} ({})", e.rec.id, e.rec.provider, entry.name, entry.kind);
        // RR-6: a remote runner is lent the harness key, the project's secrets and a git credential with each start, instead
        // of the key travelling in the plain env. `local` is lent nothing (it uses this machine's own keys).
        if entry.kind == "remote" {
            if let Some(f) = self.provider_env.get() {
                let mine = f(&e.rec.provider);
                env.retain(|(k, _)| !mine.iter().any(|(mk, _)| mk == k));
            }
            if let Some(lend) = self.lender.get() {
                let lent = lend.lend(&entry.name, &e.rec.provider, e.rec.project_id.as_deref());
                entry.runner = Arc::new(infantry_runner_api::LendingRunner::new(entry.runner.clone(), lent));
            }
        }
        // RR-5: a runner on another machine cannot dial this daemon's loopback: its session (and the infantry MCP it runs)
        // is pointed at a loopback port on the runner host that the link tunnels back here. A runner that shares this machine
        // answers `Unsupported` and the env stays as it is.
        if let Some(port) = env.iter().find(|(k, _)| k == "INFANTRY_PORT").and_then(|(_, v)| v.parse::<u16>().ok()) {
            // RR-6b: the forward targets the tunnel listener, never the main port (which trusts loopback), and the session
            // carries its own scoped token. No tunnel listener = no safe way to forward: the start fails loudly.
            let tunnel_port = self.tunnel.port();
            if entry.kind == "remote" && tunnel_port == 0 {
                return Err("a remote session needs the daemon's tunnel listener (not open on this daemon)".into());
            }
            let target = if tunnel_port != 0 { tunnel_port } else { port };
            if let Some(remote) = forward_to_runner(&entry, target)? {
                reroute_daemon_env(&mut env, &mut mcp, port, remote);
            }
        }
        // RR-6b / AGENT-OWNER-TOKEN-1: EVERY session (local or remote) carries its own scoped token instead of the owner's.
        // On the main port a connection presenting it is that session's agent, never the owner (server.rs `agent_of`).
        let token = self.tunnel.mint(&e.rec.id);
        set_token(&mut env, &mut mcp, &token);
        // HARNESS-OVERRIDE-USE-1: a saved location is a path on this machine, so it applies to the local runner only; a
        // saved path that is gone refuses the start (never a silent fall back to the default lookup).
        let program = if entry.kind == "remote" {
            None
        } else {
            saved_program(&e.rec.provider, self.cli_override.get().and_then(|f| f(&e.rec.provider)))?
        };
        let req = OpenRequest {
            program,
            runner: entry.runner,
            runner_name: entry.name,
            session_id: e.rec.id.clone(),
            cwd: PathBuf::from(&e.rec.working_dir),
            model: e.rec.model.clone(),
            effort: e.rec.effort.clone(),
            context_limit: e.rec.context_limit.filter(|n| *n > 0).map(|n| n as u64),
            state_dir: self.cfg.state_dir.clone(),
            env,
            mcp,
            approval: match self.prompter.get() {
                Some(p) => p.mode(&e.rec.id, e.rec.project_id.as_deref(), e.rec.role.as_deref()),
                None => infantry_harness::ApprovalMode::AllowAll,
            },
            prompter: self.prompter.get().cloned(),
        };
        let harness = self.harness_for(Some(&e.rec.provider)).ok_or_else(|| format!("no harness named `{}` on this daemon", e.rec.provider))?;
        let hs = harness.open(req, EventSink(tx))?;
        let (d, id) = (self.clone(), e.rec.id.clone());
        crate::sessions::spawn_bg(async move {
            while let Some(ev) = rx.recv().await {
                d.on_event(&id, ev);
            }
        });
        Ok(hs)
    }

    /// RR-8: after the runners are registered, re-attaches to every session that was running on a remote runner when this
    /// daemon stopped: its output continues from the saved cursor; a process that is gone ends the session as failed with the
    /// runner's reason (it keeps its harness resume id, so it can be resumed like a local one).
    pub fn reattach_placed(self: &Arc<Self>) {
        let ids = std::mem::take(&mut *self.reattach.lock().unwrap());
        for id in ids {
            let d = self.clone();
            crate::sessions::spawn_bg(async move { d.reattach_one(&id).await });
        }
    }

    /// RUNNER-OFFLINE-1: a session working on a paired runner that stays offline longer than `offline_sec()` (the setting
    /// `limits.runners.offlineSessionSec`, read each round) is failed with "the runner went offline: <reason>" in its chat,
    /// and daemon.log gets a line. The next prompt opens it again (resuming the conversation) once the runner is back.
    pub fn start_runner_watch(self: &Arc<Self>, offline_sec: Arc<dyn Fn() -> u64 + Send + Sync>) {
        let weak = Arc::downgrade(self);
        let _ = std::thread::Builder::new().name("runner-offline-watch".into()).spawn(move || {
            let mut since: BTreeMap<String, std::time::Instant> = BTreeMap::new();
            loop {
                std::thread::sleep(Duration::from_secs(2));
                let Some(d) = weak.upgrade() else { return };
                d.runner_watch_round(&mut since, Duration::from_secs(offline_sec().max(1)));
            }
        });
    }

    fn runner_watch_round(self: &Arc<Self>, since: &mut BTreeMap<String, std::time::Instant>, limit: Duration) {
        use infantry_runner_source::Availability;
        let down: BTreeMap<String, String> = self
            .runner_registry()
            .list()
            .into_iter()
            .filter_map(|r| match r.availability {
                Availability::Offline(why) => Some((r.name, why)),
                _ => None,
            })
            .collect();
        since.retain(|k, _| down.contains_key(k));
        for name in down.keys() {
            since.entry(name.clone()).or_insert_with(std::time::Instant::now);
        }
        for (name, why) in &down {
            if since.get(name).is_some_and(|t| t.elapsed() >= limit) {
                self.runner_went_offline(name, why);
            }
        }
    }

    fn runner_went_offline(self: &Arc<Self>, runner: &str, why: &str) {
        let entries: Vec<Arc<Mutex<Entry>>> = self.list.lock().unwrap().iter().map(|(_, e)| e.clone()).collect();
        for en in entries {
            let mut e = en.lock().unwrap();
            if e.removed || !e.turn_active || e.rec.runner != runner {
                continue;
            }
            let why = if why.trim().is_empty() { "nothing is listening on its link".to_string() } else { why.trim().to_string() };
            let msg = format!("the runner went offline: {why} (runner '{runner}')");
            eprintln!("[infantry] session {} ({} on runner {runner}): {msg}", e.rec.id, e.rec.provider);
            if let Some(mut hs) = e.hs.take() {
                hs.close(true);
            }
            self.end_turn(&mut e, TurnEnd::Failed, None, Some(msg), false);
        }
    }

    async fn reattach_one(self: Arc<Self>, id: &str) {
        use infantry_runner_api::{ProcEvent, ProcId, RunnerError};
        let Some((runner, proc)) = self.find(id).and_then(|en| {
            let e = en.lock().unwrap();
            e.rec.runner_proc.clone().map(|p| (e.rec.runner.clone(), p))
        }) else {
            return;
        };
        let pid = ProcId(proc);
        let mut tries = 0u32;
        let mut announced = false;
        let mut reason = String::new();
        loop {
            let why = match self.runner_registry().resolve(&runner) {
                Ok(entry) => match entry.runner.attach(&pid, -1).await {
                    Ok(mut rx) => {
                        if !announced {
                            announced = true;
                            self.mark_reattached(id, &runner, &pid.0);
                        }
                        loop {
                            // A process that stays alive between turns (an interactive CLI) never "ends": its turn is over
                            // when it goes quiet. The turn is ended, and the process is released so the next prompt starts a
                            // fresh one that resumes the conversation by its harness resume id.
                            let ev = match tokio::time::timeout(REATTACH_QUIET, rx.recv()).await {
                                Ok(Some(ev)) => ev,
                                Ok(None) => break,
                                Err(_) => {
                                    self.reattach_ended(id, Some(0), "");
                                    let _ = entry.runner.kill(&pid, infantry_runner_api::KillScope::Tree).await;
                                    let _ = entry.runner.release(&pid).await;
                                    return;
                                }
                            };
                            match ev {
                                ProcEvent::Stderr(b) => {
                                    for line in String::from_utf8_lossy(&b).lines().filter(|l| !l.trim().is_empty()) {
                                        reason = line.trim().to_string();
                                        self.stderr_line(id, line.to_string());
                                    }
                                }
                                ProcEvent::Exited(code) => {
                                    self.reattach_ended(id, Some(code), &reason);
                                    let _ = entry.runner.release(&pid).await;
                                    return;
                                }
                                _ => {}
                            }
                        }
                        "the link to the runner dropped".to_string()
                    }
                    Err(RunnerError::NotFound(_)) => {
                        self.reattach_ended(id, None, &format!("runner '{runner}' no longer has the process"));
                        return;
                    }
                    Err(e) => e.to_string(),
                },
                Err(r) => r.to_string(),
            };
            tries += 1;
            if tries >= REATTACH_TRIES {
                self.reattach_ended(id, None, &format!("runner '{runner}' is unreachable: {why}"));
                return;
            }
            tokio::time::sleep(REATTACH_WAIT).await;
        }
    }

    fn stderr_line(&self, id: &str, line: String) {
        if let Some(en) = self.find(id) {
            let mut e = en.lock().unwrap();
            if !e.removed {
                self.emit(&mut e, wire::STDERR, line);
            }
        }
    }

    fn mark_reattached(&self, id: &str, runner: &str, proc: &str) {
        let Some(en) = self.find(id) else { return };
        let mut e = en.lock().unwrap();
        if e.removed || e.turn_active {
            return;
        }
        e.turn_active = true;
        e.turn_started = now_ms();
        e.rec.is_process_alive = true;
        Self::set_state(&mut e, SessionState::Busy);
        self.push(&mut e);
        self.emit(&mut e, wire::STATUS, wire::status("BUSY"));
        self.emit(&mut e, wire::STDERR, format!("[infantry] re-attached to {proc} on runner '{runner}' after a restart"));
    }

    fn reattach_ended(self: &Arc<Self>, id: &str, code: Option<i32>, reason: &str) {
        let Some(en) = self.find(id) else { return };
        let mut e = en.lock().unwrap();
        if e.removed {
            return;
        }
        e.rec.runner_proc = None;
        e.turn_active = true;
        let (end, why) = match code {
            Some(0) => (TurnEnd::Completed, None),
            Some(c) => (TurnEnd::Failed, Some(if reason.is_empty() { format!("the process exited with code {c}") } else { reason.to_string() })),
            None => (TurnEnd::Failed, Some(reason.to_string())),
        };
        self.end_turn(&mut e, end, code, why, false);
    }

    fn fail_start(&self, e: &mut Entry, err: String) {
        e.turn_active = false;
        eprintln!("[infantry] session {} ({} on runner {}) start FAILED: {}", e.rec.id, e.rec.provider, e.rec.runner, err);
        e.rec.last_error = Some(err.clone());
        e.rec.is_process_alive = false;
        Self::set_state(e, SessionState::Failed);
        self.push(e);
        let now = now_ms();
        self.emit(e, wire::STATUS, wire::status_run("FAILED", "finished", now, e.turn_started));
        self.emit(e, wire::FINISHED, wire::finished(None, "FAILED", &err));
        self.notify(|o| o.on_turn_end(&e.rec, "FAILED", &err));
    }

    fn on_event(self: &Arc<Self>, id: &str, ev: HarnessEvent) {
        let Some(entry) = self.find(id) else { return };
        let mut e = entry.lock().unwrap();
        if e.removed {
            return;
        }
        match ev {
            HarnessEvent::ProviderSession(p) => e.rec.provider_session_id = Some(p),
            HarnessEvent::Placed(p) => {
                e.rec.runner_proc = Some(p);
                self.push(&mut e);
            }
            HarnessEvent::Stdout(l) if e.turn_active => {
                e.hung = false;
                e.rec.last_output_at = Some(now_ms());
                self.notify(|o| o.on_stdout(&e.rec, &l));
                self.emit(&mut e, wire::STDOUT, l);
            }
            HarnessEvent::Text(t) if e.turn_active => {
                e.hung = false;
                e.rec.last_output_at = Some(now_ms());
                self.emit(&mut e, wire::TEXT_DELTA, t);
            }
            HarnessEvent::Stderr(l) => self.emit(&mut e, wire::STDERR, l),
            HarnessEvent::Tool { name, input } => {
                e.rec.last_output_at = Some(now_ms());
                e.stats.tool(&name);
                // The chat draws a tool line from a status frame with `tool` and `activity` (transcript.ts).
                if e.turn_active {
                    e.hung = false;
                    self.emit(&mut e, wire::STATUS, wire::status_activity(&name, &wire::tool_activity(&input)));
                }
            }
            HarnessEvent::Write(w) if e.turn_active => {
                e.hung = false;
                e.rec.last_output_at = Some(now_ms());
                self.emit(&mut e, wire::STATUS, wire::status_write(&w));
            }
            HarnessEvent::Usage(u) => {
                e.rec.last_output_at = Some(now_ms());
                e.stats.usage(u);
            }
            HarnessEvent::Background(change) => self.on_background(&mut e, change),
            HarnessEvent::McpState { state, servers } => {
                e.rec.mcp = Some(state);
                e.rec.mcp_changed_at = Some(now_ms());
                e.rec.mcp_servers = servers.into_iter().map(|(name, state)| infantry_protocol::session::McpServerStatusView { name, state, detail: None, tool_count: None }).collect();
                self.push(&mut e);
            }
            HarnessEvent::TurnEnded { end, exit_code, reason, process_alive } if e.turn_active => {
                self.end_turn(&mut e, end, exit_code, reason, process_alive)
            }
            HarnessEvent::Exited { code } => {
                e.hs = None;
                // Logging only: a session that dies (a remote start that exits at once included) is named in daemon.log.
                eprintln!("[infantry] session {} ({} on runner {}) exited with code {}", e.rec.id, e.rec.provider, e.rec.runner, code.map_or("none".to_string(), |c| c.to_string()));
                // Tasks of a CLI that is gone are gone with it.
                if !e.rec.background_tasks.is_empty() {
                    e.rec.background_tasks.clear();
                    self.push(&mut e);
                    self.release_held_done(&mut e);
                }
                self.tunnel.revoke(&e.rec.id);
                self.notify(|o| o.on_exit(&e.rec));
                if e.turn_active {
                    let end = if code == Some(0) { TurnEnd::Completed } else { TurnEnd::Failed };
                    self.end_turn(&mut e, end, code, None, false);
                } else if e.rec.is_process_alive {
                    e.rec.is_process_alive = false;
                    e.rec.child_pid = None;
                    e.rec.last_exit_code = code;
                    self.push(&mut e);
                    if code != Some(0) {
                        self.notify(|o| o.on_rest_exit(&e.rec, code));
                    }
                }
            }
            _ => {}
        }
    }

    fn end_turn(self: &Arc<Self>, e: &mut Entry, end: TurnEnd, code: Option<i32>, reason: Option<String>, mut alive: bool) {
        e.turn_active = false;
        // A failed session ends its process (a failed start left claude running, FAIL-3). The next prompt opens it again and
        // resumes the provider session, so nothing is lost; a process that already exited has no handle to close.
        if end == TurnEnd::Failed && alive {
            if let Some(mut hs) = e.hs.take() {
                hs.close(false);
            }
            alive = false;
        }
        e.rec.last_exit_code = code;
        e.rec.is_process_alive = alive;
        if !alive {
            e.rec.child_pid = None;
        }
        let (state, outcome) = match end {
            TurnEnd::Completed => (SessionState::Idle, "SUCCEEDED"),
            TurnEnd::Failed => (SessionState::Failed, "FAILED"),
        };
        let detail = match end {
            TurnEnd::Completed => String::new(),
            TurnEnd::Failed => reason.filter(|r| !r.trim().is_empty()).unwrap_or_else(|| match code {
                Some(c) => format!("the process exited with code {c}"),
                None => "the turn failed with no exit code and no reason".into(),
            }),
        };
        if end == TurnEnd::Failed {
            // Logging only (daemon.log): a failed session says why, not only that it opened.
            eprintln!("[infantry] session {} ({} on runner {}) FAILED: {}", e.rec.id, e.rec.provider, e.rec.runner, detail);
            e.rec.last_error = Some(detail.clone());
        } else {
            e.stats.assistant_turns += 1;
        }
        e.stats.last_at = Some(now_ms());
        e.stats.save(&self.cfg.state_dir, &e.rec.id);
        Self::set_state(e, state);
        self.push(e);
        let st = state_word(state).to_ascii_uppercase();
        self.emit(e, wire::STATUS, wire::status_run(&st, "finished", now_ms(), e.turn_started));
        self.emit(e, wire::FINISHED, wire::finished(code, outcome, &detail));
        self.emit(e, wire::STATUS, wire::status_run(&st, "idle", now_ms(), e.turn_started));
        if let Some(line) = infantry_harness::background::turn_end_summary(&Self::task_list(&e.rec)) {
            self.emit(e, wire::STDERR, format!("[infantry] {line}"));
            e.bg_done_held = end == TurnEnd::Completed;
        }
        self.notify(|o| o.on_turn_end(&e.rec, outcome, &detail));
        if std::mem::take(&mut e.reap_pending) {
            self.reap_later(&e.rec.id.clone());
        } else if !e.rec.queued.is_empty() {
            self.drain_later(&e.rec.id.clone());
        }
    }

    fn task_list(rec: &SessionRecord) -> Vec<infantry_harness::background::BackgroundTask> {
        rec.background_tasks
            .iter()
            .map(|t| infantry_harness::background::BackgroundTask { id: t.id.clone(), label: t.label.clone(), started_at: t.started_at })
            .collect()
    }

    /// A background task of the agent CLI started or ended: the record, one chat-log line, and (when the last one ends
    /// after a turn that ended cleanly) the done report the turn end held back.
    fn on_background(self: &Arc<Self>, e: &mut Entry, change: infantry_harness::background::BackgroundChange) {
        use infantry_harness::background::{finished_line, started_line, BackgroundChange};
        let line = match change {
            BackgroundChange::Started(t) => {
                let line = started_line(&t);
                e.rec.background_tasks.push(infantry_protocol::session::BackgroundTaskView { id: t.id, label: t.label, started_at: t.started_at });
                line
            }
            BackgroundChange::Finished { task, status, exit_code, ran_ms } => {
                e.rec.background_tasks.retain(|t| t.id != task.id);
                finished_line(&task, &status, exit_code, ran_ms)
            }
        };
        e.rec.last_output_at = Some(now_ms());
        self.emit(e, wire::STDERR, format!("[infantry] {line}"));
        self.push(e);
        self.release_held_done(e);
    }

    /// The last background task ended: the done report a clean turn end held back goes out now.
    fn release_held_done(self: &Arc<Self>, e: &mut Entry) {
        if e.rec.background_tasks.is_empty() && std::mem::take(&mut e.bg_done_held) && !e.turn_active {
            self.notify(|o| o.on_turn_end(&e.rec, "SUCCEEDED", ""));
        }
    }

    /// The done-worker reaper's deferred removal, after the turn's frames.
    fn reap_later(self: &Arc<Self>, sid: &str) {
        let (d, sid) = (self.clone(), sid.to_string());
        crate::sessions::spawn_bg(async move {
            let _ = tokio::task::spawn_blocking(move || d.reap_one(&sid)).await;
        });
    }

    fn interrupt(self: &Arc<Self>, sid: &str) -> Result<(), String> {
        let entry = self.find(sid).ok_or("interrupt refused: no such session")?;
        let mut e = entry.lock().unwrap();
        if !e.turn_active {
            self.emit(&mut e, wire::STDERR, "[infantry] nothing to interrupt: no turn is running".into());
            return Ok(());
        }
        if let Some(hs) = e.hs.as_mut() {
            hs.interrupt();
        }
        e.turn_active = false;
        let alive = e.hs.as_ref().is_some_and(|h| h.process_alive());
        e.rec.is_process_alive = alive;
        if !alive {
            e.rec.child_pid = None;
        }
        e.rec.last_exit_code = None;
        self.emit(&mut e, wire::STDERR, "[infantry] turn interrupted by the owner; the session is at rest and keeps its conversation".into());
        Self::set_state(&mut e, SessionState::Idle);
        self.push(&mut e);
        let started = e.turn_started;
        self.emit(&mut e, wire::STATUS, wire::status_run("IDLE", "finished", now_ms(), started));
        if !e.rec.queued.is_empty() {
            self.drain_later(&e.rec.id.clone());
        }
        Ok(())
    }

    fn kill(self: &Arc<Self>, sid: &str, force: bool) -> Result<(), String> {
        let entry = self.find(sid).ok_or("kill refused: no such session")?;
        self.tunnel.revoke(sid);
        let mut e = entry.lock().unwrap();
        if e.rec.state == SessionState::Exited {
            return Ok(());
        }
        if let Some(mut hs) = e.hs.take() {
            hs.close(force);
        }
        if let Some(p) = self.prompter.get() {
            p.end(sid);
        }
        let now = now_ms();
        e.turn_active = false;
        e.gen += 1;
        let outcome = if force { KillOutcome::Forced } else { KillOutcome::Graceful };
        e.rec.kill_outcome = Some(outcome);
        e.rec.killed_by = Some("client request".into());
        e.rec.last_turn_at = Some(now);
        e.rec.last_exit_code = None;
        e.rec.is_process_alive = false;
        e.rec.child_pid = None;
        Self::set_state(&mut e, SessionState::Exited);
        self.push(&mut e);
        self.emit(&mut e, wire::STATUS, wire::status("EXITED"));
        let word = if force { "KILLED_FORCED" } else { "KILLED_GRACEFUL" };
        self.emit(&mut e, wire::FINISHED, wire::finished(None, word, ""));
        self.tunnel.revoke(&e.rec.id);
        self.notify(|o| {
            o.on_turn_end(&e.rec, word, "");
            o.on_exit(&e.rec);
        });
        let started = e.turn_started;
        self.emit(&mut e, wire::STATUS, wire::status_run("IDLE", "finished", now, started));
        let (gen, d, ms) = (e.gen, self.clone(), self.cfg.kill_settle_ms);
        drop(e);
        crate::sessions::spawn_bg(async move {
            tokio::time::sleep(Duration::from_millis(ms)).await;
            let mut e = entry.lock().unwrap();
            if e.removed || e.gen != gen || e.rec.state != SessionState::Exited {
                return;
            }
            let at = e.rec.last_heartbeat;
            e.rec.watch = Some(AgentWatch { state: "IDLE".into(), pending_since: None, pending: None, last_activity_at: at, nudges: 0 });
            d.push(&mut e);
            let started = e.turn_started;
            d.emit(&mut e, wire::STATUS, wire::status_run("EXITED", "idle", now_ms(), started));
        });
        Ok(())
    }

    /// Removes a session (ends its process first); the `session_delete` frame and `infantry_session_remove` share it.
    pub fn delete_session(&self, sid: &str) -> Result<(), String> {
        self.delete(sid)
    }

    fn delete(&self, sid: &str) -> Result<(), String> {
        let entry = {
            let mut list = self.list.lock().unwrap();
            match list.iter().position(|(i, _)| i == sid) {
                Some(i) => list.remove(i).1,
                None => {
                    drop(list);
                    // SESSION-GHOST-1: an id only the list copy still holds is taken off the list.
                    let mut cache = self.cache.lock().unwrap();
                    let before = cache.len();
                    cache.retain(|r| r.id != sid);
                    if cache.len() == before {
                        return Err("session_delete refused: no such session".into());
                    }
                    drop(cache);
                    self.dirty.store(true, Ordering::SeqCst);
                    self.broadcast(wire::SESSIONS, self.sessions_json());
                    return Ok(());
                }
            }
        };
        {
            self.tunnel.revoke(sid);
            let mut e = entry.lock().unwrap();
            e.removed = true;
            if let Some(mut hs) = e.hs.take() {
                hs.close(true);
            }
            // Under the session's lock (lock order: session, then cache), so no write of it can land after this.
            self.cache.lock().unwrap().retain(|r| r.id != sid);
        }
        let wd = entry.lock().unwrap().rec.working_dir.clone();
        self.drop_own_folder(&wd, sid);
        Stats::remove(&self.cfg.state_dir, sid);
        crate::frame_log::remove(&self.cfg.state_dir, sid);
        self.dirty.store(true, Ordering::SeqCst);
        self.broadcast(wire::SESSIONS, self.sessions_json());
        Ok(())
    }

    /// Ends every session's process (the daemon is stopping) and writes the store.
    pub fn shutdown(&self) {
        let list: Vec<_> = self.list.lock().unwrap().iter().map(|(_, e)| e.clone()).collect();
        for entry in list {
            let mut e = entry.lock().unwrap();
            if let Some(mut hs) = e.hs.take() {
                // RR-8: a session placed on a remote runner keeps its process there (the next start of this daemon
                // re-attaches to it); only the local ones end with the daemon.
                if e.rec.runner != infantry_runner_source::LOCAL && e.rec.runner_proc.is_some() {
                    std::mem::forget(hs);
                } else {
                    hs.close(true);
                }
            }
        }
        self.dirty.store(true, Ordering::SeqCst);
        self.flush();
    }
}

fn state_word(s: SessionState) -> &'static str {
    match s {
        SessionState::Starting => "starting",
        SessionState::Busy => "busy",
        SessionState::WaitingInput => "waiting_input",
        SessionState::Idle => "idle",
        SessionState::Exited => "exited",
        SessionState::Orphaned => "orphaned",
        SessionState::Failed => "failed",
    }
}

/// RR-13: why `provider` cannot run on the runner `entry`, from that runner's own `providers` answer ("not installed on box",
/// "not signed in on box"); None = usable, or the runner did not say.
fn unusable_on(entry: &infantry_runner_source::RunnerEntry, provider: &str) -> Option<String> {
    let answer = entry.scope.request("providers", "{}").ok()?;
    unusable_in(&answer, provider, &entry.name)
}

fn unusable_in(providers: &str, provider: &str, runner: &str) -> Option<String> {
    let rows: serde_json::Value = serde_json::from_str(providers).ok()?;
    let row = rows.as_array()?.iter().find(|r| r["id"].as_str() == Some(provider))?;
    if row["available"].as_bool() != Some(false) {
        return None;
    }
    let reason = row["unavailableReason"].as_str().unwrap_or("").trim();
    let what = if reason.contains("not installed") {
        "is not installed"
    } else if reason.contains("not signed in") {
        "is not signed in"
    } else {
        "is unavailable"
    };
    Some(format!("{provider} {what} on {runner}{}; open the runner's setup (infantry runner frame {runner} harness_install_status), nothing ran locally", if reason.is_empty() { String::new() } else { format!(" ({reason})") }))
}

/// The frames a `replay_request` after `last` answers with: a `replay_gap` first when older frames were evicted, and one
/// in place at every hole inside the kept history (frames lost to a failed disk write), so the client moves past a hole
/// at once instead of holding every later frame for the missing ones.
fn replay_frames(history: &VecDeque<(i64, String)>, sid: &str, last: i64) -> Vec<String> {
    let mut out = Vec::new();
    let latest = history.back().map(|h| h.0);
    let first = history.front().map(|h| h.0);
    if let (Some(first), Some(latest)) = (first, latest) {
        if first > last + 1 {
            let gap = json!({"requestedAfterSequence": last, "oldestAvailableSequence": first, "latestAvailableSequence": latest, "evictedCount": first - last - 1});
            out.push(encode(wire::REPLAY_GAP, gap.to_string(), Some(sid), None));
        }
    }
    let mut prev: Option<i64> = first.filter(|f| *f <= last + 1).map(|_| last);
    for (s, f) in history.iter().filter(|(s, _)| *s > last) {
        if let Some(prev) = prev.filter(|p| *s > p + 1) {
            let gap = json!({"requestedAfterSequence": prev, "oldestAvailableSequence": s, "latestAvailableSequence": latest.unwrap_or(*s), "evictedCount": s - prev - 1});
            out.push(encode(wire::REPLAY_GAP, gap.to_string(), Some(sid), None));
        }
        out.push(f.clone());
        prev = Some(*s);
    }
    out
}

#[cfg(test)]
mod replay_hole_tests {
    use super::*;

    fn h(seqs: &[i64]) -> VecDeque<(i64, String)> {
        seqs.iter().map(|s| (*s, format!("f{s}"))).collect()
    }

    #[test]
    fn a_hole_inside_the_history_is_marked_in_place() {
        let out = replay_frames(&h(&[1, 2, 3, 7, 8]), "s", 0);
        assert_eq!(out.len(), 6);
        assert_eq!((out[2].as_str(), out[4].as_str()), ("f3", "f7"));
        assert!(out[3].contains("replay_gap") && out[3].contains("\\\"evictedCount\\\":3") || out[3].contains("evictedCount"));
    }

    #[test]
    fn a_client_sitting_at_the_hole_gets_the_marker_first() {
        let out = replay_frames(&h(&[1, 2, 3, 7, 8]), "s", 3);
        assert!(out[0].contains("replay_gap"));
        assert_eq!(out[1], "f7");
        assert_eq!(replay_frames(&h(&[1, 2, 3]), "s", 1), vec!["f2", "f3"]);
    }
}

#[cfg(test)]
mod rr13_tests {
    use super::unusable_in;

    #[test]
    fn a_runner_without_the_harness_or_its_sign_in_is_refused_by_name() {
        let rows = r#"[{"id":"pi","available":false,"unavailableReason":"pi is not installed (not on PATH)"},
            {"id":"codex","available":false,"unavailableReason":"codex is not signed in: run `codex login`"},
            {"id":"claude","available":true,"unavailableReason":null}]"#;
        assert!(unusable_in(rows, "pi", "box").unwrap().starts_with("pi is not installed on box"));
        assert!(unusable_in(rows, "codex", "box").unwrap().starts_with("codex is not signed in on box"));
        assert_eq!(unusable_in(rows, "claude", "box"), None);
        assert_eq!(unusable_in(rows, "unknown", "box"), None);
        assert_eq!(unusable_in("not json", "pi", "box"), None);
    }
}

/// The port on the runner's host that tunnels to this daemon's [port]; `None` = the runner shares this machine.
fn forward_to_runner(entry: &infantry_runner_source::RunnerEntry, port: u16) -> Result<Option<u16>, String> {
    use infantry_runner_api::RunnerError;
    // On its own thread, so it is safe to call from inside an async runtime too.
    let answer = std::thread::scope(|s| {
        s.spawn(|| {
            let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(|e| e.to_string())?;
            Ok::<_, String>(rt.block_on(entry.runner.forward_loopback(port)))
        })
        .join()
        .unwrap_or_else(|_| Err("the loopback forward panicked".to_string()))
    })?;
    match answer {
        Ok(p) => Ok(Some(p)),
        Err(RunnerError::Unsupported(_)) => Ok(None),
        Err(e) => Err(format!("runner '{}' cannot reach this daemon: {e}", entry.name)),
    }
}

/// The token names a session must never inherit from the daemon (the owner's credential): blanked in its env.
const OWNER_TOKEN_NAMES: [&str; 4] = ["INFANTRY_TOKEN", "INFANTRY_TOKEN_FILE", "AGENTHQ_TOKEN", "AGENTHQ_TOKEN_FILE"];

/// RR-6b: lends the session's own token as `INFANTRY_TOKEN` (and in its MCP servers' env), replacing any other.
/// AGENT-OWNER-TOKEN-1: the other token names are set blank, so the daemon's own (inherited) credential never reaches it.
fn set_token(env: &mut Vec<(String, String)>, mcp: &mut infantry_harness::McpPlan, token: &str) {
    let put = |kv: &mut Vec<(String, String)>| {
        kv.retain(|(k, _)| !OWNER_TOKEN_NAMES.iter().any(|n| k.eq_ignore_ascii_case(n)));
        kv.extend(OWNER_TOKEN_NAMES[1..].iter().map(|n| (n.to_string(), String::new())));
        kv.push(("INFANTRY_TOKEN".into(), token.to_string()));
    };
    put(env);
    for spec in mcp.add.iter_mut() {
        if let infantry_harness::McpTransport::Stdio { env, .. } = &mut spec.transport {
            put(env);
        }
    }
}

/// Points the daemon address in a session's env (and in its MCP servers' env) at [remote] on the runner host.
fn reroute_daemon_env(env: &mut Vec<(String, String)>, mcp: &mut infantry_harness::McpPlan, port: u16, remote: u16) {
    let fix = |kv: &mut [(String, String)]| {
        for (k, v) in kv.iter_mut() {
            match k.as_str() {
                "INFANTRY_PORT" => *v = remote.to_string(),
                "INFANTRY_URL" => *v = format!("ws://127.0.0.1:{remote}/ws"),
                _ => {}
            }
        }
    };
    let _ = port;
    // Orchestrator-local paths mean nothing on the runner host (QA: a Windows state dir made a stray `C:` folder in the worktree).
    env.retain(|(k, _)| k != "INFANTRY_STATE_DIR" && k != "INFANTRY_BIN");
    fix(env);
    for spec in mcp.add.iter_mut() {
        if let infantry_harness::McpTransport::Stdio { command, env, .. } = &mut spec.transport {
            fix(env);
            // The orchestrator's path to its `infantry` launcher means nothing on the runner host: the bridge is the `infantry`
            // on the runner's own PATH (the runner image ships it). RR-QA2: S4 failed because the Windows path was run in a container.
            env.retain(|(k, _)| k != "INFANTRY_STATE_DIR" && k != "INFANTRY_BIN");
            if spec.name == infantry_harness::INFANTRY_MCP_SERVER {
                if let Some(first) = command.first_mut() {
                    *first = "infantry".to_string();
                }
            }
        }
    }
}

#[cfg(test)]
mod rr_qa2_tests {
    use super::reroute_daemon_env;
    use infantry_harness::{McpPlan, McpServerSpec, McpTransport, INFANTRY_MCP_SERVER};

    #[test]
    fn a_remote_sessions_infantry_mcp_runs_the_runners_own_infantry_not_the_orchestrators_path() {
        let kv = |k: &str, v: &str| (k.to_string(), v.to_string());
        let mut env = vec![kv("INFANTRY_BIN", r"C:\app\infantry.exe"), kv("INFANTRY_PORT", "18940")];
        let mut mcp = McpPlan {
            add: vec![McpServerSpec {
                name: INFANTRY_MCP_SERVER.into(),
                transport: McpTransport::Stdio {
                    command: vec![r"C:\app\infantry.exe".into(), "mcp".into(), "serve".into()],
                    env: vec![kv("INFANTRY_BIN", r"C:\app\infantry.exe"), kv("INFANTRY_STATE_DIR", r"C:\state"), kv("INFANTRY_PORT", "18940")],
                    working_dir: None,
                },
                source: INFANTRY_MCP_SERVER.into(),
                local_only: true,
            }],
            deny: Vec::new(),
        };
        reroute_daemon_env(&mut env, &mut mcp, 18940, 31000);
        let McpTransport::Stdio { command, env: menv, .. } = &mcp.add[0].transport else { panic!("stdio") };
        assert_eq!(command, &vec!["infantry".to_string(), "mcp".into(), "serve".into()]);
        assert!(menv.iter().all(|(k, _)| k != "INFANTRY_BIN" && k != "INFANTRY_STATE_DIR"));
        assert!(menv.contains(&kv("INFANTRY_PORT", "31000")));
    }
}

#[cfg(test)]
mod context_launch_tests {
    use super::*;

    fn daemon() -> (Arc<Daemon>, PathBuf) {
        let dir = std::env::temp_dir().join(format!("infantry-ctx-launch-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let d = Daemon::new(Arc::new(crate::harness::stub::Stub::default()), Config::new(dir.clone(), dir.clone())).unwrap();
        crate::compose::build(&d, &dir).unwrap();
        (d, dir)
    }

    fn limit(d: &Arc<Daemon>, sid: &str) -> (Option<i32>, Option<String>) {
        let r = d.records().into_iter().find(|r| r.id == sid).unwrap();
        (r.context_limit, r.context_limit_source)
    }

    /// An existing session has no stored limit: its next harness launch resolves the default and stores it; an explicit off stays off.
    #[tokio::test(flavor = "multi_thread")]
    async fn a_session_saved_without_a_limit_launches_with_the_default_and_off_stays_off() {
        let (d, dir) = daemon();
        let old = d.launch(NewOpts::default()).unwrap();
        let off = d.launch(NewOpts { context_limit: Some(0), ..Default::default() }).unwrap();
        for (sid, none) in [(&old, true), (&off, false)] {
            let entry = d.find(sid).unwrap();
            let mut e = entry.lock().unwrap();
            if none {
                e.rec.context_limit = None;
                e.rec.context_limit_source = None;
                e.rec.context_limit_support = None;
            }
            e.hs = None;
            d.turn_into(&mut e, "hi".into());
        }
        assert_eq!(limit(&d, &old), (Some(500_000), Some("default".into())), "the old session is on the default now, with its source");
        let rec = d.records().into_iter().find(|r| r.id == old).unwrap();
        assert!(rec.context_limit_support.is_some() && rec.context_limit_how.is_some(), "support and how are stored too");
        assert_eq!(limit(&d, &off).0, Some(0), "an explicit off is kept");
        let _ = std::fs::remove_dir_all(dir);
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn the_range_is_off_or_10k_to_10m_and_out_of_range_is_refused() {
        assert_eq!(check_context_limit(0), Ok(0));
        assert_eq!(check_context_limit(10_000), Ok(10_000));
        assert_eq!(check_context_limit(10_000_000), Ok(10_000_000));
        for bad in [-1, 1, 9_999, 10_000_001, 2_000_000_000] {
            assert_eq!(check_context_limit(bad), Err("Use 10k to 10M, or off.".to_string()), "{bad}");
        }
        let (d, dir) = daemon();
        assert!(d.launch(NewOpts { context_limit: Some(5_000), ..Default::default() }).unwrap_err().contains("10k to 10M"), "the spawn pin is refused");
        let sid = d.launch(NewOpts::default()).unwrap();
        let mut conn = Conn::default();
        let frame = |limit: serde_json::Value| Envelope::new(pf::SESSION_CONTEXT_LIMIT, json!({"sessionId": sid, "limit": limit}).to_string());
        let out = d.handle(frame(json!(5_000)), &mut conn).join("");
        assert!(out.contains("10k to 10M"), "the frame is refused: {out}");
        assert_eq!(limit(&d, &sid).0, Some(500_000), "not clamped, not changed");
        let ok = d.handle(frame(json!(250_000)), &mut conn).join("");
        assert!(ok.contains("250000"), "{ok}");
        let _ = std::fs::remove_dir_all(dir);
    }
}

#[cfg(test)]
mod ghost_tests {
    //! SESSION-GHOST-1: a session written after its delete (a caller found it before, wrote after) never comes back to
    //! the list; an id only the list still knows is removable; a restart serves no ghost.
    use super::*;

    fn daemon() -> (Arc<Daemon>, std::path::PathBuf) {
        let dir = std::env::temp_dir().join(format!("infantry-ghost-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        (Daemon::new(Arc::new(crate::harness::stub::Stub::default()), Config::new(dir.clone(), dir.clone())).unwrap(), dir)
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn a_write_after_the_delete_never_brings_the_session_back() {
        let (d, dir) = daemon();
        let sid = d.launch(NewOpts { prompt: Some("go".into()), ..Default::default() }).unwrap();
        // A late frame's path found the entry before the delete and writes after it (the turn end of a worker ended).
        let held = d.find(&sid).unwrap();
        d.delete(&sid).unwrap();
        {
            let mut e = held.lock().unwrap();
            Daemon::set_state(&mut e, SessionState::Busy);
            d.push(&mut e);
        }
        assert!(d.record(&sid).is_none(), "the deleted session is back on the list");
        d.flush();
        drop(d);
        let again = Daemon::new(Arc::new(crate::harness::stub::Stub::default()), Config::new(dir.clone(), dir.clone())).unwrap();
        assert!(again.record(&sid).is_none(), "a restart serves the ghost");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn an_id_only_the_list_knows_is_removed_from_the_list() {
        let (d, dir) = daemon();
        let sid = d.launch(NewOpts::default()).unwrap();
        // The list copy outlives the session manager's entry (the ghost state seen on the owner's daemon).
        d.list.lock().unwrap().retain(|(i, _)| i != &sid);
        assert!(d.record(&sid).is_some());
        d.delete_session(&sid).expect("removing a list-only id is OK");
        assert!(d.record(&sid).is_none());
        assert!(d.delete_session(&sid).unwrap_err().contains("no such session"));
        let _ = std::fs::remove_dir_all(&dir);
    }
}

/// Queue ids (opaque to clients) carry who queued the turn: `q-` the owner typed it, `d-` the daemon delivered it.
const OWNER_PREFIX: &str = "q-";
const DELIVERED_PREFIX: &str = "d-";

fn is_owner_turn(q: &QueuedTurn) -> bool {
    !q.id.starts_with(DELIVERED_PREFIX)
}

#[cfg(test)]
mod agent_token_tests {
    use super::set_token;

    #[test]
    fn a_session_env_carries_its_own_token_and_never_the_owners() {
        let mut env = vec![("INFANTRY_TOKEN".to_string(), "owner".to_string()), ("AGENTHQ_TOKEN_FILE".to_string(), "C:/owner.token".to_string()), ("X".to_string(), "1".to_string())];
        let mut mcp = infantry_harness::McpPlan::default();
        set_token(&mut env, &mut mcp, "session-token");
        let get = |k: &str| env.iter().filter(|(n, _)| n == k).map(|(_, v)| v.as_str()).collect::<Vec<_>>();
        assert_eq!(get("INFANTRY_TOKEN"), vec!["session-token"]);
        // The inherited owner credentials are overridden blank (a child env entry wins over the inherited one).
        for k in ["INFANTRY_TOKEN_FILE", "AGENTHQ_TOKEN", "AGENTHQ_TOKEN_FILE"] {
            assert_eq!(get(k), vec![""], "{k}");
        }
        assert_eq!(get("X"), vec!["1"]);
    }
}
