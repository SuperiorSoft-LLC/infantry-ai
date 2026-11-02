package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.mailbox.FakeAgentProvider
import com.superiorsoftllc.agenthq.mailbox.ScriptedRunner
import com.superiorsoftllc.agenthq.mailbox.tempSessionStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** AR-3a: the first turn is composed from an ordered provider list over the session's binding. */
class FirstTurnProvidersTest {
    private val dir = Files.createTempDirectory("ft-providers").toFile()
    private fun manager() = SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = false), tempSessionStore(), defaultWorkingDir = dir, bindingPolicy = com.superiorsoftllc.agenthq.daemon.plugins.TicketBindingPolicy())
    private fun record(ticketId: String?) = SessionRecord(
        id = "s1", provider = "fake", workingDir = dir.path, spawnedAt = 0, lastHeartbeat = 0, state = SessionState.IDLE, ticketId = ticketId,
    )

    @Test
    fun sectionsJoinInRegistrationOrderAndFailuresAreSkipped() {
        val m = manager()
        m.addFirstTurnProvider { id, b -> "A:$id:${b.kind}:${b.id}" }
        m.addFirstTurnProvider { _, _ -> error("boom") }
        m.addFirstTurnProvider { _, _ -> "  " }
        m.addFirstTurnProvider { _, b -> "B:${b.facts["ticketId"]}" }
        assertEquals("A:s1:ticket:t9\n\n---\n\nB:t9\n\n---\n\nhi", m.withBrief(record("t9"), "hi"))
    }

    @Test
    fun anUnboundSessionOrNoProvidersLeavesThePromptAlone() {
        val m = manager()
        assertEquals("hi", m.withBrief(record("t9"), "hi"))
        m.addFirstTurnProvider { _, _ -> "X" }
        assertEquals("hi", m.withBrief(record(null), "hi"))
    }
}
