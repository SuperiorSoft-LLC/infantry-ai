// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.plugin.api.PolicyWire
import com.superiorsoftllc.infantry.plugins.official.attention.InfantryAttention
import com.superiorsoftllc.infantry.plugins.official.attention.QUESTION_HISTORY_DISMISS_TAG_PREFIX
import com.superiorsoftllc.infantry.plugins.official.board.TICKET_HANDLE_TAG_PREFIX
import com.superiorsoftllc.infantry.plugins.official.chatfeed.RESUME_SESSION_TAG
import com.superiorsoftllc.infantry.plugins.official.chatfeed.SessionStartPane
import com.superiorsoftllc.infantry.plugins.official.header.EDIT_PROJECT_BUTTON_TAG
import com.superiorsoftllc.infantry.plugins.official.header.PROJECT_CONFIG_DIALOG_TAG
import com.superiorsoftllc.infantry.plugins.official.header.PROJECT_DIRECTORY_CLEAR_TAG
import com.superiorsoftllc.infantry.plugins.official.header.ProjectPicker
import com.superiorsoftllc.infantry.plugins.official.notes.MEMORY_ADD_NOTE_BUTTON_TAG
import com.superiorsoftllc.infantry.plugins.official.run.RUN_ADD_TAG
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ProjectView
import com.superiorsoftllc.infantry.ui.model.SessionPaneAction
import com.superiorsoftllc.infantry.ui.model.ToastEvent
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.net.Controller
import com.superiorsoftllc.infantry.ui.plugin.TabContext
import com.superiorsoftllc.infantry.ui.protocol.AttentionItem
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.OwnerQuestionView
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import com.superiorsoftllc.infantry.ui.protocol.TicketColumns
import com.superiorsoftllc.infantry.ui.shell.BoardContext
import com.superiorsoftllc.infantry.ui.shell.CoreGit
import com.superiorsoftllc.infantry.ui.shell.CoreLink
import com.superiorsoftllc.infantry.ui.shell.CoreRun
import com.superiorsoftllc.infantry.ui.shell.CoreSession
import com.superiorsoftllc.infantry.ui.shell.EmptyConfig
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import com.superiorsoftllc.infantry.ui.shell.NoRoles
import com.superiorsoftllc.infantry.ui.shell.ShellContext
import com.superiorsoftllc.infantry.ui.shell.ToastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test

/**
 * GATE-PANES-1 (docs/GAP-AUDIT.md gap 2e): one write control per pane group (Notes, Attention, Chat, Header, Board, Run),
 * drawn through its real pane over a live link. Under production it is enabled; the moment the daemon's policy turns
 * writes off (a read-only preset: `*` off, `kind:read` on) the same control is dimmed, rather than answering with an
 * error. The policy reaches each pane the way the window's does: `state.connection.policy`, and the shell context's link
 * ([CoreLink]) for a control that asks `rememberGate`.
 */
