package com.superiorsoftllc.agenthq.plugins.official.stats

import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon
import com.superiorsoftllc.agenthq.ui.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.ui.shell.LocalShellContext
import com.superiorsoftllc.agenthq.ui.shell.NoUsage
import com.superiorsoftllc.agenthq.ui.shell.NoUsageMeter

/** The Stats tab's id. */
const val STATS_TAB_ID = "infantry.stats"

/** The Stats tab (order 100 in the centre strip). It polls through the plugin API's usage context. */
private fun statsTab() = TabProvider(
    id = STATS_TAB_ID, container = TabContainers.CENTER, order = 100,
    title = { "Stats" },
    icon = tabIcon(
        "stats",
        { moveTo(3f, 21f); lineTo(21f, 21f) },
        { moveTo(6f, 17f); lineTo(6f, 12f) },
        { moveTo(12f, 17f); lineTo(12f, 5f) },
        { moveTo(18f, 17f); lineTo(18f, 9f) },
    ),
    testTag = "center-tab-STATS",
    routes = listOf("requestSessionStats", "requestUsageMeter"),
) {
    val ctx = LocalShellContext.current
    StatsTabContent(it.state, ctx?.usage ?: NoUsage, ctx?.meter ?: NoUsageMeter)
}

/** PLUGINS-1: Infantry_Stats (official since RUNTIME-10), the selected session's statistics, compiled in on web and Android (D9). */
val InfantryStats = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Stats", version = "1.0.0", author = "Infantry AI", description = "The selected session's statistics.",
        tabs = listOf(TabDeclaration(STATS_TAB_ID, TabContainers.CENTER)),
    ),
    listOf(statsTab()),
)

/**
 * RUNTIME-10: the entry of Infantry_Stats (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Stats`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class StatsPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(statsTab())
    }

    override fun modules(): List<AgentHqModule> = listOf(InfantryStats)
}

/** The Stats tab: the selected session's statistics, or (USAGE-1) the daemon's token meter by day / week. */
@androidx.compose.runtime.Composable
private fun StatsTabContent(state: com.superiorsoftllc.agenthq.ui.model.AppState, usage: com.superiorsoftllc.agenthq.ui.shell.UsageContext, meter: com.superiorsoftllc.agenthq.ui.shell.UsageMeterContext) {
    var daily by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.padding(start = 14.dp, top = 8.dp)) {
            for ((label, on) in listOf("Session" to !daily, "Tokens by day/week" to daily)) {
                androidx.compose.material3.Text(
                    label, color = if (on) com.superiorsoftllc.agenthq.ui.Palette.Accent else com.superiorsoftllc.agenthq.ui.Palette.Muted, fontSize = 11.sp,
                    modifier = androidx.compose.ui.Modifier.testTag("stats-mode-$label").clickable { daily = label != "Session" }.padding(end = 14.dp),
                )
            }
        }
        if (daily) UsageMeterPane(state, meter) else StatsPane(state, usage)
    }
}
