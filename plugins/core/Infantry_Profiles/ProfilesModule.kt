package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.ProfilesPane
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.circle
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** PLUGINS-1: core module Infantry_Profiles. Saved agent profiles. */
val InfantryProfiles = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Profiles", version = "1.0.0", author = "Infantry AI", description = "Saved agent profiles.",
        tabs = listOf(TabDeclaration("infantry.profiles", TabContainers.CENTER)),
    ),
    listOf(
        TabProvider(
            id = "infantry.profiles", container = TabContainers.CENTER, order = 40,
            title = { "Profiles  ^9" },
            icon = tabIcon(
                "profiles",
                { moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close() },
                { circle(8.5f, 11f, 2f) },
                { moveTo(13f, 10f); lineTo(18f, 10f) },
                { moveTo(13f, 14f); lineTo(18f, 14f) },
            ),
            testTag = "center-tab-PROFILES",
            parent = SETTINGS_TAB_ID,
            shortcut = 9,
            routes = listOf("saveProfile", "deleteProfile", "spawnAs"),
        ) { ProfilesPane(it.state, it.appController, onSessionStarted = it.showChat) },
    ),
)

/** RUNTIME-1: the entry of Infantry_Profiles (module.json `entry`): hands over this module alone. */
class ProfilesModuleSource : ModuleSource {
    override fun modules(): List<AgentHqModule> = listOf(InfantryProfiles)
}
