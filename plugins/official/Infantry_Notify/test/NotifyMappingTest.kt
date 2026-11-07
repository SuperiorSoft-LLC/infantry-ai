// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notify

import com.superiorsoftllc.infantry.ui.shell.LinkContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** NOTIFY-1: the client-side mapping: payload parsing, target routing, the Android channel, dedupe and ack on delivery. */
class NotifyMappingTest {
    private val push = """{"id":"n1","title":"Milestone done: X","body":"ticket-1 is done.","level":"milestone","target":"all",
        |"at":5,"sound":"chime","soundType":"audio/wav","soundData":"UklGRg==","ticketId":"ticket-1","projectId":"p"}""".trimMargin()

    @Test fun `a push parses, a broken one does not`() {
        val p = NotifyPayload.parse(push)!!
        assertEquals("n1", p.id); assertEquals("milestone", p.level); assertEquals("ticket-1", p.ticketId); assertEquals("audio/wav", p.soundType)
        assertNull(NotifyPayload.parse("""{"title":"no id"}"""))
        assertNull(NotifyPayload.parse("not json"))
        assertEquals(listOf("n1"), NotifyPayload.parseList("[$push, 3]").map { it.id })
    }

    @Test fun `target and channel mapping`() {
        assertTrue(NotifyRouting.shows("all", "android"))
        assertTrue(NotifyRouting.shows("desktop", "desktop"))
        assertFalse(NotifyRouting.shows("android", "desktop"))
        assertEquals(NotifyRouting.CHANNEL_URGENT, NotifyRouting.androidChannel("urgent"))
        assertEquals(NotifyRouting.CHANNEL_MILESTONES, NotifyRouting.androidChannel("milestone"))
        assertEquals(NotifyRouting.CHANNEL_MILESTONES, NotifyRouting.androidChannel("info"))
        assertTrue(NotifyRouting.androidId("n1") >= 0)
        assertEquals(NotifyRouting.androidId("n1"), NotifyRouting.androidId("n1"))
    }

    @Test fun `delivery shows once, plays the sound, acks`() = runBlocking {
        val shown = mutableListOf<String>()
        val played = mutableListOf<String>()
        val sent = mutableListOf<Pair<String, String>>()
        val notifier = object : Notifier {
            override val platform = "desktop"
            override fun show(p: NotifyPayload): Boolean { shown += p.id; return true }
            override fun play(type: String, bytes: ByteArray): Boolean { played += type; return true }
            override val opened: Flow<String> = emptyFlow()
        }
        val link = object : LinkContext {
            override val live = true
            override val contract: String? = null
            override suspend fun sendFrame(type: String, data: String): Boolean { sent += type to data; return true }
        }
        val dedupe = NotifyDedupe()
        val p = NotifyPayload.parse(push)!!
        assertTrue(deliver(p, notifier, dedupe, link))
        assertFalse(deliver(p, notifier, dedupe, link), "a replay showed twice")
        assertFalse(deliver(p.copy(id = "n2", target = "android"), notifier, dedupe, link), "another platform's notification shown")
        assertEquals(listOf("n1"), shown)
        assertEquals(listOf("audio/wav"), played)
        assertEquals(listOf(NotifyFrames.ACK to """{"id":"n1","device":"desktop"}"""), sent)
    }

    @Test fun `sound list and sound answers parse`() {
        assertEquals(listOf(SoundRow("chime", true, 10), SoundRow("ding.mp3", false, 0)),
            NotifyLists.sounds("""[{"name":"chime","builtin":true,"bytes":10},{"name":"ding.mp3"}]"""))
        assertEquals(Triple("bell", "audio/wav", "AA=="), NotifyLists.sound("""{"name":"bell","type":"audio/wav","data":"AA=="}"""))
        assertNull(NotifyLists.sound("""{"name":"gone"}"""))
    }
}
