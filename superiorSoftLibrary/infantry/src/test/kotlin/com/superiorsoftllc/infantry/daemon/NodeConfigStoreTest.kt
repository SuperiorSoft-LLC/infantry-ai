package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.db.InfantryDatabaseFactory
import com.superiorsoftllc.infantry.plugin.api.ConfigSetBy
import com.superiorsoftllc.infantry.plugin.api.NodeConfigEntry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** P1 step 2 (docs/BLOCKS-LAYOUT-DESIGN.md 6s B): node_config is seeded, never overwritten by a seed, and survives a reopen. */
class NodeConfigStoreTest {
    private val path = Files.createTempDirectory("node-config").resolve("infantry.db").toString()

    @Test
    fun `a seed inserts only missing keys, a put overwrites, a null put removes, and rows survive a reopen`() {
        InfantryDatabaseFactory.open(path).use { h ->
            val store = NodeConfigStore(h.database) { 7L }
            store.seed(listOf(NodeConfigEntry("infantry.shell.board", "showEmpty", JsonPrimitive(true)), NodeConfigEntry("n", "nothing", null)))
            store.put(NodeConfigEntry("infantry.shell.board", "showEmpty", JsonPrimitive(false), ConfigSetBy.USER))
            // A later seed (the next start) does not undo the user's value.
            store.seed(listOf(NodeConfigEntry("infantry.shell.board", "showEmpty", JsonPrimitive(true)), NodeConfigEntry("infantry.shell.board", "columns", JsonArray(listOf(JsonPrimitive("todo"))))))
            val rows = store.all().associateBy { it.key }
            assertEquals(setOf("columns", "showEmpty"), rows.keys)
            assertEquals(JsonPrimitive(false), rows.getValue("showEmpty").value)
            assertEquals(ConfigSetBy.USER, rows.getValue("showEmpty").setBy)
            assertEquals(ConfigSetBy.SEED, rows.getValue("columns").setBy)
            assertEquals(7L, rows.getValue("columns").updatedAt)
        }
        InfantryDatabaseFactory.open(path).use { h ->
            val store = NodeConfigStore(h.database)
            assertEquals(JsonPrimitive(false), store.all().first { it.key == "showEmpty" }.value)
            store.put(NodeConfigEntry("infantry.shell.board", "showEmpty", null))
            assertNull(store.all().firstOrNull { it.key == "showEmpty" })
        }
    }
}
