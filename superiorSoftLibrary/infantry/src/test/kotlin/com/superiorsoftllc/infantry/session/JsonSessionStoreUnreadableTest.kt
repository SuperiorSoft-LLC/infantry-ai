package com.superiorsoftllc.infantry.session

import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The distinction the store has to make, pinned by name: "nothing on disk" is an empty list, and "something on disk
 * that will not read" is [SessionStoreUnreadableException]. Before the window fix both answered emptyList(), so a
 * caller could not tell a fresh install from a store it had failed to read.
 *
 * This file names the new exception, so it lives apart from [JsonSessionStoreTest]: that file must still compile
 * against the base code, where the exception does not exist, and it is the file the base-code RED is run from.
 */
class JsonSessionStoreUnreadableTest {

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

    /**
     * The distinction the store has to make: "nothing on disk" is an empty list, "something on disk that will not
     * read" is [SessionStoreUnreadableException]. Before this ticket both answered emptyList(), so a caller could
     * not tell a fresh install from a store it had failed to read.
     */
    @Test
    fun aStoreThatStaysUnreadableIsReportedAsUnreadableNotAsEmpty() {
        val dir = storeDir()
        val file = File(dir, "sessions.json")
        JsonSessionStore(file).saveAll(listOf(session("committed")))

        val answer = runWhileLocked(file) { JsonSessionStore(file).load() }

        assertTrue(
            answer.exceptionOrNull() is SessionStoreUnreadableException,
            "a store that is on disk but cannot be read must be reported as unreadable, not as an empty store: got $answer",
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
}
