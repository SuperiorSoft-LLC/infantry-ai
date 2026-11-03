package com.superiorsoftllc.infantry.persistence

import com.superiorsoftllc.infantry.protocol.TraitCheck
import com.superiorsoftllc.infantry.protocol.TraitSave
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TRAITS-T1 (docs/INFANTRY-ROLE-TRAITS.md section 2): the trait library's rules, over a real SQLite file. */
class TraitStoreTest {
    private val catalog = setOf("pm", "qa", "architect")

    private fun withStore(block: (TraitStore) -> Unit) {
        val file = Files.createTempFile("infantry-traits-", ".db").toFile().also { it.delete() }
        var tick = 100L
        SqlInfantryStore.open(file.absolutePath).use { block(TraitStore(it.traits, { id -> id in catalog }, { tick++ })) }
        file.delete()
    }

    private val report = TraitCheck(kind = "report", fields = listOf("commit", "tests", "wall time"))

    private fun refused(store: TraitStore, save: TraitSave, fragment: String) {
        val before = store.list()
        val e = assertFailsWith<IllegalArgumentException>("must refuse: ${save.toString().take(80)}") { store.save(save) }
        assertTrue(e.message!!.contains(fragment), "refusal '${e.message}' must mention '$fragment'")
        assertEquals(before, store.list(), "a refused save writes nothing")
    }

    @Test
    fun createIsVersionOneAndCheckDefaultsApply() = withStore { s ->
        val t = s.save(TraitSave(id = "report-back", name = "  REPORT BACK  ", body = "mail the pm", check = report))
        assertEquals("report-back", t.id)
        assertEquals("REPORT BACK", t.name)
        assertEquals(1, t.version)
        assertEquals(t.createdAt, t.updatedAt)
        assertEquals("pm", t.check!!.reportTo)
        assertTrue(t.check!!.reprompt)
        assertEquals(listOf("commit", "tests", "wall time"), t.check!!.fields)
        assertEquals(t, s.get("report-back"))
    }

    @Test
    fun versionMovesOnlyWhenNameBodyOrCheckChanges() = withStore { s ->
        val v1 = s.save(TraitSave("t", "T", "body", report))
        val same = s.save(TraitSave("t", " T ", "body", report))
        assertEquals(v1, same, "an identical save changes nothing, not even updatedAt")
        assertEquals(v1, s.get("t"))

        val v2 = s.save(TraitSave("t", "T", "body 2", report))
        assertEquals(2, v2.version)
        assertEquals(v1.createdAt, v2.createdAt)
        assertTrue(v2.updatedAt > v1.updatedAt)

        assertEquals(3, s.save(TraitSave("t", "T renamed", "body 2", report)).version)
        assertEquals(4, s.save(TraitSave("t", "T renamed", "body 2", report.copy(reprompt = false))).version)
        assertEquals(5, s.save(TraitSave("t", "T renamed", "body 2", null)).version, "dropping the check is a change")
        assertEquals(5, s.save(TraitSave("t", "T renamed", "body 2", null)).version)
        assertEquals(1, s.list().size)
    }

    @Test
    fun idMustBeASlug() = withStore { s ->
        for (bad in listOf("", "Report", "1abc", "a_b", "a b", "x".repeat(33))) refused(s, TraitSave(bad, "n", "b"), "id")
        assertEquals("x".repeat(32), s.save(TraitSave("x".repeat(32), "n", "b")).id)
    }

    @Test
    fun nameAndBodyLimits() = withStore { s ->
        refused(s, TraitSave("a", "  ", "b"), "name")
        refused(s, TraitSave("a", "n".repeat(81), "b"), "name")
        s.save(TraitSave("a", "n".repeat(80), "b"))
        refused(s, TraitSave("b", "n", "  "), "body")
        // 1025 two-byte chars = 2050 bytes: the cap is on UTF-8 BYTES, not chars.
        refused(s, TraitSave("b", "n", "é".repeat(1025)), "2048")
        refused(s, TraitSave("b", "n", "x".repeat(2049)), "2048")
        assertEquals(2048, s.save(TraitSave("b", "n", "x".repeat(2048))).body.length)
    }

    @Test
    fun checkIsValidated() = withStore { s ->
        refused(s, TraitSave("a", "n", "b", report.copy(kind = "lint")), "kind")
        refused(s, TraitSave("a", "n", "b", report.copy(reportTo = "nobody")), "reportTo")
        refused(s, TraitSave("a", "n", "b", report.copy(reportTo = "Bad Id")), "reportTo")
        refused(s, TraitSave("a", "n", "b", report.copy(fields = emptyList())), "fields")
        refused(s, TraitSave("a", "n", "b", report.copy(fields = (1..13).map { "f$it" })), "fields")
        refused(s, TraitSave("a", "n", "b", report.copy(fields = listOf("commit", "Commit"))), "duplicate")
        for (bad in listOf("1x", "a:b", "", "x".repeat(33))) refused(s, TraitSave("a", "n", "b", report.copy(fields = listOf(bad))), "field")
        val ok = s.save(TraitSave("a", "n", "b", report.copy(reportTo = "qa", fields = (1..12).map { "f-$it" })))
        assertEquals("qa", ok.check!!.reportTo)
        assertEquals(12, ok.check!!.fields.size)
    }

    @Test
    fun listOrdersByIdAndDeleteReportsUnknown() = withStore { s ->
        s.save(TraitSave("b", "B", "x"))
        s.save(TraitSave("a", "A", "x"))
        assertEquals(listOf("a", "b"), s.list().map { it.id })
        assertTrue(s.delete("a"))
        assertNull(s.get("a"))
        assertFalse(s.delete("a"))
        assertEquals(listOf("b"), s.list().map { it.id })
        assertEquals(1, s.save(TraitSave("a", "A", "x")).version, "a re-created id starts again at version 1")
    }
}
