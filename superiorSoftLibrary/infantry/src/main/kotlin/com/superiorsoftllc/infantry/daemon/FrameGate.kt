// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope

/**
 * SEC-FIX-1 (contract 1.136.0): the ONE gate every client frame passes before the WebSocket dispatch, a bare text
 * frame included (it decodes as a `prompt`): the frames in [OWNER_ONLY] (saved commands, secrets, run control) need an
 * owner connection ([OwnerCredential]). A refusal is an `error` frame naming the frame type and the rule, and nothing runs.
 *
 * DAEMON-PLUGINS-1: the plain read-only frame list this gate once applied under an env lockdown with no preset installed is
 * gone: a lockdown is a policy preset ([com.superiorsoftllc.infantry.policy.PolicyRuntime]); one pinned but not installed is
 * fail closed there, and a plugin's owner-only frames are checked by the plugin host.
 */
object FrameGate {
    /** `replay_request` is a frame the daemon reads before its dispatch (a session's stream replay). */
    const val REPLAY_REQUEST = "replay_request"

    val OWNER_ONLY: Set<String> = setOf(
        Envelope.COMMAND_SAVE, Envelope.COMMAND_DELETE,
        Envelope.RUN_START, Envelope.RUN_STOP, Envelope.RUN_ATTACH, Envelope.RUN_RESIZE, Envelope.RUN_INPUT,
        Envelope.SECRET_SET, Envelope.SECRET_CLEAR, Envelope.SECRET_IMPORT,
        Envelope.SECRET_FIELD_SET, Envelope.SECRET_FIELD_DELETE, Envelope.SECRET_DELETE, Envelope.SECRET_MOVE,
        // PLUGIN-ENDPOINTS-2 (1.138.0): the one frame that hands a stored value back.
        Envelope.PLUGIN_SECRET_GET,
        // RUNNER-POOL-6a: `infantry runner pair|add|rotate|detach <name>` through the daemon (pairing, credentials).
        com.superiorsoftllc.infantry.runners.RunnerLinkService.ADMIN,
    )

    /** Why a frame of [type] is refused on this connection, or null when it may be dispatched. */
    fun refusal(type: String, owner: Boolean, pluginOwnerOnly: Boolean = false): String? = when {
        !owner && (type in OWNER_ONLY || pluginOwnerOnly) -> "${shown(type)} refused: it needs the owner credential (${OwnerCredential.HEADER}); agents do not hold it"
        else -> null
    }

    /**
     * RUNNER-POOL-5 (docs/RUNNER-POOL.md section 7): the caller seat of a frame that arrived over the runner link, presented with
     * a runner-scoped pair credential: `orchestrator:<id>`. It is never the owner and never an agent.
     */
    const val ORCHESTRATOR_SEAT_PREFIX = "orchestrator:"

    /** RUNNER-POOL-5: the runner link frame family; the ONLY frames a runner-scoped credential may call. */
    const val RUNNER_LINK_PREFIX = "runner_link_"

    fun orchestratorSeat(orchestratorId: String): String = ORCHESTRATOR_SEAT_PREFIX + orchestratorId

    /**
     * RUNNER-POOL-5: why a frame of [type] is refused for the caller [seat]. An `orchestrator:<id>` seat (a runner credential)
     * may call only the [RUNNER_LINK_PREFIX] family: every other frame, owner frames (settings, plugins, keystore, updater,
     * remote) included, is refused. A link frame from any other seat is refused too: the family is reached only over the link.
     * Other seats fall through to [refusal] as a non-owner.
     */
    fun refusal(type: String, seat: String, pluginOwnerOnly: Boolean = false): String? {
        val link = type.startsWith(RUNNER_LINK_PREFIX)
        return when {
            seat.startsWith(ORCHESTRATOR_SEAT_PREFIX) && !link ->
                "${shown(type)} refused: a runner credential (seat ${shown(seat)}) may call only the runner link frames"
            !seat.startsWith(ORCHESTRATOR_SEAT_PREFIX) && link ->
                "${shown(type)} refused: runner link frames are accepted only over the runner link from the paired orchestrator"
            link -> null
            else -> refusal(type, owner = seat == OWNER_SEAT, pluginOwnerOnly = pluginOwnerOnly)
        }
    }

    const val OWNER_SEAT = "owner"

    /** A client-chosen type is echoed printable and at most [SHOWN_TYPE_CHARS] characters long. */
    private fun shown(type: String): String = type.filter { it in ' '..'~' }.take(SHOWN_TYPE_CHARS)

    private const val SHOWN_TYPE_CHARS = 64
}
