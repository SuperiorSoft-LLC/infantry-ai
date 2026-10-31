package com.superiorsoftllc.agenthq.backlog

import com.superiorsoftllc.agenthq.daemon.api.board.BoardAgent
import com.superiorsoftllc.agenthq.settings.RoutingRules
import com.superiorsoftllc.agenthq.settings.WeightExecutors
import com.superiorsoftllc.agenthq.context.ComposedContext
import com.superiorsoftllc.agenthq.context.ContextInput
import com.superiorsoftllc.agenthq.context.TicketContext
import com.superiorsoftllc.agenthq.protocol.EffectiveContext
import com.superiorsoftllc.agenthq.protocol.RoutingRule
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.protocol.TicketStatus.IN_PROGRESS
import com.superiorsoftllc.agenthq.protocol.TicketStatus.TODO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ROUTING-1 (contract 1.128.0): rule matching, validation, the Opus line, precedence and the no-fallback park. */
class RoutingRulesTest {
    private val light = RoutingRule("light", weights = listOf(1, 2), provider = "fake", model = "light-model")
    private val heavy = RoutingRule("heavy", weights = listOf(4, 5), provider = "fake", model = "heavy-model")
    private val defaultRow = RoutingRule("default", provider = "fake", model = "mid-model")
    private val rules = listOf(light, heavy, defaultRow)

    // --- matching ---------------------------------------------------------------------------------------

    @Test
    fun weightBandsPickTheirRule_andTheDefaultCatchesTheRest() {
        assertEquals("light", RoutingRules.match(rules, 1, null, null)?.name)
        assertEquals("light", RoutingRules.match(rules, 2, null, null)?.name)
        assertEquals("default", RoutingRules.match(rules, 3, null, null)?.name)
        assertEquals("heavy", RoutingRules.match(rules, 4, null, null)?.name)
        assertEquals("heavy", RoutingRules.match(rules, 5, null, null)?.name)
        assertEquals("default", RoutingRules.match(rules, null, null, null)?.name, "unweighted reaches only the default row")
    }

    @Test
    fun theFirstMatchWins() {
        val first = RoutingRule("docs", weights = listOf(4, 5), ticketTypes = listOf("documentation"), provider = "fake", model = "docs-model")
        val ordered = listOf(first, heavy, defaultRow)
        assertEquals("docs", RoutingRules.match(ordered, 5, "documentation", null)?.name)
        assertEquals("heavy", RoutingRules.match(ordered, 5, "defect", null)?.name)
        assertEquals("heavy", RoutingRules.match(listOf(heavy, first, defaultRow), 5, "documentation", null)?.name, "order decides, not specificity")
    }

    @Test
    fun aRoleAndATypeMatchOnlyWhenPresent() {
        val gate = RoutingRule("gate", role = "qa", provider = "fake", model = "gate-model")
        val list = listOf(gate, defaultRow)
        assertEquals("gate", RoutingRules.match(list, 3, null, "qa")?.name)
        assertEquals("default", RoutingRules.match(list, 3, null, "backend")?.name)
        assertEquals("default", RoutingRules.match(list, 3, null, null)?.name)
    }

    // --- validation -------------------------------------------------------------------------------------

    private fun validate(list: List<RoutingRule>, roleKnown: (String) -> Boolean = { true }, check: (String, String, String?) -> Unit = { _, _, _ -> }) =
        RoutingRules.validate(list, check, roleKnown)

    @Test
    fun aGoodListIsAcceptedAndAnEmptyOneClears() {
        assertEquals(3, validate(rules).size)
        assertTrue(validate(emptyList()).isEmpty())
    }

    @Test
    fun anUnknownProviderOrModelIsRefusedWithTheRuleName() {
        val ex = assertFailsWith<IllegalArgumentException> {
            validate(rules) { _, model, _ -> require(model != "heavy-model") { "model 'heavy-model' is not listed" } }
        }
        assertTrue(ex.message!!.contains("rule 'heavy'") && ex.message!!.contains("not listed"), ex.message)
    }

    @Test
    fun theListMustEndInAMatchAllDefault_andOnlyThere() {
        assertFailsWith<IllegalArgumentException> { validate(listOf(light, heavy)) }
        assertFailsWith<IllegalArgumentException> { validate(listOf(defaultRow, light)) }
    }

    @Test
    fun badNamesWeightsTypesRolesAndSizesAreRefused() {
        assertFailsWith<IllegalArgumentException> { validate(listOf(light.copy(name = " "), defaultRow)) }
        assertFailsWith<IllegalArgumentException> { validate(listOf(light, light, defaultRow)) }
        assertFailsWith<IllegalArgumentException> { validate(listOf(light.copy(weights = listOf(6)), defaultRow)) }
        assertFailsWith<IllegalArgumentException> { validate(listOf(light.copy(ticketTypes = listOf("nonsense")), defaultRow)) }
        assertFailsWith<IllegalArgumentException> { validate(listOf(light.copy(role = "ghost"), defaultRow), roleKnown = { it != "ghost" }) }
        assertFailsWith<IllegalArgumentException> { validate(List(RoutingRules.MAX_RULES + 1) { defaultRow.copy(name = "r$it") }) }
    }

