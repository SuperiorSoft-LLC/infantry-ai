// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Settings and effective-context behavior that does not start a session. */
class TicketContextDaemonTest {
    private fun withDaemon(block: (DaemonFixture) -> Unit) {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)
            block(fixture)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    private fun JsonObject.bool(key: String): Boolean = this[key]!!.jsonPrimitive.content.toBoolean()

    @Test
    fun contextLinkedTasksDefaultsTrueAndIsOverridablePerTicketInBothDirections() = withDaemon { fixture ->
        assertTrue(fixture.client.settings().bool("context.includeLinkedTasksContext"))

        fixture.client.saveProfile("""{"id":"backend","name":"Backend","role":"backend"}""")
        val on = fixture.client.saveTicket("""{"title":"On","includeLinkedTasksContext":true}""")
        assertEquals(true, on.effectiveContext!!.includeLinkedTasksContext.value)
        assertEquals("ticket", on.effectiveContext!!.includeLinkedTasksContext.source)

        fixture.client.answerTo(
            Envelope.SETTINGS_SET,
            """{"key":"context.includeLinkedTasksContext","value":false}""",
        )
        assertTrue(!fixture.client.settings("context.includeLinkedTasksContext").bool("context.includeLinkedTasksContext"))

        val inherits = fixture.client.saveTicket("""{"title":"Inherits"}""")
        assertEquals(false, inherits.effectiveContext!!.includeLinkedTasksContext.value)
        assertEquals("project", inherits.effectiveContext!!.includeLinkedTasksContext.source)

        val pinnedOn = fixture.client.saveTicket("""{"title":"Pinned on","includeLinkedTasksContext":true}""")
        assertEquals(true, pinnedOn.effectiveContext!!.includeLinkedTasksContext.value)
        assertEquals("ticket", pinnedOn.effectiveContext!!.includeLinkedTasksContext.source)
    }

    @Test
    fun anUnknownSettingKeyOrAWrongTypeIsRefused() = withDaemon { fixture ->
        val unknown = fixture.client.answerTo(Envelope.SETTINGS_SET, """{"key":"context.nope","value":true}""")
        assertEquals(Envelope.ERROR, unknown.type)
        assertTrue(unknown.data.contains("context.nope"), unknown.data)

        val wrongType = fixture.client.answerTo(
            Envelope.SETTINGS_SET,
            """{"key":"context.includeLinkedTasksContext","value":"yes"}""",
        )
        assertEquals(Envelope.ERROR, wrongType.type)
        assertTrue(wrongType.data.contains("context.includeLinkedTasksContext"), wrongType.data)
        assertTrue(fixture.sqlStore.settings.all().isEmpty(), "refused writes do not persist settings")
    }

    @Test
    fun aSettingUpdateAnswersOnlyTheRequesterAndTicketViewsResolveOnRead() = withDaemon { fixture ->
        val existing = fixture.client.saveTicket("""{"title":"Existing ticket"}""")
        val observer = fixture.connectClient()
        Thread.sleep(100)
        val requesterBefore = fixture.client.frames.size
        val observerBefore = observer.frames.size

        val response = fixture.client.answerTo(
            Envelope.SETTINGS_SET,
            """{"key":"context.includeLinkedTasksContext","value":false}""",
        )
        assertEquals(Envelope.SETTINGS, response.type)
        Thread.sleep(100)

        val requesterFrames = fixture.client.frames.drop(requesterBefore)
        assertEquals(listOf(Envelope.SETTINGS), requesterFrames.map { it.type }, "only the requester gets the settings answer")
        assertEquals(observerBefore, observer.frames.size, "other clients get no setting or ticket push")

        val viewed = fixture.client.board().single { it.id == existing.id }
        assertFalse(viewed.effectiveContext!!.includeLinkedTasksContext.value)
        assertEquals("project", viewed.effectiveContext!!.includeLinkedTasksContext.source)
    }

    @Test
    fun aSettingSurvivesARestart() {
        val stateDir = java.nio.file.Files.createTempDirectory("infantry-settings-restart").toFile()
        val first = DaemonFixture.launch(stateDir = stateDir)
        try {
            first.client.await(Envelope.READY)
            first.client.answerTo(
                Envelope.SETTINGS_SET,
                """{"key":"context.includeLinkedTasksContext","value":false}""",
            )
        } finally {
            first.stop()
        }

        val second = DaemonFixture.launch(stateDir = stateDir)
        try {
            second.client.await(Envelope.READY)
            assertTrue(
                !second.client.settings("context.includeLinkedTasksContext")
                    .bool("context.includeLinkedTasksContext"),
                "the setting is durable across a restart",
            )
        } finally {
            second.stop()
            second.deleteState()
        }
    }
}
