package com.superiorsoftllc.agenthq.questions

import com.superiorsoftllc.agenthq.db.AgentHQDatabaseFactory
import com.superiorsoftllc.agenthq.mailbox.MailResult
import com.superiorsoftllc.agenthq.mailbox.MailSendRequest
import com.superiorsoftllc.agenthq.protocol.OwnerQuestion
import com.superiorsoftllc.agenthq.protocol.PromptOption
import com.superiorsoftllc.agenthq.protocol.QuestionAnswer
import com.superiorsoftllc.agenthq.protocol.QuestionAsk
import com.superiorsoftllc.agenthq.protocol.QuestionResolvedBy
import com.superiorsoftllc.agenthq.protocol.QuestionStates
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ASK-1: the non-blocking owner questions, over a fake clock and a recording mail sender. */
class OwnerQuestionsTest {
    private var now = 1_000_000L
    private val sent = mutableListOf<MailSendRequest>()
    private val pushed = mutableListOf<OwnerQuestion>()
    private var ids = 0

    private fun service(
        repository: OwnerQuestionRepository = InMemoryOwnerQuestionRepository(),
        maxOpen: Int = 3,
        maxPerHour: Int = 6,
    ) = OwnerQuestions(
        repository = repository,
        send = { sent += it; MailResult.Refused("not delivered in this test") },
        clock = { now },
        newId = { "q${++ids}" },
        maxOpen = { maxOpen },
        maxPerHour = { maxPerHour },
    ).also { it.sink = { q -> pushed += q } }

    private val worker = Asker(sessionId = "s-1", agentId = "agent-1", role = "backend", projectId = "p-1")

    private fun ask(defaultAfterMin: Int? = null, allowOther: Boolean = false, recommended: String = "SQLite") = QuestionAsk(
        question = "Which database?",
        options = listOf(PromptOption("Postgres", "a server to run"), PromptOption("SQLite", "one file")),
        recommended = recommended,
        reason = "no server to operate",
        allowOther = allowOther,
        defaultAfterMin = defaultAfterMin,
    )

    @Test
    fun askReturnsAnOpenQuestionAtOnceAndPushesItWithoutMailing() {
        val questions = service()
        val q = questions.ask(worker, ask())
        assertEquals(QuestionStates.OPEN, q.state)
        assertEquals("SQLite", q.recommended)
        assertEquals("no server to operate", q.reason)
        assertEquals("agent-1", q.agentId)
        assertEquals("backend", q.role)
        assertEquals(listOf(q), pushed)
        assertTrue(sent.isEmpty(), "asking mails nobody")
    }

    @Test
    fun theOwnersPickIsMailedToTheAskingAgentNamingTheQuestionAndTheOption() {
        val questions = service()
        val q = questions.ask(worker, ask())
        val answered = questions.answer(QuestionAnswer(q.id, answer = "Postgres"))

        assertEquals(QuestionStates.ANSWERED, answered.state)
        assertEquals(QuestionResolvedBy.OWNER, answered.resolvedBy)
        val mail = sent.single()
        assertEquals(OwnerQuestions.OWNER, mail.from)
        assertEquals("agent-1", mail.to, "the agent id: it outlives the session that asked")
        assertEquals("question:${q.id}", mail.threadId)
        assertTrue(q.id in mail.subject && "Postgres" in mail.subject, mail.subject)
        assertTrue("Chosen option: Postgres (a server to run)" in mail.body, mail.body)
        assertEquals(answered, pushed.last())
    }

    @Test
    fun aSessionWithNoAgentIsMailedByItsSessionId() {
        val questions = service()
        val q = questions.ask(Asker("s-9"), ask())
        questions.answer(QuestionAnswer(q.id, answer = "SQLite"))
        assertEquals("s-9", sent.single().to)
    }

