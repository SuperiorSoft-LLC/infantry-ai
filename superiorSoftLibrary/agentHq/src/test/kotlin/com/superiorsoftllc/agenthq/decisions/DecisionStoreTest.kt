package com.superiorsoftllc.agenthq.decisions

import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import java.nio.file.Files
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P10-K4 (docs/AGENTHQ-PHASE10-KNOWLEDGE.md K-D7): the decisions log over a REAL SQLite file. Numbering is per project,
 * a supersede stays inside its project and happens once per decision, the table refuses raw UPDATE/DELETE, and the rows
 * are read back after the store is closed and reopened (a daemon restart).
 */
class DecisionStoreTest {
    private val path = Files.createTempFile("agenthq-decisions-", ".db").also { it.toFile().delete() }.toAbsolutePath().toString()
    private val opened = mutableListOf<SqlAgentHqStore>()

    private fun open(): DecisionStore = DecisionStore(SqlAgentHqStore.open(path).also { opened += it }, clock = { 1_000L })

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

    private fun refusalCode(block: () -> Unit): String = assertFailsWith<DecisionRefusal> { block() }.code

    @Test
    fun numbersArePerProjectFromOneWithNoGap() {
        val store = open()
        assertEquals(listOf(1, 2, 3), (1..3).map { store.add("A", "a$it").number })
        assertEquals(listOf(1, 2), (1..2).map { store.add("B", "b$it").number })
        assertEquals(4, store.add("A", "a4").number)
        assertEquals("D4", store.get("A", "4")!!.ref)
        assertEquals(listOf("D1", "D2"), store.list("B").map { it.ref })
    }

    @Test
    fun supersedeRulesChainsAndRefusals() {
        val store = open()
        store.add("P", "use mongo")                                  // D1
        store.add("P", "tabs")                                       // D2
        val d3 = store.add("P", "use mysql", supersedes = "D1")      // D3 supersedes D1
        assertEquals(1, d3.supersedes)
        assertEquals(3, store.get("P", "D1")!!.supersededBy)

        // D1 is already superseded: the head of its chain (D3) is what a later decision must name.
        assertEquals(DecisionRefusal.ALREADY_SUPERSEDED, refusalCode { store.add("P", "use postgres", supersedes = "1") })
        assertEquals(DecisionRefusal.DECISION_NOT_FOUND, refusalCode { store.add("P", "x", supersedes = "D9") })
        val d4 = store.add("P", "use postgres", supersedes = "3")  // chain D4 -> D3 -> D1, plain-number ref
        assertEquals(3, d4.supersedes)

        assertEquals(listOf("D2", "D4"), store.list("P").map { it.ref }, "only active decisions by default")
        assertEquals(listOf(1, 2, 3, 4), store.list("P", includeSuperseded = true).map { it.number })
        assertEquals(listOf(3, null, 4, null), store.list("P", includeSuperseded = true).map { it.supersededBy })
        // A refused add wrote nothing and took no number.
        assertEquals(5, store.add("P", "next").number)
    }

    @Test
    fun aCrossProjectSupersedeIsRefused() {
        val store = open()
        store.add("A", "a decision")                                  // A's D1
        store.add("B", "b one")
        store.add("B", "b two")                                       // B's D2
        // B has no D3; A's D1 is invisible to B, and B's D1 is not A's.
        assertEquals(DecisionRefusal.DECISION_NOT_FOUND, refusalCode { store.add("A", "x", supersedes = "D2") })
        assertNull(store.get("A", "D2"))
        assertEquals(1, store.add("B", "b three", supersedes = "D1").supersedes)
        assertNull(store.get("A", "D1")!!.supersededBy, "superseding B's D1 never touches A's D1")
        // The table itself refuses a raw cross-project supersede (A has no D2).
        assertFailsWith<SQLException> {
            exec("INSERT INTO decisions(project_id, number, summary, body, supersedes, created_at) VALUES ('A', 2, 'raw', '', 2, 1);")
        }
    }

    @Test
    fun summaryAndBodyShapeAreEnforced() {
        val store = open()
        assertFailsWith<IllegalArgumentException> { store.add("P", "  ") }
        assertFailsWith<IllegalArgumentException> { store.add("P", "one\ntwo") }
        assertFailsWith<IllegalArgumentException> { store.add("P", "x".repeat(DecisionStore.SUMMARY_MAX + 1)) }
        assertFailsWith<IllegalArgumentException> { store.add("P", "ok", body = "y".repeat(DecisionStore.BODY_MAX + 1)) }
        assertFailsWith<IllegalArgumentException> { store.add("P", "ok", supersedes = "X1") }
        assertFailsWith<IllegalArgumentException> { store.add(" ", "ok") }
        val ok = store.add("P", "x".repeat(DecisionStore.SUMMARY_MAX), body = "y".repeat(DecisionStore.BODY_MAX))
        assertEquals(1, ok.number)
    }

    @Test
    fun rawUpdateAndDeleteAbort() {
        val store = open()
        store.add("P", "keep me", body = "why")
        val update = assertFailsWith<SQLException> { exec("UPDATE decisions SET summary = 'edited' WHERE project_id = 'P';") }
        assertTrue("append-only" in (update.message ?: ""), update.message)
        val delete = assertFailsWith<SQLException> { exec("DELETE FROM decisions;") }
        assertTrue("append-only" in (delete.message ?: ""), delete.message)
        assertEquals(1, count("SELECT COUNT(*) FROM decisions;"))
        assertEquals("keep me", store.get("P", "D1")!!.summary)
    }

    @Test
    fun rowsSurviveARestart() {
        val first = open()
        first.add("P", "use sqlite", body = "embedded", author = DecisionAuthor("s1", "agent-1", "backend"))
        first.add("P", "use postgres", supersedes = "D1")
        opened.forEach { it.close() }
        opened.clear()

        val again = open()
        val all = again.list("P", includeSuperseded = true)
        assertEquals(listOf("use sqlite", "use postgres"), all.map { it.summary })
        val d1 = all.first()
        assertEquals("embedded", d1.body)
        assertEquals(Triple("s1", "agent-1", "backend"), Triple(d1.authorSessionId, d1.authorAgentId, d1.authorRole))
        assertEquals(2, d1.supersededBy)
        assertEquals(1_000L, d1.createdAt)
        assertEquals(3, again.add("P", "next").number, "numbering continues after a restart")
    }
}
