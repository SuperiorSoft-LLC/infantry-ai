package com.superiorsoftllc.agenthq.agents

import com.superiorsoftllc.agenthq.mailbox.FakeAgentProvider
import com.superiorsoftllc.agenthq.mailbox.ScriptedRunner
import com.superiorsoftllc.agenthq.mailbox.tempSessionStore
import com.superiorsoftllc.agenthq.persistence.TicketRow
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.SkillsMode
import com.superiorsoftllc.agenthq.session.InMemorySessionInstances
import com.superiorsoftllc.agenthq.session.RoleInstance
import com.superiorsoftllc.agenthq.session.SessionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * P10-K13 (docs/AGENTHQ-PHASE10-KNOWLEDGE.md K-D9, K-D11): the pure [SkillAttachment.effective] (merge order and
 * de-duplication, replace, include = false resolved ticket -> project -> true) and its freeze at spawn by T4's instance
 * builder ([SessionManager.startNew]): a ticket edited after spawn leaves the instance unchanged, an anonymous spawn
 * gets none.
 */
class SkillAttachmentTest {
    private val agent = AgentProfile(id = "a1", name = "A", role = "backend", provider = "fake", skillIds = listOf("s-agent", "s-shared"))
    private val project = SkillAttachment.Project(skillIds = listOf("s-shared", "s-project"))

    private fun ticket(
        skillIds: List<String> = listOf("s-project", "s-ticket", "s-agent"),
        mode: String = SkillsMode.MERGE,
        include: Boolean? = null,
    ) = TicketRow(
        id = "t1", title = "T", status = "todo", position = 0, createdAt = 1, updatedAt = 1,
        skillIds = skillIds, skillsMode = mode, includeSkillsContext = include,
    )

    // --- the pure resolution ----------------------------------------------------------------------------------

    @Test
    fun mergeIsAgentThenProjectThenTicketAndTheFirstOccurrenceWins() {
        assertEquals(
            listOf("s-agent", "s-shared", "s-project", "s-ticket"),
            SkillAttachment.effective(agent, project, ticket()),
        )
    }

    @Test
    fun mergeIsTheModeWithoutATicketAndWithoutAnAgent() {
        assertEquals(listOf("s-agent", "s-shared", "s-project"), SkillAttachment.effective(agent, project, null))
        assertEquals(listOf("s-shared", "s-project", "s-ticket", "s-agent"), SkillAttachment.effective(null, project, ticket()))
        assertEquals(emptyList(), SkillAttachment.effective(null, SkillAttachment.Project(), null))
    }

    @Test
    fun replaceKeepsOnlyTheTicketsIds() {
        assertEquals(listOf("s-project", "s-ticket", "s-agent"), SkillAttachment.effective(agent, project, ticket(mode = SkillsMode.REPLACE)))
        assertEquals(emptyList(), SkillAttachment.effective(agent, project, ticket(skillIds = emptyList(), mode = SkillsMode.REPLACE)))
    }

    @Test
    fun includeFalseGivesNoSkillsAndResolvesTicketThenProjectThenTrue() {
        assertEquals(emptyList(), SkillAttachment.effective(agent, project, ticket(include = false)), "the ticket switches it off")
        val projectOff = project.copy(includeSkillsContext = false)
        assertEquals(emptyList(), SkillAttachment.effective(agent, projectOff, ticket()), "the ticket inherits the project's false")
        assertEquals(emptyList(), SkillAttachment.effective(agent, projectOff, null), "no ticket: the project's false")
        assertEquals(
            listOf("s-agent", "s-shared", "s-project", "s-ticket"),
            SkillAttachment.effective(agent, projectOff, ticket(include = true)),
            "the ticket's true beats the project's false",
        )
    }

    // --- the freeze at spawn ------------------------------------------------------------------------------------

    private fun manager(instances: InMemorySessionInstances, tickets: Map<String, TicketRow>) =
        SessionManager(FakeAgentProvider(), ScriptedRunner(autoExit = false), tempSessionStore(), killGraceMs = 50, instances = instances, bindingPolicy = com.superiorsoftllc.agenthq.daemon.plugins.TicketBindingPolicy()).apply {
            instanceFor = { agentId, role -> RoleInstance(agentId, role, "persona") }
            skillsFor = { agentId, binding ->
                SkillAttachment.effective(agentId?.let { if (it == agent.id) agent else null }, project, binding?.id?.let(tickets::get))
            }
        }

    @Test
    fun aBoundSpawnFreezesTheEffectiveSkillsAndATicketEditedAfterSpawnLeavesTheInstanceUnchanged() {
        val instances = InMemorySessionInstances()
        val tickets = mutableMapOf("t1" to ticket())
        val manager = manager(instances, tickets)

        val s = manager.startNew("", agentId = "a1", role = "backend", ticketId = "t1")
        val frozen = listOf("s-agent", "s-shared", "s-project", "s-ticket")
        assertEquals(frozen, instances.get(s.id)!!.skillIds, "written by the instance builder at spawn")

        tickets["t1"] = ticket(skillIds = listOf("s-late"), mode = SkillsMode.REPLACE)
        assertEquals(frozen, manager.skillIds(s.id), "the live ticket never reaches a started session")
        assertEquals(frozen, manager.instance(s.id)!!.skillIds)
        tickets["t1"] = ticket(include = false)
        assertEquals(frozen, manager.skillIds(s.id), "switching skills off after spawn changes nothing either")
    }

    @Test
    fun aGivenInstanceStillGetsTheSkillsResolvedAtSpawn() {
        val instances = InMemorySessionInstances()
        val manager = manager(instances, mapOf("t1" to ticket(mode = SkillsMode.REPLACE)))

        val s = manager.startNew("", agentId = "a1", role = "backend", ticketId = "t1", instance = RoleInstance("a1", "backend", "rendered"))

        assertEquals(RoleInstance("a1", "backend", "rendered", skillIds = listOf("s-project", "s-ticket", "s-agent")), instances.get(s.id))
    }

    @Test
    fun anAnonymousSpawnGetsNoSkills() {
        val instances = InMemorySessionInstances()
        val manager = manager(instances, mapOf("t1" to ticket()))
        manager.skillsFor = { _, _ -> error("an anonymous spawn resolves no skills") }

        val s = manager.startNew("", ticketId = "t1")

        assertNull(instances.get(s.id))
        assertEquals(emptyList(), manager.skillIds(s.id))
    }
}
