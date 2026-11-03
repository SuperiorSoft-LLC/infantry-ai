// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mvp

import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.Story
import com.superiorsoftllc.infantry.protocol.StoryStatus
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.protocol.TicketStatus
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * E1-c (contract 1.62.0): the story frames are deprecated shims over the ticket board, and an epic's `storyIds`
 * are derived from the tickets filed under it. Driven over the real daemon socket.
 */
class StoryShimDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val timeoutMs = 4_000L

    private fun DaemonFixture.story(description: String, match: (Story) -> Boolean): Story =
        json.decodeFromString(
            Story.serializer(),
            client.awaitWhere(description, timeoutMs) { e ->
                e.type == Envelope.STORY && runCatching { json.decodeFromString(Story.serializer(), e.data) }.getOrNull()?.let(match) == true
            }.data,
        )

    private fun DaemonFixture.board(description: String, match: (List<Ticket>) -> Boolean): List<Ticket> =
        json.decodeFromString(
            ListSerializer(Ticket.serializer()),
            client.awaitWhere(description, timeoutMs) { e ->
                e.type == Envelope.TICKETS &&
                    runCatching { json.decodeFromString(ListSerializer(Ticket.serializer()), e.data) }.getOrNull()?.let(match) == true
            }.data,
        )

    private fun DaemonFixture.idOf(type: String): String =
        json.parseToJsonElement(client.await(type, timeoutMs = timeoutMs).data).jsonObject["id"]!!.jsonPrimitive.content

    @Test
    fun storySaveWithAnEpicCreatesATicketTheEpicListsAndStoryMoveDoingMakesItInProgress() {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope.PROJECT_SAVE, """{"name":"P"}""")
            val projectId = fixture.idOf(Envelope.PROJECT)
            fixture.send(Envelope.EPIC_SAVE, """{"projectId":"$projectId","name":"Epic One"}""")
            val epicId = fixture.idOf(Envelope.EPIC)

            fixture.send(Envelope.STORY_SAVE, """{"projectId":"$projectId","title":"Shim story","epicId":"$epicId"}""")
            val story = fixture.story("story_save answer") { it.title == "Shim story" }

            // A TICKET, visible on `tickets`, filed under the epic.
            fixture.send(Envelope.TICKET_LIST, "")
            val tickets = fixture.board("tickets listing the shim's ticket") { list -> list.any { it.title == "Shim story" } }
            val ticket = tickets.single { it.title == "Shim story" }
            assertEquals(story.id, ticket.id, "the story id IS the ticket id")
            assertEquals(epicId, ticket.epicId)
            assertEquals(projectId, ticket.projectId)
            assertEquals(TicketStatus.TODO, ticket.status)

            // The epic frame lists the ticket's id.
            fixture.send(Envelope.EPIC_LIST, projectId)
            val epic = json.parseToJsonElement(fixture.client.await(Envelope.EPICS, timeoutMs = timeoutMs).data).jsonArray.single().jsonObject
            assertEquals(listOf(ticket.id), epic["storyIds"]!!.jsonArray.map { it.jsonPrimitive.content })

            // story_move doing -> the ticket is in_progress (a plain column change, no run).
            fixture.send(Envelope.STORY_MOVE, """{"id":"${story.id}","status":"doing"}""")
            val doing = fixture.story("story_move answer in doing") { it.id == story.id && it.status == StoryStatus.DOING }
            assertEquals(StoryStatus.DOING, doing.status)
            fixture.send(Envelope.TICKET_LIST, "")
            fixture.board("tickets with the shim's ticket in_progress") { list ->
                list.any { it.id == ticket.id && it.status == TicketStatus.IN_PROGRESS }
            }
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun storyGetOfAnOldStoryIdResolvesThroughStoryMigration() {
        val stateDir = Files.createTempDirectory("infantry-story-shim").toFile()
        val stories = File(stateDir, "stories.json")
        stories.writeText(
            """{"version":1,"stories":[{"id":"story-old-1","projectId":"project-x","title":"Old story","status":"doing"}]}""",
        )
        val fixture = DaemonFixture.launch(stateDir = stateDir, storyMigrationFile = stories)
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope.STORY_GET, "story-old-1")
            val got = fixture.story("story_get of the old id") { it.title == "Old story" }
            assertNotEquals("story-old-1", got.id, "the answer names the ticket the story became")
            assertEquals(StoryStatus.DOING, got.status)
            assertEquals("project-x", got.projectId)

            fixture.send(Envelope.TICKET_LIST, "")
            fixture.board("tickets holding the migrated story") { list ->
                list.any { it.id == got.id && it.status == TicketStatus.IN_PROGRESS }
            }

            fixture.send(Envelope.STORY_GET, "story-never-was")
            fixture.client.awaitWhere("story_get refusal of an unknown id", timeoutMs) { e ->
                e.type == Envelope.ERROR && e.data.contains("story_get refused") && e.data.contains("story-never-was")
            }
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
