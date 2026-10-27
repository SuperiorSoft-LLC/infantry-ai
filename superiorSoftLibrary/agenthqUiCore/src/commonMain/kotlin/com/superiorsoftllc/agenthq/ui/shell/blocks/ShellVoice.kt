package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.superiorsoftllc.agenthq.ui.LocalShell
import com.superiorsoftllc.agenthq.ui.block.Block
import com.superiorsoftllc.agenthq.ui.block.DockScope
import com.superiorsoftllc.agenthq.ui.block.Edge
import com.superiorsoftllc.agenthq.ui.voice.VoiceAlerts

/** VOICE-1: spoken attention alerts (and their toast) over every pane. */
object ShellVoice : Block("infantry.shell.voice", "infantry.shell.voice", Edge.OVERLAY) {
    @Composable
    override fun Render(dock: DockScope) = with(dock) {
        val s = LocalShell.current
        VoiceAlerts(s.controller.spoken, s.ui.flags.voice, Modifier.aligned(Alignment.BottomStart))
    }
}
