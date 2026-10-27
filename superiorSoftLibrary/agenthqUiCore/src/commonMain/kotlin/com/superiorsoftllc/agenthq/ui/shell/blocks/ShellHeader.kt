package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.superiorsoftllc.agenthq.ui.ATTENTION_TAB_ID
import com.superiorsoftllc.agenthq.ui.AssistantEntry
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.LocalWindowChrome
import com.superiorsoftllc.agenthq.ui.ProjectPicker
import com.superiorsoftllc.agenthq.ui.ServerSwitcherChip
import com.superiorsoftllc.agenthq.ui.TitleBarFrame
import com.superiorsoftllc.agenthq.ui.TitleBarAgents
import com.superiorsoftllc.agenthq.ui.TitleBarBrand
import com.superiorsoftllc.agenthq.ui.TitleBarProject
import com.superiorsoftllc.agenthq.ui.TitleBarToggleLeft
import com.superiorsoftllc.agenthq.ui.TitleBarAttention
import com.superiorsoftllc.agenthq.ui.TitleBarRightToggle
import com.superiorsoftllc.agenthq.ui.TitleBarConnection
import com.superiorsoftllc.agenthq.ui.TitleBarWindowControls
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlaced
import com.superiorsoftllc.agenthq.ui.model.PanelSide
import com.superiorsoftllc.agenthq.ui.model.needsInput

/**
 * BLOCKS-LAYOUT L6 (docs/BLOCKS-LAYOUT-DESIGN.md section 8): the window's title bar as a `titleBar` layout block. Its
 * frame is the 36dp Panel row plus the divider; its items are children, left to right, ids `infantry.shell.header.<name>`.
 * Each item owns the gap next to it, so a replaced child keeps the bar's spacing.
 */
class TitleBarBlock(id: String, children: List<Block> = emptyList(), place: Place = Place()) :
    Block("titleBar", id, place, children) {
    @Composable
    override fun Render(scope: BlockScope) = TitleBarFrame(scope.modifier) { RenderPlaced(children) }

    override fun withChildren(children: List<Block>) = TitleBarBlock(id, children, place)
}

private const val HEADER = "infantry.shell.header"

private const val LEAD = "$HEADER.lead"

/**
 * BLOCKS-LAYOUT L7: the bar's left and middle, a container of child blocks `infantry.shell.header.lead.<name>`. Its
 * children, the drag area's weight included, go straight into the bar's row ([BlockScope.row]); drawn outside a row they
 * get a row of their own.
 */
class HeaderLeadBlock(children: List<Block>, place: Place = Place()) : Block("titleBarLead", LEAD, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        val row = scope.row
        if (row == null) Row(scope.modifier, verticalAlignment = Alignment.CenterVertically) { RenderPlaced(children) } else with(row) { RenderPlaced(children) }
    }

    override fun withChildren(children: List<Block>) = HeaderLeadBlock(children, place)
}

/** PANEL-TOGGLE-1: the agent list toggle at the bar's left edge, with the 6dp before it. */
object HeaderToggleLeft : Block("panelToggle", "$LEAD.toggleLeft") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        TitleBarToggleLeft(s.state, scope.modifier) { s.controller.togglePanel(PanelSide.LEFT) }
    }
}

/** The logo + name, a drag area (a double-click toggles maximize), with the 6dp after the left toggle and 10dp after it. */
object HeaderBrand : Block("brand", "$LEAD.brand") {
    @Composable
    override fun Render(scope: BlockScope) = TitleBarBrand(LocalWindowChrome.current, modifier = scope.modifier)
}

/** L7 interim: the project slot and the agent strip, until they are children of their own. */
private object HeaderLeadRest : Block("titleBarLeadRest", "$LEAD.rest") {
    @Composable
    override fun Render(scope: BlockScope) {
        val row = scope.row
        if (row == null) Row(verticalAlignment = Alignment.CenterVertically) { Rest(scope.modifier) } else row.Rest(scope.modifier)
    }

    @Composable
    private fun RowScope.Rest(modifier: Modifier) {
        val s = LocalShell.current
        val state = s.state
        TitleBarProject({ ProjectPicker(state, s.controller) }, modifier)
        TitleBarAgents(
            state, s.ui.flags.agentScope, onConnect = { s.changeConnection?.invoke() ?: run { s.ui.showConnect = true } }, onAttention = { s.showTab(ATTENTION_TAB_ID) },
            chrome = LocalWindowChrome.current,
            projectSlot = { ProjectPicker(state, s.controller) },
            // ASSISTANT-1: the last member of the team strip; a tap toggles the popup (the daemon makes the session on the first open).
            assistantSlot = {
                if (state.connection.hasAssistant || state.assistant != null) {
                    AssistantEntry(
                        s.assistantPanel.open,
                        state.assistant?.let { needsInput(it, state.waiting, state.openPrompts, state.openQuestionSessions) } == true,
                    ) { s.assistantPanel.toggle() }
                }
            },
        )
    }
}

val HeaderLead = HeaderLeadBlock(listOf(HeaderToggleLeft, HeaderBrand, HeaderLeadRest))

/** M2-6: minimize / maximize-restore / close where the platform has a window, else the bar's 12dp end. */
object HeaderWindowControls : Block("windowControls", "$HEADER.windowControls") {
    @Composable
    override fun Render(scope: BlockScope) = TitleBarWindowControls(LocalWindowChrome.current, scope.modifier)
}

/** The connection chip; a tap opens the connection (or the host's own connect screen, MOBILE-CONNCHIP-1). */
object HeaderConnection : Block("connectionChip", "$HEADER.connection") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        TitleBarConnection(s.state, { s.changeConnection?.invoke() ?: run { s.ui.showConnect = true } }, scope.modifier)
    }
}

/** SWITCH-1: the server dropdown, where the host has a server switcher; its menu also opens Servers. */
object HeaderServer : Block("serverSwitcher", "$HEADER.server") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        val switcher = s.switcher
        if (switcher != null) ServerSwitcherChip(switcher, onSelect = s.switchTo, onManage = { s.ui.showServers = true }, modifier = scope.modifier)
    }
}

/** PANEL-TOGGLE-1: the side panel toggle, in view at every width. */
object HeaderToggleRight : Block("panelToggle", "$HEADER.toggleRight") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        TitleBarRightToggle(s.state, scope.modifier) { s.controller.togglePanel(PanelSide.RIGHT) }
    }
}

/** Contract 1.9.0: the open attention count (wide only; a compact bar lists it in the overflow); a tap opens Attention. */
object HeaderAttention : Block("attentionChip", "$HEADER.attention") {
    @Composable
    override fun Render(scope: BlockScope) {
        val s = LocalShell.current
        TitleBarAttention(s.state, { s.showTab(ATTENTION_TAB_ID) }, scope.modifier)
    }
}

/** BLOCKS-SHELL-1: the window's title bar, on top. */
val ShellHeader = TitleBarBlock(HEADER, listOf(HeaderLead, HeaderAttention, HeaderToggleRight, HeaderServer, HeaderConnection, HeaderWindowControls))
