// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.stats

import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.TabDeclaration
import com.superiorsoftllc.infantry.ui.plugin.InfantryModule
import com.superiorsoftllc.infantry.ui.plugin.ModuleSource
import com.superiorsoftllc.infantry.ui.plugin.TabContainers
import com.superiorsoftllc.infantry.ui.plugin.TabProvider
import com.superiorsoftllc.infantry.ui.plugin.UiPlugin
import com.superiorsoftllc.infantry.ui.plugin.UiRegistrar
import com.superiorsoftllc.infantry.ui.plugin.tabIcon
import com.superiorsoftllc.infantry.ui.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.infantry.ui.shell.LocalShellContext
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import com.superiorsoftllc.infantry.ui.block.layout.Block
import com.superiorsoftllc.infantry.ui.block.layout.BlockScope
import com.superiorsoftllc.infantry.ui.block.layout.Place
import com.superiorsoftllc.infantry.ui.block.layout.RenderPlaced
import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.shell.UsageContext
import com.superiorsoftllc.infantry.ui.shell.UsageMeterContext
import com.superiorsoftllc.infantry.ui.shell.NoUsage
import com.superiorsoftllc.infantry.ui.shell.NoUsageMeter

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
    StatsTab(it.state, ctx?.usage ?: NoUsage, ctx?.meter ?: NoUsageMeter)
}

/** The Stats pane as a parent node (declared first: the module value below reads it at class load). */
val ShellStats = StatsPaneBlock(listOf(StatsBodyBlock))

/** PLUGINS-1: Infantry_Stats (official since RUNTIME-10), the selected session's statistics, compiled in on web and Android (D9). */
val InfantryStats = InfantryModule(
    ModuleManifest(
        name = "Infantry_Stats", version = "1.0.0", author = "Infantry AI", description = "The selected session's statistics.",
        tabs = listOf(TabDeclaration(STATS_TAB_ID, TabContainers.CENTER)),
    ),
    listOf(statsTab()),
    tabTrees = mapOf(STATS_TAB_ID to ShellStats),
)

/**
 * RUNTIME-10: the entry of Infantry_Stats (module.json `entry`), an official module.
 *  - Desktop loads it at run time from its signed jar (`official/Infantry_Stats`): the loader takes it as a [UiPlugin].
 *  - Web and Android compile it in from the generated module list, which takes it as a [ModuleSource].
 */
class StatsPlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(statsTab())
        registrar.tabTree(STATS_TAB_ID, ShellStats)
    }

    override fun modules(): List<InfantryModule> = listOf(InfantryStats)
}

/** Node id of the Stats tab's tree: a plugin injects a section into it (`"inject": [{"into": "infantry.shell.stats"}]`). */
const val STATS_NODE = "infantry.shell.stats"

private class StatsHost(val state: AppState, val usage: UsageContext, val meter: UsageMeterContext)

private val LocalStatsHost = compositionLocalOf<StatsHost?> { null }

/**
 * USAGE-FIT (parent/child rule): the Stats tab is a PARENT. Its content is the child `infantry.shell.stats.body` (weight 1,
 * takes the room) followed by whatever other plugins inject into [STATS_NODE], each drawn below it with its own placement.
 * With nothing injected the tree is the body alone: the tab draws what it drew before.
 */
class StatsPaneBlock(children: List<Block>, place: Place = Place()) : Block("statsPane", STATS_NODE, place, children) {
    @Composable
    override fun Render(scope: BlockScope) {
        Column(scope.modifier.fillMaxSize()) { RenderPlaced(children) }
    }

    override fun withChildren(children: List<Block>) = StatsPaneBlock(children, place)
}

/** The Stats tab's existing content (Session | Tokens by day/week), unchanged, as the first child. */
object StatsBodyBlock : Block("statsBody", "$STATS_NODE.body", Place(weight = 1f)) {
    @Composable
    override fun Render(scope: BlockScope) {
        val h = LocalStatsHost.current ?: return
        Box(scope.modifier) { StatsTabContent(h.state, h.usage, h.meter) }
    }
}


/** The tab: [ShellStats] as the window resolved it (injections in), over the tab's state. */
@Composable
private fun StatsTab(state: AppState, usage: UsageContext, meter: UsageMeterContext) {
    CompositionLocalProvider(LocalStatsHost provides StatsHost(state, usage, meter)) {
        (LocalShellContext.current?.layout?.tabTree(STATS_NODE) ?: ShellStats).Render(BlockScope(Modifier, null))
    }
}

/** The Stats tab: the selected session's statistics, or (USAGE-1) the daemon's token meter by day / week. */
@androidx.compose.runtime.Composable
private fun StatsTabContent(state: com.superiorsoftllc.infantry.ui.model.AppState, usage: com.superiorsoftllc.infantry.ui.shell.UsageContext, meter: com.superiorsoftllc.infantry.ui.shell.UsageMeterContext) {
    var daily by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Row(androidx.compose.ui.Modifier.padding(start = 14.dp, top = 8.dp)) {
            for ((label, on) in listOf("Session" to !daily, "Tokens by day/week" to daily)) {
                androidx.compose.material3.Text(
                    label, color = if (on) com.superiorsoftllc.infantry.ui.Palette.Accent else com.superiorsoftllc.infantry.ui.Palette.Muted, fontSize = 11.sp,
                    modifier = androidx.compose.ui.Modifier.testTag("stats-mode-$label").clickable { daily = label != "Session" }.padding(end = 14.dp),
                )
            }
        }
        if (daily) UsageMeterPane(state, meter) else StatsPane(state, usage)
    }
}
