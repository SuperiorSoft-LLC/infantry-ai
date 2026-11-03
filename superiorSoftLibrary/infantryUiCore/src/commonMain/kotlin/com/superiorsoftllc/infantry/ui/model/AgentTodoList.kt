package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.TodoItemPayload

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
 * TODO-BOX-1: the to-do toggle shows only once the agent has made a list (a non-empty one); it stays after the run ends,
 * so the owner can still read what was done.
 */
fun todoBoxVisible(list: AgentTodoList?): Boolean = list != null && !list.isEmpty

/** Which panel of the chat's top-right slot is open; at most one at a time. */
enum class SidePanel { NONE, REASONING, TODO }

/** Clicking [panel]'s button: opens it (closing the other one), or closes it when it is already open. */
fun SidePanel.toggled(panel: SidePanel): SidePanel = if (this == panel) SidePanel.NONE else panel
