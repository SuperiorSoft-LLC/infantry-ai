// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.model.withMemoryList
import com.superiorsoftllc.infantry.ui.model.withMemorySearch
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.MemoryHit
import com.superiorsoftllc.infantry.ui.protocol.MemoryIndexEntry
import com.superiorsoftllc.infantry.ui.protocol.MemoryNote
import com.superiorsoftllc.infantry.ui.protocol.WireRequests
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UI-MEM-1: the UI decodes the daemon's project-memory frames and encodes the requests that draw them.
 *
 * Decoding (contracts 1.20.0 / 1.21.0): a `memory_notes` frame is one project's index -- the frame
 * names no project, so it is matched to the oldest awaited `memory_list` ([withMemoryList]); a
 * `memory_note` frame (a `memory_save` answer) carries its own project and upserts one note by name;
 * a `memory_hits` frame is the latest search, its project and query taken from the awaited request
 * ([withMemorySearch]). Encoding: `memory_list` (raw project id), `memory_save` and `memory_search`.
 */
class MemoryFramesModelTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun feed(vararg e: Envelope, from: AppState = AppState()) = e.fold(from) { s, f -> reduce(s, f, 100) }

    private fun notes(vararg x: MemoryIndexEntry) =
        Envelope(Envelope.MEMORY_NOTES, json.encodeToString(ListSerializer(MemoryIndexEntry.serializer()), x.toList()))

    private fun note(x: MemoryNote) = Envelope(Envelope.MEMORY_NOTE, json.encodeToString(MemoryNote.serializer(), x))

    private fun hits(vararg x: MemoryHit) =
        Envelope(Envelope.MEMORY_HITS, json.encodeToString(ListSerializer(MemoryHit.serializer()), x.toList()))

    /**
     * (a) Each encoder produces the contract frame: type, and payload byte for byte. `memory_list`
     * carries the raw project id; `memory_save` sends projectId + name + description + body;
     * `memory_search` sends projectId + query.
     */
    @Test fun encodersProduceTheContractFrames() {
        val list = WireRequests.memoryList("A")
        assertEquals(Envelope.MEMORY_LIST, list.type, "memory_list names its frame")
        assertEquals("A", list.data, "memory_list carries the raw project id as payload")

        val save = WireRequests.memorySave("A", "auth", "how auth works", "the body")
        assertEquals(Envelope.MEMORY_SAVE, save.type, "memory_save names its frame")
        assertEquals(
            """{"projectId":"A","name":"auth","description":"how auth works","body":"the body"}""",
            save.data,
            "memory_save sends projectId, name, description and body",
        )

        val search = WireRequests.memorySearch("A", "token")
        assertEquals(Envelope.MEMORY_SEARCH, search.type, "memory_search names its frame")
        assertEquals("""{"projectId":"A","query":"token"}""", search.data, "memory_search sends projectId and query")
    }

    /**
     * (b) A `memory_notes` frame names no project, so it answers the oldest awaited `memory_list` and
     * lands under that project; listing another project keeps the first, and an empty answer clears
     * exactly the project that asked.
     */
    @Test fun aMemoryNotesFrameListsTheProjectThatAsked() {
        val afterA = feed(
            notes(MemoryIndexEntry("auth", "how auth works"), MemoryIndexEntry("cart", "cart rules")),
            from = AppState().withMemoryList("A"),
        )
        assertEquals(listOf("auth", "cart"), afterA.memoryNotes.getValue("A").map { it.name }, "the index lands under the project whose memory_list asked, in order")
        assertEquals("how auth works", afterA.memoryNotes.getValue("A").first().description, "each row keeps its description")
        assertEquals(emptyList(), afterA.memoryListAwaiting, "the request is answered")

        val afterB = feed(notes(MemoryIndexEntry("x", "d")), from = afterA.withMemoryList("B"))
        assertEquals(listOf("auth", "cart"), afterB.memoryNotes.getValue("A").map { it.name }, "listing B keeps A's index")
        assertEquals(listOf("x"), afterB.memoryNotes.getValue("B").map { it.name }, "B's index is stored under B")

        val cleared = feed(notes(), from = afterB.withMemoryList("A"))
        assertEquals(emptyList(), cleared.memoryNotes.getValue("A"), "an empty answer clears exactly the project that asked")
        assertEquals(listOf("x"), cleared.memoryNotes.getValue("B").map { it.name }, "another project's index is untouched")
    }

    /**
     * (c) A `memory_note` frame is the whole stored note. It carries its project and upserts by name --
     * a note's identity inside its project -- so a save of a known name replaces it, never duplicates.
     */
    @Test fun aMemoryNoteFrameUpsertsByNameInsideItsProject() {
        val loaded = feed(notes(MemoryIndexEntry("auth", "stale")), from = AppState().withMemoryList("A"))

        val saved = feed(
            note(MemoryNote("n1", "A", "auth", "", "how auth works", "the body", "2026-10-11T00:00:00Z")),
            from = loaded,
        )
        assertEquals(listOf("auth"), saved.memoryNotes.getValue("A").map { it.name }, "a save of a known name replaces, not duplicates")
        assertEquals("how auth works", saved.memoryNotes.getValue("A").first().description, "the stored note's field is updated")
        assertEquals("the body", saved.memoryNotes.getValue("A").first().body, "the save answer carries the body")

        val added = feed(note(MemoryNote("n2", "A", "billing", "", "how billing works", "b", "t")), from = saved)
        assertEquals(listOf("auth", "billing"), added.memoryNotes.getValue("A").map { it.name }, "an unknown name is appended to its project")
    }

    /**
     * (d) A `memory_hits` frame names neither project nor query, so the search it records is the one
     * this window last asked ([withMemorySearch]); its hits are kept in the daemon's ranked order.
     */
    @Test fun aMemoryHitsFrameRecordsTheQueryItAnswers() {
        val asked = AppState().withMemorySearch("A", "token")
        val answered = feed(
            hits(MemoryHit("auth", "how auth works", "…the token…"), MemoryHit("sessions", "sessions", "…token…")),
            from = asked,
        )
        assertEquals("token", answered.memorySearch?.query, "the latest search keeps the query that was asked")
        assertEquals("A", answered.memorySearch?.projectId, "and the project it belongs to")
        assertEquals(listOf("auth", "sessions"), answered.memorySearch?.hits?.map { it.name }, "the hits are kept in ranked order")
        assertEquals("…the token…", answered.memorySearch?.hits?.first()?.snippet, "a hit keeps its snippet")
        assertEquals(emptyList(), answered.memorySearchAwaiting, "the search is answered")
    }
}
