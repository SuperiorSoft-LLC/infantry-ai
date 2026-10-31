package com.superiorsoftllc.agenthq.backlog

import com.superiorsoftllc.agenthq.settings.RoutingRules
import com.superiorsoftllc.agenthq.settings.WeightExecutor
import com.superiorsoftllc.agenthq.settings.WeightExecutors
import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.context.ComposedContext
import com.superiorsoftllc.agenthq.context.ContextInput
import com.superiorsoftllc.agenthq.context.TicketContext
import com.superiorsoftllc.agenthq.mailbox.FakeAgentProvider
import com.superiorsoftllc.agenthq.provider.AgentProvider
import com.superiorsoftllc.agenthq.provider.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.mailbox.ScriptedRunner
import com.superiorsoftllc.agenthq.mailbox.tempSessionStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.EffectiveContext
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.protocol.TicketStatus.IN_PROGRESS
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.settings.SettingsService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TICKET-WEIGHT (contract 1.104.0): the `weight` field's persistence and migration, the weight -> {provider, model}
 * mapping setting, and its precedence at ticket start (an explicit pin always wins).
 */
class TicketWeightTest {
    private val dir: File = Files.createTempDirectory("agenthq-weight").toFile()
    private val dbPath = File(dir, "agenthq.db").absolutePath
    private val opened = mutableListOf<SqlAgentHqStore>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { runCatching { it.close() } }
        dir.deleteRecursively()
    }

    private fun openStore(): SqlAgentHqStore = SqlAgentHqStore.open(dbPath).also { opened += it }

    private fun plainBoard(store: SqlAgentHqStore = openStore()): TicketBoard {
        val ids = ('a'..'z').map { it.toString() }.iterator()
        return TicketBoard(store, idFactory = { ids.next() })
    }

    // --- persistence ------------------------------------------------------------------------------------

    @Test
    fun aTicketIsUnweightedByDefault_andAWeightSurvivesAReopen() {
        val first = openStore()
        val board = plainBoard(first)
        val plain = board.save(TicketSave(title = "No weight")).ticket
        val heavy = board.save(TicketSave(title = "Heavy", weight = 4)).ticket
        assertNull(plain.weight)
        assertEquals(4, heavy.weight)
        first.close()

        val reread = plainBoard(openStore())
        assertNull(reread.get(plain.id).weight, "an unweighted ticket stays unweighted")
        assertEquals(4, reread.get(heavy.id).weight, "the weight is durable")
    }

    @Test
    fun anEditKeepsTheWeightWhenAbsent_changesItWhenGiven_andClearsItOnZero() {
        val board = plainBoard()
        val id = board.save(TicketSave(title = "T", weight = 2)).ticket.id

        assertEquals(2, board.save(TicketSave(id = id, title = "T2")).ticket.weight, "absent keeps")
        assertEquals(5, board.save(TicketSave(id = id, weight = 5)).ticket.weight, "given changes")
        assertNull(board.save(TicketSave(id = id, weight = 0)).ticket.weight, "0 clears")
        assertNull(board.get(id).weight)
    }

    @Test
    fun aWeightOutsideTheScaleIsRefusedAndSavesNothing() {
        val board = plainBoard()
        for (bad in listOf(-1, 6, 100)) {
            assertFailsWith<IllegalArgumentException>("weight $bad") { board.save(TicketSave(title = "Bad", weight = bad)) }
        }
        assertTrue(board.list().isEmpty(), "a refused create leaves no ticket")
    }

    // --- mapping setting --------------------------------------------------------------------------------

    @Test
    fun theMappingDefaultsLowCheapMidSonnetHighOpus_andTheOwnerCanEditIt() {
        val settings = SettingsService(openStore())
        val defaults = settings.weightExecutors()
        assertEquals("claude-haiku-5-5", defaults.forWeight(1)?.model)
        assertEquals("claude-sonnet-5-5", defaults.forWeight(3)?.model)
        assertEquals(WeightExecutor("pi", "anthropic/claude-opus-5-5"), defaults.forWeight(5), "Opus only as pi + anthropic/claude-opus-5-5")
        assertEquals(WeightExecutor("pi", "anthropic/claude-opus-5-5"), defaults.forWeight(4))
        assertFailsWith<IllegalArgumentException>("Opus off pi is refused") {
            settings.set(WeightExecutors.SETTING, Json.parseToJsonElement("""{"5":{"provider":"claude","model":"claude-opus-5-5"}}"""))
        }
        assertNull(defaults.forWeight(null), "no weight, no choice")
        assertTrue(settings.all().containsKey(WeightExecutors.SETTING), "the mapping is listed with the other settings")

        val edit = buildJsonObject { put("1", buildJsonObject { put("provider", "opencode"); put("model", "free-model") }) }
        settings.set(WeightExecutors.SETTING, edit)
        val reread = SettingsService(openStore()).weightExecutors()
        assertEquals(WeightExecutor("opencode", "free-model"), reread.forWeight(1), "stored server-side")
        assertEquals("claude-sonnet-5-5", reread.forWeight(3)?.model, "weights left out keep the default")
    }

    @Test
    fun aMalformedMappingIsRefusedAndChangesNothing() {
        val settings = SettingsService(openStore())
        val bad = listOf<kotlinx.serialization.json.JsonElement>(
            JsonPrimitive("x"),
            Json.parseToJsonElement("""{"9":{"provider":"a"}}"""),
            Json.parseToJsonElement("""{"1":{"provider":null,"model":null}}"""),
            Json.parseToJsonElement("""{"1":"claude"}"""),
            Json.parseToJsonElement("""{"1":{"provider":5}}"""),
        )
        for (value in bad) assertFailsWith<IllegalArgumentException>("$value") { settings.set(WeightExecutors.SETTING, value) }
        assertEquals("claude-haiku-5-5", settings.weightExecutors().forWeight(1)?.model)
    }

    // --- precedence -------------------------------------------------------------------------------------

    @Test
    fun theMappingAppliesOnlyToAWeightedTicketThatPinsNothing() {
        assertTrue(WeightExecutors.applies(3, null, null, null, null))
        assertEquals(false, WeightExecutors.applies(null, null, null, null, null), "unweighted: current behaviour")
        assertEquals(false, WeightExecutors.applies(3, "claude", null, null, null), "a ticket provider wins")
        assertEquals(false, WeightExecutors.applies(3, null, "m", null, null), "a ticket model wins")
        assertEquals(false, WeightExecutors.applies(3, null, null, "claude", null), "a role / profile provider wins")
        assertEquals(false, WeightExecutors.applies(3, null, null, null, "m"), "a role / profile model wins")
    }

    private val seen = mutableListOf<RunRequest>()
    private val recording = object : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest): ProcessSpec = FakeAgentProvider().spec(request).also { seen += request }
    }

    private class Started(val ticket: Ticket, val spawnedModel: String?, val spawnedProvider: String?)

    /** Starts one ticket on the board whose weight mapping sends weight 5 to fake/heavy-model; returns what the start recorded. */
    private fun start(save: TicketSave, profile: AgentProfile = AgentProfile(name = "Backend", role = "backend")): Started {
        val store = openStore()
        val manager = SessionManager(recording, ScriptedRunner(autoExit = false), tempSessionStore(), killGraceMs = 50, defaultWorkingDir = dir)
        val profiles = AgentProfiles(store.agents, manager)
        val worker = profiles.save(profile)
        val prompting = object : TicketContext {
            override fun compose(input: ContextInput) = ComposedContext("Ship ${input.title}.", emptyList())
            override fun effective(override: Boolean?, skillsOverride: Boolean?): EffectiveContext? = null
        }
        val mapping = WeightExecutors.parse(
            buildJsonObject { put("5", buildJsonObject { put("provider", "fake"); put("model", "heavy-model") }) },
        )
        val ids = ('a'..'z').map { it.toString() }.iterator()
        val board = TicketBoard(store, idFactory = { ids.next() }, context = prompting, agents = profiles, sessions = manager, weightExecutors = { mapping })
        val ticket = board.save(save.copy(agentId = worker.id)).ticket
        val view = board.get(ticket.id).effectiveExecutor!!
        val answer = board.move(TicketMove(ticket.id, IN_PROGRESS)).ticket
        assertEquals("started", answer.start?.reason, "start detail: ${answer.start?.detail}")
        board.close()
        val request = seen.single()
        return Started(
            ticket = answer.copy(effectiveExecutor = view),
            spawnedModel = request.model,
            spawnedProvider = recording.id,
        )
    }

    @Test
    fun aWeightedTicketWithNoPinStartsOnTheMappedExecutor_andSaysWhy() {
        val started = start(TicketSave(title = "Big", weight = 5))

        assertEquals("heavy-model", started.spawnedModel)
        assertEquals("fake", started.spawnedProvider)
        assertEquals("weight", started.ticket.effectiveExecutor!!.model!!.source)
        assertEquals("heavy-model", started.ticket.effectiveExecutor!!.model!!.value)
        val detail = started.ticket.start!!.detail.orEmpty()
        assertTrue(detail.contains("weight 5") && detail.contains("heavy-model"), "the start says which model and why: '$detail'")
    }

    @Test
    fun anExplicitModelOnTheTicketBeatsTheWeight() {
        val started = start(TicketSave(title = "Pinned", weight = 5, provider = "fake", model = "pinned-model"))

        assertEquals("pinned-model", started.spawnedModel)
        assertEquals("ticket", started.ticket.effectiveExecutor!!.model!!.source)
        assertTrue(!started.ticket.start!!.detail.orEmpty().contains("weight 5"), "the weight did not choose, so it is not named")
    }

    @Test
    fun aProfileThatPinsAModelBeatsTheWeight() {
        val started = start(TicketSave(title = "Profile", weight = 5), AgentProfile(name = "Pinned", role = "backend", provider = "fake", model = "profile-model"))

        assertEquals("profile-model", started.spawnedModel)
        assertEquals("profile", started.ticket.effectiveExecutor!!.model!!.source)
    }

    @Test
    fun anUnweightedTicketKeepsTheCurrentBehaviour() {
        val started = start(TicketSave(title = "Plain"))

        assertTrue(started.ticket.effectiveExecutor!!.model!!.source != "weight")
        assertTrue(!started.ticket.start!!.detail.orEmpty().contains("weight"))
    }
}
