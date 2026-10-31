package com.superiorsoftllc.agenthq.daemon.api.board

/**
 * DP-5h-3a2 (API 1.12.0, additive): everything a board plugin needs from the daemon to BUILD the ticket board itself, in
 * daemon-API types only (the plugin carries the board classes; its class loader hides the daemon's). A daemon that hosts the
 * board in core, or has none, answers null from `DaemonApi.boardRuntime`; the plugin then builds nothing and its frames stay
 * `<type> refused: this daemon has no board`. Official / core tier only (a community plugin gets null).
 */
interface BoardRuntime {
    /** The board's host: store, branches, sessions, agents, run health, context, images and the event appender (docs/DAEMON-BOARD-API.md). */
    val host: BoardHost

    /** The settings-driven knobs the board reads at every start / run end / view. */
    val tuning: BoardTuning

    /** The typed ticket event stream (triggers, done-worker reaper, ticket-files consume it; so may the plugin). */
    val ticketEvents: TicketEvents get() = NoTicketEvents

    /** The story-shim inputs (the deprecated story frames over the ticket board). */
    val stories: BoardStoryInputs get() = BoardStoryInputs.NONE

    /** The epic store's persistence and checks. */
    val epics: BoardEpicInputs get() = BoardEpicInputs.NONE
}

/** A start's plan-meter verdict (USAGE-PACE): [over] = refused with [reason]; otherwise allowed, with an optional [warning]. */
data class BoardPacing(val over: Boolean = false, val reason: String? = null, val warning: String? = null)

/** The daemon's settings and registries as functions, read per use, never cached by the board. */
interface BoardTuning {
    /** `tickets.exitBehavior`: `leave` (default) or `done`. */
    fun exitBehavior(): String = "leave"

    /** `limits.tickets.autoDoneMinOutputs`. */
    fun autoDoneMinOutputs(): Long = 1

    /** The role ids a ticket may name (the live catalog); empty = the board's canonical list. */
    fun roleIds(): List<String> = emptyList()

    /** `tickets.weightExecutors` as stored JSON, or null for the default. */
    fun weightExecutorsJson(): String? = null

    /** `tickets.weightContext` as stored JSON, or null for the default. */
    fun weightContextJson(): String? = null

    /** The most agents of [role] that may run at once in [projectId]. */
    fun laneCap(projectId: String?, role: String): Int = 1

    /** The project's routing rules as stored JSON (a list of rules), or null for none. */
    fun routingRulesJson(projectId: String?): String? = null

    /** A rule-driven start was refused: raise it (an attention item). */
    fun routingRefused(ticketId: String, projectId: String?, detail: String) {}

    /** Whether one more worker may start on the plan meter. */
    fun pacing(providerId: String?, model: String?): BoardPacing = BoardPacing()

    /** Whether agent profiles exist (a ticket start composes a prompt from one). */
    fun profilesAvailable(): Boolean = true

    /** Of [ids], the skill ids no stored skill carries (a save naming one is refused). */
    fun unknownSkills(ids: List<String>): List<String> = ids

    /** The RELATED (auto) block to store atop a description; null = keep as given. */
    fun relatedDescription(draft: BoardRelatedDraft): String? = null

    companion object {
        val DEFAULT: BoardTuning = object : BoardTuning {}
    }
}

/** KNOWLEDGE-PTR-1 (C): a ticket as a save or link is about to store it, with the board rows and links. */
data class BoardRelatedDraft(
    val ticketId: String?,
    val title: String,
    val description: String,
    val projectId: String?,
    val rows: List<StoredTicket>,
    val links: List<StoredLink>,
)

/** Inputs of the deprecated story frames. */
interface BoardStoryInputs {
    /** An old story id -> the ticket id its migration mapped it to, or null. */
    fun ticketFor(storyId: String): String? = null

    /** A story create named [epicId]: push that epic's frame to every client. */
    fun epicChanged(epicId: String) {}

    companion object { val NONE: BoardStoryInputs = object : BoardStoryInputs {} }
}

/** Inputs of the epic store: persistence as `Epic` JSON (the plugin owns the type), and the project checks. */
interface BoardEpicInputs {
    /** Every stored epic as JSON. */
    fun loadAll(): List<String> = emptyList()

    /** Insert or replace one epic (JSON). */
    fun upsert(epicJson: String) {}

    /** Whether [projectId] is a known project. */
    fun projectKnown(projectId: String): Boolean = true

    /** `epic_base`: throws [IllegalArgumentException] with the reason when [branch] does not resolve in the epic's project. */
    fun checkBase(projectId: String, branch: String) {}

    /** Validates and normalises a base-branch name. */
    fun branchName(name: String): String = name.trim()

    companion object { val NONE: BoardEpicInputs = object : BoardEpicInputs {} }
}
