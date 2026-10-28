package com.superiorsoftllc.agenthq.plugins.core

import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.ui.plugin.TabContext
import com.superiorsoftllc.agenthq.ui.plugin.TabFactory

/**
 * PLUGINS-1: the core modules compiled into every build (desktop, web, Android), the way Magento's registration
 * lists a module. Add a core module by adding its folder under plugins/core and its value here. Community modules
 * are never named here: the loader finds them by scanning plugins/community. S4: they are compiled into the app UI
 * (:agenthqAppUi), which the shell finds through [CoreModuleSource]; Infantry_AppUi fills the shell's empty slots.
 */
object CoreModules {
    val all: List<AgentHqModule> = listOf(
        InfantryAgents,
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
        InfantrySettings,
        InfantryChanges,
        InfantryReview,
        InfantryAttention,
        InfantryUsage,
        InfantryPrompts,
        InfantrySkills,
        InfantryBoard,
        InfantryAppUi,
    )

    val factory: TabFactory by lazy { TabFactory(all) }
}

/**
 * S4 (docs/SHELL-LEAKS.md): the window's controller in a core module's tab. The shell gives it to core-tier contexts
 * only ([TabContext.coreController]); every module here is core tier, so a null is a wiring bug, not a refusal.
 */
internal val TabContext.appController: Controller
    get() = checkNotNull(coreController) { "a core module's tab got a community module's context" }

/** S4: hands [CoreModules] to the shell (`META-INF/services/com.superiorsoftllc.agenthq.ui.plugin.ModuleSource`). */
class CoreModuleSource : ModuleSource {
    override fun modules(): List<AgentHqModule> = CoreModules.all
}
