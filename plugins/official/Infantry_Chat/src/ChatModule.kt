package com.superiorsoftllc.infantry.plugins.official.chat

import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.FillDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.plugins.official.chat.CHAT_FILL_ID
import com.superiorsoftllc.infantry.plugins.official.chat.ShellCenter

/** Infantry_Chat as a compiled-in module (web and Android, D9): the manifest mirrors module.json. */
val InfantryChat = InfantryModule(
    ModuleManifest(
        name = "Infantry_Chat", version = "1.0.0", author = "Infantry AI",
        description = "The centre column: the agent chat, the centre tab strip and the New agent dialog.",
        slots = listOf("chat.feed"),
        fills = listOf(FillDeclaration(CHAT_FILL_ID)),
        config = listOf(
            ConfigDeclaration(node = "infantry.shell.config.markdownEngine", group = "Markdown engine", tab = "Appearance", order = 40, title = "Markdown engine", keywords = listOf("markdown", "table", "chat", "format", "renderer"), block = "infantry.shell.config.markdownEngine"),
            ConfigDeclaration(node = "infantry.shell.config.reasoning", group = "Agent reasoning", tab = "Appearance", order = 50, title = "Agent reasoning", keywords = listOf("reasoning", "thinking", "thought", "panel", "inline"), block = "infantry.shell.config.reasoning"),
        ),
    ),
    fills = mapOf(CHAT_FILL_ID to ShellCenter),
)

/**
 * RUNTIME-22: the entry of Infantry_Chat (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Chat`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class ChatPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.fill(CHAT_FILL_ID, ShellCenter)
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryChat)
}
