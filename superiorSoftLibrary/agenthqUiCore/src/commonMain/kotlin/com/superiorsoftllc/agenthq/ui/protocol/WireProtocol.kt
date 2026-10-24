package com.superiorsoftllc.agenthq.ui.protocol

import com.superiorsoftllc.agenthq.ui.model.NoteScope
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Client-owned common DTOs for the published AgentHQ WebSocket contract. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Envelope(
    val type: String,
    val data: String = "",
    val sessionId: String? = null,
    /** Contract 1.7.0: the per-session stream sequence; null on control frames and from older daemons. */
    val sequence: Long? = null,
    /**
     * Contract 1.87.0: a client-chosen id the daemon echoes on its direct answer (or `error`) to that request. Omitted
     * when null, so a frame without it is what an older client sent.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val requestId: String? = null,
    /** Contract 1.125.0: who a `turn` frame came from; absent means the owner. Omitted when null. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val origin: com.superiorsoftllc.agenthq.ui.model.MessageOrigin? = null,
) {
    companion object {
        const val READY = "ready"
        const val PROMPT = "prompt"
        const val STDOUT = "stdout"
        const val STDERR = "stderr"
        const val EXIT = "exit"
        const val ERROR = "error"
        const val SESSION = "session"
        const val SESSIONS = "sessions"
        const val KILL = "kill"
        /** AGENT-UX-11: remove a session's durable record. See `agentHq`'s `Protocol.SESSION_DELETE`. */
        const val SESSION_DELETE = "session_delete"
        /** BUG-M2-15b (contract 1.61.0): bind an unbound session to a project. See `agentHq`'s `Protocol.SESSION_PROJECT`. */
        const val SESSION_PROJECT = "session_project"
        const val RESUME = "resume"
        /** Stop the session's current turn; the daemon answers by settling the session IDLE (contract 1.31.0 daemon). */
        const val INTERRUPT = "interrupt"
        const val NEW = "new"
        /** Contract 1.34.0: ask for every provider's models; answered with a `models` frame ([ProviderModels] list). */
        const val MODELS = "models"
        /** CP-4 (contract 1.38.0): ask for the `/` commands of a harness, filtered by what was typed; answered with a `command_catalog` frame. */
        const val COMMAND_CATALOG = "command_catalog"

        /** CP-4 slice d (contract 1.39.0): a `/` command ran against a session; the data is a [CommandResultAnswer]. */
        const val COMMAND_RESULT = "command_result"
        const val WORKTREE = "worktree"
        const val WORKTREES = "worktrees"
        const val WORKTREE_REMOVE = "worktree_remove"
        const val REPO = "repo"
        const val MAIL_SEND = "mail_send"
        const val MAIL_READ = "mail_read"
        const val MAIL_INBOX = "mail_inbox"
        const val MAIL_OUTBOX = "mail_outbox"
        const val MAIL_THREAD = "mail_thread"
        const val MAIL = "mail"
        const val MAILS = "mails"
        const val MAIL_THREADS = "mail_threads"
        const val MAIL_WAITING = "mail_waiting"
        const val MAIL_REFUSED = "mail_refused"
        const val TEXT_DELTA = "text_delta"
        const val STATUS = "status"
        const val FINISHED = "finished"
        const val POD_SUBMIT = "pod_submit"
        const val POD_QUERY = "pod_query"
        const val POD_CANCEL = "pod_cancel"
        const val POD = "pod"

        /** Contract 1.11.0: the prompt a run was started with, sequenced ahead of its reply. */
        const val TURN = "turn"

        /** Contract 1.23.0: the model's own reasoning, kept apart from [TEXT_DELTA] so it is never the answer. */
        const val REASONING = "reasoning"

        /** Contract 1.7.0: ask for a session's retained frames after an exclusive cursor ([ReplayRequest]). */
        const val REPLAY_REQUEST = "replay_request"

        /** Contract 1.7.0: the cursor predates the retained buffer; [ReplayGap] says what was evicted. */
        const val REPLAY_GAP = "replay_gap"

        // Contract 1.3.0 / 1.4.0: providers and agent profiles (the Role detail screen's data).
        const val PROVIDERS = "providers"
        const val AGENT_SAVE = "agent_save"
        const val AGENT_LIST = "agent_list"
        const val AGENT_DELETE = "agent_delete"
        const val AGENT = "agent"
        const val AGENTS = "agents"
        // Contract 1.47.0 (role-first S3): one agent onto another executor; `agent_swapped` is pushed to every client.
        const val AGENT_SWAP = "agent_swap"
        const val AGENT_SWAPPED = "agent_swapped"
        // UX-HARNESS-2 (contract 1.83.0): one session with no agent onto another harness, in place; `session_swapped`
        // is pushed to every client.
        /** Contract 1.115.0: ask for the installation-wide Assistant; the daemon answers with its `session` frame. */
        const val ASSISTANT = "assistant"
        const val SESSION_SWAP = "session_swap"
        const val SESSION_SWAPPED = "session_swapped"

        // Contract 1.12.0: the role catalog (the Role picker's data).
        const val ROLE_LIST = "role_list"
        const val ROLE_SAVE = "role_save"
        const val ROLE = "role"
        const val ROLES = "roles"
        /** Contract 1.108.0 (ROLE-EDIT-1): `role_delete` (data = role id); a removed custom role is pushed as `role_removed`, a reset one as `role`. */
        const val ROLE_DELETE = "role_delete"
        const val ROLE_REMOVED = "role_removed"

        // Contract 1.40.0 (ROLE-1c): what a role runs on.
        const val ROLE_BINDING_LIST = "role_binding_list"
        const val ROLE_BINDING_SET = "role_binding_set"
        const val ROLE_BINDING = "role_binding"
        const val ROLE_BINDINGS = "role_bindings"

        // Contract 1.9.0: durable attention history, pushes and acknowledgement.
        const val ATTENTION = "attention"
        const val ATTENTION_LIST = "attention_list"
        const val ATTENTION_ITEMS = "attention_items"
        const val ATTENTION_ACK = "attention_ack"

        // Contract 1.13.0: observed usage per run (sequenced on the session stream) and limit state per provider.
        const val USAGE = "usage"
        const val PROVIDER_STATE = "provider_state"
        const val PROVIDER_STATES = "provider_states"

        // Contract 1.59.0 (U1): plan-limit meters. `limits` (empty data) asks; the daemon answers `limits` (a list of
        // `ProviderLimits`) and pushes the same frame after each run's `usage`.
        const val LIMITS = "limits"
        // Contract 1.119.0 (USAGE-PACE): `usage_get` (empty) is answered by `usage_report` (a `UsageReport`); 1.121.0 adds
        // `usage_set` (a `UsageSet`: manual percent / override / pacing rule), also answered by `usage_report`.
        const val USAGE_GET = "usage_get"
        const val USAGE_REPORT = "usage_report"
        const val USAGE_SET = "usage_set"

        // Contract 1.120.0 (ROGUE-1): `processes` {op: list|reap|reap_rogue, pid?} is answered by `processes`
        // (a ProcessesAnswer); `process_pressure` (a ProcessReport) is pushed when free RAM drops under the floor.
        const val PROCESSES = "processes"
        const val PROCESS_PRESSURE = "process_pressure"

        // Contract 1.92.0 (STATS-1): `session_stats_get` {"sessionId"} asks for one session's statistics, read by the
        // daemon from its harness's own local record; answered to the requester only by `session_stats` (a SessionStats).
        const val SESSION_STATS_GET = "session_stats_get"
        const val SESSION_STATS = "session_stats"

        // Daemon settings. BUG-M2-11e reads and writes `session.keepAliveMinutes` (contract 1.68.0) through them.
        // `settings_get` (data = a key, or empty for all) and `settings_set` are answered by `settings` (a JSON object).
        const val SETTINGS_GET = "settings_get"
        const val SETTINGS_SET = "settings_set"
        const val SETTINGS = "settings"

        // Contract 1.73.0 (CLIPATH-1): where the daemon runs each CLI from. `cli_locations` (no data) and
        // `cli_location_set` ({id, path}; null path clears) are both answered by `cli_locations` ([CliLocations]).
        const val CLI_LOCATIONS = "cli_locations"
        const val CLI_LOCATION_SET = "cli_location_set"

        // Contract 1.102.0 (HARNESS-INSTALL-1): the daemon detects / npm-installs the harness CLIs on ITS machine.
        const val HARNESS_INSTALL_STATUS = "harness_install_status"
        const val HARNESS_INSTALL = "harness_install"
        const val HARNESS_INSTALL_RESULT = "harness_install_result"

        // Contract 1.76.0 (BUG-PERM-1): the global, harness-neutral Tool approval. `tool_approval` (no data) and
        // `tool_approval_set` ({mode}; null clears) are both answered by `tool_approval` ([ToolApprovalInfo]).
        const val TOOL_APPROVAL = "tool_approval"
        const val TOOL_APPROVAL_SET = "tool_approval_set"
        // Contract 1.102.0 (SECRETS-1): the daemon-owned keystore. `secret_set` ({name, value}), `secret_clear` ({name}),
        // `secret_import` ({name}) and `secret_status` are answered by `secret_state` ([SecretState]); no value ever comes back.
        const val SECRET_SET = "secret_set"
        const val SECRET_CLEAR = "secret_clear"
        const val SECRET_IMPORT = "secret_import"
        const val SECRET_STATUS = "secret_status"
        const val SECRET_STATE = "secret_state"

        // Contract 1.77.0 (PROMPT-1): a harness asks the owner mid-run. `prompt_request` (daemon -> every client,
        // [PromptRequestView], envelope sessionId = the asking session) opens a card; `prompt_response` (client ->
        // daemon, [PromptResponseWire]) answers it; `prompt_resolved` ([PromptResolvedView]) closes it on every client.
        const val PROMPT_REQUEST = "prompt_request"
        const val PROMPT_RESPONSE = "prompt_response"
        const val PROMPT_RESOLVED = "prompt_resolved"

        // Contract 1.97.0 (ASK-1): owner questions, NON-BLOCKING and stored. `question` ([OwnerQuestionView]) pushes a
        // new or changed one; `questions` (a list) is sent on connect and answers `question_list`; `question_answer`
        // ([QuestionAnswerWire]) picks or overrides; `question_cancel` withdraws. The daemon mails the pick to the agent.
        const val QUESTION = "question"
        const val QUESTIONS = "questions"
        const val QUESTION_LIST = "question_list"
        const val QUESTION_ANSWER = "question_answer"
        const val QUESTION_CANCEL = "question_cancel"

        // Contracts 1.5.0 / 1.6.0: the daemon-owned backlog (the scrum board). Changes are pushed to every client.
        const val TICKET_SAVE = "ticket_save"
        const val TICKET_LIST = "ticket_list"
        const val TICKET_MOVE = "ticket_move"
        const val TICKET_DELETE = "ticket_delete"
        const val TICKET_START_NEXT = "ticket_start_next"
        // Contract 1.83.0 -- IMG-1: `image_upload` ([ImageUploadWire], client -> daemon) stores an image in the project;
        // `image` ([ImageStored]) answers the uploading connection only; a refusal is `error` "image_upload refused: ...".
        const val IMAGE_UPLOAD = "image_upload"
        const val IMAGE = "image"
        const val TICKET = "ticket"
        const val TICKETS = "tickets"

        // Contract 1.14.0: manual triggers.
        const val TRIGGER_SAVE = "trigger_save"
        const val TRIGGER_LIST = "trigger_list"
        const val TRIGGER_FIRE = "trigger_fire"
        const val TRIGGER_ARM = "trigger_arm"
        const val TRIGGER_DISABLE = "trigger_disable"
        const val TRIGGER_DELETE = "trigger_delete"
        const val TRIGGER_EVENTS = "trigger_events"
        const val TRIGGER = "trigger"
        const val TRIGGERS = "triggers"

        // Contract 1.18.0 (PW-5): projects and the workspaces inside them.
        const val PROJECT_LIST = "project_list"
        const val PROJECT_SAVE = "project_save"
        const val PROJECT = "project"
        const val PROJECTS = "projects"
        // Contract 1.81.0 (BASE-1a): read, set or clear a project's base branch; answered by `project`.
        const val PROJECT_BASE = "project_base"
        const val PROJECT_CONFIG = "project_config"
        const val WORKSPACE_LIST = "workspace_list"
        const val WORKSPACE_SAVE = "workspace_save"
        const val WORKSPACE = "workspace"
        const val WORKSPACES = "workspaces"

        // Contract 1.36.0 (CP-2): a project's git tree. `project_git_status` (raw project id) is answered by
        // `project_git`; `project_git_diff` by `project_git_patch`; `project_git_log` by `project_git_history`.
        const val PROJECT_GIT_STATUS = "project_git_status"
        const val PROJECT_GIT_DIFF = "project_git_diff"
        const val PROJECT_GIT_LOG = "project_git_log"
        const val PROJECT_GIT = "project_git"
        const val PROJECT_GIT_PATCH = "project_git_patch"
        const val PROJECT_GIT_HISTORY = "project_git_history"

        // Contract 1.99.0 (PIPE-2): `pipeline_show` / `pipeline_config` are answered by `pipeline_state` (a project's optional
        // pipeline); the daemon also PUSHES `pipeline_state` to every client whenever a project's pipeline changes.
        const val PIPELINE_SHOW = "pipeline_show"
        const val PIPELINE_CONFIG = "pipeline_config"
        const val PIPELINE_STATE = "pipeline_state"

        // Contract 1.104.0 (VOICE-1): the daemon PUSHES `spoken` (one SpokenNotice) to every client when an agent sends `notify`.
        const val SPOKEN = "spoken"

        // Contract 1.65.0 (BRANCH-VIEW-1): local branches and one branch's changes since its merge-base with a base.
        // `project_git_branches` -> `project_git_branch_list`, `project_git_branch_changes` ->
        // `project_git_branch_diffstat`, `project_git_branch_diff` -> `project_git_patch` (carrying `branch`).
        const val PROJECT_GIT_BRANCHES = "project_git_branches"
        const val PROJECT_GIT_BRANCH_CHANGES = "project_git_branch_changes"
        const val PROJECT_GIT_BRANCH_DIFF = "project_git_branch_diff"
        const val PROJECT_GIT_BRANCH_LIST = "project_git_branch_list"
        const val PROJECT_GIT_BRANCH_DIFFSTAT = "project_git_branch_diffstat"

        // Contract 1.74.0 (BRANCH-SWITCH-1): switch (or create and switch) the project's checked-out branch.
        // `project_git_checkout` -> `project_git_checkout_result`; a switch that happened also pushes `project_git`.
        const val PROJECT_GIT_CHECKOUT = "project_git_checkout"
        const val PROJECT_GIT_CHECKOUT_RESULT = "project_git_checkout_result"

        // Contract 1.88.0 (UX-CHANGES-1): the repository's worktrees and one worktree's state, read-only.
        // `project_git_worktrees` -> `project_git_worktree_list`, `project_git_worktree_status` -> `project_git_worktree`.
        const val PROJECT_GIT_WORKTREES = "project_git_worktrees"
        const val PROJECT_GIT_WORKTREE_STATUS = "project_git_worktree_status"
        const val PROJECT_GIT_WORKTREE_LIST = "project_git_worktree_list"
        const val PROJECT_GIT_WORKTREE = "project_git_worktree"

        // Contract 1.19.0 (EPIC-4): a project's epics (the Epic screen's data).
        const val EPIC_SAVE = "epic_save"
        const val EPIC_LIST = "epic_list"
        const val EPIC = "epic"
        const val EPICS = "epics"

        // Contract 1.33.0 (in-app build): saved commands and one run's lifecycle.
        const val COMMAND_LIST = "command_list"
        const val COMMANDS = "commands"
        // Contract 1.94.0 (RUN-CMD-1): edit the saved commands; answered by `commands`, refused by `error`.
        const val COMMAND_SAVE = "command_save"
        const val COMMAND_DELETE = "command_delete"
        const val RUN_START = "run_start"
        const val RUN = "run"
        const val RUN_OUTPUT = "run_output"
        const val RUN_FINISHED = "run_finished"
        const val RUN_STOP = "run_stop"

        // Contract 1.8.0 (MVP story board): a project's stories (the Story board's data).
        // `story_save` / `story_get` / `story_move` are answered by `story`; `story_list`
        // (empty data to list every project's board, or a raw project id) by `stories`.
        const val STORY_SAVE = "story_save"
        const val STORY_GET = "story_get"
        const val STORY_LIST = "story_list"
        const val STORY_MOVE = "story_move"
        const val STORY = "story"
        const val STORIES = "stories"

        // Contract 1.20.0 (MEM-5): project-memory search -- `memory_search` is answered by `memory_hits`.
        const val MEMORY_SEARCH = "memory_search"
        const val MEMORY_HITS = "memory_hits"

        // Contract 1.21.0 (MEM-8): write and list a project's memory notes -- `memory_save` is answered
        // by `memory_note` (the stored note); `memory_list` (raw project id, like `epic_list`) by
        // `memory_notes` (the project's index of {name, description}, body withheld).
        const val MEMORY_SAVE = "memory_save"
        const val MEMORY_LIST = "memory_list"
        const val MEMORY_NOTE = "memory_note"
        const val MEMORY_NOTES = "memory_notes"
        const val MEMORY_DELETE = "memory_delete"
        // Contract 1.51.0 (P10-K2): `memory_get {projectId, name}` reads one whole note, answered by `memory_note`.
        const val MEMORY_GET = "memory_get"

        // Contract 1.44.0 (P10-A): the prompt library. `prompt_save` -> `prompt_item` (pushed to all); `prompt_list` (data = optional
        // tag) -> `prompts`; `prompt_get` (data = id) -> `prompt_item`; `prompt_delete` (data = id) -> `prompts` (pushed to all).
        const val PROMPT_SAVE = "prompt_save"
        const val PROMPT_LIST = "prompt_list"
        const val PROMPT_GET = "prompt_get"
        const val PROMPT_DELETE = "prompt_delete"
        const val PROMPT_ITEM = "prompt_item"
        const val PROMPTS = "prompts"

        // Contract 1.45.0 (P10-B): the skills library, same shape as the prompt library.
        const val SKILL_SAVE = "skill_save"
        const val SKILL_LIST = "skill_list"
        const val SKILL_GET = "skill_get"
        const val SKILL_DELETE = "skill_delete"
        const val SKILL_ITEM = "skill_item"
        const val SKILLS = "skills"

        // Contract 1.48.0 (TRAITS-T1): the trait library. `trait_save` (TraitSave, save-as-update by id) -> `trait_item`,
        // pushed to all; `trait_list` -> `traits`; `trait_get` (data = id) -> `trait_item`; `trait_delete` (data = id) -> `traits`.
        const val TRAIT_SAVE = "trait_save"
        const val TRAIT_LIST = "trait_list"
        const val TRAIT_GET = "trait_get"
        const val TRAIT_DELETE = "trait_delete"
        const val TRAIT_ITEM = "trait_item"
        const val TRAITS = "traits"

        // Contract 1.49.0 (TRAITS-T2): a role's ordered trait list. `role_traits_set` (RoleTraitsSave; `[]` clears) ->
        // `role_traits`, pushed to all; `role_traits_get` (data = role id) -> `role_traits`; `role_traits_list` -> `role_traits_all`.
        const val ROLE_TRAITS_SET = "role_traits_set"
        const val ROLE_TRAITS_GET = "role_traits_get"
        const val ROLE_TRAITS_LIST = "role_traits_list"
        const val ROLE_TRAITS = "role_traits"
        const val ROLE_TRAITS_ALL = "role_traits_all"

        // AGENT-ROSTER (ROSTER-2..5): the live, project-scoped "who's here" list.
        // `agent_roster` carries {"projectId": ...} and is answered by `roster`, the
        // JSON list of that project's roster rows -- the frame names no project of
        // its own.
        const val AGENT_ROSTER = "agent_roster"
        const val ROSTER = "roster"
    }
}

