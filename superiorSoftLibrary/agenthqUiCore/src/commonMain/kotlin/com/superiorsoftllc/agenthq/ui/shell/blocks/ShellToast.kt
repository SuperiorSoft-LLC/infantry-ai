package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.ToastHost
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import kotlinx.coroutines.launch

/** BLOCKS-SHELL-1: the toasts, bottom right over the window; a tap opens what a toast is about. */
object ShellToast : Block("infantry.shell.toast", "infantry.shell.toast") {
    @Composable
    override fun Render(scope: BlockScope) = with(scope) {
        val s = LocalShell.current
        val state = s.state
        ToastHost(
            state,
            onOpen = { n ->
                n.projectId?.let { id -> s.actionScope.launch { s.controller.selectProject(id) } }
                if (n.sessionId != null && state.agents.containsKey(n.sessionId)) s.controller.select(n.sessionId) else s.ui.showBoard = true
            },
            modifier = Modifier.aligned(Alignment.BottomEnd),
        )
    }
}
