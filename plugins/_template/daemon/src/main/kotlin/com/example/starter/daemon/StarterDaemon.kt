// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.example.starter.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The template's daemon half: one tool, `example_sessions`, declared in module.json `daemon.tools` (see README.md). To read
 * as the caller instead (through every gate the caller's own frames pass), use `call.request("<frame>", data)`.
 */
class StarterDaemon : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.tools.register(SessionCount(api))
    }

    private class SessionCount(private val api: DaemonApi) : DaemonTool {
        override val name = "example_sessions"
        override val description = "How many sessions the daemon runs."
        override val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
            put("required", buildJsonArray {})
            put("additionalProperties", false)
        }
        override val annotations = buildJsonObject { put("readOnlyHint", true) }

        override fun call(call: ToolCall): ToolResult = ToolResult("The daemon runs ${api.sessions.list().size} session(s).")
    }
}
