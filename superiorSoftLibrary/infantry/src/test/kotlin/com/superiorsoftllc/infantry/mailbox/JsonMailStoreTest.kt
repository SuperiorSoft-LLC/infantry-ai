// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What the mailbox store promises a READER, which nothing in the class contract of [MailStore]
 * used to say: a message that reached the file is never reported as "this mailbox has no mail".
 *
 * The defect (ticket ec738be1) is the same read-side hole the session store had (d52bbf40), and it
 * is WORSE here, not equal. Losing resume capability costs a daemon its context; losing the mailbox
 * can cost the only record of an undelivered message, and a reader told "no messages" may act on
 * that and drop it for good.
 *
 * [JsonMailStore.saveAll] replaces the store file by moving a `.tmp` sibling into place. On
 * Windows/NTFS (measured, JDK 25) the non-atomic fallback DELETES the target before it copies the
 * replacement in, and even the atomic path leaves a momentarily stale negative directory entry. A
 * reader that lands in that window is answered `emptyList()` by the old
 * `if (!file.isFile) return emptyList()` -- and the old `runCatching` turned ANY read failure,
 * including a half-written file, into the same false empty.
 *
 * Determinism: the two `..._whenTheStoreIs...` tests construct the single disk state the replacement
 * leaves behind, so they need no timing at all. The concurrent test asserts an INVARIANT (a committed
 * message stays visible) that holds however the threads interleave, and fails when a reader is ever
 * answered with an empty mailbox.
 */
class JsonMailStoreTest {

    private fun storeDir(): File =
        File(System.getProperty("java.io.tmpdir"), "infantry-mailstore-${UUID.randomUUID()}").apply { mkdirs() }

    private fun message(id: String, body: String = "hi") = Message(
        id = id,
        threadId = "t-$id",
        seq = 1L,
        from = "human",
        to = "pm",
        body = body,
        sentAt = 0L,
    )

    private fun snapshotAt(target: File, id: String) {
        target.parentFile?.mkdirs()
        val text = JsonMailStore(target).let { store ->
            // Written by hand rather than through saveAll(), because the point is the exact bytes a
            // replacement leaves on disk, not a committed store.
            buildString {
                append("{\n  \"version\": 1,\n  \"messages\": [\n")
                append("    {\n")
                append("      \"id\": \"").append(id).append("\",\n")
                append("      \"threadId\": \"t-").append(id).append("\",\n")
                append("      \"seq\": 1,\n")
                append("      \"from\": \"human\",\n")
                append("      \"to\": \"pm\",\n")
                append("      \"subject\": \"\",\n")
                append("      \"body\": \"hi\",\n")
                append("      \"sentAt\": 0\n")
                append("    }\n  ]\n}")
            }
        }
        target.writeText(text)
    }

    private fun tmpOf(file: File) = File(file.parentFile, file.name + ".tmp")

    @Test
    fun load_recoversTheReplacementWhenTheStoreIsMomentarilyGone() {
        // Given: the state a replacement leaves behind while it is in flight -- the target has been
        // removed and the complete replacement sits in the `.tmp` sibling that is its source.
        val dir = storeDir()
        val file = File(dir, "mailbox.json")
        snapshotAt(tmpOf(file), "in-flight")

        // When: the store is loaded before the replacement is moved into place.
        val loaded = JsonMailStore(file).load()

        // Then: the mail the replacement carries is served, not an empty mailbox.
        assertEquals(
            listOf("in-flight"),
            loaded.map { it.id },
            "a readable replacement must be served while the target is missing: reporting no mail here " +
                "is the same false empty that loses an undelivered message",
        )
    }

    @Test
    fun load_ignoresAReplacementThatNeverCommittedWhenTheStoreIsReadable() {
        // Given: a target that was written AND a `.tmp` left by a write that failed before committing.
        val dir = storeDir()
        val file = File(dir, "mailbox.json")
        JsonMailStore(file).saveAll(listOf(message("committed")))
        snapshotAt(tmpOf(file), "never-committed")

        // When: the store is loaded.
        val loaded = JsonMailStore(file).load()

        // Then: the committed snapshot wins. The `.tmp` is an uncommitted candidate, not a newer truth.
        assertEquals(
            listOf("committed"),
            loaded.map { it.id },
            "an uncommitted replacement must never override a readable store",
        )
    }

