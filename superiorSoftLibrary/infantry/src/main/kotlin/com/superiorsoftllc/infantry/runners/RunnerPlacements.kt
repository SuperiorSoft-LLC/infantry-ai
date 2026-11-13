// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.session.SessionState
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * RUNNER-UNIFY-6 (docs/RUNNER-UNIFY.md 5.3): the orchestrator's PLACEMENT INDEX of the sessions it started on remote runners. It holds
 * only what the orchestrator itself knows about such a session: which runner it sits on, what the start pinned ([Placed]), how much of
 * the runner's stream was taken ([taken], the resume point of the pushed frames), whether its first turn still owes its role brief, and
 * the few states this daemon recorded itself (a runner event, a kill it sent, a failure reason). It builds no session record: the
 * session's record is the one its runner sends in its own `session` frames ([SessionDirectory]). It replaces RUNNER-E2E-2's
 * RemoteSessionMirror, whose synthesised records are gone.
 *
 * Persisted in [store] (`runner-sessions.json`) on every change and read back by [restore], so a daemon restart does not orphan a
 * session that lives on a runner. The file format is the mirror's, unchanged: a file written before RUNNER-UNIFY-6 loads as is, every
 * session with it. [placedOn] names a session's remote runner when it is placed (null = this daemon's own session). Generic: it names
 * no harness.
 */
