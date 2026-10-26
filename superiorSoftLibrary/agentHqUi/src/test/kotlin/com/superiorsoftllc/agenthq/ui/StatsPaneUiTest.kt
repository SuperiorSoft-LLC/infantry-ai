package com.superiorsoftllc.agenthq.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.superiorsoftllc.agenthq.ui.model.StatsView
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.net.DaemonClient
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class StatsFakeDaemonClient(scope: CoroutineScope) : DaemonClient(scope) {
    val sent = CopyOnWriteArrayList<Envelope>()
    private val incoming = MutableSharedFlow<Envelope>(extraBufferCapacity = 64)
    override val frames: SharedFlow<Envelope> get() = incoming
    override suspend fun send(envelope: Envelope): Boolean { sent += envelope; return true }
    fun push(e: Envelope) {
        val deadline = System.currentTimeMillis() + 10_000
        while (incoming.subscriptionCount.value == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        check(incoming.tryEmit(e)) { "frame buffer full" }
    }
    fun statsAsks() = sent.filter { it.type == Envelope.SESSION_STATS_GET }
}

/**
 * STATS-2: the Stats tab paints the daemon's `session_stats` frame (contract 1.92.0) for the selected agent. A fake
 * daemon pushes hand-written frames (the wire shape is pinned here, not this client's DTOs): tiles show the values,
 * a null field shows "-", the context gauge goes red past 100%, and the tab strip opens and leaves the pane.
 */
@OptIn(ExperimentalTestApi::class)
class StatsPaneUiTest {
    private val sid = "s-stats-1"
    private val t0 = 1_790_000_000_000L

    private fun sessionsFrame() = Envelope(
        Envelope.SESSIONS,
        """[{"id":"$sid","provider":"claude","workingDir":"/w","spawnedAt":$t0,"lastHeartbeat":$t0,"state":"IDLE"}]""",
    )

    private val fullStats = """
        {"sessionId":"$sid","harness":"claude","providerSessionId":"676e602e-1111-2222-3333-444455556666","status":"idle",
         "startedAt":$t0,"endedAt":${t0 + 131_000_000},"durationMs":131000000,"userPrompts":507,"assistantTurns":2706,
         "toolUses":3300,"models":["opus-5.5"],"lastContextTokens":234310,"modelContextLimit":200000,"billedTokens":480000000,
         "series":[{"at":$t0,"contextTokens":20000,"cumulativeBilledTokens":21000},
                   {"at":${t0 + 60_000},"contextTokens":150000,"cumulativeBilledTokens":180000},
                   {"at":${t0 + 120_000},"contextTokens":234310,"cumulativeBilledTokens":420000}],
         "topSources":[{"tool":"Bash","calls":552,"tokensAdded":593400},{"tool":"Read","calls":120,"tokensAdded":null}],
         "unattributedToolTokens":12000}
    """.trimIndent()

    /** Everything a harness may leave unsaid, left unsaid. */
    private val nullStats = """{"sessionId":"$sid","harness":"pi"}"""

    private fun underStats() = """{"sessionId":"$sid","harness":"opencode","lastContextTokens":100000,"modelContextLimit":200000}"""

    private fun ComposeUiTest.tile(key: String) = onNodeWithTag(STATS_TILE_TAG_PREFIX + key)

    private fun ComposeUiTest.gaugeDescription(): String =
        onNodeWithTag(STATS_GAUGE_TAG).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()

    private fun withPane(width: Int = 760, height: Int = 1100, body: ComposeUiTest.(StatsFakeDaemonClient, Controller) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = StatsFakeDaemonClient(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(width, height) {
                setContent {
                    val state by controller.state.collectAsState()
                    Box(Modifier.fillMaxSize().background(Palette.Bg).testTag("shot")) { StatsPane(state, controller) }
                }
                link.push(Envelope(Envelope.READY, "contract=1.92.0 provider=stub instance=i1"))
                link.push(sessionsFrame())
                waitUntil(timeoutMillis = 10_000) { controller.state.value.selectedId == sid }
                waitUntil(timeoutMillis = 10_000) { link.statsAsks().isNotEmpty() }
                body(link, controller)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `the open pane asks for the selected session's stats and the tiles show the frame's values`() = withPane { link, controller ->
        assertTrue(link.statsAsks().first().data.contains("\"sessionId\":\"$sid\""), "session_stats_get names the selected session")
        onNodeWithText("Reading this session's record...").assertExists()

        link.push(Envelope(Envelope.SESSION_STATS, fullStats))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.sessionStats[sid] != null }
        waitForIdle()

        onNodeWithTag(STATS_HARNESS_TAG).assertTextEquals("claude")
        tile("duration").assertTextEquals("1d 12h")
        tile("status").assertTextEquals("Idle")
        tile("exchanges").assertTextEquals("507 / 2706")
        tile("tools").assertTextEquals("3.3K")
        tile("models").assertTextEquals("opus-5.5")
        tile("session").assertTextEquals("676e602e")
        tile("cost").assertTextEquals("cost not reported")
        onNodeWithTag(STATS_COPY_ID_TAG).assertExists()
        onNodeWithTag(STATS_CONTEXT_CHART_TAG).assertExists()
        onNodeWithTag(STATS_BILLED_CHART_TAG).assertExists()
        onNodeWithTag(STATS_SOURCE_ROW_TAG_PREFIX + "Bash").assertExists()
        onNodeWithText("593.4K").assertExists()
        onNodeWithText("552").assertExists()

        // Time / Requests toggle switches the axis labels.
        onNodeWithText("#3").assertDoesNotExist()
        onNodeWithTag(STATS_AXIS_REQUESTS_TAG).performClick()
        waitForIdle()
        onNodeWithText("#3").assertExists()

        val out = File("build/reports/stats2/stats-pane.png").apply { parentFile.mkdirs() }
        ImageIO.write(onNodeWithTag("shot").captureToImage().toAwtImage(), "png", out)
        println("STATS-2 screenshot: ${out.absolutePath}")
    }

    @Test fun `a null field shows a dash, never a made-up value`() = withPane { link, controller ->
        link.push(Envelope(Envelope.SESSION_STATS, nullStats))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.sessionStats[sid] != null }
        waitForIdle()

        onNodeWithTag(STATS_HARNESS_TAG).assertTextEquals("pi")
        tile("duration").assertTextEquals("-")
        tile("status").assertTextEquals("-")
        tile("exchanges").assertTextEquals("- / -")
        tile("tools").assertTextEquals("-")
        tile("models").assertTextEquals("-")
        tile("session").assertTextEquals("-")
        tile("billed").assertTextEquals("no usage data for pi")
        tile("cost").assertTextEquals("cost not reported")
        onNodeWithTag(STATS_COPY_ID_TAG).assertDoesNotExist()
        onNodeWithTag(STATS_GAUGE_PERCENT_TAG).assertTextEquals("-")
        assertEquals("Context window: limit unknown", gaugeDescription())
        onNodeWithTag(STATS_CONTEXT_CHART_TAG).assertDoesNotExist()
    }

    @Test fun `the context gauge goes red past the model limit and stays plain under it`() = withPane { link, controller ->
        link.push(Envelope(Envelope.SESSION_STATS, fullStats))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.sessionStats[sid]?.lastContextTokens == 234_310L }
        waitForIdle()
        onNodeWithTag(STATS_GAUGE_PERCENT_TAG).assertTextEquals("117%")
        onNodeWithText("234,310 / 200,000").assertExists()
        assertEquals("Context window: 117% of the limit, over", gaugeDescription())
        assertTrue(StatsView.gaugeOver(controller.state.value.sessionStats.getValue(sid)))

        link.push(Envelope(Envelope.SESSION_STATS, underStats()))
        waitUntil(timeoutMillis = 10_000) { controller.state.value.sessionStats[sid]?.lastContextTokens == 100_000L }
        waitForIdle()
        onNodeWithTag(STATS_GAUGE_PERCENT_TAG).assertTextEquals("50%")
        assertEquals("Context window: 50% of the limit", gaugeDescription())
        onNodeWithTag(STATS_HARNESS_TAG).assertTextEquals("opencode")
    }

    @Test fun `the Stats tab opens the pane, refreshes while open and stops asking once left`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = StatsFakeDaemonClient(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(1200, 900) {
                setContent {
                    val state by controller.state.collectAsState()
                    var tab by remember { mutableStateOf("infantry.cli") }
                    Column {
                        CenterTabBar(state, tab) { tab = it }
                        if (tab == "infantry.stats") StatsPane(state, controller)
                    }
                }
                link.push(Envelope(Envelope.READY, "contract=1.92.0 provider=stub instance=i1"))
                link.push(sessionsFrame())
                waitUntil(timeoutMillis = 10_000) { controller.state.value.selectedId == sid }
                waitForIdle()
                onNodeWithTag(STATS_PANE_TAG).assertDoesNotExist()
                assertEquals(0, link.statsAsks().size, "nothing is asked while the tab is closed")

                onNodeWithText("Stats").assertExists()
                onNodeWithTag("center-tab-STATS").performClick()
                waitUntil(timeoutMillis = 10_000) { link.statsAsks().isNotEmpty() }
                onNodeWithTag(STATS_PANE_TAG).assertExists()

                val first = link.statsAsks().size
                mainClock.advanceTimeBy(STATS_REFRESH_MS + 500)
                waitUntil(timeoutMillis = 10_000) { link.statsAsks().size > first }

                onNodeWithTag("center-tab-OUTPUT").performClick()
                waitForIdle()
                onNodeWithTag(STATS_PANE_TAG).assertDoesNotExist()
                val left = link.statsAsks().size
                mainClock.advanceTimeBy(STATS_REFRESH_MS * 3)
                waitForIdle()
                assertEquals(left, link.statsAsks().size, "leaving the tab stops the refresh")
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `with no session selected the pane shows a one-line empty state and asks nothing`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = StatsFakeDaemonClient(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(760, 600) {
                setContent {
                    val state by controller.state.collectAsState()
                    StatsPane(state, controller)
                }
                link.push(Envelope(Envelope.READY, "contract=1.92.0 provider=stub instance=i1"))
                waitUntil(timeoutMillis = 10_000) { controller.state.value.connection.isLive }
                waitForIdle()
                assertEquals(null, controller.state.value.selectedId)
                onNodeWithText("Select an agent to see its session's statistics.").assertExists()
                mainClock.advanceTimeBy(STATS_REFRESH_MS * 2)
                waitForIdle()
                assertEquals(0, link.statsAsks().size)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test fun `an older daemon is never asked and the pane says why`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val link = StatsFakeDaemonClient(scope)
            val controller = Controller(scope, link)
            runDesktopComposeUiTest(760, 600) {
                setContent {
                    val state by controller.state.collectAsState()
                    StatsPane(state, controller)
                }
                link.push(Envelope(Envelope.READY, "contract=1.91.0 provider=stub instance=i1"))
                link.push(sessionsFrame())
                waitUntil(timeoutMillis = 10_000) { controller.state.value.selectedId == sid }
                waitForIdle()
                onNodeWithText("This daemon does not report session statistics. Update it to contract 1.92.0 or later.").assertExists()
                mainClock.advanceTimeBy(STATS_REFRESH_MS * 2)
                waitForIdle()
                assertEquals(0, link.statsAsks().size)
            }
        } finally {
            scope.cancel()
        }
    }

    private fun statsOf(id: String, harness: String, last: Long, billed: Long = last + 10_000) = com.superiorsoftllc.agenthq.ui.protocol.SessionStats(
        sessionId = id, harness = harness, lastContextTokens = last, modelContextLimit = 1_000_000,
        series = listOf(
            com.superiorsoftllc.agenthq.ui.protocol.StatsPoint(t0, 10_000, 10_000),
            com.superiorsoftllc.agenthq.ui.protocol.StatsPoint(t0 + 1000, last, billed),
        ),
    )

    @Test fun `chart scale - default shows the over scale marker, a per-session change stays in that session, reset restores the default`() = runDesktopComposeUiTest(760, 1100) {
        val scales = com.superiorsoftllc.agenthq.ui.model.ChartScales()
        var current by mutableStateOf("a")
        setContent {
            Box(Modifier.fillMaxSize().background(Palette.Bg)) {
                androidx.compose.runtime.key(current) {
                    StatsBody(if (current == "a") statsOf("a", "pi", 300_000) else statsOf("b", "opencode-go", 300_000), scales)
                }
            }
        }
        waitForIdle()
        // Both sessions start on the shared 200K default; 300K is past it, so the marker shows.
        onNodeWithTag(STATS_OVER_SCALE_TAG_PREFIX + "context").assertExists()
        onNodeWithTag(STATS_LIMIT_PERCENT_TAG).assertTextEquals("30% of limit")
        onNodeWithTag(STATS_SCALE_RESET_TAG_PREFIX + "context").assertHasNoClickAction()

        // Session a picks 500K: the marker goes away.
        onNodeWithTag(STATS_SCALE_CHIP_TAG_PREFIX + "context-500000").performClick()
        waitForIdle()
        onNodeWithTag(STATS_OVER_SCALE_TAG_PREFIX + "context").assertDoesNotExist()

        // Session b never changed: still the default, still over scale.
        current = "b"
        waitForIdle()
        onNodeWithTag(STATS_OVER_SCALE_TAG_PREFIX + "context").assertExists()

        // Back on a: remembered; reset returns it to the default.
        current = "a"
        waitForIdle()
        onNodeWithTag(STATS_OVER_SCALE_TAG_PREFIX + "context").assertDoesNotExist()
        onNodeWithTag(STATS_SCALE_RESET_TAG_PREFIX + "context").performClick()
        waitForIdle()
        onNodeWithTag(STATS_OVER_SCALE_TAG_PREFIX + "context").assertExists()
    }

    @Test fun `chart scale - the billed chart is flagged over its own 2M default`() = runDesktopComposeUiTest(760, 1100) {
        setContent { Box(Modifier.fillMaxSize().background(Palette.Bg)) { StatsBody(statsOf("a", "claude", 100_000, billed = 3_000_000)) } }
        waitForIdle()
        onNodeWithTag(STATS_OVER_SCALE_TAG_PREFIX + "billed").assertExists()
        onNodeWithTag(STATS_OVER_SCALE_TAG_PREFIX + "context").assertDoesNotExist()
    }
}
