package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.net.DaemonClient
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.Project
import com.superiorsoftllc.agenthq.ui.protocol.Workspace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue

/** A daemon link with no network: records every frame the controller sends, and lets the test push frames in. */
private class FakeDaemonClient(scope: CoroutineScope) : DaemonClient(scope) {
    val sent = CopyOnWriteArrayList<Envelope>()
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    override val frames: SharedFlow<Envelope> get() = incoming
    override suspend fun send(envelope: Envelope): Boolean { sent += envelope; return true }
    /** A shared flow drops what nobody collects yet, so wait for the controller's collector before pushing. */
    fun push(e: Envelope) {
        val deadline = System.currentTimeMillis() + 10_000
        while (incoming.subscriptionCount.value == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        check(incoming.tryEmit(e)) { "frame buffer full" }
    }
    fun sentOf(type: String) = sent.filter { it.type == type }
}

/**
 * UI-SHELL-1 / M2-13: the sidebar's project slot, driven like a person drives it. The daemon side is a fake link
 * (frames pushed in, requests recorded), so the assertions are exactly "what the window asked" and "what it drew".
 * M2-13 removed the WORKSPACES block (the Git tab already shows the same thing); only the refusal notice is left.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectsSidebarUiTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun projects(vararg p: Project) = Envelope(Envelope.PROJECTS, json.encodeToString(ListSerializer(Project.serializer()), p.toList()))
    private fun workspaces(vararg w: Workspace) = Envelope(Envelope.WORKSPACES, json.encodeToString(ListSerializer(Workspace.serializer()), w.toList()))

    @Test fun `the sidebar draws no workspaces block, and the daemon project refusals still show inline`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = FakeDaemonClient(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(900, 900) {
                setContent {
                    val state by controller.state.collectAsState()
                    // M2-7: projects are picked / created from the title bar's picker.
                    androidx.compose.foundation.layout.Column {
                        ProjectPicker(state, controller)
                        ProjectsSidebar(state, onDismiss = { controller.dismissProjectRefusal() })
                    }
                }
                // Connect: `ready` makes the link live and the controller asks for the project list.
                link.push(Envelope(Envelope.READY, "contract=1.18.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_LIST).isNotEmpty() }

                // A project is active and the daemon answered with its workspaces: the sidebar still draws none of it.
                link.push(projects(Project("p-alpha", "Alpha"), Project("p-beta", "Beta")))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.projects.size == 2 }
                onNodeWithTag(PROJECT_PICKER_TAG).assertTextContains("Alpha", substring = true)
                link.push(workspaces(Workspace("w-a1", "p-alpha", "alpha-checkout")))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.workspaces.size == 1 }
                waitForIdle()
                onNodeWithText("WORKSPACES", substring = true).assertDoesNotExist()
                onNodeWithText("New workspace").assertDoesNotExist()
                onNodeWithText("Create workspace").assertDoesNotExist()
                onNodeWithText("alpha-checkout").assertDoesNotExist()

                // New Project (title-bar modal) still sends project_save.
                onNodeWithTag(NEW_PROJECT_BUTTON_TAG).performClick()
                onNodeWithTag(NEW_PROJECT_NAME_TAG).performTextInput("Gamma")
                onNodeWithTag(NEW_PROJECT_CREATE_TAG).performClick()
                waitUntil(timeoutMillis = 10_000) { link.sentOf(Envelope.PROJECT_SAVE).isNotEmpty() }
                assertTrue(link.sentOf(Envelope.PROJECT_SAVE).single().data.contains("\"Gamma\""), "project_save carries the typed name")

                // A refusal of the create shows inline in the New Project form, which stays open; the sidebar does
                // not repeat it, so the message is drawn exactly once.
                val gammaRefusal = "project_save refused: a project named Gamma already exists"
                link.push(Envelope(Envelope.ERROR, gammaRefusal))
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(gammaRefusal).fetchSemanticsNodes().isNotEmpty() }
                waitForIdle()
                onAllNodesWithText(gammaRefusal).assertCountEquals(1)
                onNode(hasText(gammaRefusal) and hasAnyAncestor(hasTestTag(NEW_PROJECT_DIALOG_TAG))).assertExists()
                onNodeWithTag(NEW_PROJECT_DIALOG_TAG).assertExists()
                onNodeWithText("Dismiss").assertDoesNotExist()

                // A project refusal nobody's form is waiting for still shows inline, in the sidebar.
                onNodeWithTag(NEW_PROJECT_CANCEL_TAG).performClick()
                val otherRefusal = "project_delete refused: unknown project"
                link.push(Envelope(Envelope.ERROR, otherRefusal))
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithText(otherRefusal).fetchSemanticsNodes().isNotEmpty() }
                onAllNodesWithText(otherRefusal).assertCountEquals(1)
                onNodeWithText("Dismiss").assertExists()
            }
        } finally {
            scope.cancel()
        }
    }
}
