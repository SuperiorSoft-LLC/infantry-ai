package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.session.stateDir
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable storage for mailbox messages, so a message to an offline agent is never lost and a
 * delivery failure is never lost either.
 *
 * Same shape and same reasoning as
 * [com.superiorsoftllc.agenthq.session.SessionStore]: an interface with one on-disk JSON
 * implementation, written to a `.tmp` sibling and atomically moved into place, with a corrupt file
 * treated as "no mail" so the daemon always starts. No database engine is added: the mailbox holds
 * a few hundred rows at most and is only ever listed by thread.
 *
 * CONTRACT: implementations are safe to call from several threads and must never lose a previously
 * saved message because one write failed.
 */
interface MailStore {
    /** Every persisted message, or an empty list when nothing has been stored yet. */
    fun load(): List<Message>

    /** Replace the persisted set with exactly [messages] (an upsert of the whole snapshot). */
    fun saveAll(messages: Collection<Message>)
}

/**
 * A whole-file JSON store for the mailbox. Mirrors
 * [com.superiorsoftllc.agenthq.session.JsonSessionStore] deliberately: same `.tmp` + atomic-move
 * discipline, same "corrupt is empty, not fatal" rule.
 *
 * WHY [load] RETRIES, AND WHY [saveAll] RETRIES BEFORE IT REPLACES (ticket ec738be1)
 *
 * Replacing one file in place is not atomic on Windows, whatever option is passed:
 *
 *  - `Files.move(tmp, file, ATOMIC_MOVE, REPLACE_EXISTING)` is a single `MoveFileEx` and is safe;
 *  - `Files.move(tmp, file, REPLACE_EXISTING)` -- the fallback the atomic move downgrades to -- is
 *    `DeleteFile(target)` **followed by `CopyFileEx(source, target)`**: for the whole copy the store
 *    does not exist by name, and then exists only partially.
 *
 * So a reader that asks during a replacement can be told the mailbox is empty, or be handed a
 * half-written file whose parse failure is swallowed into the same empty answer. For the session
 * store that cost resume context; here it is worse, because the mailbox is often the ONLY record of
 * an undelivered message, and a caller told "no mail" may act on that and drop it for good.
 *
 * So: a transient failure of the atomic move is RETRIED (the colliding read handle lives
 * microseconds, but a foreign reader can loop on it; see [REPLACE_BUDGET_MS]) and only a filesystem that genuinely cannot move atomically still gets the plain
 * replace. The retry is not where the guarantee lives -- [load] is: it tolerates the window whatever
 * the writer does, because a write that failed and a write in flight look alike from the outside.
 */
class JsonMailStore(private val file: File) : MailStore {

    /** The replacement this store writes before it moves it into place; also [load]'s second witness. */
    private val tmpFile: File = File(file.parentFile, file.name + ".tmp")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class Snapshot(
        val version: Int = 1,
        val messages: List<Message> = emptyList(),
    )

    /**
     * The persisted messages.
     *
     * Never answers "no mail" for a mailbox that has some: the store file is momentarily unreadable
     * while it is being replaced, so the read is retried -- and a replacement that is readable (the
     * `.tmp` it is moved from, or the only copy an interrupted replacement left behind) is served
     * instead of the gap. [load_reportsNoMailWhenNothingWasEverWritten] still holds: when nothing was
     * ever written there is nothing to serve, and the wait is brief.
     */
    @Synchronized
    override fun load(): List<Message> {
        // Nothing on disk at all: the only thing that can still appear is a write that is starting or
        // has just landed, both sub-millisecond, so the look is brief. Something on disk that is
        // unreadable is a replacement provably in flight -- its `.tmp` exists until the move lands --
        // so that one gets a longer budget for a slow or synced volume.
        val budgetMs = if (file.isFile || tmpFile.isFile) {
            UNREADABLE_LOOK_MS
        } else {
            ABSENT_LOOK_MS
        }
        val deadline = System.nanoTime() + budgetMs * 1_000_000L
        var failure: Exception? = null

        fun messagesIn(candidate: File): List<Message>? {
            if (!candidate.isFile) return null
            return try {
                // NIO, not java.io: on Windows `Files.readString` opens with FILE_SHARE_DELETE, so this read never refuses
                // the move a concurrent [saveAll] commits with. `File.readText` (java.io) does (BUG-MAILSTORE-PERSIST).
                val root = json.parseToJsonElement(Files.readString(candidate.toPath())).jsonObject
                val array = root["messages"] as? JsonArray ?: return emptyList()
                array.map { json.decodeFromJsonElement(Message.serializer(), it) }
            } catch (e: Exception) {
                failure = e
                null
            }
        }

        while (true) {
            messagesIn(file)?.let { return it }
            messagesIn(tmpFile)?.let { return it }
            if (System.nanoTime() >= deadline) break
            Thread.sleep(LOOK_POLL_MS)
        }
        failure?.let {
            System.err.println("[agentHq] could not read mail store ${file.path}: ${it.message}")
        }
        return emptyList()
    }

