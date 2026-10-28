package com.superiorsoftllc.agenthq.plugins.official.prompts

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon
import com.superiorsoftllc.agenthq.ui.shell.LocalShellContext

/** The Prompts tab's id. */
const val PROMPTS_TAB_ID = "infantry.prompts"

/** The Prompts tab (order 50 in the right-hand strip). Its pane reads the plugin API contexts (link, prompts) only. */
private fun promptsTab() = TabProvider(
    id = PROMPTS_TAB_ID, container = TabContainers.RIGHT, order = 50,
    title = { "Prompts" },
    icon = tabIcon(
        "prompts",
        { moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 16f); lineTo(10f, 16f); lineTo(6f, 20f); lineTo(6f, 16f); lineTo(4f, 16f); close() },
    ),
    testTag = "right-tab-PROMPTS",
    routes = listOf("loadPrompts", "savePrompt", "deletePrompt"),
) { tab ->
    LocalShellContext.current?.let { ctx -> PromptsPane(ctx.link, ctx.prompts, onInsert = tab.insertIntoChat) }
}

/** PLUGINS-1: Infantry_Prompts (official since RUNTIME-9), as a compiled-in module (web and Android, D9; the manifest mirrors module.json). */
val InfantryPrompts = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Prompts", version = "1.0.0", author = "Infantry AI", description = "Saved prompts to insert into the chat.",
        tabs = listOf(TabDeclaration(PROMPTS_TAB_ID, TabContainers.RIGHT)),
    ),
    listOf(promptsTab()),
)

/**
 * RUNTIME-9: the entry of Infantry_Prompts (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Prompts`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class PromptsPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(promptsTab())
    }

    override fun modules(): List<AgentHqModule> = listOf(InfantryPrompts)
}
