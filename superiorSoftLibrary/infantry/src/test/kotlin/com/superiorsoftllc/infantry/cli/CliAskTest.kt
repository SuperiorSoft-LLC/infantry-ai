// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.TestWait
import com.superiorsoftllc.infantry.daemon.AccessPolicy
import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.PromptKinds
import com.superiorsoftllc.infantry.protocol.PromptOption
import com.superiorsoftllc.infantry.protocol.PromptRequest
import com.superiorsoftllc.infantry.protocol.PromptResolved
import com.superiorsoftllc.infantry.protocol.PromptResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.StringReader
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PROMPT-5: `infantry ask` over a REAL daemon and the REAL [Cli.run]. The CLI asks for the session named by
 * `INFANTRY_SESSION_ID`, blocks, and prints the owner's answer as JSON. The owner is a second WebSocket client that
 * answers the `prompt_request` card with a `prompt_response`, exactly as the UI does.
 */
class CliAskTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fixtures = mutableListOf<DaemonFixture>()

    private fun launch(): DaemonFixture =
        DaemonFixture.launch(stateDir = Files.createTempDirectory("infantry-ask-cli-test").toFile())
            .also {
                fixtures += it
                it.client.await(Envelope.READY)
            }

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
    }

    private data class Result(val exit: Int, val out: String, val err: String)

    private fun DaemonFixture.cli(vararg args: String, session: String?): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val env = buildMap {
            if (session != null) put(AccessPolicy.SESSION_ID_ENV, session)
            put(ANSWER_TIMEOUT_ENV, TestWait.defaultMs.toString())
        }
        val exit = Cli.run(arrayOf(*args, "--port", port.toString()), out, err, getenv = { env[it] }, input = { StringReader("") })
        return Result(exit, out.toString(), err.toString())
    }

    /** The CLI blocks until answered, so it runs off the test thread. */
    private fun DaemonFixture.cliAsync(vararg args: String, session: String?): CompletableFuture<Result> =
        CompletableFuture.supplyAsync { cli(*args, session = session) }

    /**
     * A real session on the daemon (the stub answers a prompt), returned once its turn has FINISHED. A turn's end
     * resolves the session's open prompts (`session_end`), so asking before the stub's turn is over races it.
     */
    private fun DaemonFixture.spawnSession(): String {
        val owner = connectClient()
        owner.await(Envelope.READY)
        owner.send(Envelope(Envelope.PROMPT, "hello"))
        val session = owner.awaitWhere("a new session's turn", 10_000) { it.type == Envelope.TURN && it.sessionId != null }.sessionId!!
        owner.awaitWhere("the end of $session's first turn", 10_000) { it.type == Envelope.FINISHED && it.sessionId == session }
        return session
    }

    /** The card the CLI opened for [session], as every client sees it. */
    private fun DaemonFixture.cardFor(session: String): PromptRequest {
        val frame = client.awaitWhere("the prompt_request of $session", 10_000) {
            it.type == Envelope.PROMPT_REQUEST && it.sessionId == session
        }
        return json.decodeFromString(PromptRequest.serializer(), frame.data)
    }

    private fun DaemonFixture.answer(response: PromptResponse) =
        client.send(Envelope.PROMPT_RESPONSE, json.encodeToString(PromptResponse.serializer(), response))

    private fun Result.answerJson(): JsonObject = json.parseToJsonElement(out.trim()).jsonObject

    @Test
    fun anAnswerRoundTripsAsJsonOnStdout() {
        val fixture = launch()
        val session = fixture.spawnSession()
        val pending = fixture.cliAsync(
            "ask", "--question", "Which database?",
            "--option", "Postgres|the relational default",
            "--option", "SQLite|one file, no server",
            session = session,
        )

        val card = fixture.cardFor(session)
        assertEquals(PromptKinds.QUESTION, card.kind)
        assertEquals("Which database?", card.question)
        assertEquals(
            listOf(PromptOption("Postgres", "the relational default"), PromptOption("SQLite", "one file, no server")),
            card.options,
        )
        assertEquals(false, card.multiSelect)
        assertEquals(true, card.allowOther)

        fixture.answer(PromptResponse(card.promptId, answers = listOf("SQLite")))
        val result = pending.get(TestWait.defaultMs, TimeUnit.MILLISECONDS)
        assertEquals(0, result.exit, result.err)
        val answer = result.answerJson()
        assertEquals(listOf("SQLite"), answer.getValue("answers").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonNull, answer.getValue("otherText"))
    }

    @Test
    fun multiSelectReturnsEveryChosenOptionAndOtherCarriesFreeText() {
        val fixture = launch()
        val session = fixture.spawnSession()
        val pending = fixture.cliAsync(
            "ask", "--question", "Which suites?", "--multi", "--other",
            "--option", "unit", "--option", "wire|the daemon socket", "--option", "soak",
            session = session,
        )

        val card = fixture.cardFor(session)
        assertEquals(true, card.multiSelect)
        assertEquals(true, card.allowOther)
        assertEquals(PromptOption("unit", ""), card.options.first(), "a bare --option is a label with no description")

        fixture.answer(PromptResponse(card.promptId, answers = listOf("unit", "soak"), otherText = "and lint"))
        val result = pending.get(TestWait.defaultMs, TimeUnit.MILLISECONDS)
        assertEquals(0, result.exit, result.err)
        val answer = result.answerJson()
        assertEquals(listOf("unit", "soak"), answer.getValue("answers").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("and lint", answer.getValue("otherText").jsonPrimitive.content)
    }

    @Test
    fun noAnswerWithinTheTimeoutExits3AndClosesTheCard() {
        val fixture = launch()
        val session = fixture.spawnSession()
        val started = System.currentTimeMillis()
        val result = fixture.cli("ask", "--question", "Ship it?", "--option", "yes", "--option", "no", "--timeout", "1", session = session)

        assertEquals(3, result.exit, "out=${result.out} err=${result.err}")
        assertTrue(System.currentTimeMillis() - started < 15_000, "the timeout is the asker's, not the daemon's 600 s default")
        assertTrue("no answer" in result.err, result.err)
        assertTrue(result.answerJson().getValue("answers").jsonArray.isEmpty(), result.out)

        val closed = fixture.client.awaitWhere("the timed-out card's prompt_resolved", 10_000) {
            it.type == Envelope.PROMPT_RESOLVED && it.sessionId == session
        }
        val resolved = json.decodeFromString(PromptResolved.serializer(), closed.data)
        assertEquals("timeout", resolved.resolvedBy)
        assertEquals("no_answer", resolved.outcome)
    }

    @Test
    fun noSessionInTheEnvironmentIsAUsageErrorAndOpensNoCard() {
        val fixture = launch()
        val result = fixture.cli("ask", "--question", "Ship it?", "--option", "yes", session = null)

        assertEquals(2, result.exit, result.err)
        assertTrue(AccessPolicy.SESSION_ID_ENV in result.err, "the message names the missing variable: ${result.err}")
        Thread.sleep(300)
        assertTrue(fixture.client.of(Envelope.PROMPT_REQUEST).isEmpty(), "nothing was asked")
    }

    @Test
    fun malformedInvocationsAreUsageErrors() {
        val fixture = launch()
        val session = "s-anything"
        fun exitOf(vararg args: String) = fixture.cli(*args, session = session).exit

        assertEquals(2, exitOf("ask", "--option", "yes"), "no --question")
        assertEquals(2, exitOf("ask", "--question", "Ship it?"), "no --option")
        assertEquals(2, exitOf("ask", "--question", "Ship it?", "--option", "yes", "--option", "yes"), "a repeated label")
        assertEquals(2, exitOf("ask", "--question", "Ship it?", "--option", "|no label"), "a blank label")
        assertEquals(2, exitOf("ask", "--question", "Ship it?", "--option", "yes", "--timeout", "0"), "a timeout below 1")
        assertEquals(2, exitOf("ask", "--question", "Ship it?", "--option", "yes", "--timeout", "soon"), "a timeout that is no number")
    }

    @Test
    fun anUnknownSessionIsRefusedByTheDaemon() {
        val fixture = launch()
        val result = fixture.cli("ask", "--question", "Ship it?", "--option", "yes", session = "no-such-session")

        assertEquals(2, result.exit, result.err)
        assertTrue("prompt_ask refused" in result.err && "no-such-session" in result.err, result.err)
        assertTrue(fixture.client.of(Envelope.PROMPT_REQUEST).isEmpty(), "nothing was asked")
    }
}
