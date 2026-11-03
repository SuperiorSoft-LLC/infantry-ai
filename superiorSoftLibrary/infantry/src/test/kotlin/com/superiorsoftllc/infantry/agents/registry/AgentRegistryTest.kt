// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.agents.registry

import com.superiorsoftllc.infantry.plugin.api.AgentDefinition
import com.superiorsoftllc.infantry.plugin.api.AgentDefinitions
import com.superiorsoftllc.infantry.plugin.api.AgentSchedule
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AGENT-REGISTRY-1 (docs/AGENT-REGISTRY.md section 3): the registry service, its override layer and the manifest block. */
class AgentRegistryTest {
    private val roles = listOf(AgentDefinition("architect", "Architect", role = "architect"), AgentDefinition("pm", "PM", role = "pm"))
    private val personas = mapOf("architect" to "You shape systems.", "pm" to "You run the board.")

    private fun registry(file: File? = null, log: MutableList<String> = ArrayList(), profile: (String) -> AgentDefinition? = { null }) =
        AgentRegistry({ roles }, { personas[it] }, file?.let { FileAgentOverrides(it) { line -> log += line } } ?: MemoryAgentOverrides(), profile) { log += it }

    @Test fun `roles come first in catalog order, then module agents by id`() {
        val r = registry()
        r.setModuleAgents("Zeta_Mod", listOf(AgentDefinition("zeta-b"), AgentDefinition("zeta-a", instructions = "own")))
        r.setModuleAgents("Acme_News", listOf(AgentDefinition("acme-news", "News")))
        assertEquals(listOf("architect", "pm", "acme-news", "zeta-a", "zeta-b"), r.list().map { it.definition.id })
        val news = r.get("acme-news")!!
        assertEquals("Acme_News" to "manifest", news.contributor to news.source)
        assertEquals("zeta-b", r.get("zeta-b")!!.definition.name, "a blank name shows the id")
        assertEquals("roles" to "roles", r.get("pm")!!.let { it.contributor to it.source })
    }

    @Test fun `a role agent's instructions are its role's persona, read live`() {
        val r = registry()
        assertEquals("You shape systems.", r.instructionsOf("architect"))
        r.setModuleAgents("Acme_X", listOf(AgentDefinition("acme-x", role = "pm"), AgentDefinition("acme-y", instructions = "mine", role = "pm")))
        assertEquals("You run the board.", r.instructionsOf("acme-x"), "a role reference with no own instructions")
        assertEquals("mine", r.instructionsOf("acme-y"), "own instructions win over the role")
        assertNull(r.instructionsOf("nobody"))
    }

    @Test fun `the first holder of an id keeps it`() {
        val log = ArrayList<String>()
        val r = registry(log = log)
        r.setModuleAgents("Evil_Mod", listOf(AgentDefinition("architect", instructions = "hijack")))
        assertEquals("roles", r.get("architect")!!.contributor)
        assertEquals("You shape systems.", r.instructionsOf("architect"))
        assertTrue(log.any { "shadowed" in it }, log.toString())
    }

    @Test fun `code may contribute only ids its manifest declares, and the handle withdraws it`() {
        val r = registry()
        r.setModuleAgents("Acme_News", listOf(AgentDefinition("acme-news", instructions = "base")))
        assertFailsWith<IllegalStateException> { r.contribute("Acme_News", true, AgentDefinition("acme-other")) }
        assertFailsWith<IllegalStateException> { r.contribute("Other_Mod", true, AgentDefinition("acme-news")) }
        val handle = r.contribute("Acme_News", true, AgentDefinition("acme-news", instructions = "base + headlines"))
        assertEquals("service" to "base + headlines", r.get("acme-news")!!.let { it.source to it.effectiveInstructions })
        handle.close()
        assertEquals("manifest" to "base", r.get("acme-news")!!.let { it.source to it.effectiveInstructions })
        r.removeModule("Acme_News")
        assertNull(r.get("acme-news"))
    }

    @Test fun `disable refuses new sessions of that agent only, and survives a restart`() {
        val file = File(Files.createTempDirectory("agent-registry").toFile(), "agents/overrides.json")
        val r = registry(file)
        assertNull(r.spawnRefusal("architect"), "every agent is enabled with no override")
        assertNull(r.spawnRefusal(null))
        assertNull(r.spawnRefusal("not-an-agent"), "an id the registry does not hold is not its to refuse")
        assertFalse(r.setEnabled("architect", false)!!.enabled)
        assertNotNull(r.spawnRefusal("architect"))
        assertNull(r.spawnRefusal("pm"))
        assertNull(r.setEnabled("nobody", false))

        val again = registry(file)
        assertFalse(again.get("architect")!!.enabled, "the override is persisted")
        assertTrue(again.setEnabled("architect", true)!!.enabled)
        assertNull(registry(file).spawnRefusal("architect"))
    }

