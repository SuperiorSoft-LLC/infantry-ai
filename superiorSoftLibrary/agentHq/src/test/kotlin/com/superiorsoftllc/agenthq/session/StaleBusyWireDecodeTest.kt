package com.superiorsoftllc.agenthq.session

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BASICS-KILL-1: the wire rewrites an at-rest session with background work to state BUSY (display only, `turnActive`
 * false). A client decoding that state called the session live and refused to remove it forever ("stale BUSY").
 * [SessionListWire] decodes the TRUE state; a real turn in flight still decodes BUSY, and an older daemon's record
 * (no `turnActive`) decodes exactly as before.
 */
class StaleBusyWireDecodeTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun record(state: SessionState, bg: Boolean) = SessionRecord(
        id = "s1", provider = "stub", workingDir = "/w", spawnedAt = 1, lastHeartbeat = 1, state = state,
        providerSessionId = "p",
        bgJobs = if (bg) listOf(BgJob("j", "gradlew test", 1L, 1L)) else emptyList(),
    )

    private fun roundTrip(r: SessionRecord): Pair<String, SessionRecord> {
        val text = json.encodeToString(SessionListWire, listOf(r))
        return text to json.decodeFromString(SessionListWire, text).single()
    }

    @Test
    fun anAtRestSessionThatReadsBusyForBackgroundWorkDecodesIdleAndNotLive() {
        val (text, decoded) = roundTrip(record(SessionState.IDLE, bg = true))
        assertEquals("BUSY", json.parseToJsonElement(text).let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject["state"]!!.jsonPrimitive.content })
        assertEquals(SessionState.IDLE, decoded.state)
        assertFalse(decoded.live, "removable without force")
    }

    @Test
    fun aRealTurnInFlightStillDecodesBusyAndLive() {
        val (_, decoded) = roundTrip(record(SessionState.BUSY, bg = false))
        assertEquals(SessionState.BUSY, decoded.state)
        assertTrue(decoded.live)
    }

    @Test
    fun anOlderDaemonsRecordWithoutTurnActiveDecodesAsBefore() {
        val old = """[{"id":"s1","provider":"stub","workingDir":"/w","spawnedAt":1,"lastHeartbeat":1,"state":"BUSY"}]"""
        assertEquals(SessionState.BUSY, json.decodeFromString(SessionListWire, old).single().state)
    }
}
