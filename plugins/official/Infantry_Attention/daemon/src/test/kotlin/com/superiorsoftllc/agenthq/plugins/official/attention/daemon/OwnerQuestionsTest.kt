package com.superiorsoftllc.agenthq.plugins.official.attention.daemon

import com.superiorsoftllc.agenthq.daemon.api.storage.PluginStorage
import java.io.File
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ASK-1: the non-blocking owner questions, over a fake clock and a recording mail sender. */
class OwnerQuestionsTest {
    private var now = 1_000_000L
    private class Mail(val to: String, val subject: String, val body: String)
    private val sent = mutableListOf<Mail>()
    private val pushed = mutableListOf<OwnerQuestion>()
    private var ids = 0

    private fun service(
        repository: OwnerQuestionRepository = InMemoryOwnerQuestionRepository(),
        maxOpen: Int = 3,
        maxPerHour: Int = 6,
        exists: (String) -> Boolean = { true },
        busy: (String) -> Boolean = { false },
        ttlMinutes: Long = 0,
    ) = OwnerQuestions(
        repository = repository,
        notify = { to, subject, body -> sent += Mail(to, subject, body); true },
        clock = { now },
        newId = { "q${++ids}" },
        maxOpen = { maxOpen },
        maxPerHour = { maxPerHour },
        sessionExists = exists,
        sessionBusy = busy,
        ttlMinutes = { ttlMinutes },
    ).also { it.sink = { q -> pushed += q } }

    private val worker = Asker(sessionId = "s-1", agentId = "agent-1", role = "backend", projectId = "p-1")

    private fun ask(defaultAfterMin: Int? = null, allowOther: Boolean = false, recommended: String = "SQLite") = QuestionAsk(
        question = "Which database?",
        options = listOf(QuestionOption("Postgres", "a server to run"), QuestionOption("SQLite", "one file")),
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
        assertEquals("s-1", mail.to, "the asking session: the notifier addresses sessions")
        assertTrue("(thread question:${q.id})" in mail.body, "the notifier has no thread id: it rides in the body")
        assertTrue(q.id in mail.subject && "Postgres" in mail.subject, mail.subject)
        assertTrue("Chosen option: Postgres (a server to run)" in mail.body, mail.body)
        assertEquals(answered, pushed.last())
    }

    @Test
    fun aSessionWithNoAgentIsMailedToo() {
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
        assertFailsWith<IllegalArgumentException> { questions.ask(worker, ask().copy(options = listOf(QuestionOption("SQLite")))) }
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
        assertTrue(QuestionAsk("q", listOf(QuestionOption("a")), "a", "r").allowOther)
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
        assertTrue("(thread question:${q.id})" in sent.last().body, "the override lands in the same thread")
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
        val first = Jdbc(path)
        val asked = try {
            service(StorageOwnerQuestionRepository(first)).ask(worker, ask(defaultAfterMin = 1, allowOther = true))
        } finally {
            first.close()
        }

        now += 2 * 60_000L
        val second = Jdbc(path)
        try {
            val questions = service(StorageOwnerQuestionRepository(second))
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
            second.close()
        }
    }

    @Test
    fun theWireJsonCarriesEveryKeyAndDecodesStrictly() {
        val q = service().ask(worker, ask(defaultAfterMin = 3))
        val text = QuestionJson.questionText(q)
        assertTrue("\"resolvedAt\":null" in text && "\"defaultAfterMin\":3" in text && "\"state\":\"open\"" in text, text)
        assertEquals(listOf(QuestionOption("Postgres", "a server to run"), QuestionOption("SQLite", "one file")), QuestionJson.parseOptions(QuestionJson.options(q.options).toString()))
        assertEquals(QuestionAsk("q", listOf(QuestionOption("a"), QuestionOption("b")), "a", "r", defaultAfterMin = 2),
            QuestionJson.decodeAsk("""{"question":"q","options":[{"label":"a"},{"label":"b","description":""}],"recommended":"a","reason":"r","defaultAfterMin":2}"""))
        assertFailsWith<IllegalArgumentException> { QuestionJson.decodeAsk("""{"question":"q","options":[],"recommended":"a","reason":"r","bogus":1}""") }
        assertEquals(QuestionAnswer("q1", "a", null), QuestionJson.decodeAnswer("""{"questionId":"q1","answer":"a"}"""))
    }

