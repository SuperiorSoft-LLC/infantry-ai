package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.attention.AttentionItem
import com.superiorsoftllc.agenthq.attention.AttentionKind
import com.superiorsoftllc.agenthq.orchestration.GatedRunner
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.protocol.TriggerEventView
import com.superiorsoftllc.agenthq.protocol.TriggerView
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 9.5 wiring (contract 1.14.0): manual triggers whose action is `start_next`, on the real daemon.
 *
 * A trigger coalesces events (window, one run in flight, one follow-up slot), refuses past its hourly
 * ceiling, and TRIPS on any non-success: an attention item is raised, later events are recorded
 * `suppressed_tripped`, nothing is retried, and only `trigger_arm` re-arms it. Every event gets a durable
 * disposition row. A trigger's start is UNATTENDED, so it passes the launch gate.
 */
class TriggerDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val manual = """{"name":"nightly","coalesceMs":0,"action":{"kind":"start_next"}}"""

    @Test
    fun aSavedTriggerIsPushedListedAndAnUnservedKindIsRefused() = withDaemon(StubScript.default()) { fixture ->
        val watcher = fixture.connectClient()
        fixture.send(Envelope(Envelope.TRIGGER_SAVE, manual))
        val saved = trigger(fixture.client.await(Envelope.TRIGGER))
        assertEquals("nightly", saved.name)
        assertEquals("armed", saved.state)
        assertEquals("manual", saved.kind)
        assertEquals(saved, trigger(watcher.await(Envelope.TRIGGER)), "pushed to every client")

        fixture.send(Envelope(Envelope.TRIGGER_LIST))
        assertEquals(listOf(saved), triggers(fixture.client.await(Envelope.TRIGGERS)))

        fixture.send(Envelope(Envelope.TRIGGER_SAVE, """{"name":"x","action":{"kind":"pod"}}"""))
        val refused = fixture.client.awaitWhere("the refusal", 10_000) { it.type == Envelope.ERROR }
        assertTrue(refused.data.startsWith("trigger_save refused: ") && refused.data.contains("pod"), refused.data)
    }

    @Test
    fun firingStartsTheTopTicketUnattendedAndASuccessReArms() = withDaemon(StubScript.default()) { fixture ->
        fixture.client.saveProfile("""{"id":"backend","name":"Backend","role":"backend"}""")
        val ticket = fixture.client.saveTicket("""{"title":"Ship it","agentId":"backend"}""")
        val trigger = save(fixture, manual)

        val mark = fixture.client.mark()
        fixture.send(Envelope(Envelope.TRIGGER_FIRE, trigger.id))
        // The hand-out is an EVENT the daemon pushes: `broadcastBoard` sends one `ticket` frame per
        // changed ticket, carrying `start` and the session id. Wait for THAT frame -- `mark` taken
        // before the fire, so a fast hand-out's frame cannot be missed -- instead of polling derived
        // board state (`board.get(...).sessionId`) against a wall clock. Derived state is written by
        // several threads at different moments, so polling it turns "how long did this take" into a
        // correctness assumption; the pushed frame IS the hand-out's own answer. A start that never
        // comes then fails on its own assertion, naming the reason the hand-out gave, instead of as a
        // bare `not met within Nms` timeout that says nothing about why.
        val started = fixture.client
            .awaitAfter(mark, "the ticket the trigger started") { frame ->
                frame.ticketOrNull()?.let { it.id == ticket.id && it.start != null } == true
            }
            .requireTicket()
        assertEquals("started", started.start!!.reason, "the trigger's hand-out must start the ticket: ${started.start?.detail}")
        assertNotNull(started.sessionId, "a started ticket carries the session id of its run")

        val session = fixture.daemon.manager.get(started.sessionId!!)!!
        assertEquals("backend", session.agentId)
        assertTrue(session.unattended, "a trigger's run is unattended")

        // The re-arm is pushed the same way -- the trigger's own `trigger` frame once its run ends.
        // Scan from the SAME mark: the save's own "armed" push sits behind it, so the first "armed"
        // after the mark is the re-arm and cannot be missed by the stub finishing before a later mark.
        fixture.client.awaitAfter(mark, "the trigger re-armed after the run succeeded") { frame ->
            frame.type == Envelope.TRIGGER && trigger(frame).let { it.id == trigger.id && it.state == "armed" }
        }
        // Reaching "armed" is only possible from a run that ended SUCCEEDED (IDLE, exit 0 at that moment). The session
        // may be re-prompted right after, so its live state is not asserted here; the exit code is final.
        assertEquals(0, fixture.daemon.manager.get(session.id)?.lastExitCode, "a successful run exits 0")

        assertEquals(listOf("started"), events(fixture, trigger.id).map { it.disposition })
    }

    @Test
    fun aFailedRunTripsTheTriggerRaisesAttentionAndSuppressesUntilArmed() = withDaemon(StubScript.failing()) { fixture ->
        fixture.client.saveProfile("""{"id":"backend","name":"Backend","role":"backend"}""")
        fixture.client.saveTicket("""{"title":"Fails","agentId":"backend"}""")
        val trigger = save(fixture, manual)

        fixture.send(Envelope(Envelope.TRIGGER_FIRE, trigger.id))
        val tripped = fixture.client.awaitWhere("the trip attention", 10_000) { frame ->
            frame.type == Envelope.ATTENTION &&
                json.decodeFromString(AttentionItem.serializer(), frame.data).let { it.kind == AttentionKind.TRIGGER_TRIPPED && it.triggerId == trigger.id }
        }
        assertNotNull(tripped)
        fixture.await(5_000, "the trigger tripped") { state(fixture, trigger.id) == "tripped" }

        fixture.send(Envelope(Envelope.TRIGGER_FIRE, trigger.id))
        fixture.await(5_000, "the later event suppressed") { events(fixture, trigger.id).map { it.disposition } == listOf("started", "suppressed_tripped") }
        assertEquals(1, fixture.daemon.manager.list().size, "a tripped trigger starts nothing and never retries")

        fixture.send(Envelope(Envelope.TRIGGER_ARM, trigger.id))
        fixture.await(5_000, "re-armed only by trigger_arm") { state(fixture, trigger.id) == "armed" }
        val health = fixture.health()
        assertEquals("0", health["triggersTripped"].toString())
    }

    @Test
    fun anEmptyBoardIsNothingToStartAndNeverATrip() = withDaemon(StubScript.default()) { fixture ->
        val trigger = save(fixture, manual)
        fixture.send(Envelope(Envelope.TRIGGER_FIRE, trigger.id))
        fixture.await(5_000, "the nothing_to_start disposition") { events(fixture, trigger.id).map { it.disposition } == listOf("nothing_to_start") }
        assertEquals("armed", state(fixture, trigger.id))
        assertTrue(fixture.daemon.attention.list().none { it.kind == AttentionKind.TRIGGER_TRIPPED })
    }

    @Test
    fun aTriggerRunningWhenTheDaemonStopsComesBackTripped() {
        val state = Files.createTempDirectory("agenthq-trigger-test").toFile()
        val first = DaemonFixture.launch(runner = GatedRunner(), context = FakeTicketContext("HANG"), stateDir = state)
        val triggerId: String
        try {
            first.client.await(Envelope.READY)
            first.client.saveProfile("""{"id":"backend","name":"Backend","role":"backend"}""")
            first.client.saveTicket("""{"title":"Hangs","agentId":"backend"}""")
            triggerId = save(first, manual).id
            first.send(Envelope(Envelope.TRIGGER_FIRE, triggerId))
            first.await(10_000, "the trigger running") { state(first, triggerId) == "running" }
        } finally {
            first.stop()
        }
        val second = DaemonFixture.launch(StubScript.default(), stateDir = state)
        try {
            second.client.await(Envelope.READY)
            second.send(Envelope(Envelope.TRIGGER_LIST))
            val reloaded = triggers(second.client.await(Envelope.TRIGGERS)).single { it.id == triggerId }
            assertEquals("tripped", reloaded.state)
            assertTrue(reloaded.trippedReason!!.contains("restarted"), reloaded.trippedReason)
            assertTrue(second.daemon.attention.list().any { it.kind == AttentionKind.TRIGGER_TRIPPED && it.triggerId == triggerId })
        } finally {
            second.stop()
            second.deleteState()
        }
    }

    @Test
    fun boardDoneMoveFiresShipTrigger() = withDaemon(StubScript.default()) { fixture ->
        val ship = save(fixture, """{"name":"ship","kind":"ship","coalesceMs":0,"action":{"kind":"start_next"}}""")
        val ticket = fixture.client.saveTicket("""{"title":"Ship me"}""")

        // A move INTO done fires the ship trigger once. Nothing is left to start, so the event settles as nothing_to_start.
        fixture.client.moveTicket(ticket.id, "done")
        fixture.await(10_000, "the ship event for the done move") { events(fixture, ship.id).map { it.disposition } == listOf("nothing_to_start") }
        assertEquals(listOf("ship"), events(fixture, ship.id).map { it.source })

        // Re-committing the same done ticket is no transition: no second event. Leaving done for todo is no event either.
        fixture.client.moveTicket(ticket.id, "done")
        fixture.client.moveTicket(ticket.id, "todo")
        assertEquals(listOf("ship"), events(fixture, ship.id).map { it.source }, "one ship event in all: no double fire, no fire on leaving done")
    }

    // --- P9.5d: the `prompt` action (contract 1.41.0) ---------------------------------------------------

    private val promptText = "Summarise yesterday's merged work. MARKER-95D"
    private val promptBody = """{"name":"digest","coalesceMs":0,"action":{"kind":"prompt","agentId":"backend","prompt":"$promptText"}}"""

    /** The `action` object of a trigger frame's raw JSON (TriggerView is decoded loosely elsewhere in this file). */
    private fun actionOf(raw: JsonElement): JsonObject = raw.jsonObject.getValue("action").jsonObject

    private fun refusalOf(fixture: DaemonFixture, body: String): String {
        val mark = fixture.client.mark()
        fixture.send(Envelope(Envelope.TRIGGER_SAVE, body))
        val frame = fixture.client.awaitAfter(mark, "the refusal") { it.type == Envelope.ERROR || it.type == Envelope.TRIGGER }
        assertEquals(Envelope.ERROR, frame.type, "must be refused, not saved: ${frame.data}")
        return frame.data
    }

    @Test
    fun aPromptActionWithoutAnAgentIsRefused() = withDaemon(StubScript.default()) { fixture ->
        val noAgent = refusalOf(fixture, """{"name":"p","action":{"kind":"prompt","prompt":"do it"}}""")
        assertTrue(noAgent.startsWith("trigger_save refused: ") && noAgent.contains("agentId"), noAgent)
        val blankAgent = refusalOf(fixture, """{"name":"p","action":{"kind":"prompt","agentId":"   ","prompt":"do it"}}""")
        assertTrue(blankAgent.contains("agentId"), blankAgent)
    }

    @Test
    fun aPromptActionNeedsANonBlankPromptOfAtMost4000Characters() = withDaemon(StubScript.default()) { fixture ->
        val missing = refusalOf(fixture, """{"name":"p","action":{"kind":"prompt","agentId":"backend"}}""")
        assertTrue(missing.contains("prompt"), missing)
        val blank = refusalOf(fixture, """{"name":"p","action":{"kind":"prompt","agentId":"backend","prompt":"  \n "}}""")
        assertTrue(blank.contains("prompt"), blank)
        val tooLong = refusalOf(fixture, """{"name":"p","action":{"kind":"prompt","agentId":"backend","prompt":"${"x".repeat(4001)}"}}""")
        assertTrue(tooLong.contains("4000"), tooLong)
        // The boundary itself is accepted.
        val saved = save(fixture, """{"name":"p","action":{"kind":"prompt","agentId":"backend","prompt":"${"x".repeat(4000)}"}}""")
        assertEquals("prompt", saved.action.kind)
    }

    @Test
    fun anUnknownActionKindIsRefusedNamingItNeverIgnored() = withDaemon(StubScript.default()) { fixture ->
        val refused = refusalOf(fixture, """{"name":"p","action":{"kind":"teleport","agentId":"backend"}}""")
        assertTrue(refused.startsWith("trigger_save refused: ") && refused.contains("teleport"), refused)
        assertTrue(fixture.daemon.triggers.list().isEmpty(), "a refused save stores nothing")
    }

    @Test
    fun aStartNextActionCarryingAPromptIsRefusedNotSilentlyDropped() = withDaemon(StubScript.default()) { fixture ->
        val refused = refusalOf(fixture, """{"name":"p","action":{"kind":"start_next","prompt":"stray"}}""")
        assertTrue(refused.contains("prompt"), refused)
    }

    @Test
    fun firingAPromptTriggerStartsASessionForThatAgentWhoseFirstMessageIsThePrompt() {
        val gated = GatedRunner()
        // FakeTicketContext would REPLACE a composed ticket prompt with a fixed string: a prompt action that
        // went through the ticket composer would lose its text, and this test would say so.
        val fixture = DaemonFixture.launch(runner = gated, context = FakeTicketContext())
        try {
            fixture.client.await(Envelope.READY)
            fixture.client.saveProfile("""{"id":"backend","name":"Backend","role":"backend"}""")
            val mark = fixture.client.mark()
            fixture.send(Envelope(Envelope.TRIGGER_SAVE, promptBody))
            val savedFrame = fixture.client.awaitAfter(mark, "the saved trigger") { it.type == Envelope.TRIGGER }
            val trigger = trigger(savedFrame)
            assertEquals(promptText, actionOf(json.parseToJsonElement(savedFrame.data)).getValue("prompt").jsonPrimitive.content, "the saved trigger echoes its prompt")

            fixture.send(Envelope(Envelope.TRIGGER_FIRE, trigger.id))
            fixture.await(15_000, "the prompt delivered to the agent's session") { gated.prompts.isNotEmpty() }

            val delivered = gated.prompts.first()
            assertTrue(delivered.contains(promptText), "the session's first message must contain the prompt, was: $delivered")
            val session = fixture.daemon.manager.list().single()
            assertEquals("backend", session.agentId, "the session belongs to the action's agent")
            assertNull(session.ticketId, "a prompt action has no ticket")
            assertTrue(session.unattended, "a trigger's run is unattended")
            fixture.await(10_000, "the started disposition") { events(fixture, trigger.id).map { it.disposition } == listOf("started") }
            fixture.await(10_000, "re-armed once the prompt run ended") { state(fixture, trigger.id) == "armed" }
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun aPromptTriggerForAnUnknownAgentTripsAndStartsNothing() = withDaemon(StubScript.default()) { fixture ->
        val trigger = save(fixture, promptBody) // no profile "backend" exists
        fixture.send(Envelope(Envelope.TRIGGER_FIRE, trigger.id))
        fixture.await(10_000, "the trigger tripped") { state(fixture, trigger.id) == "tripped" }
        assertTrue(fixture.daemon.manager.list().isEmpty(), "a refused prompt launch creates no session")
        assertTrue(fixture.daemon.attention.list().any { it.kind == AttentionKind.TRIGGER_TRIPPED && it.triggerId == trigger.id })
    }

    @Test
    fun aPromptActionSurvivesADaemonRestart() {
        val state = Files.createTempDirectory("agenthq-trigger-prompt").toFile()
        val first = DaemonFixture.launch(StubScript.default(), stateDir = state)
        val id: String
        try {
            first.client.await(Envelope.READY)
            id = save(first, promptBody).id
        } finally {
            first.stop()
        }
        val second = DaemonFixture.launch(StubScript.default(), stateDir = state)
        try {
            second.client.await(Envelope.READY)
            second.send(Envelope(Envelope.TRIGGER_LIST))
            val raw = second.client.await(Envelope.TRIGGERS).data
            val mine = json.parseToJsonElement(raw).jsonArray.map { it.jsonObject }.single { it.getValue("id").jsonPrimitive.content == id }
            val action = mine.getValue("action").jsonObject
            assertEquals("prompt", action.getValue("kind").jsonPrimitive.content)
            assertEquals("backend", action.getValue("agentId").jsonPrimitive.content)
            assertEquals(promptText, action["prompt"]?.jsonPrimitive?.content, "the prompt is durable: $raw")
        } finally {
            second.stop()
            second.deleteState()
        }
    }

    private fun save(fixture: DaemonFixture, body: String): TriggerView {
        val mark = fixture.client.frames.size
        fixture.send(Envelope(Envelope.TRIGGER_SAVE, body))
        return trigger(fixture.client.awaitAfter(mark, "the saved trigger") { it.type == Envelope.TRIGGER })
    }

    private fun state(fixture: DaemonFixture, id: String): String {
        val mark = fixture.client.frames.size
        fixture.send(Envelope(Envelope.TRIGGER_LIST))
        return triggers(fixture.client.awaitAfter(mark, "the trigger list") { it.type == Envelope.TRIGGERS }).single { it.id == id }.state
    }

    private fun events(fixture: DaemonFixture, id: String): List<TriggerEventView> {
        val mark = fixture.client.frames.size
        fixture.send(Envelope(Envelope.TRIGGER_EVENTS, id))
        val frame = fixture.client.awaitAfter(mark, "the trigger events") { it.type == Envelope.TRIGGER_EVENTS }
        return json.decodeFromString(ListSerializer(TriggerEventView.serializer()), frame.data)
    }

    private fun trigger(frame: Envelope) = json.decodeFromString(TriggerView.serializer(), frame.data)
    private fun triggers(frame: Envelope) = json.decodeFromString(ListSerializer(TriggerView.serializer()), frame.data)

    private fun withDaemon(script: StubScript, block: (DaemonFixture) -> Unit) {
        val fixture = DaemonFixture.launch(script, runner = ScriptedRunner(script, 0))
        try {
            fixture.client.await(Envelope.READY)
            block(fixture)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
