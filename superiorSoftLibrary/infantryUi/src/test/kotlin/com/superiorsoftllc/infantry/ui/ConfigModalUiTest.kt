// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.infantry.ui.model.AgentScope
import com.superiorsoftllc.infantry.ui.model.FLAGS_LIFETIME_NOTE
import com.superiorsoftllc.infantry.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test

/**
 * CONFIG flags surface: the footer cog opens the configuration modal; the agent-scope dropdown flips the
 * flag; the modal states the flag's effect on screen; the choice survives a close and a reopen within the
 * window. Every state is read off the composed window, then flipped back.
 */
@OptIn(ExperimentalTestApi::class)
class ConfigModalUiTest {
    @Test fun `the footer cog opens the modal, the agent-scope flag flips and states its effect, and the choice is remembered`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            // No daemon needed: the flag is local window state, so an unreachable URL renders the shell.
            runDesktopComposeUiTest(1700, 1000) { dumpTreeOnFailure {
                setContent { App(Controller(scope), "ws://127.0.0.1:1/ws", null) }

                // The footer (and its cog) is composed by the shell after its first frames; the click waits for it instead of
                // racing it (CONFIG-11: this was the intermittent "cannot find Configuration" at the first click).
                waitUntil(timeoutMillis = 10_000) { onAllNodesWithContentDescription("Configuration").fetchSemanticsNodes().isNotEmpty() }
                // Default (BUG-M2-15): Project Scoped Agents, with its effect stated.
                onNodeWithContentDescription("Configuration").performClick()
                // CONFIG-TABS-1: the modal opens on Appearance; agent scope lives under General.
                onNodeWithTag("agent-scope-field").assertDoesNotExist()
                onNodeWithTag(CONFIG_TAB_TAG_PREFIX + ConfigTab.GENERAL.name).performClick()
                onNodeWithTag("agent-scope-field").assertTextContains("Project Scoped Agents")
                onNodeWithText(AgentScope.PROJECT.effect).assertExists()
                // The modal must keep stating the flags' lifetime: it is the only thing telling the user
                // the settings are window-local and reset on restart. Asserted on the constant itself so a
                // silenced or reworded note goes RED instead of quietly leaving the modal lying.
                onNodeWithText(FLAGS_LIFETIME_NOTE).assertExists()

                // Flip to Global Agents: the field and the stated effect both change.
                onNodeWithTag("agent-scope-field").performClick()
                onNodeWithTag("agent-scope-option-GLOBAL").performClick()
                onNodeWithTag("agent-scope-field").assertTextContains("Global Agents")
                onNodeWithText(AgentScope.GLOBAL.effect).assertExists()
                onNodeWithText(AgentScope.PROJECT.effect).assertDoesNotExist()

                // Close, reopen: the choice is still there (remembered within the window), and so is the tab.
                onNodeWithText("Done").performClick()
                onNodeWithTag("agent-scope-field").assertDoesNotExist()
                onNodeWithContentDescription("Configuration").performClick()
                onNodeWithTag("agent-scope-field").assertTextContains("Global Agents")

                // Flip back to Project Scoped Agents.
                onNodeWithTag("agent-scope-field").performClick()
                onNodeWithTag("agent-scope-option-PROJECT").performClick()
                onNodeWithTag("agent-scope-field").assertTextContains("Project Scoped Agents")
                onNodeWithText(AgentScope.PROJECT.effect).assertExists()
            } }
        } finally {
            scope.cancel()
        }
    }

    /**
     * QA-FIX-K (ddf28acb): diagnostics only, no assertion changes. This test fails now and then in the full QA run with a
     * bare "AssertionError at SemanticsNodeInteraction.kt:178" (a lookup found 0 or 2+ nodes) and never alone. On a
     * failure the whole semantics tree (every root, unmerged) joins the original message, and goes to stderr, so the
     * next failure names the lookup and the window state behind it.
     */
    private fun ComposeUiTest.dumpTreeOnFailure(body: ComposeUiTest.() -> Unit) {
        try {
            body()
        } catch (e: AssertionError) {
            val tree = runCatching { onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE) }
                .getOrElse { "(tree unavailable: $it)" }
            val message = "${e.message}\n--- semantics tree at the failure (QA-FIX-K) ---\n$tree"
            System.err.println("ConfigModalUiTest failed: $message")
            throw AssertionError(message, e)
        }
    }
}
