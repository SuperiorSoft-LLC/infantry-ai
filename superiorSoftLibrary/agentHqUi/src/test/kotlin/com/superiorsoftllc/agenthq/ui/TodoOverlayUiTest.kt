package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.AgentTodoList
import com.superiorsoftllc.agenthq.ui.model.TodoCollapseMemory
import com.superiorsoftllc.agenthq.ui.protocol.TodoItemPayload
import kotlin.test.Test
import kotlin.test.assertEquals

/** TODO-OVERLAY-1: the floating card, its header counter, the collapse to a pill and the empty state. */
@OptIn(ExperimentalTestApi::class)
class TodoOverlayUiTest {
    private val list = AgentTodoList.of(
        listOf(TodoItemPayload("one", "done"), TodoItemPayload("two", "in_progress", "Doing two"), TodoItemPayload("three", "pending")), 1,
    )

    @Test
    fun cardShowsItemsCollapsesToThePillAndReopens() {
        runDesktopComposeUiTest(1000, 800) {
            setContent {
                var memory by remember { mutableStateOf(TodoCollapseMemory()) }
                Box(Modifier.fillMaxSize()) { TodoOverlay("a", list, running = true, memory = memory, onMemory = { memory = it }) }
            }
            assertEquals(3, onAllNodesWithTag(TODO_ITEM_TAG).fetchSemanticsNodes().size)
            onNodeWithTag(TODO_HEADER_TAG).performClick()
            onNodeWithTag(TODO_PILL_TAG).assertExists()
            onNodeWithTag(TODO_CARD_TAG).assertDoesNotExist()
            onNodeWithTag(TODO_PILL_TAG).performClick()
            onNodeWithTag(TODO_CARD_TAG).assertExists()
        }
    }

    @Test
    fun nothingDrawnForAnEmptyListOrANonRunningSession() {
        runDesktopComposeUiTest(1000, 800) {
            setContent {
                Box(Modifier.fillMaxSize()) {
                    TodoOverlay("a", AgentTodoList(emptyList(), 1), true, TodoCollapseMemory()) {}
                    TodoOverlay("b", list, false, TodoCollapseMemory()) {}
                }
            }
            onNodeWithTag(TODO_CARD_TAG).assertDoesNotExist()
            onNodeWithTag(TODO_PILL_TAG).assertDoesNotExist()
        }
    }
}
