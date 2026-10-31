package com.superiorsoftllc.agenthq.daemon.api.board

/** DP-5h-3a2 (DaemonApi 1.13.0, additive): KNOWLEDGE-PTR-1 (C): a ticket as a save or link is about to store it, with the board rows and links. */
data class BoardRelatedDraft(
    val ticketId: String?,
    val title: String,
    val description: String,
    val projectId: String?,
    val rows: List<StoredTicket>,
    val links: List<StoredLink>,
)

/** DP-5h-3a2 (1.13.0): inputs of the deprecated story frames. */
interface BoardStoryInputs {
    /** An old story id -> the ticket id its migration mapped it to, or null. */
    fun ticketFor(storyId: String): String? = null

    /** A story create named [epicId]: push that epic's frame to every client. */
    fun epicChanged(epicId: String) {}

    /** DP-5h-3e-b (1.14.0): the legacy `stories.json` to fold into the board once at start; null = none. */
    fun migrationFile(): String? = null

    /** DP-5h-3e-b (1.14.0): record that [storyId] became [ticketId] (the `story_migration` table; [ticketFor] reads it back). */
    fun recordMapping(storyId: String, ticketId: String, at: Long) {}

    companion object { val NONE: BoardStoryInputs = object : BoardStoryInputs {} }
}

/** DP-5h-3a2 (1.13.0): inputs of the epic store: persistence as `Epic` JSON (the plugin owns the type), and the project checks. */
interface BoardEpicInputs {
    fun loadAll(): List<String> = emptyList()
    fun upsert(epicJson: String) {}
    fun projectKnown(projectId: String): Boolean = true

    /** `epic_base`: throws [IllegalArgumentException] with the reason when [branch] does not resolve in the epic's project. */
    fun checkBase(projectId: String, branch: String) {}
    fun branchName(name: String): String = name.trim()

    companion object { val NONE: BoardEpicInputs = object : BoardEpicInputs {} }
}

/** DP-5h-3e-b (1.14.0): what core reads of a ticket without any board type. */
data class BoardCoreTicket(val sessionId: String?, val projectId: String?, val status: String)

/**
 * DP-5h-3e-b (DaemonApi 1.14.0, additive; docs/DAEMON-BOARD-API.md section 10): what the BOARD PLUGIN hands back to core once it has built the
 * board, so core's own consumers (start charge, orchestrator link, session reaper, roster titles, handover, first-turn brief, worker sweep,
 * ticket files, epic base branch, epic broadcast) reach it without a board class crossing the plugin's class loader. Plain data only (strings,
 * JSON text of the published wire types, [Map], [List]); a call before [BoardRuntime.attachCore] is answered by core, never by this.
 */
interface BoardCoreAccess {
    fun ticket(id: String): BoardCoreTicket?
    fun starterOf(workerSessionId: String): String?
    fun titlesBySession(): Map<String, String>
    fun brief(sessionId: String): String?

    /** The git checkout a ticket cut for [projectId] runs in (absolute path), null = none. */
    fun cutRepo(projectId: String?): String?

    /** The base branch of an epic (blank / unknown = null). */
    fun epicBaseBranchOf(epicId: String): String?

    /** Epic id to its ticket ids, in board order. */
    fun ticketIdsByEpic(): Map<String, List<String>>

    /** The wire `Epic` as JSON text, null when unknown. */
    fun epicJson(epicId: String): String?

    /** A project's lane caps changed: drain what now fits. */
    fun laneCapsChanged(projectId: String)

    /** Clear the queue marks (a re-adopt); the number cleared. */
    fun clearQueueMarks(): Int

    /** The in-progress worker sweep; the ids it flagged. */
    fun sweepWorkers(graceMs: Long, flag: (ticketId: String, sessionId: String?, detail: String) -> Unit): List<String>

    /** The wire `Ticket` as JSON text (throws the board's refusal when unknown). */
    fun getTicketJson(id: String): String

    /** Run a `ticket_move` (wire `TicketMove` JSON) as [startedBy]; the answer's wire `Ticket` as JSON text. */
    fun moveTicketJson(moveJson: String, startedBy: String?): String

    /**
     * The board operations core's `Tickets` API applies its host policy over, as wire JSON: `list` (TicketListQuery -> [Ticket]), `get` (id -> Ticket),
     * `save` (TicketSave -> Ticket), `move` (TicketMove -> Ticket, [startedBy] recorded), `delete` (id -> ""), `link` / `unlink` (TicketLinkRequest -> Ticket),
     * `reorder` (TicketReorder -> ""), `epicGet` (id -> Epic or ""), `epicList` (projectId -> [Epic]), `epicSave` ([projectId, name, goal] -> Epic), `epicSetBase` ([id, branch or null] -> Epic; the checks are the plugin's), `startNext` (agent filter or "" -> Ticket or ""), `startPrompt` (JSON array [agentId, prompt, projectId or null] ->
     * "S:<sessionId>" or "R:<detail>"). A board refusal is thrown as [IllegalArgumentException] with the board's own text.
     */
    fun invoke(op: String, payload: String, startedBy: String?): String

    /** Daemon shutdown, before the manager: stop draining the queue. */
    fun stopQueueDrain()

    /** Daemon shutdown, after the manager. */
    fun close()
}
