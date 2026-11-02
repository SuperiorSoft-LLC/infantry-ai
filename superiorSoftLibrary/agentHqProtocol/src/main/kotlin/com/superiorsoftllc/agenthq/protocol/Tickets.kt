package com.superiorsoftllc.agenthq.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * Contract 1.5.0 (story 9.2): the BACKLOG's wire types.
 * Contract 1.6.0 (story 9.3): ticket start + controlled context -- executor pins, the role queue, the
 * effective executor/context views, the last run, the start outcome, and the settings frames.
 *
 * The daemon owns the board: which column a ticket is in, where in it, and whether it is held. A client
 * asks for a change (`ticket_save`, `ticket_move`, `ticket_link`, ...) and renders what comes back; it
 * never computes a column or a position itself. That is the session rule applied to tickets: state is
 * what the daemon decides, never what a participant asserts.
 *
 * Plain Kotlin and kotlinx.serialization only, so a non-JVM client can share the types.
 */

/** The board's columns, as the wire spells them. */
object TicketStatus {
    /**
     * Contract 1.131.0: limbo, potential work parked until someone deliberately promotes it to [TODO]. A backlog
     * ticket is never started (a start is refused naming the lane), never picked by any "start next" logic and never
     * moved by the daemon (a held backlog ticket stays in backlog, never goes to [PENDING]).
     */
    const val BACKLOG = "backlog"

    /** The current sprint: committed work, ready to be worked on. */
    const val TODO = "todo"

    /** Started: contract 1.6.0 (story 9.3) starts a session for it (see the `handOut` transaction). */
    const val IN_PROGRESS = "in_progress"

    /** Held by an open `blocked_by` prerequisite. Derived by the daemon, never set by hand. */
    const val PENDING = "pending"
    const val DONE = "done"

    /** Board order: `tickets` lists column by column in this order, each column top first. */
    val ALL: List<String> = listOf(BACKLOG, TODO, IN_PROGRESS, PENDING, DONE)

    /** The refusal a start of a [BACKLOG] ticket answers with, wherever the start comes from. */
    const val BACKLOG_START_REFUSAL =
        "ticket is in the Backlog lane: move it to To Do (the sprint) before starting it"
}

/**
 * A ticket's type: AgentHQ's own set (contract 1.91.0, ORIGIN-SCRUB-1b).
 *
 * The daemon publishes only [ALL]. A [RETIRED] spelling is still accepted on input through [normalize], and a
 * stored row holding one is rewritten once when the database opens (see `TicketTypeMigration`).
 */
object TicketType {
    const val ENHANCEMENT = "enhancement"
    const val DEFECT = "defect"
    const val RESTRUCTURE = "restructure"
    const val UPKEEP = "upkeep"
    const val DOCUMENTATION = "documentation"
    const val COSMETIC = "cosmetic"
    const val PERFORMANCE = "performance"
    const val TESTING = "testing"
    const val HARDENING = "hardening"

    val ALL: List<String> =
        listOf(ENHANCEMENT, DEFECT, RESTRUCTURE, UPKEEP, DOCUMENTATION, COSMETIC, PERFORMANCE, TESTING, HARDENING)

    /** Retired spelling (contract 1.90.0 and earlier) -> its replacement. Read side only: never published again. */
    val RETIRED: Map<String, String> = mapOf(
        "feature" to ENHANCEMENT,
        "bug" to DEFECT,
        "refactor" to RESTRUCTURE,
        "chore" to UPKEEP,
        "docs" to DOCUMENTATION,
        "style" to COSMETIC,
        "perf" to PERFORMANCE,
        "test" to TESTING,
        "security" to HARDENING,
    )

    /** [type] under its current name: a [RETIRED] spelling maps to its replacement, anything else is unchanged. */
    fun normalize(type: String): String = RETIRED[type] ?: type
}

/**
 * Link types. Four are stored ([BLOCKED_BY], [CHILD_OF], [RELATES_TO], [DUPLICATES]); the other four are
 * their reverses, accepted on `ticket_link` / `ticket_unlink` and used when a ticket lists a link from
 * the other side. [DEPENDS_ON] is accepted as a synonym of [BLOCKED_BY].
 */
