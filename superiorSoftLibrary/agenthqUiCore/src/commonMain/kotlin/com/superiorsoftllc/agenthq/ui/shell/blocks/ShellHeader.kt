package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.ui.ATTENTION_TAB_ID
import com.superiorsoftllc.agenthq.ui.AssistantEntry
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.ProjectPicker
import com.superiorsoftllc.agenthq.ui.ServerSwitcherChip
import com.superiorsoftllc.agenthq.ui.TitleBar
import com.superiorsoftllc.agenthq.ui.block.Block
import com.superiorsoftllc.agenthq.ui.block.DockScope
import com.superiorsoftllc.agenthq.ui.block.Edge
import com.superiorsoftllc.agenthq.ui.model.needsInput

/** BLOCKS-SHELL-1: the window's title bar, on top. */
object ShellHeader : Block("infantry.shell.header", "infantry.shell.header", Edge.TOP) {
    @Composable
    override fun Render(dock: DockScope) {
        val s = LocalShell.current
        val state = s.state
        val switcher = s.switcher
        TitleBar(
            state, s.ui.flags.agentScope, onConnect = { s.changeConnection?.invoke() ?: run { s.ui.showConnect = true } }, onAttention = { s.showTab(ATTENTION_TAB_ID) },
            projectSlot = { ProjectPicker(state, s.controller) },
            onTogglePanel = s.controller::togglePanel,
            serverSlot = {
                if (switcher != null) ServerSwitcherChip(switcher, onSelect = s.switchTo, onManage = { s.ui.showServers = true })
            },
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
