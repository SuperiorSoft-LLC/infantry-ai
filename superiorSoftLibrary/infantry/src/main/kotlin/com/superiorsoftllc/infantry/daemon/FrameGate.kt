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
    )

    /** Why a frame of [type] is refused on this connection, or null when it may be dispatched. */
    fun refusal(type: String, owner: Boolean, pluginOwnerOnly: Boolean = false): String? = when {
        !owner && (type in OWNER_ONLY || pluginOwnerOnly) -> "${shown(type)} refused: it needs the owner credential (${OwnerCredential.HEADER}); agents do not hold it"
        else -> null
    }

    /** A client-chosen type is echoed printable and at most [SHOWN_TYPE_CHARS] characters long. */
    private fun shown(type: String): String = type.filter { it in ' '..'~' }.take(SHOWN_TYPE_CHARS)

    private const val SHOWN_TYPE_CHARS = 64
}
