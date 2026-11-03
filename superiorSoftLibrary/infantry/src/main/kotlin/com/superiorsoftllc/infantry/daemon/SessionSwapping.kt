package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.SessionSwap
import com.superiorsoftllc.infantry.protocol.RoleIds
import com.superiorsoftllc.infantry.protocol.SessionSwapped
import com.superiorsoftllc.infantry.protocol.SwapExecutor
import com.superiorsoftllc.infantry.session.SessionManager

/**
 * UX-HARNESS-2 (`session_swap`): move ONE session that belongs to no agent onto another harness, in place. The
 * agent-level `agent_swap` owns the executor of a session bound to an agent, so such a session is refused here and
 * nothing changes. Every check runs before the write ([SessionManager.reharness] is all-or-nothing).
 *
 * HARNESS-CARRY-1: the conversation continues either way. Within one harness only the model changes; across harnesses
 * the next turn is a first run on the new CLI, seeded with the conversation so far ([SessionManager.reharness]), so
 * [SessionSwapped.newConversation] is always false.
 *
 * SWAP-AGENT-1: a [SessionSwap.role] also swaps the role the session runs as ([SessionManager.rerole]): the next turn
 * is a first turn under the new role, carrying the conversation. [roleKnown] is the daemon's role catalog; an unknown
 * role is refused before anything is written, so the harness is not switched either.
 */
fun swapSession(
    manager: SessionManager,
    request: SessionSwap,
    roleKnown: (String) -> Boolean = { true },
    /** TICKET-HANDOVER: moves a ticketed session across harnesses after a handoff turn (see [SessionHandover]). */
    handover: SessionHandover? = null,
): SessionSwapped {
    val sessionId = request.sessionId.trim()
    val provider = request.provider.trim().lowercase()
    require(provider.isNotEmpty()) { "pick a provider to switch to" }
    val before = manager.get(sessionId) ?: throw IllegalArgumentException("this session no longer exists")
    require(before.agentId.isNullOrBlank()) { "this session belongs to an agent; switch the agent instead (agent_swap)" }
    require(manager.providerFor(provider) != null) { "'${provider.take(40)}' isn't available here; pick another provider" }
    // null = keep the role; blank = no role.
    val role = request.role?.trim()?.lowercase()?.let(RoleIds::normalize)
    val roleChanges = role != null && role.ifEmpty { null } != before.role?.takeIf { it.isNotBlank() }
    if (roleChanges && !role.isNullOrEmpty()) {
        require(RoleIds.PATTERN.matches(role) && roleKnown(role)) { "there is no role '${role.take(40)}'; pick another role" }
    }
    require(!(roleChanges && before.global)) { "the Assistant's role is fixed; change its model, not its role" }
    require(!roleChanges || !before.live) { "a reply is still running; wait for it to finish or stop it, then switch" }
    require(!manager.isHeld(sessionId)) { "this session is already switching; wait for that switch to finish" }
    val crosses = manager.providerFor(provider)?.id != before.provider
    if (!roleChanges && crosses && handover?.applies(before) == true) {
        require(!before.live) { "a reply is still running; wait for it to finish or stop it, then switch" }
        manager.checkSwitch(provider, request.model, request.effort)
        handover.begin(sessionId) { SessionHandover.Target(provider, request.model, request.effort) }
        val defaultModel = runCatching { manager.providerFor(provider)?.defaultModel() }.getOrNull()?.takeIf { it.isNotBlank() }
        return SessionSwapped(
            sessionId = before.id,
            provider = manager.providerFor(provider)!!.id,
            model = request.model?.trim()?.takeIf { it.isNotEmpty() },
            defaultModel = defaultModel,
            from = SwapExecutor(before.provider, before.model, before.effort),
            newConversation = false,
            role = before.role,
            fromRole = before.role,
        )
    }
    manager.reharness(sessionId, provider, request.model, request.effort)
    val after = if (roleChanges) manager.rerole(sessionId, role) else manager.get(sessionId)!!
    val defaultModel = runCatching { manager.providerFor(after.provider)?.defaultModel() }.getOrNull()?.takeIf { it.isNotBlank() }
    return SessionSwapped(
        sessionId = after.id,
        provider = after.provider,
        model = after.model,
        defaultModel = defaultModel,
        from = SwapExecutor(before.provider, before.model, before.effort),
        newConversation = false,
        role = after.role,
        fromRole = before.role,
    )
}
