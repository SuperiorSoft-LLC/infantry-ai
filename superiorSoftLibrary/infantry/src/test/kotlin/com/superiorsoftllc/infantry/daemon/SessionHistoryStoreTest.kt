// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CLI-PTY-5: a session's chat survives a daemon restart as history, bounded to the newest items, with no live state in it. */
class SessionHistoryStoreTest {
    private val dir = Files.createTempDirectory("chat-history").toFile()

    private fun store(max: Int = 200) = SessionHistoryStore(dir, { max })

    @Test
    fun keptFrames_comeBackAfterARestart_deltasJoined_liveStateLeftOut() {
        val s = store()
        s.record(Envelope(Envelope.TURN, "hello", "s1", sequence = 1))
        s.record(Envelope(Envelope.TEXT_DELTA, "Hi ", "s1", sequence = 2))
        s.record(Envelope(Envelope.TEXT_DELTA, "there", "s1", sequence = 3))
        s.record(Envelope(Envelope.STATUS, """{"state":"BUSY","runActivity":{"state":"THINKING"}}""", "s1", sequence = 4))
        s.record(Envelope(Envelope.STATUS, """{"state":"BUSY","detail":"compacting","tool":"Bash","todos":[]}""", "s1", sequence = 5))
        s.record(Envelope(Envelope.SESSION, "{}", "s1", sequence = 6))
        s.flush()

        val loaded = store().loadAll(setOf("s1")).getValue("s1")
        assertEquals(0, loaded.dropped)
        assertEquals(listOf(Envelope.TURN, Envelope.TEXT_DELTA, Envelope.STATUS), loaded.frames.map { it.type })
        assertEquals("Hi there", loaded.frames[1].data)
        assertEquals("""{"state":"IDLE","detail":"compacting"}""", loaded.frames[2].data, "a kept status carries its line only, never live state")
        assertTrue(loaded.frames.all { it.sequence == null })
    }

    @Test
    fun onlyTheNewestItemsLoad_andTheRestCountAsDropped() {
        val s = store(max = 50)
        repeat(2000) { i ->
            s.record(Envelope(Envelope.TURN, "q$i", "s1"))
            s.record(Envelope(Envelope.TEXT_DELTA, "a$i", "s1"))
        }
        s.flush()
        val lines = java.io.File(dir, "s1.jsonl").readLines().size
        assertTrue(lines <= 50 * SessionHistoryStore.COMPACT_FACTOR + 1, "the file is compacted as it grows ($lines lines)")

        val loaded = store(max = 50).loadAll(setOf("s1")).getValue("s1")
        assertEquals(50, loaded.frames.size)
        assertEquals("a1999", loaded.frames.last().data, "the newest item is last")
        assertEquals(4000L - 50, loaded.dropped, "the gap counts every older item, across compactions")
    }

    @Test
    fun aDeletedOrUnknownSession_losesItsHistory() {
        val s = store()
        s.record(Envelope(Envelope.TURN, "x", "gone"))
        s.record(Envelope(Envelope.TURN, "y", "kept"))
        s.record(Envelope(Envelope.TURN, "z", "deleted"))
        s.forget("deleted")
        s.flush()
        val loaded = store().loadAll(setOf("kept", "deleted"))
        assertEquals(setOf("kept"), loaded.keys)
        assertFalse(java.io.File(dir, "gone.jsonl").exists(), "a session the daemon no longer holds has its file removed")
    }

    @Test
    fun zeroKeepsNothing_andAnUnsafeIdIsIgnored() {
        val off = store(max = 0)
        off.record(Envelope(Envelope.TURN, "x", "s1"))
        off.flush()
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        val s = store()
        s.record(Envelope(Envelope.TURN, "x", "../evil"))
        s.flush()
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
}
