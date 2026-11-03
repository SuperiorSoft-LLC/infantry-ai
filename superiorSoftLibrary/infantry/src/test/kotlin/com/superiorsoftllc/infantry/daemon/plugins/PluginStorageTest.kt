// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.storage.NoPluginStorage
import com.superiorsoftllc.infantry.db.InfantryDatabaseFactory
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** DAEMON-STORAGE-1: prefix refusal, adopt list, idempotent migrate, transaction rollback, grant. */
class PluginStorageTest {
    private val dir = Files.createTempDirectory("pstore")
    private val handle = InfantryDatabaseFactory.open(dir.resolve("t.db").toString())

    @AfterTest fun close() { handle.close() }

    private fun storage(id: String = "Acme_Notes", adopt: List<String> = emptyList(), may: () -> Boolean = { true }) =
        LocalPluginStorage(handle, id, adopt, may)

    private fun versionOf(): Long? = handle.driver.executeQuery(null, "SELECT version FROM plugin_storage_versions WHERE module = 'Acme_Notes'", { c: app.cash.sqldelight.db.SqlCursor ->
        app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) (c as app.cash.sqldelight.driver.jdbc.JdbcCursor).resultSet.getLong(1) else null)
    }, 0).value

    @Test fun `prefix is the lowercased id`() {
        assertEquals("acme_notes_", LocalPluginStorage.prefixOf("Acme_Notes"))
        assertEquals("a_b_", LocalPluginStorage.prefixOf("a-b"))
    }

    @Test fun `own prefix works and other tables are refused`() {
        val s = storage()
        s.migrate(1, listOf("CREATE TABLE IF NOT EXISTS acme_notes_n (id INTEGER PRIMARY KEY, body TEXT)"))
        assertEquals(1, s.execute("INSERT INTO acme_notes_n (body) VALUES (?)", listOf("hi")))
        assertEquals("hi", s.query("SELECT body FROM acme_notes_n WHERE id = ?", listOf(1)).single()["body"])
        for (bad in listOf(
            "SELECT * FROM tickets", "SELECT * FROM acme_notes_n JOIN tickets ON 1", "UPDATE tickets SET x = 1",
            "DELETE FROM runs", "INSERT INTO runs (id) VALUES (1)", "DROP TABLE tickets", "SELECT * FROM acme_notes_n, runs",
            "SELECT * FROM acme_notes_n AS a, runs", "SELECT * FROM main.acme_notes_n", "SELECT * FROM plugin_storage_versions",
            "ATTACH DATABASE 'x' AS y", "PRAGMA user_version", "SELECT 1; DROP TABLE acme_notes_n", "CREATE INDEX i ON runs(id)",
            "SELECT * FROM json_each('[1]')",
        )) assertFailsWith<IllegalArgumentException>(bad) { s.query(bad) }
        // A table name inside a string literal or comment is not a table.
        assertEquals(0, s.query("SELECT * FROM acme_notes_n WHERE body = 'FROM tickets' -- FROM runs").size)
    }

    @Test fun `adopt list lets an official module name a core table, others stay refused`() {
        storage(adopt = listOf("tickets")).query("SELECT 1 FROM tickets LIMIT 1")
        assertFailsWith<IllegalArgumentException> { storage(adopt = listOf("tickets")).query("SELECT 1 FROM runs") }
        assertFailsWith<IllegalArgumentException> { storage().query("SELECT 1 FROM tickets") }
    }

    @Test fun `migrate is idempotent and records the version`() {
        val s = storage()
        val ddl = listOf("CREATE TABLE acme_notes_n (id INTEGER)", "INSERT INTO acme_notes_n VALUES (1)")
        s.migrate(1, ddl)
        s.migrate(1, ddl) // would fail on the second CREATE if it ran again
        assertEquals(1, s.query("SELECT COUNT(*) AS c FROM acme_notes_n").single()["c"].let { (it as Number).toInt() })
        s.migrate(2, listOf("ALTER TABLE acme_notes_n ADD COLUMN b TEXT"))
        s.migrate(1, listOf("ALTER TABLE acme_notes_n ADD COLUMN b TEXT")) // older: no-op
        val v = (handle.driver as Any).let { storage().let { _ -> LocalPluginStorage.VERSIONS } }.let { _ -> versionOf() }
        assertEquals(2L, v)
    }

    @Test fun `a failing migrate rolls back and records nothing`() {
        val s = storage()
        assertFailsWith<Exception> { s.migrate(1, listOf("CREATE TABLE acme_notes_a (id INTEGER)", "CREATE TABLE acme_notes_a (id INTEGER)")) }
        s.migrate(1, listOf("CREATE TABLE acme_notes_a (id INTEGER)")) // succeeds: the failed run left no table and no version
    }

    @Test fun `transaction rolls back on exception and commits otherwise`() {
        val s = storage()
        s.migrate(1, listOf("CREATE TABLE acme_notes_n (id INTEGER)"))
        assertFailsWith<IllegalStateException> {
            s.transaction { s.execute("INSERT INTO acme_notes_n VALUES (1)"); error("boom") }
        }
        assertEquals(0, s.query("SELECT * FROM acme_notes_n").size)
        s.transaction { s.execute("INSERT INTO acme_notes_n VALUES (2)") }
        assertEquals(1, s.query("SELECT * FROM acme_notes_n").size)
    }

    @Test fun `a community module needs the storage grant, the default refuses`() {
        val denied = storage(may = { false })
        assertEquals("missing grant 'storage' in modules.json", assertFailsWith<IllegalStateException> { denied.query("SELECT 1") }.message)
        assertFailsWith<IllegalStateException> { NoPluginStorage.query("SELECT 1") }
        assertNotNull(LocalPluginStorage.tablesRefused("SELECT * FROM x", "p_", emptySet()))
        assertNull(LocalPluginStorage.tablesRefused("SELECT * FROM p_x", "p_", emptySet()))
    }
}
