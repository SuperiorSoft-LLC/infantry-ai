package com.superiorsoftllc.infantry.mvp

import com.superiorsoftllc.infantry.db.InfantryDatabaseFactory
import com.superiorsoftllc.infantry.protocol.ProjectSave
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** PROJECT-DATA-1: the generic project data store (set / unset / validation / restart) and the typed keys it must not disturb. */
class ProjectDataTest {

    private fun store() = ProjectStore(idFactory = { "p1" })

    @Test
    fun setReadsBackAndUnsetRemoves() {
        val store = store()
        val id = store.save(ProjectSave(name = "P")).id
        assertEquals(emptyMap(), store.get(id).data)

        store.setData(id, mapOf("build.root" to "C:/x", "app.json" to """{"a":1}"""))
        assertEquals("C:/x", store.dataOf(id, "build.root"))
        assertEquals("""{"a":1}""", store.dataOf(id, "app.json"))

        store.setData(id, emptyMap(), listOf("build.root", "never.set"))
        assertNull(store.dataOf(id, "build.root"))
        assertEquals(setOf("app.json"), store.get(id).data.keys)
    }

    @Test
    fun invalidKeysAndValuesAreRefusedAndWriteNothing() {
        val store = store()
        val id = store.save(ProjectSave(name = "P")).id
        for (bad in listOf("", " ", "has space", "-lead", "a/b", "x".repeat(81))) {
            assertFailsWith<IllegalArgumentException>("key '$bad'") { store.setData(id, mapOf(bad to "v")) }
        }
        assertFailsWith<IllegalArgumentException> { store.setData(id, mapOf("ok.key" to "  ")) }
        assertFailsWith<IllegalArgumentException> { store.setData(id, mapOf("ok.key" to "v".repeat(ProjectStore.DATA_VALUE_MAX + 1))) }
        // a refusal part-way through leaves the earlier entry unwritten too
        assertFailsWith<IllegalArgumentException> { store.setData(id, linkedMapOf("good.key" to "v", "bad key" to "v")) }
        assertEquals(emptyMap(), store.get(id).data)
        store.setData(id, mapOf("x".repeat(80) to "v".repeat(ProjectStore.DATA_VALUE_MAX)))
    }

    @Test
    fun entryCountIsLimited() {
        val store = store()
        val id = store.save(ProjectSave(name = "P")).id
        store.setData(id, (1..ProjectStore.DATA_MAX_ENTRIES).associate { "k$it" to "v" })
        assertFailsWith<IllegalArgumentException> { store.setData(id, mapOf("one.more" to "v")) }
    }

    @Test
    fun typedKeysAreUnchangedByData() {
        val store = store()
        val id = store.save(ProjectSave(name = "P")).id
        store.setLaneCaps(id, mapOf("backend" to 3))
        store.setAutoRemoveDoneWorkers(id, true)
        store.setExperiments(id, mapOf("x" to "on"))
        store.setData(id, mapOf("build.root" to "r"))
        val p = store.get(id)
        assertEquals(mapOf("backend" to 3), p.laneCaps)
        assertEquals(true, p.autoRemoveDoneWorkers)
        assertEquals(mapOf("x" to "on"), p.experiments)
        assertEquals(mapOf("build.root" to "r"), p.data)
    }

    @Test
    fun dataSurvivesARestart() {
        val state = Files.createTempDirectory("infantry-pd1-").toFile()
        try {
            val dbPath = File(state, "infantry.db").absolutePath
            val first = InfantryDatabaseFactory.open(dbPath)
            val id = ProjectStore(persistence = SqlProjectPersistence(first.database)).let {
                val pid = it.save(ProjectSave(name = "P")).id
                it.setData(pid, mapOf("build.backupRoot" to "b"))
                pid
            }
            first.close()
            val second = InfantryDatabaseFactory.open(dbPath)
            try {
                assertEquals("b", ProjectStore(persistence = SqlProjectPersistence(second.database)).dataOf(id, "build.backupRoot"))
            } finally {
                second.close()
            }
        } finally {
            runCatching { state.deleteRecursively() }
        }
    }
}