object TicketLinkType {
    /** "A blocked_by B": A waits for B. While B is not done (or was closed as won't fix), A is held. */
    const val BLOCKED_BY = "blocked_by"
    const val BLOCKS = "blocks"
    const val DEPENDS_ON = "depends_on"

    /** "A child_of B": structure only. It never holds a ticket. */
    const val CHILD_OF = "child_of"
    const val PARENT_OF = "parent_of"

    /** Symmetric: each ticket lists the other as `relates_to`. */
    const val RELATES_TO = "relates_to"

    /** "A duplicates B": A repeats B. */
    const val DUPLICATES = "duplicates"
    const val DUPLICATED_BY = "duplicated_by"

    val ALL: List<String> = listOf(BLOCKED_BY, BLOCKS, DEPENDS_ON, CHILD_OF, PARENT_OF, RELATES_TO, DUPLICATES, DUPLICATED_BY)
}

/** One link, seen from the ticket that lists it: "this ticket [type] [ticketId]". */
@Serializable
data class TicketLink(
    val type: String,
    val ticketId: String,
)

/**
 * One ticket, as the daemon holds it: the answer to `ticket_save`, `ticket_get`, `ticket_move`,
 * `ticket_link` and `ticket_unlink`, the frame pushed to every client when a ticket changes, and one
 * element of `tickets`.
 *
 * - [status]: one of [TicketStatus.ALL]. [position] is its place in that column, 0 at the top; positions
 *   are contiguous per column.
 * - [ticketType]: one of [TicketType.ALL], or null for an untyped ticket.
 * - [wontFix]: true only on a `done` ticket closed as won't fix. Such a ticket does NOT release the
 *   tickets it blocks.
 * - [links]: every link naming this ticket, from this ticket's side (a link stored as "A blocked_by B"
 *   is listed by B as `blocks` A), oldest first.
 * - [heldBy]: the `blocked_by` prerequisites that hold this ticket right now (not done, or done as won't
 *   fix), and [holdReason] says why in words; empty and null when nothing holds it. A held ticket that
 *   was not started is in `pending`.
 * - [updatedAt]: when a client last changed the ticket itself (content, column, won't-fix, a place it
 *   asked for, or its executor pins), or the daemon moved it between `todo` and `pending`. Shifting
 *   because another ticket moved, and links, do not count.
 *
 * Story 9.3 (contract 1.6.0) adds the executor pins and the daemon's derived views:
 * - [agentId]: a profile id or `role:<role>`, or null when unassigned. A ticket created without one is
 *   assigned `role:<role of the first profile>`; an explicit empty string leaves it unassigned.
 * - [provider] / [model] / [useWorktree] / [worktreeBranch] / [includeLinkedTasksContext]: the ticket's
 *   own pins. Null = inherit (the profile, the project setting, or the daemon default). `useWorktree`
 *   null follows the daemon (own worktree iff a repo is configured), true requires one, false shares.
 * - [queuedFor] / [queuedAt]: the lane this ready-but-unrunnable ticket waits on (a profile id or a
 *   `role:<role>`) and when it was queued. Cleared when the ticket leaves `todo`, its [agentId] changes
 *   or it is deleted.
 * - [sessionId]: the session the daemon started for this ticket, once one has.
 * - [executorOverrides] / [effectiveExecutor]: what the ticket itself pins, and what will actually run
 *   with the SOURCE of each value (`ticket`, `profile`, `role` or `default`).
 * - [effectiveContext]: each context switch with its source (`ticket`, `project` or `default`).
 * - [lastRun]: the outcome of the most recent run for this ticket, when there was one. The ticket stays
 *   `in_progress` after a run; the backlog never restarts a failed one.
 * - [start]: present only on the answer to a start (a `ticket_move` to `in_progress`, a
 *   `ticket_start_next`, or a queue drain): [TicketStart].
 *
 * MVP Sprint-Zero adds [projectId]: the key a ticket uses to name the project it belongs to
 * (slice 0 of ticket b2fbb94d freezes it; MVP slice A, branch feature-agenthq-mvp-scope,
 * implements it). Null = unassigned to a project; the daemon stores and echoes whatever project
 * id the ticket carries, so an MVP board can draw a boundary around part of the backlog. The key
 * is Project-oriented on purpose: `Story.projectId` spells the same join, and the ordered list of
 * a story's siblings lives on `Epic.storyIds` (RETIRED, contract 1.29.0: this used to be
 * `Project.storyIds`, maintained by I0).
 */
