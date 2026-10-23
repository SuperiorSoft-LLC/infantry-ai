package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.Notice
import com.superiorsoftllc.agenthq.ui.model.crossProjectNotices
import kotlinx.coroutines.delay

/** Test tag on the bottom-right host, and on each toast in it. */
const val TOAST_HOST_TAG = "toast-host"
const val TOAST_TAG = "toast"

/** A toast stays this long before it dismisses itself. */
const val TOAST_LIFETIME_MS = 8_000L

/** At most this many toasts stack at once; the newest win. */
const val MAX_VISIBLE_TOASTS = 4

/**
 * Which cross-project notices to show: those newer than [baseline] (what was already there when the host first
 * composed is history, not news) that have not been [dismissed], newest [MAX_VISIBLE_TOASTS], oldest first.
 */
fun visibleToasts(notices: List<Notice>, baseline: Long, dismissed: Set<Notice>): List<Notice> =
    notices.filter { it.at > baseline && it !in dismissed }.takeLast(MAX_VISIBLE_TOASTS)

/**
 * Global bottom-right toasts for events in OTHER projects than the active one. Overlay it in a [Box] over the whole
 * window; it draws nothing when there is nothing to say. [onOpen] is called when a toast is clicked (jump to its project).
 */
@Composable
fun ToastHost(state: AppState, onOpen: (Notice) -> Unit, modifier: Modifier = Modifier) {
    val all = state.crossProjectNotices()
    val baseline = remember { state.notices.maxOfOrNull { it.at } ?: Long.MIN_VALUE }
    var dismissed by remember { mutableStateOf(emptySet<Notice>()) }
    val shown = visibleToasts(all, baseline, dismissed)
    if (shown.isEmpty()) return
    Column(
        modifier.padding(16.dp).width(320.dp).testTag(TOAST_HOST_TAG),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (n in shown) {
            LaunchedEffect(n) { delay(TOAST_LIFETIME_MS); dismissed = dismissed + n }
            val colour = noticeColour(n.kind)
            val project = state.projects.firstOrNull { it.id == n.projectId }?.name ?: n.projectId.orEmpty()
            Row(
                Modifier.height(IntrinsicSize.Min).background(colour.copy(alpha = 0.12f).compositeOver(Palette.Panel))
                    .clickable { dismissed = dismissed + n; onOpen(n) }.testTag(TOAST_TAG),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(colour))
                Column(Modifier.weight(1f).padding(10.dp, 6.dp)) {
                    Text(project, color = Palette.Muted, fontSize = 11.sp, maxLines = 1)
                    Text(n.text, color = colour, fontSize = 12.sp, maxLines = 3)
                }
                Text("×", color = Palette.Muted, fontSize = 16.sp,
                    modifier = Modifier.clickable { dismissed = dismissed + n }.padding(10.dp, 4.dp))
            }
        }
    }
}
