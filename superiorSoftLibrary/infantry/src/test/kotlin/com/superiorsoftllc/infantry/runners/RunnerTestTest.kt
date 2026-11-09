// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHarness
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerInfo
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.SessionRef
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartSpec
import com.superiorsoftllc.infantry.daemon.api.runners.LinkAnswer
import com.superiorsoftllc.infantry.daemon.api.runners.LinkFailure
import com.superiorsoftllc.infantry.daemon.api.runners.LinkHandler
import com.superiorsoftllc.infantry.daemon.api.runners.LinkServing
import com.superiorsoftllc.infantry.daemon.api.runners.LinkTlsMaterial
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkTransport
import com.superiorsoftllc.infantry.settings.LimitRegistry
import com.superiorsoftllc.infantry.settings.RunnerLinkSettings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkWire as F

/**
 * RUNNER-TEST-1 (docs/wiki/RunnerPairing-design.md 4, step 6): `runner_admin` op `test` — one throwaway turn on a runner.
 *
 * What it pins: the start carries a provider, a model and the fixed prompt and nothing else (no ticket, no git remote, no project, so
 * the runner clones nothing); the harness is the one asked for, else `echo`, else the first harness signed in there; the answer is read
 * from the transcript (the turn line the runner writes is not an answer); and the session is stopped AND removed afterwards, on the
 * timeout path too — a test never leaves a worker on either session list. The runner's own harness grant is untouched: a harness it does
 * not grant is refused there, and nothing starts.
 */
class RunnerTestTest {
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private var now = 1_000_000L
    private val dir: File = Files.createTempDirectory("runner-test-op").toFile()
    /** The runner grants every harness (its own `runner.harnesses`); one test narrows it to none. */
    private var settings = RunnerLinkSettings.Values(harnesses = listOf("*"))
    private var limits: Map<String, Long> = emptyMap()

    /** What the fake runner answered, in the order it was asked. */
    private val starts = mutableListOf<StartSpec>()
    private val stops = mutableListOf<String>()
    private val removes = mutableListOf<String>()
    private var stopForced = false
    private var removeRefusals = 0
    private var harnesses = listOf(RunnerHarness("echo", loggedIn = false))
    private var transcript: () -> List<String> = { emptyList() }

    /** Each clock read advances by this much, so a wait that would sleep runs out at once: no test ever sleeps. */
    private var clockStep = 5_000L
    private fun clock(): Long = now.also { now += clockStep }

    /** What the runner side records of a session started over the link (the gate its DELIVER, TRANSCRIPT and REMOVE go through). */
    private val remoteStarts = RemoteStarts(cloneRoot = { File(dir, "clones") }, outboxMax = { 8 }, log = {})

    private val local = object : RunnerLink {
        override fun ticketIdOf(sessionId: String): String? = null
        override fun hello(): OrchestratorReply<RunnerInfo> = OrchestratorReply.Ok(RunnerInfo("box", F.CONTRACT, harnesses))

        override fun startSession(spec: StartSpec): OrchestratorReply<SessionRef> {
            starts += spec
            remoteStarts.linked(SESSION)
            return OrchestratorReply.Ok(SessionRef(SESSION))
        }

        override fun stopSession(sessionId: String, reason: String?, force: Boolean): OrchestratorReply<Boolean> {
            stops += sessionId
            stopForced = force
            return OrchestratorReply.Ok(true)
        }

        override fun readTranscript(sessionId: String, fromLine: Int, maxLines: Int): OrchestratorReply<List<String>> = OrchestratorReply.Ok(transcript())

        override fun removeSession(sessionId: String): OrchestratorReply<Boolean> {
            if (removeRefusals > 0) {
                removeRefusals--
                return OrchestratorReply.Unavailable("session '$sessionId' is still running (BUSY); kill it first, then remove it")
            }
            removes += sessionId
            return OrchestratorReply.Ok(true)
        }
    }

    private val transport = InProcess()
    private val runner = RunnerLinkService(
        File(dir, "runner"), { def -> limits[def.key] ?: def.default }, { settings }, local, { transport },
        host = "127.0.0.1", log = {}, clock = ::clock, starts = remoteStarts,
    )
    private val orch = RunnerLinkService(
        File(dir, "orch"), { def -> limits[def.key] ?: def.default }, { RunnerLinkSettings.Values() }, null, { transport },
        host = "127.0.0.1", log = {}, clock = ::clock,
    )

