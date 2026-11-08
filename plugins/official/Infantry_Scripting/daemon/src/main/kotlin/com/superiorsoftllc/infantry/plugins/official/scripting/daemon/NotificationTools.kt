// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.plugin.api.Notification
import com.superiorsoftllc.infantry.plugin.api.NotificationAction
import com.superiorsoftllc.infantry.plugin.api.NotificationLevel
import com.superiorsoftllc.infantry.plugin.api.Notifications
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * NOTIFY-CORE-1 (docs/wiki/Notifications.md): `infantry_notification_post` (script alias `notify`): a script or agent posts one
 * notification on the core bus (`DaemonApi.notifications`), the same bus UI plugins and daemon plugins use. The clients route
 * it by level (`notifications.route.<level>`) and the active presenter of each channel shows it.
 * Script: `ih.call("notify", {title="Build failed", level="error", dedupeKey="build"})`.
 */
class NotificationTools(private val bus: () -> Notifications) {
    val tool: DaemonTool = object : DaemonTool {
        override val name = NAME
        override val description = "Post a notification on the core notification bus: an in-app toast (and the OS notification for urgent ones, " +
            "per the notifications.route.<level> settings). level info | success | warning | error | urgent. dedupeKey drops repeats within " +
            "notifications.dedupeWindowSec (one toast per outage). Script: ih.call(\"notify\", {title=..., body=..., level=\"error\"})."
        override val inputSchema: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{
            "title":{"type":"string"},"body":{"type":"string"},
            "level":{"type":"string","description":"info (default) | success | warning | error | urgent"},
            "id":{"type":"string","description":"a fixed id: the same id is shown once"},
            "dedupeKey":{"type":"string","description":"repeats with this key within the dedupe window are dropped"},
            "sticky":{"type":"boolean","description":"stays until dismissed"},
            "timeoutMs":{"type":"integer","description":"how long the toast shows (0 = the default)"},
            "channels":{"type":"array","items":{"type":"string"},"description":"inApp and/or os; default: the level's route"},
            "actions":{"type":"array","items":{"type":"object","properties":{"id":{"type":"string"},"label":{"type":"string"}}}},
            "ticketId":{"type":"string"},"sessionId":{"type":"string"},"projectId":{"type":"string"}},
            "required":["title"]}""",
        ).jsonObject
        override fun call(call: ToolCall): ToolResult = handle(call)
    }

    fun handle(call: ToolCall): ToolResult {
        val a = call.arguments
        fun s(k: String) = (a[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val title = s("title") ?: return ToolResult("needs title", isError = true)
        val levelText = s("level")?.lowercase()
        val level = NotificationLevel.of(levelText)
        if (levelText != null && level == NotificationLevel.INFO && levelText != NotificationLevel.INFO.wire) {
            return ToolResult("level must be info, success, warning, error or urgent (got '$levelText')", isError = true)
        }
        val actions = (a["actions"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            NotificationAction(id, (o["label"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: id)
        }
        val n = Notification(
            title = title,
            body = s("body").orEmpty(),
            level = level,
            id = s("id").orEmpty(),
            source = SOURCE,
            actions = actions,
            sticky = (a["sticky"] as? JsonPrimitive)?.booleanOrNull ?: false,
            timeoutMs = (a["timeoutMs"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0,
            dedupeKey = s("dedupeKey"),
            channels = (a["channels"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { c -> c.isNotEmpty() } },
            ticketId = s("ticketId"),
            sessionId = s("sessionId") ?: call.clientSessionId,
            projectId = s("projectId") ?: call.clientProjectId,
        )
        val id = runCatching { bus().notify(n) }.getOrElse { return ToolResult("notification failed: ${it.message}", isError = true) }
        return ToolResult("""{"ok":true,"id":${JsonPrimitive(id)}}""")
    }

    companion object {
        const val NAME = "infantry_notification_post"
        const val SOURCE = "script"
    }
}
