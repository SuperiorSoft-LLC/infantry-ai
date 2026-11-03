// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board.daemon

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** The argument readers and schema pieces the board tools share, as the CLI's MCP ticket tools wrote them (descriptors stay byte for byte). */
internal object ToolArgs {
    /** A trimmed, non-blank string argument, else null. */
    fun string(args: JsonObject, key: String): String? = (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    /** A boolean argument; `"true"` as a string counts. Absent = false. */
    fun flag(args: JsonObject, key: String): Boolean =
        (args[key] as? JsonPrimitive)?.let { it.booleanOrNull ?: (it.contentOrNull?.trim() == "true") } ?: false

    fun prop(description: String) = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    fun boolProp(description: String) = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    /** TOKEN-1: the `verbose` property of a write tool: true restores the full ticket in the answer (default false = one line). */
    fun verboseProp() = boolProp("true = answer with the full ticket (description and RELATED block included). Default false: a one-line confirmation.")

    fun annotations(readOnly: Boolean, destructive: Boolean = false) = buildJsonObject {
        put("readOnlyHint", readOnly)
        put("destructiveHint", destructive)
        put("openWorldHint", false)
    }
}
