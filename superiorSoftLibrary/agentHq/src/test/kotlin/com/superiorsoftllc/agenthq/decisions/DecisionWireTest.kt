package com.superiorsoftllc.agenthq.decisions

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
 * P10-K4 (contract 1.53.0) over the real daemon socket: `decision_add` is pushed as `decision_item` to EVERY client,
 * `decision_list` / `decision_get` answer the asker, refusals put the code first, and the rows outlive the daemon.
 */
class DecisionWireTest {
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

    private fun decode(envelope: Envelope) = json.decodeFromString(Decision.serializer(), envelope.data)

    @Test
    fun theContractIs1_54_0() {
        assertEquals("1.101.0", EventVocabulary.VERSION)
    }

    @Test
    fun anAddIsPushedToEveryClientAndListedAndRead() {
        val fixture = launch()
        val other = fixture.connectClient()
        fixture.send(Envelope.DECISION_ADD, """{"projectId":"P","summary":"use sqlite","body":"embedded"}""")
        val mine = decode(fixture.client.await(Envelope.DECISION_ITEM))
        val theirs = decode(other.await(Envelope.DECISION_ITEM))
        assertEquals(mine, theirs, "the add is pushed to the other client too")
        assertEquals("D1", mine.ref)
        assertNull(mine.authorSessionId)

        fixture.send(Envelope.DECISION_ADD, """{"projectId":"P","summary":"use postgres","supersedes":"D1"}""")
        assertEquals(1, decode(fixture.client.await(Envelope.DECISION_ITEM, occurrence = 1)).supersedes)

        fixture.send(Envelope.DECISION_LIST, """{"projectId":"P"}""")
        val active = json.decodeFromString(ListSerializer(Decision.serializer()), fixture.client.await(Envelope.DECISIONS).data)
        assertEquals(listOf("D2"), active.map { it.ref })
        fixture.send(Envelope.DECISION_LIST, """{"projectId":"P","includeSuperseded":true}""")
        val all = json.decodeFromString(ListSerializer(Decision.serializer()), fixture.client.await(Envelope.DECISIONS, occurrence = 1).data)
        assertEquals(listOf(2, null), all.map { it.supersededBy })

        fixture.send(Envelope.DECISION_GET, """{"projectId":"P","ref":"1"}""")
        val d1 = decode(fixture.client.await(Envelope.DECISION_ITEM, occurrence = 2))
        assertEquals("embedded", d1.body)
        assertEquals(2, d1.supersededBy)
        assertTrue(other.of(Envelope.DECISIONS).isEmpty(), "list answers go to the asker only")
    }

    @Test
    fun refusalsPutTheCodeFirst() {
        val fixture = launch()
        fixture.send(Envelope.DECISION_ADD, """{"projectId":"P","summary":"a"}""")
        fixture.client.await(Envelope.DECISION_ITEM)
        fixture.send(Envelope.DECISION_ADD, """{"projectId":"P","summary":"b","supersedes":"D1"}""")
        fixture.client.await(Envelope.DECISION_ITEM, occurrence = 1)

        val refusals = listOf(
            Envelope.DECISION_ADD to """{"projectId":"P","summary":"c","supersedes":"D1"}""",
            Envelope.DECISION_ADD to """{"projectId":"Q","summary":"c","supersedes":"D1"}""",
            Envelope.DECISION_ADD to """{"projectId":"P","summary":"c","sessionId":"no-such-session"}""",
            Envelope.DECISION_GET to """{"projectId":"P","ref":"D9"}""",
        )
        refusals.forEachIndexed { i, (type, data) ->
            fixture.send(type, data)
            fixture.client.await(Envelope.ERROR, occurrence = i)
        }
        val expected = listOf(
            "decision_add refused: already_superseded: ",
            "decision_add refused: decision_not_found: ",
            "decision_add refused: session_not_found: ",
            "decision_get refused: decision_not_found: ",
        )
        val errors = fixture.client.of(Envelope.ERROR).map { it.data }
        assertEquals(expected.size, errors.size, errors.toString())
        expected.zip(errors).forEach { (prefix, error) -> assertTrue(error.startsWith(prefix), "expected '$prefix...', got '$error'") }
        assertEquals(2, fixture.client.of(Envelope.DECISION_ITEM).size, "no refusal pushed an item")
    }

    @Test
    fun decisionsOutliveTheDaemon() {
        val stateDir = java.nio.file.Files.createTempDirectory("agenthq-decisions-restart").toFile()
        val first = launch(stateDir)
        first.send(Envelope.DECISION_ADD, """{"projectId":"P","summary":"survive me"}""")
        first.client.await(Envelope.DECISION_ITEM)
        first.stop()
        fixtures.remove(first)

        val second = launch(stateDir)
        second.send(Envelope.DECISION_GET, """{"projectId":"P","ref":"D1"}""")
        assertEquals("survive me", decode(second.client.await(Envelope.DECISION_ITEM)).summary)
    }

    @Test
    fun theAuthorComesFromTheDeclaredSession() {
        val path = java.nio.file.Files.createTempFile("agenthq-decision-handler-", ".db").also { it.toFile().delete() }.toString()
        com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore.open(path).use { sql ->
            val pushed = mutableListOf<Envelope>()
            val handler = DecisionHandler(
                store = DecisionStore(sql),
                author = { id -> if (id == "s1") DecisionAuthor("s1", "agent-7", "backend") else null },
                push = { pushed += it },
            )
            assertNull(handler.answer(Envelope(Envelope.DECISION_ADD, """{"projectId":"P","summary":"x","sessionId":"s1"}""")))
            val added = decode(pushed.single())
            assertEquals(Triple("s1", "agent-7", "backend"), Triple(added.authorSessionId, added.authorAgentId, added.authorRole))
            assertNull(handler.answer(Envelope("memory_list", "P")), "another handler's frame is not answered")
        }
    }
}
