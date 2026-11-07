// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.runners

import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.FillDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar

private val rowsCard = RunnersCardBlock()

/** Infantry_Runners as a compiled-in module (web and Android, D9): the manifest mirrors module.json (the form card stays in module.json). */
val InfantryRunners = InfantryModule(
    ModuleManifest(
        name = "Infantry_Runners", version = "1.0.0", author = "Infantry AI", description = "The Runners settings card and its rows.",
        fills = listOf(FillDeclaration(RUNNERS_CARD_NODE)),
        config = listOf(
            ConfigDeclaration(node = RUNNERS_CARD_NODE, group = "Runners", tab = "Settings", order = 911, title = "Runners", keywords = listOf("runner", "pause", "detach", "rotate"), block = RUNNERS_CARD_NODE),
        ),
    ),
    fills = mapOf(RUNNERS_CARD_NODE to rowsCard),
)

/**
 * RUNNER-POOL-11: the entry of Infantry_Runners (module.json `entry`), an official module: desktop loads it from its signed jar,
 * web and Android compile it in from the generated module list.
 */
class RunnersPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) = registrar.fill(RUNNERS_CARD_NODE, rowsCard)

    override fun modules(): List<InfantryModule> = listOf(InfantryRunners)
}
