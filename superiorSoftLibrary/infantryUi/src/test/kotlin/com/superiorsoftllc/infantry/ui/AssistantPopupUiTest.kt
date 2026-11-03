// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.approvals.*
import com.superiorsoftllc.infantry.plugins.official.assistant.*
import com.superiorsoftllc.infantry.plugins.official.changes.*
import com.superiorsoftllc.infantry.plugins.official.chat.*
import com.superiorsoftllc.infantry.plugins.official.chatfeed.*

import com.superiorsoftllc.infantry.plugins.official.header.*

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.daemon.InfantryDaemon
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.provider.HarnessModelCatalogs
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.stories.JsonStoryStore
import com.superiorsoftllc.infantry.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ASSISTANT-1: in the REAL window against a REAL in-process daemon. The Assistant is the last portrait of the team bar; a
 * tap opens a popup that hosts the SAME ChatPanel the main view uses; closing it keeps the draft; its role chip is fixed
 * while the model stays free; and it is one global session that no project switch hides.
 */
@OptIn(ExperimentalTestApi::class)
class AssistantPopupUiTest {
    private val daemonWaitMs = System.getenv("INFANTRY_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: 60_000L

    private fun bootDaemon(closers: MutableList<() -> Unit>): Int {
        val dir = Files.createTempDirectory("infantry-ui-assistant").toFile()
        val provider = StubProvider(StubScript.default(), delayMs = 0)
        val daemon = InfantryDaemon.build(
            memory = FixedMemory,
            port = 0, host = "127.0.0.1", provider = provider, runner = provider.transport()!!,
            sessionStore = JsonSessionStore(File(dir, "sessions.json")),
            mailStore = JsonMailStore(File(dir, "mailbox.json")),
            store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath),
            mailBaseUrl = "http://127.0.0.1:1", token = null,
            storyStore = JsonStoryStore(File(dir, "stories.json")),
            modelCatalogs = HarnessModelCatalogs.EMPTY,
            assistantDir = File(dir, "assistant"), // never the operator's ~/.infantry
        )
        daemon.server.start(wait = false)
        closers += { daemon.server.stop(0, 200); daemon.close(); dir.deleteRecursively() }
        return runBlocking {
            repeat(250) {
                daemon.server.engine.resolvedConnectors().firstOrNull()?.port?.takeIf { it > 0 }?.let { return@runBlocking it }
                delay(20)
            }
            error("no port")
        }
    }

