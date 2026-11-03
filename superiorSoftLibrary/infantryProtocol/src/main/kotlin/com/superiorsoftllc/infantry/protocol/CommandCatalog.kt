// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.protocol

import com.superiorsoftllc.infantry.harness.HarnessCommand
import kotlinx.serialization.Serializable

/**
 * CP-4: ask for the `/` commands a client can offer. `provider` names whose catalog; absent, the envelope's session
 * decides, and absent that, the daemon's default provider. `prefix` is what the user has typed after the slash so far
 * (a leading `/` is tolerated): a bare word filters command names, `"effort hi"` narrows that command's values.
 */
@Serializable
data class CommandCatalogRequest(
    val provider: String? = null,
    val prefix: String = "",
) {
    companion object {
        fun decode(text: String): CommandCatalogRequest =
            if (text.isBlank()) CommandCatalogRequest() else StrictJson.decode(serializer(), text, "command_catalog payload")
    }
}

/** The answer: the provider whose catalog this is, the prefix it was filtered by, and the matching commands. */
@Serializable
data class CommandCatalogAnswer(
    val provider: String,
    val prefix: String,
    val commands: List<HarnessCommand>,
)

/**
 * CP-4 slice (d) (contract 1.39.0): the answer to a `prompt` that ran a `/` command against a session. [ok] is true
 * when the command took effect; [command] is the bare name that ran, [message] says what happened in a sentence a
 * client can show. A command that is refused is NOT a result: it is an `error` frame, as before.
 */
@Serializable
data class CommandResultAnswer(
    val ok: Boolean,
    val command: String,
    val message: String,
)
