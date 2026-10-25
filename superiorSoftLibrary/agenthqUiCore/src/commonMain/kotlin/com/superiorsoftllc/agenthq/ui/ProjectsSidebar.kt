package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.Notice

/**
 * The newest daemon refusal of a project / workspace request, owned by [AppState.projectRefusal]: it clears on a
 * successful save, on a project switch and when the user dismisses it, so it never stays pinned.
 */
internal fun AppState.projectsRefusal(): Notice? = projectRefusal

/**
 * The sidebar's project slot: the daemon's newest project / workspace refusal, shown inline with a dismiss button.
 * Picking and creating a project live in the title bar (M2-7, [ProjectPicker]); the WORKSPACES block was removed in
 * M2-13 because the Git tab already shows the same thing. Draws nothing while there is no refusal.
 */
@Composable
fun ProjectsSidebar(state: AppState, onDismiss: () -> Unit) {
    val refusal = state.projectsRefusal() ?: return
    Row(
        Modifier.fillMaxWidth().padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(refusal.text, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = onDismiss) { Text("Dismiss", fontSize = 11.sp) }
    }
}
