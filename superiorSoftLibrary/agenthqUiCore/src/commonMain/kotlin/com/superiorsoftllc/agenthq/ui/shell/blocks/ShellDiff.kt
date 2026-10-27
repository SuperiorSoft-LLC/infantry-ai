package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.ProjectDiffModal
import com.superiorsoftllc.agenthq.ui.block.Block
import com.superiorsoftllc.agenthq.ui.block.DockScope
import com.superiorsoftllc.agenthq.ui.block.Edge

/** DIFF-MODAL: the changed file's diff over the window, while one is open. */
object ShellDiff : Block("infantry.shell.diff", "infantry.shell.diff", Edge.OVERLAY) {
    @Composable
    override fun Render(dock: DockScope) {
        val s = LocalShell.current
        s.ui.diffTarget?.let { target ->
            ProjectDiffModal(s.state, s.controller, target, onTarget = { next -> s.ui.diffTarget = next; if (next == null) s.root.requestFocus() })
        }
    }
}
