package com.superiorsoftllc.agenthq.issues

import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P10-K6 (contract 1.54.0) over the real daemon socket: `issue_open` / `issue_attempt` / `issue_fix` are pushed as
 * `issue_item` to EVERY client, `issue_list` / `issue_get` / `issue_precheck` answer the asker, refusals put the code
 * first, the declared session scopes the active issue, and the rows outlive the daemon.
 */
class IssueWireTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val fixtures = mutableListOf<DaemonFixture>()

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
    }

    private fun launch(stateDir: java.io.File? = null): DaemonFixture =
        (if (stateDir == null) DaemonFixture.launch() else DaemonFixture.launch(stateDir = stateDir)).also {
            fixtures += it
            it.client.await(Envelope.READY)
        }

    private fun decode(envelope: Envelope) = json.decodeFromString(Issue.serializer(), envelope.data)
    private fun decodeList(envelope: Envelope) = json.decodeFromString(ListSerializer(Issue.serializer()), envelope.data)

    @Test
    fun theContractIs1_54_0() {
        assertEquals("1.99.0", EventVocabulary.VERSION)
    }

    @Test
    fun writesArePushedToEveryClientAndReadsAnswerTheAsker() {
        val fixture = launch()
        val other = fixture.connectClient()
        fixture.send(Envelope.ISSUE_OPEN, """{"projectId":"P","summary":"login 500","location":"src/auth/Login.kt","ticketId":"T-1"}""")
        val mine = decode(fixture.client.await(Envelope.ISSUE_ITEM))
        assertEquals(mine, decode(other.await(Envelope.ISSUE_ITEM)), "the open is pushed to the other client too")
        assertEquals("I1", mine.ref)
        assertEquals(Issue.STATUS_OPEN, mine.status)
        assertEquals("T-1", mine.ticketId)

        fixture.send(Envelope.ISSUE_ATTEMPT, """{"projectId":"P","summary":"null check","issue":"I1"}""")
        val attempted = decode(fixture.client.await(Envelope.ISSUE_ITEM, occurrence = 1))
        assertEquals(listOf("attempt" to "failed"), attempted.events.map { it.kind to it.outcome }, "default outcome failed")
        fixture.send(Envelope.ISSUE_FIX, """{"projectId":"P","summary":"refresh token","issue":"1"}""")
        val fixed = decode(other.await(Envelope.ISSUE_ITEM, occurrence = 2))
        assertEquals(Issue.STATUS_FIXED, fixed.status)

        fixture.send(Envelope.ISSUE_OPEN, """{"projectId":"P","summary":"cart","location":"src/cart/Cart.kt"}""")
        fixture.client.await(Envelope.ISSUE_ITEM, occurrence = 3)

        fixture.send(Envelope.ISSUE_LIST, """{"projectId":"P"}""")
        assertEquals(listOf("I2"), decodeList(fixture.client.await(Envelope.ISSUES)).map { it.ref })
        fixture.send(Envelope.ISSUE_LIST, """{"projectId":"P","includeFixed":true}""")
        assertEquals(listOf("I1", "I2"), decodeList(fixture.client.await(Envelope.ISSUES, occurrence = 1)).map { it.ref })
        fixture.send(Envelope.ISSUE_PRECHECK, """{"projectId":"P","path":"src\\auth"}""")
        val pre = decodeList(fixture.client.await(Envelope.ISSUES, occurrence = 2))
        assertEquals(listOf("I1"), pre.map { it.ref })
        assertEquals(listOf("attempt", "fix"), pre.single().events.map { it.kind })
        fixture.send(Envelope.ISSUE_GET, """{"projectId":"P","ref":"I1"}""")
        assertEquals(2, decode(fixture.client.await(Envelope.ISSUE_ITEM, occurrence = 4)).events.size)
        assertTrue(other.of(Envelope.ISSUES).isEmpty(), "list answers go to the asker only")
    }

    @Test
    fun refusalsPutTheCodeFirst() {
        val fixture = launch()
        fixture.send(Envelope.ISSUE_OPEN, """{"projectId":"P","summary":"a","location":"src/a"}""")
        fixture.client.await(Envelope.ISSUE_ITEM)
        fixture.send(Envelope.ISSUE_FIX, """{"projectId":"P","summary":"done","issue":"I1"}""")
        fixture.client.await(Envelope.ISSUE_ITEM, occurrence = 1)

        val refusals = listOf(
            Envelope.ISSUE_FIX to """{"projectId":"P","summary":"x"}""",
            Envelope.ISSUE_FIX to """{"projectId":"P","summary":"x","issue":"I1"}""",
            Envelope.ISSUE_ATTEMPT to """{"projectId":"P","summary":"x","issue":"I1"}""",
            Envelope.ISSUE_ATTEMPT to """{"projectId":"Q","summary":"x","issue":"I1"}""",
            Envelope.ISSUE_OPEN to """{"projectId":"P","summary":"x","location":"src/a","sessionId":"no-such-session"}""",
            Envelope.ISSUE_GET to """{"projectId":"P","ref":"I9"}""",
            Envelope.ISSUE_ATTEMPT to """{"projectId":"P","summary":"x","outcome":"great"}""",
            Envelope.ISSUE_OPEN to """{"projectId":"P","summary":"x","location":" "}""",
        )
        refusals.forEachIndexed { i, (type, data) ->
            fixture.send(type, data)
            fixture.client.await(Envelope.ERROR, occurrence = i)
        }
        val expected = listOf(
            "issue_fix refused: no_active_issue: ",
            "issue_fix refused: already_fixed: ",
            "issue_attempt refused: already_fixed: ",
            "issue_attempt refused: issue_not_found: ",
            "issue_open refused: session_not_found: ",
            "issue_get refused: issue_not_found: ",
            "issue_attempt refused: invalid outcome 'great'",
            "issue_open refused: location is required",
        )
        val errors = fixture.client.of(Envelope.ERROR).map { it.data }
        assertEquals(expected.size, errors.size, errors.toString())
        expected.zip(errors).forEach { (prefix, error) -> assertTrue(error.startsWith(prefix), "expected '$prefix...', got '$error'") }
        assertEquals(2, fixture.client.of(Envelope.ISSUE_ITEM).size, "no refusal pushed an item")
    }

    @Test
    fun issuesOutliveTheDaemon() {
        val stateDir = java.nio.file.Files.createTempDirectory("agenthq-issues-restart").toFile()
        val first = launch(stateDir)
        first.send(Envelope.ISSUE_OPEN, """{"projectId":"P","summary":"survive me","location":"src/a"}""")
        first.client.await(Envelope.ISSUE_ITEM)
        first.send(Envelope.ISSUE_ATTEMPT, """{"projectId":"P","summary":"try","issue":"I1","outcome":"partial"}""")
        first.client.await(Envelope.ISSUE_ITEM, occurrence = 1)
        first.stop()
        fixtures.remove(first)

        val second = launch(stateDir)
        second.send(Envelope.ISSUE_GET, """{"projectId":"P","ref":"I1"}""")
        val back = decode(second.client.await(Envelope.ISSUE_ITEM))
        assertEquals("survive me", back.summary)
        assertEquals(listOf("partial"), back.events.map { it.outcome })
    }

    @Test
    fun theDeclaredSessionIsTheAuthorAndTheScopeOfTheActiveIssue() {
        val path = java.nio.file.Files.createTempFile("agenthq-issue-handler-", ".db").also { it.toFile().delete() }.toString()
        com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore.open(path).use { sql ->
            val pushed = mutableListOf<Envelope>()
            val handler = IssueHandler(
                store = IssueStore(sql),
                actor = { id -> if (id == "s1" || id == "s2") IssueActor(id, "agent-$id") else null },
                push = { pushed += it },
            )
            fun send(type: String, data: String) = assertNull(handler.answer(Envelope(type, data)), "a write is answered by its push")
            send(Envelope.ISSUE_OPEN, """{"projectId":"P","summary":"one","location":"src/a","sessionId":"s1"}""")
            send(Envelope.ISSUE_OPEN, """{"projectId":"P","summary":"two","location":"src/b","sessionId":"s2"}""")
            send(Envelope.ISSUE_ATTEMPT, """{"projectId":"P","summary":"s1 tries","sessionId":"s1"}""")
            send(Envelope.ISSUE_FIX, """{"projectId":"P","summary":"s2 fixes","sessionId":"s2"}""")
            val items = pushed.map(::decode)
            assertEquals(listOf(1, 2, 1, 2), items.map { it.number }, "each session's unnamed write went to its own issue")
            assertEquals("s1" to "agent-s1", items[0].openedBySession to items[0].openedByAgent)
            assertEquals("agent-s1", items[2].events.single().agentId)
            assertEquals(Issue.STATUS_FIXED, items[3].status)
            assertEquals(Issue.STATUS_OPEN, IssueStore(sql).get("P", "I1")!!.status)
            assertNull(handler.answer(Envelope("memory_list", "P")), "another handler's frame is not answered")
        }
    }
}
