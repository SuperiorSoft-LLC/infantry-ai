package com.superiorsoftllc.agenthq.agents

import com.superiorsoftllc.agenthq.context.ContextInput
import com.superiorsoftllc.agenthq.context.TicketContextComposer
import com.superiorsoftllc.agenthq.mailbox.FakeAgentProvider
import com.superiorsoftllc.agenthq.mailbox.ScriptedRunner
import com.superiorsoftllc.agenthq.mailbox.tempSessionStore
import com.superiorsoftllc.agenthq.session.InMemorySessionInstances
import com.superiorsoftllc.agenthq.session.RoleInstance
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.TraitSnapshot
import com.superiorsoftllc.agenthq.settings.ProjectSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * TRAITS-T4 + T5, below the daemon: the [SessionManager.startNew] chokepoint (a caller that passes no instance still
 * freezes one through the injected [SessionManager.instanceFor]; a given one wins; an anonymous spawn writes none; the
 * first write wins), the legacy freeze of a pre-29.sqm bound session, and the renderer's text and order.
 */
class RoleInstanceTest {
    private val rb = TraitSnapshot("report-back", 3, "REPORT BACK", "mail the pm")
    private val tdd = TraitSnapshot("tdd", 1, "TDD", "red first")

    private fun manager(instances: InMemorySessionInstances = InMemorySessionInstances()) =
        SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = false), tempSessionStore(), killGraceMs = 50, instances = instances)

    @Test
    fun aCallerThatPassesNoInstanceStillFreezesOneThroughTheInjectedResolver() {
        val instances = InMemorySessionInstances()
        val manager = manager(instances)
        val calls = mutableListOf<Pair<String?, String?>>()
        manager.instanceFor = { agentId, role -> calls += agentId to role; RoleInstance(agentId, role, "persona", listOf(rb)) }

        val s = manager.startNew("", agentId = "a1", role = "backend")

        assertEquals(listOf<Pair<String?, String?>>("a1" to "backend"), calls)
        assertEquals(RoleInstance("a1", "backend", "persona", listOf(rb)), instances.get(s.id))
    }

    @Test
    fun aGivenInstanceWinsOverTheResolverAndIsStoredAsGiven() {
        val instances = InMemorySessionInstances()
        val manager = manager(instances)
        manager.instanceFor = { _, _ -> error("must not resolve when the caller passed an instance") }
        val given = RoleInstance("a1", "backend", "rendered persona", listOf(tdd))

        val s = manager.startNew("", agentId = "a1", role = "backend", instance = given)

        assertEquals(given, instances.get(s.id))
        assertEquals(given, manager.instance(s.id))
    }

    @Test
    fun anAnonymousSpawnWritesNoRowEvenWithAResolver() {
        val instances = InMemorySessionInstances()
        val manager = manager(instances)
        manager.instanceFor = { _, _ -> error("an anonymous spawn resolves nothing") }

        val s = manager.startNew("")

        assertNull(instances.get(s.id))
        assertNull(manager.instance(s.id))
    }

    @Test
    fun theFirstInstanceForASessionWinsAndDeleteRemovesIt() {
        val instances = InMemorySessionInstances()
        val manager = manager(instances)
        val s = manager.startNew("", role = "qa", instance = RoleInstance(null, "qa", null, listOf(rb)))
        instances.insert(s.id, RoleInstance(null, "qa", "overwrite attempt", emptyList()), 99L)
        assertEquals(listOf(rb), manager.instance(s.id)!!.traits)

        manager.delete(s.id)
        assertNull(instances.get(s.id))
    }

    @Test
    fun aBoundSessionWithNoRowIsFrozenOnceOnFirstRead() {
        val instances = InMemorySessionInstances()
        val manager = manager(instances)
        val s = manager.startNew("", agentId = "a1", role = "backend") // no resolver yet: a pre-29.sqm session
        assertNull(instances.get(s.id))
        var persona = "v1"
        manager.instanceFor = { agentId, role -> RoleInstance(agentId, role, persona, emptyList()) }

        assertEquals("v1", manager.instance(s.id)!!.instructions)
        persona = "v2"
        assertEquals("v1", manager.instance(s.id)!!.instructions, "frozen at the first read, never re-resolved")
    }

    // --- T5 renderer ------------------------------------------------------------------------------------------------

    @Test
    fun renderTraitsIsNullWhenEmptyAndOneBlockPerTraitInOrder() {
        assertNull(renderTraits(emptyList()))
        assertEquals(
            "Standing rules bound to your role (they apply to this whole session):\n\n" +
                "## REPORT BACK (report-back v3)\nmail the pm\n\n## TDD (tdd v1)\nred first",
            renderTraits(listOf(rb, tdd)),
        )
    }

    @Test
    fun firstTurnOrderIsPersonaIdentityTraitsTask() {
        val identity = identityLine("backend", "a1")!!
        assertEquals(
            "persona\n\n$identity\n\n${renderTraits(listOf(rb))}\n\ntask",
            firstTurn(RoleInstance("a1", "backend", "persona", listOf(rb)), "backend", "a1", "task"),
        )
    }

    @Test
    fun withNoTraitsTheInstanceFirstTurnIsExactlyTodaysText() {
        for ((persona, role, agent) in listOf(Triple("persona", "backend", "a1"), Triple(null, "qa", null), Triple(null, null, null))) {
            val instance = if (role == null) null else RoleInstance(agent, role, persona, emptyList())
            assertEquals(firstTurn(persona, role, agent, "task"), firstTurn(instance, role, agent, "task"))
        }
        assertEquals("task", firstTurn(null as RoleInstance?, null, null, "task"))
    }

    @Test
    fun theComposerAddsATraitsSourceBetweenIdentityAndTicketOnlyWhenThereAreTraits() {
        val composer = TicketContextComposer(object : ProjectSettings {
            override fun all() = kotlinx.serialization.json.JsonObject(emptyMap())
            override fun set(key: String, value: kotlinx.serialization.json.JsonElement) = all()
            override fun includeLinkedTasksContext(): Boolean? = null
        })
        fun input(traits: List<TraitSnapshot>) = ContextInput(
            instructions = "persona", title = "T", description = "D", links = emptyList(), includeLinkedOverride = false,
            ticketId = "t1", role = "backend", agentId = "a1", traits = traits,
        )
        val with = composer.compose(input(listOf(rb)))
        assertEquals(listOf("agenthq", "team", "instructions", "identity", "traits", "ticket"), with.sources.map { it.source })
        assertEquals(renderTraits(listOf(rb))!!.length, with.sources.single { it.source == "traits" }.chars)
        assertEquals(true, with.prompt.contains("${identityLine("backend", "a1")}\n\n${renderTraits(listOf(rb))}\n\nT\n\nD"))

        val without = composer.compose(input(emptyList()))
        assertEquals(listOf("agenthq", "team", "instructions", "identity", "ticket"), without.sources.map { it.source })
        assertEquals(with.prompt.replace("\n\n${renderTraits(listOf(rb))}", ""), without.prompt)
    }
}
