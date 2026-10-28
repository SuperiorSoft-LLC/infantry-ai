package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import androidx.compose.runtime.Composable
import com.superiorsoftllc.agenthq.plugin.api.ConfigDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.LocalTabFactory
import com.superiorsoftllc.agenthq.ui.coreShell
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.SubTabHost
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabContext
import com.superiorsoftllc.agenthq.ui.plugin.TabGroups
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.circle
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** SETTINGS-TAB-1: the Settings host tab's id; a tab joins it by naming it [TabProvider.parent]. */
const val SETTINGS_TAB_ID = "infantry.settings"

/**
 * SETTINGS-TAB-1: core module Infantry_Settings. A host tab: the tabs that name it their parent (Profiles, Triggers,
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
    listOf(
        TabProvider(
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
        ) { SettingsTab(it) },
    ),
)

@Composable
private fun SettingsTab(context: TabContext) {
    val ui = coreShell?.ui
    SubTabHost(LocalTabFactory.current, SETTINGS_TAB_ID, context, ui?.subTabs?.get(SETTINGS_TAB_ID)) { picked ->
        ui?.subTabs?.set(SETTINGS_TAB_ID, picked)
    }
}

/** RUNTIME-1: the entry of Infantry_Settings (module.json `entry`): hands over this module alone. */
class SettingsModuleSource : ModuleSource {
    override fun modules(): List<AgentHqModule> = listOf(InfantrySettings)
}
