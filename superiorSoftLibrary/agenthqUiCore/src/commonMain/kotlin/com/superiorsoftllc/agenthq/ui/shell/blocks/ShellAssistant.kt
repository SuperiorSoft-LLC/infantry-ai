package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.superiorsoftllc.agenthq.ui.AssistantPopup
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.TITLE_BAR_HEIGHT
import com.superiorsoftllc.agenthq.ui.block.Block
import com.superiorsoftllc.agenthq.ui.block.DockScope
import com.superiorsoftllc.agenthq.ui.block.Edge

/**
 * ASSISTANT-1: the global Assistant's popup, docked top right under the title bar (a full-screen sheet when compact). The
 * window owns its state, so closing it keeps the draft, the width and the model; it is one more host of ChatPanel.
 */
object ShellAssistant : Block("infantry.shell.assistant", "infantry.shell.assistant", Edge.OVERLAY) {
    @Composable
    override fun Render(dock: DockScope) = with(dock) {
        val s = LocalShell.current
        if (s.assistantPanel.open) {
            val compactWindow = s.state.panels.compactWindow
            AssistantPopup(
                s.state, s.controller, s.assistantPanel, s.assistantChat, compact = compactWindow,
                modifier = Modifier.aligned(Alignment.TopEnd).then(if (compactWindow) Modifier else Modifier.padding(top = TITLE_BAR_HEIGHT + 1.dp, bottom = 28.dp)),
            )
        }
    }
}
