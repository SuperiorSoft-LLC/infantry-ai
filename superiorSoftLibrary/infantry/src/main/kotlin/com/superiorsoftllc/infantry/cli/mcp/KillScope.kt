package com.superiorsoftllc.infantry.cli.mcp

import com.superiorsoftllc.infantry.daemon.AccessPolicy
import com.superiorsoftllc.infantry.session.SessionRecord

/**
 * The session rules `infantry session rm|kill` apply to the CLI caller. The MCP tools' own copy of the kill rule is the host's
 * (`Spawns.mayKill`, docs/RUNTIME-LOAD.md section 13); this is the CLI's, evaluated from the caller's environment.
 */
internal object KillScope {

    /** The prefix of the daemon's `session_delete` refusal, dropped where the caller names its own command. */
    const val DELETE_REFUSED = "session_delete refused: "

    /** SESSION-RM-1: the one refusal for removing a running session, shared by `infantry session rm` and the `infantry_session_remove` tool. */
    fun liveRefusal(session: SessionRecord) =
        "session ${session.id} is still running (${session.state}); kill it first: call infantry_session_remove with force=true " +
            "(CLI: infantry session rm ${session.id} --force) to kill and remove it in one step"

    /**
     * Contract 1.114.0: who may kill [session], or null when the caller may. No session id (the owner / CLI): any session. A
     * privileged role (pm): any session in its own project. Every other role: the sessions it spawned, matched by the recorded
     * spawner session id. No session, privileged or not, may kill ITSELF.
     */
    fun refusal(session: SessionRecord, snapshot: List<SessionRecord>, getenv: (String) -> String?): String? {
        val callerId = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (session.id == callerId) {
            return "session ${session.id} is this session itself; an agent may not kill its own session. " +
                "End your turn instead, or ask the owner to stop it."
        }
        if (session.spawnerSessionId == callerId) return null
        val caller = ProjectScope.callerOf(snapshot, getenv)
        if (ProjectScope.isPrivileged(caller, true)) {
            // The project rule for kill is stricter than reading: even a pm reaches only its OWN project.
            val own = caller?.projectId ?: getenv(AccessPolicy.PROJECT_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
            if (own == null || session.projectId == own) return null
            return "session ${session.id} belongs to project '${session.projectId ?: "none"}', not this session's project '$own'; " +
                "a pm may only kill sessions in its own project. Ask a pm of that project to do it."
        }
        return "session ${session.id} was not spawned by this session ($callerId); a ${caller?.role ?: "worker"} may only kill sessions it " +
            "spawned itself. Ask the pm to do it: infantry send pm \"please remove session ${session.id}\""
    }
}
