package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runner.StubScript
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun memoryFramesAreRefusedWithThePluginAbsentText() {
        val fixture = DaemonFixture.launch(StubScript.default())
        try {
            fixture.client.await(Envelope.READY)
            listOf(Envelope.MEMORY_SEARCH, Envelope.MEMORY_SAVE, Envelope.MEMORY_LIST, Envelope.MEMORY_GET, Envelope.MEMORY_DELETE, Envelope.MEMORY_PURGE_ROLE)
                .forEachIndexed { i, type ->
                    fixture.send(type, """{"projectId":"P","name":"n"}""")
                    assertEquals("$type refused: this daemon has no memory", fixture.client.await(Envelope.ERROR, occurrence = i).data)
                }
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
