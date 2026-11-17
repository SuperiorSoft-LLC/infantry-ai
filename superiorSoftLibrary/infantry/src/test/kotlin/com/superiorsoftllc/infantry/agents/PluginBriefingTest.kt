// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.agents

import com.superiorsoftllc.infantry.daemon.api.knowledge.AgentBriefing
import com.superiorsoftllc.infantry.daemon.api.knowledge.BriefingBudget
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeBriefing
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeEntry
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeSections
import com.superiorsoftllc.infantry.daemon.api.plugins.LoadedModule
import com.superiorsoftllc.infantry.daemon.plugins.DaemonPluginHost
import com.superiorsoftllc.infantry.mailbox.FakeAgentProvider
import com.superiorsoftllc.infantry.mailbox.ScriptedRunner
import com.superiorsoftllc.infantry.mailbox.tempSessionStore
import com.superiorsoftllc.infantry.persistence.AgentRepository
import com.superiorsoftllc.infantry.plugin.api.KnowledgeDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.settings.LimitRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PK-1 (plan CAL-PLAN-2 B3/B4): the PLUGIN TOOLS section, its scope and budget, the first-turn hook, and `modules.changed` on upgrade. */
class PluginBriefingTest {
    private fun entry(id: String, summary: String = "$id summary", module: String = "Acme_Cal", roles: List<String> = emptyList(),
                      skipRoles: List<String> = emptyList(), projects: List<String> = emptyList()) =
        KnowledgeEntry(id, summary, roles = roles, skipRoles = skipRoles, projects = projects, module = module)

    // --- render: scope ---

    @Test
    fun anEntryForEveryRoleReachesAnyRoleAndNoRole() {
        val all = listOf(entry("cal", roles = listOf("*")), entry("open"))
        for (role in listOf("pm", "assistant", null)) {
            val s = KnowledgeBriefing.render(all, role, "p1")!!
            assertTrue(s.startsWith(KnowledgeBriefing.HEADER), s)
            assertTrue("- cal summary (plugin:Acme_Cal/cal)" in s, s)
            assertTrue("- open summary (plugin:Acme_Cal/open)" in s, s)
        }
    }

    @Test
    fun rolesSkipRolesAndProjectsScopeAnEntry() {
        val es = listOf(
            entry("asst", roles = listOf("assistant")),
            entry("notqa", roles = listOf("*"), skipRoles = listOf("QA")),
            entry("p1only", projects = listOf("p1")),
        )
        val pm = KnowledgeBriefing.render(es, "pm", "p2")!!
        assertTrue("asst" !in pm && "notqa summary" in pm && "p1only" !in pm, pm)
        val qa = KnowledgeBriefing.render(es, "qa", "p1")!!
        assertTrue("notqa" !in qa && "p1only summary" in qa, qa)
        val assistant = KnowledgeBriefing.render(es, "assistant", null)!!
        assertTrue("asst summary" in assistant && "p1only" !in assistant, assistant)
        assertNull(KnowledgeBriefing.render(listOf(entry("asst", roles = listOf("assistant"))), "pm", null))
        assertNull(KnowledgeBriefing.render(emptyList(), "pm", null))
    }

    // --- render: budget ---

    @Test
    fun aLongSummaryIsCutToTheSummaryBudget() {
        val s = KnowledgeBriefing.render(listOf(entry("long", summary = "x".repeat(500))), "pm", null, BriefingBudget(summaryMaxChars = 50))!!
        val line = s.lines()[1]
        assertTrue(line.startsWith("- " + "x".repeat(47) + "... (plugin:"), line)
    }

    @Test
    fun overBudgetRoleSpecificEntriesComeFirstAndTheRestAreNamedInOneMoreLine() {
        val es = (1..20).map { entry("general$it", summary = "general tool number $it does a useful thing for everyone") } +
            entry("mine", summary = "the pm tool", roles = listOf("pm"))
        val s = KnowledgeBriefing.render(es, "pm", null, BriefingBudget(maxChars = 400))!!
        assertTrue(s.length <= 400, "${s.length}: $s")
        val lines = s.lines()
        assertEquals("- the pm tool (plugin:Acme_Cal/mine)", lines[1])
        assertTrue(lines.last().startsWith("+"), s)
        assertTrue("more:" in lines.last() && "infantry memory search plugin:" in lines.last(), s)
        val shown = lines.count { it.startsWith("- ") }
        assertEquals(es.size - shown, lines.last().substringAfter('+').substringBefore(' ').toInt())
    }

    @Test
    fun aTinyBudgetStillStaysWithinItAndSaysMoreIsThere() {
        val es = (1..5).map { entry("e$it") }
        val s = KnowledgeBriefing.render(es, "pm", null, BriefingBudget(maxChars = 120))!!
        assertTrue(s.length <= 120, "${s.length}: $s")
        val unlimited = KnowledgeBriefing.render(es, "pm", null, BriefingBudget(maxChars = 0, summaryMaxChars = 0))!!
        assertEquals(6, unlimited.lines().size)
    }

    // --- shared skip-marker parser ---

    @Test
    fun theSharedParserMatchesSharedKnowledge() {
        val text = "# T\n\n## A <!-- skip: pm -->\nfor others\n\n## B\nfor all\n"
        assertEquals("# T\n\n## B\nfor all", KnowledgeSections.textFor(text, "pm"))
        assertTrue("## A\nfor others" in KnowledgeSections.textFor(text, "qa"))
        assertEquals(SharedKnowledge.textFor("qa"), KnowledgeSections.textFor(SharedKnowledge.text, "qa"))
    }

