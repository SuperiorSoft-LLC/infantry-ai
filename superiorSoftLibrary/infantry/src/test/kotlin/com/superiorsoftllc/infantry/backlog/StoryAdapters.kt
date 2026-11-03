// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
@file:Suppress("PackageDirectoryMismatch")

package com.superiorsoftllc.infantry.stories

import com.superiorsoftllc.infantry.backlog.TicketBoard
import com.superiorsoftllc.infantry.db.InfantryDatabase
import com.superiorsoftllc.infantry.epics.SqlEpicPersistence
import com.superiorsoftllc.infantry.mvp.StoryTickets
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketSave
import java.io.File

// DP-5g: StoryHandler / StoryMigration live in :infantryBoard and name the ticket board only through ports. This file (in
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
    database: InfantryDatabase,
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
