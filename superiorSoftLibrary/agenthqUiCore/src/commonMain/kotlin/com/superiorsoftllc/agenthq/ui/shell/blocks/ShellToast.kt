package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.LocalToast
import com.superiorsoftllc.agenthq.ui.ToastHost
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlaced
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlain
import kotlinx.coroutines.launch

/**
 * BLOCKS-SHELL-1: the toasts, bottom right over the window; a tap opens what a toast is about.
 * TOAST-1: a container. Its children (a module's own toast blocks) draw over the dock after the core host, aligning
 * themselves in the dock's stack, under the controller's [com.superiorsoftllc.agenthq.ui.ToastService] (`LocalToast`).
 * The service itself is provided at the shell root, so any block anywhere can toast.
 */
class ToastBlock(children: List<Block> = emptyList(), place: Place = Place()) :
    Block("infantry.shell.toast", "infantry.shell.toast", place, children) {
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
        if (children.isNotEmpty()) CompositionLocalProvider(LocalToast provides s.controller.toasts) {
            box?.run { RenderPlaced(children) } ?: RenderPlain(children)
        }
    }

    override fun withChildren(children: List<Block>) = ToastBlock(children, place)
}

val ShellToast = ToastBlock()
