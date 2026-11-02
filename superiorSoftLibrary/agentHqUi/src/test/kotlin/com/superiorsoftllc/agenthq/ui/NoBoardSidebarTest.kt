package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.plugins.official.agents.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.net.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test

/**
 * AR-7: the sidebar no longer draws a Board button of its own; Infantry_Board contributes it to the left.board slot.
 * With no Board contributor (no PanelSlot host, no provider) the sidebar draws, and there is no Board button.
 */
@OptIn(ExperimentalTestApi::class)
class NoBoardSidebarTest {
    @Test fun `the sidebar has no Board button when Infantry_Board contributes none`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val state = Controller(scope).state.value
            runDesktopComposeUiTest(600, 800) {
                setContent {
                    Sidebar(
                        state, emptyList(), "", {}, null, {}, FocusRequester(),
                        onSelect = {}, onNew = {}, onRemove = {}, onAssign = { _, _ -> }, projects = {},
                    )
                }
                onNodeWithTag(SIDEBAR_SEARCH_TAG).assertExists()
                onNodeWithTag("sidebar-board-button").assertDoesNotExist()
            }
        } finally { scope.cancel() }
    }
}