    // --- core side: budget enforcement and the first turn ---

    @Test
    fun theCoreHoldsAnOvershootingProviderToTheBudgetAndSurvivesAThrowingOne() {
        val big = object : AgentBriefing {
            override fun section(role: String?, projectId: String?, budget: BriefingBudget) = "y".repeat(5000)
        }
        assertEquals(1500, PluginBriefingSection.of(big, "pm", null, 1500, 200)!!.length)
        val boom = object : AgentBriefing {
            override fun section(role: String?, projectId: String?, budget: BriefingBudget): String? = error("plugin fault")
        }
        assertNull(PluginBriefingSection.of(boom, "pm", null, 1500, 200))
        assertNull(PluginBriefingSection.of(null, "pm", null, 1500, 200))
        assertEquals(1500L, LimitRegistry.KNOWLEDGE_PLUGINS_MAX_CHARS.default)
        assertEquals(200L, LimitRegistry.KNOWLEDGE_PLUGINS_SUMMARY_MAX_CHARS.default)
    }

    @Test
    fun theBudgetFromTheSettingsReachesTheProvider() {
        var seen: BriefingBudget? = null
        val p = object : AgentBriefing {
            override fun section(role: String?, projectId: String?, budget: BriefingBudget): String? { seen = budget; return "s" }
        }
        PluginBriefingSection.of(p, "pm", "p1", 900, 80)
        assertEquals(BriefingBudget(900, 80), seen)
        PluginBriefingSection.of(p, "pm", "p1", 0, 0)
        assertEquals(Int.MAX_VALUE, seen!!.maxChars)
    }

    private class Repo(vararg profiles: AgentProfile) : AgentRepository {
        private val map = profiles.associateBy { it.id!! }.toMutableMap()
        override fun save(profile: AgentProfile, at: Long) { map[profile.id!!] = profile }
        override fun get(id: String) = map[id]
        override fun all() = map.values.toList()
        override fun delete(id: String) = map.remove(id) != null
    }

    private fun profiles(repo: AgentRepository = Repo()) = AgentProfiles(
        repo, SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = false), tempSessionStore(), killGraceMs = 50),
        rolePersona = { "persona of $it" },
    )

    @Test
    fun theFirstTurnCarriesTheSectionAfterTheSharedKnowledgeForTheRoleAndProject() {
        val agents = profiles()
        val asked = mutableListOf<Pair<String?, String?>>()
        agents.pluginBriefing = { role, pid -> asked += role to pid; "PLUGIN TOOLS\n- cal" }
        val text = agents.instanceFor(null, "pm", "p1")!!.instructions!!
        assertEquals(listOf<Pair<String?, String?>>("pm" to "p1"), asked)
        assertTrue(text.startsWith("persona of pm"), text)
        assertTrue(text.endsWith("PLUGIN TOOLS\n- cal"), text)
        val knowledge = SharedKnowledge.textFor("pm")
        if (knowledge.isNotEmpty()) assertTrue(text.indexOf(knowledge) < text.indexOf("PLUGIN TOOLS"))
    }

    @Test
    fun noProviderOrANullAnswerOrAFaultLeavesTheInstructionsAsBefore() {
        val agents = profiles()
        val before = agents.instanceFor(null, "pm")!!.instructions
        assertEquals(SharedKnowledge.withKnowledge("persona of pm", "pm"), before)
        agents.pluginBriefing = { _, _ -> null }
        assertEquals(before, agents.instanceFor(null, "pm", "p1")!!.instructions)
        agents.pluginBriefing = { _, _ -> error("boom") }
        assertEquals(before, agents.instanceFor(null, "pm", "p1")!!.instructions)
    }

    @Test
    fun aSpawnWithNoProjectUsesTheProfilesProject() {
        val repo = Repo(AgentProfile(id = "a1", name = "A", role = "pm", provider = "cc-fake", projectId = "pX", traitIds = emptyList()))
        val agents = profiles(repo)
        var pid: String? = "unset"
        agents.pluginBriefing = { _, p -> pid = p; null }
        agents.instanceFor("a1", null)
        assertEquals("pX", pid)
        agents.instanceFor("a1", null, "pY")
        assertEquals("pY", pid)
    }

    // --- modules.changed fires on a version change ---

    private fun loaded(id: String, version: String) =
        LoadedModule(id, "official", ModuleManifest(name = id, version = version, knowledge = listOf(KnowledgeDeclaration("k", "s"))), null)

    @Test
    fun modulesChangedFiresOnASetChangeAndOnAnUpgradeButNotOnNothing() {
        val v1 = listOf(loaded("A", "1.0.0"), loaded("B", "2.0.0"))
        assertNull(DaemonPluginHost.modulesChangedPayload(v1, listOf(loaded("A", "1.0.0"), loaded("B", "2.0.0"))))
        val upgrade = DaemonPluginHost.modulesChangedPayload(v1, listOf(loaded("A", "1.1.0"), loaded("B", "2.0.0")))
        assertNotNull(upgrade)
        assertTrue("\"modules\":[\"A\",\"B\"]" in upgrade, upgrade)
        assertTrue("{\"id\":\"A\",\"version\":\"1.1.0\"}" in upgrade, upgrade)
        assertNotNull(DaemonPluginHost.modulesChangedPayload(v1, listOf(loaded("A", "1.0.0"))))
        assertNotNull(DaemonPluginHost.modulesChangedPayload(emptyList(), v1))
    }
}