    @Test fun `an unreadable override file is ignored, not fatal`() {
        val file = File(Files.createTempDirectory("agent-registry-bad").toFile(), "overrides.json").apply { writeText("{not json") }
        val log = ArrayList<String>()
        assertTrue(registry(file, log).list().all { it.enabled })
        assertTrue(log.any { "unreadable" in it })
    }

    @Test fun `a profile is a behaviour preset the agent's own fields override`() {
        val preset = AgentDefinition("p", instructions = "preset says", harness = "claude", model = "opus", contextLimit = 200_000, tools = listOf("t1"))
        val r = registry(profile = { if (it == "agent-1234abcd") preset else null })
        r.setModuleAgents("Acme_X", listOf(
            AgentDefinition("acme-a", profile = "agent-1234abcd"),
            AgentDefinition("acme-b", profile = "agent-1234abcd", instructions = "mine", model = "haiku", tools = listOf("t2")),
            AgentDefinition("acme-c", profile = "missing", instructions = "alone"),
        ))
        val a = r.get("acme-a")!!.definition
        assertEquals(listOf("preset says", "claude", "opus", 200_000L, listOf("t1")), listOf(a.instructions, a.harness, a.model, a.contextLimit, a.tools))
        assertEquals("agent-1234abcd", a.profile, "the reference stays visible")
        val b = r.get("acme-b")!!.definition
        assertEquals(listOf("mine", "claude", "haiku", listOf("t2")), listOf(b.instructions, b.harness, b.model, b.tools))
        assertEquals("alone", r.instructionsOf("acme-c"), "an unknown profile adds nothing")
        assertNotNull(AgentDefinitions.refusal(AgentDefinition("acme-d", profile = "Bad Profile"), "Acme_X", community = true))
    }

    @Test fun `module json agents parse and validate`() {
        val m = Json { ignoreUnknownKeys = true }.decodeFromString(ModuleManifest.serializer(), """
            {"name":"Acme_News","version":"1.0.0","agents":[{"id":"acme-news","name":"News","instructions":"x","tools":["acme_news_fetch"],
             "schedule":{"everyMinutes":30,"prompt":"brief"},"ui":{"color":"#0ea5e9","icon":"newspaper"},"futureKey":1}]}
        """.trimIndent())
        val a = m.agents.single()
        assertEquals(AgentSchedule(30, "brief"), a.schedule)
        assertEquals(listOf("acme_news_fetch"), a.tools)
        assertNull(AgentDefinitions.refusal(a, "Acme_News", community = true))
        assertEquals(emptyList(), Json.decodeFromString(ModuleManifest.serializer(), """{"name":"X_Y","version":"1"}""").agents, "absent = none")

        fun refused(d: AgentDefinition, community: Boolean = true) = AgentDefinitions.refusal(d, "Acme_News", community)
        assertNotNull(refused(AgentDefinition("Bad Id")))
        assertNotNull(refused(AgentDefinition("news")), "a community id outside its vendor prefix")
        assertNull(refused(AgentDefinition("news"), community = false), "official modules name their own ids")
        assertNotNull(refused(AgentDefinition("acme-n", schedule = AgentSchedule(0))))
        assertNotNull(refused(AgentDefinition("acme-n", tools = listOf("a", "a"))))
        assertNotNull(refused(AgentDefinition("acme-n", contextLimit = 0)))
        assertNotNull(refused(AgentDefinition("acme-n", instructions = "x".repeat(AgentDefinitions.MAX_INSTRUCTIONS + 1))))
    }

    @Test fun `the example news agent's module json is a valid community contribution`() {
        val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "plugins/examples/Acme_NewsAgent/module.json") }.first { it.isFile }
        val m = Json { ignoreUnknownKeys = true }.decodeFromString(ModuleManifest.serializer(), file.readText())
        assertEquals(listOf("acme-news"), m.agents.map { it.id })
        m.agents.forEach { assertNull(AgentDefinitions.refusal(it, m.name, community = true)) }
    }
}