/** The four backlog columns, in board order. `pending` is derived by the daemon and never set by a client. */
object TicketColumns {
    const val TODO = "todo"
    const val IN_PROGRESS = "in_progress"
    const val PENDING = "pending"
    const val DONE = "done"
    val ALL: List<String> = listOf(TODO, IN_PROGRESS, PENDING, DONE)
}

/** Contracts 1.5.0 / 1.6.0: one resolved executor value and where it came from (`ticket`, `profile`, `role`, `default`). */
@Serializable
data class ExecutorField(val value: String? = null, val source: String = "default")

/** Contract 1.6.0: the executor a ticket will actually run on. */
@Serializable
data class EffectiveExecutor(
    val agentId: String? = null,
    val role: String? = null,
    val provider: ExecutorField? = null,
    val model: ExecutorField? = null,
)

/** Contract 1.6.0: the outcome of a ticket's latest run; [outcome] is a finished-outcome name, null while in flight. */
@Serializable
data class TicketRun(
    val sessionId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val outcome: String? = null,
    val detail: String? = null,
)

/** Contract 1.6.0: the answer to a start. [reason] is `started`, `queued`, `held` or `start_refused`. */
@Serializable
data class TicketStart(val ok: Boolean, val reason: String, val detail: String? = null)

@Serializable
data class TicketLink(val type: String, val ticketId: String)

/** A backlog ticket as the board needs it (the daemon sends more; unknown keys are ignored). */
@Serializable
data class Ticket(
    val id: String,
    val title: String,
    val description: String = "",
    val ticketType: String? = null,
    /** The project the ticket belongs to (the daemon stores it; null = legacy, no project). */
    val projectId: String? = null,
    val status: String,
    val position: Int = 0,
    val wontFix: Boolean = false,
    val links: List<TicketLink> = emptyList(),
    val heldBy: List<String> = emptyList(),
    val holdReason: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val agentId: String? = null,
    /** Contract 1.6.0: the ticket's own executor pins; null inherits (profile, role, daemon default). */
    val provider: String? = null,
    val model: String? = null,
    val queuedFor: String? = null,
    val sessionId: String? = null,
    val effectiveExecutor: EffectiveExecutor? = null,
    val lastRun: TicketRun? = null,
    val start: TicketStart? = null,
    /** BRANCH-PER-TASK 1/3: the ticket's own branch (`ticket/<short-id>-<slug>`), once a start cut it; null = never started. */
    val branch: String? = null,
    /** BRANCH-PER-TASK 1/3: the commit that branch was cut from, once a start cut it. */
    val base: String? = null,    /** Contract 1.30.0 / M2-14: the epic the ticket is filed under; null = none. */
    val epicId: String? = null,
    /**
     * IMG-3 (contract 1.84.0): the ticket's images, in attach order, each a relPath under its project directory
     * (`.agenthq/uploads/ticket/<sha256>.<ext>`). Empty from an older daemon.
     */
    val images: List<String> = emptyList(),
)

/**
 * Contract 1.96.0 (ticket-b5aa01bc): the `error` refusing a `prompt` whose session already has a run in flight reads
 * `run_in_flight <sessionId>: <reason>` and carries the prompt's `requestId`. See `agentHq`'s `RUN_IN_FLIGHT`.
 */
object RunInFlight {
    const val PREFIX = "run_in_flight"
    const val SINCE_MAJOR = 1
    const val SINCE_MINOR = 96
}

/** IMG-1 (contract 1.83.0): `image_upload`'s body. [base64] is the file's bytes; [mimeType] may be blank (sniffed). */
@Serializable
data class ImageUploadWire(
    val projectId: String,
    val purpose: String,
    val fileName: String = "",
    val mimeType: String = "",
    val base64: String,
) {
    companion object {
        const val PURPOSE_TICKET = "ticket"
        /** IMG-2: an image attached in the agent chat composer. */
        const val PURPOSE_CHAT = "chat"
        /** ATTACH-INLINE-1 (contract 1.89.0): a chat attachment of any type, at most [MAX_FILE_BYTES]. */
        const val PURPOSE_FILE = "file"
        /** The daemon's own limit (10 MB decoded); checked here too so a too-large file is never sent. */
        const val MAX_BYTES: Int = 10 * 1024 * 1024
        /** The daemon's limit for purpose `file` (20 MB decoded). */
        const val MAX_FILE_BYTES: Int = 20 * 1024 * 1024

        fun maxBytes(purpose: String): Int = if (purpose == PURPOSE_FILE) MAX_FILE_BYTES else MAX_BYTES
    }
}

/** IMG-1: the daemon's `image` answer: where the upload is stored (absolute on the daemon's machine, and relative). */
@Serializable
data class ImageStored(
    val path: String,
    val relPath: String,
    val sha256: String = "",
    val bytes: Long = 0,
    val mimeType: String = "",
)

/**
 * Contract 1.14.0: what a trigger does when it fires. Served: `start_next`, optionally only for [agentId], and
 * (1.41.0) `prompt`: a ticketless session for [agentId] whose first message is [prompt].
 */
@Serializable
data class TriggerAction(val kind: String = "start_next", val agentId: String? = null, val prompt: String? = null)

