package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory

/**
 * PLUGINS-1: the core modules compiled into every build (desktop, web, Android), the way Magento's registration
 * lists a module. Add a core module by adding its folder under plugins/core and its value here. Community modules
 * are never named here: the loader finds them by scanning plugins/community.
 */
object CoreModules {
    val all: List<AgentHqModule> = listOf(
        InfantryCli,
        InfantryPods,
        InfantryRoles,
        InfantryProfiles,
        InfantryTriggers,
        InfantryEpics,
        InfantryNotes,
        InfantryRun,
        InfantryGit,
        InfantryStats,
        InfantryChanges,
        InfantryReview,
        InfantryAttention,
        InfantryUsage,
        InfantryPrompts,
        InfantrySkills,
    )

    val factory: TabFactory by lazy { TabFactory(all) }
}