    @Test
    fun malformedQuestionsAreRefused() {
        val questions = service()
        val noSuch = assertFailsWith<IllegalArgumentException> { questions.ask(worker, ask(recommended = "MySQL")) }
        assertTrue("not one of the options" in noSuch.message!!, noSuch.message)
        assertFailsWith<IllegalArgumentException> { questions.ask(worker, ask(recommended = " ")) }
        assertFailsWith<IllegalArgumentException> { questions.ask(worker, ask().copy(reason = "")) }
        assertFailsWith<IllegalArgumentException> { questions.ask(worker, ask().copy(options = listOf(PromptOption("SQLite")))) }
        assertFailsWith<IllegalArgumentException> { questions.ask(worker, ask(defaultAfterMin = 0)) }
        assertTrue(questions.list().isEmpty())
    }

    @Test
    fun answersAreValidatedAgainstTheQuestion() {
        val questions = service()
        val q = questions.ask(worker, ask())
        assertFailsWith<IllegalArgumentException> { questions.answer(QuestionAnswer(q.id, answer = "MySQL")) }
        assertFailsWith<IllegalArgumentException> { questions.answer(QuestionAnswer(q.id)) }
        assertFailsWith<IllegalArgumentException> { questions.answer(QuestionAnswer("nope", answer = "SQLite")) }
        questions.answer(QuestionAnswer(q.id, answer = "SQLite"))
        val twice = assertFailsWith<IllegalArgumentException> { questions.answer(QuestionAnswer(q.id, answer = "Postgres")) }
        assertTrue("already answered" in twice.message!!, twice.message)
        assertEquals(1, sent.size)
    }

    @Test
    fun otherTextIsDeliveredWhenTheQuestionAllowsIt() {
        val questions = service()
        val q = questions.ask(worker, ask(allowOther = true))
        val answered = questions.answer(QuestionAnswer(q.id, otherText = "use DuckDB"))
        assertEquals(null, answered.answer)
        assertTrue("Custom answer from the owner: use DuckDB" in sent.single().body, sent.single().body)
    }

    @Test
    fun customAnswerMailSaysCustomNotChosenOption() {
        val questions = service()
        val q = questions.ask(worker, ask(allowOther = true))
        questions.answer(QuestionAnswer(q.id, otherText = "  use DuckDB  "))
        val mail = sent.single()
        assertTrue("Custom answer from the owner: use DuckDB" in mail.body, mail.body)
        assertTrue("Chosen option" !in mail.body, mail.body)
        assertTrue(mail.subject.startsWith("Custom answer"), mail.subject)
    }

    @Test
    fun blankCustomAnswerIsRefused() {
        val questions = service()
        val q = questions.ask(worker, ask(allowOther = true))
        assertFailsWith<IllegalArgumentException> { questions.answer(QuestionAnswer(q.id, otherText = "   ")) }
        assertEquals(0, sent.size)
    }

    @Test
    fun otherIsOnByDefaultAndAnOldFrameWithoutItStillDecodes() {
        assertTrue(QuestionAsk("q", listOf(PromptOption("a")), "a", "r").allowOther)
        assertTrue(QuestionAsk.decode("""{"question":"q","options":[{"label":"a"}],"recommended":"a","reason":"r"}""").allowOther)
        assertEquals(false, QuestionAsk.decode("""{"question":"q","options":[{"label":"a"}],"recommended":"a","reason":"r","allowOther":false}""").allowOther)
    }

