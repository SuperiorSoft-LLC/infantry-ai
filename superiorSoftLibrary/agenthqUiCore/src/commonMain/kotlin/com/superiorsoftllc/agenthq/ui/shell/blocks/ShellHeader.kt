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
import com.superiorsoftllc.agenthq.ui.TitleBarLead
import com.superiorsoftllc.agenthq.ui.TitleBarWindowControls
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.block.layout.Place
import com.superiorsoftllc.agenthq.ui.block.layout.RenderPlaced
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

/**
 * The left toggle, logo + name, project slot and avatar strip (L7 splits it). Its items, the drag area's weight included,
 * go straight into the bar's row ([BlockScope.row]); drawn outside a row they get a row of their own.
 */
object HeaderLead : Block("titleBarLead", "$HEADER.lead") {
    @Composable
    override fun Render(scope: BlockScope) {
        val row = scope.row
        if (row == null) Row(verticalAlignment = Alignment.CenterVertically) { Lead(scope.modifier) } else row.Lead(scope.modifier)
    }

    @Composable
    private fun RowScope.Lead(modifier: Modifier) {
        val s = LocalShell.current
        val state = s.state
        val switcher = s.switcher
        TitleBarLead(
            state, s.ui.flags.agentScope, onConnect = { s.changeConnection?.invoke() ?: run { s.ui.showConnect = true } }, onAttention = { s.showTab(ATTENTION_TAB_ID) },
            chrome = LocalWindowChrome.current,
            projectSlot = { ProjectPicker(state, s.controller) },
            onTogglePanel = s.controller::togglePanel,
            // ASSISTANT-1: the last member of the team strip; a tap toggles the popup (the daemon makes the session on the first open).
            assistantSlot = {
                if (state.connection.hasAssistant || state.assistant != null) {
                    AssistantEntry(
                        s.assistantPanel.open,
                        state.assistant?.let { needsInput(it, state.waiting, state.openPrompts, state.openQuestionSessions) } == true,
                    ) { s.assistantPanel.toggle() }
                }
            },
            serverSlot = {
                if (switcher != null) ServerSwitcherChip(switcher, onSelect = s.switchTo, onManage = { s.ui.showServers = true })
            },
            modifier = modifier,
        )
    }
}

/** M2-6: minimize / maximize-restore / close where the platform has a window, else the bar's 12dp end. */
object HeaderWindowControls : Block("windowControls", "$HEADER.windowControls") {
    @Composable
    override fun Render(scope: BlockScope) = TitleBarWindowControls(LocalWindowChrome.current, scope.modifier)
}

/** BLOCKS-SHELL-1: the window's title bar, on top. */
val ShellHeader = TitleBarBlock(HEADER, listOf(HeaderLead, HeaderWindowControls))
