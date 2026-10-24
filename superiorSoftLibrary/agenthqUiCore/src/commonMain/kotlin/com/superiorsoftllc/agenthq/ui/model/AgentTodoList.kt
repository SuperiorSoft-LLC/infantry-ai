package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.TodoItemPayload

enum class TodoStatus { PENDING, IN_PROGRESS, DONE }

/** One line of an agent's own to-do list. [activeText] is the present-tense form (claude `activeForm`) when the harness has one. */
data class TodoItem(val text: String, val status: TodoStatus, val activeText: String? = null) {
    /** What the card shows: the active form while the item is in progress, else the plain text. */
    val label: String get() = if (status == TodoStatus.IN_PROGRESS) activeText ?: text else text
}

/**
 * TODO-OVERLAY-1: the agent's to-do list as of [updatedAt]. Every update from the agent carries the whole list, so a new
 * list REPLACES the old one ([of] builds it from the wire items); nothing is merged.
 */
data class AgentTodoList(val items: List<TodoItem>, val updatedAt: Long) {
    val doneCount: Int get() = items.count { it.status == TodoStatus.DONE }
    val total: Int get() = items.size
    val isEmpty: Boolean get() = items.isEmpty()
    val allDone: Boolean get() = items.isNotEmpty() && doneCount == total

    /** The header / pill counter: `3/7`. */
    val counter: String get() = "$doneCount/$total"

    companion object {
        fun of(items: List<TodoItemPayload>, now: Long): AgentTodoList = AgentTodoList(
            items.map { TodoItem(it.text, statusOf(it.status), it.activeText) },
            now,
        )

        fun statusOf(wire: String): TodoStatus = when (wire) {
            "done" -> TodoStatus.DONE
            "in_progress" -> TodoStatus.IN_PROGRESS
            else -> TodoStatus.PENDING
        }
    }
}

/**
 * Whether the card is open, remembered per session. A session never opened or closed by the owner follows [defaultCollapsed]
 * (true on a narrow screen); an explicit toggle always wins afterwards.
 */
data class TodoCollapseMemory(private val explicit: Map<String, Boolean> = emptyMap()) {
    fun isCollapsed(sessionId: String, defaultCollapsed: Boolean): Boolean = explicit[sessionId] ?: defaultCollapsed
    fun toggled(sessionId: String, defaultCollapsed: Boolean): TodoCollapseMemory =
        TodoCollapseMemory(explicit + (sessionId to !isCollapsed(sessionId, defaultCollapsed)))
}

/** What the overlay draws for a session: nothing when the list is empty or the session is not the running one. */
fun todoCardVisible(list: AgentTodoList?, sessionRunning: Boolean): Boolean = sessionRunning && list != null && !list.isEmpty

/** Milliseconds after the last item is done before the card folds itself into the pill. */
const val TODO_AUTO_COLLAPSE_MS = 4_000L
