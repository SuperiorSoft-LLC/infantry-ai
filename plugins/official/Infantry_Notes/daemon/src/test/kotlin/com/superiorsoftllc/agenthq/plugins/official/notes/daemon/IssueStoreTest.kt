package com.superiorsoftllc.agenthq.plugins.official.notes.daemon

import com.superiorsoftllc.agenthq.decisions.*
import com.superiorsoftllc.agenthq.issues.*
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P10-K6 (docs/AGENTHQ-PHASE10-KNOWLEDGE.md K-D8): the issues log over a REAL SQLite file. The active issue is per
 * session, an attempt with nowhere to go opens an implicit issue, the status is derived from the fix, precheck matches
 * by path segment, both tables refuse raw UPDATE/DELETE, and everything is read back after a reopen (a daemon restart).
 */
class IssueStoreTest {
    private val path = Files.createTempFile("agenthq-issues-", ".db").also { it.toFile().delete() }.toAbsolutePath().toString()
    private val opened = mutableListOf<java.sql.Connection>()

    /** A FIXED clock on purpose: the store must still order "most recent" without ties. */
    private fun open(): IssueStore = IssueStore(NotesTestDb.open(path).also { opened += it.first }.second, clock = { 1_000L })

    private val s1 = IssueActor("s1", "agent-1")
    private val s2 = IssueActor("s2", "agent-2")
    private val s3 = IssueActor("s3", "agent-3")

    @AfterTest
    fun cleanup() {
        opened.forEach { runCatching { it.close() } }
        listOf("", "-wal", "-shm").forEach { java.io.File(path + it).delete() }
    }

    private fun exec(sql: String) = DriverManager.getConnection("jdbc:sqlite:$path?foreign_keys=true").use { conn ->
        conn.createStatement().use { it.executeUpdate(sql) }
    }

