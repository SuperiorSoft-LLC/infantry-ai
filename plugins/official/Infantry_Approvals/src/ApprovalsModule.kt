package com.superiorsoftllc.agenthq.plugins.official.approvals

import com.superiorsoftllc.agenthq.plugin.api.FillDeclaration
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.plugins.official.approvals.ShellQuestionPill

private const val QUESTIONS_FILL_ID = "infantry.shell.questions"

/** Infantry_Approvals as a compiled-in module (web and Android, D9): the manifest mirrors module.json. */
val InfantryApprovals = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Approvals", version = "1.0.0", author = "Infantry AI",
        description = "The questions pill: open owner questions the owner cannot see inline, one click from the asking chat.",
        fills = listOf(FillDeclaration(QUESTIONS_FILL_ID)),
    ),
    fills = mapOf(QUESTIONS_FILL_ID to ShellQuestionPill),
)

/**
 * RUNTIME-23 (F7): the entry of Infantry_Approvals (module.json `entry`), an official module. The daemon stays the gate:
 * the pill only shows and cancels through the existing `questions` context; answers and enforcement are the daemon's.
 *  - Desktop loads it at run time from its signed jar; the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class ApprovalsPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.fill(QUESTIONS_FILL_ID, ShellQuestionPill)
    }

    override fun modules(): List<AgentHqModule> = listOf(InfantryApprovals)
}