    @Test
    fun load_reportsNoMailWhenNothingWasEverWritten() {
        // Given: a state directory the mailbox has never written to.
        val file = File(storeDir(), "mailbox.json")

        // When/Then: "no mail" is the honest answer, and it is an empty list, not an exception.
        assertEquals(emptyList(), JsonMailStore(file).load())
    }

    @Test
    fun aCommittedMessageIsNeverInvisibleToAConcurrentReader() {
        // Given: a message that HAS reached the file (the precondition, asserted by reading it back).
        val dir = storeDir()
        val file = File(dir, "mailbox.json")
        val writer = JsonMailStore(file)
        // A SECOND instance, because that is the situation the guarantee must survive: the store is
        // not re-entrant across instances, so a second daemon's store object shares nothing with the
        // writer but the file itself.
        val reader = JsonMailStore(file)
        writer.saveAll(listOf(message("committed")))
        assertEquals(listOf("committed"), reader.load().map { it.id }, "precondition: it is on disk")

        // When: a writer replaces the store repeatedly while a reader hammers it.
        val stop = AtomicBoolean(false)
        val missed = java.util.Collections.synchronizedList(mutableListOf<String>())
        val reads = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val writerThread = Thread {
            started.await()
            var n = 0
            while (!stop.get()) {
                writer.saveAll(listOf(message("committed", body = "body-${n++}")))
            }
        }
        val readerThread = Thread {
            started.await()
            while (!stop.get()) {
                val seen = reader.load()
                if (seen.isEmpty()) missed.add("empty")
                reads.set(true)
            }
        }
        writerThread.isDaemon = true
        readerThread.isDaemon = true
        writerThread.start()
        readerThread.start()
        started.countDown()
        Thread.sleep(1_500)
        stop.set(true)
        writerThread.join(5_000)
        readerThread.join(5_000)

        // Then: the reader was answered, and never with an empty mailbox.
        assertTrue(reads.get(), "the reader never completed a load; the test proved nothing")
        assertEquals(
            emptyList(),
            missed.toList(),
            "a message committed BEFORE the writer started was reported as an empty mailbox on " +
                "${missed.size} reads. That is how undelivered mail is lost.",
        )
    }

    @Test
    fun aWriteThatMeetsAReaderHoldingTheStoreStillCommits() {
        // Given: a committed store, and another party holding it OPEN -- which is what a concurrent
        // load(), a backup or an editor does. A blanket catch that answers the denial by unlinking the
        // store (the delete-then-copy the old fallback ran) turns a READ into the loss of every mail.
        val dir = storeDir()
        val file = File(dir, "mailbox.json")
        val store = JsonMailStore(file)
        store.saveAll(listOf(message("committed")))

        val held = RandomAccessFile(file, "r")
        try {
            // Asserted rather than assumed: with that handle open the OS denies the atomic replace
            // OF THE STORE -- the same denial the write below meets. If it did NOT fail, this test
            // would pass vacuously.
            val probe = File(dir, "probe-replacement.json").apply { writeText("{}") }
            assertFailsWith<IOException>(
                "a held read handle must make the atomic replace fail, or this test proves nothing",
            ) {
                Files.move(
                    probe.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }

            // When: the store is replaced while that handle is open, and the handle is released while
            // the write is in flight. The delay is what CREATES the collision; no assertion below
            // depends on how long it is -- the commit is asserted, not the path that produced it.
            var outcome: Throwable? = null
            val writer = Thread { outcome = runCatching { store.saveAll(listOf(message("written-while-held"))) }.exceptionOrNull() }
            writer.start()
            val replacement = tmpOf(file)
            val deadline = System.currentTimeMillis() + 5_000
            while (!replacement.isFile && System.currentTimeMillis() < deadline) Thread.sleep(1)
            assertTrue(replacement.isFile || !writer.isAlive, "the replacement was never written")
            Thread.sleep(3)
            held.close()
            writer.join()

            // Then: the write committed. It could not have committed while the handle was open, so a
            // store that answers a locked file by unlinking it cannot pass this: it fails the write
            // (or destroys every message) instead of waiting out a read that lasts microseconds.
            assertEquals(null, outcome, "the replacement must be committed, not failed: $outcome")
        } finally {
            held.close()
        }

        assertEquals(
            listOf("written-while-held"),
            JsonMailStore(file).load().map { it.id },
            "the write that met an open handle must be readable afterwards",
        )
    }
}
