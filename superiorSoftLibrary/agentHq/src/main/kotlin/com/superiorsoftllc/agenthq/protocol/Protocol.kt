package com.superiorsoftllc.agenthq.protocol

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * One WebSocket frame, in both directions.
 *
 * Phase 1 vocabulary: the daemon sends [READY], then for each prompt a stream of [STDOUT] / [STDERR]
 * lines and one [EXIT]; [ERROR] reports a daemon-side failure. The client sends [PROMPT].
 *
 * Phase 3 adds the session lifecycle without touching the provider seam:
 *  - [SESSION] carries one session snapshot as JSON in [data]; [SESSIONS] carries the list.
 *  - [KILL] and [RESUME] are client commands (see the README for their [data] encoding).
 *  - [sessionId] tags streamed [STDOUT] / [STDERR] / [EXIT] frames with the session they belong to;
 *    it is optional so a Phase 1/2 client that never knew the field keeps working (`ignoreUnknownKeys`).
 *
 * Phase 4 adds worktree visibility over the SAME socket:
 *  - [WORKTREE] carries one `WorktreeStatus` as JSON in [data]; [WORKTREES] carries the list.
 *  - [REPO] is a client command setting the repository that subsequent new sessions are cut from
 *    (empty [data] clears it); [WORKTREE_REMOVE] (`"<sessionId> [force]"`) deliberately cleans up a
 *    session's worktree. The worktree's path and branch are ALSO on every [SESSION] record.
 *
 * Phase 6 adds the mailbox (agent-to-agent messaging) over the SAME socket:
 *  - [MAIL_SEND] carries a `MailSendRequest` as JSON; the daemon answers with [MAIL] (the stored
 *    message) or [MAIL_REFUSED]. [MAIL_READ] (`"<messageId>"`) acknowledges a message.
 *  - [MAIL_INBOX] / [MAIL_OUTBOX] (`"<sessionId>"`, blank = this connection's active session)
 *    request messages; [MAIL_THREAD] (`"<threadId>"`), [MAIL_THREADS] and [MAIL_WAITING] read the
 *    mailbox. Answers are [MAILS] (a list), [MAIL] (one), [MAIL_THREADS] (list of `ThreadSummary`)
 *    and [MAIL_WAITING] (list of `WaitingOn`).
 *
 * Phase 9 freezes the **public event vocabulary** alongside the raw stream frames:
 *  - [TEXT_DELTA] carries incremental assistant text, normalised from whichever provider ran;
 *  - [STATUS] carries a lifecycle transition as a machine-readable label;
 *  - [FINISHED] carries how the run ended (exit code + outcome), so a client needs no pid watch.
 *  [STDOUT] / [STDERR] / [EXIT] stay exactly as they were (raw provider lines), so an existing client
 *  is untouched; the new frames are ADDITIVE and are what a UI should render. See
 *  `com.superiorsoftllc.agenthq.protocol.EventVocabulary` for the provider -> neutral mapping and
 *  `CONTRACT.md` for the published boundary.
 *
 * Phase 5b adds the pod wire surface over the SAME socket, taking the contract to **1.1.0**:
 *  - [POD_SUBMIT] submits a pod (a `PodSubmitRequest` as JSON), [POD_QUERY] reads one pod's state
 *    (`"<podId>"`), [POD_CANCEL] cancels one (`"<podId> [force]"`).
 *  - all three are answered with [POD], carrying a `PodStatus` as JSON -- the pod record, its nodes
 *    and its runs -- so one frame answers "what is this pod's state now" for every command.
 *  - [POD_SUBMIT] / [POD_QUERY] / [POD_CANCEL] / [POD] are all NEW. Nothing Phase 9 froze moved,
 *    which is what makes 1.1.0 a minor bump rather than a break; see [EventVocabulary.VERSION].
 *
 * Contract **1.3.0** lets one daemon serve several providers:
 *  - [NEW] may carry a `SpawnOptions` JSON object (`{"provider":"claude","model":"sonnet"}`) choosing
 *    the provider and model of the sessions this connection creates next. Empty [data] keeps its
 *    pre-1.3.0 meaning.
 *  - [PROVIDERS] is a client request, answered by a [PROVIDERS] frame listing every `ProviderInfo`
 *    this daemon can spawn, the default first.
 *
 * Contract **1.4.0** adds agent profiles (story 9.1):
 *  - [AGENT_SAVE] (an `AgentProfile` as JSON) is answered by [AGENT] (the profile as stored);
 *    [AGENT_LIST] by [AGENTS] (every profile, oldest first); [AGENT_DELETE] (a profile id) by [AGENTS].
 *  - `SpawnOptions` gains `agentId`, and a session spawned from a profile records `agentId` and `role`.
 *
 * Contract **1.5.0** adds the backlog (story 9.2), owned by the daemon:
 *  - [TICKET_SAVE], [TICKET_GET], [TICKET_MOVE], [TICKET_LINK] and [TICKET_UNLINK] are answered by
 *    [TICKET] (one `Ticket`); [TICKET_LIST], [TICKET_REORDER] and [TICKET_DELETE] by [TICKETS] (the board).
 *  - Every change is pushed to EVERY client as it happens: one [TICKET] per changed ticket, or one
 *    [TICKETS] after a delete or a reorder. A request that changed nothing is answered to its sender only.
 *
 * Contract **1.7.0** adds per-session sequence numbers and replay after reconnect (AgentHQ F2):
 *  - Session-stream frames carry an increasing per-session `sequence`; `replay_request` requests frames
 *    after an exclusive cursor, and `replay_gap` reports frames evicted from the bounded buffer.
 *
 * Contract **1.11.0** puts both sides of a conversation on the session stream (talk to an agent):
 *  - [TURN] carries the prompt a run was started with, exactly as the provider received it. It is a
 *    sequenced session-stream frame, emitted before the reply it causes, so `replay_request` rebuilds
 *    the whole conversation and every connected client sees what was asked, not only what was answered.
 *  - [READY] also names the daemon process (`instance=<id>`) and the epoch millis its replay history
 *    begins (`since=<millis>`), because sequence numbers restart with the daemon.
 *
 * Contract **1.14.0** serves manual triggers (story 9.5 wiring):
 *  - [TRIGGER_SAVE], [TRIGGER_ARM] and [TRIGGER_DISABLE] change a trigger, which is pushed to EVERY client
 *    as [TRIGGER]; [TRIGGER_FIRE] (`"<triggerId>"`) records one event and answers [TRIGGER];
 *    [TRIGGER_LIST] answers [TRIGGERS]; [TRIGGER_DELETE] pushes [TRIGGERS]; [TRIGGER_EVENTS]
 *    (`"<triggerId>"`) answers [TRIGGER_EVENTS] with that trigger's event dispositions.
 *
 * Contract **1.13.0** wires usage and limit state (story 9.7b):
 *  - [USAGE] is pushed, sequenced on the session stream, when a run that reported usage ends;
 *  - [PROVIDER_STATE] is pushed when a provider's observed limit state changes; [PROVIDER_STATES] asks for
 *    (and answers with) the latest state of every provider, `unknown` until something is observed.
 *
 * Contract **1.12.0** puts the role catalog on the wire:
 *  - [ROLE_LIST] is answered by [ROLES] (every role, canonical ids first);
 *  - [ROLE_SAVE] (a `RoleSave` as JSON) writes a custom role definition and pushes the stored [ROLE] to
 *    EVERY client; a refusal is an `error` naming the problem, and nothing is written.
 *
 * Contract **1.6.0** adds ticket hand-out and controlled context (story 9.3):
 *  - [TICKET_START_NEXT] starts the top ready ticket; moving one to `in_progress` uses the same daemon path.
 *  - [SETTINGS_GET] / [SETTINGS_SET] are answered by [SETTINGS].
 *  - `Ticket` gains executor, queue, effective-context and run fields; `SessionRecord` gains `ticketId`
 *    and `contextSources`.
 *
 * The **MVP Sprint-Zero** frames (slice 0 of ticket b2fbb94d) are FROZEN, NOT YET PUBLISHED: the
 * names below exist so both implementation slices compile against one seam, and the daemon refuses
 * them until the slices land. Project (slice A): [PROJECT_SAVE] / [PROJECT_GET] / [PROJECT_LIST]
 * are answered by [PROJECT] / [PROJECTS]. Story board (slice B): [STORY_SAVE] / [STORY_GET] /
 * [STORY_MOVE] are answered by [STORY], [STORY_LIST] by [STORIES]. `Ticket` gains the nullable
 * [Ticket.projectId] key and `Story` carries [Story.projectId]; I0 owned the ordered join, which at
 * the time lived on `Project.storyIds` (RETIRED, contract 1.29.0: it now lives on
 * `Epic.storyIds` instead). The version bump is taken once, at integration, by the bundle
 * these two slices share -- NOT here. See `protocol/Projects.kt` and `protocol/Stories.kt`.
 *
 * Frames are JSON so the envelope can grow without breaking an older client.
 */
/**
 * BUG-MCP-RACE-1: the first contract whose daemon echoes [Envelope.requestId] (see [Envelope.CORRELATED_REQUESTS]).
 * Top level, not in [Envelope]: a frame-name scan of Envelope's static strings must see frames only.
 */
const val REQUEST_ID_CONTRACT = "1.87.0"

/**
 * Contract 1.96.0 (ticket-b5aa01bc): the prefix of the [Envelope.ERROR] refusing a [Envelope.PROMPT] whose session
 * already has a run in flight: `run_in_flight <sessionId>: <reason>`. Nothing ran and nothing was echoed; the prompt can
 * be sent again once that run ends. Carries the prompt's [Envelope.requestId] when it had one. Top level, like
 * [REQUEST_ID_CONTRACT]: it is an error prefix, not a frame.
 */
const val RUN_IN_FLIGHT = "run_in_flight"

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Envelope(
    val type: String,
    val data: String = "",
    val sessionId: String? = null,
    /** F2's per-session sequence; absent on control frames and frames from older daemons. */
    val sequence: Long? = null,
    /**
     * BUG-MCP-RACE-1 (contract 1.87.0): a client-chosen id correlating a request with ITS answer. The daemon echoes it
     * on the direct answer (or `error`) to that request, and NEVER on a push to every client, so a client can tell its
     * own `ticket` answer from another ticket's broadcast of the same type. Omitted when null, so a frame without it is
     * byte-for-byte what a pre-1.87 peer sends.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val requestId: String? = null,
) {
    companion object {
        /**
         * BUG-MCP-RACE-1: the requests whose answer type the daemon also BROADCASTS (a board change is pushed to every
         * client as `ticket` / `tickets`). From [REQUEST_ID_CONTRACT], a request of these types carrying a [requestId]
         * is ALWAYS answered directly with that id, even when the push already carried the change, so the requester
         * waits for its own id and never takes a bystander's push.
         */
        val CORRELATED_REQUESTS: Set<String> = setOf(
            TICKET_SAVE, TICKET_GET, TICKET_LIST, TICKET_MOVE, TICKET_LINK,
            TICKET_UNLINK, TICKET_REORDER, TICKET_DELETE, TICKET_START_NEXT,
        )

        const val READY = "ready"
        const val PROMPT = "prompt"
        const val STDOUT = "stdout"
        const val STDERR = "stderr"
        const val EXIT = "exit"
        const val ERROR = "error"

        // Phase 3 -- session lifecycle.
        const val SESSION = "session"
        const val SESSIONS = "sessions"
        const val KILL = "kill"

        /**
         * Interrupt the session's CURRENT turn: stop its run and return the session to rest (IDLE) with
         * the same session id, provider conversation and transcript. Unlike [KILL], the session survives.
         * `data` is the session id. Refused with [ERROR] when no run is in flight.
         */
        const val INTERRUPT = "interrupt"

        // IN-APP BUILD (contract 1.33.0, additive): saved commands and a single in-flight run. Design:
        // docs/AGENTHQ-IN-APP-BUILD-DESIGN.md section 3. The daemon sends NO argv to a client.
        /** client -> daemon, empty data: request the saved commands; answered by [COMMANDS]. */
        const val COMMAND_LIST = "command_list"
        /**
         * daemon -> client: list of `CommandSpec` `{id, name, cwd, argv, graceMillis}` (argv + graceMillis since 1.94.0).
         * Also pushed to EVERY client after a [COMMAND_SAVE] / [COMMAND_DELETE].
         */
        const val COMMANDS = "commands"
        // RUN-CMD-1 (contract 1.94.0, additive): edit the saved commands from a client; the daemon writes commands.json.
        /** client -> daemon: `{id, name?, argv: [...], cwd?: relative|null, graceMillis?}`, upsert by id; answered by [COMMANDS]. */
        const val COMMAND_SAVE = "command_save"
        /** client -> daemon: `{"id": "<id>"}`; answered by [COMMANDS]. Both refuse with [ERROR] `command_<op> refused: ...`. */
        const val COMMAND_DELETE = "command_delete"
        /** client -> daemon: `{"commandId": "<id>"}`; refused with [ERROR] on unknown id or a run in flight. */
        const val RUN_START = "run_start"
        /** daemon -> client: one `RunRecord`, the answer to [RUN_START]. */
        const val RUN = "run"
        /** daemon -> client: one output line `{runId, sequence, stream, line, truncated}`. */
        const val RUN_OUTPUT = "run_output"
        /** daemon -> client: sent exactly once per run `{runId, exitCode, outcome, endedAt, detail}`. */
        const val RUN_FINISHED = "run_finished"
        /** client -> daemon: `"<runId> [force]"`; a kill of the process tree, answered by [RUN_FINISHED]. */
        const val RUN_STOP = "run_stop"
        const val RESUME = "resume"
        const val NEW = "new"

        // AGENT-UX-11 (needs contract 1.26.0): remove one session's durable record. `session_delete`
        // carries the session id; the daemon pushes the refreshed `sessions` list to every client and
        // refuses, without touching anything, an unknown id or a session that is currently running.
        const val SESSION_DELETE = "session_delete"

        // BUG-M2-15b (contract 1.61.0): bind an unbound session to a project. `session_project` carries
        // {"sessionId","projectId"}; the answer is the changed `session` record pushed to every client. Refused as an
        // `error` frame, nothing written: an unknown session, an unknown project, or a session bound to another project.
        const val SESSION_PROJECT = "session_project"

        // ROLE-NOTES-1: `session_restart` carries {"sessionId","force"?}; reboots the session in place as a never-run
        // session (same id, role, harness, project, worktree) whose next turn is composed anew. The changed `session`
        // record is pushed to every client; a refusal (unknown id, mid-turn without force) is an `error` frame.
        const val SESSION_RESTART = "session_restart"

        // Phase 4 -- worktree isolation.
        const val WORKTREE = "worktree"
        const val WORKTREES = "worktrees"
        const val WORKTREE_REMOVE = "worktree_remove"
        const val REPO = "repo"

        // Phase 6 -- the mailbox (agent-to-agent messaging).
        const val MAIL_SEND = "mail_send"
        const val MAIL_READ = "mail_read"
        const val MAIL_INBOX = "mail_inbox"
        const val MAIL_OUTBOX = "mail_outbox"
        const val MAIL_THREAD = "mail_thread"
        const val MAIL = "mail"
        const val MAILS = "mails"
        const val MAIL_THREADS = "mail_threads"
        const val MAIL_WAITING = "mail_waiting"

        /** `agenthq bg run`: register / heartbeat / finish / list a session's registered background jobs ([com.superiorsoftllc.agenthq.session.BgJobRequest]); answered by a `bg_job` list. */
        const val BG_JOB = "bg_job"
        const val MAIL_REFUSED = "mail_refused"

        // Phase 9 -- the public, provider-neutral event vocabulary (see EventVocabulary).
        // These four are what a UI renders; the raw STDOUT/STDERR/EXIT frames stay for
        // backward compatibility and for anyone who wants the provider's own bytes.
        const val TEXT_DELTA = "text_delta"
        const val STATUS = "status"
        const val FINISHED = "finished"

        // Contract 1.23.0 -- the model's reasoning, kept as its own frame so a UI can render it
        // (collapsed, say) apart from [TEXT_DELTA]'s answer text.
        const val REASONING = "reasoning"

        // Contract 1.11.0 -- the human side of a conversation: the prompt a run was started with,
        // sequenced on the session stream in front of the reply it caused.
        const val TURN = "turn"

        // Contract 1.12.0 -- the role catalog. `role_list` -> `roles`; `role_save` -> `role`, pushed to all.
        const val ROLE_LIST = "role_list"
        const val ROLE_SAVE = "role_save"
        const val ROLE = "role"
        const val ROLES = "roles"

        // Contract 1.40.0 (ROLE-1c): what a role runs on. `role_binding_list` -> `role_bindings`; `role_binding_get`
        // (data = role id) -> `role_binding`; `role_binding_set` -> `role_binding`, pushed to all.
        const val ROLE_BINDING_LIST = "role_binding_list"
        const val ROLE_BINDING_GET = "role_binding_get"
        const val ROLE_BINDING_SET = "role_binding_set"
        const val ROLE_BINDING = "role_binding"
        const val ROLE_BINDINGS = "role_bindings"

        // Contract 1.44.0 (P10-A): the prompt library. `prompt_save` -> `prompt_item`, pushed to all; `prompt_list` (data = optional tag
        // filter) -> `prompts`; `prompt_get` (data = id) -> `prompt_item`; `prompt_delete` (data = id) -> `prompts`, pushed to all.
        const val PROMPT_SAVE = "prompt_save"
        const val PROMPT_LIST = "prompt_list"
        const val PROMPT_GET = "prompt_get"
        const val PROMPT_DELETE = "prompt_delete"
        const val PROMPT_ITEM = "prompt_item"
        const val PROMPTS = "prompts"

        // Contract 1.45.0 (P10-B): the skills library. `skill_save` -> `skill_item`, pushed to all; `skill_list` (data = optional tag
        // filter) -> `skills`; `skill_get` (data = id, or since 1.56.0 an exact name) -> `skill_item`; `skill_delete` (data = id) -> `skills`, pushed to all.
        const val SKILL_SAVE = "skill_save"
        const val SKILL_LIST = "skill_list"
        const val SKILL_GET = "skill_get"
        const val SKILL_DELETE = "skill_delete"
        const val SKILL_ITEM = "skill_item"
        const val SKILLS = "skills"

        // Contract 1.48.0 (TRAITS-T1): the trait library. `trait_save` (TraitSave, save-as-update by id) -> `trait_item`, pushed
        // to all; `trait_list` -> `traits`; `trait_get` (data = id) -> `trait_item`; `trait_delete` (data = id) -> `traits`, pushed to all.
        const val TRAIT_SAVE = "trait_save"
        const val TRAIT_LIST = "trait_list"
        const val TRAIT_GET = "trait_get"
        const val TRAIT_DELETE = "trait_delete"
        const val TRAIT_ITEM = "trait_item"
        const val TRAITS = "traits"

        // Contract 1.49.0 (TRAITS-T2): a role's ordered trait list. `role_traits_set` (RoleTraitsSave, strict; `[]` clears)
        // -> `role_traits`, pushed to all; `role_traits_get` (data = role id) -> `role_traits`; `role_traits_list` -> `role_traits_all`.
        const val ROLE_TRAITS_SET = "role_traits_set"
        const val ROLE_TRAITS_GET = "role_traits_get"
        const val ROLE_TRAITS_LIST = "role_traits_list"
        const val ROLE_TRAITS = "role_traits"
        const val ROLE_TRAITS_ALL = "role_traits_all"

        // Contract 1.13.0 -- story 9.7b: observed usage per run and limit state per provider.
        const val USAGE = "usage"
        const val PROVIDER_STATE = "provider_state"
        const val PROVIDER_STATES = "provider_states"

        // Contract 1.59.0 (U1): plan-limit meters read live from each provider's usage endpoint. `limits` (empty data)
        // is answered by `limits` (a list of `ProviderLimits`), also pushed to every client after a run's `usage` frame.
        const val LIMITS = "limits"

        // Contract 1.14.0 -- story 9.5: triggers. Changes are pushed to every client.
        const val TRIGGER_SAVE = "trigger_save"
        const val TRIGGER_LIST = "trigger_list"
        const val TRIGGER_FIRE = "trigger_fire"
        const val TRIGGER_ARM = "trigger_arm"
        const val TRIGGER_DISABLE = "trigger_disable"
        const val TRIGGER_DELETE = "trigger_delete"
        const val TRIGGER_EVENTS = "trigger_events"
        const val TRIGGER = "trigger"
        const val TRIGGERS = "triggers"

        // Phase 5b -- the pod command surface. NEW frames, additive: a client built against 1.0.0
        // never sees these and is unaffected by them.
        const val POD_SUBMIT = "pod_submit"
        const val POD_QUERY = "pod_query"
        const val POD_CANCEL = "pod_cancel"
        const val POD = "pod"

        // Contract 1.3.0 -- per-spawn provider. A NEW request/answer pair; nothing older moved.
        const val PROVIDERS = "providers"

        // Model picker: the request and its answer share this frame (additive).
        const val MODELS = "models"

        // CP-4: the harness's `/` command catalog. The request and its answer share this frame (additive).
        const val COMMAND_CATALOG = "command_catalog"

        // CP-4 slice d (1.39.0): a `/` command ran against a session; carries a CommandResultAnswer (additive).
        const val COMMAND_RESULT = "command_result"

        // Contract 1.4.0 -- agent profiles. Requests and their answers, all NEW.
        const val AGENT_SAVE = "agent_save"
        const val AGENT_LIST = "agent_list"
        const val AGENT_DELETE = "agent_delete"
        const val AGENT = "agent"
        const val AGENTS = "agents"

        // Contract 1.47.0 (role-first S3): `agent_swap` (an AgentSwap) moves one agent to another executor; answered by
        // `agent_swapped` (an AgentSwapped) pushed to EVERY client. A refusal is an `error` frame, nothing written.
        const val AGENT_SWAP = "agent_swap"
        const val AGENT_SWAPPED = "agent_swapped"

        // UX-HARNESS-2 (contract 1.83.0): `session_swap` (a SessionSwap) restarts one session that has no agent on
        // another harness; answered by `session_swapped` (a SessionSwapped) pushed to EVERY client. A refusal is an
        // `error` frame "session_swap refused: <reason>", nothing written.
        const val SESSION_SWAP = "session_swap"
        const val SESSION_SWAPPED = "session_swapped"

        // AGENT-ROSTER: the live, project-scoped "who's here" list. `agent_roster` carries
        // {"projectId": ...} and is answered by `roster`, the JSON list of that project's roster
        // rows; a blank projectId is refused as an `error` frame. Additive: nothing older moved.
        const val AGENT_ROSTER = "agent_roster"
        const val ROSTER = "roster"

        // STATS-1 (contract 1.92.0): `session_stats_get` {"sessionId"} asks for one session's statistics, read from its
        // harness's own local record; answered to the requester by `session_stats` (a SessionStats, every field the
        // harness does not expose null). An unknown or blank session id is an `error` "session_stats_get refused: ...".
        const val SESSION_STATS_GET = "session_stats_get"
        const val SESSION_STATS = "session_stats"

        // Contract 1.5.0 -- the backlog. Requests, their answers and the pushes, all NEW.
        const val TICKET_SAVE = "ticket_save"
        const val TICKET_GET = "ticket_get"
        const val TICKET_LIST = "ticket_list"
        const val TICKET_MOVE = "ticket_move"
        const val TICKET_LINK = "ticket_link"
        const val TICKET_UNLINK = "ticket_unlink"
        const val TICKET_REORDER = "ticket_reorder"
        const val TICKET_DELETE = "ticket_delete"
        const val TICKET = "ticket"
        const val TICKETS = "tickets"

        // Contract 1.6.0 -- story 9.3: start the top startable ticket, and the daemon's settings.
        // `ticket_start_next` is answered by [TICKET]; `settings_get` / `settings_set` by [SETTINGS].
        const val TICKET_START_NEXT = "ticket_start_next"
        const val SETTINGS_GET = "settings_get"
        const val SETTINGS_SET = "settings_set"
        const val SETTINGS = "settings"

        // Contract 1.73.0 -- CLIPATH-1: where each CLI (claude, opencode, pi, descriptor harnesses, git) is launched
        // from. `cli_locations` (no data) and `cli_location_set` ({id, path?}; null/blank path clears) are both
        // answered by [CLI_LOCATIONS] (`CliLocations`); a bad path is refused as an `error`.
        const val CLI_LOCATIONS = "cli_locations"
        const val CLI_LOCATION_SET = "cli_location_set"

        // Contract 1.100.0 -- HARNESS-INSTALL-1: the built-in harness installer. `harness_install_status` ({checkLatest?}) is
        // answered by [HARNESS_INSTALL_STATUS] (`HarnessInstallReport`); `harness_install` ({ids?, update?}) by
        // [HARNESS_INSTALL_RESULT] (`HarnessInstallResult`). A bad id is refused as an `error`.
        const val HARNESS_INSTALL_STATUS = "harness_install_status"
        const val HARNESS_INSTALL = "harness_install"
        const val HARNESS_INSTALL_RESULT = "harness_install_result"

        // Contract 1.76.0 -- BUG-PERM-1: the global, harness-neutral Tool approval (deny / allowEdits / allowAll).
        // `tool_approval` (no data) and `tool_approval_set` ({mode?}; null/blank clears) are both answered by
        // [TOOL_APPROVAL] (`ToolApprovalInfo`); an unknown mode is refused as an `error`.
        const val TOOL_APPROVAL = "tool_approval"
        const val TOOL_APPROVAL_SET = "tool_approval_set"

        // Contract 1.77.0 -- PROMPT-1: the owner answers a harness's tool approval or question from the chat.
        // [PROMPT_REQUEST] (daemon -> every client, `PromptRequest`, envelope sessionId = the asking session) opens a
        // card; it is also replayed to a connecting client for every prompt still open. [PROMPT_RESPONSE] (client ->
        // daemon, `PromptResponse`) answers one; an unknown, already resolved or malformed answer is an `error`
        // "prompt_response refused: ...". [PROMPT_RESOLVED] (daemon -> every client, `PromptResolved`) closes the card
        // everywhere, whoever or whatever (timeout, session end) resolved it.
        const val PROMPT_REQUEST = "prompt_request"
        const val PROMPT_RESPONSE = "prompt_response"
        const val PROMPT_RESOLVED = "prompt_resolved"

        // Contract 1.78.0 -- PROMPT-6: "Always allow". `prompt_response` takes the decision `allow_always`, which
        // stores an `ApprovalRule` for the asking session's project. [APPROVAL_RULES] (client -> daemon,
        // `ApprovalRulesQuery`) and [APPROVAL_RULE_DELETE] (client -> daemon, `ApprovalRuleDelete`) are both answered by
        // [APPROVAL_RULES] (`ApprovalRulesList`); a bad request is an `error`.
        const val APPROVAL_RULES = "approval_rules"
        const val APPROVAL_RULE_DELETE = "approval_rule_delete"
        // Contract 1.79.0 -- PROMPT-5: any harness asks the owner through the `agenthq ask` CLI. [PROMPT_ASK] (client ->
        // daemon, `PromptAsk`, envelope sessionId = the asking session) opens a `question` prompt for that session;
        // [PROMPT_ANSWER] (daemon -> that connection only, `PromptAnswer`) carries its outcome once it resolves.
        const val PROMPT_ASK = "prompt_ask"
        const val PROMPT_ANSWER = "prompt_answer"

        // Contract 1.97.0 -- ASK-1: owner questions, NON-BLOCKING and stored (`QuestionFrames.kt`). [QUESTION_ASK]
        // (agent -> daemon, `QuestionAsk`, envelope sessionId = the asker) is answered at once by [QUESTION_ASKED]
        // (`QuestionAsked`); the owner's pick reaches the asker later as mail. [QUESTION] (daemon -> every client,
        // `OwnerQuestion`) pushes every new or changed question; [QUESTIONS] (`List<OwnerQuestion>`, oldest first)
        // answers [QUESTION_LIST] and is sent to every connecting client. [QUESTION_ANSWER] (`QuestionAnswer`) and
        // [QUESTION_CANCEL] (`QuestionCancel`) are answered by the [QUESTION] push. A refusal is an `error`
        // "<frame> refused: ...".
        const val QUESTION_ASK = "question_ask"
        const val QUESTION_ASKED = "question_asked"
        const val QUESTION = "question"
        const val QUESTION_LIST = "question_list"
        const val QUESTIONS = "questions"
        const val QUESTION_ANSWER = "question_answer"
        const val QUESTION_CANCEL = "question_cancel"

        // Contract 1.83.0 -- IMG-1: image uploads. [IMAGE_UPLOAD] (client -> daemon, `ImageUpload`) stores a png, jpeg,
        // gif or webp (magic bytes checked, at most 10 MB) at `<project directory>/.agenthq/uploads/<sha256>.<ext>`;
        // [IMAGE] (`ImageStored`) answers its absolute path. Anything else is an `error` "image_upload refused: ...".
        const val IMAGE_UPLOAD = "image_upload"
        const val IMAGE = "image"

        // Contract 1.8.0 -- durable attention history, acknowledgement and broadcast updates.
        const val ATTENTION = "attention"
        const val ATTENTION_LIST = "attention_list"
        const val ATTENTION_ITEMS = "attention_items"
        const val ATTENTION_ACK = "attention_ack"

        // MVP Sprint-Zero (slice 0 freeze). FROZEN NAMES, NO DAEMON BEHAVIOUR YET: the daemon answers
        // these with `error` until the implementing slices land. Project -- slice A
        // (branch feature-agenthq-mvp-scope): `project_save` / `project_get` / `project_list` are
        // answered by `project` / `projects`. The ordered join was, at the time, maintained by I0 on
        // `Project.storyIds`, not by slice A (RETIRED, contract 1.29.0: it now lives on
        // `Epic.storyIds` instead). Story board -- slice B (branch feature-agenthq-mvp-board):
        // `story_save` / `story_get` / `story_move` by `story`, `story_list` by `stories`.
        const val PROJECT_SAVE = "project_save"
        const val PROJECT_GET = "project_get"
        const val PROJECT_LIST = "project_list"
        const val PROJECT = "project"
        const val PROJECTS = "projects"
        // BASE-1a (contract 1.81.0): read, set or clear a project's base branch; answered by `project`.
        const val PROJECT_BASE = "project_base"
        // CP-2 (contract 1.36.0): git over a project's own directory (`Project.directory`). Requests:
        // `project_git_status` (raw project id), `project_git_diff` and `project_git_log` (JSON). Answers:
        // `project_git` (branch, head, changed files), `project_git_patch` (one file's diff) and
        // `project_git_history` (commits). A refusal is the standing `error` frame naming its request.
        const val PROJECT_GIT_STATUS = "project_git_status"
        const val PROJECT_GIT_DIFF = "project_git_diff"
        const val PROJECT_GIT_LOG = "project_git_log"
        const val PROJECT_GIT = "project_git"
        const val PROJECT_GIT_PATCH = "project_git_patch"
        const val PROJECT_GIT_HISTORY = "project_git_history"
        // BRANCH-VIEW-1 (contract 1.65.0): local branches and one branch's changes since its merge-base
        // with a base. `project_git_branches` -> `project_git_branch_list`, `project_git_branch_changes`
        // -> `project_git_branch_diffstat`, `project_git_branch_diff` -> `project_git_patch` (with `branch`).
        const val PROJECT_GIT_BRANCHES = "project_git_branches"
        const val PROJECT_GIT_BRANCH_CHANGES = "project_git_branch_changes"
        const val PROJECT_GIT_BRANCH_DIFF = "project_git_branch_diff"
        const val PROJECT_GIT_BRANCH_LIST = "project_git_branch_list"
        const val PROJECT_GIT_BRANCH_DIFFSTAT = "project_git_branch_diffstat"
        // BRANCH-SWITCH-1 (contract 1.74.0): switch (or create and switch) the project's checked-out branch.
        // `project_git_checkout` -> `project_git_checkout_result`; a switch that happened also pushes `project_git`.
        const val PROJECT_GIT_CHECKOUT = "project_git_checkout"
        const val PROJECT_GIT_CHECKOUT_RESULT = "project_git_checkout_result"
        // UX-CHANGES-1 (contract 1.88.0): the repository's worktrees and one worktree's state, read-only.
        // `project_git_worktrees` -> `project_git_worktree_list`, `project_git_worktree_status` -> `project_git_worktree`.
        const val PROJECT_GIT_WORKTREES = "project_git_worktrees"
        const val PROJECT_GIT_WORKTREE_STATUS = "project_git_worktree_status"
        const val PROJECT_GIT_WORKTREE_LIST = "project_git_worktree_list"
        const val PROJECT_GIT_WORKTREE = "project_git_worktree"
        // PW-5 (contract 1.18.0): the project's workspaces. `workspace_save` is answered by `workspace`,
        // `workspace_list` (raw project id payload) by `workspaces`.
        const val WORKSPACE_SAVE = "workspace_save"
        const val WORKSPACE_LIST = "workspace_list"
        const val WORKSPACE = "workspace"
        const val WORKSPACES = "workspaces"
        // EPIC-4 (contract 1.19.0): a project's epics. `epic_save` is answered by `epic`, `epic_list`
        // (raw project id payload, like `project_get` / `workspace_list`) by `epics`.
        const val EPIC_SAVE = "epic_save"
        const val EPIC_LIST = "epic_list"
        // BASE-1c (contract 1.82.0): read, set or clear an epic's base branch (overrides the project's); answered by `epic`.
        const val EPIC_BASE = "epic_base"
        const val EPIC = "epic"
        const val EPICS = "epics"
        const val STORY_SAVE = "story_save"
        const val STORY_GET = "story_get"
        const val STORY_LIST = "story_list"
        const val STORY_MOVE = "story_move"
        const val STORY = "story"
        const val STORIES = "stories"
        // MEM-5 (contract 1.20.0): project-memory search. `memory_search` ({projectId, query}) is
        // answered by `memory_hits`: the project's ranked hits, each {name, description, snippet}.
        const val MEMORY_SEARCH = "memory_search"
        const val MEMORY_HITS = "memory_hits"
        // MEM-8 (contract 1.21.0): write and list a project's memory notes. `memory_save`
        // ({projectId, name, description, body}) is answered by `memory_note` (the stored note);
        // `memory_list` (raw project id payload, like `epic_list`) by `memory_notes`: the project's
        // notes as {name, description}, in name order.
        const val MEMORY_SAVE = "memory_save"
        const val MEMORY_LIST = "memory_list"
        const val MEMORY_NOTE = "memory_note"
        const val MEMORY_NOTES = "memory_notes"
        // P10-K2 (contract 1.51.0): `memory_get` ({projectId, name}) reads one note, answered by `memory_note`
        // or refused `memory_get refused: note_not_found: ...`. `memory_save` also takes mode/find/content/folder.
        const val MEMORY_GET = "memory_get"

        /** NOTES-1 (contract 1.98.0): delete one note of a scope; answered by [MEMORY_NOTES]. */
        const val MEMORY_DELETE = "memory_delete"

        /** NOTES-1: purge every role note of a role (needs a typed confirmation); answered by [MEMORY_NOTES]. */
        const val MEMORY_PURGE_ROLE = "memory_purge_role"
        // P10-K4 (contract 1.53.0): the project's append-only decisions log. `decision_add`
        // ({projectId, sessionId?, summary, body?, supersedes?}) is answered by a `decision_item` PUSHED to every client;
        // `decision_list` ({projectId, includeSuperseded}) by `decisions`; `decision_get` ({projectId, ref}) by
        // `decision_item` or refused `decision_not_found`.
        const val DECISION_ADD = "decision_add"
        const val DECISION_LIST = "decision_list"
        const val DECISION_GET = "decision_get"
        const val DECISION_ITEM = "decision_item"
        const val DECISIONS = "decisions"
        // PIPE-1 (contract 1.99.0 pushes `pipeline_state` to every client on each change, PIPE-2): a project's optional pipeline. `pipeline_config` ({projectId, stages?, exclusiveCheck?,
        // clear?}), `pipeline_show` ({projectId}), `pipeline_record` ({projectId, commit, stage, evidence?, sessionId?}) and
        // `pipeline_check` ({projectId, action: start|ping|finish, scope?, commit?, what?, sessionId?}) are each answered by
        // one `pipeline_state`, or refused with a code first (`no_pipeline`, `unknown_stage`, `check_running`, `no_check`).
        const val PIPELINE_CONFIG = "pipeline_config"
        const val PIPELINE_SHOW = "pipeline_show"
        const val PIPELINE_RECORD = "pipeline_record"
        const val PIPELINE_CHECK = "pipeline_check"
        const val PIPELINE_STATE = "pipeline_state"
        // VOICE-1 (contract 1.104.0): `notify` ({say, level?, projectId?, ticketId?}) raises a SPOKEN attention alert; the daemon
        // pushes `spoken` (one SpokenNotice) to every client, the requester included, and a refusal answers `error`.
        const val NOTIFY = "notify"
        const val SPOKEN = "spoken"
        // SECRETS-1 (contract 1.100.0): the daemon-owned keystore. `secret_set` ({name, value}), `secret_clear` ({name}), `secret_import` ({name}:
        // copy the older environment variable into the keystore) and `secret_status` ({}) are each answered by one `secret_state`
        // ({protection, secrets:[{name, label, set, tail, importable, overriddenByEnv}]}), pushed to every client after a change.
        // No frame ever carries a stored value back; a refusal never echoes the frame's data.
        const val SECRET_SET = "secret_set"
        const val SECRET_CLEAR = "secret_clear"
        const val SECRET_IMPORT = "secret_import"
        const val SECRET_STATUS = "secret_status"
        const val SECRET_STATE = "secret_state"
        // P10-K6 (contract 1.54.0): the project's append-only issues log. `issue_open`
        // ({projectId, sessionId?, summary, location, ticketId?}), `issue_attempt` ({projectId, sessionId?, summary,
        // outcome?, location?, issue?}) and `issue_fix` ({projectId, sessionId?, summary, issue?}) are answered by an
        // `issue_item` PUSHED to every client; `issue_list` ({projectId, includeFixed, location?}) and `issue_precheck`
        // ({projectId, path}) by `issues`; `issue_get` ({projectId, ref}) by `issue_item` or refused `issue_not_found`.
        const val ISSUE_OPEN = "issue_open"
        const val ISSUE_ATTEMPT = "issue_attempt"
        const val ISSUE_FIX = "issue_fix"
        const val ISSUE_LIST = "issue_list"
        const val ISSUE_GET = "issue_get"
        const val ISSUE_PRECHECK = "issue_precheck"
        const val ISSUE_ITEM = "issue_item"
        const val ISSUES = "issues"
    }
}
