package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage
import com.superiorsoftllc.infantry.pipeline.PipelineRefusal
import com.superiorsoftllc.infantry.protocol.Envelope
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PIPE-1 / DP-6: the optional project pipeline over a REAL SQLite file (the three core tables, as the daemon creates them).
 * No pipeline is the default, stage names are data, a commit's current stage is its newest entry, an exclusive project refuses
 * a second check in one scope, and the rows survive the store being closed and reopened (a daemon restart).
 */
class PipelineStoreTest {
    /** A [PluginStorage] over a plain JDBC connection; the real one (LocalPluginStorage) is covered by PluginStorageTest. */
    private class JdbcStorage(private val c: Connection) : PluginStorage {
        override fun migrate(version: Int, sql: List<String>) = sql.forEach { c.createStatement().use { s -> s.execute(it) } }
        override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> = c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs ->
                val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                generateSequence { if (rs.next()) cols.associateWith { c -> rs.getObject(c) } else null }.toList()
            }
        }
        override fun execute(sql: String, args: List<Any?>): Int = c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeUpdate()
        }
        override fun <T> transaction(block: () -> T): T = block()
    }

    private val path = Files.createTempFile("infantry-pipeline-", ".db").also { it.toFile().delete() }.toAbsolutePath().toString()
    private val opened = mutableListOf<Connection>()
    private var now = 1_000L

    private fun open(): PipelineStore {
        val c = DriverManager.getConnection("jdbc:sqlite:$path").also { opened += it }
        val db = JdbcStorage(c)
        db.migrate(1, listOf(
            "CREATE TABLE IF NOT EXISTS pipeline_config (project_id TEXT NOT NULL PRIMARY KEY, stages TEXT NOT NULL, exclusive_check INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS pipeline_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, project_id TEXT NOT NULL, commit_sha TEXT NOT NULL, stage TEXT NOT NULL, moved_by TEXT, evidence TEXT, moved_at INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS pipeline_checks (project_id TEXT NOT NULL, scope TEXT NOT NULL, commit_sha TEXT NOT NULL, what TEXT NOT NULL, started_by TEXT, started_at INTEGER NOT NULL, last_output_at INTEGER NOT NULL, PRIMARY KEY (project_id, scope))",
        ))
        return PipelineStore(db, clock = { now })
    }

    @AfterTest
    fun cleanup() {
        opened.forEach { runCatching { it.close() } }
        listOf("", "-wal", "-shm").forEach { java.io.File(path + it).delete() }
    }

    private fun code(block: () -> Unit): String = assertFailsWith<PipelineRefusal> { block() }.code

    @Test
    fun `a project with no pipeline sees nothing and every write is refused no_pipeline`() {
        val pipeline = open()
        val state = pipeline.state("P")
        assertFalse(state.configured)
        assertNull(state.config)
        assertTrue(state.commits.isEmpty() && state.history.isEmpty() && state.checks.isEmpty())
        assertEquals("no_pipeline", code { pipeline.record("P", "abc", "merged", null, null) })
        assertEquals("no_pipeline", code { pipeline.check("P", "start", "", "abc", "suite", null) })
    }

    @Test
    fun `a configured pipeline with no history is empty`() {
        val pipeline = open()
        val state = pipeline.configure("P", listOf("merged", "testing"), exclusiveCheck = false, clear = false)
        assertTrue(state.configured)
        assertEquals(listOf("merged", "testing"), state.config?.stages)
        assertTrue(state.commits.isEmpty() && state.history.isEmpty() && state.checks.isEmpty())
    }

    @Test
    fun `stage transitions keep the newest per commit and the whole history`() {
        val pipeline = open()
        pipeline.configure("P", listOf("merged", "testing", "passed", "failed", "promoted"), false, false)
        pipeline.record("P", "aaa", "merged", null, "git (1)")
        now = 2_000
        pipeline.record("P", "aaa", "testing", null, "qa (2)")
        now = 3_000
        pipeline.record("P", "bbb", "merged", null, null)
        now = 4_000
        val state = pipeline.record("P", "aaa", "failed", "suite=core 3 failed log=/tmp/x.log", "qa (2)")
        assertEquals(listOf("aaa" to "failed", "bbb" to "merged"), state.commits.map { it.commit to it.stage })
        assertEquals("qa (2)", state.commits.first().movedBy)
        assertEquals(4_000, state.commits.first().movedAt)
        assertEquals("suite=core 3 failed log=/tmp/x.log", state.commits.first().evidence)
        assertEquals(4, state.history.size)
        // A failed commit can be retested: any configured stage to any configured stage.
        assertEquals("testing", pipeline.record("P", "aaa", "testing", null, null).commits.first { it.commit == "aaa" }.stage)
    }

    @Test
    fun `an unknown stage is refused and writes nothing`() {
        val pipeline = open()
        pipeline.configure("P", listOf("merged"), false, false)
        assertEquals("unknown_stage", code { pipeline.record("P", "aaa", "shipped", null, null) })
        assertTrue(pipeline.state("P").history.isEmpty())
    }

    @Test
    fun `bad stage lists are refused and clear removes the pipeline`() {
        val pipeline = open()
        assertFailsWith<IllegalArgumentException> { pipeline.configure("P", emptyList(), false, false) }
        assertFailsWith<IllegalArgumentException> { pipeline.configure("P", listOf("a", "a"), false, false) }
        assertFailsWith<IllegalArgumentException> { pipeline.configure("P", listOf("a", " "), false, false) }
        pipeline.configure("P", listOf("a"), false, false)
        assertFalse(pipeline.configure("P", null, false, clear = true).configured)
    }

    @Test
    fun `an exclusive pipeline refuses a second check in the same scope but not another scope`() {
        val pipeline = open()
        pipeline.configure("P", listOf("testing"), exclusiveCheck = true, clear = false)
        pipeline.check("P", "start", "wt1", "aaa", "core suite", "qa (2)")
        val refusal = assertFailsWith<PipelineRefusal> { pipeline.check("P", "start", "wt1", "bbb", "core suite", "qa (2)") }
        assertEquals("check_running", refusal.code)
        assertTrue("core suite" in refusal.message!! && "aaa" in refusal.message!!, refusal.message)
        pipeline.check("P", "start", "wt2", "bbb", "core suite", null)
        assertEquals(2, pipeline.state("P").checks.size)
        // Finishing frees the scope.
        pipeline.check("P", "finish", "wt1", null, null, null)
        assertEquals("bbb", pipeline.check("P", "start", "wt1", "bbb", "again", null).checks.first { it.scope == "wt1" }.commit)
    }

    @Test
    fun `a non exclusive pipeline replaces the running check`() {
        val pipeline = open()
        pipeline.configure("P", listOf("testing"), false, false)
        pipeline.check("P", "start", "", "aaa", "one", null)
        val state = pipeline.check("P", "start", "", "bbb", "two", null)
        assertEquals(listOf("bbb"), state.checks.map { it.commit })
    }

    @Test
    fun `ping moves last output time and finish or ping without a check is refused no_check`() {
        val pipeline = open()
        pipeline.configure("P", listOf("testing"), false, false)
        assertEquals("no_check", code { pipeline.check("P", "ping", "", null, null, null) })
        assertEquals("no_check", code { pipeline.check("P", "finish", "", null, null, null) })
        pipeline.check("P", "start", "", "aaa", "suite", null)
        now = 9_000
        val check = pipeline.check("P", "ping", "", null, null, null).checks.single()
        assertEquals(1_000, check.startedAt)
        assertEquals(9_000, check.lastOutputAt)
        assertTrue(pipeline.check("P", "finish", "", null, null, null).checks.isEmpty())
        assertFailsWith<IllegalArgumentException> { pipeline.check("P", "bogus", "", null, null, null) }
    }

    @Test
    fun `state survives a restart and projects are isolated`() {
        open().apply {
            configure("P", listOf("merged"), true, false)
            record("P", "aaa", "merged", "ev", null)
        }
        opened.forEach { it.close() }
        val reopened = open()
        assertEquals(listOf("aaa"), reopened.state("P").commits.map { it.commit })
        assertTrue(reopened.state("P").config!!.exclusiveCheck)
        assertFalse(reopened.state("Other").configured)
    }

    @Test
    fun `the handler answers pipeline_state and a refusal with its code first`() {
        val handler = PipelineHandler(open(), author = { id -> if (id == "s1") "qa (agent-1)" else null })
        fun send(type: String, data: String) = handler.answer(Frame(type, data))!!
        assertEquals(Envelope.PIPELINE_STATE, send(Envelope.PIPELINE_SHOW, """{"projectId":"P"}""").type)
        val refused = send(Envelope.PIPELINE_RECORD, """{"projectId":"P","commit":"a","stage":"x"}""")
        assertEquals(Envelope.ERROR, refused.type)
        assertTrue(refused.data.startsWith("pipeline_record refused: no_pipeline:"), refused.data)
        send(Envelope.PIPELINE_CONFIG, """{"projectId":"P","stages":["merged"]}""")
        val ok = send(Envelope.PIPELINE_RECORD, """{"projectId":"P","commit":"a","stage":"merged","sessionId":"s1"}""")
        assertTrue("qa (agent-1)" in ok.data, ok.data)
        assertTrue(send(Envelope.PIPELINE_RECORD, """{"projectId":"P","commit":"a","stage":"merged","sessionId":"nope"}""").data.contains("session_not_found"))
    }

    @Test
    fun `a change is pushed to every client and a show or a refusal is not`() {
        val pushed = mutableListOf<Frame>()
        val handler = PipelineHandler(open(), author = { null }, push = { pushed += it })
        handler.answer(Frame(Envelope.PIPELINE_SHOW, """{"projectId":"P"}"""))
        handler.answer(Frame(Envelope.PIPELINE_RECORD, """{"projectId":"P","commit":"a","stage":"x"}"""))
        assertTrue(pushed.isEmpty(), "a show and a refusal push nothing")
        handler.answer(Frame(Envelope.PIPELINE_CONFIG, """{"projectId":"P","stages":["merged"]}"""))
        handler.answer(Frame(Envelope.PIPELINE_RECORD, """{"projectId":"P","commit":"a","stage":"merged"}"""))
        assertEquals(listOf(Envelope.PIPELINE_STATE, Envelope.PIPELINE_STATE), pushed.map { it.type })
        assertTrue("\"commit\":\"a\"" in pushed.last().data, pushed.last().data)
    }
}
