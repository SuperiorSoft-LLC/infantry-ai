package com.superiorsoftllc.agenthq.daemon.plugins

import com.superiorsoftllc.agenthq.cli.TurnLine
import com.superiorsoftllc.agenthq.cli.mcp.ProjectScope
import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.daemon.api.Caller
import com.superiorsoftllc.agenthq.daemon.api.sessions.GRANT_SESSIONS_READ
import com.superiorsoftllc.agenthq.daemon.api.sessions.GRANT_SESSIONS_TRANSCRIPT
import com.superiorsoftllc.agenthq.daemon.api.sessions.ReadScope
import com.superiorsoftllc.agenthq.daemon.api.sessions.SessionReads
import com.superiorsoftllc.agenthq.daemon.api.sessions.SessionView
import com.superiorsoftllc.agenthq.daemon.api.sessions.SessionsResult
import com.superiorsoftllc.agenthq.daemon.api.sessions.Transcript
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.FinishedPayload
import com.superiorsoftllc.agenthq.session.SessionRecord
import kotlinx.serialization.json.Json

/** RUNTIME-LOAD-5: a daemon [SessionRecord] as the API's read-only [SessionView] (no secret, no transcript). */
object SessionViews {
    fun of(r: SessionRecord) = SessionView(
        id = r.id, agentId = r.agentId, role = r.role, provider = r.provider, model = r.model, projectId = r.projectId,
        state = r.state.name, runPhase = r.runPhase, live = r.live, lastAction = r.lastAction, lastOutputAt = r.lastOutputAt,
        lastEventAt = r.lastEventAt, backgroundWork = r.backgroundWork?.label, progress = r.progress, runCount = r.runCount,
        lastExitCode = r.lastExitCode, lastError = r.lastError, ticketId = r.ticketId, workingDir = r.workingDir,
        worktreePath = r.worktreePath, worktreeBranch = r.worktreeBranch, repoRoot = r.repoRoot, spawnerSessionId = r.spawnerSessionId,
    )
}

/**
 * RUNTIME-LOAD-5 (docs/RUNTIME-LOAD.md section 11): the LOCAL binding of [SessionReads] over the daemon's own session records and
 * replay buffer. The HOST applies the project scope with the very rules of [ProjectScope] (the board API's binding uses the same),
 * seeing the caller as the daemon's record for [Caller.activeSession]: no session on an owner connection = the owner / CLI path; an
 * agent connection with no session, or a session the daemon does not know, is refused (fail closed).
 */
