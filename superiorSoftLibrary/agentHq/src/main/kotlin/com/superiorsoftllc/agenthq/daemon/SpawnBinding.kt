package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.protocol.SpawnOptions
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionRecord
import java.io.File

/**
 * BIND AT CREATION. A console that opens as an agent profile gets its session NOW, carrying the
 * profile's agent id, role and project, instead of waiting for its first prompt. Without this a
 * freshly opened agent has no session, so a colleague addressing it by profile id is told "no running
 * session" until the owner has spoken to it once (the BIND ticket).
 *
 * The session is created at rest (no prompt, no run): it is addressable and available, and the first
 * prompt or delivered message starts its first run. Returns null for options that name no profile: a
 * genuinely anonymous console stays anonymous on purpose and is created by its first prompt.
 */
fun bindSessionOnSpawn(
    manager: SessionManager,
    agents: AgentProfiles,
    options: SpawnOptions?,
    repo: File?,
): SessionRecord? {
    if (options?.agentId.isNullOrBlank() && options?.role.isNullOrBlank()) return null
    val resolved = agents.resolveSpawn(options)
    return manager.startNew(
        prompt = "",
        repo = repo,
        branchHint = resolved.agentId,
        providerId = resolved.providerId,
        model = resolved.model,
        effort = resolved.effort,
        agentId = resolved.agentId,
        role = resolved.role,
        projectId = resolved.projectId,
        instance = agents.instanceFor(resolved.agentId, resolved.role),
        contextLimit = options?.contextLimit, // CONTEXT-LIMIT-1
    )
}
