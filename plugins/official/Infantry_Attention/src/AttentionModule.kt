package com.superiorsoftllc.agenthq.plugins.official.attention

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.ATTENTION_TAB_ID
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon
import com.superiorsoftllc.agenthq.ui.shell.LocalShellContext
import com.superiorsoftllc.agenthq.ui.shell.NoAttention

/** The Attention tab (order 30 in the right strip, shortcut 6). Its requests go through the plugin API's attention context. */
private fun attentionTab() = TabProvider(
    id = ATTENTION_TAB_ID, container = TabContainers.RIGHT, order = 30,
    title = { s -> "Attention ${s.attentionOpenCount.takeIf { it > 0 }?.let { "($it)" } ?: ""} ^6" },
    icon = tabIcon(
        "attention",
        { moveTo(6f, 16f); lineTo(6f, 11f); curveTo(6f, 7f, 8.5f, 5f, 12f, 5f); curveTo(15.5f, 5f, 18f, 7f, 18f, 11f); lineTo(18f, 16f); lineTo(20f, 18f); lineTo(4f, 18f); close() },
        { moveTo(10f, 21f); lineTo(14f, 21f) },
    ),
    testTag = "right-tab-ATTENTION",
    shortcut = 6,
    routes = listOf("ackAttention", "answerQuestion", "select"),
) { AttentionPane(it.state, LocalShellContext.current?.attention ?: NoAttention, it.showChat) }

/** PLUGINS-1: Infantry_Attention (official since RUNTIME-13), what needs the owner, compiled in on web and Android (D9). */
val InfantryAttention = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Attention", version = "1.0.0", author = "Infantry AI", description = "What needs the owner.",
        tabs = listOf(TabDeclaration(ATTENTION_TAB_ID, TabContainers.RIGHT)),
    ),
    listOf(attentionTab()),
)

/**
 * RUNTIME-13: the entry of Infantry_Attention (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Attention`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class AttentionPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(attentionTab())
    }

    override fun modules(): List<AgentHqModule> = listOf(InfantryAttention)
}
