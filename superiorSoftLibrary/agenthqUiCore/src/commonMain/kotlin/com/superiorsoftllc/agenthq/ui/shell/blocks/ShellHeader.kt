package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.ATTENTION_TAB_ID
import com.superiorsoftllc.agenthq.ui.AssistantEntry
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.LocalWindowChrome
import com.superiorsoftllc.agenthq.ui.ProjectPicker
import com.superiorsoftllc.agenthq.ui.ServerSwitcherChip
import com.superiorsoftllc.agenthq.ui.TitleBarFrame
import com.superiorsoftllc.agenthq.ui.TitleBarLead
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

/** The left toggle, logo + name, project slot and avatar strip (L7 splits it); it takes the width the items after it leave. */
object HeaderLead : Block("titleBarLead", "$HEADER.lead", Place(weight = 1f)) {
    @Composable
    override fun Render(scope: BlockScope) {
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
            modifier = scope.modifier,
        )
    }
}

/** BLOCKS-SHELL-1: the window's title bar, on top. */
val ShellHeader = TitleBarBlock(HEADER, listOf(HeaderLead))
