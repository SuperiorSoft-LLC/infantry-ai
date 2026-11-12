// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * RUNNER-E2E-2: the orchestrator's read-only view of the sessions it started on remote runners. The runner owns the process; the
 * pool indexes which session sits on which runner ([sessionsOn]); the runner's status events ([status]) say what state each is in.
 * A session with no event yet reads as [SessionState.STARTING]. The session list, the board's worker check and the log replay read
 * this instead of finding nothing (which the board reported as "worker gone"). Generic: it names no harness.
 *
 * [sessionIds] says which session ids sit on remote runners now; [clock] is the epoch millis source.
 *
 * RUNNER-OUTPUT-1: with a [store] file, what the mirror keeps (the runner of each session, its placement, last state and how much
 * of its runner transcript the orchestrator already took) is written there on every change and read back by [restore], so a
 * daemon restart does not orphan a session that lives on a runner. [runnerOf] names a session's runner when it is written.
 */
class RemoteSessionMirror(
    private val sessionIds: () -> List<String>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val store: File? = null,
    private val runnerOf: (sessionId: String) -> String? = { null },
) {
    @Serializable
    private data class Kept(
        val sessionId: String, val runner: String, val provider: String = "", val model: String? = null, val repoRoot: String? = null,
        val branch: String? = null, val ticketId: String? = null, val runnerWorktree: String? = null, val projectId: String? = null,
        val agentId: String? = null, val role: String? = null, val state: String? = null, val failedWhy: String? = null,
        val firstSeen: Long? = null, val taken: Int = 0,
    )

    private val runners = ConcurrentHashMap<String, String>()
    private val takenLines = ConcurrentHashMap<String, Int>()

    private class Seen(val state: SessionState, val at: Long)

    private val seen = ConcurrentHashMap<String, Seen>()
    private val firstSeen = ConcurrentHashMap<String, Long>()
    private val placedAs = ConcurrentHashMap<String, Placed>()
    private val failedWhy = ConcurrentHashMap<String, String>()

    /**
     * RUNNER-TICKET-ROW-1: what the start pinned for [sessionId]: the harness, the effective [model], the orchestrator's checkout
     * [repoRoot] (where the runner's pushed ticket branch is fetched to, and where the board row reads its commit tip) and the
     * ticket [branch]. Without it the board row showed no commit, an empty worktree and the harness's default model.
     * RUNNER-WORKTREE-PATH-1: [runnerWorktree] is the branch's worktree ON THE RUNNER (from the runner's start reply), the record's
     * worktreePath; readers label it with the record's runner. Null (an older runner) leaves worktreePath unset, as before.
     * RUNNER-DONE-1: [projectId], [agentId] and [role] are the start's, so the project roster (`agents --live`) lists the worker.
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
    )

    fun placed(sessionId: String, info: Placed) {
        placedAs[sessionId] = info
        runnerOf(sessionId)?.let { runners[sessionId] = it }
        save()
    }

    /** RUNNER-OUTPUT-1: [sessionId]'s runner transcript lines the orchestrator already put on its own stream. */
    fun taken(sessionId: String): Int = takenLines[sessionId] ?: 0

    fun taken(sessionId: String, lines: Int) {
        takenLines[sessionId] = lines
        save()
    }

    /**
     * RUNNER-OUTPUT-1: read the [store] back after a restart: what each session had is kept again, and the answer maps each
     * session to its runner, for the pool's index ([com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHub.placed]).
     */
    fun restore(): Map<String, String> {
        val file = store?.takeIf { it.isFile } ?: return emptyMap()
        val kept = runCatching { codec.decodeFromString(ListSerializer(Kept.serializer()), file.readText()) }.getOrElse { return emptyMap() }
        for (k in kept) {
            runners[k.sessionId] = k.runner
            placedAs[k.sessionId] = Placed(k.provider, k.model, k.repoRoot, k.branch, k.ticketId, k.runnerWorktree, k.projectId, k.agentId, k.role)
            k.state?.let { st -> runCatching { SessionState.valueOf(st) }.getOrNull() }?.let { seen[k.sessionId] = Seen(it, clock()) }
            k.failedWhy?.let { failedWhy[k.sessionId] = it }
            k.firstSeen?.let { firstSeen[k.sessionId] = it }
            takenLines[k.sessionId] = k.taken
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
                p?.projectId, p?.agentId, p?.role, seen[id]?.state?.name, failedWhy[id], firstSeen[id], takenLines[id] ?: 0,
            )
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(codec.encodeToString(ListSerializer(Kept.serializer()), kept))
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
    }

    fun placement(sessionId: String): Placed? = placedAs[sessionId]

    /** RUNNER-SESSION-GHOST-1: [sessionId] was deleted: what this mirror kept about it goes too. */
    fun forget(sessionId: String) {
        placedAs.remove(sessionId); seen.remove(sessionId); firstSeen.remove(sessionId); failedWhy.remove(sessionId)
        runners.remove(sessionId); takenLines.remove(sessionId)
        save()
    }

    /** A runner event moved [sessionId] to [state] (a SessionState name; an unknown word is ignored). */
    fun status(sessionId: String, state: String) {
        val s = runCatching { SessionState.valueOf(state.trim().uppercase()) }.getOrNull() ?: return
        val before = seen[sessionId]?.state
        seen[sessionId] = Seen(s, clock())
        if (before != s) save()
    }

    /** GREEN-GAP-1: why [sessionId] FAILED on its runner (the runner's own error line); the failure card shows it instead of "no reason". */
    fun failed(sessionId: String, reason: String) {
        if (reason.isNotBlank()) failedWhy[sessionId] = reason.trim()
        save()
    }

    /** A turn of [sessionId] ended: the session rests until its next status says otherwise. */
    fun finished(sessionId: String) {
        val cur = seen[sessionId]?.state
        if (cur == null || cur == SessionState.BUSY || cur == SessionState.STARTING) { seen[sessionId] = Seen(SessionState.IDLE, clock()); save() }
    }

    fun get(sessionId: String): SessionRecord? = if (sessionId in sessionIds()) record(sessionId) else null

    fun list(): List<SessionRecord> = sessionIds().map(::record)

    private fun record(id: String): SessionRecord {
        val now = clock()
        val s = seen[id]
        val p = placedAs[id]
        return SessionRecord(
            id = id, provider = p?.provider ?: REMOTE_PROVIDER, model = p?.model, workingDir = p?.repoRoot.orEmpty(), repoRoot = p?.repoRoot,
            worktreePath = p?.runnerWorktree, worktreeBranch = p?.branch, ticketId = p?.ticketId,
            projectId = p?.projectId, agentId = p?.agentId, role = p?.role, spawnedAt = firstSeen.computeIfAbsent(id) { now },
            lastHeartbeat = s?.at ?: now, state = s?.state ?: SessionState.STARTING,
            lastError = if (s?.state == SessionState.FAILED) failedWhy[id] else null,
            // The brief went over the link with the start; the runner resumes its own conversation, so the board treats it as briefed.
            providerSessionId = REMOTE_PROVIDER, runCount = 1,
        )
    }

    companion object {
        /** The provider word of a mirrored record: the runner's harness is not known here. */
        const val REMOTE_PROVIDER = "remote"

        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }
}
