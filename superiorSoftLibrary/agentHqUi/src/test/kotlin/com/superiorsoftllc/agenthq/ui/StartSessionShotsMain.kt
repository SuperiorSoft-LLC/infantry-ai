package com.superiorsoftllc.agenthq.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.backlog.TicketBoard
import com.superiorsoftllc.agenthq.daemon.AgentHqDaemon
import com.superiorsoftllc.agenthq.mailbox.JsonMailStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.system.exitProcess

/**
 * GAP-006b dev tool: the whole window against an in-process stub daemon (temp state) seeded with a phantom ticket worker
 * (IDLE, one run, no provider session id: the 9849e22a shape) and a finished session that has a conversation, then one
 * PNG each of the Start session pane, its Apply / Cancel card and the Resume pane. Args: output folder.
 */
@OptIn(ExperimentalTestApi::class)
fun main(args: Array<String>) {
    val out = File(args.firstOrNull() ?: "build/start-session-shots").apply { mkdirs() }
    val dir = Files.createTempDirectory("agenthq-start-shots").toFile()
    val provider = StubProvider(StubScript.default(), delayMs = 0)
    val store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath)
    val ticket = TicketBoard(store, idFactory = { "ticket-e13c0aa3" })
        .save(TicketSave(title = "Fix the board's drag and drop", agentId = "role:backend")).ticket
    val worktree = File(dir, "worktrees/ticket-e13c0aa3").apply { mkdirs() }
    fun session(id: String, state: SessionState, psid: String?, ticketId: String?, at: Long) = SessionRecord(
        id = id, provider = provider.id, workingDir = worktree.path, spawnedAt = at, lastHeartbeat = at, state = state,
        providerSessionId = psid, runCount = 1, role = "backend", model = "claude-sonnet-5-5", ticketId = ticketId,
        worktreePath = worktree.path, worktreeBranch = ticketId?.let { "ticket/e13c0aa3-fix-the-board" },
    )
    val now = System.currentTimeMillis()
    JsonSessionStore(File(dir, "sessions.json")).saveAll(
        listOf(
            session("9849e22a-phantom", SessionState.IDLE, psid = null, ticketId = ticket.id, at = now - 60_000),
            session("f84ba8f6-finished", SessionState.EXITED, psid = "prov-f84ba8f6", ticketId = null, at = now - 30_000),
        ),
    )
    val daemon = AgentHqDaemon.build(
        memory = FixedMemory,
        port = 18798, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
        sessionStore = JsonSessionStore(File(dir, "sessions.json")), mailStore = JsonMailStore(File(dir, "mailbox.json")),
        store = store, mailBaseUrl = "http://127.0.0.1:1", token = null,
    )
    daemon.start()
    val controller = Controller(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    runDesktopComposeUiTest(1400, 860) {
        setContent { App(controller, "ws://127.0.0.1:18798/ws", null) }
        waitUntil(timeoutMillis = 20_000) { controller.state.value.connection.isLive && controller.state.value.agents.size >= 2 }
        fun shot(name: String) {
            waitForIdle()
            Thread.sleep(400)
            waitForIdle()
            val roots = onAllNodes(isRoot())
            ImageIO.write(roots[roots.fetchSemanticsNodes().size - 1].captureToImage().toAwtImage(), "png", File(out, "$name.png"))
            println("shot ${File(out, "$name.png").path}")
        }
        controller.select("9849e22a-phantom")
        waitUntil(timeoutMillis = 10_000) { onAllNodes(androidx.compose.ui.test.hasTestTag(START_SESSION_TAG)).fetchSemanticsNodes().isNotEmpty() }
        shot("1-start-session-pane")
        onNodeWithTag(START_SESSION_TAG).performClick()
        shot("2-start-card-apply-cancel")
        onNodeWithTag(START_CANCEL_TAG).performClick()
        shot("3-after-cancel")
        controller.select("f84ba8f6-finished")
        waitUntil(timeoutMillis = 10_000) { onAllNodes(androidx.compose.ui.test.hasTestTag(RESUME_SESSION_TAG)).fetchSemanticsNodes().isNotEmpty() }
        shot("4-resume-pane")
        // Apply on the phantom: its ticket starts on the stub harness and the pane follows the ticket's new worker.
        controller.select("9849e22a-phantom")
        waitUntil(timeoutMillis = 10_000) { onAllNodes(androidx.compose.ui.test.hasTestTag(START_SESSION_TAG)).fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag(START_SESSION_TAG).performClick()
        onNodeWithTag(START_APPLY_TAG).performClick()
        runCatching { waitUntil(timeoutMillis = 20_000) { controller.state.value.selectedId.let { it != null && it != "9849e22a-phantom" } } }
        Thread.sleep(1500)
        val t = controller.state.value.tickets.firstOrNull { it.id == ticket.id }
        println("after apply: selected=${controller.state.value.selectedId} ticket=${t?.status} session=${t?.sessionId} start=${t?.start}")
        shot("5-after-apply")
    }
    daemon.close()
    exitProcess(0)
}
