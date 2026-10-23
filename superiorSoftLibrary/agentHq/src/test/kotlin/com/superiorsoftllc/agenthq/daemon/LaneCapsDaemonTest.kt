package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.orchestration.GatedRunner
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Project
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** LANE-CAPS (contract 1.111.0): the per-project, per-role cap on concurrent agents, over a real daemon and WebSocket. */
class LaneCapsDaemonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun launch(state: File = Files.createTempDirectory("agenthq-lanecaps").toFile()): DaemonFixture {
        val fixture = DaemonFixture.launch(runner = GatedRunner(), context = FakeTicketContext("HANG"), stateDir = state)
        fixture.client.await(Envelope.READY)
        return fixture
    }

    /** The first `project` frame carrying exactly [caps] (a set answers the sender AND is pushed to every client). */
    private fun DaemonFixture.projectWith(caps: Map<String, Int>): Project =
        json.decodeFromString(
            Project.serializer(),
            client.awaitWhere("project with caps $caps") { e ->
                e.type == Envelope.PROJECT && json.decodeFromString(Project.serializer(), e.data).laneCaps == caps
            }.data,
        )

    private fun DaemonFixture.createProject(): String {
        send(Envelope.PROJECT_SAVE, """{"name":"P"}""")
        return projectWith(emptyMap()).id
    }

    private fun DaemonFixture.start(title: String, projectId: String) =
        client.saveTicket("""{"title":"$title","agentId":"role:backend","projectId":"$projectId"}""").let {
            client.moveTicket(it.id, TicketStatus.IN_PROGRESS)
        }

    private fun DaemonFixture.activeAgents() = daemon.manager.list().mapNotNull { it.agentId }.toSet()

    private fun <T> DaemonFixture.use(block: DaemonFixture.() -> T): T = try { block() } finally { stop(); deleteState() }

    @Test
    fun capOneQueuesTheSecondTicket() = launch().use {
        val p = createProject()
                assertTrue(start("A", p).start!!.ok)
        assertEquals("queued", start("B", p).start!!.reason)
        assertEquals(1, activeAgents().size)
    }

    @Test
    fun capThreeStartsThreeAtOnceWithDistinctAgentsThenQueues() = launch().use {
        val p = createProject()
        send(Envelope.PROJECT_CONFIG, """{"projectId":"$p","laneCaps":{"backend":3}}""")
        projectWith(mapOf("backend" to 3))
        repeat(3) { assertTrue(start("T$it", p).start!!.ok, "ticket $it starts") }
        assertEquals(3, activeAgents().size, "three distinct agents")
        assertEquals("queued", start("T3", p).start!!.reason, "the fourth queues at the cap")
        assertEquals(3, activeAgents().size)
    }

    @Test
    fun loweringTheCapKeepsRunningAgentsAndBlocksNewOnes() = launch().use {
        val p = createProject()
        send(Envelope.PROJECT_CONFIG, """{"projectId":"$p","laneCaps":{"backend":3}}""")
        projectWith(mapOf("backend" to 3))
        repeat(3) { start("T$it", p) }
        send(Envelope.PROJECT_CONFIG, """{"projectId":"$p","laneCaps":{"backend":1}}""")
        // create answer + (set 3: answer + push) + (set 1: answer + push)
        await(what = "the lowering answered and pushed") { client.of(Envelope.PROJECT).size >= 5 }
        assertEquals(3, activeAgents().size, "running agents are untouched")
        assertEquals("queued", start("T3", p).start!!.reason)
    }

    @Test
    fun idleClonesAboveALoweredCapAreRemovedWhenTheirRunsEnd() = launch().use {
        val p = createProject()
        send(Envelope.PROJECT_CONFIG, """{"projectId":"$p","laneCaps":{"backend":3}}""")
        projectWith(mapOf("backend" to 3))
        val sessions = (0 until 3).map { start("T$it", p).sessionId!! }
        assertEquals(3, activeAgents().size)
        send(Envelope.PROJECT_CONFIG, """{"projectId":"$p","laneCaps":{"backend":1}}""")
        sessions.forEach { send(Envelope.KILL, "$it force") }
        await(what = "the clones are pruned back to the cap") {
            daemon.agents.list().count { it.role == "backend" } == 1
        }
    }

    @Test
    fun theCapSurvivesADaemonRestart() {
        val state = Files.createTempDirectory("agenthq-lanecaps-restart").toFile()
        val first = launch(state)
        val p = with(first) {
            val id = createProject()
            send(Envelope.PROJECT_CONFIG, """{"projectId":"$id","laneCaps":{"backend":3}}""")
            projectWith(mapOf("backend" to 3))
            id
        }
        first.stop() // the state directory is kept: the second daemon reopens its database
        val second = DaemonFixture.launch(runner = GatedRunner(), context = FakeTicketContext("HANG"), stateDir = state)
        second.use {
            client.await(Envelope.READY)
            send(Envelope.PROJECT_CONFIG, """{"projectId":"$p"}""")
            projectWith(mapOf("backend" to 3))
            repeat(3) { assertTrue(start("T$it", p).start!!.ok) }
        }
        state.deleteRecursively()
    }
}
