// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BUG-FLAKE1-PERSIST (FLAKE-1 follow-up F3): a FOREIGN reader of `sessions.json` must not make writes fail.
 *
 * A `java.io` reader (an AV scanner, an indexer, a tool, or -- before this fix -- the store's own [JsonSessionStore.load])
 * opens the file WITHOUT `FILE_SHARE_DELETE`. On Windows that refuses the `MoveFileEx` the store commits a write with,
 * for as long as the handle is open. The store used to give up after 4 attempts 2 ms apart, so a reader looping on the
 * file lost writes outright: measured at file level, 89-92 daemon writes failed in 3 s.
 *
 * The reader here loops back-to-back, the worst case of a scanner or a polling tool; the store must find one of
 * its gaps. On a platform with no sharing violation (Linux, macOS) the test still holds and simply passes.
 */
class JsonSessionStoreForeignReaderTest {

    private fun session(id: String, heartbeat: Long) = SessionRecord(
        id = id,
        provider = "fakejava",
        workingDir = "",
        spawnedAt = 0L,
        lastHeartbeat = heartbeat,
        state = SessionState.IDLE,
    )

    @Test
    fun noWriteIsLostWhileAForeignJavaIoReaderLoopsOnTheStore() {
        val dir = File(System.getProperty("java.io.tmpdir"), "infantry-store-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(dir, "sessions.json")
        val store = JsonSessionStore(file)
        store.saveAll(listOf(session("s", heartbeat = -1L)))

        val stop = AtomicBoolean(false)
        val reads = AtomicInteger(0)
        val reader = Thread {
            while (!stop.get()) {
                // java.io, deliberately: FileInputStream opens without FILE_SHARE_DELETE, like most foreign readers.
                runCatching { FileInputStream(file).use { it.readBytes() } }.onSuccess { reads.incrementAndGet() }
            }
        }.apply { isDaemon = true; start() }

        val failures = mutableListOf<String>()
        try {
            repeat(WRITES) { i ->
                runCatching { store.saveAll(listOf(session("s", heartbeat = i.toLong()))) }
                    .onFailure { failures += "write $i: ${it::class.simpleName}: ${it.message}" }
            }
        } finally {
            stop.set(true)
            reader.join()
        }
        println("JsonSessionStoreForeignReader: ${reads.get()} foreign reads raced $WRITES writes, ${failures.size} failed")

        assertTrue(reads.get() > WRITES / 4, "vacuous: the foreign reader only read ${reads.get()} times")
        assertEquals(
            emptyList(),
            failures.take(5),
            "${failures.size} of $WRITES writes were lost while a java.io reader looped on ${file.name}",
        )
        assertEquals(
            (WRITES - 1).toLong(),
            JsonSessionStore(file).load().single().lastHeartbeat,
            "the last write is not the one on disk",
        )
    }

    private companion object {
        const val WRITES = 300
    }
}
