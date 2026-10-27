package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.testTag
import com.superiorsoftllc.agenthq.ui.CENTER_PANE_TAG
import com.superiorsoftllc.agenthq.ui.CenterTabBar
import com.superiorsoftllc.agenthq.ui.ChatPanel
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.NewAgentDialog
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlaced
import com.superiorsoftllc.agenthq.ui.net.CommandResult

private const val CENTER = "infantry.shell.center"

/**
 * BLOCKS-SHELL-1: the centre column (chat, centre tabs, the New agent dialog); it fills what the side panels leave.
 * BLOCKS-LAYOUT L9b: a container of child blocks `infantry.shell.center.<name>`, top to bottom, drawn straight into its
 * column.
 */
class CenterBlock(children: List<Block>, place: Place = Place(weight = 1f)) : Block("center", CENTER, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val controller = s.controller
        val compact = dockCompact()
        // MOBILE-LAYOUT-2: crossing 600dp (a resize, a rotation) parks this window class's panel flags and brings
        // back the other class's, so compact navigation never rewrites the wide layout. The panels render from
        // forWindow(compact) straight away; the effect then stores the same swap, so no frame shows stale flags.
        LaunchedEffect(compact) { controller.setCompactWindow(compact) }
        Column(scope.modifier.fillMaxHeight().testTag(CENTER_PANE_TAG)) {
            RenderPlaced(children)
        }
    }

    override fun withChildren(children: List<Block>) = CenterBlock(children, place)
}

/** CHAT-PANEL-1: the main window is one host of the shared ChatPanel; the tab strip and the other tabs are its slots. */
object CenterChat : Block("chatPanel", "$CENTER.chat", Place(weight = 1f)) {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val ui = s.ui
        ChatPanel(
            s.selected?.id, s.controller, scope.modifier.fillMaxWidth(), chat = s.chat,
            onOpenTicket = { ui.showBoard = true },
            beforeContent = { CenterTabBar(s.state, ui.centerTab) { ui.centerTab = it } },
            content = { transcript -> s.tabs.Content(ui.centerTab, s.tabContext(transcript)) },
        )
    }
}

/** The New agent modal and its catalog refresh. A dialog has no node in the column, so the placement has nothing to wear. */
object CenterNewAgent : Block("newAgentDialog", "$CENTER.newAgent") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val state = s.state
        val controller = s.controller
        val ui = s.ui
        LaunchedEffect(ui.showNewAgent, state.connection.isLive) {
            if (ui.showNewAgent && state.connection.isLive) controller.refreshModelCatalog()
        }
        if (ui.showNewAgent) {
            NewAgentDialog(
                state,
                onAdd = { role, picked, task ->
                    controller.pickSpawnRole(role)
                    controller.pickModel(picked)
                    val r = controller.spawn(task)
                    if (r is CommandResult.Refused) r.reason else { ui.showNewAgent = false; null }
                },
                onDismiss = { ui.showNewAgent = false },
            )
        }
    }
}

val ShellCenter = CenterBlock(listOf(CenterChat, CenterNewAgent))