class LocalSessionReads(
    private val sessions: () -> List<SessionRecord>,
    /** Session [id]'s replay: the gap payload (JSON, null = none) and the retained frames; null = no history for it. */
    private val replay: (id: String) -> Replay? = { null },
    /** The active preset's refusal of a frame of this type (`sessions`, `replay_request`), or null: the read is gated like the frame. */
    private val gate: (type: String) -> String? = { null },
) : SessionReads {
    data class Replay(val gap: String?, val frames: List<Envelope>)

    private class Who(val by: Caller, val record: SessionRecord?) {
        val getenv: (String) -> String? = { name -> if (name == AccessPolicy.SESSION_ID_ENV) by.activeSession?.takeIf { it.isNotBlank() } else null }
    }

    private fun who(by: Caller, all: List<SessionRecord>): Pair<Who?, String?> {
        val id = by.activeSession?.trim()?.takeIf { it.isNotEmpty() }
        if (id == null) return if (by.agent && !by.owner) null to "an agent connection needs a session to be scoped to a project" else Who(by, null) to null
        val record = all.firstOrNull { it.id == id } ?: return null to "session '${id.take(80)}' is unknown to this daemon; a session read cannot be scoped to it"
        return Who(by.copy(activeSession = id), record) to null
    }

    private inline fun <T> op(by: Caller, frame: String = Envelope.SESSIONS, body: (List<SessionRecord>, Who) -> SessionsResult<T>): SessionsResult<T> {
        gate(frame)?.let { return SessionsResult.Refused(it) }
        val all = sessions()
        val (w, why) = who(by, all)
        return if (w == null) SessionsResult.Refused(why!!) else body(all, w)
    }

    override fun caller(by: Caller): SessionsResult<SessionView?> = op(by) { _, w -> SessionsResult.Ok(w.record?.let(SessionViews::of)) }

    override fun scope(by: Caller, requested: String?, defaultProjectId: String?): SessionsResult<ReadScope> = op(by) { all, w ->
        val s = ProjectScope.resolve(requested, all, w.getenv, defaultProjectId)
        SessionsResult.Ok(ReadScope(s.projectId, s.refusal))
    }

    override fun list(by: Caller, projectId: String?): SessionsResult<List<SessionView>> = op(by) { all, w ->
        val s = ProjectScope.resolve(projectId?.trim()?.takeIf { it.isNotEmpty() } ?: ReadScope.ALL, all, w.getenv, null)
        s.refusal?.let { return@op SessionsResult.Refused(it) }
        SessionsResult.Ok(all.filter { s.projectId == null || it.projectId == s.projectId }.map(SessionViews::of))
    }

    override fun find(ref: String, by: Caller): SessionsResult<SessionView> = op(by) { all, w ->
        when (val r = resolve(all, w, ref)) {
            is SessionsResult.Ok -> SessionsResult.Ok(SessionViews.of(r.value))
            is SessionsResult.Missing -> r
            is SessionsResult.Refused -> r
            is SessionsResult.Unavailable -> r
        }
    }

    override fun transcript(id: String, by: Caller): SessionsResult<Transcript> = op(by, REPLAY_REQUEST) { all, w ->
        when (val r = resolve(all, w, id)) {
            is SessionsResult.Ok -> SessionsResult.Ok(render(r.value.id))
            is SessionsResult.Missing -> r
            is SessionsResult.Refused -> r
            is SessionsResult.Unavailable -> r
        }
    }

    /** The ONE session [ref] names (full id, else a unique prefix), then the caller's scope: the texts the CLI's tool worded. */
    private fun resolve(all: List<SessionRecord>, w: Who, ref: String): SessionsResult<SessionRecord> {
        val requested = ref.trim()
        val matches = all.filter { it.id == requested }.ifEmpty { all.filter { it.id.startsWith(requested) } }
        val session = when (matches.size) {
            1 -> matches.single()
            0 -> return SessionsResult.Missing("no session '$requested' on this daemon")
            else -> return SessionsResult.Missing("session id '$requested' is ambiguous: ${matches.joinToString(", ") { it.id }}")
        }
        ProjectScope.foreignRefusal("session ${session.id}", session.projectId, w.record, w.getenv)?.let { return SessionsResult.Refused(it) }
        return SessionsResult.Ok(session)
    }

    /**
     * The replayed frames as readable lines, as the CLI's `agenthq_get_session` read them: `text_delta`s of one stretch joined
     * into one entry (the raw stdout line is skipped), turns by their origin, stderr / error / finish / gap marked.
     */
    private fun render(id: String): Transcript {
        val batch = replay(id) ?: return Transcript(emptyList(), "no replay history for session '$id'")
        val entries = mutableListOf<StringBuilder>()
        var lastWasDelta = false
        batch.gap?.let { entries += StringBuilder("[replay gap] earlier output was evicted: $it") }
        for (frame in batch.frames) {
            if (frame.sessionId != id) continue
            when (frame.type) {
                Envelope.TEXT_DELTA -> {
                    if (!lastWasDelta) entries += StringBuilder()
                    entries.last().append(frame.data)
                    lastWasDelta = true
                    continue
                }
                Envelope.TURN -> entries += StringBuilder(TurnLine.of(frame) + " " + frame.data.lineSequence().first().take(200))
                Envelope.STDERR -> entries += StringBuilder("[stderr] ${frame.data}")
                Envelope.ERROR -> entries += StringBuilder("[error] ${frame.data}")
                Envelope.FINISHED -> {
                    val payload = runCatching { json.decodeFromString(FinishedPayload.serializer(), frame.data) }.getOrNull()
                    entries += StringBuilder("[finished] ${payload?.outcome ?: "UNKNOWN"} exit=${payload?.exitCode ?: "?"}")
                }
                else -> Unit
            }
            lastWasDelta = false
        }
        return Transcript(entries.flatMap { it.toString().lines() }.filter { it.isNotBlank() })
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        const val REPLAY_REQUEST = "replay_request"
    }
}

/** RUNTIME-LOAD-5: [SessionReads] behind a plugin's grants (bundled / official trusted; community default deny). */
internal class GrantedSessionReads(private val reads: () -> SessionReads, private val allowed: (String) -> Boolean) : SessionReads {
    private inline fun <T> need(grant: String, call: () -> SessionsResult<T>): SessionsResult<T> =
        if (allowed(grant)) call() else SessionsResult.Refused("missing grant '$grant' in modules.json")

    override fun caller(by: Caller) = need(GRANT_SESSIONS_READ) { reads().caller(by) }
    override fun scope(by: Caller, requested: String?, defaultProjectId: String?) = need(GRANT_SESSIONS_READ) { reads().scope(by, requested, defaultProjectId) }
    override fun list(by: Caller, projectId: String?) = need(GRANT_SESSIONS_READ) { reads().list(by, projectId) }
    override fun find(ref: String, by: Caller) = need(GRANT_SESSIONS_READ) { reads().find(ref, by) }
    override fun transcript(id: String, by: Caller) = need(GRANT_SESSIONS_TRANSCRIPT) { reads().transcript(id, by) }
}
