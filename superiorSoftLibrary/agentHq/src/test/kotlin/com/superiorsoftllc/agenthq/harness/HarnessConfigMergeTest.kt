package com.superiorsoftllc.agenthq.harness

import com.superiorsoftllc.agenthq.daemon.AgentHqMcp
import com.superiorsoftllc.agenthq.daemon.CoreHarnessContributions
import com.superiorsoftllc.agenthq.harness.api.ConfigParts
import com.superiorsoftllc.agenthq.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.agenthq.harness.api.HarnessProcess
import com.superiorsoftllc.agenthq.stats.ReadOnlyDb
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HARNESS H8b-1: API 2.11.0 config-merge slot, host queryDb, and the in-place opencode registration through them. */
class HarnessConfigMergeTest {
    @Test fun apiVersionIs2_11() = assertEquals("2.11.0", HARNESS_API_VERSION)

    @Test fun opencodeMergeThroughTheSlotEqualsTheDirectMerge() {
        CoreHarnessContributions.register()
        val launch = AgentHqMcp.Launch("agenthq", listOf("mcp", "serve"), mapOf("A" to "b"))
        val existing = """{"model":"x","permission":"ask"}"""
        val viaSlot = HarnessConfigMerge.merge("opencode", existing, ConfigParts(mcp = HarnessProcess(launch.argv, env = launch.env)))
        assertEquals(AgentHqMcp.mergeOpenCode(existing, launch), viaSlot)
    }

    @Test fun watchPluginIsAppendedOnlyWhenGiven() {
        CoreHarnessContributions.register()
        val plain = HarnessConfigMerge.merge("opencode", """{"a":1}""", ConfigParts())
        assertEquals("""{"a":1}""", plain)
        val withPlugin = HarnessConfigMerge.merge("opencode", """{"a":1}""", ConfigParts(watchPlugin = File("w.js").absoluteFile))
        assertTrue("\"plugin\"" in withPlugin!! && "w.js" in withPlugin)
    }

    @Test fun unknownHarnessLeavesTheConfigAlone() {
        assertEquals("{}", HarnessConfigMerge.merge("nope", "{}", ConfigParts()))
        assertNull(HarnessConfigMerge.merge("nope", null, ConfigParts()))
    }

    @Test fun readOnlyDbQueriesAndRefusesBadInput() {
        val dir = Files.createTempDirectory("rodb").toFile().apply { deleteOnExit() }
        val db = File(dir, "t.db")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c ->
            c.createStatement().use { it.execute("CREATE TABLE s(id TEXT, n INTEGER)"); it.execute("INSERT INTO s VALUES ('a', 7)") }
        }
        assertEquals(listOf(mapOf("id" to "a", "n" to 7)), ReadOnlyDb.query(db, "SELECT id, n FROM s WHERE id = ?", listOf("a"))?.map { r -> r.mapValues { (it.value as? Number)?.toInt() ?: it.value } })
        assertNull(ReadOnlyDb.query(db, "INSERT INTO s VALUES ('b', 1)"))
        assertNull(ReadOnlyDb.query(File(dir, "missing.db"), "SELECT 1"))
    }

    @Test fun hostQueryDbNeedsTheStatsHook() {
        val dir = Files.createTempDirectory("rodb2").toFile().apply { deleteOnExit() }
        val db = File(dir, "t.db")
        DriverManager.getConnection("jdbc:sqlite:${db.absolutePath}").use { c -> c.createStatement().use { it.execute("CREATE TABLE s(id TEXT)") } }
        val services = HarnessHostServices(queryDb = { f, sql, p -> ReadOnlyDb.query(f, sql, p) })
        fun host(hooks: Set<String>) = ModuleHarnessHost("M", dir, HarnessHostDeclaration(hooks = hooks), { true }, services, {})
        assertNull(host(emptySet()).queryDb(db, "SELECT * FROM s"))
        assertEquals(emptyList(), host(setOf("stats")).queryDb(db, "SELECT * FROM s"))
    }
}
