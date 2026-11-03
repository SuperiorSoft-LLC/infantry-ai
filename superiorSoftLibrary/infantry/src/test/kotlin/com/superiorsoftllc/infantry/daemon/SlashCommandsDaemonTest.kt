// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.runner.ScriptedRunner
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.SessionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CP-4: the CLI options of a harness (model, effort, compact...) as a catalog the daemon serves and executes (slices a, b, d),
 * and the boundary rule that a leading `/` is a command and never a prompt.
 *
 * Everything here goes through the real daemon on a real WebSocket, and names the frames by their wire strings
 * on purpose: the tests describe the behaviour a client sees, not the Kotlin types that implement it.
 *
 *  - `command_catalog` (request `{provider?, prefix?}`, optional envelope sessionId) -> `command_catalog` answer.
 *  - A `prompt` whose text begins with `/` is a COMMAND, never a prompt: it is executed against the session
 *    (`command_result`) or refused (`error`), and the provider never receives it as a turn.
 */
class SlashCommandsDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // --- slice (b): the catalog, filtered by prefix --------------------------------------------------------------

    @Test
    fun theCatalogOfTheDefaultHarnessListsModelEffortAndCompact() = withDaemon { fixture, _ ->
        val names = catalog(fixture, prefix = "").names()
        assertTrue(names.containsAll(listOf("model", "effort", "compact")), "the catalog must offer model, effort and compact: $names")
    }

    @Test
    fun theCatalogIsFilteredByPrefixAndToleratesTheLeadingSlash() = withDaemon { fixture, _ ->
        assertEquals(listOf("model"), catalog(fixture, prefix = "mo").names())
        assertEquals(listOf("model"), catalog(fixture, prefix = "/mo").names(), "what the user typed includes the slash")
        assertEquals(listOf("model"), catalog(fixture, prefix = "/MO").names(), "matching ignores case")
        assertEquals(emptyList(), catalog(fixture, prefix = "zzz").names(), "no match is an empty list, not an error")
        assertEquals(listOf("compact"), catalog(fixture, prefix = "/c").names())
    }

    @Test
    fun anArgumentPrefixNarrowsTheValuesOfTheNamedCommand() = withDaemon { fixture, _ ->
        val answer = catalog(fixture, prefix = "/effort hi")
        val effort = answer.commands.single { it.jsonObject["name"]!!.jsonPrimitive.content == "effort" }.jsonObject
        assertEquals(listOf("high"), effort["values"]!!.jsonArray.map { it.jsonPrimitive.content }, "only the values the user's text can still become")
    }

    @Test
    fun theCatalogOfAnUnknownProviderIsRefusedNamingTheProvider() = withDaemon { fixture, _ ->
        fixture.client.await(Envelope.READY)
        fixture.send(Envelope("command_catalog", """{"provider":"nope","prefix":""}"""))
        val refused = fixture.client.await(Envelope.ERROR)
        assertTrue("nope" in refused.data, "the refusal names the provider: ${refused.data}")
    }

    @Test
    fun theCatalogOfASessionIsTheCatalogOfThatSessionsProvider() = withDaemon { fixture, _ ->
        val sessionId = startIdleSession(fixture)
        fixture.send(Envelope("command_catalog", """{"prefix":"c"}""", sessionId))
        val answer = parseCatalog(fixture.client.await("command_catalog").data)
        assertEquals("stub", answer.provider, "the session's own provider")
        assertEquals(listOf("compact"), answer.names())
    }


    // --- slice (d): executing against the running session --------------------------------------------------------

    @Test
    fun slashModelSwitchesTheSessionsModelForItsNextTurnAndIsNeverSentAsAPrompt() = withDaemon { fixture, runner ->
        val sessionId = startIdleSession(fixture)
        val specsBefore = runner.specs.size

        fixture.client.send(Envelope(Envelope.PROMPT, "/model stub-fast", sessionId))
        val result = fixture.client.await("command_result")
        assertEquals(sessionId, result.sessionId)
        assertTrue(result.data.contains("\"ok\":true"), "the result says it worked: ${result.data}")

        assertEquals("stub-fast", assertNotNull(fixture.daemon.manager.get(sessionId)).model)
        assertEquals(specsBefore, runner.specs.size, "switching a model starts no run")
        assertEquals(listOf("hello"), turns(fixture, sessionId), "the command never became a turn")

        fixture.client.send(Envelope(Envelope.PROMPT, "after the switch", sessionId))
        fixture.await(10_000, "the next turn to run") { runner.specs.size == specsBefore + 1 }
        val argv = runner.specs.last().command
        assertEquals("stub-fast", argv.getOrNull(argv.indexOf("--model") + 1), "the next run uses the new model: $argv")
    }

    @Test
    fun slashEffortSetsTheSessionsEffortOnTheNextRunAndRefusesAValueTheHarnessDoesNotHave() = withDaemon { fixture, runner ->
        val sessionId = startIdleSession(fixture)

        fixture.client.send(Envelope(Envelope.PROMPT, "/effort extreme", sessionId))
        val refused = fixture.client.await(Envelope.ERROR)
        assertTrue("high" in refused.data && "low" in refused.data, "the refusal lists the valid levels: ${refused.data}")
        assertNull(effortOf(fixture, sessionId), "a refused command changes nothing")

        fixture.client.send(Envelope(Envelope.PROMPT, "/effort high", sessionId))
        fixture.client.await("command_result")
        assertEquals("high", effortOf(fixture, sessionId))

        val specsBefore = runner.specs.size
        fixture.client.send(Envelope(Envelope.PROMPT, "think hard", sessionId))
        fixture.await(10_000, "the next turn to run") { runner.specs.size == specsBefore + 1 }
        val argv = runner.specs.last().command
        assertEquals("high", argv.getOrNull(argv.indexOf("--effort") + 1), "the next run carries the effort: $argv")
    }

    @Test
    fun slashCompactRunsTheHarnessOwnCompactionOnTheSameProviderConversation() = withDaemon { fixture, runner ->
        val sessionId = startIdleSession(fixture)
        val providerSession = assertNotNull(fixture.daemon.manager.get(sessionId)!!.providerSessionId)
        val specsBefore = runner.specs.size

        fixture.client.send(Envelope(Envelope.PROMPT, "/compact", sessionId))
        val result = fixture.client.await("command_result")
        assertTrue(result.data.contains("\"ok\":true") && result.data.contains("compact"), "the daemon says it is compacting: ${result.data}")
        fixture.await(10_000, "the compaction run to start") { runner.specs.size == specsBefore + 1 }
        fixture.await(10_000, "the compaction run to finish") { fixture.daemon.manager.get(sessionId)?.state == SessionState.IDLE }

        val argv = runner.specs.last().command
        assertEquals(providerSession, argv.getOrNull(argv.indexOf("--resume") + 1), "compaction acts on the session's own conversation: $argv")
        assertEquals(listOf("hello", "/compact"), turns(fixture, sessionId), "the harness was handed its own /compact command")
    }

    @Test
    fun anUnknownCommandIsRefusedWithTheKnownListAndNothingReachesTheHarness() = withDaemon { fixture, runner ->
        val sessionId = startIdleSession(fixture)
        val specsBefore = runner.specs.size

        fixture.client.send(Envelope(Envelope.PROMPT, "/frobnicate now", sessionId))
        val refused = fixture.client.await(Envelope.ERROR)
        assertTrue("/frobnicate" in refused.data, "names what was typed: ${refused.data}")
        listOf("/model", "/effort", "/compact").forEach { assertTrue(it in refused.data, "lists $it: ${refused.data}") }

        assertEquals(specsBefore, runner.specs.size, "a refused command starts no run")
        assertEquals(listOf("hello"), turns(fixture, sessionId))
        assertEquals(1, fixture.daemon.manager.list().size)
    }

    @Test
    fun aLeadingSlashWithNoSessionIsRefusedAndStartsNoSession() = withDaemon { fixture, runner ->
        fixture.client.await(Envelope.READY)
        fixture.client.send(Envelope(Envelope.PROMPT, "/model stub-fast"))
        val refused = fixture.client.await(Envelope.ERROR)
        assertTrue("session" in refused.data.lowercase(), "says there is no session to apply it to: ${refused.data}")
        assertEquals(0, runner.specs.size)
        assertEquals(0, fixture.daemon.manager.list().size, "a command must not spawn a session")
    }

    @Test
    fun aSlashAfterOtherTextIsJustAPrompt() = withDaemon { fixture, runner ->
        fixture.client.await(Envelope.READY)
        fixture.client.send(Envelope(Envelope.PROMPT, "please read src/main and /usr/bin"))
        fixture.await(10_000, "the prompt to run") { runner.specs.size == 1 }
        assertEquals(1, fixture.daemon.manager.list().size)
    }

    @Test
    fun aCommandNamingAnUnknownSessionIsRefusedAndRunsNothing() = withDaemon { fixture, runner ->
        val sessionId = startIdleSession(fixture)
        val specsBefore = runner.specs.size
        fixture.client.send(Envelope(Envelope.PROMPT, "/compact", "no-such-session"))
        val refused = fixture.client.await(Envelope.ERROR)
        assertTrue("no-such-session" in refused.data || "unknown" in refused.data.lowercase(), refused.data)
        assertEquals(specsBefore, runner.specs.size)
        assertEquals(listOf("hello"), turns(fixture, sessionId))
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private class Catalog(val provider: String, val prefix: String, val commands: JsonArray) {
        fun names(): List<String> = commands.map { it.jsonObject["name"]!!.jsonPrimitive.content }
    }

    private fun parseCatalog(data: String): Catalog {
        val obj: JsonObject = json.parseToJsonElement(data).jsonObject
        return Catalog(
            provider = obj["provider"]!!.jsonPrimitive.content,
            prefix = obj["prefix"]?.jsonPrimitive?.content.orEmpty(),
            commands = obj["commands"]!!.jsonArray,
        )
    }

    private fun catalog(fixture: DaemonFixture, prefix: String): Catalog {
        fixture.client.await(Envelope.READY)
        // await(type) returns the FIRST frame of that type ever seen, so ask for the answer to THIS request.
        val answered = fixture.client.of("command_catalog").size
        fixture.send(Envelope("command_catalog", """{"prefix":"$prefix"}"""))
        return parseCatalog(fixture.client.await("command_catalog", occurrence = answered).data)
    }

    /** One finished first turn, so the session has a provider conversation. */
    private fun startIdleSession(fixture: DaemonFixture): String {
        fixture.client.await(Envelope.READY)
        fixture.client.send(Envelope(Envelope.PROMPT, "hello"))
        val sessionId = fixture.client.awaitWhere("the first turn", 10_000) { it.type == Envelope.TURN }.sessionId!!
        fixture.await(10_000, "the first run to rest with a provider session id") {
            fixture.daemon.manager.get(sessionId)?.let { it.state == SessionState.IDLE && it.providerSessionId != null } == true
        }
        return sessionId
    }

    /** The session's `effort` as a client sees it on the wire (read from the served JSON, so it needs no Kotlin type). */
    private fun effortOf(fixture: DaemonFixture, sessionId: String): String? {
        val record = fixture.daemon.manager.get(sessionId)!!
        val wire = json.encodeToJsonElement(com.superiorsoftllc.infantry.session.SessionRecordSerializer, record).jsonObject
        return wire["effort"]?.jsonPrimitive?.contentOrNull
    }

    private fun turns(fixture: DaemonFixture, sessionId: String): List<String> =
        fixture.client.frames.filter { it.type == Envelope.TURN && it.sessionId == sessionId }.map { it.data }

    private class RecordingRunner(private val inner: ProcessRunner) : ProcessRunner {
        val specs: MutableList<ProcessSpec> = CopyOnWriteArrayList()
        override val id: String = "recording-${inner.id}"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            specs += spec
            return inner.start(spec, listener)
        }
    }

    private fun withDaemon(block: (DaemonFixture, RecordingRunner) -> Unit) {
        val script = StubScript.default()
        val runner = RecordingRunner(ScriptedRunner(script, delayMs = 0))
        val fixture = DaemonFixture.launch(script, runner = runner)
        try {
            block(fixture, runner)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
