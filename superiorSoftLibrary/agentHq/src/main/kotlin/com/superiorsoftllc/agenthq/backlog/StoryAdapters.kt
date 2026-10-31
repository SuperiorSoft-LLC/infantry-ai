@file:Suppress("PackageDirectoryMismatch")

package com.superiorsoftllc.agenthq.stories

import com.superiorsoftllc.agenthq.backlog.TicketBoard
import com.superiorsoftllc.agenthq.db.AgentHQDatabase
import com.superiorsoftllc.agenthq.epics.SqlEpicPersistence
import com.superiorsoftllc.agenthq.mvp.StoryTickets
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketSave
import java.io.File

// DP-5g: StoryHandler / StoryMigration live in :agentHqBoard and name the ticket board only through ports. This file (in
// backlog/, so BoardImportGuardTest's ratchet counts it with the board) adapts the core TicketBoard and database to them.

/** The story shims' view of [TicketBoard]. */
fun TicketBoard.asStoryTickets(): StoryTickets = object : StoryTickets {
    override fun get(id: String): Ticket = this@asStoryTickets.get(id)
    override fun save(request: TicketSave): Ticket = this@asStoryTickets.save(request).ticket
    override fun moveWithoutStart(request: TicketMove): Ticket = this@asStoryTickets.move(request, startOnInProgress = false).ticket
}

/** Builds the E1-a story migration over the core board and database (same call shape the class had before the move). */
fun StoryMigration(
    file: File,
    board: TicketBoard,
    database: AgentHQDatabase,
    clock: () -> Long = System::currentTimeMillis,
    afterStory: (storyId: String) -> Unit = {},
): StoryMigration {
    val queries = database.story_migrationQueries
    return StoryMigration(
        file = file,
        board = StoryMigration.StoryImport { title, status, projectId, epicId, description, inTx ->
            board.import(title, status, projectId, epicId, description, inTx)
        },
        mappings = object : StoryMigration.StoryMappings {
            override fun ticketFor(storyId: String): String? = queries.ticketFor(storyId).executeAsOneOrNull()
            override fun insert(storyId: String, ticketId: String, at: Long) { queries.insertMapping(storyId, ticketId, at) }
        },
        epics = { SqlEpicPersistence(database).loadAll() },
        clock = clock,
        afterStory = afterStory,
    )
}
