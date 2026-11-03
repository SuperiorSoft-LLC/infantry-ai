package com.superiorsoftllc.infantry.ui.sound

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.MessageOrigin
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.serialization.json.Json
import com.superiorsoftllc.infantry.ui.shell.PlatformHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncomingCommsSoundTest {
    private val agent = MessageOrigin.Agent("s1", "a1", "pm")
    private fun snap(count: Int, last: MessageOrigin? = agent) = mapOf("a" to IncomingSnapshot(count, last))

    /** The gate in the order the app drives it, settled after the first observation. */
    private fun settled(): IncomingCommsGate = IncomingCommsGate(settleMs = 2_000).also { it.observe(0, "g", snap(0, null), emptySet()); it.observe(2_500, "g", snap(0, null), emptySet()) }

    @Test fun `a new incoming message after settling fires one event with its origin`() {
        val gate = settled()
        assertEquals(listOf(IncomingComms("a", agent)), gate.observe(3_000, "g", snap(1), emptySet()))
        assertTrue(gate.observe(3_100, "g", snap(1), emptySet()).isEmpty())
    }

    @Test fun `only non-owner turns count as incoming`() {
        val session = Envelope(Envelope.SESSION, Json.encodeToString(SessionRecord.serializer(), SessionRecord("a", "stub", "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE)), "a", 1)
        var s = reduce(AppState(), session, 100)
        s = reduce(s, Envelope(Envelope.TURN, "mine", "a", 2), 100)
        assertEquals(0, incomingSnapshots(s).getValue("a").count)
        val ext = MessageOrigin.External("gh", "mail")
        s = reduce(s, Envelope(Envelope.TURN, "hello", "a", 3, origin = ext), 100)
        s = reduce(s, Envelope(Envelope.TURN, "again", "a", 4, origin = MessageOrigin.System("scheduler")), 100)
        assertEquals(2, incomingSnapshots(s).getValue("a").count)
        assertEquals(MessageOrigin.System("scheduler"), incomingSnapshots(s).getValue("a").last)
    }

    @Test fun `history already present at connect or replayed after a history request is silent`() {
        val gate = IncomingCommsGate(settleMs = 2_000)
        assertTrue(gate.observe(0, "g", snap(5), emptySet()).isEmpty())
        assertTrue(gate.observe(500, "g", snap(9), emptySet()).isEmpty())
        assertTrue(gate.observe(3_000, "g", snap(9), setOf("a")).isEmpty())
        // the agent's replay arrives after its history was requested
        assertTrue(gate.observe(3_100, "g", snap(14), setOf("a")).isEmpty())
        assertTrue(gate.observe(4_000, "g", snap(20), setOf("a")).isEmpty())
        assertEquals(1, gate.observe(6_000, "g", snap(21), setOf("a")).size)
    }

    @Test fun `a reconnect makes everything baseline again`() {
        val gate = settled()
        assertEquals(1, gate.observe(3_000, "g", snap(1), emptySet()).size)
        assertTrue(gate.observe(4_000, "g2", snap(7), emptySet()).isEmpty())
    }

    @Test fun `a burst is one sound`() {
        val cooldown = BurstCooldown(1_500)
        assertTrue(cooldown.tryAcquire(10_000))
        assertTrue(!cooldown.tryAcquire(10_200))
        assertTrue(!cooldown.tryAcquire(11_499))
        assertTrue(cooldown.tryAcquire(11_500))
    }

    private class MemHost : PlatformHost {
        val prefs = HashMap<String, String>()
        override val defaultDaemonUrl = "ws://x"
        override fun setting(key: String): String? = null
        override fun readTextFile(path: String): String? = null
        override fun uiPref(key: String): String? = prefs[key]
        override fun saveUiPref(key: String, value: String) { prefs[key] = value }
    }

    @Test fun `settings default to on at full volume and persist enabled, volume and file name`() {
        val host = MemHost()
        assertEquals(IncomingCommsSoundSettings(true, 100, null), IncomingCommsSoundSettings.load(host))
        val changed = IncomingCommsSoundSettings(enabled = false, volumePercent = 35, customName = "ding.mp3")
        changed.save(host, IncomingCommsSoundSettings())
        assertEquals(changed, IncomingCommsSoundSettings.load(host))
        IncomingCommsSoundSettings().save(host, changed)
        assertEquals(IncomingCommsSoundSettings(), IncomingCommsSoundSettings.load(host))
    }

    @Test fun `a bad stored volume is clamped or ignored`() {
        val host = MemHost().apply { prefs[IncomingCommsSoundSettings.KEY_VOLUME] = "900" }
        assertEquals(100, IncomingCommsSoundSettings.load(host).volumePercent)
        host.prefs[IncomingCommsSoundSettings.KEY_VOLUME] = "abc"
        assertEquals(100, IncomingCommsSoundSettings.load(host).volumePercent)
    }
}
