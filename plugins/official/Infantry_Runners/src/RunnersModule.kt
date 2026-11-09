// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.runners

import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.infantry.plugin.api.ConfigScopes
import com.superiorsoftllc.infantry.plugin.api.ConfigVisibleWhen
import com.superiorsoftllc.infantry.plugin.api.FillDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import kotlinx.serialization.json.JsonPrimitive

private val rowsCard = RunnersCardBlock()

/** Infantry_Runners as a compiled-in module (web and Android, D9): the manifest mirrors module.json, form card included (CI-TESTS-2: ModuleConfigDriftTest pins them equal). */
val InfantryRunners = InfantryModule(
    ModuleManifest(
        name = "Infantry_Runners", version = "1.0.0", author = "Infantry AI", description = "The Runners settings card and its rows.",
        fills = listOf(FillDeclaration(RUNNERS_CARD_NODE)),
        config = listOf(
            ConfigDeclaration(node = "infantry.runners", group = "Runners", title = "Runners", tab = "Settings", order = 910,
                applies = "Applies to: where ticket workers run. Off = every worker runs on this machine, as before.",
                keywords = listOf("runner", "runners", "server", "remote", "placement", "workers", "any"),
                fields = listOf(
                    ConfigFieldDeclaration("runners.enabled", ConfigFieldTypes.TOGGLE, "Use runners", JsonPrimitive(false), scope = ConfigScopes.DAEMON, order = 1,
                        description = "Off = every worker runs on this machine and this card shows nothing else."),
                    ConfigFieldDeclaration("runners.default", ConfigFieldTypes.TEXT, "Default placement", JsonPrimitive("local"), scope = ConfigScopes.DAEMON, order = 2,
                        placeholder = "local | <runner name> | any", visibleWhen = ConfigVisibleWhen("runners.enabled", "true"),
                        description = "Where a ticket with no pin and no routing rule starts. `any` picks the runner that is up, has the project and " +
                            "harness, and has the most free slots (ties go to local); it waits when none is free."),
                    ConfigFieldDeclaration("runners.local.maxWorkers", ConfigFieldTypes.NUMBER, "Local worker cap", JsonPrimitive(1), min = 1.0, scope = ConfigScopes.DAEMON, order = 3,
                        visibleWhen = ConfigVisibleWhen("runners.enabled", "true"),
                        description = "Most workers this machine runs at once, under the global worker cap. Other runners: set runners.<name>.maxWorkers."),
                ),
            ),
            ConfigDeclaration(node = RUNNERS_CARD_NODE, group = "Runners", tab = "Settings", order = 911, title = "Runners",
            // RUNNER-ADD-UI-1: a new owner searches for how to add (pair) a runner and for the check, not only for pause.
            keywords = listOf("runner", "add", "pair", "pairing", "link", "connect", "check", "health", "fix", "pause", "resume", "detach", "rotate", "fingerprint"),
            block = RUNNERS_CARD_NODE),
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
