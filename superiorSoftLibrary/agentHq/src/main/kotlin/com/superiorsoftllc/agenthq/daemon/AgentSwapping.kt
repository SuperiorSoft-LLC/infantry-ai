package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.protocol.AgentSwap
import com.superiorsoftllc.agenthq.protocol.AgentSwapped
import com.superiorsoftllc.agenthq.protocol.SpawnOptions
import com.superiorsoftllc.agenthq.protocol.SwapExecutor
import com.superiorsoftllc.agenthq.protocol.SwapSession
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionRecord
import java.io.File

/**
 * Role-first S3 (contract 1.47.0, `agent_swap`; docs/AGENTHQ-ROLE-FIRST.md 4.1-4.2): move one agent to another
 * executor and decide what happens to its current session.
 *
 * All validation first, then writes: the agent, the executor ([AgentProfiles.validateExecutor]) and the session mode
 * are checked inside [AgentProfiles.swap] BEFORE the profile is written, so a refusal ([IllegalArgumentException])
 * leaves the profile and every session exactly as they were. The agent's current session is derived as everywhere
 * else: its run in flight, else its latest session.
 *
 * | current session  | harness changes | `auto`                              |
 * |------------------|-----------------|-------------------------------------|
 * | none             | -               | `none`                              |
 * | idle / finished  | no              | `kept` (next run: new model/effort) |
 * | idle / finished  | yes             | `kept` (moved onto the new harness, conversation carried: HARNESS-CARRY-1) |
 * | running          | no              | `kept` (the run in flight finishes on the old executor) |
 * | running          | yes             | `none` (nothing interrupted; a new session comes with the next spawn or mail to the agent, see MailRouter) |
 *
 * `keep` is refused when the harness changes (an explicit `keep` promises the same CLI conversation); `auto` across
 * harnesses keeps the SESSION instead and carries its conversation to the new CLI ([SessionManager.reharness]); `new`
 * always cuts a fresh session bound to the agent, at rest, on the new executor. With no session at all `keep` answers `none`.
 */
fun swapAgent(manager: SessionManager, agents: AgentProfiles, request: AgentSwap, repo: File?): AgentSwapped {
    val mode = request.session.trim().lowercase()
    require(mode in AgentSwap.SESSION_MODES) { "session '${mode.take(20)}' is not one of ${AgentSwap.SESSION_MODES}" }

    var from = SwapExecutor()
    var current: SessionRecord? = null
    var outcome = SwapSession.NONE
    var moves = false
    val swap = agents.swap(request.agentId, request.provider, request.model, request.effort) { before, after ->
        val old = agents.executorFor(before, null, null, null)
        val next = agents.executorFor(after, null, null, null)
        val session = currentSession(manager, before.id!!)
        val oldHarness = session?.provider ?: old.provider ?: manager.providerId
        val newHarness = next.provider ?: manager.providerId
        val crosses = oldHarness != newHarness
        require(!(mode == AgentSwap.KEEP && crosses)) {
            "session 'keep' cannot cross harnesses ('$oldHarness' -> '$newHarness'): a conversation belongs to one harness; use 'auto' or 'new'"
        }
        from = SwapExecutor(old.provider ?: manager.providerId, old.model, old.effort)
        current = session
        outcome = when {
            mode == AgentSwap.NEW -> SwapSession.NEW
            session == null -> SwapSession.NONE
            crosses -> if (session.live) SwapSession.NONE else SwapSession.KEPT.also { moves = true }
            else -> SwapSession.KEPT
        }
    }

    // The profile is stored; now the session consequence.
    val stored = swap.after
    val sessionId = when (outcome) {
        SwapSession.KEPT -> {
            val next = agents.executorFor(stored, null, null, null)
            if (moves) manager.reharness(current!!.id, next.provider ?: manager.providerId, next.model, next.effort).id
            else manager.retarget(current!!.id, next.model, next.effort).id
        }
        SwapSession.NEW -> bindSessionOnSpawn(manager, agents, SpawnOptions(agentId = stored.id, projectId = stored.projectId), repo)?.id
        else -> null
    }
    return AgentSwapped(stored, from, SwapSession(outcome, sessionId))
}

/** The agent's current session: the one in flight, else the latest it started. Null = it has none. */
internal fun currentSession(manager: SessionManager, agentId: String): SessionRecord? {
    val mine = manager.list().filter { it.agentId == agentId }.sortedByDescending { it.spawnedAt }
    return mine.firstOrNull { it.live } ?: mine.firstOrNull()
}
