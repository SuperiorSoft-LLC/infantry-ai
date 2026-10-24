package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.daemon.TestWait
import com.superiorsoftllc.agenthq.daemon.WsTestClient
import com.superiorsoftllc.agenthq.mailbox.Message
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.OwnerQuestion
import com.superiorsoftllc.agenthq.protocol.PromptOption
import com.superiorsoftllc.agenthq.protocol.QuestionAnswer
import com.superiorsoftllc.agenthq.protocol.QuestionAsk
import com.superiorsoftllc.agenthq.protocol.QuestionStates
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.StringReader
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ASK-1: `agenthq ask --recommend` over a REAL daemon and the REAL [Cli.run]. The CLI returns a question id at once
 * (nobody answers during the call); the owner is a second WebSocket client that sees the `question` push and answers
 * with `question_answer`, exactly as the UI does; the pick lands in the asker's mailbox. A second daemon over the same
 * state directory still serves the open question.
 */
class CliOwnerQuestionTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fixtures = mutableListOf<DaemonFixture>()
    private val dirs = mutableListOf<File>()

    private fun launch(stateDir: File = Files.createTempDirectory("agenthq-question-cli-test").toFile().also { dirs += it }): DaemonFixture =
        DaemonFixture.launch(stateDir = stateDir).also {
            fixtures += it
            it.client.await(Envelope.READY)
        }

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach { runCatching { it.stop() } }
        dirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private data class Result(val exit: Int, val out: String, val err: String, val millis: Long)

    private fun DaemonFixture.cli(vararg args: String, session: String?): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val env = buildMap {
            if (session != null) put(AccessPolicy.SESSION_ID_ENV, session)
            put(ANSWER_TIMEOUT_ENV, TestWait.defaultMs.toString())
        }
        val started = System.currentTimeMillis()
        val exit = Cli.run(arrayOf(*args, "--port", port.toString()), out, err, getenv = { env[it] }, input = { StringReader("") })
        return Result(exit, out.toString(), err.toString(), System.currentTimeMillis() - started)
    }

    /** A real session on the daemon, returned once its first turn has finished. */
    private fun DaemonFixture.spawnSession(): String {
        val owner = connectClient()
        owner.await(Envelope.READY)
        owner.send(Envelope(Envelope.PROMPT, "hello"))
        val session = owner.awaitWhere("a new session's turn", 10_000) { it.type == Envelope.TURN && it.sessionId != null }.sessionId!!
        owner.awaitWhere("the end of $session's first turn", 10_000) { it.type == Envelope.FINISHED && it.sessionId == session }
        return session
    }

    private fun DaemonFixture.askDatabase(session: String, vararg extra: String) = cli(
        "ask", "--question", "Which database?",
        "--option", "Postgres|a server to run",
        "--option", "SQLite|one file, no server",
        "--recommend", "SQLite", "--reason", "nothing to operate",
        *extra,
        session = session,
    )

    private fun WsTestClient.questionPush(id: String, state: String): OwnerQuestion {
        val frame = awaitWhere("the $state push of question $id", 10_000) {
            it.type == Envelope.QUESTION && json.decodeFromString(OwnerQuestion.serializer(), it.data).let { q -> q.id == id && q.state == state }
        }
        return json.decodeFromString(OwnerQuestion.serializer(), frame.data)
    }

    private fun Result.questionId(): String = json.parseToJsonElement(out.trim()).jsonObject.getValue("questionId").jsonPrimitive.content

    @Test
    fun askReturnsAtOnceTheOwnerPicksAndThePickIsMailedToTheAsker() {
        val fixture = launch()
        val session = fixture.spawnSession()

        val result = fixture.askDatabase(session)
        assertEquals(0, result.exit, result.err)
        val id = result.questionId()
        assertTrue("non-blocking" in result.out, result.out)
        assertTrue(result.millis < 5_000, "the ask returned without waiting for anyone (took ${result.millis} ms)")

        // The owner's client sees the question with its options and the recommendation.
        val shown = fixture.client.questionPush(id, QuestionStates.OPEN)
        assertEquals(session, shown.sessionId)
        assertEquals(listOf(PromptOption("Postgres", "a server to run"), PromptOption("SQLite", "one file, no server")), shown.options)
        assertEquals("SQLite", shown.recommended)
        assertEquals("nothing to operate", shown.reason)

        fixture.client.send(Envelope.QUESTION_ANSWER, json.encodeToString(QuestionAnswer.serializer(), QuestionAnswer(id, answer = "Postgres")))
        assertEquals("Postgres", fixture.client.questionPush(id, QuestionStates.ANSWERED).answer)

        val owner = fixture.connectClient()
        owner.await(Envelope.READY)
        owner.send(Envelope(Envelope.MAIL_INBOX, session, session))
        val inbox = json.decodeFromString(ListSerializer(Message.serializer()), owner.await(Envelope.MAILS).data)
        val mail = inbox.single { it.threadId == "question:$id" }
        assertEquals("owner", mail.from)
        assertTrue(id in mail.subject && "Postgres" in mail.subject, mail.subject)
        assertTrue("Chosen option: Postgres" in mail.body, mail.body)
    }

    @Test
    fun askTestRaisesTheSampleQuestionAndThePickIsMailed() {
        val fixture = launch()
        val session = fixture.spawnSession()

        val result = fixture.cli("ask", "--test", session = session)
        assertEquals(0, result.exit, result.err)
        val id = result.questionId()

        val shown = fixture.client.questionPush(id, QuestionStates.OPEN)
        assertEquals(QuestionAsk.sample().options, shown.options)
        assertEquals("Looks good", shown.recommended)
        assertTrue(shown.allowOther)
        assertTrue(shown.question.startsWith("TEST"), shown.question)

        // The owner types an answer of their own: the Other path.
        fixture.client.send(Envelope.QUESTION_ANSWER, json.encodeToString(QuestionAnswer.serializer(), QuestionAnswer(id, otherText = "my own words")))
        assertEquals("my own words", fixture.client.questionPush(id, QuestionStates.ANSWERED).otherText)
    }

    @Test
    fun theOwnersClientCanRaiseTheSampleForTheOpenSession() {
        val fixture = launch()
        val session = fixture.spawnSession()
        // What the Config "Send a test question" button sends: a plain question_ask naming the open session.
        fixture.client.send(Envelope(Envelope.QUESTION_ASK, json.encodeToString(QuestionAsk.serializer(), QuestionAsk.sample()), session))
        val asked = fixture.client.await(Envelope.QUESTION_ASKED)
        val id = json.decodeFromString(com.superiorsoftllc.agenthq.protocol.QuestionAsked.serializer(), asked.data).questionId
        assertEquals(session, fixture.client.questionPush(id, QuestionStates.OPEN).sessionId)
    }

    @Test
    fun anAgentCannotAnswerAQuestion() {
        val fixture = launch()
        val session = fixture.spawnSession()
        val id = fixture.askDatabase(session).questionId()

        fixture.client.send(Envelope(Envelope.QUESTION_ANSWER, json.encodeToString(QuestionAnswer.serializer(), QuestionAnswer(id, answer = "SQLite")), session))
        val error = fixture.client.awaitWhere("the refusal", 10_000) { it.type == Envelope.ERROR && it.data.startsWith("question_answer refused") }
        assertTrue("only the owner" in error.data, error.data)
    }

    @Test
    fun aRefusedQuestionExitsTwoWithTheDaemonsReason() {
        val fixture = launch()
        val session = fixture.spawnSession()
        val result = fixture.cli(
            "ask", "--question", "Which?", "--option", "a", "--option", "b", "--recommend", "c", "--reason", "why",
            session = session,
        )
        assertEquals(2, result.exit)
        assertTrue("not one of the options" in result.err, result.err)
    }

    @Test
    fun usageErrorsAreCaughtBeforeConnecting() {
        val fixture = launch()
        val session = fixture.spawnSession()
        assertEquals(2, fixture.cli("ask", "--question", "Q?", "--option", "a", "--option", "b", "--recommend", "a", session = session).exit, "no --reason")
        assertEquals(2, fixture.cli("ask", "--question", "Q?", "--option", "a", "--option", "b", "--reason", "r", session = session).exit, "--reason without --recommend")
        assertEquals(2, fixture.askDatabase(session, "--default-after", "0").exit, "--default-after out of range")
        assertEquals(2, fixture.askDatabase(session, "--multi").exit, "--multi is the blocking ask's")
    }

    @Test
    fun theFourthOpenQuestionOfOneAgentIsRateLimited() {
        val fixture = launch()
        val session = fixture.spawnSession()
        repeat(3) { assertEquals(0, fixture.askDatabase(session).exit) }
        val limited = fixture.askDatabase(session)
        assertEquals(2, limited.exit)
        assertTrue("rate limit" in limited.err, limited.err)
    }

    @Test
    fun anOpenQuestionSurvivesADaemonRestart() {
        val stateDir = Files.createTempDirectory("agenthq-question-restart").toFile().also { dirs += it }
        val first = launch(stateDir)
        val id = first.askDatabase(first.spawnSession()).questionId()
        first.stop()

        val second = launch(stateDir)
        val replay = second.client.await(Envelope.QUESTIONS)
        val questions = json.decodeFromString(ListSerializer(OwnerQuestion.serializer()), replay.data)
        val open = questions.single { it.id == id }
        assertEquals(QuestionStates.OPEN, open.state)
        assertEquals("SQLite", open.recommended)

        second.client.send(Envelope.QUESTION_ANSWER, json.encodeToString(QuestionAnswer.serializer(), QuestionAnswer(id, answer = "SQLite")))
        assertEquals(QuestionStates.ANSWERED, second.client.questionPush(id, QuestionStates.ANSWERED).state)
    }
}
