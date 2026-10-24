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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * BASICS-KILL-1 (contract 1.118.0): `Project.autoRemoveDoneWorkers`, over a real daemon and WebSocket. A ticket moved to
 * done while its worker is mid-turn keeps the worker until that turn ends, then the session is gone; with the setting
 * off (the default) the finished worker stays listed. The setting survives a daemon restart (migration 47).
 */
class AutoRemoveDoneWorkersDaemonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Each worker turn runs [TURN_MS] then ends by itself, so a move to done can land mid-turn. */
    private fun launch(state: File = Files.createTempDirectory("agenthq-autoremove").toFile()): DaemonFixture =
        DaemonFixture.launch(runner = GatedRunner(holdMs = TURN_MS), context = FakeTicketContext("work"), stateDir = state)
            .also { it.client.await(Envelope.READY) }

    private fun DaemonFixture.project(predicate: (Project) -> Boolean): Project =
        json.decodeFromString(
            Project.serializer(),
            client.awaitWhere("a matching project frame") { e ->
                e.type == Envelope.PROJECT && predicate(json.decodeFromString(Project.serializer(), e.data))
            }.data,
        )

    private fun DaemonFixture.createProject(): String {
        send(Envelope.PROJECT_SAVE, """{"name":"P"}""")
        return project { true }.id
    }

    private fun DaemonFixture.autoRemove(projectId: String, on: Boolean) {
        send(Envelope.PROJECT_CONFIG, """{"projectId":"$projectId","autoRemoveDoneWorkers":$on}""")
        project { it.id == projectId && it.autoRemoveDoneWorkers == on }
    }

    private fun DaemonFixture.startWorker(projectId: String): String {
        val ticket = client.saveTicket("""{"title":"T","agentId":"role:backend","projectId":"$projectId"}""")
        val started = client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
        assertTrue(started.start!!.ok, "the ticket starts: ${started.start}")
        val session = started.sessionId!!
        await(what = "the worker to be mid-turn") { daemon.manager.get(session)?.live == true }
        return "${ticket.id} $session"
    }

    private fun <T> DaemonFixture.use(block: DaemonFixture.() -> T): T = try { block() } finally { stop(); deleteState() }

    @Test
    fun onADoneTicketsWorkerIsRemovedOnlyAfterItsTurnEnds(): Unit = launch().use {
        val p = createProject()
        autoRemove(p, true)
        val (ticketId, session) = startWorker(p).split(" ")

        client.moveTicket(ticketId, TicketStatus.DONE)
        // Mid-turn: the worker usually moves its own ticket and then mails its starter, so it is never cut off.
        Thread.sleep(200)
        assertNotNull(daemon.manager.get(session), "a worker mid-turn is not removed when its ticket moves to done")

        await(what = "the worker's session to be removed after its turn") { daemon.manager.get(session) == null }
    }

    @Test
    fun offIsTheDefaultAndTheFinishedWorkerStaysListed(): Unit = launch().use {
        val p = createProject()
        val (ticketId, session) = startWorker(p).split(" ")
        client.moveTicket(ticketId, TicketStatus.DONE)
        await(what = "the worker's turn to end") { daemon.manager.get(session)?.live == false }
        Thread.sleep(300)
        assertNotNull(daemon.manager.get(session), "without the setting a done ticket's worker stays listed")
    }

    @Test
    fun theSettingSurvivesADaemonRestart() {
        val state = Files.createTempDirectory("agenthq-autoremove-restart").toFile()
        val first = launch(state)
        val p = with(first) { createProject().also { autoRemove(it, true) } }
        first.stop() // the state directory is kept: the second daemon reopens its database
        launch(state).use {
            send(Envelope.PROJECT_CONFIG, """{"projectId":"$p"}""")
            assertEquals(true, project { it.id == p }.autoRemoveDoneWorkers)
        }
        state.deleteRecursively()
    }

    private companion object {
        const val TURN_MS = 1_500L
    }
}