    private fun withApp(body: androidx.compose.ui.test.ComposeUiTest.(Controller) -> Unit) {
        val closers = mutableListOf<() -> Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val port = bootDaemon(closers)
            val controller = Controller(scope)
            runDesktopComposeUiTest(1700, 1000) {
                setContent { App(controller, "ws://127.0.0.1:$port/ws", null) }
                waitUntil(timeoutMillis = daemonWaitMs) {
                    val s = controller.state.value
                    s.connection.isLive && s.toolApproval != null && s.providerStates.isNotEmpty()
                }
                body(controller)
            }
        } finally {
            scope.cancel()
            closers.reversed().forEach { runCatching { it() } }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.popupBox() =
        onNode(hasTestTag(PROMPT_BOX_TAG) and hasAnyAncestor(hasTestTag(ASSISTANT_POPUP_TAG)))

    private fun androidx.compose.ui.test.ComposeUiTest.mainBox() =
        onNode(hasTestTag(PROMPT_BOX_TAG) and hasAnyAncestor(hasTestTag(CENTER_PANE_TAG)))

    private fun androidx.compose.ui.test.ComposeUiTest.open(controller: Controller) {
        onNodeWithTag(TITLE_BAR_ASSISTANT_TAG).performClick()
        waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.assistant != null }
        waitUntil(timeoutMillis = daemonWaitMs) { popupBoxExists() }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.popupBoxExists(): Boolean = runCatching { popupBox().assertExists() }.isSuccess

    @Test fun `the assistant is the last portrait, opens a popup with the same chat and is one global projectless session`() = withApp { controller ->
        onNodeWithTag(TITLE_BAR_ASSISTANT_TAG).assertExists()
        onNodeWithTag(ASSISTANT_POPUP_TAG).assertDoesNotExist()
        open(controller)
        val a = controller.state.value.assistant!!
        assertEquals("assistant", a.session.role)
        assertTrue(a.session.global)
        assertNull(a.session.projectId, "the Assistant belongs to no project")
        // One chat component, two hosts: the main view's composer and the popup's composer are both the ChatPanel's.
        mainBox().assertExists()
        popupBox().assertExists()
        assertEquals(2, onAllNodesWithTag(PROMPT_BOX_TAG).fetchSemanticsNodes().size)
        // Not an ordinary agent: never in the sidebar list nor the main selection.
        onNodeWithTag("agent-row-${a.id}").assertDoesNotExist()
        assertTrue(controller.state.value.selectedId != a.id, "selected=${controller.state.value.selectedId} assistant=${a.id} agents=${controller.state.value.agents.keys}")
    }

    @Test fun `closing and reopening the popup keeps the draft`() = withApp { controller ->
        open(controller)
        popupBox().performTextInput("remember me")
        waitForIdle()
        onNodeWithTag(ASSISTANT_CLOSE_TAG).performClick()
        waitForIdle()
        onNodeWithTag(ASSISTANT_POPUP_TAG).assertDoesNotExist()
        onNodeWithTag(TITLE_BAR_ASSISTANT_TAG).performClick()
        waitUntil(timeoutMillis = daemonWaitMs) { popupBoxExists() }
        assertEquals("remember me", popupBox().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("", mainBox().fetchSemanticsNode().config[SemanticsProperties.EditableText].text, "the main draft is not the popup's")
    }

    @Test fun `the role is locked while the model picker stays open`() = withApp { controller ->
        open(controller)
        onNode(hasTestTag(EXECUTOR_SWITCH_CHIP_TAG) and hasAnyAncestor(hasTestTag(ASSISTANT_POPUP_TAG))).performClick()
        waitUntil(timeoutMillis = daemonWaitMs) { runCatching { onNodeWithTag(SWITCH_AGENT_PICKER_TAG).assertExists() }.isSuccess }
        onNodeWithTag(SWITCH_ROLE_FIXED_TAG).assertExists()
        onNodeWithTag(SWITCH_ROLE_TAG_PREFIX + "backend").assertDoesNotExist()
        onNodeWithTag(SWITCH_ROLE_TAG_PREFIX + "assistant").assertExists()
        onNodeWithTag(EXECUTOR_SWITCH_APPLY_TAG).assertExists()
    }

    @Test fun `the assistant stays reachable across project switches`() = withApp { controller ->
        fun create(name: String) {
            onNodeWithTag(NEW_PROJECT_BUTTON_TAG).performClick()
            onNodeWithTag(NEW_PROJECT_NAME_TAG).performTextInput(name)
            onNodeWithTag(NEW_PROJECT_CREATE_TAG).performClick()
            waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.projects.any { it.name == name } }
        }
        fun pick(id: String) {
            onNodeWithTag(PROJECT_PICKER_TAG).performClick()
            onNodeWithTag(PROJECT_OPTION_TAG_PREFIX + id).performClick()
            waitUntil(timeoutMillis = daemonWaitMs) { controller.state.value.activeProjectId == id }
            waitForIdle()
        }
        create("Alpha")
        create("Beta")
        val alpha = controller.state.value.projects.first { it.name == "Alpha" }.id
        val beta = controller.state.value.projects.first { it.name == "Beta" }.id
        pick(alpha)
        open(controller)
        val id = controller.state.value.assistant!!.id
        pick(beta)
        onNodeWithTag(TITLE_BAR_ASSISTANT_TAG).assertExists()
        onNodeWithTag(ASSISTANT_POPUP_TAG).assertExists()
        assertEquals(id, controller.state.value.assistant?.id, "the same session under another project")
        pick(alpha)
        assertEquals(id, controller.state.value.assistant?.id)
        assertFalse(controller.state.value.orderedAgents.any { it.id == id })
    }
}
