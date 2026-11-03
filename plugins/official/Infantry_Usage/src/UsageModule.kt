package com.superiorsoftllc.infantry.plugins.official.usage

import com.superiorsoftllc.infantry.plugin.api.FillDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.TabDeclaration
import com.superiorsoftllc.infantry.ui.ConfigEntry
import com.superiorsoftllc.infantry.ui.ConfigLimitsSection
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.tabIcon
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import com.superiorsoftllc.infantry.ui.shell.NoUsage
import com.superiorsoftllc.infantry.ui.shell.blocks.ConfigSectionBlock

/** The Usage tab's id. */
const val USAGE_TAB_ID = "infantry.usage"

/** The Usage tab (order 40 in the right strip, Ctrl+7). Its requests go through the plugin API's usage context. */
private fun usageTab() = TabProvider(
    id = USAGE_TAB_ID, container = TabContainers.RIGHT, order = 40,
    title = { "Usage ^7" },
    icon = tabIcon(
        "usage",
        { moveTo(4f, 17f); arcTo(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, x1 = 20f, y1 = 17f) },
        { moveTo(12f, 15f); lineTo(16f, 9f) },
    ),
    testTag = "right-tab-USAGE",
    shortcut = 7,
) { UsagePane(it.state, it.state.selected, LocalShellContext.current?.usage?.takeIf { u -> u !== NoUsage }) }

/** RUNTIME-19: the Limits section of the Configuration dialog, which this module fills (`infantry.shell.config.limits`). */
const val LIMITS_CONFIG_ID = "infantry.shell.config.limits"

private val limitsSection = ConfigSectionBlock(ConfigEntry.LIMITS, "limits") { c, m -> ConfigLimitsSection(c, m) }

/** PLUGINS-1: Infantry_Usage (official since RUNTIME-10), plan usage and limits, as a compiled-in module (web and Android, D9). */
val InfantryUsage = InfantryModule(
    ModuleManifest(
        name = "Infantry_Usage", version = "1.0.0", author = "Infantry AI", description = "Plan usage and limits.",
        tabs = listOf(TabDeclaration(USAGE_TAB_ID, TabContainers.RIGHT)),
        fills = listOf(FillDeclaration(LIMITS_CONFIG_ID)),
    ),
    listOf(usageTab()),
    fills = mapOf(LIMITS_CONFIG_ID to limitsSection),
)

/**
 * RUNTIME-10: the entry of Infantry_Usage (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Usage`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class UsagePlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(usageTab())
        registrar.fill(LIMITS_CONFIG_ID, limitsSection)
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryUsage)
}
