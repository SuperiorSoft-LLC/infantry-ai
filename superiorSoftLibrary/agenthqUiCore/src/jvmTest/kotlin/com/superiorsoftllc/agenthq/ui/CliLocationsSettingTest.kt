package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.CliLocationsSetting
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.net.DaemonClient
import com.superiorsoftllc.agenthq.ui.protocol.CliLocation
import com.superiorsoftllc.agenthq.ui.protocol.CliLocations
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** A daemon link that records what the controller sends and lets the test push the daemon's answers. */
private class RecordingLink(scope: CoroutineScope) : DaemonClient(scope) {
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    private val record = kotlinx.coroutines.flow.MutableStateFlow<List<Envelope>>(emptyList())
    val sent: List<Envelope> get() = record.value
    override val frames: SharedFlow<Envelope> get() = incoming
    override suspend fun send(envelope: Envelope): Boolean { record.update { it + envelope }; return true }
    suspend fun push(e: Envelope) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 10_000.milliseconds
        while (incoming.subscriptionCount.value == 0 && deadline.hasNotPassedNow()) delay(10)
        check(incoming.tryEmit(e)) { "frame buffer full" }
    }
}

/**
 * CLIPATH-1 (contract 1.73.0): Settings > "CLI locations". The rows are the daemon's `cli_locations` answer; Save sends
 * `cli_location_set` with the path, Clear sends it with an explicit null; the row only changes when the daemon answers.
 */
class CliLocationsSettingTest {
    private val json = Json { encodeDefaults = true }

    private fun frame(vararg tools: CliLocation) = Envelope(Envelope.CLI_LOCATIONS, json.encodeToString(CliLocations.serializer(), CliLocations(tools.toList())))

    private fun <T> withController(contract: String, body: suspend (RecordingLink, Controller) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = RecordingLink(scope)
            val controller = Controller(scope, link)
            return runBlocking {
                link.push(Envelope(Envelope.READY, "contract=$contract provider=stub instance=i1"))
                awaitUntil { controller.state.value.connection.contract != null }
                body(link, controller)
            }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun awaitUntil(condition: () -> Boolean) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 10_000.milliseconds
        while (!condition() && deadline.hasNotPassedNow()) delay(10)
        check(condition()) { "condition not met in time" }
    }

    @Test
    fun `setting then clearing a path sends the daemon both requests and shows only what it answers`() = withController("1.73.0") { link, controller ->
        awaitUntil { link.sent.any { it.type == Envelope.CLI_LOCATIONS } }

        val notFound = CliLocation("claude", "Claude Code", tried = listOf("/home/u/.local/bin/claude"), reason = "Claude Code not found")
        link.push(frame(notFound))
        awaitUntil { controller.state.value.cliLocations != null }
        assertEquals("Not found on PATH or in the usual install folders", CliLocationsSetting.status(controller.state.value.cliLocations!!.single()))

        // Set.
        assertIs<CommandResult.Sent>(controller.setCliLocation("claude", "  /opt/claude  "))
        val set = Json.parseToJsonElement(link.sent.last { it.type == Envelope.CLI_LOCATION_SET }.data).jsonObject
        assertEquals("claude", set.getValue("id").jsonPrimitive.content)
        assertEquals("/opt/claude", set.getValue("path").jsonPrimitive.content, "trimmed before it is sent")
        assertEquals(null, controller.state.value.cliLocations!!.single().override, "nothing changes until the daemon answers")

        link.push(frame(CliLocation("claude", "Claude Code", path = "/opt/claude", source = "override", override = "/opt/claude", tried = listOf("/opt/claude"))))
        awaitUntil { controller.state.value.cliLocations!!.single().override == "/opt/claude" }
        assertEquals("Using /opt/claude (set here)", CliLocationsSetting.status(controller.state.value.cliLocations!!.single()))

        // Clear: an explicit JSON null, never a missing key or an empty string.
        assertIs<CommandResult.Sent>(controller.setCliLocation("claude", null))
        val clear = Json.parseToJsonElement(link.sent.last { it.type == Envelope.CLI_LOCATION_SET }.data).jsonObject
        assertEquals(JsonNull, clear["path"])
        assertIs<CommandResult.Sent>(controller.setCliLocation("claude", "   "))
        assertEquals(JsonNull, Json.parseToJsonElement(link.sent.last { it.type == Envelope.CLI_LOCATION_SET }.data).jsonObject["path"], "blank = clear")

        link.push(frame(CliLocation("claude", "Claude Code", path = "/usr/bin/claude", source = "path", tried = listOf("/usr/bin/claude"))))
        awaitUntil { controller.state.value.cliLocations!!.single().override == null }
        assertEquals("Using /usr/bin/claude (found on PATH)", CliLocationsSetting.status(controller.state.value.cliLocations!!.single()))
    }

    @Test
    fun `an older daemon is never asked and a set is refused locally`() = withController("1.72.0") { link, controller ->
        val result = controller.setCliLocation("git", "/usr/bin/git")
        assertIs<CommandResult.Refused>(result)
        assertTrue("1.73.0" in result.reason)
        assertTrue(link.sent.none { it.type == Envelope.CLI_LOCATIONS || it.type == Envelope.CLI_LOCATION_SET })
        assertNull(controller.state.value.cliLocations)
    }

    @Test
    fun `row texts name the source, a broken override, and the tried paths`() {
        assertEquals("default install location", CliLocationsSetting.sourceLabel("default"))
        assertEquals("environment variable", CliLocationsSetting.sourceLabel("env"))
        val broken = CliLocation("pi", "Pi", override = "/gone/pi", tried = listOf("/gone/pi"), reason = "Pi: the location set in Settings (/gone/pi) does not exist.")
        assertTrue(CliLocationsSetting.status(broken).startsWith("Not usable: Pi: the location"))
        assertEquals(listOf("/gone/pi"), CliLocationsSetting.triedLines(broken))
        assertEquals("/gone/pi", CliLocationsSetting.initialText(broken))
        assertEquals(emptyList(), CliLocationsSetting.triedLines(CliLocation("git", "Git", path = "/usr/bin/git", source = "default", tried = listOf("/usr/bin/git"))))
        assertNull(CliLocationsSetting.pathToSave("  "))
        assertEquals("/x", CliLocationsSetting.pathToSave(" /x "))
    }
}
