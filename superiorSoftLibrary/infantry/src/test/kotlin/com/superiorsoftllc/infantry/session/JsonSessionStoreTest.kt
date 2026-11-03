package com.superiorsoftllc.infantry.session

import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What the on-disk store promises a READER, which is what [SessionManager.loadAndReconcile] is built
 * on: a snapshot that reached the file is never reported as "this store has no sessions".
 *
 * The promise is not academic. On Windows/NTFS (measured, JDK 25) a reader that asks for the store
 * file while it is being replaced can be told the name does not exist -- the JDK's non-atomic
 * `Files.move` deletes the target before it copies the replacement in, and even the atomic path
 * leaves a momentarily stale negative directory entry. The window is sub-millisecond, but a reader
 * that lands in it and is answered `emptyList()` starts a daemon with ZERO sessions and then fails
 * the first `resume(id)` with "unknown session <id>" -- ticket d52bbf40, where
 * [SessionProcessIntegrationTest.realProcess_completes_thenResumesAcrossManagerRestart_keepsContext]
 * reproduced exactly that: the previous manager's last write was still in flight when the "restarted"
 * manager opened the store.
 *
 * Determinism: the two `..._whenTheStoreIs...` tests construct the single disk state the replacement
 * leaves behind, so they need no timing at all. The concurrent test never asserts on elapsed time
 * either -- it asserts an invariant (a committed snapshot stays visible) that holds no matter how the
 * threads interleave, and it FAILS when a reader is answered with an empty store.
 */
class JsonSessionStoreTest {

    private fun storeDir(): File =
        File(System.getProperty("java.io.tmpdir"), "infantry-store-${UUID.randomUUID()}").apply { mkdirs() }

    private fun session(id: String, heartbeat: Long = 0L) = SessionRecord(
        id = id,
        provider = "fakejava",
        workingDir = "",
        spawnedAt = 0L,
        lastHeartbeat = heartbeat,
        state = SessionState.IDLE,
    )

    @Test
    fun aCommittedSnapshotIsNeverInvisibleToAConcurrentReader() {
        // Given: a snapshot that HAS reached the file (the precondition, verified by reading it back).
        val dir = storeDir()
        val file = File(dir, "sessions.json")
        val writerStore = JsonSessionStore(file)
        // A SECOND instance, because that is the situation the guarantee has to survive: the store is
        // not re-entrant across instances, so a restarted daemon's store object (or any other reader
        // of the file) shares nothing with the writer but the file itself.
        val readerStore = JsonSessionStore(file)
        writerStore.saveAll(listOf(session("committed")))
        assertEquals(
            listOf("committed"),
            readerStore.load().map { it.id },
            "precondition: the committed snapshot must be readable before the concurrent writes start",
        )

        // When: the writer keeps replacing the snapshot while the reader keeps loading it.
        var writes = 0
        var writeFailures = 0
        var reads = 0
        var blanks = 0
        var sawAWriteInFlight = false
        val writer = Thread {
            repeat(WRITES) { i ->
                // A failed write is allowed by the store's contract ("must never lose a previously
                // saved session because one write failed") and is what a daemon does with it: log and
                // carry on. It is counted, not asserted -- what this test asserts is the READER's
                // guarantee, which must hold even while writes are failing.
                runCatching { writerStore.saveAll(listOf(session("committed", heartbeat = i.toLong()))) }
                    .onFailure { writeFailures++ }
                writes++
            }
        }
        writer.start()
        while (writer.isAlive) {
            reads++
            // Non-vacuity: the reader must really race the writer, not run after it. `sessions.json.tmp`
            // exists from the moment a write starts until its replacement is moved into place, so seeing
            // it here proves a write was in flight while this read was taken.
            if (File(dir, "sessions.json.tmp").isFile) sawAWriteInFlight = true
            if (readerStore.load().none { it.id == "committed" }) blanks++
        }
        writer.join()
        println(
            "JsonSessionStore: $reads reads raced $writes writes ($writeFailures failed), $blanks saw no sessions",
        )

        // Then: not one of those reads may answer "no sessions".
        assertTrue(
            writes == WRITES && reads > 20,
            "vacuous: the reader performed $reads reads against $writes writes, so there was no race",
        )
        assertTrue(
            sawAWriteInFlight,
            "vacuous: no read was taken while a write was in flight, so nothing here tested the race",
        )
        assertEquals(
            0,
            blanks,
            "a snapshot committed BEFORE the writer started was reported as no-sessions on $blanks of " +
                "$reads reads. That is how a daemon restart loses every session it was asked to resume " +
                "(\"unknown session <id>\", ticket d52bbf40): the in-flight replacement makes the store " +
                "file momentarily invisible and load() answers emptyList() for it.",
        )
    }

