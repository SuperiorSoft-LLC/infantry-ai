package com.superiorsoftllc.agenthq.plugins.official.pace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superiorsoftllc.agenthq.plugin.api.ModuleManifest
import com.superiorsoftllc.agenthq.plugin.api.TabDeclaration
import com.superiorsoftllc.agenthq.ui.Palette
import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.plugin.AgentHqModule
import com.superiorsoftllc.agenthq.ui.plugin.ModuleSource
import com.superiorsoftllc.agenthq.ui.plugin.TabContainers
import com.superiorsoftllc.agenthq.ui.plugin.TabProvider
import com.superiorsoftllc.agenthq.ui.plugin.UiPlugin
import com.superiorsoftllc.agenthq.ui.plugin.UiRegistrar
import com.superiorsoftllc.agenthq.ui.plugin.tabIcon
import com.superiorsoftllc.agenthq.ui.shell.LocalShellContext
import com.superiorsoftllc.agenthq.ui.shell.NoPace
import com.superiorsoftllc.agenthq.ui.shell.NoUsageMeter
import com.superiorsoftllc.agenthq.ui.shell.PaceContext
import com.superiorsoftllc.agenthq.ui.shell.UsageMeterContext
import kotlinx.coroutines.delay

/** The Pace tab's id. */
const val PACE_TAB_ID = "infantry.pace"
const val PACE_TAB_CONTENT_TAG = "pace-tab"
const val PACE_STATE_TAG = "pace-state"

/** What the tab asks the daemon for: the last 8 days (the longest range plus a window), thinned to 600 points per series. */
const val PACE_QUERY = """{"sinceHours":192,"maxPoints":600}"""

/** How often the open tab asks again; only while it is composed. The daemon samples about every 5 minutes, so a minute is plenty. */
const val PACE_REFRESH_MS = 60_000L

/** The Pace tab (order 110, right after Stats). Its own tab: it works with Stats present or absent (docs/PACING.md). */
private fun paceTab() = TabProvider(
    id = PACE_TAB_ID, container = TabContainers.CENTER, order = 110,
    title = { "Pace" },
    icon = tabIcon(
        "pace",
        { moveTo(3f, 21f); lineTo(21f, 21f) },
        { moveTo(4f, 17f); lineTo(9f, 11f); lineTo(13f, 14f); lineTo(20f, 5f) },
        { moveTo(16f, 5f); lineTo(20f, 5f); lineTo(20f, 9f) },
    ),
    testTag = "center-tab-PACE",
    routes = listOf("requestPace", "requestUsageMeter"),
) {
    val ctx = LocalShellContext.current
    PaceTabContent(it.state, ctx?.pace ?: NoPace, ctx?.meter ?: NoUsageMeter)
}

/** Infantry_Pace (official, optional), compiled in on web and Android (D9). */
val InfantryPace = AgentHqModule(
    ModuleManifest(
        name = "Infantry_Pace", version = "1.0.0", author = "Infantry AI",
        description = "Pacing trend charts for every harness.",
        tabs = listOf(TabDeclaration(PACE_TAB_ID, TabContainers.CENTER)),
    ),
    listOf(paceTab()),
)

/** The entry of Infantry_Pace (module.json `entry`): desktop loads it from its signed jar as a [UiPlugin]; web and Android compile it in as a [ModuleSource]. */
class PacePlugin : UiPlugin, ModuleSource {
    override fun register(registrar: UiRegistrar) {
        registrar.tab(paceTab())
    }

    override fun modules(): List<AgentHqModule> = listOf(InfantryPace)
}

/** What the tab shows, in order: not connected, waiting, no answer, refused, nothing recorded yet, the cards. One owner of the decision, so the test can assert it. */
sealed interface PaceTabState {
    object Offline : PaceTabState
    object Loading : PaceTabState
    data class Unavailable(val why: String) : PaceTabState
    object Empty : PaceTabState
    data class Ready(val report: PaceReportView) : PaceTabState

    companion object {
        fun of(live: Boolean, raw: String?, waitedLong: Boolean, refused: String?): PaceTabState {
            if (!live) return Offline
            val report = PaceReportParser.parse(raw)
            return when {
                report != null && report.series.isEmpty() -> Empty
                report != null -> Ready(report)
                refused != null -> Unavailable(refused)
                waitedLong -> Unavailable("The daemon did not answer. The pacing plugin (Infantry_Pace daemon half) may be off, or the daemon is older than DaemonApi 1.10.0.")
                else -> Loading
            }
        }
    }
}

@Composable
fun PaceTabContent(state: AppState, pace: PaceContext, meter: UsageMeterContext) {
    val live = state.connection.isLive
    var refused by remember { mutableStateOf<String?>(null) }
    var waitedLong by remember { mutableStateOf(false) }
    // One request when the tab opens and each minute after; the token totals reuse the Stats meter (asked once, no polling of its own).
    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        if (state.usageMeter == null) meter.refresh()
        while (true) {
            val r = pace.refresh(PACE_QUERY)
            refused = (r as? CommandResult.Refused)?.reason
            delay(PACE_REFRESH_MS)
        }
    }
    LaunchedEffect(live) { waitedLong = false; delay(6_000); waitedLong = true }
    val view = PaceTabState.of(live, state.paceReport, waitedLong, refused)
    Box(Modifier.fillMaxSize().testTag(PACE_TAB_CONTENT_TAG)) {
        when (view) {
            PaceTabState.Offline -> Note("Not connected to a daemon.")
            PaceTabState.Loading -> Note("Reading the pacing history...")
            is PaceTabState.Unavailable -> Note(view.why)
            PaceTabState.Empty -> Note("No pacing history recorded yet. The daemon samples the plan meters about every 5 minutes while this plugin is on; the first charts appear after a few readings.")
            is PaceTabState.Ready -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
                Text("PACING TREND", color = Palette.Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Text(
                    "One card per provider window, from that provider's own meter. Pace delta = points ahead (+) or behind (-) an even burn through the window; " +
                        "the heavy line is even pace. Momentum is the slope of where you are projected to land at reset over a short look-back " +
                        "(6 hours by default, set in Settings). Forecast = a projection, not a promise.",
                    color = Palette.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                )
                val markers = PaceSessions.markers(state.usageMeter?.requests.orEmpty(), view.report.generatedAt)
                for (s in view.report.series) {
                    val totals = state.usageMeter?.let { PaceTokens.totals(it.rows, s.provider) }
                    PaceCard(s, view.report.generatedAt, totals, markers)
                }
                PaceSessionsPanel(state.usageMeter?.requests, view.report.series, view.report.generatedAt)
            }
        }
    }
}

@Composable
private fun Note(text: String) = Box(Modifier.fillMaxSize().padding(24.dp).testTag(PACE_STATE_TAG), Alignment.Center) {
    Text(text, color = Palette.Muted, fontSize = 12.sp)
}
