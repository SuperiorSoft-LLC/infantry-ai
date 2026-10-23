package com.superiorsoftllc.agenthq.notify

import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotifyHandlerTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val pushed = mutableListOf<Envelope>()
    private val handler = NotifyHandler(
        projectName = { if (it == "p1") "Alpha" else error("unknown") },
        push = { pushed += it }, clock = { 42L }, idFactory = { "n1" },
    )

    private fun ask(req: NotifyRequest) =
        handler.answer(Envelope(Envelope.NOTIFY, json.encodeToString(NotifyRequest.serializer(), req)))

    @Test fun pushesASpokenFrameWithTheProjectName() {
        assertNull(ask(NotifyRequest("  I need a\nrestart  ", "urgent", "p1", "t1")))
        val frame = pushed.single()
        assertEquals(Envelope.SPOKEN, frame.type)
        assertEquals(
            SpokenNotice("n1", 42L, "I need a restart", "urgent", "p1", "Alpha", "t1"),
            json.decodeFromString(SpokenNotice.serializer(), frame.data),
        )
    }

    @Test fun unknownProjectStillSpeaksWithoutAName() {
        ask(NotifyRequest("hi", projectId = "gone"))
        assertNull(json.decodeFromString(SpokenNotice.serializer(), pushed.single().data).projectName)
    }

    @Test fun refusesBlankTextAndUnknownLevel() {
        assertTrue(ask(NotifyRequest("   "))!!.data.startsWith("notify refused"))
        assertTrue(ask(NotifyRequest("x", "loud"))!!.data.startsWith("notify refused"))
        assertTrue(pushed.isEmpty())
    }

    @Test fun capsTheText() {
        ask(NotifyRequest("a".repeat(500)))
        assertEquals(NotifyHandler.MAX_SAY, json.decodeFromString(SpokenNotice.serializer(), pushed.single().data).say.length)
    }
}