@Serializable
data class Ticket(
    val id: String,
    val title: String,
    val description: String = "",
    val ticketType: String? = null,
    /** MVP Sprint-Zero: the project this ticket belongs to (slice A implements). Null = no project. */
    val projectId: String? = null,
    /** CLI-T3 patch (675e7c4c): the epic (full id) this ticket is filed under; null = none. */
    val epicId: String? = null,
    val status: String,
    val position: Int,
    val wontFix: Boolean = false,
    val links: List<TicketLink> = emptyList(),
    val heldBy: List<String> = emptyList(),
    val holdReason: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val agentId: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val useWorktree: Boolean? = null,
    val worktreeBranch: String? = null,
    val includeLinkedTasksContext: Boolean? = null,
    val queuedFor: String? = null,
    val queuedAt: Long? = null,
    val sessionId: String? = null,
    val executorOverrides: ExecutorOverrides? = null,
    val effectiveExecutor: EffectiveExecutor? = null,
    val effectiveContext: EffectiveContext? = null,
    val lastRun: TicketRun? = null,
    val start: TicketStart? = null,
    /** BRANCH-PER-TASK 1/3: the ticket's own branch (`ticket/<short-id>-<slug>`), once a start cut it. */
    val branch: String? = null,
    /** BRANCH-PER-TASK 1/3: the commit that branch was cut from, once a start cut it. */
    val base: String? = null,
    /** P10-K11 (contract 1.55.0): the skill ids this ticket attaches, in attach order. */
    val skillIds: List<String> = emptyList(),
    /** P10-K11: `merge` (after the agent's and the project's ids) or `replace` (only the ticket's ids apply). */
    val skillsMode: String = SkillsMode.MERGE,
    /** P10-K11: the ticket's skills-section switch; null inherits (see [EffectiveContext.includeSkillsContext]). */
    val includeSkillsContext: Boolean? = null,
    /**
     * IMG-3 (contract 1.84.0): the ticket's images, in attach order: each the relPath an `image_upload` with purpose
     * `ticket` answered (`.agenthq/uploads/ticket/<sha256>.<ext>`, relative to the ticket's project directory). A
     * started worker's brief lists each one's absolute path under "Attached images". Empty from an older daemon.
     */
    val images: List<String> = emptyList(),
    /**
     * TICKET-WEIGHT (contract 1.103.0): 1..[TicketWeight.MAX], or null = unweighted (the default). A weighted ticket
     * with no executor pin of its own is started on the project's weight mapping (`tickets.weightExecutors`); the
     * source of the chosen provider / model is then `weight` in [effectiveExecutor].
     */
    val weight: Int? = null,
    /**
     * CONTEXT-LIMIT-1 (contract 1.146.0): the worker's context limit in tokens, or null = none (the harness's own window).
     * Applied through the worker's harness; [effectiveContextLimit] says the value, its source and how that harness honours it.
     */
    val contextLimit: Int? = null,
    /** CONTEXT-LIMIT-1: the context limit a start would use, like [effectiveExecutor]; null only from an older daemon. */
    val effectiveContextLimit: EffectiveContextLimit? = null,
    /** EFFORT-1 (contract 1.148.0): the worker's reasoning effort (one of low / medium / high / xhigh / max), or null = the harness default. */
    val effort: String? = null,
    /** EFFORT-1: the effort a start would use with its source and how the effective harness honours it; null only from an older daemon. */
    val effectiveEffort: EffectiveEffort? = null,
)

/**
 * EFFORT-1: a ticket's effective reasoning effort. [value] null = the harness default; [source] `ticket` (the ticket pins it),
 * `agent` (the agent profile's), `role` (the role binding's) or `default`. [support] / [how] say how the ticket's effective
 * harness honours an effort (`supported` / `best_effort` / `unsupported` = recorded, not applied); null when not configured here.
 */
