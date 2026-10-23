package com.superiorsoftllc.agenthq.session

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Durable storage for sessions, so a daemon restart does not lose them.
 *
 * The store is deliberately an INTERFACE with one on-disk implementation. The Phase 3 need is
 * "survive a restart", not "query sessions", and a single daemon writes a handful of rows, so the
 * implementation is a JSON file ([JsonSessionStore]) and **no database engine is added**.
 *
 * WHY NOT SQLITE YET, and when it changes: SQLite becomes the right default once sessions are
 * *queried* rather than merely listed -- filtering by state, joining to worktrees, history per
 * workspace. That is Phase 4 territory (worktree isolation), which will also raise the write rate.
 * Until then a DB engine would be a dependency (and, on Windows, a JDBC driver bundle) paid for with
 * no query to justify it. Because the seam is already an interface, swapping in a SQLite/Exposed
 * implementation later touches this file plus the construction site, not [SessionManager].
 *
 * CONTRACT: implementations must be safe to call from several threads (stdout and stderr are drained
 * on separate threads) and must never lose a previously saved session because one write failed.
 *
 * And one promise to READERS, which [com.superiorsoftllc.agenthq.session.SessionManager.loadAndReconcile]
 * is built on: a snapshot that reached the disk is never reported as "this store has no sessions".
 * Replacing a file is not atomic on every platform (see [JsonSessionStore]), so a read that lands while
 * a write is being placed must be retried rather than answered from the gap -- the caller reads this
 * store ONCE, at startup, and a false empty there starts a daemon with no sessions at all.
 */
interface SessionStore {
    /**
     * All persisted sessions, or an empty list when nothing has been stored yet. A stored snapshot that exists but
     * cannot be read is never answered as an empty list: it throws [SessionStoreUnreadableException].
     */
    fun load(): List<SessionRecord>

    /** Replace the persisted set with exactly [sessions] (an upsert of the whole snapshot). */
    fun saveAll(sessions: Collection<SessionRecord>)
}

/**
 * A session store that EXISTS but cannot be read. This is a different answer from "no sessions" (an empty
 * list), and the two must never be conflated: a caller that treats an unreadable store as empty starts with
 * nothing and then replaces the store with its own snapshot, which destroys every session it could not read.
 *
 * Thrown by [JsonSessionStore.load] once its look budget is spent with something on disk that will not read,
 * and by [JsonSessionStore.saveAll] when asked to replace a store this instance could not read.
 */
class SessionStoreUnreadableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Where AgentHQ keeps its durable state (the session store today).
 *
 * Default `~/.agenthq`, overridable with `AGENTHQ_STATE_DIR` so a test or a second instance can point
 * somewhere else (PKG-1: a system property of the same name wins, see [agentHqSetting]). It is stable across restarts by construction -- that is the whole point of the
 * store -- and lives outside the repo so a `git clean` cannot erase live sessions.
 */
fun stateDir(getenv: (String) -> String? = { agentHqSetting(it) }): File {
    val override = getenv("AGENTHQ_STATE_DIR")
    val dir = if (!override.isNullOrBlank()) File(override) else File(System.getProperty("user.home"), ".agenthq")
    dir.mkdirs()
    return dir
}

/**
 * A whole-file JSON store. Small, dependency-free, and honest about being single-file: it writes a
 * `.tmp` sibling and moves it into place so a crash mid-write cannot truncate the real file.
 *
 * A store that is on disk but cannot be read is NOT "no sessions": [load] throws
 * [SessionStoreUnreadableException] once its wait is spent, the caller still starts (with no sessions in
 * memory), and this instance never overwrites a store it could not read (see [load] and [saveAll]).
 *
 * ONE LIMIT, NAMED RATHER THAN HIDDEN: a stale negative directory view lasting longer than ABSENT_CONFIRM_MS for
 * BOTH names still reports no-sessions with no trace. That is the absent branch of [load]. The measurement on record
 * is a typical case, not a bound, and nothing in this file removes the limit (see the note on ABSENT_CONFIRM_MS).
 *
 * WHY [load] RETRIES, AND WHY [saveAll] RETRIES BEFORE IT REPLACES (ticket d52bbf40)
 *
 * Replacing one file in place is not atomic on Windows, whatever option is passed:
 *
 *  - `Files.move(tmp, file, ATOMIC_MOVE, REPLACE_EXISTING)` is a single `MoveFileEx` (JDK source,
 *    `WindowsFileCopy.move`) and is safe;
 *  - `Files.move(tmp, file, REPLACE_EXISTING)` -- the fallback the atomic move downgrades to -- is
 *    `DeleteFile(target)` **followed by `CopyFileEx(source, target)`**: for the whole copy the store
 *    does not exist by name, and then exists only partially. Measured with a 20k-write probe on
 *    NTFS/JDK 25: 62% of a concurrent reader's reads saw no file at all, and 67% when the same probe
 *    ran the fallback only.
 *
 * A reader merely holding the file open is enough to make the atomic move fail with a sharing
 * violation -- measured: 2570 of 4000 writes, when a reader hammered the store. So the old blanket
 * `catch (_: Exception) { plain replace }` turned a READ into the deletion of every session, and any
 * reader in that window -- a daemon restarting over the store, or the very test that read the file a
 * moment earlier -- was told the store was empty. `SessionManager.resume` answers that with
 * "unknown session <id>", which is exactly the flake this ticket exists for.
 *
 * So: a transient failure of the atomic move is RETRIED (the colliding read handle lives
 * microseconds, but a foreign reader can loop on it; see [REPLACE_BUDGET_MS]) and only a filesystem that genuinely cannot move atomically
 * still gets the plain replace. The retry is not where the guarantee lives -- [load] is: it tolerates
 * the window whatever the writer does, because a write that failed and a write in flight look alike
 * from the outside.
 */
