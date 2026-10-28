package com.superiorsoftllc.agenthq.plugins.official.alerts

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.superiorsoftllc.agenthq.plugin.api.FillDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.ui.block.layout.Block
import com.superiorsoftllc.agenthq.ui.block.layout.BlockScope
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.shell.LocalShellContext
import com.superiorsoftllc.agenthq.ui.sound.IncomingCommsSoundEffect
import com.superiorsoftllc.agenthq.ui.voice.VoiceAlerts

/** The shell's incoming-comms sound slot, which this module fills. */
const val SOUND_FILL_ID = "infantry.shell.sound"

/** The shell's spoken-alerts slot, which this module fills. */
const val VOICE_FILL_ID = "infantry.shell.voice"

/** BLOCKS-SHELL-1: the incoming-comms sound; it draws nothing. RUNTIME-16: reads the shell context's alerts domain. */
object AlertsSound : Block(SOUND_FILL_ID, SOUND_FILL_ID) {
    @Composable
    override fun Render(scope: BlockScope) {
        val alerts = LocalShellContext.current?.alerts ?: return
        val state = alerts.state ?: return
        IncomingCommsSoundEffect(state, alerts.commsSound)
    }
}

/** VOICE-1: spoken attention alerts (and their toast) over every pane. RUNTIME-16: reads the shell context's alerts domain. */
object AlertsVoice : Block(VOICE_FILL_ID, VOICE_FILL_ID) {
    @Composable
    override fun Render(scope: BlockScope) = with(scope) {
        val alerts = LocalShellContext.current?.alerts ?: return
        VoiceAlerts(alerts.spoken, alerts.voice, Modifier.aligned(Alignment.BottomStart))
    }
}

/** RUNTIME-16: Infantry_Alerts (official), the sound and voice slots, compiled in on web and Android (D9). */
val InfantryAlerts = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Alerts", version = "1.0.0", author = "Infantry AI",
        description = "The incoming-comms sound and spoken attention alerts.",
        fills = listOf(FillDeclaration(SOUND_FILL_ID), FillDeclaration(VOICE_FILL_ID)),
    ),
    fills = mapOf(SOUND_FILL_ID to AlertsSound, VOICE_FILL_ID to AlertsVoice),
)

/**
 * RUNTIME-16: the entry of Infantry_Alerts (module.json `entry`), an official module. It fills the shell's sound and voice
 * slots (formerly filled by Infantry_AppUi). Without it the app plays no incoming-comms sound and speaks no alert.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Alerts`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class AlertsPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.fill(SOUND_FILL_ID, AlertsSound)
        registrar.fill(VOICE_FILL_ID, AlertsVoice)
    }

    override fun modules(): List<AgentHqModule> = listOf(InfantryAlerts)
}
