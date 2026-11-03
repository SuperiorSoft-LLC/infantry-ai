package com.superiorsoftllc.infantry.plugins.official.profiles

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.TabDeclaration
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.circle
import com.superiorsoftllc.infantry.ui.plugin.tabIcon
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import com.superiorsoftllc.infantry.ui.shell.NoExecutor
import com.superiorsoftllc.infantry.ui.shell.NoRoles

/** The Profiles tab's id. */
const val PROFILES_TAB_ID = "infantry.profiles"

/**
 * The Profiles tab (order 40 in the center strip, shortcut 9, under the official Settings host when grouped). Profile
 * saves, deletes and starts go through the plugin API's roles context; the harness picker reads its executor context.
 */
private fun profilesTab() = TabProvider(
    id = PROFILES_TAB_ID, container = TabContainers.CENTER, order = 40,
    title = { "Profiles  ^9" },
    icon = tabIcon(
        "profiles",
        { moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close() },
        { circle(8.5f, 11f, 2f) },
        { moveTo(13f, 10f); lineTo(18f, 10f) },
        { moveTo(13f, 14f); lineTo(18f, 14f) },
    ),
    testTag = "center-tab-PROFILES",
    parent = "infantry.settings", // the official Infantry_Settings host tab (RUNTIME-5)
    shortcut = 9,
    routes = listOf("saveProfile", "deleteProfile", "spawnAs"),
) {
    val shell = LocalShellContext.current
    ProfilesPane(it.state, shell?.roles ?: NoRoles, shell?.executor ?: NoExecutor, onSessionStarted = it.showChat)
}

/** PLUGINS-1: Infantry_Profiles (official since RUNTIME-12), saved agent profiles, compiled in on web and Android (D9). */
val InfantryProfiles = InfantryModule(
    ModuleManifest(
        name = "Infantry_Profiles", version = "1.0.0", author = "Infantry AI", description = "Saved agent profiles.",
        tabs = listOf(TabDeclaration(PROFILES_TAB_ID, TabContainers.CENTER)),
    ),
    listOf(profilesTab()),
)

/**
 * RUNTIME-12: the entry of Infantry_Profiles (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Profiles`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class ProfilesPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(profilesTab())
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryProfiles)
}