class JsonSessionStore(private val file: File) : SessionStore {

    /** The replacement this store writes before it moves it into place; also [load]'s second witness. */
    private val tmpFile: File = File(file.parentFile, file.name + ".tmp")

    /**
     * Set when [load] could not read a store that exists; [saveAll] refuses to replace that store. Only a later
     * successful [load] clears it, and nothing calls load after startup, so the refusal holds for the life of this
     * instance. That is intended: the sessions on disk are not in memory, and replacing the store would destroy them.
     */
    private var unreadableAtLoad: SessionStoreUnreadableException? = null

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    private data class Snapshot(
        val version: Int = 1,
        val sessions: List<SessionRecord> = emptyList(),
    )

    /**
     * The persisted sessions: an empty list only when nothing has ever been stored here, never a false empty for
     * a store that exists.
     *
     * The store file is momentarily invisible or unreadable while it is replaced, and it stays unreadable for as
     * long as another party holds it open (an antivirus scan, an indexer, a backup). So a read keeps looking while
     * there is something to wait for: [ABSENT_CONFIRM_MS] when nothing is on disk, and [UNREADABLE_BUDGET_MS] from
     * the first sighting of something that will not read. A readable store, or its `.tmp` sibling (the replacement
     * in flight, the second witness), is served at once. When the wait is spent with something on disk that cannot
     * be read, this throws [SessionStoreUnreadableException] instead of answering "no sessions": a caller that
     * starts from an empty answer would replace the store with its own snapshot and destroy what it could not read.
     */
    @Synchronized
    override fun load(): List<SessionRecord> {
        val started = System.nanoTime()
        var firstSighting = -1L
        var failure: Exception? = null
        while (true) {
            // A look is fresh when it begins at or after the instant the wait ran out, so the verdict below never
            // rests on a read taken before the wait was spent.
            val lookStart = System.nanoTime()
            for (candidate in arrayOf(file, tmpFile)) {
                if (!candidate.isFile) continue
                if (firstSighting < 0) firstSighting = System.nanoTime()
                try {
                    val sessions = readSnapshot(candidate)
                    unreadableAtLoad = null
                    return sessions
                } catch (e: Exception) {
                    failure = e
                }
            }
            val deadline = if (firstSighting < 0) {
                started + ABSENT_CONFIRM_MS * NANOS_PER_MS
            } else {
                firstSighting + UNREADABLE_BUDGET_MS * NANOS_PER_MS
            }
            if (lookStart >= deadline) break
            Thread.sleep(LOOK_POLL_MS)
        }
        // Nothing was ever on disk for the whole wait: a store that was never written, which is honestly empty.
        val unread = failure ?: return emptyList()
        val error = SessionStoreUnreadableException(
            "session store ${file.path} exists but could not be read: ${unread.message}",
            unread,
        )
        unreadableAtLoad = error
        System.err.println("[agentHq] ${error.message}. Starting with no sessions; the store will not be overwritten.")
        throw error
    }