    private fun count(sql: String): Int = DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
        conn.createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getInt(1) } }
    }

    private fun refusalCode(block: () -> Unit): String = assertFailsWith<IssueRefusal> { block() }.code

    @Test
    fun twoSessionsAttemptsNeverCross() {
        val store = open()
        assertEquals("I1", store.open("P", "login 500", "src/auth/Login.kt", actor = s1).ref)
        assertEquals("I2", store.open("P", "cart total off", "src/cart/Cart.kt", actor = s2).ref)

        // s2 opened I2 AFTER s1 opened I1: a project-wide "most recent" would send s1's attempt to I2.
        val a1 = store.attempt("P", "null check on token", actor = s1)
        assertEquals(1, a1.number, "s1's attempt goes to s1's own issue")
        val a2 = store.attempt("P", "round half up", actor = s2)
        assertEquals(2, a2.number, "s2's attempt goes to s2's own issue")
        assertEquals(listOf("s1"), store.get("P", "I1")!!.events.map { it.sessionId })
        assertEquals(listOf("s2"), store.get("P", "I2")!!.events.map { it.sessionId })

        // s1's fix closes I1, never I2.
        assertEquals(1, store.fix("P", "token refreshed before use", actor = s1).number)
        assertFalse(store.get("P", "I2")!!.fixed, "s1's fix never touches s2's issue")
        assertNull(store.active("P", "s1"), "s1's only issue is fixed: no active issue left")
        assertEquals(2, store.active("P", "s2")!!.number)

        // A third session that touched nothing gets an implicit issue, never someone else's.
        val a3 = store.attempt("P", "cleared cache", actor = s3)
        assertEquals(3, a3.number)
        assertTrue(a3.implicit)
        assertEquals(1, store.get("P", "I2")!!.events.size, "s3's attempt never landed on s2's open issue")
        assertEquals(IssueRefusal.NO_ACTIVE_ISSUE, refusalCode { store.fix("P", "x", actor = s1) })
    }

    @Test
    fun namingAnIssueInAnAttemptMakesItTheSessionsActiveIssue() {
        val store = open()
        store.open("P", "a", "src/a", actor = s1)                 // I1, s1
        store.open("P", "b", "src/b", actor = s2)                 // I2, s2
        store.attempt("P", "look at a", issue = "I1", actor = s2) // s2 names I1: now its most recent touch
        assertEquals(1, store.attempt("P", "more on a", actor = s2).number)
        assertEquals(1, store.active("P", "s1")!!.number, "s1 still on I1")
        // Fixing I1 drops s2 back to its older open issue.
        store.fix("P", "done", issue = "1", actor = s2)
        assertEquals(2, store.active("P", "s2")!!.number)
        assertNull(store.active("P", "s1"))
        assertNull(store.active("P", null))
        assertNull(store.active("Q", "s2"), "the active issue is per project too")
    }

    @Test
    fun anAttemptWithNowhereToGoOpensAnImplicitIssue() {
        val store = open()
        val first = store.attempt("P", "bumped the timeout\nsecond line of detail", location = "src/net/Http.kt:42", actor = s1)
        assertEquals("I1", first.ref)
        assertTrue(first.implicit)
        assertEquals("bumped the timeout", first.summary, "named by the attempt's first line")
        assertEquals("src/net/Http.kt:42", first.location)
        assertEquals(Issue.STATUS_OPEN, first.status)
        assertEquals("bumped the timeout\nsecond line of detail", first.events.single().summary)
        assertEquals(1, store.attempt("P", "retry on 503", actor = s1).number, "the implicit issue is now s1's active one")

        // No session at all: nothing is active, so each unnamed attempt opens its own implicit issue.
        val anon1 = store.attempt("P", "anon one")
        val anon2 = store.attempt("P", "anon two")
        assertEquals(listOf(2, 3), listOf(anon1.number, anon2.number))
        assertTrue(anon1.implicit && anon2.implicit)
        assertEquals("", anon1.location)
        assertNull(anon1.openedBySession)
        val long = store.attempt("P", "z".repeat(IssueStore.SUMMARY_MAX + 50))
        assertEquals(IssueStore.SUMMARY_MAX, long.summary.length)
        assertFalse(store.open("P", "explicit", "src/x", actor = s2).implicit)
    }

    @Test
    fun outcomesDefaultToFailedAndAnUnknownOneIsRefused() {
        val store = open()
        store.open("P", "flaky test", "src/test/FlakyTest.kt", actor = s1)
        assertEquals("failed", store.attempt("P", "added sleep", actor = s1).events.last().outcome)
        assertEquals("partial", store.attempt("P", "added retry", outcome = "partial", actor = s1).events.last().outcome)
        assertEquals("worked", store.attempt("P", "fixed the race", outcome = "worked", actor = s1).events.last().outcome)
        val bad = assertFailsWith<IllegalArgumentException> { store.attempt("P", "x", outcome = "succeeded", actor = s1) }
        assertFalse(bad is IssueRefusal, "an unknown outcome is a shape error, not a coded refusal")
        assertTrue("invalid outcome" in (bad.message ?: ""), bad.message)
        assertEquals(3, count("SELECT COUNT(*) FROM issue_events;"), "the refused attempt wrote nothing")
        assertFalse(store.get("P", "I1")!!.fixed, "an attempt that worked does not fix the issue")
        assertEquals(listOf(1, 2, 3), store.get("P", "I1")!!.events.map { it.seq })
    }

    @Test
    fun fixRulesAndRefusals() {
        val store = open()
        assertEquals(IssueRefusal.NO_ACTIVE_ISSUE, refusalCode { store.fix("P", "x") })
        assertEquals(IssueRefusal.NO_ACTIVE_ISSUE, refusalCode { store.fix("P", "x", actor = s1) })
        store.open("P", "one", "src/one", actor = s1)
        val fixed = store.fix("P", "patched", actor = s1)
        assertEquals(Issue.STATUS_FIXED, fixed.status)
        assertEquals(listOf("fix"), fixed.events.map { it.kind })
        assertNull(fixed.events.single().outcome)

        assertEquals(IssueRefusal.ALREADY_FIXED, refusalCode { store.fix("P", "again", issue = "I1") })
        assertEquals(IssueRefusal.ALREADY_FIXED, refusalCode { store.attempt("P", "recurs", issue = "I1") })
        assertEquals(IssueRefusal.ISSUE_NOT_FOUND, refusalCode { store.fix("P", "x", issue = "I9") })
        assertEquals(IssueRefusal.ISSUE_NOT_FOUND, refusalCode { store.attempt("Q", "x", issue = "I1") }, "another project's I1 is not found")
        assertNull(store.get("Q", "I1"))
        assertEquals(1, count("SELECT COUNT(*) FROM issue_events;"), "no refusal wrote an event")
        assertEquals(1, count("SELECT COUNT(*) FROM issues;"), "no refusal opened an issue")
    }

    @Test
    fun shapeIsEnforced() {
        val store = open()
        assertFailsWith<IllegalArgumentException> { store.open("P", "ok", "  ") }
        assertFailsWith<IllegalArgumentException> { store.open("P", " ", "src/a") }
        assertFailsWith<IllegalArgumentException> { store.open("P", "one\ntwo", "src/a") }
        assertFailsWith<IllegalArgumentException> { store.open("P", "x".repeat(IssueStore.SUMMARY_MAX + 1), "src/a") }
        assertFailsWith<IllegalArgumentException> { store.open(" ", "ok", "src/a") }
        assertFailsWith<IllegalArgumentException> { store.attempt("P", " ") }
        assertFailsWith<IllegalArgumentException> { store.attempt("P", "x", issue = "D1") }
        assertFailsWith<IllegalArgumentException> { store.precheck("P", " ") }
        assertEquals(0, count("SELECT COUNT(*) FROM issues;"))
        assertEquals("T-9", store.open("P", "ok", "src/a", ticketId = " T-9 ").ticketId)
    }

    @Test
    fun precheckMatchesBySegmentOpenFirst() {
        val store = open()
        store.open("P", "a dir", "src/a")                       // I1
        store.open("P", "sibling", "src/ab/x.kt")               // I2: shares the prefix text, not the segment
        store.open("P", "windows path", "src\\a\\b.kt:12")      // I3
        store.open("P", "c file", "./src/a/c.kt")               // I4, fixed below
        store.open("P", "elsewhere", "lib/a")                   // I5
        store.open("Q", "other project", "src/a")               // Q's I1
        store.attempt("P", "tried", issue = "I3")
        store.fix("P", "done", issue = "I4")

        val hits = store.precheck("P", "src/a")
        assertEquals(listOf("I1", "I3", "I4"), hits.map { it.ref }, "open first, then fixed; never src/ab, never Q")
        assertEquals(listOf("tried"), hits[1].events.map { it.summary }, "with their attempts")
        assertEquals(listOf("I3"), store.precheck("P", "src/a/b.kt:40").map { it.ref }, "the :line suffix is dropped")
        assertEquals(listOf("I3"), store.precheck("P", "src\\a\\b.kt").map { it.ref })
        assertEquals(listOf("I2"), store.precheck("P", "src/ab/").map { it.ref })
        assertEquals(emptyList(), store.precheck("P", "src/a/b").map { it.ref }, "b is not b.kt")

        assertEquals(listOf("I1", "I3"), store.list("P", location = "src/a").map { it.ref }, "list hides fixed by default")
        assertEquals(listOf("I1", "I3", "I4"), store.list("P", includeFixed = true, location = "src/a").map { it.ref })
        assertEquals(listOf("I1", "I2", "I3", "I5"), store.list("P").map { it.ref })
    }

    @Test
    fun rawUpdateAndDeleteAbortOnBothTables() {
        val store = open()
        store.open("P", "keep me", "src/a", actor = s1)
        store.attempt("P", "tried", actor = s1)
        for ((sql, word) in listOf(
            "UPDATE issues SET summary = 'edited' WHERE project_id = 'P';" to "append-only",
            "DELETE FROM issues;" to "append-only",
            "UPDATE issue_events SET outcome = 'worked';" to "append-only",
            "DELETE FROM issue_events;" to "append-only",
        )) {
            val e = assertFailsWith<SQLException>(sql) { exec(sql) }
            assertTrue(word in (e.message ?: ""), "$sql -> ${e.message}")
        }
        // One fix, then nothing: the database refuses a second fix and a late attempt, raw SQL included.
        exec("INSERT INTO issue_events(project_id, issue_number, seq, kind, summary, outcome, created_at) VALUES ('P', 1, 2, 'fix', 'f', NULL, 5);")
        assertFailsWith<SQLException> {
            exec("INSERT INTO issue_events(project_id, issue_number, seq, kind, summary, outcome, created_at) VALUES ('P', 1, 3, 'attempt', 'late', 'failed', 6);")
        }
        assertFailsWith<SQLException>("an attempt needs a known outcome") {
            exec("INSERT INTO issues(project_id, number, summary, created_at) VALUES ('P', 2, 'two', 1);")
            exec("INSERT INTO issue_events(project_id, issue_number, seq, kind, summary, outcome, created_at) VALUES ('P', 2, 1, 'attempt', 'x', 'great', 6);")
        }
        assertFailsWith<SQLException>("an event needs its issue") {
            exec("INSERT INTO issue_events(project_id, issue_number, seq, kind, summary, outcome, created_at) VALUES ('P', 9, 1, 'attempt', 'x', 'failed', 6);")
        }
        assertEquals(2, count("SELECT COUNT(*) FROM issues;"))
        assertEquals(2, count("SELECT COUNT(*) FROM issue_events;"))
        assertEquals("keep me", store.get("P", "I1")!!.summary)
        assertTrue(store.get("P", "I1")!!.fixed)
    }

    @Test
    fun issuesSurviveARestartAndTheActiveIssueWithThem() {
        val first = open()
        first.open("P", "survive me", "src/a.kt", ticketId = "T-1", actor = s1)    // I1
        first.open("P", "s2's", "src/b.kt", actor = s2)                              // I2
        first.attempt("P", "tried once", outcome = "partial", actor = s1)
        opened.forEach { it.close() }
        opened.clear()

        val again = open()
        val i1 = again.get("P", "I1")!!
        assertEquals("survive me", i1.summary)
        assertEquals("T-1", i1.ticketId)
        assertEquals("s1" to "agent-1", i1.openedBySession to i1.openedByAgent)
        assertEquals(listOf("partial"), i1.events.map { it.outcome })
        // The active issue is derived from the rows, so it survives too, still per session.
        assertEquals(1, again.attempt("P", "tried twice", actor = s1).number)
        assertEquals(2, again.attempt("P", "s2 again", actor = s2).number)
        assertTrue(again.get("P", "I1")!!.events.last().createdAt > i1.events.last().createdAt, "time keeps moving after a restart")
        assertEquals(3, again.open("P", "next", "src/c.kt").number, "numbering continues after a restart")
    }
}
