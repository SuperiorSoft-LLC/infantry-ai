package com.superiorsoftllc.agenthq.ui.shell.blocks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.voice.VoiceAlerts

/** VOICE-1: spoken attention alerts (and their toast) over every pane. */
object ShellVoice : Block("infantry.shell.voice", "infantry.shell.voice") {
    @Composable
    override fun Render(scope: BlockScope) = with(scope) {
        val s = appShell
        VoiceAlerts(s.controller.spoken, s.ui.flags.voice, Modifier.aligned(Alignment.BottomStart))
    }
}
