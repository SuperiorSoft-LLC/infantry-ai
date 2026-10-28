package com.superiorsoftllc.agenthq.plugins.official

import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.plugin.api.ConfigDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabContext
import com.superiorsoftllc.agenthq.ui.plugin.TabGroups
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.plugin.circle
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** SETTINGS-TAB-1: the Settings host tab's id; a tab joins it by naming it [TabProvider.parent]. */
const val SETTINGS_TAB_ID = "infantry.settings"

/** The Settings host tab (order 200 in the centre strip). */
private fun settingsTab() = TabProvider(
    id = SETTINGS_TAB_ID, container = TabContainers.CENTER, order = 200,
    title = { "Settings" },
    icon = tabIcon(
        "settings",
        { circle(12f, 12f, 3f) },
        { circle(12f, 12f, 7f) },
        { moveTo(12f, 2f); lineTo(12f, 5f) },
        { moveTo(12f, 19f); lineTo(12f, 22f) },
        { moveTo(2f, 12f); lineTo(5f, 12f) },
        { moveTo(19f, 12f); lineTo(22f, 12f) },
        { moveTo(4.9f, 4.9f); lineTo(7f, 7f) },
        { moveTo(17f, 17f); lineTo(19.1f, 19.1f) },
        { moveTo(4.9f, 19.1f); lineTo(7f, 17f) },
        { moveTo(17f, 7f); lineTo(19.1f, 4.9f) },
    ),
    testTag = "center-tab-SETTINGS",
) { SettingsTab(it) }

/**
 * SETTINGS-TAB-1: Infantry_Settings (official since RUNTIME-5) as a compiled-in module (web and Android, D9; the manifest
 * mirrors module.json). A host tab: the tabs that name it their parent (Profiles, Triggers,
 * Notes, Run, Git) draw as its sub-tabs while its `children.grouped` switch is on, and stay in the centre strip (with
 * this tab hidden) while it is off, so the owner can compare the two layouts.
 */
val InfantrySettings = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Settings", version = "1.0.0", author = "Infantry AI",
        description = "The Settings tab: groups the setup tabs (Profiles, Triggers, Notes, Run, Git) under one.",
        tabs = listOf(TabDeclaration(SETTINGS_TAB_ID, TabContainers.CENTER)),
        config = listOf(
            ConfigDeclaration(
                SETTINGS_TAB_ID, "Tabs",
                listOf(ConfigFieldDeclaration(TabGroups.GROUPED, ConfigFieldTypes.CHECKBOX, "Group the setup tabs under Settings")),
            ),
        ),
    ),
    listOf(settingsTab()),
)

/** RUNTIME-5: the picked sub-tab lives in the window and is reached through the plugin API (no core-only shell or factory). */
@Composable
private fun SettingsTab(context: TabContext) = context.SubTabs(SETTINGS_TAB_ID)

/**
 * RUNTIME-5: the entry of Infantry_Settings (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Settings`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class SettingsPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) = registrar.tab(settingsTab())

    override fun modules(): List<AgentHqModule> = listOf(InfantrySettings)
}
