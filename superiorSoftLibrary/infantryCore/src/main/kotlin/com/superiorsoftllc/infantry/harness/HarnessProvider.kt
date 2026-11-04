// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.harness.CliHarness
import com.superiorsoftllc.infantry.harness.HarnessEvent
import com.superiorsoftllc.infantry.harness.HarnessRequest
import com.superiorsoftllc.infantry.runner.ProcessSpec

/**
 * WIRE-4: a registry harness seen as an [AgentProvider], so a spawn can name a harness id that no
 * `INFANTRY_PROVIDERS` entry configured. Only a [CliHarness] qualifies: the session manager drives an argv
 * child, and a harness with no process of its own has nothing to hand it.
 */
class HarnessProvider(private val harness: CliHarness) : AgentProvider {
    override val id: String = harness.id

    override val commands get() = harness.commands

    override fun contextLimit(): com.superiorsoftllc.infantry.harness.ContextLimitCapability = harness.contextLimit

    override fun effort(): com.superiorsoftllc.infantry.harness.EffortCapability = harness.effort

    override fun midTurnInput(): Boolean = HarnessCapability.MID_TURN_INPUT in harness.capabilities

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
    override fun sessionAdapter(): com.superiorsoftllc.infantry.session.host.HarnessSessionAdapter? = harness.sessionAdapter()

    /** HARNESS-CARRY-1: a plugin harness's own carry, through the same seam as a built-in provider's. */
    override fun conversationCarry(): com.superiorsoftllc.infantry.harness.ConversationCarry = harness.conversationCarry

    override fun sessionIdFromLine(line: String): String? =
        harness.newTranslator().translate(line).filterIsInstance<HarnessEvent.SessionStarted>().firstOrNull()?.sessionId
}
