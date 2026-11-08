// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official

import com.superiorsoftllc.infantry.ui.shell.LocalPlatformHost
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import androidx.compose.runtime.Composable
import com.superiorsoftllc.infantry.plugin.api.ConfigDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldDeclaration
import com.superiorsoftllc.infantry.plugin.api.ConfigFieldTypes
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.TabDeclaration
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabContext
import com.superiorsoftllc.infantry.ui.plugin.TabGroups
import com.superiorsoftllc.infantry.ui.plugin.TabProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.circle
import com.superiorsoftllc.infantry.ui.plugin.tabIcon

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

/** PLUGIN-UI-1: Settings > Plugins, a sub-tab of Settings; it draws nothing when the host has no plugin manager. */
private fun pluginsTab() = TabProvider(
    id = PLUGINS_TAB_ID, container = TabContainers.CENTER, order = 90,
    title = { "Plugins" },
    icon = tabIcon("plugins", { moveTo(5f, 5f); lineTo(19f, 5f); lineTo(19f, 19f); lineTo(5f, 19f); close() }),
    testTag = "center-tab-PLUGINS", parent = SETTINGS_TAB_ID,
) {
    val port = LocalPlatformHost.current.pluginManager
    if (port != null) PluginsScreen(port)
}

/**
 * SETTINGS-TAB-1: Infantry_Settings (official since RUNTIME-5) as a compiled-in module (web and Android, D9; the manifest
 * mirrors module.json). A host tab: the tabs that name it their parent (Profiles, Triggers,
 * Notes, Run, Git) draw as its sub-tabs while its `children.grouped` switch is on, and stay in the centre strip (with
 * this tab hidden) while it is off, so the owner can compare the two layouts.
 */
val InfantrySettings = InfantryModule(
    ModuleManifest(
        name = "Infantry_Settings", version = "1.0.0", author = "Infantry AI",
        description = "The Settings tab: groups the setup tabs (Profiles, Triggers, Notes, Run, Git) under one.",
        tabs = listOf(TabDeclaration(SETTINGS_TAB_ID, TabContainers.CENTER)),
        config = listOf(
            ConfigDeclaration(
                SETTINGS_TAB_ID, "Tabs",
                listOf(ConfigFieldDeclaration(TabGroups.GROUPED, ConfigFieldTypes.CHECKBOX, "Group the setup tabs under Settings")),
                tab = "Appearance", order = 60, title = "Tab groups", keywords = listOf("settings", "tabs", "group", "sub-tabs", "strip"),
                block = "infantry.shell.config.tabGroups",
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
    // The Plugins screen is desktop only: the loader takes this UiPlugin; web and Android compile in `modules()`, which has no such tab.
    override fun register(registrar: UiRegistrar) {
        registrar.tab(settingsTab())
        registrar.tab(pluginsTab())
        // UI-PLUGIN-BRIDGE-1: `plugins.apply` (module.json `capabilities`, runs client): the daemon, a script or an agent makes this
        // window apply the plugins root as it is now, live, through the swap the Plugins tab starts (one shared call site).
        registrar.capability(APPLY_CAPABILITY) { args -> applyPluginsLive(args, com.superiorsoftllc.infantry.ui.shell.PluginLifecycleBridge.current, com.superiorsoftllc.infantry.ui.shell.PluginLifecycleBridge.manager) }
    }

    override fun modules(): List<InfantryModule> = listOf(InfantrySettings)
}

/** UI-PLUGIN-BRIDGE-1: the id of this window's live-apply capability (module.json `capabilities`). */
const val APPLY_CAPABILITY = "plugins.apply"

/**
 * UI-PLUGIN-BRIDGE-1 / PLUGIN-APPLY-2: runs the shell's live plugin swap for a `plugins.apply` call and answers
 * `{applied, message, warnings}`. With `id` and `action` (`enable` / `disable`) it first changes that plugin's state through the
 * window's plugin manager ([manager]), then swaps: one call does the whole job. With neither it only swaps whatever changed in
 * the plugins root. An enable / disable that cannot be carried out (no id, no manager, a refusal) fails the call with the reason
 * and swaps nothing. Throws when the window has no lifecycle, so the caller gets the reason.
 */
internal fun applyPluginsLive(
    args: kotlinx.serialization.json.JsonObject,
    lifecycle: com.superiorsoftllc.infantry.ui.shell.PluginLifecycle?,
    manager: com.superiorsoftllc.infantry.plugin.api.PluginManagerPort? = null,
): kotlinx.serialization.json.JsonObject {
    val l = lifecycle ?: throw IllegalStateException("this window has no plugin lifecycle (no live swap here)")
    val id = (args["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
    val action = (args["action"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val on = when (action) { "enable" -> true; "disable" -> false; else -> null } // install / update / other: only a label, the swap applies what changed
    if (on != null) {
        if (id == null) throw IllegalArgumentException("action '$action' needs an id: the plugin to $action")
        val m = manager ?: throw IllegalStateException("this window has no plugin manager: run `infantry plugin $action $id` first, then call plugins.apply without an action")
        val already = m.list().firstOrNull { it.id == id }?.enabled
        if (already != on) {
            val r = m.setEnabled(id, on)
            if (!r.ok) throw IllegalStateException("could not $action $id: ${r.message}")
        }
    }
    val message = l.apply()
    return kotlinx.serialization.json.buildJsonObject {
        put("applied", kotlinx.serialization.json.JsonPrimitive(true))
        put("message", kotlinx.serialization.json.JsonPrimitive(message))
        id?.let { put("id", kotlinx.serialization.json.JsonPrimitive(it)) }
        action?.let { put("action", kotlinx.serialization.json.JsonPrimitive(it)) }
        put("warnings", kotlinx.serialization.json.JsonArray(l.warnings.values.map { kotlinx.serialization.json.JsonPrimitive(it) }))
    }
}
