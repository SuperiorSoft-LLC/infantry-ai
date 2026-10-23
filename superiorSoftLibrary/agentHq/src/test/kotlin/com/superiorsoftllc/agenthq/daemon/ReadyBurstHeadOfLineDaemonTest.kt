package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.executables.ExecutableEnvironment
import com.superiorsoftllc.agenthq.executables.HostOs
import com.superiorsoftllc.agenthq.executables.SystemExecutableEnvironment
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.ToolApprovalInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PERF-3 (FLAKE-1 F1): the Controller's ready burst (`providers`, `cli_locations`, `settings_get`, `tool_approval`)
 * used to be answered inline on the connection's request loop, so a slow PATH scan or settings read held the first
 * user command (`project_save`) behind it for 10-20 s under I/O load -- the BUG-ModelsHeadOfLine class. Those reads
 * now run off the loop: a slow resolver delays only its own reply.
 */
class ReadyBurstHeadOfLineDaemonTest {

    /** The real machine, except that once armed every PATH lookup parks until released (bounded so CI cannot hang). */
    private class SlowEnvironment : ExecutableEnvironment by SystemExecutableEnvironment {
        val blocking = AtomicBoolean(false)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override val os: HostOs get() = SystemExecutableEnvironment.os
        override fun env(name: String): String? {
            if (blocking.get()) {
                entered.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            return SystemExecutableEnvironment.env(name)
        }
    }

    @Test
    fun aSlowCliLocationsScanDoesNotBlockTheProjectSaveSentAfterIt() {
        val slow = SlowEnvironment()
        val fixture = DaemonFixture.launch(executableEnvironment = slow)
        try {
            fixture.client.await(Envelope.READY)

            slow.blocking.set(true)
            fixture.send(Envelope.CLI_LOCATIONS, "")
            assertTrue(slow.entered.await(10, TimeUnit.SECONDS), "the daemon scanned PATH")

            // The connection keeps answering while the PATH scan is still running.
            fixture.send(Envelope.PROJECT_SAVE, """{"name":"after-burst"}""")
            assertNotNull(
                runCatching {
                    fixture.client.awaitWhere("after-burst project", 4_000) { it.type == Envelope.PROJECT && it.data.contains("after-burst") }
                }.getOrNull(),
                "project_save waited behind a slow cli_locations",
            )
            assertNull(fixture.client.awaitOrNull(Envelope.CLI_LOCATIONS, timeoutMs = 200), "cli_locations answered before the scan returned")

            slow.blocking.set(false)
            slow.release.countDown()
            assertNotNull(fixture.client.awaitOrNull(Envelope.CLI_LOCATIONS, timeoutMs = 10_000), "cli_locations answered once the scan returned")
        } finally {
            slow.blocking.set(false)
            slow.release.countDown()
            fixture.stop()
            fixture.deleteState()
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * The reads moved off the loop, the setters did not: a set sent BEFORE a get on one connection is applied inline
     * before the get is even queued, so the get reads the new value. Both are sent back to back, no wait in between.
     */
    @Test
    fun aSettingsGetSentAfterASettingsSetReadsTheNewValue() {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)
            val key = "context.includeLinkedTasksContext"
            fixture.send(Envelope.SETTINGS_SET, """{"key":"$key","value":false}""")
            fixture.send(Envelope.SETTINGS_GET, key)
            // Occurrence 0 answers the set, 1 the get; both must carry the value the set stored.
            for (occurrence in 0..1) {
                val settings = json.decodeFromString(JsonObject.serializer(), fixture.client.await(Envelope.SETTINGS, occurrence).data)
                assertEquals("false", settings.getValue(key).jsonPrimitive.content, "settings frame #$occurrence after settings_set")
            }
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun aToolApprovalSentAfterAToolApprovalSetReadsTheNewMode() {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope.TOOL_APPROVAL_SET, """{"mode":"allowAll"}""")
            fixture.send(Envelope.TOOL_APPROVAL, "")
            // Occurrence 0 answers the set, 1 the get; both must carry the mode the set stored.
            for (occurrence in 0..1) {
                val info = json.decodeFromString(ToolApprovalInfo.serializer(), fixture.client.await(Envelope.TOOL_APPROVAL, occurrence).data)
                assertEquals("allowAll", info.mode, "tool_approval frame #$occurrence after tool_approval_set")
            }
        } finally {
            fixture.send(Envelope.TOOL_APPROVAL_SET, """{"mode":null}""")
            fixture.stop()
            fixture.deleteState()
        }
    }
}
