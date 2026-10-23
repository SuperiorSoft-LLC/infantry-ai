package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.net.DaemonClient
import com.superiorsoftllc.agenthq.ui.protocol.AgentProfile
import com.superiorsoftllc.agenthq.ui.protocol.AgentSwapped
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.Project
import com.superiorsoftllc.agenthq.ui.protocol.SwapExecutor
import com.superiorsoftllc.agenthq.ui.protocol.SwapSession
import com.superiorsoftllc.agenthq.ui.protocol.SwapSessionMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

/** A fake daemon link: records every frame sent and lets [respond] answer one, the way the daemon would. */
private class AgentsLink(scope: CoroutineScope) : DaemonClient(scope) {
    val sent = mutableListOf<Envelope>()
    var respond: (Envelope) -> List<Envelope> = { emptyList() }
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    override val frames: SharedFlow<Envelope> get() = incoming
    override suspend fun send(envelope: Envelope): Boolean {
        synchronized(sent) { sent += envelope }
        respond(envelope).forEach { check(incoming.tryEmit(it)) { "frame buffer full" } }
        return true
    }
    suspend fun push(e: Envelope) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 10_000.milliseconds
        while (incoming.subscriptionCount.value == 0 && deadline.hasNotPassedNow()) delay(10)
        check(incoming.tryEmit(e)) { "frame buffer full" }
    }
    fun sentOf(type: String) = synchronized(sent) { sent.filter { it.type == type } }
    fun subscribers(): Int = incoming.subscriptionCount.value
    fun emitNow(e: Envelope) = check(incoming.tryEmit(e)) { "frame buffer full" }
}

/** Holds every dispatched task until the test runs it, so a test can choose the order coroutines interleave in. */
private class ParkedDispatcher : CoroutineDispatcher() {
    private val parked = ArrayDeque<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { parked.addLast(block) }
    /** Run the most recently parked task first (tasks it parks run before older ones) until [until] holds. */
    fun runLatestFirst(until: () -> Boolean = { false }) {
        while (!until() && parked.isNotEmpty()) parked.removeLast().run()
    }
}

/**
 * ROLE-FIRST S8: the Controller's agent commands -- add (agent_save then new{agentId,role} + prompt), swap
 * (agent_swap, applied from the agent_swapped push on every window), the effort-carrying save, and the gate
 * that keeps an older daemon from being sent what it cannot read.
 */
class ControllerAgentsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val qa = AgentProfile(id = "qa-1", name = "QA", role = "qa", provider = "claude", model = "sonnet", projectId = "p1", effort = "low")

    private fun <T> withController(contract: String, profiles: List<AgentProfile> = listOf(qa), body: suspend (Controller, AgentsLink) -> T): T {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = AgentsLink(scope)
            val controller = Controller(scope, link)
            return runBlocking {
                link.push(Envelope(Envelope.READY, "contract=$contract provider=stub instance=i1"))
                link.push(Envelope(Envelope.AGENTS, json.encodeToString(ListSerializer(AgentProfile.serializer()), profiles)))
                link.push(Envelope(Envelope.PROJECTS, json.encodeToString(ListSerializer(Project.serializer()), listOf(Project("p1", "P1")))))
                await(controller) { it.connection.isLive && it.profiles.size == profiles.size && it.activeProjectId == "p1" }
                body(controller, link)
            }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun await(controller: Controller, what: (AppState) -> Boolean) {
        val deadline = kotlin.time.TimeSource.Monotonic.markNow() + 10_000.milliseconds
        while (!what(controller.state.value) && deadline.hasNotPassedNow()) delay(10)
        check(what(controller.state.value)) { "state never reached the expected shape: ${controller.state.value.profiles}" }
    }

    private fun data(env: Envelope): JsonObject = Json.parseToJsonElement(env.data).jsonObject

    private fun swapped(agent: AgentProfile, outcome: String, sessionId: String?) = Envelope(
        Envelope.AGENT_SWAPPED,
        json.encodeToString(AgentSwapped.serializer(), AgentSwapped(agent, SwapExecutor("claude", "sonnet", "low"), SwapSession(outcome, sessionId))),
    )

    @Test
    fun addAgentSavesTheProfileThenStartsItsFirstSessionByAgentIdAndRole() = withController("1.47.0") { c, link ->
        link.respond = { env ->
            if (env.type == Envelope.AGENT_SAVE) {
                val asked = json.decodeFromString(AgentProfile.serializer(), env.data)
                listOf(Envelope(Envelope.AGENT, json.encodeToString(AgentProfile.serializer(), asked.copy(id = "dev-9"))))
            } else emptyList()
        }
        val r = c.addAgent("developer", SwapExecutor("opencode", "deepseek/v3", "high"), "fix the build", name = "Dev")
        assertEquals(CommandResult.Sent, r)
        val saved = json.decodeFromString(AgentProfile.serializer(), link.sentOf(Envelope.AGENT_SAVE).single().data)
        assertEquals(AgentProfile(name = "Dev", role = "developer", provider = "opencode", model = "deepseek/v3", projectId = "p1", effort = "high"), saved)
        val new = data(link.sentOf(Envelope.NEW).single())
        assertEquals("dev-9", new["agentId"]?.jsonPrimitive?.content)
        assertEquals("developer", new["role"]?.jsonPrimitive?.content)
        assertEquals("fix the build", link.sentOf(Envelope.PROMPT).single().data)
        assertTrue(c.state.value.profiles.any { it.id == "dev-9" })
    }

    @Test
    fun addAgentWithNoTaskAndNoExecutorCreatesAnInheritingAgentAndStartsNothing() = withController("1.47.0") { c, link ->
        link.respond = { env ->
            if (env.type == Envelope.AGENT_SAVE) listOf(Envelope(Envelope.AGENT, json.encodeToString(AgentProfile.serializer(), json.decodeFromString(AgentProfile.serializer(), env.data).copy(id = "qa-2"))))
            else emptyList()
        }
        assertEquals(CommandResult.Sent, c.addAgent("qa", null, "  "))
        val saved = json.decodeFromString(AgentProfile.serializer(), link.sentOf(Envelope.AGENT_SAVE).single().data)
        assertNull(saved.provider, "an untouched executor saves no override: the agent inherits the role default")
        assertNull(saved.model)
        assertEquals("qa", saved.name, "the name defaults to the role (its id when the catalog has no entry)")
        assertTrue(link.sentOf(Envelope.NEW).isEmpty(), "a blank task starts nothing")
        assertTrue(link.sentOf(Envelope.PROMPT).isEmpty())
    }

    @Test
    fun addAgentRefusedByTheDaemonStartsNothingAndSurfacesTheError() = withController("1.47.0") { c, link ->
        link.respond = { env -> if (env.type == Envelope.AGENT_SAVE) listOf(Envelope(Envelope.ERROR, "agent_save refused: unknown role 'nope'")) else emptyList() }
        val r = c.addAgent("nope", null, "hello")
        assertTrue(r is CommandResult.Refused, "was $r")
        assertEquals("agent_save refused: unknown role 'nope'", c.state.value.agentError)
        assertTrue(link.sentOf(Envelope.NEW).isEmpty())
    }

    @Test
    fun swapSendsAgentSwapAndTheKeptPushUpdatesTheAgentAndRecordsTheOutcome() = withController("1.47.0") { c, link ->
        assertTrue(c.state.value.connection.swapsAgents)
        val stored = qa.copy(model = "opus", effort = "high")
        link.respond = { env -> if (env.type == Envelope.AGENT_SWAP) listOf(swapped(stored, "kept", "s-1")) else emptyList() }
        assertEquals(CommandResult.Sent, c.swapAgent("qa-1", "claude", "opus", "high", SwapSessionMode.KEEP))
        val d = data(link.sentOf(Envelope.AGENT_SWAP).single())
        assertEquals(mapOf("agentId" to "qa-1", "provider" to "claude", "model" to "opus", "effort" to "high", "session" to "keep"), d.mapValues { it.value.jsonPrimitive.content })
        await(c) { it.agentSwaps["qa-1"] != null }
        assertEquals(stored, c.state.value.profiles.single { it.id == "qa-1" })
        assertEquals(SwapSession("kept", "s-1"), c.state.value.agentSwaps["qa-1"]!!.session)
    }

    @Test
    fun swapWithNoModelOrEffortOmitsThemAndDefaultsTheSessionToAuto() = withController("1.47.0") { c, link ->
        assertEquals(CommandResult.Sent, c.swapAgent("qa-1", "pi", null, null))
        val d = data(link.sentOf(Envelope.AGENT_SWAP).single())
        assertEquals(setOf("agentId", "provider", "session"), d.keys, "the daemon decodes strictly: an absent model/effort is omitted, never null")
        assertEquals("auto", d["session"]?.jsonPrimitive?.content)
    }

    @Test
    fun aNewSessionOutcomeAndANoneOutcomeAreRecorded() = withController("1.47.0") { c, link ->
        link.push(swapped(qa.copy(provider = "pi", model = null), "new", "s-new"))
        await(c) { it.agentSwaps["qa-1"]?.session?.outcome == "new" }
        assertEquals("s-new", c.state.value.agentSwaps["qa-1"]!!.session.sessionId)
        assertEquals("pi", c.state.value.profiles.single { it.id == "qa-1" }.provider)
        link.push(swapped(qa.copy(provider = "opencode"), "none", null))
        await(c) { it.agentSwaps["qa-1"]?.session?.outcome == "none" }
        assertNull(c.state.value.agentSwaps["qa-1"]!!.session.sessionId)
    }

    @Test
    fun aSwapPushedForAnotherClientsSwapIsAppliedHereToo() = withController("1.47.0") { c, link ->
        // This window sent nothing: the push is the daemon's fan-out of someone else's agent_swap.
        link.push(swapped(qa.copy(model = "haiku"), "kept", "s-1"))
        await(c) { it.agentSwaps["qa-1"] != null }
        assertTrue(link.sentOf(Envelope.AGENT_SWAP).isEmpty())
        assertEquals("haiku", c.state.value.profiles.single { it.id == "qa-1" }.model)
    }

    @Test
    fun aSwapRefusalIsAStateErrorAndChangesNothingLocally() = withController("1.47.0") { c, link ->
        link.respond = { env -> if (env.type == Envelope.AGENT_SWAP) listOf(Envelope(Envelope.ERROR, "agent_swap refused: keep is not possible across harnesses")) else emptyList() }
        assertEquals(CommandResult.Sent, c.swapAgent("qa-1", "pi", null, null, SwapSessionMode.KEEP))
        await(c) { it.agentError != null }
        assertEquals("agent_swap refused: keep is not possible across harnesses", c.state.value.agentError)
        assertEquals(qa, c.state.value.profiles.single(), "a refusal writes nothing: the profile stays as it was")
        assertTrue(c.state.value.agentSwaps.isEmpty())
    }

    @Test
    fun belowContract147SwapIsUnavailableAndNothingIsSent() = withController("1.46.0") { c, link ->
        assertFalse(c.state.value.connection.swapsAgents)
        val r = c.swapAgent("qa-1", "claude", "opus", null)
        assertTrue(r is CommandResult.Refused, "was $r")
        assertTrue(link.sentOf(Envelope.AGENT_SWAP).isEmpty())
    }

    @Test
    fun saveAgentCarriesEffortFrom146() = withController("1.46.0") { c, link ->
        assertEquals(CommandResult.Sent, c.saveAgent(qa.copy(effort = "max")))
        assertEquals("max", data(link.sentOf(Envelope.AGENT_SAVE).single())["effort"]?.jsonPrimitive?.content)
    }

    @Test
    fun belowContract146EffortIsNeverSentOnASaveOrAnAdd() = withController("1.45.0") { c, link ->
        assertFalse(c.state.value.connection.sendsAgentEffort)
        assertEquals(CommandResult.Sent, c.saveAgent(qa.copy(effort = "max")))
        assertFalse("effort" in data(link.sentOf(Envelope.AGENT_SAVE).single()).keys, "an older daemon is never sent the effort key")
        link.respond = { env -> if (env.type == Envelope.AGENT_SAVE) listOf(Envelope(Envelope.AGENT, json.encodeToString(AgentProfile.serializer(), json.decodeFromString(AgentProfile.serializer(), env.data).copy(id = "x")))) else emptyList() }
        assertEquals(CommandResult.Sent, c.addAgent("qa", SwapExecutor("claude", null, "high"), null))
        assertFalse("effort" in data(link.sentOf(Envelope.AGENT_SAVE).last()).keys)
    }
    /** M2-17: an edit sends only the changed fields; an empty epicId (None) needs contract 1.60.0. */
    @Test
    fun editTicketSendsOnlyTheChangedFieldsAndClearsTheEpicWithAnEmptyId() = withController("1.60.0") { c, link ->
        assertEquals(CommandResult.Sent, c.editTicket("t-1", epicId = ""))
        val sent = data(link.sentOf(Envelope.TICKET_SAVE).single())
        assertEquals(setOf("id", "epicId"), sent.keys, "only the id and the changed epic travel: $sent")
        assertEquals("", sent["epicId"]!!.jsonPrimitive.content)
    }

    @Test
    fun belowContract160ClearingATicketsEpicIsRefusedLocallyAndNothingIsSent() = withController("1.59.0") { c, link ->
        assertTrue(c.editTicket("t-1", epicId = "") is CommandResult.Refused)
        assertTrue(link.sentOf(Envelope.TICKET_SAVE).isEmpty(), "an older daemon would silently keep the epic")
        assertEquals(CommandResult.Sent, c.editTicket("t-1", epicId = "e-1"), "filing under an epic works on any daemon")
    }

    @Test
    fun aTicketSaveRefusalIsKeptForTheEditFormAndClearedByTheNextEdit() = withController("1.60.0") { c, link ->
        link.respond = { env -> if (env.type == Envelope.TICKET_SAVE) listOf(Envelope(Envelope.ERROR, "ticket_save refused: no ticket 't-9'")) else emptyList() }
        assertEquals(CommandResult.Sent, c.editTicket("t-9", title = "x"))
        await(c) { it.ticketError != null }
        assertEquals("ticket_save refused: no ticket 't-9'", c.state.value.ticketError)
        link.respond = { emptyList() }
        c.editTicket("t-9", title = "y")
        assertNull(c.state.value.ticketError, "the next edit starts without the old refusal")
    }

    /**
     * BUG-FLAKE-4 (gate #15: "state never reached the expected shape" in the swap test, under full-suite load): the
     * order load produces, forced. The link-info collector is held back while the frame collector reduces `ready`;
     * the info collector then runs with the link's initial (IDLE) info. It must not take the confirmed link down.
     */
    @Test
    fun aReadyReducedBeforeTheInfoCollectorRunsStaysLive() {
        val dispatcher = ParkedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val link = AgentsLink(scope)
            val controller = Controller(scope, link)
            // Newest first: every collector launched after the info one starts, the frame collector subscribes.
            dispatcher.runLatestFirst(until = { link.subscribers() > 0 })
            link.emitNow(Envelope(Envelope.READY, "contract=1.47.0 provider=stub instance=i1"))
            // `ready` is reduced first, then whatever is still parked (the info collector, when it was dispatched).
            dispatcher.runLatestFirst()
            assertTrue(controller.state.value.connection.isLive, "ready confirmed the link: ${controller.state.value.connection}")
        } finally {
            scope.cancel()
        }
    }
}
