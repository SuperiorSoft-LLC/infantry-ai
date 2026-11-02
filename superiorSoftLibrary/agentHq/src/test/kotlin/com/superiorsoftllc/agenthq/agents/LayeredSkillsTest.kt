package com.superiorsoftllc.agenthq.agents

import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LayeredSkillsTest {
    private val dir: File = Files.createTempDirectory("agenthq-layered").toFile()
    private val db = SqlAgentHqStore.open(File(dir, "a.db").absolutePath)
    private val skills = LayeredSkillStore(db)

    @AfterTest
    fun cleanUp() {
        runCatching { db.close() }
        dir.deleteRecursively()
    }

    private fun s(n: String, b: String = "body of $n") = LayerSkill(n, "desc $n", b)
    private val global = SkillScope(SkillLayer.GLOBAL)
    private fun project(p: String) = SkillScope(SkillLayer.PROJECT, p)
    private fun role(p: String, r: String) = SkillScope(SkillLayer.ROLE, p, r)

    @Test
    fun eachLayerReachesTheRightAgents() {
        skills.save(global, s("g"))
        skills.save(project("p1"), s("pj"))
        skills.save(role("p1", "backend"), s("rl"))
        assertEquals(listOf("g", "pj", "rl"), skills.resolve("p1", "backend").map { it.skill.name })
        // another role of the same project: no role skill
        assertEquals(listOf("g", "pj"), skills.resolve("p1", "frontend").map { it.skill.name })
        // no role: global + project
        assertEquals(listOf("g", "pj"), skills.resolve("p1", null).map { it.skill.name })
    }

    @Test
    fun otherProjectsAreUnaffected() {
        skills.save(global, s("g"))
        skills.save(project("p1"), s("pj"))
        skills.save(role("p1", "backend"), s("rl"))
        assertEquals(listOf("g"), skills.resolve("p2", "backend").map { it.skill.name })
        assertEquals(listOf("g"), skills.resolve(null, null).map { it.skill.name })
    }

    @Test
    fun narrowerLayerOverridesWiderOne() {
        skills.save(global, s("x", "global body"))
        skills.save(project("p1"), s("x", "project body"))
        assertEquals(SkillLayer.PROJECT, skills.resolve("p1", "backend").single().layer)
        skills.save(role("p1", "backend"), s("x", "role body"))
        val r = skills.resolve("p1", "backend").single()
        assertEquals(SkillLayer.ROLE, r.layer)
        assertEquals("role body", r.skill.body)
        assertEquals("global body", skills.resolve("p2", "backend").single().skill.body)
    }

    @Test
    fun crud() {
        assertNull(skills.get(global, "a"))
        skills.save(global, s("a", "one"))
        skills.save(global, s("a", "two"))
        assertEquals("two", assertNotNull(skills.get(global, "a")).body)
        assertEquals(1, skills.list(global).size)
        assertTrue(skills.delete(global, "a"))
        assertFalse(skills.delete(global, "a"))
        assertFailsWith<IllegalArgumentException> { skills.save(global, LayerSkill("", "", "b")) }
        assertFailsWith<IllegalArgumentException> { skills.save(global, LayerSkill("n", "", " ")) }
        assertFailsWith<IllegalArgumentException> { SkillScope(SkillLayer.ROLE, "p", null) }
    }

    @Test
    fun briefNamesTheLayerAndShortensLongBodies() {
        skills.save(global, s("short"))
        skills.save(project("p1"), s("long", "x".repeat(3000)))
        val text = assertNotNull(renderLayeredSkills(skills.resolve("p1", null)))
        assertTrue("### Skill: short [global]" in text)
        assertTrue("- long [project]" in text && "x".repeat(3000) !in text)
        assertNull(renderLayeredSkills(emptyList()))
    }
}
