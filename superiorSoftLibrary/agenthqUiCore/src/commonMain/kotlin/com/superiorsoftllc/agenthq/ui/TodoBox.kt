package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.superiorsoftllc.agenthq.ui.model.TodoItem
import com.superiorsoftllc.agenthq.ui.model.TodoStatus

const val TODO_BUTTON_TAG = "todo-button"
const val TODO_PANEL_TAG = "todo-panel"
const val TODO_PANEL_CLOSE_TAG = "todo-panel-close"
const val TODO_ITEM_TAG = "todo-item"

/**
 * TODO-BOX-1: the to-do toggle, next to the reasoning button in the chat's top-right row ([ChatSideSlot]). It carries
 * the done counter (`☑ 3/7`); clicking it opens / closes [TodoPanel].
 */
@Composable
internal fun TodoButton(list: AgentTodoList, open: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (open) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.testTag(TODO_BUTTON_TAG)
            .semantics { contentDescription = if (open) "Close to-do list" else "Open to-do list, ${list.doneCount} of ${list.total} done" }
            .clickable(onClick = onToggle),
    ) {
        Text(
            "☑ ${list.counter}",
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The checklist itself, like the reasoning panel but only the items: done ✓ struck through, the current one ▶, the rest ○. */
@Composable
internal fun TodoPanel(list: AgentTodoList?, maxHeight: Dp, onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = scheme.surface.copy(alpha = 0.97f),
        border = BorderStroke(1.dp, scheme.outlineVariant),
        shadowElevation = 4.dp,
        modifier = Modifier.testTag(TODO_PANEL_TAG).widthIn(max = 360.dp),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "To do ${list?.counter.orEmpty()}", Modifier.weight(1f),
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface,
                )
                OutlinedButton(onClick = onClose, modifier = Modifier.testTag(TODO_PANEL_CLOSE_TAG)) { Text("Close", fontSize = 11.sp) }
            }
            Column(
                Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                if (list == null || list.isEmpty) Text("No to-do list.", fontSize = 11.sp, color = scheme.onSurfaceVariant)
                else list.items.forEach { TodoRow(it) }
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
    Row(
        Modifier.testTag(TODO_ITEM_TAG).semantics {
            contentDescription = "${item.label}, " + when (item.status) { TodoStatus.DONE -> "done"; TodoStatus.IN_PROGRESS -> "in progress"; TodoStatus.PENDING -> "to do" }
        },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
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
