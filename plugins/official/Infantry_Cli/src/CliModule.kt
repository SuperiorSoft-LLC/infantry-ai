package com.superiorsoftllc.infantry.plugins.official

import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.TabDeclaration
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.tabIcon

/** The CLI tab: the selected agent's chat transcript, first in the centre strip (Ctrl+1). */
private fun cliTab() = TabProvider(
    id = "infantry.cli", container = TabContainers.CENTER, order = 10,
    title = { "CLI  ^1" },
    icon = tabIcon(
        "cli",
        { moveTo(5f, 7f); lineTo(10f, 12f); lineTo(5f, 17f) },
        { moveTo(12f, 17f); lineTo(19f, 17f) },
    ),
    testTag = "center-tab-OUTPUT",
    shortcut = 1,
) { it.transcript() }

/** Infantry_Cli as a compiled-in module (web and Android, D9): the manifest mirrors module.json. */
val InfantryCli = InfantryModule(
    ModuleManifest(
        name = "Infantry_Cli", version = "1.0.0", author = "Infantry AI", description = "The selected agent's chat transcript.",
        tabs = listOf(TabDeclaration("infantry.cli", TabContainers.CENTER)),
    ),
    listOf(cliTab()),
)

/**
 * RUNTIME-3: the entry of Infantry_Cli (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Cli`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class CliPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) = registrar.tab(cliTab())

    override fun modules(): List<InfantryModule> = listOf(InfantryCli)
}
