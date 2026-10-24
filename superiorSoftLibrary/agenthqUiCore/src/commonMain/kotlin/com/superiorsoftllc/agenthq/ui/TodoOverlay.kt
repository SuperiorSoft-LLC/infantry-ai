package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AgentTodoList
import com.superiorsoftllc.agenthq.ui.model.TODO_AUTO_COLLAPSE_MS
import com.superiorsoftllc.agenthq.ui.model.TodoCollapseMemory
import com.superiorsoftllc.agenthq.ui.model.TodoItem
import com.superiorsoftllc.agenthq.ui.model.TodoStatus
import com.superiorsoftllc.agenthq.ui.model.todoCardVisible
import kotlinx.coroutines.delay

const val TODO_CARD_TAG = "todo-card"
const val TODO_PILL_TAG = "todo-pill"
const val TODO_HEADER_TAG = "todo-header"
const val TODO_ITEM_TAG = "todo-item"

private val NARROW = 600.dp

/**
 * TODO-OVERLAY-1: the agent's own to-do list, floating in the top-right of the chat area (the bottom-right belongs to the
 * toast host). Lay it out inside the transcript's Box; it draws nothing when [list] is empty or the session is not running.
 * Collapsed state is remembered per [sessionId] in [memory]; a narrow area starts as the pill.
 */
@Composable
fun BoxScope.TodoOverlay(
    sessionId: String,
    list: AgentTodoList?,
    running: Boolean,
    memory: TodoCollapseMemory,
    /** Room kept free above the card for the slot's first row (the reasoning button). */
    topInset: Dp = 0.dp,
    onMemory: (TodoCollapseMemory) -> Unit,
) {
    if (!todoCardVisible(list, running) || list == null) return
    BoxWithConstraints(Modifier.matchParentSize()) {
        val narrow = maxWidth < NARROW
        val listCap = maxHeight * 0.4f
        val collapsed = memory.isCollapsed(sessionId, narrow)
        // Everything done: fold into the pill after a few seconds, once (a later change to the list restarts the wait).
        LaunchedEffect(sessionId, list.updatedAt, list.allDone) {
            if (list.allDone && !memory.isCollapsed(sessionId, narrow)) {
                delay(TODO_AUTO_COLLAPSE_MS)
                onMemory(memory.toggled(sessionId, narrow))
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(start = 8.dp, end = 8.dp, bottom = 8.dp, top = 8.dp + topInset)) {
            val toggle = { onMemory(memory.toggled(sessionId, narrow)) }
            if (collapsed) TodoPill(list, toggle) else TodoCard(list, maxListHeight = listCap, onToggle = toggle)
        }
    }
}

@Composable
private fun TodoPill(list: AgentTodoList, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.testTag(TODO_PILL_TAG)
            .semantics { contentDescription = "To do ${list.counter}, expand" }
            .clickable(onClick = onToggle),
    ) {
        Text(
            "☑ ${list.counter}",
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TodoCard(list: AgentTodoList, maxListHeight: Dp, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 4.dp,
        modifier = Modifier.testTag(TODO_CARD_TAG).widthIn(max = 280.dp),
    ) {
        Column(Modifier.padding(8.dp)) {
            Row(
                Modifier.testTag(TODO_HEADER_TAG)
                    .semantics { contentDescription = "To do ${list.counter}, collapse" }
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("To do ${list.counter}", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("▴", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // About 40% of the chat height, then it scrolls instead of covering the transcript.
            Column(
                Modifier.padding(top = 6.dp).heightIn(max = maxListHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                list.items.forEach { TodoRow(it) }
            }
        }
    }
}

@Composable
private fun TodoRow(item: TodoItem) {
    val scheme = MaterialTheme.colorScheme
    val done = item.status == TodoStatus.DONE
    val active = item.status == TodoStatus.IN_PROGRESS
    val mark = when (item.status) { TodoStatus.DONE -> "✓"; TodoStatus.IN_PROGRESS -> "▶"; TodoStatus.PENDING -> "○" }
    Row(Modifier.testTag(TODO_ITEM_TAG), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(mark, fontSize = 12.sp, color = if (active) scheme.primary else scheme.onSurfaceVariant)
        Text(
            item.label,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            textDecoration = if (done) TextDecoration.LineThrough else null,
            color = when { done -> scheme.onSurface.copy(alpha = 0.45f); active -> scheme.primary; else -> scheme.onSurface },
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
