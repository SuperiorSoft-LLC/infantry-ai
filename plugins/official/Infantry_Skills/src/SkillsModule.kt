// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.skills

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.TabDeclaration
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.tabIcon
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext

/** The Skills tab's id. */
const val SKILLS_TAB_ID = "infantry.skills"

/** The Skills tab (order 60 in the right-hand strip). Its pane reads the plugin API contexts (link, skills) only. */
private fun skillsTab() = TabProvider(
    id = SKILLS_TAB_ID, container = TabContainers.RIGHT, order = 60,
    title = { "Skills" },
    icon = tabIcon(
        "skills",
        { moveTo(12f, 3f); lineTo(14.6f, 9f); lineTo(21f, 9.5f); lineTo(16f, 13.8f); lineTo(17.6f, 20f); lineTo(12f, 16.7f); lineTo(6.4f, 20f); lineTo(8f, 13.8f); lineTo(3f, 9.5f); lineTo(9.4f, 9f); close() },
    ),
    testTag = "right-tab-SKILLS",
    routes = listOf("loadSkills", "saveSkill", "deleteSkill"),
) { tab ->
    LocalShellContext.current?.let { ctx -> SkillsPane(ctx.link, ctx.skills, onInsert = tab.insertIntoChat) }
}

/** PLUGINS-1: Infantry_Skills (official since RUNTIME-9), as a compiled-in module (web and Android, D9; the manifest mirrors module.json). */
val InfantrySkills = InfantryModule(
    ModuleManifest(
        name = "Infantry_Skills", version = "1.0.0", author = "Infantry AI", description = "Saved skills to insert into the chat.",
        tabs = listOf(TabDeclaration(SKILLS_TAB_ID, TabContainers.RIGHT)),
    ),
    listOf(skillsTab()),
)

/**
 * RUNTIME-9: the entry of Infantry_Skills (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Skills`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class SkillsPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(skillsTab())
    }

    override fun modules(): List<InfantryModule> = listOf(InfantrySkills)
}