@Serializable
data class EffectiveEffort(
    val value: String? = null,
    val source: String,
    val support: String? = null,
    val how: String? = null,
)

/**
 * CONTEXT-LIMIT-1: a ticket's effective context limit. [value] null = none; [source] `ticket` (the ticket pins it) or `default`
 * (no limit). [support] is how the ticket's effective harness honours a limit (`supported` / `best_effort` / `unsupported`,
 * unsupported = recorded, not applied) and [how] says it in words; both null when the harness is not configured here.
 */
@Serializable
data class EffectiveContextLimit(
    val value: Int? = null,
    val source: String,
    val support: String? = null,
    val how: String? = null,
)

/** CONTEXT-LIMIT-1: on `ticket_save`, [CLEAR] removes a ticket's context limit (absent or null keeps it). */
object TicketContextLimit {
    const val CLEAR = 0
}

/** TICKET-WEIGHT: the weight scale; 1 = lightest. On `ticket_save`, [CLEAR] removes a ticket's weight. */
object TicketWeight {
    const val MIN = 1
    const val MAX = 5
    const val CLEAR = 0
    val RANGE = MIN..MAX
}

/** P10-K11: the two values of [Ticket.skillsMode] / [TicketSave.skillsMode]. */
object SkillsMode {
    const val MERGE = "merge"
    const val REPLACE = "replace"
    val ALL = listOf(MERGE, REPLACE)
}

/**
 * What a ticket itself pins for its executor, for [Ticket.executorOverrides]. Null fields inherit.
 * The same two fields also travel on `ticket_save` as direct ticket fields ([Ticket.provider],
 * [Ticket.model]); this view exists so a UI reads the pins off one object, the way a board UI expects it.
 */
@Serializable
data class ExecutorOverrides(
    val provider: String? = null,
    val model: String? = null,
)

/**
 * One resolved executor value and WHERE it came from. [value] is null only when the source itself
 * carries no value (a `role:<role>` ticket whose profiles pin no provider: source `role`, value null).
 */
@Serializable
data class ExecutorField(
    val value: String? = null,
    val source: String,
)

/**
 * The executor a ticket will actually run on ([Ticket.effectiveExecutor]): the resolved provider and
 * model with their sources, plus the profile and role the ticket resolves to.
 */
@Serializable
data class EffectiveExecutor(
    val agentId: String? = null,
    val role: String? = null,
    val provider: ExecutorField? = null,
    val model: ExecutorField? = null,
    /** Contract 1.128.0 (ROUTING-1): the name of the project routing rule that picked the executor (source `rule`); null when none did. */
    val rule: String? = null,
)

/** One context switch's effective [value] and WHERE it came from (`ticket`, `project` or `default`). */
@Serializable
data class ContextSwitch(
    val value: Boolean,
    val source: String,
)

/**
 * The effective context switches ([Ticket.effectiveContext]): each field with its source.
 * P10-K11 (contract 1.55.0) adds [includeSkillsContext], resolved the same way as [includeLinkedTasksContext]
 * (ticket, then the `context.includeSkillsContext` setting, then the default true). Null only from a daemon
 * older than 1.55.0.
 */
@Serializable
data class EffectiveContext(
    val includeLinkedTasksContext: ContextSwitch,
    val includeSkillsContext: ContextSwitch? = null,
)

/**
 * The outcome of one run for a ticket, as stored in `ticket_runs` and served as [Ticket.lastRun].
 * [outcome] is the name of a [FinishedPayload.Outcome] (`SUCCEEDED`, `FAILED`, `KILLED_GRACEFUL`,
 * `KILLED_FORCED` or `ORPHANED`); [endedAt] is null while the run is in flight.
 */
@Serializable
data class TicketRun(
    val sessionId: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val outcome: String? = null,
    val detail: String? = null,
)