/** Contract 1.14.0: a trigger as the daemon holds it. [state] is `armed`, `running`, `tripped` or `disabled`. */
@Serializable
data class TriggerView(
    val id: String,
    val name: String,
    val kind: String = "manual",
    val action: TriggerAction = TriggerAction(),
    val enabled: Boolean = true,
    val state: String,
    val coalesceMs: Long = 0,
    val ceilingRuns: Int = 6,
    val ceilingWindowMs: Long = 3_600_000,
    val createdAt: Long = 0,
    val trippedAt: Long? = null,
    val trippedReason: String? = null,
    /** Contract 1.17.0: for kind `schedule`, the period between due events in ms; null = not timed. */
    val intervalMs: Long? = null,
    /** Contract 1.105.0: the project a `prompt` action mails into; null = none. */
    val projectId: String? = null,
    /** Contract 1.105.0: when the trigger last accepted an event; null = never. */
    val lastFiredAt: Long? = null,
    /** Contract 1.105.0: for kind `schedule`, when the next event is due; null = not timed. */
    val nextFireAt: Long? = null,
)

/** Contract 1.14.0: one event row; [disposition] is lower snake case (`started`, `folded`, `nothing_to_start`, ...). */
@Serializable
data class TriggerEventView(
    val eventId: String,
    val triggerId: String,
    val receivedAt: Long,
    val source: String,
    val disposition: String,
    val deliveryId: String? = null,
    val digest: String? = null,
)

/** Contract 1.13.0: what one run reported using. Only sent when the provider reported usage at all. */
@Serializable
data class RunUsage(
    val runId: String,
    val provider: String,
    val model: String,
    val input: Long,
    val output: Long,
    val reasoning: Long,
    val cacheRead: Long,
    val cacheWrite: Long,
    val costUsd: Double? = null,
)

/**
 * Contract 1.13.0: a provider's last observed limit state. [status] is the wire string (`unknown`,
 * `stalled`, `exhausted`); [utilization] is null when there is no signal, which is NOT 0%.
 */
@Serializable
data class ProviderState(
    val provider: String,
    val status: String = "unknown",
    val window: String? = null,
    val utilization: Double? = null,
    val resetsAt: Long? = null,
    val observedAt: Long? = null,
    val source: String = "none",
)

/**
 * Contract 1.59.0 (U1): one plan-limit window of one provider. [percent] null = unknown, NOT 0%. [resetsAt] and
 * [observedAt] are epoch millis. [paceDelta] = percent minus the elapsed share of the window: + ahead, - under.
 */
@Serializable
data class LimitWindow(
    val provider: String,
    val label: String,
    val windowMinutes: Int = 0,
    val percent: Double? = null,
    val resetsAt: Long? = null,
    val observedAt: Long = 0,
    val source: String = "live",
    val paceDelta: Double? = null,
)

/**
 * Contract 1.92.0 (STATS-1): one session's statistics as the daemon read them from the harness's own record. Every
 * field a harness does not expose is null, which means "not said", never zero: the Stats tab shows "-" for it.
 */
@Serializable
data class SessionStats(
    val sessionId: String,
    val harness: String,
    val providerSessionId: String? = null,
    val status: String? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val durationMs: Long? = null,
    val userPrompts: Int? = null,
    val assistantTurns: Int? = null,
    val toolUses: Int? = null,
    val models: List<String>? = null,
    val lastContextTokens: Long? = null,
    val modelContextLimit: Long? = null,
    val billedTokens: Long? = null,
    val series: List<StatsPoint>? = null,
    val topSources: List<ContextSource>? = null,
    val unattributedToolTokens: Long? = null,
    /** Contract 1.113.0: running total of the cost the harness reported; null = the harness reports none. */
    val costTotal: Double? = null,
)

/** STATS-1: one model request. [at] is null when the record gives no time. */
@Serializable
data class StatsPoint(val at: Long? = null, val contextTokens: Long, val cumulativeBilledTokens: Long, val cumulativeCost: Double? = null)

/** STATS-1: one tool's share of the context; [tokensAdded] null when no single-tool step of it exists. */
@Serializable
data class ContextSource(val tool: String, val calls: Int, val tokensAdded: Long? = null)

/** USAGE-PACE (1.119.0): below [below] percent used, [workers] ticket workers may run; [below] null = every percent left. */
@Serializable
data class PacingTier(val below: Double? = null, val workers: Int)

/** USAGE-PACE: the pacing rule; the metric is the highest percent among a meter's windows. */
@Serializable
data class PacingRule(val enabled: Boolean = true, val tiers: List<PacingTier> = emptyList())

/** USAGE-PACE: a hand-set allowance: [workers] null = no limit, until [until] (epoch millis). */
@Serializable
data class UsageOverride(val workers: Int? = null, val until: Long = 0)

/** USAGE-PACE: one plan meter (`claude`, `opencode-go`, else a harness id) of a [UsageReport]. */
@Serializable
data class MeterUsage(
    val meter: String,
    val percent: Double? = null,
    val window: String? = null,
    val resetsAt: Long? = null,
    val paceDelta: Double? = null,
    /** `live`, `manual` or `none`. */
    val source: String = "none",
    val windows: List<LimitWindow> = emptyList(),
    val reason: String? = null,
    val ruleAllowance: Int? = null,
    val override: UsageOverride? = null,
    /** null = no limit. */
    val allowance: Int? = null,
    val running: Int = 0,
    val canStart: Boolean = true,
    val warning: String? = null,
    /** 1.124.0: epoch ms of the live read behind these numbers; null for a manual / unread meter. */
    val updatedAt: Long? = null,
)

/** USAGE-PACE (1.119.0): the `usage_report` frame. */
@Serializable
data class UsageReport(val meters: List<MeterUsage> = emptyList(), val rule: PacingRule = PacingRule(), val observedAt: Long = 0)

/** USAGE-PACE (1.121.0): the `usage_set` frame; only the keys a request names are sent (no defaults encoded). */
@Serializable
data class UsageSet(
    val meter: String = "",
    val manualPercent: Double? = null,
    val manualResetsAt: Long? = null,
    val clearManual: Boolean = false,
    val overrideWorkers: Int? = null,
    val overrideUntil: Long? = null,
    val clearOverride: Boolean = false,
    val rule: PacingRule? = null,
)

/** Contract 1.120.0 (ROGUE-1): one process the daemon's rogue detector reports; [sessionId] null = not ours (never reaped). */
@Serializable
data class TrackedProcess(
    val pid: Long,
    val command: String,
    val sessionId: String? = null,
    val startedAt: Long? = null,
    val memoryMb: Long? = null,
    val rogue: Boolean = false,
    val reason: String? = null,
)

/** Contract 1.120.0 (ROGUE-1): free RAM against the floor, the tracked processes and the heavy unattributed ones. */
@Serializable
data class ProcessReport(
    val freeMb: Long? = null,
    val totalMb: Long? = null,
    val floorMb: Long = 4096,
    val underPressure: Boolean = false,
    val autoReap: Boolean = false,
    val tracked: List<TrackedProcess> = emptyList(),
    val unattributed: List<TrackedProcess> = emptyList(),
) {
    val rogue: List<TrackedProcess> get() = tracked.filter { it.rogue }
}

/** Contract 1.120.0 (ROGUE-1): the `processes` answer. */
@Serializable
data class ProcessesAnswer(
    val report: ProcessReport,
    val reaped: List<TrackedProcess> = emptyList(),
    val refused: String? = null,
)

/** Contract 1.59.0 (U1): one provider's plan limits. [status] `ok` or `unknown`; [reason] is a fixed code when unknown. */
@Serializable
data class ProviderLimits(
    val provider: String,
    val status: String = "unknown",
    val windows: List<LimitWindow> = emptyList(),
    val reason: String? = null,
    val observedAt: Long = 0,
)

/**
 * Contract 1.12.0: one role of the daemon's catalog. [source] is kept as the wire string (`BUILT_IN`,
 * `CUSTOM`, `GENERATED`) so a source added later still decodes.
 */
@Serializable
data class Role(
    val id: String,
    val name: String,
    val color: String,
    val persona: String = "",
    val source: String = "BUILT_IN",
    /** Contract 1.93.0 (ROLE-ICON-1): the role's icon key; "" (and every older daemon) = none, paint the role's default. */
    val icon: String = "",
    /** Contract 1.108.0 (ROLE-EDIT-1): a one-line description for the role editor; "" (and every older daemon) = none. */
    val description: String = "",
)

/**
 * Contract 1.12.0: the `role_save` payload. The daemon decodes it strictly, so it carries no other key; [icon]
 * (1.93.0, null = keep, "" = clear) is sent only by [WireRequests.roleSave], and only to a daemon that knows it.
 */
@Serializable
data class RoleSave(
    val id: String,
    val name: String,
    val color: String? = null,
    val persona: String = "",
    val icon: String? = null,
    /** Contract 1.108.0: null = keep, "" = clear; sent only to a daemon that knows it. */
    val description: String? = null,
)

/** Contract 1.40.0: what a role runs on; a null field is "no pin" (the daemon default applies at spawn time). */
@Serializable
data class RoleBinding(
    val roleId: String,
    val provider: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val updatedAt: Long = 0,
)

/** Contract 1.40.0: the `role_binding_set` payload. The daemon decodes it strictly, so it carries no other key. */
@Serializable
data class RoleBindingSave(val roleId: String, val provider: String? = null, val model: String? = null, val effort: String? = null)

/** Contract 1.4.0: an agent profile -- a named binding of a role to a provider/model and instructions. */
@Serializable
data class AgentProfile(
    val id: String? = null,
    val name: String,
    val role: String,
    val provider: String? = null,
    val model: String? = null,
    val instructions: String? = null,
    /** CP-3: the one project this agent belongs to; the daemon refuses a new agent without one. */
    val projectId: String? = null,
    /** Contract 1.46.0 (role-first S2): the profile's effort level; null = inherit. Decoded only (UI is S8). */
    val effort: String? = null,
)

/** Contract 1.47.0: the executor an agent resolved to before a swap (a null model / effort = the harness default). */
@Serializable
data class SwapExecutor(val provider: String? = null, val model: String? = null, val effort: String? = null)

/** Contract 1.47.0: what a swap did to the agent's session: [outcome] `kept` | `new` | `none`; [sessionId] the kept or new one. */
@Serializable
data class SwapSession(val outcome: String, val sessionId: String? = null)

/** Contract 1.47.0: what `agent_swap` may do to the agent's session (`session` field). */
enum class SwapSessionMode(val wire: String) { AUTO("auto"), KEEP("keep"), NEW("new") }

/**
 * Contract 1.47.0 (role-first S3): the `agent_swapped` push -- the profile as stored, the executor it left, and the
 * session outcome. Decoded only (the Swap UI is S8); unknown keys are ignored so a later daemon stays readable.
 */
@Serializable
data class AgentSwapped(val agent: AgentProfile, val from: SwapExecutor, val session: SwapSession) {
    companion object {
        private val lenient = Json { ignoreUnknownKeys = true }

        fun decode(text: String): AgentSwapped = lenient.decodeFromString(serializer(), text)
    }
}

/**
 * UX-HARNESS-2 (contract 1.83.0): the `session_swapped` push -- the session (with no agent) now runs on [provider] /
 * [model] (null = the harness default, named by [defaultModel] when known); [newConversation] is always false since
 * HARNESS-CARRY-1 (the conversation carries over to the new harness). Unknown keys are ignored so a later daemon stays readable.
 */
@Serializable
data class SessionSwapped(
    val sessionId: String,
    val provider: String,
    val model: String? = null,
    val defaultModel: String? = null,
    val from: SwapExecutor = SwapExecutor(),
    val newConversation: Boolean = false,
    /** SWAP-AGENT-1 (contract 1.86.0): the role the session now runs as, and the one before (null = none, or an older daemon). */
    val role: String? = null,
    val fromRole: String? = null,
) {
    companion object {
        private val lenient = Json { ignoreUnknownKeys = true }

        fun decode(text: String): SessionSwapped = lenient.decodeFromString(serializer(), text)
    }
}

/**
 * Contract 1.34.0: one group of models a provider can run, as the `models` frame lists them. [group] is the
 * upstream vendor inside the provider (blank = none); [models] are the exact ids a `new` frame's `model`
 * takes. An empty [models] with a [note] is a provider with nothing to offer right now: listed, never hidden.
 */
@Serializable
data class ProviderModels(
    val provider: String,
    val group: String,
    val label: String,
    val models: List<String>,
    val note: String? = null,
)

