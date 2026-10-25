package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AgentTodoList
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.OutputLine
import com.superiorsoftllc.agenthq.ui.model.SidePanel
import com.superiorsoftllc.agenthq.ui.protocol.TodoItemPayload
import kotlin.test.Test
import kotlin.test.assertEquals

/** TODO-BOX-1: the to-do toggle next to the reasoning button, its checklist panel, and one panel open at a time. */
@OptIn(ExperimentalTestApi::class)
class TodoBoxUiTest {
    private val list = AgentTodoList.of(
        listOf(TodoItemPayload("one", "done"), TodoItemPayload("two", "in_progress", "Doing two"), TodoItemPayload("three", "pending")), 1,
    )
    private val reasoning = listOf(OutputLine(LineKind.REASONING, "weighing the options", 0))

    @Test
    fun toggleOpensTheChecklistAndTheTwoPanelsAreExclusive() {
        runDesktopComposeUiTest(1000, 800) {
            setContent {
                var panel by remember { mutableStateOf(SidePanel.NONE) }
                Box(Modifier.fillMaxSize()) { ChatSideSlot(reasoning, list, panel) { panel = it } }
            }
            onNodeWithTag(TODO_BUTTON_TAG).assertExists()
            onNodeWithText("☑ 1/3").assertExists()
            onNodeWithTag(REASONING_BUTTON_TAG).assertExists()
            onNodeWithTag(TODO_PANEL_TAG).assertDoesNotExist()

            onNodeWithTag(TODO_BUTTON_TAG).performClick()
            onNodeWithTag(TODO_PANEL_TAG).assertExists()
            assertEquals(3, onAllNodesWithTag(TODO_ITEM_TAG).fetchSemanticsNodes().size)
            onNodeWithText("Doing two").assertExists()

            // Opening reasoning closes the checklist, and back.
            onNodeWithTag(REASONING_BUTTON_TAG).performClick()
            onNodeWithTag(REASONING_PANEL_TAG).assertExists()
            onNodeWithTag(TODO_PANEL_TAG).assertDoesNotExist()
            onNodeWithTag(TODO_BUTTON_TAG).performClick()
            onNodeWithTag(TODO_PANEL_TAG).assertExists()
            onNodeWithTag(REASONING_PANEL_TAG).assertDoesNotExist()

            onNodeWithTag(TODO_PANEL_CLOSE_TAG).performClick()
            onNodeWithTag(TODO_PANEL_TAG).assertDoesNotExist()
            onNodeWithTag(TODO_BUTTON_TAG).performClick()
            onNodeWithTag(TODO_BUTTON_TAG).performClick()
            onNodeWithTag(TODO_PANEL_TAG).assertDoesNotExist()
        }
    }

    @Test
    fun noListNoToggle() {
        runDesktopComposeUiTest(800, 600) {
            setContent {
                Box(Modifier.fillMaxSize()) {
                    ChatSideSlot(reasoning, AgentTodoList(emptyList(), 1), SidePanel.NONE) {}
                }
            }
            onNodeWithTag(REASONING_BUTTON_TAG).assertExists()
            onNodeWithTag(TODO_BUTTON_TAG).assertDoesNotExist()
        }
        runDesktopComposeUiTest(800, 600) {
            setContent { Box(Modifier.fillMaxSize()) { ChatSideSlot(emptyList(), list, SidePanel.NONE) {} } }
            onNodeWithTag(TODO_BUTTON_TAG).assertExists()
            onNodeWithTag(REASONING_BUTTON_TAG).assertDoesNotExist()
        }
    }
}