    @Test
    fun withoutDefaultAfterAQuestionWaitsForever() {
        val questions = service()
        questions.ask(worker, ask())
        now += 30L * 24 * 3_600_000
        assertTrue(questions.sweep().isEmpty())
        assertEquals(QuestionStates.OPEN, questions.list().single().state)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun defaultAfterAutoPicksTheRecommendationOnceDueAndTheOwnerCanOverride() {
        val questions = service()
        val q = questions.ask(worker, ask(defaultAfterMin = 5))

        now += 5 * 60_000L - 1
        assertTrue(questions.sweep().isEmpty(), "not due one millisecond early")

        now += 1
        val auto = questions.sweep().single()
        assertEquals(QuestionStates.AUTO, auto.state)
        assertEquals("SQLite", auto.answer)
        assertEquals(QuestionResolvedBy.AUTO, auto.resolvedBy)
        assertTrue("auto-selected after 5 min, owner did not answer" in sent.single().body, sent.single().body)
        assertTrue(questions.sweep().isEmpty(), "picked once")

        val overridden = questions.answer(QuestionAnswer(q.id, answer = "Postgres"))
        assertEquals(QuestionStates.ANSWERED, overridden.state)
        assertTrue(overridden.overridden)
        assertEquals(2, sent.size)
        assertTrue("OVERRIDE" in sent.last().body && "'SQLite'" in sent.last().body, sent.last().body)
        assertEquals("question:${q.id}", sent.last().threadId, "the override lands in the same thread")
    }

    @Test
    fun anAgentMayHoldAtMostMaxOpenQuestions() {
        val questions = service(maxOpen = 2)
        val first = questions.ask(worker, ask())
        questions.ask(worker, ask())
        val refused = assertFailsWith<IllegalArgumentException> { questions.ask(worker, ask()) }
        assertTrue("rate limit" in refused.message!! && "carry on" in refused.message!!, refused.message)
        // Another agent is not limited by this one's questions.
        questions.ask(Asker("s-2", agentId = "agent-2"), ask())
        // A resolved question frees a slot.
        questions.answer(QuestionAnswer(first.id, answer = "SQLite"))
        questions.ask(worker, ask())
    }

    @Test
    fun anAgentMayAskAtMostMaxPerHourInAnyRollingHour() {
        val questions = service(maxOpen = 10, maxPerHour = 2)
        questions.answer(QuestionAnswer(questions.ask(worker, ask()).id, answer = "SQLite"))
        now += 10 * 60_000L
        questions.answer(QuestionAnswer(questions.ask(worker, ask()).id, answer = "SQLite"))
        assertFailsWith<IllegalArgumentException> { questions.ask(worker, ask()) }
        now += 50 * 60_000L + 1
        questions.ask(worker, ask())
    }

    @Test
    fun theOwnerCancelsAndTheAgentIsToldTheAskerCancelsSilently() {
        val questions = service()
        val a = questions.ask(worker, ask())
        val cancelled = questions.cancel(a.id, bySession = null)
        assertEquals(QuestionStates.CANCELLED, cancelled.state)
        assertEquals(QuestionResolvedBy.OWNER, cancelled.resolvedBy)
        assertTrue("cancelled" in sent.single().subject)

        val b = questions.ask(worker, ask())
        assertFailsWith<IllegalArgumentException> { questions.cancel(b.id, bySession = "someone-else") }
        assertEquals(QuestionResolvedBy.ASKER, questions.cancel(b.id, bySession = "s-1").resolvedBy)
        assertEquals(1, sent.size, "the asker withdrew it: no mail")
    }

    @Test
    fun questionsSurviveARestartAndAnOverdueOneIsPickedByTheNextSweep() {
        val path = File(Files.createTempDirectory("agenthq-questions").toFile(), "agenthq.db").absolutePath
        val firstHandle = AgentHQDatabaseFactory.open(path)
        val asked = try {
            service(SqlOwnerQuestionRepository(firstHandle.database)).ask(worker, ask(defaultAfterMin = 1, allowOther = true))
        } finally {
            firstHandle.close()
        }

        now += 2 * 60_000L
        val secondHandle = AgentHQDatabaseFactory.open(path)
        try {
            val questions = service(SqlOwnerQuestionRepository(secondHandle.database))
            assertEquals(listOf(asked), questions.list(), "every column reads back as stored")
            val auto = questions.sweep().single()
            assertEquals(asked.id, auto.id)
            questions.answer(QuestionAnswer(asked.id, answer = "Postgres", otherText = "and back it up"))
            val stored = questions.get(asked.id)!!
            assertEquals(QuestionStates.ANSWERED, stored.state)
            assertEquals("Postgres", stored.answer)
            assertEquals("and back it up", stored.otherText)
            assertTrue(stored.overridden)
        } finally {
            secondHandle.close()
        }
    }
}