    private var paired = false

    /** Pairs the two sides over the transport they share (the PAIR path `runner add` takes), once. */
    private fun pair() {
        if (paired) return
        val code = RunnerLinkService.field(runner.admin(op("pair", "name" to "box")), "code")
        assertNotNull(code, "the runner issued no pair code")
        val added = orch.admin(op("add", "name" to "box", "url" to "wss://h:1", "code" to code, "fingerprint" to FP, "orchestratorName" to "orch"))
        assertEquals("true", RunnerLinkService.field(added, "ok"), added.toString())
        paired = true
        runner.tick()
    }

    /** One `test` op on the orchestrator, after pairing. */
    private fun test(vararg fields: Pair<String, String?>): JsonObject {
        pair()
        return orch.admin(op("test", "name" to "box", *fields))
    }

    private fun JsonObject.lines(key: String): List<String> = (this[key] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }

    @Test
    fun `a test starts one throwaway turn, reads its answer, then removes the session for good`() {
        transcript = { listOf("[turn] Reply with the single word: ready", "session: echo-1f", "echo: ready") }
        val answer = test()
        assertEquals("true", RunnerLinkService.field(answer, "ok"), answer.toString())

        // The start is a start the orchestrator already sends, and it carries nothing project-bound.
        val start = starts.single()
        assertEquals("echo", start.provider)
        assertNull(start.model)
        assertEquals(RunnerLinkService.TEST_PROMPT, start.prompt)
        assertNull(start.ticketId)
        assertNull(start.gitRemote)
        assertNull(start.projectId)
        assertTrue(start.startId.startsWith("test-"), start.startId)

        assertEquals("echo", RunnerLinkService.field(answer, "harness"))
        assertEquals(SESSION, RunnerLinkService.field(answer, "sessionId"))
        assertEquals("echo: ready", RunnerLinkService.field(answer, "reply"))
        assertTrue((answer["ms"] as JsonPrimitive).int > 0, answer.toString())
        // The session leaves nothing behind: one `remove` frame ends it and forgets it (an older runner gets a force stop instead).
        assertEquals(listOf(SESSION), removes)
        assertTrue(stops.isEmpty(), "a remove that went through needs no separate stop: ${stops}")
        assertNull(answer["cleanup"])
    }

    @Test
    fun `a failed turn line and a stream-json init event are not taken for the answer`() {
        transcript = {
            listOf(
                "[turn] Reply with the single word: ready",
                """{"type":"system","subtype":"init","session_id":"s1"}""",
                "[infantry] * session started (claude · default)",
                "[infantry] turn failed: the claude process did not start: Claude Code is not signed in.",
            )
        }
        val answer = test()
        assertEquals("false", RunnerLinkService.field(answer, "ok"), answer.toString())
        assertTrue(RunnerLinkService.field(answer, "error")!!.contains("no answer from"), answer.toString())
        assertTrue(answer.lines("lastLines").any { it.contains("turn failed") }, answer.toString())
    }

    @Test
    fun `a stream-json result event answers with its result text`() {
        transcript = { listOf("[infantry] * session started (claude)", """{"type":"result","subtype":"success","result":"ready"}""") }
        assertEquals("ready", RunnerLinkService.field(test(), "reply"))
    }

    @Test
    fun `the turn line the runner writes is not taken for the answer`() {
        transcript = { listOf("[turn] Reply with the single word: ready") }
        val answer = test()
        assertEquals("false", RunnerLinkService.field(answer, "ok"), answer.toString())
        assertTrue(RunnerLinkService.field(answer, "error")!!.contains("no answer from 'echo'"), answer.toString())
        assertTrue(answer.lines("lastLines").any { it.contains("[turn]") }, answer.toString())
        // The timeout path cleans up too.
        assertEquals(listOf(SESSION), removes)
    }

