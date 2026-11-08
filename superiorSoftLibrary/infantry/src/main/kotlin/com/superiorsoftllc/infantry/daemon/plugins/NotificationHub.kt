// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.plugin.api.Notification
import com.superiorsoftllc.infantry.plugin.api.NotificationActionEvent
import com.superiorsoftllc.infantry.plugin.api.NotificationPresenter
import com.superiorsoftllc.infantry.plugin.api.NotificationSettings
import com.superiorsoftllc.infantry.plugin.api.NotificationWire
import com.superiorsoftllc.infantry.plugin.api.Notifications
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * NOTIFY-CORE-1 (docs/wiki/Notifications.md): the daemon side of the ONE notification bus. Daemon plugins (`DaemonApi.notifications`),
 * scripts (`ih.call("notify", ...)`) and core post here; the hub stamps the notification (id, time, source), drops a repeat (the
 * same id, or the same dedupe key within `notifications.dedupeWindowSec`, the client's rule too), and broadcasts it as
 * `notification_push` to every connected client, whose bus routes and presents it. Sticky ones are kept (at most
 * `notifications.historyMax`) and replayed to each client that connects, until a client dismisses them. A pressed action
 * (`notification_action`) reaches the listeners of the module that posted it and is published as the named event
 * `notification.action`.
 */
class NotificationHub(
    private val settings: () -> NotificationSettings,
    private val broadcast: (type: String, data: String) -> Unit,
    private val publish: (name: String, payload: String, source: String) -> Unit = { _, _, _ -> },
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private val lastByKey = HashMap<String, Long>()
    private val seen = LinkedHashMap<String, Notification>()
    private val sticky = LinkedHashMap<String, Notification>()
    private val listeners = CopyOnWriteArrayList<Pair<String, (NotificationActionEvent) -> Unit>>()

    /** Posts [notification] as [source] (a module name, `daemon`, `script:<name>`). The answer is its id; a dropped repeat answers the id too. */
    fun post(notification: Notification, source: String): String {
        val now = clock()
        val cfg = settings()
        val n = notification.copy(
            id = notification.id.ifBlank { "notification-" + UUID.randomUUID() },
            at = if (notification.at > 0) notification.at else now,
            source = source.ifBlank { notification.source.ifBlank { DAEMON_SOURCE } },
            title = notification.title.take(cfg.titleMaxChars.coerceAtLeast(1)),
            body = notification.body.take(cfg.bodyMaxChars.coerceAtLeast(1)),
        )
        val windowMs = cfg.dedupeWindowSec * MILLIS_PER_SECOND
        synchronized(lock) {
            if (seen.containsKey(n.id)) return n.id
            val key = n.dedupeKey?.takeIf { it.isNotBlank() }
            if (key != null) {
                val last = lastByKey[key]
                lastByKey[key] = now
                if (last != null && windowMs > 0 && now - last < windowMs) return n.id
            }
            seen[n.id] = n
            trim(seen, cfg.historyMax)
            if (n.sticky) { sticky[n.id] = n; trim(sticky, cfg.historyMax) }
            if (lastByKey.size > cfg.historyMax * KEY_FACTOR) lastByKey.entries.minByOrNull { it.value }?.let { lastByKey.remove(it.key) }
        }
        runCatching { broadcast(NotificationWire.PUSH, NotificationWire.encode(n)) }.onFailure { log("[notifications] broadcast failed: $it") }
        return n.id
    }

    /** The sticky notifications a newly connected client is sent (oldest first). */
    fun replay(): List<Notification> = synchronized(lock) { sticky.values.toList() }

    /** A client dismissed [id]: no longer replayed. */
    fun dismissed(id: String) {
        synchronized(lock) { sticky.remove(id) }
    }

    /** A client pressed an action: tell the posting module's listeners and publish the named event. */
    fun action(e: NotificationActionEvent) {
        val n = synchronized(lock) { seen[e.notificationId] }
        val event = e.copy(source = n?.source ?: e.source)
        listeners.forEach { (module, l) -> if (module == event.source) runCatching { l(event) }.onFailure { log("[notifications] $module action listener threw: $it") } }
        val payload = buildJsonObject {
            put("notificationId", JsonPrimitive(event.notificationId))
            put("actionId", JsonPrimitive(event.actionId))
            put("source", JsonPrimitive(event.source))
        }.toString()
        runCatching { publish(NotificationWire.EVENT_ACTION, payload, event.source.ifBlank { DAEMON_SOURCE }) }
    }

    /** [listener] hears the pressed actions of [module]'s notifications. */
    fun onAction(module: String, listener: (NotificationActionEvent) -> Unit): AutoCloseable {
        val entry = module to listener
        listeners += entry
        return AutoCloseable { listeners -= entry }
    }

    /** The bus as module [module] sees it: every post is that module's; `present` is a no-op (presenters live on the clients). */
    fun forModule(module: String, onClose: (AutoCloseable) -> Unit = {}): Notifications = object : Notifications {
        override fun notify(title: String, body: String) {
            notify(Notification(title = title, body = body))
        }
        override fun notify(notification: Notification): String = post(notification, module)
        override fun dismiss(id: String) = dismissed(id)
        override fun present(presenterId: String, channels: Set<String>, presenter: NotificationPresenter): AutoCloseable = AutoCloseable {}
        override fun onAction(listener: (NotificationActionEvent) -> Unit): AutoCloseable = onAction(module, listener).also(onClose)
    }

    private fun <V> trim(map: LinkedHashMap<String, V>, max: Int) {
        val keep = max.coerceAtLeast(1)
        while (map.size > keep) map.remove(map.keys.first())
    }

    companion object {
        const val DAEMON_SOURCE = "daemon"
        const val MILLIS_PER_SECOND = 1000L
        /** Dedupe keys kept: this many times `notifications.historyMax`. */
        const val KEY_FACTOR = 4
    }
}
