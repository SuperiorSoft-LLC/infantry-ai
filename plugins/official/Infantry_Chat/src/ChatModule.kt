package com.superiorsoftllc.agenthq.plugins.official.chat

import com.superiorsoftllc.agenthq.plugin.api.FillDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.ui.ChatPanel
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.shell.ChatPanels
import com.superiorsoftllc.agenthq.ui.shell.blocks.CHAT_FILL_ID
import com.superiorsoftllc.agenthq.ui.shell.blocks.ShellCenter

/** Infantry_Chat as a compiled-in module (web and Android, D9): the manifest mirrors module.json. */
val InfantryChat = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Chat", version = "1.0.0", author = "Infantry AI",
        description = "The centre column: the agent chat, the centre tab strip and the New agent dialog.",
        fills = listOf(FillDeclaration(CHAT_FILL_ID)),
    ),
    fills = mapOf(CHAT_FILL_ID to ShellCenter),
)

/** Hands the shared chat panel to hosts that stay in the shell (the Assistant popup) through [ChatPanels]. */
private fun publishPanel() {
    ChatPanels.panel = { id, controller, modifier, chat, allowSpawn, tag -> ChatPanel(id, controller, modifier, chat = chat, allowSpawn = allowSpawn, transcriptTag = tag) }
}

/**
 * RUNTIME-22: the entry of Infantry_Chat (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Chat`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class ChatPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        publishPanel()
        registrar.fill(CHAT_FILL_ID, ShellCenter)
    }

    override fun modules(): List<AgentHqModule> { publishPanel(); return listOf(InfantryChat) }
}