/**
 * The answer to a start, carried on the `ticket` frame as [Ticket.start]:
 *  - [ok] true only for [reason] `started`; every other reason is a refusal or a deferral.
 *  - [reason] one of `started`, `queued`, `held`, `start_refused`. (`launch_blocked` is reserved for
 *    story 9.7 and is never produced in 1.6.0.)
 *  - [detail] why, in words: for `held` the open prerequisite ids, for `queued` the lane, for
 *    `start_refused` the cause (`no repo configured`, an unknown profile, ...). For `started`, what the
 *    ticket's branch was cut from (`cut from <ref> <short sha>`, BUG-REGRESS-1; absent when the branch was
 *    reused), then any launch warning ([LAUNCH_WARNING]...), joined by `; `; null when there is neither.
 */
@Serializable
data class TicketStart(
    val ok: Boolean,
    val reason: String,
    val detail: String? = null,
) {
    companion object {
        /** How a launch warning in a started ticket's [detail] begins: advice, the run started anyway. */
        const val LAUNCH_WARNING = "launch warning: "

        /** GAP-006b: the [reason] of a start of an in-progress ticket whose worker can carry it: nothing was started. */
        const val ALREADY_IN_PROGRESS = "already_in_progress"

        /** GAP-006b: how a restart's [detail] begins, naming why the old worker could not carry the ticket. */
        const val RESTARTED = "restarted: "
    }
}

/**
 * `ticket_save`: create a ticket (no [id]) or edit one (the [id] of an existing ticket; an unknown id is
 * refused, so an edit racing a delete never brings the ticket back).
 *
 * On a create, [title] is required and the ticket enters `todo` (or [status] `backlog`) at the bottom. On an edit, a null field
 * is left as it is, and an empty [description] or [ticketType] clears it. Columns, positions and links
 * have their own frames (`ticket_move`, `ticket_reorder`, `ticket_link`).
 *
 * Story 9.3: [agentId] (a profile id or `role:<role>`), the executor pins [provider] / [model] /
 * [useWorktree] / [worktreeBranch], and the context override [includeLinkedTasksContext] are set here.
 * On a create with no [agentId] the daemon assigns `role:<role of the first profile>`; an explicit
 * empty string leaves the ticket unassigned.
 *
 * MVP Sprint-Zero: [projectId] names the project the ticket belongs to (slice A implements the
 * storage; the key is frozen here so both MVP slices and clients compile against one seam).
 */
@Serializable
data class TicketSave(
    val id: String? = null,
    val title: String? = null,
    val description: String? = null,
    val ticketType: String? = null,
    val projectId: String? = null,
    /** CLI-T3 patch (675e7c4c): the epic to file the ticket under. Absent or null keeps the current one; M2-17 (1.60.0): empty clears it. */
    val epicId: String? = null,
    val agentId: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val useWorktree: Boolean? = null,
    val worktreeBranch: String? = null,
    val includeLinkedTasksContext: Boolean? = null,
    /**
     * P10-K11 (contract 1.55.0): absent or null keeps the stored list, `[]` clears it. Every id must name an existing
     * skill and appear once, or the whole save is refused.
     */
    val skillIds: List<String>? = null,
    /** P10-K11: `merge` or `replace`; absent or null keeps the stored mode (a new ticket gets `merge`). */
    val skillsMode: String? = null,
    /** P10-K11: the ticket's skills-section switch; absent or null keeps it, like [includeLinkedTasksContext]. */
    val includeSkillsContext: Boolean? = null,
    /**
     * IMG-3 (contract 1.84.0): absent or null keeps the stored images, `[]` clears them. Each entry is a relPath or an
     * absolute path naming an existing file directly under the ticket's project's `.agenthq/uploads/ticket/`; anything
     * else refuses the whole save. Stored as relPaths, duplicates dropped. An image this save removes is deleted from
     * disk once no ticket references it.
     */
    val images: List<String>? = null,
    /** TICKET-WEIGHT (contract 1.103.0): 1..5 sets the weight, [TicketWeight.CLEAR] (0) removes it, absent or null keeps it. */
    val weight: Int? = null,
    /**
     * CONTEXT-LIMIT-1 (contract 1.146.0): a context limit in tokens (10000..10000000) sets it, [TicketContextLimit.CLEAR] (0)
     * removes it, absent or null keeps it. Out of range refuses the save.
     */
    val contextLimit: Int? = null,
    /** EFFORT-1 (contract 1.148.0): a level sets the worker's reasoning effort, "" / `off` / `default` removes it, absent or null keeps it. An unknown level refuses the save. */
    val effort: String? = null,
    /**
     * BACKLOG (contract 1.131.0): on a CREATE only, the column the new ticket enters: [TicketStatus.TODO] (absent or
     * null, the default) or [TicketStatus.BACKLOG]; any other column is refused. On an edit it must be absent: columns
     * change over `ticket_move`.
     */
    val status: String? = null,
) {
    companion object {
        fun decode(text: String): TicketSave = StrictJson.decode(serializer(), text, "ticket_save payload")
    }
}