    /** One read of [candidate]. Throws when it cannot be read, or does not parse as a snapshot. */
    private fun readSnapshot(candidate: File): List<SessionRecord> {
        // NIO, not java.io: on Windows `Files.readString` opens with FILE_SHARE_DELETE, so this read never refuses the
        // move a concurrent [saveAll] commits with. `File.readText` (java.io) does refuse it (BUG-FLAKE1-PERSIST).
        val root = json.parseToJsonElement(Files.readString(candidate.toPath())) as JsonObject
        val array = root["sessions"] as? JsonArray ?: return emptyList()
        // ORIGIN-SCRUB-1a: a session saved under a retired role id reads back under the id that replaced it.
        return array.map { json.decodeFromJsonElement(SessionRecord.serializer(), it) }
            .map { r -> com.superiorsoftllc.agenthq.role.RoleIds.normalizeOrNull(r.role).let { if (it == r.role) r else r.copy(role = it) } }
    }

    @Synchronized
    override fun saveAll(sessions: Collection<SessionRecord>) {
        // A store this instance could not read is never replaced by it: the snapshot on disk holds sessions this
        // instance does not know about, and replacing it would destroy them. The refusal is an explicit failure, and
        // the caller (SessionManager.writeAll) carries on in memory, as it does for any failed write.
        unreadableAtLoad?.let {
            throw SessionStoreUnreadableException(
                "refusing to overwrite session store ${file.path}: it could not be read when it was loaded",
                it,
            )
        }
        val sorted = sessions.sortedBy { it.spawnedAt }
        // Manually encode using SessionRecordSerializer for each session to include the computed `live` field
        val sessionsElements = sorted.map { json.encodeToJsonElement(SessionRecordSerializer, it) }
        val sessionsJson = JsonArray(sessionsElements)
        val snapshotJson = JsonObject(mapOf(
            "version" to JsonPrimitive(1),
            "sessions" to sessionsJson,
        ))
        val text = json.encodeToString(snapshotJson)
        file.parentFile?.mkdirs()
        Files.writeString(tmpFile.toPath(), text)
        replaceIntoPlace()
    }

    /**
     * Move the replacement over the store, retrying a transient refusal before giving up on it.
     *
     * The retry is here because the alternative was measured to be catastrophic: the plain replace
     * DELETES the store while it copies the replacement in, so a failed atomic move used to unlink
     * every session for the duration of a file copy. A retrying write keeps the file in place and the
     * store readable for every other reader (a backup, a second daemon mid-restart, a human).
     *
     * The last attempt is still the plain replace, unchanged: a filesystem that refuses to move
     * atomically (a synced folder, a different volume) has no other way to commit the write, and is an
     * explicitly accepted trade -- [load] rides out the window it opens.
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
                // file, so a few fixed 2 ms yields lost writes outright (BUG-FLAKE1-PERSIST: 89-92 in 3 s).
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
        // write is lost: say how long it was retried. The caller (SessionManager.writeAll) logs a failing
        // write ONCE per failure streak, so this is not printed here as well.
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
         * How long [load] keeps looking, with nothing on disk, before it answers "no sessions". 250 ms is the margin
         * set by the review gate; the figure before this ticket was 25 ms. It is a margin, not a measured bound.
         *
         * THE RESIDUAL, NAMED: a stale negative directory view lasting longer than ABSENT_CONFIRM_MS for BOTH names
         * still reports no-sessions with no trace. The one measurement on record (1428 absences under a hammering
         * reader, each served again within one poll of a millisecond) is a typical case from one probe. It does not
         * bound the tail, so the limit is stated here rather than retired.
         */
        private const val ABSENT_CONFIRM_MS = 250L

        /**
         * How long [load] keeps looking, from its first sighting of something on disk that will not read, before it
         * reports [SessionStoreUnreadableException]. It is sized to outlast a scan or a sync that holds the store
         * open, which the 250 ms it replaces could not. Waiting costs a startup delay; answering empty costs the
         * sessions, so the wait is the cheaper side of that trade.
         */
        private const val UNREADABLE_BUDGET_MS = 2_000L

        /** One look per millisecond while waiting. */
        private const val LOOK_POLL_MS = 1L

        private const val NANOS_PER_MS = 1_000_000L

        /**
         * How long [replaceIntoPlace] keeps retrying a refused atomic move before the plain fallback. Was 4 x 2 ms,
         * which a foreign java.io reader looping on the store defeated (BUG-FLAKE1-PERSIST). The backoff doubles from
         * [REPLACE_FIRST_BACKOFF_MS] up to [REPLACE_MAX_BACKOFF_MS]; an uncontended write never waits at all.
         */
        private const val REPLACE_BUDGET_MS = 2_000L
        private const val REPLACE_FIRST_BACKOFF_MS = 1L
        private const val REPLACE_MAX_BACKOFF_MS = 4L

        /** The daemon's default store: `sessions.json` inside [stateDir]. */
        fun default(): JsonSessionStore = JsonSessionStore(File(stateDir(), "sessions.json"))
    }
}
