package com.superiorsoftllc.agenthq.executables

import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.protocol.CliLocationSet
import com.superiorsoftllc.agenthq.protocol.CliLocations
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CLIPATH-1 (contract 1.73.0; CLIPATH-2 1.75.0 lists `node`) over the real daemon socket: `cli_location_set` stores an override, `cli_locations`
 * reads it back with its source, a null path clears it, and a bad path is refused without being stored.
 */
class CliLocationsWireTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fixtures = mutableListOf<DaemonFixture>()
    private val scratch = Files.createTempDirectory("agenthq-clipath").toFile()

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
        scratch.deleteRecursively()
    }

    /** A real file the OS would launch: `.cmd` on Windows (decided by extension), an executable bit elsewhere. */
    private fun fakeExecutable(name: String): File {
        val windows = HostOs.current() == HostOs.WINDOWS
        val file = File(scratch, if (windows) "$name.cmd" else name)
        file.writeText(if (windows) "@echo off\r\n" else "#!/bin/sh\n")
        file.setExecutable(true)
        return file
    }

    private fun locations(fixture: DaemonFixture, occurrence: Int): CliLocations =
        json.decodeFromString(CliLocations.serializer(), fixture.client.await(Envelope.CLI_LOCATIONS, occurrence).data)

    @Test
    fun theContractIs1_78_0() {
        assertEquals("1.105.0", EventVocabulary.VERSION)
    }

    @Test
    fun setReadAndClearAnOverrideRoundTripsOverTheWire() {
        val fixture = DaemonFixture.launch().also { fixtures += it }
        fixture.client.await(Envelope.READY)
        val exe = fakeExecutable("my-claude").absolutePath

        fixture.send(Envelope.CLI_LOCATIONS, "")
        val initial = locations(fixture, 0)
        assertEquals(listOf("claude", "opencode", "pi", "git", "node"), initial.tools.take(5).map { it.id }, "built-ins first, git and node (1.75.0) included")
        val node = initial.tools.single { it.id == "node" }
        assertEquals("Node.js", node.label)
        assertTrue(node.path != null || !node.reason.isNullOrBlank(), "node is either resolved or says why not: $node")
        assertNull(initial.tools.single { it.id == "claude" }.override)

        fixture.send(Envelope.CLI_LOCATION_SET, json.encodeToString(CliLocationSet.serializer(), CliLocationSet("claude", exe)))
        val set = locations(fixture, 1).tools.single { it.id == "claude" }
        assertEquals(exe, set.path)
        assertEquals("override", set.source)
        assertEquals(exe, set.override)

        // Read back with a fresh request: it was stored, not only echoed.
        fixture.send(Envelope.CLI_LOCATIONS, "")
        assertEquals(exe, locations(fixture, 2).tools.single { it.id == "claude" }.override)

        fixture.send(Envelope.CLI_LOCATION_SET, """{"id":"claude","path":null}""")
        val cleared = locations(fixture, 3).tools.single { it.id == "claude" }
        assertNull(cleared.override)
        assertNotEquals("override", cleared.source)
        assertTrue(cleared.path != exe, "after a clear the override no longer decides: $cleared")
    }

    @Test
    fun aBadPathIsRefusedWithAReasonAndNothingIsStored() {
        val fixture = DaemonFixture.launch().also { fixtures += it }
        fixture.client.await(Envelope.READY)
        val missing = File(scratch, "no-such-git.exe").absolutePath

        fixture.send(Envelope.CLI_LOCATION_SET, json.encodeToString(CliLocationSet.serializer(), CliLocationSet("git", missing)))
        val error = fixture.client.awaitWhere("cli_location_set refusal") { it.type == Envelope.ERROR && "cli_location_set refused" in it.data }
        assertTrue("does not exist" in error.data, error.data)

        fixture.send(Envelope.CLI_LOCATION_SET, """{"id":"nope","path":null}""")
        fixture.client.awaitWhere("unknown id refusal") { it.type == Envelope.ERROR && "unknown CLI 'nope'" in it.data }

        fixture.send(Envelope.CLI_LOCATIONS, "")
        assertNull(locations(fixture, 0).tools.single { it.id == "git" }.override, "a refused path is never stored")
    }

    @Test
    fun aHarnessWhoseExecutableIsGoneIsListedUnavailableWithTheReasonAndTriedPath() {
        val fixture = DaemonFixture.launch(harnesses = com.superiorsoftllc.agenthq.daemon.loadHarnesses(descriptorDir = null))
            .also { fixtures += it }
        fixture.client.await(Envelope.READY)
        val exe = fakeExecutable("vanishing-claude")
        fixture.send(Envelope.CLI_LOCATION_SET, json.encodeToString(CliLocationSet.serializer(), CliLocationSet("claude", exe.absolutePath)))
        locations(fixture, 0)
        assertTrue(exe.delete())

        fixture.send(Envelope.PROVIDERS, "")
        val infos = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(com.superiorsoftllc.agenthq.protocol.ProviderInfo.serializer()),
            fixture.client.await(Envelope.PROVIDERS).data,
        )
        val claude = infos.single { it.id == "claude" }
        assertEquals(false, claude.available, "a stored override that vanished is reported, not silently replaced")
        val reason = claude.unavailableReason.orEmpty()
        assertTrue("does not exist" in reason && exe.absolutePath in reason, reason)
        assertTrue(infos.single { it.id == "stub" }.available, "a provider with no CLI is always available")
    }
}