    @Test
    fun `an echo answer wins the pick even when it reports no sign-in, else the first signed-in harness`() {
        assertEquals("echo", RunnerLinkService.field(test(), "harness"))

        // No echo on the runner: the first harness it reports signed in runs the test.
        harnesses = listOf(RunnerHarness("alpha", listOf("m1"), loggedIn = false), RunnerHarness("beta", listOf("m2"), loggedIn = true))
        assertEquals("beta", RunnerLinkService.field(test(), "harness"))
        assertEquals("beta", starts.last().provider, "the harness the start named")
    }

    @Test
    fun `a runner with no signed-in harness refuses before anything starts`() {
        harnesses = listOf(RunnerHarness("alpha", listOf("m1"), loggedIn = false))
        val answer = test()
        assertEquals("false", RunnerLinkService.field(answer, "ok"), answer.toString())
        val error = RunnerLinkService.field(answer, "error").orEmpty()
        assertTrue(error.contains("no logged-in harness on 'box'"), error)
        assertTrue(error.contains("infantry runner check"), error)
        assertTrue(starts.isEmpty(), "nothing may start: ${starts.map { it.provider }}")
    }

    @Test
    fun `a harness the runner does not grant is its own refusal, and nothing starts`() {
        // The orchestrator asks for a harness the runner's own runner.harnesses does not list (here it grants none).
        settings = RunnerLinkSettings.Values()
        val answer = test("harness" to "claude")
        assertEquals("false", RunnerLinkService.field(answer, "ok"), answer.toString())
        val error = RunnerLinkService.field(answer, "error").orEmpty()
        assertTrue(error.contains("is not in this runner's ${RunnerLinkSettings.HARNESSES}"), error)
        assertTrue(starts.isEmpty(), "a refused start never reaches the runner's session layer")
        assertTrue(removes.isEmpty())
    }

    @Test
    fun `an older runner that cannot remove still stops the session, and the answer says it was left behind`() {
        transcript = { listOf("echo: ready") }
        transport.refuses += F.REMOVE
        val answer = test()
        assertEquals("true", RunnerLinkService.field(answer, "ok"), answer.toString())
        assertEquals(RunnerLinkService.LEFT_BEHIND, RunnerLinkService.field(answer, "cleanup"))
        assertTrue(removes.isEmpty())
        assertEquals(listOf(SESSION), stops, "the session is still stopped with force on an older runner")
        assertTrue(stopForced)
    }

    @Test
    fun `a remove the runner refuses falls back to a force stop`() {
        transcript = { listOf("echo: ready") }
        removeRefusals = 1
        val answer = test()
        assertEquals("true", RunnerLinkService.field(answer, "ok"), answer.toString())
        assertEquals(RunnerLinkService.LEFT_BEHIND, RunnerLinkService.field(answer, "cleanup"))
        assertTrue(removes.isEmpty())
        assertEquals(listOf(SESSION), stops)
    }

    private fun op(name: String, vararg fields: Pair<String, String?>) =
        JsonObject((fields.filter { it.second != null } + ("op" to name)).associate { (k, v) -> k to JsonPrimitive(v!!) })

    /**
     * An in-process [RunnerLinkTransport]: [call] reaches the handler [serve] was given, no socket. [refuses] lists the frames an
     * older runner does not implement: it answers those with an error, as its `else ->` branch does.
     */
    private class InProcess : RunnerLinkTransport {
        private var handler: LinkHandler? = null
        val refuses = mutableSetOf<String>()

        override fun call(url: String, fingerprint: String?, allowPlain: Boolean, orchestratorId: String?, credential: String?, type: String, data: String, timeoutSeconds: Long): LinkAnswer {
            if (type in refuses) return LinkAnswer(F.ERROR, "$type refused: not a runner link operation this runner offers (contract ${F.CONTRACT})")
            return (handler ?: throw LinkFailure("nothing serves $url")).handle(type, data, orchestratorId, credential, PEER)
        }

        override fun serve(handler: LinkHandler, host: String, port: Int, tls: LinkTlsMaterial?, callTimeoutMs: Int): LinkServing {
            this.handler = handler
            return object : LinkServing {
                override val port: Int = 1
                override fun close() { this@InProcess.handler = null }
            }
        }

        override fun fingerprint(tls: LinkTlsMaterial): String = FP
    }

    private companion object {
        const val SESSION = "s-test"
        const val PEER = "100.64.0.9"
        const val FP = "AA:BB:CC"
    }
}
