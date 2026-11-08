// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugin.api.Notification
import com.superiorsoftllc.infantry.plugin.api.NotificationAction
import com.superiorsoftllc.infantry.plugin.api.NotificationChannels
import com.superiorsoftllc.infantry.plugin.api.NotificationLevel
import com.superiorsoftllc.infantry.plugin.api.NotificationPresenter
import com.superiorsoftllc.infantry.plugin.api.NotificationWire
import com.superiorsoftllc.infantry.ui.model.ToastKind
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.net.DaemonClient
import com.superiorsoftllc.infantry.ui.plugin.ModuleNotifications
import com.superiorsoftllc.infantry.ui.plugin.NotificationsRelay
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private class NotifyLink(scope: CoroutineScope) : DaemonClient(scope) {
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    val sent = CopyOnWriteArrayList<Envelope>()
    override val frames: SharedFlow<Envelope> get() = incoming
    override suspend fun send(envelope: Envelope): Boolean { sent += envelope; return true }
    suspend fun push(e: Envelope) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 10_000.milliseconds
        while (incoming.subscriptionCount.value == 0 && deadline.hasNotPassedNow()) delay(10)
        check(incoming.tryEmit(e)) { "frame buffer full" }
    }
}

/**
 * NOTIFY-CORE-1, through the window's Controller: a plugin's notification (the module service, via the relay) and a daemon
 * push both reach the core toast; a plugin presenter takes over in-app; actions and dismissals of a pushed one go back to the
 * daemon; the dedupe key gives one toast per outage.
 */
class ControllerNotificationsTest {
    private fun waitFor(what: String, check: () -> Boolean) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 10_000.milliseconds
        while (!check() && deadline.hasNotPassedNow()) runBlocking { delay(10) }
        assertTrue(check(), what)
    }

    private fun <T> withController(block: (Controller, NotifyLink) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = NotifyLink(scope)
            return block(Controller(scope, link), link)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a plugin notification reaches the core toast, stamped with its module`() = withController { controller, _ ->
        val relay = NotificationsRelay()
        val plugin = ModuleNotifications("Acme_Hello", relay)
        relay.target = controller.notifications
        val id = plugin.notify(Notification(title = "Install failed", body = "no space", level = NotificationLevel.ERROR, source = "Other_Module"))
        val toast = controller.state.value.toasts.single()
        assertEquals(id, toast.id)
        assertEquals("Install failed", toast.title)
        assertEquals("no space", toast.body)
        assertEquals(ToastKind.ERROR, toast.kind)
        assertEquals("Acme_Hello", controller.notifications.history.value.single().notification.source, "a module cannot post as another")
        // The short form shows the module on top.
        plugin.notify("loaded", "")
        assertEquals("Acme_Hello", controller.state.value.toasts.first { it.id != id }.title)
    }

    @Test
    fun `a plugin presenter registered before the window overrides in-app, and the toast is back when it leaves`() = withController { controller, _ ->
        val relay = NotificationsRelay()
        val shown = mutableListOf<String>()
        val handle = ModuleNotifications("Fancy_Toasts", relay).present("ignored", setOf(NotificationChannels.IN_APP), NotificationPresenter { shown += it.title; true })
        relay.target = controller.notifications
        assertEquals("Fancy_Toasts", controller.notifications.active(NotificationChannels.IN_APP))
        relay.notify(Notification(title = "a"))
        assertEquals(listOf("a"), shown)
        assertTrue(controller.state.value.toasts.isEmpty())
        handle.close()
        relay.notify(Notification(title = "b"))
        assertEquals(listOf("b"), controller.state.value.toasts.map { it.title })
    }

    @Test
    fun `a daemon push is a toast, once per outage, and its action and dismissal go back to the daemon`() = withController { controller, link ->
        val pushed = Notification(title = "Could not reach GitHub", level = NotificationLevel.WARNING, id = "notification-1", source = "Infantry_Updater",
            dedupeKey = "updater.offline", actions = listOf(NotificationAction("retry", "Retry")), at = 5)
        runBlocking {
            link.push(Envelope(NotificationWire.PUSH, NotificationWire.encode(pushed)))
            link.push(Envelope(NotificationWire.PUSH, NotificationWire.encode(pushed.copy(id = "notification-2"))))
        }
        waitFor("the push is a toast") { controller.state.value.toasts.any { it.id == "notification-1" } }
        runBlocking { delay(200) }
        assertEquals(listOf("notification-1"), controller.state.value.toasts.map { it.id }, "one toast per outage")
        assertEquals(listOf("Retry"), controller.state.value.toasts.single().actions.map { it.label })

        controller.notificationAction("notification-1", "retry")
        waitFor("the action is sent") { link.sent.any { it.type == NotificationWire.ACTION } }
        val action = NotificationWire.action(link.sent.first { it.type == NotificationWire.ACTION }.data)
        assertEquals("retry", action?.actionId)
        assertEquals("Infantry_Updater", action?.source)

        controller.dismissNotification("notification-1")
        waitFor("the dismissal is sent") { link.sent.any { it.type == NotificationWire.DISMISS } }
        assertEquals("notification-1", NotificationWire.dismissId(link.sent.first { it.type == NotificationWire.DISMISS }.data))
        assertTrue(controller.state.value.toasts.isEmpty())

        // A local one is never sent to the daemon.
        val local = controller.notifications.notify(Notification(title = "local"))
        controller.dismissNotification(local)
        runBlocking { delay(100) }
        assertEquals(1, link.sent.count { it.type == NotificationWire.DISMISS })
    }
}
