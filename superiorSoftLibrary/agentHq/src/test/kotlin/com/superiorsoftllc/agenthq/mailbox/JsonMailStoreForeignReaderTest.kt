package com.superiorsoftllc.agenthq.mailbox

import java.io.File
import java.io.FileInputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BUG-MAILSTORE-PERSIST (BUG-FLAKE1-PERSIST follow-up): a FOREIGN reader of `mailbox.json` must not make mail writes fail.
 *
 * Mirrors [com.superiorsoftllc.agenthq.session.JsonSessionStoreForeignReaderTest]. A `java.io` reader (an AV scanner, an
 * indexer, a tool, or -- before this fix -- the store's own [JsonMailStore.load]) opens the file WITHOUT
 * `FILE_SHARE_DELETE`. On Windows that refuses the `MoveFileEx` the store commits a write with, for as long as the handle
 * is open. The store used to give up after 4 attempts 2 ms apart, so a reader looping on the file lost mail outright.
 *
 * The reader here loops back-to-back, the worst case of a scanner or a polling tool; the store must find one of its
 * gaps. On a platform with no sharing violation (Linux, macOS) the test still holds and simply passes.
 */
class JsonMailStoreForeignReaderTest {

    private fun message(seq: Long) = Message(
        id = "m",
        threadId = "t",
        seq = seq,
        from = "human",
        to = "pm",
        body = "write $seq",
        sentAt = 0L,
    )

    @Test
    fun noMailWriteIsLostWhileAForeignJavaIoReaderLoopsOnTheStore() {
        val dir = File(System.getProperty("java.io.tmpdir"), "agenthq-mailstore-${UUID.randomUUID()}").apply { mkdirs() }
        val file = File(dir, "mailbox.json")
        val store = JsonMailStore(file)
        store.saveAll(listOf(message(seq = -1L)))

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
                runCatching { store.saveAll(listOf(message(seq = i.toLong()))) }
                    .onFailure { failures += "write $i: ${it::class.simpleName}: ${it.message}" }
            }
        } finally {
            stop.set(true)
            reader.join()
        }
        println("JsonMailStoreForeignReader: ${reads.get()} foreign reads raced $WRITES writes, ${failures.size} failed")

        assertTrue(reads.get() > WRITES / 4, "vacuous: the foreign reader only read ${reads.get()} times")
        assertEquals(
            emptyList(),
            failures.take(5),
            "${failures.size} of $WRITES mail writes were lost while a java.io reader looped on ${file.name}",
        )
        assertEquals(
            (WRITES - 1).toLong(),
            JsonMailStore(file).load().single().seq,
            "the last mail write is not the one on disk",
        )
    }

    private companion object {
        const val WRITES = 300
    }
}