    @Synchronized
    override fun saveAll(messages: Collection<Message>) {
        val snapshot = Snapshot(messages = messages.sortedWith(compareBy({ it.threadId }, { it.seq })))
        val text = json.encodeToString(Snapshot.serializer(), snapshot)
        file.parentFile?.mkdirs()
        Files.writeString(tmpFile.toPath(), text)
        replaceIntoPlace()
    }

    /**
     * Move the replacement over the store, retrying a transient refusal before giving up on it.
     *
     * The retry is here because the alternative was measured to be catastrophic: the plain replace
     * DELETES the store while it copies the replacement in, so a failed atomic move used to unlink
     * every message for the duration of a file copy. Retrying keeps the file in place and readable
     * for every other reader.
     *
     * The last attempt is still the plain replace, unchanged: a filesystem that refuses to move
     * atomically has no other way to commit the write, and is an explicitly accepted trade -- [load]
     * rides out the window it opens.
     */
    private fun replaceIntoPlace() {
        val started = System.nanoTime()
        var backoff = REPLACE_FIRST_BACKOFF_MS
        var attempts = 0
        var lastRefusal: Exception? = null
        while (true) {
            attempts++
            try {
                Files.move(
                    tmpFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
                return
            } catch (_: AtomicMoveNotSupportedException) {
                // Not a transient refusal but "this filesystem has no atomic move": stop retrying.
                break
            } catch (refusal: Exception) {
                // A transient one: Windows denies a rename over a file another handle has open without
                // FILE_SHARE_DELETE (an AV scan, an indexer, any java.io reader). Such a reader can loop on the
                // file, so a few fixed 2 ms yields lost mail outright (BUG-MAILSTORE-PERSIST: 124 of 300 writes).
                // Retry with backoff until REPLACE_BUDGET_MS is spent: bounded, but long enough to land in a gap.
                lastRefusal = refusal
                val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MS
                if (elapsedMs >= REPLACE_BUDGET_MS) break
                Thread.sleep(minOf(backoff, REPLACE_BUDGET_MS - elapsedMs))
                backoff = minOf(backoff * 2, REPLACE_MAX_BACKOFF_MS)
            }
        }
        // Best effort, and the behaviour this store has always had on such a filesystem: better to
        // replace non-atomically than to leave the snapshot only in the `.tmp`. If that fails too, the
        // write is lost: say how long it was retried.
        try {
            Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Exception) {
            val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MS
            throw IOException(
                "could not replace ${file.path} after $attempts atomic attempts over ${elapsedMs}ms " +
                    "(another process holds it open?): ${failure.message}",
                failure,
            ).apply { lastRefusal?.let { addSuppressed(it) } }
        }
    }

    companion object {
        /**
         * How long [load] keeps looking when nothing is on disk, and when something is but cannot be
         * read. Neither is a correctness device -- the correctness is in looking again, whatever it
         * costs -- and both windows are sub-millisecond when measured.
         */
        private const val ABSENT_LOOK_MS = 25L
        private const val UNREADABLE_LOOK_MS = 250L
        private const val LOOK_POLL_MS = 1L

        private const val NANOS_PER_MS = 1_000_000L

        /**
         * How long [replaceIntoPlace] keeps retrying a refused atomic move before the plain fallback. Was 4 x 2 ms,
         * which a foreign java.io reader looping on the store defeated (BUG-MAILSTORE-PERSIST, same fix as
         * BUG-FLAKE1-PERSIST in JsonSessionStore). The backoff doubles from [REPLACE_FIRST_BACKOFF_MS] up to
         * [REPLACE_MAX_BACKOFF_MS]; an uncontended write never waits at all.
         */
        private const val REPLACE_BUDGET_MS = 2_000L
        private const val REPLACE_FIRST_BACKOFF_MS = 1L
        private const val REPLACE_MAX_BACKOFF_MS = 4L

        /** The daemon's default store: `mailbox.json` inside the shared AgentHQ state dir. */
        fun default(): JsonMailStore = JsonMailStore(File(stateDir(), "mailbox.json"))
    }
}