    @Test
    fun load_recoversTheReplacementWhenTheStoreIsMomentarilyGone() {
        // Given: the state a replacement leaves behind while it is in flight -- the target has been
        // removed and the complete replacement sits in the `.tmp` sibling that is its source.
        val dir = storeDir()
        val file = File(dir, "sessions.json")
        snapshotAt(tmpOf(file), "in-flight")

        // When: the store is loaded before the replacement is moved into place.
        val loaded = JsonSessionStore(file).load()

        // Then: the sessions the replacement carries are served, not an empty store.
        assertEquals(
            listOf("in-flight"),
            loaded.map { it.id },
            "a readable replacement must be served while the target is missing: reporting no sessions " +
                "here is the same false empty that makes resume() answer \"unknown session\"",
        )
    }

    @Test
    fun rawReadTextThrowsInReplacementWindowWhereLoadSurvives() {
        // The replacement window [JsonSessionStore.saveAll] opens when the store file is removed
        // and its `.tmp` sibling holds the in-flight snapshot, until Files.move places it. The
        // non-atomic fallback of replaceIntoPlace (DeleteFile target then CopyFileEx) -- and,
        // measured, even the atomic path's stale negative directory entry -- both leave the target
        // absent for a reader.
        //
        // A reader that asks the file directly (the pre-fix SessionProcessIntegrationTest:83 did
        // exactly `storeFile.readText()`) meets a FileNotFoundException there -- on Windows the
        // sharing-violation form, the exact symptom of fc7942c8. load(), which the fix routes
        // through, retries the window and falls back to the .tmp, so it returns the committed
        // snapshot. This is the deterministic witness: not a timing race, but the on-disk state
        // the writer actually passes through, held long enough to read it.
        val dir = storeDir()
        val file = File(dir, "sessions.json")
        snapshotAt(tmpOf(file), "committed")   // store file absent, .tmp carries the snapshot

        // Non-vacuity: the store file genuinely is absent, so the raw read cannot succeed.
        assertTrue(!file.isFile, "the replacement window requires the store file to be absent")

        // The raw read that bypassed the store throws -- the defect, pinned deterministically.
        assertFailsWith<FileNotFoundException> { file.readText() }

        // The store read the fix routes through survives the same window.
        assertEquals(
            listOf("committed"),
            JsonSessionStore(file).load().map { it.id },
            "load() must serve the committed snapshot from the .tmp while the target is absent",
        )
    }

    @Test
    fun load_ignoresAReplacementThatNeverCommittedWhenTheStoreIsReadable() {
        // Given: a target that was written AND a `.tmp` left by a write that failed before it committed.
        val dir = storeDir()
        val file = File(dir, "sessions.json")
        JsonSessionStore(file).saveAll(listOf(session("committed")))
        snapshotAt(tmpOf(file), "never-committed")

        // When: the store is loaded.
        val loaded = JsonSessionStore(file).load()

        // Then: the committed snapshot wins. The `.tmp` is an uncommitted candidate, not a newer truth:
        // adopting it would resurrect a snapshot the writer abandoned.
        assertEquals(
            listOf("committed"),
            loaded.map { it.id },
            "an uncommitted replacement must never override a readable store",
        )
    }

    @Test
    fun load_reportsNoSessionsWhenNothingWasEverWritten() {
        // Given: a state directory a daemon has never written to.
        val file = File(storeDir(), "sessions.json")

        // When/Then: "no sessions" is the honest answer, and it is an empty list, not an exception.
        assertEquals(emptyList(), JsonSessionStore(file).load())
    }