class RunnerPlacements(
    private val store: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val placedOn: (sessionId: String) -> String? = { null },
) {
    /** The persisted row: the mirror's format (RUNNER-OUTPUT-1), kept field for field so an older file loads unchanged. */
    @Serializable
    private data class Kept(
        val sessionId: String, val runner: String, val provider: String = "", val model: String? = null, val repoRoot: String? = null,
        val branch: String? = null, val ticketId: String? = null, val runnerWorktree: String? = null, val projectId: String? = null,
        val agentId: String? = null, val role: String? = null, val state: String? = null, val failedWhy: String? = null,
        val firstSeen: Long? = null, val taken: Long = 0, val briefOwed: Boolean = false,
    )

    /**
     * RUNNER-TICKET-ROW-1: what the start pinned for a session: the harness, the effective [model], the orchestrator's checkout
     * [repoRoot] (where the runner's pushed ticket branch is fetched to, and where the board row reads its commit tip) and the
     * ticket [branch]. RUNNER-WORKTREE-PATH-1: [runnerWorktree] is the branch's worktree ON THE RUNNER (from the start reply).
     * RUNNER-DONE-1: [projectId], [agentId] and [role] are the start's, so the project roster lists the worker.
     */
    class Placed(
        val provider: String,
        val model: String?,
        val repoRoot: String?,
        val branch: String?,
        val ticketId: String? = null,
        val runnerWorktree: String? = null,
        val projectId: String? = null,
        val agentId: String? = null,
        val role: String? = null,
        /**
         * RUNNER-ROLE-1: the start carried no first turn (a session outside the board, bound at rest on the runner), so its role
         * brief is still owed: the orchestrator composes it into the session's first turn, exactly as for a local bound session.
         */
        val briefOwed: Boolean = false,
    )

    /** What this daemon itself recorded about a placed session: the last [state] it saw (at [at]), why it failed, when first seen. */
    class Known(val state: SessionState?, val at: Long, val failedWhy: String?, val firstSeen: Long)

    private class Seen(val state: SessionState, val at: Long)

    private val runners = ConcurrentHashMap<String, String>()
    private val takenSeq = ConcurrentHashMap<String, Long>()
    private val seen = ConcurrentHashMap<String, Seen>()
    private val firstSeen = ConcurrentHashMap<String, Long>()
    private val placedAs = ConcurrentHashMap<String, Placed>()
    private val failedWhy = ConcurrentHashMap<String, String>()
    private val owed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun placed(sessionId: String, info: Placed) {
        placedAs[sessionId] = info
        if (info.briefOwed) owed.add(sessionId)
        placedOn(sessionId)?.let { runners[sessionId] = it }
        save()
    }

    fun placement(sessionId: String): Placed? = placedAs[sessionId]

    /** The remote runner [sessionId] was placed on, as kept here; null = not placed (or this daemon's own). */
    fun runnerFor(sessionId: String): String? = runners[sessionId]

    /** Every session kept here, sorted. */
    fun ids(): List<String> = runners.keys.sorted()

    /** What this daemon recorded about [sessionId] itself; the first call fixes when it was first seen. */
    fun known(sessionId: String): Known {
        val s = seen[sessionId]
        val now = clock()
        return Known(s?.state, s?.at ?: now, failedWhy[sessionId], firstSeen.computeIfAbsent(sessionId) { now })
    }

    /** The highest runner stream sequence already taken of [sessionId] (the pushed frames resume after it). */
    fun taken(sessionId: String): Long = takenSeq[sessionId] ?: 0L

    fun taken(sessionId: String, taken: Long) {
        takenSeq[sessionId] = taken
        save()
    }

    /**
     * Read the [store] back after a restart: what each session had is kept again, and the answer maps each session to its runner,
     * for the pool's index ([com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHub.placed]). A file that does not decode
     * is left in place untouched (never overwritten by an empty index) and reported through [onUnreadable].
     */
    fun restore(onUnreadable: (String) -> Unit = { System.err.println(it) }): Map<String, String> {
        val file = store?.takeIf { it.isFile } ?: return emptyMap()
        val kept = runCatching { codec.decodeFromString(ListSerializer(Kept.serializer()), file.readText()) }.getOrElse {
            // Keep a copy aside before anything can save over it: a placement lost here is a runner session lost from the list.
            runCatching { file.copyTo(File(file.parentFile, file.name + ".unreadable"), overwrite = true) }
            onUnreadable("[infantry] ${file.name} could not be read (${it.message}); kept as ${file.name}.unreadable")
            return emptyMap()
        }
        for (k in kept) {
            runners[k.sessionId] = k.runner
            placedAs[k.sessionId] = Placed(k.provider, k.model, k.repoRoot, k.branch, k.ticketId, k.runnerWorktree, k.projectId, k.agentId, k.role, k.briefOwed)
            k.state?.let { st -> runCatching { SessionState.valueOf(st) }.getOrNull() }?.let { seen[k.sessionId] = Seen(it, clock()) }
            k.failedWhy?.let { failedWhy[k.sessionId] = it }
            k.firstSeen?.let { firstSeen[k.sessionId] = it }
            takenSeq[k.sessionId] = k.taken
            if (k.briefOwed) owed.add(k.sessionId)
        }
        return kept.associate { it.sessionId to it.runner }
    }

    @Synchronized
    private fun save() {
        val file = store ?: return
        val kept = runners.keys.sorted().map { id ->
            val p = placedAs[id]
            Kept(
                id, runners.getValue(id), p?.provider.orEmpty(), p?.model, p?.repoRoot, p?.branch, p?.ticketId, p?.runnerWorktree,
                p?.projectId, p?.agentId, p?.role, seen[id]?.state?.name, failedWhy[id], firstSeen[id], takenSeq[id] ?: 0L, id in owed,
            )
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(codec.encodeToString(ListSerializer(Kept.serializer()), kept))
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** RUNNER-ROLE-1: [sessionId]'s first turn must still carry its role brief ([Placed.briefOwed]). */
    fun owesBrief(sessionId: String): Boolean = sessionId in owed

    /** RUNNER-ROLE-1: the runner took [sessionId]'s first turn with its brief; later turns go as typed. */
    fun briefed(sessionId: String) {
        if (owed.remove(sessionId)) save()
    }

    /** RUNNER-SESSION-GHOST-1: [sessionId] was deleted: what this index kept about it goes too. */
    fun forget(sessionId: String) {
        placedAs.remove(sessionId); seen.remove(sessionId); firstSeen.remove(sessionId); failedWhy.remove(sessionId)
        runners.remove(sessionId); takenSeq.remove(sessionId); owed.remove(sessionId)
        save()
    }

    /** A runner event, or this daemon itself (a kill it sent), moved [sessionId] to [state] (a SessionState name; others ignored). */
    fun status(sessionId: String, state: String) {
        if (!runners.containsKey(sessionId)) return // not placed here: this daemon's own session, or one already forgotten
        val s = runCatching { SessionState.valueOf(state.trim().uppercase()) }.getOrNull() ?: return
        val before = seen[sessionId]?.state
        seen[sessionId] = Seen(s, clock())
        if (before != s) save()
    }

    /** GREEN-GAP-1 / RUNNER-UNIFY-5: why [sessionId] FAILED on its runner, as its frames carry it; the failure card shows it. */
    fun failed(sessionId: String, reason: String) {
        if (reason.isNotBlank()) failedWhy[sessionId] = reason.trim()
        save()
    }

    /** A turn of [sessionId] ended: the session rests until its next status says otherwise. */
    fun finished(sessionId: String) {
        val cur = seen[sessionId]?.state
        if (cur == null || cur == SessionState.BUSY || cur == SessionState.STARTING) { seen[sessionId] = Seen(SessionState.IDLE, clock()); save() }
    }

    companion object {
        /** The provider word of a placed session whose start pinned none, before its runner's first record. */
        const val REMOTE_PROVIDER = "remote"

        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }
}
