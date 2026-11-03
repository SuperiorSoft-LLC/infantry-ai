package com.superiorsoftllc.infantry.plugins.official.processes

import androidx.compose.runtime.Composable
import com.superiorsoftllc.infantry.plugin.api.FillDeclaration
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import com.superiorsoftllc.infantry.ui.shell.blocks.PROCESSES_BADGE_SLOT

/** The status bar's processes-badge slot, which this module fills (`infantry.shell.footer.processesBadge`). */
const val PROCESSES_FILL_ID = PROCESSES_BADGE_SLOT

/** ROGUE-1's processes badge (rogue background processes / low memory). RUNTIME-18: reads the shell context's processes domain. */
object ProcessesBadgeBlock : Block("processesBadge", PROCESSES_FILL_ID) {
    @Composable
    override fun Render(scope: BlockScope) {
        val ctx = LocalShellContext.current ?: return
        ProcessesBadge(ctx.link, ctx.processes, scope.modifier)
    }
}

/** RUNTIME-18: Infantry_Processes (official), the status bar's processes badge, compiled in on web and Android (D9). */
val InfantryProcesses = InfantryModule(
    ModuleManifest(
        name = "Infantry_Processes", version = "1.0.0", author = "Infantry AI",
        description = "The status bar's processes badge (free RAM, rogue processes) and its panel.",
        fills = listOf(FillDeclaration(PROCESSES_FILL_ID)),
    ),
    fills = mapOf(PROCESSES_FILL_ID to ProcessesBadgeBlock),
)

/**
 * RUNTIME-18: the entry of Infantry_Processes (module.json `entry`), an official module. It fills the status bar's
 * processes-badge slot (formerly drawn by Infantry_AppUi). Without it the status bar shows no RAM / rogue-process badge.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Processes`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class ProcessesPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.fill(PROCESSES_FILL_ID, ProcessesBadgeBlock)
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryProcesses)
}
