// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugin.host

import com.superiorsoftllc.infantry.plugin.api.Notification
import com.superiorsoftllc.infantry.plugin.api.NotificationAction
import com.superiorsoftllc.infantry.plugin.api.NotificationActionEvent
import com.superiorsoftllc.infantry.plugin.api.NotificationChannels
import com.superiorsoftllc.infantry.plugin.api.NotificationLevel
import com.superiorsoftllc.infantry.plugin.api.NotificationPresenter
import com.superiorsoftllc.infantry.plugin.api.NotificationSettings
import com.superiorsoftllc.infantry.plugin.api.NotificationWire
import com.superiorsoftllc.infantry.plugin.api.Notifications
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NOTIFY-CORE-1: the bus's routing, presenter override, containment, dedupe and OS routing. */
class NotificationCenterTest {
    private class Shown : NotificationPresenter {
        val items = mutableListOf<Notification>()
        val dismissed = mutableListOf<String>()
        override fun show(notification: Notification): Boolean { items += notification; return true }
        override fun dismiss(id: String) { dismissed += id }
    }

    private var now = 1_000_000L
    private var cfg = NotificationSettings()
    private var minute: Int? = 12 * 60
    private val faults = mutableListOf<String>()
    private val core = Shown()
    private val center = NotificationCenter({ cfg }, { now }, { minute }, onFault = { id, ch, _ -> faults += "$id/$ch" }).also {
        it.setDefault(NotificationChannels.IN_APP, core)
    }

    @Test
    fun aPluginNotificationReachesTheCoreToast() {
        val asPlugin: Notifications = center
        asPlugin.notify("Acme_Hello", "loaded")
        val id = asPlugin.notify(Notification(title = "Install failed", body = "no space", level = NotificationLevel.ERROR, source = "Acme_Hello"))
        assertEquals(listOf("loaded", "no space"), core.items.map { it.body })
        assertEquals(id, core.items.last().id)
        assertEquals(NotificationChannels.CORE, center.active(NotificationChannels.IN_APP))
        assertEquals(2, center.history.value.size)
        assertEquals("Acme_Hello", center.history.value.first().notification.source)
    }

    @Test
    fun aPluginPresenterOverridesInApp() {
        val plugin = Shown()
        val handle = center.present("Fancy_Toasts", setOf(NotificationChannels.IN_APP), plugin)
        center.notify(Notification(title = "a"))
        assertEquals(listOf("a"), plugin.items.map { it.title })
        assertTrue(core.items.isEmpty())
        // The setting chooses: core forces the default, a name picks that presenter.
        cfg = cfg.copy(presenters = mapOf(NotificationChannels.IN_APP to NotificationChannels.CORE))
        center.notify(Notification(title = "b"))
        assertEquals(listOf("b"), core.items.map { it.title })
        cfg = cfg.copy(presenters = mapOf(NotificationChannels.IN_APP to "Fancy_Toasts"))
        center.notify(Notification(title = "c"))
        assertEquals(listOf("a", "c"), plugin.items.map { it.title })
        handle.close()
        center.notify(Notification(title = "d"))
        assertEquals(listOf("b", "d"), core.items.map { it.title })
    }

    @Test
    fun aThrowingPresenterFallsBackToTheCoreDefault() {
        center.present("Broken", setOf(NotificationChannels.IN_APP), NotificationPresenter { error("boom") })
        val d = center.deliver(Notification(title = "still shown"))
        assertEquals(listOf("still shown"), core.items.map { it.title })
        assertEquals(mapOf(NotificationChannels.IN_APP to NotificationChannels.CORE), d.shownOn)
        assertEquals(listOf("Broken/inApp"), faults)
        // Contained: skipped from then on, no second fault.
        center.notify(Notification(title = "next"))
        assertEquals(1, faults.size)
        assertEquals(NotificationChannels.CORE, center.active(NotificationChannels.IN_APP))
        // A presenter that cannot show one also falls back.
        center.present("Shy", setOf(NotificationChannels.IN_APP), NotificationPresenter { false })
        center.notify(Notification(title = "third"))
        assertEquals(listOf("still shown", "next", "third"), core.items.map { it.title })
    }

