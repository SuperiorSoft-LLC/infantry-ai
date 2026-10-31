package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.runner.StubScript
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** DP-7c: with no Infantry_Notes plugin the decision and issue frames are refused (never hang) and the digest omits them. */
class KnowledgeRecordsAbsentTest {
    @Test
    fun decisionAndIssueFramesAreRefusedWithThePluginAbsentText() {
        val fixture = DaemonFixture.launch(StubScript.default())
        try {
            fixture.client.await(Envelope.READY)
            val cases = listOf(
                Envelope.DECISION_LIST to "decisions",
                Envelope.DECISION_ADD to "decisions",
                Envelope.ISSUE_LIST to "issues",
                Envelope.ISSUE_OPEN to "issues",
            )
            cases.forEachIndexed { i, (type, what) ->
                fixture.send(type, """{"projectId":"P","summary":"x","location":""}""")
                val reply = fixture.client.await(Envelope.ERROR, occurrence = i)
                assertEquals("$type refused: this daemon has no $what", reply.data)
            }
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun theDigestOmitsDecisionsAndIssuesWithNoRecordsProvider() {
        val memory = com.superiorsoftllc.agenthq.memory.MemoryStore().also { it.save("p1", "arch", "how", "body") }
        val digest = com.superiorsoftllc.agenthq.agents.LocalKnowledge(memory) { null }.digest("p1")!!
        assertTrue(digest.decisionLines.isEmpty() && digest.activeDecisions == 0 && digest.openIssues == 0)
    }
}
