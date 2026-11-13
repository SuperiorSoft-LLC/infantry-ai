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
 * CONTINGENCY (`runners.unifiedDirectory`, default on, [enabled], read live): off, or a placed session whose runner has sent no record yet (pushed
 * frames off, an older runner, the moment right after a start), reads the [legacy] record, exactly as before. The received records are
 * kept in [store] so a daemon restart does not fall back to the mirror for sessions whose runner is quiet. Generic: it names no harness.
 */
class SessionDirectory(
    private val local: () -> List<SessionRecord>,
    private val localGet: (String) -> SessionRecord?,
    private val placed: () -> List<String>,
    private val legacy: (String) -> SessionRecord?,
    private val placement: (String) -> RemoteSessionMirror.Placed? = { null },
    private val enabled: () -> Boolean = { true },
    private val store: File? = null,
    /** RUNNER-ROLE-1: a placed session whose first turn still owes its role brief ([RemoteSessionMirror.owesBrief]). */
    private val owesBrief: (String) -> Boolean = { false },
    /** RUNNER-ROLE-1: that first turn was taken ([RemoteSessionMirror.briefed]). */
    private val markBriefed: (String) -> Unit = {},
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
    fun remote(): List<SessionRecord> = placed().mapNotNull(::remoteRecord)

    /** One session placed on a remote runner; null = not placed (or a local session). */
    fun remoteGet(id: String): SessionRecord? = if (id in placed()) remoteRecord(id) else null

    /** The runner's own record of [id], when one arrived; what the directory serves for it with the flag on. */
    fun received(id: String): SessionRecord? = received[id]?.record

    /**
     * A `session` frame of [runner] about one of the sessions placed there: [data] is the runner's record as it serves it. A frame
     * that does not decode, or names another session than [sessionId], is ignored (the mirror's record stands). True = taken.
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
        if (localGet(id) == null) markBriefed(id)
    }

    /** [sessionId] was deleted: the record the runner sent goes too. */
    fun forget(sessionId: String) {
        if (received.remove(sessionId) != null) save()
    }

    private fun remoteRecord(id: String): SessionRecord? = rawRemoteRecord(id)?.let { briefState(it, owesBrief(id)) }

    private fun rawRemoteRecord(id: String): SessionRecord? {
        val r = received[id]?.record
        if (r == null || !enabled()) return legacy(id)
        val out = overlay(r, placement(id))
        // A runner that went quiet sends no more frames: an end this daemon recorded itself (a kill it sent, a runner lost) stands.
        val l = legacy(id)
        return if (l != null && l.state in ENDED && out.state !in ENDED) out.copy(state = l.state, lastError = l.lastError ?: out.lastError) else out
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

    private fun overlay(r: SessionRecord, p: RemoteSessionMirror.Placed?): SessionRecord = if (p == null) r else r.copy(
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
        /** A placed session not yet run: the mirror's STARTING before the runner's first record, or the runner's own IDLE. */
        val AT_REST = setOf(com.superiorsoftllc.infantry.session.SessionState.STARTING, com.superiorsoftllc.infantry.session.SessionState.IDLE)
        val ENDED = setOf(com.superiorsoftllc.infantry.session.SessionState.EXITED, com.superiorsoftllc.infantry.session.SessionState.FAILED, com.superiorsoftllc.infantry.session.SessionState.ORPHANED)
    }
}