/**
 * `ticket_list`'s filters. The data is either empty (the whole board), a bare project id (UI-BOARD-PROJ),
 * or, since contract 1.66.0 (E1-b), this JSON object. A blank or absent field is no filter; both given
 * means both must match. An epic no ticket is filed under answers an empty board, never an error.
 */
@Serializable
data class TicketListQuery(
    val projectId: String? = null,
    val epicId: String? = null,
) {
    companion object {
        /** Reads [text] as a query: a JSON object when it starts with `{`, otherwise a bare project id. */
        fun decode(text: String): TicketListQuery {
            val trimmed = text.trim()
            val raw = if (trimmed.startsWith("{")) {
                StrictJson.decode(serializer(), trimmed, "ticket_list payload")
            } else {
                TicketListQuery(projectId = trimmed)
            }
            return TicketListQuery(
                projectId = raw.projectId?.trim()?.takeIf { it.isNotEmpty() },
                epicId = raw.epicId?.trim()?.takeIf { it.isNotEmpty() },
            )
        }
    }
}

/**
 * `ticket_move`: put ticket [id] in column [status] (null = stay in its column) at [position] (null = the
 * bottom of a new column, or where it is when it stays). [wontFix] true closes it as won't fix and needs
 * [status] `done` (or a ticket already done); leaving `done` clears it.
 */
@Serializable
data class TicketMove(
    val id: String,
    val status: String? = null,
    val position: Int? = null,
    val wontFix: Boolean? = null,
) {
    companion object {
        fun decode(text: String): TicketMove = StrictJson.decode(serializer(), text, "ticket_move payload")
    }
}

/**
 * `ticket_link` / `ticket_unlink`: "[ticketId] [type] [relatedId]", [type] one of [TicketLinkType.ALL].
 * Answered with [ticketId]'s ticket.
 */
@Serializable
data class TicketLinkRequest(
    val ticketId: String,
    val relatedId: String,
    val type: String,
) {
    companion object {
        fun decode(text: String, frame: String): TicketLinkRequest = StrictJson.decode(serializer(), text, "$frame payload")
    }
}

/**
 * `ticket_reorder`: the tickets in [ids] go to the top of their column in that order, and every other
 * ticket of the column keeps its relative order below them. With [status], every id must be in that
 * column. A reorder never moves a ticket to another column.
 */
@Serializable
data class TicketReorder(
    val status: String? = null,
    val ids: List<String>,
) {
    companion object {
        fun decode(text: String): TicketReorder = StrictJson.decode(serializer(), text, "ticket_reorder payload")
    }
}

/**
 * `settings_set` (contract 1.6.0): `{"key": <key>, "value": <json>}`. The key set is closed --
 * `context.includeLinkedTasksContext` (boolean), and since 1.55.0 (P10-K11) `context.includeSkillsContext` (boolean)
 * and `context.skillIds` (array of existing skill ids, no duplicates) -- and the value's JSON type must match the
 * key's, so an unknown key, a wrong type or an unknown skill id is refused. Answered with the whole `settings` object, and the change is
 * durable across a restart.
 *
 * `settings_get` takes a bare key (empty = every setting) and is answered the same way.
 */
@Serializable
data class SettingsSet(
    val key: String,
    val value: JsonElement,
    /** SETTINGS-SCOPES-1: `app` (default) | `project` | `role` | `session`; the ids below pick the target. A JSON null value clears the key at that scope. */
    val scope: String? = null,
    val project: String? = null,
    val role: String? = null,
    val session: String? = null,
) {
    companion object {
        fun decode(text: String): SettingsSet = StrictJson.decode(serializer(), text, "settings_set payload")
    }
}
