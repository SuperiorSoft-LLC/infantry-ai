package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.Notice

/**
 * The newest daemon refusal of a project / workspace request. The daemon names the refused request in the
 * `error` text (`project_save refused: ...`, `workspace_list failed: ...`), so the sidebar can show its own
 * refusals inline instead of leaving them to the global strip.
 */
internal fun AppState.projectsRefusal(): Notice? =
    notices.lastOrNull { it.sessionId == null && (it.text.startsWith("project_") || it.text.startsWith("workspace_")) }

/**
 * The sidebar's project slot: the daemon's newest project / workspace refusal, shown inline. Picking and creating
 * a project live in the title bar (M2-7, [ProjectPicker]); the WORKSPACES block was removed in M2-13 because the
 * Git tab already shows the same thing. Draws nothing while there is no refusal. Everything shown comes from [state].
 */
@Composable
fun ProjectsSidebar(state: AppState) {
    state.projectsRefusal()?.let {
        Text(it.text, color = Palette.Danger, fontSize = 11.sp, modifier = Modifier.fillMaxWidth().padding(10.dp))
    }
}
