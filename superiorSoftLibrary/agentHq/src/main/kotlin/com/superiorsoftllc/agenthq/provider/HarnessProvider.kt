package com.superiorsoftllc.agenthq.provider

import com.superiorsoftllc.agenthq.harness.CliHarness
import com.superiorsoftllc.agenthq.harness.HarnessEvent
import com.superiorsoftllc.agenthq.harness.HarnessRequest
import com.superiorsoftllc.agenthq.runner.ProcessSpec

/**
 * WIRE-4: a registry harness seen as an [AgentProvider], so a spawn can name a harness id that no
 * `AGENTHQ_PROVIDERS` entry configured. Only a [CliHarness] qualifies: the session manager drives an argv
 * child, and a harness with no process of its own has nothing to hand it.
 */
class HarnessProvider(private val harness: CliHarness) : AgentProvider {
    override val id: String = harness.id

    override val commands get() = harness.commands

    override fun contextLimit(): com.superiorsoftllc.agenthq.harness.ContextLimitCapability = harness.contextLimit

    override fun effort(): com.superiorsoftllc.agenthq.harness.EffortCapability = harness.effort

    override fun spec(request: RunRequest): ProcessSpec {
        val spec = harness.processSpec(
            HarnessRequest(
                prompt = request.prompt,
                harnessId = id,
                project = request.workingDir?.path.orEmpty(),
                model = request.model,
                sessionId = request.resumeSessionId,
                effort = request.effort.takeIf { harness.effort.applies },
                toolApproval = request.toolApproval,
                contextLimit = request.contextLimit.takeIf { harness.contextLimit.applies },
            ),
        )
        // The harness's spec builder takes no directory; the session's own working dir still applies.
        return if (spec.workingDir == null && request.workingDir != null) spec.copy(workingDir = request.workingDir) else spec
    }

    /** BUG-M2-11b-1: a plugin harness's persistent mode, through the same seam as a built-in provider's. */
    override fun sessionAdapter(): com.superiorsoftllc.agenthq.session.host.HarnessSessionAdapter? = harness.sessionAdapter()

    /** HARNESS-CARRY-1: a plugin harness's own carry, through the same seam as a built-in provider's. */
    override fun conversationCarry(): com.superiorsoftllc.agenthq.harness.ConversationCarry = harness.conversationCarry

    override fun sessionIdFromLine(line: String): String? =
        harness.newTranslator().translate(line).filterIsInstance<HarnessEvent.SessionStarted>().firstOrNull()?.sessionId
}
