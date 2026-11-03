package com.superiorsoftllc.infantry.persistence

import com.superiorsoftllc.infantry.protocol.PromptSave
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P10-A: the prompt library's rules, over a real SQLite file. */
class PromptStoreTest {
    private fun withStore(block: (PromptStore) -> Unit) {
        val file = Files.createTempFile("infantry-prompts-", ".db").toFile().also { it.delete() }
        var tick = 100L
        var n = 0
        SqlInfantryStore.open(file.absolutePath).use { block(PromptStore(it.prompts, { tick++ }, { "id-${++n}" })) }
        file.delete()
    }

    private fun refused(store: PromptStore, save: PromptSave, fragment: String) {
        val e = assertFailsWith<IllegalArgumentException>(save.name.take(20)) { store.save(save) }
        assertTrue(e.message!!.contains(fragment), "refusal '${e.message}' must mention '$fragment'")
        assertTrue(store.list(null).none { it.name == save.name.trim() }, "a refused save writes nothing")
    }

    @Test
    fun saveCreatesThenSameNameUpdatesInPlace() = withStore { s ->
        val first = s.save(PromptSave("  Review  ", "body 1", listOf("Code", "code ", "qa")))
        assertEquals("id-1", first.id)
        assertEquals("Review", first.name)
        assertEquals(listOf("code", "qa"), first.tags)
        val second = s.save(PromptSave("Review", "body 2", listOf("x")))
        assertEquals(first.id, second.id)
        assertEquals(first.createdAt, second.createdAt)
        assertTrue(second.updatedAt > first.updatedAt)
        assertEquals("body 2", s.get(first.id)!!.body)
        assertEquals(1, s.list(null).size)
    }

    @Test
    fun listFiltersByTagAndOrdersByName() = withStore { s ->
        s.save(PromptSave("b", "x", listOf("alpha")))
        s.save(PromptSave("a", "x", listOf("alpha", "beta")))
        s.save(PromptSave("c", "x"))
        assertEquals(listOf("a", "b", "c"), s.list(null).map { it.name })
        assertEquals(listOf("a", "b", "c"), s.list("  ").map { it.name })
        assertEquals(listOf("a", "b"), s.list("ALPHA").map { it.name })
        assertEquals(listOf("a"), s.list("beta").map { it.name })
        assertTrue(s.list("none").isEmpty())
    }

    @Test
    fun deleteRemovesAndReportsUnknown() = withStore { s ->
        val p = s.save(PromptSave("a", "x"))
        assertTrue(s.delete(p.id))
        assertNull(s.get(p.id))
        assertFalse(s.delete(p.id))
    }

    @Test
    fun limitsAreEnforcedAtTheBoundary() = withStore { s ->
        // Exactly at every limit is accepted.
        val atLimit = s.save(PromptSave("n".repeat(120), "b".repeat(64 * 1024), (1..16).map { "t".repeat(30) + it.toString(16).padStart(2, '0') }))
        assertEquals(16, atLimit.tags.size)
        refused(s, PromptSave("n".repeat(121), "b"), "name")
        refused(s, PromptSave("  ", "b"), "name")
        refused(s, PromptSave("big", "b".repeat(64 * 1024 + 1)), "body")
        refused(s, PromptSave("multibyte", "é".repeat(32 * 1024 + 1)), "body")
        refused(s, PromptSave("empty", "  "), "body")
        refused(s, PromptSave("tag", "b", listOf("t".repeat(33))), "tag")
        refused(s, PromptSave("tags", "b", (1..17).map { "t$it" }), "tags")
    }
}
