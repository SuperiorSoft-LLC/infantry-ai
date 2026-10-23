package com.superiorsoftllc.agenthq.memory

import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P10-K2 (contract 1.51.0) over the real daemon socket: `memory_save` gains optional `mode` / `find` /
 * `content` / `folder`, a new `memory_get` frame reads one note, and `memory_notes` rows carry `folder`.
 */
class MemoryModesWireTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val fixtures = mutableListOf<DaemonFixture>()
    private val seen = mutableMapOf<String, Int>()

    @AfterTest
    fun stopAll() {
        fixtures.forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
    }

    private fun launch(): DaemonFixture = DaemonFixture.launch().also {
        fixtures += it
        it.client.await(Envelope.READY)
    }

    private fun DaemonFixture.next(type: String): String {
        val occurrence = seen.getOrElse(type) { 0 }
        seen[type] = occurrence + 1
        return client.await(type, occurrence = occurrence, timeoutMs = 4_000).data
    }

    private fun DaemonFixture.save(payload: String) = send(Envelope.MEMORY_SAVE, payload)

    @Test
    fun theContractIs1_51_0() {
        assertEquals("1.103.0", EventVocabulary.VERSION)
    }

    @Test
    fun aPre151FourFieldPayloadStillReplaces() {
        val fixture = launch()
        fixture.save("""{"projectId":"P","name":"n","description":"d","body":"one"}""")
        fixture.next(Envelope.MEMORY_NOTE)
        fixture.save("""{"projectId":"P","name":"n","description":"d2","body":"two"}""")
        val note = json.parseToJsonElement(fixture.next(Envelope.MEMORY_NOTE)).jsonObject
        assertEquals("two", note["body"]!!.jsonPrimitive.content)
        assertEquals("d2", note["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun appendAndEditOverTheWireAnswerTheWholeNote() {
        val fixture = launch()
        fixture.save("""{"projectId":"P","name":"n","description":"d","body":"alpha","folder":"ops"}""")
        fixture.next(Envelope.MEMORY_NOTE)
        fixture.save("""{"projectId":"P","name":"n","mode":"append","content":"beta"}""")
        val appended = json.parseToJsonElement(fixture.next(Envelope.MEMORY_NOTE)).jsonObject
        assertEquals("alpha\n\nbeta", appended["body"]!!.jsonPrimitive.content)
        assertEquals("ops", appended["folder"]!!.jsonPrimitive.content)
        assertEquals("d", appended["description"]!!.jsonPrimitive.content)

        fixture.save("""{"projectId":"P","name":"n","mode":"edit","find":"alpha","content":""}""")
        val edited = json.parseToJsonElement(fixture.next(Envelope.MEMORY_NOTE)).jsonObject
        assertEquals("\n\nbeta", edited["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun refusalTextStartsWithTheCode() {
        val fixture = launch()
        fixture.save("""{"projectId":"P","name":"n","description":"d","body":"aaa"}""")
        fixture.next(Envelope.MEMORY_NOTE)

        fixture.save("""{"projectId":"P","name":"n","mode":"edit","find":"aa","content":"b"}""")
        assertTrue(fixture.next(Envelope.ERROR).startsWith("memory_save refused: anchor_ambiguous: "))
        fixture.save("""{"projectId":"P","name":"n","mode":"edit","find":"zz","content":"b"}""")
        assertTrue(fixture.next(Envelope.ERROR).startsWith("memory_save refused: anchor_not_found: "))
        fixture.save("""{"projectId":"P","name":"ghost","mode":"append","content":"b"}""")
        assertTrue(fixture.next(Envelope.ERROR).startsWith("memory_save refused: note_not_found: "))

        assertEquals("aaa", fixture.daemon.memoryNotes.get("P", "n")!!.body, "every refusal leaves the note untouched")
    }

    @Test
    fun theOtherModesFieldIsRefused() {
        val fixture = launch()
        fixture.save("""{"projectId":"P","name":"n","description":"d","body":"aaa"}""")
        fixture.next(Envelope.MEMORY_NOTE)
        listOf(
            """{"projectId":"P","name":"n","description":"d","body":"x","content":"y"}""",
            """{"projectId":"P","name":"n","mode":"append","body":"x"}""",
            """{"projectId":"P","name":"n","mode":"append","content":"x","find":"a"}""",
            """{"projectId":"P","name":"n","mode":"edit","find":"a"}""",
            """{"projectId":"P","name":"n","mode":"replace","body":"x"}""",
            """{"projectId":"P","name":"n","mode":"merge","content":"x"}""",
        ).forEach { payload ->
            fixture.save(payload)
            assertTrue(fixture.next(Envelope.ERROR).startsWith("memory_save refused: "), payload)
        }
        assertEquals("aaa", fixture.daemon.memoryNotes.get("P", "n")!!.body)
    }

    @Test
    fun memoryGetReadsOneNoteOrRefusesNoteNotFound() {
        val fixture = launch()
        fixture.save("""{"projectId":"P","name":"n","description":"d","body":"the body","folder":"ops"}""")
        fixture.next(Envelope.MEMORY_NOTE)

        fixture.send(Envelope.MEMORY_GET, """{"projectId":"P","name":"n"}""")
        val got = json.parseToJsonElement(fixture.next(Envelope.MEMORY_NOTE)).jsonObject
        assertEquals("the body", got["body"]!!.jsonPrimitive.content)
        assertEquals("ops", got["folder"]!!.jsonPrimitive.content)

        fixture.send(Envelope.MEMORY_GET, """{"projectId":"P","name":"nope"}""")
        assertTrue(fixture.next(Envelope.ERROR).startsWith("memory_get refused: note_not_found: "))
        fixture.send(Envelope.MEMORY_GET, """{"projectId":"Q","name":"n"}""")
        assertTrue(fixture.next(Envelope.ERROR).startsWith("memory_get refused: note_not_found: "))
    }

    @Test
    fun theIndexCarriesTheFolder() {
        val fixture = launch()
        fixture.save("""{"projectId":"P","name":"b","description":"d","body":"x","folder":"ops/deploy"}""")
        fixture.next(Envelope.MEMORY_NOTE)
        fixture.save("""{"projectId":"P","name":"a","description":"d","body":"x"}""")
        fixture.next(Envelope.MEMORY_NOTE)

        fixture.send(Envelope.MEMORY_LIST, "P")
        val rows = json.parseToJsonElement(fixture.next(Envelope.MEMORY_NOTES)).jsonArray.map { it.jsonObject }
        assertEquals(listOf("a" to "", "b" to "ops/deploy"), rows.map { it["name"]!!.jsonPrimitive.content to it["folder"]!!.jsonPrimitive.content })
        assertTrue(rows.none { "body" in it }, "the index still withholds the body")
    }
}