/**
 * CP-4 (contract 1.38.0): one entry of a harness's `/` command catalog, as the `command_catalog` frame lists it. [action]
 * is kept as the wire string (`SET_MODEL`, `SET_EFFORT`, `NATIVE`) so an action added later still renders. [argument] is
 * the hint for the value to type (null = the command takes none); [values] are the accepted values, already narrowed by
 * the typed prefix when the request named the command.
 */
@Serializable
data class SlashCommand(
    val name: String,
    val description: String,
    val action: String,
    val argument: String? = null,
    val values: List<String> = emptyList(),
    val native: String? = null,
)

/** CP-4: the `command_catalog` answer -- whose catalog, the prefix it was filtered by, and the matches. */
@Serializable
data class CommandCatalogAnswer(
    val provider: String,
    val prefix: String,
    val commands: List<SlashCommand>,
)

/** CP-4 slice d: the `command_result` answer -- a `/` command took effect; [message] is a sentence to show. A refusal is an `error` frame instead. */
@Serializable
data class CommandResultAnswer(
    val ok: Boolean,
    val command: String,
    val message: String,
)

/** CP-4: the `command_catalog` request body. `prefix` is the text after the slash as typed so far. */
@Serializable
data class CommandCatalogRequest(val prefix: String = "", val provider: String? = null)

/**
 * Contract 1.3.0: a provider a spawn may name, the default first. AGENT-UX-10 adds [models] (the ids
 * the daemon read from the provider CLI, null = none reported) and [modelsStale] (that list is
 * last-known data after a failed refresh); both are ADDITIVE and default-absent for an older daemon.
 */
@Serializable
data class ProviderInfo(
    val id: String,
    val default: Boolean = false,
    val defaultModel: String? = null,
    val models: List<String>? = null,
    val modelsStale: Boolean = false,
    /** CLIPATH-1 (1.73.0): false when the daemon cannot find this provider's CLI; [unavailableReason] says why. */
    val available: Boolean = true,
    val unavailableReason: String? = null,
)

/**
 * Contract 1.73.0 (CLIPATH-1): one CLI the daemon launches. [path] null = not found ([reason], [tried]); [source] is
 * `override` | `env` | `default` | `path`; [override] is the path set in Settings, null when none.
 */
@Serializable
data class CliLocation(
    val id: String,
    val label: String,
    val path: String? = null,
    val source: String? = null,
    val override: String? = null,
    val tried: List<String> = emptyList(),
    val reason: String? = null,
)

/** The `cli_locations` frame's data. */
@Serializable
data class CliLocations(val tools: List<CliLocation> = emptyList())

/** Contract 1.102.0 (SECRETS-1): one keystore row. [tail] is a masked tail, never the value. */
@Serializable
data class SecretRow(
    val name: String,
    val label: String,
    val set: Boolean = false,
    val tail: String = "",
    val importable: Boolean = false,
    val overriddenByEnv: Boolean = false,
)

/** Contract 1.102.0 (SECRETS-1): the `secret_state` frame. */
@Serializable
data class SecretState(val protection: String = "", val secrets: List<SecretRow> = emptyList())

/**
 * Contract 1.76.0 (BUG-PERM-1): the `tool_approval` frame. [mode] is the stored global value (null = none); [effective]
 * what a run with no session override gets now; [source] `setting` | `env` | `default`.
 */
@Serializable
data class ToolApprovalInfo(
    val mode: String? = null,
    val effective: String = "deny",
    val source: String = "default",
    val choices: List<String> = emptyList(),
)

/**
 * Contract 1.9.0: a durable record that something needs a human. [kind] is kept as the wire string
 * (`stalled`, `deadline`, `trigger_tripped`, ...) so a kind added later still renders.
 */
@Serializable
data class AttentionItem(
    val id: String,
    val at: Long,
    val kind: String,
    val triggerId: String? = null,
    val ticketId: String? = null,
    val podId: String? = null,
    val sessionId: String? = null,
    val outcome: String,
    val detail: String,
    val ackedAt: Long? = null,
)

/** Payload of [Envelope.REPLAY_REQUEST]; the envelope's `sessionId` names the session. */
@Serializable
data class ReplayRequest(val lastSequence: Long)

/** Payload of [Envelope.REPLAY_GAP], sent before the retained frames when the cursor is too old. */
@Serializable
data class ReplayGap(
    val requestedAfterSequence: Long,
    val oldestAvailableSequence: Long,
    val latestAvailableSequence: Long,
    val evictedCount: Long,
)

@Serializable
enum class SessionState { STARTING, BUSY, WAITING_INPUT, IDLE, EXITED, ORPHANED, FAILED }

@Serializable
enum class KillOutcome { GRACEFUL, FORCED }

@Serializable
data class SessionRecord(
    val id: String,
    val provider: String,
    val workingDir: String,
    val spawnedAt: Long,
    val lastHeartbeat: Long,
    val state: SessionState,
    val providerSessionId: String? = null,
    val childPid: Long? = null,
    val runCount: Int = 0,
    val resumeCount: Int = 0,
    val lastExitCode: Int? = null,
    val killOutcome: KillOutcome? = null,
    val lastError: String? = null,
    val worktreePath: String? = null,
    val worktreeBranch: String? = null,
    val repoRoot: String? = null,
    val worktreeBaseRef: String? = null,
    /** Contract 1.3.0: the model the spawn named; null = the provider's default. */
    val model: String? = null,
    /** Contract 1.39.0: the reasoning effort `/effort` set; null = never set. */
    val effort: String? = null,
    /** Contract 1.4.0: the agent profile the session was spawned from, and that profile's role. */
    val agentId: String? = null,
    val role: String? = null,
    /** Contract 1.6.0: the ticket a hand-out started this session for. */
    val ticketId: String? = null,
    val unattended: Boolean = false,
    /** The project the session is bound to (the daemon's `SessionRecord.projectId`); null = no project. */
    val projectId: String? = null,
    /**
     * Contract 1.52.0 (TRAITS-T6): where the session stands on its REPORT BACK obligation; null = no obligation, or
     * an older daemon that never sends it. See [SessionReport].
     */
    val report: SessionReport? = null,
    /** Background processes the session runs while no turn is active; the wire state then reads BUSY for display only. */
    val backgroundWork: BackgroundWorkView? = null,
    /** Jobs the agent registered through `agenthq bg run`; the wire state reads BUSY while any is present. */
    val bgJobs: List<BgJobView> = emptyList(),
    /** Contract 1.112.0: a turn is in flight (state BUSY alone may be background work only); null from an older daemon. */
    val turnActive: Boolean? = null,
    /** PIPELINE-WATCHDOG: what the daemon derived about this agent's pending work; null from an older daemon. */
    val watch: AgentWatchView? = null,
    /** Contract 1.115.0: the installation-wide Assistant session (no project); false from an older daemon. */
    val global: Boolean = false,
    /** Contract 1.117.0: the agent's own report on its long job; null = none, or an older daemon. */
    val progress: ProgressView? = null,
) {
    val resumable: Boolean
        get() = providerSessionId != null &&
            state != SessionState.STARTING &&
            (turnActive?.not() ?: (state != SessionState.BUSY)) &&
            state != SessionState.WAITING_INPUT
}

/** PIPELINE-WATCHDOG: the daemon's `AgentWatch`. [state] stays the wire string (WORKING, WAITING, STALLED, QUIET, UNREPORTED, IDLE). */
@Serializable
data class AgentWatchView(
    val state: String,
    val pendingSince: Long? = null,
    val pending: String? = null,
    val lastActivityAt: Long = 0,
    val nudges: Int = 0,
)

/**
 * Contract 1.117.0: the daemon's `SessionProgress`, the agent's MEASUREMENT of its long job (never an estimate). [mode] and
 * [state] stay wire strings so a newer daemon's value decodes; [percent] is null for `indeterminate`.
 */
@Serializable
data class ProgressView(
    val mode: String,
    val state: String = "running",
    val percent: Double? = null,
    val label: String? = null,
    val steps: List<String> = emptyList(),
    val stepsTotal: Int? = null,
    val stepsDone: Int? = null,
    val step: String? = null,
    val done: Long? = null,
    val total: Long? = null,
    val bytesPerSec: Double? = null,
    /** PROGRESS-S5 (opt-in): an indeterminate job that asked for an estimate from past runs; [estimateMs] = their median, null = none yet. */
    val estimated: Boolean? = null,
    val estimateMs: Long? = null,
    val startedAt: Long = 0,
    val updatedAt: Long = 0,
    /** 1.123.0: the countdown inputs; see the daemon's SessionProgress. */
    val labelStartedAt: Long? = null,
    val estimateSource: String? = null,
    val lastOutputAt: Long? = null,
)

@Serializable
data class BgJobView(val id: String, val label: String, val pid: Long = 0, val startedAt: Long = 0)

@Serializable
data class BackgroundWorkView(val pid: Long, val label: String, val startedAt: Long, val count: Int = 1)

/**
 * Contract 1.52.0 (TRAITS-T6, docs/AGENTHQ-ROLE-TRAITS.md section 5): the daemon's `SessionRecord.report`. [status] is
 * kept as the wire string (`pending`, `reported`, `unreported`) so a status added later decodes and simply shows no flag.
 */
@Serializable
data class SessionReport(
    val status: String = STATUS_PENDING,
    val traitId: String? = null,
    val reprompted: Boolean = false,
    val checkedRun: Int = 0,
    val reportMessageId: String? = null,
    val attentionId: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_REPORTED = "reported"
        const val STATUS_UNREPORTED = "unreported"
    }
}

@Serializable
data class WorktreeStatus(
    val sessionId: String,
    val repoRoot: String,
    val path: String,
    val branch: String,
    val baseRef: String,
    val exists: Boolean,
    val dirty: Boolean,
    val ahead: Int,
    val behind: Int,
    val removed: Boolean,
    /**
     * Changed files of this session's own worktree. `null` = UNKNOWN (older daemon, missing directory, or
     * git unreadable); empty = KNOWN CLEAN. A client must never render null as "clean".
     */
    val changes: List<ChangedFile>? = null,
    /** True when the daemon cut [changes] at its cap. */
    val changesTruncated: Boolean = false,
)

/** What git says happened to a path (mirrors the daemon's `ChangeKind`). */
@Serializable
enum class ChangeKind { ADDED, MODIFIED, DELETED, RENAMED, UNTRACKED, CONFLICTED }

/** One changed path; [staged] says which side of the index [status] describes. */
@Serializable
data class ChangedFile(
    val path: String,
    val status: ChangeKind,
    val staged: Boolean,
    val fromPath: String? = null,
)

@Serializable
data class WaitingOn(
    val session: String,
    val messageId: String,
    val threadId: String,
    val waitingOn: String,
    val since: Long,
    val subject: String,
)

@Serializable
data class StatusPayload(
    val state: SessionState,
    val detail: String = "",
    /** Contract 1.25.0: the running tool's name (`Read`, `Bash`, …), or null (AGENT-UX-9). */
    val tool: String? = null,
    /** Contract 1.26.0: the tool call's salient argument (`ls -la src`), or null (AGENT-UX-12). */
    val activity: String? = null,
    /** Contract 1.80.0 (UX-WRITE-1): what a file-writing tool call wrote, or null for every other status. */
    val write: FileWrite? = null,
    /** Contract 1.127.0 (TODO-OVERLAY-1): the agent's whole to-do list (REPLACES the client's), or null on every other status. */
    val todos: List<TodoItemPayload>? = null,
    /** Contract 1.95.0 (1c24fdc4): the run's harness-neutral activity, sent when its phase or label changes. */
    val runActivity: RunActivityPayload? = null,
)

/**
 * Contract 1.95.0 (1c24fdc4): what the run is doing. [state] is one of `idle`, `thinking`, `tool_running`,
 * `waiting_for_input`, `finished`, `error`; kept as a string so a phase added later decodes instead of failing the
 * whole frame. [label] names the tool call while `tool_running`. Times are the daemon's clock (epoch ms).
 */
/** Contract 1.127.0: one to-do item; [status] is `pending`, `in_progress` or `done`, [activeText] the present-tense form or null. */
@Serializable
data class TodoItemPayload(val text: String, val status: String, val activeText: String? = null)

@Serializable
data class RunActivityPayload(
    val state: String,
    val label: String? = null,
    val startedAt: Long = 0,
    val turnStartedAt: Long = 0,
    val lastEventAt: Long = 0,
)

/**
 * Contract 1.80.0 (UX-WRITE-1): what one file-writing tool call wrote. One of [content] (the whole file, a `Write`),
 * [edits] (the replacements of an `Edit` / `MultiEdit`) or [patch] (an applied patch's text) is set. [truncated]: the
 * daemon cut a text at its cap.
 */
