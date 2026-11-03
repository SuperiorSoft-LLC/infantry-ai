package com.superiorsoftllc.infantry.plugins.official.triggers.daemon

import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.protocol.TriggerAction
import com.superiorsoftllc.infantry.protocol.TriggerEventView
import com.superiorsoftllc.infantry.protocol.TriggerView
import java.nio.file.Files
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TRIG-2b: the same script of inputs against core's TriggerRepository and [StorageTriggerStore] (on a core-created file); same results. */
class TriggerStoreContractTest {
    private class JdbcStorage(private val c: Connection) : PluginStorage {
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

    private fun trigger(id: String, at: Long, prompt: String? = null) = TriggerView(
        id = id, name = "n-$id", kind = "schedule", action = TriggerAction("prompt", "agent-1", prompt), enabled = true, state = "armed",
        coalesceMs = 1000, ceilingRuns = 5, ceilingWindowMs = 60_000, createdAt = at, intervalMs = 5000, projectId = "p1",
        lastFiredAt = at + 1, nextFireAt = at + 2,
    )

    private fun script(s: TriggerStore): List<Any?> {
        val out = mutableListOf<Any?>()
        fun MutableList<Any?>.r(v: Any?) { add(v) }
        out.r(s.get("a")); out.r(s.all()); out.r(s.intervals())
        s.upsert(trigger("b", 20), 5000); s.upsert(trigger("a", 10, "hi"), null)
        out.r(s.all()); out.r(s.intervals()); out.r(s.get("a"))
        s.setPendingMail("a", "mail-1"); out.r(s.pendingMail("a"))
        s.upsert(trigger("a", 10, "hi").copy(state = "running"), 7000) // keeps the pending mail
        out.r(s.pendingMail("a")); out.r(s.get("a"))
        s.setPendingMail("a", null); out.r(s.pendingMail("a")); out.r(s.pendingMail("zzz"))
        for (i in 1..6) s.insertEvent(TriggerEventView("e$i", "a", i * 100L, "webhook", "started", if (i % 2 == 0) "d$i" else null, null), keep = 4)
        s.insertEvent(TriggerEventView("e3", "a", 300, "webhook", "duplicate", null, "dg"), keep = 4)
        out.r(s.events("a")); out.r(s.events("b"))
        s.setWebhookSecret("a", "s1"); s.setWebhookSecret("a", "s2"); out.r(s.webhookSecret("a")); out.r(s.webhookSecret("b"))
        s.deleteWebhookSecret("a"); out.r(s.webhookSecret("a"))
        s.setWebhookSecret("b", "sb")
        out.r(s.delete("b")); out.r(s.delete("b")); out.r(s.events("b")); out.r(s.webhookSecret("b")); out.r(s.all())
        return out
    }

    @Test
    fun `core repository and the plugin store give the same results`() {
        val dir = Files.createTempDirectory("trig2b")
        val core = SqlInfantryStore.open(dir.resolve("core.db").toString())
        val coreResult = script(core.triggers)
        core.close()
        SqlInfantryStore.open(dir.resolve("plugin.db").toString()).close()
        val conn = DriverManager.getConnection("jdbc:sqlite:${dir.resolve("plugin.db")}")
        val pluginResult = conn.use { script(StorageTriggerStore(JdbcStorage(it))) }
        assertEquals(coreResult, pluginResult)
        assertTrue(coreResult.contains("mail-1")); assertNull(coreResult.first()); assertFalse(coreResult.contains("s1"))
    }
}