    @Test
    fun oneToastPerOutage() {
        repeat(5) {
            center.notify(Notification(title = "Could not reach GitHub", level = NotificationLevel.WARNING, dedupeKey = "updater.offline"))
            now += 60_000
        }
        assertEquals(1, core.items.size)
        assertEquals(4, center.history.value.single().repeats)
        // After a quiet window it shows again; the same id never shows twice.
        now += NotificationSettings.DEFAULT_DEDUPE_WINDOW_SEC * 1000L
        center.notify(Notification(title = "Could not reach GitHub", dedupeKey = "updater.offline"))
        center.notify(Notification(title = "x", id = "fixed"))
        assertTrue(center.deliver(Notification(title = "x", id = "fixed")).deduped)
        assertEquals(3, core.items.size)
        // 0 = no dedupe.
        cfg = cfg.copy(dedupeWindowSec = 0)
        center.notify(Notification(title = "y", dedupeKey = "k"))
        center.notify(Notification(title = "y", dedupeKey = "k"))
        assertEquals(5, core.items.size)
    }

    @Test
    fun osChannelRouting() {
        val os = Shown()
        center.present("Infantry_Notify", setOf(NotificationChannels.OS), os)
        center.notify(Notification(title = "info"))
        center.notify(Notification(title = "boom", level = NotificationLevel.URGENT))
        center.notify(Notification(title = "milestone", channels = listOf(NotificationChannels.OS)))
        assertEquals(listOf("boom", "milestone"), os.items.map { it.title })
        assertEquals(listOf("info", "boom"), core.items.map { it.title })
        // Routes are settings: errors to the OS too.
        cfg = cfg.copy(routes = cfg.routes + (NotificationLevel.ERROR to listOf(NotificationChannels.IN_APP, NotificationChannels.OS)))
        center.notify(Notification(title = "err", level = NotificationLevel.ERROR))
        assertEquals("err", os.items.last().title)
        // Quiet hours keep all but urgent off the OS.
        cfg = cfg.copy(quietHours = "22:00-07:00")
        minute = 23 * 60
        center.notify(Notification(title = "quiet err", level = NotificationLevel.ERROR))
        center.notify(Notification(title = "wake", level = NotificationLevel.URGENT))
        assertEquals(listOf("boom", "milestone", "err", "wake"), os.items.map { it.title })
        // No OS presenter: an OS-only notification falls back to in-app instead of vanishing.
        val bare = NotificationCenter({ NotificationSettings() }).also { it.setDefault(NotificationChannels.IN_APP, core) }
        assertNull(bare.active(NotificationChannels.OS))
        val d = bare.deliver(Notification(title = "os only", channels = listOf(NotificationChannels.OS)))
        assertEquals(mapOf(NotificationChannels.IN_APP to NotificationChannels.CORE), d.shownOn)
    }

    @Test
    fun dismissAndActions() {
        val heard = mutableListOf<NotificationActionEvent>()
        val stop = center.onAction { heard += it }
        val id = center.notify(Notification(title = "Update", source = "Infantry_Updater", actions = listOf(NotificationAction("retry", "Retry"))))
        center.action(id, "retry")
        assertEquals(listOf(NotificationActionEvent(id, "retry", "Infantry_Updater")), heard)
        center.dismiss(id)
        assertEquals(listOf(id), core.dismissed)
        assertTrue(center.history.value.single().dismissed)
        stop.close()
        center.action(id, "retry")
        assertEquals(1, heard.size)
    }

    @Test
    fun settingsParseAndValidate() {
        val s = NotificationSettings.from(mapOf(
            "notifications.route.error" to JsonPrimitive("inApp, os"),
            "notifications.route.info" to JsonPrimitive("none"),
            "notifications.presenter.os" to JsonPrimitive("Infantry_Notify"),
            "notifications.historyMax" to JsonPrimitive(5),
        ))
        assertEquals(listOf("inApp", "os"), s.route(NotificationLevel.ERROR))
        assertEquals(emptyList(), s.route(NotificationLevel.INFO))
        assertEquals("Infantry_Notify", s.presenter("os"))
        assertEquals(5, s.historyMax)
        assertTrue(NotificationSettings(quietHours = "22:00-07:00").quiet(6 * 60))
        assertFalse(NotificationSettings(quietHours = "22:00-07:00").quiet(12 * 60))
        assertEquals(JsonPrimitive("inApp,os"), NotificationSettings.validated("notifications.route.urgent", JsonPrimitive(" inApp ,os")))
        assertFailsWith<IllegalArgumentException> { NotificationSettings.validated("notifications.quietHours", JsonPrimitive("late")) }
        assertFailsWith<IllegalArgumentException> { NotificationSettings.validated("notifications.historyMax", JsonPrimitive(0)) }
        assertNull(NotificationSettings.fromSettingsFrame("""{"other":1}"""))
        val wire = NotificationWire.notification(NotificationWire.encode(Notification(title = "t", level = NotificationLevel.URGENT, ticketId = "ticket-1")))
        assertEquals(NotificationLevel.URGENT, wire?.level)
        assertEquals("ticket-1", wire?.ticketId)
    }
}
