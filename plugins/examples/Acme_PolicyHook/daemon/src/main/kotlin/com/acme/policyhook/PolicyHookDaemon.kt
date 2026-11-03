// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.acme.policyhook

import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.FrameGateHook
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * DAEMON-PLUGINS-2: a reference daemon plugin that uses the policy API and a hook.
 *
 * - hook `frameGate`: runs for every client frame (after core's own policy). It only counts frames by the catalog's action
 *   kind and answers null = pass; a gate that returned text would refuse the frame (closed on failure, see docs section 4);
 * - tool `acme_policy_check` (action `acme.policy.read`): for a frame type, the action the catalog gives it
 *   (`api.policy.actionOf`) and the active preset's refusal (`api.policy.refusal`), plus the counters.
 */
class PolicyHookDaemon : DaemonPlugin {
    private val seen = ConcurrentHashMap<String, AtomicLong>()

    override fun start(api: DaemonApi) {
        api.hooks.frameGate(FrameGateHook { frame, _ ->
            val kind = api.policy.actionOf(frame)?.kind ?: "unknown"
            seen.computeIfAbsent(kind) { AtomicLong() }.incrementAndGet()
            null
        })
        api.tools.register(PolicyCheck(api, seen))
        api.log("started")
    }

    private class PolicyCheck(private val api: DaemonApi, private val seen: Map<String, AtomicLong>) : DaemonTool {
        override val name = "acme_policy_check"
        override val description = "Reports the policy action of a frame type, whether the active preset refuses it, and how many frames of each kind the daemon saw."
        override val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("frame", buildJsonObject { put("type", "string"); put("description", "a frame type, e.g. sessions (default: sessions)") })
            })
            put("required", buildJsonArray {})
            put("additionalProperties", false)
        }

        override fun call(call: ToolCall): ToolResult {
            val type = (call.arguments["frame"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: "sessions"
            val frame = Frame(type)
            val action = api.policy.actionOf(frame)
            val refusal = api.policy.refusal(frame)
            val counts = seen.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value.get()}" }.ifEmpty { "none yet" }
            return ToolResult("$type: action=${action?.id ?: "none"} kind=${action?.kind ?: "-"}; ${refusal?.let { "refused: $it" } ?: "not refused by preset '${api.policy.active().id}'"}. Frames seen: $counts.")
        }
    }
}
