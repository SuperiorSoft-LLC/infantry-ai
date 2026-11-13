// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionRecordSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * RUNNER-UNIFY-4 (docs/RUNNER-UNIFY.md 5.3): the orchestrator's ONE session directory. Every caller that used to merge
 * `manager.list() + remoteMirror.list()` (or look up `manager.get(id) ?: remoteMirror.get(id)`) reads [list] / [get] here.
 *
 * What it holds: this daemon's own sessions ([local] / [localGet], the in-process runner) and, for each session the pool placed on a
 * remote runner ([placed]), the record THAT RUNNER sent in its own `session` frames ([accept]): its real working dir (`/work/...`,
 * not the orchestrator's checkout), harness, model, state, error and conversation. Only the orchestrator's own facts are laid over
 * it ([overlay]): the project, agent, role and ticket the start pinned, and the orchestrator checkout the runner's ticket branch is
 * fetched to (where the board row reads its commit tip).
 *
 * RUNNER-UNIFY-6: the only path (the flag is gone). A placed session whose runner has sent no record yet (the moment right after a
 * start, or a runner that is down) is still listed: it reads a minimal record from what the [placements] index knows (the start's
 * harness, project, role, ticket and the runner's worktree; STARTING, or the last state this daemon recorded), so a session is never
 * dropped from the list for want of a frame. The received records are kept in [store] so a daemon restart serves them at once.
 * Generic: it names no harness.
 */
class SessionDirectory(
    private val local: () -> List<SessionRecord>,
    private val localGet: (String) -> SessionRecord?,
    private val placed: () -> List<String>,
    /** The placement index: what each start pinned, the states this daemon recorded, the owed role briefs (RUNNER-ROLE-1). */
    private val placements: RunnerPlacements,
    private val store: File? = null,
) {
    @Serializable
    private data class Kept(val runner: String, val record: JsonElement)

    private class Received(val runner: String, val record: SessionRecord)

    private val received = ConcurrentHashMap<String, Received>()

    init { restore() }

    /** Every session of this daemon: its own, then those on runners. */
    fun list(): List<SessionRecord> = local() + remote()

    /** One session, wherever it runs; null = unknown here. */
    fun get(id: String): SessionRecord? = localGet(id) ?: remoteGet(id)

    /** The sessions placed on remote runners only. */
    fun remote(): List<SessionRecord> = placed().map(::remoteRecord)

    /** One session placed on a remote runner; null = not placed (or a local session). */
    fun remoteGet(id: String): SessionRecord? = if (id in placed()) remoteRecord(id) else null

    /** The runner's own record of [id], when one arrived; what the directory serves for it. */
    fun received(id: String): SessionRecord? = received[id]?.record

    /**
     * A `session` frame of [runner] about one of the sessions placed there: [data] is the runner's record as it serves it. A frame
     * that does not decode, or names another session than [sessionId], is ignored (what was served stands). True = taken.
     */
    fun accept(runner: String, sessionId: String, data: String): Boolean {
        val record = runCatching { codec.decodeFromString(SessionRecordSerializer, data) }.getOrNull() ?: return false
        if (record.id != sessionId) return false
        received[sessionId] = Received(runner, record)
        save()
        return true
    }

    /**
     * RUNNER-ROLE-1: [id]'s first turn, composed with its brief because its record read [SessionRecord.neverRun], was taken. A local
     * session counts its own run; a placed one stops owing its brief here, so the next turn goes as typed.
     */
    fun briefed(id: String) {
        if (localGet(id) == null) placements.briefed(id)
    }

    /** [sessionId] was deleted: the record the runner sent goes too. */
    fun forget(sessionId: String) {
        if (received.remove(sessionId) != null) save()
    }

    private fun remoteRecord(id: String): SessionRecord = rawRemoteRecord(id).let { briefState(it, placements.owesBrief(id)) }

    private fun rawRemoteRecord(id: String): SessionRecord {
        val known = placements.known(id)
        val r = received[id]?.record ?: return placeholder(id, placements.placement(id), known)
        val out = overlay(r, placements.placement(id))
        // A runner that went quiet sends no more frames: an end this daemon recorded itself (a kill it sent, a runner lost) stands.
        val ended = known.state?.takeIf { it in ENDED }
        return if (ended != null && out.state !in ENDED) out.copy(state = ended, lastError = known.failedWhy ?: out.lastError) else out
    }

    /**
     * A placed session its runner has sent no record of yet: listed from the placement alone, so it is never lost from the list. Its
     * working dir is the runner's worktree when the start reply named one (never the orchestrator's checkout: the `C:\` bug).
     */
    private fun placeholder(id: String, p: RunnerPlacements.Placed?, known: RunnerPlacements.Known): SessionRecord {
        val state = known.state ?: com.superiorsoftllc.infantry.session.SessionState.STARTING
        return SessionRecord(
            id = id, provider = p?.provider?.takeIf { it.isNotBlank() } ?: RunnerPlacements.REMOTE_PROVIDER, model = p?.model,
            workingDir = p?.runnerWorktree.orEmpty(), repoRoot = p?.repoRoot, worktreePath = p?.runnerWorktree, worktreeBranch = p?.branch,
            ticketId = p?.ticketId, projectId = p?.projectId, agentId = p?.agentId, role = p?.role, spawnedAt = known.firstSeen,
            lastHeartbeat = known.at, state = state,
            lastError = if (state == com.superiorsoftllc.infantry.session.SessionState.FAILED) known.failedWhy else null,
            // A ticket start's brief went over the link with the start: the board treats it as briefed (briefState decides the rest).
            providerSessionId = RunnerPlacements.REMOTE_PROVIDER, runCount = 1,
        )
    }

    /**
     * RUNNER-ROLE-1: a placed session reads [SessionRecord.neverRun] exactly while it owes its brief, so every first-turn site
     * composes it as it does for a local bound session (one rule, no branch on where the session runs). One that owes nothing is
     * never read as never-run (a runner record not yet refreshed after the first turn would brief it twice).
     */
    private fun briefState(r: SessionRecord, owed: Boolean): SessionRecord = when {
        owed && r.state in AT_REST ->
            r.copy(providerSessionId = null, runCount = 0, state = com.superiorsoftllc.infantry.session.SessionState.IDLE)
        !owed && r.neverRun -> r.copy(runCount = 1)
        else -> r
    }

    private fun overlay(r: SessionRecord, p: RunnerPlacements.Placed?): SessionRecord = if (p == null) r else r.copy(
        projectId = p.projectId ?: r.projectId,
        agentId = p.agentId ?: r.agentId,
        role = p.role ?: r.role,
        ticketId = p.ticketId ?: r.ticketId,
        repoRoot = p.repoRoot ?: r.repoRoot,
        worktreeBranch = r.worktreeBranch ?: p.branch,
    )

    private fun restore() {
        val file = store?.takeIf { it.isFile } ?: return
        val kept = runCatching { codec.decodeFromString(ListSerializer(Kept.serializer()), file.readText()) }.getOrElse { return }
        for (k in kept) {
            val rec = runCatching { codec.decodeFromJsonElement(SessionRecordSerializer, k.record) }.getOrNull() ?: continue
            received[rec.id] = Received(k.runner, rec)
        }
    }

    @Synchronized
    private fun save() {
        val file = store ?: return
        val kept = received.entries.sortedBy { it.key }.map { (_, v) -> Kept(v.runner, codec.encodeToJsonElement(SessionRecordSerializer, v.record)) }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(codec.encodeToString(ListSerializer(Kept.serializer()), kept))
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private companion object {
        val codec = Json { ignoreUnknownKeys = true; encodeDefaults = false }
        /** A placed session not yet run: STARTING before the runner's first record, or the runner's own IDLE. */
        val AT_REST = setOf(com.superiorsoftllc.infantry.session.SessionState.STARTING, com.superiorsoftllc.infantry.session.SessionState.IDLE)
        val ENDED = setOf(com.superiorsoftllc.infantry.session.SessionState.EXITED, com.superiorsoftllc.infantry.session.SessionState.FAILED, com.superiorsoftllc.infantry.session.SessionState.ORPHANED)
    }
}
