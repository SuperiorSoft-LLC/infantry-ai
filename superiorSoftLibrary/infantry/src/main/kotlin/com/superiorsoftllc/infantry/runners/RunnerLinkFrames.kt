// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.FrameGate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * RUNNER-POOL-5: the runner link frame family (`runner_link_*`, [FrameGate.RUNNER_LINK_PREFIX]). Each request is one
 * `Envelope(type, data = JSON, requestId)` over the link WebSocket; the answer is [REPLY] (data = the answer JSON) or [ERROR]
 * (data = the reason), echoing the request id. The credential travels in the connection's [CREDENTIAL_HEADER] and the caller's id
 * in [ORCHESTRATOR_HEADER], never in a frame body; [PAIR] is the only frame accepted without them.
 *
 * Wire contract between machines on different app versions: [CONTRACT] is exchanged by [HELLO]; a later step adds operations as
 * minor bumps (docs/RUNNER-POOL.md section 10).
 */
object RunnerLinkFrames {
    const val CONTRACT = "1.0.0"
    const val PATH = "/runner-link"
    const val CREDENTIAL_HEADER = "X-Infantry-Runner-Credential"
    const val ORCHESTRATOR_HEADER = "X-Infantry-Orchestrator"

    const val PAIR = "runner_link_pair"
    const val HELLO = "runner_link_hello"
    const val REKEY = "runner_link_rekey"
    const val DETACH = "runner_link_detach"
    const val START = "runner_link_start"
    const val STOP = "runner_link_stop"
    const val REPLY = "runner_link_reply"
    const val ERROR = "runner_link_error"

    @Serializable
    data class PairRequest(val code: String, val orchestratorId: String, val orchestratorName: String = "")

    @Serializable
    data class PairAnswer(val runnerId: String, val runnerName: String, val credential: String, val contract: String = CONTRACT)

    @Serializable
    data class HelloAnswer(val runnerId: String, val runnerName: String, val contract: String = CONTRACT, val issuedAt: Long)

    @Serializable
    data class RekeyAnswer(val credential: String, val issuedAt: Long)

    /** A remote start: the subset of `StartSpec` an orchestrator may name. A runner path (`repo`) is never taken from the wire. */
    @Serializable
    data class StartRequest(
        val startId: String,
        val projectId: String? = null,
        val provider: String? = null,
        val model: String? = null,
        val role: String? = null,
        val agentId: String? = null,
        val effort: String? = null,
        val ticketId: String? = null,
    )

    @Serializable
    data class StartAnswer(val sessionId: String? = null, val refusal: String? = null)

    @Serializable
    data class StopRequest(val sessionId: String, val reason: String? = null, val force: Boolean = false, val ticketId: String? = null)

    @Serializable
    data class StopAnswer(val stopped: Boolean)

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}
