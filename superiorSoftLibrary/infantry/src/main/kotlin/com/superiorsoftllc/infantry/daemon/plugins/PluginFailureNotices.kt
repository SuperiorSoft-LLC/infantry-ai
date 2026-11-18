// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.plugin.api.Notification
import com.superiorsoftllc.infantry.plugin.api.NotificationAction
import com.superiorsoftllc.infantry.plugin.api.NotificationLevel
import java.util.concurrent.ConcurrentHashMap

/** PLUGIN-ERR-NOTIFY-1: what kind of plugin failure a notice reports (one notice per plugin per kind per start). */
enum class PluginFailureKind(val key: String) { REFUSED("refused"), START("start"), DISABLED("disabled"), INSTALL("install") }

/**
 * PLUGIN-ERR-NOTIFY-1: turns a daemon-half failure into an error notice on the one notification bus, so every client
 * (desktop bottom-right toast, web, Android) shows it. The same plugin and kind raises once (dedupeKey, plus a local set so a
 * disabled `dedupeWindowSec = 0` setting cannot make retries repeat); "Details" re-posts the full reason.
 */
class PluginFailureNotices(private val hub: NotificationHub) {
    private val raised = ConcurrentHashMap.newKeySet<String>()
    private val reasons = ConcurrentHashMap<String, String>()

    init { hub.onAction(SOURCE) { e -> if (e.actionId == ACTION_DETAILS) details(e.notificationId) } }

    fun raise(plugin: String, kind: PluginFailureKind, reason: String): Boolean {
        val key = "plugin-failure:$plugin:${kind.key}"
        if (!raised.add(key)) return false
        val full = reason.trim()
        reasons[key] = full
        hub.post(
            Notification(
                title = "$plugin couldn't ${verb(kind)}",
                body = human(full).take(SHORT_MAX),
                level = NotificationLevel.ERROR,
                id = key,
                actions = listOf(NotificationAction(ACTION_DETAILS, "Details")),
                sticky = true,
                dedupeKey = key,
            ),
            SOURCE,
        )
        return true
    }

    /** A plugin that loads again may fail again: forget its notices (a reload or install starts a new "start"). */
    fun reset(plugin: String) { raised.removeIf { it.startsWith("plugin-failure:$plugin:") } }

    private fun details(notificationId: String) {
        val full = reasons[notificationId] ?: return
        hub.post(Notification(title = "Details: ${notificationId.removePrefix("plugin-failure:")}", body = full, level = NotificationLevel.ERROR, id = "$notificationId:details", sticky = true), SOURCE)
    }

    private fun verb(kind: PluginFailureKind) = when (kind) {
        PluginFailureKind.REFUSED, PluginFailureKind.START -> "start"
        PluginFailureKind.DISABLED -> "keep running"
        PluginFailureKind.INSTALL -> "be installed"
    }

    /** A short sentence: the missing-grant reason reads as a permission, anything else is its first line. */
    internal fun human(reason: String): String {
        val grant = Regex("missing grant '([^']+)'").find(reason)?.groupValues?.get(1)
        if (grant != null) return "It needs the '$grant' permission it didn't ask for."
        return reason.removePrefix("refused: ").removePrefix("failed: ").lineSequence().firstOrNull().orEmpty()
    }

    companion object {
        const val SOURCE = "daemon"
        const val ACTION_DETAILS = "details"
        private const val SHORT_MAX = 300
    }
}
