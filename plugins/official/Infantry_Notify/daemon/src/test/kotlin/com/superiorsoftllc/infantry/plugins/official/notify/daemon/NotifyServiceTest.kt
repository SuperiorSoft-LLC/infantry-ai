// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notify.daemon

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.time.LocalTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NOTIFY-1: argument parsing, queue-then-deliver, milestone fires once, nothing fires without the mark, the settings. */
class NotifyServiceTest {
    private val dir: File = Files.createTempDirectory("notify-test").toFile()
    private var settings = NotifySettings()
    private var clock = 1_000_000L
    private var time = LocalTime.NOON
    private val pushed = mutableListOf<NotifyMessage>()

    private fun service() = NotifyService(dir, { settings }, { pushed += it }, now = { clock }, localTime = { time })

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    @Test fun `parse needs a title and checks every argument`() {
        assertTrue(NotifyRequest.parse(buildJsonObject {}).isFailure)
        assertTrue(NotifyRequest.parse(buildJsonObject { put("title", "  ") }).isFailure)
        val ok = NotifyRequest.parse(buildJsonObject {
            put("title", "Build done"); put("body", "b"); put("level", "URGENT"); put("sound", "chime"); put("target", "android"); put("ticketId", "ticket-1")
        }).getOrThrow()
        assertEquals(NotifyRequest("Build done", "b", "urgent", "chime", "android", "ticket-1"), ok)
        assertTrue(NotifyRequest.parse(buildJsonObject { put("title", "t"); put("level", "loud") }).isFailure)
        assertTrue(NotifyRequest.parse(buildJsonObject { put("title", "t"); put("target", "tv") }).isFailure)
        assertTrue(NotifyRequest.parse(buildJsonObject { put("title", "t"); put("sound", "../x.wav") }).isFailure)
        assertTrue(NotifyRequest.parse(buildJsonObject { put("title", "t"); put("url", "file:///c") }).isFailure)
        assertTrue(NotifyRequest.parse(buildJsonObject { put("title", "x".repeat(201)) }).isFailure)
        assertEquals(NotifyLevel.INFO, NotifyRequest.parse(buildJsonObject { put("title", "t") }).getOrThrow().level)
    }

    @Test fun `a notification is queued until acked and survives a restart`() {
        val s = service()
        val sent = assertIs<NotifyService.Outcome.Sent>(s.notify(NotifyRequest("Checkpoint", sound = "chime")))
        assertEquals(1, pushed.size)
        assertEquals("chime", sent.message.sound)
        assertNotNull(sent.message.soundData)
        // No client acked: a client that connects (here: a new daemon start) receives it.
        val again = service()
        assertEquals(listOf(sent.message.id), again.pending().map { it.id })
        assertTrue(again.ack(sent.message.id))
        assertTrue(again.pending().isEmpty())
        assertTrue(service().pending().isEmpty())
    }

    @Test fun `the queue drops what is older than the max age and keeps at most queueMax`() {
        settings = NotifySettings(queueMaxAgeMinutes = 10, queueMax = 2)
        val s = service()
        repeat(3) { s.notify(NotifyRequest("n$it")) }
        assertEquals(listOf("n1", "n2"), s.pending().map { it.title })
        clock += 11 * 60_000
        assertTrue(s.pending().isEmpty())
    }

    @Test fun `a milestone ticket fires once on done, an unmarked one never`() {
        val s = service()
        s.onTicketDone("ticket-a", seq = 5, title = "A", projectId = "p")
        assertTrue(pushed.isEmpty(), "unmarked ticket fired")
        s.setMilestone("ticket-a", on = true, sound = "bell")
        val o = assertIs<NotifyService.Outcome.Sent>(s.onTicketDone("ticket-a", seq = 7, title = "Ship it", projectId = "p"))
        assertEquals(NotifyLevel.MILESTONE, o.message.level)
        assertEquals("bell", o.message.sound)
        assertEquals("ticket-a", o.message.ticketId)
        assertEquals("Milestone done: Ship it", o.message.title)
        // The same done event again (a replay, or DONE plus its transition) fires nothing; so does a restart.
        assertNull(s.onTicketDone("ticket-a", seq = 7, title = "Ship it", projectId = "p"))
        assertNull(service().onTicketDone("ticket-a", seq = 7, title = "Ship it", projectId = "p"))
        assertEquals(1, pushed.size)
        s.setMilestone("ticket-a", on = false, sound = null)
        assertNull(s.onTicketDone("ticket-a", seq = 9, title = "Ship it", projectId = "p"))
        assertEquals(1, pushed.size)
    }

    @Test fun `off drops, quiet hours silence all but urgent, default sound is none`() {
        settings = NotifySettings(enabled = false)
        assertIs<NotifyService.Outcome.Off>(service().notify(NotifyRequest("x")))
        assertTrue(pushed.isEmpty())
        settings = NotifySettings(quietHours = "22:00-07:00", defaultSound = "chime")
        time = LocalTime.of(23, 30)
        val quiet = assertIs<NotifyService.Outcome.Sent>(service().notify(NotifyRequest("x")))
        assertNull(quiet.message.soundData)
        assertNotNull(quiet.silentReason)
        val urgent = assertIs<NotifyService.Outcome.Sent>(service().notify(NotifyRequest("wake", level = NotifyLevel.URGENT)))
        assertEquals("chime", urgent.message.sound)
        settings = NotifySettings()
        time = LocalTime.NOON
        assertNull(assertIs<NotifyService.Outcome.Sent>(service().notify(NotifyRequest("x"))).message.sound)
    }

    @Test fun `owner sound files are listed and unknown names refused`() {
        File(dir, "sounds").mkdirs()
        File(dir, "sounds/ding.wav").writeBytes(NotifySounds.synth("chime")!!)
        val s = service()
        assertEquals(listOf("chime", "bell", "alarm", "ding.wav"), s.sounds.list().map { it.name })
        assertEquals("audio/wav", s.sounds.resolve("ding.wav")?.type)
        assertTrue(runCatching { s.sounds.resolve("nope.wav") }.isFailure)
        assertNull(s.sounds.resolve("none"))
    }

    @Test fun `settings read the notify keys`() {
        val cfg = mapOf("notify.enabled" to "false", "notify.queueMax" to "7", "notify.quietHours" to "01:00-02:00", "notify.defaultSound" to "bell")
        val s = NotifySettings.from { cfg[it] }
        assertEquals(NotifySettings(enabled = false, quietHours = "01:00-02:00", defaultSound = "bell", queueMax = 7), s)
        assertTrue(s.inQuietHours(LocalTime.of(1, 30)))
        assertTrue(!s.inQuietHours(LocalTime.of(2, 0)))
    }

    @Test fun `a device's ack never hides the notification from another device`() {
        val s = service()
        val id = (s.notify(NotifyRequest(title = "m")) as NotifyService.Outcome.Sent).message.id
        assertTrue(s.ack(id, "desktop"))
        assertTrue(s.pending("desktop").isEmpty())
        assertEquals(listOf(id), s.pending("android").map { it.id })
        assertTrue(s.unacked().isEmpty())
        assertEquals(listOf(id), service().pending("android").map { it.id })
        assertTrue(s.ack(id, "android"))
        assertTrue(s.pending("android").isEmpty())
    }

    @Test fun `a notification aimed at one device is not pending for another`() {
        val s = service()
        s.notify(NotifyRequest(title = "d", target = "desktop"))
        assertTrue(s.pending("android").isEmpty())
        assertEquals(1, s.pending("desktop").size)
    }
}
