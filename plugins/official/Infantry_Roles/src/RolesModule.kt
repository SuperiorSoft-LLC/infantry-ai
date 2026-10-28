package com.superiorsoftllc.agenthq.plugins.official.roles

import androidx.compose.runtime.remember
import com.superiorsoftllc.agenthq.plugin.api.FillDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.ConfigEntry
import com.superiorsoftllc.agenthq.ui.ConfigRolesSection
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.plugin.circle
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon
import com.superiorsoftllc.agenthq.ui.shell.LocalShellContext
import com.superiorsoftllc.agenthq.ui.shell.blocks.ConfigSectionBlock

/** The Roles tab's id. */
const val ROLES_TAB_ID = "infantry.roles"

/** The roles window's empty shell slot (Configuration > Roles > Manage roles), which this module fills itself. */
const val ROLES_FILL_ID = "infantry.shell.roles"

/** RUNTIME-19: the Roles section of the Configuration dialog, which this module fills (`infantry.shell.config.roles`). */
const val ROLES_CONFIG_ID = "infantry.shell.config.roles"

private val rolesSection = ConfigSectionBlock(ConfigEntry.ROLES, "roles") { c, m -> ConfigRolesSection(c, m) }

/**
 * The Roles tab (order 30 in the center strip, shortcut 5). Everything it reads and sends goes through the plugin API's
 * shell context domains ([RolesDeps]); drawn outside a shell it shows the tab host's state with no requests.
 */
private fun rolesTab() = TabProvider(
    id = ROLES_TAB_ID, container = TabContainers.CENTER, order = 30,
    title = { "Roles  ^5" },
    icon = tabIcon(
        "roles",
        { circle(12f, 8f, 4f) },
        { moveTo(4f, 21f); curveTo(4f, 16f, 8f, 14f, 12f, 14f); curveTo(16f, 14f, 20f, 16f, 20f, 21f) },
    ),
    testTag = "center-tab-ROLES",
    shortcut = 5,
    routes = listOf("saveRole", "saveRoleBinding", "saveProfile", "deleteProfile", "spawnAs", "select"),
) { host ->
    val ctx = LocalShellContext.current
    val deps = remember(ctx, host.state) { if (ctx != null) RolesDeps(ctx) else RolesDeps(host.state) }
    RolesPane(deps, onSessionStarted = host.showChat, onOpenAgent = host::openAgent)
}

/** PLUGINS-1: Infantry_Roles (official since RUNTIME-14), the Roles tab and the roles window, compiled in on web and Android (D9). */
val InfantryRoles = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Roles", version = "1.0.0", author = "Infantry AI", description = "Roles: who does what, and starting a session as one.",
        tabs = listOf(TabDeclaration(ROLES_TAB_ID, TabContainers.CENTER)),
        fills = listOf(FillDeclaration(ROLES_FILL_ID), FillDeclaration(ROLES_CONFIG_ID)),
    ),
    listOf(rolesTab()),
    fills = mapOf(ROLES_FILL_ID to ShellRoles, ROLES_CONFIG_ID to rolesSection),
)

/**
 * RUNTIME-14: the entry of Infantry_Roles (module.json `entry`), an official module. It registers the Roles tab and fills
 * the roles window's slot (`infantry.shell.roles`, formerly filled by Infantry_AppUi).
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Roles`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class RolesPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(rolesTab())
        registrar.fill(ROLES_FILL_ID, ShellRoles)
        registrar.fill(ROLES_CONFIG_ID, rolesSection)
    }

    override fun modules(): List<AgentHqModule> = listOf(InfantryRoles)
}