@Serializable
data class FileWrite(
    val path: String,
    val content: String? = null,
    val edits: List<FileEdit>? = null,
    val patch: String? = null,
    val truncated: Boolean = false,
)

/** Contract 1.80.0: one replacement of an edit: [oldText] became [newText]. */
@Serializable
data class FileEdit(val oldText: String, val newText: String)

@Serializable
data class FinishedPayload(
    val exitCode: Int?,
    val outcome: Outcome,
    val detail: String = "",
) {
    @Serializable
    enum class Outcome { SUCCEEDED, FAILED, KILLED_GRACEFUL, KILLED_FORCED, ORPHANED }
}

@Serializable
data class NodeSpec(
    val name: String,
    val prompt: String,
    val dependsOn: Set<String> = emptySet(),
    val maxAttempts: Int = 1,
)

@Serializable
data class PodSubmitRequest(
    val goal: String,
    val nodes: List<NodeSpec>,
    val maxConcurrency: Int = 1,
)

@Serializable
data class RunRecord(
    val id: String,
    val sessionId: String,
    val provider: String,
    val workingDir: String,
    val prompt: String,
    val state: SessionState,
    val startedAt: Long,
    val endedAt: Long? = null,
    val exitCode: Int? = null,
    val stdoutLines: Long = 0,
    val stderrLines: Long = 0,
    val providerSessionId: String? = null,
    val error: String? = null,
    val podId: String? = null,
) {
    val isFinished: Boolean get() = endedAt != null
}

@Serializable
data class PodRecord(
    val id: String,
    val goal: String,
    val state: String,
    val maxConcurrency: Int = 1,
    val createdAt: Long,
    val endedAt: Long? = null,
)

@Serializable
data class NodeRecord(
    val id: String,
    val podId: String,
    val name: String,
    val specIndex: Int,
    val prompt: String,
    val state: String,
    val maxAttempts: Int = 1,
    val attempts: Int = 0,
    val reviewState: String? = null,
    val detail: String? = null,
    val createdAt: Long,
    val endedAt: Long? = null,
)

@Serializable
data class PodStatus(
    val pod: PodRecord,
    val nodes: List<NodeRecord>,
    val runs: Map<String, List<RunRecord>>,
)

/**
 * Contract 1.18.0: one project on the wire (`project` / `projects` frames); the UI renders id/name/goal.
 * [directory] (contract 1.36.0) is the absolute directory the project lives in on the DAEMON's machine, `""` = none.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val goal: String = "",
    val directory: String = "",
    /** Contract 1.81.0 (BASE-1a): the branch the project's ticket starts cut from; `""` = none (the daemon's default). */
    val baseBranch: String = "",
    /** Contract 1.111.0 (LANE-CAPS): the most agents of a role that run at once, by role id; a role not listed runs 1. */
    val laneCaps: Map<String, Int> = emptyMap(),
    /** Contract 1.118.0 (BASICS-KILL-1): a done ticket's worker session is ended and removed; false from an older daemon. */
    val autoRemoveDoneWorkers: Boolean = false,
)

/** Contract 1.111.0 (LANE-CAPS) `project_config`, answered by `project`: [laneCaps] merges into the stored caps; null reads. */
@Serializable
data class ProjectConfigRequest(
    val projectId: String,
    val laneCaps: Map<String, Int>? = null,
    /** Contract 1.118.0: set `Project.autoRemoveDoneWorkers`; null = unchanged. */
    val autoRemoveDoneWorkers: Boolean? = null,
)

/**
 * Contract 1.81.0 (BASE-1a) `project_base`, answered by `project`: [branch] sets the project's base branch (refused with
 * an `error` `project_base refused: ...` unless it resolves where tickets are cut), [clear] removes it.
 */
@Serializable
data class ProjectBaseRequest(val projectId: String, val branch: String? = null, val clear: Boolean = false)

/** Contract 1.18.0 (PW-5): one workspace on the wire (`workspace` / `workspaces` frames), owned by its project. */
@Serializable
data class Workspace(val id: String, val projectId: String, val name: String, val path: String? = null)

/**
 * Contract 1.8.0 `project_save` payload. A CREATE sends no [id] (the daemon mints it); an EDIT (contract 1.36.0
 * settings) sends the known [id] plus only the fields to change. A null field is omitted from the encoded JSON and
 * the daemon keeps (or, on create, leaves empty) that field. [directory] `""` CLEARS the project's directory.
 */
@Serializable
data class ProjectSave(
    val name: String? = null,
    val goal: String? = null,
    val id: String? = null,
    val directory: String? = null,
)

/**
 * Contract 1.36.0: the answer to `project_git_status`. [available] `false` means the daemon COULD NOT read the
 * tree ([reason] says why: no directory, directory gone, not a repository) -- that is unknown, never clean.
 * [changes] uses the same entries as the worktree view; [repoRoot] may be above the project's directory.
 */
@Serializable
data class ProjectGitStatus(
    val projectId: String,
    val directory: String = "",
    val available: Boolean = false,
    val reason: String = "",
    val repoRoot: String = "",
    val branch: String? = null,
    val head: String? = null,
    val changes: List<ChangedFile> = emptyList(),
    val changesTruncated: Boolean = false,
)

/**
 * Contract 1.36.0 `project_git_diff` request: a repository-relative [path], on the [staged] or unstaged side.
 * [worktree] (contract 1.88.0) reads it in that worktree of the project's repository; null = the project's directory.
 */
@Serializable
data class ProjectGitDiffRequest(val projectId: String, val path: String, val staged: Boolean = false, val worktree: String? = null)

/** Contract 1.36.0: the answer to `project_git_diff`. A [binary] file carries no [patch] text. */
@Serializable
data class ProjectGitPatch(
    val projectId: String,
    val path: String,
    val staged: Boolean = false,
    val available: Boolean = false,
    val reason: String = "",
    val patch: String = "",
    val binary: Boolean = false,
    val truncated: Boolean = false,
    /** Contract 1.65.0: the branch whose `mergeBase..branch` diff this answers; null for a working-tree diff. */
    val branch: String? = null,
    /** Contract 1.88.0: the worktree the diff was read in; null for the project's directory. */
    val worktree: String? = null,
)

/** Contract 1.88.0 `project_git_worktrees` request: [sessionId] marks (and opens the view on) that session's worktree. */
@Serializable
data class ProjectGitWorktreesRequest(val projectId: String, val sessionId: String? = null)

/** Contract 1.88.0: one worktree of the project's repository. [project]/[session] mark the ones holding those directories. */
@Serializable
data class GitWorktree(
    val path: String,
    val branch: String? = null,
    val head: String? = null,
    val main: Boolean = false,
    val project: Boolean = false,
    val session: Boolean = false,
    val detached: Boolean = false,
    val bare: Boolean = false,
    val prunable: Boolean = false,
)

/** Contract 1.88.0: the answer to `project_git_worktrees`; [selected] is the path the view opens on. */
@Serializable
data class ProjectGitWorktreeList(
    val projectId: String,
    val available: Boolean = false,
    val reason: String = "",
    val worktrees: List<GitWorktree> = emptyList(),
    val selected: String? = null,
)

/** Contract 1.88.0 `project_git_worktree_status` request: [path] is a [GitWorktree.path]. */
@Serializable
data class ProjectGitWorktreeStatusRequest(val projectId: String, val path: String, val historyLimit: Int = 30)

/**
 * Contract 1.88.0: the answer to `project_git_worktree_status`. [ahead]/[behind] count against [base] and are null
 * when there is none ([baseReason] says why); [uncommitted] counts distinct changed paths. [available] `false` = unknown.
 */
@Serializable
data class ProjectGitWorktreeStatus(
    val projectId: String,
    val path: String,
    val available: Boolean = false,
    val reason: String = "",
    val branch: String? = null,
    val head: String? = null,
    val base: String = "",
    val ahead: Int? = null,
    val behind: Int? = null,
    val baseReason: String = "",
    val uncommitted: Int = 0,
    val changes: List<ChangedFile> = emptyList(),
    val changesTruncated: Boolean = false,
    val commits: List<GitCommit> = emptyList(),
    val commitsTruncated: Boolean = false,
)

/** Contract 1.65.0 `project_git_branches` request. */
@Serializable
data class ProjectGitBranchesRequest(val projectId: String)

/** Contract 1.65.0: one local branch; [worktreePath] is set when a worktree has it checked out. */
@Serializable
data class GitBranch(
    val name: String,
    val head: String = "",
    val upstream: String? = null,
    val isWorktree: Boolean = false,
    val worktreePath: String? = null,
)

/** Contract 1.65.0: the answer to `project_git_branches`; [available] `false` = unknown, see [ProjectGitStatus]. */
@Serializable
data class ProjectGitBranchList(
    val projectId: String,
    val available: Boolean = false,
    val reason: String = "",
    val current: String? = null,
    val branches: List<GitBranch> = emptyList(),
    val truncated: Boolean = false,
)

/** Contract 1.65.0 `project_git_branch_changes` request; a null [base] lets the daemon pick the default base. */
@Serializable
data class ProjectGitBranchChangesRequest(val projectId: String, val branch: String, val base: String? = null)

/** Contract 1.65.0: one path a branch changed since the merge-base, with its line counts. */
@Serializable
data class BranchChangedFile(
    val path: String,
    val status: ChangeKind,
    val fromPath: String? = null,
    val added: Int = 0,
    val removed: Int = 0,
    val binary: Boolean = false,
)

/** Contract 1.65.0: the answer to `project_git_branch_changes`; [base] is the base actually used. */
@Serializable
data class ProjectGitBranchDiffstat(
    val projectId: String,
    val branch: String,
    val base: String = "",
    val mergeBase: String = "",
    val available: Boolean = false,
    val reason: String = "",
    val changes: List<BranchChangedFile> = emptyList(),
    val truncated: Boolean = false,
)

/** Contract 1.74.0 `project_git_checkout` request: switch to [branch], or create it from HEAD and switch when [create]. */
@Serializable
data class ProjectGitCheckoutRequest(val projectId: String, val branch: String, val create: Boolean = false)

/**
 * Contract 1.74.0: the answer to `project_git_checkout`. [switched] `false` is a refusal with a stable [code]
 * (`dirty`, `held_by_worktree`, `bad_name`, `no_branch`, `exists`, `unavailable`, `git_failed`) and a [reason].
 */
@Serializable
data class ProjectGitCheckoutResult(
    val projectId: String,
    val branch: String,
    val create: Boolean = false,
    val switched: Boolean = false,
    val code: String = "",
    val reason: String = "",
    val previous: String? = null,
    val current: String? = null,
    val dirtyCount: Int = 0,
    val worktreePath: String? = null,
)

/** Contract 1.65.0 `project_git_branch_diff` request: one [path] of [branch] vs [base]. */
@Serializable
data class ProjectGitBranchDiffRequest(val projectId: String, val branch: String, val base: String? = null, val path: String)

/** Contract 1.36.0 `project_git_log` request. */
@Serializable
data class ProjectGitLogRequest(val projectId: String, val limit: Int = 50)

/** Contract 1.36.0: one commit; [timestamp] is epoch SECONDS. */
@Serializable
data class GitCommit(
    val hash: String,
    val shortHash: String,
    val author: String,
    val timestamp: Long,
    val subject: String,
)

/** Contract 1.36.0: the answer to `project_git_log`, newest first; [available] `false` = unknown, see [ProjectGitStatus]. */
@Serializable
data class ProjectGitHistory(
    val projectId: String,
    val available: Boolean = false,
    val reason: String = "",
    val commits: List<GitCommit> = emptyList(),
    val truncated: Boolean = false,
)

/** Contract 1.18.0 `workspace_save` payload: the owning project, the name, the optional checkout folder. */
@Serializable
data class WorkspaceSave(val projectId: String, val name: String, val path: String? = null)

/**
 * Contract 1.19.0 (EPIC-4): one epic on the wire (`epic` / `epics` frames), owned by its project.
 * [storyIds] is the epic's ordered story list, empty on creation.
 */
@Serializable
data class Epic(
    val id: String,
    val projectId: String,
    val name: String,
    val goal: String = "",
    val storyIds: List<String> = emptyList(),
)

/**
 * Contract 1.19.0 `epic_save` payload for a CREATE: the daemon mints the id, so no `id` is sent.
 * A null [goal] is omitted from the encoded JSON, and the daemon leaves the goal empty.
 */
@Serializable
data class EpicSave(val projectId: String, val name: String, val goal: String? = null)