@OptIn(ExperimentalTestApi::class)
class GatePanesUiTest {
    private val readOnly = PolicyWire(
        preset = "read-only", label = "Read only", disable = listOf("*"), allow = listOf("kind:read"), reason = "Read-only deployment",
    )
    private val controller = Controller(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
    private val project = ProjectView("p1", "Acme", directory = "/work/acme")

    /** A connected window state (contract recent enough for every pane) with [policy] as the daemon's active policy. */
    private fun live(policy: PolicyWire, base: AppState = AppState()): AppState {
        val ready = reduce(base, Envelope(Envelope.READY, "provider=stub contract=1.200.0 instance=A since=0"), 0)
        return ready.copy(connection = ready.connection.copy(policy = policy), projects = listOf(project), activeProjectId = project.id)
    }

    /** The shell context the window provides, over [state]: what `rememberGate` reads the link (and its policy) from. */
    private fun shellOf(state: AppState): ShellContext = object : ShellContext {
        override val link = CoreLink(state)
        override val session = CoreSession(state)
        override val git = CoreGit(state, controller)
        override val run = CoreRun(state, controller, null)
        override val board = object : BoardContext {
            override val open = false
            override fun close() {}
            override fun openAgent(sessionId: String) {}
        }
        override val roles = NoRoles
        override val toast = object : ToastContext {
            override val service = NoToastService
            override val events = emptyList<ToastEvent>()
            override fun open(event: ToastEvent) {}
        }
        override val config = EmptyConfig
    }

    /**
     * Draws [pane] over a live state, production first: [check] passes with every control enabled; then the policy turns
     * read-only and [check] runs again, expecting them dimmed. [prepare] runs between the first draw and the switch.
     */
    private fun productionThenReadOnly(
        base: AppState = AppState(),
        prepare: ComposeUiTest.() -> Unit = {},
        pane: @Composable (AppState) -> Unit,
        check: ComposeUiTest.(enabled: Boolean) -> Unit,
    ) = runDesktopComposeUiTest(1200, 900) {
        var policy by mutableStateOf(PolicyWire.Production)
        setContent {
            val state = live(policy, base)
            CompositionLocalProvider(LocalShellContext provides shellOf(state)) { pane(state) }
        }
        prepare()
        check(true)
        policy = readOnly
        waitForIdle()
        check(false)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.enabled(on: Boolean) = if (on) assertIsEnabled() else assertIsNotEnabled()

    @Test fun `Notes - Add note dims under a read-only policy`() = productionThenReadOnly(
        pane = { MemoryPane(it, controller) },
    ) { on -> onNodeWithTag(MEMORY_ADD_NOTE_BUTTON_TAG).enabled(on) }

    @Test fun `Attention - Acknowledge and Dismiss dim under a read-only policy`() = productionThenReadOnly(
        base = AppState(
            attention = listOf(AttentionItem("a1", at = 1, kind = "stall", outcome = "stalled", detail = "a run stalled")),
            questions = listOf(OwnerQuestionView("q1", sessionId = "s1", question = "Which way?", recommended = "left")),
        ),
        pane = { state ->
            InfantryAttention.tabs.first().content(
                TabContext(state, controller, open = {}, showChat = {}, insertIntoChat = {}, diffTarget = null, onOpenDiff = {}, transcript = {}),
            )
        },
    ) { on ->
        onNodeWithText("Acknowledge").enabled(on)
        onNodeWithTag(QUESTION_HISTORY_DISMISS_TAG_PREFIX + "q1").enabled(on)
    }

    @Test fun `Chat - Resume dims under a read-only policy`() = productionThenReadOnly(
        pane = { SessionStartPane(SessionPaneAction.Resume("s1"), onApply = {}, onResume = {}) },
    ) { on -> onNodeWithTag(RESUME_SESSION_TAG).enabled(on) }

    @Test fun `Header - the open project dialog's directory control dims when the policy turns read-only`() = productionThenReadOnly(
        prepare = { onNodeWithTag(EDIT_PROJECT_BUTTON_TAG).performClick(); onNodeWithTag(PROJECT_CONFIG_DIALOG_TAG).assertExists() },
        pane = { ProjectPicker(it, controller) },
    ) { on -> onNodeWithTag(PROJECT_DIRECTORY_CLEAR_TAG).enabled(on) }

    @Test fun `Board - the drag handle stops offering moves under a read-only policy`() = productionThenReadOnly(
        base = AppState(tickets = listOf(Ticket(id = "t1", projectId = "p1", title = "T1", status = TicketColumns.TODO, position = 0))),
        pane = { BoardPane(it, controller, onOpenAgent = {}) },
    ) { on ->
        val cursor = SemanticsMatcher("hover cursor") { node -> node.config.any { (k, _) -> k.name == "HoverCursor" } }
        onNodeWithTag(TICKET_HANDLE_TAG_PREFIX + "t1", useUnmergedTree = true).assert(if (on) cursor else !cursor)
    }

    @Test fun `Run - Add command dims under a read-only policy`() = productionThenReadOnly(
        pane = { RunPane(it, controller) },
    ) { on -> onNodeWithTag(RUN_ADD_TAG).enabled(on) }
}
