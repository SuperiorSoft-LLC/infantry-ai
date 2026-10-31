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