/**
 * Contract 1.8.0 (MVP story board): one story on the wire (`story` / `stories` frames), owned by its
 * project. [status] is one of the frozen story vocabulary -- `todo`, `doing`, `done` -- and a story
 * carries no `position`, no `description` and no timestamps (ordering lives on [Project.storyIds]).
 */
@Serializable
data class Story(
    val id: String,
    val projectId: String,
    val title: String,
    val status: String,
)

/**
 * Contract 1.8.0 `story_save` payload: a create carries a [title] and the [projectId] it belongs to
 * (the daemon mints the id and lands it in `todo`); an edit carries the [id] of an existing story.
 * Contract 1.22.0 (STORY-EPIC-1) adds an optional [epicId]: a create given one also joins that epic,
 * and the daemon ignores it on an edit. A null field is omitted from the encoded JSON and the daemon
 * leaves it as it is.
 */
@Serializable
data class StorySave(
    val id: String? = null,
    val projectId: String? = null,
    val title: String? = null,
    val epicId: String? = null,
)

/** Contract 1.8.0 `story_move` payload: put story [id] in column [status] (null = stay in its column). */
@Serializable
data class StoryMove(val id: String, val status: String? = null)

/**
 * Contract 1.21.0 (MEM-8): one stored project-memory note, the payload of a `memory_note` frame (the
 * `memory_save` answer). A note's identity inside its project is its [name]; [folder] defaults to the
 * project root and [updatedAt] is the daemon's ISO instant.
 */
@Serializable
data class MemoryNote(
    val id: String,
    val projectId: String,
    val name: String,
    val folder: String = "",
    val description: String = "",
    val body: String = "",
    val updatedAt: String = "",
    /** Contract 1.98.0 (NOTES-1): `project` | `role` | `session`, and who owns it ("" | role id | session id). */
    val scope: String = "project",
    val ownerId: String = "",
)

/** Contract 1.44.0 (P10-A): the payload of `prompt_save`. An existing [name] is updated in place by the daemon. */
@Serializable
data class PromptSave(val name: String, val body: String, val tags: List<String> = emptyList())

/** Contract 1.45.0 (P10-B): the payload of `skill_save`. An existing [name] is updated in place by the daemon. */
@Serializable
data class SkillSave(val name: String, val description: String = "", val body: String, val tags: List<String> = emptyList())