    @Test
    fun opusRunsOnlyOnPiWithTheAnthropicModel() {
        validate(listOf(heavy.copy(provider = "pi", model = "anthropic/claude-opus-5-5"), defaultRow))
        for ((p, m) in listOf("claude" to "claude-opus-5-5", "pi" to "claude-opus-5-5", "opencode" to "anthropic/claude-opus-5-5", "pi" to "anthropic/claude-opus-4")) {
            assertFailsWith<IllegalArgumentException>("$p/$m") { validate(listOf(heavy.copy(provider = p, model = m), defaultRow)) }
        }
    }

    // --- precedence at start, and the park (DP-5f-6: over the board-side fake host) ---------------------

    private val reported = mutableListOf<String>()
    private lateinit var host: InMemoryBoardHost

    private fun board(projectRules: List<RoutingRule>, profile: BoardAgent = BoardAgent(name = "Backend", role = "backend")): Pair<TicketBoard, String?> {
        host = InMemoryBoardHost()
        val worker = host.agents.save(profile)
        val prompting = object : TicketContext {
            override fun compose(input: ContextInput) = ComposedContext("Ship ${input.title}.", emptyList())
            override fun effective(override: Boolean?, skillsOverride: Boolean?): EffectiveContext? = null
        }
        val ids = ('a'..'z').map { it.toString() }.iterator()
        return TicketBoard(
            host, idFactory = { ids.next() }, context = prompting, profilesAvailable = true,
            routingRules = { projectRules }, routingRefused = { id, _, detail -> reported += "$id|$detail" },
        ) to worker.id
    }

    @Test
    fun aRuleBeatsTheRoleBinding_andTheTicketSaysWhichRule() {
        val (b, worker) = board(rules, BoardAgent(name = "Pinned", role = "backend", provider = "fake", model = "profile-model"))
        val t = b.save(TicketSave(title = "Big", weight = 5, agentId = worker)).ticket
        val view = b.get(t.id).effectiveExecutor!!
        assertEquals("heavy", view.rule)
        assertEquals("rule", view.model!!.source)
        val answer = b.move(TicketMove(t.id, IN_PROGRESS)).ticket
        assertEquals("started", answer.start?.reason, answer.start?.detail)
        assertEquals("heavy-model", host.starts.single().model)
        assertTrue(answer.start!!.detail.orEmpty().contains("rule 'heavy'"), answer.start?.detail)

    }

    @Test
    fun aTicketsOwnPinBeatsEveryRule() {
        val (b, worker) = board(rules)
        val t = b.save(TicketSave(title = "Pinned", weight = 5, provider = "fake", model = "pinned-model", agentId = worker)).ticket
        val view = b.get(t.id).effectiveExecutor!!
        assertNull(view.rule)
        assertEquals("ticket", view.model!!.source)
        b.move(TicketMove(t.id, IN_PROGRESS))
        assertEquals("pinned-model", host.starts.single().model)

    }

    @Test
    fun aRuleThatCannotStartParksTheTicketAndNeverFallsBack() {
        val broken = listOf(heavy.copy(provider = "ghost-harness"), defaultRow)
        val (b, worker) = board(broken)
        val t = b.save(TicketSave(title = "Big", weight = 5, agentId = worker)).ticket
        val answer = b.move(TicketMove(t.id, IN_PROGRESS)).ticket
        assertEquals("start_refused", answer.start?.reason)
        assertTrue(answer.start!!.detail.orEmpty().contains("rule 'heavy'"), answer.start?.detail)
        assertEquals(TODO, b.get(t.id).status, "the ticket is parked in todo")
        assertTrue(host.starts.isEmpty(), "nothing ran: not on the default row, not on another harness")
        assertEquals(1, reported.size, "the refusal was reported")
        b.move(TicketMove(t.id, IN_PROGRESS))
        assertEquals(1, reported.size, "the same refusal is not reported twice")

    }

    @Test
    fun aProjectWithNoRulesKeepsTodaysBehaviour() {
        val (b, worker) = board(emptyList(), BoardAgent(name = "Pinned", role = "backend", provider = "fake", model = "profile-model"))
        val t = b.save(TicketSave(title = "Plain", weight = 5, agentId = worker)).ticket
        assertNull(b.get(t.id).effectiveExecutor!!.rule)
        b.move(TicketMove(t.id, IN_PROGRESS))
        assertEquals("profile-model", host.starts.single().model)

    }
}
