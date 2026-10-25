package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.SidePanel
import com.superiorsoftllc.agenthq.ui.model.TodoStatus
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.model.todoBoxVisible
import com.superiorsoftllc.agenthq.ui.model.toggled
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import com.superiorsoftllc.agenthq.ui.protocol.StatusPayload
import com.superiorsoftllc.agenthq.ui.protocol.TodoItemPayload
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** TODO-OVERLAY-1: replace semantics, status mapping, counter, collapse memory and visibility of the agent's to-do list. */
class TodoListModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun rec() = SessionRecord("a", "stub", "/w", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.BUSY, providerSessionId = "p")
    private fun session() = Envelope(Envelope.SESSION, json.encodeToString(SessionRecord.serializer(), rec()), "a", 1)
    private fun todo(seq: Long, vararg items: TodoItemPayload) = Envelope(
        Envelope.STATUS,
        json.encodeToString(StatusPayload.serializer(), StatusPayload(SessionState.BUSY, tool = "TodoWrite", todos = items.toList())),
        "a", seq,
    )
    private fun feed(vararg e: Envelope) = e.fold(AppState()) { s, f -> reduce(s, f, 100) }

    @Test fun everyUpdateReplacesTheListAndMapsStatuses() {
        val s = feed(
            session(),
            todo(2, TodoItemPayload("a", "pending"), TodoItemPayload("b", "pending", "Doing b")),
            todo(3, TodoItemPayload("a", "done"), TodoItemPayload("b", "in_progress", "Doing b"), TodoItemPayload("c", "pending")),
        )
        val list = s.agents.getValue("a").todos!!
        assertEquals(3, list.total)
        assertEquals("1/3", list.counter)
        assertEquals(listOf(TodoStatus.DONE, TodoStatus.IN_PROGRESS, TodoStatus.PENDING), list.items.map { it.status })
        assertEquals("Doing b", list.items[1].label)
        assertEquals("a", list.items[0].label)
        assertFalse(list.allDone)
    }

    @Test fun anEmptyListClearsAndANonTodoStatusLeavesItAlone() {
        val withList = feed(session(), todo(2, TodoItemPayload("a", "done")))
        assertTrue(withList.agents.getValue("a").todos!!.allDone)
        val plain = reduce(withList, Envelope(Envelope.STATUS, json.encodeToString(StatusPayload.serializer(), StatusPayload(SessionState.BUSY, tool = "Read")), "a", 3), 100)
        assertEquals(1, plain.agents.getValue("a").todos!!.total)
        val cleared = reduce(plain, todo(4), 100)
        assertTrue(cleared.agents.getValue("a").todos!!.isEmpty)
        assertNull(feed(session()).agents.getValue("a").todos)
    }

    @Test fun theBoxShowsOnceAListExistsWhateverTheSessionState() {
        val list = feed(session(), todo(2, TodoItemPayload("a", "pending"))).agents.getValue("a").todos
        assertTrue(todoBoxVisible(list))
        assertFalse(todoBoxVisible(null))
        assertFalse(todoBoxVisible(feed(session(), todo(2)).agents.getValue("a").todos))
    }

    @Test fun onePanelAtATime() {
        assertEquals(SidePanel.TODO, SidePanel.NONE.toggled(SidePanel.TODO))
        assertEquals(SidePanel.NONE, SidePanel.TODO.toggled(SidePanel.TODO))
        assertEquals(SidePanel.REASONING, SidePanel.TODO.toggled(SidePanel.REASONING))
        assertEquals(SidePanel.TODO, SidePanel.REASONING.toggled(SidePanel.TODO))
    }

    @Test fun aTodoBarNamesItsCurrentItem() {
        val p = com.superiorsoftllc.agenthq.ui.protocol.ProgressView(
            mode = "count", percent = 50.0, label = "to-do", step = "beta", done = 1, total = 2, startedAt = 0, updatedAt = 0, origin = "todo",
        )
        val d = com.superiorsoftllc.agenthq.ui.model.progressDisplay(p, now = 0)
        assertTrue(d.headline.contains("1 / 2 beta"), d.headline)
        assertEquals(0.5f, d.fraction)
    }
}
