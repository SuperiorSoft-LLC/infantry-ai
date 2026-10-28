package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.SkillsPane
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon

/** PLUGINS-1: core module Infantry_Skills. Saved skills to insert into the chat. */
val InfantrySkills = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Skills", version = "1.0.0", author = "Infantry AI", description = "Saved skills to insert into the chat.",
        tabs = listOf(TabDeclaration("infantry.skills", TabContainers.RIGHT)),
    ),
    listOf(
        TabProvider(
            id = "infantry.skills", container = TabContainers.RIGHT, order = 60,
            title = { "Skills" },
            icon = tabIcon(
                "skills",
                { moveTo(12f, 3f); lineTo(14.6f, 9f); lineTo(21f, 9.5f); lineTo(16f, 13.8f); lineTo(17.6f, 20f); lineTo(12f, 16.7f); lineTo(6.4f, 20f); lineTo(8f, 13.8f); lineTo(3f, 9.5f); lineTo(9.4f, 9f); close() },
            ),
            testTag = "right-tab-SKILLS",
            routes = listOf("loadSkills", "saveSkill", "deleteSkill"),
        ) { SkillsPane(it.state, it.appController, onInsert = it.insertIntoChat) },
    ),
)

/** RUNTIME-1: the entry of Infantry_Skills (module.json `entry`): hands over this module alone. */
class SkillsModuleSource : ModuleSource {
    override fun modules(): List<AgentHqModule> = listOf(InfantrySkills)
}