    @Test
    fun aWriteThatMeetsAReaderHoldingTheStoreStillCommits() {
        // Given: a committed snapshot, and another party holding the store OPEN -- which is exactly
        // what a concurrent `load()`, the integration test's `storeFile.readText()`, a backup or an
        // editor does. The precondition is asserted rather than assumed: while that handle is open,
        // NEITHER replacement can touch the store. So a write can only land by waiting for the handle
        // to go away, and the write below is released mid-call to show the store does that instead of
        // answering a locked file by unlinking it (the delete-then-copy the old blanket catch ran).
        val dir = storeDir()
        val file = File(dir, "sessions.json")
        val store = JsonSessionStore(file)
        store.saveAll(listOf(session("committed", heartbeat = 0L)))

        val held = RandomAccessFile(file, "r")
        try {
            // Asserted rather than assumed: with that handle open, the OS denies the atomic replace
            // OF THE STORE (the same denial the write below meets) -- measured on this machine, and
            // what makes the rest of this test mean something.
            val probeReplacement = File(dir, "probe-replacement.json").apply { writeText("{}") }
            assertFailsWith<IOException>(
                "a held read handle must make the atomic replace fail, or this test proves nothing",
            ) {
                Files.move(
                    probeReplacement.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }

            // When: the store is replaced while that handle is open, and the handle is released while
            // the write is in flight. The release is triggered by the replacement appearing on disk --
            // the write is in `replaceIntoPlace()` from microseconds after that -- and delayed just
            // past its first attempt. That delay is what CREATES the collision; no assertion below
            // depends on how long it is: the commit is asserted, not the path that produced it.
            var outcome: Throwable? = null
            val writer = Thread {
                outcome = runCatching {
                    store.saveAll(listOf(session("committed", heartbeat = 1L)))
                }.exceptionOrNull()
            }
            writer.start()
            val replacement = File(dir, "sessions.json.tmp")
            val deadline = System.currentTimeMillis() + 5_000
            while (!replacement.isFile && System.currentTimeMillis() < deadline) Thread.sleep(1)
            assertTrue(replacement.isFile || !writer.isAlive, "the replacement was never written")
            Thread.sleep(3)
            held.close()
            writer.join()

            // Then: the write committed. It could not have committed while the handle was open, so a
            // store that answers a locked file with "delete and copy" cannot pass this: it fails the
            // write (or destroys the snapshot) instead of waiting for a read that lasts microseconds.
            assertEquals(null, outcome, "the replacement must be committed, not failed: $outcome")
            assertEquals(
                listOf(1L),
                JsonSessionStore(file).load().map { it.lastHeartbeat },
                "the store must hold the replacement that was written under the held handle",
            )
        } finally {
            held.close()
        }
    }

    /** The `.tmp` sibling [JsonSessionStore] writes before it replaces the store; the reader's second witness. */
    private fun tmpOf(file: File): File = File(file.parentFile, file.name + ".tmp")

    /**
     * THE REPRODUCTION of this ticket (RED first; QA gate 2d90e9eb: "1 of 3576 reads").
     *
     * A committed snapshot is read concurrently while the store is being rewritten and, for stretches longer
     * than a reader's look, another party holds it unreadable. That is what an in-flight replacement looks like
     * to a reader whose rename is held up: the store cannot be read, and the reader gave up after its fixed look
     * and answered emptyList(), which starts a daemon with no sessions.
     *
     * Deterministic, not a timing race: every hold is a real byte-range lock (or a removed store name with its
     * replacement locked) that lasts [HOLD_MS], far longer than the look the reader used to give up after. Every
     * read that overlaps a hold must still answer the committed snapshot. The writer keeps rewriting throughout;
     * the writes that meet a hold fail, which the store contract allows.
     */
    @Test
    fun aStoreHeldUnreadableByAnotherPartyIsNeverReportedAsEmpty() {
        val dir = storeDir()
        val file = File(dir, "sessions.json")
        val tmp = tmpOf(file)
        val writerStore = JsonSessionStore(file)
        val readerStore = JsonSessionStore(file)
        writerStore.saveAll(listOf(session("committed")))
        assertEquals(
            listOf("committed"),
            readerStore.load().map { it.id },
            "precondition: the committed snapshot must be readable before anything holds it",
        )
        val snapshot = file.readBytes()

        // Counts hold starts and ends: odd while a hold is in progress. A read that overlaps a hold is one that
        // starts during it, or during which the count changes (a read blocked across a whole hold, which the reader
        // thread cannot start another read past, so a boolean sampled at its ends would miss it).
        val holdEvents = AtomicInteger(0)
        val holderRunning = AtomicBoolean(true)
        val writing = AtomicBoolean(true)
        val holds = AtomicInteger(0)
        val witnessed = AtomicInteger(0)
        val holderFailure = AtomicReference<Throwable?>(null)
        val holder = Thread {
            try {
                repeat(HOLDS) { cycle ->
                    if (hold(file, tmp, snapshot, midReplacement = cycle % 2 == 1, holdEvents = holdEvents)) {
                        witnessed.incrementAndGet()
                    }
                    holds.incrementAndGet()
                    Thread.sleep(GAP_MS)
                }
            } catch (failure: Throwable) {
                holderFailure.set(failure)
            } finally {
                holderRunning.set(false)
            }
        }
        val writer = Thread {
            var heartbeat = 0L
            while (holderRunning.get()) writeQuietly(writerStore, heartbeat++)
            // Writes after the last hold, so the store ends on a clean, readable snapshot.
            repeat(5) { writeQuietly(writerStore, heartbeat++) }
            writing.set(false)
        }

        var reads = 0
        var overlappingAHold = 0
        var blanks = 0
        holder.start()
        writer.start()
        while (writing.get()) {
            val eventsBefore = holdEvents.get()
            val answer = runCatching { readerStore.load() }
            val eventsAfter = holdEvents.get()
            reads++
            if (eventsBefore % 2 == 1 || eventsAfter != eventsBefore) overlappingAHold++
            answer.onSuccess { sessions -> if (sessions.none { it.id == "committed" }) blanks++ }
        }
        holder.join()
        writer.join()

        holderFailure.get()?.let { throw AssertionError("the holder thread failed, so the test proved nothing", it) }
        assertEquals(HOLDS, holds.get(), "vacuous: not every hold took place, so the window was not held open")
        assertEquals(
            HOLDS,
            witnessed.get(),
            "vacuous: a hold did not make the store unreadable while it lasted, so that hold proved nothing",
        )
        assertTrue(overlappingAHold > 0, "vacuous: no read overlapped a hold, so nothing here tested the window")
        assertEquals(
            0,
            blanks,
            "a snapshot committed BEFORE the holds was reported as no-sessions on $blanks of $reads reads " +
                "($overlappingAHold of them overlapped a hold). That is how a daemon restart loses every session " +
                "it was asked to resume (\"unknown session <id>\", ticket d52bbf40): load() gave up on the " +
                "unreadable store and answered emptyList().",
        )

        // Nothing is held any more: a clean rewrite lands and the store reads back as the committed snapshot.
        writerStore.saveAll(listOf(session("committed", heartbeat = 1L)))
        assertEquals(
            listOf("committed"),
            readerStore.load().map { it.id },
            "once nothing holds the store, it must read back",
        )
    }

    /**
     * A hold longer than the old look and shorter than the wait is WAITED OUT: a reader that calls load() while the
     * store is locked gets the committed snapshot back once the lock is released, not "unreadable" and not an empty
     * board. The lock is taken before the reader starts and held for BRIEF_HOLD_MS, so the reader's first look is made
     * while the store is locked, and the wait is what answers. A reader that gave up at once, or sooner than the hold,
     * fails here.
     */
    @Test
    fun aBriefHoldIsWaitedOutAndTheCommittedSnapshotIsReturned() {
        val dir = storeDir()
        val file = File(dir, "sessions.json")
        JsonSessionStore(file).saveAll(listOf(session("committed")))
        val reader = JsonSessionStore(file)
        val locked = CountDownLatch(1)
        val holder = Thread {
            RandomAccessFile(file, "rw").use { handle ->
                val lock = handle.channel.lock()
                locked.countDown()
                try {
                    Thread.sleep(BRIEF_HOLD_MS)
                } finally {
                    lock.release()
                }
            }
        }
        holder.start()
        assertTrue(locked.await(5, TimeUnit.SECONDS), "the holder never took the lock, so the brief hold was not exercised")
        assertEquals(
            listOf("committed"),
            reader.load().map { it.id },
            "a store held for $BRIEF_HOLD_MS ms must be waited for: the committed snapshot must come back",
        )
        holder.join()
    }

    /**
     * An instance that could not read its store never replaces it. A store that is unreadable for a while keeps
     * every session it holds, and a write that would overwrite it fails instead.
     */
    @Test
    fun anUnreadableStoreIsNeverOverwrittenByTheInstanceThatCouldNotReadIt() {
        val dir = storeDir()
        val file = File(dir, "sessions.json")
        JsonSessionStore(file).saveAll(listOf(session("committed")))
        val committedBytes = file.readBytes()
        val store = JsonSessionStore(file)

        // The outcome of this load is asserted by the test above; here it only arms the refusal.
        runWhileLocked(file) { store.load() }

        val write = runCatching { store.saveAll(listOf(session("replacement"))) }
        assertTrue(
            write.isFailure,
            "saveAll must refuse to replace a store it could not read, but it replaced it: the committed sessions are gone",
        )
        assertTrue(
            file.readBytes().contentEquals(committedBytes),
            "the unreadable store must be left byte-for-byte intact",
        )
    }

    /** Runs [action] while this thread holds an exclusive byte-range lock on [file], so every read of it fails. */
    private fun <T> runWhileLocked(file: File, action: () -> T): Result<T> {
        RandomAccessFile(file, "rw").use { handle ->
            val lock = handle.channel.lock()
            try {
                return runCatching(action)
            } finally {
                lock.release()
            }
        }
    }

    /**
     * One hold, on this thread. All file I/O happens BEFORE a lock is taken: the snapshot is written through handles
     * that are already open, and then the locks are taken and held for [HOLD_MS] and nothing else. An I/O stall while
     * a lock is held would stretch the hold past the reader's wait, and the test would then measure the machine rather
     * than the store. The `.tmp` sibling is locked so the writer cannot land a replacement. The locked shape also locks
     * the store itself; the mid-replacement shape removes the store NAME instead. Either way every read of the store
     * fails until the locks are released, and the store is valid again the moment the hold ends.
     */
    private fun hold(
        file: File,
        tmp: File,
        snapshot: ByteArray,
        midReplacement: Boolean,
        holdEvents: AtomicInteger,
    ): Boolean {
        val tmpHandle = openForHold(tmp)
        val fileHandle = if (midReplacement) null else openForHold(file)
        var tmpLock: FileLock? = null
        var fileLock: FileLock? = null
        var unreadableWhileHeld = false
        try {
            tmpHandle.setLength(0)
            tmpHandle.write(snapshot)
            fileHandle?.let {
                it.setLength(0)
                it.write(snapshot)
            }
            tmpLock = tmpHandle.channel.lock()
            fileLock = fileHandle?.channel?.lock()
            if (midReplacement) removeStore(file)
            holdEvents.incrementAndGet()
            try {
                // The witness that this hold proved something: while it lasts, neither the store nor its
                // replacement can be read by anyone else.
                unreadableWhileHeld = !canBeRead(file) && !canBeRead(tmp)
                Thread.sleep(HOLD_MS)
            } finally {
                holdEvents.incrementAndGet()
            }
        } finally {
            fileLock?.release()
            tmpLock?.release()
            fileHandle?.close()
            tmpHandle.close()
        }
        return unreadableWhileHeld
    }

    private fun canBeRead(file: File): Boolean = runCatching { file.readText() }.isSuccess

    /**
     * Opens [file] for a hold. The writer may hold the same name for a moment while it replaces it, which the OS
     * reports as a sharing violation, so the open is retried for a short while rather than failing the holder.
     */
    private fun openForHold(file: File): RandomAccessFile {
        var last: IOException? = null
        repeat(OPEN_ATTEMPTS) {
            try {
                return RandomAccessFile(file, "rw")
            } catch (failure: IOException) {
                last = failure
                Thread.sleep(1L)
            }
        }
        throw last!!
    }

    /** Removes the store name for the mid-replacement shape, retrying while the writer has it open. */
    private fun removeStore(file: File) {
        repeat(OPEN_ATTEMPTS) {
            if (!file.exists() || file.delete()) return
            Thread.sleep(1L)
        }
        throw IOException("could not remove ${file.path} for the mid-replacement hold")
    }

    /** A write the store may refuse while it is held. The contract allows that, so the refusal is not asserted. */
    private fun writeQuietly(store: SessionStore, heartbeat: Long) {
        runCatching { store.saveAll(listOf(session("committed", heartbeat = heartbeat))) }
    }

    /** Places a snapshot file, byte-for-byte as the store writes one, at [path]. */
    private fun snapshotAt(path: File, vararg ids: String) {
        val staging = File(storeDir(), "sessions.json")
        JsonSessionStore(staging).saveAll(ids.map { session(it) })
        staging.copyTo(path, overwrite = true)
    }

    private companion object {
        /** Enough replacements that a reader racing them sees the window; small enough to stay fast. */
        const val WRITES = 400

        /** Holds of the store per reproduction run, alternating the locked and the mid-replacement shape. */
        const val HOLDS = 6

        /**
         * How long each hold lasts: twice the 250 ms look a reader gave up after before the fix, and well inside the 2 s
         * wait after it, so neither the scheduler nor the wait itself decides the outcome.
         */
        const val HOLD_MS = 500L

        /** Quiet time between holds, so the store is readable again and the writer lands a clean write. */
        const val GAP_MS = 60L

        /**
         * Longer than the 250 ms look the reader gave up after before the fix, and shorter than the 2 s wait after it.
         * A hold this long must be waited OUT. A budget that gives up sooner (250 ms, or none) fails the brief test.
         */
        const val BRIEF_HOLD_MS = 500L

        /** Attempts at opening or removing a store name for a hold, one millisecond apart. */
        const val OPEN_ATTEMPTS = 500
    }
}
