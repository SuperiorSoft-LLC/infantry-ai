package com.superiorsoftllc.agenthq.plugins.official.attention.daemon

import com.superiorsoftllc.agenthq.daemon.api.storage.PluginStorage
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ATTN-2b: the attention store over a REAL SQLite file with the table as core creates it. */
class AttentionStoreTest {
    private val path = Files.createTempFile("agenthq-attention-", ".db").also { it.toFile().delete() }.toAbsolutePath().toString()
    private val opened = mutableListOf<Connection>()

    private class Jdbc(private val c: Connection) : PluginStorage {
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
    }

    private fun db(): PluginStorage {
        val c = DriverManager.getConnection("jdbc:sqlite:$path").also { opened += it }
        return Jdbc(c).also {
            it.migrate(1, listOf(
                "CREATE TABLE IF NOT EXISTS attention (id TEXT NOT NULL PRIMARY KEY, created_at INTEGER NOT NULL, kind TEXT NOT NULL, trigger_id TEXT, ticket_id TEXT, " +
                    "pod_id TEXT, session_id TEXT, outcome TEXT NOT NULL, detail TEXT NOT NULL, acked_at INTEGER)",
            ))
        }
    }

    @AfterTest
    fun cleanup() {
        opened.forEach { runCatching { it.close() } }
        listOf("", "-wal", "-shm").forEach { java.io.File(path + it).delete() }
    }

    @Test
    fun attentionIsDurableOrderedAndAcknowledgedIdempotently() {
        var nextId = 0
        val pushed = mutableListOf<String>()
        val attention = AttentionStore(db(), clock = { 100L + nextId }, idFactory = { "attention-${++nextId}" })
        attention.eventSink = { pushed += "${it.id}:${it.ackedAt}" }

        val first = attention.raise("trigger_tripped", triggerId = "trigger-1", outcome = "FAILED", detail = "trigger run failed")
        val second = attention.raise("ceiling", podId = "pod-2", outcome = "REFUSED", detail = "human intervention required")
        assertEquals(listOf(first, second), attention.list())
        assertEquals(2, attention.openCount())

        val afterRestart = AttentionStore(db())
        assertEquals(listOf(first, second), afterRestart.list())
        val acked = assertNotNull(afterRestart.acknowledge(first.id, at = 500L))
        assertEquals(500L, acked.ackedAt)
        assertEquals(1, afterRestart.openCount())
        assertEquals(acked, afterRestart.acknowledge(first.id, at = 600L))
        assertNull(afterRestart.acknowledge("missing", at = 700L))
        assertEquals(listOf("attention-1:null", "attention-2:null"), pushed)
    }

    @Test
    fun anAcknowledgementIsPushedOnce() {
        val pushed = mutableListOf<Long?>()
        val s = AttentionStore(db())
        s.eventSink = { pushed += it.ackedAt }
        val item = s.raise("stalled", ticketId = "t1", outcome = "silent", detail = "d")
        s.acknowledge(item.id, at = 5L); s.acknowledge(item.id, at = 9L)
        assertEquals(listOf(null, 5L), pushed)
    }

    @Test
    fun refusesBlankTextAndUnknownKinds() {
        val s = AttentionStore(db())
        assertFailsWith<IllegalArgumentException> { s.raise("stalled", outcome = " ", detail = "d") }
        assertFailsWith<IllegalArgumentException> { s.raise("stalled", outcome = "o", detail = "") }
        assertFailsWith<IllegalArgumentException> { s.raise("bogus", outcome = "o", detail = "d") }
        assertTrue(s.list().isEmpty())
    }

    @Test
    fun theWireFormCarriesEveryKey() {
        val s = AttentionStore(db(), clock = { 7L }, idFactory = { "a1" })
        val item = s.raise("stalled", ticketId = "t1", outcome = "o", detail = "d")
        assertEquals(
            """{"id":"a1","at":7,"kind":"stalled","triggerId":null,"ticketId":"t1","podId":null,"sessionId":null,"outcome":"o","detail":"d","ackedAt":null}""",
            AttentionJson.itemText(item),
        )
        assertEquals("[${AttentionJson.itemText(item)}]", AttentionJson.listText(s.list()))
    }
}