    @Test
    fun orphaningASessionClosesItsOpenQuestionsWithoutMailAndPushesThem() {
        val questions = service()
        val mine = questions.ask(worker, ask())
        val other = questions.ask(Asker("s-2", "agent-2"), ask())
        val closed = questions.orphanSession("s-1")
        assertEquals(listOf(mine.id), closed.map { it.id })
        assertEquals(QuestionStates.CANCELLED, questions.get(mine.id)!!.state)
        assertEquals(QuestionResolvedBy.ORPHANED, questions.get(mine.id)!!.resolvedBy)
        assertEquals(QuestionStates.OPEN, questions.get(other.id)!!.state)
        assertEquals(QuestionStates.CANCELLED, pushed.last().state)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun theSweepOrphansQuestionsWhoseSessionIsGone_AlsoAfterARestart() {
        val repository = InMemoryOwnerQuestionRepository()
        val q = service(repository).ask(worker, ask())
        // "restart": a new service over the same stored rows, the session no longer exists
        val after = service(repository, exists = { false })
        after.sweep()
        assertEquals(QuestionResolvedBy.ORPHANED, after.get(q.id)!!.resolvedBy)
        assertEquals(QuestionStates.CANCELLED, after.get(q.id)!!.state)
    }

    @Test
    fun theSweepExpiresAnOldQuestionOfAnIdleAskerButNotOfABusyOne() {
        var busy = true
        val questions = service(ttlMinutes = 10, busy = { busy })
        val q = questions.ask(worker, ask())
        now += 11 * OwnerQuestions.MINUTE_MS
        questions.sweep()
        assertEquals(QuestionStates.OPEN, questions.get(q.id)!!.state, "a busy asker keeps its question")
        busy = false
        questions.sweep()
        assertEquals(QuestionResolvedBy.ORPHANED, questions.get(q.id)!!.resolvedBy)
    }

    @Test
    fun aTtlOfZeroNeverExpiresAQuestionByAge() {
        val questions = service(ttlMinutes = 0)
        val q = questions.ask(worker, ask())
        now += 1000 * OwnerQuestions.HOUR_MS
        questions.sweep()
        assertEquals(QuestionStates.OPEN, questions.get(q.id)!!.state)
    }

    @Test
    fun theOwnerCanDismissAnOrphanedOpenQuestionByCancel() {
        val questions = service()
        val q = questions.ask(worker, ask())
        assertEquals(QuestionStates.CANCELLED, questions.cancel(q.id, null).state)
    }

    /** A real SQLite file with `owner_questions` as core creates it. */
    private class Jdbc(path: String) : PluginStorage, AutoCloseable {
        private val c: Connection = DriverManager.getConnection("jdbc:sqlite:$path").also {
            it.createStatement().use { s ->
                s.execute(
                    "CREATE TABLE IF NOT EXISTS owner_questions (id TEXT NOT NULL PRIMARY KEY, project_id TEXT, session_id TEXT NOT NULL, agent_id TEXT, role TEXT, " +
                        "question TEXT NOT NULL, options TEXT NOT NULL, recommended TEXT NOT NULL, reason TEXT NOT NULL, allow_other INTEGER NOT NULL DEFAULT 0, " +
                        "default_after_min INTEGER, state TEXT NOT NULL CHECK (state IN ('open', 'answered', 'auto', 'cancelled')), answer TEXT, other_text TEXT, " +
                        "resolved_by TEXT, created_at INTEGER NOT NULL, resolved_at INTEGER, overridden INTEGER NOT NULL DEFAULT 0)",
                )
            }
        }
        override fun migrate(version: Int, sql: List<String>) = sql.forEach { c.createStatement().use { s -> s.execute(it) } }
        override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> = c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs ->
                val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                generateSequence { if (rs.next()) cols.associateWith { k -> rs.getObject(k) } else null }.toList()
            }
        }
        override fun execute(sql: String, args: List<Any?>): Int = c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeUpdate()
        }
        override fun <T> transaction(block: () -> T): T = block()
        override fun close() = c.close()
    }
}
