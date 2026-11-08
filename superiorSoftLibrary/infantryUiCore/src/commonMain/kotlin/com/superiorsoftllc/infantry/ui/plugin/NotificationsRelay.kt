// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.plugin

import com.superiorsoftllc.infantry.plugin.api.Notification
import com.superiorsoftllc.infantry.plugin.api.NotificationActionEvent
import com.superiorsoftllc.infantry.plugin.api.NotificationPresenter
import com.superiorsoftllc.infantry.plugin.api.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * NOTIFY-CORE-1: the plugins' way onto the window's notification bus. A plugin registers in `UiPlugin.register`, before a
 * window (and its controller's bus) exists; the relay keeps every presenter and action listener and registers them on the
 * bus [target] when the window sets it (again on a new window). A notification posted with no bus yet is only logged.
 */
class NotificationsRelay(private val say: (String) -> Unit = {}) : Notifications {
    private class Held(val seq: Long, val bind: (Notifications) -> AutoCloseable) {
        var handle: AutoCloseable? = null
    }

    private val held = MutableStateFlow<List<Held>>(emptyList())
    private val seq = MutableStateFlow(0L)
    private val current = MutableStateFlow<Notifications?>(null)

    /** The window's bus; setting it moves every held registration onto it. */
    var target: Notifications?
        get() = current.value
        set(value) {
            if (current.value === value) return
            current.value = value
            held.value.forEach { h ->
                runCatching { h.handle?.close() }
                h.handle = value?.let { runCatching { h.bind(it) }.getOrNull() }
            }
        }

    override fun notify(title: String, body: String) {
        notify(Notification(title = title, body = body))
    }

    override fun notify(notification: Notification): String {
        val bus = current.value
        if (bus == null) {
            say("[plugins] ${notification.source.ifBlank { "plugin" }} notifies (no window yet): ${notification.title} - ${notification.body}")
            return notification.id
        }
        return bus.notify(notification)
    }

    override fun dismiss(id: String) {
        current.value?.dismiss(id)
    }

    override fun present(presenterId: String, channels: Set<String>, presenter: NotificationPresenter): AutoCloseable =
        hold { it.present(presenterId, channels, presenter) }

    override fun onAction(listener: (NotificationActionEvent) -> Unit): AutoCloseable = hold { it.onAction(listener) }

    private fun hold(bind: (Notifications) -> AutoCloseable): AutoCloseable {
        var mine = 0L
        seq.update { mine = it + 1; mine }
        val h = Held(mine, bind)
        held.update { it + h }
        current.value?.let { h.handle = runCatching { bind(it) }.getOrNull() }
        return AutoCloseable {
            held.update { l -> l.filterNot { it.seq == mine } }
            runCatching { h.handle?.close() }
            h.handle = null
        }
    }
}

/**
 * NOTIFY-CORE-1: the [Notifications] service one module receives: the bus, with every notification it posts stamped with its
 * [module] name as the source (a module cannot post as another).
 */
class ModuleNotifications(private val module: String, private val bus: Notifications, private val say: (String) -> Unit = {}) : Notifications {
    override fun notify(title: String, body: String) {
        notify(Notification(title = title, body = body))
    }

    override fun notify(notification: Notification): String {
        say("[plugins] $module notifies: ${notification.title}${notification.body.takeIf { it.isNotBlank() }?.let { " - $it" } ?: ""}")
        return bus.notify(notification.copy(source = module))
    }

    override fun dismiss(id: String) = bus.dismiss(id)

    override fun present(presenterId: String, channels: Set<String>, presenter: NotificationPresenter): AutoCloseable =
        bus.present(module, channels, presenter)

    override fun onAction(listener: (NotificationActionEvent) -> Unit): AutoCloseable =
        bus.onAction { e -> if (e.source == module) listener(e) }
}
