package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope

/**
 * SEC-FIX-1 (contract 1.136.0): the ONE gate every client frame passes before the WebSocket dispatch, a bare text
 * frame included (it decodes as a `prompt`). Two rules, in this order:
 *  1. Under the demo lock (`AGENTHQ_DEMO_LOCK`) only the frames in [DEMO_LOCK_ALLOWED] get through: read-only
 *     queries that spawn nothing, write nothing and read no secret. Every other frame, a frame added later included,
 *     is refused by default. The lock binds the owner too: a demo is shown to people who are not the owner.
 *  2. The frames in [OWNER_ONLY] (saved commands, secrets, run control) need an owner connection ([OwnerCredential]).
 *
 * A refusal is an `error` frame naming the frame type and the rule, and nothing runs.
 */
object FrameGate {
    /** `replay_request` is a frame the daemon reads before its dispatch (a session's stream replay). */
    const val REPLAY_REQUEST = "replay_request"

    val DEMO_LOCK_ALLOWED: Set<String> = setOf(
        REPLAY_REQUEST,
        Envelope.SESSIONS, Envelope.SESSION_STATS_GET,
        Envelope.PROVIDERS, Envelope.PROVIDER_STATES, Envelope.MODELS, Envelope.LIMITS, Envelope.LIMIT_CATALOG,
        Envelope.USAGE_GET, Envelope.SETTINGS_GET, Envelope.CLI_LOCATIONS, Envelope.TOOL_APPROVAL, Envelope.APPROVAL_RULES,
        Envelope.ROLE_LIST, Envelope.ROLE_BINDING_LIST, Envelope.ROLE_BINDING_GET,
        Envelope.PROMPT_LIST, Envelope.PROMPT_GET, Envelope.SKILL_LIST, Envelope.SKILL_GET,
        Envelope.TRAIT_LIST, Envelope.TRAIT_GET, Envelope.ROLE_TRAITS_GET, Envelope.ROLE_TRAITS_LIST,
        Envelope.AGENT_LIST, Envelope.AGENT_ROSTER,
        Envelope.TICKET_GET, Envelope.TICKET_LIST, Envelope.QUESTION_LIST, Envelope.ATTENTION_LIST, Envelope.POD_QUERY,
        Envelope.TRIGGER_LIST, Envelope.TRIGGER_EVENTS,
        Envelope.PROJECT_GET, Envelope.PROJECT_LIST,
        Envelope.PROJECT_GIT_STATUS, Envelope.PROJECT_GIT_DIFF, Envelope.PROJECT_GIT_LOG, Envelope.PROJECT_GIT_HISTORY,
        Envelope.PROJECT_GIT_BRANCHES, Envelope.PROJECT_GIT_BRANCH_CHANGES, Envelope.PROJECT_GIT_BRANCH_DIFF,
        Envelope.PROJECT_GIT_BRANCH_LIST, Envelope.PROJECT_GIT_BRANCH_DIFFSTAT,
        Envelope.PROJECT_GIT_WORKTREES, Envelope.PROJECT_GIT_WORKTREE_STATUS, Envelope.PROJECT_GIT_WORKTREE_LIST,
        Envelope.WORKSPACE_LIST, Envelope.EPIC_LIST, Envelope.STORY_GET, Envelope.STORY_LIST,
        Envelope.MEMORY_SEARCH, Envelope.MEMORY_LIST, Envelope.MEMORY_GET, Envelope.DECISION_LIST, Envelope.DECISION_GET,
        Envelope.PIPELINE_SHOW, Envelope.ISSUE_LIST, Envelope.ISSUE_GET, Envelope.ISSUE_PRECHECK,
    )

    val OWNER_ONLY: Set<String> = setOf(
        Envelope.COMMAND_SAVE, Envelope.COMMAND_DELETE,
        Envelope.RUN_START, Envelope.RUN_STOP, Envelope.RUN_ATTACH, Envelope.RUN_RESIZE, Envelope.RUN_INPUT,
        Envelope.SECRET_SET, Envelope.SECRET_CLEAR, Envelope.SECRET_IMPORT,
    )

    /** Why a frame of [type] is refused on this connection, or null when it may be dispatched. */
    fun refusal(type: String, demoLock: Boolean, owner: Boolean): String? = when {
        demoLock && type !in DEMO_LOCK_ALLOWED -> "${shown(type)} refused: this frame is off under the demo lock"
        !owner && type in OWNER_ONLY -> "$type refused: it needs the owner credential (${OwnerCredential.HEADER}); agents do not hold it"
        else -> null
    }

    /** A client-chosen type is echoed printable and at most [SHOWN_TYPE_CHARS] characters long. */
    private fun shown(type: String): String = type.filter { it in ' '..'~' }.take(SHOWN_TYPE_CHARS)

    private const val SHOWN_TYPE_CHARS = 64
}
