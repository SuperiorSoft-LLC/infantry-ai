package com.superiorsoftllc.infantry.epics

import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * EPIC-4: `epic_save` / `epic_list`, driven over the real daemon socket like
 * [com.superiorsoftllc.infantry.mvp.WorkspaceDaemonTest].
 */
class EpicDaemonTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun epicSaveFrameIsListedByEpicList() {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)

            // An existing project to own the epic (epic_save refuses an unknown project, so this one
            // must exist).
            fixture.send(Envelope.PROJECT_SAVE, """{"name":"P"}""")
            val projectId = json.parseToJsonElement(fixture.client.await(Envelope.PROJECT, timeoutMs = 4_000).data)
                .jsonObject["id"]!!.jsonPrimitive.content

            // epic_save answers with the whole epic.
            fixture.send(Envelope.EPIC_SAVE, """{"projectId":"$projectId","name":"Epic One","goal":"ship it"}""")
            val saved = json.parseToJsonElement(fixture.client.await(Envelope.EPIC, timeoutMs = 4_000).data).jsonObject
            val epicId = saved["id"]!!.jsonPrimitive.content
            assertTrue(epicId.isNotBlank(), "the daemon chose the id: '$epicId'")
            assertEquals("Epic One", saved["name"]!!.jsonPrimitive.content)
            assertEquals("ship it", saved["goal"]!!.jsonPrimitive.content)
            assertTrue(saved["storyIds"]!!.jsonArray.isEmpty(), "a fresh epic holds no stories")

            // epic_list answers with that epic, its id and its (empty) storyIds.
            fixture.send(Envelope.EPIC_LIST, projectId)
            val listed = json.parseToJsonElement(fixture.client.await(Envelope.EPICS, timeoutMs = 4_000).data)
                .jsonArray
            assertEquals(1, listed.size)
            val epic = listed.first().jsonObject
            assertEquals(epicId, epic["id"]!!.jsonPrimitive.content)
            assertTrue(
                epic["storyIds"]!!.jsonArray.isEmpty(),
                "the listed epic holds its id and an empty storyIds: ${epic["storyIds"]}",
            )
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    /**
     * STORY-EPIC-1 (contract 1.22.0): a `story_save` that names an `epicId` joins that epic -- the
     * story id lands in the epic's ordered `storyIds`, visible through `epic_list`.
     */
    @Test
    fun storySaveWithEpicIdJoinsThatEpic() {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)

            fixture.send(Envelope.PROJECT_SAVE, """{"name":"P"}""")
            val projectId = json.parseToJsonElement(fixture.client.await(Envelope.PROJECT, timeoutMs = 4_000).data)
                .jsonObject["id"]!!.jsonPrimitive.content

            fixture.send(Envelope.EPIC_SAVE, """{"projectId":"$projectId","name":"Epic One"}""")
            val epicId = json.parseToJsonElement(fixture.client.await(Envelope.EPIC, timeoutMs = 4_000).data)
                .jsonObject["id"]!!.jsonPrimitive.content

            // A story created with an epicId joins that epic.
            fixture.send(
                Envelope.STORY_SAVE,
                """{"projectId":"$projectId","title":"Story One","epicId":"$epicId"}""",
            )
            val storyId = json.parseToJsonElement(fixture.client.await(Envelope.STORY, timeoutMs = 4_000).data)
                .jsonObject["id"]!!.jsonPrimitive.content
            assertTrue(storyId.isNotBlank(), "the daemon chose the story id: '$storyId'")

            // epic_list now shows the new story in the epic's storyIds.
            fixture.send(Envelope.EPIC_LIST, projectId)
            val epic = json.parseToJsonElement(fixture.client.await(Envelope.EPICS, timeoutMs = 4_000).data)
                .jsonArray.first().jsonObject
            assertEquals(
                listOf(storyId),
                epic["storyIds"]!!.jsonArray.map { it.jsonPrimitive.content },
                "story_save with an epicId must append the story id to the epic's storyIds",
            )
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
