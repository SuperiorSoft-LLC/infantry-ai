// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.usage.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.usage.UsageReport
import com.superiorsoftllc.infantry.daemon.api.usage.UsageSet
import com.superiorsoftllc.infantry.daemon.api.usage.UsageText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * USAGE-OVERRIDE-GAP-1: read AND set the pacing allowance override (what `infantry usage override <meter> <n|none|clear> [hours]`
 * does) without the CLI. Who may set it is the setting [ROLES_SETTING] (default `owner,pm`; `owner` = a person with no session);
 * the role is looked up from the daemon's own session table, never taken from the caller's claim. Every set or clear is logged
 * with the caller and the reason. Without `value` it only shows the meter's allowance and any active override with its expiry.
 *
 * @param roleOf the role of session [id] as the daemon knows it (null = unknown session)
 * @param allowedRoles the roles that may set (the comma list of [ROLES_SETTING])
 * @param audit one line per change
 * @param now epoch millis
 */
class UsageOverrideTool(
    private val roleOf: (sessionId: String) -> String?,
    private val allowedRoles: () -> List<String>,
    private val audit: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : DaemonTool {
    override val name = NAME
    override val description =
        "Read or set the pacing allowance override that gates ticket starts (CLI: infantry usage override). meter: claude, opencode-go, ... " +
            "value: a worker count (\"2\"), \"none\" (no limit) or \"clear\" (back to the pacing rule); omit value to only show the meter's " +
            "allowance, any active override and when it expires. hours: how long the override lasts (default: until the meter's window " +
            "resets, else 2h). reason: why (logged). Setting needs a role in setting $ROLES_SETTING (default owner,pm); the answer shows the " +
            "new allowance and the override's expiry."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("meter", buildJsonObject { put("type", "string"); put("description", "The meter, e.g. claude.") })
            put("value", buildJsonObject { put("type", "string"); put("description", "Worker count, \"none\" or \"clear\". Omit to only read.") })
            put("hours", buildJsonObject { put("type", "number"); put("description", "How long the override lasts (default: until the window resets, else 2h).") })
            put("reason", buildJsonObject { put("type", "string"); put("description", "Why; written to the log.") })
        })
        put("required", buildJsonArray { add(JsonPrimitive("meter")) })
        put("additionalProperties", false)
    }

    override fun call(call: ToolCall): ToolResult = try {
        fun arg(k: String) = (call.arguments[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val meter = arg("meter")?.lowercase() ?: return refused("$NAME needs meter")
        val value = arg("value")?.lowercase()
        val hours = arg("hours")?.let { it.toDoubleOrNull()?.takeIf { h -> h > 0 } ?: return refused("$NAME: hours must be a number above 0") }
        val current = report(call)
        if (value == null) return ToolResult(render(current, meter, now()))
        val set = when {
            value == "clear" -> UsageSet(meter = meter, clearOverride = true)
            value == "none" || value.toIntOrNull()?.let { it >= 0 } == true -> {
                val until = if (hours != null) now() + (hours * 3_600_000L).toLong()
                else current.meters.firstOrNull { it.meter == meter }?.resetsAt?.takeIf { it > now() } ?: (now() + DEFAULT_MS)
                UsageSet(meter = meter, overrideWorkers = value.toIntOrNull(), overrideUntil = until)
            }
            else -> return refused("$NAME: value must be a worker count, \"none\" or \"clear\"")
        }
        val who = call.clientSessionId?.let { id -> "session $id (role ${roleOf(id) ?: "unknown"})" } ?: "owner (no session)"
        val role = call.clientSessionId?.let { id -> roleOf(id)?.lowercase() ?: return refused("$NAME refused: session $id is unknown") } ?: OWNER
        val allowed = allowedRoles().map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (role !in allowed) return refused("$NAME refused: role '$role' may not set the allowance (setting $ROLES_SETTING lists: ${allowed.joinToString(", ")}).")
        val reply = call.request("usage_set", UsageSet.encode(set))
        if (reply.type != "usage_report") return refused(reply.data.ifBlank { "usage_set failed" })
        val after = json.decodeFromString(UsageReport.serializer(), reply.data)
        audit("usage override: $who set $meter to ${value} ${hours?.let { "for ${it}h " } ?: ""}reason: ${arg("reason") ?: "(none given)"}")
        ToolResult(render(after, meter, now()))
    } catch (e: Exception) {
        refused(e.message ?: "$NAME failed")
    }

    private fun report(call: ToolCall): UsageReport {
        val reply = call.request("usage_get", "", timeoutMs = 45_000L)
        if (reply.type != "usage_report") throw IllegalStateException(reply.data.ifBlank { "usage_get failed" })
        return json.decodeFromString(UsageReport.serializer(), reply.data)
    }

    private fun refused(text: String) = ToolResult(text, isError = true)

    companion object {
        const val NAME = "infantry_usage_override"
        const val ROLES_SETTING = "usage.overrideRoles"
        const val DEFAULT_ROLES = "owner,pm"
        private const val OWNER = "owner"
        private const val DEFAULT_MS = 2 * 3_600_000L
        private val json = Json { ignoreUnknownKeys = true }

        /** The meter's line from the shared text, plus the override's expiry as a date when one is active. */
        fun render(report: UsageReport, meter: String, now: Long): String {
            val m = report.meters.firstOrNull { it.meter == meter } ?: return "no meter '$meter' (known: ${report.meters.joinToString { it.meter }.ifBlank { "none" }})"
            val line = UsageText.render(report.copy(meters = listOf(m))).lines().drop(1).joinToString("\n")
            val o = m.override ?: return "$line\nno override active; the pacing rule decides (allowance ${m.allowance?.toString() ?: "no limit"})."
            return "$line\noverride active: ${o.workers?.toString() ?: "no limit"} until ${java.time.Instant.ofEpochMilli(o.until)} (in ${UsageText.until(o.until, now)})."
        }
    }
}