/** Contract 1.45.0 (P10-B): one saved skill, as the `skill_item` and `skills` frames carry it. */
@Serializable
data class SkillView(
    val id: String,
    val name: String,
    val description: String = "",
    val body: String,
    val tags: List<String> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * Contract 1.48.0 (TRAITS-T1): the optional check of a trait. v1 knows one [kind], `report`: the session mails [reportTo]
 * one report carrying every label in [fields]; [reprompt] = one re-prompt before the attention item.
 */
@Serializable
data class TraitCheck(
    val kind: String = KIND_REPORT,
    val reportTo: String = DEFAULT_REPORT_TO,
    val fields: List<String> = emptyList(),
    val reprompt: Boolean = true,
) {
    companion object {
        const val KIND_REPORT = "report"
        const val DEFAULT_REPORT_TO = "pm"
    }
}

/** Contract 1.48.0: the payload of `trait_save`. Save-as-update is by [id] (an author-chosen slug), not by name; decoded strictly. */
@Serializable
data class TraitSave(val id: String, val name: String, val body: String, val check: TraitCheck? = null)

/** Contract 1.48.0: one stored trait, as the `trait_item` and `traits` frames carry it. */
@Serializable
data class TraitView(
    val id: String,
    val name: String,
    val version: Int = 1,
    val body: String,
    val check: TraitCheck? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/** Contract 1.49.0 (TRAITS-T2): the payload of `role_traits_set`; [traitIds] is ordered and `[]` clears. Decoded strictly. */
@Serializable
data class RoleTraitsSave(val roleId: String, val traitIds: List<String>)

/** Contract 1.49.0: one role's stored trait list, as the `role_traits` and `role_traits_all` frames carry it. */
@Serializable
data class RoleTraits(val roleId: String, val traitIds: List<String> = emptyList(), val updatedAt: Long = 0)

/** Contract 1.44.0 (P10-A): one saved prompt, as the `prompt_item` and `prompts` frames carry it. */
@Serializable
data class PromptView(
    val id: String,
    val name: String,
    val body: String,
    val tags: List<String> = emptyList(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * Contract 1.21.0 (MEM-8): one row of a `memory_notes` frame -- a project's note reduced to the pair an
 * index lists, its [name] and its one-line [description], with the body deliberately withheld.
 */
@Serializable
data class MemoryIndexEntry(val name: String, val description: String = "")

/**
 * Contract 1.20.0 (MEM-5): one ranked row of a `memory_hits` frame -- a matching note reduced to its
 * [name], its one-line [description] and a [snippet] of the body around the match.
 */
@Serializable
data class MemoryHit(val name: String, val description: String = "", val snippet: String = "")

/** Contract 1.51.0 (P10-K2) `memory_get` payload: one note of [projectId], by [name]. */
@Serializable
data class MemoryGet(
    val projectId: String,
    val name: String,
    val scope: String? = null,
    val sessionId: String? = null,
    val role: String? = null,
)

/** Contract 1.98.0 (NOTES-1) `memory_list` payload for a non-project scope (a project list is the bare id). */
@Serializable
data class MemoryListRequest(val projectId: String, val scope: String? = null, val sessionId: String? = null, val role: String? = null)

/** Contract 1.98.0 (NOTES-1) `memory_delete` payload: one note by name inside a scope. */
@Serializable
data class MemoryDelete(val projectId: String, val name: String, val scope: String? = null, val sessionId: String? = null, val role: String? = null)

/** Contract 1.20.0 (MEM-5) `memory_search` payload: the owning [projectId] and the [query] to match. */
@Serializable
data class MemorySearch(val projectId: String, val query: String, val scope: String? = null, val sessionId: String? = null, val role: String? = null)

/** Contract 1.21.0 (MEM-8) `memory_save` payload: the owning [projectId] and the note to write. */
@Serializable
data class MemorySave(
    val projectId: String,
    val name: String,
    val description: String,
    val body: String,
    val scope: String? = null,
    val sessionId: String? = null,
    val role: String? = null,
)

/**
 * AGENT-ROSTER (ROSTER-2..5): the `agent_roster` request payload -- the project whose
 * agents the daemon answers with a `roster` frame. The daemon refuses a blank id.
 */
@Serializable
data class RosterRequest(val projectId: String? = null)

/**
 * One row of a project's roster (`roster` frame). The row names no project of its
 * own: a `roster` answer is scoped by the `agent_roster` request it answers.
 */
@Serializable
data class RosterRow(
    val agentId: String,
    val name: String,
    val role: String,
    val status: RosterStatus,
    val sessionId: String? = null,
    val title: String? = null,
    val pending: Int = 0,
)

/**
 * Where an agent is: [BUSY] has a run in flight, [IDLE] is at rest and resumable
 * (a queued message can wake it), [OFFLINE] has no session to wake.
 */
@Serializable
enum class RosterStatus { BUSY, IDLE, OFFLINE }

/**
 * Request encoders for the project / workspace / epic / story frames (contracts 1.8.0, 1.18.0 and
 * 1.19.0), each matching CONTRACT.md byte for byte: `project_list` carries no payload and is answered
 * by `projects`; `project_save` (a create) is answered by `project`; `workspace_list` carries the raw
 * project id as payload (like `project_get`) and is answered by `workspaces`; `workspace_save` by
 * `workspace`; `epic_list` (raw project id) by `epics`; `epic_save` by `epic`; `story_list` (empty
 * data for every project's board, or a raw project id) by `stories`; `story_save` / `story_move` by
 * `story`. A refusal comes back as an `error` frame naming the request (`<frame> refused: <why>`).
 */
object WireRequests {
    private val json = Json

    /**
     * Contract 1.47.0 `agent_swap`. The daemon decodes it strictly, so an absent [model] / [effort] (blank = inherit)
     * is OMITTED, never sent as null.
     */
    /**
     * UX-HARNESS-2 (contract 1.83.0) `session_swap`, strict on the daemon: a blank [model] is omitted (= harness default).
     * SWAP-AGENT-1 (1.86.0): [role] null = keep the role (the key is omitted), blank = no role, else the role to run as.
     */
    fun sessionSwap(sessionId: String, provider: String, model: String?, role: String? = null): Envelope = Envelope(
        Envelope.SESSION_SWAP,
        JsonObject(
            buildMap {
                put("sessionId", JsonPrimitive(sessionId))
                put("provider", JsonPrimitive(provider))
                model?.trim()?.ifEmpty { null }?.let { put("model", JsonPrimitive(it)) }
                role?.let { put("role", JsonPrimitive(it.trim())) }
            },
        ).toString(),
    )

    fun agentSwap(agentId: String, provider: String, model: String?, effort: String?, session: SwapSessionMode): Envelope = Envelope(
        Envelope.AGENT_SWAP,
        JsonObject(
            buildMap {
                put("agentId", JsonPrimitive(agentId))
                put("provider", JsonPrimitive(provider))
                model?.trim()?.ifEmpty { null }?.let { put("model", JsonPrimitive(it)) }
                effort?.trim()?.ifEmpty { null }?.let { put("effort", JsonPrimitive(it)) }
                put("session", JsonPrimitive(session.wire))
            },
        ).toString(),
    )

    /**
     * Contract 1.4.0 `agent_save`, carrying the profile's effort only when the daemon stores one ([withEffort],
     * contract 1.46.0): an older daemon is never sent the `effort` key at all.
     */
    /**
     * ROLE-ICON-1: `role_save`. `icon` is dropped when [withIcon] is false (a daemon below 1.93.0 decodes strictly and
     * would refuse the whole save) and when it is null (keep the stored icon), so an icon-less save is byte-for-byte
     * the pre-1.93.0 payload.
     */
    fun roleSave(save: RoleSave, withIcon: Boolean, withDescription: Boolean = false): Envelope {
        val full = Json { encodeDefaults = true }.encodeToJsonElement(RoleSave.serializer(), save) as JsonObject
        // ROLE-EDIT-1: `description` likewise only to a daemon on contract >= 1.108.0, and only when set (null = keep).
        val noIcon = if (withIcon && save.icon != null) full else JsonObject(full - "icon")
        return Envelope(Envelope.ROLE_SAVE, (if (withDescription && save.description != null) noIcon else JsonObject(noIcon - "description")).toString())
    }

    /** ROLE-EDIT-1: delete a custom role (or reset a customised bundled one). */
    fun roleDelete(roleId: String): Envelope = Envelope(Envelope.ROLE_DELETE, roleId.trim())

    fun agentSave(profile: AgentProfile, withEffort: Boolean): Envelope {
        val full = Json { encodeDefaults = true }.encodeToJsonElement(AgentProfile.serializer(), profile) as JsonObject
        return Envelope(Envelope.AGENT_SAVE, (if (withEffort) full else JsonObject(full - "effort")).toString())
    }

    /**
     * `worktrees` naming ONE session: the daemon answers a single `worktree` frame (one git read) instead of
     * the list of every worktree. A daemon that predates this ignores the id and answers the list; the
     * reducer handles both, so the request is safe against any daemon.
     */
    fun worktreeOf(sessionId: String): Envelope = Envelope(Envelope.WORKTREES, sessionId)

    /**
     * `command_catalog` for [sessionId]'s provider, filtered by [typed] (the text after the slash, as typed so far).
     * With no session (the New agent box) [provider] names whose catalog; null there means the daemon's default provider.
     */
    fun commandCatalog(sessionId: String?, typed: String, provider: String? = null): Envelope = Envelope(
        Envelope.COMMAND_CATALOG,
        Json { encodeDefaults = true; explicitNulls = false }.encodeToString(CommandCatalogRequest.serializer(), CommandCatalogRequest(typed, provider)),
        sessionId,
    )

    /** `project_list` (empty data): the daemon answers `projects` with every project in creation order. */
    fun projectList(): Envelope = Envelope(Envelope.PROJECT_LIST)

    /** `project_save` for a CREATE: the daemon mints the id and answers a `project` frame. */
    fun projectSave(name: String, goal: String? = null): Envelope = Envelope(
        Envelope.PROJECT_SAVE,
        json.encodeToString(ProjectSave.serializer(), ProjectSave(name = name, goal = goal)),
    )

    /** `project_save` for an EDIT of [projectId]: only the name changes; the id (and so everything keyed by it) stays. */
    fun projectRename(projectId: String, name: String): Envelope = Envelope(
        Envelope.PROJECT_SAVE,
        json.encodeToString(ProjectSave.serializer(), ProjectSave(id = projectId, name = name)),
    )

    /** Encodes EVERY field (a `staged = false` or a cleared `""` directory is meaning, not noise). */
    private val full = Json { encodeDefaults = true }

    /** `project_save` for an EDIT of [projectId]: only the directory changes; `""` clears it (contract 1.36.0). */
    fun projectSetDirectory(projectId: String, directory: String): Envelope = Envelope(
        Envelope.PROJECT_SAVE,
        json.encodeToString(ProjectSave.serializer(), ProjectSave(id = projectId, directory = directory)),
    )

    /** `project_config` (contract 1.111.0): set [projectId]'s cap of concurrent [role] agents to [cap]. */
    fun projectConfig(projectId: String, role: String, cap: Int): Envelope = Envelope(
        Envelope.PROJECT_CONFIG,
        Json { encodeDefaults = false; explicitNulls = false }.encodeToString(
            ProjectConfigRequest.serializer(), ProjectConfigRequest(projectId, mapOf(role to cap)),
        ),
    )

    /** `project_config` (contract 1.118.0): turn [projectId]'s auto-removal of done tickets' worker sessions on or off. */
    fun projectAutoRemove(projectId: String, on: Boolean): Envelope = Envelope(
        Envelope.PROJECT_CONFIG,
        Json { encodeDefaults = false; explicitNulls = false }.encodeToString(
            ProjectConfigRequest.serializer(), ProjectConfigRequest(projectId, autoRemoveDoneWorkers = on),
        ),
    )

    /** `project_base` (contract 1.81.0): set [projectId]'s base branch to [branch], or clear it when [branch] is null. */
    fun projectBase(projectId: String, branch: String?): Envelope = Envelope(
        Envelope.PROJECT_BASE,
        Json { encodeDefaults = false; explicitNulls = false }.encodeToString(
            ProjectBaseRequest.serializer(),
            if (branch == null) ProjectBaseRequest(projectId, clear = true) else ProjectBaseRequest(projectId, branch),
        ),
    )

    /** `project_git_status`: the raw project id; the daemon answers `project_git` (contract 1.36.0). */
    fun projectGitStatus(projectId: String): Envelope = Envelope(Envelope.PROJECT_GIT_STATUS, projectId)

    /**
     * `project_git_diff` for one changed [path]; the daemon answers `project_git_patch` (contract 1.36.0). A null
     * [worktree] is OMITTED, never sent as `null`: a daemon before 1.88.0 decodes this strictly and refuses unknown keys.
     */
    fun projectGitDiff(projectId: String, path: String, staged: Boolean, worktree: String? = null): Envelope = Envelope(
        Envelope.PROJECT_GIT_DIFF,
        fullNoNulls.encodeToString(ProjectGitDiffRequest.serializer(), ProjectGitDiffRequest(projectId, path, staged, worktree)),
    )

    /** Every field, except a null one, which is left out (the daemon decodes strictly; see [projectGitDiff]). */
    private val fullNoNulls = Json { encodeDefaults = true; explicitNulls = false }

    /** `project_git_worktrees` (contract 1.88.0); the daemon answers `project_git_worktree_list`. */
    fun projectGitWorktrees(projectId: String, sessionId: String? = null): Envelope = Envelope(
        Envelope.PROJECT_GIT_WORKTREES,
        fullNoNulls.encodeToString(ProjectGitWorktreesRequest.serializer(), ProjectGitWorktreesRequest(projectId, sessionId)),
    )

    /** `project_git_worktree_status` for the worktree at [path] (contract 1.88.0); the daemon answers `project_git_worktree`. */
    fun projectGitWorktreeStatus(projectId: String, path: String, historyLimit: Int = 30): Envelope = Envelope(
        Envelope.PROJECT_GIT_WORKTREE_STATUS,
        full.encodeToString(ProjectGitWorktreeStatusRequest.serializer(), ProjectGitWorktreeStatusRequest(projectId, path, historyLimit)),
    )

    /** `pipeline_show`; the daemon answers `pipeline_state` (contract 1.99.0). */
    fun pipelineShow(projectId: String): Envelope = Envelope(
        Envelope.PIPELINE_SHOW,
        full.encodeToString(PipelineShowRequest.serializer(), PipelineShowRequest(projectId)),
    )

    /** `pipeline_config`: set the stage names ([stages] non-empty), or remove the pipeline (null [stages]). */
    fun pipelineConfigure(projectId: String, stages: List<String>?, exclusiveCheck: Boolean = false): Envelope = Envelope(
        Envelope.PIPELINE_CONFIG,
        full.encodeToString(
            PipelineConfigureRequest.serializer(),
            PipelineConfigureRequest(projectId, stages, exclusiveCheck, clear = stages == null),
        ),
    )

    /** `project_git_branches`; the daemon answers `project_git_branch_list` (contract 1.65.0). */
    fun projectGitBranches(projectId: String): Envelope = Envelope(
        Envelope.PROJECT_GIT_BRANCHES,
        full.encodeToString(ProjectGitBranchesRequest.serializer(), ProjectGitBranchesRequest(projectId)),
    )

    /** `project_git_branch_changes`; the daemon answers `project_git_branch_diffstat` (contract 1.65.0). */
    fun projectGitBranchChanges(projectId: String, branch: String, base: String? = null): Envelope = Envelope(
        Envelope.PROJECT_GIT_BRANCH_CHANGES,
        full.encodeToString(ProjectGitBranchChangesRequest.serializer(), ProjectGitBranchChangesRequest(projectId, branch, base)),
    )

    /** `project_git_checkout`; the daemon answers `project_git_checkout_result` (contract 1.74.0). */
    fun projectGitCheckout(projectId: String, branch: String, create: Boolean = false): Envelope = Envelope(
        Envelope.PROJECT_GIT_CHECKOUT,
        full.encodeToString(ProjectGitCheckoutRequest.serializer(), ProjectGitCheckoutRequest(projectId, branch, create)),
    )

    /** `project_git_branch_diff` for one [path]; the daemon answers `project_git_patch` with `branch` (contract 1.65.0). */
    fun projectGitBranchDiff(projectId: String, branch: String, path: String, base: String? = null): Envelope = Envelope(
        Envelope.PROJECT_GIT_BRANCH_DIFF,
        full.encodeToString(ProjectGitBranchDiffRequest.serializer(), ProjectGitBranchDiffRequest(projectId, branch, base, path)),
    )

    /** `project_git_log`; the daemon answers `project_git_history`, newest first (contract 1.36.0). */
    fun projectGitLog(projectId: String, limit: Int = 50): Envelope = Envelope(
        Envelope.PROJECT_GIT_LOG,
        full.encodeToString(ProjectGitLogRequest.serializer(), ProjectGitLogRequest(projectId, limit)),
    )

    /** `workspace_list` for [projectId]: the raw id as payload; the daemon answers `workspaces`. */
    fun workspaceList(projectId: String): Envelope = Envelope(Envelope.WORKSPACE_LIST, projectId)

    /** `workspace_save` inside [projectId]: the daemon answers a `workspace` frame. */
    fun workspaceSave(projectId: String, name: String, path: String? = null): Envelope = Envelope(
        Envelope.WORKSPACE_SAVE,
        json.encodeToString(WorkspaceSave.serializer(), WorkspaceSave(projectId = projectId, name = name, path = path)),
    )

    /** `settings_get` for one [key]; the daemon answers a `settings` frame holding only that key. */
    fun settingsGet(key: String): Envelope = Envelope(Envelope.SETTINGS_GET, key)

    /** STATS-2: `session_stats_get` for one AgentHQ session id (contract 1.92.0); answered by `session_stats`. */
    fun sessionStatsGet(sessionId: String): Envelope =
        Envelope(Envelope.SESSION_STATS_GET, JsonObject(mapOf("sessionId" to JsonPrimitive(sessionId))).toString())

    /** ROGUE-1 (1.120.0): list the tracked processes (`op=list`), reap one (`reap` + [pid]) or every rogue one (`reap_rogue`). */
    fun processes(op: String = "list", pid: Long? = null): Envelope = Envelope(
        Envelope.PROCESSES,
        JsonObject(buildMap { put("op", JsonPrimitive(op)); if (pid != null) put("pid", JsonPrimitive(pid)) }).toString(),
    )

    /** `settings_set`: the daemon validates [value], stores it and answers `settings` with every setting. */
    fun settingsSet(key: String, value: JsonElement): Envelope = Envelope(
        Envelope.SETTINGS_SET,
        JsonObject(mapOf("key" to JsonPrimitive(key), "value" to value)).toString(),
    )

    /** CLIPATH-1 (1.73.0): ask where each CLI is launched from; the daemon answers `cli_locations`. */
    fun cliLocations(): Envelope = Envelope(Envelope.CLI_LOCATIONS)

    /** CLIPATH-1 (1.73.0): set [id]'s executable to [path], or clear it back to the default when [path] is null. */
    fun cliLocationSet(id: String, path: String?): Envelope = Envelope(
        Envelope.CLI_LOCATION_SET,
        JsonObject(mapOf("id" to JsonPrimitive(id), "path" to (path?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull))).toString(),
    )

    /** HARNESS-INSTALL-1 (1.102.0): a real server-side check of every harness CLI; [checkLatest] also asks npm for the newest version. */
    fun harnessInstallStatus(checkLatest: Boolean = false): Envelope =
        Envelope(Envelope.HARNESS_INSTALL_STATUS, JsonObject(mapOf("checkLatest" to JsonPrimitive(checkLatest))).toString())

    /** HARNESS-INSTALL-1 (1.102.0): install (or, with [update], refresh) [ids] on the daemon machine; empty = all missing. */
    fun harnessInstall(ids: List<String>, update: Boolean): Envelope = Envelope(
        Envelope.HARNESS_INSTALL,
        JsonObject(mapOf("ids" to kotlinx.serialization.json.JsonArray(ids.map(::JsonPrimitive)), "update" to JsonPrimitive(update))).toString(),
    )

    /** PROMPT-2 (1.77.0): answer the open prompt [r.promptId]; the daemon answers with a `prompt_resolved` push. */
    fun promptResponse(r: PromptResponseWire): Envelope =
        Envelope(Envelope.PROMPT_RESPONSE, wireJson.encodeToString(PromptResponseWire.serializer(), r))

    /** ASK-1 (1.97.0): the owner's pick (or override) of a question; the daemon answers with a `question` push. */
    fun questionAnswer(a: QuestionAnswerWire): Envelope =
        Envelope(Envelope.QUESTION_ANSWER, wireJson.encodeToString(QuestionAnswerWire.serializer(), a))

    /** ASK-1 (1.97.0): the owner withdraws an open question; the daemon tells the agent and pushes `question`. */
    fun questionCancel(questionId: String): Envelope =
        Envelope(Envelope.QUESTION_CANCEL, JsonObject(mapOf("questionId" to JsonPrimitive(questionId))).toString())

    /** BUG-PERM-1 (1.76.0): ask for the global Tool approval; the daemon answers `tool_approval`. */
    fun toolApproval(): Envelope = Envelope(Envelope.TOOL_APPROVAL)

    /** Contract 1.102.0 (SECRETS-1): ask for the keystore's set / not set state. */
    fun secretStatus(): Envelope = Envelope(Envelope.SECRET_STATUS, "{}")

    /** Contract 1.102.0: store [value] as secret [name]. The value goes out once and is never read back. */
    fun secretSet(name: String, value: String): Envelope = Envelope(
        Envelope.SECRET_SET,
        JsonObject(mapOf("name" to JsonPrimitive(name), "value" to JsonPrimitive(value))).toString(),
    )

    fun secretClear(name: String): Envelope = Envelope(Envelope.SECRET_CLEAR, JsonObject(mapOf("name" to JsonPrimitive(name))).toString())

    fun secretImport(name: String): Envelope = Envelope(Envelope.SECRET_IMPORT, JsonObject(mapOf("name" to JsonPrimitive(name))).toString())

    /** BUG-PERM-1 (1.76.0): set the global Tool approval to [mode] (`deny` / `allowEdits` / `allowAll`). */
    fun toolApprovalSet(mode: String): Envelope = Envelope(
        Envelope.TOOL_APPROVAL_SET,
        JsonObject(mapOf("mode" to JsonPrimitive(mode))).toString(),
    )

    /** Contract 1.33.0: ask for the saved commands; the daemon answers a `commands` frame. */
    fun commandList(): Envelope = Envelope(Envelope.COMMAND_LIST)

    /**
     * Contract 1.94.0 (RUN-CMD-1): upsert the saved command [id]. [argv] is one element per argument, never a shell
     * string; [cwd] is relative to the daemon's repo (null = the repo root). The daemon answers a `commands` frame.
     */
    fun commandSave(id: String, name: String, argv: List<String>, cwd: String?, graceMillis: Long): Envelope {
        val fields = linkedMapOf<String, JsonElement>(
            "id" to JsonPrimitive(id),
            "name" to JsonPrimitive(name),
            "argv" to kotlinx.serialization.json.JsonArray(argv.map { JsonPrimitive(it) }),
        )
        if (cwd != null) fields["cwd"] = JsonPrimitive(cwd)
        fields["graceMillis"] = JsonPrimitive(graceMillis)
        return Envelope(Envelope.COMMAND_SAVE, JsonObject(fields).toString())
    }

    /** Contract 1.94.0 (RUN-CMD-1): remove the saved command [id]; the daemon answers a `commands` frame. */
    fun commandDelete(id: String): Envelope =
        Envelope(Envelope.COMMAND_DELETE, JsonObject(mapOf("id" to JsonPrimitive(id))).toString())

    /** Contract 1.33.0: start the saved command [commandId]; the daemon answers a `run` frame. */
    fun runStart(commandId: String): Envelope =
        Envelope(Envelope.RUN_START, """{"commandId":${Json.encodeToString(kotlinx.serialization.serializer<String>(), commandId)}}""")

    /** Contract 1.33.0: kill [runId]'s process tree; the answer is the `run_finished` frame. */
    fun runStop(runId: String, force: Boolean = false): Envelope =
        Envelope(Envelope.RUN_STOP, if (force) "$runId force" else runId)

    /** `epic_list` for [projectId]: the raw id as payload; the daemon answers an `epics` frame. */
    fun epicList(projectId: String): Envelope = Envelope(Envelope.EPIC_LIST, projectId)

    /** `epic_save` inside [projectId]: the daemon answers an `epic` frame. */
    fun epicSave(projectId: String, name: String, goal: String? = null): Envelope = Envelope(
        Envelope.EPIC_SAVE,
        json.encodeToString(EpicSave.serializer(), EpicSave(projectId = projectId, name = name, goal = goal)),
    )

    /**
     * `story_list`: with a [projectId] the raw id is the payload (the daemon answers that project's
     * board); with no project the payload is empty and the daemon answers every project's board.
     * Either way the answer is a `stories` frame.
     */
    fun storyList(projectId: String? = null): Envelope = Envelope(Envelope.STORY_LIST, projectId.orEmpty())

    /**
     * UI-BOARD-PROJ: `ticket_list` scoped to [projectId] -- the raw id as payload, answered by a
     * `tickets` frame holding only that project's tickets. With no project the payload is empty, which
     * keeps the whole-board answer a legacy client expects.
     */
    fun ticketList(projectId: String? = null): Envelope = Envelope(Envelope.TICKET_LIST, projectId.orEmpty())

    /**
     * `story_save` inside [projectId]: the daemon answers a `story` frame. An [epicId] (contract
     * 1.22.0) is written only when non-null, so a create ordered from inside an epic joins it while
     * every other create keeps the 1.8.0 payload shape.
     */
    fun storySave(projectId: String, title: String, epicId: String? = null): Envelope = Envelope(
        Envelope.STORY_SAVE,
        json.encodeToString(StorySave.serializer(), StorySave(projectId = projectId, title = title, epicId = epicId)),
    )

    /** `story_move` for [id] into [status] (null = stay in its column): the daemon answers `story`. */
    fun storyMove(id: String, status: String? = null): Envelope = Envelope(
        Envelope.STORY_MOVE,
        json.encodeToString(StoryMove.serializer(), StoryMove(id = id, status = status)),
    )

    /** `prompt_list` (1.44.0): empty [tag] = all prompts; answered by `prompts`. */
    fun promptList(tag: String = ""): Envelope = Envelope(Envelope.PROMPT_LIST, tag)

    /** `prompt_save` (1.44.0): creates the prompt or updates the one with that name; answered by `prompt_item`. */
    fun promptSave(name: String, body: String, tags: List<String>): Envelope = Envelope(
        Envelope.PROMPT_SAVE,
        json.encodeToString(PromptSave.serializer(), PromptSave(name = name, body = body, tags = tags)),
    )

    /** `skill_list` (1.45.0): empty [tag] = all skills; answered by `skills`. */
    fun skillList(tag: String = ""): Envelope = Envelope(Envelope.SKILL_LIST, tag)

    /** `skill_save` (1.45.0): creates the skill or updates the one with that name; answered by `skill_item`. */
    fun skillSave(name: String, description: String, body: String, tags: List<String>): Envelope = Envelope(
        Envelope.SKILL_SAVE,
        json.encodeToString(SkillSave.serializer(), SkillSave(name = name, description = description, body = body, tags = tags)),
    )

    /** `skill_delete` (1.45.0): the raw id; the daemon pushes the remaining `skills`. */
    fun skillDelete(id: String): Envelope = Envelope(Envelope.SKILL_DELETE, id)

    /** `trait_list` (1.48.0): answered by `traits`. */
    fun traitList(): Envelope = Envelope(Envelope.TRAIT_LIST)

    /** `trait_save` (1.48.0): creates or updates the trait with that id; pushed back as `trait_item`. A null check is OMITTED. */
    fun traitSave(save: TraitSave): Envelope = Envelope(Envelope.TRAIT_SAVE, json.encodeToString(TraitSave.serializer(), save))

    /** `trait_delete` (1.48.0): the raw id; the daemon pushes the remaining `traits` (refused while a role or agent binds it). */
    fun traitDelete(id: String): Envelope = Envelope(Envelope.TRAIT_DELETE, id)

    /** `role_traits_list` (1.49.0): answered by `role_traits_all`. */
    fun roleTraitsList(): Envelope = Envelope(Envelope.ROLE_TRAITS_LIST)

    /** `role_traits_set` (1.49.0): the role's whole ordered list (`[]` clears); pushed back as `role_traits`. */
    fun roleTraitsSet(roleId: String, traitIds: List<String>): Envelope =
        Envelope(Envelope.ROLE_TRAITS_SET, json.encodeToString(RoleTraitsSave.serializer(), RoleTraitsSave(roleId, traitIds)))

    /** `prompt_delete` (1.44.0): the raw id; the daemon pushes the remaining `prompts`. */
    fun promptDelete(id: String): Envelope = Envelope(Envelope.PROMPT_DELETE, id)

    /** `memory_list` for [projectId]: the raw id as payload (like `epic_list`); answered by `memory_notes`. */
    fun memoryList(projectId: String): Envelope = Envelope(Envelope.MEMORY_LIST, projectId)

    /** NOTES-1: `memory_list` of one scope; the project scope keeps the bare-id payload. */
    fun memoryList(projectId: String, at: NoteScope): Envelope =
        if (at.scope == NoteScope.PROJECT) memoryList(projectId)
        else Envelope(Envelope.MEMORY_LIST, json.encodeToString(MemoryListRequest.serializer(), MemoryListRequest(projectId, at.scope, at.sessionId, at.role)))

    /** NOTES-1: `memory_delete` of one note of a scope; answered by a `memory_notes` index. */
    fun memoryDelete(projectId: String, name: String, at: NoteScope): Envelope = Envelope(
        Envelope.MEMORY_DELETE,
        json.encodeToString(MemoryDelete.serializer(), MemoryDelete(projectId, name, at.scope, at.sessionId, at.role)),
    )

    /** `memory_save` inside [projectId]: the daemon answers a `memory_note` frame (the stored note). */
    fun memorySave(projectId: String, name: String, description: String, body: String, at: NoteScope = NoteScope.Project): Envelope = Envelope(
        Envelope.MEMORY_SAVE,
        json.encodeToString(
            MemorySave.serializer(),
            MemorySave(projectId = projectId, name = name, description = description, body = body, scope = at.wireScope, sessionId = at.sessionId, role = at.role),
        ),
    )

    /** `memory_get` of one note of [projectId]: the daemon answers a `memory_note` frame (the whole note, body included). */
    fun memoryGet(projectId: String, name: String, at: NoteScope = NoteScope.Project): Envelope = Envelope(
        Envelope.MEMORY_GET,
        json.encodeToString(MemoryGet.serializer(), MemoryGet(projectId = projectId, name = name, scope = at.wireScope, sessionId = at.sessionId, role = at.role)),
    )

    /** `memory_search` inside [projectId]: the daemon answers a `memory_hits` frame (the ranked hits). */
    fun memorySearch(projectId: String, query: String, at: NoteScope = NoteScope.Project): Envelope = Envelope(
        Envelope.MEMORY_SEARCH,
        json.encodeToString(MemorySearch.serializer(), MemorySearch(projectId = projectId, query = query, scope = at.wireScope, sessionId = at.sessionId, role = at.role)),
    )

}

/** Contract 1.77.0 (PROMPT-1): one choice of a `question` prompt. */
@Serializable
data class PromptOptionView(val label: String, val description: String = "")

/**
 * Contract 1.77.0 (PROMPT-1): `prompt_request`. [kind] `tool_approval` carries [tool] and [input] (a one-line summary);
 * `question` carries [question], [options], [multiSelect], [allowOther].
 */
@Serializable
data class PromptRequestView(
    val promptId: String,
    val sessionId: String,
    val kind: String,
    val createdAt: Long = 0,
    val tool: String? = null,
    val input: String? = null,
    val question: String? = null,
    val options: List<PromptOptionView> = emptyList(),
    val multiSelect: Boolean = false,
    val allowOther: Boolean = false,
)

/** Contract 1.77.0 (PROMPT-1): `prompt_response`, a [decision] for a tool approval, [answers]/[otherText] for a question. */
@Serializable
data class PromptResponseWire(
    val promptId: String,
    val decision: String? = null,
    val answers: List<String> = emptyList(),
    val otherText: String? = null,
)

/**
 * Contract 1.97.0 (ASK-1): one owner question (`question` / `questions`). [state] is `open`, `answered`, `auto` (the
 * daemon picked [recommended] after [defaultAfterMin]; still overridable) or `cancelled`.
 */
@Serializable
data class OwnerQuestionView(
    val id: String,
    val projectId: String? = null,
    val sessionId: String,
    val agentId: String? = null,
    val role: String? = null,
    val question: String,
    val options: List<PromptOptionView> = emptyList(),
    val recommended: String,
    val reason: String = "",
    val allowOther: Boolean = false,
    val defaultAfterMin: Int? = null,
    val state: String = "open",
    val answer: String? = null,
    val otherText: String? = null,
    val resolvedBy: String? = null,
    val createdAt: Long = 0,
    val resolvedAt: Long? = null,
    val overridden: Boolean = false,
)

/** Contract 1.97.0 (ASK-1): `question_answer`, the owner's pick ([answer], a label) and/or own text. */
@Serializable
data class QuestionAnswerWire(val questionId: String, val answer: String? = null, val otherText: String? = null)

/** Contract 1.77.0 (PROMPT-1): `prompt_resolved`, the card for [promptId] closes on every client. */
@Serializable
data class PromptResolvedView(val promptId: String, val resolvedBy: String = "", val outcome: String = "")

/** Encodes outgoing frames: defaults kept out, so an unanswered field is absent rather than null. */
internal val wireJson = Json { encodeDefaults = false; explicitNulls = false }

/** Contract 1.102.0 (HARNESS-INSTALL-1): one harness CLI's check. [state]: installed | missing | outdated | unknown | user-managed. */
@Serializable
data class HarnessInstallStatus(
    val id: String,
    val label: String = "",
    val npmPackage: String = "",
    val state: String,
    val installedVersion: String? = null,
    val path: String? = null,
    val managed: Boolean = false,
    val wanted: String? = null,
    val latest: String? = null,
    val note: String? = null,
)

/** The `harness_install_status` frame's data; [npmPath] null = no Node/npm there and [guidance] says what to do. */
@Serializable
data class HarnessInstallReport(
    val harnesses: List<HarnessInstallStatus> = emptyList(),
    val prefixDir: String = "",
    val npmPath: String? = null,
    val guidance: String? = null,
)

@Serializable
data class HarnessInstallOutcome(val id: String, val result: String, val version: String? = null, val message: String? = null)

/** The `harness_install_result` frame's data: per-harness outcomes, the npm log and the status after the run. */
@Serializable
data class HarnessInstallResult(
    val outcomes: List<HarnessInstallOutcome> = emptyList(),
    val log: List<String> = emptyList(),
    val report: HarnessInstallReport = HarnessInstallReport(),
)
