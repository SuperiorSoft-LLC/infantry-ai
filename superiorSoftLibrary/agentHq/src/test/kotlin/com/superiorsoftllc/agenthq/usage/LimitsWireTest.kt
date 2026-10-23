package com.superiorsoftllc.agenthq.usage

import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** U1 (contract 1.59.0) over the real daemon socket: `limits` is answered with every source's windows, token-free. */
class LimitsWireTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val fixtures = mutableListOf<DaemonFixture>()
    private val now = 1_800_000_000_000L
    private val secret = "opencode-go-SECRET-KEY-42"

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
    }

    @Test
    fun theContractIs1_68_0() {
        assertEquals("1.102.0", EventVocabulary.VERSION)
    }

    @Test
    fun aLimitsRequestIsAnsweredWithEverySourcesWindows() {
        val http = LimitHttp { url, _ ->
            if (url == OpenCodeGoUsageSource.URL) {
                LimitHttpResponse(200, """{"usage":{"rolling":{"percent":"55","resetsAt":${(now + 3_600_000) / 1000}}}}""")
            } else {
                LimitHttpResponse(429, "")
            }
        }
        val limits = LimitService(
            listOf(ClaudeOAuthUsageSource(http) { secret }, OpenCodeGoUsageSource(http) { secret }),
            liveEnabled = { true }, clock = { now }, log = {},
        )
        val fixture = DaemonFixture.launch(limits = limits).also { fixtures += it }
        fixture.client.await(Envelope.READY)

        fixture.send(Envelope.LIMITS, "")
        val frame = fixture.client.await(Envelope.LIMITS)
        assertFalse(frame.data.contains(secret), "the credential never reaches the wire")
        val answer = json.decodeFromString(ListSerializer(ProviderLimits.serializer()), frame.data)
        assertEquals(ProviderLimits.unknown("claude", now, "rate_limited"), answer[0])
        assertEquals(
            ProviderLimits(
                "opencode-go", "ok",
                listOf(LimitWindow("opencode-go", "5h limit", 300, 55.0, now + 3_600_000, now, "live", -25.0)),
                null, now,
            ),
            answer[1],
        )
    }
}
