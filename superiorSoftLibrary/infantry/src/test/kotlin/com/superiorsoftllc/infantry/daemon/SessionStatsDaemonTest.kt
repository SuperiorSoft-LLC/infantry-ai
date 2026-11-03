package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.stats.SessionStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * STATS-1 over the REAL daemon and a real WebSocket: `session_stats_get` for a live session is answered by
 * `session_stats` naming that session, its harness and the daemon's status. The session has run no turn, so its
 * CLI has revealed no id and no harness file is touched: every harness field is null, not guessed. Unknown and
 * blank ids are refused as `error` frames, never a crash.
 */
class SessionStatsDaemonTest {

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

    @Test
    fun sessionStatsAnswersForALiveSession() = withDaemon { fixture ->
        fixture.client.saveProfile("""{"id":"s-one","name":"S One","role":"backend"}""")
        fixture.send(Envelope(Envelope.NEW, """{"agentId":"s-one","projectId":"P"}"""))
        fixture.await(what = "a new s-one session") { fixture.daemon.manager.list().any { it.agentId == "s-one" } }
        val record = fixture.daemon.manager.list().first { it.agentId == "s-one" }

        fixture.send(Envelope(Envelope.SESSION_STATS_GET, """{"sessionId":"${record.id}"}"""))
        val answer = fixture.client.await(Envelope.SESSION_STATS)
        val stats = JSON.decodeFromString(SessionStats.serializer(), answer.data)

        assertEquals(record.id, stats.sessionId, answer.data)
        assertEquals(record.provider, stats.harness, answer.data)
        assertEquals(record.state.name.lowercase(), stats.status, answer.data)
        assertNull(stats.providerSessionId, answer.data)
        assertNull(stats.userPrompts, "no harness record yet: null, never zero: ${answer.data}")
        assertNull(stats.series, answer.data)
        assertNull(stats.modelContextLimit, answer.data)
    }

    @Test
    fun unknownOrBlankSessionIsRefusedWithAnError() = withDaemon { fixture ->
        val unknown = fixture.client.answerTo(Envelope.SESSION_STATS_GET, """{"sessionId":"nope"}""")
        assertEquals(Envelope.ERROR, unknown.type)
        assertTrue(unknown.data.startsWith("session_stats_get refused: unknown session 'nope'"), unknown.data)

        val blank = fixture.client.answerTo(Envelope.SESSION_STATS_GET, """{"sessionId":"  "}""")
        assertTrue(blank.data.startsWith("session_stats_get refused: sessionId is blank"), blank.data)
    }
}
