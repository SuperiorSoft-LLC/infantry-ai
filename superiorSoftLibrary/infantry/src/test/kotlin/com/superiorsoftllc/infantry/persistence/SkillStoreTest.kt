package com.superiorsoftllc.infantry.persistence

import com.superiorsoftllc.infantry.protocol.SkillSave
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** P10-B: the skills library's rules, over a real SQLite file. */
class SkillStoreTest {
    private fun withStore(block: (SkillStore) -> Unit) {
        val file = Files.createTempFile("infantry-skills-", ".db").toFile().also { it.delete() }
        var tick = 100L
        var n = 0
        SqlInfantryStore.open(file.absolutePath).use { block(SkillStore(it.skills, { tick++ }, { "id-${++n}" })) }
        file.delete()
    }

    private fun refused(store: SkillStore, save: SkillSave, fragment: String) {
        val e = assertFailsWith<IllegalArgumentException>(save.name.take(20)) { store.save(save) }
        assertTrue(e.message!!.contains(fragment), "refusal '${e.message}' must mention '$fragment'")
        assertTrue(store.list(null).none { it.name == save.name.trim() }, "a refused save writes nothing")
    }

    @Test
    fun saveCreatesThenSameNameUpdatesInPlace() = withStore { s ->
        val first = s.save(SkillSave(name = "  Review  ", description = "  Use when reviewing  ", body = "body 1", tags = listOf("Code", "code ", "qa")))
        assertEquals("id-1", first.id)
        assertEquals("Review", first.name)
        assertEquals("Use when reviewing", first.description)
        assertEquals(listOf("code", "qa"), first.tags)
        val second = s.save(SkillSave(name = "Review", description = "new desc", body = "body 2", tags = listOf("x")))
        assertEquals(first.id, second.id)
        assertEquals(first.createdAt, second.createdAt)
        assertTrue(second.updatedAt > first.updatedAt)
        assertEquals("body 2", s.get(first.id)!!.body)
        assertEquals("new desc", s.get(first.id)!!.description)
        assertEquals(1, s.list(null).size)
    }

    @Test
    fun listFiltersByTagAndOrdersByName() = withStore { s ->
        s.save(SkillSave(name = "b", body = "x", tags = listOf("alpha")))
        s.save(SkillSave(name = "a", body = "x", tags = listOf("alpha", "beta")))
        s.save(SkillSave(name = "c", body = "x"))
        assertEquals(listOf("a", "b", "c"), s.list(null).map { it.name })
        assertEquals(listOf("a", "b", "c"), s.list("  ").map { it.name })
        assertEquals(listOf("a", "b"), s.list("ALPHA").map { it.name })
        assertEquals(listOf("a"), s.list("beta").map { it.name })
        assertTrue(s.list("none").isEmpty())
    }

    @Test
    fun deleteRemovesAndReportsUnknown() = withStore { s ->
        val p = s.save(SkillSave(name = "a", body = "x"))
        assertTrue(s.delete(p.id))
        assertNull(s.get(p.id))
        assertFalse(s.delete(p.id))
    }

    @Test
    fun limitsAreEnforcedAtTheBoundary() = withStore { s ->
        // Exactly at every limit is accepted.
        val atLimit = s.save(
            SkillSave(
                name = "n".repeat(120),
                description = "d".repeat(500),
                body = "b".repeat(64 * 1024),
                tags = (1..16).map { "t".repeat(30) + it.toString(16).padStart(2, '0') },
            ),
        )
        assertEquals(16, atLimit.tags.size)
        assertEquals(500, atLimit.description.length)
        refused(s, SkillSave(name = "n".repeat(121), body = "b"), "name")
        refused(s, SkillSave(name = "  ", body = "b"), "name")
        refused(s, SkillSave(name = "desc", description = "d".repeat(501), body = "b"), "description")
        refused(s, SkillSave(name = "big", body = "b".repeat(64 * 1024 + 1)), "body")
        refused(s, SkillSave(name = "multibyte", body = "é".repeat(32 * 1024 + 1)), "body")
        refused(s, SkillSave(name = "empty", body = "  "), "body")
        refused(s, SkillSave(name = "tag", body = "b", tags = listOf("t".repeat(33))), "tag")
        refused(s, SkillSave(name = "tags", body = "b", tags = (1..17).map { "t$it" }), "tags")
    }
}
